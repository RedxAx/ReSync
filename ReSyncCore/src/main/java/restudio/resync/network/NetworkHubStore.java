package restudio.resync.network;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface NetworkHubStore extends AutoCloseable {
    CompletableFuture<Void> open();

    CompletableFuture<NetworkNode> registerNode(NetworkNode node);

    CompletableFuture<NetworkNode> updateNodeStatus(String networkId, String nodeId, NetworkNodeStatus status, long heartbeatAt);

    CompletableFuture<Void> revokeNode(String networkId, String nodeId, long revokedAt);

    CompletableFuture<List<NetworkNode>> listNodes(String networkId);

    CompletableFuture<Void> seedEnrollment(String networkId, String nodeId, byte[] tokenHash, long expiresAt, long createdAt);

    CompletableFuture<Boolean> enrollNode(String networkId, String nodeId, byte[] tokenHash, byte[] credentialHash, long now);

    CompletableFuture<Boolean> authenticateNode(String networkId, String nodeId, byte[] credentialHash);

    CompletableFuture<NetworkNodeMetrics> updateNodeMetrics(NetworkNodeMetrics metrics);

    CompletableFuture<List<NetworkNodeMetrics>> listNodeMetrics(String networkId);

    CompletableFuture<NetworkVariable> compareAndSetVariable(NetworkVariable variable, long expectedRevision);

    CompletableFuture<Optional<NetworkVariable>> getVariable(String networkId, NetworkVariableScope scope, String scopeId, String key, long now);

    CompletableFuture<List<NetworkVariable>> listVariables(String networkId, NetworkVariableScope scope, String scopeId, long now);

    CompletableFuture<Integer> purgeExpiredVariables(long now);

    CompletableFuture<NetworkEvent> publishEvent(NetworkEvent event);

    CompletableFuture<List<NetworkEvent>> pendingEvents(String networkId, String consumerId, int limit, long now);

    CompletableFuture<Void> acknowledgeEvent(String eventId, String consumerId, long acknowledgedAt);

    CompletableFuture<Integer> purgeEvents(long before, long now);

    CompletableFuture<PlayerTransfer> beginTransfer(String transferId, String networkId, UUID playerId, String sourceNodeId, String targetNodeId, long deadline, long now);

    CompletableFuture<PlayerStateSnapshot> commitSnapshot(String transferId, PlayerStateSnapshot snapshot);

    CompletableFuture<PlayerTransfer> markTargetReady(String transferId, long now);

    CompletableFuture<PlayerTransfer> markConnected(String transferId, long now);

    CompletableFuture<PlayerTransfer> acknowledgeApplied(String transferId, String snapshotId, long now);

    CompletableFuture<PlayerTransfer> commitTransfer(String transferId, long now);

    CompletableFuture<PlayerTransfer> abortTransfer(String transferId, String failure, long now);

    CompletableFuture<Optional<PlayerTransfer>> getTransfer(String transferId);

    CompletableFuture<Optional<PlayerTransfer>> getActiveTransfer(String networkId, UUID playerId, long now);

    CompletableFuture<List<PlayerTransfer>> recoverableTransfers(String networkId, long now);

    CompletableFuture<Integer> expireTransfers(String networkId, long now);

    CompletableFuture<Optional<PlayerLease>> getLease(String networkId, UUID playerId);

    CompletableFuture<PlayerLease> claimOwnership(String networkId, UUID playerId, String nodeId, long now);

    CompletableFuture<PlayerStateSnapshot> saveOwnerSnapshot(PlayerStateSnapshot snapshot);

    CompletableFuture<Optional<PlayerStateSnapshot>> latestSnapshot(String networkId, UUID playerId, String family);

    CompletableFuture<Optional<PlayerStateSnapshot>> latestSnapshotInRealm(String networkId, UUID playerId, String realmId);

    CompletableFuture<PlayerTransfer> beginRestore(String transferId, String networkId, UUID playerId, String targetNodeId, String snapshotId, long deadline, long now);

    default CompletableFuture<List<PlayerStateSnapshot>> listSnapshots(String networkId, UUID playerId, int limit) {
        return listSnapshots(networkId, playerId, 0, limit);
    }

    CompletableFuture<List<PlayerStateSnapshot>> listSnapshots(String networkId, UUID playerId, int offset, int limit);

    CompletableFuture<Optional<PlayerStateSnapshot>> getSnapshot(String snapshotId);

    CompletableFuture<Void> pinSnapshot(String snapshotId, boolean pinned);

    CompletableFuture<Integer> purgeSnapshots(String networkId, long before, int retainPerPlayerFamily, long now);

    CompletableFuture<Path> backup(Path target);

    CompletableFuture<Void> appendAudit(String networkId, String actorNodeId, String action, String subject, String detail, long createdAt);

    @Override
    void close();
}
