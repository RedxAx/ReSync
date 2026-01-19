package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;

public class DataMessage extends Message {
    private byte[] payload;
    private int channel;
    private boolean isServerResponse;

    @Override
    public MessageType getType() {
        return MessageType.DATA;
    }

    @Override
    public byte[] serialize() {
        return payload != null ? payload : new byte[0];
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        int payloadLength = buffer.remaining();
        payload = new byte[payloadLength];
        buffer.get(payload);
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public boolean isServerResponse() {
        return isServerResponse;
    }

    public void setServerResponse(boolean serverResponse) {
        isServerResponse = serverResponse;
    }
}
