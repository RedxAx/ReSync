package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkResourceMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkResourceManifestStoreTest {
    @TempDir
    Path temporary;

    @Test
    void repeatedAcknowledgedBaselineWritesAreIdempotentAndTombstonesSurviveReload() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("source"));
        NetworkResourceManifestStore store = new NetworkResourceManifestStore(dataRoot);
        NetworkResourceMetadata saved = metadata("flow", "welcome", 1, false);
        NetworkResourceMetadata deleted = metadata("flow", "welcome", 2, true);

        store.put(saved);
        byte[] first = Files.readAllBytes(store.file());
        store.put(saved);
        assertArrayEquals(first, Files.readAllBytes(store.file()));
        store.put(deleted);
        store.flush();

        NetworkResourceManifestStore reloaded = new NetworkResourceManifestStore(dataRoot);
        NetworkResourceManifestStore.Entry entry = reloaded.get("flow", "welcome");
        assertEquals(2, entry.revision());
        assertTrue(entry.deleted());
        assertEquals(deleted.payloadHash(), entry.payloadHash());
    }

    @Test
    void quiesceBlocksManifestMutationsAndRebindKeepsThePreviousRootWhenTheCandidateFails() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkResourceManifestStore store = new NetworkResourceManifestStore(source);
        store.put(metadata("flow", "welcome", 1, false));
        Path previous = store.root();
        store.quiesce();

        assertThrows(IllegalStateException.class, () -> store.put(metadata("flow", "welcome", 2, false)));
        assertThrows(IOException.class, () -> store.rebind(temporary.resolve("missing")));
        assertEquals(previous, store.root());

        Path replacement = Files.createDirectories(temporary.resolve("replacement").resolve("network"));
        store.rebind(replacement);
        store.resume();
        assertEquals(replacement, store.root());
        assertNull(store.get("flow", "welcome"));
        store.put(metadata("flow", "welcome", 1, false));
        assertEquals(1, store.get("flow", "welcome").revision());
    }

    @Test
    void malformedManifestFailsClosedInsteadOfDiscardingTheTombstoneBaseline() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("source"));
        Path networkRoot = Files.createDirectories(dataRoot.resolve("network"));
        Files.writeString(networkRoot.resolve("resource-manifest.json"), "{not-json");

        NetworkResourceManifestStore store = new NetworkResourceManifestStore(dataRoot);

        assertThrows(IllegalStateException.class, () -> store.get("flow", "welcome"));
        assertThrows(IllegalStateException.class, store::flush);
    }

    @Test
    void externalManifestChangesFailHealthAndKeepFurtherWritesClosed() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("source"));
        NetworkResourceManifestStore store = new NetworkResourceManifestStore(dataRoot);
        store.put(metadata("flow", "welcome", 1, false));
        Files.writeString(store.file(), "{}");

        assertThrows(IOException.class, store::healthCheck);
        assertThrows(IllegalStateException.class, () -> store.put(metadata("flow", "other", 1, false)));
    }

    private NetworkResourceMetadata metadata(String type, String id, long revision, boolean deleted) {
        byte[] payload = deleted ? new byte[0] : new byte[]{1, 2, 3};
        return new NetworkResourceMetadata(type, id, revision, NetworkPayloads.sha256(payload), deleted, "node", revision);
    }
}
