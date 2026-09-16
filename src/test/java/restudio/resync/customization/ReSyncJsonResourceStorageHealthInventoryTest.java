package restudio.resync.customization;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReSyncJsonResourceStorageHealthInventoryTest {
    private ReSyncJsonResourceStorage storage;
    private AssetTransactionCoordinator coordinator;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.closePersistence();
        }
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void initializationAndEachHealthCheckBuildExactlyOneCallScopedInventory() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path assets = scope.resolve("assets");
        Files.createDirectories(assets);
        coordinator = new AssetTransactionCoordinator(assets, new Gson());
        AtomicInteger scans = new AtomicInteger();
        ReSyncJsonResourceStorage.AssetInventoryFactory factory = root -> {
            scans.incrementAndGet();
            return JsonAssetInventory.scan(root);
        };

        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope),
            new AssetPersistenceGate(scope), coordinator, factory);
        assertEquals(1, scans.get());

        storage.healthCheckPersistenceLocal();
        assertEquals(2, scans.get());

        storage.healthCheckPersistenceLocal();
        assertEquals(3, scans.get());
    }
}
