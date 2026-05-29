package restudio.resync.resources;

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

    @Test
    void savesLoadsListsAndDeletesAssetFiles() {
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
        assertTrue(Files.exists(tempDir.resolve("assets").resolve("GUIs").resolve("gui__main.json")));

        store.delete("main");

        assertFalse(Files.exists(tempDir.resolve("assets").resolve("GUIs").resolve("gui__main.json")));
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
        Files.writeString(legacy.resolve("main.json"), "main:Main");
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

        assertTrue(Files.exists(tempDir.resolve("assets").resolve("Customization").resolve("Scoreboards").resolve("scoreboard__main.json")));
        assertFalse(Files.exists(legacy));
    }

    private record TestResource(String id, String name) {
        private static TestResource fromJson(String json) {
            String[] parts = json.split(":", 2);
            return new TestResource(parts[0], parts.length > 1 ? parts[1] : "");
        }

        private String toJson() {
            return id + ":" + name;
        }
    }
}
