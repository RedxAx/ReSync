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
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.ConversionRoute;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.ProviderLease;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledConversionExecutionTest {
    private static final OwnerId OWNER = OwnerId.of("conversion-test");
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("execute"));
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("editor"));
    private static final ContractRef<ProviderId> NODES = ContractRef.of(OWNER, ProviderId.of("nodes"));
    private static final ContractRef<ProviderId> CONVERTERS = ContractRef.of(OWNER, ProviderId.of("converters"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final PinId SOURCE = PinId.of("source-value");
    private static final PinId TARGET = PinId.of("target-value");
    private static final PinId CONVERT_INPUT = PinId.of("declared-operand");
    private static final PinId CONVERT_OUTPUT = PinId.of("declared-result");
    private static final TypedValue ORIGINAL = TypedValue.value(TEXT, "hello");
    private static final TypedValue LENGTH = TypedValue.value(NUMBER, 5);
    private static final TypedValue NONEMPTY = TypedValue.value(BOOLEAN, true);

    @Test
    void executesDeclaredMultiHopAndIsolatedFanoutWithExactEndpointsAndInvocationAuthority() throws Exception {
        Fixture fixture = new Fixture(false, false);
        CompiledExecutionPlan plan = fixture.compile();
        CorrelationId root = CorrelationId.random();
        long deadline = System.currentTimeMillis() + 60_000;
        CompiledRuntimeContext context = new CompiledRuntimeContext(null, null, Map.of("origin", ORIGINAL));

        CompiledExecutionRunner.ExecutionResult result = fixture.runner.execute(plan, null, Map.of(),
            new RuntimeCancellationToken(), context, root, deadline).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("source", "length", "length", "nonempty", "text-target", "number-target", "boolean-target"), fixture.calls);
        assertSame(ORIGINAL, fixture.received.get("text-target"));
        assertSame(LENGTH, fixture.received.get("number-target"));
        assertSame(NONEMPTY, fixture.received.get("boolean-target"));
        assertSame(ORIGINAL, result.outputs().get(new GraphEndpoint(nodeId(1), SOURCE)));
        assertEquals(2, plan.providerLeases().stream().filter(lease -> lease.provider().equals(CONVERTERS)).count());
        assertEquals(2, plan.conversionRoutes().size());
        assertEquals(List.of(edge("length", TEXT, NUMBER).id(), edge("nonempty", NUMBER, BOOLEAN).id()),
            plan.conversionRoutes().get(1).conversionIds());
        assertEquals(3, fixture.conversions.size());
        assertEquals(3, fixture.conversions.stream().map(RuntimeInvocation::idempotencyKey).distinct().count());
        fixture.conversions.forEach(invocation -> {
            assertEquals(root, invocation.invocationId());
            assertSame(context, invocation.runtimeContext());
            assertEquals(deadline, invocation.deadlineMillis());
            assertEquals(Set.of(CONVERT_INPUT), invocation.inputs().keySet());
            assertTrue(invocation.idempotencyKey().matches("conversion:[0-9a-f]{64}"));
        });
        assertSame(ORIGINAL, fixture.conversions.get(0).inputs().get(CONVERT_INPUT));
        assertSame(ORIGINAL, fixture.conversions.get(1).inputs().get(CONVERT_INPUT));
        assertSame(LENGTH, fixture.conversions.get(2).inputs().get(CONVERT_INPUT));
    }

    @Test
    void conversionFailureStopsBeforeTargetsAndClosesTheWholeLease() throws Exception {
        Fixture fixture = new Fixture(false, false);
        fixture.firstHandler.set(invocation -> CompletableFuture.failedFuture(new IllegalStateException("Conversion failed")));

        var result = fixture.runner.execute(fixture.compile()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.FAILURE, result.status());
        assertEquals(List.of("source", "length"), fixture.calls);
        assertTrue(fixture.received.isEmpty());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(CONVERTERS).status());
    }

    @Test
    void cancellationDuringConversionStopsContinuationAndPreservesTheRootDeadline() throws Exception {
        Fixture fixture = new Fixture(false, false);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        fixture.firstHandler.set(invocation -> pending);
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        long deadline = System.currentTimeMillis() + 60_000;
        var execution = fixture.runner.execute(fixture.compile(), null, Map.of(), cancellation, null,
            CorrelationId.random(), deadline).toCompletableFuture();
        assertEquals(List.of("source", "length"), fixture.calls);
        assertFalse(execution.isDone());
        assertEquals(deadline, fixture.conversions.getFirst().deadlineMillis());

        cancellation.cancel();
        fixture.conversions.getFirst().cancellationToken().cancelled().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pending.complete(RuntimeResult.success(LENGTH));

        assertEquals(CompiledExecutionRunner.Status.CANCELLED, execution.get(5, TimeUnit.SECONDS).status());
        assertTrue(fixture.received.isEmpty());
        assertEquals(List.of("source", "length"), fixture.calls);
    }

    @Test
    void converterProviderIsLeasedBeforeSourceAndUnavailableProvidersCannotStartANewPlan() throws Exception {
        Fixture fixture = new Fixture(false, false);
        CompiledExecutionPlan plan = fixture.compile();
        CompletableFuture<RuntimeResult> source = new CompletableFuture<>();
        fixture.sourceHandler.set(invocation -> source);
        var execution = fixture.runner.execute(plan).toCompletableFuture();
        RuntimeUnloadResult unload = fixture.registry.tryUnload(CONVERTERS);
        assertEquals(RuntimeUnloadResult.Status.BLOCKED, unload.status());
        assertEquals(1, unload.remainingLeases());

        source.complete(RuntimeResult.success(ORIGINAL));

        CompletionException failure = assertThrows(CompletionException.class, execution::join);
        assertTrue(failure.getCause() instanceof RuntimeCapabilityUnavailableException);
        assertTrue(fixture.conversions.isEmpty());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(CONVERTERS).status());
        fixture.calls.clear();
        assertThrows(CompletionException.class, () -> fixture.runner.execute(plan).toCompletableFuture().join());
        assertTrue(fixture.calls.isEmpty());
    }

    @Test
    void rejectsMissingRoutesWrongConnectionIdentityAndMissingConverterLeaseBeforeAnyHandler() {
        Fixture fixture = new Fixture(false, false);
        CompiledExecutionPlan plan = fixture.compile();
        ConversionRoute route = plan.conversionRoutes().getFirst();
        ConversionRoute wrong = new ConversionRoute(route.connectionId(), route.source(), new GraphEndpoint(nodeId(4), TARGET),
            route.sourceType(), route.targetType(), route.conversionIds());
        List<CompiledExecutionPlan> invalid = List.of(copy(plan, List.of(), false),
            copy(plan, List.of(wrong, plan.conversionRoutes().get(1)), false), copy(plan, plan.conversionRoutes(), true));

        invalid.forEach(candidate -> assertThrows(CompletionException.class,
            () -> fixture.runner.execute(candidate).toCompletableFuture().join()));
        assertTrue(fixture.calls.isEmpty());
    }

    @Test
    void graphCompilationRejectsUndeclaredRoutesAndNonUnaryConverterContracts() {
        Fixture missing = new Fixture(true, false);
        Fixture invalid = new Fixture(false, true);

        for (Fixture fixture : List.of(missing, invalid)) {
            var result = new GraphCompiler(fixture.registry.snapshot().manifest()).compileResult(fixture.graph, fixture.catalog);
            assertFalse(result.compiled());
            assertTrue(result.validation().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.PIN_CONVERSION_MISSING")));
            assertTrue(fixture.calls.isEmpty());
        }
    }

    @Test
    void converterOnlyTimeoutFencesTheWholePlanBeforeTheConverterStarts() throws Exception {
        Fixture fixture = new Fixture(false, false, 1_000);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> source = new AtomicReference<>();
        fixture.sourceHandler.set(invocation -> {
            source.set(invocation);
            return pending;
        });
        CompiledExecutionPlan plan = fixture.compile();
        assertTrue(plan.steps().stream().allMatch(step -> step.semantics().timeoutMillis() == 0));
        assertEquals(1_000, fixture.registry.resolve(new RuntimeBindingKey(CAPABILITY,
            ContractRef.of(OWNER, OperationId.of("length")))).descriptor().semantics().timeoutMillis());

        var execution = fixture.runner.execute(plan).toCompletableFuture();

        assertTrue(source.get().deadlineMillis() < RuntimeExecutionContext.NO_DEADLINE);
        source.get().cancellationToken().cancelled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var result = execution.get(10, TimeUnit.SECONDS);
        pending.complete(RuntimeResult.success(ORIGINAL));
        assertEquals(CompiledExecutionRunner.Status.FAILURE, result.status());
        assertEquals("RUNTIME.EXECUTION_TIMEOUT", result.failure().diagnostic().code());
        assertEquals(List.of("source"), fixture.calls);
        assertTrue(fixture.conversions.isEmpty());
        assertTrue(fixture.received.isEmpty());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(CONVERTERS).status());
    }

    @Test
    void staleConverterFingerprintRejectsThePlanBeforeAnyHandler() {
        Fixture fixture = new Fixture(false, false);
        CompiledExecutionPlan plan = fixture.compile();
        List<ProviderLease> leases = plan.providerLeases().stream().map(lease -> !lease.provider().equals(CONVERTERS) ? lease
            : new ProviderLease(lease.leaseId(), lease.provider(), lease.capability(), lease.operation(), ContentHash.of("0".repeat(64)),
                lease.drainDeadlineMillis(), lease.hardDeadlineMillis(), lease.unloadPolicy())).toList();
        CompiledExecutionPlan stale = new CompiledExecutionPlan(plan.planId(), plan.graph(), plan.graphRevision(), plan.catalogBinding(),
            plan.graphHash(), plan.steps(), plan.connections(), plan.conversionRoutes(), plan.structuralRoutes(), plan.functionBindings(), leases, plan.unknown());

        CompletionException failure = assertThrows(CompletionException.class, () -> fixture.runner.execute(stale).toCompletableFuture().join());

        assertTrue(failure.getCause().getMessage().contains("Compiled Conversion Provider Fingerprint Is Stale"));
        assertTrue(fixture.calls.isEmpty());
        assertTrue(fixture.received.isEmpty());
    }

    @Test
    void invalidOrMissingConverterOutputFailsClosedBeforeDownstream() throws Exception {
        Map<RuntimeResult, String> invalid = new LinkedHashMap<>();
        invalid.put(RuntimeResult.success(ORIGINAL), "RUNTIME.RESULT_TYPE_MISMATCH");
        invalid.put(RuntimeResult.success(), "RUNTIME.RESULT_OUTPUT_COUNT");
        invalid.put(RuntimeResult.success(Map.of(PinId.of("undeclared-result"), LENGTH), null), "RUNTIME.RESULT_OUTPUT_COUNT");
        for (var candidate : invalid.entrySet()) {
            Fixture fixture = new Fixture(false, false);
            fixture.firstHandler.set(invocation -> CompletableFuture.completedFuture(candidate.getKey()));

            var result = fixture.runner.execute(fixture.compile()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(CompiledExecutionRunner.Status.FAILURE, result.status());
            assertEquals(candidate.getValue(), result.failure().diagnostic().code());
            assertEquals(List.of("source", "length"), fixture.calls);
            assertTrue(fixture.received.isEmpty());
            assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(CONVERTERS).status());
        }
    }

    @Test
    void conversionKeysAreBoundedStableForReplayAndIsolatedByRootPlanConnectionAndHop() throws Exception {
        String longId = String.join(".", List.of("a".repeat(31), "b".repeat(31), "c".repeat(31), "d".repeat(31)));
        ConversionGraph.ConversionEdge original = edge("length", TEXT, NUMBER);
        ConversionGraph.ConversionEdge longIdentity = new ConversionGraph.ConversionEdge(TypeReference.of(OWNER.value(), longId),
            original.source(), original.target(), original.cost(), original.losslessness(), original.failure(), original.capability(), original.operation());
        assertEquals(127, longIdentity.id().localId().length());
        assertTrue(longIdentity.id().canonicalKey().contains("\u0000"));
        Fixture fixture = new Fixture(false, false, 0, List.of(longIdentity, edge("nonempty", NUMBER, BOOLEAN)));
        CompiledExecutionPlan plan = fixture.compile();
        CorrelationId root = CorrelationId.random();

        List<String> first = conversionKeys(fixture, plan, root);
        List<String> replay = conversionKeys(fixture, plan, root);
        List<String> differentRoot = conversionKeys(fixture, plan, CorrelationId.random());
        GraphDocument nextGraph = new GraphDocument(fixture.graph.resource(), fixture.graph.revision() + 1,
            fixture.graph.catalogBinding(), fixture.graph.nodes(), fixture.graph.connections());
        CompiledExecutionPlan nextPlan = new GraphCompiler(fixture.registry.snapshot().manifest()).compile(nextGraph, fixture.catalog);
        List<String> differentPlan = conversionKeys(fixture, nextPlan, root);

        assertEquals(first, replay);
        assertEquals(3, first.stream().distinct().count());
        assertTrue(first.stream().noneMatch(differentRoot::contains));
        assertTrue(first.stream().noneMatch(differentPlan::contains));
        first.forEach(key -> {
            assertEquals(75, key.length());
            assertTrue(key.matches("conversion:[0-9a-f]{64}"));
        });
    }

    private static List<String> conversionKeys(Fixture fixture, CompiledExecutionPlan plan, CorrelationId root) throws Exception {
        fixture.conversions.clear();
        var result = fixture.runner.execute(plan, null, Map.of(), new RuntimeCancellationToken(), null, root)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(3, fixture.conversions.size());
        return fixture.conversions.stream().map(RuntimeInvocation::idempotencyKey).toList();
    }

    private static CompiledExecutionPlan copy(CompiledExecutionPlan plan, List<ConversionRoute> routes, boolean removeLeases) {
        return new CompiledExecutionPlan(plan.planId(), plan.graph(), plan.graphRevision(), plan.catalogBinding(), plan.graphHash(),
            plan.steps(), plan.connections(), routes, plan.structuralRoutes(), plan.functionBindings(),
            removeLeases ? plan.providerLeases().stream().filter(lease -> !lease.provider().equals(CONVERTERS)).toList() : plan.providerLeases(), plan.unknown());
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final List<RuntimeInvocation> conversions = new ArrayList<>();
        final Map<String, TypedValue> received = new LinkedHashMap<>();
        final AtomicReference<RuntimeOperationHandler> firstHandler = new AtomicReference<>(invocation -> CompletableFuture.completedFuture(RuntimeResult.success(LENGTH)));
        final AtomicReference<RuntimeOperationHandler> sourceHandler = new AtomicReference<>(invocation -> CompletableFuture.completedFuture(RuntimeResult.success(ORIGINAL)));
        final RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        final CatalogSnapshot catalog;
        final GraphDocument graph;
        final CompiledExecutionRunner runner;

        Fixture(boolean missingRoute, boolean invalidSignature) {
            this(missingRoute, invalidSignature, 0);
        }

        Fixture(boolean missingRoute, boolean invalidSignature, long converterTimeoutMillis) {
            this(missingRoute, invalidSignature, converterTimeoutMillis, List.of(edge("length", TEXT, NUMBER), edge("nonempty", NUMBER, BOOLEAN)));
        }

        Fixture(boolean missingRoute, boolean invalidSignature, long converterTimeoutMillis, List<ConversionGraph.ConversionEdge> declaredConversions) {
            List<RuntimeOperationDescriptor> nodeOperations = List.of(operation("source", List.of(pin(SOURCE, false, TEXT))),
                operation("text-target", List.of(pin(TARGET, true, TEXT))), operation("number-target", List.of(pin(TARGET, true, NUMBER))),
                operation("boolean-target", List.of(pin(TARGET, true, BOOLEAN))));
            List<RuntimeOperationDescriptor.Pin> firstPins = new ArrayList<>(List.of(pin(CONVERT_INPUT, true, TEXT), pin(CONVERT_OUTPUT, false, NUMBER)));
            if (invalidSignature) {
                firstPins.add(pin(PinId.of("additional-operand"), true, TEXT));
            }
            List<RuntimeOperationDescriptor> converterOperations = List.of(operation("length", firstPins, converterTimeoutMillis),
                operation("nonempty", List.of(pin(CONVERT_INPUT, true, NUMBER), pin(CONVERT_OUTPUT, false, BOOLEAN))));
            registry.activate(provider(NODES), nodeOperations.stream().map(operation -> RuntimeBinding.available(operation, NODES, "1.0.0", invocation -> {
                String id = operation.operation().id().value();
                calls.add(id);
                if (id.equals("source")) {
                    return sourceHandler.get().execute(invocation);
                }
                received.put(id, invocation.inputs().get(TARGET));
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })).toList());
            registry.activate(provider(CONVERTERS), converterOperations.stream().map(operation -> RuntimeBinding.available(operation, CONVERTERS, "1.0.0", invocation -> {
                String id = operation.operation().id().value();
                calls.add(id);
                conversions.add(invocation);
                return id.equals("length") ? firstHandler.get().execute(invocation)
                    : CompletableFuture.completedFuture(RuntimeResult.success(Map.of(CONVERT_OUTPUT, NONEMPTY), null));
            })).toList());
            CatalogVersion version = new CatalogVersion(1, 0);
            List<RuntimeOperationDescriptor> requirements = new ArrayList<>(nodeOperations);
            requirements.addAll(converterOperations);
            CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(version, version),
                    CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/conversion-test", "1.0.0", "test", "conversion-test"))
                .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Operations for typed conversion execution tests.", 1)))
                .capabilities(List.of(new CatalogCapabilityDescriptor(CAPABILITY.id(), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(EDITOR.id(), 1, false, InspectorFallback.READ_ONLY_FIELD)))
                .definitions(nodeOperations.stream().map(CompiledConversionExecutionTest::definition).toList())
                .runtimeRequirements(requirements)
                .conversions(missingRoute ? List.of() : declaredConversions)
                .build();
            var compiled = new CatalogCompiler(version, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1);
            assertTrue(compiled.accepted(), () -> compiled.diagnostics().toString());
            catalog = compiled.snapshot().orElseThrow();
            List<GraphNode> nodes = new ArrayList<>();
            for (int index = 0; index < nodeOperations.size(); index++) {
                nodes.add(new GraphNode(nodeId(index + 1), ContractRef.of(OWNER, NodeId.of(nodeOperations.get(index).operation().id().value())), 1, Map.of()));
            }
            List<GraphConnection> connections = List.of(connection(2), connection(3), connection(4));
            graph = new GraphDocument(new ServerResourceLocator(uuid(1), ContractRef.of(OWNER, ResourceTypeId.of("flow")), "conversion"),
                1, new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()), nodes, connections);
            CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
            runner = new CompiledExecutionRunner(() -> activation, registry, new RuntimeAuthority("test-authority"));
        }

        CompiledExecutionPlan compile() {
            var result = new GraphCompiler(registry.snapshot().manifest()).compileResult(graph, catalog);
            assertTrue(result.compiled(), () -> result.validation().diagnostics().toString());
            return result.plan();
        }
    }

    private static RuntimeProviderDescriptor provider(ContractRef<ProviderId> id) {
        return new RuntimeProviderDescriptor(id, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static GraphConnection connection(int target) {
        return new GraphConnection(ConnectionId.of(uuid(target)), new GraphEndpoint(nodeId(1), SOURCE), new GraphEndpoint(nodeId(target), TARGET));
    }

    private static NodeInstanceId nodeId(int id) {
        return NodeInstanceId.of(uuid(id));
    }

    private static UUID uuid(int id) {
        return new UUID(0x4000L, 0x8000000000000000L | id);
    }

    private static ConversionGraph.ConversionEdge edge(String id, TypeExpr source, TypeExpr target) {
        return new ConversionGraph.ConversionEdge(TypeReference.of(OWNER.value(), id), source, target, 1,
            ConversionGraph.Losslessness.LOSSLESS, ConversionGraph.FailureBehavior.DIAGNOSTIC, CAPABILITY, ContractRef.of(OWNER, OperationId.of(id)));
    }

    private static RuntimeOperationDescriptor.Pin pin(PinId id, boolean input, TypeExpr type) {
        return new RuntimeOperationDescriptor.Pin(id, input ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT, type);
    }

    private static RuntimeOperationDescriptor operation(String id, List<RuntimeOperationDescriptor.Pin> pins) {
        return operation(id, pins, 0);
    }

    private static RuntimeOperationDescriptor operation(String id, List<RuntimeOperationDescriptor.Pin> pins, long timeoutMillis) {
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, CAPABILITY,
            RuntimeSemantics.Cancellation.COOPERATIVE, timeoutMillis, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of("cancelled"),
            new RuntimeFailureContract(TEXT, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        return new RuntimeOperationDescriptor(CAPABILITY, ContractRef.of(OWNER, OperationId.of(id)), pins, semantics);
    }

    private static CatalogNodeDescriptor definition(RuntimeOperationDescriptor operation) {
        return CatalogNodeDescriptor.builder(operation.operation().id().value()).domain("flow").family("operation").displayName("Operation")
            .description("Executes a typed operation with explicit runtime bindings.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(operation.pins().stream().map(pin -> new CatalogNodeDescriptor.Pin(pin.id(),
                pin.direction() == RuntimeOperationDescriptor.Direction.INPUT ? CatalogNodeDescriptor.Direction.INPUT : CatalogNodeDescriptor.Direction.OUTPUT,
                pin.type(), "Value", "The exact typed value accepted or returned by this operation.", CatalogNodeDescriptor.Requirement.REQUIRED,
                null, EDITOR, null, null, null)).toList())
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports a structured operation execution failure.",
                    List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation returned a structured failure result."))),
                new CatalogNodeDescriptor.Branch("cancelled", "Cancelled", "Reports cooperative operation execution cancellation.",
                    List.of(new CatalogNodeDescriptor.Case("cancelled", "Cancelled", "The operation stopped after a cancellation request.")))))
            .handler(new CatalogNodeDescriptor.Handler(operation.capability(), operation.operation())).semantics(operation.semantics())
            .requiredCapabilities(Set.of(CAPABILITY)).build();
    }
}
