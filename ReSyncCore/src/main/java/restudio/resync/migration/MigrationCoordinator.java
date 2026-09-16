package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

public final class MigrationCoordinator {
    private final MigrationFence fence;
    private final PersistenceParticipantRegistry participants;
    private final SnapshotService snapshots;
    private final AcceptedStagePublisher acceptedStagePublisher;
    private final String expectedPublisherContractIdentity;
    private final boolean publisherConfigured;

    public MigrationCoordinator(MigrationFence fence, PersistenceParticipantRegistry participants) {
        this(fence, participants, AcceptedStagePublisher.none(), "", false);
    }

    public MigrationCoordinator(
        MigrationFence fence,
        PersistenceParticipantRegistry participants,
        AcceptedStagePublisher acceptedStagePublisher
    ) {
        this(fence, participants, acceptedStagePublisher,
            acceptedStagePublisher == null ? "" : acceptedStagePublisher.contractIdentity(), true);
    }

    public MigrationCoordinator(
        MigrationFence fence,
        PersistenceParticipantRegistry participants,
        AcceptedStagePublisher acceptedStagePublisher,
        String expectedPublisherContractIdentity
    ) {
        this(fence, participants, acceptedStagePublisher, expectedPublisherContractIdentity, true);
    }

    private MigrationCoordinator(
        MigrationFence fence,
        PersistenceParticipantRegistry participants,
        AcceptedStagePublisher acceptedStagePublisher,
        String expectedPublisherContractIdentity,
        boolean publisherConfigured
    ) {
        this.fence = Objects.requireNonNull(fence, "fence");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.snapshots = new SnapshotService(fence);
        this.acceptedStagePublisher = Objects.requireNonNull(acceptedStagePublisher, "acceptedStagePublisher");
        this.expectedPublisherContractIdentity = expectedPublisherContractIdentity == null
            || expectedPublisherContractIdentity.isEmpty()
            ? "" : MigrationCanonical.requireText(expectedPublisherContractIdentity, "publisherContractIdentity");
        if (!this.expectedPublisherContractIdentity.equals(this.expectedPublisherContractIdentity.strip())) {
            throw new IllegalArgumentException("publisherContractIdentity Must Not Have Surrounding Whitespace");
        }
        this.publisherConfigured = publisherConfigured;
    }

    public MigrationResult execute(MigrationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!request.plan().planHash().equals(request.journal().planHash())) {
            throw new MigrationException("Migration Journal Does Not Belong To Plan");
        }
        if (!request.plan().sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())) {
            throw new MigrationException("Migration Plan Does Not Belong To Snapshot");
        }
        requireAcceptedStagePublisher(request);
        request.quarantineReport().requireAccepted(request.quarantineAcceptance());
        requireExistingJournalBinding(request);
        PreflightResult preflight = snapshots.preflight(request.sourceRoot(), request.snapshotStagingRoot(), participants, request.reservedBytes());
        preflight.requirePassed();

        Snapshot snapshot = null;
        StagedMigration staged = null;
        boolean quiesced = false;
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        try {
            participants.flushAll();
            participants.quiesceAll();
            quiesced = true;
            try {
                snapshot = snapshots.createFenced(request.sourceRoot(), request.snapshotStagingRoot(), request.snapshotMetadata(), participants);
                if (!snapshot.manifest().manifestHash().equals(request.plan().sourceManifestHash())) {
                    throw new MigrationException("Source Snapshot Manifest Does Not Match Migration Plan");
                }
                bindJournal(request);
                request.journal().transition(MigrationJournalState.PREPARED, "Verified Source Snapshot " + snapshot.manifest().manifestHash());
                request.journal().transition(MigrationJournalState.TRANSFORMING, "Transforming Full Snapshot");
                staged = request.stager().stage(
                    snapshot.root(),
                    request.migrationStagingRoot(),
                    request.plan(),
                    request.quarantineReport(),
                    request.quarantineAcceptance());
                if (!staged.planHash().equals(request.plan().planHash())) {
                    throw new MigrationException("Staged Migration Plan Hash Does Not Match");
                }
                requireStagedIntegrity(staged);
                requireQuarantineMaterialized(staged, request.quarantineReport());
                requireUntouchedSourceData(snapshot, staged, request.plan(), request.quarantineReport());
                request.journal().transition(MigrationJournalState.VALIDATING, "Validating Staged Root");
                request.validator().validate(staged);
                requireStagedIntegrity(staged);
                if (request.journal().binding().isPresent()) {
                    request.journal().bindStagedReplacementDigest(staged.contentHash());
                }
                request.journal().transition(MigrationJournalState.STAGED, "Staged Root Verified");
                publishAcceptedStage(request, snapshot, staged);
                Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition = topologyTransition(staged);
                request.activator().activate(staged);
                request.journal().transition(MigrationJournalState.ACTIVATED, "Replacement Root Activated");
                Path activeRoot = request.activator().activeRoot().orElse(staged.root());
                activeRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
                Optional<Path> archivedSource = request.activator().archivedSourceRoot();
                StagedMigration active = preserveEvidence(staged, activeRoot, archivedSource,
                    staged.planHash(), TreeDigest.of(activeRoot));
                rebindParticipants(activeRoot, topologyTransition);
                participants.healthCheckAll();
                if (request.verifier() != null) {
                    request.verifier().verify(active);
                }
                String replacementRootHash = active.contentHash();
                if (!staged.contentHash().equals(replacementRootHash)) {
                    throw new MigrationException("Activated Replacement Root Changed During Verification");
                }
                StagedMigration verified = preserveEvidence(staged, activeRoot, archivedSource,
                    staged.planHash(), replacementRootHash);
                if (request.journal().binding().isPresent()) {
                    request.journal().bindStagedReplacementDigest(replacementRootHash);
                    bindArchivedSourceDigest(request);
                    requireArchivedSource(request);
                }
                request.activationMarkerWriter().write(request.plan(), verified);
                requireActivationAuthority(request, verified);
                request.journal().transition(MigrationJournalState.COMMITTED, "Migration Committed With Replacement Authority");
                return new MigrationResult(snapshot, verified, MigrationJournalState.COMMITTED);
            } catch (AcceptedStagePublisher.Failure exception) {
                markTerminalPublicationConflict(request, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                recoverFailure(request, staged, exception);
                throw exception;
            } finally {
                if (quiesced) {
                    participants.resumeAll();
                }
            }
        } finally {
            migration.close();
        }
    }

    public JournalRecovery recover(MigrationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!request.plan().planHash().equals(request.journal().planHash())) {
            throw new MigrationException("Migration Journal Does Not Belong To Plan");
        }
        if (!request.plan().sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())) {
            throw new MigrationException("Migration Plan Does Not Belong To Snapshot");
        }
        requireAcceptedStagePublisher(request);
        request.quarantineReport().requireAccepted(request.quarantineAcceptance());
        requireExistingJournalBinding(request);
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        boolean quiesced = false;
        try {
            participants.flushAll();
            participants.quiesceAll();
            quiesced = true;
            Optional<MigrationJournalState> state = request.journal().currentState();
            if (state.isEmpty()) {
                return request.journal().recovery();
            }
            if (state.get() == MigrationJournalState.COMMITTED) {
                requireCommittedPublicationBinding(request);
                Path activeRoot = request.activator().activeRoot()
                    .orElseThrow(() -> new MigrationException("Committed Migration Active Root Is Unavailable"));
                activeRoot = MigrationPaths.requireDirectory(activeRoot, "committedRoot");
                StagedMigration staged = new StagedMigration(activeRoot, request.activator().archivedSourceRoot(), request.plan().planHash(), TreeDigest.of(activeRoot));
                requireStagedIntegrity(staged, request.journal());
                requireArchivedSource(request);
                Optional<MigrationJournal.Binding> committedBinding = request.journal().binding();
                if (committedBinding.isPresent() && !committedBinding.get().stagedReplacementDigest().equals(staged.contentHash())) {
                    throw new MigrationException("Committed Migration Replacement Digest Does Not Match Active Root");
                }
                replayCommittedPublication(request, retainedSnapshot(request), staged);
                request.validator().validate(staged);
                if (request.verifier() != null) {
                    request.verifier().verify(staged);
                }
                requireActivationAuthority(request, staged);
                participants.rebindAll(activeRoot);
                participants.healthCheckAll();
            } else if (state.get() == MigrationJournalState.PREPARED
                || state.get() == MigrationJournalState.TRANSFORMING
                || state.get() == MigrationJournalState.VALIDATING) {
                recoverPreActivation(request);
            } else if (state.get() == MigrationJournalState.STAGED) {
                recoverStaged(request);
            } else if (state.get() == MigrationJournalState.ACTIVATED) {
                continueActivated(request);
            } else if (state.get() == MigrationJournalState.FAILED) {
                if (!request.journal().terminalConflict()) {
                    recoverFailed(request);
                }
            }
            return request.journal().recovery();
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

    private void requireAcceptedStagePublisher(MigrationRequest request) throws MigrationException {
        if (request.activationMarkerWriter().binding().isPresent()
            && (!publisherConfigured || expectedPublisherContractIdentity.isEmpty())) {
            throw new MigrationException("Production Migration Requires An Accepted Stage Publisher");
        }
    }

    private void publishAcceptedStage(MigrationRequest request, Snapshot snapshot, StagedMigration staged) throws IOException {
        AssetAdoptionArtifactProducer.StageOutput stageOutput = staged.typedEvidence()
            .map(StagedMigration.TypedEvidence::stageOutput)
            .orElse(null);
        AcceptedStagePublisher.Publication publication = new AcceptedStagePublisher.Publication(
            request.journal().migrationId(),
            snapshot,
            request.journal().binding(),
            request.plan().planHash(),
            staged,
            request.quarantineReport(),
            request.quarantineAcceptance(),
            stageOutput,
            expectedPublisherContractIdentity);
        AcceptedStagePublisher.Result result;
        try {
            result = acceptedStagePublisher.publish(publication);
        } catch (AcceptedStagePublisher.Failure exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AcceptedStagePublisher.Failure("Accepted Stage Publication Failed", exception);
        }
        bindPublicationResult(request, publication, result);
    }

    private void replayCommittedPublication(MigrationRequest request, Snapshot snapshot,
                                            StagedMigration staged) throws IOException {
        Optional<MigrationJournal.PublicationBinding> retained = request.journal().binding()
            .flatMap(binding -> Optional.ofNullable(binding.publicationBinding()));
        if (retained.isEmpty()) {
            return;
        }
        AssetAdoptionArtifactProducer.StageOutput stageOutput = staged.typedEvidence()
            .map(StagedMigration.TypedEvidence::stageOutput)
            .orElse(null);
        AcceptedStagePublisher.Publication publication = new AcceptedStagePublisher.Publication(
            request.journal().migrationId(),
            snapshot,
            request.journal().binding(),
            request.plan().planHash(),
            staged,
            request.quarantineReport(),
            request.quarantineAcceptance(),
            stageOutput,
            expectedPublisherContractIdentity);
        AcceptedStagePublisher.Result result;
        try {
            result = acceptedStagePublisher.publish(publication);
        } catch (AcceptedStagePublisher.Failure exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AcceptedStagePublisher.Failure("Committed Accepted Stage Publication Replay Failed", exception);
        }
        if (result == null || !result.published() || !retained.get().equals(result.publicationBinding())) {
            throw new MigrationException("Committed Accepted Stage Publication Does Not Match Retained Binding");
        }
        bindPublicationResult(request, publication, result);
    }

    private void bindPublicationResult(
        MigrationRequest request,
        AcceptedStagePublisher.Publication publication,
        AcceptedStagePublisher.Result result
    ) throws IOException {
        if (result == null || !result.published()) {
            if (request.activationMarkerWriter().binding().isPresent() || !expectedPublisherContractIdentity.isEmpty()) {
                throw new MigrationException("Production Accepted Stage Publisher Returned No Publication Result");
            }
            return;
        }
        if (!expectedPublisherContractIdentity.isEmpty()
            && !expectedPublisherContractIdentity.equals(result.contractIdentity())) {
            throw new MigrationException("Accepted Stage Publisher Contract Identity Does Not Match Expected Contract");
        }
        String sourceManifestHash = publication.sourceSnapshot().manifest().manifestHash();
        if (!sourceManifestHash.equals(result.sourceManifestHash())) {
            throw new MigrationException("Accepted Stage Publication Source Manifest Does Not Match Snapshot");
        }
        Optional<AssetAdoptionArtifactProducer.StageOutput> stageOutput = publication.stageOutput() == null
            ? Optional.empty() : Optional.of(publication.stageOutput());
        if (stageOutput.isPresent()
            && !stageOutput.get().postStageAdmission().manifestHash().equals(result.postStageManifestHash())) {
            throw new MigrationException("Accepted Stage Publication Post-Stage Manifest Does Not Match Stage Output");
        }
        MigrationJournal.PublicationBinding next = new MigrationJournal.PublicationBinding(
            result.artifactHash(),
            result.contractIdentity(),
            result.sourceManifestHash(),
            result.postStageManifestHash());
        Optional<MigrationJournal.PublicationBinding> existing = request.journal().binding()
            .map(MigrationJournal.Binding::publicationBinding);
        if (existing.isPresent() && !existing.get().equals(next)) {
            throw new MigrationException("Accepted Stage Publication Does Not Match Existing Journal Identity");
        }
        if (request.journal().binding().isPresent()) {
            request.journal().bindPublication(next);
        }
    }

    private void recoverFailure(MigrationRequest request, StagedMigration staged, Exception primary) {
        try {
            var state = request.journal().currentState();
            if (state.isPresent() && state.get() != MigrationJournalState.COMMITTED && state.get() != MigrationJournalState.ROLLED_BACK) {
                request.journal().transition(MigrationJournalState.FAILED, message(primary));
                StagedMigration rollback = staged;
                if (rollback == null) {
                    rollback = recoverRollbackRoot(request).orElse(null);
                }
                if (rollback != null) {
                    request.rollback().rollback(rollback);
                    restoreParticipantsAfterFailure(rollback.previousRoot().orElse(null), primary);
                }
                if (originalRootIsAvailable(request, rollback)) {
                    request.journal().transition(MigrationJournalState.ROLLED_BACK, "Activation Rolled Back After Failure");
                }
            }
        } catch (Exception recoveryFailure) {
            primary.addSuppressed(recoveryFailure);
        }
    }

    private static boolean originalRootIsAvailable(MigrationRequest request, StagedMigration rollback) throws IOException {
        return Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
            || rollback != null && rollback.previousRoot().map(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).orElse(false);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static void bindJournal(MigrationRequest request) throws IOException {
        Optional<ActivationMarkerWriter.Binding> binding = request.activationMarkerWriter().binding();
        if (binding.isEmpty()) {
            return;
        }
        ActivationMarkerWriter.Binding value = binding.get();
        String archivedSourceDigest = request.journal().binding()
            .map(MigrationJournal.Binding::archivedSourceDigest)
            .orElse(MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST);
        MigrationJournal.PublicationBinding publicationBinding = request.journal().binding()
            .map(MigrationJournal.Binding::publicationBinding)
            .orElse(null);
        request.journal().bind(new MigrationJournal.Binding(
            request.plan().sourceSnapshotId(),
            request.plan().sourceManifestHash(),
            request.quarantineReport().reportHash(),
            request.quarantineAcceptance().acceptanceHash(),
            "",
            archivedSourceDigest,
            value.replacementCatalogHash(),
            value.runtimeBindingManifestHash(),
            value.runtimeBindingManifestVersion(),
            value.participantReadinessHash(),
            value.participantReadinessVersion(),
            value.authorityBundle(),
            value.authorityTrustAnchorHash(),
            value.authorityUseGrant(),
            publicationBinding));
    }

    private void requireExistingJournalBinding(MigrationRequest request) throws IOException {
        Optional<MigrationJournal.Binding> existing = request.journal().binding();
        if (existing.isEmpty()) {
            return;
        }
        MigrationJournal.Binding binding = existing.get();
        if (!binding.sourceSnapshotId().equals(request.plan().sourceSnapshotId())
            || !binding.sourceManifestHash().equals(request.plan().sourceManifestHash())
            || !binding.quarantineReportHash().equals(request.quarantineReport().reportHash())
            || !binding.acceptanceHash().equals(request.quarantineAcceptance().acceptanceHash())) {
            throw new MigrationException("Migration Journal Binding Does Not Match The Requested Transaction");
        }
        Optional<ActivationMarkerWriter.Binding> authority = request.activationMarkerWriter().binding();
        if (authority.isPresent() && (!binding.replacementCatalogHash().equals(authority.get().replacementCatalogHash())
            || !binding.runtimeBindingManifestHash().equals(authority.get().runtimeBindingManifestHash())
            || binding.runtimeBindingManifestVersion() != authority.get().runtimeBindingManifestVersion()
            || !binding.participantReadinessHash().equals(authority.get().participantReadinessHash())
            || binding.participantReadinessVersion() != authority.get().participantReadinessVersion()
            || !Objects.equals(binding.authorityBundle(), authority.get().authorityBundle())
            || !binding.authorityTrustAnchorHash().equals(authority.get().authorityTrustAnchorHash())
            || !Objects.equals(binding.authorityUseGrant(), authority.get().authorityUseGrant()))) {
            throw new MigrationException("Migration Journal Binding Does Not Match The Requested Authority");
        }
        Optional<MigrationJournal.PublicationBinding> publication = Optional.ofNullable(binding.publicationBinding());
        if (publication.isPresent()) {
            if (!publication.get().sourceManifestHash().equals(request.plan().sourceManifestHash())) {
                throw new MigrationException("Migration Journal Publication Source Manifest Does Not Match The Requested Plan");
            }
            if (!expectedPublisherContractIdentity.isEmpty()
                && !publication.get().publisherContractIdentity().equals(expectedPublisherContractIdentity)) {
                throw new MigrationException("Migration Journal Publication Contract Does Not Match The Requested Publisher");
            }
        }
    }

    private void requireCommittedPublicationBinding(MigrationRequest request) throws MigrationException {
        if (!request.activationMarkerWriter().binding().isPresent() && expectedPublisherContractIdentity.isEmpty()) {
            return;
        }
        MigrationJournal.PublicationBinding publication = request.journal().binding()
            .flatMap(binding -> Optional.ofNullable(binding.publicationBinding()))
            .orElseThrow(() -> new MigrationException("Committed Migration Publication Binding Is Missing"));
        if (!expectedPublisherContractIdentity.isEmpty()
            && !publication.publisherContractIdentity().equals(expectedPublisherContractIdentity)) {
            throw new MigrationException("Committed Migration Publication Contract Does Not Match Expected Publisher");
        }
        if (!publication.sourceManifestHash().equals(request.plan().sourceManifestHash())) {
            throw new MigrationException("Committed Migration Publication Source Manifest Does Not Match Plan");
        }
    }

    private static void requireStagedIntegrity(StagedMigration staged) throws IOException {
        requireStagedIntegrity(staged, null);
    }

    private static void requireStagedIntegrity(StagedMigration staged, MigrationJournal journal) throws IOException {
        Path root = MigrationPaths.requireDirectory(staged.root(), "stagedRoot");
        MigrationPaths.requireNoSymlinkTree(root);
        if (!staged.contentHash().equals(TreeDigest.of(root))) {
            throw new MigrationException("Staged Replacement Digest Does Not Match Root");
        }
        if (journal != null) {
            Optional<MigrationJournal.Binding> binding = journal.binding();
            if (binding.isPresent() && !binding.get().stagedReplacementDigest().isEmpty()
                && !binding.get().stagedReplacementDigest().equals(staged.contentHash())) {
                throw new MigrationException("Staged Replacement Digest Does Not Match Journal Binding");
            }
        }
    }

    private static void requireQuarantineMaterialized(StagedMigration staged, QuarantineReport report) throws IOException {
        Path root = MigrationPaths.requireDirectory(staged.root(), "stagedRoot");
        for (QuarantineRecord record : report.records()) {
            String recordId = record.recordId();
            Path source = MigrationPaths.resolveInside(root, record.sourceLocation());
            Path quarantined = MigrationPaths.resolveInside(root, ".quarantine/migration/" + recordId + "/" + record.sourceLocation());
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(quarantined, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(quarantined)) {
                throw new MigrationException("Quarantine Record Was Not Materialized: " + recordId);
            }
        }
    }

    private static void requireActivationAuthority(MigrationRequest request, StagedMigration staged) throws IOException {
        Optional<ActivationMarkerWriter.Binding> binding = request.activationMarkerWriter().binding();
        if (binding.isEmpty()) {
            binding = request.journal().binding().map(value -> new ActivationMarkerWriter.Binding(
                value.replacementCatalogHash(),
                value.runtimeBindingManifestHash(),
                value.runtimeBindingManifestVersion(),
                value.participantReadinessHash(),
                value.participantReadinessVersion(),
                value.authorityBundle()));
        }
        if (binding.isEmpty()) {
            return;
        }
        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(staged.root());
        ActivationMarkerWriter.Binding expected = binding.get();
        String expectedArchivedSourceDigest = request.journal().binding()
            .map(MigrationJournal.Binding::archivedSourceDigest)
            .filter(digest -> !digest.isEmpty())
            .orElse(MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST);
        if (!marker.sourceManifestHash().equals(request.plan().sourceManifestHash())
            || !marker.planHash().equals(request.plan().planHash())
            || !marker.replacementRootHash().equals(staged.contentHash())
            || !marker.archivedSourceDigest().equals(expectedArchivedSourceDigest)
            || !marker.replacementCatalogHash().equals(expected.replacementCatalogHash())
            || !marker.runtimeBindingManifestHash().equals(expected.runtimeBindingManifestHash())
            || marker.runtimeBindingManifestVersion() != expected.runtimeBindingManifestVersion()
            || !marker.participantReadinessHash().equals(expected.participantReadinessHash())
            || marker.participantReadinessVersion() != expected.participantReadinessVersion()
            || !Objects.equals(marker.authorityBundle(), expected.authorityBundle())
            || !marker.authorityTrustAnchorHash().equals(expected.authorityTrustAnchorHash())
            || !Objects.equals(marker.authorityUseGrant(), expected.authorityUseGrant())
            || !marker.participantReadinessComplete()) {
            throw new MigrationException("Replacement Activation Marker Does Not Bind The Committed Transaction");
        }
    }

    private void continueActivated(MigrationRequest request) throws IOException {
        continueActivated(request, null, Optional.empty());
    }

    private void continueActivated(MigrationRequest request, StagedMigration evidence,
                                   Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition)
        throws IOException {
        StagedMigration active = null;
        try {
            requireCommittedPublicationBinding(request);
            Path activeRoot = request.activator().activeRoot()
                .orElseThrow(() -> new MigrationException("Activated Migration Active Root Is Unavailable"));
            activeRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
            Optional<Path> archivedSource = request.activator().archivedSourceRoot();
            active = preserveEvidence(evidence, activeRoot, archivedSource,
                request.plan().planHash(), TreeDigest.of(activeRoot));
            requireStagedIntegrity(active, request.journal());
            requireArchivedSource(request);
            request.validator().validate(active);
            if (request.verifier() != null) {
                request.verifier().verify(active);
            }
            Optional<PersistenceParticipantRegistry.TopologyTransition> rebindTopology = topologyTransition;
            if (rebindTopology.isEmpty()) {
                MigrationJournal.Binding binding = request.journal().binding().orElseThrow(
                    () -> new MigrationException("Activated Migration Journal Binding Is Missing"));
                rebindTopology = acceptedStagePublisher.recoverTopology(
                        activeRoot, binding, request.plan().planHash())
                    .map(participants::topologyTransition);
            }
            rebindParticipants(activeRoot, rebindTopology);
            participants.healthCheckAll();
            ActivationMarkerWriter writer = request.activationMarkerWriter();
            if (writer.binding().isEmpty() && request.journal().binding().isPresent()) {
                MigrationJournal.Binding binding = request.journal().binding().orElseThrow();
                writer = ActivationMarkerWriter.replacementRoot(new ActivationMarkerWriter.Binding(
                    binding.replacementCatalogHash(),
                    binding.runtimeBindingManifestHash(),
                binding.runtimeBindingManifestVersion(),
                    binding.participantReadinessHash(),
                    binding.participantReadinessVersion(),
                    binding.authorityBundle()));
            }
            if (writer.binding().isPresent()) {
                request.journal().bindStagedReplacementDigest(active.contentHash());
                bindArchivedSourceDigest(request);
                requireArchivedSource(request);
                writer.write(request.plan(), active);
                MigrationRequest markerRequest = request.withActivationMarker(writer);
                requireActivationAuthority(markerRequest, active);
            }
            request.journal().transition(
                MigrationJournalState.COMMITTED,
                writer.binding().isPresent()
                    ? "Migration Recovered With Replacement Authority"
                    : "Migration Recovered Without Replacement Authority Binding");
        } catch (IOException | RuntimeException exception) {
            if (active != null) {
                recoverFailure(request, preserveEvidence(active, active.root(),
                    Optional.of(request.sourceRoot()), active.planHash(), active.contentHash()), exception);
            } else {
                markFailed(request, exception);
            }
            throw exception;
        }
    }

    private void recoverStaged(MigrationRequest request) throws IOException {
        StagedMigration staged = null;
        Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition = Optional.empty();
        try {
            Snapshot snapshot = retainedSnapshot(request);
            staged = recoverStagedEvidence(request, snapshot);
            requireStagedIntegrity(staged, request.journal());
            requireQuarantineMaterialized(staged, request.quarantineReport());
            request.validator().validate(staged);
            if (request.journal().binding().isPresent()
                && request.journal().binding().orElseThrow().stagedReplacementDigest().isEmpty()) {
                request.journal().bindStagedReplacementDigest(staged.contentHash());
            }
            publishAcceptedStage(request, snapshot, staged);
            topologyTransition = topologyTransition(staged);
            request.activator().activate(staged);
            request.journal().transition(MigrationJournalState.ACTIVATED, "Recovered Staged Replacement Root");
        } catch (AcceptedStagePublisher.Failure exception) {
            markTerminalPublicationConflict(request, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            recoverFailure(request, staged == null ? null : preserveEvidence(staged, staged.root(),
                Optional.of(request.sourceRoot()), staged.planHash(), staged.contentHash()), exception);
            throw exception;
        }
        continueActivated(request, staged, topologyTransition);
    }

    private StagedMigration recoverStagedEvidence(MigrationRequest request, Snapshot snapshot) throws IOException {
        Optional<MigrationJournal.Binding> binding = request.journal().binding();
        if (binding.isEmpty() || binding.get().stagedReplacementDigest().isEmpty()) {
            return restagePreActivation(request, snapshot);
        }
        StagedMigration recovered;
        try {
            recovered = recoverStagedRoot(request);
        } catch (MigrationException exception) {
            if (!acceptedStagePublisher.requiresTypedEvidenceForRecovery()
                || !"Recoverable Staged Replacement Root Is Unavailable".equals(message(exception))) {
                throw exception;
            }
            recovered = null;
        }
        if (!acceptedStagePublisher.requiresTypedEvidenceForRecovery()
            || recovered != null && recovered.typedEvidence().isPresent()) {
            return recovered;
        }
        StagedMigration reconstructed = restagePreActivation(request, snapshot);
        if (reconstructed.typedEvidence().isEmpty()) {
            throw new AcceptedStagePublisher.Failure(
                "Accepted Stage Recovery Requires Exact Typed Stage Evidence", null, true);
        }
        return reconstructed;
    }

    private static void markFailed(MigrationRequest request, Exception primary) {
        try {
            MigrationJournalState state = request.journal().currentState().orElse(null);
            if (state != MigrationJournalState.FAILED && state != MigrationJournalState.ROLLED_BACK
                && state != MigrationJournalState.COMMITTED && state != null) {
                request.journal().transition(MigrationJournalState.FAILED, message(primary));
            }
        } catch (Exception recoveryFailure) {
            primary.addSuppressed(recoveryFailure);
        }
    }

    private static void markTerminalPublicationConflict(MigrationRequest request,
                                                        AcceptedStagePublisher.Failure failure) {
        if (failure.retryable()) {
            return;
        }
        try {
            request.journal().markTerminalConflict(message(failure));
        } catch (Exception journalFailure) {
            failure.addSuppressed(journalFailure);
        }
    }

    private static StagedMigration preserveEvidence(StagedMigration template, Path root,
                                                    Optional<Path> previousRoot, String planHash,
                                                    String contentHash) {
        Optional<StagedMigration.TypedEvidence> evidence = template == null
            ? Optional.empty() : template.typedEvidence();
        return new StagedMigration(root, previousRoot, planHash, contentHash, evidence);
    }

    private void recoverFailed(MigrationRequest request) throws IOException {
        Optional<StagedMigration> staged = hasRollbackEvidence(request)
            ? recoverRollbackRoot(request) : Optional.empty();
        if (staged.isPresent()) {
            request.rollback().rollback(staged.get());
        }
        if (!originalRootIsAvailable(request, staged.orElse(null))) {
            throw new MigrationException("Failed Migration Original Root Is Unavailable");
        }
        Path activeRoot = request.activator().activeRoot().orElse(request.sourceRoot());
        restoreParticipantsAfterFailure(activeRoot, new MigrationException("Recovering Failed Migration"));
        request.journal().transition(MigrationJournalState.ROLLED_BACK, "Recovered Failed Migration To Rollback");
    }

    private static boolean hasRollbackEvidence(MigrationRequest request) throws IOException {
        if (Files.exists(request.migrationStagingRoot(), LinkOption.NOFOLLOW_LINKS)
            || request.activator().archivedSourceRoot().isPresent()) {
            return true;
        }
        return request.journal().binding()
            .map(MigrationJournal.Binding::archivedSourceDigest)
            .filter(value -> !value.isBlank())
            .isPresent();
    }

    private void recoverPreActivation(MigrationRequest request) throws IOException {
        if (request.activator().archivedSourceRoot().isPresent()) {
            rollbackPreActivation(request);
            return;
        }
        if (!Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Pre-Activation Recovery Could Not Prove The Original Source Root");
        }
        StagedMigration staged = null;
        try {
            Snapshot snapshot = retainedSnapshot(request);
            MigrationJournalState state = request.journal().currentState().orElseThrow();
            if (state == MigrationJournalState.PREPARED) {
                request.journal().transition(MigrationJournalState.TRANSFORMING, "Resuming Snapshot Transformation");
            }
            staged = restagePreActivation(request, snapshot);
            if (request.journal().currentState().orElseThrow() != MigrationJournalState.VALIDATING) {
                request.journal().transition(MigrationJournalState.VALIDATING, "Resuming Staged Root Validation");
            }
            request.validator().validate(staged);
            requireStagedIntegrity(staged);
            requireQuarantineMaterialized(staged, request.quarantineReport());
            if (request.journal().binding().isPresent()) {
                request.journal().bindStagedReplacementDigest(staged.contentHash());
            }
            request.journal().transition(MigrationJournalState.STAGED, "Recovered Staged Root Verified");
            publishAcceptedStage(request, snapshot, staged);
            Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition = topologyTransition(staged);
            request.activator().activate(staged);
            request.journal().transition(MigrationJournalState.ACTIVATED, "Recovered Replacement Root Activated");
            continueActivated(request, staged, topologyTransition);
        } catch (AcceptedStagePublisher.Failure exception) {
            markTerminalPublicationConflict(request, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            recoverPreActivationFailure(request, staged, exception);
            throw exception;
        }
    }

    private StagedMigration restagePreActivation(MigrationRequest request, Snapshot snapshot) throws IOException {
        clearMigrationStagingRoot(request.migrationStagingRoot());
        StagedMigration staged = request.stager().stage(
            snapshot.root(),
            request.migrationStagingRoot(),
            request.plan(),
            request.quarantineReport(),
            request.quarantineAcceptance());
        if (!staged.planHash().equals(request.plan().planHash())) {
            throw new MigrationException("Recovered Staged Migration Plan Hash Does Not Match");
        }
        requireStagedIntegrity(staged);
        requireQuarantineMaterialized(staged, request.quarantineReport());
        requireUntouchedSourceData(snapshot, staged, request.plan(), request.quarantineReport());
        Optional<MigrationJournal.Binding> binding = request.journal().binding();
        if (binding.isPresent() && !binding.get().stagedReplacementDigest().isEmpty()
            && !binding.get().stagedReplacementDigest().equals(staged.contentHash())) {
            throw new MigrationException("Recovered Staged Replacement Digest Does Not Match Journal Binding");
        }
        return staged;
    }

    private static Snapshot retainedSnapshot(MigrationRequest request) throws IOException {
        Path root = MigrationPaths.requireDirectory(request.snapshotStagingRoot(), "snapshotStagingRoot");
        Path manifestPath = root.resolveSibling(root.getFileName() + ".manifest");
        Path statePath = root.resolveSibling(root.getFileName() + ".state");
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        requireVerifiedSnapshotState(statePath);
        SnapshotVerification recorded = SnapshotStateStore.readVerification(statePath);
        if (!recorded.verified() || !recorded.failures().isEmpty()
            || !recorded.manifestHash().equals(manifest.manifestHash())
            || !manifest.metadata().equals(request.snapshotMetadata())
            || !manifest.manifestHash().equals(request.plan().sourceManifestHash())) {
            throw new MigrationException("Retained Migration Snapshot Is Not Bound To The Request");
        }
        ProductionSnapshotMetadataManifest.Values metadata = ProductionSnapshotMetadataManifest.read(root);
        if (!metadata.metadata().equals(manifest.metadata())
            || !metadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Migration Snapshot Metadata Does Not Match Manifest");
        }
        SnapshotVerification current = manifest.verify(root);
        if (!current.verified() || !current.failures().isEmpty()
            || !current.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Retained Migration Snapshot Tree Is Not Verified");
        }
        return new Snapshot(root, manifestPath, statePath, manifest.metadata(), manifest, SnapshotState.VERIFIED, current);
    }

    private static void requireVerifiedSnapshotState(Path statePath) throws IOException {
        Path normalized = MigrationPaths.requirePath(statePath, "statePath");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MigrationException("Retained Migration Snapshot State Is Not A Regular File");
        }
        List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !("state=" + SnapshotState.VERIFIED.name()).equals(lines.getFirst())) {
            throw new MigrationException("Retained Migration Snapshot State Is Not VERIFIED");
        }
    }

    private static void clearMigrationStagingRoot(Path stagingRoot) throws IOException {
        Path normalized = MigrationPaths.requirePath(stagingRoot, "migrationStagingRoot");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("migrationStagingRoot Must Be A Non-Symbolic-Link Directory");
        }
        MigrationPaths.requireNoSymlinkTree(normalized);
        Files.walkFileTree(normalized, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
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

    private void rollbackPreActivation(MigrationRequest request) throws IOException {
        Optional<StagedMigration> staged = recoverRollbackRoot(request);
        if (staged.isPresent()) {
            StagedMigration rollback = preserveEvidence(staged.get(), staged.get().root(),
                Optional.of(request.sourceRoot()), staged.get().planHash(), staged.get().contentHash());
            request.rollback().rollback(rollback);
        }
        if (!Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Pre-Activation Recovery Could Not Prove The Original Source Root");
        }
        request.journal().transition(MigrationJournalState.ROLLED_BACK, "Recovered Pre-Activation Migration To Rollback");
    }

    private void recoverPreActivationFailure(MigrationRequest request, StagedMigration staged, Exception primary) {
        try {
            MigrationJournalState state = request.journal().currentState().orElse(null);
            if (state == null || state == MigrationJournalState.COMMITTED || state == MigrationJournalState.ROLLED_BACK) {
                return;
            }
            request.journal().transition(MigrationJournalState.FAILED, message(primary));
            Optional<StagedMigration> rollbackRoot = staged == null ? recoverRollbackRoot(request) : Optional.of(staged);
            if (rollbackRoot.isPresent()) {
                StagedMigration rollback = preserveEvidence(rollbackRoot.get(), rollbackRoot.get().root(),
                    Optional.of(request.sourceRoot()), rollbackRoot.get().planHash(), rollbackRoot.get().contentHash());
                request.rollback().rollback(rollback);
            }
            if (!Files.isDirectory(request.sourceRoot(), LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Pre-Activation Recovery Could Not Prove The Original Source Root");
            }
            request.journal().transition(MigrationJournalState.ROLLED_BACK, "Recovered Pre-Activation Failure To Rollback");
        } catch (Exception recoveryFailure) {
            primary.addSuppressed(recoveryFailure);
        }
    }

    private StagedMigration recoverStagedRoot(MigrationRequest request) throws IOException {
        Path candidate = MigrationPaths.requirePath(request.migrationStagingRoot(), "migrationStagingRoot");
        Optional<Path> activeRoot = request.activator().activeRoot();
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireNoSymlinkTree(candidate);
            Optional<Path> previous = activeRoot.filter(root -> !root.toAbsolutePath().normalize().equals(candidate));
            return new StagedMigration(candidate, previous, request.plan().planHash(), TreeDigest.of(candidate));
        }
        if (activeRoot.isPresent() && Files.isDirectory(activeRoot.get(), LinkOption.NOFOLLOW_LINKS)) {
            Path active = MigrationPaths.requireDirectory(activeRoot.get(), "activeRoot");
            String digest = TreeDigest.of(active);
            Optional<MigrationJournal.Binding> binding = request.journal().binding();
            if (binding.isPresent() && binding.get().stagedReplacementDigest().equals(digest)) {
                return new StagedMigration(active, request.activator().archivedSourceRoot(), request.plan().planHash(), digest);
            }
        }
        throw new MigrationException("Recoverable Staged Replacement Root Is Unavailable");
    }

    private Optional<StagedMigration> recoverRollbackRoot(MigrationRequest request) throws IOException {
        Path candidate = MigrationPaths.requirePath(request.migrationStagingRoot(), "migrationStagingRoot");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Recoverable Migration Staging Root Is Invalid");
            }
            Optional<Path> activeRoot = request.activator().activeRoot();
            Optional<Path> previous = activeRoot.filter(root -> !root.toAbsolutePath().normalize().equals(candidate));
            return Optional.of(new StagedMigration(candidate, previous, request.plan().planHash(), TreeDigest.of(candidate)));
        }
        Optional<Path> activeRoot = request.activator().activeRoot();
        if (activeRoot.isPresent() && Files.isDirectory(activeRoot.get(), LinkOption.NOFOLLOW_LINKS)) {
            Path active = MigrationPaths.requireDirectory(activeRoot.get(), "activeRoot");
            Optional<MigrationJournal.Binding> binding = request.journal().binding();
            String activeDigest = TreeDigest.of(active);
            if (binding.isPresent() && !binding.get().stagedReplacementDigest().isEmpty()
                && binding.get().stagedReplacementDigest().equals(activeDigest)) {
                return Optional.of(new StagedMigration(active, request.activator().archivedSourceRoot(), request.plan().planHash(), activeDigest));
            }
        }
        Optional<Path> archivedSource = request.activator().archivedSourceRoot();
        if (archivedSource.isPresent() && Files.isDirectory(archivedSource.get(), LinkOption.NOFOLLOW_LINKS)) {
            requireArchivedSource(request);
            throw new MigrationException("Recoverable Replacement Root Is Unavailable For Rollback");
        }
        return Optional.empty();
    }

    private static void bindArchivedSourceDigest(MigrationRequest request) throws IOException {
        Optional<Path> archivedSource = request.activator().archivedSourceRoot();
        if (archivedSource.isEmpty()) {
            return;
        }
        request.journal().bindArchivedSourceDigest(TreeDigest.of(archivedSource.orElseThrow()));
    }

    private static void requireArchivedSource(MigrationRequest request) throws IOException {
        Optional<MigrationJournal.Binding> binding = request.journal().binding();
        if (binding.isEmpty() || binding.get().archivedSourceDigest().isEmpty()
            || binding.get().archivedSourceDigest().equals(MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST)) {
            return;
        }
        Path archivedSource = request.activator().archivedSourceRoot()
            .orElseThrow(() -> new MigrationException("Archived Source Root Is Unavailable"));
        if (!binding.get().archivedSourceDigest().equals(TreeDigest.of(archivedSource))) {
            throw new MigrationException("Archived Source Digest Does Not Match Journal Binding");
        }
    }

    private static void requireUntouchedSourceData(Snapshot snapshot, StagedMigration staged, MigrationPlan plan, QuarantineReport report) throws IOException {
        Path sourceRoot = MigrationPaths.requireDirectory(snapshot.root(), "snapshotRoot");
        Path stagedRoot = MigrationPaths.requireDirectory(staged.root(), "stagedRoot");
        for (SnapshotManifest.Entry entry : snapshot.manifest().entries()) {
            if (isAffected(entry.relativePath(), plan.operations()) || isQuarantined(entry.relativePath(), report)) {
                continue;
            }
            Path source = MigrationPaths.resolveInside(sourceRoot, entry.relativePath());
            Path target = MigrationPaths.resolveInside(stagedRoot, entry.relativePath());
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Untouched Source File Was Not Preserved: " + entry.relativePath());
            }
            if (Files.size(source) != Files.size(target) || Files.mismatch(source, target) != -1) {
                throw new MigrationException("Untouched Source File Was Changed: " + entry.relativePath());
            }
        }
        for (String directory : snapshot.manifest().directories()) {
            if (isAffected(directory, plan.operations())) {
                continue;
            }
            Path target = MigrationPaths.resolveInside(stagedRoot, directory);
            if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Untouched Source Directory Was Not Preserved: " + directory);
            }
        }
    }

    private static boolean isQuarantined(String path, QuarantineReport report) {
        return report.records().stream().anyMatch(record -> record.sourceLocation().equals(path));
    }

    private void restoreParticipantsAfterFailure(Path previousRoot, Exception primary) throws IOException {
        if (previousRoot == null) {
            return;
        }
        PersistenceRebindStatus status = participants.rebindStatus();
        if (status.state() == PersistenceRebindStatus.State.INCONSISTENT) {
            throw new MigrationException("Persistence Participant Roots Are Inconsistent After Activation Failure");
        }
        if (status.state() != PersistenceRebindStatus.State.COMMITTED
            || status.activeRoot().map(previousRoot::equals).orElse(false)) {
            return;
        }
        try {
            participants.rebindAll(previousRoot);
            participants.healthCheckAll();
            if (!participants.rebindStatus().activeRoot().map(previousRoot::equals).orElse(false)) {
                throw new MigrationException("Persistence Participants Did Not Restore Previous Root");
            }
        } catch (IOException | RuntimeException recoveryFailure) {
            primary.addSuppressed(recoveryFailure);
            throw recoveryFailure;
        }
    }

    private Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition(StagedMigration staged)
        throws IOException {
        Optional<VerifiedSnapshotAdmission> postStageAdmission = staged.typedEvidence()
            .map(StagedMigration.TypedEvidence::stageOutput)
            .map(AssetAdoptionArtifactProducer.StageOutput::postStageAdmission);
        if (postStageAdmission.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(participants.topologyTransition(postStageAdmission.orElseThrow()));
    }

    private void rebindParticipants(
        Path activeRoot, Optional<PersistenceParticipantRegistry.TopologyTransition> topologyTransition
    ) throws IOException {
        if (topologyTransition.isPresent()) {
            participants.rebindAll(activeRoot, topologyTransition.orElseThrow());
            return;
        }
        participants.rebindAll(activeRoot);
    }

    private static boolean isAffected(String path, List<MigrationOperation> operations) {
        return operations.stream().anyMatch(operation -> affects(path, operation.sourcePath()) || affects(path, operation.targetPath()));
    }

    private static boolean affects(String path, String operationPath) {
        return !operationPath.isEmpty()
            && (path.equals(operationPath)
                || path.startsWith(operationPath + "/")
                || operationPath.startsWith(path + "/"));
    }
}
