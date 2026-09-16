package restudio.resync.restore;

import restudio.resync.migration.AtomicDirectoryRootStore;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AtomicRestoreActivation implements RestoreActivation {
    private final AtomicDirectoryRootStore roots;
    private final Path controlDirectory;
    private final Path scopeRoot;
    private final RestoreActivationRecovery recovery;
    private final ActiveRootReader activeRoots;

    public AtomicRestoreActivation(Path controlDirectory) throws IOException {
        this(controlDirectory, null);
    }

    AtomicRestoreActivation(Path controlDirectory, ActiveRootReader activeRoots) throws IOException {
        this.controlDirectory = MigrationPaths.requirePath(controlDirectory, "controlDirectory");
        this.scopeRoot = Objects.requireNonNull(this.controlDirectory.getParent(), "controlDirectory parent");
        roots = new AtomicDirectoryRootStore(this.controlDirectory);
        recovery = new RestoreActivationRecovery(this.controlDirectory.resolve("activation-intent"));
        this.activeRoots = activeRoots == null ? roots::activeRoot : Objects.requireNonNull(activeRoots, "activeRoots");
    }

    @Override
    public Optional<Path> activeRoot() throws IOException {
        return observeActiveRoot().activeRoot();
    }

    public synchronized Optional<Path> restoreReadyActiveRoot() throws IOException {
        ActiveRootObservation observation = observeActiveRoot();
        if (observation.intent().filter(intent -> intent.phase() != RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE).isPresent()) {
            return Optional.empty();
        }
        return observation.activeRoot();
    }

    public synchronized boolean recoveryIntentPending() throws IOException {
        return recovery.pending();
    }

    public synchronized Optional<Path> recover() throws IOException {
        Optional<RestoreActivationRecovery.Intent> pending = recovery.read();
        if (pending.isEmpty()) {
            return roots.activeRoot();
        }
        RestoreActivationRecovery.Intent intent = pending.get();
        requireStagedRoot(intent.staged().root());
        Optional<Path> active = roots.activeRoot();
        if (intent.phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
            requireCompletedBootstrap(intent,
                active.orElseThrow(() -> new MigrationException("Restore Active Root Is Missing")));
            return active;
        }
        if (intent.staged().previousRoot().isEmpty()) {
            requireBootstrapSource(intent, intent.evidenceRoot());
        }
        if (intent.phase() == RestoreActivationRecovery.Phase.PREPARING) {
            if (intent.staged().previousRoot().isPresent()) {
                throw new MigrationException("Bootstrap Preparation Cannot Replace An Existing Active Root");
            }
            if (!active.equals(intent.staged().previousRoot())) {
                throw new MigrationException("Restore Bootstrap Intent Does Not Match The Active Root");
            }
            MigrationPlan currentPlan = bootstrapPlan(MigrationPaths.requireDirectory(intent.evidenceRoot(), "evidenceRoot"));
            if (!currentPlan.planHash().equals(intent.staged().planHash())) {
                throw new MigrationException("Restore Bootstrap Source Changed While Activation Was Interrupted");
            }
            discard(intent.staged().root());
            return Optional.of(prepareBootstrap(intent.evidenceRoot(), intent.staged().root()));
        }
        if (intent.phase() == RestoreActivationRecovery.Phase.READY) {
            if (active.isEmpty() && intent.staged().previousRoot().isEmpty()) {
                verifyPrepared(intent.staged());
                roots.activate(intent.staged());
                recovery.activated(intent.evidenceRoot(), intent.staged());
                return Optional.of(intent.staged().root());
            }
            if (active.equals(Optional.of(intent.staged().root()))) {
                verifyPrepared(intent.staged());
                recovery.activated(intent.evidenceRoot(), intent.staged());
                return active;
            }
            if (active.equals(intent.staged().previousRoot())) {
                verifyPrepared(intent.staged());
                recovery.rolledBack(intent.evidenceRoot(), intent.staged());
                discard(intent.staged().root());
                return active;
            }
            throw new MigrationException("Ready Restore Activation Intent Does Not Match The Active Root");
        }
        if (intent.phase() == RestoreActivationRecovery.Phase.ACTIVATED) {
            if (active.equals(Optional.of(intent.staged().root()))) {
                verifyPrepared(intent.staged());
                return active;
            }
            if (active.equals(intent.staged().previousRoot())) {
                verifyPrepared(intent.staged());
                recovery.rolledBack(intent.evidenceRoot(), intent.staged());
                discard(intent.staged().root());
                return active;
            }
            throw new MigrationException("Activated Restore Intent Does Not Match The Active Root");
        }
        if (!active.equals(intent.staged().previousRoot())) {
            throw new MigrationException("Rolled Back Restore Intent Does Not Match The Active Root");
        }
        verifyIfPresent(intent.staged());
        discard(intent.staged().root());
        return active;
    }

    public synchronized Path initialize(Path sourceRoot) throws IOException {
        Path source = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
        Optional<RestoreActivationRecovery.Intent> initial = recovery.read();
        if (initial.isPresent() && initial.get().staged().previousRoot().isEmpty()) {
            requireBootstrapSource(initial.get(), source);
        }
        Optional<Path> recovered = recover();
        if (recovered.isPresent()) {
            completeBootstrap(source, recovered.get());
            return recovered.get();
        }
        Path activeRoots = scopeRoot.resolve("active-roots").toAbsolutePath().normalize();
        Files.createDirectories(activeRoots);
        MigrationPaths.requireDirectory(activeRoots, "activeRoots");
        Path target = activeRoots.resolve("bootstrap-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Path active = prepareBootstrap(source, target);
        completeBootstrap(source, active);
        return active;
    }

    public synchronized void converged(Path root) throws IOException {
        Path expected = normalizeRequestedRoot(root);
        ActiveRootObservation observation = observeActiveRoot();
        Path active = observation.activeRoot()
            .orElseThrow(() -> new MigrationException("Restore Active Root Is Missing"));
        if (!active.equals(expected)) {
            throw new MigrationException("Persistence Participants Did Not Converge On The Active Root");
        }
        Optional<RestoreActivationRecovery.Intent> pending = observation.intent();
        if (pending.isPresent()) {
            RestoreActivationRecovery.Intent intent = pending.get();
            requireStagedRoot(intent.staged().root());
            if (intent.phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
                requireCompletedBootstrap(intent, active);
                return;
            }
            if (intent.staged().previousRoot().isEmpty()) {
                throw new MigrationException("Initial Bootstrap Copy Has Not Completed Before Participant Convergence");
            }
            boolean activated = intent.phase() == RestoreActivationRecovery.Phase.ACTIVATED && intent.staged().root().equals(active);
            boolean rolledBack = intent.phase() == RestoreActivationRecovery.Phase.ROLLED_BACK
                && intent.staged().previousRoot().equals(Optional.of(active));
            if (!activated && !rolledBack) {
                throw new MigrationException("Restore Activation Intent Has Not Reached A Converged Outcome");
            }
            if (activated) {
                verifyPrepared(intent.staged());
            } else {
                verifyIfPresent(intent.staged());
            }
        }
        recovery.clear();
    }

    public synchronized boolean recoveryPending() throws IOException {
        validateCompletedBootstrap();
        return recovery.pending();
    }

    @Override
    public String planHash(Snapshot snapshot) {
        return plan(snapshot).planHash();
    }

    @Override
    public StagedMigration stage(Snapshot snapshot, Path stagingRoot) throws IOException {
        validateCompletedBootstrap();
        SnapshotVerification verification = snapshot.manifest().verify(snapshot.root());
        verification.requireVerified();
        return roots.stage(snapshot.root(), requireStagedRoot(stagingRoot), plan(snapshot));
    }

    @Override
    public synchronized void activate(StagedMigration staged) throws IOException {
        activate(staged.root(), staged);
    }

    @Override
    public synchronized void swap(RestoreActivation.Candidate candidate) throws IOException {
        RestoreActivation.Candidate checked = Objects.requireNonNull(candidate, "candidate");
        validateBeforeActivation(checked.snapshot(), checked.staged());
        activate(checked.snapshot().root(), checked.staged());
    }

    private void activate(Path evidenceRoot, StagedMigration staged) throws IOException {
        validateCompletedBootstrap();
        recovery.ready(evidenceRoot, staged);
        roots.activate(staged);
        recovery.activated(evidenceRoot, staged);
    }

    @Override
    public void validateBeforeActivation(Snapshot snapshot, StagedMigration staged) throws IOException {
        SnapshotVerification verification = snapshot.manifest().verify(staged.root());
        verification.requireVerified();
        Path previous = staged.previousRoot().orElseThrow(() -> new MigrationException("Restore Previous Root Is Missing"));
        Path active = roots.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Missing"));
        if (!active.equals(previous)) {
            throw new MigrationException("Restore Active Root Changed Before Activation");
        }
        MigrationPaths.requireDirectory(previous, "previousRoot");
    }

    @Override
    public synchronized void rollback(StagedMigration staged) throws IOException {
        validateCompletedBootstrap();
        roots.rollback(staged);
        Path evidenceRoot = recovery.read().filter(intent -> intent.staged().equals(staged))
            .map(RestoreActivationRecovery.Intent::evidenceRoot).orElse(staged.root());
        recovery.rolledBack(evidenceRoot, staged);
    }

    @Override
    public void verify(Snapshot snapshot, StagedMigration staged) throws IOException {
        Path active = roots.activeRoot().orElseThrow(() -> new MigrationException("Restored Root Is Not Active"));
        if (!active.equals(staged.root())) {
            throw new MigrationException("Restored Root Pointer Does Not Match Staged Root");
        }
        SnapshotVerification verification = snapshot.manifest().verify(active);
        verification.requireVerified();
    }

    @Override
    public void discard(Path stagingRoot) throws IOException {
        Path root = requireStagedRoot(stagingRoot);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (activeRoot().map(root::equals).orElse(false)) {
            throw new MigrationException("Cannot Discard Active Restore Root");
        }
        MigrationPaths.requireNoSymlinkTree(root);
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private Path requireStagedRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "restoreStagingRoot");
        if (normalized.equals(scopeRoot) || !normalized.startsWith(scopeRoot) || normalized.startsWith(controlDirectory)) {
            throw new MigrationException("Restore Root Is Outside Activation Scope: " + normalized);
        }
        return normalized;
    }

    private Path prepareBootstrap(Path sourceRoot, Path targetRoot) throws IOException {
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        Path target = requireStagedRoot(targetRoot);
        MigrationPlan plan = bootstrapPlan(source);
        recovery.preparing(source, target, Optional.empty(), plan.planHash());
        StagedMigration staged = roots.stage(source, target, plan);
        if (!staged.contentHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Restore Bootstrap Source Changed During Activation Preparation");
        }
        recovery.ready(source, staged);
        roots.activate(staged);
        recovery.activated(source, staged);
        return staged.root();
    }

    private void completeBootstrap(Path source, Path active) throws IOException {
        Optional<RestoreActivationRecovery.Intent> pending = recovery.read();
        if (pending.isEmpty() || pending.get().staged().previousRoot().isPresent()) {
            return;
        }
        RestoreActivationRecovery.Intent intent = pending.get();
        requireBootstrapSource(intent, source);
        if (intent.phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
            requireCompletedBootstrap(intent, active);
            return;
        }
        Path observed = roots.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Missing"));
        if (intent.phase() != RestoreActivationRecovery.Phase.ACTIVATED || !intent.staged().root().equals(active)
            || !observed.equals(active)) {
            throw new MigrationException("Bootstrap Copy Has Not Reached Its Exact Activated Root");
        }
        verifyPrepared(intent.staged());
        recovery.bootstrapComplete(source, intent.staged());
        requireCompletedBootstrap(recovery.read().orElseThrow(), observed);
    }

    private void requireBootstrapSource(RestoreActivationRecovery.Intent intent, Path source) throws IOException {
        if (intent.staged().previousRoot().isPresent() || !intent.evidenceRoot().equals(source)
            || source.equals(intent.staged().root())) {
            throw new MigrationException("Bootstrap Copy Does Not Match The Expected Source Root");
        }
        requireStagedRoot(intent.staged().root());
        if (intent.phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
            requireCompletedBootstrap(intent);
            return;
        }
        MigrationPlan original = bootstrapPlan(MigrationPaths.requireDirectory(source, "bootstrapSource"));
        if (!original.planHash().equals(intent.staged().planHash())
            || intent.phase() != RestoreActivationRecovery.Phase.PREPARING
                && !original.sourceManifestHash().equals(intent.staged().contentHash())) {
            throw new MigrationException("Bootstrap Copy No Longer Matches Its Original Source Proof");
        }
    }

    private void validateCompletedBootstrap() throws IOException {
        Optional<RestoreActivationRecovery.Intent> intent = recovery.read();
        if (intent.isPresent() && intent.get().phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
            Path active = activeRoots.read().orElseThrow(() -> new MigrationException("Restore Active Root Is Missing"));
            requireCompletedBootstrap(intent.get(), active);
        }
    }

    private void requireCompletedBootstrap(RestoreActivationRecovery.Intent intent) throws IOException {
        Path active = activeRoots.read().orElseThrow(() -> new MigrationException("Restore Active Root Is Missing"));
        requireCompletedBootstrap(intent, active);
    }

    private void requireCompletedBootstrap(RestoreActivationRecovery.Intent intent, Path active) throws IOException {
        if (intent.phase() != RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE
            || intent.staged().previousRoot().isPresent()
            || !RestoreActivationRecovery.bootstrapPlan(intent.staged().contentHash()).planHash().equals(intent.staged().planHash())) {
            throw new MigrationException("Completed Bootstrap Copy Proof Is Invalid");
        }
        Path target = requireStagedRoot(intent.staged().root());
        if (!active.equals(target)) {
            throw new MigrationException("Completed Bootstrap Copy Does Not Match The Active Root Pointer");
        }
    }

    private ActiveRootObservation observeActiveRoot() throws IOException {
        Optional<RestoreActivationRecovery.Intent> intent = recovery.read();
        Optional<Path> active = activeRoots.read();
        if (intent.isPresent() && intent.get().phase() == RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE) {
            requireCompletedBootstrap(intent.get(),
                active.orElseThrow(() -> new MigrationException("Restore Active Root Is Missing")));
        }
        return new ActiveRootObservation(active, intent);
    }

    private static void verifyPrepared(StagedMigration staged) throws IOException {
        if (!staged.contentHash().equals(TreeDigest.of(staged.root()))) {
            throw new MigrationException("Prepared Restore Root Content Does Not Match The Activation Intent");
        }
    }

    private static void verifyIfPresent(StagedMigration staged) throws IOException {
        Path root = staged.root();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            verifyPrepared(staged);
        }
    }

    private static Path normalizeRequestedRoot(Path root) throws MigrationException {
        if (root == null) {
            throw new MigrationException("activeRoot Is Required");
        }
        return root.toAbsolutePath().normalize();
    }

    private static MigrationPlan bootstrapPlan(Path sourceRoot) throws IOException {
        String sourceHash = TreeDigest.migratableOf(sourceRoot);
        return RestoreActivationRecovery.bootstrapPlan(sourceHash);
    }

    private static MigrationPlan plan(Snapshot snapshot) {
        return new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), snapshot.metadata().formatVersion(), snapshot.metadata().formatVersion(), QuarantineReport.empty().reportHash(), List.of());
    }

    @FunctionalInterface
    interface ActiveRootReader {
        Optional<Path> read() throws IOException;
    }

    private record ActiveRootObservation(Optional<Path> activeRoot,
                                         Optional<RestoreActivationRecovery.Intent> intent) {
    }
}
