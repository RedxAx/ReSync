package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageCoreDeleteTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void deletesCorePayloadMetadataAndCreatesTypedTombstoneAtomically() throws Exception {
        FlowStorage storage = storage();
        UUID saveMutation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID deleteMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");

        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(graph("main", 1),
            ResourceActivationState.ACTIVE, saveMutation, 0L);
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = storage.deleteCoreGraph("flow", "main",
            deleteMutation, 1L);

        assertEquals(SERVER, tombstone.resource().serverId());
        assertEquals("flow", tombstone.resource().resourceType().value());
        assertEquals("main", tombstone.resource().id());
        assertEquals(2L, tombstone.revision());
        assertEquals(deleteMutation, tombstone.mutationId());
        assertEquals(saved.graphDocument().checksum(), tombstone.priorPayloadHash());
        assertTrue(tombstone.deleted());
        assertTrue(storage.getCoreGraph("flow", "main").isEmpty());

        Path asset = tempDir.resolve("assets/Blueprints/Flows/main.json");
        Path tombstoneFile = tempDir.resolve("assets/.tombstones/flow/main.json");
        assertFalse(Files.exists(asset));
        assertTrue(Files.isRegularFile(tombstoneFile));
        assertFalse(Files.readString(tombstoneFile).contains("assetActivationState"));
        assertFalse(Files.readString(tempDir.resolve("assets/project.json")).contains("\"type\":\"flow\""));
    }

    @Test
    void deleteReplayIsIdempotentAndDifferentMutationConflicts() {
        FlowStorage storage = storage();
        UUID saveMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID deleteMutation = UUID.fromString("55555555-5555-4555-8555-555555555555");
        storage.saveCoreGraph(graph("replay", 1), ResourceActivationState.ACTIVE, saveMutation, 0L);

        CoreGraphStorageBoundary.CoreGraphTombstone deleted = storage.deleteCoreGraph("flow", "replay",
            deleteMutation, 1L);
        assertEquals(deleted, storage.deleteCoreGraph("flow", "replay", deleteMutation, 1L));
        assertThrows(ResourceRevisionConflictException.class,
            () -> storage.deleteCoreGraph("flow", "replay",
                UUID.fromString("66666666-6666-4666-8666-666666666666"), 1L));
        CoreGraphStorageBoundary.Decoded recreated = storage.saveCoreGraph(graph("replay", 3),
            ResourceActivationState.ACTIVE, UUID.fromString("77777777-7777-4777-8777-777777777777"), 2L);
        assertEquals(3L, recreated.envelope().assetRevision());
        assertTrue(storage.getCoreGraph("flow", "replay").isPresent());
    }

    @Test
    void replaysACommittedDeleteAfterRestart() throws Exception {
        UUID saveMutation = UUID.fromString("88888888-8888-4888-8888-888888888888");
        UUID deleteMutation = UUID.fromString("99999999-9999-4999-8999-999999999999");
        FlowStorage first = storage();
        first.saveCoreGraph(graph("restart-delete", 1), ResourceActivationState.ACTIVE, saveMutation, 0L);
        CoreGraphStorageBoundary.CoreGraphTombstone deleted = first.deleteCoreGraph("flow", "restart-delete",
            deleteMutation, 1L);

        FlowStorage restarted = storage();
        CoreGraphStorageBoundary.CoreGraphTombstone replay = restarted.deleteCoreGraph("flow", "restart-delete",
            deleteMutation, 1L);

        assertEquals(deleted, replay);
        assertTrue(restarted.getCoreGraph("flow", "restart-delete").isEmpty());
    }

    @Test
    void functionDeleteUsesTheFunctionSourceChecksum() {
        FlowStorage storage = storage();
        UUID saveMutation = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        UUID deleteMutation = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(source("function-delete", 1),
            ResourceActivationState.ACTIVE, saveMutation, 0L);

        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = storage.deleteCoreGraph("function", "function-delete",
            deleteMutation, 1L);

        assertEquals(saved.functionSourceDocument().checksum(), tombstone.priorPayloadHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "flow", "function"})
    void canonicalCoreTombstonesExposeTypedRuntimeInvalidationIdentity(String type) {
        FlowStorage storage = storage();
        String id = type + "-compiled-delete";
        UUID saveMutation = UUID.randomUUID();
        UUID deleteMutation = UUID.randomUUID();
        CoreGraphStorageBoundary.Decoded saved = "function".equals(type)
            ? storage.saveCoreGraph(source(id, 1), ResourceActivationState.ACTIVE, saveMutation, 0L)
            : storage.saveCoreGraph(graph(type, id, 1), ResourceActivationState.ACTIVE, saveMutation, 0L);

        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = storage.deleteCoreGraph(type, id, deleteMutation, 1L);
        FlowStorage.GraphIdentity identity = storage.readGraphIdentity(type, id);

        assertNotNull(identity);
        assertEquals(type, identity.type());
        assertEquals(id, identity.id());
        assertEquals(tombstone.revision(), identity.revision());
        assertEquals(deleteMutation.toString(), identity.mutationId());
        assertEquals(tombstone.priorPayloadHash().canonicalText(), identity.payloadHash());
        assertEquals("function".equals(type) ? saved.functionSourceDocument().checksum()
            : saved.graphDocument().checksum(), tombstone.priorPayloadHash());
        assertTrue(identity.deleted());
    }

    @Test
    void recoversPreparedDeleteAcrossTargetsAndReplaysItAfterRestart() throws Exception {
        UUID saveMutation = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID deleteMutation = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        String id = "prepared-delete";
        AssetTransactionCoordinator coordinator = transactions();
        FlowStorage first = storage(coordinator);
        first.saveCoreGraph(graph(id, 1), ResourceActivationState.ACTIVE, saveMutation, 0L);
        Path assets = tempDir.resolve("assets");
        Path asset = assets.resolve("Blueprints/Flows").resolve(id + ".json");
        Path project = assets.resolve("project.json");
        Path tombstoneFile = assets.resolve(".tombstones/flow").resolve(id + ".json");
        byte[] priorState = Files.readAllBytes(assets.resolve(".asset-coordinator/state.json"));
        byte[] priorProject = Files.readAllBytes(project);
        byte[] priorAsset = Files.readAllBytes(asset);
        CoreGraphStorageBoundary.CoreGraphTombstone persisted = first.deleteCoreGraph("flow", id,
            deleteMutation, 1L);
        Path journal = journalForMutation(assets, deleteMutation);
        JsonObject journalObject = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        journalObject.addProperty("state", "PREPARED");
        coordinator.close();
        Files.writeString(journal, journalObject.toString());
        Files.write(assets.resolve(".asset-coordinator/state.json"), priorState);
        Files.write(project, priorProject);
        Files.write(asset, priorAsset);
        Files.deleteIfExists(tombstoneFile);

        FlowStorage restarted = storage();
        CoreGraphStorageBoundary.CoreGraphTombstone recovered = restarted.deleteCoreGraph("flow", id,
            deleteMutation, 1L);

        assertEquals(persisted, recovered);
        assertTrue(restarted.getCoreGraph("flow", id).isEmpty());
        assertFalse(Files.exists(asset));
        assertTrue(Files.isRegularFile(tombstoneFile));
        assertFalse(Files.readString(project).contains("\"id\":\"" + id + "\""));
        assertTrue(Files.readString(journal)
            .contains("\"state\":\"COMMITTED\""));
    }

    @Test
    void deleteRequiresTheExactAuthoritativeLocator() {
        FlowStorage storage = storage();
        UUID saveMutation = UUID.fromString("88888888-8888-4888-8888-888888888888");
        storage.saveCoreGraph(graph("identity", 1), ResourceActivationState.ACTIVE, saveMutation, 0L);
        ServerResourceLocator foreign = new ServerResourceLocator(
            new ServerId(UUID.fromString("99999999-9999-4999-8999-999999999999")),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "identity");

        assertThrows(IllegalArgumentException.class,
            () -> storage.deleteCoreGraph(foreign,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 1L));
        assertTrue(storage.getCoreGraph("flow", "identity").isPresent());
    }

    @Test
    void coreSaveRejectsLegacyPayloadWithoutImplicitResurrection() throws Exception {
        FlowStorage storage = storage();
        Path legacy = tempDir.resolve("flows/legacy.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"id\":\"legacy\",\"nodes\":{}}");

        assertThrows(IllegalStateException.class,
            () -> storage.saveCoreGraph(graph("legacy", 1), ResourceActivationState.ACTIVE,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), 0L));
        assertTrue(Files.isRegularFile(legacy));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Flows/legacy.json")));
    }

    private FlowStorage storage() {
        return storage(transactions());
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator);
    }

    private AssetTransactionCoordinator transactions() {
        try {
            return new AssetTransactionCoordinator(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private static GraphDocument graph(String id, long revision) {
        return graph("flow", id, revision);
    }

    private static GraphDocument graph(String type, String id, long revision) {
        List<GraphNode> nodes = "command".equals(type)
            ? List.of(new GraphNode(NodeInstanceId.deterministic("command-start-" + id),
                CommandGraphContract.CANONICAL_START, 1, Map.of()))
            : List.of();
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(), nodes,
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static FunctionSourceDocument source(String id, long revision) {
        ServerResourceLocator resource = resource("function", id);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("futureGraph", true)));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("futureSignature", true));
        return new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("futureSource", true)));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private static Path journalForMutation(Path assets, UUID mutationId) throws Exception {
        try (var transactions = Files.list(assets.resolve(".transactions"))) {
            return transactions.map(path -> path.resolve("journal.json")).filter(Files::isRegularFile)
                .filter(path -> {
                    try {
                        return mutationId.toString().equals(JsonParser.parseString(Files.readString(path))
                            .getAsJsonObject().get("mutationId").getAsString());
                    } catch (IOException exception) {
                        throw new IllegalStateException("Failed to inspect asset transaction journal", exception);
                    }
                }).findFirst().orElseThrow();
        }
    }
}
