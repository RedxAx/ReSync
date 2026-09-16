package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

class CoreGraphStorageBoundaryTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)),
        new ContentHash("1".repeat(64)));
    private static final String MUTATION = "22222222-2222-4222-8222-222222222222";

    @Test
    void roundTripsGraphAndFunctionPayloadsWithOpaqueData() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "round-trip-flow", 4, OpaqueData.of(Map.of("futureGraph", Map.of("enabled", true))));
        FunctionSourceDocument source = source("round-trip-function", 7);

        byte[] graphBytes = boundary.encode(graph, metadata("flow", 4, MUTATION));
        byte[] functionBytes = boundary.encode(source, metadata("function", 7, MUTATION));
        CoreGraphStorageBoundary.Decoded decodedGraph = boundary.decode(graphBytes);
        CoreGraphStorageBoundary.Decoded decodedFunction = boundary.decode(functionBytes);

        assertEquals(CoreGraphStorageBoundary.GRAPH_DOCUMENT_KIND, decodedGraph.corePayloadKind());
        assertEquals(graph.resource(), decodedGraph.graphDocument().resource());
        assertEquals(graph.revision(), decodedGraph.graphDocument().revision());
        assertEquals(graph.unknown(), decodedGraph.graphDocument().unknown());
        assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND, decodedFunction.corePayloadKind());
        assertEquals(source.signature().function(), decodedFunction.functionSourceDocument().signature().function());
        assertEquals(source.signature().revision(), decodedFunction.functionSourceDocument().signature().revision());
        assertEquals(source.unknown(), decodedFunction.functionSourceDocument().unknown());
        assertArrayEquals(graphBytes, boundary.encode(decodedGraph));
        assertArrayEquals(functionBytes, boundary.encode(decodedFunction));
    }

    @Test
    void graphFunctionIdentityAndRevisionMustMatchTheAssetEnvelope() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "identity-flow", 3, OpaqueData.empty());
        FunctionSourceDocument source = source("identity-function", 8);

        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph, metadata("function", 3, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(source, metadata("flow", 8, MUTATION)));

        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph, metadata("flow", 99, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(source, metadata("function", 101, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph("function", "identity-function-graph", 3, OpaqueData.empty()),
                metadata("function", 99, MUTATION)));
    }

    @Test
    void assetIntegrityHashDoesNotContaminateCoreChecksums() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "hash-flow", 5, OpaqueData.empty());
        FunctionSourceDocument source = source("hash-function", 6);

        byte[] graphCore = GraphDocumentCodec.INSTANCE.encode(graph).canonicalBytes();
        byte[] functionCore = FunctionSourceDocumentCodec.INSTANCE.encode(source).canonicalBytes();
        CoreGraphStorageBoundary.Decoded decodedGraph = boundary.decode(boundary.encode(graph, metadata("flow", 5, MUTATION)));
        CoreGraphStorageBoundary.Decoded decodedFunction = boundary.decode(boundary.encode(source,
            metadata("function", 6, MUTATION)));

        assertArrayEquals(graphCore, GraphDocumentCodec.INSTANCE.encode(decodedGraph.graphDocument()).canonicalBytes());
        assertArrayEquals(functionCore, FunctionSourceDocumentCodec.INSTANCE.encode(decodedFunction.functionSourceDocument()).canonicalBytes());
        assertNotEquals(graph.checksum(), decodedGraph.envelope().assetHash());
        assertNotEquals(source.checksum(), decodedFunction.envelope().assetHash());
        assertFalse(decodedGraph.envelope().assetHash().equals(graph.checksum()));
        assertFalse(decodedFunction.envelope().assetHash().equals(source.checksum()));
    }

    @Test
    void activationStateIsOuterMetadataAndPartOfTheAssetHash() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "activation-flow", 5, OpaqueData.empty());

        byte[] active = boundary.encode(graph, metadata("flow", 5, MUTATION));
        byte[] inactive = boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 5, MUTATION, ResourceActivationState.INACTIVE));
        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(inactive);

        assertEquals(ResourceActivationState.INACTIVE, decoded.envelope().assetActivationState());
        assertArrayEquals(GraphDocumentCodec.INSTANCE.encode(graph).canonicalBytes(),
            GraphDocumentCodec.INSTANCE.encode(decoded.graphDocument()).canonicalBytes());
        assertNotEquals(new String(active, StandardCharsets.UTF_8), new String(inactive, StandardCharsets.UTF_8));
        assertNotEquals(boundary.decode(active).envelope().assetHash(), decoded.envelope().assetHash());
    }

    @Test
    void acceptsVersionThreeAsActiveCompatibilityAndKeepsItCanonical() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] current = boundary.encode(graph("flow", "legacy-envelope-flow", 6, OpaqueData.empty()),
            metadata("flow", 6, MUTATION));
        JsonValue.JsonObject currentObject = assertObject(current);
        Map<String, JsonValue> legacyFields = new LinkedHashMap<>(currentObject.fields());
        legacyFields.remove(CoreGraphStorageBoundary.ASSET_ACTIVATION_STATE);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_FORMAT_VERSION, JsonValue.of(3));
        legacyFields.remove(CoreGraphStorageBoundary.ASSET_HASH);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_HASH, JsonValue.of("0".repeat(64)));
        JsonValue.JsonObject legacyObject = JsonValue.object(legacyFields);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_HASH,
            JsonValue.of(boundary.assetIntegrityHash(legacyObject).canonicalText()));
        byte[] legacy = JsonValue.object(legacyFields).canonicalBytes();

        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(legacy);

        assertEquals(3, decoded.envelope().assetFormatVersion());
        assertEquals(ResourceActivationState.ACTIVE, decoded.envelope().assetActivationState());
        assertArrayEquals(legacy, boundary.encode(decoded));
    }

    @Test
    void exactExpectedLocatorIsValidatedOnEncodeAndDecode() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "expected-locator-flow", 7, OpaqueData.empty());
        ServerResourceLocator expected = resource("flow", "expected-locator-flow");
        byte[] encoded = boundary.encode(graph, metadata("flow", 7, MUTATION), expected);

        assertEquals(graph.resource(), boundary.decode(encoded, expected).graphDocument().resource());
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph, metadata("flow", 7, MUTATION), resource("flow", "other-flow")));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.decode(encoded, resource("command", "expected-locator-flow")));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.decode(encoded, new ServerResourceLocator(UUID.fromString("33333333-3333-4333-8333-333333333333"),
                expected.key())));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.decode(encoded, new ServerResourceLocator(SERVER,
                new ContractRef<>(new OwnerId("foreign.owner"),
                    new ResourceTypeId("flow")), expected.id())));
    }

    @Test
    void rejectsUnknownMissingAndMismatchedEnvelopeData() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] encoded = boundary.encode(graph("flow", "reject-flow", 2, OpaqueData.empty()),
            metadata("flow", 2, MUTATION));
        JsonValue.JsonObject object = assertObject(encoded);

        Map<String, JsonValue> unknown = new LinkedHashMap<>(object.fields());
        unknown.put("assetUnexpected", JsonValue.of(true));
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(JsonValue.object(unknown)));

        Map<String, JsonValue> missing = new LinkedHashMap<>(object.fields());
        missing.remove(CoreGraphStorageBoundary.ASSET_REVISION);
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(JsonValue.object(missing)));

        Map<String, JsonValue> wrongKind = new LinkedHashMap<>(object.fields());
        wrongKind.put(CoreGraphStorageBoundary.CORE_PAYLOAD_KIND, JsonValue.of("unsupported"));
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(JsonValue.object(wrongKind)));

        Map<String, JsonValue> wrongVersion = new LinkedHashMap<>(object.fields());
        wrongVersion.put(CoreGraphStorageBoundary.CORE_PAYLOAD_VERSION, JsonValue.of(2));
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(JsonValue.object(wrongVersion)));

        Map<String, JsonValue> wrongHash = new LinkedHashMap<>(object.fields());
        wrongHash.put(CoreGraphStorageBoundary.ASSET_HASH, JsonValue.of("f".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(JsonValue.object(wrongHash)));
    }

    @Test
    void encodeRejectsEveryUnknownEnvelopeFieldFamilyThatDecodeRejects() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();

        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph("flow", "asset-prefix-flow", 2,
                OpaqueData.of(Map.of("assetUnexpected", true))), metadata("flow", 2, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph("flow", "core-prefix-flow", 2,
                OpaqueData.of(Map.of("corePayloadFuture", true))), metadata("flow", 2, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(graph("flow", "legacy-field-flow", 2,
                OpaqueData.of(Map.of("contentHash", true))), metadata("flow", 2, MUTATION)));

        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(source("asset-prefix-function", 2,
                OpaqueData.of(Map.of("assetUnexpected", true))), metadata("function", 2, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(source("core-prefix-function", 2,
                OpaqueData.of(Map.of("corePayloadFuture", true))), metadata("function", 2, MUTATION)));
        assertThrows(IllegalArgumentException.class,
            () -> boundary.encode(source("legacy-field-function", 2,
                OpaqueData.of(Map.of("contentHash", true))), metadata("function", 2, MUTATION)));
    }

    @Test
    void rejectsDuplicateAndNonCanonicalEnvelopeBytes() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] encoded = boundary.encode(graph("flow", "canonical-flow", 1, OpaqueData.empty()),
            metadata("flow", 1, MUTATION));
        String canonical = new String(encoded, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> boundary.decode((" " + canonical).getBytes(StandardCharsets.UTF_8)));
        String duplicate = canonical.substring(0, canonical.length() - 1)
            + ",\"assetRevision\":1}";
        assertThrows(IllegalArgumentException.class, () -> boundary.decode(duplicate.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void canonicalEnvelopeBytesAreStableAcrossReencoding() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph("flow", "stable-flow", 11,
            OpaqueData.of(Map.of("zFuture", List.of("last", true), "aFuture", Map.of("value", 4))));
        CoreGraphStorageBoundary.AssetMetadata metadata = metadata("flow", 11, MUTATION);

        byte[] first = boundary.encode(graph, metadata);
        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(first);
        byte[] second = boundary.encode(decoded);
        byte[] third = boundary.encode(decoded.graphDocument(), decoded.envelope().metadata());

        assertArrayEquals(first, second);
        assertArrayEquals(second, third);
        assertEquals(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
        assertTrue(Arrays.equals(first, CanonicalCodec.decode(first).canonicalBytes()));
    }

    @Test
    void preservesOpaqueCoreFieldsWithoutTreatingThemAsEnvelopeFields() {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        Map<String, Object> opaque = Map.of("futureField", Map.of("ordered", List.of("one", 2, false)));
        GraphDocument graph = graph("flow", "opaque-flow", 12, OpaqueData.of(opaque));

        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(boundary.encode(graph, metadata("flow", 12, MUTATION)));

        assertEquals(JsonValue.fromJava(opaque).canonicalText(),
            JsonValue.fromJava(decoded.graphDocument().unknown().fields()).canonicalText());
    }

    private static CoreGraphStorageBoundary.AssetMetadata metadata(String type, long revision, String mutation) {
        return new CoreGraphStorageBoundary.AssetMetadata(type, revision, mutation);
    }

    private static GraphDocument graph(String type, String id, long revision, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), unknown);
    }

    private static FunctionSourceDocument source(String id, long revision) {
        return source(id, revision, OpaqueData.of(Map.of("sourceFuture", List.of(true, "keep"))));
    }

    private static FunctionSourceDocument source(String id, long revision, OpaqueData unknown) {
        ServerResourceLocator resource = resource("function", id);
        GraphDocument graph = graph("function", id, revision, OpaqueData.of(Map.of("graphFuture", Map.of("keep", true))));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("signatureFuture", Map.of("keep", true)));
        return new FunctionSourceDocument(signature, graph, unknown);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(type)), id);
    }

    private static JsonValue.JsonObject assertObject(byte[] value) {
        return (JsonValue.JsonObject) CanonicalCodec.decode(value);
    }
}
