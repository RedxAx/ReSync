package restudio.resync.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.filesystem.windows.WindowsFileMutation;
import restudio.resync.filesystem.windows.WindowsFileIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReSyncExtensionManagerWindowsDurabilityTest {
    @TempDir
    Path extensionRoot;

    @Test
    void defaultProductionBackendUsesNativeWindowsDurability() {
        WindowsFileMutation mutation = WindowsFileMutation.system();
        assumeTrue(mutation.available(extensionRoot));

        ReSyncExtensionManager manager = new ReSyncExtensionManager(null, extensionRoot);

        assertTrue(manager.recoveryDurabilityAvailable());
    }

    @Test
    void windowsProductionBackendFlushesTheLifecycleDirectory() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path directory = Files.createDirectories(extensionRoot.resolve("directory"));
        RecordingBackend recordingBackend = new RecordingBackend();

        new ReSyncExtensionManager.SystemLifecycleRecoveryBackend(new WindowsFileMutation(recordingBackend))
            .forceDirectory(directory);

        assertEquals(directory.toAbsolutePath().normalize(), recordingBackend.flushed);
        assertNotEquals(directory.getParent().toAbsolutePath().normalize(), recordingBackend.flushed);
    }

    private static final class RecordingBackend implements WindowsFileMutation.Backend {
        private Path flushed;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public WindowsFileIdentity.Observation observe(Path path) throws IOException {
            throw new IOException("Observation is not expected");
        }

        @Override
        public void flush(Path path) {
            flushed = path;
        }

        @Override
        public void rename(Path source, Path destination) {
        }

        @Override
        public void deleteFile(Path path) {
        }

        @Override
        public void deleteTree(Path path) {
        }
    }
}
