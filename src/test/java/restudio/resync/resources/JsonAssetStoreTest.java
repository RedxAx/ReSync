package restudio.resync.resources;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAssetStoreTest {
    @TempDir
    Path tempDir;
    private static final Gson GSON = new Gson();

    @Test
    void savesLoadsListsAndDeletesAssetFiles() throws Exception {
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            tempDir.resolve("legacy"),
            "gui",
            "GUIs",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        store.save(new TestResource("main", "Main"));

        assertEquals("Main", store.get("main").name());
        assertEquals("main", store.listIds().getFirst());
        Path file = tempDir.resolve("assets").resolve("GUIs").resolve("main.json");
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("\"resourceType\":\"gui\""));

        store.delete("main");

        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsUnsafeIds() {
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            tempDir.resolve("legacy"),
            "tab",
            "Customization/Tabs",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        assertThrows(IllegalArgumentException.class, () -> store.save(new TestResource("../bad", "Bad")));
    }

    @Test
    void migratesLegacyFilesToAssetFolder() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("main.json"), GSON.toJson(new TestResource("main", "Main")));
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            legacy,
            "scoreboard",
            "Customization/Scoreboards",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        store.migrateLegacyAssets();

        Path file = tempDir.resolve("assets").resolve("Customization").resolve("Scoreboards").resolve("main.json");
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("\"resourceType\":\"scoreboard\""));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void migratesPrefixedAssetsInPlace() throws Exception {
        Path folder = tempDir.resolve("assets").resolve("GUIs").resolve("Custom");
        Files.createDirectories(folder);
        Path legacy = folder.resolve("gui__main.json");
        Files.writeString(legacy, GSON.toJson(new TestResource("main", "Main")));
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            tempDir.resolve("legacy"),
            "gui",
            "GUIs",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        store.migrateLegacyAssets();

        Path migrated = folder.resolve("main.json");
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(migrated));
        assertTrue(Files.readString(migrated).contains("\"resourceType\":\"gui\""));
        assertEquals("main", store.listIds().getFirst());
    }

    @Test
    void prefixedMigrationDoesNotOverwriteDifferentTypedIdOnlyAsset() throws Exception {
        Path folder = tempDir.resolve("assets").resolve("Shared");
        Files.createDirectories(folder);
        Path existing = folder.resolve("main.json");
        Path legacy = folder.resolve("gui__main.json");
        Files.writeString(existing, "{\"id\":\"main\",\"resourceType\":\"scoreboard\"}");
        Files.writeString(legacy, GSON.toJson(new TestResource("main", "Main")));
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            tempDir.resolve("legacy"),
            "gui",
            "GUIs",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        store.migrateLegacyAssets();

        Path migrated = tempDir.resolve("assets").resolve("GUIs").resolve("gui").resolve("main.json");
        assertTrue(Files.exists(existing));
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(migrated));
        assertTrue(Files.readString(existing).contains("\"resourceType\":\"scoreboard\""));
        assertTrue(Files.readString(migrated).contains("\"resourceType\":\"gui\""));
    }

    @Test
    void flatLegacyMigrationDoesNotOverwriteDifferentTypedIdOnlyAsset() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Path targetFolder = tempDir.resolve("assets").resolve("GUIs");
        Files.createDirectories(legacy);
        Files.createDirectories(targetFolder);
        Path existing = targetFolder.resolve("main.json");
        Files.writeString(existing, "{\"id\":\"main\",\"resourceType\":\"scoreboard\"}");
        Files.writeString(legacy.resolve("main.json"), GSON.toJson(new TestResource("main", "Main")));
        JsonAssetStore<TestResource> store = new JsonAssetStore<>(
            tempDir.resolve("assets"),
            legacy,
            "gui",
            "GUIs",
            TestResource::fromJson,
            TestResource::toJson,
            TestResource::id
        );

        store.migrateLegacyAssets();

        Path migrated = tempDir.resolve("assets").resolve("GUIs").resolve("gui").resolve("main.json");
        assertTrue(Files.exists(existing));
        assertTrue(Files.exists(migrated));
        assertFalse(Files.exists(legacy));
        assertTrue(Files.readString(existing).contains("\"resourceType\":\"scoreboard\""));
        assertTrue(Files.readString(migrated).contains("\"resourceType\":\"gui\""));
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
