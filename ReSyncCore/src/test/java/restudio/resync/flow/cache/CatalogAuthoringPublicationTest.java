package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogAuthoringPublicationTest {
    private static final OwnerId OWNER = OwnerId.of("catalog.authoring");
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("editor"));
    private static final ContractRef<CapabilityId> EDITOR_DESCRIPTOR =
        ContractRef.of(OWNER, CapabilityId.of("editor-descriptor"));
    private static final ContractRef<CapabilityId> OTHER = ContractRef.of(OWNER, CapabilityId.of("other"));
    private static final CatalogCacheOpaque DATA = CatalogCacheOpaque.of(
        "{\"id\":\"editor\",\"ownerId\":\"catalog.authoring\"}".getBytes(StandardCharsets.UTF_8));

    @Test
    void projectsEveryTypedAuthoringSectionUnderTheExactCatalogBinding() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));

        CatalogAuthoringPublication publication = CatalogAuthoringPublication.project(snapshot, Set.of());

        CatalogBinding expected = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(),
            snapshot.bindingManifestHash());
        assertEquals(expected, publication.binding());
        assertEquals(List.of("capabilities", "categories", "conversions", "editors", "optionSources",
            "previews", "types", "validators"), publication.sections().stream()
            .map(value -> value.section().wireName()).toList());
        assertTrue(publication.sections().stream().allMatch(value -> value.present()));
        assertTrue(publication.sections().stream().allMatch(value -> value.acknowledged()));
        assertTrue(publication.advertisedEditCapabilities().isEmpty());
    }

    @Test
    void unacknowledgedEditorSectionCannotAdvertiseAnEditCapability() {
        CatalogBinding binding = binding();
        CatalogAuthoringPublication.SectionProjection capabilities = section(
            CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication.SectionProjection editors = section(
            CatalogAuthoringPublication.Section.EDITORS, true, false, CatalogCacheState.READ_ONLY,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.READ_ONLY, Set.of(EDITOR),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA)));

        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(capabilities, editors), null);

        assertTrue(publication.advertisedEditCapabilities().isEmpty());
        assertFalse(publication.canEdit(EDITOR));
    }

    @Test
    void advertisedEditCapabilityUsesTheEditorDescriptorReference() {
        CatalogBinding binding = binding();
        CatalogAuthoringPublication.SectionProjection capabilities = section(
            CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                EDITOR_DESCRIPTOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication.SectionProjection editors = section(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(EDITOR_DESCRIPTOR),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA)));

        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(capabilities, editors), null);

        assertTrue(publication.canEdit(EDITOR_DESCRIPTOR));
        assertFalse(publication.canEdit(EDITOR));
    }

    @Test
    void incompatibleProjectionKeepsEntriesOpaqueAndReadOnly() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));

        CatalogAuthoringPublication publication = CatalogAuthoringPublication.project(snapshot, Set.of(),
            new CatalogProjectionVersion(Math.addExact(CatalogProjectionVersion.current().generation(), 1), 0));

        assertFalse(publication.compatible());
        assertTrue(publication.advertisedEditCapabilities().isEmpty());
        assertTrue(publication.sections().stream().allMatch(value -> value.state() == CatalogCacheState.UNAVAILABLE));
        assertTrue(publication.sections().stream().allMatch(value -> !value.acknowledged()));
    }

    @Test
    void projectionRejectsAForeignCatalogBinding() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogBinding foreign = new CatalogBinding(snapshot.generation(), new ContentHash("a".repeat(64)),
            snapshot.bindingManifestHash());

        assertThrows(IllegalArgumentException.class, () -> CatalogAuthoringPublication.project(foreign, snapshot,
            Set.of()));
    }

    @Test
    void acknowledgedSectionsCanBeRestrictedWithoutDroppingOpaqueData() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        Set<CatalogAuthoringPublication.Section> acknowledged = EnumSet.of(
            CatalogAuthoringPublication.Section.TYPES, CatalogAuthoringPublication.Section.CAPABILITIES);

        CatalogAuthoringPublication publication = CatalogAuthoringPublication.project(snapshot, Set.of(), acknowledged);

        assertTrue(publication.section(CatalogAuthoringPublication.Section.TYPES).acknowledged());
        assertFalse(publication.section(CatalogAuthoringPublication.Section.EDITORS).acknowledged());
        assertEquals(CatalogCacheState.READ_ONLY,
            publication.section(CatalogAuthoringPublication.Section.EDITORS).state());
    }

    @Test
    void absentSectionsRemainDistinctFromAcknowledgedEmptySections() {
        CatalogAuthoringPublication.SectionProjection types = section(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE, List.of());

        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(binding(), new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(types));

        assertTrue(publication.section(CatalogAuthoringPublication.Section.TYPES).present());
        assertTrue(publication.section(CatalogAuthoringPublication.Section.TYPES).entries().isEmpty());
        assertFalse(publication.section(CatalogAuthoringPublication.Section.EDITORS).present());
        assertTrue(publication.section(CatalogAuthoringPublication.Section.EDITORS).entries().isEmpty());
    }

    @Test
    void requiredSectionsMustIncludeTheEntrySection() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(OTHER), Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false, DATA));
    }

    @Test
    void publicEntryKeepsItsValidatedIdentityAndRejectsNonCanonicalData() {
        CatalogAuthoringPublication.Entry entry = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), false, DATA);

        assertSame(entry.reference(), entry.reference());
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), false, CatalogCacheOpaque.of(
                "{\"ownerId\":\"catalog.authoring\",\"id\":\"editor\"}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void publicConstructionRejectsKnownFieldCollisionsImmediately() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication(binding(),
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), List.of(), null, Map.of("kind", "future")));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE, List.of(),
            Map.of("state", "future")));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.TYPES, EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(),
            Set.of(CatalogAuthoringPublication.Section.TYPES), false, DATA, Map.of("data", "future")));
    }

    @Test
    void activeEntriesRequireCompleteActiveCapabilityClosure() {
        CatalogAuthoringPublication.SectionProjection capabilities = section(
            CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                EDITOR_DESCRIPTOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false, DATA)));
        CatalogAuthoringPublication.SectionProjection undeclaredEditor = section(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(OTHER),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA)));
        CatalogAuthoringPublication.SectionProjection incompleteEditor = section(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(EDITOR_DESCRIPTOR),
                Set.of(CatalogAuthoringPublication.Section.EDITORS), false, DATA)));
        CatalogAuthoringPublication.SectionProjection validEditor = section(
            CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE,
            List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.EDITORS,
                EDITOR.canonicalText(), CatalogCacheState.ACTIVE, Set.of(EDITOR_DESCRIPTOR),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
                false, DATA)));

        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication(binding(),
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), List.of(capabilities, undeclaredEditor)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication(binding(),
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), List.of(capabilities, incompleteEditor)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuthoringPublication(binding(),
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), List.of(capabilities, validEditor), Set.of()));
    }

    @Test
    void sectionStateCannotExposeEditableEntriesFromAReadOnlySection() {
        CatalogAuthoringPublication.Entry active = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.TYPES, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), false, DATA);

        assertThrows(IllegalArgumentException.class, () -> section(CatalogAuthoringPublication.Section.TYPES,
            true, true, CatalogCacheState.READ_ONLY, List.of(active)));
    }

    private static CatalogBinding binding() {
        return new CatalogBinding(1, new ContentHash("b".repeat(64)), new ContentHash("c".repeat(64)));
    }

    private static CatalogAuthoringPublication.SectionProjection section(CatalogAuthoringPublication.Section section,
                                                                          boolean present, boolean acknowledged,
                                                                          CatalogCacheState state,
                                                                          List<CatalogAuthoringPublication.Entry> entries) {
        return new CatalogAuthoringPublication.SectionProjection(section, present, acknowledged, state, entries,
            Map.of());
    }
}
