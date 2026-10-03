package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
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
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionObservationTest {
    private static final OwnerId OWNER = OwnerId.of("observation-test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("provider"));
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("observed-node");
    private static final PinId OUTPUT = PinId.of("value");
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void cancelledLogicalResultKeepsPhysicalCompletionPendingUntilTheHandlerEnds() throws Exception {
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        Fixture fixture = fixture(pending);
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        CompiledExecutionRunner.ExecutionHandle execution = fixture.runner().executeObserved(fixture.template(), Map.of(),
            cancellation, null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);

        assertTrue(cancellation.cancel());
        assertEquals(CompiledExecutionRunner.Status.CANCELLED, execution.result().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertFalse(execution.physicalCompletion().toCompletableFuture().isDone());
        assertEquals(RuntimeUnloadResult.Status.BLOCKED, fixture.registry().tryUnload(PROVIDER).status());

        pending.complete(RuntimeResult.success(Map.of(OUTPUT, TypedValue.value(TEXT, "physical-end")), "success"));
        execution.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry().tryUnload(PROVIDER).status());
    }

    @Test
    void observedResultPreservesTheActualTypedOutputsAndSelectedBranch() throws Exception {
        TypedValue value = TypedValue.value(TEXT, "actual-output");
        Fixture fixture = fixture(CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT, value), "success")));
        CompiledExecutionRunner.ExecutionHandle execution = fixture.runner().executeObserved(fixture.template(), Map.of(),
            new RuntimeCancellationToken(), null, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);

        CompiledExecutionRunner.ExecutionResult result = execution.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        execution.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(value, result.nodeResults().get(NODE).outputs().get(OUTPUT));
        assertEquals("success", result.nodeResults().get(NODE).branch());
        assertEquals(value, result.outputs().get(new GraphEndpoint(NODE, OUTPUT)));
    }

    @Test
    void closingAnUnusedLeasePublishesPhysicalCompletionOnlyAfterItsOwnerReleasesIt() throws Exception {
        Fixture fixture = fixture(new CompletableFuture<>());
        RuntimePlanLease lease = fixture.registry().acquire(RuntimeTestSupport.input(new RuntimeLeaseInput.BindingRequirement(
            fixture.binding().key(), fixture.binding().executionFingerprint(), List.of(), List.of(OUTPUT))));

        assertFalse(lease.physicalCompletion().toCompletableFuture().isDone());
        lease.close();
        lease.physicalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(lease.isReleased());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry().tryUnload(PROVIDER).status());
    }

    private static Fixture fixture(CompletableFuture<RuntimeResult> pending) {
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.COOPERATIVE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of("success"), Set.of("failure"), Set.of("cancelled"),
            new RuntimeFailureContract(TEXT, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(ContractRef.of(OWNER, CapabilityId.of("observed")),
            ContractRef.of(OWNER, OperationId.of("execute")), List.of(new RuntimeOperationDescriptor.Pin(OUTPUT.canonicalText(),
                RuntimeOperationDescriptor.Direction.OUTPUT, TEXT)), semantics);
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", ignored -> pending);
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        CompiledExecutionStep step = new CompiledExecutionStep(UUID.randomUUID(), NODE, ContractRef.of(OWNER, NodeId.of("observed")),
            new CatalogNodeDescriptor.Handler(operation.capability(), operation.operation()), Map.of(), Map.of(OUTPUT, List.of()),
            semantics, OpaqueData.empty());
        ContentHash hash = ContentHash.of("1".repeat(64));
        CompiledExecutionPlan plan = new CompiledExecutionPlan(UUID.randomUUID(), new ServerResourceLocator(
            ServerId.deterministic("observed-server"), ContractRef.of(OWNER, ResourceTypeId.of("flow")), "observed"),
            1, new CatalogBinding(1, hash, hash), hash, List.of(step), List.of(), List.of(), OpaqueData.empty());
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry, new RuntimeAuthority("test-authority"));
        return new Fixture(registry, binding, runner, runner.prepare(plan, NODE));
    }

    private record Fixture(RuntimeBindingRegistry registry, RuntimeBinding binding, CompiledExecutionRunner runner,
                           CompiledExecutionRunner.ExecutionTemplate template) {}
}
