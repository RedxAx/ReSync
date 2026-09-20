package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.metadata.MinecraftSchemaBundle;
import restudio.resync.metadata.MinecraftSchemaBundle.RuntimeIdentity;
import restudio.resync.metadata.MinecraftSchemaBundleCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSchemaExportFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsCanonicalBundleAtomicallyWithStableDigest() throws Exception {
        MinecraftSchemaBundle bundle = new MinecraftSchemaBundle("26.3", "2026-09-20T00:00:00Z",
            new RuntimeIdentity("paper", "26.3", "git-Paper-130", "api"), List.of(), List.of());

        MinecraftSchemaExportFiles.Result first = MinecraftSchemaExportFiles.write(temporaryDirectory, bundle);
        MinecraftSchemaExportFiles.Result second = MinecraftSchemaExportFiles.write(temporaryDirectory, bundle);

        assertEquals(temporaryDirectory.resolve("exports/minecraft-schema-26.3.json").toAbsolutePath(), first.path());
        assertEquals(first.path(), second.path());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(first.bytes(), second.bytes());
        assertEquals(bundle, new MinecraftSchemaBundleCodec().decodeBytes(Files.readAllBytes(first.path())));
        assertTrue(Files.isRegularFile(first.path()));
        try (var files = Files.list(first.path().getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
