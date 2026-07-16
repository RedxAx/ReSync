package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NetworkEventCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 8192;
    private static final int MAXIMUM_PAYLOAD_BYTES = 524288;

    private NetworkEventCodec() {
    }

    public static byte[] encodePublish(NetworkEventPublish event) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, event.eventId());
            writeString(output, event.channel());
            writeOptionalString(output, event.subject());
            output.writeLong(event.createdAt());
            output.writeLong(event.expiresAt());
            writePayload(output, event.payload());
        });
    }

    public static NetworkEventPublish decodePublish(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String eventId = readString(input);
            String channel = readString(input);
            String subject = readOptionalString(input);
            long createdAt = input.readLong();
            long expiresAt = input.readLong();
            NetworkEventPublish event = new NetworkEventPublish(eventId, channel, subject, readPayload(input), createdAt, expiresAt);
            requireEnd(input);
            return event;
        });
    }

    public static byte[] encodeEvent(NetworkEvent event) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, event.eventId());
            writeString(output, event.networkId());
            writeString(output, event.channel());
            writeOptionalString(output, event.subject());
            writeString(output, event.originNodeId());
            output.writeLong(event.createdAt());
            output.writeLong(event.expiresAt());
            writePayload(output, event.payload());
        });
    }

    public static NetworkEvent decodeEvent(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String eventId = readString(input);
            String networkId = readString(input);
            String channel = readString(input);
            String subject = readOptionalString(input);
            String originNodeId = readString(input);
            long createdAt = input.readLong();
            long expiresAt = input.readLong();
            NetworkEvent event = new NetworkEvent(eventId, networkId, channel, subject, readPayload(input), originNodeId, createdAt, expiresAt);
            requireEnd(input);
            return event;
        });
    }

    public static byte[] encodeAcknowledgement(String eventId) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, NetworkValues.required(eventId, "Event ID"));
        });
    }

    public static String decodeAcknowledgement(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String eventId = readString(input);
            requireEnd(input);
            return eventId;
        });
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        int version = input.readUnsignedShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Network Event Format " + version);
        }
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Event Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null || payload.length < Short.BYTES) {
            throw new IllegalArgumentException("Network Event Payload Is Invalid");
        }
        try {
            return reader.read(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Event Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Event Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Event Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeOptionalString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Event Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Event Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static String readOptionalString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Event Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static void writePayload(DataOutputStream output, byte[] payload) throws IOException {
        if (payload.length > MAXIMUM_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Network Event Payload Is Too Large");
        }
        output.writeInt(payload.length);
        output.write(payload);
    }

    private static byte[] readPayload(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAXIMUM_PAYLOAD_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Event Payload Is Invalid");
        }
        return readExact(input, length);
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Event Payload Ended Early");
        }
        return bytes;
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Event Payload Has Trailing Data");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
