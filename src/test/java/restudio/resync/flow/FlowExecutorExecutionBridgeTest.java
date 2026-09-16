package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.FunctionHandler;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowExecutorExecutionBridgeTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void configuredBridgeReceivesDetachedLegacyExecutionContext() {
        FlowGraph graph = graph();
        Map<String, Object> eventVariables = Map.of("event.value", "ready");
        AtomicReference<FlowExecutionBridge.Context> received = new AtomicReference<>();
        FlowExecutionBridge bridge = context -> {
            received.set(context);
            return java.util.concurrent.CompletableFuture.completedFuture(FlowExecutionBridge.Result.executed());
        };
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of(), bridge);

        FlowExecutionBridge.MappingContext mapping = mappingFor(graph, Map.of("start", NodeId.of("compiled_start")), Map.of());
        executor.execute(graph, "start", null, null, eventVariables, mapping).join();

        FlowExecutionBridge.Context context = received.get();
        assertNotSame(graph, context.graph());
        assertEquals("start", context.startNodeId());
        assertSame(eventVariables, context.eventVariables());
        assertSame(mapping, context.mappingContext());
        context.graph().getNodes().get("start").setType("changed");
        assertEquals("legacy", graph.getNodes().get("start").getType());
        executor.shutdown();
    }

    @Test
    void bridgeCanBeConfiguredAfterExecutorConstruction() {
        AtomicBoolean bridgeInvoked = new AtomicBoolean();
        FlowExecutionBridge bridge = context -> {
            bridgeInvoked.set(true);
            return CompletableFuture.completedFuture(FlowExecutionBridge.Result.executed());
        };
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            executor.configureExecutionBridge(bridge);
            executor.execute(graph(), "start", null, null, Map.of(),
                mappingFor(graph(), Map.of("start", NodeId.of("compiled_start")), Map.of())).join();

            assertTrue(bridgeInvoked.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void configuredBridgeExecutionHoldsAdmissionUntilTerminalCompletion() {
        FlowGraph graph = graph();
        CompletableFuture<FlowExecutionBridge.Result> bridgeResult = new CompletableFuture<>();
        FlowExecutionBridge bridge = context -> bridgeResult;
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of(), bridge);
        try {
            CompletableFuture<Void> execution = executor.execute(graph, "start", null, null, Map.of(),
                mappingFor(graph, Map.of("start", NodeId.of("compiled_start")), Map.of()));
            FlowExecutor.AdmissionFence fence = executor.fenceAdmissions();
            try {
                CompletableFuture<Void> drained = fence.whenDrained();
                assertFalse(drained.isDone());
                bridgeResult.complete(FlowExecutionBridge.Result.executed());
                execution.join();
                drained.join();
            } finally {
                fence.close();
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void unsupportedBridgeResultFailsClosedBeforeLegacyRuntime() {
        AtomicBoolean legacyInvoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("legacy", (context, node) -> legacyInvoked.set(true));
        FlowExecutionBridge bridge = context -> java.util.concurrent.CompletableFuture.completedFuture(
            FlowExecutionBridge.Result.unsupported("Graph materialization is unavailable"));
        FlowGraph graph = graph();
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of(), bridge);

        CompletionException thrown = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
            () -> executor.execute(graph, "start", null, null, Map.of(),
                mappingFor(graph, Map.of("start", NodeId.of("compiled_start")), Map.of())).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("CORE_EXECUTION_UNSUPPORTED", failure.getCode());
        assertFalse(legacyInvoked.get());
        executor.shutdown();
    }

    @Test
    void configuredBridgeWithoutMappingFailsClosedBeforeBridgeInvocation() {
        AtomicBoolean bridgeInvoked = new AtomicBoolean();
        FlowExecutionBridge bridge = context -> {
            bridgeInvoked.set(true);
            return java.util.concurrent.CompletableFuture.completedFuture(FlowExecutionBridge.Result.executed());
        };
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of(), bridge);

        CompletionException thrown = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
            () -> executor.execute(graph(), "start", null, null, Map.of()).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("CORE_EXECUTION_UNSUPPORTED", failure.getCode());
        assertFalse(bridgeInvoked.get());
        executor.shutdown();
    }

    @Test
    void configuredBridgePreservesLegacyFunctionEntryPoint() {
        AtomicBoolean bridgeInvoked = new AtomicBoolean();
        FlowExecutionBridge bridge = context -> {
            bridgeInvoked.set(true);
            return CompletableFuture.completedFuture(FlowExecutionBridge.Result.executed());
        };
        HandlerRegistry handlers = new HandlerRegistry();
        FunctionHandler functionHandler = new FunctionHandler();
        handlers.register("function_start", functionHandler);
        handlers.register("function_end", functionHandler);
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of(), bridge);
        FlowGraph function = new FlowGraph();
        function.setId("legacy-function");
        function.setFunction(true);
        function.getNodes().put("start", functionNode("function_start"));
        function.getNodes().put("end", functionNode("function_end"));
        function.getConnections().add(new FlowConnection("start", "flow", "end", "flow"));

        Map<String, Object> result = executor.executeFunction(function, null, null, Map.of(), Map.of()).join();

        assertTrue(result.isEmpty());
        assertFalse(bridgeInvoked.get());
        executor.shutdown();
    }

    @Test
    void configuredBridgeRejectsEveryLegacySubflowEntryPointWithoutFallback() {
        AtomicBoolean bridgeInvoked = new AtomicBoolean();
        FlowExecutionBridge bridge = context -> {
            bridgeInvoked.set(true);
            return CompletableFuture.completedFuture(FlowExecutionBridge.Result.executed());
        };
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of(), bridge);
        FlowGraph function = new FlowGraph();
        function.setId("legacy-subflow-function");
        function.setFunction(true);
        FlowRuntime parentRuntime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of());

        try {
            List<CompletableFuture<Object>> attempts = List.of(
                executor.executeSubFlow(function, "start", "output", "value", null, null, Map.of()),
                executor.executeSubFlow(function, "output", "value", null, null, Map.of()),
                executor.executeSubFlow(parentRuntime, function, "output", "value", null, null, Map.of()));

            for (CompletableFuture<Object> attempt : attempts) {
                CompletionException thrown = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, attempt::join);
                FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());
                assertEquals("FUNCTION_COMPILED_EXECUTION_UNAVAILABLE", failure.getCode());
                assertEquals("compiled-core", failure.getDetails().get("bridge"));
            }
            assertFalse(bridgeInvoked.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void explicitCompiledEntryPointRequiresMetadataBeforeBridgeInvocation() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)),
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()),
            new RuntimeBindingRegistry(),
            RuntimeAuthority.anonymous());
        FlowExecutor.CompiledExecutionAuthority authority = new FlowExecutor.CompiledExecutionAuthority(
            CompiledCoreFlowExecutionBridge.authorityHash(), new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));

        CompletionException thrown = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
            () -> executor.executeCompiled(graph(), "start", null, null, Map.of(),
                mappingFor(graph(), Map.of("start", NodeId.of("compiled_start")), Map.of()), null, authority, bridge).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("CORE_EXECUTION_UNSUPPORTED", failure.getCode());
        executor.shutdown();
    }

    @Test
    void mappingMustCoverEveryNodeAndReferencedPin() {
        FlowGraph graph = graph();
        graph.getNodes().put("next", new FlowNode("legacy", 1, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("start", "flow", "next", "flow"));

        FlowExecutionBridge.MappingContext incomplete = mappingFor(graph,
            Map.of("start", NodeId.of("compiled_start")), Map.of());
        assertTrue(incomplete.validationFailure(graph, "start").isPresent());

        FlowExecutionBridge.MappingContext missingPin = mappingFor(graph,
            Map.of("start", NodeId.of("compiled_start"), "next", NodeId.of("compiled_next")), Map.of());
        assertTrue(missingPin.validationFailure(graph, "start").isPresent());

        FlowExecutionBridge.MappingContext complete = mappingFor(graph,
            Map.of("start", NodeId.of("compiled_start"), "next", NodeId.of("compiled_next")),
            Map.of("start/flow", PinId.of("flow"), "next/flow", PinId.of("flow")));
        assertTrue(complete.validationFailure(graph, "start").isEmpty());

        FlowExecutionBridge.MappingContext unscoped = mappingFor(graph,
            Map.of("start", NodeId.of("compiled_start"), "next", NodeId.of("compiled_next")),
            Map.of("flow", PinId.of("flow")));
        assertTrue(unscoped.validationFailure(graph, "start").isPresent());
    }

    @Test
    void mappingTargetsMayRepeatAcrossScopedKeys() {
        FlowExecutionBridge.MappingContext mapping = mappingFor(graph(),
            Map.of("start", NodeId.of("same"), "alias", NodeId.of("same")),
            Map.of("start/flow", PinId.of("flow"), "alias/flow", PinId.of("flow")));

        assertEquals(NodeId.of("same"), mapping.nodeMappings().get("start"));
        assertEquals(NodeId.of("same"), mapping.nodeMappings().get("alias"));
        assertEquals(PinId.of("flow"), mapping.pinMappings().get("start/flow"));
        assertEquals(PinId.of("flow"), mapping.pinMappings().get("alias/flow"));
    }

    @Test
    void failedBridgeResultFailsClosedBeforeLegacyRuntime() {
        AtomicBoolean legacyInvoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("legacy", (context, node) -> legacyInvoked.set(true));
        FlowExecutionBridge bridge = context -> java.util.concurrent.CompletableFuture.completedFuture(
            FlowExecutionBridge.Result.failed("Compiled plan rejected", new IllegalStateException("rejected")));
        FlowGraph graph = graph();
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of(), bridge);

        CompletionException thrown = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
            () -> executor.execute(graph, "start", null, null, Map.of(),
                mappingFor(graph, Map.of("start", NodeId.of("compiled_start")), Map.of())).join());
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, thrown.getCause());

        assertEquals("CORE_EXECUTION_FAILED", failure.getCode());
        assertFalse(legacyInvoked.get());
        executor.shutdown();
    }

    @Test
    void absentBridgePreservesLegacyExecution() {
        AtomicBoolean legacyInvoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("legacy", (context, node) -> legacyInvoked.set(true));
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());

        executor.execute(graph(), "start", null, null, Map.of()).join();

        assertTrue(legacyInvoked.get());
        executor.shutdown();
    }

    private FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("legacy-graph");
        graph.getNodes().put("start", new FlowNode("legacy", 0, 0, Map.of()));
        return graph;
    }

    private FlowNode functionNode(String operation) {
        FlowNode node = new FlowNode(operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }

    private FlowExecutionBridge.MappingContext mappingFor(FlowGraph graph, Map<String, NodeId> nodeMappings,
                                                          Map<String, PinId> pinMappings) {
        return new FlowExecutionBridge.MappingContext(
            new ServerResourceLocator(new ServerId(UUID.fromString("11111111-1111-5111-8111-111111111111")),
                ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), graph.getId()),
            new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64))),
            new SnapshotId(UUID.fromString("22222222-2222-5222-8222-222222222222")),
            nodeMappings,
            pinMappings,
            List.of(TypeReference.of("restudio.resync", "flow"))
        );
    }
}
