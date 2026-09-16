package restudio.resync.flow.handler.generic;

import restudio.resync.migration.ManagedFlowFileLegacyOwnershipManifest;
import restudio.resync.migration.ManagedFlowFileMigrationCompletion;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ManagedFlowFileMigrationAuthority {
    private static final Set<String> RECORD_NAMES = Set.of(
        ManagedFlowFileMigrationContract.MANIFEST_FILE,
        ManagedFlowFileMigrationContract.COMPLETION_FILE);

    private ManagedFlowFileMigrationAuthority() {
    }

    public static Path migrationRoot(Path persistenceRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(persistenceRoot, "managedFlowFileRoot");
        Path migration = root.resolve(ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY).normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, migration);
        if (Files.exists(migration, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(migration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed flow-file migration path is not a directory");
        }
        return migration;
    }

    public static Path manifestPath(Path persistenceRoot) throws IOException {
        return recordPath(persistenceRoot, ManagedFlowFileMigrationContract.MANIFEST_FILE);
    }

    public static Path completionPath(Path persistenceRoot) throws IOException {
        return recordPath(persistenceRoot, ManagedFlowFileMigrationContract.COMPLETION_FILE);
    }

    public static ManagedFlowFileLegacyOwnershipManifest readManifest(Path persistenceRoot) throws IOException {
        requireMigrationRecords(persistenceRoot);
        return ManagedFlowFileLegacyOwnershipManifest.read(readBytes(recordPath(
            persistenceRoot, ManagedFlowFileMigrationContract.MANIFEST_FILE)));
    }

    public static ManagedFlowFileMigrationCompletion readCompletion(Path persistenceRoot) throws IOException {
        requireMigrationRecords(persistenceRoot);
        return ManagedFlowFileMigrationCompletion.read(readBytes(recordPath(
            persistenceRoot, ManagedFlowFileMigrationContract.COMPLETION_FILE)));
    }

    public static void rejectFreshMigrationRecords(Path persistenceRoot) throws IOException {
        Path migration = migrationRoot(persistenceRoot);
        if (Files.notExists(migration, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        MigrationPaths.requireNoSymlinkTree(migration);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(migration)) {
            if (children.iterator().hasNext()) {
                throw new IOException("Fresh managed flow-file stores cannot contain migration records");
            }
        }
    }

    static Admission admitPending(Connection connection, Path databaseFile, Path persistenceRoot,
                                  ManagedFlowFileMigrationContract.StoreMetadata metadata)
        throws IOException, SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(persistenceRoot, "persistenceRoot");
        Objects.requireNonNull(metadata, "metadata");
        if (!connection.getAutoCommit()) {
            throw new SQLException("Managed flow-file admission requires an idle SQLite connection");
        }
        boolean transactionStarted = false;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
            transactionStarted = true;
            SqliteManagedFlowFileCapability.validateSchemaShape(connection);
            ManagedFlowFileMigrationContract.StoreMetadata current = SqliteManagedFlowFileCapability.readMetadata(connection);
            if (!current.equals(metadata)) {
                if (current.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED) {
                    validateAdmitted(persistenceRoot, current);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("COMMIT");
                    }
                    return null;
                }
                throw new IOException("Managed flow-file pending metadata changed before admission");
            }
            Admission admission = validatePendingBaseline(connection, databaseFile, persistenceRoot, current);
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + ManagedFlowFileStoreContract.METADATA_TABLE
                    + " SET " + ManagedFlowFileStoreContract.METADATA_ORIGIN_COLUMN + " = ?, "
                    + ManagedFlowFileStoreContract.METADATA_INSTALL_IDENTITY_COLUMN + " = ?, "
                    + ManagedFlowFileStoreContract.METADATA_MIGRATION_ID_COLUMN + " = ?, "
                    + ManagedFlowFileStoreContract.METADATA_COMPLETION_AUTHORITY_HASH_COLUMN + " = ?, "
                    + ManagedFlowFileStoreContract.METADATA_COMPLETION_HASH_COLUMN + " = ?"
                    + " WHERE id = 1 AND " + ManagedFlowFileStoreContract.METADATA_ORIGIN_COLUMN + " = ?"
                    + " AND " + ManagedFlowFileStoreContract.METADATA_MIGRATION_ID_COLUMN + " = ?"
                    + " AND " + ManagedFlowFileStoreContract.METADATA_COMPLETION_AUTHORITY_HASH_COLUMN + " IS NULL"
                    + " AND " + ManagedFlowFileStoreContract.METADATA_COMPLETION_HASH_COLUMN + " IS NULL")) {
                statement.setString(1, ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED.wireValue());
                statement.setString(2, admission.completion().targetInstallIdentity());
                statement.setString(3, admission.completion().migrationId());
                statement.setString(4, admission.completion().completionAuthorityHash());
                statement.setString(5, admission.completion().completionHash());
                statement.setString(6, ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING.wireValue());
                statement.setString(7, current.migrationId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Managed flow-file pending metadata was not admitted");
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
            return admission;
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            if (transactionStarted) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private static Admission validatePendingBaseline(Connection connection, Path databaseFile, Path persistenceRoot,
                                                     ManagedFlowFileMigrationContract.StoreMetadata metadata)
        throws IOException, SQLException {
        if (metadata.origin() != ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING) {
            throw new IOException("Managed flow-file admission requires a pending offline-migration origin");
        }
        ManagedFlowFileLegacyOwnershipManifest manifest = readManifest(persistenceRoot);
        ManagedFlowFileMigrationCompletion completion = readCompletion(persistenceRoot);
        if (!completion.matchesPending(manifest, metadata)) {
            throw new IOException("Managed flow-file migration completion does not match pending store metadata");
        }
        LogicalSnapshot logical = readLogicalSnapshot(connection);
        if (logical.entryCount() != completion.entryCount()
            || !logical.logicalContentHash().equals(completion.logicalContentHash())) {
            throw new IOException("Managed flow-file migration logical content does not match completion record");
        }
        List<ManagedFlowFileMigrationContract.LegacyEntry> actualEntries = logical.entries().stream()
            .map(entry -> new ManagedFlowFileMigrationContract.LegacyEntry(
                entry.logicalPath(), entry.kind(), entry.size(), entry.sha256()))
            .sorted(ManagedFlowFileMigrationContract.legacyEntryComparator())
            .toList();
        if (!manifest.entries().equals(actualEntries)) {
            throw new IOException("Managed flow-file migration manifest does not describe the database content");
        }
        String physicalHash = physicalDatabaseHash(databaseFile);
        if (!physicalHash.equals(completion.physicalDatabaseHash())) {
            throw new IOException("Managed flow-file migration database hash does not match completion record");
        }
        if (manifest.entryCount() != completion.entryCount()) {
            throw new IOException("Managed flow-file migration manifest entry count does not match completion record");
        }
        return new Admission(manifest, completion, logical, physicalHash);
    }

    static void validateAdmitted(Path persistenceRoot,
                                 ManagedFlowFileMigrationContract.StoreMetadata metadata) throws IOException {
        Objects.requireNonNull(persistenceRoot, "persistenceRoot");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.origin() != ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED) {
            throw new IOException("Managed flow-file restart requires an admitted migration origin");
        }
        ManagedFlowFileLegacyOwnershipManifest manifest = readManifest(persistenceRoot);
        ManagedFlowFileMigrationCompletion completion = readCompletion(persistenceRoot);
        if (!completion.matchesAdmitted(manifest, metadata)) {
            throw new IOException("Managed flow-file completion provenance does not match admitted metadata");
        }
    }

    private static LogicalSnapshot readLogicalSnapshot(Connection connection) throws SQLException {
        List<ManagedFlowFileMigrationContract.LogicalEntry> entries = new ArrayList<>();
        String query = "SELECT " + ManagedFlowFileStoreContract.FILES_PATH_COLUMN + ", "
            + ManagedFlowFileStoreContract.FILES_KIND_COLUMN + ", "
            + ManagedFlowFileStoreContract.FILES_CONTENT_COLUMN + " FROM "
            + ManagedFlowFileStoreContract.FILES_TABLE + " WHERE "
            + ManagedFlowFileStoreContract.FILES_PATH_COLUMN + " <> ''";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String path = result.getString(1);
                String kind = result.getString(2);
                byte[] content = result.getBytes(3);
                ManagedFlowFileMigrationContract.EntryKind entryKind;
                if ("FILE".equals(kind)) {
                    entryKind = ManagedFlowFileMigrationContract.EntryKind.FILE;
                } else if ("DIRECTORY".equals(kind)) {
                    entryKind = ManagedFlowFileMigrationContract.EntryKind.DIRECTORY;
                } else {
                    throw new SQLException("Managed flow-file database contains an unsupported entry kind");
                }
                try {
                    entries.add(new ManagedFlowFileMigrationContract.LogicalEntry(path, entryKind, content));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("Managed flow-file database contains an invalid logical path or entry", exception);
                }
            }
        }
        entries.sort(ManagedFlowFileMigrationContract.logicalEntryComparator());
        return new LogicalSnapshot(entries.size(), ManagedFlowFileMigrationContract.canonicalLogicalContentHash(entries), entries);
    }

    private static String physicalDatabaseHash(Path databaseFile) throws IOException {
        Path database = MigrationPaths.requirePath(databaseFile, "databaseFile");
        if (Files.isSymbolicLink(database) || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed flow-file database is not a regular file");
        }
        return StorageSafety.sha256(Files.readAllBytes(database));
    }

    private static void requireMigrationRecords(Path persistenceRoot) throws IOException {
        Path migration = migrationRoot(persistenceRoot);
        if (Files.notExists(migration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed flow-file migration directory is missing");
        }
        MigrationPaths.requireNoSymlinkTree(migration);
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(migration)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        if (children.size() != RECORD_NAMES.size()
            || children.stream().anyMatch(path -> !RECORD_NAMES.contains(path.getFileName().toString()))) {
            throw new IOException("Managed flow-file migration directory must contain exactly the two migration records");
        }
        for (String recordName : RECORD_NAMES) {
            Path record = recordPath(persistenceRoot, recordName);
            MigrationPaths.requireNoSymlinkTraversal(migration, record);
            if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Managed flow-file migration record is not a regular file");
            }
        }
    }

    private static Path recordPath(Path persistenceRoot, String fileName) throws IOException {
        Path migration = migrationRoot(persistenceRoot);
        Path path = migration.resolve(fileName).normalize();
        if (!path.getParent().equals(migration) || !path.startsWith(migration)) {
            throw new IOException("Managed flow-file migration record path is invalid");
        }
        if (Files.exists(migration, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireNoSymlinkTraversal(migration, path);
        }
        return path;
    }

    private static byte[] readBytes(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed flow-file migration record is missing or not regular");
        }
        return Files.readAllBytes(path);
    }

    public record LogicalSnapshot(int entryCount, String logicalContentHash,
                                  List<ManagedFlowFileMigrationContract.LogicalEntry> entries) {
        public LogicalSnapshot {
            if (entryCount < 0 || entries == null || entryCount != entries.size()) {
                throw new IllegalArgumentException("Managed flow-file logical snapshot entry count is invalid");
            }
            logicalContentHash = ManagedFlowFileMigrationContract.requireDigest(logicalContentHash, "logicalContentHash");
            entries = List.copyOf(entries);
        }
    }

    public record Admission(ManagedFlowFileLegacyOwnershipManifest manifest,
                            ManagedFlowFileMigrationCompletion completion,
                            LogicalSnapshot logical,
                            String physicalDatabaseHash) {
        public Admission {
            manifest = Objects.requireNonNull(manifest, "manifest");
            completion = Objects.requireNonNull(completion, "completion");
            logical = Objects.requireNonNull(logical, "logical");
            physicalDatabaseHash = ManagedFlowFileMigrationContract.requireDigest(physicalDatabaseHash, "physicalDatabaseHash");
        }
    }
}
