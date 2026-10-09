package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeModuleStartupTest {
    private JavaPlugin plugin;
    private RecipeModule module;
    private ReSyncJsonResourceStorage writer;
    private ReSyncJsonResourceStorage runtimeStorage;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        assetsGate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (module != null) {
                module.stop(null);
            }
            if (runtimeStorage != null) {
                runtimeStorage.closePersistence();
            }
            if (writer != null) {
                writer.closePersistence();
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
    void persistedValidRecipeRegistersAfterRestartEvenWhenAnEarlierRecipeIsMalformed() {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        writer = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        writer.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "a_invalid",
              "enabled": true,
              "type": "shaped",
              "output": {"material": "STONE"},
              "shape": ["AAAA"],
              "keys": {"A": {"material": "STICK"}}
            }
            """));
        writer.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "z_valid",
              "enabled": true,
              "type": "shapeless",
              "output": {"material": "DIAMOND"},
              "ingredients": [{"material": "STICK"}]
            }
            """));

        runtimeStorage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        module = new RecipeModule(plugin, runtimeStorage);
        module.startRecipeLifecycle();

        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "a_invalid")));
        Recipe registered = Bukkit.getRecipe(new NamespacedKey(plugin, "z_valid"));
        ShapelessRecipe shapeless = assertInstanceOf(ShapelessRecipe.class, registered);
        assertNotNull(shapeless);
        assertEquals(Material.DIAMOND, shapeless.getResult().getType());

        runtimeStorage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "live_added",
              "enabled": true,
              "type": "shapeless",
              "output": {"material": "EMERALD"},
              "ingredients": [{"material": "COBBLESTONE"}]
            }
            """));

        Recipe liveAdded = Bukkit.getRecipe(new NamespacedKey(plugin, "live_added"));
        assertEquals(Material.EMERALD, assertInstanceOf(ShapelessRecipe.class, liveAdded).getResult().getType());

        runtimeStorage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "live_added",
              "enabled": true,
              "type": "shapeless",
              "output": {"material": "GOLD_INGOT"},
              "ingredients": [{"material": "COBBLESTONE"}]
            }
            """));
        Recipe updated = Bukkit.getRecipe(new NamespacedKey(plugin, "live_added"));
        assertEquals(Material.GOLD_INGOT, assertInstanceOf(ShapelessRecipe.class, updated).getResult().getType());

        runtimeStorage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "live_added",
              "enabled": false,
              "type": "shapeless",
              "output": {"material": "GOLD_INGOT"},
              "ingredients": [{"material": "COBBLESTONE"}]
            }
            """));
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "live_added")));

        runtimeStorage.delete(ReSyncResourceCatalog.RECIPE_DEFINITION, "live_added");
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "live_added")));

        CompletableFuture.runAsync(() -> runtimeStorage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "queued",
              "type": "shapeless",
              "output": {"material": "DIAMOND"},
              "ingredients": [{"material": "STICK"}]
            }
            """))).join();
        module.stop(null);
        MockBukkit.getMock().getScheduler().performOneTick();
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "queued")));
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "z_valid")));
    }

    @Test
    void collidingRecipeKeyFallsBackWhenOneDefinitionFailsOrIsDeleted() {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        writer = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        writer.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "Collision",
              "enabled": true,
              "type": "shaped",
              "output": {"material": "STONE"},
              "shape": ["AAAA"],
              "keys": {"A": {"material": "STICK"}}
            }
            """));
        writer.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "collision",
              "enabled": true,
              "type": "shapeless",
              "output": {"material": "DIAMOND"},
              "ingredients": [{"material": "STICK"}]
            }
            """));

        runtimeStorage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
        module = new RecipeModule(plugin, runtimeStorage);
        module.startRecipeLifecycle();
        NamespacedKey key = new NamespacedKey(plugin, "collision");
        assertEquals(Material.DIAMOND, assertInstanceOf(ShapelessRecipe.class, Bukkit.getRecipe(key)).getResult().getType());

        runtimeStorage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe("""
            {
              "id": "Collision",
              "enabled": false,
              "type": "shaped",
              "output": {"material": "STONE"},
              "shape": ["A"],
              "keys": {"A": {"material": "STICK"}}
            }
            """));
        assertEquals(Material.DIAMOND, assertInstanceOf(ShapelessRecipe.class, Bukkit.getRecipe(key)).getResult().getType());

        runtimeStorage.delete(ReSyncResourceCatalog.RECIPE_DEFINITION, "Collision");
        assertEquals(Material.DIAMOND, assertInstanceOf(ShapelessRecipe.class, Bukkit.getRecipe(key)).getResult().getType());
        runtimeStorage.delete(ReSyncResourceCatalog.RECIPE_DEFINITION, "collision");
        assertNull(Bukkit.getRecipe(key));
    }

    private JsonObject recipe(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
