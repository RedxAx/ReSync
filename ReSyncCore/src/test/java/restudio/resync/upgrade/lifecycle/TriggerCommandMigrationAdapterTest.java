package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Adaptation;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Change;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Claim;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Input;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.SourceFile;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;

class TriggerCommandMigrationAdapterTest {
    private static final String MANIFEST = "0".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesExactTypedCommandAndPreservesEventSystemAndAuthority() throws Exception {
        write("triggers.json", Map.of(
            "version", 1,
            "events", List.of(Map.of("event", "server_start", "resource", Map.of("type", "flow", "id", "fixture"))),
            "commands", List.of(Map.of(
                "path", " /FIXTURE   set <Value> ",
                "resource", Map.of("type", "command", "id", "fixture"),
                "resourceRevision", 7,
                "mutationId", "mutation-007",
                "structured", true)),
            "system", List.of(Map.of("event", "shutdown", "resource", Map.of("type", "flow", "id", "fixture")))));
        write("assets/Blueprints/Commands/fixture.json", commandGraph("fixture", 7, "mutation-007"));
        write("assets/Blueprints/Flows/fixture.json", Map.of("id", "fixture", "resourceType", "flow", "resourceRevision", 99,
            "mutationId", "flow-mutation", "nodes", Map.of(), "connections", List.of()));

        Adaptation result = new TriggerCommandMigrationAdapter().adapt(input(
            "triggers.json", "assets/Blueprints/Commands/fixture.json", "assets/Blueprints/Flows/fixture.json"));

        assertAll(
            () -> assertEquals(2, result.claims().size()),
            () -> assertEquals(List.of("assets/Blueprints/Commands/fixture.json", "triggers.json"), result.claims().stream().map(Claim::relativePath).toList()),
            () -> assertEquals(List.of(ProductionPersistenceOwners.FLOW_ASSETS, ProductionPersistenceOwners.TRIGGERS), result.claims().stream().map(Claim::owner).toList()),
            () -> assertEquals(2, result.changes().size()),
            () -> assertTrue(result.quarantineRecords().isEmpty()),
            () -> assertFalse(result.claims().stream().anyMatch(claim -> claim.relativePath().contains("/Flows/"))));

        Map<String, Object> graph = changedObject(result, "assets/Blueprints/Commands/fixture.json");
        assertAll(
            () -> assertEquals("command", graph.get("resourceType")),
            () -> assertEquals("fixture", graph.get("id")),
            () -> assertEquals(7L, ((BigDecimal) graph.get("resourceRevision")).longValueExact()),
            () -> assertEquals("mutation-007", graph.get("mutationId")),
            () -> assertEquals("fixture", graph.get("commandLabel")),
            () -> assertEquals(Boolean.TRUE, graph.get("structured")),
            () -> assertEquals(List.of("fixture set <Value>"), graph.get("commandPaths")),
            () -> assertEquals(Map.of("start", Map.of("type", "event.resync.command", "inputs", Map.of("opaque", "kept"))), graph.get("nodes")),
            () -> assertEquals(List.of(Map.of("source", "start", "target", "next")), graph.get("connections")));

        Map<String, Object> triggers = changedObject(result, "triggers.json");
        assertAll(
            () -> assertEquals(List.of(), triggers.get("commands")),
            () -> assertEquals(List.of(Map.of("event", "server_start", "resource", Map.of("id", "fixture", "type", "flow"))), triggers.get("events")),
            () -> assertEquals(List.of(Map.of("event", "shutdown", "resource", Map.of("id", "fixture", "type", "flow"))), triggers.get("system")));
    }

    @Test
    void conflictingLegacyRowsAreQuarantinedWithoutRemovingAnyTriggerType() throws Exception {
        String first = CanonicalJson.canonicalize(Map.of("command", "first", "subcommands", List.of("run <target>"), "structured", true));
        String second = CanonicalJson.canonicalize(Map.of("command", "second", "subcommands", List.of("run <target>"), "structured", true));
        List<Object> rows = List.of(
            Map.of("id", "shared:event", "flowId", "shared", "type", "EVENT", "context", "server_start"),
            Map.of("id", "shared:command:a", "flowId", "shared", "type", "COMMAND", "context", first),
            Map.of("id", "shared:command:b", "flowId", "shared", "type", "COMMAND", "context", second),
            Map.of("id", "shared:system", "flowId", "shared", "type", "SYSTEM", "context", "shutdown"));
        write("triggers.json", rows);
        write("assets/Commands/shared.json", commandGraph("shared", 3, "mutation-003"));

        Adaptation result = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/shared.json"));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals("MIGRATION.COMMAND_BINDING_AMBIGUOUS", result.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sourceHash("triggers.json"), result.quarantineRecords().getFirst().sourceHash()),
            () -> assertTrue(result.quarantineRecords().getFirst().recordId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")),
            () -> assertEquals(2, result.claims().size()),
            () -> assertEquals(rows, CanonicalJson.parse(Files.readAllBytes(temporaryDirectory.resolve("triggers.json")))));
    }

    @Test
    void staleAuthorityAndConflictingGraphOwnershipRemainQuarantined() throws Exception {
        write("triggers.json", Map.of("events", List.of(), "commands", List.of(Map.of(
            "path", "fixture expected",
            "resource", Map.of("type", "command", "id", "fixture"),
            "resourceRevision", 8,
            "mutationId", "stale-mutation"))));
        Map<String, Object> graph = new LinkedHashMap<>(commandGraph("fixture", 7, "mutation-007"));
        graph.put("commandLabel", "different");
        graph.put("structured", false);
        graph.put("commandPaths", List.of("different path"));
        write("assets/Commands/fixture.json", graph);

        Adaptation result = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/fixture.json"));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals("MIGRATION.COMMAND_AUTHORITY_MISMATCH", result.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sourceHash("triggers.json"), result.quarantineRecords().getFirst().sourceHash()));
    }

    @Test
    void oneRejectedCommandBlocksEveryCrossFileChange() throws Exception {
        write("triggers.json", Map.of("events", List.of(Map.of("event", "server_start")), "commands", List.of(
            Map.of("path", "valid run", "resource", Map.of("type", "command", "id", "valid")),
            Map.of("path", "missing run", "resource", Map.of("type", "command", "id", "missing")))));
        write("assets/Commands/valid.json", commandGraph("valid", 4, "mutation-004"));

        Adaptation result = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/valid.json"));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals("MIGRATION.COMMAND_RESOURCE_MISSING", result.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sourceHash("triggers.json"), result.quarantineRecords().getFirst().sourceHash()));
    }

    @Test
    void malformedIdentityContextAndAuthorityBecomeExactSourceQuarantine() throws Exception {
        write("triggers.json", List.of(Map.of("id", "broken", "flowId", "broken", "type", "COMMAND", "context", "{not-json")));
        write("assets/Commands/broken.json", commandGraph("broken", 1, "mutation-001"));

        Adaptation malformedContext = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));

        assertAll(
            () -> assertTrue(malformedContext.changes().isEmpty()),
            () -> assertEquals("MIGRATION.COMMAND_BINDING_INVALID", malformedContext.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", malformedContext.quarantineRecords().getFirst().sourceLocation()));

        write("triggers.json", Map.of("commands", List.of(Map.of(
            "path", "broken run",
            "resource", Map.of("type", "command")))));
        Adaptation missingCommandId = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        assertAll(
            () -> assertTrue(missingCommandId.changes().isEmpty()),
            () -> assertEquals("MIGRATION.COMMAND_BINDING_INVALID", missingCommandId.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", missingCommandId.quarantineRecords().getFirst().sourceLocation()));

        write("triggers.json", Map.of("commands", List.of(Map.of(
            "path", "broken run",
            "resource", Map.of("type", "command", "id", "broken"),
            "resourceRevision", "one",
            "mutationId", 7))));
        Adaptation wrongBindingAuthority = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        assertAll(
            () -> assertTrue(wrongBindingAuthority.changes().isEmpty()),
            () -> assertEquals("MIGRATION.COMMAND_BINDING_INVALID", wrongBindingAuthority.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", wrongBindingAuthority.quarantineRecords().getFirst().sourceLocation()));

        write("triggers.json", Map.of("commands", List.of(Map.of(
            "path", "broken run",
            "resource", Map.of("type", "command", "id", "broken"),
            "resourceRevision", 1,
            "mutationId", 7))));
        Adaptation wrongBindingMutation = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        assertAll(
            () -> assertTrue(wrongBindingMutation.changes().isEmpty()),
            () -> assertEquals("MIGRATION.COMMAND_BINDING_INVALID", wrongBindingMutation.quarantineRecords().getFirst().code()),
            () -> assertEquals("triggers.json", wrongBindingMutation.quarantineRecords().getFirst().sourceLocation()));

        Map<String, Object> graph = new LinkedHashMap<>(commandGraph("broken", 1, "mutation-001"));
        graph.put("resourceRevision", "one");
        write("assets/Commands/broken.json", graph);
        write("triggers.json", Map.of("commands", List.of(Map.of("path", "broken run", "resource", Map.of("type", "command", "id", "broken")))));
        Adaptation wrongGraphAuthority = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        assertAll(
            () -> assertTrue(wrongGraphAuthority.changes().isEmpty()),
            () -> assertEquals("MIGRATION.COMMAND_AUTHORITY_INVALID", wrongGraphAuthority.quarantineRecords().getFirst().code()),
            () -> assertEquals("assets/Commands/broken.json", wrongGraphAuthority.quarantineRecords().getFirst().sourceLocation()));

        Files.write(temporaryDirectory.resolve("assets/Commands/broken.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        Adaptation malformedGraph = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        var graphQuarantine = malformedGraph.quarantineRecords().stream()
            .filter(record -> record.code().equals("MIGRATION.COMMAND_GRAPH_INVALID"))
            .findFirst()
            .orElseThrow();
        assertAll(
            () -> assertTrue(malformedGraph.changes().isEmpty()),
            () -> assertEquals("assets/Commands/broken.json", graphQuarantine.sourceLocation()),
            () -> assertEquals(sourceHash("assets/Commands/broken.json"), graphQuarantine.sourceHash()));

        write("triggers.json", Map.of("commands", List.of()));
        Adaptation malformedGraphWithoutRows = new TriggerCommandMigrationAdapter().adapt(input("triggers.json", "assets/Commands/broken.json"));
        assertAll(
            () -> assertTrue(malformedGraphWithoutRows.changes().isEmpty()),
            () -> assertEquals(1, malformedGraphWithoutRows.quarantineRecords().size()),
            () -> assertEquals("MIGRATION.COMMAND_GRAPH_INVALID", malformedGraphWithoutRows.quarantineRecords().getFirst().code()),
            () -> assertEquals("assets/Commands/broken.json", malformedGraphWithoutRows.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sourceHash("assets/Commands/broken.json"), malformedGraphWithoutRows.quarantineRecords().getFirst().sourceHash()));
    }

    @Test
    void appliedMigrationHasZeroSecondRunChanges() throws Exception {
        String context = CanonicalJson.canonicalize(Map.of("command", "/Example", "subcommands", List.of("one", "two   <target>"), "structured", false));
        write("triggers.json", List.of(
            Map.of("id", "example:event", "flowId", "example", "type", "EVENT", "context", "server_start"),
            Map.of("id", "example:command", "flowId", "example", "type", "COMMAND", "context", context),
            Map.of("id", "example:system", "flowId", "example", "type", "SYSTEM", "context", "shutdown")));
        write("assets/Commands/example.json", commandGraph("example", 12, "mutation-012"));
        TriggerCommandMigrationAdapter adapter = new TriggerCommandMigrationAdapter();

        Adaptation first = adapter.adapt(input("triggers.json", "assets/Commands/example.json"));
        apply(first);
        Adaptation second = adapter.adapt(input("triggers.json", "assets/Commands/example.json"));

        assertAll(
            () -> assertEquals(2, first.changes().size()),
            () -> assertTrue(first.quarantineRecords().isEmpty()),
            () -> assertTrue(second.changes().isEmpty()),
            () -> assertTrue(second.quarantineRecords().isEmpty()),
            () -> assertEquals(first.claims(), second.claims()));
        List<Object> remaining = array(CanonicalJson.parse(Files.readAllBytes(temporaryDirectory.resolve("triggers.json"))));
        assertEquals(List.of("EVENT", "SYSTEM"), remaining.stream().map(TriggerCommandMigrationAdapterTest::object).map(row -> row.get("type")).toList());
    }

    @Test
    void composerPlansStagesAndThenProducesZeroChangesWithExactOwners() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("composer-source"));
        write(source, "triggers.json", Map.of(
            "events", List.of(Map.of("event", "server_start")),
            "commands", List.of(Map.of("path", "composer run", "resource", Map.of("type", "command", "id", "composer")))));
        write(source, "assets/Commands/composer.json", commandGraph("composer", 5, "mutation-005"));
        Snapshot snapshot = snapshot(source, temporaryDirectory.resolve("composer-snapshot"), 1, "composer-source", "legacy-build",
            ProductionPersistenceOwners.TRIGGERS);
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new TriggerCommandMigrationAdapter()));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertAll(
            () -> assertEquals(2, proposal.plan().operations().size()),
            () -> assertTrue(proposal.quarantineReport().records().isEmpty()));
        StagedMigration staged = upgrade.stage(snapshot.root(), temporaryDirectory.resolve("composer-staged"), proposal.plan());
        Map<String, Object> stagedTriggers = object(CanonicalJson.parse(Files.readAllBytes(staged.root().resolve("triggers.json"))));
        Map<String, Object> stagedGraph = object(CanonicalJson.parse(Files.readAllBytes(staged.root().resolve("assets/Commands/composer.json"))));
        assertAll(
            () -> assertEquals(List.of(), stagedTriggers.get("commands")),
            () -> assertEquals(List.of("composer run"), stagedGraph.get("commandPaths")),
            () -> assertEquals(5L, ((BigDecimal) stagedGraph.get("resourceRevision")).longValueExact()),
            () -> assertEquals("mutation-005", stagedGraph.get("mutationId")));

        Snapshot canonical = snapshot(staged.root(), temporaryDirectory.resolve("composer-canonical-snapshot"), 2, "composer-canonical", "replacement-build",
            ProductionPersistenceOwners.TRIGGERS);
        UpgradeProposal second = upgrade.plan(canonical, window(2, "replacement-build", 3, "next-build"));
        assertAll(
            () -> assertTrue(second.plan().operations().isEmpty()),
            () -> assertTrue(second.quarantineReport().records().isEmpty()));
    }

    @Test
    void composerRejectsOwnerMismatchWithoutPartialGraphChange() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("owner-mismatch-source"));
        write(source, "triggers.json", Map.of("commands", List.of(Map.of(
            "path", "mismatch run", "resource", Map.of("type", "command", "id", "mismatch")))));
        write(source, "assets/Commands/mismatch.json", commandGraph("mismatch", 2, "mutation-002"));
        Snapshot snapshot = snapshot(source, temporaryDirectory.resolve("owner-mismatch-snapshot"), 1, "owner-mismatch", "legacy-build", "wrong.triggers");

        UpgradeProposal proposal = new ReSyncTypedLifecycleUpgrade(List.of(new TriggerCommandMigrationAdapter()))
            .plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertEquals(1, proposal.quarantineReport().records().size()),
            () -> assertEquals("MIGRATION.LIFECYCLE_OWNER_MISMATCH", proposal.quarantineReport().records().getFirst().code()),
            () -> assertEquals("triggers.json", proposal.quarantineReport().records().getFirst().sourceLocation()));
    }

    private Map<String, Object> commandGraph(String id, long revision, String mutationId) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", id);
        graph.put("resourceType", "command");
        graph.put("resourceRevision", revision);
        graph.put("mutationId", mutationId);
        graph.put("nodes", Map.of("start", Map.of("type", "event.resync.command", "inputs", Map.of("opaque", "kept"))));
        graph.put("connections", List.of(Map.of("source", "start", "target", "next")));
        return graph;
    }

    private void write(String relativePath, Object value) throws Exception {
        write(temporaryDirectory, relativePath, value);
    }

    private void write(Path root, String relativePath, Object value) throws Exception {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, CanonicalJson.canonicalBytes(value));
    }

    private Input input(String... relativePaths) throws Exception {
        List<SourceFile> files = new ArrayList<>();
        for (String relativePath : relativePaths) {
            Path path = temporaryDirectory.resolve(relativePath);
            byte[] bytes = Files.readAllBytes(path);
            String owner = relativePath.equals("triggers.json") ? ProductionPersistenceOwners.TRIGGERS : ProductionPersistenceOwners.FLOW_ASSETS;
            files.add(new SourceFile(relativePath, bytes.length, sha256(bytes), owner));
        }
        return new Input(temporaryDirectory, SnapshotMetadata.preflight(), MANIFEST, files);
    }

    private String sourceHash(String relativePath) throws Exception {
        return sha256(Files.readAllBytes(temporaryDirectory.resolve(relativePath)));
    }

    private Snapshot snapshot(Path source, Path staging, int format, String snapshotId, String build, String triggerOwner) throws Exception {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, source.resolve("assets")));
        participants.register(participant(triggerOwner, source.resolve("triggers.json")));
        SnapshotMetadata metadata = new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, "b".repeat(64), Map.of());
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);
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

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }

    private void apply(Adaptation adaptation) throws Exception {
        for (Change change : adaptation.changes()) {
            Files.write(temporaryDirectory.resolve(change.targetPath()), change.targetBytes());
        }
    }

    private static Map<String, Object> changedObject(Adaptation result, String path) {
        Change change = result.changes().stream().filter(candidate -> candidate.targetPath().equals(path)).findFirst().orElseThrow();
        return object(CanonicalJson.parse(change.targetBytes()));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static List<Object> array(Object value) {
        return new ArrayList<>((List<?>) value);
    }
}
