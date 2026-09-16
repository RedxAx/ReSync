package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceAdapterMutationIdentityTest {
    private static final UUID MUTATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String HASH = "a".repeat(64);

    @Test
    void legacyAdaptersFailClosedWithoutInvokingLegacyMutationMethods() {
        CountingAdapter adapter = new CountingAdapter();

        assertFalse(adapter.supportsAuthoritativeMutationIdentity());
        IllegalStateException saveFailure = assertThrows(IllegalStateException.class,
            () -> adapter.save("value", MUTATION_ID, 1L));
        IllegalStateException deleteFailure = assertThrows(IllegalStateException.class,
            () -> adapter.delete("id", MUTATION_ID, 1L));
        IllegalStateException readFailure = assertThrows(IllegalStateException.class,
            () -> adapter.readMutationStamp("id"));

        assertEquals(FlowResourceAdapter.AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE, saveFailure.getMessage());
        assertEquals(saveFailure.getMessage(), deleteFailure.getMessage());
        assertEquals(saveFailure.getMessage(), readFailure.getMessage());
        assertEquals(0, adapter.legacySaves);
        assertEquals(0, adapter.legacyDeletes);
    }

    @Test
    void exactAdapterReceivesCallerIdentityAndReadsStrictStamp() {
        ExactAdapter adapter = new ExactAdapter();

        adapter.save("value", MUTATION_ID, 7L);
        adapter.delete("id", MUTATION_ID, 8L);

        assertEquals(MUTATION_ID, adapter.lastMutationId);
        assertEquals(8L, adapter.lastExpectedRevision);
        FlowResourceMutationStamp stamp = adapter.readMutationStamp("id");
        assertNotNull(stamp);
        assertEquals("flow", stamp.type());
        assertEquals("id", stamp.id());
        assertEquals(8L, stamp.revision());
        assertEquals(MUTATION_ID, stamp.mutationId());
        assertEquals(HASH, stamp.payloadHash());
        assertTrue(stamp.deleted());
    }

    @Test
    void mutationStampRejectsMalformedIdentityAndHash() {
        assertThrows(NullPointerException.class, () -> new FlowResourceMutationStamp(null, "id", 1L, MUTATION_ID, HASH, false));
        assertThrows(IllegalArgumentException.class, () -> new FlowResourceMutationStamp("flow", " ", 1L, MUTATION_ID, HASH, false));
        assertThrows(IllegalArgumentException.class, () -> new FlowResourceMutationStamp("flow", "id", 0L, MUTATION_ID, HASH, false));
        assertThrows(NullPointerException.class, () -> new FlowResourceMutationStamp("flow", "id", 1L, null, HASH, false));
        assertThrows(IllegalArgumentException.class, () -> new FlowResourceMutationStamp("flow", "id", 1L, MUTATION_ID, "A".repeat(64), false));
        assertThrows(IllegalArgumentException.class, () -> new FlowResourceMutationStamp("flow", "id", 1L, MUTATION_ID, "not-a-hash", false));
    }

    private static class CountingAdapter implements FlowResourceAdapter<String> {
        private int legacySaves;
        private int legacyDeletes;

        @Override
        public ReSyncManagedResource descriptor() {
            return null;
        }

        @Override
        public String get(String id) {
            return null;
        }

        @Override
        public List<String> listIds() {
            return List.of();
        }

        @Override
        public String deserialize(String json) {
            return json;
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
            legacySaves++;
        }

        @Override
        public void delete(String id) {
            legacyDeletes++;
        }
    }

    private static final class ExactAdapter extends CountingAdapter {
        private UUID lastMutationId;
        private long lastExpectedRevision;
        private FlowResourceMutationStamp stamp;

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public void save(String value, UUID mutationId, long expectedRevision) {
            lastMutationId = mutationId;
            lastExpectedRevision = expectedRevision;
            stamp = new FlowResourceMutationStamp("flow", "id", expectedRevision + 1L, mutationId, HASH, false);
        }

        @Override
        public void delete(String id, UUID mutationId, long expectedRevision) {
            lastMutationId = mutationId;
            lastExpectedRevision = expectedRevision;
            stamp = new FlowResourceMutationStamp("flow", id, expectedRevision, mutationId, HASH, true);
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            return stamp;
        }
    }
}
