package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphStorageBoundaryTombstoneTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final ContentHash PRIOR_HASH = new ContentHash("a".repeat(64));

    @Test
    void roundTripsTypedTombstoneWithTheCompleteLocator() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ServerResourceLocator resource = resourceWithUnknownData();

        byte[] encoded = boundary.encodeTombstone(resource, 8L, MUTATION, PRIOR_HASH);
        CoreGraphStorageBoundary.CoreGraphTombstone decoded = boundary.decodeTombstone(encoded, resource);
        JsonValue.JsonObject object = assertObject(encoded);

        assertEquals(resource, decoded.resource());
        assertEquals(8L, decoded.revision());
        assertEquals(MUTATION, decoded.mutationId());
        assertEquals(PRIOR_HASH, decoded.priorPayloadHash());
        assertTrue(decoded.deleted());
        assertEquals(CoreGraphStorageBoundary.CURRENT_TOMBSTONE_FORMAT_VERSION,
            decoded.tombstoneFormatVersion());
        assertEquals(boundary.tombstoneIntegrityHash(object), decoded.integrityHash());
        assertFalse(object.contains(CoreGraphStorageBoundary.ASSET_ACTIVATION_STATE));
        assertArrayEquals(encoded, boundary.encodeTombstone(decoded));
    }

    @Test
    void rejectsTamperedStateAndNonCanonicalBytes() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] encoded = boundary.encodeTombstone(resource(), 3L, MUTATION, PRIOR_HASH);
        JsonValue.JsonObject object = assertObject(encoded);

        Map<String, JsonValue> changedHash = new LinkedHashMap<>(object.fields());
        changedHash.put(CoreGraphStorageBoundary.TOMBSTONE_PAYLOAD_HASH, JsonValue.of("b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> boundary.decodeTombstone(JsonValue.object(changedHash)));

        Map<String, JsonValue> live = new LinkedHashMap<>(object.fields());
        live.put(CoreGraphStorageBoundary.TOMBSTONE_DELETED, JsonValue.of(false));
        assertThrows(IllegalArgumentException.class, () -> boundary.decodeTombstone(JsonValue.object(live)));

        String canonical = new String(encoded, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> boundary.decodeTombstone((" " + canonical).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWrongLocatorVersionAndUnknownEnvelopeFields() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] encoded = boundary.encodeTombstone(resource(), 3L, MUTATION, PRIOR_HASH);
        JsonValue.JsonObject object = assertObject(encoded);

        ServerResourceLocator wrongServer = new ServerResourceLocator(
            new ServerId(UUID.fromString("33333333-3333-4333-8333-333333333333")), resource().key());
        assertThrows(IllegalArgumentException.class, () -> boundary.decodeTombstone(encoded, wrongServer));

        Map<String, JsonValue> wrongVersion = new LinkedHashMap<>(object.fields());
        wrongVersion.put(CoreGraphStorageBoundary.TOMBSTONE_FORMAT_VERSION, JsonValue.of(2));
        assertThrows(IllegalArgumentException.class, () -> boundary.decodeTombstone(JsonValue.object(wrongVersion)));

        Map<String, JsonValue> activation = new LinkedHashMap<>(object.fields());
        activation.put(CoreGraphStorageBoundary.ASSET_ACTIVATION_STATE, JsonValue.of("ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> boundary.decodeTombstone(JsonValue.object(activation)));
    }

    private static ServerResourceLocator resource() {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "deleted-flow");
    }

    private static ServerResourceLocator resourceWithUnknownData() {
        ContractRef<ResourceTypeId> type = ContractRef.of(new OwnerId("restudio.resync"),
            new ResourceTypeId("flow"), Map.of("typeFuture", "keep"));
        ResourceKey key = new ResourceKey(type, "deleted-flow", Map.of("keyFuture", true));
        return new ServerResourceLocator(SERVER, key, Map.of("locatorFuture", 7));
    }

    private static JsonValue.JsonObject assertObject(byte[] bytes) {
        return CanonicalCodec.requireObject(CanonicalCodec.decode(bytes));
    }
}
