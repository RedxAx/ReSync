package restudio.resync.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncShutdownDeadlineTest {
    @Test
    void aRetainedShutdownHasABoundedWaitWithoutCancellingPhysicalWork() {
        CompletableFuture<Void> physical = new CompletableFuture<>();

        IllegalStateException failure = assertTimeout(Duration.ofSeconds(2), () -> assertThrows(IllegalStateException.class,
            () -> ReSyncServer.awaitShutdown(physical, Duration.ofMillis(20))));

        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertFalse(physical.isDone());
        physical.complete(null);
        ReSyncServer.awaitShutdown(physical, Duration.ofMillis(20));
        assertTrue(physical.isDone());
    }

    @Test
    void failedCleanupPreservesItsFailureInsteadOfWaitingForever() {
        CompletableFuture<Void> physical = CompletableFuture.failedFuture(new IllegalStateException("Persistence Could Not Quiesce"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ReSyncServer.awaitShutdown(physical, Duration.ofSeconds(1)));

        assertTrue(failure.getCause().getCause().getMessage().contains("Could Not Quiesce"));
    }
}
