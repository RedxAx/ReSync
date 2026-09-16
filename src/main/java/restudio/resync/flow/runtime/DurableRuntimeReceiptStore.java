package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.concurrent.locks.ReentrantLock;

public final class DurableRuntimeReceiptStore
    implements RuntimeReceiptStore, RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.runtime.invocation";
    public static final String DIRECTORY = "runtime";
    public static final String FILE_NAME = "runtime-receipts.json";
    public static final String QUARANTINE_DIRECTORY = ".quarantine/runtime-receipt-temps";
    private static final String KIND = "runtime-invocation-store";
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final String LOCK_SUFFIX = ".lock";
    private static final long MAX_DOCUMENT_BYTES = 33_554_432L;
    private static final long VERSION = 2;
    private static final Set<String> DOCUMENT_FIELDS = Set.of("kind", "version", "generation", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of("key", "inputHash", "state", "outcome", "auditState", "audit", "provenance", "failure");
    private static final Set<String> KEY_FIELDS = Set.of("provider", "planFingerprint", "binding", "executionFingerprint", "authority", "idempotencyKey", "principal", "idempotencyKind");
    private static final Set<String> AUDIT_FIELDS = Set.of("leaseId", "deliveryId", "authorityReference", "bindingReference", "idempotencyReference", "status", "sensitive", "phase");
    private static final Map<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private final Path scopeRoot;
    private final Object monitor = new Object();
    private Path activeScopeRoot;
    private Path activeFile;
    private Map<RuntimeReceiptStore.Key, Entry> entries;
    private long generation;
    private long baselineGeneration;
    private Map<Key, String> baselineEntrySignatures = Map.of();
    private String baselineContentHash = "";
    private String baselineDocumentHash = "";
    private int activeInvocations;
    private int activeAuditDeliveries;
    private final Set<Key> auditDeliveries = new HashSet<>();
    private boolean quiescing;
    private boolean quiesced;
    private boolean faulted;
    private String faultReason = "";
    private volatile Observation observation = new Observation(0, true);
    private int observationChanges;

    public record Observation(long revision, boolean stable) {
    }

    public Observation observation() {
        return observation;
    }

    public boolean isCurrent(Observation expected) {
        return expected != null && expected.stable() && observation == expected;
    }

    private ObservationChange observeChange() {
        observationChanges++;
        observation = new Observation(observation.revision() + 1, false);
        return new ObservationChange();
    }

    private final class ObservationChange implements AutoCloseable {
        @Override
        public void close() {
            observationChanges--;
            observation = new Observation(observation.revision() + 1, observationChanges == 0);
        }
    }

    public DurableRuntimeReceiptStore(Path dataRoot) {
        this(dataRoot, MigrationPaths.requirePath(dataRoot, "dataRoot").resolve(DIRECTORY).resolve(FILE_NAME));
    }

    public DurableRuntimeReceiptStore(Path scopeRoot, Path receiptFile) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.activeScopeRoot = this.scopeRoot;
        this.activeFile = MigrationPaths.requirePath(receiptFile, "receiptFile");
        if (!activeFile.startsWith(this.scopeRoot) || activeFile.equals(this.scopeRoot)) {
            throw new IllegalArgumentException("Runtime Receipt File Must Be Inside Its Scope Root");
        }
        synchronized (monitor) {
            try {
                ensureParent(activeFile);
                MigrationPaths.requireNoSymlinkTraversal(this.activeScopeRoot, activeFile.getParent());
                LoadedDocument loaded = readOrBootstrap(activeFile);
                adoptLoadedDocument(loaded);
            } catch (IOException | RuntimeException exception) {
                faulted = true;
                faultReason = reason(exception);
                throw new IllegalStateException("Runtime Receipt Store Could Not Be Recovered", exception);
            }
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        synchronized (monitor) {
            return activeFile;
        }
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        Objects.requireNonNull(context, "context");
        return PersistenceOwnershipIndex.builder(context)
            .exactRoot()
            .rootSiblingExact(context.participantRoot().getFileName().toString() + LOCK_SUFFIX)
            .rootSiblingExact(QUARANTINE_CONTAINER)
            .rootSiblingExact(QUARANTINE_DIRECTORY)
            .rootSiblingAtomicTemp(ATOMIC_TEMP_SUFFIX)
            .rootSiblingAtomicTemp(QUARANTINE_DIRECTORY, ATOMIC_TEMP_SUFFIX)
            .build();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        synchronized (monitor) {
            Path parent = activeFile.getParent();
            Path quarantineRoot = parent.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
            String targetName = activeFile.getFileName().toString();
            Path quarantineContainer = parent.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
            Path lockFile = parent.resolve(lockFileName(targetName)).toAbsolutePath().normalize();
            return candidate.equals(activeFile) || candidate.equals(lockFile)
                || candidate.equals(quarantineContainer)
                || candidate.equals(quarantineRoot)
                || isAtomicTempSibling(parent, targetName, candidate)
                || isAtomicTempSibling(quarantineRoot, targetName, candidate);
        }
    }

    @Override
    public Claim claim(Key key, ContentHash inputHash) {
        Objects.requireNonNull(key, "Receipt Key Is Required");
        Objects.requireNonNull(inputHash, "Receipt Input Hash Is Required");
        synchronized (monitor) {
            requireOperational();
            Entry entry = entries.get(key);
            if (entry != null) {
                return new Claim(entry.outcome, false, entry.inputHash.equals(inputHash), true);
            }
            Map.Entry<Key, Entry> equivalent = findEquivalent(key);
            if (equivalent != null) {
                Entry selected = equivalent.getValue();
                boolean principalMatches = equivalent.getKey().principalReference().equals(key.principalReference());
                return new Claim(principalMatches ? selected.outcome : new CompletableFuture<>(), false,
                    selected.inputHash.equals(inputHash), principalMatches);
            }
            entry = new Entry(inputHash);
            entries.put(key, entry);
            return new Claim(entry.outcome, true, true, true);
        }
    }

    @Override
    public boolean available() {
        synchronized (monitor) {
            return !faulted && !quiescing && !quiesced;
        }
    }

    @Override
    public boolean quiesced() {
        synchronized (monitor) {
            return quiesced;
        }
    }

    @Override
    public RuntimeReceiptStore.InvocationLease acquireInvocationLease() {
        synchronized (monitor) {
            requireOperational();
            activeInvocations++;
            return new InvocationPermit();
        }
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void reserve(Key key, ContentHash inputHash, RuntimeExecutionProvenance provenance,
                        RuntimeLeaseInput.AuditEvent auditAttempt) {
        Objects.requireNonNull(key, "Receipt Key Is Required");
        Objects.requireNonNull(inputHash, "Receipt Input Hash Is Required");
        Objects.requireNonNull(provenance, "Runtime Provenance Is Required");
        requireCompleteProvenance(provenance, inputHash);
        requireAuditProvenance(auditAttempt, provenance);
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                requireOperational();
                Entry entry = entries.get(key);
                if (entry == null) {
                    Map.Entry<Key, Entry> equivalent = findEquivalent(key);
                    if (equivalent != null) {
                        if (!equivalent.getKey().principalReference().equals(key.principalReference())) {
                            throw new IllegalStateException("Runtime Idempotency Key Belongs To Another Principal");
                        }
                        if (!equivalent.getValue().inputHash.equals(inputHash)) {
                            throw new IllegalStateException("Runtime Idempotency Key Was Reused With Different Inputs");
                        }
                        return;
                    }
                    entry = new Entry(inputHash);
                    entries.put(key, entry);
                }
                if (!entry.inputHash.equals(inputHash)) {
                    throw new IllegalStateException("Runtime Idempotency Key Was Reused With Different Inputs");
                }
                if (entry.state == State.COMPLETED) {
                    if (entry.outcomeValue == null) {
                        throw new IllegalStateException("Completed Runtime Receipt Has No Outcome");
                    }
                    validateProvenance(key, entry, provenance);
                    return;
                }
                entry.state = State.RESERVED;
                entry.provenance = provenance;
                validateProvenance(key, entry);
                entry.audit = auditAttempt;
                entry.auditState = auditAttempt == null ? AuditState.NONE : AuditState.PENDING;
                entry.failure = "";
                persistLocked();
            }
        }
    }

    @Override
    public void complete(Key key, RuntimeResult result, RuntimeExecutionProvenance provenance,
                         RuntimeLeaseInput.AuditEvent auditEvent) {
        Objects.requireNonNull(key, "Receipt Key Is Required");
        Objects.requireNonNull(result, "Runtime Result Is Required");
        Objects.requireNonNull(provenance, "Runtime Provenance Is Required");
        requireAuditProvenance(auditEvent, provenance);
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                requireWritable();
                Entry entry = entries.get(key);
                if (entry == null) {
                    throw new IllegalStateException("Runtime Outcome Has No Durable Reservation");
                }
                requireCompleteProvenance(provenance, entry.inputHash);
                if (entry.state == State.COMPLETED) {
                    if (entry.outcomeValue == null || !entry.outcomeValue.canonicalJson().equals(result.canonicalJson())) {
                        throw new IllegalStateException("Runtime Receipt Outcome Changed After Completion");
                    }
                    validateProvenance(key, entry, provenance);
                    if (!Objects.equals(entry.audit, auditEvent)) {
                        throw new IllegalStateException("Runtime Receipt Audit Changed After Completion");
                    }
                    return;
                }
                entry.state = State.COMPLETED;
                entry.outcomeValue = result;
                entry.provenance = provenance;
                validateProvenance(key, entry);
                if (auditEvent != null) {
                    entry.audit = auditEvent;
                    entry.auditState = AuditState.PENDING;
                } else {
                    entry.auditState = AuditState.NONE;
                }
                persistLocked();
                entry.outcome.complete(result);
            }
        }
    }

    @Override
    public void recordPendingAudit(Key key, RuntimeExecutionProvenance provenance,
                                   RuntimeLeaseInput.AuditEvent auditEvent, Throwable failure) {
        Objects.requireNonNull(key, "Audit Key Is Required");
        Objects.requireNonNull(provenance, "Runtime Provenance Is Required");
        Objects.requireNonNull(auditEvent, "Audit Event Is Required");
        requireAuditProvenance(auditEvent, provenance);
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                requireWritable();
                Entry entry = entries.get(key);
                if (entry == null) {
                    throw new IllegalStateException("Pending Audit Has No Durable Reservation");
                }
                if (entry.state != State.COMPLETED || entry.outcomeValue == null) {
                    throw new IllegalStateException("Pending Audit Requires A Completed Runtime Receipt");
                }
                requireCompleteProvenance(provenance, entry.inputHash);
                entry.provenance = provenance;
                validateProvenance(key, entry);
                entry.audit = auditEvent;
                entry.auditState = AuditState.PENDING;
                entry.failure = failure == null ? "" : reason(failure);
                persistLocked();
            }
        }
    }

    @Override
    public void markAuditRecorded(Key key, RuntimeLeaseInput.AuditEvent auditEvent) {
        Objects.requireNonNull(key, "Audit Key Is Required");
        Objects.requireNonNull(auditEvent, "Audit Event Is Required");
        Objects.requireNonNull(auditEvent.provenance(), "Runtime Provenance Is Required");
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                requireWritable();
                Entry entry = entries.get(key);
                if (entry == null) {
                    return;
                }
                if (entry.provenance == null || !entry.provenance.equals(auditEvent.provenance())) {
                    throw new IllegalArgumentException("Runtime Audit Provenance Does Not Match Receipt Provenance");
                }
                if (entry.audit == null || !entry.audit.equals(auditEvent)) {
                    throw new IllegalArgumentException("Runtime Audit Event Does Not Match Receipt Audit");
                }
                entry.audit = auditEvent;
                entry.auditState = AuditState.RECORDED;
                entry.failure = "";
                persistLocked();
            }
        }
    }

    private Delivery nextDeliveryLocked() {
        return entries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(DurableRuntimeReceiptStore::keyText)))
            .filter(entry -> entry.getValue().auditState == AuditState.PENDING
                && entry.getValue().audit != null
                && !auditDeliveries.contains(entry.getKey()))
            .map(entry -> new Delivery(entry.getKey(), entry.getValue().audit))
            .findFirst()
            .orElse(null);
    }

    @Override
    public int retryPendingAudits(RuntimeAuditBoundary boundary) {
        Objects.requireNonNull(boundary, "Runtime Audit Boundary Is Required");
        int recorded = 0;
        while (true) {
            Delivery delivery;
            synchronized (monitor) {
                try (ObservationChange change = observeChange()) {
                    requireOperational();
                    delivery = nextDeliveryLocked();
                    if (delivery == null) {
                        return recorded;
                    }
                    auditDeliveries.add(delivery.key());
                    activeAuditDeliveries++;
                }
            }
            Throwable failure = null;
            try {
                boundary.record(delivery.event());
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
            RuntimeException persistenceFailure = null;
            synchronized (monitor) {
                try (ObservationChange change = observeChange()) {
                    auditDeliveries.remove(delivery.key());
                    activeAuditDeliveries--;
                    Entry entry = entries.get(delivery.key());
                    if (entry != null && entry.auditState == AuditState.PENDING && entry.audit != null
                        && entry.audit.equals(delivery.event())) {
                        if (failure == null) {
                            entry.auditState = AuditState.RECORDED;
                            entry.failure = "";
                            recorded++;
                        } else {
                            entry.failure = reason(failure);
                        }
                        try {
                            persistLocked();
                        } catch (RuntimeException exception) {
                            persistenceFailure = exception;
                        }
                    }
                    monitor.notifyAll();
                }
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }
        }
    }

    @Override
    public int retryPendingAudits() {
        synchronized (monitor) {
            return (int) entries.values().stream().filter(entry -> entry.auditState == AuditState.PENDING).count();
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                requireWritable();
                persistLocked();
            }
        }
    }

    @Override
    public void quiesce() throws IOException {
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                if (faulted) {
                    throw new IOException("Runtime Receipt Store Is Faulted: " + faultReason);
                }
                if (quiesced) {
                    return;
                }
                if (quiescing) {
                    awaitQuiescing();
                    if (faulted) {
                        throw new IOException("Runtime Receipt Store Is Faulted: " + faultReason);
                    }
                    return;
                }
                persistLocked();
                quiescing = true;
                try {
                    while (activeInvocations > 0 || activeAuditDeliveries > 0) {
                        monitor.wait();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    quiescing = false;
                    monitor.notifyAll();
                    throw new IOException("Runtime Receipt Store Quiesce Was Interrupted", exception);
                }
                if (faulted) {
                    quiescing = false;
                    throw new IOException("Runtime Receipt Store Is Faulted: " + faultReason);
                }
                try {
                    persistLocked();
                } catch (RuntimeException exception) {
                    quiescing = false;
                    throw new IOException("Runtime Receipt Store Quiesce Could Not Persist Final State", exception);
                }
                quiesced = true;
                quiescing = false;
                monitor.notifyAll();
            }
        }
    }

    @Override
    public void resume() throws IOException {
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                if (faulted) {
                    throw new IOException("Runtime Receipt Store Is Faulted: " + faultReason);
                }
                if (activeInvocations > 0) {
                    throw new IOException("Runtime Receipt Store Cannot Resume With Active Invocations");
                }
                try {
                    MigrationPaths.requireNoSymlinkTraversal(activeScopeRoot, activeFile.getParent());
                    try (FileLockHandle ignored = acquireFileLock(activeFile)) {
                        recoverAtomicTemps(activeFile.getParent(), activeFile.getFileName().toString());
                        requireExisting(activeFile);
                        adoptLoadedDocument(readDocument(activeFile));
                    }
                } catch (IOException | RuntimeException exception) {
                    faulted = true;
                    faultReason = reason(exception);
                    throw new IOException("Runtime Receipt Store Could Not Resume", exception);
                }
                quiescing = false;
                quiesced = false;
                monitor.notifyAll();
            }
        }
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                if (!quiesced) {
                    throw new IOException("Runtime Receipt Store Must Be Quiesced Before Rebind");
                }
                Path candidateScope = requireDirectory(activeRoot, "activeRoot");
                Path candidateFile = candidateScope.resolve(DIRECTORY).resolve(FILE_NAME).toAbsolutePath().normalize();
                if (!candidateFile.startsWith(candidateScope) || candidateFile.equals(candidateScope)) {
                    throw new IOException("Runtime Receipt Rebind Escaped Scope");
                }
                LoadedDocument candidate;
                MigrationPaths.requireNoSymlinkTraversal(candidateScope, candidateFile.getParent());
                try (FileLockHandle ignored = acquireFileLock(candidateFile)) {
                    recoverAtomicTemps(candidateFile.getParent(), candidateFile.getFileName().toString());
                    candidate = readDocument(candidateFile);
                }
                activeFile = candidateFile;
                activeScopeRoot = candidateScope;
                adoptLoadedDocument(candidate);
            }
        }
    }

    @Override
    public void healthCheck() throws IOException {
        synchronized (monitor) {
            try (ObservationChange change = observeChange()) {
                if (faulted) {
                    throw new IOException("Runtime Receipt Store Is Faulted: " + faultReason);
                }
                try {
                    MigrationPaths.requireNoSymlinkTraversal(activeScopeRoot, activeFile.getParent());
                    try (FileLockHandle ignored = acquireFileLock(activeFile)) {
                        recoverAtomicTemps(activeFile.getParent(), activeFile.getFileName().toString());
                        requireExisting(activeFile);
                        LoadedDocument loaded = readDocument(activeFile);
                        if (!sameBaselineDocument(loaded)) {
                            adoptLoadedDocument(loaded);
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    faulted = true;
                    faultReason = reason(exception);
                    throw new IOException("Runtime Receipt Store Health Check Failed", exception);
                }
            }
        }
    }

    @Override
    public void releaseProvider(ContractRef<ProviderId> provider) {
        Objects.requireNonNull(provider, "Provider Is Required");
    }

    public int pendingAuditCount() {
        synchronized (monitor) {
            return (int) entries.values().stream().filter(entry -> entry.auditState == AuditState.PENDING).count();
        }
    }

    @Override
    public OptionalLong storedDeadline(String authorityIdentity, String principalReference, CorrelationId invocationId) {
        Objects.requireNonNull(authorityIdentity, "Receipt Authority Is Required");
        Objects.requireNonNull(principalReference, "Receipt Principal Is Required");
        Objects.requireNonNull(invocationId, "Receipt Invocation ID Is Required");
        synchronized (monitor) {
            return entries.entrySet().stream()
                .filter(entry -> entry.getKey().authorityIdentity().equals(authorityIdentity)
                    && entry.getKey().principalReference().equals(principalReference)
                    && entry.getValue().provenance != null
                    && invocationId.equals(entry.getValue().provenance.invocationId()))
                .map(Map.Entry::getValue)
                .map(entry -> entry.provenance.deadlineMillis())
                .findFirst()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
        }
    }

    public String faultReason() {
        synchronized (monitor) {
            return faultReason;
        }
    }

    private void requireOperational() {
        if (faulted) {
            throw new IllegalStateException("Runtime Receipt Store Is Faulted: " + faultReason);
        }
        if (quiescing) {
            throw new IllegalStateException("Runtime Receipt Store Is Quiescing");
        }
        if (quiesced) {
            throw new IllegalStateException("Runtime Receipt Store Is Quiesced");
        }
    }

    private void requireWritable() {
        if (faulted) {
            throw new IllegalStateException("Runtime Receipt Store Is Faulted: " + faultReason);
        }
        if (quiesced) {
            throw new IllegalStateException("Runtime Receipt Store Is Quiesced");
        }
    }

    private void awaitQuiescing() throws IOException {
        try {
            while (quiescing && !quiesced) {
                monitor.wait();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Runtime Receipt Store Quiesce Was Interrupted", exception);
        }
    }

    private void releaseInvocationLease() {
        synchronized (monitor) {
            if (activeInvocations <= 0) {
                throw new IllegalStateException("Runtime Receipt Store Invocation Lease Was Already Released");
            }
            activeInvocations--;
            if (activeInvocations == 0) {
                monitor.notifyAll();
            }
        }
    }

    private void persistLocked() {
        try {
            MigrationPaths.requireNoSymlinkTraversal(activeScopeRoot, activeFile.getParent());
            try (FileLockHandle ignored = acquireFileLock(activeFile)) {
                ensureParent(activeFile);
                recoverAtomicTemps(activeFile.getParent(), activeFile.getFileName().toString());
                requireExisting(activeFile);
                reconcileActiveDocument(readDocument(activeFile));
                long nextGeneration = nextGeneration(generation);
                byte[] bytes = CanonicalJson.canonicalize(documentValue(entries, nextGeneration))
                    .getBytes(StandardCharsets.UTF_8);
                writeAtomic(activeFile, bytes, true);
                generation = nextGeneration;
                recordBaseline(readDocument(activeFile));
            }
        } catch (IOException | RuntimeException exception) {
            faulted = true;
            faultReason = reason(exception);
            throw new IllegalStateException("Runtime Receipt Store Could Not Be Persisted", exception);
        }
    }

    private static LoadedDocument readOrBootstrap(Path file) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "receipt file");
        try (FileLockHandle ignored = acquireFileLock(normalized)) {
            ensureParent(normalized);
            recoverAtomicTemps(normalized.getParent(), normalized.getFileName().toString());
            if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                String document = CanonicalJson.canonicalize(documentValue(Map.of(), 0));
                writeAtomic(normalized, document.getBytes(StandardCharsets.UTF_8), false);
                return readDocument(normalized);
            }
            return readDocument(normalized);
        }
    }

    private static Map<Key, Entry> read(Path file) throws IOException {
        return readDocument(file).entries();
    }

    private static LoadedDocument readDocument(Path file) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "receipt file");
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Runtime Receipt File Cannot Be A Symbolic Link");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt File Is Missing: " + normalized);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt File Must Be A Regular File");
        }
        byte[] bytes = readBytesCapped(normalized, "Runtime Receipt File");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, raw.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Runtime Receipt File Is Not Valid UTF-8");
        }
        Object parsed = CanonicalJson.parse(raw);
        if (!CanonicalJson.canonicalize(parsed).equals(raw)) {
            throw new IOException("Runtime Receipt File Is Not Canonical");
        }
        LoadedDocument decoded = decodeDocument(parsed);
        return new LoadedDocument(decoded.entries(), decoded.generation(),
            semanticHash(decoded.entries()), hex(sha256(bytes)));
    }

    private void adoptLoadedDocument(LoadedDocument loaded) {
        entries = loaded.entries();
        generation = loaded.generation();
        recordBaseline(loaded);
    }

    private void recordBaseline(LoadedDocument loaded) {
        baselineGeneration = loaded.generation();
        baselineContentHash = loaded.contentHash();
        baselineDocumentHash = loaded.documentHash();
        baselineEntrySignatures = entrySignatures(loaded.entries());
    }

    private boolean sameBaselineDocument(LoadedDocument loaded) {
        return baselineGeneration == loaded.generation()
            && Objects.equals(baselineContentHash, loaded.contentHash())
            && Objects.equals(baselineDocumentHash, loaded.documentHash());
    }

    private void reconcileActiveDocument(LoadedDocument active) throws IOException {
        if (sameBaselineDocument(active)) {
            return;
        }
        if (active.generation() < baselineGeneration) {
            throw new IOException("Runtime Receipt Active Generation Regressed");
        }
        Map<Key, String> activeSignatures = entrySignatures(active.entries());
        Map<Key, String> currentSignatures = entrySignatures(entries);
        Map<Key, Entry> merged = new LinkedHashMap<>(active.entries());
        Set<Key> keys = new HashSet<>(baselineEntrySignatures.keySet());
        keys.addAll(currentSignatures.keySet());
        keys.addAll(activeSignatures.keySet());
        for (Key key : keys) {
            String baseline = baselineEntrySignatures.get(key);
            String current = currentSignatures.get(key);
            String external = activeSignatures.get(key);
            boolean localChanged = !Objects.equals(current, baseline);
            boolean externalChanged = !Objects.equals(external, baseline);
            if (localChanged && externalChanged && !Objects.equals(current, external)) {
                throw new IOException("Runtime Receipt Active Document Conflicts With Local Mutation");
            }
            if (localChanged) {
                if (entries.containsKey(key)) {
                    merged.put(key, entries.get(key));
                } else {
                    merged.remove(key);
                }
            }
        }
        entries = merged;
        generation = Math.max(generation, active.generation());
        recordBaseline(active);
    }

    private static String semanticHash(Map<Key, Entry> values) {
        String document = CanonicalJson.canonicalize(documentValue(values, 0));
        return hex(sha256(document.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<Key, String> entrySignatures(Map<Key, Entry> values) {
        Map<Key, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key,
            CanonicalJson.canonicalize(encodeEntry(key, value))));
        return Map.copyOf(result);
    }

    private static Map<String, Object> documentValue(Map<Key, Entry> entries, long generation) {
        List<Map<String, Object>> values = entries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(DurableRuntimeReceiptStore::keyText)))
            .map(entry -> encodeEntry(entry.getKey(), entry.getValue()))
            .toList();
        return Map.of("entries", values, "generation", generation, "kind", KIND, "version", VERSION);
    }

    private static Map<String, Object> encodeEntry(Key key, Entry entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", encodeKey(key));
        value.put("inputHash", entry.inputHash.canonicalText());
        value.put("state", entry.state.name().toLowerCase(Locale.ROOT));
        if (entry.outcomeValue != null) {
            value.put("outcome", entry.outcomeValue.canonicalValue());
        }
        value.put("auditState", entry.auditState.name().toLowerCase(Locale.ROOT));
        if (entry.audit != null) {
            value.put("audit", encodeAudit(entry.audit));
        }
        if (entry.provenance != null) {
            value.put("provenance", entry.provenance.canonicalValue());
        }
        if (entry.failure != null && !entry.failure.isBlank()) {
            value.put("failure", entry.failure);
        }
        return value;
    }

    private static Map<String, Object> encodeKey(Key key) {
        return Map.of(
            "provider", key.provider().canonicalText(),
            "planFingerprint", key.planFingerprint().canonicalText(),
            "binding", key.binding().canonical(),
            "executionFingerprint", key.executionFingerprint().canonicalText(),
            "authority", key.authorityIdentity(),
            "idempotencyKey", key.idempotencyKey(),
            "principal", key.principalReference(),
            "idempotencyKind", key.idempotencyKind().name().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> encodeAudit(RuntimeLeaseInput.AuditEvent event) {
        return Map.of(
            "leaseId", event.leaseId().toString(),
            "deliveryId", event.deliveryId().toString(),
            "authorityReference", event.authorityReference(),
            "bindingReference", event.bindingReference(),
            "idempotencyReference", event.idempotencyReference(),
            "status", event.status().name().toLowerCase(Locale.ROOT),
            "sensitive", event.sensitive(),
            "phase", event.phase());
    }

    private static LoadedDocument decodeDocument(Object raw) {
        Map<String, Object> document = object(raw, "runtime receipt document");
        requireFields(document, DOCUMENT_FIELDS, "runtime receipt document");
        if (!KIND.equals(text(document.get("kind"), "runtime receipt kind")) || integer(document.get("version"), "runtime receipt version") != VERSION) {
            throw new IllegalArgumentException("Unsupported runtime receipt document");
        }
        long generation = document.containsKey("generation")
            ? integer(document.get("generation"), "runtime receipt generation") : 0;
        if (generation < 0) {
            throw new IllegalArgumentException("Runtime receipt generation cannot be negative");
        }
        Object rawEntries = document.get("entries");
        if (!(rawEntries instanceof List<?> values)) {
            throw new IllegalArgumentException("Runtime receipt entries must be an array");
        }
        Map<Key, Entry> result = new LinkedHashMap<>();
        for (Object rawEntry : values) {
            Map<String, Object> entryValue = object(rawEntry, "runtime receipt entry");
            requireFields(entryValue, ENTRY_FIELDS, "runtime receipt entry");
            requireField(entryValue, "key", "runtime receipt entry");
            requireField(entryValue, "inputHash", "runtime receipt entry");
            requireField(entryValue, "state", "runtime receipt entry");
            requireField(entryValue, "auditState", "runtime receipt entry");
            Key key = decodeKey(entryValue.get("key"));
            Entry entry = new Entry(new ContentHash(text(entryValue.get("inputHash"), "input hash")));
            entry.state = enumValue(entryValue.get("state"), State.values(), "receipt state");
            entry.auditState = enumValue(entryValue.get("auditState"), AuditState.values(), "audit state");
            if (entryValue.containsKey("outcome")) {
                if (entry.state != State.COMPLETED) {
                    throw new IllegalArgumentException("Reserved runtime receipt cannot contain an outcome");
                }
                entry.outcomeValue = RuntimeResult.fromCanonical(object(entryValue.get("outcome"), "runtime receipt outcome"));
                entry.outcome.complete(entry.outcomeValue);
            } else if (entry.state == State.RESERVED) {
                entry.outcomeValue = RuntimeResult.recoveredReservation(keyText(key));
                entry.state = State.COMPLETED;
                entry.outcome.complete(entry.outcomeValue);
            } else {
                throw new IllegalArgumentException("Completed runtime receipt has no outcome");
            }
            entry.provenance = entryValue.containsKey("provenance")
                ? decodeProvenance(entryValue.get("provenance")) : null;
            if (entryValue.containsKey("outcome") && entry.provenance == null) {
                throw new IllegalArgumentException("Completed runtime receipt has no provenance");
            }
            if (entry.provenance != null) {
                requireCompleteProvenance(entry.provenance, entry.inputHash);
            }
            validateProvenance(key, entry);
            if (entryValue.containsKey("audit")) {
                entry.audit = decodeAudit(entryValue.get("audit"), entry.provenance);
            }
            if (entry.auditState != AuditState.NONE && entry.audit == null) {
                throw new IllegalArgumentException("Runtime receipt audit state has no audit event");
            }
            if (entry.auditState == AuditState.NONE && entry.audit != null) {
                throw new IllegalArgumentException("Runtime receipt audit event has no audit state");
            }
            if (entry.auditState != AuditState.NONE && entry.provenance == null) {
                throw new IllegalArgumentException("Runtime receipt audit state has no provenance");
            }
            entry.failure = entryValue.containsKey("failure") ? text(entryValue.get("failure"), "receipt failure") : "";
            if (result.keySet().stream().anyMatch(existing -> existing.sameOperation(key))) {
                throw new IllegalArgumentException("Duplicate runtime receipt operation");
            }
            if (result.putIfAbsent(key, entry) != null) {
                throw new IllegalArgumentException("Duplicate runtime receipt key");
            }
        }
        return new LoadedDocument(result, generation, "", "");
    }

    private static Key decodeKey(Object raw) {
        Map<String, Object> value = object(raw, "runtime receipt key");
        requireFields(value, KEY_FIELDS, "runtime receipt key");
        String[] binding = text(value.get("binding"), "binding").split("#", -1);
        if (binding.length != 2) {
            throw new IllegalArgumentException("Invalid runtime receipt binding");
        }
        return new Key(
            ContractRef.parseCanonicalText(text(value.get("provider"), "provider"), ProviderId::of),
            new ContentHash(text(value.get("planFingerprint"), "plan fingerprint")),
            new RuntimeBindingKey(
                ContractRef.parseCanonicalText(binding[0], CapabilityId::of),
                ContractRef.parseCanonicalText(binding[1], OperationId::of)),
            new ContentHash(text(value.get("executionFingerprint"), "execution fingerprint")),
            text(value.get("authority"), "authority"),
            text(value.get("idempotencyKey"), "idempotency key"),
            optionalText(value.get("principal"), "principal"),
            value.containsKey("idempotencyKind")
                ? enumValue(value.get("idempotencyKind"), RuntimeReceiptStore.IdempotencyKind.values(), "idempotency kind")
                : RuntimeReceiptStore.IdempotencyKind.OPERATION_KEY);
    }

    private static RuntimeLeaseInput.AuditEvent decodeAudit(Object raw, RuntimeExecutionProvenance provenance) {
        Map<String, Object> value = object(raw, "runtime audit event");
        requireFields(value, AUDIT_FIELDS, "runtime audit event");
        RuntimeResult.Status status = switch (text(value.get("status"), "audit status")) {
            case "success" -> RuntimeResult.Status.SUCCESS;
            case "failure" -> RuntimeResult.Status.FAILURE;
            case "cancelled" -> RuntimeResult.Status.CANCELLED;
            default -> throw new IllegalArgumentException("Unknown audit status");
        };
        return new RuntimeLeaseInput.AuditEvent(
            UUID.fromString(text(value.get("leaseId"), "audit lease")),
            text(value.get("authorityReference"), "audit authority"),
            text(value.get("bindingReference"), "audit binding"),
            text(value.get("idempotencyReference"), "audit idempotency"),
            status,
            booleanValue(value.get("sensitive"), "audit sensitive"),
            text(value.get("phase"), "audit phase"), provenance,
            value.containsKey("deliveryId")
                ? UUID.fromString(text(value.get("deliveryId"), "audit delivery")) : null);
    }

    private static void requireAuditProvenance(RuntimeLeaseInput.AuditEvent event,
                                               RuntimeExecutionProvenance provenance) {
        if (event != null && (event.provenance() == null || !event.provenance().equals(provenance))) {
            throw new IllegalArgumentException("Runtime Audit Provenance Does Not Match Execution Provenance");
        }
    }

    private static void validateProvenance(Key key, Entry entry) {
        RuntimeExecutionProvenance provenance = entry.provenance;
        if (provenance == null) {
            return;
        }
        if (!key.provider().equals(provenance.provider())
            || !key.planFingerprint().equals(provenance.planFingerprint())
            || !key.binding().equals(provenance.binding())
            || !key.executionFingerprint().equals(provenance.executionFingerprint())
            || !key.authorityIdentity().equals(provenance.authorityIdentity())
            || !key.idempotencyKey().equals(provenance.idempotencyKey())
            || (!key.principalReference().isEmpty() && !key.principalReference().equals(provenance.principal().canonical()))
            || (provenance.inputHash() != null && !entry.inputHash.equals(provenance.inputHash()))) {
            throw new IllegalArgumentException("Runtime receipt provenance does not match its key");
        }
    }

    private static void validateProvenance(Key key, Entry entry, RuntimeExecutionProvenance candidate) {
        Objects.requireNonNull(candidate, "Runtime Provenance Is Required");
        if (entry.provenance != null && !entry.provenance.equals(candidate)) {
            throw new IllegalStateException("Runtime Receipt Provenance Changed After Reservation");
        }
        validateProvenance(key, entry);
    }

    private static void requireCompleteProvenance(RuntimeExecutionProvenance provenance, ContentHash inputHash) {
        if (provenance.inputHash() == null || !provenance.inputHash().equals(inputHash)
            || provenance.contextHash() == null || provenance.leaseId() == null
            || provenance.invocationId() == null || provenance.deadlineMillis() < 0) {
            throw new IllegalArgumentException("Runtime Receipt Provenance Is Incomplete");
        }
    }

    private Map.Entry<Key, Entry> findEquivalent(Key key) {
        return entries.entrySet().stream()
            .filter(entry -> entry.getKey().sameOperation(key))
            .findFirst()
            .orElse(null);
    }

    private static RuntimeExecutionProvenance decodeProvenance(Object raw) {
        Map<String, Object> value = object(raw, "runtime execution provenance");
        Set<String> fields = Set.of("authority", "principal", "binding", "provider", "providerVersion",
            "runtimeGeneration", "runtimeManifestHash", "catalogGeneration", "catalogHash", "planFingerprint",
            "executionFingerprint", "idempotencyKey", "invocationId", "mutationId", "inputHash", "contextHash",
            "deadlineMillis", "leaseId", "sessionReference", "creatorPrincipal", "creatorSessionReference");
        requireFields(value, fields, "runtime execution provenance");
        String authority = text(value.get("authority"), "provenance authority");
        return new RuntimeExecutionProvenance(
            authority,
            decodePrincipal(value.get("principal"), authority),
            decodeBindingKey(value.get("binding")),
            decodeProvider(value.get("provider")),
            text(value.get("providerVersion"), "provenance provider version"),
            integer(value.get("runtimeGeneration"), "provenance runtime generation"),
            new ContentHash(text(value.get("runtimeManifestHash"), "provenance runtime manifest hash")),
            integer(value.get("catalogGeneration"), "provenance catalog generation"),
            value.containsKey("catalogHash")
                ? new ContentHash(text(value.get("catalogHash"), "provenance catalog hash")) : null,
            new ContentHash(text(value.get("planFingerprint"), "provenance plan fingerprint")),
            new ContentHash(text(value.get("executionFingerprint"), "provenance execution fingerprint")),
            text(value.get("idempotencyKey"), "provenance idempotency key"),
            value.containsKey("invocationId")
                ? CorrelationId.parseCanonicalText(text(value.get("invocationId"), "provenance invocation id"))
                : CorrelationId.deterministic("legacy-runtime-invocation:" + text(value.get("idempotencyKey"), "provenance idempotency key")),
            value.containsKey("mutationId") ? text(value.get("mutationId"), "provenance mutation id") : null,
            value.containsKey("inputHash") ? new ContentHash(text(value.get("inputHash"), "provenance input hash")) : null,
            value.containsKey("contextHash")
                ? new ContentHash(text(value.get("contextHash"), "provenance context hash"))
                : ContentHash.of(CanonicalJson.sha256("runtime-context", Map.of())),
            value.containsKey("deadlineMillis")
                ? integer(value.get("deadlineMillis"), "provenance deadline") : RuntimeExecutionContext.NO_DEADLINE,
            value.containsKey("leaseId") ? UUID.fromString(text(value.get("leaseId"), "provenance lease id")) : null,
            value.containsKey("sessionReference") ? text(value.get("sessionReference"), "provenance session reference") : null,
            value.containsKey("creatorPrincipal") ? text(value.get("creatorPrincipal"), "provenance creator principal") : null,
            value.containsKey("creatorSessionReference")
                ? text(value.get("creatorSessionReference"), "provenance creator session reference") : null);
    }

    private static RuntimePrincipal decodePrincipal(Object raw, String authority) {
        Map<String, Object> value = object(raw, "runtime provenance principal");
        requireFields(value, Set.of("kind", "identity"), "runtime provenance principal");
        String kind = text(value.get("kind"), "provenance principal kind");
        if (!kind.equals(kind.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Provenance principal kind is not canonical");
        }
        RuntimePrincipal.Kind selected;
        try {
            selected = RuntimePrincipal.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown provenance principal kind", exception);
        }
        String identity = text(value.get("identity"), "provenance principal identity");
        UUID token = UUID.nameUUIDFromBytes((authority + ":" + kind + ":" + identity)
            .getBytes(StandardCharsets.UTF_8));
        return new RuntimePrincipal(selected, identity, token, authority);
    }

    private static RuntimeBindingKey decodeBindingKey(Object raw) {
        Map<String, Object> value = object(raw, "runtime provenance binding");
        requireFields(value, Set.of("capability", "operation"), "runtime provenance binding");
        return new RuntimeBindingKey(
            decodeReference(value.get("capability"), "provenance capability", CapabilityId::of),
            decodeReference(value.get("operation"), "provenance operation", OperationId::of));
    }

    private static ContractRef<ProviderId> decodeProvider(Object raw) {
        return decodeReference(raw, "provenance provider", ProviderId::of);
    }

    private static <T extends LocalId> ContractRef<T> decodeReference(
        Object raw, String label, Function<String, T> factory) {
        Map<String, Object> value = object(raw, label);
        requireFields(value, Set.of("ownerId", "localId"), label);
        return ContractRef.of(
            OwnerId.of(text(value.get("ownerId"), label + " owner")),
            factory.apply(text(value.get("localId"), label + " id")));
    }

    private static String keyText(Key key) {
        return key.provider().canonicalText() + "|" + key.planFingerprint().canonicalText() + "|"
            + key.binding().canonical() + "|" + key.executionFingerprint().canonicalText() + "|"
            + key.authorityIdentity() + "|" + key.idempotencyKey() + "|" + key.principalReference()
            + "|" + key.idempotencyKind().name();
    }

    private static long nextGeneration(long current) {
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("Runtime Receipt Generation Exhausted");
        }
        return current + 1;
    }

    private static boolean isAtomicTempSibling(Path parent, String targetName, Path candidate) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        return normalizedCandidate.getParent() != null
            && normalizedCandidate.getParent().equals(normalizedParent)
            && isAtomicTempName(normalizedCandidate.getFileName().toString(), targetName);
    }

    private static void writeAtomic(Path target, byte[] bytes, boolean replaceExisting) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "receipt file");
        byte[] content = Objects.requireNonNull(bytes, "bytes");
        requireDocumentSize(content.length, "Runtime Receipt Document");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Runtime Receipt File Has No Parent");
        }
        ensureParent(normalizedTarget);
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(normalizedTarget, "Runtime Receipt File");
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(normalizedTarget.toString());
            }
        } else if (!Files.notExists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt File Could Not Be Inspected: " + normalizedTarget);
        }
        Path temporary = createAtomicTemp(parent, normalizedTarget.getFileName().toString(), content);
        try {
            verifyBytes(temporary, content, "Runtime Receipt Atomic Temp");
            if (replaceExisting) {
                Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            }
            requireRegularFile(normalizedTarget, "Runtime Receipt File");
            verifyBytes(normalizedTarget, content, "Runtime Receipt File");
            StorageSafety.forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Runtime Receipt Atomic Publication Failed: " + normalizedTarget, exception);
        }
    }

    private static Path createAtomicTemp(Path parent, String targetName, byte[] content) throws IOException {
        Path normalizedParent = requireDirectory(parent, "Runtime Receipt Parent");
        requireDocumentSize(content.length, "Runtime Receipt Atomic Temp");
        for (int attempt = 0; attempt < 128; attempt++) {
            Path temporary = normalizedParent.resolve(targetName + "-" + UUID.randomUUID() + ATOMIC_TEMP_SUFFIX)
                .toAbsolutePath().normalize();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    int before = buffer.position();
                    int written = channel.write(buffer);
                    if (written <= 0 || buffer.position() == before) {
                        throw new IOException("Runtime Receipt Atomic Temp Write Made No Progress");
                    }
                }
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            requireAtomicTempFile(temporary);
            return temporary;
        }
        throw new IOException("Unable To Reserve A Runtime Receipt Atomic Temp");
    }

    private static FileLockHandle acquireFileLock(Path target) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "receipt file");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Runtime Receipt File Has No Parent");
        }
        ensureParent(normalizedTarget);
        String name = normalizedTarget.getFileName().toString();
        Path lockPath = parent.resolve(lockFileName(name)).toAbsolutePath().normalize();
        if (!lockPath.getParent().equals(parent)
            || lockPath.equals(parent)) {
            throw new IOException("Runtime Receipt Lock File Escaped Its Parent");
        }
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(lockPath, "Runtime Receipt Lock File");
        } else if (!Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt Lock File Could Not Be Inspected: " + lockPath);
        }
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        processLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.lock();
            return new FileLockHandle(processLock, channel, lock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            processLock.unlock();
            throw exception;
        }
    }

    private static String lockFileName(String targetName) {
        return targetName + LOCK_SUFFIX;
    }

    private static boolean recoverAtomicTemps(Path root, String targetName) throws IOException {
        Path normalizedRoot = requireDirectory(root, "Runtime Receipt Root");
        Path target = normalizedRoot.resolve(targetName).toAbsolutePath().normalize();
        if (!target.getParent().equals(normalizedRoot)) {
            throw new IOException("Runtime Receipt Target Escaped Its Root");
        }
        List<Path> temporaryFiles = new ArrayList<>();
        try (var children = Files.list(normalizedRoot)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.equals(targetName)) {
                    requireRegularFile(child, "Runtime Receipt File");
                } else if (name.equals(lockFileName(targetName))) {
                    requireRegularFile(child, "Runtime Receipt Lock File");
                } else if (name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                } else if (isAtomicTempName(name, targetName)) {
                    requireAtomicTempFile(child);
                    temporaryFiles.add(child);
                } else if (name.startsWith(targetName)) {
                    throw new IOException("Runtime Receipt Root Contains An Unknown Target Entry: " + child);
                } else {
                    continue;
                }
            }
        }
        validateQuarantine(normalizedRoot, targetName);
        if (temporaryFiles.isEmpty()) {
            return false;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Path temporary : temporaryFiles) {
            requireAtomicTempFile(temporary);
            byte[] bytes = readBytesCapped(temporary, "Runtime Receipt Atomic Temp");
            LoadedDocument document = readDocument(temporary);
            verifyBytes(temporary, bytes, "Runtime Receipt Atomic Temp");
            if (!document.documentHash().equals(hex(sha256(bytes)))) {
                throw new IOException("Runtime Receipt Atomic Temp Checksum Changed: " + temporary);
            }
            candidates.add(new Candidate(temporary, document, bytes));
        }
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            promoteAbsentTarget(normalizedRoot, target, targetName, candidates);
            return true;
        }
        LoadedDocument targetDocument = readDocument(target);
        List<Candidate> newer = candidates.stream()
            .filter(candidate -> candidate.document().generation() > targetDocument.generation())
            .toList();
        List<Candidate> conflicting = candidates.stream()
            .filter(candidate -> candidate.document().generation() == targetDocument.generation()
                && !candidate.contentHash().equals(targetDocument.contentHash()))
            .toList();
        if (!conflicting.isEmpty()) {
            throw new IOException("Runtime Receipt Atomic Temps Conflict With The Active Generation");
        }
        if (newer.isEmpty()) {
            quarantineCandidates(normalizedRoot, targetName, candidates);
            return false;
        }
        Candidate selected = selectUnambiguousNewer(newer);
        List<Candidate> evidence = candidates.stream()
            .filter(candidate -> candidate != selected)
            .toList();
        validateQuarantineDestinations(normalizedRoot, targetName, evidence);
        promoteCandidate(selected, target, true);
        quarantineCandidates(normalizedRoot, targetName, evidence);
        return true;
    }

    private static void promoteAbsentTarget(Path root, Path target, String targetName,
                                             List<Candidate> candidates) throws IOException {
        Set<String> contentHashes = candidates.stream().map(Candidate::contentHash).collect(java.util.stream.Collectors.toSet());
        if (contentHashes.size() != 1) {
            throw new IOException("Runtime Receipt Atomic Temps Contain Conflicting Crash Candidates");
        }
        Candidate selected = candidates.stream()
            .max(Comparator.comparingLong((Candidate candidate) -> candidate.document().generation())
                .thenComparing(candidate -> candidate.path().getFileName().toString()))
            .orElseThrow(() -> new IOException("Runtime Receipt Crash Candidate Is Missing"));
        List<Candidate> evidence = candidates.stream()
            .filter(candidate -> candidate != selected)
            .toList();
        validateQuarantineDestinations(root, targetName, evidence);
        promoteCandidate(selected, target, false);
        quarantineCandidates(root, targetName, evidence);
    }

    private static Candidate selectUnambiguousNewer(List<Candidate> candidates) throws IOException {
        Set<String> contentHashes = candidates.stream().map(Candidate::contentHash).collect(java.util.stream.Collectors.toSet());
        if (contentHashes.size() != 1) {
            throw new IOException("Runtime Receipt Atomic Temps Contain Multiple Newer Candidates");
        }
        return candidates.stream()
            .max(Comparator.comparingLong((Candidate candidate) -> candidate.document().generation())
                .thenComparing(candidate -> candidate.path().getFileName().toString()))
            .orElseThrow(() -> new IOException("Runtime Receipt Newer Candidate Is Missing"));
    }

    private static void promoteCandidate(Candidate candidate, Path target, boolean replaceExisting) throws IOException {
        requireAtomicTempFile(candidate.path());
        verifyBytes(candidate.path(), candidate.bytes(), "Runtime Receipt Atomic Temp");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target, "Runtime Receipt File");
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
        } else if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt File Could Not Be Inspected: " + target);
        }
        try {
            if (replaceExisting) {
                Files.move(candidate.path(), target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(candidate.path(), target, StandardCopyOption.ATOMIC_MOVE);
            }
            requireRegularFile(target, "Runtime Receipt File");
            verifyBytes(target, candidate.bytes(), "Runtime Receipt Recovered File");
            StorageSafety.forceDirectory(target.getParent());
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Runtime Receipt Atomic Recovery Publication Failed: " + target, exception);
        }
    }

    private static void validateQuarantineDestinations(Path root, String targetName,
                                                        List<Candidate> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
        }
        requireDirectory(container, "Runtime Receipt Quarantine Container");
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
        }
        requireDirectory(quarantine, "Runtime Receipt Quarantine");
        for (Candidate candidate : candidates) {
            Path source = candidate.path();
            Path destination = quarantine.resolve(source.getFileName().toString()).toAbsolutePath().normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Runtime Receipt Quarantine Target Collides: " + destination);
            }
        }
    }

    private static void quarantineCandidates(Path root, String targetName,
                                             List<Candidate> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        validateQuarantineDestinations(root, targetName, candidates);
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        List<Candidate> ordered = candidates.stream()
            .sorted(Comparator.comparing(candidate -> candidate.path().getFileName().toString()))
            .toList();
        for (Candidate candidate : ordered) {
            requireAtomicTempFile(candidate.path());
            Path destination = quarantine.resolve(candidate.path().getFileName().toString())
                .toAbsolutePath().normalize();
            Files.move(candidate.path(), destination, StandardCopyOption.ATOMIC_MOVE);
            requireAtomicTempFile(destination);
            verifyBytes(destination, candidate.bytes(), "Runtime Receipt Quarantine Evidence");
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(container);
        StorageSafety.forceDirectory(root);
        validateQuarantine(root, targetName);
    }

    private static void validateQuarantine(Path root, String targetName) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectory(container, "Runtime Receipt Quarantine Container");
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectory(quarantine, "Runtime Receipt Quarantine");
        try (var entries = Files.list(quarantine)) {
            for (Path child : entries.toList()) {
                if (!isAtomicTempName(child.getFileName().toString(), targetName)) {
                    throw new IOException("Runtime Receipt Quarantine Contains An Unknown Entry: " + child);
                }
                requireAtomicTempFile(child);
            }
        }
    }

    private static void requireAtomicTempFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt Atomic Temp Must Be A Regular Non-Symbolic-Link File: " + file);
        }
    }

    private static boolean isAtomicTempName(String name, String targetName) {
        if (name == null || !name.startsWith(targetName) || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return false;
        }
        String token = name.substring(targetName.length(), name.length() - ATOMIC_TEMP_SUFFIX.length());
        return isSafeGeneratedToken(token);
    }

    private static boolean isSafeGeneratedToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z') && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static void requireRegularFile(Path file, String label) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " Must Be A Regular Non-Symbolic-Link File: " + file);
        }
    }

    private static void verifyBytes(Path file, byte[] expected, String label) throws IOException {
        byte[] actual = readBytesCapped(file, label);
        if (actual.length != expected.length || !Arrays.equals(sha256(actual), sha256(expected))) {
            throw new IOException(label + " Content Verification Failed: " + file);
        }
    }

    private static byte[] readBytesCapped(Path file, String label) throws IOException {
        long size = Files.size(file);
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IOException(label + " Exceeds The Maximum Supported Size: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IOException(label + " Exceeds The Maximum Supported Size: " + file);
        }
        return bytes;
    }

    private static void requireDocumentSize(long size, String label) throws IOException {
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IOException(label + " Exceeds The Maximum Supported Size");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte element : value) {
            result.append(String.format(Locale.ROOT, "%02x", element & 0xff));
        }
        return result.toString();
    }

    private static void ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("Runtime Receipt File Has No Parent");
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt Parent Must Be A Non-Symbolic-Link Directory");
        }
    }

    private static void requireExisting(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime Receipt File Is Missing: " + file);
        }
    }

    private static Path requireDirectory(Path path, String label) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, label);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " Must Be A Non-Symbolic-Link Directory");
        }
        return normalized;
    }

    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(label + " keys must be strings");
            }
            result.put(text, value);
        });
        return result;
    }

    private static void requireFields(Map<String, Object> value, Set<String> known, String label) {
        if (!value.keySet().equals(known) && !known.containsAll(value.keySet())) {
            throw new IllegalArgumentException(label + " contains unknown fields");
        }
    }

    private static void requireField(Map<String, Object> value, String field, String label) {
        if (!value.containsKey(field) || value.get(field) == null) {
            throw new IllegalArgumentException(label + " is missing " + field);
        }
    }

    private static String text(Object raw, String label) {
        if (!(raw instanceof String value) || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(label + " must be canonical text");
        }
        return value;
    }

    private static String optionalText(Object raw, String label) {
        if (!(raw instanceof String value) || !value.equals(value.strip())) {
            throw new IllegalArgumentException(label + " must be canonical text");
        }
        return value;
    }

    private static boolean booleanValue(Object raw, String label) {
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(label + " must be boolean");
        }
        return value;
    }

    private static long integer(Object raw, String label) {
        if (raw instanceof Number number) {
            try {
                return new java.math.BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(label + " must be an integer", exception);
            }
        }
        throw new IllegalArgumentException(label + " must be an integer");
    }

    private static <E extends Enum<E>> E enumValue(Object raw, E[] values, String label) {
        String value = text(raw, label);
        for (E candidate : values) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown " + label + ": " + value);
    }

    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private enum State {
        RESERVED,
        COMPLETED
    }

    private enum AuditState {
        NONE,
        PENDING,
        RECORDED
    }

    private record LoadedDocument(Map<Key, Entry> entries, long generation,
                                  String contentHash, String documentHash) {
    }

    private record Candidate(Path path, LoadedDocument document, byte[] bytes) {
        private String contentHash() {
            return document.contentHash();
        }
    }

    private static final class FileLockHandle implements AutoCloseable {
        private final ReentrantLock processLock;
        private final FileChannel channel;
        private final FileLock lock;

        private FileLockHandle(ReentrantLock processLock, FileChannel channel, FileLock lock) {
            this.processLock = processLock;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            } finally {
                processLock.unlock();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record Delivery(Key key, RuntimeLeaseInput.AuditEvent event) {
    }

    private static final class Entry {
        private final ContentHash inputHash;
        private final CompletableFuture<RuntimeResult> outcome = new CompletableFuture<>();
        private RuntimeResult outcomeValue;
        private State state = State.RESERVED;
        private AuditState auditState = AuditState.NONE;
        private RuntimeLeaseInput.AuditEvent audit;
        private RuntimeExecutionProvenance provenance;
        private String failure = "";

        private Entry(ContentHash inputHash) {
            this.inputHash = Objects.requireNonNull(inputHash, "Input Hash Is Required");
        }

        private RuntimeResult outcome() {
            return outcomeValue;
        }
    }

    private final class InvocationPermit implements RuntimeReceiptStore.InvocationLease {
        private boolean closed;

        @Override
        public void close() {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            releaseInvocationLease();
        }
    }
}
