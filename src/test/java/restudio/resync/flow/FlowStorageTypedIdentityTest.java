package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

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
    void graphListsNeverLeakAcrossResourceTypes() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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

    @Test
    void tombstoneBlocksAStaleGraphCopyAfterRestart() throws IOException {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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

        FlowStorage restarted = new FlowStorage(tempDir.toFile());

        assertTrue(Files.exists(tempDir.resolve("assets/.tombstones/flow/deleted_flow.json")));
        assertEquals(List.of(), restarted.listGraphIds("flow"));
    }

    @Test
    void commandGraphRequiresOneCommandStart() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph command = new FlowGraph();
        command.setId("invalid_command");
        command.setResourceType("command");

        assertThrows(IllegalArgumentException.class, () -> storage.saveGraph(command));
    }
}
