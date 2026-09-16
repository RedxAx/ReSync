package restudio.resync.worldgen;

import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class WorldGenGeneratedOutputController {
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final String JOURNAL_FILE = "generated.transaction";
    private static final String JOURNAL_VERSION = "1";
    private static final String STARTUP_REBUILD_INTENT_FILE = "generated.startup-rebuild";
    private static final String STARTUP_REBUILD_INTENT_VERSION = "1";
    private static final String ATOMIC_TEMP_SEPARATOR = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final String HANDOFF_DIRECTORY = ".handoffs";
    private static final String HANDOFF_STAGING_PREFIX = ".generated.handoff.";
    private static final String HANDOFF_PREFIX = "handoff.";
    private static final String RECOVERY_QUARANTINE_DIRECTORY = ".quarantine/worldgen-generated";
    private static final int MOVE_ATTEMPTS = 8;
    private static final long MOVE_RETRY_MILLIS = 25L;

    private final Object monitor = new Object();
    private final MigrationFence fence;
    private final Duration defaultDrainTimeout;
    private final Rebuilder rebuilder;
    private final ReentrantLock transactionLock = new ReentrantLock(true);
    private final Map<Long, Lease> active = new LinkedHashMap<>();
    private final Map<Long, Path> pinnedHandoffs = new LinkedHashMap<>();
    private Path scopeRoot;
    private Path generatedRoot;
    private State state = State.OPEN;
    private long nextOperation;
    private long generation;
    private boolean rebuildRequired;
    private String failureReason = "";

    public WorldGenGeneratedOutputController(Path scopeRoot, Rebuilder rebuilder) throws IOException {
        this(scopeRoot, new MigrationFence(), DEFAULT_DRAIN_TIMEOUT, rebuilder);
    }

    public WorldGenGeneratedOutputController(Path scopeRoot, MigrationFence fence, Rebuilder rebuilder) throws IOException {
        this(scopeRoot, fence, DEFAULT_DRAIN_TIMEOUT, rebuilder);
    }

    public WorldGenGeneratedOutputController(Path scopeRoot, MigrationFence fence, Duration drainTimeout,
                                             Rebuilder rebuilder) throws IOException {
        Path normalizedScope = MigrationPaths.requireDirectory(scopeRoot, "scopeRoot");
        this.scopeRoot = normalizedScope;
        this.fence = Objects.requireNonNull(fence, "fence");
        this.defaultDrainTimeout = requireTimeout(drainTimeout, "drainTimeout");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        Path parent = normalizedScope.resolve("worldgen").toAbsolutePath().normalize();
        Files.createDirectories(parent);
        MigrationPaths.requireNoSymlinkTraversal(normalizedScope, parent);
        boolean recoveryRequired = recoverControllerAtomicTemporaries(parent);
        recoveryRequired |= recoverTransaction(parent, WorldGenGeneratedOutputPolicy.root(normalizedScope));
        this.generatedRoot = ensureRoot(normalizedScope);
        this.rebuildRequired = recoveryRequired || startupRebuildIntentPresent(parent);
    }

    public Path scopeRoot() {
        synchronized (monitor) {
            return scopeRoot;
        }
    }

    public Path generatedRoot() {
        synchronized (monitor) {
            return generatedRoot;
        }
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    public boolean admissionOpen() {
        synchronized (monitor) {
            return state == State.OPEN && !rebuildRequired;
        }
    }

    public int activeOperationCount() {
        synchronized (monitor) {
            return active.size();
        }
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    public boolean rebuildRequired() {
        synchronized (monitor) {
            return rebuildRequired;
        }
    }

    public Health health() {
        State current;
        Path root;
        int activeOperations;
        long currentGeneration;
        String currentFailure;
        boolean currentRebuildRequired;
        synchronized (monitor) {
            current = state;
            root = generatedRoot;
            activeOperations = active.size();
            currentGeneration = generation;
            currentFailure = failureReason;
            currentRebuildRequired = rebuildRequired;
        }
        if (current != State.CLOSED && current != State.QUIESCING && current != State.RESUMING
            && current != State.REBINDING) {
            try {
                validateRoot(root);
                if (current == State.OPEN && !currentRebuildRequired) {
                    rebuilder.healthCheck();
                }
            } catch (IOException | RuntimeException exception) {
                currentFailure = reason(exception);
            }
        }
        if (currentFailure.isBlank()) {
            currentFailure = switch (current) {
                case OPEN -> currentRebuildRequired ? "Rebuild Required" : "";
                case QUIESCED -> currentRebuildRequired ? "Rebuild Required" : "Quiesced";
                case QUIESCING -> "Quiescing";
                case RESUMING -> "Resuming";
                case REBINDING -> "Rebinding";
                case DEGRADED -> "Degraded";
                case FAILED -> "Failed";
                case CLOSED -> "Closed";
            };
        }
        boolean available = current == State.OPEN && !currentRebuildRequired && currentFailure.isBlank();
        return new Health(available, current, activeOperations, currentGeneration, currentFailure);
    }

    public Lease acquire(String operation) {
        return acquire(operation, false);
    }

    private Lease acquire(String operation, boolean recovery) {
        String label = requireText(operation, "operation");
        MigrationFence.MutationLease mutation = null;
        try {
            synchronized (monitor) {
                requireAdmission(recovery);
            }
            mutation = fence.beginMutation();
            synchronized (monitor) {
                requireAdmission(recovery);
                Operation activeOperation = new Operation(++nextOperation, label, mutation);
                Lease lease = new Lease(activeOperation);
                active.put(activeOperation.id(), lease);
                mutation = null;
                return lease;
            }
        } finally {
            if (mutation != null) {
                mutation.close();
            }
        }
    }

    public Optional<Lease> tryAcquire(String operation, Duration timeout) throws InterruptedException {
        String label = requireText(operation, "operation");
        Duration wait = requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state != State.OPEN || rebuildRequired) {
                return Optional.empty();
            }
        }
        Optional<MigrationFence.MutationLease> mutation = fence.tryBeginMutation(wait);
        if (mutation.isEmpty()) {
            return Optional.empty();
        }
        synchronized (monitor) {
            if (state != State.OPEN || rebuildRequired) {
                mutation.orElseThrow().close();
                return Optional.empty();
            }
            Operation activeOperation = new Operation(++nextOperation, label, mutation.orElseThrow());
            Lease lease = new Lease(activeOperation);
            active.put(activeOperation.id(), lease);
            return Optional.of(lease);
        }
    }

    public <T> CompletableFuture<T> submit(String operation, Executor executor,
                                            Function<? super CancellationToken, ? extends T> task) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");
        Lease lease = acquire(operation);
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<Thread> runner = new AtomicReference<>();
        lease.onCancel(() -> {
            Thread thread = runner.get();
            if (thread != null) {
                thread.interrupt();
            }
            result.cancel(false);
        });
        try {
            executor.execute(() -> {
                runner.set(Thread.currentThread());
                lease.setRunner(Thread.currentThread());
                try {
                    lease.throwIfCancellationRequested();
                    T value = task.apply(lease.token());
                    if (!result.isCancelled()) {
                        result.complete(value);
                    }
                } catch (CancellationException exception) {
                    result.cancel(false);
                } catch (Throwable exception) {
                    if (!result.isCancelled()) {
                        result.completeExceptionally(exception);
                    }
                } finally {
                    lease.close();
                }
            });
        } catch (RuntimeException exception) {
            lease.close();
            result.completeExceptionally(exception);
        }
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                lease.cancel();
            }
        });
        return result;
    }

    public <T> CompletableFuture<T> track(String operation, CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage");
        Lease lease = acquire(operation);
        CompletableFuture<T> result = stage.toCompletableFuture();
        lease.onCancel(() -> result.cancel(true));
        result.whenComplete((value, failure) -> lease.close());
        return result;
    }

    public <T> TransactionResult<T> transact(String operation, TransactionWriter<T> writer) throws IOException {
        return transact(operation, writer, false);
    }

    private <T> TransactionResult<T> transact(String operation, TransactionWriter<T> writer,
                                               boolean recovery) throws IOException {
        String label = requireText(operation, "operation");
        Objects.requireNonNull(writer, "writer");
        Lease lease = acquire(label, recovery);
        lease.setRunner(Thread.currentThread());
        try {
            lease.throwIfCancellationRequested();
            TransactionResult<T> result = executeTransaction(writer);
            lease.throwIfCancellationRequested();
            return result;
        } finally {
            lease.setRunner(null);
            lease.close();
        }
    }

    public Handoff acquireHandoff(String operation) {
        return new Handoff(acquire(operation));
    }

    public void flush() throws IOException {
        State current = state();
        if (current == State.CLOSED) {
            return;
        }
        if (current == State.FAILED || current == State.DEGRADED || current == State.RESUMING
            || current == State.REBINDING || current == State.QUIESCING) {
            throw failure("WorldGen Generated Output Is " + current.name());
        }
        if (current == State.OPEN && rebuildRequired()) {
            throw failure("WorldGen Generated Output Requires Rebuild");
        }
        if (!awaitIdle(defaultDrainTimeout)) {
            throw new IOException("WorldGen Generated Output Flush Timed Out With " + activeOperationCount() + " Active Operations");
        }
        validateRoot(generatedRoot());
        rebuilder.healthCheck();
    }

    public void quiesce() throws IOException {
        quiesce(defaultDrainTimeout);
    }

    public void quiesce(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        List<Lease> operations;
        synchronized (monitor) {
            while (state == State.QUIESCING) {
                awaitStateChange();
            }
            if (state == State.QUIESCED || state == State.CLOSED) {
                return;
            }
            if (state == State.RESUMING || state == State.REBINDING) {
                throw new IOException("WorldGen Generated Output Cannot Quiesce During " + state.name());
            }
            state = State.QUIESCING;
            failureReason = "";
            operations = new ArrayList<>(active.values());
        }
        try {
            operations.forEach(Lease::cancel);
            if (!awaitIdle(wait)) {
                fail("WorldGen Generated Output Drain Timed Out With " + activeOperationCount() + " Active Operations", State.FAILED);
                throw new IOException(failureReason());
            }
        } catch (IOException | RuntimeException exception) {
            if (state() == State.QUIESCING) {
                fail(reason(exception), State.FAILED);
            }
            throw exception;
        } catch (Error error) {
            if (state() == State.QUIESCING) {
                fail(reason(error), State.FAILED);
            }
            throw error;
        }
        synchronized (monitor) {
            state = State.QUIESCED;
            failureReason = "";
            generation++;
            monitor.notifyAll();
        }
    }

    public boolean awaitIdle(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            while (!active.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("WorldGen Generated Output Drain Was Interrupted", exception);
                }
            }
            return true;
        }
    }

    public void resume() throws IOException {
        synchronized (monitor) {
            if (state == State.OPEN) {
                return;
            }
            if (state != State.QUIESCED) {
                throw failure("WorldGen Generated Output Cannot Resume From " + state.name());
            }
            if (rebuildRequired) {
                throw failure("WorldGen Generated Output Cannot Resume Before Its Required Rebuild");
            }
            state = State.RESUMING;
            monitor.notifyAll();
        }
        try {
            validateRoot(generatedRoot());
            rebuilder.healthCheck();
        } catch (IOException | RuntimeException exception) {
            fail(reason(exception), State.DEGRADED);
            throw exception;
        } catch (Error error) {
            fail(reason(error), State.DEGRADED);
            throw error;
        }
        synchronized (monitor) {
            if (state != State.RESUMING) {
                throw failure("WorldGen Generated Output Changed During Resume");
            }
            state = State.OPEN;
            rebuildRequired = false;
            generation++;
            failureReason = "";
            monitor.notifyAll();
        }
    }

    public void rebuild() throws IOException {
        Path parent;
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw failure("WorldGen Generated Output Cannot Rebuild From " + state.name());
            }
            parent = generatedRoot.getParent();
        }
        if (startupRebuildIntentPresent(parent)) {
            throw failure("WorldGen Generated Output Startup Rebuild Requires Coordinator Proof");
        }
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw failure("WorldGen Generated Output Cannot Rebuild From " + state.name());
            }
        }
        transact("rebuild", stage -> {
            rebuilder.rebuild(stage);
            return Boolean.TRUE;
        }, true);
        validateRoot(generatedRoot());
        rebuilder.healthCheck();
        synchronized (monitor) {
            rebuildRequired = false;
            failureReason = "";
        }
    }

    public void rebuildForStartup(ReSyncPersistenceCoordinator.DerivedStartupContext context,
                                  IoAction preparation) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(preparation, "preparation");
        requireStartupContext(context);
        synchronized (monitor) {
            if (state != State.QUIESCED) {
                throw failure("WorldGen Generated Output Must Be Quiesced For Startup Rebuild");
            }
            if (!active.isEmpty()) {
                throw new IOException("WorldGen Generated Output Has Active Operations During Startup Rebuild");
            }
            state = State.RESUMING;
            rebuildRequired = true;
            failureReason = "";
            monitor.notifyAll();
        }
        try {
            writeStartupRebuildIntent();
            retireInvalidJournalAtomicTemporariesForStartup(generatedRoot().getParent());
            preparation.run();
            executeTransaction(stage -> {
                rebuilder.rebuild(stage);
                return Boolean.TRUE;
            }, () -> {
                validateRoot(generatedRoot());
                rebuilder.healthCheck();
                requireStartupContext(context);
            });
            requireStartupContext(context);
            synchronized (monitor) {
                if (state != State.RESUMING || !active.isEmpty()) {
                    throw failure("WorldGen Generated Output Changed During Startup Rebuild");
                }
            }
            clearRecoveryBackupEvidence();
            requireStartupContext(context);
            clearStartupRebuildIntent();
            synchronized (monitor) {
                if (state != State.RESUMING || !active.isEmpty()) {
                    throw failure("WorldGen Generated Output Changed During Startup Rebuild Completion");
                }
                state = State.QUIESCED;
                rebuildRequired = false;
                generation++;
                failureReason = "";
                monitor.notifyAll();
            }
        } catch (IOException | RuntimeException exception) {
            failStartupRebuild(exception);
            throw exception;
        } catch (Error error) {
            failStartupRebuild(error);
            throw error;
        }
    }

    private void requireStartupContext(ReSyncPersistenceCoordinator.DerivedStartupContext context) {
        context.verify();
        context.requireOwner(WorldGenGeneratedOutputPolicy.OWNER);
        context.requireActiveRoot(scopeRoot());
    }

    private void writeStartupRebuildIntent() throws IOException {
        Path parent = generatedRoot().getParent();
        if (parent == null) {
            throw new IOException("WorldGen Generated Root Has No Parent");
        }
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), parent);
        Path intent = startupRebuildIntent(parent);
        publishStartupRebuildIntent(parent);
        if (!startupRebuildIntentPresent(parent)) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Was Not Published");
        }
    }

    private void clearStartupRebuildIntent() throws IOException {
        Path parent = generatedRoot().getParent();
        if (parent == null) {
            throw new IOException("WorldGen Generated Root Has No Parent");
        }
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), parent);
        Path intent = startupRebuildIntent(parent);
        if (!startupRebuildIntentPresent(parent)) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Is Missing");
        }
        Files.delete(intent);
        forceDirectory(parent);
    }

    private void clearRecoveryBackupEvidence() throws IOException {
        Path parent = generatedRoot().getParent();
        if (parent == null) {
            throw new IOException("WorldGen Generated Root Has No Parent");
        }
        Path quarantine = recoveryQuarantine(parent);
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        quarantine = prepareRecoveryQuarantine(parent);
        List<Path> backups;
        try (var stream = Files.list(quarantine)) {
            backups = stream
                .filter(path -> orphanArtifactKind(path.getFileName().toString()) == OrphanArtifactKind.BACKUP)
                .toList();
        }
        for (Path backup : backups) {
            validateOrphanArtifact(backup, "worldgen generated recovery backup");
            deleteTree(backup);
        }
        if (!backups.isEmpty()) {
            forceDirectory(quarantine);
            forceDirectory(parent);
        }
    }

    private void failStartupRebuild(Throwable failure) {
        State failureState = transactionFailureState(generatedRoot());
        synchronized (monitor) {
            rebuildRequired = true;
            if (state == State.RESUMING || state == State.OPEN) {
                state = failureState;
                failureReason = reason(failure);
            }
            monitor.notifyAll();
        }
    }

    public void rebind(Path activeRoot) throws IOException {
        Path candidateScope;
        synchronized (monitor) {
            if (state != State.QUIESCED && state != State.DEGRADED && state != State.FAILED) {
                throw failure("WorldGen Generated Output Must Be Quiesced Before Rebind");
            }
            if (!active.isEmpty()) {
                throw new IOException("WorldGen Generated Output Has Active Operations During Rebind");
            }
            state = State.REBINDING;
            candidateScope = activeRoot;
            monitor.notifyAll();
        }
        try {
            candidateScope = MigrationPaths.requireDirectory(candidateScope, "activeRoot");
            Path candidateParent = candidateScope.resolve("worldgen").toAbsolutePath().normalize();
            Files.createDirectories(candidateParent);
            MigrationPaths.requireNoSymlinkTraversal(candidateScope, candidateParent);
            boolean recoveryRequired = recoverControllerAtomicTemporaries(candidateParent);
            recoveryRequired |= recoverTransaction(candidateParent,
                WorldGenGeneratedOutputPolicy.root(candidateScope));
            Path candidateRoot = ensureRoot(candidateScope);
            recoveryRequired |= startupRebuildIntentPresent(candidateParent);
            synchronized (monitor) {
                scopeRoot = candidateScope;
                generatedRoot = candidateRoot;
                rebuildRequired = recoveryRequired;
                failureReason = "";
                state = State.QUIESCED;
                generation++;
                monitor.notifyAll();
            }
        } catch (IOException | RuntimeException exception) {
            fail(reason(exception), State.DEGRADED);
            throw exception;
        } catch (Error error) {
            fail(reason(error), State.DEGRADED);
            throw error;
        }
    }

    public void healthCheck() throws IOException {
        State current;
        boolean currentRebuildRequired;
        synchronized (monitor) {
            current = state;
            currentRebuildRequired = rebuildRequired;
        }
        if (current == State.CLOSED || current == State.QUIESCING || current == State.RESUMING
            || current == State.REBINDING || current == State.DEGRADED || current == State.FAILED) {
            throw failure("WorldGen Generated Output Is " + current.name());
        }
        if (currentRebuildRequired) {
            throw failure("WorldGen Generated Output Requires Rebuild");
        }
        validateRoot(generatedRoot());
        rebuilder.healthCheck();
    }

    public void close() throws IOException {
        State current = state();
        if (current == State.RESUMING || current == State.REBINDING) {
            throw failure("WorldGen Generated Output Cannot Close During " + current.name());
        }
        if (current == State.OPEN || current == State.DEGRADED || current == State.FAILED || current == State.QUIESCING) {
            quiesce(defaultDrainTimeout);
        }
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            if (state != State.QUIESCED) {
                throw failure("WorldGen Generated Output Is " + state.name());
            }
            state = State.CLOSED;
            monitor.notifyAll();
        }
    }

    private <T> TransactionResult<T> executeTransaction(TransactionWriter<T> writer) throws IOException {
        return executeTransaction(writer, () -> {
        });
    }

    private <T> TransactionResult<T> executeTransaction(TransactionWriter<T> writer,
                                                         TransactionVerifier verifier) throws IOException {
        lockTransaction();
        try {
            return executeTransactionLocked(writer, false, verifier).transaction();
        } finally {
            transactionLock.unlock();
        }
    }

    private <T> TransactionResult<T> executeHandoffTransaction(Lease lease,
                                                               TransactionWriter<T> writer) throws IOException {
        lockTransaction();
        Path snapshot = null;
        try {
            synchronized (monitor) {
                if (lease.isClosed() || active.get(lease.operationRecord().id()) == null) {
                    throw new IllegalStateException("WorldGen Generated Output Handoff Is Closed");
                }
                lease.throwIfCancellationRequested();
            }
            TransactionExecution<T> execution = executeTransactionLocked(writer, true, () -> {
            });
            snapshot = execution.snapshotRoot();
            TransactionResult<T> transaction = execution.transaction();
            Path pinnedRoot = pinHandoff(lease.operationRecord(), transaction.activeRoot(), snapshot);
            return new TransactionResult<>(transaction.value(), transaction.stagingRoot(), pinnedRoot);
        } finally {
            try {
                if (snapshot != null) {
                    deleteTree(snapshot);
                }
            } finally {
                transactionLock.unlock();
            }
        }
    }

    private void lockTransaction() throws IOException {
        try {
            transactionLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("WorldGen Generated Output Transaction Was Interrupted", exception);
        }
    }

    private <T> TransactionExecution<T> executeTransactionLocked(TransactionWriter<T> writer,
                                                                  boolean captureSnapshot,
                                                                  TransactionVerifier verifier) throws IOException {
        Objects.requireNonNull(verifier, "verifier");
        Path root;
        synchronized (monitor) {
            root = generatedRoot;
            if (state != State.OPEN && state != State.RESUMING) {
                throw failure("WorldGen Generated Output Transaction Is Not Admitted In " + state.name());
            }
        }
        Path parent = root.getParent();
        if (parent == null) {
            throw new IOException("WorldGen Generated Root Has No Parent");
        }
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), parent);
        Path stage = parent.resolve("generated.stage." + UUID.randomUUID()).toAbsolutePath().normalize();
        Path backup = parent.resolve("generated.backup." + UUID.randomUUID()).toAbsolutePath().normalize();
        Path snapshot = captureSnapshot
            ? parent.resolve(HANDOFF_STAGING_PREFIX + UUID.randomUUID()).toAbsolutePath().normalize() : null;
        ensureSibling(parent, stage);
        ensureSibling(parent, backup);
        if (snapshot != null) {
            ensureSibling(parent, snapshot);
        }
        T value;
        boolean committed = false;
        try {
            Files.createDirectory(stage);
            preservePinnedHandoffs(root, stage);
            writeJournal(parent, TransactionPhase.PREPARED, stage, backup);
            value = Objects.requireNonNull(writer.apply(stage), "transaction result");
            validateRoot(stage);
            forceTree(stage);
            if (snapshot != null) {
                Files.createDirectory(snapshot);
                copyGeneratedContents(stage, snapshot);
                validateRoot(snapshot);
                forceTree(snapshot);
            }
            writeJournal(parent, TransactionPhase.BACKUP_PENDING, stage, backup);
            moveRecoverably(root, backup);
            writeJournal(parent, TransactionPhase.BACKED_UP, stage, backup);
            writeJournal(parent, TransactionPhase.ACTIVATION_PENDING, stage, backup);
            moveRecoverably(stage, root);
            writeJournal(parent, TransactionPhase.ACTIVATED, stage, backup);
            forceDirectory(parent);
            verifier.verify();
            writeJournal(parent, TransactionPhase.COMMITTED, stage, backup);
            committed = true;
            deleteTree(backup);
            Files.deleteIfExists(journal(parent));
            forceDirectory(parent);
            return new TransactionExecution<>(new TransactionResult<>(value, stage, root), snapshot);
        } catch (IOException | RuntimeException | Error exception) {
            boolean rollbackFailed = false;
            if (!committed) {
                try {
                    rollbackTransaction(parent, root, stage, backup);
                } catch (IOException | RuntimeException | Error rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                    rollbackFailed = true;
                    fail("WorldGen Generated Output Rollback Failed After " + reason(exception) + ": "
                        + reason(rollbackFailure), transactionFailureState(root));
                }
            } else {
                fail("WorldGen Generated Output Commit Cleanup Failed: " + reason(exception), State.DEGRADED);
            }
            if (snapshot != null && !rollbackFailed) {
                try {
                    deleteTree(snapshot);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private static State transactionFailureState(Path root) {
        try {
            return validateTransactionDirectory(root, "worldgen generated root") ? State.DEGRADED : State.FAILED;
        } catch (IOException | RuntimeException | Error exception) {
            return State.FAILED;
        }
    }

    private void release(Operation operation) {
        transactionLock.lock();
        try {
            Path pinnedRoot = pinnedHandoffs.remove(operation.id());
            if (pinnedRoot != null) {
                try {
                    deletePinnedHandoff(pinnedRoot);
                } catch (IOException | RuntimeException exception) {
                    fail(reason(exception), State.DEGRADED);
                }
            }
            synchronized (monitor) {
                if (active.remove(operation.id()) != null) {
                    monitor.notifyAll();
                }
            }
        } finally {
            transactionLock.unlock();
        }
        operation.closeMutation();
    }

    private void deletePinnedHandoff(Path pinnedRoot) throws IOException {
        Path handoffRoot = pinnedRoot.getParent();
        deleteTree(pinnedRoot);
        if (handoffRoot == null || pinnedHandoffs.values().stream().anyMatch(path -> handoffRoot.equals(path.getParent()))) {
            return;
        }
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), handoffRoot);
        Files.deleteIfExists(handoffRoot);
    }

    private void preservePinnedHandoffs(Path root, Path stage) throws IOException {
        if (pinnedHandoffs.isEmpty()) {
            return;
        }
        Path handoffRoot = root.resolve(HANDOFF_DIRECTORY).toAbsolutePath().normalize();
        Path stagedHandoffRoot = stage.resolve(HANDOFF_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), root);
        Files.createDirectory(stagedHandoffRoot);
        for (Path pinnedRoot : pinnedHandoffs.values()) {
            Path normalizedPinnedRoot = MigrationPaths.requirePath(pinnedRoot, "pinnedHandoffRoot");
            if (!normalizedPinnedRoot.startsWith(handoffRoot) || normalizedPinnedRoot.equals(handoffRoot)) {
                throw new IOException("WorldGen Generated Handoff Escapes Its Root");
            }
            if (!Files.isDirectory(normalizedPinnedRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedPinnedRoot)) {
                throw new IOException("WorldGen Generated Handoff Is Missing: " + normalizedPinnedRoot);
            }
            MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), normalizedPinnedRoot);
            Path target = stagedHandoffRoot.resolve(normalizedPinnedRoot.getFileName()).normalize();
            ensureSibling(stagedHandoffRoot, target);
            copyTree(normalizedPinnedRoot, target);
        }
    }

    private Path pinHandoff(Operation operation, Path activeRoot, Path sourceRoot) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path normalizedSource = MigrationPaths.requireDirectory(sourceRoot, "handoffSourceRoot");
        validateRoot(normalizedSource);
        Path parent = normalizedRoot.getParent();
        if (parent == null) {
            throw new IOException("WorldGen Generated Root Has No Parent");
        }
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), normalizedRoot);
        MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), normalizedSource);
        Path temporary = parent.resolve(HANDOFF_STAGING_PREFIX + UUID.randomUUID()).toAbsolutePath().normalize();
        ensureSibling(parent, temporary);
        Path pinnedRoot = null;
        try {
            Files.createDirectory(temporary);
            copyGeneratedContents(normalizedSource, temporary);
            validateRoot(temporary);
            forceTree(temporary);

            Path handoffRoot = normalizedRoot.resolve(HANDOFF_DIRECTORY).toAbsolutePath().normalize();
            MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), normalizedRoot);
            Files.createDirectories(handoffRoot);
            MigrationPaths.requireNoSymlinkTraversal(scopeRoot(), handoffRoot);
            pinnedRoot = handoffRoot.resolve(HANDOFF_PREFIX + operation.id() + "." + UUID.randomUUID())
                .toAbsolutePath().normalize();
            ensureSibling(handoffRoot, pinnedRoot);
            moveRecoverably(temporary, pinnedRoot);
            validateRoot(pinnedRoot);
            Path previous = pinnedHandoffs.get(operation.id());
            if (previous != null) {
                deleteTree(previous);
            }
            pinnedHandoffs.put(operation.id(), pinnedRoot);
            return pinnedRoot;
        } catch (IOException | RuntimeException | Error exception) {
            if (pinnedRoot != null) {
                try {
                    deleteTree(pinnedRoot);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                deleteTree(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static void copyGeneratedContents(Path source, Path target) throws IOException {
        try (var stream = Files.list(source)) {
            for (Path child : stream.toList()) {
                if (HANDOFF_DIRECTORY.equals(child.getFileName().toString())) {
                    continue;
                }
                copyTree(child, target.resolve(child.getFileName()));
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Symbolic Link Is Not Allowed In WorldGen Generated Output: " + source);
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(target);
            try (var stream = Files.list(source)) {
                for (Path child : stream.toList()) {
                    copyTree(child, target.resolve(child.getFileName()));
                }
            }
            return;
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid WorldGen Generated Output File: " + source);
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void requireAdmission(boolean recovery) {
        if (state != State.OPEN || rebuildRequired && !recovery) {
            if (state == State.OPEN && rebuildRequired) {
                throw new IllegalStateException("WorldGen Generated Output Admission Requires Rebuild");
            }
            throw new IllegalStateException("WorldGen Generated Output Admission Is " + state.name());
        }
    }

    private void awaitStateChange() throws IOException {
        try {
            monitor.wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("WorldGen Generated Output Lifecycle Was Interrupted", exception);
        }
    }

    private void fail(String reason, State failureState) {
        synchronized (monitor) {
            state = failureState;
            failureReason = reason == null || reason.isBlank() ? "WorldGen Generated Output Lifecycle Failed" : reason;
            monitor.notifyAll();
        }
    }

    private String failureReason() {
        synchronized (monitor) {
            return failureReason;
        }
    }

    private IOException failure(String fallback) {
        String reason = failureReason();
        return new IOException(reason == null || reason.isBlank() ? fallback : reason);
    }

    private static Path ensureRoot(Path scopeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(scopeRoot, "scopeRoot");
        Path root = WorldGenGeneratedOutputPolicy.root(scope);
        Files.createDirectories(root);
        Path normalized = MigrationPaths.requireDirectory(root, "worldgen generated root");
        MigrationPaths.requireNoSymlinkTraversal(scope, normalized);
        return normalized;
    }

    private static void validateRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "worldgen generated root");
        MigrationPaths.requireNoSymlinkTree(normalized);
    }

    private static void clearGeneratedChildren(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "worldgen generated root");
        Path scope = normalized.getParent() == null || normalized.getParent().getParent() == null
            ? normalized : normalized.getParent().getParent();
        MigrationPaths.requireNoSymlinkTraversal(scope, normalized);
        List<Path> children;
        try (var stream = Files.list(normalized)) {
            children = stream.toList();
        }
        for (Path child : children) {
            deleteTree(child);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Symbolic Link Is Not Allowed In WorldGen Generated Output: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Symbolic Link Is Not Allowed In WorldGen Generated Output: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Invalid WorldGen Generated Output File: " + file);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void forceTree(Path root) throws IOException {
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Invalid WorldGen Generated Output File: " + file);
                }
                AtomicFiles.force(file);
                return FileVisitResult.CONTINUE;
            }
        });
        forceDirectory(root);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException exception) {
        }
    }

    private static void moveRecoverably(Path source, Path target) throws IOException {
        AccessDeniedException failure = null;
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            requireMoveReady(source, target);
            try {
                moveOnce(source, target);
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)
                    || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("WorldGen Generated Output Move Did Not Publish The Expected Directory: "
                        + source + " -> " + target);
                }
                return;
            } catch (AccessDeniedException exception) {
                failure = exception;
                requireRetryableMove(source, target, exception);
                if (attempt + 1 == MOVE_ATTEMPTS) {
                    throw exception;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(Math.min(200L, MOVE_RETRY_MILLIS << Math.min(attempt, 3)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException stopped = new IOException("WorldGen Generated Output Move Was Interrupted", interrupted);
                    stopped.addSuppressed(exception);
                    throw stopped;
                }
            }
        }
        throw failure == null ? new IOException("WorldGen Generated Output Move Failed") : failure;
    }

    private static void moveOnce(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void requireRetryableMove(Path source, Path target, IOException failure) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw failure;
        }
        if (Files.isSymbolicLink(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Output Move Target Already Exists: " + target, failure);
        }
    }

    private static void requireMoveReady(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Output Move Source Is Invalid: " + source);
        }
        if (Files.isSymbolicLink(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Output Move Target Already Exists: " + target);
        }
    }

    private static void rollbackTransaction(Path parent, Path root, Path stage, Path backup) throws IOException {
        boolean rootPresent = validateTransactionDirectory(root, "worldgen generated root");
        boolean stagePresent = validateTransactionDirectory(stage, "worldgen generated stage");
        boolean backupPresent = validateTransactionDirectory(backup, "worldgen generated backup");
        if (!rootPresent && !backupPresent) {
            throw new IOException("WorldGen Generated Transaction Has No Authoritative Root");
        }
        if (rootPresent && stagePresent && backupPresent) {
            throw new IOException("WorldGen Generated Transaction Cannot Roll Back Over An Unexpected Live Root");
        }
        completeRollback(parent, root, stage, backup);
        Files.deleteIfExists(journal(parent));
        forceDirectory(parent);
    }

    private static boolean recoverTransaction(Path parent, Path root) throws IOException {
        Path journal = journal(parent);
        if (Files.isSymbolicLink(journal)) {
            throw new IOException("WorldGen Generated Transaction Journal Is Invalid");
        }
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            return recoverOrphanArtifacts(parent, root);
        }
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Transaction Journal Is Invalid");
        }
        TransactionJournal transaction = readJournal(parent, journal);
        Path stage = transaction.stage();
        Path backup = transaction.backup();
        rejectUnexpectedTransactionArtifacts(parent, stage, backup);
        boolean rootPresent = validateTransactionDirectory(root, "worldgen generated root");
        boolean stagePresent = validateTransactionDirectory(stage, "worldgen generated stage");
        boolean backupPresent = validateTransactionDirectory(backup, "worldgen generated backup");
        switch (transaction.phase()) {
            case PREPARED -> recoverPrepared(parent, root, stage, backup, rootPresent, stagePresent, backupPresent);
            case BACKUP_PENDING -> recoverBackupPending(parent, root, stage, backup, rootPresent, stagePresent,
                backupPresent);
            case BACKED_UP -> recoverBackedUp(parent, root, stage, backup, rootPresent, stagePresent, backupPresent);
            case ACTIVATION_PENDING -> recoverActivationPending(parent, root, stage, backup, rootPresent, stagePresent,
                backupPresent);
            case ACTIVATED -> recoverActivated(parent, root, stage, backup, rootPresent, stagePresent, backupPresent);
            case COMMITTED -> recoverCommitted(root, stage, backup, rootPresent, stagePresent, backupPresent);
            case ROLLBACK_PENDING -> {
                recoverRollbackPending(parent, root, stage, backup);
                writeJournal(parent, TransactionPhase.ROLLED_BACK, stage, backup);
            }
            case ROLLED_BACK -> recoverRolledBack(root, stage, backup, rootPresent, stagePresent, backupPresent);
        }
        boolean recoveryRequired = recoverOrphanArtifacts(parent, root);
        Files.deleteIfExists(journal);
        forceDirectory(parent);
        return recoveryRequired;
    }

    private static boolean recoverOrphanArtifacts(Path parent, Path root) throws IOException {
        Path normalizedParent = MigrationPaths.requireDirectory(parent, "worldgen generated transaction parent");
        Path normalizedRoot = MigrationPaths.requirePath(root, "worldgen generated root");
        List<Path> artifacts;
        try (var stream = Files.list(normalizedParent)) {
            artifacts = stream
                .filter(path -> isTransactionArtifactName(path.getFileName().toString()))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
        for (Path artifact : artifacts) {
            String name = artifact.getFileName().toString();
            if (JOURNAL_FILE.equals(name)) {
                throw new IOException("WorldGen Generated Transaction Journal Disappeared During Recovery");
            }
            if (orphanArtifactKind(name) == null) {
                throw new IOException("WorldGen Generated Transaction Artifact Is Invalid: " + artifact);
            }
        }
        if (artifacts.isEmpty()) {
            Path quarantine = recoveryQuarantine(normalizedParent);
            if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                quarantine = prepareRecoveryQuarantine(normalizedParent);
                if (recoveryQuarantineContainsBackup(quarantine)) {
                    publishStartupRebuildIntent(normalizedParent);
                    return true;
                }
            }
            return false;
        }
        boolean rootPresent = validateTransactionDirectory(normalizedRoot, "worldgen generated root");
        boolean orphanBackupPresent = artifacts.stream()
            .anyMatch(path -> orphanArtifactKind(path.getFileName().toString()) == OrphanArtifactKind.BACKUP);
        if (!rootPresent && orphanBackupPresent) {
            throw ambiguousOrphanRecovery(normalizedRoot, artifacts);
        }
        for (Path artifact : artifacts) {
            validateOrphanArtifact(artifact, "worldgen generated orphan");
        }
        if (orphanBackupPresent) {
            publishStartupRebuildIntent(normalizedParent);
        }
        Path quarantine = prepareRecoveryQuarantine(normalizedParent);
        for (Path artifact : artifacts) {
            Path destination = quarantine.resolve(artifact.getFileName()).toAbsolutePath().normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("WorldGen Generated Recovery Quarantine Destination Is Invalid: " + destination);
            }
        }
        for (Path artifact : artifacts) {
            Path destination = quarantine.resolve(artifact.getFileName()).toAbsolutePath().normalize();
            moveRecoverably(artifact, destination);
            validateOrphanArtifact(destination, "worldgen generated recovery quarantine artifact");
        }
        forceDirectory(quarantine);
        forceDirectory(quarantine.getParent());
        forceDirectory(normalizedParent);
        return !rootPresent || orphanBackupPresent || recoveryQuarantineContainsBackup(quarantine);
    }

    private static Path prepareRecoveryQuarantine(Path parent) throws IOException {
        Path normalizedParent = MigrationPaths.requireDirectory(parent, "worldgen generated transaction parent");
        Path scope = normalizedParent.getParent();
        if (scope == null) {
            throw new IOException("WorldGen Generated Transaction Parent Has No Scope Root");
        }
        Path quarantine = normalizedParent.resolve(RECOVERY_QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(scope, quarantine);
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(quarantine)
                || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("WorldGen Generated Recovery Quarantine Is Invalid: " + quarantine);
            }
        } else {
            Files.createDirectories(quarantine);
            MigrationPaths.requireNoSymlinkTraversal(scope, quarantine);
        }
        try (var stream = Files.list(quarantine)) {
            for (Path artifact : stream.toList()) {
                if (orphanArtifactKind(artifact.getFileName().toString()) == null) {
                    throw new IOException("WorldGen Generated Recovery Quarantine Contains An Unexpected Entry: "
                        + artifact);
                }
                validateOrphanArtifact(artifact, "worldgen generated recovery quarantine artifact");
            }
        }
        return quarantine;
    }

    private static void validateOrphanArtifact(Path artifact, String label) throws IOException {
        if (Files.isSymbolicLink(artifact)) {
            throw new IOException("WorldGen Generated " + label + " Is A Symbolic Link: " + artifact);
        }
        if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)
            || !Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated " + label + " Is Not A Directory: " + artifact);
        }
        validateRoot(artifact);
    }

    private static boolean recoveryQuarantineContainsBackup(Path quarantine) throws IOException {
        try (var stream = Files.list(quarantine)) {
            return stream.anyMatch(path -> orphanArtifactKind(path.getFileName().toString())
                == OrphanArtifactKind.BACKUP);
        }
    }

    private static IOException ambiguousOrphanRecovery(Path root, List<Path> artifacts) {
        return new IOException("WorldGen Generated Orphan Recovery Is Ambiguous Without A Live Root: root="
            + root + ", artifacts=" + artifacts.stream().map(path -> path.getFileName().toString()).toList());
    }

    private static TransactionJournal readJournal(Path parent, Path journal) throws IOException {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(journal))).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("WorldGen Generated Transaction Journal Encoding Is Invalid", exception);
        }
        if (content.indexOf('\u0000') >= 0 || content.indexOf('\r') >= 0) {
            throw new IOException("WorldGen Generated Transaction Journal Grammar Is Invalid");
        }
        String[] lines = content.split("\\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] withoutTerminator = new String[lines.length - 1];
            System.arraycopy(lines, 0, withoutTerminator, 0, withoutTerminator.length);
            lines = withoutTerminator;
        }
        if (lines.length != 4) {
            throw new IOException("WorldGen Generated Transaction Journal Grammar Is Invalid");
        }
        if (!JOURNAL_VERSION.equals(journalValue(lines[0], "version"))) {
            throw new IOException("WorldGen Generated Transaction Journal Version Is Unsupported");
        }
        TransactionPhase phase;
        try {
            phase = TransactionPhase.valueOf(journalValue(lines[1], "phase"));
        } catch (IllegalArgumentException exception) {
            throw new IOException("WorldGen Generated Transaction Journal Phase Is Invalid", exception);
        }
        String stageName = journalValue(lines[2], "stage");
        String backupName = journalValue(lines[3], "backup");
        validateArtifactName(stageName, "generated.stage.");
        validateArtifactName(backupName, "generated.backup.");
        Path stage = sibling(parent, stageName);
        Path backup = sibling(parent, backupName);
        if (stage.equals(backup)) {
            throw new IOException("WorldGen Generated Transaction Journal Uses The Same Stage And Backup");
        }
        return new TransactionJournal(phase, stage, backup);
    }

    private static String journalValue(String line, String key) throws IOException {
        String prefix = key + "=";
        if (!line.startsWith(prefix) || line.indexOf('=', prefix.length()) >= 0) {
            throw new IOException("WorldGen Generated Transaction Journal Grammar Is Invalid");
        }
        String value = line.substring(prefix.length());
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IOException("WorldGen Generated Transaction Journal Grammar Is Invalid");
        }
        return value;
    }

    private static void validateArtifactName(String name, String prefix) throws IOException {
        if (!name.startsWith(prefix) || name.length() != prefix.length() + 36) {
            throw new IOException("WorldGen Generated Transaction Journal Path Is Invalid");
        }
        String uuid = name.substring(prefix.length());
        try {
            if (!UUID.fromString(uuid).toString().equals(uuid)) {
                throw new IOException("WorldGen Generated Transaction Journal Path Is Invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("WorldGen Generated Transaction Journal Path Is Invalid", exception);
        }
    }

    private static void recoverPrepared(Path parent, Path root, Path stage, Path backup,
                                        boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (rootPresent && backupPresent) {
            throw ambiguousRecovery("PREPARED", root, stage, backup);
        }
        if (rootPresent) {
            deleteArtifact(stage, stagePresent, "worldgen generated stage");
            return;
        }
        if (backupPresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        throw ambiguousRecovery("PREPARED", root, stage, backup);
    }

    private static void recoverBackupPending(Path parent, Path root, Path stage, Path backup,
                                             boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (rootPresent && backupPresent) {
            throw ambiguousRecovery("BACKUP_PENDING", root, stage, backup);
        }
        if (rootPresent) {
            deleteArtifact(stage, stagePresent, "worldgen generated stage");
            return;
        }
        if (backupPresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        throw ambiguousRecovery("BACKUP_PENDING", root, stage, backup);
    }

    private static void recoverBackedUp(Path parent, Path root, Path stage, Path backup,
                                        boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (rootPresent && backupPresent && !stagePresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        if (!rootPresent && backupPresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        throw ambiguousRecovery("BACKED_UP", root, stage, backup);
    }

    private static void recoverActivationPending(Path parent, Path root, Path stage, Path backup,
                                                 boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (rootPresent && backupPresent && !stagePresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        if (!rootPresent && backupPresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        throw ambiguousRecovery("ACTIVATION_PENDING", root, stage, backup);
    }

    private static void recoverActivated(Path parent, Path root, Path stage, Path backup,
                                         boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (rootPresent && backupPresent && !stagePresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        if (!rootPresent && backupPresent) {
            completeRollback(parent, root, stage, backup);
            return;
        }
        throw ambiguousRecovery("ACTIVATED", root, stage, backup);
    }

    private static void recoverCommitted(Path root, Path stage, Path backup,
                                         boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (!rootPresent) {
            throw new IOException("WorldGen Generated Transaction Cannot Roll Back A Committed Root: " + root);
        }
        if (stagePresent) {
            throw ambiguousRecovery("COMMITTED", root, stage, backup);
        }
        deleteArtifact(backup, backupPresent, "worldgen generated backup");
    }

    private static void completeRollback(Path parent, Path root, Path stage, Path backup) throws IOException {
        writeJournal(parent, TransactionPhase.ROLLBACK_PENDING, stage, backup);
        recoverRollbackPending(parent, root, stage, backup);
        writeJournal(parent, TransactionPhase.ROLLED_BACK, stage, backup);
    }

    private static void recoverRollbackPending(Path parent, Path root, Path stage, Path backup) throws IOException {
        boolean rootPresent = validateTransactionDirectory(root, "worldgen generated root");
        boolean stagePresent = validateTransactionDirectory(stage, "worldgen generated stage");
        boolean backupPresent = validateTransactionDirectory(backup, "worldgen generated backup");
        if (!rootPresent && !backupPresent) {
            throw ambiguousRecovery("ROLLBACK_PENDING", root, stage, backup);
        }
        if (rootPresent && stagePresent && backupPresent) {
            throw ambiguousRecovery("ROLLBACK_PENDING", root, stage, backup);
        }
        if (backupPresent) {
            if (rootPresent) {
                deleteTree(root);
            }
            restoreBackup(root, backup);
        }
        deleteArtifact(stage, stagePresent, "worldgen generated stage");
        validateTransactionDirectory(root, "worldgen generated rolled back root");
        forceDirectory(parent);
    }

    private static void recoverRolledBack(Path root, Path stage, Path backup,
                                          boolean rootPresent, boolean stagePresent, boolean backupPresent)
        throws IOException {
        if (!rootPresent || stagePresent || backupPresent) {
            throw ambiguousRecovery("ROLLED_BACK", root, stage, backup);
        }
    }

    private static void restoreBackup(Path root, Path backup) throws IOException {
        if (Files.isSymbolicLink(root) || Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Transaction Cannot Restore Over A Live Root");
        }
        if (!validateTransactionDirectory(backup, "worldgen generated backup")) {
            throw new IOException("WorldGen Generated Transaction Backup Is Missing");
        }
        moveRecoverably(backup, root);
        validateTransactionDirectory(root, "worldgen generated root");
    }

    private static void deleteArtifact(Path artifact, boolean present, String label) throws IOException {
        if (present) {
            validateTransactionDirectory(artifact, label);
            deleteTree(artifact);
        }
    }

    private static boolean validateTransactionDirectory(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("WorldGen Generated Transaction " + label + " Is A Symbolic Link");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Transaction " + label + " Is Invalid");
        }
        validateRoot(path);
        return true;
    }

    private static void rejectUnexpectedTransactionArtifacts(Path parent, Path stage, Path backup)
        throws IOException {
        try (var stream = Files.list(parent)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (!isTransactionArtifactName(name)) {
                    continue;
                }
                if (orphanArtifactKind(name) == OrphanArtifactKind.HANDOFF) {
                    continue;
                }
                if ((stage == null || !child.equals(stage)) && (backup == null || !child.equals(backup))) {
                    throw new IOException("WorldGen Generated Transaction Artifact Is Unexpected: " + child);
                }
            }
        }
    }

    private static boolean isTransactionArtifactName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return (normalized.startsWith("generated.")
            || normalized.startsWith(HANDOFF_STAGING_PREFIX.toLowerCase(Locale.ROOT)))
            && !normalized.equals(JOURNAL_FILE)
            && !normalized.equals(STARTUP_REBUILD_INTENT_FILE)
            && controllerAtomicTarget(name) == null;
    }

    static boolean ownsRecoveryArtifact(Path parent, Path candidate) {
        Path normalizedParent = MigrationPaths.requirePath(parent, "parent");
        Path normalizedCandidate = MigrationPaths.requirePath(candidate, "candidate");
        return ownsRecoveryArtifactResolved(normalizedParent, normalizedCandidate);
    }

    static boolean ownsRecoveryArtifactResolved(Path parent, Path candidate) {
        if (candidate.getParent() == null || !candidate.getParent().equals(parent)) {
            return false;
        }
        String name = candidate.getFileName().toString();
        return STARTUP_REBUILD_INTENT_FILE.equals(name)
            || controllerAtomicTarget(name) != null
            || orphanArtifactKind(name) != null;
    }

    static Path controllerAtomicTemporary(Path target, UUID id) {
        Path normalizedTarget = MigrationPaths.requirePath(target, "target");
        Path parent = normalizedTarget.getParent();
        if (parent == null || controllerAtomicTargetFile(normalizedTarget.getFileName().toString()) == null) {
            throw new IllegalArgumentException("WorldGen Generated Controller Atomic Target Is Invalid");
        }
        UUID normalizedId = Objects.requireNonNull(id, "id");
        return parent.resolve(normalizedTarget.getFileName() + ATOMIC_TEMP_SEPARATOR + normalizedId
            + ATOMIC_TEMP_SUFFIX).toAbsolutePath().normalize();
    }

    static Path startupRebuildIntent(Path parent) {
        return MigrationPaths.requirePath(parent, "parent").resolve(STARTUP_REBUILD_INTENT_FILE)
            .toAbsolutePath().normalize();
    }

    private static boolean startupRebuildIntentPresent(Path parent) throws IOException {
        Path intent = startupRebuildIntent(parent);
        if (Files.isSymbolicLink(intent)) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Is Invalid");
        }
        if (!Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(intent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Is Invalid");
        }
        String content = Files.readString(intent, StandardCharsets.UTF_8);
        if (!startupRebuildIntentContent().equals(content)) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Is Invalid");
        }
        return true;
    }

    private static String startupRebuildIntentContent() {
        return "version=" + STARTUP_REBUILD_INTENT_VERSION + "\nowner="
            + WorldGenGeneratedOutputPolicy.OWNER + "\n";
    }

    private static void publishStartupRebuildIntent(Path parent) throws IOException {
        Path intent = startupRebuildIntent(parent);
        writeControllerFile(intent, startupRebuildIntentContent().getBytes(StandardCharsets.UTF_8), true);
        AtomicFiles.force(intent);
        forceDirectory(parent);
        recoverControllerAtomicTemporaries(parent, ControllerAtomicTarget.STARTUP_REBUILD_INTENT);
    }

    private static boolean recoverControllerAtomicTemporaries(Path parent) throws IOException {
        return recoverControllerAtomicTemporaries(parent, null);
    }

    private static boolean recoverControllerAtomicTemporaries(Path parent, ControllerAtomicTarget expected)
        throws IOException {
        Path normalizedParent = MigrationPaths.requireDirectory(parent, "worldgen generated controller parent");
        Map<ControllerAtomicTarget, List<Path>> temporaries = new LinkedHashMap<>();
        try (var stream = Files.list(normalizedParent)) {
            for (Path candidate : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                ControllerAtomicTarget target = controllerAtomicTarget(candidate.getFileName().toString());
                if (target != null && (expected == null || expected == target)) {
                    temporaries.computeIfAbsent(target, ignored -> new ArrayList<>()).add(candidate);
                }
            }
        }
        boolean recoveryRequired = false;
        for (Map.Entry<ControllerAtomicTarget, List<Path>> entry : temporaries.entrySet()) {
            recoveryRequired |= recoverControllerAtomicTemporaries(normalizedParent, entry.getKey(), entry.getValue());
        }
        return recoveryRequired;
    }

    private static boolean recoverControllerAtomicTemporaries(Path parent, ControllerAtomicTarget kind,
                                                               List<Path> temporaries)
        throws IOException {
        Path target = parent.resolve(kind.fileName()).toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            validateControllerFile(parent, kind, target);
            for (Path temporary : temporaries) {
                requireControllerAtomicTemporary(temporary);
                Files.delete(temporary);
            }
            forceDirectory(parent);
            return false;
        }
        if (temporaries.size() != 1) {
            return true;
        }
        Path temporary = temporaries.get(0);
        requireControllerAtomicTemporary(temporary);
        try {
            validateControllerFile(parent, kind, temporary);
        } catch (IOException invalid) {
            return true;
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("WorldGen Generated Controller Atomic Recovery Is Not Supported: " + target,
                exception);
        }
        forceDirectory(parent);
        validateControllerFile(parent, kind, target);
        return false;
    }

    private static void requireControllerAtomicTemporary(Path temporary) throws IOException {
        if (Files.isSymbolicLink(temporary)
            || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Controller Atomic Temporary Is Invalid: " + temporary);
        }
    }

    private static void retireInvalidJournalAtomicTemporariesForStartup(Path parent) throws IOException {
        Path normalizedParent = MigrationPaths.requireDirectory(parent, "worldgen generated controller parent");
        Path target = journal(normalizedParent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (recoverControllerAtomicTemporaries(normalizedParent, ControllerAtomicTarget.JOURNAL)) {
                throw new IOException("WorldGen Generated Journal Recovery Remains Ambiguous During Startup Rebuild");
            }
            return;
        }
        List<Path> temporaries;
        try (var stream = Files.list(normalizedParent)) {
            temporaries = stream
                .filter(path -> controllerAtomicTarget(path.getFileName().toString()) == ControllerAtomicTarget.JOURNAL)
                .toList();
        }
        for (Path temporary : temporaries) {
            requireControllerAtomicTemporary(temporary);
            boolean valid;
            try {
                validateControllerFile(normalizedParent, ControllerAtomicTarget.JOURNAL, temporary);
                valid = true;
            } catch (IOException invalid) {
                valid = false;
            }
            if (valid) {
                throw new IOException("WorldGen Generated Valid Pending Journal Cannot Be Retired During Startup Rebuild");
            }
            Files.delete(temporary);
        }
        if (!temporaries.isEmpty()) {
            forceDirectory(normalizedParent);
        }
    }

    private static void validateControllerFile(Path parent, ControllerAtomicTarget kind, Path file)
        throws IOException {
        if (kind == ControllerAtomicTarget.JOURNAL) {
            readJournal(parent, file);
            return;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !startupRebuildIntentContent().equals(Files.readString(file, StandardCharsets.UTF_8))) {
            throw new IOException("WorldGen Generated Startup Rebuild Intent Is Invalid");
        }
    }

    private static ControllerAtomicTarget controllerAtomicTarget(String name) {
        if (name == null || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return null;
        }
        for (ControllerAtomicTarget target : ControllerAtomicTarget.values()) {
            String prefix = target.fileName() + ATOMIC_TEMP_SEPARATOR;
            if (name.startsWith(prefix) && name.length() == prefix.length() + 36 + ATOMIC_TEMP_SUFFIX.length()) {
                String id = name.substring(prefix.length(), name.length() - ATOMIC_TEMP_SUFFIX.length());
                try {
                    if (UUID.fromString(id).toString().equals(id)) {
                        return target;
                    }
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ControllerAtomicTarget controllerAtomicTargetFile(String name) {
        for (ControllerAtomicTarget target : ControllerAtomicTarget.values()) {
            if (target.fileName().equals(name)) {
                return target;
            }
        }
        return null;
    }

    static Path recoveryQuarantine(Path parent) {
        return recoveryQuarantineResolved(MigrationPaths.requirePath(parent, "parent"));
    }

    static Path recoveryQuarantineResolved(Path parent) {
        return parent.resolve(RECOVERY_QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
    }

    static boolean ownsRecoveryQuarantine(Path quarantine, Path candidate) {
        Path normalizedQuarantine = MigrationPaths.requirePath(quarantine, "quarantine");
        Path normalizedCandidate = MigrationPaths.requirePath(candidate, "candidate");
        return ownsRecoveryQuarantineResolved(normalizedQuarantine, normalizedCandidate);
    }

    static boolean ownsRecoveryQuarantineResolved(Path quarantine, Path candidate) {
        if (!candidate.startsWith(quarantine) || candidate.equals(quarantine)) {
            return false;
        }
        Path relative = quarantine.relativize(candidate);
        if (relative.getNameCount() == 0) {
            return false;
        }
        String artifactName = relative.getName(0).toString();
        return orphanArtifactKind(artifactName) != null;
    }

    private static OrphanArtifactKind orphanArtifactKind(String name) {
        if (isCanonicalArtifactName(name, "generated.stage.")) {
            return OrphanArtifactKind.STAGE;
        }
        if (isCanonicalArtifactName(name, "generated.backup.")) {
            return OrphanArtifactKind.BACKUP;
        }
        if (isCanonicalArtifactName(name, HANDOFF_STAGING_PREFIX)) {
            return OrphanArtifactKind.HANDOFF;
        }
        return null;
    }

    private static boolean isCanonicalArtifactName(String name, String prefix) {
        if (name == null || !name.startsWith(prefix) || name.length() != prefix.length() + 36) {
            return false;
        }
        String uuid = name.substring(prefix.length());
        try {
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static IOException ambiguousRecovery(String phase, Path root, Path stage, Path backup) {
        return new IOException("WorldGen Generated Transaction Recovery Is Ambiguous At " + phase + ": root="
            + root.getFileName() + ", stage=" + stage.getFileName() + ", backup=" + backup.getFileName());
    }

    private static void writeJournal(Path parent, TransactionPhase phase, Path stage, Path backup) throws IOException {
        String content = "version=" + JOURNAL_VERSION + "\nphase=" + phase.name() + "\nstage="
            + stage.getFileName() + "\nbackup=" + backup.getFileName() + "\n";
        writeControllerFile(journal(parent), content.getBytes(StandardCharsets.UTF_8));
        forceDirectory(parent);
    }

    private static void writeControllerFile(Path target, byte[] content) throws IOException {
        writeControllerFile(target, content, false);
    }

    private static void writeControllerFile(Path target, byte[] content, boolean publishOverRecoveryEvidence)
        throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "target");
        Path parent = normalizedTarget.getParent();
        ControllerAtomicTarget targetKind = controllerAtomicTargetFile(normalizedTarget.getFileName().toString());
        if (parent == null || targetKind == null) {
            throw new IOException("WorldGen Generated Controller Atomic Target Is Invalid: " + normalizedTarget);
        }
        MigrationPaths.requireDirectory(parent, "worldgen generated controller parent");
        if (!publishOverRecoveryEvidence && recoverControllerAtomicTemporaries(parent, targetKind)) {
            throw new IOException("WorldGen Generated Controller Atomic Recovery Requires A Proof-Scoped Rebuild: "
                + normalizedTarget);
        }
        Path temporary = null;
        IOException collision = null;
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = controllerAtomicTemporary(normalizedTarget, UUID.randomUUID());
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
                temporary = candidate;
                break;
            } catch (FileAlreadyExistsException exception) {
                collision = exception;
            } catch (IOException | RuntimeException exception) {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }
        if (temporary == null) {
            throw new IOException("WorldGen Generated Controller Atomic Temporary Could Not Be Reserved: "
                + normalizedTarget, collision);
        }
        boolean publicationAttempted = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(Objects.requireNonNull(content, "content"));
                while (buffer.hasRemaining()) {
                    int before = buffer.position();
                    int written = channel.write(buffer);
                    if (written <= 0 || buffer.position() - before != written) {
                        throw new IOException("WorldGen Generated Controller Atomic Write Made No Progress: "
                            + normalizedTarget);
                    }
                }
                channel.force(true);
            }
            publicationAttempted = true;
            Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(parent);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("WorldGen Generated Controller Atomic Publication Is Not Supported: "
                + normalizedTarget, exception);
        } catch (IOException | RuntimeException exception) {
            if (!publicationAttempted) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private static Path journal(Path parent) {
        return parent.resolve(JOURNAL_FILE).toAbsolutePath().normalize();
    }

    private static Path sibling(Path parent, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
            || fileName.equals(".") || fileName.equals("..")) {
            throw new IOException("WorldGen Generated Transaction Journal Path Is Invalid");
        }
        Path candidate = parent.resolve(fileName).toAbsolutePath().normalize();
        if (!candidate.getParent().equals(parent)) {
            throw new IOException("WorldGen Generated Transaction Journal Escapes Its Parent");
        }
        return candidate;
    }

    private static void ensureSibling(Path parent, Path candidate) throws IOException {
        if (!candidate.getParent().equals(parent) || Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Generated Transaction Sibling Is Invalid: " + candidate);
        }
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " Must Be Non-Negative");
        }
        return timeout;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }

    private static String reason(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        RESUMING,
        REBINDING,
        DEGRADED,
        FAILED,
        CLOSED
    }

    public record Health(boolean available, State state, int activeOperations, long generation, String failureReason) {
        public Health {
            failureReason = failureReason == null ? "" : failureReason.trim();
        }
    }

    public record TransactionResult<T>(T value, Path stagingRoot, Path activeRoot) {
        public TransactionResult {
            Objects.requireNonNull(value, "value");
            stagingRoot = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
            activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
        }

        public Path activePath(Path stagedPath) {
            Path candidate = MigrationPaths.requirePath(stagedPath, "stagedPath");
            if (!candidate.equals(stagingRoot) && !candidate.startsWith(stagingRoot)) {
                throw new IllegalArgumentException("Staged Path Is Outside Transaction Root: " + candidate);
            }
            return activeRoot.resolve(stagingRoot.relativize(candidate)).normalize();
        }
    }

    private record TransactionExecution<T>(TransactionResult<T> transaction, Path snapshotRoot) {
        private TransactionExecution {
            Objects.requireNonNull(transaction, "transaction");
        }
    }

    @FunctionalInterface
    public interface Rebuilder {
        void rebuild(Path generatedRoot) throws IOException;

        default void healthCheck() throws IOException {
        }
    }

    @FunctionalInterface
    public interface TransactionWriter<T> {
        T apply(Path stagingRoot) throws IOException;
    }

    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface TransactionVerifier {
        void verify() throws IOException;
    }

    public final class Lease implements AutoCloseable {
        private final Operation operation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CancellationToken token = new CancellationToken();
        private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
        private volatile Thread runner;

        private Lease(Operation operation) {
            this.operation = operation;
        }

        public String operation() {
            return operation.operation();
        }

        public boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }

        public CancellationToken token() {
            return token;
        }

        public boolean isClosed() {
            return closed.get();
        }

        public MigrationFence.MutationLease mutationLease() {
            if (closed.get()) {
                throw new IllegalStateException("WorldGen Generated Output Lease Is Closed");
            }
            return operation.mutation();
        }

        public void onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (!cancellation.compareAndSet(null, callback)) {
                throw new IllegalStateException("WorldGen Generated Output Lease Cancellation Callback Is Already Set");
            }
            if (cancelled.get()) {
                callback.run();
            }
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                token.cancel();
                Runnable callback = cancellation.get();
                if (callback != null) {
                    callback.run();
                }
                Thread activeRunner = runner;
                if (activeRunner != null) {
                    activeRunner.interrupt();
                }
            }
        }

        private void setRunner(Thread runner) {
            this.runner = runner;
            if (cancelled.get() && runner != null) {
                runner.interrupt();
            }
        }

        private Operation operationRecord() {
            return operation;
        }

        private void throwIfCancellationRequested() {
            token.throwIfCancellationRequested();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(operation);
            }
        }
    }

    public final class Handoff implements AutoCloseable {
        private final Lease lease;

        private Handoff(Lease lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        public String operation() {
            return lease.operation();
        }

        public boolean isCancellationRequested() {
            return lease.isCancellationRequested();
        }

        public CancellationToken token() {
            return lease.token();
        }

        public MigrationFence.MutationLease fenceLease() {
            if (lease.isClosed()) {
                throw new IllegalStateException("WorldGen Generated Output Handoff Is Closed");
            }
            return lease.operation.mutation();
        }

        public boolean isClosed() {
            return lease.isClosed();
        }

        public MigrationFence.MutationLease mutationLease() {
            return lease.mutationLease();
        }

        public void onCancel(Runnable callback) {
            lease.onCancel(callback);
        }

        public void cancel() {
            lease.cancel();
        }

        public void attachRunner(Thread runner) {
            lease.setRunner(Objects.requireNonNull(runner, "runner"));
        }

        public void detachRunner() {
            lease.setRunner(null);
        }

        public void throwIfCancellationRequested() {
            lease.throwIfCancellationRequested();
        }

        public <T> TransactionResult<T> transact(TransactionWriter<T> writer) throws IOException {
            Objects.requireNonNull(writer, "writer");
            if (lease.isClosed()) {
                throw new IllegalStateException("WorldGen Generated Output Handoff Is Closed");
            }
            attachRunner(Thread.currentThread());
            try {
                throwIfCancellationRequested();
                TransactionResult<T> result = executeHandoffTransaction(lease, writer);
                throwIfCancellationRequested();
                return result;
            } finally {
                detachRunner();
            }
        }

        @Override
        public void close() {
            lease.close();
        }
    }

    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        public void throwIfCancellationRequested() {
            if (isCancellationRequested()) {
                throw new CancellationException("WorldGen Generated Output Operation Was Cancelled");
            }
        }

        private void cancel() {
            cancelled.set(true);
        }
    }

    private record Operation(long id, String operation, MigrationFence.MutationLease mutation) {
        private void closeMutation() {
            mutation.close();
        }
    }

    private record TransactionJournal(TransactionPhase phase, Path stage, Path backup) {
    }

    private enum OrphanArtifactKind {
        STAGE,
        BACKUP,
        HANDOFF
    }

    private enum ControllerAtomicTarget {
        JOURNAL(JOURNAL_FILE),
        STARTUP_REBUILD_INTENT(STARTUP_REBUILD_INTENT_FILE);

        private final String fileName;

        ControllerAtomicTarget(String fileName) {
            this.fileName = fileName;
        }

        private String fileName() {
            return fileName;
        }
    }

    private enum TransactionPhase {
        PREPARED,
        BACKUP_PENDING,
        BACKED_UP,
        ACTIVATION_PENDING,
        ACTIVATED,
        COMMITTED,
        ROLLBACK_PENDING,
        ROLLED_BACK
    }
}
