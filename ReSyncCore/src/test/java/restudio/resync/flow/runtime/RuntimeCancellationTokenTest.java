package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aFinishedChildAndRemovedCallbackDoNotReceiveLaterCancellation() throws Exception {
        RuntimeCancellationToken parent = new RuntimeCancellationToken();
        RuntimeCancellationToken child = parent.child();
        AtomicBoolean called = new AtomicBoolean();
        RuntimeCancellationToken.Registration registration = parent.onCancel(() -> called.set(true));
        child.finish();
        registration.close();

        parent.cancel();
        parent.cancelled().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertFalse(child.isCancelled());
        assertFalse(called.get());
    }

    @Test
    void deadlineExpiryCancelsTheTokenAndRejectsUse() throws Exception {
        RuntimeCancellationToken token = new RuntimeCancellationToken(System.currentTimeMillis() + 20);

        token.cancelled().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(token.isCancelled());
        assertThrows(RuntimeOperationCancelledException.class, token::throwIfCancelled);
    }
}
