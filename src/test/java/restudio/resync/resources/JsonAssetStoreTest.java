package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAssetStoreTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void coordinatesPayloadMetadataBlobAndTombstoneLineage() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator)) {
            AssetTransactionCoordinator.Snapshot metadata = coordinator.read(snapshot -> snapshot);
            coordinator.transact(new TransactionRequest(UUID.randomUUID(), metadata.project(), List.of(),
                List.of(ProjectDelta.set(List.of("unknown"), new JsonPrimitive("preserved")))));
            Path icon = assets.resolve("Icons/main.png");
            byte[] iconBytes = "icon".getBytes(StandardCharsets.UTF_8);
            UUID saveMutation = UUID.fromString("11111111-1111-4111-8111-111111111111");

            store.save(new TestResource("main", "Main"), Map.of(icon, iconBytes), saveMutation, 0L);

            JsonAssetStore.AssetStamp live = store.readStamp("main");
            assertEquals(1L, live.revision());
            assertEquals(saveMutation, live.mutationId());
            assertFalse(live.deleted());
            assertArrayEquals(iconBytes, Files.readAllBytes(icon));
            JsonObject project = JsonParser.parseString(Files.readString(assets.resolve("project.json"))).getAsJsonObject();
            assertEquals("preserved", project.get("unknown").getAsString());
            assertEquals("gui", project.getAsJsonArray("resources").get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("main", project.getAsJsonArray("resources").get(0).getAsJsonObject().get("id").getAsString());
            assertThrows(IllegalStateException.class, () -> store.save(new TestResource("main", "Main"),
                Map.of(icon, "changed".getBytes(StandardCharsets.UTF_8)), saveMutation, 0L));
            Files.write(icon, "corrupt".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class,
                () -> store.save(new TestResource("main", "Main"), Map.of(icon, iconBytes), saveMutation, 0L));
            Files.write(icon, iconBytes);

            UUID deleteMutation = UUID.fromString("22222222-2222-4222-8222-222222222222");
            store.delete("main", deleteMutation, live.revision());

            JsonAssetStore.AssetStamp deleted = store.readStamp("main");
            assertEquals(2L, deleted.revision());
            assertEquals(deleteMutation, deleted.mutationId());
            assertEquals(live.payloadHash(), deleted.payloadHash());
            assertTrue(deleted.deleted());
            JsonObject afterDelete = JsonParser.parseString(Files.readString(assets.resolve("project.json"))).getAsJsonObject();
            assertEquals("preserved", afterDelete.get("unknown").getAsString());
            assertTrue(afterDelete.getAsJsonArray("resources").isEmpty());
        }
    }

    @Test
    void historicalRetryIsExactAndDoesNotRestoreStaleCacheState() throws Exception {
        Path assets = tempDir.resolve("assets");
        UUID firstMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID secondMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
        TestResource first = new TestResource("main", "First");
        TestResource second = new TestResource("main", "Second");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator)) {
            store.save(first, firstMutation, 0L);
            store.save(second, secondMutation, 1L);
        }

        try (AssetTransactionCoordinator reopenedCoordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> reopened = store(assets, reopenedCoordinator)) {
            reopened.save(first, firstMutation, 0L);

            assertEquals("Second", reopened.get("main").name());
            assertEquals(secondMutation, reopened.readStamp("main").mutationId());
            assertEquals(2L, reopened.readStamp("main").revision());
            assertThrows(IllegalStateException.class,
                () -> reopened.save(new TestResource("main", "Changed"), firstMutation, 0L));
        }
    }

    @Test
    void reopensExactDeletedStampFromCoordinatorLineage() throws Exception {
        Path assets = tempDir.resolve("assets");
        UUID deleteMutation = UUID.fromString("66666666-6666-4666-8666-666666666666");
        JsonAssetStore.AssetStamp deleted;
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator)) {
            store.save(new TestResource("main", "Main"), UUID.randomUUID(), 0L);
            store.delete("main", deleteMutation, 1L);
            deleted = store.readStamp("main");
        }

        try (AssetTransactionCoordinator reopenedCoordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> reopened = store(assets, reopenedCoordinator)) {
            assertEquals(deleted, reopened.readStamp("main"));
            assertEquals(deleteMutation, reopened.readStamp("main").mutationId());
        }
    }

    @Test
    void preparedBatchCommitsCanonicalSaveAndAliasDeleteTogether() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator)) {
            store.save(new TestResource("alias", "Legacy"), UUID.randomUUID(), 0L);
            UUID mutationId = UUID.fromString("55555555-5555-4555-8555-555555555555");
            AssetTransactionCoordinator.Snapshot snapshot = store.coordinatorSnapshot();
            JsonAssetStore.PreparedMutation save = store.prepareSave(snapshot, new TestResource("canonical", "Current"),
                Map.of(), mutationId, 0L);
            JsonAssetStore.PreparedMutation delete = store.prepareDelete(snapshot, "alias", mutationId, 1L);

            store.commitPrepared(mutationId, snapshot, List.of(save, delete));

            assertEquals("Current", store.get("canonical").name());
            assertNull(store.get("alias"));
            JsonObject project = JsonParser.parseString(Files.readString(assets.resolve("project.json"))).getAsJsonObject();
            assertEquals(1, project.getAsJsonArray("resources").size());
            assertEquals("canonical", project.getAsJsonArray("resources").get(0).getAsJsonObject().get("id").getAsString());
            long rootSequence = coordinator.read(current -> current.rootSequence());
            assertEquals(2L, rootSequence);

            assertEquals(List.of(new JsonAssetStore.ReplayOperation("alias", true),
                new JsonAssetStore.ReplayOperation("canonical", false)), store.replayOperations(mutationId));
            AssetTransactionCoordinator.Snapshot replaySnapshot = store.coordinatorSnapshot();
            List<JsonAssetStore.PreparedMutation> replay = store.replayOperations(mutationId).stream().map(operation ->
                operation.deleted() ? store.prepareDelete(replaySnapshot, operation.id(), mutationId, -1L)
                    : store.prepareSave(replaySnapshot, new TestResource(operation.id(), "Current"), Map.of(), mutationId, -1L)
            ).toList();
            assertTrue(store.commitPrepared(mutationId, replaySnapshot, replay).replay());
            long replayRootSequence = coordinator.read(current -> current.rootSequence());
            assertEquals(2L, replayRootSequence);
        }
    }

    @Test
    void closeUnregistersStoreWithoutClosingBorrowedCoordinator() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            JsonAssetStore<TestResource> store = store(assets, coordinator);

            store.close();
            store.close();

            coordinator.healthCheck();
            assertThrows(IllegalStateException.class, () -> store.get("main"));
        }
    }

    @Test
    void payloadMergerPreservesAuthoritativeUnknownFields() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            try (JsonAssetStore<JsonObject> raw = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                json -> JsonParser.parseString(json).getAsJsonObject(), GSON::toJson,
                value -> value.get("id").getAsString(), null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
                () -> true)) {
                JsonObject initial = new JsonObject();
                initial.addProperty("id", "main");
                initial.addProperty("name", "Initial");
                initial.addProperty("unknown", "preserved");
                raw.save(initial, UUID.randomUUID(), 0L);
            }
            try (JsonAssetStore<TestResource> typed = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                TestResource::fromJson, TestResource::toJson, TestResource::id, null,
                LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true, () -> () -> {
                }, (value, existing, serialized) -> {
                    existing.addProperty("id", value.id());
                    existing.addProperty("name", value.name());
                    return existing;
                })) {
                typed.save(new TestResource("main", "Updated"), UUID.randomUUID(), 1L);
                JsonObject persisted = JsonParser.parseString(Files.readString(assets.resolve("GUIs/main.json"))).getAsJsonObject();
                assertEquals("Updated", persisted.get("name").getAsString());
                assertEquals("preserved", persisted.get("unknown").getAsString());
            }
        }
    }

    @Test
    void defaultPayloadMergerPreservesNestedAndArrayUnknownFields() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<JsonObject> store = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                 json -> JsonParser.parseString(json).getAsJsonObject(), GSON::toJson,
                 value -> value.get("id").getAsString(), null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
                 () -> true)) {
            JsonObject initial = new JsonObject();
            initial.addProperty("id", "nested");
            initial.addProperty("name", "Initial");
            initial.addProperty("futureTopLevel", true);
            JsonObject nested = new JsonObject();
            nested.addProperty("known", "initial");
            nested.addProperty("futureNested", "preserved");
            initial.add("nested", nested);
            JsonObject item = new JsonObject();
            item.addProperty("id", "entry");
            item.addProperty("value", "initial");
            item.addProperty("futureEntry", 7);
            initial.add("entries", new JsonArray());
            initial.getAsJsonArray("entries").add(item);
            store.save(initial, UUID.randomUUID(), 0L);

            JsonObject updated = new JsonObject();
            updated.addProperty("id", "nested");
            updated.addProperty("name", "Updated");
            JsonObject updatedNested = new JsonObject();
            updatedNested.addProperty("known", "updated");
            updated.add("nested", updatedNested);
            JsonObject updatedItem = new JsonObject();
            updatedItem.addProperty("id", "entry");
            updatedItem.addProperty("value", "updated");
            updated.add("entries", new JsonArray());
            updated.getAsJsonArray("entries").add(updatedItem);
            store.save(updated, UUID.randomUUID(), 1L);

            JsonObject persisted = JsonParser.parseString(Files.readString(assets.resolve("GUIs/nested.json"))).getAsJsonObject();
            assertEquals("Updated", persisted.get("name").getAsString());
            assertTrue(persisted.get("futureTopLevel").getAsBoolean());
            assertEquals("updated", persisted.getAsJsonObject("nested").get("known").getAsString());
            assertEquals("preserved", persisted.getAsJsonObject("nested").get("futureNested").getAsString());
            assertEquals(7, persisted.getAsJsonArray("entries").get(0).getAsJsonObject().get("futureEntry").getAsInt());
        }
    }

    @Test
    void getRetriesWhenACommitInvalidatesTheReadGeneration() throws Exception {
        Path assets = tempDir.resolve("assets");
        CountDownLatch firstReadEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                 json -> {
                     if (reads.getAndIncrement() == 0) {
                         firstReadEntered.countDown();
                         try {
                             if (!releaseFirstRead.await(5, TimeUnit.SECONDS)) {
                                 throw new IllegalStateException("Timed out waiting for the concurrent commit");
                             }
                         } catch (InterruptedException exception) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("Reader was interrupted", exception);
                         }
                     }
                     return TestResource.fromJson(json);
                 }, TestResource::toJson, TestResource::id, null,
                 LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true)) {
            store.save(new TestResource("main", "old"), UUID.randomUUID(), 0L);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var pending = executor.submit(() -> store.get("main"));
                assertTrue(firstReadEntered.await(5, TimeUnit.SECONDS));
                store.save(new TestResource("main", "new"), UUID.randomUUID(), 1L);
                releaseFirstRead.countDown();
                assertEquals("new", pending.get(5, TimeUnit.SECONDS).name());
            } finally {
                releaseFirstRead.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void validatedPublicationRejectsAConcurrentCommitWithTheDefaultLease() throws Exception {
        Path assets = tempDir.resolve("assets");
        CountDownLatch publicationEnteredIdExtractor = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        AtomicBoolean blockPublication = new AtomicBoolean();
        AtomicReference<Thread> publicationThread = new AtomicReference<>();
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                 TestResource::fromJson, TestResource::toJson, value -> {
                     if (blockPublication.get() && Thread.currentThread() == publicationThread.get()
                         && blockPublication.compareAndSet(true, false)) {
                         publicationEnteredIdExtractor.countDown();
                         try {
                             if (!releasePublication.await(5, TimeUnit.SECONDS)) {
                                 throw new IllegalStateException("Timed out waiting for the concurrent commit");
                             }
                         } catch (InterruptedException exception) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("Publication was interrupted", exception);
                         }
                     }
                     return value.id();
                 }, null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true)) {
            store.save(new TestResource("main", "old"), UUID.randomUUID(), 0L);
            JsonAssetStore.AssetStamp oldStamp = store.readStamp("main");
            TestResource oldValue = store.readUncached("main");
            blockPublication.set(true);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var publication = executor.submit(() -> {
                    publicationThread.set(Thread.currentThread());
                    store.publishValidated("main", oldValue, oldStamp);
                    return null;
                });
                assertTrue(publicationEnteredIdExtractor.await(5, TimeUnit.SECONDS));
                store.save(new TestResource("main", "new"), UUID.randomUUID(), 1L);
                releasePublication.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> publication.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalStateException);
                assertEquals("new", store.get("main").name());
            } finally {
                releasePublication.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void cachedJsonValuesAreDetachedFromCallersAndReloadValidatorsRunOutsideLeases() throws Exception {
        Path assets = tempDir.resolve("assets");
        AtomicBoolean leaseHeld = new AtomicBoolean();
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<JsonObject> store = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                 json -> {
                     assertFalse(leaseHeld.get());
                     return JsonParser.parseString(json).getAsJsonObject();
                 }, GSON::toJson, value -> value.get("id").getAsString(), null,
                 LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true,
                 () -> {
                     leaseHeld.set(true);
                     return () -> leaseHeld.set(false);
                 })) {
            JsonObject initial = new JsonObject();
            initial.addProperty("id", "main");
            initial.addProperty("name", "Stable");
            store.save(initial, UUID.randomUUID(), 0L);

            JsonObject first = store.get("main");
            first.addProperty("name", "Mutated By Caller");
            assertEquals("Stable", store.get("main").get("name").getAsString());

            store.reload("main", value -> assertFalse(leaseHeld.get()));
        }
    }

    @Test
    void reloadAndValidatedPublicationPreserveOpaqueDurableFieldsWithARestrictedWriter() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<JsonObject> durable = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                 json -> JsonParser.parseString(json).getAsJsonObject(), GSON::toJson,
                 value -> value.get("id").getAsString(), null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
                 () -> true)) {
            JsonObject initial = new JsonObject();
            initial.addProperty("id", "opaque");
            initial.addProperty("name", "Known");
            initial.addProperty("futureField", "preserve");
            durable.save(initial, UUID.randomUUID(), 0L);

            JsonAssetStore<JsonObject> restricted = new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs",
                json -> JsonParser.parseString(json).getAsJsonObject(), value -> {
                    JsonObject known = new JsonObject();
                    known.addProperty("id", value.get("id").getAsString());
                    known.addProperty("name", value.get("name").getAsString());
                    return GSON.toJson(known);
                }, value -> value.get("id").getAsString(), null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
                () -> true);
            try (restricted) {
                restricted.reload("opaque", value -> assertTrue(value.has("futureField")));
                assertEquals("preserve", restricted.get("opaque").get("futureField").getAsString());

                restricted.clearCache();
                JsonAssetStore.AssetStamp stamp = restricted.readStamp("opaque");
                JsonObject validated = restricted.readUncached("opaque");
                restricted.publishValidated("opaque", validated, stamp);

                assertEquals("preserve", restricted.get("opaque").get("futureField").getAsString());
            }
        }
    }

    @Test
    void rejectsWrongCoordinatorRootAndReservedBlobTargets() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            assertThrows(IllegalArgumentException.class, () -> store(tempDir.resolve("other"), coordinator));
            try (JsonAssetStore<TestResource> store = store(assets, coordinator)) {
                Path reserved = assets.resolve(".asset-coordinator/state.json");
                assertTrue(Files.exists(reserved));
                byte[] original = Files.readAllBytes(reserved);
                assertThrows(IllegalStateException.class, () -> store.save(new TestResource("main", "Main"),
                    Map.of(reserved, "bad".getBytes(StandardCharsets.UTF_8)), UUID.randomUUID(), 0L));
                assertArrayEquals(original, Files.readAllBytes(reserved));
            }
        }
    }

    @Test
    void retainsOpaqueAdoptedLineageWithoutPretendingItIsRuntimeUuid() {
        JsonAssetStore.AssetStamp stamp = new JsonAssetStore.AssetStamp("gui", "main", 1L, "legacy:opaque",
            "0".repeat(64), false);

        assertEquals("legacy:opaque", stamp.mutationValue());
        assertTrue(stamp.runtimeMutationId().isEmpty());
        assertThrows(IllegalStateException.class, stamp::mutationId);
    }

    private JsonAssetStore<TestResource> store(Path assets, AssetTransactionCoordinator coordinator) {
        return new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "gui", "GUIs", TestResource::fromJson,
            TestResource::toJson, TestResource::id, null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
            () -> true);
    }

    private record TestResource(String id, String name) {
        private static TestResource fromJson(String json) {
            return GSON.fromJson(json, TestResource.class);
        }

        private String toJson() {
            return GSON.toJson(this);
        }
    }
}
