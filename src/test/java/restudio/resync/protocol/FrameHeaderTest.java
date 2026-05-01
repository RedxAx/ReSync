package restudio.resync.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameHeaderTest {
    @Test
    void rejectsShortHeader() {
        assertThrows(IllegalArgumentException.class, () -> new FrameHeader(new byte[11]));
    }

    @Test
    void rejectsUnknownMessageType() {
        byte[] header = new byte[12];
        header[1] = (byte) 0x55;
        assertThrows(IllegalArgumentException.class, () -> new FrameHeader(header));
    }

    @Test
    void rejectsInvalidChannelSetter() {
        FrameHeader header = new FrameHeader();
        assertThrows(IllegalArgumentException.class, () -> header.setChannel(0x1_0000));
    }

    @Test
    void rejectsNegativePayloadLengthSetter() {
        FrameHeader header = new FrameHeader();
        assertThrows(IllegalArgumentException.class, () -> header.setPayloadLength(-1));
    }
}
