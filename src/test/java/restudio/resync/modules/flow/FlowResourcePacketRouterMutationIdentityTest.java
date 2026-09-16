package restudio.resync.modules.flow;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenProject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourcePacketRouterMutationIdentityTest {
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final ServerId SERVER = ServerId.deterministic("flow-resource-packet-router-mutation-identity");
    private AssetTransactionCoordinator coordinator;
    private CustomContentStorage customContentStorage;
    private ReSyncJsonResourceStorage jsonResourceStorage;
    private AssetPersistenceGate assetsGate;

    @AfterEach
    void tearDown() throws Exception {
        if (customContentStorage != null) {
            assetsGate.quiesce();
            if (jsonResourceStorage != null) {
                jsonResourceStorage.closePersistence();
            }
            customContentStorage.close();
        }
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void durableAdaptersForwardExactIdentityAndUnsupportedAdaptersStayClosed(@TempDir Path temporary) throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        coordinator = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        assetsGate = new AssetPersistenceGate(temporary);
        FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), assetsGate,
            SERVER, coordinator);
        customContentStorage = new CustomContentStorage(plugin, temporary, new ItemAttributeSchemaService(),
            LegacyRuntimeActivationGate.runtime(temporary), assetsGate, coordinator);
        jsonResourceStorage = new ReSyncJsonResourceStorage(
            plugin, LegacyRuntimeActivationGate.runtime(temporary), assetsGate, coordinator);
        WorldGenProjectStorage worldGenStorage = new WorldGenProjectStorage(
            temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), assetsGate, coordinator);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(storage, customContentStorage, null, jsonResourceStorage,
            null, null, null, registry, ignored -> {
        });
        router.registerExternalLifecycle(worldGenStorage, null);

        FlowResourceAdapter<String> projectMetadata = adapter(registry, ReSyncResourceCatalog.PROJECT_METADATA);
        UUID projectMetadataSave = UUID.nameUUIDFromBytes("project_metadata:save".getBytes(StandardCharsets.UTF_8));
        projectMetadata.save("{}", projectMetadataSave, 0L);
        FlowResourceMutationStamp projectMetadataLive = projectMetadata.readMutationStamp(SERVER.canonicalText());
        assertNotNull(projectMetadataLive);
        assertEquals(projectMetadataSave, projectMetadataLive.mutationId());
        assertFalse(projectMetadataLive.deleted());

        WorldGenProject worldGen = new WorldGenProject();
        worldGen.setId("router-worldgen");
        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.WORLDGEN), worldGen,
            ReSyncResourceCatalog.WORLDGEN, "router-worldgen");

        assertExactMutationIdentity(graphAdapter(registry, ReSyncResourceCatalog.FLOW),
            graph("router-flow", ReSyncResourceCatalog.FLOW, false), ReSyncResourceCatalog.FLOW, "router-flow");
        assertExactMutationIdentity(graphAdapter(registry, ReSyncResourceCatalog.FUNCTION),
            graph("router-function", ReSyncResourceCatalog.FUNCTION, false), ReSyncResourceCatalog.FUNCTION, "router-function");
        assertExactMutationIdentity(graphAdapter(registry, ReSyncResourceCatalog.COMMAND),
            graph("router-command", ReSyncResourceCatalog.COMMAND, true), ReSyncResourceCatalog.COMMAND, "router-command");

        FlowResourceAdapter<FlowGraph> flow = graphAdapter(registry, ReSyncResourceCatalog.FLOW);
        FlowResourceAdapter<FlowGraph> function = graphAdapter(registry, ReSyncResourceCatalog.FUNCTION);
        FlowResourceAdapter<FlowGraph> command = graphAdapter(registry, ReSyncResourceCatalog.COMMAND);
        FlowGraph flowPayload = graph("wrong-function", ReSyncResourceCatalog.FLOW, false);
        FlowGraph declaredFunctionPayload = FlowSerializer.deserialize("""
            {"id":"wrong-function","resourceType":"function","nodes":{},"connections":[],"localVariables":[]}
            """);
        assertThrows(IllegalArgumentException.class, () -> function.deserialize(FlowSerializer.serialize(flowPayload)));
        assertThrows(IllegalArgumentException.class, () -> function.deserialize(FlowSerializer.serialize(declaredFunctionPayload)));
        assertThrows(IllegalArgumentException.class, () -> function.validate(flowPayload));
        assertThrows(IllegalArgumentException.class, () -> function.save(flowPayload));
        assertThrows(IllegalArgumentException.class, () -> function.save(flowPayload,
            UUID.nameUUIDFromBytes("function:wrong-function".getBytes(StandardCharsets.UTF_8)), 0L));
        assertEquals(ReSyncResourceCatalog.FLOW, flowPayload.getResourceType());
        assertFalse(flowPayload.isFunction());
        assertFalse(function.conflicts("wrong-function"));

        flow.save(graph("shared-graph-id", ReSyncResourceCatalog.FLOW, false),
            UUID.nameUUIDFromBytes("flow:shared-graph-id".getBytes(StandardCharsets.UTF_8)), 0L);
        assertTrue(flow.conflicts("shared-graph-id"));
        assertFalse(function.conflicts("shared-graph-id"));
        assertFalse(command.conflicts("shared-graph-id"));
        function.save(graph("shared-graph-id", ReSyncResourceCatalog.FUNCTION, false),
            UUID.nameUUIDFromBytes("function:shared-graph-id".getBytes(StandardCharsets.UTF_8)), 0L);
        command.save(graph("shared-graph-id", ReSyncResourceCatalog.COMMAND, true),
            UUID.nameUUIDFromBytes("command:shared-graph-id".getBytes(StandardCharsets.UTF_8)), 0L);
        FlowGraph storedFlow = flow.get("shared-graph-id");
        FlowGraph storedFunction = function.get("shared-graph-id");
        FlowGraph storedCommand = command.get("shared-graph-id");
        assertNotNull(storedFlow);
        assertNotNull(storedFunction);
        assertNotNull(storedCommand);
        assertEquals(ReSyncResourceCatalog.FLOW, storedFlow.getResourceType());
        assertEquals(ReSyncResourceCatalog.FUNCTION, storedFunction.getResourceType());
        assertEquals(ReSyncResourceCatalog.COMMAND, storedCommand.getResourceType());

        CustomContentDefinition customContent = CustomContentGraphAdapter.toDefinition(
            CustomContentGraphAdapter.createContentGraph("router-content", "item", "Router Content"));
        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.CUSTOM_CONTENT), customContent,
            ReSyncResourceCatalog.CUSTOM_CONTENT, "router-content");

        JsonObject chat = new JsonObject();
        chat.addProperty("id", "router-chat");
        chat.addProperty("message", "Router Chat");
        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.CHAT), chat,
            ReSyncResourceCatalog.CHAT, "router-chat");

        FlowResourceAdapter<JsonObject> schedule = adapter(registry, ReSyncResourceCatalog.SCHEDULE_DEFINITION);
        assertEquals("router-function", schedule.deserialize(schedule("router-schedule", "router-function").toString())
            .get("targetId").getAsString());
        IllegalArgumentException missingTarget = assertThrows(IllegalArgumentException.class,
            () -> schedule.deserialize(schedule("router-schedule", " ").toString()));
        assertEquals("Schedule target is required", missingTarget.getMessage());
        JsonObject conflictingSchedule = schedule("router-schedule", "first");
        JsonObject conflictingTarget = new JsonObject();
        conflictingTarget.addProperty("type", "function");
        conflictingTarget.addProperty("id", "second");
        conflictingSchedule.add("target", conflictingTarget);
        assertThrows(IllegalArgumentException.class, () -> schedule.deserialize(conflictingSchedule.toString()));

        JsonObject nestedSchedule = schedule("router-schedule", " ");
        JsonObject nestedTarget = new JsonObject();
        nestedTarget.addProperty("type", "function");
        nestedTarget.addProperty("id", "router-function");
        nestedSchedule.add("target", nestedTarget);
        assertEquals("router-function", schedule.deserialize(nestedSchedule.toString())
            .getAsJsonObject("target").get("id").getAsString());

        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.GUI),
            new GuiDefinition("shared", "Shared GUI", 1), ReSyncResourceCatalog.GUI, "shared");
        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.SCOREBOARD),
            new ScoreboardDefinition("shared", "Shared Scoreboard"), ReSyncResourceCatalog.SCOREBOARD, "shared");
        assertExactMutationIdentity(adapter(registry, ReSyncResourceCatalog.TAB),
            new TabDefinition("shared"), ReSyncResourceCatalog.TAB, "shared");

        FlowResourceMutationStamp projectMetadataCurrent = projectMetadata.readMutationStamp(SERVER.canonicalText());
        UUID projectMetadataDelete = UUID.nameUUIDFromBytes("project_metadata:delete".getBytes(StandardCharsets.UTF_8));
        projectMetadata.delete(SERVER.canonicalText(), projectMetadataDelete, projectMetadataCurrent.revision());
        FlowResourceMutationStamp projectMetadataTombstone = projectMetadata.readMutationStamp(SERVER.canonicalText());
        assertNotNull(projectMetadataTombstone);
        assertEquals(projectMetadataDelete, projectMetadataTombstone.mutationId());
        assertTrue(projectMetadataTombstone.deleted());

    }

    private <T> void assertExactMutationIdentity(FlowResourceAdapter<T> adapter, T value, String type, String id) {
        assertTrue(adapter.supportsAuthoritativeMutationIdentity());
        UUID saveMutation = UUID.nameUUIDFromBytes((type + ":save").getBytes(StandardCharsets.UTF_8));

        adapter.save(value, saveMutation, 0L);

        FlowResourceMutationStamp live = adapter.readMutationStamp(id);
        assertNotNull(live);
        assertEquals(type, live.type());
        assertEquals(id, live.id());
        assertEquals(1L, live.revision());
        assertEquals(saveMutation, live.mutationId());
        assertTrue(live.payloadHash().matches(HASH_PATTERN));
        assertFalse(live.deleted());

        UUID deleteMutation = UUID.nameUUIDFromBytes((type + ":delete").getBytes(StandardCharsets.UTF_8));
        adapter.delete(id, deleteMutation, live.revision());

        FlowResourceMutationStamp tombstone = adapter.readMutationStamp(id);
        assertNotNull(tombstone);
        assertEquals(type, tombstone.type());
        assertEquals(id, tombstone.id());
        assertEquals(2L, tombstone.revision());
        assertEquals(deleteMutation, tombstone.mutationId());
        assertEquals(live.payloadHash(), tombstone.payloadHash());
        assertTrue(tombstone.deleted());
    }

    private FlowGraph graph(String id, String type, boolean command) {
        String json = command
            ? """
                {
                  "id":"%s",
                  "resourceType":"command",
                  "nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{"command":"%s"}}},
                  "connections":[],
                  "localVariables":[]
                }
                """.formatted(id, id)
            : """
                {
                  "id":"%s",
                  "resourceType":"%s",
                  "function":%s,
                  "nodes":{},
                  "connections":[],
                  "localVariables":[]
                }
                """.formatted(id, type, ReSyncResourceCatalog.FUNCTION.equals(type));
        return FlowSerializer.deserialize(json);
    }

    private JsonObject schedule(String id, String targetId) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("targetType", "function");
        value.addProperty("targetId", targetId);
        value.addProperty("timingMode", "after_delay");
        return value;
    }

    @SuppressWarnings("unchecked")
    private FlowResourceAdapter<FlowGraph> graphAdapter(FlowResourceRegistry registry, String type) {
        return (FlowResourceAdapter<FlowGraph>) registry.get(type);
    }

    @SuppressWarnings("unchecked")
    private <T> FlowResourceAdapter<T> adapter(FlowResourceRegistry registry, String type) {
        return (FlowResourceAdapter<T>) registry.get(type);
    }
}
