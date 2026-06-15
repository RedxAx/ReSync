package restudio.resync.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageProfileServiceTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void convertsOffersIntoMerchantRecipes() {
        TestVillageProfileService service = new TestVillageProfileService("""
            {
              "enabled": true,
              "maxUses": 8,
              "offers": [
                {
                  "cost": "minecraft:emerald",
                  "costAmount": 2,
                  "cost2": "minecraft:book",
                  "cost2Amount": 1,
                  "result": "minecraft:diamond",
                  "resultAmount": 3
                }
              ]
            }
            """);

        List<MerchantRecipe> recipes = service.recipes("profile");

        assertEquals(1, recipes.size());
        MerchantRecipe recipe = recipes.getFirst();
        assertEquals(Material.DIAMOND, recipe.getResult().getType());
        assertEquals(3, recipe.getResult().getAmount());
        assertEquals(8, recipe.getMaxUses());
        assertEquals(2, recipe.getIngredients().size());
        assertEquals(Material.EMERALD, recipe.getIngredients().getFirst().getType());
        assertEquals(2, recipe.getIngredients().getFirst().getAmount());
        assertEquals(Material.BOOK, recipe.getIngredients().get(1).getType());
    }

    @Test
    void disabledProfilesReturnNoRecipes() {
        TestVillageProfileService service = new TestVillageProfileService("""
            {
              "enabled": false,
              "offers": [
                { "cost": "minecraft:emerald", "result": "minecraft:diamond" }
              ]
            }
            """);

        assertTrue(service.recipes("profile").isEmpty());
    }

    @Test
    void invalidOfferItemsAreSkipped() {
        TestVillageProfileService service = new TestVillageProfileService("""
            {
              "enabled": true,
              "offers": [
                { "cost": "minecraft:emerald", "result": "minecraft:not_real" }
              ]
            }
            """);

        assertTrue(service.recipes("profile").isEmpty());
    }

    private static class TestVillageProfileService extends VillageProfileService {
        private final JsonObject profile;

        TestVillageProfileService(String json) {
            super(null, null);
            this.profile = JsonParser.parseString(json).getAsJsonObject();
        }

        @Override
        public JsonObject get(String id) {
            return profile;
        }

    }
}
