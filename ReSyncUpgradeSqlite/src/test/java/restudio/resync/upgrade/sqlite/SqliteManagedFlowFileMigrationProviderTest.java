package restudio.resync.upgrade.sqlite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.ManagedFlowFileLegacyOwnershipManifest;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.flow.LegacyResyncSnapshotAdapter;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProviders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqliteManagedFlowFileMigrationProviderTest {
    @TempDir
    Path temporary;

    @Test
    void emitsZeroEntryMigrationWithRootAndCanonicalRecords() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("zero"));
        byte[] graph = graph("zero", "{}").getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/zero.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph, List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"));

        SqliteManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();
        ManagedFlowFileMigrationProvider.Result result = provider.produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/zero.json", "flow", "zero", graph)), Set.of());
        ManagedFlowFileMigrationProvider.Result second = provider.produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/zero.json", "flow", "zero", graph)), Set.of());

        assertEquals(3, result.files().size());
        assertEquals(HexFormat.of().formatHex(result.files().stream()
            .filter(value -> value.targetPath().equals("flow-files/managed-files.db")).findFirst().orElseThrow().bytes()),
            HexFormat.of().formatHex(second.files().stream()
                .filter(value -> value.targetPath().equals("flow-files/managed-files.db")).findFirst().orElseThrow().bytes()));
        byte[] manifestBytes = result.files().stream().filter(value -> value.targetPath().endsWith("manifest.json"))
            .findFirst().orElseThrow().bytes();
        assertEquals(0, ManagedFlowFileLegacyOwnershipManifest.read(manifestBytes).entryCount());
        assertTrue(result.files().stream().allMatch(value -> value.operationType().name().equals("GENERATE")));
    }

    @Test
    void emitsExplicitEligibleZeroEntryMigrationWithoutAnyGraph() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("empty-eligible"));
        Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        OfflineUpgradeSnapshotInput input = emptyInput(source);
        SqliteManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();

        ManagedFlowFileMigrationProvider.Result first = provider.produce(input, List.of(), Set.of());

        assertEquals(3, first.files().size());
        assertEquals(0, ManagedFlowFileLegacyOwnershipManifest.read(first.files().stream()
            .filter(value -> value.targetPath().endsWith("manifest.json")).findFirst().orElseThrow().bytes()).entryCount());
        materialize(source, first);
        ManagedFlowFileMigrationProvider.Result second = provider.produce(input, List.of(), Set.of());
        assertTrue(second.files().isEmpty(), second.toString());
        assertTrue(second.quarantines().isEmpty(), second.toString());
    }

    @Test
    void migratesStaticFilesFromFlowFunctionAndCommandGraphs() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("mixed-types"));
        List<String> types = List.of("flow", "function", "command");
        List<ManagedFlowFileMigrationProvider.Graph> graphs = new ArrayList<>();
        List<ImmutableSnapshotAdapter.Entry> entries = new ArrayList<>();
        List<String> directories = new ArrayList<>(List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows",
            "assets/Blueprints/Functions", "assets/Blueprints/Commands"));
        for (String type : types) {
            String logicalPath = type + "/value.txt";
            byte[] content = (type + "-bytes").getBytes(StandardCharsets.UTF_8);
            Path file = source.resolve(logicalPath);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            directories.add(type);
            entries.add(new ImmutableSnapshotAdapter.Entry(logicalPath, content.length,
                ManagedFlowFileMigrationContract.sha256(content), ProductionPersistenceOwners.STANDALONE_ROOT));
            String graphPath = "assets/Blueprints/" + switch (type) {
                case "flow" -> "Flows/";
                case "function" -> "Functions/";
                case "command" -> "Commands/";
                default -> throw new IllegalStateException(type);
            } + type + ".json";
            byte[] graph = typedGraph(type, type, "{\"node\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\""
                + logicalPath + "\"}}}").getBytes(StandardCharsets.UTF_8);
            Path graphFile = source.resolve(graphPath);
            Files.createDirectories(graphFile.getParent());
            Files.write(graphFile, graph);
            graphs.add(new ManagedFlowFileMigrationProvider.Graph(graphPath, type, type, graph));
        }
        OfflineUpgradeSnapshotInput input = input(source, source.resolve("assets/Blueprints/Flows/flow.json"),
            Files.readAllBytes(source.resolve("assets/Blueprints/Flows/flow.json")), directories,
            entries.toArray(ImmutableSnapshotAdapter.Entry[]::new));

        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider()
            .produce(input, graphs, Set.of(), true);

        ManagedFlowFileLegacyOwnershipManifest manifest = ManagedFlowFileLegacyOwnershipManifest.read(result.files().stream()
            .filter(value -> value.targetPath().endsWith("manifest.json")).findFirst().orElseThrow().bytes());
        assertEquals(List.of("command", "command/value.txt", "flow", "flow/value.txt", "function", "function/value.txt"), manifest.entries().stream()
            .map(ManagedFlowFileMigrationContract.LegacyEntry::logicalPath).toList());
        assertEquals(Set.of("assets/Blueprints/Commands/command.json", "assets/Blueprints/Flows/flow.json",
            "assets/Blueprints/Functions/function.json"), result.generatedContributors().values().stream()
            .flatMap(Collection::stream).collect(Collectors.toSet()));
    }

    @Test
    void quarantinesDynamicAndConnectedPathsInEverySupportedGraphType() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("mixed-quarantine"));
        OfflineUpgradeSnapshotInput input = input(source, source.resolve("assets/Blueprints/Flows/flow.json"),
            typedGraph("flow", "flow", "{}").getBytes(StandardCharsets.UTF_8),
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"));
        SqliteManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();
        for (String type : List.of("flow", "function", "command")) {
            byte[] dynamic = typedGraph(type, type, "{\"node\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"{runtime.path}\"}}}")
                .getBytes(StandardCharsets.UTF_8);
            ManagedFlowFileMigrationProvider.Result dynamicResult = provider.produce(input,
                List.of(new ManagedFlowFileMigrationProvider.Graph(type + ".json", type, type, dynamic)), Set.of(), true);
            assertTrue(dynamicResult.quarantines().stream().anyMatch(value ->
                value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_TEMPLATE")), type + dynamicResult);

            byte[] connected = typedGraph(type, type, "{\"node\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"value.txt\"}}}",
                "[{\"targetNodeId\":\"node\",\"targetPin\":\"path\"}]").getBytes(StandardCharsets.UTF_8);
            ManagedFlowFileMigrationProvider.Result connectedResult = provider.produce(input,
                List.of(new ManagedFlowFileMigrationProvider.Graph(type + "-connected.json", type, type, connected)), Set.of(), true);
            assertTrue(connectedResult.quarantines().stream().anyMatch(value ->
                value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_CONNECTED")), type + connectedResult);
        }
    }

    @Test
    void ignoresForeignFileLikeOperationAndRejectsMismatchedCanonicalOperation() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("canonical-node"));
        byte[] content = "legacy".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("data.txt"), content);
        byte[] foreign = graph("foreign", "{\"node\":{\"type\":\"foreign.file\",\"handlerConfig\":{\"operation\":\"file_write\"},\"inputValues\":{\"path\":\"data.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/foreign.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, foreign);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, foreign,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("data.txt", content.length,
                ManagedFlowFileMigrationContract.sha256(content), ProductionPersistenceOwners.STANDALONE_ROOT));
        SqliteManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();
        ManagedFlowFileMigrationProvider.Result untouched = provider.produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/foreign.json", "flow",
                "foreign", foreign)), Set.of());
        assertTrue(untouched.quarantines().isEmpty(), untouched.toString());
        assertEquals(0, ManagedFlowFileLegacyOwnershipManifest.read(untouched.files().stream()
            .filter(value -> value.targetPath().endsWith("manifest.json")).findFirst().orElseThrow().bytes()).entryCount());

        byte[] mismatched = graph("mismatched", "{\"node\":{\"type\":\"file.write\",\"handlerConfig\":{\"operation\":\"file_delete\"},\"inputValues\":{\"path\":\"data.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        ManagedFlowFileMigrationProvider.Result rejected = provider.produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/mismatched.json", "flow",
                "mismatched", mismatched)), Set.of());
        assertTrue(rejected.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_GRAPH_INVALID")), rejected.toString());
    }

    @Test
    void ignoresUnknownGraphResourceTypesEvenWhenTheyContainFileLikeNodes() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("unknown-type"));
        byte[] graph = typedGraph("unknown", "foreign", "{\"node\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"data.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        OfflineUpgradeSnapshotInput input = input(source, source.resolve("foreign.json"), graph, List.of());

        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("foreign.json", "foreign", "unknown", graph)), Set.of());

        assertTrue(result.files().isEmpty(), result.toString());
        assertTrue(result.quarantines().isEmpty(), result.toString());
    }

    @Test
    void migratesStaticFileAndQuarantinesConnectedPath() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("file"));
        byte[] content = "legacy".getBytes(StandardCharsets.UTF_8);
        Path file = source.resolve("data.txt");
        Files.write(file, content);
        byte[] graph = graph("file", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"data.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/file.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph, List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("data.txt", content.length, ManagedFlowFileMigrationContract.sha256(content),
                ProductionPersistenceOwners.STANDALONE_ROOT));

        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/file.json", "flow", "file", graph)), Set.of());
        ManagedFlowFileLegacyOwnershipManifest manifest = ManagedFlowFileLegacyOwnershipManifest.read(result.files().stream()
            .filter(value -> value.targetPath().endsWith("manifest.json")).findFirst().orElseThrow().bytes());
        assertEquals(List.of("data.txt"), manifest.entries().stream().map(ManagedFlowFileMigrationContract.LegacyEntry::logicalPath).toList());
        ManagedFlowFileMigrationProvider.Result duplicate = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/file.json", "flow", "file", graph),
                new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/file-copy.json", "flow", "file-copy",
                    graph("file-copy", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"data.txt\"}}}")
                        .getBytes(StandardCharsets.UTF_8))), Set.of());
        assertTrue(duplicate.quarantines().isEmpty(), duplicate.toString());

        byte[] connected = graph("connected", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"data.txt\"}}}",
            "[{\"targetNodeId\":\"write\",\"targetPin\":\"path\"}]")
            .getBytes(StandardCharsets.UTF_8);
        ManagedFlowFileMigrationProvider.Result quarantined = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/connected.json", "flow", "connected", connected)), Set.of());
        assertTrue(quarantined.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_CONNECTED")));
    }

    @Test
    void emitsParentsAndMultipleStaticFilesWithoutChangingLegacyBytes() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("multiple"));
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.resolve("nested"));
        Files.write(source.resolve("nested/first.txt"), first);
        Files.write(source.resolve("nested/second.txt"), second);
        byte[] unreferenced = "unreferenced".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("unreferenced.txt"), unreferenced);
        byte[] graph = graph("multiple", "{\"first\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"nested/first.txt\"}},"
            + "\"second\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"nested/second.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/multiple.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows", "nested"),
            new ImmutableSnapshotAdapter.Entry("nested/first.txt", first.length,
                ManagedFlowFileMigrationContract.sha256(first), ProductionPersistenceOwners.STANDALONE_ROOT),
            new ImmutableSnapshotAdapter.Entry("nested/second.txt", second.length,
                ManagedFlowFileMigrationContract.sha256(second), ProductionPersistenceOwners.STANDALONE_ROOT));

        byte[] beforeFirst = Files.readAllBytes(source.resolve("nested/first.txt"));
        byte[] beforeSecond = Files.readAllBytes(source.resolve("nested/second.txt"));
        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/multiple.json", "flow",
                "multiple", graph)), Set.of());

        ManagedFlowFileLegacyOwnershipManifest manifest = ManagedFlowFileLegacyOwnershipManifest.read(result.files().stream()
            .filter(value -> value.targetPath().endsWith("manifest.json")).findFirst().orElseThrow().bytes());
        assertEquals(List.of("nested", "nested/first.txt", "nested/second.txt"), manifest.entries().stream()
            .map(ManagedFlowFileMigrationContract.LegacyEntry::logicalPath).toList());
        byte[] database = result.files().stream().filter(value -> value.targetPath().endsWith("managed-files.db"))
            .findFirst().orElseThrow().bytes();
        Path databasePath = temporary.resolve("multiple-output.db");
        Files.write(databasePath, database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.prepareStatement("SELECT path, kind, content FROM managed_files ORDER BY path");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("", rows.getString(1));
            assertEquals("DIRECTORY", rows.getString(2));
            assertEquals(0, rows.getBytes(3).length);
            assertTrue(rows.next());
            assertEquals("nested", rows.getString(1));
            assertEquals("DIRECTORY", rows.getString(2));
            assertTrue(rows.next());
            assertEquals("nested/first.txt", rows.getString(1));
            assertEquals("FILE", rows.getString(2));
            assertArrayEquals(first, rows.getBytes(3));
            assertTrue(rows.next());
            assertEquals("nested/second.txt", rows.getString(1));
            assertEquals("FILE", rows.getString(2));
            assertArrayEquals(second, rows.getBytes(3));
            assertTrue(!rows.next());
        }
        assertArrayEquals(beforeFirst, Files.readAllBytes(source.resolve("nested/first.txt")));
        assertArrayEquals(beforeSecond, Files.readAllBytes(source.resolve("nested/second.txt")));
        assertArrayEquals(unreferenced, Files.readAllBytes(source.resolve("unreferenced.txt")));
    }

    @Test
    void quarantinesUnsafeParticipantOwnedAndTamperedSources() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("rejections"));
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("owned.txt"), "tampered".getBytes(StandardCharsets.UTF_8));
        byte[] graph = graph("rejections", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"owned.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/rejections.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("owned.txt", original.length,
                ManagedFlowFileMigrationContract.sha256(original), ProductionPersistenceOwners.STANDALONE_ROOT));
        ManagedFlowFileMigrationProvider.Result tampered = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/rejections.json", "flow",
                "rejections", graph)), Set.of());
        assertTrue(tampered.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_SOURCE_TAMPERED")));
        assertTrue(tampered.files().isEmpty(), tampered.toString());
        assertArrayEquals("tampered".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source.resolve("owned.txt")));

        byte[] participantGraph = graph("participant", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"owned.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        OfflineUpgradeSnapshotInput participantInput = input(source, graphPath, participantGraph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("owned.txt", "tampered".getBytes(StandardCharsets.UTF_8).length,
                ManagedFlowFileMigrationContract.sha256("tampered".getBytes(StandardCharsets.UTF_8)), "other.persistence"));
        ManagedFlowFileMigrationProvider.Result participant = new SqliteManagedFlowFileMigrationProvider().produce(participantInput,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/participant.json", "flow",
                "participant", participantGraph)), Set.of());
        assertTrue(participant.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PARTICIPANT_OWNERSHIP")));

        byte[] unsafe = graph("unsafe", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"../escape\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        ManagedFlowFileMigrationProvider.Result unsafeResult = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/unsafe.json", "flow", "unsafe", unsafe)), Set.of());
        assertTrue(unsafeResult.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_UNSAFE")));
    }

    @Test
    void quarantinesSymlinkSourceEscape() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("symlink"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(source.resolve("linked.txt"), outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable");
        }
        byte[] graph = graph("symlink", "{\"read\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"linked.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/symlink.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("linked.txt", outside.toFile().length(),
                ManagedFlowFileMigrationContract.sha256(Files.readAllBytes(outside)),
                ProductionPersistenceOwners.STANDALONE_ROOT));
        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/symlink.json", "flow",
                "symlink", graph)), Set.of());
        assertTrue(result.files().isEmpty(), result.toString());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_SOURCE_SYMLINK")), result.toString());
    }

    @Test
    void quarantineDoesNotClaimZeroEntryWhenAFileNodeIsRejected() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("accepted-quarantine"));
        byte[] graph = graph("accepted", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"{runtime.path}\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/accepted.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"));

        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(new ManagedFlowFileMigrationProvider.Graph("assets/Blueprints/Flows/accepted.json", "flow",
                "accepted", graph)), Set.of());
        assertTrue(result.quarantines().stream().anyMatch(value ->
            value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_TEMPLATE")));
        assertTrue(result.files().isEmpty(), result.toString());
    }

    @Test
    void existingPendingMigrationIsAnIdempotentZeroOperation() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("idempotent"));
        byte[] graph = graph("idempotent", "{}").getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/idempotent.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
            List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"));
        ManagedFlowFileMigrationProvider.Graph graphInput = new ManagedFlowFileMigrationProvider.Graph(
            "assets/Blueprints/Flows/idempotent.json", "flow", "idempotent", graph);
        ManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();
        ManagedFlowFileMigrationProvider.Result first = provider.produce(input, List.of(graphInput), Set.of());
        for (var file : first.files()) {
            Path target = source.resolve(file.targetPath());
            Files.createDirectories(target.getParent());
            Files.write(target, file.bytes());
        }
        Path database = source.resolve("flow-files/managed-files.db");
        ManagedFlowFileLegacyOwnershipManifest ownership = ManagedFlowFileLegacyOwnershipManifest.read(
            Files.readAllBytes(source.resolve("flow-files/.migration/legacy-managed-flow-files.manifest.json")));
        var completion = restudio.resync.migration.ManagedFlowFileMigrationCompletion.read(
            Files.readAllBytes(source.resolve("flow-files/.migration/managed-flow-files.completion.json")));
        assertEquals(completion.manifestHash(), ownership.manifestHash());
        assertEquals(completion.physicalDatabaseHash(), ManagedFlowFileMigrationContract.sha256(Files.readAllBytes(database)));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("SELECT schema_id, format_version, writer_id, writer_version, origin, contract_version, install_identity, source_snapshot_identity, source_install_identity, source_archive_identity, migration_id, completion_authority_hash, completion_hash FROM managed_flow_file_store_metadata WHERE id = 1");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            var metadata = new ManagedFlowFileMigrationContract.StoreMetadata(rows.getString(1), rows.getInt(2),
                rows.getString(3), rows.getInt(4), ManagedFlowFileMigrationContract.Origin.parse(rows.getString(5)),
                rows.getInt(6), rows.getString(7), rows.getString(8), rows.getString(9), rows.getString(10),
                rows.getString(11), rows.getString(12), rows.getString(13));
            assertEquals("offline-migration-pending", metadata.origin().wireValue());
            assertTrue(completion.matchesPending(ownership, metadata));
        }
        assertEquals(completion.source(), ownership.source());
        try (var children = Files.list(source.resolve("flow-files/.migration"))) {
            assertEquals(List.of("legacy-managed-flow-files.manifest.json", "managed-flow-files.completion.json"),
                children.map(value -> value.getFileName().toString()).sorted().toList());
        }
        assertTrue(Files.isRegularFile(source.resolve("flow-files/managed-files.db")));
        assertTrue(Files.isRegularFile(source.resolve("flow-files/.migration/legacy-managed-flow-files.manifest.json")));
        assertTrue(Files.isRegularFile(source.resolve("flow-files/.migration/managed-flow-files.completion.json")));
        ManagedFlowFileMigrationProvider.Result second = provider.produce(input, List.of(graphInput), Set.of());
        assertTrue(second.files().isEmpty(), second.toString());
        assertTrue(second.quarantines().isEmpty(), second.toString());
        assertEquals(List.of("flow-files/managed-files.db", "flow-files/.migration/legacy-managed-flow-files.manifest.json",
            "flow-files/.migration/managed-flow-files.completion.json"), second.claimedPaths());
    }

    @Test
    void quarantinesManifestCompletionAndDatabaseTampering() throws Exception {
        for (String target : List.of("manifest", "completion", "database")) {
            Path source = Files.createDirectories(temporary.resolve("target-tamper-" + target));
            byte[] graph = graph(target, "{}").getBytes(StandardCharsets.UTF_8);
            Path graphPath = source.resolve("assets/Blueprints/Flows/" + target + ".json");
            Files.createDirectories(graphPath.getParent());
            Files.write(graphPath, graph);
            OfflineUpgradeSnapshotInput input = input(source, graphPath, graph,
                List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"));
            ManagedFlowFileMigrationProvider.Graph graphInput = new ManagedFlowFileMigrationProvider.Graph(
                "assets/Blueprints/Flows/" + target + ".json", "flow", target, graph);
            ManagedFlowFileMigrationProvider provider = new SqliteManagedFlowFileMigrationProvider();
            materialize(source, provider.produce(input, List.of(graphInput), Set.of()));
            Path tampered = switch (target) {
                case "manifest" -> source.resolve("flow-files/.migration/legacy-managed-flow-files.manifest.json");
                case "completion" -> source.resolve("flow-files/.migration/managed-flow-files.completion.json");
                default -> source.resolve("flow-files/managed-files.db");
            };
            Files.write(tampered, new byte[] {0}, StandardOpenOption.APPEND);
            ManagedFlowFileMigrationProvider.Result result = provider.produce(input, List.of(graphInput), Set.of());
            assertTrue(result.files().isEmpty(), result.toString());
            assertTrue(result.quarantines().stream().anyMatch(value ->
                value.code().equals("MIGRATION.MANAGED_FLOW_FILE_TARGET_TAMPERED")), result.toString());
        }
    }

    @Test
    void coordinatesThroughTheSingleLegacyGraphOwner() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("owner"));
        byte[] content = "legacy".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("data.txt"), content);
        byte[] graph = graph("owner", "{\"write\":{\"type\":\"file.write\",\"inputValues\":{\"path\":\"data.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/owner.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        OfflineUpgradeSnapshotInput input = input(source, graphPath, graph, List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows"),
            new ImmutableSnapshotAdapter.Entry("data.txt", content.length, ManagedFlowFileMigrationContract.sha256(content),
                ProductionPersistenceOwners.STANDALONE_ROOT));

        assertEquals(SqliteManagedFlowFileMigrationProvider.PROVIDER_ID,
            ManagedFlowFileMigrationProviders.discover().getFirst().id());
        var result = new LegacyResyncSnapshotAdapter().transform(input);

        assertTrue(result.files().stream().anyMatch(value -> value.targetPath().equals("flow-files/managed-files.db")),
            result.files().toString() + " / " + result.quarantines());
        assertTrue(result.files().stream().anyMatch(value -> value.targetPath().endsWith("managed-flow-files.completion.json")),
            result.files().toString() + " / " + result.quarantines());
    }

    @Test
    void rebuildsManagedStoreAfterDependencyClosureWithoutQuarantinedGraphBytes() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("dependency-finalization"));
        byte[] aBytes = "a-only".getBytes(StandardCharsets.UTF_8);
        byte[] cBytes = "c-survivor".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("a.txt"), aBytes);
        Files.write(source.resolve("c.txt"), cBytes);
        Path graphRoot = Files.createDirectories(source.resolve("assets/Blueprints/Flows"));
        byte[] aGraph = typedGraph("a", "flow", "{\"write\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"a.txt\"}},"
            + "\"schedule\":{\"type\":\"schedule.interval\",\"inputValues\":{\"flow_id\":\"b\",\"seconds\":4}}}")
            .getBytes(StandardCharsets.UTF_8);
        byte[] bGraph = graph("b", "{\"write\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"{runtime.path}\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        byte[] cGraph = graph("c", "{\"write\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"c.txt\"}}}")
            .getBytes(StandardCharsets.UTF_8);
        Path aPath = graphRoot.resolve("a.json");
        Path bPath = graphRoot.resolve("b.json");
        Path cPath = graphRoot.resolve("c.json");
        Files.write(aPath, aGraph);
        Files.write(bPath, bGraph);
        Files.write(cPath, cGraph);
        List<String> directories = List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows");
        OfflineUpgradeSnapshotInput input = input(source, aPath, aGraph, directories,
            new ImmutableSnapshotAdapter.Entry("assets/Blueprints/Flows/b.json", bGraph.length,
                ManagedFlowFileMigrationContract.sha256(bGraph), ProductionPersistenceOwners.FLOW_ASSETS),
            new ImmutableSnapshotAdapter.Entry("assets/Blueprints/Flows/c.json", cGraph.length,
                ManagedFlowFileMigrationContract.sha256(cGraph), ProductionPersistenceOwners.FLOW_ASSETS),
            new ImmutableSnapshotAdapter.Entry("a.txt", aBytes.length,
                ManagedFlowFileMigrationContract.sha256(aBytes), ProductionPersistenceOwners.STANDALONE_ROOT),
            new ImmutableSnapshotAdapter.Entry("c.txt", cBytes.length,
                ManagedFlowFileMigrationContract.sha256(cBytes), ProductionPersistenceOwners.STANDALONE_ROOT));
        byte[] originalA = Files.readAllBytes(aPath);
        byte[] originalB = Files.readAllBytes(bPath);

        var result = new LegacyResyncSnapshotAdapter().transform(input);
        assertTrue(result.quarantines().stream().anyMatch(value -> value.sourceLocation().equals("assets/Blueprints/Flows/b.json")
            && value.code().equals("MIGRATION.MANAGED_FLOW_FILE_PATH_TEMPLATE")), result.quarantines().toString());
        assertTrue(result.quarantines().stream().anyMatch(value -> value.sourceLocation().equals("assets/Blueprints/Flows/a.json")
            && value.code().equals("MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED")), result.quarantines().toString());
        byte[] database = result.files().stream().filter(value -> value.targetPath().endsWith("managed-files.db"))
            .findFirst().orElseThrow().bytes();
        byte[] manifestBytes = result.files().stream().filter(value -> value.targetPath().endsWith("manifest.json"))
            .findFirst().orElseThrow().bytes();
        ManagedFlowFileLegacyOwnershipManifest manifest = ManagedFlowFileLegacyOwnershipManifest.read(manifestBytes);
        assertTrue(manifest.entries().stream().anyMatch(value -> value.logicalPath().equals("c.txt")));
        assertFalse(manifest.entries().stream().anyMatch(value -> value.logicalPath().equals("a.txt")));
        Path databasePath = temporary.resolve("dependency-finalization.db");
        Files.write(databasePath, database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.prepareStatement("SELECT path FROM managed_files WHERE path IN ('a.txt', 'c.txt') ORDER BY path");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("c.txt", rows.getString(1));
            assertFalse(rows.next());
        }
        for (var file : result.files()) {
            Path target = source.resolve(file.targetPath());
            Files.createDirectories(target.getParent());
            Files.write(target, file.bytes());
        }
        assertArrayEquals(originalA, Files.readAllBytes(aPath));
        assertArrayEquals(originalB, Files.readAllBytes(bPath));
        assertArrayEquals(aBytes, Files.readAllBytes(source.resolve("a.txt")));
        assertArrayEquals(cBytes, Files.readAllBytes(source.resolve("c.txt")));

        var second = new LegacyResyncSnapshotAdapter().transform(input);
        assertTrue(second.files().stream().noneMatch(value -> value.targetPath().startsWith("flow-files/")),
            second.files().toString());
    }

    private OfflineUpgradeSnapshotInput input(Path source, Path graphPath, byte[] graph, List<String> directories,
                                              ImmutableSnapshotAdapter.Entry... additional) throws Exception {
        List<ImmutableSnapshotAdapter.Entry> entries = new ArrayList<>();
        entries.add(new ImmutableSnapshotAdapter.Entry("assets/Blueprints/Flows/" + graphPath.getFileName(), graph.length,
            ManagedFlowFileMigrationContract.sha256(graph), ProductionPersistenceOwners.FLOW_ASSETS));
        entries.addAll(List.of(additional));
        SnapshotMetadata metadata = new SnapshotMetadata(1, "snapshot", Instant.EPOCH,
            LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
        SnapshotManifest manifest = new SnapshotManifest(metadata, directories, entries.stream()
            .map(value -> new SnapshotManifest.Entry(value.relativePath(), value.size(), value.sha256(), value.owner())).toList());
        manifest.write(source.resolveSibling(source.getFileName() + ".manifest"));
        ImmutableSnapshotAdapter.View view = new ImmutableSnapshotAdapter.View(metadata,
            manifest.manifestHash(), directories, entries);
        ProductionSnapshotMetadataManifest.write(source, manifest);
        return new OfflineUpgradeSnapshotInput(source, view);
    }

    private String graph(String id, String nodes) {
        return typedGraph(id, "flow", nodes);
    }

    private String typedGraph(String id, String type, String nodes) {
        return "{\"id\":\"" + id + "\",\"resourceType\":\"" + type + "\",\"version\":1,\"nodes\":"
            + nodes + ",\"connections\":[]}";
    }

    private String graph(String id, String nodes, String connections) {
        return typedGraph(id, "flow", nodes, connections);
    }

    private String typedGraph(String id, String type, String nodes, String connections) {
        return "{\"id\":\"" + id + "\",\"resourceType\":\"" + type + "\",\"version\":1,\"nodes\":"
            + nodes + ",\"connections\":" + connections + "}";
    }

    private void materialize(Path source, ManagedFlowFileMigrationProvider.Result result) throws Exception {
        for (var file : result.files()) {
            Path target = source.resolve(file.targetPath());
            Files.createDirectories(target.getParent());
            Files.write(target, file.bytes());
        }
    }

    private OfflineUpgradeSnapshotInput emptyInput(Path source) throws Exception {
        SnapshotMetadata metadata = new SnapshotMetadata(1, "empty-eligible", Instant.EPOCH,
            LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
        List<String> directories = List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows");
        SnapshotManifest manifest = new SnapshotManifest(metadata, directories, List.of());
        manifest.write(source.resolveSibling(source.getFileName() + ".manifest"));
        ProductionSnapshotMetadataManifest.write(source, manifest);
        return new OfflineUpgradeSnapshotInput(source, new ImmutableSnapshotAdapter.View(metadata,
            manifest.manifestHash(), directories, List.of()));
    }
}
