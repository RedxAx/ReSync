package restudio.resync.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ReSyncMessagePayloadCodec {
    private ReSyncMessagePayloadCodec() {
    }

    public static byte[] encodeAcknowledgement(int sequence) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(sequence).array();
    }

    public static int decodeAcknowledgement(byte[] payload) {
        return exact(payload, Integer.BYTES, "acknowledgement").getInt();
    }

    public static byte[] encodeHeartbeat(long timestamp) {
        return ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();
    }

    public static long decodeHeartbeat(byte[] payload) {
        return exact(payload, Long.BYTES, "heartbeat").getLong();
    }

    public static byte[] encodeSubscription(String channelId, String data) {
        byte[] channel = text(channelId, "subscription channel");
        byte[] metadata = text(data == null ? "" : data, "subscription data");
        return ByteBuffer.allocate(Integer.BYTES * 2 + channel.length + metadata.length)
            .putInt(channel.length).put(channel).putInt(metadata.length).put(metadata).array();
    }

    public static Subscription decodeSubscription(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload(payload));
        String channelId = readText(buffer, "subscription channel");
        String data = buffer.hasRemaining() ? readText(buffer, "subscription data") : null;
        requireConsumed(buffer);
        return new Subscription(channelId, data);
    }

    public static byte[] encodeUnsubscription(String channelId) {
        byte[] channel = text(channelId, "unsubscription channel");
        return ByteBuffer.allocate(Integer.BYTES + channel.length).putInt(channel.length).put(channel).array();
    }

    public static String decodeUnsubscription(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload(payload));
        String channelId = readText(buffer, "unsubscription channel");
        requireConsumed(buffer);
        return channelId;
    }

    public static byte[] encodeError(int code, String message) {
        byte[] text = text(message == null ? "" : message, "error message");
        return ByteBuffer.allocate(Integer.BYTES * 2 + text.length).putInt(code).putInt(text.length).put(text).array();
    }

    public static ErrorPayload decodeError(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload(payload));
        if (buffer.remaining() < Integer.BYTES) {
            throw malformed("ReSync error payload is incomplete");
        }
        int code = buffer.getInt();
        String message = readText(buffer, "error message");
        requireConsumed(buffer);
        return new ErrorPayload(code, message);
    }

    public static byte[] payload(byte[] payload) {
        byte[] value = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (value.length > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "ReSync message payload exceeds the shared limit");
        }
        return value;
    }

    public static byte[] encodeText(String value) {
        return payload((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeText(byte[] payload) {
        return new String(payload(payload), StandardCharsets.UTF_8);
    }

    private static ByteBuffer exact(byte[] payload, int length, String type) {
        ByteBuffer buffer = ByteBuffer.wrap(payload(payload));
        if (buffer.remaining() != length) {
            throw malformed("ReSync " + type + " payload has an invalid length");
        }
        return buffer;
    }

    private static byte[] text(String value, String field) {
        if (value == null) {
            throw malformed("ReSync " + field + " is required");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ReSyncProtocolContract.MAX_MESSAGE_FIELD_BYTES) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.HANDSHAKE_FIELD_TOO_LARGE,
                "ReSync " + field + " exceeds the shared limit");
        }
        return bytes;
    }

    private static String readText(ByteBuffer buffer, String field) {
        if (buffer.remaining() < Integer.BYTES) {
            throw malformed("ReSync " + field + " length is missing");
        }
        int length = buffer.getInt();
        if (length < 0 || length > ReSyncProtocolContract.MAX_MESSAGE_FIELD_BYTES || length > buffer.remaining()) {
            throw malformed("ReSync " + field + " length is invalid");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireConsumed(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.TRAILING_FRAME_BYTES,
                "ReSync message payload contains trailing bytes");
        }
    }

    private static ReSyncProtocolException malformed(String message) {
        return new ReSyncProtocolException(ReSyncProtocolException.Reason.MALFORMED_MESSAGE, message);
    }

    public record Subscription(String channelId, String data) {
    }

    public record ErrorPayload(int code, String message) {
    }
}
