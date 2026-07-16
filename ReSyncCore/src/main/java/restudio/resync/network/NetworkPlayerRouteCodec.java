package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NetworkPlayerRouteCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_ROUTE_BYTES = 512;

    private NetworkPlayerRouteCodec() {
    }

    public static byte[] encode(NetworkPlayerRoute route) {
        byte[] name = route.routeName().getBytes(StandardCharsets.UTF_8);
        requireName(name);
        return ByteBuffer.allocate(Short.BYTES + Long.BYTES * 2 + Short.BYTES + name.length).putShort((short) FORMAT_VERSION).putLong(route.playerId().getMostSignificantBits()).putLong(route.playerId().getLeastSignificantBits()).putShort((short) name.length).put(name).array();
    }

    public static NetworkPlayerRoute decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Long.BYTES * 2 + Short.BYTES + 1) {
            throw new IllegalArgumentException("Network Player Route Payload Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (Short.toUnsignedInt(buffer.getShort()) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Network Player Route Format Is Invalid");
        }
        UUID playerId = new UUID(buffer.getLong(), buffer.getLong());
        byte[] routeName = readName(buffer);
        return new NetworkPlayerRoute(playerId, new String(routeName, StandardCharsets.UTF_8));
    }

    public static byte[] encodeResult(NetworkPlayerRouteResult result) {
        byte[] name = result.routeName().getBytes(StandardCharsets.UTF_8);
        requireName(name);
        return ByteBuffer.allocate(Short.BYTES + Byte.BYTES + Short.BYTES + name.length).putShort((short) FORMAT_VERSION).put((byte) result.status().ordinal()).putShort((short) name.length).put(name).array();
    }

    public static NetworkPlayerRouteResult decodeResult(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Byte.BYTES + Short.BYTES + 1) {
            throw new IllegalArgumentException("Network Player Route Result Is Invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (Short.toUnsignedInt(buffer.getShort()) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Network Player Route Format Is Invalid");
        }
        int ordinal = Byte.toUnsignedInt(buffer.get());
        NetworkPlayerRouteStatus[] statuses = NetworkPlayerRouteStatus.values();
        if (ordinal >= statuses.length) {
            throw new IllegalArgumentException("Network Player Route Status Is Invalid");
        }
        byte[] routeName = readName(buffer);
        return new NetworkPlayerRouteResult(statuses[ordinal], new String(routeName, StandardCharsets.UTF_8));
    }

    private static byte[] readName(ByteBuffer buffer) {
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || length > MAXIMUM_ROUTE_BYTES || buffer.remaining() != length) {
            throw new IllegalArgumentException("Network Player Route Name Is Invalid");
        }
        byte[] name = new byte[length];
        buffer.get(name);
        return name;
    }

    private static void requireName(byte[] name) {
        if (name.length == 0 || name.length > MAXIMUM_ROUTE_BYTES) {
            throw new IllegalArgumentException("Network Player Route Name Is Invalid");
        }
    }
}
