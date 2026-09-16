package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowModuleDeferredOutputTest {
    @Test
    void includesDeferredOutputAddedBeforePendingCompletion() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        List<String> deferredOutputs = new CopyOnWriteArrayList<>();
        CompletableFuture<List<String>> result = FlowModule.completeLegacyOutputCapture(
            List.of("synchronous"), deferredOutputs, () -> List.of(pending), List::copyOf);

        assertFalse(result.isDone());

        deferredOutputs.add("deferred");
        pending.complete(null);

        assertEquals(List.of("synchronous", "deferred"), result.join());
    }

    @Test
    void waitsForPendingOperationRegisteredWhileCapturingDeferredOutput() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>(List.of(first));
        List<String> deferredOutputs = new CopyOnWriteArrayList<>();
        CompletableFuture<List<String>> result = FlowModule.completeLegacyOutputCapture(
            List.of("synchronous"), deferredOutputs, () -> pending, List::copyOf);

        pending.add(second);
        first.complete(null);

        assertFalse(result.isDone());

        deferredOutputs.add("deferred");
        second.complete(null);

        assertEquals(List.of("synchronous", "deferred"), result.join());
    }

    @Test
    void waitsForNestedPendingOperationAfterInitialFailure() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        CompletableFuture<Void> nested = new CompletableFuture<>();
        List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>(List.of(first, second));
        List<String> deferredOutputs = new CopyOnWriteArrayList<>();
        CompletableFuture<List<String>> result = FlowModule.completeLegacyOutputCapture(
            List.of("synchronous"), deferredOutputs, () -> pending, List::copyOf);
        IllegalStateException failure = new IllegalStateException("initial failure");

        pending.add(nested);
        first.completeExceptionally(failure);
        second.complete(null);

        assertFalse(result.isDone());

        nested.complete(null);

        CompletionException completion = assertThrows(CompletionException.class, result::join);
        assertSame(failure, completion.getCause());
    }
}
