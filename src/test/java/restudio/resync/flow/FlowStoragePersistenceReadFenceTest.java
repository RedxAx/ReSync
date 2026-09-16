package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStoragePersistenceReadFenceTest {
    @Test
    void graphReadsFailDuringQuiesceAndCannotReuseThePreviousRootCache(@TempDir Path temporary) throws Exception {
        Path current = Files.createDirectory(temporary.resolve("current"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        AssetPersistenceGate gate = new AssetPersistenceGate(current);
        try (AssetTransactionCoordinator currentCoordinator = coordinator(current);
             AssetTransactionCoordinator candidateCoordinator = coordinator(candidate)) {
            FlowStorage storage = new FlowStorage(current.toFile(), LegacyRuntimeActivationGate.runtime(current), gate,
                currentCoordinator);
            storage.saveGraph(graph("shared", "current"));
            assertEquals("current", storage.getGraph("flow", "shared").getNodes().get("node").getType());

            FlowStorage candidateStorage = new FlowStorage(candidate.toFile(), LegacyRuntimeActivationGate.runtime(candidate),
                new AssetPersistenceGate(candidate), candidateCoordinator);
            candidateStorage.saveGraph(graph("shared", "candidate"));

            storage.quiescePersistence();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> executor.submit(() -> storage.getGraph("flow", "shared")).get());
                assertTrue(failure.getCause() instanceof IllegalStateException);
            } finally {
                executor.shutdownNow();
            }

            storage.rebindPersistence(candidate.resolve("assets"), candidateCoordinator);
            storage.resumePersistence();

            assertEquals("candidate", storage.getGraph("flow", "shared").getNodes().get("node").getType());
        }
    }

    @Test
    void reopeningTheSharedGateDoesNotReopenStorageWithoutAValidatedResume(@TempDir Path temporary) throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), gate,
                coordinator);
            storage.saveGraph(graph("gate-state", "before"));
            storage.quiescePersistence();
            gate.resume();

            assertThrows(IllegalStateException.class, () -> storage.getGraph("flow", "gate-state"));

            storage.resumePersistence();
            assertEquals("before", storage.getGraph("flow", "gate-state").getNodes().get("node").getType());
        }
    }

    private FlowGraph graph(String id, String nodeType) {
        FlowGraph graph = new FlowGraph(id, Map.of("node", new FlowNode(nodeType, 0, 0, Map.of())), List.of(), List.of());
        graph.setResourceType("flow");
        return graph;
    }

    private AssetTransactionCoordinator coordinator(Path root) throws Exception {
        return new AssetTransactionCoordinator(Files.createDirectories(root.resolve("assets")), new Gson());
    }
}
