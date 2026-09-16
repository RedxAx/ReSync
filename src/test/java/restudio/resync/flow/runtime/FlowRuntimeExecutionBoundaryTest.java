package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeExecutionBoundaryTest {
    @Test
    void correctThreadAffinityExecutesInlineAndOppositeAffinityRemainsQueued() {
        for (boolean initiallyMain : List.of(true, false)) {
            AtomicBoolean primary = new AtomicBoolean(initiallyMain);
            ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
            ArrayDeque<Runnable> asyncQueue = new ArrayDeque<>();
            FlowRuntimeExecutionBoundary boundary = new FlowRuntimeExecutionBoundary(mainQueue::add, asyncQueue::add, primary::get);
            RuntimeExecutionContext current = context(initiallyMain ? RuntimeSemantics.ThreadMode.MAIN : RuntimeSemantics.ThreadMode.ASYNCHRONOUS);
            AtomicBoolean called = new AtomicBoolean();
            var inline = boundary.execute(current, invocation(current), ignored -> {
                called.set(true);
                return CompletableFuture.completedFuture(RuntimeResult.success());
            });
            assertTrue(inline.toCompletableFuture().isDone());
            assertTrue(called.get());
            assertTrue(mainQueue.isEmpty());
            assertTrue(asyncQueue.isEmpty());

            RuntimeExecutionContext opposite = context(initiallyMain ? RuntimeSemantics.ThreadMode.ASYNCHRONOUS : RuntimeSemantics.ThreadMode.MAIN);
            called.set(false);
            var queued = boundary.execute(opposite, invocation(opposite), ignored -> {
                called.set(true);
                return CompletableFuture.completedFuture(RuntimeResult.success());
            });
            assertFalse(queued.toCompletableFuture().isDone());
            assertFalse(called.get());
            ArrayDeque<Runnable> queue = initiallyMain ? asyncQueue : mainQueue;
            assertEquals(1, queue.size());
            primary.set(!initiallyMain);
            queue.removeFirst().run();
            queued.toCompletableFuture().join();
            assertTrue(called.get());
        }
    }

    @Test
    void aQueuedCancelledInvocationNeverReachesItsHandler() {
        AtomicBoolean primary = new AtomicBoolean(false);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        FlowRuntimeExecutionBoundary boundary = new FlowRuntimeExecutionBoundary(queue::add, queue::add, primary::get);
        RuntimeExecutionContext context = context(RuntimeSemantics.ThreadMode.MAIN);
        RuntimeInvocation invocation = invocation(context);
        AtomicBoolean called = new AtomicBoolean();
        var result = boundary.execute(context, invocation, ignored -> {
            called.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        invocation.cancellationToken().cancel();
        primary.set(true);
        queue.removeFirst().run();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertFalse(called.get());
    }

    private RuntimeInvocation invocation(RuntimeExecutionContext context) {
        return new RuntimeInvocation(context.descriptor().key(), "boundary-test", new RuntimeCancellationToken());
    }

    private RuntimeExecutionContext context(RuntimeSemantics.ThreadMode thread) {
        OwnerId owner = OwnerId.of("resync.test");
        ContractRef<CapabilityId> capability = ContractRef.of(owner, CapabilityId.of("boundary"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, thread, capability,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "string")), Set.of("RUNTIME.FAILURE"),
                Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability,
            ContractRef.of(owner, OperationId.of("boundary")), List.of(), semantics);
        return new RuntimeExecutionContext(new RuntimeBindingDescriptor(operation, ContractRef.of(owner, ProviderId.of("boundary")),
            "1.0.0", true), RuntimeAuthority.anonymous());
    }
}
