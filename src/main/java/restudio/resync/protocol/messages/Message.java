package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;

public abstract class Message {
    private int channel = 0;
    private int sequence = 0;

    public abstract MessageType getType();

    public abstract byte[] serialize();

    public abstract void deserialize(ByteBuffer buffer);

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
