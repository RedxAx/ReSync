package restudio.resync.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class AdvancementIconJson {
    private static final String CUSTOM_MODEL_DATA = "minecraft:custom_model_data";

    private AdvancementIconJson() {
    }

    static JsonObject fromResolved(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("Advancement icon item is empty");
        }
        if (PaperUnsafe.serializeItemAsJsonSupported()) {
            try {
                JsonObject serialized = PaperUnsafe.serializeItemAsJson(item).deepCopy();
                serialized.remove("DataVersion");
                serialized.remove("count");
                return normalizeAdvancementIcon(serialized, item);
            } catch (RuntimeException ignored) {
            }
        }
        return fromItemStack(item);
    }

    static JsonObject fromReference(String reference) {
        JsonObject icon = new JsonObject();
        icon.addProperty("id", reference.startsWith("minecraft:") ? reference : "minecraft:" + reference);
        return icon;
    }

    static JsonObject fromItemStack(ItemStack item) {
        JsonObject icon = new JsonObject();
        icon.addProperty("id", item.getType().getKey().toString());
        JsonObject components = visualComponents(null, item);
        if (components != null && !components.isEmpty()) {
            icon.add("components", components);
        }
        return icon;
    }

    private static JsonObject normalizeAdvancementIcon(JsonObject serialized, ItemStack item) {
        JsonObject icon = new JsonObject();
        if (serialized.has("id")) {
            icon.add("id", serialized.get("id"));
        } else {
            icon.addProperty("id", item.getType().getKey().toString());
        }
        JsonObject serializedComponents = serialized.has("components") && serialized.get("components").isJsonObject()
            ? serialized.getAsJsonObject("components").deepCopy()
            : null;
        JsonObject components = visualComponents(serializedComponents, item);
        if (components != null && !components.isEmpty()) {
            icon.add("components", components);
        }
        return icon;
    }

    private static JsonObject visualComponents(JsonObject components, ItemStack item) {
        JsonObject result = components != null ? components : new JsonObject();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData() && !result.has(CUSTOM_MODEL_DATA)) {
            writeCustomModelData(result, meta.getCustomModelData());
        }
        if (result.has(CUSTOM_MODEL_DATA)) {
            normalizeCustomModelData(result);
        }
        if (result.isEmpty()) {
            return null;
        }
        return result;
    }

    private static void normalizeCustomModelData(JsonObject components) {
        if (!components.has(CUSTOM_MODEL_DATA)) {
            return;
        }
        JsonElement element = components.get(CUSTOM_MODEL_DATA);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            writeCustomModelData(components, element.getAsInt());
            return;
        }
        if (!element.isJsonObject()) {
            components.remove(CUSTOM_MODEL_DATA);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("floats") && object.getAsJsonArray("floats").size() > 0) {
            return;
        }
        if (object.has("value") && object.get("value").isJsonPrimitive() && object.get("value").getAsJsonPrimitive().isNumber()) {
            writeCustomModelData(components, object.get("value").getAsInt());
            return;
        }
        components.remove(CUSTOM_MODEL_DATA);
    }

    private static void writeCustomModelData(JsonObject components, int value) {
        JsonObject customModelData = new JsonObject();
        JsonArray floats = new JsonArray();
        floats.add(value);
        customModelData.add("floats", floats);
        components.add(CUSTOM_MODEL_DATA, customModelData);
    }

    static JsonObject normalizeComponents(JsonObject components) {
        JsonObject copy = components != null ? components.deepCopy() : new JsonObject();
        normalizeCustomModelData(copy);
        return copy;
    }
}
