package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeExecutionContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowContextCancellationTest {
    @Test
    void cancelledWallClockDelaySettlesWithoutWaitingForItsTimer() throws Exception {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            CompletableFuture<Void> completion = context(executor, cancellation)
                .runAfterMillisBeforeContinuation(() -> ran.set(true), 10_000L);
            cancellation.cancel();
            assertThrows(Exception.class, () -> completion.get(2, TimeUnit.SECONDS));
            assertTrue(completion.isCancelled());
            assertFalse(ran.get());
        } finally {
            executor.shutdown();
        }
    }

    private FlowContext context(FlowExecutor executor, RuntimeCancellationToken cancellation) {
        return new FlowContext(null, null, null, null, executor, null, null, null, null,
            RuntimeExecutionContext.NO_DEADLINE, null, cancellation);
    }
}
