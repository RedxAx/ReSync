package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenAggregateResourceAuthorityTest {
    private static final Gson GSON = new Gson();
    private static final ServerId SERVER = ServerId.deterministic("worldgen-aggregate-resource-authority");
    private AssetTransactionCoordinator coordinator;
    private WorldGenProjectStorage worldGenStorage;

    @AfterEach
    void closeStorage() throws Exception {
        if (worldGenStorage != null) {
            worldGenStorage.closePersistence();
        }
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void createReplaysExactlyAndCopyPreservesOpaquePayloadAtCanonicalPath(@TempDir Path temporary) throws Exception {
        coordinator = AssetTransactionCoordinator.open(temporary.resolve("assets"), GSON);
        AssetPersistenceGate assetsGate = new AssetPersistenceGate(temporary);
        FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), assetsGate,
            SERVER, coordinator);
        worldGenStorage = new WorldGenProjectStorage(
            temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), assetsGate, coordinator);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(storage, null, null, null,
            null, null, null, registry, ignored -> {
        });
        router.registerExternalLifecycle(worldGenStorage, null);
        WorldGenNodeDefinitions.registerDefaults(WorldGenNodeRegistry.getInstance());
        FlowResourceAdapter<String> metadataAdapter = adapter(registry, ReSyncResourceCatalog.PROJECT_METADATA);
        metadataAdapter.save("{}", UUID.fromString("10000000-0000-4000-8000-000000000001"), 0L);

        WorldGenProject source = worldGenProject("aggregate-worldgen");
        JsonObject sourceJson = GSON.fromJson(WorldGenSerializer.serializeProject(source), JsonObject.class);
        sourceJson.addProperty("futureWorldGenField", "preserved");
        source = WorldGenSerializer.deserializeProject(sourceJson.toString());
        FlowResourceAdapter<WorldGenProject> worldGenAdapter = adapter(registry, ReSyncResourceCatalog.WORLDGEN);
        ServerResourceLocator sourceResource = locator("aggregate-worldgen");
        UUID createMutation = UUID.fromString("20000000-0000-4000-8000-000000000002");
        ResourcePresentationIntent presentation = new ResourcePresentationIntent(
            "Aggregate WorldGen", "WorldGen/aggregate-worldgen.json", 7);
        String sourceHash = payloadHash(worldGenAdapter, source);
        AggregateResourceCreateStorage.Result created = aggregateCreate(
            registry, sourceResource, source, createMutation, sourceHash, presentation);
        long committedSequence = coordinator.read(snapshot -> snapshot.rootSequence());

        assertEquals(createMutation, created.primary().stamp().mutationId());
        assertEquals(1L, created.primary().stamp().revision());
        assertEquals(sourceHash, created.primary().stamp().payloadHash());
        assertEquals(createMutation, created.projectMetadata().stamp().mutationId());
        assertEquals("preserved", created.primary().canonicalPayload().get("futureWorldGenField"));
        assertTrue(Files.isRegularFile(temporary.resolve("assets/WorldGen/aggregate-worldgen.json")));
        JsonObject metadata = GSON.toJsonTree(created.projectMetadata().canonicalPayload()).getAsJsonObject();
        JsonObject sourceEntry = resourceEntry(metadata, sourceResource.id());
        assertEquals("Aggregate WorldGen", sourceEntry.get("displayName").getAsString());
        assertEquals("WorldGen/aggregate-worldgen.json", sourceEntry.get("path").getAsString());
        assertEquals(7, sourceEntry.get("sortOrder").getAsInt());

        AggregateResourceCreateStorage.Result replay = aggregateCreate(
            registry, sourceResource, source, createMutation, sourceHash, presentation);
        assertEquals(created.primary().stamp(), replay.primary().stamp());
        assertEquals(created.projectMetadata().stamp(), replay.projectMetadata().stamp());
        assertEquals(committedSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());

        String copyId = "aggregate-worldgen-copy";
        WorldGenProject copy = worldGenAdapter.duplicate(worldGenAdapter.get(sourceResource.id()), copyId);
        UUID copyMutation = UUID.fromString("30000000-0000-4000-8000-000000000003");
        FlowResourceMutationStamp sourceStamp = worldGenAdapter.readMutationStamp(sourceResource.id());
        try (FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(
            new FlowResourceKey(ReSyncResourceCatalog.WORLDGEN, sourceResource.id()),
            new FlowResourceKey(ReSyncResourceCatalog.WORLDGEN, copyId),
            new FlowResourceKey(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText())), copyMutation.toString())) {
            FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
                copyMutation, sourceStamp.revision(), payloadHash(worldGenAdapter, copy));
            var result = registry.duplicate(ReSyncResourceCatalog.WORLDGEN, sourceResource.id(), copyId, context);
            assertTrue(result.success(), result.errorCode() + ":" + result.message() + ":" + result.details());
        }
        assertEquals("preserved", GSON.fromJson(worldGenAdapter.serialize(worldGenAdapter.get(copyId)), JsonObject.class)
            .get("futureWorldGenField").getAsString());
        JsonObject copiedMetadata = GSON.fromJson(Files.readString(temporary.resolve("assets/project.json")), JsonObject.class);
        assertEquals("WorldGen/aggregate-worldgen-copy.json", resourceEntry(copiedMetadata, copyId).get("path").getAsString());
    }

    private AggregateResourceCreateStorage.Result aggregateCreate(FlowResourceRegistry registry,
                                                                    ServerResourceLocator resource,
                                                                    WorldGenProject project,
                                                                    UUID mutationId,
                                                                    String payloadHash,
                                                                    ResourcePresentationIntent presentation) {
        try (FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(
            new FlowResourceKey(ReSyncResourceCatalog.WORLDGEN, resource.id()),
            new FlowResourceKey(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText())),
            mutationId.toString())) {
            FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
                mutationId, 0L, payloadHash);
            return registry.create(resource, project, context, presentation);
        }
    }

    private JsonObject resourceEntry(JsonObject metadata, String id) {
        return metadata.getAsJsonArray("resources").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(element -> ReSyncResourceCatalog.WORLDGEN.equals(element.get("type").getAsString())
                && id.equals(element.get("id").getAsString()))
            .findFirst().orElseThrow();
    }

    private String payloadHash(FlowResourceAdapter<WorldGenProject> adapter, WorldGenProject project) {
        return ResourcePayloadCodecs.json().hashPayload(GSON.fromJson(adapter.serialize(project), Map.class)).canonicalText();
    }

    private ServerResourceLocator locator(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(ReSyncResourceCatalog.WORLDGEN)), id);
    }

    private WorldGenProject worldGenProject(String id) {
        WorldGenProject project = new WorldGenProject();
        project.setId(id);
        WorldGenGraph graph = new WorldGenGraph();
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("noise", new WorldGenNode("worldgen:simplex", 0, 0, Map.of("seed", 0, "frequency", 0.01f)));
        nodes.put("height", new WorldGenNode("worldgen:output_height", 160, 0, Map.of()));
        graph.setNodes(nodes);
        graph.setConnections(List.of(new WorldGenConnection("noise", "out", "height", "height")));
        project.setTerrainGraph(graph);
        return project;
    }

    @SuppressWarnings("unchecked")
    private <T> FlowResourceAdapter<T> adapter(FlowResourceRegistry registry, String type) {
        return (FlowResourceAdapter<T>) registry.get(type);
    }
}
