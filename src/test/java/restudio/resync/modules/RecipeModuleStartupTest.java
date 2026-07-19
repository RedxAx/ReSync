package restudio.resync.modules;

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
import restudio.resync.resources.ReSyncResourceCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeModuleStartupTest {
    private JavaPlugin plugin;
    private RecipeModule module;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (module != null) {
            module.stop(null);
        }
        MockBukkit.unmock();
    }

    @Test
    void persistedValidRecipeRegistersAfterRestartEvenWhenAnEarlierRecipeIsMalformed() {
        ReSyncJsonResourceStorage writer = new ReSyncJsonResourceStorage(plugin);
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

        ReSyncJsonResourceStorage runtimeStorage = new ReSyncJsonResourceStorage(plugin);
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

        runtimeStorage.delete(ReSyncResourceCatalog.RECIPE_DEFINITION, "live_added");
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "live_added")));
    }

    private JsonObject recipe(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
