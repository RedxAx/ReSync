package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Heartbeat extends Message {
    private long timestamp;

    @Override
    public MessageType getType() {
        return MessageType.HEARTBEAT;
    }

    @Override
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(timestamp);
        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        timestamp = buffer.getLong();
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
