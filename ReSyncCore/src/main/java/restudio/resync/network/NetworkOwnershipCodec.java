package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NetworkOwnershipCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 4096;

    private NetworkOwnershipCodec() {
    }

    public static byte[] encode(PlayerLease lease) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(FORMAT_VERSION);
            writeString(output, lease.networkId(), false);
            output.writeLong(lease.playerId().getMostSignificantBits());
            output.writeLong(lease.playerId().getLeastSignificantBits());
            writeString(output, lease.ownerNodeId(), true);
            writeString(output, lease.pendingNodeId(), true);
            output.writeLong(lease.fenceEpoch());
            output.writeLong(lease.leaseExpiresAt());
            output.writeLong(lease.updatedAt());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Player Ownership Failed", exception);
        }
    }

    public static PlayerLease decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Short.BYTES * 3 + Long.BYTES * 5) {
            throw new IllegalArgumentException("Network Player Ownership Payload Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            int version = input.readUnsignedShort();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Network Player Ownership Format " + version);
            }
            PlayerLease lease = new PlayerLease(readString(input, false), new UUID(input.readLong(), input.readLong()), readString(input, true), readString(input, true), input.readLong(), input.readLong(), input.readLong());
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Player Ownership Payload Has Trailing Data");
            }
            return lease;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Player Ownership Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Player Ownership Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value, boolean optional) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if ((!optional && bytes.length == 0) || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Player Ownership Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, boolean optional) throws IOException {
        int length = input.readUnsignedShort();
        if ((!optional && length == 0) || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Player Ownership Text Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Player Ownership Text Ended Early");
        }
        return new String(value, StandardCharsets.UTF_8);
    }
}
