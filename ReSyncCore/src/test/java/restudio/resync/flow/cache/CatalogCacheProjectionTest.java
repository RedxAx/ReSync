package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorSectionId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorId;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorSection;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCacheProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContentHash CHECKSUM_A = new ContentHash("a".repeat(64));
    private static final OwnerId OWNER = new OwnerId("resync.cache");
    private static final OwnerId SYSTEM_OWNER = new OwnerId("resync.system");
    private static final ContractRef<CapabilityId> CAPABILITY = capability("flow-execute");
    private static final ContractRef<CapabilityId> EDITOR = capability("generic-editor");
    private static final ContractRef<CapabilityId> CATEGORY = capability("flow");
    private static final ContractRef<CapabilityId> AUTHORIZATION = new ContractRef<>(SYSTEM_OWNER, new CapabilityId("flow-authorize"));
    private static final ContractRef<OperationId> OPERATION = new ContractRef<>(OWNER, new OperationId("flow-operation"));
    private static final TypeExpr STRING_TYPE = TypeExpr.named(new TypeReference("resync.types", "string"));
    private static final RuntimeFailureContract FAILURE = new RuntimeFailureContract(
        STRING_TYPE,
        Set.of("RUNTIME.FAILURE"),
        Set.of("failed"),
        RuntimeFailureContract.CommitBoundary.NO_MUTATION
    );
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final CatalogContractRange RANGE = new CatalogContractRange(CONTRACT, CONTRACT);
    private static final byte[] OPAQUE_BYTES = "{\"future\":{\"value\":7},\"id\":\"unknown\",\"pins\":[\"preserve\"]}".getBytes(StandardCharsets.UTF_8);

    @Test
    void tombstonesRemainForOrderingAndCannotCarryPayload() {
        CatalogCacheKey key = key(CHECKSUM_A);
        ContractRef<NodeId> definitionKey = definitionKey("removed");
        CatalogCacheDefinition definition = known(definitionKey, "removed");
        CatalogCacheSnapshot snapshot = new CatalogCacheSnapshot(key, 5,
            List.of(CatalogCacheEntry.tombstone(definitionKey, 5)));
        CatalogCacheEntry tombstone = snapshot.entry(definitionKey).orElseThrow();
        assertTrue(tombstone.tombstone());
        assertEquals(5, tombstone.revision());
        assertNull(tombstone.definition());
        assertTrue(snapshot.definitions().isEmpty());
        assertTrue(snapshot.selectable(definitionKey).isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> new CatalogCacheEntry(definitionKey, 5, definition, true));
        assertThrows(IllegalArgumentException.class,
            () -> new CatalogCacheEntry(definitionKey, 5, null, false));
    }

    @Test
    void unavailableOpaqueDefinitionsRemainLosslessButNotSelectable() {
        ContractRef<NodeId> definitionKey = definitionKey("unknown");
        CatalogCacheOpaque opaque = CatalogCacheOpaque.of(OPAQUE_BYTES);
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(definitionKey, opaque,
            Set.of(CAPABILITY));

        CatalogCacheSnapshot snapshot = new CatalogCacheSnapshot(key(CHECKSUM_A), 2,
            List.of(CatalogCacheEntry.present(definitionKey, 2, definition)));

        assertArrayEquals(OPAQUE_BYTES, definition.opaque().canonicalBytes());
        assertNull(definition.descriptor());
        assertEquals(Set.of(CAPABILITY), definition.requiredCapabilities());
        assertEquals(CatalogCacheState.UNAVAILABLE, definition.state());
        assertFalse(definition.selectable());
        assertFalse(definition.editable());
        assertSame(definition, snapshot.entry(definitionKey).orElseThrow().definition());
        assertTrue(snapshot.selectable(definitionKey).isEmpty());
        assertEquals(List.of(definition), snapshot.definitions());
    }

    @Test
    void capabilityProjectionDistinguishesReadOnlyAndActive() {
        ContractRef<NodeId> definitionKey = definitionKey("capable");
        CatalogNodeDescriptor descriptor = node("capable", CatalogNodeDescriptor.Lifecycle.ACTIVE, Set.of(CAPABILITY));
        CatalogOwned<CatalogNodeDescriptor> owned = owned(definitionKey, descriptor);

        CatalogCacheDefinition readOnly = CatalogCacheProjector.projectDefinition(owned, Set.of());
        CatalogCacheDefinition active = CatalogCacheProjector.projectDefinition(owned, Set.of(CAPABILITY));

        assertEquals(CatalogCacheState.READ_ONLY, readOnly.state());
        assertTrue(readOnly.selectable());
        assertFalse(readOnly.editable());
        assertSame(descriptor, readOnly.descriptor());
        assertEquals(Set.of(CAPABILITY), readOnly.requiredCapabilities());
        assertEquals(CatalogCacheState.ACTIVE, active.state());
        assertTrue(active.selectable());
        assertTrue(active.editable());
        assertSame(descriptor, active.descriptor());
    }

    @Test
    void migrationOnlyProjectionRetainsTypedDescriptorButIsUnavailable() {
        ContractRef<NodeId> definitionKey = definitionKey("migration-only");
        CatalogNodeDescriptor descriptor = node("migration-only", CatalogNodeDescriptor.Lifecycle.MIGRATION_ONLY,
            Set.of(CAPABILITY));

        CatalogCacheDefinition projection = CatalogCacheProjector.projectDefinition(owned(definitionKey, descriptor),
            Set.of(CAPABILITY));

        assertEquals(CatalogCacheState.UNAVAILABLE, projection.state());
        assertSame(descriptor, projection.descriptor());
        assertEquals(Set.of(CAPABILITY), projection.requiredCapabilities());
        assertFalse(projection.selectable());
        assertFalse(projection.editable());
    }

    @Test
    void liveSnapshotProjectionPublishesLinkedInspectorContent() {
        CatalogNodeDescriptor descriptor = node("linked", CatalogNodeDescriptor.Lifecycle.ACTIVE, Set.of());
        InspectorDescriptor inspector = new InspectorDescriptor(OWNER, InspectorId.of("generic"), "Generic Inspector",
            "Edits linked node values through the generic inspector.", List.of(new InspectorSection(
            InspectorSectionId.of("linked-settings"), "Linked Settings", "Contains the linked inspector settings.", List.of(), null)), List.of());
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", RANGE,
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/linked", "1.0.0", "test", "linked"))
            .definitions(List.of(descriptor))
            .inspectors(List.of(inspector))
            .build();
        CatalogSnapshot snapshot = snapshot(contribution);

        CatalogCacheSnapshot projected = CatalogCacheProjector.project(SERVER, 6, snapshot, Set.of());
        CatalogCacheEntry projectedEntry = projected.entry(definitionKey("linked")).orElseThrow();
        String expected = CatalogCanonicalizer.canonicalNodeContent(descriptor, contribution);
        assertEquals(expected, new String(projectedEntry.definition().canonicalDescriptor().canonicalBytes(), StandardCharsets.UTF_8));

        CatalogCachePublication.Entry publication = CatalogCachePublication.from(projected).entries().getFirst();
        String published = new String(publication.data().canonicalBytes(), StandardCharsets.UTF_8);
        assertEquals(expected, published);
        assertTrue(published.contains("\"id\":\"generic\""));
        assertTrue(published.contains("\"id\":\"linked-settings\""));
    }

    @Test
    void projectionRejectsSnapshotChecksumMismatch() {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));

        assertThrows(IllegalArgumentException.class,
            () -> CatalogCacheProjector.project(key(CHECKSUM_A), 1, snapshot, Set.of()));
    }

    @Test
    void cacheSnapshotsExposeImmutableCollections() {
        CatalogCacheKey key = key(CHECKSUM_A);
        ContractRef<NodeId> definitionKey = definitionKey("immutable");
        CatalogCacheEntry entry = CatalogCacheEntry.present(definitionKey, 1, known(definitionKey, "immutable"));
        CatalogCacheSnapshot snapshot = new CatalogCacheSnapshot(key, 1, List.of(entry));

        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
    }

    private static CatalogCacheKey key(ContentHash checksum) {
        return new CatalogCacheKey(SERVER, checksum);
    }

    private static CatalogSnapshot snapshot(CatalogContribution contribution) {
        List<CatalogContribution> contributions = List.of(contribution);
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(2, CONTRACT, contributions, Set.of(), List.of());
        return new CatalogSnapshot(2, CONTRACT,
            CatalogCanonicalizer.contentChecksum(2, CONTRACT, contributions, Set.of(), List.of()),
            CatalogCanonicalizer.bindingManifestHash(contributions), Set.of(), contributions,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), canonical);
    }

    private static ContractRef<NodeId> definitionKey(String id) {
        return new ContractRef<>(OWNER, new NodeId(id));
    }

    private static ContractRef<CapabilityId> capability(String id) {
        return new ContractRef<>(OWNER, new CapabilityId(id));
    }

    private static CatalogCacheDefinition known(ContractRef<NodeId> key, String id) {
        return CatalogCacheDefinition.known(key, node(id, CatalogNodeDescriptor.Lifecycle.ACTIVE, Set.of()),
            CatalogCacheState.ACTIVE);
    }

    private static CatalogOwned<CatalogNodeDescriptor> owned(ContractRef<NodeId> key, CatalogNodeDescriptor descriptor) {
        return new CatalogOwned<>(key, descriptor, CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
            "classpath:/nodes/" + descriptor.id().value() + ".json", "1.0.0", "test", descriptor.id().value()));
    }

    private static CatalogNodeDescriptor node(String id, CatalogNodeDescriptor.Lifecycle lifecycle,
                                              Set<ContractRef<CapabilityId>> requiredCapabilities) {
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin("value", CatalogNodeDescriptor.Direction.INPUT,
            STRING_TYPE, "Value", "The value supplied to the catalog operation.",
            CatalogNodeDescriptor.Requirement.REQUIRED, EDITOR);
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch("failed", "Failed",
            "Describes the failure outcome for this catalog operation.", List.of(
            new CatalogNodeDescriptor.Case("failure", "Failure",
                "The operation completed with a structured failure.")));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            AUTHORIZATION,
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of(),
            Set.of("failed"),
            Set.of(),
            FAILURE,
            Set.<ContractRef<ResourceTypeId>>of(),
            Set.<ContractRef<ResourceTypeId>>of()
        );
        return CatalogNodeDescriptor.builder(id)
            .lifecycle(lifecycle)
            .domain("flow")
            .family("operation")
            .displayName("Operation")
            .description("Executes one deterministic catalog operation with explicit behavior.")
            .category(CATEGORY)
            .pins(List.of(pin))
            .inspector(InspectorId.of("generic"))
            .branches(List.of(failure))
            .handler(new CatalogNodeDescriptor.Handler(CAPABILITY, OPERATION))
            .semantics(semantics)
            .requiredCapabilities(requiredCapabilities)
            .build();
    }
}
