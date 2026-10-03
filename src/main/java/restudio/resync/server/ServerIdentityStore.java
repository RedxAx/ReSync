package restudio.resync.server;

import restudio.resync.flow.identity.ServerId;
import restudio.resync.filesystem.windows.WindowsFileIdentity;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.FreshInstallInputs;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ProductionAuthoritySigner;
import restudio.resync.migration.ReSyncDataFixer;
import restudio.resync.storage.StorageSafety;
import restudio.resync.upgrade.AssetCoordinatorMigration.FreshRootAuthority;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ServerIdentityStore implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.install-identity";
    public static final String FILE_NAME = "server-id";
    public static final String INSTALL_SIGNAL_FILE = ".resync-install.signal";
    public static final String AUTHORITY_FILE = ".resync-install-authority.db";
    public static final String AUTHORITY_WAL_FILE = AUTHORITY_FILE + "-wal";
    public static final String AUTHORITY_SHM_FILE = AUTHORITY_FILE + "-shm";
    public static final String AUTHORITY_JOURNAL_FILE = AUTHORITY_FILE + "-journal";
    public static final String QUARANTINE_DIRECTORY = ".quarantine/server-identity-temps";
    private static final String INSTALL_SIGNAL_PREFIX = "format=1\nstate=FRESH_INSTALL\nroot=EMPTY\n";
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String AUTHORITY_QUARANTINE_DIRECTORY = ".quarantine/authority-bundle";
    private static final String BOOTSTRAP_MARKER_FILE = ".resync-install.bootstrap";
    private static final String BOOTSTRAP_MARKER_TEMP_FILE = ".resync-install.bootstrap.tmp";
    private static final String BOOTSTRAP_IDENTITY_TEMP_FILE = ".resync-install.server-id.tmp";
    private static final String BOOTSTRAP_SIGNAL_TEMP_FILE = ".resync-install.signal.tmp";
    private static final String LEGACY_IDENTITY_TEMP_FILE = "server-id.tmp";
    private static final String LEGACY_UNFORCED_IDENTITY_TEMP_FILE = "server-id-unforced.tmp";
    private static final String ATOMIC_TEMP_PREFIX = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final int UUID_LENGTH = 36;
    private static final String AUTHORITY_TABLE = "resync_install_identity_authority";
    private static final int AUTHORITY_APPLICATION_ID = 0x52534944;
    private static final int AUTHORITY_FORMAT_VERSION = 1;
    private static final int SQLITE_OPEN_READWRITE = 0x00000002;
    private static final int SQLITE_OPEN_URI = 0x00000040;
    private static final int SQLITE_OPEN_PRIVATECACHE = 0x00040000;
    private static final int SQLITE_OPEN_NOFOLLOW = 0x01000000;
    private static final String AUTHORITY_TABLE_SQL = "CREATE TABLE " + AUTHORITY_TABLE
        + " (singleton INTEGER PRIMARY KEY CHECK(singleton = 1),"
        + " server_id TEXT NOT NULL, install_signal TEXT NOT NULL,"
        + " install_signal_hash TEXT NOT NULL,"
        + " projection_state TEXT NOT NULL CHECK(projection_state IN ('PENDING', 'COMMITTED')))";
    private static final String CREATE_AUTHORITY_TABLE = AUTHORITY_TABLE_SQL;
    private static final List<AuthorityColumn> AUTHORITY_COLUMNS = List.of(
        new AuthorityColumn("singleton", "INTEGER", false, null, 1, 0),
        new AuthorityColumn("server_id", "TEXT", true, null, 0, 0),
        new AuthorityColumn("install_signal", "TEXT", true, null, 0, 0),
        new AuthorityColumn("install_signal_hash", "TEXT", true, null, 0, 0),
        new AuthorityColumn("projection_state", "TEXT", true, null, 0, 0)
    );
    private static final String[] AUTHORITY_FILE_NAMES = {
        AUTHORITY_FILE,
        AUTHORITY_WAL_FILE,
        AUTHORITY_SHM_FILE,
        AUTHORITY_JOURNAL_FILE
    };
    private static final Set<String> FRESH_INSTALL_ENTRIES = Set.of(
        "config.properties",
        "config.yml",
        "config.yaml",
        "resync.properties",
        FILE_NAME,
        INSTALL_SIGNAL_FILE,
        AUTHORITY_FILE,
        AUTHORITY_WAL_FILE,
        AUTHORITY_SHM_FILE,
        AUTHORITY_JOURNAL_FILE,
        QUARANTINE_CONTAINER
    );
    private static final Set<String> LEGACY_TRANSIENT_FILES = Set.of(
        BOOTSTRAP_MARKER_FILE,
        BOOTSTRAP_MARKER_TEMP_FILE,
        BOOTSTRAP_IDENTITY_TEMP_FILE,
        BOOTSTRAP_SIGNAL_TEMP_FILE,
        LEGACY_IDENTITY_TEMP_FILE,
        LEGACY_UNFORCED_IDENTITY_TEMP_FILE
    );
    private static final Map<Path, Object> AUTHORITY_OPEN_LOCKS = new ConcurrentHashMap<>();
    private final Path scopeRoot;
    private final Map<Path, AuthorityEvidence> authorityEvidence = new HashMap<>();
    private final ReentrantReadWriteLock authorityOperations = new ReentrantReadWriteLock(true);
    private volatile Binding binding;
    private volatile ProductionAuthorityKeyStore authorityKeyStore;
    private PersistenceState persistenceState = PersistenceState.OPEN;

    private ServerIdentityStore(Path scopeRoot, Binding binding) {
        this.scopeRoot = scopeRoot;
        this.binding = binding;
    }

    public static synchronized ServerIdentityStore open(Path path) throws IOException {
        return open(path, null, AuthorityFaultInjector.none(), ProjectionWriter.atomic());
    }

    static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority) throws IOException {
        return open(path, freshAuthority, null, AuthorityFaultInjector.none(), ProjectionWriter.atomic());
    }

    static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority,
                                                  ServerId configuredServerId) throws IOException {
        return open(path, freshAuthority, configuredServerId, AuthorityFaultInjector.none(), ProjectionWriter.atomic());
    }

    static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority,
                                                  ServerId configuredServerId, FreshInstallInitializer initializer) throws IOException {
        return open(path, freshAuthority, configuredServerId, AuthorityFaultInjector.none(), ProjectionWriter.atomic(), initializer);
    }

    static synchronized ServerIdentityStore open(Path path, AuthorityFaultInjector faultInjector) throws IOException {
        return open(path, null, faultInjector, ProjectionWriter.atomic());
    }

    static synchronized ServerIdentityStore open(Path path, AuthorityFaultInjector faultInjector,
                                                  ProjectionWriter projectionWriter) throws IOException {
        return open(path, null, faultInjector, projectionWriter);
    }

    static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority,
                                                  AuthorityFaultInjector faultInjector,
                                                  ProjectionWriter projectionWriter) throws IOException {
        return open(path, freshAuthority, null, faultInjector, projectionWriter);
    }

    private static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority,
                                                          ServerId configuredServerId,
                                                          AuthorityFaultInjector faultInjector,
                                                          ProjectionWriter projectionWriter) throws IOException {
        return open(path, freshAuthority, configuredServerId, faultInjector, projectionWriter, root -> {});
    }

    private static synchronized ServerIdentityStore open(Path path, FreshRootAuthority freshAuthority,
                                                          ServerId configuredServerId, AuthorityFaultInjector faultInjector,
                                                          ProjectionWriter projectionWriter, FreshInstallInitializer initializer) throws IOException {
        Path normalized = MigrationPaths.requirePath(Objects.requireNonNull(path, "Server Identity Path Is Required"), FILE_NAME);
        Path dataRoot = normalized.getParent();
        if (dataRoot == null) {
            throw new IOException("Server Identity Path Has No Data Root: " + normalized);
        }
        AuthorityFaultInjector injector = Objects.requireNonNull(faultInjector, "faultInjector");
        ProjectionWriter writer = Objects.requireNonNull(projectionWriter, "projectionWriter");
        FreshInstallInitializer preparation = Objects.requireNonNull(initializer, "initializer");
        ensureDataRoot(dataRoot);
        recoverInterruptedArtifacts(dataRoot);
        MigrationPaths.ValidatedRoot authorityRoot = MigrationPaths.requireValidatedRoot(dataRoot,
            "server identity data root");
        Path signal = dataRoot.resolve(INSTALL_SIGNAL_FILE).toAbsolutePath().normalize();
        Optional<AuthorityRecord> authority = readAuthority(authorityRoot, injector);
        if (authority.isPresent()) {
            AuthorityRecord record = authority.get();
            requireConfiguredIdentity(configuredServerId, record.serverId());
            ProjectionPresence projection = inspectProjection(normalized, signal, record);
            if (!projection.complete()) {
                projectPair(dataRoot, record, projection, injector, writer);
            }
            if (record.state() == ProjectionState.PENDING) {
                preparation.prepare(dataRoot);
                finalizeAuthority(authorityRoot, record, injector);
                return new ServerIdentityStore(dataRoot, new Binding(normalized, record.serverId(), true));
            }
            return new ServerIdentityStore(dataRoot, new Binding(normalized, record.serverId(), false));
        }

        boolean identityExists = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
        boolean signalExists = Files.exists(signal, LinkOption.NOFOLLOW_LINKS);
        if (identityExists || signalExists) {
            if (!identityExists || !signalExists) {
                throw new IOException("ReSync Install Identity Pair Is Incomplete: " + dataRoot);
            }
            Pair pair = readPair(normalized, signal);
            requireConfiguredIdentity(configuredServerId, pair.serverId());
            writeAuthority(authorityRoot, AuthorityRecord.committed(pair.serverId(), pair.signal()), injector);
            return new ServerIdentityStore(dataRoot, new Binding(normalized, pair.serverId(), false));
        }
        if (freshAuthority == null && !isFreshInstallRoot(dataRoot)) {
            throw new IOException("ReSync Server Identity Is Missing From An Existing Installation: " + normalized);
        }
        if (freshAuthority != null) {
            requireFreshAuthorityRoot(dataRoot, freshAuthority);
        }
        ServerId created = configuredServerId == null ? ServerId.random() : configuredServerId;
        AuthorityRecord pending = AuthorityRecord.pending(created);
        writeAuthority(authorityRoot, pending, injector);
        ProjectionPresence projection = inspectProjection(normalized, signal, pending);
        projectPair(dataRoot, pending, projection, injector, writer);
        preparation.prepare(dataRoot);
        finalizeAuthority(authorityRoot, pending, injector);
        return new ServerIdentityStore(dataRoot, new Binding(normalized, created, true));
    }

    private static void requireConfiguredIdentity(ServerId configured, ServerId durable) throws IOException {
        if (configured != null && !configured.equals(durable)) {
            throw new IOException("Configured ReSync Server Identity Does Not Match Durable Authority");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    public Binding binding() {
        return binding;
    }

    public Path path() {
        Binding current = binding;
        return current.path();
    }

    public ServerId serverId() {
        Binding current = binding;
        return current.serverId();
    }

    public synchronized ProductionAuthoritySigner productionAuthoritySigner() {
        return productionAuthorityKeyStore();
    }

    synchronized ProductionAuthorityKeyStore productionAuthorityKeyStore() {
        ProductionAuthorityKeyStore current = authorityKeyStore;
        if (current == null) {
            current = new ProductionAuthorityKeyStore(this);
            authorityKeyStore = current;
        }
        return current;
    }

    <T> T withProductionAuthority(ProductionAuthorityOperation<T> operation) throws IOException {
        var lease = authorityOperations.readLock();
        lease.lock();
        try {
            return Objects.requireNonNull(operation, "operation").execute(productionAuthorityKeyStore());
        } finally {
            lease.unlock();
        }
    }

    public boolean freshInstall() {
        Binding current = binding;
        return current.freshInstall();
    }

    public Path installSignalPath() {
        Binding current = binding;
        return current.installSignalPath();
    }

    @Override
    public synchronized Path root() {
        Binding current = binding;
        return current.path();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        Binding current = binding;
        return ownedPaths(current).contains(candidate) || ownsEvidencePath(current, candidate);
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        PersistenceOwnershipIndex.Builder builder = PersistenceOwnershipIndex.builder()
            .exact(context.participantRootRelative());
        for (String name : ownedFileNames()) {
            if (!name.equals(FILE_NAME) && !name.equals(QUARANTINE_CONTAINER)
                && !name.equals(QUARANTINE_DIRECTORY) && !LEGACY_TRANSIENT_FILES.contains(name)) {
                builder.exact(context.relativeToSource(context.participantRoot().resolveSibling(name)));
            }
        }
        Path dataRoot = context.participantRoot().getParent();
        if (dataRoot == null) {
            throw new IllegalArgumentException("Server Identity Participant Root Has No Data Root");
        }
        String dataRootRelative = relativeToSource(context, dataRoot);
        String quarantineRelative = relativeToSource(context,
            dataRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize());
        builder.exact(relativeToSource(context,
                dataRoot.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize()))
            .exact(quarantineRelative)
            .directChildAtomicTemp(dataRootRelative)
            .directChildAtomicTemp(quarantineRelative);
        for (String name : LEGACY_TRANSIENT_FILES) {
            builder.exact(relativeToSource(context, dataRoot.resolve(name).toAbsolutePath().normalize()));
            builder.exact(relativeToSource(context,
                dataRoot.resolve(QUARANTINE_DIRECTORY).resolve(name).toAbsolutePath().normalize()));
        }
        return builder.build();
    }

    @Override
    public synchronized void flush() throws IOException {
        authorityEvidence.clear();
        Binding current = binding;
        Path dataRoot = requireDataRoot(current);
        String before = scopeFingerprint(dataRoot, null, true);
        AuthorityRecord authority = activeAuthority(current);
        requireProjection(current, authority);
        checkpoint(authorityPath(dataRoot));
        if (!before.equals(scopeFingerprint(dataRoot, null, true))) {
            throw new IOException("Server Identity Persistence Changed During Flush");
        }
        requireStableAuthority(dataRoot, authority);
    }

    @Override
    public synchronized void quiesce() throws IOException {
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        flush();
        persistenceState = PersistenceState.QUIESCED;
    }

    @Override
    public synchronized void resume() throws IOException {
        healthCheck();
        persistenceState = PersistenceState.OPEN;
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        var lease = authorityOperations.writeLock();
        lease.lock();
        try {
            rebindAuthority(activeRoot);
        } finally {
            lease.unlock();
        }
    }

    private synchronized void rebindAuthority(Path activeRoot) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Server identity persistence must be quiesced before rebind");
        }
        Path candidateScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidateIdentity = candidateScope.resolve(FILE_NAME).toAbsolutePath().normalize();
        Path candidateSignal = candidateScope.resolve(INSTALL_SIGNAL_FILE).toAbsolutePath().normalize();
        if (!candidateIdentity.startsWith(candidateScope) || !candidateSignal.startsWith(candidateScope)) {
            throw new IOException("Server identity rebind escaped active root");
        }
        Binding current = binding;
        Path currentScope = requireDataRoot(current);
        AuthorityEvidence cachedCurrent = authorityEvidence.get(currentScope);
        String currentFingerprint;
        AuthorityRecord currentAuthority;
        if (cachedCurrent != null) {
            validateAuthorityArtifacts(currentScope);
            currentFingerprint = scopeFingerprint(currentScope, null, true, true);
            if (!cachedCurrent.fingerprint().equals(currentFingerprint)) {
                authorityEvidence.remove(currentScope);
                throw new IOException("Active Server Identity Changed Before Rebind");
            }
            currentAuthority = cachedCurrent.authority();
        } else {
            currentAuthority = activeAuthority(current);
            currentFingerprint = scopeFingerprint(currentScope, null, true, true);
        }
        requireProjection(current, currentAuthority);
        if (!currentAuthority.serverId().equals(current.serverId())) {
            throw new IOException("Active Server Identity Does Not Match Durable Authority");
        }
        boolean sameScope = currentScope.equals(candidateScope);
        recoverInterruptedArtifacts(candidateScope);
        AuthorityEvidence cachedCandidate = authorityEvidence.get(candidateScope);
        String candidateBefore = null;
        String cachedCandidateFingerprint = null;
        Optional<AuthorityRecord> authority;
        if (cachedCandidate != null) {
            validateAuthorityArtifacts(candidateScope);
            cachedCandidateFingerprint = scopeFingerprint(candidateScope, null, true, true);
            if (!cachedCandidate.fingerprint().equals(cachedCandidateFingerprint)) {
                authorityEvidence.remove(candidateScope);
                throw new IOException("Candidate Server Identity Changed Before Rebind");
            }
            authority = Optional.of(cachedCandidate.authority());
        } else {
            candidateBefore = scopeFingerprint(candidateScope, null, true);
            authority = readAuthority(candidateScope, AuthorityFaultInjector.none());
        }
        AuthorityRecord candidate;
        if (authority.isPresent()) {
            candidate = authority.get();
            if (!sameAuthorityIdentity(currentAuthority, candidate)) {
                throw new IOException("Candidate Server Identity Does Not Match The Active Durable Authority");
            }
            ProjectionPresence projection = inspectProjection(candidateIdentity, candidateSignal, candidate);
            if (!projection.complete()) {
                throw new IOException("Candidate Server Identity Pair Is Incomplete");
            }
            if (candidate.state() == ProjectionState.PENDING) {
                finalizeAuthority(candidateScope, candidate, AuthorityFaultInjector.none());
                candidate = candidate.committed();
            }
        } else {
            Pair pair = readPair(candidateIdentity, candidateSignal);
            candidate = AuthorityRecord.committed(pair.serverId(), pair.signal());
            if (!sameAuthorityIdentity(currentAuthority, candidate)) {
                throw new IOException("Candidate Server Identity Does Not Match The Active Durable Authority");
            }
            writeAuthority(candidateScope, candidate, AuthorityFaultInjector.none());
        }
        String candidateAuthorityBaseline = cachedCandidateFingerprint == null
            ? scopeFingerprint(candidateScope, null, true, true) : cachedCandidateFingerprint;
        String currentAfter = sameScope ? candidateAuthorityBaseline
            : scopeFingerprint(currentScope, null, true, true);
        String candidateAfter = sameScope ? currentAfter
            : scopeFingerprint(candidateScope, null, true, true);
        if (!currentFingerprint.equals(currentAfter)) {
            authorityEvidence.remove(currentScope);
            authorityEvidence.remove(candidateScope);
            throw new IOException("Active Server Identity Changed During Rebind");
        }
        if (candidateBefore != null && !candidateBefore.equals(scopeFingerprint(candidateScope, null, true))) {
            authorityEvidence.remove(candidateScope);
            throw new IOException("Candidate Server Identity Changed During Rebind");
        }
        if (!candidateAuthorityBaseline.equals(candidateAfter)) {
            authorityEvidence.remove(candidateScope);
            throw new IOException("Candidate Server Identity Changed During Rebind");
        }
        requireProjection(current, currentAuthority);
        authorityEvidence.put(currentScope, new AuthorityEvidence(currentAuthority, currentAfter));
        authorityEvidence.put(candidateScope, new AuthorityEvidence(candidate, candidateAfter));
        Binding next = new Binding(candidateIdentity, candidate.serverId(), false);
        binding = next;
        authorityKeyStore = null;
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        authorityEvidence.clear();
        Binding current = binding;
        Path dataRoot = requireDataRoot(current);
        String before = scopeFingerprint(dataRoot, null, true, true);
        AuthorityRecord authority = activeAuthority(current);
        requireProjection(current, authority);
        if (!before.equals(scopeFingerprint(dataRoot, null, true, true))) {
            throw new IOException("Server Identity Persistence Changed During Health Check");
        }
    }

    public synchronized boolean isQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    static Set<String> ownedFileNames() {
        return Set.of(FILE_NAME, INSTALL_SIGNAL_FILE, AUTHORITY_FILE, AUTHORITY_WAL_FILE,
            AUTHORITY_SHM_FILE, AUTHORITY_JOURNAL_FILE, QUARANTINE_CONTAINER, QUARANTINE_DIRECTORY,
            BOOTSTRAP_MARKER_FILE, BOOTSTRAP_MARKER_TEMP_FILE, BOOTSTRAP_IDENTITY_TEMP_FILE,
            BOOTSTRAP_SIGNAL_TEMP_FILE, LEGACY_IDENTITY_TEMP_FILE, LEGACY_UNFORCED_IDENTITY_TEMP_FILE);
    }

    static Optional<ServerId> durableAuthorityId(Path dataRoot) throws IOException {
        return readAuthority(dataRoot, AuthorityFaultInjector.none()).map(AuthorityRecord::serverId);
    }

    private AuthorityRecord activeAuthority(Binding current) throws IOException {
        Path dataRoot = requireDataRoot(current);
        AuthorityRecord authority = readAuthority(dataRoot, AuthorityFaultInjector.none())
            .orElseThrow(() -> new IOException("Durable Server Identity Authority Is Missing"));
        if (authority.state() != ProjectionState.COMMITTED) {
            throw new IOException("Durable Server Identity Authority Is Not Committed");
        }
        return authority;
    }

    private static boolean sameAuthorityIdentity(AuthorityRecord first, AuthorityRecord second) {
        return first.serverId().equals(second.serverId())
            && first.signal().equals(second.signal())
            && first.signalHash().equals(second.signalHash());
    }

    private void requireProjection(Binding current, AuthorityRecord authority) throws IOException {
        ProjectionPresence projection = inspectProjection(current.path(), current.installSignalPath(), authority);
        if (!projection.complete() || !authority.serverId().equals(current.serverId())) {
            throw new IOException("Active Server Identity Projection Does Not Match Durable Authority");
        }
    }

    private static void ensureDataRoot(Path dataRoot) throws IOException {
        if (!Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dataRoot);
        }
        MigrationPaths.requireDirectory(dataRoot, "server identity data root");
    }

    private static Path requireDataRoot(Binding current) throws IOException {
        Path dataRoot = current.path().getParent();
        if (dataRoot == null) {
            throw new IOException("Server Identity Path Has No Data Root");
        }
        return MigrationPaths.requireDirectory(dataRoot, "server identity data root");
    }

    private static String relativeToSource(PersistenceOwnershipContext context, Path path) {
        Path source = context.sourceRoot().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return source.equals(normalized) ? "" : context.relativeToSource(normalized);
    }

    private static boolean ownsEvidencePath(Binding current, Path candidate) {
        Path dataRoot = current.path().getParent();
        if (dataRoot == null || candidate.getParent() == null) {
            return false;
        }
        Path normalizedRoot = dataRoot.toAbsolutePath().normalize();
        Path quarantine = normalizedRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        Path parent = candidate.getParent();
        String name = candidate.getFileName().toString();
        return (parent.equals(normalizedRoot) || parent.equals(quarantine))
            && (LEGACY_TRANSIENT_FILES.contains(name) || isAtomicTempName(name));
    }

    private static void recoverInterruptedArtifacts(Path dataRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(dataRoot, "server identity data root");
        MigrationPaths.requireNoSymlinkTraversal(root, root);
        List<Path> evidence = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path path : entries.sorted(Comparator.comparing(candidate -> candidate.getFileName().toString())).toList()) {
                String name = path.getFileName().toString();
                if (name.equals(FILE_NAME) || name.equals(INSTALL_SIGNAL_FILE)) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        FileState state = readStableArtifactState(path, "Server Identity Projection");
                        if (!state.present()) {
                            throw new IOException("Server Identity Projection Disappeared During Recovery: " + path);
                        }
                    }
                } else if (isAuthorityArtifactName(name) || name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                } else if (LEGACY_TRANSIENT_FILES.contains(name) || isAtomicTempName(name)) {
                    FileState state = readStableArtifactState(path, "Server Identity Recovery Artifact");
                    if (!state.present()) {
                        throw new IOException("Server Identity Recovery Artifact Disappeared During Recovery: " + path);
                    }
                    evidence.add(path);
                } else if (isAmbiguousArtifactName(name)) {
                    throw new IOException("Server Identity Root Contains An Unknown Recovery Artifact: " + path);
                }
            }
        }
        validateQuarantine(root);
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (Files.exists(container, LinkOption.NOFOLLOW_LINKS)
            && Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            prepareQuarantine(root);
        }
        quarantineArtifacts(root, evidence);
    }

    private static void quarantineArtifacts(Path root, List<Path> evidence) throws IOException {
        if (evidence.isEmpty()) {
            return;
        }
        Path quarantine = prepareQuarantine(root);
        List<Path> ordered = evidence.stream()
            .sorted(Comparator.comparing(candidate -> candidate.getFileName().toString()))
            .toList();
        List<FileState> originalStates = new ArrayList<>(ordered.size());
        List<Path> destinations = new ArrayList<>(ordered.size());
        for (Path source : ordered) {
            FileState state = readStableArtifactState(source, "Server Identity Recovery Artifact");
            if (!state.present()) {
                throw new IOException("Server Identity Recovery Artifact Disappeared Before Quarantine: " + source);
            }
            originalStates.add(state);
            Path destination = quarantine.resolve(source.getFileName().toString()).toAbsolutePath().normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Server Identity Recovery Quarantine Target Collides: " + destination);
            }
            destinations.add(destination);
        }
        for (int index = 0; index < ordered.size(); index++) {
            Path source = ordered.get(index);
            Path destination = destinations.get(index);
            try {
                FileState before = readStableArtifactState(source, "Server Identity Recovery Artifact");
                if (!sameFileState(originalStates.get(index), before)) {
                    throw new IOException("Server Identity Recovery Artifact Changed Before Quarantine: " + source);
                }
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
                FileState moved = readStableArtifactState(destination, "Server Identity Quarantine Evidence");
                if (!moved.present() || !sameFileContent(before, moved)) {
                    throw new IOException("Server Identity Recovery Evidence Verification Failed: " + source);
                }
                if (readStableArtifactState(source, "Server Identity Recovery Artifact").present()) {
                    throw new IOException("Server Identity Recovery Artifact Remained After Quarantine: " + source);
                }
            } catch (IOException exception) {
                throw new IOException("Failed To Quarantine Server Identity Recovery Artifact: " + source, exception);
            }
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
        StorageSafety.forceDirectory(root);
        validateQuarantine(root);
    }

    private static Path prepareQuarantine(Path root) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, container);
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
            StorageSafety.forceDirectory(container);
            StorageSafety.forceDirectory(root);
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server Identity Quarantine Container Is Invalid: " + container);
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
            StorageSafety.forceDirectory(quarantine);
            StorageSafety.forceDirectory(container);
            StorageSafety.forceDirectory(root);
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server Identity Quarantine Root Is Invalid: " + quarantine);
        }
        validateQuarantine(root);
        return quarantine;
    }

    private static void validateQuarantine(Path root) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, container);
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server Identity Quarantine Container Is Invalid: " + container);
        }
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        Path authorityQuarantine = root.resolve(AUTHORITY_QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, authorityQuarantine);
        try (var entries = Files.list(container)) {
            for (Path path : entries.toList()) {
                if (path.equals(quarantine)) {
                    continue;
                }
                if (path.equals(authorityQuarantine)) {
                    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Authority Bundle Quarantine Root Is Invalid: " + path);
                    }
                    continue;
                }
                throw new IOException("Server Identity Quarantine Container Contains An Unknown Entry: " + path);
            }
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server Identity Quarantine Root Is Invalid: " + quarantine);
        }
        try (var entries = Files.list(quarantine)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!LEGACY_TRANSIENT_FILES.contains(name) && !isAtomicTempName(name)) {
                    throw new IOException("Server Identity Quarantine Contains An Unknown Entry: " + path);
                }
                requireRegularArtifact(path, "Server Identity Quarantine Evidence");
            }
        }
    }

    private static boolean isAuthorityArtifactName(String name) {
        for (String authorityName : AUTHORITY_FILE_NAMES) {
            if (authorityName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAmbiguousArtifactName(String name) {
        return name.startsWith(ATOMIC_TEMP_PREFIX)
            || (name.startsWith(FILE_NAME) && !name.equals(FILE_NAME))
            || (name.startsWith(INSTALL_SIGNAL_FILE) && !name.equals(INSTALL_SIGNAL_FILE))
            || name.startsWith(".resync-install.");
    }

    private static boolean isAtomicTempName(String name) {
        if (name == null || name.length() != ATOMIC_TEMP_PREFIX.length() + UUID_LENGTH + ATOMIC_TEMP_SUFFIX.length()
            || !name.startsWith(ATOMIC_TEMP_PREFIX) || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return false;
        }
        int uuidStart = ATOMIC_TEMP_PREFIX.length();
        for (int index = 0; index < UUID_LENGTH; index++) {
            char character = name.charAt(uuidStart + index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }

    private static void requireRegularArtifact(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " Must Be A Regular Non-Symbolic-Link File: " + path);
        }
    }

    private static FileState readStableArtifactState(Path path, String label) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, label);
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException(label + " Has No Parent Directory: " + normalized);
        }
        MigrationPaths.requireDirectory(parent, label + " Parent Directory");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalized);
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return FileState.absent();
        }
        if (!before.isRegularFile() || Files.isSymbolicLink(normalized)) {
            throw new IOException(label + " Must Be A Regular Non-Symbolic-Link File: " + normalized);
        }
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(before.size(), 8192));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
            bytes = output.toByteArray();
        } catch (NoSuchFileException exception) {
            throw new IOException(label + " Disappeared During Read: " + normalized, exception);
        }
        BasicFileAttributes after;
        try {
            after = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new IOException(label + " Disappeared During Read: " + normalized, exception);
        }
        if (!after.isRegularFile() || Files.isSymbolicLink(normalized)
            || !sameFileIdentity(before, after) || after.size() != bytes.length) {
            throw new IOException(label + " Changed During Read: " + normalized);
        }
        return new FileState(true, after.fileKey(), after.size(), after.creationTime(), after.lastModifiedTime(),
            bytes, StorageSafety.sha256(bytes));
    }

    private static FileState readStableAuthorityState(MigrationPaths.ValidatedRoot root, Path path, String label)
        throws IOException {
        Optional<BasicFileAttributes> inspectedBefore = root.inspectDirectRegularFile(path, label);
        if (inspectedBefore.isEmpty()) {
            return FileState.absent();
        }
        BasicFileAttributes before = inspectedBefore.get();
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(before.size(), 8192));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
            bytes = output.toByteArray();
        } catch (NoSuchFileException exception) {
            throw new IOException(label + " Disappeared During Read: " + path, exception);
        }
        Optional<BasicFileAttributes> inspectedAfter = root.inspectDirectRegularFile(path, label);
        if (inspectedAfter.isEmpty()) {
            throw new IOException(label + " Disappeared During Read: " + path);
        }
        BasicFileAttributes after = inspectedAfter.get();
        if (!sameFileIdentity(before, after) || after.size() != bytes.length) {
            throw new IOException(label + " Changed During Read: " + path);
        }
        return new FileState(true, after.fileKey(), after.size(), after.creationTime(), after.lastModifiedTime(),
            bytes, StorageSafety.sha256(bytes));
    }

    private static boolean sameFileIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return Objects.equals(first.fileKey(), second.fileKey())
            && first.size() == second.size()
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static boolean sameFileState(FileState first, FileState second) {
        if (first.present() != second.present()) {
            return false;
        }
        if (!first.present()) {
            return true;
        }
        return Objects.equals(first.fileKey(), second.fileKey())
            && first.size() == second.size()
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime())
            && first.contentHash().equals(second.contentHash())
            && Arrays.equals(first.bytes(), second.bytes());
    }

    private static boolean sameFileContent(FileState first, FileState second) {
        return first.present() && second.present()
            && first.size() == second.size()
            && first.contentHash().equals(second.contentHash())
            && Arrays.equals(first.bytes(), second.bytes());
    }

    private static AuthorityArtifacts validateAuthorityArtifacts(Path dataRoot) throws IOException {
        MigrationPaths.ValidatedRoot root = MigrationPaths.requireValidatedRoot(dataRoot, "server identity data root");
        return validateAuthorityArtifacts(root);
    }

    private static AuthorityArtifacts validateAuthorityArtifacts(MigrationPaths.ValidatedRoot root) throws IOException {
        List<Path> paths = new ArrayList<>(AUTHORITY_FILE_NAMES.length);
        for (String name : AUTHORITY_FILE_NAMES) {
            paths.add(root.path().resolve(name).toAbsolutePath().normalize());
        }
        List<Optional<BasicFileAttributes>> before = root.inspectDirectRegularFiles(paths,
            "Server Identity Authority Artifact");
        List<Optional<String>> contentHashes = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            Optional<BasicFileAttributes> attributes = before.get(index);
            contentHashes.add(attributes.isPresent()
                ? Optional.of(authorityArtifactHash(paths.get(index), attributes.get().size()))
                : Optional.empty());
        }
        List<Optional<BasicFileAttributes>> after = root.inspectDirectRegularFiles(paths,
            "Server Identity Authority Artifact");
        List<Optional<AuthorityArtifactIdentity>> identities = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            Optional<BasicFileAttributes> first = before.get(index);
            Optional<BasicFileAttributes> second = after.get(index);
            if (first.isPresent() != second.isPresent()
                || first.isPresent() && !sameFileIdentity(first.get(), second.orElseThrow())) {
                throw new IOException("Server Identity Authority Artifact Changed During Snapshot: "
                    + paths.get(index));
            }
            identities.add(second.isPresent()
                ? Optional.of(new AuthorityArtifactIdentity(second.get(), contentHashes.get(index).orElseThrow(),
                    authorityArtifactObjectIdentity(paths.get(index), second.get())))
                : Optional.empty());
        }
        if (identities.getFirst().isEmpty()) {
            for (int index = 1; index < AUTHORITY_FILE_NAMES.length; index++) {
                if (identities.get(index).isPresent()) {
                    throw new IOException("Server Identity Authority Sidecar Exists Without Database: " + root.path());
                }
            }
        }
        return new AuthorityArtifacts(root, paths, identities);
    }

    private static String authorityArtifactHash(Path path, long expectedSize) throws IOException {
        AuthorityArtifactContent content;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            content = authorityArtifactContent(channel);
        } catch (NoSuchFileException exception) {
            throw new IOException("Server Identity Authority Artifact Disappeared During Snapshot: " + path,
                exception);
        }
        if (content.size() != expectedSize) {
            throw new IOException("Server Identity Authority Artifact Changed During Snapshot: " + path);
        }
        return content.contentHash();
    }

    private static AuthorityArtifactContent authorityArtifactContent(FileChannel channel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
        long size = 0;
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (true) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            size += read;
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return new AuthorityArtifactContent(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static Object authorityArtifactObjectIdentity(Path path, BasicFileAttributes attributes)
        throws IOException {
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = WindowsFileIdentity.system().observe(path);
            if (!observation.isRegularFile() || observation.isReparsePoint()) {
                throw new IOException("Server Identity Authority Artifact Has No Stable Windows Identity: " + path);
            }
            return observation.identity();
        }
        if (attributes.fileKey() == null) {
            throw new IOException("Server Identity Authority Artifact Has No Stable File Identity: " + path);
        }
        return attributes.fileKey();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void requireSameAuthorityArtifacts(AuthorityArtifacts expected, AuthorityArtifacts actual,
                                                      String phase) throws IOException {
        if (!expected.root().path().equals(actual.root().path()) || !expected.paths().equals(actual.paths())
            || expected.identities().size() != actual.identities().size()) {
            throw new IOException("Server Identity Authority Artifact Set Changed " + phase);
        }
        for (int index = 0; index < expected.identities().size(); index++) {
            Optional<AuthorityArtifactIdentity> first = expected.identities().get(index);
            Optional<AuthorityArtifactIdentity> second = actual.identities().get(index);
            if (first.isPresent() != second.isPresent()
                || first.isPresent() && !first.get().sameSnapshot(second.orElseThrow())) {
                throw new IOException("Server Identity Authority Artifact Changed " + phase + ": "
                    + expected.paths().get(index));
            }
        }
    }

    private static void requireAuthorityArtifactsAfterOpen(AuthorityArtifacts before, AuthorityArtifacts after)
        throws IOException {
        if (!before.root().path().equals(after.root().path()) || !before.paths().equals(after.paths())
            || before.identities().size() != after.identities().size()) {
            throw new IOException("Server Identity Authority Artifact Set Changed During SQLite Open");
        }
        for (int index = 0; index < before.identities().size(); index++) {
            Optional<AuthorityArtifactIdentity> first = before.identities().get(index);
            Optional<AuthorityArtifactIdentity> second = after.identities().get(index);
            if (first.isPresent() && second.isPresent()) {
                if (!sameAuthorityArtifactAfterOpen(first.get().objectIdentity(), second.get().objectIdentity())) {
                    throw new IOException("Server Identity Authority Artifact Identity Changed During SQLite Open: "
                        + before.paths().get(index));
                }
                continue;
            }
            if (first.isPresent() == second.isPresent()) {
                continue;
            }
            boolean sqliteCreatedWalSidecar = first.isEmpty() && second.isPresent() && (index == 1 || index == 2);
            boolean sqliteRecoveredJournal = first.isPresent() && second.isEmpty() && index == 3;
            if (!sqliteCreatedWalSidecar && !sqliteRecoveredJournal) {
                throw new IOException("Server Identity Authority Artifact Presence Changed During SQLite Open: "
                    + before.paths().get(index));
            }
        }
    }

    static boolean sameAuthorityArtifactAfterOpen(Object firstObjectIdentity, Object secondObjectIdentity) {
        return firstObjectIdentity != null && secondObjectIdentity != null
            && Objects.equals(firstObjectIdentity, secondObjectIdentity);
    }

    private static Optional<AuthorityRecord> readAuthority(Path dataRoot, AuthorityFaultInjector faultInjector) throws IOException {
        return readAuthority(MigrationPaths.requireValidatedRoot(dataRoot, "server identity data root"), faultInjector);
    }

    private static Optional<AuthorityRecord> readAuthority(MigrationPaths.ValidatedRoot root,
                                                            AuthorityFaultInjector faultInjector) throws IOException {
        AuthorityArtifacts artifacts = validateAuthorityArtifacts(root);
        if (!artifacts.databasePresent()) {
            return Optional.empty();
        }
        try (Connection connection = openDatabase(artifacts, false, AuthorityOpenHook.none())) {
            AuthorityRecord record = readAuthority(connection);
            faultInjector.cut(AuthorityCut.AFTER_AUTHORITY_REOPEN);
            return Optional.ofNullable(record);
        } catch (SQLException exception) {
            throw sqlFailure("Failed To Reopen Server Identity Authority", exception);
        }
    }

    private static AuthorityRecord readAuthority(Connection connection) throws SQLException, IOException {
        if (inspectAuthoritySchema(connection) == AuthoritySchema.EMPTY) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT singleton, server_id, install_signal, install_signal_hash, projection_state "
                + "FROM " + AUTHORITY_TABLE)) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                int singleton = result.getInt(1);
                String serverId = result.getString(2);
                String signal = result.getString(3);
                String hash = result.getString(4);
                String state = result.getString(5);
                if (result.next() || singleton != 1) {
                    throw new IOException("Server Identity Authority Contains Multiple Rows");
                }
                try {
                    return AuthorityRecord.fromPersisted(serverId, signal, hash, state);
                } catch (RuntimeException exception) {
                    throw new IOException("Server Identity Authority Record Is Invalid", exception);
                }
            }
        }
    }

    private static AuthoritySchema inspectAuthoritySchema(Connection connection) throws SQLException, IOException {
        int applicationId = pragmaInt(connection, "application_id");
        int formatVersion = pragmaInt(connection, "user_version");
        int objectCount = 0;
        boolean expectedTable = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")) {
            while (result.next()) {
                objectCount++;
                if ("table".equals(result.getString(1))
                    && AUTHORITY_TABLE.equals(result.getString(2))
                    && AUTHORITY_TABLE.equals(result.getString(3))
                    && AUTHORITY_TABLE_SQL.equals(result.getString(4))) {
                    expectedTable = true;
                }
            }
        }
        if (objectCount == 0) {
            if (applicationId != 0 || formatVersion != 0) {
                throw new IOException("Server Identity Authority Has Metadata Without Its V1 Schema");
            }
            return AuthoritySchema.EMPTY;
        }
        if (applicationId != AUTHORITY_APPLICATION_ID || formatVersion != AUTHORITY_FORMAT_VERSION
            || objectCount != 1 || !expectedTable) {
            throw new IOException("Server Identity Authority Schema Is Not The Supported V1 Contract");
        }
        validateAuthorityColumns(connection);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA index_list('" + AUTHORITY_TABLE + "')")) {
            if (result.next()) {
                throw new IOException("Server Identity Authority Contains An Unexpected Index");
            }
        }
        return AuthoritySchema.V1;
    }

    private static int pragmaInt(Connection connection, String pragma) throws SQLException, IOException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            if (!result.next()) {
                throw new IOException("SQLite Authority Metadata Is Missing: " + pragma);
            }
            return result.getInt(1);
        }
    }

    private static void validateAuthorityColumns(Connection connection) throws SQLException, IOException {
        int index = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_xinfo('" + AUTHORITY_TABLE + "')")) {
            while (result.next()) {
                if (index >= AUTHORITY_COLUMNS.size()) {
                    throw new IOException("Server Identity Authority Contains Unexpected Columns");
                }
                AuthorityColumn expected = AUTHORITY_COLUMNS.get(index);
                if (result.getInt(1) != index
                    || !expected.name().equals(result.getString(2))
                    || !expected.type().equals(result.getString(3))
                    || expected.notNull() != (result.getInt(4) != 0)
                    || !Objects.equals(expected.defaultValue(), result.getString(5))
                    || expected.primaryKey() != result.getInt(6)
                    || expected.hidden() != result.getInt(7)) {
                    throw new IOException("Server Identity Authority Columns Drifted From V1 Contract");
                }
                index++;
            }
        }
        if (index != AUTHORITY_COLUMNS.size()) {
            throw new IOException("Server Identity Authority Is Missing V1 Columns");
        }
    }

    private static void writeAuthority(Path dataRoot, AuthorityRecord expected,
                                       AuthorityFaultInjector faultInjector) throws IOException {
        writeAuthority(MigrationPaths.requireValidatedRoot(dataRoot, "server identity data root"), expected,
            faultInjector);
    }

    private static void writeAuthority(MigrationPaths.ValidatedRoot root, AuthorityRecord expected,
                                       AuthorityFaultInjector faultInjector) throws IOException {
        AuthorityArtifacts artifacts = validateAuthorityArtifacts(root);
        try (Connection connection = openDatabase(artifacts, true, AuthorityOpenHook.none())) {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                if (inspectAuthoritySchema(connection) == AuthoritySchema.EMPTY) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("PRAGMA application_id = " + AUTHORITY_APPLICATION_ID);
                        statement.execute("PRAGMA user_version = " + AUTHORITY_FORMAT_VERSION);
                        statement.execute(CREATE_AUTHORITY_TABLE);
                    }
                    if (inspectAuthoritySchema(connection) != AuthoritySchema.V1) {
                        throw new IOException("Failed To Create The V1 Server Identity Authority Schema");
                    }
                }
                AuthorityRecord existing = readAuthority(connection);
                if (existing != null) {
                    if (!existing.equals(expected)) {
                        throw new IOException("Server Identity Authority Does Not Match Requested Generation");
                    }
                    connection.rollback();
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + AUTHORITY_TABLE
                        + " (singleton, server_id, install_signal, install_signal_hash, projection_state)"
                        + " VALUES (1, ?, ?, ?, ?)")) {
                    statement.setString(1, expected.serverId().canonicalText());
                    statement.setString(2, expected.signal());
                    statement.setString(3, expected.signalHash());
                    statement.setString(4, expected.state().name());
                    faultInjector.cut(AuthorityCut.BEFORE_AUTHORITY_WRITE);
                    statement.executeUpdate();
                    faultInjector.cut(AuthorityCut.AFTER_AUTHORITY_WRITE);
                }
                faultInjector.cut(AuthorityCut.BEFORE_AUTHORITY_COMMIT);
                connection.commit();
                committed = true;
                faultInjector.cut(AuthorityCut.AFTER_AUTHORITY_COMMIT);
            } catch (SQLException | IOException exception) {
                if (!committed) {
                    rollback(connection, exception);
                }
                if (exception instanceof IOException io) {
                    throw io;
                }
                throw sqlFailure("Failed To Commit Server Identity Authority", (SQLException) exception);
            }
        } catch (SQLException exception) {
            throw sqlFailure("Failed To Write Server Identity Authority", exception);
        }
    }

    private static void finalizeAuthority(Path dataRoot, AuthorityRecord expected,
                                          AuthorityFaultInjector faultInjector) throws IOException {
        finalizeAuthority(MigrationPaths.requireValidatedRoot(dataRoot, "server identity data root"), expected,
            faultInjector);
    }

    private static void finalizeAuthority(MigrationPaths.ValidatedRoot root, AuthorityRecord expected,
                                          AuthorityFaultInjector faultInjector) throws IOException {
        AuthorityArtifacts artifacts = validateAuthorityArtifacts(root);
        try (Connection connection = openDatabase(artifacts, false, AuthorityOpenHook.none())) {
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                AuthorityRecord existing = readAuthority(connection);
                if (existing == null || !existing.serverId().equals(expected.serverId())
                    || !existing.signal().equals(expected.signal())) {
                    throw new IOException("Server Identity Authority Generation Changed During Projection");
                }
                if (existing.state() == ProjectionState.COMMITTED) {
                    connection.rollback();
                    return;
                }
                faultInjector.cut(AuthorityCut.BEFORE_PROJECTION_COMMIT);
                try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + AUTHORITY_TABLE + " SET projection_state = 'COMMITTED' WHERE singleton = 1")) {
                    statement.executeUpdate();
                }
                connection.commit();
                committed = true;
                faultInjector.cut(AuthorityCut.AFTER_PROJECTION_COMMIT);
            } catch (SQLException | IOException exception) {
                if (!committed) {
                    rollback(connection, exception);
                }
                if (exception instanceof IOException io) {
                    throw io;
                }
                throw sqlFailure("Failed To Commit Server Identity Projection", (SQLException) exception);
            }
        } catch (SQLException exception) {
            throw sqlFailure("Failed To Finalize Server Identity Authority", exception);
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Connection openDatabase(Path database, boolean create) throws SQLException, IOException {
        return openDatabase(database, create, AuthorityOpenHook.none());
    }

    static Connection openAuthorityDatabase(Path database, boolean create, AuthorityOpenHook openHook)
        throws SQLException, IOException {
        return openDatabase(database, create, openHook);
    }

    private static Connection openDatabase(Path database, boolean create, AuthorityOpenHook openHook)
        throws SQLException, IOException {
        Path normalized = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Server Identity Authority Database Has No Parent Directory: " + normalized);
        }
        AuthorityArtifacts artifacts = validateAuthorityArtifacts(parent);
        if (!artifacts.database().equals(normalized)) {
            throw new IOException("Server Identity Authority Database Path Is Invalid: " + normalized);
        }
        return openDatabase(artifacts, create, openHook);
    }

    private static Connection openDatabase(AuthorityArtifacts artifacts, boolean create, AuthorityOpenHook openHook)
        throws SQLException, IOException {
        AuthorityOpenHook hook = Objects.requireNonNull(openHook, "openHook");
        Path normalized = artifacts.database();
        Object lock = AUTHORITY_OPEN_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            AuthorityArtifacts prepared = validateAuthorityArtifacts(artifacts.root());
            requireSameAuthorityArtifacts(artifacts, prepared, "Before Authority Open");
            AuthorityFileIdentity before = authorityFileIdentity(prepared.root(), normalized);
            if (!before.present()) {
                if (!create) {
                    throw new IOException("Server Identity Authority Database Is Missing: " + normalized);
                }
                createAuthorityDatabaseFile(normalized);
                prepared = validateAuthorityArtifacts(prepared.root());
                before = authorityFileIdentity(prepared.root(), normalized);
            }
            Properties properties = authoritySqliteProperties();
            hook.beforeOpen(normalized);
            AuthorityArtifacts afterHook = validateAuthorityArtifacts(prepared.root());
            requireSameAuthorityArtifacts(prepared, afterHook, "Before SQLite Open");
            try (AuthorityArtifactHandles handles = AuthorityArtifactHandles.open(afterHook)) {
                AuthorityArtifacts beforeJdbc = validateAuthorityArtifacts(afterHook.root());
                requireSameAuthorityArtifacts(afterHook, beforeJdbc, "Before SQLite Open");
                handles.requireSnapshot(beforeJdbc);
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized, properties);
                try {
                    requireStableAuthorityFile(beforeJdbc.root(), normalized, before);
                    if (!supportsNoFollow(connection)) {
                        throw new SQLException("SQLite NOFOLLOW Open Mode Is Unavailable");
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout = 5000");
                        try (ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                                throw new IOException("SQLite WAL Durability Is Unavailable For Server Identity Authority");
                            }
                        }
                        statement.execute("PRAGMA synchronous = FULL");
                        try (ResultSet result = statement.executeQuery("PRAGMA synchronous")) {
                            if (!result.next() || result.getInt(1) != 2) {
                                throw new IOException("SQLite FULL Durability Is Unavailable For Server Identity Authority");
                            }
                        }
                    }
                    AuthorityArtifacts afterPragmas = validateAuthorityArtifacts(beforeJdbc.root());
                    if (!afterPragmas.databasePresent()) {
                        throw new IOException("Server Identity Authority Database Disappeared During Open: " + normalized);
                    }
                    requireAuthorityArtifactsAfterOpen(beforeJdbc, afterPragmas);
                    try (AuthorityArtifactHandles trustedHandles = AuthorityArtifactHandles.open(afterPragmas)) {
                        AuthorityArtifacts trusted = validateAuthorityArtifacts(afterPragmas.root());
                        requireSameAuthorityArtifacts(afterPragmas, trusted, "After SQLite Pragmas");
                        trustedHandles.requireSnapshot(trusted);
                        hook.afterPragmas(normalized);
                        AuthorityArtifacts verified = validateAuthorityArtifacts(trusted.root());
                        requireSameAuthorityArtifacts(trusted, verified, "After SQLite Open Hook");
                        trustedHandles.requireSnapshot(verified);
                        trustedHandles.close();
                    }
                    handles.close();
                    return connection;
                } catch (IOException | SQLException | RuntimeException exception) {
                    try {
                        connection.close();
                    } catch (SQLException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                    throw exception;
                }
            }
        }
    }

    private static Properties authoritySqliteProperties() {
        Properties properties = new Properties();
        int openMode = SQLITE_OPEN_READWRITE | SQLITE_OPEN_URI | SQLITE_OPEN_PRIVATECACHE | SQLITE_OPEN_NOFOLLOW;
        properties.setProperty("open_mode", Integer.toString(openMode));
        return properties;
    }

    private static AuthorityFileIdentity authorityFileIdentity(MigrationPaths.ValidatedRoot root, Path database)
        throws IOException {
        FileState state = readStableAuthorityState(root, database, "Server Identity Authority Database");
        if (!state.present()) {
            return AuthorityFileIdentity.absent();
        }
        return new AuthorityFileIdentity(true, state.fileKey(), state.creationTime(), state.contentHash());
    }

    private static void requireStableAuthorityFile(MigrationPaths.ValidatedRoot root, Path database,
                                                   AuthorityFileIdentity expected) throws IOException {
        AuthorityFileIdentity actual = authorityFileIdentity(root, database);
        if (!actual.present() || expected.present() != actual.present()) {
            throw new IOException("Server Identity Authority Database Changed During Open: " + database);
        }
        if (expected.present()
            && ((expected.fileKey() != null || actual.fileKey() != null
                ? !Objects.equals(expected.fileKey(), actual.fileKey())
                : !expected.creationTime().equals(actual.creationTime()))
                || !expected.contentHash().equals(actual.contentHash()))) {
            throw new IOException("Server Identity Authority Database Identity Changed During Open: " + database);
        }
    }

    private static void createAuthorityDatabaseFile(Path database) throws IOException {
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        StorageSafety.forceDirectory(database.getParent());
    }

    private static boolean supportsNoFollow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sqlite_version()")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                return supportsNoFollowVersion(result.getString(1));
            }
        }
    }

    static boolean supportsNoFollowVersion(String version) {
        return versionAtLeast(version, 3, 42, 0);
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

    private static void checkpoint(Path database) throws IOException {
        AuthorityArtifacts artifacts = validateAuthorityArtifacts(database.getParent());
        try (Connection connection = openDatabase(artifacts, false, AuthorityOpenHook.none())) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
                if (!result.next() || result.getInt(1) != 0) {
                    throw new IOException("SQLite Server Identity Authority Checkpoint Is Busy");
                }
            }
        } catch (SQLException exception) {
            throw sqlFailure("Failed To Flush Server Identity Authority", exception);
        }
    }

    private static Path authorityPath(Path dataRoot) {
        return dataRoot.resolve(AUTHORITY_FILE).toAbsolutePath().normalize();
    }

    private static void requireStableAuthority(Path dataRoot, AuthorityRecord expected) throws IOException {
        AuthorityRecord actual = readAuthority(dataRoot, AuthorityFaultInjector.none())
            .orElseThrow(() -> new IOException("Durable Server Identity Authority Disappeared"));
        if (!expected.equals(actual)) {
            throw new IOException("Durable Server Identity Authority Changed During Validation");
        }
    }

    private static Set<Path> ownedPaths(Binding current) {
        Path parent = current.path().getParent();
        if (parent == null) {
            throw new IllegalStateException("Server Identity Path Has No Data Root");
        }
        Path normalizedParent = parent.toAbsolutePath().normalize();
        return Set.of(current.path(), current.installSignalPath(), authorityPath(normalizedParent),
            parent.resolve(AUTHORITY_WAL_FILE).toAbsolutePath().normalize(),
            parent.resolve(AUTHORITY_SHM_FILE).toAbsolutePath().normalize(),
            parent.resolve(AUTHORITY_JOURNAL_FILE).toAbsolutePath().normalize(),
            normalizedParent.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize(),
            normalizedParent.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize());
    }

    private static String scopeFingerprint(Path dataRoot, Path excludedProjection,
                                           boolean includeQuarantine) throws IOException {
        return scopeFingerprint(dataRoot, excludedProjection, includeQuarantine, false);
    }

    private static String scopeFingerprint(Path dataRoot, Path excludedProjection,
                                           boolean includeQuarantine, boolean includeAuthority) throws IOException {
        Path root = MigrationPaths.requireDirectory(dataRoot, "server identity data root");
        Path excluded = excludedProjection == null ? null : MigrationPaths.requirePath(excludedProjection, "excluded projection");
        validateQuarantine(root);
        List<String> values = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path path : entries.sorted(Comparator.comparing(candidate -> candidate.getFileName().toString())).toList()) {
                String name = path.getFileName().toString();
                if (excluded != null && excluded.equals(path.toAbsolutePath().normalize())) {
                    continue;
                }
                if (name.equals(FILE_NAME) || name.equals(INSTALL_SIGNAL_FILE)
                    || LEGACY_TRANSIENT_FILES.contains(name) || isAtomicTempName(name)) {
                    appendFingerprint(values, name, path);
                } else if (name.equals(QUARANTINE_CONTAINER)) {
                    if (includeQuarantine) {
                        values.add(QUARANTINE_CONTAINER + "=present");
                        appendQuarantineFingerprint(values, root);
                    }
                } else if (isAuthorityArtifactName(name)) {
                    continue;
                } else if (isAmbiguousArtifactName(name)) {
                    throw new IOException("Server Identity Root Contains An Unknown Recovery Artifact: " + path);
                }
            }
        }
        if (includeAuthority) {
            for (String name : AUTHORITY_FILE_NAMES) {
                Path path = root.resolve(name).toAbsolutePath().normalize();
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                    values.add(name + "=<missing>");
                } else {
                    appendFingerprint(values, name, path);
                }
            }
        }
        return StorageSafety.sha256(String.join("\n", values));
    }

    private static void appendFingerprint(List<String> values, String name, Path path) throws IOException {
        FileState state = readStableArtifactState(path, "Server Identity Fingerprint Artifact");
        if (!state.present()) {
            throw new IOException("Server Identity Fingerprint Artifact Is Missing: " + path);
        }
        values.add(name + "=" + stateFingerprint(state));
    }

    private static String stateFingerprint(FileState state) {
        return state.contentHash() + "|" + String.valueOf(state.fileKey()) + "|" + state.size()
            + "|" + state.creationTime() + "|" + state.lastModifiedTime();
    }

    private static void appendQuarantineFingerprint(List<String> values, Path root) throws IOException {
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            values.add(QUARANTINE_DIRECTORY + "=<missing>");
            return;
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server Identity Quarantine Root Is Invalid: " + quarantine);
        }
        values.add(QUARANTINE_DIRECTORY + "=present");
        try (var entries = Files.list(quarantine)) {
            for (Path path : entries.sorted(Comparator.comparing(candidate -> candidate.getFileName().toString())).toList()) {
                appendFingerprint(values, QUARANTINE_DIRECTORY + "/" + path.getFileName(), path);
            }
        }
    }

    private static ProjectionPresence inspectProjection(Path identity, Path signal, AuthorityRecord expected) throws IOException {
        Path normalizedIdentity = MigrationPaths.requirePath(identity, FILE_NAME);
        Path normalizedSignal = MigrationPaths.requirePath(signal, INSTALL_SIGNAL_FILE);
        Path parent = normalizedIdentity.getParent();
        if (parent == null || !parent.equals(normalizedSignal.getParent())) {
            throw new IOException("Server Identity Pair Must Share A Data Root");
        }
        MigrationPaths.requireDirectory(parent, "server identity data root");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalizedIdentity);
        MigrationPaths.requireNoSymlinkTraversal(parent, normalizedSignal);
        boolean identityExists = Files.exists(normalizedIdentity, LinkOption.NOFOLLOW_LINKS);
        boolean signalExists = Files.exists(normalizedSignal, LinkOption.NOFOLLOW_LINKS);
        boolean identityValid = false;
        if (identityExists) {
            requireRegularProjection(normalizedIdentity, "Server Identity");
            try {
                ServerId actual = readIdentity(normalizedIdentity);
                identityValid = expected == null || expected.serverId().equals(actual);
            } catch (IOException exception) {
                if (expected == null) {
                    throw exception;
                }
            }
        }
        boolean signalValid = false;
        if (signalExists) {
            requireRegularProjection(normalizedSignal, "Install Signal");
            try {
                String actual = readSignal(normalizedSignal);
                signalValid = expected == null || expected.signal().equals(actual);
            } catch (IOException exception) {
                if (expected == null) {
                    throw exception;
                }
            }
        }
        return new ProjectionPresence(identityExists, identityValid, signalExists, signalValid);
    }

    private static void requireRegularProjection(Path path, String name) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(name + " Projection Is Not A Regular File: " + path);
        }
    }

    private static void projectPair(Path dataRoot, AuthorityRecord authority, ProjectionPresence projection,
                                    AuthorityFaultInjector faultInjector, ProjectionWriter projectionWriter) throws IOException {
        Path identity = dataRoot.resolve(FILE_NAME).toAbsolutePath().normalize();
        Path signal = dataRoot.resolve(INSTALL_SIGNAL_FILE).toAbsolutePath().normalize();
        if (!projection.identityValid()) {
            faultInjector.cut(AuthorityCut.BEFORE_IDENTITY_PROJECTION);
            repairProjection(dataRoot, identity, projection.identityPresent(),
                (authority.serverId().canonicalText() + "\n").getBytes(StandardCharsets.UTF_8), projectionWriter);
            faultInjector.cut(AuthorityCut.AFTER_IDENTITY_PROJECTION);
        }
        if (!projection.signalValid()) {
            faultInjector.cut(AuthorityCut.BEFORE_SIGNAL_PROJECTION);
            repairProjection(dataRoot, signal, projection.signalPresent(),
                authority.signal().getBytes(StandardCharsets.UTF_8), projectionWriter);
            faultInjector.cut(AuthorityCut.AFTER_SIGNAL_PROJECTION);
        }
        ProjectionPresence complete = inspectProjection(identity, signal, authority);
        if (!complete.complete()) {
            throw new IOException("Server Identity Projection Is Incomplete After Durable Repair");
        }
        requireStableAuthority(dataRoot, authority);
    }

    private static void repairProjection(Path dataRoot, Path target, boolean targetPresent, byte[] bytes,
                                         ProjectionWriter projectionWriter) throws IOException {
        FileState initialTarget = readStableArtifactState(target, "Server Identity Projection Target");
        if (initialTarget.present() != targetPresent) {
            throw new IOException("Server Identity Projection Target Changed Before Repair: " + target);
        }
        String before = scopeFingerprint(dataRoot, target, false);
        if (targetPresent) {
            quarantineProjectionEvidence(dataRoot, target, initialTarget);
        }
        if (!before.equals(scopeFingerprint(dataRoot, target, false))) {
            throw new IOException("Server Identity Persistence Changed Before Projection Repair");
        }
        String afterEvidence = scopeFingerprint(dataRoot, target, true);
        FileState beforeWrite = readStableArtifactState(target, "Server Identity Projection Target");
        if (beforeWrite.present()) {
            throw new IOException("Server Identity Projection Evidence Arrived Before Repair: " + target);
        }
        projectionWriter.write(target, bytes);
        FileState written = readStableArtifactState(target, "Server Identity Projection Target");
        if (!written.present() || !Arrays.equals(bytes, written.bytes())) {
            throw new IOException("Server Identity Projection Changed During Repair: " + target);
        }
        if (!afterEvidence.equals(scopeFingerprint(dataRoot, target, true))) {
            throw new IOException("Server Identity Persistence Changed During Projection Repair");
        }
    }

    private static void quarantineProjectionEvidence(Path root, Path target, FileState expected) throws IOException {
        FileState original = readStableArtifactState(target, "Server Identity Corrupt Projection");
        if (!original.present() || !sameFileState(expected, original)) {
            throw new IOException("Server Identity Corrupt Projection Changed During Quarantine: " + target);
        }
        Path quarantine = prepareQuarantine(root);
        Path destination = reserveQuarantineEvidence(quarantine);
        try {
            FileState before = readStableArtifactState(target, "Server Identity Corrupt Projection");
            if (!sameFileState(original, before)) {
                throw new IOException("Server Identity Corrupt Projection Changed During Quarantine: " + target);
            }
            Files.move(target, destination, StandardCopyOption.ATOMIC_MOVE);
            FileState moved = readStableArtifactState(destination, "Server Identity Corrupt Projection Evidence");
            if (!moved.present() || !sameFileContent(original, moved)) {
                throw new IOException("Server Identity Corrupt Projection Changed During Quarantine: " + target);
            }
            if (readStableArtifactState(target, "Server Identity Corrupt Projection").present()) {
                throw new IOException("Server Identity Corrupt Projection Remained After Quarantine: " + target);
            }
        } catch (IOException exception) {
            throw new IOException("Failed To Quarantine Server Identity Corrupt Projection: " + target, exception);
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
        StorageSafety.forceDirectory(root);
        validateQuarantine(root);
    }

    private static Path reserveQuarantineEvidence(Path quarantine) throws IOException {
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = quarantine.resolve(ATOMIC_TEMP_PREFIX + UUID.randomUUID() + ATOMIC_TEMP_SUFFIX)
                .toAbsolutePath().normalize();
            if (!candidate.getParent().equals(quarantine)) {
                throw new IOException("Server Identity Quarantine Evidence Escaped Its Root");
            }
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("Unable To Reserve Server Identity Quarantine Evidence");
    }

    private static void writeProjectionAtomically(Path target, byte[] bytes) throws IOException {
        FileState state = readStableArtifactState(target, "Server Identity Projection Target");
        if (state.present()) {
            throw new FileAlreadyExistsException(target.toString());
        }
        AtomicFiles.writeNew(target, bytes);
    }

    private static Pair readPair(Path identity, Path signal) throws IOException {
        ProjectionPresence projection = inspectProjection(identity, signal, null);
        if (!projection.complete()) {
            throw new IOException("Server Identity Pair Is Incomplete");
        }
        Path normalizedIdentity = MigrationPaths.requirePath(identity, FILE_NAME);
        Path normalizedSignal = MigrationPaths.requirePath(signal, INSTALL_SIGNAL_FILE);
        ServerId id = readIdentity(normalizedIdentity);
        String signalContent = readSignal(normalizedSignal);
        String expected = installSignalCanonical(id);
        if (!expected.equals(signalContent)) {
            throw new IOException("Fresh Install Signal Does Not Match Server Identity: " + normalizedSignal);
        }
        return new Pair(id, signalContent);
    }

    private static ServerId readIdentity(Path path) throws IOException {
        FileState file = readStableArtifactState(path, "Server Identity");
        if (!file.present()) {
            throw new IOException("Server Identity Is Missing: " + path);
        }
        String persisted = new String(file.bytes(), StandardCharsets.UTF_8);
        String value = persisted.endsWith("\n") ? persisted.substring(0, persisted.length() - 1) : persisted;
        try {
            ServerId serverId = ServerId.parseCanonicalText(value);
            if (!persisted.equals(serverId.canonicalText() + "\n")) {
                throw new IOException("Server Identity Is Not Canonical: " + path);
            }
            return serverId;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Server Identity Is Invalid: " + path, exception);
        }
    }

    private static String readSignal(Path signal) throws IOException {
        FileState file = readStableArtifactState(signal, "Fresh Install Signal");
        if (!file.present()) {
            throw new IOException("Fresh Install Signal Is Missing: " + signal);
        }
        return new String(file.bytes(), StandardCharsets.UTF_8);
    }

    private static String installSignalCanonical(ServerId serverId) {
        String unsigned = INSTALL_SIGNAL_PREFIX + "server-id=" + serverId.canonicalText() + "\n";
        return unsigned + "signal-hash=" + StorageSafety.sha256(unsigned) + "\n";
    }

    private static boolean isFreshInstallRoot(Path dataRoot) throws IOException {
        try (var entries = Files.list(dataRoot)) {
            return entries.map(entry -> entry.getFileName().toString())
                .allMatch(FRESH_INSTALL_ENTRIES::contains);
        }
    }

    private static void requireFreshAuthorityRoot(Path dataRoot, FreshRootAuthority authority) throws IOException {
        Path root = MigrationPaths.requireDirectory(dataRoot, "server identity data root").toRealPath();
        if (!root.equals(Objects.requireNonNull(authority, "freshAuthority").activeRoot())) {
            throw new IOException("Fresh ReSync Identity Authority Does Not Match The Active Root");
        }
        Path migrations = root.resolve(ReSyncDataFixer.VERSION_DIRECTORY);
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!FRESH_INSTALL_ENTRIES.contains(name) && !name.equals(ReSyncDataFixer.VERSION_DIRECTORY)
                    && !FreshInstallInputs.acceptsEntry(entry)) {
                    throw new IOException("Fresh ReSync Identity Root Contains Unowned Data");
                }
            }
        }
        if (!Files.exists(migrations, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(migrations) || !Files.isDirectory(migrations, LinkOption.NOFOLLOW_LINKS)
            || ReSyncDataFixer.installedVersion(root).isEmpty()) {
            throw new IOException("Fresh ReSync Identity Root Has Invalid Migration State");
        }
        Path version = ReSyncDataFixer.versionPath(root);
        try (var entries = Files.list(migrations)) {
            if (entries.anyMatch(entry -> !entry.equals(version))) {
                throw new IOException("Fresh ReSync Identity Root Contains Unowned Migration Data");
            }
        }
    }

    private static IOException sqlFailure(String message, SQLException exception) {
        return new IOException(message, exception);
    }

    @FunctionalInterface
    interface FreshInstallInitializer {
        void prepare(Path root) throws IOException;
    }

    public record Binding(Path path, ServerId serverId, boolean freshInstall) {
        public Binding {
            path = MigrationPaths.requirePath(path, FILE_NAME);
            serverId = Objects.requireNonNull(serverId, "serverId");
        }

        public Path installSignalPath() {
            Path parent = path.getParent();
            if (parent == null) {
                throw new IllegalStateException("Server Identity Path Has No Data Root");
            }
            return parent.resolve(INSTALL_SIGNAL_FILE).toAbsolutePath().normalize();
        }
    }

    private record Pair(ServerId serverId, String signal) {
    }

    private record ProjectionPresence(boolean identityPresent, boolean identityValid,
                                      boolean signalPresent, boolean signalValid) {
        private boolean complete() {
            return identityPresent && identityValid && signalPresent && signalValid;
        }
    }

    private record FileState(boolean present, Object fileKey, long size, FileTime creationTime,
                             FileTime lastModifiedTime, byte[] bytes, String contentHash) {
        private static FileState absent() {
            return new FileState(false, null, 0, null, null, new byte[0], "");
        }
    }

    private record AuthorityArtifacts(MigrationPaths.ValidatedRoot root, List<Path> paths,
                                      List<Optional<AuthorityArtifactIdentity>> identities) {
        private AuthorityArtifacts {
            paths = List.copyOf(paths);
            identities = List.copyOf(identities);
        }

        private Path database() {
            return paths.getFirst();
        }

        private boolean databasePresent() {
            return identities.getFirst().isPresent();
        }
    }

    private record AuthorityArtifactIdentity(BasicFileAttributes attributes, String contentHash,
                                             Object objectIdentity) {
        private AuthorityArtifactIdentity {
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(objectIdentity, "objectIdentity");
        }

        private boolean sameSnapshot(AuthorityArtifactIdentity other) {
            return sameFileIdentity(attributes, other.attributes) && contentHash.equals(other.contentHash)
                && objectIdentity.equals(other.objectIdentity);
        }
    }

    private static final class AuthorityArtifactHandles implements AutoCloseable {
        private final List<Optional<FileChannel>> channels;

        private AuthorityArtifactHandles(List<Optional<FileChannel>> channels) {
            this.channels = List.copyOf(channels);
        }

        private static AuthorityArtifactHandles open(AuthorityArtifacts artifacts) throws IOException {
            List<Optional<FileChannel>> channels = new ArrayList<>(artifacts.paths().size());
            try {
                for (int index = 0; index < artifacts.paths().size(); index++) {
                    channels.add(artifacts.identities().get(index).isPresent()
                        ? Optional.of(FileChannel.open(artifacts.paths().get(index), StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS))
                        : Optional.empty());
                }
                return new AuthorityArtifactHandles(channels);
            } catch (IOException | RuntimeException exception) {
                close(channels, exception);
                throw exception;
            }
        }

        private void requireSnapshot(AuthorityArtifacts artifacts) throws IOException {
            if (channels.size() != artifacts.identities().size()) {
                throw new IOException("Server Identity Authority Handle Set Changed Before SQLite Open");
            }
            for (int index = 0; index < channels.size(); index++) {
                Optional<FileChannel> channel = channels.get(index);
                Optional<AuthorityArtifactIdentity> identity = artifacts.identities().get(index);
                if (channel.isPresent() != identity.isPresent()) {
                    throw new IOException("Server Identity Authority Handle Changed Before SQLite Open: "
                        + artifacts.paths().get(index));
                }
                if (channel.isEmpty()) {
                    continue;
                }
                AuthorityArtifactContent content = authorityArtifactContent(channel.get());
                if (content.size() != identity.get().attributes().size()
                    || !content.contentHash().equals(identity.get().contentHash())) {
                    throw new IOException("Server Identity Authority Handle Changed Before SQLite Open: "
                        + artifacts.paths().get(index));
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (Optional<FileChannel> channel : channels) {
                if (channel.isEmpty()) {
                    continue;
                }
                try {
                    channel.get().close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static void close(List<Optional<FileChannel>> channels, Exception failure) {
            for (Optional<FileChannel> channel : channels) {
                if (channel.isEmpty()) {
                    continue;
                }
                try {
                    channel.get().close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private record AuthorityArtifactContent(long size, String contentHash) {
        private AuthorityArtifactContent {
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    private record AuthorityFileIdentity(boolean present, Object fileKey, FileTime creationTime, String contentHash) {
        private static AuthorityFileIdentity absent() {
            return new AuthorityFileIdentity(false, null, null, "");
        }
    }

    private record AuthorityEvidence(AuthorityRecord authority, String fingerprint) {
    }

    private record AuthorityRecord(ServerId serverId, String signal, String signalHash, ProjectionState state) {
        private AuthorityRecord {
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(signal, "signal");
            Objects.requireNonNull(signalHash, "signalHash");
            Objects.requireNonNull(state, "state");
            if (!installSignalCanonical(serverId).equals(signal)
                || !StorageSafety.sha256(signal).equals(signalHash)) {
                throw new IllegalArgumentException("Server Identity Authority Signal Is Invalid");
            }
        }

        private static AuthorityRecord pending(ServerId serverId) {
            String signal = installSignalCanonical(serverId);
            return new AuthorityRecord(serverId, signal, StorageSafety.sha256(signal), ProjectionState.PENDING);
        }

        private static AuthorityRecord committed(ServerId serverId, String signal) {
            return new AuthorityRecord(serverId, signal, StorageSafety.sha256(signal), ProjectionState.COMMITTED);
        }

        private static AuthorityRecord fromPersisted(String serverId, String signal, String signalHash, String state) {
            ServerId parsed = ServerId.parseCanonicalText(serverId);
            ProjectionState projectionState = ProjectionState.valueOf(state);
            return new AuthorityRecord(parsed, signal, signalHash, projectionState);
        }

        private AuthorityRecord committed() {
            return new AuthorityRecord(serverId, signal, signalHash, ProjectionState.COMMITTED);
        }
    }

    private record AuthorityColumn(String name, String type, boolean notNull, String defaultValue,
                                   int primaryKey, int hidden) {
    }

    private enum AuthoritySchema {
        EMPTY,
        V1
    }

    private enum ProjectionState {
        PENDING,
        COMMITTED
    }

    enum AuthorityCut {
        AFTER_AUTHORITY_REOPEN,
        BEFORE_AUTHORITY_WRITE,
        AFTER_AUTHORITY_WRITE,
        BEFORE_AUTHORITY_COMMIT,
        AFTER_AUTHORITY_COMMIT,
        BEFORE_IDENTITY_PROJECTION,
        AFTER_IDENTITY_PROJECTION,
        BEFORE_SIGNAL_PROJECTION,
        AFTER_SIGNAL_PROJECTION,
        BEFORE_PROJECTION_COMMIT,
        AFTER_PROJECTION_COMMIT
    }

    @FunctionalInterface
    interface ProductionAuthorityOperation<T> {
        T execute(ProductionAuthorityKeyStore store) throws IOException;
    }

    @FunctionalInterface
    interface AuthorityFaultInjector {
        void cut(AuthorityCut point) throws IOException;

        static AuthorityFaultInjector none() {
            return point -> {
            };
        }
    }

    @FunctionalInterface
    interface AuthorityOpenHook {
        void beforeOpen(Path database) throws IOException;

        default void afterPragmas(Path database) throws IOException {
        }

        static AuthorityOpenHook none() {
            return database -> {
            };
        }
    }

    @FunctionalInterface
    interface ProjectionWriter {
        void write(Path target, byte[] bytes) throws IOException;

        static ProjectionWriter atomic() {
            return ServerIdentityStore::writeProjectionAtomically;
        }
    }

    private enum PersistenceState {
        OPEN,
        QUIESCED
    }
}
