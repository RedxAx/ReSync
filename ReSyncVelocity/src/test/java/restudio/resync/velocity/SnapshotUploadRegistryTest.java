package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import restudio.resync.network.NetworkSnapshotChunk;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotUploadRegistryTest {
    @Test
    void enforcesCapacityAcrossOwnerAndTransferUploads() {
        SnapshotUploadRegistry registry = new SnapshotUploadRegistry(2, 1000);
        registry.ownerUpload("owner", new Object(), chunk("owner:owner", "owner", 0), 0);
        registry.transferUpload(chunk("transfer", "transfer", 0), 0);

        assertThrows(IllegalStateException.class, () -> registry.transferUpload(chunk("blocked", "blocked", 0), 0));
        assertEquals(2, registry.activeUploads());
    }

    @Test
    void expiresOwnerUploadAndReleasesCapacity() {
        SnapshotUploadRegistry registry = new SnapshotUploadRegistry(1, 1000);
        SnapshotUploadRegistry.OwnerUpload upload = registry.ownerUpload("owner", new Object(), chunk("owner:owner", "owner", 0), 0);

        registry.expireOwners(1000);

        assertEquals(0, registry.activeUploads());
        assertTrue(upload.result().isCompletedExceptionally());
        registry.transferUpload(chunk("transfer", "transfer", 0), 1000);
        assertEquals(1, registry.activeUploads());
    }

    @Test
    void rejectsAnotherSessionForExistingOwnerUpload() {
        SnapshotUploadRegistry registry = new SnapshotUploadRegistry(2, 1000);
        registry.ownerUpload("owner", new Object(), chunk("owner:owner", "owner", 0), 0);

        assertThrows(SecurityException.class, () -> registry.ownerUpload("owner", new Object(), chunk("owner:owner", "owner", 0), 1));
    }

    @Test
    void disconnectCleanupOnlyRemovesMatchingOwner() {
        SnapshotUploadRegistry registry = new SnapshotUploadRegistry(2, 1000);
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        SnapshotUploadRegistry.OwnerUpload first = registry.ownerUpload("first", firstOwner, chunk("owner:first", "first", 0), 0);
        SnapshotUploadRegistry.OwnerUpload second = registry.ownerUpload("second", secondOwner, chunk("owner:second", "second", 0), 0);

        registry.discardOwners(firstOwner, "Session Closed");

        assertEquals(1, registry.activeUploads());
        assertTrue(first.result().isCompletedExceptionally());
        assertFalse(second.result().isDone());
    }

    private NetworkSnapshotChunk chunk(String transferId, String snapshotId, int index) {
        return new NetworkSnapshotChunk(transferId, snapshotId, "network", UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, "player/data", "hash", 1, 1, "node", 1, 1, index, 1, new byte[]{1});
    }
}
