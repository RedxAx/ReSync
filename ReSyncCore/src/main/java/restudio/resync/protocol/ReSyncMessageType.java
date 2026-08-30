package restudio.resync.protocol;

public enum ReSyncMessageType {
    HANDSHAKE_REQUEST(ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST),
    HANDSHAKE_RESPONSE(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE),
    SUBSCRIBE(ReSyncProtocolContract.MESSAGE_SUBSCRIBE),
    UNSUBSCRIBE(ReSyncProtocolContract.MESSAGE_UNSUBSCRIBE),
    DATA(ReSyncProtocolContract.MESSAGE_DATA),
    HEARTBEAT(ReSyncProtocolContract.MESSAGE_HEARTBEAT),
    ACK(ReSyncProtocolContract.MESSAGE_ACK),
    ERROR(ReSyncProtocolContract.MESSAGE_ERROR),
    CHANNEL_REGISTRY(ReSyncProtocolContract.MESSAGE_CHANNEL_REGISTRY);

    private final byte value;

    ReSyncMessageType(int value) {
        this.value = (byte) value;
    }

    public byte value() {
        return value;
    }

    public int unsignedValue() {
        return Byte.toUnsignedInt(value);
    }

    public static ReSyncMessageType fromValue(byte value) {
        for (ReSyncMessageType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new ReSyncProtocolException(ReSyncProtocolException.Reason.UNKNOWN_MESSAGE_TYPE,
            "Unknown ReSync message type " + Byte.toUnsignedInt(value));
    }
}
