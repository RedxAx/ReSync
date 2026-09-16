package restudio.resync.customcontent;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentServicePersistenceRootTest {
    @TempDir
    Path temporary;

    @Test
    void vanillaProviderUsesActiveContentPersistenceRoot() throws Exception {
        MockBukkit.mock();
        AssetPersistenceGate assetsGate = null;
        AssetTransactionCoordinator coordinator = null;
        CustomContentStorage storage = null;
        try {
            JavaPlugin plugin = MockBukkit.createMockPlugin();
            Path pluginRoot = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path activeRoot = Files.createDirectory(temporary.resolve("active"));
            assertNotEquals(pluginRoot, activeRoot);

            assetsGate = new AssetPersistenceGate(activeRoot);
            coordinator = AssetTransactionCoordinator.open(activeRoot.resolve("assets"), new Gson());
            CanonicalProjectMetadataFixture.seed(coordinator);
            storage = new CustomContentStorage(plugin, activeRoot, new ItemAttributeSchemaService(),
                LegacyRuntimeActivationGate.runtime(activeRoot), assetsGate, coordinator);
            CustomContentService service = new CustomContentService(storage, null, null);

            Path expected = activeRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize();
            VanillaContentProvider provider = service.getVanillaProvider();
            CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(activeRoot, provider);

            assertEquals(expected, provider.persistenceRoot());
            assertEquals(expected, participant.root());
            assertTrue(Files.exists(expected));
            assertFalse(Files.exists(pluginRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME)));
        } finally {
            if (storage != null) {
                assetsGate.quiesce();
                storage.close();
            }
            if (coordinator != null) {
                coordinator.close();
            }
            MockBukkit.unmock();
        }
    }
}
