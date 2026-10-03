package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.ProviderLease;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.LeaseId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeResult.Status;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledExecutionRunnerTest {
    private static final OwnerId OWNER = OwnerId.of("runner-test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("provider"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("runner-test", "text"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr EXECUTION = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr FAILURE = TypeExpr.named(TypeReference.of("runner-test", "failure"));
    private static final CatalogBinding CATALOG_BINDING = new CatalogBinding(
        1,
        hash("catalog"),
        hash("manifest"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        ContractRef.of(OWNER, ResourceTypeId.of("flow")),
        "runner");
    private static final ContentHash GRAPH_HASH = hash("graph");
    private static final UUID PLAN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final NodeInstanceId SOURCE_A = NodeInstanceId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    private static final NodeInstanceId SOURCE_B = NodeInstanceId.of(UUID.fromString("00000000-0000-4000-8000-000000000002"));
    private static final NodeInstanceId JOIN = NodeInstanceId.of(UUID.fromString("00000000-0000-4000-8000-000000000003"));
    private static final NodeInstanceId FAILURE_NODE = NodeInstanceId.of(UUID.fromString("00000000-0000-4000-8000-000000000004"));
    private static final NodeInstanceId DOWNSTREAM = NodeInstanceId.of(UUID.fromString("00000000-0000-4000-8000-000000000005"));
    private static final PinId OUTPUT_A = PinId.of("output-a");
    private static final PinId OUTPUT_B = PinId.of("output-b");
    private static final PinId JOIN_OUTPUT = PinId.of("joined");
    private static final PinId LEFT = PinId.of("left");
    private static final PinId RIGHT = PinId.of("right");
    private static final PinId DOWNSTREAM_INPUT = PinId.of("input");
    private static final PinId EXECUTION_INPUT = PinId.of("execution-input");

    @Test
    void givesEachRunAUniqueRootAndReusesChildrenForAnExplicitRetry() throws Exception {
        RuntimeOperationDescriptor operation = operation("run-identity", List.of(), RuntimeSemantics.Cancellation.NONE);
        List<String> invocationKeys = new CopyOnWriteArrayList<>();
        RuntimeBinding binding = binding(operation, invocation -> {
            invocationKeys.add(invocation.idempotencyKey());
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        CompiledExecutionPlan plan = plan(List.of(step(SOURCE_A, operation, Map.of(), Map.of())), List.of(), List.of());
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(binding), new RuntimeAuthority("test-authority"));
        CorrelationId retryRoot = CorrelationId.random();

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(plan, null, Map.of(), new RuntimeCancellationToken(), null,
            retryRoot).toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(plan, null, Map.of(), new RuntimeCancellationToken(), null,
            retryRoot).toCompletableFuture().get(5, TimeUnit.SECONDS).status());

        assertEquals(3, invocationKeys.size());
        assertFalse(invocationKeys.get(0).equals(invocationKeys.get(1)));
        assertEquals(invocationKeys.get(1), invocationKeys.get(2));
    }

    @Test
    void preparedTimeoutBudgetKeepsEquivalentBindingsAndRejectsChangedSemantics() {
        RuntimeOperationDescriptor initial = operation("resident-timeout", List.of(), RuntimeSemantics.Cancellation.NONE);
        Map<String, Object> firstSemantics = new LinkedHashMap<>(initial.semantics().canonicalValue());
        firstSemantics.put("timeoutMillis", 1_000L);
        RuntimeOperationDescriptor original = new RuntimeOperationDescriptor(initial.capability(), initial.operation(), initial.pins(),
            RuntimeSemantics.fromCanonical(firstSemantics));
        RuntimeBinding originalBinding = binding(original, ignored -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(originalBinding);
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"));
        CompiledExecutionPlan plan = plan(List.of(step(SOURCE_A, original, Map.of(), Map.of())), List.of(), List.of());
        CompiledExecutionRunner.ExecutionTemplate prepared = runner.prepare(plan, SOURCE_A);

        assertEquals(RuntimeUnloadResult.Status.REMOVED, registry.unload(PROVIDER).status());
        registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(binding(original, ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(prepared, Map.of(), new RuntimeCancellationToken(),
            null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE).toCompletableFuture().join().status());

        assertEquals(RuntimeUnloadResult.Status.REMOVED, registry.unload(PROVIDER).status());
        Map<String, Object> changedSemantics = new LinkedHashMap<>(original.semantics().canonicalValue());
        changedSemantics.put("timeoutMillis", 50L);
        RuntimeOperationDescriptor changed = new RuntimeOperationDescriptor(original.capability(), original.operation(), original.pins(),
            RuntimeSemantics.fromCanonical(changedSemantics));
        AtomicBoolean called = new AtomicBoolean();
        registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(binding(changed, ignored -> {
                called.set(true);
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })));

        CompletionException failure = assertThrows(CompletionException.class, () -> runner.execute(prepared, Map.of(),
            new RuntimeCancellationToken(), null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE).toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof RuntimeCapabilityUnavailableException);
        assertFalse(called.get());
    }

    @Test
    void residentTemplateIsReusedAndInvocationScopeReturnsToZero() throws Exception {
        RuntimeOperationDescriptor operation = operation("resident-template", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        CompiledExecutionPlan plan = plan(List.of(step(SOURCE_A, operation, Map.of(), Map.of())), List.of(), List.of());
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(binding), new RuntimeAuthority("test-authority"));
        CompiledExecutionRunner.ExecutionTemplate template = runner.prepare(plan, SOURCE_A);
        CorrelationId invocationId = CorrelationId.random();
        long deadline = System.currentTimeMillis() + 60_000L;

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(template, Map.of(), new RuntimeCancellationToken(),
            null, invocationId, deadline).toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(template, Map.of(), new RuntimeCancellationToken(),
            null, invocationId, deadline + 60_000L).toCompletableFuture().get(5, TimeUnit.SECONDS).status());

        assertEquals(1L, runner.templatePreparationCount());
        assertEquals(0, runner.activeInvocationCount());
    }

    @Test
    void executesInDeterministicTopologicalOrderAndRoutesTypedOutputs() throws Exception {
        RuntimeOperationDescriptor sourceOperationA = operation("source-a", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, TEXT)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor sourceOperationB = operation("source-b", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_B, RuntimeOperationDescriptor.Direction.OUTPUT, TEXT)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor joinOperation = operation("join", List.of(
            new RuntimeOperationDescriptor.Pin(LEFT, RuntimeOperationDescriptor.Direction.INPUT, TEXT),
            new RuntimeOperationDescriptor.Pin(RIGHT, RuntimeOperationDescriptor.Direction.INPUT, TEXT),
            new RuntimeOperationDescriptor.Pin(JOIN_OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT, TEXT)),
            RuntimeSemantics.Cancellation.NONE);
        List<String> invoked = new CopyOnWriteArrayList<>();
        AtomicReference<Map<PinId, TypedValue>> joinInputs = new AtomicReference<>();
        RuntimeBinding sourceBindingA = binding(sourceOperationA, invocation -> {
            invoked.add("a");
            return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT_A, value("one")), null));
        });
        RuntimeBinding sourceBindingB = binding(sourceOperationB, invocation -> {
            invoked.add("b");
            return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT_B, value("two")), null));
        });
        RuntimeBinding joinBinding = binding(joinOperation, invocation -> {
            invoked.add("join");
            joinInputs.set(invocation.inputs());
            return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(JOIN_OUTPUT, value("joined")), null));
        });
        RuntimeBindingRegistry registry = registry(sourceBindingA, sourceBindingB, joinBinding);
        GraphEndpoint sourceATarget = new GraphEndpoint(JOIN, LEFT);
        GraphEndpoint sourceBTarget = new GraphEndpoint(JOIN, RIGHT);
        GraphConnection sourceAConnection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000011")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), sourceATarget);
        GraphConnection sourceBConnection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000012")),
            new GraphEndpoint(SOURCE_B, OUTPUT_B), sourceBTarget);
        Map<PinId, List<GraphEndpoint>> joinOutputs = outputBindings(JOIN_OUTPUT);
        CompiledExecutionPlan plan = plan(
            List.of(
                step(JOIN, joinOperation, inputs(LEFT, RIGHT), joinOutputs),
                step(SOURCE_B, sourceOperationB, Map.of(), outputBindings(OUTPUT_B, sourceBTarget)),
                step(SOURCE_A, sourceOperationA, Map.of(), outputBindings(OUTPUT_A, sourceATarget))),
            List.of(sourceBConnection, sourceAConnection), List.of());

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("a", "b", "join"), invoked);
        assertEquals(value("one"), joinInputs.get().get(LEFT));
        assertEquals(value("two"), joinInputs.get().get(RIGHT));
        assertEquals(value("joined"), result.outputs().get(new GraphEndpoint(JOIN, JOIN_OUTPUT)));
    }

    @Test
    void convertsDirectlyAssignableNumbersToStringsWithoutACatalogConversionRoute() throws Exception {
        RuntimeOperationDescriptor sourceOperation = operation("direct-number-source", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, NUMBER)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor targetOperation = operation("direct-string-target", List.of(
            new RuntimeOperationDescriptor.Pin(DOWNSTREAM_INPUT, RuntimeOperationDescriptor.Direction.INPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        GraphEndpoint target = new GraphEndpoint(DOWNSTREAM, DOWNSTREAM_INPUT);
        GraphConnection connection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000014")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), target);
        CompiledExecutionPlan plan = plan(List.of(
            step(SOURCE_A, sourceOperation, Map.of(), outputBindings(OUTPUT_A, target)),
            step(DOWNSTREAM, targetOperation, Map.of(DOWNSTREAM_INPUT, TypedValue.absent(STRING)), Map.of())),
            List.of(connection), List.of());
        AtomicReference<TypedValue> received = new AtomicReference<>();
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(
            binding(sourceOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success(Map.of(
                OUTPUT_A, TypedValue.value(NUMBER, BigDecimal.valueOf(12))), null))),
            binding(targetOperation, invocation -> {
                received.set(invocation.inputs().get(DOWNSTREAM_INPUT));
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })),
            new RuntimeAuthority("test-authority"));

        assertDoesNotThrow(() -> runner.prepare(plan, SOURCE_A));
        assertEquals(CompiledExecutionRunner.Status.SUCCESS,
            runner.execute(plan, SOURCE_A, Map.of(), new RuntimeCancellationToken()).toCompletableFuture()
                .get(5, TimeUnit.SECONDS).status());
        assertEquals(TypedValue.value(STRING, "12"), received.get());
    }

    @Test
    void includesIndependentDataSourcesInAnEventExecutionScope() throws Exception {
        RuntimeOperationDescriptor eventOperation = operation("scoped-event", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor literalOperation = operation("scoped-literal", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_B, RuntimeOperationDescriptor.Direction.OUTPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor consumerOperation = operation("scoped-consumer", List.of(
            new RuntimeOperationDescriptor.Pin(LEFT, RuntimeOperationDescriptor.Direction.INPUT, STRING),
            new RuntimeOperationDescriptor.Pin(RIGHT, RuntimeOperationDescriptor.Direction.INPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor unrelatedOperation = operation("scoped-unrelated", List.of(), RuntimeSemantics.Cancellation.NONE);
        GraphEndpoint left = new GraphEndpoint(JOIN, LEFT);
        GraphEndpoint right = new GraphEndpoint(JOIN, RIGHT);
        GraphConnection eventConnection = new GraphConnection(ConnectionId.interactive(),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), left);
        GraphConnection literalConnection = new GraphConnection(ConnectionId.interactive(),
            new GraphEndpoint(SOURCE_B, OUTPUT_B), right);
        CompiledExecutionPlan plan = plan(List.of(
            step(SOURCE_A, eventOperation, Map.of(), outputBindings(OUTPUT_A, left)),
            step(SOURCE_B, literalOperation, Map.of(), outputBindings(OUTPUT_B, right)),
            step(JOIN, consumerOperation, Map.of(LEFT, TypedValue.absent(STRING), RIGHT, TypedValue.absent(STRING)), Map.of()),
            step(DOWNSTREAM, unrelatedOperation, Map.of(), Map.of())),
            List.of(eventConnection, literalConnection), List.of());
        AtomicReference<Map<PinId, TypedValue>> received = new AtomicReference<>();
        AtomicBoolean unrelatedInvoked = new AtomicBoolean();
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(
            binding(eventOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success(
                Map.of(OUTPUT_A, TypedValue.value(STRING, "event")), null))),
            binding(literalOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success(
                Map.of(OUTPUT_B, TypedValue.value(STRING, "literal")), null))),
            binding(consumerOperation, invocation -> {
                received.set(invocation.inputs());
                return CompletableFuture.completedFuture(RuntimeResult.success());
            }),
            binding(unrelatedOperation, invocation -> {
                unrelatedInvoked.set(true);
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })), new RuntimeAuthority("test-authority"));

        CompiledExecutionRunner.ExecutionTemplate prepared = runner.prepare(plan, SOURCE_A);
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(prepared, Map.of(),
            new RuntimeCancellationToken(), null, CorrelationId.random(), System.currentTimeMillis() + 60_000L)
            .toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(TypedValue.value(STRING, "event"), received.get().get(LEFT));
        assertEquals(TypedValue.value(STRING, "literal"), received.get().get(RIGHT));
        assertFalse(unrelatedInvoked.get());
    }

    @Test
    void rendersConnectedStringTemplatePinWithoutPassingItToTheFixedRuntimeBinding() throws Exception {
        PinId textPin = PinId.of("text");
        PinId extraPin = PinId.of("extraPin");
        RuntimeOperationDescriptor sourceOperation = operation("template-source", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor targetOperation = operation("template-target", List.of(
            new RuntimeOperationDescriptor.Pin(textPin, RuntimeOperationDescriptor.Direction.INPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        GraphEndpoint target = new GraphEndpoint(DOWNSTREAM, extraPin);
        GraphConnection connection = new GraphConnection(ConnectionId.interactive(),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), target);
        CompiledExecutionPlan plan = plan(List.of(
            step(SOURCE_A, sourceOperation, Map.of(), outputBindings(OUTPUT_A, target)),
            step(DOWNSTREAM, targetOperation, Map.of(
                textPin, TypedValue.value(STRING, "Color: {extraPin}, {{literal}}"),
                extraPin, TypedValue.absent(STRING)), Map.of())), List.of(connection), List.of());
        AtomicReference<Map<PinId, TypedValue>> received = new AtomicReference<>();
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(
            binding(sourceOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success(
                Map.of(OUTPUT_A, TypedValue.value(STRING, "red")), null))),
            binding(targetOperation, invocation -> {
                received.set(invocation.inputs());
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })), new RuntimeAuthority("test-authority"));
        CompiledExecutionRunner.ExecutionTemplate resident = runner.prepare(plan, SOURCE_A);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(resident, Map.of(),
            new RuntimeCancellationToken(), null, CorrelationId.random(), System.currentTimeMillis() + 60_000L)
            .toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(Map.of(textPin, TypedValue.value(STRING, "Color: red, {literal}")), received.get());
        assertEquals(1L, runner.templatePreparationCount());
    }

    @Test
    void connectedJsonReplacesThePreparedEmptyObjectDefault() throws Exception {
        String json = "{\"outer\":{\"value\":1}}";
        assertEquals(TypedValue.value(STRING, json), receiveConnectedString("{}", json));
    }

    @Test
    void connectedStringsPreserveTemplateCharactersWithoutRenderingAgain() throws Exception {
        String text = "Hello {name}, {{literal}}, }}";
        assertEquals(TypedValue.value(STRING, text), receiveConnectedString("Configured {name}, {{escaped}}", text));
    }

    private TypedValue receiveConnectedString(String configured, String connected) throws Exception {
        PinId text = PinId.of("text");
        PinId name = PinId.of("name");
        RuntimeOperationDescriptor sourceOperation = operation("connected-string-source", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor targetOperation = operation("connected-string-target", List.of(
            new RuntimeOperationDescriptor.Pin(text, RuntimeOperationDescriptor.Direction.INPUT, STRING)),
            RuntimeSemantics.Cancellation.NONE);
        GraphEndpoint target = new GraphEndpoint(DOWNSTREAM, text);
        GraphConnection connection = new GraphConnection(ConnectionId.interactive(), new GraphEndpoint(SOURCE_A, OUTPUT_A), target);
        CompiledExecutionPlan plan = plan(List.of(
            step(SOURCE_A, sourceOperation, Map.of(), outputBindings(OUTPUT_A, target)),
            step(DOWNSTREAM, targetOperation, Map.of(text, TypedValue.value(STRING, configured),
                name, TypedValue.value(STRING, "Authored")), Map.of())), List.of(connection), List.of());
        AtomicReference<TypedValue> received = new AtomicReference<>();
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry(
            binding(sourceOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success(
                Map.of(OUTPUT_A, TypedValue.value(STRING, connected)), null))),
            binding(targetOperation, invocation -> {
                received.set(invocation.inputs().get(text));
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })), new RuntimeAuthority("test-authority"));

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, runner.execute(runner.prepare(plan, SOURCE_A), Map.of(),
            new RuntimeCancellationToken(), null, CorrelationId.random(), System.currentTimeMillis() + 60_000L)
            .toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        return received.get();
    }

    @Test
    void returnsFailureAndStopsBeforeDownstreamSteps() throws Exception {
        RuntimeOperationDescriptor failureOperation = operation("failure", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, TEXT)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor downstreamOperation = operation("downstream", List.of(
            new RuntimeOperationDescriptor.Pin(DOWNSTREAM_INPUT, RuntimeOperationDescriptor.Direction.INPUT, TEXT)),
            RuntimeSemantics.Cancellation.NONE);
        AtomicBoolean downstreamInvoked = new AtomicBoolean();
        RuntimeBinding failureBinding = binding(failureOperation, invocation ->
            CompletableFuture.completedFuture(RuntimeResult.failure(failure())));
        RuntimeBinding downstreamBinding = binding(downstreamOperation, invocation -> {
            downstreamInvoked.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBindingRegistry registry = registry(failureBinding, downstreamBinding);
        GraphEndpoint target = new GraphEndpoint(DOWNSTREAM, DOWNSTREAM_INPUT);
        GraphConnection connection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000013")),
            new GraphEndpoint(FAILURE_NODE, OUTPUT_A), target);
        CompiledExecutionPlan plan = plan(
            List.of(
                step(DOWNSTREAM, downstreamOperation, Map.of(DOWNSTREAM_INPUT, TypedValue.absent(TEXT)), Map.of()),
                step(FAILURE_NODE, failureOperation, Map.of(), outputBindings(OUTPUT_A, target))),
            List.of(connection), List.of());

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.FAILURE, result.status());
        assertEquals("RUNTIME.HANDLER_FAILURE", result.failure().diagnostic().code());
        assertFalse(downstreamInvoked.get());
    }

    @Test
    void returnsCancelledWhenTheExecutionTokenIsAlreadyCancelled() throws Exception {
        RuntimeOperationDescriptor operation = operation("cancel", List.of(), RuntimeSemantics.Cancellation.COOPERATIVE);
        AtomicBoolean invoked = new AtomicBoolean();
        RuntimeBindingRegistry registry = registry(binding(operation, invocation -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success());
        }));
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of())), List.of(), List.of());
        RuntimeCancellationToken cancellationToken = new RuntimeCancellationToken();
        cancellationToken.cancel();

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
            .execute(plan, Map.of(), cancellationToken).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.CANCELLED, result.status());
        assertEquals("RUNTIME.CANCELLED", result.failure().diagnostic().code());
        assertFalse(invoked.get());
    }

    @Test
    void rejectsAPlanWithAStaleRuntimeBindingFingerprint() {
        RuntimeOperationDescriptor operation = operation("stale", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(binding);
        ProviderLease staleLease = providerLease(binding, hash("stale-fingerprint"));
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of())), List.of(), List.of(staleLease));

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
                .execute(plan).toCompletableFuture().join());

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Compiled Plan Runtime Binding Fingerprint Is Stale: " + operation.key().canonical(), exception.getCause().getMessage());
    }

    @Test
    void rejectsAPlanBoundToADifferentRuntimeProvider() {
        RuntimeOperationDescriptor operation = operation("provider-mismatch", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(binding);
        ContractRef<ProviderId> otherProvider = ContractRef.of(OWNER, ProviderId.of("other-provider"));
        ProviderLease staleLease = providerLease(otherProvider, binding, binding.executionFingerprint());
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of())), List.of(), List.of(staleLease));

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
                .execute(plan).toCompletableFuture().join());

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Compiled Plan Runtime Binding Fingerprint Is Stale: " + operation.key().canonical(), exception.getCause().getMessage());
    }

    @Test
    void rejectsAPlanStepWithAStaleResolvedProviderIdentity() {
        RuntimeOperationDescriptor operation = operation("resolved-provider", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(binding);
        ContractRef<ProviderId> otherProvider = ContractRef.of(OWNER, ProviderId.of("other-provider"));
        RuntimeBindingDescriptor resolved = new RuntimeBindingDescriptor(operation, otherProvider, "1.0.0", true);
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of(), resolved)), List.of(), List.of());

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
                .execute(plan).toCompletableFuture().join());

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Compiled Plan Runtime Binding Provider Is Stale: " + operation.key().canonical(), exception.getCause().getMessage());
    }

    @Test
    void rejectsAPlanStepWithAStaleResolvedBindingDescriptor() {
        RuntimeOperationDescriptor operation = operation("resolved-descriptor", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(binding);
        RuntimeBindingDescriptor resolved = new RuntimeBindingDescriptor(operation, PROVIDER, "2.0.0", true);
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of(), resolved)), List.of(), List.of());

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
                .execute(plan).toCompletableFuture().join());

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Compiled Plan Runtime Binding Descriptor Is Stale: " + operation.key().canonical(), exception.getCause().getMessage());
    }

    @Test
    void closesLeaseWhenExecuteStepFailsSynchronously() {
        RuntimeOperationDescriptor operation = operation("synchronous-failure", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeBinding binding = binding(operation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        RuntimeBindingRegistry registry = registry(binding);
        Map<GraphEndpoint, TypedValue> injectedInputs = new LinkedHashMap<>() {
            @Override
            public void forEach(BiConsumer<? super GraphEndpoint, ? super TypedValue> action) {
                registry.securityRevoke(PROVIDER);
                super.forEach(action);
            }
        };
        CompiledExecutionPlan plan = plan(
            List.of(step(FAILURE_NODE, operation, Map.of(), Map.of())), List.of(), List.of());

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"))
                .execute(plan, injectedInputs, new RuntimeCancellationToken())
                .toCompletableFuture().join());

        assertEquals(RuntimeCapabilityUnavailableException.class, exception.getCause().getClass());
        assertTrue(registry.snapshot().provider(PROVIDER).isEmpty());
    }

    @Test
    void rejectsCyclesBeforeAcquiringRuntimeBindings() {
        RuntimeOperationDescriptor firstOperation = operation("cycle-a", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor secondOperation = operation("cycle-b", List.of(), RuntimeSemantics.Cancellation.NONE);
        GraphConnection first = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000021")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), new GraphEndpoint(SOURCE_B, OUTPUT_B));
        GraphConnection second = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000022")),
            new GraphEndpoint(SOURCE_B, OUTPUT_B), new GraphEndpoint(SOURCE_A, OUTPUT_A));
        CompiledExecutionPlan plan = plan(
            List.of(step(SOURCE_A, firstOperation, Map.of(), Map.of()), step(SOURCE_B, secondOperation, Map.of(), Map.of())),
            List.of(first, second), List.of());

        CompletionException exception = assertThrows(CompletionException.class, () ->
            new CompiledExecutionRunner(RuntimeTestSupport.registry(), new RuntimeAuthority("test-authority"))
                .execute(plan).toCompletableFuture().join());

        assertEquals(IllegalArgumentException.class, exception.getCause().getClass());
        assertEquals("Compiled Execution Plan Contains A Cycle", exception.getCause().getMessage());
    }

    @Test
    void honorsAnExplicitStartNodeAndDoesNotExecuteDisconnectedRoots() throws Exception {
        RuntimeOperationDescriptor firstOperation = operation("start-a", List.of(), RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor secondOperation = operation("start-b", List.of(), RuntimeSemantics.Cancellation.NONE);
        List<String> invoked = new CopyOnWriteArrayList<>();
        RuntimeBinding firstBinding = binding(firstOperation, invocation -> {
            invoked.add("a");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBinding secondBinding = binding(secondOperation, invocation -> {
            invoked.add("b");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        CompiledExecutionPlan plan = plan(
            List.of(step(SOURCE_A, firstOperation, Map.of(), Map.of()), step(SOURCE_B, secondOperation, Map.of(), Map.of())),
            List.of(), List.of());

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry(firstBinding, secondBinding), new RuntimeAuthority("test-authority"))
            .execute(plan, SOURCE_B, Map.of(), new RuntimeCancellationToken()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("b"), invoked);
        assertTrue(result.nodeResults().containsKey(SOURCE_B));
        assertFalse(result.nodeResults().containsKey(SOURCE_A));
    }

    @Test
    void executesOnlyTheActionSelectedByTheSourceEndpointBranch() throws Exception {
        RuntimeOperationDescriptor sourceOperation = operation("branch-source", List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, EXECUTION)),
            RuntimeSemantics.Cancellation.NONE, Set.of("selected", "other"));
        RuntimeOperationDescriptor selectedOperation = operation("selected-action", List.of(
            new RuntimeOperationDescriptor.Pin(EXECUTION_INPUT, RuntimeOperationDescriptor.Direction.INPUT, EXECUTION)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor otherOperation = operation("other-action", List.of(
            new RuntimeOperationDescriptor.Pin(EXECUTION_INPUT, RuntimeOperationDescriptor.Direction.INPUT, EXECUTION)),
            RuntimeSemantics.Cancellation.NONE);
        List<String> invoked = new CopyOnWriteArrayList<>();
        RuntimeBinding sourceBinding = binding(sourceOperation, invocation -> {
            invoked.add("source");
            return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT_A, executionToken()), "selected"));
        });
        RuntimeBinding selectedBinding = binding(selectedOperation, invocation -> {
            invoked.add("selected");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBinding otherBinding = binding(otherOperation, invocation -> {
            invoked.add("other");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        GraphEndpoint selectedTarget = new GraphEndpoint(JOIN, EXECUTION_INPUT);
        GraphEndpoint otherTarget = new GraphEndpoint(SOURCE_B, EXECUTION_INPUT);
        GraphConnection selectedConnection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000031")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A, null, BranchId.of("selected")), selectedTarget);
        GraphConnection otherConnection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000032")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A, null, BranchId.of("other")), otherTarget);
        CompiledExecutionPlan plan = plan(
            List.of(
                step(JOIN, selectedOperation, Map.of(EXECUTION_INPUT, TypedValue.absent(EXECUTION)), Map.of()),
                step(SOURCE_B, otherOperation, Map.of(EXECUTION_INPUT, TypedValue.absent(EXECUTION)), Map.of()),
                step(SOURCE_A, sourceOperation, Map.of(), outputBindings(OUTPUT_A, selectedTarget, otherTarget))),
            List.of(otherConnection, selectedConnection), List.of());

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(
            registry(sourceBinding, selectedBinding, otherBinding), new RuntimeAuthority("test-authority"))
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("source", "selected"), invoked);
        assertEquals(Set.of(SOURCE_A, JOIN), result.nodeResults().keySet());
        assertFalse(result.nodeResults().containsKey(SOURCE_B));
    }

    @ParameterizedTest
    @EnumSource(value = TypedValue.State.class, names = {"ABSENT", "NULL"})
    void doesNotExecuteAFlowActionWithoutARealRoutedToken(TypedValue.State state) throws Exception {
        RuntimeOperationDescriptor sourceOperation = operation("inactive-source-" + state.name().toLowerCase(), List.of(
            new RuntimeOperationDescriptor.Pin(OUTPUT_A, RuntimeOperationDescriptor.Direction.OUTPUT, EXECUTION)),
            RuntimeSemantics.Cancellation.NONE);
        RuntimeOperationDescriptor actionOperation = operation("inactive-action-" + state.name().toLowerCase(), List.of(
            new RuntimeOperationDescriptor.Pin(EXECUTION_INPUT, RuntimeOperationDescriptor.Direction.INPUT, EXECUTION)),
            RuntimeSemantics.Cancellation.NONE);
        AtomicBoolean actionInvoked = new AtomicBoolean();
        TypedValue inactiveValue = state == TypedValue.State.ABSENT
            ? TypedValue.absent(EXECUTION) : TypedValue.nullValue(EXECUTION);
        RuntimeBinding sourceBinding = binding(sourceOperation, invocation ->
            CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT_A, inactiveValue), null)));
        RuntimeBinding actionBinding = binding(actionOperation, invocation -> {
            actionInvoked.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        GraphEndpoint target = new GraphEndpoint(DOWNSTREAM, EXECUTION_INPUT);
        GraphConnection connection = new GraphConnection(
            ConnectionId.of(UUID.fromString("00000000-0000-4000-8000-000000000033")),
            new GraphEndpoint(SOURCE_A, OUTPUT_A), target);
        CompiledExecutionPlan plan = plan(
            List.of(
                step(DOWNSTREAM, actionOperation, Map.of(EXECUTION_INPUT, TypedValue.absent(EXECUTION)), Map.of()),
                step(SOURCE_A, sourceOperation, Map.of(), outputBindings(OUTPUT_A, target))),
            List.of(connection), List.of());

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(
            registry(sourceBinding, actionBinding), new RuntimeAuthority("test-authority"))
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertFalse(actionInvoked.get());
        assertEquals(Set.of(SOURCE_A), result.nodeResults().keySet());
        assertEquals(inactiveValue, result.outputs().get(new GraphEndpoint(SOURCE_A, OUTPUT_A)));
    }

    private static RuntimeBindingRegistry registry(RuntimeBinding... bindings) {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(bindings));
        return registry;
    }

    private static RuntimeBinding binding(RuntimeOperationDescriptor operation, RuntimeOperationHandler handler) {
        return RuntimeBinding.available(operation, PROVIDER, "1.0.0", handler);
    }

    private static RuntimeOperationDescriptor operation(String id, List<RuntimeOperationDescriptor.Pin> pins,
                                                        RuntimeSemantics.Cancellation cancellation) {
        return operation(id, pins, cancellation, Set.of("success"));
    }

    private static RuntimeOperationDescriptor operation(String id, List<RuntimeOperationDescriptor.Pin> pins,
                                                        RuntimeSemantics.Cancellation cancellation,
                                                        Set<String> successBranches) {
        return new RuntimeOperationDescriptor(
            ContractRef.of(OWNER, CapabilityId.of("capability-" + id)),
            ContractRef.of(OWNER, OperationId.of("operation-" + id)),
            pins, semantics(cancellation, successBranches));
    }

    private static RuntimeSemantics semantics(RuntimeSemantics.Cancellation cancellation, Set<String> successBranches) {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")),
            cancellation,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            successBranches,
            Set.of("failure"),
            cancellation == RuntimeSemantics.Cancellation.NONE ? Set.of() : Set.of("cancelled"),
            new RuntimeFailureContract(FAILURE, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static CompiledExecutionStep step(NodeInstanceId nodeId, RuntimeOperationDescriptor operation,
                                              Map<PinId, TypedValue> inputs,
                                              Map<PinId, List<GraphEndpoint>> outputs) {
        return step(nodeId, operation, inputs, outputs, null);
    }

    private static CompiledExecutionStep step(NodeInstanceId nodeId, RuntimeOperationDescriptor operation,
                                              Map<PinId, TypedValue> inputs,
                                              Map<PinId, List<GraphEndpoint>> outputs,
                                              RuntimeBindingDescriptor resolvedBinding) {
        UUID stepId = UUID.nameUUIDFromBytes(("step:" + nodeId.canonicalText()).getBytes(StandardCharsets.UTF_8));
        CatalogNodeDescriptor.Handler handler = new CatalogNodeDescriptor.Handler(operation.capability(), operation.operation());
        return new CompiledExecutionStep(
            stepId,
            nodeId,
            ContractRef.of(OWNER, NodeId.of("node-" + operation.operation().id().canonicalText())),
            handler,
            inputs,
            outputs,
            operation.semantics(),
            resolvedBinding,
            OpaqueData.empty());
    }

    private static CompiledExecutionPlan plan(List<CompiledExecutionStep> steps, List<GraphConnection> connections,
                                              List<ProviderLease> providerLeases) {
        return new CompiledExecutionPlan(
            PLAN_ID,
            RESOURCE,
            1,
            CATALOG_BINDING,
            GRAPH_HASH,
            steps,
            connections,
            List.of(),
            List.of(),
            List.of(),
            providerLeases,
            OpaqueData.empty());
    }

    private static Map<PinId, List<GraphEndpoint>> outputBindings(PinId pin, GraphEndpoint... targets) {
        return Map.of(pin, List.of(targets));
    }

    private static Map<PinId, List<GraphEndpoint>> outputBindings(PinId pin) {
        return outputBindings(pin, new GraphEndpoint(JOIN, JOIN_OUTPUT));
    }

    private static Map<PinId, TypedValue> inputs(PinId first, PinId second) {
        Map<PinId, TypedValue> inputs = new LinkedHashMap<>();
        inputs.put(first, TypedValue.absent(TEXT));
        inputs.put(second, TypedValue.absent(TEXT));
        return inputs;
    }

    private static ProviderLease providerLease(RuntimeBinding binding, ContentHash fingerprint) {
        return providerLease(PROVIDER, binding, fingerprint);
    }

    private static ProviderLease providerLease(ContractRef<ProviderId> provider, RuntimeBinding binding, ContentHash fingerprint) {
        return new ProviderLease(
            LeaseId.deterministic("runner-test-" + binding.key().canonical()),
            provider,
            binding.key().capability(),
            binding.key().operation(),
            fingerprint,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeFailure failure() {
        return new RuntimeFailure(diagnostic(), false, TypedValue.value(FAILURE, "failed"));
    }

    private static Diagnostic diagnostic() {
        return Diagnostic.builder(
                "RUNTIME.HANDLER_FAILURE",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "execution")
            .messageKey(ContractRef.of(OWNER, CapabilityId.of("runtime-handler-failure")))
            .message("The handler failed while executing the node.")
            .remediation("Inspect the correlated handler failure.")
            .correlationId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .build();
    }

    private static TypedValue value(String value) {
        return TypedValue.value(TEXT, value);
    }

    private static TypedValue executionToken() {
        return TypedValue.value(EXECUTION, true);
    }

    private static ContentHash hash(String value) {
        return ContentHash.of(CanonicalJson.sha256("runner-test", Map.of("value", value)));
    }
}
