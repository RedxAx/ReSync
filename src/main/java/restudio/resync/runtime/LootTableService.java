package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class LootTableService {
    private final ReSyncJsonResourceStorage storage;
    private final CustomContentService customContentService;
    private final RuntimeFlowDispatcher dispatcher;
    private final Random random = new Random();

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService) {
        this(storage, customContentService, null);
    }

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher) {
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
    }

    public JsonObject get(String id) {
        return storage != null ? storage.get(ReSyncResourceCatalog.LOOT_TABLE, id) : null;
    }

    public List<ItemStack> generate(String id) {
        return generate(id, Map.of());
    }

    public List<ItemStack> generate(String id, Map<String, Object> context) {
        JsonObject table = get(id);
        if (table == null || !bool(table, "enabled", true)) {
            dispatch(table, "deniedRollFlow", context, List.of(), null);
            return List.of();
        }
        dispatch(table, "beforeRollFlow", context, List.of(), null);
        List<ItemStack> result = new ArrayList<>();
        JsonArray pools = array(table, "pools");
        for (JsonElement poolElement : pools) {
            if (poolElement == null || !poolElement.isJsonObject()) {
                continue;
            }
            JsonObject pool = poolElement.getAsJsonObject();
            int rolls = Math.max(0, integer(pool, "rolls", 1));
            JsonArray entries = array(pool, "entries");
            for (int i = 0; i < rolls; i++) {
                ItemStack item = roll(entries, context);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        List<ItemStack> items = List.copyOf(result);
        dispatch(table, items.isEmpty() ? "deniedRollFlow" : "afterRollFlow", context, items, null);
        return items;
    }

    public List<ItemStack> give(Player player, String id) {
        Map<String, Object> context = new HashMap<>();
        if (player != null) {
            context.put("player", player);
            context.put("location", player.getLocation());
        }
        List<ItemStack> items = generate(id, context);
        if (player != null) {
            for (ItemStack item : items) {
                player.getInventory().addItem(item);
            }
        }
        return items;
    }

    public List<ItemStack> fillContainer(Inventory inventory, String id) {
        return fillContainer(inventory, id, Map.of());
    }

    public List<ItemStack> fillContainer(Inventory inventory, String id, Map<String, Object> context) {
        List<ItemStack> items = generate(id, context);
        if (inventory != null) {
            for (ItemStack item : items) {
                inventory.addItem(item);
            }
        }
        return items;
    }

    public Map<String, Object> context(Player player, Entity entity, Location location) {
        Map<String, Object> context = new HashMap<>();
        if (player != null) {
            context.put("player", player);
        }
        if (entity != null) {
            context.put("entity", entity);
        }
        if (location != null) {
            context.put("location", location);
        }
        return context;
    }

    private void dispatch(JsonObject table, String hook, Map<String, Object> context, List<ItemStack> items, Event event) {
        String flowId = table != null && table.has("hooks") && table.get("hooks").isJsonObject()
            ? text(table.getAsJsonObject("hooks"), hook)
            : "";
        if (flowId.isBlank() || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        if (context != null) {
            variables.putAll(context);
        }
        variables.put("lootTable", text(table, "id"));
        variables.put("items", items);
        Player player = variables.get("player") instanceof Player value ? value : null;
        dispatcher.dispatch(flowId, player, event, variables);
    }

    private ItemStack roll(JsonArray entries, Map<String, Object> context) {
        List<JsonObject> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (JsonElement element : entries) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!conditionsPass(entry, context)) {
                continue;
            }
            double chance = decimal(entry, "chance", 100.0);
            if (chance < 100.0 && random.nextDouble() * 100.0 > Math.max(0.0, chance)) {
                continue;
            }
            int weight = Math.max(0, integer(entry, "weight", 1));
            if (weight <= 0) {
                continue;
            }
            totalWeight += weight;
            candidates.add(entry);
        }
        if (candidates.isEmpty() || totalWeight <= 0) {
            return null;
        }
        int cursor = random.nextInt(totalWeight);
        for (JsonObject entry : candidates) {
            cursor -= Math.max(0, integer(entry, "weight", 1));
            if (cursor < 0) {
                return item(entry);
            }
        }
        return null;
    }

    private ItemStack item(JsonObject entry) {
        String item = text(entry, "item");
        if (item.isBlank()) {
            return null;
        }
        int min = Math.max(1, integer(entry, "minAmount", integer(entry, "amount", 1)));
        int max = Math.max(min, integer(entry, "maxAmount", min));
        int amount = min + random.nextInt(max - min + 1);
        ItemStack stack = createReferencedItem(item, amount);
        if (stack != null) {
            stack.setAmount(amount);
            applyComponents(stack, entry);
        }
        return stack;
    }

    protected ItemStack createReferencedItem(String reference, int amount) {
        ItemStack stack = customContentService != null ? customContentService.createReferencedItem(reference, amount) : null;
        if (stack != null) {
            return stack;
        }
        Material material = RuntimeMaterialResolver.itemMaterial(reference);
        return material != null ? new ItemStack(material, Math.max(1, amount)) : null;
    }

    private boolean conditionsPass(JsonObject entry, Map<String, Object> context) {
        JsonElement conditions = entry != null ? entry.get("conditions") : null;
        if (conditions == null || conditions.isJsonNull()) {
            return true;
        }
        if (conditions.isJsonArray()) {
            for (JsonElement element : conditions.getAsJsonArray()) {
                if (!element.isJsonObject() || !conditionObjectPasses(element.getAsJsonObject(), context)) {
                    return false;
                }
            }
            return true;
        }
        return conditions.isJsonObject() && conditionObjectPasses(conditions.getAsJsonObject(), context);
    }

    private boolean conditionObjectPasses(JsonObject conditions, Map<String, Object> context) {
        if (!bool(conditions, "enabled", true)) {
            return false;
        }
        if (conditions.has("allOf") && !conditionGroupPasses(conditions.get("allOf"), context, true)) {
            return false;
        }
        if (conditions.has("anyOf") && !conditionGroupPasses(conditions.get("anyOf"), context, false)) {
            return false;
        }
        if (conditions.has("not") && conditions.get("not").isJsonObject() && conditionObjectPasses(conditions.getAsJsonObject("not"), context)) {
            return false;
        }
        double chance = decimal(conditions, "chance", 100.0);
        if (chance < 100.0 && random.nextDouble() * 100.0 > Math.max(0.0, chance)) {
            return false;
        }
        Player player = context != null && context.get("player") instanceof Player value ? value : null;
        Entity entity = context != null && context.get("entity") instanceof Entity value ? value : null;
        Location location = context != null && context.get("location") instanceof Location value ? value : null;
        List<String> permissions = stringList(conditions.get("permission"));
        if (!permissions.isEmpty() && (player == null || permissions.stream().anyMatch(permission -> !player.hasPermission(permission)))) {
            return false;
        }
        List<String> worlds = stringList(conditions.get("world"));
        if (!worlds.isEmpty() && !containsIgnoreCase(worlds, worldName(player, entity, location))) {
            return false;
        }
        String entityType = text(conditions, "entityType");
        if (!entityType.isBlank() && (entity == null || !entity.getType().name().equalsIgnoreCase(stripNamespace(entityType)))) {
            return false;
        }
        String playerName = text(conditions, "player");
        return playerName.isBlank() || player != null
            && (player.getName().equalsIgnoreCase(playerName) || player.getUniqueId().toString().equalsIgnoreCase(playerName));
    }

    private boolean conditionGroupPasses(JsonElement group, Map<String, Object> context, boolean all) {
        if (group == null || !group.isJsonArray()) {
            return false;
        }
        boolean matched = false;
        for (JsonElement element : group.getAsJsonArray()) {
            boolean passes = element.isJsonObject() && conditionObjectPasses(element.getAsJsonObject(), context);
            matched |= passes;
            if (all && !passes) {
                return false;
            }
            if (!all && passes) {
                return true;
            }
        }
        return all || matched;
    }

    private String worldName(Player player, Entity entity, Location location) {
        if (location != null && location.getWorld() != null) {
            return location.getWorld().getName();
        }
        if (entity != null && entity.getWorld() != null) {
            return entity.getWorld().getName();
        }
        return player != null && player.getWorld() != null ? player.getWorld().getName() : "";
    }

    private boolean containsIgnoreCase(List<String> values, String actual) {
        return actual != null && values.stream().anyMatch(value -> value.equalsIgnoreCase(actual));
    }

    private String stripNamespace(String value) {
        return value != null && value.contains(":") ? value.substring(value.indexOf(':') + 1) : value;
    }

    private void applyComponents(ItemStack stack, JsonObject entry) {
        JsonObject components = object(entry, "components");
        if (components == null) {
            components = new JsonObject();
        }
        if (entry.has("enchantments") && !components.has("enchantments")) {
            components.add("enchantments", entry.get("enchantments"));
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String displayName = firstText(components, "displayName", "name", "minecraft:custom_name");
            if (!displayName.isBlank()) {
                meta.setDisplayName(displayName);
            }
            JsonElement loreElement = components.has("minecraft:lore") ? components.get("minecraft:lore") : components.get("lore");
            List<String> lore = stringList(loreElement);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            Integer customModelData = optionalInteger(components, "customModelData");
            if (customModelData == null) {
                customModelData = optionalInteger(components, "custom_model_data");
            }
            if (customModelData == null) {
                customModelData = customModelData(components.get("minecraft:custom_model_data"));
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            stack.setItemMeta(meta);
        }
        JsonElement enchantments = components.has("minecraft:enchantments") ? components.get("minecraft:enchantments") : components.get("enchantments");
        applyEnchantments(stack, enchantments);
    }

    private Integer customModelData(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return integer(element, 0);
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("value")) {
            return integer(object.get("value"), 0);
        }
        JsonArray floats = array(object, "floats");
        return floats.size() > 0 ? (int) floats.get(0).getAsDouble() : null;
    }

    private void applyEnchantments(ItemStack stack, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                addEnchantment(stack, entry.getKey(), integer(entry.getValue(), 1));
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value != null && value.isJsonObject()) {
                    JsonObject object = value.getAsJsonObject();
                    addEnchantment(stack, firstText(object, "key", "id", "enchantment"), integer(object, "level", 1));
                }
            }
        }
    }

    private void addEnchantment(ItemStack stack, String key, int level) {
        Enchantment enchantment = enchantment(key);
        if (enchantment != null && level > 0) {
            stack.addUnsafeEnchantment(enchantment, level);
        }
    }

    private Enchantment enchantment(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        NamespacedKey namespacedKey = normalized.contains(":") ? NamespacedKey.fromString(normalized) : NamespacedKey.minecraft(normalized);
        return namespacedKey != null ? Enchantment.getByKey(namespacedKey) : null;
    }

    private JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return componentText(object.get(key));
    }

    private String firstText(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = text(object, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int integer(JsonElement element, int fallback) {
        try {
            return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Integer optionalInteger(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private double decimal(JsonObject object, String key, double fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> stringList(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value != null && !value.isJsonNull()) {
                    String text = componentText(value);
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                }
            }
        } else {
            String text = componentText(element);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private String componentText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) {
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        String text = text(object, "text");
        if (!text.isBlank()) {
            return text;
        }
        return text(object, "translate");
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
