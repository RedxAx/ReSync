package restudio.resync.protocol;

import java.nio.ByteBuffer;

public class FrameHeader {
    private boolean compressed;
    private boolean batch;
    private boolean hasAck;
    private MessageType messageType;
    private int channel;
    private int sequence;
    private int payloadLength;

    public FrameHeader() {
    }

    public FrameHeader(byte[] data) {
        if (data.length < 12) {
            throw new IllegalArgumentException("Frame header must be at least 12 bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte flags = buffer.get();
        compressed = (flags & 0x80) != 0;
        batch = (flags & 0x40) != 0;
        hasAck = (flags & 0x20) != 0;

        messageType = MessageType.fromValue(buffer.get());
        channel = buffer.getShort() & 0xFFFF;
        sequence = buffer.getInt();
        payloadLength = buffer.getInt();
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(12);

        byte flags = 0;
        if (compressed) flags |= 0x80;
        if (batch) flags |= 0x40;
        if (hasAck) flags |= 0x20;

        buffer.put(flags);
        buffer.put(messageType.getValue());
        buffer.putShort((short) channel);
        buffer.putInt(sequence);
        buffer.putInt(payloadLength);

        return buffer.array();
    }

    public boolean isCompressed() {
        return compressed;
    }

    public void setCompressed(boolean compressed) {
        this.compressed = compressed;
    }

    public boolean isBatch() {
        return batch;
    }

    public void setBatch(boolean batch) {
        this.batch = batch;
    }

    public boolean hasAck() {
        return hasAck;
    }

    public void setHasAck(boolean hasAck) {
        this.hasAck = hasAck;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        if (messageType == null) {
            throw new IllegalArgumentException("Message type is required");
        }
        this.messageType = messageType;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        if (channel < 0 || channel > 0xFFFF) {
            throw new IllegalArgumentException("Invalid channel: " + channel);
        }
        this.channel = channel;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public void setPayloadLength(int payloadLength) {
        if (payloadLength < 0) {
            throw new IllegalArgumentException("Negative payload length");
        }
        this.payloadLength = payloadLength;
    }

    public int getTotalLength() {
        return 12 + payloadLength;
    }
}
