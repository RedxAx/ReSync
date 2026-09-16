package restudio.resync.upgrade;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.storage.CoreGraphAssetCodec;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;
import restudio.resync.migration.VerifiedSnapshotAdmission;
import restudio.resync.migration.VerifiedSnapshotExporter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class TypedStageEvidenceConverter {
    private static final String ASSETS_PREFIX = "assets/";
    private static final String PROJECT_PATH = "assets/project.json";
    private static final String TOMBSTONE_PREFIX = "assets/.tombstones/";
    private static final String CONTROL_PREFIX = "assets/.migrations/";
    private static final String MIGRATION_PREFIX = "assets/migration-backups/";
    private static final String QUARANTINE_ROOT = ".quarantine/migration/";

    private TypedStageEvidenceConverter() {
    }

    public static AssetAdoptionArtifactProducer.StageOutput productionOutput(
        VerifiedSnapshotAdmission sourceAdmission,
        MigrationPlan plan,
        VerifiedSnapshotAdmission postStageAdmission,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance
    ) throws IOException {
        return productionOutput(sourceAdmission, plan, postStageAdmission, quarantineReport, acceptance, List.of());
    }

    public static AssetAdoptionArtifactProducer.StageOutput productionOutput(
        VerifiedSnapshotAdmission sourceAdmission,
        MigrationPlan plan,
        VerifiedSnapshotAdmission postStageAdmission,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> declaredOutputs
    ) throws IOException {
        VerifiedSnapshotAdmission source = Objects.requireNonNull(sourceAdmission, "sourceAdmission");
        VerifiedSnapshotAdmission post = Objects.requireNonNull(postStageAdmission, "postStageAdmission");
        Map<String, SnapshotManifest.Entry> before = entries(source.snapshot().manifest());
        Map<String, SnapshotManifest.Entry> after = entries(post.snapshot().manifest());
        Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations = new TreeMap<>(
            ReSyncTypedLifecycleUpgrade.production().authoritativeAdaptations(source.snapshot()));
        addDeclaredAdaptations(adaptations, declaredOutputs, before, after, post.snapshot().root());
        Map<OutputKey, MutableOutput> mutable = new TreeMap<>(Comparator.comparing(OutputKey::adapterId)
            .thenComparing(OutputKey::owner));
        Map<String, List<QuarantineRecord>> blockedRecords = new TreeMap<>();
        Map<String, String> planOperationMapping = new TreeMap<>();
        Map<String, MatchedPath> pathMatches = new TreeMap<>();
        for (QuarantineRecord record : quarantineReport.records()) {
            String sourcePath = MigrationPaths.requireRelative(record.sourceLocation());
            if (!sourcePath.startsWith(ASSETS_PREFIX) || sourcePath.startsWith(CONTROL_PREFIX)
                || sourcePath.startsWith(MIGRATION_PREFIX)) {
                continue;
            }
            if (sourcePath.equals(PROJECT_PATH)) {
                throw new MigrationException("Production Typed Lifecycle Cannot Block The Asset Project Authority");
            }
            if (record.sourceHash().isEmpty()) {
                throw new MigrationException("Production Typed Lifecycle Blocked Evidence Requires An Exact Source Hash: "
                    + sourcePath);
            }
            String evidencePath = quarantineEvidencePath(record, sourcePath);
            SnapshotManifest.Entry evidenceEntry = after.get(evidencePath);
            SnapshotManifest.Entry sourceEntry = before.get(sourcePath);
            String productionOwner = exactSourceClaimOwner(sourcePath, sourceEntry, adaptations);
            if (sourceEntry == null || evidenceEntry == null
                || !record.sourceHash().equals(sourceEntry.sha256())
                || !record.sourceHash().equals(evidenceEntry.sha256())
                || !productionOwner.equals(evidenceEntry.owner())) {
                throw new MigrationException("Production Typed Lifecycle Quarantine Evidence Is Not An Exact Retained Source: "
                    + sourcePath);
            }
            blockedRecords.computeIfAbsent(sourcePath, ignored -> new ArrayList<>()).add(record);
        }
        Set<String> blockedSourcePaths = Set.copyOf(blockedRecords.keySet());
        Map<String, Set<String>> blockedAdaptationIdsByPath = new TreeMap<>();
        for (Map.Entry<String, TypedLifecycleMigrationAdapter.Adaptation> entry : adaptations.entrySet()) {
            for (QuarantineRecord record : entry.getValue().quarantineRecords()) {
                if (blockedSourcePaths.contains(record.sourceLocation())) {
                    blockedAdaptationIdsByPath.computeIfAbsent(record.sourceLocation(), ignored -> new HashSet<>())
                        .add(entry.getKey());
                }
            }
        }
        for (MigrationOperation operation : plan.operations()) {
            String sourcePath = emptyToNull(operation.sourcePath());
            String targetPath = emptyToNull(operation.targetPath());
            if (isExcludedMaterializationPath(sourcePath) && isExcludedMaterializationPath(targetPath)) {
                continue;
            }
            boolean sourceAsset = sourcePath != null && sourcePath.startsWith(ASSETS_PREFIX);
            boolean targetAsset = targetPath != null && targetPath.startsWith(ASSETS_PREFIX);
            if (!sourceAsset && !targetAsset) {
                continue;
            }
            if ((sourcePath != null && !sourceAsset) || (targetPath != null && !targetAsset)) {
                throw new MigrationException("Production Typed Lifecycle Operation Crosses The Assets Boundary: "
                    + operation.adapterId());
            }
        }
        for (Map.Entry<String, SnapshotManifest.Entry> entry : after.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(ASSETS_PREFIX) || isExcludedMaterializationPath(path)) {
                continue;
            }
            MatchedPath matchedPath = exactPathAdapter(path, entry.getValue(), before, adaptations,
                blockedSourcePaths, blockedAdaptationIdsByPath);
            pathMatches.put(path, matchedPath);
            output(mutable, matchedPath.adapterId(), matchedPath.targetOwner()).claims.add(
                new AssetAdoptionArtifactProducer.Claim(path, matchedPath.targetOwner()));
        }
        for (MigrationOperation operation : plan.operations()) {
            String sourcePath = emptyToNull(operation.sourcePath());
            String targetPath = emptyToNull(operation.targetPath());
            if (isExcludedMaterializationPath(sourcePath) && isExcludedMaterializationPath(targetPath)) {
                continue;
            }
            boolean sourceAsset = sourcePath != null && sourcePath.startsWith(ASSETS_PREFIX);
            boolean targetAsset = targetPath != null && targetPath.startsWith(ASSETS_PREFIX);
            if (!sourceAsset && !targetAsset) {
                continue;
            }
            if ((sourcePath != null && !sourceAsset) || (targetPath != null && !targetAsset)) {
                throw new MigrationException("Production Typed Lifecycle Operation Crosses The Assets Boundary: "
                    + operation.adapterId());
            }
            SnapshotManifest.Entry sourceEntry = sourcePath == null ? null : before.get(sourcePath);
            SnapshotManifest.Entry targetEntry = targetPath == null ? null : after.get(targetPath);
            if (sourcePath != null && (sourceEntry == null || !operation.sourceHash().equals(sourceEntry.sha256()))) {
                throw new MigrationException("Production Typed Lifecycle Operation Source Hash Is Not Exact: "
                    + sourcePath);
            }
            if (targetPath != null && (targetEntry == null || !operation.targetHash().equals(targetEntry.sha256()))) {
                throw new MigrationException("Production Typed Lifecycle Operation Target Hash Is Not Exact: "
                    + targetPath);
            }
            MatchedChange matchedChange = exactChangeAdapter(operation, before, after, adaptations,
                blockedSourcePaths, blockedAdaptationIdsByPath);
            String adapterId = bindPlanOperation(planOperationMapping, operation, matchedChange.adapterId());
            String owner = matchedChange.targetOwner();
            output(mutable, adapterId, owner)
                .changes.add(new AssetAdoptionArtifactProducer.AcceptedChange(
                    matchedChange.productionKind(), sourcePath, targetPath,
                    sourcePath == null ? null : sourceEntry.sha256(),
                    targetPath == null ? null : targetEntry.sha256(), owner));
        }
        Set<String> materializedIdentities = new HashSet<>();
        Set<String> materializedPaths = new HashSet<>();
        for (Map.Entry<String, SnapshotManifest.Entry> entry : after.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(ASSETS_PREFIX) || isExcludedMaterializationPath(path)) {
                continue;
            }
            AssetLineage lineage = lineage(post.snapshot().root(), path, entry.getValue(), before, source.snapshot().root());
            MatchedPath pathMatch = pathMatches.get(path);
            if (pathMatch == null) {
                throw new MigrationException("Production Typed Lifecycle Asset Has No Exact Adapter Owner: " + path);
            }
            MutableOutput target = output(mutable, pathMatch.adapterId(), pathMatch.targetOwner());
            String owner = pathMatch.targetOwner();
            if (path.startsWith(TOMBSTONE_PREFIX)) {
                Path futurePath = lineage.canonicalFuturePath();
                materializedIdentities.add(identity(lineage.type(), lineage.id()));
                materializedPaths.add(futurePath.toString());
                AssetAdoptionArtifactProducer.LegacyDeletion deletion = new AssetAdoptionArtifactProducer.LegacyDeletion(
                    lineage.priorPayloadHash(), lineage.originalLogicalPath(), futurePath, entry.getValue().sha256(),
                    owner, path, lineage.revision(), lineage.mutationId(), lineage.auxiliaryKey());
                target.assets.add(new AssetAdoptionArtifactProducer.AssetRecord(path, owner,
                    lineage.type(), lineage.id(), "DELETED", lineage.revision(), lineage.mutationId(),
                    entry.getValue().sha256(), futurePath, deletion));
            } else {
                materializedIdentities.add(identity(lineage.type(), lineage.id()));
                materializedPaths.add(path.substring(ASSETS_PREFIX.length()));
                target.assets.add(new AssetAdoptionArtifactProducer.AssetRecord(path, owner,
                    lineage.type(), lineage.id(), "LIVE", lineage.revision(), lineage.mutationId(),
                    entry.getValue().sha256(), Path.of(path.substring(ASSETS_PREFIX.length())), null));
            }
        }
        List<AssetAdoptionArtifactProducer.BlockedState> blocked = new ArrayList<>();
        for (Map.Entry<String, List<QuarantineRecord>> entry : blockedRecords.entrySet()) {
            String originalPath = entry.getKey();
            QuarantineRecord first = entry.getValue().stream()
                .sorted(Comparator.comparing(QuarantineRecord::recordId)).findFirst().orElseThrow();
            String evidencePath = quarantineEvidencePath(first, originalPath);
            SnapshotManifest.Entry manifestEntry = after.get(evidencePath);
            SnapshotManifest.Entry sourceEntry = before.get(originalPath);
            String owner = exactSourceClaimOwner(originalPath, sourceEntry, adaptations);
            AssetLineage lineage = lineage(source.snapshot().root(), originalPath, sourceEntry, before, source.snapshot().root());
            String reason = entry.getValue().stream()
                .sorted(Comparator.comparing(QuarantineRecord::recordId))
                .map(record -> record.code() + ": " + record.reason())
                .collect(Collectors.joining("; "));
            Path futurePath = lineage.canonicalFuturePath();
            Path logicalPath = Path.of(originalPath.substring(ASSETS_PREFIX.length()));
            String candidateIdentity = identity(lineage.type(), lineage.id());
            String candidateType = materializedIdentities.contains(candidateIdentity) ? null : lineage.type();
            String candidateId = candidateType == null ? null : lineage.id();
            Path blockedFuturePath = futurePath != null && materializedPaths.contains(futurePath.toString())
                ? null : futurePath;
            blocked.add(new AssetAdoptionArtifactProducer.BlockedState(owner, evidencePath,
                manifestEntry.sha256(), Path.of(evidencePath), logicalPath, blockedFuturePath,
                lineage.revision(), lineage.mutationId(), reason, candidateType, candidateId));
        }
        List<AssetAdoptionArtifactProducer.LifecycleOutput> outputs = mutable.values().stream()
            .filter(value -> !value.claims.isEmpty() || !value.changes.isEmpty() || !value.assets.isEmpty())
            .map(MutableOutput::value)
            .toList();
        return output(source, plan, post, outputs, quarantineReport, acceptance, blocked);
    }

    private static void addDeclaredAdaptations(
        Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> declaredOutputs,
        Map<String, SnapshotManifest.Entry> before,
        Map<String, SnapshotManifest.Entry> after,
        Path postRoot
    ) throws IOException {
        for (AssetAdoptionArtifactProducer.LifecycleOutput output : declaredOutputs == null
            ? List.<AssetAdoptionArtifactProducer.LifecycleOutput>of() : declaredOutputs) {
            String adapterId = output.adapterId();
            if (adaptations.containsKey(adapterId)) {
                throw new MigrationException("Declared Typed Lifecycle Adaptation Conflicts With Production Adapter: "
                    + adapterId);
            }
            List<TypedLifecycleMigrationAdapter.Claim> claims = output.claims().stream()
                .map(claim -> new TypedLifecycleMigrationAdapter.Claim(claim.relativePath(), claim.owner()))
                .toList();
            List<TypedLifecycleMigrationAdapter.Change> changes = new ArrayList<>();
            for (AssetAdoptionArtifactProducer.AcceptedChange change : output.changes()) {
                String sourcePath = change.sourcePath() == null ? "" : change.sourcePath();
                String targetPath = change.targetPath() == null ? "" : change.targetPath();
                SnapshotManifest.Entry sourceEntry = sourcePath.isEmpty() ? null : before.get(sourcePath);
                if (sourceEntry != null && (!output.owner().equals(sourceEntry.owner())
                    || change.sourceHash() == null || !change.sourceHash().equals(sourceEntry.sha256()))) {
                    throw new MigrationException("Declared Typed Lifecycle Source Is Not Exact: " + sourcePath);
                }
                byte[] targetBytes = null;
                SnapshotManifest.Entry targetEntry = targetPath.isEmpty() ? null : after.get(targetPath);
                if (targetEntry != null) {
                    if (!output.owner().equals(targetEntry.owner()) || change.targetHash() == null
                        || !change.targetHash().equals(targetEntry.sha256())) {
                        throw new MigrationException("Declared Typed Lifecycle Target Is Not Exact: " + targetPath);
                    }
                    targetBytes = Files.readAllBytes(MigrationPaths.resolveInside(postRoot, targetPath));
                    if (!CanonicalHash.rawSha256(targetBytes).equals(targetEntry.sha256())) {
                        throw new MigrationException("Declared Typed Lifecycle Target Bytes Are Not Exact: "
                            + targetPath);
                    }
                } else if (!targetPath.isEmpty()) {
                    throw new MigrationException("Declared Typed Lifecycle Target Is Missing: " + targetPath);
                } else if (change.targetHash() != null) {
                    throw new MigrationException("Declared Typed Lifecycle Delete Carries A Target Hash: "
                        + sourcePath);
                }
                changes.add(new TypedLifecycleMigrationAdapter.Change(change.kind(), sourcePath, targetPath,
                    targetBytes));
            }
            adaptations.put(adapterId, new TypedLifecycleMigrationAdapter.Adaptation(claims, changes, List.of()));
        }
    }

    public static AssetAdoptionArtifactProducer.StageOutput output(
        MigrationPlan plan,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        VerifiedSnapshotExporter.Request exportRequest,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
        List<AssetAdoptionArtifactProducer.BlockedState> blocked
    ) throws IOException {
        VerifiedSnapshotExporter.Request request = Objects.requireNonNull(exportRequest, "exportRequest");
        VerifiedSnapshotAdmission source = request.verifiedSourceAdmission();
        MigrationPlan migrationPlan = Objects.requireNonNull(plan, "plan");
        QuarantineReport report = Objects.requireNonNull(quarantineReport, "quarantineReport");
        Snapshot sourceSnapshot = source.snapshot();
        if (!sourceSnapshot.metadata().snapshotId().equals(migrationPlan.sourceSnapshotId())
            || !sourceSnapshot.manifest().manifestHash().equals(migrationPlan.sourceManifestHash())
            || !report.reportHash().equals(migrationPlan.quarantineReportHash())) {
            throw new MigrationException("Typed Stage Export Inputs Do Not Match Their Migration Plan");
        }
        report.requireAccepted(Objects.requireNonNull(acceptance, "acceptance"));
        if (!request.stagedRoot().equals(request.exportRoot())) {
            VerifiedSnapshotAdmission postStage = VerifiedSnapshotExporter.export(request);
            return output(source, migrationPlan, postStage, lifecycleOutputs,
                quarantineReport, acceptance, blocked);
        }
        throw new MigrationException("Verified Snapshot Export Requires Distinct Staged And Export Roots");
    }

    public static AssetAdoptionArtifactProducer.StageOutput output(
        VerifiedSnapshotAdmission sourceAdmission,
        MigrationPlan plan,
        VerifiedSnapshotAdmission postStageAdmission,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        List<AssetAdoptionArtifactProducer.BlockedState> blocked
    ) throws IOException {
        VerifiedSnapshotAdmission source = Objects.requireNonNull(sourceAdmission, "sourceAdmission");
        MigrationPlan migrationPlan = Objects.requireNonNull(plan, "plan");
        Snapshot sourceSnapshot = source.snapshot();
        if (!sourceSnapshot.metadata().snapshotId().equals(migrationPlan.sourceSnapshotId())
            || !sourceSnapshot.manifest().manifestHash().equals(migrationPlan.sourceManifestHash())) {
            throw new MigrationException("Typed Stage Source Does Not Match Its Migration Plan");
        }
        QuarantineReport report = Objects.requireNonNull(quarantineReport, "quarantineReport");
        if (!report.reportHash().equals(migrationPlan.quarantineReportHash())) {
            throw new MigrationException("Typed Stage Quarantine Report Does Not Match Its Migration Plan");
        }
        AssetAdoptionArtifactProducer.StageOutput output = new AssetAdoptionArtifactProducer.StageOutput(
            migrationPlan.planHash(),
            sourceSnapshot.metadata().snapshotId(),
            sourceSnapshot.manifest().manifestHash(),
            Objects.requireNonNull(postStageAdmission, "postStageAdmission"),
            lifecycleOutputs,
            report,
            Objects.requireNonNull(acceptance, "acceptance"),
            blocked);
        output.requireAccepted();
        VerifiedSnapshotAdmission postAdmission = output.postStageAdmission();
        Snapshot postStage = postAdmission.snapshot();
        MigrationPaths.requireDistinctRoots(source.root(), postAdmission.root());
        if (postStage.metadata().formatVersion() != migrationPlan.targetFormatVersion()) {
            throw new MigrationException("Typed Stage Post-Stage Snapshot Format Does Not Match Its Migration Plan");
        }
        return output;
    }

    public static StagedMigration attach(
        StagedMigration staged,
        VerifiedSnapshotAdmission sourceAdmission,
        AssetAdoptionArtifactProducer.StageOutput stageOutput
    ) throws IOException {
        StagedMigration checked = Objects.requireNonNull(staged, "staged");
        VerifiedSnapshotAdmission source = Objects.requireNonNull(sourceAdmission, "sourceAdmission");
        AssetAdoptionArtifactProducer.StageOutput output = Objects.requireNonNull(stageOutput, "stageOutput");
        Snapshot sourceSnapshot = source.snapshot();
        Snapshot postStage = output.postStageAdmission().snapshot();
        postStage.manifest().verify(checked.root()).requireVerified();
        if (!checked.planHash().equals(output.planHash())
            || !checked.contentHash().equals(TreeDigest.of(postStage.root()))
            || !sourceSnapshot.metadata().snapshotId().equals(output.sourceSnapshotId())
            || !sourceSnapshot.manifest().manifestHash().equals(output.sourceManifestHash())
            || checked.root().equals(postStage.root())) {
            throw new MigrationException("Typed Stage Evidence Does Not Match Staged Migration");
        }
        MigrationPaths.requireDistinctRoots(source.root(), postStage.root());
        output.requireAccepted();
        return checked.attachTypedEvidence(new StagedMigration.TypedEvidence(source, output));
    }

    public static StagedMigration attach(
        StagedMigration staged,
        VerifiedSnapshotAdmission sourceAdmission,
        MigrationPlan plan,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        AssetAdoptionArtifactProducer.StageOutput stageOutput
    ) throws IOException {
        MigrationPlan migrationPlan = Objects.requireNonNull(plan, "plan");
        QuarantineReport report = Objects.requireNonNull(quarantineReport, "quarantineReport");
        QuarantineAcceptance checkedAcceptance = Objects.requireNonNull(acceptance, "acceptance");
        AssetAdoptionArtifactProducer.StageOutput output = Objects.requireNonNull(stageOutput, "stageOutput");
        if (!migrationPlan.planHash().equals(output.planHash())
            || !migrationPlan.quarantineReportHash().equals(report.reportHash())
            || !report.reportHash().equals(output.quarantineReport().reportHash())
            || !checkedAcceptance.equals(output.acceptance())) {
            throw new MigrationException("Typed Stage Evidence Does Not Match Its Accepted Migration Inputs");
        }
        report.requireAccepted(checkedAcceptance);
        if (output.postStageAdmission().snapshot().metadata().formatVersion()
            != migrationPlan.targetFormatVersion()) {
            throw new MigrationException("Typed Stage Post-Stage Snapshot Format Does Not Match Its Migration Plan");
        }
        return attach(staged, sourceAdmission, output);
    }

    public static StagedMigration attach(
        StagedMigration staged,
        MigrationPlan plan,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        VerifiedSnapshotExporter.Request exportRequest,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
        List<AssetAdoptionArtifactProducer.BlockedState> blocked
    ) throws IOException {
        AssetAdoptionArtifactProducer.StageOutput output = output(plan, quarantineReport, acceptance,
            exportRequest, lifecycleOutputs, blocked);
        return attach(staged, exportRequest.verifiedSourceAdmission(), plan, quarantineReport,
            acceptance, output);
    }

    public static StagedMigration attach(
        StagedMigration staged,
        VerifiedSnapshotAdmission sourceAdmission,
        MigrationPlan plan,
        VerifiedSnapshotAdmission postStageAdmission,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
        QuarantineReport quarantineReport,
        QuarantineAcceptance acceptance,
        List<AssetAdoptionArtifactProducer.BlockedState> blocked
    ) throws IOException {
        AssetAdoptionArtifactProducer.StageOutput output = output(sourceAdmission, plan, postStageAdmission,
            lifecycleOutputs, quarantineReport, acceptance, blocked);
        return attach(staged, sourceAdmission, output);
    }

    private static Map<String, SnapshotManifest.Entry> entries(SnapshotManifest manifest) throws MigrationException {
        List<SnapshotManifest.Entry> sorted = new ArrayList<>(manifest.entries());
        sorted.sort(Comparator.comparing(SnapshotManifest.Entry::relativePath));
        Map<String, SnapshotManifest.Entry> result = new TreeMap<>();
        for (SnapshotManifest.Entry entry : sorted) {
            SnapshotManifest.Entry previous = result.putIfAbsent(entry.relativePath(), entry);
            if (previous != null) {
                throw new MigrationException("Duplicate Production Typed Snapshot Entry: " + entry.relativePath());
            }
        }
        return result;
    }

    private static MutableOutput output(Map<OutputKey, MutableOutput> outputs, String adapterId, String owner) {
        return outputs.computeIfAbsent(new OutputKey(adapterId, owner), ignored -> new MutableOutput(adapterId, owner));
    }

    private static AssetLineage lineage(Path root, String path, SnapshotManifest.Entry entry,
                                        Map<String, SnapshotManifest.Entry> before, Path beforeRoot) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(MigrationPaths.resolveInside(root, path));
            CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
            if (path.startsWith(TOMBSTONE_PREFIX)) {
                try {
                    CoreGraphAssetCodec.Tombstone tombstone = codec.decodeTombstone(bytes);
                    String type = tombstone.resource().resourceType().value();
                    String id = tombstone.resource().id();
                    Path logical = priorLogicalPath(before, beforeRoot, type, id);
                    if (logical == null) {
                        logical = Path.of(type + "/" + id + ".json");
                    }
                    return new AssetLineage(type, id, tombstone.revision(), tombstone.mutationId().toString(),
                        tombstone.priorPayloadHash().canonicalText(), null, logical,
                        new AssetAdoptionArtifactProducer.AuxiliaryKey(
                            AssetAdoptionArtifactProducer.AuxiliaryKey.FLOW_GRAPH_FAMILY,
                            "tombstone:" + type, id));
                } catch (RuntimeException ignored) {
                }
            } else {
                try {
                    CoreGraphAssetCodec.Asset asset = codec.decode(bytes);
                    var resource = asset.graphDocument() != null
                        ? asset.graphDocument().resource() : asset.functionSourceDocument().graph().resource();
                    return new AssetLineage(resource.resourceType().value(), resource.id(),
                        asset.envelope().assetRevision(), asset.envelope().assetMutationId(), null,
                        null, Path.of(path.substring(ASSETS_PREFIX.length())), null);
                } catch (RuntimeException ignored) {
                }
            }
            Object parsed = CanonicalJson.parseOpaque(bytes);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new MigrationException("Production Typed Asset Is Not An Object: " + path);
            }
            Map<String, Object> value = stringMap(raw);
            Map<String, Object> nested = value.get("resource") instanceof Map<?, ?> resource
                ? stringMap(resource) : Map.of();
            String type = firstText(value, nested, "resourceType", "type");
            String id = firstText(value, nested, "id");
            long revision = firstLong(value, nested, "assetRevision", "resourceRevision", "revision");
            String mutationId = firstText(value, nested, "assetMutationId", "resourceMutationId", "mutationId");
            String priorPayloadHash = firstOptionalText(value, nested, "payloadHash", "priorPayloadHash");
            Path logical = path.startsWith(TOMBSTONE_PREFIX)
                ? priorLogicalPath(before, beforeRoot, type, id) : null;
            if (path.startsWith(TOMBSTONE_PREFIX) && logical == null) {
                logical = Path.of(type + "/" + id + ".json");
            }
            Path future = path.startsWith(TOMBSTONE_PREFIX)
                ? logical : Path.of(path.substring(ASSETS_PREFIX.length()));
            AssetAdoptionArtifactProducer.AuxiliaryKey auxiliary = path.startsWith(TOMBSTONE_PREFIX)
                ? genericAuxiliary(value, nested, type, id) : null;
            return new AssetLineage(type, id, revision, mutationId, priorPayloadHash,
                path.startsWith(TOMBSTONE_PREFIX) ? null : logical,
                future, auxiliary);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof MigrationException migrationException) {
                throw migrationException;
            }
            throw new MigrationException("Production Typed Asset Lineage Is Invalid: " + path, exception);
        }
    }

    private static Path priorLogicalPath(Map<String, SnapshotManifest.Entry> before, Path beforeRoot,
                                         String type, String id) {
        for (String candidate : before.keySet().stream().sorted().toList()) {
            if (!candidate.startsWith(ASSETS_PREFIX) || candidate.equals(PROJECT_PATH)
                || candidate.startsWith(TOMBSTONE_PREFIX) || candidate.startsWith(CONTROL_PREFIX)
                || candidate.startsWith(MIGRATION_PREFIX)) {
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(MigrationPaths.resolveInside(beforeRoot, candidate));
                AssetLineage lineage;
                try {
                    CoreGraphAssetCodec.Asset asset = new CoreGraphAssetCodec().decode(bytes);
                    var resource = asset.graphDocument() != null
                        ? asset.graphDocument().resource() : asset.functionSourceDocument().graph().resource();
                    lineage = new AssetLineage(resource.resourceType().value(), resource.id(),
                        asset.envelope().assetRevision(), asset.envelope().assetMutationId(), null, null,
                        Path.of(candidate.substring(ASSETS_PREFIX.length())), null);
                } catch (RuntimeException ignored) {
                    lineage = shallowLineage(bytes, candidate);
                }
                if (lineage.type().equals(type) && lineage.id().equals(id)) {
                    return Path.of(candidate.substring(ASSETS_PREFIX.length()));
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static AssetLineage shallowLineage(byte[] bytes, String path) {
        Object parsed = CanonicalJson.parseOpaque(bytes);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Asset Is Not An Object");
        }
        Map<String, Object> value = stringMap(raw);
        Map<String, Object> nested = value.get("resource") instanceof Map<?, ?> resource
            ? stringMap(resource) : Map.of();
        return new AssetLineage(firstText(value, nested, "resourceType", "type"), firstText(value, nested, "id"),
            firstLong(value, nested, "assetRevision", "resourceRevision", "revision"),
            firstText(value, nested, "assetMutationId", "resourceMutationId", "mutationId"),
            firstOptionalText(value, nested, "payloadHash", "priorPayloadHash"), null,
            Path.of(path.substring(ASSETS_PREFIX.length())), null);
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Asset Contains A Non-Text Field Name");
            }
            result.put(text, value);
        });
        return result;
    }

    private static String firstText(Map<String, Object> value, Map<String, Object> nested, String... fields) {
        String result = firstOptionalText(value, nested, fields);
        if (result == null) {
            throw new IllegalArgumentException("Asset Lineage Field Is Missing: " + String.join("/", fields));
        }
        return result;
    }

    private static String firstOptionalText(Map<String, Object> value, Map<String, Object> nested, String... fields) {
        for (String field : fields) {
            Object direct = value.get(field);
            if (direct instanceof String text && !text.isBlank()) {
                return text;
            }
            Object child = nested.get(field);
            if (child instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static long firstLong(Map<String, Object> value, Map<String, Object> nested, String... fields) {
        for (String field : fields) {
            Object candidate = value.containsKey(field) ? value.get(field) : nested.get(field);
            if (candidate instanceof BigDecimal number) {
                try {
                    return number.longValueExact();
                } catch (ArithmeticException ignored) {
                }
            }
            if (candidate instanceof Number number && number.longValue() >= 0L) {
                return number.longValue();
            }
        }
        throw new IllegalArgumentException("Asset Lineage Revision Is Missing");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isExcludedMaterializationPath(String path) {
        return path != null && (path.equals(PROJECT_PATH) || path.startsWith(CONTROL_PREFIX)
            || path.startsWith(MIGRATION_PREFIX));
    }

    private static String bindPlanOperation(Map<String, String> mapping, MigrationOperation operation,
                                            String productionAdapterId) throws MigrationException {
        String operationIdentity = operation.kind() + "\u0000" + operation.adapterId() + "\u0000"
            + operation.sourcePath() + "\u0000" + operation.targetPath() + "\u0000"
            + operation.sourceHash() + "\u0000" + operation.targetHash();
        String previous = mapping.putIfAbsent(operationIdentity, productionAdapterId);
        if (previous != null && !previous.equals(productionAdapterId)) {
            throw new MigrationException("Production Typed Lifecycle Plan Operation Maps To Multiple Exact Adaptations: "
                + operation.adapterId() + " " + operation.sourcePath() + " -> " + operation.targetPath()
                + " -> " + previous + ", " + productionAdapterId);
        }
        return previous == null ? productionAdapterId : previous;
    }

    private static String identity(String type, String id) {
        return type + "\u0000" + id;
    }

    private static boolean samePath(String first, String second) {
        return Objects.equals(emptyToNull(first), emptyToNull(second));
    }

    private static MatchedPath exactPathAdapter(
        String path,
        SnapshotManifest.Entry targetEntry,
        Map<String, SnapshotManifest.Entry> before,
        Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations,
        Set<String> blockedSourcePaths,
        Map<String, Set<String>> blockedAdaptationIdsByPath
    ) throws MigrationException {
        List<MatchedPath> changes = new ArrayList<>();
        List<MatchedPath> claims = new ArrayList<>();
        for (Map.Entry<String, TypedLifecycleMigrationAdapter.Adaptation> entry : adaptations.entrySet()) {
            String adapterId = entry.getKey();
            TypedLifecycleMigrationAdapter.Adaptation adaptation = entry.getValue();
            for (TypedLifecycleMigrationAdapter.Change change : adaptation.changes()) {
                if (!path.equals(change.targetPath()) || !targetEntry.sha256().equals(change.targetHash())) {
                    continue;
                }
                String sourcePath = emptyToNull(change.sourcePath());
                if (blockedSourcePaths.contains(sourcePath == null ? "" : sourcePath)
                    && blockedAdaptationIdsByPath.getOrDefault(sourcePath, Set.of()).contains(adapterId)) {
                    continue;
                }
                String sourceClaimOwner = exactClaimOwner(adaptation, sourcePath);
                SnapshotManifest.Entry sourceEntry = sourcePath == null ? null : before.get(sourcePath);
                if (sourceEntry != null && !sourceClaimOwner.equals(sourceEntry.owner())) {
                    continue;
                }
                if (!sourceClaimOwner.equals(targetEntry.owner())) {
                    continue;
                }
                changes.add(new MatchedPath(adapterId, change.kind(), sourcePath, path,
                    sourceEntry == null ? "" : sourceEntry.sha256(), targetEntry.sha256(),
                    sourceClaimOwner, targetEntry.owner()));
            }
            for (TypedLifecycleMigrationAdapter.Claim claim : adaptation.claims()) {
                if (!path.equals(claim.relativePath()) || !claim.owner().equals(targetEntry.owner())) {
                    continue;
                }
                SnapshotManifest.Entry sourceEntry = before.get(path);
                if (sourceEntry == null || !claim.owner().equals(sourceEntry.owner())
                    || !sourceEntry.sha256().equals(targetEntry.sha256())) {
                    continue;
                }
                claims.add(new MatchedPath(adapterId, "claim", path, path, sourceEntry.sha256(),
                    targetEntry.sha256(), claim.owner(), targetEntry.owner()));
            }
        }
        List<MatchedPath> matches = changes.isEmpty() ? claims : changes;
        Set<String> identities = matches.stream().map(MatchedPath::identity).collect(Collectors.toSet());
        if (identities.size() != matches.size() || matches.size() != 1) {
            throw new MigrationException("Production Typed Lifecycle Asset Requires Exactly One Matching Adaptation: "
                + path + " matches=" + matches.stream().map(MatchedPath::identity).sorted().toList());
        }
        return matches.getFirst();
    }

    private static MatchedChange exactChangeAdapter(
        MigrationOperation operation,
        Map<String, SnapshotManifest.Entry> before,
        Map<String, SnapshotManifest.Entry> after,
        Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations,
        Set<String> blockedSourcePaths,
        Map<String, Set<String>> blockedAdaptationIdsByPath
    ) throws MigrationException {
        List<MatchedChange> matches = new ArrayList<>();
        Set<String> matchIdentities = new HashSet<>();
        String sourcePath = emptyToNull(operation.sourcePath());
        String targetPath = emptyToNull(operation.targetPath());
        for (Map.Entry<String, TypedLifecycleMigrationAdapter.Adaptation> entry : adaptations.entrySet()) {
            String adapterId = entry.getKey();
            if (sourcePath != null && blockedSourcePaths.contains(sourcePath)
                && blockedAdaptationIdsByPath.getOrDefault(sourcePath, Set.of()).contains(adapterId)) {
                continue;
            }
            for (TypedLifecycleMigrationAdapter.Change change : entry.getValue().changes()) {
                if (!samePath(change.sourcePath(), sourcePath) || !samePath(change.targetPath(), targetPath)
                    || !change.targetHash().equals(operation.targetHash())
                    || !operation.type().wireName().equals(productionOperationType(change).wireName())) {
                    continue;
                }
                String sourceClaimOwner = exactClaimOwner(entry.getValue(), sourcePath);
                SnapshotManifest.Entry sourceEntry = sourcePath == null ? null : before.get(sourcePath);
                SnapshotManifest.Entry targetEntry = targetPath == null ? null : after.get(targetPath);
                if (sourceEntry != null && (!operation.sourceHash().equals(sourceEntry.sha256())
                    || !sourceClaimOwner.equals(sourceEntry.owner()))) {
                    continue;
                }
                if (targetPath != null && (targetEntry == null || !operation.targetHash().equals(targetEntry.sha256())
                    || !sourceClaimOwner.equals(targetEntry.owner()))) {
                    continue;
                }
                if (targetPath == null && !operation.targetHash().isEmpty()) {
                    continue;
                }
                MatchedChange candidate = new MatchedChange(adapterId, change, operation.kind(),
                    sourcePath == null ? "" : operation.sourceHash(), operation.targetHash(),
                    sourceClaimOwner, targetPath == null ? sourceClaimOwner : targetEntry.owner());
                if (!matchIdentities.add(candidate.identity())) {
                    throw new MigrationException("Production Typed Lifecycle Operation Has A Duplicate Exact Adaptation: "
                        + operation.sourcePath() + " -> " + operation.targetPath()
                        + " match=" + candidate.identity());
                }
                matches.add(candidate);
            }
        }
        if (matches.size() != 1) {
            throw new MigrationException("Production Typed Lifecycle Operation Requires Exactly One Matching Adaptation: "
                + operation.kind() + " " + operation.sourcePath() + " -> " + operation.targetPath()
                + " matches=" + matches.stream().map(MatchedChange::identity).sorted().toList());
        }
        return matches.getFirst();
    }

    private static String exactClaimOwner(TypedLifecycleMigrationAdapter.Adaptation adaptation,
                                           String sourcePath) throws MigrationException {
        List<TypedLifecycleMigrationAdapter.Claim> claims = sourcePath == null
            ? adaptation.claims()
            : adaptation.claims().stream().filter(claim -> claim.relativePath().equals(sourcePath)).toList();
        Set<String> owners = claims.stream().map(TypedLifecycleMigrationAdapter.Claim::owner).collect(Collectors.toSet());
        if (owners.size() != 1) {
            throw new MigrationException("Production Typed Lifecycle Change Requires One Exact Source Claim Owner: "
                + (sourcePath == null ? "<generated>" : sourcePath));
        }
        return owners.iterator().next();
    }

    private static String exactSourceClaimOwner(String sourcePath, SnapshotManifest.Entry sourceEntry,
                                                Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations)
        throws MigrationException {
        if (sourceEntry == null) {
            throw new MigrationException("Production Typed Lifecycle Quarantine Source Is Missing: " + sourcePath);
        }
        Set<String> owners = new HashSet<>();
        for (TypedLifecycleMigrationAdapter.Adaptation adaptation : adaptations.values()) {
            adaptation.claims().stream().filter(claim -> claim.relativePath().equals(sourcePath))
                .map(TypedLifecycleMigrationAdapter.Claim::owner).forEach(owners::add);
        }
        if (owners.size() != 1 || !owners.contains(sourceEntry.owner())) {
            throw new MigrationException("Production Typed Lifecycle Quarantine Source Owner Is Not Exact: " + sourcePath);
        }
        return owners.iterator().next();
    }

    private static MigrationOperationType productionOperationType(
        TypedLifecycleMigrationAdapter.Change change) {
        if (change.targetPath().isEmpty()) {
            return MigrationOperationType.DELETE;
        }
        if (change.targetPath().equals(change.sourcePath())) {
            return MigrationOperationType.CONVERT;
        }
        return switch (change.kind().toLowerCase(Locale.ROOT)) {
            case "copy" -> MigrationOperationType.COPY;
            case "move" -> MigrationOperationType.MOVE;
            case "rename" -> MigrationOperationType.RENAME;
            case "replace" -> MigrationOperationType.REPLACE;
            case "convert" -> MigrationOperationType.CONVERT;
            default -> MigrationOperationType.GENERATE;
        };
    }

    private static String quarantineEvidencePath(QuarantineRecord record, String sourcePath)
        throws MigrationException {
        String recordId = record.recordId();
        if (recordId == null || !recordId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new MigrationException("Production Typed Lifecycle Quarantine Record ID Is Not A Safe Path Segment: "
                + recordId);
        }
        return QUARANTINE_ROOT + recordId + "/" + sourcePath;
    }

    private static AssetAdoptionArtifactProducer.AuxiliaryKey genericAuxiliary(
        Map<String, Object> value, Map<String, Object> nested, String type, String id) {
        String family = firstOptionalText(value, nested, "auxiliaryFamily", "tombstoneFamily");
        if (family == null || family.isBlank() || family.equalsIgnoreCase(
            AssetAdoptionArtifactProducer.AuxiliaryKey.JSON_FAMILY)) {
            return new AssetAdoptionArtifactProducer.AuxiliaryKey(
                AssetAdoptionArtifactProducer.AuxiliaryKey.JSON_FAMILY, type + ".tombstone", id);
        }
        if (family.equalsIgnoreCase(AssetAdoptionArtifactProducer.AuxiliaryKey.FLOW_GRAPH_FAMILY)) {
            return new AssetAdoptionArtifactProducer.AuxiliaryKey(
                AssetAdoptionArtifactProducer.AuxiliaryKey.FLOW_GRAPH_FAMILY, "tombstone:" + type, id);
        }
        if (!family.equalsIgnoreCase(AssetAdoptionArtifactProducer.AuxiliaryKey.EXPLICIT_FAMILY)) {
            throw new IllegalArgumentException("Unsupported typed tombstone auxiliary family: " + family);
        }
        return new AssetAdoptionArtifactProducer.AuxiliaryKey(
            AssetAdoptionArtifactProducer.AuxiliaryKey.EXPLICIT_FAMILY, type, id);
    }

    private static String productionLifecycleId(String adapterId, String owner) {
        return adapterId + "#" + owner;
    }

    private record OutputKey(String adapterId, String owner) {
    }

    private record MatchedPath(String adapterId, String kind, String sourcePath, String targetPath,
                               String sourceHash, String targetHash, String sourceClaimOwner,
                               String targetOwner) {
        private String identity() {
            return adapterId + "\u0000" + kind + "\u0000" + sourcePath + "\u0000" + targetPath + "\u0000"
                + sourceHash + "\u0000" + targetHash + "\u0000" + sourceClaimOwner + "\u0000" + targetOwner;
        }
    }

    private record MatchedChange(String adapterId, TypedLifecycleMigrationAdapter.Change change,
                                 String operationKind, String sourceHash, String targetHash,
                                 String sourceClaimOwner, String targetOwner) {
        private String productionKind() {
            return change.kind();
        }

        private String identity() {
            return adapterId + "\u0000" + operationKind + "\u0000" + change.kind() + "\u0000"
                + change.sourcePath() + "\u0000" + change.targetPath() + "\u0000"
                + sourceHash + "\u0000" + targetHash + "\u0000" + sourceClaimOwner + "\u0000" + targetOwner;
        }
    }

    private static final class MutableOutput {
        private final String adapterId;
        private final String owner;
        private final List<AssetAdoptionArtifactProducer.Claim> claims = new ArrayList<>();
        private final List<AssetAdoptionArtifactProducer.AcceptedChange> changes = new ArrayList<>();
        private final List<AssetAdoptionArtifactProducer.AssetRecord> assets = new ArrayList<>();

        private MutableOutput(String adapterId, String owner) {
            this.adapterId = adapterId;
            this.owner = owner;
        }

        private AssetAdoptionArtifactProducer.LifecycleOutput value() {
            return new AssetAdoptionArtifactProducer.LifecycleOutput(productionLifecycleId(adapterId, owner), owner,
                claims, changes, assets);
        }
    }

    private record AssetLineage(String type, String id, long revision, String mutationId,
                                String priorPayloadHash, Path originalLogicalPath,
                                Path canonicalFuturePath,
                                AssetAdoptionArtifactProducer.AuxiliaryKey auxiliaryKey) {
    }
}
