package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class LootTableService implements Listener {
    private static final GsonComponentSerializer COMPONENT_SERIALIZER = GsonComponentSerializer.gson();
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
        return generate(id, context, null);
    }

    public List<ItemStack> generate(String id, Map<String, Object> context, Event event) {
        JsonObject table = get(id);
        Map<String, Object> rollContext = new HashMap<>();
        if (context != null) {
            rollContext.putAll(context);
        }
        if (id != null && !id.isBlank()) {
            rollContext.putIfAbsent("lootTable", id);
        }
        if (table == null || !bool(table, "enabled", true)) {
            dispatch(table, "deniedRollFlow", rollContext, List.of(), event);
            return List.of();
        }
        dispatch(table, "beforeRollFlow", rollContext, List.of(), event);
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
                ItemStack item = roll(entries, rollContext);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        List<ItemStack> items = List.copyOf(result);
        dispatch(table, items.isEmpty() ? "deniedRollFlow" : "afterRollFlow", rollContext, items, event);
        return items;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        Map<String, Object> context = context(event.getPlayer(), null, location);
        context.put("event.type", "block_break");
        context.put("event.block", event.getBlock());
        context.put("event.item", event.getPlayer().getInventory().getItemInMainHand());
        TriggeredLootResult result = triggeredLoot("block_break", context, event, event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), null, location, null);
        if (result.overrideDrops()) {
            event.setDropItems(false);
        }
        dropNaturally(location, result.items());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location location = event.getBlockPlaced().getLocation();
        ItemStack item = event.getItemInHand();
        Map<String, Object> context = context(event.getPlayer(), null, location);
        context.put("event.type", "block_place");
        context.put("event.block", event.getBlockPlaced());
        context.put("event.item", item);
        TriggeredLootResult result = triggeredLoot("block_place", context, event, event.getPlayer(), item, null, location, null);
        dropNaturally(location, result.items());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        ItemStack tool = killer != null ? killer.getInventory().getItemInMainHand() : null;
        EntityDamageEvent damageEvent = event.getEntity().getLastDamageCause();
        Map<String, Object> context = context(killer, event.getEntity(), event.getEntity().getLocation());
        context.put("event.type", "entity_death");
        context.put("event.entity", event.getEntity());
        context.put("event.killer", killer);
        context.put("event.item", tool);
        putDamageContext(context, damageEvent);
        TriggeredLootResult result = triggeredLoot("entity_death", context, event, killer, tool, event.getEntity(), event.getEntity().getLocation(), damageEvent);
        if (result.overrideDrops()) {
            event.getDrops().clear();
        }
        event.getDrops().addAll(result.items());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || event.getAction() == Action.PHYSICAL) {
            return;
        }
        Location location = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : event.getPlayer().getLocation();
        Map<String, Object> context = context(event.getPlayer(), null, location);
        context.put("event.type", "item_use");
        context.put("event.block", event.getClickedBlock());
        context.put("event.item", item);
        TriggeredLootResult result = triggeredLoot("item_use", context, event, event.getPlayer(), item, null, location, null);
        dropNaturally(location, result.items());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        Map<String, Object> context = context(player, event.getEntity(), event.getEntity().getLocation());
        context.put("event.type", "item_hit_entity");
        context.put("event.entity", event.getEntity());
        context.put("event.item", item);
        putDamageContext(context, event);
        TriggeredLootResult result = triggeredLoot("item_hit_entity", context, event, player, item, event.getEntity(), event.getEntity().getLocation(), event);
        dropNaturally(event.getEntity().getLocation(), result.items());
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

    private TriggeredLootResult triggeredLoot(String eventType, Map<String, Object> context, Event event, Player player, ItemStack item, Entity entity, Location location, EntityDamageEvent damageEvent) {
        if (storage == null || eventType == null) {
            return new TriggeredLootResult(false, List.of());
        }
        List<ItemStack> items = new ArrayList<>();
        boolean overrideDrops = false;
        for (String id : storage.listIds(ReSyncResourceCatalog.LOOT_TABLE)) {
            JsonObject table = get(id);
            if (table == null || !bool(table, "enabled", true)) {
                continue;
            }
            for (JsonObject trigger : triggers(table)) {
                if (!triggerMatches(trigger, eventType, player, item, entity, location, damageEvent)) {
                    continue;
                }
                overrideDrops |= bool(trigger, "overrideDrops", false);
                Map<String, Object> variables = new HashMap<>(context != null ? context : Map.of());
                variables.put("lootTable", id);
                variables.put("lootEvent", eventType);
                items.addAll(generate(id, variables, event));
            }
        }
        return new TriggeredLootResult(overrideDrops, List.copyOf(items));
    }

    private List<JsonObject> triggers(JsonObject table) {
        List<JsonObject> result = new ArrayList<>();
        JsonObject trigger = object(table, "trigger");
        if (trigger != null) {
            result.add(trigger);
        }
        JsonArray links = array(table, "links");
        for (JsonElement element : links) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    private boolean triggerMatches(JsonObject trigger, String eventType, Player player, ItemStack item, Entity entity, Location location, EntityDamageEvent damageEvent) {
        String configuredEvent = text(trigger, "event");
        if (configuredEvent.isBlank() || "none".equalsIgnoreCase(configuredEvent) || !configuredEvent.equalsIgnoreCase(eventType)) {
            return false;
        }
        String target = text(trigger, "target");
        String tool = text(trigger, "tool");
        return switch (eventType) {
            case "block_break" -> matchesBlockTarget(location, target) && matchesTool(player, item, tool, damageEvent);
            case "block_place" -> (matchesBlockTarget(location, target) || matchesItemTarget(item, target)) && matchesTool(player, item, tool, damageEvent);
            case "entity_death" -> matchesEntityTarget(entity, target) && matchesTool(player, item, tool, damageEvent);
            case "item_use" -> matchesItemTarget(item, firstFilled(target, tool));
            case "item_hit_entity" -> matchesItemTarget(item, target) && matchesTool(player, item, tool, damageEvent) && matchesEntityTarget(entity, text(trigger, "entity"));
            default -> false;
        };
    }

    private boolean matchesTool(Player player, ItemStack item, String tool, EntityDamageEvent damageEvent) {
        if (tool == null || tool.isBlank() || "none".equalsIgnoreCase(tool)) {
            return true;
        }
        if (isDamageTypeReference(tool)) {
            return matchesDamageType(damageEvent, tool);
        }
        ItemStack held = item != null ? item : player != null ? player.getInventory().getItemInMainHand() : null;
        return matchesItemTarget(held, tool);
    }

    private void putDamageContext(Map<String, Object> context, EntityDamageEvent damageEvent) {
        if (context == null || damageEvent == null) {
            return;
        }
        context.put("event.damageCause", damageEvent.getCause().name().toLowerCase(Locale.ROOT));
        String damageType = damageTypeKey(damageEvent);
        if (!damageType.isBlank()) {
            context.put("event.damageType", damageType);
        }
    }

    private boolean isDamageTypeReference(String value) {
        return value != null && (value.startsWith("damage_type:") || value.startsWith("damage:"));
    }

    private boolean matchesDamageType(EntityDamageEvent damageEvent, String reference) {
        if (damageEvent == null) {
            return false;
        }
        String expected = normalizeDamageType(damageTypeReference(reference));
        if (expected.isBlank()) {
            return true;
        }
        String cause = normalizeDamageType(damageEvent.getCause().name());
        if (expected.equals(cause) || damageTypeAliases(expected).contains(cause) || damageTypeAliases(cause).contains(expected)) {
            return true;
        }
        String key = normalizeDamageType(damageTypeKey(damageEvent));
        return !key.isBlank() && (expected.equals(key) || damageTypeAliases(expected).contains(key) || damageTypeAliases(key).contains(expected));
    }

    private String damageTypeReference(String reference) {
        if (reference == null) {
            return "";
        }
        if (reference.startsWith("damage_type:")) {
            return reference.substring("damage_type:".length());
        }
        if (reference.startsWith("damage:")) {
            return reference.substring("damage:".length());
        }
        return reference;
    }

    private String damageTypeKey(EntityDamageEvent damageEvent) {
        if (damageEvent == null) {
            return "";
        }
        try {
            Object source = damageEvent.getClass().getMethod("getDamageSource").invoke(damageEvent);
            Object type = source != null ? source.getClass().getMethod("getDamageType").invoke(source) : null;
            Object key = type != null ? type.getClass().getMethod("getKey").invoke(type) : null;
            return key != null ? key.toString() : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private String normalizeDamageType(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.replace('-', '_');
    }

    private List<String> damageTypeAliases(String value) {
        return switch (value) {
            case "fire", "in_fire", "on_fire", "fire_tick" -> List.of("fire", "in_fire", "on_fire", "fire_tick");
            case "fall", "fall_damage" -> List.of("fall", "fall_damage");
            case "drown", "drowning" -> List.of("drown", "drowning");
            case "explosion", "block_explosion", "entity_explosion" -> List.of("explosion", "block_explosion", "entity_explosion");
            case "mob_attack", "entity_attack", "player_attack" -> List.of("mob_attack", "entity_attack", "player_attack");
            case "arrow", "projectile", "trident" -> List.of("arrow", "projectile", "trident");
            case "magic", "indirect_magic" -> List.of("magic", "indirect_magic");
            default -> List.of(value);
        };
    }

    private boolean matchesItemTarget(ItemStack item, String target) {
        if (target == null || target.isBlank() || "none".equalsIgnoreCase(target)) {
            return true;
        }
        if (customContentService != null && customContentService.matchesItemReference(item, target)) {
            return true;
        }
        Material material = RuntimeMaterialResolver.itemMaterial(target);
        return material != null && item != null && item.getType() == material;
    }

    private boolean matchesBlockTarget(Location location, String target) {
        if (target == null || target.isBlank() || "none".equalsIgnoreCase(target)) {
            return true;
        }
        if (customContentService != null && customContentService.matchesBlockReference(location, target)) {
            return true;
        }
        Material material = material(target);
        return material != null && location != null && location.getBlock().getType() == material;
    }

    private boolean matchesEntityTarget(Entity entity, String target) {
        if (target == null || target.isBlank() || "none".equalsIgnoreCase(target)) {
            return true;
        }
        return entity != null && entity.getType().name().equalsIgnoreCase(stripNamespace(target));
    }

    private void dropNaturally(Location location, List<ItemStack> items) {
        if (location == null || items == null || items.isEmpty()) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(location, item);
            }
        }
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
        variables.put("lootTable", firstFilled(text(table, "id"), String.valueOf(variables.getOrDefault("lootTable", ""))));
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

    private Material material(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String value = stripNamespace(reference).replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Material.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
            JsonElement displayName = firstComponent(components, "displayName", "name", "minecraft:custom_name", "minecraft:item_name");
            if (displayName != null) {
                meta.displayName(itemTextComponent(displayName, false));
            }
            JsonElement loreElement = components.has("minecraft:lore") ? components.get("minecraft:lore") : components.get("lore");
            List<Component> lore = itemLoreComponents(loreElement);
            if (!lore.isEmpty()) {
                meta.lore(lore);
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

    private JsonElement firstComponent(JsonObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                JsonElement element = object.get(key);
                if (!element.isJsonPrimitive() || !element.getAsString().isBlank()) {
                    return element;
                }
            }
        }
        return null;
    }

    private String firstFilled(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
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

    private Component itemTextComponent(JsonElement element, boolean lore) {
        if (element == null || element.isJsonNull()) {
            return Component.empty();
        }
        if (element.isJsonPrimitive()) {
            return lore ? TextFormatter.parseItemLore(element.getAsString()) : TextFormatter.parseItemName(element.getAsString());
        }
        try {
            return TextFormatter.applyItemTextDefaults(COMPONENT_SERIALIZER.deserialize(element.toString()));
        } catch (RuntimeException ignored) {
            String text = componentText(element);
            return lore ? TextFormatter.parseItemLore(text) : TextFormatter.parseItemName(text);
        }
    }

    private List<Component> itemLoreComponents(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<Component> values = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value != null && !value.isJsonNull()) {
                    values.add(itemTextComponent(value, true));
                }
            }
        } else {
            values.add(itemTextComponent(element, true));
        }
        return List.copyOf(values);
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record TriggeredLootResult(boolean overrideDrops, List<ItemStack> items) {
    }
}
