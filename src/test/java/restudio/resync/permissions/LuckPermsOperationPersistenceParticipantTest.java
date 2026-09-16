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

    private String journal(String operationId, long revision) {
        return "[{\"operationId\":\"" + operationId + "\",\"applied\":true,\"revision\":" + revision
            + ",\"conflicts\":[],\"entities\":[]}]";
    }
}
