package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.migration.DirectoryMigrationStager;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;

class OfflineMigrationRehearsalTest {
    private static final ContractRef<ResourceTypeId> FLOW = ContractRef.of(OwnerId.of("resync"), ResourceTypeId.of("flow"));

    @TempDir
    Path temporary;

    @Test
    void plannerAndStagerAreDeterministicAndLeaveSourceUntouched() throws IOException, URISyntaxException {
        Path source = graphSource("deterministic", true);
        Snapshot snapshot = snapshot(source, "offline-deterministic");
        OfflineSnapshotUpgradePlanner planner = graphPlanner();
        UpgradeProposal first = planner.plan(snapshot, window());
        UpgradeProposal second = planner.plan(snapshot, window());

        assertEquals(first.plan().planHash(), second.plan().planHash());
        assertEquals(first.quarantineReport().canonicalText(), second.quarantineReport().canonicalText());
        assertEquals(first.plan().operations().getFirst().resourceKey().orElseThrow(), new ResourceKey(FLOW, "fixture-flow"));
        String sourceDigest = TreeDigest.of(snapshot.root());
        StagedMigration staged = new DirectoryMigrationStager().stage(
            snapshot.root(),
            temporary.resolve("deterministic-staged"),
            first.plan(),
            first.quarantineReport(),
            first.quarantineReport().accept("test", Instant.EPOCH));

        assertEquals(sourceDigest, TreeDigest.of(snapshot.root()));
        assertEquals(staged.contentHash(), TreeDigest.of(staged.root()));
        assertTrue(Files.exists(staged.root().resolve("replacement/graphs/fixture-flow.json")));
    }

    @Test
    void canonicalWinnerAndAcceptedDuplicateAndUnsupportedFilesAreMaterialized() throws IOException, URISyntaxException {
        Path source = graphSource("quarantine", true);
        Files.writeString(source.resolve("legacy/flows/unsupported.txt"), "unsupported");
        Snapshot snapshot = snapshot(source, "offline-quarantine");
        UpgradeProposal proposal = graphPlanner().plan(snapshot, window());

        assertEquals(1, proposal.plan().operations().size());
        assertEquals("assets/Blueprints/Flows/fixture-flow.json", proposal.plan().operations().getFirst().sourcePath());
        assertEquals(2, proposal.quarantineReport().records().size());
        StagedMigration staged = new DirectoryMigrationStager().stage(
            snapshot.root(),
            temporary.resolve("quarantine-staged"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("test", Instant.EPOCH));

        proposal.quarantineReport().records().forEach(record -> assertTrue(Files.exists(staged.root().resolve(".quarantine/migration")
            .resolve(record.recordId())
            .resolve(record.sourceLocation()))));
        assertTrue(Files.notExists(staged.root().resolve("legacy/flows/fixture-flow.json")));
        assertTrue(Files.notExists(staged.root().resolve("legacy/flows/unsupported.txt")));
        assertEquals("fixture-flow", Files.readString(staged.root().resolve("replacement/graphs/fixture-flow.json")).contains("fixture-flow") ? "fixture-flow" : "");
    }

    @Test
    void sourceHashAndTargetHashAreVerifiedBeforeAndAfterApplication() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("hash-source"));
        Files.writeString(source.resolve("legacy.json"), "legacy");
        String sourceHash = SnapshotManifest.scan(source, SnapshotMetadata.preflight(), participants(source)).entries().getFirst().sha256();
        MigrationPlan plan = plan(source, List.of(new MigrationOperation(MigrationOperationType.COPY, "hash.copy", "legacy.json", "replacement.json", sourceHash, "f".repeat(64))));
        Files.writeString(source.resolve("legacy.json"), "tampered");

        assertThrows(IOException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("hash-staged"), plan));
        Files.writeString(source.resolve("legacy.json"), "legacy");
        assertThrows(IOException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("target-hash-staged"), plan));
    }

    @Test
    void targetCollisionsAndUndeclaredWritesAreRejected() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("collision-source"));
        Files.writeString(source.resolve("one.json"), "one");
        Files.writeString(source.resolve("two.json"), "two");
        String oneHash = SnapshotManifest.scan(source, SnapshotMetadata.preflight(), participants(source)).entries().stream().filter(entry -> entry.relativePath().equals("one.json")).findFirst().orElseThrow().sha256();
        String twoHash = SnapshotManifest.scan(source, SnapshotMetadata.preflight(), participants(source)).entries().stream().filter(entry -> entry.relativePath().equals("two.json")).findFirst().orElseThrow().sha256();
        MigrationPlan collision = plan(source, List.of(
            new MigrationOperation(MigrationOperationType.COPY, "collision.one", "one.json", "replacement.json", oneHash, oneHash),
            new MigrationOperation(MigrationOperationType.COPY, "collision.two", "two.json", "replacement.json", twoHash, twoHash)));

        assertThrows(IOException.class, () -> new DirectoryMigrationStager().stage(source, temporary.resolve("collision-staged"), collision));

        Path nonEmpty = Files.createDirectory(temporary.resolve("non-empty-staged"));
        Files.writeString(nonEmpty.resolve("undeclared.txt"), "unexpected");
        MigrationPlan copy = plan(source, List.of());
        assertThrows(IOException.class, () -> new DirectoryMigrationStager().stage(source, nonEmpty, copy));
    }

    private OfflineSnapshotUpgradePlanner graphPlanner() {
        return OfflineSnapshotUpgradePlanner.forGraphFamily(OfflineSnapshotUpgradePlanner.graphFamily(FLOW, List.of(
            OfflineSnapshotUpgradePlanner.graphSource("canonical.graph", "assets/Blueprints/Flows", "replacement/graphs", "core", 0),
            OfflineSnapshotUpgradePlanner.graphSource("legacy.graph", "legacy/flows", "replacement/graphs", "core", 10))));
    }

    private Path graphSource(String name, boolean includeLegacy) throws IOException, URISyntaxException {
        Path source = Files.createDirectory(temporary.resolve(name));
        Path canonical = Path.of(getClass().getClassLoader().getResource("fixtures/node-replacement/full-folder/populated/assets/Blueprints/Flows/fixture-flow.json").toURI());
        Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.copy(canonical, source.resolve("assets/Blueprints/Flows/fixture-flow.json"));
        if (includeLegacy) {
            Path legacy = Path.of(getClass().getClassLoader().getResource("fixtures/node-replacement/migration/offline-graph-overlay/legacy/flows/fixture-flow.json").toURI());
            Files.createDirectories(source.resolve("legacy/flows"));
            Files.copy(legacy, source.resolve("legacy/flows/fixture-flow.json"));
        }
        return source;
    }

    private Snapshot snapshot(Path source, String id) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, temporary.resolve(id + "-snapshot"), metadata(id), participants(source));
    }

    private MigrationPlan plan(Path source, List<MigrationOperation> operations) throws IOException {
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("direct-plan");
        return new MigrationPlan("direct-plan", SnapshotManifest.scan(source, metadata, participants).manifestHash(), 1, 2, QuarantineReport.empty().reportHash(), operations);
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

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
    }

    private UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
    }
}
