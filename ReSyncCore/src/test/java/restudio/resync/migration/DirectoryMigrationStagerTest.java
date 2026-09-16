package restudio.resync.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryMigrationStagerTest {
    @TempDir
    Path temporary;

    @Test
    void excludesExistingQuarantineEvidenceFromTheStagedRoot() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("ordinary.txt"), "ordinary");
        Files.createDirectories(source.resolve(".quarantine/evidence"));
        Files.writeString(source.resolve(".quarantine/evidence/failed.txt"), "failed");

        StagedMigration staged = new DirectoryMigrationStager().stage(source, temporary.resolve("staging"), plan());

        assertEquals("ordinary", Files.readString(staged.root().resolve("ordinary.txt")));
        assertFalse(Files.exists(staged.root().resolve(".quarantine")));
        assertNotEquals(TreeDigest.of(source), TreeDigest.migratableOf(source));
        assertEquals(TreeDigest.migratableOf(source), staged.contentHash());
        Files.writeString(source.resolve("ordinary.txt"), "changed");
        assertNotEquals(staged.contentHash(), TreeDigest.migratableOf(source));
    }

    @Test
    void rejectsMalformedReservedQuarantineEntry() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("malformed-source"));
        Files.writeString(source.resolve(".quarantine"), "not-a-directory");

        assertThrows(MigrationException.class,
            () -> new DirectoryMigrationStager().stage(source, temporary.resolve("malformed-staging"), plan()));
    }

    @Test
    void rejectsSymlinkInsideReservedQuarantineEvidence() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("symlink-source"));
        Path quarantine = Files.createDirectories(source.resolve(".quarantine/evidence"));
        Path target = Files.createDirectory(temporary.resolve("outside"));
        try {
            Files.createSymbolicLink(quarantine.resolve("link"), target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        assertThrows(MigrationException.class,
            () -> new DirectoryMigrationStager().stage(source, temporary.resolve("symlink-staging"), plan()));
    }

    private MigrationPlan plan() {
        return new MigrationPlan("reserved-quarantine-test", "0".repeat(64), 1, 2,
            QuarantineReport.empty().reportHash(), List.of());
    }
}
