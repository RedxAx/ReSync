package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkSnapshotAdminCodecTest {
    @Test
    void roundTripsSnapshotQueriesMetadataAndCommands() {
        UUID playerId = UUID.randomUUID();
        NetworkSnapshotQuery query = new NetworkSnapshotQuery(playerId, 50);
        NetworkSnapshotMetadata metadata = new NetworkSnapshotMetadata("snapshot", "network", playerId, 3, "survival/custom", 123, NetworkPayloads.sha256(new byte[]{1}), 1, 5000, "survival", 1000, true);
        NetworkSnapshotPin pin = new NetworkSnapshotPin("snapshot", false);
        NetworkSnapshotRestore restore = new NetworkSnapshotRestore("snapshot", "survival-two", 5000);

        assertEquals(query, NetworkSnapshotAdminCodec.decodeQuery(NetworkSnapshotAdminCodec.encodeQuery(query)));
        assertEquals("snapshot", NetworkSnapshotAdminCodec.decodeReference(NetworkSnapshotAdminCodec.encodeReference("snapshot")));
        assertEquals(pin, NetworkSnapshotAdminCodec.decodePin(NetworkSnapshotAdminCodec.encodePin(pin)));
        assertEquals(restore, NetworkSnapshotAdminCodec.decodeRestore(NetworkSnapshotAdminCodec.encodeRestore(restore)));
        assertEquals(metadata, NetworkSnapshotAdminCodec.decodeMetadata(NetworkSnapshotAdminCodec.encodeMetadata(metadata)));
        assertEquals(List.of(metadata), NetworkSnapshotAdminCodec.decodeList(NetworkSnapshotAdminCodec.encodeList(List.of(metadata))));
    }
}
