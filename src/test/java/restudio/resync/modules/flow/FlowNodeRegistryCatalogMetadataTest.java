package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void deltaSnapshotIsBoundToTheRequestedRegistryBaseline() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
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
        request.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);

        NodeRegistrySnapshot snapshot = handler.buildSnapshot(request);

        assertTrue(snapshot.isFullSync());
        assertEquals("", snapshot.getBaseRegistryChecksum());
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
        request.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
        request.setRegistryChecksum(full.getRegistryChecksum());
        request.setPluginChecksums(Map.of("fixture", pluginChecksum));

        NodeRegistrySnapshot unchanged = handler.buildSnapshot(request);

        assertTrue(unchanged.getPlugins().isEmpty());
        assertTrue(unchanged.getRemovedPlugins().isEmpty());

        definitions.register("fixture", new NodeDefinition.Builder("fixture:one", "One Updated", NodeDefinition.NodeCategory.DATA).build());
        NodeRegistrySnapshot replaced = handler.buildSnapshot(request);

        assertEquals("One Updated", replaced.getPlugins().getFirst().getNodes().getFirst().getDisplayName());
        assertTrue(replaced.getRemovedPlugins().isEmpty());

        definitions.unregisterPlugin("fixture");
        NodeRegistrySnapshot removed = handler.buildSnapshot(request);

        assertTrue(removed.getPlugins().isEmpty());
        assertEquals(List.of("fixture"), removed.getRemovedPlugins());
    }
}
