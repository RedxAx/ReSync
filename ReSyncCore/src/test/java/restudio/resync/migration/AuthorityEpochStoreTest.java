package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorityEpochStoreTest {
    @TempDir
    Path temporary;

    @Test
    void recoversMissingHistoricalRootAndAdvancesOnlyOnceForNewActiveRoot() throws Exception {
        Path coordinationRoot = Files.createDirectory(temporary.resolve("coordination"));
        Path historicalRoot = temporary.resolve("historical");
        String canonical = "format=1\nepoch=7\nroot="
            + MigrationCanonical.encode(historicalRoot.toAbsolutePath().normalize().toString()) + "\n";
        Files.writeString(coordinationRoot.resolve(AuthorityEpochStore.FILE_NAME),
            canonical + "hash=" + MigrationCanonical.sha256(canonical) + "\n");

        AuthorityEpochStore store = new AuthorityEpochStore(coordinationRoot);
        assertEquals(historicalRoot.toAbsolutePath().normalize(), store.boundRoot());
        assertEquals(8L, store.bind(Files.createDirectory(temporary.resolve("recovered"))));
        assertEquals(8L, store.bind(store.boundRoot()));
    }

    @Test
    void failedInitialBindingDoesNotPublishAndRetriesPersistenceForTheSameRoot() throws Exception {
        Path coordinationRoot = Files.createDirectory(temporary.resolve("failed-coordination"));
        AuthorityEpochStore store = new AuthorityEpochStore(coordinationRoot);
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        Files.createDirectory(store.file());

        assertThrows(IOException.class, () -> store.bind(activeRoot));
        assertThrows(IllegalStateException.class, store::current);
        assertNull(store.boundRoot());

        Files.delete(store.file());
        assertEquals(1L, store.bind(activeRoot));
        AuthorityEpochStore reopened = new AuthorityEpochStore(coordinationRoot);
        assertEquals(1L, reopened.current());
        assertEquals(activeRoot.toAbsolutePath().normalize(), reopened.boundRoot());
    }
}
