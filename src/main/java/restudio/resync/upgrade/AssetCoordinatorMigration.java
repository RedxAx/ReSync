package restudio.resync.upgrade;

import com.google.gson.Gson;
import restudio.resync.migration.FreshRootProvenance;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.VerifiedSnapshotAdmission;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AdoptedAsset;
import restudio.resync.storage.AssetTransactionCoordinator.AdoptionBinding;
import restudio.resync.storage.AssetTransactionCoordinator.AdoptionEvidence;
import restudio.resync.storage.AssetTransactionCoordinator.AdoptionInventory;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.AssetMutationId;
import restudio.resync.storage.AssetTransactionCoordinator.BlockedAdoption;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.LegacyDeletionProvenance;
import restudio.resync.storage.AssetTransactionCoordinator.Live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AssetCoordinatorMigration {
    public static final String ARTIFACT_RELATIVE_PATH = AssetAdoptionArtifactProducer.ARTIFACT_RELATIVE_PATH;
    public static final String FORMAT = AssetAdoptionArtifactProducer.FORMAT;

    private AssetCoordinatorMigration() {
    }

    public static Result prepare(Path coordinationRoot, VerifiedSnapshotAdmission verifiedPostStageSnapshot)
        throws IOException {
        throw new MigrationException("Asset Adoption Requires A Typed Verified Stage Output");
    }

    public static Result prepare(Path coordinationRoot, VerifiedSnapshotAdmission verifiedSourceSnapshot,
                                 AssetAdoptionArtifactProducer.StageOutput stageOutput) throws IOException {
        return portable(AssetAdoptionArtifactProducer.produce(coordinationRoot,
            Objects.requireNonNull(verifiedSourceSnapshot, "verifiedSourceSnapshot"),
            Objects.requireNonNull(stageOutput, "stageOutput")));
    }

    public static Result load(Path coordinationRoot) throws IOException {
        return portable(AssetAdoptionArtifactProducer.load(coordinationRoot));
    }

    public static Result prepareEmpty(Path coordinationRoot, FreshRootProvenance freshRootProvenance) throws IOException {
        return portable(AssetAdoptionArtifactProducer.produceEmpty(coordinationRoot,
            Objects.requireNonNull(freshRootProvenance, "freshRootProvenance")));
    }

    public static Result prepareEmptyUnconsumed(Path coordinationRoot, FreshRootProvenance freshRootProvenance)
        throws IOException {
        return portable(AssetAdoptionArtifactProducer.produceEmptyUnconsumed(coordinationRoot,
            Objects.requireNonNull(freshRootProvenance, "freshRootProvenance")));
    }

    private static Result portable(AssetAdoptionArtifactProducer.Result source) throws IOException {
        validatePortable(source);
        List<AdoptedAsset> assets = new ArrayList<>();
        for (AssetAdoptionArtifactProducer.Asset asset : source.inventory().assets()) {
            AssetKey key = new AssetKey(asset.type(), asset.id());
            AssetMutationId mutation = new AssetMutationId(asset.mutationId());
            if (asset.state().kind().equals("LIVE")) {
                assets.add(new AdoptedAsset(key, asset.path(), new Live(asset.state().revision(), asset.state().hash()),
                    mutation, asset.content(), null));
                continue;
            }
            AssetAdoptionArtifactProducer.LegacyDeletion deletion = asset.deletion();
            if (deletion == null) {
                throw new MigrationException("Portable Deleted Adoption Is Missing Its Exact Provenance: "
                    + asset.canonicalKey());
            }
            LegacyDeletionProvenance provenance = new LegacyDeletionProvenance(deletion.priorPayloadHash(),
                deletion.originalLogicalPath(), deletion.canonicalFuturePath(), deletion.tombstoneEvidenceHash(),
                deletion.owner(), deletion.evidenceKey(), deletion.revision(), new AssetMutationId(deletion.mutationId()));
            assets.add(new AdoptedAsset(key, asset.path(), new Deleted(asset.state().revision(), asset.state().hash()),
                mutation, asset.content(), provenance));
        }
        List<BlockedAdoption> blocked = new ArrayList<>();
        for (AssetAdoptionArtifactProducer.BlockedState value : source.inventory().blocked()) {
            if (value.mutationId().isBlank()) {
                throw new MigrationException("Portable Blocked Adoption Is Missing Its Exact Mutation Identity: "
                    + value.evidenceKey());
            }
            AssetKey candidate = value.candidateType() == null && value.candidateId() == null
                ? null : new AssetKey(value.candidateType(), value.candidateId());
            blocked.add(new BlockedAdoption(value.owner(), value.evidenceKey(), value.evidenceHash(), value.revision(),
                new AssetMutationId(value.mutationId()), candidate, value.originalLogicalPath(),
                value.canonicalFuturePath(), value.reason()));
        }
        List<Evidence> evidence = source.evidence().stream().map(value -> new Evidence(value.originalPath(),
            value.evidencePath(), value.hash(), value.size(), value.owner())).toList();
        for (Evidence item : evidence) {
            if (!item.originalPath().startsWith(".tombstones/")) {
                continue;
            }
            String[] segments = item.originalPath().split("/", -1);
            if (segments.length != 3 || segments[2].isBlank() || !segments[2].endsWith(".json")) {
                throw new MigrationException("Portable Asset Adoption Tombstone Path Is Invalid: " + item.originalPath());
            }
            List<AssetAdoptionArtifactProducer.Asset> deleted = source.inventory().assets().stream()
                .filter(asset -> asset.deletion() != null
                    && normalizeEvidenceKey(asset.deletion().evidenceKey()).equals(item.originalPath()))
                .toList();
            if (deleted.isEmpty()) {
                boolean blockedLineage = source.inventory().blocked().stream()
                    .anyMatch(value -> normalizeEvidenceKey(value.evidenceKey()).equals(item.originalPath()));
                if (!blockedLineage) {
                    throw new MigrationException("Portable Asset Adoption Tombstone Has No Exact Typed Lineage: "
                        + item.originalPath());
                }
                continue;
            }
            if (deleted.size() != 1) {
                throw new MigrationException("Portable Asset Adoption Tombstone Has Ambiguous Deleted Lineage: "
                    + item.originalPath());
            }
            AssetAdoptionArtifactProducer.Asset asset = deleted.getFirst();
            AssetAdoptionArtifactProducer.LegacyDeletion deletion = asset.deletion();
            if (!asset.state().kind().equals("DELETED") || !item.owner().equals(deletion.owner())
                || !item.hash().equals(asset.state().hash()) || !item.hash().equals(deletion.tombstoneEvidenceHash())
                || item.size() < 0L || deletion.revision() != asset.state().revision()
                || !deletion.mutationId().equals(asset.mutationId())) {
                throw new MigrationException("Portable Asset Adoption Tombstone Bytes Or Lineage Do Not Match Its Deleted Asset: "
                    + item.originalPath());
            }
            AssetKey tombstoneKey = new AssetKey(deletion.auxiliaryKey().type(), deletion.auxiliaryKey().id());
            AssetMutationId mutation = new AssetMutationId(asset.mutationId());
            long revision = asset.state().revision();
            byte[] content = readDurableEvidence(source.artifactPath().getParent(), item);
            AdoptedAsset auxiliary = new AdoptedAsset(tombstoneKey, Path.of(item.originalPath()),
                new Live(revision, item.hash()), mutation, content, null);
            AdoptedAsset existing = assets.stream().filter(value -> value.key().equals(tombstoneKey)).findFirst().orElse(null);
            if (existing == null) {
                if (assets.stream().anyMatch(value -> value.path().normalize().equals(auxiliary.path().normalize()))) {
                    throw new MigrationException("Portable Asset Adoption Tombstone Path Conflicts With Another Asset: "
                        + item.originalPath());
                }
                assets.add(auxiliary);
            } else if (!existing.path().normalize().equals(auxiliary.path().normalize())
                || !existing.state().equals(auxiliary.state()) || !existing.mutationId().equals(auxiliary.mutationId())
                || !Arrays.equals(existing.content(), auxiliary.content())) {
                throw new MigrationException("Portable Asset Adoption Tombstone Conflicts With Its Typed Asset: "
                    + item.originalPath());
            }
        }
        List<AdoptionEvidence> adoptionEvidence = evidence.stream()
            .map(value -> new AdoptionEvidence(value.originalPath(), value.hash(), value.size())).toList();
        AdoptionInventory inventory = new AdoptionInventory(source.inventory().provenance(), source.inventory().projectJson(),
            assets, blocked, adoptionEvidence);
        return new Result(source.artifactPath(), source.artifactHash(), source.postStageManifestHash(), inventory, evidence);
    }

    private static void validatePortable(AssetAdoptionArtifactProducer.Result source) throws MigrationException {
        Objects.requireNonNull(source, "source");
        try {
            new QuarantineReport(source.quarantine()).requireAccepted(source.acceptance());
        } catch (IOException | RuntimeException exception) {
            throw new MigrationException("Portable Asset Adoption Quarantine Is Not Accepted", exception);
        }
        requirePortableAdmission(source.sourceManifest(), source.sourceManifestHash(), source.sourceState(), "source");
        requirePortableAdmission(source.postStageManifest(), source.postStageManifestHash(), source.postStageState(),
            "post-stage");
        HashSet<String> evidencePaths = new HashSet<>();
        for (AssetAdoptionArtifactProducer.Evidence evidence : source.evidence()) {
            if (!evidencePaths.add(evidence.evidencePath())
                || evidence.originalPath().startsWith("assets/")
                || !evidence.evidencePath().startsWith("evidence/adoption-v1/")) {
                throw new MigrationException("Portable Asset Adoption Evidence Is Outside Its Durable Namespace");
            }
        }
    }

    private static void requirePortableAdmission(String encodedManifest, String manifestHash, String state, String label)
        throws MigrationException {
        if (encodedManifest == null || encodedManifest.isBlank() || state == null || state.isBlank()
            || !state.equals("state=VERIFIED\nverified=true\nmanifest-hash=" + manifestHash + "\nfailures=0\n")) {
            throw new MigrationException("Portable Asset Adoption " + label + " Admission Is Not Verified");
        }
        byte[] manifest;
        try {
            manifest = Base64.getUrlDecoder().decode(encodedManifest);
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Portable Asset Adoption " + label + " Manifest Is Invalid", exception);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(manifest).equals(encodedManifest)
            || !sha256(manifest).equals(manifestHash)) {
            throw new MigrationException("Portable Asset Adoption " + label + " Manifest Hash Does Not Match");
        }
    }

    public record Result(Path artifactPath, String artifactHash, String manifestHash, AdoptionInventory inventory,
                         List<Evidence> evidence) {
        public Result {
            artifactPath = MigrationPaths.requirePath(artifactPath, "artifactPath");
            requireDigestUnchecked(artifactHash, "artifactHash");
            requireDigestUnchecked(manifestHash, "manifestHash");
            inventory = Objects.requireNonNull(inventory, "inventory");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        }

        public AssetTransactionCoordinator openOrAdopt(Path assetsRoot, Gson gson) throws IOException {
            Path assets = MigrationPaths.requirePath(assetsRoot, "assetsRoot");
            verifyDurableEvidence(artifactPath.getParent(), evidence);
            if (!Files.exists(assets, LinkOption.NOFOLLOW_LINKS)) {
                if (!inventory.assets().isEmpty() || !inventory.blocked().isEmpty() || !evidence.isEmpty()
                    || !inventory.projectJson().equals("{}")) {
                    throw new MigrationException("Non-Empty Asset Adoption Authority Requires Its Verified Assets Root");
                }
                MigrationPaths.requireWritableParent(assets);
                Files.createDirectory(assets);
            }
            assets = MigrationPaths.requireDirectory(assets, "assetsRoot");
            return AssetTransactionCoordinator.openOrAdopt(assets, Objects.requireNonNull(gson, "gson"), inventory,
                new AdoptionBinding(artifactHash, manifestHash), evidence.isEmpty() ? null : this::retireEvidence);
        }

        public Optional<FreshRootAuthority> freshRootAuthority(Path coordinationRoot, Path activeRoot) throws IOException {
            String provenance = inventory.provenance();
            if (!provenance.startsWith("fresh-root:")) {
                return Optional.empty();
            }
            String proofHash = provenance.substring("fresh-root:".length());
            if (!proofHash.matches("[0-9a-f]{64}") || !inventory.projectJson().equals("{}")
                || !inventory.assets().isEmpty() || !inventory.blocked().isEmpty() || !inventory.evidence().isEmpty()
                || !evidence.isEmpty()) {
                throw new MigrationException("Fresh Asset Adoption Authority Is Not Exact");
            }
            Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
            Path active = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
            Result durable = AssetCoordinatorMigration.load(coordination);
            if (!artifactPath.equals(durable.artifactPath()) || !artifactHash.equals(durable.artifactHash())
                || !manifestHash.equals(durable.manifestHash()) || !inventory.equals(durable.inventory())
                || !evidence.equals(durable.evidence())) {
                throw new MigrationException("Fresh Asset Adoption Authority Does Not Match Its Durable Artifact");
            }
            Path genesis = FreshRootProvenance.verifyConsumedInstallation(coordination, proofHash, artifactHash);
            if (!genesis.equals(active) || !genesis.toRealPath().equals(active.toRealPath())) {
                return Optional.empty();
            }
            return Optional.of(new FreshRootAuthority(active.toRealPath(), proofHash, artifactHash));
        }

        private void retireEvidence(Path assetsRoot) throws IOException {
            Path artifactRoot = artifactPath.getParent();
            for (Evidence item : evidence) {
                Path durable = MigrationPaths.resolveInside(artifactRoot, item.evidencePath());
                if (Files.isSymbolicLink(durable) || !Files.isRegularFile(durable, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Asset Adoption Durable Evidence Is Missing: " + item.evidencePath());
                }
                byte[] durableBytes = Files.readAllBytes(durable);
                if (durableBytes.length != item.size() || !sha256(durableBytes).equals(item.hash())) {
                    throw new MigrationException("Asset Adoption Durable Evidence Changed: " + item.evidencePath());
                }
                Path source = MigrationPaths.resolveInside(assetsRoot, item.originalPath());
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Asset Adoption Evidence Source Is Invalid: " + item.originalPath());
                }
                byte[] sourceBytes = Files.readAllBytes(source);
                if (sourceBytes.length != item.size() || !sha256(sourceBytes).equals(item.hash())) {
                    throw new MigrationException("Asset Adoption Evidence Source Changed Before Retirement: "
                        + item.originalPath());
                }
                if (!item.originalPath().startsWith(".tombstones/")) {
                    Files.deleteIfExists(source);
                }
            }
        }
    }

    public record FreshRootAuthority(Path activeRoot, String proofHash, String artifactHash) {
        public FreshRootAuthority {
            activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
            requireDigestUnchecked(proofHash, "proofHash");
            requireDigestUnchecked(artifactHash, "artifactHash");
        }
    }

    private static void verifyDurableEvidence(Path artifactRoot, List<Evidence> evidence) throws IOException {
        HashSet<String> paths = new HashSet<>();
        for (Evidence item : evidence) {
            if (!paths.add(item.evidencePath())) {
                throw new MigrationException("Asset Adoption Durable Evidence Is Duplicated: " + item.evidencePath());
            }
            readDurableEvidence(artifactRoot, item);
        }
    }

    private static byte[] readDurableEvidence(Path artifactRoot, Evidence item) throws IOException {
        Path durable = MigrationPaths.resolveInside(artifactRoot, item.evidencePath());
        if (Files.isSymbolicLink(durable) || !Files.isRegularFile(durable, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Asset Adoption Durable Evidence Is Missing: " + item.evidencePath());
        }
        byte[] bytes = Files.readAllBytes(durable);
        if (bytes.length != item.size() || !sha256(bytes).equals(item.hash())) {
            throw new MigrationException("Asset Adoption Durable Evidence Changed: " + item.evidencePath());
        }
        return bytes;
    }

    private static String normalizeEvidenceKey(String value) {
        String normalized = MigrationPaths.requireRelative(value);
        return normalized.startsWith("assets/") ? normalized.substring("assets/".length()) : normalized;
    }

    public record Evidence(String originalPath, String evidencePath, String hash, long size, String owner) {
        public Evidence {
            originalPath = MigrationPaths.requireRelative(originalPath);
            evidencePath = MigrationPaths.requireRelative(evidencePath);
            requireDigestUnchecked(hash, "evidenceHash");
            if (size < 0L) {
                throw new IllegalArgumentException("Evidence Size Cannot Be Negative");
            }
            owner = requireText(owner, "evidenceOwner");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static void requireDigestUnchecked(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A Lowercase SHA-256 Digest");
        }
    }
}
