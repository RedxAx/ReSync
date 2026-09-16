package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotManifestValidationTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsNegativeExtensionCount() throws IOException {
        Path manifest = writeManifest(-1, 0, 0);

        assertThrows(MigrationException.class, () -> SnapshotManifest.read(manifest));
    }

    @Test
    void rejectsNegativeDirectoryCount() throws IOException {
        Path manifest = writeManifest(0, -1, 0);

        assertThrows(MigrationException.class, () -> SnapshotManifest.read(manifest));
    }

    @Test
    void rejectsNegativeFileCount() throws IOException {
        Path manifest = writeManifest(0, 0, -1);

        assertThrows(MigrationException.class, () -> SnapshotManifest.read(manifest));
    }

    @Test
    void rejectsFileAncestorOfAnotherFile() {
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of(), List.of(entry("data"), entry("data/value"))));
    }

    @Test
    void rejectsFileAncestorOfDirectory() {
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of("data/value"), List.of(entry("data"))));
    }

    @Test
    void allowsDirectoryAncestorOfFile() {
        SnapshotManifest manifest = manifest(List.of("data"), List.of(entry("data/value")));

        assertEquals(List.of("data"), manifest.directories());
        assertEquals(List.of("data/value"), manifest.entries().stream().map(SnapshotManifest.Entry::relativePath).toList());
    }

    @Test
    void requiresSlashBoundaryForFileAncestors() {
        SnapshotManifest manifest = manifest(List.of("database-copy/value"), List.of(entry("database")));

        assertEquals(List.of("database-copy/value"), manifest.directories());
    }

    private SnapshotManifest manifest(List<String> directories, List<SnapshotManifest.Entry> entries) {
        return new SnapshotManifest(SnapshotMetadata.preflight(), directories, entries);
    }

    private SnapshotManifest.Entry entry(String path) {
        return new SnapshotManifest.Entry(path, 0, "a".repeat(64), "owner");
    }

    private Path writeManifest(int extensions, int directories, int files) throws IOException {
        Path path = temporary.resolve("manifest-" + extensions + "-" + directories + "-" + files);
        List<String> lines = List.of(
            "format=1",
            "snapshot-id=" + MigrationCanonical.encode("preflight"),
            "created-at=0",
            "build=" + MigrationCanonical.encode("preflight"),
            "catalog=" + "0".repeat(64),
            "extensions=" + extensions,
            "directories=" + directories,
            "files=" + files,
            "manifest-hash=" + "0".repeat(64));
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return path;
    }
}
