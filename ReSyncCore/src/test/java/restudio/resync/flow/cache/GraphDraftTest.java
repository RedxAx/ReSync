package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphDraftTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.core");
    private static final ContractRef<ResourceTypeId> FLOW_TYPE = ContractRef.of(OWNER, ResourceTypeId.of("flow"));
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = ContractRef.of(OWNER, ResourceTypeId.of("function"));
    private static final ContentHash PROTOCOL = new ContentHash("a".repeat(64));
    private static final ContentHash ASSET = new ContentHash("b".repeat(64));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("c".repeat(64)),
        new ContentHash("d".repeat(64)));
    private static final UUID DRAFT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MUTATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void graphPayloadEqualityUsesCanonicalOrderingAndImmutableUnknownData() {
        ServerResourceLocator resource = resource(FLOW_TYPE, "alpha");
        GraphDraft first = graphDraft(resource, graph(resource, false, "same"), Map.of("future", Map.of("keep", true)));
        GraphDraft reordered = graphDraft(resource, graph(resource, true, "same"), Map.of("future", Map.of("keep", true)));

        assertEquals(first, reordered);
        assertEquals(first.hashCode(), reordered.hashCode());
        assertEquals(first.canonicalJson(), reordered.canonicalJson());
        assertThrows(UnsupportedOperationException.class, () -> first.unknown().put("new", true));
    }

    @Test
    void equalityRejectsDifferentPayloadLocatorRevisionAndPendingMutation() {
        ServerResourceLocator resource = resource(FLOW_TYPE, "alpha");
        GraphDraft first = graphDraft(resource, graph(resource, false, "same"), Map.of());

        assertNotEquals(first, graphDraft(resource, graph(resource, false, "different"), Map.of()));
        assertNotEquals(first, graphDraft(resource(FLOW_TYPE, "beta"),
            graph(resource(FLOW_TYPE, "beta"), false, "same"), Map.of()));
        assertNotEquals(first, first.withBaseRevision(first.baseRevision() + 1));
        assertNotEquals(first, first.withMutationId(UUID.fromString("44444444-4444-4444-8444-444444444444")));
    }

    @Test
    void functionPayloadEqualityUsesCanonicalContent() {
        ServerResourceLocator resource = resource(FUNCTION_TYPE, "alpha");
        GraphDraft first = functionDraft(resource, function(resource, false));
        GraphDraft reordered = functionDraft(resource, function(resource, true));

        assertEquals(first, reordered);
        assertEquals(first.hashCode(), reordered.hashCode());
        assertNotEquals(first, functionDraft(resource, function(resource, false, "different")));
    }

    private static GraphDraft graphDraft(ServerResourceLocator resource, GraphDocument graph,
                                         Map<String, ?> unknown) {
        return new GraphDraft(DRAFT_ID, resource, graph.revision(), MUTATION_ID, PROTOCOL, ASSET, graph, null,
            ResourceActivationState.ACTIVE, false, unknown);
    }

    private static GraphDraft functionDraft(ServerResourceLocator resource, FunctionSourceDocument function) {
        return new GraphDraft(DRAFT_ID, resource, function.graph().revision(), MUTATION_ID, PROTOCOL, ASSET, null,
            function, ResourceActivationState.ACTIVE, false, Map.of());
    }

    private static GraphDocument graph(ServerResourceLocator resource, boolean reverse, String marker) {
        GraphNode first = new GraphNode(NodeInstanceId.of(UUID.fromString("55555555-5555-4555-8555-555555555555")),
            ContractRef.of(OWNER, NodeId.of("first")), 1, Map.of());
        GraphNode second = new GraphNode(NodeInstanceId.of(UUID.fromString("66666666-6666-4666-8666-666666666666")),
            ContractRef.of(OWNER, NodeId.of("second")), 1, Map.of());
        List<GraphNode> nodes = reverse ? List.of(second, first) : List.of(first, second);
        return new GraphDocument(new CatalogVersion(1, 0), resource, 4, BINDING, Set.of(), nodes, List.of(),
            List.of(), OpaqueData.of(Map.of("marker", marker)));
    }

    private static FunctionSourceDocument function(ServerResourceLocator resource, boolean reverse) {
        return function(resource, reverse, "same");
    }

    private static FunctionSourceDocument function(ServerResourceLocator resource, boolean reverse, String marker) {
        GraphDocument graph = graph(resource, reverse, marker);
        return new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(resource),
            new restudio.resync.flow.function.FunctionRevision(graph.revision()), List.of(), List.of()), graph);
    }

    private static ServerResourceLocator resource(ContractRef<ResourceTypeId> type, String id) {
        return new ServerResourceLocator(SERVER, type, id);
    }
}
