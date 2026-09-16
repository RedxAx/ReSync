package restudio.resync.upgrade;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Collectors;

public final class StandaloneUpgradePlanner implements UpgradePlanner {
    public static final String MISSING_ADAPTER_CODE = "MIGRATION.UPGRADE_ADAPTER_MISSING";
    public static final String COLLISION_CODE = "MIGRATION.UPGRADE_ADAPTER_COLLISION";
    public static final String OWNER_MISMATCH_CODE = "MIGRATION.UPGRADE_ADAPTER_OWNER_MISMATCH";
    public static final String TRANSFORM_FAILED_CODE = "MIGRATION.UPGRADE_ADAPTER_FAILED";
    public static final String CASE_COLLISION_CODE = "MIGRATION.UPGRADE_CASE_COLLISION";
    public static final String TARGET_DIRECTORY_CODE = "MIGRATION.UPGRADE_TARGET_DIRECTORY";
    public static final String TARGET_PREFIX_COLLISION_CODE = "MIGRATION.UPGRADE_TARGET_PREFIX_COLLISION";

    private final OfflineUpgradeAdapterRegistry registry;

    public StandaloneUpgradePlanner(OfflineUpgradeAdapterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public OfflineUpgradeAdapterRegistry registry() {
        return registry;
    }

    @Override
    public UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException {
        Objects.requireNonNull(sourceSnapshot, "sourceSnapshot");
        Objects.requireNonNull(sourceWindow, "sourceWindow");
        ImmutableSnapshotAdapter.View source = ImmutableSnapshotAdapter.adapt(sourceSnapshot);
        sourceWindow.requireSupported(source.metadata());
        Set<String> sourcePaths = source.entries().stream().map(ImmutableSnapshotAdapter.Entry::relativePath).collect(Collectors.toUnmodifiableSet());
        rejectCaseEquivalentPaths(source.directories(), sourcePaths);
        List<PlannedCandidate> candidates = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        Set<String> snapshotClaimedPaths = new HashSet<>();

        SnapshotAdapterBinding.Capture snapshotBinding = SnapshotAdapterBinding.capture(sourceSnapshot, registry);
        List<OfflineUpgradeSnapshotAdapter> snapshotClaimers = new ArrayList<>();
        for (SnapshotAdapterBinding.Invocation invocation : snapshotBinding.invocations()) {
            OfflineUpgradeSnapshotAdapter adapter = invocation.adapter();
            if (invocation.claims()) {
                snapshotClaimers.add(adapter);
            }
            if (!invocation.failurePhase().isEmpty() && "claims".equals(invocation.failurePhase())) {
                ImmutableSnapshotAdapter.Entry trigger = source.entries().stream()
                    .filter(entry -> "triggers.json".equals(entry.relativePath()))
                    .findFirst().orElse(null);
                source.entries().stream()
                    .filter(entry -> adapter.owns(entry.relativePath(), entry.owner()))
                    .forEach(entry -> snapshotClaimedPaths.add(entry.relativePath()));
                if (trigger != null) {
                    quarantine.add(quarantine(trigger, TRANSFORM_FAILED_CODE,
                        "The standalone snapshot adapter could not prove its input shape: " + invocation.failureMessage(),
                        "Repair the snapshot shape or adapter claim and rerun the dry run.", List.of(adapter.wireId())));
                }
            }
        }
        if (snapshotClaimers.size() > 1) {
            ImmutableSnapshotAdapter.Entry trigger = source.entries().stream()
                .filter(entry -> "triggers.json".equals(entry.relativePath()))
                .findFirst().orElse(null);
            if (trigger != null) {
                quarantine.add(quarantine(trigger, COLLISION_CODE,
                    "More than one standalone snapshot adapter claims this cross-document input.",
                    "Register exactly one snapshot adapter for the trigger and graph documents.",
                    snapshotClaimers.stream().map(OfflineUpgradeSnapshotAdapter::wireId).toList()));
            }
            for (OfflineUpgradeSnapshotAdapter adapter : snapshotClaimers) {
                source.entries().stream()
                    .filter(entry -> adapter.owns(entry.relativePath(), entry.owner()))
                    .forEach(entry -> snapshotClaimedPaths.add(entry.relativePath()));
            }
            snapshotClaimers.clear();
        }
        for (OfflineUpgradeSnapshotAdapter adapter : snapshotClaimers) {
            try {
                SnapshotAdapterBinding.Invocation invocation = snapshotBinding.invocation(adapter.wireId());
                OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = Objects.requireNonNull(
                    invocation.transform(), invocation.failureMessage().isBlank()
                        ? "snapshot adapter transform result" : invocation.failureMessage());
                if (!invocation.failurePhase().isEmpty()) {
                    throw new IllegalArgumentException(invocation.failureMessage());
                }
                quarantine.addAll(transformed.quarantines());
                for (String claimedPath : transformed.claimedPaths()) {
                    ImmutableSnapshotAdapter.Entry entry = source.entries().stream()
                        .filter(value -> claimedPath.equals(value.relativePath())).findFirst().orElse(null);
                    if (entry == null) {
                        throw new IllegalArgumentException("Snapshot adapter claimed a missing source path: " + claimedPath);
                    }
                    snapshotClaimedPaths.add(claimedPath);
                    if (!adapter.owns(claimedPath, entry.owner())) {
                        quarantine.add(quarantine(entry, OWNER_MISMATCH_CODE,
                            "The standalone snapshot adapter claimed a source file outside its declared ownership.",
                            "Register the adapter against every participant it coordinates.", List.of(adapter.owner(), entry.owner())));
                        continue;
                    }
                }
                for (OfflineUpgradeSnapshotAdapter.FileTransform file : transformed.files()) {
                    ImmutableSnapshotAdapter.Entry entry = source.entries().stream()
                        .filter(value -> file.sourcePath().equals(value.relativePath())).findFirst().orElse(null);
                    if (entry == null && !file.sourcePath().isBlank()) {
                        throw new IllegalArgumentException("Snapshot adapter transformed a missing source path: " + file.sourcePath());
                    }
                    if (entry != null && !adapter.owns(file.sourcePath(), entry.owner())) {
                        snapshotClaimedPaths.add(file.sourcePath());
                        quarantine.add(quarantine(entry, OWNER_MISMATCH_CODE,
                            "The standalone snapshot adapter transformed a source file outside its declared ownership.",
                            "Register the adapter against the participant that owns this source file.", List.of(adapter.owner(), entry.owner())));
                        continue;
                    }
                    byte[] sourceBytes = entry == null ? null : Files.readAllBytes(MigrationPaths.resolveInside(sourceSnapshot.root(), file.sourcePath()));
                    if (entry != null && !entry.sha256().equals(sha256(sourceBytes))) {
                        throw new IllegalArgumentException("Source Hash Changed During Standalone Planning");
                    }
                    byte[] targetBytes = file.bytes();
                    boolean changed = sourceBytes == null || !Arrays.equals(sourceBytes, targetBytes);
                    MigrationOperationType operationType = file.operationType() != null ? file.operationType() : changed ? MigrationOperationType.CONVERT :
                        file.sourcePath().equals(file.targetPath()) ? MigrationOperationType.COPY : MigrationOperationType.MOVE;
                    requireOperationSemantics(file.sourcePath(), file.targetPath(), changed, operationType);
                    candidates.add(new PlannedCandidate(entry, adapter.wireId(), operationType,
                        MigrationPaths.requireRelative(file.targetPath()), entry == null ? "" : entry.sha256(), sha256(targetBytes), targetBytes));
                }
            } catch (IOException | RuntimeException exception) {
                ImmutableSnapshotAdapter.Entry trigger = source.entries().stream()
                    .filter(entry -> "triggers.json".equals(entry.relativePath()))
                    .findFirst().orElse(null);
                source.entries().stream()
                    .filter(entry -> adapter.owns(entry.relativePath(), entry.owner()))
                    .forEach(entry -> snapshotClaimedPaths.add(entry.relativePath()));
                if (trigger != null) {
                    quarantine.add(quarantine(trigger, TRANSFORM_FAILED_CODE,
                        "The standalone snapshot adapter failed closed: " + reason(exception),
                        "Repair the cross-document input and rerun the dry run.", List.of(adapter.wireId())));
                }
            }
        }

        for (ImmutableSnapshotAdapter.Entry entry : source.entries()) {
            if (isPreservedProvenancePath(entry.relativePath())) {
                continue;
            }
            if (snapshotClaimedPaths.contains(entry.relativePath())) {
                continue;
            }
            OfflineUpgradeAdapterRegistry.Resolution resolution;
            try {
                resolution = registry.resolve(entry.relativePath());
            } catch (RuntimeException exception) {
                quarantine.add(quarantine(entry, TRANSFORM_FAILED_CODE,
                    "The standalone upgrade adapter matcher failed closed: " + reason(exception),
                    "Repair the adapter matcher and rerun the dry run.", List.of()));
                continue;
            }
            if (resolution.state() == OfflineUpgradeAdapterRegistry.Resolution.State.NONE) {
                quarantine.add(quarantine(entry, MISSING_ADAPTER_CODE,
                    "No standalone upgrade adapter owns this source file.",
                    "Install or register one deterministic adapter for this typed source file.", List.of()));
                continue;
            }
            if (resolution.state() == OfflineUpgradeAdapterRegistry.Resolution.State.AMBIGUOUS) {
                quarantine.add(quarantine(entry, COLLISION_CODE,
                    "More than one standalone upgrade adapter matches this source file.",
                    "Remove the adapter collision and rerun the dry run.",
                    resolution.matches().stream().map(OfflineUpgradeAdapter::wireId).toList()));
                continue;
            }
            OfflineUpgradeAdapter adapter = resolution.adapter();
            if (!adapter.owner().equals(entry.owner())) {
                quarantine.add(quarantine(entry, OWNER_MISMATCH_CODE,
                    "The standalone upgrade adapter owner does not match the snapshot owner.",
                    "Register the adapter against the participant that owns this source file.",
                    List.of(adapter.owner(), entry.owner())));
                continue;
            }
            try {
                String targetPath = MigrationPaths.requireRelative(adapter.targetPath(entry.relativePath()));
                byte[] sourceBytes = Files.readAllBytes(MigrationPaths.resolveInside(sourceSnapshot.root(), entry.relativePath()));
                String sourceHash = sha256(sourceBytes);
                if (!entry.sha256().equals(sourceHash)) {
                    throw new IllegalArgumentException("Source Hash Changed During Standalone Planning");
                }
                OfflineUpgradeAdapter.TransformResult transformed = Objects.requireNonNull(
                    adapter.transform(entry.relativePath(), sourceBytes), "adapter transform result");
                byte[] targetBytes = transformed.bytes();
                boolean changed = !Arrays.equals(sourceBytes, targetBytes);
                OfflineUpgradeAdapter.TransformResult effective = new OfflineUpgradeAdapter.TransformResult(targetBytes, changed);
                MigrationOperationType operationType = Objects.requireNonNull(
                    adapter.operationType(entry.relativePath(), targetPath, effective), "adapter operation type");
                requireOperationSemantics(entry.relativePath(), targetPath, changed, operationType);
                candidates.add(new PlannedCandidate(entry, adapter.wireId(), operationType, targetPath, sourceHash, sha256(targetBytes), targetBytes));
            } catch (IOException | RuntimeException exception) {
                quarantine.add(quarantine(entry, TRANSFORM_FAILED_CODE,
                    "The standalone upgrade adapter failed closed: " + reason(exception),
                    "Repair the adapter input or graph and rerun the dry run.", List.of(adapter.wireId())));
            }
        }

        Map<String, List<PlannedCandidate>> byTarget = new HashMap<>();
        for (PlannedCandidate candidate : candidates) {
            byTarget.computeIfAbsent(candidate.targetPath(), ignored -> new ArrayList<>()).add(candidate);
        }
        Set<String> sourceFilesystemPaths = new HashSet<>(sourcePaths);
        sourceFilesystemPaths.addAll(source.directories());
        rejectCaseEquivalentTargets(sourceFilesystemPaths, candidates);
        rejectTargetTypeCollisions(sourcePaths, new HashSet<>(source.directories()), candidates);
        Set<PlannedCandidate> collisions = new HashSet<>();
        for (Map.Entry<String, List<PlannedCandidate>> target : byTarget.entrySet()) {
            List<PlannedCandidate> targetCandidates = target.getValue();
            boolean sourceCollision = sourcePaths.contains(target.getKey())
                && targetCandidates.stream().anyMatch(candidate -> candidate.entry() == null
                    || !candidate.entry().relativePath().equals(target.getKey()));
            if (targetCandidates.size() > 1 || sourceCollision) {
                collisions.addAll(targetCandidates);
                List<String> affected = targetCandidates.stream().map(candidate -> candidate.entry().relativePath()).sorted().toList();
                for (PlannedCandidate candidate : targetCandidates) {
                    ImmutableSnapshotAdapter.Entry evidence = candidate.entry() != null ? candidate.entry() : source.entries().stream().findFirst().orElseThrow();
                    quarantine.add(quarantine(evidence, COLLISION_CODE,
                        "Multiple source files or an unaffected source file map to the same replacement path.",
                        "Provide one unique replacement target for every source file.", affected));
                }
            }
        }

        List<MigrationOperation> operations = candidates.stream()
            .filter(candidate -> !collisions.contains(candidate))
            .sorted(Comparator.comparing(candidate -> candidate.entry() == null ? candidate.targetPath() : candidate.entry().relativePath()))
            .map(candidate -> new MigrationOperation(
                candidate.operationType(),
                candidate.adapterId(),
                candidate.entry() == null ? "" : candidate.entry().relativePath(),
                candidate.targetPath(),
                candidate.sourceHash(),
                candidate.targetHash()))
            .toList();
        QuarantineReport report = new QuarantineReport(quarantine);
        MigrationPlan plan = new MigrationPlan(
            source.metadata().snapshotId(),
            source.manifestHash(),
            sourceWindow.sourceFormatVersion(),
            sourceWindow.targetFormatVersion(),
            report.reportHash(),
            operations).withSnapshotAdapterResultHash(snapshotBinding.bindingHash());
        return new UpgradeProposal(plan, report, new restudio.resync.flow.diagnostic.DiagnosticSet(List.of()));
    }

    private static boolean isPreservedProvenancePath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        String root = "assets/migration-backups/";
        if (!normalized.startsWith(root)) {
            return false;
        }
        String remainder = normalized.substring(root.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0) {
            return false;
        }
        String migrationId = remainder.substring(0, separator);
        return migrationId.startsWith("typed-automation-")
            && migrationId.length() > "typed-automation-".length();
    }

    private static void rejectCaseEquivalentPaths(List<String> directories, Set<String> files) throws IOException {
        Map<String, String> seen = new HashMap<>();
        for (String path : directories) {
            registerCaseFoldedPath(seen, path);
        }
        for (String path : files) {
            registerCaseFoldedPath(seen, path);
        }
    }

    private static void rejectCaseEquivalentTargets(Set<String> sourcePaths, List<PlannedCandidate> candidates) throws IOException {
        Map<String, String> seen = new HashMap<>();
        for (String path : sourcePaths) {
            registerPathPrefixes(seen, path);
        }
        for (PlannedCandidate candidate : candidates) {
            registerPathPrefixes(seen, candidate.targetPath());
        }
    }

    private static void rejectTargetTypeCollisions(Set<String> sourceFiles, Set<String> sourceDirectories,
                                                   List<PlannedCandidate> candidates) throws IOException {
        for (PlannedCandidate candidate : candidates) {
            String target = candidate.targetPath();
            if (sourceDirectories.contains(target)) {
                throw new MigrationException(TARGET_DIRECTORY_CODE
                    + ": Migration target collides with an existing directory: " + target);
            }
            if (sourceFiles.stream().anyMatch(path -> isDescendant(path, target))) {
                throw new MigrationException(TARGET_PREFIX_COLLISION_CODE
                    + ": Migration target requires a directory beneath an existing file: " + target);
            }
        }
        List<String> targets = candidates.stream().map(PlannedCandidate::targetPath).distinct().sorted().toList();
        for (int index = 0; index < targets.size(); index++) {
            for (int next = index + 1; next < targets.size(); next++) {
                String first = targets.get(index);
                String second = targets.get(next);
                if (isDescendant(first, second) || isDescendant(second, first)) {
                    throw new MigrationException(TARGET_PREFIX_COLLISION_CODE
                        + ": Migration targets have a file and directory prefix collision: " + first + " and " + second);
                }
            }
        }
    }

    private static void registerPathPrefixes(Map<String, String> seen, String path) throws IOException {
        String[] segments = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            prefix.append(segment);
            String value = prefix.toString();
            String previous = seen.putIfAbsent(caseFold(value), value);
            if (previous != null && !previous.equals(value)) {
                throw new MigrationException(CASE_COLLISION_CODE + ": Case-equivalent filesystem paths collide: " + previous + " and " + value);
            }
        }
    }

    private static void registerCaseFoldedPath(Map<String, String> seen, String path) throws IOException {
        String folded = caseFold(path);
        String previous = seen.putIfAbsent(folded, path);
        if (previous != null && !previous.equals(path)) {
            throw new MigrationException(CASE_COLLISION_CODE + ": Case-equivalent source paths collide: " + previous + " and " + path);
        }
    }

    private static void requireOperationSemantics(String sourcePath, String targetPath, boolean changed,
                                                   MigrationOperationType operationType) throws IOException {
        if (operationType == MigrationOperationType.GENERATE) {
            if (targetPath.isBlank() || sourcePath.equals(targetPath)) {
                throw new MigrationException("Generate Operation Requires A Distinct Target Path");
            }
            return;
        }
        if (changed && operationType != MigrationOperationType.CONVERT) {
            throw new MigrationException("Changed adapter bytes require a convert operation: " + sourcePath);
        }
        if (!changed && sourcePath.equals(targetPath) && operationType != MigrationOperationType.COPY) {
            throw new MigrationException("Unchanged same-path adapter bytes require a copy operation: " + sourcePath);
        }
        if (!changed && !sourcePath.equals(targetPath)
            && operationType != MigrationOperationType.MOVE && operationType != MigrationOperationType.RENAME) {
            throw new MigrationException("Unchanged relocation requires move or rename operation: " + sourcePath);
        }
    }

    private static String caseFold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean isDescendant(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    private static QuarantineRecord quarantine(ImmutableSnapshotAdapter.Entry entry, String code, String reason,
                                               String action, List<String> references) {
        String recordId = "upgrade-" + CanonicalJson.sha256("resync.standalone-upgrade.quarantine",
            List.of(code, entry.relativePath(), entry.sha256(), reason, references == null ? List.of() : references)).substring(0, 24);
        return new QuarantineRecord(recordId, code, entry.relativePath(), reason, references, action, entry.sha256());
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private record PlannedCandidate(ImmutableSnapshotAdapter.Entry entry, String adapterId,
                                    MigrationOperationType operationType,
                                    String targetPath, String sourceHash, String targetHash, byte[] targetBytes) {
        private PlannedCandidate {
            adapterId = Objects.requireNonNull(adapterId, "adapterId");
            operationType = Objects.requireNonNull(operationType, "operationType");
            targetPath = MigrationPaths.requireRelative(targetPath);
            sourceHash = sourceHash == null || sourceHash.isBlank() ? "" : requireHash(sourceHash, "sourceHash");
            targetHash = requireHash(targetHash, "targetHash");
            targetBytes = Objects.requireNonNull(targetBytes, "targetBytes").clone();
        }

        public byte[] targetBytes() {
            return targetBytes.clone();
        }

        private static String requireHash(String value, String field) {
            if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }

    public record Candidate(ImmutableSnapshotAdapter.Entry entry, OfflineUpgradeAdapter adapter,
                            MigrationOperationType operationType,
                            String targetPath, String sourceHash, String targetHash, byte[] targetBytes) {
        public Candidate {
            entry = Objects.requireNonNull(entry, "entry");
            adapter = Objects.requireNonNull(adapter, "adapter");
            operationType = Objects.requireNonNull(operationType, "operationType");
            targetPath = MigrationPaths.requireRelative(targetPath);
            sourceHash = requireHash(sourceHash, "sourceHash");
            targetHash = requireHash(targetHash, "targetHash");
            targetBytes = Objects.requireNonNull(targetBytes, "targetBytes").clone();
        }

        @Override
        public byte[] targetBytes() {
            return targetBytes.clone();
        }

        private static String requireHash(String value, String field) {
            if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }
}
