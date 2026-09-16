package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

@FunctionalInterface
public interface AcceptedStagePublisher {
    Result publish(Publication publication) throws IOException;

    default String contractIdentity() {
        return "";
    }

    default boolean requiresTypedEvidenceForRecovery() {
        return false;
    }

    default Optional<RecoveredTopology> recoverTopology(
        Path activeRoot, MigrationJournal.Binding binding, String planHash
    ) throws IOException {
        return Optional.empty();
    }

    static AcceptedStagePublisher none() {
        return publication -> Result.none();
    }

    static AcceptedStagePublisher production(Path coordinationRoot) {
        return new ProductionAcceptedStagePublisher(coordinationRoot);
    }

    static AcceptedStagePublisher custom(Consumer<Publication> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return publication -> {
            consumer.accept(publication);
            return Result.none();
        };
    }

    final class Publication {
        private final String migrationId;
        private final Snapshot sourceSnapshot;
        private final Optional<MigrationJournal.Binding> journalBinding;
        private final String planHash;
        private final StagedMigration staged;
        private final QuarantineReport quarantineReport;
        private final QuarantineAcceptance quarantineAcceptance;
        private final AssetAdoptionArtifactProducer.StageOutput stageOutput;
        private final String expectedPublisherContractIdentity;

        public Publication(
            String migrationId,
            Snapshot sourceSnapshot,
            Optional<MigrationJournal.Binding> journalBinding,
            String planHash,
            StagedMigration staged,
            QuarantineReport quarantineReport,
            QuarantineAcceptance quarantineAcceptance
        ) throws IOException {
            this(migrationId, sourceSnapshot, journalBinding, planHash, staged, quarantineReport,
                quarantineAcceptance, null, "");
        }

        public Publication(
            String migrationId,
            Snapshot sourceSnapshot,
            Optional<MigrationJournal.Binding> journalBinding,
            String planHash,
            StagedMigration staged,
            QuarantineReport quarantineReport,
            QuarantineAcceptance quarantineAcceptance,
            AssetAdoptionArtifactProducer.StageOutput stageOutput
        ) throws IOException {
            this(migrationId, sourceSnapshot, journalBinding, planHash, staged, quarantineReport,
                quarantineAcceptance, stageOutput, "");
        }

        public Publication(
            String migrationId,
            Snapshot sourceSnapshot,
            Optional<MigrationJournal.Binding> journalBinding,
            String planHash,
            StagedMigration staged,
            QuarantineReport quarantineReport,
            QuarantineAcceptance quarantineAcceptance,
            AssetAdoptionArtifactProducer.StageOutput stageOutput,
            String expectedPublisherContractIdentity
        ) throws IOException {
            this.migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
            this.sourceSnapshot = Objects.requireNonNull(sourceSnapshot, "sourceSnapshot");
            if (!sourceSnapshot.verified()) {
                throw new MigrationException("Accepted Stage Source Snapshot Must Be Verified");
            }
            this.journalBinding = journalBinding == null ? Optional.empty() : journalBinding;
            this.planHash = MigrationCanonical.requireDigest(planHash, "planHash");
            this.staged = Objects.requireNonNull(staged, "staged");
            if (!this.planHash.equals(staged.planHash())) {
                throw new MigrationException("Accepted Stage Plan Hash Does Not Match Staged Evidence");
            }
            this.quarantineReport = Objects.requireNonNull(quarantineReport, "quarantineReport");
            this.quarantineAcceptance = Objects.requireNonNull(quarantineAcceptance, "quarantineAcceptance");
            this.quarantineReport.requireAccepted(this.quarantineAcceptance);
            this.expectedPublisherContractIdentity = expectedPublisherContractIdentity == null
                || expectedPublisherContractIdentity.isEmpty()
                ? "" : MigrationCanonical.requireText(expectedPublisherContractIdentity, "publisherContractIdentity");
            if (!this.expectedPublisherContractIdentity.equals(this.expectedPublisherContractIdentity.strip())) {
                throw new IllegalArgumentException("publisherContractIdentity Must Not Have Surrounding Whitespace");
            }
            if (!sourceSnapshot.verification().manifestHash().equals(sourceSnapshot.manifest().manifestHash())) {
                throw new MigrationException("Accepted Stage Source Verification Does Not Match Snapshot Manifest");
            }
            if (stageOutput != null) {
                if (!stageOutput.planHash().equals(planHash)
                    || !stageOutput.sourceSnapshotId().equals(sourceSnapshot.metadata().snapshotId())
                    || !stageOutput.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
                    || !stageOutput.quarantineReport().reportHash().equals(quarantineReport.reportHash())) {
                    throw new MigrationException("Accepted Stage Output Does Not Match Publication");
                }
                if (stageOutput.acceptance() != null && quarantineAcceptance != null
                    && !stageOutput.acceptance().acceptanceHash().equals(quarantineAcceptance.acceptanceHash())) {
                    throw new MigrationException("Accepted Stage Output Acceptance Does Not Match Publication");
                }
            }
            this.stageOutput = stageOutput;
            for (MigrationJournal.Binding binding : this.journalBinding.stream().toList()) {
                if (!binding.sourceSnapshotId().equals(sourceSnapshot.metadata().snapshotId())
                    || !binding.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
                    || !binding.quarantineReportHash().equals(quarantineReport.reportHash())
                    || !binding.acceptanceHash().equals(quarantineAcceptance.acceptanceHash())) {
                    throw new MigrationException("Accepted Stage Source Binding Does Not Match Snapshot");
                }
                if (!binding.stagedReplacementDigest().isEmpty()
                    && !binding.stagedReplacementDigest().equals(staged.contentHash())) {
                    throw new MigrationException("Accepted Stage Binding Does Not Match Staged Evidence");
                }
                if (binding.publicationBinding() != null) {
                    MigrationJournal.PublicationBinding publicationBinding = binding.publicationBinding();
                    if (!this.expectedPublisherContractIdentity.isEmpty()
                        && !this.expectedPublisherContractIdentity.equals(publicationBinding.publisherContractIdentity())) {
                        throw new MigrationException("Accepted Stage Publication Contract Does Not Match Existing Binding");
                    }
                    if (!publicationBinding.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())) {
                        throw new MigrationException("Accepted Stage Publication Source Manifest Does Not Match Snapshot");
                    }
                    if (stageOutput != null
                        && !publicationBinding.postStageManifestHash().equals(stageOutput.postStageAdmission().manifestHash())) {
                        throw new MigrationException("Accepted Stage Publication Post-Stage Manifest Does Not Match Stage Output");
                    }
                }
            }
        }

        public String migrationId() {
            return migrationId;
        }

        public Snapshot sourceSnapshot() {
            return sourceSnapshot;
        }

        public Optional<MigrationJournal.Binding> journalBinding() {
            return journalBinding;
        }

        public String planHash() {
            return planHash;
        }

        public StagedMigration staged() {
            return staged;
        }

        public QuarantineReport quarantineReport() {
            return quarantineReport;
        }

        public QuarantineAcceptance quarantineAcceptance() {
            return quarantineAcceptance;
        }

        public AssetAdoptionArtifactProducer.StageOutput stageOutput() {
            return stageOutput;
        }

        public String expectedPublisherContractIdentity() {
            return expectedPublisherContractIdentity;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Publication other)) {
                return false;
            }
            return migrationId.equals(other.migrationId)
                && sourceSnapshot.equals(other.sourceSnapshot)
                && journalBinding.equals(other.journalBinding)
                && planHash.equals(other.planHash)
                && staged.equals(other.staged)
                && quarantineReport.equals(other.quarantineReport)
                && quarantineAcceptance.equals(other.quarantineAcceptance)
                && Objects.equals(stageOutput, other.stageOutput)
                && expectedPublisherContractIdentity.equals(other.expectedPublisherContractIdentity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(migrationId, sourceSnapshot, journalBinding, planHash, staged,
                quarantineReport, quarantineAcceptance, stageOutput, expectedPublisherContractIdentity);
        }

        @Override
        public String toString() {
            return "Publication[migrationId=" + migrationId + ", sourceSnapshot=" + sourceSnapshot
                + ", journalBinding=" + journalBinding + ", planHash=" + planHash + ", staged=" + staged
                + ", quarantineReport=" + quarantineReport + ", quarantineAcceptance=" + quarantineAcceptance
                + ", stageOutput=" + stageOutput + ", expectedPublisherContractIdentity="
                + expectedPublisherContractIdentity + "]";
        }

        public AssetAdoptionArtifactProducer.StageOutput requireStageOutput() throws MigrationException {
            if (stageOutput == null) {
                throw new MigrationException("Production Accepted Stage Publication Has No Exact Stage Output");
            }
            return stageOutput;
        }

        public String sourceManifestHash() {
            return sourceSnapshot.manifest().manifestHash();
        }

        public String publisherContractIdentity() {
            return expectedPublisherContractIdentity;
        }

        public String postStageManifestHash() {
            return stageOutput == null ? "" : stageOutput.postStageAdmission().manifestHash();
        }

        public Optional<String> optionalPostStageManifestHash() {
            return stageOutput == null
                ? Optional.empty() : Optional.of(stageOutput.postStageAdmission().manifestHash());
        }

        public Optional<AssetAdoptionArtifactProducer.StageOutput> typedStageOutput() {
            return Optional.ofNullable(stageOutput);
        }

        public Optional<MigrationJournal.PublicationBinding> existingPublicationBinding() {
            return journalBinding.flatMap(binding -> Optional.ofNullable(binding.publicationBinding()));
        }
    }

    record Result(
        String contractIdentity,
        String artifactPath,
        String artifactHash,
        String sourceManifestHash,
        String postStageManifestHash
    ) {
        public Result {
            contractIdentity = optionalText(contractIdentity, "contractIdentity");
            artifactPath = optionalText(artifactPath, "artifactPath");
            artifactHash = optionalDigest(artifactHash, "artifactHash");
            sourceManifestHash = optionalDigest(sourceManifestHash, "sourceManifestHash");
            postStageManifestHash = optionalDigest(postStageManifestHash, "postStageManifestHash");
            boolean empty = artifactPath.isEmpty() && artifactHash.isEmpty() && sourceManifestHash.isEmpty()
                && postStageManifestHash.isEmpty();
            if (!empty && (contractIdentity.isEmpty() || artifactPath.isEmpty() || artifactHash.isEmpty()
                || sourceManifestHash.isEmpty() || postStageManifestHash.isEmpty())) {
                throw new IllegalArgumentException("Accepted Stage Publication Result Is Incomplete");
            }
        }

        private static String optionalText(String value, String field) {
            return value == null || value.isEmpty() ? "" : MigrationCanonical.requireText(value, field);
        }

        private static String optionalDigest(String value, String field) {
            return value == null || value.isEmpty() ? "" : MigrationCanonical.requireDigest(value, field);
        }

        public static Result none() {
            return new Result("", "", "", "", "");
        }

        public boolean published() {
            return !artifactHash.isEmpty();
        }

        public MigrationJournal.PublicationBinding publicationBinding() {
            if (!published()) {
                throw new IllegalStateException("Accepted Stage Publication Result Is Empty");
            }
            return new MigrationJournal.PublicationBinding(artifactHash, contractIdentity,
                sourceManifestHash, postStageManifestHash);
        }
    }

    record RecoveredTopology(SnapshotManifest manifest, Path activeRoot, String activeDigest,
                             Set<String> excludedPaths) {
        public RecoveredTopology {
            manifest = Objects.requireNonNull(manifest, "manifest");
            activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
            activeDigest = MigrationCanonical.requireDigest(activeDigest, "activeDigest");
            excludedPaths = Set.copyOf(excludedPaths);
        }

        public void requireCurrent(Path root) throws IOException {
            Path current = MigrationPaths.requireDirectory(root, "activeRoot");
            if (!current.equals(activeRoot) || !TreeDigest.of(current).equals(activeDigest)) {
                throw new MigrationException("Recovered Post-Stage Persistence Topology Does Not Match Active Root");
            }
        }
    }

    final class Failure extends IOException {
        private final boolean retryable;

        public Failure(String message, Throwable cause) {
            this(message, cause, true);
        }

        public Failure(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
