package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class SubscribeRequest extends Message {
    private String channelId;
    private String data;

    @Override
    public MessageType getType() {
        return MessageType.SUBSCRIBE;
    }

    @Override
    public byte[] serialize() {
        byte[] channelIdBytes = channelId.getBytes(StandardCharsets.UTF_8);
        byte[] dataBytes = data != null ? data.getBytes(StandardCharsets.UTF_8) : new byte[0];

        ByteBuffer buffer = ByteBuffer.allocate(4 + channelIdBytes.length + 4 + dataBytes.length);

        buffer.putInt(channelIdBytes.length);
        buffer.put(channelIdBytes);

        buffer.putInt(dataBytes.length);
        buffer.put(dataBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        int channelIdLen = buffer.getInt();
        byte[] channelIdBytes = new byte[channelIdLen];
        buffer.get(channelIdBytes);
        channelId = new String(channelIdBytes, StandardCharsets.UTF_8);

        if (buffer.remaining() >= 4) {
            int dataLen = buffer.getInt();
            if (dataLen > 0 && buffer.remaining() >= dataLen) {
                byte[] dataBytes = new byte[dataLen];
                buffer.get(dataBytes);
                data = new String(dataBytes, StandardCharsets.UTF_8);
            }
        }
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
