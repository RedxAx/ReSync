package restudio.resync.world;

import restudio.resync.migration.MigrationPaths;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class WorldExternalPersistenceCapability {
    public static final String OWNER = "resync.world-external-persistence";
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> REQUIRED_OPERATIONS = Set.of("save", "quiesce", "resume", "snapshot", "restore", "rebind");

    private final Object monitor = new Object();
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final Duration defaultDrainTimeout;
    private final Map<String, WorldRoot> roots = new LinkedHashMap<>();
    private final Map<Long, ActiveOperation> activeOperations = new LinkedHashMap<>();
    private final Map<SnapshotId, ExternalWorldSnapshot> registeredSnapshots = new LinkedHashMap<>();
    private WorldExternalPersistenceAdapter adapter;
    private WorldExternalPersistenceAdapterRegistration registration;
    private State state = State.UNAVAILABLE;
    private long nextOperationId;
    private long generation;
    private String failureReason = "";

    public WorldExternalPersistenceCapability() {
        this(DEFAULT_DRAIN_TIMEOUT);
    }

    public WorldExternalPersistenceCapability(Duration defaultDrainTimeout) {
        this.defaultDrainTimeout = requireTimeout(defaultDrainTimeout, "defaultDrainTimeout");
    }

    public WorldExternalPersistenceCapability(WorldExternalPersistenceAdapter adapter,
                                              Collection<WorldRoot> roots) throws IOException {
        this(DEFAULT_DRAIN_TIMEOUT);
        throw new IOException("External World Adapter Registration Is Required");
    }

    public WorldExternalPersistenceCapability(WorldExternalPersistenceAdapter adapter,
                                              Collection<WorldRoot> roots,
                                              Duration defaultDrainTimeout) throws IOException {
        this(defaultDrainTimeout);
        throw new IOException("External World Adapter Registration Is Required");
    }

    public WorldExternalPersistenceCapability(WorldExternalPersistenceAdapterRegistration registration,
                                              WorldExternalPersistenceAdapter adapter,
                                              Collection<WorldRoot> roots) throws IOException {
        this(registration, adapter, roots, DEFAULT_DRAIN_TIMEOUT);
    }

    public WorldExternalPersistenceCapability(WorldExternalPersistenceAdapterRegistration registration,
                                              WorldExternalPersistenceAdapter adapter,
                                              Collection<WorldRoot> roots,
                                              Duration defaultDrainTimeout) throws IOException {
        this(defaultDrainTimeout);
        bind(registration, adapter, roots);
    }

    public static WorldExternalPersistenceCapability unavailable() {
        return new WorldExternalPersistenceCapability();
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    public boolean available() {
        synchronized (monitor) {
            return state == State.OPEN || state == State.QUIESCED;
        }
    }

    public boolean admissionOpen() {
        synchronized (monitor) {
            return state == State.OPEN;
        }
    }

    public boolean normalMutationAdmissionOpen() {
        synchronized (monitor) {
            return state == State.UNAVAILABLE || state == State.OPEN;
        }
    }

    public boolean isQuiesced() {
        synchronized (monitor) {
            return state == State.QUIESCED;
        }
    }

    public boolean isClosed() {
        synchronized (monitor) {
            return state == State.CLOSED;
        }
    }

    public int activeOperationCount() {
        synchronized (monitor) {
            return activeOperations.size();
        }
    }

    public List<ActiveOperationInfo> activeOperations() {
        synchronized (monitor) {
            return activeOperations.values().stream()
                .map(operation -> new ActiveOperationInfo(operation.id(), operation.action(), operation.worldNames()))
                .toList();
        }
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    public WorldExternalPersistenceAdapter adapter() {
        synchronized (monitor) {
            return adapter;
        }
    }

    public WorldExternalPersistenceAdapterRegistration registration() {
        synchronized (monitor) {
            return registration;
        }
    }

    public Map<String, WorldRoot> roots() {
        synchronized (monitor) {
            return Map.copyOf(roots);
        }
    }

    public Map<SnapshotId, ExternalWorldSnapshot> registeredSnapshots() {
        synchronized (monitor) {
            return Map.copyOf(registeredSnapshots);
        }
    }

    public WorldRoot root(String worldName) {
        String name = requireWorldName(worldName);
        synchronized (monitor) {
            WorldRoot root = roots.get(name);
            if (root == null) {
                throw new IdentityRejectedException(name, "External World Root Is Not Registered");
            }
            return root;
        }
    }

    public void bind(WorldExternalPersistenceAdapterRegistration candidateRegistration,
                     WorldExternalPersistenceAdapter candidateAdapter,
                     Collection<WorldRoot> candidateRoots) throws IOException {
        Objects.requireNonNull(candidateRegistration, "registration");
        Objects.requireNonNull(candidateAdapter, "adapter");
        String adapterId = requireText(candidateAdapter.id(), "adapterId");
        if (!adapterId.equals(candidateRegistration.adapterId()) || !candidateRegistration.matches(candidateAdapter)) {
            throw new IOException("External World Adapter Registration Does Not Match Adapter Identity");
        }
        if (!REQUIRED_OPERATIONS.equals(candidateRegistration.verifiedOperations())) {
            throw new IOException("External World Adapter Registration Does Not Cover Recoverable Transactions");
        }
        Path probeManifest = candidateRegistration.probeManifest();
        if (probeManifest == null || !Files.isRegularFile(probeManifest, LinkOption.NOFOLLOW_LINKS)
            || !candidateRegistration.probeManifestHash().equals(hashFile(probeManifest))) {
            throw new IOException("External World Adapter Registration Evidence Is Missing Or Tampered");
        }
        String evidence = Files.readString(probeManifest, StandardCharsets.UTF_8);
        if (!evidence.contains("status=COMMITTED")) {
            throw new IOException("External World Adapter Registration Evidence Is Not Committed");
        }
        Map<String, WorldRoot> validatedRoots = validateRoots(candidateRoots);
        lifecycleLock.lock();
        try {
            synchronized (monitor) {
                if (state != State.UNAVAILABLE) {
                    throw new IllegalStateException("External World Persistence Is Already Bound");
                }
                adapter = candidateAdapter;
                registration = candidateRegistration;
                roots.putAll(validatedRoots);
            }
            try {
                adapterCall(candidateAdapter, () -> {
                    candidateAdapter.healthCheck(List.copyOf(validatedRoots.values()));
                    return null;
                });
            } catch (IOException | RuntimeException exception) {
                synchronized (monitor) {
                    adapter = null;
                    registration = null;
                    roots.clear();
                }
                throw exception;
            }
            synchronized (monitor) {
                state = State.OPEN;
                failureReason = "";
                monitor.notifyAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void registerWorld(String worldName, Path worldRoot) throws IOException {
        WorldRoot validated = validateRoot(new WorldRoot(requireWorldName(worldName), worldRoot));
        synchronized (monitor) {
            if (state != State.UNAVAILABLE) {
                throw new IllegalStateException("World Roots Cannot Be Added After External Persistence Is Bound");
            }
            requireDistinctRoot(validated, roots.values());
            roots.put(validated.name(), validated);
        }
    }

    public MutationLease acquireMutation(String action, String worldName) {
        return acquireMutation(action, worldName == null ? List.of() : List.of(worldName));
    }

    public MutationLease acquireMutation(String action, String firstWorldName, String secondWorldName) {
        List<String> names = new ArrayList<>();
        names.add(firstWorldName);
        names.add(secondWorldName);
        return acquireMutation(action, names);
    }

    public MutationLease acquireMutation(String action, Collection<String> worldNames) {
        String operation = requireText(action, "action");
        List<String> names = validateWorldNames(worldNames);
        synchronized (monitor) {
            requireNormalAdmission();
            return addOperation(operation, names);
        }
    }

    public MutationLease acquireNormalMutation(String action, String worldName) {
        return acquireMutation(action, worldName);
    }

    public MutationLease acquireNormalMutation(String action, Collection<String> worldNames) {
        return acquireMutation(action, worldNames);
    }

    public MutationLease acquireReplacementMutation(String action, String worldName) {
        return acquireReplacementMutation(action, worldName == null ? List.of() : List.of(worldName));
    }

    public MutationLease acquireReplacementMutation(String action, String firstWorldName, String secondWorldName) {
        List<String> names = new ArrayList<>();
        names.add(firstWorldName);
        names.add(secondWorldName);
        return acquireReplacementMutation(action, names);
    }

    public MutationLease acquireReplacementMutation(String action, Collection<String> worldNames) {
        String operation = requireText(action, "action");
        List<String> names = validateWorldNames(worldNames);
        synchronized (monitor) {
            requireOpen();
            requireRegisteredNames(names);
            return addOperation(operation, names);
        }
    }

    public MutationLease acquireCreation(String action, String worldName) {
        String operation = requireText(action, "action");
        List<String> names = validateWorldNames(worldName == null ? List.of() : List.of(worldName));
        synchronized (monitor) {
            requireNormalAdmission();
            return addOperation(operation, names);
        }
    }

    public MutationLease acquireClone(String action, String sourceWorld, String targetWorld) {
        String operation = requireText(action, "action");
        List<String> candidates = new ArrayList<>();
        candidates.add(sourceWorld);
        candidates.add(targetWorld);
        List<String> names = validateWorldNames(candidates);
        synchronized (monitor) {
            requireNormalAdmission();
            return addOperation(operation, names);
        }
    }

    public void save() throws IOException {
        save(defaultDrainTimeout);
    }

    public void savePersistence() throws IOException {
        save();
    }

    public void flush() throws IOException {
        save();
    }

    public void save(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        lifecycleLock.lock();
        try {
            State previous;
            WorldExternalPersistenceAdapter currentAdapter;
            List<WorldRoot> currentRoots;
            synchronized (monitor) {
                if (state != State.OPEN && state != State.QUIESCED && state != State.QUIESCING) {
                    throw failure("External World Persistence Cannot Save From " + state.name());
                }
                previous = state == State.QUIESCED ? State.QUIESCED : State.OPEN;
                state = State.QUIESCING;
                currentAdapter = requireAdapter();
                currentRoots = List.copyOf(roots.values());
                monitor.notifyAll();
            }
            awaitDrained(wait);
            WorldExternalPersistenceAdapter.TransactionReceipt receipt = null;
            try {
                receipt = adapterCall(currentAdapter, () -> currentAdapter.save(currentRoots));
                requireReceipt(receipt, "save");
                synchronized (monitor) {
                    state = previous;
                    generation = incrementGeneration();
                    failureReason = "";
                    monitor.notifyAll();
                }
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                compensate(currentAdapter, exception.receipt(), previous, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                compensate(currentAdapter, receipt, previous, exception);
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void drain() throws IOException {
        drain(defaultDrainTimeout);
    }

    public void drain(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        lifecycleLock.lock();
        try {
            synchronized (monitor) {
                if (state == State.OPEN) {
                    state = State.QUIESCING;
                    monitor.notifyAll();
                } else if (state != State.QUIESCING) {
                    throw failure("External World Persistence Cannot Drain From " + state.name());
                }
            }
            awaitDrained(wait);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void quiesce() throws IOException {
        quiesce(defaultDrainTimeout);
    }

    public void quiescePersistence() throws IOException {
        quiesce();
    }

    public void quiesce(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        lifecycleLock.lock();
        try {
            WorldExternalPersistenceAdapter currentAdapter;
            List<WorldRoot> currentRoots;
            synchronized (monitor) {
                if (state == State.QUIESCED) {
                    return;
                }
                if (state != State.OPEN && state != State.QUIESCING) {
                    throw failure("External World Persistence Cannot Quiesce From " + state.name());
                }
                state = State.QUIESCING;
                currentAdapter = requireAdapter();
                currentRoots = List.copyOf(roots.values());
                monitor.notifyAll();
            }
            awaitDrained(wait);
            WorldExternalPersistenceAdapter.TransactionReceipt saveReceipt = null;
            WorldExternalPersistenceAdapter.TransactionReceipt quiesceReceipt = null;
            try {
                saveReceipt = adapterCall(currentAdapter, () -> currentAdapter.save(currentRoots));
                requireReceipt(saveReceipt, "save");
                quiesceReceipt = adapterCall(currentAdapter, () -> currentAdapter.quiesce(currentRoots));
                requireReceipt(quiesceReceipt, "quiesce");
                synchronized (monitor) {
                    state = State.QUIESCED;
                    generation = incrementGeneration();
                    failureReason = "";
                    monitor.notifyAll();
                }
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                if (saveReceipt == null && "save".equals(exception.receipt().operation())) {
                    saveReceipt = exception.receipt();
                }
                if (quiesceReceipt == null && "quiesce".equals(exception.receipt().operation())) {
                    quiesceReceipt = exception.receipt();
                }
                compensateQuiesce(currentAdapter, quiesceReceipt, saveReceipt, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                compensateQuiesce(currentAdapter, quiesceReceipt, saveReceipt, exception);
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void resume() throws IOException {
        lifecycleLock.lock();
        try {
            WorldExternalPersistenceAdapter currentAdapter;
            List<WorldRoot> currentRoots;
            synchronized (monitor) {
                if (state == State.OPEN) {
                    return;
                }
                if (state != State.QUIESCED) {
                    throw failure("External World Persistence Cannot Resume From " + state.name());
                }
                state = State.RESUMING;
                currentAdapter = requireAdapter();
                currentRoots = List.copyOf(roots.values());
                monitor.notifyAll();
            }
            WorldExternalPersistenceAdapter.TransactionReceipt resumeReceipt = null;
            try {
                resumeReceipt = adapterCall(currentAdapter, () -> currentAdapter.resume(currentRoots));
                requireReceipt(resumeReceipt, "resume");
                adapterCall(currentAdapter, () -> {
                    currentAdapter.healthCheck(currentRoots);
                    return null;
                });
                synchronized (monitor) {
                    state = State.OPEN;
                    generation = incrementGeneration();
                    failureReason = "";
                    monitor.notifyAll();
                }
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                compensate(currentAdapter, exception.receipt(), State.QUIESCED, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                compensate(currentAdapter, resumeReceipt, State.QUIESCED, exception);
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void resumePersistence() throws IOException {
        resume();
    }

    public ExternalWorldSnapshot snapshot(String worldName, Path destination) throws IOException {
        lifecycleLock.lock();
        try {
            String name = requireWorldName(worldName);
            WorldRoot worldRoot = root(name);
            Path target = requireSnapshotPath(destination);
            WorldExternalPersistenceAdapter currentAdapter;
            long currentGeneration;
            synchronized (monitor) {
                requireQuiesced();
                state = State.SNAPSHOTTING;
                currentAdapter = requireAdapter();
                currentGeneration = generation;
                monitor.notifyAll();
            }
            WorldExternalPersistenceAdapter.SnapshotArtifact artifact = null;
            try {
                artifact = adapterCall(currentAdapter, () -> currentAdapter.snapshot(worldRoot, target, currentGeneration));
                if (artifact == null || !target.equals(requireSnapshotPath(artifact.path()))) {
                    throw new PathRejectedException(artifact == null ? null : artifact.path(),
                        "External World Snapshot Artifact Path Does Not Match Requested Path");
                }
                ExternalWorldSnapshot snapshot = createVerifiedSnapshot(name, worldRoot, artifact, currentGeneration);
                synchronized (monitor) {
                    requireState(State.SNAPSHOTTING);
                    if (registeredSnapshots.containsKey(snapshot.snapshotId())) {
                        throw new IdentityRejectedException(snapshot.snapshotId().value(), "External World Snapshot Identity Already Registered");
                    }
                    registeredSnapshots.put(snapshot.snapshotId(), snapshot);
                    state = State.QUIESCED;
                    monitor.notifyAll();
                }
                return snapshot;
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                compensateSnapshot(currentAdapter, exception.receipt(), exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                if (artifact != null) {
                    rollback(currentAdapter, new WorldExternalPersistenceAdapter.TransactionReceipt("snapshot",
                        artifact.transactionToken()), exception);
                }
                synchronized (monitor) {
                    if (state == State.SNAPSHOTTING) {
                        state = artifact == null ? State.FAILED : State.QUIESCED;
                        failureReason = message(exception);
                        monitor.notifyAll();
                    }
                }
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void restore(String worldName, ExternalWorldSnapshot snapshot) throws IOException {
        lifecycleLock.lock();
        try {
            String name = requireWorldName(worldName);
            WorldRoot worldRoot = root(name);
            validateRegisteredSnapshot(name, worldRoot, snapshot);
            WorldExternalPersistenceAdapter currentAdapter;
            synchronized (monitor) {
                requireQuiesced();
                state = State.RESTORING;
                currentAdapter = requireAdapter();
                monitor.notifyAll();
            }
            WorldExternalPersistenceAdapter.TransactionReceipt receipt = null;
            try {
                receipt = adapterCall(currentAdapter, () -> currentAdapter.restore(worldRoot, snapshot));
                requireReceipt(receipt, "restore");
                synchronized (monitor) {
                    requireState(State.RESTORING);
                    state = State.QUIESCED;
                    generation = incrementGeneration();
                    failureReason = "";
                    monitor.notifyAll();
                }
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                compensate(currentAdapter, exception.receipt(), State.QUIESCED, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                compensate(currentAdapter, receipt, State.QUIESCED, exception);
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void rebind(Map<String, Path> candidateRoots) throws IOException {
        lifecycleLock.lock();
        try {
            Map<String, WorldRoot> validatedRoots = validateCandidateRoots(candidateRoots);
            WorldExternalPersistenceAdapter currentAdapter;
            Map<String, WorldRoot> previousRoots;
            synchronized (monitor) {
                requireQuiesced();
                if (!validatedRoots.keySet().equals(roots.keySet())) {
                    throw new IdentityRejectedException("", "External World Rebind Must Preserve Exact Named Roots");
                }
                state = State.REBINDING;
                currentAdapter = requireAdapter();
                previousRoots = new LinkedHashMap<>(roots);
                monitor.notifyAll();
            }
            WorldExternalPersistenceAdapter.TransactionReceipt receipt = null;
            try {
                receipt = adapterCall(currentAdapter, () -> currentAdapter.rebind(Map.copyOf(validatedRoots)));
                requireReceipt(receipt, "rebind");
                synchronized (monitor) {
                    requireState(State.REBINDING);
                    roots.clear();
                    roots.putAll(validatedRoots);
                    generation = incrementGeneration();
                    state = State.QUIESCED;
                    failureReason = "";
                    monitor.notifyAll();
                }
            } catch (WorldExternalPersistenceAdapter.TransactionFailure exception) {
                compensate(currentAdapter, exception.receipt(), State.QUIESCED, exception);
                synchronized (monitor) {
                    roots.clear();
                    roots.putAll(previousRoots);
                }
                throw exception;
            } catch (IOException | RuntimeException exception) {
                compensate(currentAdapter, receipt, State.QUIESCED, exception);
                synchronized (monitor) {
                    roots.clear();
                    roots.putAll(previousRoots);
                }
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void rebindPersistence(Map<String, Path> candidateRoots) throws IOException {
        rebind(candidateRoots);
    }

    public void rebindWorld(String worldName, Path worldRoot) throws IOException {
        String name = requireWorldName(worldName);
        Map<String, Path> replacement = new LinkedHashMap<>();
        synchronized (monitor) {
            for (WorldRoot root : roots.values()) {
                replacement.put(root.name(), root.root());
            }
        }
        if (!replacement.containsKey(name)) {
            throw new IdentityRejectedException(name, "External World Root Is Not Registered");
        }
        replacement.put(name, worldRoot);
        rebind(replacement);
    }

    public void healthCheck() throws IOException {
        lifecycleLock.lock();
        try {
            WorldExternalPersistenceAdapter currentAdapter;
            List<WorldRoot> currentRoots;
            synchronized (monitor) {
                if (state == State.UNAVAILABLE || state == State.FAILED || state == State.CLOSED) {
                    throw failure("External World Persistence Is " + state.name());
                }
                if (state != State.OPEN && state != State.QUIESCED) {
                    throw new IOException("External World Persistence Lifecycle Transition Is Active");
                }
                currentAdapter = requireAdapter();
                currentRoots = List.copyOf(roots.values());
            }
            try {
                adapterCall(currentAdapter, () -> {
                    currentAdapter.healthCheck(currentRoots);
                    return null;
                });
            } catch (IOException | RuntimeException exception) {
                synchronized (monitor) {
                    state = State.FAILED;
                    failureReason = message(exception);
                    monitor.notifyAll();
                }
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void healthCheckPersistence() throws IOException {
        healthCheck();
    }

    public Health health() {
        synchronized (monitor) {
            return new Health(state == State.OPEN || state == State.QUIESCED, state, activeOperations.size(), roots.size(),
                adapter == null ? "" : safeAdapterId(adapter), registration == null ? "" : registration.capabilityId(),
                generation, failureReason);
        }
    }

    public void close() throws IOException {
        lifecycleLock.lock();
        try {
            State current = state();
            if (current == State.OPEN || current == State.QUIESCING) {
                quiesce(defaultDrainTimeout);
            }
            synchronized (monitor) {
                if (state == State.FAILED) {
                    throw failure("External World Persistence Is Failed");
                }
                if (state == State.UNAVAILABLE || state == State.CLOSED) {
                    state = State.CLOSED;
                    monitor.notifyAll();
                    return;
                }
                if (state != State.QUIESCED) {
                    throw failure("External World Persistence Cannot Close From " + state.name());
                }
                state = State.CLOSED;
                monitor.notifyAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private ExternalWorldSnapshot createVerifiedSnapshot(String name, WorldRoot root,
                                                          WorldExternalPersistenceAdapter.SnapshotArtifact artifact,
                                                          long currentGeneration) throws IOException {
        Path path = requireSnapshotPath(artifact.path());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PathRejectedException(path, "External World Snapshot Artifact Must Be A Regular File");
        }
        String contentHash = hashFile(path);
        String treeHash = hashTree(root.root());
        String manifest = canonicalManifest(artifact.snapshotId(), name, root, path, currentGeneration, contentHash, treeHash);
        String signature = sign(manifest);
        return new ExternalWorldSnapshot(artifact.snapshotId(), registration.capabilityId(), registration.adapterId(), name,
            root.root(), path, currentGeneration, contentHash, treeHash, manifest, signature, true, artifact.transactionToken());
    }

    private void validateRegisteredSnapshot(String name, WorldRoot root, ExternalWorldSnapshot snapshot) throws IOException {
        if (snapshot == null || !name.equals(snapshot.worldName())) {
            throw new IdentityRejectedException(name, "External World Snapshot Identity Does Not Match");
        }
        synchronized (monitor) {
            ExternalWorldSnapshot registeredSnapshot = registeredSnapshots.get(snapshot.snapshotId());
            if (registeredSnapshot == null || !registeredSnapshot.equals(snapshot)) {
                throw new IdentityRejectedException(name, "External World Snapshot Is Not Registered");
            }
            if (state != State.QUIESCED) {
                throw new IOException("External World Persistence Must Be Quiesced");
            }
        }
        Path path = requireSnapshotPath(snapshot.path());
        Path creationRoot = requireSnapshotRoot(snapshot.root(), snapshot.worldName());
        if (!path.equals(snapshot.path()) || !snapshot.verified() || !registration.capabilityId().equals(snapshot.capabilityId())
            || !registration.adapterId().equals(snapshot.adapterId())) {
            throw new IdentityRejectedException(name, "External World Snapshot Binding Is Invalid");
        }
        if (!snapshot.manifest().equals(canonicalManifest(snapshot.snapshotId(), name, creationRoot, path, snapshot.generation(),
            snapshot.contentHash(), snapshot.treeHash())) || !snapshot.signature().equals(sign(snapshot.manifest()))) {
            throw new IdentityRejectedException(name, "External World Snapshot Manifest Or Signature Is Invalid");
        }
        if (!snapshot.contentHash().equals(hashFile(path))) {
            throw new IdentityRejectedException(name, "External World Snapshot Artifact Hash Is Invalid");
        }
    }

    private String canonicalManifest(SnapshotId snapshotId, String name, WorldRoot root, Path path, long snapshotGeneration,
                                     String contentHash, String treeHash) {
        return canonicalManifest(snapshotId, name, root.root(), path, snapshotGeneration, contentHash, treeHash);
    }

    private String canonicalManifest(SnapshotId snapshotId, String name, Path root, Path path, long snapshotGeneration,
                                     String contentHash, String treeHash) {
        return String.join("\n", OWNER, registration.capabilityId(), registration.adapterId(), snapshotId.value(), name,
            root.toString(), path.toString(), Long.toString(snapshotGeneration), contentHash, treeHash);
    }

    private Path requireSnapshotRoot(Path root, String worldName) throws IOException {
        if (root == null) {
            throw new PathRejectedException(null, "External World Snapshot Creation Root Is Required");
        }
        Path normalized = MigrationPaths.requirePath(root, "worldRoot");
        Path leaf = normalized.getFileName();
        if (leaf == null || !worldName.equals(leaf.toString())) {
            throw new PathRejectedException(normalized, "External World Snapshot Creation Root Must Be Named Exactly " + worldName);
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(normalized, "worldRoot");
            MigrationPaths.requireNoSymlinkTree(normalized);
        }
        return normalized;
    }

    private String sign(String value) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(registration.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IOException("External World Snapshot Signature Failed", exception);
        }
    }

    private String hashTree(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "worldRoot");
        MessageDigest tree = digest();
        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.comparing(Path::toString)).forEach(path -> {
                try {
                    if (Files.isSymbolicLink(path)) {
                        throw new PathRejectedException(path, "External World Root Contains A Symbolic Link");
                    }
                    Path relative = normalized.relativize(path);
                    String kind = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "d" : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? "f" : "o";
                    update(tree, kind + "\n" + relative + "\n" + Files.size(path) + "\n");
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        tree.update(Files.readAllBytes(path));
                    }
                } catch (IOException exception) {
                    throw new HashingException(exception);
                }
            });
        }
        try {
            return HexFormat.of().formatHex(tree.digest());
        } catch (HashingException exception) {
            throw exception.exception;
        }
    }

    private String hashFile(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (GeneralSecurityException exception) {
            throw new IOException("External World Snapshot Hash Failed", exception);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 Is Not Available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, WorldRoot> validateCandidateRoots(Map<String, Path> candidateRoots) throws IOException {
        if (candidateRoots == null || candidateRoots.isEmpty()) {
            throw new IdentityRejectedException("", "External World Rebind Requires Exact Named Roots");
        }
        List<WorldRoot> roots = new ArrayList<>();
        for (Map.Entry<String, Path> entry : candidateRoots.entrySet()) {
            roots.add(new WorldRoot(requireWorldName(entry.getKey()), entry.getValue()));
        }
        return validateRoots(roots);
    }

    private Map<String, WorldRoot> validateRoots(Collection<WorldRoot> candidateRoots) throws IOException {
        if (candidateRoots == null || candidateRoots.isEmpty()) {
            throw new IdentityRejectedException("", "External World Persistence Requires Exact Named Roots");
        }
        Map<String, WorldRoot> validated = new LinkedHashMap<>();
        for (WorldRoot root : candidateRoots) {
            if (root == null) {
                throw new IdentityRejectedException("", "External World Root Is Required");
            }
            WorldRoot checked = validateRoot(root);
            if (validated.put(checked.name(), checked) != null) {
                throw new IdentityRejectedException(checked.name(), "Duplicate External World Identity");
            }
        }
        validateDistinctRoots(validated.values());
        return validated;
    }

    private WorldRoot validateRoot(WorldRoot root) throws IOException {
        String name = requireWorldName(root.name());
        Path normalized = MigrationPaths.requireDirectory(root.root(), "worldRoot");
        Path leaf = normalized.getFileName();
        if (leaf == null || !name.equals(leaf.toString())) {
            throw new PathRejectedException(normalized, "External World Root Must Be Named Exactly " + name);
        }
        MigrationPaths.requireNoSymlinkTree(normalized);
        return new WorldRoot(name, normalized);
    }

    private void requireDistinctRoot(WorldRoot candidate, Collection<WorldRoot> existing) {
        for (WorldRoot root : existing) {
            if (candidate.name().equalsIgnoreCase(root.name())) {
                throw new IdentityRejectedException(candidate.name(), "Duplicate External World Identity");
            }
            MigrationPaths.requireDistinctRoots(candidate.root(), root.root());
        }
    }

    private void validateDistinctRoots(Collection<WorldRoot> candidateRoots) {
        List<WorldRoot> roots = new ArrayList<>(candidateRoots);
        for (int index = 0; index < roots.size(); index++) {
            requireDistinctRoot(roots.get(index), roots.subList(0, index));
        }
    }

    private List<String> validateWorldNames(Collection<String> worldNames) {
        if (worldNames == null || worldNames.isEmpty()) {
            throw new IdentityRejectedException("", "External World Operation Requires Exact Named Roots");
        }
        List<String> names = new ArrayList<>();
        for (String worldName : worldNames) {
            String name = requireWorldName(worldName);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private Path requireSnapshotPath(Path path) {
        if (path == null) {
            throw new PathRejectedException(null, "External World Snapshot Path Is Required");
        }
        Path normalized = MigrationPaths.requirePath(path, "snapshotPath");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new PathRejectedException(normalized, "External World Snapshot Path Must Be A Regular File");
        }
        return normalized;
    }

    private long incrementGeneration() throws IOException {
        try {
            return Math.addExact(generation, 1L);
        } catch (ArithmeticException exception) {
            throw new IOException("External World Persistence Generation Overflowed", exception);
        }
    }

    private WorldExternalPersistenceAdapter requireAdapter() throws IOException {
        if (adapter == null || registration == null) {
            throw failure("External World Persistence Is Unavailable");
        }
        return adapter;
    }

    private void requireOpen() {
        if (state == State.UNAVAILABLE) {
            throw new OperationRejectedException("External World Persistence Is Unavailable", "WORLD_PERSISTENCE_UNAVAILABLE");
        }
        if (state != State.OPEN) {
            throw new OperationRejectedException("External World Persistence Admission Is " + state.name(), "WORLD_PERSISTENCE_ADMISSION_CLOSED");
        }
    }

    private void requireNormalAdmission() {
        if (state == State.UNAVAILABLE || state == State.OPEN) {
            return;
        }
        throw new OperationRejectedException("World Replacement Fence Is " + state.name(), "WORLD_REPLACEMENT_FENCE_CLOSED");
    }

    private void requireQuiesced() throws IOException {
        if (state == State.UNAVAILABLE) {
            throw failure("External World Persistence Is Unavailable");
        }
        if (state != State.QUIESCED) {
            throw new IOException("External World Persistence Must Be Quiesced");
        }
    }

    private void requireState(State expected) throws IOException {
        synchronized (monitor) {
            if (state != expected) {
                throw new IOException("External World Persistence Changed During " + expected.name());
            }
        }
    }

    private void awaitDrained(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (!activeOperations.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    IOException exception = new IOException("External World Persistence Drain Timed Out With " + activeOperations.size() + " Active Operations");
                    fail(exception);
                    throw exception;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException("External World Persistence Drain Was Interrupted", exception);
                    fail(failure);
                    throw failure;
                }
            }
        }
    }

    private void compensateQuiesce(WorldExternalPersistenceAdapter currentAdapter,
                                   WorldExternalPersistenceAdapter.TransactionReceipt quiesceReceipt,
                                   WorldExternalPersistenceAdapter.TransactionReceipt saveReceipt,
                                   Throwable failure) throws IOException {
        IOException rollbackFailure = null;
        if (saveReceipt == null || quiesceReceipt == null) {
            rollbackFailure = new IOException("External World Quiesce Did Not Produce Recoverable Transaction Tokens");
        }
        if (quiesceReceipt != null) {
            rollbackFailure = rollbackFailure(currentAdapter, quiesceReceipt, rollbackFailure);
        }
        if (saveReceipt != null) {
            rollbackFailure = rollbackFailure(currentAdapter, saveReceipt, rollbackFailure);
        }
        synchronized (monitor) {
            if (rollbackFailure == null) {
                state = State.OPEN;
                failureReason = message(failure);
            } else {
                state = State.FAILED;
                failureReason = message(rollbackFailure);
            }
            monitor.notifyAll();
        }
        if (rollbackFailure != null) {
            IOException exception = new IOException("External World Quiesce Compensation Failed", failure);
            exception.addSuppressed(rollbackFailure);
            throw exception;
        }
    }

    private void compensate(WorldExternalPersistenceAdapter currentAdapter,
                             WorldExternalPersistenceAdapter.TransactionReceipt receipt,
                             State fallback,
                             Throwable failure) throws IOException {
        IOException rollbackFailure = receipt == null
            ? new IOException("External World Lifecycle Did Not Produce A Recoverable Transaction Token")
            : rollbackFailure(currentAdapter, receipt, null);
        synchronized (monitor) {
            state = rollbackFailure == null ? fallback : State.FAILED;
            failureReason = message(rollbackFailure == null ? failure : rollbackFailure);
            monitor.notifyAll();
        }
        if (rollbackFailure != null) {
            IOException exception = new IOException("External World Lifecycle Compensation Failed", failure);
            exception.addSuppressed(rollbackFailure);
            throw exception;
        }
    }

    private IOException rollbackFailure(WorldExternalPersistenceAdapter currentAdapter,
                                        WorldExternalPersistenceAdapter.TransactionReceipt receipt,
                                        IOException previous) {
        try {
            adapterCall(currentAdapter, () -> {
                currentAdapter.rollback(receipt);
                return null;
            });
        } catch (IOException exception) {
            if (previous != null) {
                previous.addSuppressed(exception);
                return previous;
            }
            return exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("External World Transaction Rollback Failed", exception);
            if (previous != null) {
                previous.addSuppressed(failure);
                return previous;
            }
            return failure;
        }
        return previous;
    }

    private void rollback(WorldExternalPersistenceAdapter currentAdapter,
                           WorldExternalPersistenceAdapter.TransactionReceipt receipt,
                           Throwable failure) throws IOException {
        IOException rollbackFailure = rollbackFailure(currentAdapter, receipt, null);
        if (rollbackFailure != null) {
            synchronized (monitor) {
                state = State.FAILED;
                failureReason = message(rollbackFailure);
                monitor.notifyAll();
            }
            IOException exception = new IOException("External World Snapshot Compensation Failed", failure);
            exception.addSuppressed(rollbackFailure);
            throw exception;
        }
    }

    private void compensateSnapshot(WorldExternalPersistenceAdapter currentAdapter,
                                    WorldExternalPersistenceAdapter.TransactionReceipt receipt,
                                    Throwable failure) throws IOException {
        IOException rollbackFailure = receipt == null
            ? new IOException("External World Snapshot Did Not Produce A Recoverable Transaction Token")
            : rollbackFailure(currentAdapter, receipt, null);
        synchronized (monitor) {
            state = rollbackFailure == null ? State.QUIESCED : State.FAILED;
            failureReason = message(rollbackFailure == null ? failure : rollbackFailure);
            monitor.notifyAll();
        }
        if (rollbackFailure != null) {
            IOException exception = new IOException("External World Snapshot Compensation Failed", failure);
            exception.addSuppressed(rollbackFailure);
            throw exception;
        }
    }

    private <T> T adapterCall(WorldExternalPersistenceAdapter currentAdapter,
                               WorldExternalPersistenceAdapter.CheckedOperation<T> operation) throws IOException {
        if (registration != null && (!registration.matches(currentAdapter)
            || !registration.adapterId().equals(safeAdapterId(currentAdapter)))) {
            throw new IOException("External World Adapter Identity Changed During Operation");
        }
        try {
            return currentAdapter.executionContract().execute(operation);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("External World Adapter Execution Failed", exception);
        }
    }

    private void fail(Throwable exception) {
        synchronized (monitor) {
            state = State.FAILED;
            failureReason = message(exception);
            monitor.notifyAll();
        }
    }

    private IOException failure(String fallback) {
        String reason = failureReason == null || failureReason.isBlank() ? fallback : failureReason;
        return new IOException(reason);
    }

    private MutationLease addOperation(String action, List<String> names) {
        long id = ++nextOperationId;
        ActiveOperation active = new ActiveOperation(id, action, names);
        activeOperations.put(id, active);
        return new MutationLease(this, active);
    }

    private void release(ActiveOperation operation) {
        synchronized (monitor) {
            if (activeOperations.remove(operation.id()) != null) {
                monitor.notifyAll();
            }
        }
    }

    private void requireRegisteredNames(Collection<String> names) {
        for (String name : names) {
            if (!roots.containsKey(name)) {
                throw new IdentityRejectedException(name, "External World Root Is Not Registered");
            }
        }
    }

    private static void requireReceipt(WorldExternalPersistenceAdapter.TransactionReceipt receipt, String operation) throws IOException {
        if (receipt == null || !operation.equals(receipt.operation()) || receipt.token().isBlank()) {
            throw new IOException("External World Adapter Did Not Return A Recoverable " + operation + " Transaction");
        }
    }

    private static String requireWorldName(String worldName) {
        if (worldName == null || worldName.isBlank() || !worldName.equals(worldName.trim())) {
            throw new IdentityRejectedException(worldName, "External World Identity Must Be Exact");
        }
        if (worldName.indexOf('\u0000') >= 0 || worldName.indexOf('/') >= 0 || worldName.indexOf('\\') >= 0
            || worldName.equals(".") || worldName.equals("..") || worldName.contains("..")) {
            throw new IdentityRejectedException(worldName, "External World Identity Is Not A Named World");
        }
        try {
            Path parsed = Path.of(worldName);
            if (parsed.isAbsolute() || parsed.getNameCount() != 1 || !worldName.equals(parsed.getFileName().toString())) {
                throw new IdentityRejectedException(worldName, "External World Identity Is Not A Named World");
            }
        } catch (InvalidPathException exception) {
            throw new IdentityRejectedException(worldName, "External World Identity Is Not A Named World", exception);
        }
        return worldName;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " Must Be Exact And Non-Blank");
        }
        return value;
    }

    private static Duration requireTimeout(Duration timeout, String field) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(field + " Must Be Non-Negative");
        }
        return timeout;
    }

    private static String safeAdapterId(WorldExternalPersistenceAdapter adapter) {
        try {
            return adapter.id() == null ? "" : adapter.id();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String message(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        return message == null || message.isBlank() ? exception == null ? "" : exception.getClass().getSimpleName() : message;
    }

    public enum State {
        UNAVAILABLE,
        OPEN,
        QUIESCING,
        QUIESCED,
        SNAPSHOTTING,
        RESTORING,
        REBINDING,
        RESUMING,
        FAILED,
        CLOSED
    }

    public record SnapshotId(String value) {
        public SnapshotId {
            value = requireText(value, "snapshotId");
        }
    }

    public record WorldRoot(String name, Path root) {
        public WorldRoot {
            name = Objects.requireNonNull(name, "name");
            root = MigrationPaths.requirePath(root, "root");
        }
    }

    public record ExternalWorldSnapshot(SnapshotId snapshotId, String capabilityId, String adapterId, String worldName,
                                        Path root, Path path, long generation, String contentHash, String treeHash,
                                        String manifest, String signature, boolean verified, String transactionToken) {
        public ExternalWorldSnapshot {
            snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
            capabilityId = capabilityId == null ? "" : capabilityId;
            adapterId = adapterId == null ? "" : adapterId;
            worldName = requireWorldName(worldName);
            root = root == null ? null : MigrationPaths.requirePath(root, "worldRoot");
            path = MigrationPaths.requirePath(path, "snapshotPath");
            if (generation < 0L) {
                throw new IllegalArgumentException("generation Must Not Be Negative");
            }
            contentHash = contentHash == null ? "" : contentHash;
            treeHash = treeHash == null ? "" : treeHash;
            manifest = manifest == null ? "" : manifest;
            signature = signature == null ? "" : signature;
            transactionToken = transactionToken == null ? "" : transactionToken;
            if (verified && (root == null || capabilityId.isBlank() || adapterId.isBlank() || contentHash.isBlank()
                || treeHash.isBlank() || manifest.isBlank() || signature.isBlank() || transactionToken.isBlank())) {
                throw new IllegalArgumentException("Verified External World Snapshot Metadata Is Incomplete");
            }
        }

        public ExternalWorldSnapshot(String snapshotId, String worldName, Path path, long generation) {
            this(new SnapshotId(snapshotId), "", "", worldName, null, path, generation, "", "", "", "", false, "");
        }
    }

    public record ActiveOperationInfo(long id, String action, List<String> worldNames) {
        public ActiveOperationInfo {
            worldNames = List.copyOf(worldNames == null ? List.of() : worldNames);
        }
    }

    public record Health(boolean available, State state, int activeOperations, int registeredWorlds,
                         String adapterId, String capabilityId, long generation, String failureReason) {
        public Health {
            adapterId = adapterId == null ? "" : adapterId;
            capabilityId = capabilityId == null ? "" : capabilityId;
            failureReason = failureReason == null ? "" : failureReason;
        }

        public Health(boolean available, State state, int activeOperations, int registeredWorlds,
                      String adapterId, long generation, String failureReason) {
            this(available, state, activeOperations, registeredWorlds, adapterId, "", generation, failureReason);
        }
    }

    public static class OperationRejectedException extends IllegalStateException {
        private final String code;

        public OperationRejectedException(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class IdentityRejectedException extends IllegalArgumentException {
        private final String identity;

        public IdentityRejectedException(String identity, String message) {
            super(message);
            this.identity = identity;
        }

        public IdentityRejectedException(String identity, String message, Throwable cause) {
            super(message, cause);
            this.identity = identity;
        }

        public String identity() {
            return identity;
        }
    }

    public static final class PathRejectedException extends IllegalArgumentException {
        private final Path path;

        public PathRejectedException(Path path, String message) {
            super(message);
            this.path = path;
        }

        public Path path() {
            return path;
        }
    }

    private record ActiveOperation(long id, String action, List<String> worldNames) {
        private ActiveOperation {
            worldNames = List.copyOf(worldNames);
        }
    }

    private static final class HashingException extends RuntimeException {
        private final IOException exception;

        private HashingException(IOException exception) {
            super(exception);
            this.exception = exception;
        }
    }

    public static final class MutationLease implements AutoCloseable {
        private final WorldExternalPersistenceCapability owner;
        private final ActiveOperation operation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MutationLease(WorldExternalPersistenceCapability owner, ActiveOperation operation) {
            this.owner = owner;
            this.operation = operation;
        }

        public long id() {
            return operation.id();
        }

        public String action() {
            return operation.action();
        }

        public List<String> worldNames() {
            return operation.worldNames();
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(operation);
            }
        }
    }
}
