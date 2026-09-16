package restudio.resync.upgrade.cli;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.ActivationMarkerWriter;
import restudio.resync.migration.AcceptedStagePublisher;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.AuthorityUseGrant;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.OfflineReplacementUpgradeEntrypoint;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.PreflightResult;
import restudio.resync.migration.ProductionAcceptedStagePublisher;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.VerifiedSnapshotAdmission;
import restudio.resync.migration.VerifiedSnapshotExporter;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;
import restudio.resync.upgrade.InvocationBoundUpgradePlanner;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.InvocationBinding;
import restudio.resync.upgrade.StandaloneUpgradePlanner;
import restudio.resync.upgrade.StandaloneUpgradeStager;
import restudio.resync.upgrade.SnapshotAdapterBinding;
import restudio.resync.upgrade.UpgradeApplyResult;
import restudio.resync.upgrade.UpgradeDryRunResult;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradePlanner;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeStatus;
import restudio.resync.upgrade.UpgradeVersion;
import restudio.resync.upgrade.adapter.IdentityFileAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class OfflineUpgradeCli {
    private static final int FAILURE_EXIT = 10;
    private static final String PUBLISHER_CONTRACT_IDENTITY = ProductionAcceptedStagePublisher.CONTRACT_IDENTITY;

    private OfflineUpgradeCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }

    public static int run(String[] args, PrintWriter output, PrintWriter errors) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(errors, "errors");
        try {
            return run(args, output, errors, OfflineUpgradeAdapterRegistry.discover());
        } catch (Exception exception) {
            Result result = Result.failure("UPGRADE_FAILURE", reason(exception), ExitCode.FAILURE);
            result.write(requestedOutputFormat(args), output);
            return result.exitCode().value();
        }
    }

    public static int run(String[] args, PrintWriter output, PrintWriter errors, OfflineUpgradeAdapterRegistry discovered) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(errors, "errors");
        OutputFormat requestedFormat = requestedOutputFormat(args);
        Parsed parsed;
        try {
            parsed = Parsed.parse(args, discovered);
        } catch (UsageException exception) {
            Result result = Result.failure("USAGE_ERROR", exception.getMessage(), ExitCode.USAGE_ERROR);
            result.write(requestedFormat, output);
            return result.exitCode().value();
        } catch (IllegalArgumentException exception) {
            Result result = Result.failure("USAGE_ERROR", reason(exception), ExitCode.USAGE_ERROR);
            result.write(requestedFormat, output);
            return result.exitCode().value();
        } catch (Exception exception) {
            Result result = Result.failure("UPGRADE_FAILURE", reason(exception), ExitCode.FAILURE);
            result.write(requestedFormat, output);
            return result.exitCode().value();
        }
        if (parsed.help()) {
            output.println(usage());
            return ExitCode.SUCCESS.value();
        }
        try {
            Result result = execute(parsed.options());
            result.write(parsed.options().outputFormat(), output);
            return result.exitCode().value();
        } catch (UsageException exception) {
            Result result = Result.failure("USAGE_ERROR", exception.getMessage(), ExitCode.USAGE_ERROR);
            result.write(parsed.options().outputFormat(), output);
            return result.exitCode().value();
        } catch (Exception exception) {
            Result result = Result.failure("UPGRADE_FAILURE", reason(exception), ExitCode.FAILURE);
            result.write(parsed.options().outputFormat(), output);
            return result.exitCode().value();
        }
    }

    private static OutputFormat requestedOutputFormat(String[] args) {
        String[] values = args == null ? new String[0] : args;
        OutputFormat format = OutputFormat.HUMAN;
        for (int index = 0; index < values.length; index++) {
            if (!"--output".equals(values[index]) || index + 1 >= values.length) {
                continue;
            }
            String value = values[index + 1];
            if ("json".equalsIgnoreCase(value)) {
                format = OutputFormat.JSON;
            } else if ("human".equalsIgnoreCase(value)) {
                format = OutputFormat.HUMAN;
            }
            index++;
        }
        return format;
    }

    public static ExitCode dryRunExit(UpgradeStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case READY -> ExitCode.SUCCESS;
            case AWAITING_QUARANTINE_ACCEPTANCE -> ExitCode.QUARANTINE_REQUIRED;
            case FAILED, APPLIED, ALREADY_COMMITTED -> ExitCode.FAILURE;
        };
    }

    public static ExitCode applyExit(UpgradeStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case APPLIED, ALREADY_COMMITTED -> ExitCode.SUCCESS;
            case AWAITING_QUARANTINE_ACCEPTANCE -> ExitCode.QUARANTINE_REQUIRED;
            case FAILED, READY -> ExitCode.FAILURE;
        };
    }

    public static StandaloneUpgradeStager.RecoveryMetadata recoveryMetadata(
        Path sourceRoot, Path stagingRoot, MigrationPlan plan
    ) throws IOException {
        return StandaloneUpgradeStager.recoveryMetadata(sourceRoot, stagingRoot, plan);
    }

    public static StandaloneUpgradeStager.RecoveryMetadata recoveryMetadata(
        Path sourceRoot, Path controlRoot, String migrationId
    ) throws IOException {
        String normalizedMigrationId = requireMigrationId(migrationId);
        Path control = MigrationPaths.requirePath(controlRoot, "controlRoot");
        Path planPath = control.resolve("plans").resolve(normalizedMigrationId + ".plan")
            .toAbsolutePath().normalize();
        Path stagingRoot = control.resolve("staging").resolve(normalizedMigrationId)
            .toAbsolutePath().normalize();
        return recoveryMetadata(sourceRoot, stagingRoot, MigrationPlan.read(planPath));
    }

    public enum ExitCode {
        SUCCESS(0),
        USAGE_ERROR(2),
        QUARANTINE_REQUIRED(3),
        FAILURE(10);

        private final int value;

        ExitCode(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    public enum OutputFormat {
        HUMAN,
        JSON;

        private static OutputFormat parse(String value) {
            if (value == null || value.equalsIgnoreCase("human")) {
                return HUMAN;
            }
            if (value.equalsIgnoreCase("json")) {
                return JSON;
            }
            if (!value.isBlank()) {
                throw new UsageException("Invalid output format");
            }
            throw new UsageException("Invalid output format");
        }
    }

    private static Result execute(Options options) throws IOException {
        if (options.command() == Command.APPLY && options.persistenceCoordinationRoot() == null) {
            throw new UsageException("Apply Requires An Explicit Persistence Coordination Root");
        }
        if (options.command() == Command.DRY_RUN) {
            VerifiedSnapshotAdmission admission = verifiedSource(options, OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY);
            writeInvocation(options, admission.snapshot());
            OfflineReplacementUpgradeEntrypoint entrypoint = new OfflineReplacementUpgradeEntrypoint(request(
                options, null, null, OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY, admission, null));
            UpgradeDryRunResult dryRun = entrypoint.dryRun();
            retainPlanOnlyResult(options, dryRun, admission);
            UpgradeStatus status = displayStatus(dryRun);
            return Result.dryRun(dryRun, status, status == UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE
                ? ExitCode.QUARANTINE_REQUIRED : dryRunExit(status));
        }

        ActivationMarkerWriter.Binding authority = options.authority();
        if (authority == null) {
            throw new UsageException("Apply Requires A Production Authority Bundle, Trust Anchor, And Use Grant");
        }
        OfflineReplacementUpgradeEntrypoint.Request.Mode mode = OfflineReplacementUpgradeEntrypoint.Request.Mode.APPLY_OR_RESUME;
        VerifiedSnapshotAdmission retainedAdmission = verifiedSource(options, mode);
        UpgradePlanner planner = boundPlanner(options, retainedAdmission.snapshot());
        UpgradeDryRunResult retained = retainedDryRun(options, planner, retainedAdmission);
        boolean hasQuarantine = retained.proposal().map(UpgradeProposal::quarantineReport)
            .map(report -> !report.records().isEmpty()).orElse(false);
        if (options.acceptQuarantine() && !hasQuarantine) {
            throw new UsageException("--accept-quarantine requires retained quarantine records");
        }
        if (retained.status() == UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE && !options.acceptQuarantine()) {
            return Result.dryRun("apply", retained, retained.status(), ExitCode.QUARANTINE_REQUIRED);
        }
        OfflineReplacementUpgradeEntrypoint.Request request = request(
            options, authority, retainedAcceptance(options, retained), mode, retainedAdmission,
            retained.proposal().map(UpgradeProposal::plan).orElseThrow(
                () -> new MigrationException("Offline Upgrade Plan Is Missing")));
        UpgradeApplyResult applied = new OfflineReplacementUpgradeEntrypoint(request).apply(retained);
        AcceptedStagePublisher.Result publication = request.acceptedStagePublisher()
            instanceof ProductionAcceptedStagePublisher production ? production.lastResult() : AcceptedStagePublisher.Result.none();
        return Result.applied(applied, retained, applyExit(applied.status()), publication);
    }

    private static OfflineReplacementUpgradeEntrypoint.Request request(Options options,
                                                                         ActivationMarkerWriter.Binding authority,
                                                                         QuarantineAcceptance acceptance,
                                                                         OfflineReplacementUpgradeEntrypoint.Request.Mode mode,
                                                                         VerifiedSnapshotAdmission admission,
                                                                         MigrationPlan productionPlan) throws IOException {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        Snapshot invocationSnapshot = admission == null ? invocationSnapshot(options) : admission.snapshot();
        UpgradePlanner planner = boundPlanner(options, invocationSnapshot);
        StandaloneUpgradeStager.TypedStageContext typedStageContext = productionPlan == null
            ? null : typedStageContext(options, productionPlan, invocationSnapshot);
        StandaloneUpgradeStager stager = new StandaloneUpgradeStager(
            options.adapters(), invocationPath(options), InvocationBinding.digest(invocationCanonical(options, invocationSnapshot)),
            typedStageContext);
        ActivationMarkerWriter markerWriter = authority == null
            ? ActivationMarkerWriter.none()
            : ActivationMarkerWriter.replacementRoot(authority);
        OfflineReplacementUpgradeEntrypoint.Request request = mode
            == OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY
            ? new OfflineReplacementUpgradeEntrypoint.Request(
                options.sourceRoot(),
                options.controlRoot(),
                options.metadata(),
                options.window(),
                planner,
                participants,
                stager,
                null,
                null,
                acceptance,
                options.reservedBytes(),
                options.migrationId(),
                markerWriter,
                options.trustAnchor(),
                options.authorityUseGrant(),
                mode,
                null,
                null,
                "")
            : new OfflineReplacementUpgradeEntrypoint.Request(
                options.sourceRoot(),
                options.controlRoot(),
                options.metadata(),
                options.window(),
                planner,
                participants,
                stager,
                null,
                null,
                acceptance,
                options.reservedBytes(),
                options.migrationId(),
                markerWriter,
                options.trustAnchor(),
                options.authorityUseGrant(),
                mode);
        if (mode == OfflineReplacementUpgradeEntrypoint.Request.Mode.APPLY_OR_RESUME) {
            request = request.withPersistenceCoordination(options.persistenceCoordinationRoot(),
                new ProductionAcceptedStagePublisher(options.persistenceCoordinationRoot()),
                options.publisherContractIdentity());
        }
        return admission == null ? request
            : request.withVerifiedSource(Objects.requireNonNull(admission, "admission"));
    }

    private static StandaloneUpgradeStager.TypedStageContext typedStageContext(
        Options options,
        MigrationPlan plan,
        Snapshot sourceSnapshot
    ) throws IOException {
        Map<String, String> sourceOwners = new TreeMap<>();
        sourceSnapshot.manifest().entries().forEach(entry -> sourceOwners.put(entry.relativePath(), entry.owner()));
        List<VerifiedSnapshotExporter.ChangeTargetOwner> changeTargetOwners = plan.operations().stream()
            .filter(operation -> !operation.targetPath().isBlank())
            .map(operation -> new VerifiedSnapshotExporter.ChangeTargetOwner(operation.targetPath(), sourceOwners.getOrDefault(
                operation.targetPath(), adapterOwner(options.adapters(), operation.adapterId()))))
            .toList();
        SnapshotMetadata sourceMetadata = options.metadata();
        SnapshotMetadata targetMetadata = new SnapshotMetadata(plan.targetFormatVersion(), sourceMetadata.snapshotId(),
            sourceMetadata.createdAt(), sourceMetadata.build(), sourceMetadata.catalogChecksum(),
            sourceMetadata.extensionVersions());
        Path exportRoot = options.persistenceCoordinationRoot().resolve("post-stage").resolve(options.migrationId())
            .toAbsolutePath().normalize();
        return new StandaloneUpgradeStager.TypedStageContext(targetMetadata, changeTargetOwners, exportRoot,
            true, declaredLifecycleOutputs(options, plan, sourceSnapshot), List.of());
    }

    private static List<AssetAdoptionArtifactProducer.LifecycleOutput> declaredLifecycleOutputs(
        Options options,
        MigrationPlan plan,
        Snapshot sourceSnapshot
    ) throws IOException {
        Map<String, DeclaredLifecycleOutput> grouped = new TreeMap<>();
        Map<String, String> sourceOwners = sourceSnapshot.manifest().entries().stream()
            .collect(Collectors.toMap(SnapshotManifest.Entry::relativePath,
                SnapshotManifest.Entry::owner));
        for (MigrationOperation operation : plan.operations()) {
            if (!isLegacyTypedEvidenceAdapter(operation.adapterId())) {
                continue;
            }
            String sourcePath = optionalPath(operation.sourcePath());
            String targetPath = optionalPath(operation.targetPath());
            boolean sourceAsset = sourcePath != null && sourcePath.startsWith("assets/");
            boolean targetAsset = targetPath != null && targetPath.startsWith("assets/");
            if (!sourceAsset && !targetAsset) {
                continue;
            }
            if (sourcePath != null && !sourceAsset || targetPath != null && !targetAsset) {
                throw new MigrationException("Typed Stage Declared Lifecycle Operation Crosses The Assets Boundary: "
                    + operation.adapterId());
            }
            String owner = sourcePath == null ? sourceOwners.get(targetPath)
                : sourceOwners.get(sourcePath);
            if (owner == null) {
                owner = sourceOwners.get(targetPath);
            }
            if (owner == null) {
                owner = adapterOwner(options.adapters(), operation.adapterId());
            }
            String declaredOwner = owner;
            if (sourcePath != null && targetPath != null) {
                String targetOwner = sourceOwners.get(targetPath);
                if (targetOwner != null && !targetOwner.equals(declaredOwner)) {
                    throw new MigrationException("Typed Stage Declared Lifecycle Owner Does Not Match The Target: "
                        + targetPath);
                }
            }
            String key = operation.adapterId() + "\u0000" + declaredOwner;
            DeclaredLifecycleOutput output = grouped.computeIfAbsent(key,
                ignored -> new DeclaredLifecycleOutput("declared." + operation.adapterId() + "." + declaredOwner,
                    declaredOwner));
            String claimPath = sourcePath == null ? targetPath : sourcePath;
            output.addClaim(claimPath);
            output.changes().add(new AssetAdoptionArtifactProducer.AcceptedChange(
                operation.kind(), sourcePath, targetPath,
                optionalHash(operation.sourceHash()), optionalHash(operation.targetHash()), declaredOwner));
        }
        return grouped.values().stream().map(DeclaredLifecycleOutput::output).toList();
    }

    private static boolean isLegacyTypedEvidenceAdapter(String adapterId) {
        return adapterId != null && (adapterId.startsWith("resync.legacy-snapshot#")
            || adapterId.startsWith("resync.command-binding-cross-document#"));
    }

    private static String optionalPath(String value) {
        return value == null || value.isBlank() ? null : MigrationPaths.requireRelative(value);
    }

    private static String optionalHash(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class DeclaredLifecycleOutput {
        private final String adapterId;
        private final String owner;
        private final List<AssetAdoptionArtifactProducer.Claim> claims = new ArrayList<>();
        private final Set<String> claimPaths = new HashSet<>();
        private final List<AssetAdoptionArtifactProducer.AcceptedChange> changes = new ArrayList<>();

        private DeclaredLifecycleOutput(String adapterId, String owner) {
            this.adapterId = adapterId;
            this.owner = owner;
        }

        private void addClaim(String path) {
            if (claimPaths.add(path)) {
                claims.add(new AssetAdoptionArtifactProducer.Claim(path, owner));
            }
        }

        private List<AssetAdoptionArtifactProducer.AcceptedChange> changes() {
            return changes;
        }

        private AssetAdoptionArtifactProducer.LifecycleOutput output() {
            return new AssetAdoptionArtifactProducer.LifecycleOutput(adapterId, owner, claims, changes);
        }
    }

    private static String adapterOwner(OfflineUpgradeAdapterRegistry adapters, String wireId) {
        OfflineUpgradeAdapter adapter = adapters.byWireId(wireId);
        if (adapter != null) {
            return adapter.owner();
        }
        var snapshotAdapter = adapters.snapshotByWireId(wireId);
        if (snapshotAdapter != null) {
            return snapshotAdapter.owner();
        }
        throw new IllegalStateException("Offline Upgrade Plan References An Unknown Adapter: " + wireId);
    }

    private static VerifiedSnapshotAdmission verifiedSource(Options options,
                                                             OfflineReplacementUpgradeEntrypoint.Request.Mode mode) throws IOException {
        Path admissionRoot = mode == OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY
            ? options.sourceRoot()
            : retainedSnapshotRoot(options);
        VerifiedSnapshotAdmission admission = new SnapshotService(new MigrationFence()).admitExported(admissionRoot);
        requirePublisherContractIdentity(admission, options.publisherContractIdentity());
        return admission;
    }

    private static void requirePublisherContractIdentity(VerifiedSnapshotAdmission admission,
                                                         String expected) throws IOException {
        if (admission == null || !PUBLISHER_CONTRACT_IDENTITY.equals(expected)) {
            throw new MigrationException("Production Snapshot Publisher Contract Identity Does Not Match The Invocation");
        }
    }

    private static QuarantineAcceptance retainedAcceptance(Options options, UpgradeDryRunResult retained) {
        if (!options.acceptQuarantine()) {
            return null;
        }
        return retained.proposal().map(UpgradeProposal::quarantineReport)
            .map(report -> report.accept(options.acceptedBy(), Instant.EPOCH))
            .orElse(null);
    }

    private static void retainPlanOnlyResult(Options options, UpgradeDryRunResult result,
                                             VerifiedSnapshotAdmission admission) throws IOException {
        if (result.proposal().isEmpty() || result.sourceSnapshot().isEmpty()) {
            return;
        }
        UpgradeProposal proposal = result.proposal().get();
        Snapshot sourceSnapshot = result.sourceSnapshot().get();
        Snapshot admittedSnapshot = admission.snapshot();
        requireAdmissionBinding(sourceSnapshot, admittedSnapshot, proposal.plan(), options.publisherContractIdentity());
        Path snapshotRoot = options.controlRoot().resolve("snapshots").resolve(options.migrationId())
            .toAbsolutePath().normalize();
        Path planPath = options.controlRoot().resolve("plans").resolve(options.migrationId() + ".plan")
            .toAbsolutePath().normalize();
        Path reportPath = planPath.resolveSibling(planPath.getFileName() + ".report");
        if (!Files.exists(planPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(planPath.getParent());
            proposal.plan().write(planPath);
        }
        MigrationPlan retainedPlan = MigrationPlan.read(planPath);
        if (!retainedPlan.canonicalText().equals(proposal.plan().canonicalText())
            || !retainedPlan.planHash().equals(proposal.plan().planHash())) {
            throw new MigrationException("Retained Offline Upgrade Plan Hash Does Not Match");
        }
        if (Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS)) {
            String canonical = result.proposal().orElseThrow().quarantineReport().canonicalText();
            String content = Files.readString(reportPath, StandardCharsets.UTF_8);
            String expected = canonical + "report-hash=" + proposal.quarantineReport().reportHash() + "\n";
            if (!content.equals(expected)) {
                throw new MigrationException("Retained Quarantine Report Does Not Match Proposal");
            }
        } else {
            String canonical = proposal.quarantineReport().canonicalText();
            AtomicFiles.write(reportPath, (canonical + "report-hash=" + proposal.quarantineReport().reportHash() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        }
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        Snapshot retainedSnapshot = Files.exists(snapshotRoot, LinkOption.NOFOLLOW_LINKS)
            ? snapshots.admitExported(snapshotRoot).snapshot()
            : snapshots.copyAdmitted(admission, snapshotRoot);
        requireAdmissionBinding(retainedSnapshot, admission.snapshot(), proposal.plan(), options.publisherContractIdentity());
    }

    private static void requireAdmissionBinding(Snapshot snapshot, Snapshot admitted, MigrationPlan plan,
                                                String expectedPublisherContractIdentity) throws IOException {
        ProductionSnapshotMetadataManifest.read(snapshot.root());
        ProductionSnapshotMetadataManifest.read(admitted.root());
        if (!snapshot.metadata().equals(admitted.metadata())
            || !snapshot.manifest().canonicalText().equals(admitted.manifest().canonicalText())
            || !snapshot.manifest().manifestHash().equals(admitted.manifest().manifestHash())
            || !PUBLISHER_CONTRACT_IDENTITY.equals(expectedPublisherContractIdentity)
            || !plan.sourceSnapshotId().equals(admitted.metadata().snapshotId())
            || !plan.sourceManifestHash().equals(admitted.manifest().manifestHash())) {
            throw new MigrationException("Retained Offline Upgrade Plan Does Not Match Verified Snapshot Admission");
        }
    }

    private static UpgradeDryRunResult retainedDryRun(Options options, UpgradePlanner planner,
                                                       VerifiedSnapshotAdmission admission) throws IOException {
        Snapshot snapshot = admission.snapshot();
        if (!snapshot.root().equals(retainedSnapshotRoot(options))) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Does Not Match Invocation");
        }
        if (!snapshot.metadata().equals(options.metadata())) {
            throw new MigrationException("Retained Offline Upgrade Snapshot Metadata Does Not Match Invocation");
        }
        requirePublisherContractIdentity(admission, options.publisherContractIdentity());
        verifyInvocation(options);
        UpgradeProposal proposal = planner.plan(snapshot, options.window());
        String invocationHash = InvocationBinding.digest(invocationCanonical(options));
        if (!proposal.plan().invocationHash().equals(invocationHash)) {
            throw new MigrationException("Retained Upgrade Plan Is Not Bound To The Invocation");
        }
        UpgradeStatus status = proposal.hasErrors()
            ? UpgradeStatus.FAILED
            : proposal.quarantineReport().records().isEmpty()
                ? UpgradeStatus.READY
                : UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE;
        return new UpgradeDryRunResult(status, Optional.of(snapshot), Optional.of(proposal), options.window(),
            new PreflightResult(true, List.of()), proposal.diagnostics());
    }

    private static Path retainedSnapshotRoot(Options options) {
        return options.controlRoot().resolve("snapshots").resolve(options.migrationId()).toAbsolutePath().normalize();
    }

    private static void writeInvocation(Options options, Snapshot snapshot) throws IOException {
        InvocationBinding.write(invocationPath(options), invocationCanonical(options, snapshot));
    }

    private static void verifyInvocation(Options options) throws IOException {
        InvocationBinding.verify(invocationPath(options), invocationCanonical(options, invocationSnapshot(options)));
    }

    private static Path invocationPath(Options options) {
        return options.controlRoot().resolve("plans").resolve(options.migrationId() + ".invocation").toAbsolutePath().normalize();
    }

    private static UpgradePlanner boundPlanner(Options options, Snapshot snapshot) throws IOException {
        return new InvocationBoundUpgradePlanner(
            new StandaloneUpgradePlanner(options.adapters()),
            InvocationBinding.digest(invocationCanonical(options, snapshot)));
    }

    private static String invocationCanonical(Options options) throws IOException {
        return invocationCanonical(options, invocationSnapshot(options));
    }

    private static Snapshot invocationSnapshot(Options options) {
        Path root = options.command() == Command.APPLY ? retainedSnapshotRoot(options) : options.sourceRoot();
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return new SnapshotService(new MigrationFence()).admitExported(root).snapshot();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String invocationCanonical(Options options, Snapshot snapshot) throws IOException {
        String publisherContractIdentity = options.publisherContractIdentity();
        if (!PUBLISHER_CONTRACT_IDENTITY.equals(publisherContractIdentity)) {
            throw new MigrationException("Production Snapshot Publisher Contract Identity Does Not Match The Invocation");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", 1);
        value.put("sourceRoot", options.sourceRoot().toString());
        value.put("controlRoot", options.controlRoot().toString());
        value.put("persistenceCoordinationRoot", null);
        value.put("snapshotMetadata", Map.of(
            "formatVersion", options.metadata().formatVersion(),
            "snapshotId", options.metadata().snapshotId(),
            "createdAt", options.metadata().createdAt().toString(),
            "build", options.metadata().build(),
            "catalogChecksum", options.metadata().catalogChecksum(),
            "extensionVersions", options.metadata().extensionVersions()));
        value.put("publisherContractIdentity", publisherContractIdentity);
        value.put("sourceWindow", Map.of(
            "upgraderVersion", options.window().upgraderVersion().value(),
            "sourceFormatVersion", options.window().sourceFormatVersion(),
            "sourceBuild", options.window().sourceBuild(),
            "targetFormatVersion", options.window().targetFormatVersion(),
            "replacementContract", options.window().replacementContract()));
        value.put("migrationId", options.migrationId());
        value.put("reservedBytes", options.reservedBytes());
        value.put("preservePaths", options.preservePaths());
        value.put("adapters", options.adapters().adapters().stream()
            .sorted(Comparator.comparing((OfflineUpgradeAdapter adapter) -> adapter.wireId())
                .thenComparing(OfflineUpgradeAdapter::owner)
                .thenComparing(adapter -> adapter.getClass().getName()))
            .map(adapter -> Map.of("wireId", adapter.wireId(), "owner", adapter.owner(),
                "implementation", adapter.getClass().getName()))
            .toList());
        value.put("snapshotAdapters", options.adapters().snapshotAdapters().stream()
            .sorted(Comparator.comparing((OfflineUpgradeSnapshotAdapter adapter) -> adapter.wireId())
                .thenComparing(OfflineUpgradeSnapshotAdapter::owner)
                .thenComparing(adapter -> adapter.getClass().getName()))
            .map(adapter -> Map.of("wireId", adapter.wireId(), "owner", adapter.owner(),
                "implementation", adapter.getClass().getName()))
            .toList());
        if (snapshot != null) {
            value.put("snapshotAdapterResultHash", SnapshotAdapterBinding.capture(snapshot, options.adapters()).bindingHash());
        }
        return CanonicalJson.canonicalize(value);
    }

    private static UpgradeStatus displayStatus(UpgradeDryRunResult result) {
        if (result.status() != UpgradeStatus.FAILED || result.proposal().isEmpty()
            || result.proposal().get().quarantineReport().records().isEmpty()) {
            return result.status();
        }
        boolean quarantineOnly = result.diagnostics().diagnostics().stream()
            .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
            .allMatch(diagnostic -> diagnostic.code().equals("MIGRATION.QUARANTINE_UNRESOLVED"));
        return quarantineOnly ? UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE : result.status();
    }

    private static String usage() {
        return "Usage: resync-offline-upgrader <dry-run|apply> --source <directory> "
            + "--snapshot-id <id> --source-format <number> --source-build <build> "
            + "--target-format <number> --replacement-contract <contract> --catalog-checksum <sha256> "
            + "--migration-id <id> [--preserve <relative-path>]... [--extension <owner=version>]... [--output human|json] [--control <directory>] [--persistence-coordination-root <directory>]"
            + "\nProduction upgrades require --authority-bundle <file> --authority-trust-anchor <file> "
            + "--authority-use-grant <file>; apply may additionally use --accept-quarantine.";
    }

    private record Result(String command, UpgradeStatus status, ExitCode exitCode, String message,
                          String planHash, String snapshotId, String manifestHash, String quarantineHash,
                          int operations, int quarantines, boolean changed,
                          String adoptionArtifactPath, String adoptionArtifactHash,
                          String adoptionSourceManifestHash, String adoptionPostStageManifestHash) {
        private static Result dryRun(UpgradeDryRunResult result, UpgradeStatus status, ExitCode code) {
            return dryRun("dry-run", result, status, code);
        }

        private static Result dryRun(String command, UpgradeDryRunResult result, UpgradeStatus status, ExitCode code) {
            UpgradeProposal proposal = result.proposal().orElse(null);
            return new Result(command, status, code, diagnosticMessage(result.diagnostics()),
                proposal == null ? null : proposal.plan().planHash(),
                result.sourceSnapshot().map(snapshot -> snapshot.metadata().snapshotId()).orElse(null),
                result.sourceSnapshot().map(snapshot -> snapshot.manifest().manifestHash()).orElse(null),
                proposal == null ? null : proposal.quarantineReport().reportHash(),
                proposal == null ? 0 : proposal.plan().operations().size(),
                proposal == null ? 0 : proposal.quarantineReport().records().size(), false,
                null, null, null, null);
        }

        private static Result applied(UpgradeApplyResult result, UpgradeDryRunResult dryRun, ExitCode code) {
            return applied(result, dryRun, code, AcceptedStagePublisher.Result.none());
        }

        private static Result applied(UpgradeApplyResult result, UpgradeDryRunResult dryRun, ExitCode code,
                                      AcceptedStagePublisher.Result publication) {
            UpgradeProposal proposal = dryRun.proposal().orElse(null);
            return new Result("apply", result.status(), code, diagnosticMessage(result.diagnostics()),
                result.planHash().orElse(proposal == null ? null : proposal.plan().planHash()),
                dryRun.sourceSnapshot().map(snapshot -> snapshot.metadata().snapshotId()).orElse(null),
                dryRun.sourceSnapshot().map(snapshot -> snapshot.manifest().manifestHash()).orElse(null),
                proposal == null ? null : proposal.quarantineReport().reportHash(),
                proposal == null ? 0 : proposal.plan().operations().size(),
                proposal == null ? 0 : proposal.quarantineReport().records().size(), result.changed(),
                publication.artifactPath().isBlank() ? null : publication.artifactPath(),
                publication.artifactHash().isBlank() ? null : publication.artifactHash(),
                publication.sourceManifestHash().isBlank() ? null : publication.sourceManifestHash(),
                publication.postStageManifestHash().isBlank() ? null : publication.postStageManifestHash());
        }

        private static String diagnosticMessage(DiagnosticSet diagnostics) {
            return diagnostics.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                .map(Result::formatDiagnostic)
                .findFirst()
                .orElse(null);
        }

        private static String formatDiagnostic(Diagnostic diagnostic) {
            Object reason = diagnostic.evidence().get("reason");
            String detail = reason == null ? diagnostic.message() : reason.toString();
            return diagnostic.code() + ": " + detail.replace('\n', ' ').replace('\r', ' ');
        }

        private static Result failure(String status, String message, ExitCode code) {
            return new Result("error", null, code, message, null, null, null, null, 0, 0, false,
                null, null, null, null);
        }

        private void write(OutputFormat format, PrintWriter output) {
            if (format == OutputFormat.JSON) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("command", command);
                value.put("exitCode", exitCode.value());
                value.put("status", status == null ? "FAILED" : status.name());
                value.put("message", message);
                value.put("changed", changed);
                value.put("operations", operations);
                value.put("quarantines", quarantines);
                value.put("planHash", planHash);
                value.put("snapshotId", snapshotId);
                value.put("manifestHash", manifestHash);
                value.put("quarantineReportHash", quarantineHash);
                value.put("adoptionArtifactPath", adoptionArtifactPath);
                value.put("adoptionArtifactHash", adoptionArtifactHash);
                value.put("adoptionSourceManifestHash", adoptionSourceManifestHash);
                value.put("adoptionPostStageManifestHash", adoptionPostStageManifestHash);
                output.println(CanonicalJson.canonicalize(value));
                return;
            }
            output.println("command=" + command);
            output.println("status=" + (status == null ? "FAILED" : status.name()));
            output.println("exit-code=" + exitCode.value());
            output.println("changed=" + changed);
            output.println("operations=" + operations);
            output.println("quarantines=" + quarantines);
            if (planHash != null) {
                output.println("plan-hash=" + planHash);
            }
            if (snapshotId != null) {
                output.println("snapshot-id=" + snapshotId);
            }
            if (manifestHash != null) {
                output.println("manifest-hash=" + manifestHash);
            }
            if (quarantineHash != null) {
                output.println("quarantine-report-hash=" + quarantineHash);
            }
            if (adoptionArtifactPath != null) {
                output.println("adoption-artifact-path=" + adoptionArtifactPath);
            }
            if (adoptionArtifactHash != null) {
                output.println("adoption-artifact-hash=" + adoptionArtifactHash);
            }
            if (adoptionSourceManifestHash != null) {
                output.println("adoption-source-manifest-hash=" + adoptionSourceManifestHash);
            }
            if (adoptionPostStageManifestHash != null) {
                output.println("adoption-post-stage-manifest-hash=" + adoptionPostStageManifestHash);
            }
            if (message != null) {
                output.println("message=" + message);
            }
        }
    }

    private enum Command {
        DRY_RUN,
        APPLY
    }

    private record Options(Command command, Path sourceRoot, Path controlRoot, Path persistenceCoordinationRoot, SnapshotMetadata metadata,
                           String publisherContractIdentity, UpgradeSourceWindow window, String migrationId, long reservedBytes,
                           OfflineUpgradeAdapterRegistry adapters, List<String> preservePaths,
                           boolean acceptQuarantine, String acceptedBy,
                           ActivationMarkerWriter.Binding authority,
                           ProductionAuthorityTrustAnchor trustAnchor,
                           AuthorityUseGrant authorityUseGrant, OutputFormat outputFormat) {
        private Options {
            preservePaths = preservePaths == null ? List.of() : preservePaths.stream().sorted().toList();
        }
    }

    private record Parsed(Options options, boolean help) {
        private static Parsed parse(String[] args, OfflineUpgradeAdapterRegistry discovered) throws IOException {
            List<String> values = new ArrayList<>(List.of(args == null ? new String[0] : args));
            if (values.size() == 1 && values.getFirst().equals("--help")) {
                return new Parsed(null, true);
            }
            if (values.isEmpty() || (!values.getFirst().equals("dry-run") && !values.getFirst().equals("apply"))) {
                throw new UsageException("Exactly one command, dry-run or apply, is required");
            }
            Command command = values.removeFirst().equals("dry-run") ? Command.DRY_RUN : Command.APPLY;
            Map<String, List<String>> options = new LinkedHashMap<>();
            List<String> preserve = new ArrayList<>();
            List<String> extensions = new ArrayList<>();
            boolean accept = false;
            List<String> supported = List.of(
                "source", "control", "snapshot-id", "source-format", "source-build", "target-format",
                "replacement-contract", "catalog-checksum", "created-at", "migration-id", "reserved-bytes",
                "output", "accepted-by", "authority-bundle", "authority-trust-anchor", "authority-use-grant",
                "persistence-coordination-root");
            for (int index = 0; index < values.size(); index++) {
                String option = values.get(index);
                if (option.equals("--accept-quarantine")) {
                    if (accept) {
                        throw new UsageException("Duplicate option: --accept-quarantine");
                    }
                    accept = true;
                    continue;
                }
                if (!option.startsWith("--") || option.length() == 2) {
                    throw new UsageException("Invalid option: " + option);
                }
                String name = option.substring(2);
                if (name.equals("persistence-coordination") || name.equals("coordination-root")) {
                    name = "persistence-coordination-root";
                }
                if (name.equals("preserve")) {
                    preserve.add(next(values, ++index, option));
                    continue;
                }
                if (name.equals("extension")) {
                    extensions.add(next(values, ++index, option));
                    continue;
                }
                if (name.equals("accept-quarantine")) {
                    throw new UsageException("--accept-quarantine does not take a value");
                }
                if (!supported.contains(name)) {
                    throw new UsageException("Unknown option: " + option);
                }
                String value = next(values, ++index, option);
                if (options.putIfAbsent(name, List.of(value)) != null) {
                    throw new UsageException("Duplicate option: " + option);
                }
            }
            OutputFormat format = OutputFormat.parse(single(options, "output", "human"));
            Path source = directory(singleRequired(options, "source"), "source");
            Path control = options.containsKey("control")
                ? MigrationPaths.requirePath(Path.of(singleRequired(options, "control")), "control")
                : source.getParent().resolve(OfflineReplacementUpgradeEntrypoint.CONTROL_DIRECTORY_NAME);
            validateControl(source, control);
            Path persistenceCoordinationRoot = options.containsKey("persistence-coordination-root")
                ? directory(singleRequired(options, "persistence-coordination-root"), "persistence coordination root")
                : null;
            if (persistenceCoordinationRoot != null) {
                MigrationPaths.requireDistinctRoots(source, persistenceCoordinationRoot);
                MigrationPaths.requireDistinctRoots(control, persistenceCoordinationRoot);
            }
            ProductionSnapshotMetadataManifest.Values productionMetadata =
                ProductionSnapshotMetadataManifest.read(source);
            String snapshotId = text(singleRequired(options, "snapshot-id"), "snapshot-id");
            String sourceBuild = text(singleRequired(options, "source-build"), "source-build");
            int sourceFormat = positiveInt(singleRequired(options, "source-format"), "source-format");
            int targetFormat = positiveInt(singleRequired(options, "target-format"), "target-format");
            String contract = text(singleRequired(options, "replacement-contract"), "replacement-contract");
            String catalog = digest(singleRequired(options, "catalog-checksum"), "catalog-checksum");
            Instant createdAt = options.containsKey("created-at") ? instant(singleRequired(options, "created-at")) : Instant.EPOCH;
            String migrationId = migrationId(singleRequired(options, "migration-id"));
            long reserved = options.containsKey("reserved-bytes") ? nonNegativeLong(singleRequired(options, "reserved-bytes"), "reserved-bytes") : 0;
            List<String> normalizedPreserve = preserve.stream().map(MigrationPaths::requireRelative).toList();
            List<OfflineUpgradeAdapter> adapterValues = new ArrayList<>(discovered.adapters());
            if (!normalizedPreserve.isEmpty()) {
                IdentityFileAdapter mandatoryIdentity = new IdentityFileAdapter(normalizedPreserve,
                    ProductionPersistenceOwners.STANDALONE_ROOT);
                int identityIndex = -1;
                for (int index = 0; index < adapterValues.size(); index++) {
                    if (IdentityFileAdapter.KEY.wireId().equals(adapterValues.get(index).wireId())) {
                        identityIndex = index;
                        break;
                    }
                }
                if (identityIndex < 0) {
                    adapterValues.add(mandatoryIdentity);
                } else {
                    OfflineUpgradeAdapter existing = adapterValues.get(identityIndex);
                    if (!(existing instanceof IdentityFileAdapter identity)
                        || !identity.owner().equals(ProductionPersistenceOwners.STANDALONE_ROOT)) {
                        throw new UsageException("Duplicate Offline Upgrade Adapter: " + IdentityFileAdapter.KEY.wireId());
                    }
                    adapterValues.set(identityIndex, identity.withAdditionalPaths(normalizedPreserve));
                }
            }
            OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(
                adapterValues, discovered.snapshotAdapters());
            if (command == Command.DRY_RUN && (accept || options.containsKey("accepted-by"))) {
                throw new UsageException("Apply-only options cannot be used with dry-run");
            }
            SnapshotMetadata declaredMetadata = new SnapshotMetadata(sourceFormat, snapshotId, createdAt, sourceBuild, catalog,
                extensions(extensions));
            if (!declaredMetadata.equals(productionMetadata.metadata())) {
                throw new UsageException("Source Metadata Does Not Match The Production Snapshot Metadata Manifest");
            }
            SnapshotMetadata metadata = productionMetadata.metadata();
            UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, metadata.formatVersion(),
                metadata.build(),
                targetFormat, contract);
            if (command == Command.DRY_RUN && hasAuthorityOption(options)) {
                throw new UsageException("Authority Options Cannot Be Used With Dry-Run");
            }
            AuthorityInput authorityInput = command == Command.APPLY
                ? parseAuthority(options, source, migrationId)
                : null;
            ActivationMarkerWriter.Binding authority = authorityInput == null ? null : authorityInput.binding();
            if (authority != null && authority.authorityBundle() != null) {
                ProductionAuthorityBundle bundle = authority.authorityBundle();
                if (!bundle.snapshotId().canonicalText().equals(metadata.snapshotId())) {
                    throw new UsageException("Authority Bundle Snapshot Identity Does Not Match Source Metadata");
                }
                if (!bundle.catalogContentChecksum().canonicalText().equals(metadata.catalogChecksum())) {
                    throw new UsageException("Authority Bundle Catalog Checksum Does Not Match Source Metadata");
                }
                requireSourceServerIdentity(source, bundle.serverId());
                ProductionAuthorityTrustAnchor trustAnchor = authorityInput.trustAnchor();
                if (!trustAnchor.matchesDurableInstall(source) || !bundle.verifySignature(source, trustAnchor)) {
                    throw new UsageException("Authority Bundle Signature Does Not Match Durable Install Authority");
                }
                if (!bundle.freshAt(Instant.now())) {
                    throw new UsageException("Authority Bundle Timestamp Is Outside The Freshness Window");
                }
            }
            if (options.containsKey("accepted-by") && !accept) {
                throw new UsageException("--accepted-by requires --accept-quarantine");
            }
            String acceptedBy = text(options.containsKey("accepted-by") ? singleRequired(options, "accepted-by") : "offline-cli", "accepted-by");
            Options parsedOptions = new Options(command, source, control, persistenceCoordinationRoot, metadata,
                PUBLISHER_CONTRACT_IDENTITY, window, migrationId, reserved, registry,
                normalizedPreserve.stream().sorted().toList(), accept, acceptedBy, authority,
                authorityInput == null ? null : authorityInput.trustAnchor(),
                authorityInput == null ? null : authorityInput.authorityUseGrant(), format);
            if (authorityInput != null) {
                requireGrantTuple(parsedOptions, authorityInput);
            }
            return new Parsed(parsedOptions, false);
        }

        private static Map<String, String> extensions(List<String> values) {
            Map<String, String> result = new TreeMap<>();
            for (String value : values) {
                int separator = value.indexOf('=');
                if (separator <= 0 || separator == value.length() - 1 || value.indexOf('=', separator + 1) >= 0) {
                    throw new UsageException("Invalid extension owner=version");
                }
                String owner = text(value.substring(0, separator), "extension owner");
                String version = text(value.substring(separator + 1), "extension version");
                if (result.putIfAbsent(owner, version) != null) {
                    throw new UsageException("Duplicate extension owner: " + owner);
                }
            }
            return result;
        }

        private record AuthorityInput(ProductionAuthorityBundle bundle,
                                      ProductionAuthorityTrustAnchor trustAnchor,
                                      AuthorityUseGrant authorityUseGrant,
                                      ActivationMarkerWriter.Binding binding) {
        }

        private static boolean hasAuthorityOption(Map<String, List<String>> options) {
            return options.containsKey("authority-bundle")
                || options.containsKey("authority-trust-anchor")
                || options.containsKey("authority-use-grant");
        }

        private static AuthorityInput parseAuthority(Map<String, List<String>> options,
                                                      Path sourceRoot,
                                                      String migrationId) throws IOException {
            boolean hasBundle = options.containsKey("authority-bundle");
            boolean hasAnchor = options.containsKey("authority-trust-anchor");
            boolean hasGrant = options.containsKey("authority-use-grant");
            if (!hasBundle || !hasAnchor || !hasGrant) {
                throw new UsageException("Production Upgrades Require --authority-bundle, --authority-trust-anchor, And --authority-use-grant");
            }
            ProductionAuthorityBundle bundle = ProductionAuthorityBundle.read(
                MigrationPaths.requirePath(Path.of(singleRequired(options, "authority-bundle")), "authority-bundle"));
            ProductionAuthorityTrustAnchor trustAnchor = ProductionAuthorityTrustAnchor.fromCanonical(
                readCanonicalFile(Path.of(singleRequired(options, "authority-trust-anchor")), "authority-trust-anchor"));
            AuthorityUseGrant authorityUseGrant = AuthorityUseGrant.fromCanonical(
                readCanonicalFile(Path.of(singleRequired(options, "authority-use-grant")), "authority-use-grant"));
            ActivationMarkerWriter.Binding binding = ActivationMarkerWriter.Binding.authenticated(bundle, trustAnchor, authorityUseGrant);
            return new AuthorityInput(bundle, trustAnchor, authorityUseGrant, binding);
        }

        private static String readCanonicalFile(Path path, String field) throws IOException {
            Path normalized = MigrationPaths.requirePath(path, field);
            if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new UsageException("" + field + " Must Be A Regular File");
            }
            if (Files.size(normalized) > 128 * 1024) {
                throw new UsageException(field + " Is Too Large");
            }
            return Files.readString(normalized, StandardCharsets.UTF_8);
        }

        private static void requireGrantTuple(Options options, AuthorityInput authority) throws IOException {
            ProductionAuthorityBundle bundle = authority.bundle();
            ProductionAuthorityTrustAnchor trustAnchor = authority.trustAnchor();
            AuthorityUseGrant grant = authority.authorityUseGrant();
            String canonicalSourcePath = AuthorityUseGrant.canonicalSourcePath(options.sourceRoot());
            String sourceIdentityHash = AuthorityUseGrant.sourceIdentityDigest(options.sourceRoot(), bundle);
            if (!grant.serverId().equals(bundle.serverId())
                || !grant.installationId().equals(bundle.installationId())
                || !grant.installAuthorityHash().equals(bundle.installAuthorityHash())
                || !grant.snapshotId().equals(bundle.snapshotId())
                || !grant.canonicalSourcePath().equals(canonicalSourcePath)
                || !grant.sourceIdentityHash().equals(sourceIdentityHash)
                || !grant.migrationId().equals(options.migrationId())) {
                throw new UsageException("Authority Use Grant Does Not Match The Requested Source, Snapshot, Or Migration");
            }
            Snapshot currentSnapshot = invocationSnapshot(options);
            if (currentSnapshot == null) {
                return;
            }
            String invocationHash = InvocationBinding.digest(invocationCanonical(options, currentSnapshot));
            if (!grant.verify(trustAnchor, bundle, canonicalSourcePath, sourceIdentityHash, options.migrationId(),
                invocationHash, grant.planPreimageHash(), Instant.now())) {
                throw new UsageException("Authority Use Grant Does Not Match The Requested Invocation");
            }
        }

        private static void requireSourceServerIdentity(Path source, ServerId expected) throws IOException {
            Path identity = MigrationPaths.requirePath(source.resolve("server-id"), "source server identity");
            if (Files.isSymbolicLink(identity) || !Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) {
                throw new UsageException("Authority Bundle Requires The Source Server Identity");
            }
            String text;
            try {
                text = Files.readString(identity, StandardCharsets.UTF_8);
                if (!text.endsWith("\n") || text.indexOf('\r') >= 0 || !text.substring(0, text.length() - 1).equals(expected.canonicalText())) {
                    throw new UsageException("Source Server Identity Does Not Match Authority Bundle");
                }
                ServerId actual = ServerId.parseCanonicalText(text.substring(0, text.length() - 1));
                if (!actual.equals(expected)) {
                    throw new UsageException("Source Server Identity Does Not Match Authority Bundle");
                }
            } catch (IllegalArgumentException exception) {
                throw new UsageException("Source Server Identity Is Invalid: " + exception.getMessage());
            }
        }

        private static String next(List<String> values, int index, String option) {
            if (index >= values.size() || values.get(index).startsWith("--")) {
                throw new UsageException("Missing value for " + option);
            }
            return values.get(index);
        }

        private static String singleRequired(Map<String, List<String>> values, String name) {
            List<String> found = values.get(name);
            if (found == null || found.isEmpty() || found.getFirst().isBlank()) {
                throw new UsageException("Missing required option: --" + name);
            }
            return found.getFirst();
        }

        private static String single(Map<String, List<String>> values, String name, String fallback) {
            return values.getOrDefault(name, List.of(fallback)).getFirst();
        }

        private static Path directory(String value, String field) throws IOException {
            return MigrationPaths.requireDirectory(Path.of(value), field);
        }

        private static void validateControl(Path source, Path control) throws IOException {
            if (source.getParent() == null || !source.getParent().equals(control.getParent())
                || !OfflineReplacementUpgradeEntrypoint.CONTROL_DIRECTORY_NAME.equals(control.getFileName().toString())) {
                throw new UsageException("control must be the sibling .resync-replacement-control directory");
            }
            MigrationPaths.requireDistinctRoots(source, control);
        }

        private static String text(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
                throw new UsageException("Invalid " + field);
            }
            return normalized;
        }

        private static int positiveInt(String value, String field) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new UsageException("Invalid positive integer for " + field);
            }
        }

        private static long nonNegativeLong(String value, String field) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new UsageException("Invalid non-negative integer for " + field);
            }
        }

        private static Instant instant(String value) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException exception) {
                throw new UsageException("Invalid created-at timestamp");
            }
        }

        private static String digest(String value, String field) {
            if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
                throw new UsageException("Invalid SHA-256 digest for " + field);
            }
            return value.toLowerCase(Locale.ROOT);
        }

        private static String migrationId(String value) {
            String normalized = text(value, "migration-id");
            if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new UsageException("Invalid migration-id");
            }
            return normalized;
        }
    }

    private static String requireMigrationId(String value) {
        String normalized = Objects.requireNonNull(value, "migrationId").trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid migrationId");
        }
        return normalized;
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
