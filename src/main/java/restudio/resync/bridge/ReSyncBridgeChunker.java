package restudio.resync.bridge;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ReSyncBridgeChunker {
    public interface PacketSink {
        void send(ReSyncBridgeEnvelope envelope);
    }

    public static final int CHUNK_SIZE = 24_000;
    private static final int MAX_REASSEMBLED_BYTES = 4_194_304;
    private static final int MAX_PENDING_MESSAGES = 32;
    private static final long MAX_PENDING_BYTES = 8L * 1024L * 1024L;
    private static final long TIMEOUT_MS = 10_000;
    private final Map<PendingKey, PendingChunks> pending = new HashMap<>();
    private long pendingBytes;

    public void send(UUID sessionId, int sequence, byte type, byte[] payload, PacketSink sink) {
        byte[] data = payload == null ? new byte[0] : payload;
        if (data.length > MAX_REASSEMBLED_BYTES) {
            throw new IllegalArgumentException("Bridge payload too large");
        }
        int count = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_SIZE;
            int end = Math.min(data.length, start + CHUNK_SIZE);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);
            sink.send(new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, type, sessionId, sequence, index, count, chunk));
        }
    }

    public synchronized byte[] accept(ReSyncBridgeEnvelope envelope) {
        cleanup();
        if (envelope.chunkCount() <= 0 || envelope.chunkIndex() < 0 || envelope.chunkIndex() >= envelope.chunkCount()) {
            throw new IllegalArgumentException("Invalid bridge chunk");
        }
        if (envelope.chunkCount() > (MAX_REASSEMBLED_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE || envelope.payload().length > CHUNK_SIZE) {
            throw new IllegalArgumentException("Bridge payload too large");
        }
        if (envelope.chunkCount() == 1) {
            if (envelope.payload().length > MAX_REASSEMBLED_BYTES) {
                throw new IllegalArgumentException("Bridge payload too large");
            }
            return envelope.payload();
        }
        PendingKey key = new PendingKey(envelope.sessionId(), envelope.sequence(), envelope.type());
        PendingChunks chunks = pending.get(key);
        if (chunks == null) {
            if (pending.size() >= MAX_PENDING_MESSAGES) {
                throw new IllegalStateException("Bridge chunk assembly limit reached");
            }
            chunks = new PendingChunks(envelope.chunkCount());
            pending.put(key, chunks);
        }
        if (chunks.count() != envelope.chunkCount()) {
            remove(key, chunks);
            throw new IllegalArgumentException("Invalid bridge chunk sequence");
        }
        int previousBytes = chunks.bytesAt(envelope.chunkIndex());
        long projectedBytes = pendingBytes - previousBytes + envelope.payload().length;
        if (projectedBytes > MAX_PENDING_BYTES) {
            remove(key, chunks);
            throw new IllegalStateException("Bridge chunk assembly byte limit reached");
        }
        pendingBytes = projectedBytes;
        chunks.put(envelope.chunkIndex(), envelope.payload());
        if (!chunks.complete()) {
            return null;
        }
        try {
            return chunks.join();
        } finally {
            remove(key, chunks);
        }
    }

    public synchronized void clear() {
        pending.clear();
        pendingBytes = 0L;
    }

    synchronized int pendingMessageCount() {
        return pending.size();
    }

    synchronized long pendingByteCount() {
        return pendingBytes;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<PendingKey, PendingChunks>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingChunks chunks = iterator.next().getValue();
            if (now - chunks.createdAt > TIMEOUT_MS) {
                pendingBytes -= chunks.bytes();
                iterator.remove();
            }
        }
    }

    private void remove(PendingKey key, PendingChunks chunks) {
        if (pending.remove(key, chunks)) {
            pendingBytes -= chunks.bytes();
        }
        if (pendingBytes < 0L) {
            throw new IllegalStateException("Bridge chunk accounting is inconsistent");
        }
    }

    private static class PendingChunks {
        private final byte[][] chunks;
        private final long createdAt = System.currentTimeMillis();

        private PendingChunks(int count) {
            chunks = new byte[count][];
        }

        private void put(int index, byte[] payload) {
            chunks[index] = payload == null ? new byte[0] : payload;
        }

        private int bytesAt(int index) {
            byte[] value = chunks[index];
            return value == null ? 0 : value.length;
        }

        private long bytes() {
            long total = 0L;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    total += chunk.length;
                }
            }
            return total;
        }

        private boolean complete() {
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    return false;
                }
            }
            return true;
        }

        private int count() {
            return chunks.length;
        }

        private byte[] join() {
            int total = 0;
            for (byte[] chunk : chunks) {
                total += chunk.length;
                if (total > MAX_REASSEMBLED_BYTES) {
                    throw new IllegalArgumentException("Bridge payload too large");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            for (byte[] chunk : chunks) {
                out.writeBytes(chunk);
            }
            return out.toByteArray();
        }
    }

    private record PendingKey(UUID sessionId, int sequence, byte type) {
    }
}
