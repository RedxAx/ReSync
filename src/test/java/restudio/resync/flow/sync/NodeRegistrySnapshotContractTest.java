package restudio.resync.flow.sync;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.registry.NodeDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistrySnapshotContractTest {
    @Test
    void preservesVersionIdentityCompatibilityAndCapabilities() {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
        snapshot.setMinimumClientContractVersion(NodeRegistrySnapshot.MINIMUM_SUPPORTED_CONTRACT_VERSION);
        snapshot.setServerIdentity("server-a");
        snapshot.setCompatibleUntil(1234L);
        snapshot.setCapabilities(List.of("nodes", "resources"));
        snapshot.setRegistryDiagnostics(Map.of("rejectedDefinitions", 0));

        assertEquals(2, snapshot.getContractVersion());
        assertEquals(2, snapshot.getMinimumClientContractVersion());
        assertEquals("server-a", snapshot.getServerIdentity());
        assertEquals(1234L, snapshot.getCompatibleUntil());
        assertEquals(List.of("nodes", "resources"), snapshot.getCapabilities());
        assertEquals(0, snapshot.getRegistryDiagnostics().get("rejectedDefinitions"));
    }

    @Test
    void bindsDeltaSnapshotsToOneRegistryBaseline() {
        NodeRegistrySnapshot delta = new NodeRegistrySnapshot();
        delta.setFullSync(false);
        delta.setBaseRegistryChecksum("registry-a");

        assertTrue(delta.canApplyTo("registry-a"));
        assertEquals(false, delta.canApplyTo("registry-b"));

        delta.setFullSync(true);

        assertTrue(delta.canApplyTo("registry-b"));
    }

    @Test
    void transportsRegistryRequestBaselineAndContractVersion() {
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
        request.setRegistryChecksum("registry-a");
        request.setPluginChecksums(Map.of("request", "plugin-a"));

        NodeRegistryRequest restored = new Gson().fromJson(new Gson().toJson(request), NodeRegistryRequest.class);

        assertEquals(2, restored.getContractVersion());
        assertEquals("registry-a", restored.getRegistryChecksum());
        assertEquals("plugin-a", restored.getPluginChecksums().get("request"));
    }

    @Test
    void transportsTemporalClockDomainsAsOptionalNodeMetadata() {
        NodeDefinition definition = new NodeDefinition.Builder("time.current", "Current Time", NodeDefinition.NodeCategory.DATA)
            .clockDomain("wall_time")
            .build();
        NodePluginPayload plugin = new NodePluginPayload();
        plugin.setPluginId("builtin");
        plugin.setNodes(List.of(definition));
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setPlugins(List.of(plugin));

        NodeRegistrySnapshot restored = new Gson().fromJson(new Gson().toJson(snapshot), NodeRegistrySnapshot.class);

        assertEquals("wall_time", restored.getPlugins().getFirst().getNodes().getFirst().getClockDomain());
    }

    @Test
    void transportsExtensionOwnershipAndValidatorInventoryAsCacheSafeDiagnostics() {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setCapabilities(List.of("extensions", "diagnostics", "extension_validators"));
        snapshot.setRegistryDiagnostics(Map.of(
            "extensionContributionInventory", List.of(Map.of("pluginId", "request", "nodes", List.of("request:quest_info"), "validators", List.of("request:quest_references"))),
            "validatorInventory", List.of(Map.of("id", "request:quest_references", "owner", "request"))
        ));

        NodeRegistrySnapshot restored = new Gson().fromJson(new Gson().toJson(snapshot), NodeRegistrySnapshot.class);

        assertTrue(restored.getCapabilities().contains("extension_validators"));
        assertEquals("request:quest_references", inventoryValue(restored, "validatorInventory", "id"));
        assertEquals("request", inventoryValue(restored, "extensionContributionInventory", "pluginId"));
    }

    @Test
    void readsTheSharedCrossRepositoryRegistryFixture() throws Exception {
        Path fixture = Path.of("..", "Remotely", "contracts", "node-registry-v2.fixture.json");
        NodeRegistrySnapshot snapshot = new Gson().fromJson(Files.readString(fixture), NodeRegistrySnapshot.class);

        assertEquals(2, snapshot.getContractVersion());
        assertEquals("server-a", snapshot.getServerIdentity());
        assertEquals("request", snapshot.getPlugins().getFirst().getPluginId());
        assertEquals("request:quest", snapshot.getTypeMetadata().getFirst().getId());
        assertEquals("request:quest", snapshot.getResourceMetadata().getFirst().getTypeId());
        assertTrue(snapshot.canApplyTo("any-baseline"));
        assertEquals("available", snapshot.getResourceMetadata().getFirst().getOperationAvailability().get("save"));
        assertEquals("Quest IDs", snapshot.getOptionSourceMetadata().getFirst().getDisplayName());
        assertEquals("request:quest", snapshot.getOptionSourceMetadata().getFirst().getValueType());
        assertEquals(List.of("player"), snapshot.getOptionSourceMetadata().getFirst().getContextKeys());
        assertEquals("request:quest_references", inventoryValue(snapshot, "validatorInventory", "id"));
    }

    @Test
    void catalogValueTypeResolvesFromAuthoritativeTypeMetadata() {
        FlowTypeMetadata type = new FlowTypeMetadata();
        type.setId("request:quest");
        type.setCatalogSource("request:quest_ids");
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setTypeMetadata(List.of(type));
        snapshot.setOptionSourceMetadata(List.of(new FlowOptionSourceMetadata(
            "request:quest_ids", "request", "SEARCHABLE_LIST", true)));

        FlowOptionSourceMetadata source = snapshot.getOptionSourceMetadata().getFirst();

        assertEquals("Quest IDs", source.getDisplayName());
        assertEquals("request:quest", source.getValueType());
    }

    private Object inventoryValue(NodeRegistrySnapshot snapshot, String inventory, String key) {
        List<?> values = (List<?>) snapshot.getRegistryDiagnostics().get(inventory);
        return ((Map<?, ?>) values.getFirst()).get(key);
    }
}
