package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class NetworkNodePresenceCodec {
    private static final int MAXIMUM_NODE_ID_BYTES = 512;
    private static final int METRICS_BYTES = Integer.BYTES * 2 + Double.BYTES * 2 + Long.BYTES * 3;

    private NetworkNodePresenceCodec() {
    }

    public static byte[] encode(NetworkNodePresence presence) {
        byte[] nodeId = presence.nodeId().getBytes(StandardCharsets.UTF_8);
        if (nodeId.length == 0 || nodeId.length > MAXIMUM_NODE_ID_BYTES) {
            throw new IllegalArgumentException("Network Presence Node ID Is Invalid");
        }
        return ByteBuffer.allocate(Short.BYTES + nodeId.length + Byte.BYTES + METRICS_BYTES).putShort((short) nodeId.length).put(nodeId).put(statusCode(presence.status())).putInt(presence.players()).putInt(presence.capacity()).putDouble(presence.tps()).putDouble(presence.mspt()).putLong(presence.heapUsed()).putLong(presence.heapMaximum()).putLong(presence.observedAt()).array();
    }

    public static NetworkNodePresence decode(String networkId, byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Byte.BYTES + METRICS_BYTES) {
            throw new IllegalArgumentException("Network Node Presence Payload Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int nodeIdLength = Short.toUnsignedInt(buffer.getShort());
        if (nodeIdLength == 0 || nodeIdLength > MAXIMUM_NODE_ID_BYTES || buffer.remaining() != nodeIdLength + Byte.BYTES + METRICS_BYTES) {
            throw new IllegalArgumentException("Network Node Presence Payload Is Invalid");
        }
        byte[] nodeId = new byte[nodeIdLength];
        buffer.get(nodeId);
        NetworkNodeStatus status = status(buffer.get());
        return new NetworkNodePresence(networkId, new String(nodeId, StandardCharsets.UTF_8), status, buffer.getInt(), buffer.getInt(), buffer.getDouble(), buffer.getDouble(), buffer.getLong(), buffer.getLong(), buffer.getLong());
    }

    private static byte statusCode(NetworkNodeStatus status) {
        return switch (status) {
            case ONLINE -> (byte) 1;
            case DRAINING -> (byte) 2;
            case MAINTENANCE -> (byte) 3;
            case OFFLINE -> (byte) 4;
            case REVOKED -> (byte) 5;
        };
    }

    private static NetworkNodeStatus status(byte code) {
        return switch (code) {
            case 1 -> NetworkNodeStatus.ONLINE;
            case 2 -> NetworkNodeStatus.DRAINING;
            case 3 -> NetworkNodeStatus.MAINTENANCE;
            case 4 -> NetworkNodeStatus.OFFLINE;
            case 5 -> NetworkNodeStatus.REVOKED;
            default -> throw new IllegalArgumentException("Network Node Presence Status Is Invalid");
        };
    }
}
