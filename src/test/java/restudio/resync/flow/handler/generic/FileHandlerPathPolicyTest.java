package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHandlerPathPolicyTest {
    @TempDir
    Path root;

    @Test
    void usesOneExactDatabasePathAndStartsOnEverySupportedProvider() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            Path expected = root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)
                .resolve(ManagedFlowFileCapability.DATABASE_FILE).normalize();
            assertEquals(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).normalize(), capability.root());
            assertEquals(capability.root(), capability.persistenceRoot());
            assertEquals(expected, capability.databaseFile());
            assertEquals(List.of(expected, expected.resolveSibling("managed-files.db-wal"),
                expected.resolveSibling("managed-files.db-shm"), expected.resolveSibling("managed-files.db-journal")),
                capability.persistenceFiles());
            assertTrue(Files.isRegularFile(expected));
        }
    }

    @Test
    void resolvesNormalizedLogicalPathsWithoutHostPathResolution() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            FileHandler handler = new FileHandler(capability);
            assertEquals("flows/output.txt", handler.resolveSafePath("flows/./output.txt"));
            assertEquals(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).normalize(), capability.persistenceRoot());
            assertEquals(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)
                .resolve(ManagedFlowFileCapability.DATABASE_FILE).normalize(), capability.databaseFile());
        }
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("../outside.txt", "blocked"));
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("/absolute.txt", "blocked"));
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("C:/absolute.txt", "blocked"));
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("nested\\outside.txt", "blocked"));
            assertFalse(Files.exists(root.resolve("outside.txt")));
        }
    }

    @Test
    void rejectsCrossRootSourcesAndTargets() throws Exception {
        Path outside = Files.writeString(root.getParent().resolve("outside-flow-file.txt"), "outside");
        try (ManagedFlowFileCapability capability = capability()) {
            capability.write("source.txt", "source");
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.copy(outside.toString(), "copied.txt"));
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.move("source.txt", outside.toString()));
            assertEquals("source", capability.read("source.txt"));
            assertEquals("outside", Files.readString(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void legacyPhysicalFilesRemainUntouchedAndAreNotImported() throws Exception {
        Path legacy = Files.createDirectories(root.resolve("legacy")).resolve("value.txt");
        Files.writeString(legacy, "legacy");
        try (ManagedFlowFileCapability capability = capability()) {
            assertFalse(capability.exists("legacy/value.txt"));
            capability.write("legacy/value.txt", "managed");
            assertEquals("legacy", Files.readString(legacy));
            assertEquals("managed", capability.read("legacy/value.txt"));
        }
    }

    @Test
    void createsExplicitDirectoryRowsAndListsDeterministically() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            capability.createDirectory("nested/deeper");
            capability.write("nested/deeper/value.txt", "value");
            capability.write("nested/other.txt", "other");
            assertEquals(List.of("deeper", "other.txt"), capability.list("nested"));
            assertEquals(List.of("nested"), capability.list("."));
            assertThrows(ManagedFlowFileCapability.AccessException.class, () -> capability.delete("nested"));
            assertEquals("FILE_DIRECTORY_NOT_EMPTY",
                assertThrows(ManagedFlowFileCapability.AccessException.class,
                    () -> capability.delete("nested")).code());
        }
    }

    @Test
    void supportsReadWriteAppendCopyMoveSizeAndDelete() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            capability.createDirectory("nested");
            capability.write("nested/value.txt", "one\ntwo");
            capability.append("nested/value.txt", "\nthree");
            assertEquals("one\ntwo\nthree", capability.read("nested/value.txt"));
            assertEquals(List.of("one", "two", "three"), capability.readLines("nested/value.txt"));
            assertEquals(13L, capability.size("nested/value.txt"));
            assertTrue(capability.exists("nested/value.txt"));

            capability.copy("nested/value.txt", "copy.txt");
            capability.move("copy.txt", "moved.txt");
            assertFalse(capability.exists("copy.txt"));
            assertEquals("one\ntwo\nthree", capability.read("moved.txt"));
            assertEquals(List.of("moved.txt", "nested"), capability.list("."));
            assertTrue(capability.delete("moved.txt"));
            assertFalse(capability.exists("moved.txt"));
            assertTrue(capability.delete("nested/value.txt"));
            assertTrue(capability.delete("nested"));
        }
    }

    @Test
    void copyAndMoveFailuresRollbackTheirTransactions() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            capability.write("source.txt", "source");
            capability.write("blocked", "not-a-directory");
            assertEquals("PATH_NOT_DIRECTORY",
                assertThrows(ManagedFlowFileCapability.AccessException.class,
                    () -> capability.copy("source.txt", "blocked/copy.txt")).code());
            assertEquals("PATH_NOT_DIRECTORY",
                assertThrows(ManagedFlowFileCapability.AccessException.class,
                    () -> capability.move("source.txt", "blocked/moved.txt")).code());
            assertEquals("source", capability.read("source.txt"));
            assertFalse(capability.exists("blocked/copy.txt"));
            assertFalse(capability.exists("blocked/moved.txt"));
        }
    }

    @Test
    void survivesCloseAndReopenWithDurableContent() throws Exception {
        Path database;
        try (ManagedFlowFileCapability capability = capability()) {
            database = capability.databaseFile();
            capability.createDirectory("durable");
            capability.write("durable/value.txt", "persisted");
        }
        assertTrue(Files.isRegularFile(database));
        try (ManagedFlowFileCapability reopened = capability()) {
            assertEquals("persisted", reopened.read("durable/value.txt"));
            assertEquals(List.of("value.txt"), reopened.list("durable"));
        }
    }

    @Test
    void serializesConcurrentOperationsWithoutLostWrites() throws Exception {
        try (ManagedFlowFileCapability capability = capability();
             ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                int value = index;
                writes.add(executor.submit(() -> {
                    try {
                        capability.write("concurrent/" + value + ".txt", Integer.toString(value));
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }
            for (Future<?> write : writes) {
                write.get();
            }
            assertEquals(24, capability.list("concurrent").size());
            assertEquals("17", capability.read("concurrent/17.txt"));
        }
    }

    @Test
    void physicalSymlinksCannotInfluenceLogicalOperations() throws Exception {
        Path outside = Files.createTempDirectory(root.getParent(), "resync-flow-file-outside-");
        Path legacyLink = root.resolve("legacy-link");
        try {
            try {
                Files.createSymbolicLink(legacyLink, outside);
            } catch (UnsupportedOperationException | IOException exception) {
                return;
            }
            try (ManagedFlowFileCapability capability = capability()) {
                capability.write("legacy-link/secret.txt", "managed");
                assertFalse(Files.exists(outside.resolve("secret.txt")));
                assertEquals("managed", capability.read("legacy-link/secret.txt"));
            }
        } finally {
            Files.deleteIfExists(legacyLink);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void fileHandlerStartsWithoutSecureDirectoryStream() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            FileHandler handler = new FileHandler(capability);
            assertTrue(capability.available());
            assertEquals(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).normalize(), capability.persistenceRoot());
        }
    }

    @Test
    void corruptStorageDegradesOnlyFileCapability() throws Exception {
        Path database = Files.createDirectories(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY))
            .resolve(ManagedFlowFileCapability.DATABASE_FILE);
        Files.writeString(database, "not a sqlite database");
        try (ManagedFlowFileCapability capability = ManagedFlowFileCapability.unavailable(root,
            new IllegalStateException("corrupt managed flow-file database"))) {
            FileHandler handler = new FileHandler(capability);
            assertFalse(capability.available());
            assertEquals(root.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).normalize(), capability.persistenceRoot());
            FileHandler.FileOperationException failure = assertThrows(FileHandler.FileOperationException.class,
                () -> handler.resolveSafePath("value.txt"));
            assertEquals("FILE_CAPABILITY_UNAVAILABLE", failure.code());
            assertEquals("Managed flow-file storage is unavailable", failure.getMessage());
        }
    }

    @Test
    void copyAndMoveMutationFailuresRollbackWithoutFencing() throws Exception {
        SqliteManagedFlowFileCapability.FailureInjector injector = new SqliteManagedFlowFileCapability.FailureInjector() {
            @Override
            public void afterMutation(String operation, int mutationCount) throws IOException {
                if (mutationCount == 1 && Set.of("copy", "move").contains(operation)) {
                    throw new IOException("injected mutation failure");
                }
            }
        };
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(root, injector)) {
            capability.write("source.txt", "source");
            assertThrows(IOException.class, () -> capability.copy("source.txt", "copy.txt"));
            assertEquals("source", capability.read("source.txt"));
            assertFalse(capability.exists("copy.txt"));
            assertThrows(IOException.class, () -> capability.move("source.txt", "moved.txt"));
            assertEquals("source", capability.read("source.txt"));
            assertFalse(capability.exists("moved.txt"));
            assertTrue(capability.available());
        }
    }

    @Test
    void rollbackFailureFencesTheCapability() throws Exception {
        SqliteManagedFlowFileCapability.FailureInjector injector = new SqliteManagedFlowFileCapability.FailureInjector() {
            @Override
            public void beforeRollback(String operation) throws SQLException {
                throw new SQLException("injected rollback failure");
            }
        };
        SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(root, injector);
        try {
            capability.write("source.txt", "source");
            capability.write("blocked", "not-a-directory");
            ManagedFlowFileCapability.AccessException failure = assertThrows(
                ManagedFlowFileCapability.AccessException.class,
                () -> capability.copy("source.txt", "blocked/copy.txt"));
            assertEquals("FILE_TRANSACTION_ROLLBACK_FAILED", failure.code());
            assertFalse(capability.available());
            ManagedFlowFileCapability.AccessException fenced = assertThrows(
                ManagedFlowFileCapability.AccessException.class,
                () -> capability.read("source.txt"));
            assertEquals("FILE_CAPABILITY_FENCED", fenced.code());
        } finally {
            capability.close();
        }
    }

    @Test
    void postCommitCleanupFailureReturnsDurableSuccessAndPreventsRetryOnFencedStore() throws Exception {
        SqliteManagedFlowFileCapability.FailureInjector injector = new SqliteManagedFlowFileCapability.FailureInjector() {
            @Override
            public void beforeAutoCommitReset(String operation) throws SQLException {
                if ("append".equals(operation)) {
                    throw new SQLException("injected cleanup failure");
                }
            }
        };
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(root, injector)) {
            capability.write("value.txt", "one");
            assertEquals("value.txt", capability.append("value.txt", "-two"));
            assertFalse(capability.available());
            assertTrue(capability.postCommitCleanupFailure() != null);
        }
        try (ManagedFlowFileCapability reopened = capability()) {
            assertEquals("one-two", reopened.read("value.txt"));
        }
    }

    @Test
    void quiesceAndRebindLifecycleUsesWholePersistenceRoot() throws Exception {
        Path reboundDataRoot = Files.createTempDirectory(root.getParent(), "rebound-data-");
        Path reboundPersistenceRoot = Files.createDirectory(reboundDataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        try (SqliteManagedFlowFileCapability ignored = new SqliteManagedFlowFileCapability(reboundDataRoot)) {
        }
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(root)) {
            capability.quiesce();
            assertThrows(ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("blocked.txt", "blocked"));
            capability.rebind(reboundPersistenceRoot);
            assertEquals(reboundPersistenceRoot, capability.persistenceRoot());
            capability.resume();
            capability.write("rebound.txt", "rebound");
        }
        try (ManagedFlowFileCapability reopened = new SqliteManagedFlowFileCapability(reboundDataRoot)) {
            assertEquals("rebound", reopened.read("rebound.txt"));
        }
    }

    @Test
    void resumeIsTheOnlyTransitionOutOfQuiescedState() throws Exception {
        try (ManagedFlowFileCapability capability = capability()) {
            capability.quiesce();
            ManagedFlowFileCapability.AccessException blocked = assertThrows(
                ManagedFlowFileCapability.AccessException.class,
                () -> capability.write("blocked.txt", "blocked"));
            assertEquals("FILE_CAPABILITY_QUIESCED", blocked.code());
            capability.resume();
            capability.write("resumed.txt", "resumed");
            assertEquals("resumed", capability.read("resumed.txt"));
        }
    }

    private ManagedFlowFileCapability capability() {
        return new SqliteManagedFlowFileCapability(root);
    }
}
