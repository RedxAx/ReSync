package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAssetInventoryTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void oneInventoryValidatesMultipleStoresWithoutAnotherTraversal() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
        List<JsonAssetStore<JsonObject>> stores = new ArrayList<>();
        try {
            for (String type : ReSyncResourceCatalog.jsonStorageTypes()) {
                ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
                JsonAssetStore<JsonObject> store = jsonStore(assets, coordinator, type, resource.defaultFolder());
                stores.add(store);
                JsonObject value = new JsonObject();
                value.addProperty("id", "asset_" + type);
                value.addProperty("name", resource.displayName());
                store.save(value, UUID.randomUUID(), 0L);
            }
            AtomicInteger observed = new AtomicInteger();

            JsonAssetInventory inventory = JsonAssetInventory.scan(assets, ignored -> observed.incrementAndGet());
            int traversalCount = observed.get();
            Map<String, List<String>> expected = ReSyncResourceCatalog.jsonStorageTypes().stream()
                .collect(Collectors.toMap(type -> type, type -> List.of("asset_" + type)));
            Map<String, List<String>> actual = inventory.jsonFilesUnder(assets).stream()
                .filter(entry -> !entry.type().isBlank())
                .collect(Collectors.groupingBy(JsonAssetInventory.Entry::type,
                    Collectors.mapping(JsonAssetInventory.Entry::id, Collectors.toList())));

            assertEquals(expected, actual);
            assertTrue(traversalCount > 2);
            assertEquals(traversalCount, inventory.visitedPathCount());
            for (JsonAssetStore<JsonObject> store : stores) {
                store.healthCheckLocal(inventory);
            }
            assertEquals(traversalCount, observed.get());
        } finally {
            for (JsonAssetStore<JsonObject> store : stores.reversed()) {
                store.close();
            }
            coordinator.close();
        }
    }

    @Test
    void canonicalDoubleUnderscoreIdSurvivesDefaultFolderSaveHealthDeleteHealth() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            store.save(new TestResource("menu__v2", "Menu"), UUID.randomUUID(), 0L);

            store.healthCheck();
            assertTrue(Files.exists(assets.resolve("GUIs/menu__v2.json")));

            store.delete("menu__v2", UUID.randomUUID(), 1L);
            store.healthCheck();
            assertTrue(Files.exists(assets.resolve(".tombstones/gui/menu__v2.json")));
        }
    }

    @Test
    void canonicalDoubleUnderscoreIdSurvivesCustomFolderSaveHealthDeleteHealth() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<FolderResource> store = new JsonAssetStore<>(assets, tempDir.resolve("legacy-custom"), "gui", "GUIs",
                 FolderResource::fromJson, FolderResource::toJson, FolderResource::id, FolderResource::folder,
                 LegacyRuntimeActivationGate.runtime(tempDir), coordinator, () -> true)) {
            store.save(new FolderResource("custom__v2", "Custom", "GUIs/Nested/Menus"), UUID.randomUUID(), 0L);

            store.healthCheck();
            assertTrue(Files.exists(assets.resolve("GUIs/Nested/Menus/custom__v2.json")));

            store.delete("custom__v2", UUID.randomUUID(), 1L);
            store.healthCheck();
            assertTrue(Files.exists(assets.resolve(".tombstones/gui/custom__v2.json")));
        }
    }

    @Test
    void declaredCurrentIdentityWinsAndLegacyDuplicateRemainsFailClosed() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            store.save(new TestResource("menu__v2", "Current"), UUID.randomUUID(), 0L);
            store.save(new TestResource("legacy", "Current Legacy Id"), UUID.randomUUID(), 0L);
            Path legacy = assets.resolve("Legacy/gui__legacy.json");
            Files.createDirectories(legacy.getParent());
            JsonObject legacyPayload = JsonParser.parseString(Files.readString(assets.resolve("GUIs/legacy.json"))).getAsJsonObject();
            legacyPayload.remove("resourceType");
            Files.writeString(legacy, GSON.toJson(legacyPayload));

            JsonAssetInventory inventory = JsonAssetInventory.scan(assets);
            List<JsonAssetInventory.Entry> typed = inventory.filesForType("gui");

            assertTrue(typed.stream().anyMatch(entry -> entry.id().equals("menu__v2")));
            assertTrue(typed.stream().anyMatch(entry -> entry.path().equals(legacy) && entry.id().equals("legacy")));
            IOException failure = assertThrows(IOException.class, () -> store.healthCheckLocal(inventory));
            assertTrue(failure.getMessage().contains("Duplicate gui asset identity: legacy"));
        }
    }

    @Test
    void sharedInventoryRetainsDuplicatePayloadHashAndUnexpectedFileValidation() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            store.save(new TestResource("main", "Main"), UUID.randomUUID(), 0L);
            Path canonical = assets.resolve("GUIs/main.json");
            Path duplicate = assets.resolve("Other/main.json");
            Files.createDirectories(duplicate.getParent());
            Files.copy(canonical, duplicate);

            IOException duplicateFailure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));
            assertTrue(duplicateFailure.getMessage().contains("Duplicate gui asset identity"));

            Files.delete(duplicate);
            Files.writeString(canonical, Files.readString(canonical).replace("Main", "Changed"));
            assertThrows(IOException.class, () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));

            Files.delete(canonical);
            Files.writeString(assets.resolve("GUIs/unrecognized.json"), "{\"id\":\"unrecognized\"}");
            IOException unexpectedFailure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));
            assertTrue(unexpectedFailure.getMessage().contains("Unrecognized gui asset file"));
        }
    }

    @Test
    void sharedInventoryRetainsTombstoneValidation() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            Path tombstones = assets.resolve(".tombstones/gui");
            Files.createDirectories(tombstones);
            Files.writeString(tombstones.resolve(".json"), "{}");

            IOException failure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));

            assertTrue(failure.getMessage().contains("Invalid JSON resource tombstone file"));
        }
    }

    @Test
    void inventoryRejectsSymbolicLinksWithoutFollowingThem() throws Exception {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        Path external = tempDir.resolve("external.json");
        Files.writeString(external, "{}");
        Path link = assets.resolve("linked.json");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + failure.getMessage());
            return;
        }

        IOException failure = assertThrows(IOException.class, () -> JsonAssetInventory.scan(assets));

        assertTrue(failure.getMessage().contains("Rejects Symbolic Links"));
    }

    @Test
    void inventoryRejectsDirectorySymbolicLinksWithoutFollowingThem() throws Exception {
        Path assets = tempDir.resolve("assets-directory-link");
        Files.createDirectories(assets);
        Path external = tempDir.resolve("external-directory");
        Files.createDirectories(external);
        Files.writeString(external.resolve("outside.json"), "{}");
        Path link = assets.resolve("linked-directory");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.assumeTrue(false, "Directory symbolic links are unavailable: " + failure.getMessage());
            return;
        }

        IOException failure = assertThrows(IOException.class, () -> JsonAssetInventory.scan(assets));

        assertTrue(failure.getMessage().contains("Rejects Symbolic Links"));
    }

    @Test
    void inventoryRejectsSymbolicRoot() throws Exception {
        Path target = tempDir.resolve("root-target");
        Files.createDirectories(target);
        Path rootLink = tempDir.resolve("root-link");
        try {
            Files.createSymbolicLink(rootLink, target);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.assumeTrue(false, "Root symbolic links are unavailable: " + failure.getMessage());
            return;
        }

        assertThrows(IOException.class, () -> JsonAssetInventory.scan(rootLink));
    }

    @Test
    void inventoryRejectsTraversalBeyondItsBoundedDepth() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path nested = assets;
        for (int depth = 0; depth < 65; depth++) {
            nested = nested.resolve("d");
        }
        Files.createDirectories(nested);

        IOException failure = assertThrows(IOException.class, () -> JsonAssetInventory.scan(assets));

        assertTrue(failure.getMessage().contains("Exceeded Maximum Depth"));
    }

    @Test
    void inventorySkipsCoordinatorHistoryTrees() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path history = assets.resolve(".transactions");
        for (int depth = 0; depth < 70; depth++) {
            history = history.resolve("history");
        }
        Files.createDirectories(history);
        Files.writeString(history.resolve("payload.json"), "{}");
        Files.createDirectories(assets.resolve("GUIs"));
        Files.writeString(assets.resolve("GUIs/menu.json"), "{\"resourceType\":\"gui\",\"id\":\"menu\"}");
        List<Path> observed = new ArrayList<>();

        JsonAssetInventory inventory = JsonAssetInventory.scan(assets, observed::add);

        assertEquals(1, inventory.filesForType("gui").size());
        assertTrue(observed.stream().noneMatch(path -> path.startsWith(assets.resolve(".transactions").resolve("history"))));
    }

    @Test
    void inventoryAndStoreRejectDifferentRoots() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path other = tempDir.resolve("other");
        Files.createDirectories(other);
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            IOException failure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(other)));

            assertTrue(failure.getMessage().contains("Inventory Root Does Not Match Store Root"));
        }
    }

    @Test
    void freshInventoryObservesFilesCreatedAfterThePreviousCall() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            JsonAssetInventory before = JsonAssetInventory.scan(assets);
            Files.createDirectories(assets.resolve("GUIs"));
            Files.writeString(assets.resolve("GUIs/unexpected.json"), "{\"id\":\"unexpected\"}");

            store.healthCheckLocal(before);
            IOException failure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));

            assertTrue(failure.getMessage().contains("Unrecognized gui asset file"));
        }
    }

    @Test
    void emptyTombstoneRootIsValidButNestedDirectoriesAreRejected() throws Exception {
        Path assets = tempDir.resolve("assets");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON);
             JsonAssetStore<TestResource> store = store(assets, coordinator, "gui", "GUIs")) {
            Path tombstones = assets.resolve(".tombstones/gui");
            Files.createDirectories(tombstones);
            store.healthCheckLocal(JsonAssetInventory.scan(assets));

            Files.createDirectories(tombstones.resolve("nested"));
            IOException failure = assertThrows(IOException.class,
                () -> store.healthCheckLocal(JsonAssetInventory.scan(assets)));

            assertTrue(failure.getMessage().contains("Invalid JSON resource tombstone directory"));
        }
    }

    private JsonAssetStore<TestResource> store(Path assets, AssetTransactionCoordinator coordinator, String type, String folder) {
        return new JsonAssetStore<>(assets, tempDir.resolve("legacy-" + type), type, folder, TestResource::fromJson,
            TestResource::toJson, TestResource::id, null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
            () -> true);
    }

    private JsonAssetStore<JsonObject> jsonStore(Path assets, AssetTransactionCoordinator coordinator, String type, String folder) {
        return new JsonAssetStore<>(assets, tempDir.resolve("legacy-" + type), type, folder,
            json -> JsonParser.parseString(json).getAsJsonObject(), GSON::toJson,
            value -> value.get("id").getAsString(), null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
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

    private record FolderResource(String id, String name, String folder) {
        private static FolderResource fromJson(String json) {
            return GSON.fromJson(json, FolderResource.class);
        }

        private String toJson() {
            return GSON.toJson(this);
        }
    }
}
