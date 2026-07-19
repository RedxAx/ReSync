package restudio.resync.flow.handler.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.TradeProfileService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncRuntimeResourceHandlerTest {
    @AfterEach
    void clearRuntimeAccess() {
        ReSyncRuntimeContentAccess.clear();
    }

    @Test
    void unavailableTradeAuthorityProducesStructuredFailure() {
        TestFlowContext context = execute("trade_apply_trade_profile", Map.of("profile_id", "starter"));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertFalse(result.success());
        assertEquals("TRADE_SERVICE_UNAVAILABLE", context.outputs.get("error_code"));
    }

    @Test
    void invalidTradeRuntimeContextIsDistinguishedFromMissingProfile() {
        ReSyncRuntimeContentAccess.configure(null, new StubTradeProfileService(), null);

        TestFlowContext invalidContext = execute("trade_apply_trade_profile", Map.of("profile_id", "starter"));
        TestFlowContext missing = execute("trade_apply_trade_profile", Map.of("profile_id", "missing"));

        assertEquals("INVALID_TRADE_CONTEXT", invalidContext.outputs.get("error_code"));
        assertEquals("RESOURCE_NOT_FOUND", missing.outputs.get("error_code"));
    }

    @Test
    void emptyValidLootRollIsSuccessful() {
        ReSyncRuntimeContentAccess.configure(new StubLootTableService(), null, null);

        TestFlowContext context = execute("loot_generate", Map.of("loot_table", "empty"));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertTrue(result.success());
        assertEquals("flow", context.triggeredOutput);
        assertEquals(List.of(), context.outputs.get("items"));
    }

    @Test
    void unavailableAndMissingLootTablesProduceDistinctFailures() {
        TestFlowContext unavailable = execute("loot_generate", Map.of("loot_table", "empty"));
        ReSyncRuntimeContentAccess.configure(new StubLootTableService(), null, null);
        TestFlowContext missing = execute("loot_generate", Map.of("loot_table", "missing"));

        assertEquals("LOOT_SERVICE_UNAVAILABLE", unavailable.outputs.get("error_code"));
        assertEquals("RESOURCE_NOT_FOUND", missing.outputs.get("error_code"));
        assertEquals("failed", missing.triggeredOutput);
    }

    @Test
    void unavailableNpcAuthorityProducesStructuredFailure() {
        TestFlowContext context = execute("npc_spawn", Map.of("npc_id", "guide"));

        assertEquals("NPC_SERVICE_UNAVAILABLE", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        HandlerRegistry registry = new HandlerRegistry();
        new ReSyncRuntimeResourceHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("ReSyncRuntimeResourceHandler");
        FlowNode node = new FlowNode("trade.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
        return context;
    }

    private static class StubTradeProfileService extends TradeProfileService {
        private StubTradeProfileService() {
            super(null, null);
        }

        @Override
        public JsonObject get(String id) {
            if (!"starter".equals(id)) return null;
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            return value;
        }
    }

    private static class StubLootTableService extends LootTableService {
        private StubLootTableService() {
            super(null, null);
        }

        @Override
        public JsonObject get(String id) {
            if (!"empty".equals(id)) return null;
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("enabled", true);
            return value;
        }

        @Override
        public List<ItemStack> generate(String id, Map<String, Object> context) {
            return List.of();
        }
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            if (value == null) return defaultValue;
            return type != null ? type.cast(value) : (T) value;
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
