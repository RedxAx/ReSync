package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NetworkChatMessageCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_IDENTITY_BYTES = 8192;
    private static final int MAXIMUM_MESSAGE_BYTES = 262144;

    private NetworkChatMessageCodec() {
    }

    public static byte[] encode(NetworkChatMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(FORMAT_VERSION);
            output.writeLong(message.playerId().getMostSignificantBits());
            output.writeLong(message.playerId().getLeastSignificantBits());
            writeString(output, message.playerName(), MAXIMUM_IDENTITY_BYTES);
            writeString(output, message.displayName(), MAXIMUM_IDENTITY_BYTES);
            writeString(output, message.channelId(), MAXIMUM_IDENTITY_BYTES);
            writeString(output, message.message(), MAXIMUM_MESSAGE_BYTES);
            output.writeLong(message.sentAt());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Chat Message Failed", exception);
        }
    }

    public static NetworkChatMessage decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Long.BYTES * 3 + Integer.BYTES * 4 + 4) {
            throw new IllegalArgumentException("Network Chat Message Payload Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readUnsignedShort() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Network Chat Message Format Is Invalid");
            }
            UUID playerId = new UUID(input.readLong(), input.readLong());
            NetworkChatMessage message = new NetworkChatMessage(playerId, readString(input, MAXIMUM_IDENTITY_BYTES), readString(input, MAXIMUM_IDENTITY_BYTES), readString(input, MAXIMUM_IDENTITY_BYTES), readString(input, MAXIMUM_MESSAGE_BYTES), input.readLong());
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Chat Message Payload Has Trailing Data");
            }
            return message;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Chat Message Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Chat Message Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Network Chat Message Text Is Invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximumBytes || length > input.available()) {
            throw new IllegalArgumentException("Network Chat Message Text Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Chat Message Text Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
