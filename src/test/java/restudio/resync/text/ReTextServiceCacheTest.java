package restudio.resync.text;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReTextServiceCacheTest {
    private ReSyncJsonResourceStorage storage;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;
    private ReTextService text;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        assetsGate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        text = new ReTextService(storage);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (storage != null) {
                storage.closePersistence();
            }
            if (assetsGate != null) {
                assetsGate.quiesce();
            }
            if (coordinator != null) {
                coordinator.close();
            }
        } finally {
            MockBukkit.unmock();
        }
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
