package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionInputMap;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledFunctionGraphExecutionTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("native-function-frame-test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("native-functions"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("functions"));
    private static final CatalogVersion SCHEMA = new CatalogVersion(1, 0);
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr FLOW = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final FunctionParameterId INPUT = FunctionParameterId.deterministic("native-function-input");
    private static final FunctionParameterId OUTPUT = FunctionParameterId.deterministic("native-function-output");
    private static final PinId FLOW_PIN = PinId.of("flow");
    private static final PinId NEXT = PinId.of("output_flow");
    private static final PinId VALUE = PinId.of("value");
    private static final PinId ANSWER = PinId.of("answer");

    @Test
    void asyncGraphRoutesTypedInputsAndReturnsOnlyAfterItsWait() throws Exception {
        CompletableFuture<RuntimeResult> operation = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> admitted = new AtomicReference<>();
        Fixture fixture = new Fixture(invocation -> {
            admitted.set(invocation);
            return operation;
        });
        FunctionSourceDocument source = fixture.source("async", NUMBER, null, true, 1, "delayed");
        CompiledExecutionRunner.FunctionExecutionHandle handle = fixture.run(source, input(NUMBER, BigDecimal.valueOf(7)),
            new RuntimeCancellationToken());

        assertEquals(BigDecimal.valueOf(7), admitted.get().inputs().get(VALUE).value());
        assertFalse(handle.result().toCompletableFuture().isDone());
        assertFalse(handle.physicalCompletion().toCompletableFuture().isDone());
        operation.complete(answer(TypedValue.value(NUMBER, BigDecimal.valueOf(8))));

        FunctionResult result = handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.successful());
        assertEquals(BigDecimal.valueOf(8), result.outputs().value(OUTPUT).value());
        handle.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, fixture.returns.get());
    }

    @Test
    void cancellationSuppressesReturnAndRetainsStartedPhysicalWork() throws Exception {
        CompletableFuture<RuntimeResult> operation = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> admitted = new AtomicReference<>();
        Fixture fixture = new Fixture(invocation -> {
            admitted.set(invocation);
            return operation;
        });
        FunctionSourceDocument source = fixture.source("cancelled", NUMBER, null, true, 1, "delayed");
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        CompiledExecutionRunner.FunctionExecutionHandle handle = fixture.run(source, input(NUMBER, BigDecimal.ONE), cancellation);

        cancellation.cancel();
        FunctionResult result = handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.cancelled());
        assertTrue(result.outputs().values().isEmpty());
        admitted.get().cancellationToken().cancelled().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(handle.physicalCompletion().toCompletableFuture().isDone());
        assertEquals(0, fixture.returns.get());

        operation.complete(answer(TypedValue.value(NUMBER, BigDecimal.TEN)));
        handle.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0, fixture.returns.get());
    }

    @Test
    void nestedAsyncGraphUsesItsOwnLocalsAndWaitsForChildPhysicalCompletion() throws Exception {
        CompletableFuture<RuntimeResult> operation = new CompletableFuture<>();
        AtomicReference<RuntimeScope> childScope = new AtomicReference<>();
        AtomicReference<RuntimeScope> parentScope = new AtomicReference<>();
        AtomicReference<CompiledExecutionRunner.FunctionExecutionHandle> childHandle = new AtomicReference<>();
        Fixture fixture = new Fixture(invocation -> {
            invocation.scope().locals().put("state", "child");
            childScope.set(invocation.scope());
            return operation;
        });
        FunctionSourceDocument child = fixture.source("nested-child", NUMBER, null, true, 1, "delayed");
        fixture.call.set(invocation -> {
            invocation.scope().locals().put("state", "parent");
            parentScope.set(invocation.scope());
            CompiledExecutionRunner.FunctionExecutionHandle nested = fixture.run(child,
                new FunctionInputMap(Map.of(INPUT, invocation.inputs().get(VALUE))), invocation.cancellationToken());
            childHandle.set(nested);
            return nested.result().thenCombine(nested.physicalCompletion(), (result, ignored) -> {
                assertEquals("parent", invocation.scope().locals().get("state"));
                return answer(result.outputs().value(OUTPUT));
            });
        });
        FunctionSourceDocument parent = fixture.source("nested-parent", NUMBER, null, true, 1, "call");
        CompiledExecutionRunner.FunctionExecutionHandle handle = fixture.run(parent, input(NUMBER, BigDecimal.valueOf(4)),
            new RuntimeCancellationToken());

        assertNotSame(parentScope.get(), childScope.get());
        assertFalse(handle.result().toCompletableFuture().isDone());
        assertFalse(childHandle.get().physicalCompletion().toCompletableFuture().isDone());
        operation.complete(answer(TypedValue.value(NUMBER, BigDecimal.valueOf(5))));

        assertEquals(BigDecimal.valueOf(5), handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .outputs().value(OUTPUT).value());
        handle.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("parent", parentScope.get().locals().get("state"));
        assertEquals("child", childScope.get().locals().get("state"));
    }

    @Test
    void declaredDefaultsAreUsedAndUnknownOrMistypedInputsAreRejected() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(answer(invocation.inputs().get(VALUE))));
        FunctionSourceDocument source = fixture.source("defaults", NUMBER, TypedValue.value(NUMBER, BigDecimal.TEN), true, 1, "delayed");

        FunctionResult result = fixture.run(source, FunctionInputMap.empty(), new RuntimeCancellationToken())
            .result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(BigDecimal.TEN, result.outputs().value(OUTPUT).value());
        assertThrows(IllegalArgumentException.class, () -> fixture.run(source, input(TEXT, "ten"), new RuntimeCancellationToken()));
        assertThrows(IllegalArgumentException.class, () -> fixture.run(source,
            new FunctionInputMap(Map.of(FunctionParameterId.deterministic("unknown"), TypedValue.value(NUMBER, BigDecimal.ONE))),
            new RuntimeCancellationToken()));
        FunctionSourceDocument required = fixture.source("required", NUMBER, null, true, 1, "delayed");
        assertThrows(IllegalArgumentException.class, () -> fixture.run(required, FunctionInputMap.empty(), new RuntimeCancellationToken()));
    }

    @Test
    void nullOptionalOutputsRemainTypedAcrossTheFunctionBoundary() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        TypeExpr optional = TypeExpr.optional(NUMBER);
        FunctionSourceDocument source = fixture.source("nullable", optional, null, false, 1, null);

        FunctionResult result = fixture.run(source, new FunctionInputMap(Map.of(INPUT, TypedValue.nullValue(optional))),
            new RuntimeCancellationToken()).result().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertEquals(TypedValue.State.NULL, result.outputs().value(OUTPUT).state());
        assertEquals(optional, result.outputs().value(OUTPUT).type());
    }

    @Test
    void missingAndDuplicateReturnsCannotPublishSuccessfulOutputs() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(answer(invocation.inputs().get(VALUE))));
        FunctionSourceDocument missing = fixture.source("missing", NUMBER, null, true, 0, "delayed");
        FunctionResult result = fixture.run(missing, input(NUMBER, BigDecimal.ONE), new RuntimeCancellationToken())
            .result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.failed());
        assertTrue(result.outputs().values().isEmpty());

        FunctionSourceDocument duplicate = fixture.source("duplicate", NUMBER, null, true, 2, "delayed");
        CompiledExecutionRunner.FunctionExecutionHandle handle = fixture.run(duplicate, input(NUMBER, BigDecimal.ONE), new RuntimeCancellationToken());
        assertThrows(ExecutionException.class, () -> handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
        handle.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void templatedReturnUsesTheSameRenderedValueAsTheRuntimeCallback() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        String id = "rendered-return";
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of("function")), id);
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1),
            List.of(new FunctionParameterContract(INPUT, TEXT)), List.of(new FunctionParameterContract(OUTPUT, TEXT)));
        GraphNode returning = new GraphNode(nodeId(id, "return"), ContractRef.of(OWNER, NodeId.of("return")), 1,
            Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "Hello {name}"))));
        GraphDocument graph = new GraphDocument(SCHEMA, resource, 1, fixture.binding(), Set.of(),
            List.of(node(id, "start", "function_start"), returning),
            List.of(edge(id, "start", "flow", "return", "flow"),
                edge(id, "start", "function-output-" + INPUT.canonicalText(), "return", "name")),
            List.of(), List.of(), OpaqueData.empty());

        FunctionResult result = fixture.run(new FunctionSourceDocument(signature, graph), input(TEXT, "Ada"),
            new RuntimeCancellationToken()).result().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertEquals("Hello Ada", fixture.returned.get().inputs().get(VALUE).value());
        assertEquals("Hello Ada", result.outputs().value(OUTPUT).value());
    }

    @Test
    void signatureReturnPreservesNestedJsonAndConsumesOnlyPairedBraceEscapes() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        String[] templates = {"{\"outer\":{\"value\":1}}", "{{name}}", "}}"};
        String[] expected = {templates[0], "{name}", "}}"};
        for (int ordinal = 0; ordinal < templates.length; ordinal++) {
            String id = "literal-return-" + ordinal;
            ServerResourceLocator resource = new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of("function")), id);
            FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1),
                List.of(), List.of(new FunctionParameterContract(OUTPUT, TEXT)));
            PinId output = PinId.of("function-input-" + OUTPUT.canonicalText());
            GraphNode end = new GraphNode(nodeId(id, "end"), ContractRef.of(OWNER, NodeId.of("function_end")), 1,
                Map.of(output, new PinValue(output, TypedValue.value(TEXT, templates[ordinal]))));
            GraphDocument graph = new GraphDocument(SCHEMA, resource, 1, fixture.binding(), Set.of(),
                List.of(node(id, "start", "function_start"), end), List.of(edge(id, "start", "flow", "end", "flow")),
                List.of(), List.of(), OpaqueData.empty());

            FunctionResult result = fixture.run(new FunctionSourceDocument(signature, graph), FunctionInputMap.empty(),
                new RuntimeCancellationToken()).result().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(result.successful());
            assertEquals(expected[ordinal], result.outputs().value(OUTPUT).value());
        }
    }

    @Test
    void signatureReturnRendersItsDeclaredPlaceholderPins() throws Exception {
        Fixture fixture = new Fixture(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        String id = "signature-rendered-return";
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of("function")), id);
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1),
            List.of(new FunctionParameterContract(INPUT, TEXT)), List.of(new FunctionParameterContract(OUTPUT, TEXT)));
        PinId output = PinId.of("function-input-" + OUTPUT.canonicalText());
        GraphNode end = new GraphNode(nodeId(id, "end"), ContractRef.of(OWNER, NodeId.of("function_end")), 1,
            Map.of(output, new PinValue(output, TypedValue.value(TEXT, "Hello {name}"))));
        GraphDocument graph = new GraphDocument(SCHEMA, resource, 1, fixture.binding(), Set.of(),
            List.of(node(id, "start", "function_start"), end),
            List.of(edge(id, "start", "flow", "end", "flow"),
                edge(id, "start", "function-output-" + INPUT.canonicalText(), "end", "name")),
            List.of(), List.of(), OpaqueData.empty());

        FunctionResult result = fixture.run(new FunctionSourceDocument(signature, graph), input(TEXT, "Ada"),
            new RuntimeCancellationToken()).result().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertEquals("Hello Ada", result.outputs().value(OUTPUT).value());
        GraphNode invalidEnd = new GraphNode(end.instanceId(), end.definition(), 1,
            Map.of(output, new PinValue(output, TypedValue.value(NUMBER, BigDecimal.ONE))));
        GraphDocument invalid = new GraphDocument(SCHEMA, resource, 1, fixture.binding(), Set.of(),
            List.of(node(id, "start", "function_start"), invalidEnd),
            List.of(edge(id, "start", "flow", "end", "flow")), List.of(), List.of(), OpaqueData.empty());
        var rejected = new GraphCompiler(fixture.registry.snapshot().manifest())
            .compileResult(new FunctionSourceDocument(signature, invalid), fixture.catalog);
        assertFalse(rejected.compiled());
        assertTrue(rejected.validation().diagnostics().stream().anyMatch(diagnostic -> "GRAPH.PIN_VALUE_TYPE".equals(diagnostic.code())));
    }

    @Test
    void committedLocalDefaultsAreFreshForEachInvocationOfTheResidentPlan() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = new Fixture(invocation -> {
            Map<String, Object> locals = invocation.scope().locals();
            assertEquals(BigDecimal.valueOf(5), locals.get("counter"));
            assertTrue(locals.containsKey("unset"));
            assertEquals(null, locals.get("unset"));
            List<?> lists = (List<?>) locals.get("values");
            assertEquals(List.of(List.of(BigDecimal.ONE)), lists);
            ((List<?>) lists.getFirst()).clear();
            lists.clear();
            locals.put("counter", BigDecimal.TEN);
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(answer(invocation.inputs().get(VALUE)));
        });
        FunctionSourceDocument source = fixture.source("saved-locals", NUMBER, null, true, 1, "delayed");
        GraphDocument original = source.graph();
        TypeExpr lists = TypeExpr.list(TypeExpr.list(NUMBER));
        List<GraphVariable> defaults = List.of(
            new GraphVariable(UUID.nameUUIDFromBytes(new byte[]{1}), "counter", NUMBER, TypedValue.value(NUMBER, BigDecimal.valueOf(5))),
            new GraphVariable(UUID.nameUUIDFromBytes(new byte[]{2}), "values", lists, TypedValue.value(lists, List.of(List.of(BigDecimal.ONE)))),
            new GraphVariable(UUID.nameUUIDFromBytes(new byte[]{3}), "unset", TypeExpr.optional(NUMBER)));
        GraphDocument graph = new GraphDocument(SCHEMA, original.resource(), original.revision(), original.catalogBinding(), Set.of(),
            original.nodes(), original.connections(), defaults, List.of(), OpaqueData.empty());
        var compiled = new GraphCompiler(fixture.registry.snapshot().manifest())
            .compileResult(new FunctionSourceDocument(source.signature(), graph), fixture.catalog);
        assertTrue(compiled.compiled(), compiled.validation().diagnostics()::toString);
        var template = fixture.runner.prepare(compiled.plan(), nodeId(graph.resource().id(), "start"));

        for (int ordinal = 0; ordinal < 2; ordinal++) {
            var handle = fixture.runner.executeFunctionObserved(template, source.signature(), input(NUMBER, BigDecimal.ONE),
                new RuntimeCancellationToken(), null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
            assertEquals(BigDecimal.ONE, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS).outputs().value(OUTPUT).value());
            handle.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        assertEquals(2, calls.get());
        assertEquals(List.of(List.of(BigDecimal.ONE)), compiled.plan().localDefaults().get("values").value());
    }

    private static FunctionInputMap input(TypeExpr type, Object value) {
        return new FunctionInputMap(Map.of(INPUT, TypedValue.value(type, value)));
    }

    private static RuntimeResult answer(TypedValue value) {
        return RuntimeResult.success(Map.of(NEXT, TypedValue.value(FLOW, true), ANSWER, value), null);
    }

    private static final class Fixture {
        private final RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        private final AtomicReference<RuntimeOperationHandler> call = new AtomicReference<>(invocation ->
            CompletableFuture.failedFuture(new IllegalStateException("Nested Handler Is Required")));
        private final AtomicInteger returns = new AtomicInteger();
        private final AtomicReference<RuntimeInvocation> returned = new AtomicReference<>();
        private final CatalogSnapshot catalog;
        private final CompiledExecutionRunner runner;

        private Fixture(RuntimeOperationHandler delayed) {
            List<CatalogNodeDescriptor> definitions = List.of(
                definition("function_start", List.of(pin("flow", false, FLOW))),
                definition("function_end", List.of(pin("flow", true, FLOW))),
                definition("delayed", List.of(pin("flow", true, FLOW), pin("value", true, NUMBER),
                    pin("output_flow", false, FLOW), pin("answer", false, NUMBER))),
                definition("call", List.of(pin("flow", true, FLOW), pin("value", true, NUMBER),
                    pin("output_flow", false, FLOW), pin("answer", false, NUMBER))),
                definition("return", List.of(pin("flow", true, FLOW), pin("value", true, TEXT))));
            List<RuntimeOperationDescriptor> operations = definitions.stream().map(definition -> new RuntimeOperationDescriptor(
                CAPABILITY, definition.handler().operation(), definition.pins().stream().map(pin -> new RuntimeOperationDescriptor.Pin(
                    pin.id(), pin.direction() == CatalogNodeDescriptor.Direction.INPUT ? RuntimeOperationDescriptor.Direction.INPUT
                        : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList(), definition.semantics(),
                definition.metadata())).toList();
            List<RuntimeBinding> bindings = operations.stream().map(operation -> RuntimeBinding.available(operation, PROVIDER, "1.0.0",
                invocation -> switch (operation.operation().id().value()) {
                    case "function_start" -> CompletableFuture.completedFuture(RuntimeResult.success(Map.of(FLOW_PIN, TypedValue.value(FLOW, true)), null));
                    case "function_end" -> {
                        returns.incrementAndGet();
                        yield CompletableFuture.completedFuture(RuntimeResult.success());
                    }
                    case "delayed" -> delayed.execute(invocation);
                    case "call" -> call.get().execute(invocation);
                    case "return" -> {
                        returned.set(invocation);
                        yield CompletableFuture.completedFuture(RuntimeResult.success());
                    }
                    default -> throw new IllegalStateException("Unknown Test Operation");
                })).toList();
            registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), bindings);
            CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(SCHEMA, SCHEMA),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/native-function-test.json", "1.0.0",
                    "test", "native-function-test"))
                .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Operations that run inside an admitted Function.", 1)))
                .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("functions"), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD)))
                .definitions(definitions).runtimeRequirements(operations).build();
            var compiled = new CatalogCompiler(SCHEMA, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1L);
            assertTrue(compiled.accepted(), compiled.diagnostics()::toString);
            catalog = compiled.snapshot().orElseThrow();
            runner = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"));
        }

        private CatalogBinding binding() {
            return CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        }

        private FunctionSourceDocument source(String id, TypeExpr type, TypedValue defaultValue, boolean required,
                                               int returnCount, String operation) {
            ServerResourceLocator resource = new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of("function")), id);
            FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1),
                List.of(new FunctionParameterContract(INPUT, type, required, defaultValue)), List.of(new FunctionParameterContract(OUTPUT, type)));
            List<GraphNode> nodes = new ArrayList<>();
            List<GraphConnection> edges = new ArrayList<>();
            nodes.add(node(id, "start", "function_start"));
            if (operation != null) {
                nodes.add(node(id, "operation", operation));
                edges.add(edge(id, "start", "flow", "operation", "flow"));
                edges.add(edge(id, "start", "function-output-" + INPUT.canonicalText(), "operation", "value"));
            }
            for (int ordinal = 0; ordinal < returnCount; ordinal++) {
                String end = "end" + ordinal;
                nodes.add(node(id, end, "function_end"));
                edges.add(edge(id, operation == null ? "start" : "operation", operation == null ? "flow" : "output_flow", end, "flow"));
                edges.add(edge(id, operation == null ? "start" : "operation",
                    operation == null ? "function-output-" + INPUT.canonicalText() : "answer", end, "function-input-" + OUTPUT.canonicalText()));
            }
            CatalogBinding binding = CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
            GraphDocument graph = new GraphDocument(SCHEMA, resource, 1L, binding, Set.of(), nodes, edges, List.of(), List.of(), OpaqueData.empty());
            return new FunctionSourceDocument(signature, graph);
        }

        private CompiledExecutionRunner.FunctionExecutionHandle run(FunctionSourceDocument source, FunctionInputMap inputs,
                                                                    RuntimeCancellationToken cancellation) {
            var compiled = new GraphCompiler(registry.snapshot().manifest()).compileResult(source, catalog);
            assertTrue(compiled.compiled(), compiled.validation().diagnostics()::toString);
            return runner.executeFunctionObserved(runner.prepare(compiled.plan(), nodeId(source.graph().resource().id(), "start")),
                source.signature(), inputs, cancellation, null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
        }
    }

    private static GraphNode node(String id, String name, String operation) {
        return new GraphNode(nodeId(id, name), ContractRef.of(OWNER, NodeId.of(operation)), 1, Map.of());
    }

    private static NodeInstanceId nodeId(String id, String name) {
        return NodeInstanceId.deterministic("native-function-" + id + "-" + name);
    }

    private static GraphConnection edge(String id, String source, String sourcePin, String target, String targetPin) {
        return new GraphConnection(ConnectionId.deterministic("native-function-" + id + "-" + source + "-" + sourcePin + "-" + target),
            new GraphEndpoint(nodeId(id, source), PinId.of(sourcePin)), new GraphEndpoint(nodeId(id, target), PinId.of(targetPin)));
    }

    private static CatalogNodeDescriptor definition(String id, List<CatalogNodeDescriptor.Pin> pins) {
        RuntimeSemantics.Cancellation cancellation = "delayed".equals(id) || "call".equals(id)
            ? RuntimeSemantics.Cancellation.COOPERATIVE : RuntimeSemantics.Cancellation.NONE;
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, CAPABILITY,
            cancellation, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), cancellation == RuntimeSemantics.Cancellation.NONE
                ? Set.of() : Set.of("cancelled"), new RuntimeFailureContract(TEXT, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        List<CatalogNodeDescriptor.Branch> branches = new ArrayList<>();
        branches.add(new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports a structured operation failure.",
            List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation failed to finish successfully."))));
        if (cancellation != RuntimeSemantics.Cancellation.NONE) {
            branches.add(new CatalogNodeDescriptor.Branch("cancelled", "Cancelled", "Reports cancellation before physical work has drained.",
                List.of(new CatalogNodeDescriptor.Case("cancelled", "Cancelled", "The operation observed its cancellation token."))));
        }
        return CatalogNodeDescriptor.builder(id).domain("flow").family("function").displayName(id)
            .description("Executes the typed " + id + " operation inside this Function.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow"))).pins(pins)
            .branches(branches)
            .handler(new CatalogNodeDescriptor.Handler(CAPABILITY, ContractRef.of(OWNER, OperationId.of(id))))
            .semantics(semantics).requiredCapabilities(Set.of(CAPABILITY))
            .metadata("return".equals(id) ? Map.of("handlerConfig", Map.of("operation", "return_value")) : Map.of()).build();
    }

    private static CatalogNodeDescriptor.Pin pin(String id, boolean input, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), input ? CatalogNodeDescriptor.Direction.INPUT : CatalogNodeDescriptor.Direction.OUTPUT,
            type, id, "Provides the typed " + id + " value for this operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
            ContractRef.of(OWNER, CapabilityId.of("generic-editor")), null, null, null);
    }
}
