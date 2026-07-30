package restudio.resync.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSchemaNormalizerTest {
    @Test
    void testerBackupShapelessRecipeDropsStaleShapedStone() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/regressions/tester-resync-backup/recipe-mixed-schema.json")) {
            JsonObject recipe = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();

            assertTrue(RecipeSchemaNormalizer.normalize(recipe));

            assertFalse(recipe.has("shape"));
            assertFalse(recipe.has("keys"));
            assertEquals(2, recipe.getAsJsonArray("ingredients").size());
            assertEquals("spyglass", recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("material").getAsString());
            assertEquals("diamond_helmet", recipe.getAsJsonArray("ingredients").get(1).getAsJsonObject().get("material").getAsString());
            assertFalse(RecipeSchemaNormalizer.normalize(recipe));
        }
    }

    @Test
    void shapedRecipeConvertsEffectiveIngredientsAndBecomesIdempotent() {
        JsonObject recipe = JsonParser.parseString("""
            {
              "type": "shaped",
              "ingredients": [
                {"material": "oak_planks"},
                {"material": "stick"}
              ]
            }
            """).getAsJsonObject();

        assertTrue(RecipeSchemaNormalizer.normalize(recipe));

        assertFalse(recipe.has("ingredients"));
        assertEquals("AB ", recipe.getAsJsonArray("shape").get(0).getAsString());
        assertEquals("oak_planks", recipe.getAsJsonObject("keys").getAsJsonObject("A").get("material").getAsString());
        assertEquals("stick", recipe.getAsJsonObject("keys").getAsJsonObject("B").get("material").getAsString());
        assertFalse(RecipeSchemaNormalizer.normalize(recipe));
    }
}
