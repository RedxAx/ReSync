package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCredentialStoreTest {
    @TempDir
    Path temporary;

    @Test
    void credentialIsReboundWithTheNetworkRootAndFailsClosedForAnEmptyCandidate() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("network"));
        Path candidate = Files.createDirectories(temporary.resolve("candidate").resolve("network"));
        Path credential = source.resolve("node.credential");
        Files.writeString(credential, "old-secret");
        NetworkCredentialStore store = new NetworkCredentialStore(credential, "old-secret");
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, MigrationFence.systemWide(), Duration.ofSeconds(2));
        controller.register(store.persistenceComponent("credential"));

        controller.quiescePersistence();
        Files.writeString(candidate.resolve("node.credential"), "new-secret");
        controller.rebindPersistence(candidate);
        assertEquals(candidate.resolve("node.credential"), store.file());
        assertEquals("new-secret", store.value());
        controller.resumePersistence();

        NetworkCredentialStore second = new NetworkCredentialStore(source.resolve("second.credential"), "old-secret");
        Path emptyCandidate = Files.createDirectories(temporary.resolve("empty-network"));
        Files.writeString(emptyCandidate.resolve("second.credential"), "");
        assertThrows(IOException.class, () -> second.validateRebind(emptyCandidate));
        assertTrue(Files.exists(candidate.resolve("node.credential")));
    }

    @Test
    void canonicalTemporaryCredentialIsQuarantinedBeforeItCanAffectTheCredential() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("network"));
        Path credential = network.resolve("node.credential");
        Path temporaryFile = network.resolve("node.credential.tmp");
        Files.writeString(temporaryFile, "interrupted-secret");

        NetworkCredentialStore store = new NetworkCredentialStore(credential, "");

        assertTrue(!Files.exists(temporaryFile));
        Path quarantine = network.resolve(".quarantine/network-exact-files");
        assertTrue(Files.isDirectory(quarantine));
        assertEquals("", store.value());
        store.save("new-secret");
        assertEquals("new-secret", Files.readString(credential));
    }

    @Test
    void credentialTemporaryCollisionFailsClosedAfterPreservingEvidence() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("collision"));
        Path credential = network.resolve("node.credential");
        Files.writeString(credential, "current-secret");
        Files.writeString(network.resolve("node.credential.tmp"), "next-secret");

        assertThrows(IllegalStateException.class, () -> new NetworkCredentialStore(credential, "current-secret"));
        assertTrue(!Files.exists(network.resolve("node.credential.tmp")));
        assertTrue(Files.isDirectory(network.resolve(".quarantine/network-exact-files")));
    }

    @Test
    void credentialTemporarySymlinkFailsClosedWithoutWritingThroughIt() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("symlink"));
        Path credential = network.resolve("node.credential");
        Path target = network.resolve("outside");
        Files.writeString(target, "outside-secret");
        try {
            Files.createSymbolicLink(network.resolve("node.credential.tmp"), target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false);
        }

        assertThrows(IllegalStateException.class, () -> new NetworkCredentialStore(credential, ""));
        assertEquals("outside-secret", Files.readString(target));
    }

    @Test
    void foreignStorageSafetyTemporaryCredentialIsNotClaimed() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("storage-temporary"));
        Path credential = network.resolve("node.credential");
        Path storageTemporary = network.resolve(".resync-00000000-0000-0000-0000-000000000001.tmp");
        Files.writeString(storageTemporary, "interrupted-secret");

        assertThrows(IllegalStateException.class, () -> new NetworkCredentialStore(credential, ""));

        assertTrue(Files.exists(storageTemporary));
        assertTrue(!Files.exists(network.resolve(".quarantine/network-exact-files")));
    }

    @Test
    void credentialMutationRejectsAnExternalContentChangeBeforeWritingOrClearing() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("external-change"));
        Path credential = network.resolve("node.credential");
        Files.writeString(credential, "original-secret");
        NetworkCredentialStore store = new NetworkCredentialStore(credential, "original-secret");

        Files.writeString(credential, "external-secret");
        assertThrows(IOException.class, () -> store.save("new-secret"));
        assertEquals("external-secret", Files.readString(credential));
        assertThrows(IOException.class, store::clear);
        assertEquals("external-secret", Files.readString(credential));
    }

    @Test
    void unknownCredentialTemporaryNameFailsClosed() throws Exception {
        Path network = Files.createDirectories(temporary.resolve("unknown-temporary"));
        Files.writeString(network.resolve("node.credential.unknown.tmp"), "interrupted-secret");

        assertThrows(IllegalStateException.class,
            () -> new NetworkCredentialStore(network.resolve("node.credential"), ""));
    }
}
