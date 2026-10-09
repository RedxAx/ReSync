package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class UnsubscribeRequest extends Message {
    private String channelId;

    @Override
    public MessageType getType() {
        return MessageType.UNSUBSCRIBE;
    }

    @Override
    public byte[] serialize() {
        byte[] channelIdBytes = channelId.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(4 + channelIdBytes.length);

        buffer.putInt(channelIdBytes.length);
        buffer.put(channelIdBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        channelId = readString(buffer, "Channel ID");
        requireComplete(buffer);
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }
}
