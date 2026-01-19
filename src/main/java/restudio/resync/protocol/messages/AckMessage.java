package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
        acknowledgedSequence = buffer.getInt();
    }

    public int getAcknowledgedSequence() {
        return acknowledgedSequence;
    }

    public void setAcknowledgedSequence(int acknowledgedSequence) {
        this.acknowledgedSequence = acknowledgedSequence;
    }
}
