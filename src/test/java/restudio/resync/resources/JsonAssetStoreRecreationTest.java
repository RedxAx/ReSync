package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.CanonicalProjectMetadataFixture;
import restudio.resync.storage.ProjectMetadataLineage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAssetStoreRecreationTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"custom_content", "recipe_definition", "variable_definition", "timer_definition", "schedule_definition", "worldgen"})
    void recreatesDeletedIdentityInOneTransactionAndReplaysAfterRestart(String type) throws Exception {
        Path assets = tempDir.resolve("assets");
        Path tombstone = assets.resolve(".tombstones").resolve(type).resolve("main.json");
        UUID create = UUID.randomUUID();
        UUID delete = UUID.randomUUID();
        UUID recreate = UUID.randomUUID();
        JsonObject original = payload("Original");
        JsonObject replacement = payload("Replacement");
        ResourcePresentationIntent firstPresentation = new ResourcePresentationIntent("Original", "First/main.json", 1);
        ResourcePresentationIntent nextPresentation = new ResourcePresentationIntent("Replacement", "Second/main.json", 4);
        JsonAssetStore.AggregateCreateResult recreated;
        long sequence;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, GSON)) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            try (JsonAssetStore<JsonObject> store = store(assets, coordinator, type)) {
                JsonAssetStore.AggregateCreateResult created = store.create(original, Map.of(), create, 0L, firstPresentation);
                assertEquals(1L, created.primaryStamp().revision());
                store.delete("main", delete, 1L);
                assertEquals(2L, store.readStamp("main").revision());
                assertTrue(Files.isRegularFile(tombstone));
                assertThrows(ResourceRevisionConflictException.class,
                    () -> store.create(replacement, Map.of(), UUID.randomUUID(), 0L, nextPresentation));
                assertTrue(store.readStamp("main").deleted());
                long before = coordinator.read(snapshot -> snapshot.rootSequence());

                recreated = store.create(replacement, Map.of(), recreate, 2L, nextPresentation);

                sequence = coordinator.read(snapshot -> snapshot.rootSequence());
                assertEquals(before + 1L, sequence);
                assertEquals(3L, recreated.primaryStamp().revision());
                assertEquals(recreate, recreated.primaryStamp().mutationId());
                assertEquals(recreate, recreated.projectMetadataStamp().mutationId());
                assertNotEquals(created.primaryStamp().payloadHash(), recreated.primaryStamp().payloadHash());
                assertFalse(recreated.primaryStamp().deleted());
                assertFalse(Files.exists(tombstone));
                assertFalse(Files.exists(assets.resolve(firstPresentation.path())));
                assertTrue(Files.isRegularFile(assets.resolve(nextPresentation.path())));
                JsonObject metadata = JsonParser.parseString(recreated.canonicalProjectMetadataJson()).getAsJsonObject();
                assertEquals(1, metadata.getAsJsonArray("resources").size());
                assertEquals(nextPresentation.path(), metadata.getAsJsonArray("resources").get(0).getAsJsonObject()
                    .get("path").getAsString());
                AssetTransactionCoordinator.TransactionResult transaction = coordinator.mutation(recreate).orElseThrow().result();
                assertTrue(transaction.states().get(new AssetKey(type, "main")) instanceof Live);
                assertTrue(transaction.states().get(new AssetKey(type + ".tombstone", "main")) instanceof Deleted);
                assertTrue(transaction.states().get(new AssetKey("project_metadata.lineage", "project")) instanceof Live);
                assertTrue(coordinator.mutation(create).isPresent());
                assertTrue(coordinator.mutation(delete).isPresent());
                assertThrows(IllegalStateException.class,
                    () -> store.create(replacement, Map.of(), UUID.randomUUID(), 3L, nextPresentation));
                assertThrows(IllegalStateException.class,
                    () -> store.create(original, Map.of(), recreate, 2L, nextPresentation));
            }
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, GSON);
             JsonAssetStore<JsonObject> store = store(assets, coordinator, type)) {
            assertEquals(recreated.primaryStamp(), store.readStamp("main"));
            assertFalse(Files.exists(tombstone));
            JsonAssetStore.AggregateCreateResult replay = store.create(replacement, Map.of(), recreate, 2L, nextPresentation);
            assertTrue(replay.replayed());
            assertEquals(recreated.primaryStamp(), replay.primaryStamp());
            assertEquals(recreated.projectMetadataStamp(), replay.projectMetadataStamp());
            assertEquals(recreated.canonicalPayloadJson(), replay.canonicalPayloadJson());
            assertEquals(recreated.canonicalProjectMetadataJson(), replay.canonicalProjectMetadataJson());
            assertEquals(sequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
            store.delete("main", delete, 1L);
            assertEquals(recreated.primaryStamp(), store.readStamp("main"));
        }
    }

    private JsonObject payload(String name) {
        JsonObject value = new JsonObject();
        value.addProperty("id", "main");
        value.addProperty("name", name);
        return value;
    }

    private JsonAssetStore<JsonObject> store(Path assets, AssetTransactionCoordinator coordinator, String type) {
        return new JsonAssetStore<>(assets, tempDir.resolve("legacy"), type, "Resources",
            json -> JsonParser.parseString(json).getAsJsonObject(), GSON::toJson, value -> value.get("id").getAsString(),
            null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true, () -> () -> {
            }, (value, existing, serialized) -> JsonAssetStore.mergePayload(existing, serialized, serialized.keySet()),
            ProjectMetadataLineage.writer(assets, GSON));
    }
}
