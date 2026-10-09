package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationDefinition;
import restudio.resync.flow.automation.AutomationInstanceKey;
import restudio.resync.flow.automation.AutomationScope;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimerHandlerTest {
    private JavaPlugin plugin;
    private ReSyncJsonResourceStorage storage;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;
    private AutomationTaskService tasks;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        assetsGate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.compatibility(scope), assetsGate, coordinator);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (tasks != null) {
                tasks.shutdown();
            }
            if (storage != null) {
                storage.closePersistence();
            }
            if (assetsGate != null) {
                assetsGate.quiesce();
            }
            if (coordinator != null) {
                coordinator.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void publishesAuthoredTimerOutputsWithoutLegacyAliases() {
        storage.save(ReSyncResourceCatalog.TIMER_DEFINITION, JsonParser.parseString("""
            {
              "id": "bounded_timer",
              "name": "Bounded Timer",
              "scope": "server",
              "defaultDuration": 1,
              "defaultUnit": "seconds"
            }
            """).getAsJsonObject());
        AutomationDefinitionRegistry definitions = new AutomationDefinitionRegistry(storage);
        tasks = new AutomationTaskService(plugin, definitions);
        TimerHandler handler = new TimerHandler(definitions, tasks);
        FlowNode node = new FlowNode("automation.timer", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "timer"));
        TestFlowContext context = new TestFlowContext(Map.of(
            "timer", "bounded_timer",
            "action", "Start",
            "duration", 2D,
            "unit", "Seconds"
        ));

        handler.execute(context, node);

        assertEquals(Set.of("output_timer", "state", "remaining", "elapsed", "output_duration", "progress", "progress_percent"),
            context.outputs.keySet());
        assertFalse(context.outputs.containsKey("timer"));
        assertFalse(context.outputs.containsKey("duration"));
        assertEquals("active", context.outputs.get("state"));
        assertEquals(2_000L, context.outputs.get("output_duration"));
        double progress = (Double) context.outputs.get("progress");
        assertTrue(progress >= 0D && progress <= 1D);
        assertEquals(progress * 100D, context.outputs.get("progress_percent"));
        assertEquals("active", context.triggeredOutput);
    }

    @Test
    void resolvesTypedTimerLocatorAndRoutesInactiveCheck() {
        storage.save(ReSyncResourceCatalog.TIMER_DEFINITION, JsonParser.parseString("""
            {
              "id": "typed_timer",
              "name": "Typed Timer",
              "scope": "server",
              "defaultDuration": 1,
              "defaultUnit": "seconds"
            }
            """).getAsJsonObject());
        AutomationDefinitionRegistry definitions = new AutomationDefinitionRegistry(storage);
        tasks = new AutomationTaskService(plugin, definitions);
        TimerHandler handler = new TimerHandler(definitions, tasks);
        FlowNode node = new FlowNode("automation.timer", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "timer"));
        ServerResourceLocator timer = new ServerResourceLocator(ServerId.deterministic("timer-handler-test"),
            ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of(ReSyncResourceCatalog.TIMER_DEFINITION)), "typed_timer");
        TestFlowContext context = new TestFlowContext(Map.of(
            "timer", timer,
            "action", "Check"
        ));

        handler.execute(context, node);

        assertEquals("inactive", context.triggeredOutput);
        assertEquals("inactive", context.outputs.get("state"));
        assertEquals("typed_timer", context.outputs.get("output_timer") instanceof FlowResourceReference reference
            ? reference.id() : null);
    }

    @Test
    void serverScopedTimerKeepsTheStartingPlayerAsTheEventOwner() {
        storage.save(ReSyncResourceCatalog.TIMER_DEFINITION, JsonParser.parseString("""
            {
              "id": "owned_timer",
              "name": "Owned Timer",
              "scope": "server",
              "defaultDuration": 1,
              "defaultUnit": "seconds"
            }
            """).getAsJsonObject());
        AutomationDefinitionRegistry definitions = new AutomationDefinitionRegistry(storage);
        tasks = new AutomationTaskService(plugin, definitions);
        TimerHandler handler = new TimerHandler(definitions, tasks);
        FlowNode node = new FlowNode("automation.timer", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "timer"));
        Player player = MockBukkit.getMock().addPlayer();
        TestFlowContext context = new TestFlowContext(player, Map.of(
            "timer", "owned_timer",
            "action", "Start",
            "duration", 2D,
            "unit", "Seconds"
        ));

        handler.execute(context, node);

        AutomationTaskService.TaskSnapshot snapshot = tasks.check(
            new AutomationInstanceKey(AutomationDefinition.Kind.TIMER, "owned_timer", AutomationScope.SERVER, "server"));
        assertEquals(player, snapshot.owner());
        assertEquals("server", snapshot.ownerId());
        assertEquals("active", context.outputs.get("state"));
    }

    private static final class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            this(null, inputs);
        }

        private TestFlowContext(Player player, Map<String, Object> inputs) {
            super(null, player, null);
            this.inputs = inputs;
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
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
