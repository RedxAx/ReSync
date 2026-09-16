package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AssetTransactionCoordinator implements AutoCloseable {
    private static final int STATE_VERSION = 1;
    private static final String STATE_FORMAT = "asset-coordinator-state-v1";
    private static final String INTENT_FORMAT = "asset-transaction-intent-v1";
    private static final String GENESIS_FORMAT = "asset-coordinator-genesis-v1";
    private static final String HISTORY_CHECKPOINT_FORMAT = "asset-history-checkpoint-v2";
    private static final String LEGACY_HISTORY_CHECKPOINT_FORMAT = "asset-history-checkpoint-v1";
    private static final String HISTORY_CHECKPOINT_FILE = "history-checkpoint.json";
    private static final long HISTORY_CHECKPOINT_SIZE_LIMIT = 16L * 1024L * 1024L;
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private static final long RETAINED_EVIDENCE_FILE_LIMIT = 32L * 1024L * 1024L;
    private static final long RETAINED_EVIDENCE_TOTAL_LIMIT = 128L * 1024L * 1024L;
    private static final int EVIDENCE_MAX_DEPTH = 64;
    private static final int EVIDENCE_MAX_PATHS = 100_000;
    private static final long HASHED_EVIDENCE_FILE_LIMIT = 512L * 1024L * 1024L;
    private static final long HASHED_EVIDENCE_TOTAL_LIMIT = 8L * 1024L * 1024L * 1024L;
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Object ROOT_MONITOR = new Object();
    private static final Map<Path, RootContext> ROOTS = new LinkedHashMap<>();
    private final RootContext context;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final List<ListenerRegistration> registrations = new CopyOnWriteArrayList<>();
    private final ThreadLocal<JsonObject> transactionIntentScope = new ThreadLocal<>();

    public AssetTransactionCoordinator(Path canonicalRoot, Gson gson) throws IOException {
        this(canonicalRoot, gson, Clock.systemUTC());
    }

    public AssetTransactionCoordinator(Path canonicalRoot, Gson gson, Clock clock) throws IOException {
        context = acquireRoot(canonicalRoot, gson, clock, null, null, null);
    }

    public static AssetTransactionCoordinator open(Path canonicalRoot, Gson gson) throws IOException {
        return new AssetTransactionCoordinator(canonicalRoot, gson);
    }

    public static AssetTransactionCoordinator open(Path canonicalRoot, Gson gson, Clock clock) throws IOException {
        return new AssetTransactionCoordinator(canonicalRoot, gson, clock);
    }

    static AssetTransactionCoordinator openObserved(Path canonicalRoot, Gson gson, Clock clock,
                                                     EvidenceReadObserver observer) throws IOException {
        return new AssetTransactionCoordinator(acquireRoot(canonicalRoot, gson, clock, null, null, null, observer));
    }

    public static AssetTransactionCoordinator adoptExisting(Path canonicalRoot, Gson gson,
                                                            AdoptionInventory inventory) throws IOException {
        return new AssetTransactionCoordinator(acquireRoot(canonicalRoot, gson, Clock.systemUTC(),
            Objects.requireNonNull(inventory, "inventory"), AdoptionBinding.derived(inventory), null));
    }

    public static AssetTransactionCoordinator adoptExisting(Path canonicalRoot, Gson gson, Clock clock,
                                                            AdoptionInventory inventory) throws IOException {
        return new AssetTransactionCoordinator(acquireRoot(canonicalRoot, gson, clock,
            Objects.requireNonNull(inventory, "inventory"), AdoptionBinding.derived(inventory), null));
    }

    public static AssetTransactionCoordinator openOrAdopt(Path canonicalRoot, Gson gson,
                                                          AdoptionInventory inventory,
                                                          AdoptionBinding binding) throws IOException {
        return openOrAdopt(canonicalRoot, gson, Clock.systemUTC(), inventory, binding, null);
    }

    public static AssetTransactionCoordinator openOrAdopt(Path canonicalRoot, Gson gson,
                                                          AdoptionInventory inventory, AdoptionBinding binding,
                                                          AdoptionPreparation preparation) throws IOException {
        return openOrAdopt(canonicalRoot, gson, Clock.systemUTC(), inventory, binding, preparation);
    }

    public static AssetTransactionCoordinator openOrAdopt(Path canonicalRoot, Gson gson, Clock clock,
                                                          AdoptionInventory inventory,
                                                          AdoptionBinding binding) throws IOException {
        return openOrAdopt(canonicalRoot, gson, clock, inventory, binding, null);
    }

    public static AssetTransactionCoordinator openOrAdopt(Path canonicalRoot, Gson gson, Clock clock,
                                                          AdoptionInventory inventory, AdoptionBinding binding,
                                                          AdoptionPreparation preparation) throws IOException {
        return new AssetTransactionCoordinator(acquireRoot(canonicalRoot, gson, clock,
            Objects.requireNonNull(inventory, "inventory"), Objects.requireNonNull(binding, "binding"), preparation));
    }

    private AssetTransactionCoordinator(RootContext context) {
        this.context = context;
    }

    public Path canonicalRoot() {
        return context.root;
    }

    public Optional<CommittedAsset> committedAsset(AssetKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        ResourceEntry entry = context.state.resources().get(key);
        requireOpen();
        return entry == null ? Optional.empty() : Optional.of(new CommittedAsset(
            entry.state(), context.root.resolve(entry.path()).normalize(), entry.mutationId()));
    }

    public long committedSequence() {
        requireOpen();
        return context.state.rootSequence();
    }

    public <T> T read(Function<Snapshot, T> reader) {
        Objects.requireNonNull(reader, "reader");
        lifecycle.readLock().lock();
        try {
            requireOpen();
            context.lock.readLock().lock();
            try {
                return reader.apply(context.state.snapshot(context.metadata, context.root));
            } finally {
                context.lock.readLock().unlock();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public TransactionResult transact(TransactionRequest request) throws IOException {
        return transact(request, null);
    }

    public TransactionResult transact(TransactionRequest request, long expectedSequence) throws IOException {
        if (expectedSequence < 0L) {
            throw new IllegalArgumentException("Expected sequence cannot be negative");
        }
        return transact(request, Long.valueOf(expectedSequence));
    }

    private TransactionResult transact(TransactionRequest request, Long expectedSequence) throws IOException {
        Objects.requireNonNull(request, "request");
        context.notificationLock.lock();
        try {
            lifecycle.readLock().lock();
            TransactionResult result;
            try {
                requireOpen();
                context.lock.writeLock().lock();
                try {
                    result = context.transact(request, transactionIntentScope.get(), expectedSequence);
                } finally {
                    context.lock.writeLock().unlock();
                }
            } finally {
                lifecycle.readLock().unlock();
            }
            if (!result.replay()) {
                context.activeDeliveries++;
                try {
                    context.notifyListeners(result);
                } finally {
                    context.activeDeliveries--;
                    completePendingRelease(context);
                }
            }
            return result;
        } finally {
            context.notificationLock.unlock();
        }
    }

    public <T> T withTransactionIntentScope(JsonObject scope, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        JsonObject previous = transactionIntentScope.get();
        if (scope == null) {
            transactionIntentScope.remove();
        } else {
            transactionIntentScope.set(scope.deepCopy());
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                transactionIntentScope.remove();
            } else {
                transactionIntentScope.set(previous);
            }
        }
    }

    public void healthCheck() throws IOException {
        withHealthCheckScope(() -> {
        });
    }

    void withHealthCheckScope(HealthCheckAction action) throws IOException {
        Objects.requireNonNull(action, "action");
        lifecycle.readLock().lock();
        try {
            requireOpen();
            context.lock.writeLock().lock();
            try {
                context.healthCheck();
                action.run();
            } finally {
                context.lock.writeLock().unlock();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void flush() throws IOException {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            context.lock.writeLock().lock();
            try {
                context.flush();
            } finally {
                context.lock.writeLock().unlock();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public Optional<MutationView> mutation(UUID mutationId) throws IOException {
        Objects.requireNonNull(mutationId, "mutationId");
        lifecycle.readLock().lock();
        try {
            requireOpen();
            context.lock.readLock().lock();
            try {
                return context.mutation(mutationId);
            } finally {
                context.lock.readLock().unlock();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public ListenerRegistration addListener(PostCommitListener listener) {
        Objects.requireNonNull(listener, "listener");
        lifecycle.readLock().lock();
        try {
            requireOpen();
            ListenerRegistration registration = new ListenerRegistration(context, listener);
            context.listeners.add(listener);
            registrations.add(registration);
            return registration;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public List<ListenerFailure> listenerFailures() {
        return List.copyOf(context.listenerFailures);
    }

    ValidationMetrics validationMetrics() {
        context.lock.readLock().lock();
        try {
            AssetTransactionManager.IoMetrics managerMetrics = context.manager.ioMetrics();
            EvidenceWatcher.Metrics watcherMetrics = context.evidenceWatcher.metrics();
            return new ValidationMetrics(context.fullValidationPasses, context.incrementalValidationPasses,
                context.historyEvidenceScans, managerMetrics.fullJournalPasses(), managerMetrics.journalReads(),
                managerMetrics.stagedPayloadReads(), managerMetrics.indexedMutationLookups(),
                managerMetrics.assetPathCanonicalizations(),
                watcherMetrics.fullRegistrationPasses(), watcherMetrics.incrementalRegistrationPasses(),
                watcherMetrics.incrementalDirectoryVisits(), watcherMetrics.registeredDirectories(),
                watcherMetrics.acceptedEvidencePaths(), watcherMetrics.invalidations(), context.phaseTimings.snapshot(),
                context.manager.phaseTiming(), watcherMetrics.fullDirectoryWalks());
        } finally {
            context.lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        context.notificationLock.lock();
        try {
            lifecycle.writeLock().lock();
            try {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                for (ListenerRegistration registration : registrations) {
                    registration.close();
                }
                registrations.clear();
                releaseRoot(context);
            } finally {
                lifecycle.writeLock().unlock();
            }
        } finally {
            context.notificationLock.unlock();
        }
    }

    public void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Asset transaction coordinator is closed");
        }
    }

    private static RootContext acquireRoot(Path requestedRoot, Gson gson, Clock clock,
                                           AdoptionInventory adoption, AdoptionBinding adoptionBinding,
                                           AdoptionPreparation preparation) throws IOException {
        return acquireRoot(requestedRoot, gson, clock, adoption, adoptionBinding, preparation, null);
    }

    private static RootContext acquireRoot(Path requestedRoot, Gson gson, Clock clock,
                                           AdoptionInventory adoption, AdoptionBinding adoptionBinding,
                                           AdoptionPreparation preparation, EvidenceReadObserver observer) throws IOException {
        Objects.requireNonNull(gson, "gson");
        Objects.requireNonNull(clock, "clock");
        Path root = canonicalRoot(requestedRoot);
        synchronized (ROOT_MONITOR) {
            RootContext existing = ROOTS.get(root);
            if (existing != null) {
                if (existing.references == 0) {
                    throw new RootBusyException("Asset transaction root is completing listener delivery: " + root);
                }
                if (adoption != null) {
                    existing.lock.writeLock().lock();
                    try {
                        existing.validateAdoption(adoption, adoptionBinding);
                        if (preparation != null) {
                            if (!Files.isRegularFile(existing.stateFile, LinkOption.NOFOLLOW_LINKS)) {
                                throw new IOException("Asset adoption preparation requires durable coordinator state");
                            }
                            preparation.prepare(existing.root);
                        }
                        existing.healthCheck();
                    } finally {
                        existing.lock.writeLock().unlock();
                    }
                }
                existing.references++;
                return existing;
            }
            RootContext created = RootContext.open(root, gson, clock, adoption, adoptionBinding, preparation, observer);
            created.references = 1;
            ROOTS.put(root, created);
            return created;
        }
    }

    private static void releaseRoot(RootContext context) throws IOException {
        synchronized (ROOT_MONITOR) {
            RootContext registered = ROOTS.get(context.root);
            if (registered != context || context.references < 1) {
                throw new IOException("Asset transaction coordinator root registry is inconsistent: " + context.root);
            }
            context.references--;
            if (context.references == 0) {
                if (context.activeDeliveries > 0) {
                    context.releasePending = true;
                } else {
                    ROOTS.remove(context.root);
                    context.close();
                }
            }
        }
    }

    private static void completePendingRelease(RootContext context) throws IOException {
        synchronized (ROOT_MONITOR) {
            if (!context.releasePending || context.references != 0 || context.activeDeliveries != 0
                || ROOTS.get(context.root) != context) {
                return;
            }
            context.releasePending = false;
            ROOTS.remove(context.root);
            context.close();
        }
    }

    private static Path canonicalRoot(Path requestedRoot) throws IOException {
        if (requestedRoot == null) {
            throw new IOException("Asset transaction root is required");
        }
        Path normalized = requestedRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset transaction root is not a safe directory: " + normalized);
        }
        return normalized.toRealPath();
    }

    private static long elapsedNanos(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static final class RootContext implements AutoCloseable {
        private final Path root;
        private final Path coordinatorRoot;
        private final Path stateFile;
        private final Path projectFile;
        private final Path historyCheckpointFile;
        private final Gson gson;
        private final Clock clock;
        private final FileChannel lockChannel;
        private final FileLock processLock;
        private final AssetTransactionManager manager;
        private final EvidenceWatcher evidenceWatcher;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private final ReentrantLock notificationLock = new ReentrantLock(true);
        private final CopyOnWriteArrayList<PostCommitListener> listeners = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ListenerFailure> listenerFailures = new CopyOnWriteArrayList<>();
        private final PhaseTimings phaseTimings = new PhaseTimings();
        private volatile CoordinatorState state;
        private AssetProjectMetadata metadata;
        private HistoryValidation historyValidation;
        private long fullValidationPasses = 1L;
        private long incrementalValidationPasses;
        private long historyEvidenceScans;
        private int references;
        private volatile int activeDeliveries;
        private volatile boolean releasePending;

        private RootContext(Path root, Path coordinatorRoot, Gson gson, Clock clock, FileChannel lockChannel,
                            FileLock processLock, AssetTransactionManager manager, CoordinatorState state,
                            AssetProjectMetadata metadata, HistoryValidation historyValidation,
                            EvidenceWatcher evidenceWatcher, long historyEvidenceScans) {
            this.root = root;
            this.coordinatorRoot = coordinatorRoot;
            this.stateFile = coordinatorRoot.resolve("state.json");
            this.projectFile = root.resolve("project.json");
            this.historyCheckpointFile = coordinatorRoot.resolve(HISTORY_CHECKPOINT_FILE);
            this.gson = gson;
            this.clock = clock;
            this.lockChannel = lockChannel;
            this.processLock = processLock;
            this.manager = manager;
            this.evidenceWatcher = evidenceWatcher;
            this.state = state;
            this.metadata = metadata;
            this.historyValidation = historyValidation;
            this.historyEvidenceScans = historyEvidenceScans;
        }

        private static RootContext open(Path root, Gson gson, Clock clock, AdoptionInventory adoption,
                                        AdoptionBinding adoptionBinding, AdoptionPreparation preparation,
                                        EvidenceReadObserver observer) throws IOException {
            Path coordinatorRoot = root.resolve(".asset-coordinator");
            StorageSafety.createDirectoriesNoSymlinks(root, coordinatorRoot);
            StorageSafety.createDirectoriesNoSymlinks(root, coordinatorRoot.resolve("bindings"));
            Path lockPath = coordinatorRoot.resolve("root.lock");
            boolean authorityExisted = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Asset transaction root lock is unsafe: " + lockPath);
            }
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock processLock = null;
            EvidenceWatcher evidenceWatcher = null;
            EvidenceWatcher startupWatcher = null;
            try {
                try {
                    processLock = channel.tryLock();
                } catch (OverlappingFileLockException failure) {
                    throw new RootBusyException("Asset transaction root is already coordinated in this process: " + root, failure);
                }
                if (processLock == null) {
                    throw new RootBusyException("Asset transaction root is locked by another process: " + root);
                }
                prepareGenesis(root, coordinatorRoot, gson, adoption, adoptionBinding, authorityExisted);
                AssetTransactionManager manager = new AssetTransactionManager(root, gson,
                    AssetTransactionManager.RecoveryMode.DEFERRED, observer == null ? null : observer::contentRead);
                if (observer == null && preparation == null) {
                    long checkpointStarted = System.nanoTime();
                    CheckpointOpen checkpoint = tryCheckpointOpen(root, coordinatorRoot, manager, gson);
                    if (checkpoint != null) {
                        evidenceWatcher = checkpoint.evidenceWatcher();
                        TemporaryLifecycleDiagnostics.event("asset_history_checkpoint", checkpointStarted,
                            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null,
                                "asset_coordinator", null, null, null, checkpoint.state().rootSequence(), null,
                                checkpoint.state().rootSequence()), "outcome", "accepted", "validationMode",
                                "checkpoint", "historyEntryCount", checkpoint.checkpoint().evidenceCount(),
                                "mutationCount", checkpoint.state().mutations().size()));
                        return new RootContext(root, coordinatorRoot, gson, clock, channel, processLock, manager,
                            checkpoint.state(), checkpoint.metadata(), checkpoint.historyValidation(), evidenceWatcher, 1L);
                    }
                }
                EvidenceSnapshot evidence = captureEvidenceSnapshot(root, manager, adoption, observer);
                boolean finalSnapshotFresh = true;
                validateJournalsBeforeRecovery(evidence);
                validateInternalEvidence(evidence);
                HistoryEvidence historyBefore = evidence.history();
                long historyEvidenceScans = 1L;
                ValidatedJournals validated = validateInspections(root, manager, evidence.inspections(), evidence, gson);
                Path stateFile = coordinatorRoot.resolve("state.json");
                Path projectFile = root.resolve("project.json");
                boolean stateExists = evidence.isRegularFile(stateFile);
                if (!stateExists && (validated.managerTransactions > 0 || hasUnregisteredEvidence(evidence))
                    && !validated.initialPrepared()) {
                    throw new IOException("Asset evidence exists without durable coordinator state");
                }
                CoordinatorState recoveryState = stateExists ? readState(evidence.content(stateFile), stateFile) : CoordinatorState.empty();
                if (!validated.preparedFingerprints.isEmpty()) {
                    long durableSequence = recoveryState.rootSequence;
                    if (durableSequence != validated.preparedSequenceBefore
                        && durableSequence != Math.addExact(validated.preparedSequenceBefore, 1L)) {
                        throw new IOException("Prepared coordinator journal does not continue durable root sequence");
                    }
                }
                validateRecoveryEvidence(root, recoveryState, validated, adoption, evidence);
                if (!validated.preparedFingerprints.isEmpty()) {
                    validateDurableHistory(recoveryState, stateExists ? stateIdentity(evidence.content(stateFile), stateFile) : null,
                        validated);
                    startupWatcher = EvidenceWatcher.openNamespace(root, evidence);
                    requireUnchangedStartupNamespace(startupWatcher, "prepared recovery registration");
                    if (observer != null) {
                        observer.beforePreparedRecovery(root);
                    }
                    EvidenceSnapshot recoveryEvidence = evidence;
                    ValidatedJournals recoveryJournals = validated;
                    manager.validateFrozenControlEvidence(recoveryEvidence.inspections());
                    AssetTransactionManager.RecoveryEvidence recoveryFiles = preparedRecoveryFiles(recoveryEvidence,
                        recoveryJournals.preparedFileStates);
                    requireUnchangedStartupNamespace(startupWatcher, "prepared recovery validation");
                    startupWatcher.close();
                    startupWatcher = null;
                    finalSnapshotFresh = false;
                    manager.recoverValidatedInspections(evidence.inspections(), validated.preparedFingerprints,
                        () -> recoveryFiles);
                    evidence = captureEvidenceSnapshot(root, manager, adoption, observer);
                    finalSnapshotFresh = true;
                    validateJournalsBeforeRecovery(evidence);
                    validateInternalEvidence(evidence);
                    historyBefore = evidence.history();
                    historyEvidenceScans++;
                    validated = validateInspections(root, manager, evidence.inspections(), evidence, gson);
                }
                AssetProjectMetadata metadata = readMetadata(evidence.contentOrNull(projectFile), projectFile);
                stateExists = evidence.isRegularFile(stateFile);
                if (!stateExists && (validated.managerTransactions > 0 || hasUnregisteredEvidence(evidence))) {
                    throw new IOException("Asset evidence exists without durable coordinator state");
                }
                CoordinatorState state = stateExists
                    ? readState(evidence.content(stateFile), stateFile)
                    : CoordinatorState.initial(metadata);
                validateDurableHistory(state, stateExists ? stateIdentity(evidence.content(stateFile), stateFile) : null,
                    validated);
                validatePair(stateExists, evidence.isRegularFile(projectFile), state, metadata);
                if (!validated.coordinatorJournals.equals(state.mutations.keySet())) {
                    throw new IOException("Coordinator journals do not match durable mutation state");
                }
                if (preparation != null) {
                    if (!evidence.isRegularFile(stateFile)) {
                        throw new IOException("Asset adoption preparation requires durable coordinator state");
                    }
                    finalSnapshotFresh = false;
                    preparation.prepare(root);
                }
                if (!finalSnapshotFresh) {
                    evidence = captureEvidenceSnapshot(root, manager, adoption, observer);
                    validateJournalsBeforeRecovery(evidence);
                    validateInternalEvidence(evidence);
                    historyBefore = evidence.history();
                    historyEvidenceScans++;
                    validated = validateInspections(root, manager, evidence.inspections(), evidence, gson);
                    metadata = readMetadata(evidence.contentOrNull(projectFile), projectFile);
                    stateExists = evidence.isRegularFile(stateFile);
                    state = stateExists ? readState(evidence.content(stateFile), stateFile) : CoordinatorState.initial(metadata);
                    validateDurableHistory(state, stateExists ? stateIdentity(evidence.content(stateFile), stateFile) : null,
                        validated);
                    validatePair(stateExists, evidence.isRegularFile(projectFile), state, metadata);
                    if (!validated.coordinatorJournals.equals(state.mutations.keySet())) {
                        throw new IOException("Coordinator journals do not match durable mutation state");
                    }
                    validateRecoveryEvidence(root, state, validated, adoption, evidence);
                }
                startupWatcher = EvidenceWatcher.openNamespace(root, evidence);
                requireUnchangedStartupNamespace(startupWatcher, "registration");
                validateAssets(root, state, evidence);
                if (observer != null) {
                    observer.beforeFinalFence(root);
                }
                manager.validateFrozenControlEvidence(evidence.inspections());
                validateFinalAssetEvidence(state, evidence);
                validateFinalControlEvidence(evidence);
                HistoryEvidence historyAfter = captureHistoryEvidence(root, evidence.limits());
                historyEvidenceScans++;
                if (!historyBefore.equals(historyAfter)) {
                    throw new IOException("Asset transaction history changed during coordinator validation: "
                        + historyDifference(historyBefore, historyAfter));
                }
                validateFinalNamespaceEvidence(evidence);
                requireUnchangedStartupNamespace(startupWatcher, "validation");
                evidenceWatcher = EvidenceWatcher.open(root, evidence);
                requireUnchangedStartupNamespace(startupWatcher, "watcher handoff");
                startupWatcher.close();
                startupWatcher = null;
                HistoryValidation historyValidation = new HistoryValidation(compactValidatedJournals(validated, state,
                    stateIdentity(evidence.content(stateFile), stateFile)));
                RootContext context = new RootContext(root, coordinatorRoot, gson, clock, channel, processLock, manager,
                    state, metadata, historyValidation, evidenceWatcher, historyEvidenceScans);
                context.persistHistoryCheckpoint();
                return context;
            } catch (IOException | RuntimeException failure) {
                if (startupWatcher != null) {
                    try {
                        startupWatcher.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (evidenceWatcher != null) {
                    try {
                        evidenceWatcher.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (processLock != null) {
                    try {
                        processLock.release();
                    } catch (IOException releaseFailure) {
                        failure.addSuppressed(releaseFailure);
                    }
                }
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private static CheckpointOpen tryCheckpointOpen(Path root, Path coordinatorRoot,
                                                        AssetTransactionManager manager, Gson gson) {
            EvidenceWatcher startupWatcher = null;
            EvidenceWatcher evidenceWatcher = null;
            long checkpointStarted = System.nanoTime();
            try {
                Path checkpointFile = coordinatorRoot.resolve(HISTORY_CHECKPOINT_FILE);
                if (Files.isSymbolicLink(checkpointFile)
                    || !Files.isRegularFile(checkpointFile, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
                long captureStarted = System.nanoTime();
                EvidenceSnapshot evidence = captureCheckpointEvidenceSnapshot(root);
                checkpointPhase("capture", captureStarted, evidence.paths().size(), evidence.hashBudget().hashed);
                long namespaceStarted = System.nanoTime();
                startupWatcher = EvidenceWatcher.openNamespace(root, evidence);
                checkpointPhase("namespace_registration", namespaceStarted, startupWatcher.registeredPaths.size(), 0L);
                requireUnchangedStartupNamespace(startupWatcher, "checkpoint registration");
                CapturedContent checkpointContent = evidence.captured(checkpointFile);
                HistoryCheckpoint checkpoint = readHistoryCheckpoint(checkpointContent.bytes(), checkpointFile);
                Path stateFile = coordinatorRoot.resolve("state.json");
                Path projectFile = root.resolve("project.json");
                CapturedContent stateContent = evidence.captured(stateFile);
                CoordinatorState state = readState(stateContent.bytes(), stateFile);
                String stateIdentity = stateIdentity(stateContent.bytes(), stateFile);
                if (checkpoint.rootSequence() != state.rootSequence
                    || !checkpoint.stateHash().equals(StorageSafety.sha256(stateIdentity))) {
                    throw new IOException("checkpoint state mismatch");
                }
                Set<String> mutations = state.mutations.keySet().stream().map(UUID::toString)
                    .collect(Collectors.toUnmodifiableSet());
                long hashStarted = System.nanoTime();
                long hashedBefore = evidence.hashBudget().hashed;
                Map<String, CheckpointEvidence> currentEvidence = checkpointEvidence(root, evidence);
                checkpointPhase("content_hash", hashStarted, currentEvidence.size(), evidence.hashBudget().hashed - hashedBefore);
                String currentEvidenceHash = checkpointEvidenceHash(currentEvidence);
                long validationStarted = System.nanoTime();
                if (!mutations.equals(checkpoint.committedIndex().keySet())
                    || !checkpoint.genesisHash().equals(evidence.captured(coordinatorRoot.resolve("genesis.json")).hash())
                    || checkpoint.evidenceCount() != currentEvidence.size()
                    || !checkpoint.evidenceHash().equals(currentEvidenceHash)
                    || !checkpoint.legacyEvidence().isEmpty() && !checkpoint.legacyEvidence().equals(currentEvidence)) {
                    throw new IOException("checkpoint evidence mismatch");
                }
                AssetProjectMetadata metadata = readMetadata(evidence.contentOrNull(projectFile), projectFile);
                validatePair(true, evidence.isRegularFile(projectFile), state, metadata);
                ValidatedJournals validated = new ValidatedJournals(Set.copyOf(state.mutations.keySet()), Map.of(),
                    Set.of(), checkpoint.committedIndex().size(), -1L, null, Map.of(), evidence.genesis(), Map.of(),
                    new CheckpointTip(state.rootSequence, state, stateIdentity));
                validateDurableHistory(state, stateIdentity, validated);
                validateAssets(root, state, evidence);
                validateFinalAssetEvidence(state, evidence);
                validateFinalControlEvidence(evidence);
                validateFinalNamespaceEvidence(evidence);
                checkpointPhase("validation", validationStarted, evidence.paths().size(), 0L);
                long watchStarted = System.nanoTime();
                evidenceWatcher = EvidenceWatcher.openTrusted(startupWatcher, evidence, currentEvidence);
                checkpointPhase("evidence_promotion", watchStarted, evidenceWatcher.registeredPaths.size(), 0L);
                startupWatcher = null;
                requireUnchangedStartupNamespace(evidenceWatcher, "checkpoint watcher handoff");
                manager.trustCommittedIndex(checkpoint.committedIndex());
                EvidenceWatcher acceptedWatcher = evidenceWatcher;
                evidenceWatcher = null;
                return new CheckpointOpen(state, metadata, new HistoryValidation(validated), acceptedWatcher, checkpoint);
            } catch (IOException | RuntimeException failure) {
                TemporaryLifecycleDiagnostics.event("asset_history_checkpoint", checkpointStarted,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null,
                        "asset_coordinator", null, null, null, null, null, null), "outcome", "fallback",
                        "failure", failure.getClass().getSimpleName(), "reason", Objects.toString(failure.getMessage(), "")));
                return null;
            } finally {
                if (startupWatcher != null) {
                    try {
                        startupWatcher.close();
                    } catch (IOException ignored) {
                    }
                }
                if (evidenceWatcher != null) {
                    try {
                        evidenceWatcher.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private static void checkpointPhase(String phase, long started, long entries, long bytes) {
            TemporaryLifecycleDiagnostics.event("asset_history_checkpoint_phase", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null,
                    "asset_coordinator", null, null, null, null, null, null), "phase", phase,
                    "historyEntryCount", entries, "bytes", bytes));
        }

        private void persistHistoryCheckpoint() throws IOException {
            persistHistoryCheckpoint(stateIdentity(stateFile));
        }

        private void persistHistoryCheckpoint(String stateIdentity) throws IOException {
            long started = System.nanoTime();
            Map<String, AssetTransactionManager.CommittedIndexEntry> committedIndex = manager.committedIndexSnapshot();
            Set<String> mutations = state.mutations.keySet().stream().map(UUID::toString)
                .collect(Collectors.toUnmodifiableSet());
            if (!mutations.equals(committedIndex.keySet())) {
                throw new IOException("Asset history checkpoint commit index does not match durable mutation state");
            }
            Path genesisFile = coordinatorRoot.resolve("genesis.json");
            Map<String, CheckpointEvidence> evidence = evidenceWatcher.checkpointEvidence();
            HistoryCheckpoint checkpoint = new HistoryCheckpoint(state.rootSequence,
                StorageSafety.sha256(stateIdentity), StorageSafety.sha256(Files.readAllBytes(genesisFile)),
                committedIndex, evidence.size(), checkpointEvidenceHash(evidence), Map.of());
            String serialized = serializeHistoryCheckpoint(checkpoint);
            StorageSafety.writeUtf8Atomic(historyCheckpointFile, serialized);
            if (evidenceWatcher.acceptCheckpoint(historyCheckpointFile)) {
                throw new IOException("Asset history checkpoint changed during publication: "
                    + evidenceWatcher.changeReason());
            }
            checkpointPhase("publication", started, evidence.size(), serialized.getBytes(StandardCharsets.UTF_8).length);
        }

        private void validateAdoption(AdoptionInventory adoption, AdoptionBinding binding) throws IOException {
            if (evidenceWatcher.changed()) {
                throw new IOException("Asset transaction evidence changed before adoption validation: "
                    + evidenceWatcher.changeReason());
            }
            Genesis genesis = readGenesis(root);
            if (!genesis.provenance.equals(adoption.provenance)
                || !genesis.artifactHash.equals(binding.artifactHash)
                || !genesis.manifestHash.equals(binding.manifestHash)
                || !genesis.inventoryHash.equals(adoptionInventoryHash(root, adoption))) {
                throw new IOException("Asset adoption inventory does not match the active root authority");
            }
            if (state.rootSequence == 0L) {
                validateAdoptionProjectBytes(root, adoption);
            }
            HistoryEvidence historyBefore = captureHistoryEvidence(root);
            historyEvidenceScans++;
            validateInternalEvidence(root);
            ValidatedJournals validated = validateInspections(root, manager, manager.inspectTransactions(), gson);
            validateRecoveryEvidence(root, state, validated, adoption);
            validateDurableHistory(state, stateIdentity(stateFile), validated);
            validatePair(stateFile, projectFile, state, metadata);
            validateAssets(root, state, phaseTimings);
            HistoryEvidence historyAfter = captureHistoryEvidence(root);
            historyEvidenceScans++;
            if (!historyBefore.equals(historyAfter)) {
                throw new IOException("Asset transaction history changed during coordinator validation");
            }
            if (evidenceWatcher.changed()) {
                throw new IOException("Asset transaction evidence changed during adoption validation: "
                    + evidenceWatcher.changeReason());
            }
            fullValidationPasses++;
            historyValidation = new HistoryValidation(compactValidatedJournals(validated, state,
                stateIdentity(stateFile)));
            persistHistoryCheckpoint();
        }

        private TransactionResult transact(TransactionRequest request, JsonObject intentScope, Long expectedSequence) throws IOException {
            long started = System.nanoTime();
            long accountedBefore = phaseTimings.accountedNanos() + manager.timedCommitNanos();
            try {
                return transactNow(request, intentScope, expectedSequence);
            } finally {
                phaseTimings.finishOpenAttempts();
                long elapsed = elapsedNanos(started);
                long accounted = phaseTimings.accountedNanos() + manager.timedCommitNanos() - accountedBefore;
                phaseTimings.transactionAttemptCount++;
                phaseTimings.transactionNanos += elapsed;
                phaseTimings.residualNanos += Math.max(0L, elapsed - accounted);
            }
        }

        private TransactionResult transactNow(TransactionRequest request, JsonObject intentScope, Long expectedSequence) throws IOException {
            recoverPendingTransactions();
            NormalizedRequest normalized;
            long normalizationStarted = System.nanoTime();
            try {
                normalized = normalize(request, intentScope);
            } finally {
                phaseTimings.normalizationAttemptCount++;
                phaseTimings.normalizationNanos += elapsedNanos(normalizationStarted);
            }
            validateMetadataFile();
            validateAssets(root, state, phaseTimings);
            MutationRecord existing = state.mutations.get(request.mutationId());
            if (existing != null) {
                if (!existing.intentHash.equals(normalized.intentHash)) {
                    throw new MutationConflictException("Mutation ID was already committed with a different asset transaction: " + request.mutationId());
                }
                return existing.result.replayed();
            }
            if (expectedSequence != null && state.rootSequence != expectedSequence) {
                throw new StateConflictException("Asset state changed after validation", Map.of(), state.project);
            }
            if (!state.project.equals(request.expectedProject())) {
                throw new StateConflictException("Asset project metadata changed", Map.of(), state.project);
            }
            phaseTimings.beginStateWork();
            Map<Path, byte[]> writes = new LinkedHashMap<>();
            Set<Path> deletes = new LinkedHashSet<>();
            Map<AssetKey, ResourceEntry> nextResources = new LinkedHashMap<>(state.resources);
            Map<Path, AssetKey> pathOwners = new LinkedHashMap<>();
            state.resources.values().forEach(entry -> pathOwners.put(root.resolve(entry.path).normalize(), entry.key));
            Map<AssetKey, ExpectedState> conflicts = new LinkedHashMap<>();
            for (NormalizedAssetDelta delta : normalized.assets) {
                ResourceEntry tracked = state.resources.get(delta.key);
                String relativePath = root.relativize(delta.path).toString().replace('\\', '/');
                Path trackedPath = tracked == null ? null : root.resolve(tracked.path).normalize();
                boolean recreated = tracked != null && tracked.state instanceof Deleted
                    && tracked.state.equals(delta.expected) && !delta.deleted && delta.previousPath == null
                    && delta.lineageSource == null;
                Path currentPath = delta.previousPath != null ? delta.previousPath : recreated ? trackedPath : delta.path;
                if (tracked != null && !trackedPath.equals(currentPath)) {
                    throw new AssetPathConflictException("Asset key is already bound to a different path: " + delta.key.canonical());
                }
                if (delta.previousPath != null && (tracked == null || !(tracked.state instanceof Live))) {
                    throw new AssetPathConflictException("Asset relocation requires an exact current live source CAS: "
                        + delta.key.canonical());
                }
                AssetKey pathOwner = pathOwners.get(delta.path);
                if (pathOwner != null && !pathOwner.equals(delta.key)) {
                    throw new AssetPathConflictException("Asset path is already bound to a different key: " + relativePath);
                }
                if ((delta.previousPath != null || recreated) && Files.exists(delta.path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new AssetPathConflictException("Asset write target already exists: " + relativePath);
                }
                ExpectedState current = tracked != null ? validateTracked(tracked, currentPath) : discover(currentPath);
                if (!current.equals(delta.expected)) {
                    conflicts.put(delta.key, current);
                    continue;
                }
                long revision = Math.addExact(delta.lineageState != null
                    ? delta.lineageState.revision() : current.revision(), 1L);
                ExpectedState nextState;
                if (delta.deleted) {
                    String tombstoneHash = StorageSafety.sha256("deleted\n" + delta.key.canonical() + "\n" + revision + "\n" + request.mutationId());
                    nextState = new Deleted(revision, tombstoneHash);
                    deletes.add(delta.path);
                } else {
                    nextState = new Live(revision, StorageSafety.sha256(delta.content));
                    writes.put(delta.path, delta.content);
                    if (recreated) {
                        pathOwners.remove(trackedPath);
                    }
                    if (delta.previousPath != null) {
                        deletes.add(delta.previousPath);
                        pathOwners.remove(delta.previousPath);
                    }
                }
                nextResources.put(delta.key, new ResourceEntry(delta.key, relativePath, nextState,
                    AssetMutationId.of(request.mutationId())));
                pathOwners.put(delta.path, delta.key);
            }
            if (!conflicts.isEmpty()) {
                throw new StateConflictException("One or more assets changed", conflicts, state.project);
            }
            AssetProjectMetadata nextMetadata = metadata.apply(normalized.projectDeltas.stream().map(ProjectDelta::metadataDelta).toList());
            ExpectedProject nextProject = nextMetadata.hash().equals(metadata.hash())
                ? state.project
                : new ExpectedProject(Math.addExact(state.project.revision(), 1L), nextMetadata.hash());
            long nextSequence = Math.addExact(state.rootSequence, 1L);
            Map<AssetKey, ExpectedState> resultStates = new LinkedHashMap<>();
            for (NormalizedAssetDelta delta : normalized.assets) {
                resultStates.put(delta.key, nextResources.get(delta.key).state);
            }
            TransactionResult result = new TransactionResult(nextSequence, request.mutationId(), normalized.intentHash,
                resultStates, nextProject, false);
            Map<UUID, MutationRecord> nextMutations = new LinkedHashMap<>(state.mutations);
            nextMutations.put(request.mutationId(), new MutationRecord(normalized.intentHash, clock.instant(), result));
            CoordinatorState nextState = new CoordinatorState(nextSequence, nextProject, nextResources, nextMutations,
                state.blocked);
            writes.put(stateFile, serializeState(nextState, gson).getBytes(StandardCharsets.UTF_8));
            boolean projectExistedBefore = Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS);
            boolean projectWritten = false;
            if (nextMetadata != metadata) {
                writes.put(projectFile, nextMetadata.canonicalJson().getBytes(StandardCharsets.UTF_8));
                projectWritten = true;
            } else if (!Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS)) {
                writes.put(projectFile, metadata.canonicalJson().getBytes(StandardCharsets.UTF_8));
                projectWritten = true;
            }
            AssetTransactionManager.TransactionDescriptor baseDescriptor = manager.descriptor(Map.of(), writes, deletes);
            JsonObject binding = binding(request, normalized, result, nextMetadata, baseDescriptor, projectExistedBefore,
                projectWritten);
            Path bindingFile = coordinatorRoot.resolve("bindings").resolve(request.mutationId() + ".json");
            writes.put(bindingFile, AssetProjectMetadata.of(binding).canonicalJson().getBytes(StandardCharsets.UTF_8));
            phaseTimings.finishStateWork();
            HistoryValidation trustedHistory = historyValidation;
            if (trustedHistory == null || !trustedHistory.journals.preparedFingerprints.isEmpty()) {
                throw new IOException("Asset transaction history has no trusted append tip");
            }
            try {
                AssetTransactionManager.CommitResult committed = manager.commitInspected(Map.of(), writes, deletes,
                    request.mutationId().toString());
                if (committed.replay() || committed.inspection() == null) {
                    throw new IOException("Asset transaction manager returned an unexpected coordinator replay");
                }
                phaseTimings.beginAcceptedTip();
                ValidatedJournals appended = validateCommittedAppend(root, manager, trustedHistory.journals,
                    committed.inspection(), gson);
                PreparedTransition appendedTransition = appended.transitions.get(nextSequence);
                if (appendedTransition == null || !applyTransition(state, appendedTransition).equals(nextState)
                    || !appendedTransition.stateIdentity.equals(stateIdentity(stateFile))) {
                    throw new IOException("Incremental coordinator journal does not advance the trusted durable tip");
                }
                if (!appended.coordinatorJournals.equals(nextState.mutations.keySet())) {
                    throw new IOException("Incremental coordinator journal does not match durable mutation state");
                }
                Set<Path> expectedEvidence = new LinkedHashSet<>();
                expectedEvidence.add(root.resolve(".transactions").resolve(committed.transactionId()));
                expectedEvidence.add(root.resolve(".snapshots").resolve(committed.transactionId()));
                committed.inspection().visibility().descriptor().operations().stream()
                    .map(AssetTransactionManager.TransactionOperation::resource)
                    .filter(resource -> resource.startsWith(".asset-coordinator/"))
                    .map(root::resolve)
                    .forEach(expectedEvidence::add);
                if (evidenceWatcher.acceptCommit(expectedEvidence)) {
                    throw new IOException("Asset transaction evidence changed during the local commit: "
                        + evidenceWatcher.changeReason());
                }
                historyValidation = new HistoryValidation(compactValidatedJournals(appended, nextState,
                    appendedTransition.stateIdentity));
                state = nextState;
                metadata = nextMetadata;
                persistHistoryCheckpoint(appendedTransition.stateIdentity);
                phaseTimings.finishAcceptedTip();
                incrementalValidationPasses++;
                AssetTransactionManager.IoMetrics io = manager.ioMetrics();
                EvidenceWatcher.Metrics watcher = evidenceWatcher.metrics();
                TemporaryLifecycleDiagnostics.event("asset_history_tip", 0L,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "asset_coordinator",
                        request.mutationId().toString(), null, null, nextSequence, null, nextSequence),
                        "outcome", "advanced", "transactionId", committed.transactionId(),
                        "journalCount", appended.coordinatorJournals.size(), "validationMode", "incremental",
                        "fullValidationPasses", fullValidationPasses,
                        "incrementalValidationPasses", incrementalValidationPasses,
                        "managerFullJournalPasses", io.fullJournalPasses(), "managerJournalReads", io.journalReads(),
                        "managerStagedPayloadReads", io.stagedPayloadReads(),
                        "managerIndexedMutationLookups", io.indexedMutationLookups(),
                        "managerAssetPathCanonicalizations", io.assetPathCanonicalizations(),
                        "watcherFullRegistrationPasses", watcher.fullRegistrationPasses(),
                        "watcherIncrementalRegistrationPasses", watcher.incrementalRegistrationPasses(),
                        "watcherIncrementalDirectoryVisits", watcher.incrementalDirectoryVisits(),
                        "watcherRegisteredDirectories", watcher.registeredDirectories(),
                        "watcherAcceptedEvidencePaths", watcher.acceptedEvidencePaths(),
                        "watcherInvalidations", watcher.invalidations()));
            } catch (IOException failure) {
                phaseTimings.finishAcceptedTip();
                historyValidation = null;
                try {
                    reloadDurableState();
                    recoverPendingTransactions(true);
                    MutationRecord recovered = state.mutations.get(request.mutationId());
                    if (recovered != null && recovered.intentHash.equals(normalized.intentHash)) {
                        return recovered.result;
                    }
                } catch (IOException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
            state = nextState;
            metadata = nextMetadata;
            return result;
        }

        private void recoverPendingTransactions() throws IOException {
            recoverPendingTransactions(false);
        }

        private void recoverPendingTransactions(boolean force) throws IOException {
            long measurementStarted = System.nanoTime();
            long nestedBefore = phaseTimings.recoveryNestedNanos();
            try {
                recoverPendingTransactionsNow(force);
            } finally {
                long nested = phaseTimings.recoveryNestedNanos() - nestedBefore;
                phaseTimings.recoveryAttemptCount++;
                phaseTimings.recoveryNanos += Math.max(0L, elapsedNanos(measurementStarted) - nested);
            }
        }

        private void recoverPendingTransactionsNow(boolean force) throws IOException {
            long started = TemporaryLifecycleDiagnostics.start();
            Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(null, "asset_coordinator", null,
                null, null, state.rootSequence, null, state.rootSequence);
            HistoryValidation cached = historyValidation;
            boolean evidenceChanged = cached != null && evidenceWatcher.changed();
            if (cached != null && evidenceChanged) {
                String evidenceChange = evidenceWatcher.changeReason();
                TemporaryLifecycleDiagnostics.event("asset_history_tip", started,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", "rejected", "validationMode", "terminal",
                        "reason", "external_evidence", "evidenceChange", evidenceChange));
                throw new IOException("Asset transaction evidence changed outside the coordinator: " + evidenceChange);
            }
            if (!force && cached != null && cached.journals.preparedFingerprints.isEmpty()) {
                TemporaryLifecycleDiagnostics.event("asset_recovery", started,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", "trusted_tip",
                        "mutationCount", state.mutations.size(), "journalCount",
                        cached.journals.coordinatorJournals.size(), "preparedCount", 0));
                return;
            }
            TemporaryLifecycleDiagnostics.event("asset_history_tip", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "invalidated", "validationMode", "full",
                    "reason", force ? "explicit" : cached == null ? "untrusted" : "prepared", "evidenceChange", ""));
            fullValidationPasses++;
            long phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "captureHistoryBefore", "outcome", "started"));
            HistoryEvidence historyBefore = captureHistoryEvidence(root);
            historyEvidenceScans++;
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "captureHistoryBefore", "outcome", "complete",
                    "historyEntryCount", historyBefore.entries().size()));
            phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateJournals", "outcome", "started"));
            validateJournalsBeforeRecovery(root);
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateJournals", "outcome", "complete"));
            phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateInternalEvidence", "outcome", "started"));
            validateInternalEvidence(root);
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateInternalEvidence", "outcome", "complete"));
            phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "inspectTransactions", "outcome", "started"));
            var inspections = manager.inspectTransactions();
            ValidatedJournals validated = validateInspections(root, manager, inspections, gson);
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "inspectTransactions", "outcome", "complete",
                    "inspectionCount", inspections.size(), "journalCount", validated.coordinatorJournals.size(),
                    "preparedCount", validated.preparedFingerprints.size()));
            phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateRecoveryEvidence", "outcome", "started"));
            validateRecoveryEvidence(root, state, validated);
            validateDurableHistory(state, validated);
            boolean durableStateExists = Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS);
            CoordinatorState durableState = durableStateExists ? readState(stateFile, gson) : CoordinatorState.empty();
            validateDurableHistory(durableState, durableStateExists ? stateIdentity(stateFile) : null, validated);
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "validateRecoveryEvidence", "outcome", "complete",
                    "durableMutationCount", durableState.mutations.size()));
            if (!validated.preparedFingerprints.isEmpty()) {
                long preparedStarted = TemporaryLifecycleDiagnostics.start();
                TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                    TemporaryLifecycleDiagnostics.with(identity, "phase", "recoverPrepared", "outcome", "started",
                        "preparedCount", validated.preparedFingerprints.size()));
                if (state.rootSequence != validated.preparedSequenceBefore
                    && state.rootSequence != Math.addExact(validated.preparedSequenceBefore, 1L)) {
                    throw new IOException("Prepared coordinator journal does not continue active root sequence");
                }
                manager.recoverValidated(validated.preparedFingerprints);
                historyBefore = captureHistoryEvidence(root);
                validateInternalEvidence(root);
                reloadDurableState();
                validated = validateInspections(root, manager, manager.inspectTransactions(), gson);
                TemporaryLifecycleDiagnostics.event("asset_recovery_phase", preparedStarted,
                    TemporaryLifecycleDiagnostics.with(identity, "phase", "recoverPrepared", "outcome", "complete",
                        "preparedCount", validated.preparedFingerprints.size(), "journalCount", validated.coordinatorJournals.size()));
            }
            phaseStarted = TemporaryLifecycleDiagnostics.start();
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", 0L,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "captureHistoryAfter", "outcome", "started"));
            validateDurableHistory(state, Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                ? stateIdentity(stateFile) : null, validated);
            if (!validated.coordinatorJournals.equals(state.mutations.keySet())) {
                throw new IOException("Coordinator journals do not match active mutation state");
            }
            HistoryEvidence historyAfter = captureHistoryEvidence(root);
            historyEvidenceScans++;
            if (!historyBefore.equals(historyAfter)) {
                throw new IOException("Asset transaction history changed during coordinator validation");
            }
            TemporaryLifecycleDiagnostics.event("asset_recovery_phase", phaseStarted,
                TemporaryLifecycleDiagnostics.with(identity, "phase", "captureHistoryAfter", "outcome", "complete",
                    "historyEntryCount", historyAfter.entries().size()));
            evidenceWatcher.refresh();
            historyValidation = new HistoryValidation(compactValidatedJournals(validated, state,
                stateIdentity(stateFile)));
            persistHistoryCheckpoint();
            TemporaryLifecycleDiagnostics.event("asset_recovery", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "validated", "mutationCount", state.mutations.size(),
                    "journalCount", validated.coordinatorJournals.size(), "preparedCount", validated.preparedFingerprints.size()));
        }

        private JsonObject binding(TransactionRequest request, NormalizedRequest normalized, TransactionResult result,
                                   AssetProjectMetadata nextMetadata,
                                   AssetTransactionManager.TransactionDescriptor baseDescriptor,
                                   boolean projectExistedBefore,
                                   boolean projectWritten) {
            JsonObject binding = new JsonObject();
            binding.addProperty("format", "asset-coordinator-binding-v1");
            binding.addProperty("mutationId", request.mutationId().toString());
            binding.addProperty("intentHash", normalized.intentHash);
            binding.add("intent", normalized.intent());
            binding.addProperty("sequenceBefore", state.rootSequence);
            binding.addProperty("sequenceAfter", result.rootSequence());
            binding.add("projectBefore", metadata.document());
            binding.addProperty("projectBeforeHash", metadata.hash());
            binding.add("projectAfter", nextMetadata.document());
            binding.addProperty("projectAfterHash", nextMetadata.hash());
            binding.addProperty("projectExistedBefore", projectExistedBefore);
            binding.addProperty("projectWritten", projectWritten);
            binding.addProperty("baseFingerprint", baseDescriptor.fingerprint());
            JsonArray operations = new JsonArray();
            for (AssetTransactionManager.TransactionOperation operation : baseDescriptor.operations()) {
                operations.add(operationJson(operation));
            }
            binding.add("baseOperations", operations);
            return binding;
        }

        private NormalizedRequest normalize(TransactionRequest request, JsonObject intentScope) throws IOException {
            if (request.assets().isEmpty() && request.projectDeltas().isEmpty()) {
                throw new IllegalArgumentException("Asset transaction must contain at least one delta");
            }
            List<NormalizedAssetDelta> assets = new ArrayList<>();
            Set<AssetKey> keys = new LinkedHashSet<>();
            Set<Path> paths = new LinkedHashSet<>();
            for (AssetDelta delta : request.assets()) {
                Path path = requireAssetPath(delta.path());
                Path previousPath = delta.previousPath() != null ? requireAssetPath(delta.previousPath()) : null;
                if (previousPath != null && previousPath.equals(path)) {
                    throw new IllegalArgumentException("Asset relocation source and target paths are identical");
                }
                if (!keys.add(delta.key())) {
                    throw new IllegalArgumentException("Asset transaction contains duplicate key: " + delta.key().canonical());
                }
                if (!paths.add(path)) {
                    throw new IllegalArgumentException("Asset transaction contains duplicate path: " + path);
                }
                if (previousPath != null && !paths.add(previousPath)) {
                    throw new IllegalArgumentException("Asset transaction contains duplicate relocation path: " + previousPath);
                }
                assets.add(new NormalizedAssetDelta(delta.key(), path, previousPath, delta.expected(), delta.content(),
                    delta.deleted(), delta.lineageSource(), delta.lineageState(), delta.transformedRelocation()));
            }
            for (NormalizedAssetDelta delta : assets) {
                if (delta.lineageSource == null) {
                    continue;
                }
                NormalizedAssetDelta source = assets.stream().filter(candidate -> candidate.key.equals(delta.lineageSource))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Asset reclassification source is missing: " + delta.lineageSource.canonical()));
                if (!source.deleted || !source.expected.equals(delta.lineageState)) {
                    throw new IllegalArgumentException("Asset reclassification source CAS does not match its target lineage");
                }
            }
            assets.sort(Comparator.comparing(delta -> delta.key.canonical()));
            List<ProjectDelta> projectDeltas = new ArrayList<>(request.projectDeltas());
            Set<List<String>> projectPaths = new LinkedHashSet<>();
            for (ProjectDelta delta : projectDeltas) {
                if (!projectPaths.add(delta.path())) {
                    throw new IllegalArgumentException("Asset transaction contains duplicate project metadata path: " + String.join("/", delta.path()));
                }
                for (List<String> existing : projectPaths) {
                    if (existing != delta.path() && (isPrefix(existing, delta.path()) || isPrefix(delta.path(), existing))) {
                        throw new IllegalArgumentException("Asset transaction contains overlapping project metadata paths");
                    }
                }
            }
            projectDeltas.sort((left, right) -> comparePaths(left.path(), right.path()));
            JsonObject intent = new JsonObject();
            intent.addProperty("format", INTENT_FORMAT);
            intent.addProperty("mutationId", request.mutationId().toString());
            if (intentScope != null && !intentScope.entrySet().isEmpty()) {
                intent.add("scope", intentScope.deepCopy());
            }
            intent.add("expectedProject", expectedProjectJson(request.expectedProject()));
            JsonArray assetArray = new JsonArray();
            for (NormalizedAssetDelta delta : assets) {
                JsonObject item = new JsonObject();
                item.addProperty("key", delta.key.canonical());
                item.addProperty("type", delta.key.type());
                item.addProperty("id", delta.key.id());
                item.addProperty("path", root.relativize(delta.path).toString().replace('\\', '/'));
                if (delta.previousPath != null) {
                    item.addProperty("previousPath", root.relativize(delta.previousPath).toString().replace('\\', '/'));
                }
                item.add("expected", expectedStateJson(delta.expected));
                item.addProperty("operation", delta.previousPath != null
                    ? delta.transformedRelocation ? "RELOCATE_TRANSFORM" : "RELOCATE"
                    : delta.lineageSource != null ? "RECLASSIFY" : delta.deleted ? "DELETE" : "WRITE");
                if (delta.lineageSource != null) {
                    item.addProperty("lineageSourceKey", delta.lineageSource.canonical());
                    item.addProperty("lineageSourceType", delta.lineageSource.type());
                    item.addProperty("lineageSourceId", delta.lineageSource.id());
                    item.add("lineageSourceState", expectedStateJson(delta.lineageState));
                }
                item.addProperty("payloadHash", delta.deleted ? "" : StorageSafety.sha256(delta.content));
                item.addProperty("payloadSize", delta.deleted ? 0L : delta.content.length);
                assetArray.add(item);
            }
            intent.add("assets", assetArray);
            JsonArray projectArray = new JsonArray();
            for (ProjectDelta delta : projectDeltas) {
                JsonObject item = new JsonObject();
                JsonArray path = new JsonArray();
                delta.path().forEach(path::add);
                item.add("path", path);
                item.addProperty("operation", delta.remove() ? "REMOVE" : "SET");
                if (!delta.remove()) {
                    item.add("value", delta.value());
                }
                projectArray.add(item);
            }
            intent.add("projectDeltas", projectArray);
            String intentHash = StorageSafety.sha256(AssetProjectMetadata.of(intent).canonicalJson());
            return new NormalizedRequest(intentHash, intent.deepCopy(), List.copyOf(assets), List.copyOf(projectDeltas));
        }

        private static int comparePaths(List<String> left, List<String> right) {
            int shared = Math.min(left.size(), right.size());
            for (int index = 0; index < shared; index++) {
                int compared = left.get(index).compareTo(right.get(index));
                if (compared != 0) {
                    return compared;
                }
            }
            return Integer.compare(left.size(), right.size());
        }

        private static boolean isPrefix(List<String> prefix, List<String> path) {
            return prefix.size() < path.size() && prefix.equals(path.subList(0, prefix.size()));
        }

        private Path requireAssetPath(Path requested) throws IOException {
            if (requested == null) {
                throw new IOException("Asset transaction path is required");
            }
            if (requested.toString().chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Asset transaction path contains control characters");
            }
            Path path = requested.isAbsolute() ? requested.toAbsolutePath().normalize() : root.resolve(requested).normalize();
            Path transactions = root.resolve(".transactions");
            Path snapshots = root.resolve(".snapshots");
            if (!path.startsWith(root) || path.equals(root) || path.startsWith(coordinatorRoot)
                || path.startsWith(transactions) || path.startsWith(snapshots) || path.equals(projectFile)) {
                throw new IOException("Asset transaction path escapes the managed asset root: " + requested);
            }
            Path existing = path;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || Files.isSymbolicLink(existing)) {
                throw new IOException("Asset transaction path has an unsafe ancestor: " + requested);
            }
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(root)) {
                throw new IOException("Asset transaction path has an unsafe ancestor: " + requested);
            }
            MigrationPaths.requireNoSymlinkTraversal(root, existing);
            return realExisting.resolve(existing.relativize(path)).normalize();
        }

        private ExpectedState discover(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return Missing.INSTANCE;
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset transaction target is not a safe regular file: " + path);
            }
            return new Live(0L, hashAsset(path));
        }

        private ExpectedState validateTracked(ResourceEntry tracked, Path path) throws IOException {
            if (tracked.state instanceof Live live) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !live.hash().equals(hashAsset(path))) {
                    throw new IOException("Live asset does not match coordinator state: " + tracked.key.canonical());
                }
            } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Deleted asset exists on disk: " + tracked.key.canonical());
            }
            return tracked.state;
        }

        private String hashAsset(Path path) throws IOException {
            long started = System.nanoTime();
            try {
                return StorageSafety.sha256(Files.readAllBytes(path));
            } finally {
                phaseTimings.trackedAssetHashAttemptCount++;
                phaseTimings.trackedAssetHashNanos += elapsedNanos(started);
            }
        }

        private void validateMetadataFile() throws IOException {
            long started = System.nanoTime();
            try {
                validateMetadataFileNow();
            } finally {
                phaseTimings.metadataValidationAttemptCount++;
                phaseTimings.metadataValidationNanos += elapsedNanos(started);
            }
        }

        private void validateMetadataFileNow() throws IOException {
            boolean projectExists = Files.exists(projectFile, LinkOption.NOFOLLOW_LINKS);
            if ((projectExists && (!Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(projectFile) || !metadata.hash().equals(readMetadata(projectFile).hash())))
                || (!projectExists && (state.rootSequence != 0L || !metadata.hash().equals(AssetProjectMetadata.empty().hash())))) {
                throw new IOException("Asset project metadata changed outside the coordinator");
            }
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                if (state.rootSequence == 0L && state.resources.isEmpty() && state.mutations.isEmpty()
                    && state.blocked.isEmpty()) {
                    return;
                }
                throw new IOException("Asset coordinator state disappeared from the active root context");
            }
            CoordinatorState durable = readState(stateFile, gson);
            if (durable.rootSequence != state.rootSequence || !durable.project.equals(state.project)
                || !durable.resources.equals(state.resources) || !durable.mutations.equals(state.mutations)
                || !durable.blocked.equals(state.blocked)) {
                throw new IOException("Asset coordinator state changed outside the active root context");
            }
        }

        private void reloadDurableState() throws IOException {
            CoordinatorState durable = readState(stateFile, gson);
            AssetProjectMetadata durableMetadata = readMetadata(projectFile);
            validatePair(stateFile, projectFile, durable, durableMetadata);
            validateAssets(root, durable, phaseTimings);
            state = durable;
            metadata = durableMetadata;
        }

        private void healthCheck() throws IOException {
            recoverPendingTransactions(false);
            validateMetadataFile();
            validateAssets(root, state, phaseTimings);
        }

        private void flush() throws IOException {
            healthCheck();
            StorageSafety.forceDirectory(root);
            StorageSafety.forceDirectory(coordinatorRoot);
            StorageSafety.forceDirectory(coordinatorRoot.resolve("bindings"));
            StorageSafety.forceDirectory(root.resolve(".transactions"));
            StorageSafety.forceDirectory(root.resolve(".snapshots"));
        }

        private Optional<MutationView> mutation(UUID mutationId) throws IOException {
            MutationRecord mutation = state.mutations.get(mutationId);
            if (mutation == null) {
                return Optional.empty();
            }
            Path bindingFile = coordinatorRoot.resolve("bindings").resolve(mutationId + ".json");
            if (Files.isSymbolicLink(bindingFile) || !Files.isRegularFile(bindingFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset transaction binding is missing: " + mutationId);
            }
            JsonObject binding = parseObject(Files.readAllBytes(bindingFile), "Asset transaction binding");
            JsonObject intent = binding.getAsJsonObject("intent");
            if (!mutationId.toString().equals(requiredString(binding, "mutationId"))
                || !mutation.intentHash.equals(requiredHash(binding, "intentHash")) || intent == null
                || !mutation.intentHash.equals(StorageSafety.sha256(AssetProjectMetadata.of(intent).canonicalJson()))) {
                throw new IOException("Asset transaction binding no longer matches durable history: " + mutationId);
            }
            AssetProjectMetadata projectAfter = AssetProjectMetadata.of(binding.getAsJsonObject("projectAfter"));
            String projectAfterHash = requiredHash(binding, "projectAfterHash");
            boolean projectWritten = binding.get("projectWritten").getAsBoolean();
            if (!projectAfterHash.equals(mutation.result.project().hash())
                || projectWritten && !projectAfter.hash().equals(projectAfterHash)
                || !projectWritten && (!requiredHash(binding, "projectBeforeHash").equals(projectAfterHash)
                    || !projectAfter.document().equals(binding.getAsJsonObject("projectBefore")))) {
                throw new IOException("Asset transaction project snapshot no longer matches durable history: " + mutationId);
            }
            return Optional.of(new MutationView(mutationId, mutation.intentHash, intent, mutation.result, projectAfter));
        }

        private void notifyListeners(TransactionResult result) {
            for (PostCommitListener listener : listeners) {
                try {
                    listener.committed(result);
                } catch (Throwable failure) {
                    listenerFailures.add(new ListenerFailure(result.rootSequence(), listener.getClass().getName(), failure));
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                processLock.release();
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                lockChannel.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                evidenceWatcher.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class EvidenceWatcher implements AutoCloseable {
        private final Path root;
        private final WatchService service;
        private boolean normalizeDirectoryMetadata;
        private final Map<WatchKey, Path> directories = new LinkedHashMap<>();
        private final Set<Path> registeredPaths = new LinkedHashSet<>();
        private Map<Path, EvidenceIdentity> accepted = Map.of();
        private long fullRegistrationPasses;
        private long fullDirectoryWalks;
        private long incrementalRegistrationPasses;
        private long incrementalDirectoryVisits;
        private long invalidations;
        private String changeReason = "";
        private String poisonReason = "";

        private EvidenceWatcher(Path root, WatchService service, boolean normalizeDirectoryMetadata) {
            this.root = root;
            this.service = service;
            this.normalizeDirectoryMetadata = normalizeDirectoryMetadata;
        }

        private static EvidenceWatcher open(Path root, EvidenceSnapshot evidence) throws IOException {
            EvidenceWatcher watcher = new EvidenceWatcher(root, root.getFileSystem().newWatchService(), false);
            try {
                watcher.fullRegistrationPasses++;
                watcher.registerEvidenceDirectories();
                watcher.acceptSnapshot(evidence);
                return watcher;
            } catch (IOException | RuntimeException failure) {
                try {
                    watcher.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private static EvidenceWatcher openTrusted(EvidenceWatcher watcher, EvidenceSnapshot evidence,
                                                   Map<String, CheckpointEvidence> trusted) throws IOException {
            Path root = watcher.root;
            try {
                watcher.fullRegistrationPasses++;
                Map<Path, EvidenceIdentity> accepted = new LinkedHashMap<>();
                for (Map.Entry<String, CheckpointEvidence> entry : trusted.entrySet()) {
                    Path path = root.resolve(entry.getKey()).toAbsolutePath().normalize();
                    if (!path.startsWith(root)) {
                        throw new IOException("Asset history checkpoint path escapes its root: " + entry.getKey());
                    }
                    accepted.put(path, entry.getValue().identity(evidence.path(path)));
                }
                Path checkpointFile = root.resolve(".asset-coordinator").resolve(HISTORY_CHECKPOINT_FILE);
                EvidencePath checkpointPath = evidence.path(checkpointFile);
                CapturedContent checkpointContent = evidence.captured(checkpointFile);
                accepted.put(checkpointFile, evidenceIdentity(checkpointPath, checkpointContent.hash()));
                Map<Path, EvidenceIdentity> fenced = new LinkedHashMap<>(watcher.accepted);
                fenced.putAll(accepted);
                watcher.accepted = Map.copyOf(fenced);
                requireUnchangedStartupNamespace(watcher, "checkpoint promotion");
                watcher.accepted = Map.copyOf(accepted);
                watcher.normalizeDirectoryMetadata = false;
                for (Map.Entry<WatchKey, Path> entry : List.copyOf(watcher.directories.entrySet())) {
                    if (!watcher.isEvidencePath(entry.getValue())) {
                        entry.getKey().cancel();
                        watcher.directories.remove(entry.getKey());
                        watcher.registeredPaths.remove(entry.getValue());
                    }
                }
                return watcher;
            } catch (IOException | RuntimeException failure) {
                try {
                    watcher.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private static EvidenceWatcher openNamespace(Path root, EvidenceSnapshot evidence) throws IOException {
            EvidenceWatcher watcher = new EvidenceWatcher(root, root.getFileSystem().newWatchService(), true);
            try {
                for (Map.Entry<Path, EvidencePath> entry : evidence.paths().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                    if (entry.getValue().directory() && !entry.getValue().symbolicLink()) {
                        watcher.registerDirectory(entry.getKey(), false);
                    }
                }
                return watcher;
            } catch (IOException | RuntimeException failure) {
                try {
                    watcher.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private boolean changed() throws IOException {
            if (!poisonReason.isEmpty()) {
                changeReason = poisonReason;
                return true;
            }
            boolean changed = false;
            changeReason = "";
            EvidenceHashBudget hashBudget = new EvidenceHashBudget(HASHED_EVIDENCE_FILE_LIMIT,
                HASHED_EVIDENCE_TOTAL_LIMIT);
            try {
                WatchKey key = service.poll();
                if (key == null) {
                    try {
                        key = service.poll(2L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while checking asset transaction evidence", failure);
                    }
                }
                while (key != null) {
                    Path directory = directories.get(key);
                    if (directory == null) {
                        changed |= invalidate("unknown_watch_key", null);
                    } else {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                changed |= invalidate("overflow", directory);
                                continue;
                            }
                            Object context = event.context();
                            if (!(context instanceof Path relative)) {
                                changed |= invalidate("invalid_context", directory);
                                continue;
                            }
                            Path path = directory.resolve(relative).toAbsolutePath().normalize();
                            EvidenceIdentity expected = accepted.get(path);
                            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                                if (!isAtomicTemporary(path)) {
                                    changed |= invalidate("missing", path);
                                }
                                continue;
                            }
                            if (normalizeDirectoryMetadata && event.kind() == StandardWatchEventKinds.ENTRY_MODIFY
                                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                                continue;
                            }
                            if (expected == null) {
                                changed |= invalidate("unexpected", path);
                            } else {
                                try {
                                    if (!expected.equals(identity(path, hashBudget, !expected.hash().isEmpty()))) {
                                        changed |= invalidate("identity", path);
                                    }
                                } catch (IOException failure) {
                                    changed |= invalidate("identity_read", path);
                                }
                            }
                        }
                    }
                    if (!key.reset()) {
                        Path removed = directories.remove(key);
                        if (removed != null) {
                            registeredPaths.remove(removed);
                        }
                        changed |= invalidate("key_loss", removed);
                    }
                    key = service.poll();
                }
            } catch (ClosedWatchServiceException failure) {
                throw new IOException("Asset transaction evidence watcher is closed", failure);
            }
            return changed;
        }

        private boolean acceptCommit(Set<Path> expected) throws IOException {
            Set<Path> acceptedPaths = new LinkedHashSet<>();
            Set<Path> registrationCandidates = new LinkedHashSet<>();
            List<Path> evidenceRoots = List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
                root.resolve(".asset-coordinator"));
            for (Path path : expected) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(root) || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalized)) {
                    throw new IOException("Unsafe locally committed transaction evidence: " + path);
                }
                boolean tree = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    && (normalized.getParent().equals(evidenceRoots.get(0))
                    || normalized.getParent().equals(evidenceRoots.get(1)));
                if (tree) {
                    try (Stream<Path> paths = Files.walk(normalized)) {
                        for (Path evidence : paths.toList()) {
                            if (Files.isSymbolicLink(evidence)) {
                                throw new IOException("Symbolic link in locally committed transaction evidence: " + evidence);
                            }
                            Path absolute = evidence.toAbsolutePath().normalize();
                            acceptedPaths.add(absolute);
                            if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                                registrationCandidates.add(absolute);
                            }
                        }
                    }
                } else {
                    acceptedPaths.add(normalized);
                    Path candidate = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                        ? normalized : normalized.getParent();
                    if (candidate != null) {
                        registrationCandidates.add(candidate);
                    }
                }
                for (Path evidenceRoot : evidenceRoots) {
                    if (!normalized.startsWith(evidenceRoot)) {
                        continue;
                    }
                    Path parent = normalized.getParent();
                    while (parent != null && parent.startsWith(evidenceRoot)) {
                        acceptedPaths.add(parent);
                        parent = parent.getParent();
                    }
                }
            }
            Map<Path, EvidenceIdentity> nextAccepted = new LinkedHashMap<>();
            EvidenceHashBudget hashBudget = new EvidenceHashBudget(HASHED_EVIDENCE_FILE_LIMIT,
                HASHED_EVIDENCE_TOTAL_LIMIT);
            for (Path path : acceptedPaths) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(root) || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                nextAccepted.put(normalized, identity(normalized, hashBudget, true));
            }
            Map<Path, EvidenceIdentity> updated = new LinkedHashMap<>(accepted);
            updated.putAll(nextAccepted);
            accepted = Map.copyOf(updated);
            incrementalRegistrationPasses++;
            for (Path directory : registrationCandidates) {
                if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    registerDirectory(directory, true);
                }
            }
            return changed();
        }

        private boolean acceptCheckpoint(Path checkpoint) throws IOException {
            Path normalized = checkpoint.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (!normalized.equals(root.resolve(".asset-coordinator").resolve(HISTORY_CHECKPOINT_FILE))
                || parent == null || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
                throw new IOException("Asset history checkpoint publication is unsafe: " + checkpoint);
            }
            EvidenceHashBudget hashBudget = new EvidenceHashBudget(HASHED_EVIDENCE_FILE_LIMIT,
                HASHED_EVIDENCE_TOTAL_LIMIT);
            Map<Path, EvidenceIdentity> updated = new LinkedHashMap<>(accepted);
            updated.put(parent, identity(parent, hashBudget, false));
            updated.put(normalized, identity(normalized, hashBudget, true));
            accepted = Map.copyOf(updated);
            return changed();
        }

        private void acceptSnapshot(EvidenceSnapshot evidence) throws IOException {
            Map<Path, String> hashes = new LinkedHashMap<>();
            evidence.contents.forEach((path, content) -> hashes.put(path, content.hash()));
            for (AssetTransactionManager.TransactionInspection inspection : evidence.inspections) {
                AssetTransactionManager.JournalEvidence journal = inspection.journalEvidence();
                hashes.put(journal.journalPath(), journal.journalIdentity().hash());
                for (AssetTransactionManager.JournalEntryEvidence entry : journal.entries()) {
                    if (entry.stagedIdentity() != null) {
                        hashes.put(journal.transactionDirectory().resolve(entry.staged()).normalize(),
                            entry.stagedIdentity().hash());
                    }
                }
            }
            List<Path> evidenceRoots = List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
                root.resolve(".asset-coordinator"));
            Map<Path, EvidenceIdentity> captured = new LinkedHashMap<>();
            for (Map.Entry<Path, EvidencePath> entry : evidence.paths.entrySet()) {
                Path path = entry.getKey().toAbsolutePath().normalize();
                if (evidenceRoots.stream().noneMatch(path::startsWith)) {
                    continue;
                }
                EvidencePath evidencePath = entry.getValue();
                String hash = evidencePath.regularFile() ? hashes.get(path) : "";
                if (evidencePath.regularFile() && hash == null) {
                    if (path.equals(root.resolve(".asset-coordinator/root.lock"))) {
                        captured.put(path, evidenceIdentity(evidencePath, ""));
                    } else {
                        throw new IOException("Validated asset evidence has no captured content identity: " + path);
                    }
                } else {
                    captured.put(path, evidenceIdentity(evidencePath, hash));
                }
            }
            accepted = Map.copyOf(captured);
        }

        private Map<String, CheckpointEvidence> checkpointEvidence() throws IOException {
            Path checkpointFile = root.resolve(".asset-coordinator").resolve(HISTORY_CHECKPOINT_FILE);
            Map<String, CheckpointEvidence> checkpoint = new LinkedHashMap<>();
            for (Map.Entry<Path, EvidenceIdentity> entry : accepted.entrySet()) {
                Path path = entry.getKey();
                if (path.equals(checkpointFile)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                CheckpointEvidence evidence = CheckpointEvidence.from(entry.getValue());
                if (evidence.regularFile() && evidence.hash().isBlank()
                    && !relative.equals(".asset-coordinator/root.lock")) {
                    throw new IOException("Trusted asset evidence has no content hash: " + relative);
                }
                checkpoint.put(relative, evidence);
            }
            return Map.copyOf(checkpoint);
        }

        private void refresh() throws IOException {
            if (!poisonReason.isEmpty()) {
                throw new IOException("Asset transaction evidence watcher is poisoned: " + poisonReason);
            }
            removeInvalidRegistrations();
            fullRegistrationPasses++;
            registerEvidenceDirectories();
            Map<Path, EvidenceIdentity> previous = accepted;
            Map<Path, EvidenceIdentity> refreshed = captureAcceptedEvidence();
            accepted = Map.copyOf(refreshed);
            try {
                if (changed()) {
                    throw new IOException("Asset transaction evidence changed during watcher refresh: " + changeReason());
                }
            } catch (IOException | RuntimeException failure) {
                accepted = previous;
                throw failure;
            }
        }

        private Map<Path, EvidenceIdentity> captureAcceptedEvidence() throws IOException {
            Map<Path, EvidenceIdentity> captured = new LinkedHashMap<>();
            EvidencePathBudget pathBudget = new EvidencePathBudget(EVIDENCE_MAX_PATHS);
            EvidenceHashBudget hashBudget = new EvidenceHashBudget(HASHED_EVIDENCE_FILE_LIMIT,
                HASHED_EVIDENCE_TOTAL_LIMIT);
            for (Path evidenceRoot : List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
                root.resolve(".asset-coordinator"))) {
                if (!Files.isDirectory(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Files.walkFileTree(evidenceRoot, Set.of(), EVIDENCE_MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                        captureAcceptedPath(directory, attributes, captured, pathBudget, hashBudget);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        captureAcceptedPath(file, attributes, captured, pathBudget, hashBudget);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return Map.copyOf(captured);
        }

        private void captureAcceptedPath(Path path, BasicFileAttributes attributes,
                                         Map<Path, EvidenceIdentity> captured, EvidencePathBudget pathBudget,
                                         EvidenceHashBudget hashBudget) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
                throw new IOException("Symbolic link in asset transaction evidence: " + path);
            }
            Path normalized = path.toAbsolutePath().normalize();
            pathBudget.visit(root, normalized);
            EvidenceIdentity metadata = evidenceIdentity(EvidencePath.of(attributes), "");
            EvidenceIdentity previous = accepted.get(normalized);
            if (previous != null && previous.sameMetadata(metadata)) {
                captured.put(normalized, previous);
            } else if (!metadata.regularFile() || normalized.equals(root.resolve(".asset-coordinator/root.lock"))) {
                captured.put(normalized, metadata);
            } else {
                captured.put(normalized, identity(normalized, hashBudget, true));
            }
        }

        private Metrics metrics() {
            return new Metrics(fullRegistrationPasses, incrementalRegistrationPasses, incrementalDirectoryVisits,
                registeredPaths.size(), accepted.size(), invalidations, fullDirectoryWalks);
        }

        private String changeReason() {
            return changeReason;
        }

        private boolean isEvidencePath(Path path) {
            return path.startsWith(root.resolve(".transactions")) || path.startsWith(root.resolve(".snapshots"))
                || path.startsWith(root.resolve(".asset-coordinator"));
        }

        private void registerEvidenceDirectories() throws IOException {
            fullDirectoryWalks++;
            for (Path evidenceRoot : List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
                root.resolve(".asset-coordinator"))) {
                if (!Files.isDirectory(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                registerTree(evidenceRoot, false);
            }
        }

        private void registerTree(Path start, boolean incremental) throws IOException {
            try (Stream<Path> paths = Files.walk(start)) {
                for (Path directory : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path)).toList()) {
                    registerDirectory(directory, incremental);
                }
            }
        }

        private void registerDirectory(Path directory, boolean incremental) throws IOException {
            Path normalized = directory.toAbsolutePath().normalize();
            if (incremental) {
                incrementalDirectoryVisits++;
            }
            if (registeredPaths.contains(normalized)) {
                return;
            }
            WatchKey key = normalized.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            registeredPaths.add(normalized);
            directories.put(key, normalized);
        }

        private void removeInvalidRegistrations() {
            for (Map.Entry<WatchKey, Path> entry : List.copyOf(directories.entrySet())) {
                if (entry.getKey().isValid()) {
                    continue;
                }
                directories.remove(entry.getKey());
                registeredPaths.remove(entry.getValue());
            }
        }

        private static EvidenceIdentity identity(Path path, EvidenceHashBudget hashBudget, boolean hashContent)
            throws IOException {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            String hash = before.isRegularFile() && hashContent ? streamHash(path, before.size(), hashBudget) : "";
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!EvidencePath.of(before).equals(EvidencePath.of(after))) {
                throw new IOException("Asset transaction evidence changed during identity capture: " + path);
            }
            return new EvidenceIdentity(before.isDirectory(), before.isRegularFile(), before.size(), hash,
                before.lastModifiedTime(), before.creationTime(), Objects.toString(before.fileKey(), ""));
        }

        private static EvidenceIdentity evidenceIdentity(EvidencePath path, String hash) {
            return new EvidenceIdentity(path.directory(), path.regularFile(), path.size(), hash, path.modifiedAt(),
                path.createdAt(), path.fileKey());
        }

        private boolean invalidate(String reason, Path path) {
            if (changeReason.isEmpty()) {
                String resource = path == null ? "" : root.relativize(path.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
                if (resource.length() > 240) {
                    resource = resource.substring(0, 240);
                }
                changeReason = resource.isEmpty() ? reason : reason + ":" + resource;
                poisonReason = changeReason;
                invalidations++;
            }
            return true;
        }

        private static boolean isAtomicTemporary(Path path) {
            Path name = path.getFileName();
            return name != null && name.toString().matches(
                "\\.resync-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:\\.backup)?\\.tmp");
        }

        @Override
        public void close() throws IOException {
            service.close();
        }

        private record Metrics(long fullRegistrationPasses, long incrementalRegistrationPasses,
                               long incrementalDirectoryVisits, long registeredDirectories,
                               long acceptedEvidencePaths, long invalidations, long fullDirectoryWalks) {
        }
    }

    private static EvidenceSnapshot captureEvidenceSnapshot(Path root, AssetTransactionManager manager,
                                                             AdoptionInventory adoption,
                                                             EvidenceReadObserver observer) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        EvidenceLimits limits = EvidenceLimits.from(observer);
        EvidencePathBudget pathBudget = new EvidencePathBudget(limits.maximumPaths());
        EvidenceHashBudget hashBudget = new EvidenceHashBudget(limits.hashedFileLimit(), limits.hashedTotalLimit());
        if (observer != null) {
            observer.walkStarted(normalizedRoot);
        }
        Map<Path, EvidencePath> paths = new LinkedHashMap<>();
        Files.walkFileTree(normalizedRoot, Set.of(), limits.maximumDepth(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Path normalized = directory.toAbsolutePath().normalize();
                pathBudget.visit(normalizedRoot, normalized);
                paths.put(normalized, EvidencePath.of(attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                pathBudget.visit(normalizedRoot, normalized);
                if (attributes.isDirectory()) {
                    throw new IOException("Asset evidence exceeded maximum depth: " + normalizedRoot.relativize(normalized));
                }
                paths.put(normalized, EvidencePath.of(attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<AssetTransactionManager.TransactionInspection> inspections = manager.inspectTransactions();
        Path snapshots = normalizedRoot.resolve(".snapshots");
        Path coordinator = normalizedRoot.resolve(".asset-coordinator");
        Path project = normalizedRoot.resolve("project.json");
        Set<Path> contentPaths = new LinkedHashSet<>();
        for (Map.Entry<Path, EvidencePath> entry : paths.entrySet()) {
            Path path = entry.getKey();
            if (!entry.getValue().regularFile()) {
                continue;
            }
            if (path.equals(project) || path.equals(coordinator.resolve("state.json"))
                || path.equals(coordinator.resolve("genesis.json"))
                || path.equals(coordinator.resolve(HISTORY_CHECKPOINT_FILE))) {
                contentPaths.add(path);
            }
        }
        Set<Path> retainedPaths = new LinkedHashSet<>(contentPaths);
        Map<Path, CapturedContent> contents = new LinkedHashMap<>();
        EvidenceRetentionBudget retentionBudget = new EvidenceRetentionBudget(
            observer == null ? RETAINED_EVIDENCE_FILE_LIMIT : observer.retainedFileLimit(),
            observer == null ? RETAINED_EVIDENCE_TOTAL_LIMIT : observer.retainedTotalLimit());
        readEvidenceContents(normalizedRoot, paths, contentPaths, retainedPaths, contents, retentionBudget, hashBudget,
            observer);
        Path stateFile = coordinator.resolve("state.json");
        if (contents.containsKey(stateFile)) {
            CoordinatorState state = readState(contents.get(stateFile).bytes(), stateFile);
            state.resources.values().stream().filter(entry -> entry.state instanceof Live)
                .map(entry -> normalizedRoot.resolve(entry.path).normalize()).forEach(contentPaths::add);
        }
        for (AssetTransactionManager.TransactionInspection inspection : inspections) {
            Map<String, byte[]> stagedWrites = inspection.stagedWritesView();
            String bindingResource = ".asset-coordinator/bindings/" + inspection.visibility().mutationId() + ".json";
            contentPaths.add(normalizedRoot.resolve(bindingResource).normalize());
            Path transactionSnapshots = snapshots.resolve(inspection.visibility().transactionId()).normalize();
            inspection.journalEvidence().entries().stream().filter(entry -> Boolean.TRUE.equals(entry.existed()))
                .map(entry -> transactionSnapshots.resolve(entry.target()).normalize()).forEach(contentPaths::add);
            if (PREPARED.equals(inspection.visibility().state())) {
                inspection.journalEvidence().entries().stream().filter(entry -> Boolean.TRUE.equals(entry.existed()))
                    .map(entry -> transactionSnapshots.resolve(entry.target()).normalize()).forEach(retainedPaths::add);
            }
            byte[] bindingBytes = stagedWrites.get(bindingResource);
            if (bindingBytes == null) {
                continue;
            }
            JsonObject binding = parseObject(bindingBytes, "Coordinator journal binding");
            JsonObject intent = binding.getAsJsonObject("intent");
            if (intent == null || intent.getAsJsonArray("assets") == null) {
                continue;
            }
            for (JsonElement element : intent.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                Path assetPath = normalizedRoot.resolve(requiredString(asset, "path")).normalize();
                contentPaths.add(assetPath);
                if (PREPARED.equals(inspection.visibility().state())) {
                    retainedPaths.add(assetPath);
                }
                if (asset.has("previousPath")) {
                    Path previousPath = normalizedRoot.resolve(requiredString(asset, "previousPath")).normalize();
                    contentPaths.add(previousPath);
                    if (PREPARED.equals(inspection.visibility().state())) {
                        retainedPaths.add(previousPath);
                    }
                }
            }
        }
        if (adoption != null) {
            adoption.evidence().forEach(item -> contentPaths.add(normalizedRoot.resolve(item.originalPath()).normalize()));
        }
        readEvidenceContents(normalizedRoot, paths, contentPaths, retainedPaths, contents, retentionBudget, hashBudget,
            observer);
        Genesis genesis = readGenesis(contents.get(coordinator.resolve("genesis.json")).bytes(),
            coordinator.resolve("genesis.json"));
        return new EvidenceSnapshot(normalizedRoot, paths, contents, inspections,
            historyEvidence(normalizedRoot, paths), genesis, 1L, observer, limits, hashBudget);
    }

    private static EvidenceSnapshot captureCheckpointEvidenceSnapshot(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        EvidenceLimits limits = EvidenceLimits.from(null);
        EvidencePathBudget pathBudget = new EvidencePathBudget(limits.maximumPaths());
        EvidenceHashBudget hashBudget = new EvidenceHashBudget(limits.hashedFileLimit(), limits.hashedTotalLimit());
        Map<Path, EvidencePath> paths = new LinkedHashMap<>();
        long walkStarted = System.nanoTime();
        Files.walkFileTree(normalizedRoot, Set.of(), limits.maximumDepth(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Path normalized = directory.toAbsolutePath().normalize();
                pathBudget.visit(normalizedRoot, normalized);
                paths.put(normalized, EvidencePath.of(attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                pathBudget.visit(normalizedRoot, normalized);
                if (attributes.isDirectory()) {
                    throw new IOException("Asset evidence exceeded maximum depth: " + normalizedRoot.relativize(normalized));
                }
                paths.put(normalized, EvidencePath.of(attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        RootContext.checkpointPhase("namespace_capture", walkStarted, paths.size(), 0L);
        Path coordinator = normalizedRoot.resolve(".asset-coordinator");
        Path stateFile = coordinator.resolve("state.json");
        Set<Path> contentPaths = new LinkedHashSet<>(List.of(normalizedRoot.resolve("project.json"), stateFile,
            coordinator.resolve("genesis.json"), coordinator.resolve(HISTORY_CHECKPOINT_FILE)));
        Set<Path> retainedPaths = new LinkedHashSet<>(contentPaths);
        Map<Path, CapturedContent> contents = new LinkedHashMap<>();
        EvidenceRetentionBudget retentionBudget = new EvidenceRetentionBudget(RETAINED_EVIDENCE_FILE_LIMIT,
            RETAINED_EVIDENCE_TOTAL_LIMIT);
        readEvidenceContents(normalizedRoot, paths, contentPaths, retainedPaths, contents, retentionBudget, hashBudget,
            null);
        if (!contents.containsKey(stateFile)) {
            throw new IOException("Asset history checkpoint has no durable coordinator state");
        }
        CoordinatorState state = readState(contents.get(stateFile).bytes(), stateFile);
        state.resources.values().stream().filter(entry -> entry.state instanceof Live)
            .map(entry -> normalizedRoot.resolve(entry.path).normalize()).forEach(contentPaths::add);
        readEvidenceContents(normalizedRoot, paths, contentPaths, retainedPaths, contents, retentionBudget, hashBudget,
            null);
        Path genesisFile = coordinator.resolve("genesis.json");
        CapturedContent genesisContent = contents.get(genesisFile);
        if (genesisContent == null) {
            throw new IOException("Asset root durable genesis is missing: " + genesisFile);
        }
        Genesis genesis = readGenesis(genesisContent.bytes(), genesisFile);
        return new EvidenceSnapshot(normalizedRoot, paths, contents, List.of(), historyEvidence(normalizedRoot, paths),
            genesis, 1L, null, limits, hashBudget);
    }

    private static Map<String, CheckpointEvidence> checkpointEvidence(Path root, EvidenceSnapshot snapshot) throws IOException {
        Path checkpointFile = root.resolve(".asset-coordinator").resolve(HISTORY_CHECKPOINT_FILE).normalize();
        List<Path> evidenceRoots = List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
            root.resolve(".asset-coordinator"));
        List<Map.Entry<Path, EvidencePath>> entries = snapshot.paths().entrySet().stream()
            .filter(entry -> !entry.getKey().equals(checkpointFile)
                && evidenceRoots.stream().anyMatch(entry.getKey()::startsWith))
            .toList();
        if (entries.size() < 2) {
            Map<String, CheckpointEvidence> current = new LinkedHashMap<>();
            for (Map.Entry<Path, EvidencePath> entry : entries) {
                CheckpointEvidenceEntry captured = checkpointEvidenceEntry(root, snapshot, entry);
                current.put(captured.path(), captured.evidence());
            }
            return Map.copyOf(current);
        }
        int workers = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workers,
            Thread.ofPlatform().name("ReSync-Asset-Checkpoint-", 0L).factory());
        List<Future<CheckpointEvidenceEntry>> futures = new ArrayList<>(entries.size());
        try {
            for (Map.Entry<Path, EvidencePath> entry : entries) {
                Callable<CheckpointEvidenceEntry> task = () -> checkpointEvidenceEntry(root, snapshot, entry);
                futures.add(executor.submit(task));
            }
            Map<String, CheckpointEvidence> current = new LinkedHashMap<>();
            for (Future<CheckpointEvidenceEntry> future : futures) {
                CheckpointEvidenceEntry captured = future.get();
                current.put(captured.path(), captured.evidence());
            }
            return Map.copyOf(current);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Asset history checkpoint hashing was interrupted", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IOException("Asset history checkpoint hashing failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CheckpointEvidenceEntry checkpointEvidenceEntry(Path root, EvidenceSnapshot snapshot,
                                                                    Map.Entry<Path, EvidencePath> entry) throws IOException {
        Path path = entry.getKey();
        String relative = root.relativize(path).toString().replace('\\', '/');
        EvidencePath evidence = entry.getValue();
        String hash = "";
        if (evidence.regularFile() && !relative.equals(".asset-coordinator/root.lock")) {
            snapshot.requireNoSymlinkTraversal(path);
            hash = streamHash(path, evidence.size(), snapshot.hashBudget());
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!evidence.equals(EvidencePath.of(after))) {
                throw new IOException("Asset history checkpoint evidence changed after hashing: " + relative);
            }
        }
        return new CheckpointEvidenceEntry(relative, new CheckpointEvidence(evidence.directory(), evidence.regularFile(),
            evidence.regularFile() ? evidence.size() : 0L, hash,
            evidence.regularFile() ? evidence.modifiedAt().toString() : "",
            evidence.regularFile() ? evidence.createdAt().toString() : "", evidence.fileKey()));
    }

    private static String checkpointEvidenceHash(Map<String, CheckpointEvidence> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            evidence.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                updateCheckpointDigest(digest, entry.getKey());
                CheckpointEvidence value = entry.getValue();
                updateCheckpointDigest(digest, Boolean.toString(value.directory()));
                updateCheckpointDigest(digest, Boolean.toString(value.regularFile()));
                updateCheckpointDigest(digest, Long.toString(value.size()));
                updateCheckpointDigest(digest, value.hash());
                updateCheckpointDigest(digest, value.modifiedAt());
                updateCheckpointDigest(digest, value.createdAt());
                updateCheckpointDigest(digest, value.fileKey());
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void updateCheckpointDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String serializeHistoryCheckpoint(HistoryCheckpoint checkpoint) {
        JsonObject marker = new JsonObject();
        marker.addProperty("format", HISTORY_CHECKPOINT_FORMAT);
        marker.addProperty("rootSequence", checkpoint.rootSequence());
        marker.addProperty("stateHash", checkpoint.stateHash());
        marker.addProperty("genesisHash", checkpoint.genesisHash());
        JsonArray index = new JsonArray();
        checkpoint.committedIndex().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("mutationId", entry.getKey());
            item.addProperty("transactionId", entry.getValue().transactionId());
            item.addProperty("fingerprint", entry.getValue().fingerprint());
            index.add(item);
        });
        marker.add("committedIndex", index);
        marker.addProperty("evidenceCount", checkpoint.evidenceCount());
        marker.addProperty("evidenceHash", checkpoint.evidenceHash());
        marker.addProperty("markerHash", StorageSafety.sha256(AssetProjectMetadata.of(marker).canonicalJson()));
        return AssetProjectMetadata.of(marker).canonicalJson();
    }

    private static HistoryCheckpoint readHistoryCheckpoint(byte[] bytes, Path file) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > HISTORY_CHECKPOINT_SIZE_LIMIT) {
            throw new IOException("Asset history checkpoint size is invalid: " + file);
        }
        try {
            JsonObject marker = parseObject(bytes, "Asset history checkpoint");
            String markerHash = requiredHash(marker, "markerHash");
            JsonObject unhashed = marker.deepCopy();
            unhashed.remove("markerHash");
            String format = requiredString(marker, "format");
            if (!Set.of(HISTORY_CHECKPOINT_FORMAT, LEGACY_HISTORY_CHECKPOINT_FORMAT).contains(format)
                || !markerHash.equals(StorageSafety.sha256(AssetProjectMetadata.of(unhashed).canonicalJson()))) {
                throw new IOException("Asset history checkpoint hash or format is invalid");
            }
            long rootSequence = marker.get("rootSequence").getAsLong();
            if (rootSequence < 0L) {
                throw new IOException("Asset history checkpoint sequence is invalid");
            }
            Map<String, AssetTransactionManager.CommittedIndexEntry> committedIndex = new LinkedHashMap<>();
            for (JsonElement element : marker.getAsJsonArray("committedIndex")) {
                JsonObject item = element.getAsJsonObject();
                String mutationId = requiredString(item, "mutationId");
                AssetTransactionManager.CommittedIndexEntry indexed = new AssetTransactionManager.CommittedIndexEntry(
                    requiredString(item, "transactionId"), requiredHash(item, "fingerprint"));
                if (committedIndex.putIfAbsent(mutationId, indexed) != null) {
                    throw new IOException("Asset history checkpoint commit index is duplicated");
                }
            }
            Map<String, CheckpointEvidence> legacyEvidence = new LinkedHashMap<>();
            int evidenceCount;
            String evidenceHash;
            if (LEGACY_HISTORY_CHECKPOINT_FORMAT.equals(format)) {
                for (JsonElement element : marker.getAsJsonArray("evidence")) {
                    JsonObject item = element.getAsJsonObject();
                    String path = requiredString(item, "path");
                    CheckpointEvidence value = new CheckpointEvidence(item.get("directory").getAsBoolean(),
                        item.get("regularFile").getAsBoolean(), item.get("size").getAsLong(),
                        requiredString(item, "hash"), requiredString(item, "modifiedAt"),
                        requiredString(item, "createdAt"), requiredString(item, "fileKey"));
                    if (legacyEvidence.putIfAbsent(path, value) != null) {
                        throw new IOException("Asset history checkpoint evidence is duplicated: " + path);
                    }
                }
                evidenceCount = legacyEvidence.size();
                evidenceHash = checkpointEvidenceHash(legacyEvidence);
            } else {
                evidenceCount = marker.get("evidenceCount").getAsInt();
                evidenceHash = requiredHash(marker, "evidenceHash");
                if (evidenceCount < 0 || evidenceCount > EVIDENCE_MAX_PATHS) {
                    throw new IOException("Asset history checkpoint evidence count is invalid");
                }
            }
            return new HistoryCheckpoint(rootSequence, requiredHash(marker, "stateHash"),
                requiredHash(marker, "genesisHash"), Map.copyOf(committedIndex), evidenceCount, evidenceHash,
                Map.copyOf(legacyEvidence));
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset history checkpoint is corrupt: " + file, failure);
        }
    }

    private static void readEvidenceContents(Path root, Map<Path, EvidencePath> paths, Set<Path> requested,
                                             Set<Path> retained, Map<Path, CapturedContent> contents,
                                             EvidenceRetentionBudget retentionBudget,
                                             EvidenceHashBudget hashBudget,
                                             EvidenceReadObserver observer) throws IOException {
        for (Path path : requested.stream().filter(candidate -> !contents.containsKey(candidate)).sorted().toList()) {
            EvidencePath classified = paths.get(path);
            if (classified == null || !classified.regularFile()) {
                continue;
            }
            boolean retain = retained.contains(path);
            if (retain) {
                retentionBudget.retain(classified.size());
            }
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            EvidencePath beforePath = EvidencePath.of(before);
            if (!classified.equals(beforePath)) {
                throw new IOException("Asset evidence changed before content capture: " + root.relativize(path));
            }
            byte[] bytes = retain ? readRetainedBytes(path, classified.size()) : null;
            String hash = retain ? StorageSafety.sha256(bytes) : streamHash(path, classified.size(), hashBudget);
            if (observer != null) {
                observer.contentRead(path);
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!beforePath.equals(EvidencePath.of(after)) || (retain && before.size() != bytes.length)) {
                throw new IOException("Asset evidence changed during content capture: " + root.relativize(path));
            }
            contents.put(path, new CapturedContent(hash, before.size(), beforePath,
                retain ? bytes : null));
        }
    }

    private static String streamHash(Path path, long expectedSize, EvidenceHashBudget budget) throws IOException {
        budget.reserve(expectedSize);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long readBytes = 0L;
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    readBytes = Math.addExact(readBytes, read);
                    if (readBytes > expectedSize) {
                        throw new IOException("Asset evidence changed while hashing: " + path);
                    }
                    digest.update(buffer, 0, read);
                }
            }
            if (readBytes != expectedSize) {
                throw new IOException("Asset evidence changed while hashing: " + path);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        } catch (ArithmeticException failure) {
            throw new IOException("Asset evidence size overflow while hashing: " + path, failure);
        }
    }

    private static byte[] readRetainedBytes(Path path, long expectedSize) throws IOException {
        if (expectedSize < 0L || expectedSize > Integer.MAX_VALUE) {
            throw new IOException("Retained asset evidence exceeds its in-memory bound: " + path);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
        long readBytes = 0L;
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                readBytes = Math.addExact(readBytes, read);
                if (readBytes > expectedSize) {
                    throw new IOException("Retained asset evidence changed while reading: " + path);
                }
                output.write(buffer, 0, read);
            }
        } catch (ArithmeticException failure) {
            throw new IOException("Retained asset evidence size overflow while reading: " + path, failure);
        }
        if (readBytes != expectedSize) {
            throw new IOException("Retained asset evidence changed while reading: " + path);
        }
        return output.toByteArray();
    }

    private static HistoryEvidence historyEvidence(Path root, Map<Path, EvidencePath> paths) {
        List<Path> evidenceRoots = List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
            root.resolve(".asset-coordinator"));
        List<HistoryEvidenceEntry> entries = new ArrayList<>();
        for (Path evidenceRoot : evidenceRoots) {
            boolean found = false;
            for (Map.Entry<Path, EvidencePath> entry : paths.entrySet()) {
                if (!entry.getKey().startsWith(evidenceRoot)) {
                    continue;
                }
                found = true;
                entries.add(historyEvidenceEntry(root, entry.getKey(), entry.getValue()));
            }
            if (!found) {
                entries.add(historyEvidenceEntry(root, evidenceRoot, (EvidencePath) null));
            }
        }
        entries.sort(Comparator.comparing(HistoryEvidenceEntry::path));
        return new HistoryEvidence(entries);
    }

    private static HistoryEvidence captureHistoryEvidence(Path root) throws IOException {
        return captureHistoryEvidence(root, EvidenceLimits.from(null));
    }

    private static HistoryEvidence captureHistoryEvidence(Path root, EvidenceLimits limits) throws IOException {
        List<HistoryEvidenceEntry> entries = new ArrayList<>();
        EvidencePathBudget pathBudget = new EvidencePathBudget(limits.maximumPaths());
        List<Path> evidenceRoots = List.of(root.resolve(".transactions"), root.resolve(".snapshots"),
            root.resolve(".asset-coordinator"));
        for (Path evidenceRoot : evidenceRoots) {
            if (!Files.exists(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                entries.add(historyEvidenceEntry(root, evidenceRoot, (BasicFileAttributes) null));
                continue;
            }
            Files.walkFileTree(evidenceRoot, Set.of(), limits.maximumDepth(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    pathBudget.visit(root, directory);
                    entries.add(historyEvidenceEntry(root, directory, attributes));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    pathBudget.visit(root, file);
                    if (attributes.isDirectory()) {
                        throw new IOException("Asset history evidence exceeded maximum depth: " + root.relativize(file));
                    }
                    entries.add(historyEvidenceEntry(root, file, attributes));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        entries.sort(Comparator.comparing(HistoryEvidenceEntry::path));
        return new HistoryEvidence(List.copyOf(entries));
    }

    private static String historyDifference(HistoryEvidence before, HistoryEvidence after) {
        Map<String, HistoryEvidenceEntry> beforeEntries = before.entries.stream()
            .collect(Collectors.toMap(HistoryEvidenceEntry::path, entry -> entry));
        Map<String, HistoryEvidenceEntry> afterEntries = after.entries.stream()
            .collect(Collectors.toMap(HistoryEvidenceEntry::path, entry -> entry));
        Set<String> paths = new TreeSet<>(beforeEntries.keySet());
        paths.addAll(afterEntries.keySet());
        return paths.stream().filter(path -> !Objects.equals(beforeEntries.get(path), afterEntries.get(path)))
            .limit(8L).map(path -> path + " before=" + beforeEntries.get(path) + " after=" + afterEntries.get(path))
            .collect(Collectors.joining("; "));
    }

    private static HistoryEvidenceEntry historyEvidenceEntry(Path root, Path path, BasicFileAttributes attributes) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        if (attributes == null) {
            return new HistoryEvidenceEntry(relative, "MISSING", 0L, FileTime.fromMillis(0L),
                FileTime.fromMillis(0L), "");
        }
        String type = attributes.isSymbolicLink() ? "SYMLINK"
            : attributes.isDirectory() ? "DIRECTORY" : attributes.isRegularFile() ? "FILE" : "OTHER";
        return new HistoryEvidenceEntry(relative, type, attributes.isRegularFile() ? attributes.size() : 0L,
            attributes.lastModifiedTime(),
            attributes.creationTime(), Objects.toString(attributes.fileKey(), ""));
    }

    private static HistoryEvidenceEntry historyEvidenceEntry(Path root, Path path, EvidencePath evidence) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        if (evidence == null) {
            return new HistoryEvidenceEntry(relative, "MISSING", 0L, FileTime.fromMillis(0L),
                FileTime.fromMillis(0L), "");
        }
        return new HistoryEvidenceEntry(relative, evidence.type(), evidence.regularFile() ? evidence.size() : 0L,
            evidence.modifiedAt(),
            evidence.createdAt(), evidence.fileKey());
    }

    private static void validateJournalsBeforeRecovery(Path root) throws IOException {
        try {
            validateJournalStructure(root);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset transaction journal structure is corrupt", failure);
        }
    }

    private static void validateJournalsBeforeRecovery(EvidenceSnapshot evidence) throws IOException {
        try {
            Path root = evidence.root();
            Path transactions = root.resolve(".transactions");
            if (!evidence.isDirectory(transactions) || evidence.isSymbolicLink(transactions)) {
                throw new IOException("Asset transaction journal root is unsafe: " + transactions);
            }
            Set<Path> expectedDirectories = new LinkedHashSet<>();
            int prepared = 0;
            long preparedModified = Long.MIN_VALUE;
            long newestCommitted = Long.MIN_VALUE;
            for (AssetTransactionManager.TransactionInspection inspection : evidence.inspections()) {
                AssetTransactionManager.TransactionVisibility visibility = inspection.visibility();
                AssetTransactionManager.JournalEvidence journalEvidence = inspection.journalEvidence();
                Path transactionDirectory = journalEvidence.transactionDirectory();
                Path journal = journalEvidence.journalPath();
                if (!transactionDirectory.getParent().equals(transactions)
                    || !transactionDirectory.getFileName().toString().equals(visibility.transactionId())
                    || !journal.equals(transactionDirectory.resolve("journal.json"))
                    || !expectedDirectories.add(transactionDirectory)
                    || evidence.isSymbolicLink(transactionDirectory) || !evidence.isDirectory(transactionDirectory)
                    || evidence.isSymbolicLink(journal) || !evidence.isRegularFile(journal)
                    || !evidence.path(journal).matches(journalEvidence.journalIdentity())) {
                    throw new IOException("Asset transaction journal identity is unsafe: " + transactionDirectory);
                }
                Set<Path> allowed = new LinkedHashSet<>();
                allowed.add(journal);
                for (AssetTransactionManager.JournalEntryEvidence entry : journalEvidence.entries()) {
                    if (entry.delete()) {
                        if (!entry.staged().isEmpty()) {
                            throw new IOException("Deleted asset transaction entry has staged content: " + journal);
                        }
                        continue;
                    }
                    Path staged = transactionDirectory.resolve(entry.staged()).normalize();
                    if (!staged.startsWith(transactionDirectory) || !transactionDirectory.equals(staged.getParent())
                        || evidence.isSymbolicLink(staged) || !evidence.isRegularFile(staged)) {
                        throw new IOException("Asset transaction staged path is unsafe: " + entry.staged());
                    }
                    allowed.add(staged);
                }
                for (Path path : evidence.directChildren(transactionDirectory)) {
                    if (!allowed.contains(path)) {
                        throw new IOException("Unexpected content in asset transaction journal: " + path);
                    }
                }
                long modified = journalEvidence.journalIdentity().modifiedAt().toMillis();
                if (PREPARED.equals(visibility.state())) {
                    prepared++;
                    preparedModified = modified;
                } else if (COMMITTED.equals(visibility.state())) {
                    newestCommitted = Math.max(newestCommitted, modified);
                } else {
                    throw new IOException("Asset transaction journal has an unknown state: " + journal);
                }
            }
            for (Path path : evidence.directChildren(transactions)) {
                if (!expectedDirectories.contains(path) || evidence.isSymbolicLink(path) || !evidence.isDirectory(path)) {
                    throw new IOException("Unexpected file in asset transaction journal root: " + path);
                }
            }
            if (prepared > 1) {
                throw new IOException("Multiple prepared asset transactions have ambiguous recovery order");
            }
            if (prepared == 1 && newestCommitted > preparedModified) {
                throw new IOException("Prepared asset transaction is older than a committed journal");
            }
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset transaction journal structure is corrupt", failure);
        }
    }

    private static void prepareGenesis(Path root, Path coordinatorRoot, Gson gson, AdoptionInventory requested,
                                       AdoptionBinding requestedBinding, boolean authorityExisted) throws IOException {
        Path genesisFile = coordinatorRoot.resolve("genesis.json");
        if (Files.exists(genesisFile, LinkOption.NOFOLLOW_LINKS)) {
            Genesis genesis = readGenesis(root);
            if (requested != null && (!genesis.provenance.equals(requested.provenance)
                || !genesis.artifactHash.equals(requestedBinding.artifactHash)
                || !genesis.manifestHash.equals(requestedBinding.manifestHash)
                || !genesis.inventoryHash.equals(adoptionInventoryHash(root, requested)))) {
                throw new IOException("Asset adoption inventory does not match durable genesis");
            }
            Path stateFile = coordinatorRoot.resolve("state.json");
            if (requested != null && (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                || readState(stateFile, gson).rootSequence == 0L)) {
                validateAdoptionProjectBytes(root, requested);
            }
            return;
        }
        AdoptionInventory adoption = requested;
        AdoptionBinding binding = requestedBinding;
        if (adoption == null) {
            if (authorityExisted || hasExternalGenesisEvidence(root)) {
                throw new IOException("Asset root authority is missing durable genesis; explicit adoption is required");
            }
            adoption = new AdoptionInventory("new-root", "{}", List.of());
            binding = AdoptionBinding.derived(adoption);
        }
        AdoptionDocument document = adoptionDocument(root, adoption);
        Path projectFile = root.resolve("project.json");
        byte[] projectBytes = adoption.projectJson.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(projectFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(projectFile) || !Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(projectBytes, Files.readAllBytes(projectFile))) {
                throw new IOException("Asset adoption project bytes do not match project.json");
            }
        } else {
            StorageSafety.writeBytesAtomic(projectFile, projectBytes);
        }
        String stateJson = serializeState(document.state, gson);
        Path stateFile = coordinatorRoot.resolve("state.json");
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!stateIdentity(stateFile).equals(AssetProjectMetadata.of(
                JsonParser.parseString(stateJson).getAsJsonObject()).canonicalJson())) {
                throw new IOException("Partial asset adoption state does not match requested inventory");
            }
        } else {
            StorageSafety.writeUtf8Atomic(stateFile, stateJson);
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("format", GENESIS_FORMAT);
        marker.addProperty("provenance", adoption.provenance);
        marker.addProperty("artifactHash", binding.artifactHash);
        marker.addProperty("manifestHash", binding.manifestHash);
        marker.addProperty("inventoryHash", document.inventoryHash);
        marker.addProperty("projectHash", document.state.project.hash());
        marker.add("state", JsonParser.parseString(stateJson).getAsJsonObject());
        marker.addProperty("markerHash", StorageSafety.sha256(AssetProjectMetadata.of(marker).canonicalJson()));
        StorageSafety.writeUtf8Atomic(genesisFile, AssetProjectMetadata.of(marker).canonicalJson());
        StorageSafety.forceDirectory(coordinatorRoot);
        StorageSafety.forceDirectory(root);
    }

    private static boolean hasExternalGenesisEvidence(Path root) throws IOException {
        Path coordinator = root.resolve(".asset-coordinator");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> !path.startsWith(coordinator)
                && (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)));
        }
    }

    private static void validateAdoptionProjectBytes(Path root, AdoptionInventory adoption) throws IOException {
        Path project = root.resolve("project.json");
        byte[] expected = adoption.projectJson.getBytes(StandardCharsets.UTF_8);
        if (Files.isSymbolicLink(project) || !Files.isRegularFile(project, LinkOption.NOFOLLOW_LINKS)
            || !Arrays.equals(expected, Files.readAllBytes(project))) {
            throw new IOException("Asset adoption project bytes do not match durable genesis");
        }
    }

    private static AdoptionDocument adoptionDocument(Path root, AdoptionInventory adoption) throws IOException {
        AssetProjectMetadata metadata;
        try {
            metadata = AssetProjectMetadata.parse(adoption.projectJson);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Asset adoption project metadata is invalid", failure);
        }
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path project = root.resolve("project.json");
        Map<AssetKey, ResourceEntry> resources = new LinkedHashMap<>();
        Set<Path> managedLivePaths = new LinkedHashSet<>();
        Set<Path> managedEvidencePaths = validateAdoptionEvidence(root, transactions, snapshots, coordinator, project,
            adoption.evidence(), true);
        List<AdoptedAsset> assets = adoption.assets.stream().sorted(Comparator.comparing(asset -> asset.key.canonical())).toList();
        for (AdoptedAsset asset : assets) {
            Path path = asset.path.isAbsolute() ? asset.path.toAbsolutePath().normalize() : root.resolve(asset.path).normalize();
            String relative = root.relativize(path).toString().replace('\\', '/');
            validateManagedPath(root, transactions, snapshots, coordinator, project, relative, path);
            if (resources.containsKey(asset.key) || resources.values().stream().anyMatch(entry -> entry.path.equals(relative))) {
                throw new IOException("Asset adoption inventory contains a duplicate key or path: " + asset.key.canonical());
            }
            if (asset.state instanceof Missing) {
                throw new IOException("Asset adoption inventory cannot persist Missing state: " + asset.key.canonical());
            }
            if (asset.state instanceof Live live) {
                if (!live.hash().equals(StorageSafety.sha256(asset.content)) || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(asset.content, Files.readAllBytes(path))) {
                    throw new IOException("Asset adoption live bytes do not match inventory: " + asset.key.canonical());
                }
                managedLivePaths.add(path);
            } else if (asset.content.length != 0 || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset adoption deleted state has physical content: " + asset.key.canonical());
            }
            resources.put(asset.key, new ResourceEntry(asset.key, relative, asset.state, asset.mutationId,
                asset.deletionProvenance));
        }
        try (Stream<Path> diskPaths = Files.walk(root)) {
            for (Path path : diskPaths.toList()) {
                if (path.startsWith(coordinator) || path.equals(root) || path.equals(transactions)
                    || path.equals(snapshots)) {
                    continue;
                }
                if (path.startsWith(transactions) || path.startsWith(snapshots)) {
                    throw new IOException("Asset adoption root contains prior transaction evidence: "
                        + root.relativize(path));
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Asset adoption root contains an unmanaged symbolic link: " + root.relativize(path));
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.equals(project)
                    && !managedLivePaths.contains(path.normalize()) && !managedEvidencePaths.contains(path.normalize())) {
                    throw new IOException("Asset adoption inventory omitted a physical file: " + root.relativize(path));
                }
            }
        }
        CoordinatorState state = new CoordinatorState(0L, new ExpectedProject(0L, metadata.hash()), resources, Map.of(),
            adoption.blocked);
        state.validate();
        return new AdoptionDocument(state, adoptionInventoryHash(root, adoption));
    }

    private static String adoptionInventoryHash(Path root, AdoptionInventory adoption) throws IOException {
        AssetProjectMetadata metadata;
        try {
            metadata = AssetProjectMetadata.parse(adoption.projectJson);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Asset adoption project metadata is invalid", failure);
        }
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path project = root.resolve("project.json");
        Set<AssetKey> keys = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        JsonArray inventoryAssets = new JsonArray();
        for (AdoptedAsset asset : adoption.assets.stream()
            .sorted(Comparator.comparing(value -> value.key.canonical())).toList()) {
            Path path = asset.path.isAbsolute() ? asset.path.toAbsolutePath().normalize() : root.resolve(asset.path).normalize();
            String relative = root.relativize(path).toString().replace('\\', '/');
            validateManagedPath(root, transactions, snapshots, coordinator, project, relative, path);
            if (!keys.add(asset.key) || !paths.add(relative)) {
                throw new IOException("Asset adoption inventory contains a duplicate key or path: " + asset.key.canonical());
            }
            if (asset.state instanceof Live live && !live.hash().equals(StorageSafety.sha256(asset.content))) {
                throw new IOException("Asset adoption live content hash does not match its state: " + asset.key.canonical());
            }
            JsonObject item = new JsonObject();
            item.addProperty("key", asset.key.canonical());
            item.addProperty("type", asset.key.type());
            item.addProperty("id", asset.key.id());
            item.addProperty("path", relative);
            item.add("state", expectedStateJson(asset.state));
            item.addProperty("mutationId", asset.mutationId.value());
            item.addProperty("payloadHash", asset.state instanceof Live ? StorageSafety.sha256(asset.content) : "");
            item.addProperty("payloadSize", asset.content.length);
            if (asset.deletionProvenance != null) {
                item.add("deletionProvenance", deletionProvenanceJson(asset.deletionProvenance));
            }
            inventoryAssets.add(item);
        }
        JsonObject inventory = new JsonObject();
        inventory.addProperty("format", GENESIS_FORMAT);
        inventory.addProperty("provenance", adoption.provenance);
        inventory.addProperty("projectHash", metadata.hash());
        inventory.addProperty("projectBytesHash", StorageSafety.sha256(
            adoption.projectJson.getBytes(StandardCharsets.UTF_8)));
        inventory.add("assets", inventoryAssets);
        JsonArray blocked = new JsonArray();
        adoption.blocked.stream().map(AssetTransactionCoordinator::blockedAdoptionJson).forEach(blocked::add);
        inventory.add("blocked", blocked);
        JsonArray evidence = new JsonArray();
        adoption.evidence.stream().sorted(Comparator.comparing(AdoptionEvidence::originalPath)).forEach(item -> {
            JsonObject value = new JsonObject();
            value.addProperty("originalPath", item.originalPath());
            value.addProperty("hash", item.hash());
            value.addProperty("size", item.size());
            evidence.add(value);
        });
        inventory.add("evidence", evidence);
        return StorageSafety.sha256(AssetProjectMetadata.of(inventory).canonicalJson());
    }

    private static Genesis readGenesis(Path root) throws IOException {
        Path file = root.resolve(".asset-coordinator/genesis.json");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset root durable genesis is missing");
        }
        return readGenesis(Files.readAllBytes(file), file);
    }

    private static Genesis readGenesis(byte[] content, Path file) throws IOException {
        if (content == null) {
            throw new IOException("Asset root durable genesis is missing: " + file);
        }
        JsonObject marker = parseObject(content, "Asset coordinator genesis");
        String markerHash = requiredHash(marker, "markerHash");
        JsonObject unhashed = marker.deepCopy();
        unhashed.remove("markerHash");
        if (!GENESIS_FORMAT.equals(requiredString(marker, "format"))
            || !markerHash.equals(StorageSafety.sha256(AssetProjectMetadata.of(unhashed).canonicalJson()))) {
            throw new IOException("Asset coordinator genesis hash or format is invalid");
        }
        JsonObject stateDocument = marker.getAsJsonObject("state");
        CoordinatorState state = coordinatorState(validateStateDocument(
            stateDocument.toString().getBytes(StandardCharsets.UTF_8)));
        if (state.rootSequence != 0L || !state.mutations.isEmpty()
            || !state.project.hash().equals(requiredHash(marker, "projectHash"))) {
            throw new IOException("Asset coordinator genesis state is invalid");
        }
        return new Genesis(state, AssetProjectMetadata.of(stateDocument).canonicalJson(),
            requiredHash(marker, "inventoryHash"), requiredString(marker, "provenance"),
            requiredHash(marker, "artifactHash"), requiredHash(marker, "manifestHash"));
    }

    private static void validateJournalStructure(Path root) throws IOException {
        Path transactions = root.resolve(".transactions");
        if (!Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) {
            StorageSafety.createDirectoriesNoSymlinks(root, transactions);
            return;
        }
        if (Files.isSymbolicLink(transactions) || !Files.isDirectory(transactions, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset transaction journal root is unsafe: " + transactions);
        }
        int prepared = 0;
        long preparedModified = Long.MIN_VALUE;
        long newestCommitted = Long.MIN_VALUE;
        try (Stream<Path> entries = Files.list(transactions)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected file in asset transaction journal root: " + entry);
                }
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Asset transaction journal directory is a symbolic link: " + entry);
                }
                Path journal = entry.resolve("journal.json");
                if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(journal)) {
                    throw new IOException("Asset transaction journal is missing: " + entry);
                }
                JsonObject object;
                try {
                    JsonElement parsed = JsonParser.parseString(StorageSafety.readUtf8(journal));
                    if (!parsed.isJsonObject()) {
                        throw new IOException("Asset transaction journal is not an object: " + journal);
                    }
                    object = parsed.getAsJsonObject();
                } catch (RuntimeException failure) {
                    throw new IOException("Asset transaction journal is corrupt: " + journal, failure);
                }
                Set<Path> allowed = new LinkedHashSet<>();
                allowed.add(journal);
                JsonArray journalEntries = object.getAsJsonArray("entries");
                if (journalEntries == null) {
                    throw new IOException("Asset transaction journal has no entries: " + journal);
                }
                for (JsonElement element : journalEntries) {
                    JsonObject journalEntry = element.getAsJsonObject();
                    boolean delete = journalEntry.get("delete").getAsBoolean();
                    String staged = requiredString(journalEntry, "staged");
                    if (delete) {
                        if (!staged.isEmpty()) {
                            throw new IOException("Deleted asset transaction entry has staged content: " + journal);
                        }
                        continue;
                    }
                    Path stagedPath = entry.resolve(staged).normalize();
                    if (!stagedPath.startsWith(entry) || !entry.equals(stagedPath.getParent())) {
                        throw new IOException("Asset transaction staged path is unsafe: " + staged);
                    }
                    allowed.add(stagedPath);
                }
                try (Stream<Path> transactionFiles = Files.list(entry)) {
                    for (Path transactionFile : transactionFiles.toList()) {
                        if (!allowed.contains(transactionFile)) {
                            throw new IOException("Unexpected content in asset transaction journal: " + transactionFile);
                        }
                    }
                }
                String state = object.has("state") ? object.get("state").getAsString() : "";
                long modified = Files.getLastModifiedTime(journal, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (PREPARED.equals(state)) {
                    prepared++;
                    preparedModified = modified;
                } else if (!COMMITTED.equals(state)) {
                    throw new IOException("Asset transaction journal has an unknown state: " + journal);
                } else {
                    newestCommitted = Math.max(newestCommitted, modified);
                }
            }
        }
        if (prepared > 1) {
            throw new IOException("Multiple prepared asset transactions have ambiguous recovery order");
        }
        if (prepared == 1 && newestCommitted > preparedModified) {
            throw new IOException("Prepared asset transaction is older than a committed journal");
        }
    }

    private static ValidatedJournals validateInspections(Path root, AssetTransactionManager manager,
                                                         List<AssetTransactionManager.TransactionInspection> inspections,
                                                         Gson gson)
        throws IOException {
        try {
            return validateInspectionsContent(root, manager, inspections, null, null, gson);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator journal validation failed", failure);
        }
    }

    private static ValidatedJournals validateInspections(Path root, AssetTransactionManager manager,
                                                         List<AssetTransactionManager.TransactionInspection> inspections,
                                                         EvidenceSnapshot evidence, Gson gson) throws IOException {
        try {
            return validateInspectionsContent(root, manager, inspections, null, evidence, gson);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator journal validation failed", failure);
        }
    }

    private static ValidatedJournals validateCommittedAppend(Path root, AssetTransactionManager manager,
                                                             ValidatedJournals trusted,
                                                             AssetTransactionManager.TransactionInspection inspection,
                                                             Gson gson)
        throws IOException {
        if (trusted == null || !trusted.preparedFingerprints.isEmpty()
            || !COMMITTED.equals(inspection.visibility().state())) {
            throw new IOException("Asset transaction history cannot accept an incremental commit");
        }
        try {
            return validateInspectionsContent(root, manager, List.of(inspection), trusted, null, gson);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator incremental journal validation failed", failure);
        }
    }

    private static ValidatedJournals validateInspectionsContent(Path root, AssetTransactionManager manager,
                                                                List<AssetTransactionManager.TransactionInspection> inspections,
                                                                ValidatedJournals trusted, EvidenceSnapshot evidence,
                                                                Gson gson)
        throws IOException {
        Genesis genesis = trusted == null ? evidence == null ? readGenesis(root) : evidence.genesis() : trusted.genesis;
        Set<UUID> coordinatorJournals = new LinkedHashSet<>(trusted == null ? Set.of() : trusted.coordinatorJournals);
        Set<Long> coordinatorSequences = new LinkedHashSet<>();
        long sequenceBase = trusted != null && trusted.checkpointTip != null
            ? trusted.checkpointTip.sequence() : 0L;
        Set<Path> preparedAssetPaths = new LinkedHashSet<>();
        Map<String, String> preparedFingerprints = new LinkedHashMap<>();
        Map<Long, PreparedTransition> transitions = new LinkedHashMap<>(trusted == null ? Map.of() : trusted.transitions);
        Map<String, Map<String, PreparedFileState>> preparedFileStates = new LinkedHashMap<>();
        PreparedPhysicalValidation preparedPhysicalValidation = null;
        PreparedTransition preparedTransition = null;
        long preparedSequenceBefore = -1L;
        for (AssetTransactionManager.TransactionInspection inspection : inspections) {
            AssetTransactionManager.TransactionVisibility visibility = inspection.visibility();
            List<AssetTransactionManager.TransactionOperation> operations = visibility.descriptor().operations();
            List<String> bindings = operations.stream().map(AssetTransactionManager.TransactionOperation::resource)
                .filter(resource -> resource.startsWith(".asset-coordinator/bindings/")).toList();
            boolean hasState = operations.stream().anyMatch(operation -> operation.resource().equals(".asset-coordinator/state.json"));
            if (!hasState && bindings.isEmpty()) {
                throw new IOException("Asset transaction is not owned by the coordinator: " + visibility.transactionId());
            }
            if (!hasState || bindings.size() != 1) {
                throw new IOException("Coordinator transaction has an incomplete ownership binding: " + visibility.transactionId());
            }
            UUID mutationId;
            try {
                mutationId = UUID.fromString(visibility.mutationId());
            } catch (IllegalArgumentException failure) {
                throw new IOException("Coordinator journal has an invalid mutation ID: " + visibility.mutationId(), failure);
            }
            String bindingResource = ".asset-coordinator/bindings/" + mutationId + ".json";
            if (!bindings.getFirst().equals(bindingResource) || !coordinatorJournals.add(mutationId)) {
                throw new IOException("Coordinator journal binding identity is invalid: " + mutationId);
            }
            Map<String, byte[]> staged = inspection.stagedWritesView();
            byte[] bindingBytes = staged.get(bindingResource);
            byte[] stateBytes = staged.get(".asset-coordinator/state.json");
            if (bindingBytes == null || stateBytes == null) {
                throw new IOException("Coordinator journal binding content is incomplete: " + mutationId);
            }
            JsonObject binding = parseObject(bindingBytes, "Coordinator journal binding");
            if (!"asset-coordinator-binding-v1".equals(requiredString(binding, "format"))
                || !mutationId.toString().equals(requiredString(binding, "mutationId"))) {
                throw new IOException("Coordinator journal binding identity does not match its journal: " + mutationId);
            }
            JsonObject intent = binding.getAsJsonObject("intent");
            String intentHash = requiredHash(binding, "intentHash");
            if (intent == null || !intentHash.equals(StorageSafety.sha256(AssetProjectMetadata.of(intent).canonicalJson()))
                || !mutationId.toString().equals(requiredString(intent, "mutationId"))) {
                throw new IOException("Coordinator journal intent binding is invalid: " + mutationId);
            }
            long sequenceBefore = binding.get("sequenceBefore").getAsLong();
            long sequenceAfter = binding.get("sequenceAfter").getAsLong();
            if (sequenceBefore < 0L || sequenceAfter != Math.addExact(sequenceBefore, 1L)) {
                throw new IOException("Coordinator journal sequence binding is invalid: " + mutationId);
            }
            if (sequenceAfter <= sequenceBase) {
                throw new IOException("Coordinator journal does not advance its trusted history tip: " + mutationId);
            }
            if (!coordinatorSequences.add(sequenceAfter)) {
                throw new IOException("Coordinator journal sequence is duplicated: " + sequenceAfter);
            }
            AssetTransactionManager.TransactionDescriptor baseDescriptor = manager.descriptorFromInspection(
                operations.stream().filter(operation -> !operation.resource().equals(bindingResource)).toList());
            if (!baseDescriptor.fingerprint().equals(requiredHash(binding, "baseFingerprint"))
                || !baseDescriptor.operations().equals(operations(binding.getAsJsonArray("baseOperations")))) {
                throw new IOException("Coordinator journal operation binding does not match staged content: " + mutationId);
            }
            JsonObject stagedState = validateStateDocument(stateBytes);
            validateBindingIntent(root, binding, intent, baseDescriptor.operations(), stagedState, intentHash, mutationId,
                sequenceAfter);
            PreparedTransition transition = preparedTransition(intent, stagedState, stateBytes, mutationId);
            if (transitions.putIfAbsent(sequenceAfter, transition) != null) {
                throw new IOException("Coordinator journal transition sequence is duplicated: " + sequenceAfter);
            }
            if (PREPARED.equals(visibility.state())) {
                preparedPhysicalValidation = new PreparedPhysicalValidation(visibility.transactionId(), bindingResource,
                    bindingBytes, binding, intent, sequenceBefore, transition);
                intent.getAsJsonArray("assets").forEach(element -> {
                    JsonObject asset = element.getAsJsonObject();
                    preparedAssetPaths.add(root.resolve(requiredString(asset, "path")).normalize());
                    if (asset.has("previousPath")) {
                        preparedAssetPaths.add(root.resolve(requiredString(asset, "previousPath")).normalize());
                    }
                });
                preparedFingerprints.put(visibility.transactionId(), visibility.descriptor().fingerprint());
                preparedSequenceBefore = sequenceBefore;
                preparedTransition = transition;
            }
        }
        long expectedLastSequence = Math.addExact(sequenceBase, inspections.size());
        for (long sequence = Math.addExact(sequenceBase, 1L); sequence <= expectedLastSequence; sequence++) {
            if (!coordinatorSequences.contains(sequence)) {
                throw new IOException("Coordinator journal sequence has a gap: " + sequence);
            }
        }
        if (preparedPhysicalValidation != null) {
            PreparedTransition previous = preparedPhysicalValidation.sequenceBefore == 0L
                ? null : transitions.get(preparedPhysicalValidation.sequenceBefore);
            if (preparedPhysicalValidation.sequenceBefore > 0L && previous == null) {
                throw new IOException("Prepared coordinator transaction has no exact prior state transition");
            }
            String stateBeforeHash = previous == null
                ? StorageSafety.sha256(serializeState(genesis.state, gson)) : previous.stateHash;
            Map<String, PreparedFileState> fileStates = validatePreparedPhysicalState(root,
                preparedPhysicalValidation.transactionId, preparedPhysicalValidation.bindingResource,
                preparedPhysicalValidation.bindingBytes, preparedPhysicalValidation.binding,
                preparedPhysicalValidation.intent, stateBeforeHash, preparedPhysicalValidation.transition.stateHash,
                evidence);
            preparedFileStates.put(preparedPhysicalValidation.transactionId, fileStates);
        }
        return new ValidatedJournals(Set.copyOf(coordinatorJournals), Map.copyOf(preparedFingerprints),
            Set.copyOf(preparedAssetPaths), Math.addExact(trusted == null ? 0 : trusted.managerTransactions,
                inspections.size()), preparedSequenceBefore, preparedTransition,
            Map.copyOf(transitions), genesis, Map.copyOf(preparedFileStates),
            trusted == null ? null : trusted.checkpointTip);
    }

    private static ValidatedJournals compactValidatedJournals(ValidatedJournals validated, CoordinatorState state,
                                                               String stateIdentity) throws IOException {
        if (validated == null || state == null || stateIdentity == null || !validated.preparedFingerprints.isEmpty()
            || validated.preparedTransition != null || !validated.coordinatorJournals.equals(state.mutations.keySet())) {
            throw new IOException("Asset transaction history cannot establish a compact trusted tip");
        }
        return new ValidatedJournals(validated.coordinatorJournals, Map.of(), Set.of(),
            validated.managerTransactions, -1L, null, Map.of(), validated.genesis, Map.of(),
            new CheckpointTip(state.rootSequence, state, stateIdentity));
    }

    private static PreparedTransition preparedTransition(JsonObject intent, JsonObject stagedState, byte[] stateBytes,
                                                         UUID mutationId) throws IOException {
        CoordinatorState staged = coordinatorState(stagedState);
        MutationRecord mutation = staged.mutations.get(mutationId);
        if (mutation == null) {
            throw new IOException("Prepared coordinator state has no bound mutation: " + mutationId);
        }
        Map<AssetKey, PreparedAsset> assets = new LinkedHashMap<>();
        for (JsonElement element : intent.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            AssetKey key = new AssetKey(requiredString(asset, "type"), requiredString(asset, "id"));
            ExpectedState result = mutation.result.states().get(key);
            String previousPath = asset.has("previousPath") ? requiredString(asset, "previousPath") : null;
            if (result == null || assets.putIfAbsent(key, new PreparedAsset(requiredString(asset, "path"), previousPath,
                expectedState(asset.getAsJsonObject("expected")), result)) != null) {
                throw new IOException("Prepared coordinator transition has an invalid asset: " + key.canonical());
            }
        }
        return new PreparedTransition(mutationId, expectedProject(intent.getAsJsonObject("expectedProject")), staged,
            Map.copyOf(assets), AssetProjectMetadata.of(stagedState).canonicalJson(), StorageSafety.sha256(stateBytes));
    }

    private static void validateBindingIntent(Path root, JsonObject binding, JsonObject intent,
                                              List<AssetTransactionManager.TransactionOperation> operations,
                                              JsonObject stagedState, String intentHash, UUID mutationId,
                                              long sequenceAfter) throws IOException {
        if (!INTENT_FORMAT.equals(requiredString(intent, "format"))) {
            throw new IOException("Coordinator journal intent format is unsupported: " + mutationId);
        }
        if (stagedState.get("rootSequence").getAsLong() != sequenceAfter) {
            throw new IOException("Coordinator journal staged state has the wrong root sequence: " + mutationId);
        }
        JsonObject mutation = null;
        for (JsonElement element : stagedState.getAsJsonArray("mutations")) {
            JsonObject candidate = element.getAsJsonObject();
            if (mutationId.toString().equals(requiredString(candidate, "mutationId"))) {
                mutation = candidate;
                break;
            }
        }
        if (mutation == null || !intentHash.equals(requiredString(mutation, "intentHash"))
            || mutation.getAsJsonObject("result").get("rootSequence").getAsLong() != sequenceAfter) {
            throw new IOException("Coordinator journal staged mutation does not match its binding: " + mutationId);
        }
        JsonObject result = mutation.getAsJsonObject("result");
        JsonObject resultProject = result.getAsJsonObject("project");
        if (!resultProject.equals(stagedState.getAsJsonObject("project"))) {
            throw new IOException("Coordinator journal project result does not match staged state: " + mutationId);
        }
        JsonObject expectedProject = intent.getAsJsonObject("expectedProject");
        ExpectedProject expectedProjectState = expectedProject(expectedProject);
        if (!expectedProject.equals(expectedProjectJson(expectedProjectState))) {
            throw new IOException("Coordinator journal expected project is not canonical: " + mutationId);
        }
        String projectBeforeHash = requiredHash(binding, "projectBeforeHash");
        String projectAfterHash = requiredHash(binding, "projectAfterHash");
        if (!projectBeforeHash.equals(expectedProjectState.hash())
            || !projectAfterHash.equals(requiredString(resultProject, "hash"))) {
            throw new IOException("Coordinator journal project hashes do not match its intent and result: " + mutationId);
        }
        AssetProjectMetadata beforeMetadata = AssetProjectMetadata.of(binding.getAsJsonObject("projectBefore"));
        List<AssetProjectMetadata.Delta> projectDeltas = new ArrayList<>();
        List<List<String>> normalizedProjectPaths = new ArrayList<>();
        for (JsonElement element : intent.getAsJsonArray("projectDeltas")) {
            JsonObject delta = element.getAsJsonObject();
            List<String> path = new ArrayList<>();
            delta.getAsJsonArray("path").forEach(segment -> path.add(segment.getAsString()));
            String operation = requiredString(delta, "operation");
            if (!operation.equals("REMOVE") && !operation.equals("SET")) {
                throw new IOException("Coordinator journal has an unknown project operation: " + operation);
            }
            if (!normalizedProjectPaths.isEmpty()
                && RootContext.comparePaths(normalizedProjectPaths.getLast(), path) >= 0) {
                throw new IOException("Coordinator journal project paths are not strictly normalized");
            }
            for (List<String> existing : normalizedProjectPaths) {
                if (RootContext.isPrefix(existing, path) || RootContext.isPrefix(path, existing)) {
                    throw new IOException("Coordinator journal project paths overlap");
                }
            }
            normalizedProjectPaths.add(List.copyOf(path));
            if (operation.equals("REMOVE")) {
                if (delta.has("value")) {
                    throw new IOException("Coordinator journal remove operation has a value");
                }
                projectDeltas.add(AssetProjectMetadata.Delta.remove(path));
            } else {
                if (!delta.has("value")) {
                    throw new IOException("Coordinator journal set operation has no value");
                }
                projectDeltas.add(AssetProjectMetadata.Delta.set(path, delta.get("value")));
            }
        }
        AssetProjectMetadata appliedMetadata = beforeMetadata.apply(projectDeltas);
        AssetProjectMetadata boundAfterMetadata = AssetProjectMetadata.of(binding.getAsJsonObject("projectAfter"));
        if (!appliedMetadata.canonicalJson().equals(boundAfterMetadata.canonicalJson())) {
            throw new IOException("Coordinator journal project delta result is invalid: " + mutationId);
        }
        boolean projectChanged = !beforeMetadata.canonicalJson().equals(boundAfterMetadata.canonicalJson());
        long expectedProjectRevision = projectChanged
            ? Math.addExact(expectedProjectState.revision(), 1L)
            : expectedProjectState.revision();
        if (resultProject.get("revision").getAsLong() != expectedProjectRevision) {
            throw new IOException("Coordinator journal project revision does not match its delta: " + mutationId);
        }
        if (!resultProject.equals(expectedProjectJson(new ExpectedProject(expectedProjectRevision, projectAfterHash)))) {
            throw new IOException("Coordinator journal project result is not canonical: " + mutationId);
        }
        Map<String, JsonObject> resultStates = new LinkedHashMap<>();
        for (JsonElement element : result.getAsJsonArray("states")) {
            JsonObject state = element.getAsJsonObject();
            String key = new AssetKey(requiredString(state, "type"), requiredString(state, "id")).canonical();
            if (resultStates.putIfAbsent(key, state.getAsJsonObject("state")) != null) {
                throw new IOException("Coordinator journal result contains a duplicate asset state: " + key);
            }
        }
        Map<String, AssetTransactionManager.TransactionOperation> remaining = new LinkedHashMap<>();
        for (AssetTransactionManager.TransactionOperation operation : operations) {
            if (remaining.putIfAbsent(operation.resource(), operation) != null) {
                throw new IOException("Coordinator journal base operations contain a duplicate path: " + operation.resource());
            }
        }
        requireOperation(remaining, ".asset-coordinator/state.json", "WRITE_BINARY", null, null);
        String previousAssetKey = null;
        Set<String> assetPaths = new LinkedHashSet<>();
        for (JsonElement element : intent.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            AssetKey assetKey = new AssetKey(requiredString(asset, "type"), requiredString(asset, "id"));
            String canonicalKey = assetKey.canonical();
            if (!canonicalKey.equals(requiredString(asset, "key"))
                || previousAssetKey != null && previousAssetKey.compareTo(canonicalKey) >= 0) {
                throw new IOException("Coordinator journal asset keys are not strictly normalized");
            }
            previousAssetKey = canonicalKey;
            String resource = requiredString(asset, "path");
            if (resource.chars().anyMatch(Character::isISOControl) || resource.indexOf('\\') >= 0) {
                throw new IOException("Coordinator journal asset path is not normalized: " + resource);
            }
            Path resourcePath = root.resolve(resource).normalize();
            String normalizedResource = root.relativize(resourcePath).toString().replace('\\', '/');
            if (!resourcePath.startsWith(root) || !resource.equals(normalizedResource) || resource.equals("project.json")
                || resource.startsWith(".transactions/") || resource.startsWith(".snapshots/")
                || resource.startsWith(".asset-coordinator/") || !assetPaths.add(resource)) {
                throw new IOException("Coordinator journal asset path is unsafe or duplicated: " + resource);
            }
            String assetOperation = requiredString(asset, "operation");
            if (!assetOperation.equals("WRITE") && !assetOperation.equals("DELETE")
                && !assetOperation.equals("RELOCATE") && !assetOperation.equals("RELOCATE_TRANSFORM")
                && !assetOperation.equals("RECLASSIFY")) {
                throw new IOException("Coordinator journal has an unknown asset operation: " + assetOperation);
            }
            boolean deleted = assetOperation.equals("DELETE");
            boolean relocated = assetOperation.equals("RELOCATE") || assetOperation.equals("RELOCATE_TRANSFORM");
            boolean transformedRelocation = assetOperation.equals("RELOCATE_TRANSFORM");
            boolean reclassified = assetOperation.equals("RECLASSIFY");
            String previousResource = null;
            if (relocated) {
                previousResource = requiredString(asset, "previousPath");
                if (previousResource.equals(resource) || previousResource.chars().anyMatch(Character::isISOControl)
                    || previousResource.indexOf('\\') >= 0) {
                    throw new IOException("Coordinator journal relocation source is not normalized: " + previousResource);
                }
                Path previousResourcePath = root.resolve(previousResource).normalize();
                String normalizedPrevious = root.relativize(previousResourcePath).toString().replace('\\', '/');
                if (!previousResourcePath.startsWith(root) || !previousResource.equals(normalizedPrevious)
                    || previousResource.equals("project.json") || previousResource.startsWith(".transactions/")
                    || previousResource.startsWith(".snapshots/") || previousResource.startsWith(".asset-coordinator/")
                    || !assetPaths.add(previousResource)) {
                    throw new IOException("Coordinator journal relocation source is unsafe or duplicated: "
                        + previousResource);
                }
            } else if (asset.has("previousPath")) {
                throw new IOException("Coordinator journal non-relocation has a previous path");
            }
            String payloadHash = requiredString(asset, "payloadHash");
            long payloadSize = asset.get("payloadSize").getAsLong();
            if (deleted ? !payloadHash.isEmpty() || payloadSize != 0L
                : !SHA_256.matcher(payloadHash).matches() || payloadSize < 0L) {
                throw new IOException("Coordinator journal asset payload is invalid: " + canonicalKey);
            }
            requireOperation(remaining, resource, deleted ? "DELETE" : "WRITE_BINARY", payloadHash, payloadSize);
            if (relocated) {
                requireOperation(remaining, previousResource, "DELETE", "", 0L);
            }
            JsonObject expected = asset.getAsJsonObject("expected");
            ExpectedState expectedAssetState = expectedState(expected);
            if (!expected.equals(expectedStateJson(expectedAssetState))) {
                throw new IOException("Coordinator journal expected asset state is not canonical: " + canonicalKey);
            }
            if (relocated && !(expectedAssetState instanceof Live)) {
                throw new IOException("Coordinator journal relocation source is not live: "
                    + canonicalKey);
            }
            if (relocated && !transformedRelocation && !expectedAssetState.hash().equals(payloadHash)) {
                throw new IOException("Coordinator journal relocation changes payload without an explicit transform");
            }
            ExpectedState revisionState = expectedAssetState;
            if (reclassified) {
                AssetKey sourceKey = new AssetKey(requiredString(asset, "lineageSourceType"),
                    requiredString(asset, "lineageSourceId"));
                if (!sourceKey.canonical().equals(requiredString(asset, "lineageSourceKey"))) {
                    throw new IOException("Coordinator journal reclassification source key is not canonical");
                }
                revisionState = expectedState(asset.getAsJsonObject("lineageSourceState"));
                if (!(expectedAssetState instanceof Missing) || !(revisionState instanceof Live)
                    || sourceKey.equals(assetKey)) {
                    throw new IOException("Coordinator journal reclassification source lineage is invalid");
                }
                JsonObject sourceIntent = null;
                for (JsonElement candidateElement : intent.getAsJsonArray("assets")) {
                    JsonObject candidate = candidateElement.getAsJsonObject();
                    if (sourceKey.canonical().equals(requiredString(candidate, "key"))) {
                        sourceIntent = candidate;
                        break;
                    }
                }
                if (sourceIntent == null || !"DELETE".equals(requiredString(sourceIntent, "operation"))
                    || !sourceIntent.getAsJsonObject("expected").equals(expectedStateJson(revisionState))) {
                    throw new IOException("Coordinator journal reclassification source CAS is missing");
                }
            } else if (asset.has("lineageSourceKey") || asset.has("lineageSourceType")
                || asset.has("lineageSourceId") || asset.has("lineageSourceState")) {
                throw new IOException("Coordinator journal non-reclassification has lineage fields");
            }
            JsonObject resulting = resultStates.remove(canonicalKey);
            long revision = Math.addExact(revisionState.revision(), 1L);
            String expectedHash = deleted
                ? StorageSafety.sha256("deleted\n" + canonicalKey + "\n" + revision + "\n" + mutationId)
                : payloadHash;
            ExpectedState expectedResult = deleted ? new Deleted(revision, expectedHash) : new Live(revision, expectedHash);
            if (resulting == null || !resulting.equals(expectedStateJson(expectedResult))) {
                throw new IOException("Coordinator journal result state does not match its intent: " + requiredString(asset, "key"));
            }
        }
        if (!resultStates.isEmpty()) {
            throw new IOException("Coordinator journal result contains states outside its intent: " + mutationId);
        }
        boolean projectWritten = binding.get("projectWritten").getAsBoolean();
        boolean projectExistedBefore = binding.get("projectExistedBefore").getAsBoolean();
        if (projectChanged && !projectWritten || !projectChanged && projectWritten && projectExistedBefore
            || !projectWritten && !projectBeforeHash.equals(projectAfterHash)
            || projectWritten && !boundAfterMetadata.hash().equals(projectAfterHash)) {
            throw new IOException("Coordinator journal project write binding is inconsistent: " + mutationId);
        }
        if (projectWritten) {
            requireOperation(remaining, "project.json", "WRITE_BINARY",
                requiredString(stagedState.getAsJsonObject("project"), "hash"), null);
        }
        if (!remaining.isEmpty()) {
            throw new IOException("Coordinator journal contains operations outside its normalized intent: " + remaining.keySet());
        }
    }

    private static Map<String, PreparedFileState> validatePreparedPhysicalState(Path root, String transactionId,
                                                                                String bindingResource,
                                                                                byte[] bindingBytes,
                                                                                JsonObject binding, JsonObject intent,
                                                                                String stateBeforeHash,
                                                                                String stateAfterHash,
                                                                                EvidenceSnapshot evidence)
        throws IOException {
        Map<String, PreparedFileState> states = new LinkedHashMap<>();
        addPreparedFileState(states, bindingResource, false, "", StorageSafety.sha256(bindingBytes));
        addPreparedFileState(states, ".asset-coordinator/state.json", true, stateBeforeHash, stateAfterHash);
        for (JsonElement element : intent.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            JsonObject expected = asset.getAsJsonObject("expected");
            String kind = requiredString(expected, "kind");
            String operation = requiredString(asset, "operation");
            boolean deleted = "DELETE".equals(operation);
            boolean relocated = "RELOCATE".equals(operation) || "RELOCATE_TRANSFORM".equals(operation);
            String preHash = "LIVE".equals(kind) ? requiredHash(expected, "hash") : "";
            String postHash = deleted ? "" : requiredHash(asset, "payloadHash");
            boolean missingBefore = !"LIVE".equals(kind);
            if (relocated) {
                addPreparedFileState(states, requiredString(asset, "previousPath"), true, preHash, "");
                addPreparedFileState(states, requiredString(asset, "path"), false, "", postHash);
            } else {
                String resource = requiredString(asset, "path");
                addPreparedFileState(states, resource, !missingBefore, preHash, postHash);
            }
        }
        boolean projectExistedBefore = binding.get("projectExistedBefore").getAsBoolean();
        if (binding.get("projectWritten").getAsBoolean()) {
            addPreparedFileState(states, "project.json", projectExistedBefore,
                projectExistedBefore ? requiredHash(binding, "projectBeforeHash") : "",
                requiredHash(binding, "projectAfterHash"));
        }
        for (Map.Entry<String, PreparedFileState> entry : states.entrySet()) {
            validatePreparedFileState(root, transactionId, entry.getKey(), entry.getValue(), evidence);
        }
        return Map.copyOf(states);
    }

    private static void addPreparedFileState(Map<String, PreparedFileState> states, String resource, boolean existed,
                                             String preHash, String postHash) throws IOException {
        PreparedFileState state = new PreparedFileState(existed, preHash, postHash);
        if (states.putIfAbsent(resource, state) != null) {
            throw new IOException("Prepared coordinator transaction repeats a physical target: " + resource);
        }
    }

    private static AssetTransactionManager.RecoveryEvidence preparedRecoveryFiles(
        EvidenceSnapshot evidence, Map<String, Map<String, PreparedFileState>> preparedFileStates) throws IOException {
        Path root = evidence.root();
        Path snapshots = root.resolve(".snapshots");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        Set<Path> snapshotsToCreate = new LinkedHashSet<>();
        for (AssetTransactionManager.TransactionInspection inspection : evidence.inspections()) {
            if (!PREPARED.equals(inspection.visibility().state())) {
                continue;
            }
            Path transactionSnapshots = snapshots.resolve(inspection.visibility().transactionId()).normalize();
            Map<String, PreparedFileState> states = preparedFileStates.get(inspection.visibility().transactionId());
            if (states == null) {
                throw new IOException("Prepared coordinator transaction has no frozen physical evidence");
            }
            Set<String> remaining = new LinkedHashSet<>(states.keySet());
            for (AssetTransactionManager.JournalEntryEvidence entry : inspection.journalEvidence().entries()) {
                PreparedFileState state = states.get(entry.target());
                if (state == null || !remaining.remove(entry.target()) || !Objects.equals(entry.existed(), state.existed)
                    || entry.delete() != state.postHash.isEmpty()
                    || !entry.delete() && !entry.hash().equals(state.postHash)) {
                    throw new IOException("Prepared journal physical evidence is inconsistent: " + entry.target());
                }
                Path target = root.resolve(entry.target()).normalize();
                Path snapshot = transactionSnapshots.resolve(entry.target()).normalize();
                evidence.revalidate(target);
                evidence.revalidate(snapshot);
                if (state.existed) {
                    byte[] prior;
                    if (evidence.isRegularFile(snapshot)) {
                        prior = evidence.retainedBytesView(snapshot);
                    } else {
                        prior = evidence.retainedBytesView(target);
                        snapshotsToCreate.add(snapshot.toAbsolutePath().normalize());
                    }
                    if (!state.preHash.equals(StorageSafety.sha256(prior))) {
                        throw new IOException("Frozen prepared pre-state changed before recovery: " + entry.target());
                    }
                    files.put(target.toAbsolutePath().normalize(), prior);
                }
            }
            if (!remaining.isEmpty()) {
                throw new IOException("Prepared journal is missing physical evidence: " + remaining);
            }
        }
        return new AssetTransactionManager.RecoveryEvidence(files, snapshotsToCreate);
    }

    private static void validateFinalControlEvidence(EvidenceSnapshot evidence) throws IOException {
        Path root = evidence.root();
        evidence.revalidate(root.resolve("project.json"));
        evidence.revalidate(root.resolve(".asset-coordinator/state.json"));
        evidence.revalidate(root.resolve(".asset-coordinator/genesis.json"));
        Path checkpoint = root.resolve(".asset-coordinator").resolve(HISTORY_CHECKPOINT_FILE);
        if (evidence.exists(checkpoint)) {
            evidence.revalidate(checkpoint);
        }
        for (AssetTransactionManager.TransactionInspection inspection : evidence.inspections()) {
            evidence.revalidate(root.resolve(".asset-coordinator/bindings")
                .resolve(inspection.visibility().mutationId() + ".json"));
        }
    }

    private static void validateFinalAssetEvidence(CoordinatorState state, EvidenceSnapshot evidence) throws IOException {
        Path root = evidence.root();
        for (ResourceEntry entry : state.resources.values().stream().sorted(Comparator.comparing(ResourceEntry::path)).toList()) {
            if (entry.state instanceof Live) {
                evidence.revalidate(root.resolve(entry.path).normalize());
            }
        }
    }

    private static void validateFinalNamespaceEvidence(EvidenceSnapshot evidence) throws IOException {
        Path root = evidence.root();
        EvidencePathBudget pathBudget = new EvidencePathBudget(evidence.limits().maximumPaths());
        Set<Path> observed = new LinkedHashSet<>();
        Files.walkFileTree(root, Set.of(), evidence.limits().maximumDepth(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                validate(directory, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isDirectory()) {
                    throw new IOException("Asset final evidence exceeded maximum depth: " + root.relativize(file));
                }
                validate(file, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }

            private void validate(Path path, BasicFileAttributes attributes) throws IOException {
                Path normalized = path.toAbsolutePath().normalize();
                pathBudget.visit(root, normalized);
                EvidencePath expected = evidence.paths().get(normalized);
                if (expected == null || !expected.sameNamespaceIdentity(EvidencePath.of(attributes))) {
                    throw new IOException("Asset namespace changed during coordinator validation: "
                        + root.relativize(normalized));
                }
                observed.add(normalized);
            }
        });
        if (!observed.equals(evidence.paths().keySet())) {
            throw new IOException("Asset namespace changed during coordinator validation");
        }
    }

    private static void requireUnchangedStartupNamespace(EvidenceWatcher watcher, String stage) throws IOException {
        if (watcher.changed()) {
            throw new IOException("Asset namespace changed during startup " + stage + ": " + watcher.changeReason());
        }
    }

    private static void validatePreparedFileState(Path root, String transactionId, String resource,
                                                  PreparedFileState state, EvidenceSnapshot evidence) throws IOException {
        Path target = root.resolve(resource).normalize();
        Path snapshotRoot = root.resolve(".snapshots").resolve(transactionId).normalize();
        Path snapshot = snapshotRoot.resolve(resource).normalize();
        if (!target.startsWith(root) || !snapshot.startsWith(snapshotRoot)) {
            throw new IOException("Prepared coordinator transaction target is unsafe: " + resource);
        }
        boolean targetExists = exists(target, evidence);
        boolean snapshotExists = exists(snapshot, evidence);
        String targetHash = "";
        if (targetExists) {
            if (isSymbolicLink(target, evidence) || !isRegularFile(target, evidence)) {
                throw new IOException("Prepared coordinator transaction target is unsafe: " + resource);
            }
            targetHash = contentHash(target, evidence);
            if (!targetHash.equals(state.preHash) && !targetHash.equals(state.postHash)) {
                throw new IOException("Prepared coordinator transaction target is in an unexpected third state: " + resource);
            }
        }
        if (snapshotExists) {
            if (isSymbolicLink(snapshot, evidence) || !isRegularFile(snapshot, evidence)
                || !state.existed || !state.preHash.equals(contentHash(snapshot, evidence))) {
                throw new IOException("Prepared coordinator transaction snapshot is not its exact pre-state: " + resource);
            }
        }
        if (!state.existed) {
            if (!targetExists || targetHash.equals(state.postHash)) {
                return;
            }
            throw new IOException("Prepared coordinator transaction has an impossible absent pre-state: " + resource);
        }
        if (targetExists && targetHash.equals(state.preHash)) {
            return;
        }
        if (!snapshotExists) {
            throw new IOException("Prepared coordinator transaction lost its exact pre-state snapshot: " + resource);
        }
    }

    private static boolean exists(Path path, EvidenceSnapshot evidence) {
        return evidence == null ? Files.exists(path, LinkOption.NOFOLLOW_LINKS) : evidence.exists(path);
    }

    private static boolean isRegularFile(Path path, EvidenceSnapshot evidence) {
        return evidence == null ? Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) : evidence.isRegularFile(path);
    }

    private static boolean isSymbolicLink(Path path, EvidenceSnapshot evidence) {
        return evidence == null ? Files.isSymbolicLink(path) : evidence.isSymbolicLink(path);
    }

    private static byte[] content(Path path, EvidenceSnapshot evidence) throws IOException {
        return evidence == null ? Files.readAllBytes(path) : evidence.content(path);
    }

    private static String contentHash(Path path, EvidenceSnapshot evidence) throws IOException {
        return evidence == null ? StorageSafety.sha256(Files.readAllBytes(path)) : evidence.hash(path);
    }

    private static void requireOperation(Map<String, AssetTransactionManager.TransactionOperation> remaining,
                                         String resource, String operation, String hash, Long size) throws IOException {
        AssetTransactionManager.TransactionOperation actual = remaining.remove(resource);
        if (actual == null || !actual.operation().equals(operation)
            || hash != null && !actual.payloadHash().equals(hash)
            || size != null && actual.payloadSize() != size) {
            throw new IOException("Coordinator journal operation does not match its intent: " + resource);
        }
    }

    private static List<AssetTransactionManager.TransactionOperation> operations(JsonArray array) throws IOException {
        if (array == null) {
            throw new IOException("Coordinator journal binding has no base operations");
        }
        List<AssetTransactionManager.TransactionOperation> operations = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject operation = element.getAsJsonObject();
            operations.add(new AssetTransactionManager.TransactionOperation(requiredString(operation, "resource"),
                requiredString(operation, "operation"), requiredString(operation, "payloadHash"),
                operation.get("payloadSize").getAsLong()));
        }
        return List.copyOf(operations);
    }

    private static JsonObject operationJson(AssetTransactionManager.TransactionOperation operation) {
        JsonObject object = new JsonObject();
        object.addProperty("resource", operation.resource());
        object.addProperty("operation", operation.operation());
        object.addProperty("payloadHash", operation.payloadHash());
        object.addProperty("payloadSize", operation.payloadSize());
        return object;
    }

    private static JsonObject validateStateDocument(byte[] content) throws IOException {
        JsonObject object = parseObject(content, "Coordinator staged state");
        String storedHash = requiredHash(object, "stateHash");
        JsonObject unhashed = object.deepCopy();
        unhashed.remove("stateHash");
        if (!storedHash.equals(StorageSafety.sha256(AssetProjectMetadata.of(unhashed).canonicalJson()))) {
            throw new IOException("Coordinator staged state hash does not match its content");
        }
        if (object.get("version").getAsInt() != STATE_VERSION || !STATE_FORMAT.equals(requiredString(object, "format"))) {
            throw new IOException("Coordinator staged state version is unsupported");
        }
        coordinatorState(object);
        return object;
    }

    private static JsonObject parseObject(byte[] content, String field) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException(field + " is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException(field + " is corrupt", failure);
        }
    }

    private static void validateInternalEvidence(Path root) throws IOException {
        try {
            validateInternalEvidenceContent(root);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator internal evidence is corrupt", failure);
        }
    }

    private static void validateInternalEvidence(EvidenceSnapshot evidence) throws IOException {
        try {
            Path root = evidence.root();
            Path snapshots = root.resolve(".snapshots");
            Path coordinator = root.resolve(".asset-coordinator");
            Path bindings = coordinator.resolve("bindings");
            Set<Path> allowedSnapshots = new LinkedHashSet<>();
            Set<Path> allowedSnapshotDirs = new LinkedHashSet<>();
            Set<Path> requiredSnapshots = new LinkedHashSet<>();
            Set<Path> allowedBindings = new LinkedHashSet<>();
            Set<Path> requiredBindings = new LinkedHashSet<>();
            for (AssetTransactionManager.TransactionInspection inspection : evidence.inspections()) {
                AssetTransactionManager.TransactionVisibility visibility = inspection.visibility();
                boolean committed = COMMITTED.equals(visibility.state());
                Path transactionSnapshots = snapshots.resolve(visibility.transactionId()).normalize();
                allowedSnapshotDirs.add(transactionSnapshots);
                List<Path> journalBindings = new ArrayList<>();
                boolean hasCoordinatorState = false;
                for (AssetTransactionManager.JournalEntryEvidence entry : inspection.journalEvidence().entries()) {
                    String target = entry.target();
                    Path targetSnapshot = transactionSnapshots.resolve(target).normalize();
                    if (!targetSnapshot.startsWith(transactionSnapshots)) {
                        throw new IOException("Asset transaction snapshot path is unsafe: " + target);
                    }
                    if (Boolean.TRUE.equals(entry.existed())) {
                        allowedSnapshots.add(targetSnapshot);
                        Path parent = targetSnapshot.getParent();
                        while (parent != null && parent.startsWith(transactionSnapshots)) {
                            allowedSnapshotDirs.add(parent);
                            if (parent.equals(transactionSnapshots)) {
                                break;
                            }
                            parent = parent.getParent();
                        }
                        if (committed) {
                            requiredSnapshots.add(targetSnapshot);
                        }
                    }
                    if (target.startsWith(".asset-coordinator/bindings/")) {
                        Path binding = root.resolve(target).normalize();
                        if (!binding.startsWith(bindings) || !bindings.equals(binding.getParent())) {
                            throw new IOException("Coordinator binding path is unsafe: " + target);
                        }
                        journalBindings.add(binding);
                    }
                    if (target.equals(".asset-coordinator/state.json")) {
                        hasCoordinatorState = true;
                    }
                }
                if (!journalBindings.isEmpty()) {
                    UUID mutationId;
                    try {
                        mutationId = UUID.fromString(visibility.mutationId());
                    } catch (IllegalArgumentException failure) {
                        throw new IOException("Coordinator journal mutation ID is invalid", failure);
                    }
                    Path expectedBinding = bindings.resolve(mutationId + ".json");
                    if (!hasCoordinatorState || journalBindings.size() != 1
                        || !journalBindings.getFirst().equals(expectedBinding)) {
                        throw new IOException("Transaction journal does not own its coordinator binding");
                    }
                    allowedBindings.add(expectedBinding);
                    if (committed) {
                        requiredBindings.add(expectedBinding);
                    }
                } else if (hasCoordinatorState) {
                    throw new IOException("Transaction journal has coordinator state without a binding");
                }
            }
            if (!evidence.isDirectory(snapshots) || evidence.isSymbolicLink(snapshots)) {
                throw new IOException("Asset transaction snapshot root is unsafe: " + snapshots);
            }
            for (Path path : evidence.descendants(snapshots)) {
                if (evidence.isSymbolicLink(path)) {
                    throw new IOException("Unexpected symbolic link in asset transaction snapshots: " + path);
                }
                if (evidence.isRegularFile(path) && !allowedSnapshots.contains(path)) {
                    throw new IOException("Unexpected asset transaction snapshot: " + path);
                }
                if (evidence.isDirectory(path) && !allowedSnapshotDirs.contains(path)) {
                    throw new IOException("Unexpected directory in asset transaction snapshots: " + path);
                }
            }
            for (Path required : requiredSnapshots) {
                if (evidence.isSymbolicLink(required) || !evidence.isRegularFile(required)) {
                    throw new IOException("Committed asset transaction snapshot is missing: " + required);
                }
                evidence.captured(required);
            }
            Path rootLock = coordinator.resolve("root.lock");
            Path state = coordinator.resolve("state.json");
            Path genesis = coordinator.resolve("genesis.json");
            Path checkpoint = coordinator.resolve(HISTORY_CHECKPOINT_FILE);
            if (evidence.isSymbolicLink(coordinator) || !evidence.isDirectory(coordinator)
                || evidence.isSymbolicLink(bindings) || !evidence.isDirectory(bindings)
                || evidence.isSymbolicLink(rootLock) || !evidence.isRegularFile(rootLock)
                || evidence.isSymbolicLink(genesis) || !evidence.isRegularFile(genesis)
                || evidence.exists(checkpoint) && (evidence.isSymbolicLink(checkpoint)
                    || !evidence.isRegularFile(checkpoint))
                || evidence.exists(state) && (evidence.isSymbolicLink(state) || !evidence.isRegularFile(state))) {
                throw new IOException("Asset coordinator authority files are unsafe");
            }
            for (Path path : evidence.descendants(coordinator)) {
                if (path.equals(bindings) || path.equals(rootLock) || path.equals(state) || path.equals(genesis)
                    || path.equals(checkpoint)) {
                    continue;
                }
                if (!allowedBindings.contains(path) || evidence.isSymbolicLink(path)
                    || !evidence.isRegularFile(path)) {
                    throw new IOException("Unexpected asset coordinator internal content: " + path);
                }
            }
            for (Path required : requiredBindings) {
                if (evidence.isSymbolicLink(required) || !evidence.isRegularFile(required)) {
                    throw new IOException("Committed coordinator binding is missing: " + required);
                }
                evidence.captured(required);
            }
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator internal evidence is corrupt", failure);
        }
    }

    private static void validateInternalEvidenceContent(Path root) throws IOException {
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path bindings = coordinator.resolve("bindings");
        Set<Path> allowedSnapshots = new LinkedHashSet<>();
        Set<Path> allowedSnapshotDirs = new LinkedHashSet<>();
        Set<Path> requiredSnapshots = new LinkedHashSet<>();
        Set<Path> allowedBindings = new LinkedHashSet<>();
        Set<Path> requiredBindings = new LinkedHashSet<>();
        if (Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> transactionDirs = Files.list(transactions)) {
                for (Path transactionDir : transactionDirs.toList()) {
                    Path journal = transactionDir.resolve("journal.json");
                    JsonObject object = parseObject(Files.readAllBytes(journal), "Asset transaction journal");
                    boolean committed = COMMITTED.equals(requiredString(object, "state"));
                    Path transactionSnapshots = snapshots.resolve(requiredString(object, "id")).normalize();
                    allowedSnapshotDirs.add(transactionSnapshots);
                    List<Path> journalBindings = new ArrayList<>();
                    boolean hasCoordinatorState = false;
                    for (JsonElement element : object.getAsJsonArray("entries")) {
                        JsonObject entry = element.getAsJsonObject();
                        String target = requiredString(entry, "target");
                        Path targetSnapshot = transactionSnapshots.resolve(target).normalize();
                        if (!targetSnapshot.startsWith(transactionSnapshots)) {
                            throw new IOException("Asset transaction snapshot path is unsafe: " + target);
                        }
                        if (entry.has("existed") && !entry.get("existed").isJsonNull()
                            && entry.get("existed").getAsBoolean()) {
                            allowedSnapshots.add(targetSnapshot);
                            Path parent = targetSnapshot.getParent();
                            while (parent != null && parent.startsWith(transactionSnapshots)) {
                                allowedSnapshotDirs.add(parent);
                                if (parent.equals(transactionSnapshots)) {
                                    break;
                                }
                                parent = parent.getParent();
                            }
                            if (committed) {
                                requiredSnapshots.add(targetSnapshot);
                            }
                        }
                        if (target.startsWith(".asset-coordinator/bindings/")) {
                            Path binding = root.resolve(target).normalize();
                            if (!binding.startsWith(bindings) || !bindings.equals(binding.getParent())) {
                                throw new IOException("Coordinator binding path is unsafe: " + target);
                            }
                            journalBindings.add(binding);
                        }
                        if (target.equals(".asset-coordinator/state.json")) {
                            hasCoordinatorState = true;
                        }
                    }
                    if (!journalBindings.isEmpty()) {
                        UUID mutationId;
                        try {
                            mutationId = UUID.fromString(requiredString(object, "mutationId"));
                        } catch (IllegalArgumentException failure) {
                            throw new IOException("Coordinator journal mutation ID is invalid", failure);
                        }
                        Path expectedBinding = bindings.resolve(mutationId + ".json");
                        if (!hasCoordinatorState || journalBindings.size() != 1
                            || !journalBindings.getFirst().equals(expectedBinding)) {
                            throw new IOException("Transaction journal does not own its coordinator binding");
                        }
                        allowedBindings.add(expectedBinding);
                        if (committed) {
                            requiredBindings.add(expectedBinding);
                        }
                    } else if (hasCoordinatorState) {
                        throw new IOException("Transaction journal has coordinator state without a binding");
                    }
                }
            }
        }
        if (Files.exists(snapshots, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(snapshots) || !Files.isDirectory(snapshots, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset transaction snapshot root is unsafe: " + snapshots);
            }
            try (Stream<Path> snapshotPaths = Files.walk(snapshots)) {
                for (Path path : snapshotPaths.toList()) {
                    if (path.equals(snapshots)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new IOException("Unexpected symbolic link in asset transaction snapshots: " + path);
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !allowedSnapshots.contains(path)) {
                        throw new IOException("Unexpected asset transaction snapshot: " + path);
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !allowedSnapshotDirs.contains(path)) {
                        throw new IOException("Unexpected directory in asset transaction snapshots: " + path);
                    }
                }
            }
        }
        for (Path required : requiredSnapshots) {
            if (Files.isSymbolicLink(required) || !Files.isRegularFile(required, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Committed asset transaction snapshot is missing: " + required);
            }
        }
        if (Files.exists(coordinator, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(coordinator) || !Files.isDirectory(coordinator, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset coordinator internal root is unsafe: " + coordinator);
            }
        Path rootLock = coordinator.resolve("root.lock");
        Path state = coordinator.resolve("state.json");
        Path genesis = coordinator.resolve("genesis.json");
        Path checkpoint = coordinator.resolve(HISTORY_CHECKPOINT_FILE);
            if (Files.isSymbolicLink(bindings) || !Files.isDirectory(bindings, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(rootLock) || !Files.isRegularFile(rootLock, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(genesis) || !Files.isRegularFile(genesis, LinkOption.NOFOLLOW_LINKS)
            || Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(checkpoint)
                || !Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS))
            || Files.exists(state, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(state) || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Asset coordinator authority files are unsafe");
            }
            try (Stream<Path> coordinatorPaths = Files.walk(coordinator)) {
                for (Path path : coordinatorPaths.toList()) {
                    if (path.equals(coordinator) || path.equals(bindings) || path.equals(rootLock) || path.equals(state)
                        || path.equals(genesis) || path.equals(checkpoint)) {
                        continue;
                    }
                    if (!allowedBindings.contains(path) || Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unexpected asset coordinator internal content: " + path);
                    }
                }
            }
        }
        for (Path required : requiredBindings) {
            if (Files.isSymbolicLink(required) || !Files.isRegularFile(required, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Committed coordinator binding is missing: " + required);
            }
        }
    }

    private static void validateDurableHistory(CoordinatorState current, String currentIdentity,
                                               ValidatedJournals validated) throws IOException {
        validateDurableHistory(current, currentIdentity, true, validated);
    }

    private static void validateDurableHistory(CoordinatorState current,
                                               ValidatedJournals validated) throws IOException {
        validateDurableHistory(current, null, false, validated);
    }

    private static void validateDurableHistory(CoordinatorState current, String currentIdentity,
                                               boolean requireIdentity, ValidatedJournals validated) throws IOException {
        if (validated.checkpointTip != null) {
            CheckpointTip tip = validated.checkpointTip;
            if (!validated.preparedFingerprints.isEmpty() || validated.preparedTransition != null
                || !validated.transitions.isEmpty() || tip.sequence != current.rootSequence || !tip.state.equals(current)
                || requireIdentity && !tip.stateIdentity.equals(currentIdentity)
                || !validated.coordinatorJournals.equals(current.mutations.keySet())) {
                throw new IOException("Durable coordinator state does not match its trusted history checkpoint");
            }
            return;
        }
        CoordinatorState chained = validated.genesis.state;
        CoordinatorState preparedBefore = null;
        Set<UUID> chainedMutations = new LinkedHashSet<>();
        for (long sequence = 1L; sequence <= validated.transitions.size(); sequence++) {
            PreparedTransition transition = validated.transitions.get(sequence);
            if (transition == null || transition.staged.rootSequence != sequence
                || !chainedMutations.add(transition.mutationId)) {
                throw new IOException("Coordinator journal history is incomplete or duplicated at sequence " + sequence);
            }
            if (transition.equals(validated.preparedTransition)) {
                preparedBefore = chained;
            }
            chained = applyTransition(chained, transition);
        }
        if (!chainedMutations.equals(validated.coordinatorJournals)) {
            throw new IOException("Coordinator journal history does not own the durable mutation set");
        }
        PreparedTransition prepared = validated.preparedTransition;
        if (prepared != null && (preparedBefore == null || prepared.staged.rootSequence != validated.transitions.size())) {
            throw new IOException("Prepared coordinator journal is not the final durable history transition");
        }
        boolean currentIsTip = current.equals(chained);
        boolean currentIsPreparedBefore = prepared != null && current.equals(preparedBefore);
        if (!currentIsTip && !currentIsPreparedBefore) {
            throw new IOException("Durable coordinator state does not match its complete journal history");
        }
        if (!requireIdentity) {
            return;
        }
        if (currentIdentity == null) {
            if (current.rootSequence != 0L) {
                throw new IOException("Durable coordinator state file is missing from retained history");
            }
            return;
        }
        String expectedIdentity = current.rootSequence == 0L ? validated.genesis.stateIdentity
            : validated.transitions.get(current.rootSequence).stateIdentity;
        if (expectedIdentity == null || !expectedIdentity.equals(currentIdentity)) {
            throw new IOException("Durable coordinator state document does not match its journal history tip");
        }
    }

    private static CoordinatorState applyTransition(CoordinatorState current,
                                                    PreparedTransition transition) throws IOException {
        CoordinatorState staged = transition.staged;
        if (staged.rootSequence != Math.addExact(current.rootSequence, 1L)
            || !current.project.equals(transition.expectedProject)) {
            throw new IOException("Coordinator journal does not continue the previous durable state");
        }
        Map<UUID, MutationRecord> expectedMutations = new LinkedHashMap<>(current.mutations);
        expectedMutations.put(transition.mutationId, staged.mutations.get(transition.mutationId));
        if (!expectedMutations.equals(staged.mutations)) {
            throw new IOException("Coordinator journal rewrites durable mutation history");
        }
        Map<AssetKey, ResourceEntry> expectedResources = new LinkedHashMap<>(current.resources);
        Map<String, AssetKey> pathOwners = new LinkedHashMap<>();
        current.resources.forEach((key, entry) -> pathOwners.put(entry.path, key));
        for (Map.Entry<AssetKey, PreparedAsset> entry : transition.assets.entrySet()) {
            AssetKey key = entry.getKey();
            PreparedAsset asset = entry.getValue();
            ResourceEntry existing = current.resources.get(key);
            ExpectedState currentState = existing == null ? Missing.INSTANCE : existing.state;
            boolean recreated = existing != null && currentState instanceof Deleted
                && asset.expected.equals(currentState) && asset.result instanceof Live && asset.previousPath == null;
            String sourcePath = asset.previousPath != null ? asset.previousPath : recreated ? existing.path : asset.path;
            AssetKey pathOwner = pathOwners.get(asset.path);
            if (!currentState.equals(asset.expected) || existing != null && !existing.path.equals(sourcePath)
                || pathOwner != null && !pathOwner.equals(key)) {
                throw new IOException("Coordinator journal asset does not continue durable state: " + key.canonical());
            }
            if (recreated) {
                pathOwners.remove(existing.path);
            }
            if (asset.previousPath != null) {
                if (existing == null || !(existing.state instanceof Live) || !(asset.result instanceof Live)) {
                    throw new IOException("Coordinator journal relocation does not preserve live asset state: "
                        + key.canonical());
                }
                pathOwners.remove(asset.previousPath);
            }
            expectedResources.put(key, new ResourceEntry(key, asset.path, asset.result,
                AssetMutationId.of(transition.mutationId)));
            pathOwners.put(asset.path, key);
        }
        if (!expectedResources.equals(staged.resources)) {
            throw new IOException("Coordinator journal rewrites resources outside its intent");
        }
        if (!current.blocked.equals(staged.blocked)) {
            throw new IOException("Coordinator journal rewrites blocked adoption evidence");
        }
        return staged;
    }

    private static void validateRecoveryEvidence(Path root, CoordinatorState state,
                                                 ValidatedJournals validated) throws IOException {
        validateRecoveryEvidence(root, state, validated, null);
    }

    private static void validateRecoveryEvidence(Path root, CoordinatorState state,
                                                   ValidatedJournals validated, AdoptionInventory adoption) throws IOException {
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path migrations = root.resolve(".migrations");
        Path quarantine = root.resolve(".quarantine");
        Path project = root.resolve("project.json");
        Set<Path> adoptionEvidence = adoption == null ? Set.of() : validateAdoptionEvidence(root, transactions, snapshots,
            coordinator, project, adoption.evidence(), false);
        Set<Path> tracked = new LinkedHashSet<>();
        for (ResourceEntry entry : state.resources.values()) {
            Path path = root.resolve(entry.path).normalize();
            validateManagedPath(root, transactions, snapshots, coordinator, project, entry.path, path);
            tracked.add(path);
            if (validated.preparedAssetPaths.contains(path)) {
                continue;
            }
            if (entry.state instanceof Live live) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(path)))) {
                    throw new IOException("Live asset does not match coordinator state before recovery: "
                        + entry.key.canonical());
                }
            } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Deleted asset exists before coordinator recovery: " + entry.key.canonical());
            }
        }
        Set<Path> allowed = new LinkedHashSet<>(tracked);
        allowed.addAll(validated.preparedAssetPaths);
        allowed.addAll(adoptionEvidence);
        allowed.add(project);
        try (Stream<Path> diskPaths = Files.walk(root)) {
            for (Path path : diskPaths.filter(path -> !path.startsWith(transactions) && !path.startsWith(snapshots)
                && !path.startsWith(coordinator) && !path.startsWith(migrations)
                && !path.startsWith(quarantine)).toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Unregistered symbolic link exists before coordinator recovery: "
                        + root.relativize(path));
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !allowed.contains(path.normalize())) {
                    throw new IOException("Unregistered physical asset exists before coordinator recovery: "
                        + root.relativize(path));
                }
            }
        }
        if (validated.preparedFingerprints.isEmpty()) {
            AssetProjectMetadata metadata = readMetadata(project);
            if (!state.project.hash().equals(metadata.hash())) {
                throw new IOException("Asset project metadata changed before coordinator recovery");
            }
        }
    }

    private static void validateRecoveryEvidence(Path root, CoordinatorState state, ValidatedJournals validated,
                                                  AdoptionInventory adoption, EvidenceSnapshot evidence) throws IOException {
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path project = root.resolve("project.json");
        Set<Path> adoptionEvidence = adoption == null ? Set.of() : validateAdoptionEvidence(root, transactions, snapshots,
            coordinator, project, adoption.evidence(), false, evidence);
        Set<Path> tracked = new LinkedHashSet<>();
        for (ResourceEntry entry : state.resources.values()) {
            Path path = root.resolve(entry.path).normalize();
            validateManagedPath(root, transactions, snapshots, coordinator, project, entry.path, path);
            tracked.add(path);
            if (validated.preparedAssetPaths.contains(path)) {
                continue;
            }
            if (entry.state instanceof Live live) {
                if (evidence.isSymbolicLink(path) || !evidence.isRegularFile(path)
                    || !live.hash().equals(evidence.hash(path))) {
                    throw new IOException("Live asset does not match coordinator state before recovery: "
                        + entry.key.canonical());
                }
            } else if (evidence.exists(path)) {
                throw new IOException("Deleted asset exists before coordinator recovery: " + entry.key.canonical());
            }
        }
        Set<Path> allowed = new LinkedHashSet<>(tracked);
        allowed.addAll(validated.preparedAssetPaths);
        allowed.addAll(adoptionEvidence);
        allowed.add(project);
        for (Path path : evidence.externalPaths()) {
            if (evidence.isSymbolicLink(path)) {
                throw new IOException("Unregistered symbolic link exists before coordinator recovery: "
                    + root.relativize(path));
            }
            if (evidence.isRegularFile(path) && !allowed.contains(path.normalize())) {
                throw new IOException("Unregistered physical asset exists before coordinator recovery: "
                    + root.relativize(path));
            }
        }
        if (validated.preparedFingerprints.isEmpty()) {
            AssetProjectMetadata metadata = readMetadata(evidence.contentOrNull(project), project);
            if (!state.project.hash().equals(metadata.hash())) {
                throw new IOException("Asset project metadata changed before coordinator recovery");
            }
        }
    }

    private static Set<Path> validateAdoptionEvidence(Path root, Path transactions, Path snapshots, Path coordinator,
                                                      Path project, List<AdoptionEvidence> evidence,
                                                      boolean requirePresent) throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        for (AdoptionEvidence item : evidence) {
            Path path = root.resolve(item.originalPath()).normalize();
            validateEvidencePath(root, transactions, snapshots, coordinator, project, item.originalPath(), path);
            if (!paths.add(path)) {
                throw new IOException("Asset adoption evidence contains a duplicate physical path: " + item.originalPath());
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (requirePresent) {
                    throw new IOException("Asset adoption evidence is missing: " + item.originalPath());
                }
                continue;
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset adoption evidence is not a safe regular file: " + item.originalPath());
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != item.size() || !item.hash().equals(StorageSafety.sha256(bytes))) {
                throw new IOException("Asset adoption evidence bytes do not match its verified hash: " + item.originalPath());
            }
        }
        return paths;
    }

    private static Set<Path> validateAdoptionEvidence(Path root, Path transactions, Path snapshots, Path coordinator,
                                                      Path project, List<AdoptionEvidence> evidence,
                                                      boolean requirePresent, EvidenceSnapshot snapshot) throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        for (AdoptionEvidence item : evidence) {
            Path path = root.resolve(item.originalPath()).normalize();
            validateEvidencePath(root, transactions, snapshots, coordinator, project, item.originalPath(), path);
            if (!paths.add(path)) {
                throw new IOException("Asset adoption evidence contains a duplicate physical path: " + item.originalPath());
            }
            if (!snapshot.exists(path)) {
                if (requirePresent) {
                    throw new IOException("Asset adoption evidence is missing: " + item.originalPath());
                }
                continue;
            }
            if (snapshot.isSymbolicLink(path) || !snapshot.isRegularFile(path)) {
                throw new IOException("Asset adoption evidence is not a safe regular file: " + item.originalPath());
            }
            CapturedContent content = snapshot.captured(path);
            if (content.size() != item.size() || !item.hash().equals(content.hash())) {
                throw new IOException("Asset adoption evidence bytes do not match its verified hash: " + item.originalPath());
            }
        }
        return paths;
    }

    private static void validateManagedPath(Path root, Path transactions, Path snapshots, Path coordinator,
                                            Path project, String resource, Path path) throws IOException {
        validatePath(root, transactions, snapshots, coordinator, project, resource, path, false);
    }

    private static void validateEvidencePath(Path root, Path transactions, Path snapshots, Path coordinator,
                                             Path project, String resource, Path path) throws IOException {
        validatePath(root, transactions, snapshots, coordinator, project, resource, path, true);
    }

    private static void validatePath(Path root, Path transactions, Path snapshots, Path coordinator,
                                     Path project, String resource, Path path, boolean allowReservedEvidence) throws IOException {
        Path migrations = root.resolve(".migrations");
        if (resource.chars().anyMatch(Character::isISOControl) || !path.startsWith(root) || path.equals(root)
            || path.startsWith(transactions) || path.startsWith(snapshots) || path.startsWith(coordinator)
            || path.equals(project) || !allowReservedEvidence && path.startsWith(migrations)) {
            throw new IOException("Asset coordinator state contains an unsafe path: " + resource);
        }
    }

    private static boolean hasUnregisteredEvidence(Path root) throws IOException {
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path bindings = coordinator.resolve("bindings");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.startsWith(snapshots) && !path.equals(snapshots)
                || path.startsWith(coordinator) && !path.equals(coordinator) && !path.equals(bindings)
                    && !path.equals(coordinator.resolve("root.lock"))
                || (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                    && !path.startsWith(transactions) && !path.startsWith(snapshots) && !path.startsWith(coordinator));
        }
    }

    private static boolean hasUnregisteredEvidence(EvidenceSnapshot evidence) {
        Path root = evidence.root();
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path bindings = coordinator.resolve("bindings");
        Path lock = coordinator.resolve("root.lock");
        return evidence.paths().keySet().stream().anyMatch(path -> path.startsWith(snapshots) && !path.equals(snapshots)
            || path.startsWith(coordinator) && !path.equals(coordinator) && !path.equals(bindings) && !path.equals(lock)
            || (evidence.isRegularFile(path) || evidence.isSymbolicLink(path))
                && !path.startsWith(transactions) && !path.startsWith(snapshots) && !path.startsWith(coordinator));
    }

    private static CoordinatorState readState(Path stateFile, Gson gson) throws IOException {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return CoordinatorState.empty();
        }
        if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset coordinator state is not a safe regular file: " + stateFile);
        }
        return readState(Files.readAllBytes(stateFile), stateFile);
    }

    private static CoordinatorState readState(byte[] content, Path stateFile) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Asset coordinator state is not an object");
            }
            JsonObject object = parsed.getAsJsonObject();
            String storedHash = requiredString(object, "stateHash");
            JsonObject unhashed = object.deepCopy();
            unhashed.remove("stateHash");
            String calculatedHash = StorageSafety.sha256(AssetProjectMetadata.of(unhashed).canonicalJson());
            if (!storedHash.equals(calculatedHash)) {
                throw new IOException("Asset coordinator state hash does not match its content");
            }
            if (object.get("version").getAsInt() != STATE_VERSION || !STATE_FORMAT.equals(requiredString(object, "format"))) {
                throw new IOException("Unsupported asset coordinator state version");
            }
            return coordinatorState(object);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator state is corrupt: " + stateFile, failure);
        }
    }

    private static String stateIdentity(Path stateFile) throws IOException {
        return stateIdentity(Files.readAllBytes(stateFile), stateFile);
    }

    private static String stateIdentity(byte[] content, Path stateFile) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Asset coordinator state is not an object");
            }
            return AssetProjectMetadata.of(parsed.getAsJsonObject()).canonicalJson();
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset coordinator state identity is corrupt: " + stateFile, failure);
        }
    }

    private static CoordinatorState coordinatorState(JsonObject object) throws IOException {
        long rootSequence = object.get("rootSequence").getAsLong();
        ExpectedProject project = expectedProject(object.getAsJsonObject("project"));
        Map<AssetKey, ResourceEntry> resources = new LinkedHashMap<>();
        Set<String> resourcePaths = new LinkedHashSet<>();
        for (JsonElement element : object.getAsJsonArray("resources")) {
            JsonObject item = element.getAsJsonObject();
            AssetKey key = new AssetKey(requiredString(item, "type"), requiredString(item, "id"));
            ResourceEntry entry = new ResourceEntry(key, requiredString(item, "path"),
                expectedState(item.getAsJsonObject("state")), new AssetMutationId(requiredString(item, "mutationId")),
                item.has("deletionProvenance") ? deletionProvenance(item.getAsJsonObject("deletionProvenance")) : null);
            if (resources.putIfAbsent(key, entry) != null) {
                throw new IOException("Asset coordinator state contains a duplicate key: " + key.canonical());
            }
            if (!resourcePaths.add(entry.path)) {
                throw new IOException("Asset coordinator state contains a duplicate path: " + entry.path);
            }
        }
        Map<UUID, MutationRecord> mutations = new LinkedHashMap<>();
        for (JsonElement element : object.getAsJsonArray("mutations")) {
            JsonObject item = element.getAsJsonObject();
            UUID id = UUID.fromString(requiredString(item, "mutationId"));
            String intentHash = requiredHash(item, "intentHash");
            Instant committedAt = Instant.parse(requiredString(item, "committedAt"));
            TransactionResult result = transactionResult(item.getAsJsonObject("result"));
            if (!id.equals(result.mutationId()) || !intentHash.equals(result.intentHash())) {
                throw new IOException("Asset coordinator mutation record identity does not match its result: " + id);
            }
            if (mutations.putIfAbsent(id, new MutationRecord(intentHash, committedAt, result)) != null) {
                throw new IOException("Asset coordinator state contains a duplicate mutation ID: " + id);
            }
        }
        List<BlockedAdoption> blocked = new ArrayList<>();
        if (object.has("blocked")) {
            for (JsonElement element : object.getAsJsonArray("blocked")) {
                blocked.add(blockedAdoption(element.getAsJsonObject()));
            }
        }
        CoordinatorState state = new CoordinatorState(rootSequence, project, resources, mutations, blocked);
        state.validate();
        return state;
    }

    private static String serializeState(CoordinatorState state, Gson gson) {
        JsonObject object = new JsonObject();
        object.addProperty("version", STATE_VERSION);
        object.addProperty("format", STATE_FORMAT);
        object.addProperty("rootSequence", state.rootSequence);
        object.add("project", expectedProjectJson(state.project));
        JsonArray resources = new JsonArray();
        state.resources.values().stream().sorted(Comparator.comparing(entry -> entry.key.canonical())).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("type", entry.key.type());
            item.addProperty("id", entry.key.id());
            item.addProperty("path", entry.path);
            item.add("state", expectedStateJson(entry.state));
            item.addProperty("mutationId", entry.mutationId.value());
            if (entry.deletionProvenance != null) {
                item.add("deletionProvenance", deletionProvenanceJson(entry.deletionProvenance));
            }
            resources.add(item);
        });
        object.add("resources", resources);
        JsonArray mutations = new JsonArray();
        state.mutations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("mutationId", entry.getKey().toString());
            item.addProperty("intentHash", entry.getValue().intentHash);
            item.addProperty("committedAt", entry.getValue().committedAt.toString());
            item.add("result", transactionResultJson(entry.getValue().result));
            mutations.add(item);
        });
        object.add("mutations", mutations);
        JsonArray blocked = new JsonArray();
        state.blocked.stream().map(AssetTransactionCoordinator::blockedAdoptionJson).forEach(blocked::add);
        object.add("blocked", blocked);
        object.addProperty("stateHash", StorageSafety.sha256(AssetProjectMetadata.of(object).canonicalJson()));
        return gson.toJson(AssetProjectMetadata.of(object).document());
    }

    private static AssetProjectMetadata readMetadata(Path projectFile) throws IOException {
        if (!Files.exists(projectFile, LinkOption.NOFOLLOW_LINKS)) {
            return AssetProjectMetadata.empty();
        }
        if (Files.isSymbolicLink(projectFile) || !Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset project metadata is not a safe regular file: " + projectFile);
        }
        return readMetadata(Files.readAllBytes(projectFile), projectFile);
    }

    private static AssetProjectMetadata readMetadata(byte[] content, Path projectFile) throws IOException {
        if (content == null) {
            return AssetProjectMetadata.empty();
        }
        try {
            return AssetProjectMetadata.parse(new String(content, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Asset project metadata is corrupt: " + projectFile, failure);
        }
    }

    private static void validatePair(Path stateFile, Path projectFile, CoordinatorState state,
                                     AssetProjectMetadata metadata) throws IOException {
        boolean hasState = Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS);
        boolean hasProject = Files.exists(projectFile, LinkOption.NOFOLLOW_LINKS);
        validatePair(hasState, hasProject, state, metadata);
    }

    private static void validatePair(boolean hasState, boolean hasProject, CoordinatorState state,
                                     AssetProjectMetadata metadata) throws IOException {
        if (hasState && !hasProject) {
            throw new IOException("Asset coordinator state and project metadata are incomplete");
        }
        if (!state.project.hash().equals(metadata.hash())) {
            throw new IOException("Asset project metadata hash does not match coordinator state");
        }
    }

    private static void validateAssets(Path root, CoordinatorState state) throws IOException {
        validateAssets(root, state, (PhaseTimings) null);
    }

    private static void validateAssets(Path root, CoordinatorState state, PhaseTimings phaseTimings) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path transactions = normalizedRoot.resolve(".transactions");
        Path snapshots = normalizedRoot.resolve(".snapshots");
        Path coordinator = normalizedRoot.resolve(".asset-coordinator");
        Path migrations = normalizedRoot.resolve(".migrations");
        Path quarantine = normalizedRoot.resolve(".quarantine");
        Path project = normalizedRoot.resolve("project.json");
        Map<Path, ResourceEntry> indexed = new LinkedHashMap<>();
        for (ResourceEntry entry : state.resources.values()) {
            Path path = normalizedRoot.resolve(entry.path).normalize();
            validateManagedPath(normalizedRoot, transactions, snapshots, coordinator, project, entry.path, path);
            if (indexed.putIfAbsent(path, entry) != null) {
                throw new IOException("Asset coordinator state contains a duplicate physical path: " + entry.path);
            }
        }
        Map<Path, ResourceEntry> resources = Map.copyOf(indexed);
        Set<Path> seen = new LinkedHashSet<>();
        Set<Path> internalRoots = Set.of(transactions, snapshots, coordinator, migrations, quarantine);
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, normalizedRoot);
        for (Map.Entry<Path, ResourceEntry> indexedEntry : resources.entrySet()) {
            Path path = indexedEntry.getKey();
            if (!path.startsWith(quarantine)) {
                continue;
            }
            ResourceEntry entry = indexedEntry.getValue();
            Path existing = path;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || Files.isSymbolicLink(existing)) {
                throw new IOException("Asset coordinator state contains an unsafe path ancestor: " + entry.path);
            }
            MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, existing);
            if (entry.state instanceof Deleted) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Deleted asset exists on disk: " + entry.key.canonical());
                }
                continue;
            }
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!(entry.state instanceof Live live) || !before.isRegularFile() || before.isSymbolicLink()) {
                throw new IOException("Live asset is missing: " + entry.key.canonical());
            }
            String hash;
            long started = phaseTimings == null ? 0L : System.nanoTime();
            try {
                hash = StorageSafety.sha256(Files.readAllBytes(path));
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!EvidencePath.of(before).sameNamespaceIdentity(EvidencePath.of(after))) {
                    throw new IOException("Live asset changed during validation: " + entry.key.canonical());
                }
            } finally {
                if (phaseTimings != null) {
                    phaseTimings.trackedAssetHashAttemptCount++;
                    phaseTimings.trackedAssetHashNanos += elapsedNanos(started);
                }
            }
            if (!live.hash().equals(hash)) {
                throw new IOException("Live asset hash does not match coordinator state: " + entry.key.canonical());
            }
            seen.add(path);
        }
        long traversalStarted = phaseTimings == null ? 0L : System.nanoTime();
        long hashingBefore = phaseTimings == null ? 0L : phaseTimings.trackedAssetHashNanos;
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                    Path path = directory.toAbsolutePath().normalize();
                    if (!path.equals(normalizedRoot) && internalRoots.contains(path)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    validate(path, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    validate(file.toAbsolutePath().normalize(), attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    throw failure;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    return FileVisitResult.CONTINUE;
                }

                private void validate(Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        throw new IOException("Unregistered symbolic link exists in the asset root: "
                            + normalizedRoot.relativize(path));
                    }
                    ResourceEntry entry = resources.get(path);
                    if (entry == null) {
                        if (attributes.isRegularFile() && !path.equals(project)) {
                            throw new IOException("Unregistered physical asset exists: "
                                + normalizedRoot.relativize(path));
                        }
                        return;
                    }
                    seen.add(path);
                    if (entry.state instanceof Deleted) {
                        throw new IOException("Deleted asset exists on disk: " + entry.key.canonical());
                    }
                    if (!(entry.state instanceof Live live) || !attributes.isRegularFile()) {
                        throw new IOException("Live asset is missing: " + entry.key.canonical());
                    }
                    String hash;
                    long started = phaseTimings == null ? 0L : System.nanoTime();
                    try {
                        hash = StorageSafety.sha256(Files.readAllBytes(path));
                        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                        if (!EvidencePath.of(attributes).sameNamespaceIdentity(EvidencePath.of(after))) {
                            throw new IOException("Live asset changed during validation: " + entry.key.canonical());
                        }
                    } finally {
                        if (phaseTimings != null) {
                            phaseTimings.trackedAssetHashAttemptCount++;
                            phaseTimings.trackedAssetHashNanos += elapsedNanos(started);
                        }
                    }
                    if (!live.hash().equals(hash)) {
                        throw new IOException("Live asset hash does not match coordinator state: "
                            + entry.key.canonical());
                    }
                }
            });
            for (Map.Entry<Path, ResourceEntry> indexedEntry : resources.entrySet()) {
                if (indexedEntry.getValue().state instanceof Live && !seen.contains(indexedEntry.getKey())) {
                    throw new IOException("Live asset is missing: " + indexedEntry.getValue().key.canonical());
                }
            }
        } finally {
            if (phaseTimings != null) {
                phaseTimings.externalTraversalAttemptCount++;
                long hashing = phaseTimings.trackedAssetHashNanos - hashingBefore;
                phaseTimings.externalTraversalNanos += Math.max(0L, elapsedNanos(traversalStarted) - hashing);
            }
        }
    }

    private static void validateAssets(Path root, CoordinatorState state, EvidenceSnapshot evidence) throws IOException {
        Path transactions = root.resolve(".transactions");
        Path snapshots = root.resolve(".snapshots");
        Path coordinator = root.resolve(".asset-coordinator");
        Path project = root.resolve("project.json");
        Set<Path> paths = new LinkedHashSet<>();
        for (ResourceEntry entry : state.resources.values()) {
            Path path = root.resolve(entry.path).normalize();
            validateManagedPath(root, transactions, snapshots, coordinator, project, entry.path, path);
            if (!paths.add(path)) {
                throw new IOException("Asset coordinator state contains a duplicate physical path: " + entry.path);
            }
            Path existing = path;
            while (existing != null && !evidence.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || evidence.isSymbolicLink(existing)) {
                throw new IOException("Asset coordinator state contains an unsafe path ancestor: " + entry.path);
            }
            evidence.requireNoSymlinkTraversal(existing);
            if (entry.state instanceof Live live) {
                if (evidence.isSymbolicLink(path) || !evidence.isRegularFile(path)) {
                    throw new IOException("Live asset is missing: " + entry.key.canonical());
                }
                if (!live.hash().equals(evidence.hash(path))) {
                    throw new IOException("Live asset hash does not match coordinator state: " + entry.key.canonical());
                }
            } else if (entry.state instanceof Deleted && evidence.exists(path)) {
                throw new IOException("Deleted asset exists on disk: " + entry.key.canonical());
            }
        }
        for (Path path : evidence.externalPaths()) {
            if (evidence.isSymbolicLink(path)) {
                throw new IOException("Unregistered symbolic link exists in the asset root: " + root.relativize(path));
            }
            if (evidence.isRegularFile(path) && !path.equals(project) && !paths.contains(path.normalize())) {
                throw new IOException("Unregistered physical asset exists: " + root.relativize(path));
            }
        }
    }

    static List<Path> externalAssetPaths(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Set<Path> internalRoots = Set.of(
            normalizedRoot.resolve(".transactions"),
            normalizedRoot.resolve(".snapshots"),
            normalizedRoot.resolve(".asset-coordinator"),
            normalizedRoot.resolve(".migrations"),
            normalizedRoot.resolve(".quarantine")
        );
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path normalized = directory.toAbsolutePath().normalize();
                if (!normalized.equals(normalizedRoot) && internalRoots.contains(normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                paths.add(normalized);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                paths.add(file.toAbsolutePath().normalize());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(paths);
    }

    private static JsonObject expectedProjectJson(ExpectedProject project) {
        JsonObject object = new JsonObject();
        object.addProperty("revision", project.revision());
        object.addProperty("hash", project.hash());
        return object;
    }

    private static ExpectedProject expectedProject(JsonObject object) {
        return new ExpectedProject(object.get("revision").getAsLong(), requiredHash(object, "hash"));
    }

    private static JsonObject expectedStateJson(ExpectedState state) {
        JsonObject object = new JsonObject();
        object.addProperty("kind", state.kind());
        object.addProperty("revision", state.revision());
        object.addProperty("hash", state.hash());
        return object;
    }

    private static ExpectedState expectedState(JsonObject object) {
        String kind = requiredString(object, "kind");
        long revision = object.get("revision").getAsLong();
        String hash = requiredString(object, "hash");
        return switch (kind) {
            case "MISSING" -> Missing.INSTANCE;
            case "LIVE" -> new Live(revision, hash);
            case "DELETED" -> new Deleted(revision, hash);
            default -> throw new IllegalArgumentException("Unknown asset state: " + kind);
        };
    }

    private static JsonObject deletionProvenanceJson(LegacyDeletionProvenance provenance) {
        JsonObject object = new JsonObject();
        if (provenance.priorPayloadHash() != null) {
            object.addProperty("priorPayloadHash", provenance.priorPayloadHash());
        }
        if (provenance.originalLogicalPath() != null) {
            object.addProperty("originalLogicalPath", provenance.originalLogicalPath().toString().replace('\\', '/'));
        }
        if (provenance.canonicalFuturePath() != null) {
            object.addProperty("canonicalFuturePath", provenance.canonicalFuturePath().toString().replace('\\', '/'));
        }
        object.addProperty("tombstoneEvidenceHash", provenance.tombstoneEvidenceHash());
        object.addProperty("owner", provenance.owner());
        object.addProperty("evidenceKey", provenance.evidenceKey());
        object.addProperty("revision", provenance.revision());
        object.addProperty("mutationId", provenance.mutationId().value());
        return object;
    }

    private static LegacyDeletionProvenance deletionProvenance(JsonObject object) {
        return new LegacyDeletionProvenance(object.has("priorPayloadHash")
            ? requiredHash(object, "priorPayloadHash") : null,
            object.has("originalLogicalPath") ? Path.of(requiredString(object, "originalLogicalPath")) : null,
            object.has("canonicalFuturePath") ? Path.of(requiredString(object, "canonicalFuturePath")) : null,
            requiredHash(object, "tombstoneEvidenceHash"), requiredString(object, "owner"),
            requiredString(object, "evidenceKey"), object.get("revision").getAsLong(),
            new AssetMutationId(requiredString(object, "mutationId")));
    }

    private static JsonObject blockedAdoptionJson(BlockedAdoption blocked) {
        JsonObject object = new JsonObject();
        object.addProperty("owner", blocked.owner());
        object.addProperty("evidenceKey", blocked.evidenceKey());
        object.addProperty("evidenceHash", blocked.evidenceHash());
        object.addProperty("revision", blocked.revision());
        object.addProperty("mutationId", blocked.mutationId().value());
        if (blocked.candidateKey() != null) {
            object.addProperty("candidateType", blocked.candidateKey().type());
            object.addProperty("candidateId", blocked.candidateKey().id());
        }
        if (blocked.originalLogicalPath() != null) {
            object.addProperty("originalLogicalPath", blocked.originalLogicalPath().toString().replace('\\', '/'));
        }
        if (blocked.canonicalFuturePath() != null) {
            object.addProperty("canonicalFuturePath", blocked.canonicalFuturePath().toString().replace('\\', '/'));
        }
        object.addProperty("reason", blocked.reason());
        return object;
    }

    private static BlockedAdoption blockedAdoption(JsonObject object) {
        return new BlockedAdoption(requiredString(object, "owner"), requiredString(object, "evidenceKey"),
            requiredHash(object, "evidenceHash"), object.get("revision").getAsLong(),
            new AssetMutationId(requiredString(object, "mutationId")),
            object.has("candidateType") || object.has("candidateId")
                ? new AssetKey(requiredString(object, "candidateType"), requiredString(object, "candidateId")) : null,
            object.has("originalLogicalPath") ? Path.of(requiredString(object, "originalLogicalPath")) : null,
            object.has("canonicalFuturePath") ? Path.of(requiredString(object, "canonicalFuturePath")) : null,
            requiredString(object, "reason"));
    }

    private static JsonObject transactionResultJson(TransactionResult result) {
        JsonObject object = new JsonObject();
        object.addProperty("rootSequence", result.rootSequence());
        object.addProperty("mutationId", result.mutationId().toString());
        object.addProperty("intentHash", result.intentHash());
        object.add("project", expectedProjectJson(result.project()));
        JsonArray states = new JsonArray();
        result.states().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(AssetKey::canonical))).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("type", entry.getKey().type());
            item.addProperty("id", entry.getKey().id());
            item.add("state", expectedStateJson(entry.getValue()));
            states.add(item);
        });
        object.add("states", states);
        return object;
    }

    private static TransactionResult transactionResult(JsonObject object) {
        long sequence = object.get("rootSequence").getAsLong();
        UUID mutationId = UUID.fromString(requiredString(object, "mutationId"));
        String intentHash = requiredHash(object, "intentHash");
        ExpectedProject project = expectedProject(object.getAsJsonObject("project"));
        Map<AssetKey, ExpectedState> states = new LinkedHashMap<>();
        for (JsonElement element : object.getAsJsonArray("states")) {
            JsonObject item = element.getAsJsonObject();
            AssetKey key = new AssetKey(requiredString(item, "type"), requiredString(item, "id"));
            if (states.putIfAbsent(key, expectedState(item.getAsJsonObject("state"))) != null) {
                throw new IllegalArgumentException("Duplicate asset result state: " + key.canonical());
            }
        }
        return new TransactionResult(sequence, mutationId, intentHash, states, project, false);
    }

    private static String requiredString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return object.get(field).getAsString();
    }

    private static String requiredHash(JsonObject object, String field) {
        String hash = requiredString(object, field);
        if (!SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 field: " + field);
        }
        return hash;
    }

    private record CoordinatorState(long rootSequence, ExpectedProject project,
                                    Map<AssetKey, ResourceEntry> resources,
                                    Map<UUID, MutationRecord> mutations,
                                    List<BlockedAdoption> blocked) {
        private CoordinatorState {
            resources = Map.copyOf(resources);
            mutations = Map.copyOf(mutations);
            blocked = List.copyOf(blocked);
        }

        private static CoordinatorState empty() {
            return initial(AssetProjectMetadata.empty());
        }

        private static CoordinatorState initial(AssetProjectMetadata metadata) {
            return new CoordinatorState(0L, new ExpectedProject(0L, metadata.hash()), Map.of(), Map.of(), List.of());
        }

        private Snapshot snapshot(AssetProjectMetadata metadata, Path root) {
            Map<AssetKey, ExpectedState> states = new LinkedHashMap<>();
            resources.forEach((key, value) -> states.put(key, value.state));
            Map<AssetKey, Path> paths = new LinkedHashMap<>();
            resources.forEach((key, value) -> paths.put(key, root.resolve(value.path).normalize()));
            Map<AssetKey, AssetMutationId> lineages = new LinkedHashMap<>();
            resources.forEach((key, value) -> lineages.put(key, value.mutationId));
            return new Snapshot(rootSequence, states, paths, lineages, project, projectLineage(), metadata, blocked);
        }

        private AssetMutationId projectLineage() {
            if (project.revision() == 0L) {
                return null;
            }
            return mutations.entrySet().stream()
                .filter(entry -> entry.getValue().result.project().equals(project))
                .min(Comparator.comparingLong(entry -> entry.getValue().result.rootSequence()))
                .map(entry -> AssetMutationId.of(entry.getKey()))
                .orElseThrow(() -> new IllegalStateException("Asset project revision has no durable mutation lineage"));
        }

        private void validate() throws IOException {
            if (rootSequence < 0L || project.revision() > rootSequence) {
                throw new IOException("Asset coordinator sequence is invalid");
            }
            Set<Long> sequences = new LinkedHashSet<>();
            for (MutationRecord mutation : mutations.values()) {
                if (mutation.result.rootSequence() < 1L || mutation.result.rootSequence() > rootSequence) {
                    throw new IOException("Asset coordinator mutation sequence is invalid");
                }
                if (!sequences.add(mutation.result.rootSequence())) {
                    throw new IOException("Asset coordinator mutation sequence is duplicated");
                }
            }
            if (sequences.size() != rootSequence) {
                throw new IOException("Asset coordinator mutation sequence has a gap");
            }
            Set<String> blockedEvidence = new LinkedHashSet<>();
            for (BlockedAdoption evidence : blocked) {
                if (!blockedEvidence.add(evidence.owner() + "\n" + evidence.evidenceKey())) {
                    throw new IOException("Asset coordinator blocked adoption evidence is duplicated");
                }
            }
        }
    }

    private record ResourceEntry(AssetKey key, String path, ExpectedState state, AssetMutationId mutationId,
                                 LegacyDeletionProvenance deletionProvenance) {
        private ResourceEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(mutationId, "mutationId");
            if (state instanceof Missing) {
                throw new IllegalArgumentException("Missing assets are not persisted in coordinator state");
            }
            if (deletionProvenance != null && (!(state instanceof Deleted deleted)
                || !deletionProvenance.mutationId().equals(mutationId)
                || deletionProvenance.revision() != deleted.revision()
                || !deletionProvenance.tombstoneEvidenceHash().equals(deleted.hash())
                || deletionProvenance.canonicalFuturePath() == null
                || !deletionProvenance.canonicalFuturePath().normalize().equals(Path.of(path).normalize()))) {
                throw new IllegalArgumentException("Asset deletion provenance does not match its durable state");
            }
        }

        private ResourceEntry(AssetKey key, String path, ExpectedState state, AssetMutationId mutationId) {
            this(key, path, state, mutationId, null);
        }
    }

    private record MutationRecord(String intentHash, Instant committedAt, TransactionResult result) {
    }

    private record NormalizedAssetDelta(AssetKey key, Path path, Path previousPath, ExpectedState expected,
                                        byte[] content, boolean deleted, AssetKey lineageSource,
                                        ExpectedState lineageState, boolean transformedRelocation) {
    }

    private record ValidatedJournals(Set<UUID> coordinatorJournals, Map<String, String> preparedFingerprints,
                                     Set<Path> preparedAssetPaths, int managerTransactions,
                                     long preparedSequenceBefore, PreparedTransition preparedTransition,
                                     Map<Long, PreparedTransition> transitions, Genesis genesis,
                                     Map<String, Map<String, PreparedFileState>> preparedFileStates,
                                     CheckpointTip checkpointTip) {
        private boolean initialPrepared() {
            return managerTransactions == 1 && coordinatorJournals.size() == 1 && preparedFingerprints.size() == 1
                && preparedSequenceBefore == 0L;
        }
    }

    private record HistoryValidation(ValidatedJournals journals) {
    }

    private record CheckpointTip(long sequence, CoordinatorState state, String stateIdentity) {
    }

    private record HistoryCheckpoint(long rootSequence, String stateHash, String genesisHash,
                                     Map<String, AssetTransactionManager.CommittedIndexEntry> committedIndex,
                                     int evidenceCount, String evidenceHash,
                                     Map<String, CheckpointEvidence> legacyEvidence) {
        private HistoryCheckpoint {
            committedIndex = Map.copyOf(committedIndex);
            legacyEvidence = Map.copyOf(legacyEvidence);
        }
    }

    private record CheckpointOpen(CoordinatorState state, AssetProjectMetadata metadata,
                                  HistoryValidation historyValidation, EvidenceWatcher evidenceWatcher,
                                  HistoryCheckpoint checkpoint) {
    }

    private static final class PhaseTimings {
        private long transactionAttemptCount;
        private long transactionNanos;
        private long recoveryAttemptCount;
        private long recoveryNanos;
        private long normalizationAttemptCount;
        private long normalizationNanos;
        private long metadataValidationAttemptCount;
        private long metadataValidationNanos;
        private long trackedAssetHashAttemptCount;
        private long trackedAssetHashNanos;
        private long externalTraversalAttemptCount;
        private long externalTraversalNanos;
        private long stateWorkAttemptCount;
        private long stateWorkNanos;
        private long acceptedTipAttemptCount;
        private long acceptedTipNanos;
        private long residualNanos;
        private boolean stateWorkActive;
        private long stateWorkStarted;
        private long stateWorkHashingBefore;
        private boolean acceptedTipActive;
        private long acceptedTipStarted;

        private long accountedNanos() {
            return recoveryNanos + normalizationNanos + metadataValidationNanos + trackedAssetHashNanos
                + externalTraversalNanos + stateWorkNanos + acceptedTipNanos;
        }

        private long recoveryNestedNanos() {
            return trackedAssetHashNanos + externalTraversalNanos;
        }

        private void beginStateWork() {
            stateWorkAttemptCount++;
            stateWorkActive = true;
            stateWorkStarted = System.nanoTime();
            stateWorkHashingBefore = trackedAssetHashNanos;
        }

        private void finishStateWork() {
            if (!stateWorkActive) {
                return;
            }
            long nestedHashing = trackedAssetHashNanos - stateWorkHashingBefore;
            stateWorkNanos += Math.max(0L, elapsedNanos(stateWorkStarted) - nestedHashing);
            stateWorkActive = false;
        }

        private void beginAcceptedTip() {
            acceptedTipAttemptCount++;
            acceptedTipActive = true;
            acceptedTipStarted = System.nanoTime();
        }

        private void finishAcceptedTip() {
            if (!acceptedTipActive) {
                return;
            }
            acceptedTipNanos += elapsedNanos(acceptedTipStarted);
            acceptedTipActive = false;
        }

        private void finishOpenAttempts() {
            finishStateWork();
            finishAcceptedTip();
        }

        private PhaseTiming snapshot() {
            return new PhaseTiming(transactionAttemptCount, transactionNanos, recoveryAttemptCount, recoveryNanos,
                normalizationAttemptCount, normalizationNanos, metadataValidationAttemptCount, metadataValidationNanos,
                trackedAssetHashAttemptCount, trackedAssetHashNanos, externalTraversalAttemptCount, externalTraversalNanos,
                stateWorkAttemptCount, stateWorkNanos, acceptedTipAttemptCount, acceptedTipNanos, residualNanos);
        }
    }

    interface EvidenceReadObserver {
        void walkStarted(Path root);

        void contentRead(Path path);

        default void beforeFinalFence(Path root) throws IOException {
        }

        default void beforePreparedRecovery(Path root) throws IOException {
        }

        default long retainedFileLimit() {
            return RETAINED_EVIDENCE_FILE_LIMIT;
        }

        default long retainedTotalLimit() {
            return RETAINED_EVIDENCE_TOTAL_LIMIT;
        }

        default int maximumDepth() {
            return EVIDENCE_MAX_DEPTH;
        }

        default int maximumPaths() {
            return EVIDENCE_MAX_PATHS;
        }

        default long hashedFileLimit() {
            return HASHED_EVIDENCE_FILE_LIMIT;
        }

        default long hashedTotalLimit() {
            return HASHED_EVIDENCE_TOTAL_LIMIT;
        }
    }

    private record CapturedContent(String hash, long size, EvidencePath path, byte[] retainedBytes) {
        private CapturedContent {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(path, "path");
        }

        private byte[] bytes() {
            if (retainedBytes == null) {
                throw new IllegalStateException("Asset evidence bytes were not retained");
            }
            return retainedBytes.clone();
        }
    }

    private static final class EvidenceRetentionBudget {
        private final long fileLimit;
        private final long totalLimit;
        private long retained;

        private EvidenceRetentionBudget(long fileLimit, long totalLimit) {
            if (fileLimit < 0L || totalLimit < 0L) {
                throw new IllegalArgumentException("Invalid retained asset evidence budget");
            }
            this.fileLimit = fileLimit;
            this.totalLimit = totalLimit;
        }

        private void retain(long bytes) throws IOException {
            if (bytes < 0L || bytes > fileLimit || retained > totalLimit - bytes) {
                throw new IOException("Retained asset evidence exceeds its bounded startup budget");
            }
            retained += bytes;
        }
    }

    private record EvidenceLimits(int maximumDepth, int maximumPaths, long hashedFileLimit, long hashedTotalLimit) {
        private EvidenceLimits {
            if (maximumDepth < 1 || maximumPaths < 1 || hashedFileLimit < 0L || hashedTotalLimit < 0L) {
                throw new IllegalArgumentException("Invalid asset evidence limits");
            }
        }

        private static EvidenceLimits from(EvidenceReadObserver observer) {
            return observer == null
                ? new EvidenceLimits(EVIDENCE_MAX_DEPTH, EVIDENCE_MAX_PATHS, HASHED_EVIDENCE_FILE_LIMIT,
                    HASHED_EVIDENCE_TOTAL_LIMIT)
                : new EvidenceLimits(observer.maximumDepth(), observer.maximumPaths(), observer.hashedFileLimit(),
                    observer.hashedTotalLimit());
        }
    }

    private static final class EvidencePathBudget {
        private final int limit;
        private int visited;

        private EvidencePathBudget(int limit) {
            this.limit = limit;
        }

        private void visit(Path root, Path path) throws IOException {
            visited++;
            if (visited > limit) {
                throw new IOException("Asset evidence exceeded maximum paths: " + root.relativize(path));
            }
        }
    }

    private static final class EvidenceHashBudget {
        private final long fileLimit;
        private final long totalLimit;
        private long hashed;

        private EvidenceHashBudget(long fileLimit, long totalLimit) {
            this.fileLimit = fileLimit;
            this.totalLimit = totalLimit;
        }

        private synchronized void reserve(long bytes) throws IOException {
            if (bytes < 0L || bytes > fileLimit || hashed > totalLimit - bytes) {
                throw new IOException("Hashed asset evidence exceeds its bounded startup budget");
            }
            hashed += bytes;
        }
    }

    private record CheckpointEvidenceEntry(String path, CheckpointEvidence evidence) {
    }

    private record EvidenceSnapshot(Path root, Map<Path, EvidencePath> paths, Map<Path, CapturedContent> contents,
                                    List<AssetTransactionManager.TransactionInspection> inspections,
                                    HistoryEvidence history, Genesis genesis, long externalWalks,
                                    EvidenceReadObserver observer, EvidenceLimits limits,
                                    EvidenceHashBudget hashBudget) {
        private EvidenceSnapshot {
            root = root.toAbsolutePath().normalize();
            paths = Map.copyOf(paths);
            Map<Path, CapturedContent> contentCopy = new LinkedHashMap<>();
            contents.forEach((path, content) -> contentCopy.put(path.toAbsolutePath().normalize(), content));
            contents = Map.copyOf(contentCopy);
            inspections = List.copyOf(inspections);
            Objects.requireNonNull(history, "history");
            Objects.requireNonNull(genesis, "genesis");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(hashBudget, "hashBudget");
            if (externalWalks != 1L) {
                throw new IllegalArgumentException("Evidence snapshot must contain exactly one classified walk");
            }
        }

        private Path normalize(Path path) {
            return path.toAbsolutePath().normalize();
        }

        private boolean exists(Path path) {
            return paths.containsKey(normalize(path));
        }

        private EvidencePath path(Path path) throws IOException {
            Path normalized = normalize(path);
            EvidencePath evidence = paths.get(normalized);
            if (evidence == null) {
                throw new IOException("Asset evidence path was not captured: " + root.relativize(normalized));
            }
            return evidence;
        }

        private boolean isRegularFile(Path path) {
            EvidencePath evidence = paths.get(normalize(path));
            return evidence != null && evidence.regularFile();
        }

        private boolean isDirectory(Path path) {
            EvidencePath evidence = paths.get(normalize(path));
            return evidence != null && evidence.directory();
        }

        private boolean isSymbolicLink(Path path) {
            EvidencePath evidence = paths.get(normalize(path));
            return evidence != null && evidence.symbolicLink();
        }

        private byte[] content(Path path) throws IOException {
            CapturedContent content = contents.get(normalize(path));
            if (content == null) {
                throw new IOException("Asset evidence content was not captured: " + root.relativize(normalize(path)));
            }
            return content.bytes();
        }

        private byte[] contentOrNull(Path path) {
            CapturedContent content = contents.get(normalize(path));
            return content == null ? null : content.bytes();
        }

        private byte[] retainedBytesView(Path path) throws IOException {
            CapturedContent content = captured(path);
            if (content.retainedBytes() == null) {
                throw new IOException("Asset evidence bytes were not retained: " + root.relativize(normalize(path)));
            }
            return content.retainedBytes();
        }

        private String hash(Path path) throws IOException {
            CapturedContent content = contents.get(normalize(path));
            if (content == null) {
                throw new IOException("Asset evidence content was not captured: " + root.relativize(normalize(path)));
            }
            return content.hash();
        }

        private CapturedContent captured(Path path) throws IOException {
            CapturedContent content = contents.get(normalize(path));
            if (content == null) {
                throw new IOException("Asset evidence content was not captured: " + root.relativize(normalize(path)));
            }
            return content;
        }

        private void revalidate(Path path) throws IOException {
            Path normalized = normalize(path);
            EvidencePath expectedPath = paths.get(normalized);
            if (expectedPath == null) {
                if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Asset recovery path changed from missing state: " + root.relativize(normalized));
                }
                return;
            }
            if (!expectedPath.regularFile() || expectedPath.symbolicLink()) {
                throw new IOException("Asset recovery path is not a safe regular file: " + root.relativize(normalized));
            }
            CapturedContent expected = captured(normalized);
            BasicFileAttributes before = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!expectedPath.equals(EvidencePath.of(before))) {
                throw new IOException("Asset recovery path identity changed: " + root.relativize(normalized));
            }
            byte[] bytes = expected.retainedBytes() == null ? null : readRetainedBytes(normalized, before.size());
            String hash = bytes == null ? streamHash(normalized, before.size(), hashBudget) : StorageSafety.sha256(bytes);
            if (observer != null) {
                observer.contentRead(normalized);
            }
            BasicFileAttributes after = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            EvidencePath afterPath = EvidencePath.of(after);
            if (!expectedPath.equals(afterPath) || before.size() != expected.size()
                || (bytes != null && bytes.length != expected.size()) || !expected.hash().equals(hash)) {
                throw new IOException("Asset recovery path content changed: " + root.relativize(normalized));
            }
        }

        private List<Path> directChildren(Path directory) {
            Path normalized = normalize(directory);
            return paths.keySet().stream().filter(path -> normalized.equals(path.getParent())).sorted().toList();
        }

        private List<Path> descendants(Path directory) {
            Path normalized = normalize(directory);
            return paths.keySet().stream().filter(path -> path.startsWith(normalized) && !path.equals(normalized))
                .sorted().toList();
        }

        private List<Path> externalPaths() {
            Set<Path> internalRoots = Set.of(root.resolve(".transactions"), root.resolve(".snapshots"),
                root.resolve(".asset-coordinator"), root.resolve(".migrations"), root.resolve(".quarantine"));
            return paths.keySet().stream().filter(path -> internalRoots.stream().noneMatch(path::startsWith))
                .sorted().toList();
        }

        private void requireNoSymlinkTraversal(Path path) throws IOException {
            Path normalized = normalize(path);
            if (!normalized.startsWith(root)) {
                throw new IOException("Asset evidence path escapes its root: " + normalized);
            }
            Path current = normalized;
            while (current != null && current.startsWith(root)) {
                if (isSymbolicLink(current)) {
                    throw new IOException("Asset evidence path traverses a symbolic link: " + root.relativize(current));
                }
                if (current.equals(root)) {
                    return;
                }
                current = current.getParent();
            }
            throw new IOException("Asset evidence path does not reach its root: " + normalized);
        }
    }

    private record EvidencePath(String type, boolean directory, boolean regularFile, boolean symbolicLink, long size,
                                FileTime modifiedAt, FileTime createdAt, String fileKey) {
        private static EvidencePath of(BasicFileAttributes attributes) {
            String type = attributes.isSymbolicLink() ? "SYMLINK"
                : attributes.isDirectory() ? "DIRECTORY" : attributes.isRegularFile() ? "FILE" : "OTHER";
            return new EvidencePath(type, attributes.isDirectory(), attributes.isRegularFile(),
                attributes.isSymbolicLink(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(),
                Objects.toString(attributes.fileKey(), ""));
        }

        private boolean sameNamespaceIdentity(EvidencePath other) {
            if (other == null || !type.equals(other.type) || directory != other.directory
                || regularFile != other.regularFile || symbolicLink != other.symbolicLink
                || !fileKey.equals(other.fileKey)) {
                return false;
            }
            return directory || size == other.size && modifiedAt.equals(other.modifiedAt)
                && createdAt.equals(other.createdAt);
        }

        private boolean matches(AssetTransactionManager.ContentIdentity identity) {
            return regularFile && size == identity.size() && modifiedAt.equals(identity.modifiedAt())
                && createdAt.equals(identity.createdAt()) && fileKey.equals(identity.fileKey());
        }
    }

    private record HistoryEvidence(List<HistoryEvidenceEntry> entries) {
        private HistoryEvidence {
            entries = List.copyOf(entries);
        }
    }

    private record HistoryEvidenceEntry(String path, String type, long size, FileTime modifiedAt, FileTime createdAt,
                                        String fileKey) {
    }

    private record EvidenceIdentity(boolean directory, boolean regularFile, long size, String hash,
                                    FileTime modifiedAt, FileTime createdAt, String fileKey) {
        private boolean sameMetadata(EvidenceIdentity other) {
            return other != null && directory == other.directory && regularFile == other.regularFile
                && size == other.size && modifiedAt.equals(other.modifiedAt) && createdAt.equals(other.createdAt)
                && fileKey.equals(other.fileKey);
        }
    }

    private record CheckpointEvidence(boolean directory, boolean regularFile, long size, String hash,
                                      String modifiedAt, String createdAt, String fileKey) {
        private CheckpointEvidence {
            hash = hash == null ? "" : hash;
            modifiedAt = modifiedAt == null ? "" : modifiedAt;
            createdAt = createdAt == null ? "" : createdAt;
            fileKey = fileKey == null ? "" : fileKey;
        }

        private static CheckpointEvidence from(EvidenceIdentity identity) {
            return new CheckpointEvidence(identity.directory, identity.regularFile,
                identity.regularFile ? identity.size : 0L, identity.regularFile ? identity.hash : "",
                identity.regularFile ? identity.modifiedAt.toString() : "",
                identity.regularFile ? identity.createdAt.toString() : "", identity.fileKey);
        }

        private boolean matches(EvidencePath evidence) {
            if (evidence == null || directory != evidence.directory || regularFile != evidence.regularFile
                || !fileKey.equals(evidence.fileKey)) {
                return false;
            }
            return directory || size == evidence.size && modifiedAt.equals(evidence.modifiedAt.toString())
                && createdAt.equals(evidence.createdAt.toString());
        }

        private EvidenceIdentity identity(EvidencePath evidence) throws IOException {
            if (!matches(evidence)) {
                throw new IOException("Asset history checkpoint metadata changed");
            }
            return new EvidenceIdentity(evidence.directory, evidence.regularFile, evidence.size,
                evidence.regularFile ? hash : "", evidence.modifiedAt, evidence.createdAt, evidence.fileKey);
        }
    }

    record ValidationMetrics(long fullValidationPasses, long incrementalValidationPasses, long historyEvidenceScans,
                             long managerFullJournalPasses, long managerJournalReads,
                             long managerStagedPayloadReads, long managerIndexedMutationLookups,
                             long managerAssetPathCanonicalizations,
                             long watcherFullRegistrationPasses, long watcherIncrementalRegistrationPasses,
                             long watcherIncrementalDirectoryVisits, long watcherRegisteredDirectories,
                             long watcherAcceptedEvidencePaths, long watcherInvalidations, PhaseTiming phaseTiming,
                             AssetTransactionManager.PhaseTiming managerPhaseTiming, long watcherFullDirectoryWalks) {
    }

    record PhaseTiming(long transactionAttemptCount, long transactionNanos,
                       long recoveryAttemptCount, long recoveryNanos,
                       long normalizationAttemptCount, long normalizationNanos,
                       long metadataValidationAttemptCount, long metadataValidationNanos,
                       long trackedAssetHashAttemptCount, long trackedAssetHashNanos,
                       long externalTraversalAttemptCount, long externalTraversalNanos,
                       long stateWorkAttemptCount, long stateWorkNanos,
                       long acceptedTipAttemptCount, long acceptedTipNanos, long residualNanos) {
    }

    private record PreparedTransition(UUID mutationId, ExpectedProject expectedProject, CoordinatorState staged,
                                      Map<AssetKey, PreparedAsset> assets, String stateIdentity, String stateHash) {
    }

    private record PreparedAsset(String path, String previousPath, ExpectedState expected, ExpectedState result) {
    }

    private record PreparedPhysicalValidation(String transactionId, String bindingResource, byte[] bindingBytes,
                                              JsonObject binding, JsonObject intent, long sequenceBefore,
                                              PreparedTransition transition) {
        private PreparedPhysicalValidation {
            bindingBytes = bindingBytes.clone();
            binding = binding.deepCopy();
            intent = intent.deepCopy();
        }

        @Override
        public byte[] bindingBytes() {
            return bindingBytes.clone();
        }

        @Override
        public JsonObject binding() {
            return binding.deepCopy();
        }

        @Override
        public JsonObject intent() {
            return intent.deepCopy();
        }
    }

    private record PreparedFileState(boolean existed, String preHash, String postHash) {
        private PreparedFileState {
            Objects.requireNonNull(preHash, "preHash");
            Objects.requireNonNull(postHash, "postHash");
            if (existed && preHash.isEmpty() || !existed && !preHash.isEmpty()) {
                throw new IllegalArgumentException("Prepared file pre-state is inconsistent");
            }
        }
    }

    private record AdoptionDocument(CoordinatorState state, String inventoryHash) {
    }

    private record Genesis(CoordinatorState state, String stateIdentity, String inventoryHash, String provenance,
                           String artifactHash, String manifestHash) {
    }

    private record NormalizedRequest(String intentHash, JsonObject intent, List<NormalizedAssetDelta> assets,
                                     List<ProjectDelta> projectDeltas) {
        private NormalizedRequest {
            intent = intent.deepCopy();
        }

        @Override
        public JsonObject intent() {
            return intent.deepCopy();
        }
    }

    public record CommittedAsset(ExpectedState state, Path path, AssetMutationId mutationId) {
        public CommittedAsset {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(mutationId, "mutationId");
        }
    }

    public record AssetKey(String type, String id) {
        public AssetKey {
            type = requireIdentity(type, "type");
            id = requireIdentity(id, "id");
        }

        public String canonical() {
            return type.length() + ":" + type + id.length() + ":" + id;
        }

        private static String requireIdentity(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Asset " + field + " is invalid");
            }
            return value;
        }
    }

    public record AdoptionInventory(String provenance, String projectJson, List<AdoptedAsset> assets,
                                    List<BlockedAdoption> blocked, List<AdoptionEvidence> evidence) {
        public AdoptionInventory {
            provenance = AssetKey.requireIdentity(provenance, "adoption provenance");
            projectJson = Objects.requireNonNull(projectJson, "projectJson");
            if (projectJson.isBlank()) {
                throw new IllegalArgumentException("Asset adoption project JSON is required");
            }
            assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
            blocked = List.copyOf(Objects.requireNonNull(blocked, "blocked"));
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            Set<String> evidencePaths = new LinkedHashSet<>();
            for (AdoptionEvidence item : evidence) {
                if (!evidencePaths.add(item.originalPath().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Asset adoption evidence contains a duplicate or case-aliased path: "
                        + item.originalPath());
                }
            }
            Map<String, AdoptionEvidence> evidenceByPath = new LinkedHashMap<>();
            evidence.forEach(item -> evidenceByPath.put(item.originalPath(), item));
            for (AdoptedAsset asset : assets) {
                String path = asset.path().toString().replace('\\', '/');
                if (!path.startsWith(".tombstones/")) {
                    continue;
                }
                AdoptionEvidence item = evidenceByPath.get(path);
                if (item == null || item.size() != asset.content().length
                    || !item.hash().equals(StorageSafety.sha256(asset.content()))) {
                    throw new IllegalArgumentException("Asset adoption tombstone is not bound to exact external evidence: " + path);
                }
            }
        }

        public AdoptionInventory(String provenance, String projectJson, List<AdoptedAsset> assets,
                                 List<BlockedAdoption> blocked) {
            this(provenance, projectJson, assets, blocked, List.of());
        }

        public AdoptionInventory(String provenance, String projectJson, List<AdoptedAsset> assets) {
            this(provenance, projectJson, assets, List.of(), List.of());
        }
    }

    public record AdoptionEvidence(String originalPath, String hash, long size) {
        public AdoptionEvidence {
            originalPath = MigrationPaths.requireRelative(originalPath);
            if (originalPath.startsWith("assets/") || originalPath.equals("project.json")
                || originalPath.startsWith(".asset-coordinator/")
                || originalPath.startsWith(".transactions/") || originalPath.startsWith(".snapshots/")) {
                throw new IllegalArgumentException("Asset adoption evidence path is reserved: " + originalPath);
            }
            requireSha256(hash, "Asset adoption evidence hash");
            if (size < 0L) {
                throw new IllegalArgumentException("Asset adoption evidence size cannot be negative");
            }
        }
    }

    public record AdoptionBinding(String artifactHash, String manifestHash) {
        public AdoptionBinding {
            requireSha256(artifactHash, "Asset adoption artifact hash");
            requireSha256(manifestHash, "Asset adoption manifest hash");
        }

        private static AdoptionBinding derived(AdoptionInventory inventory) {
            JsonObject document = new JsonObject();
            document.addProperty("provenance", inventory.provenance());
            document.addProperty("projectHash", StorageSafety.sha256(inventory.projectJson()));
            JsonArray assets = new JsonArray();
            inventory.assets().stream().sorted(Comparator.comparing(asset -> asset.key().canonical())).forEach(asset -> {
                JsonObject item = new JsonObject();
                item.addProperty("key", asset.key().canonical());
                item.addProperty("path", asset.path().toString().replace('\\', '/'));
                item.add("state", expectedStateJson(asset.state()));
                item.addProperty("mutationId", asset.mutationId().value());
                item.addProperty("contentHash", StorageSafety.sha256(asset.content()));
                if (asset.deletionProvenance() != null) {
                    item.add("deletionProvenance", deletionProvenanceJson(asset.deletionProvenance()));
                }
                assets.add(item);
            });
            document.add("assets", assets);
            JsonArray blocked = new JsonArray();
            inventory.blocked().stream().map(AssetTransactionCoordinator::blockedAdoptionJson).forEach(blocked::add);
            document.add("blocked", blocked);
            JsonArray evidence = new JsonArray();
            inventory.evidence().stream().sorted(Comparator.comparing(AdoptionEvidence::originalPath)).forEach(item -> {
                JsonObject value = new JsonObject();
                value.addProperty("originalPath", item.originalPath());
                value.addProperty("hash", item.hash());
                value.addProperty("size", item.size());
                evidence.add(value);
            });
            document.add("evidence", evidence);
            String basis = AssetProjectMetadata.of(document).canonicalJson();
            return new AdoptionBinding(StorageSafety.sha256("artifact\n" + basis),
                StorageSafety.sha256("manifest\n" + basis));
        }
    }

    @FunctionalInterface
    public interface AdoptionPreparation {
        void prepare(Path canonicalRoot) throws IOException;
    }

    public record AssetMutationId(String value) {
        public AssetMutationId {
            if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Asset mutation ID is invalid");
            }
        }

        public static AssetMutationId of(UUID mutationId) {
            return new AssetMutationId(Objects.requireNonNull(mutationId, "mutationId").toString());
        }

        public Optional<UUID> uuid() {
            try {
                UUID parsed = UUID.fromString(value);
                return parsed.toString().equals(value) ? Optional.of(parsed) : Optional.empty();
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
    }

    public record LegacyDeletionProvenance(String priorPayloadHash, Path originalLogicalPath,
                                           Path canonicalFuturePath, String tombstoneEvidenceHash,
                                           String owner, String evidenceKey, long revision,
                                           AssetMutationId mutationId) {
        public LegacyDeletionProvenance {
            if (priorPayloadHash != null) {
                requireSha256(priorPayloadHash, "Legacy deletion prior payload hash");
            }
            requireSha256(tombstoneEvidenceHash, "Legacy deletion tombstone evidence hash");
            owner = AssetKey.requireIdentity(owner, "legacy deletion owner");
            evidenceKey = requireOpaque(evidenceKey, "Legacy deletion evidence key", 4096);
            if (revision < 1L) {
                throw new IllegalArgumentException("Legacy deletion revision must be positive");
            }
            Objects.requireNonNull(mutationId, "mutationId");
            requireEvidencePath(originalLogicalPath, "Legacy deletion original logical path");
            requireEvidencePath(canonicalFuturePath, "Legacy deletion canonical future path");
        }
    }

    public record BlockedAdoption(String owner, String evidenceKey, String evidenceHash, long revision,
                                  AssetMutationId mutationId, AssetKey candidateKey, Path originalLogicalPath,
                                  Path canonicalFuturePath, String reason) {
        public BlockedAdoption {
            owner = AssetKey.requireIdentity(owner, "blocked adoption owner");
            evidenceKey = requireOpaque(evidenceKey, "Blocked adoption evidence key", 4096);
            requireSha256(evidenceHash, "Blocked adoption evidence hash");
            if (revision < 0L) {
                throw new IllegalArgumentException("Blocked adoption revision cannot be negative");
            }
            Objects.requireNonNull(mutationId, "mutationId");
            reason = requireOpaque(reason, "Blocked adoption reason", 4096);
            requireEvidencePath(originalLogicalPath, "Blocked adoption original logical path");
            requireEvidencePath(canonicalFuturePath, "Blocked adoption canonical future path");
        }

        public BlockedAdoption(String owner, String evidenceKey, String evidenceHash, long revision,
                               AssetMutationId mutationId, Path originalLogicalPath,
                               Path canonicalFuturePath, String reason) {
            this(owner, evidenceKey, evidenceHash, revision, mutationId, null, originalLogicalPath,
                canonicalFuturePath, reason);
        }
    }

    private static void requireEvidencePath(Path path, String field) {
        if (path != null && path.toString().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains control characters");
        }
    }

    private static String requireOpaque(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    public record AdoptedAsset(AssetKey key, Path path, ExpectedState state, AssetMutationId mutationId,
                               byte[] content, LegacyDeletionProvenance deletionProvenance) {
        public AdoptedAsset {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(mutationId, "mutationId");
            content = content == null ? new byte[0] : content.clone();
            if (state instanceof Missing || state instanceof Deleted && content.length != 0) {
                throw new IllegalArgumentException("Adopted asset content does not match its state");
            }
            if (state instanceof Deleted deleted) {
                if (deletionProvenance == null || deletionProvenance.revision() != deleted.revision()
                    || !deletionProvenance.mutationId().equals(mutationId)
                    || !deletionProvenance.tombstoneEvidenceHash().equals(deleted.hash())
                    || deletionProvenance.canonicalFuturePath() == null
                    || !deletionProvenance.canonicalFuturePath().normalize().equals(path.normalize())) {
                    throw new IllegalArgumentException("Adopted deleted asset requires matching legacy deletion provenance");
                }
            } else if (deletionProvenance != null) {
                throw new IllegalArgumentException("Adopted live asset cannot contain deletion provenance");
            }
        }

        public AdoptedAsset(AssetKey key, Path path, ExpectedState state, UUID mutationId, byte[] content) {
            this(key, path, requireLiveAdoption(state), AssetMutationId.of(mutationId), content, null);
        }

        public AdoptedAsset(AssetKey key, Path path, Live state, AssetMutationId mutationId, byte[] content) {
            this(key, path, state, mutationId, content, null);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        private static ExpectedState requireLiveAdoption(ExpectedState state) {
            if (!(state instanceof Live)) {
                throw new IllegalArgumentException("Deleted adoption requires explicit legacy deletion provenance");
            }
            return state;
        }
    }

    public sealed interface ExpectedState permits Missing, Live, Deleted {
        String kind();

        long revision();

        String hash();
    }

    public enum Missing implements ExpectedState {
        INSTANCE;

        @Override
        public String kind() {
            return "MISSING";
        }

        @Override
        public long revision() {
            return 0L;
        }

        @Override
        public String hash() {
            return "";
        }
    }

    public record Live(long revision, String hash) implements ExpectedState {
        public Live {
            if (revision < 0L) {
                throw new IllegalArgumentException("Live asset revision cannot be negative");
            }
            requireSha256(hash, "Live asset hash");
        }

        @Override
        public String kind() {
            return "LIVE";
        }
    }

    public record Deleted(long revision, String hash) implements ExpectedState {
        public Deleted {
            if (revision < 1L) {
                throw new IllegalArgumentException("Deleted asset revision must be positive");
            }
            requireSha256(hash, "Deleted asset hash");
        }

        @Override
        public String kind() {
            return "DELETED";
        }
    }

    public record ExpectedProject(long revision, String hash) {
        public ExpectedProject {
            if (revision < 0L) {
                throw new IllegalArgumentException("Asset project revision cannot be negative");
            }
            requireSha256(hash, "Asset project hash");
        }
    }

    public record AssetDelta(AssetKey key, Path path, Path previousPath, ExpectedState expected, byte[] content,
                             boolean deleted, AssetKey lineageSource, ExpectedState lineageState,
                             boolean transformedRelocation) {
        public AssetDelta {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(expected, "expected");
            if (deleted && previousPath != null) {
                throw new IllegalArgumentException("Asset relocation cannot be deleted");
            }
            if ((lineageSource == null) != (lineageState == null)) {
                throw new IllegalArgumentException("Asset reclassification lineage is incomplete");
            }
            if (lineageSource != null && (deleted || previousPath != null || lineageSource.equals(key)
                || !(lineageState instanceof Live))) {
                throw new IllegalArgumentException("Asset reclassification lineage is invalid");
            }
            if (transformedRelocation && (previousPath == null || deleted || !(expected instanceof Live))) {
                throw new IllegalArgumentException("Transformed asset relocation is invalid");
            }
            if (deleted) {
                content = new byte[0];
            } else {
                content = Objects.requireNonNull(content, "content").clone();
            }
            if (previousPath != null && (!(expected instanceof Live live)
                || !transformedRelocation && !live.hash().equals(StorageSafety.sha256(content)))) {
                throw new IllegalArgumentException("Asset relocation source or payload mode is invalid");
            }
        }

        public AssetDelta(AssetKey key, Path path, ExpectedState expected, byte[] content, boolean deleted) {
            this(key, path, null, expected, content, deleted, null, null, false);
        }

        public AssetDelta(AssetKey key, Path path, Path previousPath, ExpectedState expected, byte[] content,
                          boolean deleted) {
            this(key, path, previousPath, expected, content, deleted, null, null, false);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public static AssetDelta write(AssetKey key, Path path, ExpectedState expected, byte[] content) {
            return new AssetDelta(key, path, null, expected, content, false, null, null, false);
        }

        public static AssetDelta delete(AssetKey key, Path path, ExpectedState expected) {
            return new AssetDelta(key, path, null, expected, new byte[0], true, null, null, false);
        }

        public static AssetDelta relocate(AssetKey key, Path sourcePath, Path targetPath, ExpectedState expected,
                                          byte[] content) {
            if (!(expected instanceof Live live) || !live.hash().equals(StorageSafety.sha256(content))) {
                throw new IllegalArgumentException("Asset relocation must preserve payload bytes");
            }
            return new AssetDelta(key, targetPath, Objects.requireNonNull(sourcePath, "sourcePath"), expected,
                content, false, null, null, false);
        }

        public static AssetDelta relocateTransformed(AssetKey key, Path sourcePath, Path targetPath,
                                                     Live expected, byte[] transformedContent) {
            return new AssetDelta(key, targetPath, Objects.requireNonNull(sourcePath, "sourcePath"), expected,
                transformedContent, false, null, null, true);
        }
    }

    public record Reclassification(AssetDelta source, AssetDelta target) {
        public Reclassification {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            if (!source.deleted() || target.deleted() || source.previousPath() != null || target.previousPath() != null
                || source.key().equals(target.key()) || source.path().equals(target.path())
                || !source.key().equals(target.lineageSource()) || !source.expected().equals(target.lineageState())) {
                throw new IllegalArgumentException("Asset reclassification requires distinct source delete and target write deltas");
            }
            if (!(source.expected() instanceof Live)) {
                throw new IllegalArgumentException("Asset reclassification source must be live");
            }
            if (!(target.expected() instanceof Missing)) {
                throw new IllegalArgumentException("Asset reclassification target must be missing");
            }
        }

        public static Reclassification of(AssetKey sourceKey, Path sourcePath, ExpectedState sourceExpected,
                                          AssetKey targetKey, Path targetPath, ExpectedState targetExpected,
                                          byte[] content) {
            return new Reclassification(AssetDelta.delete(sourceKey, sourcePath, sourceExpected),
                new AssetDelta(targetKey, targetPath, null, targetExpected, content, false, sourceKey,
                    sourceExpected, false));
        }

        public List<AssetDelta> assets() {
            return List.of(source, target);
        }
    }

    public record ProjectDelta(List<String> path, JsonElement value, boolean remove) {
        public ProjectDelta {
            AssetProjectMetadata.Delta validated = new AssetProjectMetadata.Delta(path, value, remove);
            path = validated.path();
            value = validated.value();
        }

        @Override
        public JsonElement value() {
            return value.deepCopy();
        }

        private AssetProjectMetadata.Delta metadataDelta() {
            return new AssetProjectMetadata.Delta(path, value, remove);
        }

        public static ProjectDelta set(List<String> path, JsonElement value) {
            return new ProjectDelta(path, value, false);
        }

        public static ProjectDelta remove(List<String> path) {
            return new ProjectDelta(path, JsonNull.INSTANCE, true);
        }
    }

    public record TransactionRequest(UUID mutationId, ExpectedProject expectedProject, List<AssetDelta> assets,
                                     List<ProjectDelta> projectDeltas) {
        public TransactionRequest {
            Objects.requireNonNull(mutationId, "mutationId");
            Objects.requireNonNull(expectedProject, "expectedProject");
            assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
            projectDeltas = List.copyOf(Objects.requireNonNull(projectDeltas, "projectDeltas"));
        }

        public static TransactionRequest reclassify(UUID mutationId, ExpectedProject expectedProject,
                                                    Reclassification reclassification,
                                                    List<ProjectDelta> projectDeltas) {
            return new TransactionRequest(mutationId, expectedProject,
                Objects.requireNonNull(reclassification, "reclassification").assets(), projectDeltas);
        }
    }

    public record TransactionResult(long rootSequence, UUID mutationId, String intentHash,
                                    Map<AssetKey, ExpectedState> states, ExpectedProject project,
                                    boolean replay) {
        public TransactionResult {
            if (rootSequence < 1L) {
                throw new IllegalArgumentException("Asset transaction result sequence must be positive");
            }
            Objects.requireNonNull(mutationId, "mutationId");
            requireSha256(intentHash, "Asset transaction intent hash");
            states = Map.copyOf(Objects.requireNonNull(states, "states"));
            Objects.requireNonNull(project, "project");
        }

        private TransactionResult replayed() {
            return replay ? this : new TransactionResult(rootSequence, mutationId, intentHash, states, project, true);
        }
    }

    public record MutationView(UUID mutationId, String intentHash, JsonObject intent, TransactionResult result,
                               AssetProjectMetadata projectAfter) {
        public MutationView {
            Objects.requireNonNull(projectAfter, "projectAfter");
            Objects.requireNonNull(mutationId, "mutationId");
            requireSha256(intentHash, "Asset mutation intent hash");
            intent = Objects.requireNonNull(intent, "intent").deepCopy();
            Objects.requireNonNull(result, "result");
            if (!mutationId.equals(result.mutationId()) || !intentHash.equals(result.intentHash())) {
                throw new IllegalArgumentException("Asset mutation view identity does not match its result");
            }
        }

        @Override
        public JsonObject intent() {
            return intent.deepCopy();
        }
    }

    public record Snapshot(long rootSequence, Map<AssetKey, ExpectedState> states, Map<AssetKey, Path> paths,
                           Map<AssetKey, AssetMutationId> lineages, ExpectedProject project,
                           AssetMutationId projectLineage,
                           AssetProjectMetadata metadata, List<BlockedAdoption> blocked) {
        public Snapshot {
            states = Map.copyOf(Objects.requireNonNull(states, "states"));
            paths = Map.copyOf(Objects.requireNonNull(paths, "paths"));
            lineages = Map.copyOf(Objects.requireNonNull(lineages, "lineages"));
            blocked = List.copyOf(Objects.requireNonNull(blocked, "blocked"));
            if (!states.keySet().equals(paths.keySet()) || !states.keySet().equals(lineages.keySet())) {
                throw new IllegalArgumentException("Asset snapshot paths and lineage do not match its states");
            }
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(metadata, "metadata");
        }

        public Optional<ExpectedState> state(AssetKey key) {
            return Optional.ofNullable(states.get(key));
        }

        public Optional<Path> path(AssetKey key) {
            return Optional.ofNullable(paths.get(key));
        }

        public Optional<UUID> mutationId(AssetKey key) {
            AssetMutationId mutationId = lineages.get(key);
            return mutationId == null ? Optional.empty() : mutationId.uuid();
        }

        public Optional<AssetMutationId> lineage(AssetKey key) {
            return Optional.ofNullable(lineages.get(key));
        }

        public Optional<String> mutationValue(AssetKey key) {
            return lineage(key).map(AssetMutationId::value);
        }

        public Optional<UUID> projectMutationId() {
            return projectLineage == null ? Optional.empty() : projectLineage.uuid();
        }

        public Optional<String> projectMutationValue() {
            return projectLineage == null ? Optional.empty() : Optional.of(projectLineage.value());
        }
    }

    @FunctionalInterface
    public interface PostCommitListener {
        void committed(TransactionResult result);
    }

    public static final class ListenerRegistration implements AutoCloseable {
        private final RootContext context;
        private final PostCommitListener listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ListenerRegistration(RootContext context, PostCommitListener listener) {
            this.context = context;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                context.listeners.remove(listener);
            }
        }
    }

    public record ListenerFailure(long rootSequence, String listener, Throwable failure) {
    }

    @FunctionalInterface
    interface HealthCheckAction {
        void run() throws IOException;
    }

    public static class MutationConflictException extends IOException {
        public MutationConflictException(String message) {
            super(message);
        }
    }

    public static final class AssetPathConflictException extends MutationConflictException {
        public AssetPathConflictException(String message) {
            super(message);
        }
    }

    public static final class RootBusyException extends IOException {
        public RootBusyException(String message) {
            super(message);
        }

        public RootBusyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class StateConflictException extends MutationConflictException {
        private final Map<AssetKey, ExpectedState> authoritativeStates;
        private final ExpectedProject authoritativeProject;

        public StateConflictException(String message, Map<AssetKey, ExpectedState> authoritativeStates,
                                      ExpectedProject authoritativeProject) {
            super(message);
            this.authoritativeStates = Map.copyOf(authoritativeStates);
            this.authoritativeProject = authoritativeProject;
        }

        public Map<AssetKey, ExpectedState> authoritativeStates() {
            return authoritativeStates;
        }

        public ExpectedProject authoritativeProject() {
            return authoritativeProject;
        }
    }

    private static void requireSha256(String hash, String field) {
        if (hash == null || !SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
    }
}
