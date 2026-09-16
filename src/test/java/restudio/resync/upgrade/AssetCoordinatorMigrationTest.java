package restudio.resync.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.VerifiedSnapshotAdmission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetCoordinatorMigrationTest {
    @TempDir
    Path temporary;

    @Test
    void claimsOnlyPreparationFailsClosed() {
        assertThrows(MigrationException.class, () -> AssetCoordinatorMigration.prepare(temporary,
            (VerifiedSnapshotAdmission) null));
        assertThrows(MigrationException.class, () -> AssetCoordinatorMigration.prepare(temporary,
            (VerifiedSnapshotAdmission) null, List.of()));
    }

    @Test
    void runtimeLoadRejectsTheRetiredClaimsOnlyArtifact() throws Exception {
        Path coordination = Files.createDirectories(temporary.resolve("coordination"));
        Path artifact = coordination.resolve(AssetCoordinatorMigration.ARTIFACT_RELATIVE_PATH);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"format\":\"asset-coordinator-adoption-v1\",\"claims\":[]}",
            StandardCharsets.UTF_8);

        assertThrows(MigrationException.class, () -> AssetCoordinatorMigration.load(coordination));
    }
}
