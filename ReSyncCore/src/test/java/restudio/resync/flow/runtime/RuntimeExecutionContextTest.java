package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeExecutionContextTest {
    @Test
    void declaredExecutionHooksRejectWrongThreadEffectResourceAndCommit() {
        RuntimeExecutionContext context = new RuntimeExecutionContext(
            new RuntimeBindingDescriptor(descriptor(), provider(), "1.0.0", true),
            new RuntimeAuthority("authority"));
        ServerResourceLocator allowed = new ServerResourceLocator(
            new ServerId(UUID.randomUUID()),
            ContractRef.of(new OwnerId("test"), ResourceTypeId.of("allowed")),
            "one");
        ServerResourceLocator denied = new ServerResourceLocator(
            new ServerId(UUID.randomUUID()),
            ContractRef.of(new OwnerId("test"), ResourceTypeId.of("denied")),
            "one");

        context.requireThread(RuntimeSemantics.ThreadMode.CURRENT);
        context.requireEffect(RuntimeSemantics.Effect.STATE_MUTATING);
        context.requireCommitBoundary(RuntimeFailureContract.CommitBoundary.ATOMIC);
        context.requireRead(allowed);
        context.beginCommit();
        context.completeCommit();
        assertThrows(IllegalStateException.class, () -> context.requireThread(RuntimeSemantics.ThreadMode.MAIN));
        assertThrows(IllegalStateException.class, () -> context.requireEffect(RuntimeSemantics.Effect.PURE));
        assertThrows(IllegalStateException.class, () -> context.requireCommitBoundary(RuntimeFailureContract.CommitBoundary.NO_MUTATION));
        assertThrows(IllegalStateException.class, () -> context.requireRead(denied));
    }

    @Test
    void handlerViewCarriesThePolicyContextAndRejectsWrongBinding() throws Exception {
        RuntimeOperationDescriptor descriptor = operation();
        RuntimeExecutionContext context = new RuntimeExecutionContext(
            new RuntimeBindingDescriptor(descriptor, provider(), "1.0.0", true),
            new RuntimeAuthority("authority"));
        RuntimeOperationHandler handler = context.handlerView(invocation -> {
            assertEquals(context, invocation.executionContext());
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeInvocation invocation = new RuntimeInvocation(
            descriptor.key(), "operation", new RuntimeCancellationToken());

        assertEquals(RuntimeResult.Status.SUCCESS, handler.execute(invocation).toCompletableFuture().get().status());
        assertThrows(IllegalStateException.class, () -> handler.execute(new RuntimeInvocation(
            new RuntimeBindingKey(capability("other"), operationId("other")),
            "operation",
            new RuntimeCancellationToken())));
    }

    @Test
    void effectiveDeadlineIsPropagatedThroughContextInvocationAndToken() {
        long deadline = System.currentTimeMillis() + 1_000;
        RuntimeExecutionContext context = new RuntimeExecutionContext(
            new RuntimeBindingDescriptor(descriptor(), provider(), "1.0.0", true),
            new RuntimeAuthority("authority"),
            deadline);
        RuntimeInvocation invocation = new RuntimeInvocation(
            descriptor().key(), "deadline", new RuntimeCancellationToken(deadline)).withExecutionContext(context);

        assertEquals(deadline, context.deadlineMillis());
        assertEquals(deadline, invocation.deadlineMillis());
        assertTrue(context.remainingMillis() <= 1_000);
    }

    private static RuntimeOperationDescriptor descriptor() {
        return new RuntimeOperationDescriptor(
            capability("capability"), operationId("operation"), List.of(), semantics());
    }

    private static RuntimeOperationDescriptor operation() {
        return new RuntimeOperationDescriptor(
            capability("capability"), operationId("operation"), List.of(), semantics());
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_MUTATING,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability("authorization"),
            RuntimeSemantics.Cancellation.COOPERATIVE,
            1_000,
            100,
            2_000,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of("cancelled"),
            new RuntimeFailureContract(
                type("failure"),
                Set.of("RUNTIME.HANDLER_FAILURE"),
                Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.ATOMIC),
            Set.of(resourceType("allowed")),
            Set.of(resourceType("allowed")));
    }

    private static ContractRef<ProviderId> provider() {
        return ContractRef.of(new OwnerId("test"), new ProviderId("provider"));
    }

    private static ContractRef<CapabilityId> capability(String localId) {
        return ContractRef.of(new OwnerId("test"), CapabilityId.of(localId));
    }

    private static ContractRef<OperationId> operationId(String localId) {
        return ContractRef.of(new OwnerId("test"), OperationId.of(localId));
    }

    private static ContractRef<ResourceTypeId> resourceType(String localId) {
        return ContractRef.of(new OwnerId("test"), ResourceTypeId.of(localId));
    }

    private static TypeExpr type(String localId) {
        return TypeExpr.named(TypeReference.of("test", localId));
    }
}
