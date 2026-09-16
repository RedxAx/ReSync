package restudio.resync.migration;

import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record StagedMigration(Path root, Optional<Path> previousRoot, String planHash, String contentHash,
                              Optional<TypedEvidence> typedEvidence) {
    public StagedMigration(Path root, Optional<Path> previousRoot, String planHash, String contentHash) {
        this(root, previousRoot, planHash, contentHash, Optional.empty());
    }

    public StagedMigration(Path root, Optional<Path> previousRoot, String planHash, String contentHash,
                           TypedEvidence typedEvidence) {
        this(root, previousRoot, planHash, contentHash, Optional.of(Objects.requireNonNull(typedEvidence, "typedEvidence")));
    }

    public StagedMigration {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        previousRoot = previousRoot == null ? Optional.empty() : previousRoot.map(value -> value.toAbsolutePath().normalize());
        planHash = MigrationCanonical.requireDigest(planHash, "planHash");
        contentHash = MigrationCanonical.requireDigest(contentHash, "contentHash");
        typedEvidence = typedEvidence == null ? Optional.empty() : typedEvidence.map(Objects::requireNonNull);
        String canonicalPlanHash = planHash;
        typedEvidence.ifPresent(value -> value.requireMatches(canonicalPlanHash));
    }

    public StagedMigration attachTypedEvidence(TypedEvidence evidence) {
        return new StagedMigration(root, previousRoot, planHash, contentHash, evidence);
    }

    public StagedMigration withRoots(Path nextRoot, Optional<Path> nextPreviousRoot) {
        return typedEvidence.map(evidence -> new StagedMigration(nextRoot, nextPreviousRoot, planHash,
                contentHash, evidence))
            .orElseGet(() -> new StagedMigration(nextRoot, nextPreviousRoot, planHash, contentHash));
    }

    public Optional<AssetAdoptionArtifactProducer.StageOutput> stageOutput() {
        return typedEvidence.map(TypedEvidence::stageOutput);
    }

    public record TypedEvidence(VerifiedSnapshotAdmission sourceAdmission,
                                AssetAdoptionArtifactProducer.StageOutput stageOutput) {
        public TypedEvidence {
            sourceAdmission = Objects.requireNonNull(sourceAdmission, "sourceAdmission");
            stageOutput = Objects.requireNonNull(stageOutput, "stageOutput");
            if (!stageOutput.sourceSnapshotId().equals(sourceAdmission.metadata().snapshotId())
                || !stageOutput.sourceManifestHash().equals(sourceAdmission.manifestHash())) {
                throw new IllegalArgumentException("Typed Stage Evidence Does Not Match Its Source Admission");
            }
            MigrationPaths.requireDistinctRoots(sourceAdmission.root(), stageOutput.postStageAdmission().root());
        }

        private void requireMatches(String planHash) {
            if (!stageOutput.planHash().equals(planHash)) {
                throw new IllegalArgumentException("Typed Stage Evidence Does Not Match Its Plan");
            }
        }
    }
}
