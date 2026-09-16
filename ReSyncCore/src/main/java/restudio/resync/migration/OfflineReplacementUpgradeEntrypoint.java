package restudio.resync.migration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.UpgradeApplyRequest;
import restudio.resync.upgrade.UpgradeApplyResult;
import restudio.resync.upgrade.UpgradeDryRunRequest;
import restudio.resync.upgrade.UpgradeDryRunResult;
import restudio.resync.upgrade.UpgradePlanner;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeStatus;

public final class OfflineReplacementUpgradeEntrypoint {
    public static final String CONTROL_DIRECTORY_NAME = ".resync-replacement-control";
    public static final String RUNTIME_LOCK_NAME = "runtime.lock";
    public static final String OFFLINE_LOCK_NAME = "offline-upgrade.lock";

    public enum CommittedRepairCut {
        PREPARED,
        CORRUPT_ARCHIVE_MOVED,
        ARCHIVED,
        REPLACEMENT_INSTALLED,
        INSTALLED,
        MARKER_PUBLISHED
    }

    @FunctionalInterface
    public interface CommittedRepairFault {
        void after(CommittedRepairCut cut) throws IOException;

        static CommittedRepairFault none() {
            return cut -> {
            };
        }
    }

    private final Request request;
    private final CommittedRepairFault committedRepairFault;

    public OfflineReplacementUpgradeEntrypoint(Request request) {
        this(request, CommittedRepairFault.none());
    }

    public OfflineReplacementUpgradeEntrypoint(Request request, CommittedRepairFault committedRepairFault) {
        this.request = Objects.requireNonNull(request, "request");
        this.committedRepairFault = Objects.requireNonNull(committedRepairFault, "committedRepairFault");
    }

    public UpgradeDryRunResult dryRun() throws IOException {
        if (request.mode() == Request.Mode.PLAN_ONLY) {
            return planOnly();
        }
        UpgradeDryRunResult planned = computePlan();
        requireApplyAuthorityBeforeMutation(planned);
        try (UpgradeLocks ignored = acquireLocks()) {
            return dryRunLocked();
        }
    }

    public UpgradeApplyResult apply(UpgradeDryRunResult dryRun) throws IOException {
        Objects.requireNonNull(dryRun, "dryRun");
        if (request.mode() == Request.Mode.PLAN_ONLY) {
            throw new MigrationException("Plan-Only Offline Upgrade Cannot Apply Or Resume");
        }
        requireVerifiedSourceBinding(dryRun);
        requireApplyAuthorityBeforeMutation(dryRun);
        try (UpgradeLocks ignored = acquireLocks()) {
            return applyLocked(dryRun);
        }
    }

    private void requireVerifiedSourceBinding(UpgradeDryRunResult dryRun) throws IOException {
        if (request.verifiedSource() == null) {
            return;
        }
        Snapshot admitted = request.verifiedSource().snapshot();
        Snapshot planned = dryRun.sourceSnapshot().orElseThrow(
            () -> new MigrationException("Offline Upgrade Dry Run Has No Verified Source Snapshot"));
        SnapshotVerification plannedVerification = planned.manifest().verify(planned.root());
        plannedVerification.requireVerified();
        if (!planned.metadata().equals(admitted.metadata())
            || !planned.manifest().canonicalText().equals(admitted.manifest().canonicalText())
            || !planned.manifest().manifestHash().equals(admitted.manifest().manifestHash())
            || !plannedVerification.manifestHash().equals(admitted.manifest().manifestHash())
            || dryRun.proposal().isEmpty()
            || !dryRun.proposal().get().plan().sourceManifestHash().equals(admitted.manifest().manifestHash())
            || !dryRun.proposal().get().plan().sourceSnapshotId().equals(admitted.metadata().snapshotId())) {
            throw new MigrationException("Offline Upgrade Dry Run Does Not Match Verified Snapshot Admission");
        }
    }

    public RunResult execute() throws IOException {
        if (request.mode() == Request.Mode.PLAN_ONLY) {
            return new RunResult(planOnly(), null);
        }
        Path journalPath = request.controlRoot().resolve("journals").resolve(request.migrationId() + ".journal")
            .toAbsolutePath().normalize();
        UpgradeDryRunResult planned = Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)
            ? retainedDryRun(retainedPaths()) : computePlan();
        requireVerifiedSourceBinding(planned);
        requireApplyAuthorityBeforeMutation(planned);
        try (UpgradeLocks ignored = acquireLocks()) {
            Paths paths = prepare(false);
            if (Files.exists(paths.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
                UpgradeDryRunResult retained = retainedDryRun(paths);
                return new RunResult(retained, applyLocked(retained));
            }
            UpgradeDryRunResult dryRun = dryRunLocked();
            if (!dryRun.canApply()) {
                return new RunResult(dryRun, null);
            }
            return new RunResult(dryRun, applyLocked(dryRun));
        }
    }

    public UpgradeDryRunResult planOnly() throws IOException {
        if (request.mode() != Request.Mode.PLAN_ONLY) {
            throw new MigrationException("Plan-Only Planning Requires A PLAN_ONLY Request");
        }
        return computePlan();
    }

    private UpgradeDryRunResult computePlan() throws IOException {
        Paths paths = readOnlyPaths();
        Path source = MigrationPaths.requireDirectory(request.sourceRoot(), "sourceRoot");
        Snapshot snapshot = request.verifiedSource() == null ? null : request.verifiedSource().snapshot();
        Path preflightRoot = snapshot == null ? source : snapshot.root();
        PersistenceParticipantRegistry preflightParticipants = snapshot == null
            ? request.participants() : request.verifiedSource().participantRegistry(snapshot.root());
        PreflightResult preflight = new MigrationPreflight().inspect(
            preflightRoot, paths.snapshotRoot(), preflightParticipants, request.reservedBytes());
        if (!preflight.passed()) {
            return new UpgradeDryRunResult(
                UpgradeStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                request.sourceWindow(),
                preflight,
                new DiagnosticSet(List.of()));
        }
        if (snapshot == null) {
            SnapshotManifest manifest = SnapshotManifest.scan(source, request.snapshotMetadata(), request.participants());
            SnapshotVerification verification = manifest.verify(source);
            verification.requireVerified();
            Path manifestPath = paths.snapshotRoot().resolveSibling(paths.snapshotRoot().getFileName() + ".manifest");
            Path statePath = paths.snapshotRoot().resolveSibling(paths.snapshotRoot().getFileName() + ".state");
            snapshot = new Snapshot(source, manifestPath, statePath, request.snapshotMetadata(), manifest,
                SnapshotState.VERIFIED, verification);
        }
        requireActiveSourceMatchesAdmission(snapshot);
        return planSnapshot(snapshot, preflight);
    }

    private void requireActiveSourceMatchesAdmission(Snapshot admitted) throws IOException {
        if (request.verifiedSource() == null) {
            return;
        }
        SnapshotManifest active = SnapshotManifest.scan(
            MigrationPaths.requireDirectory(request.sourceRoot(), "sourceRoot"),
            admitted.metadata(), request.participants());
        if (!active.canonicalText().equals(admitted.manifest().canonicalText())
            || !active.manifestHash().equals(admitted.manifest().manifestHash())) {
            throw new MigrationException("Offline Upgrade Active Source Does Not Match Verified Snapshot Admission");
        }
    }

    private UpgradeDryRunResult planSnapshot(Snapshot snapshot, PreflightResult preflight) throws IOException {
        request.sourceWindow().requireSupported(snapshot.metadata());
        if (!snapshot.metadata().equals(request.snapshotMetadata())) {
            throw new MigrationException("Offline Upgrade Snapshot Metadata Does Not Match Request");
        }
        UpgradeProposal first = Objects.requireNonNull(request.planner().plan(snapshot, request.sourceWindow()), "planner result");
        if (request.verifiedSource() != null) {
            request.verifiedSource().snapshot();
        }
        UpgradeProposal second = Objects.requireNonNull(request.planner().plan(snapshot, request.sourceWindow()), "planner result");
        if (request.verifiedSource() != null) {
            request.verifiedSource().snapshot();
        }
        if (!sameProposal(first, second)) {
            throw new MigrationException("Offline Upgrade Planner Is Not Deterministic");
        }
        MigrationPlan plan = first.plan();
        if (!plan.sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())
            || !plan.sourceManifestHash().equals(snapshot.manifest().manifestHash())
            || plan.sourceFormatVersion() != request.sourceWindow().sourceFormatVersion()
            || plan.targetFormatVersion() != request.sourceWindow().targetFormatVersion()) {
            throw new MigrationException("Offline Upgrade Plan Does Not Match The Requested Source Window");
        }
        UpgradeStatus status = first.hasErrors()
            ? UpgradeStatus.FAILED
            : first.quarantineReport().records().isEmpty()
                ? UpgradeStatus.READY : UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE;
        return new UpgradeDryRunResult(status, Optional.of(snapshot), Optional.of(first), request.sourceWindow(),
            preflight, first.diagnostics());
    }

    private static boolean sameProposal(UpgradeProposal first, UpgradeProposal second) {
        return first.plan().canonicalText().equals(second.plan().canonicalText())
            && first.quarantineReport().canonicalText().equals(second.quarantineReport().canonicalText())
            && first.diagnostics().toJson().equals(second.diagnostics().toJson());
    }

    private Paths retainedPaths() throws IOException {
        return readOnlyPaths(false);
    }

    private Paths readOnlyPaths() throws IOException {
        return readOnlyPaths(true);
    }

    private Paths readOnlyPaths(boolean sourceRequired) throws IOException {
        Path source = MigrationPaths.requirePath(request.sourceRoot(), "sourceRoot");
        Path control = MigrationPaths.requirePath(request.controlRoot(), "controlRoot");
        if (source.getParent() == null || !source.getParent().equals(control.getParent())
            || !CONTROL_DIRECTORY_NAME.equals(control.getFileName().toString())) {
            throw new MigrationException("controlRoot Must Be The Sibling .resync-replacement-control Directory");
        }
        MigrationPaths.requireDistinctRoots(source, control);
        MigrationPaths.requireWritableParent(control);
        MigrationPaths.requireWritableParent(MigrationActivationMarker.markerPath(source));
        if (sourceRequired) {
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("sourceRoot Must Be An Existing Directory: " + source);
            }
            MigrationPaths.requireDirectory(source, "sourceRoot");
            MigrationPaths.requireNoSymlinkTree(source);
            if (request.participants().participants().isEmpty()) {
                throw new MigrationException("At Least One Offline Upgrade Participant Is Required");
            }
            request.participants().validateForRoot(source);
            request.sourceWindow().requireSupported(request.snapshotMetadata());
        }
        Path plans = control.resolve("plans").toAbsolutePath().normalize();
        Path journals = control.resolve("journals").toAbsolutePath().normalize();
        Path snapshots = control.resolve("snapshots").resolve(request.migrationId()).toAbsolutePath().normalize();
        Path commitSnapshots = control.resolve("snapshots").resolve(request.migrationId() + "-commit").toAbsolutePath().normalize();
        Path staging = control.resolve("staging").resolve(request.migrationId()).toAbsolutePath().normalize();
        Path plan = plans.resolve(request.migrationId() + ".plan").toAbsolutePath().normalize();
        Path journal = journals.resolve(request.migrationId() + ".journal").toAbsolutePath().normalize();
        MigrationPaths.requireDistinctRoots(source, plans);
        MigrationPaths.requireDistinctRoots(source, journals);
        MigrationPaths.requireDistinctRoots(source, snapshots);
        MigrationPaths.requireDistinctRoots(source, commitSnapshots);
        MigrationPaths.requireDistinctRoots(source, staging);
        MigrationPaths.requireDistinctRoots(control, source);
        if (Files.exists(control.resolve("active-root.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Upgrade Control Root Contains A Partial Activation");
        }
        return new Paths(control, snapshots, commitSnapshots, staging, plan, journal);
    }

    private void requireApplyAuthorityBeforeMutation(UpgradeDryRunResult dryRun) throws IOException {
        UpgradeProposal proposal = dryRun.proposal().orElseThrow(
            () -> new MigrationException("Offline Upgrade Plan Is Missing"));
        MigrationPlan plan = proposal.plan();
        Path journalPath = request.controlRoot().resolve("journals").resolve(request.migrationId() + ".journal")
            .toAbsolutePath().normalize();
        MigrationJournal existing = Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)
            ? MigrationJournal.open(journalPath) : null;
        requireAuthorityShape();
        if (existing != null && existing.currentState().orElse(null) == MigrationJournalState.COMMITTED
            && existing.planHash().equals(plan.planHash())) {
            requireCommittedPersistedAuthority(existing.binding().orElseThrow(
                () -> new MigrationException("Committed Offline Upgrade Journal Has No Replacement Authority Binding")), plan);
            return;
        }
        requireReplacementAuthorityBinding(plan, existing != null);
    }

    private void requireAuthorityShape() throws MigrationException {
        ActivationMarkerWriter.Binding binding = request.activationMarkerWriter().binding().orElse(null);
        if (request.trustAnchor() == null || request.authorityUseGrant() == null || binding == null
            || binding.authorityBundle() == null
            || !Objects.equals(binding.trustAnchor(), request.trustAnchor())
            || !Objects.equals(binding.authorityUseGrant(), request.authorityUseGrant())) {
            throw new MigrationException("APPLY_OR_RESUME Requires A Complete Authenticated Authority");
        }
    }

    private UpgradeDryRunResult retainedDryRun(Paths paths) throws IOException {
        Snapshot snapshot = retainedSnapshot(paths);
        UpgradeProposal proposal = request.planner().plan(snapshot, request.sourceWindow());
        return new UpgradeDryRunResult(
            UpgradeStatus.READY,
            Optional.of(snapshot),
            Optional.of(proposal),
            request.sourceWindow(),
            new PreflightResult(true, List.of()),
            proposal.diagnostics());
    }

    private Snapshot retainedSnapshot(Paths paths) throws IOException {
        Path manifestPath = paths.snapshotRoot().resolveSibling(paths.snapshotRoot().getFileName() + ".manifest");
        Path statePath = paths.snapshotRoot().resolveSibling(paths.snapshotRoot().getFileName() + ".state");
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        requireVerifiedSnapshotState(statePath);
        SnapshotVerification verification = SnapshotStateStore.readVerification(statePath);
        if (!verification.verified() || !verification.failures().isEmpty()
            || !verification.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Is Not Verified");
        }
        if (!manifest.metadata().equals(request.snapshotMetadata())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Is Not Bound To The Request");
        }
        ProductionSnapshotMetadataManifest.Values metadata = ProductionSnapshotMetadataManifest.read(paths.snapshotRoot());
        if (!metadata.metadata().equals(manifest.metadata())
            || !metadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Metadata Does Not Match Manifest");
        }
        SnapshotVerification current = manifest.verify(paths.snapshotRoot());
        if (!current.verified() || !current.failures().isEmpty()
            || !current.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Tree Is Not Verified");
        }
        return new Snapshot(paths.snapshotRoot(), manifestPath, statePath, manifest.metadata(), manifest,
            SnapshotState.VERIFIED, current);
    }

    private static void requireVerifiedSnapshotState(Path statePath) throws IOException {
        Path normalized = MigrationPaths.requirePath(statePath, "statePath");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MigrationException("Retained Offline Upgrade Snapshot State Is Not A Regular File");
        }
        List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !("state=" + SnapshotState.VERIFIED.name()).equals(lines.getFirst())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot State Is Not VERIFIED");
        }
    }

    public record RunResult(UpgradeDryRunResult dryRun, UpgradeApplyResult apply) {
        public RunResult {
            dryRun = Objects.requireNonNull(dryRun, "dryRun");
        }
    }

    public record Request(
        Path sourceRoot,
        Path controlRoot,
        SnapshotMetadata snapshotMetadata,
        UpgradeSourceWindow sourceWindow,
        UpgradePlanner planner,
        PersistenceParticipantRegistry participants,
        MigrationStager stager,
        StagedMigrationValidator stagedValidator,
        ActivatedMigrationVerifier activatedVerifier,
        QuarantineAcceptance quarantineAcceptance,
        long reservedBytes,
        String migrationId,
        ActivationMarkerWriter activationMarkerWriter,
        ProductionAuthorityTrustAnchor trustAnchor,
        AuthorityUseGrant authorityUseGrant,
        Request.Mode mode,
        VerifiedSnapshotAdmission verifiedSource,
        Path coordinationRoot,
        AcceptedStagePublisher acceptedStagePublisher,
        String publisherContractIdentity
    ) {
        public enum Mode {
            PLAN_ONLY,
            APPLY_OR_RESUME
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                ActivationMarkerWriter.none(), null, null, Mode.APPLY_OR_RESUME);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, null, null, Mode.APPLY_OR_RESUME);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityBundle.TrustContext trustContext
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustContext == null ? null : trustContext.trustedAuthority().trustAnchor(), null,
                Mode.APPLY_OR_RESUME);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityTrustAnchor trustAnchor,
            AuthorityUseGrant authorityUseGrant
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, Mode.APPLY_OR_RESUME);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityTrustAnchor trustAnchor,
            AuthorityUseGrant authorityUseGrant,
            Request.Mode mode
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, mode, null, null, null, null);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityTrustAnchor trustAnchor,
            AuthorityUseGrant authorityUseGrant,
            Request.Mode mode,
            VerifiedSnapshotAdmission verifiedSource
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, mode, verifiedSource, null, null, null);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityTrustAnchor trustAnchor,
            AuthorityUseGrant authorityUseGrant,
            Request.Mode mode,
            Path coordinationRoot,
            AcceptedStagePublisher acceptedStagePublisher,
            String publisherContractIdentity
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, mode, null, coordinationRoot,
                acceptedStagePublisher, publisherContractIdentity);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            Mode mode
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                ActivationMarkerWriter.none(), null, null, mode);
        }

        public Request(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants,
            MigrationStager stager,
            StagedMigrationValidator stagedValidator,
            ActivatedMigrationVerifier activatedVerifier,
            QuarantineAcceptance quarantineAcceptance,
            long reservedBytes,
            String migrationId,
            Mode mode,
            ActivationMarkerWriter activationMarkerWriter,
            ProductionAuthorityTrustAnchor trustAnchor,
            AuthorityUseGrant authorityUseGrant
        ) {
            this(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, mode);
        }

        public Request {
            sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
            controlRoot = Objects.requireNonNull(controlRoot, "controlRoot").toAbsolutePath().normalize();
            snapshotMetadata = Objects.requireNonNull(snapshotMetadata, "snapshotMetadata");
            sourceWindow = Objects.requireNonNull(sourceWindow, "sourceWindow");
            planner = Objects.requireNonNull(planner, "planner");
            participants = Objects.requireNonNull(participants, "participants");
            stager = Objects.requireNonNull(stager, "stager");
            if (reservedBytes < 0) {
                throw new IllegalArgumentException("reservedBytes Must Be Non-Negative");
            }
            migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
            mode = Objects.requireNonNull(mode, "mode");
            activationMarkerWriter = activationMarkerWriter == null ? ActivationMarkerWriter.none() : activationMarkerWriter;
            if (coordinationRoot != null) {
                coordinationRoot = MigrationPaths.requirePath(coordinationRoot, "coordinationRoot");
                MigrationPaths.requireDistinctRoots(sourceRoot, coordinationRoot);
                MigrationPaths.requireDistinctRoots(controlRoot, coordinationRoot);
            }
            if (publisherContractIdentity == null || publisherContractIdentity.isEmpty()) {
                publisherContractIdentity = "";
            } else {
                publisherContractIdentity = MigrationCanonical.requireText(publisherContractIdentity, "publisherContractIdentity");
                if (!publisherContractIdentity.equals(publisherContractIdentity.strip())) {
                    throw new IllegalArgumentException("publisherContractIdentity Must Not Have Surrounding Whitespace");
                }
            }
            if (acceptedStagePublisher == null && !publisherContractIdentity.isEmpty()) {
                throw new IllegalArgumentException("Publisher Contract Identity Requires An Accepted Stage Publisher");
            }
            boolean fixedProductionIdentity = ProductionAcceptedStagePublisher.CONTRACT_IDENTITY.equals(publisherContractIdentity);
            if (fixedProductionIdentity && !(acceptedStagePublisher instanceof ProductionAcceptedStagePublisher)) {
                throw new IllegalArgumentException("Fixed Production Contract Identity Requires A Production Accepted Stage Publisher");
            }
            if (acceptedStagePublisher != null
                && !(acceptedStagePublisher instanceof ProductionAcceptedStagePublisher)
                && ProductionAcceptedStagePublisher.CONTRACT_IDENTITY.equals(acceptedStagePublisher.contractIdentity())) {
                throw new IllegalArgumentException("Custom Accepted Stage Publisher Cannot Claim The Fixed Production Contract Identity");
            }
            if (acceptedStagePublisher instanceof ProductionAcceptedStagePublisher productionPublisher
                && (!productionPublisher.contract().value().equals(publisherContractIdentity)
                    || coordinationRoot == null
                    || !productionPublisher.coordinationRoot().equals(coordinationRoot))) {
                throw new IllegalArgumentException("Production Accepted Stage Publisher Requires Its Fixed Contract And Coordination Root");
            }
            if (verifiedSource != null) {
                if (mode == Mode.PLAN_ONLY && !sourceRoot.equals(verifiedSource.root())) {
                    throw new IllegalArgumentException("Verified Snapshot Admission Does Not Match Source Root");
                }
                if (!snapshotMetadata.equals(verifiedSource.metadata())) {
                    throw new IllegalArgumentException("Verified Snapshot Admission Does Not Match Snapshot Metadata");
                }
                PersistenceParticipantRegistry activeParticipants = verifiedSource.participantRegistry(sourceRoot);
                if (!participants.participants().isEmpty() && !sameParticipantIdentity(participants, activeParticipants)) {
                    throw new IllegalArgumentException("Verified Snapshot Admission Owns Persistence Participant Identity");
                }
                participants = activeParticipants;
            }
            if (mode == Mode.PLAN_ONLY) {
                if (trustAnchor != null || authorityUseGrant != null || activationMarkerWriter.binding().isPresent()) {
                    throw new IllegalArgumentException("Plan-Only Offline Replacement Upgrade Cannot Carry Authority");
                }
            } else if (trustAnchor != null && authorityUseGrant != null && activationMarkerWriter.binding().isPresent()) {
                ActivationMarkerWriter.Binding binding = activationMarkerWriter.binding().orElseThrow();
                if (!Objects.equals(binding.trustAnchor(), trustAnchor)
                    || !Objects.equals(binding.authorityUseGrant(), authorityUseGrant)
                    || binding.authorityBundle() == null
                    || !trustAnchor.matches(binding.authorityBundle())) {
                    throw new IllegalArgumentException("Offline Replacement Upgrade Authority Does Not Match Activation Writer");
                }
            }
        }

        public static Request of(
            Path sourceRoot,
            Path controlRoot,
            SnapshotMetadata snapshotMetadata,
            UpgradeSourceWindow sourceWindow,
            UpgradePlanner planner,
            PersistenceParticipantRegistry participants
        ) {
            throw new IllegalArgumentException("Offline Replacement Upgrade Request Requires A Pinned Trust Anchor And Signed Authority Use Grant");
        }

        public Request withActivationMarker(ActivationMarkerWriter writer) {
            PersistenceParticipantRegistry sourceParticipants = verifiedSource == null
                ? participants : verifiedSource.participantRegistry(sourceRoot);
            return new Request(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, sourceParticipants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId, writer, trustAnchor, authorityUseGrant,
                mode, verifiedSource, coordinationRoot, acceptedStagePublisher, publisherContractIdentity);
        }

        public Request withVerifiedSource(VerifiedSnapshotAdmission admission) {
            if (verifiedSource != null) {
                throw new IllegalStateException("Offline Replacement Upgrade Request Already Has A Verified Source");
            }
            VerifiedSnapshotAdmission verified = Objects.requireNonNull(admission, "admission");
            return new Request(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner,
                verified.participantRegistry(sourceRoot), stager, stagedValidator, activatedVerifier, quarantineAcceptance,
                reservedBytes, migrationId, activationMarkerWriter, trustAnchor, authorityUseGrant, mode,
                verified, coordinationRoot, acceptedStagePublisher,
                publisherContractIdentity);
        }

        private static boolean sameParticipantIdentity(PersistenceParticipantRegistry actual,
                                                        PersistenceParticipantRegistry expected) {
            List<PersistenceParticipant> actualParticipants = actual.participants().stream().toList();
            List<PersistenceParticipant> expectedParticipants = expected.participants().stream().toList();
            if (actualParticipants.size() != expectedParticipants.size()) {
                return false;
            }
            for (int index = 0; index < actualParticipants.size(); index++) {
                PersistenceParticipant actualParticipant = actualParticipants.get(index);
                PersistenceParticipant expectedParticipant = expectedParticipants.get(index);
                if (actualParticipant.getClass() != expectedParticipant.getClass()
                    || !actualParticipant.owner().equals(expectedParticipant.owner())
                    || !MigrationPaths.requirePath(actualParticipant.root(), "participant root")
                        .equals(MigrationPaths.requirePath(expectedParticipant.root(), "participant root"))
                    || actualParticipant.classification() != expectedParticipant.classification()
                    || actualParticipant.rootMayBeAbsent() != expectedParticipant.rootMayBeAbsent()) {
                    return false;
                }
            }
            return true;
        }

        public Request withPersistenceCoordination(Path coordinationRoot,
                                                   AcceptedStagePublisher acceptedStagePublisher,
                                                   String publisherContractIdentity) {
            return new Request(sourceRoot, controlRoot, snapshotMetadata, sourceWindow, planner, participants, stager,
                stagedValidator, activatedVerifier, quarantineAcceptance, reservedBytes, migrationId,
                activationMarkerWriter, trustAnchor, authorityUseGrant, mode, verifiedSource, coordinationRoot,
                acceptedStagePublisher, publisherContractIdentity);
        }

        public Request withPersistenceCoordination(Path coordinationRoot,
                                                   AcceptedStagePublisher acceptedStagePublisher,
                                                   ProductionAcceptedStagePublisher.ContractIdentity publisherContractIdentity) {
            Objects.requireNonNull(publisherContractIdentity, "publisherContractIdentity");
            return withPersistenceCoordination(coordinationRoot, acceptedStagePublisher, publisherContractIdentity.value());
        }

        public Request withPersistenceCoordination(Path coordinationRoot,
                                                   AcceptedStagePublisher acceptedStagePublisher) {
            String identity = acceptedStagePublisher == null ? "" : acceptedStagePublisher.contractIdentity();
            if (identity.isBlank()) {
                identity = "custom-accepted-stage-publisher";
            }
            return withPersistenceCoordination(coordinationRoot, acceptedStagePublisher, identity);
        }

        public Request withProductionPersistenceCoordination(Path coordinationRoot) {
            return withPersistenceCoordination(coordinationRoot,
                new ProductionAcceptedStagePublisher(coordinationRoot),
                ProductionAcceptedStagePublisher.CONTRACT_IDENTITY);
        }

        public Request withPersistenceCoordination(Path coordinationRoot) {
            return withProductionPersistenceCoordination(coordinationRoot);
        }

        public Path persistenceCoordinationRoot() {
            return coordinationRoot;
        }
    }

    private UpgradeDryRunResult dryRunLocked() throws IOException {
        Snapshot admitted = request.verifiedSource() == null ? null : request.verifiedSource().snapshot();
        Paths paths = prepare(false);
        if (Files.exists(MigrationActivationMarker.markerPath(request.sourceRoot()), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Source Root Already Contains A Replacement Activation Marker");
        }
        if (Files.exists(ReplacementActivationRecord.path(request.sourceRoot()), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Source Root Already Contains A Replacement Activation Record");
        }
        if (Files.exists(paths.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Upgrade Journal Already Exists");
        }
        if (Files.exists(paths.commitSnapshotRoot(), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Upgrade Commit Snapshot Staging Already Exists");
        }
        if (Files.exists(paths.snapshotRoot(), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Upgrade Snapshot Staging Already Exists");
        }
        UpgradeDryRunResult result;
        if (admitted == null) {
            UpgradeDryRunRequest dryRunRequest = new UpgradeDryRunRequest(
                request.sourceRoot(),
                paths.snapshotRoot(),
                request.snapshotMetadata(),
                request.participants(),
                request.sourceWindow(),
                request.planner(),
                request.reservedBytes());
            result = new ReplacementUpgrader(new SnapshotService(new MigrationFence()),
                new MigrationCoordinator(new MigrationFence(), request.participants())).dryRun(dryRunRequest);
        } else {
            PersistenceParticipantRegistry preflightParticipants = request.verifiedSource().participantRegistry(admitted.root());
            PreflightResult preflight = new MigrationPreflight().inspect(
                admitted.root(), paths.snapshotRoot(), preflightParticipants, request.reservedBytes());
            preflight.requirePassed();
            Snapshot retained = new SnapshotService(new MigrationFence()).copyAdmitted(
                request.verifiedSource(), paths.snapshotRoot());
            result = planSnapshot(retained, preflight);
        }
        if (result.proposal().isPresent()) {
            UpgradeProposal proposal = result.proposal().get();
            requireReplacementAuthorityBinding(proposal.plan(), false);
            writePlan(paths, proposal.plan(), proposal.quarantineReport());
        }
        return result;
    }

    private UpgradeApplyResult applyLocked(UpgradeDryRunResult dryRun) throws IOException {
        requireProductionPersistence();
        if (request.verifiedSource() != null) {
            request.verifiedSource().snapshot();
        }
        Paths paths = prepare(true);
        retainedSnapshot(paths);
        UpgradeProposal proposal = dryRun.proposal().orElseThrow(() -> new MigrationException("Offline Upgrade Plan Is Missing"));
        MigrationPlan plan = proposal.plan();
        if (!plan.sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())) {
            throw new MigrationException("Offline Upgrade Plan Snapshot Does Not Match Request");
        }
        MigrationPlan retainedPlan = MigrationPlan.read(paths.planPath());
        if (!plan.planHash().equals(retainedPlan.planHash())
            || !plan.canonicalText().equals(retainedPlan.canonicalText())) {
            throw new MigrationException("Offline Upgrade Plan Does Not Match Retained Plan");
        }
        requireRetainedReport(paths, proposal.quarantineReport());
        QuarantineAcceptance acceptance = request.quarantineAcceptance();
        if (acceptance == null && proposal.quarantineReport().records().isEmpty()) {
            acceptance = proposal.quarantineReport().accept("offline-upgrader", Instant.EPOCH);
        }
        if (acceptance == null) {
            throw new MigrationException("Offline Upgrade Quarantine Acceptance Is Required");
        }
        proposal.quarantineReport().requireAccepted(acceptance);
        if (Files.exists(paths.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
            MigrationJournal existing = MigrationJournal.open(paths.journalPath());
            if (existing.currentState().orElse(null) == MigrationJournalState.COMMITTED
                && existing.planHash().equals(plan.planHash())) {
                requireCommittedPersistedAuthority(existing.binding().orElseThrow(
                    () -> new MigrationException("Committed Offline Upgrade Journal Has No Replacement Authority Binding")), plan);
                repairCommittedRootIfNeeded(paths, existing, plan, proposal, acceptance);
                requireCommittedAuthority(existing, plan, proposal, acceptance);
                requireRetainedPublication(paths, existing, plan, proposal, acceptance);
                MigrationJournal.Binding committedBinding = existing.binding().orElseThrow(
                    () -> new MigrationException("Committed Offline Upgrade Journal Has No Replacement Authority Binding"));
                AuthorityBundleConsumptionLedger.Binding authorityConsumption = authorityConsumption(
                    committedBinding.authorityBundle(), committedBinding.authorityUseGrant());
                AuthorityBundleConsumptionLedger.reserve(authorityConsumption);
                UpgradeApplyResult result = new UpgradeApplyResult(
                    UpgradeStatus.ALREADY_COMMITTED,
                    Optional.empty(),
                    Optional.of(plan.planHash()),
                    dryRun.diagnostics(),
                    false);
                Path finalReport = paths.planPath().resolveSibling(paths.planPath().getFileName() + ".final-report");
                if (Files.exists(finalReport, LinkOption.NOFOLLOW_LINKS)) {
                    requireFinalReport(finalReport, existing, plan, proposal, acceptance, proposal.diagnostics());
                } else {
                    writeFinalReport(paths, plan, proposal, acceptance, proposal.diagnostics(), result);
                }
                commitAuthorityConsumption(authorityConsumption);
                return result;
            }
            if (!existing.planHash().equals(plan.planHash())) {
                throw new MigrationException("Offline Upgrade Journal Does Not Belong To Retained Plan");
            }
            ProductionAuthorityBundle authorityBundle = requireReplacementAuthorityBinding(plan, true);
            AuthorityBundleConsumptionLedger.Binding authorityConsumption = authorityConsumption(authorityBundle);
            AuthorityBundleConsumptionLedger.reserve(authorityConsumption);
            if (existing.currentState().orElse(null) == MigrationJournalState.ROLLED_BACK) {
                UpgradeApplyResult result = new UpgradeApplyResult(
                    UpgradeStatus.FAILED,
                    Optional.empty(),
                    Optional.of(plan.planHash()),
                    proposal.diagnostics(),
                    false);
                Path finalReport = paths.planPath().resolveSibling(paths.planPath().getFileName() + ".final-report");
                if (Files.exists(finalReport, LinkOption.NOFOLLOW_LINKS)) {
                    requireRolledBackFinalReport(finalReport);
                } else {
                    writeFinalReport(paths, plan, proposal, acceptance, proposal.diagnostics(), result);
                }
                return result;
            }
            MigrationRequest recoveryRequest = buildMigration(paths, plan, proposal, acceptance, existing, true);
            MigrationJournalState beforeRecovery = existing.currentState().orElse(null);
            UpgradeApplyResult result;
            try {
                migrationCoordinator(new MigrationFence()).recover(recoveryRequest);
                MigrationJournalState afterRecovery = existing.currentState().orElse(null);
                result = new UpgradeApplyResult(
                    afterRecovery == MigrationJournalState.COMMITTED
                        ? UpgradeStatus.APPLIED : UpgradeStatus.FAILED,
                    Optional.empty(),
                    Optional.of(plan.planHash()),
                    dryRun.diagnostics(),
                    beforeRecovery != afterRecovery && afterRecovery == MigrationJournalState.COMMITTED);
            } catch (IOException | RuntimeException exception) {
                result = new UpgradeApplyResult(
                    UpgradeStatus.FAILED,
                    Optional.empty(),
                    Optional.of(plan.planHash()),
                    dryRun.diagnostics(),
                    false);
            }
            writeFinalReport(paths, plan, proposal, acceptance, proposal.diagnostics(), result);
            if (result.status() == UpgradeStatus.APPLIED
                || result.status() == UpgradeStatus.ALREADY_COMMITTED) {
                commitAuthorityConsumption(authorityConsumption);
            }
            return result;
        }
        ProductionAuthorityBundle authorityBundle = requireReplacementAuthorityBinding(plan, false);
        if (request.verifiedSource() != null) {
            requireActiveSourceMatchesAdmission(request.verifiedSource().snapshot());
        }
        AuthorityBundleConsumptionLedger.Binding authorityConsumption = authorityConsumption(authorityBundle);
        AuthorityBundleConsumptionLedger.reserve(authorityConsumption);
        ActivationMarkerWriter.Binding authority = request.activationMarkerWriter().binding().orElseThrow();
        String archivedSourceDigest = TreeDigest.of(request.sourceRoot());
        MigrationJournal journal = MigrationJournal.create(
            paths.journalPath(),
            request.migrationId(),
            plan.planHash(),
            new MigrationJournal.Binding(
                plan.sourceSnapshotId(),
                plan.sourceManifestHash(),
                proposal.quarantineReport().reportHash(),
                acceptance.acceptanceHash(),
                "",
                archivedSourceDigest,
                authority.replacementCatalogHash(),
                authority.runtimeBindingManifestHash(),
                authority.runtimeBindingManifestVersion(),
                authority.participantReadinessHash(),
                authority.participantReadinessVersion(),
                authority.authorityBundle(),
                authority.authorityTrustAnchorHash(),
                authority.authorityUseGrant()));
        MigrationFence fence = new MigrationFence();
        MigrationRequest migration = buildMigration(paths, plan, proposal, acceptance, journal, false);
        UpgradeApplyResult result = new ReplacementUpgrader(new SnapshotService(fence), migrationCoordinator(fence))
            .apply(new UpgradeApplyRequest(dryRun, migration));
        if (result.status() == UpgradeStatus.FAILED && journal.currentState().isEmpty()) {
            Files.deleteIfExists(journal.path());
        }
        if (result.status() != UpgradeStatus.ALREADY_COMMITTED) {
            writeFinalReport(paths, plan, proposal, acceptance, proposal.diagnostics(), result);
        }
        if (result.status() == UpgradeStatus.APPLIED
            || result.status() == UpgradeStatus.ALREADY_COMMITTED) {
            commitAuthorityConsumption(authorityConsumption);
        }
        return result;
    }

    private AcceptedStagePublisher.Result requireRetainedPublication(
        Paths paths,
        MigrationJournal journal,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance
    ) throws IOException {
        MigrationJournal.Binding binding = journal.binding().orElseThrow(
            () -> new MigrationException("Committed Offline Upgrade Journal Has No Binding"));
        MigrationJournal.PublicationBinding publicationBinding = binding.publicationBinding();
        if (publicationBinding == null) {
            throw new MigrationException("Committed Offline Upgrade Journal Has No Publication Binding");
        }
        if (request.acceptedStagePublisher() == null || request.publisherContractIdentity().isBlank()
            || !request.publisherContractIdentity().equals(publicationBinding.publisherContractIdentity())) {
            throw new MigrationException("Committed Offline Upgrade Publication Contract Does Not Match The Request");
        }
        Snapshot snapshot = retainedSnapshot(paths);
        Path activeRoot = MigrationPaths.requireDirectory(request.sourceRoot(), "committedRoot");
        String activeDigest = TreeDigest.of(activeRoot);
        if (binding.stagedReplacementDigest().isEmpty()
            || !binding.stagedReplacementDigest().equals(activeDigest)) {
            throw new MigrationException("Committed Offline Upgrade Active Root Does Not Match Publication Binding");
        }
        Path archive = request.controlRoot().resolve("archives")
            .resolve(request.migrationId() + "-source").toAbsolutePath().normalize();
        Optional<Path> archivedSource = Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)
            ? Optional.of(archive) : Optional.empty();
        StagedMigration staged = new StagedMigration(activeRoot, archivedSource, plan.planHash(), activeDigest);
        AcceptedStagePublisher.Publication publication = new AcceptedStagePublisher.Publication(
            request.migrationId(), snapshot, Optional.of(binding), plan.planHash(), staged,
            proposal.quarantineReport(), acceptance, null, request.publisherContractIdentity());
        AcceptedStagePublisher.Result result = request.acceptedStagePublisher().publish(publication);
        if (result == null || !result.published()
            || !publicationBinding.equals(result.publicationBinding())) {
            throw new MigrationException("Committed Offline Upgrade Retained Publication Does Not Match Journal Binding");
        }
        return result;
    }

    private void requireProductionPersistence() throws IOException {
        if (request.coordinationRoot() == null) {
            throw new MigrationException("Production Offline Upgrade Requires An Explicit Persistence Coordination Root");
        }
        MigrationPaths.requireDirectory(request.coordinationRoot(), "coordinationRoot");
        if (request.acceptedStagePublisher() == null || request.publisherContractIdentity().isBlank()) {
            throw new MigrationException("Production Offline Upgrade Requires A Non-No-Op Accepted Stage Publisher");
        }
        boolean fixedProductionIdentity = request.publisherContractIdentity()
            .equals(ProductionAcceptedStagePublisher.CONTRACT_IDENTITY);
        if (fixedProductionIdentity && !(request.acceptedStagePublisher() instanceof ProductionAcceptedStagePublisher)) {
            throw new MigrationException("Fixed Production Contract Identity Requires A Production Accepted Stage Publisher");
        }
        if (request.acceptedStagePublisher() != null
            && !(request.acceptedStagePublisher() instanceof ProductionAcceptedStagePublisher)
            && ProductionAcceptedStagePublisher.CONTRACT_IDENTITY.equals(request.acceptedStagePublisher().contractIdentity())) {
            throw new MigrationException("Custom Accepted Stage Publisher Cannot Claim The Fixed Production Contract Identity");
        }
        if (request.acceptedStagePublisher() instanceof ProductionAcceptedStagePublisher productionPublisher
            && (!fixedProductionIdentity
                || !productionPublisher.coordinationRoot().equals(request.coordinationRoot()))) {
            throw new MigrationException("Production Offline Upgrade Requires The Fixed Contract And Coordination Root");
        }
    }

    private MigrationCoordinator migrationCoordinator(MigrationFence fence) {
        return request.acceptedStagePublisher() == null
            ? new MigrationCoordinator(fence, request.participants())
            : new MigrationCoordinator(fence, request.participants(), request.acceptedStagePublisher(),
                request.publisherContractIdentity());
    }

    private MigrationRequest buildMigration(
        Paths paths,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance,
        MigrationJournal journal,
        boolean recovering
    ) {
        RootActivator roots = new RootActivator(request.sourceRoot(), request.controlRoot(), request.migrationId(), request.stager(),
            journal.binding().map(MigrationJournal.Binding::archivedSourceDigest).orElse(""));
        StagedMigrationValidator validator = staged -> {
            StagedMigration candidate = roots.candidate(staged);
            MigrationPaths.requireNoSymlinkTree(candidate.root());
            if (!candidate.contentHash().equals(TreeDigest.of(candidate.root()))) {
                throw new MigrationException("Offline Upgrade Staged Root Hash Does Not Match");
            }
            validateActivationMarker(candidate, plan);
            if (request.stagedValidator() != null) {
                request.stagedValidator().validate(candidate);
            }
        };
        ActivatedMigrationVerifier verifier = staged -> {
            MigrationPaths.requireNoSymlinkTree(staged.root());
            if (request.activatedVerifier() != null) {
                request.activatedVerifier().verify(staged);
            }
        };
        return new MigrationRequest(
            request.sourceRoot(),
            recovering ? paths.snapshotRoot() : paths.commitSnapshotRoot(),
            request.snapshotMetadata(),
            paths.migrationRoot(),
            plan,
            journal,
            roots,
            validator,
            roots,
            roots,
            verifier,
            proposal.quarantineReport(),
            acceptance,
            request.reservedBytes())
            .withActivationMarker(request.activationMarkerWriter());
    }

    private ProductionAuthorityBundle requireReplacementAuthorityBinding(MigrationPlan plan, boolean allowMissingSource)
        throws IOException {
        ActivationMarkerWriter.Binding binding = request.activationMarkerWriter().binding().orElseThrow(
            () -> new MigrationException("APPLY_OR_RESUME Requires A Runtime-Compatible Activation Authority"));
        ProductionAuthorityBundle authority = binding.authorityBundle();
        if (authority == null) {
            throw new MigrationException("APPLY_OR_RESUME Requires An Authenticated Production Authority Bundle");
        }
        ProductionAuthorityTrustAnchor anchor = request.trustAnchor();
        AuthorityUseGrant grant = request.authorityUseGrant();
        if (anchor == null || grant == null) {
            throw new MigrationException("APPLY_OR_RESUME Requires A Complete Authenticated Authority");
        }
        if (!Objects.equals(binding.trustAnchor(), request.trustAnchor())
            || !Objects.equals(binding.authorityUseGrant(), request.authorityUseGrant())) {
            throw new MigrationException("Offline Replacement Upgrade Authority Grant Does Not Match Activation Authority");
        }
        if (!anchor.matches(authority)) {
            throw new MigrationException("Offline Replacement Upgrade Authority Bundle Does Not Match The Pinned Trust Anchor");
        }
        if (!authority.snapshotId().canonicalText().equals(request.snapshotMetadata().snapshotId())) {
            throw new MigrationException("Offline Replacement Upgrade Authority Bundle Snapshot Does Not Match Source Metadata");
        }
        if (!authority.catalogContentChecksum().canonicalText().equals(request.snapshotMetadata().catalogChecksum())) {
            throw new MigrationException("Offline Replacement Upgrade Authority Bundle Catalog Does Not Match Source Metadata");
        }
        Instant now = Instant.now();
        boolean sourcePresent = Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS);
        String canonicalSourcePath;
        String sourceIdentityHash;
        if (sourcePresent) {
            canonicalSourcePath = AuthorityUseGrant.canonicalSourcePath(request.sourceRoot());
            sourceIdentityHash = AuthorityUseGrant.sourceIdentityDigest(request.sourceRoot(), authority);
            requireSourceServerIdentity(authority);
            if (!authority.verifySignature(request.sourceRoot(), anchor)) {
                throw new MigrationException("Offline Replacement Upgrade Authority Signature Does Not Match The Pinned Trust Anchor");
            }
        } else {
            if (!allowMissingSource) {
                throw new MigrationException("Offline Replacement Upgrade Source Root Is Missing");
            }
            canonicalSourcePath = request.sourceRoot().toAbsolutePath().normalize().toString();
            sourceIdentityHash = grant.sourceIdentityHash();
        }
        if (!grant.canonicalSourcePath().equals(canonicalSourcePath)
            || !grant.migrationId().equals(request.migrationId())
            || grant.invocationHash().isBlank()
            || !grant.invocationHash().equals(plan.invocationHash())
            || !grant.planPreimageHash().equals(AuthorityUseGrant.planPreimageHash(plan.canonicalText()))
            || !grant.verify(anchor, authority, canonicalSourcePath, sourceIdentityHash, request.migrationId(),
                plan.invocationHash(), AuthorityUseGrant.planPreimageHash(plan.canonicalText()), now)) {
            throw new MigrationException("Offline Replacement Upgrade Authority Use Grant Does Not Match The Requested Transaction");
        }
        if (!authority.freshAt(now)) {
            throw new MigrationException("Offline Replacement Upgrade Authority Bundle Is Outside The Freshness Window");
        }
        return authority;
    }

    private void commitAuthorityConsumption(AuthorityBundleConsumptionLedger.Binding authority) throws IOException {
        AuthorityBundleConsumptionLedger.commit(authority);
    }

    private AuthorityBundleConsumptionLedger.Binding authorityConsumption(ProductionAuthorityBundle authority) throws IOException {
        return authorityConsumption(authority, request.authorityUseGrant());
    }

    private AuthorityBundleConsumptionLedger.Binding authorityConsumption(
        ProductionAuthorityBundle authority,
        AuthorityUseGrant grant
    ) throws IOException {
        return new AuthorityBundleConsumptionLedger.Binding(request.sourceRoot(), authority, grant);
    }

    private void requireSourceServerIdentity(ProductionAuthorityBundle authority) throws IOException {
        Path identity = MigrationPaths.requirePath(request.sourceRoot().resolve("server-id"), "source server identity");
        if (Files.isSymbolicLink(identity) || !Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Replacement Upgrade Authority Bundle Requires The Source Server Identity");
        }
        String text = Files.readString(identity, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0
            || !text.substring(0, text.length() - 1).equals(authority.serverId().canonicalText())) {
            throw new MigrationException("Offline Replacement Upgrade Source Server Identity Does Not Match Authority Bundle");
        }
        try {
            if (!ServerId.parseCanonicalText(text.substring(0, text.length() - 1)).equals(authority.serverId())) {
                throw new MigrationException("Offline Replacement Upgrade Source Server Identity Does Not Match Authority Bundle");
            }
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Offline Replacement Upgrade Source Server Identity Is Invalid", exception);
        }
    }

    private void repairCommittedRootIfNeeded(
        Paths paths,
        MigrationJournal journal,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance
    ) throws IOException {
        Path expectedJournal = paths.journalPath();
        if (!journal.migrationId().equals(request.migrationId()) || !journal.path().equals(expectedJournal)
            || !journal.planHash().equals(plan.planHash()) || !plan.sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())) {
            return;
        }
        MigrationJournal.Binding value = journal.binding().orElse(null);
        ActivationMarkerWriter.Binding authority = request.activationMarkerWriter().binding().orElse(null);
        if (value == null || authority == null || value.stagedReplacementDigest().isEmpty()
            || value.archivedSourceDigest().isEmpty()) {
            return;
        }
        Path repairJournalPath = CommittedReplacementRepairJournal.path(request.controlRoot(), request.migrationId());
        boolean repairJournalExists = Files.exists(repairJournalPath, LinkOption.NOFOLLOW_LINKS);
        String expectedReplacementDigest = value.stagedReplacementDigest();
        Optional<String> activeDigest = existingRootDigest(request.sourceRoot());
        RootActivator retainedCandidate = new RootActivator(request.sourceRoot(), request.controlRoot(),
            request.migrationId(), request.stager(), value.archivedSourceDigest());
        if (activeDigest.isPresent() && activeDigest.get().equals(expectedReplacementDigest)) {
            retainedCandidate.retainCandidate(request.sourceRoot(), expectedReplacementDigest);
        } else {
            retainedCandidate.requireRetainedCandidate(expectedReplacementDigest);
        }
        if (!repairJournalExists && activeDigest.isPresent() && activeDigest.get().equals(expectedReplacementDigest)) {
            return;
        }
        if (!repairBindingMatches(value, plan, proposal, acceptance, authority)) {
            if (!repairJournalExists && activeDigest.isPresent() && activeDigest.get().equals(expectedReplacementDigest)) {
                return;
            }
            throw new MigrationException("Committed Offline Upgrade Repair Authority Does Not Match The Retained Plan");
        }
        Path archive = request.controlRoot().resolve("archives").resolve(request.migrationId() + "-source")
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)
            || !value.archivedSourceDigest().equals(TreeDigest.of(archive))) {
            if (!repairJournalExists && activeDigest.isPresent() && activeDigest.get().equals(expectedReplacementDigest)) {
                return;
            }
            throw new MigrationException("Committed Offline Upgrade Repair Source Archive Does Not Match Journal");
        }
        Path retained = request.controlRoot().resolve("archives").resolve(request.migrationId() + "-replacement")
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(retained, LinkOption.NOFOLLOW_LINKS)
            || !expectedReplacementDigest.equals(TreeDigest.of(retained))) {
            throw new MigrationException("Retained Offline Upgrade Replacement Candidate Does Not Match Journal");
        }
        Path damaged = request.controlRoot().resolve("archives").resolve(request.migrationId() + "-corrupt-replacement")
            .toAbsolutePath().normalize();
        Optional<MigrationActivationMarker.Values> marker = optionalActivationMarker(request.sourceRoot());
        CommittedReplacementRepairJournal.Values repair;
        if (repairJournalExists) {
            repair = CommittedReplacementRepairJournal.read(repairJournalPath);
            requireRepairJournalBinding(repair, request.migrationId(), plan, value, expectedReplacementDigest);
        } else {
            String initialDigest;
            if (activeDigest.isPresent()) {
                initialDigest = activeDigest.get();
            } else if (Files.isDirectory(damaged, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Committed Offline Upgrade Repair Archive Requires Its Journal");
            } else {
                throw new MigrationException("Committed Offline Upgrade Repair Source Is Missing");
            }
            if (activeDigest.isPresent() && activeDigest.get().equals(expectedReplacementDigest)) {
                return;
            }
            if (Files.exists(damaged, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Committed Offline Upgrade Repair Archive Requires Its Journal");
            }
            if (marker.isPresent() && !repairMarkerMatches(marker.get(), plan, value, authority, expectedReplacementDigest)) {
                throw new MigrationException("Committed Offline Upgrade Repair Marker Authority Does Not Match");
            }
            String activeMarkerHash = marker.isPresent() ? activationMarkerHash(request.sourceRoot()) : "";
            repair = new CommittedReplacementRepairJournal.Values(request.migrationId(), plan.planHash(), initialDigest,
                expectedReplacementDigest, value.archivedSourceDigest(), CommittedReplacementRepairJournal.State.PREPARED, "",
                activeMarkerHash);
            CommittedReplacementRepairJournal.write(repairJournalPath, repair);
            committedRepairFault.after(CommittedRepairCut.PREPARED);
        }
        resumeCommittedRepair(plan, value, authority, repair, repairJournalPath, archive, retained, damaged);
    }

    private void resumeCommittedRepair(
        MigrationPlan plan,
        MigrationJournal.Binding binding,
        ActivationMarkerWriter.Binding authority,
        CommittedReplacementRepairJournal.Values initial,
        Path repairJournalPath,
        Path archive,
        Path retained,
        Path damaged
    ) throws IOException {
        Path repairPath = request.controlRoot().resolve("staging").resolve(request.migrationId() + "-repair")
            .toAbsolutePath().normalize();
        CommittedReplacementRepairJournal.Values current = initial;
        if (!binding.archivedSourceDigest().equals(TreeDigest.of(archive))) {
            throw new MigrationException("Committed Offline Upgrade Repair Source Archive Does Not Match Journal");
        }
        if (!current.expectedReplacementDigest().equals(TreeDigest.of(retained))) {
            throw new MigrationException("Retained Offline Upgrade Replacement Candidate Does Not Match Journal");
        }
        requireRepairArchiveAuthority(damaged, plan, binding, authority, current.expectedReplacementDigest(), current.activeMarkerHash());
        if (current.state() == CommittedReplacementRepairJournal.State.PREPARED) {
            if (Files.exists(damaged, LinkOption.NOFOLLOW_LINKS)) {
                requireRepairArchiveDigest(damaged, current.activeReplacementDigest());
            } else {
                if (!Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Committed Offline Upgrade Repair Source Is Missing Before Archive");
                }
                String active = TreeDigest.of(request.sourceRoot());
                if (!active.equals(current.activeReplacementDigest())) {
                    throw new MigrationException("Committed Offline Upgrade Repair Active Root Does Not Match Journal");
                }
                Files.createDirectories(damaged.getParent());
                RootActivator.moveAtomically(request.sourceRoot(), damaged);
                requireRepairArchiveDigest(damaged, current.activeReplacementDigest());
                requireRepairArchiveAuthority(damaged, plan, binding, authority,
                    current.expectedReplacementDigest(), current.activeMarkerHash());
                committedRepairFault.after(CommittedRepairCut.CORRUPT_ARCHIVE_MOVED);
            }
            current = current.withState(CommittedReplacementRepairJournal.State.ARCHIVED,
                current.activeReplacementDigest());
            CommittedReplacementRepairJournal.write(repairJournalPath, current);
            committedRepairFault.after(CommittedRepairCut.ARCHIVED);
        }
        if (current.state() == CommittedReplacementRepairJournal.State.ARCHIVED) {
            requireRepairArchiveDigest(damaged, current.corruptArchiveDigest());
            if (Files.exists(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
                    || !current.expectedReplacementDigest().equals(TreeDigest.of(request.sourceRoot()))) {
                    throw new MigrationException("Committed Offline Upgrade Repair Active Root Changed Before Install");
                }
            } else {
                if (Files.exists(repairPath, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(repairPath, LinkOption.NOFOLLOW_LINKS)
                        || !current.expectedReplacementDigest().equals(TreeDigest.of(repairPath))) {
                        throw new MigrationException("Committed Offline Upgrade Repair Candidate Does Not Match Journal");
                    }
                    RootActivator.forceTree(repairPath);
                } else {
                    RootActivator.copyTree(retained, repairPath);
                    RootActivator.forceTree(repairPath);
                }
                if (!current.expectedReplacementDigest().equals(TreeDigest.of(repairPath))) {
                    throw new MigrationException("Committed Offline Upgrade Repair Candidate Does Not Match Journal");
                }
                Files.createDirectories(request.sourceRoot().getParent());
                RootActivator.moveAtomically(repairPath, request.sourceRoot());
                committedRepairFault.after(CommittedRepairCut.REPLACEMENT_INSTALLED);
            }
            if (!current.expectedReplacementDigest().equals(TreeDigest.of(request.sourceRoot()))) {
                throw new MigrationException("Committed Offline Upgrade Repair Active Root Does Not Match Journal");
            }
            current = current.withState(CommittedReplacementRepairJournal.State.INSTALLED, current.corruptArchiveDigest());
            CommittedReplacementRepairJournal.write(repairJournalPath, current);
            committedRepairFault.after(CommittedRepairCut.INSTALLED);
        }
        if (current.state() == CommittedReplacementRepairJournal.State.INSTALLED) {
            if (!current.expectedReplacementDigest().equals(TreeDigest.of(request.sourceRoot()))) {
                throw new MigrationException("Committed Offline Upgrade Repair Active Root Does Not Match Journal");
            }
            Optional<MigrationActivationMarker.Values> marker = optionalActivationMarker(request.sourceRoot());
            if (marker.isPresent()) {
                if (!repairMarkerMatches(marker.get(), plan, binding, authority, current.expectedReplacementDigest())) {
                    throw new MigrationException("Committed Offline Upgrade Repair Marker Authority Does Not Match");
                }
            } else {
                StagedMigration repaired = new StagedMigration(request.sourceRoot(), Optional.of(archive), plan.planHash(),
                    current.expectedReplacementDigest());
                request.activationMarkerWriter().write(plan, repaired);
                marker = optionalActivationMarker(request.sourceRoot());
                if (marker.isEmpty() || !repairMarkerMatches(marker.get(), plan, binding, authority, current.expectedReplacementDigest())) {
                    throw new MigrationException("Committed Offline Upgrade Repair Did Not Publish Activation Authority");
                }
            }
            current = current.withState(CommittedReplacementRepairJournal.State.MARKER_PUBLISHED,
                current.corruptArchiveDigest());
            CommittedReplacementRepairJournal.write(repairJournalPath, current);
            committedRepairFault.after(CommittedRepairCut.MARKER_PUBLISHED);
        }
        if (current.state() == CommittedReplacementRepairJournal.State.MARKER_PUBLISHED) {
            if (!current.expectedReplacementDigest().equals(TreeDigest.of(request.sourceRoot()))) {
                throw new MigrationException("Committed Offline Upgrade Repair Active Root Does Not Match Journal");
            }
            MigrationActivationMarker.Values marker = MigrationActivationMarker.read(request.sourceRoot());
            if (!repairMarkerMatches(marker, plan, binding, authority, current.expectedReplacementDigest())) {
                throw new MigrationException("Committed Offline Upgrade Repair Marker Authority Does Not Match");
            }
            requireRepairArchiveDigest(damaged, current.corruptArchiveDigest());
        }
    }

    private static Optional<String> existingRootDigest(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Committed Offline Upgrade Active Root Is Not A Directory");
        }
        return Optional.of(TreeDigest.of(root));
    }

    private static Optional<MigrationActivationMarker.Values> optionalActivationMarker(Path root) throws IOException {
        if (!Files.exists(MigrationActivationMarker.markerPath(root), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(MigrationActivationMarker.read(root));
    }

    private static String activationMarkerHash(Path root) throws IOException {
        Path markerPath = MigrationActivationMarker.markerPath(root);
        if (!Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        return MigrationCanonical.sha256(Files.readAllBytes(markerPath));
    }

    private static boolean repairBindingMatches(
        MigrationJournal.Binding binding,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance,
        ActivationMarkerWriter.Binding authority
    ) {
        return binding.sourceSnapshotId().equals(plan.sourceSnapshotId())
            && binding.sourceManifestHash().equals(plan.sourceManifestHash())
            && binding.quarantineReportHash().equals(proposal.quarantineReport().reportHash())
            && binding.quarantineReportHash().equals(plan.quarantineReportHash())
            && binding.acceptanceHash().equals(acceptance.acceptanceHash())
            && binding.replacementCatalogHash().equals(authority.replacementCatalogHash())
            && binding.runtimeBindingManifestHash().equals(authority.runtimeBindingManifestHash())
            && binding.runtimeBindingManifestVersion() == authority.runtimeBindingManifestVersion()
            && binding.participantReadinessHash().equals(authority.participantReadinessHash())
            && binding.participantReadinessVersion() == authority.participantReadinessVersion()
            && Objects.equals(binding.authorityBundle(), authority.authorityBundle())
            && binding.authorityTrustAnchorHash().equals(authority.authorityTrustAnchorHash())
            && Objects.equals(binding.authorityUseGrant(), authority.authorityUseGrant());
    }

    private static boolean repairMarkerMatches(
        MigrationActivationMarker.Values marker,
        MigrationPlan plan,
        MigrationJournal.Binding binding,
        ActivationMarkerWriter.Binding authority,
        String replacementDigest
    ) {
        return marker.sourceManifestHash().equals(plan.sourceManifestHash())
            && marker.planHash().equals(plan.planHash())
            && marker.replacementRootHash().equals(replacementDigest)
            && marker.archivedSourceDigest().equals(binding.archivedSourceDigest())
            && marker.replacementCatalogHash().equals(authority.replacementCatalogHash())
            && marker.runtimeBindingManifestHash().equals(authority.runtimeBindingManifestHash())
            && marker.runtimeBindingManifestVersion() == authority.runtimeBindingManifestVersion()
            && marker.participantReadinessHash().equals(authority.participantReadinessHash())
            && marker.participantReadinessVersion() == authority.participantReadinessVersion()
            && Objects.equals(marker.authorityBundle(), authority.authorityBundle())
            && marker.authorityTrustAnchorHash().equals(authority.authorityTrustAnchorHash())
            && Objects.equals(marker.authorityUseGrant(), authority.authorityUseGrant());
    }

    private static void requireRepairJournalBinding(
        CommittedReplacementRepairJournal.Values repair,
        String migrationId,
        MigrationPlan plan,
        MigrationJournal.Binding binding,
        String expectedReplacementDigest
    ) throws MigrationException {
        if (!repair.migrationId().equals(migrationId)) {
            throw new MigrationException("Committed Offline Upgrade Repair Journal Identity Does Not Match");
        }
        if (!repair.planHash().equals(plan.planHash())
            || !repair.expectedReplacementDigest().equals(expectedReplacementDigest)
            || !repair.archivedSourceDigest().equals(binding.archivedSourceDigest())) {
            throw new MigrationException("Committed Offline Upgrade Repair Journal Binding Does Not Match");
        }
    }

    private static void requireRepairArchiveDigest(Path archive, String expectedDigest) throws IOException {
        if (!Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)
            || !MigrationCanonical.requireDigest(expectedDigest, "corruptArchiveDigest").equals(TreeDigest.of(archive))) {
            throw new MigrationException("Committed Offline Upgrade Corrupt Replacement Archive Does Not Match Journal");
        }
    }

    private static void requireRepairArchiveAuthority(
        Path archive,
        MigrationPlan plan,
        MigrationJournal.Binding binding,
        ActivationMarkerWriter.Binding authority,
        String replacementDigest,
        String expectedMarkerHash
    ) throws IOException {
        if (!Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path markerPath = MigrationActivationMarker.markerPath(archive);
        boolean markerPresent = Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS);
        if (expectedMarkerHash.isEmpty()) {
            if (markerPresent) {
                throw new MigrationException("Committed Offline Upgrade Corrupt Replacement Archive Has Unexpected Authority Marker");
            }
            return;
        }
        if (!markerPresent) {
            throw new MigrationException("Committed Offline Upgrade Corrupt Replacement Archive Has No Authority Marker");
        }
        if (!expectedMarkerHash.equals(MigrationCanonical.sha256(Files.readAllBytes(markerPath)))) {
            throw new MigrationException("Committed Offline Upgrade Corrupt Replacement Archive Marker Does Not Match Journal");
        }
        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(archive);
        if (!repairMarkerMatches(marker, plan, binding, authority, replacementDigest)) {
            throw new MigrationException("Committed Offline Upgrade Corrupt Replacement Archive Authority Does Not Match");
        }
    }

    private void requireCommittedAuthority(
        MigrationJournal journal,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance
    ) throws IOException {
        Path expectedJournal = request.controlRoot().resolve("journals").resolve(request.migrationId() + ".journal")
            .toAbsolutePath().normalize();
        if (!journal.migrationId().equals(request.migrationId()) || !journal.path().equals(expectedJournal)
            || !journal.planHash().equals(plan.planHash())
            || !plan.sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())) {
            throw new MigrationException("Committed Offline Upgrade Journal Identity Does Not Match The Request");
        }
        Path markerPath = MigrationActivationMarker.markerPath(request.sourceRoot());
        if (!Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Committed Offline Upgrade Has No Replacement Activation Authority");
        }
        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(request.sourceRoot());
        String replacementHash = TreeDigest.of(request.sourceRoot());
        Optional<MigrationJournal.Binding> binding = journal.binding();
        if (binding.isEmpty()) {
            throw new MigrationException("Committed Offline Upgrade Journal Has No Replacement Authority Binding");
        }
        MigrationJournal.Binding value = binding.get();
        ActivationMarkerWriter.Binding authority = request.activationMarkerWriter().binding().orElseThrow();
        requireCommittedPersistedAuthority(value, plan);
        requireCommittedRequestSourceBinding(value);
        value.requireArchivedSourceDigest();
        Path archive = request.controlRoot().resolve("archives").resolve(request.migrationId() + "-source");
        String archivedHash = TreeDigest.of(archive);
        if (!marker.sourceManifestHash().equals(plan.sourceManifestHash())
            || !marker.planHash().equals(plan.planHash())
            || !marker.replacementRootHash().equals(replacementHash)
            || !marker.archivedSourceDigest().equals(archivedHash)
            || !value.archivedSourceDigest().equals(archivedHash)
            || !marker.replacementCatalogHash().equals(authority.replacementCatalogHash())
            || !marker.runtimeBindingManifestHash().equals(authority.runtimeBindingManifestHash())
            || marker.runtimeBindingManifestVersion() != authority.runtimeBindingManifestVersion()
            || !marker.participantReadinessHash().equals(authority.participantReadinessHash())
            || marker.participantReadinessVersion() != authority.participantReadinessVersion()
            || !Objects.equals(marker.authorityBundle(), authority.authorityBundle())
            || !marker.authorityTrustAnchorHash().equals(authority.authorityTrustAnchorHash())
            || !Objects.equals(marker.authorityUseGrant(), authority.authorityUseGrant())
            || !value.replacementCatalogHash().equals(authority.replacementCatalogHash())
            || !value.runtimeBindingManifestHash().equals(authority.runtimeBindingManifestHash())
            || value.runtimeBindingManifestVersion() != authority.runtimeBindingManifestVersion()
            || !value.participantReadinessHash().equals(authority.participantReadinessHash())
            || value.participantReadinessVersion() != authority.participantReadinessVersion()
            || !Objects.equals(value.authorityBundle(), authority.authorityBundle())
            || !value.authorityTrustAnchorHash().equals(authority.authorityTrustAnchorHash())
            || !Objects.equals(value.authorityUseGrant(), authority.authorityUseGrant())
            || !value.acceptanceHash().equals(acceptance.acceptanceHash())
            || !value.quarantineReportHash().equals(proposal.quarantineReport().reportHash())
            || !value.quarantineReportHash().equals(plan.quarantineReportHash())) {
            throw new MigrationException("Committed Offline Upgrade Authority Does Not Match The Retained Plan");
        }
        value.requireStagedReplacementDigest();
        if (!value.stagedReplacementDigest().equals(replacementHash)
            || !value.sourceSnapshotId().equals(plan.sourceSnapshotId())
            || !value.sourceManifestHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Committed Offline Upgrade Journal Binding Does Not Match The Retained Plan");
        }
    }

    private void requireCommittedPersistedAuthority(MigrationJournal.Binding binding, MigrationPlan plan) throws IOException {
        ProductionAuthorityBundle authority = binding.authorityBundle();
        AuthorityUseGrant grant = binding.authorityUseGrant();
        ProductionAuthorityTrustAnchor anchor = request.trustAnchor();
        String requestedSourcePath = Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
            ? AuthorityUseGrant.canonicalSourcePath(request.sourceRoot())
            : request.sourceRoot().toAbsolutePath().normalize().toString();
        if (authority == null || grant == null || anchor == null
            || !MigrationCanonical.sha256(anchor.canonicalBytes()).equals(binding.authorityTrustAnchorHash())
            || !anchor.matches(authority)
            || !authority.verifySignature(anchor)
            || !grant.canonicalSourcePath().equals(requestedSourcePath)
            || !grant.migrationId().equals(request.migrationId())
            || !grant.invocationHash().equals(plan.invocationHash())
            || !grant.planPreimageHash().equals(AuthorityUseGrant.planPreimageHash(plan.canonicalText()))
            || !grant.verify(anchor, authority, grant.canonicalSourcePath(), grant.sourceIdentityHash(),
                grant.migrationId(), grant.invocationHash(), grant.planPreimageHash(), grant.issuedAt())) {
            throw new MigrationException("Committed Offline Upgrade Persisted Authority Is Not Authenticated");
        }
    }

    private void requireCommittedRequestSourceBinding(MigrationJournal.Binding binding) throws IOException {
        ProductionAuthorityBundle authority = binding.authorityBundle();
        AuthorityUseGrant grant = binding.authorityUseGrant();
        ProductionAuthorityTrustAnchor anchor = request.trustAnchor();
        String canonicalSourcePath = AuthorityUseGrant.canonicalSourcePath(request.sourceRoot());
        String sourceIdentityHash = AuthorityUseGrant.sourceIdentityDigest(request.sourceRoot(), authority);
        requireSourceServerIdentity(authority);
        if (!grant.canonicalSourcePath().equals(canonicalSourcePath)
            || !grant.sourceIdentityHash().equals(sourceIdentityHash)
            || !authority.verifySignature(request.sourceRoot(), anchor)) {
            throw new MigrationException("Committed Offline Upgrade Persisted Authority Does Not Match The Requested Source Root");
        }
    }

    private static void requireFinalReport(
        Path reportPath,
        MigrationJournal journal,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance,
        DiagnosticSet expectedDiagnostics
    ) throws IOException {
        Path normalized = MigrationPaths.requirePath(reportPath, "finalReportPath");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Committed Offline Upgrade Final Report Is Not A Regular File");
        }
        if (Files.size(normalized) > 256 * 1024) {
            throw new MigrationException("Committed Offline Upgrade Final Report Is Too Large");
        }
        String content = decodeReport(Files.readAllBytes(normalized));
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (lines.isEmpty() || !lines.getLast().isEmpty()) {
            throw new MigrationException("Committed Offline Upgrade Final Report Must End With A Newline");
        }
        lines.removeLast();
        if (lines.isEmpty() || !lines.getLast().startsWith("report-hash=")) {
            throw new MigrationException("Committed Offline Upgrade Final Report Hash Is Missing");
        }
        String reportHash = MigrationCanonical.requireDigest(lines.removeLast().substring("report-hash=".length()), "reportHash");
        String canonical = String.join("\n", lines) + "\n";
        if (!MigrationCanonical.sha256(canonical).equals(reportHash)
            || !(canonical + "report-hash=" + reportHash + "\n").equals(content)) {
            throw new MigrationException("Committed Offline Upgrade Final Report Hash Does Not Match Content");
        }
        if (lines.isEmpty() || (!lines.getFirst().equals("format=1") && !lines.getFirst().equals("format=2")
            && !lines.getFirst().equals("format=3"))) {
            throw new MigrationException("Committed Offline Upgrade Final Report Format Is Invalid");
        }
        boolean authorityFormat = lines.getFirst().equals("format=2") || lines.getFirst().equals("format=3");
        boolean authorityGrantFormat = lines.getFirst().equals("format=3");
        int cursor = 1;
        String status = reportField(lines, cursor++, "status=");
        if (!status.equals(UpgradeStatus.APPLIED.name()) && !status.equals(UpgradeStatus.ALREADY_COMMITTED.name())) {
            throw new MigrationException("Committed Offline Upgrade Final Report Status Is Invalid");
        }
        String changedValue = reportField(lines, cursor++, "changed=");
        if (!changedValue.equals("true") && !changedValue.equals("false")) {
            throw new MigrationException("Committed Offline Upgrade Final Report Changed State Is Invalid");
        }
        boolean changed = Boolean.parseBoolean(changedValue);
        if (changed != status.equals(UpgradeStatus.APPLIED.name())) {
            throw new MigrationException("Committed Offline Upgrade Final Report Changed State Is Invalid");
        }
        if (!reportField(lines, cursor++, "plan-hash=").equals(plan.planHash())
            || !MigrationCanonical.decode(reportField(lines, cursor++, "source-snapshot-id=")).equals(plan.sourceSnapshotId())
            || !reportField(lines, cursor++, "source-manifest-hash=").equals(plan.sourceManifestHash())) {
            throw new MigrationException("Committed Offline Upgrade Final Report Plan Binding Does Not Match");
        }
        MigrationJournal.Binding binding = journal.binding().orElseThrow();
        if (!reportField(lines, cursor++, "archived-source-digest=").equals(binding.archivedSourceDigest())
            || !reportField(lines, cursor++, "quarantine-report-hash=").equals(proposal.quarantineReport().reportHash())
            || !reportField(lines, cursor++, "acceptance-hash=").equals(acceptance.acceptanceHash())
            || !MigrationCanonical.decode(reportField(lines, cursor++, "accepted-by=")).equals(acceptance.acceptedBy())
            || !reportField(lines, cursor++, "accepted-at=").equals(acceptance.acceptedAt().toString())
            || !reportField(lines, cursor++, "accepted-records=").equals(Integer.toString(acceptance.acceptedRecordIds().size()))
            || !reportField(lines, cursor++, "final-state=").equals(MigrationJournalState.COMMITTED.name())) {
            throw new MigrationException("Committed Offline Upgrade Final Report Transaction Binding Does Not Match");
        }
        ProductionAuthorityBundle authority = journal.binding().orElseThrow().authorityBundle();
        boolean journalGrantFormat = journal.binding().orElseThrow().authorityUseGrant() != null;
        if (authorityFormat != (authority != null) || authorityGrantFormat != journalGrantFormat) {
            throw new MigrationException("Committed Offline Upgrade Final Report Authority Format Does Not Match Journal");
        }
        if (authorityFormat && !readAuthorityReport(lines, cursor, authority, authorityGrantFormat).equals(authority)) {
            throw new MigrationException("Committed Offline Upgrade Final Report Authority Does Not Match Journal");
        }
        if (authorityFormat) {
            cursor += authorityGrantFormat ? 18 : 15;
            if (authorityGrantFormat) {
                String anchorHash = MigrationCanonical.requireDigest(reportField(lines, cursor++, "authority-trust-anchor-hash="),
                    "authorityTrustAnchorHash");
                String grantText = MigrationCanonical.decode(reportField(lines, cursor++, "authority-use-grant="));
                AuthorityUseGrant grant;
                try {
                    grant = AuthorityUseGrant.fromCanonical(grantText);
                } catch (RuntimeException exception) {
                    throw new MigrationException("Committed Offline Upgrade Final Report Authority Use Grant Is Invalid", exception);
                }
                if (!anchorHash.equals(binding.authorityTrustAnchorHash())
                    || !grant.equals(binding.authorityUseGrant())) {
                    throw new MigrationException("Committed Offline Upgrade Final Report Authority Use Grant Does Not Match Journal");
                }
            }
        }
        int diagnostics = parseReportCount(reportField(lines, cursor++, "diagnostics="));
        String diagnosticsHash = MigrationCanonical.requireDigest(reportField(lines, cursor++, "diagnostics-hash="), "diagnosticsHash");
        if (!diagnosticsHash.equals(MigrationCanonical.sha256(expectedDiagnostics.toJson()))) {
            throw new MigrationException("Committed Offline Upgrade Final Report Diagnostics Do Not Match");
        }
        List<String> expectedDiagnosticRows = diagnosticRows(expectedDiagnostics);
        List<String> actualDiagnosticRows = new ArrayList<>();
        for (int index = 0; index < diagnostics; index++) {
            String diagnostic = reportField(lines, cursor++, "diagnostic=");
            String[] fields = diagnostic.split("\\|", -1);
            if (fields.length != 2) {
                throw new MigrationException("Committed Offline Upgrade Final Report Diagnostic Is Invalid");
            }
            MigrationCanonical.decode(fields[0]);
            MigrationCanonical.decode(fields[1]);
            actualDiagnosticRows.add(diagnostic);
        }
        if (diagnostics != expectedDiagnosticRows.size() || !actualDiagnosticRows.equals(expectedDiagnosticRows)) {
            throw new MigrationException("Committed Offline Upgrade Final Report Diagnostics Do Not Match");
        }
        if (cursor != lines.size()) {
            throw new MigrationException("Committed Offline Upgrade Final Report Contains Extra Content");
        }
    }

    private static void requireRolledBackFinalReport(Path reportPath) throws IOException {
        Path normalized = MigrationPaths.requirePath(reportPath, "finalReportPath");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Rolled Back Offline Upgrade Final Report Is Not A Regular File");
        }
        if (Files.size(normalized) > 65536) {
            throw new MigrationException("Rolled Back Offline Upgrade Final Report Is Too Large");
        }
        String content = decodeReport(Files.readAllBytes(normalized));
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (lines.isEmpty() || !lines.getLast().isEmpty()) {
            throw new MigrationException("Rolled Back Offline Upgrade Final Report Must End With A Newline");
        }
        lines.removeLast();
        if (lines.isEmpty() || !lines.getLast().startsWith("report-hash=")) {
            throw new MigrationException("Rolled Back Offline Upgrade Final Report Hash Is Missing");
        }
        String reportHash = MigrationCanonical.requireDigest(lines.removeLast().substring("report-hash=".length()), "reportHash");
        String canonical = String.join("\n", lines) + "\n";
        if (!MigrationCanonical.sha256(canonical).equals(reportHash)
            || !(canonical + "report-hash=" + reportHash + "\n").equals(content)
            || !lines.contains("status=FAILED")
            || !lines.contains("changed=false")
            || !lines.contains("final-state=" + MigrationJournalState.ROLLED_BACK.name())) {
            throw new MigrationException("Rolled Back Offline Upgrade Final Report Is Invalid");
        }
    }

    private static List<String> diagnosticRows(DiagnosticSet diagnostics) {
        return diagnostics.diagnostics().stream()
            .map(diagnostic -> MigrationCanonical.encode(diagnostic.code()) + "|"
                + MigrationCanonical.encode(diagnostic.message()))
            .toList();
    }

    private static ProductionAuthorityBundle readAuthorityReport(List<String> lines, int index,
                                                                 ProductionAuthorityBundle expected,
                                                                 boolean grantFormat) throws MigrationException {
        try {
            ServerId serverId = ServerId.parseCanonicalText(reportField(lines, index++, "authority-server-id="));
            String installationId = serverId.canonicalText();
            String authorityKeyId;
            String publicKeyFingerprint;
            if (grantFormat) {
                installationId = MigrationCanonical.decode(reportField(lines, index++, "authority-installation-id="));
                authorityKeyId = MigrationCanonical.decode(reportField(lines, index++, "authority-key-id="));
                publicKeyFingerprint = MigrationCanonical.requireDigest(reportField(lines, index++, "authority-public-key-fingerprint="),
                    "authorityPublicKeyFingerprint");
            } else {
                authorityKeyId = null;
                publicKeyFingerprint = null;
            }
            SnapshotId snapshotId = SnapshotId.parseCanonicalText(reportField(lines, index++, "authority-snapshot-id="));
            String timestampText = reportField(lines, index++, "authority-timestamp=");
            Instant timestamp = Instant.parse(timestampText);
            if (!timestamp.toString().equals(timestampText)) {
                throw new MigrationException("Final Report Authority Timestamp Is Not Canonical");
            }
            ContentHash catalogChecksum = ContentHash.parseCanonicalText(reportField(lines, index++, "authority-catalog-content-checksum="));
            long generation = Long.parseLong(reportField(lines, index++, "authority-catalog-generation="));
            int contractGeneration = Integer.parseInt(reportField(lines, index++, "authority-catalog-contract-generation="));
            int contractMinor = Integer.parseInt(reportField(lines, index++, "authority-catalog-contract-minor="));
            ContentHash runtimeHash = ContentHash.parseCanonicalText(reportField(lines, index++, "authority-runtime-binding-manifest-hash="));
            int runtimeVersion = Integer.parseInt(reportField(lines, index++, "authority-runtime-binding-manifest-version="));
            String readinessHash = MigrationCanonical.requireDigest(reportField(lines, index++, "authority-readiness-report-hash="),
                "authorityReadinessReportHash");
            int readinessVersion = Integer.parseInt(reportField(lines, index++, "authority-readiness-report-version="));
            String installAuthorityHash = MigrationCanonical.requireDigest(reportField(lines, index++, "authority-install-authority-hash="),
                "installAuthorityHash");
            String signingPublicKey = reportField(lines, index++, "authority-signing-public-key=");
            String signature = reportField(lines, index++, "authority-signature=");
            String bundleHash = MigrationCanonical.requireDigest(reportField(lines, index, "authority-bundle-hash="), "authorityBundleHash");
            ProductionAuthorityBundle value = grantFormat
                ? ProductionAuthorityBundle.create(serverId, installationId, authorityKeyId, publicKeyFingerprint, snapshotId,
                    timestamp, catalogChecksum, generation, new CatalogVersion(contractGeneration, contractMinor), runtimeHash,
                    runtimeVersion, readinessHash, readinessVersion, installAuthorityHash, signingPublicKey, signature)
                : ProductionAuthorityBundle.create(serverId, snapshotId, timestamp, catalogChecksum, generation,
                    new CatalogVersion(contractGeneration, contractMinor), runtimeHash, runtimeVersion, readinessHash,
                    readinessVersion, installAuthorityHash, signingPublicKey, signature);
            if (!value.bundleHash().equals(bundleHash) || !value.equals(expected)) {
                throw new MigrationException("Final Report Authority Bundle Does Not Match Journal");
            }
            return value;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Final Report Authority Bundle Is Invalid", exception);
        }
    }

    private static String reportField(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Offline Upgrade Final Report Field: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static int parseReportCount(String value) throws MigrationException {
        try {
            int count = Integer.parseInt(value);
            if (count < 0) {
                throw new NumberFormatException();
            }
            return count;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Offline Upgrade Final Report Diagnostic Count", exception);
        }
    }

    private static String decodeReport(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            if (!Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes) || value.indexOf('\r') >= 0) {
                throw new MigrationException("Committed Offline Upgrade Final Report Encoding Is Not Canonical");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw new MigrationException("Committed Offline Upgrade Final Report Encoding Is Invalid", exception);
        }
    }

    private static void writeFinalReport(
        Paths paths,
        MigrationPlan plan,
        UpgradeProposal proposal,
        QuarantineAcceptance acceptance,
        DiagnosticSet diagnostics,
        UpgradeApplyResult result
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        MigrationJournal.Binding authorityBinding = null;
        if (Files.isRegularFile(paths.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
            authorityBinding = MigrationJournal.open(paths.journalPath()).binding().orElse(null);
        }
        ProductionAuthorityBundle authorityBundle = authorityBinding == null ? null : authorityBinding.authorityBundle();
        boolean authorityGrant = authorityBinding != null && authorityBinding.authorityUseGrant() != null;
        report.append("format=").append(authorityBundle == null ? 1 : authorityGrant ? 3 : 2).append('\n');
        report.append("status=").append(result.status()).append('\n');
        report.append("changed=").append(result.changed()).append('\n');
        report.append("plan-hash=").append(plan.planHash()).append('\n');
        report.append("source-snapshot-id=").append(MigrationCanonical.encode(plan.sourceSnapshotId())).append('\n');
        report.append("source-manifest-hash=").append(plan.sourceManifestHash()).append('\n');
        String archivedSourceDigest = "";
        MigrationJournalState journalState = result.migration().map(MigrationResult::finalState).orElse(null);
        if (Files.isRegularFile(paths.journalPath(), LinkOption.NOFOLLOW_LINKS)) {
            MigrationJournal journal = MigrationJournal.open(paths.journalPath());
            archivedSourceDigest = journal.binding().map(MigrationJournal.Binding::archivedSourceDigest).orElse("");
            if (journalState == null) {
                journalState = journal.currentState().orElse(null);
            }
        }
        report.append("archived-source-digest=").append(archivedSourceDigest).append('\n');
        report.append("quarantine-report-hash=").append(proposal.quarantineReport().reportHash()).append('\n');
        report.append("acceptance-hash=").append(acceptance.acceptanceHash()).append('\n');
        report.append("accepted-by=").append(MigrationCanonical.encode(acceptance.acceptedBy())).append('\n');
        report.append("accepted-at=").append(acceptance.acceptedAt()).append('\n');
        report.append("accepted-records=").append(acceptance.acceptedRecordIds().size()).append('\n');
        report.append("final-state=").append(journalState == null
            ? result.status() == UpgradeStatus.ALREADY_COMMITTED
                ? MigrationJournalState.COMMITTED.name() : "NONE"
            : journalState.name()).append('\n');
        appendAuthorityReport(report, authorityBinding);
        report.append("diagnostics=").append(diagnostics.diagnostics().size()).append('\n');
        report.append("diagnostics-hash=").append(MigrationCanonical.sha256(diagnostics.toJson())).append('\n');
        diagnostics.diagnostics().forEach(diagnostic -> report.append("diagnostic=")
            .append(MigrationCanonical.encode(diagnostic.code())).append('|')
            .append(MigrationCanonical.encode(diagnostic.message())).append('\n'));
        String content = report.toString();
        Path reportPath = paths.planPath().resolveSibling(paths.planPath().getFileName() + ".final-report");
        AtomicFiles.write(reportPath, (content + "report-hash=" + MigrationCanonical.sha256(content) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void appendAuthorityReport(StringBuilder report, MigrationJournal.Binding binding) {
        if (binding == null || binding.authorityBundle() == null) {
            return;
        }
        ProductionAuthorityBundle authority = binding.authorityBundle();
        report.append("authority-server-id=").append(authority.serverId().canonicalText()).append('\n')
            .append(binding.authorityUseGrant() == null ? "" : "authority-installation-id="
                + MigrationCanonical.encode(authority.installationId()) + "\n")
            .append(binding.authorityUseGrant() == null ? "" : "authority-key-id="
                + MigrationCanonical.encode(authority.authorityKeyId()) + "\n")
            .append(binding.authorityUseGrant() == null ? "" : "authority-public-key-fingerprint="
                + authority.publicKeyFingerprint() + "\n")
            .append("authority-snapshot-id=").append(authority.snapshotId().canonicalText()).append('\n')
            .append("authority-timestamp=").append(authority.timestamp()).append('\n')
            .append("authority-catalog-content-checksum=").append(authority.catalogContentChecksum().canonicalText()).append('\n')
            .append("authority-catalog-generation=").append(authority.catalogGeneration()).append('\n')
            .append("authority-catalog-contract-generation=").append(authority.catalogContractVersion().generation()).append('\n')
            .append("authority-catalog-contract-minor=").append(authority.catalogContractVersion().minor()).append('\n')
            .append("authority-runtime-binding-manifest-hash=").append(authority.runtimeBindingManifestHash().canonicalText()).append('\n')
            .append("authority-runtime-binding-manifest-version=").append(authority.runtimeBindingManifestVersion()).append('\n')
            .append("authority-readiness-report-hash=").append(authority.readinessReportHash()).append('\n')
            .append("authority-readiness-report-version=").append(authority.readinessReportVersion()).append('\n')
            .append("authority-install-authority-hash=").append(authority.installAuthorityHash()).append('\n')
            .append("authority-signing-public-key=").append(authority.signingPublicKey()).append('\n')
            .append("authority-signature=").append(authority.signature()).append('\n')
            .append("authority-bundle-hash=").append(authority.bundleHash()).append('\n');
        if (binding.authorityUseGrant() != null) {
            report.append("authority-trust-anchor-hash=").append(binding.authorityTrustAnchorHash()).append('\n')
                .append("authority-use-grant=").append(MigrationCanonical.encode(binding.authorityUseGrant().canonical())).append('\n');
        }
    }

    private static void validateActivationMarker(StagedMigration staged, MigrationPlan plan) throws IOException {
        Path markerPath = MigrationActivationMarker.markerPath(staged.root());
        if (!Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(staged.root());
        if (!marker.sourceManifestHash().equals(plan.sourceManifestHash())
            || !marker.planHash().equals(plan.planHash())
            || !marker.replacementRootHash().equals(staged.contentHash())) {
            throw new MigrationException("Offline Upgrade Activation Marker Does Not Match Staged Root");
        }
    }

    private Paths prepare(boolean applying) throws IOException {
        Path source = MigrationPaths.requirePath(request.sourceRoot(), "sourceRoot");
        Path control = MigrationPaths.requirePath(request.controlRoot(), "controlRoot");
        if (source.getParent() == null || !source.getParent().equals(control.getParent()) || !CONTROL_DIRECTORY_NAME.equals(control.getFileName().toString())) {
            throw new MigrationException("controlRoot Must Be The Sibling .resync-replacement-control Directory");
        }
        MigrationPaths.requireDistinctRoots(source, control);
        MigrationPaths.requireWritableParent(control);
        Files.createDirectories(control);
        if (Files.isSymbolicLink(control) || !Files.isDirectory(control, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("controlRoot Must Be A Non-Symbolic-Link Directory");
        }
        boolean sourcePresent = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        MigrationPaths.requireWritableParent(MigrationActivationMarker.markerPath(source));
        if (request.participants().participants().isEmpty()) {
            throw new MigrationException("At Least One Offline Upgrade Participant Is Required");
        }
        request.sourceWindow().requireSupported(request.snapshotMetadata());
        Path plans = control.resolve("plans").toAbsolutePath().normalize();
        Path journals = control.resolve("journals").toAbsolutePath().normalize();
        Path snapshots = control.resolve("snapshots").resolve(request.migrationId()).toAbsolutePath().normalize();
        Path commitSnapshots = control.resolve("snapshots").resolve(request.migrationId() + "-commit").toAbsolutePath().normalize();
        Path staging = control.resolve("staging").resolve(request.migrationId()).toAbsolutePath().normalize();
        Path plan = plans.resolve(request.migrationId() + ".plan").toAbsolutePath().normalize();
        Path journal = journals.resolve(request.migrationId() + ".journal").toAbsolutePath().normalize();
        boolean authorityRecovery = false;
        if (Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(journal)) {
            MigrationJournal retainedJournal = MigrationJournal.open(journal);
            MigrationJournalState state = retainedJournal.currentState().orElse(null);
            authorityRecovery = state == MigrationJournalState.ACTIVATED || state == MigrationJournalState.COMMITTED;
        }
        if (!sourcePresent && !Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("sourceRoot Must Be An Existing Directory: " + source);
        }
        if (sourcePresent) {
            MigrationPaths.requireDirectory(source, "sourceRoot");
            MigrationPaths.requireNoSymlinkTree(source);
            if (!authorityRecovery) {
                request.participants().validateForRoot(source);
            }
        }
        MigrationPaths.requireDistinctRoots(source, plans);
        MigrationPaths.requireDistinctRoots(source, journals);
        MigrationPaths.requireDistinctRoots(source, snapshots);
        MigrationPaths.requireDistinctRoots(source, commitSnapshots);
        MigrationPaths.requireDistinctRoots(source, staging);
        MigrationPaths.requireDistinctRoots(control, source);
        if (Files.exists(control.resolve("active-root.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Offline Upgrade Control Root Contains A Partial Activation");
        }
        if (applying && !Files.isDirectory(snapshots, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Is Missing");
        }
        return new Paths(control, snapshots, commitSnapshots, staging, plan, journal);
    }

    private UpgradeLocks acquireLocks() throws IOException {
        Path control = MigrationPaths.requirePath(request.controlRoot(), "controlRoot");
        return UpgradeLocks.open(control);
    }

    private static void writePlan(Paths paths, MigrationPlan plan, QuarantineReport report) throws IOException {
        Files.createDirectories(paths.planPath().getParent());
        if (Files.exists(paths.planPath(), LinkOption.NOFOLLOW_LINKS)) {
            MigrationPlan retained = MigrationPlan.read(paths.planPath());
            if (!plan.planHash().equals(retained.planHash())
                || !plan.canonicalText().equals(retained.canonicalText())) {
                throw new MigrationException("Retained Offline Upgrade Plan Hash Does Not Match");
            }
            Path reportPath = paths.planPath().resolveSibling(paths.planPath().getFileName() + ".report");
            if (!Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS)) {
                writeReport(reportPath, report.canonicalText());
            } else {
                requireReport(reportPath, report);
            }
            return;
        }
        plan.write(paths.planPath());
        Path reportPath = paths.planPath().resolveSibling(paths.planPath().getFileName() + ".report");
        try {
            writeReport(reportPath, report.canonicalText());
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(paths.planPath());
            throw exception;
        }
    }

    private static void requireRetainedReport(Paths paths, QuarantineReport report) throws IOException {
        requireReport(paths.planPath().resolveSibling(paths.planPath().getFileName() + ".report"), report);
    }

    private static void requireReport(Path path, QuarantineReport report) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "reportPath");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MigrationException("Retained Quarantine Report Is Not A Regular File");
        }
        String canonical = report.canonicalText();
        String content = Files.readString(normalized, StandardCharsets.UTF_8);
        String suffix = content.substring(Math.min(content.length(), canonical.length()));
        if (!content.startsWith(canonical)
            || !suffix.startsWith("report-hash=")
            || !suffix.endsWith("\n")
            || suffix.substring("report-hash=".length(), suffix.length() - 1).contains("\n")) {
            throw new MigrationException("Retained Quarantine Report Does Not Match Proposal");
        }
        String hash = suffix.substring("report-hash=".length(), suffix.length() - 1);
        if (!MigrationCanonical.requireDigest(hash, "reportHash").equals(MigrationCanonical.sha256(canonical))) {
            throw new MigrationException("Retained Quarantine Report Hash Does Not Match Content");
        }
    }

    private static void writeReport(Path path, String canonical) throws IOException {
        AtomicFiles.write(path, (canonical + "report-hash=" + MigrationCanonical.sha256(canonical) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private record Paths(Path controlRoot, Path snapshotRoot, Path commitSnapshotRoot, Path migrationRoot, Path planPath, Path journalPath) {
    }

    private static final class RootActivator implements MigrationStager, MigrationActivator, MigrationRollback {
        private static final String REPLACEMENT_ARCHIVE_SUFFIX = "-replacement";
        private static final String REPLACEMENT_STAGE_SUFFIX = "-replacement.stage";
        private static final String REPLACEMENT_EVIDENCE_SUFFIX = "-corrupt-replacement";
        private final Path sourceRoot;
        private final Path controlRoot;
        private final String migrationId;
        private final MigrationStager stager;
        private final String expectedArchivedSourceDigest;
        private Path candidateRoot;
        private Path archiveRoot;
        private Path replacementArchiveRoot;
        private Path replacementArchiveStageRoot;
        private Path replacementArchiveEvidenceRoot;
        private String archivedSourceDigest;
        private boolean activated;

        private RootActivator(Path sourceRoot, Path controlRoot, String migrationId, MigrationStager stager, String expectedArchivedSourceDigest) {
            this.sourceRoot = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
            this.controlRoot = MigrationPaths.requirePath(controlRoot, "controlRoot");
            this.migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
            this.stager = Objects.requireNonNull(stager, "stager");
            Path stagingParent = this.controlRoot.resolve("staging").toAbsolutePath().normalize();
            Path archiveParent = this.controlRoot.resolve("archives").toAbsolutePath().normalize();
            this.candidateRoot = canonicalSibling(stagingParent, this.migrationId, "candidateRoot");
            this.archiveRoot = canonicalSibling(archiveParent, this.migrationId + "-source", "archiveRoot");
            this.replacementArchiveRoot = canonicalSibling(archiveParent,
                this.migrationId + REPLACEMENT_ARCHIVE_SUFFIX, "replacementArchiveRoot");
            this.replacementArchiveStageRoot = canonicalSibling(archiveParent,
                this.migrationId + REPLACEMENT_STAGE_SUFFIX, "replacementArchiveStageRoot");
            this.replacementArchiveEvidenceRoot = canonicalSibling(archiveParent,
                this.migrationId + REPLACEMENT_EVIDENCE_SUFFIX, "replacementArchiveEvidenceRoot");
            this.expectedArchivedSourceDigest = expectedArchivedSourceDigest == null || expectedArchivedSourceDigest.isBlank()
                ? null : MigrationCanonical.requireDigest(expectedArchivedSourceDigest, "expectedArchivedSourceDigest");
            this.archivedSourceDigest = this.expectedArchivedSourceDigest;
        }

        @Override
        public StagedMigration stage(Path sourceSnapshot, Path stagingRoot, MigrationPlan plan) throws IOException {
            StagedMigration copied = stager.stage(sourceSnapshot, stagingRoot, plan);
            candidateRoot = copied.root();
            return withPreviousRoot(copied, Optional.of(sourceRoot));
        }

        @Override
        public StagedMigration stage(
            Path sourceSnapshot,
            Path stagingRoot,
            MigrationPlan plan,
            QuarantineReport quarantineReport,
            QuarantineAcceptance quarantineAcceptance
        ) throws IOException {
            StagedMigration copied = stager.stage(sourceSnapshot, stagingRoot, plan, quarantineReport, quarantineAcceptance);
            candidateRoot = copied.root();
            return withPreviousRoot(copied, Optional.of(sourceRoot));
        }

        @Override
        public void activate(StagedMigration staged) throws IOException {
            if (activated) {
                return;
            }
            Path candidate = MigrationPaths.requirePath(candidateRoot, "candidateRoot");
            Path archives = requireArchiveParent();
            MigrationPaths.requireDistinctRoots(sourceRoot, archiveRoot);
            MigrationPaths.requireDistinctRoots(candidate, archiveRoot);
            MigrationPaths.requireDistinctRoots(candidate, replacementArchiveRoot);
            MigrationPaths.requireDistinctRoots(candidate, replacementArchiveStageRoot);
            MigrationPaths.requireDistinctRoots(candidate, replacementArchiveEvidenceRoot);
            requireArchivePath(archives, archiveRoot, "archiveRoot");
            requireArchivePath(archives, replacementArchiveRoot, "replacementArchiveRoot");
            requireArchivePath(archives, replacementArchiveStageRoot, "replacementArchiveStageRoot");
            requireArchivePath(archives, replacementArchiveEvidenceRoot, "replacementArchiveEvidenceRoot");
            if (Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
                verifyArchive();
                String activeDigest = Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                    ? TreeDigest.of(sourceRoot) : null;
                boolean activeReplacement = activeDigest != null && staged.contentHash().equals(activeDigest);
                if (Files.exists(candidateRoot, LinkOption.NOFOLLOW_LINKS)) {
                    forceTree(candidate);
                    String candidateDigest = TreeDigest.of(candidate);
                    if (!staged.contentHash().equals(candidateDigest)) {
                        throw new MigrationException("Offline Upgrade Candidate Hash Does Not Match");
                    }
                    if (activeReplacement) {
                        if (!candidateDigest.equals(activeDigest)) {
                            throw new MigrationException("Offline Upgrade Candidate Does Not Match Active Replacement");
                        }
                        MigrationPaths.requireDistinctRoots(candidate, sourceRoot);
                        retainCandidate(candidate, staged.contentHash());
                        deleteCandidate(candidate);
                        activated = true;
                        return;
                    }
                    retainCandidate(candidate, staged.contentHash());
                } else if (activeReplacement) {
                    retainCandidate(sourceRoot, staged.contentHash());
                }
                if (activeReplacement
                    && !Files.exists(candidateRoot, LinkOption.NOFOLLOW_LINKS)) {
                    activated = true;
                    return;
                }
                if (!Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                    candidate = MigrationPaths.requireDirectory(candidate, "candidateRoot");
                    if (!staged.contentHash().equals(TreeDigest.of(candidate))) {
                        throw new MigrationException("Offline Upgrade Candidate Hash Does Not Match");
                    }
                    moveAtomically(candidate, sourceRoot);
                    activated = true;
                    return;
                }
                throw new MigrationException("Offline Upgrade Source Archive Already Exists");
            }
            if (Files.exists(MigrationActivationMarker.markerPath(sourceRoot), LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Source Root Already Contains A Replacement Activation Marker");
            }
            candidate = MigrationPaths.requireDirectory(candidate, "candidateRoot");
            forceTree(candidate);
            if (!staged.contentHash().equals(TreeDigest.of(candidate))) {
                throw new MigrationException("Offline Upgrade Candidate Hash Does Not Match");
            }
            retainCandidate(candidate, staged.contentHash());
            String originalSourceDigest = TreeDigest.of(sourceRoot);
            boolean sourceMoved = false;
            try {
                moveAtomically(sourceRoot, archiveRoot);
                sourceMoved = true;
                archivedSourceDigest = TreeDigest.of(archiveRoot);
                if (!originalSourceDigest.equals(archivedSourceDigest)
                    || (expectedArchivedSourceDigest != null && !expectedArchivedSourceDigest.equals(archivedSourceDigest))) {
                    throw new MigrationException("Offline Upgrade Source Archive Does Not Match Original Source");
                }
                moveAtomically(candidate, sourceRoot);
                activated = true;
            } catch (IOException | RuntimeException exception) {
                if (sourceMoved && Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS) && !Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        moveAtomically(archiveRoot, sourceRoot);
                    } catch (IOException | RuntimeException restoreFailure) {
                        exception.addSuppressed(restoreFailure);
                    }
                }
                throw exception;
            }
        }

        @Override
        public void rollback(StagedMigration staged) throws IOException {
            Path candidate = MigrationPaths.requirePath(candidateRoot, "candidateRoot");
            boolean sourceExists = Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS);
            boolean archiveExists = Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS);
            if (!archiveExists) {
                deleteCandidate(candidate);
                return;
            }
            Path archive = MigrationPaths.requireDirectory(archiveRoot, "archiveRoot");
            verifyArchive();
            if (sourceExists) {
                Path active = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomically(active, candidate);
                } else {
                    throw new MigrationException("Offline Upgrade Rollback Candidate Already Exists");
                }
            }
            moveAtomically(archive, sourceRoot);
            deleteCandidate(candidate);
            activated = false;
            archivedSourceDigest = expectedArchivedSourceDigest;
        }

        @Override
        public Optional<Path> activeRoot() {
            return Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) ? Optional.of(sourceRoot) : Optional.empty();
        }

        @Override
        public Optional<Path> archivedSourceRoot() {
            return Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS) ? Optional.of(archiveRoot) : Optional.empty();
        }

        private StagedMigration candidate(StagedMigration staged) throws IOException {
            Path candidate = Files.isDirectory(candidateRoot, LinkOption.NOFOLLOW_LINKS)
                ? MigrationPaths.requireDirectory(candidateRoot, "candidateRoot")
                : MigrationPaths.requireDirectory(sourceRoot, "activeRoot");
            return withRoot(staged, candidate, Optional.empty());
        }

        private static StagedMigration withPreviousRoot(StagedMigration staged, Optional<Path> previousRoot) {
            return withRoot(staged, staged.root(), previousRoot);
        }

        private static StagedMigration withRoot(StagedMigration staged, Path root,
                                                Optional<Path> previousRoot) {
            return staged.withRoots(root, previousRoot);
        }

        private void verifyArchive() throws IOException {
            Path archive = MigrationPaths.requireDirectory(archiveRoot, "archiveRoot");
            String digest = TreeDigest.of(archive);
            if (expectedArchivedSourceDigest != null && !expectedArchivedSourceDigest.equals(digest)) {
                throw new MigrationException("Offline Upgrade Source Archive Digest Does Not Match Journal Binding");
            }
            archivedSourceDigest = digest;
        }

        private void retainCandidate(Path candidate, String expectedDigest) throws IOException {
            String expected = MigrationCanonical.requireDigest(expectedDigest, "expectedReplacementDigest");
            Path archives = requireArchiveParent();
            Path retained = replacementArchiveRoot;
            Path stage = replacementArchiveStageRoot;
            Path evidence = replacementArchiveEvidenceRoot;
            requireArchivePath(archives, retained, "replacementArchiveRoot");
            requireArchivePath(archives, stage, "replacementArchiveStageRoot");
            requireArchivePath(archives, evidence, "replacementArchiveEvidenceRoot");
            requireCandidateDirectory(candidate, "candidateRoot");
            requireEvidenceDirectoryIfPresent(evidence);

            boolean retainedExists = existsNoFollow(retained);
            boolean stageExists = existsNoFollow(stage);
            if (retainedExists) {
                requireCandidateDirectory(retained, "replacementArchiveRoot");
                if (expected.equals(TreeDigest.of(retained))) {
                    if (stageExists) {
                        throw new MigrationException("Retained Offline Upgrade Replacement Candidate Has A Staging Collision");
                    }
                    return;
                }
                if (stageExists) {
                    throw new MigrationException("Retained Offline Upgrade Replacement Candidate Has An Archive Collision");
                }
                moveFailedCandidateToEvidence(retained, evidence);
                retainedExists = false;
            }
            if (stageExists) {
                requireCandidateDirectory(stage, "replacementArchiveStageRoot");
                if (expected.equals(TreeDigest.of(stage))) {
                    moveAtomically(stage, retained);
                    requireCandidateDigest(retained, expected, "Retained Offline Upgrade Replacement Candidate");
                    return;
                }
                moveFailedCandidateToEvidence(stage, evidence);
            }
            if (retainedExists || existsNoFollow(retained) || existsNoFollow(stage)) {
                throw new MigrationException("Retained Offline Upgrade Replacement Candidate Has An Unknown State");
            }
            Path normalizedCandidate = requireCandidateDirectory(candidate, "candidateRoot");
            requireCandidateDigest(normalizedCandidate, expected, "Offline Upgrade Candidate");
            try {
                copyTree(normalizedCandidate, stage);
                requireCandidateDigest(stage, expected, "Retained Offline Upgrade Replacement Candidate");
                moveAtomically(stage, retained);
                requireCandidateDigest(retained, expected, "Retained Offline Upgrade Replacement Candidate");
            } catch (IOException | RuntimeException exception) {
                if (existsNoFollow(stage)) {
                    try {
                        moveFailedCandidateToEvidence(stage, evidence);
                    } catch (IOException | RuntimeException evidenceFailure) {
                        exception.addSuppressed(evidenceFailure);
                    }
                }
                throw exception;
            }
        }

        private void requireRetainedCandidate(String expectedDigest) throws IOException {
            String expected = MigrationCanonical.requireDigest(expectedDigest, "expectedReplacementDigest");
            Path archives = requireArchiveParent();
            requireArchivePath(archives, replacementArchiveRoot, "replacementArchiveRoot");
            requireArchivePath(archives, replacementArchiveStageRoot, "replacementArchiveStageRoot");
            requireArchivePath(archives, replacementArchiveEvidenceRoot, "replacementArchiveEvidenceRoot");
            requireEvidenceDirectoryIfPresent(replacementArchiveEvidenceRoot);
            if (!existsNoFollow(replacementArchiveRoot)) {
                throw new MigrationException("Retained Offline Upgrade Replacement Candidate Does Not Match Journal");
            }
            requireCandidateDirectory(replacementArchiveRoot, "replacementArchiveRoot");
            requireCandidateDigest(replacementArchiveRoot, expected,
                "Retained Offline Upgrade Replacement Candidate");
            if (existsNoFollow(replacementArchiveStageRoot)) {
                throw new MigrationException("Retained Offline Upgrade Replacement Candidate Has A Staging Collision");
            }
        }

        private Path requireArchiveParent() throws IOException {
            Path parent = replacementArchiveRoot.getParent();
            if (parent == null) {
                throw new MigrationException("Replacement Archive Parent Is Missing");
            }
            try {
                parent = MigrationPaths.requirePath(parent, "replacementArchiveParent");
            } catch (IllegalArgumentException exception) {
                throw new MigrationException("Replacement Archive Parent Traversal Is Not Allowed", exception);
            }
            if (!existsNoFollow(parent)) {
                MigrationPaths.requireWritableParent(parent);
                createDirectoryNoFollow(parent);
            }
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Replacement Archive Parent Must Be A Non-Symbolic-Link Directory");
            }
            return MigrationPaths.requireDirectory(parent, "replacementArchiveParent");
        }

        private static void requireArchivePath(Path parent, Path path, String field) throws IOException {
            try {
                MigrationPaths.requireNoSymlinkTraversal(parent, path);
            } catch (IllegalArgumentException exception) {
                throw new MigrationException(field + " Traversal Is Not Allowed", exception);
            }
            if (Files.isSymbolicLink(path)) {
                throw new MigrationException(field + " Cannot Be A Symbolic Link");
            }
        }

        private static Path canonicalSibling(Path parent, String name, String field) {
            Path normalizedParent = MigrationPaths.requirePath(parent, field + "Parent");
            if (name == null || name.isBlank() || name.indexOf('\u0000') >= 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException(field + " Must Be A Strict Sibling Path");
            }
            Path normalized = normalizedParent.resolve(name).normalize();
            if (!normalized.startsWith(normalizedParent)
                || !normalizedParent.equals(normalized.getParent())
                || !name.equals(normalized.getFileName().toString())) {
                throw new IllegalArgumentException(field + " Must Be A Strict Sibling Path");
            }
            return normalized;
        }

        private static boolean existsNoFollow(Path path) throws IOException {
            try {
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return true;
            } catch (NoSuchFileException exception) {
                return false;
            }
        }

        private static void createDirectoryNoFollow(Path path) throws IOException {
            Path normalized = MigrationPaths.requirePath(path, "directory");
            if (existsNoFollow(normalized)) {
                if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Directory Must Be A Non-Symbolic-Link Directory: " + normalized);
                }
                return;
            }
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new MigrationException("Directory Has No Parent: " + normalized);
            }
            createDirectoryNoFollow(parent);
            Files.createDirectory(normalized);
        }

        private static Path requireCandidateDirectory(Path path, String field) throws IOException {
            if (!existsNoFollow(path) || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException(field + " Must Be A Non-Symbolic-Link Directory");
            }
            MigrationPaths.requireNoSymlinkTree(path);
            return MigrationPaths.requireDirectory(path, field);
        }

        private static void requireEvidenceDirectoryIfPresent(Path path) throws IOException {
            if (!existsNoFollow(path)) {
                return;
            }
            requireCandidateDirectory(path, "replacementArchiveEvidenceRoot");
        }

        private static void requireCandidateDigest(Path path, String expected, String field) throws IOException {
            if (!expected.equals(TreeDigest.of(path))) {
                throw new MigrationException(field + " Does Not Match Staged Root");
            }
        }

        private static void moveFailedCandidateToEvidence(Path failed, Path evidence) throws IOException {
            requireCandidateDirectory(failed, "failedReplacementCandidate");
            if (existsNoFollow(evidence)) {
                throw new MigrationException("Replacement Candidate Evidence Collision");
            }
            Path parent = evidence.getParent();
            if (parent == null) {
                throw new MigrationException("Replacement Candidate Evidence Has No Parent");
            }
            requireArchivePath(parent, evidence, "replacementArchiveEvidenceRoot");
            moveAtomically(failed, evidence);
            requireCandidateDirectory(evidence, "replacementArchiveEvidenceRoot");
        }

        private static void copyTree(Path source, Path target) throws IOException {
            Path normalizedSource = MigrationPaths.requireDirectory(source, "sourceTree");
            Path normalizedTarget = MigrationPaths.requirePath(target, "targetTree");
            MigrationPaths.requireDistinctRoots(normalizedSource, normalizedTarget);
            if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Replacement Candidate Target Already Exists");
            }
            createDirectoryNoFollow(normalizedTarget);
            Files.walkFileTree(normalizedSource, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                    }
                    Path destination = directory.equals(normalizedSource)
                        ? normalizedTarget : MigrationPaths.resolveInside(normalizedTarget, MigrationPaths.relative(normalizedSource, directory));
                    createDirectoryNoFollow(destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                        throw new MigrationException("Only Regular Files Are Allowed In A Replacement Candidate: " + file);
                    }
                    Path destination = MigrationPaths.resolveInside(normalizedTarget, MigrationPaths.relative(normalizedSource, file));
                    Path parent = destination.getParent();
                    if (parent == null) {
                        throw new MigrationException("Replacement Candidate File Has No Parent");
                    }
                    createDirectoryNoFollow(parent);
                    AtomicFiles.copy(file, destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw new MigrationException("Cannot Copy Replacement Candidate: " + file, exception);
                }
            });
        }

        private static void forceTree(Path root) throws IOException {
            Path normalized = MigrationPaths.requireDirectory(root, "candidateRoot");
            Files.walkFileTree(normalized, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                        throw new MigrationException("Only Regular Files Are Allowed In A Candidate Root: " + file);
                    }
                    AtomicFiles.force(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw new MigrationException("Cannot Force Candidate File: " + file, exception);
                }
            });
        }

        private static void deleteCandidate(Path candidate) throws IOException {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Offline Upgrade Candidate Root Is Invalid");
            }
            MigrationPaths.requireNoSymlinkTree(candidate);
            Files.walkFileTree(candidate, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
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

        private static void moveAtomically(Path source, Path target) throws IOException {
            Path normalizedSource = MigrationPaths.requirePath(source, "moveSource");
            Path normalizedTarget = MigrationPaths.requirePath(target, "moveTarget");
            if (!existsNoFollow(normalizedSource) || Files.isSymbolicLink(normalizedSource)
                || !Files.isDirectory(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Offline Upgrade Atomic Move Source Must Be A Non-Symbolic-Link Directory");
            }
            MigrationPaths.requireNoSymlinkTree(normalizedSource);
            Path sourceParent = normalizedSource.getParent();
            Path targetParent = normalizedTarget.getParent();
            if (sourceParent == null || targetParent == null) {
                throw new MigrationException("Offline Upgrade Atomic Move Requires Parent Directories");
            }
            if (Files.isSymbolicLink(targetParent) || !Files.isDirectory(targetParent, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Offline Upgrade Atomic Move Target Parent Is Invalid");
            }
            requireArchivePath(targetParent, normalizedTarget, "moveTarget");
            if (existsNoFollow(normalizedTarget)) {
                throw new MigrationException("Offline Upgrade Atomic Move Target Already Exists");
            }
            try {
                Files.move(normalizedSource, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new MigrationException("Offline Upgrade Requires Atomic Directory Moves", exception);
            }
        }
    }

    private static final class UpgradeLocks implements AutoCloseable {
        private final FileChannel runtimeChannel;
        private final FileLock runtimeLock;
        private final FileChannel offlineChannel;
        private final FileLock offlineLock;

        private UpgradeLocks(FileChannel runtimeChannel, FileLock runtimeLock, FileChannel offlineChannel, FileLock offlineLock) {
            this.runtimeChannel = runtimeChannel;
            this.runtimeLock = runtimeLock;
            this.offlineChannel = offlineChannel;
            this.offlineLock = offlineLock;
        }

        private static UpgradeLocks open(Path controlRoot) throws IOException {
            Files.createDirectories(controlRoot);
            FileChannel runtime = FileChannel.open(controlRoot.resolve(RUNTIME_LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock runtimeLock = tryLock(runtime);
            if (runtimeLock == null) {
                runtime.close();
                throw new MigrationException("ReSync Data Root Is Live Or Runtime-Locked");
            }
            FileChannel offline = null;
            FileLock offlineLock = null;
            try {
                offline = FileChannel.open(controlRoot.resolve(OFFLINE_LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                offlineLock = tryLock(offline);
                if (offlineLock == null) {
                    throw new MigrationException("ReSync Data Root Is Already Locked For Offline Upgrade");
                }
                return new UpgradeLocks(runtime, runtimeLock, offline, offlineLock);
            } catch (IOException | RuntimeException exception) {
                if (offlineLock != null) {
                    offlineLock.release();
                }
                if (offline != null) {
                    offline.close();
                }
                runtimeLock.release();
                runtime.close();
                throw exception;
            }
        }

        private static FileLock tryLock(FileChannel channel) throws IOException {
            try {
                return channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                offlineLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                offlineChannel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                runtimeLock.release();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                runtimeChannel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
