package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerIdentityStoreTest {
    @Test
    void requiresSqliteNoFollowVersion() {
        assertFalse(ServerIdentityStore.supportsNoFollowVersion("3.41.3"));
        assertTrue(ServerIdentityStore.supportsNoFollowVersion("3.42.0"));
        assertTrue(ServerIdentityStore.supportsNoFollowVersion("3.53.2"));
    }

    @Test
    void strongObjectIdentityAllowsLegitimateSqliteContentMutation() {
        assertTrue(ServerIdentityStore.sameAuthorityArtifactAfterOpen("identity", "identity"));
        assertFalse(ServerIdentityStore.sameAuthorityArtifactAfterOpen("identity", "replacement"));
        assertFalse(ServerIdentityStore.sameAuthorityArtifactAfterOpen(null, null));
    }

    @Test
    void preservesIdentityAcrossReopenAndDataRootMove(@TempDir Path temporary) throws Exception {
        Path originalRoot = Files.createDirectory(temporary.resolve("original"));
        ServerIdentityStore original = ServerIdentityStore.open(originalRoot.resolve("server-id"));
        ServerIdentityStore reopened = ServerIdentityStore.open(originalRoot.resolve("server-id"));
        Path movedRoot = Files.move(originalRoot, temporary.resolve("moved"));
        ServerIdentityStore moved = ServerIdentityStore.open(movedRoot.resolve("server-id"));

        assertEquals(original.serverId(), reopened.serverId());
        assertEquals(original.serverId(), moved.serverId());
        assertTrue(original.freshInstall());
        assertFalse(reopened.freshInstall());
        assertFalse(moved.freshInstall());
    }

    @Test
    void rejectsInvalidPersistedIdentityWithoutReplacingIt(@TempDir Path temporary) throws Exception {
        Path identity = temporary.resolve("server-id");
        Files.writeString(identity, "invalid");

        assertThrows(IOException.class, () -> ServerIdentityStore.open(identity));
        assertEquals("invalid", Files.readString(identity));
    }

    @Test
    void missingIdentityInAnExistingDataRootFailsClosed(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("config.properties"), "enabled=true\n");
        Files.createDirectories(temporary.resolve("assets"));

        assertThrows(IOException.class, () -> ServerIdentityStore.open(temporary.resolve("server-id")));
    }

    @Test
    void ownershipIndexMatchesExactIdentityAndAuthoritySidecars(@TempDir Path temporary) throws Exception {
        ServerIdentityStore store = ServerIdentityStore.open(temporary.resolve(ServerIdentityStore.FILE_NAME));
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(temporary, store.root());
        PersistenceOwnershipIndex index = store.ownershipIndex(context);

        for (String name : ServerIdentityStore.ownedFileNames()) {
            Path candidate = store.root().resolveSibling(name);
            String relative = candidate.equals(store.root())
                ? context.participantRootRelative()
                : context.relativeToSource(candidate);
            assertEquals(store.owns(candidate), index.owns(relative), name);
        }

        Path malformed = store.root().resolveSibling(ServerIdentityStore.AUTHORITY_FILE + ".tmp");
        assertEquals(store.owns(malformed), index.owns(context.relativeToSource(malformed)));
        Path canonical = store.root().resolveSibling(".resync-00000000-0000-0000-0000-000000000000.tmp");
        assertTrue(store.owns(canonical));
        assertTrue(index.owns(context.relativeToSource(canonical)));
        Path quarantine = temporary.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY);
        Path quarantineEvidence = quarantine.resolve(".resync-00000000-0000-0000-0000-000000000001.tmp");
        Path quarantineContainer = temporary.resolve(".quarantine");
        assertTrue(store.owns(quarantineContainer));
        assertTrue(index.owns(context.relativeToSource(quarantineContainer)));
        assertTrue(store.owns(quarantineEvidence));
        assertTrue(index.owns(context.relativeToSource(quarantineEvidence)));
        assertFalse(store.owns(store.root().resolveSibling(".resync-invalid.tmp")));
    }

    @Test
    void quarantinesCanonicalInterruptedArtifactWithoutDeletingEvidence(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("canonical-recovery"));
        ServerIdentityStore first = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path temporaryFile = dataRoot.resolve(".resync-00000000-0000-0000-0000-000000000000.tmp");
        Files.writeString(temporaryFile, "interrupted identity write");

        ServerIdentityStore reopened = ServerIdentityStore.open(first.path());

        Path evidence = dataRoot.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY).resolve(temporaryFile.getFileName());
        assertFalse(Files.exists(temporaryFile));
        assertEquals("interrupted identity write", Files.readString(evidence));
        assertEquals(first.serverId(), reopened.serverId());
    }

    @Test
    void rejectsUnknownIdentityArtifactWithoutDeletingIt(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("unknown-recovery"));
        ServerIdentityStore first = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path unknown = dataRoot.resolve(".resync-not-a-uuid.tmp");
        Files.writeString(unknown, "ambiguous");

        assertThrows(IOException.class, () -> ServerIdentityStore.open(first.path()));
        assertEquals("ambiguous", Files.readString(unknown));
    }

    @Test
    void completesInterruptedQuarantineContainerBeforeBootstrap(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("partial-quarantine"));
        Files.createDirectory(dataRoot.resolve(".quarantine"));

        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertTrue(Files.isDirectory(dataRoot.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY)));
        assertEquals(store.serverId(), ServerIdentityStore.durableAuthorityId(dataRoot).orElseThrow());
    }

    @Test
    void rejectsForeignQuarantineStructureWithoutReplacingIt(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("foreign-quarantine"));
        Path container = Files.createDirectory(dataRoot.resolve(".quarantine"));
        Path foreign = container.resolve("foreign");
        Files.writeString(foreign, "foreign");

        assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertEquals("foreign", Files.readString(foreign));
    }

    @Test
    void rejectsSymlinkProjectionWithoutReadingOutsideRoot(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("symlink-projection"));
        Path outside = temporary.resolve("outside");
        Files.writeString(outside, "outside");
        Path identity = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        try {
            Files.createSymbolicLink(identity, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        assertThrows(Exception.class, () -> ServerIdentityStore.open(identity));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void rejectsEverySymlinkAuthorityArtifactWithoutReadingOutsideRoot(@TempDir Path temporary) throws Exception {
        List<String> names = List.of(ServerIdentityStore.AUTHORITY_FILE, ServerIdentityStore.AUTHORITY_WAL_FILE,
            ServerIdentityStore.AUTHORITY_SHM_FILE, ServerIdentityStore.AUTHORITY_JOURNAL_FILE);
        for (int index = 0; index < names.size(); index++) {
            Path dataRoot = Files.createDirectory(temporary.resolve("symlink-authority-" + index));
            ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
            Path outside = Files.writeString(temporary.resolve("outside-authority-" + index), "outside");
            Path artifact = dataRoot.resolve(names.get(index));
            Files.deleteIfExists(artifact);
            try {
                Files.createSymbolicLink(artifact, outside);
            } catch (UnsupportedOperationException | IOException exception) {
                return;
            }

            assertThrows(IOException.class, () -> ServerIdentityStore.open(store.path()), names.get(index));
            assertEquals("outside", Files.readString(outside));
        }
    }

    @Test
    void authorityOpenRejectsPathReplacementBeforePragmas(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("authority-open-fence"));
        ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path replacementRoot = Files.createDirectory(temporary.resolve("authority-replacement-source"));
        ServerIdentityStore.open(replacementRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path replacementSource = replacementRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path original = temporary.resolve("authority-original.db");
        Path replacement = temporary.resolve("authority-replacement.db");
        Files.copy(replacementSource, replacement);
        byte[] replacementBytes = Files.readAllBytes(replacement);

        assertThrows(Exception.class, () -> ServerIdentityStore.openAuthorityDatabase(database, false, target -> {
            Files.move(target, original);
            try {
                Files.createSymbolicLink(target, replacement);
            } catch (UnsupportedOperationException | IOException exception) {
                Files.copy(replacement, target);
            }
        }).close());
        assertArrayEquals(replacementBytes, Files.readAllBytes(replacement));
        Files.deleteIfExists(database);
        Files.move(original, database);
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void authorityOpenRejectsInjectedSidecarsBeforeJdbc(@TempDir Path temporary) throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside-before-jdbc"), "outside");
        Path probe = temporary.resolve("before-jdbc-probe");
        try {
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        List<String> names = List.of(ServerIdentityStore.AUTHORITY_WAL_FILE,
            ServerIdentityStore.AUTHORITY_SHM_FILE, ServerIdentityStore.AUTHORITY_JOURNAL_FILE);
        for (int index = 0; index < names.size(); index++) {
            Path dataRoot = Files.createDirectory(temporary.resolve("before-jdbc-sidecar-" + index));
            ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
            Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
            Path sidecar = dataRoot.resolve(names.get(index));
            Files.deleteIfExists(sidecar);
            AtomicBoolean afterPragmas = new AtomicBoolean();

            IOException failure = assertThrows(IOException.class,
                () -> ServerIdentityStore.openAuthorityDatabase(database, false,
                    new ServerIdentityStore.AuthorityOpenHook() {
                        @Override
                        public void beforeOpen(Path ignored) throws IOException {
                            Files.createSymbolicLink(sidecar, outside);
                        }

                        @Override
                        public void afterPragmas(Path ignored) {
                            afterPragmas.set(true);
                        }
                    }).close(), names.get(index));

            assertTrue(failure.getMessage().contains("Authority Artifact"), names.get(index));
            assertFalse(afterPragmas.get(), names.get(index));
            assertEquals("outside", Files.readString(outside));
        }
    }

    @Test
    void authorityOpenRejectsRegularSidecarReplacementBeforeJdbc(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("before-jdbc-regular-replacement"));
        ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path journal = dataRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE);
        Path original = temporary.resolve("original-journal");
        Path replacement = Files.writeString(temporary.resolve("replacement-journal"), "other");
        Files.writeString(journal, "first");
        BasicFileAttributes originalAttributes = Files.readAttributes(journal, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        Files.getFileAttributeView(replacement, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setTimes(
            originalAttributes.lastModifiedTime(), originalAttributes.lastAccessTime(), originalAttributes.creationTime());
        AtomicBoolean afterPragmas = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class,
            () -> ServerIdentityStore.openAuthorityDatabase(database, false,
                new ServerIdentityStore.AuthorityOpenHook() {
                    @Override
                    public void beforeOpen(Path ignored) throws IOException {
                        Files.move(journal, original);
                        Files.move(replacement, journal);
                    }

                    @Override
                    public void afterPragmas(Path ignored) {
                        afterPragmas.set(true);
                    }
                }).close());

        assertTrue(failure.getMessage().contains("Authority Artifact Changed Before SQLite Open"));
        assertFalse(afterPragmas.get());
        assertEquals("first", Files.readString(original));
        assertEquals("other", Files.readString(journal));
    }

    @Test
    void authorityOpenRevalidatesSidecarsAfterTheOpenHookAndPragmas(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("authority-open-sidecar-fence"));
        ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path outside = Files.writeString(temporary.resolve("outside-sidecar"), "outside");
        Path probe = temporary.resolve("sidecar-probe");
        try {
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        Path journal = dataRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE);
        Files.deleteIfExists(journal);

        IOException failure = assertThrows(IOException.class,
            () -> ServerIdentityStore.openAuthorityDatabase(database, false,
                new ServerIdentityStore.AuthorityOpenHook() {
                    @Override
                    public void beforeOpen(Path ignored) {
                    }

                    @Override
                    public void afterPragmas(Path ignored) throws IOException {
                        Files.createSymbolicLink(journal, outside);
                    }
                }).close());

        assertTrue(failure.getMessage().contains("Authority Artifact"));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void authorityOpenRejectsRegularWalReplacementAfterTrustedPragmaSnapshot(@TempDir Path temporary)
        throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("after-pragmas-wal-replacement"));
        ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path wal = dataRoot.resolve(ServerIdentityStore.AUTHORITY_WAL_FILE);
        Path original = temporary.resolve("trusted-wal");
        Path replacement = temporary.resolve("replacement-wal");
        AtomicBoolean attempted = new AtomicBoolean();
        AtomicBoolean replaced = new AtomicBoolean();

        try (Connection keeper = ServerIdentityStore.openAuthorityDatabase(database, false,
            ServerIdentityStore.AuthorityOpenHook.none())) {
            try (Statement statement = keeper.createStatement()) {
                statement.executeUpdate("UPDATE resync_install_identity_authority"
                    + " SET install_signal_hash = install_signal_hash");
            }
            assertTrue(Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS));
            IOException failure;
            try {
                failure = assertThrows(IOException.class,
                    () -> ServerIdentityStore.openAuthorityDatabase(database, false,
                        new ServerIdentityStore.AuthorityOpenHook() {
                            @Override
                            public void beforeOpen(Path ignored) {
                            }

                            @Override
                            public void afterPragmas(Path ignored) throws IOException {
                                byte[] bytes = Files.readAllBytes(wal);
                                if (bytes.length == 0) {
                                    bytes = new byte[]{1};
                                } else {
                                    bytes[0] ^= 1;
                                }
                                Files.write(replacement, bytes);
                                BasicFileAttributes attributes = Files.readAttributes(wal, BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                                Files.getFileAttributeView(replacement, BasicFileAttributeView.class,
                                    LinkOption.NOFOLLOW_LINKS).setTimes(attributes.lastModifiedTime(),
                                    attributes.lastAccessTime(), attributes.creationTime());
                                attempted.set(true);
                                Files.move(wal, original);
                                Files.move(replacement, wal);
                                replaced.set(true);
                            }
                        }).close());
            } finally {
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(wal);
                    Files.move(original, wal);
                }
            }

            assertTrue(attempted.get(), failure::toString);
            if (replaced.get()) {
                assertTrue(failure.getMessage().contains("Authority Artifact Changed After SQLite Open Hook"));
            } else {
                assertTrue(failure instanceof FileSystemException);
            }
        }
    }

    @Test
    void authorityOpenRejectsJournalRemovalAfterTrustedPragmaSnapshot(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("after-pragmas-journal-removal"));
        ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path database = dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE);
        Path journal = Files.createFile(dataRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE));
        AtomicBoolean removed = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class,
            () -> ServerIdentityStore.openAuthorityDatabase(database, false,
                new ServerIdentityStore.AuthorityOpenHook() {
                    @Override
                    public void beforeOpen(Path ignored) {
                    }

                    @Override
                    public void afterPragmas(Path ignored) throws IOException {
                        Files.delete(journal);
                        removed.set(true);
                    }
                }).close());

        assertTrue(removed.get());
        assertTrue(failure.getMessage().contains("Authority Artifact Changed After SQLite Open Hook"));
    }

}
