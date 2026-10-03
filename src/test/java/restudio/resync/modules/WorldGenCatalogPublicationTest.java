package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.modules.flow.FlowCatalogPublicationPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.worldgen.registry.WorldGenFlowCatalog;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenCatalogPublicationTest {
    @TempDir
    Path temporary;

    @Test
    void worldGenDefaultsArePublishedAsQualifiedTypedCatalogEntries() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution contribution = WorldGenFlowCatalogContribution.create();
        contribution.apply(definitions, handlers);

        List<NodeDefinition> publishedDefinitions = contribution.definitions();
        NodeDefinition simplex = publishedDefinitions.stream()
            .filter(value -> value.getId().equals("simplex"))
            .findFirst()
            .orElseThrow();
        assertEquals("worldgen", simplex.getOwner());
        assertEquals("worldgen:simplex", simplex.getHandlerConfig().get("worldgenNode"));
        assertTrue(handlers.hasOperation(WorldGenFlowCatalog.HANDLER_ID, "worldgen_node_simplex"));

        NodeDefinition biomeConstant = publishedDefinitions.stream()
            .filter(value -> value.getId().equals("biome_constant"))
            .findFirst()
            .orElseThrow();
        NodeDefinition.PinDefinition biomeInput = biomeConstant.getInputs().stream()
            .filter(value -> value.getDisplayName().equals("biome"))
            .findFirst()
            .orElseThrow();
        NodeDefinition.PinDefinition biomeOutput = biomeConstant.getOutputs().getFirst();
        assertEquals(PinId.of("biome"), biomeInput.getId());
        assertEquals(PinId.of("output_biome"), biomeOutput.getId());
        assertEquals("biome", biomeOutput.getDisplayName());
        RuntimeOperationDescriptor biomeRuntime = FlowModule.runtimeOperationDescriptor(biomeConstant, handlers);
        assertEquals(PinId.of("biome"), biomeRuntime.inputPins().getFirst().id());
        assertEquals(PinId.of("output_biome"), biomeRuntime.outputPins().getFirst().id());

        OwnerId owner = OwnerId.of("worldgen");
        CatalogBundle bundle = catalogBundle(owner, publishedDefinitions, handlers);
        RuntimeBindingRegistry bindings = new RuntimeBindingRegistry();
        ProviderId providerId = ProviderId.of("worldgen-flow");
        ContractRef<ProviderId> provider = ContractRef.of(owner, providerId);
        bindings.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            bundle.requirements().stream()
                .map(requirement -> RuntimeBinding.available(requirement, provider, "1.0.0",
                    ignored -> CompletableFuture.completedFuture(RuntimeResult.success())))
                .toList());

        CatalogVersion version = FlowModule.CATALOG_CONTRACT_VERSION;
        CatalogContribution catalogContribution = CatalogContribution.builder(owner, "1.0.0",
                new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
                    "runtime://worldgen/catalog", "1.0.0", "worldgen-test", "worldgen"))
            .definitions(bundle.nodes())
            .categories(List.copyOf(bundle.categories().values()))
            .capabilities(List.copyOf(bundle.capabilities().values()))
            .optionSources(List.copyOf(bundle.optionSources().values()))
            .runtimeRequirements(bundle.requirements())
            .build();

        var result = new CatalogCompiler(version, CatalogBindingProof.live(bindings)).compile(List.of(catalogContribution), 7);
        assertTrue(result.accepted(), result.diagnostics().toString());
        CatalogSnapshot snapshot = result.snapshot().orElseThrow();
        assertTrue(snapshot.definition(ContractRef.of(owner, NodeId.of("simplex"))).isPresent());

        writeProductionPublication(snapshot, bundle.requirements());
    }

    @Test
    void featureOptionSourceIsReservedForStringCatalogSelectors() {
        List<NodeDefinition> definitions = WorldGenFlowCatalogContribution.create().definitions();

        List<NodeDefinition.PinDefinition> featurePins = definitions.stream()
            .flatMap(definition -> Stream.concat(definition.getInputs().stream(), definition.getOutputs().stream()))
            .filter(pin -> "worldgen:features".equals(pin.getOptionsSource()))
            .toList();

        assertFalse(featurePins.isEmpty());
        assertTrue(featurePins.stream().allMatch(pin -> FlowDataType.STRING.equals(pin.getDataType())));
        NodeDefinition.PinDefinition scatterFeature = definitions.stream()
            .filter(definition -> definition.getId().equals("scatter"))
            .flatMap(definition -> definition.getInputs().stream())
            .filter(pin -> pin.getId().equals(PinId.of("feature")))
            .findFirst()
            .orElseThrow();
        assertNull(scatterFeature.getOptionsSource());
    }

    private CatalogBundle catalogBundle(OwnerId owner, Collection<NodeDefinition> definitions, HandlerRegistry handlers) {
        Map<String, CatalogCategoryDescriptor> categories = new LinkedHashMap<>();
        Map<String, CatalogCapabilityDescriptor> capabilities = new LinkedHashMap<>();
        Map<String, InspectorOptionSource> optionSources = new LinkedHashMap<>();
        List<RuntimeOperationDescriptor> requirements = new ArrayList<>();
        List<CatalogNodeDescriptor> nodes = definitions.stream()
            .map(definition -> {
                try {
                    return FlowModule.catalogNode(owner, definition, categories, capabilities, optionSources,
                        requirements, handlers);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Failed to publish worldgen node " + definition.getId(), exception);
                }
            })
            .toList();
        assertFalse(nodes.isEmpty());
        return new CatalogBundle(nodes, requirements, categories, capabilities, optionSources);
    }

    private void writeProductionPublication(CatalogSnapshot snapshot, List<RuntimeOperationDescriptor> requirements) throws Exception {
        String sourceDirectory = System.getenv("RESYNC_SOURCE_DIR");
        if (sourceDirectory == null || sourceDirectory.isBlank()) {
            return;
        }
        Path outputDirectory = Path.of(sourceDirectory).resolve("build/cross-repo/worldgen");
        Files.createDirectories(outputDirectory);
        ServerId server = ServerId.random();
        Set<ContractRef<CapabilityId>> supportedCapabilities = requirements.stream()
            .map(RuntimeOperationDescriptor::capability)
            .collect(Collectors.toUnmodifiableSet());
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(server, () -> snapshot,
            supportedCapabilities);
        CompressionPool compressionPool = new CompressionPool(2, 1);
        Path dataRoot = Files.createDirectory(temporary.resolve("catalog-receipts"));
        CatalogPublicationReceiptStore receiptStore = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot,
            temporary.resolve("coordination"), new MigrationFence());
        coordinator.register(receiptStore);
        coordinator.seal();
        try {
            Codec codec = new Codec(compressionPool);
            List<byte[]> frames = new ArrayList<>();
            FrameSender frameSender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame.clone());
                }

                @Override
                public void close(int code, String reason) {
                }
            };
            Session session = new Session("worldgen-publication-" + UUID.randomUUID(), "remotely-fixture",
                new ConnectionInfo(null, frameSender, 1));
            FlowPacketSender sender = new FlowPacketSender(codec, ReSyncProtocolContract.CHANNEL_FLOW_ID, Set.of());
            FlowCatalogPublicationPacketHandler packetHandler = new FlowCatalogPublicationPacketHandler(sender, transport,
                receiptStore);

            assertTrue(packetHandler.sendFull(session));
            assertEquals(1, frames.size());
            Codec.Frame frame = codec.decodeFrame(frames.getFirst());
            assertEquals(MessageType.DATA, frame.header.getMessageType());
            DataMessage message = (DataMessage) codec.decodePayload(frame);
            byte[] payload = message.getPayload();
            assertNotNull(payload);
            assertEquals(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION, payload[0]);
            CatalogCachePublication publication = new CatalogCachePublicationCodec().decodeBytes(
                Arrays.copyOfRange(payload, 1, payload.length));
            assertEquals(server, publication.serverId());
            assertEquals(publication, packetHandler.lastValidPublication().orElseThrow());
            writeAtomically(outputDirectory.resolve("publication.packet"), frames.getFirst());
        } finally {
            coordinator.close();
            compressionPool.close();
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record CatalogBundle(List<CatalogNodeDescriptor> nodes,
                                 List<RuntimeOperationDescriptor> requirements,
                                 Map<String, CatalogCategoryDescriptor> categories,
                                 Map<String, CatalogCapabilityDescriptor> capabilities,
                                 Map<String, InspectorOptionSource> optionSources) {
    }
}
