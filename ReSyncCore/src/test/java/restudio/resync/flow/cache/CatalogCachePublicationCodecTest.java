package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogCachePublicationCodecTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @Test
    void publicationAboveStandardTokenBudgetRoundTripsWithCatalogLimits() {
        CatalogCachePublication publication = largePublication();
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        byte[] encoded = codec.encodeBytes(publication);

        assertEquals(publication, codec.decodeBytes(encoded));
    }

    @Test
    void validatedPublicationRetainsCanonicalInputAfterCallerMutation() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication publication = smallPublication();
        byte[] input = codec.encodeBytes(publication);
        byte[] expected = input.clone();

        CatalogCachePublicationCodec.ValidatedPublication validated = codec.decodeValidatedPublication(input);
        input[0] = '[';
        byte[] exposed = validated.canonicalBytes();
        exposed[0] = '[';

        assertEquals(publication, validated.publication());
        assertArrayEquals(expected, validated.canonicalBytes());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeValidatedPublication(input));
    }

    @Test
    void validatedPublicationRetainsExactAuthoringSubtreeBytes() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        ContentHash checksum = new ContentHash("0".repeat(64));
        CatalogBinding binding = new CatalogBinding(1, checksum, new ContentHash("1".repeat(64)));
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 1, checksum, CatalogProjectionVersion.current(),
            binding.bindingManifestHash());
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            binding, 1, List.of(), authoring, Map.of());
        byte[] input = codec.encodeBytes(publication);
        byte[] expected = new CatalogAuthoringPublicationCodec().encodeBytes(authoring);

        CatalogCachePublicationCodec.ValidatedPublication validated = codec.decodeValidatedPublication(input);
        byte[] exposed = validated.authoringCanonicalBytes();
        exposed[0] = '[';
        input[0] = '[';

        assertEquals(publication, validated.publication());
        assertArrayEquals(expected, validated.authoringCanonicalBytes());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeValidatedPublication(input));
    }

    @Test
    void decodeRejectsNormalizedEntryStateAlias() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        JsonValue.JsonObject encoded = codec.encode(smallPublication());
        JsonValue.JsonObject entry = firstEntry(encoded);
        Map<String, JsonValue> changedEntry = new LinkedHashMap<>(entry.fields());
        changedEntry.put("state", JsonValue.of("ACTIVE"));
        JsonValue.JsonObject changed = replaceEntries(encoded, List.of(JsonValue.object(changedEntry)));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(changed));
    }

    @Test
    void decodeRejectsUnsortedCapabilitiesAndEntries() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        JsonValue.JsonObject encoded = codec.encode(smallPublication());
        JsonValue.JsonObject entry = firstEntry(encoded);
        JsonValue.JsonArray capabilities = (JsonValue.JsonArray) entry.value("requiredCapabilities");
        List<JsonValue> reversedCapabilities = new ArrayList<>(capabilities.values());
        Collections.reverse(reversedCapabilities);
        Map<String, JsonValue> changedEntry = new LinkedHashMap<>(entry.fields());
        changedEntry.put("requiredCapabilities", JsonValue.array(reversedCapabilities));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            replaceEntries(encoded, List.of(JsonValue.object(changedEntry)))));

        JsonValue.JsonObject ordered = codec.encode(twoEntryPublication());
        List<JsonValue> reversedEntries = new ArrayList<>(((JsonValue.JsonArray) ordered.value("entries")).values());
        Collections.reverse(reversedEntries);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replaceEntries(ordered, reversedEntries)));
    }

    @Test
    void opaqueValueAndBytesRemainConsistentAfterCallerMutation() {
        byte[] input = "{\"nested\":[{\"value\":1}]}".getBytes(StandardCharsets.UTF_8);
        byte[] expected = input.clone();
        CatalogCacheOpaque opaque = CatalogCacheOpaque.of(input);
        input[0] = '[';
        byte[] exposed = opaque.canonicalBytes();
        exposed[0] = '[';

        assertArrayEquals(expected, opaque.canonicalBytes());
        assertArrayEquals(expected, opaque.canonicalValue().canonicalBytes());
        JsonValue.JsonObject object = (JsonValue.JsonObject) opaque.canonicalValue();
        assertThrows(UnsupportedOperationException.class, () -> object.fields().clear());
        JsonValue.JsonArray nested = (JsonValue.JsonArray) object.value("nested");
        assertThrows(UnsupportedOperationException.class, () -> nested.values().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> ((JsonValue.JsonObject) nested.values().getFirst()).fields().clear());
    }

    @Test
    void publicOpaqueConstructionRejectsNoncanonicalJson() {
        byte[] data = "{ \"value\":1}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> CatalogCacheOpaque.of(data));
        assertThrows(IllegalArgumentException.class, () -> CatalogCacheOpaque.of(new byte[]{(byte) 0xc3, 0x28}));
    }

    @Test
    void publicOpaqueConstructionOwnsValidatedBytes() {
        byte[] data = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] expected = data.clone();
        CatalogCacheOpaque opaque = CatalogCacheOpaque.of(data);

        data[2] = 'x';

        assertArrayEquals(expected, opaque.canonicalBytes());
    }

    @Test
    void entryRejectsRequiredCapabilitiesAboveTheBound() {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>();
        for (int index = 0; index <= CatalogCachePublication.MAX_REQUIRED_CAPABILITIES; index++) {
            capabilities.add(ContractRef.of(new OwnerId("catalog.test"), new CapabilityId("capability-" + index)));
        }
        ContractRef<NodeId> key = ContractRef.of(new OwnerId("catalog.test"), new NodeId("bounded"));
        CatalogCacheOpaque data = CatalogCacheOpaque.of("{\"value\":1}".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> CatalogCachePublication.Entry.present(key, 1,
            CatalogCacheState.ACTIVE, capabilities, false, data));
    }

    @Test
    void decodeRejectsRequiredCapabilitiesAboveTheBoundBeforeEntryConstruction() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        JsonValue.JsonObject encoded = codec.encode(smallPublication());
        JsonValue.JsonObject entry = firstEntry(encoded);
        List<ContractRef<CapabilityId>> capabilities = new ArrayList<>();
        for (int index = 0; index <= CatalogCachePublication.MAX_REQUIRED_CAPABILITIES; index++) {
            capabilities.add(ContractRef.of(new OwnerId("catalog.test"), new CapabilityId("wire-" + index)));
        }
        capabilities.sort(Comparator.comparing(ContractRef::canonicalText));
        Map<String, JsonValue> changedEntry = new LinkedHashMap<>(entry.fields());
        changedEntry.put("requiredCapabilities", JsonValue.array(capabilities.stream()
            .map(IdentityCodec::encode).toList()));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            replaceEntries(encoded, List.of(JsonValue.object(changedEntry)))));
    }

    private static CatalogCachePublication largePublication() {
        byte[] data = compactCanonicalArray(500_000);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 1, new ContentHash(
            "0000000000000000000000000000000000000000000000000000000000000000"));
        ContractRef<NodeId> firstKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("first"));
        ContractRef<NodeId> secondKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("second"));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1, List.of(
            CatalogCachePublication.Entry.present(firstKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(data)),
            CatalogCachePublication.Entry.present(secondKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(data))));
    }

    private static CatalogCachePublication smallPublication() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 1, new ContentHash(
            "0000000000000000000000000000000000000000000000000000000000000000"));
        ContractRef<NodeId> definitionKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("entry"));
        ContractRef<CapabilityId> first = ContractRef.of(new OwnerId("catalog.test"), new CapabilityId("first"));
        ContractRef<CapabilityId> second = ContractRef.of(new OwnerId("catalog.test"), new CapabilityId("second"));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1, List.of(
            CatalogCachePublication.Entry.present(definitionKey, 1, CatalogCacheState.ACTIVE, Set.of(second, first),
                false, CatalogCacheOpaque.of("{\"value\":1}".getBytes(StandardCharsets.UTF_8)))));
    }

    private static CatalogCachePublication twoEntryPublication() {
        CatalogCachePublication first = smallPublication();
        ContractRef<NodeId> secondKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("second"));
        CatalogCachePublication.Entry second = CatalogCachePublication.Entry.present(secondKey, 1,
            CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of("{\"value\":2}".getBytes(StandardCharsets.UTF_8)));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, first.key(), 1,
            List.of(first.entries().getFirst(), second));
    }

    private static JsonValue.JsonObject firstEntry(JsonValue.JsonObject publication) {
        return (JsonValue.JsonObject) ((JsonValue.JsonArray) publication.value("entries")).values().getFirst();
    }

    private static JsonValue.JsonObject replaceEntries(JsonValue.JsonObject publication, List<JsonValue> entries) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(publication.fields());
        fields.put("entries", JsonValue.array(entries));
        return JsonValue.object(fields);
    }

    private static byte[] compactCanonicalArray(int values) {
        StringBuilder text = new StringBuilder(values * 2 + 1);
        text.append('[');
        for (int index = 0; index < values; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append('0');
        }
        text.append(']');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
