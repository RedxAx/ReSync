package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeExecutionBarrierTest {
    @Test
    void admissionTracksActiveWorkAndReleasesIdempotently() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();

        RuntimeExecutionBarrier.Admission admission = barrier.acquire();

        assertEquals(1, barrier.activeCount());
        assertFalse(admission.isClosed());

        admission.close();
        admission.close();

        assertTrue(admission.isClosed());
        assertEquals(0, barrier.activeCount());
    }

    @Test
    void fencingRejectsNewAdmissionsAndSignalsOnlyAfterExistingWorkReleases() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();
        RuntimeExecutionBarrier.Admission admission = barrier.acquire();

        assertTrue(barrier.fence());
        assertFalse(barrier.fence());
        assertTrue(barrier.isFenced());
        assertTrue(barrier.tryAcquire().isEmpty());
        CompletionStage<Void> signal = barrier.drainSignal();
        assertFalse(signal.toCompletableFuture().isDone());
        assertTrue(signal.toCompletableFuture().complete(null));
        assertEquals(RuntimeExecutionBarrier.DrainStatus.TIMED_OUT, barrier.drain(Duration.ZERO).status());

        admission.close();

        assertTrue(signal.toCompletableFuture().isDone());
        assertEquals(0, barrier.activeCount());
        assertTrue(barrier.drain(Duration.ZERO).drained());
    }

    @Test
    void drainReturnsWithinTheBoundWhenWorkDoesNotRelease() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();
        RuntimeExecutionBarrier.Admission admission = barrier.acquire();

        RuntimeExecutionBarrier.DrainResult result = barrier.drain(Duration.ofMillis(25));

        assertEquals(RuntimeExecutionBarrier.DrainStatus.TIMED_OUT, result.status());
        assertEquals(1, result.activeCount());
        assertTrue(result.elapsedMillis() < 1_000);
        assertTrue(barrier.tryAcquire().isEmpty());

        admission.close();
        assertTrue(barrier.drain(Duration.ZERO).drained());
    }

    @Test
    void drainSignalCannotBeObservedBeforeFence() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();

        assertThrows(IllegalStateException.class, barrier::drainSignal);
        assertThrows(IllegalArgumentException.class, () -> barrier.drain(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> barrier.drain(null));
    }

    @Test
    void overflowingDurationFailsInsteadOfClamping() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();
        RuntimeExecutionBarrier.Admission admission = barrier.acquire();

        assertThrows(IllegalArgumentException.class, () -> barrier.drain(Duration.ofSeconds(Long.MAX_VALUE)));

        admission.close();
    }

    @Test
    void overflowingMillisecondDeadlineFailsInsteadOfClamping() {
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeDeadline.deadlineMillisExact(Long.MAX_VALUE - 1, 2));
    }

    @Test
    void acquireFailsAfterFence() {
        RuntimeExecutionBarrier barrier = new RuntimeExecutionBarrier();

        barrier.fence();

        assertThrows(IllegalStateException.class, barrier::acquire);
        Optional<RuntimeExecutionBarrier.Admission> admission = barrier.tryAcquire();
        assertTrue(admission.isEmpty());
    }
}
