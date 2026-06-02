package restudio.resync.advancement;

import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class AdvancementIconJson {
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
                return serialized;
            } catch (RuntimeException ignored) {
                // Fall through to Bukkit-native icon JSON.
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
        JsonObject components = componentObject(item);
        if (components != null && !components.isEmpty()) {
            icon.add("components", components);
        }
        return icon;
    }

    private static JsonObject componentObject(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) {
            return null;
        }
        JsonObject components = new JsonObject();
        JsonObject customModelData = new JsonObject();
        customModelData.addProperty("value", meta.getCustomModelData());
        components.add("minecraft:custom_model_data", customModelData);
        return components;
    }
}
