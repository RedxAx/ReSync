package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.ManagedFlowFileLegacyOwnershipManifest;
import restudio.resync.migration.ManagedFlowFileMigrationCompletion;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedFlowFileMigrationAdmissionTest {
    @TempDir
    Path temporary;

    @Test
    void freshInstallCarriesOriginAndIdentityWithoutMigrationRecords() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh"));
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot, true, "target-install")) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + capability.databaseFile());
                 var statement = connection.prepareStatement(
                     "SELECT origin, contract_version, install_identity, migration_id, completion_authority_hash, completion_hash FROM "
                         + ManagedFlowFileStoreContract.METADATA_TABLE + " WHERE id = 1");
                 var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("fresh-install", result.getString(1));
                assertEquals(ManagedFlowFileStoreContract.CONTRACT_VERSION, result.getInt(2));
                assertEquals("target-install", result.getString(3));
                assertEquals(null, result.getString(4));
                assertEquals(null, result.getString(5));
                assertEquals(null, result.getString(6));
            }
            Path migrationRoot = ManagedFlowFileMigrationAuthority.migrationRoot(capability.persistenceRoot());
            if (Files.exists(migrationRoot)) {
                try (var entries = Files.list(migrationRoot)) {
                    assertEquals(0, entries.count());
                }
            }
        }
    }

    @Test
    void freshInstallRejectsAnyMigrationDirectoryEntry() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh-extra"));
        Path migrationRoot = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)
            .resolve(ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY));
        Files.writeString(migrationRoot.resolve("unexpected"), "unexpected");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, true, "target-install"));
    }

    @Test
    void migratedInstallAdmitsOnceThenAllowsMutationAndRestart() throws Exception {
        Fixture fixture = fixture("migrated", List.of(
            new LogicalFixture("folder/value.txt", ManagedFlowFileMigrationContract.EntryKind.FILE, "value")));

        try (SqliteManagedFlowFileCapability admitted = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
            assertEquals("value", admitted.read("folder/value.txt"));
            assertEquals("offline-migration-admitted", metadataValue(fixture.database(), "origin"));
            assertEquals(fixture.completion().completionAuthorityHash(), metadataValue(
                fixture.database(), "completion_authority_hash"));
            assertEquals(fixture.completion().completionHash(), metadataValue(
                fixture.database(), "completion_hash"));
        }

        try (SqliteManagedFlowFileCapability mutated = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
            mutated.write("folder/after.txt", "after");
        }
        try (SqliteManagedFlowFileCapability restarted = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
            assertEquals("after", restarted.read("folder/after.txt"));
        }

        Files.delete(ManagedFlowFileMigrationAuthority.completionPath(fixture.persistenceRoot()));
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void zeroEntryMigrationAdmitsWithEmptyLogicalBaseline() throws Exception {
        Fixture fixture = fixture("zero", List.of());
        try (SqliteManagedFlowFileCapability admitted = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
            assertEquals("offline-migration-admitted", metadataValue(fixture.database(), "origin"));
            assertEquals(0, fixture.completion().entryCount());
            assertEquals(ManagedFlowFileMigrationContract.canonicalLogicalContentHash(List.of()),
                fixture.completion().logicalContentHash());
        }
    }

    @Test
    void migratedInstallRejectsExtraRecord() throws Exception {
        Fixture fixture = fixture("extra-record", List.of());
        Files.writeString(fixture.persistenceRoot().resolve(ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY)
            .resolve("extra-record"), "extra");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void migratedInstallRejectsPhysicalBaselineTampering() throws Exception {
        Fixture fixture = fixture("physical-tamper", List.of());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 73");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void admittedRestartRejectsCompletionMutation() throws Exception {
        Fixture fixture = fixture("completion-mutation", List.of());
        try (SqliteManagedFlowFileCapability admitted = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
        }
        ManagedFlowFileMigrationCompletion original = ManagedFlowFileMigrationCompletion.read(
            Files.readAllBytes(ManagedFlowFileMigrationAuthority.completionPath(fixture.persistenceRoot())));
        ManagedFlowFileMigrationCompletion mutated = new ManagedFlowFileMigrationCompletion(
            original.formatVersion(), original.migrationVersion(), original.migrationId(), original.manifestHash(),
            original.database(), original.entryCount(), original.logicalContentHash(), digest("replacement-physical"),
            original.source(), original.sourceArchiveIdentity(), original.targetInstallIdentity());
        StorageSafety.writeBytesAtomicStrict(ManagedFlowFileMigrationAuthority.completionPath(fixture.persistenceRoot()),
            mutated.canonicalBytes());
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void admittedRestartRejectsMetadataBindingMismatches() throws Exception {
        Fixture targetMismatch = fixture("target-mismatch", List.of());
        admit(targetMismatch);
        updateMetadata(targetMismatch.database(), "install_identity", "other-install");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(targetMismatch.dataRoot(), false));

        Fixture migrationMismatch = fixture("migration-mismatch", List.of());
        admit(migrationMismatch);
        updateMetadata(migrationMismatch.database(), "migration_id", "other-migration");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(migrationMismatch.dataRoot(), false, "target-install"));

        Fixture hashMismatch = fixture("hash-mismatch", List.of());
        admit(hashMismatch);
        updateMetadata(hashMismatch.database(), "completion_hash", digest("other-completion"));
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(hashMismatch.dataRoot(), false, "target-install"));
    }

    @Test
    void rootRowMustBeDirectoryAndEmpty() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("root-content"));
        Path database;
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(
            dataRoot, true, "target-install")) {
            database = capability.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("UPDATE managed_files SET content = X'01' WHERE path = ''");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, false, "target-install"));

        Path kindDataRoot = Files.createDirectory(temporary.resolve("root-kind"));
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(
            kindDataRoot, true, "target-install")) {
            database = capability.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("UPDATE managed_files SET kind = 'FILE' WHERE path = ''");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(kindDataRoot, false, "target-install"));
    }

    @Test
    void metadataMustContainExactlyOneRow() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("metadata-count"));
        Path database;
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(
            dataRoot, true, "target-install")) {
            database = capability.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.execute("INSERT INTO managed_flow_file_store_metadata "
                + "SELECT 2, schema_id, format_version, writer_id, writer_version, origin, contract_version, "
                + "install_identity, source_snapshot_identity, source_install_identity, source_archive_identity, "
                + "migration_id, completion_authority_hash, completion_hash "
                + "FROM managed_flow_file_store_metadata WHERE id = 1");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, false, "target-install"));
    }

    @Test
    void rootRowCountMustBeExactlyOne() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("root-count"));
        Path database;
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(
            dataRoot, true, "target-install")) {
            database = capability.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM managed_files WHERE path = ''");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, false, "target-install"));
    }

    @Test
    void concurrentAdmissionHasOneCommittedMetadataTransition() throws Exception {
        Fixture fixture = fixture("concurrent-admission", List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> openAndClose(fixture.dataRoot()));
            Future<Boolean> second = executor.submit(() -> openAndClose(fixture.dataRoot()));
            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals("offline-migration-admitted", metadataValue(fixture.database(), "origin"));
        assertEquals(fixture.completion().completionHash(), metadataValue(fixture.database(), "completion_hash"));
    }

    @Test
    void metadataMutationTriggerIsRejectedBeforeAdmission() throws Exception {
        Fixture fixture = fixture("metadata-trigger", List.of());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER mutate_metadata AFTER UPDATE OF origin ON "
                + ManagedFlowFileStoreContract.METADATA_TABLE + " BEGIN UPDATE "
                + ManagedFlowFileStoreContract.METADATA_TABLE
                + " SET install_identity = 'tampered' WHERE id = 1; END");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
        assertEquals("offline-migration-pending", metadataValue(fixture.database(), "origin"));
    }

    @Test
    void alternateCollationSchemaIsRejected() throws Exception {
        DatabaseFixture fixture = lookalikeDatabase("alternate-collation",
            "CREATE TABLE managed_files (path TEXT PRIMARY KEY COLLATE NOCASE NOT NULL,"
                + "kind TEXT NOT NULL CHECK(kind IN ('FILE', 'DIRECTORY')),content BLOB NOT NULL)");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void extraUniqueConstraintIsRejected() throws Exception {
        DatabaseFixture fixture = lookalikeDatabase("extra-unique",
            "CREATE TABLE managed_files (path TEXT PRIMARY KEY NOT NULL,"
                + "kind TEXT NOT NULL CHECK(kind IN ('FILE', 'DIRECTORY')),content BLOB NOT NULL,UNIQUE(kind))");
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void lowercaseStringLiteralSchemaIsRejectedBeforeAdmission() throws Exception {
        assertLookalikeSchemaRejected("lowercase-literal",
            "CREATE TABLE managed_files (path TEXT PRIMARY KEY NOT NULL,"
                + "kind TEXT NOT NULL CHECK(kind IN ('file', 'DIRECTORY')),content BLOB NOT NULL)");
    }

    @Test
    void embeddedWhitespaceStringLiteralSchemaIsRejectedBeforeAdmission() throws Exception {
        assertLookalikeSchemaRejected("embedded-whitespace-literal",
            "CREATE TABLE managed_files (path TEXT PRIMARY KEY NOT NULL,"
                + "kind TEXT NOT NULL CHECK(kind IN ('FI LE', 'DIRECTORY')),content BLOB NOT NULL)");
    }

    @Test
    void escapedStringLiteralSchemaIsRejectedBeforeAdmission() throws Exception {
        assertLookalikeSchemaRejected("escaped-literal",
            "CREATE TABLE managed_files (path TEXT PRIMARY KEY NOT NULL,"
                + "kind TEXT NOT NULL CHECK(kind IN ('FILE', 'DIRECTORY', 'DIREC''TORY')),content BLOB NOT NULL)");
    }

    @Test
    void explicitIndexIsRejected() throws Exception {
        Path dataRoot = freshDatabase("explicit-index");
        Path database = dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)
            .resolve(ManagedFlowFileCapability.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX explicit_managed_files_index ON managed_files(kind)");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, false, "target-install"));
    }

    @Test
    void viewIsRejected() throws Exception {
        Path dataRoot = freshDatabase("extra-view");
        Path database = dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY)
            .resolve(ManagedFlowFileCapability.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE VIEW extra_managed_files_view AS SELECT path FROM managed_files");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, false, "target-install"));
    }

    @Test
    void migrationDirectorySymlinkIsRejected() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("migration-link"));
        Path root = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Path target = Files.createDirectory(temporary.resolve("migration-target"));
        try {
            Files.createSymbolicLink(root.resolve(ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY), target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(dataRoot, true, "target-install"));
    }

    @Test
    void migrationRecordSymlinkIsRejected() throws Exception {
        Fixture fixture = fixture("record-link", List.of());
        Path migration = fixture.persistenceRoot().resolve(ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY);
        Path manifest = migration.resolve(ManagedFlowFileMigrationContract.MANIFEST_FILE);
        Path target = migration.resolve("manifest-target");
        Files.move(manifest, target);
        try {
            Files.createSymbolicLink(manifest, target.getFileName());
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable");
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
    }

    @Test
    void preMetadataExistingDatabaseRemainsUnavailable() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("legacy"));
        Path root = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Path database = root.resolve(ManagedFlowFileCapability.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE managed_flow_file_store_metadata (id INTEGER PRIMARY KEY, schema_id TEXT, format_version INTEGER, writer_id TEXT)");
            statement.execute("INSERT INTO managed_flow_file_store_metadata VALUES(1, 'resync-managed-flow-files', 1, 'resync.flow.files')");
        }
        assertThrows(IllegalArgumentException.class, () -> new SqliteManagedFlowFileCapability(dataRoot, false));
    }

    private Fixture fixture(String name, List<LogicalFixture> logicalFixtures) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(name));
        Path database;
        Path persistenceRoot;
        try (SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot, true, "target-install")) {
            for (LogicalFixture fixture : logicalFixtures) {
                capability.write(fixture.path(), fixture.content());
            }
            database = capability.databaseFile();
            persistenceRoot = capability.persistenceRoot();
        }
        ManagedFlowFileMigrationContract.SourceIdentity source =
            new ManagedFlowFileMigrationContract.SourceIdentity("snapshot-" + name, "legacy-install-" + name);
        String migrationId = "migration-" + name;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "UPDATE " + ManagedFlowFileStoreContract.METADATA_TABLE
                     + " SET origin = ?, source_snapshot_identity = ?, source_install_identity = ?, "
                     + "source_archive_identity = ?, migration_id = ?, completion_authority_hash = NULL, completion_hash = NULL WHERE id = 1")) {
            statement.setString(1, ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING.wireValue());
            statement.setString(2, source.snapshotId());
            statement.setString(3, source.installId());
            statement.setString(4, "archive-" + name);
            statement.setString(5, migrationId);
            statement.executeUpdate();
        }

        List<ManagedFlowFileMigrationContract.LogicalEntry> logicalEntries = readLogicalEntries(database);
        List<ManagedFlowFileMigrationContract.LegacyEntry> manifestEntries = logicalEntries.stream()
            .map(entry -> new ManagedFlowFileMigrationContract.LegacyEntry(
                entry.logicalPath(), entry.kind(), entry.size(), entry.sha256()))
            .toList();
        ManagedFlowFileLegacyOwnershipManifest manifest =
            new ManagedFlowFileLegacyOwnershipManifest(source, manifestEntries);
        String physicalHash = StorageSafety.sha256(Files.readAllBytes(database));
        ManagedFlowFileMigrationCompletion completion = new ManagedFlowFileMigrationCompletion(
            1, migrationId, manifest.manifestHash(), ManagedFlowFileMigrationContract.DatabaseIdentity.current(),
            logicalEntries.size(), ManagedFlowFileMigrationContract.canonicalLogicalContentHash(logicalEntries),
            physicalHash, source, "archive-" + name, "target-install");
        Path migrationRoot = Files.createDirectories(persistenceRoot.resolve(
            ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY));
        StorageSafety.writeBytesAtomicStrict(migrationRoot.resolve(ManagedFlowFileMigrationContract.MANIFEST_FILE),
            manifest.canonicalBytes());
        StorageSafety.writeBytesAtomicStrict(migrationRoot.resolve(ManagedFlowFileMigrationContract.COMPLETION_FILE),
            completion.canonicalBytes());
        return new Fixture(dataRoot, database, persistenceRoot, completion);
    }

    private Path freshDatabase(String name) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(name));
        try (SqliteManagedFlowFileCapability ignored = new SqliteManagedFlowFileCapability(
            dataRoot, true, "target-install")) {
        }
        return dataRoot;
    }

    private DatabaseFixture lookalikeDatabase(String name, String filesSql) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(name));
        Path root = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Path database = root.resolve(ManagedFlowFileCapability.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute(filesSql);
            statement.execute("CREATE TABLE managed_flow_file_store_metadata ("
                + "id INTEGER PRIMARY KEY CHECK(id = 1),schema_id TEXT NOT NULL,format_version INTEGER NOT NULL,"
                + "writer_id TEXT NOT NULL,writer_version INTEGER NOT NULL,origin TEXT NOT NULL,"
                + "contract_version INTEGER NOT NULL,install_identity TEXT NOT NULL,source_snapshot_identity TEXT,"
                + "source_install_identity TEXT,source_archive_identity TEXT,migration_id TEXT,"
                + "completion_authority_hash TEXT,completion_hash TEXT)");
            statement.execute("INSERT INTO managed_flow_file_store_metadata VALUES(1,"
                + "'resync-managed-flow-files',1,'resync.flow.files',1,'fresh-install',1,'target-install',"
                + "NULL,NULL,NULL,NULL,NULL,NULL)");
            statement.execute("INSERT INTO managed_files(path, kind, content) VALUES('', 'DIRECTORY', X'')");
        }
        return new DatabaseFixture(dataRoot, database);
    }

    private void assertLookalikeSchemaRejected(String name, String filesSql) throws Exception {
        DatabaseFixture fixture = lookalikeDatabase(name, filesSql);
        assertThrows(IllegalArgumentException.class,
            () -> new SqliteManagedFlowFileCapability(fixture.dataRoot(), false, "target-install"));
        assertEquals("fresh-install", metadataValue(fixture.database(), "origin"));
    }

    private static void admit(Fixture fixture) throws Exception {
        try (SqliteManagedFlowFileCapability ignored = new SqliteManagedFlowFileCapability(
            fixture.dataRoot(), false, "target-install")) {
        }
    }

    private static boolean openAndClose(Path dataRoot) throws Exception {
        try (SqliteManagedFlowFileCapability ignored = new SqliteManagedFlowFileCapability(
            dataRoot, false, "target-install")) {
            return true;
        }
    }

    private static void updateMetadata(Path database, String column, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "UPDATE " + ManagedFlowFileStoreContract.METADATA_TABLE + " SET " + column + " = ? WHERE id = 1")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private static List<ManagedFlowFileMigrationContract.LogicalEntry> readLogicalEntries(Path database) throws Exception {
        List<ManagedFlowFileMigrationContract.LogicalEntry> entries = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "SELECT path, kind, content FROM managed_files WHERE path <> ''");
             var result = statement.executeQuery()) {
            while (result.next()) {
                ManagedFlowFileMigrationContract.EntryKind kind = "DIRECTORY".equals(result.getString(2))
                    ? ManagedFlowFileMigrationContract.EntryKind.DIRECTORY
                    : ManagedFlowFileMigrationContract.EntryKind.FILE;
                entries.add(new ManagedFlowFileMigrationContract.LogicalEntry(
                    result.getString(1), kind, result.getBytes(3)));
            }
        }
        entries.sort(ManagedFlowFileMigrationContract.logicalEntryComparator());
        return entries;
    }

    private static String metadataValue(Path database, String column) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "SELECT " + column + " FROM " + ManagedFlowFileStoreContract.METADATA_TABLE + " WHERE id = 1");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static String digest(String value) {
        return ManagedFlowFileMigrationContract.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private record LogicalFixture(String path, ManagedFlowFileMigrationContract.EntryKind kind, String content) {
    }

    private record Fixture(Path dataRoot, Path database, Path persistenceRoot,
                           ManagedFlowFileMigrationCompletion completion) {
    }

    private record DatabaseFixture(Path dataRoot, Path database) {
    }
}
