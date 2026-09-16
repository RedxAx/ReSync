package restudio.resync.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.storage.CoreGraphAssetCodec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class CoreGraphStorageBoundary {
    public static final String RESOURCE_TYPE = CoreGraphAssetCodec.RESOURCE_TYPE;
    public static final String ASSET_FORMAT_VERSION = CoreGraphAssetCodec.ASSET_FORMAT_VERSION;
    public static final String ASSET_REVISION = CoreGraphAssetCodec.ASSET_REVISION;
    public static final String ASSET_MUTATION_ID = CoreGraphAssetCodec.ASSET_MUTATION_ID;
    public static final String ASSET_ACTIVATION_STATE = CoreGraphAssetCodec.ASSET_ACTIVATION_STATE;
    public static final String ASSET_HASH = CoreGraphAssetCodec.ASSET_HASH;
    public static final String CORE_PAYLOAD_KIND = CoreGraphAssetCodec.CORE_PAYLOAD_KIND;
    public static final String CORE_PAYLOAD_VERSION = CoreGraphAssetCodec.CORE_PAYLOAD_VERSION;
    public static final int LEGACY_ASSET_FORMAT_VERSION = CoreGraphAssetCodec.LEGACY_ASSET_FORMAT_VERSION;
    public static final int CURRENT_ASSET_FORMAT_VERSION = CoreGraphAssetCodec.CURRENT_ASSET_FORMAT_VERSION;
    public static final int CURRENT_CORE_PAYLOAD_VERSION = CoreGraphAssetCodec.CURRENT_CORE_PAYLOAD_VERSION;
    public static final String GRAPH_DOCUMENT_KIND = CoreGraphAssetCodec.GRAPH_DOCUMENT_KIND;
    public static final String FUNCTION_SOURCE_KIND = CoreGraphAssetCodec.FUNCTION_SOURCE_KIND;
    public static final String ASSET_HASH_DOMAIN = CoreGraphAssetCodec.ASSET_HASH_DOMAIN;
    public static final String TOMBSTONE_KIND = CoreGraphAssetCodec.TOMBSTONE_KIND;
    public static final String TOMBSTONE_FORMAT_VERSION = CoreGraphAssetCodec.TOMBSTONE_FORMAT_VERSION;
    public static final String TOMBSTONE_RESOURCE = CoreGraphAssetCodec.TOMBSTONE_RESOURCE;
    public static final String TOMBSTONE_REVISION = CoreGraphAssetCodec.TOMBSTONE_REVISION;
    public static final String TOMBSTONE_MUTATION_ID = CoreGraphAssetCodec.TOMBSTONE_MUTATION_ID;
    public static final String TOMBSTONE_PAYLOAD_HASH = CoreGraphAssetCodec.TOMBSTONE_PAYLOAD_HASH;
    public static final String TOMBSTONE_DELETED = CoreGraphAssetCodec.TOMBSTONE_DELETED;
    public static final String TOMBSTONE_HASH = CoreGraphAssetCodec.TOMBSTONE_HASH;
    public static final int CURRENT_TOMBSTONE_FORMAT_VERSION = CoreGraphAssetCodec.CURRENT_TOMBSTONE_FORMAT_VERSION;
    public static final String TOMBSTONE_HASH_DOMAIN = CoreGraphAssetCodec.TOMBSTONE_HASH_DOMAIN;

    private final CoreGraphAssetCodec codec;

    public CoreGraphStorageBoundary() {
        this.codec = new CoreGraphAssetCodec();
    }

    public CoreGraphStorageBoundary(CanonicalCodec<GraphDocument> graphCodec,
                                    CanonicalCodec<FunctionSourceDocument> functionCodec) {
        this.codec = new CoreGraphAssetCodec(graphCodec, functionCodec);
    }

    public byte[] encode(GraphDocument graph, AssetMetadata metadata) {
        return codec.encode(graph, toCore(metadata));
    }

    public byte[] encode(GraphDocument graph, AssetMetadata metadata, ServerResourceLocator expectedResource) {
        return codec.encode(graph, toCore(metadata), expectedResource);
    }

    public byte[] encode(GraphDocument graph, ServerResourceLocator expectedResource, AssetMetadata metadata) {
        return encode(graph, metadata, expectedResource);
    }

    public byte[] encode(FunctionSourceDocument source, AssetMetadata metadata) {
        return codec.encode(source, toCore(metadata));
    }

    public byte[] encode(FunctionSourceDocument source, AssetMetadata metadata,
                         ServerResourceLocator expectedResource) {
        return codec.encode(source, toCore(metadata), expectedResource);
    }

    public byte[] encode(FunctionSourceDocument source, ServerResourceLocator expectedResource,
                         AssetMetadata metadata) {
        return encode(source, metadata, expectedResource);
    }

    public byte[] encode(GraphDocument graph, String resourceType, long assetRevision, String assetMutationId) {
        return encode(graph, new AssetMetadata(resourceType, assetRevision, assetMutationId));
    }

    public byte[] encode(FunctionSourceDocument source, String resourceType, long assetRevision,
                         String assetMutationId) {
        return encode(source, new AssetMetadata(resourceType, assetRevision, assetMutationId));
    }

    public byte[] encode(Decoded decoded) {
        Decoded checked = Objects.requireNonNull(decoded, "Decoded asset is required");
        CoreGraphAssetCodec.Envelope envelope = toCore(checked.envelope());
        CoreGraphAssetCodec.Asset asset = checked.graphDocument() != null
            ? codec.reconstruct(envelope, checked.graphDocument())
            : codec.reconstruct(envelope, checked.functionSourceDocument());
        return codec.encode(asset);
    }

    public JsonValue.JsonObject encodeValue(GraphDocument graph, AssetMetadata metadata) {
        return codec.encodeValue(graph, toCore(metadata));
    }

    public JsonValue.JsonObject encodeValue(GraphDocument graph, AssetMetadata metadata,
                                            ServerResourceLocator expectedResource) {
        return codec.encodeValue(graph, toCore(metadata), expectedResource);
    }

    public JsonValue.JsonObject encodeValue(FunctionSourceDocument source, AssetMetadata metadata) {
        return codec.encodeValue(source, toCore(metadata));
    }

    public JsonValue.JsonObject encodeValue(FunctionSourceDocument source, AssetMetadata metadata,
                                            ServerResourceLocator expectedResource) {
        return codec.encodeValue(source, toCore(metadata), expectedResource);
    }

    public String encodeText(GraphDocument graph, AssetMetadata metadata) {
        return new String(encode(graph, metadata), StandardCharsets.UTF_8);
    }

    public String encodeText(GraphDocument graph, AssetMetadata metadata,
                             ServerResourceLocator expectedResource) {
        return new String(encode(graph, metadata, expectedResource), StandardCharsets.UTF_8);
    }

    public String encodeText(FunctionSourceDocument source, AssetMetadata metadata) {
        return new String(encode(source, metadata), StandardCharsets.UTF_8);
    }

    public String encodeText(FunctionSourceDocument source, AssetMetadata metadata,
                             ServerResourceLocator expectedResource) {
        return new String(encode(source, metadata, expectedResource), StandardCharsets.UTF_8);
    }

    public Decoded decode(byte[] input) {
        return fromCore(codec.decode(input));
    }

    public Decoded decode(byte[] input, ServerResourceLocator expectedResource) {
        return fromCore(codec.decode(input, expectedResource));
    }

    public Decoded decodeText(String input) {
        return fromCore(codec.decodeText(input));
    }

    public Decoded decodeText(String input, ServerResourceLocator expectedResource) {
        return fromCore(codec.decodeText(input, expectedResource));
    }

    public Decoded decode(JsonValue value) {
        return fromCore(codec.decode(value));
    }

    public Decoded decode(JsonValue value, ServerResourceLocator expectedResource) {
        return fromCore(codec.decode(value, expectedResource));
    }

    public RevisionSkew decodeOneBehindRevision(byte[] input, ServerResourceLocator expectedResource) {
        CoreGraphAssetCodec.RevisionSkew skew = codec.decodeOneBehindRevision(input, expectedResource);
        return new RevisionSkew(fromCore(skew.source()));
    }

    public Decoded repairOneBehindRevision(byte[] input, ServerResourceLocator expectedResource,
                                           AssetMetadata repairedMetadata) {
        return fromCore(codec.repairOneBehindRevision(input, expectedResource, toCore(repairedMetadata)));
    }

    public void verifyRevisionRepair(Decoded repaired, ServerResourceLocator expectedResource,
                                     AssetMetadata sourceMetadata, ContentHash sourceAssetHash) {
        Decoded checked = Objects.requireNonNull(repaired, "Repaired Core asset is required");
        CoreGraphAssetCodec.Envelope envelope = toCore(checked.envelope());
        CoreGraphAssetCodec.Asset asset = checked.graphDocument() != null
            ? codec.reconstruct(envelope, checked.graphDocument())
            : codec.reconstruct(envelope, checked.functionSourceDocument());
        codec.verifyRevisionRepair(asset, expectedResource, toCore(sourceMetadata), sourceAssetHash);
    }

    public GraphDocument decodeGraph(byte[] input) {
        return codec.decodeGraph(input);
    }

    public GraphDocument decodeGraph(byte[] input, ServerResourceLocator expectedResource) {
        return codec.decodeGraph(input, expectedResource);
    }

    public FunctionSourceDocument decodeFunctionSource(byte[] input) {
        return codec.decodeFunctionSource(input);
    }

    public FunctionSourceDocument decodeFunctionSource(byte[] input,
                                                       ServerResourceLocator expectedResource) {
        return codec.decodeFunctionSource(input, expectedResource);
    }

    public ContentHash assetIntegrityHash(JsonValue.JsonObject value) {
        return codec.assetIntegrityHash(value);
    }

    public byte[] encodeTombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                  ContentHash priorPayloadHash) {
        return codec.encodeTombstone(resource, revision, mutationId, priorPayloadHash);
    }

    public byte[] encodeTombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                  String priorPayloadHash) {
        return encodeTombstone(resource, revision, mutationId, new ContentHash(priorPayloadHash));
    }

    public byte[] encodeTombstone(ServerResourceLocator resource, long revision, String mutationId,
                                  ContentHash priorPayloadHash) {
        return encodeTombstone(resource, revision, canonicalMutationId(mutationId), priorPayloadHash);
    }

    public byte[] encodeTombstone(CoreGraphTombstone tombstone) {
        return codec.encodeTombstone(toCore(tombstone));
    }

    public String encodeTombstoneText(ServerResourceLocator resource, long revision, UUID mutationId,
                                      ContentHash priorPayloadHash) {
        return new String(encodeTombstone(resource, revision, mutationId, priorPayloadHash), StandardCharsets.UTF_8);
    }

    public JsonValue.JsonObject encodeTombstoneValue(ServerResourceLocator resource, long revision,
                                                     UUID mutationId, ContentHash priorPayloadHash) {
        return codec.encodeTombstoneValue(new CoreGraphAssetCodec.Tombstone(resource, revision, mutationId,
            priorPayloadHash));
    }

    public JsonValue.JsonObject encodeTombstoneValue(ServerResourceLocator resource, long revision,
                                                     UUID mutationId, String priorPayloadHash) {
        return encodeTombstoneValue(resource, revision, mutationId, new ContentHash(priorPayloadHash));
    }

    public JsonValue.JsonObject encodeTombstoneValue(CoreGraphTombstone tombstone) {
        return codec.encodeTombstoneValue(toCore(tombstone));
    }

    public CoreGraphTombstone decodeTombstone(byte[] input) {
        return fromCore(codec.decodeTombstone(input));
    }

    public CoreGraphTombstone decodeTombstone(byte[] input, ServerResourceLocator expectedResource) {
        return fromCore(codec.decodeTombstone(input, expectedResource));
    }

    public CoreGraphTombstone decodeTombstoneText(String input) {
        return fromCore(codec.decodeTombstone(CanonicalCodec.decode(input)));
    }

    public CoreGraphTombstone decodeTombstoneText(String input,
                                                  ServerResourceLocator expectedResource) {
        return fromCore(codec.decodeTombstone(CanonicalCodec.decode(input), expectedResource));
    }

    public CoreGraphTombstone decodeTombstone(JsonValue value) {
        return fromCore(codec.decodeTombstone(value));
    }

    public CoreGraphTombstone decodeTombstone(JsonValue value,
                                              ServerResourceLocator expectedResource) {
        return fromCore(codec.decodeTombstone(value, expectedResource));
    }

    public ContentHash tombstoneIntegrityHash(JsonValue.JsonObject value) {
        return codec.tombstoneIntegrityHash(value);
    }

    private static CoreGraphAssetCodec.Metadata toCore(AssetMetadata metadata) {
        AssetMetadata checked = Objects.requireNonNull(metadata, "Asset metadata is required");
        return new CoreGraphAssetCodec.Metadata(checked.resourceType(), checked.assetRevision(),
            checked.assetMutationId(), checked.assetActivationState());
    }

    private static CoreGraphAssetCodec.Envelope toCore(AssetEnvelope envelope) {
        AssetEnvelope checked = Objects.requireNonNull(envelope, "Asset envelope is required");
        return new CoreGraphAssetCodec.Envelope(checked.resourceType(), checked.assetFormatVersion(),
            checked.assetRevision(), checked.assetMutationId(), checked.assetActivationState(), checked.assetHash(),
            checked.corePayloadKind(), checked.corePayloadVersion());
    }

    private static CoreGraphAssetCodec.Tombstone toCore(CoreGraphTombstone tombstone) {
        CoreGraphTombstone checked = Objects.requireNonNull(tombstone, "Core graph tombstone is required");
        return new CoreGraphAssetCodec.Tombstone(checked.resource(), checked.revision(), checked.mutationId(),
            checked.priorPayloadHash(), checked.deleted(), checked.tombstoneFormatVersion(), checked.integrityHash());
    }

    private static Decoded fromCore(CoreGraphAssetCodec.Asset asset) {
        AssetEnvelope envelope = fromCore(asset.envelope());
        return new Decoded(envelope, asset.graphDocument(), asset.functionSourceDocument());
    }

    private static AssetEnvelope fromCore(CoreGraphAssetCodec.Envelope envelope) {
        return new AssetEnvelope(envelope.resourceType(), envelope.assetFormatVersion(), envelope.assetRevision(),
            envelope.assetMutationId(), envelope.assetActivationState(), envelope.assetHash(),
            envelope.corePayloadKind(), envelope.corePayloadVersion());
    }

    private static CoreGraphTombstone fromCore(CoreGraphAssetCodec.Tombstone tombstone) {
        return new CoreGraphTombstone(tombstone.resource(), tombstone.revision(), tombstone.mutationId(),
            tombstone.priorPayloadHash(), tombstone.deleted(), tombstone.formatVersion(), tombstone.integrityHash());
    }

    private static UUID canonicalMutationId(String value) {
        Objects.requireNonNull(value, "Asset mutation ID is required");
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Asset mutation ID must be a canonical UUID", exception);
        }
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Asset mutation ID must be a canonical UUID");
        }
        return parsed;
    }

    public record AssetMetadata(String resourceType, long assetRevision, String assetMutationId,
                                ResourceActivationState assetActivationState) {
        public AssetMetadata {
            CoreGraphAssetCodec.Metadata checked = new CoreGraphAssetCodec.Metadata(resourceType, assetRevision,
                assetMutationId, assetActivationState);
            resourceType = checked.resourceType();
            assetRevision = checked.assetRevision();
            assetMutationId = checked.assetMutationId();
            assetActivationState = checked.assetActivationState();
        }

        public AssetMetadata(String resourceType, long assetRevision, String assetMutationId) {
            this(resourceType, assetRevision, assetMutationId, ResourceActivationState.ACTIVE);
        }

        public AssetMetadata(String resourceType, long assetRevision, UUID assetMutationId) {
            this(resourceType, assetRevision, Objects.requireNonNull(assetMutationId,
                "Asset mutation ID is required").toString());
        }

        public AssetMetadata(String resourceType, long assetRevision, UUID assetMutationId,
                             ResourceActivationState assetActivationState) {
            this(resourceType, assetRevision, Objects.requireNonNull(assetMutationId,
                "Asset mutation ID is required").toString(), assetActivationState);
        }
    }

    public record AssetEnvelope(String resourceType, int assetFormatVersion, long assetRevision,
                                String assetMutationId, ResourceActivationState assetActivationState,
                                ContentHash assetHash, String corePayloadKind, int corePayloadVersion) {
        public AssetEnvelope {
            CoreGraphAssetCodec.Envelope checked = new CoreGraphAssetCodec.Envelope(resourceType,
                assetFormatVersion, assetRevision, assetMutationId, assetActivationState, assetHash,
                corePayloadKind, corePayloadVersion);
            resourceType = checked.resourceType();
            assetFormatVersion = checked.assetFormatVersion();
            assetRevision = checked.assetRevision();
            assetMutationId = checked.assetMutationId();
            assetActivationState = checked.assetActivationState();
            assetHash = checked.assetHash();
            corePayloadKind = checked.corePayloadKind();
            corePayloadVersion = checked.corePayloadVersion();
        }

        public AssetEnvelope(String resourceType, int assetFormatVersion, long assetRevision,
                             String assetMutationId, ContentHash assetHash, String corePayloadKind,
                             int corePayloadVersion) {
            this(resourceType, assetFormatVersion, assetRevision, assetMutationId,
                ResourceActivationState.ACTIVE, assetHash, corePayloadKind, corePayloadVersion);
        }

        public AssetMetadata metadata() {
            return new AssetMetadata(resourceType, assetRevision, assetMutationId, assetActivationState);
        }
    }

    public static final class Decoded {
        private final AssetEnvelope envelope;
        private final GraphDocument graphDocument;
        private final FunctionSourceDocument functionSourceDocument;

        private Decoded(AssetEnvelope envelope, GraphDocument graphDocument,
                        FunctionSourceDocument functionSourceDocument) {
            this.envelope = Objects.requireNonNull(envelope, "Asset envelope is required");
            if ((graphDocument == null) == (functionSourceDocument == null)) {
                throw new IllegalArgumentException("Exactly one Core payload is required");
            }
            this.graphDocument = graphDocument;
            this.functionSourceDocument = functionSourceDocument;
        }

        public AssetEnvelope envelope() {
            return envelope;
        }

        public GraphDocument graphDocument() {
            return graphDocument;
        }

        public FunctionSourceDocument functionSourceDocument() {
            return functionSourceDocument;
        }

        public Object payload() {
            return graphDocument != null ? graphDocument : functionSourceDocument;
        }

        public String corePayloadKind() {
            return envelope.corePayloadKind();
        }
    }

    public record RevisionSkew(Decoded source) {
        public RevisionSkew {
            source = Objects.requireNonNull(source, "Source asset is required");
            long payloadRevision = source.graphDocument() != null
                ? source.graphDocument().revision()
                : source.functionSourceDocument().graph().revision();
            if (payloadRevision + 1 != source.envelope().assetRevision()) {
                throw new IllegalArgumentException("Core graph revision skew must be exactly one revision");
            }
        }
    }

    public record CoreGraphTombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                     ContentHash priorPayloadHash, boolean deleted, int tombstoneFormatVersion,
                                     ContentHash integrityHash) {
        public CoreGraphTombstone {
            CoreGraphAssetCodec.Tombstone checked = new CoreGraphAssetCodec.Tombstone(resource, revision,
                mutationId, priorPayloadHash, deleted, tombstoneFormatVersion, integrityHash);
            resource = checked.resource();
            revision = checked.revision();
            mutationId = checked.mutationId();
            priorPayloadHash = checked.priorPayloadHash();
            deleted = checked.deleted();
            tombstoneFormatVersion = checked.formatVersion();
            integrityHash = checked.integrityHash();
        }

        public CoreGraphTombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                  ContentHash priorPayloadHash) {
            this(resource, revision, mutationId, priorPayloadHash, true, CURRENT_TOMBSTONE_FORMAT_VERSION, null);
        }

        public ContentHash payloadHash() {
            return priorPayloadHash;
        }

        public ContentHash priorCorePayloadHash() {
            return priorPayloadHash;
        }

        public int formatVersion() {
            return tombstoneFormatVersion;
        }

        public ContentHash tombstoneHash() {
            return integrityHash;
        }
    }
}
