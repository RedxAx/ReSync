package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCachePublicationTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.publication");
    private static final ContractRef<NodeId> DEFINITION = ContractRef.of(OWNER, new NodeId("future-node"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, new CapabilityId("future-editor"));
    private static final ContentHash CHECKSUM = new ContentHash("a".repeat(64));
    private static final ContentHash BINDING_MANIFEST_HASH = new ContentHash("b".repeat(64));
    private static final byte[] OPAQUE = "{\"future\":{\"value\":7},\"id\":\"future-node\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void publicationRoundTripsServerGenerationChecksumRevisionAndLosslessEntries() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, new CatalogProjectionVersion(2, 1));
        CatalogCachePublication.Entry present = CatalogCachePublication.Entry.present(DEFINITION, 4,
            CatalogCacheState.UNAVAILABLE, Set.of(CAPABILITY), true, CatalogCacheOpaque.of(OPAQUE));
        CatalogCachePublication.Entry tombstone = CatalogCachePublication.Entry.tombstone(
            ContractRef.of(OWNER, new NodeId("removed")), 5);
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 5,
            List.of(present, tombstone), Map.of("futurePublicationField", Map.of("keep", true)));

        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        String encoded = codec.encodeText(publication);
        CatalogCachePublication decoded = codec.decodeText(encoded);

        assertEquals(publication.kind(), decoded.kind());
        assertEquals(SERVER, decoded.serverId());
        assertEquals(7, decoded.catalogGeneration());
        assertEquals(CHECKSUM, decoded.snapshotChecksum());
        assertEquals(new CatalogProjectionVersion(2, 1), decoded.projectionVersion());
        assertEquals(5, decoded.revision());
        assertEquals(publication.entries(), decoded.entries());
        assertEquals(true, ((Map<?, ?>) decoded.unknown().get("futurePublicationField")).get("keep"));
        assertTrue(encoded.contains("\"tombstone\":true"));
    }

    @Test
    void publicationRoundTripsCanonicalIntegralRevisionsWithTrailingZeros() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        for (long revision : List.of(10L, 100L, 1000L)) {
            CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_MANIFEST_HASH,
                CatalogProjectionVersion.current());
            CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
                revision, List.of(CatalogCachePublication.Entry.tombstone(DEFINITION, revision)));

            CatalogCachePublication decoded = codec.decodeText(codec.encodeText(publication));

            assertEquals(revision, decoded.revision());
            assertEquals(revision, decoded.entries().getFirst().revision());
        }
    }

    @Test
    void publicationRejectsInvalidRevisionNumbersWithoutWeakeningCanonicalValidation() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_MANIFEST_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 0, List.of());
        String encoded = codec.encodeText(publication);

        assertEquals(0, codec.decodeText(encoded).revision());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"revision\":0", "\"revision\":-1")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"revision\":0", "\"revision\":1.5")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"revision\":0", "\"revision\":9223372036854775808")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"revision\":0", "\"revision\":1.0")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"revision\":0", "\"revision\":1e2")));
    }

    @Test
    void publicationRejectsProjectionVersionNarrowingOverflowAsProtocolFailure() {
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_MANIFEST_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 0, List.of());
        String encoded = codec.encodeText(publication).replace("\"projectionVersion\":{\"generation\":1,\"minor\":1}",
            "\"projectionVersion\":{\"generation\":4294967297,\"minor\":0}");

        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded));
    }

    @Test
    void publicationRejectsNonCanonicalOpaqueDataBeforeStorage() {
        byte[] nonCanonical = "{\"id\":\"future-node\",\"future\":{\"value\":7}}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> CatalogCacheOpaque.of(nonCanonical));
    }

    @Test
    void publicationAcceptsCatalogEntryBeyondTheStandardTokenLimit() {
        CatalogCacheOpaque data = CatalogCacheOpaque.of("{\"value\":0}".getBytes(StandardCharsets.UTF_8));
        List<CatalogCachePublication.Entry> entries = new ArrayList<>(50_000);
        for (int index = 0; index < 50_000; index++) {
            entries.add(CatalogCachePublication.Entry.present(
                ContractRef.of(OWNER, new NodeId("node-" + index)), 1, CatalogCacheState.ACTIVE, Set.of(), false, data));
        }
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_MANIFEST_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            entries);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        assertEquals(publication, codec.decodeBytes(codec.encodeBytes(publication)));
    }

    @Test
    void publicationCarriesOptionalAuthoringProjectionWithDomainSeparatedChecksum() {
        CatalogBinding binding = new CatalogBinding(7, CHECKSUM, BINDING_MANIFEST_HASH);
        CatalogProjectionVersion projectionVersion = CatalogProjectionVersion.current();
        CatalogCacheKey key = new CatalogCacheKey(SERVER, binding, projectionVersion);
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            projectionVersion, List.of());
        CatalogCachePublication base = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 0,
            List.of(), Map.of("futurePublicationField", Map.of("keep", true)));

        CatalogCachePublication publication = base.withAuthoringPublication(authoring);
        ContentHash checksum = publication.authoringPublicationChecksum();

        assertEquals(authoring, publication.authoringPublication());
        assertTrue(publication.hasAuthoringPublication());
        assertEquals(CatalogCachePublicationCodec.authoringPublicationChecksum(authoring), checksum);
        assertNotEquals(new ContentHash(CanonicalHash.sha256("other-authoring", new CatalogAuthoringPublicationCodec()
            .encodeBytes(authoring))), checksum);

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeText(
            new CatalogCachePublicationCodec().encodeText(publication));
        assertEquals(publication, decoded);
        assertEquals(publication.unknown(), decoded.unknown());

        CatalogCachePublication removed = publication.withAuthoringPublication(null);
        assertFalse(removed.hasAuthoringPublication());
        assertEquals(base, removed);
    }

    @Test
    void publicationRejectsAuthoringBindingMismatchAndKnownUnknownCollision() {
        CatalogBinding binding = new CatalogBinding(7, CHECKSUM, BINDING_MANIFEST_HASH);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.current());
        CatalogBinding foreign = new CatalogBinding(8, CHECKSUM, BINDING_MANIFEST_HASH);
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(foreign, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of());

        CatalogCachePublication base = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> base.withAuthoringPublication(authoring));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCachePublication(
            CatalogCachePublication.Kind.FULL, key, 0, List.of(), Map.of("authoringPublication", true)));
    }

    @Test
    void codecUsesLegacyTopLevelBindingAndCurrentNestedBinding() {
        CatalogBinding binding = new CatalogBinding(7, CHECKSUM, BINDING_MANIFEST_HASH);
        CatalogCachePublication legacy = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.LEGACY), 0, List.of());
        CatalogCachePublication current = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.CURRENT), 0, List.of());
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        String legacyText = codec.encodeText(legacy);
        JsonValue.JsonObject legacyWire = (JsonValue.JsonObject) CanonicalCodec.decode(legacyText);
        assertTrue(legacyWire.contains("bindingManifestHash"));
        assertFalse(legacyWire.contains("catalogBinding"));
        assertEquals(legacy, codec.decodeText(legacyText));

        String currentText = codec.encodeText(current);
        JsonValue.JsonObject currentWire = (JsonValue.JsonObject) CanonicalCodec.decode(currentText);
        assertTrue(currentWire.contains("catalogBinding"));
        assertFalse(currentWire.contains("bindingManifestHash"));
        assertEquals(current, codec.decodeText(currentText));
    }

    @Test
    void codecRejectsBindingWireFormsFromTheWrongProjectionVersion() {
        CatalogBinding binding = new CatalogBinding(7, CHECKSUM, BINDING_MANIFEST_HASH);
        CatalogCachePublication legacy = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.LEGACY), 0, List.of());
        CatalogCachePublication current = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.CURRENT), 0, List.of());
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        String nestedBinding = "{\"generation\":7,\"catalogChecksum\":\"" + CHECKSUM.canonicalText()
            + "\",\"bindingManifestHash\":\"" + BINDING_MANIFEST_HASH.canonicalText() + "\"}";

        String legacyMixed = codec.encodeText(legacy).replace("\"projectionVersion\":",
            "\"catalogBinding\":" + nestedBinding + ",\"projectionVersion\":");
        String currentMixed = codec.encodeText(current).replace("\"catalogBinding\":",
            "\"bindingManifestHash\":\"" + BINDING_MANIFEST_HASH.canonicalText() + "\",\"catalogBinding\":");

        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(legacyMixed));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(currentMixed));
    }

    @Test
    void deltaRetainsUnknownFieldsOnPresentAndTombstoneEntries() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_MANIFEST_HASH,
            CatalogProjectionVersion.current());
        ContractRef<NodeId> removed = ContractRef.of(OWNER, new NodeId("removed"));
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(DEFINITION, CatalogCacheOpaque.of(OPAQUE));
        CatalogCacheDefinition removedDefinition = CatalogCacheDefinition.opaqueUnavailable(removed,
            CatalogCacheOpaque.of("{\"id\":\"removed\"}".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> presentUnknown = Map.of("futureEntryField", Map.of("keep", true));
        Map<String, Object> tombstoneUnknown = Map.of("futureTombstoneField", List.of("keep"));
        CatalogCacheSnapshot previous = new CatalogCacheSnapshot(key, 4, List.of(
            CatalogCacheEntry.present(DEFINITION, 4, definition, Map.of("futureEntryField", Map.of("keep", false))),
            CatalogCacheEntry.present(removed, 4, removedDefinition)));
        CatalogCacheSnapshot current = new CatalogCacheSnapshot(key, 5, List.of(
            CatalogCacheEntry.present(DEFINITION, 5, definition, presentUnknown),
            CatalogCacheEntry.tombstone(removed, 5, tombstoneUnknown)));

        CatalogCachePublication publication = CatalogCachePublication.delta(previous, current);
        CatalogCachePublication.Entry present = publication.entries().stream()
            .filter(entry -> entry.definitionKey().equals(DEFINITION)).findFirst().orElseThrow();
        CatalogCachePublication.Entry tombstone = publication.entries().stream()
            .filter(entry -> entry.definitionKey().equals(removed)).findFirst().orElseThrow();

        assertEquals(presentUnknown, present.unknown());
        assertEquals(tombstoneUnknown, tombstone.unknown());
        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeText(
            new CatalogCachePublicationCodec().encodeText(publication));
        assertEquals(publication.entries(), decoded.entries());
    }

    @Test
    void projectorKeyCarriesCatalogGenerationAndProjectionVersion() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));

        CatalogCacheSnapshot projected = CatalogCacheProjector.project(SERVER, 3, snapshot, Set.of(),
            new CatalogProjectionVersion(3, 2));

        assertEquals(1, projected.key().catalogGeneration());
        assertEquals(new CatalogProjectionVersion(3, 2), projected.key().projectionVersion());
        assertEquals(snapshot.contentChecksum(), projected.key().snapshotChecksum());
    }

    @Test
    void projectorRejectsGenerationMismatchEvenWhenChecksumMatches() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 2, snapshot.contentChecksum(), BINDING_MANIFEST_HASH,
            CatalogProjectionVersion.current());

        assertThrows(IllegalArgumentException.class, () -> CatalogCacheProjector.project(key, 1, snapshot, Set.of()));
    }
}
