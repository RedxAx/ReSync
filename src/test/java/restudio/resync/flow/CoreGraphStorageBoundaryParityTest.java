package restudio.resync.flow;

import org.junit.jupiter.api.Test;
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
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.storage.CoreGraphAssetCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreGraphStorageBoundaryParityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)),
        new ContentHash("1".repeat(64)));
    private static final String GOLDEN_V4_GRAPH = "{" +
        "\"assetActivationState\":\"inactive\",\"assetFormatVersion\":4," +
        "\"assetHash\":\"9b835bd9e325501db64198736699a2e97e702b720771346fdcc40e3111ceda94\"," +
        "\"assetMutationId\":\"22222222-2222-4222-8222-222222222222\",\"assetRevision\":3," +
        "\"catalogBinding\":{\"bindingManifestHash\":\"" + "1".repeat(64) +
        "\",\"catalogChecksum\":\"" + "0".repeat(64) + "\",\"generation\":1}," +
        "\"connections\":[],\"corePayloadKind\":\"graph-document\",\"corePayloadVersion\":1," +
        "\"futureGraph\":{\"keep\":[true,7]},\"nodes\":[],\"requiredCapabilities\":[]," +
        "\"resource\":{\"id\":\"golden-flow\",\"serverId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"type\":{\"localId\":\"flow\",\"ownerId\":\"restudio.resync\"}},\"resourceType\":\"flow\"," +
        "\"revision\":3,\"schemaVersion\":{\"generation\":1,\"minor\":0}}";
    private static final String GOLDEN_V3_GRAPH = "{" +
        "\"assetFormatVersion\":3," +
        "\"assetHash\":\"acde826f65e8a6dfbf4b109d2e517dc4665d1aae74c2d21de39cd5484378ae76\"," +
        "\"assetMutationId\":\"22222222-2222-4222-8222-222222222222\",\"assetRevision\":3," +
        "\"catalogBinding\":{\"bindingManifestHash\":\"" + "1".repeat(64) +
        "\",\"catalogChecksum\":\"" + "0".repeat(64) + "\",\"generation\":1}," +
        "\"connections\":[],\"corePayloadKind\":\"graph-document\",\"corePayloadVersion\":1," +
        "\"futureGraph\":{\"keep\":[true,7]},\"nodes\":[],\"requiredCapabilities\":[]," +
        "\"resource\":{\"id\":\"golden-flow\",\"serverId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"type\":{\"localId\":\"flow\",\"ownerId\":\"restudio.resync\"}},\"resourceType\":\"flow\"," +
        "\"revision\":3,\"schemaVersion\":{\"generation\":1,\"minor\":0}}";
    private static final String GOLDEN_V4_FUNCTION = "{" +
        "\"assetActivationState\":\"inactive\",\"assetFormatVersion\":4," +
        "\"assetHash\":\"317642602b7dbc6e2984a3801e243da6e69fb44e4e81d04ce51c5bdf7ece41d3\"," +
        "\"assetMutationId\":\"22222222-2222-4222-8222-222222222222\",\"assetRevision\":6," +
        "\"corePayloadKind\":\"function-source\",\"corePayloadVersion\":1,\"graph\":{" +
        "\"catalogBinding\":{\"bindingManifestHash\":\"" + "1".repeat(64) +
        "\",\"catalogChecksum\":\"" + "0".repeat(64) + "\",\"generation\":1}," +
        "\"connections\":[],\"graphFuture\":{\"keep\":true},\"nodes\":[],\"requiredCapabilities\":[]," +
        "\"resource\":{\"id\":\"golden-function\",\"serverId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"type\":{\"localId\":\"function\",\"ownerId\":\"restudio.resync\"}},\"revision\":6," +
        "\"schemaVersion\":{\"generation\":1,\"minor\":0}},\"resourceType\":\"function\",\"signature\":{" +
        "\"function\":{\"id\":\"golden-function\",\"serverId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"type\":{\"localId\":\"function\",\"ownerId\":\"restudio.resync\"}}," +
        "\"inputs\":[],\"outputs\":[],\"revision\":6,\"signatureFuture\":{\"keep\":true}}," +
        "\"sourceFuture\":[true,\"keep\"]}";
    private static final String GOLDEN_TOMBSTONE = "{" +
        "\"deleted\":true,\"formatVersion\":1,\"kind\":\"core-graph-tombstone\"," +
        "\"mutationId\":\"22222222-2222-4222-8222-222222222222\",\"payloadHash\":\"" + "a".repeat(64) +
        "\",\"resource\":{\"id\":\"deleted-flow\",\"serverId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"type\":{\"localId\":\"flow\",\"ownerId\":\"restudio.resync\"}},\"revision\":8," +
        "\"tombstoneHash\":\"6d4a21b1d9b427ea494c93d4e3697d891df3cb5a0fbdd9fad7db9e0c74f223ca\"}";

    @Test
    void adapterAndCoreMatchFrozenV4InactiveGraphAndFunctionBytes() {
        CoreGraphStorageBoundary adapter = new CoreGraphStorageBoundary();
        CoreGraphAssetCodec core = new CoreGraphAssetCodec();
        GraphDocument graph = graph("flow", "golden-flow", 3,
            OpaqueData.of(Map.of("futureGraph", Map.of("keep", List.of(true, 7)))));

        byte[] adapterGraph = adapter.encode(graph, new CoreGraphStorageBoundary.AssetMetadata("flow", 3,
            MUTATION, ResourceActivationState.INACTIVE));
        byte[] coreGraph = core.encode(graph, new CoreGraphAssetCodec.Metadata("flow", 3, MUTATION,
            ResourceActivationState.INACTIVE));

        assertArrayEquals(GOLDEN_V4_GRAPH.getBytes(StandardCharsets.UTF_8), adapterGraph);
        assertArrayEquals(adapterGraph, coreGraph);

        FunctionSourceDocument source = source("golden-function", 6);
        byte[] adapterFunction = adapter.encode(source, new CoreGraphStorageBoundary.AssetMetadata("function", 6,
            MUTATION, ResourceActivationState.INACTIVE));
        byte[] coreFunction = core.encode(source, new CoreGraphAssetCodec.Metadata("function", 6, MUTATION,
            ResourceActivationState.INACTIVE));

        assertArrayEquals(GOLDEN_V4_FUNCTION.getBytes(StandardCharsets.UTF_8), adapterFunction);
        assertArrayEquals(adapterFunction, coreFunction);
        assertArrayEquals(adapterFunction, adapter.encode(adapter.decode(adapterFunction)));
        assertArrayEquals(coreFunction, core.encode(core.decode(coreFunction)));
    }

    @Test
    void adapterAndCorePreserveFrozenV3BytesAsActive() {
        byte[] golden = GOLDEN_V3_GRAPH.getBytes(StandardCharsets.UTF_8);
        CoreGraphStorageBoundary adapter = new CoreGraphStorageBoundary();
        CoreGraphAssetCodec core = new CoreGraphAssetCodec();
        CoreGraphStorageBoundary.Decoded adapterDecoded = adapter.decode(golden);
        CoreGraphAssetCodec.Asset coreDecoded = core.decode(golden);

        assertEquals(3, adapterDecoded.envelope().assetFormatVersion());
        assertEquals(ResourceActivationState.ACTIVE, adapterDecoded.envelope().assetActivationState());
        assertEquals(3, coreDecoded.envelope().assetFormatVersion());
        assertEquals(ResourceActivationState.ACTIVE, coreDecoded.envelope().assetActivationState());
        assertArrayEquals(golden, adapter.encode(adapterDecoded));
        assertArrayEquals(golden, core.encode(coreDecoded));
    }

    @Test
    void adapterAndCoreMatchFrozenTombstoneBytes() {
        CoreGraphStorageBoundary adapter = new CoreGraphStorageBoundary();
        CoreGraphAssetCodec core = new CoreGraphAssetCodec();
        ServerResourceLocator resource = resource("flow", "deleted-flow");
        ContentHash priorHash = new ContentHash("a".repeat(64));
        byte[] golden = GOLDEN_TOMBSTONE.getBytes(StandardCharsets.UTF_8);

        byte[] adapterBytes = adapter.encodeTombstone(resource, 8, MUTATION, priorHash);
        byte[] coreBytes = core.encodeTombstone(resource, 8, MUTATION, priorHash);

        assertArrayEquals(golden, adapterBytes);
        assertArrayEquals(adapterBytes, coreBytes);
        assertArrayEquals(golden, adapter.encodeTombstone(adapter.decodeTombstone(golden, resource)));
        assertArrayEquals(golden, core.encodeTombstone(core.decodeTombstone(golden, resource)));
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
}
