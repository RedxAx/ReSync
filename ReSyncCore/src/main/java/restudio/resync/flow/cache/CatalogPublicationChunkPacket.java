package restudio.resync.flow.cache;

import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CatalogPublicationChunkPacket {
    public static final byte VERSION = 1;
    public static final int MAX_PUBLICATION_BYTES = 32 * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 256 * 1024;
    public static final int MAX_CHUNKS = 2048;
    public static final int SHA256_BYTES = 32;
    public static final int HEADER_BYTES = 1 + 1 + Integer.BYTES * 4 + SHA256_BYTES;

    private CatalogPublicationChunkPacket() {
    }

    public static List<Chunk> split(byte[] canonical) {
        return split(canonical, MAX_CHUNK_BYTES);
    }

    public static List<Chunk> split(byte[] canonical, int maxChunkDataBytes) {
        byte[] value = requirePublication(canonical);
        int chunkDataBytes = requireChunkDataBytes(maxChunkDataBytes);
        byte[] digest = sha256(value);
        int chunkCount = chunkCount(value.length, chunkDataBytes);
        List<Chunk> chunks = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int offset = (int) ((long) index * chunkDataBytes);
            int length = expectedChunkLength(value.length, index, chunkDataBytes);
            chunks.add(new Chunk(value.length, index, chunkCount, digest,
                Arrays.copyOfRange(value, offset, offset + length)));
        }
        return List.copyOf(chunks);
    }

    public static List<byte[]> splitEncoded(byte[] canonical) {
        return splitEncoded(canonical, MAX_CHUNK_BYTES);
    }

    public static List<byte[]> splitEncoded(byte[] canonical, int maxChunkDataBytes) {
        return split(canonical, maxChunkDataBytes).stream().map(CatalogPublicationChunkPacket::encode).toList();
    }

    public static List<byte[]> encodeChunks(byte[] canonical) {
        return encodeChunks(canonical, MAX_CHUNK_BYTES);
    }

    public static List<byte[]> encodeChunks(byte[] canonical, int maxChunkDataBytes) {
        return splitEncoded(canonical, maxChunkDataBytes);
    }

    public static byte[] encode(Chunk chunk) {
        Objects.requireNonNull(chunk, "Catalog publication chunk is required");
        byte[] digest = chunk.digest();
        byte[] payload = chunk.payload();
        validateChunk(chunk.totalLength(), chunk.chunkIndex(), chunk.chunkCount(), payload.length, digest);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK);
        buffer.put(VERSION);
        buffer.putInt(chunk.totalLength());
        buffer.putInt(chunk.chunkIndex());
        buffer.putInt(chunk.chunkCount());
        buffer.putInt(payload.length);
        buffer.put(digest);
        buffer.put(payload);
        return buffer.array();
    }

    public static Chunk decode(byte[] payload) {
        Objects.requireNonNull(payload, "Catalog publication chunk payload is required");
        if (payload.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Catalog publication chunk payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        return decode(buffer.get(), buffer);
    }

    public static Chunk decode(ByteBuffer payload) {
        Objects.requireNonNull(payload, "Catalog publication chunk payload is required");
        if (!payload.hasRemaining()) {
            throw new IllegalArgumentException("Catalog publication chunk packet ID is missing");
        }
        return decode(payload.get(), payload);
    }

    public static Chunk decode(byte packetId, byte[] payload) {
        Objects.requireNonNull(payload, "Catalog publication chunk body is required");
        return decode(packetId, ByteBuffer.wrap(payload));
    }

    public static Chunk decode(byte packetId, ByteBuffer payload) {
        Objects.requireNonNull(payload, "Catalog publication chunk body is required");
        if (packetId != ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK) {
            throw new IllegalArgumentException("Unexpected catalog publication chunk packet ID");
        }
        if (payload.remaining() < 1 + Integer.BYTES * 4 + SHA256_BYTES) {
            throw new IllegalArgumentException("Catalog publication chunk header is incomplete");
        }
        byte version = payload.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported catalog publication chunk version");
        }
        int totalLength = payload.getInt();
        int chunkIndex = payload.getInt();
        int chunkCount = payload.getInt();
        int chunkLength = payload.getInt();
        byte[] digest = new byte[SHA256_BYTES];
        payload.get(digest);
        validateChunk(totalLength, chunkIndex, chunkCount, chunkLength, digest);
        if (payload.remaining() != chunkLength) {
            throw new IllegalArgumentException("Catalog publication chunk payload has trailing or missing bytes");
        }
        byte[] bytes = new byte[chunkLength];
        payload.get(bytes);
        return new Chunk(totalLength, chunkIndex, chunkCount, digest, bytes);
    }

    static byte[] sha256(byte[] value) {
        return CanonicalDigests.sha256(value);
    }

    public static final class Reassembler {
        private Long scope;
        private int totalLength;
        private int chunkCount;
        private byte[] digest;
        private byte[][] chunks;
        private int receivedBytes;
        private int nextChunkIndex;

        public synchronized Optional<byte[]> accept(long scope, Chunk chunk) {
            try {
                Objects.requireNonNull(chunk, "Catalog publication chunk is required");
                if (this.scope == null || this.scope.longValue() != scope) {
                    if (chunk.chunkIndex() != 0) {
                        throw new IllegalArgumentException("Catalog publication transfer must begin with chunk zero");
                    }
                    clear();
                    begin(scope, chunk);
                } else if (!sameTransfer(chunk)) {
                    if (chunk.chunkIndex() != 0) {
                        throw new IllegalArgumentException("Catalog publication transfer must begin with chunk zero");
                    }
                    clear();
                    begin(scope, chunk);
                }
                byte[] payload = chunk.payload();
                byte[] existing = chunks[chunk.chunkIndex()];
                if (existing != null) {
                    if (!Arrays.equals(existing, payload)) {
                        throw new IllegalArgumentException("Catalog publication chunk duplicate conflicts");
                    }
                    return Optional.empty();
                }
                if (chunk.chunkIndex() != nextChunkIndex) {
                    throw new IllegalArgumentException("Catalog publication chunks must arrive sequentially");
                }
                if (payload.length > totalLength - receivedBytes) {
                    throw new IllegalArgumentException("Catalog publication chunk bytes exceed declared total");
                }
                chunks[chunk.chunkIndex()] = payload;
                receivedBytes += payload.length;
                nextChunkIndex++;
                if (nextChunkIndex < chunkCount) {
                    if (receivedBytes == totalLength) {
                        throw new IllegalArgumentException("Catalog publication chunks end before declared count");
                    }
                    return Optional.empty();
                }
                if (receivedBytes != totalLength) {
                    throw new IllegalArgumentException("Catalog publication chunks are incomplete");
                }
                byte[] completed = new byte[totalLength];
                int offset = 0;
                for (byte[] current : chunks) {
                    System.arraycopy(current, 0, completed, offset, current.length);
                    offset += current.length;
                }
                if (!CanonicalDigests.equal(digest, sha256(completed))) {
                    throw new IllegalArgumentException("Catalog publication digest does not match");
                }
                clear();
                return Optional.of(completed);
            } catch (RuntimeException exception) {
                clear();
                throw exception;
            }
        }

        public synchronized Optional<byte[]> accept(long scope, byte[] encodedPayload) {
            try {
                return accept(scope, decode(encodedPayload));
            } catch (RuntimeException exception) {
                clear();
                throw exception;
            }
        }

        public synchronized Optional<byte[]> accept(long scope, ByteBuffer encodedPayload) {
            try {
                return accept(scope, decode(encodedPayload));
            } catch (RuntimeException exception) {
                clear();
                throw exception;
            }
        }

        public synchronized void clear() {
            scope = null;
            totalLength = 0;
            chunkCount = 0;
            digest = null;
            chunks = null;
            receivedBytes = 0;
            nextChunkIndex = 0;
        }

        public synchronized boolean hasActiveTransfer() {
            return scope != null;
        }

        private void begin(long scope, Chunk chunk) {
            this.scope = scope;
            totalLength = chunk.totalLength();
            chunkCount = chunk.chunkCount();
            digest = chunk.digest();
            chunks = new byte[chunkCount][];
            receivedBytes = 0;
            nextChunkIndex = 0;
        }

        private boolean sameTransfer(Chunk chunk) {
            return totalLength == chunk.totalLength()
                && chunkCount == chunk.chunkCount()
                && Arrays.equals(digest, chunk.digest());
        }
    }

    private static byte[] requirePublication(byte[] canonical) {
        Objects.requireNonNull(canonical, "Canonical catalog publication is required");
        if (canonical.length == 0 || canonical.length > MAX_PUBLICATION_BYTES) {
            throw new IllegalArgumentException("Canonical catalog publication size is invalid");
        }
        return canonical;
    }

    private static int chunkCount(int totalLength, int maxChunkDataBytes) {
        int count = (int) (((long) totalLength + maxChunkDataBytes - 1L) / maxChunkDataBytes);
        if (count < 1 || count > MAX_CHUNKS) {
            throw new IllegalArgumentException("Catalog publication chunk count is invalid");
        }
        return count;
    }

    private static int expectedChunkLength(int totalLength, int chunkIndex, int maxChunkDataBytes) {
        long offset = (long) chunkIndex * maxChunkDataBytes;
        return (int) Math.min(maxChunkDataBytes, totalLength - offset);
    }

    private static void validateChunk(int totalLength, int chunkIndex, int chunkCount, int chunkLength,
                                      byte[] digest) {
        if (totalLength < 1 || totalLength > MAX_PUBLICATION_BYTES) {
            throw new IllegalArgumentException("Catalog publication total length is invalid");
        }
        int minimumChunkCount = (int) (((long) totalLength + MAX_CHUNK_BYTES - 1L) / MAX_CHUNK_BYTES);
        if (chunkCount < minimumChunkCount || chunkCount > MAX_CHUNKS
            || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Catalog publication chunk position is invalid");
        }
        if (chunkLength < 1 || chunkLength > MAX_CHUNK_BYTES || chunkLength > totalLength) {
            throw new IllegalArgumentException("Catalog publication chunk length is invalid");
        }
        if (digest == null || digest.length != SHA256_BYTES) {
            throw new IllegalArgumentException("Catalog publication digest is invalid");
        }
    }

    private static int requireChunkDataBytes(int maxChunkDataBytes) {
        if (maxChunkDataBytes < 1 || maxChunkDataBytes > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Catalog publication chunk data size is invalid");
        }
        return maxChunkDataBytes;
    }

    public record Chunk(int totalLength, int chunkIndex, int chunkCount, byte[] digest, byte[] payload) {
        public Chunk {
            digest = digest == null ? null : digest.clone();
            payload = payload == null ? null : payload.clone();
            if (payload == null) {
                throw new IllegalArgumentException("Catalog publication chunk bytes are required");
            }
            validateChunk(totalLength, chunkIndex, chunkCount, payload.length, digest);
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public byte[] bytes() {
            return payload();
        }

        public byte[] sha256() {
            return digest();
        }

        public boolean last() {
            return chunkIndex == chunkCount - 1;
        }

        public byte[] encoded() {
            return encode(this);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Chunk other
                && totalLength == other.totalLength
                && chunkIndex == other.chunkIndex
                && chunkCount == other.chunkCount
                && Arrays.equals(digest, other.digest)
                && Arrays.equals(payload, other.payload);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(totalLength, chunkIndex, chunkCount);
            result = 31 * result + Arrays.hashCode(digest);
            return 31 * result + Arrays.hashCode(payload);
        }
    }
}
