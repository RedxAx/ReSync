package restudio.resync.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void ownershipIndexIncludesTheExtensionRootAndDescendants() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path extensions = Files.createDirectory(source.resolve("extensions"));
        ExtensionPersistenceParticipant participant = new ExtensionPersistenceParticipant(source,
            new Controller(extensions));
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("sample/config.json"))));
        assertFalse(index.owns("other/config.json"));
    }

    private static final class Controller implements ExtensionPersistenceParticipant.Controller {
        private Path root;

        private Controller(Path root) {
            this.root = root;
        }

        @Override
        public Path persistenceRoot() {
            return root;
        }

        @Override
        public void flushPersistence() throws IOException {
        }

        @Override
        public void quiescePersistence() throws IOException {
        }

        @Override
        public void resumePersistence() throws IOException {
        }

        @Override
        public void rebindPersistence(Path activeRoot) throws IOException {
            root = activeRoot;
        }

        @Override
        public void healthCheckPersistence() throws IOException {
        }
    }
}
