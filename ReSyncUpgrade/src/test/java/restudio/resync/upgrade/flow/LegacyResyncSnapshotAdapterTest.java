package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.StandaloneUpgradePlanner;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyResyncSnapshotAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void composesGraphAndCommandBindingTransformsInOneSnapshotWindow() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path assets = Files.createDirectories(source.resolve("assets/Blueprints/NestedCommands"));
        Files.writeString(source.resolve("triggers.json"),
            "[{\"id\":\"main:command:start\",\"flowId\":\"main\",\"type\":\"COMMAND\",\"context\":\"{\\\"command\\\":\\\"main\\\"}\"}]",
            StandardCharsets.UTF_8);
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"command\",\"id\":\"main\",\"path\":\"Blueprints/NestedCommands\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("main.json"),
            "{\"id\":\"main\",\"resourceType\":\"command\",\"version\":1,\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputValues\":{}}},\"connections\":[]}",
            StandardCharsets.UTF_8);

        Snapshot snapshot = snapshot(source, "composition");
        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter().transform(input(snapshot));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        assertEquals(List.of("assets/Blueprints/NestedCommands/main.json", "triggers.json"),
            result.files().stream().map(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath).toList());
        assertTrue(new String(result.files().stream().filter(file -> file.sourcePath().endsWith("main.json")).findFirst().orElseThrow().bytes(), StandardCharsets.UTF_8)
            .contains("assetHash"));
        assertFalse(new String(result.files().stream().filter(file -> file.sourcePath().equals("triggers.json")).findFirst().orElseThrow().bytes(), StandardCharsets.UTF_8)
            .contains("main:command:start"));

        UpgradeProposal proposal = new StandaloneUpgradePlanner(new OfflineUpgradeAdapterRegistry(
            List.of(), List.of(new LegacyResyncSnapshotAdapter()))).plan(snapshot, LegacySnapshotWindow.sourceWindow());
        assertTrue(proposal.quarantineReport().records().isEmpty());
        assertEquals(List.of("assets/Blueprints/NestedCommands/main.json", "triggers.json"),
            proposal.plan().operations().stream().map(MigrationOperation::sourcePath).toList());
    }

    @Test
    void emitsIndependentValidGraphTransformsWhenAnotherGraphIsQuarantined() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("mixed"));
        Path assets = Files.createDirectories(source.resolve("assets/Arbitrary/Nested"));
        Files.writeString(assets.resolve("valid.json"),
            "{\"id\":\"valid\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("invalid.json"),
            "{\"id\":\"different\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);

        Snapshot snapshot = snapshot(source, "mixed");
        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyFlowGraphSnapshotAdapter().transform(input(snapshot));

        assertEquals(List.of("assets/Arbitrary/Nested/valid.json"),
            result.files().stream().map(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath).toList());
        assertEquals(1, result.quarantines().size());
        assertTrue(result.claimedPaths().contains("assets/Arbitrary/Nested/invalid.json"));
    }

    @Test
    void reanchorsManagedStoreOutputsAfterProviderAndDependencyQuarantine() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("managed-reanchor"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(flows.resolve("a.json"),
            "{\"id\":\"a\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{\"schedule\":{\"type\":\"schedule.interval\",\"inputValues\":{\"flow_id\":\"b\",\"seconds\":4}}},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("b.json"),
            "{\"id\":\"b\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"{runtime.path}\"}}},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("c.json"),
            "{\"id\":\"c\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, "managed-reanchor");
        String aPath = "assets/Blueprints/Flows/a.json";
        String bPath = "assets/Blueprints/Flows/b.json";
        String cPath = "assets/Blueprints/Flows/c.json";
        ManagedFlowFileMigrationProvider provider = new ManagedFlowFileMigrationProvider() {
            @Override
            public String id() {
                return "test.managed-flow-files";
            }

            @Override
            public Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                                  Collection<String> quarantinedGraphPaths) {
                Graph quarantinedGraph = graphs.stream().filter(graph -> graph.path().equals(bPath)).findFirst().orElseThrow();
                QuarantineRecord quarantine = new QuarantineRecord("provider-b", "MIGRATION.MANAGED_FLOW_FILE_PATH_TEMPLATE",
                    bPath, "The provider rejected graph B.", List.of(bPath), "Review the graph quarantine.",
                    ManagedFlowFileMigrationContract.sha256(quarantinedGraph.bytes()));
                Set<String> contributors = Set.of(aPath, bPath, cPath);
                List<OfflineUpgradeSnapshotAdapter.FileTransform> files = List.of(
                    new OfflineUpgradeSnapshotAdapter.FileTransform(aPath, "flow-files/managed-files.db",
                        "database".getBytes(StandardCharsets.UTF_8), MigrationOperationType.GENERATE),
                    new OfflineUpgradeSnapshotAdapter.FileTransform(aPath,
                        "flow-files/.migration/legacy-managed-flow-files.manifest.json",
                        "manifest".getBytes(StandardCharsets.UTF_8), MigrationOperationType.GENERATE),
                    new OfflineUpgradeSnapshotAdapter.FileTransform(aPath,
                        "flow-files/.migration/managed-flow-files.completion.json",
                        "completion".getBytes(StandardCharsets.UTF_8), MigrationOperationType.GENERATE));
                Map<String, Set<String>> generated = Map.of(
                    "flow-files/managed-files.db", contributors,
                    "flow-files/.migration/legacy-managed-flow-files.manifest.json", contributors,
                    "flow-files/.migration/managed-flow-files.completion.json", contributors);
                return new Result(files, List.of(), List.of(quarantine), generated);
            }
        };

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter(
            new LegacyFlowGraphSnapshotAdapter(), new restudio.resync.upgrade.command.LegacyCommandBindingSnapshotAdapter(),
            List.of(provider)).transform(input(snapshot));

        assertTrue(result.quarantines().stream().anyMatch(value -> value.sourceLocation().equals(bPath)));
        assertTrue(result.quarantines().stream().anyMatch(value -> value.sourceLocation().equals(aPath)
            && value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")));
        List<? extends OfflineUpgradeSnapshotAdapter.FileTransform> managed = result.files().stream()
            .filter(value -> value.targetPath().startsWith("flow-files/")).toList();
        assertEquals(3, managed.size());
        assertTrue(managed.stream().allMatch(value -> value.sourcePath().equals(cPath)), managed.toString());
        assertTrue(managed.stream().noneMatch(value -> value.sourcePath().equals(aPath) || value.sourcePath().equals(bPath)));
    }

    @Test
    void coordinatesManagedFileGraphsAcrossFlowFunctionAndCommandResources() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("managed-mixed-types"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Path functions = Files.createDirectories(source.resolve("assets/Blueprints/Functions"));
        Path commands = Files.createDirectories(source.resolve("assets/Blueprints/Commands"));
        Files.writeString(source.resolve("assets/flow.txt"), "flow", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("assets/function.txt"), "function", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("assets/command.txt"), "command", StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("flow.json"), managedGraph("flow", "assets/flow.txt"), StandardCharsets.UTF_8);
        Files.writeString(functions.resolve("function.json"), managedGraph("function", "assets/function.txt"), StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("command.json"), managedGraph("command", "assets/command.txt"), StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, "managed-mixed-types");
        Set<String> observedTypes = new LinkedHashSet<>();
        Set<String> contributors = Set.of(
            "assets/Blueprints/Commands/command.json",
            "assets/Blueprints/Flows/flow.json",
            "assets/Blueprints/Functions/function.json");
        ManagedFlowFileMigrationProvider provider = new ManagedFlowFileMigrationProvider() {
            @Override
            public String id() {
                return "test.managed-flow-files-mixed-types";
            }

            @Override
            public Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                                  Collection<String> quarantinedGraphPaths) {
                observedTypes.addAll(graphs.stream().map(Graph::type).toList());
                List<OfflineUpgradeSnapshotAdapter.FileTransform> files = List.of(
                    new OfflineUpgradeSnapshotAdapter.FileTransform("assets/Blueprints/Flows/flow.json",
                        "flow-files/managed-files.db", "database".getBytes(StandardCharsets.UTF_8), MigrationOperationType.GENERATE),
                    new OfflineUpgradeSnapshotAdapter.FileTransform("assets/Blueprints/Flows/flow.json",
                        "flow-files/.migration/legacy-managed-flow-files.manifest.json", "manifest".getBytes(StandardCharsets.UTF_8),
                        MigrationOperationType.GENERATE),
                    new OfflineUpgradeSnapshotAdapter.FileTransform("assets/Blueprints/Flows/flow.json",
                        "flow-files/.migration/managed-flow-files.completion.json", "completion".getBytes(StandardCharsets.UTF_8),
                        MigrationOperationType.GENERATE));
                Map<String, Set<String>> generated = Map.of(
                    "flow-files/managed-files.db", contributors,
                    "flow-files/.migration/legacy-managed-flow-files.manifest.json", contributors,
                    "flow-files/.migration/managed-flow-files.completion.json", contributors);
                return new Result(files, List.of(), List.of(), generated);
            }
        };

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter(
            new LegacyFlowGraphSnapshotAdapter(), new restudio.resync.upgrade.command.LegacyCommandBindingSnapshotAdapter(),
            List.of(provider)).transform(input(snapshot));

        assertEquals(Set.of("flow", "function", "command"), observedTypes);
        List<? extends OfflineUpgradeSnapshotAdapter.FileTransform> managed = result.files().stream()
            .filter(value -> value.targetPath().startsWith("flow-files/")).toList();
        assertEquals(3, managed.size(), managed.toString());
        assertTrue(managed.stream().allMatch(value -> value.sourcePath().equals("assets/Blueprints/Flows/flow.json")), managed.toString());
    }

    @Test
    void removesFlowConversionWhenCommandSemanticsQuarantineTheSameGraph() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("semantic-failure"));
        Path assets = Files.createDirectories(source.resolve("assets/Blueprints/Commands"));
        Files.writeString(source.resolve("triggers.json"),
            "[{\"id\":\"broken:command:start\",\"flowId\":\"broken\",\"type\":\"COMMAND\",\"context\":\"broken\"}]",
            StandardCharsets.UTF_8);
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"command\",\"id\":\"broken\",\"path\":\"Blueprints/Commands\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("broken.json"),
            "{\"id\":\"broken\",\"resourceType\":\"command\",\"version\":1,\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputValues\":{\"command\":5}}},\"connections\":[]}",
            StandardCharsets.UTF_8);

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter().transform(input(snapshot(source, "semantic-failure")));

        assertTrue(result.files().stream().noneMatch(file -> file.sourcePath().equals("assets/Blueprints/Commands/broken.json")));
        assertTrue(result.quarantines().stream().anyMatch(quarantine ->
            quarantine.sourceLocation().equals("assets/Blueprints/Commands/broken.json")));
    }

    @Test
    void discoversExplicitFunctionIdentityButDoesNotClaimShapeOnlyJson() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("identity-discovery"));
        Path assets = Files.createDirectories(source.resolve("assets/Arbitrary/Nested"));
        Files.writeString(assets.resolve("function.json"),
            "{\"id\":\"function\",\"function\":true,\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("unrelated.json"),
            "{\"nodes\":{},\"connections\":[],\"editor\":true}", StandardCharsets.UTF_8);

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyFlowGraphSnapshotAdapter()
            .transform(input(snapshot(source, "identity-discovery")));

        assertEquals(List.of("assets/Arbitrary/Nested/function.json"), result.files().stream()
            .map(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath).toList());
        assertFalse(result.claimedPaths().contains("assets/Arbitrary/Nested/unrelated.json"));
    }

    @Test
    void quarantinesMalformedProjectMetadataWithoutIdentityFallback() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("invalid-project"));
        Path assets = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(source.resolve("assets/project.json"), "{\"resources\":{}}", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("flow.json"),
            "{\"id\":\"flow\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);

        Snapshot snapshot = snapshot(source, "invalid-project");
        OfflineUpgradeSnapshotInput snapshotInput = input(snapshot);
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        assertTrue(adapter.claims(snapshotInput));
        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter.transform(snapshotInput);

        assertTrue(result.files().stream().noneMatch(file -> file.sourcePath().equals("assets/Blueprints/Flows/flow.json")));
        assertTrue(result.quarantines().stream().anyMatch(quarantine ->
            quarantine.sourceLocation().equals("assets/project.json")));

        UpgradeProposal proposal = new StandaloneUpgradePlanner(new OfflineUpgradeAdapterRegistry(
            List.of(), List.of(adapter))).plan(snapshot, LegacySnapshotWindow.sourceWindow());
        assertTrue(proposal.quarantineReport().records().stream().anyMatch(quarantine ->
            quarantine.code().equals("MIGRATION.FLOW_PROJECT_METADATA_INVALID")
                && quarantine.sourceLocation().equals("assets/project.json")));
    }

    @Test
    void repairsMissingIdentityOnCurrentGraphAndIsStableOnSecondRun() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        byte[] first = adapter.transformGraph(
            "{\"id\":\"current\",\"resourceType\":\"flow\",\"version\":2,\"nodes\":{},\"connections\":[]}".getBytes(StandardCharsets.UTF_8),
            "flow");
        byte[] second = adapter.transformGraph(first, "flow");

        assertTrue(new String(first, StandardCharsets.UTF_8).contains("assetHash"));
        assertTrue(Arrays.equals(first, second));
    }

    @Test
    void recoversFunctionClassificationFromAValidTypedAutomationBackup() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("partial-recovery"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"flow\",\"id\":\"partial\",\"path\":\"Blueprints/Flows\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("partial.json"),
            "{\"id\":\"partial\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        writeBackup(source, "typed-automation-1", "partial", functionBackup("partial", "partial"));

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter()
            .transform(input(snapshot(source, "partial-recovery")));

        assertTrue(result.quarantines().isEmpty(), result.quarantines().toString());
        OfflineUpgradeSnapshotAdapter.FileTransform graph = result.files().stream()
            .filter(file -> file.targetPath().equals("assets/Blueprints/Functions/partial.json"))
            .findFirst().orElseThrow();
        assertEquals("assets/Blueprints/Flows/partial.json", graph.sourcePath());
        assertTrue(new String(graph.bytes(), StandardCharsets.UTF_8).contains("\"resourceType\":\"function\""));
        assertTrue(result.files().stream().anyMatch(file -> file.targetPath().equals("assets/project.json")));
        assertTrue(result.files().stream().noneMatch(file -> file.sourcePath().contains("migration-backups")));
        assertTrue(result.claimedPaths().stream().noneMatch(path -> path.contains("migration-backups")));
    }

    @Test
    void rejectsTheNewestMalformedBackupInsteadOfFallingBackToAnOlderEnvelope() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("partial-recovery-order"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"flow\",\"id\":\"partial\",\"path\":\"Blueprints/Flows\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("partial.json"),
            "{\"id\":\"partial\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        writeBackup(source, "typed-automation-1", "partial", functionBackup("partial", "older"));
        writeBackup(source, "typed-automation-2", "partial", functionBackup("other", "newer"));

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter()
            .transform(input(snapshot(source, "partial-recovery-order")));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_BACKUP_MISMATCH")
                && value.sourceLocation().equals("assets/Blueprints/Flows/partial.json")), result.quarantines().toString());
        assertTrue(Files.exists(source.resolve("assets/migration-backups/typed-automation-1/partial.json")));
        assertTrue(Files.exists(source.resolve("assets/migration-backups/typed-automation-2/partial.json")));
    }

    @Test
    void quarantinesConflictingBackupsInTheSameMigrationEnvelope() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("partial-recovery-conflict"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"flow\",\"id\":\"partial\",\"path\":\"Blueprints/Flows\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("partial.json"),
            "{\"id\":\"partial\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        writeBackup(source, "typed-automation-conflict", "partial", functionBackup("partial", "first"));
        writeBackup(source, "typed-automation-conflict/function", "partial", functionBackup("partial", "second"));

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter()
            .transform(input(snapshot(source, "partial-recovery-conflict")));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_BACKUP_AMBIGUOUS")
                && value.sourceLocation().equals("assets/Blueprints/Flows/partial.json")), result.quarantines().toString());
    }

    @Test
    void quarantinesMalformedBackupEnvelopeWhilePreservingItsBytes() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("partial-recovery-malformed"));
        Path flows = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        Files.writeString(source.resolve("assets/project.json"),
            "{\"resources\":[{\"type\":\"flow\",\"id\":\"partial\",\"path\":\"Blueprints/Flows\"}]}",
            StandardCharsets.UTF_8);
        Files.writeString(flows.resolve("partial.json"),
            "{\"id\":\"partial\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{},\"connections\":[]}",
            StandardCharsets.UTF_8);
        byte[] malformed = functionBackup("partial", "malformed");
        String malformedText = new String(malformed, StandardCharsets.UTF_8)
            .replaceFirst("\\\"assetHash\\\":\\\"[0-9a-f]+\\\"", "\\\"assetHash\\\":\\\"" + "0".repeat(64) + "\\\"");
        writeBackup(source, "typed-automation-malformed", "partial", malformedText.getBytes(StandardCharsets.UTF_8));
        byte[] preserved = Files.readAllBytes(source.resolve("assets/migration-backups/typed-automation-malformed/partial.json"));

        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = new LegacyResyncSnapshotAdapter()
            .transform(input(snapshot(source, "partial-recovery-malformed")));

        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.TYPED_AUTOMATION_BACKUP_INVALID")
                && value.sourceLocation().equals("assets/Blueprints/Flows/partial.json")), result.quarantines().toString());
        assertArrayEquals(preserved, Files.readAllBytes(source.resolve("assets/migration-backups/typed-automation-malformed/partial.json")));
    }

    @Test
    void treatsTypedAutomationBackupsAsProvenanceOnlyForClaimsAndOwnership() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("backup-provenance"));
        writeBackup(source, "typed-automation-only", "orphan", functionBackup("orphan", "orphan"));
        Snapshot snapshot = snapshot(source, "backup-provenance");
        OfflineUpgradeSnapshotInput input = input(snapshot);
        LegacyResyncSnapshotAdapter adapter = new LegacyResyncSnapshotAdapter();
        String backupPath = "assets/migration-backups/typed-automation-only/orphan.json";

        assertFalse(new LegacyFlowGraphSnapshotAdapter().claims(input));
        assertFalse(adapter.owns(backupPath, ProductionPersistenceOwners.FLOW_ASSETS));
        OfflineUpgradeSnapshotAdapter.SnapshotTransform result = adapter.transform(input);
        assertTrue(result.files().isEmpty(), result.files().toString());
        assertTrue(result.claimedPaths().stream().noneMatch(path -> path.contains("migration-backups")), result.claimedPaths().toString());
    }

    private void writeBackup(Path source, String migrationId, String id, byte[] bytes) throws Exception {
        Path path = source.resolve("assets/migration-backups").resolve(migrationId).resolve(id + ".json");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private byte[] functionBackup(String id, String description) {
        String source = "{\"id\":\"" + id + "\",\"resourceType\":\"function\",\"version\":1,"
            + "\"functionDescription\":\"" + description + "\",\"nodes\":{},\"connections\":[]}";
        return new LegacyFlowGraphSnapshotAdapter().transformGraph(source.getBytes(StandardCharsets.UTF_8), "function");
    }

    private String managedGraph(String type, String path) {
        return "{\"id\":\"" + type + "\",\"resourceType\":\"" + type
            + "\",\"version\":1,\"nodes\":{\"read\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\""
            + path + "\"}}},\"connections\":[]}";
    }

    private Snapshot snapshot(Path root, String id) throws Exception {
        Path triggers = root.resolve("triggers.json");
        if (Files.notExists(triggers)) {
            Files.writeString(triggers, "[]", StandardCharsets.UTF_8);
        }
        SnapshotMetadata metadata = new SnapshotMetadata(1, id, Instant.EPOCH,
            LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, root.resolve("assets")));
        participants.register(participant(ProductionPersistenceOwners.TRIGGERS, root.resolve("triggers.json")));
        SnapshotManifest manifest = SnapshotManifest.scan(root, metadata, participants);
        Path manifestPath = root.resolveSibling(root.getFileName() + ".manifest");
        manifest.write(manifestPath);
        ProductionSnapshotMetadataManifest.write(root, manifest);
        SnapshotVerification verification = manifest.verify(root);
        verification.requireVerified();
        return new Snapshot(root, manifestPath, root.resolveSibling(root.getFileName() + ".state"), metadata,
            manifest, SnapshotState.VERIFIED, verification);
    }

    private OfflineUpgradeSnapshotInput input(Snapshot snapshot) throws Exception {
        return new OfflineUpgradeSnapshotInput(snapshot.root(), ImmutableSnapshotAdapter.adapt(snapshot));
    }

    private PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }
}
