package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
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
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreGraphWorkspaceDocumentProviderTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));

    @TempDir
    Path directory;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

    @AfterEach
    void tearDown() throws Exception {
        if (assetsGate != null) {
            assetsGate.quiesce();
        }
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void loadsAndPersistsTheExactCoreGraphWithIndependentMutationReplay() {
        FlowStorage storage = storage();
        GraphDocument original = graph("flow", "workspace", 1, Map.of("future", "original"));
        storage.saveCoreGraph(original, ResourceActivationState.INACTIVE, uuid("22222222-2222-4222-8222-222222222222"), 0L);
        CoreGraphWorkspaceDocumentProvider provider = new CoreGraphWorkspaceDocumentProvider(storage);
        JsonObject draft = provider.load("flow", "workspace");
        draft.addProperty("future", "saved");
        UUID mutation = uuid("33333333-3333-4333-8333-333333333333");

        JsonObject saved = provider.persist("flow", "workspace", draft, mutation, 1L);
        JsonObject replay = provider.persist("flow", "workspace", draft, mutation, 1L);
        CoreGraphStorageBoundary.Decoded durable = storage.getCoreGraph("flow", "workspace").orElseThrow();

        assertEquals(saved, replay);
        assertEquals(2L, durable.envelope().assetRevision());
        assertEquals(ResourceActivationState.INACTIVE, durable.envelope().assetActivationState());
        assertEquals(2L, durable.graphDocument().revision());
        assertEquals("saved", durable.graphDocument().unknown().get("future"));
    }

    @Test
    void preservesFunctionSignatureWhileAdvancingItsGraphRevision() {
        FlowStorage storage = storage();
        GraphDocument graph = graph("function", "compute", 1, Map.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(graph.resource()), FunctionRevision.of(1), List.of(), List.of(),
            Map.of("futureSignature", true));
        storage.saveCoreGraph(new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("futureSource", true))), ResourceActivationState.ACTIVE,
            uuid("44444444-4444-4444-8444-444444444444"), 0L);
        CoreGraphWorkspaceDocumentProvider provider = new CoreGraphWorkspaceDocumentProvider(storage);
        JsonObject draft = provider.load("function", "compute");
        draft.getAsJsonObject("graph").addProperty("futureEdit", true);

        provider.persist("function", "compute", draft, uuid("55555555-5555-4555-8555-555555555555"), 1L);
        FunctionSourceDocument saved = storage.getCoreGraph("function", "compute").orElseThrow().functionSourceDocument();

        assertEquals(2L, saved.graph().revision());
        JsonObject conflicting = draft.deepCopy();
        conflicting.getAsJsonObject("signature").addProperty("futureSignature", false);
        assertThrows(IllegalStateException.class, () -> provider.persist("function", "compute", conflicting,
            uuid("55555555-5555-4555-8555-555555555555"), 1L));
        assertEquals(2L, saved.signature().revision().value());
        assertEquals(true, saved.signature().unknown().get("futureSignature"));
        assertEquals(true, saved.unknown().get("futureSource"));
    }

    @Test
    void rejectsWorkspaceChangesToManagedIdentityAndRevision() {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph("flow", "managed", 1, Map.of()), ResourceActivationState.ACTIVE,
            uuid("66666666-6666-4666-8666-666666666666"), 0L);
        CoreGraphWorkspaceDocumentProvider provider = new CoreGraphWorkspaceDocumentProvider(storage);
        JsonObject draft = provider.load("flow", "managed");
        draft.addProperty("revision", 9);

        assertThrows(IllegalArgumentException.class, () -> provider.persist("flow", "managed", draft,
            uuid("77777777-7777-4777-8777-777777777777"), 1L));
    }

    @Test
    void rejectsRawGraphDocumentsAndPreservesExactJsonNumbers() {
        FlowStorage storage = storage();
        GraphDocument graph = graph("flow", "exact", 1, Map.of());
        storage.saveCoreGraph(graph, ResourceActivationState.ACTIVE, uuid("88888888-8888-4888-8888-888888888888"), 0L);
        CoreGraphWorkspaceDocumentProvider provider = new CoreGraphWorkspaceDocumentProvider(storage);
        ServerId foreignServer = new ServerId(uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        ServerResourceLocator foreignResource = new ServerResourceLocator(foreignServer, graph.resource().type(), "exact");
        GraphDocument foreign = new GraphDocument(graph.schemaVersion(), foreignResource, graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
        String foreignEnvelope = new CoreGraphStorageBoundary().encodeText(foreign,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")));

        assertEquals(null, provider.project("flow", "exact", graph.canonicalJson()));
        assertEquals(null, provider.project("flow", "exact", foreignEnvelope));
        assertEquals("{\"decimal\":1.23,\"integer\":9007199254740993}", GsonJsonValues.convert(JsonParser.parseString(
            "{\"integer\":9007199254740993,\"decimal\":1.2300}")).canonicalText());
    }

    private FlowStorage storage() {
        assetsGate = new AssetPersistenceGate(directory);
        try {
            coordinator = new AssetTransactionCoordinator(directory.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open Core graph workspace test persistence", exception);
        }
        return new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), assetsGate, SERVER, coordinator);
    }

    private static GraphDocument graph(String type, String id, long revision, Map<String, ?> unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(), List.of(), List.of(), List.of(), List.of(),
            OpaqueData.of(unknown));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
