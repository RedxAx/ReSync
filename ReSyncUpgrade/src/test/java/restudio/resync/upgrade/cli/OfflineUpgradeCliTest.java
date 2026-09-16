package restudio.resync.upgrade.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.migration.AuthorityUseGrant;
import restudio.resync.migration.MigrationActivationMarker;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionAcceptedStagePublisher;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthoritySigner;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.IdentityFileAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.RawGraphUpgradeAdapter;
import restudio.resync.upgrade.flow.LegacyResyncSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.InvocationBinding;
import restudio.resync.upgrade.SnapshotAdapterBinding;
import restudio.resync.upgrade.StandaloneUpgradePlanner;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.UpgradeStatus;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OfflineUpgradeCliTest {
    private static final String PUBLISHER_CONTRACT_IDENTITY = ProductionAcceptedStagePublisher.CONTRACT_IDENTITY;

    @TempDir
    Path temporary;

    private final Map<Path, KeyPair> signingKeys = new HashMap<>();
    private int snapshotEvidenceSequence;

    @Test
    void rejectsUnknownAndDuplicateArgumentsWithUsageExit() {
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry();

        Invocation unknown = invoke(new String[]{"dry-run", "--unknown"}, registry);
        Invocation duplicate = invoke(new String[]{"dry-run", "--source", "one", "--source", "two"}, registry);

        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), unknown.code());
        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), duplicate.code());
        assertTrue(unknown.output().contains("FAILED"));
        assertTrue(duplicate.output().contains("FAILED"));
    }

    @Test
    void jsonFormatIsHonoredForEveryFailureWithoutStderrDuplication() {
        Invocation invocation = invoke(new String[]{"dry-run", "--output", "json", "--unknown"}, new OfflineUpgradeAdapterRegistry());

        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), invocation.code());
        assertFalse(jsonBoolean(invocation.output(), "changed"));
        assertEquals(1, invocation.output().lines().count());
        assertTrue(invocation.errors().isBlank());
    }

    @Test
    void legacyRawAuthorityOptionsAreRejectedByTheProductionCli() {
        Invocation invocation = invoke(new String[]{"apply", "--catalog-hash", "a".repeat(64),
            "--runtime-binding-hash", "b".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "c".repeat(64), "--readiness-version", "1"}, new OfflineUpgradeAdapterRegistry());

        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), invocation.code());
        assertTrue(invocation.output().contains("Unknown option"));
    }

    @Test
    void sourceWindowCannotBeSelfAuthorizedByCliArguments() throws Exception {
        Path source = source("production-window");
        String[] arguments = arguments("dry-run", source, "production-window-id");
        for (int index = 0; index < arguments.length - 1; index++) {
            if ("--source-build".equals(arguments[index])) {
                arguments[index + 1] = "operator-claimed-build";
            }
        }

        Invocation mismatch = invoke(arguments, new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter())));

        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), mismatch.code());
        assertTrue(mismatch.output().contains("Source Metadata Does Not Match"));

        String[] missingArguments = arguments("dry-run", source, "production-window-missing-id");
        Files.delete(ProductionSnapshotMetadataManifest.pathFor(source));
        Invocation missing = invoke(missingArguments,
            new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter())));
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), missing.code());
        assertTrue(missing.output().contains("Production Snapshot Metadata Manifest Is Missing"));
    }

    @Test
    void dryRunDoesNotChangeSourceAndProducesCanonicalMachineOutcome() throws Exception {
        Path source = source("dry-run");
        Path graph = source.resolve("graph.json");
        byte[] before = Files.readAllBytes(graph);
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));

        Invocation invocation = invoke(arguments("dry-run", source, "dry-run-id", "--preserve", "data.txt", "--output", "json"), registry);

        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), invocation.code());
        assertArrayEquals(before, Files.readAllBytes(graph));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertFalse(jsonBoolean(invocation.output(), "changed"));
        assertTrue(invocation.output().contains("\"status\":\"READY\""));
        MigrationPlan plan = MigrationPlan.read(source.resolveSibling(".resync-replacement-control/plans/dry-run-id.plan"));
        String invocationFile = Files.readString(source.resolveSibling(".resync-replacement-control/plans/dry-run-id.invocation"));
        String canonical = invocationFile.substring(0, invocationFile.lastIndexOf("\ninvocation-hash="));
        JsonValue.JsonObject invocationDocument = (JsonValue.JsonObject) CanonicalJson.parseTree(canonical);
        assertEquals(PUBLISHER_CONTRACT_IDENTITY,
            ((JsonValue.JsonString) invocationDocument.value("publisherContractIdentity")).value());
        assertEquals(InvocationBinding.digest(canonical), plan.invocationHash());
    }

    @Test
    void exactExportedOwnersSelectLegacyGraphAndCommandMigrationWithoutResnapshotting() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("owned-export"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Path commands = Files.createDirectories(source.resolve("assets/Blueprints/Commands"));
        String flowPath = "assets/Blueprints/Flows/fixture-flow.json";
        String commandPath = "assets/Blueprints/Commands/fixture-command.json";
        Files.writeString(flows.resolve("fixture-flow.json"),
            "{\"id\":\"fixture-flow\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{\"start\":{\"type\":\"event:server_start\",\"inputValues\":{}}},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("fixture-command.json"),
            "{\"id\":\"fixture-command\",\"resourceType\":\"command\",\"version\":1,\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputValues\":{}}},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(source.resolve("triggers.json"),
            "[{\"id\":\"fixture-command:command:start\",\"flowId\":\"fixture-command\",\"type\":\"COMMAND\",\"context\":\"{\\\"command\\\":\\\"fixture\\\"}\",\"unknown\":\"retire-with-command\"},{\"id\":\"fixture-event\",\"flowId\":\"fixture-flow\",\"type\":\"EVENT\",\"context\":\"server_start\",\"unknown\":{\"keep\":true}},{\"id\":\"fixture-system\",\"flowId\":\"fixture-system\",\"type\":\"SYSTEM\",\"context\":\"boot\",\"unknown\":[\"first\",\"second\"]}]",
            StandardCharsets.UTF_8);
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(
            List.of(), List.of(new LegacyResyncSnapshotAdapter()));
        String migrationId = "owned-export-id";
        String[] dryRunArguments = legacyArguments("dry-run", source, migrationId, "--output", "json");
        writeProductionMetadata(source, migrationId, OfflineUpgradeCliTest::legacyOwner, LegacySnapshotWindow.SOURCE_BUILD);
        Snapshot admitted = new SnapshotService(new MigrationFence()).admitExported(source).snapshot();
        var expectedTransform = new LegacyResyncSnapshotAdapter().transform(
            new OfflineUpgradeSnapshotInput(admitted.root(), ImmutableSnapshotAdapter.adapt(admitted)));
        assertTrue(expectedTransform.quarantines().isEmpty(), expectedTransform.quarantines().toString());
        Map<String, byte[]> expectedOutputs = new LinkedHashMap<>();
        expectedTransform.files().forEach(file -> expectedOutputs.put(file.sourcePath(), file.bytes()));
        assertEquals(Set.of(flowPath, commandPath, "triggers.json"), expectedOutputs.keySet());
        expectedOutputs.forEach((path, bytes) -> assertTrue(CanonicalJson.parseTree(bytes) != null, path));
        JsonValue.JsonObject expectedFlow = (JsonValue.JsonObject) CanonicalJson.parseTree(expectedOutputs.get(flowPath));
        JsonValue.JsonObject expectedFlowStart = (JsonValue.JsonObject) ((JsonValue.JsonObject) expectedFlow.value("nodes")).value("start");
        assertEquals("event.server.start", ((JsonValue.JsonString) expectedFlowStart.value("type")).value());
        JsonValue.JsonObject expectedCommand = (JsonValue.JsonObject) CanonicalJson.parseTree(expectedOutputs.get(commandPath));
        JsonValue.JsonObject expectedCommandStart = (JsonValue.JsonObject) ((JsonValue.JsonObject) expectedCommand.value("nodes")).value("start");
        JsonValue.JsonObject expectedCommandInputs = (JsonValue.JsonObject) expectedCommandStart.value("inputValues");
        assertEquals("fixture", ((JsonValue.JsonString) expectedCommandInputs.value("command")).value());
        assertTrue(((JsonValue.JsonString) expectedCommand.value("assetHash")).value().matches("[0-9a-f]{64}"));
        String expectedTriggers = CanonicalJson.canonicalize(CanonicalJson.parse("""
            [{"id":"fixture-event","flowId":"fixture-flow","type":"EVENT","context":"server_start","unknown":{"keep":true}},
             {"id":"fixture-system","flowId":"fixture-system","type":"SYSTEM","context":"boot","unknown":["first","second"]}]
            """));
        assertEquals(expectedTriggers, new String(expectedOutputs.get("triggers.json"), StandardCharsets.UTF_8));

        Invocation dryRun = invoke(dryRunArguments, registry);

        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), dryRun.code(), dryRun.output());
        Path retainedRoot = source.resolveSibling(".resync-replacement-control/snapshots/" + migrationId);
        SnapshotManifest retained = SnapshotManifest.read(retainedRoot.resolveSibling(migrationId + ".manifest"));
        assertArrayEquals(Files.readAllBytes(source.resolveSibling(source.getFileName() + ".manifest")),
            Files.readAllBytes(retainedRoot.resolveSibling(migrationId + ".manifest")));
        assertArrayEquals(Files.readAllBytes(source.resolveSibling(source.getFileName() + ".state")),
            Files.readAllBytes(retainedRoot.resolveSibling(migrationId + ".state")));
        assertArrayEquals(Files.readAllBytes(ProductionSnapshotMetadataManifest.pathFor(source)),
            Files.readAllBytes(ProductionSnapshotMetadataManifest.pathFor(retainedRoot)));
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, owner(retained, flowPath));
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, owner(retained, commandPath));
        assertEquals(ProductionPersistenceOwners.TRIGGERS, owner(retained, "triggers.json"));
        MigrationPlan plan = MigrationPlan.read(source.resolveSibling(".resync-replacement-control/plans/" + migrationId + ".plan"));
        assertEquals(retained.metadata().snapshotId(), plan.sourceSnapshotId());
        assertEquals(retained.manifestHash(), plan.sourceManifestHash());
        String adapterId = LegacyResyncSnapshotAdapter.ADAPTER_ID + "#" + LegacyResyncSnapshotAdapter.ADAPTER_VERSION;
        assertEquals(List.of(commandPath, flowPath, "triggers.json"), plan.operations().stream()
            .filter(operation -> operation.adapterId().equals(adapterId))
            .map(operation -> operation.sourcePath()).sorted().toList());

        String[] applyArguments = legacyArguments("apply", source, migrationId, "--output", "json",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1");
        writeProductionMetadata(source, migrationId, OfflineUpgradeCliTest::legacyOwner, LegacySnapshotWindow.SOURCE_BUILD);
        Invocation applied = invoke(applyArguments, registry);
        Invocation replayed = invoke(applyArguments, registry);

        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), applied.code(), applied.output());
        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), replayed.code(), replayed.output());
        assertTrue(applied.output().contains("\"status\":\"APPLIED\""), applied.output());
        assertTrue(replayed.output().contains("\"status\":\"ALREADY_COMMITTED\""), replayed.output());
        assertArrayEquals(expectedOutputs.get(flowPath), Files.readAllBytes(source.resolve(flowPath)));
        assertArrayEquals(expectedOutputs.get(commandPath), Files.readAllBytes(source.resolve(commandPath)));
        assertArrayEquals(expectedOutputs.get("triggers.json"), Files.readAllBytes(source.resolve("triggers.json")));
        assertEquals(expectedTriggers, Files.readString(source.resolve("triggers.json")));
    }

    @Test
    void applyIsDurableAndASecondProcessStyleApplyIsIdempotent() throws Exception {
        Path source = source("apply");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        String[] dryRun = arguments("dry-run", source, "apply-id", "--preserve", "data.txt", "--output", "json");
        String[] apply = arguments("apply", source, "apply-id", "--preserve", "data.txt", "--output", "json",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1");

        assertEquals(0, invoke(dryRun, registry).code());
        Invocation first = invoke(apply, registry);
        Invocation second = invoke(apply, registry);

        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), first.code());
        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), second.code());
        assertTrue(first.output().contains("\"status\":\"APPLIED\""));
        assertTrue(second.output().contains("\"status\":\"ALREADY_COMMITTED\""));
        assertTrue(Files.readString(source.resolve("graph.json")).contains("\"migrated\":true"));
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void committedReplayRejectsChangedRuntimeAuthority() throws Exception {
        Path source = source("authority-replay");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        assertEquals(0, invoke(arguments("dry-run", source, "authority-id", "--preserve", "data.txt"), registry).code());
        String[] apply = arguments("apply", source, "authority-id", "--preserve", "data.txt",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1");
        assertEquals(0, invoke(apply, registry).code());
        String[] changed = arguments("apply", source, "authority-id", "--preserve", "data.txt",
            "--catalog-hash", "e".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1", "--output", "json");

        Invocation replay = invoke(changed, registry);

        assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), replay.code());
        assertTrue(replay.output().contains("\"status\":\"FAILED\""));
        assertTrue(replay.errors().isBlank());
    }

    @Test
    void failedJournalIsRecoveredToRolledBackWithoutChangingSource() throws Exception {
        Path source = source("crash-recovery");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        String migrationId = "crash-recovery-id";
        assertEquals(0, invoke(arguments("dry-run", source, migrationId, "--preserve", "data.txt"), registry).code());
        String[] authorityArguments = arguments("apply", source, migrationId, "--preserve", "data.txt");
        prepareAuthorityUseGrant(authorityArguments, registry);
        Path control = source.resolveSibling(".resync-replacement-control");
        MigrationPlan plan = MigrationPlan.read(control.resolve("plans").resolve(migrationId + ".plan"));
        QuarantineReport report = QuarantineReport.empty();
        QuarantineAcceptance acceptance = report.accept("offline-upgrader", Instant.EPOCH);
        ProductionAuthorityBundle authority = ProductionAuthorityBundle.read(ProductionAuthorityBundle.path(source));
        ProductionAuthorityTrustAnchor trustAnchor = ProductionAuthorityTrustAnchor.fromCanonical(
            Files.readString(trustAnchorPath(source), StandardCharsets.UTF_8));
        AuthorityUseGrant grant = AuthorityUseGrant.fromCanonical(Files.readString(
            authorityUseGrantPath(source, migrationId, "a".repeat(64)), StandardCharsets.UTF_8));
        MigrationJournal journal = MigrationJournal.create(control.resolve("journals").resolve(migrationId + ".journal"), migrationId,
            plan.planHash(), new MigrationJournal.Binding(plan.sourceSnapshotId(), plan.sourceManifestHash(), report.reportHash(),
                acceptance.acceptanceHash(), "", "", "a".repeat(64), "c".repeat(64), 1, "d".repeat(64), 1,
                authority, CanonicalHash.rawSha256(trustAnchor.canonicalBytes()), grant));
        journal.transition(MigrationJournalState.PREPARED, "simulated crash");
        journal.transition(MigrationJournalState.FAILED, "simulated failure");

        Invocation recovered = invoke(arguments("apply", source, migrationId, "--preserve", "data.txt", "--output", "json",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1"), registry);

        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), recovered.code());
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(journal.path()).currentState().orElseThrow());
        assertTrue(Files.exists(source.resolve("graph.json")));
        assertTrue(recovered.errors().isBlank());
    }

    @Test
    void retainedInvocationAndSnapshotTamperingFailClosed() throws Exception {
        Path source = source("tampering");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        assertEquals(0, invoke(arguments("dry-run", source, "tamper-id", "--preserve", "data.txt"), registry).code());
        Path control = source.resolveSibling(".resync-replacement-control");
        Path invocation = control.resolve("plans/tamper-id.invocation");
        Files.writeString(invocation, Files.readString(invocation).replace("replacement-1", "replacement-2"), StandardCharsets.UTF_8);
        String[] apply = arguments("apply", source, "tamper-id", "--preserve", "data.txt", "--output", "json",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1");
        Invocation invocationFailure = invoke(apply, registry);
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), invocationFailure.code());
        assertTrue(invocationFailure.output().contains("\"status\":\"FAILED\""));

        Path secondSource = source("snapshot-tampering");
        assertEquals(0, invoke(arguments("dry-run", secondSource, "snapshot-tamper-id", "--preserve", "data.txt"), registry).code());
        Path retainedGraph = secondSource.resolveSibling(".resync-replacement-control/snapshots/snapshot-tamper-id/graph.json");
        Files.writeString(retainedGraph, "tampered", StandardCharsets.UTF_8);
        Invocation snapshotFailure = invoke(arguments("apply", secondSource, "snapshot-tamper-id", "--preserve", "data.txt", "--output", "json",
            "--catalog-hash", "b".repeat(64), "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1",
            "--readiness-hash", "d".repeat(64), "--readiness-version", "1"), registry);
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), snapshotFailure.code());
        assertTrue(snapshotFailure.output().contains("\"status\":\"FAILED\""));
        assertTrue(snapshotFailure.errors().isBlank());
    }

    @Test
    void changedBytesConvertAndUnchangedRelocationMovesAndConsumesSource() throws Exception {
        Path changedSource = source("operation-convert");
        OfflineUpgradeAdapterRegistry changedRegistry = new OfflineUpgradeAdapterRegistry(List.of(graphAdapter()));
        assertEquals(0, invoke(arguments("dry-run", changedSource, "operation-convert-id", "--preserve", "data.txt"), changedRegistry).code());
        MigrationPlan changedPlan = MigrationPlan.read(changedSource.resolveSibling(".resync-replacement-control/plans/operation-convert-id.plan"));
        assertEquals(MigrationOperationType.CONVERT, changedPlan.operations().stream()
            .filter(operation -> operation.sourcePath().equals("graph.json")).findFirst().orElseThrow().type());

        Path movedSource = source("operation-move");
        byte[] movedBytes = Files.readAllBytes(movedSource.resolve("graph.json"));
        OfflineUpgradeAdapterRegistry movedRegistry = new OfflineUpgradeAdapterRegistry(List.of(
            new RelocatingAdapter("graph.json", "moved/graph.json"),
            new IdentityFileAdapter("data.txt", "standalone-root")));
        assertEquals(0, invoke(arguments("dry-run", movedSource, "operation-move-id"), movedRegistry).code());
        MigrationPlan movedPlan = MigrationPlan.read(movedSource.resolveSibling(".resync-replacement-control/plans/operation-move-id.plan"));
        assertEquals(MigrationOperationType.MOVE, movedPlan.operations().stream()
            .filter(operation -> operation.sourcePath().equals("graph.json")).findFirst().orElseThrow().type());
        Invocation moved = invoke(arguments("apply", movedSource, "operation-move-id", "--catalog-hash", "b".repeat(64),
            "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1", "--readiness-hash", "d".repeat(64),
            "--readiness-version", "1"), movedRegistry);
        assertEquals(0, moved.code(), moved.output());
        assertFalse(Files.exists(movedSource.resolve("graph.json")));
        assertArrayEquals(movedBytes, Files.readAllBytes(movedSource.resolve("moved/graph.json")));
    }

    @Test
    void acceptedQuarantineIsMaterializedAndCaseCollisionFailsBeforeApply() throws Exception {
        Path quarantinedSource = source("accepted-quarantine");
        byte[] quarantinedBytes = Files.readAllBytes(quarantinedSource.resolve("graph.json"));
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry();
        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(),
            invoke(arguments("dry-run", quarantinedSource, "accepted-quarantine-id", "--preserve", "data.txt"), registry).code());
        Invocation applied = invoke(arguments("apply", quarantinedSource, "accepted-quarantine-id", "--preserve", "data.txt",
            "--accept-quarantine", "--accepted-by", "operator", "--catalog-hash", "b".repeat(64),
            "--runtime-binding-hash", "c".repeat(64), "--runtime-binding-version", "1", "--readiness-hash", "d".repeat(64),
            "--readiness-version", "1"), registry);
        assertEquals(OfflineUpgradeCli.ExitCode.SUCCESS.value(), applied.code(), applied.output());
        assertFalse(Files.exists(quarantinedSource.resolve("graph.json")));
        Path quarantineRoot = quarantinedSource.resolve(".quarantine");
        try (var files = Files.walk(quarantineRoot)) {
            List<Path> quarantined = files.filter(Files::isRegularFile)
                .filter(file -> file.endsWith(Path.of("graph.json")))
                .toList();
            assertEquals(1, quarantined.size());
            Path relative = quarantineRoot.relativize(quarantined.getFirst());
            assertEquals(3, relative.getNameCount());
            assertEquals("migration", relative.getName(0).toString());
            assertArrayEquals(quarantinedBytes, Files.readAllBytes(quarantined.getFirst()));
        }

        Path collisionSource = Files.createDirectories(temporary.resolve("case-collision"));
        Files.writeString(collisionSource.resolve("first.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(collisionSource.resolve("second.txt"), "b", StandardCharsets.UTF_8);
        Invocation collision = invoke(arguments("dry-run", collisionSource, "case-collision-id", "--output", "json"),
            new OfflineUpgradeAdapterRegistry(List.of(
                new RelocatingAdapter("relocation-first", "first.txt", "A.txt"), new RelocatingAdapter("relocation-second", "second.txt", "a.txt"))));
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), collision.code());
        assertTrue(collision.output().contains("\"status\":\"FAILED\""));
        assertEquals("a", Files.readString(collisionSource.resolve("first.txt"), StandardCharsets.UTF_8));
        assertEquals("b", Files.readString(collisionSource.resolve("second.txt"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(collisionSource)));
    }

    @Test
    void missingAdapterAndAdapterCollisionFailClosedWithQuarantineExit() throws Exception {
        Path source = source("quarantine");
        Invocation missing = invoke(arguments("dry-run", source, "missing-id", "--output", "json"), new OfflineUpgradeAdapterRegistry());
        Path collisionSource = source("collision-quarantine");
        OfflineUpgradeAdapterRegistry collisionRegistry = new OfflineUpgradeAdapterRegistry(java.util.List.of(
            new FixedAdapter("collision-a", "graph.json"), new FixedAdapter("collision-b", "graph.json"),
            new IdentityFileAdapter("data.txt", "standalone-root")));
        Invocation collision = invoke(arguments("dry-run", collisionSource, "collision-id", "--output", "json"), collisionRegistry);

        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(), missing.code());
        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(), collision.code());
        assertTrue(missing.output().contains("\"status\":\"AWAITING_QUARANTINE_ACCEPTANCE\""));
        assertTrue(collision.output().contains("\"quarantines\":1"));
    }

    @Test
    void malformedRawGraphIsQuarantinedWithoutChangingTheSource() throws Exception {
        Path source = source("invalid-graph");
        Path graph = source.resolve("graph.json");
        Files.writeString(graph, "{invalid", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(graph);
        Invocation invocation = invoke(arguments("dry-run", source, "invalid-id", "--preserve", "data.txt", "--output", "json"),
            new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter())));

        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(), invocation.code());
        assertTrue(invocation.output().contains("\"status\":\"AWAITING_QUARANTINE_ACCEPTANCE\""));
        assertTrue(invocation.output().contains("\"quarantines\":1"));
        assertArrayEquals(before, Files.readAllBytes(graph));
    }

    @Test
    void unsupportedRuntimeFlowAndCommandFilesAreQuarantinedWithoutPartialTransforms() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("unsupported-runtime"));
        Path flow = Files.createDirectories(source.resolve("assets/Blueprints/Flows")).resolve("legacy.json");
        Path command = Files.createDirectories(source.resolve("assets/Blueprints/Commands")).resolve("legacy.json");
        Files.writeString(flow, "{\"version\":1,\"nodes\":{}}", StandardCharsets.UTF_8);
        Files.writeString(command, "{\"nodes\":{\"command\":{\"type\":\"event.resync.command\",\"legacyContext\":\"/help\"}}}", StandardCharsets.UTF_8);
        byte[] flowBefore = Files.readAllBytes(flow);
        byte[] commandBefore = Files.readAllBytes(command);

        Invocation invocation = invoke(arguments("dry-run", source, "unsupported-runtime-id", "--output", "json"),
            new OfflineUpgradeAdapterRegistry());

        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(), invocation.code());
        assertTrue(invocation.output().contains("\"quarantines\":2"));
        assertArrayEquals(flowBefore, Files.readAllBytes(flow));
        assertArrayEquals(commandBefore, Files.readAllBytes(command));
        Path retained = source.resolveSibling(".resync-replacement-control/snapshots/unsupported-runtime-id");
        assertArrayEquals(flowBefore, Files.readAllBytes(retained.resolve("assets/Blueprints/Flows/legacy.json")));
        assertArrayEquals(commandBefore, Files.readAllBytes(retained.resolve("assets/Blueprints/Commands/legacy.json")));
        assertFalse(new String(flowBefore, StandardCharsets.UTF_8).contains("\"version\":2"));
    }

    @Test
    void planningRejectsTargetDirectoryAndFilePrefixCollisions() throws Exception {
        Path directoryCollision = source("target-directory-collision");
        Files.createDirectories(directoryCollision.resolve("existing"));
        Files.writeString(directoryCollision.resolve("existing/retained.txt"), "retained", StandardCharsets.UTF_8);
        Invocation directory = invoke(arguments("dry-run", directoryCollision, "target-directory-id", "--preserve", "data.txt",
            "--preserve", "existing/retained.txt", "--output", "json"),
            new OfflineUpgradeAdapterRegistry(java.util.List.of(new RelocatingAdapter("graph.json", "existing"))));
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), directory.code());
        assertTrue(directory.output().contains("MIGRATION.UPGRADE_TARGET_DIRECTORY"), directory.output());

        Path sourceFileCollision = source("target-file-prefix-collision");
        Invocation sourceFile = invoke(arguments("dry-run", sourceFileCollision, "target-file-prefix-id", "--preserve", "data.txt", "--output", "json"),
            new OfflineUpgradeAdapterRegistry(java.util.List.of(new RelocatingAdapter("graph.json", "graph.json/child.json"))));
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), sourceFile.code());
        assertTrue(sourceFile.output().contains("MIGRATION.UPGRADE_TARGET_PREFIX_COLLISION"));

        Path targetPrefixCollision = source("target-target-prefix-collision");
        Files.writeString(targetPrefixCollision.resolve("second.txt"), "second", StandardCharsets.UTF_8);
        Invocation targetPrefix = invoke(arguments("dry-run", targetPrefixCollision, "target-target-prefix-id", "--preserve", "data.txt", "--output", "json"),
            new OfflineUpgradeAdapterRegistry(java.util.List.of(
                new RelocatingAdapter("first", "graph.json", "target"),
                new RelocatingAdapter("second", "second.txt", "target/child.json"))));
        assertEquals(OfflineUpgradeCli.ExitCode.FAILURE.value(), targetPrefix.code());
        assertTrue(targetPrefix.output().contains("MIGRATION.UPGRADE_TARGET_PREFIX_COLLISION"));
    }

    @Test
    void dryRunRejectsEveryAuthorityOptionAndRemainsAuthorityFree() throws Exception {
        Path source = source("dry-run-authority-options");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        String[] base = arguments("dry-run", source, "dry-run-authority-id");
        String[][] authorityOptions = {
            {"--authority-bundle", ProductionAuthorityBundle.path(source).toString()},
            {"--authority-trust-anchor", trustAnchorPath(source).toString()},
            {"--authority-use-grant", authorityUseGrantPath(source, "dry-run-authority-id", "a".repeat(64)).toString()}
        };

        for (String[] authorityOption : authorityOptions) {
            List<String> values = new ArrayList<>(List.of(base));
            values.add(authorityOption[0]);
            values.add(authorityOption[1]);
            Invocation invocation = invoke(values.toArray(String[]::new), registry);

            assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), invocation.code());
            assertTrue(invocation.output().contains("Authority Options Cannot Be Used With Dry-Run"));
        }
        assertFalse(Files.exists(source.resolveSibling(".resync-replacement-control/plans/dry-run-authority-id.invocation")));
    }

    @Test
    void applyRejectsEveryPartialAuthorityTuple() throws Exception {
        Path source = source("partial-authority-tuple");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry(java.util.List.of(graphAdapter()));
        String[] complete = arguments("apply", source, "partial-authority-id");
        for (String option : List.of("--authority-bundle", "--authority-trust-anchor", "--authority-use-grant")) {
            Invocation invocation = invoke(withoutOption(complete, option), registry);

            assertEquals(OfflineUpgradeCli.ExitCode.USAGE_ERROR.value(), invocation.code());
            assertTrue(invocation.output().contains("Production Upgrades Require"));
        }
    }

    @Test
    void exitMappingIsExplicitForEveryUpgradeStatus() {
        assertEquals(0, OfflineUpgradeCli.dryRunExit(UpgradeStatus.READY).value());
        assertEquals(3, OfflineUpgradeCli.dryRunExit(UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE).value());
        assertEquals(10, OfflineUpgradeCli.dryRunExit(UpgradeStatus.FAILED).value());
        assertEquals(0, OfflineUpgradeCli.applyExit(UpgradeStatus.APPLIED).value());
        assertEquals(0, OfflineUpgradeCli.applyExit(UpgradeStatus.ALREADY_COMMITTED).value());
        assertEquals(3, OfflineUpgradeCli.applyExit(UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE).value());
        assertEquals(10, OfflineUpgradeCli.applyExit(UpgradeStatus.FAILED).value());
    }

    private Path source(String name) throws Exception {
        Path source = Files.createDirectories(temporary.resolve(name));
        Files.writeString(source.resolve("graph.json"), "{\"id\":\"flow\",\"nodes\":{},\"unknown\":{\"keep\":true}}", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("data.txt"), "preserve", StandardCharsets.UTF_8);
        return source;
    }

    private RawGraphUpgradeAdapter graphAdapter() {
        return new RawGraphUpgradeAdapter(new OfflineUpgradeAdapter.AdapterKey("test.graph", 1), "standalone-root", "graph.json", graph -> {
            Map<String, JsonValue> fields = new LinkedHashMap<>(graph.root().fields());
            fields.put("migrated", JsonValue.of(true));
            return graph.withRoot(JsonValue.object(fields));
        });
    }

    private String[] arguments(String command, Path source, String migrationId, String... extra) {
        return arguments(command, source, migrationId, "legacy", 2, "replacement-1", extra);
    }

    private String[] legacyArguments(String command, Path source, String migrationId, String... extra) {
        return arguments(command, source, migrationId, LegacySnapshotWindow.SOURCE_BUILD,
            LegacySnapshotWindow.TARGET_FORMAT_VERSION, LegacySnapshotWindow.REPLACEMENT_CONTRACT, extra);
    }

    private String[] arguments(String command, Path source, String migrationId, String sourceBuild,
                               int targetFormat, String replacementContract, String... extra) {
        String defaultCatalog = "a".repeat(64);
        String requestedCatalog = defaultCatalog;
        for (int index = 0; index < extra.length - 1; index++) {
            if ("--catalog-hash".equals(extra[index]) && !"b".repeat(64).equals(extra[index + 1])) {
                requestedCatalog = extra[index + 1];
            }
        }
        writeProductionMetadata(source, migrationId, ignored -> ProductionPersistenceOwners.STANDALONE_ROOT, sourceBuild);
        writeAuthorityBundle(source, migrationId, requestedCatalog);
        writeProductionMetadata(source, migrationId, ignored -> ProductionPersistenceOwners.STANDALONE_ROOT, sourceBuild);
        List<String> values = new ArrayList<>(List.of(
            command, "--source", source.toString(), "--snapshot-id", snapshotId(migrationId), "--source-format", "1",
            "--source-build", sourceBuild, "--target-format", Integer.toString(targetFormat), "--replacement-contract", replacementContract,
            "--catalog-checksum", "a".repeat(64), "--migration-id", migrationId,
            "--preserve", "server-id", "--preserve", ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE,
            "--preserve", "authority/authority-bundle.json"));
        for (int index = 0; index < extra.length; index++) {
            if (Set.of("--catalog-hash", "--runtime-binding-hash", "--runtime-binding-version",
                "--readiness-hash", "--readiness-version").contains(extra[index])) {
                index++;
                continue;
            }
            values.add(extra[index]);
        }
        Path bundle = requestedCatalog.equals(defaultCatalog)
            ? ProductionAuthorityBundle.path(source)
            : source.resolveSibling("authority-bundle-" + requestedCatalog + ".json");
        Path trustAnchor = trustAnchorPath(source);
        Path authorityUseGrant = authorityUseGrantPath(source, migrationId, requestedCatalog);
        if ("apply".equals(command)) {
            Path persistenceCoordinationRoot = persistenceCoordinationRoot(source, migrationId);
            try {
                Files.createDirectories(persistenceCoordinationRoot);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed To Prepare Persistence Coordination Root", exception);
            }
            values.add("--persistence-coordination-root");
            values.add(persistenceCoordinationRoot.toString());
            values.add("--authority-bundle");
            values.add(bundle.toString());
            values.add("--authority-trust-anchor");
            values.add(trustAnchor.toString());
            values.add("--authority-use-grant");
            values.add(authorityUseGrant.toString());
        }
        return values.toArray(String[]::new);
    }

    private String[] withoutOption(String[] args, String option) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            if (option.equals(args[index])) {
                index++;
                continue;
            }
            values.add(args[index]);
        }
        return values.toArray(String[]::new);
    }

    private void writeProductionMetadata(Path source, String migrationId) {
        writeProductionMetadata(source, migrationId, ignored -> ProductionPersistenceOwners.STANDALONE_ROOT, "legacy");
    }

    private void writeProductionMetadata(Path source, String migrationId, Function<String, String> owner) {
        writeProductionMetadata(source, migrationId, owner, "legacy");
    }

    private void writeProductionMetadata(Path source, String migrationId, Function<String, String> owner, String build) {
        try {
            Files.writeString(source.resolve("server-id"), testServerId().canonicalText() + "\n", StandardCharsets.UTF_8);
            Files.writeString(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "test-install-authority\n", StandardCharsets.UTF_8);
            SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId(migrationId), Instant.EPOCH,
                build, "a".repeat(64), Map.of());
            PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
            participants.register(new PersistenceParticipant() {
                @Override
                public String owner() {
                    return "standalone-root";
                }

                @Override
                public Path root() {
                    return source;
                }
            });
            SnapshotManifest scanned = SnapshotManifest.scan(source, metadata, participants);
            SnapshotManifest manifest = new SnapshotManifest(metadata, scanned.directories(), scanned.entries().stream()
                .map(entry -> new SnapshotManifest.Entry(entry.relativePath(), entry.size(), entry.sha256(),
                    owner.apply(entry.relativePath())))
                .toList());
            Path evidenceRoot = temporary.resolve("snapshot-evidence-" + snapshotEvidenceSequence++);
            Snapshot evidence = new SnapshotService(new MigrationFence()).createFenced(
                source, evidenceRoot, metadata, participants, manifest);
            Files.copy(evidence.manifestPath(), source.resolveSibling(source.getFileName() + ".manifest"),
                StandardCopyOption.REPLACE_EXISTING);
            Files.copy(evidence.statePath(), source.resolveSibling(source.getFileName() + ".state"),
                StandardCopyOption.REPLACE_EXISTING);
            Files.copy(ProductionSnapshotMetadataManifest.pathFor(evidence.root()),
                ProductionSnapshotMetadataManifest.pathFor(source), StandardCopyOption.REPLACE_EXISTING);
            new SnapshotService(new MigrationFence()).admitExported(source);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed To Prepare Production Snapshot Metadata Fixture", exception);
        }
    }

    private static String legacyOwner(String relativePath) {
        if (relativePath.equals("triggers.json")) {
            return ProductionPersistenceOwners.TRIGGERS;
        }
        if (relativePath.startsWith("assets/")) {
            return ProductionPersistenceOwners.FLOW_ASSETS;
        }
        return ProductionPersistenceOwners.STANDALONE_ROOT;
    }

    private static String owner(SnapshotManifest manifest, String relativePath) {
        return manifest.entries().stream()
            .filter(entry -> entry.relativePath().equals(relativePath))
            .findFirst().orElseThrow().owner();
    }

    private String snapshotId(String migrationId) {
        return SnapshotId.deterministic("offline-cli-test-" + migrationId).canonicalText();
    }

    private void writeAuthorityBundle(Path source, String migrationId, String catalogChecksum) {
        try {
            String defaultCatalog = "a".repeat(64);
            Path bundlePath = catalogChecksum.equals(defaultCatalog)
                ? ProductionAuthorityBundle.path(source)
                : source.resolveSibling("authority-bundle-" + catalogChecksum + ".json");
            String installAuthorityHash = CanonicalHash.rawSha256(
                Files.readAllBytes(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE)));
            KeyPair keyPair = signingKeys.computeIfAbsent(source.toAbsolutePath().normalize(), ignored -> {
                try {
                    return KeyPairGenerator.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generateKeyPair();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            String signingPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            ProductionAuthorityTrustAnchor trustAnchor = ProductionAuthorityTrustAnchor.pinned(testServerId(),
                testServerId().canonicalText(), installAuthorityHash,
                ProductionAuthorityTrustAnchor.fingerprint(signingPublicKey), signingPublicKey);
            Files.writeString(trustAnchorPath(source), trustAnchor.canonical(), StandardCharsets.UTF_8);
            if (!Files.exists(bundlePath)) {
                ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
                    signer(source, keyPair), trustAnchor, SnapshotId.parseCanonicalText(snapshotId(migrationId)), Instant.now(),
                    ContentHash.of(catalogChecksum), 1, new CatalogVersion(1, 0), ContentHash.of("c".repeat(64)), 1,
                    "d".repeat(64), 1);
                bundle.write(bundlePath);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed To Prepare Authority Bundle Fixture", exception);
        }
    }

    private void prepareAuthorityUseGrant(String[] args, OfflineUpgradeAdapterRegistry discovered) throws Exception {
        String grantArgument = argumentValue(args, "--authority-use-grant");
        if (grantArgument == null) {
            return;
        }
        Path grantPath = Path.of(grantArgument);
        if (Files.exists(grantPath)) {
            return;
        }
        Path source = Path.of(argumentValue(args, "--source")).toAbsolutePath().normalize();
        Path bundlePath = Path.of(argumentValue(args, "--authority-bundle"));
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.read(bundlePath);
        ProductionAuthorityTrustAnchor trustAnchor = ProductionAuthorityTrustAnchor.fromCanonical(
            Files.readString(Path.of(argumentValue(args, "--authority-trust-anchor")), StandardCharsets.UTF_8));
        List<String> preserve = argumentValues(args, "--preserve");
        OfflineUpgradeAdapterRegistry registry = effectiveRegistry(discovered, preserve);
        ProductionSnapshotMetadataManifest.Values productionMetadata;
        try {
            productionMetadata = ProductionSnapshotMetadataManifest.read(source);
        } catch (MigrationException exception) {
            return;
        }
        SnapshotMetadata metadata = productionMetadata.metadata();
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, metadata.formatVersion(),
            metadata.build(), Integer.parseInt(argumentValue(args, "--target-format")),
            argumentValue(args, "--replacement-contract"));
        Snapshot snapshot = new SnapshotService(new MigrationFence()).admitExported(source).snapshot();
        SnapshotManifest manifest = snapshot.manifest();
        String invocationHash = InvocationBinding.digest(invocationCanonical(args, metadata,
            ProductionAcceptedStagePublisher.CONTRACT_IDENTITY, window, preserve, registry, snapshot));
        String planPreimageHash;
        try {
            UpgradeProposal proposal = new StandaloneUpgradePlanner(registry).plan(snapshot, window);
            planPreimageHash = AuthorityUseGrant.planPreimageHash(
                proposal.plan().withInvocationHash(invocationHash).canonicalText());
        } catch (RuntimeException | IOException exception) {
            MigrationPlan fallbackPlan = new MigrationPlan(metadata.snapshotId(), manifest.manifestHash(),
                metadata.formatVersion(), window.targetFormatVersion(), QuarantineReport.empty().reportHash(), List.of())
                .withInvocationHash(invocationHash)
                .withSnapshotAdapterResultHash(SnapshotAdapterBinding.capture(snapshot, registry).bindingHash());
            planPreimageHash = AuthorityUseGrant.planPreimageHash(fallbackPlan.canonicalText());
        }
        String migrationId = argumentValue(args, "--migration-id");
        AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, trustAnchor, signer(source, signingKeys.get(source)), source,
            migrationId, invocationHash, planPreimageHash, Instant.now(), Instant.now().plusSeconds(3600),
            "offline-cli-test-" + migrationId);
        Files.writeString(grantPath, grant.canonical(), StandardCharsets.UTF_8);
    }

    private OfflineUpgradeAdapterRegistry effectiveRegistry(OfflineUpgradeAdapterRegistry discovered, List<String> preserve) {
        List<OfflineUpgradeAdapter> adapters = new ArrayList<>(discovered.adapters());
        if (!preserve.isEmpty()) {
            IdentityFileAdapter identity = new IdentityFileAdapter(preserve, "standalone-root");
            int index = -1;
            for (int candidate = 0; candidate < adapters.size(); candidate++) {
                if (IdentityFileAdapter.KEY.wireId().equals(adapters.get(candidate).wireId())) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                adapters.add(identity);
            } else {
                adapters.set(index, ((IdentityFileAdapter) adapters.get(index)).withAdditionalPaths(preserve));
            }
        }
        return new OfflineUpgradeAdapterRegistry(adapters, discovered.snapshotAdapters());
    }

    private String invocationCanonical(String[] args, SnapshotMetadata metadata, String publisherContractIdentity,
                                       UpgradeSourceWindow window,
                                       List<String> preserve, OfflineUpgradeAdapterRegistry registry,
                                       Snapshot snapshot) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", 1);
        value.put("sourceRoot", Path.of(argumentValue(args, "--source")).toAbsolutePath().normalize().toString());
        Path source = Path.of(argumentValue(args, "--source")).toAbsolutePath().normalize();
        Path control = argsContains(args, "--control")
            ? Path.of(argumentValue(args, "--control")).toAbsolutePath().normalize()
            : source.getParent().resolve(".resync-replacement-control");
        value.put("controlRoot", control.toString());
        value.put("persistenceCoordinationRoot", null);
        value.put("snapshotMetadata", Map.of(
            "formatVersion", metadata.formatVersion(),
            "snapshotId", metadata.snapshotId(),
            "createdAt", metadata.createdAt().toString(),
            "build", metadata.build(),
            "catalogChecksum", metadata.catalogChecksum(),
            "extensionVersions", metadata.extensionVersions()));
        value.put("publisherContractIdentity", publisherContractIdentity);
        value.put("sourceWindow", Map.of(
            "upgraderVersion", window.upgraderVersion().value(),
            "sourceFormatVersion", window.sourceFormatVersion(),
            "sourceBuild", window.sourceBuild(),
            "targetFormatVersion", window.targetFormatVersion(),
            "replacementContract", window.replacementContract()));
        value.put("migrationId", argumentValue(args, "--migration-id"));
        value.put("reservedBytes", argsContains(args, "--reserved-bytes")
            ? Long.parseLong(argumentValue(args, "--reserved-bytes")) : 0L);
        value.put("preservePaths", preserve.stream().sorted().toList());
        value.put("adapters", registry.adapters().stream()
            .sorted(Comparator.comparing((OfflineUpgradeAdapter adapter) -> adapter.wireId())
                .thenComparing(OfflineUpgradeAdapter::owner)
                .thenComparing(adapter -> adapter.getClass().getName()))
            .map(adapter -> Map.of("wireId", adapter.wireId(), "owner", adapter.owner(),
                "implementation", adapter.getClass().getName()))
            .toList());
        value.put("snapshotAdapters", registry.snapshotAdapters().stream()
            .sorted(Comparator.comparing((OfflineUpgradeSnapshotAdapter adapter) -> adapter.wireId())
                .thenComparing(OfflineUpgradeSnapshotAdapter::owner)
                .thenComparing(adapter -> adapter.getClass().getName()))
            .map(adapter -> Map.of("wireId", adapter.wireId(), "owner", adapter.owner(),
                "implementation", adapter.getClass().getName()))
            .toList());
        value.put("snapshotAdapterResultHash", SnapshotAdapterBinding.capture(snapshot, registry).bindingHash());
        return CanonicalJson.canonicalize(value);
    }

    private ProductionAuthoritySigner signer(Path source, KeyPair keyPair) {
        return new ProductionAuthoritySigner() {
            @Override
            public ServerId serverId() {
                return testServerId();
            }

            @Override
            public String installAuthorityHash() throws IOException {
                return CanonicalHash.rawSha256(Files.readAllBytes(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE)));
            }

            @Override
            public String signingPublicKey() {
                return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            }

            @Override
            public String sign(byte[] canonicalPayload) {
                try {
                    Signature signature = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
                    signature.initSign(keyPair.getPrivate());
                    signature.update(canonicalPayload);
                    return Base64.getEncoder().encodeToString(signature.sign());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private Path trustAnchorPath(Path source) {
        return source.resolveSibling("authority-trust-anchor-" + source.getFileName() + ".json");
    }

    private Path authorityUseGrantPath(Path source, String migrationId, String catalogChecksum) {
        return source.resolveSibling("authority-use-grant-" + source.getFileName() + "-" + migrationId + "-"
            + catalogChecksum + ".json");
    }

    private Path persistenceCoordinationRoot(Path source, String migrationId) {
        return source.resolveSibling(source.getFileName() + "-persistence-coordination-" + migrationId);
    }

    private static String argumentValue(String[] args, String option) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
    }

    private static List<String> argumentValues(String[] args, String option) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                values.add(args[++index]);
            }
        }
        return values;
    }

    private static boolean argsContains(String[] args, String option) {
        for (String value : args) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ServerId testServerId() {
        return ServerId.deterministic("offline-cli-test-server");
    }

    private Invocation invoke(String[] args, OfflineUpgradeAdapterRegistry registry) {
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        if (args.length > 0 && "apply".equals(args[0])
            && argsContains(args, "--authority-bundle")
            && argsContains(args, "--authority-trust-anchor")
            && argsContains(args, "--authority-use-grant")) {
            try {
                prepareAuthorityUseGrant(args, registry);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed To Prepare Authority Use Grant Fixture", exception);
            }
        }
        int code = OfflineUpgradeCli.run(args, new PrintWriter(output), new PrintWriter(errors), registry);
        return new Invocation(code, output.toString(), errors.toString());
    }

    private boolean jsonBoolean(String output, String field) {
        JsonValue.JsonObject value = (JsonValue.JsonObject) CanonicalJson.parseTree(output);
        return ((JsonValue.JsonBoolean) value.value(field)).value();
    }

    private record Invocation(int code, String output, String errors) {
    }

    private static class FixedAdapter implements OfflineUpgradeAdapter {
        private final AdapterKey key;
        private final String path;

        private FixedAdapter(String id, String path) {
            this.key = new AdapterKey(id, 1);
            this.path = path;
        }

        @Override
        public AdapterKey key() {
            return key;
        }

        @Override
        public String owner() {
            return "standalone-root";
        }

        @Override
        public boolean matches(String relativePath) {
            return path.equals(relativePath);
        }

        @Override
        public String targetPath(String relativePath) {
            return path;
        }

        @Override
        public TransformResult transform(String relativePath, byte[] sourceBytes) {
            return TransformResult.unchanged(sourceBytes);
        }
    }

    private static final class RelocatingAdapter extends FixedAdapter {
        private final String target;

        private RelocatingAdapter(String path, String target) {
            this("relocation", path, target);
        }

        private RelocatingAdapter(String id, String path, String target) {
            super(id, path);
            this.target = target;
        }

        @Override
        public String targetPath(String relativePath) {
            return target;
        }
    }
}
