package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationStager;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;
import restudio.resync.upgrade.lifecycle.AutomationMigrationAdapter;
import restudio.resync.upgrade.lifecycle.CustomContentMigrationAdapter;
import restudio.resync.upgrade.lifecycle.ExtensionStateMigrationAdapter;
import restudio.resync.upgrade.lifecycle.JsonAssetMigrationAdapter;
import restudio.resync.upgrade.lifecycle.ManagedFileMigrationAdapter;
import restudio.resync.upgrade.lifecycle.ReplacementActivationRecordMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TombstoneMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TimerDefinitionMigrationAdapter;
import restudio.resync.upgrade.lifecycle.WorldGenMigrationAdapter;

public final class ReSyncTypedLifecycleUpgrade implements UpgradePlanner, MigrationStager {
    private static final String QUARANTINE_ROOT = ".quarantine/migration";
    private static final String PRODUCTION_MANAGED_FILES_ID = "resync.lifecycle.managed-files-v1";

    private final List<TypedLifecycleMigrationAdapter> adapters;
    private final Set<String> exclusiveRoutingAdapterIds;

    public ReSyncTypedLifecycleUpgrade(Collection<? extends TypedLifecycleMigrationAdapter> adapters) {
        this(adapters, Set.of());
    }

    private ReSyncTypedLifecycleUpgrade(Collection<? extends TypedLifecycleMigrationAdapter> adapters, Set<String> exclusiveRoutingAdapterIds) {
        List<TypedLifecycleMigrationAdapter> sorted = new ArrayList<>(adapters == null ? List.of() : adapters);
        sorted.forEach(adapter -> requireText(Objects.requireNonNull(adapter, "adapter").adapterId(), "adapterId"));
        sorted.sort(Comparator.comparing(TypedLifecycleMigrationAdapter::adapterId));
        Set<String> ids = new HashSet<>();
        for (TypedLifecycleMigrationAdapter adapter : sorted) {
            String adapterId = requireText(adapter.adapterId(), "adapterId");
            if (!ids.add(adapterId)) {
                throw new IllegalArgumentException("Duplicate Typed Lifecycle Adapter: " + adapterId);
            }
        }
        this.adapters = List.copyOf(sorted);
        this.exclusiveRoutingAdapterIds = Set.copyOf(exclusiveRoutingAdapterIds);
        if (!ids.containsAll(this.exclusiveRoutingAdapterIds)) {
            throw new IllegalArgumentException("Exclusive Lifecycle Routing References An Unregistered Adapter");
        }
    }

    public static ReSyncTypedLifecycleUpgrade production() {
        return new ReSyncTypedLifecycleUpgrade(List.of(
            new AutomationMigrationAdapter(),
            new CustomContentMigrationAdapter(),
            new ExtensionStateMigrationAdapter(),
            new JsonAssetMigrationAdapter(),
            new ManagedFileMigrationAdapter(PRODUCTION_MANAGED_FILES_ID, List.of(
                ManagedFileMigrationAdapter.Policy.preserve(ProductionPersistenceOwners.CONFIGURATION, "config.properties"),
                ManagedFileMigrationAdapter.Policy.preserve(ProductionPersistenceOwners.FLOW_ASSETS, "assets/project.json"))),
            new NetworkReconciliationMigrationAdapter(),
            new ReplacementActivationRecordMigrationAdapter(),
            new TimerDefinitionMigrationAdapter(),
            new TombstoneMigrationAdapter(),
            new WorldGenMigrationAdapter()), Set.of(TombstoneMigrationAdapter.ID));
    }

    public List<TypedLifecycleMigrationAdapter> adapters() {
        return adapters;
    }

    public Map<String, TypedLifecycleMigrationAdapter.Adaptation> authoritativeAdaptations(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        ImmutableSnapshotAdapter.View view = ImmutableSnapshotAdapter.adapt(snapshot);
        Map<String, TypedLifecycleMigrationAdapter.Adaptation> adaptations = new TreeMap<>();
        resolveAdaptations(input(snapshot.root(), view.metadata(), view.manifestHash(), view.entries()))
            .forEach((adapter, adaptation) -> adaptations.put(adapter.adapterId(), adaptation));
        return Map.copyOf(adaptations);
    }

    @Override
    public UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException {
        Objects.requireNonNull(sourceWindow, "sourceWindow");
        ImmutableSnapshotAdapter.View snapshot = ImmutableSnapshotAdapter.adapt(sourceSnapshot);
        sourceWindow.requireSupported(snapshot.metadata());
        Composition composition = compose(input(sourceSnapshot.root(), snapshot.metadata(), snapshot.manifestHash(), snapshot.entries()),
            sourceWindow.sourceFormatVersion(), sourceWindow.targetFormatVersion());
        return composition.proposal();
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
        QuarantineReport report = QuarantineReport.empty();
        if (!report.reportHash().equals(plan.quarantineReportHash())) {
            throw new MigrationException("Typed Lifecycle Staging Requires Its Quarantine Report And Acceptance");
        }
        return stage(sourceRoot, stagingRoot, plan, report, report.accept("typed-lifecycle-stager", Instant.EPOCH));
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan, QuarantineReport report, QuarantineAcceptance acceptance) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(acceptance, "acceptance");
        if (!plan.quarantineReportHash().equals(report.reportHash())) {
            throw new MigrationException("Typed Lifecycle Plan Does Not Match Quarantine Report");
        }
        report.requireAccepted(acceptance);
        Path source = MigrationPaths.requireDirectory(sourceRoot, "snapshotRoot");
        SnapshotManifest manifest = SnapshotManifest.read(sidecar(source, ".manifest"));
        if (!manifest.manifestHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Typed Lifecycle Snapshot Manifest Does Not Match Plan");
        }
        if (manifest.metadata().formatVersion() != plan.sourceFormatVersion()) {
            throw new MigrationException("Typed Lifecycle Snapshot Format Does Not Match Plan");
        }
        var verification = manifest.verify(source);
        verification.requireVerified();
        if (!verification.manifestHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Typed Lifecycle Snapshot Verification Does Not Match Plan");
        }
        Composition composition = compose(input(source, manifest), plan.sourceFormatVersion(), plan.targetFormatVersion());
        MigrationPlan expectedPlan = plan.invocationHash().isEmpty()
            ? composition.proposal().plan()
            : composition.proposal().plan().withInvocationHash(plan.invocationHash());
        if (!expectedPlan.canonicalText().equals(plan.canonicalText())) {
            throw new MigrationException("Typed Lifecycle Adapter Output Does Not Match Plan");
        }
        if (!composition.proposal().quarantineReport().canonicalText().equals(report.canonicalText())) {
            throw new MigrationException("Typed Lifecycle Adapter Quarantine Does Not Match Accepted Report");
        }

        Path staging = prepareStagingRoot(source, stagingRoot);
        copySnapshot(source, staging);
        var copied = manifest.verify(staging);
        copied.requireVerified();
        if (!copied.manifestHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Typed Lifecycle Copied Snapshot Does Not Match Plan");
        }
        for (BoundChange bound : composition.changes()) {
            if (bound.change().targetPath().isEmpty()) {
                continue;
            }
            Path target = MigrationPaths.resolveInside(staging, bound.change().targetPath());
            AtomicFiles.write(target, bound.change().targetBytes());
            if (!bound.change().targetHash().equals(sha256(Files.readAllBytes(target)))) {
                throw new MigrationException("Typed Lifecycle Staged Target Hash Does Not Match Plan: " + bound.change().targetPath());
            }
        }
        for (BoundChange bound : composition.changes()) {
            MigrationOperationType type = operationType(bound.change());
            if (type == MigrationOperationType.GENERATE || type == MigrationOperationType.COPY || type == MigrationOperationType.CONVERT) {
                continue;
            }
            Path target = MigrationPaths.resolveInside(staging, bound.change().sourcePath());
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Typed Lifecycle Delete Source Is Missing: " + bound.change().sourcePath());
            }
            Files.delete(target);
        }
        materializeQuarantine(staging, report);
        MigrationPaths.requireNoSymlinkTree(staging);
        return new StagedMigration(staging, Optional.empty(), plan.planHash(), TreeDigest.of(staging));
    }

    private Composition compose(TypedLifecycleMigrationAdapter.Input input, int sourceFormat, int targetFormat) throws IOException {
        Map<String, TypedLifecycleMigrationAdapter.SourceFile> sources = new LinkedHashMap<>();
        input.files().forEach(source -> sources.put(source.relativePath(), source));
        Set<String> retainedEvidence = sources.keySet().stream().filter(ReSyncTypedLifecycleUpgrade::isRetainedEvidence).collect(Collectors.toSet());
        TypedLifecycleMigrationAdapter.Input adapterInput = new TypedLifecycleMigrationAdapter.Input(
            input.root(),
            input.metadata(),
            input.manifestHash(),
            input.files().stream().filter(source -> !retainedEvidence.contains(source.relativePath())).toList());
        Map<String, String> sourceAliases = new HashMap<>();
        for (String sourcePath : sources.keySet()) {
            if (!retainedEvidence.contains(sourcePath)) {
                rejectReserved(sourcePath, "source");
            }
            String alias = sourceAliases.putIfAbsent(sourcePath.toLowerCase(Locale.ROOT), sourcePath);
            if (alias != null && !alias.equals(sourcePath)) {
                throw new MigrationException("Typed Lifecycle Snapshot Contains Case-Aliased Sources: " + alias + " And " + sourcePath);
            }
        }
        Map<String, List<BoundClaim>> claims = new HashMap<>();
        List<BoundAdaptation> adaptations = new ArrayList<>();
        List<BoundQuarantine> adapterQuarantine = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        Map<TypedLifecycleMigrationAdapter, TypedLifecycleMigrationAdapter.Adaptation> resolvedAdaptations = resolveAdaptations(adapterInput);

        for (TypedLifecycleMigrationAdapter adapter : adapters) {
            TypedLifecycleMigrationAdapter.Adaptation adaptation = resolvedAdaptations.get(adapter);
            BoundAdaptation bound = new BoundAdaptation(adapter, adaptation);
            adaptations.add(bound);
            for (TypedLifecycleMigrationAdapter.Claim claim : adaptation.claims()) {
                if (retainedEvidence.contains(claim.relativePath())) {
                    throw new MigrationException("Typed Lifecycle Adapter Cannot Claim Retained Quarantine Evidence: " + claim.relativePath());
                }
                if (!sources.containsKey(claim.relativePath())) {
                    throw new MigrationException("Typed Lifecycle Adapter Claimed A Missing Source: " + adapter.adapterId() + " -> " + claim.relativePath());
                }
                claims.computeIfAbsent(claim.relativePath(), ignored -> new ArrayList<>()).add(new BoundClaim(adapter, claim));
            }
            for (QuarantineRecord record : adaptation.quarantineRecords()) {
                adapterQuarantine.add(new BoundQuarantine(adapter, record));
            }
        }

        Map<String, TypedLifecycleMigrationAdapter> owners = new HashMap<>();
        Set<String> blocked = new HashSet<>();
        Set<String> adapterQuarantined = new HashSet<>();
        Set<TypedLifecycleMigrationAdapter> ownerMismatchAdapters = new HashSet<>();
        for (TypedLifecycleMigrationAdapter.SourceFile source : input.files()) {
            if (retainedEvidence.contains(source.relativePath())) {
                if (!claims.getOrDefault(source.relativePath(), List.of()).isEmpty()) {
                    throw new MigrationException("Retained Quarantine Evidence Has An External Adapter Claim: " + source.relativePath());
                }
                continue;
            }
            List<BoundClaim> sourceClaims = claims.getOrDefault(source.relativePath(), List.of());
            if (sourceClaims.isEmpty()) {
                quarantine.add(quarantine(source, "MIGRATION.LIFECYCLE_OWNER_MISSING", "No typed lifecycle adapter owns this source file.", "Register exactly one adapter for this persisted source."));
                blocked.add(source.relativePath());
                continue;
            }
            if (sourceClaims.size() != 1) {
                quarantine.add(quarantine(source, "MIGRATION.LIFECYCLE_OWNER_AMBIGUOUS", "Multiple typed lifecycle adapters own this source file.", "Keep exactly one authoritative adapter for this persisted source."));
                blocked.add(source.relativePath());
                continue;
            }
            BoundClaim bound = sourceClaims.getFirst();
            if (!bound.claim().owner().equals(source.owner())) {
                quarantine.add(quarantine(source, "MIGRATION.LIFECYCLE_OWNER_MISMATCH", "The lifecycle adapter owner does not match the verified snapshot owner.", "Bind this path to its authoritative persistence participant."));
                blocked.add(source.relativePath());
                ownerMismatchAdapters.add(bound.adapter());
                continue;
            }
            owners.put(source.relativePath(), bound.adapter());
        }
        for (TypedLifecycleMigrationAdapter.SourceFile source : input.files()) {
            input.read(source);
        }
        for (BoundQuarantine bound : adapterQuarantine) {
            QuarantineRecord record = bound.record();
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            rejectReserved(sourceLocation, "quarantine source");
            requireRecordId(record.recordId());
            TypedLifecycleMigrationAdapter.SourceFile source = sources.get(sourceLocation);
            if (source == null || owners.get(sourceLocation) != bound.adapter() || record.sourceHash().isEmpty() || !source.sha256().equals(record.sourceHash())) {
                throw new MigrationException("Typed Lifecycle Adapter Quarantine Is Not Bound To Its Uniquely Owned Verified Source: " + bound.adapter().adapterId() + " -> " + sourceLocation);
            }
            String recordId = "lifecycle-" + sha256(bound.adapter().adapterId() + "\n" + record.recordId() + "\n" + sourceLocation + "\n" + record.sourceHash()).substring(0, 32);
            quarantine.add(new QuarantineRecord(
                recordId,
                record.code(),
                sourceLocation,
                record.reason(),
                record.affectedReferences(),
                record.suggestedAction(),
                record.sourceHash()));
            blocked.add(sourceLocation);
            adapterQuarantined.add(sourceLocation);
        }
        Set<TypedLifecycleMigrationAdapter> unavailableAdapters = new HashSet<>(adaptations.stream()
            .filter(bound -> bound.adaptation().claims().stream()
                .anyMatch(claim -> owners.get(claim.relativePath()) != bound.adapter()
                    || (blocked.contains(claim.relativePath()) && !adapterQuarantined.contains(claim.relativePath()))))
            .map(BoundAdaptation::adapter)
            .collect(Collectors.toSet()));
        for (BoundAdaptation bound : adaptations) {
            if (!unavailableAdapters.contains(bound.adapter())) {
                continue;
            }
            if (ownerMismatchAdapters.contains(bound.adapter())) {
                continue;
            }
            for (TypedLifecycleMigrationAdapter.Claim claim : bound.adaptation().claims()) {
                if (blocked.add(claim.relativePath())) {
                    TypedLifecycleMigrationAdapter.SourceFile source = sources.get(claim.relativePath());
                    quarantine.add(quarantine(
                        source,
                        "MIGRATION.LIFECYCLE_DEPENDENCY_BLOCKED",
                        "Another source required by this adapter cannot be migrated safely.",
                        "Resolve the blocked dependency group before retrying this lifecycle upgrade."));
                }
            }
        }

        List<BoundChange> candidates = new ArrayList<>();
        for (BoundAdaptation bound : adaptations) {
            if (unavailableAdapters.contains(bound.adapter())) {
                continue;
            }
            for (TypedLifecycleMigrationAdapter.Change change : bound.adaptation().changes()) {
                if (change.sourcePath().isEmpty()) {
                    if (operationType(change) != MigrationOperationType.GENERATE) {
                        throw new MigrationException("Unsourced Lifecycle Changes Must Generate A Deterministic Target: " + bound.adapter().adapterId());
                    }
                    if (bound.adaptation().claims().isEmpty()) {
                        throw new MigrationException("Unsourced Lifecycle Generate Has No Declared Dependencies: " + bound.adapter().adapterId());
                    }
                    rejectReserved(change.targetPath(), "target");
                    String aliasedTarget = sourceAliases.get(change.targetPath().toLowerCase(Locale.ROOT));
                    if (aliasedTarget != null || sources.containsKey(change.targetPath())) {
                        throw new MigrationException("Unsourced Lifecycle Generate Collides With An Existing Source: " + change.targetPath());
                    }
                    candidates.add(new BoundChange(bound.adapter(), null, change));
                    continue;
                }
                TypedLifecycleMigrationAdapter.SourceFile source = sources.get(change.sourcePath());
                if (source == null) {
                    throw new MigrationException("Typed Lifecycle Change Uses A Missing Source: " + bound.adapter().adapterId() + " -> " + change.sourcePath());
                }
                if (owners.get(change.sourcePath()) != bound.adapter()) {
                    if (blocked.contains(change.sourcePath()) && !adapterQuarantined.contains(change.sourcePath())) {
                        continue;
                    }
                    throw new MigrationException("Typed Lifecycle Change Is Not Bound To Its Unique Adapter Claim: " + bound.adapter().adapterId() + " -> " + change.sourcePath());
                }
                if (blocked.contains(change.sourcePath())) {
                    if (adapterQuarantined.contains(change.sourcePath())) {
                        throw new MigrationException("Typed Lifecycle Adapter Cannot Change A Quarantined Source: " + bound.adapter().adapterId() + " -> " + change.sourcePath());
                    }
                    continue;
                }
                if (!change.targetPath().isEmpty()) {
                    rejectReserved(change.targetPath(), "target");
                }
                if (!change.targetPath().isEmpty()
                    && !change.targetPath().equals(change.sourcePath())
                    && sources.containsKey(change.targetPath())) {
                    String code = owners.get(change.targetPath()) == bound.adapter()
                        ? "MIGRATION.LIFECYCLE_TARGET_EXISTS"
                        : "MIGRATION.LIFECYCLE_TARGET_OWNER_MISMATCH";
                    quarantine.add(quarantine(source, code, "The lifecycle change would overwrite a different existing source path.", "Replace an existing file through a change sourced from that exact path."));
                    blocked.add(change.sourcePath());
                    continue;
                }
                String aliasedTarget = change.targetPath().isEmpty() ? null : sourceAliases.get(change.targetPath().toLowerCase(Locale.ROOT));
                if (aliasedTarget != null && !aliasedTarget.equals(change.targetPath())) {
                    quarantine.add(quarantine(source, "MIGRATION.LIFECYCLE_TARGET_CASE_ALIAS", "The lifecycle change target aliases an existing source path by case.", "Use an exact non-aliased replacement path on every supported file system."));
                    blocked.add(change.sourcePath());
                    continue;
                }
                if (change.targetPath().equals(change.sourcePath()) && change.targetHash().equals(source.sha256())) {
                    continue;
                }
                candidates.add(new BoundChange(bound.adapter(), source, change));
            }
        }

        Set<BoundChange> rejected = new HashSet<>();
        Map<String, List<BoundChange>> changesBySource = new HashMap<>();
        candidates.stream().filter(candidate -> !candidate.change().sourcePath().isEmpty())
            .forEach(candidate -> changesBySource.computeIfAbsent(candidate.change().sourcePath(), ignored -> new ArrayList<>()).add(candidate));
        for (List<BoundChange> sourceChanges : changesBySource.values()) {
            boolean retiresSource = sourceChanges.stream().anyMatch(candidate -> {
                MigrationOperationType type = operationType(candidate.change());
                return type == MigrationOperationType.DELETE || type == MigrationOperationType.MOVE
                    || type == MigrationOperationType.RENAME || type == MigrationOperationType.REPLACE;
            });
            if (retiresSource && sourceChanges.size() > 1) {
                for (BoundChange candidate : sourceChanges) {
                    rejectCollision(candidate, quarantine, rejected, "A destructive lifecycle source cannot be reused by another change.");
                }
            }
        }
        for (BoundChange candidate : candidates) {
            if (candidate.change().targetPath().isEmpty()) {
                continue;
            }
            String target = candidate.change().targetPath();
            boolean sourceCollision = sources.keySet().stream()
                .anyMatch(sourcePath -> !sourcePath.equals(candidate.change().sourcePath())
                    && hierarchicalCollision(target, sourcePath));
            Path targetPath = MigrationPaths.resolveInside(input.root(), target);
            if (sourceCollision || Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                if (candidate.source() == null) {
                    throw new MigrationException("Unsourced Lifecycle Generate Collides With An Existing Hierarchy: " + target);
                }
                rejectCollision(candidate, quarantine, rejected, "The lifecycle target conflicts with an existing file or directory hierarchy.");
            }
        }
        for (int first = 0; first < candidates.size(); first++) {
            BoundChange left = candidates.get(first);
            if (left.change().targetPath().isEmpty()) {
                continue;
            }
            for (int second = first + 1; second < candidates.size(); second++) {
                BoundChange right = candidates.get(second);
                if (!right.change().targetPath().isEmpty() && hierarchicalCollision(left.change().targetPath(), right.change().targetPath())) {
                    if (left.source() == null || right.source() == null) {
                        throw new MigrationException("Unsourced Lifecycle Generate Collides With Another Output: " + left.change().targetPath() + " -> " + right.change().targetPath());
                    }
                    rejectCollision(left, quarantine, rejected, "Lifecycle outputs conflict as a file and directory hierarchy.");
                    rejectCollision(right, quarantine, rejected, "Lifecycle outputs conflict as a file and directory hierarchy.");
                }
            }
        }
        Map<String, List<BoundChange>> targets = new HashMap<>();
        Map<String, List<BoundChange>> deletes = new HashMap<>();
        for (BoundChange candidate : candidates) {
            if (candidate.change().targetPath().isEmpty()) {
                deletes.computeIfAbsent(candidate.change().sourcePath().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(candidate);
            } else {
                targets.computeIfAbsent(candidate.change().targetPath().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        Set<String> deletedPaths = deletes.keySet();
        for (List<BoundChange> duplicateDeletes : deletes.values()) {
            if (duplicateDeletes.size() > 1) {
                for (BoundChange candidate : duplicateDeletes) {
                    rejectCollision(candidate, quarantine, rejected, "Multiple lifecycle changes delete the same source path.");
                }
            }
        }
        for (Map.Entry<String, List<BoundChange>> entry : targets.entrySet()) {
            if (entry.getValue().size() > 1 || deletedPaths.contains(entry.getKey())) {
                if (entry.getValue().stream().anyMatch(candidate -> candidate.source() == null)) {
                    throw new MigrationException("Unsourced Lifecycle Generate Has A Conflicting Target: " + entry.getKey());
                }
                for (BoundChange candidate : entry.getValue()) {
                    rejectCollision(candidate, quarantine, rejected, "Multiple lifecycle changes target the same replacement path.");
                }
            }
        }
        for (BoundChange candidate : candidates) {
            if (candidate.change().targetPath().isEmpty() && targets.containsKey(candidate.change().sourcePath().toLowerCase(Locale.ROOT))) {
                if (targets.get(candidate.change().sourcePath().toLowerCase(Locale.ROOT)).stream().anyMatch(target -> target.source() == null)) {
                    throw new MigrationException("Unsourced Lifecycle Generate Conflicts With A Source Delete: " + candidate.change().sourcePath());
                }
                rejectCollision(candidate, quarantine, rejected, "A lifecycle delete conflicts with a replacement targeting the same path.");
            }
        }
        Set<String> rejectedSources = rejected.stream().map(value -> value.change().sourcePath()).collect(Collectors.toSet());
        blocked.addAll(rejectedSources);
        boolean dependencyGroupChanged;
        do {
            dependencyGroupChanged = false;
            for (BoundAdaptation bound : adaptations) {
                if (unavailableAdapters.contains(bound.adapter())
                    || bound.adaptation().claims().stream()
                    .noneMatch(claim -> blocked.contains(claim.relativePath())
                        && !adapterQuarantined.contains(claim.relativePath()))) {
                    continue;
                }
                unavailableAdapters.add(bound.adapter());
                dependencyGroupChanged = true;
                for (TypedLifecycleMigrationAdapter.Claim claim : bound.adaptation().claims()) {
                    if (blocked.add(claim.relativePath())) {
                        quarantine.add(quarantine(
                            sources.get(claim.relativePath()),
                            "MIGRATION.LIFECYCLE_DEPENDENCY_BLOCKED",
                            "Another source required by this adapter cannot be migrated safely.",
                            "Resolve the blocked dependency group before retrying this lifecycle upgrade."));
                    }
                }
            }
        } while (dependencyGroupChanged);

        List<MigrationOperation> operations = new ArrayList<>();
        List<BoundChange> acceptedChanges = new ArrayList<>();
        for (BoundChange candidate : candidates) {
            if (unavailableAdapters.contains(candidate.adapter()) || blocked.contains(candidate.change().sourcePath())
                || rejectedSources.contains(candidate.change().sourcePath())) {
                continue;
            }
            if (candidate.change().targetPath().isEmpty()) {
                operations.add(new MigrationOperation(
                    MigrationOperationType.DELETE,
                    candidate.adapter().adapterId(),
                    candidate.change().sourcePath(),
                    "",
                    candidate.source().sha256(),
                    ""));
            } else if (candidate.change().targetPath().equals(candidate.change().sourcePath())) {
                operations.add(new MigrationOperation(
                    MigrationOperationType.CONVERT,
                    candidate.adapter().adapterId(),
                    candidate.change().sourcePath(),
                    candidate.change().targetPath(),
                    candidate.source().sha256(),
                    candidate.change().targetHash()));
            } else {
                MigrationOperationType type = operationType(candidate.change());
                if ((type == MigrationOperationType.MOVE || type == MigrationOperationType.RENAME)
                    && !candidate.source().sha256().equals(candidate.change().targetHash())) {
                    throw new MigrationException("Typed Lifecycle Move Or Rename Must Preserve Source Bytes: " + candidate.change().sourcePath());
                }
                operations.add(new MigrationOperation(
                    type,
                    candidate.adapter().adapterId(),
                    type.sourceRequired() ? candidate.change().sourcePath() : "",
                    candidate.change().targetPath(),
                    type.sourceRequired() ? candidate.source().sha256() : "",
                    candidate.change().targetHash()));
            }
            acceptedChanges.add(candidate);
        }
        Set<String> operationSources = new HashSet<>();
        for (MigrationOperation operation : operations) {
            if (!operation.sourcePath().isEmpty() && !operationSources.add(operation.sourcePath().toLowerCase(Locale.ROOT))) {
                throw new MigrationException("Typed Lifecycle Plan Reuses A Migration Source: " + operation.sourcePath());
            }
        }

        QuarantineReport report = new QuarantineReport(normalizeQuarantine(quarantine));
        MigrationPlan plan = new MigrationPlan(
            input.metadata().snapshotId(),
            input.manifestHash(),
            sourceFormat,
            targetFormat,
            report.reportHash(),
            operations);
        acceptedChanges.sort(Comparator.comparing((BoundChange value) -> value.adapter().adapterId())
            .thenComparing(value -> value.change().sourcePath())
            .thenComparing(value -> value.change().targetPath())
            .thenComparing(value -> value.change().kind()));
        return new Composition(new UpgradeProposal(plan, report, new DiagnosticSet(List.of())), List.copyOf(acceptedChanges));
    }

    private Map<TypedLifecycleMigrationAdapter, TypedLifecycleMigrationAdapter.Adaptation> resolveAdaptations(
        TypedLifecycleMigrationAdapter.Input input
    ) throws IOException {
        Map<TypedLifecycleMigrationAdapter, TypedLifecycleMigrationAdapter.Adaptation> resolved = new LinkedHashMap<>();
        Set<String> exclusivelyClaimed = new HashSet<>();
        for (TypedLifecycleMigrationAdapter adapter : adapters) {
            if (!exclusiveRoutingAdapterIds.contains(adapter.adapterId())) {
                continue;
            }
            TypedLifecycleMigrationAdapter.Adaptation adaptation = Objects.requireNonNull(adapter.adapt(input), "adapter adaptation");
            resolved.put(adapter, adaptation);
            adaptation.claims().forEach(claim -> exclusivelyClaimed.add(claim.relativePath()));
        }
        TypedLifecycleMigrationAdapter.Input routed = exclusivelyClaimed.isEmpty() ? input : new TypedLifecycleMigrationAdapter.Input(
            input.root(),
            input.metadata(),
            input.manifestHash(),
            input.files().stream().filter(source -> !exclusivelyClaimed.contains(source.relativePath())).toList());
        for (TypedLifecycleMigrationAdapter adapter : adapters) {
            if (!resolved.containsKey(adapter)) {
                resolved.put(adapter, Objects.requireNonNull(adapter.adapt(routed), "adapter adaptation"));
            }
        }
        return resolved;
    }

    private static TypedLifecycleMigrationAdapter.Input input(Path root, SnapshotManifest manifest) {
        return input(root, manifest.metadata(), manifest.manifestHash(), manifest.entries().stream()
            .map(entry -> new ImmutableSnapshotAdapter.Entry(entry.relativePath(), entry.size(), entry.sha256(), entry.owner()))
            .toList());
    }

    private static TypedLifecycleMigrationAdapter.Input input(Path root, SnapshotMetadata metadata, String manifestHash, List<ImmutableSnapshotAdapter.Entry> entries) {
        return new TypedLifecycleMigrationAdapter.Input(root, metadata, manifestHash, entries.stream()
            .map(entry -> new TypedLifecycleMigrationAdapter.SourceFile(entry.relativePath(), entry.size(), entry.sha256(), entry.owner()))
            .toList());
    }

    private static Path sidecar(Path root, String suffix) {
        Path parent = root.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Snapshot Root Has No Parent");
        }
        return parent.resolve(root.getFileName() + suffix).toAbsolutePath().normalize();
    }

    private static Path prepareStagingRoot(Path source, Path stagingRoot) throws IOException {
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        MigrationPaths.requireDistinctRoots(source, staging);
        MigrationPaths.requireWritableParent(staging);
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("stagingRoot Must Be A Non-Symbolic-Link Directory");
            }
            try (var entries = Files.list(staging)) {
                if (entries.findAny().isPresent()) {
                    throw new MigrationException("stagingRoot Must Be Empty");
                }
            }
        } else {
            Files.createDirectories(staging);
        }
        MigrationPaths.requireNoSymlinkTraversal(staging.getParent(), staging);
        return staging;
    }

    private static void copySnapshot(Path source, Path staging) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Typed Lifecycle Snapshot Contains A Symbolic-Link Directory: " + directory);
                }
                Path destination = directory.equals(source) ? staging : MigrationPaths.resolveInside(staging, MigrationPaths.relative(source, directory));
                Files.createDirectories(destination);
                MigrationPaths.requireNoSymlinkTraversal(staging, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Typed Lifecycle Snapshot Contains A Non-Regular File: " + file);
                }
                AtomicFiles.copy(file, MigrationPaths.resolveInside(staging, MigrationPaths.relative(source, file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Copy Typed Lifecycle Snapshot: " + file, exception);
            }
        });
    }

    private static void materializeQuarantine(Path staging, QuarantineReport report) throws IOException {
        for (QuarantineRecord record : report.records()) {
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            rejectReserved(sourceLocation, "quarantine source");
            requireRecordId(record.recordId());
            Path active = MigrationPaths.resolveInside(staging, sourceLocation);
            if (Files.isSymbolicLink(active) || !Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Typed Lifecycle Quarantine Source Is Missing: " + sourceLocation);
            }
            if (record.sourceHash().isEmpty() || !record.sourceHash().equals(sha256(Files.readAllBytes(active)))) {
                throw new MigrationException("Typed Lifecycle Quarantine Source Hash Does Not Match: " + sourceLocation);
            }
            Path destination = MigrationPaths.resolveInside(staging, QUARANTINE_ROOT + "/" + record.recordId() + "/" + sourceLocation);
            AtomicFiles.copy(active, destination);
            Files.delete(active);
        }
    }

    private static void rejectCollision(BoundChange candidate, List<QuarantineRecord> quarantine, Set<BoundChange> rejected, String reason) {
        if (!rejected.add(candidate)) {
            return;
        }
        quarantine.add(quarantine(candidate.source(), "MIGRATION.LIFECYCLE_TARGET_COLLISION", reason, "Give every lifecycle output one non-conflicting target path."));
    }

    private static void rejectReserved(String path, String field) throws MigrationException {
        String normalized = MigrationPaths.requireRelative(path);
        String folded = normalized.toLowerCase(Locale.ROOT);
        if (folded.equals(".quarantine") || folded.startsWith(".quarantine/")) {
            throw new MigrationException("Typed Lifecycle " + field + " Uses The Reserved Quarantine Root: " + normalized);
        }
    }

    private static String requireRecordId(String value) throws MigrationException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new MigrationException("Typed Lifecycle Quarantine Record ID Must Be One Safe Path Segment");
        }
        return value;
    }

    private static boolean hierarchicalCollision(String first, String second) {
        String normalizedFirst = first.toLowerCase(Locale.ROOT);
        String normalizedSecond = second.toLowerCase(Locale.ROOT);
        return normalizedFirst.equals(normalizedSecond)
            || normalizedFirst.startsWith(normalizedSecond + "/")
            || normalizedSecond.startsWith(normalizedFirst + "/");
    }

    private static boolean isRetainedEvidence(String path) {
        String prefix = QUARANTINE_ROOT + "/";
        if (path == null || !path.startsWith(prefix)) {
            return false;
        }
        String remainder = path.substring(prefix.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0 || separator == remainder.length() - 1) {
            return false;
        }
        String recordId = remainder.substring(0, separator);
        String sourcePath = remainder.substring(separator + 1);
        return recordId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
            && !sourcePath.equals(".quarantine")
            && !sourcePath.startsWith(".quarantine/");
    }

    private static MigrationOperationType operationType(TypedLifecycleMigrationAdapter.Change change) {
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
            default -> MigrationOperationType.GENERATE;
        };
    }

    private static QuarantineRecord quarantine(TypedLifecycleMigrationAdapter.SourceFile source, String code, String reason, String action) {
        String recordId = "lifecycle-" + sha256(code + "\n" + source.relativePath() + "\n" + source.sha256() + "\n" + reason).substring(0, 24);
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, List.of(), action, source.sha256());
    }

    private static List<QuarantineRecord> normalizeQuarantine(List<QuarantineRecord> records) throws MigrationException {
        Map<String, List<QuarantineRecord>> bySource = new TreeMap<>();
        for (QuarantineRecord record : records) {
            bySource.computeIfAbsent(record.sourceLocation(), ignored -> new ArrayList<>()).add(record);
        }
        List<QuarantineRecord> normalized = new ArrayList<>();
        for (Map.Entry<String, List<QuarantineRecord>> entry : bySource.entrySet()) {
            List<QuarantineRecord> sourceRecords = entry.getValue().stream().sorted(Comparator.comparing(QuarantineRecord::recordId)).toList();
            String sourceHash = sourceRecords.getFirst().sourceHash();
            if (sourceHash.isEmpty() || sourceRecords.stream().anyMatch(record -> !record.sourceHash().equals(sourceHash))) {
                throw new MigrationException("Typed Lifecycle Quarantine Records Do Not Share One Verified Source Hash: " + entry.getKey());
            }
            if (sourceRecords.size() == 1) {
                normalized.add(sourceRecords.getFirst());
                continue;
            }
            TreeSet<String> codes = new TreeSet<>();
            TreeSet<String> reasons = new TreeSet<>();
            TreeSet<String> references = new TreeSet<>();
            TreeSet<String> actions = new TreeSet<>();
            StringBuilder identity = new StringBuilder(entry.getKey()).append('\n').append(sourceHash);
            for (QuarantineRecord record : sourceRecords) {
                codes.add(record.code());
                reasons.add(record.reason());
                references.addAll(record.affectedReferences());
                actions.add(record.suggestedAction());
                identity.append('\n').append(record.recordId());
            }
            normalized.add(new QuarantineRecord(
                "lifecycle-" + sha256(identity.toString()).substring(0, 32),
                codes.size() == 1 ? codes.getFirst() : "MIGRATION.LIFECYCLE_SOURCE_QUARANTINED",
                entry.getKey(),
                String.join("; ", reasons),
                List.copyOf(references),
                String.join("; ", actions),
                sourceHash));
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private record BoundClaim(TypedLifecycleMigrationAdapter adapter, TypedLifecycleMigrationAdapter.Claim claim) {
    }

    private record BoundAdaptation(TypedLifecycleMigrationAdapter adapter, TypedLifecycleMigrationAdapter.Adaptation adaptation) {
    }

    private record BoundQuarantine(TypedLifecycleMigrationAdapter adapter, QuarantineRecord record) {
    }

    private record BoundChange(TypedLifecycleMigrationAdapter adapter, TypedLifecycleMigrationAdapter.SourceFile source, TypedLifecycleMigrationAdapter.Change change) {
    }

    private record Composition(UpgradeProposal proposal, List<BoundChange> changes) {
    }
}
