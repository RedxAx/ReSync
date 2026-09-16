package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.ManagedResourceFileContract;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.StandaloneUpgradePlanner;
import restudio.resync.upgrade.StandaloneUpgradeStager;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TypedAutomationParityTest {
    @TempDir
    Path temporary;

    @Test
    void preservesVariableScheduleAndCancellationSemanticsBeforeGenericAliases() throws Exception {
        Fixture fixture = fixture("automation", List.of(automationGraph("main")), List.of());
        LegacyResyncSnapshotAdapter composite = new LegacyResyncSnapshotAdapter();
        OfflineUpgradeSnapshotInput snapshotInput = input(fixture.snapshot());
        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = composite.transform(snapshotInput);

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        assertTrue(result.files().stream().anyMatch(file -> file.targetPath().equals("assets/Automation/Variables/migrated.variable.game_running.server.persistent.boolean.json")
            && file.operationType() == MigrationOperationType.GENERATE), result.files().toString());
        assertTrue(result.files().stream().anyMatch(file -> file.targetPath().equals("assets/Automation/Schedules/migrated.schedule.main.schedule.json")
            && file.operationType() == MigrationOperationType.GENERATE), result.files().toString());

        Map<String, Object> graph = object(file(result, "assets/Blueprints/Flows/main.json").bytes());
        Map<String, Object> nodes = object(graph.get("nodes"));
        Map<String, Object> variable = object(nodes.get("set"));
        assertEquals("automation.variable", variable.get("type"));
        assertEquals(reference("variable_definition", "migrated.variable.game_running.server.persistent.boolean"), variable.get("inputValues") instanceof Map<?, ?> values
            ? object(values).get("variable") : null);
        Map<String, Object> schedule = object(nodes.get("schedule"));
        assertEquals("automation.schedule", schedule.get("type"));
        Map<String, Object> cancel = object(nodes.get("cancel"));
        assertEquals("automation.scheduled_task", cancel.get("type"));
        List<?> connections = list(graph.get("connections"));
        assertTrue(connections.stream().map(TypedAutomationParityTest::object)
            .anyMatch(connection -> "task".equals(connection.get("sourcePin")) && "task".equals(connection.get("targetPin"))), connections.toString());

        Map<String, Object> scheduleDefinition = ManagedResourceFileContract.decode(file(result,
            "assets/Automation/Schedules/migrated.schedule.main.schedule.json").bytes());
        assertTrue(ManagedResourceFileContract.isValid("schedule_definition", "migrated.schedule.main.schedule", scheduleDefinition));
        assertEquals(2.5, ((Number) scheduleDefinition.get("duration")).doubleValue(), 0.00001);
        assertEquals("seconds", scheduleDefinition.get("unit"));
        Map<String, Object> variableDefinition = ManagedResourceFileContract.decode(file(result,
            "assets/Automation/Variables/migrated.variable.game_running.server.persistent.boolean.json").bytes());
        assertTrue(ManagedResourceFileContract.isValid("variable_definition", "migrated.variable.game_running.server.persistent.boolean", variableDefinition));
        assertEquals("Migrated Variable", variableDefinition.get("description"));

        Map<String, Object> project = object(file(result, "assets/project.json").bytes());
        assertTrue(list(project.get("resources")).stream().map(TypedAutomationParityTest::object)
            .anyMatch(resource -> "variable_definition".equals(resource.get("type"))));
        assertTrue(list(project.get("resources")).stream().map(TypedAutomationParityTest::object)
            .anyMatch(resource -> "schedule_definition".equals(resource.get("type"))));
        assertTrue(result.files().stream().noneMatch(file -> file.targetPath().contains("runtime-state")));
    }

    @Test
    void convertsVersionedLegacyCancellationToExactAuthoredScheduledTaskContract() throws Exception {
        Map<String, Object> cancel = node("schedule.cancel_task", Map.of());
        cancel.put("version", 3);
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("cancel", cancel);
        nodes.put("schedule", node("schedule.interval", Map.of("flow_id", "main", "seconds", 2)));
        nodes.put("sink", node("test.sink", Map.of()));
        Fixture fixture = fixture("versioned-cancel", List.of(graph("main", nodes, List.of(
            connection("schedule", "task_id", "cancel", "task_id"),
            connection("cancel", "task_id", "sink", "value")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        Map<String, Object> graph = object(file(result, "assets/Blueprints/Flows/main.json").bytes());
        Map<String, Object> migratedCancel = object(object(graph.get("nodes")).get("cancel"));
        assertEquals("automation.scheduled_task", migratedCancel.get("type"));
        assertEquals(2, ((Number) migratedCancel.get("version")).intValue());
        assertEquals("Cancel", object(migratedCancel.get("inputValues")).get("action"));
        List<?> connections = list(graph.get("connections"));
        assertTrue(connections.stream().map(TypedAutomationParityTest::object)
            .anyMatch(connection -> "schedule".equals(connection.get("sourceNodeId"))
                && "task".equals(connection.get("sourcePin"))
                && "cancel".equals(connection.get("targetNodeId"))
                && "task".equals(connection.get("targetPin"))), connections.toString());
        assertTrue(connections.stream().map(TypedAutomationParityTest::object)
            .anyMatch(connection -> "cancel".equals(connection.get("sourceNodeId"))
                && "output_task".equals(connection.get("sourcePin"))), connections.toString());
    }

    @Test
    void mapsEveryAuthoredAutomationOutputToStableOutputPinsAndExactVersions() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, List<String>> expectedOutputs = new LinkedHashMap<>();
        Map<String, Integer> expectedVersions = new LinkedHashMap<>();
        authoredNode(nodes, expectedOutputs, expectedVersions, "variable", "automation.variable", 1,
            List.of("flow", "variable", "value"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "timer", "automation.timer", 2,
            List.of("timer", "duration"), 3);
        authoredNode(nodes, expectedOutputs, expectedVersions, "schedule", "automation.schedule", 1,
            List.of("schedule"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "task", "automation.scheduled_task", 1,
            List.of("task"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "variableEvent", "event.variable.changed", 1,
            List.of("variable"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "timerEvent", "event.timer", 1,
            List.of("timer", "duration"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "taskEvent", "event.scheduled_task", 1,
            List.of("schedule"), 2);
        authoredNode(nodes, expectedOutputs, expectedVersions, "scheduleEvent", "event.schedule", 1,
            List.of("schedule"), 2);

        List<Map<String, Object>> connections = new ArrayList<>();
        int sinkIndex = 0;
        for (Map.Entry<String, List<String>> entry : expectedOutputs.entrySet()) {
            for (String output : entry.getValue()) {
                String sink = "sink" + sinkIndex++;
                nodes.put(sink, node("test.sink", Map.of()));
                connections.add(connection(entry.getKey(), output, sink, "value"));
            }
        }
        Fixture fixture = fixture("authored-output-pins", List.of(graph("main", nodes, connections)), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        Map<String, Object> graph = object(file(result, "assets/Blueprints/Flows/main.json").bytes());
        Map<String, Object> transformedNodes = object(graph.get("nodes"));
        List<?> transformedConnections = list(graph.get("connections"));
        for (Map.Entry<String, List<String>> entry : expectedOutputs.entrySet()) {
            Map<String, Object> transformed = object(transformedNodes.get(entry.getKey()));
            assertEquals(expectedVersions.get(entry.getKey()).intValue(), ((Number) transformed.get("version")).intValue(), entry.getKey());
            List<String> actual = transformedConnections.stream().map(TypedAutomationParityTest::object)
                .filter(connection -> entry.getKey().equals(connection.get("sourceNodeId")))
                .map(connection -> stablePin(String.valueOf(connection.get("sourcePin")))).toList();
            List<String> expected = entry.getValue().stream().map(output -> switch (entry.getKey()) {
                case "variable" -> switch (output) {
                    case "flow" -> "output_flow";
                    case "variable" -> "output_variable";
                    case "value" -> "output_value";
                    default -> throw new IllegalArgumentException(output);
                };
                case "timer" -> switch (output) {
                    case "timer" -> "output_timer";
                    case "duration" -> "output_duration";
                    default -> throw new IllegalArgumentException(output);
                };
                case "schedule" -> "output_schedule";
                case "task" -> "output_task";
                case "variableEvent" -> "output_variable";
                case "timerEvent" -> output.equals("timer") ? "output_timer" : "output_duration";
                case "taskEvent", "scheduleEvent" -> "output_schedule";
                default -> throw new IllegalArgumentException(entry.getKey());
            }).toList();
            assertEquals(expected, actual, entry.getKey());
        }
    }

    @Test
    void leavesMissingAndUnsupportedAuthoredSchemaVersionsUnconverted() throws Exception {
        Map<String, Object> missing = node("automation.timer", Map.of());
        assertUnconvertedAuthoredVersion("authored-version-missing", missing);

        Map<String, Object> sourceVersion = node("automation.timer", Map.of());
        sourceVersion.put("version", 1);
        assertUnconvertedAuthoredVersion("authored-version-one", sourceVersion);

        Map<String, Object> unsupported = node("automation.timer", Map.of());
        unsupported.put("version", 9);
        assertUnconvertedAuthoredVersion("authored-version-unsupported", unsupported);
    }

    @Test
    void rejectsOldAndNewOutputAliasesThatWouldCollide() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("variable", authored("automation.variable", 1));
        nodes.put("oldSink", node("test.sink", Map.of()));
        nodes.put("newSink", node("test.sink", Map.of()));
        Fixture fixture = fixture("authored-output-collision", List.of(graph("main", nodes, List.of(
            connection("variable", "flow", "oldSink", "value"),
            connection("variable", "output_flow", "newSink", "value")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.reason().contains("TYPED_AUTOMATION_OUTPUT_PIN_COLLISION")), result.quarantines().toString());
    }

    @Test
    void rejectsAmbiguousAuthoredTargetConnections() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("first", authored("automation.variable", 1));
        nodes.put("second", authored("automation.variable", 1));
        nodes.put("target", authored("automation.variable", 1));
        Fixture fixture = fixture("authored-target-ambiguous", List.of(graph("main", nodes, List.of(
            connection("first", "value", "target", "flow"),
            connection("second", "value", "target", "flow")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.reason().contains("TYPED_AUTOMATION_CONNECTION_AMBIGUOUS")), result.quarantines().toString());
    }

    @Test
    void skipsDynamicAndUnsupportedLegacyAutomationNodesWithoutGeneratingState() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("dynamic", node("variable.access", Map.of("mode", "get", "scope", "global")));
        nodes.put("list", node("variable.access", Map.of("mode", "list", "scope", "global", "name", "all")));
        nodes.put("wiredSchedule", node("schedule.interval", Map.of("flow_id", "main", "seconds", 2)));
        nodes.put("resultSchedule", node("schedule.interval", Map.of("flow_id", "main", "seconds", 3)));
        nodes.put("cancel", node("schedule.cancel_task", Map.of()));
        List<Map<String, Object>> connections = List.of(
            connection("source", "value", "dynamic", "name"),
            connection("source", "value", "wiredSchedule", "flow_id"),
            connection("resultSchedule", "result", "source", "value"));
        Fixture fixture = fixture("dynamic", List.of(graph("main", nodes, connections)), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        assertTrue(result.files().stream().noneMatch(file -> file.operationType() == MigrationOperationType.GENERATE), result.files().toString());
    }

    @Test
    void mapsOneWiredVariableModeToActionAndQuarantinesAmbiguousModes() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("source", node("test.source", Map.of()));
        nodes.put("variable", node("variable.access", Map.of(
            "scope", "global", "persist", true, "name", "Dynamic", "value", true)));
        Fixture fixture = fixture("wired-mode", List.of(graph("main", nodes,
            List.of(connection("source", "mode", "variable", "mode")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        Map<String, Object> graph = object(file(result, "assets/Blueprints/Flows/main.json").bytes());
        Map<String, Object> variable = object(object(graph.get("nodes")).get("variable"));
        assertEquals("automation.variable", variable.get("type"));
        assertFalse(object(variable.get("inputValues")).containsKey("mode"));
        assertFalse(object(variable.get("inputValues")).containsKey("action"));
        assertTrue(list(graph.get("connections")).stream().map(TypedAutomationParityTest::object)
            .anyMatch(value -> "action".equals(value.get("targetPin"))));

        Map<String, Object> ambiguousNodes = new LinkedHashMap<>();
        ambiguousNodes.put("first", node("test.source", Map.of()));
        ambiguousNodes.put("second", node("test.source", Map.of()));
        ambiguousNodes.put("variable", node("variable.access", Map.of(
            "scope", "global", "persist", true, "name", "Ambiguous", "value", true)));
        Fixture ambiguous = fixture("wired-mode-ambiguous", List.of(graph("main", ambiguousNodes, List.of(
            connection("first", "mode", "variable", "mode"),
            connection("second", "mode", "variable", "mode")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform ambiguousResult = adapter().transform(input(ambiguous.snapshot()));

        assertTrue(ambiguousResult.files().isEmpty(), ambiguousResult.files().toString());
        assertTrue(ambiguousResult.quarantines().stream().anyMatch(value ->
            value.sourceLocation().equals("assets/Blueprints/Flows/main.json")));
    }

    @Test
    void quarantinesEveryUnsupportedCancelOutputConsumer() throws Exception {
        for (String output : List.of("result", "failed", "error_code", "message")) {
            Map<String, Object> nodes = new LinkedHashMap<>();
            nodes.put("cancel", node("schedule.cancel_task", Map.of()));
            nodes.put("schedule", node("schedule.interval", Map.of("flow_id", "main", "seconds", 2)));
            nodes.put("sink", node("test.sink", Map.of()));
            Fixture fixture = fixture("cancel-output-" + output, List.of(graph("main", nodes, List.of(
                connection("schedule", "task_id", "cancel", "task_id"),
                connection("cancel", output, "sink", "value")))), List.of());

            OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

            assertTrue(result.files().isEmpty(), output + ": " + result.files());
            assertTrue(result.quarantines().stream().anyMatch(value ->
                value.sourceLocation().equals("assets/Blueprints/Flows/main.json")), output + ": " + result.quarantines());
        }
    }

    @Test
    void quarantinesAnUnresolvedScheduleDependencyAsOneGraph() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("schedule", node("schedule.interval", Map.of("flow_id", "missing", "seconds", 4)));
        Fixture fixture = fixture("unresolved", List.of(graph("main", nodes, List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")
            && value.sourceLocation().equals("assets/Blueprints/Flows/main.json")));
    }

    @Test
    void quarantinesBothGraphsWhenOneResourceIdentityHasConflictingContent() throws Exception {
        Map<String, Object> first = node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Round", "value", true, "default", false));
        Map<String, Object> second = node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Round", "value", true, "default", true));
        Fixture fixture = fixture("conflict", List.of(
            graph("first", Map.of("variable", first), List.of()),
            graph("second", Map.of("variable", second), List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertEquals(2, result.quarantines().stream()
            .filter(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_RESOURCE_CONFLICT")).count());
    }

    @Test
    void quarantinesEveryGraphReferencingAConflictingResourcePayload() throws Exception {
        Map<String, Object> first = node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Shared", "value", true, "default", false));
        Map<String, Object> second = node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Shared", "value", true, "default", true));
        Map<String, Object> third = node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Shared", "value", true, "default", false));
        Fixture fixture = fixture("conflict-closure", List.of(
            graph("first", Map.of("variable", first), List.of()),
            graph("second", Map.of("variable", second), List.of()),
            graph("third", Map.of("variable", third), List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertEquals(3, result.quarantines().stream()
            .filter(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_RESOURCE_CONFLICT")).count());
        assertTrue(result.quarantines().stream().allMatch(value -> value.sourceLocation().startsWith("assets/Blueprints/Flows/")));
    }

    @Test
    void closesScheduleDependenciesAfterTargetGraphQuarantine() throws Exception {
        Map<String, Object> schedule = node("schedule.interval", Map.of("flow_id", "broken", "seconds", 4));
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("id", "broken");
        broken.put("resourceType", "flow");
        broken.put("version", 1);
        broken.put("nodes", "malformed");
        broken.put("connections", List.of());
        Fixture fixture = fixture("dependency-closure", List.of(
            graph("main", Map.of("schedule", schedule), List.of()),
            new GraphSpec("broken", "flow", broken)), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")
                && value.sourceLocation().equals("assets/Blueprints/Flows/main.json")), result.quarantines().toString());
    }

    @Test
    void closesScheduleDependenciesWhenTheGenericGraphPhaseQuarantinesTheTarget() throws Exception {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("id", "broken");
        broken.put("resourceType", "flow");
        broken.put("version", 1);
        broken.put("nodes", Map.of("sink", Map.of("type", "debug.log", "inputValues", "malformed")));
        broken.put("connections", List.of());
        Fixture fixture = fixture("generic-dependency-closure", List.of(
            graph("main", Map.of("schedule", node("schedule.interval", Map.of("flow_id", "broken", "seconds", 4))), List.of()),
            new GraphSpec("broken", "flow", broken)), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")
                && value.sourceLocation().equals("assets/Blueprints/Flows/main.json")), result.quarantines().toString());
    }

    @Test
    void quarantinesOccupiedAndMalformedGeneratedTargets() throws Exception {
        Map<String, Object> occupiedNodes = Map.of("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Occupied", "value", true)));
        Fixture occupied = fixture("occupied-generated-target", List.of(graph("main", occupiedNodes, List.of())), List.of());
        Path occupiedPath = occupied.root().resolve(resourcePath("migrated.variable.occupied.server.persistent.boolean"));
        Files.createDirectories(occupiedPath.getParent());
        Files.writeString(occupiedPath, "occupied", StandardCharsets.UTF_8);
        Snapshot occupiedSnapshot = snapshot(occupied.root(), "occupied-generated-target-rebuilt");

        OfflineUpgradeSnapshotAdapter.SnapshotTransform occupiedResult = adapter().transform(input(occupiedSnapshot));

        assertTrue(occupiedResult.files().isEmpty(), occupiedResult.files().toString());
        assertTrue(occupiedResult.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_TARGET_CONFLICT")));

        String id = "migrated.variable.malformed.server.persistent.boolean";
        Map<String, Object> malformedNodes = Map.of("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Malformed", "value", true)));
        Fixture malformed = fixture("malformed-generated-target", List.of(graph("main", malformedNodes, List.of())), List.of(
            projectResource("variable_definition", id, "Automation/Variables")));
        Path malformedPath = malformed.root().resolve(resourcePath(id));
        Files.createDirectories(malformedPath.getParent());
        Files.writeString(malformedPath, "{\"resourceType\":\"variable_definition\",\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
        Snapshot malformedSnapshot = snapshot(malformed.root(), "malformed-generated-target-rebuilt");

        OfflineUpgradeSnapshotAdapter.SnapshotTransform malformedResult = adapter().transform(input(malformedSnapshot));

        assertTrue(malformedResult.files().isEmpty(), malformedResult.files().toString());
        assertTrue(malformedResult.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")));
    }

    @Test
    void quarantinesEverySamePayloadContributorWhenGeneratedTargetIsOccupied() throws Exception {
        List<GraphSpec> graphs = List.of(
            graph("first", Map.of("variable", node("variable.access", Map.of(
                "mode", "set", "scope", "global", "persist", true, "name", "Shared Occupied", "value", true))), List.of()),
            graph("second", Map.of("variable", node("variable.access", Map.of(
                "mode", "set", "scope", "global", "persist", true, "name", "Shared Occupied", "value", true))), List.of()),
            graph("third", Map.of("variable", node("variable.access", Map.of(
                "mode", "set", "scope", "global", "persist", true, "name", "Shared Occupied", "value", true))), List.of()));
        Fixture fixture = fixture("same-payload-occupied", graphs, List.of());
        Path occupied = fixture.root().resolve(resourcePath("migrated.variable.shared_occupied.server.persistent.boolean"));
        Files.createDirectories(occupied.getParent());
        Files.writeString(occupied, "occupied", StandardCharsets.UTF_8);
        Snapshot source = snapshot(fixture.root(), "same-payload-occupied-rebuilt");

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(source));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertEquals(3, result.quarantines().stream()
            .filter(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_TARGET_CONFLICT"))
            .map(value -> value.sourceLocation()).distinct().count());
        assertTrue(result.quarantines().stream()
            .filter(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_TARGET_CONFLICT"))
            .allMatch(value -> value.sourceLocation().startsWith("assets/Blueprints/Flows/")));
    }

    @Test
    void reanchorsSamePayloadResourceAfterRepresentativeIsGenericQuarantined() throws Exception {
        Map<String, Object> malformedNode = node("debug.log", Map.of());
        malformedNode.put("inputValues", "malformed");
        Map<String, Object> firstNodes = new LinkedHashMap<>();
        firstNodes.put("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Reanchor", "value", true)));
        firstNodes.put("malformed", malformedNode);
        Map<String, Object> secondNodes = Map.of("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Reanchor", "value", true)));
        Fixture fixture = fixture("same-payload-reanchor", List.of(
            graph("first", firstNodes, List.of()),
            graph("second", secondNodes, List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.sourceLocation().equals("assets/Blueprints/Flows/first.json")), result.quarantines().toString());
        assertTrue(result.quarantines().stream().noneMatch(value ->
            value.sourceLocation().equals("assets/Blueprints/Flows/second.json")), result.quarantines().toString());
        OfflineUpgradeSnapshotAdapter.FileTransform resource = file(result,
            "assets/Automation/Variables/migrated.variable.reanchor.server.persistent.boolean.json");
        assertEquals(MigrationOperationType.GENERATE, resource.operationType());
        assertEquals("assets/Blueprints/Flows/second.json", resource.sourcePath());
        assertTrue(result.files().stream().anyMatch(value ->
            value.targetPath().equals("assets/Blueprints/Flows/second.json")));
        assertTrue(result.files().stream().noneMatch(value ->
            value.targetPath().equals("assets/Blueprints/Flows/first.json")));
    }

    @Test
    void quarantinesMalformedGraphShapesWithoutEscapingToTriggerQuarantine() throws Exception {
        Map<String, Object> malformedInput = new LinkedHashMap<>();
        malformedInput.put("id", "input");
        malformedInput.put("resourceType", "flow");
        malformedInput.put("version", 1);
        malformedInput.put("nodes", Map.of("variable", Map.of("type", "variable.access", "inputValues", "malformed")));
        malformedInput.put("connections", List.of());
        Map<String, Object> malformedConnections = new LinkedHashMap<>(malformedInput);
        malformedConnections.put("id", "connections");
        malformedConnections.put("connections", "malformed");
        Map<String, Object> malformedNode = new LinkedHashMap<>();
        malformedNode.put("id", "node");
        malformedNode.put("resourceType", "flow");
        malformedNode.put("version", 1);
        malformedNode.put("nodes", Map.of("variable", "malformed"));
        malformedNode.put("connections", List.of());
        Fixture fixture = fixture("malformed-shapes", List.of(
            new GraphSpec("input", "flow", malformedInput),
            new GraphSpec("connections", "flow", malformedConnections),
            new GraphSpec("node", "flow", malformedNode)), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().stream().noneMatch(value -> value.sourceLocation().equals("triggers.json")),
            result.quarantines().toString());
        assertTrue(result.quarantines().stream().map(value -> value.sourceLocation())
            .allMatch(value -> value.startsWith("assets/Blueprints/Flows/")), result.quarantines().toString());
        assertTrue(result.files().isEmpty(), result.files().toString());
    }

    @Test
    void migratesEveryScheduleVariantAndLegacyAlias() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("daily", node("schedule.schedule", Map.of("flow_id", "main", "time_string", "08:30", "time_zone", "UTC")));
        nodes.put("repeat", node("schedule.schedule_repeating", Map.of("flow_id", "main", "interval_ticks", 40)));
        nodes.put("interval", node("schedule.interval", Map.of("flow_id", "main", "seconds", new BigDecimal("2.5"))));
        nodes.put("cron", node("schedule.cron", Map.of("flow_id", "main", "expression", "*/5 * * * *", "time_zone", "UTC")));
        nodes.put("at", node("schedule.at.time", Map.of("flow_id", "main", "time", "2026-08-17T12:00:00Z", "time_zone", "UTC")));
        nodes.put("dailyAlias", node("schedule", Map.of("flow_id", "main", "time_string", "09:15")));
        nodes.put("repeatAlias", node("schedule_repeating", Map.of("flow_id", "main", "interval_ticks", 60)));
        nodes.put("intervalAlias", node("schedule_interval", Map.of("flow_id", "main", "seconds", 3)));
        nodes.put("cronAlias", node("schedule_cron", Map.of("flow_id", "main", "expression", "0 0 * * *")));
        nodes.put("atAlias", node("schedule_at_time", Map.of("flow_id", "main", "time", "2026-08-17T13:00:00Z")));
        Fixture fixture = fixture("schedule-variants", List.of(graph("main", nodes, List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        List<? extends OfflineUpgradeSnapshotAdapter.FileTransform> generated = result.files().stream()
            .filter(value -> value.operationType() == MigrationOperationType.GENERATE).toList();
        assertEquals(10, generated.stream().filter(value -> value.targetPath().contains("Automation/Schedules/")).count(), generated.toString());
        assertTrue(generated.stream().map(value -> ManagedResourceFileContract.decode(value.bytes()))
            .anyMatch(value -> "repeating".equals(value.get("timingMode")) && "seconds".equals(value.get("unit"))
                && ((Number) value.get("duration")).doubleValue() == 2.5));
        assertTrue(generated.stream().map(value -> ManagedResourceFileContract.decode(value.bytes()))
            .filter(value -> "cron".equals(value.get("timingMode")))
            .anyMatch(value -> String.valueOf(value.get("cron")).contains("*/5")));
        assertTrue(generated.stream().map(value -> ManagedResourceFileContract.decode(value.bytes()))
            .anyMatch(value -> "at_time".equals(value.get("timingMode"))));
    }

    @Test
    void preservesVariableScopesTypesDefaultsAndLegacyAliases() throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("global", node("variable_set_global", Map.of("name", "Enabled", "persist", true,
            "value", true, "default", false)));
        nodes.put("player", node("variable.variable_set_player", Map.of("name", "Greeting", "scope", "player",
            "value", "Hello", "defaultValue", "Guest")));
        nodes.put("local", node("variable_set_local", Map.of("name", "Count", "scope", "local",
            "value", 3, "default", 0)));
        nodes.put("alias", node("variable_get_global", Map.of("name", "Alias", "valueType", "string", "default", "ready")));
        nodes.put("dotAlias", node("variable.variable_get_global", Map.of("name", "Dot Alias",
            "valueType", "string", "default", "ready")));
        Fixture fixture = fixture("variable-contract", List.of(graph("main", nodes, List.of())), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        List<Map<String, Object>> definitions = result.files().stream()
            .filter(value -> value.operationType() == MigrationOperationType.GENERATE)
            .map(value -> ManagedResourceFileContract.decode(value.bytes())).toList();
        assertEquals(5, definitions.size(), definitions.toString());
        assertTrue(definitions.stream().anyMatch(value -> "server".equals(value.get("scope"))
            && Boolean.TRUE.equals(value.get("persistent")) && "boolean".equals(value.get("valueType"))
            && Boolean.FALSE.equals(value.get("defaultValue"))));
        assertTrue(definitions.stream().anyMatch(value -> "player".equals(value.get("scope"))
            && "string".equals(value.get("valueType")) && "Guest".equals(value.get("defaultValue"))));
        assertTrue(definitions.stream().anyMatch(value -> "flow".equals(value.get("scope"))
            && "number".equals(value.get("valueType")) && ((Number) value.get("defaultValue")).intValue() == 0));
        assertTrue(definitions.stream().anyMatch(value -> "Alias".equals(value.get("name"))
            && "string".equals(value.get("valueType"))));
        assertTrue(definitions.stream().anyMatch(value -> "Dot Alias".equals(value.get("name"))
            && "string".equals(value.get("valueType"))));
    }

    @Test
    void generatesProjectMetadataWhenTheSourceHasNoProjectFile() throws Exception {
        Map<String, Object> generatedNodes = Map.of("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Generated", "value", true)));
        Fixture fixture = fixture("generated-project", List.of(graph("main", generatedNodes, List.of())), List.of());
        Files.delete(fixture.root().resolve("assets/project.json"));
        Snapshot source = snapshot(fixture.root(), "generated-project-without-metadata");

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(source));

        OfflineUpgradeSnapshotAdapter.FileTransform project = file(result, "assets/project.json");
        assertEquals(MigrationOperationType.GENERATE, project.operationType());
        Map<String, Object> metadata = object(project.bytes());
        assertTrue(list(metadata.get("resources")).stream().map(TypedAutomationParityTest::object)
            .anyMatch(value -> "flow".equals(value.get("type")) && "main".equals(value.get("id"))));
        assertTrue(list(metadata.get("resources")).stream().map(TypedAutomationParityTest::object)
            .anyMatch(value -> "variable_definition".equals(value.get("type"))));
    }

    @Test
    void reusesOnlyAnExactManagedResourceEnvelope() throws Exception {
        String id = "migrated.variable.round.server.persistent.boolean";
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", id);
        definition.put("name", "Round");
        definition.put("description", "Migrated Variable");
        definition.put("valueType", "boolean");
        definition.put("scope", "server");
        definition.put("persistent", true);
        byte[] existingBytes = ManagedResourceFileContract.encode("variable_definition", id, definition);
        Fixture fixture = fixture("existing", List.of(graph("main", Map.of("variable", node("variable.access", Map.of(
            "mode", "set", "scope", "global", "persist", true, "name", "Round", "value", true))), List.of())), List.of(
            projectResource("variable_definition", id, "Automation/Variables")));
        Path resource = fixture.root().resolve("assets/Automation/Variables/" + id + ".json");
        Files.createDirectories(resource.getParent());
        Files.write(resource, existingBytes);
        Snapshot snapshot = snapshot(fixture.root(), "existing");

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(snapshot));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        assertTrue(result.files().stream().noneMatch(file -> file.targetPath().equals(resourcePath(id))
            && file.operationType() == MigrationOperationType.GENERATE));

        Map<String, Object> mismatch = new LinkedHashMap<>(definition);
        mismatch.put("description", "Different");
        Files.write(resource, ManagedResourceFileContract.encode("variable_definition", id, mismatch));
        Snapshot mismatchSnapshot = snapshot(fixture.root(), "existing-mismatch");
        OfflineUpgradeSnapshotAdapter.SnapshotTransform mismatchResult = adapter().transform(input(mismatchSnapshot));

        assertTrue(mismatchResult.files().isEmpty(), mismatchResult.files().toString());
        assertTrue(mismatchResult.quarantines().stream().anyMatch(value -> value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")));
    }

    @Test
    void generatedPlanIsDeterministicAndStagerProtectsProvenanceTargetAndFinalFileSet() throws Exception {
        Fixture fixture = fixture("staging", List.of(automationGraph("main")), List.of());
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(List.of(), List.of(adapter()));
        StandaloneUpgradePlanner planner = new StandaloneUpgradePlanner(registry);
        UpgradeProposal first = planner.plan(fixture.snapshot(), LegacySnapshotWindow.sourceWindow());
        UpgradeProposal second = planner.plan(fixture.snapshot(), LegacySnapshotWindow.sourceWindow());

        assertTrue(first.quarantineReport().records().isEmpty(), first.quarantineReport().records().toString());
        assertEquals(first.plan().planHash(), second.plan().planHash());
        assertTrue(first.plan().operations().stream().anyMatch(operation -> operation.type() == MigrationOperationType.GENERATE));
        assertTrue(first.plan().operations().stream().filter(operation -> operation.type() == MigrationOperationType.GENERATE)
            .allMatch(operation -> !operation.sourcePath().isBlank() && !operation.sourceHash().isBlank() && !operation.targetHash().isBlank()));

        StandaloneUpgradeStager stager = new StandaloneUpgradeStager(registry);
        var acceptance = first.quarantineReport().accept("typed-automation-test", Instant.EPOCH);
        StagedMigration staged = stager.stage(fixture.root(), temporary.resolve("staging-success"), first.plan(),
            first.quarantineReport(), acceptance);
        assertTrue(Files.exists(staged.root().resolve(resourcePath("migrated.variable.game_running.server.persistent.boolean"))));
        assertTrue(Files.exists(staged.root().resolve("assets/Automation/Schedules/migrated.schedule.main.schedule.json")));

        Path tamperedSource = temporary.resolve("staging-source-tamper");
        Files.write(fixture.root().resolve("assets/Blueprints/Flows/main.json"), "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> stager.stage(fixture.root(), tamperedSource, first.plan(), first.quarantineReport(), acceptance));
        assertFalse(Files.exists(tamperedSource));

        Fixture targetFixture = fixture("staging-target-tamper", List.of(automationGraph("main")), List.of());
        Snapshot targetSnapshot = targetFixture.snapshot();
        UpgradeProposal targetProposal = planner.plan(targetSnapshot, LegacySnapshotWindow.sourceWindow());
        Path targetSourceGraph = targetFixture.root().resolve("assets/Blueprints/Flows/main.json");
        byte[] targetSourceBytes = Files.readAllBytes(targetSourceGraph);
        List<MigrationOperation> tamperedOperations = targetProposal.plan().operations().stream().map(operation ->
            operation.type() == MigrationOperationType.GENERATE
                ? new MigrationOperation(operation.type(), operation.adapterId(), operation.sourcePath(), operation.targetPath(),
                    operation.sourceHash(), "0".repeat(64)) : operation).toList();
        var tamperedPlan = new restudio.resync.migration.MigrationPlan(targetProposal.plan().sourceSnapshotId(),
            targetProposal.plan().sourceManifestHash(), targetProposal.plan().sourceFormatVersion(), targetProposal.plan().targetFormatVersion(),
            targetProposal.plan().quarantineReportHash(), tamperedOperations);
        Path tamperedTarget = temporary.resolve("target-tamper-staging");
        Exception targetFailure = assertThrows(Exception.class, () -> stager.stage(targetFixture.root(), tamperedTarget, tamperedPlan,
            targetProposal.quarantineReport(), targetProposal.quarantineReport().accept("typed-automation-test", Instant.EPOCH)));
        assertFalse(Files.exists(tamperedTarget), targetFailure.toString());
        assertArrayEquals(targetSourceBytes, Files.readAllBytes(targetSourceGraph));
    }

    @Test
    void stagedResultProducesAZeroOperationByteStableSecondRun() throws Exception {
        Fixture fixture = fixture("second-run", List.of(automationGraph("main")), List.of());
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(List.of(), List.of(adapter()));
        StandaloneUpgradePlanner planner = new StandaloneUpgradePlanner(registry);
        StandaloneUpgradeStager stager = new StandaloneUpgradeStager(registry);
        UpgradeProposal first = planner.plan(fixture.snapshot(), LegacySnapshotWindow.sourceWindow());
        StagedMigration staged = stager.stage(fixture.root(), temporary.resolve("second-run-staged"), first.plan(),
            first.quarantineReport(), first.quarantineReport().accept("typed-automation-second-run", Instant.EPOCH));

        replaceTree(fixture.root(), staged.root());
        Snapshot upgraded = snapshot(fixture.root(), "second-run-upgraded");
        UpgradeProposal second = planner.plan(upgraded, LegacySnapshotWindow.sourceWindow());

        assertTrue(second.plan().operations().isEmpty(), second.plan().operations().toString());
        assertTrue(second.quarantineReport().records().isEmpty(), second.quarantineReport().records().toString());
        StagedMigration replay = stager.stage(fixture.root(), temporary.resolve("second-run-replay"), second.plan(),
            second.quarantineReport(), second.quarantineReport().accept("typed-automation-second-run", Instant.EPOCH));
        assertTreeBytesEqual(fixture.root(), replay.root());
    }

    @Test
    void recoveredFunctionStagingPreservesBackupsAndIsByteStableOnTheSecondRun() throws Exception {
        Fixture fixture = fixture("backup-second-run", List.of(graph("partial", Map.of(), List.of())), List.of());
        writeBackup(fixture.root(), "typed-automation-1", "partial", functionBackup("partial", "partial"));
        Snapshot source = snapshot(fixture.root(), "backup-second-run-source");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(List.of(), List.of(adapter()));
        StandaloneUpgradePlanner planner = new StandaloneUpgradePlanner(registry);
        StandaloneUpgradeStager stager = new StandaloneUpgradeStager(registry);

        UpgradeProposal first = planner.plan(source, LegacySnapshotWindow.sourceWindow());

        assertTrue(first.quarantineReport().records().isEmpty(), first.quarantineReport().records().toString());
        assertTrue(first.plan().operations().stream().anyMatch(operation ->
            operation.targetPath().equals("assets/Blueprints/Functions/partial.json")));
        byte[] backupBytes = Files.readAllBytes(fixture.root()
            .resolve("assets/migration-backups/typed-automation-1/partial.json"));
        StagedMigration staged = stager.stage(fixture.root(), temporary.resolve("backup-second-run-staged"), first.plan(),
            first.quarantineReport(), first.quarantineReport().accept("typed-automation-backup", Instant.EPOCH));
        Map<String, Object> stagedProject = object(Files.readAllBytes(staged.root().resolve("assets/project.json")));
        List<Map<String, Object>> partialResources = list(stagedProject.get("resources")).stream()
            .map(TypedAutomationParityTest::object)
            .filter(resource -> "partial".equals(resource.get("id")))
            .toList();
        assertEquals(1, partialResources.size());
        assertEquals("function", partialResources.getFirst().get("type"));
        assertEquals("Blueprints/Functions", partialResources.getFirst().get("path"));
        assertTrue(list(stagedProject.get("resources")).stream().map(TypedAutomationParityTest::object)
            .noneMatch(resource -> "partial".equals(resource.get("id")) && "flow".equals(resource.get("type"))));
        assertTrue(Files.exists(staged.root().resolve("assets/Blueprints/Functions/partial.json")));
        assertFalse(Files.exists(staged.root().resolve("assets/Blueprints/Flows/partial.json")));
        assertArrayEquals(backupBytes, Files.readAllBytes(staged.root()
            .resolve("assets/migration-backups/typed-automation-1/partial.json")));

        replaceTree(fixture.root(), staged.root());
        Snapshot upgraded = snapshot(fixture.root(), "backup-second-run-upgraded");
        UpgradeProposal second = planner.plan(upgraded, LegacySnapshotWindow.sourceWindow());

        assertTrue(second.plan().operations().isEmpty(), second.plan().operations().toString());
        assertTrue(second.quarantineReport().records().isEmpty(), second.quarantineReport().records().toString());
        StagedMigration replay = stager.stage(fixture.root(), temporary.resolve("backup-second-run-replay"), second.plan(),
            second.quarantineReport(), second.quarantineReport().accept("typed-automation-backup", Instant.EPOCH));
        assertTreeBytesEqual(fixture.root(), replay.root());
    }

    private void authoredNode(Map<String, Object> nodes, Map<String, List<String>> expectedOutputs,
                              Map<String, Integer> expectedVersions, String id, String type, int sourceVersion,
                              List<String> outputs, int targetVersion) {
        nodes.put(id, authored(type, sourceVersion));
        expectedOutputs.put(id, outputs);
        expectedVersions.put(id, targetVersion);
    }

    private Map<String, Object> authored(String type, int version) {
        Map<String, Object> node = node(type, Map.of());
        node.put("version", version);
        return node;
    }

    private void assertUnconvertedAuthoredVersion(String fixtureName, Map<String, Object> authoredNode) throws Exception {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("timer", authoredNode);
        nodes.put("sink", node("test.sink", Map.of()));
        Fixture fixture = fixture(fixtureName, List.of(graph("main", nodes,
            List.of(connection("timer", "timer", "sink", "value")))), List.of());

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter().transform(input(fixture.snapshot()));

        assertTrue(result.files().stream().noneMatch(value -> value.targetPath().startsWith("assets/Automation/")), result.files().toString());
        assertTrue(result.quarantines().stream().allMatch(value ->
            value.sourceLocation().equals("assets/Blueprints/Flows/main.json")), result.quarantines().toString());
        Map<String, Object> graph = result.files().stream()
            .filter(value -> value.targetPath().equals("assets/Blueprints/Flows/main.json"))
            .findFirst()
            .map(value -> object(value.bytes()))
            .orElseGet(() -> object(read(fixture.root().resolve("assets/Blueprints/Flows/main.json"))));
        Map<String, Object> sourceNode = object(object(graph.get("nodes")).get("timer"));
        assertEquals(authoredNode.get("type"), sourceNode.get("type"));
        if (authoredNode.containsKey("version")) {
            assertEquals(((Number) authoredNode.get("version")).intValue(), ((Number) sourceNode.get("version")).intValue());
        } else {
            assertFalse(sourceNode.containsKey("version"));
        }
        List<String> pins = list(graph.get("connections")).stream().map(TypedAutomationParityTest::object)
            .filter(value -> "timer".equals(value.get("sourceNodeId")))
            .map(value -> stablePin(String.valueOf(value.get("sourcePin")))).toList();
        assertEquals(List.of("timer"), pins);
    }

    private static String stablePin(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private OfflineUpgradeSnapshotAdapter adapter() {
        return new LegacyResyncSnapshotAdapter();
    }

    private Fixture fixture(String name, List<GraphSpec> graphs, List<Map<String, Object>> extraResources) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Files.createDirectories(root.resolve("assets/Blueprints/Flows"));
        List<Object> resources = new ArrayList<>();
        for (GraphSpec graph : graphs) {
            writeJson(root.resolve("assets/Blueprints/Flows/" + graph.id() + ".json"), graph.document());
            resources.add(projectResource(graph.type(), graph.id(), "Blueprints/Flows"));
        }
        resources.addAll(extraResources);
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("serverId", "fixture");
        project.put("folders", List.of());
        project.put("resources", resources);
        writeJson(root.resolve("assets/project.json"), project);
        Files.writeString(root.resolve("triggers.json"), "[]", StandardCharsets.UTF_8);
        return new Fixture(root, snapshot(root, name));
    }

    private GraphSpec automationGraph(String id) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("cancel", node("schedule.cancel_task", Map.of()));
        nodes.put("get", node("variable.access", Map.of("mode", "get", "scope", "global", "persist", true, "name", "Game Running")));
        nodes.put("schedule", node("schedule.interval", Map.of("flow_id", id, "seconds", new BigDecimal("2.5"))));
        nodes.put("set", node("variable.access", Map.of("mode", "set", "scope", "global", "persist", true, "name", "Game Running", "value", true)));
        nodes.put("sink", node("debug.log", Map.of()));
        List<Map<String, Object>> connections = List.of(
            connection("schedule", "task_id", "cancel", "task_id"),
            connection("schedule", "flow", "sink", "flow"));
        return graph(id, nodes, connections);
    }

    private GraphSpec graph(String id, Map<String, Object> nodes, List<Map<String, Object>> connections) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("resourceType", "flow");
        document.put("version", 1);
        document.put("nodes", nodes);
        document.put("connections", connections);
        return new GraphSpec(id, "flow", document);
    }

    private Map<String, Object> node(String type, Map<String, Object> inputValues) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("inputValues", new LinkedHashMap<>(inputValues));
        return node;
    }

    private Map<String, Object> connection(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("sourceNodeId", sourceNodeId);
        connection.put("sourcePin", sourcePin);
        connection.put("targetNodeId", targetNodeId);
        connection.put("targetPin", targetPin);
        return connection;
    }

    private Map<String, Object> projectResource(String type, String id, String path) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", type);
        resource.put("id", id);
        resource.put("displayName", id);
        resource.put("path", path);
        resource.put("sortOrder", 0);
        return resource;
    }

    private String resourcePath(String id) {
        return "assets/Automation/Variables/" + id + ".json";
    }

    private void writeBackup(Path source, String migrationId, String id, byte[] bytes) throws Exception {
        Path path = source.resolve("assets/migration-backups").resolve(migrationId).resolve(id + ".json");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private byte[] functionBackup(String id, String description) {
        String source = "{\"id\":\"" + id + "\",\"resourceType\":\"function\",\"version\":1,"
            + "\"functionDescription\":\"" + description + "\",\"nodes\":{},\"connections\":[]}";
        return new LegacyFlowGraphSnapshotAdapter().transformGraph(source.getBytes(StandardCharsets.UTF_8), "function");
    }

    private void writeJson(Path path, Map<String, Object> value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, CanonicalCodec.encode(JsonValue.fromJava(value)));
    }

    private void replaceTree(Path source, Path replacement) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.equals(source)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        try (var paths = Files.walk(replacement)) {
            for (Path path : paths.toList()) {
                Path relative = replacement.relativize(path);
                Path target = source.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private void assertTreeBytesEqual(Path first, Path second) throws Exception {
        Map<String, byte[]> firstFiles = fileBytes(first);
        Map<String, byte[]> secondFiles = fileBytes(second);
        assertEquals(firstFiles.keySet(), secondFiles.keySet());
        for (String path : firstFiles.keySet()) {
            assertArrayEquals(firstFiles.get(path), secondFiles.get(path), path);
        }
    }

    private Map<String, byte[]> fileBytes(Path root) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return files;
    }

    private Snapshot snapshot(Path root, String id) throws Exception {
        SnapshotMetadata metadata = new SnapshotMetadata(1, id, Instant.EPOCH,
            LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, root.resolve("assets")));
        participants.register(participant(ProductionPersistenceOwners.TRIGGERS, root.resolve("triggers.json")));
        SnapshotManifest manifest = SnapshotManifest.scan(root, metadata, participants);
        Path manifestPath = root.resolveSibling(root.getFileName() + ".manifest");
        manifest.write(manifestPath);
        ProductionSnapshotMetadataManifest.write(root, manifest);
        SnapshotVerification verification = manifest.verify(root);
        verification.requireVerified();
        Path statePath = root.resolveSibling(root.getFileName() + ".state");
        Files.writeString(statePath, "state=VERIFIED\nverified=true\nmanifest-hash=" + verification.manifestHash()
            + "\nfailures=0\n", StandardCharsets.UTF_8);
        return new SnapshotService(new MigrationFence()).admitExported(root).snapshot();
    }

    private PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }

    private OfflineUpgradeSnapshotInput input(Snapshot snapshot) throws Exception {
        return new OfflineUpgradeSnapshotInput(snapshot.root(), ImmutableSnapshotAdapter.adapt(snapshot));
    }

    private OfflineUpgradeSnapshotAdapter.FileTransform file(OfflineUpgradeSnapshotAdapter.SnapshotTransform result, String path) {
        return result.files().stream().filter(value -> value.targetPath().equals(path)).findFirst().orElseThrow();
    }

    private static Map<String, Object> object(byte[] value) {
        return object(CanonicalCodec.decodePermissive(value).toJava());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private Map<String, Object> reference(String kind, String id) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("kind", kind);
        reference.put("id", id);
        reference.put("owner", "server");
        reference.put("available", true);
        reference.put("metadata", Map.of());
        return reference;
    }

    private record Fixture(Path root, Snapshot snapshot) {
    }

    private record GraphSpec(String id, String type, Map<String, Object> document) {
    }
}
