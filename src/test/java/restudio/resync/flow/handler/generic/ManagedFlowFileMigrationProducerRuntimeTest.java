package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider;
import restudio.resync.upgrade.sqlite.SqliteManagedFlowFileMigrationProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ManagedFlowFileMigrationProducerRuntimeTest {
    @TempDir
    Path temporary;

    @Test
    void emittedDatabaseIsAdmittedThenSupportsMutationAndRestart() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("producer-runtime"));
        byte[] legacy = "legacy-value".getBytes(StandardCharsets.UTF_8);
        Path legacyPath = source.resolve("nested/legacy.txt");
        Files.createDirectories(legacyPath.getParent());
        Files.write(legacyPath, legacy);
        byte[] graph = ("{\"id\":\"runtime\",\"resourceType\":\"flow\",\"version\":1,\"nodes\":{"
            + "\"read\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\"nested/legacy.txt\"}}},\"connections\":[]}")
            .getBytes(StandardCharsets.UTF_8);
        Path graphPath = source.resolve("assets/Blueprints/Flows/runtime.json");
        Files.createDirectories(graphPath.getParent());
        Files.write(graphPath, graph);
        SnapshotMetadata metadata = new SnapshotMetadata(1, "producer-runtime", Instant.EPOCH,
            LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
        List<String> directories = List.of("assets", "assets/Blueprints", "assets/Blueprints/Flows", "nested");
        List<ImmutableSnapshotAdapter.Entry> entries = List.of(
            new ImmutableSnapshotAdapter.Entry("assets/Blueprints/Flows/runtime.json", graph.length,
                ManagedFlowFileMigrationContract.sha256(graph), ProductionPersistenceOwners.FLOW_ASSETS),
            new ImmutableSnapshotAdapter.Entry("nested/legacy.txt", legacy.length,
                ManagedFlowFileMigrationContract.sha256(legacy), ProductionPersistenceOwners.STANDALONE_ROOT));
        SnapshotManifest manifest = new SnapshotManifest(metadata, directories, entries.stream()
            .map(value -> new SnapshotManifest.Entry(value.relativePath(), value.size(), value.sha256(), value.owner()))
            .toList());
        manifest.write(source.resolveSibling(source.getFileName() + ".manifest"));
        ProductionSnapshotMetadataManifest.write(source, manifest);
        OfflineUpgradeSnapshotInput input = new OfflineUpgradeSnapshotInput(source,
            new ImmutableSnapshotAdapter.View(metadata, manifest.manifestHash(), directories, entries));
        ManagedFlowFileMigrationProvider.Graph graphInput = new ManagedFlowFileMigrationProvider.Graph(
            "assets/Blueprints/Flows/runtime.json", "flow", "runtime", graph);
        ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(graphInput), Set.of());
        for (var file : result.files()) {
            Path target = source.resolve(file.targetPath());
            Files.createDirectories(target.getParent());
            Files.write(target, file.bytes());
        }
        assertArrayEquals(legacy, Files.readAllBytes(legacyPath));
        try (SqliteManagedFlowFileCapability admitted = new SqliteManagedFlowFileCapability(source, false)) {
            assertEquals("legacy-value", admitted.read("nested/legacy.txt"));
        }
        try (SqliteManagedFlowFileCapability mutable = new SqliteManagedFlowFileCapability(source, false)) {
            mutable.write("nested/new.txt", "new-value");
        }
        try (SqliteManagedFlowFileCapability restarted = new SqliteManagedFlowFileCapability(source, false)) {
            assertEquals("new-value", restarted.read("nested/new.txt"));
        }
        assertArrayEquals(legacy, Files.readAllBytes(legacyPath));
        ManagedFlowFileMigrationProvider.Result rerun = new SqliteManagedFlowFileMigrationProvider().produce(input,
            List.of(graphInput), Set.of());
        assertEquals(List.of(), rerun.files());
        assertEquals(List.of(), rerun.quarantines());
    }

    @Test
    void functionOnlyAndCommandOnlyStaticMigrationsAreAdmitted() throws Exception {
        for (String type : List.of("function", "command")) {
            Path source = Files.createDirectories(temporary.resolve(type + "-runtime"));
            byte[] legacy = (type + "-legacy").getBytes(StandardCharsets.UTF_8);
            String logicalPath = type + "/legacy.txt";
            Path legacyPath = source.resolve(logicalPath);
            Files.createDirectories(legacyPath.getParent());
            Files.write(legacyPath, legacy);
            String folder = type.equals("function") ? "Functions" : "Commands";
            String graphPathValue = "assets/Blueprints/" + folder + "/" + type + ".json";
            byte[] graph = ("{\"id\":\"" + type + "\",\"resourceType\":\"" + type
                + "\",\"version\":1,\"nodes\":{\"read\":{\"type\":\"file.read\",\"inputValues\":{\"path\":\""
                + logicalPath + "\"}}},\"connections\":[]}").getBytes(StandardCharsets.UTF_8);
            Path graphPath = source.resolve(graphPathValue);
            Files.createDirectories(graphPath.getParent());
            Files.write(graphPath, graph);
            SnapshotMetadata metadata = new SnapshotMetadata(1, type + "-runtime", Instant.EPOCH,
                LegacySnapshotWindow.SOURCE_BUILD, "a".repeat(64), Map.of());
            List<String> directories = List.of("assets", "assets/Blueprints", "assets/Blueprints/" + folder, type);
            List<ImmutableSnapshotAdapter.Entry> entries = List.of(
                new ImmutableSnapshotAdapter.Entry(graphPathValue, graph.length,
                    ManagedFlowFileMigrationContract.sha256(graph), ProductionPersistenceOwners.FLOW_ASSETS),
                new ImmutableSnapshotAdapter.Entry(logicalPath, legacy.length,
                    ManagedFlowFileMigrationContract.sha256(legacy), ProductionPersistenceOwners.STANDALONE_ROOT));
            SnapshotManifest manifest = new SnapshotManifest(metadata, directories, entries.stream()
                .map(value -> new SnapshotManifest.Entry(value.relativePath(), value.size(), value.sha256(), value.owner()))
                .toList());
            manifest.write(source.resolveSibling(source.getFileName() + ".manifest"));
            ProductionSnapshotMetadataManifest.write(source, manifest);
            OfflineUpgradeSnapshotInput input = new OfflineUpgradeSnapshotInput(source,
                new ImmutableSnapshotAdapter.View(metadata, manifest.manifestHash(), directories, entries));
            ManagedFlowFileMigrationProvider.Graph graphInput = new ManagedFlowFileMigrationProvider.Graph(
                graphPathValue, type, type, graph);
            ManagedFlowFileMigrationProvider.Result result = new SqliteManagedFlowFileMigrationProvider().produce(input,
                List.of(graphInput), Set.of());
            for (var file : result.files()) {
                Path target = source.resolve(file.targetPath());
                Files.createDirectories(target.getParent());
                Files.write(target, file.bytes());
            }
            assertArrayEquals(legacy, Files.readAllBytes(legacyPath));
            try (SqliteManagedFlowFileCapability admitted = new SqliteManagedFlowFileCapability(source, false)) {
                assertEquals(type + "-legacy", admitted.read(logicalPath));
            }
            try (SqliteManagedFlowFileCapability mutable = new SqliteManagedFlowFileCapability(source, false)) {
                mutable.write(type + "/after.txt", "after");
            }
            try (SqliteManagedFlowFileCapability restarted = new SqliteManagedFlowFileCapability(source, false)) {
                assertEquals("after", restarted.read(type + "/after.txt"));
            }
            assertArrayEquals(legacy, Files.readAllBytes(legacyPath));
        }
    }
}
