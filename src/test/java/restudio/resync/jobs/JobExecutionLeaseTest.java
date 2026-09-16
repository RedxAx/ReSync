package restudio.resync.jobs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobExecutionLeaseTest {
    @Test
    void cancellationRequestedBeforeRegistrationIsDeliveredExactlyOnce() {
        JobExecutionLease lease = new JobExecutionLease();
        AtomicInteger cancellations = new AtomicInteger();

        lease.requestCancellation();
        lease.requestCancellation();
        lease.setCancellation(cancellations::incrementAndGet);
        lease.requestCancellation();

        assertEquals(1, cancellations.get());
    }

    @Test
    void registeredCancellationIsDeliveredExactlyOnce() {
        JobExecutionLease lease = new JobExecutionLease();
        AtomicInteger cancellations = new AtomicInteger();
        lease.setCancellation(cancellations::incrementAndGet);

        lease.requestCancellation();
        lease.requestCancellation();

        assertEquals(1, cancellations.get());
    }
}
