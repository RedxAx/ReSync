package restudio.resync.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSafetyTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicWriteOverwritesWithoutBackupAndKeepsTargetInRoot() throws Exception {
        Path file = StorageSafety.jsonFile(tempDir, "flow-one");

        StorageSafety.writeUtf8Atomic(file, "{\"v\":1}");
        StorageSafety.writeUtf8Atomic(file, "{\"v\":2}");

        assertEquals("{\"v\":2}", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".bak")));
    }

    @Test
    void deleteRejectsTraversalTargets() throws Exception {
        Path safeFile = StorageSafety.jsonFile(tempDir, "tab-one");
        StorageSafety.writeUtf8Atomic(safeFile, "{}");

        assertThrows(IllegalArgumentException.class, () -> StorageSafety.jsonFile(tempDir, "../tab-one"));
        StorageSafety.deleteIfExists(safeFile);

        assertFalse(Files.exists(safeFile));
    }

    @Test
    void validatesBoundedSingleSegmentIds() {
        assertEquals("valid-Id_1.2", StorageSafety.validateId("valid-Id_1.2"));
        assertThrows(IllegalArgumentException.class, () -> StorageSafety.validateId("x/y"));
        assertThrows(IllegalArgumentException.class, () -> StorageSafety.validateId("x.json"));
        assertThrows(IllegalArgumentException.class, () -> StorageSafety.validateId(".."));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> StorageSafety.validateId("a".repeat(97))).getMessage().contains("Unsafe id"));
    }
}
