package restudio.resync.flow.cache;

import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class CatalogPublicationReceiptPacket {
    private static final int MAX_KEY_BYTES = 4096;
    private static final int MAX_DIAGNOSTIC_BYTES = 4096;

    private CatalogPublicationReceiptPacket() {
    }

    public static byte[] encodeClientReceived(CatalogCacheKey key, long revision) {
        return encode(new Packet(Kind.CLIENT_RECEIVED, key, revision, ""));
    }

    public static byte[] encodeCacheApplied(CatalogCacheKey key, long revision) {
        return encode(new Packet(Kind.CACHE_APPLIED, key, revision, ""));
    }

    public static byte[] encodeCacheRejected(CatalogCacheKey key, long revision, String diagnosticCode) {
        return encode(new Packet(Kind.CACHE_REJECTED, key, revision, diagnosticCode));
    }

    public static Packet decode(byte[] payload) {
        Objects.requireNonNull(payload, "Receipt packet payload is required");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (!buffer.hasRemaining()) {
                throw new IllegalArgumentException("Receipt packet payload is empty");
            }
            Kind kind = Kind.from(buffer.get());
            return decode(kind, buffer);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw new IllegalArgumentException("Invalid catalog publication receipt packet", exception);
        }
    }

    public static Packet decode(byte packetId, ByteBuffer payload) {
        Objects.requireNonNull(payload, "Receipt packet payload is required");
        return decode(Kind.from(packetId), payload);
    }

    public static Packet decode(byte packetId, byte[] payload) {
        Objects.requireNonNull(payload, "Receipt packet payload is required");
        return decode(packetId, ByteBuffer.wrap(payload));
    }

    private static Packet decode(Kind kind, ByteBuffer buffer) {
        String keyText = readText(buffer, MAX_KEY_BYTES, "publication key");
        CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(keyText);
        if (!key.canonicalText().equals(keyText) || buffer.remaining() < Long.BYTES) {
            throw new IllegalArgumentException("Receipt packet publication key is not canonical");
        }
        long revision = buffer.getLong();
        String diagnosticCode = kind == Kind.CACHE_REJECTED
            ? readText(buffer, MAX_DIAGNOSTIC_BYTES, "diagnostic code") : "";
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("Receipt packet contains trailing bytes");
        }
        return new Packet(kind, key, revision, diagnosticCode);
    }

    private static byte[] encode(Packet packet) {
        byte[] key = packet.key().canonicalText().getBytes(StandardCharsets.UTF_8);
        if (key.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Publication key is too large");
        }
        byte[] diagnostic = packet.diagnosticCode().getBytes(StandardCharsets.UTF_8);
        if (diagnostic.length > MAX_DIAGNOSTIC_BYTES) {
            throw new IllegalArgumentException("Receipt diagnostic code is too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + key.length + Long.BYTES
            + (packet.kind() == Kind.CACHE_REJECTED ? Integer.BYTES + diagnostic.length : 0));
        buffer.put(packet.kind().packetId());
        buffer.putInt(key.length);
        buffer.put(key);
        buffer.putLong(packet.revision());
        if (packet.kind() == Kind.CACHE_REJECTED) {
            buffer.putInt(diagnostic.length);
            buffer.put(diagnostic);
        }
        return buffer.array();
    }

    private static String readText(ByteBuffer buffer, int maximumBytes, String label) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Receipt packet " + label + " length is missing");
        }
        int length = buffer.getInt();
        if (length < 0 || length > maximumBytes || length > buffer.remaining()) {
            throw new IllegalArgumentException("Receipt packet " + label + " length is invalid");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Receipt packet " + label + " is not valid UTF-8", exception);
        }
    }

    public enum Kind {
        CLIENT_RECEIVED(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED),
        CACHE_APPLIED(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED),
        CACHE_REJECTED(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED);

        private final byte packetId;

        Kind(byte packetId) {
            this.packetId = packetId;
        }

        public byte packetId() {
            return packetId;
        }

        private static Kind from(byte packetId) {
            for (Kind value : values()) {
                if (value.packetId == packetId) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown catalog publication receipt packet");
        }
    }

    public record Packet(Kind kind, CatalogCacheKey key, long revision, String diagnosticCode) {
        public Packet {
            kind = Objects.requireNonNull(kind, "Receipt packet kind is required");
            key = Objects.requireNonNull(key, "Receipt packet key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Receipt packet revision must not be negative");
            }
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
            if (!diagnosticCode.equals(diagnosticCode.strip())) {
                throw new IllegalArgumentException("Receipt diagnostic code must be canonical");
            }
            if (kind == Kind.CACHE_REJECTED && diagnosticCode.isBlank()) {
                throw new IllegalArgumentException("Rejected cache application requires a diagnostic code");
            }
            if (kind != Kind.CACHE_REJECTED && !diagnosticCode.isBlank()) {
                throw new IllegalArgumentException("Successful receipt packets cannot carry a diagnostic code");
            }
        }
    }
}
