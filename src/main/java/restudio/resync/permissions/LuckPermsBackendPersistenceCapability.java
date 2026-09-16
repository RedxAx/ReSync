package restudio.resync.permissions;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class LuckPermsBackendPersistenceCapability implements AutoCloseable {
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final Object monitor = new Object();
    private final Adapter adapter;
    private final boolean adapterConfigured;
    private Path activeRoot;
    private BackendIdentity activeIdentity;
    private AdapterContract activeContract;
    private State state = State.OPEN;
    private int activeAdmissions;
    private long generation;
    private String failureReason = "";
    private SnapshotManifest lastSnapshot;
    private PendingRebind pendingRebind;
    private boolean rebindRollbackPending;
    private boolean externalQuiesced;
    private boolean externalStateUnknown;

    public LuckPermsBackendPersistenceCapability(Adapter adapter) {
        this(adapter == null ? new UnavailableAdapter("LuckPerms backend persistence adapter is unavailable") : adapter,
            adapter != null);
    }

    private LuckPermsBackendPersistenceCapability(Adapter adapter, boolean adapterConfigured) {
        this.adapterConfigured = adapterConfigured;
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        try {
            this.activeRoot = normalizeOptional(this.adapter.activeRoot());
            this.activeIdentity = readAdapterIdentity();
            this.activeContract = readAdapterContract();
            support();
        } catch (RuntimeException exception) {
            this.activeRoot = null;
            this.activeIdentity = null;
            this.activeContract = null;
            this.failureReason = "LuckPerms backend adapter identity could not be read";
            this.state = State.FAILED;
        }
    }

    public static LuckPermsBackendPersistenceCapability unavailable() {
        return unavailable("LuckPerms backend persistence adapter is unavailable");
    }

    public static LuckPermsBackendPersistenceCapability unavailable(String reason) {
        return new LuckPermsBackendPersistenceCapability(new UnavailableAdapter(reason), false);
    }

    public static LuckPermsBackendPersistenceCapability noOp() {
        return unavailable("LuckPerms backend persistence is not configured");
    }

    public Adapter adapter() {
        return adapter;
    }

    public boolean adapterConfigured() {
        return adapterConfigured;
    }

    public Optional<Path> activeRoot() {
        synchronized (monitor) {
            return Optional.ofNullable(activeRoot);
        }
    }

    public BackendIdentity backendIdentity() {
        synchronized (monitor) {
            return activeIdentity == null ? readAdapterIdentity() : activeIdentity;
        }
    }

    public BackendIdentity identity() {
        return backendIdentity();
    }

    public AdapterContract adapterContract() {
        synchronized (monitor) {
            return activeContract == null ? readAdapterContract() : activeContract;
        }
    }

    public boolean configured() {
        synchronized (monitor) {
            return state != State.FAILED && state != State.CLOSED && support().configured();
        }
    }

    public boolean admissionOpen() {
        synchronized (monitor) {
            support();
            return state == State.OPEN;
        }
    }

    public boolean normalMutationAdmissionOpen() {
        return admissionOpen();
    }

    public boolean replacementReady() {
        synchronized (monitor) {
            Support support = support();
            return lifecycleReady() && replacementReady(support);
        }
    }

    public boolean available() {
        synchronized (monitor) {
            Support support = support();
            return lifecycleReady() && replacementReady(support);
        }
    }

    public boolean snapshotReady() {
        synchronized (monitor) {
            Support support = support();
            return lifecycleReady() && snapshotReady(support);
        }
    }

    public boolean rebindReady() {
        synchronized (monitor) {
            Support support = support();
            return lifecycleReady() && rebindReady(support);
        }
    }

    public boolean restoreReady() {
        synchronized (monitor) {
            Support support = support();
            return lifecycleReady() && restoreReady(support);
        }
    }

    public String failureReason() {
        synchronized (monitor) {
            return failureReason;
        }
    }

    public Readiness readiness() {
        synchronized (monitor) {
            Support support = support();
            String adapterId = adapterId();
            boolean lifecycleReady = lifecycleReady();
            String reason = failureReason.isBlank() ? support.reason() : failureReason;
            return new Readiness(lifecycleReady && replacementReady(support), lifecycleReady && snapshotReady(support),
                lifecycleReady && restoreReady(support), lifecycleReady && rebindReady(support), state, adapterId,
                activeRoot, reason, generation, activeAdmissions);
        }
    }

    public Health health() {
        Support support;
        State current;
        int admissions;
        long currentGeneration;
        Path root;
        String adapterId;
        boolean pending;
        synchronized (monitor) {
            support = support();
            current = state;
            admissions = activeAdmissions;
            currentGeneration = generation;
            root = activeRoot;
            adapterId = adapterId();
            pending = pendingRebind != null;
        }
        boolean healthy = (current == State.OPEN || current == State.QUIESCED)
            && !pending && replacementReady(support) && checkAdapterHealth(support);
        String reason;
        synchronized (monitor) {
            if ((state != State.OPEN && state != State.QUIESCED) || pendingRebind != null) {
                healthy = false;
            }
            reason = healthy ? failureReason : failureReason.isBlank() ? support.reason() : failureReason;
            current = state;
        }
        return new Health(healthy, current, admissions, currentGeneration, root, adapterId, reason);
    }

    public Admission acquire(String operation) {
        String name = requireText(operation, "operation");
        synchronized (monitor) {
            support();
            if (state != State.OPEN) {
                throw new IllegalStateException("LuckPerms backend persistence admission is "
                    + state.name().toLowerCase());
            }
            activeAdmissions++;
            return new Admission(this, name);
        }
    }

    public Optional<Admission> tryAcquire(String operation) {
        String name = requireText(operation, "operation");
        synchronized (monitor) {
            support();
            if (state != State.OPEN) {
                return Optional.empty();
            }
            activeAdmissions++;
            return Optional.of(new Admission(this, name));
        }
    }

    public <T> CompletableFuture<T> trackMutation(String operation, Supplier<? extends CompletionStage<T>> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        Admission admission = acquire(operation);
        try {
            CompletionStage<T> stage = Objects.requireNonNull(mutation.get(), "mutation stage");
            return stage.toCompletableFuture().whenComplete((result, failure) -> admission.close());
        } catch (RuntimeException exception) {
            admission.close();
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void flush() throws IOException {
        Support support;
        synchronized (monitor) {
            requireNotClosed();
            support = support();
            if (state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence support is unavailable: " + failureReason);
            }
            if (state == State.QUIESCING) {
                throw new IOException("LuckPerms backend persistence is quiescing");
            }
        }
        if (support.configured() && support.flush()) {
            try {
                invokeAdapter(adapter::flush, "flush");
            } catch (IOException exception) {
                markDegraded(exception);
                throw exception;
            }
        }
    }

    public void quiesce() throws IOException {
        quiesce(DEFAULT_DRAIN_TIMEOUT);
    }

    public void quiesce(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        Support support;
        boolean priorExternalQuiesced;
        synchronized (monitor) {
            support = support();
            if (state == State.QUIESCED) {
                return;
            }
            if (state == State.CLOSED || state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence is " + state.name().toLowerCase());
            }
            if (state == State.QUIESCING) {
                throw new IOException("LuckPerms backend persistence is quiescing");
            }
            priorExternalQuiesced = externalQuiesced;
        }
        if (priorExternalQuiesced) {
            try {
                invokeAdapter(adapter::resume, "resume before quiesce retry");
                synchronized (monitor) {
                    externalQuiesced = false;
                    externalStateUnknown = false;
                }
            } catch (IOException exception) {
                markDegraded(exception);
                throw exception;
            }
        }
        synchronized (monitor) {
            state = State.QUIESCING;
        }

        IOException failure = awaitAdmissions(wait);
        if (failure == null) {
            synchronized (monitor) {
                support = support();
                if (state == State.FAILED) {
                    failure = new IOException("LuckPerms backend persistence support is unavailable: " + failureReason);
                }
            }
        }
        boolean quiesceAttempted = false;
        if (failure == null && support.configured() && support.quiesce()) {
            if (!hasExternalControl()) {
                failure = new IOException("LuckPerms backend adapter cannot compensate external quiesce");
            } else {
                quiesceAttempted = true;
                synchronized (monitor) {
                    externalQuiesced = true;
                }
                try {
                    invokeAdapter(adapter::quiesce, "quiesce");
                    synchronized (monitor) {
                        externalStateUnknown = false;
                    }
                } catch (IOException exception) {
                    failure = exception;
                }
            }
        }
        if (failure == null && support.configured() && support.flush()) {
            try {
                invokeAdapter(adapter::flush, "flush");
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (failure == null) {
            synchronized (monitor) {
                state = State.QUIESCED;
                failureReason = "";
                monitor.notifyAll();
            }
            return;
        }

        if (quiesceAttempted) {
            IOException compensation = compensateExternalResume();
            if (compensation != null) {
                failure.addSuppressed(compensation);
            }
        }
        synchronized (monitor) {
            failureReason = reason(failure);
            if (state != State.FAILED && state != State.CLOSED) {
                state = State.DEGRADED;
            }
            monitor.notifyAll();
        }
        throw failure;
    }

    public void drain() throws IOException {
        quiesce();
    }

    public void drain(Duration timeout) throws IOException {
        quiesce(timeout);
    }

    public void resume() throws IOException {
        Support support;
        PendingRebind pending;
        boolean rollbackPending;
        boolean resumeExternal;
        boolean externalStateNeedsRepair;
        boolean candidateHealthChecked = false;
        synchronized (monitor) {
            support = support();
            if (state == State.OPEN) {
                return;
            }
            if (state == State.CLOSED || state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence is " + state.name().toLowerCase());
            }
            if (state == State.QUIESCING) {
                throw new IOException("LuckPerms backend persistence is quiescing");
            }
            if (activeAdmissions > 0) {
                throw new IOException("LuckPerms backend persistence still has active admissions");
            }
            if (state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence support is unavailable: " + failureReason);
            }
            pending = pendingRebind;
            rollbackPending = rebindRollbackPending;
            resumeExternal = externalQuiesced;
            externalStateNeedsRepair = externalStateUnknown;
        }

        boolean rollbackAttempted = false;
        try {
            if (externalStateNeedsRepair) {
                invokeAdapter(adapter::quiesce, "quiesce before resume retry");
                synchronized (monitor) {
                    externalQuiesced = true;
                    externalStateUnknown = false;
                }
                resumeExternal = true;
            }
            if (rollbackPending && pending != null) {
                PendingRebind rollback = pending;
                rollbackAttempted = true;
                if (!isExternalQuiesced()) {
                    invokeAdapter(adapter::quiesce, "quiesce before rebind rollback");
                    synchronized (monitor) {
                        externalQuiesced = true;
                        externalStateUnknown = false;
                    }
                    resumeExternal = true;
                }
                invokeAdapter(() -> adapter.rebind(rollback.previous, rollback.manifest.receipt()), "rebind rollback retry");
                synchronized (monitor) {
                    pendingRebind = null;
                    rebindRollbackPending = false;
                }
                pending = null;
            }
            if (pending != null) {
                if (!isExternalQuiesced()) {
                    throw new IOException("LuckPerms backend external writers are not quiesced for rebind resume");
                }
                if (support.configured() && support.healthCheck()) {
                    invokeAdapter(adapter::healthCheck, "candidate health check");
                    candidateHealthChecked = true;
                }
                BackendIdentity identity = readAdapterIdentity();
                AdapterContract contract = readAdapterContract();
                if (!pending.manifest.backendIdentity().equals(identity)
                    || !pending.manifest.adapterContract().equals(contract)) {
                    throw new IOException("LuckPerms backend identity changed while resuming");
                }
            }
            if (resumeExternal) {
                synchronized (monitor) {
                    externalStateUnknown = true;
                }
                try {
                    invokeAdapter(adapter::resume, "resume");
                    synchronized (monitor) {
                        externalQuiesced = false;
                        externalStateUnknown = false;
                    }
                } catch (IOException failure) {
                    IOException compensation = compensateExternalQuiesce();
                    if (compensation != null) {
                        failure.addSuppressed(compensation);
                    }
                    throw failure;
                }
            }
            if (!candidateHealthChecked && support.configured() && support.healthCheck()) {
                invokeAdapter(adapter::healthCheck, "health check");
            }
            if (pending != null) {
                synchronized (monitor) {
                    activeRoot = pending.target;
                    activeIdentity = pending.manifest.backendIdentity();
                    activeContract = pending.manifest.adapterContract();
                    generation++;
                    pendingRebind = null;
                }
            }
            synchronized (monitor) {
                state = State.OPEN;
                failureReason = "";
                monitor.notifyAll();
            }
        } catch (IOException | RuntimeException failure) {
            IOException rollbackFailure = null;
            if (pending != null) {
                if (!isExternalQuiesced()) {
                    rollbackFailure = new IOException("LuckPerms backend external writers are not quiesced for rollback");
                } else {
                    rollbackFailure = rollbackAttempted
                        ? failure instanceof IOException exception ? exception : new IOException("LuckPerms backend rebind rollback failed", failure)
                        : rollbackRebind(pending.previous, pending.manifest.receipt());
                }
            }
            synchronized (monitor) {
                if (pending != null) {
                    activeRoot = pending.previous;
                    activeIdentity = pending.previousIdentity;
                    activeContract = pending.previousContract;
                    if (rollbackFailure == null) {
                        pendingRebind = null;
                        rebindRollbackPending = false;
                    } else {
                        rebindRollbackPending = true;
                    }
                }
                failureReason = reason(failure);
                state = State.DEGRADED;
                monitor.notifyAll();
            }
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof IOException exception) {
                throw exception;
            }
            throw new IOException("LuckPerms backend resume failed", failure);
        }
    }

    public SnapshotManifest backup(Path snapshotRoot) throws IOException {
        Path target = MigrationPaths.requirePath(snapshotRoot, "snapshotRoot");
        BackendIdentity identity;
        AdapterContract contract;
        long snapshotGeneration;
        synchronized (monitor) {
            requireQuiesced();
            requireNoPendingRebind();
            requireSnapshotSupport();
            identity = activeIdentity == null ? readAdapterIdentity() : activeIdentity;
            contract = activeContract == null ? readAdapterContract() : activeContract;
            snapshotGeneration = generation;
        }
        try {
            SnapshotReceipt receipt = Objects.requireNonNull(adapter.backup(target, snapshotGeneration),
                "backend adapter snapshot receipt");
            if (!identity.equals(receipt.backendIdentity()) || snapshotGeneration != receipt.generation()
                || !contract.equals(receipt.adapterContract())) {
                throw new IOException("LuckPerms backend snapshot receipt is not bound to the active backend");
            }
            verifyReceipt(receipt);
            SnapshotManifest manifest = new SnapshotManifest(identity, snapshotGeneration, contract, receipt);
            synchronized (monitor) {
                lastSnapshot = manifest;
            }
            return manifest;
        } catch (IOException exception) {
            markDegraded(exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException wrapped = new IOException("LuckPerms backend backup failed", exception);
            markDegraded(wrapped);
            throw wrapped;
        }
    }

    public void restore(SnapshotManifest manifest) throws IOException {
        try {
            SnapshotManifest bound = requireManifest(manifest, "restore");
            invokeAdapter(() -> adapter.restore(bound.receipt()), "restore");
        } catch (IOException exception) {
            markDegraded(exception);
            throw exception;
        }
    }

    public void restore(Path snapshotRoot) throws IOException {
        try {
            restore(resolveManifest(snapshotRoot));
        } catch (IOException exception) {
            markDegraded(exception);
            throw exception;
        }
    }

    public void rebind(SnapshotManifest manifest, Path nextRoot) throws IOException {
        SnapshotManifest bound;
        try {
            bound = requireManifest(manifest, "rebind");
        } catch (IOException exception) {
            markDegraded(exception);
            throw exception;
        }
        Path target = MigrationPaths.requirePath(nextRoot, "activeRoot");
        Path previous;
        BackendIdentity previousIdentity;
        AdapterContract previousContract;
        synchronized (monitor) {
            if (pendingRebind != null) {
                throw new IOException("LuckPerms backend rebind is already pending resume");
            }
            previous = activeRoot;
            previousIdentity = activeIdentity;
            previousContract = activeContract;
            if (previous == null || previousIdentity == null || previousContract == null) {
                throw new IOException("LuckPerms backend rebind rollback boundary is unavailable");
            }
        }
        try {
            invokeAdapter(() -> adapter.rebind(target, bound.receipt()), "rebind");
            synchronized (monitor) {
                pendingRebind = new PendingRebind(previous, previousIdentity, previousContract, target, bound);
                rebindRollbackPending = false;
                monitor.notifyAll();
            }
        } catch (IOException | RuntimeException failure) {
            IOException rollbackFailure = rollbackRebind(previous, bound.receipt());
            synchronized (monitor) {
                failureReason = reason(failure);
                state = rollbackFailure == null ? State.QUIESCED : State.DEGRADED;
                if (rollbackFailure != null) {
                    pendingRebind = new PendingRebind(previous, previousIdentity, previousContract, target, bound);
                    rebindRollbackPending = true;
                    failureReason = failureReason + "; rollback: " + reason(rollbackFailure);
                }
                monitor.notifyAll();
            }
            if (rollbackFailure != null && failure instanceof IOException exception) {
                exception.addSuppressed(rollbackFailure);
            }
            if (failure instanceof IOException exception) {
                throw exception;
            }
            IOException wrapped = new IOException("LuckPerms backend rebind failed", failure);
            if (rollbackFailure != null) {
                wrapped.addSuppressed(rollbackFailure);
            }
            throw wrapped;
        }
    }

    public void rebind(Path nextRoot) throws IOException {
        SnapshotManifest manifest;
        synchronized (monitor) {
            manifest = lastSnapshot;
        }
        if (manifest == null) {
            throw new IOException("LuckPerms backend snapshot manifest is not bound to this capability");
        }
        rebind(manifest, nextRoot);
    }

    public void rollbackPendingRebind() throws IOException {
        PendingRebind pending;
        synchronized (monitor) {
            pending = pendingRebind;
            if (pending == null) {
                return;
            }
            if (state == State.CLOSED || state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence is " + state.name().toLowerCase());
            }
        }
        try {
            invokeAdapter(() -> adapter.rebind(pending.previous, pending.manifest.receipt()), "rebind rollback");
            synchronized (monitor) {
                activeRoot = pending.previous;
                activeIdentity = pending.previousIdentity;
                activeContract = pending.previousContract;
                pendingRebind = null;
                rebindRollbackPending = false;
                state = State.QUIESCED;
                failureReason = "";
                monitor.notifyAll();
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (monitor) {
                rebindRollbackPending = true;
                failureReason = reason(failure);
                state = State.DEGRADED;
                monitor.notifyAll();
            }
            if (failure instanceof IOException exception) {
                throw exception;
            }
            throw new IOException("LuckPerms backend rebind rollback failed", failure);
        }
    }

    public void healthCheck() throws IOException {
        synchronized (monitor) {
            requireNotClosed();
            Support support = support();
            if (state == State.FAILED) {
                throw new IOException("LuckPerms backend persistence support is unavailable: " + failureReason);
            }
            if (state == State.QUIESCING) {
                throw new IOException("LuckPerms backend persistence is quiescing");
            }
            if (state == State.DEGRADED) {
                throw new IOException("LuckPerms backend persistence is degraded: " + failureReason);
            }
            if (!support.configured() || !support.healthCheck()) {
                return;
            }
        }
        try {
            invokeAdapter(adapter::healthCheck, "health check");
        } catch (IOException exception) {
            markDegraded(exception);
            throw exception;
        }
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    public int activeAdmissionCount() {
        synchronized (monitor) {
            return activeAdmissions;
        }
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    @Override
    public void close() throws IOException {
        boolean resumeExternal;
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.QUIESCING) {
                throw new IOException("LuckPerms backend persistence is still quiescing");
            }
            if (pendingRebind != null) {
                throw new IOException("LuckPerms backend rebind is still pending resume");
            }
            if (activeAdmissions > 0) {
                throw new IOException("LuckPerms backend persistence still has active admissions");
            }
            resumeExternal = externalQuiesced || externalStateUnknown;
        }
        if (resumeExternal) {
            try {
                invokeAdapter(adapter::resume, "resume before close");
                synchronized (monitor) {
                    externalQuiesced = false;
                    externalStateUnknown = false;
                }
            } catch (IOException exception) {
                IOException compensation = compensateExternalQuiesce();
                if (compensation != null) {
                    exception.addSuppressed(compensation);
                }
                markDegraded(exception);
                throw exception;
            }
        }
        try {
            adapter.close();
        } catch (IOException exception) {
            markCloseFailure(exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("LuckPerms backend persistence adapter could not close", exception);
            markCloseFailure(failure);
            throw failure;
        }
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            generation++;
            failureReason = "";
            monitor.notifyAll();
        }
    }

    private IOException awaitAdmissions(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (activeAdmissions > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new IOException("LuckPerms backend persistence drain timed out");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new IOException("LuckPerms backend persistence drain was interrupted", exception);
                }
            }
            return null;
        }
    }

    private Support support() {
        try {
            Support value = adapter.support();
            if (value == null) {
                failSupport("LuckPerms backend adapter did not declare support");
                return Support.unavailable("LuckPerms backend adapter did not declare support");
            }
            return value;
        } catch (RuntimeException exception) {
            failSupport("LuckPerms backend adapter support could not be read");
            return Support.unavailable("LuckPerms backend adapter support could not be read");
        }
    }

    private BackendIdentity readAdapterIdentity() {
        try {
            BackendIdentity identity = adapter.identity();
            if (identity == null) {
                throw new IllegalStateException("LuckPerms backend adapter identity is unavailable");
            }
            return identity;
        } catch (RuntimeException exception) {
            failSupport("LuckPerms backend adapter identity could not be read");
            throw exception;
        }
    }

    private AdapterContract readAdapterContract() {
        try {
            AdapterContract contract = adapter.contract();
            if (contract == null) {
                throw new IllegalStateException("LuckPerms backend adapter contract is unavailable");
            }
            return contract;
        } catch (RuntimeException exception) {
            failSupport("LuckPerms backend adapter contract could not be read");
            throw exception;
        }
    }

    private String adapterId() {
        try {
            return adapter.id() == null ? "" : adapter.id().trim();
        } catch (RuntimeException exception) {
            failSupport("LuckPerms backend adapter id could not be read");
            return "unavailable";
        }
    }

    private boolean checkAdapterHealth(Support support) {
        if (!support.configured() || !support.healthCheck()) {
            return true;
        }
        try {
            adapter.healthCheck();
            return true;
        } catch (IOException | RuntimeException exception) {
            markDegraded(exception);
            return false;
        }
    }

    private SnapshotManifest requireManifest(SnapshotManifest manifest, String operation) throws IOException {
        synchronized (monitor) {
            requireQuiesced();
            requireNoPendingRebind();
            if (operation.equals("restore")) {
                requireRestoreSupport();
            } else {
                requireRebindSupport();
            }
            if (manifest == null) {
                rejectManifest(operation, "snapshot manifest is required");
            }
            BackendIdentity identity = readAdapterIdentity();
            AdapterContract contract = readAdapterContract();
            if (activeIdentity != null && !activeIdentity.equals(identity)) {
                rejectManifest(operation, "active backend identity changed");
            }
            if (activeContract != null && !activeContract.equals(contract)) {
                rejectManifest(operation, "active adapter contract changed");
            }
            if (!manifest.backendIdentity().equals(identity)) {
                rejectManifest(operation, "snapshot backend identity does not match the active backend");
            }
            if (manifest.generation() != generation) {
                rejectManifest(operation, "snapshot generation does not match the active backend generation");
            }
            if (!manifest.adapterContract().equals(contract)) {
                rejectManifest(operation, "snapshot adapter contract does not match the active adapter");
            }
        }
        verifyReceipt(manifest.receipt());
        return manifest;
    }

    private SnapshotManifest resolveManifest(Path snapshotRoot) throws IOException {
        if (snapshotRoot == null) {
            throw new IOException("LuckPerms backend snapshot manifest is required");
        }
        Path normalized;
        try {
            normalized = MigrationPaths.requirePath(snapshotRoot, "snapshotRoot");
        } catch (RuntimeException exception) {
            throw new IOException("LuckPerms backend snapshot path is invalid", exception);
        }
        synchronized (monitor) {
            if (lastSnapshot == null || lastSnapshot.snapshotRoot() == null || !lastSnapshot.snapshotRoot().equals(normalized)) {
                throw new IOException("LuckPerms backend snapshot is not bound to this capability");
            }
            return lastSnapshot;
        }
    }

    private void requireSnapshotSupport() throws IOException {
        Support support = support();
        if (!snapshotReady(support)) {
            throw new IOException("LuckPerms backend snapshot is unavailable: " + support.reason());
        }
    }

    private void requireRebindSupport() throws IOException {
        Support support = support();
        if (!rebindReady(support)) {
            throw new IOException("LuckPerms backend rebind is unavailable: " + support.reason());
        }
    }

    private void requireRestoreSupport() throws IOException {
        Support support = support();
        if (!restoreReady(support)) {
            throw new IOException("LuckPerms backend restore is unavailable: " + support.reason());
        }
    }

    private boolean snapshotReady(Support support) {
        return support.snapshotReady() && hasExternalControl();
    }

    private boolean rebindReady(Support support) {
        return support.rebindReady() && hasExternalControl();
    }

    private boolean restoreReady(Support support) {
        return support.restoreReady() && hasExternalControl();
    }

    private boolean replacementReady(Support support) {
        return support.replacementReady() && hasExternalControl();
    }

    private boolean hasExternalControl() {
        try {
            return adapter.getClass().getMethod("quiesce").getDeclaringClass() != Adapter.class
                && adapter.getClass().getMethod("resume").getDeclaringClass() != Adapter.class;
        } catch (ReflectiveOperationException | SecurityException exception) {
            return false;
        }
    }

    private void rejectManifest(String operation, String detail) throws IOException {
        failureReason = operation + ": " + detail;
        state = State.DEGRADED;
        monitor.notifyAll();
        throw new IOException("LuckPerms backend " + operation + " rejected: " + detail);
    }

    private IOException rollbackRebind(Path previous, SnapshotReceipt receipt) {
        if (previous == null) {
            return new IOException("LuckPerms backend previous root is unavailable for rollback");
        }
        try {
            invokeAdapter(() -> adapter.rebind(previous, receipt), "rebind rollback");
            return null;
        } catch (IOException exception) {
            return exception;
        }
    }

    private IOException compensateExternalResume() {
        if (!hasExternalControl()) {
            return new IOException("LuckPerms backend external quiesce cannot be compensated");
        }
        synchronized (monitor) {
            externalQuiesced = true;
            externalStateUnknown = true;
        }
        try {
            invokeAdapter(adapter::resume, "resume compensation");
            synchronized (monitor) {
                externalQuiesced = false;
                externalStateUnknown = false;
            }
            return null;
        } catch (IOException exception) {
            return exception;
        }
    }

    private IOException compensateExternalQuiesce() {
        if (!hasExternalControl()) {
            return new IOException("LuckPerms backend external resume cannot be compensated");
        }
        try {
            invokeAdapter(adapter::quiesce, "quiesce compensation");
            synchronized (monitor) {
                externalQuiesced = true;
                externalStateUnknown = false;
            }
            return null;
        } catch (IOException exception) {
            synchronized (monitor) {
                externalQuiesced = false;
                externalStateUnknown = true;
            }
            return exception;
        }
    }

    private boolean isExternalQuiesced() {
        synchronized (monitor) {
            return externalQuiesced && !externalStateUnknown;
        }
    }

    private void verifyReceipt(SnapshotReceipt receipt) throws IOException {
        if (receipt == null) {
            throw new IOException("LuckPerms backend snapshot receipt is missing");
        }
        if (receipt.artifact() != null) {
            verifyArtifact(receipt.artifact(), receipt.contentHash());
        } else if (receipt.receiptId().isBlank()) {
            throw new IOException("LuckPerms backend snapshot receipt has no artifact or remote receipt id");
        }
        invokeAdapter(() -> adapter.verifySnapshot(receipt), "snapshot verification");
    }

    private void verifyArtifact(Path artifact, String expectedHash) throws IOException {
        Path normalized;
        try {
            normalized = MigrationPaths.requirePath(artifact, "snapshot artifact");
        } catch (RuntimeException exception) {
            throw new IOException("LuckPerms backend snapshot artifact path is invalid", exception);
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LuckPerms backend snapshot artifact must be an existing regular file");
        }
        String actualHash = hashRegularFile(normalized);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IOException("LuckPerms backend snapshot artifact hash does not match its receipt");
        }
    }

    private String hashRegularFile(Path artifact) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean lifecycleReady() {
        return (state == State.OPEN || state == State.QUIESCED) && pendingRebind == null;
    }

    private void requireQuiesced() throws IOException {
        if (state != State.QUIESCED) {
            throw new IOException("LuckPerms backend persistence is " + state.name().toLowerCase()
                + "; expected QUIESCED");
        }
    }

    private void requireNoPendingRebind() throws IOException {
        if (pendingRebind != null) {
            throw new IOException("LuckPerms backend rebind is still pending resume");
        }
    }

    private void requireNotClosed() throws IOException {
        if (state == State.CLOSED) {
            throw new IOException("LuckPerms backend persistence is closed");
        }
        if (state == State.FAILED) {
            throw new IOException("LuckPerms backend persistence failed: " + failureReason);
        }
    }

    private void invokeAdapter(IoOperation operation, String name) throws IOException {
        try {
            operation.run();
        } catch (IOException exception) {
            synchronized (monitor) {
                failureReason = name + ": " + reason(exception);
            }
            throw exception;
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                failureReason = name + ": " + reason(exception);
            }
            throw new IOException("LuckPerms backend " + name + " failed", exception);
        }
    }

    private void markDegraded(Throwable exception) {
        synchronized (monitor) {
            if (state != State.CLOSED && state != State.FAILED) {
                failureReason = reason(exception);
                state = State.DEGRADED;
                monitor.notifyAll();
            }
        }
    }

    private void markCloseFailure(Throwable exception) {
        synchronized (monitor) {
            if (state != State.CLOSED) {
                failureReason = "close: " + reason(exception);
                if (state != State.FAILED) {
                    state = State.DEGRADED;
                }
                monitor.notifyAll();
            }
        }
    }

    private void failSupport(String reason) {
        synchronized (monitor) {
            if (state != State.CLOSED) {
                failureReason = reason;
                state = State.FAILED;
                monitor.notifyAll();
            }
        }
    }

    private void release(Admission admission) {
        synchronized (monitor) {
            if (admission.closed.compareAndSet(false, true)) {
                activeAdmissions--;
                monitor.notifyAll();
            }
        }
    }

    private static Path normalizeOptional(Path path) {
        return path == null ? null : MigrationPaths.requirePath(path, "activeRoot");
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return timeout;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String reason(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private interface IoOperation {
        void run() throws IOException;
    }

    private record PendingRebind(Path previous, BackendIdentity previousIdentity, AdapterContract previousContract,
                                 Path target, SnapshotManifest manifest) {
    }

    @FunctionalInterface
    public interface AdapterProvider {
        Adapter create(JavaPlugin plugin);
    }

    public interface Adapter extends AutoCloseable {
        String id();

        Path activeRoot();

        default BackendIdentity identity() {
            return new BackendIdentity("luckperms", requireText(id(), "adapter id"));
        }

        default AdapterContract contract() {
            return new AdapterContract(requireText(id(), "adapter id"), "1");
        }

        Support support();

        void flush() throws IOException;

        default void backup(Path snapshotRoot) throws IOException {
            throw new IOException("LuckPerms backend adapter does not create local snapshots");
        }

        default SnapshotReceipt backup(Path snapshotRoot, long generation) throws IOException {
            backup(snapshotRoot);
            String hash = snapshotHash(snapshotRoot);
            if (hash == null || hash.isBlank()) {
                throw new IOException("LuckPerms backend adapter did not return a snapshot receipt");
            }
            return new SnapshotReceipt(identity(), generation, contract(), hash, snapshotRoot, "");
        }

        default String snapshotHash(Path snapshotRoot) throws IOException {
            return "";
        }

        default void restore(Path snapshotRoot) throws IOException {
            throw new IOException("LuckPerms backend adapter does not restore local snapshots");
        }

        default void restore(SnapshotReceipt receipt) throws IOException {
            if (receipt == null || receipt.artifact() == null) {
                throw new IOException("LuckPerms backend adapter cannot restore a remote receipt");
            }
            restore(receipt.artifact());
        }

        default void rebind(Path activeRoot) throws IOException {
            throw new IOException("LuckPerms backend adapter does not rebind local roots");
        }

        default void rebind(Path activeRoot, SnapshotReceipt receipt) throws IOException {
            rebind(activeRoot);
        }

        default Path rebindTarget(Path activeScopeRoot) throws IOException {
            return MigrationPaths.requirePath(activeScopeRoot, "activeScopeRoot");
        }

        default void verifySnapshot(SnapshotReceipt receipt) throws IOException {
            if (receipt == null || receipt.artifact() == null) {
                throw new IOException("LuckPerms backend adapter did not verify a remote snapshot receipt");
            }
        }

        default void quiesce() throws IOException {
            throw new IOException("LuckPerms backend adapter does not quiesce external writers");
        }

        default void resume() throws IOException {
            throw new IOException("LuckPerms backend adapter does not resume external writers");
        }

        void healthCheck() throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    public record BackendIdentity(String type, String instance) {
        public BackendIdentity {
            type = requireText(type, "backend type");
            instance = requireText(instance, "backend instance");
        }
    }

    public record AdapterContract(String id, String version) {
        public AdapterContract {
            id = requireText(id, "adapter contract id");
            version = requireText(version, "adapter contract version");
        }
    }

    public record SnapshotReceipt(BackendIdentity backendIdentity, long generation, AdapterContract adapterContract,
                                  String contentHash, Path artifact, String receiptId) {
        public SnapshotReceipt {
            backendIdentity = Objects.requireNonNull(backendIdentity, "backendIdentity");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            adapterContract = Objects.requireNonNull(adapterContract, "adapterContract");
            contentHash = requireText(contentHash, "contentHash");
            artifact = artifact == null ? null : MigrationPaths.requirePath(artifact, "snapshot artifact");
            receiptId = receiptId == null ? "" : receiptId.trim();
            if (artifact == null && receiptId.isBlank()) {
                throw new IllegalArgumentException("snapshot receipt must contain an artifact or receipt id");
            }
        }

        public static SnapshotReceipt local(BackendIdentity identity, long generation, AdapterContract contract,
                                            String contentHash, Path artifact) {
            return new SnapshotReceipt(identity, generation, contract, contentHash, artifact, "");
        }

        public static SnapshotReceipt remote(BackendIdentity identity, long generation, AdapterContract contract,
                                             String contentHash, String receiptId) {
            return new SnapshotReceipt(identity, generation, contract, contentHash, null, receiptId);
        }
    }

    public record SnapshotManifest(BackendIdentity backendIdentity, long generation, AdapterContract adapterContract,
                                   SnapshotReceipt receipt) {
        public SnapshotManifest {
            backendIdentity = Objects.requireNonNull(backendIdentity, "backendIdentity");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            adapterContract = Objects.requireNonNull(adapterContract, "adapterContract");
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (!backendIdentity.equals(receipt.backendIdentity()) || generation != receipt.generation()
                || !adapterContract.equals(receipt.adapterContract())) {
                throw new IllegalArgumentException("snapshot receipt is not bound to the manifest");
            }
        }

        public SnapshotManifest(BackendIdentity backendIdentity, long generation, AdapterContract adapterContract,
                                String contentHash, Path snapshotRoot) {
            this(backendIdentity, generation, adapterContract,
                SnapshotReceipt.local(backendIdentity, generation, adapterContract, contentHash, snapshotRoot));
        }

        public String contentHash() {
            return receipt.contentHash();
        }

        public Path snapshotRoot() {
            return receipt.artifact();
        }
    }

    public record Support(boolean configured, boolean flush, boolean backup, boolean restore, boolean rebind,
                          boolean quiesce, boolean resume, boolean healthCheck, String reason) {
        public Support(boolean configured, boolean flush, boolean backup, boolean restore, boolean rebind,
                       boolean healthCheck, String reason) {
            this(configured, flush, backup, restore, rebind, false, false, healthCheck, reason);
        }

        public Support {
            reason = reason == null ? "" : reason.trim();
        }

        public boolean snapshotReady() {
            return configured && flush && backup && quiesce && resume;
        }

        public boolean restoreReady() {
            return configured && restore && quiesce && resume;
        }

        public boolean rebindReady() {
            return configured && restore && rebind && quiesce && resume;
        }

        public boolean replacementReady() {
            return snapshotReady() && rebindReady() && healthCheck;
        }

        public static Support unavailable(String reason) {
            return new Support(false, false, false, false, false, false, false, false, reason);
        }

        public static Support capable() {
            return new Support(true, true, true, true, true, true, true, true, "");
        }
    }

    public record Readiness(boolean available, boolean snapshotReady, boolean restoreReady, boolean rebindReady,
                            State state, String adapterId, Path activeRoot, String reason, long generation,
                            int activeAdmissions) {
        public Readiness {
            adapterId = adapterId == null ? "" : adapterId.trim();
            activeRoot = activeRoot == null ? null : activeRoot.toAbsolutePath().normalize();
            reason = reason == null ? "" : reason.trim();
        }
    }

    public record Health(boolean healthy, State state, int activeAdmissions, long generation, Path activeRoot,
                         String adapterId, String reason) {
        public Health {
            activeRoot = activeRoot == null ? null : activeRoot.toAbsolutePath().normalize();
            adapterId = adapterId == null ? "" : adapterId.trim();
            reason = reason == null ? "" : reason.trim();
        }
    }

    public enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        DEGRADED,
        FAILED,
        CLOSED
    }

    public static final class Admission implements AutoCloseable {
        private final LuckPermsBackendPersistenceCapability capability;
        private final String operation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Admission(LuckPermsBackendPersistenceCapability capability, String operation) {
            this.capability = capability;
            this.operation = operation;
        }

        public String operation() {
            return operation;
        }

        public boolean active() {
            return !closed.get();
        }

        @Override
        public void close() {
            capability.release(this);
        }
    }

    private static final class UnavailableAdapter implements Adapter {
        private final String reason;

        private UnavailableAdapter(String reason) {
            this.reason = reason == null || reason.isBlank() ? "LuckPerms backend persistence adapter is unavailable" : reason.trim();
        }

        @Override
        public String id() {
            return "unavailable";
        }

        @Override
        public Path activeRoot() {
            return null;
        }

        @Override
        public BackendIdentity identity() {
            return new BackendIdentity("luckperms", "unavailable");
        }

        @Override
        public AdapterContract contract() {
            return new AdapterContract("unavailable", "0");
        }

        @Override
        public Support support() {
            return Support.unavailable(reason);
        }

        @Override
        public void flush() {
        }

        @Override
        public void backup(Path snapshotRoot) {
        }

        @Override
        public void restore(Path snapshotRoot) {
        }

        @Override
        public void rebind(Path activeRoot) {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void healthCheck() {
        }
    }
}
