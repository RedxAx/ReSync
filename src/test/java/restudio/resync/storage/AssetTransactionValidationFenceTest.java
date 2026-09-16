package restudio.resync.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTransactionValidationFenceTest {
    @TempDir
    Path root;

    @Test
    void staleValidationRejectsAnOtherwiseValidWriteWithoutPublication() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            AssetTransactionCoordinator.Snapshot validated = coordinator.read(snapshot -> snapshot);
            coordinator.transact(write(validated, "variable", "reference"));
            AtomicInteger notifications = new AtomicInteger();
            coordinator.addListener(result -> notifications.incrementAndGet());

            assertThrows(AssetTransactionCoordinator.StateConflictException.class,
                () -> coordinator.transact(write(validated, "flow", "consumer"), validated.rootSequence()));

            assertFalse(Files.exists(root.resolve("flow/consumer.json")));
            assertEquals(1L, coordinator.committedSequence());
            assertEquals(0, notifications.get());
        }
    }

    @Test
    void committedMutationReplaysAfterTheValidationSequenceAdvances() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            AssetTransactionCoordinator.Snapshot validated = coordinator.read(snapshot -> snapshot);
            AssetTransactionCoordinator.TransactionRequest original = write(validated, "flow", "consumer");
            AssetTransactionCoordinator.TransactionResult accepted = coordinator.transact(original, validated.rootSequence());
            coordinator.transact(write(coordinator.read(snapshot -> snapshot), "variable", "later"));

            AssetTransactionCoordinator.TransactionResult replay = coordinator.transact(original, validated.rootSequence());

            assertTrue(replay.replay());
            assertEquals(accepted.rootSequence(), replay.rootSequence());
            assertEquals(2L, coordinator.committedSequence());
        }
    }

    @Test
    void twoValidatedWritesToDifferentAssetsCannotBothCrossTheSameFence() throws Exception {
        try (AssetTransactionCoordinator first = AssetTransactionCoordinator.open(root, new Gson());
             AssetTransactionCoordinator second = AssetTransactionCoordinator.open(root, new Gson());
             var executor = Executors.newFixedThreadPool(2)) {
            AssetTransactionCoordinator.Snapshot validated = first.read(snapshot -> snapshot);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> left = executor.submit(() -> commit(first, validated, "left", ready, start));
            Future<Boolean> right = executor.submit(() -> commit(second, validated, "right", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertTrue(left.get(10, TimeUnit.SECONDS) ^ right.get(10, TimeUnit.SECONDS));
            assertEquals(1L, first.committedSequence());
            assertTrue(Files.exists(root.resolve("flow/left.json")) ^ Files.exists(root.resolve("flow/right.json")));
        }
    }

    private boolean commit(AssetTransactionCoordinator coordinator, AssetTransactionCoordinator.Snapshot validated,
                           String id, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent validation test did not start");
        }
        try {
            coordinator.transact(write(validated, "flow", id), validated.rootSequence());
            return true;
        } catch (AssetTransactionCoordinator.StateConflictException expected) {
            return false;
        }
    }

    private AssetTransactionCoordinator.TransactionRequest write(AssetTransactionCoordinator.Snapshot snapshot,
                                                                  String type, String id) {
        return new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey(type, id),
                Path.of(type, id + ".json"), AssetTransactionCoordinator.Missing.INSTANCE,
                "{}".getBytes(StandardCharsets.UTF_8))), List.of());
    }
}
