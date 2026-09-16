package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphResourceCacheTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.core");
    private static final ContractRef<ResourceTypeId> FLOW_TYPE = ContractRef.of(OWNER, ResourceTypeId.of("flow"));
    private static final ContentHash PROTOCOL_A = new ContentHash("a".repeat(64));
    private static final ContentHash PROTOCOL_B = new ContentHash("b".repeat(64));
    private static final ContentHash ASSET_A = new ContentHash("c".repeat(64));
    private static final ContentHash ASSET_B = new ContentHash("d".repeat(64));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("e".repeat(64)),
        new ContentHash("f".repeat(64)));

    @Test
    void statePreservesProtocolAssetAndInnerIdentitySeparately() {
        ServerResourceLocator resource = resource("alpha");
        GraphDocument graph = graph(resource, 4);
        UUID mutation = UUID.randomUUID();
        GraphResourceState state = GraphResourceState.live(resource, 4, mutation, PROTOCOL_A, ASSET_A, graph,
            ResourceActivationState.INACTIVE, Map.of("future", Map.of("value", 7)));

        assertEquals(PROTOCOL_A, state.protocolHash());
        assertEquals(ASSET_A, state.assetHash());
        assertEquals(graph.checksum(), state.innerChecksum());
        assertEquals(BINDING, state.catalogBinding());
        assertEquals(ResourceActivationState.INACTIVE, state.activationState());
        assertEquals(Map.of("future", Map.of("value", 7)), state.unknown());
        assertTrue(state.live());
        assertFalse(state.tombstone());
    }

    @Test
    void payloadFormsAndTombstonesAreExclusive() {
        ServerResourceLocator resource = resource("alpha");
        GraphDocument graph = graph(resource, 4);
        UUID mutation = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new GraphResourceState(resource, 4, mutation,
            PROTOCOL_A, ASSET_A, graph, graphFunction(functionResource("alpha"), 4),
            ResourceActivationState.ACTIVE, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GraphResourceState(resource, 4, mutation,
            PROTOCOL_A, ASSET_A, null, null, ResourceActivationState.ACTIVE, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GraphResourceState(resource, 4, mutation,
            PROTOCOL_A, ASSET_A, graph, null, null, true, Map.of()));

        GraphResourceState tombstone = GraphResourceState.tombstone(resource, 5, mutation, PROTOCOL_B, ASSET_B,
            graph.checksum(), BINDING, Map.of("future", true));
        assertTrue(tombstone.tombstone());
        assertNull(tombstone.payload());
        assertNull(tombstone.activationState());
        assertEquals(graph.checksum(), tombstone.innerChecksum());
        assertEquals(BINDING, tombstone.catalogBinding());
    }

    @Test
    void cacheReconcilesOrderingAndRetainsTombstones() {
        ServerResourceLocator resource = resource("alpha");
        GraphResourceState first = state(resource, 4, UUID.randomUUID(), graph(resource, 4), PROTOCOL_A, ASSET_A);
        GraphResourceCache cache = new GraphResourceCache();

        assertEquals(GraphResourceCache.Status.NEW, cache.reconcile(first).status());
        assertEquals(GraphResourceCache.Status.DUPLICATE, cache.reconcile(
            state(resource, 4, first.mutationId(), graph(resource, 4), PROTOCOL_A, ASSET_A)).status());
        assertEquals(GraphResourceCache.Status.STALE, cache.reconcile(
            state(resource, 3, UUID.randomUUID(), graph(resource, 3), PROTOCOL_B, ASSET_B)).status());
        assertEquals(GraphResourceCache.Status.CONFLICT, cache.reconcile(
            state(resource, 4, UUID.randomUUID(), graph(resource, 4, "different"), PROTOCOL_B, ASSET_B)).status());

        GraphResourceState newer = state(resource, 5, UUID.randomUUID(), graph(resource, 5), PROTOCOL_B, ASSET_B);
        assertEquals(GraphResourceCache.Status.NEWER, cache.reconcile(newer).status());

        GraphResourceState tombstone = GraphResourceState.tombstone(resource, 6, UUID.randomUUID(), PROTOCOL_A, ASSET_A);
        assertEquals(GraphResourceCache.Status.NEWER, cache.reconcile(tombstone).status());
        assertTrue(cache.state(resource).orElseThrow().tombstone());
        assertEquals(GraphResourceCache.Status.STALE, cache.reconcile(newer).status());
    }

    @Test
    void matchingMutationAcknowledgementClearsOnlyItsDraft() {
        ServerResourceLocator alpha = resource("alpha");
        ServerResourceLocator beta = resource("beta");
        UUID alphaMutation = UUID.randomUUID();
        UUID betaMutation = UUID.randomUUID();
        GraphResourceState alphaState = state(alpha, 1, UUID.randomUUID(), graph(alpha, 1), PROTOCOL_A, ASSET_A);
        GraphResourceState betaState = state(beta, 1, UUID.randomUUID(), graph(beta, 1), PROTOCOL_A, ASSET_A);
        GraphResourceCache cache = new GraphResourceCache();
        cache.putAuthoritative(alphaState);
        cache.putAuthoritative(betaState);
        cache.putDraft(GraphDraft.from(alphaState).withMutationId(alphaMutation));
        cache.putDraft(GraphDraft.from(betaState).withMutationId(betaMutation));

        GraphResourceState alphaAck = state(alpha, 2, alphaMutation, graph(alpha, 2), PROTOCOL_B, ASSET_B);
        assertEquals(GraphResourceCache.Status.ACKNOWLEDGED, cache.reconcile(alphaAck).status());
        assertTrue(cache.draft(alpha).isEmpty());
        assertTrue(cache.draft(beta).isPresent());
        assertEquals(alphaAck, cache.state(alpha).orElseThrow());
    }

    @Test
    void immutableMapsUseCompleteLocatorAndDuplicatePayloadsCompareCanonically() {
        ServerResourceLocator alpha = resource("alpha");
        GraphResourceState first = state(alpha, 1, UUID.randomUUID(), graph(alpha, 1), PROTOCOL_A, ASSET_A);
        GraphResourceCache cache = new GraphResourceCache();
        cache.putAuthoritative(first);

        assertThrows(UnsupportedOperationException.class, () -> cache.authoritative().clear());
        assertEquals(GraphResourceCache.Status.DUPLICATE, cache.reconcile(
            state(alpha, 1, first.mutationId(), graph(alpha, 1), PROTOCOL_A, ASSET_A)).status());
        assertEquals(first, cache.state(alpha).orElseThrow());
    }

    private static GraphResourceState state(ServerResourceLocator resource, long revision, UUID mutation,
                                            GraphDocument graph, ContentHash protocolHash, ContentHash assetHash) {
        return GraphResourceState.live(resource, revision, mutation, protocolHash, assetHash, graph,
            ResourceActivationState.ACTIVE);
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision) {
        return graph(resource, revision, "value");
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision, String value) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            restudio.resync.flow.graph.OpaqueData.of(Map.of("value", value)));
    }

    private static restudio.resync.flow.function.FunctionSourceDocument graphFunction(ServerResourceLocator resource,
                                                                                        long revision) {
        return new restudio.resync.flow.function.FunctionSourceDocument(
            new restudio.resync.flow.function.FunctionSignature(
                new restudio.resync.flow.function.FunctionLocator(resource),
                new restudio.resync.flow.function.FunctionRevision(revision), java.util.List.of(), java.util.List.of()),
            graph(resource, revision));
    }

    private static ServerResourceLocator functionResource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, new ResourceTypeId("function")), id);
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, FLOW_TYPE, id);
    }
}
