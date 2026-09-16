package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogPublicationReceiptPacketTest {
    private static final CatalogCacheKey KEY = new CatalogCacheKey(
        new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")), 8,
        new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)), CatalogProjectionVersion.current());

    @Test
    void roundTripsEachReceiptStage() {
        byte[] received = CatalogPublicationReceiptPacket.encodeClientReceived(KEY, 13);
        byte[] applied = CatalogPublicationReceiptPacket.encodeCacheApplied(KEY, 13);
        byte[] rejected = CatalogPublicationReceiptPacket.encodeCacheRejected(KEY, 13, "CACHE_WRITE_FAILED");

        CatalogPublicationReceiptPacket.Packet receivedPacket = CatalogPublicationReceiptPacket.decode(received);
        CatalogPublicationReceiptPacket.Packet receivedBodyPacket = CatalogPublicationReceiptPacket.decode(received[0],
            ByteBuffer.wrap(Arrays.copyOfRange(received, 1, received.length)));
        CatalogPublicationReceiptPacket.Packet appliedPacket = CatalogPublicationReceiptPacket.decode(applied);
        CatalogPublicationReceiptPacket.Packet rejectedPacket = CatalogPublicationReceiptPacket.decode(rejected);

        assertEquals(CatalogPublicationReceiptPacket.Kind.CLIENT_RECEIVED, receivedPacket.kind());
        assertEquals(receivedPacket, receivedBodyPacket);
        assertEquals(CatalogPublicationReceiptPacket.Kind.CACHE_APPLIED, appliedPacket.kind());
        assertEquals(CatalogPublicationReceiptPacket.Kind.CACHE_REJECTED, rejectedPacket.kind());
        assertEquals(KEY, rejectedPacket.key());
        assertEquals(13, rejectedPacket.revision());
        assertEquals("CACHE_WRITE_FAILED", rejectedPacket.diagnosticCode());
        assertArrayEquals(received, CatalogPublicationReceiptPacket.encodeClientReceived(receivedPacket.key(), receivedPacket.revision()));
        assertArrayEquals(applied, CatalogPublicationReceiptPacket.encodeCacheApplied(appliedPacket.key(), appliedPacket.revision()));
        assertArrayEquals(rejected, CatalogPublicationReceiptPacket.encodeCacheRejected(rejectedPacket.key(), rejectedPacket.revision(),
            rejectedPacket.diagnosticCode()));
    }

    @Test
    void rejectsMalformedAndTrailingPayloads() {
        byte[] valid = CatalogPublicationReceiptPacket.encodeClientReceived(KEY, 13);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationReceiptPacket.decode(trailing));
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationReceiptPacket.decode(new byte[] {0x01}));
    }
}
