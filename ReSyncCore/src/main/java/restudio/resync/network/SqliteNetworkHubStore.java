package restudio.resync.network;

import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SqliteNetworkHubStore implements NetworkHubStore {
    private static final int SCHEMA_VERSION = 2;
    private static final Set<NetworkTransferStatus> TERMINAL_TRANSFERS = Set.of(NetworkTransferStatus.COMMITTED, NetworkTransferStatus.ABORTED, NetworkTransferStatus.TIMED_OUT);
    private final Path databasePath;
    private final ThreadPoolExecutor executor;
    private final int maximumVariableBytes;
    private final int maximumEventBytes;
    private final int maximumSnapshotBytes;
    private final AtomicBoolean closing = new AtomicBoolean();
    private Connection connection;

    public SqliteNetworkHubStore(Path databasePath) {
        this(databasePath, 1024, 1_048_576, 4_194_304, 16_777_216);
    }

    public SqliteNetworkHubStore(Path databasePath, int queueCapacity) {
        this(databasePath, queueCapacity, 1_048_576, 4_194_304, 16_777_216);
    }

    public SqliteNetworkHubStore(Path databasePath, int queueCapacity, int maximumVariableBytes, int maximumEventBytes, int maximumSnapshotBytes) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.maximumVariableBytes = Math.max(0, maximumVariableBytes);
        this.maximumEventBytes = Math.max(0, maximumEventBytes);
        this.maximumSnapshotBytes = Math.max(0, maximumSnapshotBytes);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(32, queueCapacity)), runnable -> {
            Thread thread = new Thread(runnable, "resync-network-store");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletableFuture<Void> open() {
        return submit("Open Network Store", () -> {
            if (connection != null) {
                return null;
            }
            try {
                Path parent = databasePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                connection = JDBC.createConnection("jdbc:sqlite:" + databasePath, new Properties());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA journal_mode = WAL");
                    statement.execute("PRAGMA synchronous = NORMAL");
                    statement.execute("PRAGMA busy_timeout = 5000");
                }
                migrate();
                return null;
            } catch (Exception exception) {
                if (connection != null) {
                    try {
                        connection.close();
                    } finally {
                        connection = null;
                    }
                }
                throw exception;
            }
        });
    }

    @Override
    public CompletableFuture<NetworkNode> registerNode(NetworkNode node) {
        return submit("Register Network Node", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nodes(network_id, node_id, display_name, role, capabilities, status, heartbeat_at, revoked_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(network_id, node_id) DO UPDATE SET display_name = excluded.display_name, role = excluded.role, capabilities = excluded.capabilities,
                    status = CASE WHEN nodes.revoked_at > 0 THEN 'REVOKED' ELSE excluded.status END,
                    heartbeat_at = excluded.heartbeat_at, revoked_at = nodes.revoked_at
                """)) {
                bindNode(statement, node);
                statement.executeUpdate();
            }
            return requireNode(node.networkId(), node.nodeId());
        });
    }

    @Override
    public CompletableFuture<NetworkNode> updateNodeStatus(String networkId, String nodeId, NetworkNodeStatus status, long heartbeatAt) {
        if (status == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Node Status Is Required"));
        }
        return submit("Update Network Node", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE nodes SET status = ?, heartbeat_at = ? WHERE network_id = ? AND node_id = ? AND revoked_at = 0")) {
                statement.setString(1, status.name());
                statement.setLong(2, heartbeatAt);
                statement.setString(3, required(networkId, "Network ID"));
                statement.setString(4, required(nodeId, "Node ID"));
                if (statement.executeUpdate() != 1) {
                    throw new NetworkStoreException("Network Node Is Missing Or Revoked");
                }
            }
            return requireNode(networkId, nodeId);
        });
    }

    @Override
    public CompletableFuture<Void> revokeNode(String networkId, String nodeId, long revokedAt) {
        return submit("Revoke Network Node", () -> transaction(() -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE nodes SET status = 'REVOKED', revoked_at = ? WHERE network_id = ? AND node_id = ?")) {
                statement.setLong(1, revokedAt);
                statement.setString(2, required(networkId, "Network ID"));
                statement.setString(3, required(nodeId, "Node ID"));
                if (statement.executeUpdate() != 1) {
                    throw new NetworkStoreException("Network Node Does Not Exist");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE node_credentials SET revoked_at = ? WHERE network_id = ? AND node_id = ?")) {
                statement.setLong(1, revokedAt);
                statement.setString(2, networkId);
                statement.setString(3, nodeId);
                statement.executeUpdate();
            }
            audit(networkId, nodeId, "node.revoked", nodeId, "", revokedAt);
            return null;
        }));
    }

    @Override
    public CompletableFuture<List<NetworkNode>> listNodes(String networkId) {
        return submit("List Network Nodes", () -> {
            requireOpen();
            List<NetworkNode> nodes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM nodes WHERE network_id = ? ORDER BY role, display_name, node_id")) {
                statement.setString(1, required(networkId, "Network ID"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        nodes.add(node(result));
                    }
                }
            }
            return List.copyOf(nodes);
        });
    }

    @Override
    public CompletableFuture<Void> seedEnrollment(String networkId, String nodeId, byte[] tokenHash, long expiresAt, long createdAt) {
        return submit("Seed Network Enrollment", () -> {
            requireOpen();
            requireHash(tokenHash, "Enrollment Token Hash");
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO enrollment_tokens(network_id, node_id, token_hash, expires_at, consumed_at, created_at)
                VALUES(?, ?, ?, ?, 0, ?)
                ON CONFLICT(network_id, node_id) DO UPDATE SET token_hash = excluded.token_hash, expires_at = excluded.expires_at,
                    consumed_at = CASE WHEN enrollment_tokens.token_hash = excluded.token_hash THEN enrollment_tokens.consumed_at ELSE 0 END,
                    created_at = CASE WHEN enrollment_tokens.token_hash = excluded.token_hash THEN enrollment_tokens.created_at ELSE excluded.created_at END
                """)) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, required(nodeId, "Node ID"));
                statement.setBytes(3, tokenHash);
                statement.setLong(4, expiresAt);
                statement.setLong(5, createdAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> enrollNode(String networkId, String nodeId, byte[] tokenHash, byte[] credentialHash, long now) {
        return submit("Enroll Network Node", () -> transaction(() -> {
            requireHash(tokenHash, "Enrollment Token Hash");
            requireHash(credentialHash, "Node Credential Hash");
            String network = required(networkId, "Network ID");
            String node = required(nodeId, "Node ID");
            byte[] expectedHash = null;
            long expiresAt = 0;
            long consumedAt = 0;
            try (PreparedStatement statement = connection.prepareStatement("SELECT token_hash, expires_at, consumed_at FROM enrollment_tokens WHERE network_id = ? AND node_id = ?")) {
                statement.setString(1, network);
                statement.setString(2, node);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        expectedHash = result.getBytes("token_hash");
                        expiresAt = result.getLong("expires_at");
                        consumedAt = result.getLong("consumed_at");
                    }
                }
            }
            if (consumedAt > 0 || expiresAt > 0 && expiresAt <= now || !NetworkCredentials.matches(expectedHash, tokenHash)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE enrollment_tokens SET consumed_at = ? WHERE network_id = ? AND node_id = ? AND consumed_at = 0")) {
                statement.setLong(1, now);
                statement.setString(2, network);
                statement.setString(3, node);
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO node_credentials(network_id, node_id, credential_hash, issued_at, revoked_at)
                VALUES(?, ?, ?, ?, 0)
                ON CONFLICT(network_id, node_id) DO UPDATE SET credential_hash = excluded.credential_hash, issued_at = excluded.issued_at, revoked_at = 0
                """)) {
                statement.setString(1, network);
                statement.setString(2, node);
                statement.setBytes(3, credentialHash);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            audit(network, node, "node.enrolled", node, "", now);
            return true;
        }));
    }

    @Override
    public CompletableFuture<Boolean> authenticateNode(String networkId, String nodeId, byte[] credentialHash) {
        return submit("Authenticate Network Node", () -> {
            requireOpen();
            requireHash(credentialHash, "Node Credential Hash");
            byte[] expectedHash = null;
            try (PreparedStatement statement = connection.prepareStatement("SELECT credential_hash FROM node_credentials WHERE network_id = ? AND node_id = ? AND revoked_at = 0")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, required(nodeId, "Node ID"));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        expectedHash = result.getBytes(1);
                    }
                }
            }
            return NetworkCredentials.matches(expectedHash, credentialHash);
        });
    }

    @Override
    public CompletableFuture<NetworkNodeMetrics> updateNodeMetrics(NetworkNodeMetrics metrics) {
        return submit("Update Network Node Metrics", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO node_metrics(network_id, node_id, players, capacity, tps, mspt, heap_used, heap_maximum, observed_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(network_id, node_id) DO UPDATE SET players = excluded.players, capacity = excluded.capacity, tps = excluded.tps,
                    mspt = excluded.mspt, heap_used = excluded.heap_used, heap_maximum = excluded.heap_maximum, observed_at = excluded.observed_at
                """)) {
                statement.setString(1, metrics.networkId());
                statement.setString(2, metrics.nodeId());
                statement.setInt(3, metrics.players());
                statement.setInt(4, metrics.capacity());
                statement.setDouble(5, metrics.tps());
                statement.setDouble(6, metrics.mspt());
                statement.setLong(7, metrics.heapUsed());
                statement.setLong(8, metrics.heapMaximum());
                statement.setLong(9, metrics.observedAt());
                statement.executeUpdate();
            }
            return metrics;
        });
    }

    @Override
    public CompletableFuture<List<NetworkNodeMetrics>> listNodeMetrics(String networkId) {
        return submit("List Network Node Metrics", () -> {
            requireOpen();
            List<NetworkNodeMetrics> metrics = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM node_metrics WHERE network_id = ? ORDER BY node_id")) {
                statement.setString(1, required(networkId, "Network ID"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        metrics.add(nodeMetrics(result));
                    }
                }
            }
            return List.copyOf(metrics);
        });
    }

    @Override
    public CompletableFuture<NetworkVariable> compareAndSetVariable(NetworkVariable variable, long expectedRevision) {
        if (expectedRevision < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected Revision Cannot Be Negative"));
        }
        NetworkPayloads.requireLimit(variable.value(), maximumVariableBytes);
        return submit("Set Network Variable", () -> transaction(() -> {
            requireOpen();
            deleteExpiredVariable(variable.networkId(), variable.scope(), variable.scopeId(), variable.key(), variable.updatedAt());
            long currentRevision = variableRevision(variable.networkId(), variable.scope(), variable.scopeId(), variable.key());
            if (currentRevision != expectedRevision) {
                throw new NetworkVariableConflictException(expectedRevision, currentRevision);
            }
            long revision = currentRevision + 1;
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO network_variables(network_id, scope, scope_id, variable_key, value_type, value, revision, expires_at, origin_node_id, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(network_id, scope, scope_id, variable_key) DO UPDATE SET value_type = excluded.value_type, value = excluded.value,
                    revision = excluded.revision, expires_at = excluded.expires_at, origin_node_id = excluded.origin_node_id, updated_at = excluded.updated_at
                """)) {
                statement.setString(1, variable.networkId());
                statement.setString(2, variable.scope().name());
                statement.setString(3, variable.scopeId());
                statement.setString(4, variable.key());
                statement.setString(5, variable.type().name());
                statement.setBytes(6, variable.value());
                statement.setLong(7, revision);
                statement.setLong(8, variable.expiresAt());
                statement.setString(9, variable.originNodeId());
                statement.setLong(10, variable.updatedAt());
                statement.executeUpdate();
            }
            audit(variable.networkId(), variable.originNodeId(), "variable.changed", variable.scope().name() + ":" + variable.scopeId() + ":" + variable.key(), String.valueOf(revision), variable.updatedAt());
            return new NetworkVariable(variable.networkId(), variable.scope(), variable.scopeId(), variable.key(), variable.type(), variable.value(), revision, variable.expiresAt(), variable.originNodeId(), variable.updatedAt());
        }));
    }

    @Override
    public CompletableFuture<Optional<NetworkVariable>> getVariable(String networkId, NetworkVariableScope scope, String scopeId, String key, long now) {
        return submit("Read Network Variable", () -> {
            requireOpen();
            deleteExpiredVariable(networkId, scope, scopeId, key, now);
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM network_variables WHERE network_id = ? AND scope = ? AND scope_id = ? AND variable_key = ?")) {
                bindVariableKey(statement, networkId, scope, scopeId, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(variable(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<NetworkVariable>> listVariables(String networkId, NetworkVariableScope scope, String scopeId, long now) {
        NetworkVariableScope resolvedScope = scope == null ? NetworkVariableScope.NETWORK : scope;
        return submit("List Network Variables", () -> {
            requireOpen();
            purgeExpiredVariablesNow(now);
            List<NetworkVariable> variables = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM network_variables WHERE network_id = ? AND scope = ? AND scope_id = ? ORDER BY variable_key")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, resolvedScope.name());
                statement.setString(3, normalized(scopeId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        variables.add(variable(result));
                    }
                }
            }
            return List.copyOf(variables);
        });
    }

    @Override
    public CompletableFuture<Integer> purgeExpiredVariables(long now) {
        return submit("Purge Network Variables", () -> {
            requireOpen();
            return purgeExpiredVariablesNow(now);
        });
    }

    @Override
    public CompletableFuture<NetworkEvent> publishEvent(NetworkEvent event) {
        NetworkPayloads.requireLimit(event.payload(), maximumEventBytes);
        return submit("Publish Network Event", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO durable_events(event_id, network_id, channel, subject, payload, origin_node_id, created_at, expires_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING")) {
                statement.setString(1, event.eventId());
                statement.setString(2, event.networkId());
                statement.setString(3, event.channel());
                statement.setString(4, event.subject());
                statement.setBytes(5, event.payload());
                statement.setString(6, event.originNodeId());
                statement.setLong(7, event.createdAt());
                statement.setLong(8, event.expiresAt());
                statement.executeUpdate();
            }
            NetworkEvent stored = requireEvent(event.eventId());
            if (!stored.networkId().equals(event.networkId()) || !stored.channel().equals(event.channel()) || !stored.subject().equals(event.subject()) || !stored.originNodeId().equals(event.originNodeId()) || stored.createdAt() != event.createdAt() || stored.expiresAt() != event.expiresAt() || !Arrays.equals(stored.payload(), event.payload())) {
                throw new NetworkStoreException("Event ID Is Already Used By Different Data");
            }
            return stored;
        });
    }

    @Override
    public CompletableFuture<List<NetworkEvent>> pendingEvents(String networkId, String consumerId, int limit, long now) {
        return submit("Read Network Events", () -> {
            requireOpen();
            List<NetworkEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event.* FROM durable_events event
                WHERE event.network_id = ? AND (event.expires_at = 0 OR event.expires_at > ?)
                    AND NOT EXISTS(SELECT 1 FROM event_acknowledgements acknowledgement WHERE acknowledgement.event_id = event.event_id AND acknowledgement.consumer_id = ?)
                ORDER BY event.created_at, event.event_id LIMIT ?
                """)) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setLong(2, now);
                statement.setString(3, required(consumerId, "Consumer ID"));
                statement.setInt(4, Math.clamp(limit, 1, 1000));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        events.add(event(result));
                    }
                }
            }
            return List.copyOf(events);
        });
    }

    @Override
    public CompletableFuture<Void> acknowledgeEvent(String eventId, String consumerId, long acknowledgedAt) {
        return submit("Acknowledge Network Event", () -> {
            requireOpen();
            if (eventById(required(eventId, "Event ID")).isEmpty()) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO event_acknowledgements(event_id, consumer_id, acknowledged_at) VALUES(?, ?, ?) ON CONFLICT(event_id, consumer_id) DO UPDATE SET acknowledged_at = excluded.acknowledged_at")) {
                statement.setString(1, eventId);
                statement.setString(2, required(consumerId, "Consumer ID"));
                statement.setLong(3, acknowledgedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> purgeEvents(long before, long now) {
        return submit("Purge Network Events", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM durable_events WHERE created_at < ? OR (expires_at > 0 AND expires_at <= ?)")) {
                statement.setLong(1, before);
                statement.setLong(2, now);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<PlayerTransfer> beginTransfer(String transferId, String networkId, UUID playerId, String sourceNodeId, String targetNodeId, long deadline, long now) {
        return submit("Begin Player Transfer", () -> transaction(() -> {
            requireOpen();
            String network = required(networkId, "Network ID");
            String source = required(sourceNodeId, "Source Node ID");
            String target = required(targetNodeId, "Target Node ID");
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            if (source.equals(target)) {
                throw new IllegalArgumentException("Transfer Target Must Differ From Source");
            }
            if (deadline <= now) {
                throw new IllegalArgumentException("Transfer Deadline Must Be In The Future");
            }
            PlayerTransfer duplicate = transfer(required(transferId, "Transfer ID")).orElse(null);
            if (duplicate != null) {
                if (!duplicate.networkId().equals(network) || !duplicate.playerId().equals(playerId) || !duplicate.sourceNodeId().equals(source) || !duplicate.targetNodeId().equals(target)) {
                    throw new NetworkStoreException("Transfer ID Is Already Used By A Different Transfer");
                }
                return duplicate;
            }
            if (activeTransfer(network, playerId, now)) {
                throw new NetworkStoreException("Player Already Has An Active Transfer");
            }
            PlayerLease existing = lease(network, playerId).orElse(null);
            if (existing != null && !existing.ownerNodeId().isBlank() && !existing.ownerNodeId().equals(source)) {
                throw new NetworkStoreException("Player State Is Owned By " + existing.ownerNodeId());
            }
            long epoch = existing == null ? 1 : existing.fenceEpoch() + 1;
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_ownership(network_id, player_id, owner_node_id, pending_node_id, fence_epoch, lease_expires_at, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(network_id, player_id) DO UPDATE SET owner_node_id = excluded.owner_node_id, pending_node_id = excluded.pending_node_id,
                    fence_epoch = excluded.fence_epoch, lease_expires_at = excluded.lease_expires_at, updated_at = excluded.updated_at
                """)) {
                statement.setString(1, network);
                statement.setString(2, playerId.toString());
                statement.setString(3, source);
                statement.setString(4, target);
                statement.setLong(5, epoch);
                statement.setLong(6, deadline);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            PlayerTransfer transfer = new PlayerTransfer(transferId, network, playerId, source, target, epoch, NetworkTransferStatus.SOURCE_LEASED, "", "", deadline, now, now);
            insertTransfer(transfer);
            audit(network, source, "transfer.started", transfer.transferId(), target, now);
            return transfer;
        }));
    }

    @Override
    public CompletableFuture<PlayerStateSnapshot> commitSnapshot(String transferId, PlayerStateSnapshot snapshot) {
        NetworkPayloads.requireLimit(snapshot.payload(), maximumSnapshotBytes);
        if (!NetworkPayloads.sha256(snapshot.payload()).equalsIgnoreCase(snapshot.payloadHash())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot Payload Hash Does Not Match"));
        }
        return submit("Commit Player Snapshot", () -> transaction(() -> {
            requireOpen();
            PlayerTransfer transfer = requireTransfer(transferId);
            if (atOrAfter(transfer.status(), NetworkTransferStatus.SNAPSHOT_COMMITTED) && transfer.snapshotId().equals(snapshot.snapshotId())) {
                PlayerStateSnapshot stored = requireSnapshot(snapshot.snapshotId());
                if (!sameSnapshot(stored, snapshot)) {
                    throw new NetworkStoreException("Snapshot ID Is Already Used By Different Data");
                }
                return stored;
            }
            requireTransferStatus(transfer, NetworkTransferStatus.SOURCE_LEASED);
            if (!transfer.networkId().equals(snapshot.networkId()) || !transfer.playerId().equals(snapshot.playerId()) || transfer.fenceEpoch() != snapshot.fenceEpoch() || !transfer.sourceNodeId().equals(snapshot.originNodeId())) {
                throw new NetworkStoreException("Snapshot Does Not Match The Active Transfer Lease");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO state_snapshots(snapshot_id, network_id, player_id, fence_epoch, family, payload, payload_hash, schema_version, data_version, origin_node_id, created_at, pinned)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, snapshot.snapshotId());
                statement.setString(2, snapshot.networkId());
                statement.setString(3, snapshot.playerId().toString());
                statement.setLong(4, snapshot.fenceEpoch());
                statement.setString(5, snapshot.family());
                statement.setBytes(6, snapshot.payload());
                statement.setString(7, snapshot.payloadHash());
                statement.setInt(8, snapshot.schemaVersion());
                statement.setInt(9, snapshot.dataVersion());
                statement.setString(10, snapshot.originNodeId());
                statement.setLong(11, snapshot.createdAt());
                statement.setInt(12, snapshot.pinned() ? 1 : 0);
                statement.executeUpdate();
            }
            updateTransfer(transfer, NetworkTransferStatus.SNAPSHOT_COMMITTED, snapshot.snapshotId(), "", snapshot.createdAt());
            audit(snapshot.networkId(), snapshot.originNodeId(), "snapshot.committed", snapshot.snapshotId(), snapshot.payloadHash(), snapshot.createdAt());
            return snapshot;
        }));
    }

    @Override
    public CompletableFuture<PlayerTransfer> markTargetReady(String transferId, long now) {
        return transition(transferId, NetworkTransferStatus.SNAPSHOT_COMMITTED, NetworkTransferStatus.TARGET_READY, "", now);
    }

    @Override
    public CompletableFuture<PlayerTransfer> markConnected(String transferId, long now) {
        return transition(transferId, NetworkTransferStatus.TARGET_READY, NetworkTransferStatus.CONNECTED, "", now);
    }

    @Override
    public CompletableFuture<PlayerTransfer> acknowledgeApplied(String transferId, String snapshotId, long now) {
        return submit("Acknowledge Player State", () -> transaction(() -> {
            PlayerTransfer transfer = requireTransfer(transferId);
            if (atOrAfter(transfer.status(), NetworkTransferStatus.APPLIED) && transfer.snapshotId().equals(snapshotId)) {
                return transfer;
            }
            requireTransferStatus(transfer, NetworkTransferStatus.CONNECTED);
            if (!transfer.snapshotId().equals(required(snapshotId, "Snapshot ID"))) {
                throw new NetworkStoreException("Applied Snapshot Does Not Match The Committed Transfer Snapshot");
            }
            return updateTransfer(transfer, NetworkTransferStatus.APPLIED, transfer.snapshotId(), "", now);
        }));
    }

    @Override
    public CompletableFuture<PlayerTransfer> commitTransfer(String transferId, long now) {
        return submit("Commit Player Transfer", () -> transaction(() -> {
            PlayerTransfer transfer = requireTransfer(transferId);
            if (transfer.status() == NetworkTransferStatus.COMMITTED) {
                return transfer;
            }
            requireTransferStatus(transfer, NetworkTransferStatus.APPLIED);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE player_ownership SET owner_node_id = ?, pending_node_id = '', lease_expires_at = 0, updated_at = ? WHERE network_id = ? AND player_id = ? AND fence_epoch = ?")) {
                statement.setString(1, transfer.targetNodeId());
                statement.setLong(2, now);
                statement.setString(3, transfer.networkId());
                statement.setString(4, transfer.playerId().toString());
                statement.setLong(5, transfer.fenceEpoch());
                if (statement.executeUpdate() != 1) {
                    throw new NetworkStoreException("Player Transfer Lease Is Stale");
                }
            }
            PlayerTransfer committed = updateTransfer(transfer, NetworkTransferStatus.COMMITTED, transfer.snapshotId(), "", now);
            audit(transfer.networkId(), transfer.targetNodeId(), "transfer.committed", transfer.transferId(), transfer.snapshotId(), now);
            return committed;
        }));
    }

    @Override
    public CompletableFuture<PlayerTransfer> abortTransfer(String transferId, String failure, long now) {
        return submit("Abort Player Transfer", () -> transaction(() -> {
            PlayerTransfer transfer = requireTransfer(transferId);
            if (TERMINAL_TRANSFERS.contains(transfer.status())) {
                return transfer;
            }
            clearPendingOwner(transfer, now, true);
            PlayerTransfer aborted = updateTransfer(transfer, NetworkTransferStatus.ABORTED, transfer.snapshotId(), normalized(failure), now);
            audit(transfer.networkId(), transfer.sourceNodeId(), "transfer.aborted", transfer.transferId(), aborted.failure(), now);
            return aborted;
        }));
    }

    @Override
    public CompletableFuture<Optional<PlayerTransfer>> getTransfer(String transferId) {
        return submit("Read Player Transfer", () -> transfer(required(transferId, "Transfer ID")));
    }

    @Override
    public CompletableFuture<Optional<PlayerTransfer>> getActiveTransfer(String networkId, UUID playerId, long now) {
        return submit("Read Active Player Transfer", () -> {
            requireOpen();
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfer_intents WHERE network_id = ? AND player_id = ? AND status NOT IN ('COMMITTED', 'ABORTED', 'TIMED_OUT') AND (deadline = 0 OR deadline > ?) ORDER BY created_at DESC LIMIT 1")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, playerId.toString());
                statement.setLong(3, now);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(transfer(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<PlayerTransfer>> recoverableTransfers(String networkId, long now) {
        return submit("Recover Player Transfers", () -> {
            requireOpen();
            List<PlayerTransfer> transfers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfer_intents WHERE network_id = ? AND status NOT IN ('COMMITTED', 'ABORTED', 'TIMED_OUT') ORDER BY deadline, created_at")) {
                statement.setString(1, required(networkId, "Network ID"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        transfers.add(transfer(result));
                    }
                }
            }
            return List.copyOf(transfers);
        });
    }

    @Override
    public CompletableFuture<Integer> expireTransfers(String networkId, long now) {
        return submit("Expire Player Transfers", () -> transaction(() -> {
            List<PlayerTransfer> expired = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfer_intents WHERE network_id = ? AND deadline > 0 AND deadline <= ? AND status NOT IN ('COMMITTED', 'ABORTED', 'TIMED_OUT')")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setLong(2, now);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        expired.add(transfer(result));
                    }
                }
            }
            for (PlayerTransfer transfer : expired) {
                clearPendingOwner(transfer, now, false);
                updateTransfer(transfer, NetworkTransferStatus.TIMED_OUT, transfer.snapshotId(), "Transfer Deadline Expired", now);
                audit(transfer.networkId(), transfer.sourceNodeId(), "transfer.timed_out", transfer.transferId(), transfer.targetNodeId(), now);
            }
            return expired.size();
        }));
    }

    @Override
    public CompletableFuture<Optional<PlayerLease>> getLease(String networkId, UUID playerId) {
        return submit("Read Player Lease", () -> lease(required(networkId, "Network ID"), playerId));
    }

    @Override
    public CompletableFuture<List<PlayerLease>> listLeases(String networkId) {
        return submit("List Player Leases", () -> {
            requireOpen();
            List<PlayerLease> leases = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM player_ownership WHERE network_id = ? ORDER BY player_id")) {
                statement.setString(1, required(networkId, "Network ID"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        leases.add(playerLease(result));
                    }
                }
            }
            return List.copyOf(leases);
        });
    }

    @Override
    public CompletableFuture<PlayerLease> claimOwnership(String networkId, UUID playerId, String nodeId, long now) {
        return submit("Claim Player Ownership", () -> transaction(() -> {
            requireOpen();
            String network = required(networkId, "Network ID");
            String node = required(nodeId, "Node ID");
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            PlayerLease existing = lease(network, playerId).orElse(null);
            if (existing != null) {
                if (!existing.pendingNodeId().isBlank()) {
                    throw new NetworkStoreException("Player Ownership Has A Pending Transfer");
                }
                return existing;
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_ownership(network_id, player_id, owner_node_id, pending_node_id, fence_epoch, lease_expires_at, updated_at) VALUES(?, ?, ?, '', 1, 0, ?)")) {
                statement.setString(1, network);
                statement.setString(2, playerId.toString());
                statement.setString(3, node);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            audit(network, node, "ownership.claimed", NetworkPayloads.sha256(playerId.toString().getBytes(StandardCharsets.UTF_8)), "1", now);
            return new PlayerLease(network, playerId, node, "", 1, 0, now);
        }));
    }

    @Override
    public CompletableFuture<PlayerStateSnapshot> saveOwnerSnapshot(PlayerStateSnapshot snapshot) {
        NetworkPayloads.requireLimit(snapshot.payload(), maximumSnapshotBytes);
        if (!NetworkPayloads.sha256(snapshot.payload()).equalsIgnoreCase(snapshot.payloadHash())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot Payload Hash Does Not Match"));
        }
        return submit("Save Owned Player Snapshot", () -> transaction(() -> {
            requireOpen();
            PlayerStateSnapshot duplicate = snapshotById(snapshot.snapshotId()).orElse(null);
            if (duplicate != null) {
                if (!sameSnapshot(duplicate, snapshot)) {
                    throw new NetworkStoreException("Snapshot ID Is Already Used By Different Data");
                }
                return duplicate;
            }
            PlayerLease lease = lease(snapshot.networkId(), snapshot.playerId()).orElseThrow(() -> new NetworkStoreException("Player Ownership Does Not Exist"));
            if (!lease.ownerNodeId().equals(snapshot.originNodeId()) || !lease.pendingNodeId().isBlank() || lease.fenceEpoch() != snapshot.fenceEpoch()) {
                throw new NetworkStoreException("Snapshot Writer Does Not Own The Current Fence");
            }
            insertSnapshot(snapshot);
            audit(snapshot.networkId(), snapshot.originNodeId(), "snapshot.saved", snapshot.snapshotId(), snapshot.payloadHash(), snapshot.createdAt());
            return snapshot;
        }));
    }

    @Override
    public CompletableFuture<Optional<PlayerStateSnapshot>> latestSnapshot(String networkId, UUID playerId, String family) {
        return submit("Read Latest Player Snapshot", () -> {
            requireOpen();
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM state_snapshots WHERE network_id = ? AND player_id = ? AND family = ? ORDER BY fence_epoch DESC, created_at DESC LIMIT 1")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, playerId.toString());
                statement.setString(3, required(family, "State Family"));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerStateSnapshot>> latestSnapshotInRealm(String networkId, UUID playerId, String realmId) {
        return submit("Read Latest Realm Snapshot", () -> {
            requireOpen();
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            String prefix = required(realmId, "State Realm").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%";
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM state_snapshots WHERE network_id = ? AND player_id = ? AND family LIKE ? ESCAPE '\\' ORDER BY fence_epoch DESC, created_at DESC LIMIT 1")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, playerId.toString());
                statement.setString(3, prefix);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<PlayerTransfer> beginRestore(String transferId, String networkId, UUID playerId, String targetNodeId, String snapshotId, long deadline, long now) {
        return submit("Begin Player Snapshot Restore", () -> transaction(() -> {
            requireOpen();
            String network = required(networkId, "Network ID");
            String target = required(targetNodeId, "Target Node ID");
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            if (deadline <= now) {
                throw new IllegalArgumentException("Transfer Deadline Must Be In The Future");
            }
            PlayerTransfer duplicate = transfer(required(transferId, "Transfer ID")).orElse(null);
            if (duplicate != null) {
                if (!duplicate.networkId().equals(network) || !duplicate.playerId().equals(playerId) || !duplicate.targetNodeId().equals(target)) {
                    throw new NetworkStoreException("Transfer ID Is Already Used By A Different Restore");
                }
                return duplicate;
            }
            if (activeTransfer(network, playerId, now)) {
                throw new NetworkStoreException("Player Already Has An Active Transfer");
            }
            PlayerLease lease = lease(network, playerId).orElseThrow(() -> new NetworkStoreException("Player Ownership Does Not Exist"));
            if (lease.ownerNodeId().isBlank() || !lease.pendingNodeId().isBlank()) {
                throw new NetworkStoreException("Player Ownership Cannot Be Restored To This Target");
            }
            PlayerStateSnapshot source = requireSnapshot(snapshotId);
            if (!source.networkId().equals(network) || !source.playerId().equals(playerId)) {
                throw new NetworkStoreException("Restore Snapshot Does Not Match This Player");
            }
            long epoch = lease.fenceEpoch() + 1;
            PlayerStateSnapshot restored = new PlayerStateSnapshot(UUID.randomUUID().toString(), network, playerId, epoch, source.family(), source.payload(), source.payloadHash(), source.schemaVersion(), source.dataVersion(), source.originNodeId(), now, false);
            insertSnapshot(restored);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE player_ownership SET pending_node_id = ?, fence_epoch = ?, lease_expires_at = ?, updated_at = ? WHERE network_id = ? AND player_id = ? AND fence_epoch = ? AND pending_node_id = ''")) {
                statement.setString(1, target);
                statement.setLong(2, epoch);
                statement.setLong(3, deadline);
                statement.setLong(4, now);
                statement.setString(5, network);
                statement.setString(6, playerId.toString());
                statement.setLong(7, lease.fenceEpoch());
                if (statement.executeUpdate() != 1) {
                    throw new NetworkStoreException("Player Restore Lease Is Stale");
                }
            }
            PlayerTransfer transfer = new PlayerTransfer(transferId, network, playerId, lease.ownerNodeId(), target, epoch, NetworkTransferStatus.SNAPSHOT_COMMITTED, restored.snapshotId(), "", deadline, now, now);
            insertTransfer(transfer);
            audit(network, target, "snapshot.restore.started", transfer.transferId(), restored.snapshotId(), now);
            return transfer;
        }));
    }

    @Override
    public CompletableFuture<List<PlayerStateSnapshot>> listSnapshots(String networkId, UUID playerId, int offset, int limit) {
        return submit("List Player Snapshots", () -> {
            requireOpen();
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID Is Required");
            }
            List<PlayerStateSnapshot> snapshots = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM state_snapshots WHERE network_id = ? AND player_id = ? ORDER BY pinned DESC, created_at DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, required(networkId, "Network ID"));
                statement.setString(2, playerId.toString());
                statement.setInt(3, Math.clamp(limit, 1, 1000));
                statement.setInt(4, Math.clamp(offset, 0, 1_000_000));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        snapshots.add(snapshot(result));
                    }
                }
            }
            return List.copyOf(snapshots);
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerStateSnapshot>> getSnapshot(String snapshotId) {
        return submit("Read Player Snapshot", () -> snapshotById(required(snapshotId, "Snapshot ID")));
    }

    @Override
    public CompletableFuture<Void> pinSnapshot(String snapshotId, boolean pinned) {
        return submit("Pin Player Snapshot", () -> {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE state_snapshots SET pinned = ? WHERE snapshot_id = ?")) {
                statement.setInt(1, pinned ? 1 : 0);
                statement.setString(2, required(snapshotId, "Snapshot ID"));
                if (statement.executeUpdate() != 1) {
                    throw new NetworkStoreException("Player Snapshot Does Not Exist");
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> purgeSnapshots(String networkId, long before, int retainPerPlayerFamily, long now) {
        return submit("Purge Player Snapshots", () -> transaction(() -> {
            requireOpen();
            String network = required(networkId, "Network ID");
            int retain = Math.clamp(retainPerPlayerFamily, 1, 1000);
            Set<String> active = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_id FROM transfer_intents WHERE network_id = ? AND snapshot_id != '' AND status NOT IN (?, ?, ?)")) {
                statement.setString(1, network);
                statement.setString(2, NetworkTransferStatus.COMMITTED.name());
                statement.setString(3, NetworkTransferStatus.ABORTED.name());
                statement.setString(4, NetworkTransferStatus.TIMED_OUT.name());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        active.add(result.getString(1));
                    }
                }
            }
            List<String> expired = new ArrayList<>();
            String group = "";
            int position = 0;
            try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_id, player_id, family, created_at, pinned FROM state_snapshots WHERE network_id = ? ORDER BY player_id, family, created_at DESC")) {
                statement.setString(1, network);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String nextGroup = result.getString("player_id") + "\u0000" + result.getString("family");
                        if (!nextGroup.equals(group)) {
                            group = nextGroup;
                            position = 0;
                        }
                        position++;
                        String snapshotId = result.getString("snapshot_id");
                        if (result.getInt("pinned") == 0 && result.getLong("created_at") < before && position > retain && !active.contains(snapshotId)) {
                            expired.add(snapshotId);
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM state_snapshots WHERE snapshot_id = ?")) {
                for (String snapshotId : expired) {
                    statement.setString(1, snapshotId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            if (!expired.isEmpty()) {
                audit(network, "hub", "snapshot.retention", "expired", Integer.toString(expired.size()), now);
            }
            return expired.size();
        }));
    }

    @Override
    public CompletableFuture<Path> backup(Path target) {
        return submit("Back Up Network Store", () -> {
            requireOpen();
            Path backup = target.toAbsolutePath().normalize();
            if (Files.exists(backup)) {
                throw new NetworkStoreException("Network Backup Already Exists");
            }
            Path parent = backup.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String escaped = backup.toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
            return backup;
        });
    }

    @Override
    public CompletableFuture<Void> appendAudit(String networkId, String actorNodeId, String action, String subject, String detail, long createdAt) {
        return submit("Append Network Audit", () -> {
            requireOpen();
            audit(required(networkId, "Network ID"), required(actorNodeId, "Audit Actor"), required(action, "Audit Action"), normalized(subject), normalized(detail), createdAt);
            return null;
        });
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (connection != null) {
                        connection.close();
                        connection = null;
                    }
                    closed.complete(null);
                } catch (SQLException exception) {
                    closed.completeExceptionally(new NetworkStoreException("Close Network Store Failed", exception));
                }
            });
            closed.join();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private CompletableFuture<PlayerTransfer> transition(String transferId, NetworkTransferStatus expected, NetworkTransferStatus target, String failure, long now) {
        return submit("Advance Player Transfer", () -> transaction(() -> {
            PlayerTransfer transfer = requireTransfer(transferId);
            if (atOrAfter(transfer.status(), target)) {
                return transfer;
            }
            requireTransferStatus(transfer, expected);
            return updateTransfer(transfer, target, transfer.snapshotId(), failure, now);
        }));
    }

    private void migrate() throws Exception {
        int version;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.next() ? result.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new NetworkStoreException("Network Store Schema " + version + " Is Newer Than Supported Schema " + SCHEMA_VERSION);
        }
        if (version == 0) {
            transaction(() -> {
                applySchema();
                setSchemaVersion(SCHEMA_VERSION);
                return null;
            });
            return;
        }
        if (version < 2) {
            transaction(() -> {
                applySchemaVersion2();
                setSchemaVersion(2);
                return null;
            });
        }
    }

    private void applySchema() throws SQLException {
        List<String> statements = List.of(
            "CREATE TABLE nodes(network_id TEXT NOT NULL, node_id TEXT NOT NULL, display_name TEXT NOT NULL, role TEXT NOT NULL, capabilities TEXT NOT NULL, status TEXT NOT NULL, heartbeat_at INTEGER NOT NULL, revoked_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE enrollment_tokens(network_id TEXT NOT NULL, node_id TEXT NOT NULL, token_hash BLOB NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE node_credentials(network_id TEXT NOT NULL, node_id TEXT NOT NULL, credential_hash BLOB NOT NULL, issued_at INTEGER NOT NULL, revoked_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE node_metrics(network_id TEXT NOT NULL, node_id TEXT NOT NULL, players INTEGER NOT NULL, capacity INTEGER NOT NULL, tps REAL NOT NULL, mspt REAL NOT NULL, heap_used INTEGER NOT NULL, heap_maximum INTEGER NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE player_ownership(network_id TEXT NOT NULL, player_id TEXT NOT NULL, owner_node_id TEXT NOT NULL, pending_node_id TEXT NOT NULL, fence_epoch INTEGER NOT NULL, lease_expires_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(network_id, player_id))",
            "CREATE TABLE state_snapshots(snapshot_id TEXT PRIMARY KEY, network_id TEXT NOT NULL, player_id TEXT NOT NULL, fence_epoch INTEGER NOT NULL, family TEXT NOT NULL, payload BLOB NOT NULL, payload_hash TEXT NOT NULL, schema_version INTEGER NOT NULL, data_version INTEGER NOT NULL, origin_node_id TEXT NOT NULL, created_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX state_snapshots_player ON state_snapshots(network_id, player_id, created_at DESC)",
            "CREATE TABLE transfer_intents(transfer_id TEXT PRIMARY KEY, network_id TEXT NOT NULL, player_id TEXT NOT NULL, source_node_id TEXT NOT NULL, target_node_id TEXT NOT NULL, fence_epoch INTEGER NOT NULL, status TEXT NOT NULL, snapshot_id TEXT NOT NULL, failure TEXT NOT NULL, deadline INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE INDEX transfer_intents_recovery ON transfer_intents(network_id, status, deadline)",
            "CREATE TABLE network_variables(network_id TEXT NOT NULL, scope TEXT NOT NULL, scope_id TEXT NOT NULL, variable_key TEXT NOT NULL, value_type TEXT NOT NULL, value BLOB NOT NULL, revision INTEGER NOT NULL, expires_at INTEGER NOT NULL, origin_node_id TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(network_id, scope, scope_id, variable_key))",
            "CREATE INDEX network_variables_expiry ON network_variables(expires_at) WHERE expires_at > 0",
            "CREATE TABLE durable_events(event_id TEXT PRIMARY KEY, network_id TEXT NOT NULL, channel TEXT NOT NULL, subject TEXT NOT NULL, payload BLOB NOT NULL, origin_node_id TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
            "CREATE INDEX durable_events_delivery ON durable_events(network_id, created_at)",
            "CREATE TABLE event_acknowledgements(event_id TEXT NOT NULL REFERENCES durable_events(event_id) ON DELETE CASCADE, consumer_id TEXT NOT NULL, acknowledged_at INTEGER NOT NULL, PRIMARY KEY(event_id, consumer_id))",
            "CREATE TABLE audit_records(audit_id INTEGER PRIMARY KEY AUTOINCREMENT, network_id TEXT NOT NULL, actor_node_id TEXT NOT NULL, action TEXT NOT NULL, subject TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE INDEX audit_records_network ON audit_records(network_id, created_at DESC)"
        );
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }

    private void applySchemaVersion2() throws SQLException {
        List<String> statements = List.of(
            "CREATE TABLE IF NOT EXISTS enrollment_tokens(network_id TEXT NOT NULL, node_id TEXT NOT NULL, token_hash BLOB NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE IF NOT EXISTS node_credentials(network_id TEXT NOT NULL, node_id TEXT NOT NULL, credential_hash BLOB NOT NULL, issued_at INTEGER NOT NULL, revoked_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(network_id, node_id))",
            "CREATE TABLE IF NOT EXISTS node_metrics(network_id TEXT NOT NULL, node_id TEXT NOT NULL, players INTEGER NOT NULL, capacity INTEGER NOT NULL, tps REAL NOT NULL, mspt REAL NOT NULL, heap_used INTEGER NOT NULL, heap_maximum INTEGER NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(network_id, node_id))"
        );
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }

    private void setSchemaVersion(int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private void bindNode(PreparedStatement statement, NetworkNode node) throws SQLException {
        statement.setString(1, node.networkId());
        statement.setString(2, node.nodeId());
        statement.setString(3, node.displayName());
        statement.setString(4, node.role());
        statement.setString(5, node.capabilities().stream().map(String::trim).filter(value -> !value.isBlank()).sorted().collect(Collectors.joining("\n")));
        statement.setString(6, node.status().name());
        statement.setLong(7, node.heartbeatAt());
        statement.setLong(8, node.revokedAt());
    }

    private NetworkNode requireNode(String networkId, String nodeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM nodes WHERE network_id = ? AND node_id = ?")) {
            statement.setString(1, required(networkId, "Network ID"));
            statement.setString(2, required(nodeId, "Node ID"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NetworkStoreException("Network Node Does Not Exist");
                }
                return node(result);
            }
        }
    }

    private NetworkNode node(ResultSet result) throws SQLException {
        String capabilities = result.getString("capabilities");
        Set<String> values = capabilities == null || capabilities.isBlank() ? Set.of() : Arrays.stream(capabilities.split("\\n")).filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        return new NetworkNode(result.getString("network_id"), result.getString("node_id"), result.getString("display_name"), result.getString("role"), values, NetworkNodeStatus.valueOf(result.getString("status")), result.getLong("heartbeat_at"), result.getLong("revoked_at"));
    }

    private NetworkNodeMetrics nodeMetrics(ResultSet result) throws SQLException {
        return new NetworkNodeMetrics(result.getString("network_id"), result.getString("node_id"), result.getInt("players"), result.getInt("capacity"), result.getDouble("tps"), result.getDouble("mspt"), result.getLong("heap_used"), result.getLong("heap_maximum"), result.getLong("observed_at"));
    }

    private void bindVariableKey(PreparedStatement statement, String networkId, NetworkVariableScope scope, String scopeId, String key) throws SQLException {
        statement.setString(1, required(networkId, "Network ID"));
        statement.setString(2, (scope == null ? NetworkVariableScope.NETWORK : scope).name());
        statement.setString(3, normalized(scopeId));
        statement.setString(4, required(key, "Variable Key"));
    }

    private long variableRevision(String networkId, NetworkVariableScope scope, String scopeId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT revision FROM network_variables WHERE network_id = ? AND scope = ? AND scope_id = ? AND variable_key = ?")) {
            bindVariableKey(statement, networkId, scope, scopeId, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private void deleteExpiredVariable(String networkId, NetworkVariableScope scope, String scopeId, String key, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM network_variables WHERE network_id = ? AND scope = ? AND scope_id = ? AND variable_key = ? AND expires_at > 0 AND expires_at <= ?")) {
            bindVariableKey(statement, networkId, scope, scopeId, key);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private int purgeExpiredVariablesNow(long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM network_variables WHERE expires_at > 0 AND expires_at <= ?")) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    private NetworkVariable variable(ResultSet result) throws SQLException {
        return new NetworkVariable(result.getString("network_id"), NetworkVariableScope.valueOf(result.getString("scope")), result.getString("scope_id"), result.getString("variable_key"), NetworkVariableType.valueOf(result.getString("value_type")), result.getBytes("value"), result.getLong("revision"), result.getLong("expires_at"), result.getString("origin_node_id"), result.getLong("updated_at"));
    }

    private NetworkEvent event(ResultSet result) throws SQLException {
        return new NetworkEvent(result.getString("event_id"), result.getString("network_id"), result.getString("channel"), result.getString("subject"), result.getBytes("payload"), result.getString("origin_node_id"), result.getLong("created_at"), result.getLong("expires_at"));
    }

    private Optional<NetworkEvent> eventById(String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM durable_events WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(event(result)) : Optional.empty();
            }
        }
    }

    private NetworkEvent requireEvent(String eventId) throws SQLException {
        return eventById(eventId).orElseThrow(() -> new NetworkStoreException("Network Event Was Not Stored"));
    }

    private void insertTransfer(PlayerTransfer transfer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transfer_intents(transfer_id, network_id, player_id, source_node_id, target_node_id, fence_epoch, status, snapshot_id, failure, deadline, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, transfer.transferId());
            statement.setString(2, transfer.networkId());
            statement.setString(3, transfer.playerId().toString());
            statement.setString(4, transfer.sourceNodeId());
            statement.setString(5, transfer.targetNodeId());
            statement.setLong(6, transfer.fenceEpoch());
            statement.setString(7, transfer.status().name());
            statement.setString(8, transfer.snapshotId());
            statement.setString(9, transfer.failure());
            statement.setLong(10, transfer.deadline());
            statement.setLong(11, transfer.createdAt());
            statement.setLong(12, transfer.updatedAt());
            statement.executeUpdate();
        }
    }

    private Optional<PlayerTransfer> transfer(String transferId) throws SQLException {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfer_intents WHERE transfer_id = ?")) {
            statement.setString(1, required(transferId, "Transfer ID"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(transfer(result)) : Optional.empty();
            }
        }
    }

    private boolean activeTransfer(String networkId, UUID playerId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transfer_intents WHERE network_id = ? AND player_id = ? AND status NOT IN ('COMMITTED', 'ABORTED', 'TIMED_OUT') AND (deadline = 0 OR deadline > ?) LIMIT 1")) {
            statement.setString(1, networkId);
            statement.setString(2, playerId.toString());
            statement.setLong(3, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private PlayerTransfer requireTransfer(String transferId) throws SQLException {
        return transfer(transferId).orElseThrow(() -> new NetworkStoreException("Player Transfer Does Not Exist"));
    }

    private PlayerTransfer transfer(ResultSet result) throws SQLException {
        return new PlayerTransfer(result.getString("transfer_id"), result.getString("network_id"), UUID.fromString(result.getString("player_id")), result.getString("source_node_id"), result.getString("target_node_id"), result.getLong("fence_epoch"), NetworkTransferStatus.valueOf(result.getString("status")), result.getString("snapshot_id"), result.getString("failure"), result.getLong("deadline"), result.getLong("created_at"), result.getLong("updated_at"));
    }

    private PlayerTransfer updateTransfer(PlayerTransfer transfer, NetworkTransferStatus status, String snapshotId, String failure, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE transfer_intents SET status = ?, snapshot_id = ?, failure = ?, updated_at = ? WHERE transfer_id = ? AND status = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, normalized(snapshotId));
            statement.setString(3, normalized(failure));
            statement.setLong(4, now);
            statement.setString(5, transfer.transferId());
            statement.setString(6, transfer.status().name());
            if (statement.executeUpdate() != 1) {
                throw new NetworkStoreException("Player Transfer Changed Before The Operation Completed");
            }
        }
        return new PlayerTransfer(transfer.transferId(), transfer.networkId(), transfer.playerId(), transfer.sourceNodeId(), transfer.targetNodeId(), transfer.fenceEpoch(), status, snapshotId, failure, transfer.deadline(), transfer.createdAt(), now);
    }

    private void requireTransferStatus(PlayerTransfer transfer, NetworkTransferStatus expected) {
        if (transfer.status() != expected) {
            throw new NetworkStoreException("Player Transfer Is " + transfer.status().name() + " Instead Of " + expected.name());
        }
    }

    private boolean atOrAfter(NetworkTransferStatus current, NetworkTransferStatus expected) {
        if (TERMINAL_TRANSFERS.contains(current)) {
            return current == expected || current == NetworkTransferStatus.COMMITTED;
        }
        return current.ordinal() >= expected.ordinal();
    }

    private Optional<PlayerLease> lease(String networkId, UUID playerId) throws SQLException {
        requireOpen();
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM player_ownership WHERE network_id = ? AND player_id = ?")) {
            statement.setString(1, networkId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(playerLease(result)) : Optional.empty();
            }
        }
    }

    private PlayerLease playerLease(ResultSet result) throws SQLException {
        return new PlayerLease(result.getString("network_id"), UUID.fromString(result.getString("player_id")), result.getString("owner_node_id"), result.getString("pending_node_id"), result.getLong("fence_epoch"), result.getLong("lease_expires_at"), result.getLong("updated_at"));
    }

    private void clearPendingOwner(PlayerTransfer transfer, long now, boolean required) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE player_ownership SET pending_node_id = '', lease_expires_at = 0, updated_at = ? WHERE network_id = ? AND player_id = ? AND fence_epoch = ?")) {
            statement.setLong(1, now);
            statement.setString(2, transfer.networkId());
            statement.setString(3, transfer.playerId().toString());
            statement.setLong(4, transfer.fenceEpoch());
            if (statement.executeUpdate() != 1 && required) {
                throw new NetworkStoreException("Player Transfer Lease Is Stale");
            }
        }
    }

    private PlayerStateSnapshot snapshot(ResultSet result) throws SQLException {
        return new PlayerStateSnapshot(result.getString("snapshot_id"), result.getString("network_id"), UUID.fromString(result.getString("player_id")), result.getLong("fence_epoch"), result.getString("family"), result.getBytes("payload"), result.getString("payload_hash"), result.getInt("schema_version"), result.getInt("data_version"), result.getString("origin_node_id"), result.getLong("created_at"), result.getInt("pinned") != 0);
    }

    private boolean sameSnapshot(PlayerStateSnapshot first, PlayerStateSnapshot second) {
        return first.snapshotId().equals(second.snapshotId()) && first.networkId().equals(second.networkId()) && first.playerId().equals(second.playerId()) && first.fenceEpoch() == second.fenceEpoch() && first.family().equals(second.family()) && Arrays.equals(first.payload(), second.payload()) && first.payloadHash().equals(second.payloadHash()) && first.schemaVersion() == second.schemaVersion() && first.dataVersion() == second.dataVersion() && first.originNodeId().equals(second.originNodeId()) && first.createdAt() == second.createdAt();
    }

    private void insertSnapshot(PlayerStateSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO state_snapshots(snapshot_id, network_id, player_id, fence_epoch, family, payload, payload_hash, schema_version, data_version, origin_node_id, created_at, pinned) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, snapshot.snapshotId());
            statement.setString(2, snapshot.networkId());
            statement.setString(3, snapshot.playerId().toString());
            statement.setLong(4, snapshot.fenceEpoch());
            statement.setString(5, snapshot.family());
            statement.setBytes(6, snapshot.payload());
            statement.setString(7, snapshot.payloadHash());
            statement.setInt(8, snapshot.schemaVersion());
            statement.setInt(9, snapshot.dataVersion());
            statement.setString(10, snapshot.originNodeId());
            statement.setLong(11, snapshot.createdAt());
            statement.setInt(12, snapshot.pinned() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private PlayerStateSnapshot requireSnapshot(String snapshotId) throws SQLException {
        return snapshotById(snapshotId).orElseThrow(() -> new NetworkStoreException("Player Snapshot Does Not Exist"));
    }

    private Optional<PlayerStateSnapshot> snapshotById(String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM state_snapshots WHERE snapshot_id = ?")) {
            statement.setString(1, required(snapshotId, "Snapshot ID"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
            }
        }
    }

    private void audit(String networkId, String actorNodeId, String action, String subject, String detail, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_records(network_id, actor_node_id, action, subject, detail, created_at) VALUES(?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, networkId);
            statement.setString(2, normalized(actorNodeId));
            statement.setString(3, action);
            statement.setString(4, normalized(subject));
            statement.setString(5, normalized(detail));
            statement.setLong(6, createdAt);
            statement.executeUpdate();
        }
    }

    private void requireOpen() {
        if (connection == null) {
            throw new NetworkStoreException("Network Store Is Not Open");
        }
    }

    private void requireHash(byte[] hash, String label) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException(label + " Must Be SHA-256");
        }
    }

    private <T> T transaction(SqlOperation<T> operation) throws Exception {
        requireOpen();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.run();
            connection.commit();
            return result;
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private <T> CompletableFuture<T> submit(String action, SqlOperation<T> operation) {
        if (closing.get()) {
            return CompletableFuture.failedFuture(new NetworkStoreException("Network Store Is Closing"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(operation.run());
                } catch (CompletionException exception) {
                    future.completeExceptionally(exception.getCause() == null ? exception : exception.getCause());
                } catch (Exception exception) {
                    future.completeExceptionally(exception instanceof NetworkStoreException || exception instanceof IllegalArgumentException ? exception : new NetworkStoreException(action + " Failed", exception));
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(new NetworkStoreException(action + " Queue Is Full", exception));
        }
        return future;
    }

    private String required(String value, String label) {
        return NetworkValues.required(value, label);
    }

    private String normalized(String value) {
        return NetworkValues.normalized(value);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws Exception;
    }
}
