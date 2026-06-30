package restudio.resync.customcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.advancement.PaperUnsafe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ItemStackPreviewMetadata {
    private static final String CUSTOM_MODEL_DATA = "minecraft:custom_model_data";

    private ItemStackPreviewMetadata() {
    }

    public static Map<String, Object> fromDefinition(CustomContentService service, CustomContentDefinition definition) {
        if (service == null || definition == null || definition.getId() == null || definition.getId().isBlank()) {
            return Map.of();
        }
        ItemStack stack = service.createItem(definition.getId(), 1);
        Map<String, Object> metadata = fromStack(stack);
        if (!metadata.containsKey("material") && definition.getMaterial() != null && !definition.getMaterial().isBlank()) {
            metadata.put("material", definition.getMaterial());
        }
        if (!metadata.containsKey("customModelData") && definition.getCustomModelData() != null) {
            metadata.put("customModelData", definition.getCustomModelData());
        }
        return metadata;
    }

    public static Map<String, Object> fromStack(ItemStack stack) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (stack == null || stack.getType().isAir()) {
            return metadata;
        }
        metadata.put("material", stack.getType().name());
        Integer customModelData = extractCustomModelData(stack);
        if (customModelData != null) {
            metadata.put("customModelData", customModelData);
        }
        Map<String, Object> components = extractComponents(stack);
        if (!components.isEmpty()) {
            metadata.put("components", components);
        }
        return metadata;
    }

    private static Integer extractCustomModelData(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            return meta.getCustomModelData();
        }
        if (!PaperUnsafe.serializeItemAsJsonSupported()) {
            return null;
        }
        try {
            JsonObject serialized = PaperUnsafe.serializeItemAsJson(stack);
            if (serialized == null || !serialized.has("components") || !serialized.get("components").isJsonObject()) {
                return null;
            }
            return readCustomModelData(serialized.getAsJsonObject("components"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> extractComponents(ItemStack stack) {
        if (!PaperUnsafe.serializeItemAsJsonSupported()) {
            return Map.of();
        }
        try {
            JsonObject serialized = PaperUnsafe.serializeItemAsJson(stack);
            if (serialized == null || !serialized.has("components") || !serialized.get("components").isJsonObject()) {
                return Map.of();
            }
            JsonObject components = serialized.getAsJsonObject("components");
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : components.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && !entry.getValue().isJsonNull()) {
                    result.put(entry.getKey(), jsonToMap(entry.getValue()));
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Integer readCustomModelData(JsonObject components) {
        if (components == null || !components.has(CUSTOM_MODEL_DATA)) {
            return null;
        }
        JsonElement element = components.get(CUSTOM_MODEL_DATA);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("value") && object.get("value").isJsonPrimitive() && object.get("value").getAsJsonPrimitive().isNumber()) {
            return object.get("value").getAsInt();
        }
        if (object.has("floats") && object.get("floats").isJsonArray() && !object.getAsJsonArray("floats").isEmpty()) {
            return object.getAsJsonArray("floats").get(0).getAsInt();
        }
        return null;
    }

    private static Object jsonToMap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsNumber();
            }
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            Object[] values = new Object[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = jsonToMap(array.get(index));
            }
            return values;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), jsonToMap(entry.getValue()));
            }
            return map;
        }
        return null;
    }

    public static String providerReference(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return "";
        }
        return "provider:" + provider.toLowerCase(Locale.ROOT) + ":" + externalId;
    }
}
