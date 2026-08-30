package restudio.resync.protocol;

public final class ReSyncProtocolException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_CONFIGURATION,
        FRAME_NULL,
        FRAME_TOO_SHORT,
        FRAME_TOO_LARGE,
        INVALID_FLAGS,
        UNKNOWN_MESSAGE_TYPE,
        INVALID_CHANNEL,
        INVALID_PAYLOAD_LENGTH,
        INCOMPLETE_FRAME,
        TRAILING_FRAME_BYTES,
        PAYLOAD_TOO_LARGE,
        UNSUPPORTED_COMPRESSION,
        COMPRESSION_FAILED,
        DECOMPRESSION_FAILED,
        MALFORMED_HANDSHAKE,
        MALFORMED_MESSAGE,
        HANDSHAKE_FIELD_TOO_LARGE,
        HANDSHAKE_COLLECTION_TOO_LARGE
    }

    private final Reason reason;

    public ReSyncProtocolException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReSyncProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
