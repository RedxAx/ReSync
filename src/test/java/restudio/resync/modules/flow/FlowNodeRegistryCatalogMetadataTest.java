package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.core.Session;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowNodeRegistryCatalogMetadataTest {
    @Test
    void registrySnapshotMetadataComesFromRegisteredProviders() {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:profiles";
            }

            @Override
            public String providerId() {
                return "test";
            }

            @Override
            public String widgetType() {
                return "DROPDOWN";
            }

            @Override
            public boolean searchable() {
                return false;
            }

            @Override
            public Set<String> contextKeys() {
                return Set.of("scope", "player");
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("one");
            }
        });
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null, null, catalogs);

        List<FlowOptionSourceMetadata> metadata = handler.buildOptionSourceMetadata();

        assertEquals(1, metadata.size());
        assertEquals("server:test:profiles", metadata.getFirst().getId());
        assertEquals("test", metadata.getFirst().getProvider());
        assertEquals("DROPDOWN", metadata.getFirst().getWidgetType());
        assertEquals(false, metadata.getFirst().isSearchable());
        assertEquals("Profiles", metadata.getFirst().getDisplayName());
        assertEquals("string", metadata.getFirst().getValueType());
        assertEquals(List.of("player", "scope"), metadata.getFirst().getContextKeys());
    }

    @Test
    void resourceSelectorMetadataCarriesOwnerQualifiedTypeOnlyWithAnAuthoritativeAdapter() {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:fixture:quest";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("main");
            }
        });
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register("fixture", fixtureResource());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null, null, catalogs, resources);

        FlowOptionSourceMetadata source = handler.buildOptionSourceMetadata().getFirst();

        assertEquals("fixture:quest", source.getValueType());
        assertEquals("fixture", source.getResourceTypeOwner());
        assertEquals("quest", source.getResourceTypeId());
        assertTrue(source.isTyped());
        assertTrue(source.isAvailable());
        assertFalse(resources.hasAuthoritativeAdapter("other", "fixture:quest"));
        assertFalse(resources.hasAuthoritativeAdapter("fixture", "fixture:other"));
    }

    @Test
    void resourceSelectorMetadataFailsClosedWhenTheResourceAuthorityIsMissing() {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:flow";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("main");
            }
        });
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null, null, catalogs, new FlowResourceRegistry());

        FlowOptionSourceMetadata source = handler.buildOptionSourceMetadata().getFirst();

        assertFalse(source.isTyped());
        assertFalse(source.isAvailable());
        assertEquals("No authoritative lifecycle adapter is registered for this selector", source.getUnavailableReason());
        assertEquals("string", source.getValueType());
    }

    @Test
    void rawStringResourceConversionIsNotAdvertised() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().noneMatch(rule -> "string".equals(rule.getSourceTypeId())
            && "resource_reference".equals(rule.getTargetTypeId())));
    }

    @Test
    void extensionConversionWithoutARegisteredAdapterFailsClosed() {
        ReSyncExtensionData extensions = new ReSyncExtensionData();
        extensions.addConversion("fixture", new FlowConversionRule(
            "uuid", "boolean", "fixture:uuid-to-boolean", true, false, 1, "available"));
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null,
            extensions, null, null);
        handler.setConversionAdapterRegistry(new TypeAdapterRegistry());
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().noneMatch(rule -> "fixture:uuid-to-boolean".equals(rule.getImplementationId())));
    }

    @Test
    void extensionConversionWithARegisteredAdapterIsPublished() {
        ReSyncExtensionData extensions = new ReSyncExtensionData();
        extensions.addConversion("fixture", new FlowConversionRule(
            "uuid", "boolean", "fixture:uuid-to-boolean", true, false, 1, "available"));
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        adapters.register(UUID.class, Boolean.class, value -> !value.toString().isBlank());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null,
            extensions, null, null);
        handler.setConversionAdapterRegistry(adapters);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().anyMatch(rule -> "fixture:uuid-to-boolean".equals(rule.getImplementationId())));
    }

    @Test
    void extensionRawStringResourceConversionIsNeverPublished() {
        ReSyncExtensionData extensions = new ReSyncExtensionData();
        extensions.addConversion("fixture", new FlowConversionRule(
            "string", "resource_reference", "fixture:string-to-resource", true, false, 1, "available"));
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        adapters.register(String.class, FlowResourceReference.class, value -> new FlowResourceReference("server", value, "fixture", false, Map.of()));
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null,
            extensions, null, null);
        handler.setConversionAdapterRegistry(adapters);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().noneMatch(rule -> "fixture:string-to-resource".equals(rule.getImplementationId())));
    }

    @Test
    void parserRawStringResourceConversionIsNeverPublished() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        adapters.registerStringParser(FlowResourceReference.class,
            value -> new FlowResourceReference("server", value, "fixture", false, Map.of()));
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setConversionAdapterRegistry(adapters);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().noneMatch(rule -> "string".equals(rule.getSourceTypeId())
            && "resource_reference".equals(rule.getTargetTypeId())));
    }

    @Test
    void nullStringParserRegistrationFailsClosed() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();

        assertThrows(IllegalArgumentException.class, () -> adapters.registerStringParser(String.class, null));
        assertThrows(IllegalArgumentException.class, () -> adapters.registerStringParser(null, UUID::fromString));
    }

    @Test
    void validStringParserConversionIsPublished() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        adapters.registerStringParser(UUID.class, UUID::fromString);
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setConversionAdapterRegistry(adapters);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();

        handler.populateServerMetadata(snapshot);

        assertTrue(snapshot.getConversionRules().stream().anyMatch(rule -> "string".equals(rule.getSourceTypeId())
            && "uuid".equals(rule.getTargetTypeId())
            && "builtin:parser/string-to-uuid".equals(rule.getImplementationId())));
    }

    @Test
    void typedPublicationAuthorityRejectsLegacyRequestWithoutExplicitCapability() {
        RecordingSender sender = new RecordingSender();
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), sender, null, null);
        handler.requireTypedCatalogPublicationAuthority();

        handler.handleRequest(new Session("session", "client", null), ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)));

        ByteBuffer payload = ByteBuffer.wrap(sender.payload);
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_ERROR, payload.get());
        byte[] message = new byte[payload.getInt()];
        payload.get(message);
        assertEquals(FlowNodeRegistryPacketHandler.LEGACY_AUTHORITY_DISABLED_MESSAGE, new String(message, StandardCharsets.UTF_8));
    }

    @Test
    void typedPublicationAuthorityAllowsOnlyExplicitLegacyCompatibility() {
        RecordingSender sender = new RecordingSender();
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), sender, null, null);
        handler.setCanonicalServerIdentity(new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")));
        handler.requireTypedCatalogPublicationAuthority();
        String request = "{\"compatibilityCapability\":\"" + FlowNodeRegistryPacketHandler.LEGACY_COMPATIBILITY_CAPABILITY + "\"}";

        handler.handleRequest(new Session("session", "client", null), ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));

        assertEquals(ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY, sender.payload[0]);
    }

    @Test
    void typedPublicationAuthorityRejectsLegacyCapabilityArrays() {
        RecordingSender sender = new RecordingSender();
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), sender, null, null);
        handler.requireTypedCatalogPublicationAuthority();

        String request = "{\"capabilities\":[\"" + FlowNodeRegistryPacketHandler.LEGACY_COMPATIBILITY_CAPABILITY + "\"]}";
        handler.handleRequest(new Session("session", "client", null), ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));

        assertEquals(ReSyncProtocolContract.FLOW_PACKET_ERROR, sender.payload[0]);
    }

    @Test
    void deltaSnapshotIsBoundToTheRequestedRegistryBaseline() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        request.setRegistryChecksum("registry-a");

        NodeRegistrySnapshot delta = handler.buildSnapshot(request);

        assertFalse(delta.isFullSync());
        assertEquals("registry-a", delta.getBaseRegistryChecksum());
        assertTrue(delta.canApplyTo("registry-a"));
    }

    @Test
    void requestWithoutACompatibleBaselineReceivesAFullSnapshot() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());

        NodeRegistrySnapshot snapshot = handler.buildSnapshot(request);

        assertTrue(snapshot.isFullSync());
        assertEquals("", snapshot.getBaseRegistryChecksum());
    }

    @Test
    void producedLegacySnapshotContainsEveryCoreRequiredFlowCapability() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertTrue(ReSyncProtocolContract.FLOW_CONTRACT.hasRequiredCapabilities(snapshot.getCapabilities()));
    }

    @Test
    void registryChecksumIncludesCatalogContractMetadata() {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null, null, catalogs);
        String before = handler.computeRegistryChecksum();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:dynamic";
            }

            @Override
            public String providerId() {
                return "test";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("one");
            }
        });

        String after = handler.computeRegistryChecksum();

        assertNotEquals(before, after);
    }

    @Test
    void pluginDeltaDistinguishesUnchangedReplacedAndRemovedContributions() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register("fixture", new NodeDefinition.Builder("fixture:one", "One", NodeDefinition.NodeCategory.DATA).build());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        NodeRegistrySnapshot full = handler.buildFullSnapshot();
        String pluginChecksum = full.getPlugins().getFirst().getChecksum();
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        request.setRegistryChecksum(full.getRegistryChecksum());
        request.setPluginChecksums(Map.of("fixture", pluginChecksum));

        NodeRegistrySnapshot unchanged = handler.buildSnapshot(request);

        assertTrue(unchanged.getPlugins().isEmpty());
        assertTrue(unchanged.getRemovedPlugins().isEmpty());

        definitions.unregisterPlugin("fixture");
        definitions.register("fixture", new NodeDefinition.Builder("fixture:one", "One Updated", NodeDefinition.NodeCategory.DATA).build());
        NodeRegistrySnapshot replaced = handler.buildSnapshot(request);

        assertEquals("One Updated", replaced.getPlugins().getFirst().getNodes().getFirst().getDisplayName());
        assertTrue(replaced.getRemovedPlugins().isEmpty());

        definitions.unregisterPlugin("fixture");
        NodeRegistrySnapshot removed = handler.buildSnapshot(request);

        assertTrue(removed.getPlugins().isEmpty());
        assertEquals(List.of("fixture"), removed.getRemovedPlugins());
    }

    @Test
    void snapshotPublishesActiveCatalogAuthorityAndLosslessTypedContributions() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        Map<String, Object> drop = Map.of(
            "resourceType", "flow",
            "capability", "read",
            "owner", "extension",
            "nodeId", "flow.read",
            "priority", 4,
            "futureField", Map.of("enabled", true));
        Map<String, Object> boundary = Map.of(
            "role", "inputs",
            "owner", "extension",
            "nodeId", "function.start",
            "flowPin", "flow",
            "futureField", List.of("preserved"));
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(8L, "catalog-8",
            List.of(drop), List.of(boundary), Map.of("source", "active")));

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertEquals(8L, snapshot.getCatalogGeneration());
        assertEquals("catalog-8", snapshot.getCatalogChecksum());
        assertEquals(drop, snapshot.getDropContributions().getFirst());
        assertEquals(boundary, snapshot.getFunctionBoundaries().getFirst());
        assertEquals("active", snapshot.getCatalogMetadata().get("source"));
        assertTrue(JsonParser.parseString(new Gson().toJson(snapshot)).getAsJsonObject().has("catalogGeneration"));
        assertEquals("preserved", ((List<?>) snapshot.getFunctionBoundaries().getFirst().get("futureField")).getFirst());
        Map<?, ?> projection = (Map<?, ?>) snapshot.getCatalogMetadata().get("registryProjection");
        assertEquals(snapshot.getCatalogMetadata().get("registryProjectionHash"),
            CanonicalJson.sha256("registry-projection", projection));
    }

    @Test
    void legacySnapshotIdentifiesTypedPublicationAsTheOnlyActiveAuthority() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.requireTypedCatalogPublicationAuthority();
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.activeCatalogMetadata(
            CatalogSnapshot.empty(new CatalogVersion(1, 0))));

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertEquals("catalog-cache-publication", snapshot.getCatalogMetadata().get("activeAuthority"));
        assertEquals(true, snapshot.getCatalogMetadata().get("compatibilityOnly"));
        assertEquals(FlowNodeRegistryPacketHandler.LEGACY_COMPATIBILITY_CAPABILITY,
            snapshot.getCatalogMetadata().get("compatibilityCapability"));
    }

    @Test
    void missingActiveCatalogMetadataDoesNotUseGeneratedAtAsAuthority() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertTrue(snapshot.getGeneratedAt() > 0L);
        assertEquals(-1L, snapshot.getCatalogGeneration());
        assertTrue(snapshot.getCatalogChecksum().isBlank());
    }

    @Test
    void registryPayloadUsesOnlyTheActiveCatalogNodeSet() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register("fixture", new NodeDefinition.Builder("fixture:one", "One", NodeDefinition.NodeCategory.DATA).build());
        definitions.register("fixture", new NodeDefinition.Builder("fixture:two", "Two", NodeDefinition.NodeCategory.DATA).build());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(3L, "catalog-3", List.of(), List.of(),
            Map.of("activeNodeIds", List.of("fixture:one"))));

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertEquals(List.of("fixture:one"), snapshot.getNodeIds());
        assertEquals(List.of("fixture:one"), snapshot.getPlugins().getFirst().getNodes().stream().map(NodeDefinition::getId).toList());
        assertEquals(3L, snapshot.getCatalogGeneration());
        assertEquals("catalog-3", snapshot.getCatalogChecksum());
    }

    @Test
    void registryPayloadUsesOwnerQualifiedActiveReferences() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register("builtin", new NodeDefinition.Builder("fixture:one", "One", NodeDefinition.NodeCategory.DATA)
            .owner("builtin").build());
        definitions.register("extension", new NodeDefinition.Builder("fixture:two", "Two", NodeDefinition.NodeCategory.DATA)
            .owner("extension").build());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(4L, "catalog-4", List.of(), List.of(),
            Map.of("activeNodeReferences", List.of(Map.of("owner", "builtin", "descriptorId", "fixture-one",
                "sourceOwner", "builtin", "nodeId", "fixture:one")))));

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertEquals(List.of("fixture:one"), snapshot.getNodeIds());
        assertEquals(List.of("fixture:one"), snapshot.getPlugins().stream()
            .flatMap(payload -> payload.getNodes().stream())
            .map(NodeDefinition::getId)
            .toList());
    }

    @Test
    void ownerQualifiedCatalogReferenceRejectsMissingRawDefinition() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register("fixture", new NodeDefinition.Builder("fixture:one", "One", NodeDefinition.NodeCategory.DATA)
            .owner("fixture-owner").build());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(6L, "catalog-6", List.of(), List.of(),
            Map.of("activeNodeReferences", List.of(Map.of("owner", "fixture-owner", "nodeId", "fixture:missing")))));

        assertThrows(IllegalStateException.class, handler::buildFullSnapshot);
    }

    @Test
    void ownerQualifiedCatalogReferenceRejectsAmbiguousRawDefinitions() {
        NodeDefinition first = new NodeDefinition.Builder("shared.node", "First", NodeDefinition.NodeCategory.DATA)
            .owner("fixture-owner").build();
        NodeDefinition second = new NodeDefinition.Builder("shared.node", "Second", NodeDefinition.NodeCategory.DATA)
            .owner("fixture-owner").build();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false) {
            @Override
            public Map<String, NodeDefinition> getAllDefinitions() {
                return Map.of("first", first, "second", second);
            }
        };
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(7L, "catalog-7", List.of(), List.of(),
            Map.of("activeNodeReferences", List.of(Map.of("owner", "fixture-owner", "nodeId", "shared.node")))));

        assertThrows(IllegalStateException.class, handler::buildFullSnapshot);
    }

    @Test
    void ownerQualifiedProjectionDoesNotReplaceSameLocalIds() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register("alpha-plugin", new NodeDefinition.Builder("shared.node", "Alpha", NodeDefinition.NodeCategory.DATA)
            .owner("alpha").build());
        definitions.register("beta-plugin", new NodeDefinition.Builder("shared.node", "Beta", NodeDefinition.NodeCategory.DATA)
            .owner("beta").build());
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(definitions, null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(5L, "catalog-5", List.of(), List.of(),
            Map.of("activeNodeReferences", List.of(Map.of("owner", "alpha", "sourceOwner", "beta", "nodeId", "shared.node")))));

        NodeRegistrySnapshot alpha = handler.buildFullSnapshot();

        assertEquals(List.of("Alpha"), alpha.getPlugins().stream()
            .flatMap(payload -> payload.getNodes().stream())
            .map(NodeDefinition::getDisplayName)
            .toList());
        String alphaChecksum = alpha.getRegistryChecksum();

        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(5L, "catalog-5", List.of(), List.of(),
            Map.of("activeNodeReferences", List.of(Map.of("owner", "beta", "sourceOwner", "beta", "nodeId", "shared.node")))));

        NodeRegistrySnapshot beta = handler.buildFullSnapshot();

        assertEquals(List.of("Beta"), beta.getPlugins().stream()
            .flatMap(payload -> payload.getNodes().stream())
            .map(NodeDefinition::getDisplayName)
            .toList());
        assertNotEquals(alphaChecksum, beta.getRegistryChecksum());
    }

    @Test
    void activeCatalogMetadataRejectsUnsupportedPortableValues() {
        assertThrows(IllegalArgumentException.class, () -> new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(1L, "catalog",
            List.of(), List.of(), Map.of("unsupported", new Object())));
    }

    @Test
    void metadataSupplierFailureRetainsTheLastValidCatalogMetadata() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(9L, "catalog-9", List.of(), List.of(), Map.of()));
        handler.setActiveCatalogMetadataSupplier(() -> {
            throw new IllegalStateException("broken");
        });

        assertEquals(9L, handler.buildFullSnapshot().getCatalogGeneration());
        assertEquals("catalog-9", handler.buildFullSnapshot().getCatalogChecksum());
    }

    @Test
    void coreCatalogCanonicalContentIsPublishedAsTheWireAuthority() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        CatalogSnapshot catalog = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.activeCatalogMetadata(catalog));

        NodeRegistrySnapshot snapshot = handler.buildFullSnapshot();

        assertEquals(catalog.canonicalContent(), snapshot.getOpaqueData().get("catalogCanonicalContent"));
        assertEquals(catalog.contentChecksum().canonicalText(), snapshot.getCatalogChecksum());
        assertEquals(catalog.bindingManifestHash().canonicalText(), snapshot.getCatalogProjectionIdentity());
        assertEquals(catalog.bindingManifestHash().canonicalText(), snapshot.getCatalogMetadata().get("bindingManifestHash"));
        Object bindingValue = snapshot.getCatalogMetadata().get("catalogBinding");
        assertTrue(bindingValue instanceof Map<?, ?>);
        assertEquals(catalog.contentChecksum().canonicalText(), ((Map<?, ?>) bindingValue).get("catalogChecksum"));
    }

    @Test
    void tamperedCoreCatalogCanonicalContentFailsClosed() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        CatalogSnapshot catalog = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        FlowNodeRegistryPacketHandler.ActiveCatalogMetadata valid = FlowNodeRegistryPacketHandler.activeCatalogMetadata(catalog);
        Map<String, Object> tampered = new LinkedHashMap<>(valid.metadata());
        tampered.put("catalogCanonicalContent", "{}");
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.ActiveCatalogMetadata.of(valid.generation(), valid.checksum(),
            valid.dropContributions(), valid.functionBoundaries(), tampered));

        assertThrows(IllegalStateException.class, handler::buildFullSnapshot);

        FlowNodeRegistryPacketHandler recoveringHandler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        recoveringHandler.setActiveCatalogMetadata(valid);
        recoveringHandler.setActiveCatalogMetadataSupplier(() -> FlowNodeRegistryPacketHandler.ActiveCatalogMetadata.of(valid.generation(), valid.checksum(),
            valid.dropContributions(), valid.functionBoundaries(), tampered));

        assertThrows(IllegalStateException.class, recoveringHandler::buildFullSnapshot);
    }

    private static final class RecordingSender extends FlowPacketSender {
        private byte[] payload = new byte[0];

        private RecordingSender() {
            super(null, 0, Set.of());
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            this.payload = payload;
            return CatalogFrameSendResult.success();
        }

        @Override
        public boolean sendRawAcknowledged(Session session, byte[] payload, boolean compress) {
            this.payload = payload;
            return true;
        }
    }

    private static FlowResourceAdapter<String> fixtureResource() {
        ReSyncManagedResource descriptor = new ReSyncManagedResource("fixture:quest", "Quest", "Quests", null, true, true);
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return descriptor;
            }

            @Override
            public String get(String id) {
                return id;
            }

            @Override
            public List<String> listIds() {
                return List.of("main");
            }

            @Override
            public String deserialize(String json) {
                return json;
            }

            @Override
            public String id(String value) {
                return value;
            }

            @Override
            public void save(String value) {
            }

            @Override
            public void delete(String id) {
            }
        };
    }
}
