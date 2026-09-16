package restudio.resync.migration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationFenceTest {
    @Test
    void mutationLeaseCanBeClosedByItsAsyncCompletionThread() throws Exception {
        MigrationFence fence = new MigrationFence();
        MigrationFence.MutationLease mutation = fence.beginMutation();
        CompletableFuture.runAsync(mutation::close).get(2, TimeUnit.SECONDS);
        try (MigrationFence.MigrationLease ignored = fence.tryAcquireMigration(Duration.ofSeconds(2)).orElseThrow()) {
            assertEquals(0, fence.activeMutationCount());
        }
    }

    @Test
    void nestedMutationLeasesKeepTheSharedReadStampUntilEveryLeaseCloses() throws Exception {
        MigrationFence fence = new MigrationFence();
        MigrationFence.MutationLease outer = fence.beginMutation();
        MigrationFence.MutationLease nested = fence.beginMutation();

        CompletableFuture.runAsync(outer::close).get(2, TimeUnit.SECONDS);

        assertEquals(1, fence.activeMutationCount());
        assertTrue(fence.tryAcquireMigration(Duration.ZERO).isEmpty());

        nested.close();
        try (MigrationFence.MigrationLease ignored = fence.tryAcquireMigration(Duration.ofSeconds(2)).orElseThrow()) {
            assertEquals(0, fence.activeMutationCount());
        }
    }

    @Test
    void nestedMigrationLeasesKeepTheExclusiveStampUntilEveryLeaseCloses() throws Exception {
        MigrationFence fence = new MigrationFence();
        MigrationFence.MigrationLease outer = fence.acquireMigration();
        MigrationFence.MigrationLease nested = fence.tryAcquireMigration(Duration.ZERO).orElseThrow();

        nested.close();
        assertTrue(fence.migrationActive());
        assertTrue(fence.tryBeginMutation(Duration.ZERO).isEmpty());

        outer.close();
        try (MigrationFence.MutationLease ignored = fence.tryBeginMutation(Duration.ofSeconds(2)).orElseThrow()) {
            assertFalse(fence.migrationActive());
        }
    }

    @Test
    void migrationWaiterStopsNewMutationsUntilTheExclusiveLeaseCompletes() throws Exception {
        MigrationFence fence = new MigrationFence();
        MigrationFence.MutationLease mutation = fence.beginMutation();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch migrationEntered = new CountDownLatch(1);
        CountDownLatch releaseMigration = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            writerStarted.countDown();
            try (MigrationFence.MigrationLease ignored = fence.acquireMigration()) {
                migrationEntered.countDown();
                releaseMigration.await();
            } catch (Throwable failure) {
                writerFailure.set(failure);
            }
        });
        writer.start();
        assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
        awaitWaiting(writer);

        CompletableFuture<Boolean> candidate = CompletableFuture.supplyAsync(() -> {
            try (MigrationFence.MutationLease ignored = fence.tryBeginMutation(Duration.ofMillis(100)).orElse(null)) {
                return ignored != null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        assertFalse(candidate.get(2, TimeUnit.SECONDS));

        mutation.close();
        assertTrue(migrationEntered.await(2, TimeUnit.SECONDS));
        assertTrue(fence.migrationActive());
        releaseMigration.countDown();
        writer.join(2_000);
        assertFalse(writer.isAlive());
        assertNull(writerFailure.get());
        try (MigrationFence.MutationLease ignored = fence.tryBeginMutation(Duration.ofSeconds(2)).orElseThrow()) {
            assertFalse(fence.migrationActive());
        }
    }

    @Test
    void migrationLeaseRejectsCrossThreadCloseButRemainsUsableByItsOwner() throws Exception {
        MigrationFence fence = new MigrationFence();
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            try {
                migration.close();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        }).get(2, TimeUnit.SECONDS);

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(fence.migrationActive());
        migration.close();
        migration.close();
        assertFalse(fence.migrationActive());
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
    }
}
