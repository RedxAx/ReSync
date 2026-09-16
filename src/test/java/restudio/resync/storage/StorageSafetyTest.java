package restudio.resync.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

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

    @Test
    void jsonFileRejectsASymlinkedRootWithoutResolvingOutsideTheIntendedDirectory() throws Exception {
        Path outside = tempDir.resolve("outside");
        Path root = tempDir.resolve("json");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
            return;
        }

        assertThrows(IOException.class, () -> StorageSafety.jsonFile(root, "flow-one"));
        assertFalse(Files.exists(outside.resolve("flow-one.json")));
    }

    @Test
    void copyIfAbsentAtomicPreservesTheFirstBackup() throws Exception {
        Path source = tempDir.resolve("source.dat");
        Path target = tempDir.resolve("backup").resolve("source.dat");
        Files.writeString(source, "first");
        Files.createDirectories(target.getParent());

        StorageSafety.copyIfAbsentAtomic(source, target);
        Files.writeString(source, "second");
        StorageSafety.copyIfAbsentAtomic(source, target);

        assertEquals("first", Files.readString(target));
    }

    @Test
    void copyIfAbsentAtomicForcesContentBeforePublishingReadOnlyAttributes() throws Exception {
        Path source = tempDir.resolve("source.dat");
        Path target = tempDir.resolve("backup").resolve("source.dat");
        Files.createDirectories(target.getParent());
        Files.writeString(source, "read-only source");
        DosFileAttributeView sourceAttributes = Files.getFileAttributeView(source, DosFileAttributeView.class,
            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(sourceAttributes != null, "DOS attributes are unavailable");
        sourceAttributes.setReadOnly(true);
        try {
            StorageSafety.copyIfAbsentAtomic(source, target);

            assertEquals("read-only source", Files.readString(target));
            DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Assumptions.assumeTrue(targetAttributes != null, "DOS attributes are unavailable on the target");
            assertTrue(targetAttributes.readAttributes().isReadOnly());
        } finally {
            sourceAttributes.setReadOnly(false);
            DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (targetAttributes != null && Files.exists(target)) {
                targetAttributes.setReadOnly(false);
            }
        }
    }

    @Test
    void copyIfAbsentAtomicRejectsNonRegularBackupTarget() throws Exception {
        Path source = tempDir.resolve("source.dat");
        Path target = tempDir.resolve("backup").resolve("source.dat");
        Files.writeString(source, "data");
        Files.createDirectories(target);

        assertThrows(IOException.class, () -> StorageSafety.copyIfAbsentAtomic(source, target));
    }
}
