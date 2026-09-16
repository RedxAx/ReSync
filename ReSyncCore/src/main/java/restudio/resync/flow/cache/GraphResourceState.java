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

public final class GraphResourceState {
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "resource", "revision", "mutationId", "protocolHash", "assetHash", "innerChecksum",
        "catalogBinding", "activationState", "tombstone", "graph", "function");

    private final ServerResourceLocator resource;
    private final long revision;
    private final UUID mutationId;
    private final ContentHash protocolHash;
    private final ContentHash assetHash;
    private final ContentHash innerChecksum;
    private final CatalogBinding catalogBinding;
    private final GraphDocument graphDocument;
    private final FunctionSourceDocument functionSourceDocument;
    private final ResourceActivationState activationState;
    private final boolean tombstone;
    private final Map<String, Object> unknown;

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash,
                              GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument,
                              ResourceActivationState activationState, boolean tombstone,
                              Map<String, ?> unknown) {
        this(resource, revision, mutationId, protocolHash, assetHash, graphDocument, functionSourceDocument,
            activationState, tombstone, null, null, unknown);
    }

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash,
                              GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument,
                              ResourceActivationState activationState, boolean tombstone,
                              ContentHash innerChecksum, CatalogBinding catalogBinding,
                              Map<String, ?> unknown) {
        this.resource = Objects.requireNonNull(resource, "resource");
        if (revision < 0L) {
            throw new IllegalArgumentException("Graph resource revision cannot be negative");
        }
        this.revision = revision;
        this.mutationId = Objects.requireNonNull(mutationId, "mutationId");
        this.protocolHash = Objects.requireNonNull(protocolHash, "protocolHash");
        this.assetHash = assetHash;
        this.tombstone = tombstone;
        this.unknown = unknown(unknown);
        if (tombstone) {
            if (graphDocument != null || functionSourceDocument != null) {
                throw new IllegalArgumentException("Tombstones cannot carry a graph or function payload");
            }
            if (activationState != null) {
                throw new IllegalArgumentException("Tombstones cannot carry an activation state");
            }
            this.graphDocument = null;
            this.functionSourceDocument = null;
            this.activationState = null;
            this.innerChecksum = innerChecksum;
            this.catalogBinding = catalogBinding;
            return;
        }
        if ((graphDocument == null) == (functionSourceDocument == null)) {
            throw new IllegalArgumentException("A live graph resource must carry exactly one payload");
        }
        if (assetHash == null) {
            throw new IllegalArgumentException("Live graph resources require an asset hash");
        }
        this.activationState = Objects.requireNonNull(activationState, "activationState");
        if (graphDocument != null && !resource.equals(graphDocument.resource())) {
            throw new IllegalArgumentException("Graph resource locator does not match the state locator");
        }
        if (functionSourceDocument != null && !resource.equals(functionSourceDocument.graph().resource())) {
            throw new IllegalArgumentException("Function resource locator does not match the state locator");
        }
        ContentHash derivedChecksum = graphDocument != null ? graphDocument.checksum() : functionSourceDocument.checksum();
        CatalogBinding derivedBinding = graphDocument != null
            ? graphDocument.catalogBinding()
            : functionSourceDocument.graph().catalogBinding();
        if (innerChecksum != null && !innerChecksum.equals(derivedChecksum)) {
            throw new IllegalArgumentException("Inner graph resource checksum does not match the payload");
        }
        if (catalogBinding != null && !catalogBinding.equals(derivedBinding)) {
            throw new IllegalArgumentException("Graph resource catalog binding does not match the payload");
        }
        this.graphDocument = graphDocument;
        this.functionSourceDocument = functionSourceDocument;
        this.innerChecksum = derivedChecksum;
        this.catalogBinding = derivedBinding;
    }

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash, GraphDocument graphDocument,
                              ResourceActivationState activationState) {
        this(resource, revision, mutationId, protocolHash, assetHash, graphDocument, null,
            activationState, false, Map.of());
    }

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash, GraphDocument graphDocument,
                              ResourceActivationState activationState, Map<String, ?> unknown) {
        this(resource, revision, mutationId, protocolHash, assetHash, graphDocument, null,
            activationState, false, unknown);
    }

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash,
                              FunctionSourceDocument functionSourceDocument,
                              ResourceActivationState activationState) {
        this(resource, revision, mutationId, protocolHash, assetHash, null, functionSourceDocument,
            activationState, false, Map.of());
    }

    public GraphResourceState(ServerResourceLocator resource, long revision, UUID mutationId,
                              ContentHash protocolHash, ContentHash assetHash,
                              FunctionSourceDocument functionSourceDocument,
                              ResourceActivationState activationState, Map<String, ?> unknown) {
        this(resource, revision, mutationId, protocolHash, assetHash, null, functionSourceDocument,
            activationState, false, unknown);
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          ContentHash protocolHash, ContentHash assetHash,
                                          GraphDocument graphDocument, ResourceActivationState activationState) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, graphDocument, null,
            activationState, false, Map.of());
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          GraphDocument graphDocument, ContentHash protocolHash,
                                          ContentHash assetHash, ResourceActivationState activationState) {
        return live(resource, revision, mutationId, protocolHash, assetHash, graphDocument, activationState);
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          ContentHash protocolHash, ContentHash assetHash,
                                          GraphDocument graphDocument, ResourceActivationState activationState,
                                          Map<String, ?> unknown) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, graphDocument, null,
            activationState, false, unknown);
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          ContentHash protocolHash, ContentHash assetHash,
                                          FunctionSourceDocument functionSourceDocument,
                                          ResourceActivationState activationState) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, null,
            functionSourceDocument, activationState, false, Map.of());
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          FunctionSourceDocument functionSourceDocument, ContentHash protocolHash,
                                          ContentHash assetHash, ResourceActivationState activationState) {
        return live(resource, revision, mutationId, protocolHash, assetHash, functionSourceDocument,
            activationState);
    }

    public static GraphResourceState live(ServerResourceLocator resource, long revision, UUID mutationId,
                                          ContentHash protocolHash, ContentHash assetHash,
                                          FunctionSourceDocument functionSourceDocument,
                                          ResourceActivationState activationState, Map<String, ?> unknown) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, null,
            functionSourceDocument, activationState, false, unknown);
    }

    public static GraphResourceState tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                               ContentHash protocolHash) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, null, null, null,
            null, true, Map.of());
    }

    public static GraphResourceState tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                               ContentHash protocolHash, Map<String, ?> unknown) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, null, null, null,
            null, true, unknown);
    }

    public static GraphResourceState tombstoned(ServerResourceLocator resource, long revision, UUID mutationId,
                                                ContentHash protocolHash) {
        return tombstone(resource, revision, mutationId, protocolHash);
    }

    public static GraphResourceState tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                               ContentHash protocolHash, ContentHash assetHash) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, null, null,
            null, true, Map.of());
    }

    public static GraphResourceState tombstoned(ServerResourceLocator resource, long revision, UUID mutationId,
                                                ContentHash protocolHash, ContentHash assetHash) {
        return tombstone(resource, revision, mutationId, protocolHash, assetHash);
    }

    public static GraphResourceState tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                               ContentHash protocolHash, ContentHash assetHash,
                                               Map<String, ?> unknown) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, null, null,
            null, true, unknown);
    }

    public static GraphResourceState tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                               ContentHash protocolHash, ContentHash assetHash,
                                               ContentHash innerChecksum, CatalogBinding catalogBinding,
                                               Map<String, ?> unknown) {
        return new GraphResourceState(resource, revision, mutationId, protocolHash, assetHash, null, null,
            null, true, innerChecksum, catalogBinding, unknown);
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public long revision() {
        return revision;
    }

    public UUID mutationId() {
        return mutationId;
    }

    public ContentHash protocolHash() {
        return protocolHash;
    }

    public ContentHash payloadHash() {
        return protocolHash;
    }

    public ContentHash payloadChecksum() {
        return protocolHash;
    }

    public ContentHash protocolPayloadHash() {
        return protocolHash;
    }

    public ContentHash assetHash() {
        return assetHash;
    }

    public ContentHash innerChecksum() {
        return innerChecksum;
    }

    public ContentHash corePayloadChecksum() {
        return innerChecksum;
    }

    public String corePayloadKind() {
        if (graphDocument != null) {
            return "graph-document";
        }
        return functionSourceDocument == null ? null : "function-source";
    }

    public CatalogBinding catalogBinding() {
        return catalogBinding;
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

    public ResourceActivationState assetActivationState() {
        return activationState;
    }

    public boolean tombstone() {
        return tombstone;
    }

    public boolean deleted() {
        return tombstone;
    }

    public boolean live() {
        return !tombstone;
    }

    public boolean present() {
        return !tombstone;
    }

    public boolean isTombstone() {
        return tombstone;
    }

    public Kind kind() {
        return tombstone ? Kind.TOMBSTONED : Kind.LIVE;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> known = new LinkedHashMap<>();
        known.put("resource", resource.canonicalValue());
        known.put("revision", revision);
        known.put("mutationId", mutationId.toString());
        known.put("protocolHash", protocolHash.canonicalText());
        if (assetHash != null) {
            known.put("assetHash", assetHash.canonicalText());
        }
        if (innerChecksum != null) {
            known.put("innerChecksum", innerChecksum.canonicalText());
        }
        if (catalogBinding != null) {
            known.put("catalogBinding", Map.of("generation", catalogBinding.generation(),
                "catalogChecksum", catalogBinding.catalogChecksum().canonicalText(),
                "bindingManifestHash", catalogBinding.bindingManifestHash().canonicalText()));
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
        if (!(other instanceof GraphResourceState state)) {
            return false;
        }
        return revision == state.revision && tombstone == state.tombstone
            && resource.equals(state.resource) && mutationId.equals(state.mutationId)
            && protocolHash.equals(state.protocolHash) && Objects.equals(assetHash, state.assetHash)
            && Objects.equals(innerChecksum, state.innerChecksum)
            && Objects.equals(catalogBinding, state.catalogBinding)
            && payloadEquals(state)
            && activationState == state.activationState && unknown.equals(state.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resource, revision, mutationId, protocolHash, assetHash, innerChecksum,
            catalogBinding, payloadCanonicalText(), activationState, tombstone, unknown);
    }

    @Override
    public String toString() {
        return "GraphResourceState[resource=" + resource.canonicalText() + ", revision=" + revision
            + ", tombstone=" + tombstone + "]";
    }

    public enum Kind {
        LIVE,
        TOMBSTONED
    }

    private static Map<String, Object> unknown(Map<String, ?> source) {
        Map<String, Object> copy = IdentitySupport.unknown(source, "graph resource state unknown data");
        for (String key : copy.keySet()) {
            if (KNOWN_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Graph resource state unknown data collides with known field: " + key);
            }
        }
        return copy;
    }

    private boolean payloadEquals(GraphResourceState other) {
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
}
