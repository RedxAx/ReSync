package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NetworkPlayerLifecycleCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 2048;

    private NetworkPlayerLifecycleCodec() {
    }

    public static byte[] encode(NetworkPlayerLifecycle lifecycle) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(FORMAT_VERSION);
            output.writeByte(lifecycle.type().ordinal());
            output.writeLong(lifecycle.playerId().getMostSignificantBits());
            output.writeLong(lifecycle.playerId().getLeastSignificantBits());
            writeString(output, lifecycle.playerName(), false);
            writeString(output, lifecycle.sourceRoute(), true);
            writeString(output, lifecycle.targetRoute(), true);
            writeString(output, lifecycle.failure(), true);
            output.writeLong(lifecycle.occurredAt());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Player Lifecycle Failed", exception);
        }
    }

    public static NetworkPlayerLifecycle decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Byte.BYTES + Long.BYTES * 3 + Short.BYTES * 4 + 1) {
            throw new IllegalArgumentException("Network Player Lifecycle Payload Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readUnsignedShort() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Network Player Lifecycle Format Is Invalid");
            }
            int ordinal = input.readUnsignedByte();
            NetworkPlayerLifecycleType[] types = NetworkPlayerLifecycleType.values();
            if (ordinal >= types.length) {
                throw new IllegalArgumentException("Network Player Lifecycle Type Is Invalid");
            }
            UUID playerId = new UUID(input.readLong(), input.readLong());
            NetworkPlayerLifecycle lifecycle = new NetworkPlayerLifecycle(types[ordinal], playerId, readString(input, false), readString(input, true), readString(input, true), readString(input, true), input.readLong());
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Player Lifecycle Payload Has Trailing Data");
            }
            return lifecycle;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Player Lifecycle Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Player Lifecycle Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value, boolean optional) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if ((!optional && bytes.length == 0) || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Player Lifecycle Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, boolean optional) throws IOException {
        int length = input.readUnsignedShort();
        if ((!optional && length == 0) || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Player Lifecycle Text Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Player Lifecycle Text Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
