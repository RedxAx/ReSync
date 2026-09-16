package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowFunctionReferenceTest {
    @TempDir
    Path tempDir;

    @Test
    void authoritativeStorageBlocksDeletingReferencedFunctions() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph function = new FlowGraph();
            function.setId("library_target");
            function.setFunction(true);
            storage.saveGraph(function);

            FlowGraph caller = new FlowGraph();
            caller.setId("caller");
            caller.setNodes(new HashMap<>(Map.of("call", new FlowNode("custom_function:library_target", 0, 0, Map.of()))));
            storage.saveGraph(caller);

            assertEquals(new FlowFunctionReference("caller", "call"), storage.findFunctionReferences("library_target").getFirst());
            FlowFunctionInUseException exception = assertThrows(FlowFunctionInUseException.class, () -> storage.deleteGraph("library_target"));
            assertEquals(1, exception.getReferences().size());
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
