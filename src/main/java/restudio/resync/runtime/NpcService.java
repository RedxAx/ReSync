package restudio.resync.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.dialog.DialogService;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NpcService implements Listener {
    private final JavaPlugin plugin;
    private final ReSyncJsonResourceStorage storage;
    private final CustomContentService customContentService;
    private final RuntimeFlowDispatcher dispatcher;
    private final VillageProfileService villageProfileService;
    private final LootTableService lootTableService;
    private final DialogService dialogService;
    private final Map<String, UUID> activeNpcs = new ConcurrentHashMap<>();
    private final PlayerNpcRuntime playerNpcRuntime;
    private final NamespacedKey npcIdKey;

    public NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService) {
        this(plugin, storage, customContentService, dispatcher, villageProfileService, lootTableService, dialogService, (PlayerNpcRuntime) null);
    }

    public NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService, PlayerNpcRuntime playerNpcRuntime) {
        this(plugin, storage, customContentService, dispatcher, villageProfileService, lootTableService, dialogService, playerNpcRuntime, new NamespacedKey(plugin, "resync_npc_id"));
    }

    NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService, NamespacedKey npcIdKey) {
        this(plugin, storage, customContentService, dispatcher, villageProfileService, lootTableService, dialogService, (PlayerNpcRuntime) null, npcIdKey);
    }

    NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService, PlayerNpcRuntime playerNpcRuntime, NamespacedKey npcIdKey) {
        this.plugin = plugin;
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
        this.villageProfileService = villageProfileService;
        this.lootTableService = lootTableService;
        this.dialogService = dialogService;
        this.playerNpcRuntime = playerNpcRuntime;
        this.npcIdKey = npcIdKey;
        if (plugin != null) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickFollowPlayers, 2L, 2L);
        }
    }

    public JsonObject get(String id) {
        return storage != null ? storage.get(ReSyncResourceCatalog.NPC_DEFINITION, id) : null;
    }

    public Entity spawn(String id, Location location) {
        JsonObject definition = get(id);
        if (definition == null || location == null || location.getWorld() == null || !bool(definition, "enabled", true)) {
            return null;
        }
        Entity active = activeEntity(id);
        if (active != null && !active.isDead()) {
            return active;
        }
        if (playerEntityType(definition)) {
            if (playerNpcRuntime == null || !playerNpcRuntime.available()) {
                return null;
            }
            boolean spawned = playerNpcRuntime.spawn(id, definition, location);
            if (spawned) {
                dispatch(id, "spawnAction", null, null, location, null);
            }
            return null;
        }
        EntityType type = spawnEntityType(definition);
        if (type == null) {
            return null;
        }
        Entity entity;
        try {
            entity = location.getWorld().spawnEntity(location, type);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        activeNpcs.put(id, entity.getUniqueId());
        entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, id);
        applyDefinition(entity, definition);
        dispatch(id, "spawnAction", null, entity, location, null);
        return entity;
    }

    public boolean despawn(String id) {
        UUID uuid = activeNpcs.remove(id);
        if (uuid == null || plugin.getServer().getEntity(uuid) == null) {
            boolean packetDespawned = playerNpcRuntime != null && playerNpcRuntime.despawn(id);
            if (packetDespawned) {
                dispatch(id, "despawnAction", null, null, null, null);
            }
            return packetDespawned;
        }
        Entity entity = plugin.getServer().getEntity(uuid);
        Location location = entity.getLocation();
        entity.remove();
        dispatch(id, "despawnAction", null, entity, location, null);
        return true;
    }

    public boolean open(Player player, String id) {
        JsonObject definition = get(id);
        if (definition == null) {
            return false;
        }
        String tradeProfile = link(definition, "tradeProfile");
        if (player != null && !tradeProfile.isBlank()) {
            Entity entity = activeEntity(id);
            if (entity != null) {
                return villageProfileService.openTrades(player, entity, tradeProfile);
            }
            return villageProfileService.openVirtualTrades(player, tradeProfile);
        }
        return false;
    }

    public void spawnStartupNpcs() {
    }

    public boolean setProfile(String id, String profileId) {
        Entity entity = activeEntity(id);
        return entity instanceof Villager villager && villageProfileService.apply(villager, profileId);
    }

    public void reload(String id, JsonObject definition, boolean deleted) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (deleted || definition == null || !bool(definition, "enabled", true)) {
            despawn(id);
            return;
        }
        if (playerEntityType(definition)) {
            Entity entity = activeEntity(id);
            Location configured = location(definition);
            if (entity != null && !entity.isDead()) {
                Location spawnLocation = configured != null ? configured : entity.getLocation();
                entity.remove();
                activeNpcs.remove(id);
                if (playerNpcRuntime != null && playerNpcRuntime.available() && playerNpcRuntime.spawn(id, definition, spawnLocation)) {
                    dispatch(id, "spawnAction", null, null, spawnLocation, null);
                }
                return;
            }
            if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                playerNpcRuntime.reload(id, definition, false, configured);
                return;
            }
            return;
        }
        if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
            Location packetLocation = playerNpcRuntime.location(id);
            playerNpcRuntime.despawn(id);
            Location configured = location(definition);
            Location spawnLocation = configured != null ? configured : packetLocation;
            if (spawnLocation != null) {
                spawn(id, spawnLocation);
            }
            return;
        }
        Entity entity = activeEntity(id);
        if (entity == null || entity.isDead()) {
            return;
        }
        EntityType nextType = spawnEntityType(definition);
        if (nextType == null) {
            despawn(id);
            return;
        }
        Location location = location(definition);
        if (entity.getType() != nextType) {
            Location spawnLocation = location != null ? location : entity.getLocation();
            entity.remove();
            activeNpcs.remove(id);
            spawn(id, spawnLocation);
            return;
        }
        if (location != null) {
            entity.teleport(location);
        }
        applyDefinition(entity, definition);
    }

    public boolean isActive(String id) {
        Entity entity = activeEntity(id);
        return entity != null && !entity.isDead() || playerNpcRuntime != null && playerNpcRuntime.isActive(id);
    }

    public String playerNpcUnavailableReason() {
        return playerNpcRuntime != null ? playerNpcRuntime.unavailableReason() : "Player NPC runtime unavailable";
    }

    private Entity activeEntity(String id) {
        UUID uuid = activeNpcs.get(id);
        return uuid != null && plugin != null ? plugin.getServer().getEntity(uuid) : null;
    }

    private Location location(JsonObject definition) {
        if (definition == null || !definition.has("location") || !definition.get("location").isJsonObject()) {
            return null;
        }
        JsonObject location = definition.getAsJsonObject("location");
        String worldName = text(location, "world");
        World world = !worldName.isBlank() ? plugin.getServer().getWorld(worldName) : null;
        if (world == null) {
            return null;
        }
        return new Location(
            world,
            decimal(location, "x", 0.0),
            decimal(location, "y", world.getSpawnLocation().getY()),
            decimal(location, "z", 0.0),
            (float) decimal(location, "yaw", 0.0),
            (float) decimal(location, "pitch", 0.0)
        );
    }

    private void applyDefinition(Entity entity, JsonObject definition) {
        String displayName = text(definition, "displayName");
        if (!displayName.isBlank()) {
            entity.setCustomName(displayName);
            entity.setCustomNameVisible(true);
        } else {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        }
        entity.setInvulnerable(bool(definition, "invulnerable", true));
        entity.setGravity(bool(definition, "gravity", true));
        if (entity instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                JsonObject gear = definition.has("equipment") && definition.get("equipment").isJsonObject() ? definition.getAsJsonObject("equipment") : new JsonObject();
                equipment.setItemInMainHand(item(text(gear, "mainHand")));
                equipment.setItemInOffHand(item(text(gear, "offHand")));
                equipment.setHelmet(item(text(gear, "helmet")));
                equipment.setChestplate(item(text(gear, "chestplate")));
                equipment.setLeggings(item(text(gear, "leggings")));
                equipment.setBoots(item(text(gear, "boots")));
            }
        }
        if (entity instanceof Mob mob) {
            mob.setAI(bool(definition, "ai", false));
        }
        if (entity instanceof Villager villager) {
            String tradeProfile = link(definition, "tradeProfile");
            if (tradeProfile.isBlank()) {
                villager.setRecipes(List.of());
            } else {
                villageProfileService.apply(villager, tradeProfile);
            }
        }
    }

    private void tickFollowPlayers() {
        for (Map.Entry<String, UUID> entry : activeNpcs.entrySet()) {
            Entity entity = activeEntity(entry.getKey());
            JsonObject definition = get(entry.getKey());
            if (entity == null || entity.isDead() || definition == null || !bool(definition, "followPlayer", false)) {
                continue;
            }
            Player target = nearestPlayer(entity.getLocation(), decimal(definition, "followRange", 12.0));
            if (target == null) {
                continue;
            }
            Location next = entity.getLocation();
            LookAngles angles = lookAt(next, target.getEyeLocation());
            next.setYaw(smoothAngle(next.getYaw(), angles.yaw(), 0.35F));
            next.setPitch(smoothAngle(next.getPitch(), angles.pitch(), 0.35F));
            entity.setRotation(next.getYaw(), next.getPitch());
        }
    }

    private Player nearestPlayer(Location origin, double range) {
        if (origin == null || origin.getWorld() == null || range <= 0) {
            return null;
        }
        double maxDistanceSquared = range * range;
        Player nearest = null;
        double nearestDistance = maxDistanceSquared;
        for (Player player : origin.getWorld().getPlayers()) {
            if (player == null || player.isDead() || !player.isOnline()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(origin);
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private LookAngles lookAt(Location origin, Location target) {
        double dx = target.getX() - origin.getX();
        double dy = target.getY() - (origin.getY() + 1.62);
        double dz = target.getZ() - origin.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new LookAngles(yaw, Math.clamp(pitch, -90F, 90F));
    }

    private float smoothAngle(float current, float target, float factor) {
        float delta = ((target - current + 540F) % 360F) - 180F;
        return current + delta * factor;
    }

    private ItemStack item(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        ItemStack stack = customContentService != null ? customContentService.createReferencedItem(reference, 1) : null;
        if (stack != null) {
            return stack;
        }
        Material material = RuntimeMaterialResolver.itemMaterial(reference);
        return material != null ? new ItemStack(material, 1) : null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        String id = npcId(event.getRightClicked());
        if (id.isBlank()) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", true);
        variables.put("leftClick", false);
        variables.put("shifting", event.getPlayer().isSneaking());
        variables.put("sneaking", event.getPlayer().isSneaking());
        variables.put("shiftClick", event.getPlayer().isSneaking());
        variables.put("success", true);
        dispatchInteraction(id, false, event.getPlayer(), event.getRightClicked(), event.getRightClicked().getLocation(), event, variables);
        open(event.getPlayer(), id);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        Player player = event.getDamager() instanceof Player damager ? damager : null;
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", false);
        variables.put("leftClick", true);
        variables.put("shifting", player != null && player.isSneaking());
        variables.put("sneaking", player != null && player.isSneaking());
        variables.put("shiftClick", player != null && player.isSneaking());
        variables.put("success", true);
        JsonObject definition = get(id);
        if (bool(definition, "invulnerable", true)) {
            event.setCancelled(true);
        }
        dispatchInteraction(id, true, player, event.getEntity(), event.getEntity().getLocation(), event, variables);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        JsonObject definition = get(id);
        String lootTable = definition != null ? link(definition, "lootTable") : "";
        if (!lootTable.isBlank() && lootTableService != null) {
            event.getDrops().clear();
            event.getDrops().addAll(lootTableService.generate(lootTable, lootTableService.context(event.getEntity().getKiller(), event.getEntity(), event.getEntity().getLocation())));
        }
        activeNpcs.remove(id);
    }

    void dispatch(String id, String hook, Player player, Entity entity, Location location, Event event) {
        dispatch(id, hook, player, entity, location, event, Map.of());
    }

    void dispatch(String id, String hook, Player player, Entity entity, Location location, Event event, Map<String, Object> extraVariables) {
        JsonObject definition = get(id);
        JsonObject hooks = definition != null && definition.has("hooks") && definition.get("hooks").isJsonObject() ? definition.getAsJsonObject("hooks") : null;
        if (hooks == null || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = hookVariables(id, player, entity, location);
        if (extraVariables != null) {
            variables.putAll(extraVariables);
        }
        variables.put("hook", hook);
        if (hooks.has(hook) && dispatchHookElement(hooks.get(hook), player, event, variables)) {
            return;
        }
        String legacyHook = hook.endsWith("Action") ? hook.substring(0, hook.length() - "Action".length()) + "Flow" : hook;
        String flowId = text(hooks, legacyHook);
        if (!flowId.isBlank()) {
            dispatcher.dispatch(flowId, player, event, variables);
        }
    }

    private boolean dispatchHookElement(JsonElement element, Player player, Event event, Map<String, Object> variables) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonObject()) {
            dispatcher.dispatchFunction(element.getAsJsonObject(), player, event, variables);
            return true;
        }
        if (element.isJsonArray()) {
            boolean dispatched = false;
            for (JsonElement entry : element.getAsJsonArray()) {
                dispatched |= dispatchHookElement(entry, player, event, variables);
            }
            return dispatched;
        }
        if (element.isJsonPrimitive()) {
            String flowId = element.getAsString();
            if (!flowId.isBlank() && !"none".equalsIgnoreCase(flowId)) {
                dispatcher.dispatch(flowId, player, event, variables);
                return true;
            }
        }
        return false;
    }

    private void dispatchInteraction(String id, boolean leftClick, Player player, Entity entity, Location location, Event event, Map<String, Object> variables) {
        String primary = leftClick ? "leftClickAction" : "rightClickAction";
        if (hasHook(id, primary)) {
            dispatch(id, primary, player, entity, location, event, variables);
        }
    }

    private boolean hasHook(String id, String hook) {
        JsonObject definition = get(id);
        JsonObject hooks = definition != null && definition.has("hooks") && definition.get("hooks").isJsonObject() ? definition.getAsJsonObject("hooks") : null;
        if (hooks == null || hook == null || hook.isBlank()) {
            return false;
        }
        if (hooks.has(hook) && !hooks.get(hook).isJsonNull()) {
            JsonElement element = hooks.get(hook);
            if (element.isJsonObject()) {
                return true;
            }
            if (element.isJsonArray()) {
                return element.getAsJsonArray().size() > 0;
            }
        }
        if (hooks.has(hook) && hooks.get(hook).isJsonPrimitive() && !hooks.get(hook).getAsString().isBlank() && !"none".equalsIgnoreCase(hooks.get(hook).getAsString())) {
            return true;
        }
        String legacyHook = hook.endsWith("Action") ? hook.substring(0, hook.length() - "Action".length()) + "Flow" : hook;
        return !text(hooks, legacyHook).isBlank();
    }

    public void packetInteract(String id, Player player, Location location, boolean leftClick, boolean shifting) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", !leftClick);
        variables.put("leftClick", leftClick);
        variables.put("shifting", shifting);
        variables.put("sneaking", shifting);
        variables.put("shiftClick", shifting);
        variables.put("success", true);
        dispatchInteraction(id, leftClick, player, null, location, null, variables);
        if (!leftClick) {
            open(player, id);
        }
    }

    static Map<String, Object> hookVariables(String id, Player player, Entity entity, Location location) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("npcId", id);
        variables.put("player", player);
        variables.put("entity", entity);
        variables.put("location", location);
        variables.put("event.id", id);
        variables.put("event.entity", entity);
        variables.put("event.target", entity);
        variables.put("event.location", location);
        return variables;
    }

    private String link(JsonObject definition, String key) {
        if (definition != null && definition.has("links") && definition.get("links").isJsonObject()) {
            String value = text(definition.getAsJsonObject("links"), key);
            if (!value.isBlank() && !"none".equalsIgnoreCase(value)) {
                return value;
            }
        }
        String value = text(definition, key);
        return "none".equalsIgnoreCase(value) ? "" : value;
    }

    private String npcId(Entity entity) {
        if (entity == null) {
            return "";
        }
        String id = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        return id != null ? id : "";
    }

    private EntityType entityType(String value) {
        try {
            return EntityType.valueOf(enumName(value));
        } catch (Exception ignored) {
            return EntityType.VILLAGER;
        }
    }

    private boolean playerEntityType(JsonObject definition) {
        String type = text(definition, "entityType");
        return "player".equalsIgnoreCase(type) || "minecraft:player".equalsIgnoreCase(type);
    }

    private EntityType spawnEntityType(JsonObject definition) {
        String type = text(definition, "entityType");
        if (playerEntityType(definition)) {
            return null;
        }
        return entityType(type);
    }

    private String enumName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int namespace = value.indexOf(':');
        String local = namespace >= 0 ? value.substring(namespace + 1) : value;
        return local.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double decimal(JsonObject object, String key, double fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record LookAngles(float yaw, float pitch) {
    }
}
