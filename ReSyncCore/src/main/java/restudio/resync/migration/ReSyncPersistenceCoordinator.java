package restudio.resync.migration;

import restudio.resync.restore.AtomicRestoreActivation;
import restudio.resync.restore.RestorePreflight;
import restudio.resync.restore.RestoreRecoveryResult;
import restudio.resync.restore.RestoreRequest;
import restudio.resync.restore.RestoreResult;
import restudio.resync.restore.RestoreService;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ReSyncPersistenceCoordinator {
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private final Path dataRoot;
    private final Path coordinationRoot;
    private final Path snapshotRoot;
    private final Path restoreControlDirectory;
    private final MigrationFence fence;
    private final PersistenceParticipantRegistry participants;
    private final SnapshotService snapshots;
    private final AtomicRestoreActivation activation;
    private final RegistrationRootReader registrationRoots;
    private final RestoreService restores;
    private final AuthorityEpochStore authorityEpochStore;
    private final Map<String, EphemeralLifecycleParticipant> ephemeralParticipants = new LinkedHashMap<>();
    private FreshRootProvenance freshRootProvenance;
    private volatile boolean startupDerivedChecksRelaxed;
    private volatile boolean sealed;
    private volatile boolean restoreFaulted;
    private volatile PersistenceShutdownStatus shutdownStatus = PersistenceShutdownStatus.open();
    private volatile PersistenceReadinessCertificate readinessCertificate;
    private volatile ConvergenceTiming lastConvergenceTiming;
    private ReadinessProof validatedReadinessProof;
    private Set<String> startupDeferredDerivedOwners = Set.of();
    private DerivedStartupValidity activeDerivedStartupPreparation;
    private long derivedStartupPreparationGeneration;
    private volatile long readinessGeneration;
    private volatile long observedAuthorityEpoch;
    private volatile boolean authorityEpochTransition;

    public ReSyncPersistenceCoordinator(Path dataRoot, Path coordinationRoot) throws IOException {
        this(dataRoot, coordinationRoot, MigrationFence.systemWide(), null, null, true);
    }

    public ReSyncPersistenceCoordinator(Path dataRoot, Path coordinationRoot, MigrationFence fence) throws IOException {
        this(dataRoot, coordinationRoot, fence, null, null, true);
    }

    ReSyncPersistenceCoordinator(Path dataRoot, Path coordinationRoot, MigrationFence fence,
                                 RegistrationRootReader registrationRoots) throws IOException {
        this(dataRoot, coordinationRoot, fence, null, registrationRoots, true);
    }

    private ReSyncPersistenceCoordinator(Path dataRoot, Path coordinationRoot, MigrationFence fence,
                                         FreshRootProvenance freshRootProvenance,
                                         RegistrationRootReader registrationRoots,
                                         boolean recoverActivation) throws IOException {
        this.dataRoot = MigrationPaths.requireDirectory(dataRoot, "dataRoot");
        this.coordinationRoot = MigrationPaths.requirePath(coordinationRoot, "coordinationRoot");
        MigrationPaths.requireDistinctRoots(this.dataRoot, this.coordinationRoot);
        MigrationPaths.requireWritableParent(this.coordinationRoot);
        Files.createDirectories(this.coordinationRoot);
        if (Files.isSymbolicLink(this.coordinationRoot) || !Files.isDirectory(this.coordinationRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("coordinationRoot Must Be A Non-Symbolic-Link Directory");
        }
        this.snapshotRoot = this.coordinationRoot.resolve("snapshots").toAbsolutePath().normalize();
        this.restoreControlDirectory = this.coordinationRoot.resolve("restore-control").toAbsolutePath().normalize();
        this.fence = Objects.requireNonNull(fence, "fence");
        this.participants = new PersistenceParticipantRegistry(this.dataRoot);
        this.snapshots = new SnapshotService(this.fence);
        this.activation = new AtomicRestoreActivation(this.restoreControlDirectory);
        this.registrationRoots = registrationRoots == null ? this.activation::activeRoot : registrationRoots;
        this.authorityEpochStore = new AuthorityEpochStore(this.coordinationRoot);
        this.observedAuthorityEpoch = currentAuthorityEpochOrZero();
        this.restores = new RestoreService(this.fence, this.participants, this.activation, this::fenceRestoreReadiness);
        this.freshRootProvenance = freshRootProvenance;
        this.startupDerivedChecksRelaxed = freshRootProvenance != null;
        if (recoverActivation) {
            this.activation.recover();
            this.restoreFaulted = this.activation.recoveryPending();
        } else {
            this.restoreFaulted = true;
        }
    }

    public static ReSyncPersistenceCoordinator bootstrap(Path dataRoot, Path coordinationRoot) throws IOException {
        FreshRootProvenance provenance = FreshRootProvenance.initialize(dataRoot, coordinationRoot);
        return new ReSyncPersistenceCoordinator(dataRoot, coordinationRoot, MigrationFence.systemWide(), provenance,
            null, true);
    }

    public static PreparedBootstrap bootstrapPrepared(Path dataRoot, Path coordinationRoot) throws IOException {
        FreshRootProvenance provenance = FreshRootProvenance.initialize(dataRoot, coordinationRoot);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, coordinationRoot,
            MigrationFence.systemWide(), provenance, null, false);
        return new PreparedBootstrap(coordinator, coordinator.prepareActiveRoot());
    }

    public record PreparedBootstrap(ReSyncPersistenceCoordinator coordinator, Path activeRoot) {
        public PreparedBootstrap {
            Objects.requireNonNull(coordinator, "coordinator");
            activeRoot = Objects.requireNonNull(activeRoot, "activeRoot").toAbsolutePath().normalize();
        }
    }

    public void register(PersistenceParticipant participant) {
        registerAll(Collections.singletonList(participant));
    }

    public void registerAll(Collection<? extends PersistenceParticipant> candidates) {
        requireOpen();
        synchronized (this) {
            requireOpen();
            Objects.requireNonNull(candidates, "participants");
            List<PersistenceParticipant> requested = List.copyOf(candidates);
            if (requested.isEmpty()) {
                return;
            }
            Optional<Path> activeRoot;
            try {
                activeRoot = Objects.requireNonNull(registrationRoots.activeRoot(), "restore active root")
                    .map(root -> MigrationPaths.requirePath(root, "restore active root"));
            } catch (IOException exception) {
                throw new IllegalStateException("Restore Active Root Cannot Be Read", exception);
            }
            List<PersistenceParticipantRegistry.RegistrationWitness> registrations = requested.stream()
                .map(PersistenceParticipantRegistry::registrationWitness)
                .toList();
            for (PersistenceParticipantRegistry.RegistrationWitness registration : registrations) {
                requireRegistrationRoot(registration, activeRoot);
            }
            participants.registerAll(registrations, activeRoot.orElse(dataRoot));
            invalidateReadinessCertificate();
        }
    }

    private void requireRegistrationRoot(PersistenceParticipantRegistry.RegistrationWitness registration,
                                         Optional<Path> activeRoot) {
        Path participantRoot = registration.root();
        if (Files.isSymbolicLink(participantRoot)) {
            throw new IllegalArgumentException(
                "Persistence Participant Root Cannot Be A Symbolic Link: " + registration.owner());
        }
        Path registrationRoot = activeRoot.orElse(dataRoot);
        if (!participantRoot.startsWith(registrationRoot)) {
            throw new IllegalArgumentException(
                "Persistence Participant Root Is Outside ReSync Data Root: " + registration.owner());
        }
    }

    public void registerExternalInputs(Collection<PersistenceExternalInput.Input> inputs) {
        requireOpen();
        synchronized (this) {
            requireOpen();
            invalidateReadinessCertificate();
            participants.registerExternalInputs(inputs);
        }
    }

    public synchronized void registerEphemeral(EphemeralLifecycleParticipant participant) {
        requireOpen();
        invalidateReadinessCertificate();
        Objects.requireNonNull(participant, "participant");
        String owner = MigrationCanonical.requireText(participant.owner(), "ephemeral participant owner");
        if (ephemeralParticipants.putIfAbsent(owner, participant) != null) {
            throw new IllegalArgumentException("Ephemeral Participant Owner Is Already Registered: " + owner);
        }
    }

    public synchronized Collection<EphemeralLifecycleParticipant> ephemeralParticipants() {
        return List.copyOf(ephemeralParticipants.values());
    }

    public synchronized void seal() throws IOException {
        seal(Set.of());
    }

    private void seal(Set<String> deferredDerivedOwners) throws IOException {
        requireOpen();
        invalidateReadinessCertificate();
        Set<String> deferred = Set.copyOf(deferredDerivedOwners);
        if (participants.participants().isEmpty()) {
            throw new MigrationException("At Least One Persistence Participant Is Required");
        }
        if (!restoreParticipants()) {
            if (activation.activeRoot().isPresent()) {
                throw new MigrationException("Active Restore Root Requires Rebindable Persistence Participants");
            }
            participants.validateForRoot(dataRoot);
            bindAuthorityEpoch(activation.activeRoot().orElse(dataRoot));
            startupDeferredDerivedOwners = deferred;
            sealed = true;
            return;
        }
        convergeParticipants(deferred);
        startupDeferredDerivedOwners = deferred;
        sealed = true;
    }

    public synchronized ReadinessProof seal(PersistenceRootReadiness readiness) throws IOException {
        Objects.requireNonNull(readiness, "readiness");
        boolean declaredReadinessMatches = readiness.complete() && readinessMatchesParticipants(readiness);
        Set<String> deferredDerivedOwners = declaredReadinessMatches
            ? unavailableOptionalDerivedOwners(readiness)
            : Set.of();
        seal(deferredDerivedOwners);
        try {
            if (!declaredReadinessMatches) {
                throw new MigrationException("Declared Persistence Readiness Does Not Match The Registered Topology");
            }
            PersistenceRootReadiness convergedReadiness = readinessForCurrentParticipants(readiness);
            return establishReadinessProof(convergedReadiness)
                .orElseThrow(() -> new MigrationException("Sealed Persistence Topology Could Not Produce A Readiness Proof"));
        } catch (IOException | RuntimeException exception) {
            sealed = false;
            restoreFaulted = true;
            startupDeferredDerivedOwners = Set.of();
            invalidateReadinessCertificate();
            throw exception;
        }
    }

    public PreflightResult preflightSnapshot(Path stagingRoot, long reservedBytes) {
        requireSealed();
        return snapshots.preflight(snapshotSource(), stagingRoot, participants, reservedBytes);
    }

    public Snapshot createSnapshot(Path stagingRoot, SnapshotMetadata metadata) throws IOException {
        invalidateReadinessCertificate();
        requireSealed();
        prepareEphemeral(false);
        Throwable failure = null;
        try {
            return snapshots.create(activeDataRoot(), stagingRoot, metadata, participants);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            resumeEphemeral(false, failure);
        }
    }

    public RestorePreflight preflightRestore(RestoreRequest request) {
        requireSealed();
        return restores.preflight(request);
    }

    public RestoreResult restore(RestoreRequest request) throws IOException {
        invalidateReadinessCertificate();
        requireSealed();
        if (!restoreReady()) {
            throw new IllegalStateException("Persistence Participants Must Converge Before Restore");
        }
        prepareEphemeral(true);
        Throwable failure = null;
        RestoreResult result;
        try {
            result = restores.restore(request);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            fenceRestoreReadiness();
            throw exception;
        } finally {
            resumeEphemeral(true, failure);
        }
        confirmConvergence();
        return result;
    }

    public RestoreRecoveryResult recover(RestoreRequest request) throws IOException {
        invalidateReadinessCertificate();
        requireSealed();
        prepareEphemeral(true);
        Throwable failure = null;
        RestoreRecoveryResult result;
        try {
            result = restores.recover(request);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            fenceRestoreReadiness();
            throw exception;
        } finally {
            resumeEphemeral(true, failure);
        }
        confirmConvergence();
        return result;
    }

    public RestoreRecoveryResult rollback(RestoreRequest request) throws IOException {
        invalidateReadinessCertificate();
        requireSealed();
        prepareEphemeral(true);
        Throwable failure = null;
        RestoreRecoveryResult result;
        try {
            result = restores.rollback(request);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            fenceRestoreReadiness();
            throw exception;
        } finally {
            resumeEphemeral(true, failure);
        }
        confirmConvergence();
        return result;
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public synchronized Path prepareActiveRoot() throws IOException {
        requireOpen();
        invalidateReadinessCertificate();
        if (!participants.participants().isEmpty()) {
            throw new IllegalStateException("Active Root Must Be Prepared Before Persistence Participants");
        }
        Path activeRoot = activation.initialize(dataRoot);
        if (freshRootProvenance != null) {
            freshRootProvenance = freshRootProvenance.bind(activeRoot);
        }
        bindAuthorityEpoch(activeRoot);
        restoreFaulted = activation.recoveryPending();
        return activeRoot;
    }

    public synchronized Optional<FreshRootProvenance> freshRootProvenance() throws IOException {
        requireOpen();
        if (freshRootProvenance == null) {
            return Optional.empty();
        }
        Path activeRoot = activation.activeRoot()
            .orElseThrow(() -> new MigrationException("Fresh Root Provenance Has No Prepared Active Root"));
        freshRootProvenance.verify(coordinationRoot, activeRoot);
        return Optional.of(freshRootProvenance);
    }

    public synchronized boolean freshBootstrap() {
        return freshRootProvenance != null;
    }

    public synchronized ReSyncDataFixer.Result prepareDataFixes(ReSyncDataFixer fixer,
                                                                 boolean allowCurrentBaseline) throws IOException {
        requireOpen();
        invalidateReadinessCertificate();
        if (!participants.participants().isEmpty()) {
            throw new IllegalStateException("ReSync Data Fixes Must Run Before Persistence Participants");
        }
        ReSyncDataFixer dataFixer = Objects.requireNonNull(fixer, "fixer");
        Path activeRoot = activation.activeRoot()
            .orElseThrow(() -> new MigrationException("ReSync Data Fixes Require An Active Root"));
        activation.converged(activeRoot);
        ReSyncDataFixer.Result result = dataFixer.prepare(activeRoot, coordinationRoot, allowCurrentBaseline,
            activation::activate);
        activation.converged(result.activeRoot());
        Path preparedRoot = activation.activeRoot()
            .orElseThrow(() -> new MigrationException("ReSync Data Fixes Removed The Active Root"));
        if (!preparedRoot.equals(result.activeRoot())) {
            throw new MigrationException("ReSync Data Fix Result Does Not Match The Active Root");
        }
        bindAuthorityEpoch(preparedRoot);
        restoreFaulted = activation.recoveryPending();
        return result;
    }

    public synchronized boolean freshDerivedRepairAuthorized() {
        return freshRootProvenance != null && startupDerivedChecksRelaxed && sealed && !shutdownStarted();
    }

    public synchronized void completeStartupActivation() {
        requireSealed();
        requireNoDeferredDerivedActivation();
        invalidateReadinessCertificate();
        boolean wasRelaxed = startupDerivedChecksRelaxed;
        startupDerivedChecksRelaxed = false;
        try {
            if (!restoreReady()) {
                throw new IllegalStateException("Persistence Participants Must Be Strictly Restore-Ready");
            }
        } catch (RuntimeException | Error failure) {
            startupDerivedChecksRelaxed = wasRelaxed;
            throw failure;
        }
    }

    public synchronized ReadinessProof completeStartupActivation(PersistenceRootReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        requireSealed();
        requireNoDeferredDerivedActivation();
        boolean wasRelaxed = startupDerivedChecksRelaxed;
        startupDerivedChecksRelaxed = false;
        invalidateReadinessCertificate();
        try {
            return validateRestoreReadinessFresh(readiness)
                .orElseThrow(() -> new IllegalStateException("Persistence Participants Must Be Strictly Restore-Ready"));
        } catch (RuntimeException | Error failure) {
            startupDerivedChecksRelaxed = wasRelaxed;
            invalidateReadinessCertificate();
            throw failure;
        }
    }

    public synchronized ReadinessProof deriveStartupReadiness(ReadinessProof proof, Collection<String> derivedOwners) {
        return transitionDerivedStartupReadiness(proof, derivedOwners, false, false);
    }

    public ReadinessProof completeStartupActivation(ReadinessProof proof, Collection<String> derivedOwners) {
        Set<String> requested = derivedOwners == null ? Set.of() : derivedOwners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "derived participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        if (!requested.isEmpty()) {
            throw new IllegalStateException("Derived Startup Preparation Is Required");
        }
        return completeStartupActivation(proof, requested, context -> {
        });
    }

    public ReadinessProof completeStartupActivation(ReadinessProof proof, Collection<String> derivedOwners,
                                                    DerivedStartupPreparation preparation) {
        Objects.requireNonNull(preparation, "preparation");
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        Set<String> scope = Set.of();
        boolean callbackStarted = false;
        boolean transitionStarted = false;
        try {
            DerivedStartupContext context = null;
            synchronized (this) {
                requireSealed();
                requireCurrentReadinessProofState(proof);
                scope = requirePendingDerivedScope(proof, derivedOwners);
                if (!scope.isEmpty()) {
                    requireCurrentReadinessProof(proof);
                    if (activeDerivedStartupPreparation != null) {
                        throw new IllegalStateException("Derived Startup Preparation Is Already Active");
                    }
                    DerivedStartupValidity validity = new DerivedStartupValidity(
                        this, proof, scope, Math.addExact(derivedStartupPreparationGeneration, 1L), Thread.currentThread());
                    derivedStartupPreparationGeneration = validity.preparationGeneration;
                    activeDerivedStartupPreparation = validity;
                    context = new DerivedStartupContext(validity);
                }
            }
            if (scope.isEmpty()) {
                return finalizeEmptyStartupActivation(proof);
            }
            DerivedStartupContext activeContext = Objects.requireNonNull(context, "derived startup context");
            callbackStarted = true;
            try {
                preparation.prepare(activeContext);
            } finally {
                synchronized (this) {
                    activeContext.close();
                    if (activeDerivedStartupPreparation == activeContext.validity) {
                        activeDerivedStartupPreparation = null;
                    }
                }
            }
            synchronized (this) {
                requireCurrentReadinessProof(proof);
                if (!scope.equals(requirePendingDerivedScope(proof, scope))) {
                    throw new IllegalStateException("Derived Startup Preparation Scope Became Stale");
                }
                transitionStarted = true;
            }
            try {
                participants.activateAndValidate(scope, PersistenceParticipantClassification.DERIVED_CACHE);
            } catch (IOException failure) {
                synchronized (this) {
                    preserveOrInvalidateReadinessProof(proof, true);
                }
                throw new IllegalStateException(
                    "Derived Persistence Participants Could Not Reach Startup Readiness", failure);
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    preserveOrInvalidateReadinessProof(proof, true);
                }
                throw failure;
            }
            try {
                synchronized (this) {
                    return promoteDerivedStartupReadiness(proof, scope, true);
                }
            } catch (RuntimeException | Error failure) {
                compensateDerivedActivation(scope, failure);
                synchronized (this) {
                    preserveOrInvalidateReadinessProof(proof, true);
                }
                throw failure;
            }
        } catch (IOException failure) {
            if (callbackStarted && !transitionStarted) {
                compensateDerivedActivation(scope, failure);
            }
            throw new IllegalStateException("Derived Startup Preparation Failed", failure);
        } catch (RuntimeException | Error failure) {
            if (callbackStarted && !transitionStarted) {
                compensateDerivedActivation(scope, failure);
            }
            throw failure;
        } finally {
            migration.close();
        }
    }

    private ReadinessProof finalizeEmptyStartupActivation(ReadinessProof proof) {
        boolean wasRelaxed;
        synchronized (this) {
            requireCurrentReadinessProofState(proof);
            if (!startupDeferredDerivedOwners.isEmpty()) {
                return proof;
            }
            wasRelaxed = startupDerivedChecksRelaxed;
            startupDerivedChecksRelaxed = false;
        }
        try {
            if (!performFinalStartupReadinessValidation(proof)) {
                throw new IllegalStateException("Persistence Participants Must Be Strictly Restore-Ready");
            }
            synchronized (this) {
                requireCurrentReadinessProofState(proof);
                if (!startupDeferredDerivedOwners.isEmpty()) {
                    throw new IllegalStateException("Deferred Derived Persistence Participants Require Scoped Activation");
                }
                return promoteValidatedDerivedStartupReadiness(proof, Set.of(), true);
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                startupDerivedChecksRelaxed = wasRelaxed;
                preserveOrInvalidateReadinessProof(proof, true);
            }
            throw failure;
        }
    }

    private ReadinessProof transitionDerivedStartupReadiness(ReadinessProof proof, Collection<String> derivedOwners,
                                                             boolean completeActivation,
                                                             boolean preserveProofOnFailure) {
        requireSealed();
        requireCurrentReadinessProof(proof);
        Set<String> scope = derivedOwners == null ? Set.of() : derivedOwners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "derived participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        boolean wasRelaxed = startupDerivedChecksRelaxed;
        boolean activated = false;
        try {
            participants.activateAndValidate(scope, PersistenceParticipantClassification.DERIVED_CACHE);
            activated = true;
            return promoteDerivedStartupReadiness(proof, scope, completeActivation);
        } catch (IOException failure) {
            if (activated) {
                compensateDerivedActivation(scope, failure);
            }
            startupDerivedChecksRelaxed = wasRelaxed;
            preserveOrInvalidateReadinessProof(proof, preserveProofOnFailure);
            throw new IllegalStateException("Derived Persistence Participants Could Not Reach Startup Readiness", failure);
        } catch (RuntimeException | Error failure) {
            if (activated) {
                compensateDerivedActivation(scope, failure);
            }
            startupDerivedChecksRelaxed = wasRelaxed;
            preserveOrInvalidateReadinessProof(proof, preserveProofOnFailure);
            throw failure;
        }
    }

    private ReadinessProof promoteDerivedStartupReadiness(ReadinessProof proof, Set<String> scope,
                                                          boolean completeActivation) {
        requireCurrentReadinessProof(proof);
        return promoteValidatedDerivedStartupReadiness(proof, scope, completeActivation);
    }

    private ReadinessProof promoteValidatedDerivedStartupReadiness(ReadinessProof proof, Set<String> scope,
                                                                   boolean completeActivation) {
        requireCurrentReadinessProofState(proof);
        if (!startupDeferredDerivedOwners.containsAll(scope)) {
            throw new IllegalStateException("Derived Persistence Scope Is Not Pending");
        }
        Set<String> remainingDeferredOwners = startupDeferredDerivedOwners.stream()
            .filter(owner -> !scope.contains(owner))
            .collect(Collectors.toUnmodifiableSet());
        if (!proofMatchesCoordinatorState(proof)) {
            throw new IllegalStateException("Persistence Readiness Proof Became Stale During Derived Activation");
        }
        PersistenceRootReadiness readiness = derivedReadiness(proof.readiness(), scope);
        if (!readinessMatchesParticipants(readiness)) {
            throw new IllegalStateException("Derived Persistence Readiness No Longer Matches The Sealed Topology");
        }
        if (completeActivation && remainingDeferredOwners.isEmpty()) {
            startupDerivedChecksRelaxed = false;
        }
        ReadinessProof derived = new ReadinessProof(
            proof.generation(), proof.topologyEpoch(), proof.authorityEpoch(), proof.activeRoot(), readiness,
            proof.rebind(), proof.ephemeralLifecycles(), Math.addExact(proof.derivationGeneration(), 1L));
        readinessCertificate = null;
        validatedReadinessProof = derived;
        startupDeferredDerivedOwners = remainingDeferredOwners;
        return derived;
    }

    public Path activeDataRoot() throws IOException {
        return activation.activeRoot().orElse(dataRoot);
    }

    public long authorityEpoch() throws IOException {
        long epoch = observedAuthorityEpoch;
        if (authorityEpochTransition || epoch < 1L) {
            throw new IllegalStateException("Authority Epoch Is Not Bound To An Active Root");
        }
        return epoch;
    }

    public synchronized long readinessGeneration() {
        return readinessGeneration;
    }

    public ReadinessObservation readinessObservation() {
        return new ReadinessObservation(readinessGeneration, participants.ownershipEpoch(), readinessCertificate,
            observedAuthorityEpoch, authorityEpochTransition, sealed, restoreFaulted, shutdownStatus);
    }

    public boolean isCurrent(ReadinessObservation observation) {
        long generation = readinessGeneration;
        return observation != null && observation.owner == this && observation.generation == generation
            && observation.topologyEpoch == participants.ownershipEpoch()
            && observation.certificate == readinessCertificate && observation.sealed == sealed
            && observation.authorityEpoch == observedAuthorityEpoch && observation.restoreFaulted == restoreFaulted
            && !observation.authorityEpochTransition && !authorityEpochTransition
            && observation.shutdown == shutdownStatus && generation == readinessGeneration;
    }

    public final class ReadinessObservation {
        private final ReSyncPersistenceCoordinator owner = ReSyncPersistenceCoordinator.this;
        private final long generation;
        private final long topologyEpoch;
        private final PersistenceReadinessCertificate certificate;
        private final long authorityEpoch;
        private final boolean authorityEpochTransition;
        private final boolean sealed;
        private final boolean restoreFaulted;
        private final PersistenceShutdownStatus shutdown;

        private ReadinessObservation(long generation, long topologyEpoch, PersistenceReadinessCertificate certificate,
                                     long authorityEpoch, boolean authorityEpochTransition, boolean sealed, boolean restoreFaulted,
                                     PersistenceShutdownStatus shutdown) {
            this.generation = generation;
            this.topologyEpoch = topologyEpoch;
            this.certificate = certificate;
            this.authorityEpoch = authorityEpoch;
            this.authorityEpochTransition = authorityEpochTransition;
            this.sealed = sealed;
            this.restoreFaulted = restoreFaulted;
            this.shutdown = shutdown;
        }

        public long authorityEpoch() {
            if (authorityEpochTransition || authorityEpoch < 1L) {
                throw new IllegalStateException("Authority Epoch Is Not Bound To An Active Root");
            }
            return authorityEpoch;
        }
    }

    public synchronized Optional<PersistenceReadinessCertificate> readinessCertificate() {
        return currentReadinessCertificate();
    }

    public synchronized Optional<PersistenceReadinessCertificate> currentReadinessCertificate() {
        PersistenceReadinessCertificate candidate = readinessCertificate;
        return candidate != null && certificateMatchesCurrentState(candidate)
            ? Optional.of(candidate) : Optional.empty();
    }

    public synchronized Optional<PersistenceReadinessCertificate> currentReadinessCertificate(
        PersistenceRootReadiness expectedReadiness) {
        if (expectedReadiness == null) {
            return Optional.empty();
        }
        PersistenceReadinessCertificate candidate = readinessCertificate;
        return candidate != null && candidate.readiness() == expectedReadiness
            && certificateMatchesCurrentState(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    public synchronized long invalidateReadinessCertificate() {
        readinessCertificate = null;
        validatedReadinessProof = null;
        readinessGeneration = nextReadinessGeneration();
        return readinessGeneration;
    }

    public synchronized Optional<ReadinessProof> validateRestoreReadiness(
        PersistenceRootReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        invalidateReadinessCertificate();
        return validateRestoreReadinessFresh(readiness);
    }

    public synchronized Optional<ReadinessProof> currentValidatedReadinessProof(
        PersistenceRootReadiness expectedReadiness) {
        if (expectedReadiness == null) {
            return Optional.empty();
        }
        ReadinessProof candidate = validatedReadinessProof;
        return candidate != null && candidate.readiness() == expectedReadiness && proofMatchesCurrentState(candidate)
            ? Optional.of(candidate) : Optional.empty();
    }

    private Optional<ReadinessProof> validateRestoreReadinessFresh(PersistenceRootReadiness readiness) {
        if (!readiness.complete() || !readinessMatchesParticipants(readiness)
            || !readinessPreservesDeferredDerivedOwners(readiness)
            || !performRestoreReadinessValidation()) {
            return Optional.empty();
        }
        try {
            return establishReadinessProof(readiness);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<ReadinessProof> establishReadinessProof(PersistenceRootReadiness readiness) throws IOException {
        if (!sealed || restoreFaulted || shutdownStarted() || !readiness.complete()
            || !readinessMatchesParticipants(readiness) || !readinessPreservesDeferredDerivedOwners(readiness)) {
            return Optional.empty();
        }
        try {
            Path activeRoot = authorityEpochStore.boundRoot();
            if (activeRoot == null) {
                return Optional.empty();
            }
            long epoch = authorityEpochStore.current();
            long topologyEpoch = participants.ownershipEpoch();
            PersistenceRebindStatus rebind = participants.rebindStatus();
            Optional<Path> activationRoot = activation.activeRoot();
            if (rebind.state() != PersistenceRebindStatus.State.COMMITTED
                || activationRoot.isEmpty() || !activeRoot.equals(activationRoot.orElseThrow())
                || rebind.activeRoot().isEmpty() || !activeRoot.equals(rebind.activeRoot().orElseThrow())) {
                return Optional.empty();
            }
            List<PersistenceReadinessCertificate.Ephemeral> ephemeralLifecycles = new ArrayList<>();
            for (EphemeralLifecycleParticipant participant : ephemeralSnapshot()) {
                participant.healthCheck();
                EphemeralLifecycleParticipant.Health health = participant.lifecycleHealth();
                ephemeralLifecycles.add(new PersistenceReadinessCertificate.Ephemeral(
                    participant.owner(), health != null && health.available(),
                    health == null ? "UNKNOWN" : health.state(),
                    health == null ? 0 : health.physicalTasks(),
                    health == null ? "Ephemeral Lifecycle Health Is Unavailable" : health.reason()));
            }
            ReadinessProof proof = new ReadinessProof(
                readinessGeneration, topologyEpoch, epoch, activeRoot, readiness, rebind, ephemeralLifecycles, 0L);
            validatedReadinessProof = proof;
            return Optional.of(proof);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean proofMatchesCurrentState(ReadinessProof proof) {
        if (!proofMatchesCoordinatorState(proof)) {
            return false;
        }
        try {
            return activation.activeRoot().equals(Optional.of(proof.activeRoot()));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean proofMatchesCoordinatorState(ReadinessProof proof) {
        if (proof == null || proof.generation() != readinessGeneration || !sealed || restoreFaulted
            || shutdownStarted() || !proof.readiness().complete()
            || !readinessPreservesDeferredDerivedOwners(proof.readiness())
            || proof.topologyEpoch() != participants.ownershipEpoch()) {
            return false;
        }
        try {
            Path activeRoot = authorityEpochStore.boundRoot();
            long epoch = authorityEpochStore.current();
            PersistenceRebindStatus rebind = participants.rebindStatus();
            return proof.activeRoot().equals(activeRoot) && proof.authorityEpoch() == epoch && proof.rebind() == rebind
                && rebind.state() == PersistenceRebindStatus.State.COMMITTED && rebind.activeRoot().isPresent()
                && proof.activeRoot().equals(rebind.activeRoot().orElseThrow());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized Optional<PersistenceReadinessCertificate> publishReadinessCertificate(
        ReadinessProof proof) {
        if (proof == null || proof != validatedReadinessProof || !proofMatchesCurrentState(proof)) {
            if (proof == validatedReadinessProof) {
                readinessCertificate = null;
                validatedReadinessProof = null;
            }
            return Optional.empty();
        }
        if (!startupDeferredDerivedOwners.isEmpty()) {
            readinessCertificate = null;
            return Optional.empty();
        }
        try {
            Path activeRoot = authorityEpochStore.boundRoot();
            long epoch = authorityEpochStore.current();
            PersistenceRebindStatus rebind = participants.rebindStatus();
            if (!proof.activeRoot().equals(activeRoot) || proof.authorityEpoch() != epoch
                || proof.rebind() != rebind || rebind.state() != PersistenceRebindStatus.State.COMMITTED
                || rebind.activeRoot().isEmpty() || !proof.activeRoot().equals(rebind.activeRoot().orElseThrow())) {
                readinessCertificate = null;
                validatedReadinessProof = null;
                return Optional.empty();
            }
            PersistenceReadinessCertificate candidate = new PersistenceReadinessCertificate(
                proof.generation(), proof.topologyEpoch(), proof.authorityEpoch(), proof.activeRoot(), proof.readiness(),
                proof.rebind(), proof.derivationGeneration(), proof.ephemeralLifecycles());
            readinessCertificate = candidate;
            validatedReadinessProof = null;
            return Optional.of(candidate);
        } catch (RuntimeException exception) {
            readinessCertificate = null;
            validatedReadinessProof = null;
            return Optional.empty();
        }
    }

    public synchronized Optional<PersistenceReadinessCertificate> publishReadinessCertificate(
        PersistenceRootReadiness readiness, long validationGeneration) throws IOException {
        if (validationGeneration != readinessGeneration) {
            return Optional.empty();
        }
        return validateRestoreReadiness(readiness).flatMap(this::publishReadinessCertificate);
    }

    public AuthorityEpochStore authorityEpochStore() {
        return authorityEpochStore;
    }

    public Path coordinationRoot() {
        return coordinationRoot;
    }

    public Path snapshotRoot() {
        return snapshotRoot;
    }

    public Path restoreControlDirectory() {
        return restoreControlDirectory;
    }

    public MigrationFence fence() {
        return fence;
    }

    public PersistenceParticipantRegistry participants() {
        return participants;
    }

    public AtomicRestoreActivation activation() {
        return activation;
    }

    public Collection<PersistenceParticipant> registeredParticipants() {
        return participants.participants();
    }

    public void requireRegisteredParticipant(PersistenceParticipant participant) {
        if (shutdownStarted()) {
            throw new IllegalStateException("Persistence Coordinator Is Not Active");
        }
        participants.requireRegisteredParticipant(participant);
    }

    public synchronized PersistenceShutdownStatus quiesce() throws IOException {
        invalidateReadinessCertificate();
        if (shutdownStatus.state() == PersistenceShutdownStatus.State.CLOSED
            || shutdownStatus.state() == PersistenceShutdownStatus.State.FAILED
            || shutdownStatus.state() == PersistenceShutdownStatus.State.QUIESCED) {
            return shutdownStatus;
        }
        beginShutdown();
        MigrationFence.MigrationLease migration = null;
        try {
            migration = fence.acquireMigration();
            prepareEphemeral(false, true);
            shutdownStatus = participants.quiesceForShutdown();
            return shutdownStatus;
        } catch (IOException | RuntimeException exception) {
            restoreFaulted = true;
            if (participants.shutdownStatus().state() == PersistenceShutdownStatus.State.QUIESCING) {
                participants.failShutdown("fence", exception);
            }
            shutdownStatus = participants.shutdownStatus();
            throw exception;
        } finally {
            if (migration != null) {
                migration.close();
            }
        }
    }

    public synchronized PersistenceShutdownStatus close() throws IOException {
        invalidateReadinessCertificate();
        if (shutdownStatus.state() == PersistenceShutdownStatus.State.CLOSED
            || shutdownStatus.state() == PersistenceShutdownStatus.State.FAILED) {
            return shutdownStatus;
        }
        if (shutdownStatus.state() != PersistenceShutdownStatus.State.QUIESCED) {
            try {
                quiesce();
            } catch (IOException | RuntimeException exception) {
                participants.closeForShutdown();
                shutdownStatus = participants.shutdownStatus();
                restoreFaulted = true;
                throw exception;
            }
        }
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        try {
            shutdownStatus = participants.closeForShutdown();
            restoreFaulted = true;
            return shutdownStatus;
        } finally {
            migration.close();
        }
    }

    public synchronized PersistenceShutdownStatus retryClose() throws IOException {
        invalidateReadinessCertificate();
        if (shutdownStatus.state() == PersistenceShutdownStatus.State.CLOSED) {
            return shutdownStatus;
        }
        if (shutdownStatus.state() != PersistenceShutdownStatus.State.FAILED) {
            return close();
        }
        try {
            participants.retryShutdown();
        } finally {
            shutdownStatus = participants.shutdownStatus();
        }
        return close();
    }

    public PersistenceShutdownStatus shutdown() throws IOException {
        return close();
    }

    public PersistenceShutdownStatus beginShutdown() {
        invalidateReadinessCertificate();
        if (shutdownStatus.state() == PersistenceShutdownStatus.State.OPEN) {
            fence.closeMutations();
            participants.beginShutdown();
            shutdownStatus = participants.shutdownStatus();
            restoreFaulted = true;
        }
        return shutdownStatus;
    }

    public PersistenceShutdownStatus shutdownStatus() {
        return shutdownStatus;
    }

    public boolean shutdownStarted() {
        return shutdownStatus.started() || participants.shutdownStarted();
    }

    public boolean sealed() {
        return sealed;
    }

    public synchronized Optional<ConvergenceTiming> lastConvergenceTiming() {
        return Optional.ofNullable(lastConvergenceTiming);
    }

    public synchronized boolean restoreReady() {
        invalidateReadinessCertificate();
        return performRestoreReadinessValidation();
    }

    private synchronized boolean performRestoreReadinessValidation() {
        if (!sealed || restoreFaulted || shutdownStarted()) {
            return false;
        }
        if (!participants.rebindStatus().stable()) {
            return false;
        }
        try {
            if (activation.recoveryPending()) {
                return false;
            }
            Path activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Required"));
            if (!activeRoot.equals(authorityEpochStore.boundRoot())) {
                return false;
            }
            authorityEpochStore.current();
            PersistenceRebindStatus status = participants.rebindStatus();
            if (status.state() != PersistenceRebindStatus.State.COMMITTED || !status.activeRoot().equals(Optional.of(activeRoot))) {
                return false;
            }
            for (EphemeralLifecycleParticipant participant : ephemeralSnapshot()) {
                participant.healthCheck();
            }
            if (startupDerivedChecksRelaxed || !startupDeferredDerivedOwners.isEmpty()) {
                participants.readinessCheckAll(PersistenceParticipantClassification.AUTHORITATIVE);
            } else {
                participants.readinessCheckAll();
            }
            participants.validateRestoreReadiness(activeRoot);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private synchronized boolean performFinalStartupReadinessValidation(ReadinessProof proof) {
        if (!proofMatchesCoordinatorState(proof)) {
            return false;
        }
        try {
            if (activation.recoveryIntentPending()) {
                return false;
            }
            Path activeRoot = proof.activeRoot();
            for (EphemeralLifecycleParticipant participant : ephemeralSnapshot()) {
                participant.healthCheck();
            }
            participants.readinessCheckAll();
            participants.validateRestoreReadiness(activeRoot);
            Optional<Path> activationRoot = activation.restoreReadyActiveRoot();
            return activationRoot.equals(Optional.of(activeRoot)) && proofMatchesCoordinatorState(proof);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean readinessMatchesParticipants(PersistenceRootReadiness readiness) {
        try {
            Map<String, PersistenceParticipant> live = new LinkedHashMap<>();
            for (PersistenceParticipant participant : participants.participants()) {
                live.put(participant.owner(), participant);
            }
            for (PersistenceRootReadiness.Owner owner : readiness.owners()) {
                PersistenceParticipant participant = live.remove(owner.owner());
                if (participant == null) {
                    if (owner.state() == PersistenceRootReadiness.State.REGISTERED) {
                        return false;
                    }
                    continue;
                }
                if (owner.classification() != participant.classification()
                    || !owner.root().equals(MigrationPaths.requirePath(participant.root(), "participant root"))) {
                    return false;
                }
            }
            return live.isEmpty() && readiness.externalInputs().equals(participants.externalInputs());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PersistenceRootReadiness readinessForCurrentParticipants(PersistenceRootReadiness readiness) {
        Map<String, PersistenceParticipant> live = new LinkedHashMap<>();
        for (PersistenceParticipant participant : participants.participants()) {
            live.put(participant.owner(), participant);
        }
        List<PersistenceRootReadiness.Owner> owners = new ArrayList<>();
        for (PersistenceRootReadiness.Owner owner : readiness.owners()) {
            PersistenceParticipant participant = live.get(owner.owner());
            if (participant == null) {
                owners.add(owner);
                continue;
            }
            owners.add(new PersistenceRootReadiness.Owner(owner.owner(),
                MigrationPaths.requirePath(participant.root(), "participant root"), owner.required(),
                owner.classification(), owner.state(), owner.reason()));
        }
        return new PersistenceRootReadiness(owners, readiness.uncoveredWriters(), readiness.externalInputs());
    }

    private PersistenceRootReadiness derivedReadiness(PersistenceRootReadiness readiness, Set<String> scope) {
        Map<String, PersistenceParticipant> live = new LinkedHashMap<>();
        for (PersistenceParticipant participant : participants.participants()) {
            live.put(participant.owner(), participant);
        }
        List<PersistenceRootReadiness.Owner> owners = new ArrayList<>();
        for (PersistenceRootReadiness.Owner owner : readiness.owners()) {
            if (!scope.contains(owner.owner())) {
                owners.add(owner);
                continue;
            }
            PersistenceParticipant participant = live.get(owner.owner());
            if (participant == null || participant.classification() != PersistenceParticipantClassification.DERIVED_CACHE
                || owner.classification() != PersistenceParticipantClassification.DERIVED_CACHE
                || !owner.root().equals(MigrationPaths.requirePath(participant.root(), "participant root"))) {
                throw new IllegalStateException("Derived Persistence Scope Does Not Match The Sealed Topology: " + owner.owner());
            }
            owners.add(PersistenceRootReadiness.Owner.registered(owner.owner(), owner.root(), owner.required(), owner.classification()));
        }
        if (owners.stream().filter(owner -> scope.contains(owner.owner())).count() != scope.size()) {
            throw new IllegalStateException("Derived Persistence Scope Is Missing From The Sealed Topology");
        }
        return new PersistenceRootReadiness(owners, readiness.uncoveredWriters(), readiness.externalInputs());
    }

    private boolean certificateMatchesCurrentState(PersistenceReadinessCertificate certificate) {
        if (!sealed || restoreFaulted || shutdownStarted() || certificate.generation() != readinessGeneration
            || certificate.topologyEpoch() != participants.ownershipEpoch()) {
            return false;
        }
        try {
            Path boundRoot = authorityEpochStore.boundRoot();
            Optional<Path> activationRoot = activation.activeRoot();
            return boundRoot != null
                && certificate.authorityEpoch() == authorityEpochStore.current()
                && certificate.activeRoot().equals(boundRoot)
                && activationRoot.equals(Optional.of(certificate.activeRoot()))
                && certificate.rebind() == participants.rebindStatus()
                && certificate.readiness().complete();
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void prepareEphemeral(boolean restore) throws IOException {
        prepareEphemeral(restore, false);
    }

    private void prepareEphemeral(boolean restore, boolean shutdown) throws IOException {
        List<EphemeralLifecycleParticipant> prepared = new ArrayList<>();
        try {
            for (EphemeralLifecycleParticipant participant : ephemeralSnapshot()) {
                EphemeralLifecycleParticipant.Health current = participant.lifecycleHealth();
                if (shutdown && current != null && "CLOSED".equalsIgnoreCase(current.state())) {
                    if (current.physicalTasks() > 0 || !current.reason().isBlank()) {
                        throw new IOException("Closed Ephemeral Participant Is Not Healthy: " + participant.owner());
                    }
                    continue;
                }
                CompletionStage<Void> stage = restore ? participant.prepareRestore() : participant.prepareSnapshot();
                await(stage, participant.owner());
                participant.healthCheck();
                prepared.add(participant);
            }
        } catch (IOException | RuntimeException exception) {
            resumeEphemeral(restore, exception, prepared);
            throw exception;
        }
    }

    private void resumeEphemeral(boolean restore, Throwable failure) throws IOException {
        resumeEphemeral(restore, failure, ephemeralSnapshot());
    }

    private void resumeEphemeral(boolean restore, Throwable failure, Collection<EphemeralLifecycleParticipant> participantsToResume) throws IOException {
        IOException resumeFailure = null;
        List<EphemeralLifecycleParticipant> ordered = new ArrayList<>(participantsToResume);
        ordered.sort(Comparator.comparing(EphemeralLifecycleParticipant::owner).reversed());
        for (EphemeralLifecycleParticipant participant : ordered) {
            try {
                if (restore) {
                    participant.resumeAfterRestore();
                } else {
                    participant.resumeAfterSnapshot();
                }
            } catch (RuntimeException exception) {
                IOException current = new IOException("Ephemeral Participant Resume Failed: " + participant.owner(), exception);
                if (resumeFailure == null) {
                    resumeFailure = current;
                } else {
                    resumeFailure.addSuppressed(current);
                }
            }
        }
        if (resumeFailure != null) {
            if (failure != null) {
                failure.addSuppressed(resumeFailure);
            } else {
                throw resumeFailure;
            }
        }
    }

    private List<EphemeralLifecycleParticipant> ephemeralSnapshot() {
        synchronized (this) {
            return ephemeralParticipants.values().stream()
                .sorted(Comparator.comparing(EphemeralLifecycleParticipant::owner))
                .toList();
        }
    }

    private void await(CompletionStage<Void> stage, String owner) throws IOException {
        if (stage == null) {
            throw new IOException("Ephemeral Participant Returned No Drain Stage: " + owner);
        }
        try {
            stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Ephemeral Participant Drain Was Interrupted: " + owner, exception);
        } catch (ExecutionException | CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IOException("Ephemeral Participant Drain Failed: " + owner, cause);
        }
    }

    private void requireOpen() {
        if (shutdownStarted()) {
            throw new IllegalStateException("Persistence Coordinator Is Shutting Down");
        }
        if (sealed) {
            throw new IllegalStateException("Persistence Participants Are Already Sealed");
        }
    }

    private void requireSealed() {
        if (shutdownStarted()) {
            throw new IllegalStateException("Persistence Coordinator Is Shutting Down");
        }
        if (!sealed) {
            throw new IllegalStateException("Persistence Participants Must Be Sealed Before Use");
        }
    }

    private void fenceRestoreReadiness() {
        invalidateReadinessCertificate();
        restoreFaulted = true;
    }

    private Path snapshotSource() {
        try {
            return activeDataRoot();
        } catch (IOException exception) {
            throw new IllegalStateException("Snapshot Active Root Cannot Be Read", exception);
        }
    }

    private boolean restoreParticipants() {
        return participants.participants().stream().allMatch(RebindablePersistenceParticipant.class::isInstance);
    }

    private void convergeParticipants(Set<String> deferredDerivedOwners) throws IOException {
        long convergenceStarted = System.nanoTime();
        long convergenceCpuStarted = currentThreadCpuNanos();
        Map<String, Long> phaseCpuMillis = new LinkedHashMap<>();
        long flushMillis = 0L;
        long quiesceMillis = 0L;
        long rebindMillis = 0L;
        long authoritativeChecksMillis = 0L;
        long resumeMillis = 0L;
        long derivedChecksMillis = 0L;
        long ownershipValidationMillis = 0L;
        long activationMillis = 0L;
        String outcome = "failed";
        participants.resetLifecycleTimings();
        invalidateReadinessCertificate();
        restoreFaulted = true;
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        boolean quiesced = false;
        boolean resumed = false;
        Throwable primary = null;
        try {
            long phaseStarted = System.nanoTime();
            long phaseCpuStarted = currentThreadCpuNanos();
            if (startupDerivedChecksRelaxed) {
                participants.flushAll(PersistenceParticipantClassification.AUTHORITATIVE);
            } else if (!deferredDerivedOwners.isEmpty()) {
                participants.flushAllExcept(deferredDerivedOwners);
            } else {
                participants.flushAll();
            }
            flushMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("flush", currentThreadCpuMillis(phaseCpuStarted));
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            participants.quiesceAll();
            quiesceMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("quiesce", currentThreadCpuMillis(phaseCpuStarted));
            quiesced = true;
            Optional<Path> currentRoot = activation.activeRoot();
            Path activeRoot = currentRoot.isPresent() ? currentRoot.get() : activation.initialize(dataRoot);
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            participants.rebindAllProvisional(activeRoot);
            rebindMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("rebind", currentThreadCpuMillis(phaseCpuStarted));
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            participants.healthAndReadinessCheckAll(PersistenceParticipantClassification.AUTHORITATIVE);
            authoritativeChecksMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("authoritativeChecks", currentThreadCpuMillis(phaseCpuStarted));
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            participants.validateForRestore(activeRoot);
            participants.commitProvisionalRebind(activeRoot);
            ownershipValidationMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("ownershipValidation", currentThreadCpuMillis(phaseCpuStarted));
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            if (startupDerivedChecksRelaxed) {
                participants.resumeAll(PersistenceParticipantClassification.AUTHORITATIVE);
            } else if (!deferredDerivedOwners.isEmpty()) {
                participants.resumeAllExcept(deferredDerivedOwners);
            } else {
                participants.resumeAll();
            }
            resumed = true;
            resumeMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("resume", currentThreadCpuMillis(phaseCpuStarted));
            if (!startupDerivedChecksRelaxed) {
                phaseStarted = System.nanoTime();
                phaseCpuStarted = currentThreadCpuNanos();
                if (deferredDerivedOwners.isEmpty()) {
                    participants.healthAndReadinessCheckAll(PersistenceParticipantClassification.DERIVED_CACHE);
                } else {
                    participants.healthAndReadinessCheckAllExcept(
                        deferredDerivedOwners, PersistenceParticipantClassification.DERIVED_CACHE);
                }
                derivedChecksMillis = elapsedMillis(phaseStarted);
                phaseCpuMillis.put("derivedChecks", currentThreadCpuMillis(phaseCpuStarted));
            }
            phaseStarted = System.nanoTime();
            phaseCpuStarted = currentThreadCpuNanos();
            activation.converged(activeRoot);
            bindAuthorityEpoch(activeRoot);
            activationMillis = elapsedMillis(phaseStarted);
            phaseCpuMillis.put("activation", currentThreadCpuMillis(phaseCpuStarted));
            restoreFaulted = false;
            outcome = "complete";
        } catch (IOException | RuntimeException exception) {
            MigrationException failure = participants.failProvisionalRebind(exception);
            primary = failure;
            throw failure;
        } finally {
            try {
                if (quiesced && !resumed) {
                    if (startupDerivedChecksRelaxed) {
                        participants.resumeAll(PersistenceParticipantClassification.AUTHORITATIVE);
                    } else if (!deferredDerivedOwners.isEmpty()) {
                        participants.resumeAllExcept(deferredDerivedOwners);
                    } else {
                        participants.resumeAll();
                    }
                }
            } catch (IOException resumeFailure) {
                if (primary != null) {
                    primary.addSuppressed(resumeFailure);
                } else {
                    throw resumeFailure;
                }
            } finally {
                lastConvergenceTiming = new ConvergenceTiming(
                    flushMillis, quiesceMillis, rebindMillis, authoritativeChecksMillis, resumeMillis,
                    derivedChecksMillis, ownershipValidationMillis, activationMillis,
                    elapsedMillis(convergenceStarted), currentThreadCpuMillis(convergenceCpuStarted), outcome,
                    participants.lifecycleTimingsMillis(), participants.lifecycleCpuTimingsMillis(), phaseCpuMillis);
                migration.close();
            }
        }
    }

    private Set<String> unavailableOptionalDerivedOwners(PersistenceRootReadiness readiness) {
        Set<String> registeredOwners = participants.participants().stream()
            .map(PersistenceParticipant::owner)
            .collect(Collectors.toUnmodifiableSet());
        return readiness.owners().stream()
            .filter(owner -> !owner.required())
            .filter(owner -> owner.classification() == PersistenceParticipantClassification.DERIVED_CACHE)
            .filter(owner -> owner.state() == PersistenceRootReadiness.State.UNAVAILABLE)
            .filter(owner -> registeredOwners.contains(owner.owner()))
            .map(PersistenceRootReadiness.Owner::owner)
            .collect(Collectors.toUnmodifiableSet());
    }

    private void requireNoDeferredDerivedActivation() {
        if (!startupDeferredDerivedOwners.isEmpty()) {
            throw new IllegalStateException("Deferred Derived Persistence Participants Require Scoped Activation");
        }
    }

    private void requireCurrentReadinessProof(ReadinessProof proof) {
        if (proof == null || proof != validatedReadinessProof || !proofMatchesCurrentState(proof)) {
            throw new IllegalStateException("Persistence Readiness Proof Is Stale");
        }
    }

    private void requireCurrentReadinessProofState(ReadinessProof proof) {
        if (proof == null || proof != validatedReadinessProof || !proofMatchesCoordinatorState(proof)) {
            throw new IllegalStateException("Persistence Readiness Proof Is Stale");
        }
    }

    private synchronized void requireActiveDerivedStartupContext(DerivedStartupContext context, String owner) {
        DerivedStartupValidity validity = context.validity;
        requireActiveDerivedStartupContext(validity);
        String normalizedOwner = MigrationCanonical.requireText(owner, "derived participant owner");
        if (!validity.owners.contains(normalizedOwner)) {
            throw new IllegalStateException("Derived Startup Preparation Owner Is Not Pending: " + normalizedOwner);
        }
    }

    private synchronized void requireActiveDerivedStartupRoot(DerivedStartupContext context, Path activeRoot) {
        DerivedStartupValidity validity = context.validity;
        requireActiveDerivedStartupContext(validity);
        Path normalizedRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
        if (!validity.activeRoot.equals(normalizedRoot)) {
            throw new IllegalStateException("Derived Startup Preparation Active Root Does Not Match");
        }
    }

    private synchronized void requireActiveDerivedStartupContext(DerivedStartupContext context) {
        requireActiveDerivedStartupContext(context.validity);
    }

    private void requireActiveDerivedStartupContext(DerivedStartupValidity validity) {
        if (validity.coordinator != this || activeDerivedStartupPreparation != validity || !validity.active
            || validity.callbackThread != Thread.currentThread() || !fence.migrationActive()) {
            throw new IllegalStateException("Derived Startup Preparation Context Is Not Active");
        }
        requireCurrentReadinessProof(validity.proof);
        if (!startupDeferredDerivedOwners.containsAll(validity.owners)
            || !validity.owners.equals(requirePendingDerivedScope(validity.proof, validity.owners))) {
            throw new IllegalStateException("Derived Startup Preparation Scope Is Not Current");
        }
    }

    private Set<String> requirePendingDerivedScope(ReadinessProof proof, Collection<String> derivedOwners) {
        Set<String> requested = derivedOwners == null ? Set.of() : derivedOwners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "derived participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        if (requested.isEmpty()) {
            return requested;
        }
        if (!startupDeferredDerivedOwners.containsAll(requested)) {
            throw new IllegalStateException("Derived Startup Preparation Scope Is Not Pending");
        }
        for (String owner : requested) {
            PersistenceRootReadiness.Owner declared = proof.readiness().owner(owner);
            if (declared == null || declared.required()
                || declared.classification() != PersistenceParticipantClassification.DERIVED_CACHE
                || declared.state() != PersistenceRootReadiness.State.UNAVAILABLE) {
                throw new IllegalStateException("Derived Startup Preparation Scope Is Not Pending: " + owner);
            }
        }
        return requested;
    }

    private void preserveOrInvalidateReadinessProof(ReadinessProof proof, boolean preserve) {
        if (!preserve) {
            invalidateReadinessCertificate();
            return;
        }
        readinessCertificate = null;
        if (validatedReadinessProof == proof && proofMatchesCurrentState(proof)) {
            validatedReadinessProof = proof;
        }
    }

    private boolean readinessPreservesDeferredDerivedOwners(PersistenceRootReadiness readiness) {
        for (String owner : startupDeferredDerivedOwners) {
            PersistenceRootReadiness.Owner declared = readiness.owner(owner);
            if (declared == null || declared.required()
                || declared.classification() != PersistenceParticipantClassification.DERIVED_CACHE
                || declared.state() != PersistenceRootReadiness.State.UNAVAILABLE) {
                return false;
            }
        }
        return true;
    }

    private void compensateDerivedActivation(Set<String> scope, Throwable primary) {
        try {
            participants.quiesceScope(scope, PersistenceParticipantClassification.DERIVED_CACHE);
        } catch (IOException compensationFailure) {
            primary.addSuppressed(compensationFailure);
        }
    }

    private static long elapsedMillis(long started) {
        return started <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    private static long currentThreadCpuNanos() {
        return THREAD_CPU.isCurrentThreadCpuTimeSupported() ? Math.max(0L, THREAD_CPU.getCurrentThreadCpuTime()) : 0L;
    }

    private static long currentThreadCpuMillis(long started) {
        return started <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, currentThreadCpuNanos() - started));
    }

    @FunctionalInterface
    interface RegistrationRootReader {
        Optional<Path> activeRoot() throws IOException;
    }

    @FunctionalInterface
    public interface DerivedStartupPreparation {
        void prepare(DerivedStartupContext context) throws IOException;
    }

    public static final class DerivedStartupContext {
        private final DerivedStartupValidity validity;

        private DerivedStartupContext(DerivedStartupValidity validity) {
            this.validity = validity;
        }

        public Set<String> owners() {
            return validity.owners;
        }

        public Path activeRoot() {
            return validity.activeRoot;
        }

        public long generation() {
            return validity.proof.generation();
        }

        public long topologyEpoch() {
            return validity.proof.topologyEpoch();
        }

        public long authorityEpoch() {
            return validity.proof.authorityEpoch();
        }

        public long preparationGeneration() {
            return validity.preparationGeneration;
        }

        public void verify() {
            validity.coordinator.requireActiveDerivedStartupContext(this);
        }

        public void requireOwner(String owner) {
            validity.coordinator.requireActiveDerivedStartupContext(this, owner);
        }

        public void requireActiveRoot(Path activeRoot) {
            validity.coordinator.requireActiveDerivedStartupRoot(this, activeRoot);
        }

        private void close() {
            validity.active = false;
        }
    }

    private static final class DerivedStartupValidity {
        private final ReSyncPersistenceCoordinator coordinator;
        private final ReadinessProof proof;
        private final Set<String> owners;
        private final Path activeRoot;
        private final long preparationGeneration;
        private final Thread callbackThread;
        private volatile boolean active = true;

        private DerivedStartupValidity(ReSyncPersistenceCoordinator coordinator, ReadinessProof proof,
                                       Set<String> owners, long preparationGeneration, Thread callbackThread) {
            this.coordinator = coordinator;
            this.proof = proof;
            this.owners = Set.copyOf(owners);
            this.activeRoot = MigrationPaths.requirePath(proof.activeRoot(), "activeRoot");
            this.preparationGeneration = preparationGeneration;
            this.callbackThread = callbackThread;
        }
    }

    public record ConvergenceTiming(long flushMillis, long quiesceMillis, long rebindMillis,
                                    long authoritativeChecksMillis, long resumeMillis, long derivedChecksMillis,
                                    long ownershipValidationMillis, long activationMillis, long totalMillis,
                                    long totalCpuMillis, String outcome, Map<String, Long> participantTimingsMillis,
                                    Map<String, Long> participantCpuTimingsMillis, Map<String, Long> phaseCpuTimingsMillis) {
        public ConvergenceTiming {
            if (flushMillis < 0L || quiesceMillis < 0L || rebindMillis < 0L || authoritativeChecksMillis < 0L
                || resumeMillis < 0L || derivedChecksMillis < 0L || ownershipValidationMillis < 0L
                || activationMillis < 0L || totalMillis < 0L || totalCpuMillis < 0L) {
                throw new IllegalArgumentException("Persistence Convergence Timing Cannot Be Negative");
            }
            outcome = MigrationCanonical.requireText(outcome, "outcome");
            participantTimingsMillis = Collections.unmodifiableMap(new LinkedHashMap<>(
                participantTimingsMillis == null ? Map.of() : participantTimingsMillis));
            participantCpuTimingsMillis = Collections.unmodifiableMap(new LinkedHashMap<>(
                participantCpuTimingsMillis == null ? Map.of() : participantCpuTimingsMillis));
            phaseCpuTimingsMillis = Collections.unmodifiableMap(new LinkedHashMap<>(
                phaseCpuTimingsMillis == null ? Map.of() : phaseCpuTimingsMillis));
        }
    }

    private synchronized void confirmConvergence() throws IOException {
        invalidateReadinessCertificate();
        Path activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Required"));
        PersistenceRebindStatus status = participants.rebindStatus();
        if (status.state() != PersistenceRebindStatus.State.COMMITTED || !status.activeRoot().equals(Optional.of(activeRoot))) {
            throw new MigrationException("Persistence Participants Have Not Converged On The Active Root");
        }
        for (EphemeralLifecycleParticipant participant : ephemeralSnapshot()) {
            participant.healthCheck();
        }
        if (startupDerivedChecksRelaxed) {
            participants.healthAndReadinessCheckAll(PersistenceParticipantClassification.AUTHORITATIVE);
        } else {
            participants.healthAndReadinessCheckAll();
        }
        participants.validateForRestore(activeRoot);
        activation.converged(activeRoot);
        bindAuthorityEpoch(activeRoot);
        restoreFaulted = false;
    }

    private synchronized void bindAuthorityEpoch(Path activeRoot) throws IOException {
        authorityEpochTransition = true;
        invalidateReadinessCertificate();
        try {
            observedAuthorityEpoch = authorityEpochStore.bind(activeRoot);
        } catch (IOException | RuntimeException failure) {
            restoreFaulted = true;
            throw failure;
        } finally {
            authorityEpochTransition = false;
        }
    }

    private long currentAuthorityEpochOrZero() {
        try {
            return authorityEpochStore.current();
        } catch (IllegalStateException unbound) {
            return 0L;
        }
    }

    public static final class ReadinessProof {
        private final long generation;
        private final long topologyEpoch;
        private final long authorityEpoch;
        private final Path activeRoot;
        private final PersistenceRootReadiness readiness;
        private final PersistenceRebindStatus rebind;
        private final List<PersistenceReadinessCertificate.Ephemeral> ephemeralLifecycles;
        private final long derivationGeneration;

        private ReadinessProof(long generation, long topologyEpoch, long authorityEpoch, Path activeRoot,
                               PersistenceRootReadiness readiness, PersistenceRebindStatus rebind,
                               List<PersistenceReadinessCertificate.Ephemeral> ephemeralLifecycles,
                               long derivationGeneration) {
            if (generation < 1L) {
                throw new IllegalArgumentException("Persistence Readiness Proof Generation Must Be Positive");
            }
            if (topologyEpoch < 1L) {
                throw new IllegalArgumentException("Persistence Readiness Proof Topology Epoch Must Be Positive");
            }
            if (authorityEpoch < 1L) {
                throw new IllegalArgumentException("Persistence Readiness Proof Authority Epoch Must Be Positive");
            }
            if (derivationGeneration < 0L) {
                throw new IllegalArgumentException("Persistence Readiness Proof Derivation Generation Must Not Be Negative");
            }
            this.generation = generation;
            this.topologyEpoch = topologyEpoch;
            this.authorityEpoch = authorityEpoch;
            this.activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
            this.readiness = Objects.requireNonNull(readiness, "readiness");
            this.rebind = Objects.requireNonNull(rebind, "rebind");
            this.ephemeralLifecycles = ephemeralLifecycles == null ? List.of() : List.copyOf(ephemeralLifecycles);
            this.derivationGeneration = derivationGeneration;
        }

        public long generation() {
            return generation;
        }

        public long validationGeneration() {
            return generation;
        }

        public long topologyEpoch() {
            return topologyEpoch;
        }

        public long authorityEpoch() {
            return authorityEpoch;
        }

        public Path activeRoot() {
            return activeRoot;
        }

        public PersistenceRootReadiness readiness() {
            return readiness;
        }

        public PersistenceRebindStatus rebind() {
            return rebind;
        }

        public List<PersistenceReadinessCertificate.Ephemeral> ephemeralLifecycles() {
            return ephemeralLifecycles;
        }

        public long derivationGeneration() {
            return derivationGeneration;
        }
    }

    private long nextReadinessGeneration() {
        try {
            return Math.addExact(readinessGeneration, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Persistence Readiness Certificate Generation Exhausted", exception);
        }
    }

}
