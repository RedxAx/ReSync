package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCatalogCompatibilityRebindTest {
    private static final ServerId SERVER = ServerId.deterministic("compatible-core-catalog-rebind-test");
    private static final ContentHash SOURCE_CHECKSUM = new ContentHash("a".repeat(64));
    private static final ContentHash TARGET_CHECKSUM = new ContentHash("d".repeat(64));
    private static final CatalogBinding SOURCE = new CatalogBinding(54L, SOURCE_CHECKSUM, new ContentHash("b".repeat(64)));
    private static final CatalogBinding TARGET = new CatalogBinding(55L, TARGET_CHECKSUM, new ContentHash("c".repeat(64)));
    private static final UUID SOURCE_MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REBIND_MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void rebindsAGraphWithoutChangingTopologyOrOpaquePayload() {
        ServerResourceLocator resource = resource("command", "test");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 6L, SOURCE, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("preserve", List.of("exact", 7L))));
        CoreGraphStorageBoundary.Decoded source = decoded(graph, "command", SOURCE_MUTATION);

        CoreGraphStorageBoundary.Decoded rebound = CoreCatalogCompatibilityRebind.project(source, TARGET,
            REBIND_MUTATION);

        assertTrue(CoreCatalogCompatibilityRebind.eligible(source, TARGET));
        assertEquals(7L, rebound.envelope().assetRevision());
        assertEquals(7L, rebound.graphDocument().revision());
        assertEquals(REBIND_MUTATION.toString(), rebound.envelope().assetMutationId());
        assertEquals(TARGET, rebound.graphDocument().catalogBinding());
        assertEquals(graph.nodes(), rebound.graphDocument().nodes());
        assertEquals(graph.connections(), rebound.graphDocument().connections());
        assertEquals(graph.variables(), rebound.graphDocument().variables());
        assertEquals(graph.functions(), rebound.graphDocument().functions());
        assertEquals(graph.requiredCapabilities(), rebound.graphDocument().requiredCapabilities());
        assertEquals(source.graphDocument().unknown(), rebound.graphDocument().unknown());
        assertEquals(source.envelope().assetActivationState(), rebound.envelope().assetActivationState());
        assertNotEquals(source.envelope().assetHash(), rebound.envelope().assetHash());
        assertFalse(CoreCatalogCompatibilityRebind.exactProjection(resource, source, rebound, TARGET));
        UUID expectedMutation = CoreCatalogCompatibilityRebind.mutationId(resource, source, TARGET);
        CoreGraphStorageBoundary.Decoded exact = CoreCatalogCompatibilityRebind.project(source, TARGET, expectedMutation);
        assertTrue(CoreCatalogCompatibilityRebind.exactProjection(resource, source, exact, TARGET));
        assertFalse(CoreCatalogCompatibilityRebind.exactProjection(resource, source, source, TARGET));
        assertFalse(CoreCatalogCompatibilityRebind.exactProjection(resource, source, exact,
            new CatalogBinding(56L, TARGET_CHECKSUM, new ContentHash("c".repeat(64)))));
    }

    @Test
    void rebindsAFunctionWithOneExactGraphAndSignatureRevisionAdvance() {
        ServerResourceLocator resource = resource("function", "historical");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 9L, SOURCE, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("graph", true)));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(9L),
            List.of(), List.of(), Map.of("signature", "preserve"));
        FunctionSourceDocument function = new FunctionSourceDocument(signature, graph,
            OpaqueData.of(Map.of("source", "preserve")));
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        CoreGraphStorageBoundary.Decoded source = boundary.decode(boundary.encode(function,
            new CoreGraphStorageBoundary.AssetMetadata("function", 9L, SOURCE_MUTATION,
                ResourceActivationState.INACTIVE), resource), resource);

        CoreGraphStorageBoundary.Decoded rebound = CoreCatalogCompatibilityRebind.project(source, TARGET,
            REBIND_MUTATION);

        assertEquals(10L, rebound.functionSourceDocument().graph().revision());
        assertEquals(10L, rebound.functionSourceDocument().signature().revision().value());
        assertEquals(signature.inputs(), rebound.functionSourceDocument().signature().inputs());
        assertEquals(signature.outputs(), rebound.functionSourceDocument().signature().outputs());
        assertEquals(signature.unknown(), rebound.functionSourceDocument().signature().unknown());
        assertEquals(function.unknown(), rebound.functionSourceDocument().unknown());
        assertEquals(ResourceActivationState.INACTIVE, rebound.envelope().assetActivationState());
    }

    @Test
    void admitsChangedCatalogContentOnlyForAForwardGeneration() {
        ServerResourceLocator resource = resource("flow", "incompatible");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 2L, SOURCE, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphStorageBoundary.Decoded source = decoded(graph, "flow", SOURCE_MUTATION);

        assertFalse(CoreCatalogCompatibilityRebind.eligible(source, SOURCE));
        assertFalse(CoreCatalogCompatibilityRebind.eligible(source,
            new CatalogBinding(53L, TARGET_CHECKSUM, new ContentHash("e".repeat(64)))));
        assertTrue(CoreCatalogCompatibilityRebind.eligible(source,
            new CatalogBinding(55L, new ContentHash("e".repeat(64)), new ContentHash("f".repeat(64)))));
        assertThrows(IllegalArgumentException.class, () -> CoreCatalogCompatibilityRebind.project(source,
            new CatalogBinding(53L, new ContentHash("e".repeat(64)), new ContentHash("f".repeat(64))),
            REBIND_MUTATION));
    }

    private static CoreGraphStorageBoundary.Decoded decoded(GraphDocument graph, String type, UUID mutationId) {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        return boundary.decode(boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(type,
            graph.revision(), mutationId), graph.resource()), graph.resource());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }
}
