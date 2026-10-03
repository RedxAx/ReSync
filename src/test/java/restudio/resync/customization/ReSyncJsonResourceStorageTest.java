package restudio.resync.customization;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.modules.AdvancementModule;
import restudio.resync.modules.flow.FlowResourceAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncJsonResourceStorageTest {
    private static final String SERVER_ID = "e65887a4-ea27-4c55-bae2-e1c8d92da433";
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();
    private ReSyncJsonResourceStorage storage;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.closePersistence();
        }
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void requiresAllSharedPersistenceDependencies() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetPersistenceGate gate = new AssetPersistenceGate(scope);
        AssetTransactionCoordinator coordinator = coordinator(scope);

        assertThrows(NullPointerException.class,
            () -> new ReSyncJsonResourceStorage(plugin, null, gate, coordinator));
        assertThrows(NullPointerException.class,
            () -> new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), gate, null));
    }

    @Test
    void persistsExactMutationAndTombstoneThroughSharedCoordinator() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "main");
        value.addProperty("message", "Stable");
        UUID saveMutation = UUID.randomUUID();

        storage.save(ReSyncResourceCatalog.CHAT, value, saveMutation, 0L);

        FlowResourceMutationStamp live = storage.readMutationStamp(ReSyncResourceCatalog.CHAT, "main");
        assertNotNull(live);
        assertEquals(1L, live.revision());
        assertEquals(saveMutation, live.mutationId());
        assertFalse(live.deleted());
        UUID deleteMutation = UUID.randomUUID();
        storage.delete(ReSyncResourceCatalog.CHAT, "main", deleteMutation, live.revision());
        FlowResourceMutationStamp deleted = storage.readMutationStamp(ReSyncResourceCatalog.CHAT, "main");
        assertEquals(2L, deleted.revision());
        assertEquals(deleteMutation, deleted.mutationId());
        assertEquals(live.payloadHash(), deleted.payloadHash());
        assertTrue(deleted.deleted());
    }

    @Test
    void reportsInterceptorValidationBeforeAnyResourceWrite() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "invalid-npc");
        value.addProperty("entityType", "player");
        ReSyncJsonResourceStorage.ResourceMutationInterceptor validator = new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void beforeSave(String type, JsonObject candidate) {
                throw new IllegalArgumentException("Player NPCs do not support entity AI");
            }
        };
        storage.addInterceptor(validator);

        FlowResourceAdapter.PreCommitRejection rejection = assertThrows(
            FlowResourceAdapter.PreCommitRejection.class,
            () -> storage.save(ReSyncResourceCatalog.NPC_DEFINITION, value, UUID.randomUUID(), 0L));
        assertEquals("Player NPCs do not support entity AI", rejection.getMessage());
        assertNull(storage.readMutationStamp(ReSyncResourceCatalog.NPC_DEFINITION, "invalid-npc"));

        storage.removeInterceptor(validator);
        storage.save(ReSyncResourceCatalog.NPC_DEFINITION, value, UUID.randomUUID(), 0L);
        assertEquals(1L, storage.readMutationStamp(ReSyncResourceCatalog.NPC_DEFINITION, "invalid-npc").revision());
    }

    @Test
    void publishesProjectMetadataLineageInTheSameGenericResourceTransaction() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        AssetTransactionCoordinator coordinator = coordinators.getFirst();
        JsonObject value = new JsonObject();
        value.addProperty("id", "lineage");
        value.addProperty("message", "Stable");
        UUID mutationId = UUID.randomUUID();

        storage.save(ReSyncResourceCatalog.CHAT, value, mutationId, 0L);

        AssetTransactionCoordinator.MutationView mutation = coordinator.mutation(mutationId).orElseThrow();
        AssetTransactionCoordinator.AssetKey resourceKey = new AssetTransactionCoordinator.AssetKey(
            ReSyncResourceCatalog.CHAT, "lineage");
        AssetTransactionCoordinator.AssetKey lineageKey = new AssetTransactionCoordinator.AssetKey(
            "project_metadata.lineage", "project");
        assertTrue(mutation.result().states().containsKey(resourceKey));
        assertTrue(mutation.result().states().containsKey(lineageKey));
        JsonObject lineage = new Gson().fromJson(Files.readString(
            plugin.getDataFolder().toPath().resolve("assets/.durability/project-metadata-lineage.v1.json")), JsonObject.class);
        assertEquals(mutationId.toString(), lineage.get("mutationId").getAsString());
        assertEquals(SERVER_ID, lineage.get("id").getAsString());
        assertEquals(2L, lineage.get("revision").getAsLong());
        assertEquals(ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(
            Files.readString(plugin.getDataFolder().toPath().resolve("assets/project.json")), Map.class)).canonicalText(),
            lineage.get("payloadHash").getAsString());
    }

    @Test
    void preservesRevisionChecksAndListenerVisibility() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        List<String> callbacks = new ArrayList<>();
        storage.addListener((type, id, value, deleted) -> callbacks.add(id + ":" + deleted));
        storage.addListener((type, id, value, deleted) -> {
            throw new IllegalStateException("listener diagnostics");
        });
        JsonObject value = new JsonObject();
        value.addProperty("id", "revision");
        value.addProperty("message", "First");
        storage.save(ReSyncResourceCatalog.CHAT, value, UUID.randomUUID(), 0L);

        assertThrows(ResourceRevisionConflictException.class,
            () -> storage.save(ReSyncResourceCatalog.CHAT, value.deepCopy(), UUID.randomUUID(), 0L));
        JsonObject updated = value.deepCopy();
        updated.addProperty("message", "Second");
        storage.save(ReSyncResourceCatalog.CHAT, updated, UUID.randomUUID(), 1L);

        assertEquals("Second", storage.get(ReSyncResourceCatalog.CHAT, "revision").get("message").getAsString());
        assertEquals(List.of("revision:false", "revision:false"), callbacks);
    }

    @Test
    void returnsDetachedJsonValuesFromTheStoreCache() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "detached");
        JsonObject nested = new JsonObject();
        nested.addProperty("name", "Stable");
        value.add("nested", nested);

        storage.save(ReSyncResourceCatalog.CHAT, value, UUID.randomUUID(), 0L);

        JsonObject first = storage.get(ReSyncResourceCatalog.CHAT, "detached");
        first.getAsJsonObject("nested").addProperty("name", "Mutated By Caller");

        assertEquals("Stable", storage.get(ReSyncResourceCatalog.CHAT, "detached")
            .getAsJsonObject("nested").get("name").getAsString());
    }

    @Test
    void returnsLogicalPayloadWithoutPersistenceEnvelope() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "logical");
        value.addProperty("message", "Stable");
        UUID mutationId = UUID.randomUUID();

        storage.save(ReSyncResourceCatalog.CHAT, value, mutationId, 0L);

        JsonObject loaded = storage.get(ReSyncResourceCatalog.CHAT, "logical");
        assertFalse(loaded.has("resourceType"));
        assertFalse(loaded.has("assetFormatVersion"));
        assertFalse(loaded.has("assetRevision"));
        assertFalse(loaded.has("assetMutationId"));
        assertFalse(loaded.has("assetHash"));
        assertFalse(loaded.has("assetAuxiliaryHash"));
        assertEquals(storage.readMutationStamp(ReSyncResourceCatalog.CHAT, "logical").payloadHash(),
            ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(loaded, Map.class)).canonicalText());
    }

    @Test
    void capturesDetachedValuesAndRevisionFromOneCoordinatorSnapshot() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject second = new JsonObject();
        second.addProperty("id", "second");
        second.addProperty("name", "Second");
        JsonObject first = new JsonObject();
        first.addProperty("id", "first");
        first.addProperty("name", "First");
        storage.save(ReSyncResourceCatalog.VARIABLE_DEFINITION, second, UUID.randomUUID(), 0L);
        storage.save(ReSyncResourceCatalog.VARIABLE_DEFINITION, first, UUID.randomUUID(), 0L);

        ReSyncJsonResourceStorage.ResourceSnapshot before = storage.readSnapshot(ReSyncResourceCatalog.VARIABLE_DEFINITION);

        assertEquals(List.of("first", "second"), before.values().stream()
            .map(ReSyncJsonResourceStorage.ResourceSnapshotValue::id).toList());
        assertTrue(storage.isCurrent(before));
        before.values().getFirst().value().addProperty("name", "Caller Mutation");
        assertEquals("First", before.values().getFirst().value().get("name").getAsString());
        JsonObject update = first.deepCopy();
        update.addProperty("name", "Updated");
        storage.save(ReSyncResourceCatalog.VARIABLE_DEFINITION, update, UUID.randomUUID(), 1L);

        ReSyncJsonResourceStorage.ResourceSnapshot after = storage.readSnapshot(ReSyncResourceCatalog.VARIABLE_DEFINITION);

        assertFalse(storage.isCurrent(before));
        assertTrue(storage.isCurrent(after));
        assertFalse(before.revision().equals(after.revision()));
        assertEquals("Updated", after.values().getFirst().value().get("name").getAsString());
    }

    @Test
    void normalSavePreservesUnknownPayloadFieldsAtEveryJsonLevel() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject initial = new JsonObject();
        initial.addProperty("id", "future");
        initial.addProperty("displayName", "Initial");
        initial.addProperty("futureTopLevel", true);
        JsonObject channel = new JsonObject();
        channel.addProperty("prefix", "<>");
        channel.addProperty("futureNested", "keep");
        initial.add("channel", channel);
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "entry");
        entry.addProperty("value", "initial");
        entry.addProperty("futureEntry", 11);
        initial.add("entries", new JsonArray());
        initial.getAsJsonArray("entries").add(entry);

        storage.save(ReSyncResourceCatalog.CHAT, initial, UUID.randomUUID(), 0L);

        JsonObject update = new JsonObject();
        update.addProperty("id", "future");
        update.addProperty("displayName", "Updated");
        JsonObject updatedChannel = new JsonObject();
        updatedChannel.addProperty("prefix", "[]");
        update.add("channel", updatedChannel);
        JsonObject updatedEntry = new JsonObject();
        updatedEntry.addProperty("id", "entry");
        updatedEntry.addProperty("value", "updated");
        update.add("entries", new JsonArray());
        update.getAsJsonArray("entries").add(updatedEntry);
        storage.save(ReSyncResourceCatalog.CHAT, update, UUID.randomUUID(), 1L);

        JsonObject persisted = storage.get(ReSyncResourceCatalog.CHAT, "future");
        assertEquals("Updated", persisted.get("displayName").getAsString());
        assertTrue(persisted.get("futureTopLevel").getAsBoolean());
        assertEquals("[]", persisted.getAsJsonObject("channel").get("prefix").getAsString());
        assertEquals("keep", persisted.getAsJsonObject("channel").get("futureNested").getAsString());
        assertEquals(11, persisted.getAsJsonArray("entries").get(0).getAsJsonObject().get("futureEntry").getAsInt());
    }

    @Test
    void scheduleStorageParsingRejectsConflictingTargetIds() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "conflicting-schedule");
        value.addProperty("targetType", "function");
        value.addProperty("targetId", "first");
        value.addProperty("timingMode", "after_delay");
        JsonObject target = new JsonObject();
        target.addProperty("type", "function");
        target.addProperty("id", "second");
        value.add("target", target);

        FlowResourceAdapter.PreCommitRejection failure = assertThrows(FlowResourceAdapter.PreCommitRejection.class,
            () -> storage.save(ReSyncResourceCatalog.SCHEDULE_DEFINITION, value, UUID.randomUUID(), 0L));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertEquals("Schedule target ID fields conflict", failure.getCause().getMessage());
        assertNull(storage.readMutationStamp(ReSyncResourceCatalog.SCHEDULE_DEFINITION, "conflicting-schedule"));
    }

    @Test
    void scheduleStorageAcceptsTopLevelTargetWithoutNestedTarget() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "top-level-schedule");
        value.addProperty("targetType", "function");
        value.addProperty("targetId", "scheduled-function");
        value.addProperty("timingMode", "after_delay");

        storage.save(ReSyncResourceCatalog.SCHEDULE_DEFINITION, value, UUID.randomUUID(), 0L);

        assertNotNull(storage.readMutationStamp(ReSyncResourceCatalog.SCHEDULE_DEFINITION, "top-level-schedule"));
    }

    @Test
    void rebindAndCloseReleaseOnlyStoreRegistrations() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        AssetTransactionCoordinator first = coordinators.getFirst();
        Path candidateScope = tempDir.resolve("candidate").toAbsolutePath().normalize();
        Files.createDirectories(candidateScope.resolve("assets"));
        AssetTransactionCoordinator candidate = coordinator(candidateScope);
        storage.quiescePersistence();

        storage.rebindPersistence(candidateScope, candidate);
        storage.resumePersistence();
        storage.closePersistence();
        storage.closePersistence();

        first.healthCheck();
        candidate.healthCheck();
        assertThrows(IllegalStateException.class, () -> storage.listIds(ReSyncResourceCatalog.CHAT));
    }

    @Test
    void rejectsCoordinatorWithDifferentGateScope() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetTransactionCoordinator coordinator = coordinator(scope);
        AssetPersistenceGate wrongGate = new AssetPersistenceGate(tempDir.resolve("wrong"));

        assertThrows(IllegalArgumentException.class, () -> new ReSyncJsonResourceStorage(plugin,
            LegacyRuntimeActivationGate.runtime(scope), wrongGate, coordinator));
    }

    @Test
    void advancementEventTreesFollowCommittedEditsAndStorageRebinds() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        AdvancementModule module = new AdvancementModule();
        Field field = AdvancementModule.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(module, storage);
        List<FlowResourceMutationStamp> committed = new ArrayList<>();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                if (ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
                    assertEquals(storage.readMutationStamp(type, id), stamp);
                    committed.add(stamp);
                }
            }
        });
        Method read = AdvancementModule.class.getDeclaredMethod("admitTrees");
        read.setAccessible(true);
        JsonObject tree = JsonParser.parseString("""
            {"id":"events","enabled":true,"nodes":{"root":{"enabled":true,"display":{"icon":"minecraft:stone"},
            "criteria":{"break":{"trigger":"break_block"}}}}}
            """).getAsJsonObject();
        storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE, tree);
        Object first = read.invoke(module);
        assertSame(first, read.invoke(module));
        tree.getAsJsonObject("nodes").getAsJsonObject("root").getAsJsonObject("criteria")
            .getAsJsonObject("break").addProperty("trigger", "place_block");
        storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE, tree);
        Map<?, ?> updated = (Map<?, ?>) read.invoke(module);
        assertEquals("place_block", ((JsonObject) updated.get("events")).getAsJsonObject("nodes").getAsJsonObject("root")
            .getAsJsonObject("criteria").getAsJsonObject("break").get("trigger").getAsString());
        storage.delete(ReSyncResourceCatalog.ADVANCEMENT_TREE, "events");
        assertTrue(((Map<?, ?>) read.invoke(module)).isEmpty());
        storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE, tree);
        assertFalse(((Map<?, ?>) read.invoke(module)).isEmpty());
        assertEquals(List.of(1L, 2L, 3L, 4L), committed.stream().map(FlowResourceMutationStamp::revision).toList());
        assertEquals(List.of(false, false, true, false), committed.stream().map(FlowResourceMutationStamp::deleted).toList());
        Path replacement = tempDir.resolve("replacement");
        storage.quiescePersistence();
        storage.rebindPersistence(replacement, coordinator(replacement));
        storage.resumePersistence();
        assertTrue(((Map<?, ?>) read.invoke(module)).isEmpty());
        storage.quiescePersistence();
        assertTrue(((Map<?, ?>) read.invoke(module)).isEmpty());
    }

    private ReSyncJsonResourceStorage storage(JavaPlugin plugin) throws Exception {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetTransactionCoordinator coordinator = coordinator(scope);
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(), List.of(),
            List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), new Gson().toJsonTree(SERVER_ID)))));
        return new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope),
            new AssetPersistenceGate(scope), coordinator);
    }

    private AssetTransactionCoordinator coordinator(Path scope) throws Exception {
        Path assets = scope.resolve("assets").toAbsolutePath().normalize();
        Files.createDirectories(assets);
        AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, new Gson());
        coordinators.add(coordinator);
        return coordinator;
    }

    @Test
    void aggregateCreateConvertsPreStoreValidationFailureToPreCommitRejection() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        ReSyncJsonResourceStorage.ResourceMutationInterceptor interceptor = new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void beforeSave(String type, JsonObject value) {
                throw new IllegalArgumentException("Legacy hook value is not accepted");
            }
        };
        storage.addInterceptor(interceptor);
        JsonObject value = new JsonObject();
        value.addProperty("id", "legacy");
        value.addProperty("message", "Rejected");
        String expectedPayloadHash = ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(value, Map.class))
            .canonicalText();

        try {
            AggregateResourceCreateStorage.PreCommitRejection failure = assertThrows(
                AggregateResourceCreateStorage.PreCommitRejection.class,
                () -> storage.create(ReSyncResourceCatalog.CHAT, value, UUID.randomUUID(), 0L,
                    new ResourcePresentationIntent("Legacy", "Customization/Chat/legacy.json", 1), expectedPayloadHash));

            assertEquals("PROTO.RESOURCE_OPERATION_FAILED", failure.errorCode());
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertNull(storage.readMutationStamp(ReSyncResourceCatalog.CHAT, "legacy"));
        } finally {
            storage.removeInterceptor(interceptor);
        }
    }

    @Test
    void aggregateCreateConvertsPayloadHashMismatchToPreCommitRejection() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject value = new JsonObject();
        value.addProperty("id", "changed");
        value.addProperty("message", "Actual");

        AggregateResourceCreateStorage.PreCommitRejection failure = assertThrows(
            AggregateResourceCreateStorage.PreCommitRejection.class,
            () -> storage.create(ReSyncResourceCatalog.CHAT, value, UUID.randomUUID(), 0L,
                new ResourcePresentationIntent("Changed", "Customization/Chat/changed.json", 1), "0".repeat(64)));

        assertEquals("RESOURCE_PAYLOAD_INVALID", failure.errorCode());
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertNull(storage.readMutationStamp(ReSyncResourceCatalog.CHAT, "changed"));
    }
}
