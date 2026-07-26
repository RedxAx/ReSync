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
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNpcHandle;
import restudio.resync.Log;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.diagnostics.BoundedDiagnosticDeduplicator;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.runtime.event.ReSyncNpcInteractEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    private final TradeProfileService tradeProfileService;
    private final LootTableService lootTableService;
    private final DialogService dialogService;
    private final Map<String, UUID> activeNpcs = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> activeDefinitions = new ConcurrentHashMap<>();
    private final BoundedDiagnosticDeduplicator reportedLifecycleFailures = new BoundedDiagnosticDeduplicator(512);
    private final PlayerNpcRuntime playerNpcRuntime;
    private final PlayerNpcInstanceStorage playerNpcInstances;
    private final NamespacedKey npcIdKey;
    private BukkitTask followTask;
    private BukkitTask restoreTask;

    public NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, TradeProfileService tradeProfileService, LootTableService lootTableService, DialogService dialogService) {
        this(plugin, storage, customContentService, dispatcher, tradeProfileService, lootTableService, dialogService, (PlayerNpcRuntime) null);
    }

    public NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, TradeProfileService tradeProfileService, LootTableService lootTableService, DialogService dialogService, PlayerNpcRuntime playerNpcRuntime) {
        this(plugin, storage, customContentService, dispatcher, tradeProfileService, lootTableService, dialogService, playerNpcRuntime, new NamespacedKey(plugin, "resync_npc_id"));
    }

    NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, TradeProfileService tradeProfileService, LootTableService lootTableService, DialogService dialogService, NamespacedKey npcIdKey) {
        this(plugin, storage, customContentService, dispatcher, tradeProfileService, lootTableService, dialogService, (PlayerNpcRuntime) null, npcIdKey);
    }

    NpcService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, TradeProfileService tradeProfileService, LootTableService lootTableService, DialogService dialogService, PlayerNpcRuntime playerNpcRuntime, NamespacedKey npcIdKey) {
        this.plugin = plugin;
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
        this.tradeProfileService = tradeProfileService;
        this.lootTableService = lootTableService;
        this.dialogService = dialogService;
        this.playerNpcRuntime = playerNpcRuntime;
        this.playerNpcInstances = plugin != null
            ? new PlayerNpcInstanceStorage(plugin.getDataFolder().toPath().resolve("runtime").resolve("player-npcs.json"))
            : null;
        this.npcIdKey = npcIdKey;
        if (plugin != null) {
            followTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickFollowPlayers, 2L, 2L);
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
            if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                playerNpcRuntime.despawn(id);
            }
            return active;
        }
        boolean packetActive = playerNpcRuntime != null && playerNpcRuntime.isActive(id);
        if (playerEntityType(definition)) {
            if (playerNpcRuntime == null || !playerNpcRuntime.available()) {
                return null;
            }
            if (packetActive) {
                return null;
            }
            boolean spawned = playerNpcRuntime.spawn(id, definition, location);
            if (spawned) {
                if (!savePlayerNpcPosition(id, location)) {
                    playerNpcRuntime.despawn(id);
                    reportLifecycleWarning("persist Player NPC", id, "The NPC position could not be saved");
                    return null;
                }
                activeDefinitions.put(id, definition.deepCopy());
                dispatch(id, "spawnAction", null, null, location, null);
            }
            return null;
        }
        if (packetActive) {
            playerNpcRuntime.despawn(id);
        }
        EntityType type = spawnEntityType(definition);
        if (type == null) {
            return null;
        }
        Entity entity = location.getWorld().spawnEntity(location, type);
        try {
            entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, id);
            applyDefinition(entity, definition);
            activeNpcs.put(id, entity.getUniqueId());
            activeDefinitions.put(id, definition.deepCopy());
            if (!removePlayerNpcPosition(id)) {
                throw new IllegalStateException("The previous Player NPC instance could not be removed");
            }
        } catch (RuntimeException exception) {
            entity.remove();
            activeNpcs.remove(id, entity.getUniqueId());
            throw exception;
        }
        dispatch(id, "spawnAction", null, entity, location, null);
        return entity;
    }

    public boolean despawn(String id) {
        UUID uuid = activeNpcs.remove(id);
        Entity entity = uuid != null && plugin != null ? plugin.getServer().getEntity(uuid) : null;
        Location location = entity != null ? entity.getLocation() : playerNpcRuntime != null ? playerNpcRuntime.location(id) : null;
        boolean entityDespawned = entity != null;
        boolean persisted = playerNpcInstances != null && playerNpcInstances.contains(id);
        if (persisted && !removePlayerNpcPosition(id)) {
            reportLifecycleWarning("persist Player NPC despawn", id, "The saved NPC instance could not be removed");
            if (uuid != null) {
                activeNpcs.put(id, uuid);
            }
            return false;
        }
        if (entity != null) {
            entity.remove();
        }
        boolean packetDespawned = playerNpcRuntime != null && playerNpcRuntime.despawn(id);
        if (entityDespawned || packetDespawned || persisted) {
            dispatch(id, "despawnAction", null, entity, location, null);
            activeDefinitions.remove(id);
            return true;
        }
        activeDefinitions.remove(id);
        return false;
    }

    public boolean open(Player player, String id) {
        JsonObject definition = get(id);
        if (definition == null) {
            return false;
        }
        String dialog = link(definition, "dialog");
        if (player != null && !dialog.isBlank()) {
            boolean opened = dialogService != null && dialogService.show(player, dialog);
            if (!opened) {
                reportLifecycleWarning("open NPC dialog", id, "Dialog " + dialog + " is unavailable");
            }
            return opened;
        }
        String tradeProfile = link(definition, "tradeProfile");
        if (player != null && !tradeProfile.isBlank()) {
            Entity entity = activeEntity(id);
            boolean opened = tradeProfileService != null && (entity != null
                ? tradeProfileService.openTrades(player, entity, tradeProfile)
                : tradeProfileService.openVirtualTrades(player, tradeProfile));
            if (!opened) {
                reportLifecycleWarning("open NPC trade profile", id, "Trade profile " + tradeProfile + " is unavailable");
            }
            return opened;
        }
        return false;
    }

    public void restorePersistentNpcs() {
        if (plugin == null || storage == null) {
            return;
        }
        if (restoreTask != null) {
            restoreTask.cancel();
        }
        restoreTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            restoreTask = null;
            try {
                restorePersistedNpcEntities();
            } catch (RuntimeException exception) {
                reportLifecycleFailure("restore persisted NPC entities", "startup", exception);
            }
            try {
                restorePersistedPlayerNpcs();
            } catch (RuntimeException exception) {
                reportLifecycleFailure("restore persisted Player NPC instances", "startup", exception);
            }
        }, 1L);
    }

    public void shutdown() {
        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        activeNpcs.clear();
        activeDefinitions.clear();
    }

    public boolean setProfile(String id, String profileId) {
        Entity entity = activeEntity(id);
        return tradeProfileService != null && entity instanceof Villager villager && tradeProfileService.apply(villager, profileId);
    }

    public void reload(String id, JsonObject definition, boolean deleted) {
        try {
            if (id == null || id.isBlank()) {
                return;
            }
            if (storage != null) {
                definition = get(id);
                deleted = definition == null;
            }
            if (deleted || definition == null || !bool(definition, "enabled", true)) {
                despawn(id);
                return;
            }
            if (playerEntityType(definition)) {
                Entity entity = activeEntity(id);
                if (entity != null && !entity.isDead()) {
                    Location spawnLocation = entity.getLocation();
                    entity.remove();
                    activeNpcs.remove(id);
                    boolean activated = false;
                    if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                        activated = playerNpcRuntime.reload(id, definition, false, spawnLocation);
                    } else if (playerNpcRuntime != null && playerNpcRuntime.available() && playerNpcRuntime.spawn(id, definition, spawnLocation)) {
                        activated = true;
                        dispatch(id, "spawnAction", null, null, spawnLocation, null);
                    }
                    if (activated) {
                        activeDefinitions.put(id, definition.deepCopy());
                        savePlayerNpcPosition(id, playerNpcRuntime.location(id));
                    } else {
                        activeDefinitions.remove(id);
                        reportLifecycleWarning("reload Player NPC", id, playerNpcUnavailableReason());
                    }
                    return;
                }
                if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                    Location currentLocation = playerNpcRuntime.location(id);
                    if (playerNpcRuntime.reload(id, definition, false, currentLocation)) {
                        activeDefinitions.put(id, definition.deepCopy());
                        savePlayerNpcPosition(id, playerNpcRuntime.location(id));
                    } else {
                        reportLifecycleWarning("reload Player NPC", id, "Packet runtime rejected the updated definition");
                    }
                    return;
                }
                return;
            }
            if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                Location packetLocation = playerNpcRuntime.location(id);
                playerNpcRuntime.despawn(id);
                if (packetLocation != null) {
                    spawn(id, packetLocation);
                    if (!isActive(id)) {
                        activeDefinitions.remove(id);
                        reportLifecycleWarning("replace Player NPC entity type", id, spawnFailureReason(id));
                    }
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
            if (entity.getType() != nextType) {
                Location spawnLocation = entity.getLocation();
                entity.remove();
                activeNpcs.remove(id);
                spawn(id, spawnLocation);
                if (!isActive(id)) {
                    activeDefinitions.remove(id);
                    reportLifecycleWarning("replace NPC entity type", id, spawnFailureReason(id));
                }
                return;
            }
            applyDefinition(entity, definition);
            activeDefinitions.put(id, definition.deepCopy());
        } catch (RuntimeException exception) {
            reportLifecycleFailure("reload NPC definition", id == null ? "unknown" : id, exception);
        }
    }

    public boolean isActive(String id) {
        Entity entity = activeEntity(id);
        return entity != null && !entity.isDead() || playerNpcRuntime != null && playerNpcRuntime.isActive(id);
    }

    public FlowNpcHandle handle(String id) {
        Entity entity = activeEntity(id);
        if (entity != null && !entity.isDead()) {
            return handle(id, entity.getUniqueId().toString(), false, entity.getLocation());
        }
        Location location = playerNpcRuntime != null ? playerNpcRuntime.location(id) : null;
        return location != null ? handle(id, "", true, location) : null;
    }

    public List<FlowNpcHandle> activeHandles() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(activeNpcs.keySet());
        if (playerNpcRuntime != null) {
            ids.addAll(playerNpcRuntime.activeIds());
        }
        List<FlowNpcHandle> handles = new ArrayList<>();
        for (String id : ids) {
            FlowNpcHandle handle = handle(id);
            if (handle != null) {
                handles.add(handle);
            }
        }
        return List.copyOf(handles);
    }

    public Location location(String id) {
        Entity entity = activeEntity(id);
        if (entity != null && !entity.isDead()) {
            return entity.getLocation();
        }
        return playerNpcRuntime != null ? playerNpcRuntime.location(id) : null;
    }

    public Entity entity(String id) {
        Entity entity = activeEntity(id);
        return entity != null && !entity.isDead() ? entity : null;
    }

    public boolean teleport(String id, Location location) {
        if (id == null || id.isBlank() || location == null || location.getWorld() == null) {
            return false;
        }
        Entity entity = activeEntity(id);
        if (entity != null && !entity.isDead()) {
            return entity.teleport(location);
        }
        Location previousLocation = playerNpcRuntime != null ? playerNpcRuntime.location(id) : null;
        boolean teleported = playerNpcRuntime != null && playerNpcRuntime.teleport(id, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
        if (teleported && !savePlayerNpcPosition(id, location)) {
            if (previousLocation != null && previousLocation.getWorld() != null) {
                playerNpcRuntime.teleport(id, previousLocation.getWorld().getName(), previousLocation.getX(), previousLocation.getY(), previousLocation.getZ(),
                    previousLocation.getYaw(), previousLocation.getPitch());
            }
            reportLifecycleWarning("persist Player NPC teleport", id, "The new NPC position could not be saved");
            return false;
        }
        return teleported;
    }

    public String playerNpcUnavailableReason() {
        if (playerNpcRuntime == null) {
            return "Player NPC runtime unavailable";
        }
        String reason = playerNpcRuntime.unavailableReason();
        return reason == null || reason.isBlank() ? "Player NPC packet runtime failed to create the NPC" : reason;
    }

    public String spawnFailureReason(String id) {
        JsonObject definition = get(id);
        if (definition == null) {
            return "NPC definition does not exist";
        }
        if (!bool(definition, "enabled", true)) {
            return "NPC definition is disabled";
        }
        if (playerEntityType(definition)) {
            return playerNpcUnavailableReason();
        }
        return spawnEntityType(definition) == null ? "NPC entity type is unavailable" : "Server failed to create the NPC entity";
    }

    public boolean playerNpcAvailable() {
        return playerNpcRuntime != null && playerNpcRuntime.available();
    }

    public boolean requiresPlayerRuntime(String id) {
        return playerEntityType(get(id));
    }

    private FlowNpcHandle handle(String id, String entityUuid, boolean packetBacked, Location location) {
        return new FlowNpcHandle(id, entityUuid, packetBacked, true, location.getWorld() != null ? location.getWorld().getName() : "",
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private Entity activeEntity(String id) {
        UUID uuid = activeNpcs.get(id);
        Entity entity = uuid != null && plugin != null ? plugin.getServer().getEntity(uuid) : null;
        if (uuid != null && (entity == null || entity.isDead() || !entity.isValid())) {
            activeNpcs.remove(id, uuid);
            return null;
        }
        return entity;
    }

    private void restorePersistedNpcEntities() {
        if (plugin == null) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                reconcilePersistedEntity(entity);
            }
        }
    }

    private void restorePersistedPlayerNpcs() {
        if (plugin == null || playerNpcInstances == null || playerNpcRuntime == null || !playerNpcRuntime.available()) {
            return;
        }
        for (Map.Entry<String, PlayerNpcInstanceStorage.Position> entry : playerNpcInstances.snapshot().entrySet()) {
            String id = entry.getKey();
            JsonObject definition = get(id);
            if (definition == null || !bool(definition, "enabled", true) || !playerEntityType(definition)) {
                removePlayerNpcPosition(id);
                continue;
            }
            Location location = entry.getValue().resolve(plugin.getServer());
            if (location == null || isActive(id)) {
                continue;
            }
            if (playerNpcRuntime.spawn(id, definition, location)) {
                activeDefinitions.put(id, definition.deepCopy());
            } else {
                reportLifecycleWarning("restore persistent Player NPC", id, playerNpcUnavailableReason());
            }
        }
    }

    private void reconcilePersistedEntity(Entity entity) {
        String id = npcId(entity);
        if (id.isBlank()) {
            return;
        }
        try {
            JsonObject definition = get(id);
            EntityType expectedType = definition != null ? spawnEntityType(definition) : null;
            if (definition == null || !bool(definition, "enabled", true) || expectedType == null || entity.getType() != expectedType) {
                entity.remove();
                activeNpcs.remove(id, entity.getUniqueId());
                return;
            }
            Entity active = activeEntity(id);
            if (active != null && !active.getUniqueId().equals(entity.getUniqueId())) {
                entity.remove();
                return;
            }
            if (playerNpcRuntime != null && playerNpcRuntime.isActive(id)) {
                playerNpcRuntime.despawn(id);
            }
            removePlayerNpcPosition(id);
            activeNpcs.put(id, entity.getUniqueId());
            applyDefinition(entity, definition);
            activeDefinitions.put(id, definition.deepCopy());
        } catch (RuntimeException exception) {
            activeNpcs.remove(id, entity.getUniqueId());
            activeDefinitions.remove(id);
            entity.remove();
            reportLifecycleFailure("restore persisted NPC entity", id, exception);
        }
    }

    private boolean savePlayerNpcPosition(String id, Location location) {
        return playerNpcInstances == null || playerNpcInstances.save(id, location);
    }

    private boolean removePlayerNpcPosition(String id) {
        return playerNpcInstances == null || playerNpcInstances.remove(id);
    }

    private void applyDefinition(Entity entity, JsonObject definition) {
        String displayName = text(definition, "displayName");
        if (!displayName.isBlank()) {
            entity.customName(TextFormatter.parse(displayName));
            entity.setCustomNameVisible(true);
        } else {
            entity.customName(null);
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
            } else if (tradeProfileService != null) {
                tradeProfileService.apply(villager, tradeProfile);
            } else {
                throw new IllegalStateException("Trade profile service is unavailable");
            }
        }
    }

    private void tickFollowPlayers() {
        for (Map.Entry<String, UUID> entry : activeNpcs.entrySet()) {
            try {
                tickFollowPlayer(entry.getKey());
            } catch (RuntimeException exception) {
                reportLifecycleFailure("update NPC follow rotation", entry.getKey(), exception);
            }
        }
    }

    private void tickFollowPlayer(String id) {
        Entity entity = activeEntity(id);
        JsonObject definition = get(id);
        if (entity == null || entity.isDead() || definition == null || !bool(definition, "followPlayer", false)) {
            return;
        }
        Player target = nearestPlayer(entity.getLocation(), decimal(definition, "followRange", 12.0));
        if (target == null) {
            return;
        }
        Location next = entity.getLocation();
        LookAngles angles = lookAt(next, target.getEyeLocation());
        next.setYaw(smoothAngle(next.getYaw(), angles.yaw(), 0.35F));
        next.setPitch(smoothAngle(next.getPitch(), angles.pitch(), 0.35F));
        entity.setRotation(next.getYaw(), next.getPitch());
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
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        String id = npcId(event.getRightClicked());
        if (id.isBlank()) {
            return;
        }
        ReSyncNpcInteractEvent interaction = fireInteractionEvent(id, event.getPlayer(), event.getRightClicked(), event.getRightClicked().getLocation(), false,
            event.getPlayer().isSneaking());
        if (interaction.isCancelled()) {
            event.setCancelled(true);
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", true);
        variables.put("leftClick", false);
        variables.put("shifting", event.getPlayer().isSneaking());
        variables.put("sneaking", event.getPlayer().isSneaking());
        variables.put("shiftClick", event.getPlayer().isSneaking());
        variables.put("success", true);
        variables.put("cancelled", false);
        dispatchInteraction(id, false, event.getPlayer(), event.getRightClicked(), event.getRightClicked().getLocation(), event, variables);
        open(event.getPlayer(), id);
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        Player player = attackingPlayer(event.getDamager());
        boolean shifting = player != null && player.isSneaking();
        boolean directPlayerAttack = event.getDamager() instanceof Player;
        if (directPlayerAttack) {
            ReSyncNpcInteractEvent interaction = fireInteractionEvent(id, player, event.getEntity(), event.getEntity().getLocation(), true, shifting);
            if (interaction.isCancelled()) {
                event.setCancelled(true);
                return;
            }
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", false);
        variables.put("leftClick", directPlayerAttack);
        variables.put("shifting", player != null && player.isSneaking());
        variables.put("sneaking", player != null && player.isSneaking());
        variables.put("shiftClick", player != null && player.isSneaking());
        variables.put("success", true);
        variables.put("damager", event.getDamager());
        variables.put("damage", event.getDamage());
        variables.put("finalDamage", event.getFinalDamage());
        variables.put("damageCause", event.getCause().name());
        JsonObject definition = get(id);
        if (bool(definition, "invulnerable", true)) {
            event.setCancelled(true);
        }
        variables.put("cancelled", event.isCancelled());
        if (directPlayerAttack) {
            dispatchInteraction(id, true, player, event.getEntity(), event.getEntity().getLocation(), event, variables);
        }
        dispatch(id, "damageAction", player, event.getEntity(), event.getEntity().getLocation(), event, variables);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        String id = npcId(event.getEntity());
        if (id.isBlank()) {
            return;
        }
        try {
            JsonObject definition = activeDefinition(id);
            String lootTable = definition != null ? link(definition, "lootTable") : "";
            if (!lootTable.isBlank() && lootTableService != null) {
                event.getDrops().clear();
                event.getDrops().addAll(lootTableService.generate(lootTable,
                    lootTableService.context(event.getEntity().getKiller(), event.getEntity(), event.getEntity().getLocation())));
            }
        } catch (RuntimeException exception) {
            reportLifecycleFailure("generate NPC death loot", id, exception);
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("killer", event.getEntity().getKiller());
            variables.put("drops", List.copyOf(event.getDrops()));
            variables.put("droppedExperience", event.getDroppedExp());
            variables.put("success", true);
            dispatch(id, "deathAction", event.getEntity().getKiller(), event.getEntity(), event.getEntity().getLocation(), event, variables);
        } catch (RuntimeException exception) {
            reportLifecycleFailure("dispatch NPC death action", id, exception);
        } finally {
            activeNpcs.remove(id, event.getEntity().getUniqueId());
            activeDefinitions.remove(id);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            reconcilePersistedEntity(entity);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            String id = npcId(entity);
            if (!id.isBlank()) {
                activeNpcs.remove(id, entity.getUniqueId());
            }
        }
    }

    void dispatch(String id, String hook, Player player, Entity entity, Location location, Event event) {
        dispatch(id, hook, player, entity, location, event, Map.of());
    }

    void dispatch(String id, String hook, Player player, Entity entity, Location location, Event event, Map<String, Object> extraVariables) {
        JsonObject definition = activeDefinition(id);
        JsonObject hooks = definition != null && definition.has("hooks") && definition.get("hooks").isJsonObject() ? definition.getAsJsonObject("hooks") : null;
        if (hooks == null || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = hookVariables(id, player, entity, location);
        FlowNpcHandle handle = entity != null && location != null
            ? handle(id, entity.getUniqueId().toString(), false, location)
            : handle(id);
        variables.put("handle", handle);
        variables.put("event.handle", handle);
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

    void dispatchInteraction(String id, boolean leftClick, Player player, Entity entity, Location location, Event event, Map<String, Object> variables) {
        String primary = leftClick ? "leftClickAction" : "rightClickAction";
        if (hasHook(id, "interactAction")) {
            dispatch(id, "interactAction", player, entity, location, event, variables);
        }
        if (hasHook(id, primary)) {
            dispatch(id, primary, player, entity, location, event, variables);
        }
    }

    private boolean hasHook(String id, String hook) {
        JsonObject definition = activeDefinition(id);
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
        ReSyncNpcInteractEvent interaction = fireInteractionEvent(id, player, null, location, leftClick, shifting);
        if (interaction.isCancelled()) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("rightClick", !leftClick);
        variables.put("leftClick", leftClick);
        variables.put("shifting", shifting);
        variables.put("sneaking", shifting);
        variables.put("shiftClick", shifting);
        variables.put("success", true);
        variables.put("cancelled", false);
        dispatchInteraction(id, leftClick, player, null, location, null, variables);
        if (!leftClick) {
            open(player, id);
        }
    }

    static Map<String, Object> hookVariables(String id, Player player, Entity entity, Location location) {
        Map<String, Object> variables = new HashMap<>();
        FlowNpcHandle handle = null;
        variables.put("npcId", id);
        variables.put("player", player);
        variables.put("entity", entity);
        variables.put("location", location);
        variables.put("handle", handle);
        variables.put("event.id", id);
        variables.put("event.npcId", id);
        variables.put("event.player", player);
        variables.put("event.entity", entity);
        variables.put("event.target", entity);
        variables.put("event.location", location);
        variables.put("event.handle", handle);
        return variables;
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
    }

    private void reportLifecycleFailure(String action, String id, RuntimeException exception) {
        String detail = exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
        String signature = action + '|' + id + '|' + exception.getClass().getName() + '|' + detail;
        if (reportedLifecycleFailures.add(signature)) {
            Log.warn("Failed to " + action + " " + id + ": " + detail, exception);
        }
    }

    private void reportLifecycleWarning(String action, String id, String detail) {
        String signature = action + '|' + id + '|' + detail;
        if (reportedLifecycleFailures.add(signature)) {
            Log.warn("Failed to " + action + " " + id + ": " + detail);
        }
    }

    private ReSyncNpcInteractEvent fireInteractionEvent(String id, Player player, Entity entity, Location location, boolean leftClick, boolean shifting) {
        ReSyncNpcInteractEvent event = new ReSyncNpcInteractEvent(handle(id), player, entity, location, leftClick, shifting);
        if (plugin != null) {
            plugin.getServer().getPluginManager().callEvent(event);
        }
        return event;
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

    private JsonObject activeDefinition(String id) {
        JsonObject definition = get(id);
        return definition != null ? definition : activeDefinitions.get(id);
    }

    private String npcId(Entity entity) {
        if (entity == null) {
            return "";
        }
        String id = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        return id != null ? id : "";
    }

    private EntityType entityType(String value) {
        if (value == null || value.isBlank()) return EntityType.VILLAGER;
        try {
            return EntityType.valueOf(enumName(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown NPC entity type: " + value, exception);
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
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("NPC definition field must be a boolean: " + key, exception);
        }
    }

    private double decimal(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        double value;
        try {
            value = object.get(key).getAsDouble();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("NPC definition field must be a number: " + key, exception);
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException("NPC definition field must be finite: " + key);
        return value;
    }

    private record LookAngles(float yaw, float pitch) {
    }
}
