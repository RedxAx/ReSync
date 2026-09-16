package restudio.resync.upgrade;

import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationStager;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;
import restudio.resync.migration.VerifiedSnapshotAdmission;
import restudio.resync.migration.VerifiedSnapshotExporter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class StandaloneUpgradeStager implements MigrationStager {
    private static final String QUARANTINE_ROOT = ".quarantine/migration";

    private final OfflineUpgradeAdapterRegistry registry;
    private final Path invocationPath;
    private final String invocationHash;
    private final TypedStageContext typedStageContext;

    public StandaloneUpgradeStager(OfflineUpgradeAdapterRegistry registry) {
        this(registry, null, null, null);
    }

    public StandaloneUpgradeStager(OfflineUpgradeAdapterRegistry registry, Path invocationPath, String invocationHash) {
        this(registry, invocationPath, invocationHash, null);
    }

    public StandaloneUpgradeStager(OfflineUpgradeAdapterRegistry registry, Path invocationPath, String invocationHash,
                                   TypedStageContext typedStageContext) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.invocationPath = invocationPath == null ? null : MigrationPaths.requirePath(invocationPath, "invocationPath");
        if ((this.invocationPath == null) != (invocationHash == null)) {
            throw new IllegalArgumentException("Invocation Binding Must Be Complete");
        }
        this.invocationHash = invocationHash == null ? null : requireDigest(invocationHash, "invocationHash");
        this.typedStageContext = typedStageContext;
    }

    public static RecoveryMetadata recoveryMetadata(Path sourceRoot, Path stagingRoot, MigrationPlan plan)
        throws IOException {
        MigrationPlan migrationPlan = Objects.requireNonNull(plan, "plan");
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        VerifiedSnapshotAdmission sourceAdmission = new SnapshotService(new MigrationFence()).admitExported(source);
        SnapshotMetadata sourceMetadata = sourceAdmission.metadata();
        if (!sourceMetadata.snapshotId().equals(migrationPlan.sourceSnapshotId())
            || sourceMetadata.formatVersion() != migrationPlan.sourceFormatVersion()
            || !sourceAdmission.snapshot().manifest().manifestHash().equals(migrationPlan.sourceManifestHash())) {
            throw new MigrationException("Recovery Source Snapshot Does Not Match The Migration Plan");
        }
        boolean present = Files.exists(staging, LinkOption.NOFOLLOW_LINKS);
        if (present && (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS))) {
            throw new MigrationException("Recovery Staging Root Is Not A Directory");
        }
        String stagedDigest = "";
        String stagedSnapshotId = "";
        String stagedManifestHash = "";
        String stagedFailure = "";
        boolean verified = false;
        if (present) {
            try {
                stagedDigest = TreeDigest.of(staging);
                VerifiedSnapshotAdmission stagedAdmission = new SnapshotService(new MigrationFence())
                    .admitExported(staging);
                stagedSnapshotId = stagedAdmission.metadata().snapshotId();
                stagedManifestHash = stagedAdmission.snapshot().manifest().manifestHash();
                verified = stagedAdmission.metadata().formatVersion() == migrationPlan.targetFormatVersion();
                if (!verified) {
                    stagedFailure = "Recovery Staging Snapshot Format Does Not Match The Migration Plan";
                }
            } catch (IOException | RuntimeException exception) {
                stagedFailure = message(exception);
            }
        }
        return new RecoveryMetadata(source, staging, migrationPlan.planHash(), sourceMetadata.snapshotId(),
            sourceAdmission.snapshot().manifest().manifestHash(), stagedDigest, stagedSnapshotId,
            stagedManifestHash, present, verified, stagedFailure);
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan,
                                 QuarantineReport report, QuarantineAcceptance acceptance) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(acceptance, "acceptance");
        if (invocationPath != null) {
            InvocationBinding.verifyDigest(invocationPath, invocationHash);
            if (!invocationHash.equals(plan.invocationHash())) {
                throw new MigrationException("Migration Plan Does Not Match The Invocation Binding");
            }
        }
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        Path staging = prepareStagingRoot(stagingRoot, source);
        if (!plan.quarantineReportHash().equals(report.reportHash())) {
            throw new MigrationException("Migration Plan Does Not Match Quarantine Report");
        }
        report.requireAccepted(acceptance);
        try {
            SnapshotManifest manifest = verifyRetainedManifest(source, plan);
            SourceTree sourceTree = inspectSource(source);
            manifest.verify(source).requireVerified();
            verifySnapshotAdapterBinding(source, plan);
            OperationSet operationSet = validateOperations(sourceTree, plan.operations());
            copyTree(source, staging);
            applyOperations(source, staging, operationSet, manifest);
            materializeQuarantine(source, staging, operationSet, report);
            manifest.verify(source).requireVerified();
            verifyExpectedFiles(staging, sourceTree.files(), operationSet, report);
            MigrationPaths.requireNoSymlinkTree(staging);
            StagedMigration staged = new StagedMigration(staging, Optional.empty(), plan.planHash(), TreeDigest.of(staging));
            if (typedStageContext == null) {
                return staged;
            }
            VerifiedSnapshotAdmission actualSource = typedStageContext.admit(source, plan);
            VerifiedSnapshotExporter.Request request = typedStageContext.request(staging, actualSource);
            VerifiedSnapshotAdmission postStage = VerifiedSnapshotExporter.export(request);
            AssetAdoptionArtifactProducer.StageOutput output = typedStageContext.productionEvidence()
                ? TypedStageEvidenceConverter.productionOutput(actualSource, plan, postStage, report, acceptance,
                    typedStageContext.lifecycleOutputs())
                : TypedStageEvidenceConverter.output(actualSource, plan, postStage,
                    typedStageContext.lifecycleOutputs(), report, acceptance, typedStageContext.blocked());
            return TypedStageEvidenceConverter.attach(staged, actualSource, output);
        } catch (IOException | RuntimeException exception) {
            deleteTree(staging, exception);
            throw exception;
        }
    }

    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan,
                                 QuarantineReport report, QuarantineAcceptance acceptance,
                                 VerifiedSnapshotAdmission sourceAdmission,
                                 AssetAdoptionArtifactProducer.StageOutput stageOutput) throws IOException {
        VerifiedSnapshotAdmission admission = Objects.requireNonNull(sourceAdmission, "sourceAdmission");
        Path source = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
        if (!admission.root().equals(source)) {
            throw new MigrationException("Typed Stage Source Admission Does Not Match The Source Root");
        }
        StagedMigration staged = stage(sourceRoot, stagingRoot, plan, report, acceptance);
        try {
            return TypedStageEvidenceConverter.attach(staged, admission, plan, report, acceptance, stageOutput);
        } catch (IOException | RuntimeException exception) {
            deleteTree(staged.root(), exception);
            throw exception;
        }
    }

    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan,
                                 QuarantineReport report, QuarantineAcceptance acceptance,
                                 VerifiedSnapshotExporter.Request exportRequest,
                                 List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
                                 List<AssetAdoptionArtifactProducer.BlockedState> blocked) throws IOException {
        VerifiedSnapshotExporter.Request request = Objects.requireNonNull(exportRequest, "exportRequest");
        Path source = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        if (!request.verifiedSourceAdmission().root().equals(source)
            || !request.stagedRoot().equals(staging)) {
            throw new MigrationException("Verified Snapshot Export Request Does Not Match The Source Or Staging Root");
        }
        StagedMigration staged = stage(sourceRoot, stagingRoot, plan, report, acceptance);
        try {
            return TypedStageEvidenceConverter.attach(staged, plan, report, acceptance, request,
                lifecycleOutputs, blocked);
        } catch (IOException | RuntimeException exception) {
            deleteTree(staged.root(), exception);
            throw exception;
        }
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
        QuarantineReport report = QuarantineReport.empty();
        if (!plan.quarantineReportHash().equals(report.reportHash())) {
            throw new MigrationException("Standalone Upgrade Requires Explicit Quarantine Acceptance");
        }
        return stage(sourceRoot, stagingRoot, plan, report, report.accept("offline-upgrader", Instant.EPOCH));
    }

    private static Path prepareStagingRoot(Path stagingRoot, Path source) throws IOException {
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        MigrationPaths.requireDistinctRoots(source, staging);
        MigrationPaths.requireWritableParent(staging);
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("stagingRoot Must Be A Non-Symbolic-Link Directory");
            }
            try (var stream = Files.list(staging)) {
                if (stream.findAny().isPresent()) {
                    throw new MigrationException("stagingRoot Must Be Empty");
                }
            }
        } else {
            Files.createDirectories(staging);
        }
        MigrationPaths.requireNoSymlinkTraversal(staging.getParent(), staging);
        return staging;
    }

    private static SnapshotManifest verifyRetainedManifest(Path source, MigrationPlan plan) throws IOException {
        Path fileName = source.getFileName();
        if (fileName == null) {
            throw new MigrationException("Source Snapshot Has No File Name");
        }
        Path manifestPath = source.resolveSibling(fileName + ".manifest");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifestPath)) {
            throw new MigrationException("Retained Source Snapshot Manifest Is Missing");
        }
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        if (!manifest.metadata().snapshotId().equals(plan.sourceSnapshotId())
            || !manifest.manifestHash().equals(plan.sourceManifestHash())) {
            throw new MigrationException("Retained Source Snapshot Manifest Does Not Match The Plan");
        }
        manifest.verify(source).requireVerified();
        return manifest;
    }

    private void verifySnapshotAdapterBinding(Path source, MigrationPlan plan) throws IOException {
        String expected = plan.snapshotAdapterResultHash();
        if (expected.isBlank()) {
            if (!registry.snapshotAdapters().isEmpty()) {
                throw new MigrationException("Migration Plan Is Missing Its Snapshot Adapter Result Binding");
            }
            return;
        }
        Snapshot snapshot = new SnapshotService(new MigrationFence()).admitExported(source).snapshot();
        String actual = SnapshotAdapterBinding.capture(snapshot, registry).bindingHash();
        if (!expected.equals(actual)) {
            throw new MigrationException("Snapshot Adapter Result Changed Between Dry Run And Apply");
        }
    }

    private static SourceTree inspectSource(Path source) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Set<String> directories = new TreeSet<>();
        Map<String, String> paths = new HashMap<>();
        List<Path> filePaths = new ArrayList<>();
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(source)) {
                    String relative = MigrationPaths.relative(source, directory);
                    rejectReservedPath(relative);
                    registerCaseFolded(paths, relative);
                    directories.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(source, file);
                rejectReservedPath(relative);
                registerCaseFolded(paths, relative);
                files.put(relative, sha256(file));
                rejectFileAlias(filePaths, file, "Source Root");
                filePaths.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Source File: " + file, exception);
            }
        });
        return new SourceTree(files, directories);
    }

    private static OperationSet validateOperations(SourceTree sourceTree, List<MigrationOperation> values) throws IOException {
        List<MigrationOperation> operations = new ArrayList<>(values == null ? List.of() : values);
        operations.sort(Comparator.comparing(StandaloneUpgradeStager::canonicalOperation));
        Map<String, MigrationOperation> targets = new TreeMap<>();
        Map<String, List<MigrationOperation>> sources = new HashMap<>();
        Map<String, String> foldedTargets = new HashMap<>();
        for (MigrationOperation operation : operations) {
            MigrationOperationType type;
            try {
                type = operation.type();
            } catch (RuntimeException exception) {
                throw new MigrationException("Unsupported Migration Operation Type: " + operation.kind(), exception);
            }
            if (type.sourceRequired()) {
                if (!operation.sourcePath().isEmpty() && !sourceTree.files().containsKey(operation.sourcePath())) {
                    throw new MigrationException("Migration Source File Is Missing: " + operation.sourcePath());
                }
                if (!operation.sourcePath().isEmpty()) {
                    sources.computeIfAbsent(operation.sourcePath(), ignored -> new ArrayList<>()).add(operation);
                }
                if (!operation.sourcePath().isEmpty() && !operation.sourceHash().isEmpty() && !operation.sourceHash().equals(sourceTree.files().get(operation.sourcePath()))) {
                    throw new MigrationException("Migration Source Hash Does Not Match: " + operation.sourcePath());
                }
                if (type == MigrationOperationType.CONVERT
                    && (operation.targetHash().isEmpty() || operation.targetHash().equals(sourceTree.files().get(operation.sourcePath())))) {
                    throw new MigrationException("Convert Operation Must Declare A Changed Target Hash: " + operation.sourcePath());
                }
            }
            if (type == MigrationOperationType.GENERATE && !operation.sourcePath().isEmpty()) {
                if (!sourceTree.files().containsKey(operation.sourcePath())) {
                    throw new MigrationException("Generate Provenance File Is Missing: " + operation.sourcePath());
                }
                if (!operation.sourceHash().isEmpty() && !operation.sourceHash().equals(sourceTree.files().get(operation.sourcePath()))) {
                    throw new MigrationException("Generate Provenance Hash Does Not Match: " + operation.sourcePath());
                }
            }
            if (type.targetRequired()) {
                if (operation.targetPath().isEmpty()) {
                    throw new MigrationException("Migration Operation Requires A Target Path");
                }
                rejectReservedPath(operation.targetPath());
                MigrationOperation previous = targets.putIfAbsent(operation.targetPath(), operation);
                if (previous != null) {
                    throw new MigrationException("Migration Target Collision: " + operation.targetPath());
                }
                String folded = caseFold(operation.targetPath());
                String previousFolded = foldedTargets.putIfAbsent(folded, operation.targetPath());
                if (previousFolded != null && !previousFolded.equals(operation.targetPath())) {
                    throw new MigrationException(StandaloneUpgradePlanner.CASE_COLLISION_CODE + ": Case-equivalent migration targets collide: " + previousFolded + " and " + operation.targetPath());
                }
                if (sourceTree.files().containsKey(operation.targetPath()) && !operation.targetPath().equals(operation.sourcePath())) {
                    throw new MigrationException("Migration Target Collides With An Unaffected Source File: " + operation.targetPath());
                }
                if (sourceTree.directories().contains(operation.targetPath())) {
                    throw new MigrationException(StandaloneUpgradePlanner.TARGET_DIRECTORY_CODE
                        + ": Migration Target Collides With An Existing Directory: " + operation.targetPath());
                }
                if (sourceTree.files().keySet().stream().anyMatch(path -> isDescendant(path, operation.targetPath()))) {
                    throw new MigrationException(StandaloneUpgradePlanner.TARGET_PREFIX_COLLISION_CODE
                        + ": Migration Target Requires A Directory Beneath An Existing File: " + operation.targetPath());
                }
                for (String existingTarget : targets.keySet()) {
                    if (isDescendant(existingTarget, operation.targetPath()) || isDescendant(operation.targetPath(), existingTarget)) {
                        throw new MigrationException(StandaloneUpgradePlanner.TARGET_PREFIX_COLLISION_CODE
                            + ": Migration Targets Have A File And Directory Prefix Collision: "
                            + existingTarget + " and " + operation.targetPath());
                    }
                }
            } else if (!operation.targetPath().isEmpty()) {
                throw new MigrationException("Delete Operation Must Not Declare A Target Path");
            }
            if (!operation.targetHash().isEmpty() && !type.targetRequired()) {
                throw new MigrationException("Delete Operation Must Not Declare A Target Hash");
            }
        }
        for (Map.Entry<String, List<MigrationOperation>> entry : sources.entrySet()) {
            boolean destructive = entry.getValue().stream().anyMatch(operation -> !operation.type().preservesSource());
            if (destructive && entry.getValue().size() > 1) {
                throw new MigrationException("A Destructive Migration Source Is Used More Than Once: " + entry.getKey());
            }
        }
        return new OperationSet(List.copyOf(operations), Map.copyOf(targets));
    }

    private void applyOperations(Path source, Path staging, OperationSet operationSet,
                                 SnapshotManifest manifest) throws IOException {
        List<String> destructiveSources = new ArrayList<>();
        Map<String, OfflineUpgradeSnapshotAdapter.SnapshotTransform> snapshotTransforms = new HashMap<>();
        OfflineUpgradeSnapshotInput snapshotInput = snapshotInput(staging, source, manifest);
        for (MigrationOperation operation : operationSet.operations()) {
            MigrationOperationType type = operation.type();
            Path sourcePath = type.sourceRequired() ? MigrationPaths.resolveInside(source, operation.sourcePath()) : null;
            Path stagedSource = type.sourceRequired() ? MigrationPaths.resolveInside(staging, operation.sourcePath()) : null;
            if (type.sourceRequired() && !operation.sourceHash().isEmpty() && !operation.sourceHash().equals(sha256(sourcePath))) {
                throw new MigrationException("Migration Source Hash Changed During Staging: " + operation.sourcePath());
            }
            if (type == MigrationOperationType.GENERATE && !operation.sourcePath().isEmpty()
                && !operation.sourceHash().isEmpty()
                && !operation.sourceHash().equals(sha256(MigrationPaths.resolveInside(source, operation.sourcePath())))) {
                throw new MigrationException("Generate Provenance Hash Changed During Staging: " + operation.sourcePath());
            }
            if (type == MigrationOperationType.DELETE) {
                Files.delete(stagedSource);
                continue;
            }
            OfflineUpgradeAdapter adapter = registry.byWireId(operation.adapterId());
            OfflineUpgradeSnapshotAdapter snapshotAdapter = registry.snapshotByWireId(operation.adapterId());
            byte[] sourceBytes = sourcePath == null ? null : Files.readAllBytes(sourcePath);
            byte[] targetBytes;
            if (adapter != null) {
                if (!adapter.matches(operation.sourcePath())) {
                    throw new MigrationException("Standalone Upgrade Adapter Is Missing Or Does Not Own The Planned Source: " + operation.adapterId());
                }
                OfflineUpgradeAdapter.TransformResult transformed = Objects.requireNonNull(
                    adapter.transform(operation.sourcePath(), sourceBytes), "adapter transform result");
                targetBytes = transformed.bytes();
            } else if (snapshotAdapter != null) {
                OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = snapshotTransforms.computeIfAbsent(
                    operation.adapterId(), ignored -> {
                        try {
                            return Objects.requireNonNull(snapshotAdapter.transform(snapshotInput), "snapshot adapter transform result");
                        } catch (IOException | RuntimeException exception) {
                            throw new SnapshotTransformFailure(exception);
                        }
                    });
                OfflineUpgradeSnapshotAdapter.FileTransform file = transformed.files().stream()
                    .filter(value -> operation.sourcePath().equals(value.sourcePath())
                        && operation.targetPath().equals(value.targetPath()))
                    .findFirst().orElseThrow(() -> new MigrationException(
                        "Snapshot Upgrade Adapter Did Not Reproduce The Planned File: " + operation.sourcePath()));
                targetBytes = file.bytes();
            } else {
                throw new MigrationException("Standalone Upgrade Adapter Is Missing Or Does Not Own The Planned Source: " + operation.adapterId());
            }
            boolean changed = sourceBytes == null || !Arrays.equals(sourceBytes, targetBytes);
            requireOperationSemantics(operation, changed);
            if (!operation.targetHash().isEmpty() && !operation.targetHash().equals(sha256(targetBytes))) {
                throw new MigrationException("Migration Target Hash Does Not Match Plan: " + operation.targetPath());
            }
            Path target = MigrationPaths.resolveInside(staging, operation.targetPath());
            ensureParent(target, staging);
            if (!target.equals(stagedSource) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Migration Target Was Written Before Application: " + operation.targetPath());
            }
            AtomicFiles.write(target, targetBytes);
            if (!type.preservesSource() && !operation.sourcePath().equals(operation.targetPath())) {
                destructiveSources.add(operation.sourcePath());
            }
        }
        for (String sourcePath : destructiveSources) {
            Files.deleteIfExists(MigrationPaths.resolveInside(staging, sourcePath));
        }
    }

    private static OfflineUpgradeSnapshotInput snapshotInput(Path root, Path provenanceRoot, SnapshotManifest manifest) {
        ImmutableSnapshotAdapter.View view = new ImmutableSnapshotAdapter.View(
            manifest.metadata(),
            manifest.manifestHash(),
            manifest.directories(),
            manifest.entries().stream().map(entry -> new ImmutableSnapshotAdapter.Entry(
                entry.relativePath(), entry.size(), entry.sha256(), entry.owner())).toList());
        return new OfflineUpgradeSnapshotInput(root, view, provenanceRoot);
    }

    private static void requireOperationSemantics(MigrationOperation operation, boolean changed) throws IOException {
        MigrationOperationType type = operation.type();
        if (type == MigrationOperationType.GENERATE) {
            if (operation.targetPath().isEmpty() || operation.targetHash().isEmpty()
                || (!operation.sourcePath().isEmpty() && operation.sourcePath().equals(operation.targetPath()))) {
                throw new MigrationException("Generate Operation Must Declare A Distinct Target And Target Hash");
            }
            return;
        }
        if (changed && type != MigrationOperationType.CONVERT) {
            throw new MigrationException("Changed adapter bytes require a convert operation: " + operation.sourcePath());
        }
        if (!changed && operation.sourcePath().equals(operation.targetPath()) && type != MigrationOperationType.COPY) {
            throw new MigrationException("Unchanged same-path adapter bytes require a copy operation: " + operation.sourcePath());
        }
        if (!changed && !operation.sourcePath().equals(operation.targetPath())
            && type != MigrationOperationType.MOVE && type != MigrationOperationType.RENAME) {
            throw new MigrationException("Unchanged relocation requires move or rename operation: " + operation.sourcePath());
        }
    }

    private static void materializeQuarantine(Path source, Path staging, OperationSet operations,
                                              QuarantineReport report) throws IOException {
        for (QuarantineRecord record : report.records()) {
            String recordId = requireRecordId(record.recordId());
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            rejectReservedPath(sourceLocation);
            Path sourcePath = MigrationPaths.resolveInside(source, sourceLocation);
            if (Files.isSymbolicLink(sourcePath) || !Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Source Is Not A Regular File: " + sourceLocation);
            }
            if (!record.sourceHash().isEmpty() && !record.sourceHash().equals(sha256(sourcePath))) {
                throw new MigrationException("Quarantine Source Hash Does Not Match: " + sourceLocation);
            }
            Path destination = MigrationPaths.resolveInside(staging, QUARANTINE_ROOT + "/" + recordId + "/" + sourceLocation);
            ensureParent(destination, staging);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Target Collision: " + destination);
            }
            MigrationOperation replacement = operations.targets().get(sourceLocation);
            if (replacement != null && !samePathConversion(replacement, sourceLocation)) {
                throw new MigrationException("Quarantine Source Collides With A Declared Target: " + sourceLocation);
            }
            AtomicFiles.copy(sourcePath, destination);
            if (replacement == null) {
                Files.deleteIfExists(MigrationPaths.resolveInside(staging, sourceLocation));
            }
        }
    }

    private static void verifyExpectedFiles(Path staging, Map<String, String> sourceFiles,
                                            OperationSet operations, QuarantineReport report) throws IOException {
        Map<String, String> expected = new TreeMap<>(sourceFiles);
        for (MigrationOperation operation : operations.operations()) {
            MigrationOperationType type = operation.type();
            if (!type.preservesSource()) {
                expected.remove(operation.sourcePath());
            }
            if (type.targetRequired()) {
                String targetHash = operation.targetHash().isEmpty()
                    ? operation.targetPath().equals(operation.sourcePath()) ? sourceFiles.get(operation.sourcePath()) : null
                    : operation.targetHash();
                expected.put(operation.targetPath(), targetHash);
            }
        }
        for (QuarantineRecord record : report.records()) {
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            String sourceHash = sourceFiles.get(sourceLocation);
            if (sourceHash == null) {
                throw new MigrationException("Quarantine Source Is Not In The Source File Set: " + sourceLocation);
            }
            MigrationOperation replacement = operations.targets().get(sourceLocation);
            if (replacement == null) {
                expected.remove(sourceLocation);
            }
            expected.put(QUARANTINE_ROOT + "/" + requireRecordId(record.recordId()) + "/" + sourceLocation, sourceHash);
        }
        Map<String, String> actual = inspectStagedFiles(staging);
        if (!expected.keySet().equals(actual.keySet())) {
            Set<String> unexpected = new HashSet<>(actual.keySet());
            unexpected.removeAll(expected.keySet());
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(actual.keySet());
            throw new MigrationException("Staged Root File Set Does Not Match Plan: unexpected=" + unexpected + ", missing=" + missing);
        }
        for (Map.Entry<String, String> expectedFile : expected.entrySet()) {
            if (expectedFile.getValue() != null && !expectedFile.getValue().equals(actual.get(expectedFile.getKey()))) {
                throw new MigrationException("Staged File Hash Does Not Match: " + expectedFile.getKey());
            }
        }
    }

    private static Map<String, String> inspectStagedFiles(Path staging) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Map<String, String> folded = new HashMap<>();
        List<Path> filePaths = new ArrayList<>();
        Files.walkFileTree(staging, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(staging)) {
                    registerCaseFolded(folded, MigrationPaths.relative(staging, directory));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(staging, file);
                registerCaseFolded(folded, relative);
                files.put(relative, sha256(file));
                rejectFileAlias(filePaths, file, "Staged Root");
                filePaths.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Verify Staged Root: " + file, exception);
            }
        });
        return files;
    }

    private static void rejectFileAlias(List<Path> previous, Path candidate, String label) throws IOException {
        for (Path value : previous) {
            if (Files.isSameFile(value, candidate)) {
                throw new MigrationException(label + " Contains A File Alias: " + candidate);
            }
        }
    }

    private static void copyTree(Path source, Path staging) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                Path destination = directory.equals(source) ? staging : MigrationPaths.resolveInside(staging, MigrationPaths.relative(source, directory));
                MigrationPaths.requireNoSymlinkTraversal(staging, destination);
                Files.createDirectories(destination);
                MigrationPaths.requireNoSymlinkTraversal(staging, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                Path destination = MigrationPaths.resolveInside(staging, MigrationPaths.relative(source, file));
                ensureParent(destination, staging);
                AtomicFiles.copy(file, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Copy Staged Root: " + file, exception);
            }
        });
    }

    private static void ensureParent(Path destination, Path staging) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new MigrationException("Staged Destination Has No Parent: " + destination);
        }
        MigrationPaths.requireNoSymlinkTraversal(staging, parent);
        Files.createDirectories(parent);
        MigrationPaths.requireNoSymlinkTraversal(staging, parent);
    }

    private static void rejectReservedPath(String path) throws IOException {
        MigrationPaths.requireRelative(path);
        if (path.equals(".quarantine") || path.startsWith(".quarantine/")) {
            throw new MigrationException("The Reserved Quarantine Path Cannot Be A Migration Target: " + path);
        }
    }

    private static String requireRecordId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new MigrationException("Quarantine Record ID Must Be One Safe Path Segment");
        }
        return value;
    }

    private static void registerCaseFolded(Map<String, String> paths, String path) throws IOException {
        String folded = caseFold(path);
        String previous = paths.putIfAbsent(folded, path);
        if (previous != null && !previous.equals(path)) {
            throw new MigrationException(StandaloneUpgradePlanner.CASE_COLLISION_CODE + ": Case-equivalent filesystem paths collide: " + previous + " and " + path);
        }
    }

    private static String caseFold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String canonicalOperation(MigrationOperation operation) {
        return operation.kind() + "\u0000" + operation.adapterId() + "\u0000" + operation.sourcePath() + "\u0000" + operation.targetPath() + "\u0000" + operation.sourceHash() + "\u0000" + operation.targetHash();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path); var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static void deleteTree(Path root, Exception primary) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException exception) {
            primary.addSuppressed(exception);
            return;
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                primary.addSuppressed(exception);
            }
        }
    }

    private static boolean isDescendant(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    private static boolean samePathConversion(MigrationOperation operation, String sourceLocation) {
        return operation.type() == MigrationOperationType.CONVERT
            && sourceLocation.equals(operation.sourcePath())
            && sourceLocation.equals(operation.targetPath());
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private record SourceTree(Map<String, String> files, Set<String> directories) {
        private SourceTree {
            files = Map.copyOf(files);
            directories = Set.copyOf(directories);
        }
    }

    private record OperationSet(List<MigrationOperation> operations, Map<String, MigrationOperation> targets) {
        private OperationSet {
            operations = List.copyOf(operations);
            targets = Map.copyOf(targets);
        }
    }

    private static final class SnapshotTransformFailure extends RuntimeException {
        private SnapshotTransformFailure(Throwable cause) {
            super("Snapshot Upgrade Adapter Failed", cause);
        }
    }

    private static String message(Throwable exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    public record RecoveryMetadata(
        Path sourceRoot,
        Path stagingRoot,
        String planHash,
        String sourceSnapshotId,
        String sourceManifestHash,
        String stagedReplacementDigest,
        String stagedSnapshotId,
        String stagedManifestHash,
        boolean stagedPresent,
        boolean stagedVerified,
        String stagedFailure
    ) {
        public RecoveryMetadata {
            sourceRoot = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
            stagingRoot = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
            planHash = requireDigest(planHash, "planHash");
            sourceSnapshotId = requireText(sourceSnapshotId, "sourceSnapshotId");
            sourceManifestHash = requireDigest(sourceManifestHash, "sourceManifestHash");
            stagedReplacementDigest = optionalDigest(stagedReplacementDigest, "stagedReplacementDigest");
            stagedSnapshotId = stagedSnapshotId == null ? "" : stagedSnapshotId;
            stagedManifestHash = optionalDigest(stagedManifestHash, "stagedManifestHash");
            stagedFailure = stagedFailure == null ? "" : stagedFailure;
            if (!stagedPresent && (!stagedReplacementDigest.isEmpty() || !stagedSnapshotId.isEmpty()
                || !stagedManifestHash.isEmpty() || stagedVerified || !stagedFailure.isEmpty())) {
                throw new IllegalArgumentException("Absent Recovery Staging Cannot Carry Staging Metadata");
            }
            if (stagedVerified && (stagedSnapshotId.isEmpty() || stagedManifestHash.isEmpty()
                || !stagedFailure.isEmpty())) {
                throw new IllegalArgumentException("Verified Recovery Staging Metadata Is Incomplete");
            }
        }

        private static String optionalDigest(String value, String field) {
            return value == null || value.isBlank() ? "" : requireDigest(value, field);
        }
    }

    public record TypedStageContext(
        SnapshotMetadata targetMetadata,
        List<VerifiedSnapshotExporter.ChangeTargetOwner> changeTargetOwners,
        Path exportRoot,
        boolean productionEvidence,
        List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
        List<AssetAdoptionArtifactProducer.BlockedState> blocked
    ) {
        public TypedStageContext(
            VerifiedSnapshotAdmission ignoredSourceAdmission,
            SnapshotMetadata targetMetadata,
            List<VerifiedSnapshotExporter.SourceOwnerBinding> ignoredSourceOwnerBindings,
            List<VerifiedSnapshotExporter.ChangeTargetOwner> changeTargetOwners,
            Path exportRoot,
            List<AssetAdoptionArtifactProducer.LifecycleOutput> lifecycleOutputs,
            List<AssetAdoptionArtifactProducer.BlockedState> blocked
        ) {
            this(targetMetadata, changeTargetOwners, exportRoot,
                (lifecycleOutputs == null || lifecycleOutputs.isEmpty())
                    && (blocked == null || blocked.isEmpty()), lifecycleOutputs, blocked);
        }

        public TypedStageContext {
            targetMetadata = Objects.requireNonNull(targetMetadata, "targetMetadata");
            changeTargetOwners = List.copyOf(changeTargetOwners == null ? List.of() : changeTargetOwners);
            exportRoot = MigrationPaths.requirePath(exportRoot, "exportRoot");
            lifecycleOutputs = List.copyOf(lifecycleOutputs == null ? List.of() : lifecycleOutputs);
            blocked = List.copyOf(blocked == null ? List.of() : blocked);
        }

        private VerifiedSnapshotAdmission admit(Path source, MigrationPlan plan) throws IOException {
            VerifiedSnapshotAdmission actual = new SnapshotService(new MigrationFence())
                .admitExported(source);
            if (!actual.metadata().equals(targetMetadataForSource(actual, plan))
                || !actual.snapshot().manifest().manifestHash().equals(plan.sourceManifestHash())
                || !actual.metadata().snapshotId().equals(plan.sourceSnapshotId())) {
                throw new MigrationException("Typed Stage Source Admission Does Not Match The Commit Snapshot Or Plan");
            }
            return actual;
        }

        private VerifiedSnapshotExporter.Request request(Path stagedRoot, VerifiedSnapshotAdmission actualSource)
            throws IOException {
            List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwnerBindings = actualSource.snapshot()
                .manifest().entries().stream()
                .map(entry -> new VerifiedSnapshotExporter.SourceOwnerBinding(entry.relativePath(), entry.owner()))
                .toList();
            Map<String, String> actualOwners = actualSource.snapshot().manifest().entries().stream()
                .collect(Collectors.toMap(SnapshotManifest.Entry::relativePath,
                    SnapshotManifest.Entry::owner));
            Map<String, String> targetOwnerByPath = new TreeMap<>();
            for (VerifiedSnapshotExporter.ChangeTargetOwner value : changeTargetOwners) {
                String actualOwner = actualOwners.get(value.relativePath());
                if (actualOwner != null && !actualOwner.equals(value.owner())) {
                    throw new MigrationException("Typed Stage Change Target Owner Does Not Match The Declared Adaptation: "
                        + value.relativePath());
                }
                String previous = targetOwnerByPath.putIfAbsent(value.relativePath(), value.owner());
                if (previous != null && !previous.equals(value.owner())) {
                    throw new MigrationException("Typed Stage Change Target Owners Conflict: " + value.relativePath());
                }
            }
            List<Path> stagedFiles;
            try (var stream = Files.walk(stagedRoot)) {
                stagedFiles = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
            }
            for (Path stagedFile : stagedFiles) {
                String relative = MigrationPaths.relative(stagedRoot, stagedFile);
                if (!relative.startsWith(QUARANTINE_ROOT + "/")) {
                    continue;
                }
                String remainder = relative.substring((QUARANTINE_ROOT + "/").length());
                int separator = remainder.indexOf('/');
                if (separator <= 0 || separator == remainder.length() - 1) {
                    throw new MigrationException("Typed Stage Quarantine Evidence Path Is Invalid: " + relative);
                }
                String original = remainder.substring(separator + 1);
                String owner = actualOwners.get(original);
                if (owner == null) {
                    throw new MigrationException("Typed Stage Quarantine Evidence Has No Original Owner: " + relative);
                }
                String previous = targetOwnerByPath.putIfAbsent(relative, owner);
                if (previous != null && !previous.equals(owner)) {
                    throw new MigrationException("Typed Stage Quarantine Evidence Owner Conflicts: " + relative);
                }
            }
            List<VerifiedSnapshotExporter.ChangeTargetOwner> targetOwners = targetOwnerByPath.entrySet().stream()
                .map(value -> new VerifiedSnapshotExporter.ChangeTargetOwner(value.getKey(), value.getValue()))
                .toList();
            return new VerifiedSnapshotExporter.Request(stagedRoot, actualSource, targetMetadata,
                sourceOwnerBindings, targetOwners, exportRoot);
        }

        private SnapshotMetadata targetMetadataForSource(VerifiedSnapshotAdmission actual, MigrationPlan plan) {
            SnapshotMetadata source = actual.metadata();
            return new SnapshotMetadata(plan.sourceFormatVersion(), source.snapshotId(), source.createdAt(),
                source.build(), source.catalogChecksum(), source.extensionVersions());
        }
    }
}
