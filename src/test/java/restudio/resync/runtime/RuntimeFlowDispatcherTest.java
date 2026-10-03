package restudio.resync.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFlowDispatcherTest {
    private static final ServerId SERVER = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    @TempDir
    Path root;

    @AfterEach
    void closeDiagnostics() {
        TemporaryLifecycleDiagnostics.close();
    }

    @Test
    void dispatchAcknowledgementRejectsUnavailableRuntimeAuthorities() {
        RuntimeFlowDispatcher dispatcher = new RuntimeFlowDispatcher(null, null);

        assertFalse(dispatcher.dispatch("configured_flow", null, null, Map.of()));
        CompletionException thrown = assertThrows(CompletionException.class,
            () -> dispatcher.dispatchAsync("configured_flow", null, null, Map.of()).join());
        FlowHandlerException failure = assertInstanceOf(FlowHandlerException.class, thrown.getCause());
        assertEquals("FLOW_STORAGE_UNAVAILABLE", failure.getCode());
    }

    @Test
    void unavailableRuntimeDispatchHasOneCorrelatedTerminal() throws Exception {
        CapturingDiagnosticSink diagnostics = new CapturingDiagnosticSink();
        bindDiagnostics(diagnostics);
        RuntimeFlowDispatcher dispatcher = new RuntimeFlowDispatcher(null, null);

        assertThrows(CompletionException.class,
            () -> dispatcher.dispatchAsync("configured_flow", null, null, Map.of()).join());

        List<DiagnosticEvent> events = diagnostics.events.stream()
            .filter(event -> event.stage().startsWith("trigger_"))
            .toList();
        assertEquals(1L, events.stream().filter(event -> event.stage().equals("trigger_ingress")).count());
        assertEquals(1L, events.stream().filter(event -> event.stage().equals("trigger_execution_terminal")).count());
        assertEquals(1, events.stream().map(event -> event.identity().correlationId()).distinct().count());
    }

    @Test
    void functionDispatchKeepsSelectedIdentityArgumentsAndAsyncOutputs() throws Exception {
        CapturingDiagnosticSink diagnostics = new CapturingDiagnosticSink();
        bindDiagnostics(diagnostics);
        FunctionParameterId argument = FunctionParameterId.of(UUID.randomUUID());
        FunctionParameterContract parameter = new FunctionParameterContract(argument, STRING, true, null, Map.of("name", "message"));
        try (Fixture fixture = new Fixture(root, function(List.of(parameter)))) {
            FunctionSourceDocument selected = fixture.storage.core.functionSourceDocument();
            JsonObject call = call("qa_hook");
            JsonObject inputs = new JsonObject();
            inputs.addProperty("function-input-" + argument.canonicalText(), "QA Configured");
            call.add("inputs", inputs);
            Map<String, Object> variables = new HashMap<>(Map.of("npcId", "qa_npc"));

            CompletableFuture<Map<String, Object>> dispatch = fixture.dispatcher.dispatchFunctionAsync(call, null, null, variables);
            inputs.addProperty("function-input-" + argument.canonicalText(), "Changed");
            variables.put("npcId", "changed_npc");
            fixture.storage.core = decoded(function(), ResourceActivationState.ACTIVE);

            assertFalse(dispatch.isDone());
            assertSame(selected, fixture.executor.source);
            assertEquals(2L, fixture.executor.source.graph().functions().getFirst().revision());
            assertEquals(Map.of(argument.canonicalText(), TypedValue.value(STRING, "QA Configured")), fixture.executor.inputs);
            assertEquals("qa_npc", fixture.executor.variables.get("npcId"));
            assertSame(fixture.executor.principal, fixture.executor.invocation.principal());
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("result", "QA Complete");
            outputs.put("optional", null);
            fixture.executor.completion.complete(outputs);

            assertSame(outputs, dispatch.join());
            assertTrue(dispatch.join().containsKey("optional"));
            List<DiagnosticEvent> terminals = diagnostics.events.stream()
                .filter(event -> event.stage().equals("trigger_execution_terminal")).toList();
            assertEquals(1, terminals.size());
            assertEquals(fixture.executor.invocation.invocationId(), terminals.getFirst().identity().correlationId());
        }
    }

    @Test
    void functionDispatchRejectsMissingAndNonFunctionResources() throws Exception {
        try (Fixture fixture = new Fixture(root, null)) {
            assertEquals("FUNCTION_NOT_FOUND", code(fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of())));
            CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
            GraphDocument graph = new GraphDocument(resource("flow", "qa_hook"), 4L, BINDING, List.of(), List.of());
            fixture.storage.core = boundary.decode(boundary.encode(graph,
                new CoreGraphStorageBoundary.AssetMetadata("flow", 4L, UUID.randomUUID().toString(), ResourceActivationState.ACTIVE)));
            assertEquals("FUNCTION_INVALID", code(fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of())));
            fixture.storage.core = decoded(function(), ResourceActivationState.INACTIVE);
            assertEquals("FUNCTION_NOT_ACTIVE", code(fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of())));
        }
    }

    @Test
    void functionDispatchRejectsMissingAuthenticatedRuntimeAndInlineOnlyCalls() throws Exception {
        try (Fixture fixture = new Fixture(root, function())) {
            fixture.executor.available = false;
            assertEquals("FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                code(fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of())));
            JsonObject inline = new JsonObject();
            inline.addProperty("type", "inlineFunction");
            inline.add("graph", new JsonObject());
            assertEquals("FUNCTION_CALL_REQUIRED", code(fixture.dispatcher.dispatchFunctionAsync(inline, null, null, Map.of())));
        }
    }

    @Test
    void functionDispatchPreservesSynchronousRejectionAndAsyncFailure() throws Exception {
        try (Fixture fixture = new Fixture(root, function())) {
            FlowHandlerException rejection = new FlowHandlerException("FUNCTION_AUTHORIZATION_DENIED", "Denied", "Use a trusted principal");
            fixture.executor.rejection = rejection;
            CompletionException rejected = assertThrows(CompletionException.class,
                () -> fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of()).join());
            assertSame(rejection, rejected.getCause());
            fixture.executor.rejection = null;
            CompletableFuture<Map<String, Object>> dispatch = fixture.dispatcher.dispatchFunctionAsync(call("qa_hook"), null, null, Map.of());
            FlowExecutor.FlowExecutionException failure = new FlowExecutor.FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_FAILED", "Failed", null, null, "Inspect the Function");
            assertFalse(dispatch.isDone());
            fixture.executor.completion.completeExceptionally(failure);
            CompletionException failed = assertThrows(CompletionException.class, dispatch::join);
            assertSame(failure, failed.getCause());
        }
    }

    @Test
    void nativeCallArgumentsRejectUnknownAndDuplicateIdentities() throws Exception {
        FunctionParameterId id = FunctionParameterId.random();
        FunctionParameterContract parameter = new FunctionParameterContract(id, STRING, true, null, Map.of("name", "message"));
        try (Fixture fixture = new Fixture(root, function(List.of(parameter)))) {
            JsonObject unknown = call("qa_hook");
            JsonObject inputs = new JsonObject();
            inputs.addProperty("undeclared", "value");
            unknown.add("inputs", inputs);
            assertEquals("FUNCTION_ARGUMENT_UNKNOWN", code(fixture.dispatcher.dispatchFunctionAsync(unknown, null, null, Map.of())));
            JsonObject duplicate = call("qa_hook");
            inputs = new JsonObject();
            inputs.addProperty(id.canonicalText(), "first");
            inputs.addProperty("function-input-" + id.canonicalText(), "second");
            duplicate.add("inputs", inputs);
            assertEquals("FUNCTION_ARGUMENT_DUPLICATE", code(fixture.dispatcher.dispatchFunctionAsync(duplicate, null, null, Map.of())));
        }
    }

    @Test
    void nativeServiceCallAcceptsParameterArrayAndRetainsTypedDefaults() throws Exception {
        FunctionParameterId id = FunctionParameterId.random();
        FunctionParameterId defaultId = FunctionParameterId.random();
        FunctionParameterContract parameter = new FunctionParameterContract(id, STRING, true, null, Map.of("name", "message"));
        FunctionParameterContract defaultParameter = new FunctionParameterContract(defaultId, STRING, true,
            TypedValue.value(STRING, "Saved Default"), Map.of("name", "npcId"));
        try (Fixture fixture = new Fixture(root, function(List.of(parameter, defaultParameter)))) {
            JsonObject call = call("qa_hook");
            JsonObject argument = new JsonObject();
            argument.addProperty("parameterId", id.canonicalText());
            argument.addProperty("value", "$npcId");
            call.add("parameters", new Gson().toJsonTree(List.of(argument)));

            CompletableFuture<Map<String, Object>> execution = FunctionCallSupport.execute(fixture.storage, fixture.executor,
                call, null, null, Map.of("npcId", "qa_npc"));

            assertFalse(execution.isDone());
            assertEquals(Map.of(id.canonicalText(), TypedValue.value(STRING, "qa_npc")), fixture.executor.inputs);
            assertEquals(TypedValue.value(STRING, "Saved Default"), fixture.executor.source.signature().inputs().get(1).defaultValue());
            fixture.executor.completion.complete(Map.of());
            assertTrue(execution.join().isEmpty());
        }
    }

    private static JsonObject call(String id) {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", id);
        return call;
    }

    private static FunctionSourceDocument function() {
        return function(List.of());
    }

    private static FunctionSourceDocument function(List<FunctionParameterContract> inputs) {
        ServerResourceLocator locator = resource("function", "qa_hook");
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(locator), FunctionRevision.of(4L), inputs, List.of());
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), locator, 4L, BINDING, Set.of(), List.of(), List.of(),
            List.of(new FunctionBinding(resource("function", "qa_child"), 2L, List.of(), List.of())), OpaqueData.empty());
        return new FunctionSourceDocument(signature, graph);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private static CoreGraphStorageBoundary.Decoded decoded(FunctionSourceDocument source, ResourceActivationState activation) {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        return boundary.decode(boundary.encode(source, new CoreGraphStorageBoundary.AssetMetadata("function",
            source.signature().revision().value(), UUID.randomUUID().toString(), activation)));
    }

    private static String code(CompletableFuture<?> dispatch) {
        CompletionException thrown = assertThrows(CompletionException.class, dispatch::join);
        return assertInstanceOf(FlowHandlerException.class, thrown.getCause()).getCode();
    }

    private static final class Fixture implements AutoCloseable {
        private final AssetTransactionCoordinator coordinator;
        private final SelectedStorage storage;
        private final CapturingExecutor executor = new CapturingExecutor();
        private final RuntimeFlowDispatcher dispatcher;

        private Fixture(Path root, FunctionSourceDocument source) throws Exception {
            coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
            storage = new SelectedStorage(root.toFile(), source, coordinator);
            dispatcher = new RuntimeFlowDispatcher(storage, executor);
        }

        @Override
        public void close() throws Exception {
            executor.shutdown();
            coordinator.close();
        }
    }

    private static final class SelectedStorage extends FlowStorage {
        private CoreGraphStorageBoundary.Decoded core;

        private SelectedStorage(File root, FunctionSourceDocument source, AssetTransactionCoordinator coordinator) {
            super(root, coordinator);
            this.core = source == null ? null : decoded(source, ResourceActivationState.ACTIVE);
        }

        @Override
        public FlowGraph getGraph(String type, String id) {
            throw new IllegalStateException("Static Function bindings cannot be projected into a legacy graph");
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> getCoreGraph(String type, String id) {
            return "function".equals(type) && "qa_hook".equals(id) ? Optional.ofNullable(core) : Optional.empty();
        }
    }

    private static final class CapturingExecutor extends FlowExecutor {
        private final RuntimePrincipal principal = new RuntimePrincipalAuthority(new RuntimeAuthority("dispatch-test"))
            .issuePersistentSystem("runtime-hooks");
        private final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        private boolean available = true;
        private RuntimeException rejection;
        private FunctionSourceDocument source;
        private Map<String, Object> inputs;
        private Map<String, Object> variables;
        private FunctionInvocationContext invocation;

        private CapturingExecutor() {
            super(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        }

        @Override
        public FunctionInvocationContext defaultFunctionInvocationContext(Player player, Event event, Map<String, Object> variables,
                                                                          CorrelationId id, long deadline) {
            return available ? new FunctionInvocationContext(principal, id,
                new CompiledRuntimeContext(null, null, Map.of(), principal), deadline) : null;
        }

        @Override
        public CompletableFuture<Map<String, Object>> executeFunctionSource(FunctionSourceDocument source, Player player, Event event,
                Map<String, Object> inputs, Map<String, Object> variables, FunctionInvocationContext invocation) {
            if (rejection != null) {
                throw rejection;
            }
            this.source = source;
            this.inputs = inputs;
            this.variables = variables;
            this.invocation = invocation;
            return completion;
        }
    }

    private static void bindDiagnostics(DiagnosticSink sink) throws Exception {
        Method bind = TemporaryLifecycleDiagnostics.class.getDeclaredMethod("bind", DiagnosticSink.class);
        bind.setAccessible(true);
        bind.invoke(null, sink);
    }

    private static final class CapturingDiagnosticSink implements DiagnosticSink {
        private final List<DiagnosticEvent> events = new ArrayList<>();

        @Override
        public Status status() {
            return Status.ready(Mode.VERBOSE);
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            events.add(event);
            return Offer.ACCEPTED;
        }

        @Override
        public Status pause() {
            return status();
        }

        @Override
        public Status resume() {
            return status();
        }

        @Override
        public Flush flush() {
            return events.isEmpty() ? Flush.EMPTY : Flush.FLUSHED;
        }

        @Override
        public void close() {
        }
    }
}
