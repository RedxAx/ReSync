package restudio.resync.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncShutdownCoordinatorTest {
    @Test
    void retrySignalBeforeFailurePublicationIsCoalescedUntilReplacementLeaseReleases() {
        CompletableFuture<Boolean> firstNetwork = new CompletableFuture<>();
        CompletableFuture<Void> retryCompletion = new CompletableFuture<>();
        AtomicBoolean replacementLease = new AtomicBoolean(true);
        AtomicInteger initialAttempts = new AtomicInteger();
        AtomicInteger retryAttempts = new AtomicInteger();
        ReSyncShutdownCoordinator coordinator = new ReSyncShutdownCoordinator(
            () -> {
                initialAttempts.incrementAndGet();
                return firstNetwork.thenApply(completed -> {
                    replacementLease.set(false);
                    return completed;
                });
            },
            () -> {
                retryAttempts.incrementAndGet();
                assertFalse(replacementLease.get());
                return retryCompletion;
            },
            () -> {
            },
            () -> CompletableFuture.completedFuture(null));

        var first = coordinator.continueShutdown(true).toCompletableFuture();
        var coalesced = coordinator.continueShutdown(false).toCompletableFuture();

        assertSame(first, coalesced);
        assertEquals(1, initialAttempts.get());
        assertEquals(0, retryAttempts.get());
        firstNetwork.complete(false);

        assertFalse(first.isDone());
        assertEquals(1, retryAttempts.get());
        retryCompletion.complete(null);

        assertTrue(first.isDone());
        assertFalse(first.isCompletedExceptionally());
        assertFalse(replacementLease.get());
    }

    @Test
    void initialSuccessFinishesCoreBeforeCompletionPublishes() {
        CompletableFuture<Void> coreCompletion = new CompletableFuture<>();
        AtomicInteger finishCalls = new AtomicInteger();
        ReSyncShutdownCoordinator coordinator = new ReSyncShutdownCoordinator(
            () -> CompletableFuture.completedFuture(true),
            () -> CompletableFuture.completedFuture(null),
            finishCalls::incrementAndGet,
            () -> coreCompletion);

        var completion = coordinator.continueShutdown(true).toCompletableFuture();

        assertEquals(1, finishCalls.get());
        assertFalse(completion.isDone());
        coreCompletion.complete(null);

        assertTrue(completion.isDone());
        assertFalse(completion.isCompletedExceptionally());
    }
}
