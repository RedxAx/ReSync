package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class Message {
    protected static final int MAX_FIELD_BYTES = 4_194_304;
    private static final int MAX_ENTRY_COUNT = 65_536;

    private int channel = 0;
    private int sequence = 0;

    public abstract MessageType getType();

    public abstract byte[] serialize();

    public abstract void deserialize(ByteBuffer buffer);

    protected static String readString(ByteBuffer buffer, String field) {
        return readString(buffer, field, MAX_FIELD_BYTES);
    }

    protected static String readString(ByteBuffer buffer, String field, int maxBytes) {
        requireBytes(buffer, Integer.BYTES, field + " length");
        int length = buffer.getInt();
        if (length < 0 || length > maxBytes || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid " + field + " length");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    protected static int readCount(ByteBuffer buffer, String field, int entryBytes) {
        requireBytes(buffer, Integer.BYTES, field + " count");
        int count = buffer.getInt();
        if (count < 0 || count > MAX_ENTRY_COUNT || count > buffer.remaining() / entryBytes) {
            throw new IllegalArgumentException("Invalid " + field + " count");
        }
        return count;
    }

    protected static void requireBytes(ByteBuffer buffer, int length, String field) {
        if (buffer.remaining() < length) {
            throw new IllegalArgumentException(field + " is incomplete");
        }
    }

    protected static void requireComplete(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("Trailing message bytes");
        }
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }
}
