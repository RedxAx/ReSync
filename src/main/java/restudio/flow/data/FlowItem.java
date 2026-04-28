package restudio.flow.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlowItem implements FlowDataObject {
    private String material;
    private int amount;
    private String displayName;
    private List<String> lore;
    private Integer customModelData;
    private Map<String, Object> nbt;
    private List<FlowEnchantment> enchantments;
    private int durability;

    public FlowItem() {
    }

    public FlowItem(String material, int amount) {
        this.material = material;
        this.amount = amount;
    }

    public static FlowItem fromItemStack(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        FlowItem item = new FlowItem(itemStack.getType().name(), itemStack.getAmount());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                item.displayName = meta.getDisplayName();
            }
            if (meta.hasLore()) {
                item.lore = new ArrayList<>(meta.getLore());
            }
            if (meta.hasCustomModelData()) {
                item.customModelData = meta.getCustomModelData();
            }
        }
        item.durability = itemStack.getDurability();
        Map<Enchantment, Integer> enchants = itemStack.getEnchantments();
        if (!enchants.isEmpty()) {
            item.enchantments = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                item.enchantments.add(FlowEnchantment.fromEnchantment(entry.getKey(), entry.getValue()));
            }
        }
        return item;
    }

    public ItemStack toItemStack() {
        Material resolved = Material.matchMaterial(material == null ? "" : material);
        Material type = resolved != null ? resolved : Material.AIR;
        int safeAmount = amount <= 0 ? 1 : amount;
        ItemStack stack = new ItemStack(type, safeAmount);
        if (durability > 0) {
            stack.setDurability((short) durability);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                meta.setDisplayName(displayName);
            }
            if (lore != null) {
                meta.setLore(lore);
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            stack.setItemMeta(meta);
        }
        if (enchantments != null) {
            for (FlowEnchantment fe : enchantments) {
                Enchantment ench = fe.resolveEnchantment();
                if (ench != null) {
                    stack.addUnsafeEnchantment(ench, fe.getLevel());
                }
            }
        }
        return stack;
    }

    @Override
    public String getTypeId() {
        return "item";
    }

    @Override
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("material", material);
        obj.addProperty("amount", amount);
        if (displayName != null) obj.addProperty("displayName", displayName);
        if (lore != null) {
            JsonArray arr = new JsonArray();
            for (String line : lore) arr.add(line);
            obj.add("lore", arr);
        }
        if (customModelData != null) obj.addProperty("customModelData", customModelData);
        if (nbt != null) obj.add("nbt", new com.google.gson.Gson().toJsonTree(nbt));
        obj.addProperty("durability", durability);
        if (enchantments != null) {
            JsonArray arr = new JsonArray();
            for (FlowEnchantment fe : enchantments) arr.add(fe.toJson());
            obj.add("enchantments", arr);
        }
        return obj;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore;
    }

    public Integer getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(Integer customModelData) {
        this.customModelData = customModelData;
    }

    public Map<String, Object> getNbt() {
        return nbt;
    }

    public void setNbt(Map<String, Object> nbt) {
        this.nbt = nbt;
    }

    public List<FlowEnchantment> getEnchantments() {
        return enchantments;
    }

    public void setEnchantments(List<FlowEnchantment> enchantments) {
        this.enchantments = enchantments;
    }

    public int getDurability() {
        return durability;
    }

    public void setDurability(int durability) {
        this.durability = durability;
    }
}
