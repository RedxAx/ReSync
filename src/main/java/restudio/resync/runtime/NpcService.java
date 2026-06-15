package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.Chunk;
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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.dialog.DialogService;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.HashMap;
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
    private final NamespacedKey npcIdKey;

    public NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService) {
        this(plugin, storage, customContentService, dispatcher, villageProfileService, lootTableService, dialogService, new NamespacedKey(plugin, "resync_npc_id"));
    }

    NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, VillageProfileService villageProfileService, LootTableService lootTableService, DialogService dialogService, NamespacedKey npcIdKey) {
        this.plugin = plugin;
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
        this.villageProfileService = villageProfileService;
        this.lootTableService = lootTableService;
        this.dialogService = dialogService;
        this.npcIdKey = npcIdKey;
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
        EntityType type = entityType(text(definition, "entityType"));
        Entity entity = location.getWorld().spawnEntity(location, type);
        activeNpcs.put(id, entity.getUniqueId());
        entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, id);
        applyDefinition(entity, definition);
        dispatch(id, "spawnFlow", null, entity, location, null);
        return entity;
    }

    public boolean despawn(String id) {
        UUID uuid = activeNpcs.remove(id);
        if (uuid == null || plugin.getServer().getEntity(uuid) == null) {
            return false;
        }
        Entity entity = plugin.getServer().getEntity(uuid);
        Location location = entity.getLocation();
        entity.remove();
        dispatch(id, "despawnFlow", null, entity, location, null);
        return true;
    }

    public boolean open(Player player, String id) {
        JsonObject definition = get(id);
        if (definition == null) {
            return false;
        }
        String dialog = text(definition, "dialog");
        if (player != null && dialogService != null && !dialog.isBlank() && dialogService.show(player, dialog)) {
            return true;
        }
        String tradeProfile = text(definition, "tradeProfile");
        if (player != null && !tradeProfile.isBlank()) {
            Entity entity = activeEntity(id);
            if (entity instanceof Villager villager) {
                return villageProfileService.openTrades(player, villager, tradeProfile);
            }
        }
        return false;
    }

    public void spawnStartupNpcs() {
        if (storage == null) {
            return;
        }
        for (String id : storage.listIds(ReSyncResourceCatalog.NPC_DEFINITION)) {
            JsonObject definition = get(id);
            if (definition == null || !"startup".equalsIgnoreCase(text(definition, "spawnMode"))) {
                continue;
            }
            Location location = location(definition);
            if (location != null) {
                spawn(id, location);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (storage == null || event == null) {
            return;
        }
        for (String id : storage.listIds(ReSyncResourceCatalog.NPC_DEFINITION)) {
            JsonObject definition = get(id);
            if (definition == null || !"chunk".equalsIgnoreCase(text(definition, "spawnMode"))) {
                continue;
            }
            Location location = location(definition);
            if (location != null && sameChunk(event.getChunk(), location)) {
                spawn(id, location);
            }
        }
    }

    public boolean setProfile(String id, String profileId) {
        Entity entity = activeEntity(id);
        return entity instanceof Villager villager && villageProfileService.apply(villager, profileId);
    }

    private Entity activeEntity(String id) {
        UUID uuid = activeNpcs.get(id);
        return uuid != null ? plugin.getServer().getEntity(uuid) : null;
    }

    private boolean sameChunk(Chunk chunk, Location location) {
        return chunk != null && location != null && location.getWorld() != null
            && chunk.getWorld().equals(location.getWorld())
            && chunk.getX() == location.getBlockX() >> 4
            && chunk.getZ() == location.getBlockZ() >> 4;
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
        }
        entity.setInvulnerable(bool(definition, "invulnerable", true));
        if (entity instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null && definition.has("equipment") && definition.get("equipment").isJsonObject()) {
                JsonObject gear = definition.getAsJsonObject("equipment");
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
        if (entity instanceof Villager villager && !text(definition, "tradeProfile").isBlank()) {
            villageProfileService.apply(villager, text(definition, "tradeProfile"));
        }
    }

    private ItemStack item(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        ItemStack stack = customContentService != null ? customContentService.createReferencedItem(reference, 1) : null;
        if (stack != null) {
            return stack;
        }
        Material material = Material.matchMaterial(reference);
        return material != null && material.isItem() && !material.isAir() ? new ItemStack(material, 1) : null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        String id = npcId(event.getRightClicked());
        if (id.isBlank()) {
            return;
        }
        dispatch(id, "interactFlow", event.getPlayer(), event.getRightClicked(), event.getRightClicked().getLocation(), event);
        open(event.getPlayer(), id);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        Player player = event.getDamager() instanceof Player damager ? damager : null;
        dispatch(id, "damageFlow", player, event.getEntity(), event.getEntity().getLocation(), event);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        JsonObject definition = get(id);
        String lootTable = definition != null ? text(definition, "lootTable") : "";
        if (!lootTable.isBlank() && lootTableService != null) {
            event.getDrops().clear();
            event.getDrops().addAll(lootTableService.generate(lootTable, lootTableService.context(event.getEntity().getKiller(), event.getEntity(), event.getEntity().getLocation())));
        }
        activeNpcs.remove(id);
        dispatch(id, "deathFlow", event.getEntity().getKiller(), event.getEntity(), event.getEntity().getLocation(), event);
    }

    void dispatch(String id, String hook, Player player, Entity entity, Location location, Event event) {
        JsonObject definition = get(id);
        String flowId = definition != null && definition.has("hooks") && definition.get("hooks").isJsonObject()
            ? text(definition.getAsJsonObject("hooks"), hook)
            : "";
        if (flowId.isBlank() || dispatcher == null) {
            return;
        }
        dispatcher.dispatch(flowId, player, event, hookVariables(id, player, entity, location));
    }

    static Map<String, Object> hookVariables(String id, Player player, Entity entity, Location location) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("npcId", id);
        variables.put("player", player);
        variables.put("entity", entity);
        variables.put("location", location);
        return variables;
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
            return EntityType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return EntityType.VILLAGER;
        }
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
}
