package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCachePublicationTransportTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @Test
    void publishesOnlyTheAuthoritativeActiveSnapshot() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, active::get, Set.of());

        CatalogCachePublication publication = transport.publishFull().orElseThrow();

        assertEquals(active.get().generation(), publication.catalogGeneration());
        assertEquals(active.get().contentChecksum(), publication.snapshotChecksum());
        assertEquals(publication, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void activationTransportReadsCatalogAndRuntimeFromOneRecord() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());

        CatalogCachePublication publication = transport.publishFull().orElseThrow();

        assertEquals(catalog.generation(), publication.catalogGeneration());
        assertEquals(catalog.contentChecksum(), publication.snapshotChecksum());
        assertEquals(catalog.bindingManifestHash(), activation.active().runtime().bindingManifestHash());
    }

    @Test
    void repeatedFullPublicationReusesTheCommittedBaselineDraft() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());

        CatalogCachePublication first = transport.publishFull().orElseThrow();
        CatalogCachePublication reconnect = transport.publishFull().orElseThrow();

        assertSame(first, reconnect);
        assertEquals(first.revision(), transport.lastValidProjection().orElseThrow().revision());
    }

    @Test
    void fullPublicationCacheIsInvalidatedByActivationReplacementAndNewerDelta() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogCachePublication initial = transport.publishFull().orElseThrow();

        CatalogSnapshot replacementCatalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 2).snapshot().orElseThrow();
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of());
        try {
            assertTrue(activation.stageReplacement(replacementCatalog, replacement).commit().committed());
            CatalogCachePublication replacementPublication = transport.publishFull().orElseThrow();

            assertNotSame(initial, replacementPublication);
            assertNotEquals(initial.key(), replacementPublication.key());
            CatalogCacheSnapshot previous = transport.lastValidProjection().orElseThrow();
            CatalogCacheSnapshot next = new CatalogCacheSnapshot(replacementPublication.key(),
                previous.revision() + 1, List.of());
            CatalogCachePublication delta = transport.publishDelta(previous, next).orElseThrow();
            CatalogCachePublication rebuilt = transport.publishFull().orElseThrow();

            assertNotSame(replacementPublication, rebuilt);
            assertTrue(rebuilt.revision() > delta.revision());
        } finally {
            replacement.close();
        }
    }

    @Test
    void globalAuthoringCapabilityOmitsSessionSpecificIdentity() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());

        Map<String, Object> capability = transport.catalogAuthoringCapability().orElseThrow();

        assertEquals("restudio.resync/catalog_authoring", capability.get("capability"));
        assertEquals(true, capability.get("available"));
        assertEquals(CatalogProjectionVersion.current().canonicalText(), capability.get("projectionVersion"));
        assertEquals(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES, capability.get("maxPublicationBytes"));
        assertEquals(CatalogPublicationChunkPacket.MAX_CHUNK_BYTES, capability.get("maxChunkBytes"));
        assertFalse(capability.containsKey("catalogBinding"));
        assertFalse(capability.containsKey("authoringChecksum"));
    }

    @Test
    void sessionAuthoringUsesActiveOwnerQualifiedCapabilitiesWhenHandshakeOnlyHasGenericSupport() {
        CatalogSnapshot active = authoringSnapshot();
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCachePublication publication = transport.publishFull().orElseThrow();

        assertFalse(publication.authoringPublication().section(CatalogAuthoringPublication.Section.EDITORS).editable());
        ContractRef<CapabilityId> authoringProtocol = ContractRef.of(OwnerId.of("restudio.resync"),
            CapabilityId.of("catalog_authoring"));
        CatalogCachePublication session = transport.publicationForSession(publication, Set.of(authoringProtocol));

        assertTrue(session.authoringPublication().section(CatalogAuthoringPublication.Section.EDITORS).editable());
        assertTrue(session.authoringPublication().advertisedEditCapabilities().contains(AUTHORING_EDITOR));
        assertSame(session.authoringPublication(), transport.publicationForSession(publication,
            Set.of(authoringProtocol)).authoringPublication());
    }

    @Test
    void activationAuthoringProjectionUsesCurrentCapabilitiesAfterReload() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot initialCatalog = reloadedAuthoringSnapshot(AUTHORING_EDITOR, runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initialCatalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation);
        transport.prewarmAuthoring();

        CatalogAuthoringPublication initialAuthoring = awaitAuthoring(transport);
        CatalogCachePublication initialPublication = transport.publishFull().orElseThrow();
        assertTrue(initialAuthoring.canEdit(AUTHORING_EDITOR));
        assertEquals(initialAuthoring, initialPublication.authoringPublication());

        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of());
        try {
            CatalogSnapshot replacementCatalog = reloadedAuthoringSnapshot(REPLACEMENT_EDITOR, replacement.preview(), 2);
            assertTrue(activation.stageReplacement(replacementCatalog, replacement).commit().committed());
            transport.prewarmAuthoring();

            CatalogAuthoringPublication reloadedAuthoring = awaitAuthoring(transport);
            CatalogCachePublication reloadedPublication = transport.publishFull().orElseThrow();
            assertTrue(reloadedAuthoring.canEdit(REPLACEMENT_EDITOR));
            assertFalse(reloadedAuthoring.canEdit(AUTHORING_EDITOR));
            assertEquals(reloadedAuthoring, reloadedPublication.authoringPublication());
        } finally {
            replacement.close();
        }
    }

    @Test
    void authoringReadinessCompletesAfterTheProjectionIsAvailable() throws Exception {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = reloadedAuthoringSnapshot(AUTHORING_EDITOR, runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation);

        assertTrue(transport.ensureCatalogAuthoringAvailable().get(2L, TimeUnit.SECONDS));
        assertTrue(transport.catalogAuthoringAvailable());
        assertTrue(transport.catalogAuthoringPublication().orElseThrow().canEdit(AUTHORING_EDITOR));
    }

    @Test
    void asyncPreparationWaitsForMatchingAuthoringInsteadOfDowngrading() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = reloadedAuthoringSnapshot(AUTHORING_EDITOR, runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation);

        CatalogCachePublicationTransport.PreparationResult pending = transport.prepareFullAsync().join();

        assertEquals(CatalogCachePublicationTransport.PreparationStatus.NOT_READY, pending.status());
        assertTrue(pending.retryable());
        assertTrue(pending.prepared() == null);
        assertTrue(transport.lastValidPublication().isEmpty());

        awaitAuthoring(transport);
        CatalogCachePublicationTransport.PreparationResult ready = transport.prepareFullAsync().join();

        assertTrue(ready.ready());
        CatalogCachePublication prepared = ready.prepared().publication();
        assertTrue(prepared.hasAuthoringPublication());
        assertTrue(transport.commitAsyncPreparedPublication(ready.prepared()));
        assertEquals(prepared, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void asyncPreparationRejectsAnActivationChangedAfterCapture() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = reloadedAuthoringSnapshot(AUTHORING_EDITOR, runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation);
        transport.prewarmAuthoring();
        awaitAuthoring(transport);

        CatalogCachePublicationTransport.PreparationResult result = transport.prepareFullAsync().join();
        assertTrue(result.ready());
        CatalogRuntimeActivation.ActivationRecord replacement = activation.publishPublicationKey(
            result.prepared().publication().key());

        assertFalse(transport.commitAsyncPreparedPublication(result.prepared()));
        assertEquals("CATALOG_PUBLICATION.ASYNC_STALE", transport.lastFailureCode().orElseThrow());
        assertTrue(transport.lastValidPublication().isEmpty());
        assertNotSame(result.prepared().activation(), replacement);
    }

    @Test
    void asyncRefreshUsesTheExactCommittedBaseline() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = reloadedAuthoringSnapshot(AUTHORING_EDITOR, runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation);
        transport.prewarmAuthoring();
        awaitAuthoring(transport);

        CatalogCachePublicationTransport.PreparationResult full = transport.prepareFullAsync().join();
        assertTrue(full.ready());
        CatalogAuthoringPublication authoring = full.prepared().publication().authoringPublication();
        assertTrue(transport.commitAsyncPreparedPublication(full.prepared()));
        CatalogCacheSnapshot baseline = transport.lastValidProjection().orElseThrow();

        CatalogCachePublicationTransport.PreparationResult refresh = transport.prepareRefreshAsync().join();

        assertTrue(refresh.ready());
        assertSame(authoring, refresh.prepared().publication().authoringPublication());
        assertEquals(CatalogCachePublication.Kind.DELTA, refresh.prepared().publication().kind());
        assertSame(baseline, refresh.prepared().baselineProjection());
        assertTrue(transport.commitAsyncPreparedPublication(refresh.prepared()));
        assertTrue(transport.lastValidProjection().orElseThrow().revision() > baseline.revision());
    }

    @Test
    void preparedPublicationDoesNotAdvanceBaselineUntilCommit() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());

        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();

        assertTrue(transport.lastValidPublication().isEmpty());
        assertTrue(transport.validatesAgainstActive(prepared));
        assertTrue(transport.commitPreparedPublication(prepared));
        assertEquals(prepared, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void activationPreparationDoesNotPublishItsKeyAndDiscardKeepsItUnchanged() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogRuntimeActivation.ActivationRecord before = activation.active();

        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();

        assertSame(before, activation.active());
        assertTrue(activation.activePublicationKey().isEmpty());
        assertTrue(transport.validatesAgainstActive(prepared));
        transport.discardPreparedPublication(prepared);
        assertSame(before, activation.active());
        assertTrue(activation.activePublicationKey().isEmpty());
    }

    @Test
    void preparedPublicationRejectsAReplacementActivationWithTheSameSemanticIdentity() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogRuntimeActivation.ActivationRecord staged = activation.active();
        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();

        CatalogRuntimeActivation.ActivationRecord replacement = activation.publishPublicationKey(prepared.key());

        assertNotSame(staged, replacement);
        assertSame(staged.catalog(), replacement.catalog());
        assertSame(staged.runtime(), replacement.runtime());
        assertEquals(Optional.of(prepared.key()), replacement.publicationKey());
        assertFalse(transport.validatesAgainstActive(prepared));
        assertFalse(transport.commitPreparedPublication(prepared));
        assertEquals("CATALOG_PUBLICATION.KEY_MISMATCH", transport.lastFailureCode().orElseThrow());
        assertTrue(transport.lastValidPublication().isEmpty());
    }

    @Test
    void preparedRefreshAndDiscardPreserveTheCommittedActivationKey() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogCachePublication initial = transport.publishFull().orElseThrow();
        CatalogRuntimeActivation.ActivationRecord before = activation.active();

        CatalogCachePublication prepared = transport.prepareRefresh().orElseThrow();

        assertSame(before, activation.active());
        assertEquals(initial.key(), activation.activePublicationKey().orElseThrow());
        transport.discardPreparedPublication(prepared);
        assertSame(before, activation.active());
        assertEquals(initial.key(), activation.activePublicationKey().orElseThrow());
    }

    @Test
    void commitPublishesThePreparedKeyOnce() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();

        assertTrue(transport.commitPreparedPublication(prepared));
        CatalogRuntimeActivation.ActivationRecord committed = activation.active();
        assertEquals(prepared.key(), committed.publicationKey().orElseThrow());
        assertFalse(transport.commitPreparedPublication(prepared));
        assertSame(committed, activation.active());
    }

    @Test
    void publicationFenceExposesOnlyTheExactCommittedActivation() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogCachePublication publication = transport.prepareFull().orElseThrow();

        assertTrue(transport.commitPreparedPublication(publication));
        CatalogRuntimeActivation.ActivationRecord committed = transport.committedActivationFor(publication)
            .orElseThrow();
        assertTrue(transport.withPublicationFence(publication, committed, () -> true));
        CatalogRuntimeActivation.ActivationRecord equivalent = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, committed.publicationKey());
        assertFalse(transport.withPublicationFence(publication, equivalent, () -> true));
    }

    @Test
    void pendingValidationRequiresTheExactPreparedCandidate() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();
        CatalogCachePublication forged = new CatalogCachePublication(prepared.kind(), prepared.key(),
            prepared.catalogBinding(), prepared.revision(), prepared.entries(), prepared.authoringPublication(),
            Map.of("forged", true));

        assertFalse(transport.validatesAgainstActive(forged));
        assertFalse(transport.commitPreparedPublication(forged));
        assertTrue(activation.activePublicationKey().isEmpty());
        assertTrue(transport.lastValidPublication().isEmpty());
    }

    @Test
    void staleActiveSnapshotRejectsThePreparedCandidate() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, active::get, Set.of());
        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();
        active.set(new CatalogCompiler(new CatalogVersion(1, 0)).compile(List.of(), 2).snapshot().orElseThrow());

        assertFalse(transport.validatesAgainstActive(prepared));
        assertFalse(transport.commitPreparedPublication(prepared));
        assertTrue(transport.lastValidPublication().isEmpty());
    }

    @Test
    void discardedPublicationLeavesTheLastCommittedBaselineUntouched() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCachePublication previous = transport.publishFull().orElseThrow();

        CatalogCachePublication prepared = transport.prepareRefresh().orElseThrow();
        transport.discardPreparedPublication(prepared);

        assertEquals(previous, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void aSuccessfulPreparationClearsThePreviousFailureCode() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());

        transport.publishFull(new CatalogCacheKey(SERVER, active.generation() + 1, active.contentChecksum()));
        assertEquals("CATALOG_PUBLICATION.KEY_MISMATCH", transport.lastFailureCode().orElseThrow());

        assertTrue(transport.prepareFull().isPresent());
        assertTrue(transport.lastFailureCode().isEmpty());
    }

    @Test
    void keyMismatchAndMissingSnapshotKeepTheLastValidPublication() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, active::get, Set.of());
        CatalogCachePublication previous = transport.publishFull().orElseThrow();

        assertTrue(transport.publishFull(new CatalogCacheKey(SERVER, 2, previous.snapshotChecksum())).isEmpty());
        assertEquals("CATALOG_PUBLICATION.KEY_MISMATCH", transport.lastFailureCode().orElseThrow());
        assertEquals(previous, transport.lastValidPublication().orElseThrow());

        active.set(null);
        assertTrue(transport.publishFull().isEmpty());
        assertEquals("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT", transport.lastFailureCode().orElseThrow());
        assertEquals(previous, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void supplierFailureFailsClosedWithoutReplacingState() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active.get(), Set.of());
        CatalogCachePublication previous = transport.publishFull().orElseThrow();
        active.set(null);

        assertTrue(transport.activeKey().isEmpty());
        assertEquals(previous, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void publishesDeltaWithTombstonesOnlyForTheActiveKey() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCacheKey key = currentKey(active);
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("resync.transport"), new NodeId("removed"));
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(node,
            CatalogCacheOpaque.of("{\"id\":\"removed\"}".getBytes(StandardCharsets.UTF_8)));
        CatalogCacheSnapshot previous = new CatalogCacheSnapshot(key, 1,
            List.of(CatalogCacheEntry.present(node, 1, definition)));
        CatalogCacheSnapshot current = new CatalogCacheSnapshot(key, 2,
            List.of(CatalogCacheEntry.tombstone(node, 2)));

        CatalogCachePublication publication = transport.publishDelta(previous, current).orElseThrow();

        assertEquals(CatalogCachePublication.Kind.DELTA, publication.kind());
        assertTrue(publication.entries().getFirst().tombstone());
        assertEquals(publication, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void derivesDeltaFromLastValidProjectionAndPreservesOpaqueDataAndTombstones() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCacheKey key = currentKey(active);
        ContractRef<NodeId> removed = ContractRef.of(new OwnerId("resync.transport"), new NodeId("removed"));
        ContractRef<NodeId> changed = ContractRef.of(new OwnerId("resync.transport"), new NodeId("changed"));
        CatalogCacheSnapshot previous = new CatalogCacheSnapshot(key, 1, List.of(
            CatalogCacheEntry.present(removed, 1, CatalogCacheDefinition.opaqueUnavailable(removed,
                CatalogCacheOpaque.of("{\"id\":\"removed\"}".getBytes(StandardCharsets.UTF_8)))),
            CatalogCacheEntry.present(changed, 1, CatalogCacheDefinition.opaqueUnavailable(changed,
                CatalogCacheOpaque.of("{\"id\":\"before\"}".getBytes(StandardCharsets.UTF_8))))));
        CatalogCacheSnapshot current = new CatalogCacheSnapshot(key, 2, List.of(
            CatalogCacheEntry.present(removed, 2, CatalogCacheDefinition.opaqueUnavailable(removed,
                CatalogCacheOpaque.of("{\"id\":\"removed\"}".getBytes(StandardCharsets.UTF_8)))),
            CatalogCacheEntry.present(changed, 2, CatalogCacheDefinition.opaqueUnavailable(changed,
                CatalogCacheOpaque.of("{\"id\":\"after\"}".getBytes(StandardCharsets.UTF_8))))));

        transport.publishDelta(previous, current).orElseThrow();
        CatalogCacheSnapshot next = new CatalogCacheSnapshot(key, 3, List.of(
            CatalogCacheEntry.present(changed, 3, CatalogCacheDefinition.opaqueUnavailable(changed,
                CatalogCacheOpaque.of("{\"id\":\"final\"}".getBytes(StandardCharsets.UTF_8))))));

        CatalogCachePublication publication = transport.publishDelta(next).orElseThrow();

        assertEquals(CatalogCachePublication.Kind.DELTA, publication.kind());
        assertTrue(publication.entries().stream().anyMatch(CatalogCachePublication.Entry::tombstone));
        CatalogCachePublication.Entry changedEntry = publication.entries().stream()
            .filter(entry -> entry.definitionKey().equals(changed))
            .findFirst().orElseThrow();
        assertArrayEquals("{\"id\":\"final\"}".getBytes(StandardCharsets.UTF_8), changedEntry.data().canonicalBytes());
    }

    @Test
    void baseFromAnotherCatalogGenerationRequiresFullPublication() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCacheKey previousKey = new CatalogCacheKey(SERVER, active.generation() + 1, active.contentChecksum());
        CatalogCacheKey currentPublicationKey = currentKey(active);
        CatalogCacheSnapshot previous = CatalogCacheSnapshot.empty(previousKey);
        CatalogCacheSnapshot current = new CatalogCacheSnapshot(currentPublicationKey, 1, List.of());

        assertTrue(transport.publishDelta(previous, current).isEmpty());
        assertEquals("CATALOG_PUBLICATION.DELTA_REQUIRES_FULL", transport.lastFailureCode().orElseThrow());
        assertTrue(transport.lastValidPublication().isEmpty());
    }

    @Test
    void derivingDeltaWithoutAValidBaseFailsClosed() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
        CatalogCacheKey key = currentKey(active);

        assertTrue(transport.publishDelta(CatalogCacheSnapshot.empty(key)).isEmpty());
        assertEquals("CATALOG_PUBLICATION.NO_BASE_PROJECTION", transport.lastFailureCode().orElseThrow());
    }

    @Test
    void refreshFallsBackToFullWhenTheAuthoringBindingGenerationChanges() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, active::get, Set.of());

        CatalogCachePublication initial = transport.publishFull().orElseThrow();
        CatalogSnapshot next = new CatalogCompiler(new CatalogVersion(1, 0)).compile(List.of(), 2).snapshot().orElseThrow();
        active.set(next);

        CatalogCachePublication refresh = transport.publishRefresh().orElseThrow();

        assertEquals(CatalogCachePublication.Kind.FULL, refresh.kind());
        assertEquals(currentKey(next), refresh.key());
        assertEquals(next.generation(), refresh.catalogGeneration());
        assertEquals(next.bindingManifestHash(), refresh.catalogBinding().bindingManifestHash());
        assertTrue(refresh.hasAuthoringPublication());
        assertEquals(refresh.catalogBinding(), refresh.authoringPublication().binding());
        assertEquals(refresh.projectionVersion(), refresh.authoringPublication().projectionVersion());
        assertTrue(refresh.revision() > initial.revision());
        assertEquals(refresh, transport.lastValidPublication().orElseThrow());
    }

    @Test
    void refreshFallsBackToFullWhenTheSnapshotChecksumChanges() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, active::get, Set.of());

        CatalogCachePublication initial = transport.publishFull().orElseThrow();
        active.set(new CatalogCompiler(new CatalogVersion(2, 0)).compile(List.of(), 2).snapshot().orElseThrow());

        CatalogCachePublication refresh = transport.publishRefresh().orElseThrow();

        assertEquals(CatalogCachePublication.Kind.FULL, refresh.kind());
        assertNotEquals(initial.key(), refresh.key());
        assertEquals(2, refresh.catalogGeneration());
        assertEquals(active.get().contentChecksum(), refresh.snapshotChecksum());
    }

    private static CatalogCacheKey currentKey(CatalogSnapshot snapshot) {
        CatalogBinding binding = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(),
            snapshot.bindingManifestHash());
        return new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.current());
    }

    private static final ContractRef<CapabilityId> AUTHORING_EDITOR = ContractRef.of(
        OwnerId.of("resync.transport.authoring"), CapabilityId.of("generic-editor"));

    private static final ContractRef<CapabilityId> REPLACEMENT_EDITOR = ContractRef.of(
        OwnerId.of("resync.transport.reloaded"), CapabilityId.of("generic-editor"));

    private static CatalogSnapshot authoringSnapshot() {
        CatalogVersion contract = new CatalogVersion(1, 0);
        CatalogContractRange range = new CatalogContractRange(contract, contract);
        TypeExpr text = TypeExpr.named(TypeReference.of("resync.types", "text"));
        InspectorCapability editor = new InspectorCapability(AUTHORING_EDITOR, "Generic Editor",
            "Provides the generic authoring editor.", new InspectorValueSchema(text),
            new InspectorValueSchema(text), List.of(), AUTHORING_EDITOR, InspectorFallback.GENERIC);
        CatalogContribution contribution = CatalogContribution.builder(AUTHORING_EDITOR.owner(), "1.0.0", range,
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/authoring", "1.0.0",
                    "transport-test", "authoring"))
            .capabilities(List.of(new CatalogCapabilityDescriptor(AUTHORING_EDITOR.id(), 1, false,
                InspectorFallback.GENERIC)))
            .editors(List.of(editor))
            .build();
        return new CatalogCompiler(contract).compile(List.of(contribution), 1).snapshot().orElseThrow();
    }

    private static CatalogSnapshot reloadedAuthoringSnapshot(
        ContractRef<CapabilityId> editorReference,
        RuntimeRegistrySnapshot runtime,
        long generation
    ) {
        CatalogVersion contract = new CatalogVersion(1, 0);
        CatalogContractRange range = new CatalogContractRange(contract, contract);
        TypeExpr text = TypeExpr.named(TypeReference.of("resync.types", "text"));
        InspectorCapability editor = new InspectorCapability(editorReference, "Generic Editor",
            "Provides the generic authoring editor.", new InspectorValueSchema(text),
            new InspectorValueSchema(text), List.of(), editorReference, InspectorFallback.GENERIC);
        CatalogContribution contribution = CatalogContribution.builder(editorReference.owner(), "1.0.0", range,
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/authoring", "1.0.0",
                    "transport-test", "authoring"))
            .capabilities(List.of(new CatalogCapabilityDescriptor(editorReference.id(), 1, false,
                InspectorFallback.GENERIC)))
            .editors(List.of(editor))
            .build();
        return new CatalogCompiler(contract, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(contribution), generation).snapshot().orElseThrow();
    }

    private static CatalogAuthoringPublication awaitAuthoring(CatalogCachePublicationTransport transport) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<CatalogAuthoringPublication> publication = transport.catalogAuthoringPublication();
            if (publication.isPresent()) {
                return publication.orElseThrow();
            }
            Thread.yield();
        }
        return transport.catalogAuthoringPublication().orElseThrow();
    }
}
