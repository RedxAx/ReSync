package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationCoordinator;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;

class OfflineSnapshotUpgradePlannerTest {
    @TempDir
    Path temporary;

    @Test
    void bindsSupportedFilesToCompleteManifestHashesAndQuarantinesTheRest() throws IOException {
        Path source = source("identity");
        Snapshot snapshot = snapshot(source, "identity-snapshot");
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.exact("canonical/value.json", "core")));

        UpgradeProposal proposal = planner.plan(snapshot, window());
        MigrationOperation operation = proposal.plan().operations().stream().filter(value -> value.sourcePath().equals("canonical/value.json")).findFirst().orElseThrow();
        SnapshotManifest.Entry entry = snapshot.manifest().entries().stream().filter(value -> value.relativePath().equals("canonical/value.json")).findFirst().orElseThrow();

        assertEquals(entry.sha256(), operation.sourceHash());
        assertEquals(entry.sha256(), operation.targetHash());
        assertEquals(operation.sourcePath(), operation.targetPath());
        assertEquals(1, proposal.quarantineReport().records().size());
        assertEquals("MIGRATION.RUNTIME_LEGACY_INPUT", proposal.quarantineReport().records().getFirst().code());
        assertEquals("legacy/old.json", proposal.quarantineReport().records().getFirst().sourceLocation());
        assertEquals(entry.sha256(), operation.sourceHash());
        assertTrue(proposal.hasErrors());
        assertEquals("legacy", Files.readString(source.resolve("legacy/old.json")));
    }

    @Test
    void dryRunAndSecondRunAreDeterministicAndFailClosed() throws IOException {
        Path source = source("dry-run");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("dry-run-snapshot");
        UpgradeSourceWindow window = window();
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.exact("canonical/value.json", "core")));
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(new MigrationFence()), new MigrationCoordinator(new MigrationFence(), participants));

        UpgradeDryRunResult first = upgrader.dryRun(request(source, temporary.resolve("first-snapshot"), metadata, participants, window, planner));
        UpgradeDryRunResult second = upgrader.dryRun(request(source, temporary.resolve("second-snapshot"), metadata, participants, window, planner));

        assertEquals(UpgradeStatus.FAILED, first.status());
        assertFalse(first.canApply());
        assertEquals(first.proposal().orElseThrow().plan().planHash(), second.proposal().orElseThrow().plan().planHash());
        assertEquals(first.proposal().orElseThrow().quarantineReport().canonicalText(), second.proposal().orElseThrow().quarantineReport().canonicalText());
        assertEquals(first.diagnostics().toJson(), second.diagnostics().toJson());
    }

    @Test
    void immutableSnapshotViewRejectsRetainedSnapshotTampering() throws IOException {
        Snapshot snapshot = snapshot(source("tamper"), "tamper-snapshot");
        Files.writeString(snapshot.root().resolve("canonical/value.json"), "changed");

        assertThrows(IOException.class, () -> ImmutableSnapshotAdapter.adapt(snapshot));
    }

    @Test
    void directoryRuleMapsNestedGraphAndResourceFilesDeterministically() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("directories"));
        Files.createDirectories(source.resolve("legacy/graphs/nested"));
        Files.createDirectories(source.resolve("legacy/resources"));
        Files.writeString(source.resolve("legacy/graphs/nested/graph.json"), "graph");
        Files.writeString(source.resolve("legacy/resources/item.json"), "resource");
        Snapshot snapshot = snapshot(source, "directories-snapshot");
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.graphDirectory("legacy/graphs", "replacement/graphs", "core"),
            OfflineSnapshotUpgradePlanner.resourceDirectory("legacy/resources", "replacement/resources", "core")));

        UpgradeProposal proposal = planner.plan(snapshot, window());

        assertEquals(List.of("legacy/graphs/nested/graph.json", "legacy/resources/item.json"), proposal.plan().operations().stream().map(MigrationOperation::sourcePath).toList());
        assertEquals(List.of("replacement/graphs/nested/graph.json", "replacement/resources/item.json"), proposal.plan().operations().stream().map(MigrationOperation::targetPath).toList());
        assertEquals(List.of("graph-copy", "resource-copy"), proposal.plan().operations().stream().map(MigrationOperation::kind).toList());
        assertTrue(proposal.quarantineReport().records().isEmpty());
    }

    @Test
    void overlappingDirectoryRulesQuarantineAmbiguousFiles() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("ambiguous"));
        Files.createDirectories(source.resolve("legacy/graphs/nested"));
        Files.writeString(source.resolve("legacy/graphs/nested/graph.json"), "graph");
        Snapshot snapshot = snapshot(source, "ambiguous-snapshot");
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.directory("legacy", "replacement/a", "core"),
            OfflineSnapshotUpgradePlanner.graphDirectory("legacy/graphs", "replacement/b", "core")));

        UpgradeProposal proposal = planner.plan(snapshot, window());

        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals(1, proposal.quarantineReport().records().size());
        assertTrue(proposal.quarantineReport().records().getFirst().reason().contains("Multiple offline adapters"));
    }

    @Test
    void targetCollisionsQuarantineEverySource() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("collision"));
        Files.createDirectories(source.resolve("legacy/one"));
        Files.createDirectories(source.resolve("legacy/two"));
        Files.writeString(source.resolve("legacy/one/value.json"), "one");
        Files.writeString(source.resolve("legacy/two/value.json"), "two");
        Snapshot snapshot = snapshot(source, "collision-snapshot");
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.directory("legacy/one", "replacement", "core"),
            OfflineSnapshotUpgradePlanner.directory("legacy/two", "replacement", "core")));

        UpgradeProposal proposal = planner.plan(snapshot, window());

        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals(2, proposal.quarantineReport().records().size());
        assertTrue(proposal.quarantineReport().records().stream().allMatch(record -> record.reason().contains("same replacement path")));
    }

    private UpgradeDryRunRequest request(Path source, Path snapshotStage, SnapshotMetadata metadata, PersistenceParticipantRegistry participants, UpgradeSourceWindow window, UpgradePlanner planner) {
        return new UpgradeDryRunRequest(source, snapshotStage, metadata, participants, window, planner, 0);
    }

    private Path source(String name) throws IOException {
        Path source = Files.createDirectory(temporary.resolve(name));
        Files.createDirectories(source.resolve("canonical"));
        Files.createDirectories(source.resolve("legacy"));
        Files.writeString(source.resolve("canonical/value.json"), "canonical");
        Files.writeString(source.resolve("legacy/old.json"), "legacy");
        return source;
    }

    private Snapshot snapshot(Path source, String snapshotId) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, temporary.resolve(snapshotId + "-stage"), metadata(snapshotId), participants(source));
    }

    private PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return source;
            }
        });
        return participants;
    }

    private SnapshotMetadata metadata(String snapshotId) {
        return new SnapshotMetadata(1, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
    }

    private UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
    }
}
