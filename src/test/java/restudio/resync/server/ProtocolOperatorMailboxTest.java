package restudio.resync.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolOperatorMailboxTest {
    @Test
    void cancellingAReceiptDoesNotReleaseRunningEffectsOrShutdownDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean effect = new AtomicBoolean();
        try (ProtocolEnvelopeMailbox mailbox = mailbox()) {
            CompletableFuture<Void> receipt = mailbox.submitOperator(10, () -> {
                entered.countDown();
                await(release);
                effect.set(true);
            }).toCompletableFuture();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                receipt.cancel(true);
                mailbox.closeAdmission();
                assertFalse(mailbox.whenIdle().isDone());
                assertFalse(effect.get());
                assertThrows(ExecutionException.class, () -> mailbox.submitOperator(10, () -> {}).toCompletableFuture().get());
            } finally {
                release.countDown();
            }
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertTrue(effect.get());
        }
    }

    @Test
    void shutdownRunsAcceptedQueuedWorkBeforeReleasingPhysicalOwnership() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean effect = new AtomicBoolean();
        try (ProtocolEnvelopeMailbox mailbox = mailbox()) {
            CompletableFuture<Void> running = mailbox.submitOperator(10, () -> {
                entered.countDown();
                await(release);
            }).toCompletableFuture();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                CompletableFuture<Void> queued = mailbox.submitOperator(10, () -> effect.set(true)).toCompletableFuture();
                mailbox.shutdown();
                assertFalse(mailbox.whenIdle().isDone());
                assertFalse(queued.isDone());
                release.countDown();
                running.get(2, TimeUnit.SECONDS);
                queued.get(2, TimeUnit.SECONDS);
                mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertTrue(effect.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void errorsSettleAdmissionSoSubsequentWorkCanRun() throws Exception {
        try (ProtocolEnvelopeMailbox mailbox = mailbox()) {
            assertThrows(ExecutionException.class, () -> mailbox.submitOperator(10, () -> {
                throw new AssertionError("Effect failed");
            }).toCompletableFuture().get(2, TimeUnit.SECONDS));
            AtomicBoolean effect = new AtomicBoolean();
            mailbox.submitOperator(10, () -> effect.set(true)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertTrue(effect.get());
        }
    }

    private static ProtocolEnvelopeMailbox mailbox() {
        return new ProtocolEnvelopeMailbox(new ProtocolEnvelopeDispatchBoundary((connection, session, request) ->
            ProtocolEnvelopeDispatchResult.accepted()), new ProtocolEnvelopeMailbox.Limits(1, 4, 4096, 4, 4096, 1024));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Physical operation was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
