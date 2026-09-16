package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.server.ServerIdentityStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedFlowFilePersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void ownsTheCompleteFlowFileRootAndClosesTheExactCapability() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot);
        Path legacyPhysicalFile = Files.writeString(capability.persistenceRoot().resolve("legacy.txt"), "legacy");
        ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(dataRoot, capability);

        assertEquals(capability.persistenceRoot(), participant.root());
        assertTrue(participant.owns(capability.databaseFile()));
        assertTrue(participant.owns(participant.root()));
        assertTrue(participant.owns(legacyPhysicalFile));
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
        var ownership = participant.ownershipIndex(context);
        assertTrue(ownership.owns(context.participantRootRelative()));
        assertTrue(ownership.owns(context.relativeToSource(capability.databaseFile())));
        assertTrue(ownership.owns(context.relativeToSource(legacyPhysicalFile)));
        assertFalse(ownership.owns(ManagedFlowFileCapability.ROOT_DIRECTORY + "-other/legacy.txt"));
        participant.healthCheck();
        participant.quiesce();
        assertThrows(ManagedFlowFileCapability.AccessException.class, () -> capability.write("blocked.txt", "blocked"));
        participant.resume();
        participant.close();
        assertFalse(capability.available());
    }

    @Test
    void rebindRequiresAnExistingValidatedDatabase() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        Files.createDirectory(targetRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(sourceRoot)) {
            capability.quiesce();
            ManagedFlowFileCapability.AccessException failure = assertThrows(
                ManagedFlowFileCapability.AccessException.class,
                () -> capability.rebind(targetRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)));
            assertEquals("FILE_REBIND_FAILED", failure.code());
            assertFalse(capability.available());
        }
    }

    @Test
    void acceptsOnlyAValidatedExistingDatabaseDuringRebind() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source-valid"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target-valid"));
        try (SqliteManagedFlowFileCapability target = new SqliteManagedFlowFileCapability(targetRoot)) {
            target.write("existing.txt", "target");
        }
        try (SqliteManagedFlowFileCapability source = new SqliteManagedFlowFileCapability(sourceRoot)) {
            source.quiesce();
            source.rebind(targetRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
            source.resume();
            assertEquals("target", source.read("existing.txt"));
        }
    }

    @Test
    void rejectsMetadataCorruptionWithoutRewritingTheDatabase() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("corrupt-metadata"));
        Path database;
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot)) {
            database = capability.databaseFile();
            capability.write("preserved.txt", "preserved");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "UPDATE managed_flow_file_store_metadata SET format_version = 99 WHERE id = 1")) {
            statement.executeUpdate();
        }
        assertThrows(IllegalArgumentException.class, () -> new SqliteManagedFlowFileCapability(dataRoot));
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void rejectsAnExistingEmptyDatabaseInsteadOfTreatingItAsFreshInstall() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("empty-database"));
        Path root = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Files.createFile(root.resolve(ManagedFlowFileCapability.DATABASE_FILE));

        assertThrows(IllegalArgumentException.class, () -> new SqliteManagedFlowFileCapability(dataRoot));
    }

    @Test
    void existingServerCannotBootstrapADeletedManagedDatabase() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-server"));
        ServerIdentityStore freshIdentity = ServerIdentityStore.open(dataRoot.resolve("server-id"));
        assertTrue(freshIdentity.freshInstall());
        ServerIdentityStore existingIdentity = ServerIdentityStore.open(dataRoot.resolve("server-id"));
        assertFalse(existingIdentity.freshInstall());

        Path flowRoot = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Path database = flowRoot.resolve(ManagedFlowFileCapability.DATABASE_FILE);

        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, existingIdentity.freshInstall()));
        assertFalse(Files.exists(database));
    }
}
