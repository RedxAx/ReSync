package restudio.resync.flow.automation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VariableServiceTest {
    private ReSyncJsonResourceStorage storage;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;
    private VariableService variables;
    private FlowContext context;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        assetsGate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        variables = new VariableService(new AutomationDefinitionRegistry(storage), new FlowValueCodecRegistry());
        FlowGraph graph = new FlowGraph();
        graph.setId("variable-test");
        context = new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
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
    void definitionOwnsDefaultScopeAndRuntimeState() {
        VariableDefinition definition = definition("game_running", "boolean", "server", false, false);

        assertEquals(false, variables.get(context, definition, null));
        assertFalse(variables.exists(context, definition, null));
        assertEquals(true, variables.set(context, definition, null, true));
        assertTrue(variables.exists(context, definition, null));
        assertEquals(true, variables.get(context, definition, null));
        assertEquals(true, variables.delete(context, definition, null));
        assertEquals(false, variables.get(context, definition, null));
        assertFalse(variables.exists(context, definition, null));
    }

    @Test
    void numericUpdatesAreAtomicForOneScopedInstance() {
        VariableDefinition definition = definition("round", "number", "server", false, 0);

        CompletableFuture.allOf(IntStream.range(0, 200).mapToObj(ignored -> CompletableFuture.runAsync(() ->
            variables.updateNumber(context, definition, null, 1D, Double::sum))).toArray(CompletableFuture[]::new)).join();

        assertEquals(200D, variables.get(context, definition, null));
        assertEquals("round", variables.list(context, AutomationScope.SERVER, null).getFirst().id());
    }

    @Test
    void admittedTimerDefinitionsFollowCommittedUpdatesAndDeletion() {
        AutomationDefinitionRegistry definitions = new AutomationDefinitionRegistry(storage);
        JsonObject timer = new JsonObject();
        timer.addProperty("id", "resident_timer");
        timer.addProperty("defaultDuration", 10D);
        storage.save(ReSyncResourceCatalog.TIMER_DEFINITION, timer);
        assertEquals(10D, definitions.timer("resident_timer").defaultDuration());
        assertEquals(10D, definitions.timer("resident_timer").defaultDuration());

        timer.addProperty("defaultDuration", 20D);
        storage.save(ReSyncResourceCatalog.TIMER_DEFINITION, timer);
        assertEquals(20D, definitions.timer("resident_timer").defaultDuration());
        storage.delete(ReSyncResourceCatalog.TIMER_DEFINITION, "resident_timer");
        assertThrows(IllegalArgumentException.class, () -> definitions.timer("resident_timer"));
    }

    private VariableDefinition definition(String id, String type, String scope, boolean persistent, Object defaultValue) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", id);
        json.addProperty("valueType", type);
        json.addProperty("scope", scope);
        json.addProperty("persistent", persistent);
        if (defaultValue instanceof Boolean bool) {
            json.addProperty("defaultValue", bool);
        } else if (defaultValue instanceof Number number) {
            json.addProperty("defaultValue", number);
        } else if (defaultValue != null) {
            json.addProperty("defaultValue", defaultValue.toString());
        }
        storage.save(ReSyncResourceCatalog.VARIABLE_DEFINITION, json);
        return variables.definition(id);
    }
}
