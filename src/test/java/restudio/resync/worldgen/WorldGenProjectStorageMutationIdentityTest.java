package restudio.resync.worldgen;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenProjectStorageMutationIdentityTest {
    private static final UUID SAVE_MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DELETE_MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STALE_MUTATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESURRECT_MUTATION = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void callerMutationIdentityAndCanonicalHashSurviveRetryDeleteAndReopen(@TempDir Path temporary) throws Exception {
        WorldGenProject project = new WorldGenProject();
        project.setId("main");
        project.getSettings().setSeedPolicy("custom");
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            WorldGenProjectStorage storage = storage(temporary, coordinator);

            storage.saveProject(project, SAVE_MUTATION, 0L);
            FlowResourceMutationStamp live = storage.readMutationStamp("main");
            assertNotNull(live);
            assertEquals("worldgen", live.type());
            assertEquals("main", live.id());
            assertEquals(1L, live.revision());
            assertEquals(SAVE_MUTATION, live.mutationId());
            assertTrue(live.payloadHash().matches("[0-9a-f]{64}"));
            assertFalse(live.deleted());

            storage.saveProject(project, SAVE_MUTATION, 0L);
            assertEquals(live, storage.readMutationStamp("main"));

            WorldGenProject changed = new WorldGenProject();
            changed.setId("main");
            changed.getSettings().setSeedPolicy("different");
            assertThrows(IllegalStateException.class, () -> storage.saveProject(changed, SAVE_MUTATION, 1L));

            storage.closePersistence();
            WorldGenProjectStorage reopened = storage(temporary, coordinator);
            assertEquals(live, reopened.readMutationStamp("main"));
            assertThrows(IllegalStateException.class, () -> reopened.deleteProject("main", SAVE_MUTATION, 1L));

            reopened.deleteProject("main", DELETE_MUTATION, 1L);
            FlowResourceMutationStamp tombstone = reopened.readMutationStamp("main");
            assertNotNull(tombstone);
            assertEquals("worldgen", tombstone.type());
            assertEquals("main", tombstone.id());
            assertEquals(2L, tombstone.revision());
            assertEquals(DELETE_MUTATION, tombstone.mutationId());
            assertEquals(live.payloadHash(), tombstone.payloadHash());
            assertTrue(tombstone.deleted());

            reopened.deleteProject("main", DELETE_MUTATION, 1L);
            assertEquals(tombstone, reopened.readMutationStamp("main"));

            reopened.closePersistence();
            WorldGenProjectStorage reopenedAfterDelete = storage(temporary, coordinator);
            assertEquals(tombstone, reopenedAfterDelete.readMutationStamp("main"));
            assertThrows(ResourceRevisionConflictException.class,
                () -> reopenedAfterDelete.saveProject(project, STALE_MUTATION, 1L));
            reopenedAfterDelete.closePersistence();
        }
    }

    @Test
    void historicalSaveAndDeleteReplaysDoNotRepublishAfterLaterMutations(@TempDir Path temporary) throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            WorldGenProjectStorage storage = storage(temporary, coordinator);
            AtomicInteger changes = new AtomicInteger();
            AtomicInteger recipes = new AtomicInteger();
            storage.setChangeListener(changes::incrementAndGet);
            storage.setGeneratedRecipeUpdater(recipes::incrementAndGet);
            WorldGenProject original = new WorldGenProject();
            original.setId("history");
            original.getSettings().setSeedPolicy("original");
            WorldGenProject changed = new WorldGenProject();
            changed.setId("history");
            changed.getSettings().setSeedPolicy("changed");

            storage.saveProject(original, SAVE_MUTATION, 0L);
            storage.saveProject(changed, STALE_MUTATION, 1L);
            FlowResourceMutationStamp changedStamp = storage.readMutationStamp("history");
            storage.saveProject(original, SAVE_MUTATION, 0L);

            assertEquals(2, changes.get());
            assertEquals(2, recipes.get());
            assertEquals(changedStamp, storage.readMutationStamp("history"));
            assertEquals("changed", storage.getProject("history").getSettings().getSeedPolicy());

            storage.deleteProject("history", DELETE_MUTATION, 2L);
            WorldGenProject restored = new WorldGenProject();
            restored.setId("history");
            restored.getSettings().setSeedPolicy("restored");
            storage.saveProject(restored, RESURRECT_MUTATION, 3L);
            FlowResourceMutationStamp restoredStamp = storage.readMutationStamp("history");
            storage.deleteProject("history", DELETE_MUTATION, 2L);

            assertEquals(4, changes.get());
            assertEquals(4, recipes.get());
            assertEquals(restoredStamp, storage.readMutationStamp("history"));
            assertEquals("restored", storage.getProject("history").getSettings().getSeedPolicy());
            storage.closePersistence();
        }
    }

    @Test
    void committedDerivedRecipeFailureIsVisibleAndRetriedBySaveAndDeleteReplay(@TempDir Path temporary) throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            WorldGenProjectStorage storage = storage(temporary, coordinator);
            AtomicInteger updates = new AtomicInteger();
            AtomicBoolean fail = new AtomicBoolean(true);
            storage.setGeneratedRecipeUpdater(() -> {
                updates.incrementAndGet();
                if (fail.get()) {
                    throw new IllegalStateException("recipe unavailable");
                }
            });
            WorldGenProject project = new WorldGenProject();
            project.setId("retry");

            assertThrows(WorldGenProjectStorage.CommittedMutationException.class,
                () -> storage.saveProject(project, SAVE_MUTATION, 0L));
            assertFalse(storage.readMutationStamp("retry").deleted());
            long afterFailedSave = coordinator.read(snapshot -> snapshot.rootSequence());

            fail.set(false);
            AssetTransactionCoordinator.TransactionResult saveReplay = storage.saveProject(project, SAVE_MUTATION, 0L);
            assertTrue(saveReplay.replay());
            long afterSaveReplay = coordinator.read(snapshot -> snapshot.rootSequence());
            assertEquals(afterFailedSave, afterSaveReplay);

            fail.set(true);
            assertThrows(WorldGenProjectStorage.CommittedMutationException.class,
                () -> storage.deleteProject("retry", DELETE_MUTATION, 1L));
            assertTrue(storage.readMutationStamp("retry").deleted());
            long afterFailedDelete = coordinator.read(snapshot -> snapshot.rootSequence());

            fail.set(false);
            AssetTransactionCoordinator.TransactionResult deleteReplay = storage.deleteProject("retry", DELETE_MUTATION, 1L);
            assertTrue(deleteReplay.replay());
            long afterDeleteReplay = coordinator.read(snapshot -> snapshot.rootSequence());
            assertEquals(afterFailedDelete, afterDeleteReplay);
            assertEquals(4, updates.get());
            storage.closePersistence();
        }
    }

    @Test
    void generatedRebuilderUsesTheReboundCoordinatorWithoutReadingTheOldRoot(@TempDir Path temporary) throws Exception {
        Path current = Files.createDirectory(temporary.resolve("current-rebuilder"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate-rebuilder"));
        AssetPersistenceGate gate = new AssetPersistenceGate(current);
        try (AssetTransactionCoordinator currentCoordinator = coordinator(current);
             AssetTransactionCoordinator candidateCoordinator = coordinator(candidate)) {
            WorldGenProjectStorage storage = new WorldGenProjectStorage(current.toFile(), LegacyRuntimeActivationGate.runtime(current),
                gate, currentCoordinator);
            WorldGenGeneratedOutputRebuilder rebuilder = new WorldGenGeneratedOutputRebuilder(storage,
                new WorldGenDatapackCompiler(null), currentCoordinator);
            storage.quiescePersistence();
            storage.rebindPersistence(candidate, candidateCoordinator);
            gate.rebind(candidate);
            storage.resumePersistence();

            assertEquals(candidateCoordinator, storage.currentCoordinator());
            rebuilder.ensureRecipe();
            boolean candidateHasRecipe = candidateCoordinator.read(
                snapshot -> snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).isPresent());
            boolean currentHasNoRecipe = currentCoordinator.read(
                snapshot -> snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).isEmpty());
            assertTrue(candidateHasRecipe);
            assertTrue(currentHasNoRecipe);
            storage.closePersistence();
        }
    }

    @Test
    void reloadValidatorRunsOutsideTheReadLease(@TempDir Path temporary) throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
            WorldGenProjectStorage storage = new WorldGenProjectStorage(temporary.toFile(),
                LegacyRuntimeActivationGate.runtime(temporary), gate, coordinator);
            WorldGenProject project = new WorldGenProject();
            project.setId("validator");
            storage.saveProject(project, SAVE_MUTATION, 0L);
            AtomicBoolean completed = new AtomicBoolean();

            storage.reloadProject("validator", ignored -> {
                Thread toggle = new Thread(() -> {
                    gate.quiesce();
                    gate.resume();
                    completed.set(true);
                });
                toggle.setDaemon(true);
                toggle.start();
                try {
                    toggle.join(2000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Validator was interrupted", exception);
                }
                assertTrue(completed.get());
            });
            storage.closePersistence();
        }
    }

    @Test
    void projectReadsAreDefensiveCopies(@TempDir Path temporary) throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            WorldGenProjectStorage storage = storage(temporary, coordinator);
            WorldGenProject source = new WorldGenProject();
            source.setId("defensive");
            source.getSettings().setSeedPolicy("persisted");
            storage.saveProject(source, SAVE_MUTATION, 0L);

            WorldGenProject first = storage.getProject("defensive");
            first.getSettings().setSeedPolicy("caller-mutated");
            WorldGenProject second = storage.getProject("defensive");

            assertEquals("persisted", second.getSettings().getSeedPolicy());
            storage.closePersistence();
        }
    }

    @Test
    void preservesUnknownMetadataPublishesOnlyCommittedChangesAndFailsClosed(@TempDir Path temporary) throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator(temporary)) {
            AssetTransactionCoordinator.Snapshot baseline = coordinator.read(snapshot -> snapshot);
            JsonObject opaque = new JsonObject();
            opaque.addProperty("retained", true);
            JsonObject resourceOpaque = new JsonObject();
            resourceOpaque.addProperty("retained", true);
            JsonObject resource = new JsonObject();
            resource.addProperty("type", "worldgen");
            resource.addProperty("id", "durable");
            resource.addProperty("path", "legacy/durable.json");
            resource.add("futurePresentation", resourceOpaque);
            JsonObject unrelated = new JsonObject();
            unrelated.addProperty("type", "flow");
            unrelated.addProperty("id", "unrelated");
            unrelated.addProperty("path", "flows/unrelated.json");
            unrelated.addProperty("futureField", "retained");
            JsonArray resources = new JsonArray();
            resources.add(resource);
            resources.add(unrelated);
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                baseline.project(),
                List.of(),
                List.of(
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("futureWorldGen"), opaque),
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("resources"), resources))));
            WorldGenProjectStorage storage = storage(temporary, coordinator);
            AtomicInteger changes = new AtomicInteger();
            AtomicInteger recipes = new AtomicInteger();
            storage.setChangeListener(changes::incrementAndGet);
            storage.setGeneratedRecipeUpdater(recipes::incrementAndGet);
            WorldGenProject project = new WorldGenProject();
            project.setId("durable");

            storage.saveProject(project, SAVE_MUTATION, 0L);
            storage.saveProject(project, SAVE_MUTATION, 0L);

            assertEquals(1, changes.get());
            assertEquals(1, recipes.get());
            boolean futureWorldGenRetained = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonObject("futureWorldGen").get("retained").getAsBoolean());
            assertTrue(futureWorldGenRetained);
            boolean durableResourcePresent = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonArray("resources").asList().stream()
                .anyMatch(element -> resource(element, "worldgen", "durable")));
            assertTrue(durableResourcePresent);
            boolean durableFuturePresentationRetained = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonArray("resources").asList().stream()
                .filter(element -> resource(element, "worldgen", "durable"))
                .map(JsonElement::getAsJsonObject)
                .anyMatch(element -> element.getAsJsonObject("futurePresentation").get("retained").getAsBoolean()));
            assertTrue(durableFuturePresentationRetained);
            storage.setChangeListener(() -> {
                throw new IllegalStateException("listener failure");
            });
            WorldGenProject changed = new WorldGenProject();
            changed.setId("durable");
            changed.getSettings().setSeedPolicy("changed");
            storage.saveProject(changed, STALE_MUTATION, 1L);
            assertEquals(2L, storage.readMutationStamp("durable").revision());
            storage.deleteProject("durable", DELETE_MUTATION, 2L);
            assertTrue(storage.readMutationStamp("durable").deleted());
            boolean futureWorldGenRetainedAfterDelete = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonObject("futureWorldGen").get("retained").getAsBoolean());
            assertTrue(futureWorldGenRetainedAfterDelete);
            boolean durableResourceRemoved = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonArray("resources").asList().stream()
                .noneMatch(element -> resource(element, "worldgen", "durable")));
            assertTrue(durableResourceRemoved);
            boolean unrelatedFutureFieldRetained = coordinator.read(snapshot -> snapshot.metadata().document().getAsJsonArray("resources").asList().stream()
                .filter(element -> resource(element, "flow", "unrelated"))
                .map(JsonElement::getAsJsonObject)
                .anyMatch(element -> "retained".equals(element.get("futureField").getAsString())));
            assertTrue(unrelatedFutureFieldRetained);

            storage.closePersistence();
            assertThrows(IllegalStateException.class, () -> storage.getProject("durable"));
            assertThrows(IllegalStateException.class, () -> storage.reloadProject("durable", null));
            long rootSequence = coordinator.read(snapshot -> snapshot.rootSequence());
            assertEquals(4L, rootSequence);
        }
    }

    @Test
    void worldGenSerializerRetainsUnknownProjectAndNestedFieldsAcrossPojoRoundTrip() {
        WorldGenProject source = new WorldGenProject();
        source.setId("opaque");
        JsonObject raw = JsonParser.parseString(WorldGenSerializer.serializeProject(source)).getAsJsonObject();
        raw.addProperty("futureProject", true);
        raw.getAsJsonObject("settings").addProperty("futureSetting", "keep");
        JsonArray futureGraph = new JsonArray();
        futureGraph.add("keep");
        raw.getAsJsonObject("terrainGraph").add("futureGraph", futureGraph);
        JsonObject futureNode = new JsonObject();
        futureNode.addProperty("type", "future");
        futureNode.addProperty("x", 1.0D);
        futureNode.addProperty("y", 2.0D);
        futureNode.add("inputValues", new JsonObject());
        futureNode.addProperty("futureNode", 9);
        raw.getAsJsonObject("terrainGraph").getAsJsonObject("nodes").add("node", futureNode);

        WorldGenProject decoded = WorldGenSerializer.deserializeProject(raw.toString());
        decoded.getSettings().setSeedPolicy("changed");
        decoded.getTerrainGraph().getNodes().get("node").setX(8.0D);

        JsonObject persisted = JsonParser.parseString(WorldGenSerializer.serializeProject(decoded)).getAsJsonObject();
        assertTrue(persisted.get("futureProject").getAsBoolean());
        assertEquals("keep", persisted.getAsJsonObject("settings").get("futureSetting").getAsString());
        assertEquals(List.of("keep"), persisted.getAsJsonObject("terrainGraph").get("futureGraph").getAsJsonArray().asList().stream()
            .map(JsonElement::getAsString).toList());
        assertEquals("changed", persisted.getAsJsonObject("settings").get("seedPolicy").getAsString());
        assertEquals(8.0D, persisted.getAsJsonObject("terrainGraph").getAsJsonObject("nodes").getAsJsonObject("node").get("x").getAsDouble());
        assertEquals(9, persisted.getAsJsonObject("terrainGraph").getAsJsonObject("nodes").getAsJsonObject("node").get("futureNode").getAsInt());
    }

    @Test
    void rebindRequiresTheExactOpenCandidateCoordinator(@TempDir Path temporary) throws Exception {
        Path current = Files.createDirectory(temporary.resolve("current"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        AssetPersistenceGate gate = new AssetPersistenceGate(current);
        try (AssetTransactionCoordinator currentCoordinator = coordinator(current);
             AssetTransactionCoordinator candidateCoordinator = coordinator(candidate)) {
            WorldGenProjectStorage storage = new WorldGenProjectStorage(current.toFile(), LegacyRuntimeActivationGate.runtime(current), gate, currentCoordinator);
            storage.quiescePersistence();

            assertThrows(IOException.class, () -> storage.rebindPersistence(candidate, currentCoordinator));
            storage.rebindPersistence(candidate, candidateCoordinator);
            assertThrows(IllegalStateException.class, () -> storage.getProject("candidate"));
            gate.rebind(candidate);
            storage.resumePersistence();
            WorldGenProject project = new WorldGenProject();
            project.setId("candidate");
            storage.saveProject(project, SAVE_MUTATION, 0L);

            boolean candidatePresent = candidateCoordinator.read(snapshot -> snapshot.state(new AssetTransactionCoordinator.AssetKey("worldgen", "candidate")).isPresent());
            assertTrue(candidatePresent);
            boolean currentCandidateAbsent = currentCoordinator.read(snapshot -> snapshot.state(new AssetTransactionCoordinator.AssetKey("worldgen", "candidate")).isEmpty());
            assertTrue(currentCandidateAbsent);
            storage.closePersistence();
        }
    }

    private WorldGenProjectStorage storage(Path root, AssetTransactionCoordinator coordinator) {
        return new WorldGenProjectStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), new AssetPersistenceGate(root), coordinator);
    }

    private AssetTransactionCoordinator coordinator(Path root) throws Exception {
        Path assets = Files.createDirectories(root.resolve("assets"));
        return new AssetTransactionCoordinator(assets, new Gson());
    }

    private boolean resource(JsonElement element, String type, String id) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        return object.has("type") && object.has("id")
            && type.equals(object.get("type").getAsString()) && id.equals(object.get("id").getAsString());
    }
}
