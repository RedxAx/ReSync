package restudio.resync.flow.handler.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNpcHandle;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.TradeProfileService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcRuntime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void staleNpcHandleCannotTeleportTheReplacementInstance() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Location location = new Location(MockBukkit.getMock().addSimpleWorld("npc"), 0, 64, 0);
        try {
            for (boolean packet : List.of(false, true)) {
                String entityId = packet ? "" : UUID.randomUUID().toString();
                FlowNpcHandle active = new FlowNpcHandle("guide", entityId, UUID.randomUUID().toString(), packet, true, "npc", 0, 64, 0, 0, 0);
                StubNpcService service = new StubNpcService(plugin, active);
                try {
                    ReSyncRuntimeContentAccess.configure(null, null, service);
                    FlowNpcHandle stale = new FlowNpcHandle("guide", entityId, UUID.randomUUID().toString(), packet, true, "npc", 0, 64, 0, 0, 0);
                    TestFlowContext rejected = execute("npc_teleport", Map.of("handle", stale, "location", location));
                    assertEquals("NPC_HANDLE_STALE", rejected.outputs.get("error_code"));
                    assertEquals("failed", rejected.triggeredOutput);
                    assertFalse(service.teleported);
                    TestFlowContext accepted = execute("npc_teleport", Map.of("handle", active, "location", location));
                    assertTrue((Boolean) accepted.outputs.get("success"));
                    assertTrue(service.teleported);
                } finally {
                    service.shutdown();
                }
            }
        } finally {
            ReSyncRuntimeContentAccess.clear();
            MockBukkit.unmock();
        }
    }

    @Test
    void canonicalLootSelectorChecksServerOwnerAndTypeBeforeUsingLocalId() {
        ServerId server = new ServerId(UUID.randomUUID());
        ReSyncRuntimeResourceHandler handler = new ReSyncRuntimeResourceHandler(null, server);
        FlowNode node = new FlowNode("loot.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "loot_generate"));
        ReSyncRuntimeContentAccess.configure(new StubLootTableService(), null, null);
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("loot_table")), "empty");
        TestFlowContext accepted = new TestFlowContext(Map.of("loot_table", resource));
        handler.execute(accepted, node);
        assertEquals(true, accepted.outputs.get("success"));
        for (ServerResourceLocator rejected : List.of(
                new ServerResourceLocator(new ServerId(UUID.randomUUID()), resource.type(), resource.id()),
                new ServerResourceLocator(server, ContractRef.of(OwnerId.of("other.plugin"), ResourceTypeId.of("loot_table")), resource.id()),
                new ServerResourceLocator(server, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("trade_profile")), resource.id()))) {
            TestFlowContext context = new TestFlowContext(Map.of("loot_table", rejected));
            handler.execute(context, node);
            assertEquals(false, context.outputs.get("success"));
            assertEquals("failed", context.triggeredOutput);
        }
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

    private static class StubNpcService extends NpcService {
        private final FlowNpcHandle active;
        private boolean teleported;

        private StubNpcService(JavaPlugin plugin, FlowNpcHandle active) {
            super(plugin, null, null, null, null, null, null, PlayerNpcRuntime.disabled("test"));
            this.active = active;
        }

        @Override
        public FlowNpcHandle handle(String id) {
            return active.definitionId().equals(id) ? active : null;
        }

        @Override
        public boolean teleport(String id, Location location) {
            teleported = true;
            return true;
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
