package restudio.resync.storage;

import com.google.gson.Gson;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AssetTransactionManager {
    private static final int JOURNAL_VERSION = 3;
    private static final int LEGACY_JOURNAL_VERSION = 2;
    private static final String DESCRIPTOR_VERSION = "asset-transaction-descriptor-v1";
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private static final String WRITE_TEXT = "WRITE_TEXT";
    private static final String WRITE_BINARY = "WRITE_BINARY";
    private static final String DELETE = "DELETE";
    private static final long RETAINED_EVIDENCE_FILE_LIMIT = 32L * 1024L * 1024L;
    private static final long RETAINED_EVIDENCE_TOTAL_LIMIT = 128L * 1024L * 1024L;
    private final Path assetsRoot;
    private final Path transactionRoot;
    private final Path snapshotRoot;
    private final Gson gson;
    private final RecoveryMode recoveryMode;
    private final ContentReadObserver contentReadObserver;
    private final Map<String, IndexedCommit> committedTransactions = new LinkedHashMap<>();
    private boolean commitIndexTrusted;
    private int recoveredTransactions;
    private long fullJournalPasses;
    private long journalReads;
    private long stagedPayloadReads;
    private long indexedMutationLookups;
    private long assetPathCanonicalizations;
    private long commitAttemptCount;
    private long commitNanos;
    private long prepareAttemptCount;
    private long prepareNanos;
    private long applyAttemptCount;
    private long applyNanos;
    private long journalDurabilityAttemptCount;
    private long journalDurabilityNanos;
    private long residualNanos;
    private boolean prepareActive;
    private long prepareStarted;

    public AssetTransactionManager(Path assetsRoot, Gson gson) throws IOException {
        this(assetsRoot, gson, RecoveryMode.AUTOMATIC);
    }

    public AssetTransactionManager(Path assetsRoot, Gson gson, RecoveryMode recoveryMode) throws IOException {
        this(assetsRoot, gson, recoveryMode, null);
    }

    AssetTransactionManager(Path assetsRoot, Gson gson, RecoveryMode recoveryMode,
                            ContentReadObserver contentReadObserver) throws IOException {
        Path requestedAssetsRoot;
        try {
            requestedAssetsRoot = MigrationPaths.requirePath(assetsRoot, "assetsRoot");
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unsafe asset root: " + assetsRoot, failure);
        }
        Files.createDirectories(requestedAssetsRoot);
        this.assetsRoot = MigrationPaths.requireDirectory(requestedAssetsRoot, "assetsRoot").toRealPath();
        this.transactionRoot = this.assetsRoot.resolve(".transactions");
        this.snapshotRoot = this.assetsRoot.resolve(".snapshots");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.recoveryMode = Objects.requireNonNull(recoveryMode, "recoveryMode");
        this.contentReadObserver = contentReadObserver;
        ensureInternalRoots();
        if (recoveryMode == RecoveryMode.AUTOMATIC) {
            recover();
        }
    }

    public synchronized String commit(Map<Path, String> writes, String mutationId) throws IOException {
        return commit(writes, Set.of(), mutationId);
    }

    public synchronized String commit(Map<Path, String> writes, Set<Path> deletes, String mutationId) throws IOException {
        return commit(writes, Map.of(), deletes, mutationId);
    }

    public synchronized String commit(Map<Path, String> writes, Map<Path, byte[]> binaryWrites,
                                      Set<Path> deletes, String mutationId) throws IOException {
        return commitInspected(writes, binaryWrites, deletes, mutationId).transactionId();
    }

    public synchronized CommitResult commitInspected(Map<Path, String> writes, Map<Path, byte[]> binaryWrites,
                                                      Set<Path> deletes, String mutationId) throws IOException {
        long started = System.nanoTime();
        long accountedBefore = accountedNanos();
        try {
            return commitInspectedNow(writes, binaryWrites, deletes, mutationId);
        } finally {
            finishPrepare();
            long elapsed = elapsedNanos(started);
            long accounted = accountedNanos() - accountedBefore;
            commitAttemptCount++;
            commitNanos += elapsed;
            residualNanos += Math.max(0L, elapsed - accounted);
        }
    }

    private CommitResult commitInspectedNow(Map<Path, String> writes, Map<Path, byte[]> binaryWrites,
                                             Set<Path> deletes, String mutationId) throws IOException {
        beginPrepare();
        Map<Path, String> safeWrites = writes != null ? writes : Map.of();
        Map<Path, byte[]> safeBinaryWrites = binaryWrites != null ? binaryWrites : Map.of();
        Set<Path> safeDeletes = deletes != null ? deletes : Set.of();
        if (safeWrites.isEmpty() && safeBinaryWrites.isEmpty() && safeDeletes.isEmpty()) {
            return new CommitResult("", null, false);
        }
        NormalizedTransaction normalized = normalize(safeWrites, safeBinaryWrites, safeDeletes);
        recoverIfAutomatic();
        String safeMutationId = mutationId == null ? "" : mutationId;
        IndexedCommit indexed = indexedCommit(safeMutationId, normalized.descriptor().fingerprint());
        if (indexed != null) {
            return new CommitResult(indexed.transactionId(), inspectTransaction(indexed.transactionId()), true);
        }
        String transactionId = UUID.randomUUID().toString();
        Path transactionDir = transactionRoot.resolve(transactionId);
        StorageSafety.createDirectoriesNoSymlinks(transactionRoot, transactionDir);
        boolean preparedWritten = false;
        try {
            List<Entry> entries = new ArrayList<>();
            int index = 0;
            for (PendingOperation operation : normalized.operations()) {
                String stagedName = operation.operation().equals(WRITE_BINARY)
                    ? "content-" + index++ + ".bin"
                    : "content-" + index++ + ".json";
                if (operation.operation().equals(DELETE)) {
                    entries.add(new Entry(operation.resource(), "", "", true,
                        Files.isRegularFile(operation.target()), DELETE, 0L));
                    continue;
                }
                StorageSafety.writeBytesAtomic(transactionDir.resolve(stagedName), operation.content());
                entries.add(new Entry(operation.resource(), stagedName, StorageSafety.sha256(operation.content()), false,
                    Files.isRegularFile(operation.target()), operation.operation(), (long) operation.content().length));
            }
            Journal prepared = journal(transactionId, safeMutationId, PREPARED, entries, normalized.descriptor().fingerprint());
            finishPrepare();
            writeJournal(transactionDir.resolve("journal.json"), gson.toJson(prepared));
            preparedWritten = true;
            applyMeasured(transactionDir, prepared);
            Journal committed = journal(prepared.id(), prepared.mutationId(), COMMITTED, prepared.entries(),
                prepared.fingerprint());
            writeJournal(transactionDir.resolve("journal.json"), gson.toJson(committed));
            TransactionInspection inspection = inspection(committed, normalized.operations());
            indexCommitted(committed);
            return new CommitResult(transactionId, inspection, false);
        } catch (IOException | RuntimeException failure) {
            if (!preparedWritten) {
                try {
                    discardUnpreparedTransaction(transactionDir);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    public synchronized TransactionDescriptor descriptor(Map<Path, String> writes, Set<Path> deletes)
        throws IOException {
        return descriptor(writes, Map.of(), deletes);
    }

    public synchronized TransactionDescriptor descriptor(Map<Path, String> writes, Map<Path, byte[]> binaryWrites,
                                                          Set<Path> deletes) throws IOException {
        Map<Path, String> safeWrites = writes != null ? writes : Map.of();
        Map<Path, byte[]> safeBinaryWrites = binaryWrites != null ? binaryWrites : Map.of();
        Set<Path> safeDeletes = deletes != null ? deletes : Set.of();
        return normalize(safeWrites, safeBinaryWrites, safeDeletes).descriptor();
    }

    synchronized TransactionDescriptor descriptorFromInspection(List<TransactionOperation> operations)
        throws IOException {
        if (operations == null || operations.isEmpty()) {
            throw new IOException("Asset transaction descriptor has no operations");
        }
        List<TransactionOperation> normalized = new ArrayList<>();
        Set<String> resources = new LinkedHashSet<>();
        try {
            for (TransactionOperation operation : operations) {
                if (operation == null || operation.resource().isBlank() || operation.resource().indexOf('\\') >= 0) {
                    throw new IOException("Asset transaction descriptor has an invalid resource");
                }
                Path relative = Path.of(operation.resource());
                Path canonical = relative.normalize();
                String resource = canonical.toString().replace('\\', '/');
                Path target = assetsRoot.resolve(canonical).normalize();
                if (relative.isAbsolute() || resource.isBlank() || !resource.equals(operation.resource())
                    || !target.startsWith(assetsRoot) || target.equals(assetsRoot)
                    || target.startsWith(transactionRoot) || target.startsWith(snapshotRoot)
                    || !resources.add(resource)) {
                    throw new IOException("Asset transaction descriptor has an unsafe resource: " + operation.resource());
                }
                if (DELETE.equals(operation.operation())) {
                    if (!operation.payloadHash().isEmpty() || operation.payloadSize() != 0L) {
                        throw new IOException("Asset transaction descriptor has an invalid delete: " + resource);
                    }
                } else if ((!WRITE_TEXT.equals(operation.operation()) && !WRITE_BINARY.equals(operation.operation()))
                    || !operation.payloadHash().matches("[0-9a-f]{64}")) {
                    throw new IOException("Asset transaction descriptor has an invalid write: " + resource);
                }
                normalized.add(new TransactionOperation(resource, operation.operation(), operation.payloadHash(),
                    operation.payloadSize()));
            }
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Asset transaction descriptor is corrupt", failure);
        }
        normalized.sort(Comparator.comparing(TransactionOperation::resource)
            .thenComparing(TransactionOperation::operation));
        List<TransactionOperation> result = List.copyOf(normalized);
        return new TransactionDescriptor(DESCRIPTOR_VERSION, fingerprint(result), result);
    }

    public synchronized int recover() throws IOException {
        return recoverValidated(null);
    }

    public synchronized int recoverValidated(Map<String, String> preparedFingerprints) throws IOException {
        ensureInternalRoots();
        if (!Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        fullJournalPasses++;
        List<PendingJournal> journals = new ArrayList<>();
        Map<String, IndexedCommit> recoveredIndex = new LinkedHashMap<>();
        for (Path transactionDir : transactionDirectories().stream()
            .sorted(Comparator.comparingLong(this::journalModified).thenComparing(Path::toString)).toList()) {
            Path journalFile = transactionDir.resolve("journal.json");
            journals.add(new PendingJournal(transactionDir, journalFile,
                readJournal(journalFile, preparedFingerprints == null)));
        }
        if (preparedFingerprints != null) {
            Map<String, String> actual = new LinkedHashMap<>();
            for (PendingJournal pending : journals) {
                if (PREPARED.equals(pending.journal().state())) {
                    actual.put(pending.journal().id(), pending.journal().fingerprint());
                }
            }
            if (!actual.equals(Map.copyOf(preparedFingerprints))) {
                throw new IOException("Prepared asset transactions changed after validation");
            }
        }
        int recovered = 0;
        for (PendingJournal pending : journals) {
            Journal journal = pending.journal();
            if (COMMITTED.equals(journal.state())) {
                addIndexedCommit(recoveredIndex, journal);
                continue;
            }
            if (journal.version() != JOURNAL_VERSION) {
                throw new IOException("Unsupported prepared asset transaction version: " + journal.version());
            }
            if (!PREPARED.equals(journal.state())) {
                throw new IOException("Unknown asset transaction state: " + journal.state());
            }
            apply(pending.transactionDir(), journal);
            StorageSafety.writeUtf8Atomic(pending.journalFile(), gson.toJson(journal(journal.id(), journal.mutationId(),
                COMMITTED, journal.entries(), journal.fingerprint())));
            addIndexedCommit(recoveredIndex, journal(journal.id(), journal.mutationId(), COMMITTED, journal.entries(),
                journal.fingerprint()));
            recovered++;
        }
        committedTransactions.clear();
        committedTransactions.putAll(recoveredIndex);
        commitIndexTrusted = true;
        recoveredTransactions += recovered;
        return recovered;
    }

    synchronized int recoverValidatedInspections(List<TransactionInspection> inspections,
                                                 Map<String, String> preparedFingerprints,
                                                 RecoveryGuard recoveryGuard) throws IOException {
        Objects.requireNonNull(inspections, "inspections");
        Objects.requireNonNull(preparedFingerprints, "preparedFingerprints");
        Objects.requireNonNull(recoveryGuard, "recoveryGuard");
        Map<String, String> actual = new LinkedHashMap<>();
        List<FrozenInspection> frozenInspections = new ArrayList<>();
        for (TransactionInspection inspection : inspections) {
            if (PREPARED.equals(inspection.visibility().state())) {
                actual.put(inspection.visibility().transactionId(), inspection.visibility().descriptor().fingerprint());
            }
            validateFrozenInspection(inspection);
            Journal frozen = frozenJournal(inspection);
            validateFrozenPayloads(inspection, frozen);
            frozenInspections.add(new FrozenInspection(inspection, frozen));
        }
        if (!actual.equals(Map.copyOf(preparedFingerprints))) {
            throw new IOException("Prepared asset transactions changed after validation");
        }
        RecoveryEvidence recoveryEvidence = recoveryGuard.validate();
        validateRecoveryFiles(frozenInspections, recoveryEvidence);
        int recovered = 0;
        Map<String, IndexedCommit> recoveredIndex = new LinkedHashMap<>();
        List<FrozenInspection> ordered = frozenInspections.stream().sorted(Comparator
            .comparing((FrozenInspection item) -> item.inspection().journalEvidence().journalIdentity().modifiedAt())
            .thenComparing(item -> item.inspection().journalEvidence().transactionDirectory().toString())).toList();
        for (FrozenInspection item : ordered) {
            TransactionInspection inspection = item.inspection();
            Journal frozen = item.journal();
            if (COMMITTED.equals(frozen.state())) {
                addIndexedCommit(recoveredIndex, frozen);
                continue;
            }
            if (!PREPARED.equals(frozen.state())) {
                throw new IOException("Unknown asset transaction state: " + frozen.state());
            }
            applyFrozen(inspection.journalEvidence().transactionDirectory(), frozen, inspection.stagedWritesView(),
                recoveryEvidence);
            Journal committed = journal(frozen.id(), frozen.mutationId(), COMMITTED, frozen.entries(), frozen.fingerprint());
            StorageSafety.writeUtf8Atomic(inspection.journalEvidence().journalPath(), gson.toJson(committed));
            addIndexedCommit(recoveredIndex, committed);
            recovered++;
        }
        committedTransactions.clear();
        committedTransactions.putAll(recoveredIndex);
        commitIndexTrusted = true;
        recoveredTransactions += recovered;
        return recovered;
    }

    private void validateFrozenPayloads(TransactionInspection inspection, Journal journal) throws IOException {
        if (!PREPARED.equals(journal.state())) {
            return;
        }
        Map<String, byte[]> stagedWrites = inspection.stagedWritesView();
        for (Entry entry : journal.entries()) {
            if (entry.existed() == null) {
                throw new IOException("Prepared asset transaction has no exact pre-state: " + entry.target());
            }
            if (entry.delete()) {
                continue;
            }
            byte[] content = stagedWrites.get(entry.target());
            if (content == null || entry.size() != content.length
                || !entry.hash().equals(StorageSafety.sha256(content))) {
                throw new IOException("Frozen staged asset transaction content is unavailable: " + entry.target());
            }
        }
    }

    private void validateRecoveryFiles(List<FrozenInspection> inspections, RecoveryEvidence recoveryEvidence)
        throws IOException {
        Map<Path, byte[]> recoveryFiles = recoveryEvidence.filesView();
        Set<Path> snapshotsToCreate = recoveryEvidence.snapshotsToCreate();
        Set<Path> expectedSnapshots = new LinkedHashSet<>();
        for (FrozenInspection item : inspections) {
            if (!PREPARED.equals(item.journal().state())) {
                continue;
            }
            for (Entry entry : item.journal().entries()) {
                if (!Boolean.TRUE.equals(entry.existed())) {
                    continue;
                }
                Path target = requireAssetPath(assetsRoot.resolve(entry.target())).toAbsolutePath().normalize();
                if (!recoveryFiles.containsKey(target)) {
                    throw new IOException("Frozen target evidence is unavailable: " + entry.target());
                }
                Path snapshot = snapshotRoot.resolve(item.journal().id()).resolve(entry.target()).toAbsolutePath().normalize();
                if (snapshotsToCreate.contains(snapshot)) {
                    expectedSnapshots.add(snapshot);
                }
            }
        }
        if (!expectedSnapshots.equals(snapshotsToCreate)) {
            throw new IOException("Frozen snapshot creation evidence does not match prepared transactions");
        }
    }

    synchronized void validateFrozenControlEvidence(List<TransactionInspection> inspections) throws IOException {
        for (TransactionInspection inspection : inspections) {
            JournalEvidence evidence = Objects.requireNonNull(inspection.journalEvidence(), "journalEvidence");
            validateContentIdentity(evidence.journalPath(), evidence.journalIdentity());
            for (JournalEntryEvidence entry : evidence.entries()) {
                if (!entry.delete() && controlResource(entry.target())) {
                    if (entry.stagedIdentity() == null) {
                        throw new IOException("Frozen control entry has no staged identity: " + entry.target());
                    }
                    validateContentIdentity(evidence.transactionDirectory().resolve(entry.staged()).normalize(),
                        entry.stagedIdentity());
                }
            }
        }
    }

    private void validateFrozenInspection(TransactionInspection inspection) throws IOException {
        JournalEvidence evidence = Objects.requireNonNull(inspection.journalEvidence(), "journalEvidence");
        validateContentIdentity(evidence.journalPath(), evidence.journalIdentity());
        for (JournalEntryEvidence entry : evidence.entries()) {
            if (!PREPARED.equals(inspection.visibility().state()) && !controlResource(entry.target())) {
                continue;
            }
            if (entry.delete()) {
                if (entry.stagedIdentity() != null) {
                    throw new IOException("Deleted frozen journal entry has staged evidence: " + entry.target());
                }
                continue;
            }
            if (entry.stagedIdentity() == null) {
                throw new IOException("Frozen journal entry has no staged identity: " + entry.target());
            }
            Path staged = evidence.transactionDirectory().resolve(entry.staged()).normalize();
            if (!staged.startsWith(evidence.transactionDirectory())
                || !evidence.transactionDirectory().equals(staged.getParent())) {
                throw new IOException("Frozen journal staged path is unsafe: " + entry.staged());
            }
            validateContentIdentity(staged, entry.stagedIdentity());
        }
    }

    private void validateContentIdentity(Path path, ContentIdentity expected) throws IOException {
        CapturedBytes captured = captureBytes(path, true);
        observeContentRead(path);
        if (!expected.equals(captured.identity())) {
            throw new IOException("Asset transaction evidence changed after validation: " + path);
        }
    }

    private Journal frozenJournal(TransactionInspection inspection) throws IOException {
        TransactionVisibility visibility = inspection.visibility();
        JournalEvidence evidence = Objects.requireNonNull(inspection.journalEvidence(), "journalEvidence");
        if (!visibility.transactionId().equals(evidence.transactionDirectory().getFileName().toString())) {
            throw new IOException("Frozen asset transaction identity is inconsistent: " + visibility.transactionId());
        }
        List<Entry> entries = evidence.entries().stream().map(entry -> new Entry(entry.target(), entry.staged(),
            entry.hash(), entry.delete(), entry.existed(), entry.operation(), entry.size())).toList();
        if (PREPARED.equals(visibility.state()) && evidence.version() != JOURNAL_VERSION) {
            throw new IOException("Unsupported prepared asset transaction version: " + evidence.version());
        }
        Journal frozen = new Journal(evidence.version(), visibility.transactionId(), visibility.mutationId(),
            visibility.state(), entries, visibility.descriptor().fingerprint());
        if (!visibility(frozen).equals(visibility)) {
            throw new IOException("Frozen asset transaction descriptor is inconsistent: " + visibility.transactionId());
        }
        return frozen;
    }

    private void applyFrozen(Path transactionDir, Journal journal, Map<String, byte[]> stagedWrites,
                             RecoveryEvidence recoveryEvidence) throws IOException {
        Map<Path, byte[]> recoveryFiles = recoveryEvidence.filesView();
        Set<Path> snapshotsToCreate = recoveryEvidence.snapshotsToCreate();
        MigrationPaths.requireNoSymlinkTraversal(transactionRoot, transactionDir);
        Path transactionSnapshotRoot = snapshotRoot.resolve(journal.id()).normalize();
        if (!transactionSnapshotRoot.startsWith(snapshotRoot)) {
            throw new IOException("Unsafe asset transaction snapshot: " + journal.id());
        }
        StorageSafety.createDirectoriesNoSymlinks(snapshotRoot, transactionSnapshotRoot);
        for (Entry entry : journal.entries()) {
            Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
            Path snapshot = transactionSnapshotRoot.resolve(entry.target()).normalize();
            if (!snapshot.startsWith(transactionSnapshotRoot)) {
                throw new IOException("Unsafe asset transaction snapshot: " + entry.target());
            }
            if (entry.existed() == null) {
                throw new IOException("Prepared asset transaction has no exact pre-state: " + entry.target());
            }
            if (entry.existed() && snapshotsToCreate.contains(snapshot.toAbsolutePath().normalize())) {
                byte[] prior = recoveryFiles.get(target.toAbsolutePath().normalize());
                if (prior == null) {
                    throw new IOException("Frozen target evidence is unavailable: " + entry.target());
                }
                StorageSafety.writeBytesAtomic(snapshot, prior);
            }
            if (entry.delete()) {
                StorageSafety.deleteIfExists(target);
                continue;
            }
            byte[] content = stagedWrites.get(entry.target());
            if (content == null || entry.size() != content.length
                || !entry.hash().equals(StorageSafety.sha256(content))) {
                throw new IOException("Frozen staged asset transaction content is unavailable: " + entry.target());
            }
            StorageSafety.writeBytesAtomic(target, content);
        }
    }

    public synchronized List<TransactionInspection> inspectTransactions() throws IOException {
        ensureInternalRoots();
        fullJournalPasses++;
        List<TransactionInspection> inspections = new ArrayList<>();
        Map<String, IndexedCommit> inspectedIndex = new LinkedHashMap<>();
        RetentionBudget budget = new RetentionBudget();
        for (Path transactionDir : transactionDirectories().stream().sorted().toList()) {
            Path journalFile = transactionDir.resolve("journal.json");
            Map<String, byte[]> stagedWrites = new LinkedHashMap<>();
            JournalCapture capture = new JournalCapture();
            Journal journal = readJournal(journalFile, false, stagedWrites, capture, budget, true);
            inspections.add(new TransactionInspection(visibility(journal), stagedWrites,
                journalEvidence(transactionDir, journal, capture)));
            if (COMMITTED.equals(journal.state())) {
                addIndexedCommit(inspectedIndex, journal);
            }
        }
        committedTransactions.clear();
        committedTransactions.putAll(inspectedIndex);
        commitIndexTrusted = true;
        return List.copyOf(inspections);
    }

    synchronized Map<String, CommittedIndexEntry> committedIndexSnapshot() throws IOException {
        if (!commitIndexTrusted) {
            throw new IOException("Asset transaction commit index is not trusted");
        }
        Map<String, CommittedIndexEntry> snapshot = new LinkedHashMap<>();
        committedTransactions.forEach((mutationId, entry) -> snapshot.put(mutationId,
            new CommittedIndexEntry(entry.transactionId(), entry.fingerprint())));
        return Map.copyOf(snapshot);
    }

    synchronized void trustCommittedIndex(Map<String, CommittedIndexEntry> trusted) throws IOException {
        Objects.requireNonNull(trusted, "Trusted asset transaction index is required");
        Map<String, IndexedCommit> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, CommittedIndexEntry> entry : trusted.entrySet()) {
            String mutationId = entry.getKey();
            CommittedIndexEntry indexed = entry.getValue();
            try {
                UUID.fromString(mutationId);
                UUID.fromString(indexed.transactionId());
            } catch (RuntimeException failure) {
                throw new IOException("Trusted asset transaction index has an invalid identity", failure);
            }
            if (!indexed.fingerprint().matches("[0-9a-f]{64}")) {
                throw new IOException("Trusted asset transaction index has an invalid fingerprint");
            }
            accepted.put(mutationId, new IndexedCommit(indexed.transactionId(), indexed.fingerprint()));
        }
        committedTransactions.clear();
        committedTransactions.putAll(accepted);
        commitIndexTrusted = true;
    }

    public synchronized TransactionInspection inspectTransaction(String transactionId) throws IOException {
        Journal journal = committedJournal(transactionId);
        Path transactionDir = requireTransactionRoot(transactionId);
        Map<String, byte[]> stagedWrites = new LinkedHashMap<>();
        for (Entry entry : journal.entries()) {
            if (!entry.delete()) {
                stagedPayloadReads++;
                Path staged = transactionDir.resolve(entry.staged());
                byte[] content = Files.readAllBytes(staged);
                observeContentRead(staged);
                stagedWrites.put(entry.target(), content);
            }
        }
        return new TransactionInspection(visibility(journal), stagedWrites,
            journalEvidence(transactionDir, journal));
    }

    public synchronized IoMetrics ioMetrics() {
        return new IoMetrics(fullJournalPasses, journalReads, stagedPayloadReads, indexedMutationLookups,
            assetPathCanonicalizations);
    }

    synchronized PhaseTiming phaseTiming() {
        return new PhaseTiming(commitAttemptCount, commitNanos, prepareAttemptCount, prepareNanos,
            applyAttemptCount, applyNanos, journalDurabilityAttemptCount, journalDurabilityNanos, residualNanos);
    }

    synchronized long timedCommitNanos() {
        return commitNanos;
    }

    private long accountedNanos() {
        return prepareNanos + applyNanos + journalDurabilityNanos;
    }

    private void beginPrepare() {
        prepareAttemptCount++;
        prepareActive = true;
        prepareStarted = System.nanoTime();
    }

    private void finishPrepare() {
        if (!prepareActive) {
            return;
        }
        prepareNanos += elapsedNanos(prepareStarted);
        prepareActive = false;
    }

    private static long elapsedNanos(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private List<Path> transactionDirectories() throws IOException {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.list(transactionRoot)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected content in asset transaction root: " + path);
                }
                Path journal = path.resolve("journal.json");
                if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Asset transaction journal is missing: " + path);
                }
                directories.add(path);
            }
        }
        return List.copyOf(directories);
    }

    public int getRecoveredTransactions() {
        return recoveredTransactions;
    }

    public Path assetsRoot() {
        return assetsRoot;
    }

    public synchronized TransactionVisibility readTransaction(String transactionId) throws IOException {
        recoverIfAutomatic();
        return visibility(committedJournal(transactionId));
    }

    public synchronized Optional<TransactionVisibility> findCommittedTransaction(String mutationId) throws IOException {
        recoverIfAutomatic();
        if (mutationId == null || mutationId.isBlank()) {
            return Optional.empty();
        }
        if (commitIndexTrusted) {
            indexedMutationLookups++;
            IndexedCommit indexed = committedTransactions.get(mutationId);
            return indexed == null ? Optional.empty() : Optional.of(readTransaction(indexed.transactionId()));
        }
        Journal found = null;
        fullJournalPasses++;
        try (Stream<Path> paths = Files.list(transactionRoot)) {
            for (Path transactionDir : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .sorted().toList()) {
                Path journalFile = transactionDir.resolve("journal.json");
                if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Journal journal = readJournalForAccess(journalFile);
                if (!COMMITTED.equals(journal.state()) || !mutationId.equals(journal.mutationId())) {
                    continue;
                }
                if (found != null && !found.fingerprint().equals(journal.fingerprint())) {
                    throw new IOException("Mutation ID has conflicting committed asset transactions: " + mutationId);
                }
                found = journal;
            }
        }
        return found == null ? Optional.empty() : Optional.of(visibility(found));
    }

    public synchronized RestorePreview previewRestore(String transactionId) throws IOException {
        Journal journal = committedJournal(transactionId);
        return new RestorePreview(transactionId, journal.entries().stream().map(Entry::target).sorted().toList());
    }

    public synchronized String restore(String transactionId, String mutationId) throws IOException {
        recoverIfAutomatic();
        Journal journal = committedJournal(transactionId);
        Path root = requireSnapshotRoot(transactionId);
        Map<Path, String> writes = new LinkedHashMap<>();
        Map<Path, byte[]> binaryWrites = new LinkedHashMap<>();
        Set<Path> deletes = new LinkedHashSet<>();
        for (Entry entry : journal.entries()) {
            Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
            Path snapshot = root.resolve(entry.target()).normalize();
            boolean existed = entry.existed() != null ? entry.existed() : Files.isRegularFile(snapshot);
            if (existed) {
                if (!snapshot.startsWith(root) || !Files.isRegularFile(snapshot)) {
                    throw new IOException("Asset snapshot is incomplete: " + entry.target());
                }
                byte[] content = Files.readAllBytes(snapshot);
                if (WRITE_TEXT.equals(entry.operation())) {
                    writes.put(target, new String(content, StandardCharsets.UTF_8));
                } else {
                    binaryWrites.put(target, content);
                }
            } else {
                deletes.add(target);
            }
        }
        return commit(writes, binaryWrites, deletes, mutationId);
    }

    private NormalizedTransaction normalize(Map<Path, String> writes, Map<Path, byte[]> binaryWrites,
                                             Set<Path> deletes) throws IOException {
        List<PendingOperation> operations = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        Set<Path> targetPaths = new LinkedHashSet<>();
        for (Map.Entry<Path, String> write : writes.entrySet()) {
            Path target = requireAssetPath(write.getKey());
            String resource = resource(target);
            if (!targets.add(resource) || !targetPaths.add(target)) {
                throw new IOException("Asset transaction contains duplicate write targets: " + write.getKey());
            }
            byte[] content = (write.getValue() == null ? "" : write.getValue()).getBytes(StandardCharsets.UTF_8);
            operations.add(new PendingOperation(target, resource, WRITE_TEXT, content));
        }
        for (Map.Entry<Path, byte[]> write : binaryWrites.entrySet()) {
            Path target = requireAssetPath(write.getKey());
            String resource = resource(target);
            if (!targets.add(resource) || !targetPaths.add(target)) {
                throw new IOException("Asset transaction contains duplicate write targets: " + write.getKey());
            }
            byte[] source = write.getValue() != null ? write.getValue() : new byte[0];
            operations.add(new PendingOperation(target, resource, WRITE_BINARY, source.clone()));
        }
        for (Path deleted : deletes) {
            Path target = requireAssetPath(deleted);
            String resource = resource(target);
            if (!targets.add(resource) || !targetPaths.add(target)) {
                throw new IOException("Asset transaction contains duplicate targets: " + deleted);
            }
            operations.add(new PendingOperation(target, resource, DELETE, new byte[0]));
        }
        operations.sort(Comparator.comparing(PendingOperation::resource).thenComparing(PendingOperation::operation));
        List<TransactionOperation> descriptorOperations = operations.stream()
            .map(operation -> new TransactionOperation(operation.resource(), operation.operation(),
                operation.operation().equals(DELETE) ? "" : StorageSafety.sha256(operation.content()),
                operation.content().length))
            .toList();
        String fingerprint = fingerprint(descriptorOperations);
        return new NormalizedTransaction(List.copyOf(operations),
            new TransactionDescriptor(DESCRIPTOR_VERSION, fingerprint, descriptorOperations));
    }

    private Journal journal(String id, String mutationId, String state, List<Entry> entries, String fingerprint) {
        return new Journal(JOURNAL_VERSION, id, mutationId, state, List.copyOf(entries), fingerprint);
    }

    private String fingerprint(List<TransactionOperation> operations) {
        return StorageSafety.sha256(gson.toJson(new FingerprintDocument(DESCRIPTOR_VERSION, operations)));
    }

    private TransactionVisibility visibility(Journal journal) {
        List<TransactionOperation> operations = journal.entries().stream()
            .map(entry -> new TransactionOperation(entry.target(), entry.operation(), entry.hash(), entry.size()))
            .toList();
        return new TransactionVisibility(journal.id(), journal.mutationId(), journal.state(),
            new TransactionDescriptor(DESCRIPTOR_VERSION, journal.fingerprint(), operations));
    }

    private void writeJournal(Path path, String content) throws IOException {
        long started = System.nanoTime();
        try {
            StorageSafety.writeUtf8Atomic(path, content);
        } finally {
            journalDurabilityAttemptCount++;
            journalDurabilityNanos += elapsedNanos(started);
        }
    }

    private void applyMeasured(Path transactionDir, Journal journal) throws IOException {
        long started = System.nanoTime();
        try {
            apply(transactionDir, journal);
        } finally {
            applyAttemptCount++;
            applyNanos += elapsedNanos(started);
        }
    }

    private void apply(Path transactionDir, Journal journal) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(transactionRoot, transactionDir);
        Path transactionSnapshotRoot = snapshotRoot.resolve(journal.id()).normalize();
        if (!transactionSnapshotRoot.startsWith(snapshotRoot)) {
            throw new IOException("Unsafe asset transaction snapshot: " + journal.id());
        }
        StorageSafety.createDirectoriesNoSymlinks(snapshotRoot, transactionSnapshotRoot);
        MigrationPaths.requireNoSymlinkTraversal(snapshotRoot, transactionSnapshotRoot);
        for (Entry entry : journal.entries()) {
            Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
            Path snapshot = transactionSnapshotRoot.resolve(entry.target()).normalize();
            if (!snapshot.startsWith(transactionSnapshotRoot)) {
                throw new IOException("Unsafe asset transaction snapshot: " + entry.target());
            }
            MigrationPaths.requireNoSymlinkTraversal(snapshotRoot, snapshot);
            if (Boolean.TRUE.equals(entry.existed())) {
                Path snapshotParent = snapshot.getParent();
                if (snapshotParent == null) {
                    throw new IOException("Asset transaction snapshot has no parent: " + entry.target());
                }
                StorageSafety.createDirectoriesNoSymlinks(snapshotRoot, snapshotParent);
                if (!Files.exists(snapshot)) {
                    if (!Files.isRegularFile(target)) {
                        throw new IOException("Asset transaction lost its original target: " + entry.target());
                    }
                    StorageSafety.writeBytesAtomic(snapshot, Files.readAllBytes(target));
                }
            }
            if (entry.delete()) {
                StorageSafety.deleteIfExists(target);
                continue;
            }
            Path staged = transactionDir.resolve(entry.staged()).normalize();
            if (!staged.startsWith(transactionDir)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing staged asset transaction content: " + entry.staged());
            }
            MigrationPaths.requireNoSymlinkTraversal(transactionRoot, staged);
            byte[] content = Files.readAllBytes(staged);
            if (entry.size() != content.length || !entry.hash().equals(StorageSafety.sha256(content))) {
                throw new IOException("Corrupt staged asset transaction content: " + entry.staged());
            }
            StorageSafety.writeBytesAtomic(target, content);
        }
    }

    private String committedTransaction(String mutationId, String expectedFingerprint) throws IOException {
        if (mutationId.isBlank()) {
            return "";
        }
        fullJournalPasses++;
        try (Stream<Path> paths = Files.list(transactionRoot)) {
            for (Path transactionDir : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .sorted().toList()) {
                Path journalFile = transactionDir.resolve("journal.json");
                if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Journal journal = readJournalForAccess(journalFile);
                if (!COMMITTED.equals(journal.state()) || !mutationId.equals(journal.mutationId())) {
                    continue;
                }
                if (!expectedFingerprint.equals(journal.fingerprint())) {
                    throw new IOException("Mutation ID was already committed with a different asset transaction: "
                        + mutationId);
                }
                return journal.id();
            }
        }
        return "";
    }

    private IndexedCommit indexedCommit(String mutationId, String expectedFingerprint) throws IOException {
        if (!commitIndexTrusted) {
            String transactionId = committedTransaction(mutationId, expectedFingerprint);
            return transactionId.isBlank() ? null : new IndexedCommit(transactionId, expectedFingerprint);
        }
        indexedMutationLookups++;
        IndexedCommit indexed = committedTransactions.get(mutationId);
        if (indexed == null) {
            return null;
        }
        if (!expectedFingerprint.equals(indexed.fingerprint())) {
            throw new IOException("Mutation ID was already committed with a different asset transaction: " + mutationId);
        }
        return indexed;
    }

    private void indexCommitted(Journal journal) throws IOException {
        if (!commitIndexTrusted || journal.mutationId().isBlank()) {
            return;
        }
        addIndexedCommit(committedTransactions, journal);
    }

    private static void addIndexedCommit(Map<String, IndexedCommit> index, Journal journal) throws IOException {
        if (journal.mutationId().isBlank()) {
            return;
        }
        IndexedCommit added = new IndexedCommit(journal.id(), journal.fingerprint());
        IndexedCommit existing = index.putIfAbsent(journal.mutationId(), added);
        if (existing != null && (!existing.transactionId().equals(added.transactionId())
            || !existing.fingerprint().equals(added.fingerprint()))) {
            throw new IOException("Mutation ID has conflicting committed asset transactions: " + journal.mutationId());
        }
    }

    private TransactionInspection inspection(Journal journal, List<PendingOperation> operations) throws IOException {
        Map<String, byte[]> stagedWrites = new LinkedHashMap<>();
        for (PendingOperation operation : operations) {
            if (!DELETE.equals(operation.operation())) {
                stagedWrites.put(operation.resource(), operation.content());
            }
        }
        Path transactionDir = transactionRoot.resolve(journal.id()).normalize();
        return new TransactionInspection(visibility(journal), stagedWrites, journalEvidence(transactionDir, journal));
    }

    private JournalEvidence journalEvidence(Path transactionDir, Journal journal) throws IOException {
        return journalEvidence(transactionDir, journal, null);
    }

    private JournalEvidence journalEvidence(Path transactionDir, Journal journal, JournalCapture capture) throws IOException {
        Path normalized = transactionDir.toAbsolutePath().normalize();
        Path journalPath = normalized.resolve("journal.json");
        List<JournalEntryEvidence> entries = journal.entries().stream().map(entry -> new JournalEntryEvidence(
            entry.target(), entry.staged(), entry.hash(), entry.delete(), entry.existed(), entry.operation(),
            entry.size(), capture == null ? null : capture.staged.get(entry.target()))).toList();
        ContentIdentity journalIdentity = capture == null ? contentIdentity(journalPath) : capture.journal;
        return new JournalEvidence(journal.version(), normalized, journalPath, journalIdentity, entries);
    }

    private Journal committedJournal(String transactionId) throws IOException {
        Path transactionDir = requireTransactionRoot(transactionId);
        Path journalFile = transactionDir.resolve("journal.json");
        if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset transaction does not exist: " + transactionId);
        }
        Journal journal = readJournalForAccess(journalFile);
        if (!COMMITTED.equals(journal.state())) {
            throw new IOException("Asset transaction is not committed: " + transactionId);
        }
        return journal;
    }

    private Journal readJournalForAccess(Path journalFile) throws IOException {
        return readJournal(journalFile, recoveryMode == RecoveryMode.AUTOMATIC);
    }

    private Journal readJournal(Path journalFile, boolean upgradeLegacy) throws IOException {
        return readJournal(journalFile, upgradeLegacy, null);
    }

    private Journal readJournal(Path journalFile, boolean upgradeLegacy, Map<String, byte[]> stagedWrites)
        throws IOException {
        return readJournal(journalFile, upgradeLegacy, stagedWrites, null, null, false);
    }

    private Journal readJournal(Path journalFile, boolean upgradeLegacy, Map<String, byte[]> stagedWrites,
                                JournalCapture capture, RetentionBudget budget, boolean startupInspection)
        throws IOException {
        try {
            journalReads++;
            MigrationPaths.requireNoSymlinkTraversal(transactionRoot, journalFile);
            CapturedBytes journalBytes = captureBytes(journalFile, true);
            observeContentRead(journalFile);
            if (capture != null) {
                capture.journal = journalBytes.identity();
            }
            Journal raw = gson.fromJson(new String(journalBytes.bytes(), StandardCharsets.UTF_8), Journal.class);
            if (raw == null || raw.entries() == null || raw.state() == null || raw.id() == null
                || raw.mutationId() == null) {
                throw new IOException("Incomplete asset transaction journal: " + journalFile);
            }
            requireTransactionId(raw.id());
            Path transactionDir = journalFile.getParent();
            if (transactionDir == null || !raw.id().equals(transactionDir.getFileName().toString())) {
                throw new IOException("Asset transaction journal ID does not match its directory: " + journalFile);
            }
            if (raw.version() != JOURNAL_VERSION && raw.version() != LEGACY_JOURNAL_VERSION) {
                throw new IOException("Unsupported asset transaction journal version: " + raw.version());
            }
            if (!PREPARED.equals(raw.state()) && !COMMITTED.equals(raw.state())) {
                throw new IOException("Unknown asset transaction state: " + raw.state());
            }
            List<Entry> entries = new ArrayList<>();
            Set<String> targets = new LinkedHashSet<>();
            for (Entry entry : raw.entries()) {
                Entry normalized = normalizeEntry(entry, transactionDir, raw.version() == LEGACY_JOURNAL_VERSION);
                if (!targets.add(normalized.target())) {
                    throw new IOException("Asset transaction journal contains duplicate target: " + normalized.target());
                }
                entries.add(normalized);
            }
            entries.sort(Comparator.comparing(Entry::target).thenComparing(Entry::operation));
            String expectedFingerprint = fingerprint(entries.stream()
                .map(entry -> new TransactionOperation(entry.target(), entry.operation(), entry.hash(), entry.size()))
                .toList());
            String fingerprint = raw.version() == LEGACY_JOURNAL_VERSION ? expectedFingerprint : raw.fingerprint();
            if (fingerprint == null || fingerprint.isBlank() || !expectedFingerprint.equals(fingerprint)) {
                throw new IOException("Asset transaction journal fingerprint does not match its entries: " + journalFile);
            }
            Journal normalized = journal(raw.id(), raw.mutationId(), raw.state(), entries, fingerprint);
            verifyJournal(transactionDir, normalized, stagedWrites, capture, budget, startupInspection);
            if (raw.version() == LEGACY_JOURNAL_VERSION && upgradeLegacy) {
                StorageSafety.writeUtf8Atomic(journalFile, gson.toJson(normalized));
            }
            return normalized;
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Corrupt asset transaction journal: " + journalFile, failure);
        }
    }

    private Entry normalizeEntry(Entry entry, Path transactionDir, boolean legacy) throws IOException {
        if (entry == null || entry.target() == null || entry.target().isBlank()) {
            throw new IOException("Asset transaction journal contains an incomplete entry");
        }
        Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
        String resource = resource(target);
        String operation = entry.operation();
        if (legacy && (operation == null || operation.isBlank())) {
            operation = entry.delete() ? DELETE : legacyWriteOperation(entry.staged());
        }
        if (operation == null || operation.isBlank()) {
            throw new IOException("Asset transaction journal entry has no operation: " + resource);
        }
        if (DELETE.equals(operation)) {
            if (!entry.delete() || (entry.staged() != null && !entry.staged().isBlank())
                || entry.hash() == null || !entry.hash().isBlank()) {
                throw new IOException("Invalid delete entry in asset transaction journal: " + resource);
            }
            return new Entry(resource, "", "", true, entry.existed(), DELETE, 0L);
        }
        if (!WRITE_TEXT.equals(operation) && !WRITE_BINARY.equals(operation)) {
            throw new IOException("Unknown asset transaction operation: " + operation);
        }
        if (entry.delete() || entry.staged() == null || entry.staged().isBlank()
            || entry.hash() == null || entry.hash().isBlank()) {
            throw new IOException("Invalid write entry in asset transaction journal: " + resource);
        }
        String expectedExtension = WRITE_BINARY.equals(operation) ? ".bin" : ".json";
        if (!entry.staged().endsWith(expectedExtension)) {
            throw new IOException("Asset transaction staged content does not match its operation: " + resource);
        }
        Path staged = transactionDir.resolve(entry.staged()).normalize();
        if (!staged.startsWith(transactionDir)
            || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing staged asset transaction content: " + entry.staged());
        }
        long size = entry.size() != null ? entry.size() : Files.size(staged);
        if (size < 0L) {
            throw new IOException("Invalid staged asset transaction size: " + entry.staged());
        }
        return new Entry(resource, entry.staged(), entry.hash(), false, entry.existed(), operation, size);
    }

    private String legacyWriteOperation(String staged) throws IOException {
        if (staged == null || staged.isBlank()) {
            throw new IOException("Legacy asset transaction write has no staged content");
        }
        if (staged.endsWith(".bin")) {
            return WRITE_BINARY;
        }
        if (staged.endsWith(".json")) {
            return WRITE_TEXT;
        }
        throw new IOException("Legacy asset transaction write operation is ambiguous: " + staged);
    }

    private void verifyJournal(Path transactionDir, Journal journal, Map<String, byte[]> stagedWrites,
                               JournalCapture capture, RetentionBudget budget, boolean startupInspection)
        throws IOException {
        if (journal.entries().isEmpty()) {
            throw new IOException("Asset transaction journal has no entries: " + journal.id());
        }
        for (Entry entry : journal.entries()) {
            if (entry.delete()) {
                if (!DELETE.equals(entry.operation()) || !entry.hash().isEmpty() || entry.size() != 0L) {
                    throw new IOException("Invalid delete operation in asset transaction journal: " + entry.target());
                }
                continue;
            }
            Path staged = transactionDir.resolve(entry.staged()).normalize();
            if (!staged.startsWith(transactionDir)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing staged asset transaction content: " + entry.staged());
            }
            boolean retain = !startupInspection || PREPARED.equals(journal.state())
                || retainedControlResource(entry.target());
            if (stagedWrites != null && retain && budget != null) {
                budget.retain(Files.size(staged));
            }
            CapturedBytes captured = captureBytes(staged, true);
            byte[] content = captured.bytes();
            observeContentRead(staged);
            stagedPayloadReads++;
            if (entry.size() != content.length || !entry.hash().equals(StorageSafety.sha256(content))) {
                throw new IOException("Corrupt staged asset transaction content: " + entry.staged());
            }
            if (capture != null) {
                capture.staged.put(entry.target(), captured.identity());
            }
            if (stagedWrites != null && retain) {
                stagedWrites.put(entry.target(), content);
            }
        }
    }

    private static boolean controlResource(String resource) {
        return resource.equals("project.json") || resource.equals(".asset-coordinator/state.json")
            || resource.startsWith(".asset-coordinator/bindings/");
    }

    private static boolean retainedControlResource(String resource) {
        return resource.equals(".asset-coordinator/state.json")
            || resource.startsWith(".asset-coordinator/bindings/");
    }

    private static CapturedBytes captureBytes(Path path, boolean requireRegular) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (requireRegular && (!before.isRegularFile() || before.isSymbolicLink())) {
            throw new IOException("Asset evidence is not a safe regular file: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        ContentIdentity beforeIdentity = contentIdentity(before, bytes);
        ContentIdentity afterIdentity = contentIdentity(after, bytes);
        if (!beforeIdentity.equals(afterIdentity) || before.size() != bytes.length) {
            throw new IOException("Asset evidence changed during content capture: " + path);
        }
        return new CapturedBytes(bytes, beforeIdentity);
    }

    private static ContentIdentity contentIdentity(Path path) throws IOException {
        CapturedBytes captured = captureBytes(path, true);
        return captured.identity();
    }

    private static ContentIdentity contentIdentity(BasicFileAttributes attributes, byte[] bytes) {
        return new ContentIdentity(attributes.size(), StorageSafety.sha256(bytes), attributes.lastModifiedTime(),
            attributes.creationTime(), Objects.toString(attributes.fileKey(), ""));
    }

    private void observeContentRead(Path path) {
        if (contentReadObserver != null) {
            contentReadObserver.contentRead(path.toAbsolutePath().normalize());
        }
    }

    private long journalModified(Path transactionDir) {
        try {
            return Files.getLastModifiedTime(transactionDir.resolve("journal.json")).toMillis();
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void recoverIfAutomatic() throws IOException {
        if (recoveryMode == RecoveryMode.AUTOMATIC && !commitIndexTrusted) {
            recover();
        }
    }

    private Path requireAssetPath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Asset transaction target is missing");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(assetsRoot) || normalized.equals(assetsRoot)
            || normalized.startsWith(transactionRoot) || normalized.startsWith(snapshotRoot)) {
            throw new IOException("Asset transaction target is outside the asset root: " + path);
        }
        Path existingParent = normalized.getParent();
        while (existingParent != null && !Files.exists(existingParent)) {
            existingParent = existingParent.getParent();
        }
        assetPathCanonicalizations++;
        if (existingParent == null || !existingParent.toRealPath().startsWith(assetsRoot)) {
            throw new IOException("Asset transaction target escapes the asset root: " + path);
        }
        return normalized;
    }

    private String resource(Path target) throws IOException {
        String resource = assetsRoot.relativize(target).toString().replace('\\', '/');
        if (resource.isBlank() || resource.equals(".")) {
            throw new IOException("Asset transaction target is empty: " + target);
        }
        return resource;
    }

    private void discardUnpreparedTransaction(Path transactionDir) throws IOException {
        if (!transactionDir.normalize().startsWith(transactionRoot)
            || !Files.isDirectory(transactionDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        MigrationPaths.requireNoSymlinkTraversal(transactionRoot, transactionDir);
        try (Stream<Path> paths = Files.walk(transactionDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        StorageSafety.forceDirectory(transactionRoot);
    }

    private Path requireSnapshotRoot(String transactionId) throws IOException {
        requireTransactionId(transactionId);
        Path root = snapshotRoot.resolve(transactionId).normalize();
        if (!root.startsWith(snapshotRoot)) {
            throw new IOException("Unsafe asset snapshot id");
        }
        MigrationPaths.requireNoSymlinkTraversal(snapshotRoot, root);
        return root;
    }

    private Path requireTransactionRoot(String transactionId) throws IOException {
        requireTransactionId(transactionId);
        Path root = transactionRoot.resolve(transactionId).normalize();
        if (!root.startsWith(transactionRoot)) {
            throw new IOException("Unsafe asset transaction id");
        }
        MigrationPaths.requireNoSymlinkTraversal(transactionRoot, root);
        return root;
    }

    private void ensureInternalRoots() throws IOException {
        ensureInternalRoot(transactionRoot, "transactionRoot");
        ensureInternalRoot(snapshotRoot, "snapshotRoot");
    }

    private void ensureInternalRoot(Path root, String field) throws IOException {
        try {
            StorageSafety.createDirectoriesNoSymlinks(assetsRoot, root);
            MigrationPaths.requireNoSymlinkTraversal(assetsRoot, root);
            MigrationPaths.requireDirectory(root, field);
        } catch (IllegalArgumentException failure) {
            throw new IOException(field + " is unsafe: " + root, failure);
        }
    }

    private void requireTransactionId(String transactionId) throws IOException {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9-]{1,96}")) {
            throw new IOException("Invalid asset transaction id");
        }
    }

    private record PendingOperation(Path target, String resource, String operation, byte[] content) {
    }

    private record NormalizedTransaction(List<PendingOperation> operations, TransactionDescriptor descriptor) {
    }

    private record PendingJournal(Path transactionDir, Path journalFile, Journal journal) {
    }

    private record FrozenInspection(TransactionInspection inspection, Journal journal) {
    }

    private record IndexedCommit(String transactionId, String fingerprint) {
    }

    private record FingerprintDocument(String version, List<TransactionOperation> operations) {
    }

    private record Journal(int version, String id, String mutationId, String state, List<Entry> entries,
                           String fingerprint) {
    }

    private record Entry(String target, String staged, String hash, boolean delete, Boolean existed,
                         String operation, Long size) {
    }

    public record TransactionOperation(String resource, String operation, String payloadHash, long payloadSize) {
        public TransactionOperation {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(payloadHash, "payloadHash");
            if (payloadSize < 0L) {
                throw new IllegalArgumentException("payloadSize cannot be negative");
            }
        }
    }

    public record TransactionDescriptor(String version, String fingerprint, List<TransactionOperation> operations) {
        public TransactionDescriptor {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(fingerprint, "fingerprint");
            operations = List.copyOf(operations);
        }
    }

    public record TransactionVisibility(String transactionId, String mutationId, String state,
                                        TransactionDescriptor descriptor) {
        public TransactionVisibility {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(mutationId, "mutationId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    public record RestorePreview(String transactionId, List<String> files) {
    }

    public record ContentIdentity(long size, String hash, FileTime modifiedAt, FileTime createdAt, String fileKey) {
        public ContentIdentity {
            if (size < 0L) {
                throw new IllegalArgumentException("size cannot be negative");
            }
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(modifiedAt, "modifiedAt");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(fileKey, "fileKey");
        }
    }

    public record JournalEntryEvidence(String target, String staged, String hash, boolean delete, Boolean existed,
                                       String operation, long size, ContentIdentity stagedIdentity) {
        public JournalEntryEvidence(String target, String staged, String hash, boolean delete, Boolean existed,
                                    String operation, long size) {
            this(target, staged, hash, delete, existed, operation, size, null);
        }

        public JournalEntryEvidence {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(staged, "staged");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(operation, "operation");
            if (size < 0L) {
                throw new IllegalArgumentException("size cannot be negative");
            }
        }
    }

    public record JournalEvidence(int version, Path transactionDirectory, Path journalPath, ContentIdentity journalIdentity,
                                  List<JournalEntryEvidence> entries) {
        public JournalEvidence(Path transactionDirectory, Path journalPath, FileTime modifiedAt,
                               List<JournalEntryEvidence> entries) {
            this(JOURNAL_VERSION, transactionDirectory, journalPath,
                new ContentIdentity(0L, "", modifiedAt, FileTime.fromMillis(0L), ""), entries);
        }

        public JournalEvidence(Path transactionDirectory, Path journalPath, ContentIdentity journalIdentity,
                               List<JournalEntryEvidence> entries) {
            this(JOURNAL_VERSION, transactionDirectory, journalPath, journalIdentity, entries);
        }

        public JournalEvidence {
            if (version != JOURNAL_VERSION && version != LEGACY_JOURNAL_VERSION) {
                throw new IllegalArgumentException("Unsupported journal evidence version");
            }
            transactionDirectory = Objects.requireNonNull(transactionDirectory, "transactionDirectory")
                .toAbsolutePath().normalize();
            journalPath = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize();
            Objects.requireNonNull(journalIdentity, "journalIdentity");
            entries = List.copyOf(entries);
        }

        public FileTime modifiedAt() {
            return journalIdentity.modifiedAt();
        }
    }

    interface ContentReadObserver {
        void contentRead(Path path);
    }

    interface RecoveryGuard {
        RecoveryEvidence validate() throws IOException;
    }

    record RecoveryEvidence(Map<Path, byte[]> files, Set<Path> snapshotsToCreate) {
        RecoveryEvidence {
            Map<Path, byte[]> copies = new LinkedHashMap<>();
            files.forEach((path, bytes) -> copies.put(path.toAbsolutePath().normalize(), bytes.clone()));
            files = Map.copyOf(copies);
            snapshotsToCreate = snapshotsToCreate.stream().map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        }

        Map<Path, byte[]> filesView() {
            return files;
        }
    }

    private record CapturedBytes(byte[] bytes, ContentIdentity identity) {
        private CapturedBytes {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(identity, "identity");
        }
    }

    private static final class JournalCapture {
        private ContentIdentity journal;
        private final Map<String, ContentIdentity> staged = new LinkedHashMap<>();
    }

    private static final class RetentionBudget {
        private long retained;

        private void retain(long bytes) throws IOException {
            if (bytes < 0L || bytes > RETAINED_EVIDENCE_FILE_LIMIT
                || retained > RETAINED_EVIDENCE_TOTAL_LIMIT - bytes) {
                throw new IOException("Retained asset transaction evidence exceeds its bounded startup budget");
            }
            retained += bytes;
        }
    }

    public record TransactionInspection(TransactionVisibility visibility, Map<String, byte[]> stagedWrites,
                                        JournalEvidence journalEvidence) {
        public TransactionInspection(TransactionVisibility visibility, Map<String, byte[]> stagedWrites) {
            this(visibility, stagedWrites, null);
        }

        public TransactionInspection {
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(stagedWrites, "stagedWrites");
            Map<String, byte[]> copy = new LinkedHashMap<>();
            stagedWrites.forEach((path, content) -> copy.put(path, content.clone()));
            stagedWrites = Map.copyOf(copy);
        }

        @Override
        public Map<String, byte[]> stagedWrites() {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            stagedWrites.forEach((path, content) -> copy.put(path, content.clone()));
            return Map.copyOf(copy);
        }

        Map<String, byte[]> stagedWritesView() {
            return stagedWrites;
        }
    }

    public record CommitResult(String transactionId, TransactionInspection inspection, boolean replay) {
    }

    record CommittedIndexEntry(String transactionId, String fingerprint) {
        CommittedIndexEntry {
            Objects.requireNonNull(transactionId, "Transaction ID is required");
            Objects.requireNonNull(fingerprint, "Transaction fingerprint is required");
        }
    }

    public record IoMetrics(long fullJournalPasses, long journalReads, long stagedPayloadReads,
                            long indexedMutationLookups, long assetPathCanonicalizations) {
    }

    record PhaseTiming(long commitAttemptCount, long commitNanos,
                       long prepareAttemptCount, long prepareNanos,
                       long applyAttemptCount, long applyNanos, long journalDurabilityAttemptCount,
                       long journalDurabilityNanos, long residualNanos) {
    }

    public enum RecoveryMode {
        AUTOMATIC,
        DEFERRED
    }
}
