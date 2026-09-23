package restudio.resync.upgrade;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.FreshRootProvenance;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.VerifiedSnapshotAdmission;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public final class AssetAdoptionArtifactProducer {
    public static final String ARTIFACT_RELATIVE_PATH = "asset-coordinator/adoption-v1.json";
    public static final String FORMAT = "asset-coordinator-adoption-v1";

    private static final String ASSETS_PREFIX = "assets/";
    private static final String PROJECT_PATH = "assets/project.json";
    private static final String TOMBSTONE_PREFIX = "assets/.tombstones/";
    private static final String CONTROL_PREFIX = "assets/.migrations/";
    private static final String QUARANTINE_PREFIX = ".quarantine/migration/";
    private static final Set<String> RESERVED = Set.of(
        ".asset-coordinator", ".transactions", ".snapshots", ".quarantine", ".durability",
        ".migrations", ".tombstones", "migration-backups");
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private AssetAdoptionArtifactProducer() {
    }

    public static Result produce(Path coordinationRoot, VerifiedSnapshotAdmission preStageAdmission,
                                 StageOutput stageOutput) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        VerifiedSnapshotAdmission admission = Objects.requireNonNull(preStageAdmission, "preStageAdmission");
        StageOutput staged = Objects.requireNonNull(stageOutput, "stageOutput");
        Snapshot preStage = admission.snapshot();
        if (!preStage.verified() || !preStage.metadata().equals(preStage.manifest().metadata())
            || !preStage.manifest().manifestHash().equals(admission.manifestHash())
            || !staged.sourceManifestHash().equals(admission.manifestHash())
            || !staged.sourceSnapshotId().equals(admission.metadata().snapshotId())) {
            throw new MigrationException("Asset Adoption Stage Output Does Not Match Verified Pre-Stage Admission");
        }
        validateManifestAliases(preStage.manifest());
        VerifiedSnapshotAdmission postAdmission = staged.postStageAdmission();
        Snapshot postStage = postAdmission.snapshot();
        if (!postStage.verified() || !postStage.metadata().equals(postStage.manifest().metadata())) {
            throw new MigrationException("Asset Adoption Post-Stage Snapshot Is Not Verified");
        }
        if (!postAdmission.manifestHash().equals(postStage.manifest().manifestHash())
            || !postStage.manifest().metadata().equals(postAdmission.metadata())) {
            throw new MigrationException("Asset Adoption Post-Stage Admission Is Internally Inconsistent");
        }
        validateManifestAliases(postStage.manifest());
        staged.requireAccepted();
        Path artifactPath = MigrationPaths.resolveInside(coordination, ARTIFACT_RELATIVE_PATH);
        Files.createDirectories(artifactPath.getParent());
        MigrationPaths.requireNoSymlinkTraversal(coordination, artifactPath.getParent());
        Path lockPath = artifactPath.resolveSibling(artifactPath.getFileName() + ".lock");
        if (Files.isSymbolicLink(lockPath)
            || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
            throw new MigrationException("Asset Coordinator Adoption Lock Is Invalid");
        }
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(artifactPath, ignored -> new ReentrantLock(true));
        jvmLock.lock();
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireFileLock(lockChannel)) {
            Map<String, SnapshotManifest.Entry> preStageEntries = entries(preStage.manifest());
            Map<String, SnapshotManifest.Entry> postStageEntries = entries(postStage.manifest());
            validateQuarantineEvidence(preStageEntries, postStageEntries, staged.quarantineReport());
            BlockedValidation blockedValidation = validateBlocked(postStageEntries, staged.blocked());
            validateBlockedLineage(preStageEntries, postStageEntries, blockedValidation);
            validateLifecycleClaims(postStage, postStageEntries, staged.lifecycleOutputs(),
                blockedValidation.bySourcePath());
            validateLifecycleChanges(preStage, preStageEntries, postStage, postStageEntries,
                staged.lifecycleOutputs());
            validateExhaustiveDelta(preStageEntries, postStageEntries, staged.lifecycleOutputs(),
                blockedValidation.bySourcePath());
            InventoryBuild inventory = buildInventory(postStage, postStageEntries, staged, blockedValidation);
            List<Evidence> evidence = evidenceDescriptors(postStage.manifest().manifestHash(), inventory.evidence());
            validateEvidenceDescriptors(preStage.manifest(), postStage.manifest(), evidence);
            byte[] artifact = artifactBytes(preStage, postStage, staged, inventory.inventory(), evidence);
            Result retained = retained(artifactPath, admission.manifestHash());
            if (retained != null) {
                if (!retained.postStageManifestHash().equals(postStage.manifest().manifestHash())
                    || !retained.planHash().equals(staged.planHash())
                    || !Arrays.equals(Files.readAllBytes(artifactPath), artifact)) {
                    throw new MigrationException("Asset Adoption Artifact Belongs To A Different Staged Output");
                }
                verifyDurableEvidence(artifactPath.getParent(), retained.evidence());
                return retained;
            }
            persistEvidence(artifactPath.getParent(), inventory.evidence(), evidence);
            persistArtifact(artifactPath, artifact);
            return decodeResult(artifactPath, artifact);
        } finally {
            jvmLock.unlock();
        }
    }

    public static Result load(Path coordinationRoot) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        Path artifact = MigrationPaths.resolveInside(coordination, ARTIFACT_RELATIVE_PATH);
        if (Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Asset Coordinator Adoption Artifact Is Missing");
        }
        Result result = decodeResult(artifact, Files.readAllBytes(artifact));
        verifyDurableEvidence(artifact.getParent(), result.evidence());
        return result;
    }

    public static Result produceEmpty(Path coordinationRoot, FreshRootProvenance provenance) throws IOException {
        Result result = produceEmptyUnconsumed(coordinationRoot, provenance);
        provenance.consume(result.artifactHash());
        return result;
    }

    public static Result produceEmptyUnconsumed(Path coordinationRoot, FreshRootProvenance provenance) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        FreshRootProvenance fresh = Objects.requireNonNull(provenance, "provenance");
        fresh.verify(coordination, fresh.activeRoot());
        Path artifactPath = MigrationPaths.resolveInside(coordination, ARTIFACT_RELATIVE_PATH);
        Files.createDirectories(artifactPath.getParent());
        MigrationPaths.requireNoSymlinkTraversal(coordination, artifactPath.getParent());
        Path lockPath = artifactPath.resolveSibling(artifactPath.getFileName() + ".lock");
        if (Files.isSymbolicLink(lockPath)
            || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
            throw new MigrationException("Asset Coordinator Adoption Lock Is Invalid");
        }
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(artifactPath, ignored -> new ReentrantLock(true));
        jvmLock.lock();
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireFileLock(lockChannel)) {
            String manifestHash = sha256(("empty-production-assets-v1\n" + fresh.proofHash())
                .getBytes(StandardCharsets.UTF_8));
            Snapshot empty = emptySnapshot(fresh.activeRoot(), fresh.proofHash());
            Result retained = retained(artifactPath, empty.manifest().manifestHash());
            if (retained != null) {
                return retained;
            }
            QuarantineReport quarantine = QuarantineReport.empty();
            QuarantineAcceptance acceptance = quarantine.acceptance(List.of(), "fresh-root", Instant.EPOCH);
            Inventory inventory = new Inventory("fresh-root:" + fresh.proofHash(), "{}", List.of(), List.of());
            byte[] artifact = artifactBytes(empty, empty, manifestHash, List.of(), quarantine, acceptance,
                inventory, List.of());
            persistArtifact(artifactPath, artifact);
            return decodeResult(artifactPath, artifact);
        } finally {
            jvmLock.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel) throws IOException {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException exception) {
            throw new MigrationException("Asset Coordinator Adoption Lock Is Already Held", exception);
        }
    }

    private static Result retained(Path artifactPath, String sourceManifestHash) throws IOException {
        if (!Files.exists(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(artifactPath)
            || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Asset Coordinator Adoption Artifact Is Not A Regular File");
        }
        Result result = decodeResult(artifactPath, Files.readAllBytes(artifactPath));
        if (!result.sourceManifestHash().equals(sourceManifestHash)) {
            throw new MigrationException("Asset Coordinator Adoption Artifact Belongs To A Different Verified Snapshot");
        }
        return result;
    }

    private static Map<String, SnapshotManifest.Entry> entries(SnapshotManifest manifest) throws MigrationException {
        Map<String, SnapshotManifest.Entry> entries = new TreeMap<>();
        Set<String> folded = new HashSet<>();
        for (SnapshotManifest.Entry entry : manifest.entries()) {
            if (entries.put(entry.relativePath(), entry) != null
                || !folded.add(entry.relativePath().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Snapshot Contains A Duplicate Or Case-Aliased Path: " + entry.relativePath());
            }
        }
        return entries;
    }

    private static InventoryBuild buildInventory(Snapshot snapshot, Map<String, SnapshotManifest.Entry> entries,
                                                  StageOutput stageOutput, BlockedValidation blockedValidation)
        throws IOException {
        LifecycleAuthority authority = lifecycleAuthority(stageOutput.lifecycleOutputs());
        String projectJson = "{}";
        List<Asset> assets = new ArrayList<>();
        List<BlockedState> blocked = new ArrayList<>(blockedValidation.values());
        Map<String, BlockedState> blockedByPath = blockedValidation.bySourcePath();
        List<EvidenceSource> evidence = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> seenRecords = new HashSet<>();
        Set<String> seenBlocked = new HashSet<>();
        for (BlockedState value : blocked) {
            if (value.candidateType() != null) {
                String blockedKey = key(value.candidateType(), value.candidateId());
                if (value.canonicalFuturePath() == null) {
                    requireKeyUnique(blockedKey, keys);
                } else {
                    requireUnique(blockedKey, value.canonicalFuturePath().toString(), keys, paths);
                }
            } else if (value.canonicalFuturePath() != null) {
                requirePathUnique(value.canonicalFuturePath().toString(), paths);
            }
        }
        for (SnapshotManifest.Entry entry : entries.values()) {
            BlockedState blockedState = blockedByPath.get(entry.relativePath());
            byte[] bytes = readVerified(snapshot.root(), entry);
            if (entry.relativePath().startsWith(QUARANTINE_PREFIX)) {
                if (authority.binding(entry.relativePath()) != null || authority.record(entry.relativePath()) != null
                    || blockedState != null && !blockedState.owner().equals(entry.owner())) {
                    throw new MigrationException("Quarantine Evidence Has An Invalid Lifecycle Binding: "
                        + entry.relativePath());
                }
                evidence.add(new EvidenceSource(entry, bytes));
                if (blockedState != null) {
                    seenBlocked.add(entry.relativePath());
                }
                continue;
            }
            LifecycleBinding binding = authority.binding(entry.relativePath());
            if (binding != null && !binding.owner().equals(entry.owner())) {
                throw new MigrationException("Asset Adoption Lifecycle Authority Owner Does Not Match Snapshot: " + entry.relativePath());
            }
            if (binding == null && (blockedState == null || !blockedState.owner().equals(entry.owner()))) {
                if (!entry.relativePath().startsWith(ASSETS_PREFIX)) {
                    continue;
                }
                throw new MigrationException("Asset Adoption Snapshot Path Has No Accepted Lifecycle Authority: " + entry.relativePath());
            }
            if (blockedState != null) {
                if (binding != null || authority.record(entry.relativePath()) != null) {
                    throw new MigrationException("Blocked Evidence Cannot Have An Accepted Lifecycle Authority: "
                        + entry.relativePath());
                }
                evidence.add(new EvidenceSource(entry, bytes));
                seenBlocked.add(entry.relativePath());
                continue;
            }
            if (!entry.relativePath().startsWith(ASSETS_PREFIX)) {
                continue;
            }
            if (entry.relativePath().equals(PROJECT_PATH)) {
                projectJson = projectJson(bytes);
                continue;
            }
            if (entry.relativePath().startsWith(TOMBSTONE_PREFIX)) {
                evidence.add(new EvidenceSource(entry, bytes));
                AssetRecord record = authority.record(entry.relativePath());
                requireRecord(record, entry, "deleted");
                requireDeletedRecord(record, entry, entries, snapshot.root());
                seenRecords.add(record.relativePath());
                String key = key(record.type(), record.id());
                String logical = record.deletion().canonicalFuturePath().toString();
                requireUnique(key, logical, keys, paths);
                LegacyDeletion deletion = record.deletion();
                assets.add(new Asset(record.owner(), record.type(), record.id(), record.assetPath(),
                    new AssetState("DELETED", record.revision(), entry.sha256()), record.mutationId(),
                    new byte[0], deletion));
                continue;
            }
            if (entry.relativePath().startsWith(CONTROL_PREFIX)) {
                evidence.add(new EvidenceSource(entry, bytes));
                if (authority.record(entry.relativePath()) != null) {
                    throw new MigrationException("Control Evidence Cannot Have An Accepted Lifecycle Asset Record: "
                        + entry.relativePath());
                }
                continue;
            }
            String relative = entry.relativePath().substring(ASSETS_PREFIX.length());
            requireManagedPath(relative);
            AssetRecord record = authority.record(entry.relativePath());
            requireRecord(record, entry, "live");
            requireLiveRecord(record, entry);
            seenRecords.add(record.relativePath());
            String assetKey = key(record.type(), record.id());
            String logical = normalizeAssetPath(record.assetPath());
            requireUnique(assetKey, logical, keys, paths);
            assets.add(new Asset(record.owner(), record.type(), record.id(), record.assetPath(),
                new AssetState("LIVE", record.revision(), entry.sha256()), record.mutationId(), bytes, null));
        }
        if (!seenBlocked.containsAll(blockedByPath.keySet())) {
            Set<String> missing = new LinkedHashSet<>(blockedByPath.keySet());
            missing.removeAll(seenBlocked);
            throw new MigrationException("Accepted Lifecycle Blocked Evidence Was Not Materialized: " + missing);
        }
        for (AssetRecord record : authority.records().values()) {
            if (!seenRecords.contains(record.relativePath())) {
                throw new MigrationException("Accepted Lifecycle Asset Record Was Not Materialized: " + record.relativePath());
            }
        }
        assets.sort(Comparator.comparing(Asset::canonicalKey));
        blocked.sort(Comparator.comparing(BlockedState::canonicalKey));
        String provenance = "snapshot:" + snapshot.manifest().manifestHash();
        return new InventoryBuild(new Inventory(provenance, projectJson, assets, blocked), evidence);
    }

    private static LifecycleAuthority lifecycleAuthority(List<LifecycleOutput> outputs) throws MigrationException {
        Map<String, LifecycleBinding> bindings = new TreeMap<>();
        Map<String, String> foldedPaths = new TreeMap<>();
        Map<String, AssetRecord> records = new TreeMap<>();
        Map<String, String> foldedRecords = new TreeMap<>();
        Map<String, String> identities = new TreeMap<>();
        Map<String, String> foldedAssetPaths = new TreeMap<>();
        Set<String> claimPaths = new HashSet<>();
        Set<String> changeTargets = new HashSet<>();
        Set<String> foldedChangeTargets = new HashSet<>();
        for (LifecycleOutput output : outputs) {
            LifecycleBinding binding = new LifecycleBinding(output.adapterId(), output.owner());
            for (Claim claim : output.claims()) {
                if (!claim.relativePath().startsWith(ASSETS_PREFIX)) {
                    throw new MigrationException("Accepted Lifecycle Claim Is Outside The Assets Root: " + claim.relativePath());
                }
                if (!claimPaths.add(claim.relativePath())) {
                    throw new MigrationException("Accepted Lifecycle Outputs Contain A Duplicate Claim: " + claim.relativePath());
                }
                bindPath(bindings, foldedPaths, claim.relativePath(), binding);
            }
            for (AcceptedChange change : output.changes()) {
                if (!output.owner().equals(change.owner())) {
                    throw new MigrationException("Accepted Lifecycle Change Owner Does Not Match Its Output: " + change.kind());
                }
                if (change.targetPath() != null) {
                    if (!change.targetPath().startsWith(ASSETS_PREFIX)) {
                        throw new MigrationException("Accepted Lifecycle Change Target Is Outside The Assets Root: "
                            + change.targetPath());
                    }
                    if (!changeTargets.add(change.targetPath())
                        || !foldedChangeTargets.add(change.targetPath().toLowerCase(Locale.ROOT))) {
                        throw new MigrationException("Accepted Lifecycle Outputs Contain A Duplicate Or Case-Aliased Change Target: "
                            + change.targetPath());
                    }
                    bindPath(bindings, foldedPaths, change.targetPath(), binding);
                }
            }
            for (AssetRecord record : output.assets()) {
                if (!record.relativePath().startsWith(ASSETS_PREFIX)) {
                    throw new MigrationException("Accepted Lifecycle Asset Record Is Outside The Assets Root: " + record.relativePath());
                }
                if (!output.owner().equals(record.owner())) {
                    throw new MigrationException("Accepted Lifecycle Asset Record Owner Does Not Match Its Output: " + record.relativePath());
                }
                bindPath(bindings, foldedPaths, record.relativePath(), binding);
                String foldedRecordPath = record.relativePath().toLowerCase(Locale.ROOT);
                String previousRecordPath = foldedRecords.putIfAbsent(foldedRecordPath, record.relativePath());
                if (previousRecordPath != null) {
                    throw new MigrationException("Accepted Lifecycle Asset Records Contain A Case-Aliased Path: "
                        + previousRecordPath + " And " + record.relativePath());
                }
                if (records.put(record.relativePath(), record) != null) {
                    throw new MigrationException("Duplicate Accepted Lifecycle Asset Record: " + record.relativePath());
                }
                String identity = key(record.type(), record.id());
                String previousIdentity = identities.putIfAbsent(identity.toLowerCase(Locale.ROOT), record.relativePath());
                if (previousIdentity != null) {
                    throw new MigrationException("Accepted Lifecycle Asset Records Contain A Duplicate Identity: " + identity);
                }
                String assetPath = normalizeAssetPath(record.assetPath());
                String previousAssetPath = foldedAssetPaths.putIfAbsent(assetPath.toLowerCase(Locale.ROOT), assetPath);
                if (previousAssetPath != null) {
                    throw new MigrationException("Accepted Lifecycle Asset Records Contain A Duplicate Or Case-Aliased Asset Path: "
                        + previousAssetPath + " And " + assetPath);
                }
            }
        }
        validateAuxiliaryDeclarations(records);
        return new LifecycleAuthority(bindings, records);
    }

    private static void validateAuxiliaryDeclarations(Map<String, AssetRecord> records)
        throws MigrationException {
        Set<String> identities = new HashSet<>();
        Set<String> primaryIdentities = records.values().stream()
            .map(value -> key(value.type(), value.id()).toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (AssetRecord record : records.values()) {
            if (!record.state().equals("DELETED")) {
                continue;
            }
            AuxiliaryKey auxiliary = record.deletion().auxiliaryKey();
            requireAuxiliaryKey(record, auxiliary);
            String identity = key(auxiliary.type(), auxiliary.id()).toLowerCase(Locale.ROOT);
            if (primaryIdentities.contains(identity)) {
                throw new MigrationException("Accepted Lifecycle Auxiliary Identity Conflicts With A Primary Asset: "
                    + auxiliary.type() + "/" + auxiliary.id());
            }
            if (!identities.add(identity)) {
                throw new MigrationException("Accepted Lifecycle Deleted Assets Contain A Duplicate Auxiliary Identity: "
                    + auxiliary.type() + "/" + auxiliary.id());
            }
        }
    }

    private static void requireAuxiliaryKey(AssetRecord record, AuxiliaryKey auxiliary) throws MigrationException {
        if (auxiliary == null) {
            throw new MigrationException("Deleted Lifecycle Asset Is Missing Its Exact Auxiliary Identity: "
                + record.relativePath());
        }
        String expectedType = switch (auxiliary.family()) {
            case AuxiliaryKey.JSON_FAMILY -> record.type() + ".tombstone";
            case AuxiliaryKey.FLOW_GRAPH_FAMILY -> "tombstone:" + record.type();
            case AuxiliaryKey.EXPLICIT_FAMILY -> auxiliary.type();
            default -> throw new MigrationException("Deleted Lifecycle Asset Auxiliary Family Is Unsupported: "
                + auxiliary.family());
        };
        if (!expectedType.equals(auxiliary.type())
            || (auxiliary.family() != AuxiliaryKey.EXPLICIT_FAMILY && !record.id().equals(auxiliary.id()))) {
            throw new MigrationException("Deleted Lifecycle Asset Auxiliary Identity Does Not Match Its Typed Declaration: "
                + record.relativePath());
        }
    }

    private static void bindPath(Map<String, LifecycleBinding> bindings, Map<String, String> foldedPaths,
                                 String path, LifecycleBinding binding) throws MigrationException {
        String normalized = MigrationPaths.requireRelative(path);
        String folded = normalized.toLowerCase(Locale.ROOT);
        String previousFolded = foldedPaths.putIfAbsent(folded, normalized);
        if (previousFolded != null && !previousFolded.equals(normalized)) {
            throw new MigrationException("Accepted Lifecycle Authority Contains A Case-Aliased Path: "
                + previousFolded + " And " + normalized);
        }
        LifecycleBinding previous = bindings.putIfAbsent(normalized, binding);
        if (previous != null && !previous.equals(binding)) {
            throw new MigrationException("Accepted Lifecycle Authority Contains Multiple Owners: " + normalized);
        }
    }

    private static void validateLifecycleClaims(Map<String, SnapshotManifest.Entry> postStageEntries,
                                                List<LifecycleOutput> outputs,
                                                Map<String, BlockedState> blockedByPath)
        throws MigrationException {
        for (LifecycleOutput output : outputs) {
            for (Claim claim : output.claims()) {
                if (!claim.relativePath().startsWith(ASSETS_PREFIX)) {
                    throw new MigrationException("Accepted Lifecycle Claim Is Outside The Assets Root: "
                        + claim.relativePath());
                }
                if (blockedByPath.containsKey(claim.relativePath())) {
                    throw new MigrationException("Accepted Lifecycle Claim Cannot Own Blocked Evidence: "
                        + claim.relativePath());
                }
                SnapshotManifest.Entry entry = postStageEntries.get(claim.relativePath());
                if (entry == null) {
                    throw new MigrationException("Accepted Lifecycle Claim Is Not In The Post-Stage Snapshot: "
                        + claim.relativePath());
                }
                if (!entry.owner().equals(claim.owner())) {
                    throw new MigrationException("Accepted Lifecycle Claim Owner Does Not Match The Post-Stage Snapshot: "
                        + claim.relativePath());
                }
                if (entry.size() < 0L || !entry.sha256().matches("[0-9a-fA-F]{64}")) {
                    throw new MigrationException("Accepted Lifecycle Claim Is Not Bound To An Exact Snapshot Entry: "
                        + claim.relativePath());
                }
            }
        }
    }

    private static void validateLifecycleClaims(Snapshot postStage,
                                                 Map<String, SnapshotManifest.Entry> postStageEntries,
                                                 List<LifecycleOutput> outputs,
                                                 Map<String, BlockedState> blockedByPath) throws IOException {
        validateLifecycleClaims(postStageEntries, outputs, blockedByPath);
        for (LifecycleOutput output : outputs) {
            for (Claim claim : output.claims()) {
                readVerified(postStage.root(), postStageEntries.get(claim.relativePath()));
            }
        }
    }

    private static BlockedValidation validateBlocked(Map<String, SnapshotManifest.Entry> entries,
                                                     List<BlockedState> supplied) throws MigrationException {
        List<BlockedState> blocked = new ArrayList<>(supplied == null ? List.of() : supplied);
        blocked.sort(Comparator.comparing(BlockedState::canonicalKey));
        Set<String> evidenceKeys = new HashSet<>();
        Set<String> foldedEvidenceKeys = new HashSet<>();
        Set<String> identities = new HashSet<>();
        Set<String> sourcePaths = new HashSet<>();
        Set<String> foldedSourcePaths = new HashSet<>();
        Map<String, BlockedState> bySourcePath = new TreeMap<>();
        for (BlockedState value : blocked) {
            if (!evidenceKeys.add(value.evidenceKey())
                || !foldedEvidenceKeys.add(value.evidenceKey().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Accepted Lifecycle Blocked States Contain A Duplicate Or Case-Aliased Evidence Key: "
                    + value.evidenceKey());
            }
            if (value.sourcePath() != null
                && !normalizeSnapshotPath(value.sourcePath()).equals(MigrationPaths.requireRelative(value.evidenceKey()))) {
                throw new MigrationException("Accepted Lifecycle Blocked Evidence Key Does Not Match Its Materialized Path: "
                    + value.evidenceKey());
            }
            String sourcePath = sourcePath(value, entries);
            if (sourcePath.equals(PROJECT_PATH)) {
                throw new MigrationException("Accepted Lifecycle Blocked Evidence Cannot Replace The Asset Project Authority");
            }
            if (!sourcePaths.add(sourcePath) || !foldedSourcePaths.add(sourcePath.toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Accepted Lifecycle Blocked States Contain A Duplicate Or Case-Aliased Source Path: "
                    + sourcePath);
            }
            SnapshotManifest.Entry entry = entries.get(sourcePath);
            if (entry == null) {
                throw new MigrationException("Accepted Lifecycle Blocked Evidence Is Not In The Post-Stage Snapshot: " + sourcePath);
            }
            if (!entry.sha256().equals(value.evidenceHash()) || !entry.owner().equals(value.owner())) {
                throw new MigrationException("Accepted Lifecycle Blocked Evidence Is Not Bound To Its Snapshot Entry: " + sourcePath);
            }
            if ((value.candidateType() == null) != (value.candidateId() == null)) {
                throw new MigrationException("Accepted Lifecycle Blocked Identity Is Incomplete: " + value.evidenceKey());
            }
            if (value.candidateType() != null
                && !identities.add(key(value.candidateType(), value.candidateId()).toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Accepted Lifecycle Blocked States Contain A Duplicate Identity: "
                    + value.candidateType() + "/" + value.candidateId());
            }
            bySourcePath.put(sourcePath, value);
        }
        return new BlockedValidation(List.copyOf(blocked), Map.copyOf(bySourcePath));
    }

    private static String sourcePath(BlockedState value, Map<String, SnapshotManifest.Entry> entries)
        throws MigrationException {
        if (value.sourcePath() != null) {
            String path = normalizeSnapshotPath(value.sourcePath());
            if (!isAdoptionEvidencePath(path)) {
                throw new MigrationException("Accepted Lifecycle Blocked Evidence Is Outside The Supported Roots: " + path);
            }
            return path;
        }
        try {
            String candidate = MigrationPaths.requireRelative(value.evidenceKey());
            if (entries.containsKey(candidate)) {
                return candidate;
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new MigrationException("Accepted Lifecycle Blocked Evidence Requires A Materialized Snapshot Path: "
            + value.evidenceKey());
    }

    private static boolean isAdoptionEvidencePath(String path) {
        return path.startsWith(ASSETS_PREFIX) || path.startsWith(QUARANTINE_PREFIX);
    }

    private static Set<String> validateQuarantineEvidence(Map<String, SnapshotManifest.Entry> preStageEntries,
                                                           Map<String, SnapshotManifest.Entry> postStageEntries,
                                                           QuarantineReport report) throws MigrationException {
        Set<String> expectedPaths = new HashSet<>();
        for (QuarantineRecord record : report.records()) {
            String sourcePath = MigrationPaths.requireRelative(record.sourceLocation());
            if (sourcePath.equals(PROJECT_PATH) || sourcePath.startsWith(".quarantine/")) {
                throw new MigrationException("Accepted Quarantine Cannot Replace A Reserved Asset Source: "
                    + sourcePath);
            }
            if (record.sourceHash().isEmpty()) {
                throw new MigrationException("Accepted Quarantine Evidence Requires An Exact Source Hash: "
                    + sourcePath);
            }
            String evidencePath = quarantineEvidencePath(record, sourcePath);
            if (!expectedPaths.add(evidencePath)) {
                throw new MigrationException("Accepted Quarantine Evidence Contains A Duplicate Path: "
                    + evidencePath);
            }
            SnapshotManifest.Entry sourceEntry = preStageEntries.get(sourcePath);
            SnapshotManifest.Entry evidenceEntry = postStageEntries.get(evidencePath);
            if (sourceEntry == null || evidenceEntry == null
                || sourceEntry.size() != evidenceEntry.size()
                || !sourceEntry.sha256().equals(record.sourceHash())
                || !evidenceEntry.sha256().equals(record.sourceHash())
                || !sourceEntry.owner().equals(evidenceEntry.owner())) {
                throw new MigrationException("Accepted Quarantine Evidence Is Not An Exact Retained Source: "
                    + sourcePath);
            }
        }
        for (String path : postStageEntries.keySet()) {
            if (path.startsWith(QUARANTINE_PREFIX) && !expectedPaths.contains(path)) {
                throw new MigrationException("Post-Stage Quarantine Evidence Is Not Bound To An Accepted Record: "
                    + path);
            }
        }
        return Set.copyOf(expectedPaths);
    }

    private static String quarantineEvidencePath(QuarantineRecord record, String sourcePath)
        throws MigrationException {
        String recordId = record.recordId();
        if (recordId == null || !recordId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new MigrationException("Quarantine Record ID Is Not A Safe Path Segment: " + recordId);
        }
        return QUARANTINE_PREFIX + recordId + "/" + sourcePath;
    }

    private static void validateBlockedLineage(Map<String, SnapshotManifest.Entry> preStageEntries,
                                               Map<String, SnapshotManifest.Entry> postStageEntries,
                                               BlockedValidation blockedValidation) throws MigrationException {
        for (BlockedState value : blockedValidation.values()) {
            String evidencePath = sourcePath(value, postStageEntries);
            if (!evidencePath.startsWith(".quarantine/migration/")) {
                continue;
            }
            if (value.originalLogicalPath() == null) {
                throw new MigrationException("Accepted Quarantine Evidence Requires Its Original Logical Path: "
                    + evidencePath);
            }
            String logical = normalizeAssetPath(value.originalLogicalPath());
            String original = ASSETS_PREFIX + logical;
            SnapshotManifest.Entry source = preStageEntries.get(original);
            SnapshotManifest.Entry evidence = postStageEntries.get(evidencePath);
            if (source == null || evidence == null || !source.owner().equals(value.owner())
                || !evidence.owner().equals(value.owner()) || !source.sha256().equals(value.evidenceHash())
                || !evidence.sha256().equals(value.evidenceHash())
                || !evidencePath.endsWith("/" + original)) {
                throw new MigrationException("Accepted Quarantine Evidence Is Not Bound To Its Original Snapshot Bytes: "
                    + evidencePath);
            }
        }
    }

    private static void validateLifecycleChanges(Map<String, SnapshotManifest.Entry> preStageEntries,
                                                 Map<String, SnapshotManifest.Entry> postStageEntries,
                                                 List<LifecycleOutput> outputs) throws MigrationException {
        for (LifecycleOutput output : outputs) {
            for (AcceptedChange change : output.changes()) {
                if (change.sourcePath() == null && change.targetPath() == null) {
                    throw new MigrationException("Accepted Lifecycle Change Has No Bound Source Or Target: "
                        + change.kind());
                }
                validateLifecycleChangeEndpoint(preStageEntries, change.sourcePath(), change.sourceHash(),
                    change.owner(), "Source", change.kind());
                validateLifecycleChangeEndpoint(postStageEntries, change.targetPath(), change.targetHash(),
                    change.owner(), "Target", change.kind());
            }
        }
    }

    private static void validateLifecycleChanges(Snapshot preStage,
                                                 Map<String, SnapshotManifest.Entry> preStageEntries,
                                                 Snapshot postStage,
                                                 Map<String, SnapshotManifest.Entry> postStageEntries,
                                                 List<LifecycleOutput> outputs) throws IOException {
        validateLifecycleChanges(preStageEntries, postStageEntries, outputs);
        for (LifecycleOutput output : outputs) {
            for (AcceptedChange change : output.changes()) {
                if (change.sourcePath() != null) {
                    readVerified(preStage.root(), preStageEntries.get(change.sourcePath()));
                }
                if (change.targetPath() != null) {
                    readVerified(postStage.root(), postStageEntries.get(change.targetPath()));
                }
            }
        }
    }

    private static void validateExhaustiveDelta(Map<String, SnapshotManifest.Entry> preStageEntries,
                                                Map<String, SnapshotManifest.Entry> postStageEntries,
                                                List<LifecycleOutput> outputs,
                                                Map<String, BlockedState> blockedByPath) throws MigrationException {
        Set<String> expectedSources = new LinkedHashSet<>();
        Set<String> expectedTargets = new LinkedHashSet<>();
        Set<String> removedPaths = new LinkedHashSet<>();
        Set<String> addedPaths = new LinkedHashSet<>();
        Set<String> blockedOriginals = blockedByPath.values().stream()
            .map(BlockedState::originalLogicalPath)
            .filter(Objects::nonNull)
            .map(value -> ASSETS_PREFIX + normalizeAssetPath(value))
            .collect(Collectors.toSet());
        Set<String> acceptedSources = new HashSet<>();
        Set<String> acceptedTargets = new HashSet<>();
        Set<String> acceptedPairs = new HashSet<>();
        for (String path : new TreeSet<>(postStageEntries.keySet())) {
            if (!path.startsWith(ASSETS_PREFIX)) {
                continue;
            }
            if (blockedByPath.containsKey(path)) {
                continue;
            }
            SnapshotManifest.Entry before = preStageEntries.get(path);
            SnapshotManifest.Entry after = postStageEntries.get(path);
            if (before == null) {
                expectedTargets.add(path);
                addedPaths.add(path);
            } else if (!sameEntry(before, after)) {
                expectedSources.add(path);
                expectedTargets.add(path);
            }
        }
        for (String path : new TreeSet<>(preStageEntries.keySet())) {
            if (path.startsWith(ASSETS_PREFIX) && !postStageEntries.containsKey(path)
                && !blockedOriginals.contains(path)) {
                expectedSources.add(path);
                removedPaths.add(path);
            }
        }
        for (LifecycleOutput output : outputs) {
            for (AcceptedChange change : output.changes()) {
                String source = change.sourcePath();
                String target = change.targetPath();
                if (source == null && target == null) {
                    throw new MigrationException("Accepted Lifecycle Change Has No Bound Source Or Target: " + change.kind());
                }
                if (source != null && !source.startsWith(ASSETS_PREFIX)
                    || target != null && !target.startsWith(ASSETS_PREFIX)) {
                    throw new MigrationException("Accepted Lifecycle Change Is Outside The Assets Root: " + change.kind());
                }
                if (source != null && !acceptedSources.add(source)) {
                    throw new MigrationException("Accepted Lifecycle Changes Reuse A Source Path: " + source);
                }
                if (target != null && !acceptedTargets.add(target)) {
                    throw new MigrationException("Accepted Lifecycle Changes Reuse A Target Path: " + target);
                }
                String pair = (source == null ? "" : source) + "\n" + (target == null ? "" : target);
                if (!acceptedPairs.add(pair)) {
                    throw new MigrationException("Accepted Lifecycle Changes Contain A Duplicate Endpoint Pair: " + pair);
                }
            }
        }
        if (!acceptedSources.equals(expectedSources) || !acceptedTargets.equals(expectedTargets)) {
            Set<String> missingSources = new LinkedHashSet<>(expectedSources);
            missingSources.removeAll(acceptedSources);
            Set<String> extraSources = new LinkedHashSet<>(acceptedSources);
            extraSources.removeAll(expectedSources);
            Set<String> missingTargets = new LinkedHashSet<>(expectedTargets);
            missingTargets.removeAll(acceptedTargets);
            Set<String> extraTargets = new LinkedHashSet<>(acceptedTargets);
            extraTargets.removeAll(expectedTargets);
            throw new MigrationException("Accepted Lifecycle Changes Do Not Exactly Cover The Snapshot Delta: sources missing="
                + missingSources + ", sources extra=" + extraSources + ", targets missing=" + missingTargets
                + ", targets extra=" + extraTargets);
        }
        for (String source : removedPaths) {
            List<String> candidates = addedPaths.stream()
                .filter(target -> preStageEntries.get(source).sha256().equals(postStageEntries.get(target).sha256()))
                .toList();
            if (candidates.size() > 1) {
                throw new MigrationException("Snapshot Delta Has An Ambiguous Relocation: " + source);
            }
            if (candidates.size() == 1) {
                String target = candidates.getFirst();
                if (!acceptedPairs.contains(source + "\n" + target)) {
                    throw new MigrationException("Snapshot Relocation Is Not Represented By One Accepted Change: "
                        + source + " -> " + target);
                }
            }
        }
    }

    private static boolean sameEntry(SnapshotManifest.Entry before, SnapshotManifest.Entry after) {
        return after != null && before.size() == after.size() && before.sha256().equals(after.sha256())
            && before.owner().equals(after.owner());
    }

    private static void validateLifecycleChangeEndpoint(Map<String, SnapshotManifest.Entry> entries,
                                                        String path, String hash, String owner,
                                                        String endpoint, String kind) throws MigrationException {
        if (path == null) {
            if (hash != null) {
                throw new MigrationException("Accepted Lifecycle " + endpoint + " Hash Has No Bound Path: " + kind);
            }
            return;
        }
        if (!path.startsWith(ASSETS_PREFIX)) {
            throw new MigrationException("Accepted Lifecycle " + endpoint + " Path Is Outside The Assets Root: " + path);
        }
        SnapshotManifest.Entry entry = entries.get(path);
        if (entry == null) {
            throw new MigrationException("Accepted Lifecycle " + endpoint + " Path Is Not In Its Verified Snapshot: "
                + path);
        }
        if (!owner.equals(entry.owner())) {
            throw new MigrationException("Accepted Lifecycle " + endpoint + " Owner Does Not Match Its Verified Snapshot: "
                + path);
        }
        if (entry.size() < 0L || !entry.sha256().matches("[0-9a-fA-F]{64}")) {
            throw new MigrationException("Accepted Lifecycle " + endpoint + " Is Not Bound To An Exact Snapshot Entry: "
                + path);
        }
        if (hash == null || !hash.equals(entry.sha256())) {
            throw new MigrationException("Accepted Lifecycle " + endpoint + " Hash Does Not Match Its Verified Snapshot: "
                + path);
        }
    }

    private static void requireRecord(AssetRecord record, SnapshotManifest.Entry entry, String state)
        throws MigrationException {
        if (record == null) {
            throw new MigrationException("Accepted Lifecycle " + state + " Asset Record Is Missing: " + entry.relativePath());
        }
        if (!record.relativePath().equals(entry.relativePath()) || !record.owner().equals(entry.owner())) {
            throw new MigrationException("Accepted Lifecycle Asset Record Is Not Bound To Its Snapshot Entry: " + entry.relativePath());
        }
        if (!record.payloadHash().equals(entry.sha256())) {
            throw new MigrationException("Accepted Lifecycle Asset Record Payload Hash Does Not Match Snapshot Entry: " + entry.relativePath());
        }
    }

    private static void requireLiveRecord(AssetRecord record, SnapshotManifest.Entry entry) throws MigrationException {
        if (!record.state().equals("LIVE") || record.deletion() != null
            || !normalizeAssetPath(record.assetPath()).equals(entry.relativePath().substring(ASSETS_PREFIX.length()))) {
            throw new MigrationException("Live Lifecycle Record Does Not Match Its Payload Path: " + entry.relativePath());
        }
    }

    private static void requireDeletedRecord(AssetRecord record, SnapshotManifest.Entry entry,
                                             Map<String, SnapshotManifest.Entry> entries, Path snapshotRoot)
        throws IOException {
        if (!record.state().equals("DELETED") || record.deletion() == null
            || !entry.relativePath().startsWith(TOMBSTONE_PREFIX)) {
            throw new MigrationException("Tombstone Lifecycle Record Must Be Deleted: " + entry.relativePath());
        }
        LegacyDeletion deletion = record.deletion();
        if (!deletion.evidenceKey().equals(entry.relativePath())
            || !deletion.owner().equals(entry.owner())
            || !deletion.tombstoneEvidenceHash().equals(entry.sha256())
            || !deletion.tombstoneEvidenceHash().equals(record.payloadHash())
            || deletion.revision() != record.revision()
            || !deletion.mutationId().equals(record.mutationId())
            || !record.assetPath().equals(deletion.canonicalFuturePath())) {
            throw new MigrationException("Deleted Lifecycle Record Provenance Does Not Match Its Tombstone: " + entry.relativePath());
        }
        requireAuxiliaryKey(record, deletion.auxiliaryKey());
        String expectedEvidencePath = TOMBSTONE_PREFIX + record.type() + "/" + record.id() + ".json";
        if (!entry.relativePath().equals(expectedEvidencePath)) {
            throw new MigrationException("Deleted Lifecycle Tombstone Path Does Not Match Its Typed Asset: "
                + entry.relativePath());
        }
        String logical = deletion.canonicalFuturePath().toString();
        String target = ASSETS_PREFIX + logical;
        if (containsPathFolded(entries, target)) {
            throw new MigrationException("Deleted Asset Adoption Path Is Not Absent: " + logical);
        }
        Path targetPath = MigrationPaths.resolveInside(snapshotRoot, target);
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Deleted Asset Adoption Path Is Not Absent: " + logical);
        }
        if (deletion.originalLogicalPath() != null
            && containsPathFolded(entries, ASSETS_PREFIX + deletion.originalLogicalPath())) {
            throw new MigrationException("Deleted Asset Original Path Is Still Materialized: " + deletion.originalLogicalPath());
        }
    }

    private static boolean containsPathFolded(Map<String, SnapshotManifest.Entry> entries, String path) {
        String folded = path.toLowerCase(Locale.ROOT);
        return entries.keySet().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(folded));
    }

    private static void validateManifestAliases(SnapshotManifest manifest) throws MigrationException {
        Map<String, SnapshotManifest.Entry> manifestEntries = entries(manifest);
        Set<String> foldedDirectories = new HashSet<>();
        for (String directory : manifest.directories()) {
            if (!foldedDirectories.add(directory.toLowerCase(Locale.ROOT))
                || manifestEntries.keySet().stream().anyMatch(path -> path.equalsIgnoreCase(directory))) {
                throw new MigrationException("Snapshot Manifest Contains A Duplicate Or Case-Aliased Path: " + directory);
            }
        }
    }

    private static void requireUnique(String key, String path, Set<String> keys, Set<String> paths) throws MigrationException {
        requireKeyUnique(key, keys);
        requirePathUnique(path, paths);
    }

    private static void requireKeyUnique(String key, Set<String> keys) throws MigrationException {
        String foldedKey = key.toLowerCase(Locale.ROOT);
        if (!keys.add(foldedKey)) {
            throw new MigrationException("Asset Adoption Contains A Duplicate Key: " + key);
        }
    }

    private static void requirePathUnique(String path, Set<String> paths) throws MigrationException {
        String normalized = normalizeAssetPath(Path.of(path));
        String folded = normalized.toLowerCase(Locale.ROOT);
        boolean hierarchyCollision = paths.stream()
            .anyMatch(existing -> existing.startsWith(folded + "/") || folded.startsWith(existing + "/"));
        if (hierarchyCollision || !paths.add(folded)) {
            throw new MigrationException("Asset Adoption Contains A Duplicate Or Conflicting Path: " + normalized);
        }
    }

    private static List<Evidence> evidenceDescriptors(String manifestHash, List<EvidenceSource> sources)
        throws MigrationException {
        List<Evidence> evidence = new ArrayList<>();
        for (EvidenceSource source : sources.stream().sorted(Comparator.comparing(value -> value.entry().relativePath())).toList()) {
            String manifestPath = source.entry().relativePath();
            String original = manifestPath.startsWith(ASSETS_PREFIX)
                ? manifestPath.substring(ASSETS_PREFIX.length()) : manifestPath;
            String relative = "evidence/adoption-v1/" + manifestHash + "/" + evidenceSuffix(manifestPath);
            evidence.add(new Evidence(original, relative, source.entry().sha256(), source.entry().size(),
                source.entry().owner()));
        }
        Set<String> paths = new HashSet<>();
        Set<String> foldedPaths = new HashSet<>();
        for (Evidence value : evidence) {
            if (!paths.add(value.evidencePath()) || !foldedPaths.add(value.evidencePath().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Evidence Contains A Duplicate Or Case-Aliased Path: "
                    + value.evidencePath());
            }
        }
        return List.copyOf(evidence);
    }

    private static void validateEvidenceDescriptors(SnapshotManifest sourceManifest,
                                                    SnapshotManifest postStageManifest,
                                                    List<Evidence> evidence) throws MigrationException {
        Map<String, SnapshotManifest.Entry> sourceEntries = entries(sourceManifest);
        Map<String, SnapshotManifest.Entry> postStageEntries = entries(postStageManifest);
        String sourcePrefix = "evidence/adoption-v1/" + sourceManifest.manifestHash() + "/";
        String postStagePrefix = "evidence/adoption-v1/" + postStageManifest.manifestHash() + "/";
        Set<String> originals = new HashSet<>();
        Set<String> foldedOriginals = new HashSet<>();
        Set<String> durablePaths = new HashSet<>();
        Set<String> foldedDurablePaths = new HashSet<>();
        for (Evidence value : evidence) {
            String original = evidenceManifestPath(value.originalPath());
            if (!originals.add(original) || !foldedOriginals.add(original.toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Evidence Contains A Duplicate Or Case-Aliased Original Path: "
                    + value.originalPath());
            }
            SnapshotManifest.Entry entry;
            String expectedSuffix = evidenceSuffix(original);
            if (value.evidencePath().startsWith(postStagePrefix)) {
                entry = postStageEntries.get(original);
                if (!value.evidencePath().substring(postStagePrefix.length()).equals(expectedSuffix)) {
                    throw new MigrationException("Asset Adoption Post-Stage Evidence Path Is Not Bound To Its Original Path: "
                        + value.originalPath());
                }
            } else if (value.evidencePath().startsWith(sourcePrefix)) {
                entry = sourceEntries.get(original);
                if (!value.evidencePath().substring(sourcePrefix.length()).equals(expectedSuffix)) {
                    throw new MigrationException("Asset Adoption Source Evidence Path Is Not Bound To Its Original Path: "
                        + value.originalPath());
                }
            } else {
                throw new MigrationException("Asset Adoption Evidence Path Is Not Bound To An Encoded Snapshot Manifest: "
                    + value.evidencePath());
            }
            if (entry == null) {
                throw new MigrationException("Asset Adoption Evidence Original Path Is Not In Its Encoded Snapshot Manifest: "
                    + value.originalPath());
            }
            if (!entry.owner().equals(value.owner()) || entry.size() != value.size()
                || !entry.sha256().equals(value.hash())) {
                throw new MigrationException("Asset Adoption Evidence Descriptor Does Not Match Its Encoded Snapshot Entry: "
                    + value.originalPath());
            }
            if (!durablePaths.add(value.evidencePath())
                || !foldedDurablePaths.add(value.evidencePath().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Evidence Contains A Duplicate Or Case-Aliased Durable Path: "
                    + value.evidencePath());
            }
        }
    }

    private static String evidenceManifestPath(String originalPath) throws MigrationException {
        String normalized = MigrationPaths.requireRelative(originalPath);
        String path = normalized.startsWith(ASSETS_PREFIX) || normalized.startsWith(".quarantine/migration/")
            ? normalized : ASSETS_PREFIX + normalized;
        if (path.equals(ASSETS_PREFIX) || !isAdoptionEvidencePath(path)) {
            throw new MigrationException("Asset Adoption Evidence Original Path Is Invalid: " + originalPath);
        }
        return path;
    }

    private static String evidenceSuffix(String manifestPath) throws MigrationException {
        String normalized = evidenceManifestPath(manifestPath);
        return normalized.startsWith(ASSETS_PREFIX)
            ? normalized.substring(ASSETS_PREFIX.length()) : normalized;
    }

    private static void persistEvidence(Path artifactRoot, List<EvidenceSource> sources,
                                        List<Evidence> evidence) throws IOException {
        Map<String, Evidence> byOriginal = evidence.stream().collect(Collectors.toMap(Evidence::originalPath,
            value -> value, (left, right) -> {
                throw new IllegalStateException("Duplicate evidence path");
            }, TreeMap::new));
        for (EvidenceSource source : sources.stream().sorted(Comparator.comparing(value -> value.entry().relativePath())).toList()) {
            String manifestPath = source.entry().relativePath();
            String original = manifestPath.startsWith(ASSETS_PREFIX)
                ? manifestPath.substring(ASSETS_PREFIX.length()) : manifestPath;
            Evidence descriptor = byOriginal.get(original);
            if (descriptor == null) {
                throw new MigrationException("Asset Adoption Evidence Descriptor Is Missing: " + manifestPath);
            }
            Path target = MigrationPaths.resolveInside(artifactRoot, descriptor.evidencePath());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(Files.readAllBytes(target), source.bytes())) {
                    throw new MigrationException("Asset Adoption Evidence Conflicts With A Durable Copy: " + manifestPath);
                }
            } else {
                AtomicFiles.writeNew(target, source.bytes());
            }
        }
        verifyDurableEvidence(artifactRoot, evidence);
    }

    private static void verifyDurableEvidence(Path artifactRoot, List<Evidence> evidence) throws IOException {
        Set<String> paths = new HashSet<>();
        Set<String> foldedPaths = new HashSet<>();
        for (Evidence value : evidence) {
            if (!paths.add(value.evidencePath()) || !foldedPaths.add(value.evidencePath().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Evidence Contains A Duplicate Or Case-Aliased Path: "
                    + value.evidencePath());
            }
            Path target = MigrationPaths.resolveInside(artifactRoot, value.evidencePath());
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Asset Adoption Durable Evidence Is Not A Regular File: " + value.evidencePath());
            }
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length != value.size() || !sha256(bytes).equals(value.hash())) {
                throw new MigrationException("Asset Adoption Durable Evidence Hash Does Not Match: " + value.evidencePath());
            }
        }
    }

    private static void persistArtifact(Path path, byte[] artifact) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(Files.readAllBytes(path), artifact)) {
                throw new MigrationException("Asset Coordinator Adoption Artifact Conflicts With Durable Authority");
            }
            return;
        }
        AtomicFiles.writeNew(path, artifact);
    }

    private static byte[] artifactBytes(Snapshot preStage, Snapshot postStage, StageOutput stage,
                                        Inventory inventory, List<Evidence> evidence) {
        return artifactBytes(preStage, postStage, stage.planHash(), stage.lifecycleOutputs(),
            stage.quarantineReport(), stage.acceptance(), inventory, evidence);
    }

    private static byte[] artifactBytes(Snapshot preStage, Snapshot postStage, String planHash,
                                        List<LifecycleOutput> outputs, QuarantineReport quarantineReport,
                                        QuarantineAcceptance acceptance, Inventory inventory,
                                        List<Evidence> evidence) {
        JsonValue.JsonObject payload = object(
            "format", FORMAT,
            "sourceAdmission", admissionJson(preStage),
            "postStage", admissionJson(postStage),
            "sourceManifestHash", preStage.manifest().manifestHash(),
            "manifestHash", postStage.manifest().manifestHash(),
            "planHash", planHash,
            "provenance", inventory.provenance(),
            "project", encode(inventory.projectJson().getBytes(StandardCharsets.UTF_8)),
            "outputs", outputsJson(outputs),
            "assets", assetsJson(inventory.assets()),
            "blocked", blockedJson(inventory.blocked()),
            "quarantine", quarantineJson(quarantineReport, acceptance),
            "evidence", evidenceJson(evidence));
        String artifactHash = sha256(payload.canonicalBytes());
        Map<String, JsonValue> values = new LinkedHashMap<>(payload.fields());
        values.put("artifactHash", JsonValue.of(artifactHash));
        return JsonValue.object(values).canonicalBytes();
    }

    private static Snapshot emptySnapshot(Path root, String proofHash) {
        SnapshotMetadata metadata = new SnapshotMetadata(1, "fresh-root-" + proofHash.substring(0, 16),
            Instant.EPOCH, "fresh-root", "0".repeat(64), Map.of());
        SnapshotManifest manifest = new SnapshotManifest(metadata, List.of(), List.of());
        SnapshotVerification verification = new SnapshotVerification(true, manifest.manifestHash(), List.of());
        return new Snapshot(root, root.resolve(".manifest"), root.resolve(".state"), metadata, manifest,
            SnapshotState.VERIFIED, verification);
    }

    private static JsonValue admissionJson(Snapshot snapshot) {
        return object(
            "snapshotId", snapshot.metadata().snapshotId(),
            "manifestHash", snapshot.manifest().manifestHash(),
            "metadata", metadataJson(snapshot.metadata()),
            "manifest", encode(snapshot.manifest().canonicalBytes()),
            "state", stateText(snapshot));
    }

    private static JsonValue metadataJson(SnapshotMetadata metadata) {
        Map<String, JsonValue> extensions = new TreeMap<>();
        metadata.extensionVersions().forEach((key, value) -> extensions.put(key, JsonValue.of(value)));
        return object(
            "formatVersion", metadata.formatVersion(),
            "snapshotId", metadata.snapshotId(),
            "createdAt", metadata.createdAt().toString(),
            "build", metadata.build(),
            "catalogChecksum", metadata.catalogChecksum(),
            "extensionVersions", JsonValue.object(extensions));
    }

    private static String stateText(Snapshot snapshot) {
        return "state=" + snapshot.state().name() + "\nverified=" + snapshot.verification().verified()
            + "\nmanifest-hash=" + snapshot.verification().manifestHash() + "\nfailures="
            + snapshot.verification().failures().size() + "\n"
            + snapshot.verification().failures().stream().map(value -> "failure=" + value + "\n").reduce("", String::concat);
    }

    private static JsonValue outputsJson(List<LifecycleOutput> outputs) {
        return JsonValue.array(outputs.stream().map(output -> object(
            "adapterId", output.adapterId(),
            "owner", output.owner(),
            "claims", JsonValue.array(output.claims().stream().map(claim -> object(
                "path", claim.relativePath(), "owner", claim.owner())).toList()),
            "changes", JsonValue.array(output.changes().stream().map(change -> object(
                "kind", change.kind(), "sourcePath", optionalValue(change.sourcePath()),
                "targetPath", optionalValue(change.targetPath()),
                "sourceHash", optionalValue(change.sourceHash()), "targetHash", optionalValue(change.targetHash()),
                "owner", change.owner())).toList()),
            "assets", JsonValue.array(output.assets().stream().map(asset -> object(
                "relativePath", asset.relativePath(), "owner", asset.owner(), "type", asset.type(),
                "id", asset.id(), "state", asset.state(), "revision", asset.revision(),
                "mutationId", asset.mutationId(), "payloadHash", asset.payloadHash(),
                "path", normalizeAssetPath(asset.assetPath()), "deletion", deletionJson(asset.deletion()))).toList()))).toList());
    }

    private static JsonValue deletionJson(LegacyDeletion deletion) {
        if (deletion == null) {
            return JsonValue.NULL;
        }
        return object(
            "priorPayloadHash", nullable(deletion.priorPayloadHash()),
            "originalLogicalPath", nullable(deletion.originalLogicalPath()),
            "canonicalFuturePath", nullable(deletion.canonicalFuturePath()),
            "tombstoneEvidenceHash", deletion.tombstoneEvidenceHash(), "owner", deletion.owner(),
            "evidenceKey", deletion.evidenceKey(), "revision", deletion.revision(),
            "mutationId", deletion.mutationId(), "auxiliaryKey", object(
                "family", deletion.auxiliaryKey().family(), "type", deletion.auxiliaryKey().type(),
                "id", deletion.auxiliaryKey().id()));
    }

    private static JsonValue assetsJson(List<Asset> assets) {
        return JsonValue.array(assets.stream().map(asset -> {
            Map<String, JsonValue> value = new LinkedHashMap<>();
            value.put("owner", JsonValue.of(asset.owner()));
            value.put("type", JsonValue.of(asset.type()));
            value.put("id", JsonValue.of(asset.id()));
            value.put("path", JsonValue.of(normalizeAssetPath(asset.path())));
            value.put("state", JsonValue.of(asset.state().kind()));
            value.put("revision", JsonValue.of(asset.state().revision()));
            value.put("hash", JsonValue.of(asset.state().hash()));
            value.put("mutationId", JsonValue.of(asset.mutationId()));
            value.put("content", JsonValue.of(encode(asset.content())));
            if (asset.deletion() != null) {
                value.put("deletion", deletionJson(asset.deletion()));
            }
            return JsonValue.object(value);
        }).toList());
    }

    private static JsonValue blockedJson(List<BlockedState> blocked) {
        return JsonValue.array(blocked.stream().map(value -> object(
            "owner", value.owner(), "evidenceKey", value.evidenceKey(), "evidenceHash", value.evidenceHash(),
            "sourcePath", nullableSnapshot(value.sourcePath()), "originalLogicalPath", nullable(value.originalLogicalPath()),
            "canonicalFuturePath", nullable(value.canonicalFuturePath()), "revision", value.revision(),
            "mutationId", value.mutationId(), "reason", value.reason(), "candidateType", nullable(value.candidateType()),
            "candidateId", nullable(value.candidateId()))).toList());
    }

    private static JsonValue quarantineJson(QuarantineReport report, QuarantineAcceptance acceptance) {
        return object(
            "reportHash", report.reportHash(),
            "canonical", report.canonicalText(),
            "records", JsonValue.array(report.records().stream().map(record -> object(
                "recordId", record.recordId(), "code", record.code(), "sourceLocation", record.sourceLocation(),
                "reason", record.reason(), "affectedReferences", JsonValue.array(record.affectedReferences().stream().map(JsonValue::of).toList()),
                "suggestedAction", record.suggestedAction(), "sourceHash", record.sourceHash())).toList()),
            "acceptance", acceptance == null ? JsonValue.NULL : object(
                "reportHash", acceptance.reportHash(), "acceptedRecordIds",
                JsonValue.array(acceptance.acceptedRecordIds().stream().map(JsonValue::of).toList()),
                "acceptedBy", acceptance.acceptedBy(), "acceptedAt", acceptance.acceptedAt().toString(),
                "acceptanceHash", acceptance.acceptanceHash()));
    }

    private static JsonValue evidenceJson(List<Evidence> evidence) {
        return JsonValue.array(evidence.stream().map(value -> object(
            "originalPath", value.originalPath(), "evidencePath", value.evidencePath(), "hash", value.hash(),
            "size", value.size(), "owner", value.owner())).toList());
    }

    private static Result decodeResult(Path artifactPath, byte[] bytes) throws IOException {
        try {
            JsonValue.JsonObject document = object(JsonValue.parse(bytes), "asset coordinator adoption artifact");
            String artifactHash = requiredText(document, "artifact", "artifactHash");
            Map<String, JsonValue> unhashed = new LinkedHashMap<>(document.fields());
            unhashed.remove("artifactHash");
            if (!FORMAT.equals(requiredText(document, "artifact", "format"))
                || !artifactHash.equals(sha256(JsonValue.object(unhashed).canonicalBytes()))
                || !Arrays.equals(bytes, document.canonicalBytes())) {
                throw new MigrationException("Asset Coordinator Adoption Artifact Hash Or Canonical Form Is Invalid");
            }
            JsonValue.JsonObject sourceAdmission = object(requiredObject(document, "sourceAdmission"), "sourceAdmission");
            JsonValue.JsonObject postStage = object(requiredObject(document, "postStage"), "postStage");
            String sourceManifestHash = requiredText(document, "artifact", "sourceManifestHash");
            String postStageManifestHash = requiredText(document, "artifact", "manifestHash");
            String planHash = requiredText(document, "artifact", "planHash");
            requireDigest(sourceManifestHash, "sourceManifestHash");
            requireDigest(postStageManifestHash, "manifestHash");
            requireDigest(planHash, "planHash");
            String sourceSnapshotId = requiredText(sourceAdmission, "source admission", "snapshotId");
            String postStageSnapshotId = requiredText(postStage, "post-stage admission", "snapshotId");
            SnapshotManifest sourceSnapshotManifest = validateAdmission(sourceAdmission, sourceManifestHash,
                sourceSnapshotId, "source admission");
            SnapshotManifest postStageSnapshotManifest = validateAdmission(postStage, postStageManifestHash,
                postStageSnapshotId, "post-stage admission");
            Inventory inventory = decodeInventory(document);
            List<LifecycleOutput> outputs = decodeOutputs(requiredArray(document, "outputs"));
            List<QuarantineRecord> quarantine = decodeQuarantine(requiredObject(document, "quarantine"));
            QuarantineAcceptance acceptance = decodeAcceptance(requiredObject(document, "quarantine"), quarantine);
            List<Evidence> evidence = decodeEvidence(requiredArray(document, "evidence"));
            Map<String, SnapshotManifest.Entry> sourceEntries = entries(sourceSnapshotManifest);
            Map<String, SnapshotManifest.Entry> postEntries = entries(postStageSnapshotManifest);
            Set<String> quarantinePaths = validateQuarantineEvidence(sourceEntries, postEntries,
                new QuarantineReport(quarantine));
            BlockedValidation blockedValidation = validateBlocked(postEntries, inventory.blocked());
            validateBlockedLineage(sourceEntries, postEntries, blockedValidation);
            validateLifecycleClaims(postEntries, outputs, blockedValidation.bySourcePath());
            validateLifecycleChanges(sourceEntries, postEntries, outputs);
            validateExhaustiveDelta(sourceEntries, postEntries, outputs, blockedValidation.bySourcePath());
            validateEvidenceDescriptors(sourceSnapshotManifest, postStageSnapshotManifest, evidence);
            validateInventoryAuthority(inventory, outputs, evidence);
            validatePostStageBindings(postStageSnapshotManifest, inventory, outputs, evidence, quarantinePaths);
            return new Result(artifactPath, artifactHash, sourceManifestHash, postStageManifestHash, planHash,
                requiredText(sourceAdmission, "source admission", "manifest"),
                requiredText(sourceAdmission, "source admission", "state"),
                object(sourceAdmission.value("metadata"), "source metadata").canonicalText(),
                requiredText(postStage, "post-stage admission", "manifest"),
                requiredText(postStage, "post-stage admission", "state"),
                object(postStage.value("metadata"), "post-stage metadata").canonicalText(),
                inventory, outputs, quarantine, acceptance, evidence);
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Asset Coordinator Adoption Artifact Is Structurally Invalid", exception);
        }
    }

    private static void validateInventoryAuthority(Inventory inventory, List<LifecycleOutput> outputs,
                                                   List<Evidence> evidence) throws MigrationException {
        LifecycleAuthority authority = lifecycleAuthority(outputs);
        Map<String, Evidence> evidenceByOriginal = new TreeMap<>();
        Set<String> foldedEvidence = new HashSet<>();
        for (Evidence value : evidence) {
            String original = evidenceManifestPath(value.originalPath());
            if (evidenceByOriginal.put(original, value) != null
                || !foldedEvidence.add(original.toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Artifact Contains Duplicate Or Case-Aliased Evidence: "
                    + value.originalPath());
            }
        }
        Set<String> identities = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<String> materializedRecordIdentities = new HashSet<>();
        for (Asset asset : inventory.assets()) {
            String assetIdentity = key(asset.type(), asset.id()).toLowerCase(Locale.ROOT);
            if (!identities.add(assetIdentity)) {
                throw new MigrationException("Asset Adoption Artifact Contains A Duplicate Asset Identity: " + asset.canonicalKey());
            }
            requirePathUnique(asset.path().toString(), paths);
            AssetRecord record = authority.records().values().stream()
                .filter(value -> value.type().equals(asset.type()) && value.id().equals(asset.id())).findFirst().orElse(null);
            if (record == null || !record.owner().equals(asset.owner()) || !record.assetPath().equals(asset.path())
                || !record.state().equals(asset.state().kind()) || record.revision() != asset.state().revision()
                || !record.mutationId().equals(asset.mutationId()) || !record.payloadHash().equals(asset.state().hash())
                || !Objects.equals(record.deletion(), asset.deletion())) {
                throw new MigrationException("Asset Adoption Artifact Asset Is Not Bound To Its Lifecycle Output: "
                    + asset.canonicalKey());
            }
            materializedRecordIdentities.add(assetIdentity);
            if (asset.state().kind().equals("LIVE")) {
                if (!sha256(asset.content()).equals(asset.state().hash())) {
                    throw new MigrationException("Asset Adoption Artifact Live Payload Hash Does Not Match: " + asset.canonicalKey());
                }
                continue;
            }
            LegacyDeletion deletion = Objects.requireNonNull(asset.deletion(), "deleted adoption deletion");
            Evidence tombstone = evidenceByOriginal.get(evidenceManifestPath(deletion.evidenceKey()));
            if (tombstone == null || !tombstone.hash().equals(deletion.tombstoneEvidenceHash())) {
                throw new MigrationException("Asset Adoption Artifact Deleted Provenance Is Not Bound To Durable Evidence: "
                    + asset.canonicalKey());
            }
        }
        for (AssetRecord record : authority.records().values()) {
            if (!materializedRecordIdentities.contains(key(record.type(), record.id()).toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Artifact Dropped A Lifecycle Asset Record: " + record.relativePath());
            }
        }
        for (BlockedState blocked : inventory.blocked()) {
            if (blocked.candidateType() != null
                && identities.contains(key(blocked.candidateType(), blocked.candidateId()).toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Asset Adoption Artifact Reuses An Asset Identity For Blocked State: "
                    + blocked.candidateType() + "/" + blocked.candidateId());
            }
            String source = blocked.sourcePath() == null ? blocked.evidenceKey() : normalizeSnapshotPath(blocked.sourcePath());
            if (!isAdoptionEvidencePath(source)) {
                throw new MigrationException("Asset Adoption Artifact Blocked Evidence Is Outside The Supported Roots: " + source);
            }
            Evidence item = evidenceByOriginal.get(evidenceManifestPath(source));
            if (item == null || !item.hash().equals(blocked.evidenceHash())) {
                throw new MigrationException("Asset Adoption Artifact Blocked Evidence Is Not Durable: " + source);
            }
        }
        try {
            projectJson(inventory.projectJson().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new MigrationException("Asset Adoption Artifact Project JSON Is Invalid", exception);
        }
    }

    private static void validatePostStageBindings(SnapshotManifest postStageManifest, Inventory inventory,
                                                  List<LifecycleOutput> outputs, List<Evidence> evidence,
                                                  Set<String> quarantinePaths)
        throws MigrationException {
        Map<String, SnapshotManifest.Entry> entries = entries(postStageManifest);
        LifecycleAuthority authority = lifecycleAuthority(outputs);
        Map<String, Evidence> evidenceByOriginal = new TreeMap<>();
        for (Evidence value : evidence) {
            evidenceByOriginal.put(evidenceManifestPath(value.originalPath()), value);
        }
        Map<String, BlockedState> blockedByPath = new TreeMap<>();
        for (BlockedState value : inventory.blocked()) {
            String path = sourcePath(value, entries);
            if (!isAdoptionEvidencePath(path)) {
                throw new MigrationException("Asset Adoption Artifact Blocked Evidence Is Outside The Supported Roots: " + path);
            }
            SnapshotManifest.Entry entry = entries.get(path);
            if (entry == null || !entry.owner().equals(value.owner()) || !entry.sha256().equals(value.evidenceHash())) {
                throw new MigrationException("Asset Adoption Artifact Blocked Evidence Is Not Bound To The Post-Stage Snapshot: "
                    + path);
            }
            blockedByPath.put(path, value);
        }
        String postEvidencePrefix = "evidence/adoption-v1/" + postStageManifest.manifestHash() + "/";
        for (Evidence value : evidence) {
            if (!value.evidencePath().startsWith(postEvidencePrefix)) {
                continue;
            }
            String path = evidenceManifestPath(value.originalPath());
            if (path.equals(PROJECT_PATH) || !path.startsWith(TOMBSTONE_PREFIX)
                && !path.startsWith(CONTROL_PREFIX) && !blockedByPath.containsKey(path)
                && !quarantinePaths.contains(path)) {
                throw new MigrationException("Asset Adoption Post-Stage Evidence Is Not Bound To Retained Evidence: "
                    + path);
            }
        }
        for (SnapshotManifest.Entry entry : entries.values()) {
            if (entry.relativePath().startsWith(QUARANTINE_PREFIX)
                && !evidenceByOriginal.containsKey(evidenceManifestPath(entry.relativePath()))) {
                throw new MigrationException("Asset Adoption Quarantine Evidence Has No Durable Descriptor: "
                    + entry.relativePath());
            }
            BlockedState blockedState = blockedByPath.get(entry.relativePath());
            if (blockedState != null && !entry.relativePath().startsWith(ASSETS_PREFIX)) {
                if (!entry.owner().equals(blockedState.owner()) || authority.binding(entry.relativePath()) != null
                    || authority.record(entry.relativePath()) != null
                    || !evidenceByOriginal.containsKey(evidenceManifestPath(entry.relativePath()))) {
                    throw new MigrationException("Asset Adoption Blocked Evidence Has An Invalid Lifecycle Binding: "
                        + entry.relativePath());
                }
                continue;
            }
            if (!entry.relativePath().startsWith(ASSETS_PREFIX)) {
                continue;
            }
            LifecycleBinding binding = authority.binding(entry.relativePath());
            if (binding == null || !binding.owner().equals(entry.owner())) {
                throw new MigrationException("Asset Adoption Post-Stage Path Has No Exact Lifecycle Binding: "
                    + entry.relativePath());
            }
            if (entry.relativePath().equals(PROJECT_PATH)) {
                if (authority.record(entry.relativePath()) != null) {
                    throw new MigrationException("Asset Adoption Project Path Cannot Have An Asset Record");
                }
                continue;
            }
            AssetRecord record = authority.record(entry.relativePath());
            boolean blocked = blockedState != null;
            boolean tombstone = entry.relativePath().startsWith(TOMBSTONE_PREFIX);
            boolean control = entry.relativePath().startsWith(CONTROL_PREFIX);
            if (blocked || tombstone || control) {
                if (!evidenceByOriginal.containsKey(evidenceManifestPath(entry.relativePath()))) {
                    throw new MigrationException("Asset Adoption Post-Stage Evidence Has No Durable Descriptor: "
                        + entry.relativePath());
                }
                if (blocked || control) {
                    if (record != null) {
                        throw new MigrationException("Asset Adoption Evidence Cannot Have An Asset Record: "
                            + entry.relativePath());
                    }
                } else if (record == null || !record.state().equals("DELETED")) {
                    throw new MigrationException("Asset Adoption Tombstone Has No Exact Deleted Lifecycle Record: "
                        + entry.relativePath());
                } else if (!record.relativePath().equals(entry.relativePath()) || !record.owner().equals(entry.owner())
                    || !record.payloadHash().equals(entry.sha256())) {
                    throw new MigrationException("Asset Adoption Tombstone Record Is Not Bound To Its Snapshot Entry: "
                        + entry.relativePath());
                } else {
                    String target = ASSETS_PREFIX + record.deletion().canonicalFuturePath();
                    if (containsPathFolded(entries, target)
                        || record.deletion().originalLogicalPath() != null
                        && containsPathFolded(entries,
                            ASSETS_PREFIX + record.deletion().originalLogicalPath())) {
                        throw new MigrationException("Asset Adoption Deleted Path Is Still Materialized: "
                            + entry.relativePath());
                    }
                }
                continue;
            }
            if (record == null || !record.state().equals("LIVE") || !record.relativePath().equals(entry.relativePath())
                || !record.owner().equals(entry.owner()) || !record.payloadHash().equals(entry.sha256())
                || !normalizeAssetPath(record.assetPath()).equals(entry.relativePath().substring(ASSETS_PREFIX.length()))) {
                throw new MigrationException("Asset Adoption Live Path Has No Exact Live Lifecycle Record: "
                    + entry.relativePath());
            }
        }
    }

    private static SnapshotManifest validateAdmission(JsonValue.JsonObject admission, String expectedManifestHash,
                                                     String expectedSnapshotId, String label) throws MigrationException {
        String admissionManifestHash = requiredText(admission, label, "manifestHash");
        if (!admissionManifestHash.equals(expectedManifestHash)) {
            throw new MigrationException("Asset Adoption " + label + " Manifest Hash Does Not Match Its Envelope");
        }
        JsonValue.JsonObject metadataValue = object(requiredObject(admission, "metadata"), label + " metadata");
        SnapshotMetadata metadata = decodeMetadata(metadataValue, label + " metadata");
        if (!metadata.snapshotId().equals(expectedSnapshotId)) {
            throw new MigrationException("Asset Adoption " + label + " Snapshot ID Does Not Match Its Metadata");
        }
        byte[] manifestBytes = decode(requiredText(admission, label, "manifest"));
        String manifestText = decodeUtf8(manifestBytes);
        SnapshotManifest manifest = decodeManifest(manifestText, label + " manifest");
        validateManifestAliases(manifest);
        if (!manifest.manifestHash().equals(expectedManifestHash) || !manifest.metadata().equals(metadata)) {
            throw new MigrationException("Asset Adoption " + label + " Manifest Does Not Match Its Metadata Or Hash");
        }
        validateState(requiredText(admission, label, "state"), expectedManifestHash, label);
        return manifest;
    }

    private static SnapshotMetadata decodeMetadata(JsonValue.JsonObject value, String label) throws MigrationException {
        long format = requiredLong(value, label, "formatVersion");
        if (format > Integer.MAX_VALUE) {
            throw new MigrationException("Snapshot Metadata Format Version Is Too Large: " + label);
        }
        Map<String, String> extensions = new TreeMap<>();
        JsonValue.JsonObject extensionValues = object(requiredObject(value, "extensionVersions"), label + " extensions");
        for (Map.Entry<String, JsonValue> entry : extensionValues.fields().entrySet()) {
            String previous = extensions.put(entry.getKey(), text(entry.getValue(), label + " extension"));
            if (previous != null) {
                throw new MigrationException("Duplicate Snapshot Metadata Extension: " + entry.getKey());
            }
        }
        try {
            return new SnapshotMetadata((int) format,
                requiredText(value, label, "snapshotId"),
                Instant.parse(requiredText(value, label, "createdAt")),
                requiredText(value, label, "build"),
                requiredText(value, label, "catalogChecksum"), extensions);
        } catch (RuntimeException exception) {
            throw new MigrationException("Snapshot Metadata Is Invalid: " + label, exception);
        }
    }

    private static SnapshotManifest decodeManifest(String text, String label) throws MigrationException {
        String[] lines = text.split("\\n", -1);
        if (lines.length == 0 || !lines[lines.length - 1].isEmpty()) {
            throw new MigrationException("Snapshot Manifest Is Not Canonical: " + label);
        }
        int cursor = 0;
        int format = parseManifestInt(lines, cursor++, "format=", label);
        String snapshotId = decodeManifestText(lines, cursor++, "snapshot-id=", label);
        long createdAt = parseManifestLong(lines, cursor++, "created-at=", label);
        String build = decodeManifestText(lines, cursor++, "build=", label);
        String catalog = manifestLine(lines, cursor++, "catalog=", label);
        int extensionCount = parseManifestInt(lines, cursor++, "extensions=", label);
        Map<String, String> extensions = new TreeMap<>();
        for (int index = 0; index < extensionCount; index++) {
            String row = manifestLine(lines, cursor++, "extension=", label);
            String[] fields = row.split("\\|", -1);
            if (fields.length != 2) {
                throw new MigrationException("Snapshot Manifest Extension Row Is Invalid: " + label);
            }
            String owner = decodeManifestText(fields[0], label + " extension owner");
            String version = decodeManifestText(fields[1], label + " extension version");
            if (extensions.put(owner, version) != null) {
                throw new MigrationException("Snapshot Manifest Contains A Duplicate Extension: " + owner);
            }
        }
        int directoryCount = parseManifestInt(lines, cursor++, "directories=", label);
        List<String> directories = new ArrayList<>();
        for (int index = 0; index < directoryCount; index++) {
            directories.add(decodeManifestText(lines, cursor++, "directory=", label));
        }
        int fileCount = parseManifestInt(lines, cursor++, "files=", label);
        List<SnapshotManifest.Entry> entries = new ArrayList<>();
        for (int index = 0; index < fileCount; index++) {
            String row = manifestLine(lines, cursor++, "file=", label);
            String[] fields = row.split("\\|", -1);
            if (fields.length != 4) {
                throw new MigrationException("Snapshot Manifest File Row Is Invalid: " + label);
            }
            long size;
            try {
                size = Long.parseLong(fields[1]);
            } catch (NumberFormatException exception) {
                throw new MigrationException("Snapshot Manifest File Size Is Invalid: " + label, exception);
            }
            try {
                entries.add(new SnapshotManifest.Entry(decodeManifestText(fields[0], label + " file path"), size,
                    fields[2], decodeManifestText(fields[3], label + " file owner")));
            } catch (RuntimeException exception) {
                throw new MigrationException("Snapshot Manifest File Entry Is Invalid: " + label, exception);
            }
        }
        if (cursor != lines.length - 1) {
            throw new MigrationException("Snapshot Manifest Contains Unexpected Content: " + label);
        }
        try {
            SnapshotManifest manifest = new SnapshotManifest(new SnapshotMetadata(format, snapshotId,
                Instant.ofEpochMilli(createdAt), build, catalog, extensions), directories, entries);
            if (!manifest.canonicalText().equals(text)) {
                throw new MigrationException("Snapshot Manifest Is Not Canonical: " + label);
            }
            return manifest;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Snapshot Manifest Is Invalid: " + label, exception);
        }
    }

    private static String manifestLine(String[] lines, int index, String prefix, String label) throws MigrationException {
        if (index >= lines.length - 1 || !lines[index].startsWith(prefix)) {
            throw new MigrationException("Snapshot Manifest Line Is Missing: " + label + " " + prefix);
        }
        return lines[index].substring(prefix.length());
    }

    private static String decodeManifestText(String[] lines, int index, String prefix, String label)
        throws MigrationException {
        return decodeManifestText(manifestLine(lines, index, prefix, label), label);
    }

    private static String decodeManifestText(String value, String label) throws MigrationException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            String decoded = decodeUtf8(bytes);
            if (!encode(decoded.getBytes(StandardCharsets.UTF_8)).equals(value)) {
                throw new MigrationException("Snapshot Manifest Text Is Not Canonical: " + label);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Snapshot Manifest Text Is Invalid: " + label, exception);
        }
    }

    private static int parseManifestInt(String[] lines, int index, String prefix, String label) throws MigrationException {
        try {
            int value = Integer.parseInt(manifestLine(lines, index, prefix, label));
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Snapshot Manifest Number Is Invalid: " + label, exception);
        }
    }

    private static long parseManifestLong(String[] lines, int index, String prefix, String label) throws MigrationException {
        try {
            long value = Long.parseLong(manifestLine(lines, index, prefix, label));
            if (value < 0L) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Snapshot Manifest Number Is Invalid: " + label, exception);
        }
    }

    private static void validateState(String state, String manifestHash, String label) throws MigrationException {
        String[] lines = state.split("\\n", -1);
        if (lines.length < 5 || !lines[0].equals("state=VERIFIED") || !lines[1].equals("verified=true")
            || !lines[2].equals("manifest-hash=" + manifestHash) || !lines[3].equals("failures=0")
            || !(lines.length == 5 && lines[4].isEmpty())) {
            throw new MigrationException("Snapshot State Is Not An Exact Verified Binding: " + label);
        }
    }

    private static Inventory decodeInventory(JsonValue.JsonObject document) throws MigrationException {
        String provenance = requiredText(document, "artifact", "provenance");
        String project = decodeUtf8(decode(requiredText(document, "artifact", "project")));
        List<Asset> assets = new ArrayList<>();
        for (JsonValue value : requiredArray(document, "assets").values()) {
            assets.add(decodeAsset(object(value, "asset")));
        }
        List<BlockedState> blocked = new ArrayList<>();
        for (JsonValue value : requiredArray(document, "blocked").values()) {
            blocked.add(decodeBlocked(object(value, "blocked")));
        }
        return new Inventory(provenance, project, assets, blocked);
    }

    private static Asset decodeAsset(JsonValue.JsonObject value) throws MigrationException {
        String owner = requiredText(value, "asset", "owner");
        String type = requiredText(value, "asset", "type");
        String id = requiredText(value, "asset", "id");
        Path path = Path.of(requiredText(value, "asset", "path"));
        String state = requiredText(value, "asset", "state");
        long revision = requiredLong(value, "asset", "revision");
        String hash = requiredText(value, "asset", "hash");
        String mutation = requiredText(value, "asset", "mutationId");
        byte[] content = decode(requiredText(value, "asset", "content"));
        LegacyDeletion deletion = value.value("deletion") == null || value.value("deletion") instanceof JsonValue.JsonNull
            ? null : decodeDeletion(object(value.value("deletion"), "deletion"));
        return new Asset(owner, type, id, path, new AssetState(state, revision, hash), mutation, content, deletion);
    }

    private static LegacyDeletion decodeDeletion(JsonValue.JsonObject value) throws MigrationException {
        return new LegacyDeletion(optionalText(value, "priorPayloadHash"), optionalPath(value, "originalLogicalPath"),
            optionalPath(value, "canonicalFuturePath"), requiredText(value, "deletion", "tombstoneEvidenceHash"),
            requiredText(value, "deletion", "owner"), requiredText(value, "deletion", "evidenceKey"),
            requiredLong(value, "deletion", "revision"), requiredText(value, "deletion", "mutationId"),
            decodeAuxiliaryKey(requiredObject(value, "auxiliaryKey")));
    }

    private static AuxiliaryKey decodeAuxiliaryKey(JsonValue.JsonObject value) throws MigrationException {
        return new AuxiliaryKey(requiredText(value, "auxiliary key", "family"),
            requiredText(value, "auxiliary key", "type"), requiredText(value, "auxiliary key", "id"));
    }

    private static BlockedState decodeBlocked(JsonValue.JsonObject value) throws MigrationException {
        return new BlockedState(requiredText(value, "blocked", "owner"), requiredText(value, "blocked", "evidenceKey"),
            optionalText(value, "evidenceHash"), optionalSnapshotPath(value, "sourcePath"), optionalPath(value, "originalLogicalPath"),
            optionalPath(value, "canonicalFuturePath"), requiredLong(value, "blocked", "revision"),
            optionalText(value, "mutationId"), requiredText(value, "blocked", "reason"),
            optionalText(value, "candidateType"), optionalText(value, "candidateId"));
    }

    private static List<LifecycleOutput> decodeOutputs(JsonValue.JsonArray values) throws MigrationException {
        List<LifecycleOutput> outputs = new ArrayList<>();
        for (JsonValue value : values.values()) {
            JsonValue.JsonObject object = object(value, "lifecycle output");
            List<Claim> claims = new ArrayList<>();
            for (JsonValue claim : requiredArray(object, "claims").values()) {
                JsonValue.JsonObject item = object(claim, "claim");
                claims.add(new Claim(requiredText(item, "claim", "path"), requiredText(item, "claim", "owner")));
            }
            List<AcceptedChange> changes = new ArrayList<>();
            for (JsonValue change : requiredArray(object, "changes").values()) {
                JsonValue.JsonObject item = object(change, "change");
                changes.add(new AcceptedChange(requiredText(item, "change", "kind"), optionalText(item, "sourcePath"),
                    optionalText(item, "targetPath"), optionalText(item, "sourceHash"), optionalText(item, "targetHash"),
                    requiredText(item, "change", "owner")));
            }
            List<AssetRecord> assets = new ArrayList<>();
            for (JsonValue asset : requiredArray(object, "assets").values()) {
                assets.add(decodeAssetRecord(object(asset, "lifecycle asset")));
            }
            outputs.add(new LifecycleOutput(requiredText(object, "lifecycle output", "adapterId"),
                requiredText(object, "lifecycle output", "owner"), claims, changes, assets));
        }
        return List.copyOf(outputs);
    }

    private static AssetRecord decodeAssetRecord(JsonValue.JsonObject value) throws MigrationException {
        JsonValue deletionValue = value.value("deletion");
        LegacyDeletion deletion = deletionValue == null || deletionValue instanceof JsonValue.JsonNull
            ? null : decodeDeletion(object(deletionValue, "lifecycle deletion"));
        return new AssetRecord(requiredText(value, "lifecycle asset", "relativePath"),
            requiredText(value, "lifecycle asset", "owner"), requiredText(value, "lifecycle asset", "type"),
            requiredText(value, "lifecycle asset", "id"), requiredText(value, "lifecycle asset", "state"),
            requiredLong(value, "lifecycle asset", "revision"), requiredText(value, "lifecycle asset", "mutationId"),
            requiredText(value, "lifecycle asset", "payloadHash"),
            Path.of(requiredText(value, "lifecycle asset", "path")), deletion);
    }

    private static List<QuarantineRecord> decodeQuarantine(JsonValue.JsonObject value) throws MigrationException {
        List<QuarantineRecord> records = new ArrayList<>();
        for (JsonValue record : requiredArray(value, "records").values()) {
            JsonValue.JsonObject item = object(record, "quarantine record");
            List<String> affected = new ArrayList<>();
            for (JsonValue member : requiredArray(item, "affectedReferences").values()) {
                affected.add(text(member, "affected reference"));
            }
            records.add(new QuarantineRecord(requiredText(item, "quarantine record", "recordId"),
                requiredText(item, "quarantine record", "code"), requiredText(item, "quarantine record", "sourceLocation"),
                requiredText(item, "quarantine record", "reason"), affected,
                requiredText(item, "quarantine record", "suggestedAction"), optionalText(item, "sourceHash")));
        }
        return List.copyOf(records);
    }

    private static QuarantineAcceptance decodeAcceptance(JsonValue.JsonObject value, List<QuarantineRecord> records) throws MigrationException {
        JsonValue acceptanceValue = value.value("acceptance");
        if (acceptanceValue == null || acceptanceValue instanceof JsonValue.JsonNull) {
            return null;
        }
        JsonValue.JsonObject acceptance = object(acceptanceValue, "quarantine acceptance");
        List<String> ids = new ArrayList<>();
        for (JsonValue member : requiredArray(acceptance, "acceptedRecordIds").values()) {
            ids.add(text(member, "accepted record id"));
        }
        String reportHash = requiredText(acceptance, "quarantine acceptance", "reportHash");
        QuarantineReport report = new QuarantineReport(records);
        if (!report.reportHash().equals(reportHash)) {
            throw new MigrationException("Asset Adoption Quarantine Acceptance Does Not Match Its Report");
        }
        return new QuarantineAcceptance(reportHash, ids, requiredText(acceptance, "quarantine acceptance", "acceptedBy"),
            Instant.parse(requiredText(acceptance, "quarantine acceptance", "acceptedAt")),
            requiredText(acceptance, "quarantine acceptance", "acceptanceHash"));
    }

    private static List<Evidence> decodeEvidence(JsonValue.JsonArray values) throws MigrationException {
        List<Evidence> evidence = new ArrayList<>();
        for (JsonValue value : values.values()) {
            JsonValue.JsonObject item = object(value, "evidence");
            evidence.add(new Evidence(requiredText(item, "evidence", "originalPath"), requiredText(item, "evidence", "evidencePath"),
                requiredText(item, "evidence", "hash"), requiredLong(item, "evidence", "size"),
                requiredText(item, "evidence", "owner")));
        }
        return List.copyOf(evidence);
    }

    private static byte[] readVerified(Path root, SnapshotManifest.Entry entry) throws IOException {
        Path path = MigrationPaths.resolveInside(root, entry.relativePath());
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Asset Adoption Source Is Not A Regular File: " + entry.relativePath());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != entry.size() || !sha256(bytes).equals(entry.sha256())) {
            throw new MigrationException("Asset Adoption Source Changed After Snapshot Verification: " + entry.relativePath());
        }
        return bytes;
    }

    private static String projectJson(byte[] bytes) throws MigrationException {
        try {
            JsonValue value = CanonicalCodec.decodePermissive(bytes);
            if (!(value instanceof JsonValue.JsonObject)) {
                throw new MigrationException("Asset Project Authority Must Be A JSON Object");
            }
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Asset Project Authority Is Invalid JSON", exception);
        }
        return decodeUtf8(bytes);
    }

    private static JsonValue.JsonObject object(JsonValue value, String label) throws MigrationException {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new MigrationException("Expected JSON Object: " + label);
        }
        return object;
    }

    private static JsonValue.JsonObject requiredObject(JsonValue.JsonObject object, String field) throws MigrationException {
        JsonValue value = object.value(field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            throw new MigrationException("Missing JSON Object Field: " + field);
        }
        return object(value, field);
    }

    private static JsonValue.JsonArray requiredArray(JsonValue.JsonObject object, String field) throws MigrationException {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new MigrationException("Missing JSON Array Field: " + field);
        }
        return array;
    }

    private static String requiredText(JsonValue.JsonObject object, String label, String... fields) throws MigrationException {
        for (String field : fields) {
            String value = optionalText(object, field);
            if (value != null) {
                return value;
            }
        }
        throw new MigrationException("Missing Text Field In " + label + ": " + String.join("/", fields));
    }

    private static String optionalText(JsonValue.JsonObject object, String field) throws MigrationException {
        JsonValue value = object.value(field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            return null;
        }
        if (!(value instanceof JsonValue.JsonString text) || text.value().isBlank()) {
            throw new MigrationException("Optional Text Field Is Invalid: " + field);
        }
        return text.value();
    }

    private static String text(JsonValue value, String label) throws MigrationException {
        if (!(value instanceof JsonValue.JsonString text)) {
            throw new MigrationException("Expected Text Value: " + label);
        }
        return text.value();
    }

    private static long requiredLong(JsonValue.JsonObject object, String label, String... fields) throws MigrationException {
        for (String field : fields) {
            JsonValue value = object.value(field);
            if (value instanceof JsonValue.JsonNumber number) {
                try {
                    long parsed = number.value().longValueExact();
                    if (parsed >= 0L) {
                        return parsed;
                    }
                } catch (ArithmeticException ignored) {
                }
            }
        }
        throw new MigrationException("Missing Non-Negative Integer Field In " + label + ": " + String.join("/", fields));
    }

    private static Path optionalPath(JsonValue.JsonObject object, String... fields) throws MigrationException {
        String value = null;
        for (String field : fields) {
            value = optionalText(object, field);
            if (value != null) {
                break;
            }
        }
        if (value == null) {
            return null;
        }
        try {
            return Path.of(normalizeAssetPath(Path.of(value)));
        } catch (RuntimeException exception) {
            throw new MigrationException("Invalid Asset Path", exception);
        }
    }

    private static Path optionalSnapshotPath(JsonValue.JsonObject object, String field) throws MigrationException {
        String value = optionalText(object, field);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(MigrationPaths.requireRelative(value));
        } catch (RuntimeException exception) {
            throw new MigrationException("Invalid Snapshot Evidence Path", exception);
        }
    }

    private static String normalizeAssetPath(Path path) {
        Objects.requireNonNull(path, "path");
        String value = path.toString().replace('\\', '/');
        if (value.startsWith(ASSETS_PREFIX)) {
            value = value.substring(ASSETS_PREFIX.length());
        }
        return MigrationPaths.requireRelative(value);
    }

    private static String normalizeSnapshotPath(Path path) {
        Objects.requireNonNull(path, "path");
        return MigrationPaths.requireRelative(path.toString().replace('\\', '/'));
    }

    private static void requireManagedPath(String relative) throws MigrationException {
        String safe;
        try {
            safe = MigrationPaths.requireRelative(relative);
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Asset Adoption Path Is Invalid: " + relative, exception);
        }
        String first = safe.contains("/") ? safe.substring(0, safe.indexOf('/')) : safe;
        if (RESERVED.contains(first) || safe.equals("project.json")) {
            throw new MigrationException("Asset Adoption Path Is Reserved: " + safe);
        }
    }

    private static String key(String type, String id) {
        return type.length() + ":" + type + id.length() + ":" + id;
    }

    private static JsonValue nullable(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Path path) {
            return JsonValue.of(normalizeAssetPath(path));
        }
        return JsonValue.of(value.toString());
    }

    private static JsonValue nullableSnapshot(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Path path) {
            return JsonValue.of(normalizeSnapshotPath(path));
        }
        return JsonValue.of(value.toString());
    }

    private static JsonValue optionalValue(String value) {
        return value == null ? JsonValue.NULL : JsonValue.of(value);
    }

    private static JsonValue.JsonObject object(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Canonical Object Values Must Be Paired");
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String key = Objects.requireNonNull((String) values[index], "object key");
            Object value = values[index + 1];
            result.put(key, value instanceof JsonValue json ? json : JsonValue.fromJava(value));
        }
        return JsonValue.object(result);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) throws MigrationException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (!encode(bytes).equals(value)) {
                throw new MigrationException("Asset Adoption Artifact Contains Non-Canonical Binary Data");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Asset Adoption Artifact Contains Invalid Binary Data", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (!Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)) {
                throw new MigrationException("Asset Adoption UTF-8 Data Is Not Canonical");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw new MigrationException("Asset Adoption Data Is Not Valid UTF-8", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static void requireDigest(String value, String field) throws MigrationException {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new MigrationException(field + " Must Be A SHA-256 Digest");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String requireIdentity(String value, String field, int maximumLength) {
        value = requireOpaque(value, field, maximumLength);
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String optionalIdentity(String value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " Cannot Be Blank");
        }
        return requireIdentity(value, field, maximumLength);
    }

    private static String requireOpaque(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String requireMutationId(String value, String field) {
        value = requireOpaque(value, field, 512);
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String optionalSort(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record StageOutput(String planHash, String sourceSnapshotId, String sourceManifestHash,
                              VerifiedSnapshotAdmission postStageAdmission, List<LifecycleOutput> lifecycleOutputs,
                              QuarantineReport quarantineReport, QuarantineAcceptance acceptance,
                              List<BlockedState> blocked) {
        public StageOutput {
            requireDigestUnchecked(planHash, "planHash");
            sourceSnapshotId = requireText(sourceSnapshotId, "sourceSnapshotId");
            requireDigestUnchecked(sourceManifestHash, "sourceManifestHash");
            postStageAdmission = Objects.requireNonNull(postStageAdmission, "postStageAdmission");
            List<LifecycleOutput> normalizedOutputs = new ArrayList<>(lifecycleOutputs == null ? List.of() : lifecycleOutputs);
            normalizedOutputs.sort(Comparator.comparing(LifecycleOutput::adapterId));
            Set<String> outputIds = new HashSet<>();
            normalizedOutputs.forEach(output -> {
                Objects.requireNonNull(output, "lifecycle output");
                if (!outputIds.add(output.adapterId())) {
                    throw new IllegalArgumentException("Duplicate Lifecycle Output: " + output.adapterId());
                }
            });
            lifecycleOutputs = List.copyOf(normalizedOutputs);
            quarantineReport = Objects.requireNonNull(quarantineReport, "quarantineReport");
            blocked = List.copyOf(blocked == null ? List.of() : blocked.stream()
                .sorted(Comparator.comparing(BlockedState::canonicalKey)).toList());
        }

        public void requireAccepted() throws IOException {
            quarantineReport.requireAccepted(acceptance);
        }
    }

    public record LifecycleOutput(String adapterId, String owner, List<Claim> claims, List<AcceptedChange> changes,
                                  List<AssetRecord> assets) {
        public LifecycleOutput(String adapterId, String owner, List<Claim> claims, List<AcceptedChange> changes) {
            this(adapterId, owner, claims, changes, List.of());
        }

        public LifecycleOutput {
            adapterId = requireText(adapterId, "adapterId");
            owner = requireIdentity(owner, "owner", 256);
            List<Claim> normalizedClaims = new ArrayList<>(claims == null ? List.of() : claims);
            normalizedClaims.sort(Comparator.comparing(Claim::relativePath).thenComparing(Claim::owner));
            Set<String> claimPaths = new HashSet<>();
            Set<String> foldedClaimPaths = new HashSet<>();
            for (Claim claim : normalizedClaims) {
                if (!owner.equals(claim.owner()) || !claimPaths.add(claim.relativePath())
                    || !foldedClaimPaths.add(claim.relativePath().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Lifecycle Claim Is Duplicate, Case-Aliased, Or Foreign: " + claim.relativePath());
                }
            }
            claims = List.copyOf(normalizedClaims);
            List<AcceptedChange> normalizedChanges = new ArrayList<>(changes == null ? List.of() : changes);
            normalizedChanges.sort(Comparator.comparing((AcceptedChange value) -> optionalSort(value.sourcePath()))
                .thenComparing(value -> optionalSort(value.targetPath()))
                .thenComparing(AcceptedChange::kind));
            Set<String> changeIdentities = new HashSet<>();
            for (AcceptedChange change : normalizedChanges) {
                if (!owner.equals(change.owner())) {
                    throw new IllegalArgumentException("Lifecycle Change Is Foreign To Its Output: " + change.kind());
                }
                String identity = optionalSort(change.sourcePath()) + "\n" + optionalSort(change.targetPath()) + "\n"
                    + optionalSort(change.sourceHash()) + "\n" + optionalSort(change.targetHash()) + "\n" + change.kind();
                if (!changeIdentities.add(identity.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Lifecycle Change Is Duplicate Or Case-Aliased: " + change.kind());
                }
            }
            changes = List.copyOf(normalizedChanges);
            List<AssetRecord> normalizedAssets = new ArrayList<>(assets == null ? List.of() : assets);
            normalizedAssets.sort(Comparator.comparing(AssetRecord::relativePath));
            Set<String> assetPaths = new HashSet<>();
            Set<String> foldedAssetPaths = new HashSet<>();
            Set<String> identities = new HashSet<>();
            for (AssetRecord asset : normalizedAssets) {
                Objects.requireNonNull(asset, "asset");
                if (!owner.equals(asset.owner()) || !assetPaths.add(asset.relativePath())
                    || !foldedAssetPaths.add(asset.relativePath().toLowerCase(Locale.ROOT))
                    || !identities.add(key(asset.type(), asset.id()).toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Lifecycle Asset Record Is Duplicate, Case-Aliased, Or Foreign: "
                        + asset.relativePath());
                }
            }
            assets = List.copyOf(normalizedAssets);
        }
    }

    public record Claim(String relativePath, String owner) {
        public Claim {
            relativePath = MigrationPaths.requireRelative(relativePath);
            owner = requireIdentity(owner, "claim owner", 256);
        }
    }

    public record AcceptedChange(String kind, String sourcePath, String targetPath, String sourceHash,
                                 String targetHash, String owner) {
        public AcceptedChange {
            kind = requireOpaque(kind, "change kind", 256);
            sourcePath = normalizeOptionalPath(sourcePath);
            targetPath = normalizeOptionalPath(targetPath);
            sourceHash = normalizeOptionalDigest(sourceHash, "sourceHash");
            targetHash = normalizeOptionalDigest(targetHash, "targetHash");
            owner = requireIdentity(owner, "change owner", 256);
        }

        private static String normalizeOptionalPath(String value) {
            if (value == null) {
                return null;
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("Optional Lifecycle Path Cannot Be Blank");
            }
            return MigrationPaths.requireRelative(value);
        }

        private static String normalizeOptionalDigest(String value, String field) {
            if (value == null) {
                return null;
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " Cannot Be Blank");
            }
            if (!value.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }

    public record AssetRecord(String relativePath, String owner, String type, String id, String state,
                              long revision, String mutationId, String payloadHash, Path assetPath,
                              LegacyDeletion deletion) {
        public AssetRecord {
            relativePath = MigrationPaths.requireRelative(relativePath);
            owner = requireIdentity(owner, "asset record owner", 256);
            type = requireIdentity(type, "asset record type", 256);
            id = requireIdentity(id, "asset record id", 256);
            state = requireText(state, "asset record state").toUpperCase(Locale.ROOT);
            if (!state.equals("LIVE") && !state.equals("DELETED")) {
                throw new IllegalArgumentException("Unsupported lifecycle asset record state: " + state);
            }
            if (revision < 0L || (state.equals("DELETED") && revision < 1L)) {
                throw new IllegalArgumentException("Lifecycle asset record revision is invalid");
            }
            mutationId = requireMutationId(mutationId, "asset record mutationId");
            payloadHash = normalizeDigest(payloadHash, "payloadHash");
            assetPath = Path.of(normalizeAssetPath(assetPath));
            if (state.equals("LIVE") && deletion != null) {
                throw new IllegalArgumentException("Live lifecycle asset records cannot contain deletion provenance");
            }
            if (state.equals("DELETED")) {
                deletion = Objects.requireNonNull(deletion, "deleted lifecycle asset deletion");
                if (!owner.equals(deletion.owner()) || !relativePath.equals(deletion.evidenceKey())
                    || revision != deletion.revision() || !mutationId.equals(deletion.mutationId())
                    || !payloadHash.equals(deletion.tombstoneEvidenceHash())
                    || !assetPath.equals(deletion.canonicalFuturePath())) {
                    throw new IllegalArgumentException("Lifecycle deletion provenance is not bound to its asset record");
                }
            }
        }
    }

    public record BlockedState(String owner, String evidenceKey, String evidenceHash, Path sourcePath,
                               Path originalLogicalPath, Path canonicalFuturePath, long revision,
                               String mutationId, String reason, String candidateType, String candidateId) {
        public BlockedState {
            owner = requireIdentity(owner, "blocked owner", 256);
            evidenceKey = requireOpaque(evidenceKey, "blocked evidence key", 4096);
            evidenceHash = normalizeDigest(evidenceHash, "evidenceHash");
            if (revision < 0L) {
                throw new IllegalArgumentException("blocked revision cannot be negative");
            }
            sourcePath = sourcePath == null ? null : Path.of(normalizeSnapshotPath(sourcePath));
            originalLogicalPath = originalLogicalPath == null ? null : Path.of(normalizeAssetPath(originalLogicalPath));
            canonicalFuturePath = canonicalFuturePath == null ? null : Path.of(normalizeAssetPath(canonicalFuturePath));
            mutationId = requireMutationId(mutationId, "blocked mutationId");
            reason = requireOpaque(reason, "blocked reason", 4096);
            candidateType = optionalIdentity(candidateType, "blocked candidate type", 256);
            candidateId = optionalIdentity(candidateId, "blocked candidate id", 256);
            if ((candidateType == null) != (candidateId == null)) {
                throw new IllegalArgumentException("Blocked candidate identity must be complete");
            }
        }

        public static BlockedState from(SnapshotManifest.Entry entry, String reason) {
            throw new IllegalArgumentException("Blocked State Requires Exact Typed Mutation Lineage: " + entry.relativePath());
        }

        public String canonicalKey() {
            return owner + "\n" + evidenceKey + "\n" + reason;
        }

    }

    public record Asset(String owner, String type, String id, Path path, AssetState state, String mutationId,
                        byte[] content, LegacyDeletion deletion) {
        public Asset {
            owner = requireIdentity(owner, "asset owner", 256);
            type = requireIdentity(type, "asset type", 256);
            id = requireIdentity(id, "asset id", 256);
            path = Path.of(normalizeAssetPath(path));
            state = Objects.requireNonNull(state, "asset state");
            mutationId = requireMutationId(mutationId, "asset mutationId");
            content = content == null ? new byte[0] : content.clone();
            if (state.kind().equals("DELETED") && content.length != 0) {
                throw new IllegalArgumentException("Deleted adoption content must be empty");
            }
            if (state.kind().equals("DELETED") && deletion == null) {
                throw new IllegalArgumentException("Deleted adoption requires deletion provenance");
            }
            if (!state.kind().equals("DELETED") && deletion != null) {
                throw new IllegalArgumentException("Live adoption cannot contain deletion provenance");
            }
            if (state.kind().equals("LIVE") && !sha256(content).equals(state.hash())) {
                throw new IllegalArgumentException("Live adoption content hash does not match its state");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public String canonicalKey() {
            return type.length() + ":" + type + id.length() + ":" + id;
        }
    }

    public record AssetState(String kind, long revision, String hash) {
        public AssetState {
            kind = requireText(kind, "asset state kind").toUpperCase(Locale.ROOT);
            if (!kind.equals("LIVE") && !kind.equals("DELETED")) {
                throw new IllegalArgumentException("Unsupported asset state: " + kind);
            }
            if (revision < 0L || (kind.equals("DELETED") && revision < 1L)) {
                throw new IllegalArgumentException("Asset state revision is invalid");
            }
            if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Asset state hash must be SHA-256");
            }
            hash = hash.toLowerCase(Locale.ROOT);
        }
    }

    public record AuxiliaryKey(String family, String type, String id) {
        public static final String JSON_FAMILY = "JSON";
        public static final String FLOW_GRAPH_FAMILY = "FLOW_GRAPH";
        public static final String EXPLICIT_FAMILY = "EXPLICIT";

        public AuxiliaryKey {
            family = requireText(family, "auxiliary family").toUpperCase(Locale.ROOT);
            if (!family.equals(JSON_FAMILY) && !family.equals(FLOW_GRAPH_FAMILY)
                && !family.equals(EXPLICIT_FAMILY)) {
                throw new IllegalArgumentException("Unsupported auxiliary family: " + family);
            }
            type = requireIdentity(type, "auxiliary type", 256);
            id = requireIdentity(id, "auxiliary id", 256);
        }
    }

    public record LegacyDeletion(String priorPayloadHash, Path originalLogicalPath, Path canonicalFuturePath,
                                 String tombstoneEvidenceHash, String owner, String evidenceKey, long revision,
                                 String mutationId, AuxiliaryKey auxiliaryKey) {
        public LegacyDeletion(String priorPayloadHash, Path originalLogicalPath, Path canonicalFuturePath,
                              String tombstoneEvidenceHash, String owner, String evidenceKey, long revision,
                              String mutationId) {
            this(priorPayloadHash, originalLogicalPath, canonicalFuturePath, tombstoneEvidenceHash, owner,
                evidenceKey, revision, mutationId, null);
        }

        public LegacyDeletion {
            priorPayloadHash = priorPayloadHash == null ? null : normalizeDigest(priorPayloadHash, "priorPayloadHash");
            tombstoneEvidenceHash = normalizeDigest(tombstoneEvidenceHash, "tombstoneEvidenceHash");
            owner = requireIdentity(owner, "deletion owner", 256);
            evidenceKey = requireOpaque(evidenceKey, "deletion evidence key", 4096);
            if (revision < 1L) {
                throw new IllegalArgumentException("Legacy deletion revision must be positive");
            }
            mutationId = requireMutationId(mutationId, "deletion mutationId");
            if (auxiliaryKey == null) {
                throw new IllegalArgumentException("Deleted lifecycle records require an auxiliary identity");
            }
            originalLogicalPath = originalLogicalPath == null ? null : Path.of(normalizeAssetPath(originalLogicalPath));
            canonicalFuturePath = canonicalFuturePath == null ? null : Path.of(normalizeAssetPath(canonicalFuturePath));
            if (canonicalFuturePath == null) {
                throw new IllegalArgumentException("Legacy deletion canonical future path is required");
            }
        }
    }

    public record Inventory(String provenance, String projectJson, List<Asset> assets, List<BlockedState> blocked) {
        public Inventory {
            provenance = requireText(provenance, "provenance");
            projectJson = Objects.requireNonNull(projectJson, "projectJson");
            if (projectJson.isBlank()) {
                throw new IllegalArgumentException("projectJson Is Invalid");
            }
            List<Asset> normalizedAssets = new ArrayList<>(assets == null ? List.of() : assets);
            normalizedAssets.sort(Comparator.comparing(Asset::canonicalKey));
            Set<String> identities = new HashSet<>();
            Set<String> paths = new HashSet<>();
            for (Asset asset : normalizedAssets) {
                Objects.requireNonNull(asset, "asset");
                if (!identities.add(key(asset.type(), asset.id()).toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate Or Case-Aliased Asset Identity: " + asset.canonicalKey());
                }
                String path = normalizeAssetPath(asset.path()).toLowerCase(Locale.ROOT);
                if (!paths.add(path)) {
                    throw new IllegalArgumentException("Duplicate Or Case-Aliased Asset Path: " + path);
                }
                if (asset.state().kind().equals("LIVE") && !sha256(asset.content()).equals(asset.state().hash())) {
                    throw new IllegalArgumentException("Live Asset Content Hash Does Not Match Its State: " + asset.canonicalKey());
                }
                if (asset.state().kind().equals("DELETED")
                    && !asset.state().hash().equals(asset.deletion().tombstoneEvidenceHash())) {
                    throw new IllegalArgumentException("Deleted Asset Evidence Hash Does Not Match Its State: " + asset.canonicalKey());
                }
            }
            assets = List.copyOf(normalizedAssets);
            List<BlockedState> normalizedBlocked = new ArrayList<>(blocked == null ? List.of() : blocked);
            normalizedBlocked.sort(Comparator.comparing(BlockedState::canonicalKey));
            Set<String> blockedEvidenceKeys = new HashSet<>();
            Set<String> foldedBlockedEvidenceKeys = new HashSet<>();
            Set<String> blockedSourcePaths = new HashSet<>();
            Set<String> foldedBlockedSourcePaths = new HashSet<>();
            Set<String> blockedIdentities = new HashSet<>();
            for (BlockedState value : normalizedBlocked) {
                Objects.requireNonNull(value, "blocked");
                if (!blockedEvidenceKeys.add(value.evidenceKey())
                    || !foldedBlockedEvidenceKeys.add(value.evidenceKey().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate Or Case-Aliased Blocked Evidence Key: " + value.evidenceKey());
                }
                if (value.sourcePath() != null) {
                    String sourcePath = normalizeSnapshotPath(value.sourcePath());
                    if (!blockedSourcePaths.add(sourcePath)
                        || !foldedBlockedSourcePaths.add(sourcePath.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("Duplicate Or Case-Aliased Blocked Source Path: " + sourcePath);
                    }
                }
                if (value.candidateType() != null
                    && (!blockedIdentities.add(key(value.candidateType(), value.candidateId()).toLowerCase(Locale.ROOT))
                        || identities.contains(key(value.candidateType(), value.candidateId()).toLowerCase(Locale.ROOT)))) {
                    throw new IllegalArgumentException("Duplicate, Case-Aliased, Or Asset-Conflicting Blocked Candidate Identity: "
                        + value.candidateType() + "/" + value.candidateId());
                }
            }
            blocked = List.copyOf(normalizedBlocked);
        }
    }

    public record Evidence(String originalPath, String evidencePath, String hash, long size, String owner) {
        public Evidence {
            originalPath = MigrationPaths.requireRelative(originalPath);
            evidencePath = MigrationPaths.requireRelative(evidencePath);
            hash = normalizeDigest(hash, "evidenceHash");
            if (size < 0L) {
                throw new IllegalArgumentException("Evidence size cannot be negative");
            }
            owner = requireIdentity(owner, "evidence owner", 256);
        }
    }

    public record Result(Path artifactPath, String artifactHash, String sourceManifestHash,
                         String postStageManifestHash, String planHash, String sourceManifest,
                         String sourceState, String sourceMetadata, String postStageManifest,
                         String postStageState, String postStageMetadata, Inventory inventory,
                         List<LifecycleOutput> outputs, List<QuarantineRecord> quarantine,
                         QuarantineAcceptance acceptance, List<Evidence> evidence) {
        public Result {
            artifactPath = MigrationPaths.requirePath(artifactPath, "artifactPath");
            requireDigestUnchecked(artifactHash, "artifactHash");
            requireDigestUnchecked(sourceManifestHash, "sourceManifestHash");
            requireDigestUnchecked(postStageManifestHash, "postStageManifestHash");
            requireDigestUnchecked(planHash, "planHash");
            sourceManifest = Objects.requireNonNull(sourceManifest, "sourceManifest");
            sourceState = Objects.requireNonNull(sourceState, "sourceState");
            sourceMetadata = Objects.requireNonNull(sourceMetadata, "sourceMetadata");
            postStageManifest = Objects.requireNonNull(postStageManifest, "postStageManifest");
            postStageState = Objects.requireNonNull(postStageState, "postStageState");
            postStageMetadata = Objects.requireNonNull(postStageMetadata, "postStageMetadata");
            inventory = Objects.requireNonNull(inventory, "inventory");
            List<LifecycleOutput> outputCopy = new ArrayList<>(outputs == null ? List.of() : outputs);
            outputCopy.sort(Comparator.comparing(LifecycleOutput::adapterId));
            Set<String> outputIds = new HashSet<>();
            outputCopy.forEach(output -> {
                Objects.requireNonNull(output, "output");
                if (!outputIds.add(output.adapterId())) {
                    throw new IllegalArgumentException("Duplicate Lifecycle Output: " + output.adapterId());
                }
            });
            outputs = List.copyOf(outputCopy);
            quarantine = List.copyOf(quarantine == null ? List.of() : quarantine);
            List<Evidence> evidenceCopy = new ArrayList<>(evidence == null ? List.of() : evidence);
            evidenceCopy.sort(Comparator.comparing(Evidence::originalPath));
            Set<String> evidencePaths = new HashSet<>();
            Set<String> foldedEvidencePaths = new HashSet<>();
            for (Evidence item : evidenceCopy) {
                if (!evidencePaths.add(item.originalPath())
                    || !foldedEvidencePaths.add(item.originalPath().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate Or Case-Aliased Adoption Evidence: " + item.originalPath());
                }
            }
            evidence = List.copyOf(evidenceCopy);
        }
    }

    private record InventoryBuild(Inventory inventory, List<EvidenceSource> evidence) {
    }

    private record EvidenceSource(SnapshotManifest.Entry entry, byte[] bytes) {
        private EvidenceSource {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record LifecycleBinding(String adapterId, String owner) {
    }

    private record BlockedValidation(List<BlockedState> values, Map<String, BlockedState> bySourcePath) {
        private BlockedValidation {
            values = List.copyOf(values);
            bySourcePath = Map.copyOf(bySourcePath);
        }
    }

    private record LifecycleAuthority(Map<String, LifecycleBinding> bindings, Map<String, AssetRecord> records) {
        private LifecycleAuthority {
            bindings = Map.copyOf(bindings);
            records = Map.copyOf(records);
        }

        private LifecycleBinding binding(String path) {
            return bindings.get(path);
        }

        private AssetRecord record(String path) {
            return records.get(path);
        }
    }

    private static void requireDigestUnchecked(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
    }
}
