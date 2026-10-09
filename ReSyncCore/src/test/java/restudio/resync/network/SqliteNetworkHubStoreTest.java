package restudio.resync.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteNetworkHubStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exchangesEnrollmentTokensOnceAndRevokesNodeCredentials() {
        try (SqliteNetworkHubStore store = store()) {
            long now = 500;
            String token = NetworkCredentials.generate();
            String credential = NetworkCredentials.generate();
            NetworkNode node = new NetworkNode("network", "lobby", "Lobby", "BACKEND", Set.of("presence"), NetworkNodeStatus.OFFLINE, now, 0);
            store.registerNode(node).join();
            store.seedEnrollment("network", "lobby", NetworkCredentials.hash(token), now + 60_000, now).join();

            assertTrue(store.enrollNode("network", "lobby", NetworkCredentials.hash(token), NetworkCredentials.hash(credential), now + 1).join());
            assertTrue(store.authenticateNode("network", "lobby", NetworkCredentials.hash(credential)).join());
            assertFalse(store.enrollNode("network", "lobby", NetworkCredentials.hash(token), NetworkCredentials.hash(NetworkCredentials.generate()), now + 2).join());

            store.revokeNode("network", "lobby", now + 3).join();
            assertFalse(store.authenticateNode("network", "lobby", NetworkCredentials.hash(credential)).join());
        }
    }

    @Test
    void migratesVersionOneEnrollmentAndMetricsStorage() throws Exception {
        Path database = temporaryDirectory.resolve("version-one.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE audit_records(audit_id INTEGER PRIMARY KEY AUTOINCREMENT, network_id TEXT NOT NULL, actor_node_id TEXT NOT NULL, action TEXT NOT NULL, subject TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.execute("PRAGMA user_version = 1");
        }

        try (SqliteNetworkHubStore store = new SqliteNetworkHubStore(database)) {
            store.open().join();
            String token = NetworkCredentials.generate();
            String credential = NetworkCredentials.generate();
            store.seedEnrollment("network", "lobby", NetworkCredentials.hash(token), 0, 100).join();
            assertTrue(store.enrollNode("network", "lobby", NetworkCredentials.hash(token), NetworkCredentials.hash(credential), 101).join());
            assertTrue(store.authenticateNode("network", "lobby", NetworkCredentials.hash(credential)).join());
            NetworkNodeMetrics metrics = new NetworkNodeMetrics("network", "lobby", 4, 100, 20, 12.5, 1024, 4096, 102);
            assertEquals(metrics, store.updateNodeMetrics(metrics).join());
            assertEquals(metrics, store.listNodeMetrics("network").join().getFirst());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(3, result.getInt(1));
        }
    }

    @Test
    void appendsRedactedOperatorAuditRecords() throws Exception {
        Path database = temporaryDirectory.resolve("audit.db");
        try (SqliteNetworkHubStore store = new SqliteNetworkHubStore(database)) {
            store.open().join();
            store.appendAudit("network", "operator", "proxy.command", "proxy", "sha256:abc", 500).join();
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.prepareStatement("SELECT actor_node_id, action, detail FROM audit_records WHERE network_id = ?")) {
            statement.setString(1, "network");
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("operator", result.getString("actor_node_id"));
                assertEquals("proxy.command", result.getString("action"));
                assertEquals("sha256:abc", result.getString("detail"));
            }
        }
    }

    @Test
    void storesCompareAndSetVariablesAndDurableEvents() {
        try (SqliteNetworkHubStore store = store()) {
            long now = 1_000;
            NetworkVariable first = new NetworkVariable("network", NetworkVariableScope.NETWORK, "", "maintenance", NetworkVariableType.BOOLEAN, bytes("false"), 0, 0, "proxy", now);
            NetworkVariable saved = store.compareAndSetVariable(first, 0).join();

            assertEquals(1, saved.revision());
            assertArrayEquals(bytes("false"), store.getVariable("network", NetworkVariableScope.NETWORK, "", "maintenance", now).join().orElseThrow().value());

            CompletionException conflict = assertThrows(CompletionException.class, () -> store.compareAndSetVariable(first, 0).join());
            assertInstanceOf(NetworkVariableConflictException.class, conflict.getCause());

            NetworkEvent event = new NetworkEvent("event-one", "network", NetworkChannels.EVENTS, "maintenance.changed", bytes("{}"), "proxy", now, now + 60_000);
            store.publishEvent(event).join();
            NetworkEvent pending = store.pendingEvents("network", "lobby", 10, now).join().getFirst();
            assertEquals(event.eventId(), pending.eventId());
            assertArrayEquals(event.payload(), pending.payload());
            store.acknowledgeEvent(event.eventId(), "lobby", now + 1).join();
            assertTrue(store.pendingEvents("network", "lobby", 10, now + 2).join().isEmpty());
            assertEquals(event.eventId(), store.pendingEvents("network", "survival", 10, now + 2).join().getFirst().eventId());
        }
    }

    @Test
    void storesRevisionedResourcesAndDurableTombstones() {
        try (SqliteNetworkHubStore store = store()) {
            byte[] firstPayload = bytes("{\"id\":\"welcome\"}");
            NetworkResource first = store.compareAndSetResource("network", "lobby", NetworkResourceMutation.save("function", "welcome", 0, firstPayload), 1_000).join();

            assertEquals(1, first.revision());
            assertArrayEquals(firstPayload, store.getResource("network", "function", "welcome").join().orElseThrow().payload());
            assertEquals(first.metadata(), store.listResources("network", NetworkResourceQuery.firstPage()).join().resources().getFirst());

            CompletionException conflict = assertThrows(CompletionException.class, () -> store.compareAndSetResource("network", "survival", NetworkResourceMutation.save("function", "welcome", 0, bytes("{}")), 1_001).join());
            assertInstanceOf(NetworkResourceConflictException.class, conflict.getCause());

            NetworkResource deleted = store.compareAndSetResource("network", "lobby", NetworkResourceMutation.delete("function", "welcome", first.revision()), 1_002).join();
            assertEquals(2, deleted.revision());
            assertTrue(deleted.deleted());
            assertArrayEquals(new byte[0], deleted.payload());
            assertTrue(store.getResource("network", "function", "welcome").join().orElseThrow().deleted());
        }
    }

    @Test
    void fencesStateAndCommitsOwnershipOnlyAfterApplyAcknowledgement() {
        try (SqliteNetworkHubStore store = store()) {
            UUID playerId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            PlayerTransfer transfer = store.beginTransfer("transfer-one", "network", playerId, "lobby", "survival", now + 30_000, now).join();
            assertEquals(playerId, store.listLeases("network").join().getFirst().playerId());
            byte[] payload = bytes("compressed-state");
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot("snapshot-one", "network", playerId, transfer.fenceEpoch(), "survival-shared", payload, NetworkPayloads.sha256(payload), 1, 1, "lobby", now + 1, false);

            store.commitSnapshot(transfer.transferId(), snapshot).join();
            store.markTargetReady(transfer.transferId(), now + 2).join();
            store.markConnected(transfer.transferId(), now + 3).join();
            assertEquals(NetworkTransferStatus.CONNECTED, store.markTargetReady(transfer.transferId(), now + 3).join().status());
            assertEquals(snapshot, store.commitSnapshot(transfer.transferId(), snapshot).join());
            store.acknowledgeApplied(transfer.transferId(), snapshot.snapshotId(), now + 4).join();
            PlayerTransfer committed = store.commitTransfer(transfer.transferId(), now + 5).join();
            assertEquals(NetworkTransferStatus.COMMITTED, store.acknowledgeApplied(transfer.transferId(), snapshot.snapshotId(), now + 6).join().status());

            assertEquals(NetworkTransferStatus.COMMITTED, committed.status());
            PlayerLease lease = store.getLease("network", playerId).join().orElseThrow();
            assertEquals("survival", lease.ownerNodeId());
            assertEquals("", lease.pendingNodeId());
            assertEquals(transfer.fenceEpoch(), lease.fenceEpoch());
            PlayerStateSnapshot storedSnapshot = store.listSnapshots("network", playerId, 10).join().getFirst();
            assertEquals(snapshot.snapshotId(), storedSnapshot.snapshotId());
            assertArrayEquals(snapshot.payload(), storedSnapshot.payload());

            PlayerTransfer returning = store.beginTransfer("transfer-two", "network", playerId, "survival", "lobby", now + 60_000, now + 10).join();
            assertTrue(returning.fenceEpoch() > transfer.fenceEpoch());
            PlayerStateSnapshot stale = new PlayerStateSnapshot("snapshot-stale", "network", playerId, transfer.fenceEpoch(), "survival-shared", payload, NetworkPayloads.sha256(payload), 1, 1, "survival", now + 11, false);
            assertThrows(CompletionException.class, () -> store.commitSnapshot(returning.transferId(), stale).join());

            PlayerTransfer replacement = store.beginTransfer("transfer-three", "network", playerId, "survival", "lobby", now + 90_000, now + 60_001).join();
            PlayerStateSnapshot superseded = new PlayerStateSnapshot("snapshot-superseded", "network", playerId, returning.fenceEpoch(), "survival-shared", payload, NetworkPayloads.sha256(payload), 1, 1, "survival", now + 60_002, false);
            assertThrows(CompletionException.class, () -> store.commitSnapshot(returning.transferId(), superseded).join());
            assertTrue(store.getSnapshot(superseded.snapshotId()).join().isEmpty());
            assertEquals(replacement.fenceEpoch(), store.getLease("network", playerId).join().orElseThrow().fenceEpoch());
        }
    }

    @Test
    void rejectsQueuedSnapshotsAfterDeadlineAndKeepsExpiryDurable() throws Exception {
        Path database = temporaryDirectory.resolve("queued-expiry.db");
        UUID playerId = UUID.randomUUID();
        try (SqliteNetworkHubStore store = new SqliteNetworkHubStore(database)) {
            store.open().join();
            long now = System.currentTimeMillis();
            PlayerTransfer transfer = store.beginTransfer("queued", "network", playerId, "lobby", "survival", now + 2_000, now).join();
            byte[] payload = bytes("state");
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot("late", "network", playerId, transfer.fenceEpoch(), "shared", payload, NetworkPayloads.sha256(payload), 1, 1, "lobby", now, false);
            UUID committedPlayer = UUID.randomUUID();
            PlayerTransfer committed = store.beginTransfer("committed", "network", committedPlayer, "lobby", "survival", now + 2_000, now).join();
            PlayerStateSnapshot saved = new PlayerStateSnapshot("saved", "network", committedPlayer, committed.fenceEpoch(), "shared", payload, NetworkPayloads.sha256(payload), 1, 1, "lobby", now, false);
            store.commitSnapshot(committed.transferId(), saved).join();
            store.markTargetReady(committed.transferId(), now).join();
            store.markConnected(committed.transferId(), now).join();
            store.acknowledgeApplied(committed.transferId(), saved.snapshotId(), now).join();
            store.commitTransfer(committed.transferId(), now).join();
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                var blocked = store.appendAudit("network", "operator", "blocked", "queued", "", now);
                var pending = store.commitSnapshot(transfer.transferId(), snapshot);
                UUID latePlayer = UUID.randomUUID();
                var lateTransfer = store.beginTransfer("late-transfer", "network", latePlayer, "lobby", "survival", transfer.deadline(), now);
                var lateRestore = store.beginRestore("late-restore", "network", committedPlayer, "lobby", saved.snapshotId(), transfer.deadline(), now);
                assertTrue(System.currentTimeMillis() < transfer.deadline());
                assertThrows(TimeoutException.class, () -> pending.get(Math.max(1, transfer.deadline() - System.currentTimeMillis() + 25), TimeUnit.MILLISECONDS));
                statement.execute("COMMIT");
                blocked.get(5, TimeUnit.SECONDS);
                CompletionException failure = assertThrows(CompletionException.class, pending::join);
                assertEquals("Player Transfer Deadline Expired", failure.getCause().getMessage());
                assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, lateTransfer::join).getCause());
                assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, lateRestore::join).getCause());
                assertTrue(store.getLease("network", latePlayer).join().isEmpty());
                assertTrue(store.getTransfer("late-transfer").join().isEmpty());
                assertTrue(store.getTransfer("late-restore").join().isEmpty());
                assertEquals(committed.fenceEpoch(), store.getLease("network", committedPlayer).join().orElseThrow().fenceEpoch());
                assertEquals(1, store.listSnapshots("network", committedPlayer, 10).join().size());
            }
            assertEquals(NetworkTransferStatus.TIMED_OUT, store.abortTransfer(transfer.transferId(), "retry", System.currentTimeMillis()).join().status());
            assertEquals(saved, store.commitSnapshot(committed.transferId(), saved).join());
            assertEquals(NetworkTransferStatus.COMMITTED, store.commitTransfer(committed.transferId(), System.currentTimeMillis()).join().status());
            assertEquals(NetworkTransferStatus.COMMITTED, store.beginTransfer(committed.transferId(), "network", committedPlayer, "lobby", "survival", committed.deadline(), now).join().status());
        }
        try (SqliteNetworkHubStore store = new SqliteNetworkHubStore(database)) {
            store.open().join();
            assertEquals(NetworkTransferStatus.TIMED_OUT, store.getTransfer("queued").join().orElseThrow().status());
            assertEquals("", store.getLease("network", playerId).join().orElseThrow().pendingNodeId());
            assertTrue(store.getSnapshot("late").join().isEmpty());
        }
    }

    @Test
    void rejectsSnapshotsWithForgedPayloadHashes() {
        try (SqliteNetworkHubStore store = store()) {
            long now = System.currentTimeMillis();
            UUID playerId = UUID.randomUUID();
            PlayerTransfer transfer = store.beginTransfer("transfer-forged", "network", playerId, "lobby", "survival", now + 30_000, now).join();
            PlayerStateSnapshot forged = new PlayerStateSnapshot("snapshot-forged", "network", playerId, transfer.fenceEpoch(), "survival-shared", bytes("state"), NetworkPayloads.sha256(bytes("different")), 1, 1, "lobby", now + 1, false);

            assertThrows(CompletionException.class, () -> store.commitSnapshot(transfer.transferId(), forged).join());
            assertEquals(NetworkTransferStatus.SOURCE_LEASED, store.getTransfer(transfer.transferId()).join().orElseThrow().status());
        }
    }

    @Test
    void savesOwnedDisconnectSnapshotsAndStartsFencedRestores() {
        try (SqliteNetworkHubStore store = store()) {
            long now = System.currentTimeMillis();
            UUID playerId = UUID.randomUUID();
            PlayerLease lease = store.claimOwnership("network", playerId, "survival-one", now).join();
            byte[] payload = bytes("logout-state");
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot("logout-snapshot", "network", playerId, lease.fenceEpoch(), "survival/custom", payload, NetworkPayloads.sha256(payload), 1, 1, "survival-one", now + 1, false);

            assertEquals(snapshot, store.saveOwnerSnapshot(snapshot).join());
            assertEquals(snapshot, store.latestSnapshot("network", playerId, snapshot.family()).join().orElseThrow());
            PlayerTransfer restore = store.beginRestore("restore-one", "network", playerId, "survival-two", snapshot.snapshotId(), now + 30_000, now + 2).join();

            assertEquals(NetworkTransferStatus.SNAPSHOT_COMMITTED, restore.status());
            assertTrue(restore.fenceEpoch() > lease.fenceEpoch());
            assertEquals("survival-two", store.getLease("network", playerId).join().orElseThrow().pendingNodeId());
            assertEquals(restore.snapshotId(), store.getSnapshot(restore.snapshotId()).join().orElseThrow().snapshotId());
        }
    }

    @Test
    void restoresHistoricalSnapshotsAndRetainsPinnedHistory() {
        try (SqliteNetworkHubStore store = store()) {
            long now = System.currentTimeMillis();
            UUID playerId = UUID.randomUUID();
            PlayerLease lease = store.claimOwnership("network", playerId, "survival-one", now).join();
            byte[] oldPayload = bytes("old-state");
            PlayerStateSnapshot oldSnapshot = new PlayerStateSnapshot("old-snapshot", "network", playerId, lease.fenceEpoch(), "survival/custom", oldPayload, NetworkPayloads.sha256(oldPayload), 1, 1, "survival-one", now + 1, true);
            store.saveOwnerSnapshot(oldSnapshot).join();
            store.pinSnapshot(oldSnapshot.snapshotId(), true).join();

            byte[] currentPayload = bytes("current-state");
            PlayerStateSnapshot currentSnapshot = new PlayerStateSnapshot("current-snapshot", "network", playerId, lease.fenceEpoch(), "survival/custom", currentPayload, NetworkPayloads.sha256(currentPayload), 1, 1, "survival-one", now + 2, false);
            store.saveOwnerSnapshot(currentSnapshot).join();
            PlayerTransfer restore = store.beginRestore("historical-restore", "network", playerId, "survival-one", oldSnapshot.snapshotId(), now + 30_000, now + 3).join();

            assertEquals(NetworkTransferStatus.SNAPSHOT_COMMITTED, restore.status());
            assertArrayEquals(oldPayload, store.getSnapshot(restore.snapshotId()).join().orElseThrow().payload());
            assertEquals(1, store.purgeSnapshots("network", now + 10, 1, now + 4).join());
            assertTrue(store.getSnapshot(oldSnapshot.snapshotId()).join().isPresent());
        }
    }

    @Test
    void expiresTransfersAndCreatesConsistentBackups() throws Exception {
        Path backup = temporaryDirectory.resolve("backup.db");
        try (SqliteNetworkHubStore store = store()) {
            long now = System.currentTimeMillis();
            UUID playerId = UUID.randomUUID();
            store.beginTransfer("transfer-one", "network", playerId, "lobby", "survival", now + 30_000, now).join();

            assertEquals(1, store.expireTransfers("network", now + 30_001).join());
            assertEquals(NetworkTransferStatus.TIMED_OUT, store.getTransfer("transfer-one").join().orElseThrow().status());
            assertEquals("", store.getLease("network", playerId).join().orElseThrow().pendingNodeId());
            assertEquals(backup, store.backup(backup).join());
        }

        assertTrue(Files.size(backup) > 0);
    }

    private SqliteNetworkHubStore store() {
        SqliteNetworkHubStore store = new SqliteNetworkHubStore(temporaryDirectory.resolve(UUID.randomUUID() + ".db"));
        store.open().join();
        return store;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
