package restudio.resync.protocol;

import org.java_websocket.WebSocket;
import restudio.resync.compression.CompressionPool;
import restudio.resync.protocol.messages.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class Codec {
    private final CompressionPool compressionPool;
    private final Map<Byte, Class<? extends Message>> messageTypes;
    private int sequenceCounter = 0;

    public Codec(CompressionPool compressionPool) {
        this.compressionPool = compressionPool;
        this.messageTypes = new HashMap<>();

        messageTypes.put((byte) 0x00, HandshakeRequest.class);
        messageTypes.put((byte) 0x01, HandshakeResponse.class);
        messageTypes.put((byte) 0x02, SubscribeRequest.class);
        messageTypes.put((byte) 0x03, UnsubscribeRequest.class);
        messageTypes.put((byte) 0x04, DataMessage.class);
        messageTypes.put((byte) 0x05, Heartbeat.class);
        messageTypes.put((byte) 0x06, AckMessage.class);
        messageTypes.put((byte) 0x07, ErrorMessage.class);
    }

    public byte[] encodeFrame(Message message, int channel, boolean compress) {
        return encodeFrame(message, channel, compress, false);
    }

    public byte[] encodeFrame(Message message, int channel, boolean compress, boolean batch) {
        byte[] payload = message.serialize();
        boolean actuallyCompressed = false;

        if (compress && payload.length > 1024) {
            payload = compressionPool.compress(payload);
            actuallyCompressed = true;
        }

        FrameHeader header = new FrameHeader();
        header.setCompressed(actuallyCompressed);
        header.setBatch(batch);
        header.setHasAck(false);
        header.setMessageType(message.getType());
        header.setChannel(channel);
        header.setSequence(sequenceCounter++);
        header.setPayloadLength(payload.length);

        byte[] headerBytes = header.toBytes();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(headerBytes.length + payload.length);
        baos.writeBytes(headerBytes);
        baos.writeBytes(payload);

        return baos.toByteArray();
    }

    public Frame decodeFrame(byte[] data) {
        if (data.length < 12) {
            throw new IllegalArgumentException("Frame too short");
        }

        FrameHeader header = new FrameHeader(data);
        byte[] payload = new byte[header.getPayloadLength()];

        if (data.length < 12 + header.getPayloadLength()) {
            throw new IllegalArgumentException("Incomplete frame");
        }

        System.arraycopy(data, 12, payload, 0, header.getPayloadLength());

        if (header.isCompressed()) {
            payload = compressionPool.decompress(payload);
        }

        return new Frame(header, payload);
    }

    public Message decodePayload(Frame frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame.payload);
        Class<? extends Message> messageClass = messageTypes.get(frame.header.getMessageType().getValue());

        if (messageClass == null) {
            throw new IllegalArgumentException("Unknown message type: " + frame.header.getMessageType());
        }

        try {
            Message message = messageClass.getDeclaredConstructor().newInstance();
            message.setChannel(frame.header.getChannel());
            message.setSequence(frame.header.getSequence());
            message.deserialize(buffer);
            return message;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode message", e);
        }
    }

    public void sendMessage(WebSocket conn, Message message, int channel, boolean compress) {
        byte[] frame = encodeFrame(message, channel, compress);
        conn.send(frame);
    }

    public int getNextSequence() {
        return sequenceCounter;
    }

    public void resetSequence() {
        sequenceCounter = 0;
    }

    public static class Frame {
        public final FrameHeader header;
        public final byte[] payload;

        public Frame(FrameHeader header, byte[] payload) {
            this.header = header;
            this.payload = payload;
        }
    }
}
