package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.generic.FlowControlHandler;
import restudio.resync.flow.diagnostics.FlowTraceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowExecutorControlFlowTest {
    private HandlerRegistry handlers;
    private FlowTraceService traces;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        handlers = new HandlerRegistry();
        FlowControlHandler controlHandler = new FlowControlHandler();
        registerCurrent("FlowControlHandler", controlHandler::execute, controlHandler.getSupportedOperations());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ifExecutesOnlyTheSelectedBranch() {
        AtomicInteger trueRuns = new AtomicInteger();
        AtomicInteger falseRuns = new AtomicInteger();
        registerCurrent("control_test_true_target", (context, node) -> trueRuns.incrementAndGet());
        registerCurrent("control_test_false_target", (context, node) -> falseRuns.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("if", control("if", Map.of("condition", true)));
        graph.getNodes().put("true_target", node("control_test_true_target"));
        graph.getNodes().put("false_target", node("control_test_false_target"));
        graph.getConnections().add(new FlowConnection("if", "true", "true_target", "flow"));
        graph.getConnections().add(new FlowConnection("if", "false", "false_target", "flow"));

        execute(graph, "if").join();

        assertEquals(1, trueRuns.get());
        assertEquals(0, falseRuns.get());
    }

    @Test
    void switchPublishesItsResultBeforeContinuing() {
        AtomicReference<Integer> observedIndex = new AtomicReference<>();
        AtomicReference<Boolean> observedMatch = new AtomicReference<>();
        registerCurrent("control_test_consumer", (context, node) -> {
            observedIndex.set(context.getInputValue(node, "index", Integer.class));
            observedMatch.set(context.getInputValue(node, "matched", Boolean.class));
        });
        FlowGraph graph = graph();
        graph.getNodes().put("switch", control("switch_case", Map.of("value", 2.0, "cases", List.of(1, 2L, 3.0F))));
        graph.getNodes().put("consumer", node("control_test_consumer"));
        graph.getConnections().add(new FlowConnection("switch", "flow", "consumer", "flow"));
        graph.getConnections().add(new FlowConnection("switch", "index", "consumer", "index"));
        graph.getConnections().add(new FlowConnection("switch", "matched", "consumer", "matched"));

        execute(graph, "switch").join();

        assertEquals(1, observedIndex.get());
        assertEquals(true, observedMatch.get());
    }

    @Test
    void branchAllAwaitsEachBranchInStablePinOrder() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        List<String> order = new ArrayList<>();
        registerCurrent("control_test_first", (context, node) -> {
            order.add("first-start");
            context.getAsyncOperations().put("gate", gate.thenRun(() -> order.add("first-end")));
        });
        registerCurrent("control_test_second", (context, node) -> order.add("second"));
        FlowGraph graph = graph();
        graph.getNodes().put("branches", control("branch_all", Map.of()));
        graph.getNodes().put("first", node("control_test_first"));
        graph.getNodes().put("second", node("control_test_second"));
        graph.getConnections().add(new FlowConnection("branches", "branch_0", "first", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_1", "second", "flow"));

        CompletableFuture<Void> execution = execute(graph, "branches");

        assertEquals(List.of("first-start"), order, () -> traces.snapshot().stream()
            .map(record -> record.getNodeId() + ":" + record.getStatus() + ":" + record.getErrorCode()).toList().toString());
        assertFalse(execution.isDone());

        gate.complete(null);
        execution.join();

        assertEquals(List.of("first-start", "first-end", "second"), order);
    }

    @Test
    void branchAllStopsAfterTheFirstFailure() {
        AtomicBoolean laterRan = new AtomicBoolean();
        registerCurrent("control_test_failure", (context, node) -> {
            throw new IllegalStateException("branch failed");
        });
        registerCurrent("control_test_later", (context, node) -> laterRan.set(true));
        FlowGraph graph = graph();
        graph.getNodes().put("branches", control("branch_all", Map.of()));
        graph.getNodes().put("failure", node("control_test_failure"));
        graph.getNodes().put("later", node("control_test_later"));
        graph.getConnections().add(new FlowConnection("branches", "branch_0", "failure", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_1", "later", "flow"));

        assertThrows(CompletionException.class, () -> execute(graph, "branches").join());
        assertFalse(laterRan.get());
    }

    @Test
    void randomBranchExecutesExactlyOneAvailableTarget() {
        AtomicInteger runs = new AtomicInteger();
        registerCurrent("control_test_target", (context, node) -> runs.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("random", control("branch_random", Map.of("branches", 4)));
        for (int index = 0; index < 4; index++) {
            String id = "target_" + index;
            graph.getNodes().put(id, node("control_test_target"));
            graph.getConnections().add(new FlowConnection("random", "branch_" + index, id, "flow"));
        }

        execute(graph, "random").join();

        assertEquals(1, runs.get());
    }

    @Test
    void asynchronousFanOutBranchesCanConvergeWithoutDroppingEitherPath() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger merges = new AtomicInteger();
        registerCurrent("control_test_source", (context, node) -> {
        });
        registerCurrent("control_test_delayed", (context, node) -> context.getAsyncOperations().put("gate", gate));
        registerCurrent("control_test_immediate", (context, node) -> {
        });
        registerCurrent("control_test_merge", (context, node) -> merges.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("source", node("control_test_source"));
        graph.getNodes().put("delayed", node("control_test_delayed"));
        graph.getNodes().put("immediate", node("control_test_immediate"));
        graph.getNodes().put("merge", node("control_test_merge"));
        graph.getConnections().add(new FlowConnection("source", "flow", "delayed", "flow"));
        graph.getConnections().add(new FlowConnection("source", "flow", "immediate", "flow"));
        graph.getConnections().add(new FlowConnection("delayed", "flow", "merge", "flow"));
        graph.getConnections().add(new FlowConnection("immediate", "flow", "merge", "flow"));

        CompletableFuture<Void> execution = execute(graph, "source");

        assertEquals(1, merges.get(), () -> traces.snapshot().stream()
            .map(record -> record.getNodeId() + ":" + record.getStatus() + ":" + record.getErrorCode()).toList().toString());
        assertFalse(execution.isDone());

        gate.complete(null);
        execution.join();

        assertEquals(2, merges.get());
    }

    private CompletableFuture<Void> execute(FlowGraph graph, String startNodeId) {
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        traces = new FlowTraceService(100);
        traces.setEnabled(true);
        executor.setTraceService(traces);
        return executor.execute(graph, startNodeId, null, null, Map.of());
    }

    private FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("control-flow");
        return graph;
    }

    private FlowNode control(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("FlowControlHandler", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }

    private FlowNode node(String type) {
        return new FlowNode(type, 0, 0, Map.of());
    }

    private void registerCurrent(String type, BiConsumer<FlowContext, FlowNode> operation) {
        registerCurrent(type, operation, Set.of());
    }

    private void registerCurrent(String type, BiConsumer<FlowContext, FlowNode> operation, Set<String> supportedOperations) {
        handlers.register(type, new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                operation.accept(context, node);
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.CURRENT;
            }

            @Override
            public Set<String> getSupportedOperations() {
                return supportedOperations;
            }
        });
    }
}
