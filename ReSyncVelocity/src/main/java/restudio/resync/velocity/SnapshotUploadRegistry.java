package restudio.resync.velocity;

import restudio.resync.network.NetworkSnapshotChunk;
import restudio.resync.network.PlayerLease;
import restudio.resync.network.PlayerTransfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class SnapshotUploadRegistry {
    private final int maximumActiveUploads;
    private final long ownerUploadLifetimeMillis;
    private final Map<String, TransferUpload> transferUploads = new ConcurrentHashMap<>();
    private final Map<String, OwnerUpload> ownerUploads = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    SnapshotUploadRegistry(int maximumActiveUploads, long ownerUploadLifetimeMillis) {
        if (maximumActiveUploads < 1 || ownerUploadLifetimeMillis < 1) {
            throw new IllegalArgumentException("Snapshot Upload Limits Must Be Positive");
        }
        this.maximumActiveUploads = maximumActiveUploads;
        this.ownerUploadLifetimeMillis = ownerUploadLifetimeMillis;
    }

    OwnerUpload ownerUpload(String snapshotId, Object owner, NetworkSnapshotChunk chunk, long now) {
        OwnerUpload existing = ownerUploads.get(snapshotId);
        if (existing != null) {
            if (existing.expired(now)) {
                discardOwner(snapshotId, existing, "Owned Snapshot Upload Expired");
            } else {
                existing.requireOwner(owner);
                return existing;
            }
        }
        synchronized (admissionLock) {
            expireOwners(now);
            existing = ownerUploads.get(snapshotId);
            if (existing != null) {
                existing.requireOwner(owner);
                return existing;
            }
            requireCapacity();
            OwnerUpload created = new OwnerUpload(owner, chunk, now + ownerUploadLifetimeMillis);
            ownerUploads.put(snapshotId, created);
            return created;
        }
    }

    TransferUpload transferUpload(NetworkSnapshotChunk chunk, long now) {
        TransferUpload existing = transferUploads.get(chunk.transferId());
        if (existing != null) {
            return existing;
        }
        synchronized (admissionLock) {
            existing = transferUploads.get(chunk.transferId());
            if (existing != null) {
                return existing;
            }
            expireOwners(now);
            requireCapacity();
            TransferUpload created = new TransferUpload(chunk);
            transferUploads.put(chunk.transferId(), created);
            return created;
        }
    }

    void removeTransfer(String transferId) {
        transferUploads.remove(transferId);
    }

    void removeTransfer(String transferId, TransferUpload upload) {
        transferUploads.remove(transferId, upload);
    }

    void removeOwner(String snapshotId, OwnerUpload upload) {
        ownerUploads.remove(snapshotId, upload);
    }

    void discardOwner(String snapshotId, OwnerUpload upload, String reason) {
        if (ownerUploads.remove(snapshotId, upload)) {
            upload.fail(new IllegalStateException(reason));
        }
    }

    void discardOwners(Object owner, String reason) {
        ownerUploads.forEach((snapshotId, upload) -> {
            if (upload.ownedBy(owner)) {
                discardOwner(snapshotId, upload, reason);
            }
        });
    }

    void discardOwners(String reason) {
        ownerUploads.forEach((snapshotId, upload) -> discardOwner(snapshotId, upload, reason));
    }

    void expireOwners(long now) {
        ownerUploads.forEach((snapshotId, upload) -> {
            if (upload.expired(now)) {
                discardOwner(snapshotId, upload, "Owned Snapshot Upload Expired");
            }
        });
    }

    int activeUploads() {
        return ownerUploads.size() + transferUploads.size();
    }

    private void requireCapacity() {
        if (activeUploads() >= maximumActiveUploads) {
            throw new IllegalStateException("Network Snapshot Upload Capacity Is Full");
        }
    }

    static final class OwnerUpload extends Upload<PlayerLease> {
        private final Object owner;
        private final long expiresAt;

        private OwnerUpload(Object owner, NetworkSnapshotChunk first, long expiresAt) {
            super(first);
            this.owner = owner;
            this.expiresAt = expiresAt;
        }

        private boolean ownedBy(Object candidate) {
            return owner == candidate;
        }

        private void requireOwner(Object candidate) {
            if (!ownedBy(candidate)) {
                throw new SecurityException("Owned Snapshot Upload Belongs To A Different Session");
            }
        }

        private boolean expired(long now) {
            return expiresAt <= now;
        }
    }

    static final class TransferUpload extends Upload<PlayerTransfer> {
        private TransferUpload(NetworkSnapshotChunk first) {
            super(first);
        }
    }

    abstract static class Upload<T> {
        private final NetworkSnapshotChunk first;
        private final Map<Integer, NetworkSnapshotChunk> chunks = new LinkedHashMap<>();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private int receivedBytes;
        private boolean commitClaimed;

        private Upload(NetworkSnapshotChunk first) {
            this.first = first;
        }

        synchronized boolean add(NetworkSnapshotChunk chunk) {
            if (!sameSnapshot(first, chunk)) {
                throw new IllegalArgumentException("Network Snapshot Chunk Set Is Inconsistent");
            }
            NetworkSnapshotChunk previous = chunks.putIfAbsent(chunk.chunkIndex(), chunk);
            if (previous != null && !previous.equals(chunk)) {
                throw new IllegalArgumentException("Network Snapshot Chunk Position Changed");
            }
            if (previous == null) {
                receivedBytes += chunk.payload().length;
            }
            if (receivedBytes > first.totalBytes()) {
                throw new IllegalArgumentException("Network Snapshot Chunk Set Is Too Large");
            }
            return chunks.size() == first.chunkCount() && receivedBytes == first.totalBytes();
        }

        synchronized boolean claimCommit() {
            if (commitClaimed) {
                return false;
            }
            commitClaimed = true;
            return true;
        }

        synchronized List<NetworkSnapshotChunk> chunks() {
            return List.copyOf(chunks.values());
        }

        CompletableFuture<T> result() {
            return result;
        }

        void complete(T value) {
            result.complete(value);
        }

        void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        private static boolean sameSnapshot(NetworkSnapshotChunk expected, NetworkSnapshotChunk actual) {
            return expected.transferId().equals(actual.transferId()) && expected.snapshotId().equals(actual.snapshotId()) && expected.networkId().equals(actual.networkId()) && expected.playerId().equals(actual.playerId()) && expected.fenceEpoch() == actual.fenceEpoch() && expected.family().equals(actual.family()) && expected.payloadHash().equalsIgnoreCase(actual.payloadHash()) && expected.schemaVersion() == actual.schemaVersion() && expected.dataVersion() == actual.dataVersion() && expected.originNodeId().equals(actual.originNodeId()) && expected.createdAt() == actual.createdAt() && expected.totalBytes() == actual.totalBytes() && expected.chunkCount() == actual.chunkCount();
        }
    }
}
