package restudio.resync.flow.storage;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalUuids;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphAssetCodec {
    public static final String RESOURCE_TYPE = "resourceType";
    public static final String ASSET_FORMAT_VERSION = "assetFormatVersion";
    public static final String ASSET_REVISION = "assetRevision";
    public static final String ASSET_MUTATION_ID = "assetMutationId";
    public static final String ASSET_ACTIVATION_STATE = "assetActivationState";
    public static final String ASSET_HASH = "assetHash";
    public static final String CORE_PAYLOAD_KIND = "corePayloadKind";
    public static final String CORE_PAYLOAD_VERSION = "corePayloadVersion";
    public static final int LEGACY_ASSET_FORMAT_VERSION = 3;
    public static final int CURRENT_ASSET_FORMAT_VERSION = 4;
    public static final int CURRENT_CORE_PAYLOAD_VERSION = 1;
    public static final String GRAPH_DOCUMENT_KIND = "graph-document";
    public static final String FUNCTION_SOURCE_KIND = "function-source";
    public static final String ASSET_HASH_DOMAIN = "asset-envelope";
    public static final String TOMBSTONE_KIND = "core-graph-tombstone";
    public static final String TOMBSTONE_FORMAT_VERSION = "formatVersion";
    public static final String TOMBSTONE_RESOURCE = "resource";
    public static final String TOMBSTONE_REVISION = "revision";
    public static final String TOMBSTONE_MUTATION_ID = "mutationId";
    public static final String TOMBSTONE_PAYLOAD_HASH = "payloadHash";
    public static final String TOMBSTONE_DELETED = "deleted";
    public static final String TOMBSTONE_HASH = "tombstoneHash";
    public static final int CURRENT_TOMBSTONE_FORMAT_VERSION = 1;
    public static final String TOMBSTONE_HASH_DOMAIN = "core-graph-tombstone";

    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "command", "function", "custom_content");
    private static final Set<String> GRAPH_DOCUMENT_TYPES = Set.of("flow", "command", "custom_content");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        RESOURCE_TYPE, ASSET_FORMAT_VERSION, ASSET_REVISION, ASSET_MUTATION_ID, ASSET_ACTIVATION_STATE, ASSET_HASH,
        CORE_PAYLOAD_KIND, CORE_PAYLOAD_VERSION);
    private static final Set<String> LEGACY_ENVELOPE_FIELDS = Set.of(
        "contentHash", "formatVersion", "hash", "mutationId", "payloadHash", "payloadKind", "payloadVersion",
        "resourceHash", "resourceMutationId", "resourceRevision", "type", "id");
    private static final Set<String> GRAPH_ROOT_FIELDS = Set.of(
        "schemaVersion", "resource", "revision", "catalogBinding", "requiredCapabilities", "nodes", "connections",
        "variables", "functions");
    private static final Set<String> FUNCTION_ROOT_FIELDS = Set.of("signature", "graph");
    private static final Set<String> TOMBSTONE_FIELDS = Set.of(
        "kind", TOMBSTONE_FORMAT_VERSION, TOMBSTONE_RESOURCE, TOMBSTONE_REVISION, TOMBSTONE_MUTATION_ID,
        TOMBSTONE_PAYLOAD_HASH, TOMBSTONE_DELETED, TOMBSTONE_HASH);

    private final CanonicalCodec<GraphDocument> graphCodec;
    private final CanonicalCodec<FunctionSourceDocument> functionCodec;

    public CoreGraphAssetCodec() {
        this(GraphDocumentCodec.INSTANCE, FunctionSourceDocumentCodec.INSTANCE);
    }

    public CoreGraphAssetCodec(CanonicalCodec<GraphDocument> graphCodec,
                               CanonicalCodec<FunctionSourceDocument> functionCodec) {
        this.graphCodec = Objects.requireNonNull(graphCodec, "Graph codec is required");
        this.functionCodec = Objects.requireNonNull(functionCodec, "Function source codec is required");
    }

    public byte[] encode(GraphDocument graph, Metadata metadata) {
        return encodeValue(graph, metadata, null, CURRENT_ASSET_FORMAT_VERSION).canonicalBytes();
    }

    public byte[] encode(GraphDocument graph, Metadata metadata, ServerResourceLocator expectedResource) {
        return encodeValue(graph, metadata, expectedResource, CURRENT_ASSET_FORMAT_VERSION).canonicalBytes();
    }

    public byte[] encode(FunctionSourceDocument source, Metadata metadata) {
        return encodeValue(source, metadata, null, CURRENT_ASSET_FORMAT_VERSION).canonicalBytes();
    }

    public byte[] encode(FunctionSourceDocument source, Metadata metadata, ServerResourceLocator expectedResource) {
        return encodeValue(source, metadata, expectedResource, CURRENT_ASSET_FORMAT_VERSION).canonicalBytes();
    }

    public byte[] encode(Asset asset) {
        Asset checked = Objects.requireNonNull(asset, "Decoded asset is required");
        JsonValue.JsonObject encoded = checked.graphDocument() != null
            ? encodeValue(checked.graphDocument(), checked.envelope().metadata(), null,
                checked.envelope().assetFormatVersion())
            : encodeValue(checked.functionSourceDocument(), checked.envelope().metadata(), null,
                checked.envelope().assetFormatVersion());
        if (!checked.envelope().assetHash().canonicalText().equals(text(encoded, ASSET_HASH))) {
            throw new IllegalArgumentException("Decoded asset hash does not match its payload state");
        }
        return encoded.canonicalBytes();
    }

    public Asset reconstruct(Envelope envelope, GraphDocument graph) {
        Asset asset = Asset.graph(envelope, graph);
        encode(asset);
        return asset;
    }

    public Asset reconstruct(Envelope envelope, FunctionSourceDocument source) {
        Asset asset = Asset.function(envelope, source);
        encode(asset);
        return asset;
    }

    public JsonValue.JsonObject encodeValue(GraphDocument graph, Metadata metadata) {
        return encodeValue(graph, metadata, null, CURRENT_ASSET_FORMAT_VERSION);
    }

    public JsonValue.JsonObject encodeValue(GraphDocument graph, Metadata metadata,
                                            ServerResourceLocator expectedResource) {
        return encodeValue(graph, metadata, expectedResource, CURRENT_ASSET_FORMAT_VERSION);
    }

    private JsonValue.JsonObject encodeValue(GraphDocument graph, Metadata metadata,
                                             ServerResourceLocator expectedResource, int assetFormatVersion) {
        GraphDocument checked = Objects.requireNonNull(graph, "Graph document is required");
        Metadata checkedMetadata = Objects.requireNonNull(metadata, "Asset metadata is required");
        validatePayloadIdentity(checked.resource(), GRAPH_DOCUMENT_KIND, checkedMetadata, expectedResource);
        validatePayloadRevision(checked, checkedMetadata);
        return encodeEnvelope(graphCodec.encode(checked), GRAPH_DOCUMENT_KIND, checkedMetadata, assetFormatVersion);
    }

    public JsonValue.JsonObject encodeValue(FunctionSourceDocument source, Metadata metadata) {
        return encodeValue(source, metadata, null, CURRENT_ASSET_FORMAT_VERSION);
    }

    public JsonValue.JsonObject encodeValue(FunctionSourceDocument source, Metadata metadata,
                                            ServerResourceLocator expectedResource) {
        return encodeValue(source, metadata, expectedResource, CURRENT_ASSET_FORMAT_VERSION);
    }

    private JsonValue.JsonObject encodeValue(FunctionSourceDocument source, Metadata metadata,
                                             ServerResourceLocator expectedResource, int assetFormatVersion) {
        FunctionSourceDocument checked = Objects.requireNonNull(source, "Function source document is required");
        Metadata checkedMetadata = Objects.requireNonNull(metadata, "Asset metadata is required");
        validatePayloadIdentity(checked.graph().resource(), FUNCTION_SOURCE_KIND, checkedMetadata, expectedResource);
        validatePayloadRevision(checked, checkedMetadata);
        return encodeEnvelope(functionCodec.encode(checked), FUNCTION_SOURCE_KIND, checkedMetadata, assetFormatVersion);
    }

    public String encodeText(GraphDocument graph, Metadata metadata) {
        return new String(encode(graph, metadata), StandardCharsets.UTF_8);
    }

    public String encodeText(FunctionSourceDocument source, Metadata metadata) {
        return new String(encode(source, metadata), StandardCharsets.UTF_8);
    }

    public Asset decode(byte[] input) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "Asset bytes are required")), null);
    }

    public Asset decode(byte[] input, ServerResourceLocator expectedResource) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "Asset bytes are required")), expectedResource);
    }

    public Asset decodeText(String input) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "Asset text is required")), null);
    }

    public Asset decodeText(String input, ServerResourceLocator expectedResource) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "Asset text is required")), expectedResource);
    }

    public Asset decode(JsonValue value) {
        return decode(value, null);
    }

    public Asset decode(JsonValue value, ServerResourceLocator expectedResource) {
        JsonValue.JsonObject object = CanonicalCodec.requireObject(value);
        EnvelopeValues envelope = decodeEnvelope(object);
        JsonValue.JsonObject coreObject = coreObject(object);
        rejectCoreEnvelopeFields(coreObject, envelope.corePayloadKind());
        verifyAssetHash(object, envelope);
        Asset decoded;
        if (GRAPH_DOCUMENT_KIND.equals(envelope.corePayloadKind())) {
            GraphDocument graph = graphCodec.decode(coreObject);
            validatePayloadIdentity(graph.resource(), GRAPH_DOCUMENT_KIND, envelope.metadata(), expectedResource);
            validatePayloadRevision(graph, envelope.metadata());
            decoded = Asset.graph(envelope.toPublic(), graph);
        } else {
            FunctionSourceDocument source = functionCodec.decode(coreObject);
            validatePayloadIdentity(source.graph().resource(), FUNCTION_SOURCE_KIND, envelope.metadata(), expectedResource);
            validatePayloadRevision(source, envelope.metadata());
            decoded = Asset.function(envelope.toPublic(), source);
        }
        requireCanonicalReencoding(object, encode(decoded));
        return decoded;
    }

    public RevisionSkew decodeOneBehindRevision(byte[] input, ServerResourceLocator expectedResource) {
        byte[] bytes = Objects.requireNonNull(input, "Asset bytes are required");
        JsonValue.JsonObject object = CanonicalCodec.requireObject(CanonicalCodec.decode(bytes));
        if (!Arrays.equals(bytes, object.canonicalBytes())) {
            throw new IllegalArgumentException("Revision-skewed Core asset bytes are not canonical");
        }
        EnvelopeValues envelope = decodeEnvelope(object);
        if (envelope.assetFormatVersion() != CURRENT_ASSET_FORMAT_VERSION) {
            throw new IllegalArgumentException("Core revision repair requires asset format version 4");
        }
        JsonValue.JsonObject coreObject = coreObject(object);
        rejectCoreEnvelopeFields(coreObject, envelope.corePayloadKind());
        verifyAssetHash(object, envelope);
        Asset source;
        if (GRAPH_DOCUMENT_KIND.equals(envelope.corePayloadKind())) {
            GraphDocument graph = graphCodec.decode(coreObject);
            validatePayloadIdentity(graph.resource(), GRAPH_DOCUMENT_KIND, envelope.metadata(), expectedResource);
            requireOneBehind(graph.revision(), envelope.metadata().assetRevision());
            source = Asset.graph(envelope.toPublic(), graph);
        } else {
            FunctionSourceDocument function = functionCodec.decode(coreObject);
            validatePayloadIdentity(function.graph().resource(), FUNCTION_SOURCE_KIND, envelope.metadata(), expectedResource);
            requireOneBehind(function.graph().revision(), envelope.metadata().assetRevision());
            if (function.signature().revision().value() != function.graph().revision()) {
                throw new IllegalArgumentException("Core function signature revision does not match its graph revision");
            }
            source = Asset.function(envelope.toPublic(), function);
        }
        return new RevisionSkew(source);
    }

    public Asset repairOneBehindRevision(byte[] input, ServerResourceLocator expectedResource, Metadata repairedMetadata) {
        RevisionSkew skew = decodeOneBehindRevision(input, expectedResource);
        Metadata repaired = Objects.requireNonNull(repairedMetadata, "Repaired asset metadata is required");
        Envelope sourceEnvelope = skew.source().envelope();
        long expectedRevision;
        try {
            expectedRevision = Math.addExact(sourceEnvelope.assetRevision(), 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Core revision repair exceeds the supported revision range", exception);
        }
        if (!sourceEnvelope.resourceType().equals(repaired.resourceType())
            || repaired.assetRevision() != expectedRevision
            || sourceEnvelope.assetMutationId().equals(repaired.assetMutationId())
            || sourceEnvelope.assetActivationState() != repaired.assetActivationState()) {
            throw new IllegalArgumentException("Core revision repair metadata does not advance the exact source state");
        }
        byte[] encoded;
        if (skew.source().graphDocument() != null) {
            encoded = encode(rebase(skew.source().graphDocument(), expectedRevision), repaired, expectedResource);
        } else {
            FunctionSourceDocument source = skew.source().functionSourceDocument();
            GraphDocument graph = rebase(source.graph(), expectedRevision);
            FunctionSignature signature = source.signature();
            FunctionSignature rebasedSignature = new FunctionSignature(signature.function(),
                new FunctionRevision(expectedRevision), signature.inputs(), signature.outputs(), signature.unknown());
            encoded = encode(new FunctionSourceDocument(rebasedSignature, graph, source.unknown()), repaired,
                expectedResource);
        }
        return decode(encoded, expectedResource);
    }

    public void verifyRevisionRepair(Asset repairedAsset, ServerResourceLocator expectedResource,
                                     Metadata sourceMetadata, ContentHash sourceAssetHash) {
        Asset repaired = Objects.requireNonNull(repairedAsset, "Repaired Core asset is required");
        Metadata source = Objects.requireNonNull(sourceMetadata, "Source Core asset metadata is required");
        ContentHash expectedHash = Objects.requireNonNull(sourceAssetHash, "Source Core asset hash is required");
        decode(encode(repaired), expectedResource);
        long repairedRevision = Math.addExact(source.assetRevision(), 1L);
        long payloadRevision = Math.subtractExact(source.assetRevision(), 1L);
        if (!repaired.envelope().resourceType().equals(source.resourceType())
            || repaired.envelope().assetRevision() != repairedRevision
            || repaired.envelope().assetMutationId().equals(source.assetMutationId())
            || repaired.envelope().assetActivationState() != source.assetActivationState()) {
            throw new IllegalArgumentException("Core revision repair result does not advance the exact source state");
        }
        JsonValue.JsonObject sourceEnvelope;
        if (repaired.graphDocument() != null) {
            sourceEnvelope = encodeEnvelope(graphCodec.encode(rebase(repaired.graphDocument(), payloadRevision)),
                repaired.corePayloadKind(), source, CURRENT_ASSET_FORMAT_VERSION);
        } else {
            FunctionSourceDocument current = repaired.functionSourceDocument();
            GraphDocument graph = rebase(current.graph(), payloadRevision);
            FunctionSignature signature = current.signature();
            FunctionSignature rebasedSignature = new FunctionSignature(signature.function(),
                new FunctionRevision(payloadRevision), signature.inputs(), signature.outputs(), signature.unknown());
            FunctionSourceDocument rebased = new FunctionSourceDocument(rebasedSignature, graph, current.unknown());
            sourceEnvelope = encodeEnvelope(functionCodec.encode(rebased), repaired.corePayloadKind(), source,
                CURRENT_ASSET_FORMAT_VERSION);
        }
        if (!expectedHash.canonicalText().equals(text(sourceEnvelope, ASSET_HASH))) {
            throw new IllegalArgumentException("Core revision repair result does not preserve the source payload");
        }
    }

    public GraphDocument decodeGraph(byte[] input) {
        Asset decoded = decode(input);
        if (decoded.graphDocument() == null) {
            throw new IllegalArgumentException("Asset payload is not a graph document");
        }
        return decoded.graphDocument();
    }

    public GraphDocument decodeGraph(byte[] input, ServerResourceLocator expectedResource) {
        Asset decoded = decode(input, expectedResource);
        if (decoded.graphDocument() == null) {
            throw new IllegalArgumentException("Asset payload is not a graph document");
        }
        return decoded.graphDocument();
    }

    public FunctionSourceDocument decodeFunctionSource(byte[] input) {
        Asset decoded = decode(input);
        if (decoded.functionSourceDocument() == null) {
            throw new IllegalArgumentException("Asset payload is not a function source document");
        }
        return decoded.functionSourceDocument();
    }

    public FunctionSourceDocument decodeFunctionSource(byte[] input, ServerResourceLocator expectedResource) {
        Asset decoded = decode(input, expectedResource);
        if (decoded.functionSourceDocument() == null) {
            throw new IllegalArgumentException("Asset payload is not a function source document");
        }
        return decoded.functionSourceDocument();
    }

    public ContentHash assetIntegrityHash(JsonValue.JsonObject value) {
        Objects.requireNonNull(value, "Asset value is required");
        Map<String, JsonValue> fields = new LinkedHashMap<>(value.fields());
        if (fields.remove(ASSET_HASH) == null) {
            throw new IllegalArgumentException("Asset hash field is required");
        }
        return hashWithoutAssetHash(JsonValue.object(fields));
    }

    public byte[] encodeTombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                                  ContentHash priorPayloadHash) {
        return encodeTombstone(new Tombstone(resource, revision, mutationId, priorPayloadHash));
    }

    public byte[] encodeTombstone(Tombstone tombstone) {
        return encodeTombstoneValue(tombstone).canonicalBytes();
    }

    public JsonValue.JsonObject encodeTombstoneValue(Tombstone tombstone) {
        Tombstone checked = Objects.requireNonNull(tombstone, "Core graph tombstone is required");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("kind", JsonValue.of(TOMBSTONE_KIND));
        fields.put(TOMBSTONE_FORMAT_VERSION, JsonValue.of(checked.formatVersion()));
        fields.put(TOMBSTONE_RESOURCE, IdentityCodec.encodeLocator(checked.resource()));
        fields.put(TOMBSTONE_REVISION, JsonValue.of(checked.revision()));
        fields.put(TOMBSTONE_MUTATION_ID, JsonValue.of(checked.mutationId().toString()));
        fields.put(TOMBSTONE_PAYLOAD_HASH, JsonValue.of(checked.priorPayloadHash().canonicalText()));
        fields.put(TOMBSTONE_DELETED, JsonValue.of(true));
        JsonValue.JsonObject withoutHash = JsonValue.object(fields);
        ContentHash integrityHash = hashWithoutTombstoneHash(withoutHash);
        if (checked.integrityHash() != null && !integrityHash.equals(checked.integrityHash())) {
            throw new IllegalArgumentException("Core graph tombstone integrity hash does not match its state");
        }
        fields.put(TOMBSTONE_HASH, JsonValue.of(integrityHash.canonicalText()));
        return JsonValue.object(fields);
    }

    public Tombstone decodeTombstone(byte[] input) {
        return decodeTombstone(CanonicalCodec.decode(Objects.requireNonNull(input, "Tombstone bytes are required")), null);
    }

    public Tombstone decodeTombstone(byte[] input, ServerResourceLocator expectedResource) {
        return decodeTombstone(CanonicalCodec.decode(Objects.requireNonNull(input, "Tombstone bytes are required")),
            expectedResource);
    }

    public Tombstone decodeTombstone(JsonValue value) {
        return decodeTombstone(value, null);
    }

    public Tombstone decodeTombstone(JsonValue value, ServerResourceLocator expectedResource) {
        JsonValue.JsonObject object = CanonicalCodec.requireObject(value);
        rejectTombstoneShape(object);
        if (!TOMBSTONE_KIND.equals(text(object, "kind"))) {
            throw new IllegalArgumentException("Unsupported Core graph tombstone kind");
        }
        int version = exactInt(object, TOMBSTONE_FORMAT_VERSION);
        if (version != CURRENT_TOMBSTONE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Core graph tombstone format version: " + version);
        }
        ServerResourceLocator resource = IdentityCodec.decodeLocator(object.value(TOMBSTONE_RESOURCE));
        validateTombstoneResource(resource, expectedResource);
        long revision = exactLong(object, TOMBSTONE_REVISION);
        if (revision <= 0L) {
            throw new IllegalArgumentException("Core graph tombstone revision must be positive");
        }
        UUID mutationId = parseMutationId(text(object, TOMBSTONE_MUTATION_ID));
        ContentHash priorPayloadHash = new ContentHash(text(object, TOMBSTONE_PAYLOAD_HASH));
        if (!exactBoolean(object, TOMBSTONE_DELETED)) {
            throw new IllegalArgumentException("Core graph tombstone deleted field must be true");
        }
        ContentHash integrityHash = new ContentHash(text(object, TOMBSTONE_HASH));
        ContentHash expectedHash = hashWithoutTombstoneHash(objectWithoutTombstoneHash(object));
        if (!expectedHash.equals(integrityHash)) {
            throw new IllegalArgumentException("Core graph tombstone integrity hash does not match canonical state");
        }
        Tombstone decoded = new Tombstone(resource, revision, mutationId, priorPayloadHash, true, version, integrityHash);
        if (!Arrays.equals(object.canonicalBytes(), encodeTombstone(decoded))) {
            throw new IllegalArgumentException("Core graph tombstone is not in the exact canonical shape");
        }
        return decoded;
    }

    public ContentHash tombstoneIntegrityHash(JsonValue.JsonObject value) {
        return hashWithoutTombstoneHash(objectWithoutTombstoneHash(Objects.requireNonNull(value,
            "Tombstone value is required")));
    }

    private JsonValue.JsonObject encodeEnvelope(JsonValue value, String kind, Metadata metadata,
                                                int assetFormatVersion) {
        JsonValue.JsonObject core = CanonicalCodec.requireObject(value);
        rejectCoreEnvelopeFields(core, kind);
        validateAssetFormatVersion(assetFormatVersion);
        Map<String, JsonValue> known = new LinkedHashMap<>();
        known.put(RESOURCE_TYPE, JsonValue.of(metadata.resourceType()));
        known.put(ASSET_FORMAT_VERSION, JsonValue.of(assetFormatVersion));
        known.put(ASSET_REVISION, JsonValue.of(metadata.assetRevision()));
        known.put(ASSET_MUTATION_ID, JsonValue.of(metadata.assetMutationId()));
        if (assetFormatVersion >= CURRENT_ASSET_FORMAT_VERSION) {
            known.put(ASSET_ACTIVATION_STATE, JsonValue.of(metadata.assetActivationState().wireName()));
        }
        known.put(CORE_PAYLOAD_KIND, JsonValue.of(kind));
        known.put(CORE_PAYLOAD_VERSION, JsonValue.of(CURRENT_CORE_PAYLOAD_VERSION));
        JsonValue.JsonObject withoutHash = CanonicalCodec.mergeKnownFields(known, core.fields());
        ContentHash assetHash = hashWithoutAssetHash(withoutHash);
        Map<String, JsonValue> complete = new LinkedHashMap<>(withoutHash.fields());
        complete.put(ASSET_HASH, JsonValue.of(assetHash.canonicalText()));
        return JsonValue.object(complete);
    }

    private EnvelopeValues decodeEnvelope(JsonValue.JsonObject object) {
        int assetFormatVersion = exactInt(object, ASSET_FORMAT_VERSION);
        rejectEnvelopeShape(object, assetFormatVersion);
        validateAssetFormatVersion(assetFormatVersion);
        String resourceType = text(object, RESOURCE_TYPE);
        long assetRevision = exactLong(object, ASSET_REVISION);
        String assetMutationId = text(object, ASSET_MUTATION_ID);
        ResourceActivationState activationState = object.contains(ASSET_ACTIVATION_STATE)
            ? ResourceActivationState.fromWireName(text(object, ASSET_ACTIVATION_STATE))
            : ResourceActivationState.ACTIVE;
        ContentHash assetHash = new ContentHash(text(object, ASSET_HASH));
        String kind = text(object, CORE_PAYLOAD_KIND);
        int payloadVersion = exactInt(object, CORE_PAYLOAD_VERSION);
        if (payloadVersion != CURRENT_CORE_PAYLOAD_VERSION) {
            throw new IllegalArgumentException("Unsupported Core payload version: " + payloadVersion);
        }
        if (!GRAPH_DOCUMENT_KIND.equals(kind) && !FUNCTION_SOURCE_KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported Core payload kind: " + kind);
        }
        validatePayloadKindType(kind, resourceType);
        Metadata metadata = new Metadata(resourceType, assetRevision, assetMutationId, activationState);
        return new EnvelopeValues(metadata, assetHash, kind, assetFormatVersion, payloadVersion);
    }

    private static void validateAssetFormatVersion(int version) {
        if (version != LEGACY_ASSET_FORMAT_VERSION && version != CURRENT_ASSET_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported asset format version: " + version);
        }
    }

    private static JsonValue.JsonObject coreObject(JsonValue.JsonObject object) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        ENVELOPE_FIELDS.forEach(fields::remove);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Core payload is missing");
        }
        return JsonValue.object(fields);
    }

    private static void rejectEnvelopeShape(JsonValue.JsonObject object, int assetFormatVersion) {
        Set<String> required = new LinkedHashSet<>(ENVELOPE_FIELDS);
        if (assetFormatVersion == LEGACY_ASSET_FORMAT_VERSION) {
            required.remove(ASSET_ACTIVATION_STATE);
            if (object.contains(ASSET_ACTIVATION_STATE)) {
                throw new IllegalArgumentException("Asset activation state is not accepted in asset format version 3");
            }
        }
        for (String field : required) {
            if (!object.contains(field)) {
                throw new IllegalArgumentException("Required asset envelope field is missing: " + field);
            }
        }
        rejectUnknownEnvelopePrefixes(object);
    }

    private static void rejectUnknownEnvelopePrefixes(JsonValue.JsonObject object) {
        for (String field : object.fields().keySet()) {
            if ((field.startsWith("asset") || field.startsWith("corePayload")) && !ENVELOPE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown asset envelope field: " + field);
            }
        }
    }

    private static void rejectCoreEnvelopeFields(JsonValue.JsonObject core, String kind) {
        rejectUnknownEnvelopePrefixes(core);
        Set<String> known = GRAPH_DOCUMENT_KIND.equals(kind) ? GRAPH_ROOT_FIELDS : FUNCTION_ROOT_FIELDS;
        for (String field : core.fields().keySet()) {
            if (!known.contains(field) && LEGACY_ENVELOPE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Legacy asset envelope field is not accepted: " + field);
            }
            if (ENVELOPE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Core payload collides with asset envelope field: " + field);
            }
        }
    }

    private static void verifyAssetHash(JsonValue.JsonObject object, EnvelopeValues envelope) {
        if (!hashWithoutAssetHash(objectWithoutAssetHash(object)).equals(envelope.assetHash())) {
            throw new IllegalArgumentException("Asset integrity hash does not match canonical envelope");
        }
    }

    private static JsonValue.JsonObject objectWithoutAssetHash(JsonValue.JsonObject object) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        if (fields.remove(ASSET_HASH) == null) {
            throw new IllegalArgumentException("Asset hash field is required");
        }
        return JsonValue.object(fields);
    }

    private static ContentHash hashWithoutAssetHash(JsonValue.JsonObject object) {
        return new ContentHash(CanonicalJson.sha256Canonical(ASSET_HASH_DOMAIN, object.canonicalBytes()));
    }

    private static void requireCanonicalReencoding(JsonValue.JsonObject input, byte[] reencoded) {
        if (!Arrays.equals(input.canonicalBytes(), reencoded)) {
            throw new IllegalArgumentException("Asset envelope is not in the exact canonical shape");
        }
    }

    private static void validatePayloadIdentity(ServerResourceLocator resource, String payloadKind, Metadata metadata,
                                                ServerResourceLocator expectedResource) {
        if (!RESOURCE_OWNER.equals(resource.owner())) {
            throw new IllegalArgumentException("Core resource owner does not match the authoritative Core owner");
        }
        if (expectedResource != null && (!expectedResource.serverId().equals(resource.serverId())
            || !expectedResource.owner().equals(resource.owner())
            || !expectedResource.resourceType().equals(resource.resourceType())
            || !expectedResource.id().equals(resource.id()))) {
            throw new IllegalArgumentException("Core resource does not match the expected server resource locator");
        }
        String actualType = resource.resourceType().value();
        validatePayloadKindType(payloadKind, actualType);
        if (!actualType.equals(metadata.resourceType())) {
            throw new IllegalArgumentException("Asset resource type does not match Core resource identity");
        }
    }

    private static void validatePayloadRevision(GraphDocument graph, Metadata metadata) {
        if (graph.revision() != metadata.assetRevision()) {
            throw new IllegalArgumentException("Core graph revision does not match asset revision");
        }
    }

    private static void validatePayloadRevision(FunctionSourceDocument source, Metadata metadata) {
        if (source.graph().revision() != metadata.assetRevision()
            || source.signature().revision().value() != metadata.assetRevision()) {
            throw new IllegalArgumentException("Core function revision does not match asset revision");
        }
    }

    private static void requireOneBehind(long payloadRevision, long assetRevision) {
        long expected;
        try {
            expected = Math.addExact(payloadRevision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Core payload revision exceeds the supported revision range", exception);
        }
        if (expected != assetRevision) {
            throw new IllegalArgumentException("Core payload is not exactly one revision behind its asset envelope");
        }
    }

    private static GraphDocument rebase(GraphDocument graph, long revision) {
        return new GraphDocument(graph.schemaVersion(), graph.resource(), revision, graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(),
            graph.unknown());
    }

    private static void validatePayloadKindType(String payloadKind, String resourceType) {
        if (!GRAPH_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("Unsupported Core resource type: " + resourceType);
        }
        if (GRAPH_DOCUMENT_KIND.equals(payloadKind) && !GRAPH_DOCUMENT_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("Graph document payloads require flow, command, or custom content resource type");
        }
        if (FUNCTION_SOURCE_KIND.equals(payloadKind) && !"function".equals(resourceType)) {
            throw new IllegalArgumentException("Function source payloads require function resource type");
        }
    }

    private static void rejectTombstoneShape(JsonValue.JsonObject object) {
        for (String field : object.fields().keySet()) {
            if (!TOMBSTONE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown Core graph tombstone field: " + field);
            }
        }
        for (String field : TOMBSTONE_FIELDS) {
            if (!object.contains(field)) {
                throw new IllegalArgumentException("Required Core graph tombstone field is missing: " + field);
            }
        }
    }

    private static JsonValue.JsonObject objectWithoutTombstoneHash(JsonValue.JsonObject object) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        if (fields.remove(TOMBSTONE_HASH) == null) {
            throw new IllegalArgumentException("Core graph tombstone hash field is required");
        }
        return JsonValue.object(fields);
    }

    private static ContentHash hashWithoutTombstoneHash(JsonValue.JsonObject object) {
        return new ContentHash(CanonicalJson.sha256Canonical(TOMBSTONE_HASH_DOMAIN, object.canonicalBytes()));
    }

    private static void validateTombstoneResource(ServerResourceLocator resource,
                                                  ServerResourceLocator expectedResource) {
        if (!RESOURCE_OWNER.equals(resource.owner())) {
            throw new IllegalArgumentException("Core graph tombstone resource owner is not authoritative");
        }
        if (!GRAPH_TYPES.contains(resource.resourceType().value())) {
            throw new IllegalArgumentException("Core graph tombstone resource type is not a graph type");
        }
        if (expectedResource != null && !sameFullLocator(resource, expectedResource)) {
            throw new IllegalArgumentException("Core graph tombstone resource does not match the expected locator");
        }
    }

    private static boolean sameFullLocator(ServerResourceLocator first, ServerResourceLocator second) {
        return IdentityCodec.encodeLocator(first).canonicalText().equals(IdentityCodec.encodeLocator(second).canonicalText());
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonString text) || text.value().isBlank()) {
            throw new IllegalArgumentException("Asset envelope field must be non-blank text: " + field);
        }
        return text.value();
    }

    private static int exactInt(JsonValue.JsonObject object, String field) {
        try {
            return number(object, field).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Asset envelope field must be an integer: " + field, exception);
        }
    }

    private static long exactLong(JsonValue.JsonObject object, String field) {
        try {
            return number(object, field).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Asset envelope field must be an integer: " + field, exception);
        }
    }

    private static BigDecimal number(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Asset envelope field must be an integer: " + field);
        }
        return number.value();
    }

    private static boolean exactBoolean(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Core graph tombstone field must be boolean: " + field);
        }
        return booleanValue.value();
    }

    private static UUID parseMutationId(String value) {
        return UUID.fromString(canonicalMutationId(value));
    }

    private static String canonicalMutationId(String value) {
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
        return value;
    }

    public record Metadata(String resourceType, long assetRevision, String assetMutationId,
                           ResourceActivationState assetActivationState) {
        public Metadata {
            resourceType = new ResourceTypeId(Objects.requireNonNull(resourceType, "Resource type is required")).value();
            if (assetRevision < 0L) {
                throw new IllegalArgumentException("Asset revision cannot be negative");
            }
            assetMutationId = canonicalMutationId(assetMutationId);
            assetActivationState = Objects.requireNonNull(assetActivationState, "Asset activation state is required");
        }

        public Metadata(String resourceType, long assetRevision, UUID assetMutationId,
                        ResourceActivationState assetActivationState) {
            this(resourceType, assetRevision, Objects.requireNonNull(assetMutationId,
                "Asset mutation ID is required").toString(), assetActivationState);
        }

        public Metadata(String resourceType, long assetRevision, UUID assetMutationId) {
            this(resourceType, assetRevision, assetMutationId, ResourceActivationState.ACTIVE);
        }

        public Metadata(String resourceType, long assetRevision, String assetMutationId) {
            this(resourceType, assetRevision, assetMutationId, ResourceActivationState.ACTIVE);
        }
    }

    public record Envelope(String resourceType, int assetFormatVersion, long assetRevision, String assetMutationId,
                           ResourceActivationState assetActivationState, ContentHash assetHash,
                           String corePayloadKind, int corePayloadVersion) {
        public Envelope {
            resourceType = new ResourceTypeId(Objects.requireNonNull(resourceType, "Resource type is required")).value();
            validateAssetFormatVersion(assetFormatVersion);
            if (assetRevision < 0L) {
                throw new IllegalArgumentException("Asset revision cannot be negative");
            }
            assetMutationId = canonicalMutationId(assetMutationId);
            assetActivationState = Objects.requireNonNull(assetActivationState, "Asset activation state is required");
            assetHash = Objects.requireNonNull(assetHash, "Asset hash is required");
            if (!GRAPH_DOCUMENT_KIND.equals(corePayloadKind) && !FUNCTION_SOURCE_KIND.equals(corePayloadKind)) {
                throw new IllegalArgumentException("Unsupported Core payload kind: " + corePayloadKind);
            }
            if (corePayloadVersion != CURRENT_CORE_PAYLOAD_VERSION) {
                throw new IllegalArgumentException("Unsupported Core payload version: " + corePayloadVersion);
            }
            validatePayloadKindType(corePayloadKind, resourceType);
        }

        public Metadata metadata() {
            return new Metadata(resourceType, assetRevision, assetMutationId, assetActivationState);
        }
    }

    public record RevisionSkew(Asset source) {
        public RevisionSkew {
            source = Objects.requireNonNull(source, "Revision-skewed Core asset is required");
            long payloadRevision = source.graphDocument() != null
                ? source.graphDocument().revision() : source.functionSourceDocument().graph().revision();
            requireOneBehind(payloadRevision, source.envelope().assetRevision());
        }
    }

    public static final class Asset {
        private final Envelope envelope;
        private final GraphDocument graphDocument;
        private final FunctionSourceDocument functionSourceDocument;

        private Asset(Envelope envelope, GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument) {
            this.envelope = Objects.requireNonNull(envelope, "Asset envelope is required");
            if ((graphDocument == null) == (functionSourceDocument == null)) {
                throw new IllegalArgumentException("Exactly one Core payload is required");
            }
            this.graphDocument = graphDocument;
            this.functionSourceDocument = functionSourceDocument;
            if (envelope.assetFormatVersion() == LEGACY_ASSET_FORMAT_VERSION
                && envelope.assetActivationState() != ResourceActivationState.ACTIVE) {
                throw new IllegalArgumentException("Asset format version 3 can only represent active state");
            }
            ServerResourceLocator resource = graphDocument != null ? graphDocument.resource()
                : functionSourceDocument.graph().resource();
            validatePayloadIdentity(resource, envelope.corePayloadKind(), envelope.metadata(), null);
        }

        private static Asset graph(Envelope envelope, GraphDocument graph) {
            return new Asset(envelope, Objects.requireNonNull(graph, "Graph document is required"), null);
        }

        private static Asset function(Envelope envelope, FunctionSourceDocument source) {
            return new Asset(envelope, null, Objects.requireNonNull(source, "Function source document is required"));
        }

        public Envelope envelope() {
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

    public record Tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                            ContentHash priorPayloadHash, boolean deleted, int formatVersion,
                            ContentHash integrityHash) {
        public Tombstone {
            resource = Objects.requireNonNull(resource, "Tombstone resource is required");
            if (revision <= 0L) {
                throw new IllegalArgumentException("Core graph tombstone revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Tombstone mutation ID is required");
            if ((CanonicalUuids.version(mutationId) != 4 && CanonicalUuids.version(mutationId) != 5) || CanonicalUuids.variant(mutationId) != 2) {
                throw new IllegalArgumentException("Tombstone mutation ID must use an RFC 4122 UUID version 4 or 5");
            }
            priorPayloadHash = Objects.requireNonNull(priorPayloadHash, "Prior Core payload hash is required");
            if (!deleted) {
                throw new IllegalArgumentException("Core graph tombstone deleted state must be true");
            }
            if (formatVersion != CURRENT_TOMBSTONE_FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Core graph tombstone format version: " + formatVersion);
            }
            validateTombstoneResource(resource, null);
        }

        public Tombstone(ServerResourceLocator resource, long revision, UUID mutationId,
                         ContentHash priorPayloadHash) {
            this(resource, revision, mutationId, priorPayloadHash, true, CURRENT_TOMBSTONE_FORMAT_VERSION, null);
        }

        public ContentHash payloadHash() {
            return priorPayloadHash;
        }

        public ContentHash tombstoneHash() {
            return integrityHash;
        }
    }

    private record EnvelopeValues(Metadata metadata, ContentHash assetHash, String corePayloadKind,
                                  int assetFormatVersion, int corePayloadVersion) {
        private Envelope toPublic() {
            return new Envelope(metadata.resourceType(), assetFormatVersion, metadata.assetRevision(),
                metadata.assetMutationId(), metadata.assetActivationState(), assetHash, corePayloadKind,
                corePayloadVersion);
        }
    }
}
