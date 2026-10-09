package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;

public class AckMessage extends Message {
    private int acknowledgedSequence;

    @Override
    public MessageType getType() {
        return MessageType.ACK;
    }

    @Override
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(acknowledgedSequence);
        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        requireBytes(buffer, Integer.BYTES, "AckMessage payload");
        acknowledgedSequence = buffer.getInt();
        requireComplete(buffer);
    }

    public int getAcknowledgedSequence() {
        return acknowledgedSequence;
    }

    public void setAcknowledgedSequence(int acknowledgedSequence) {
        this.acknowledgedSequence = acknowledgedSequence;
    }
}
