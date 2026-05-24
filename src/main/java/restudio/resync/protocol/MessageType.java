package restudio.resync.protocol;

import java.nio.ByteBuffer;

public enum MessageType {
    HANDSHAKE_REQUEST(0x00),
    HANDSHAKE_RESPONSE(0x01),
    SUBSCRIBE(0x02),
    UNSUBSCRIBE(0x03),
    DATA(0x04),
    HEARTBEAT(0x05),
    ACK(0x06),
    ERROR(0x07),
    CHANNEL_REGISTRY(0x08);

    private final byte value;

    MessageType(int value) {
        this.value = (byte) value;
    }

    public byte getValue() {
        return value;
    }

    public static MessageType fromValue(byte value) {
        for (MessageType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + (value & 0xFF));
    }
}
