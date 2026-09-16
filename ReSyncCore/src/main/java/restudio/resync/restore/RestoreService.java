package restudio.resync.restore;

import restudio.resync.migration.JournalRecoveryAction;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationPreflight;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.PreflightResult;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.StagingArtifactRecovery;
import restudio.resync.migration.StagedMigration;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RestoreService {
    private final MigrationFence fence;
    private final PersistenceParticipantRegistry participants;
    private final RestoreActivation activation;
    private final StagedRestoreValidator validator;
    private final Runnable failureHandler;
    private final SnapshotService snapshots;
    private final MigrationPreflight migrationPreflight = new MigrationPreflight();

    public RestoreService(MigrationFence fence, PersistenceParticipantRegistry participants, RestoreActivation activation) {
        this(fence, participants, activation, new FullRestoreValidator(), () -> {
        });
    }

    public RestoreService(MigrationFence fence, PersistenceParticipantRegistry participants, RestoreActivation activation, Runnable failureHandler) {
        this(fence, participants, activation, new FullRestoreValidator(), failureHandler);
    }

    public RestoreService(MigrationFence fence, PersistenceParticipantRegistry participants, RestoreActivation activation, StagedRestoreValidator validator) {
        this(fence, participants, activation, validator, () -> {
        });
    }

    public RestoreService(MigrationFence fence, PersistenceParticipantRegistry participants, RestoreActivation activation,
                          StagedRestoreValidator validator, Runnable failureHandler) {
        this.fence = Objects.requireNonNull(fence, "fence");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.snapshots = new SnapshotService(fence);
    }

    public RestorePreflight preflight(RestoreRequest request) {
        Objects.requireNonNull(request, "request");
        List<RestorePreflight.Check> checks = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Snapshot snapshot = request.snapshot();
        try {
            verifySnapshotManifest(snapshot);
            checks.add(check("snapshot-manifest", true, snapshot.manifest().manifestHash()));
        } catch (Exception exception) {
            addFailure(checks, diagnostics, snapshot, "RESTORE.SNAPSHOT_MANIFEST", "snapshot-manifest", message(exception));
        }

        addCompatibilityChecks(checks, diagnostics, snapshot, request.compatibility(), snapshot.metadata(), false);
        addCompatibilityChecks(checks, diagnostics, snapshot, request.compatibility(), request.currentSnapshotMetadata(), true);

        Path activeRoot = null;
        try {
            activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Required"));
            participants.validateForRestore(activeRoot);
            checks.add(check("active-root", true, activeRoot.toString()));
        } catch (Exception exception) {
            addFailure(checks, diagnostics, snapshot, "RESTORE.ACTIVE_ROOT", "active-root", message(exception));
        }

        try {
            verifyStaging(request, activeRoot);
            checks.add(check("restore-staging", true, request.restoreStagingRoot().toString()));
        } catch (Exception exception) {
            addFailure(checks, diagnostics, snapshot, "RESTORE.STAGING", "restore-staging", message(exception));
        }

        if (activeRoot != null) {
            try {
                PreflightResult current = migrationPreflight.inspect(activeRoot, request.currentSnapshotStagingRoot(), participants, snapshot.manifest().totalBytes());
                for (PreflightResult.Check check : current.checks()) {
                    String name = "current-" + check.name();
                    if (check.passed()) {
                        checks.add(check(name, true, check.detail()));
                    } else {
                        addFailure(checks, diagnostics, snapshot, "RESTORE.CURRENT_SNAPSHOT", name, check.detail());
                    }
                }
            } catch (Exception exception) {
                addFailure(checks, diagnostics, snapshot, "RESTORE.CURRENT_SNAPSHOT", "current-snapshot", message(exception));
            }
        }

        try {
            verifyJournal(request, activeRoot);
            checks.add(check("restore-journal", true, request.journalPath().toString()));
        } catch (Exception exception) {
            addFailure(checks, diagnostics, snapshot, "RESTORE.JOURNAL", "restore-journal", message(exception));
        }

        boolean passed = checks.stream().allMatch(RestorePreflight.Check::passed);
        return new RestorePreflight(passed, checks, new DiagnosticSet(diagnostics));
    }

    public RestoreResult restore(RestoreRequest request) throws IOException {
        try {
            return restoreInternal(request);
        } catch (IOException | RuntimeException exception) {
            failureHandler.run();
            throw exception;
        }
    }

    private RestoreResult restoreInternal(RestoreRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        preflight(request).requirePassed();
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        MigrationJournal journal = null;
        StagedMigration staged = null;
        Snapshot currentSnapshot = null;
        boolean quiesced = false;
        try {
            Path previousRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Restore Active Root Is Required"));
            verifySnapshotManifest(request.snapshot());
            request.compatibility().checks(request.snapshot().metadata()).stream().filter(check -> !check.passed()).findFirst().ifPresent(check -> {
                throw new IllegalStateException("Restore Compatibility Changed Before Activation: " + check.name());
            });
            verifyJournal(request, previousRoot);
            journal = MigrationJournal.create(request.journalPath(), journalId(request.snapshot()), activation.planHash(request.snapshot()));
            journal.transition(MigrationJournalState.PREPARED, "restore-prepared");
            currentSnapshot = createCurrentSnapshot(request, previousRoot);
            participants.quiesceAll();
            quiesced = true;
            journal.transition(MigrationJournalState.TRANSFORMING, "restore-transforming");
            staged = activation.stage(request.snapshot(), request.restoreStagingRoot());
            RestoreJournalDetail detail = RestoreJournalDetail.of(staged, request.snapshot().manifest().manifestHash());
            journal.transition(MigrationJournalState.VALIDATING, detail.encode());
            RestoreActivation.Candidate candidate = activation.prepare(request.snapshot(), staged, validator);
            journal.transition(MigrationJournalState.STAGED, detail.encode());
            activation.swap(candidate);
            journal.transition(MigrationJournalState.ACTIVATED, detail.encode());
            Path activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Restored Root Is Not Active"));
            participants.rebindAll(activeRoot);
            validateAuthoritativeForRestore();
            activation.verify(request.snapshot(), staged);
            resumeForRestore(activeRoot);
            quiesced = false;
            journal.transition(MigrationJournalState.COMMITTED, "restore-committed");
            return new RestoreResult(Optional.of(currentSnapshot), staged, journal.path(), MigrationJournalState.COMMITTED);
        } catch (IOException | RuntimeException exception) {
            if (journal != null) {
                if (!recoverFailure(journal, request, staged, exception)) {
                    quiesced = false;
                }
            }
            throw exception;
        } finally {
            try {
                if (quiesced) {
                    participants.resumeAll();
                }
            } finally {
                migration.close();
            }
        }
    }

    private Snapshot createCurrentSnapshot(RestoreRequest request, Path previousRoot) throws IOException {
        return snapshots.create(previousRoot, request.currentSnapshotStagingRoot(), request.currentSnapshotMetadata(), participants);
    }

    public RestoreRecoveryResult recover(RestoreRequest request) throws IOException {
        try {
            return recoverInternal(request);
        } catch (IOException | RuntimeException exception) {
            failureHandler.run();
            throw exception;
        }
    }

    public RestoreRecoveryResult rollback(RestoreRequest request) throws IOException {
        try {
            return rollbackInternal(request);
        } catch (IOException | RuntimeException exception) {
            failureHandler.run();
            throw exception;
        }
    }

    private RestoreRecoveryResult rollbackInternal(RestoreRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        participants.validateRestoreParticipants();
        MigrationJournal journal = MigrationJournal.open(request.journalPath());
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        boolean quiesced = false;
        try {
            participants.quiesceAll();
            quiesced = true;
            Optional<MigrationJournalState> state = journal.currentState();
            if (state.isEmpty()) {
                throw new MigrationException("Restore Journal Has No State");
            }
            if (state.get() == MigrationJournalState.COMMITTED) {
                throw new MigrationException("Committed Restore Cannot Be Rolled Back");
            }
            if (state.get() == MigrationJournalState.ROLLED_BACK) {
                quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
                quarantineRestoreRoot(request.restoreStagingRoot());
                return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, state.get());
            }
            Optional<RestoreJournalDetail> detail = RestoreJournalDetail.find(journal.entries());
            if (detail.isPresent()) {
                StagedMigration staged = detail.get().staged();
                validateJournalStaging(request, staged);
                Optional<Path> active = activation.activeRoot();
                if (!active.equals(staged.previousRoot()) && !active.equals(Optional.of(staged.root()))) {
                    throw new MigrationException("Restore Rollback Found An Unknown Active Root");
                }
                activation.rollback(staged);
                if (!activation.activeRoot().equals(staged.previousRoot())) {
                    throw new MigrationException("Restore Rollback Did Not Restore Previous Root");
                }
                if (staged.previousRoot().isPresent()) {
                    try {
                        rebindAfterRollback(staged.previousRoot().get(), request);
                    } catch (IOException | RuntimeException exception) {
                        quiesced = false;
                        throw exception;
                    }
                }
                quarantineRestoreRoot(staged.root());
            } else {
                quarantineRestoreRoot(request.restoreStagingRoot());
            }
            quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
            journal.recoverToRollback("Restore Explicitly Rolled Back");
            participants.resumeAll();
            quiesced = false;
            return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, MigrationJournalState.ROLLED_BACK);
        } finally {
            try {
                if (quiesced) {
                    participants.resumeAll();
                }
            } finally {
                migration.close();
            }
        }
    }

    private RestoreRecoveryResult recoverInternal(RestoreRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        participants.validateRestoreParticipants();
        MigrationJournal journal = MigrationJournal.open(request.journalPath());
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        boolean quiesced = false;
        try {
            quiesceForRecovery();
            quiesced = true;
            Optional<MigrationJournalState> state = journal.currentState();
            if (state.isEmpty()) {
                throw new MigrationException("Restore Journal Has No State");
            }
            MigrationJournalState current = state.get();
            if (current == MigrationJournalState.COMMITTED || current == MigrationJournalState.ROLLED_BACK) {
                if (current == MigrationJournalState.COMMITTED) {
                    Path activeRoot = recoverCommitted(request, journal);
                    resumeForRestore(activeRoot);
                    quiesced = false;
                } else {
                    quarantineRestoreRoot(request.restoreStagingRoot());
                    quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
                }
                return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, current);
            }
            if (current == MigrationJournalState.FAILED) {
                Optional<RestoreJournalDetail> failedDetail = RestoreJournalDetail.find(journal.entries());
                if (failedDetail.isPresent()) {
                    validateJournalStaging(request, failedDetail.get().staged());
                    return rollbackInterrupted(journal, failedDetail.get().staged(), request, "Restore Failure Requires Rollback");
                }
                quarantineRestoreRoot(request.restoreStagingRoot());
                quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
                journal.recoverToRollback("Restore Rolled Back After Failure");
                return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, MigrationJournalState.ROLLED_BACK);
            }
            Optional<RestoreJournalDetail> detail = RestoreJournalDetail.find(journal.entries());
            if (detail.isEmpty()) {
                quarantineRestoreRoot(request.restoreStagingRoot());
                quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
                if (current != MigrationJournalState.FAILED) {
                    journal.transition(MigrationJournalState.FAILED, "Restore Interrupted Before Staging");
                }
                journal.recoverToRollback("Restore Rolled Back After Interruption");
                return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, MigrationJournalState.ROLLED_BACK);
            }
            RestoreJournalDetail journalDetail = detail.get();
            StagedMigration staged = journalDetail.staged();
            validateJournalStaging(request, staged);
            if (!journalDetail.manifestHash().equals(request.snapshot().manifest().manifestHash()) || !staged.planHash().equals(activation.planHash(request.snapshot()))) {
                return rollbackInterrupted(journal, staged, request, "Restore Journal Detail Is Incompatible");
            }
            try {
                verifySnapshotManifest(request.snapshot());
                request.compatibility().checks(request.snapshot().metadata()).stream().filter(check -> !check.passed()).findFirst().ifPresent(check -> {
                    throw new IllegalStateException("Restore Compatibility Is Incompatible During Recovery: " + check.name());
                });
                if (current == MigrationJournalState.ACTIVATED) {
                    validator.validate(request.snapshot(), staged);
                    activation.verify(request.snapshot(), staged);
                } else {
                    RestoreActivation.Candidate candidate = activation.prepare(request.snapshot(), staged, validator);
                    if (current == MigrationJournalState.VALIDATING) {
                        journal.transition(MigrationJournalState.STAGED, journalDetail.encode());
                        current = MigrationJournalState.STAGED;
                    }
                    if (current == MigrationJournalState.STAGED) {
                        activation.swap(candidate);
                        journal.transition(MigrationJournalState.ACTIVATED, journalDetail.encode());
                    }
                    activation.verify(request.snapshot(), staged);
                }
                Path activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Recovered Root Is Not Active"));
                rebindForRecovery(activeRoot);
                validateAuthoritativeForRestore();
                resumeForRestore(activeRoot);
                quiesced = false;
                journal.transition(MigrationJournalState.COMMITTED, "Restore Recovered And Committed");
                return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, MigrationJournalState.COMMITTED);
            } catch (IOException | RuntimeException exception) {
                try {
                    return rollbackInterrupted(journal, staged, request, message(exception));
                } catch (IOException | RuntimeException rollbackFailure) {
                    quiesced = false;
                    throw rollbackFailure;
                }
            }
        } finally {
            try {
                if (quiesced) {
                    participants.resumeAll();
                }
            } finally {
                migration.close();
            }
        }
    }

    private void quiesceForRecovery() throws IOException {
        participants.quiesceAll();
    }

    private void rebindForRecovery(Path activeRoot) throws IOException {
        participants.rebindAll(activeRoot);
    }

    private void validateAuthoritativeForRestore() throws IOException {
        participants.healthCheckAll(PersistenceParticipantClassification.AUTHORITATIVE);
        participants.readinessCheckAll(PersistenceParticipantClassification.AUTHORITATIVE);
    }

    private void resumeForRestore(Path activeRoot) throws IOException {
        participants.resumeAll();
        validateDerivedForRestore();
        participants.validateForRestore(activeRoot);
    }

    private void validateDerivedForRestore() throws IOException {
        List<PersistenceParticipant> derived = derivedParticipants();
        if (!hasAbsentDerivedOutput(derived)) {
            participants.healthCheckAll(PersistenceParticipantClassification.DERIVED_CACHE);
            participants.readinessCheckAll(PersistenceParticipantClassification.DERIVED_CACHE);
            return;
        }
        for (PersistenceParticipant participant : derived) {
            if (isAbsentDerivedOutput(participant)) {
                continue;
            }
            try {
                participant.healthCheck();
                participant.readinessCheck();
            } catch (IOException exception) {
                throw new MigrationException("Persistence Participant Derived Cache Validation Failed: " + participant.owner(), exception);
            }
        }
    }

    private static boolean isAbsentDerivedOutput(PersistenceParticipant participant) throws IOException {
        Path root = MigrationPaths.requirePath(participant.root(), "participant root");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var children = Files.list(root)) {
            return children.findAny().isEmpty();
        }
    }

    private void validateDerivedHealthForRollback() throws IOException {
        List<PersistenceParticipant> derived = derivedParticipants();
        if (!hasAbsentDerivedOutput(derived)) {
            participants.healthCheckAll(PersistenceParticipantClassification.DERIVED_CACHE);
            return;
        }
        for (PersistenceParticipant participant : derived) {
            if (isAbsentDerivedOutput(participant)) {
                continue;
            }
            try {
                participant.healthCheck();
            } catch (IOException exception) {
                throw new MigrationException("Persistence Participant Derived Cache Health Check Failed: " + participant.owner(), exception);
            }
        }
    }

    private List<PersistenceParticipant> derivedParticipants() {
        return participants.participants().stream()
            .filter(participant -> participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE)
            .toList();
    }

    private static boolean hasAbsentDerivedOutput(List<PersistenceParticipant> derived) throws IOException {
        for (PersistenceParticipant participant : derived) {
            if (isAbsentDerivedOutput(participant)) {
                return true;
            }
        }
        return false;
    }

    private Path recoverCommitted(RestoreRequest request, MigrationJournal journal) throws IOException {
        RestoreJournalDetail detail = RestoreJournalDetail.find(journal.entries())
            .orElseThrow(() -> new MigrationException("Committed Restore Journal Detail Is Missing"));
        validateJournalStaging(request, detail.staged());
        verifySnapshotManifest(request.snapshot());
        activation.verify(request.snapshot(), detail.staged());
        Path activeRoot = activation.activeRoot().orElseThrow(() -> new MigrationException("Committed Restore Root Is Not Active"));
        participants.rebindAll(activeRoot);
        validateAuthoritativeForRestore();
        return activeRoot;
    }

    private RestoreRecoveryResult rollbackInterrupted(MigrationJournal journal, StagedMigration staged, RestoreRequest request, String reason) throws IOException {
        MigrationJournalState current = journal.currentState().orElseThrow(() -> new MigrationException("Restore Journal Has No State"));
        if (current != MigrationJournalState.FAILED) {
            journal.transition(MigrationJournalState.FAILED, reason);
        }
        Optional<Path> active = activation.activeRoot();
        Optional<Path> previous = staged.previousRoot();
        if (!active.equals(previous) && !active.equals(Optional.of(staged.root()))) {
            throw new MigrationException("Restore Recovery Found An Unknown Active Root");
        }
        activation.rollback(staged);
        if (!activation.activeRoot().equals(staged.previousRoot())) {
            throw new MigrationException("Restore Rollback Did Not Restore Previous Root");
        }
        if (staged.previousRoot().isPresent()) {
            rebindAfterRollback(staged.previousRoot().get(), request);
        }
        quarantineRestoreRoot(staged.root());
        quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
        journal.recoverToRollback("Restore Rolled Back After Interruption");
        return new RestoreRecoveryResult(journal.path(), JournalRecoveryAction.COMPLETE, MigrationJournalState.ROLLED_BACK);
    }

    private static void validateJournalStaging(RestoreRequest request, StagedMigration staged) throws IOException {
        if (!request.restoreStagingRoot().equals(staged.root())) {
            throw new MigrationException("Restore Journal Staging Root Does Not Match Request");
        }
        MigrationPaths.requireDistinctRoots(request.snapshot().root(), staged.root());
        if (staged.previousRoot().isPresent() && staged.previousRoot().get().equals(staged.root())) {
            throw new MigrationException("Restore Journal Previous Root Matches Staging Root");
        }
    }

    private void quarantineRestoreRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "restoreStagingRoot");
        requireQuarantineBoundary(normalized, "restoreStagingRoot");
        StagingArtifactRecovery.quarantineRestore(normalized);
    }

    private void quarantineCurrentSnapshot(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "currentSnapshotStagingRoot");
        requireQuarantineBoundary(normalized, "currentSnapshotStagingRoot");
        snapshots.quarantineCurrentSnapshot(normalized);
    }

    private void requireQuarantineBoundary(Path root, String field) throws IOException {
        Optional<Path> active = activation.activeRoot();
        if (active.isEmpty()) {
            return;
        }
        Path activeRoot = MigrationPaths.requirePath(active.get(), "activeRoot");
        try {
            MigrationPaths.requireDistinctRoots(activeRoot, root);
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Cannot Quarantine " + field + " Because It Overlaps The Active Root", exception);
        }
    }

    private boolean recoverFailure(MigrationJournal journal, RestoreRequest request, StagedMigration staged, Exception primary) {
        try {
            Optional<MigrationJournalState> state = journal.currentState();
            if (state.isEmpty() || state.get() == MigrationJournalState.COMMITTED || state.get() == MigrationJournalState.ROLLED_BACK) {
                return true;
            }
            journal.transition(MigrationJournalState.FAILED, message(primary));
            if (staged != null) {
                try {
                    participants.quiesceAll();
                } catch (IOException | RuntimeException quiesceFailure) {
                    primary.addSuppressed(quiesceFailure);
                }
                Optional<Path> active = activation.activeRoot();
                Optional<Path> previousRoot = staged.previousRoot();
                if (!active.equals(previousRoot) && !active.equals(Optional.of(staged.root()))) {
                    throw new MigrationException("Restore Failure Found An Unknown Active Root");
                }
                activation.rollback(staged);
                if (!activation.activeRoot().equals(staged.previousRoot())) {
                    throw new MigrationException("Restore Failure Did Not Restore Previous Root");
                }
                if (staged.previousRoot().isPresent()) {
                    try {
                        rebindAfterRollback(staged.previousRoot().get(), request);
                    } catch (IOException | RuntimeException recoveryFailure) {
                        primary.addSuppressed(recoveryFailure);
                        return false;
                    }
                }
                quarantineRestoreRoot(staged.root());
            } else {
                quarantineRestoreRoot(request.restoreStagingRoot());
            }
            quarantineCurrentSnapshot(request.currentSnapshotStagingRoot());
            journal.transition(MigrationJournalState.ROLLED_BACK, "Restore Rolled Back After Failure");
            return true;
        } catch (Exception recoveryFailure) {
            primary.addSuppressed(recoveryFailure);
            return false;
        }
    }

    private static void verifySnapshotManifest(Snapshot snapshot) throws IOException {
        if (!snapshot.verified()) {
            throw new MigrationException("Restore Snapshot Is Not Verified");
        }
        if (!snapshot.manifest().metadata().equals(snapshot.metadata())) {
            throw new MigrationException("Restore Snapshot Metadata Does Not Match Manifest");
        }
        SnapshotManifest persisted = SnapshotManifest.read(snapshot.manifestPath());
        if (!persisted.manifestHash().equals(snapshot.manifest().manifestHash()) || !persisted.canonicalText().equals(snapshot.manifest().canonicalText())) {
            throw new MigrationException("Restore Snapshot Manifest Does Not Match Persisted Manifest");
        }
        ProductionSnapshotMetadataManifest.Values productionMetadata = ProductionSnapshotMetadataManifest.read(snapshot.root());
        if (!productionMetadata.metadata().equals(persisted.metadata())
            || !productionMetadata.manifestHash().equals(persisted.manifestHash())) {
            throw new MigrationException("Restore Snapshot Metadata Manifest Does Not Match Persisted Manifest");
        }
        Path persistedStatePath = sidecar(snapshot.root(), ".state");
        if (!persistedStatePath.equals(snapshot.statePath())) {
            throw new MigrationException("Restore Snapshot State Path Does Not Match Its Root");
        }
        requireVerifiedState(persistedStatePath, persisted.manifestHash());
        SnapshotVerification verification = snapshot.manifest().verify(snapshot.root());
        verification.requireVerified();
        if (!verification.manifestHash().equals(snapshot.manifest().manifestHash()) || !verification.manifestHash().equals(snapshot.verification().manifestHash())) {
            throw new MigrationException("Restore Snapshot Verification Does Not Match Manifest");
        }
    }

    private static void verifyStaging(RestoreRequest request, Path activeRoot) throws IOException {
        Path sourceRoot = MigrationPaths.requireDirectory(request.snapshot().root(), "snapshotRoot");
        Path currentStaging = MigrationPaths.requirePath(request.currentSnapshotStagingRoot(), "currentSnapshotStagingRoot");
        Path restoreStaging = MigrationPaths.requirePath(request.restoreStagingRoot(), "restoreStagingRoot");
        MigrationPaths.requireDistinctRoots(sourceRoot, currentStaging);
        MigrationPaths.requireDistinctRoots(sourceRoot, restoreStaging);
        MigrationPaths.requireDistinctRoots(currentStaging, restoreStaging);
        if (activeRoot != null) {
            MigrationPaths.requireDistinctRoots(activeRoot, currentStaging);
            MigrationPaths.requireDistinctRoots(activeRoot, restoreStaging);
        }
        requireEmpty(currentStaging, "currentSnapshotStagingRoot");
        requireEmpty(restoreStaging, "restoreStagingRoot");
        MigrationPaths.requireWritableParent(currentStaging);
        MigrationPaths.requireWritableParent(restoreStaging);
    }

    private static void verifyJournal(RestoreRequest request, Path activeRoot) throws IOException {
        Path normalized = MigrationPaths.requirePath(request.journalPath(), "journalPath");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Restore Journal Already Exists: " + normalized);
        }
        MigrationPaths.requireDistinctRoots(normalized, request.snapshot().root());
        MigrationPaths.requireDistinctRoots(normalized, request.currentSnapshotStagingRoot());
        MigrationPaths.requireDistinctRoots(normalized, request.restoreStagingRoot());
        if (activeRoot != null) {
            MigrationPaths.requireDistinctRoots(normalized, activeRoot);
        }
        MigrationPaths.requireWritableParent(normalized);
    }

    private static void requireEmpty(Path path, String field) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(field + " Must Be A Non-Symbolic-Link Directory");
        }
        try (var stream = Files.list(path)) {
            if (stream.findAny().isPresent()) {
                throw new MigrationException(field + " Must Be Empty");
            }
        }
    }

    private static void addCompatibilityChecks(List<RestorePreflight.Check> checks, List<Diagnostic> diagnostics, Snapshot snapshot, RestoreCompatibilityPolicy policy, SnapshotMetadata metadata, boolean current) {
        for (RestorePreflight.Check compatibility : policy.checks(metadata)) {
            String name = current ? "current-" + compatibility.name() : compatibility.name();
            if (compatibility.passed()) {
                checks.add(check(name, true, compatibility.detail()));
                continue;
            }
            String code = switch (compatibility.name()) {
                case "snapshot-format" -> current ? "RESTORE.CURRENT_SNAPSHOT_FORMAT" : "RESTORE.SNAPSHOT_FORMAT";
                case "build" -> current ? "RESTORE.CURRENT_BUILD" : "RESTORE.BUILD";
                case "contract-catalog" -> current ? "RESTORE.CURRENT_CONTRACT" : "RESTORE.CONTRACT";
                case "extension-manifest" -> current ? "RESTORE.CURRENT_EXTENSIONS" : "RESTORE.EXTENSIONS";
                default -> "RESTORE.COMPATIBILITY";
            };
            addFailure(checks, diagnostics, snapshot, code, name, compatibility.detail());
        }
    }

    private static void addFailure(List<RestorePreflight.Check> checks, List<Diagnostic> diagnostics, Snapshot snapshot, String code, String name, String detail) {
        String normalized = detail(detail);
        checks.add(check(name, false, normalized));
        diagnostics.add(RestoreDiagnostics.error(snapshot, code, name, normalized, "Restore the snapshot in a compatible ReSync environment or create a new snapshot.", Map.of("check", name, "detail", normalized)));
    }

    private static RestorePreflight.Check check(String name, boolean passed, String detail) {
        return new RestorePreflight.Check(name, passed, detail(detail));
    }

    private static String journalId(Snapshot snapshot) {
        return "restore-" + snapshot.metadata().snapshotId();
    }

    private static String message(Exception exception) {
        return detail(exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage());
    }

    private void rebindAfterRollback(Path previousRoot, RestoreRequest request) throws IOException {
        verifyPreviousRootEvidence(request, previousRoot);
        participants.rebindAll(previousRoot);
        validateAuthoritativeForRestore();
        validateDerivedHealthForRollback();
    }

    private void verifyPreviousRootEvidence(RestoreRequest request, Path previousRoot) throws IOException {
        if (!hasRetainedCurrentSnapshotEvidence(request.currentSnapshotStagingRoot())) {
            return;
        }
        Snapshot retained = retainedCurrentSnapshot(request);
        Path normalizedPrevious = MigrationPaths.requireDirectory(previousRoot, "previousRoot");
        SnapshotVerification verification = retained.manifest().verify(normalizedPrevious);
        verification.requireVerified();
        if (!verification.manifestHash().equals(retained.manifest().manifestHash())) {
            throw new MigrationException("Previous Root Evidence Hash Does Not Match Its Manifest");
        }
    }

    private static boolean hasRetainedCurrentSnapshotEvidence(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "currentSnapshotStagingRoot");
        return Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)
            || Files.exists(sidecar(normalized, ".manifest"), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(sidecar(normalized, ".state"), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(ProductionSnapshotMetadataManifest.pathFor(normalized), LinkOption.NOFOLLOW_LINKS);
    }

    private Snapshot retainedCurrentSnapshot(RestoreRequest request) throws IOException {
        Path root = MigrationPaths.requireDirectory(request.currentSnapshotStagingRoot(), "currentSnapshotStagingRoot");
        Path manifestPath = sidecar(root, ".manifest");
        Path statePath = sidecar(root, ".state");
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        if (!manifest.metadata().equals(request.currentSnapshotMetadata())) {
            throw new MigrationException("Retained Current Snapshot Metadata Does Not Match The Request");
        }
        requireVerifiedState(statePath, manifest.manifestHash());
        ProductionSnapshotMetadataManifest.Values productionMetadata = ProductionSnapshotMetadataManifest.read(root);
        if (!productionMetadata.metadata().equals(manifest.metadata())
            || !productionMetadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Current Snapshot Metadata Does Not Match Its Manifest");
        }
        SnapshotVerification verification = manifest.verify(root);
        verification.requireVerified();
        if (!verification.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Current Snapshot Verification Does Not Match Its Manifest");
        }
        return new Snapshot(root, manifestPath, statePath, manifest.metadata(), manifest, SnapshotState.VERIFIED, verification);
    }

    private static void requireVerifiedState(Path statePath, String manifestHash) throws IOException {
        Path normalized = MigrationPaths.requirePath(statePath, "currentSnapshotStatePath");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Retained Current Snapshot State Is Not A Regular File");
        }
        byte[] expected = ("state=" + SnapshotState.VERIFIED.name() + "\n"
            + "verified=true\n"
            + "manifest-hash=" + manifestHash + "\n"
            + "failures=0\n").getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(expected, Files.readAllBytes(normalized))) {
            throw new MigrationException("Retained Current Snapshot State Is Not An Exact Verified Manifest Binding");
        }
    }

    private static Path sidecar(Path root, String suffix) throws IOException {
        Path parent = root.getParent();
        Path name = root.getFileName();
        if (parent == null || name == null) {
            throw new MigrationException("Current Snapshot Root Has No Parent");
        }
        return parent.resolve(name + suffix).toAbsolutePath().normalize();
    }

    private static String detail(String value) {
        String normalized = value == null || value.isBlank() ? "No Detail" : value;
        return normalized.replace('\n', ' ').replace('\r', ' ');
    }
}
