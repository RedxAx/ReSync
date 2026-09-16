package restudio.resync.bridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncBridgeChunkerTest {
    @Test
    void rejectsUnboundedConcurrentAssemblies() {
        ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        UUID sessionId = UUID.randomUUID();
        for (int sequence = 0; sequence < 32; sequence++) {
            chunker.accept(envelope(sessionId, sequence, 0, 2, new byte[]{1}));
        }

        assertEquals(32, chunker.pendingMessageCount());
        assertThrows(IllegalStateException.class,
            () -> chunker.accept(envelope(sessionId, 33, 0, 2, new byte[]{1})));
    }

    @Test
    void rejectsAggregateAssemblyBytesBeyondTheSessionLimit() {
        ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        UUID sessionId = UUID.randomUUID();
        byte[] full = new byte[ReSyncBridgeChunker.CHUNK_SIZE];
        int chunks = 175;
        for (int sequence = 1; sequence <= 2; sequence++) {
            for (int index = 0; index < chunks - 1; index++) {
                chunker.accept(envelope(sessionId, sequence, index, chunks, full));
            }
        }

        chunker.accept(envelope(sessionId, 3, 0, chunks, full));
        assertThrows(IllegalStateException.class,
            () -> chunker.accept(envelope(sessionId, 3, 1, chunks, full)));
        assertEquals(2, chunker.pendingMessageCount());
    }

    @Test
    void duplicateChunksReplaceBytesWithoutInflatingAccounting() {
        ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        UUID sessionId = UUID.randomUUID();
        chunker.accept(envelope(sessionId, 1, 0, 2, new byte[]{1, 2, 3}));
        chunker.accept(envelope(sessionId, 1, 0, 2, new byte[]{4}));

        assertEquals(1L, chunker.pendingByteCount());
        byte[] result = chunker.accept(envelope(sessionId, 1, 1, 2, new byte[]{5}));
        assertArrayEquals(new byte[]{4, 5}, result);
        assertEquals(0L, chunker.pendingByteCount());
    }

    @Test
    void oversizedCompletedAssemblyIsRejectedAndReleased() {
        ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        UUID sessionId = UUID.randomUUID();
        byte[] full = new byte[ReSyncBridgeChunker.CHUNK_SIZE];
        int chunks = 175;
        for (int index = 0; index < chunks - 1; index++) {
            chunker.accept(envelope(sessionId, 1, index, chunks, full));
        }

        assertThrows(IllegalArgumentException.class,
            () -> chunker.accept(envelope(sessionId, 1, chunks - 1, chunks, full)));
        assertEquals(0, chunker.pendingMessageCount());
        assertEquals(0L, chunker.pendingByteCount());
    }

    private static ReSyncBridgeEnvelope envelope(UUID sessionId, int sequence, int index, int count, byte[] payload) {
        return new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, ReSyncBridgeEnvelope.DATA,
            sessionId, sequence, index, count, payload);
    }
}
