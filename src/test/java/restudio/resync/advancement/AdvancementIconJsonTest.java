package restudio.resync.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdvancementIconJsonTest {
    @Test
    void fromReferenceAddsMinecraftNamespace() {
        JsonObject icon = AdvancementIconJson.fromReference("stone");
        assertEquals("minecraft:stone", icon.get("id").getAsString());
        assertFalse(icon.has("components"));
    }

    @Test
    void fromReferencePreservesNamespacedId() {
        JsonObject icon = AdvancementIconJson.fromReference("minecraft:nether_star");
        assertEquals("minecraft:nether_star", icon.get("id").getAsString());
    }

    @Test
    void normalizeComponentsConvertsValueWrapperToFloats() {
        JsonObject components = new JsonObject();
        JsonObject legacy = new JsonObject();
        legacy.addProperty("value", 4321);
        components.add("minecraft:custom_model_data", legacy);
        JsonObject normalized = AdvancementIconJson.normalizeComponents(components);
        JsonObject customModelData = normalized.getAsJsonObject("minecraft:custom_model_data");
        JsonArray floats = customModelData.getAsJsonArray("floats");
        assertEquals(1, floats.size());
        assertEquals(4321, floats.get(0).getAsInt());
        assertFalse(customModelData.has("value"));
    }

    @Test
    void normalizeComponentsConvertsPrimitiveToFloats() {
        JsonObject components = new JsonObject();
        components.addProperty("minecraft:custom_model_data", 99);
        JsonObject normalized = AdvancementIconJson.normalizeComponents(components);
        JsonObject customModelData = normalized.getAsJsonObject("minecraft:custom_model_data");
        JsonArray floats = customModelData.getAsJsonArray("floats");
        assertEquals(1, floats.size());
        assertEquals(99, floats.get(0).getAsInt());
    }

    @Test
    void normalizeComponentsPreservesExistingFloats() {
        JsonObject components = new JsonObject();
        JsonObject customModelData = new JsonObject();
        JsonArray floats = new JsonArray();
        floats.add(12.5);
        customModelData.add("floats", floats);
        components.add("minecraft:custom_model_data", customModelData);
        JsonObject normalized = AdvancementIconJson.normalizeComponents(components);
        assertEquals(12.5, normalized.getAsJsonObject("minecraft:custom_model_data").getAsJsonArray("floats").get(0).getAsDouble());
    }
}
