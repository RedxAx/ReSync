package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Adaptation;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Change;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Claim;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Input;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.SourceFile;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;

class AutomationMigrationAdapterTest {
    private static final String GRAPH = "assets/Blueprints/Flows/automation-flow.json";
    private static final String COMMAND = "assets/Blueprints/Commands/automation-command.json";
    private static final String COLLIDING_COMMAND = "assets/Blueprints/Commands/automation-flow.json";
    private static final String RUNTIME = "automation/runtime-state.json";
    private static final String TRIGGERS = "triggers.json";

    @TempDir
    Path root;

    @Test
    void graphDefinitionsAndRuntimeStateTransformAsOneStableAdaptation() throws Exception {
        write(GRAPH, graph(false));
        write(RUNTIME, "{\n  \"revision\": 7,\n  \"mutationId\": \"automation-mutation-007\",\n  \"schedules\": [],\n  \"timers\": []\n}\n".getBytes(StandardCharsets.UTF_8));
        AutomationMigrationAdapter adapter = new AutomationMigrationAdapter();

        Adaptation first = adapter.adapt(input(files(GRAPH, RUNTIME)));

        assertAll(
            () -> assertEquals(2, first.claims().size()),
            () -> assertEquals(4, first.changes().size()),
            () -> assertTrue(first.quarantineRecords().isEmpty()),
            () -> assertTrue(first.changes().stream()
                .filter(change -> change.targetPath().startsWith("assets/Automation/"))
                .allMatch(change -> change.kind().equals("generate") && change.sourcePath().isEmpty())),
            () -> assertEquals(List.of(
                "assets/Automation/Schedules/migrated.schedule.automation-flow.schedule-node.json",
                "assets/Automation/Variables/migrated.variable.fixture_variable.server.persistent.boolean.json",
                GRAPH,
                RUNTIME), first.changes().stream().map(Change::targetPath).sorted().toList()));

        Map<String, Object> migratedGraph = object(CanonicalJson.parse(change(first, GRAPH).targetBytes()));
        assertAll(
            () -> assertEquals("automation-flow", migratedGraph.get("id")),
            () -> assertEquals("flow", migratedGraph.get("resourceType")),
            () -> assertEquals(new BigDecimal("12"), migratedGraph.get("resourceRevision")),
            () -> assertEquals("graph-mutation-012", migratedGraph.get("mutationId")));
        Map<String, Object> nodes = object(migratedGraph.get("nodes"));
        Map<String, Object> setVariable = object(nodes.get("set-variable"));
        assertEquals("automation.variable", setVariable.get("type"));
        assertEquals(new BigDecimal("2"), setVariable.get("version"));
        assertEquals(Map.of("enabled", true), object(object(setVariable.get("handlerConfig")).get("extension")));
        assertEquals("variable_access", object(setVariable.get("handlerConfig")).get("operation"));
        assertEquals("automation.variable", object(nodes.get("get-variable")).get("type"));
        assertEquals(new BigDecimal("2"), object(nodes.get("get-variable")).get("version"));
        Map<String, Object> migratedScheduleNode = object(nodes.get("schedule-node"));
        assertEquals("automation.schedule", migratedScheduleNode.get("type"));
        assertEquals(new BigDecimal("2"), migratedScheduleNode.get("version"));
        assertEquals(Map.of("mode", "opaque"), object(object(migratedScheduleNode.get("handlerConfig")).get("extension")));
        assertEquals("schedule_definition", object(migratedScheduleNode.get("handlerConfig")).get("operation"));
        Map<String, Object> migratedScheduleInputs = object(migratedScheduleNode.get("inputValues"));
        assertAll(
            () -> assertEquals("preserved", migratedScheduleInputs.get("extensionOption")),
            () -> assertFalse(migratedScheduleInputs.containsKey("flow_id")),
            () -> assertFalse(migratedScheduleInputs.containsKey("seconds")));
        List<Object> migratedConnections = array(migratedGraph.get("connections"));
        Map<String, Object> flowConnection = object(migratedConnections.get(0));
        Map<String, Object> variableConnection = object(migratedConnections.get(1));
        Map<String, Object> valueConnection = object(migratedConnections.get(2));
        assertAll(
            () -> assertEquals("connection-flow", flowConnection.get("id")),
            () -> assertEquals("output_flow", flowConnection.get("sourcePin")),
            () -> assertEquals("sink-flow", flowConnection.get("targetNodeId")),
            () -> assertEquals("connection-variable", variableConnection.get("id")),
            () -> assertEquals("output_variable", variableConnection.get("sourcePin")),
            () -> assertEquals("sink-variable", variableConnection.get("targetNodeId")),
            () -> assertEquals("connection-value", valueConnection.get("connectionId")),
            () -> assertEquals(Map.of("nodeId", "get-variable", "pinId", "output_value"), valueConnection.get("source")),
            () -> assertEquals(Map.of("nodeId", "sink-value", "pinId", "input"), valueConnection.get("target")));

        Map<String, Object> variable = object(CanonicalJson.parse(change(first,
            "assets/Automation/Variables/migrated.variable.fixture_variable.server.persistent.boolean.json").targetBytes()));
        Map<String, Object> schedule = object(CanonicalJson.parse(change(first,
            "assets/Automation/Schedules/migrated.schedule.automation-flow.schedule-node.json").targetBytes()));
        Map<String, Object> runtime = object(CanonicalJson.parse(change(first, RUNTIME).targetBytes()));
        assertAll(
            () -> assertEquals("variable_definition", variable.get("resourceType")),
            () -> assertEquals(new BigDecimal("1"), variable.get("resourceRevision")),
            () -> assertTrue(variable.get("mutationId").toString().startsWith("migration-")),
            () -> assertEquals("schedule_definition", schedule.get("resourceType")),
            () -> assertEquals(Map.of("id", "target-flow", "resourceType", "flow"), schedule.get("target")),
            () -> assertEquals(new BigDecimal("7"), runtime.get("revision")),
            () -> assertEquals("automation-mutation-007", runtime.get("mutationId")));

        for (Change change : first.changes()) {
            write(change.targetPath(), change.targetBytes());
        }
        Adaptation second = adapter.adapt(input(files(
            GRAPH,
            RUNTIME,
            "assets/Automation/Variables/migrated.variable.fixture_variable.server.persistent.boolean.json",
            "assets/Automation/Schedules/migrated.schedule.automation-flow.schedule-node.json")));
        assertAll(
            () -> assertEquals(4, second.claims().size()),
            () -> assertTrue(second.changes().isEmpty()),
            () -> assertTrue(second.quarantineRecords().isEmpty()));
    }

    @Test
    void pairedScheduleAndCancelTransformTogetherWithoutBreakingTheTaskEdge() throws Exception {
        write(GRAPH, pairedCancelGraph(false));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertEquals(1, result.claims().size()),
            () -> assertEquals(2, result.changes().size()),
            () -> assertTrue(result.quarantineRecords().isEmpty()));
        Map<String, Object> migratedGraph = object(CanonicalJson.parse(change(result, GRAPH).targetBytes()));
        Map<String, Object> nodes = object(migratedGraph.get("nodes"));
        Map<String, Object> schedule = object(nodes.get("schedule-node"));
        Map<String, Object> cancel = object(nodes.get("cancel-node"));
        Map<String, Object> cancelInputs = object(cancel.get("inputValues"));
        assertAll(
            () -> assertEquals("automation.schedule", schedule.get("type")),
            () -> assertEquals(new BigDecimal("2"), schedule.get("version")),
            () -> assertEquals("automation.scheduled_task", cancel.get("type")),
            () -> assertEquals(new BigDecimal("2"), cancel.get("version")),
            () -> assertEquals(Map.of("token", "retained"), object(object(cancel.get("handlerConfig")).get("extension"))),
            () -> assertEquals("scheduled_task", object(cancel.get("handlerConfig")).get("operation")),
            () -> assertEquals("Cancel", cancelInputs.get("action")),
            () -> assertEquals("preserved", cancelInputs.get("extensionOption")),
            () -> assertFalse(cancelInputs.containsKey("task_id")));
        Map<String, Map<String, Object>> connections = new LinkedHashMap<>();
        for (Object value : array(migratedGraph.get("connections"))) {
            Map<String, Object> connection = object(value);
            connections.put(connection.get("id").toString(), connection);
        }
        assertAll(
            () -> assertEquals("task", connections.get("pair-task").get("sourcePin")),
            () -> assertEquals("task", connections.get("pair-task").get("targetPin")),
            () -> assertEquals("success", connections.get("cancelled-output").get("sourcePin")),
            () -> assertEquals("state", connections.get("status-output").get("sourcePin")),
            () -> assertEquals("output_task", connections.get("task-output").get("sourcePin")),
            () -> assertEquals("inactive", connections.get("flow-output").get("sourcePin")));
    }

    @Test
    void commandAutomationMigratesWithoutATriggerRegistryAndIsStableOnTheSecondPass() throws Exception {
        write(COMMAND, commandGraph(null));
        AutomationMigrationAdapter adapter = new AutomationMigrationAdapter();

        Adaptation first = adapter.adapt(input(files(COMMAND)));

        assertAll(
            () -> assertEquals(1, first.claims().size()),
            () -> assertEquals(2, first.changes().size()),
            () -> assertTrue(first.quarantineRecords().isEmpty()));
        Map<String, Object> graph = object(CanonicalJson.parse(change(first, COMMAND).targetBytes()));
        assertAll(
            () -> assertEquals("command", graph.get("resourceType")),
            () -> assertEquals("automation.schedule", object(object(graph.get("nodes")).get("schedule-node")).get("type")));
        String definition = "assets/Automation/Schedules/migrated.schedule.automation-command.schedule-node.json";
        assertEquals("schedule_definition", object(CanonicalJson.parse(change(first, definition).targetBytes())).get("resourceType"));

        for (Change change : first.changes()) {
            write(change.targetPath(), change.targetBytes());
        }
        Adaptation second = adapter.adapt(input(files(COMMAND, definition)));
        assertAll(
            () -> assertEquals(2, second.claims().size()),
            () -> assertTrue(second.changes().isEmpty()),
            () -> assertTrue(second.quarantineRecords().isEmpty()));
    }

    @Test
    void commandBindingAndAutomationNodesMigrateInOneGraphChange() throws Exception {
        write(COMMAND, commandGraph(null));
        write(TRIGGERS, CanonicalJson.canonicalBytes(List.of(Map.of(
            "type", "command",
            "flowId", "automation-command",
            "context", "catalog-test",
            "resourceRevision", 9,
            "mutationId", "command-mutation-009"))));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(COMMAND, TRIGGERS)));

        assertAll(
            () -> assertEquals(2, result.claims().size()),
            () -> assertEquals(3, result.changes().size()),
            () -> assertTrue(result.quarantineRecords().isEmpty()),
            () -> assertEquals(1L, result.changes().stream().filter(change -> change.targetPath().equals(COMMAND)).count()));
        Map<String, Object> graph = object(CanonicalJson.parse(change(result, COMMAND).targetBytes()));
        assertAll(
            () -> assertEquals("catalog-test", graph.get("commandLabel")),
            () -> assertEquals(false, graph.get("structured")),
            () -> assertEquals(List.of("catalog-test"), graph.get("commandPaths")),
            () -> assertEquals("automation.schedule", object(object(graph.get("nodes")).get("schedule-node")).get("type")),
            () -> assertEquals(List.of(), CanonicalJson.parse(change(result, TRIGGERS).targetBytes())));
    }

    @Test
    void commandBindingFailureSuppressesFlowAutomationDefinitions() throws Exception {
        byte[] flow = graph(false);
        byte[] command = commandGraph("different-command");
        byte[] triggers = CanonicalJson.canonicalBytes(List.of(Map.of(
            "type", "command",
            "flowId", "automation-command",
            "context", "catalog-test",
            "resourceRevision", 9,
            "mutationId", "command-mutation-009")));
        write(GRAPH, flow);
        write(COMMAND, command);
        write(TRIGGERS, triggers);

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH, COMMAND, TRIGGERS)));

        assertAll(
            () -> assertEquals(3, result.claims().size()),
            () -> assertEquals(List.of(COMMAND, GRAPH, TRIGGERS), result.claims().stream().map(Claim::relativePath).toList()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(List.of(COMMAND, GRAPH, TRIGGERS), result.quarantineRecords().stream()
                .map(record -> record.sourceLocation()).sorted().toList()),
            () -> assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.sourceLocation().equals(COMMAND)
                && record.code().equals("MIGRATION.COMMAND_GRAPH_CONFLICT"))),
            () -> assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.sourceLocation().equals(GRAPH)
                && record.code().equals(AutomationMigrationAdapter.FAMILY_QUARANTINED_CODE))),
            () -> assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.sourceLocation().equals(TRIGGERS)
                && record.code().equals(AutomationMigrationAdapter.FAMILY_QUARANTINED_CODE))),
            () -> assertEquals(sha256(flow), sha256(Files.readAllBytes(root.resolve(GRAPH)))),
            () -> assertEquals(sha256(command), sha256(Files.readAllBytes(root.resolve(COMMAND)))),
            () -> assertEquals(sha256(triggers), sha256(Files.readAllBytes(root.resolve(TRIGGERS)))));
    }

    @Test
    void commandAndFlowGraphsShareVariableTypeConflictDetection() throws Exception {
        write(GRAPH, graph(false));
        Map<String, Object> command = object(CanonicalJson.parse(commandGraph(null)));
        command.put("nodes", Map.of("variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "inputValues", Map.of("mode", "set", "scope", "global", "persist", true,
                "name", "Fixture Variable", "value", "text"))));
        write(COMMAND, CanonicalJson.canonicalBytes(command));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH, COMMAND)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.quarantineRecords().stream()
                .anyMatch(record -> AutomationMigrationAdapter.VARIABLE_TYPE_CONFLICT_CODE.equals(record.code()))));
    }

    @Test
    void commandAndFlowGraphsShareGeneratedDefinitionCollisionDetection() throws Exception {
        write(GRAPH, graph(false));
        write(COLLIDING_COMMAND, commandGraph("automation-flow", 7, null));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH, COLLIDING_COMMAND)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.quarantineRecords().stream()
                .anyMatch(record -> AutomationMigrationAdapter.RESOURCE_CONFLICT_CODE.equals(record.code()))));
    }

    @Test
    void pairedCancelWithAnUnrepresentableConnectedOutputQuarantinesTheWholeGraph() throws Exception {
        write(GRAPH, pairedCancelGraph(true));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.DYNAMIC_REFERENCE_CODE,
                result.quarantineRecords().getFirst().code()),
            () -> assertTrue(result.quarantineRecords().getFirst().reason().contains("failed")),
            () -> assertEquals(GRAPH, result.quarantineRecords().getFirst().sourceLocation()));
    }

    @Test
    void connectedLegacyScheduleResultQuarantinesInsteadOfChangingItsType() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(pairedCancelGraph(false)));
        List<Object> connections = array(document.get("connections"));
        connections.add(Map.of("id", "schedule-result", "sourceNodeId", "schedule-node", "sourcePin", "result",
            "targetNodeId", "sink-failed", "targetPin", "input"));
        document.put("connections", connections);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.DYNAMIC_REFERENCE_CODE,
                result.quarantineRecords().getFirst().code()),
            () -> assertTrue(result.quarantineRecords().getFirst().reason().contains("Schedule result")));
    }

    @Test
    void connectedLegacyTaskIdRequiresAPairedCancelInput() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(pairedCancelGraph(false)));
        List<Object> connections = array(document.get("connections"));
        Map<String, Object> taskConnection = object(connections.getFirst());
        taskConnection.put("targetNodeId", "sink-task");
        taskConnection.put("targetPin", "input");
        connections.set(0, taskConnection);
        document.put("connections", connections);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.DYNAMIC_REFERENCE_CODE,
                result.quarantineRecords().getFirst().code()),
            () -> assertTrue(result.quarantineRecords().getFirst().reason().contains("Task ID")));
    }

    @Test
    void unpairedCancelRemainsAvailableWithItsLegacyTaskIdentity() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(pairedCancelGraph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        nodes.remove("schedule-node");
        document.put("nodes", nodes);
        List<Object> connections = array(document.get("connections"));
        connections.removeIf(value -> "pair-task".equals(object(value).get("id")));
        document.put("connections", connections);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertEquals(1, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.quarantineRecords().isEmpty()));
    }

    @Test
    void previouslyMigratedScheduleRepairsItsStillLegacyPairedCancel() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(pairedCancelGraph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        nodes.put("schedule-node", Map.of(
            "type", "automation.schedule",
            "version", 2,
            "inputValues", Map.of("schedule", Map.of(
                "id", "migrated.schedule.automation-flow.schedule-node",
                "resourceType", "schedule_definition",
                "scope", "server"))));
        document.put("nodes", nodes);
        List<Object> connections = array(document.get("connections"));
        Map<String, Object> taskConnection = object(connections.getFirst());
        taskConnection.put("sourcePin", "task");
        connections.set(0, taskConnection);
        document.put("connections", connections);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertEquals(1, result.changes().size()),
            () -> assertTrue(result.quarantineRecords().isEmpty()));
        Map<String, Object> migrated = object(CanonicalJson.parse(change(result, GRAPH).targetBytes()));
        Map<String, Object> migratedNodes = object(migrated.get("nodes"));
        assertEquals("automation.scheduled_task", object(migratedNodes.get("cancel-node")).get("type"));
        assertEquals("task", object(array(migrated.get("connections")).getFirst()).get("targetPin"));
    }

    @Test
    void dynamicIdentityQuarantinesTheWholeGraphWithoutPartialResources() throws Exception {
        write(GRAPH, graph(true));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertEquals(1, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.DYNAMIC_REFERENCE_CODE, result.quarantineRecords().getFirst().code()),
            () -> assertEquals(GRAPH, result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertTrue(result.quarantineRecords().getFirst().affectedReferences().contains("node:set-variable")),
            () -> assertFalse(result.quarantineRecords().getFirst().sourceHash().isBlank()));
    }

    @Test
    void normalizedDefinitionCollisionQuarantinesTheWholeGraph() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        nodes.put("get-variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "inputValues", Map.of("mode", "set", "scope", "global", "persist", true, "name", "Fixture@Variable", "value", true)));
        document.put("nodes", nodes);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertEquals(1, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.RESOURCE_CONFLICT_CODE, result.quarantineRecords().getFirst().code()),
            () -> assertEquals(GRAPH, result.quarantineRecords().getFirst().sourceLocation()));
    }

    @Test
    void conflictingExplicitTypesQuarantineOneSharedVariableIdentity() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        nodes.put("get-variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "inputValues", Map.of("mode", "set", "scope", "global", "persist", true, "name", "Fixture Variable", "value", "text")));
        document.put("nodes", nodes);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.VARIABLE_TYPE_CONFLICT_CODE, result.quarantineRecords().getFirst().code()),
            () -> assertEquals(GRAPH, result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertTrue(result.quarantineRecords().getFirst().affectedReferences().contains("variable:Fixture Variable")));
    }

    @Test
    void unsupportedStructuredScheduleTargetIsNeverRewrittenToFlow() throws Exception {
        Map<String, Object> document = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        nodes.put("schedule-node", Map.of(
            "type", "schedule.interval",
            "version", 1,
            "inputValues", Map.of(
                "flow_id", Map.of("id", "target-command", "resourceType", "command"),
                "seconds", 5,
                "extensionOption", "preserved")));
        document.put("nodes", nodes);
        write(GRAPH, CanonicalJson.canonicalBytes(document));

        Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(AutomationMigrationAdapter.DYNAMIC_REFERENCE_CODE, result.quarantineRecords().getFirst().code()),
            () -> assertEquals(GRAPH, result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertTrue(result.quarantineRecords().getFirst().reason().contains("command")));
    }

    @Test
    void sharedDefinitionExternalCollisionProducesStableTypedFamilyQuarantine() throws Exception {
        String firstGraphPath = "assets/Blueprints/Flows/shared-a.json";
        String secondGraphPath = "assets/Blueprints/Flows/shared-b.json";
        String definitionPath = "assets/Automation/Variables/migrated.variable.shared_variable.server.persistent.boolean.json";
        Path source = Files.createDirectories(root.resolve("shared-definition-source"));
        byte[] firstGraph = sharedVariableGraph("shared-a");
        byte[] secondGraph = sharedVariableGraph("shared-b");
        byte[] externalDefinition = CanonicalJson.canonicalBytes(Map.of(
            "id", "migrated.variable.shared_variable.server.persistent.boolean",
            "mutationId", "external-mutation",
            "name", "External Definition",
            "resourceRevision", 9,
            "resourceType", "variable_definition",
            "valueType", "string"));
        write(source, firstGraphPath, firstGraph);
        write(source, secondGraphPath, secondGraph);
        write(source, definitionPath, externalDefinition);
        Snapshot snapshot = snapshot(source, root.resolve("shared-definition-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new AutomationMigrationAdapter()));
        UpgradeProposal proposal = upgrade.plan(snapshot, window());
        UpgradeProposal repeated = upgrade.plan(snapshot, window());

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertTrue(proposal.quarantineReport().records().stream()
                .anyMatch(record -> AutomationMigrationAdapter.RESOURCE_CONFLICT_CODE.equals(record.code()))),
            () -> assertTrue(proposal.quarantineReport().records().stream().map(record -> record.sourceLocation()).toList()
                .containsAll(List.of(firstGraphPath, secondGraphPath, definitionPath))),
            () -> assertEquals(proposal.quarantineReport().records().stream().map(record -> record.recordId()).sorted().toList(),
                repeated.quarantineReport().records().stream().map(record -> record.recordId()).sorted().toList()),
            () -> assertEquals(sha256(firstGraph), sha256(Files.readAllBytes(snapshot.root().resolve(firstGraphPath)))),
            () -> assertEquals(sha256(secondGraph), sha256(Files.readAllBytes(snapshot.root().resolve(secondGraphPath)))),
            () -> assertEquals(sha256(externalDefinition), sha256(Files.readAllBytes(snapshot.root().resolve(definitionPath)))));
    }

    @Test
    void ambiguousSharedDependencyStagesNeitherGraphNorGeneratedDefinition() throws Exception {
        String firstGraphPath = "assets/Blueprints/Flows/shared-a.json";
        String secondGraphPath = "assets/Blueprints/Flows/shared-b.json";
        String definitionPath = "assets/Automation/Variables/migrated.variable.shared_variable.server.persistent.boolean.json";
        Path source = Files.createDirectories(root.resolve("ambiguous-dependency-source"));
        byte[] firstGraph = sharedVariableGraph("shared-a");
        byte[] secondGraph = sharedVariableGraph("shared-b");
        write(source, firstGraphPath, firstGraph);
        write(source, secondGraphPath, secondGraph);
        Snapshot snapshot = snapshot(source, root.resolve("ambiguous-dependency-snapshot"));
        TypedLifecycleMigrationAdapter competingOwner = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "resync.lifecycle.competing-owner";
            }

            @Override
            public Adaptation adapt(Input input) {
                return input.file(firstGraphPath)
                    .map(source -> Adaptation.claimed(List.of(new Claim(source.relativePath(), source.owner()))))
                    .orElseGet(() -> Adaptation.claimed(List.of()));
            }
        };
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new AutomationMigrationAdapter(), competingOwner));

        UpgradeProposal proposal = upgrade.plan(snapshot, window());

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertEquals(2, proposal.quarantineReport().records().size()),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(firstGraphPath))),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(secondGraphPath))));
        StagedMigration staged = upgrade.stage(
            snapshot.root(),
            root.resolve("ambiguous-dependency-stage"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("automation-review", Instant.parse("2026-01-01T00:00:00Z")));
        assertAll(
            () -> assertFalse(Files.exists(staged.root().resolve(definitionPath))),
            () -> assertFalse(Files.exists(staged.root().resolve(firstGraphPath))),
            () -> assertFalse(Files.exists(staged.root().resolve(secondGraphPath))),
            () -> assertEquals(sha256(firstGraph), sha256(Files.readAllBytes(quarantined(staged, proposal, firstGraphPath)))),
            () -> assertEquals(sha256(secondGraph), sha256(Files.readAllBytes(quarantined(staged, proposal, secondGraphPath)))));
        Snapshot repeatedSnapshot = snapshot(staged.root(), root.resolve("ambiguous-dependency-repeated-snapshot"));
        UpgradeProposal repeated = upgrade.plan(repeatedSnapshot, window());
        assertAll(
            () -> assertTrue(repeated.plan().operations().isEmpty()),
            () -> assertTrue(repeated.quarantineReport().records().isEmpty()));
    }

    @Test
    void invalidRuntimeSuppressesGraphAndDefinitionsThroughAggregateStaging() throws Exception {
        Path source = Files.createDirectories(root.resolve("invalid-runtime-source"));
        byte[] graphBytes = graph(false);
        write(source, GRAPH, graphBytes);
        write(source, RUNTIME, CanonicalJson.canonicalBytes(Map.of("revision", 1, "timers", List.of())));
        Snapshot snapshot = snapshot(source, root.resolve("invalid-runtime-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new AutomationMigrationAdapter()));

        UpgradeProposal proposal = upgrade.plan(snapshot, window());

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertEquals(2, proposal.quarantineReport().records().size()),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(RUNTIME))),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(GRAPH))));
        StagedMigration staged = upgrade.stage(
            snapshot.root(),
            root.resolve("invalid-runtime-stage"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("automation-review", Instant.parse("2026-01-01T00:00:00Z")));
        assertAll(
            () -> assertFalse(Files.exists(staged.root().resolve(GRAPH))),
            () -> assertFalse(Files.exists(staged.root().resolve(RUNTIME))),
            () -> assertEquals(sha256(graphBytes), sha256(Files.readAllBytes(quarantined(staged, proposal, GRAPH)))),
            () -> assertFalse(Files.exists(staged.root().resolve("assets/Automation/Variables"))),
            () -> assertFalse(Files.exists(staged.root().resolve("assets/Automation/Schedules"))));
        Snapshot repeatedSnapshot = snapshot(staged.root(), root.resolve("invalid-runtime-repeated-snapshot"));
        UpgradeProposal repeated = upgrade.plan(repeatedSnapshot, window());
        assertAll(
            () -> assertTrue(repeated.plan().operations().isEmpty()),
            () -> assertTrue(repeated.quarantineReport().records().isEmpty()));
    }

    @Test
    void dynamicGraphSuppressesRuntimeCanonicalizationThroughAggregateStaging() throws Exception {
        Path source = Files.createDirectories(root.resolve("dynamic-graph-source"));
        byte[] runtimeBytes = "{\n  \"revision\": 7,\n  \"mutationId\": \"automation-mutation-007\",\n  \"schedules\": []\n}\n".getBytes(StandardCharsets.UTF_8);
        write(source, GRAPH, graph(true));
        write(source, RUNTIME, runtimeBytes);
        Snapshot snapshot = snapshot(source, root.resolve("dynamic-graph-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new AutomationMigrationAdapter()));

        UpgradeProposal proposal = upgrade.plan(snapshot, window());

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertEquals(2, proposal.quarantineReport().records().size()),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(GRAPH))),
            () -> assertTrue(proposal.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(RUNTIME))));
        StagedMigration staged = upgrade.stage(
            snapshot.root(),
            root.resolve("dynamic-graph-stage"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("automation-review", Instant.parse("2026-01-01T00:00:00Z")));
        assertAll(
            () -> assertFalse(Files.exists(staged.root().resolve(GRAPH))),
            () -> assertFalse(Files.exists(staged.root().resolve(RUNTIME))),
            () -> assertEquals(sha256(runtimeBytes), sha256(Files.readAllBytes(quarantined(staged, proposal, RUNTIME)))));
        Snapshot repeatedSnapshot = snapshot(staged.root(), root.resolve("dynamic-graph-repeated-snapshot"));
        UpgradeProposal repeated = upgrade.plan(repeatedSnapshot, window());
        assertAll(
            () -> assertTrue(repeated.plan().operations().isEmpty()),
            () -> assertTrue(repeated.quarantineReport().records().isEmpty()));
    }

    @Test
    void sourceVersionAliasesAcceptOnlyExplicitSupportedVersions() throws Exception {
        List<Map<String, Object>> accepted = List.of(
            Map.of("version", 1),
            Map.of("definitionVersion", 1),
            Map.of("version", 1, "definitionVersion", 1),
            Map.of("version", 2),
            Map.of("definitionVersion", 2),
            Map.of("version", 2, "definitionVersion", 2));
        for (Map<String, Object> version : accepted) {
            write(GRAPH, graphWithVariableVersion(version));
            Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));
            assertTrue(result.quarantineRecords().isEmpty(), version.toString());
            assertFalse(result.changes().isEmpty(), version.toString());
        }

        List<Map<String, Object>> rejected = List.of(
            Map.<String, Object>of(),
            Map.of("version", 0),
            Map.of("version", new BigDecimal("1.5")),
            Map.of("version", "1"),
            Map.of("version", 3),
            Map.of("version", 1, "definitionVersion", 2));
        for (Map<String, Object> version : rejected) {
            byte[] source = graphWithVariableVersion(version);
            write(GRAPH, source);
            Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));
            assertTrue(result.changes().isEmpty(), version.toString());
            assertEquals(AutomationMigrationAdapter.INVALID_STATE_CODE, result.quarantineRecords().getFirst().code(), version.toString());
            assertEquals(sha256(source), sha256(Files.readAllBytes(root.resolve(GRAPH))), version.toString());
        }
    }

    @Test
    void scheduleHandlerConfigExtensionsSurviveEveryLegacyScheduleOperation() throws Exception {
        Map<String, String> operations = Map.of(
            "schedule.schedule", "schedule",
            "schedule.schedule_repeating", "schedule_repeating",
            "schedule.cron", "cron",
            "schedule.at.time", "schedule_at_time",
            "schedule.interval", "interval");
        for (Map.Entry<String, String> entry : operations.entrySet()) {
            write(GRAPH, graphWithSchedule(entry.getKey(), entry.getValue()));
            Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));
            Map<String, Object> migrated = object(CanonicalJson.parse(change(result, GRAPH).targetBytes()));
            Map<String, Object> schedule = object(object(migrated.get("nodes")).get("schedule-node"));
            Map<String, Object> config = object(schedule.get("handlerConfig"));
            assertEquals("schedule_definition", config.get("operation"), entry.getKey());
            assertEquals(Map.of("source", entry.getKey()), object(config.get("extension")), entry.getKey());
        }
    }

    @Test
    void invalidHandlerConfigsQuarantineVariableScheduleAndPairedCancelGraphs() throws Exception {
        Map<String, Object> absent = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> absentNodes = object(absent.get("nodes"));
        Map<String, Object> absentNode = object(absentNodes.get("set-variable"));
        absentNode.remove("handlerConfig");
        absentNodes.put("set-variable", absentNode);
        absent.put("nodes", absentNodes);
        write(GRAPH, CanonicalJson.canonicalBytes(absent));
        assertTrue(new AutomationMigrationAdapter().adapt(input(files(GRAPH))).quarantineRecords().isEmpty());

        Map<String, Object> variable = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> variableNodes = object(variable.get("nodes"));
        Map<String, Object> variableNode = object(variableNodes.get("set-variable"));
        variableNode.put("handlerConfig", Map.of("operation", "different"));
        variableNodes.put("set-variable", variableNode);
        variable.put("nodes", variableNodes);

        Map<String, Object> schedule = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> scheduleNodes = object(schedule.get("nodes"));
        Map<String, Object> scheduleNode = object(scheduleNodes.get("schedule-node"));
        scheduleNode.put("handlerConfig", "invalid");
        scheduleNodes.put("schedule-node", scheduleNode);
        schedule.put("nodes", scheduleNodes);

        Map<String, Object> cancel = object(CanonicalJson.parse(pairedCancelGraph(false)));
        Map<String, Object> cancelNodes = object(cancel.get("nodes"));
        Map<String, Object> cancelNode = object(cancelNodes.get("cancel-node"));
        cancelNode.put("handlerConfig", Map.of("operation", "different"));
        cancelNodes.put("cancel-node", cancelNode);
        cancel.put("nodes", cancelNodes);

        for (byte[] source : List.of(CanonicalJson.canonicalBytes(variable), CanonicalJson.canonicalBytes(schedule),
            CanonicalJson.canonicalBytes(cancel))) {
            write(GRAPH, source);
            Adaptation result = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));
            assertTrue(result.changes().isEmpty());
            assertEquals(AutomationMigrationAdapter.INVALID_STATE_CODE, result.quarantineRecords().getFirst().code());
        }
    }

    @Test
    void equivalentDefinitionAvoidsGenerationWhileCaseAliasedDefinitionQuarantines() throws Exception {
        byte[] original = graph(false);
        write(GRAPH, original);
        Adaptation first = new AutomationMigrationAdapter().adapt(input(files(GRAPH)));
        String definitionPath = "assets/Automation/Variables/migrated.variable.fixture_variable.server.persistent.boolean.json";
        String schedulePath = "assets/Automation/Schedules/migrated.schedule.automation-flow.schedule-node.json";
        byte[] definition = change(first, definitionPath).targetBytes();
        write(definitionPath, definition);
        write(schedulePath, change(first, schedulePath).targetBytes());
        write(GRAPH, original);

        Adaptation equivalent = new AutomationMigrationAdapter().adapt(input(files(GRAPH, definitionPath, schedulePath)));
        assertAll(
            () -> assertTrue(equivalent.quarantineRecords().isEmpty()),
            () -> assertEquals(List.of(GRAPH), equivalent.changes().stream().map(Change::targetPath).toList()));

        String aliasedPath = "assets/Automation/Variables/MIGRATED.VARIABLE.FIXTURE_VARIABLE.SERVER.PERSISTENT.BOOLEAN.json";
        Files.delete(root.resolve(definitionPath));
        write(aliasedPath, definition);
        Adaptation aliased = new AutomationMigrationAdapter().adapt(input(files(GRAPH, aliasedPath, schedulePath)));
        assertAll(
            () -> assertTrue(aliased.changes().isEmpty()),
            () -> assertTrue(aliased.quarantineRecords().stream()
                .anyMatch(record -> AutomationMigrationAdapter.RESOURCE_CONFLICT_CODE.equals(record.code()))),
            () -> assertTrue(aliased.quarantineRecords().stream().anyMatch(record -> aliasedPath.equals(record.sourceLocation()))));
    }

    private byte[] graph(boolean dynamic) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("set-variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "handlerConfig", Map.of("operation", "variable_access", "extension", Map.of("enabled", true)),
            "inputValues", Map.of("mode", "set", "scope", "global", "persist", true, "name", "Fixture Variable", "value", true)));
        nodes.put("get-variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "inputValues", Map.of("mode", "get", "scope", "global", "persist", true, "name", "Fixture Variable")));
        nodes.put("schedule-node", Map.of(
            "type", "schedule.interval",
            "version", 1,
            "handlerConfig", Map.of("operation", "interval", "extension", Map.of("mode", "opaque")),
            "inputValues", Map.of("flow_id", "target-flow", "seconds", 5, "extensionOption", "preserved")));
        nodes.put("sink-flow", Map.of("type", "test.sink", "version", 1, "inputValues", Map.of()));
        nodes.put("sink-value", Map.of("type", "test.sink", "version", 1, "inputValues", Map.of()));
        nodes.put("sink-variable", Map.of("type", "test.sink", "version", 1, "inputValues", Map.of()));
        List<Object> connections = new ArrayList<>();
        connections.add(Map.of(
            "id", "connection-flow",
            "sourceNodeId", "set-variable",
            "sourcePin", "flow",
            "targetNodeId", "sink-flow",
            "targetPin", "input"));
        connections.add(Map.of(
            "id", "connection-variable",
            "sourceNodeId", "set-variable",
            "sourcePin", "variable",
            "targetNodeId", "sink-variable",
            "targetPin", "input"));
        connections.add(Map.of(
            "connectionId", "connection-value",
            "source", Map.of("nodeId", "get-variable", "pinId", "value"),
            "target", Map.of("nodeId", "sink-value", "pinId", "input")));
        if (dynamic) {
            nodes.put("source", Map.of("type", "text.literal", "version", 1, "inputValues", Map.of("value", "Dynamic")));
            connections.add(Map.of("sourceNodeId", "source", "sourcePin", "value", "targetNodeId", "set-variable", "targetPin", "mode"));
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("connections", connections);
        graph.put("id", "automation-flow");
        graph.put("mutationId", "graph-mutation-012");
        graph.put("nodes", nodes);
        graph.put("resourceRevision", 12);
        graph.put("resourceType", "flow");
        return CanonicalJson.canonicalBytes(graph);
    }

    private byte[] graphWithVariableVersion(Map<String, Object> version) {
        Map<String, Object> document = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        Map<String, Object> node = object(nodes.get("set-variable"));
        node.remove("version");
        node.remove("definitionVersion");
        node.putAll(version);
        nodes.put("set-variable", node);
        document.put("nodes", nodes);
        return CanonicalJson.canonicalBytes(document);
    }

    private byte[] graphWithSchedule(String type, String operation) {
        Map<String, Object> document = object(CanonicalJson.parse(graph(false)));
        Map<String, Object> nodes = object(document.get("nodes"));
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("type", type);
        schedule.put("version", 1);
        schedule.put("handlerConfig", Map.of("operation", operation, "extension", Map.of("source", type)));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flow_id", "target-flow");
        switch (type) {
            case "schedule.schedule" -> {
                values.put("time_string", "12:00");
                values.put("time_zone", "UTC");
            }
            case "schedule.schedule_repeating" -> values.put("interval_ticks", 20);
            case "schedule.cron" -> {
                values.put("expression", "0 12 * * *");
                values.put("time_zone", "UTC");
            }
            case "schedule.at.time" -> {
                values.put("time", "2026-01-01T12:00:00Z");
                values.put("time_zone", "UTC");
            }
            case "schedule.interval" -> values.put("seconds", 5);
            default -> throw new IllegalArgumentException(type);
        }
        schedule.put("inputValues", values);
        nodes.put("schedule-node", schedule);
        document.put("nodes", nodes);
        return CanonicalJson.canonicalBytes(document);
    }

    private static byte[] pairedCancelGraph(boolean unsafeOutput) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("schedule-node", Map.of(
            "type", "schedule.interval",
            "version", 1,
            "inputValues", Map.of("flow_id", "target-flow", "seconds", 5)));
        nodes.put("cancel-node", Map.of(
            "type", "schedule.cancel_task",
            "version", 1,
            "handlerConfig", Map.of("operation", "cancel_task", "extension", Map.of("token", "retained")),
            "inputValues", Map.of("task_id", "legacy-task", "extensionOption", "preserved")));
        for (String id : List.of("cancelled", "status", "task", "flow", "failed")) {
            nodes.put("sink-" + id, Map.of("type", "test.sink", "version", 1, "inputValues", Map.of()));
        }
        List<Object> connections = new ArrayList<>();
        connections.add(Map.of("id", "pair-task", "sourceNodeId", "schedule-node", "sourcePin", "task_id",
            "targetNodeId", "cancel-node", "targetPin", "task_id"));
        connections.add(Map.of("id", "cancelled-output", "sourceNodeId", "cancel-node", "sourcePin", "cancelled",
            "targetNodeId", "sink-cancelled", "targetPin", "input"));
        connections.add(Map.of("id", "status-output", "sourceNodeId", "cancel-node", "sourcePin", "status",
            "targetNodeId", "sink-status", "targetPin", "input"));
        connections.add(Map.of("id", "task-output", "sourceNodeId", "cancel-node", "sourcePin", "task",
            "targetNodeId", "sink-task", "targetPin", "input"));
        connections.add(Map.of("id", "flow-output", "sourceNodeId", "cancel-node", "sourcePin", "output_flow",
            "targetNodeId", "sink-flow", "targetPin", "input"));
        if (unsafeOutput) {
            connections.add(Map.of("id", "failed-output", "sourceNodeId", "cancel-node", "sourcePin", "failed",
                "targetNodeId", "sink-failed", "targetPin", "input"));
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("connections", connections);
        graph.put("id", "automation-flow");
        graph.put("mutationId", "graph-mutation-paired-cancel");
        graph.put("nodes", nodes);
        graph.put("resourceRevision", 12);
        graph.put("resourceType", "flow");
        return CanonicalJson.canonicalBytes(graph);
    }

    private static byte[] commandGraph(String commandLabel) {
        return commandGraph("automation-command", 5, commandLabel);
    }

    private static byte[] commandGraph(String id, int seconds, String commandLabel) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("connections", List.of());
        graph.put("id", id);
        graph.put("mutationId", "command-mutation-009");
        graph.put("nodes", Map.of("schedule-node", Map.of(
            "type", "schedule.interval",
            "version", 1,
            "inputValues", Map.of("flow_id", "target-flow", "seconds", seconds))));
        graph.put("resourceRevision", 9);
        graph.put("resourceType", "command");
        if (commandLabel != null) {
            graph.put("commandLabel", commandLabel);
        }
        return CanonicalJson.canonicalBytes(graph);
    }

    private static byte[] sharedVariableGraph(String id) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("connections", List.of());
        graph.put("id", id);
        graph.put("mutationId", "mutation-" + id);
        graph.put("nodes", Map.of("variable", Map.of(
            "type", "variable.access",
            "version", 1,
            "inputValues", Map.of("mode", "set", "scope", "global", "persist", true, "name", "Shared Variable", "value", true))));
        graph.put("resourceRevision", 1);
        graph.put("resourceType", "flow");
        return CanonicalJson.canonicalBytes(graph);
    }

    private Input input(List<SourceFile> files) {
        return new Input(root, SnapshotMetadata.preflight(), "0".repeat(64), files);
    }

    private List<SourceFile> files(String... paths) throws Exception {
        List<SourceFile> files = new ArrayList<>();
        for (String path : paths) {
            byte[] bytes = Files.readAllBytes(root.resolve(path));
            String owner = path.equals(RUNTIME) ? "resync.runtime"
                : path.equals(TRIGGERS) ? ProductionPersistenceOwners.TRIGGERS
                : path.startsWith("assets/Blueprints/Commands/") ? ProductionPersistenceOwners.FLOW_ASSETS
                : "resync.assets";
            files.add(new SourceFile(path, bytes.length, sha256(bytes), owner));
        }
        return files;
    }

    private void write(String path, byte[] bytes) throws IOException {
        write(root, path, bytes);
    }

    private static void write(Path base, String path, byte[] bytes) throws IOException {
        Path target = base.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private Snapshot snapshot(Path source, Path staging) throws IOException {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "automation";
            }

            @Override
            public Path root() {
                return source;
            }
        });
        return new SnapshotService(new MigrationFence()).create(
            source,
            staging,
            new SnapshotMetadata(1, "automation-snapshot", Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of()),
            participants);
    }

    private static UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-build");
    }

    private static Path quarantined(StagedMigration staged, UpgradeProposal proposal, String sourcePath) {
        String recordId = proposal.quarantineReport().records().stream()
            .filter(record -> record.sourceLocation().equals(sourcePath))
            .findFirst()
            .orElseThrow()
            .recordId();
        return staged.root().resolve(".quarantine/migration").resolve(recordId).resolve(sourcePath);
    }

    private static Change change(Adaptation adaptation, String targetPath) {
        return adaptation.changes().stream().filter(change -> change.targetPath().equals(targetPath)).findFirst().orElseThrow();
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError("Expected object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new AssertionError("Expected array");
        }
        return new ArrayList<>(list);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
