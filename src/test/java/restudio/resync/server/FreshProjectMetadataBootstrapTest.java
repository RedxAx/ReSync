package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.StorageSafety;
import restudio.resync.upgrade.AssetCoordinatorMigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshProjectMetadataBootstrapTest {
    private static final Gson GSON = new Gson();
    private static final String ARTIFACT_HASH = "2".repeat(64);
    private static final String MANIFEST_HASH = "3".repeat(64);
    private static final AssetKey LINEAGE_KEY = new AssetKey("project_metadata.lineage", "project");

    @Test
    void createsCanonicalProjectIdentityAndLineageFromDurableFreshProof(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            ServerIdentityStore identity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            FreshProjectMetadataBootstrap.Admission admission = fixture.preflight();

            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                admission.ensure(identity, coordinator, GSON);

                Snapshot snapshot = coordinator.read(current -> current);
                assertEquals(1L, snapshot.rootSequence());
                assertEquals(1L, snapshot.project().revision());
                assertEquals(identity.serverId().canonicalText(), snapshot.metadata().document().get("serverId").getAsString());
                assertTrue(snapshot.state(LINEAGE_KEY).isPresent());
                JsonObject lineage = GSON.fromJson(Files.readString(lineagePath(fixture.activeRoot())), JsonObject.class);
                assertEquals(identity.serverId().canonicalText(), lineage.get("id").getAsString());
                assertEquals(1L, lineage.get("revision").getAsLong());
                assertEquals(ResourcePayloadCodecs.json().hashPayload(
                    GSON.fromJson(snapshot.metadata().serializedJson(), Map.class)).canonicalText(),
                    lineage.get("payloadHash").getAsString());
                assertEquals(snapshot.mutationValue(LINEAGE_KEY).orElseThrow(), lineage.get("mutationId").getAsString());
            }
        }
    }

    @Test
    void restartValidatesTheExistingFreshPairWithoutWritingItAgain(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            ServerIdentityStore initialIdentity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            byte[] projectBytes;
            byte[] lineageBytes;

            FreshProjectMetadataBootstrap.Admission initialAdmission = fixture.preflight();
            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                initialAdmission.ensure(initialIdentity, coordinator, GSON);
                projectBytes = Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json"));
                lineageBytes = Files.readAllBytes(lineagePath(fixture.activeRoot()));
            }

            ServerIdentityStore reopenedIdentity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            FreshProjectMetadataBootstrap.Admission restartAdmission = fixture.preflight();
            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                restartAdmission.ensure(reopenedIdentity, coordinator, GSON);

                Snapshot snapshot = coordinator.read(current -> current);
                assertEquals(1L, snapshot.rootSequence());
                assertEquals(1L, snapshot.project().revision());
                assertArrayEquals(projectBytes, Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json")));
                assertArrayEquals(lineageBytes, Files.readAllBytes(lineagePath(fixture.activeRoot())));
            }
        }
    }

    @Test
    void forgedFreshProvenanceFailsBeforeAssetGenesis(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            AssetCoordinatorMigration.Result durable = fixture.migration();
            AssetCoordinatorMigration.Result forged = new AssetCoordinatorMigration.Result(
                durable.artifactPath(), durable.artifactHash(), durable.manifestHash(),
                new AssetTransactionCoordinator.AdoptionInventory("fresh-root:" + "4".repeat(64), "{}", List.of()),
                List.of());

            assertThrows(IOException.class, () -> FreshProjectMetadataBootstrap.preflight(
                fixture.coordinationRoot(), fixture.activeRoot(), forged));
            assertTrue(Files.notExists(fixture.activeRoot().resolve("assets")));
            assertTrue(Files.notExists(fixture.activeRoot().resolve("assets/.asset-coordinator/genesis.json")));
        }
    }

    @Test
    void rejectsProjectIdentityWithoutCoordinatorLineageAndPreservesIt(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            ServerIdentityStore identity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            FreshProjectMetadataBootstrap.Admission admission = fixture.preflight();

            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                Snapshot snapshot = coordinator.read(current -> current);
                UUID partialMutation = UUID.randomUUID();
                coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                    partialMutation, snapshot.project(), List.of(),
                    List.of(ProjectDelta.set(List.of("serverId"),
                        new JsonPrimitive(identity.serverId().canonicalText())))));
                byte[] projectBytes = Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json"));

                assertThrows(IOException.class, () -> admission.ensure(identity, coordinator, GSON));
                assertEquals(1L, coordinator.read(Snapshot::rootSequence));
                boolean lineageMissing = coordinator.read(current -> current.state(LINEAGE_KEY).isEmpty());
                assertTrue(lineageMissing);
                assertArrayEquals(projectBytes, Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json")));
            }
        }
    }

    @Test
    void rejectsUncoordinatedLineageEvidenceAndPreservesIt(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            ServerIdentityStore identity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            FreshProjectMetadataBootstrap.Admission admission = fixture.preflight();

            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                Path lineage = lineagePath(fixture.activeRoot());
                Files.createDirectories(lineage.getParent());
                byte[] ambiguous = "{\"ambiguous\":true}".getBytes(StandardCharsets.UTF_8);
                Files.write(lineage, ambiguous);

                assertThrows(IOException.class, () -> admission.ensure(identity, coordinator, GSON));
                assertEquals(0L, coordinator.read(Snapshot::rootSequence));
                assertArrayEquals(ambiguous, Files.readAllBytes(lineage));
                assertEquals("{}", Files.readString(fixture.activeRoot().resolve("assets/project.json")));
            }
        }
    }

    @Test
    void rejectsCanonicalLineageForAnotherServerAndPreservesIt(@TempDir Path temporary) throws Exception {
        try (FreshFixture fixture = freshFixture(temporary)) {
            ServerIdentityStore identity = ServerIdentityStore.open(
                fixture.activeRoot().resolve(ServerIdentityStore.FILE_NAME));
            FreshProjectMetadataBootstrap.Admission admission = fixture.preflight();

            try (AssetTransactionCoordinator coordinator = fixture.openAssets()) {
                Snapshot snapshot = coordinator.read(current -> current);
                UUID mutationId = UUID.randomUUID();
                String otherServer = UUID.randomUUID().toString();
                List<ProjectDelta> deltas = List.of(
                    ProjectDelta.set(List.of("serverId"), new JsonPrimitive(otherServer)));
                AssetDelta lineage = ProjectMetadataLineage.writer(coordinator.canonicalRoot(), GSON)
                    .write(snapshot, deltas, mutationId);
                coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                    mutationId, snapshot.project(), List.of(lineage), deltas));
                byte[] projectBytes = Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json"));
                byte[] lineageBytes = Files.readAllBytes(lineagePath(fixture.activeRoot()));

                assertThrows(IOException.class, () -> admission.ensure(identity, coordinator, GSON));
                assertEquals(1L, coordinator.read(Snapshot::rootSequence));
                assertArrayEquals(projectBytes, Files.readAllBytes(fixture.activeRoot().resolve("assets/project.json")));
                assertArrayEquals(lineageBytes, Files.readAllBytes(lineagePath(fixture.activeRoot())));
                assertEquals(StorageSafety.sha256(lineageBytes), coordinator.read(current ->
                    current.state(LINEAGE_KEY).orElseThrow()).hash());
            }
        }
    }

    @Test
    void nonFreshEmptyCoordinatorFailsClosed(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        ServerIdentityStore identity = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        AssetCoordinatorMigration.Result migration = nonFreshMigration(temporary);
        FreshProjectMetadataBootstrap.Admission admission = FreshProjectMetadataBootstrap.preflight(
            temporary.resolve("coordination"), activeRoot, migration);

        try (AssetTransactionCoordinator coordinator = migration.openOrAdopt(activeRoot.resolve("assets"), GSON)) {
            assertThrows(IOException.class, () -> admission.ensure(identity, coordinator, GSON));
            assertEquals(0L, coordinator.read(Snapshot::rootSequence));
            assertEquals("{}", Files.readString(activeRoot.resolve("assets/project.json")));
            assertTrue(Files.notExists(lineagePath(activeRoot)));
        }
    }

    @Test
    void nonFreshCanonicalRestartValidatesWithoutWriting(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        ServerIdentityStore identity = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        AssetCoordinatorMigration.Result migration = nonFreshMigration(temporary);
        byte[] projectBytes;
        byte[] lineageBytes;

        try (AssetTransactionCoordinator coordinator = migration.openOrAdopt(activeRoot.resolve("assets"), GSON)) {
            seedCanonicalPair(coordinator, identity.serverId().canonicalText());
            projectBytes = Files.readAllBytes(activeRoot.resolve("assets/project.json"));
            lineageBytes = Files.readAllBytes(lineagePath(activeRoot));
        }

        FreshProjectMetadataBootstrap.Admission admission = FreshProjectMetadataBootstrap.preflight(
            temporary.resolve("coordination"), activeRoot, migration);
        try (AssetTransactionCoordinator coordinator = migration.openOrAdopt(activeRoot.resolve("assets"), GSON)) {
            admission.ensure(identity, coordinator, GSON);

            assertEquals(1L, coordinator.read(Snapshot::rootSequence));
            assertArrayEquals(projectBytes, Files.readAllBytes(activeRoot.resolve("assets/project.json")));
            assertArrayEquals(lineageBytes, Files.readAllBytes(lineagePath(activeRoot)));
        }
    }

    private static FreshFixture freshFixture(Path temporary) throws Exception {
        Path coordinationRoot = temporary.resolve("coordination");
        ReSyncPersistenceCoordinator persistence = ReSyncPersistenceCoordinator.bootstrap(
            temporary.resolve("source"), coordinationRoot);
        try {
            Path activeRoot = persistence.prepareActiveRoot();
            AssetCoordinatorMigration.Result migration = AssetCoordinatorMigration.prepareEmpty(
                coordinationRoot, persistence.freshRootProvenance().orElseThrow());
            return new FreshFixture(persistence, activeRoot, coordinationRoot, migration);
        } catch (Exception failure) {
            try {
                persistence.shutdown();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static AssetCoordinatorMigration.Result nonFreshMigration(Path temporary) {
        return new AssetCoordinatorMigration.Result(
            temporary.resolve("coordination/asset-adoption-v1.json"), ARTIFACT_HASH, MANIFEST_HASH,
            new AssetTransactionCoordinator.AdoptionInventory("snapshot:" + "1".repeat(64), "{}", List.of()),
            List.of());
    }

    private static void seedCanonicalPair(AssetTransactionCoordinator coordinator, String serverId) throws IOException {
        Snapshot snapshot = coordinator.read(current -> current);
        UUID mutationId = UUID.randomUUID();
        List<ProjectDelta> deltas = List.of(
            ProjectDelta.set(List.of("serverId"), new JsonPrimitive(serverId)));
        AssetDelta lineage = ProjectMetadataLineage.writer(coordinator.canonicalRoot(), GSON)
            .write(snapshot, deltas, mutationId);
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
            mutationId, snapshot.project(), List.of(lineage), deltas));
    }

    private static Path lineagePath(Path activeRoot) {
        return activeRoot.resolve("assets/.durability/project-metadata-lineage.v1.json");
    }

    private record FreshFixture(ReSyncPersistenceCoordinator persistence, Path activeRoot, Path coordinationRoot,
                                AssetCoordinatorMigration.Result migration) implements AutoCloseable {
        private FreshProjectMetadataBootstrap.Admission preflight() throws IOException {
            return FreshProjectMetadataBootstrap.preflight(coordinationRoot, activeRoot, migration);
        }

        private AssetTransactionCoordinator openAssets() throws IOException {
            return migration.openOrAdopt(activeRoot.resolve("assets"), GSON);
        }

        @Override
        public void close() throws IOException {
            persistence.shutdown();
        }
    }
}
