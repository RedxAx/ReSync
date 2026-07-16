package restudio.resync.network;

import java.nio.ByteBuffer;

public final class NetworkPresenceCodec {
    private static final int PAYLOAD_BYTES = Integer.BYTES * 2 + Double.BYTES * 2 + Long.BYTES * 3;

    private NetworkPresenceCodec() {
    }

    public static byte[] encode(NetworkNodeMetrics metrics) {
        return ByteBuffer.allocate(PAYLOAD_BYTES).putInt(metrics.players()).putInt(metrics.capacity()).putDouble(metrics.tps()).putDouble(metrics.mspt()).putLong(metrics.heapUsed()).putLong(metrics.heapMaximum()).putLong(metrics.observedAt()).array();
    }

    public static NetworkNodeMetrics decode(String networkId, String nodeId, byte[] payload) {
        if (payload == null || payload.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Network Presence Payload Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        return new NetworkNodeMetrics(networkId, nodeId, buffer.getInt(), buffer.getInt(), buffer.getDouble(), buffer.getDouble(), buffer.getLong(), buffer.getLong(), buffer.getLong());
    }
}
