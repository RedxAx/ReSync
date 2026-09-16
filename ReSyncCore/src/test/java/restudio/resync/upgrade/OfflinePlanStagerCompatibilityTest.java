package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.DirectoryMigrationStager;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;

class OfflinePlanStagerCompatibilityTest {
    @TempDir
    Path temporary;

    @Test
    void genericDirectoryPlanOperationsRemainExecutable() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("generic-source"));
        Files.createDirectories(source.resolve("legacy/graphs"));
        Files.createDirectories(source.resolve("legacy/resources"));
        Files.writeString(source.resolve("legacy/graphs/graph.json"), "graph");
        Files.writeString(source.resolve("legacy/resources/item.json"), "item");
        Snapshot snapshot = snapshot(source, "generic-snapshot");
        OfflineSnapshotUpgradePlanner planner = new OfflineSnapshotUpgradePlanner(List.of(
            OfflineSnapshotUpgradePlanner.graphDirectory("legacy/graphs", "replacement/graphs", "core"),
            OfflineSnapshotUpgradePlanner.resourceDirectory("legacy/resources", "replacement/resources", "core")));

        UpgradeProposal proposal = planner.plan(snapshot, window());
        new DirectoryMigrationStager().stage(
            snapshot.root(),
            temporary.resolve("generic-staged"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("test", Instant.EPOCH));

        assertTrue(Files.exists(temporary.resolve("generic-staged/replacement/graphs/graph.json")));
        assertTrue(Files.exists(temporary.resolve("generic-staged/replacement/resources/item.json")));
    }

    @Test
    void reservedQuarantineDirectoryCannotEnterAStagedRoot() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("reserved-source"));
        Path evidence = Files.createDirectories(source.resolve(".quarantine/evidence")).resolve("failed.txt");
        Files.writeString(evidence, "failed");
        Snapshot snapshot = snapshot(source, "reserved-snapshot");
        MigrationPlan plan = new MigrationPlan(
            snapshot.metadata().snapshotId(),
            snapshot.manifest().manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.<MigrationOperation>of());

        var staged = new DirectoryMigrationStager().stage(source, temporary.resolve("reserved-staged"), plan);

        assertFalse(Files.exists(staged.root().resolve(".quarantine")));
        assertTrue(Files.exists(evidence));
    }

    @Test
    void replacementConsumesItsSourceAndSupportsInPlaceOrReplacementPaths() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("replacement-source"));
        Files.writeString(source.resolve("value.json"), "value");
        Snapshot snapshot = snapshot(source, "replacement-snapshot");
        String hash = snapshot.manifest().entries().getFirst().sha256();
        MigrationPlan inPlace = new MigrationPlan(
            snapshot.metadata().snapshotId(),
            snapshot.manifest().manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(new MigrationOperation(MigrationOperationType.REPLACE, "replacement.in-place", "value.json", "value.json", hash, hash)));

        new DirectoryMigrationStager().stage(source, temporary.resolve("replacement-staged"), inPlace);
        MigrationPlan moved = new MigrationPlan(
            snapshot.metadata().snapshotId(),
            snapshot.manifest().manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(new MigrationOperation(MigrationOperationType.REPLACE, "replacement.cross-path", "value.json", "new.json", hash, hash)));
        new DirectoryMigrationStager().stage(source, temporary.resolve("replacement-moved"), moved);
        assertFalse(Files.exists(temporary.resolve("replacement-moved/value.json")));
        assertTrue(Files.exists(temporary.resolve("replacement-moved/new.json")));
    }

    @Test
    void convertCannotBeTreatedAsAFileCopy() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("convert-source"));
        Files.writeString(source.resolve("value.json"), "value");
        Snapshot snapshot = snapshot(source, "convert-snapshot");
        String hash = snapshot.manifest().entries().getFirst().sha256();
        MigrationPlan convert = new MigrationPlan(
            snapshot.metadata().snapshotId(),
            snapshot.manifest().manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(new MigrationOperation(MigrationOperationType.CONVERT, "conversion.missing", "value.json", "new.json", hash, hash)));

        assertThrows(MigrationException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("convert-staged"), convert));
    }

    @Test
    void onlyCopyPreservesItsSource() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("operations-source"));
        Files.writeString(source.resolve("copy.txt"), "copy");
        Files.writeString(source.resolve("move.txt"), "move");
        Files.writeString(source.resolve("rename.txt"), "rename");
        Files.writeString(source.resolve("replace.txt"), "replace");
        Files.writeString(source.resolve("delete.txt"), "delete");
        SnapshotMetadata metadata = metadata("operations");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants(source));
        Map<String, String> hashes = manifest.entries().stream().collect(java.util.stream.Collectors.toMap(SnapshotManifest.Entry::relativePath, SnapshotManifest.Entry::sha256));
        MigrationPlan plan = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(
                operation(MigrationOperationType.COPY, "copy.txt", "copy-target.txt", hashes.get("copy.txt"), hashes.get("copy.txt")),
                operation(MigrationOperationType.MOVE, "move.txt", "move-target.txt", hashes.get("move.txt"), hashes.get("move.txt")),
                operation(MigrationOperationType.RENAME, "rename.txt", "rename-target.txt", hashes.get("rename.txt"), hashes.get("rename.txt")),
                operation(MigrationOperationType.REPLACE, "replace.txt", "replace-target.txt", hashes.get("replace.txt"), hashes.get("replace.txt")),
                operation(MigrationOperationType.DELETE, "delete.txt", "", hashes.get("delete.txt"), "")));

        new DirectoryMigrationStager().stage(source, temporary.resolve("operations-staged"), plan);
        Path staged = temporary.resolve("operations-staged");

        assertTrue(Files.exists(staged.resolve("copy.txt")));
        assertTrue(Files.exists(staged.resolve("copy-target.txt")));
        assertFalse(Files.exists(staged.resolve("move.txt")));
        assertTrue(Files.exists(staged.resolve("move-target.txt")));
        assertFalse(Files.exists(staged.resolve("rename.txt")));
        assertTrue(Files.exists(staged.resolve("rename-target.txt")));
        assertFalse(Files.exists(staged.resolve("replace.txt")));
        assertTrue(Files.exists(staged.resolve("replace-target.txt")));
        assertFalse(Files.exists(staged.resolve("delete.txt")));
    }

    @Test
    void replaceInPlaceConsumesTheSourcePathWithoutRemovingTheReplacement() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("replace-in-place-source"));
        Files.writeString(source.resolve("value.txt"), "value");
        SnapshotMetadata metadata = metadata("replace-in-place");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants(source));
        String hash = manifest.entries().getFirst().sha256();
        MigrationPlan plan = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(operation(MigrationOperationType.REPLACE, "value.txt", "value.txt", hash, hash)));

        new DirectoryMigrationStager().stage(source, temporary.resolve("replace-in-place-staged"), plan);

        assertTrue(Files.exists(temporary.resolve("replace-in-place-staged/value.txt")));
    }

    @Test
    void convertRejectsAnUnchangedRawCopy() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("convert-source"));
        Files.writeString(source.resolve("value.txt"), "value");
        SnapshotMetadata metadata = metadata("convert");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants(source));
        String hash = manifest.entries().getFirst().sha256();
        MigrationPlan unchanged = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(operation(MigrationOperationType.CONVERT, "value.txt", "converted.txt", hash, hash)));

        assertThrows(MigrationException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("convert-unchanged-staged"), unchanged));

        MigrationPlan claimedChanged = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(operation(MigrationOperationType.CONVERT, "value.txt", "converted.txt", hash, "f".repeat(64))));
        assertThrows(MigrationException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("convert-raw-copy-staged"), claimedChanged));
    }

    @Test
    void destructiveSourceReuseAndQuarantineTargetCollisionAreRejected() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("reuse-source"));
        Files.writeString(source.resolve("value.txt"), "value");
        Files.writeString(source.resolve("other.txt"), "other");
        SnapshotMetadata metadata = metadata("reuse");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants(source));
        String hash = manifest.entries().stream().filter(entry -> entry.relativePath().equals("value.txt")).findFirst().orElseThrow().sha256();
        MigrationPlan reused = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            QuarantineReport.empty().reportHash(),
            List.of(
                operation(MigrationOperationType.MOVE, "value.txt", "moved.txt", hash, hash),
                operation(MigrationOperationType.DELETE, "value.txt", "", hash, "")));
        assertThrows(MigrationException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("reuse-staged"), reused));

        QuarantineReport report = new QuarantineReport(List.of(new QuarantineRecord(
            "record", "MIGRATION.TEST", "value.txt", "Unsupported value", List.of(), "Review it", hash)));
        MigrationPlan collision = new MigrationPlan(
            metadata.snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            report.reportHash(),
            List.of(operation(MigrationOperationType.COPY, "value.txt", "value.txt", hash, hash)));
        assertThrows(MigrationException.class, () -> new DirectoryMigrationStager().stage(
            source,
            temporary.resolve("quarantine-collision-staged"),
            collision,
            report,
            report.accept("test", Instant.EPOCH)));
    }

    private Snapshot snapshot(Path source, String id) throws IOException {
        return new SnapshotService(new MigrationFence()).create(
            source,
            temporary.resolve(id + "-snapshot"),
            new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of()),
            participants(source));
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

    private UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
    }

    private MigrationOperation operation(MigrationOperationType type, String source, String target, String sourceHash, String targetHash) {
        return new MigrationOperation(type, "test." + type.wireName(), source, target, sourceHash, targetHash);
    }

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
    }
}
