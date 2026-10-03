package restudio.resync.permissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsOperationPersistenceParticipantTest {
    @TempDir
    Path temporary;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void strictJournalLoadPreservesRestartDeduplication() throws Exception {
        Path dataRoot = plugin.getDataFolder().toPath();
        Path journal = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, journal("operation-1", 4));

        LuckPermsManagementService service = new LuckPermsManagementService(plugin);

        assertTrue(service.hasCompletedOperation("operation-1"));
        service.flushPersistence();
        service.healthCheckPersistence();
        service.close();
    }

    @Test
    void activeRootConstructorKeepsOperationJournalOnActivePersistenceRoot() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        LuckPermsManagementService service = new LuckPermsManagementService(plugin,
            LuckPermsBackendPersistenceCapability.unavailable(), activeRoot);
        LuckPermsOperationPersistenceParticipant participant =
            new LuckPermsOperationPersistenceParticipant(activeRoot, service);
        Path activeJournal = activeRoot.resolve("runtime").resolve("luckperms-operations.json")
            .toAbsolutePath().normalize();
        Path pluginJournal = plugin.getDataFolder().toPath().resolve("runtime")
            .resolve("luckperms-operations.json").toAbsolutePath().normalize();

        assertEquals(activeJournal, service.persistenceRoot());
        assertEquals(activeJournal, participant.root());
        assertFalse(Files.exists(pluginJournal));

        service.flushPersistence();
        assertTrue(Files.isRegularFile(activeJournal));
        service.close();
    }

    @Test
    void malformedJournalFailsClosedAtServiceConstruction() throws Exception {
        Path journal = plugin.getDataFolder().toPath().resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "[{\"operationId\":\"\",\"applied\":true,\"revision\":1,\"conflicts\":[],\"entities\":[]}]");

        assertThrows(IllegalStateException.class, () -> new LuckPermsManagementService(plugin));
    }

    @Test
    void negativePersistedRevisionsFailClosedAtServiceConstruction() throws Exception {
        Path journal = plugin.getDataFolder().toPath().resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "[{\"operationId\":\"operation-1\",\"applied\":true,\"revision\":1,"
            + "\"conflicts\":[{\"type\":\"USER\",\"id\":\"user\",\"expectedRevision\":-1,"
            + "\"actualRevision\":1,\"message\":\"\"}],\"entities\":[]}]");

        assertThrows(IllegalStateException.class, () -> new LuckPermsManagementService(plugin));
    }

    @Test
    void rebindStagesCandidateAndKeepsCommittedStateWhenCandidateIsMalformed() throws Exception {
        Path dataRoot = plugin.getDataFolder().toPath();
        Path source = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, journal("source", 1));
        LuckPermsManagementService service = new LuckPermsManagementService(plugin);
        LuckPermsOperationPersistenceParticipant participant = new LuckPermsOperationPersistenceParticipant(dataRoot, service);
        participant.quiesce();

        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate"));
        Path candidate = candidateRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(candidate.getParent());
        Files.writeString(candidate, journal("candidate", 2));
        participant.rebind(candidateRoot);

        assertEquals(candidate.toAbsolutePath().normalize(), service.persistenceRoot());
        assertTrue(service.hasCompletedOperation("candidate"));
        assertTrue(!service.hasCompletedOperation("source"));

        Path malformedRoot = Files.createDirectory(temporary.resolve("malformed"));
        Path malformed = malformedRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(malformed.getParent());
        Files.writeString(malformed, "{}");
        assertThrows(IOException.class, () -> participant.rebind(malformedRoot));
        assertEquals(candidate.toAbsolutePath().normalize(), service.persistenceRoot());
        assertTrue(service.hasCompletedOperation("candidate"));
        participant.resume();
        service.close();
    }

    @Test
    void missingRebindJournalPreservesCurrentBindingAndDurableReceipts() throws Exception {
        Path candidateRoot = Files.createDirectory(temporary.resolve("missing"));
        Path candidate = candidateRoot.resolve("runtime").resolve("luckperms-operations.json");

        Exception failure = assertRejectedCandidatePreservesReceipts(candidateRoot);

        assertTrue(failure.getMessage().contains("Restore a verified backup"));
        assertTrue(failure.getMessage().contains("Do not replace the journal with an empty file"));
        assertFalse(Files.exists(candidate));
    }

    @Test
    void nonregularRebindJournalPreservesCurrentBindingAndDurableReceipts() throws Exception {
        Path candidateRoot = Files.createDirectory(temporary.resolve("directory"));
        Path candidate = candidateRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(candidate);

        assertRejectedCandidatePreservesReceipts(candidateRoot);

        assertTrue(Files.isDirectory(candidate));
    }

    @Test
    void symbolicRebindJournalPreservesCurrentBindingAndDurableReceipts() throws Exception {
        Path candidateRoot = Files.createDirectory(temporary.resolve("symbolic"));
        Path candidate = candidateRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(candidate.getParent());
        Path target = temporary.resolve("target.json");
        String targetJournal = journal("candidate", 2);
        Files.writeString(target, targetJournal);
        Files.createSymbolicLink(candidate, target);

        assertRejectedCandidatePreservesReceipts(candidateRoot);

        assertTrue(Files.isSymbolicLink(candidate));
        assertEquals(targetJournal, Files.readString(target));
    }

    @Test
    void repeatedValidRebindPreservesCandidateReceiptsAcrossRestart() throws Exception {
        Path dataRoot = plugin.getDataFolder().toPath();
        Path source = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, journal("source", 1));
        Path candidateRoot = Files.createDirectory(temporary.resolve("repeated"));
        Path candidate = candidateRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(candidate.getParent());
        String candidateJournal = journal("candidate", 2);
        Files.writeString(candidate, candidateJournal);
        LuckPermsManagementService service = new LuckPermsManagementService(plugin);
        LuckPermsOperationPersistenceParticipant participant = new LuckPermsOperationPersistenceParticipant(dataRoot, service);
        try {
            participant.quiesce();
            participant.rebind(candidateRoot);
            participant.rebind(candidateRoot);

            assertEquals(candidate.toAbsolutePath().normalize(), participant.root());
            assertTrue(service.hasCompletedOperation("candidate"));
            assertFalse(service.hasCompletedOperation("source"));
            assertEquals(candidateJournal, Files.readString(candidate));
            participant.healthCheck();
            participant.resume();
        } finally {
            service.close();
        }

        LuckPermsManagementService restarted = new LuckPermsManagementService(plugin,
            LuckPermsBackendPersistenceCapability.unavailable(), candidateRoot);
        try {
            assertTrue(restarted.hasCompletedOperation("candidate"));
            assertFalse(restarted.hasCompletedOperation("source"));
            restarted.healthCheckPersistence();
            assertEquals(candidateJournal, Files.readString(candidate));
        } finally {
            restarted.close();
        }
    }

    @Test
    void ownershipIndexClaimsOnlyTheLuckPermsJournal() throws Exception {
        Path dataRoot = plugin.getDataFolder().toPath();
        LuckPermsManagementService service = new LuckPermsManagementService(plugin);
        LuckPermsOperationPersistenceParticipant participant = new LuckPermsOperationPersistenceParticipant(dataRoot, service);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(dataRoot, participant.root()));

        assertTrue(index.owns("runtime/luckperms-operations.json"));
        assertFalse(index.owns("runtime/luckperms-operations.json.tmp"));
        assertFalse(index.owns("runtime/luckperms-operations.json/nested"));
        service.close();
    }

    private Exception assertRejectedCandidatePreservesReceipts(Path candidateRoot) throws Exception {
        Path dataRoot = plugin.getDataFolder().toPath();
        Path source = dataRoot.resolve("runtime").resolve("luckperms-operations.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, journal("source", 1));
        LuckPermsManagementService service = new LuckPermsManagementService(plugin);
        LuckPermsOperationPersistenceParticipant participant = new LuckPermsOperationPersistenceParticipant(dataRoot, service);
        Exception failure;
        String persisted;
        try {
            participant.quiesce();
            persisted = Files.readString(source);

            failure = assertThrows(Exception.class, () -> participant.rebind(candidateRoot));
            assertTrue(failure instanceof IOException || failure instanceof IllegalArgumentException);

            assertEquals(source.toAbsolutePath().normalize(), service.persistenceRoot());
            assertEquals(source.toAbsolutePath().normalize(), participant.root());
            assertTrue(service.hasCompletedOperation("source"));
            assertFalse(service.hasCompletedOperation("candidate"));
            assertTrue(service.isPersistenceQuiesced());
            assertEquals(persisted, Files.readString(source));
            participant.healthCheck();
            participant.resume();
        } finally {
            service.close();
        }

        LuckPermsManagementService restarted = new LuckPermsManagementService(plugin);
        try {
            assertTrue(restarted.hasCompletedOperation("source"));
            assertFalse(restarted.hasCompletedOperation("candidate"));
            restarted.healthCheckPersistence();
            assertEquals(persisted, Files.readString(source));
        } finally {
            restarted.close();
        }
        return failure;
    }

    private String journal(String operationId, long revision) {
        return "[{\"operationId\":\"" + operationId + "\",\"applied\":true,\"revision\":" + revision
            + ",\"conflicts\":[],\"entities\":[]}]";
    }
}
