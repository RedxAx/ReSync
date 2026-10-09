package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Vault;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.VaultDisplayItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.Plugin;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceSnapshotValue;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;

public class LootTableService implements Listener {
    private final PaperPlayerDataMutationAdmission playerDataAdmission = PaperPlayerDataMutationAdmission.shared();
    private static final GsonComponentSerializer COMPONENT_SERIALIZER = GsonComponentSerializer.gson();
    private final ReSyncJsonResourceStorage storage;
    private final CustomContentService customContentService;
    private final RuntimeFlowDispatcher dispatcher;
    private final Plugin plugin;
    private final LegacyRuntimeActivationGate legacyRuntimeGate;
    private final RuntimeResourceView<Map<String, List<LootTrigger>>> triggerView;
    private final Random random = new Random();
    private final Map<VaultUseKey, VaultSelection> pendingVaultUses = new ConcurrentHashMap<>();
    private final Map<VaultLocation, VaultSelection> restoringVaultUses = new ConcurrentHashMap<>();

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService) {
        this(storage, customContentService, null, null);
    }

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher) {
        this(storage, customContentService, dispatcher, null);
    }

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, Plugin plugin) {
        this(storage, customContentService, dispatcher, plugin, null);
    }

    public LootTableService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher,
                            Plugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
        this.plugin = plugin;
        this.legacyRuntimeGate = legacyRuntimeGate;
        this.triggerView = storage != null ? new RuntimeResourceView<>(storage, ReSyncResourceCatalog.LOOT_TABLE, this::projectTriggers) : null;
    }

    public JsonObject get(String id) {
        return storage != null ? storage.get(ReSyncResourceCatalog.LOOT_TABLE, id) : null;
    }

    public void shutdown() {
        if (triggerView != null) {
            triggerView.reset();
        }
        Map<VaultLocation, VaultSelection> restores = new HashMap<>(restoringVaultUses);
        pendingVaultUses.forEach((use, selection) -> restores.putIfAbsent(use.vault(), selection));
        pendingVaultUses.clear();
        restoringVaultUses.clear();
        restores.forEach((vault, selection) -> restoreVaultState(vault.location(), selection));
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
            dispatch(table, "deniedRollAction", rollContext, List.of(), event);
            return List.of();
        }
        dispatch(table, "beforeRollAction", rollContext, List.of(), event);
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
        dispatch(table, "afterRollAction", rollContext, items, event);
        return items;
    }

    public ItemStack preview(String id, Map<String, Object> context) {
        JsonObject table = get(id);
        if (table == null || !bool(table, "enabled", true)) {
            return null;
        }
        for (JsonElement poolElement : array(table, "pools")) {
            if (poolElement == null || !poolElement.isJsonObject()) {
                continue;
            }
            ItemStack item = roll(array(poolElement.getAsJsonObject(), "entries"), context != null ? context : Map.of());
            if (item != null) {
                item.setAmount(1);
                return item;
            }
        }
        return null;
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
        ItemStack item = event.getItem();
        if (item == null || event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (prepareVaultUse(event, item)) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
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
    public void onBlockDispenseLoot(BlockDispenseLootEvent event) {
        Player player = event.getPlayer();
        BlockState state = event.getBlock().getState();
        if (player == null || !(state instanceof Vault vault)) {
            return;
        }
        Location location = event.getBlock().getLocation();
        VaultSelection selection = pendingVaultUses.remove(new VaultUseKey(vaultLocation(location), player.getUniqueId()));
        if (selection == null) {
            return;
        }
        VaultLocation vaultLocation = vaultLocation(location);
        restoringVaultUses.put(vaultLocation, selection);
        Map<String, Object> context = context(player, null, location);
        context.put("event.type", "vault_open");
        context.put("event.block", event.getBlock());
        context.put("event.vault", vault);
        context.put("event.item", selection.usedKey());
        context.put("event.ominous", selection.ominous());
        try {
            event.setDispensedLoot(generate(selection.lootTableId(), context, event));
        } finally {
            schedule(1L, () -> {
                if (restoringVaultUses.remove(vaultLocation, selection)) {
                    restoreVaultState(location, selection);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVaultDisplayItem(VaultDisplayItemEvent event) {
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Vault vault)) {
            return;
        }
        List<VaultTrigger> candidates = vaultTriggers(vault);
        if (candidates.isEmpty()) {
            return;
        }
        List<VaultPreview> previews = vaultPreviews(vault, candidates);
        if (previews.isEmpty()) {
            return;
        }
        VaultPreview selected = previews.get(random.nextInt(previews.size()));
        Map<String, Object> context = context(selected.player(), null, event.getBlock().getLocation());
        context.put("event.type", "vault_preview");
        context.put("event.block", event.getBlock());
        context.put("event.vault", vault);
        context.put("event.item", selected.key());
        context.put("event.ominous", selected.trigger().ominous());
        ItemStack item = preview(selected.trigger().lootTableId(), context);
        if (item != null) {
            event.setDisplayItem(item);
        }
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
            playerDataAdmission.mutatePlayer("loot-table-give:" + player.getUniqueId(), player,
                () -> items.forEach(item -> player.getInventory().addItem(item)));
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

    private boolean prepareVaultUse(PlayerInteractEvent event, ItemStack usedKey) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || plugin == null) {
            return false;
        }
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Vault vault)) {
            return false;
        }
        List<VaultTrigger> candidates = vaultTriggers(vault);
        if (candidates.isEmpty()) {
            return false;
        }
        if (!VaultBlockDataAccess.isActive(vault.getBlockData())) {
            return true;
        }
        VaultTrigger match = candidates.stream().filter(candidate -> matchesItemTarget(usedKey, candidate.keyReference())).findFirst().orElse(null);
        if (match == null || vault.hasRewardedPlayer(event.getPlayer().getUniqueId())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            return true;
        }
        LootTable carrierLootTable = vaultCarrierLootTable(match.ominous());
        if (carrierLootTable == null) {
            event.setUseInteractedBlock(Event.Result.DENY);
            return true;
        }
        ItemStack nativeKey = usedKey.clone();
        nativeKey.setAmount(1);
        VaultSelection selection = new VaultSelection(match.lootTableId(), match.ominous(), nativeKey, vault.getKeyItem().clone(), vault.getLootTable(), carrierLootTable);
        Location location = event.getClickedBlock().getLocation();
        VaultUseKey useKey = new VaultUseKey(vaultLocation(location), event.getPlayer().getUniqueId());
        pendingVaultUses.put(useKey, selection);
        vault.setKeyItem(nativeKey);
        vault.setLootTable(carrierLootTable);
        vault.update(true, false);
        schedule(2L, () -> {
            if (pendingVaultUses.remove(useKey, selection)) {
                restoreVaultState(location, selection);
            }
        });
        return true;
    }

    private List<VaultTrigger> vaultTriggers(Vault vault) {
        if (storage == null || vault == null) {
            return List.of();
        }
        boolean ominous = VaultBlockDataAccess.isOminous(vault.getBlockData());
        List<VaultTrigger> result = new ArrayList<>();
        for (LootTrigger trigger : triggerView.get().getOrDefault("vault_open", List.of())) {
            if (vaultModeMatches(trigger.target(), ominous)) {
                result.add(new VaultTrigger(trigger.id(), ominous, vaultKeyReference(trigger.tool(), ominous)));
            }
        }
        return List.copyOf(result);
    }

    private List<VaultPreview> vaultPreviews(Vault vault, List<VaultTrigger> candidates) {
        if (vault == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<VaultPreview> previews = new ArrayList<>();
        for (UUID playerId : vault.getConnectedPlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || vault.hasRewardedPlayer(playerId)) {
                continue;
            }
            for (VaultTrigger candidate : candidates) {
                ItemStack key = matchingVaultKey(player, candidate.keyReference());
                if (key != null) {
                    previews.add(new VaultPreview(candidate, player, key));
                }
            }
        }
        return List.copyOf(previews);
    }

    private ItemStack matchingVaultKey(Player player, String reference) {
        if (player == null) {
            return null;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (matchesItemTarget(mainHand, reference)) {
            return mainHand;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return matchesItemTarget(offHand, reference) ? offHand : null;
    }

    static boolean vaultModeMatches(String configuredMode, boolean ominous) {
        String mode = configuredMode == null ? "" : configuredMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "", "normal" -> !ominous;
            case "ominous" -> ominous;
            case "any" -> true;
            default -> false;
        };
    }

    static String vaultKeyReference(JsonObject trigger, boolean ominous) {
        String configured = trigger != null && trigger.has("tool") && trigger.get("tool").isJsonPrimitive() ? trigger.get("tool").getAsString() : "";
        return vaultKeyReference(configured, ominous);
    }

    private static String vaultKeyReference(String configured, boolean ominous) {
        return configured.isBlank() || "none".equalsIgnoreCase(configured)
            ? ominous ? "minecraft:ominous_trial_key" : "minecraft:trial_key"
            : configured;
    }

    private LootTable vaultCarrierLootTable(boolean ominous) {
        String path = ominous ? "chests/trial_chambers/reward_ominous" : "chests/trial_chambers/reward";
        return Bukkit.getLootTable(NamespacedKey.minecraft(path));
    }

    private void restoreVaultState(Location location, VaultSelection selection) {
        if (location == null || selection == null || !(location.getBlock().getState() instanceof Vault vault)) {
            return;
        }
        ItemStack currentKey = vault.getKeyItem();
        LootTable currentLootTable = vault.getLootTable();
        boolean changed = false;
        if (currentKey.isSimilar(selection.usedKey()) && currentKey.getAmount() == selection.usedKey().getAmount()) {
            vault.setKeyItem(selection.originalKey());
            changed = true;
        }
        if (currentLootTable.getKey().equals(selection.carrierLootTable().getKey())) {
            vault.setLootTable(selection.originalLootTable());
            changed = true;
        }
        if (changed) {
            vault.update(true, false);
        }
    }

    private void schedule(long delay, Runnable action) {
        if (plugin == null) {
            action.run();
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, action, Math.max(0L, delay));
    }

    private VaultLocation vaultLocation(Location location) {
        World world = location != null ? location.getWorld() : null;
        return new VaultLocation(world != null ? world.getUID() : new UUID(0L, 0L), location != null ? location.getBlockX() : 0,
            location != null ? location.getBlockY() : 0, location != null ? location.getBlockZ() : 0);
    }

    private TriggeredLootResult triggeredLoot(String eventType, Map<String, Object> context, Event event, Player player, ItemStack item, Entity entity, Location location, EntityDamageEvent damageEvent) {
        if (storage == null || eventType == null) {
            return new TriggeredLootResult(false, List.of());
        }
        List<ItemStack> items = new ArrayList<>();
        boolean overrideDrops = false;
        for (LootTrigger trigger : triggerView.get().getOrDefault(eventType, List.of())) {
            if (!triggerMatches(trigger, eventType, player, item, entity, location, damageEvent)) {
                continue;
            }
            overrideDrops |= trigger.overrideDrops();
            Map<String, Object> variables = new HashMap<>(context != null ? context : Map.of());
            variables.put("lootTable", trigger.id());
            variables.put("lootEvent", eventType);
            items.addAll(generate(trigger.id(), variables, event));
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

    private Map<String, List<LootTrigger>> projectTriggers(List<ResourceSnapshotValue> values) {
        Map<String, List<LootTrigger>> grouped = new LinkedHashMap<>();
        for (ResourceSnapshotValue value : values) {
            JsonObject table = value.value();
            if (!bool(table, "enabled", true)) {
                continue;
            }
            for (JsonObject trigger : triggers(table)) {
                String event = text(trigger, "event").toLowerCase(Locale.ROOT);
                if (!event.isBlank() && !"none".equals(event)) {
                    grouped.computeIfAbsent(event, ignored -> new ArrayList<>()).add(new LootTrigger(value.id(),
                        text(trigger, "target"), text(trigger, "tool"), text(trigger, "entity"), bool(trigger, "overrideDrops", false)));
                }
            }
        }
        Map<String, List<LootTrigger>> result = new LinkedHashMap<>();
        grouped.forEach((event, entries) -> result.put(event, List.copyOf(entries)));
        return Map.copyOf(result);
    }

    private boolean triggerMatches(LootTrigger trigger, String eventType, Player player, ItemStack item, Entity entity,
                                   Location location, EntityDamageEvent damageEvent) {
        String target = trigger.target();
        String tool = trigger.tool();
        return switch (eventType) {
            case "block_break" -> matchesBlockTarget(location, target) && matchesTool(player, item, tool, damageEvent);
            case "block_place" -> (matchesBlockTarget(location, target) || matchesItemTarget(item, target)) && matchesTool(player, item, tool, damageEvent);
            case "entity_death" -> matchesEntityTarget(entity, target) && matchesTool(player, item, tool, damageEvent);
            case "item_use" -> matchesItemTarget(item, firstFilled(target, tool));
            case "item_hit_entity" -> matchesItemTarget(item, target) && matchesTool(player, item, tool, damageEvent) && matchesEntityTarget(entity, trigger.entity());
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
        return damageEvent.getDamageSource().getDamageType().getKey().toString();
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
        JsonObject hooks = table != null && table.has("hooks") && table.get("hooks").isJsonObject() ? table.getAsJsonObject("hooks") : null;
        if (hooks == null || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        if (context != null) {
            variables.putAll(context);
        }
        variables.put("lootTable", firstFilled(text(table, "id"), String.valueOf(variables.getOrDefault("lootTable", ""))));
        variables.put("items", items);
        variables.put("hook", hook);
        Player player = variables.get("player") instanceof Player value ? value : null;
        JsonElement action = hooks.get(hook);
        String legacyHook = hook.substring(0, hook.length() - "Action".length()) + "Flow";
        if (!legacyAllowed() && (containsLegacyHook(action) || containsLegacyHook(hooks.get(legacyHook)))) {
            legacyRuntimeGate.recordBlocked("Loot table " + variables.get("lootTable") + " " + hook + " legacy hook fallback");
        }
        if (dispatchHook(action, player, event, variables)) {
            return;
        }
        if (legacyAllowed()) {
            dispatchHook(hooks.get(legacyHook), player, event, variables);
        }
    }

    private boolean dispatchHook(JsonElement action, Player player, Event event, Map<String, Object> variables) {
        if (action == null || action.isJsonNull()) {
            return false;
        }
        if (action.isJsonObject()) {
            dispatcher.dispatchFunction(action.getAsJsonObject(), player, event, variables);
            return true;
        }
        if (action.isJsonArray()) {
            boolean dispatched = false;
            for (JsonElement element : action.getAsJsonArray()) {
                dispatched |= dispatchHook(element, player, event, variables);
            }
            return dispatched;
        }
        if (legacyAllowed() && action.isJsonPrimitive() && action.getAsJsonPrimitive().isString()) {
            String flowId = action.getAsString();
            if (!flowId.isBlank() && !"none".equalsIgnoreCase(flowId)) {
                dispatcher.dispatch(flowId, player, event, variables);
                return true;
            }
        }
        return false;
    }

    private boolean containsLegacyHook(JsonElement action) {
        if (action == null || action.isJsonNull()) {
            return false;
        }
        if (action.isJsonArray()) {
            for (JsonElement element : action.getAsJsonArray()) {
                if (containsLegacyHook(element)) {
                    return true;
                }
            }
            return false;
        }
        return action.isJsonPrimitive() && action.getAsJsonPrimitive().isString()
            && !action.getAsString().isBlank() && !"none".equalsIgnoreCase(action.getAsString());
    }

    private boolean legacyAllowed() {
        return legacyRuntimeGate == null || legacyRuntimeGate.allowsLegacyRuntime();
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
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Loot definition field must be an integer: " + key, exception);
        }
    }

    private int integer(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Loot definition value must be an integer", exception);
        }
    }

    private Integer optionalInteger(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Loot definition field must be an integer: " + key, exception);
        }
    }

    private double decimal(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        double value;
        try {
            value = object.get(key).getAsDouble();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Loot definition field must be a number: " + key, exception);
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Loot definition field must be finite: " + key);
        return value;
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
        } catch (RuntimeException componentFailure) {
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
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Loot definition field must be a boolean: " + key, exception);
        }
    }

    private record LootTrigger(String id, String target, String tool, String entity, boolean overrideDrops) {
    }

    private record TriggeredLootResult(boolean overrideDrops, List<ItemStack> items) {
    }

    private record VaultTrigger(String lootTableId, boolean ominous, String keyReference) {
    }

    private record VaultPreview(VaultTrigger trigger, Player player, ItemStack key) {
    }

    private record VaultSelection(String lootTableId, boolean ominous, ItemStack usedKey, ItemStack originalKey, LootTable originalLootTable, LootTable carrierLootTable) {
    }

    private record VaultLocation(UUID worldId, int x, int y, int z) {
        private Location location() {
            World world = Bukkit.getWorld(worldId);
            return world != null ? new Location(world, x, y, z) : null;
        }
    }

    private record VaultUseKey(VaultLocation vault, UUID playerId) {
    }
}
