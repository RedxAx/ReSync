package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkTransferCodecTest {
    @Test
    void roundTripsIntentCheckpointAndTransfer() {
        UUID playerId = UUID.randomUUID();
        NetworkTransferIntent intent = new NetworkTransferIntent("transfer", playerId, "lobby", "survival", 5000);
        NetworkTransferCheckpoint checkpoint = NetworkTransferCheckpoint.snapshot("transfer", "snapshot");
        PlayerTransfer transfer = new PlayerTransfer("transfer", "network", playerId, "lobby", "survival", 4, NetworkTransferStatus.CONNECTED, "snapshot", "", 5000, 1000, 2000);

        assertEquals(intent, NetworkTransferCodec.decodeIntent(NetworkTransferCodec.encodeIntent(intent)));
        assertEquals(checkpoint, NetworkTransferCodec.decodeCheckpoint(NetworkTransferCodec.encodeCheckpoint(checkpoint)));
        assertEquals(transfer, NetworkTransferCodec.decodeTransfer(NetworkTransferCodec.encodeTransfer(transfer)));
    }

    @Test
    void splitsAndReassemblesBoundedSnapshots() {
        byte[] payload = new byte[NetworkTransferCodec.MAXIMUM_CHUNK_BYTES * 2 + 17];
        Arrays.fill(payload, (byte) 3);
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot("snapshot", "network", UUID.randomUUID(), 2, "survival", payload, NetworkPayloads.sha256(payload), 1, 5, "lobby", 1000, false);
        List<NetworkSnapshotChunk> chunks = NetworkTransferCodec.split("transfer", snapshot);
        List<NetworkSnapshotChunk> decoded = chunks.stream().map(NetworkTransferCodec::encodeChunk).map(NetworkTransferCodec::decodeChunk).toList();

        assertEquals(3, decoded.size());
        PlayerStateSnapshot assembled = NetworkTransferCodec.assemble(decoded.reversed());
        assertEquals(snapshot, assembled);
        assertArrayEquals(payload, assembled.payload());
    }

    @Test
    void rejectsIncompleteAndCorruptSnapshots() {
        byte[] payload = new byte[NetworkTransferCodec.MAXIMUM_CHUNK_BYTES + 1];
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot("snapshot", "network", UUID.randomUUID(), 2, "survival", payload, NetworkPayloads.sha256(payload), 1, 1, "lobby", 1000, false);
        List<NetworkSnapshotChunk> chunks = NetworkTransferCodec.split("transfer", snapshot);
        assertThrows(IllegalArgumentException.class, () -> NetworkTransferCodec.assemble(List.of(chunks.getFirst())));

        List<NetworkSnapshotChunk> corrupt = new ArrayList<>(chunks);
        NetworkSnapshotChunk last = corrupt.getLast();
        corrupt.set(corrupt.size() - 1, new NetworkSnapshotChunk(last.transferId(), last.snapshotId(), last.networkId(), last.playerId(), last.fenceEpoch(), last.family(), last.payloadHash(), last.schemaVersion(), last.dataVersion(), last.originNodeId(), last.createdAt(), last.totalBytes(), last.chunkIndex(), last.chunkCount(), new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> NetworkTransferCodec.assemble(corrupt));
    }

    @Test
    void rejectsTrailingData() {
        byte[] encoded = NetworkTransferCodec.encodeCheckpoint(NetworkTransferCheckpoint.transfer("transfer"));
        assertThrows(IllegalArgumentException.class, () -> NetworkTransferCodec.decodeCheckpoint(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
