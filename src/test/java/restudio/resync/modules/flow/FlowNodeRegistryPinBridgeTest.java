package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.AuthoredNodeMetadata;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodeRegistryPinSerializer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FlowNodeRegistryPinBridgeTest {
    @Test
    void wirePinPublishesStableIdAndAuthoredRuntimeNameSeparately() {
        NodeDefinition.PinDefinition pin = new NodeDefinition.PinBuilder(PinId.of("output_flow"), "Flow",
            NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
            .runtimeName("flow")
            .build();
        Gson gson = new GsonBuilder()
            .registerTypeAdapter(NodeDefinition.PinDefinition.class, new NodeRegistryPinSerializer())
            .create();

        JsonObject value = gson.toJsonTree(pin).getAsJsonObject();

        assertEquals("output_flow", value.get("id").getAsString());
        assertEquals("flow", value.get("name").getAsString());
        assertEquals("flow", value.get("runtimeName").getAsString());
        assertEquals("Flow", value.get("displayName").getAsString());
    }

    @Test
    void registryFingerprintIncludesAuthoredRuntimeName() {
        assertNotEquals(checksum(definition("flow", "Flow", null, null)),
            checksum(definition("renamed_flow", "Flow", null, null)));
    }

    @Test
    void registryFingerprintIncludesPinDisplayName() {
        assertNotEquals(checksum(definition("flow", "Flow", null, null)),
            checksum(definition("flow", "Displayed Flow", null, null)));
    }

    @Test
    void registryFingerprintIncludesAuthoredMetadataAndMigrationMapping() {
        NodeDefinition authored = definition("flow", "Flow", authoredMetadata("Describes the original runtime name."), null);
        NodeDefinition migrated = definition("flow", "Flow", null,
            new NodeDefinition.MigrationMapping(1, 2, true,
                List.of(new NodeDefinition.PinMigrationMapping("legacy_flow", PinId.of("input_flow"),
                    NodeDefinition.PinDirection.INPUT, 1, 2))));

        assertNotEquals(checksum(definition("flow", "Flow", null, null)), checksum(authored));
        assertNotEquals(checksum(definition("flow", "Flow", null, null)), checksum(migrated));
    }

    private String checksum(NodeDefinition definition) {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register("fixture", definition);
        return new FlowNodeRegistryPacketHandler(definitions, null, null, null)
            .buildFullSnapshot().getRegistryChecksum();
    }

    private AuthoredNodeMetadata authoredMetadata(String description) {
        return new AuthoredNodeMetadata("runtime_name", "fixture", "fixture", "active", description,
            "fixture.handler", "typed-selector", "compact");
    }

    private NodeDefinition definition(String runtimeName, String pinDisplayName, AuthoredNodeMetadata authoredMetadata,
                                      NodeDefinition.MigrationMapping migrationMapping) {
        NodeDefinition.Builder builder = new NodeDefinition.Builder("fixture:runtime_name", "Runtime Name",
            NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .input(new NodeDefinition.PinBuilder(PinId.of("input_flow"), pinDisplayName, NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).runtimeName(runtimeName).build());
        if (authoredMetadata != null) {
            builder.authoredMetadata(authoredMetadata);
        }
        if (migrationMapping != null) {
            builder.migrationMapping(migrationMapping);
        }
        return builder.build();
    }
}
