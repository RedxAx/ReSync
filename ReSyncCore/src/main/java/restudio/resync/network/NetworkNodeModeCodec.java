package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class NetworkNodeModeCodec {
    private static final int MAXIMUM_NODE_ID_BYTES = 512;

    private NetworkNodeModeCodec() {
    }

    public static byte[] encode(NetworkNodeMode mode) {
        byte[] nodeId = mode.nodeId().getBytes(StandardCharsets.UTF_8);
        if (nodeId.length == 0 || nodeId.length > MAXIMUM_NODE_ID_BYTES) {
            throw new IllegalArgumentException("Network Node Mode ID Is Invalid");
        }
        return ByteBuffer.allocate(Short.BYTES + nodeId.length + Byte.BYTES).putShort((short) nodeId.length).put(nodeId).put(statusCode(mode.status())).array();
    }

    public static NetworkNodeMode decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Byte.BYTES) {
            throw new IllegalArgumentException("Network Node Mode Payload Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int nodeIdLength = Short.toUnsignedInt(buffer.getShort());
        if (nodeIdLength == 0 || nodeIdLength > MAXIMUM_NODE_ID_BYTES || buffer.remaining() != nodeIdLength + Byte.BYTES) {
            throw new IllegalArgumentException("Network Node Mode Payload Is Invalid");
        }
        byte[] nodeId = new byte[nodeIdLength];
        buffer.get(nodeId);
        return new NetworkNodeMode(new String(nodeId, StandardCharsets.UTF_8), status(buffer.get()));
    }

    private static byte statusCode(NetworkNodeStatus status) {
        return switch (status) {
            case ONLINE -> (byte) 1;
            case DRAINING -> (byte) 2;
            case MAINTENANCE -> (byte) 3;
            default -> throw new IllegalArgumentException("Network Node Mode Is Invalid");
        };
    }

    private static NetworkNodeStatus status(byte code) {
        return switch (code) {
            case 1 -> NetworkNodeStatus.ONLINE;
            case 2 -> NetworkNodeStatus.DRAINING;
            case 3 -> NetworkNodeStatus.MAINTENANCE;
            default -> throw new IllegalArgumentException("Network Node Mode Is Invalid");
        };
    }
}
