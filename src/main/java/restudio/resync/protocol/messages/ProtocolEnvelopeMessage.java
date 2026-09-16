package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ProtocolEnvelopeMessage extends Message {
    private byte[] payload = new byte[0];

    @Override
    public MessageType getType() {
        return MessageType.PROTOCOL_ENVELOPE;
    }

    @Override
    public byte[] serialize() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            throw new IllegalArgumentException("Protocol envelope payload is required");
        }
        payload = new byte[buffer.remaining()];
        buffer.get(payload);
    }

    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public void setPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Protocol envelope payload is required");
        }
        this.payload = Arrays.copyOf(payload, payload.length);
    }
}
