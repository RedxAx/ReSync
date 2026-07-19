package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileHandlerPathPolicyTest {
    @TempDir
    Path root;

    @Test
    void resolvesNormalizedPathsInsideTheDataFolder() throws Exception {
        FileHandler handler = new FileHandler(root);

        assertEquals(root.resolve("flows/output.txt").normalize(), handler.resolveSafePath("flows/./output.txt"));
    }

    @Test
    void rejectsSiblingPathsThatOnlyShareTheRootNamePrefix() {
        FileHandler handler = new FileHandler(root);
        String sibling = "../" + root.getFileName() + "-outside/secret.txt";

        assertThrows(FileHandler.FileOperationException.class, () -> handler.resolveSafePath(sibling));
    }

    @Test
    void rejectsExistingSymbolicLinksThatEscapeTheDataFolder() throws IOException {
        Path outside = Files.createTempDirectory(root.getParent(), "resync-file-policy-");
        Path link = root.resolve("outside-link");
        try {
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException exception) {
                return;
            }

            FileHandler handler = new FileHandler(root);

            assertThrows(FileHandler.FileOperationException.class, () -> handler.resolveSafePath("outside-link/secret.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
