package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.handler.HandlerRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceActivationTest {
    @Test
    void disabledGraphDoesNotEnterValidationOrRuntime() {
        FlowGraph graph = new FlowGraph();
        graph.setEnabled(false);
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        assertDoesNotThrow(() -> executor.execute(graph, null, null, Map.of()).join());
    }

    @Test
    void authoritativeDisableBlocksEveryNewExecutionOfAStaleCommand(@TempDir Path tempDir) {
        MockBukkit.mock();
        try {
            FlowStorage storage = new FlowStorage(tempDir.toFile());
            AtomicInteger executions = new AtomicInteger();
            HandlerRegistry handlers = new HandlerRegistry();
            handlers.register("event.resync.command", (context, node) -> {
            });
            handlers.register("count", (context, node) -> executions.incrementAndGet());
            FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
            executor.setExecutionAuthority(storage::isExecutionAuthorized);

            FlowGraph command = new FlowGraph("hello", Map.of(
                "start", new FlowNode("event.resync.command", 0, 0, Map.of("command", "hello")),
                "count", new FlowNode("count", 0, 0, Map.of())
            ), List.of(new FlowConnection("start", "flow", "count", "flow")), List.of());
            command.setResourceType("command");
            storage.saveGraph(command);
            FlowGraph staleEnabledCommand = FlowSerializer.deserialize(FlowSerializer.serialize(command));

            executor.execute(staleEnabledCommand, "start", null, null, Map.of()).join();
            FlowGraph authoritative = storage.getGraph("command", "hello");
            authoritative.setEnabled(false);
            storage.saveGraph(authoritative);
            executor.execute(staleEnabledCommand, "start", null, null, Map.of()).join();
            executor.execute(staleEnabledCommand, null, null, Map.of()).join();
            executor.executeSubFlow(staleEnabledCommand, "start", "count", "value", null, null, Map.of()).join();

            assertEquals(1, executions.get());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void authoritativeDisableBlocksStaleFlowsFunctionsAndSubflows(@TempDir Path tempDir) {
        MockBukkit.mock();
        try {
            FlowStorage storage = new FlowStorage(tempDir.toFile());
            AtomicInteger executions = new AtomicInteger();
            HandlerRegistry handlers = new HandlerRegistry();
            handlers.register("count", (context, node) -> executions.incrementAndGet());
            FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
            executor.setExecutionAuthority(storage::isExecutionAuthorized);

            FlowGraph flow = new FlowGraph("routine", Map.of("count", new FlowNode("count", 0, 0, Map.of())), List.of(), List.of());
            flow.setResourceType("flow");
            storage.saveGraph(flow);
            FlowGraph staleFlow = FlowSerializer.deserialize(FlowSerializer.serialize(flow));
            FlowGraph authoritativeFlow = storage.getGraph("flow", "routine");
            authoritativeFlow.setEnabled(false);
            storage.saveGraph(authoritativeFlow);

            FlowGraph function = new FlowGraph("utility", Map.of(), List.of(), List.of());
            function.setFunction(true);
            function.setResourceType("function");
            storage.saveGraph(function);
            FlowGraph staleFunction = FlowSerializer.deserialize(FlowSerializer.serialize(function));
            FlowGraph authoritativeFunction = storage.getGraph("function", "utility");
            authoritativeFunction.setEnabled(false);
            storage.saveGraph(authoritativeFunction);

            executor.execute(staleFlow, "count", null, null, Map.of()).join();
            executor.executeSubFlow(staleFlow, "count", "count", "value", null, null, Map.of()).join();
            assertTrue(executor.executeFunction(staleFunction, null, null, Map.of(), Map.of()).join().isEmpty());
            assertEquals(0, executions.get());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void anonymousTransientFlowRemainsExecutable(@TempDir Path tempDir) {
        MockBukkit.mock();
        try {
            FlowStorage storage = new FlowStorage(tempDir.toFile());
            AtomicInteger executions = new AtomicInteger();
            HandlerRegistry handlers = new HandlerRegistry();
            handlers.register("count", (context, node) -> executions.incrementAndGet());
            FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
            executor.setExecutionAuthority(storage::isExecutionAuthorized);
            FlowGraph transientFlow = new FlowGraph();
            transientFlow.getNodes().put("count", new FlowNode("count", 0, 0, Map.of()));

            executor.execute(transientFlow, "count", null, null, Map.of()).join();

            assertEquals(1, executions.get());
        } finally {
            MockBukkit.unmock();
        }
    }
}
