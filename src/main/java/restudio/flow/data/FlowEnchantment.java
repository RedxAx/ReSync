package restudio.flow.data;

import com.google.gson.JsonObject;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

public class FlowEnchantment implements FlowDataObject {
    private String key;
    private int level;

    public FlowEnchantment() {
    }

    public FlowEnchantment(String key, int level) {
        this.key = key;
        this.level = level;
    }

    public static FlowEnchantment fromEnchantment(Enchantment enchantment, int level) {
        if (enchantment == null || enchantment.getKey() == null) {
            return null;
        }
        return new FlowEnchantment(enchantment.getKey().toString(), level);
    }

    public Enchantment resolveEnchantment() {
        if (key == null || key.isBlank()) {
            return null;
        }
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) {
            return null;
        }
        return Enchantment.getByKey(namespacedKey);
    }

    @Override
    public String getTypeId() {
        return "enchantment";
    }

    @Override
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", key);
        obj.addProperty("level", level);
        return obj;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}
