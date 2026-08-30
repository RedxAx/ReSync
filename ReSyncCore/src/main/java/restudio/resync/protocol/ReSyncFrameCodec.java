package restudio.resync.protocol;

import java.nio.ByteBuffer;

public final class ReSyncFrameCodec {
    public static final int HEADER_BYTES = ReSyncProtocolContract.FRAME_HEADER_BYTES;
    public static final int DEFAULT_MAX_ENCODED_FRAME_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    public static final int DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES = ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;

    private final int maximumEncodedFrameBytes;
    private final int maximumDecompressedPayloadBytes;
    private final ReSyncCompression compression;
    private final ReSyncCompressionNegotiation negotiation;

    public ReSyncFrameCodec() {
        this(DEFAULT_MAX_ENCODED_FRAME_BYTES, DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES, null,
            ReSyncCompressionNegotiation.disabled());
    }

    public ReSyncFrameCodec(int maximumEncodedFrameBytes, int maximumDecompressedPayloadBytes) {
        this(maximumEncodedFrameBytes, maximumDecompressedPayloadBytes, null, ReSyncCompressionNegotiation.disabled());
    }

    public ReSyncFrameCodec(int maximumEncodedFrameBytes, int maximumDecompressedPayloadBytes,
                            ReSyncCompression compression, ReSyncCompressionNegotiation negotiation) {
        if (maximumEncodedFrameBytes < HEADER_BYTES || maximumDecompressedPayloadBytes < 1) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync frame limits are invalid");
        }
        if (negotiation == null) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync compression negotiation is required");
        }
        if (negotiation.enabled() && !negotiation.supports(compression)) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync compression adapter does not implement the negotiated algorithm");
        }
        this.maximumEncodedFrameBytes = maximumEncodedFrameBytes;
        this.maximumDecompressedPayloadBytes = maximumDecompressedPayloadBytes;
        this.compression = compression;
        this.negotiation = negotiation;
    }

    public byte[] encode(ReSyncFrame frame) {
        return encode(frame, frame.compressed());
    }

    public byte[] encode(ReSyncFrame frame, boolean compressionRequested) {
        if (frame == null) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.FRAME_NULL,
                "ReSync frame is required");
        }
        byte[] payload = frame.payload();
        requireDecompressedPayloadLimit(payload.length);
        boolean compressed = false;
        if (compressionRequested && payload.length > negotiation.thresholdBytes()) {
            if (!negotiation.enabled()) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.UNSUPPORTED_COMPRESSION,
                    "ReSync compression was requested without a negotiated algorithm");
            }
            if (!negotiation.supports(compression)) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.UNSUPPORTED_COMPRESSION,
                    "Negotiated ReSync compression is unavailable");
            }
            try {
                payload = compression.compress(payload);
            } catch (RuntimeException exception) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.COMPRESSION_FAILED,
                    "ReSync compression failed", exception);
            }
            if (payload == null) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.COMPRESSION_FAILED,
                    "ReSync compression returned no payload");
            }
            compressed = true;
        }
        if (payload.length > maximumEncodedFrameBytes - HEADER_BYTES) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "Encoded ReSync payload exceeds the configured limit");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        int flags = (compressed ? ReSyncProtocolContract.FRAME_FLAG_COMPRESSED : 0)
            | (frame.batch() ? ReSyncProtocolContract.FRAME_FLAG_BATCH : 0)
            | (frame.hasAck() ? ReSyncProtocolContract.FRAME_FLAG_ACK : 0);
        buffer.put((byte) flags);
        buffer.put(frame.messageType().value());
        buffer.putShort((short) frame.channel());
        buffer.putInt(frame.sequence());
        buffer.putInt(payload.length);
        buffer.put(payload);
        byte[] encoded = buffer.array();
        if (encoded.length > maximumEncodedFrameBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.FRAME_TOO_LARGE,
                "Encoded ReSync frame exceeds the configured limit");
        }
        return encoded;
    }

    public ReSyncFrame decode(byte[] encoded) {
        if (encoded == null) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.FRAME_NULL,
                "Encoded ReSync frame is required");
        }
        if (encoded.length < HEADER_BYTES) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.FRAME_TOO_SHORT,
                "Encoded ReSync frame is shorter than its header");
        }
        if (encoded.length > maximumEncodedFrameBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.FRAME_TOO_LARGE,
                "Encoded ReSync frame exceeds the configured limit");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        int flags = Byte.toUnsignedInt(buffer.get());
        if ((flags & ReSyncProtocolContract.FRAME_RESERVED_FLAGS) != 0) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_FLAGS,
                "Encoded ReSync frame contains unsupported flags");
        }
        ReSyncMessageType messageType = ReSyncMessageType.fromValue(buffer.get());
        int channel = Short.toUnsignedInt(buffer.getShort());
        int sequence = buffer.getInt();
        int payloadLength = buffer.getInt();
        if (payloadLength < 0 || payloadLength > maximumEncodedFrameBytes - HEADER_BYTES) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_PAYLOAD_LENGTH,
                "Encoded ReSync payload length is invalid");
        }
        int expectedLength = HEADER_BYTES + payloadLength;
        if (encoded.length < expectedLength) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INCOMPLETE_FRAME,
                "Encoded ReSync frame ends before its payload");
        }
        if (encoded.length > expectedLength) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.TRAILING_FRAME_BYTES,
                "Encoded ReSync frame contains trailing bytes");
        }
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        boolean compressed = (flags & ReSyncProtocolContract.FRAME_FLAG_COMPRESSED) != 0;
        if (compressed) {
            if (!negotiation.supports(compression)) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.UNSUPPORTED_COMPRESSION,
                    "Encoded ReSync frame uses unavailable compression");
            }
            try {
                payload = compression.decompress(payload, maximumDecompressedPayloadBytes);
            } catch (ReSyncProtocolException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                    "ReSync decompression failed", exception);
            }
            if (payload == null) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                    "ReSync decompression returned no payload");
            }
            if (payload.length > maximumDecompressedPayloadBytes) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                    "Decompressed ReSync payload exceeds the configured limit");
            }
        } else {
            requireDecompressedPayloadLimit(payload.length);
        }
        return new ReSyncFrame(messageType, channel, sequence, payload, compressed,
            (flags & ReSyncProtocolContract.FRAME_FLAG_BATCH) != 0,
            (flags & ReSyncProtocolContract.FRAME_FLAG_ACK) != 0);
    }

    public int maximumEncodedFrameBytes() {
        return maximumEncodedFrameBytes;
    }

    public int maximumDecompressedPayloadBytes() {
        return maximumDecompressedPayloadBytes;
    }

    public ReSyncCompressionNegotiation negotiation() {
        return negotiation;
    }

    private void requireDecompressedPayloadLimit(int length) {
        if (length > maximumDecompressedPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "ReSync payload exceeds the configured limit");
        }
    }
}
