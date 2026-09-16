package restudio.resync.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTransactionCoordinatorCommittedViewTest {
    @TempDir
    Path directory;

    @Test
    void committedViewPublishesBeforeListenerCompletionAndTracksDeleteAndClose() throws Exception {
        Path root = directory.resolve("assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("tab", "main");
        Path relativePath = Path.of("Customization/Tabs/main.json");
        UUID createMutation = UUID.fromString("90000000-0000-4000-8000-000000000001");
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson());
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(coordinator.committedAsset(key).isEmpty());
            assertEquals(0L, coordinator.committedSequence());
            coordinator.addListener(result -> {
                listenerEntered.countDown();
                await(releaseListener);
            });
            Future<AssetTransactionCoordinator.TransactionResult> create = executor.submit(() -> coordinator.transact(
                request(coordinator, createMutation, AssetTransactionCoordinator.AssetDelta.write(key, relativePath,
                    AssetTransactionCoordinator.Missing.INSTANCE, "created".getBytes(StandardCharsets.UTF_8)))));

            assertTrue(listenerEntered.await(5L, TimeUnit.SECONDS));
            AssetTransactionCoordinator.CommittedAsset live = coordinator.committedAsset(key).orElseThrow();
            assertEquals(1L, coordinator.committedSequence());
            assertEquals(root.resolve(relativePath).toAbsolutePath().normalize(), live.path());
            assertEquals(createMutation.toString(), live.mutationId().value());
            assertInstanceOf(AssetTransactionCoordinator.Live.class, live.state());
            AssetTransactionCoordinator.Snapshot whileListenerPending = coordinator.read(Function.identity());
            assertEquals(1L, whileListenerPending.rootSequence());
            assertEquals(live.state(), whileListenerPending.state(key).orElseThrow());
            assertEquals(live.path(), whileListenerPending.path(key).orElseThrow());
            assertEquals(live.mutationId(), whileListenerPending.lineage(key).orElseThrow());
            releaseListener.countDown();
            create.get(5L, TimeUnit.SECONDS);

            UUID deleteMutation = UUID.fromString("90000000-0000-4000-8000-000000000002");
            AssetTransactionCoordinator.TransactionResult deleted = coordinator.transact(request(coordinator,
                deleteMutation, AssetTransactionCoordinator.AssetDelta.delete(key, relativePath, live.state())));
            AssetTransactionCoordinator.CommittedAsset tombstone = coordinator.committedAsset(key).orElseThrow();
            assertEquals(2L, coordinator.committedSequence());
            assertEquals(deleted.states().get(key), tombstone.state());
            assertEquals(deleteMutation.toString(), tombstone.mutationId().value());
            assertInstanceOf(AssetTransactionCoordinator.Deleted.class, tombstone.state());
        } finally {
            releaseListener.countDown();
            coordinator.close();
        }

        assertThrows(IllegalStateException.class, () -> coordinator.committedAsset(key));
        assertThrows(IllegalStateException.class, coordinator::committedSequence);
        assertThrows(IllegalStateException.class, coordinator::requireOpen);
        assertThrows(IllegalStateException.class, () -> coordinator.read(Function.identity()));
    }

    @Test
    void committedViewRemainsAvailableWhileSnapshotWriteLockIsHeld() throws Exception {
        Path root = directory.resolve("locked-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("tab", "main");
        Path relativePath = Path.of("Customization/Tabs/main.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson());
             var executor = Executors.newFixedThreadPool(3)) {
            UUID mutationId = UUID.fromString("90000000-0000-4000-8000-000000000003");
            coordinator.transact(request(coordinator, mutationId, AssetTransactionCoordinator.AssetDelta.write(key,
                relativePath, AssetTransactionCoordinator.Missing.INSTANCE,
                "created".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.CommittedAsset expected = coordinator.committedAsset(key).orElseThrow();
            CountDownLatch writeLockHeld = new CountDownLatch(1);
            CountDownLatch releaseWriteLock = new CountDownLatch(1);
            Future<?> locked = executor.submit(() -> {
                coordinator.withHealthCheckScope(() -> {
                    writeLockHeld.countDown();
                    await(releaseWriteLock);
                });
                return null;
            });
            assertTrue(writeLockHeld.await(5L, TimeUnit.SECONDS));

            Future<AssetTransactionCoordinator.CommittedAsset> committed = executor.submit(() ->
                coordinator.committedAsset(key).orElseThrow());
            assertEquals(expected, committed.get(1L, TimeUnit.SECONDS));
            Future<Long> sequence = executor.submit(coordinator::committedSequence);
            assertEquals(1L, sequence.get(1L, TimeUnit.SECONDS));
            Future<AssetTransactionCoordinator.Snapshot> snapshot = executor.submit(() ->
                coordinator.read(Function.identity()));
            try {
                assertThrows(TimeoutException.class, () -> snapshot.get(50L, TimeUnit.MILLISECONDS));
            } finally {
                releaseWriteLock.countDown();
            }

            locked.get(5L, TimeUnit.SECONDS);
            AssetTransactionCoordinator.Snapshot current = snapshot.get(5L, TimeUnit.SECONDS);
            assertEquals(1L, current.rootSequence());
            assertEquals(expected.state(), current.state(key).orElseThrow());
            assertEquals(expected.path(), current.path(key).orElseThrow());
            assertEquals(expected.mutationId(), current.lineage(key).orElseThrow());
        }
    }

    private static AssetTransactionCoordinator.TransactionRequest request(
        AssetTransactionCoordinator coordinator, UUID mutationId, AssetTransactionCoordinator.AssetDelta delta) {
        return new AssetTransactionCoordinator.TransactionRequest(mutationId,
            coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(delta), List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
