package restudio.resync.flow.automation;

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
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableServiceTest {
    private ReSyncJsonResourceStorage storage;
    private VariableService variables;
    private FlowContext context;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = new ReSyncJsonResourceStorage(plugin);
        variables = new VariableService(new AutomationDefinitionRegistry(storage), new FlowValueCodecRegistry());
        FlowGraph graph = new FlowGraph();
        graph.setId("variable-test");
        context = new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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
