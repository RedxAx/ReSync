package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageCoreWriteTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void savesCanonicalGraphWithMetadataAndActivationAtomically() throws Exception {
        FlowStorage storage = storage(SERVER);
        UUID mutation = UUID.fromString("22222222-2222-4222-8222-222222222222");

        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(graph("flow", "main", 1),
            ResourceActivationState.INACTIVE, mutation, 0L);

        assertEquals(CoreGraphStorageBoundary.GRAPH_DOCUMENT_KIND, saved.corePayloadKind());
        assertEquals(1L, saved.envelope().assetRevision());
        assertEquals(ResourceActivationState.INACTIVE, saved.envelope().assetActivationState());
        assertEquals(saved.envelope(), storage.getCoreGraph("flow", "main").orElseThrow().envelope());
        assertTrue(Files.isRegularFile(tempDir.resolve("assets/Blueprints/Flows/main.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("assets/project.json")));
        assertFalse(Files.exists(tempDir.resolve("flows/main.json")));
        assertFalse(Files.exists(tempDir.resolve("assets/.tombstones/flow/main.json")));
    }

    @Test
    void enforcesExpectedRevisionAndIdempotentMutationReplay() {
        FlowStorage storage = storage(SERVER);
        GraphDocument first = graph("flow", "revisions", 1);
        UUID firstMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");

        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(first, ResourceActivationState.ACTIVE,
            firstMutation, 0L);
        CoreGraphStorageBoundary.Decoded replay = storage.saveCoreGraph(graph("flow", "revisions", 1),
            ResourceActivationState.ACTIVE, firstMutation, 0L);

        assertEquals(saved.envelope(), replay.envelope());
        assertThrows(IllegalStateException.class,
            () -> storage.saveCoreGraph(graph("flow", "revisions", 1, "different"), ResourceActivationState.ACTIVE,
                firstMutation, 0L));

        UUID secondMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
        CoreGraphStorageBoundary.Decoded second = storage.saveCoreGraph(graph("flow", "revisions", 2),
            ResourceActivationState.ACTIVE, secondMutation, 1L);
        assertEquals(2L, second.envelope().assetRevision());
        assertThrows(ResourceRevisionConflictException.class,
            () -> storage.saveCoreGraph(graph("flow", "revisions", 3), ResourceActivationState.ACTIVE,
                UUID.fromString("55555555-5555-4555-8555-555555555555"), 1L));
    }

    @Test
    void replaysACommittedSaveAfterRestart() {
        UUID mutation = UUID.fromString("66666666-6666-4666-8666-666666666666");
        GraphDocument graph = graph("flow", "restart-save", 1);

        CoreGraphStorageBoundary.Decoded saved = storage(SERVER).saveCoreGraph(graph,
            ResourceActivationState.ACTIVE, mutation, 0L);
        FlowStorage restarted = storage(SERVER);

        CoreGraphStorageBoundary.Decoded replay = restarted.saveCoreGraph(graph,
            ResourceActivationState.ACTIVE, mutation, 0L);

        assertEquals(saved.envelope(), replay.envelope());
        assertEquals(saved.graphDocument().checksum(), replay.graphDocument().checksum());
    }

    @Test
    void rejectsAnUncoordinatedEnvelopeChangeEvenWithRecomputedHash() throws Exception {
        UUID mutation = UUID.fromString("77777777-7777-4777-8777-777777777777");
        GraphDocument graph = graph("flow", "restart-v3-save", 1);
        FlowStorage first = storage(SERVER);
        CoreGraphStorageBoundary.Decoded saved = first.saveCoreGraph(graph,
            ResourceActivationState.ACTIVE, mutation, 0L);
        Path asset = tempDir.resolve("assets/Blueprints/Flows/restart-v3-save.json");
        JsonValue.JsonObject current = CanonicalCodec.requireObject(CanonicalCodec.decode(Files.readAllBytes(asset)));
        LinkedHashMap<String, JsonValue> legacyFields = new LinkedHashMap<>(current.fields());
        legacyFields.remove(CoreGraphStorageBoundary.ASSET_ACTIVATION_STATE);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_FORMAT_VERSION, JsonValue.of(3));
        legacyFields.remove(CoreGraphStorageBoundary.ASSET_HASH);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_HASH, JsonValue.of("0".repeat(64)));
        JsonValue.JsonObject withoutHash = JsonValue.object(legacyFields);
        legacyFields.put(CoreGraphStorageBoundary.ASSET_HASH,
            JsonValue.of(new CoreGraphStorageBoundary().assetIntegrityHash(withoutHash).canonicalText()));
        Files.write(asset, JsonValue.object(legacyFields).canonicalBytes());

        CoreGraphStorageBoundary.Decoded altered = new CoreGraphStorageBoundary().decode(Files.readAllBytes(asset),
            resource(SERVER, "flow", "restart-v3-save"));
        assertEquals(saved.graphDocument().checksum(), altered.graphDocument().checksum());
        assertEquals(saved.envelope().assetRevision(), altered.envelope().assetRevision());
        assertEquals(saved.envelope().assetMutationId(), altered.envelope().assetMutationId());
        assertEquals(3, altered.envelope().assetFormatVersion());
        assertNotEquals(saved.envelope(), altered.envelope());

        FlowStorage restarted = storage(SERVER);
        assertThrows(IllegalStateException.class,
            () -> restarted.getCoreGraph("flow", "restart-v3-save"));
    }

    @Test
    void failsClosedWhenALegacyTombstoneExists() {
        FlowStorage storage = storage(SERVER);
        UUID saveMutation = UUID.fromString("66666666-6666-4666-8666-666666666666");

        storage.saveCoreGraph(graph("flow", "deleted", 1), ResourceActivationState.ACTIVE, saveMutation, 0L);
        Path tombstone = tempDir.resolve("assets/.tombstones/flow/deleted.json");
        assertTrue(tombstone.getParent() != null);
        try {
            Files.createDirectories(tombstone.getParent());
            Files.writeString(tombstone, "{}");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create tombstone fixture", exception);
        }
        assertThrows(IllegalStateException.class,
            () -> storage.saveCoreGraph(graph("flow", "deleted", 2), ResourceActivationState.ACTIVE,
                UUID.fromString("88888888-8888-4888-8888-888888888888"), 2L));
        assertTrue(Files.exists(tempDir.resolve("assets/.tombstones/flow/deleted.json")));
    }

    @Test
    void storesFunctionSourcesUnderTheFunctionPayloadKind() {
        FlowStorage storage = storage(SERVER);
        FunctionSourceDocument source = source("custom", 1);

        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(source, ResourceActivationState.ACTIVE,
            UUID.fromString("99999999-9999-4999-8999-999999999999"), 0L);

        assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND, saved.corePayloadKind());
        assertEquals("custom", saved.functionSourceDocument().signature().function().id());
        assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND,
            storage.getCoreGraph("function", "custom").orElseThrow().corePayloadKind());
        assertTrue(Files.isRegularFile(tempDir.resolve("assets/Blueprints/Functions/custom.json")));
    }

    @Test
    void keepsFlowAndFunctionIdentitySeparateWhenIdsMatch() {
        FlowStorage storage = storage(SERVER);
        storage.saveCoreGraph(graph("flow", "shared", 1), ResourceActivationState.ACTIVE,
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 0L);
        storage.saveCoreGraph(source("shared", 1), ResourceActivationState.ACTIVE,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), 0L);

        assertEquals(CoreGraphStorageBoundary.GRAPH_DOCUMENT_KIND,
            storage.getCoreGraph("flow", "shared").orElseThrow().corePayloadKind());
        assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND,
            storage.getCoreGraph("function", "shared").orElseThrow().corePayloadKind());
    }

    @Test
    void rejectsForeignServerPayloadsBeforeWriting() {
        FlowStorage storage = storage(SERVER);
        ServerId foreign = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        assertThrows(IllegalArgumentException.class,
            () -> storage.saveCoreGraph(graph(foreign, "flow", "foreign", 1), ResourceActivationState.ACTIVE,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), 0L));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Flows/foreign.json")));
    }

    @Test
    void validatesInactiveCommandsWithoutClaimingLabelsAndRechecksActivation() {
        FlowStorage storage = storage(SERVER);
        storage.saveCoreGraph(command("first", "shared", 1), ResourceActivationState.ACTIVE,
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), 0L);

        CoreGraphStorageBoundary.Decoded inactive = storage.saveCoreGraph(command("second", "shared", 1),
            ResourceActivationState.INACTIVE, UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), 0L);

        assertEquals(ResourceActivationState.INACTIVE, inactive.envelope().assetActivationState());
        assertThrows(IllegalArgumentException.class,
            () -> storage.saveCoreGraph(command("second", "shared", 2), ResourceActivationState.ACTIVE,
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), 1L));
        CoreGraphStorageBoundary.Decoded stored = storage.getCoreGraph("command", "second").orElseThrow();
        assertEquals(1L, stored.envelope().assetRevision());
        assertEquals(ResourceActivationState.INACTIVE, stored.envelope().assetActivationState());
    }

    @Test
    void rejectsMalformedInactiveCommandsBeforePersistence() {
        GraphDocument malformed = graph("command", "malformed", 1);

        assertThrows(IllegalArgumentException.class,
            () -> storage(SERVER).saveCoreGraph(malformed, ResourceActivationState.INACTIVE,
                UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"), 0L));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Commands/malformed.json")));
    }

    private FlowStorage storage(ServerId server) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), server, transactions());
    }

    private AssetTransactionCoordinator transactions() {
        try {
            return new AssetTransactionCoordinator(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private static GraphDocument graph(String type, String id, long revision) {
        return graph(SERVER, type, id, revision, "preserve");
    }

    private static GraphDocument graph(ServerId server, String type, String id, long revision) {
        return graph(server, type, id, revision, "preserve");
    }

    private static GraphDocument graph(String type, String id, long revision, String future) {
        return graph(SERVER, type, id, revision, future);
    }

    private static GraphDocument graph(ServerId server, String type, String id, long revision, String future) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(server, type, id), revision, BINDING,
            Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", future)));
    }

    private static GraphDocument command(String id, String label, long revision) {
        GraphNode start = new GraphNode(NodeInstanceId.deterministic("command-start-" + id),
            CommandGraphContract.CANONICAL_START, 1, Map.of());
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource(SERVER, "command", id), revision,
            BINDING, Set.of(), List.of(start), List.of(), List.of(), List.of(), OpaqueData.empty());
        return new CommandGraphMetadata(label, false, List.of()).apply(document);
    }

    private static FunctionSourceDocument source(String id, long revision) {
        ServerResourceLocator resource = resource(SERVER, "function", id);
        GraphDocument graph = graph(SERVER, "function", id, revision);
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("futureSignature", "preserve"));
        return new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("futureSource", true)));
    }

    private static ServerResourceLocator resource(ServerId server, String type, String id) {
        return new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }
}
