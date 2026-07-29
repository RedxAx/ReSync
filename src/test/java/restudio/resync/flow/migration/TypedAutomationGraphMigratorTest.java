package restudio.resync.flow.migration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedAutomationGraphMigratorTest {
    private FlowStorage flows;
    private ReSyncJsonResourceStorage resources;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        flows = new FlowStorage(plugin);
        resources = new ReSyncJsonResourceStorage(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void migratesLiteralVariableIdentityIntoOneDefinition() {
        FlowGraph graph = graph("variables");
        FlowNode node = new FlowNode("variable.access", 0, 0, new HashMap<>(Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Game Running", "value", true)));
        graph.getNodes().put("variable", node);
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("automation.variable", node.getType());
        FlowResourceReference reference = assertInstanceOf(FlowResourceReference.class, node.getInputValues().get("variable"));
        assertEquals(ReSyncResourceCatalog.VARIABLE_DEFINITION, reference.kind());
        assertNotNull(resources.get(ReSyncResourceCatalog.VARIABLE_DEFINITION, reference.id()));
        assertEquals("set", node.getInputValues().get("action"));
        assertFalse(node.getInputValues().containsKey("scope"));
        assertFalse(node.getInputValues().containsKey("persist"));
    }

    @Test
    void retainsDynamicVariableIdentityOnCompatibilityHandler() {
        FlowGraph graph = graph("dynamic-variable");
        graph.getNodes().put("source", new FlowNode("text.literal", 0, 0, new HashMap<>(Map.of("value", "name"))));
        FlowNode variable = new FlowNode("variable.access", 0, 0, new HashMap<>(Map.of("mode", "get", "scope", "global")));
        graph.getNodes().put("variable", variable);
        graph.getConnections().add(new FlowConnection("source", "value", "variable", "name"));
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("variable.access", variable.getType());
    }

    @Test
    void groupsCompatibleGetAndSetUsagesIntoTheSameDefinition() {
        FlowGraph graph = graph("grouped-variables");
        FlowNode setter = new FlowNode("variable.access", 0, 0, new HashMap<>(Map.of(
            "mode", "set", "scope", "global", "persist", false, "name", "Round", "value", 1)));
        FlowNode getter = new FlowNode("variable.access", 0, 0, new HashMap<>(Map.of(
            "mode", "get", "scope", "global", "persist", false, "name", "Round")));
        graph.getNodes().put("setter", setter);
        graph.getNodes().put("getter", getter);
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        FlowResourceReference setReference = assertInstanceOf(FlowResourceReference.class, setter.getInputValues().get("variable"));
        FlowResourceReference getReference = assertInstanceOf(FlowResourceReference.class, getter.getInputValues().get("variable"));
        assertEquals(setReference.id(), getReference.id());
    }

    @Test
    void migratesRecoverableRepeatingScheduleAndConnections() {
        FlowGraph graph = graph("schedules");
        FlowNode schedule = new FlowNode("schedule.schedule_repeating", 0, 0,
            new HashMap<>(Map.of("flow_id", "heartbeat", "interval_ticks", 40)));
        graph.getNodes().put("schedule", schedule);
        graph.getNodes().put("next", new FlowNode("debug.log", 0, 0, new HashMap<>()));
        graph.getConnections().add(new FlowConnection("schedule", "flow", "next", "flow"));
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("automation.schedule", schedule.getType());
        FlowResourceReference reference = assertInstanceOf(FlowResourceReference.class, schedule.getInputValues().get("schedule"));
        assertNotNull(resources.get(ReSyncResourceCatalog.SCHEDULE_DEFINITION, reference.id()));
        assertEquals("scheduled", graph.getConnections().getFirst().getSourcePin());
    }

    @Test
    void preservesRecoverableScheduleCancellationReference() {
        FlowGraph graph = graph("schedule-cancel");
        FlowNode schedule = new FlowNode("schedule.schedule_repeating", 0, 0,
            new HashMap<>(Map.of("flow_id", "heartbeat", "interval_ticks", 40)));
        FlowNode cancel = new FlowNode("schedule.cancel_task", 0, 0, new HashMap<>());
        graph.getNodes().put("schedule", schedule);
        graph.getNodes().put("cancel", cancel);
        graph.getConnections().add(new FlowConnection("schedule", "task_id", "cancel", "task_id"));
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("automation.schedule", schedule.getType());
        assertEquals("automation.scheduled_task", cancel.getType());
        assertEquals("task", graph.getConnections().getFirst().getSourcePin());
        assertEquals("task", graph.getConnections().getFirst().getTargetPin());
    }

    @Test
    void preservesEnvelopeClassifiedFunctionsDuringMigration() {
        FlowGraph graph = graph("legacy-function");
        graph.setFunction(true);
        graph.getNodes().put("variable", new FlowNode("variable.access", 0, 0, new HashMap<>(Map.of(
            "mode", "set", "scope", "global", "persist", false, "name", "Round", "value", 1))));
        flows.saveGraph(graph);
        graph.setFunction(false);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("function", flows.getGraphResourceType(graph.getId()));
        assertTrue(graph.isFunction());
        assertEquals("automation.variable", graph.getNodes().get("variable").getType());
    }

    @Test
    void repairsFunctionsMisclassifiedByAnEarlierMigration() throws Exception {
        FlowGraph graph = graph("misclassified-function");
        graph.getNodes().put("node", new FlowNode("debug.log", 0, 0, new HashMap<>()));
        flows.saveGraph(graph);
        Path backup = flows.backupGraphForMigration(graph.getId(), "typed-automation-1");
        StorageSafety.writeUtf8Atomic(backup, AssetFileFormat.withResourceType(StorageSafety.readUtf8(backup), "function"));

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        assertEquals("function", flows.getGraphResourceType(graph.getId()));
        assertTrue(graph.isFunction());
        assertEquals(1, graph.getNodes().size());
    }

    private FlowGraph graph(String id) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        return graph;
    }
}
