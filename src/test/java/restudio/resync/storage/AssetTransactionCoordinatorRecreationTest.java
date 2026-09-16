package restudio.resync.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTransactionCoordinatorRecreationTest {
    private static final Gson GSON = new Gson();
    private static final AssetTransactionCoordinator.AssetKey KEY = new AssetTransactionCoordinator.AssetKey("flow", "recreated");
    private static final Path ORIGINAL = Path.of("original.json");
    private static final Path RECREATED = Path.of("recreated.json");

    @TempDir
    Path tempDir;

    @Test
    void deletedAssetCanRebindItsPathAndReplayThroughFullJournalValidation() throws Exception {
        Path root = tempDir.resolve("replay");
        AssetTransactionCoordinator.TransactionRequest recreate;
        AssetTransactionCoordinator.Deleted deleted;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            deleted = delete(coordinator);
            recreate = request(coordinator, AssetTransactionCoordinator.AssetDelta.write(KEY, RECREATED, deleted, bytes("second")));
            AssetTransactionCoordinator.TransactionResult result = coordinator.transact(recreate);
            assertEquals(deleted.revision() + 1L, result.states().get(KEY).revision());
            assertFalse(Files.exists(root.resolve(ORIGINAL)));
            assertEquals("second", Files.readString(root.resolve(RECREATED)));
            assertTrue(coordinator.transact(recreate).replay());
        }
        Files.delete(root.resolve(".asset-coordinator/history-checkpoint.json"));
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            assertTrue(coordinator.validationMetrics().managerJournalReads() > 0L);
            assertEquals(root.resolve(RECREATED), coordinator.read(snapshot -> snapshot.path(KEY).orElseThrow()));
            long revision = coordinator.read(snapshot -> snapshot.state(KEY).orElseThrow().revision());
            assertEquals(deleted.revision() + 1L, revision);
            assertTrue(coordinator.transact(recreate).replay());
            assertFalse(Files.exists(root.resolve(ORIGINAL)));
            assertEquals("second", Files.readString(root.resolve(RECREATED)));
        }
    }

    @Test
    void recreationRejectsAnInexactTombstoneAndLivePathRebinding() throws Exception {
        Path root = tempDir.resolve("cas");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.Deleted deleted = delete(coordinator);
            AssetTransactionCoordinator.Deleted stale = new AssetTransactionCoordinator.Deleted(deleted.revision(), "0".repeat(64));
            assertThrows(AssetTransactionCoordinator.MutationConflictException.class,
                () -> coordinator.transact(request(coordinator,
                    AssetTransactionCoordinator.AssetDelta.write(KEY, RECREATED, stale, bytes("invalid")))));
            assertFalse(Files.exists(root.resolve(RECREATED)));
            coordinator.transact(request(coordinator,
                AssetTransactionCoordinator.AssetDelta.write(KEY, ORIGINAL, deleted, bytes("live"))));
            AssetTransactionCoordinator.ExpectedState live = coordinator.read(snapshot -> snapshot.state(KEY).orElseThrow());
            assertThrows(AssetTransactionCoordinator.MutationConflictException.class,
                () -> coordinator.transact(request(coordinator,
                    AssetTransactionCoordinator.AssetDelta.write(KEY, RECREATED, live, bytes("invalid")))));
            assertEquals("live", Files.readString(root.resolve(ORIGINAL)));
            assertFalse(Files.exists(root.resolve(RECREATED)));
        }
    }

    @Test
    void recreationRequiresAbsentSourceAndDestination() throws Exception {
        for (Path occupied : List.of(ORIGINAL, RECREATED)) {
            Path root = tempDir.resolve(occupied.getFileName().toString());
            try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
                AssetTransactionCoordinator.Deleted deleted = delete(coordinator);
                AssetTransactionCoordinator.TransactionRequest recreate = request(coordinator,
                    AssetTransactionCoordinator.AssetDelta.write(KEY, RECREATED, deleted, bytes("invalid")));
                Files.writeString(root.resolve(occupied), "external");
                assertThrows(IOException.class, () -> coordinator.transact(recreate));
                assertEquals("external", Files.readString(root.resolve(occupied)));
            }
        }
    }

    private static AssetTransactionCoordinator.Deleted delete(AssetTransactionCoordinator coordinator) throws IOException {
        AssetTransactionCoordinator.TransactionResult created = coordinator.transact(request(coordinator,
            AssetTransactionCoordinator.AssetDelta.write(KEY, ORIGINAL, AssetTransactionCoordinator.Missing.INSTANCE, bytes("first"))));
        AssetTransactionCoordinator.TransactionResult deleted = coordinator.transact(request(coordinator,
            AssetTransactionCoordinator.AssetDelta.delete(KEY, ORIGINAL, created.states().get(KEY))));
        return (AssetTransactionCoordinator.Deleted) deleted.states().get(KEY);
    }

    private static AssetTransactionCoordinator.TransactionRequest request(AssetTransactionCoordinator coordinator,
                                                                          AssetTransactionCoordinator.AssetDelta delta)
        throws IOException {
        return new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(),
            coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(delta), List.of());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
