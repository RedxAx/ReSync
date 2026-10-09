package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.compression.CompressionPool;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.protocol.messages.HandshakeResponse;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecCompressionTest {
    @Test
    void beneficialCompressionProducesTheExactCompressedFrame() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            byte[] payload = new byte[64 * 1024];
            Arrays.fill(payload, (byte) 0x4A);
            DataMessage message = message(payload);

            byte[] frameBytes = codec.encodeFrame(message, 3, true);
            Codec.Frame frame = codec.decodeFrame(frameBytes);

            assertTrue(frame.header.isCompressed());
            assertTrue(frameBytes.length < payload.length + 12);
            assertArrayEquals(payload, frame.payload);
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void expandingCompressionFallsBackToTheExactRawFrame() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            byte[] payload = new byte[64 * 1024];
            new Random(0x3A17D5E9L).nextBytes(payload);
            DataMessage message = message(payload);

            byte[] frameBytes = codec.encodeFrame(message, 3, true);
            Codec.Frame frame = codec.decodeFrame(frameBytes);

            assertFalse(frame.header.isCompressed());
            assertEquals(payload.length + 12, frameBytes.length);
            assertArrayEquals(payload, frame.payload);
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void compressedFrameCannotInflateBeyondTheDecodedPayloadLimit() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            byte[] payload = new byte[Codec.DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES + 1];
            Arrays.fill(payload, (byte) 0x2D);
            byte[] frame = codec.encodeFrame(message(payload), 3, true);

            assertTrue(new FrameHeader(frame).isCompressed());
            assertThrows(IllegalArgumentException.class, () -> codec.decodeFrame(frame));
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void rejectedFrameDoesNotConsumeASequence() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool, 12, 4096);

            assertThrows(Codec.FrameTooLargeException.class,
                () -> codec.encodeFrame(message(new byte[]{1}), 3, false));
            assertEquals(0, codec.getNextSequence());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void compressedFrameRejectsMalformedIncompleteAndTrailingInput() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            byte[] payload = new byte[4096];
            Arrays.fill(payload, (byte) 0x53);
            byte[] compressed = compressionPool.compress(payload);
            byte[] incomplete = Arrays.copyOf(compressed, compressed.length - 1);
            byte[] trailing = Arrays.copyOf(compressed, compressed.length + 1);
            trailing[trailing.length - 1] = 1;

            assertThrows(IllegalArgumentException.class, () -> codec.decodeFrame(compressedFrame(new byte[]{1, 2, 3})));
            assertThrows(IllegalArgumentException.class, () -> codec.decodeFrame(compressedFrame(incomplete)));
            assertThrows(IllegalArgumentException.class, () -> codec.decodeFrame(compressedFrame(trailing)));
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void declaredInnerLengthsCannotAmplifyAnAdmittedFrame() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            int declared = Codec.DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES + 1;
            byte[] length = ByteBuffer.allocate(4).putInt(declared).array();
            FrameHeader header = new FrameHeader();
            header.setMessageType(MessageType.SUBSCRIBE);
            header.setPayloadLength(length.length);
            byte[] encoded = Arrays.copyOf(header.toBytes(), 12 + length.length);
            System.arraycopy(length, 0, encoded, 12, length.length);
            Codec.Frame frame = codec.decodeFrame(encoded);

            RuntimeException failure = assertThrows(RuntimeException.class, () -> codec.decodePayload(frame));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertThrows(IllegalArgumentException.class, () -> new SubscribeRequest().deserialize(ByteBuffer.wrap(length)));
            assertThrows(IllegalArgumentException.class, () -> new UnsubscribeRequest().deserialize(ByteBuffer.wrap(length)));
            assertThrows(IllegalArgumentException.class, () -> new ErrorMessage().deserialize(
                ByteBuffer.allocate(8).putInt(0).putInt(declared).flip()));
            assertThrows(IllegalArgumentException.class, () -> new HandshakeResponse().deserialize(
                ByteBuffer.allocate(5).put((byte) 1).putInt(declared).flip()));
            assertThrows(IllegalArgumentException.class, () -> new HandshakeResponse().deserialize(
                ByteBuffer.allocate(17).put((byte) 1).putInt(0).putInt(2).putInt(0).putInt(declared).flip()));
            assertThrows(IllegalArgumentException.class, () -> codec.decodeFrame(Arrays.copyOf(encoded, encoded.length + 1)));

            HandshakeResponse response = new HandshakeResponse();
            response.setMessage("Ready");
            assertInstanceOf(HandshakeResponse.class, codec.decodePayload(codec.decodeFrame(codec.encodeFrame(response, 0, false))));
            assertFalse(Codec.isClientMessage(MessageType.HANDSHAKE_RESPONSE));
        } finally {
            compressionPool.close();
        }
    }

    private static DataMessage message(byte[] payload) {
        DataMessage message = new DataMessage();
        message.setPayload(payload);
        return message;
    }

    private static byte[] compressedFrame(byte[] payload) {
        FrameHeader header = new FrameHeader();
        header.setCompressed(true);
        header.setMessageType(MessageType.DATA);
        header.setChannel(3);
        header.setSequence(1);
        header.setPayloadLength(payload.length);
        byte[] frame = Arrays.copyOf(header.toBytes(), 12 + payload.length);
        System.arraycopy(payload, 0, frame, 12, payload.length);
        return frame;
    }
}
