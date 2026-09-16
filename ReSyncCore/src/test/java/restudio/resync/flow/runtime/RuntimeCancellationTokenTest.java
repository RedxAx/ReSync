package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCancellationTokenTest {
    @Test
    void cancellationCallbacksPropagateOutsideTheCancellingCall() throws Exception {
        RuntimeCancellationToken parent = new RuntimeCancellationToken(System.currentTimeMillis() + 1_000);
        RuntimeCancellationToken child = parent.child(System.currentTimeMillis() + 2_000);
        CompletableFuture<Boolean> callback = new CompletableFuture<>();
        child.cancelled().thenRun(() -> callback.complete(child.isCancelled()));

        assertTrue(parent.cancel());
        assertTrue(callback.get(2, TimeUnit.SECONDS));
        assertEquals(parent.deadlineMillis(), child.deadlineMillis());
    }

    @Test
    void deadlineExpiryCancelsTheTokenAndRejectsUse() throws Exception {
        RuntimeCancellationToken token = new RuntimeCancellationToken(System.currentTimeMillis() + 20);

        token.cancelled().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(token.isCancelled());
        assertThrows(RuntimeOperationCancelledException.class, token::throwIfCancelled);
    }
}
