package restudio.resync.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncFrameCodecTest {
    @Test
    void roundTripsTypedEnvelopeFrames() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec();
        ReSyncFrame frame = new ReSyncFrame(ReSyncMessageType.PROTOCOL_ENVELOPE, 0, 17,
            "{}".getBytes(StandardCharsets.UTF_8), false, false, false);

        byte[] encoded = codec.encode(frame);

        assertEquals(9, Byte.toUnsignedInt(encoded[1]));
        assertEquals(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, encoded[1]);
        assertEquals(frame, codec.decode(encoded));
    }

    @Test
    void roundTripsCompressedTypedEnvelopeFrames() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec(256, 2048, ReSyncCompression.deflate(),
            ReSyncCompressionNegotiation.enabled("deflate", 0));
        ReSyncFrame frame = new ReSyncFrame(ReSyncMessageType.PROTOCOL_ENVELOPE, 0, 18,
            "typed-envelope".repeat(100).getBytes(StandardCharsets.UTF_8), true, false, false);

        byte[] encoded = codec.encode(frame);

        assertEquals(9, Byte.toUnsignedInt(encoded[1]));
        assertEquals(frame, codec.decode(encoded));
        ReSyncProtocolException failure = assertThrows(ReSyncProtocolException.class,
            () -> new ReSyncFrameCodec().decode(encoded));
        assertEquals(ReSyncProtocolException.Reason.UNSUPPORTED_COMPRESSION, failure.reason());
    }

    @Test
    void rejectsUnknownMessageTypes() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec();
        byte[] encoded = codec.encode(new ReSyncFrame(ReSyncMessageType.PROTOCOL_ENVELOPE, 0, 1,
            new byte[0], false, false, false));

        for (int type : new int[]{10, 127, 255}) {
            encoded[1] = (byte) type;
            ReSyncProtocolException failure = assertThrows(ReSyncProtocolException.class, () -> codec.decode(encoded));
            assertEquals(ReSyncProtocolException.Reason.UNKNOWN_MESSAGE_TYPE, failure.reason());
        }
    }

    @Test
    void preservesTypedEnvelopeFrameBounds() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec();
        byte[] encoded = codec.encode(new ReSyncFrame(ReSyncMessageType.PROTOCOL_ENVELOPE, 0, 1,
            new byte[]{1, 2}, false, false, false));

        ReSyncProtocolException incomplete = assertThrows(ReSyncProtocolException.class,
            () -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertEquals(ReSyncProtocolException.Reason.INCOMPLETE_FRAME, incomplete.reason());
        ReSyncProtocolException trailing = assertThrows(ReSyncProtocolException.class,
            () -> codec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        assertEquals(ReSyncProtocolException.Reason.TRAILING_FRAME_BYTES, trailing.reason());

        encoded[0] = 1;
        ReSyncProtocolException flags = assertThrows(ReSyncProtocolException.class, () -> codec.decode(encoded));
        assertEquals(ReSyncProtocolException.Reason.INVALID_FLAGS, flags.reason());
    }

    @Test
    void preservesTypedEnvelopePayloadLimits() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec(64, 8);
        ReSyncFrame frame = new ReSyncFrame(ReSyncMessageType.PROTOCOL_ENVELOPE, 0, 1,
            new byte[9], false, false, false);

        ReSyncProtocolException encode = assertThrows(ReSyncProtocolException.class, () -> codec.encode(frame));
        assertEquals(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE, encode.reason());
        byte[] encoded = new ReSyncFrameCodec().encode(frame);
        ReSyncProtocolException decode = assertThrows(ReSyncProtocolException.class, () -> codec.decode(encoded));
        assertEquals(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE, decode.reason());
    }
}
