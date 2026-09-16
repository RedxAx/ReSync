package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogAuthoringPublicationCodecTest {
    private static final OwnerId OWNER = OwnerId.of("catalog.codec");
    private static final ContractRef<CapabilityId> ALPHA = ContractRef.of(OWNER, CapabilityId.of("alpha"));
    private static final ContractRef<CapabilityId> BETA = ContractRef.of(OWNER, CapabilityId.of("beta"));
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("editor"));
    private static final ContractRef<CapabilityId> OTHER = ContractRef.of(OWNER, CapabilityId.of("other"));
    private static final CatalogCacheOpaque DATA = CatalogCacheOpaque.of(
        "{\"id\":\"editor\",\"ownerId\":\"catalog.codec\"}".getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripsExactBindingAllSectionsAndUnknownFields() {
        CatalogAuthoringPublication.SectionProjection capabilities = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)),
            Map.of("futureSectionField", Map.of("keep", true)));
        CatalogAuthoringPublication.SectionProjection editors = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(EDITOR),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA, Map.of("futureEntryField", List.of("keep")))),
            Map.of());
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(capabilities, editors), Set.of(EDITOR),
            Map.of("futurePublicationField", Map.of("keep", true)));

        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        String encoded = codec.encodeText(publication);
        CatalogAuthoringPublication decoded = codec.decodeText(encoded);

        assertEquals(publication.binding(), decoded.binding());
        assertEquals(publication.contractVersion(), decoded.contractVersion());
        assertEquals(publication.projectionVersion(), decoded.projectionVersion());
        assertEquals(publication.sections(), decoded.sections());
        assertEquals(publication.advertisedEditCapabilities(), decoded.advertisedEditCapabilities());
        assertEquals(true, ((Map<?, ?>) decoded.unknown().get("futurePublicationField")).get("keep"));
        assertEquals(true, ((Map<?, ?>) decoded.section(CatalogAuthoringPublication.Section.CAPABILITIES)
            .unknown().get("futureSectionField")).get("keep"));
        assertTrue(encoded.contains("\"catalogChecksum\""));
    }

    @Test
    void codecRejectsNonCanonicalDataAndMalformedIntegralVersions() {
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of("{\"a\":2,\"b\":1}".getBytes(StandardCharsets.UTF_8)))));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        String encoded = codec.encodeText(publication);

        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(
            encoded.replace("\"minor\":0", "\"minor\":1.0")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(
            encoded.replace("\"generation\":1", "\"generation\":0")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(
            encoded.replace("\"data\":{\"a\":2,\"b\":1}", "\"data\":{\"b\":1,\"a\":2}")));
    }

    @Test
    void codecRejectsUnknownBindingFieldsButPreservesPublicationExtensions() {
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(), null, Map.of("future", true));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        String encoded = codec.encodeText(publication);

        assertEquals(true, codec.decodeText(encoded).unknown().get("future"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(
            encoded.replace("\"bindingManifestHash\"", "\"futureBindingField\":true,\"bindingManifestHash\"")));
    }

    @Test
    void unsupportedProjectionVersionRequiresAndRetainsExplicitOpaqueUnavailableData() {
        CatalogProjectionVersion current = CatalogProjectionVersion.current();
        CatalogProjectionVersion unsupported = new CatalogProjectionVersion(Math.addExact(current.generation(), 1), 0);
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, false, CatalogCacheState.UNAVAILABLE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                EDITOR.canonicalText(), CatalogCacheState.UNAVAILABLE, Set.of(), true, DATA)));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            unsupported, List.of(types));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        String encoded = codec.encodeText(publication);

        CatalogAuthoringPublication decoded = codec.decodeText(encoded);

        CatalogAuthoringPublication.Entry entry = decoded.types().getFirst();
        assertFalse(decoded.compatible());
        assertEquals(CatalogCacheState.UNAVAILABLE, entry.state());
        assertTrue(entry.opaque());
        assertEquals(DATA, entry.data());
        assertTrue(decoded.advertisedEditCapabilities().isEmpty());
        assertEquals(encoded, codec.encodeText(decoded));
    }

    @Test
    void unsupportedProjectionVersionRejectsWireThatRequiresSemanticNormalization() {
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        CatalogProjectionVersion current = CatalogProjectionVersion.current();
        String currentWire = "\"projectionVersion\":{\"generation\":" + current.generation()
            + ",\"minor\":" + current.minor() + "}";
        String unsupportedWire = "\"projectionVersion\":{\"generation\":"
            + Math.addExact(current.generation(), 1) + ",\"minor\":0}";
        String rewritten = codec.encodeText(publication).replace(currentWire, unsupportedWire);

        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(rewritten));
    }

    @Test
    void codecRejectsSectionOrderThatWouldRequireCanonicalResorting() {
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of());
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        JsonValue.JsonObject encoded = codec.encode(publication);
        JsonValue.JsonArray sections = (JsonValue.JsonArray) encoded.value("sections");
        List<JsonValue> reversed = new ArrayList<>(sections.values());
        Collections.reverse(reversed);
        Map<String, JsonValue> fields = new LinkedHashMap<>(encoded.fields());
        fields.put("sections", JsonValue.array(reversed));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.object(fields)));
    }

    @Test
    void codecRejectsEntryOrderThatWouldRequireCanonicalResorting() {
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                    EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA),
                new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                    OTHER.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        String placeholder = "catalog.codec/placeholder";
        String reordered = codec.encodeText(publication)
            .replace(EDITOR.canonicalText(), placeholder)
            .replace(OTHER.canonicalText(), EDITOR.canonicalText())
            .replace(placeholder, OTHER.canonicalText());

        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(reordered));
    }

    @Test
    void validatedDecodeRejectsCanonicalJsonWithNonCanonicalCapabilityOrder() {
        CatalogAuthoringPublication.SectionProjection capabilities = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                    ALPHA.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA),
                new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                    BETA.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication.SectionProjection editors = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(ALPHA, BETA),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA)));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(capabilities, editors));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        JsonValue.JsonObject encoded = codec.encode(publication);
        JsonValue.JsonArray advertised = (JsonValue.JsonArray) encoded.value("advertisedEditCapabilities");
        List<JsonValue> reversed = new ArrayList<>(advertised.values());
        Collections.reverse(reversed);
        Map<String, JsonValue> fields = new LinkedHashMap<>(encoded.fields());
        fields.put("advertisedEditCapabilities", JsonValue.array(reversed));
        JsonValue.JsonObject adversarial = JsonValue.object(fields);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(adversarial));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(adversarial.canonicalText()));
    }

    @Test
    void decodedEntryDataDoesNotExposeItsCanonicalBytes() {
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        CatalogAuthoringPublication.Entry decoded = codec.decodeBytes(codec.encodeBytes(publication)).types().getFirst();

        byte[] exposed = decoded.data().canonicalBytes();
        exposed[0] = '[';

        assertEquals(DATA, decoded.data());
        assertEquals(publication, codec.decode(codec.encode(decodedPublication(decoded))));
    }

    private static CatalogAuthoringPublication decodedPublication(CatalogAuthoringPublication.Entry entry) {
        CatalogAuthoringPublication.SectionProjection types = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE, List.of(entry));
        return new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));
    }

    private static CatalogBinding binding() {
        return new CatalogBinding(4, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
    }
}
