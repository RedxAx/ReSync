package restudio.resync.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReTextServiceCacheTest {
    private ReSyncJsonResourceStorage storage;
    private ReTextService text;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = new ReSyncJsonResourceStorage(plugin);
        text = new ReTextService(storage);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void savedAndDeletedTextResourcesInvalidateCachedRuntimeValues() {
        storage.save(ReSyncResourceCatalog.TEXT_TEMPLATE, list("colors", "red"));
        assertEquals(List.of("red"), text.lines("colors"));

        storage.save(ReSyncResourceCatalog.TEXT_TEMPLATE, list("colors", "blue"));
        assertEquals(List.of("blue"), text.lines("colors"));

        storage.delete(ReSyncResourceCatalog.TEXT_TEMPLATE, "colors");
        assertNull(text.resource("colors"));
    }

    private JsonObject list(String id, String value) {
        JsonObject resource = new JsonObject();
        resource.addProperty("id", id);
        resource.addProperty("kind", "list");
        JsonArray values = new JsonArray();
        values.add(value);
        resource.add("values", values);
        return resource;
    }
}
