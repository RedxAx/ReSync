package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.permissions.LuckPermsOperationPersistenceParticipant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncServerRuntimeJournalInitializationTest {
    @TempDir
    Path temporary;

    @Test
    void freshIdentityCommitsItsJournalBeforeItCanBeReopenedAsExisting() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("journal-before-identity"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        Path journal = dataRoot.resolve("runtime/luckperms-operations.json");

        ServerIdentityStore initial = ServerIdentityStore.open(identityFile, null, null,
            LuckPermsOperationPersistenceParticipant::prepareFreshJournal);
        ServerIdentityStore reopened = ServerIdentityStore.open(identityFile, null, null,
            LuckPermsOperationPersistenceParticipant::prepareFreshJournal);
        ReSyncServer.requireExistingRuntimeJournal(reopened, journal);

        assertEquals("[]\n", Files.readString(journal));
        assertEquals(initial.serverId(), reopened.serverId());
        assertFalse(reopened.freshInstall());
    }

    @Test
    void interruptedJournalInitializationResumesWithTheSameIdentity() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("journal-interrupted"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        Path journal = dataRoot.resolve("runtime/luckperms-operations.json");
        assertThrows(IOException.class, () -> ServerIdentityStore.open(identityFile, null, null, root -> {
            throw new IOException("Journal Initialization Interrupted");
        }));
        String identity = Files.readString(identityFile);
        assertFalse(Files.exists(journal));

        ServerIdentityStore resumed = ServerIdentityStore.open(identityFile, null, null,
            LuckPermsOperationPersistenceParticipant::prepareFreshJournal);

        assertTrue(resumed.freshInstall());
        assertEquals(identity, Files.readString(identityFile));
        assertEquals("[]\n", Files.readString(journal));
        assertFalse(ServerIdentityStore.open(identityFile).freshInstall());
    }

    @Test
    void committedIdentityDoesNotRecreateAnEstablishedMissingJournal() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("journal-lost"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        Path journal = dataRoot.resolve("runtime/luckperms-operations.json");
        ServerIdentityStore.open(identityFile, null, null, LuckPermsOperationPersistenceParticipant::prepareFreshJournal);
        Files.delete(journal);

        ServerIdentityStore reopened = ServerIdentityStore.open(identityFile, null, null,
            LuckPermsOperationPersistenceParticipant::prepareFreshJournal);

        assertThrows(IOException.class, () -> ReSyncServer.requireExistingRuntimeJournal(reopened, journal));
        assertFalse(Files.exists(journal));
    }

    @Test
    void journalInitializationPreservesExistingReceipts() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("journal-receipts"));
        Path journal = dataRoot.resolve("runtime/luckperms-operations.json");
        Files.createDirectory(journal.getParent());
        Files.writeString(journal, "[{\"operationId\":\"receipt\"}]");

        LuckPermsOperationPersistenceParticipant.prepareFreshJournal(dataRoot);

        assertEquals("[{\"operationId\":\"receipt\"}]", Files.readString(journal));
    }

    @Test
    void dormantJournalPreservesReceiptsAcrossQuiesceAndRebind() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("journal-dormant"));
        LuckPermsOperationPersistenceParticipant.prepareFreshJournal(dataRoot);
        RebindablePersistenceParticipant participant = LuckPermsOperationPersistenceParticipant.dormant(dataRoot);
        Path restoredRoot = Files.createDirectory(temporary.resolve("journal-restored"));
        Path restored = restoredRoot.resolve("runtime/luckperms-operations.json");
        Files.createDirectory(restored.getParent());
        String receipts = "[{\"operationId\":\"receipt\"}]";
        Files.writeString(restored, receipts);

        assertThrows(IOException.class, () -> participant.rebind(restoredRoot));
        participant.quiesce();
        participant.rebind(restoredRoot);
        participant.resume();
        participant.flush();

        assertEquals(restored, participant.root());
        assertEquals(receipts, Files.readString(restored));
        Files.delete(restored);
        assertThrows(IOException.class, participant::healthCheck);
        assertFalse(Files.exists(restored));
    }

    @Test
    void existingInstallationRejectsAnAbsentRequiredJournalWithoutCreatingIt() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("missing-required"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        ServerIdentityStore.open(identityFile);
        ServerIdentityStore identity = ServerIdentityStore.open(identityFile);
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");

        IOException failure = assertThrows(IOException.class,
            () -> ReSyncServer.requireExistingRuntimeJournal(identity, journal));

        assertTrue(failure.getMessage().contains(journal.toString()));
        assertTrue(failure.getMessage().contains("verified backup"));
        assertFalse(Files.exists(journal));
    }

    @Test
    void freshInstallLeavesJournalCreationWithItsParticipant() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh-required"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");

        ReSyncServer.requireExistingRuntimeJournal(identity, journal);

        assertFalse(Files.exists(journal));
    }

    @Test
    void existingInstallationKeepsAnExistingRequiredJournalUntouched() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-required"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        ServerIdentityStore.open(identityFile);
        ServerIdentityStore identity = ServerIdentityStore.open(identityFile);
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "existing receipts");

        ReSyncServer.requireExistingRuntimeJournal(identity, journal);

        assertEquals("existing receipts", Files.readString(journal));
    }

    @Test
    void freshInstallMaterializesAnAbsentJournalThroughItsParticipant() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = dataRoot.resolve("runtime").resolve("player-npcs.json");
        AtomicInteger flushes = new AtomicInteger();

        ReSyncServer.materializeFreshRuntimeJournal(identity, journal, participant(journal, flushes));

        assertEquals(1, flushes.get());
        assertEquals("{}", Files.readString(journal));
    }

    @Test
    void existingInstallationDoesNotMaterializeAnAbsentJournal() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        ServerIdentityStore.open(identityFile);
        ServerIdentityStore identity = ServerIdentityStore.open(identityFile);
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        AtomicInteger flushes = new AtomicInteger();

        ReSyncServer.materializeFreshRuntimeJournal(identity, journal, participant(journal, flushes));

        assertEquals(0, flushes.get());
        assertFalse(Files.exists(journal));
    }

    @Test
    void freshInstallDoesNotReplaceAnExistingJournal() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-journal"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "sentinel");
        AtomicInteger flushes = new AtomicInteger();

        ReSyncServer.materializeFreshRuntimeJournal(identity, journal, participant(journal, flushes));

        assertEquals(0, flushes.get());
        assertEquals("sentinel", Files.readString(journal));
    }

    @Test
    void freshInstallMaterializesAnAbsentTriggerJournalAsCanonicalJson() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh-triggers"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = dataRoot.resolve("triggers.json");

        assertTrue(identity.freshInstall());
        ReSyncServer.materializeFreshTriggerJournal(identity, journal);

        assertEquals("[]\n", Files.readString(journal));
    }

    @Test
    void existingTriggerJournalRemainsUntouchedOnFreshInstall() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-triggers"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = dataRoot.resolve("triggers.json");
        Files.writeString(journal, "sentinel");

        ReSyncServer.materializeFreshTriggerJournal(identity, journal);

        assertEquals("sentinel", Files.readString(journal));
    }

    @Test
    void existingTriggerDirectoryRemainsUntouchedOnFreshInstall() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("trigger-directory"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path journal = Files.createDirectory(dataRoot.resolve("triggers.json"));

        ReSyncServer.materializeFreshTriggerJournal(identity, journal);

        assertTrue(Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void existingTriggerSymlinkIsRejectedWithoutReplacingItsTarget() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("trigger-symlink"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path target = Files.writeString(dataRoot.resolve("trigger-target.json"), "target");
        Path journal = dataRoot.resolve("triggers.json");
        try {
            Files.createSymbolicLink(journal, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return;
        }

        assertThrows(IllegalArgumentException.class, () -> ReSyncServer.materializeFreshTriggerJournal(identity, journal));
        assertTrue(Files.isSymbolicLink(journal));
        assertEquals("target", Files.readString(target));
    }

    @Test
    void existingInstallationLeavesAnAbsentTriggerJournalAbsent() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-triggers-absent"));
        Path identityFile = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        ServerIdentityStore.open(identityFile);
        ServerIdentityStore identity = ServerIdentityStore.open(identityFile);
        Path journal = dataRoot.resolve("triggers.json");

        assertFalse(identity.freshInstall());
        ReSyncServer.materializeFreshTriggerJournal(identity, journal);

        assertFalse(Files.exists(journal, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void liveReadinessRefreshPreservesEveryOwnerMetadata() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("authority-readiness"));
        Path authorityRoot = Files.createDirectory(dataRoot.resolve("authority"));
        Path otherRoot = Files.createDirectory(dataRoot.resolve("other"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("authority-coordination"));
        PersistenceParticipant authority = participant("resync.authority-bundle", authorityRoot,
            PersistenceParticipantClassification.DERIVED_CACHE);
        PersistenceParticipant other = participant("other.owner", otherRoot,
            PersistenceParticipantClassification.AUTHORITATIVE);
        coordinator.register(authority);
        coordinator.register(other);
        PersistenceRootReadiness.Owner authorityOwner = PersistenceRootReadiness.Owner.unavailable(
            authority.owner(), authority.root(), false,
            PersistenceParticipantClassification.DERIVED_CACHE, "Bundle pending export");
        PersistenceRootReadiness.Owner otherOwner = PersistenceRootReadiness.Owner.registered(
            "other.owner", otherRoot, true, PersistenceParticipantClassification.AUTHORITATIVE);
        PersistenceRootReadiness.UncoveredWriter writer = PersistenceRootReadiness.UncoveredWriter.externalAffected(
            "external.writer", dataRoot.resolve("external-writer"), PersistenceParticipantClassification.DERIVED_CACHE,
            "External writer remains outside persistence", "operator");
        PersistenceExternalInput.Input externalInput = new PersistenceExternalInput.Input(
            "operator-input", dataRoot.resolve("operator-input"), PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION,
            "Operator-owned input remains excluded");
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(
            List.of(authorityOwner, otherOwner), List.of(writer), List.of(externalInput));
        ReSyncPersistenceTopology.Registration registration = new ReSyncPersistenceTopology.Registration(
            false, List.of(otherOwner.owner()), List.of(authorityOwner.owner()),
            Map.of(authorityOwner.owner(), authorityOwner.reason()), readiness);

        ReSyncPersistenceTopology.Registration refreshed = ReSyncPersistenceTopology.refresh(coordinator, registration);

        PersistenceRootReadiness.Owner refreshedAuthority = refreshed.readiness()
            .owner(authority.owner());
        assertEquals(PersistenceRootReadiness.State.REGISTERED, refreshedAuthority.state());
        assertEquals(authority.root(), refreshedAuthority.root());
        assertEquals(authorityOwner.required(), refreshedAuthority.required());
        assertEquals(authorityOwner.classification(), refreshedAuthority.classification());
        assertEquals(otherOwner, refreshed.readiness().owner(otherOwner.owner()));
        assertEquals(readiness.uncoveredWriters(), refreshed.readiness().uncoveredWriters());
        assertEquals(readiness.externalInputs(), refreshed.readiness().externalInputs());
    }

    private PersistenceParticipant participant(String owner, Path root, PersistenceParticipantClassification classification) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return classification;
            }
        };
    }

    private PersistenceParticipant participant(Path root, AtomicInteger flushes) {
        Path normalized = root.toAbsolutePath().normalize();
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return "test.runtime-journal";
            }

            @Override
            public Path root() {
                return normalized;
            }

            @Override
            public void flush() throws IOException {
                flushes.incrementAndGet();
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized, "{}");
            }
        };
    }
}
