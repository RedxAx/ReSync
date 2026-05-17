package restudio.resync.protocol;

import org.java_websocket.WebSocket;
import restudio.resync.compression.CompressionPool;
import restudio.resync.protocol.messages.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Codec {
    public static final int DEFAULT_MAX_ENCODED_FRAME_BYTES = 1_048_576;
    public static final int DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES = 4_194_304;
    private final CompressionPool compressionPool;
    private final Map<Byte, Class<? extends Message>> messageTypes;
    private final AtomicInteger sequenceCounter = new AtomicInteger();
    private final int maxEncodedFrameBytes;
    private final int maxDecompressedPayloadBytes;

    public Codec(CompressionPool compressionPool) {
        this(compressionPool, DEFAULT_MAX_ENCODED_FRAME_BYTES, DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES);
    }

    public Codec(CompressionPool compressionPool, int maxEncodedFrameBytes, int maxDecompressedPayloadBytes) {
        this.compressionPool = compressionPool;
        this.maxEncodedFrameBytes = Math.max(12, maxEncodedFrameBytes);
        this.maxDecompressedPayloadBytes = Math.max(1, maxDecompressedPayloadBytes);
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
        header.setSequence(sequenceCounter.getAndIncrement());
        header.setPayloadLength(payload.length);

        byte[] headerBytes = header.toBytes();
        if (headerBytes.length + payload.length > maxEncodedFrameBytes) {
            throw new IllegalArgumentException("Encoded frame too large");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(headerBytes.length + payload.length);
        baos.writeBytes(headerBytes);
        baos.writeBytes(payload);

        return baos.toByteArray();
    }

    public Frame decodeFrame(byte[] data) {
        if (data.length < 12) {
            throw new IllegalArgumentException("Frame too short");
        }
        if (data.length > maxEncodedFrameBytes) {
            throw new IllegalArgumentException("Frame too large");
        }

        FrameHeader header = new FrameHeader(data);
        if (header.getPayloadLength() < 0) {
            throw new IllegalArgumentException("Negative payload length");
        }
        if (header.getPayloadLength() > maxEncodedFrameBytes - 12) {
            throw new IllegalArgumentException("Payload too large");
        }
        byte[] payload = new byte[header.getPayloadLength()];

        if (header.getPayloadLength() > data.length - 12) {
            throw new IllegalArgumentException("Incomplete frame");
        }

        System.arraycopy(data, 12, payload, 0, header.getPayloadLength());

        if (header.isCompressed()) {
            payload = compressionPool.decompress(payload);
            if (payload.length > maxDecompressedPayloadBytes) {
                throw new IllegalArgumentException("Decompressed payload too large");
            }
        } else if (payload.length > maxDecompressedPayloadBytes) {
            throw new IllegalArgumentException("Payload too large");
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

    public void sendMessage(FrameSender sender, Message message, int channel, boolean compress) {
        byte[] frame = encodeFrame(message, channel, compress);
        sender.send(frame);
    }

    public int getNextSequence() {
        return sequenceCounter.get();
    }

    public void resetSequence() {
        sequenceCounter.set(0);
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
