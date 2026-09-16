package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageTypedIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void graphListsNeverLeakAcrossResourceTypes() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph flow = new FlowGraph();
            flow.setId("ordinary_flow");
            flow.setResourceType("flow");
            FlowGraph function = new FlowGraph();
            function.setId("callable_function");
            function.setResourceType("function");
            function.setFunction(true);
            FlowGraph command = new FlowGraph();
            command.setId("root_command");
            command.setResourceType("command");
            command.setNodes(new HashMap<>(Map.of("start", new FlowNode("event.resync.command", 0, 0, Map.of()))));

            storage.saveGraph(flow);
            storage.saveGraph(function);
            storage.saveGraph(command);

            assertEquals(List.of("ordinary_flow"), storage.listFlowIds());
            assertEquals(List.of("ordinary_flow"), storage.listGraphIds("flow"));
            assertEquals(List.of("callable_function"), storage.listGraphIds("function"));
            assertEquals(List.of("root_command"), storage.listGraphIds("command"));
        }
    }

    @Test
    void staleGraphCopyBesideACommittedTombstoneFailsRestartClosed() throws IOException {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph graph = new FlowGraph();
            graph.setId("deleted_flow");
            graph.setResourceType("flow");
            storage.saveGraph(graph);
            Path asset;
            try (var paths = Files.walk(tempDir.resolve("assets"))) {
                asset = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("deleted_flow.json"))
                    .findFirst()
                    .orElseThrow();
            }
            byte[] stale = Files.readAllBytes(asset);

            storage.deleteGraph("flow", "deleted_flow");
            assertTrue(Files.exists(tempDir.resolve("assets/.tombstones/flow/deleted_flow.json")));
            Files.createDirectories(asset.getParent());
            Files.write(asset, stale);
        }

        assertTrue(Files.exists(tempDir.resolve("assets/.tombstones/flow/deleted_flow.json")));
        assertThrows(IllegalStateException.class, this::coordinator);
    }

    @Test
    void commandGraphRequiresOneCommandStart() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph command = new FlowGraph();
            command.setId("invalid_command");
            command.setResourceType("command");

            assertThrows(IllegalArgumentException.class, () -> storage.saveGraph(command));
        }
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }
}
