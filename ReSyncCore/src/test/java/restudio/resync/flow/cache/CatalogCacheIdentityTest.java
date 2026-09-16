package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCacheIdentityTest {
    private static final ServerId SERVER_A = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerId SERVER_B = new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final ContentHash CHECKSUM_A = new ContentHash("a".repeat(64));
    private static final ContentHash CHECKSUM_B = new ContentHash("b".repeat(64));
    private static final OwnerId OWNER = new OwnerId("example.reference");
    private static final ContractRef<NodeId> DEFINITION_KEY = new ContractRef<>(OWNER, new NodeId("task"));
    private static final ContractRef<CapabilityId> CAPABILITY_KEY = new ContractRef<>(OWNER, new CapabilityId("task-handler"));
    private static final byte[] OPAQUE_BYTES = "{\"future\":{\"value\":7},\"node\":\"task\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void cacheKeyIdentityIncludesServerAndSnapshotChecksum() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER_A, CHECKSUM_A);

        assertEquals(key, CatalogCacheKey.parseCanonicalText(key.canonicalText()));
        assertEquals(SERVER_A, key.serverId());
        assertEquals(CHECKSUM_A, key.snapshotChecksum());
        assertNotEquals(key, new CatalogCacheKey(SERVER_B, CHECKSUM_A));
        assertNotEquals(key, new CatalogCacheKey(SERVER_A, CHECKSUM_B));
        assertEquals(3, new HashSet<>(Set.of(
            key,
            new CatalogCacheKey(SERVER_B, CHECKSUM_A),
            new CatalogCacheKey(SERVER_A, CHECKSUM_B)
        )).size());
    }

    @Test
    void cacheKeyRejectsInvalidSnapshotChecksums() {
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("g".repeat(64)));
    }

    @Test
    void tombstoneRejectsDefinitionPayload() {
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(
            DEFINITION_KEY,
            CatalogCacheOpaque.of(OPAQUE_BYTES),
            Set.of(CAPABILITY_KEY)
        );

        CatalogCacheEntry tombstone = CatalogCacheEntry.tombstone(DEFINITION_KEY, 4);

        assertTrue(tombstone.tombstone());
        assertFalse(tombstone.present());
        assertNull(tombstone.definition());
        assertThrows(IllegalArgumentException.class,
            () -> new CatalogCacheEntry(DEFINITION_KEY, 4, definition, true));
    }

    @Test
    void cacheValuesDefensivelyCopyCollectionsAndOpaqueBytes() {
        Set<ContractRef<CapabilityId>> requiredCapabilities = new HashSet<>(Set.of(CAPABILITY_KEY));
        CatalogCacheOpaque opaque = CatalogCacheOpaque.of(OPAQUE_BYTES);
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(
            DEFINITION_KEY,
            opaque,
            requiredCapabilities
        );
        requiredCapabilities.clear();
        byte[] copiedBytes = opaque.canonicalBytes();
        copiedBytes[0] = 'x';

        CatalogCacheEntry entry = CatalogCacheEntry.present(DEFINITION_KEY, 1, definition);
        List<CatalogCacheEntry> sourceEntries = new ArrayList<>(List.of(entry));
        CatalogCacheSnapshot snapshot = new CatalogCacheSnapshot(
            new CatalogCacheKey(SERVER_A, CHECKSUM_A),
            2,
            sourceEntries
        );
        sourceEntries.clear();

        assertEquals(Set.of(CAPABILITY_KEY), definition.requiredCapabilities());
        assertArrayEquals(OPAQUE_BYTES, definition.opaque().canonicalBytes());
        assertEquals(1, snapshot.entries().size());
        assertEquals(entry, snapshot.entry(DEFINITION_KEY).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> definition.requiredCapabilities().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
    }
}
