package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.flow.data.FlowDataType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowExecutorAsyncCompositionTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void consumerAwaitsAsynchronousDataDependency() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<String> observed = new AtomicReference<>();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("async_source", (context, node) -> {
            CompletableFuture<Void> operation = gate.thenRun(() -> context.setOutput(node, "value", "ready"));
            context.getAsyncOperations().put("source", operation);
        });
        handlers.register("consumer", (context, node) -> observed.set(context.getInputValue(node, "value", String.class)));

        FlowGraph graph = dataDependencyGraph();
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        CompletableFuture<Void> execution = executor.execute(graph, "consumer", null, null, Map.of());

        assertFalse(execution.isDone());
        assertNull(observed.get());

        gate.complete(null);
        execution.join();

        assertEquals("ready", observed.get());
    }

    @Test
    void executionFanOutStartsEveryTargetBeforeAwaitingCompletion() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean siblingRan = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("source", (context, node) -> {
        });
        handlers.register("delayed", (context, node) -> context.getAsyncOperations().put("gate", gate));
        handlers.register("sibling", (context, node) -> siblingRan.set(true));

        FlowGraph graph = new FlowGraph();
        graph.setId("execution-fan-out");
        graph.getNodes().put("source", new FlowNode("source", 0, 0, Map.of()));
        graph.getNodes().put("delayed", new FlowNode("delayed", 200, 0, Map.of()));
        graph.getNodes().put("sibling", new FlowNode("sibling", 200, 100, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "flow", "delayed", "flow"));
        graph.getConnections().add(new FlowConnection("source", "flow", "sibling", "flow"));

        CompletableFuture<Void> execution = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of())
            .execute(graph, "source", null, null, Map.of());

        assertTrue(siblingRan.get());
        assertFalse(execution.isDone());

        gate.complete(null);
        execution.join();
    }

    @Test
    void deferredOutputDoesNotAlsoRunTheDefaultContinuation() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean successRan = new AtomicBoolean();
        AtomicBoolean defaultRan = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("async_branch", (context, node) -> context.getAsyncOperations().put("branch",
            gate.thenRun(() -> context.triggerOutput("success"))));
        handlers.register("success_target", (context, node) -> successRan.set(true));
        handlers.register("default_target", (context, node) -> defaultRan.set(true));
        FlowGraph graph = new FlowGraph();
        graph.setId("deferred-output");
        graph.getNodes().put("source", new FlowNode("async_branch", 0, 0, Map.of()));
        graph.getNodes().put("success", new FlowNode("success_target", 200, 0, Map.of()));
        graph.getNodes().put("default", new FlowNode("default_target", 200, 100, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "success", "success", "flow"));
        graph.getConnections().add(new FlowConnection("source", "flow", "default", "flow"));
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());

        CompletableFuture<Void> execution = executor.execute(graph, "source", null, null, Map.of());
        gate.complete(null);
        execution.join();

        assertTrue(successRan.get());
        assertFalse(defaultRan.get());
    }

    @Test
    void asynchronousFailureKeepsDependencyNodeIdentityAndFailsTrace() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean consumerRan = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("async_source", (context, node) -> context.getAsyncOperations().put("source", gate));
        handlers.register("consumer", (context, node) -> consumerRan.set(true));

        FlowTraceService traces = new FlowTraceService(50);
        traces.setEnabled(true);
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        executor.setTraceService(traces);
        CompletableFuture<Void> execution = executor.execute(dataDependencyGraph(), "consumer", null, null, Map.of());

        assertFalse(execution.isDone());
        assertTrue(traces.snapshot().stream().map(FlowTraceRecord::getStatus).anyMatch("started"::equals));
        assertFalse(traces.snapshot().stream().map(FlowTraceRecord::getStatus).anyMatch("success"::equals));

        gate.completeExceptionally(new IllegalStateException("dependency failed"));
        CompletionException thrown = assertThrows(CompletionException.class, execution::join);
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("source", failure.getNodeId());
        assertEquals("DATA_EVALUATION_FAILED", failure.getCode());
        assertFalse(failure.getRemediation().isBlank());
        assertEquals("DATA_EVALUATION_FAILED", failure.toMap().get("code"));
        assertFalse(consumerRan.get());
        FlowTraceRecord failureTrace = traces.snapshot().stream()
            .filter(record -> "failure".equals(record.getStatus()))
            .findFirst()
            .orElseThrow();
        assertEquals("DATA_EVALUATION_FAILED", failureTrace.getErrorCode());
        assertFalse(failureTrace.getRemediation().isBlank());
        assertFalse(traces.snapshot().stream().map(FlowTraceRecord::getStatus).anyMatch("success"::equals));
    }

    @Test
    void asynchronousStructuredFailuresPreserveTheirContract() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("async_structured", (context, node) -> context.awaitBeforeContinuation(
            CompletableFuture.failedFuture(new FlowHandlerException("SUBFLOW_OUTPUT_AMBIGUOUS", "Ambiguous subflow", "Connect one terminal"))));
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("node", new FlowNode("async_structured", 0, 0, Map.of()));
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> executor.execute(graph, "node", null, null, Map.of()).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("SUBFLOW_OUTPUT_AMBIGUOUS", failure.getCode());
        assertEquals("Connect one terminal", failure.getRemediation());
    }

    @Test
    void runtimeUsesTypedDefaultFromResolvedDefinition() {
        AtomicReference<Integer> observed = new AtomicReference<>();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("DefaultHandler", (context, node) -> observed.set(context.getInputValue(node, "amount", Integer.class)));
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("test.default", "Test Default", NodeDefinition.NodeCategory.DATA)
            .handler("DefaultHandler")
            .input(new NodeDefinition.PinBuilder("amount", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.INTEGER)
                .defaultValue("42")
                .build())
            .build());
        FlowGraph graph = new FlowGraph();
        graph.setId("definition-default");
        graph.getNodes().put("node", new FlowNode("test.default", 0, 0, Map.of()));

        new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of())
            .execute(graph, "node", null, null, Map.of())
            .join();

        assertEquals(42, observed.get());
    }

    @Test
    void runtimeEnforcesDeclaredAuthorizationIndependentlyOfCaller() {
        AtomicBoolean invoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("SensitiveHandler", (context, node) -> invoked.set(true));
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("admin.sensitive", "Sensitive", NodeDefinition.NodeCategory.ACTION)
            .handler("SensitiveHandler")
            .authorizationPolicy("admin_only")
            .sensitive(true)
            .auditPolicy("high_impact")
            .build());
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("node", new FlowNode("admin.sensitive", 0, 0, Map.of("api_key", "never-log-this")));
        FlowExecutor executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());
        FlowTraceService traces = new FlowTraceService(10);
        traces.setEnabled(true);
        executor.setTraceService(traces);

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> executor.execute(graph, "node", null, null, Map.of()).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("AUTHORIZATION_DENIED", failure.getCode());
        assertEquals("admin_only", failure.getDetails().get("policy"));
        assertFalse(invoked.get());
        assertTrue(traces.snapshot().stream().allMatch(record -> !record.getInputSummary().contains("never-log-this")));
        assertEquals(1, executor.auditSnapshot().size());
        FlowNodeAuditRecord audit = executor.auditSnapshot().getFirst();
        assertEquals("admin_only", audit.authorizationPolicy());
        assertEquals("high_impact", audit.auditPolicy());
        assertEquals("AUTHORIZATION_DENIED", audit.decisionCode());
        assertFalse(audit.allowed());
    }

    @Test
    void tracesRedactHeadersAndNestedCredentials() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TraceHandler", (context, node) -> {
        });
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("http.trace", "HTTP Trace", NodeDefinition.NodeCategory.ACTION)
            .handler("TraceHandler")
            .build());
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("node", new FlowNode("http.trace", 0, 0, Map.of(
            "headers", Map.of("Authorization", "Bearer never-log-header"),
            "body", Map.of("profile", Map.of("password", "never-log-password"), "name", "Visible"))));
        FlowExecutor executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());
        FlowTraceService traces = new FlowTraceService(10);
        traces.setEnabled(true);
        executor.setTraceService(traces);

        executor.execute(graph, "node", null, null, Map.of()).join();

        assertTrue(traces.snapshot().stream().allMatch(record -> !record.getInputSummary().contains("never-log-header")));
        assertTrue(traces.snapshot().stream().allMatch(record -> !record.getInputSummary().contains("never-log-password")));
    }

    private FlowGraph dataDependencyGraph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("async-composition");
        graph.getNodes().put("source", new FlowNode("async_source", 0, 0, Map.of()));
        graph.getNodes().put("consumer", new FlowNode("consumer", 200, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "value", "consumer", "value"));
        return graph;
    }
}
