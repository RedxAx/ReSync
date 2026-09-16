package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class FlowStorageGraphSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void cachedReadsAreIndependentGraphSnapshots() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            Map<String, Object> inputValues = new HashMap<>();
            inputValues.put("nested", new HashMap<>(Map.of("value", "initial")));
            FlowNode node = new FlowNode("initial.node", 1, 2, inputValues);
            FlowGraph graph = new FlowGraph("legacy-flow", new HashMap<>(Map.of("node", node)),
                new ArrayList<>(List.of(new FlowConnection("node", "next", "node", "input"))), new ArrayList<>());
            storage.saveGraph(graph);

            FlowGraph inFlight = storage.getGraph("flow", "legacy-flow");
            FlowGraph editor = storage.getGraph("flow", "legacy-flow");
            editor.getNodes().get("node").setType("changed.node");
            editor.getNodes().get("node").getInputValues().put("nested", Map.of("value", "changed"));
            editor.getConnections().getFirst().setSourcePin("changed");
            storage.saveGraph(editor);

            assertNotSame(inFlight, editor);
            assertEquals("initial.node", inFlight.getNodes().get("node").getType());
            assertEquals("initial", ((Map<?, ?>) inFlight.getNodes().get("node").getInputValues().get("nested")).get("value"));
            assertEquals("next", inFlight.getConnections().getFirst().getSourcePin());
            assertEquals("changed.node", storage.getGraph("flow", "legacy-flow").getNodes().get("node").getType());
        }
    }

    @Test
    void graphSnapshotsPreserveSerializedData() {
        FlowGraph graph = new FlowGraph();
        graph.setId("opaque-flow");
        graph.setOpaqueProperties(Map.of("futureContract", JsonParser.parseString("{\"mode\":\"safe\"}")));

        FlowGraph copy = graph.copy();

        assertEquals(FlowSerializer.serialize(graph), FlowSerializer.serialize(copy));
        assertNotSame(graph.getOpaqueProperties(), copy.getOpaqueProperties());
    }

    @Test
    void graphCacheRejectsACommitMadeDirectlyThroughTheSharedCoordinator() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            storage.saveGraph(graph("external-commit"));
            storage.getGraph("flow", "external-commit");

            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "external-commit");
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(java.util.function.Function.identity());
            AssetTransactionCoordinator.Live live = (AssetTransactionCoordinator.Live) snapshot.state(key).orElseThrow();
            Path currentFile = snapshot.path(key).orElseThrow();
            UUID mutationId = UUID.fromString("41000000-0000-4000-8000-000000000001");
            FlowGraph replacement = graph("external-commit");
            replacement.getNodes().put("replacement", new FlowNode("replacement.node", 1, 1, Map.of()));
            String encoded = AssetFileFormat.withResourceIdentity(FlowSerializer.serialize(replacement), "flow",
                live.revision() + 1L, mutationId.toString());
            Path relativeFile = tempDir.resolve("assets").toAbsolutePath().normalize()
                .relativize(currentFile.toAbsolutePath().normalize());
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(),
                List.of(AssetTransactionCoordinator.AssetDelta.write(key, relativeFile, live,
                    encoded.getBytes(StandardCharsets.UTF_8))), List.of()));

            FlowGraph loaded = storage.getGraph("flow", "external-commit");

            assertEquals(live.revision() + 1L, loaded.getResourceRevision());
            assertEquals("replacement.node", loaded.getNodes().get("replacement").getType());
        }
    }

    private FlowGraph graph(String id) {
        return FlowSerializer.deserialize("{\"id\":\"" + id
            + "\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}");
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }
}
