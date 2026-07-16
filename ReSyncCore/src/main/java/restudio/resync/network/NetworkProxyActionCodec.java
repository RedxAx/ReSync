package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class NetworkProxyActionCodec {
    private NetworkProxyActionCodec() {
    }

    public static byte[] encode(NetworkProxyAction action) {
        byte[] value = action.value().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + value.length).put(action.type() == NetworkProxyActionType.COMMAND ? (byte) 1 : (byte) 2).putInt(value.length).put(value).array();
    }

    public static NetworkProxyAction decode(byte[] payload) {
        if (payload == null || payload.length < Byte.BYTES + Integer.BYTES + 1) {
            throw new IllegalArgumentException("Network Proxy Action Payload Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        NetworkProxyActionType type = switch (buffer.get()) {
            case 1 -> NetworkProxyActionType.COMMAND;
            case 2 -> NetworkProxyActionType.BROADCAST;
            default -> throw new IllegalArgumentException("Network Proxy Action Type Is Invalid");
        };
        int length = buffer.getInt();
        if (length < 1 || buffer.remaining() != length) {
            throw new IllegalArgumentException("Network Proxy Action Payload Is Invalid");
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return new NetworkProxyAction(type, new String(value, StandardCharsets.UTF_8));
    }
}
