package restudio.resync.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AssetTransactionManagerRootSafetyTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void ordinaryDirectoryRootsRemainUsable() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        Path target = assets.resolve("ordinary.json");

        manager.commit(Map.of(target, "ordinary"), "ordinary");

        assertEquals("ordinary", Files.readString(target));
        assertTrue(Files.isDirectory(assets.resolve(".transactions")));
        assertTrue(Files.isDirectory(assets.resolve(".snapshots")));
    }

    @Test
    void rejectsSymlinkedTransactionRoot() throws Exception {
        Path assets = Files.createDirectory(tempDir.resolve("transaction-assets"));
        Path outside = Files.createDirectories(tempDir.resolve("transaction-target"));
        createSymlink(assets.resolve(".transactions"), outside);

        assertThrows(IOException.class, () -> new AssetTransactionManager(assets, GSON));
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void rejectsSymlinkedSnapshotRootAndUnsafeAssetAncestor() throws Exception {
        Path assets = Files.createDirectory(tempDir.resolve("snapshot-assets"));
        Files.createDirectory(assets.resolve(".transactions"));
        Path outside = Files.createDirectories(tempDir.resolve("snapshot-target"));
        createSymlink(assets.resolve(".snapshots"), outside);

        assertThrows(IOException.class, () -> new AssetTransactionManager(assets, GSON));
        assertTrue(Files.isDirectory(outside));

        Path linkedParent = tempDir.resolve("linked-parent");
        createSymlink(linkedParent, outside);
        assertThrows(IOException.class,
            () -> new AssetTransactionManager(linkedParent.resolve("assets"), GSON));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable");
        } catch (IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
