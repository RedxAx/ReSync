package restudio.resync.flow.handler.generic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationPaths;

public final class SqliteManagedFlowFileCapability implements ManagedFlowFileCapability {
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");
    private static final String FILE_KIND = "FILE";
    private static final String DIRECTORY_KIND = "DIRECTORY";
    private static final String CREATE_TABLE = ManagedFlowFileStoreContract.createFilesTableSql();
    private static final String CREATE_METADATA_TABLE = ManagedFlowFileStoreContract.createMetadataTableSql();
    private static final String CANONICAL_FILES_SQL = normalizeSql(
        CREATE_TABLE.replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE "));
    private static final String CANONICAL_METADATA_SQL = normalizeSql(
        CREATE_METADATA_TABLE.replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE "));
    private static final String FILES_PRIMARY_KEY_INDEX = "sqlite_autoindex_managed_files_1";
    private static final List<ColumnSpec> FILE_COLUMNS = List.of(
        new ColumnSpec("path", "TEXT", true, true),
        new ColumnSpec("kind", "TEXT", true, false),
        new ColumnSpec("content", "BLOB", true, false));
    private static final List<ColumnSpec> METADATA_COLUMNS = List.of(
        new ColumnSpec("id", "INTEGER", false, true),
        new ColumnSpec("schema_id", "TEXT", true, false),
        new ColumnSpec("format_version", "INTEGER", true, false),
        new ColumnSpec("writer_id", "TEXT", true, false),
        new ColumnSpec("writer_version", "INTEGER", true, false),
        new ColumnSpec("origin", "TEXT", true, false),
        new ColumnSpec("contract_version", "INTEGER", true, false),
        new ColumnSpec("install_identity", "TEXT", true, false),
        new ColumnSpec("source_snapshot_identity", "TEXT", false, false),
        new ColumnSpec("source_install_identity", "TEXT", false, false),
        new ColumnSpec("source_archive_identity", "TEXT", false, false),
        new ColumnSpec("migration_id", "TEXT", false, false),
        new ColumnSpec("completion_authority_hash", "TEXT", false, false),
        new ColumnSpec("completion_hash", "TEXT", false, false));
    private static final int SQLITE_OPEN_READWRITE = 0x00000002;
    private static final int SQLITE_OPEN_CREATE = 0x00000004;
    private static final int SQLITE_OPEN_URI = 0x00000040;
    private static final int SQLITE_OPEN_PRIVATECACHE = 0x00040000;
    private static final int SQLITE_OPEN_NOFOLLOW = 0x01000000;
    private static final FailureInjector NO_FAILURES = new FailureInjector() {
    };
    private final FailureInjector failureInjector;
    private final String requestedInstallIdentity;
    private Path dataRoot;
    private Path dataCanonicalRoot;
    private Object dataRootFileKey;
    private Path persistenceRoot;
    private Path canonicalPersistenceRoot;
    private Object persistenceRootFileKey;
    private Path databaseFile;
    private Path canonicalDatabaseFile;
    private Object databaseFileKey;
    private Connection connection;
    private boolean closed;
    private boolean fenced;
    private boolean quiesced;
    private TransactionState transactionState = TransactionState.IDLE;
    private String activeOperation;
    private int mutationCount;
    private IOException postCommitCleanupFailure;
    private ManagedFlowFileMigrationContract.StoreMetadata storeMetadata;

    public SqliteManagedFlowFileCapability(Path dataRoot) {
        this(dataRoot, true, null, NO_FAILURES);
    }

    public SqliteManagedFlowFileCapability(Path dataRoot, boolean allowFreshBootstrap) {
        this(dataRoot, allowFreshBootstrap, null, NO_FAILURES);
    }

    public SqliteManagedFlowFileCapability(Path dataRoot, boolean allowFreshBootstrap, String installIdentity) {
        this(dataRoot, allowFreshBootstrap, installIdentity, NO_FAILURES);
    }

    SqliteManagedFlowFileCapability(Path dataRoot, FailureInjector failureInjector) {
        this(dataRoot, true, null, failureInjector);
    }

    SqliteManagedFlowFileCapability(Path dataRoot, boolean allowFreshBootstrap, FailureInjector failureInjector) {
        this(dataRoot, allowFreshBootstrap, null, failureInjector);
    }

    private SqliteManagedFlowFileCapability(Path dataRoot, boolean allowFreshBootstrap, String installIdentity,
                                            FailureInjector failureInjector) {
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
        this.requestedInstallIdentity = installIdentity == null ? null
            : ManagedFlowFileMigrationContract.requireIdentity(installIdentity, "installIdentity");
        Connection openedConnection = null;
        try {
            Binding binding = prepareBinding(dataRoot, allowFreshBootstrap);
            boolean freshDatabase = Files.notExists(binding.databaseFile(), LinkOption.NOFOLLOW_LINKS);
            if (freshDatabase && !allowFreshBootstrap) {
                throw new IllegalArgumentException("Managed Flow File Database Is Missing For Existing Installation");
            }
            openedConnection = openDatabase(binding.databaseFile(), freshDatabase);
            initialize(openedConnection, binding, freshDatabase);
            assignBinding(binding, openedConnection);
            this.connection = openedConnection;
            verifyBinding();
        } catch (IOException | SQLException | RuntimeException exception) {
            if (openedConnection != null) {
                try {
                    openedConnection.close();
                } catch (SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Failed To Initialize Managed Flow File Database", exception);
        }
    }

    @Override
    public synchronized Path root() {
        return persistenceRoot;
    }

    @Override
    public synchronized Path databaseFile() {
        return databaseFile;
    }

    @Override
    public synchronized Path persistenceRoot() {
        return persistenceRoot;
    }

    @Override
    public synchronized List<Path> persistenceFiles() {
        return sidecars(databaseFile);
    }

    @Override
    public synchronized boolean available() {
        return !closed && !fenced;
    }

    @Override
    public synchronized String failureReason() {
        if (postCommitCleanupFailure != null) {
            return postCommitCleanupFailure.getMessage();
        }
        if (fenced) {
            return "Managed flow-file storage is fenced";
        }
        if (closed) {
            return "Managed flow-file storage is closed";
        }
        return "";
    }

    @Override
    public synchronized IOException postCommitCleanupFailure() {
        return postCommitCleanupFailure;
    }

    @Override
    public synchronized String normalize(String path) throws AccessException {
        if (path == null || path.isBlank()) {
            throw new AccessException("FILE_PATH_REQUIRED", "File path is required");
        }
        if (path.indexOf('\u0000') >= 0 || path.indexOf('\\') >= 0) {
            throw new AccessException("FILE_PATH_INVALID", "File path is invalid");
        }
        if (path.startsWith("/") || WINDOWS_DRIVE_PATH.matcher(path).matches()) {
            throw new AccessException("FILE_PATH_ABSOLUTE",
                "File path must be relative to the managed flow-file root");
        }
        String[] segments = path.split("/", -1);
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (normalized.isEmpty()) {
                    throw new AccessException("FILE_PATH_OUTSIDE_MANAGED_ROOT",
                        "File path must stay inside the managed flow-file root");
                }
                normalized.removeLast();
                continue;
            }
            if (segment.indexOf(':') >= 0 || segment.indexOf('\u0000') >= 0) {
                throw new AccessException("FILE_PATH_INVALID", "File path contains an unsafe segment");
            }
            normalized.add(segment);
        }
        return String.join("/", normalized);
    }

    @Override
    public synchronized String write(String path, String content) throws IOException {
        ensureOpen();
        String normalized = entryPath(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        return transaction("write", () -> {
            ensureParentDirectories(normalized);
            upsertFile(normalized, bytes);
            return normalized;
        });
    }

    @Override
    public synchronized String append(String path, String content) throws IOException {
        ensureOpen();
        String normalized = entryPath(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        return transaction("append", () -> {
            ensureParentDirectories(normalized);
            Entry existing = entry(normalized);
            if (existing != null && existing.directory()) {
                throw new AccessException("FILE_NOT_REGULAR", "Path is not a regular file");
            }
            if (existing == null) {
                insertFile(normalized, bytes);
                return normalized;
            }
            byte[] combined = new byte[existing.content().length + bytes.length];
            System.arraycopy(existing.content(), 0, combined, 0, existing.content().length);
            System.arraycopy(bytes, 0, combined, existing.content().length, bytes.length);
            updateFile(normalized, combined);
            return normalized;
        });
    }

    @Override
    public synchronized String read(String path) throws IOException {
        ensureOpen();
        Entry existing = requireFile(entryPath(path));
        return new String(existing.content(), StandardCharsets.UTF_8);
    }

    @Override
    public synchronized List<String> readLines(String path) throws IOException {
        return read(path).lines().toList();
    }

    @Override
    public synchronized boolean delete(String path) throws IOException {
        ensureOpen();
        String normalized = entryPath(path);
        return transaction("delete", () -> {
            Entry existing = entry(normalized);
            if (existing == null) {
                throw new AccessException("FILE_NOT_FOUND", "File does not exist");
            }
            if (existing.directory() && hasChildren(normalized)) {
                throw new AccessException("FILE_DIRECTORY_NOT_EMPTY", "Directory is not empty");
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + ManagedFlowFileStoreContract.FILES_TABLE + " WHERE path = ?")) {
                statement.setString(1, normalized);
                statement.executeUpdate();
            }
            markMutation();
            return true;
        });
    }

    @Override
    public synchronized boolean exists(String path) throws IOException {
        ensureOpen();
        return entry(normalize(path)) != null;
    }

    @Override
    public synchronized String copy(String sourcePath, String destinationPath) throws IOException {
        ensureOpen();
        String source = entryPath(sourcePath);
        String destination = entryPath(destinationPath);
        ensureDifferent(source, destination);
        return transaction("copy", () -> {
            Entry sourceEntry = requireFile(source);
            ensureParentDirectories(destination);
            Entry destinationEntry = entry(destination);
            if (destinationEntry != null && destinationEntry.directory()) {
                throw new AccessException("FILE_NOT_REGULAR", "Path is not a regular file");
            }
            upsertFile(destination, sourceEntry.content());
            return destination;
        });
    }

    @Override
    public synchronized String move(String sourcePath, String destinationPath) throws IOException {
        ensureOpen();
        String source = entryPath(sourcePath);
        String destination = entryPath(destinationPath);
        ensureDifferent(source, destination);
        return transaction("move", () -> {
            Entry sourceEntry = requireFile(source);
            ensureParentDirectories(destination);
            Entry destinationEntry = entry(destination);
            if (destinationEntry != null && destinationEntry.directory()) {
                throw new AccessException("FILE_NOT_REGULAR", "Path is not a regular file");
            }
            upsertFile(destination, sourceEntry.content());
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + ManagedFlowFileStoreContract.FILES_TABLE + " WHERE path = ?")) {
                statement.setString(1, source);
                statement.executeUpdate();
            }
            markMutation();
            return destination;
        });
    }

    @Override
    public synchronized List<String> list(String path) throws IOException {
        ensureOpen();
        String normalized = normalize(path);
        Entry directory = entry(normalized);
        if (directory == null) {
            throw new AccessException("DIRECTORY_NOT_FOUND", "Directory does not exist");
        }
        if (!directory.directory()) {
            throw new AccessException("PATH_NOT_DIRECTORY", "Path is not a directory");
        }
        String prefix = normalized.isEmpty() ? "" : normalized + "/";
        List<String> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT path FROM " + ManagedFlowFileStoreContract.FILES_TABLE + " ORDER BY path");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String candidate = result.getString(1);
                if (!candidate.startsWith(prefix) || candidate.equals(normalized)) {
                    continue;
                }
                String child = candidate.substring(prefix.length());
                if (!child.isEmpty() && child.indexOf('/') < 0) {
                    entries.add(child);
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        return entries.stream().sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
    }

    @Override
    public synchronized String createDirectory(String path) throws IOException {
        ensureOpen();
        String normalized = entryPath(path);
        return transaction("mkdir", () -> {
            ensureDirectoryChain(normalized);
            return normalized;
        });
    }

    @Override
    public synchronized long size(String path) throws IOException {
        ensureOpen();
        return requireFile(entryPath(path)).content().length;
    }

    @Override
    public synchronized void quiesce() throws IOException {
        ensureLifecycleOpen();
        if (quiesced) {
            return;
        }
        verifyBinding();
        quiesced = true;
    }

    @Override
    public synchronized void resume() throws IOException {
        ensureLifecycleOpen();
        if (!quiesced) {
            return;
        }
        verifyBinding();
        quiesced = false;
    }

    @Override
    public synchronized void rebind(Path requestedPersistenceRoot) throws IOException {
        if (!quiesced) {
            throw new AccessException("FILE_REBIND_REQUIRES_QUIESCE",
                "Managed flow-file storage must be quiesced before rebind");
        }
        if (transactionState != TransactionState.IDLE && transactionState != TransactionState.FENCED) {
            throw new AccessException("FILE_REBIND_TRANSACTION_ACTIVE", "Managed flow-file storage has an active transaction");
        }
        Connection openedConnection = null;
        try {
            Binding binding = prepareRebindBinding(requestedPersistenceRoot);
            closeConnection();
            openedConnection = openDatabase(binding.databaseFile(), false);
            initialize(openedConnection, binding, false);
            assignBinding(binding, openedConnection);
            this.connection = openedConnection;
            this.closed = false;
            this.fenced = false;
            this.transactionState = TransactionState.IDLE;
            this.postCommitCleanupFailure = null;
            verifyBinding();
        } catch (IOException | SQLException | RuntimeException exception) {
            if (openedConnection != null) {
                try {
                    openedConnection.close();
                } catch (SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            AccessException failure = new AccessException("FILE_REBIND_FAILED",
                "Managed flow-file storage rebind failed", exception);
            fence(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed && connection == null) {
            return;
        }
        closed = true;
        quiesced = true;
        closeConnection();
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        ensureLifecycleOpen();
        verifyBinding();
    }

    private static Binding prepareBinding(Path trustedDataRoot, boolean createPersistenceRoot) throws IOException {
        Objects.requireNonNull(trustedDataRoot, "trustedDataRoot");
        Path normalizedDataRoot = trustedDataRoot.toAbsolutePath().normalize();
        validateDirectory(normalizedDataRoot, "ReSync Data Root Must Be A Regular Directory");
        Path canonicalDataRoot = normalizedDataRoot.toRealPath();
        Object dataRootFileKey = fileKey(normalizedDataRoot);
        Path candidateRoot = normalizedDataRoot.resolve(ROOT_DIRECTORY).normalize();
        if (Files.isSymbolicLink(candidateRoot)) {
            throw new IllegalArgumentException("Managed Flow File Root Cannot Be A Symbolic Link");
        }
        if (createPersistenceRoot) {
            Files.createDirectories(candidateRoot);
        }
        validateDirectory(candidateRoot, "Managed Flow File Root Must Be A Regular Directory");
        Path canonicalRoot = candidateRoot.toRealPath();
        if (!canonicalRoot.startsWith(canonicalDataRoot) || canonicalRoot.equals(canonicalDataRoot)) {
            throw new IllegalArgumentException("Managed Flow File Root Must Stay Inside The ReSync Data Root");
        }
        Object rootFileKey = fileKey(candidateRoot);
        Path candidateDatabase = candidateRoot.resolve(DATABASE_FILE).normalize();
        validateDatabasePath(candidateDatabase);
        return new Binding(normalizedDataRoot, canonicalDataRoot, dataRootFileKey, candidateRoot,
            canonicalRoot, rootFileKey, candidateDatabase);
    }

    private static Binding prepareRebindBinding(Path requestedRoot) throws IOException {
        Objects.requireNonNull(requestedRoot, "persistenceRoot");
        Path normalizedRoot = requestedRoot.toAbsolutePath().normalize();
        validateDirectory(normalizedRoot, "Managed Flow File Root Must Be A Regular Directory");
        Path normalizedDataRoot = normalizedRoot.getParent();
        if (normalizedDataRoot == null) {
            throw new IllegalArgumentException("Managed Flow File Root Must Have A Data Root Parent");
        }
        Path canonicalDataRoot = normalizedDataRoot.toRealPath();
        Path canonicalRoot = normalizedRoot.toRealPath();
        if (!canonicalRoot.startsWith(canonicalDataRoot) || canonicalRoot.equals(canonicalDataRoot)) {
            throw new IllegalArgumentException("Managed Flow File Root Must Stay Inside The ReSync Data Root");
        }
        Path database = normalizedRoot.resolve(DATABASE_FILE).normalize();
        validateExistingDatabasePath(database);
        return new Binding(normalizedDataRoot, canonicalDataRoot, fileKey(normalizedDataRoot), normalizedRoot,
            canonicalRoot, fileKey(normalizedRoot), database);
    }

    private static void validateDirectory(Path path, String message) throws IOException {
        MigrationPaths.requireDirectory(path, message);
    }

    private static void validateDatabasePath(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
            || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("Managed Flow File Database Must Be A Regular File");
        }
        MigrationPaths.requirePath(path, "managedFlowFileDatabase");
    }

    private static void validateExistingDatabasePath(Path path) throws IOException {
        validateDatabasePath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed Flow File Database Must Exist For Rebind");
        }
    }

    private static Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static Connection openDatabase(Path database, boolean create) throws SQLException {
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + database, sqliteProperties(create));
        try {
            if (!supportsNoFollow(opened)) {
                throw new SQLException("SQLite NOFOLLOW open mode is unavailable");
            }
            return opened;
        } catch (SQLException exception) {
            try {
                opened.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static Properties sqliteProperties(boolean create) throws SQLException {
        Properties properties = new Properties();
        int openMode = SQLITE_OPEN_READWRITE | SQLITE_OPEN_URI
            | SQLITE_OPEN_PRIVATECACHE | SQLITE_OPEN_NOFOLLOW;
        if (create) {
            openMode |= SQLITE_OPEN_CREATE;
        }
        properties.setProperty("open_mode", Integer.toString(openMode));
        return properties;
    }

    private static boolean supportsNoFollow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sqlite_version()")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                return versionAtLeast(result.getString(1), 3, 31, 0);
            }
        }
    }

    private static boolean versionAtLeast(String version, int requiredMajor, int requiredMinor, int requiredPatch) {
        String[] segments = version == null ? new String[0] : version.split("\\.");
        int major = versionSegment(segments, 0);
        int minor = versionSegment(segments, 1);
        int patch = versionSegment(segments, 2);
        return major > requiredMajor || major == requiredMajor && (minor > requiredMinor
            || minor == requiredMinor && patch >= requiredPatch);
    }

    private static int versionSegment(String[] segments, int index) {
        if (index >= segments.length) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char character : segments[index].toCharArray()) {
            if (!Character.isDigit(character)) {
                break;
            }
            digits.append(character);
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    private void initialize(Connection connection, Binding binding, boolean freshDatabase) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = DELETE");
            statement.execute("PRAGMA synchronous = FULL");
            if (freshDatabase) {
                statement.execute(CREATE_METADATA_TABLE);
                statement.execute(CREATE_TABLE);
            }
        }
        if (freshDatabase) {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + ManagedFlowFileStoreContract.METADATA_TABLE
                    + "(id, schema_id, format_version, writer_id, writer_version, origin, contract_version, install_identity, "
                    + "source_snapshot_identity, source_install_identity, source_archive_identity, migration_id, "
                    + "completion_authority_hash, completion_hash) "
                    + "VALUES(1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, ManagedFlowFileStoreContract.SCHEMA);
                statement.setInt(2, ManagedFlowFileStoreContract.FORMAT_VERSION);
                statement.setString(3, ManagedFlowFileStoreContract.OWNER);
                statement.setInt(4, ManagedFlowFileStoreContract.WRITER_VERSION);
                statement.setString(5, ManagedFlowFileMigrationContract.Origin.FRESH_INSTALL.wireValue());
                statement.setInt(6, ManagedFlowFileStoreContract.CONTRACT_VERSION);
                statement.setString(7, requestedInstallIdentity == null ? UUID.randomUUID().toString() : requestedInstallIdentity);
                statement.setNull(8, java.sql.Types.VARCHAR);
                statement.setNull(9, java.sql.Types.VARCHAR);
                statement.setNull(10, java.sql.Types.VARCHAR);
                statement.setNull(11, java.sql.Types.VARCHAR);
                statement.setNull(12, java.sql.Types.VARCHAR);
                statement.setNull(13, java.sql.Types.VARCHAR);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + ManagedFlowFileStoreContract.FILES_TABLE + "(path, kind, content) VALUES('', ?, X'')")) {
                statement.setString(1, DIRECTORY_KIND);
                statement.executeUpdate();
            }
        }
        validateSchemaShape(connection);
        ManagedFlowFileMigrationContract.StoreMetadata metadata = readMetadata(connection);
        if (requestedInstallIdentity != null && !requestedInstallIdentity.equals(metadata.installIdentity())) {
            throw new SQLException("Managed flow-file store install identity does not match the requested installation");
        }
        if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.FRESH_INSTALL) {
            ManagedFlowFileMigrationAuthority.rejectFreshMigrationRecords(binding.persistenceRoot());
        } else if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING) {
            ManagedFlowFileMigrationAuthority.admitPending(
                connection, binding.databaseFile(), binding.persistenceRoot(), metadata);
            metadata = readMetadata(connection);
        } else if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED) {
            ManagedFlowFileMigrationAuthority.validateAdmitted(binding.persistenceRoot(), metadata);
        } else {
            throw new SQLException("Managed flow-file store origin is unsupported");
        }
        if (requestedInstallIdentity != null && !requestedInstallIdentity.equals(metadata.installIdentity())) {
            throw new SQLException("Managed flow-file store install identity changed during admission");
        }
        this.storeMetadata = metadata;
    }

    static void validateSchemaShape(Connection connection) throws SQLException {
        validateColumns(connection, ManagedFlowFileStoreContract.FILES_TABLE, FILE_COLUMNS);
        validateColumns(connection, ManagedFlowFileStoreContract.METADATA_TABLE, METADATA_COLUMNS);
        validateTableSql(connection, ManagedFlowFileStoreContract.FILES_TABLE, CANONICAL_FILES_SQL);
        validateTableSql(connection, ManagedFlowFileStoreContract.METADATA_TABLE, CANONICAL_METADATA_SQL);
        validateSchemaObjects(connection);
        if (countRows(connection, ManagedFlowFileStoreContract.METADATA_TABLE, null) != 1) {
            throw new SQLException("Managed flow-file metadata must contain exactly one row");
        }
        if (countRows(connection, ManagedFlowFileStoreContract.FILES_TABLE, "path = ''") != 1) {
            throw new SQLException("Managed flow-file database must contain exactly one root row");
        }
        String query = "SELECT kind, content FROM " + ManagedFlowFileStoreContract.FILES_TABLE + " WHERE path = ''";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            if (!result.next() || !DIRECTORY_KIND.equals(result.getString(1))) {
                throw new SQLException("Managed flow-file database root is not a directory");
            }
            byte[] content = result.getBytes(2);
            if (content == null || content.length != 0 || result.next()) {
                throw new SQLException("Managed flow-file database root content is invalid");
            }
        }
    }

    private static void validateColumns(Connection connection, String table, List<ColumnSpec> expected)
        throws SQLException {
        List<ColumnSpec> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_xinfo(" + table + ")");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                actual.add(new ColumnSpec(result.getString("name"), result.getString("type"),
                    result.getInt("notnull") != 0, result.getInt("pk") != 0));
            }
        }
        if (!expected.equals(actual)) {
            throw new SQLException("Managed flow-file table shape is not supported: " + table);
        }
    }

    private static void validateTableSql(Connection connection, String table, String expectedSql)
        throws SQLException {
        String sql;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Managed flow-file table is missing: " + table);
                }
                sql = result.getString(1);
            }
        }
        if (!normalizeSql(sql).equals(expectedSql)) {
            throw new SQLException("Managed flow-file table definition is not canonical: " + table);
        }
    }

    private static void validateSchemaObjects(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        Set<String> indexes = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String type = result.getString(1);
                String name = result.getString(2);
                String table = result.getString(3);
                String sql = result.getString(4);
                if ("table".equals(type)
                    && Set.of(ManagedFlowFileStoreContract.FILES_TABLE, ManagedFlowFileStoreContract.METADATA_TABLE).contains(name)) {
                    tables.add(name);
                } else if ("index".equals(type) && FILES_PRIMARY_KEY_INDEX.equals(name)
                    && ManagedFlowFileStoreContract.FILES_TABLE.equals(table) && sql == null) {
                    indexes.add(name);
                } else {
                    throw new SQLException("Managed flow-file database contains an unsupported SQLite schema object: " + name);
                }
            }
        }
        if (!tables.equals(Set.of(ManagedFlowFileStoreContract.FILES_TABLE, ManagedFlowFileStoreContract.METADATA_TABLE))
            || !indexes.equals(Set.of(FILES_PRIMARY_KEY_INDEX))
            || tables.size() + indexes.size() != 3) {
            throw new SQLException("Managed flow-file database SQLite schema objects are not canonical");
        }
    }

    private static String normalizeSql(String sql) {
        String value = Objects.requireNonNull(sql, "sql");
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            if (current == '\'') {
                index = appendQuoted(normalized, value, index, '\'', '\'');
                continue;
            }
            if (current == '"') {
                index = appendQuoted(normalized, value, index, '"', '"');
                continue;
            }
            if (current == '`') {
                index = appendQuoted(normalized, value, index, '`', '`');
                continue;
            }
            if (current == '[') {
                index = appendBracketed(normalized, value, index);
                continue;
            }
            normalized.append(Character.toUpperCase(current));
        }
        return normalized.toString();
    }

    private static int appendQuoted(StringBuilder normalized, String value, int start,
                                    char delimiter, char escapedDelimiter) {
        normalized.append(delimiter);
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            normalized.append(current);
            if (current == escapedDelimiter && index + 1 < value.length()
                && value.charAt(index + 1) == escapedDelimiter) {
                normalized.append(value.charAt(++index));
            } else if (current == delimiter) {
                return index;
            }
            index++;
        }
        return value.length() - 1;
    }

    private static int appendBracketed(StringBuilder normalized, String value, int start) {
        normalized.append('[');
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            normalized.append(current);
            if (current == ']') {
                return index;
            }
            index++;
        }
        return value.length() - 1;
    }

    private static int countRows(Connection connection, String table, String predicate) throws SQLException {
        String query = "SELECT COUNT(*) FROM " + table + (predicate == null ? "" : " WHERE " + predicate);
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Managed flow-file row count is unavailable: " + table);
            }
            return result.getInt(1);
        }
    }

    static ManagedFlowFileMigrationContract.StoreMetadata readMetadata(Connection connection) throws SQLException {
        String query = "SELECT "
            + ManagedFlowFileStoreContract.METADATA_SCHEMA_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_FORMAT_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_WRITER_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_WRITER_VERSION_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_ORIGIN_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_CONTRACT_VERSION_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_INSTALL_IDENTITY_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_SOURCE_SNAPSHOT_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_SOURCE_INSTALL_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_SOURCE_ARCHIVE_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_MIGRATION_ID_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_COMPLETION_AUTHORITY_HASH_COLUMN + ", "
            + ManagedFlowFileStoreContract.METADATA_COMPLETION_HASH_COLUMN
            + " FROM " + ManagedFlowFileStoreContract.METADATA_TABLE + " WHERE id = 1";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Managed flow-file store metadata is missing or unsupported");
            }
            try {
                String schema = result.getString(1);
                int format = result.getInt(2);
                String writer = result.getString(3);
                int writerVersion = result.getInt(4);
                String origin = result.getString(5);
                int contractVersion = result.getInt(6);
                String installIdentity = result.getString(7);
                if (!ManagedFlowFileStoreContract.compatible(schema, format, writer)
                    || writerVersion != ManagedFlowFileStoreContract.WRITER_VERSION
                    || contractVersion != ManagedFlowFileStoreContract.CONTRACT_VERSION) {
                    throw new SQLException("Managed flow-file store metadata is missing or unsupported");
                }
                return new ManagedFlowFileMigrationContract.StoreMetadata(
                    schema, format, writer, writerVersion,
                    ManagedFlowFileMigrationContract.Origin.parse(origin), contractVersion,
                    installIdentity, result.getString(8), result.getString(9), result.getString(10),
                    result.getString(11), result.getString(12), result.getString(13));
            } catch (IllegalArgumentException exception) {
                throw new SQLException("Managed flow-file store metadata is invalid", exception);
            }
        }
    }

    private void assignBinding(Binding binding, Connection openedConnection) throws IOException {
        this.dataRoot = binding.dataRoot();
        this.dataCanonicalRoot = binding.canonicalDataRoot();
        this.dataRootFileKey = binding.dataRootFileKey();
        this.persistenceRoot = binding.persistenceRoot();
        this.canonicalPersistenceRoot = binding.canonicalPersistenceRoot();
        this.persistenceRootFileKey = binding.persistenceRootFileKey();
        this.databaseFile = binding.databaseFile();
        this.canonicalDatabaseFile = databaseFile.toRealPath();
        this.databaseFileKey = fileKey(databaseFile);
        if (openedConnection == null) {
            throw new IOException("Managed flow-file database connection is unavailable");
        }
    }

    private List<Path> sidecars(Path database) {
        return List.of(database, database.resolveSibling(DATABASE_FILE + "-wal"),
            database.resolveSibling(DATABASE_FILE + "-shm"), database.resolveSibling(DATABASE_FILE + "-journal"));
    }

    private String entryPath(String path) throws AccessException {
        String normalized = normalize(path);
        if (normalized.isEmpty()) {
            throw new AccessException("FILE_PATH_INVALID",
                "File path must identify an entry below the managed flow-file root");
        }
        return normalized;
    }

    private void ensureDifferent(String source, String destination) throws AccessException {
        if (source.equals(destination)) {
            throw new AccessException("FILE_PATH_INVALID", "Source and destination must differ");
        }
    }

    private void ensureParentDirectories(String path) throws IOException, SQLException, AccessException {
        int separator = path.lastIndexOf('/');
        if (separator >= 0) {
            ensureDirectoryChain(path.substring(0, separator));
        }
    }

    private void ensureDirectoryChain(String path) throws IOException, SQLException, AccessException {
        if (path.isEmpty()) {
            return;
        }
        String[] segments = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(segment);
            String candidate = current.toString();
            Entry existing = entry(candidate);
            if (existing == null) {
                insertDirectory(candidate);
            } else if (!existing.directory()) {
                throw new AccessException("PATH_NOT_DIRECTORY", "Path is not a directory");
            }
        }
    }

    private Entry requireFile(String path) throws IOException {
        Entry existing = entry(path);
        if (existing == null) {
            throw new AccessException("FILE_NOT_FOUND", "File does not exist");
        }
        if (existing.directory()) {
            throw new AccessException("FILE_NOT_REGULAR", "Path is not a regular file");
        }
        return existing;
    }

    private Entry entry(String path) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT kind, content FROM " + ManagedFlowFileStoreContract.FILES_TABLE + " WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Entry(DIRECTORY_KIND.equals(result.getString(1)), result.getBytes(2));
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private boolean hasChildren(String path) throws IOException {
        String prefix = path + "/";
        try (PreparedStatement statement = connection.prepareStatement("SELECT path FROM " + ManagedFlowFileStoreContract.FILES_TABLE);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (result.getString(1).startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private void insertDirectory(String path) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + ManagedFlowFileStoreContract.FILES_TABLE + "(path, kind, content) VALUES(?, ?, X'')")) {
            statement.setString(1, path);
            statement.setString(2, DIRECTORY_KIND);
            statement.executeUpdate();
        }
        markMutation();
    }

    private void insertFile(String path, byte[] content) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + ManagedFlowFileStoreContract.FILES_TABLE + "(path, kind, content) VALUES(?, ?, ?)")) {
            statement.setString(1, path);
            statement.setString(2, FILE_KIND);
            statement.setBytes(3, content);
            statement.executeUpdate();
        }
        markMutation();
    }

    private void updateFile(String path, byte[] content) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + ManagedFlowFileStoreContract.FILES_TABLE + " SET content = ? WHERE path = ?")) {
            statement.setBytes(1, content);
            statement.setString(2, path);
            statement.executeUpdate();
        }
        markMutation();
    }

    private void upsertFile(String path, byte[] content) throws IOException, SQLException, AccessException {
        Entry existing = entry(path);
        if (existing == null) {
            insertFile(path, content);
        } else if (existing.directory()) {
            throw new AccessException("FILE_NOT_REGULAR", "Path is not a regular file");
        } else {
            updateFile(path, content);
        }
    }

    private void markMutation() throws IOException {
        mutationCount++;
        failureInjector.afterMutation(activeOperation, mutationCount);
    }

    private <T> T transaction(String operation, Transaction<T> work) throws IOException {
        ensureOpen();
        activeOperation = operation;
        mutationCount = 0;
        transactionState = TransactionState.STARTING;
        try {
            connection.setAutoCommit(false);
            transactionState = TransactionState.ACTIVE;
        } catch (Throwable failure) {
            fence(transactionFailure("FILE_TRANSACTION_BEGIN_FAILED", failure));
            throw transactionFailure("FILE_TRANSACTION_BEGIN_FAILED", failure);
        }
        T result;
        try {
            result = work.run();
            failureInjector.beforeCommit(operation);
        } catch (Throwable failure) {
            abort(operation, failure);
            rethrow(failure);
            return null;
        }
        transactionState = TransactionState.COMMITTING;
        try {
            connection.commit();
        } catch (Throwable failure) {
            AccessException ambiguous = transactionFailure("FILE_TRANSACTION_COMMIT_AMBIGUOUS", failure);
            fence(ambiguous);
            throw ambiguous;
        }
        transactionState = TransactionState.COMMITTED;
        try {
            failureInjector.beforeAutoCommitReset(operation);
            connection.setAutoCommit(true);
        } catch (Throwable cleanupFailure) {
            postCommitCleanupFailure = asIOException(cleanupFailure);
            fence(postCommitCleanupFailure);
            clearTransaction();
            return result;
        }
        transactionState = TransactionState.IDLE;
        clearTransaction();
        return result;
    }

    private void abort(String operation, Throwable originalFailure) throws IOException {
        transactionState = TransactionState.ROLLING_BACK;
        try {
            failureInjector.beforeRollback(operation);
            connection.rollback();
        } catch (Throwable rollbackFailure) {
            AccessException failure = transactionFailure("FILE_TRANSACTION_ROLLBACK_FAILED", rollbackFailure);
            failure.addSuppressed(asIOException(originalFailure));
            fence(failure);
            throw failure;
        }
        try {
            failureInjector.beforeAutoCommitReset(operation);
            connection.setAutoCommit(true);
        } catch (Throwable cleanupFailure) {
            AccessException failure = transactionFailure("FILE_TRANSACTION_CLEANUP_FAILED", cleanupFailure);
            failure.addSuppressed(asIOException(originalFailure));
            fence(failure);
            throw failure;
        }
        transactionState = TransactionState.IDLE;
        clearTransaction();
    }

    private void clearTransaction() {
        activeOperation = null;
        mutationCount = 0;
    }

    private void fence(Throwable cause) {
        Connection authority = connection;
        connection = null;
        fenced = true;
        closed = true;
        quiesced = true;
        transactionState = TransactionState.FENCED;
        clearTransaction();
        if (authority != null) {
            try {
                authority.close();
            } catch (SQLException closeFailure) {
                cause.addSuppressed(closeFailure);
            }
        }
    }

    private void closeConnection() throws IOException {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
            connection = null;
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private void ensureOpen() throws AccessException {
        ensureLifecycleOpen();
        if (quiesced) {
            throw new AccessException("FILE_CAPABILITY_QUIESCED", "Managed flow-file storage is quiesced");
        }
        verifyBinding();
    }

    private void ensureLifecycleOpen() throws AccessException {
        if (fenced) {
            throw new AccessException("FILE_CAPABILITY_FENCED", "Managed flow-file storage is fenced after an uncertain database operation");
        }
        if (closed) {
            throw new AccessException("FILE_CAPABILITY_CLOSED", "Managed flow-file capability is closed");
        }
    }

    private void verifyBinding() throws AccessException {
        try {
            verifyDirectory(dataRoot, dataCanonicalRoot, dataRootFileKey);
            verifyDirectory(persistenceRoot, canonicalPersistenceRoot, persistenceRootFileKey);
            if (Files.isSymbolicLink(databaseFile) || !Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Managed flow-file database is unavailable");
            }
            BasicFileAttributes attributes = Files.readAttributes(databaseFile, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (databaseFileKey != null && !Objects.equals(databaseFileKey, attributes.fileKey())) {
                throw new IOException("Managed flow-file database identity changed");
            }
            if (!databaseFile.toRealPath().equals(canonicalDatabaseFile)) {
                throw new IOException("Managed flow-file database path changed");
            }
            verifySchema(connection);
        } catch (IOException | RuntimeException exception) {
            AccessException failure = new AccessException("FILE_ROOT_CHANGED",
                "Managed flow-file persistence root changed or became unavailable", exception);
            fence(failure);
            throw failure;
        }
    }

    private static void verifyDirectory(Path path, Path canonicalPath, Object expectedFileKey) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed flow-file directory is unavailable");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (expectedFileKey != null && !Objects.equals(expectedFileKey, attributes.fileKey())) {
            throw new IOException("Managed flow-file directory identity changed");
        }
        if (!path.toRealPath().equals(canonicalPath)) {
            throw new IOException("Managed flow-file directory path changed");
        }
    }

    private void verifySchema(Connection connection) throws IOException {
        if (connection == null) {
            throw new IOException("Managed flow-file database connection is unavailable");
        }
        try {
            validateSchemaShape(connection);
            ManagedFlowFileMigrationContract.StoreMetadata current = readMetadata(connection);
            if (storeMetadata == null || !storeMetadata.equals(current)) {
                throw new IOException("Managed flow-file store metadata changed");
            }
        } catch (SQLException | RuntimeException exception) {
            throw new IOException("Managed flow-file store schema validation failed", exception);
        }
    }

    private AccessException transactionFailure(String code, Throwable cause) {
        return new AccessException(code, "Managed flow-file database transaction failed", cause);
    }

    private IOException asIOException(Throwable failure) {
        if (failure instanceof IOException exception) {
            return exception;
        }
        if (failure instanceof SQLException exception) {
            return databaseFailure(exception);
        }
        return new IOException("Managed flow-file database operation failed", failure);
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof SQLException exception) {
            throw new IOException("Managed flow-file database operation failed", exception);
        }
        throw new IOException("Managed flow-file database operation failed", failure);
    }

    private IOException databaseFailure(SQLException exception) {
        return new IOException("Managed flow-file database operation failed", exception);
    }

    interface FailureInjector {
        default void afterMutation(String operation, int mutationCount) throws IOException {
        }

        default void beforeCommit(String operation) throws IOException {
        }

        default void beforeRollback(String operation) throws SQLException {
        }

        default void beforeAutoCommitReset(String operation) throws SQLException {
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run() throws IOException, SQLException, AccessException;
    }

    private enum TransactionState {
        IDLE,
        STARTING,
        ACTIVE,
        COMMITTING,
        COMMITTED,
        ROLLING_BACK,
        FENCED
    }

    private record Binding(Path dataRoot, Path canonicalDataRoot, Object dataRootFileKey, Path persistenceRoot,
                           Path canonicalPersistenceRoot, Object persistenceRootFileKey, Path databaseFile) {
    }

    private record Entry(boolean directory, byte[] content) {
    }

    private record ColumnSpec(String name, String type, boolean notNull, boolean primaryKey) {
        private ColumnSpec {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type").toUpperCase(Locale.ROOT);
        }
    }
}
