package restudio.resync.flow.handler.generic;

import com.google.gson.JsonObject;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDefinitionHandlerTest {
    @Test
    void successfulBuildersPublishTypedStructuredResults() {
        TestFlowContext context = execute("build_trade", Map.of());

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertTrue(result.success());
        assertTrue((Boolean) context.outputs.get("success"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void invalidBuildersUseTheFailedBranch() {
        TestFlowContext context = execute("build_gui", Map.of("id", ""));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertFalse(result.success());
        assertEquals("RESOURCE_DEFINITION_INVALID", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    @Test
    void npcBuilderPublishesLifecycleAndHookConfiguration() {
        TestFlowContext context = execute("build_npc", Map.of(
            "id", "guide",
            "entity_type", EntityType.PLAYER,
            "spawn_mode", "startup",
            "skin_username", "Notch",
            "main_hand", "minecraft:diamond_sword",
            "interact_flow", "interact-flow",
            "damage_flow", "damage-flow",
            "death_flow", "death-flow",
            "dialog", "welcome"
        ));

        JsonObject definition = assertInstanceOf(JsonObject.class, context.outputs.get("definition"));
        assertEquals("startup", definition.get("spawnMode").getAsString());
        assertEquals("Notch", definition.getAsJsonObject("skin").get("username").getAsString());
        assertEquals("minecraft:diamond_sword", definition.getAsJsonObject("equipment").get("mainHand").getAsString());
        assertEquals("interact-flow", definition.getAsJsonObject("hooks").get("interactAction").getAsString());
        assertEquals("damage-flow", definition.getAsJsonObject("hooks").get("damageAction").getAsString());
        assertEquals("death-flow", definition.getAsJsonObject("hooks").get("deathAction").getAsString());
        assertEquals("welcome", definition.get("dialog").getAsString());
        assertEquals("flow", context.triggeredOutput);
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        ResourceDefinitionHandler handler = new ResourceDefinitionHandler();
        FlowNode node = new FlowNode("resource.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
        return context;
    }

    private static final class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public Object getInputValue(FlowNode node, String pinName) {
            return inputs.get(pinName);
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public Object getOutput(FlowNode node, String pinName) {
            return outputs.get(pinName);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
