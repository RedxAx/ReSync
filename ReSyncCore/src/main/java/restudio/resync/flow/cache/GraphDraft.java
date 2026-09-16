package restudio.resync.flow.cache;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GraphDraft {
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "draftId", "resource", "baseRevision", "mutationId", "protocolHash", "assetHash",
        "activationState", "tombstone", "graph", "function");

    private final UUID draftId;
    private final ServerResourceLocator resource;
    private final long baseRevision;
    private final UUID mutationId;
    private final ContentHash protocolHash;
    private final ContentHash assetHash;
    private final GraphDocument graphDocument;
    private final FunctionSourceDocument functionSourceDocument;
    private final ResourceActivationState activationState;
    private final boolean tombstone;
    private final Map<String, Object> unknown;

    public GraphDraft(UUID draftId, ServerResourceLocator resource, long baseRevision, UUID mutationId,
                      ContentHash protocolHash, ContentHash assetHash,
                      GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument,
                      ResourceActivationState activationState, boolean tombstone, Map<String, ?> unknown) {
        this.draftId = Objects.requireNonNull(draftId, "draftId");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (baseRevision < 0L) {
            throw new IllegalArgumentException("Graph draft base revision cannot be negative");
        }
        this.baseRevision = baseRevision;
        this.mutationId = mutationId;
        this.protocolHash = protocolHash;
        this.assetHash = assetHash;
        this.tombstone = tombstone;
        this.unknown = unknown(unknown);
        if (tombstone) {
            if (graphDocument != null || functionSourceDocument != null) {
                throw new IllegalArgumentException("Deleted graph drafts cannot carry a payload");
            }
            if (activationState != null) {
                throw new IllegalArgumentException("Deleted graph drafts cannot carry an activation state");
            }
            this.graphDocument = null;
            this.functionSourceDocument = null;
            this.activationState = null;
            return;
        }
        if ((graphDocument == null) == (functionSourceDocument == null)) {
            throw new IllegalArgumentException("A live graph draft must carry exactly one payload");
        }
        this.activationState = Objects.requireNonNull(activationState, "activationState");
        if (graphDocument != null && !resource.equals(graphDocument.resource())) {
            throw new IllegalArgumentException("Graph draft locator does not match the graph payload");
        }
        if (functionSourceDocument != null && !resource.equals(functionSourceDocument.graph().resource())) {
            throw new IllegalArgumentException("Graph draft locator does not match the function payload");
        }
        this.graphDocument = graphDocument;
        this.functionSourceDocument = functionSourceDocument;
    }

    public GraphDraft(UUID draftId, ServerResourceLocator resource, long baseRevision,
                      GraphDocument graphDocument) {
        this(draftId, resource, baseRevision, null, null, null, graphDocument, null,
            ResourceActivationState.ACTIVE, false, Map.of());
    }

    public GraphDraft(UUID draftId, ServerResourceLocator resource, long baseRevision,
                      FunctionSourceDocument functionSourceDocument) {
        this(draftId, resource, baseRevision, null, null, null, null, functionSourceDocument,
            ResourceActivationState.ACTIVE, false, Map.of());
    }

    public GraphDraft(ServerResourceLocator resource, long baseRevision, GraphDocument graphDocument) {
        this(UUID.randomUUID(), resource, baseRevision, graphDocument);
    }

    public GraphDraft(ServerResourceLocator resource, long baseRevision,
                      FunctionSourceDocument functionSourceDocument) {
        this(UUID.randomUUID(), resource, baseRevision, functionSourceDocument);
    }

    public GraphDraft(ServerResourceLocator resource, long baseRevision, GraphDocument graphDocument,
                      Map<String, ?> unknown) {
        this(UUID.randomUUID(), resource, baseRevision, null, null, null, graphDocument, null,
            ResourceActivationState.ACTIVE, false, unknown);
    }

    public GraphDraft(ServerResourceLocator resource, long baseRevision,
                      FunctionSourceDocument functionSourceDocument, Map<String, ?> unknown) {
        this(UUID.randomUUID(), resource, baseRevision, null, null, null, null, functionSourceDocument,
            ResourceActivationState.ACTIVE, false, unknown);
    }

    public static GraphDraft from(GraphResourceState state) {
        return from(state, UUID.randomUUID());
    }

    public static GraphDraft from(GraphResourceState state, UUID draftId) {
        Objects.requireNonNull(state, "state");
        return new GraphDraft(draftId, state.resource(), state.revision(), null, state.protocolHash(),
            state.assetHash(), state.graphDocument(), state.functionSourceDocument(), state.activationState(),
            state.tombstone(), state.unknown());
    }

    public static GraphDraft fromState(GraphResourceState state) {
        return from(state);
    }

    public static GraphDraft fromState(GraphResourceState state, UUID draftId) {
        return from(state, draftId);
    }

    public static GraphDraft live(UUID draftId, ServerResourceLocator resource, long baseRevision,
                                  GraphDocument graphDocument) {
        return new GraphDraft(draftId, resource, baseRevision, graphDocument);
    }

    public static GraphDraft live(UUID draftId, ServerResourceLocator resource, long baseRevision,
                                  FunctionSourceDocument functionSourceDocument) {
        return new GraphDraft(draftId, resource, baseRevision, functionSourceDocument);
    }

    public static GraphDraft tombstone(UUID draftId, ServerResourceLocator resource, long baseRevision) {
        return new GraphDraft(draftId, resource, baseRevision, null, null, null, null, null,
            null, true, Map.of());
    }

    public UUID draftId() {
        return draftId;
    }

    public UUID id() {
        return draftId;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public long baseRevision() {
        return baseRevision;
    }

    public long revision() {
        return baseRevision;
    }

    public long expectedRevision() {
        return baseRevision;
    }

    public UUID mutationId() {
        return mutationId;
    }

    public UUID pendingMutationId() {
        return mutationId;
    }

    public ContentHash protocolHash() {
        return protocolHash;
    }

    public ContentHash payloadHash() {
        return protocolHash;
    }

    public ContentHash assetHash() {
        return assetHash;
    }

    public ContentHash innerChecksum() {
        if (graphDocument != null) {
            return graphDocument.checksum();
        }
        return functionSourceDocument == null ? null : functionSourceDocument.checksum();
    }

    public ContentHash corePayloadChecksum() {
        return innerChecksum();
    }

    public CatalogBinding catalogBinding() {
        if (graphDocument != null) {
            return graphDocument.catalogBinding();
        }
        return functionSourceDocument == null ? null : functionSourceDocument.graph().catalogBinding();
    }

    public GraphDocument graphDocument() {
        return graphDocument;
    }

    public GraphDocument graph() {
        return graphDocument;
    }

    public FunctionSourceDocument functionSourceDocument() {
        return functionSourceDocument;
    }

    public FunctionSourceDocument function() {
        return functionSourceDocument;
    }

    public Object payload() {
        return graphDocument != null ? graphDocument : functionSourceDocument;
    }

    public ResourceActivationState activationState() {
        return activationState;
    }

    public boolean tombstone() {
        return tombstone;
    }

    public boolean deleted() {
        return tombstone;
    }

    public boolean isTombstone() {
        return tombstone;
    }

    public boolean live() {
        return !tombstone;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public boolean matchesMutation(UUID candidate) {
        return mutationId != null && mutationId.equals(candidate);
    }

    public boolean matches(UUID candidate) {
        return matchesMutation(candidate);
    }

    public GraphDraft withMutationId(UUID value) {
        return copy(value, protocolHash, assetHash, baseRevision, graphDocument, functionSourceDocument,
            activationState, tombstone, unknown);
    }

    public GraphDraft withSubmission(UUID value, ContentHash submissionProtocolHash,
                                     ContentHash submissionAssetHash) {
        return copy(value, submissionProtocolHash, submissionAssetHash, baseRevision, graphDocument,
            functionSourceDocument, activationState, tombstone, unknown);
    }

    public GraphDraft withBaseRevision(long value) {
        return copy(mutationId, protocolHash, assetHash, value, graphDocument, functionSourceDocument,
            activationState, tombstone, unknown);
    }

    public GraphDraft withGraph(GraphDocument value) {
        Objects.requireNonNull(value, "graphDocument");
        return copy(mutationId, protocolHash, assetHash, baseRevision, value, null,
            activationState == null ? ResourceActivationState.ACTIVE : activationState, false, unknown);
    }

    public GraphDraft withFunction(FunctionSourceDocument value) {
        Objects.requireNonNull(value, "functionSourceDocument");
        return copy(mutationId, protocolHash, assetHash, baseRevision, null, value,
            activationState == null ? ResourceActivationState.ACTIVE : activationState, false, unknown);
    }

    public GraphDraft withActivationState(ResourceActivationState value) {
        if (tombstone) {
            throw new IllegalStateException("Deleted graph drafts cannot be activated");
        }
        return copy(mutationId, protocolHash, assetHash, baseRevision, graphDocument, functionSourceDocument,
            value, false, unknown);
    }

    public GraphResourceState toState(long revision, UUID mutation, ContentHash stateProtocolHash,
                                      ContentHash stateAssetHash) {
        Objects.requireNonNull(mutation, "mutation");
        if (tombstone) {
            return GraphResourceState.tombstone(resource, revision, mutation, stateProtocolHash, stateAssetHash,
                unknown);
        }
        if (graphDocument != null) {
            return GraphResourceState.live(resource, revision, mutation, stateProtocolHash, stateAssetHash,
                graphDocument, activationState, unknown);
        }
        return GraphResourceState.live(resource, revision, mutation, stateProtocolHash, stateAssetHash,
            functionSourceDocument, activationState, unknown);
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> known = new LinkedHashMap<>();
        known.put("draftId", draftId.toString());
        known.put("resource", resource.canonicalValue());
        known.put("baseRevision", baseRevision);
        if (mutationId != null) {
            known.put("mutationId", mutationId.toString());
        }
        if (protocolHash != null) {
            known.put("protocolHash", protocolHash.canonicalText());
        }
        if (assetHash != null) {
            known.put("assetHash", assetHash.canonicalText());
        }
        known.put("tombstone", tombstone);
        if (activationState != null) {
            known.put("activationState", activationState.wireName());
        }
        if (graphDocument != null) {
            known.put("graph", CanonicalJson.parse(graphDocument.canonicalJson()));
        }
        if (functionSourceDocument != null) {
            known.put("function", CanonicalJson.parse(functionSourceDocument.canonicalJson()));
        }
        return IdentitySupport.merge(unknown, known);
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GraphDraft draft)) {
            return false;
        }
        return baseRevision == draft.baseRevision && tombstone == draft.tombstone
            && draftId.equals(draft.draftId) && resource.equals(draft.resource)
            && Objects.equals(mutationId, draft.mutationId) && Objects.equals(protocolHash, draft.protocolHash)
            && Objects.equals(assetHash, draft.assetHash) && payloadEquals(draft)
            && activationState == draft.activationState && unknown.equals(draft.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(draftId, resource, baseRevision, mutationId, protocolHash, assetHash,
            payloadCanonicalText(), activationState, tombstone, unknown);
    }

    @Override
    public String toString() {
        return "GraphDraft[draftId=" + draftId + ", resource=" + resource.canonicalText()
            + ", baseRevision=" + baseRevision + "]";
    }

    private GraphDraft copy(UUID valueMutationId, ContentHash valueProtocolHash, ContentHash valueAssetHash,
                            long valueBaseRevision, GraphDocument valueGraph,
                            FunctionSourceDocument valueFunction, ResourceActivationState valueActivationState,
                            boolean valueTombstone, Map<String, ?> valueUnknown) {
        return new GraphDraft(draftId, resource, valueBaseRevision, valueMutationId, valueProtocolHash,
            valueAssetHash, valueGraph, valueFunction, valueActivationState, valueTombstone, valueUnknown);
    }

    private boolean payloadEquals(GraphDraft other) {
        if (graphDocument != null || other.graphDocument != null) {
            return graphDocument != null && other.graphDocument != null
                && graphDocument.canonicalJson().equals(other.graphDocument.canonicalJson());
        }
        if (functionSourceDocument != null || other.functionSourceDocument != null) {
            return functionSourceDocument != null && other.functionSourceDocument != null
                && functionSourceDocument.canonicalJson().equals(other.functionSourceDocument.canonicalJson());
        }
        return true;
    }

    private String payloadCanonicalText() {
        if (graphDocument != null) {
            return graphDocument.canonicalJson();
        }
        return functionSourceDocument == null ? null : functionSourceDocument.canonicalJson();
    }

    private static Map<String, Object> unknown(Map<String, ?> source) {
        Map<String, Object> copy = IdentitySupport.unknown(source, "graph draft unknown data");
        for (String key : copy.keySet()) {
            if (KNOWN_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Graph draft unknown data collides with known field: " + key);
            }
        }
        return copy;
    }
}
