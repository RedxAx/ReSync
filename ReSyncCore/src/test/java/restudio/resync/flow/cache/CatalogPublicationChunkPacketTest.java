package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationChunkPacketTest {
    @Test
    void splitsAndRoundTripsFullAndBodyWireForms() {
        byte[] publication = new byte[CatalogPublicationChunkPacket.MAX_CHUNK_BYTES + 17];
        for (int index = 0; index < publication.length; index++) {
            publication[index] = (byte) index;
        }

        List<byte[]> encoded = CatalogPublicationChunkPacket.encodeChunks(publication);
        List<CatalogPublicationChunkPacket.Chunk> chunks = CatalogPublicationChunkPacket.split(publication);

        assertEquals(2, encoded.size());
        assertEquals(2, chunks.size());
        for (int index = 0; index < encoded.size(); index++) {
            CatalogPublicationChunkPacket.Chunk decoded = CatalogPublicationChunkPacket.decode(encoded.get(index));
            byte[] body = Arrays.copyOfRange(encoded.get(index), 1, encoded.get(index).length);
            CatalogPublicationChunkPacket.Chunk bodyDecoded = CatalogPublicationChunkPacket.decode(encoded.get(index)[0],
                ByteBuffer.wrap(body));
            assertEquals(chunks.get(index), decoded);
            assertEquals(decoded, bodyDecoded);
            assertArrayEquals(encoded.get(index), decoded.encoded());
        }
        assertEquals(publication.length, chunks.getFirst().totalLength());
        assertEquals(CatalogPublicationChunkPacket.MAX_CHUNK_BYTES, chunks.getFirst().payload().length);
        assertEquals(17, chunks.getLast().payload().length);
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK, encoded.getFirst()[0]);
        assertEquals(CatalogPublicationChunkPacket.VERSION, encoded.getFirst()[1]);
    }

    @Test
    void reassemblesSequentialDuplicatesAndOwnsCompletion() {
        byte[] publication = new byte[CatalogPublicationChunkPacket.MAX_CHUNK_BYTES * 2 + 3];
        Arrays.fill(publication, (byte) 7);
        List<CatalogPublicationChunkPacket.Chunk> chunks = CatalogPublicationChunkPacket.split(publication);
        CatalogPublicationChunkPacket.Reassembler reassembler = new CatalogPublicationChunkPacket.Reassembler();

        assertEquals(Optional.empty(), reassembler.accept(12, chunks.get(0)));
        assertEquals(Optional.empty(), reassembler.accept(12, chunks.get(0)));
        assertEquals(Optional.empty(), reassembler.accept(12, chunks.get(1)));
        assertEquals(Optional.empty(), reassembler.accept(12, chunks.get(1)));
        Optional<byte[]> result = reassembler.accept(12, chunks.get(2));
        assertTrue(result.isPresent());
        assertArrayEquals(publication, result.orElseThrow());
        assertFalse(reassembler.hasActiveTransfer());
        byte[] completed = result.orElseThrow();
        completed[0] = 99;
        assertEquals(7, publication[0]);
    }

    @Test
    void dynamicChunkBudgetRoundTripsWithSequentialReassembly() {
        byte[] publication = new byte[4097];
        for (int index = 0; index < publication.length; index++) {
            publication[index] = (byte) (index * 31);
        }
        int maxChunkDataBytes = 1000;

        List<byte[]> encoded = CatalogPublicationChunkPacket.encodeChunks(publication, maxChunkDataBytes);
        assertEquals(5, encoded.size());
        CatalogPublicationChunkPacket.Reassembler reassembler = new CatalogPublicationChunkPacket.Reassembler();
        Optional<byte[]> result = Optional.empty();
        for (int index = 0; index < encoded.size(); index++) {
            CatalogPublicationChunkPacket.Chunk chunk = CatalogPublicationChunkPacket.decode(encoded.get(index));
            assertTrue(chunk.payload().length <= maxChunkDataBytes);
            result = reassembler.accept(44, chunk);
        }

        assertArrayEquals(publication, result.orElseThrow());
    }

    @Test
    void browserAssemblyDefersDigestVerificationWithoutWeakeningTheDefaultPath() {
        byte[] publication = new byte[4097];
        Arrays.fill(publication, (byte) 7);
        List<CatalogPublicationChunkPacket.Chunk> chunks = CatalogPublicationChunkPacket.split(publication, 1000);
        CatalogPublicationChunkPacket.Chunk last = chunks.getLast();
        byte[] changed = last.payload();
        changed[0] = 8;
        CatalogPublicationChunkPacket.Chunk tampered = new CatalogPublicationChunkPacket.Chunk(last.totalLength(),
            last.chunkIndex(), last.chunkCount(), last.digest(), changed);
        CatalogPublicationChunkPacket.Reassembler strict = new CatalogPublicationChunkPacket.Reassembler();
        CatalogPublicationChunkPacket.Reassembler deferred = new CatalogPublicationChunkPacket.Reassembler();
        for (int index = 0; index < chunks.size() - 1; index++) {
            assertEquals(Optional.empty(), strict.accept(1, chunks.get(index)));
            assertEquals(Optional.empty(), deferred.acceptUnverified(1, chunks.get(index)));
        }
        assertThrows(IllegalArgumentException.class, () -> strict.accept(1, tampered));
        byte[] assembled = deferred.acceptUnverified(1, tampered).orElseThrow();
        assertFalse(Arrays.equals(last.digest(), CatalogPublicationChunkPacket.sha256(assembled)));
        assertFalse(deferred.hasActiveTransfer());
    }

    @Test
    void rejectsNonZeroStartAndOutOfOrderChunk() {
        byte[] publication = new byte[CatalogPublicationChunkPacket.MAX_CHUNK_BYTES * 2 + 1];
        List<CatalogPublicationChunkPacket.Chunk> chunks = CatalogPublicationChunkPacket.split(publication);
        CatalogPublicationChunkPacket.Reassembler reassembler = new CatalogPublicationChunkPacket.Reassembler();

        assertThrows(IllegalArgumentException.class, () -> reassembler.accept(21, chunks.get(1)));
        assertFalse(reassembler.hasActiveTransfer());
        assertEquals(Optional.empty(), reassembler.accept(21, chunks.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> reassembler.accept(21, chunks.getLast()));
        assertFalse(reassembler.hasActiveTransfer());
    }

    @Test
    void configuredBridgeFrameBudgetCanCarryTheMaximumPublication() {
        byte[] publication = new byte[CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES];
        int maxEncodedFrameBytes = 24_000;
        int maxChunkDataBytes = maxEncodedFrameBytes - 12 - CatalogPublicationChunkPacket.HEADER_BYTES;

        List<CatalogPublicationChunkPacket.Chunk> chunks = CatalogPublicationChunkPacket.split(
            publication, maxChunkDataBytes);

        assertTrue(CatalogPublicationChunkPacket.MAX_CHUNKS >= chunks.size());
        assertEquals(1_402, chunks.size());
        assertEquals(maxChunkDataBytes, chunks.getFirst().payload().length);
        assertEquals(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES - (long) maxChunkDataBytes * 1_401,
            chunks.getLast().payload().length);
        assertTrue(chunks.stream().allMatch(chunk -> CatalogPublicationChunkPacket.HEADER_BYTES + chunk.payload().length
            <= maxEncodedFrameBytes));
    }

    @Test
    void rejectsMalformedWireAndConflictingTransfers() {
        byte[] publication = new byte[CatalogPublicationChunkPacket.MAX_CHUNK_BYTES + 4];
        publication[0] = 1;
        publication[1] = 2;
        publication[2] = 3;
        publication[3] = 4;
        byte[] valid = CatalogPublicationChunkPacket.encodeChunks(publication).getFirst();

        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        trailing[trailing.length - 1] = 1;
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationChunkPacket.decode(trailing));

        byte[] wrongId = valid.clone();
        wrongId[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationChunkPacket.decode(wrongId));

        byte[] wrongVersion = valid.clone();
        wrongVersion[1] = 2;
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationChunkPacket.decode(wrongVersion));

        CatalogPublicationChunkPacket.Reassembler reassembler = new CatalogPublicationChunkPacket.Reassembler();
        CatalogPublicationChunkPacket.Chunk first = CatalogPublicationChunkPacket.decode(valid);
        assertEquals(Optional.empty(), reassembler.accept(1, first));
        byte[] conflicting = first.payload();
        conflicting[0] = 9;
        CatalogPublicationChunkPacket.Chunk conflictingChunk = new CatalogPublicationChunkPacket.Chunk(
            first.totalLength(), first.chunkIndex(), first.chunkCount(), first.digest(), conflicting);
        assertThrows(IllegalArgumentException.class, () -> reassembler.accept(1, conflictingChunk));
        assertFalse(reassembler.hasActiveTransfer());

        byte[] digest = CatalogPublicationChunkPacket.sha256(new byte[10]);
        CatalogPublicationChunkPacket.Chunk sixBytesFirst = new CatalogPublicationChunkPacket.Chunk(
            10, 0, 2, digest, new byte[6]);
        CatalogPublicationChunkPacket.Chunk sixBytesSecond = new CatalogPublicationChunkPacket.Chunk(
            10, 1, 2, digest, new byte[6]);
        assertEquals(Optional.empty(), reassembler.accept(2, sixBytesFirst));
        assertThrows(IllegalArgumentException.class, () -> reassembler.accept(2, sixBytesSecond));
        assertFalse(reassembler.hasActiveTransfer());
    }
}
