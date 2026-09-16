package restudio.resync.flow.storage;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphAssetCodecTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)),
        new ContentHash("1".repeat(64)));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void roundTripsV4GraphAndFunctionAssetsWithoutLosingUnknownData() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        GraphDocument graph = graph("flow", "round-trip-flow", 4,
            OpaqueData.of(Map.of("futureGraph", Map.of("enabled", true))));
        FunctionSourceDocument source = source("round-trip-function", 7);

        byte[] graphBytes = codec.encode(graph, metadata("flow", 4, ResourceActivationState.INACTIVE));
        byte[] functionBytes = codec.encode(source, metadata("function", 7, ResourceActivationState.ACTIVE));
        CoreGraphAssetCodec.Asset decodedGraph = codec.decode(graphBytes, graph.resource());
        CoreGraphAssetCodec.Asset decodedFunction = codec.decode(functionBytes, source.graph().resource());

        assertEquals(CoreGraphAssetCodec.CURRENT_ASSET_FORMAT_VERSION,
            decodedGraph.envelope().assetFormatVersion());
        assertEquals(4, decodedGraph.envelope().assetRevision());
        assertEquals(4, decodedGraph.graphDocument().revision());
        assertEquals(ResourceActivationState.INACTIVE, decodedGraph.envelope().assetActivationState());
        assertEquals(graph.unknown(), decodedGraph.graphDocument().unknown());
        assertEquals(CoreGraphAssetCodec.FUNCTION_SOURCE_KIND, decodedFunction.corePayloadKind());
        assertEquals(source.signature().unknown(), decodedFunction.functionSourceDocument().signature().unknown());
        assertEquals(source.graph().unknown(), decodedFunction.functionSourceDocument().graph().unknown());
        assertEquals(source.unknown(), decodedFunction.functionSourceDocument().unknown());
        assertArrayEquals(graphBytes, codec.encode(decodedGraph));
        assertArrayEquals(functionBytes, codec.encode(decodedFunction));
        assertArrayEquals(graphBytes, CanonicalCodec.decode(graphBytes).canonicalBytes());
    }

    @Test
    void v4ActivationRevisionMutationAndPayloadAreCoveredByTheAssetHash() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        GraphDocument graph = graph("command", "secured-command", 5,
            OpaqueData.of(Map.of("futurePolicy", Map.of("required", true))));
        byte[] active = codec.encode(graph, metadata("command", 5, ResourceActivationState.ACTIVE));
        byte[] inactive = codec.encode(graph, metadata("command", 5, ResourceActivationState.INACTIVE));
        JsonValue.JsonObject object = object(inactive);

        assertEquals(4, number(object, CoreGraphAssetCodec.ASSET_FORMAT_VERSION));
        assertEquals(5, number(object, CoreGraphAssetCodec.ASSET_REVISION));
        assertEquals(MUTATION.toString(), text(object, CoreGraphAssetCodec.ASSET_MUTATION_ID));
        assertEquals("inactive", text(object, CoreGraphAssetCodec.ASSET_ACTIVATION_STATE));
        assertEquals(codec.assetIntegrityHash(object).canonicalText(), text(object, CoreGraphAssetCodec.ASSET_HASH));
        assertNotEquals(text(object(active), CoreGraphAssetCodec.ASSET_HASH),
            text(object, CoreGraphAssetCodec.ASSET_HASH));

        Map<String, JsonValue> changedRevision = fields(object);
        changedRevision.put(CoreGraphAssetCodec.ASSET_REVISION, JsonValue.of(6));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.object(changedRevision)));

        Map<String, JsonValue> changedPolicy = fields(object);
        changedPolicy.put("futurePolicy", JsonValue.fromJava(Map.of("required", false)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.object(changedPolicy)));
    }

    @Test
    void readsV3AsActiveAndPreservesItsExactCanonicalBytes() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        byte[] current = codec.encode(graph("flow", "legacy-envelope", 6, OpaqueData.empty()),
            metadata("flow", 6, ResourceActivationState.ACTIVE));
        Map<String, JsonValue> fields = fields(object(current));
        fields.remove(CoreGraphAssetCodec.ASSET_ACTIVATION_STATE);
        fields.put(CoreGraphAssetCodec.ASSET_FORMAT_VERSION, JsonValue.of(3));
        fields.remove(CoreGraphAssetCodec.ASSET_HASH);
        fields.put(CoreGraphAssetCodec.ASSET_HASH, JsonValue.of("0".repeat(64)));
        fields.put(CoreGraphAssetCodec.ASSET_HASH,
            JsonValue.of(codec.assetIntegrityHash(JsonValue.object(fields)).canonicalText()));
        byte[] legacy = JsonValue.object(fields).canonicalBytes();

        CoreGraphAssetCodec.Asset decoded = codec.decode(legacy);

        assertEquals(3, decoded.envelope().assetFormatVersion());
        assertEquals(ResourceActivationState.ACTIVE, decoded.envelope().assetActivationState());
        assertArrayEquals(legacy, codec.encode(decoded));
    }

    @Test
    void failsClosedOnAuthorityIdentityKindEnvelopeAndCanonicalShapeMismatch() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        GraphDocument graph = graph("flow", "authority-flow", 2, OpaqueData.empty());
        byte[] encoded = codec.encode(graph, metadata("flow", 2, ResourceActivationState.ACTIVE), graph.resource());

        assertThrows(IllegalArgumentException.class,
            () -> codec.encode(graph, metadata("function", 2, ResourceActivationState.ACTIVE)));
        assertThrows(IllegalArgumentException.class,
            () -> codec.encode(graph, metadata("flow", 3, ResourceActivationState.ACTIVE)));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode(encoded, resource("flow", "different")));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode((" " + new String(encoded, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));

        Map<String, JsonValue> unknownEnvelope = fields(object(encoded));
        unknownEnvelope.put("assetSecurityFuture", JsonValue.of(true));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.object(unknownEnvelope)));

        Map<String, JsonValue> wrongKind = fields(object(encoded));
        wrongKind.put(CoreGraphAssetCodec.CORE_PAYLOAD_KIND, JsonValue.of("function-source"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.object(wrongKind)));

        GraphDocument foreign = new GraphDocument(new CatalogVersion(1, 0),
            new ServerResourceLocator(SERVER,
                ContractRef.of(new OwnerId("foreign.owner"), new ResourceTypeId("flow")), "authority-flow"),
            2, BINDING, Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        assertThrows(IllegalArgumentException.class,
            () -> codec.encode(foreign, metadata("flow", 2, ResourceActivationState.ACTIVE)));
    }

    @Test
    void tombstonePreservesTheCompleteLocatorAndAllOrderingState() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        ContractRef<ResourceTypeId> type = ContractRef.of(new OwnerId("restudio.resync"),
            new ResourceTypeId("flow"), Map.of("typeFuture", "keep"));
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            new ResourceKey(type, "deleted-flow", Map.of("keyFuture", true)), Map.of("locatorFuture", 7));
        ContentHash priorHash = new ContentHash("a".repeat(64));

        byte[] encoded = codec.encodeTombstone(resource, 8, MUTATION, priorHash);
        CoreGraphAssetCodec.Tombstone tombstone = codec.decodeTombstone(encoded, resource);
        JsonValue.JsonObject object = object(encoded);

        assertEquals(resource, tombstone.resource());
        assertEquals(8, tombstone.revision());
        assertEquals(MUTATION, tombstone.mutationId());
        assertEquals(priorHash, tombstone.priorPayloadHash());
        assertTrue(tombstone.deleted());
        assertEquals(codec.tombstoneIntegrityHash(object), tombstone.integrityHash());
        assertFalse(object.contains(CoreGraphAssetCodec.ASSET_ACTIVATION_STATE));
        assertArrayEquals(encoded, codec.encodeTombstone(tombstone));

        Map<String, JsonValue> changedRevision = fields(object);
        changedRevision.put(CoreGraphAssetCodec.TOMBSTONE_REVISION, JsonValue.of(9));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeTombstone(JsonValue.object(changedRevision)));

        Map<String, JsonValue> unknown = fields(object);
        unknown.put("authority", JsonValue.of("untrusted"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeTombstone(JsonValue.object(unknown)));
    }

    @Test
    void reconstructedAssetsRejectUnrepresentableV3StateAndMismatchedHashes() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        GraphDocument graph = graph("flow", "reconstructed", 4, OpaqueData.empty());
        CoreGraphAssetCodec.Envelope inactiveV3 = new CoreGraphAssetCodec.Envelope("flow", 3, 4,
            MUTATION.toString(), ResourceActivationState.INACTIVE, new ContentHash("0".repeat(64)),
            CoreGraphAssetCodec.GRAPH_DOCUMENT_KIND, CoreGraphAssetCodec.CURRENT_CORE_PAYLOAD_VERSION);

        assertThrows(IllegalArgumentException.class, () -> codec.reconstruct(inactiveV3, graph));

        CoreGraphAssetCodec.Envelope wrongHash = new CoreGraphAssetCodec.Envelope("flow", 4, 4,
            MUTATION.toString(), ResourceActivationState.ACTIVE, new ContentHash("f".repeat(64)),
            CoreGraphAssetCodec.GRAPH_DOCUMENT_KIND, CoreGraphAssetCodec.CURRENT_CORE_PAYLOAD_VERSION);
        assertThrows(IllegalArgumentException.class, () -> codec.reconstruct(wrongHash, graph));

        FunctionSourceDocument source = source("reconstructed-function", 5);
        CoreGraphAssetCodec.Envelope inactiveFunctionV3 = new CoreGraphAssetCodec.Envelope("function", 3, 5,
            MUTATION.toString(), ResourceActivationState.INACTIVE, new ContentHash("0".repeat(64)),
            CoreGraphAssetCodec.FUNCTION_SOURCE_KIND, CoreGraphAssetCodec.CURRENT_CORE_PAYLOAD_VERSION);
        CoreGraphAssetCodec.Envelope wrongFunctionHash = new CoreGraphAssetCodec.Envelope("function", 4, 5,
            MUTATION.toString(), ResourceActivationState.ACTIVE, new ContentHash("f".repeat(64)),
            CoreGraphAssetCodec.FUNCTION_SOURCE_KIND, CoreGraphAssetCodec.CURRENT_CORE_PAYLOAD_VERSION);

        assertThrows(IllegalArgumentException.class, () -> codec.reconstruct(inactiveFunctionV3, source));
        assertThrows(IllegalArgumentException.class, () -> codec.reconstruct(wrongFunctionHash, source));
    }

    @Test
    void repairsOnlyCanonicalOneBehindAssetsByAdvancingThroughANewMutation() {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        GraphDocument graph = graph("command", "one-behind", 4,
            OpaqueData.of(Map.of("futureGraph", Map.of("keep", true))));
        byte[] source = revisionSkew(codec, codec.encode(graph,
            metadata("command", 4, ResourceActivationState.INACTIVE)), 5);
        UUID repairMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertThrows(IllegalArgumentException.class, () -> codec.decode(source, graph.resource()));
        CoreGraphAssetCodec.RevisionSkew skew = codec.decodeOneBehindRevision(source, graph.resource());
        CoreGraphAssetCodec.Asset repaired = codec.repairOneBehindRevision(source, graph.resource(),
            new CoreGraphAssetCodec.Metadata("command", 6, repairMutation, ResourceActivationState.INACTIVE));

        assertEquals(5, skew.source().envelope().assetRevision());
        assertEquals(4, skew.source().graphDocument().revision());
        assertEquals(6, repaired.envelope().assetRevision());
        assertEquals(6, repaired.graphDocument().revision());
        assertEquals(repairMutation.toString(), repaired.envelope().assetMutationId());
        assertEquals(ResourceActivationState.INACTIVE, repaired.envelope().assetActivationState());
        assertEquals(graph.nodes(), repaired.graphDocument().nodes());
        assertEquals(graph.connections(), repaired.graphDocument().connections());
        assertEquals(graph.variables(), repaired.graphDocument().variables());
        assertEquals(graph.functions(), repaired.graphDocument().functions());
        assertEquals(graph.unknown(), repaired.graphDocument().unknown());
        assertArrayEquals(codec.encode(repaired), codec.encode(codec.decode(codec.encode(repaired), graph.resource())));

        assertThrows(IllegalArgumentException.class, () -> codec.repairOneBehindRevision(source, graph.resource(),
            new CoreGraphAssetCodec.Metadata("command", 5, repairMutation, ResourceActivationState.INACTIVE)));
        assertThrows(IllegalArgumentException.class, () -> codec.repairOneBehindRevision(source, graph.resource(),
            new CoreGraphAssetCodec.Metadata("command", 6, MUTATION, ResourceActivationState.INACTIVE)));
        assertThrows(IllegalArgumentException.class, () -> codec.repairOneBehindRevision(source, graph.resource(),
            new CoreGraphAssetCodec.Metadata("command", 6, repairMutation, ResourceActivationState.ACTIVE)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeOneBehindRevision(
            revisionSkew(codec, codec.encode(graph, metadata("command", 4, ResourceActivationState.INACTIVE)), 6),
            graph.resource()));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeOneBehindRevision(
            (" " + new String(source, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), graph.resource()));
    }

    private static CoreGraphAssetCodec.Metadata metadata(String type, long revision,
                                                         ResourceActivationState activationState) {
        return new CoreGraphAssetCodec.Metadata(type, revision, MUTATION, activationState);
    }

    private static GraphDocument graph(String type, String id, long revision, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), unknown);
    }

    private static FunctionSourceDocument source(String id, long revision) {
        ServerResourceLocator resource = resource("function", id);
        GraphDocument graph = graph("function", id, revision,
            OpaqueData.of(Map.of("graphFuture", Map.of("keep", true))));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("signatureFuture", Map.of("keep", true)));
        return new FunctionSourceDocument(signature, graph,
            OpaqueData.of(Map.of("sourceFuture", List.of(true, "keep"))));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(type)), id);
    }

    private static JsonValue.JsonObject object(byte[] bytes) {
        return CanonicalCodec.requireObject(CanonicalCodec.decode(bytes));
    }

    private static byte[] revisionSkew(CoreGraphAssetCodec codec, byte[] encoded, long assetRevision) {
        Map<String, JsonValue> values = fields(object(encoded));
        values.put(CoreGraphAssetCodec.ASSET_REVISION, JsonValue.of(assetRevision));
        values.remove(CoreGraphAssetCodec.ASSET_HASH);
        values.put(CoreGraphAssetCodec.ASSET_HASH, JsonValue.of("0".repeat(64)));
        JsonValue.JsonObject unhashed = JsonValue.object(values);
        values.put(CoreGraphAssetCodec.ASSET_HASH,
            JsonValue.of(codec.assetIntegrityHash(unhashed).canonicalText()));
        return JsonValue.object(values).canonicalBytes();
    }

    private static Map<String, JsonValue> fields(JsonValue.JsonObject object) {
        return new LinkedHashMap<>(object.fields());
    }

    private static String text(JsonValue.JsonObject object, String field) {
        return ((JsonValue.JsonString) object.value(field)).value();
    }

    private static long number(JsonValue.JsonObject object, String field) {
        return ((JsonValue.JsonNumber) object.value(field)).value().longValueExact();
    }
}
