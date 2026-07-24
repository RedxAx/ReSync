package restudio.resync.customcontent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import restudio.resync.ReSync;
import restudio.flow.data.CustomContentDefinition;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentListener implements Listener {
    private static final ThreadLocal<Integer> SUPPRESSED_DAMAGE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final NamespacedKey PROJECTILE_CONTENT_KEY = new NamespacedKey("resync", "projectile_content_id");
    private final CustomContentStorage storage;
    private final CustomContentService service;
    private final Map<UUID, String[]> armorSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, String> fullSetSnapshots = new ConcurrentHashMap<>();

    public CustomContentListener(CustomContentStorage storage, CustomContentService service) {
        this.storage = storage;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem() != null ? event.getItem() : itemInHand(player, event.getHand());
        String itemId = service.identifyItem(item);
        if (itemId != null) {
            CustomContentDefinition definition = storage.get(itemId);
            if (!isBlockPlacementAttempt(definition, event)) {
                Map<String, Object> vars = baseVars(player, item, null, null, event.getHand());
                Action action = event.getAction();
                String trigger = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK ? "item.left_click" : "item.right_click";
                service.dispatch(itemId, trigger, player, event, vars);
                service.dispatch(itemId, "item.use", player, event, vars);
                if (definition != null && "projectile".equalsIgnoreCase(definition.getType())
                    && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                    && !event.isCancelled() && allowsProjectileSource(definition, "Item Use") && !isBowLike(item)) {
                    Projectile projectile = launchConfiguredProjectile(player, definition);
                    markProjectile(projectile, definition, player, item, event, event.getHand());
                    if (projectileFlag(definition, "consume_item", true)) {
                        consumeOne(player, event.getHand(), item);
                    }
                    event.setCancelled(true);
                }
            }
        }
        if (event.getClickedBlock() != null) {
            Location location = event.getClickedBlock().getLocation();
            String blockId = service.identifyBlock(location);
            if (blockId != null) {
                service.dispatch(blockId, "block.interact", player, event, baseVars(player, item, location, null, event.getHand()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (isDamageAbilitySuppressed()) {
            return;
        }
        Player player = event.getDamager() instanceof Player p ? p : null;
        if (player != null) {
            ItemStack item = player.getInventory().getItemInMainHand();
            String itemId = service.identifyItem(item);
            if (itemId != null) {
                Map<String, Object> vars = baseVars(player, item, event.getEntity().getLocation(), event.getEntity(), EquipmentSlot.HAND);
                vars.put("event.damage", event.getDamage());
                service.dispatch(itemId, "item.hit_entity", player, event, vars);
                service.dispatch(itemId, "item.damage_entity", player, event, vars);
            }
        }
        if (event.getEntity() instanceof Player damaged) {
            for (ItemStack armor : damaged.getInventory().getArmorContents()) {
                String armorId = service.identifyItem(armor);
                if (armorId != null) {
                    Map<String, Object> vars = baseVars(damaged, armor, damaged.getLocation(), event.getDamager(), null);
                    vars.put("event.damage", event.getDamage());
                    service.dispatch(armorId, "armor.damaged", damaged, event, vars);
                }
            }
        }
    }

    public static void runSuppressingDamageAbilities(Runnable action) {
        int depth = SUPPRESSED_DAMAGE_DEPTH.get();
        SUPPRESSED_DAMAGE_DEPTH.set(depth + 1);
        try {
            action.run();
        } finally {
            if (depth == 0) {
                SUPPRESSED_DAMAGE_DEPTH.remove();
            } else {
                SUPPRESSED_DAMAGE_DEPTH.set(depth);
            }
        }
    }

    public static boolean isDamageAbilitySuppressed() {
        return SUPPRESSED_DAMAGE_DEPTH.get() > 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String contentId = service.identifyItem(item);
        if (contentId != null) {
            CustomContentDefinition definition = storage.get(contentId);
            if (definition != null && "block".equalsIgnoreCase(definition.getType())) {
                Location location = event.getBlockPlaced().getLocation();
                service.markPlacedBlock(location, definition);
                service.dispatch(contentId, "block.place", event.getPlayer(), event, baseVars(event.getPlayer(), item, location, null, event.getHand()));
            } else {
                Map<String, Object> vars = baseVars(event.getPlayer(), item, event.getBlockPlaced().getLocation(), null, event.getHand());
                service.dispatch(contentId, "item.break_block", event.getPlayer(), event, vars);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        String blockId = service.identifyBlock(location);
        if (blockId != null) {
            service.dispatch(blockId, "block.break", event.getPlayer(), event, baseVars(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), location, null, EquipmentSlot.HAND));
            service.clearPlacedBlock(location);
        }
        String itemId = service.identifyItem(event.getPlayer().getInventory().getItemInMainHand());
        if (itemId != null) {
            service.dispatch(itemId, "item.break_block", event.getPlayer(), event, baseVars(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), location, null, EquipmentSlot.HAND));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockY() == event.getTo().getBlockY() && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Location below = event.getTo().clone().subtract(0, 1, 0);
        String blockId = service.identifyBlock(below);
        if (blockId != null) {
            service.dispatch(blockId, "block.step_on", event.getPlayer(), event, baseVars(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), below, null, EquipmentSlot.HAND));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRedstone(BlockRedstoneEvent event) {
        Location location = event.getBlock().getLocation();
        String blockId = service.identifyBlock(location);
        if (blockId != null) {
            Map<String, Object> vars = baseVars(null, null, location, null, null);
            vars.put("event.old_current", event.getOldCurrent());
            vars.put("event.new_current", event.getNewCurrent());
            service.dispatch(blockId, "block.redstone", null, event, vars);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        String contentId = service.identifyItem(event.getItem());
        if (contentId != null) {
            service.dispatch(contentId, "item.consume", event.getPlayer(), event, baseVars(event.getPlayer(), event.getItem(), event.getPlayer().getLocation(), null, null));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        String contentId = service.identifyItem(item);
        if (contentId != null) {
            service.dispatch(contentId, "item.drop", event.getPlayer(), event, baseVars(event.getPlayer(), item, event.getItemDrop().getLocation(), event.getItemDrop(), null));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        String contentId = service.identifyItem(item);
        if (contentId != null) {
            service.dispatch(contentId, "item.pickup", player, event, baseVars(player, item, event.getItem().getLocation(), event.getItem(), null));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getProjectile() instanceof Projectile projectile)) {
            return;
        }
        ItemStack consumable = event.getConsumable();
        CustomContentDefinition definition = projectileDefinition(consumable);
        if (definition == null || !allowsProjectileSource(definition, "Bow Ammo")) {
            return;
        }
        markProjectile(projectile, definition, player, consumable, event, event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getPersistentDataContainer().has(PROJECTILE_CONTENT_KEY, PersistentDataType.STRING) || !(projectile.getShooter() instanceof Player player)) {
            return;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        CustomContentDefinition definition = projectileDefinitionForLaunch(projectile, mainHand, offhand);
        if (definition == null || !allowsProjectileSource(definition, "Item Use")) {
            return;
        }
        boolean mainHandSource = definitionFromStack(definition, mainHand);
        markProjectile(projectile, definition, player, mainHandSource ? mainHand : offhand, event, mainHandSource ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String contentId = projectile.getPersistentDataContainer().get(PROJECTILE_CONTENT_KEY, PersistentDataType.STRING);
        if (contentId == null || contentId.isBlank()) {
            return;
        }
        CustomContentDefinition definition = storage.get(contentId);
        if (definition == null) {
            return;
        }
        Player player = projectile.getShooter() instanceof Player shooter ? shooter : null;
        ItemStack item = service.createItem(contentId, 1);
        Map<String, Object> vars = baseVars(player, item, projectile.getLocation(), event.getHitEntity(), null);
        vars.put("event.projectile", projectile);
        vars.put("event.projectile_type", projectile.getType().name());
        vars.put("event.velocity", projectile.getVelocity());
        vars.put("event.block", event.getHitBlock());
        vars.put("event.hit_block", event.getHitBlock());
        vars.put("event.hit_face", event.getHitBlockFace());
        service.dispatch(contentId, "projectile.hit", player, event, vars);
        playProjectileSound(definition, projectile.getLocation(), "hit_sound");
        if (projectileFlag(definition, "remove_on_hit", false)) {
            projectile.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> scanArmor(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        service.reconcilePlayerItems(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        service.reconcileInventoryItems(event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            service.reconcilePlayerItems(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        service.reconcileChunkItems(event.getChunk());
    }

    public void tick() {
        tickBlocks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanArmor(player);
            tickHeldItem(player, player.getInventory().getItemInMainHand(), EquipmentSlot.HAND);
            tickHeldItem(player, player.getInventory().getItemInOffHand(), EquipmentSlot.OFF_HAND);
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                String contentId = service.identifyItem(armor);
                if (contentId != null) {
                    Map<String, Object> vars = baseVars(player, armor, player.getLocation(), null, null);
                    service.dispatch(contentId, "armor.tick", player, null, vars);
                    service.dispatch(contentId, "armor.while_holding", player, null, vars);
                }
            }
            String fullSet = fullSetKey(player);
            String previousFullSet = fullSetSnapshots.put(player.getUniqueId(), fullSet);
            if (!fullSet.isBlank()) {
                for (ItemStack armor : player.getInventory().getArmorContents()) {
                    String contentId = service.identifyItem(armor);
                    if (contentId != null) {
                        if (!fullSet.equals(previousFullSet)) {
                            service.dispatch(contentId, "armor.full_set", player, null, baseVars(player, armor, player.getLocation(), null, null));
                        }
                        service.dispatch(contentId, "armor.full_set_tick", player, null, baseVars(player, armor, player.getLocation(), null, null));
                    }
                }
            }
        }
    }

    private void tickHeldItem(Player player, ItemStack item, EquipmentSlot hand) {
        String contentId = service.identifyItem(item);
        if (contentId != null) {
            service.dispatch(contentId, "item.while_holding", player, null, baseVars(player, item, player.getLocation(), null, hand));
        }
    }

    private void scanArmor(Player player) {
        String[] previous = armorSnapshots.get(player.getUniqueId());
        String[] current = armorSlots(player);
        if (sameArmor(previous, current)) {
            return;
        }
        armorSnapshots.put(player.getUniqueId(), current);
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        for (int i = 0; i < armorContents.length; i++) {
            String before = previous != null && i < previous.length ? previous[i] : "";
            String after = current[i];
            if (!before.isBlank() && !before.equals(after)) {
                service.dispatch(before, "armor.unequip", player, null, baseVars(player, null, player.getLocation(), null, null));
            }
            if (!after.isBlank() && !after.equals(before)) {
                service.dispatch(after, "armor.equip", player, null, baseVars(player, armorContents[i], player.getLocation(), null, null));
            }
        }
    }

    private void tickBlocks() {
        for (Map.Entry<String, String> entry : service.getVanillaProvider().getPlacedBlocks().entrySet()) {
            Location location = locationFromKey(entry.getKey());
            if (location == null) {
                continue;
            }
            String contentId = entry.getValue();
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Player player : location.getWorld().getPlayers()) {
                double distance = player.getLocation().distanceSquared(location);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = player;
                }
                if (distance <= 9.0) {
                    service.dispatch(contentId, "block.nearby_player", player, null, baseVars(player, player.getInventory().getItemInMainHand(), location, player, null));
                }
            }
            service.dispatch(contentId, "block.tick", nearest, null, baseVars(nearest, nearest != null ? nearest.getInventory().getItemInMainHand() : null, location, nearest, null));
        }
    }

    private Location locationFromKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String[] armorSlots(Player player) {
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        String[] slots = new String[armorContents.length];
        for (int i = 0; i < armorContents.length; i++) {
            String contentId = service.identifyItem(armorContents[i]);
            slots[i] = contentId != null ? contentId : "";
        }
        return slots;
    }

    private boolean sameArmor(String[] previous, String[] current) {
        if (previous == null || current == null || previous.length != current.length) {
            return false;
        }
        for (int i = 0; i < previous.length; i++) {
            if (!previous[i].equals(current[i])) {
                return false;
            }
        }
        return true;
    }

    private String fullSetKey(Player player) {
        String[] slots = armorSlots(player);
        if (slots.length < 4) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String slot : slots) {
            if (slot == null || slot.isBlank()) {
                return "";
            }
            builder.append(slot).append('|');
        }
        return builder.toString();
    }

    private Map<String, Object> baseVars(Player player, ItemStack item, Location location, Entity target, EquipmentSlot hand) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("event.player", player);
        vars.put("event.item", item);
        vars.put("event.location", location != null ? location : player != null ? player.getLocation() : null);
        vars.put("event.block", location != null ? location.getBlock() : null);
        vars.put("event.target", target);
        vars.put("event.hand", hand != null ? hand.name().toLowerCase() : "any");
        vars.put("event.instance_id", service.getVanillaProvider().getInstanceId(item));
        if (target instanceof LivingEntity) {
            vars.put("event.target_living", true);
        }
        return vars;
    }

    private ItemStack itemInHand(Player player, EquipmentSlot hand) {
        if (player == null) {
            return null;
        }
        return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }

    private boolean isBlockPlacementAttempt(CustomContentDefinition definition, PlayerInteractEvent event) {
        return definition != null && "block".equalsIgnoreCase(definition.getType()) && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null;
    }

    private CustomContentDefinition projectileDefinitionForLaunch(Projectile projectile, ItemStack hand, ItemStack offhand) {
        CustomContentDefinition handDef = projectileDefinition(hand);
        CustomContentDefinition offhandDef = projectileDefinition(offhand);
        if (isMatchingProjectileDefinition(projectile, handDef)) {
            return handDef;
        }
        return isMatchingProjectileDefinition(projectile, offhandDef) ? offhandDef : null;
    }

    private CustomContentDefinition projectileDefinition(ItemStack item) {
        String contentId = service.identifyItem(item);
        CustomContentDefinition definition = contentId != null ? storage.get(contentId) : null;
        return definition != null && "projectile".equalsIgnoreCase(definition.getType()) ? definition : null;
    }

    private boolean isMatchingProjectileDefinition(Projectile projectile, CustomContentDefinition definition) {
        return definition != null && projectileType(definition).equalsIgnoreCase(projectile.getType().name());
    }

    private boolean definitionFromStack(CustomContentDefinition definition, ItemStack stack) {
        if (definition == null || stack == null) {
            return false;
        }
        String id = service.identifyItem(stack);
        return id != null && id.equalsIgnoreCase(definition.getId());
    }

    private void markProjectile(Projectile projectile, CustomContentDefinition definition, Player player, ItemStack item, Event event, EquipmentSlot hand) {
        if (projectile.getPersistentDataContainer().has(PROJECTILE_CONTENT_KEY, PersistentDataType.STRING)) {
            return;
        }
        projectile.getPersistentDataContainer().set(PROJECTILE_CONTENT_KEY, PersistentDataType.STRING, definition.getId());
        projectile.setGravity(projectileFlag(definition, "gravity", true));
        projectile.setGlowing(projectileFlag(definition, "glowing", false));
        if (projectile instanceof AbstractArrow arrow) {
            double damage = projectileNumber(definition, "damage", 0.0);
            if (damage > 0.0) {
                arrow.setDamage(damage);
            }
            String pickup = projectileText(definition, "pickup", "Allowed").replace(' ', '_').toUpperCase(Locale.ROOT);
            arrow.setPickupStatus(switch (pickup) {
                case "DISALLOWED" -> AbstractArrow.PickupStatus.DISALLOWED;
                case "CREATIVE_ONLY" -> AbstractArrow.PickupStatus.CREATIVE_ONLY;
                default -> AbstractArrow.PickupStatus.ALLOWED;
            });
        }
        Map<String, Object> vars = baseVars(player, item, projectile.getLocation(), null, hand);
        vars.put("event.projectile", projectile);
        vars.put("event.projectile_type", projectile.getType().name());
        vars.put("event.velocity", projectile.getVelocity());
        service.dispatch(definition.getId(), "projectile.fire", player, event, vars);
        playProjectileSound(definition, projectile.getLocation(), "fire_sound");
    }

    private Projectile launchConfiguredProjectile(Player player, CustomContentDefinition definition) {
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(projectileType(definition));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown projectile type: " + projectileType(definition), exception);
        }
        World world = player.getWorld();
        Entity entity = world.spawnEntity(player.getEyeLocation(), entityType);
        if (!(entity instanceof Projectile projectile)) {
            entity.remove();
            throw new IllegalArgumentException("Entity type is not a projectile: " + entityType.name());
        }
        projectile.setShooter(player);
        double speed = Math.max(0.05, projectileNumber(definition, "speed", 2.4));
        projectile.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(speed));
        return projectile;
    }

    private String projectileType(CustomContentDefinition definition) {
        String type = projectileText(definition, "entity_type", "ARROW").replace(' ', '_').toUpperCase(Locale.ROOT);
        return "ENDERPEARL".equals(type) ? "ENDER_PEARL" : type;
    }

    private boolean allowsProjectileSource(CustomContentDefinition definition, String source) {
        String configured = projectileText(definition, "launch_source", "Automatic");
        return "Automatic".equalsIgnoreCase(configured) || "Both".equalsIgnoreCase(configured) || source.equalsIgnoreCase(configured);
    }

    private void consumeOne(Player player, EquipmentSlot hand, ItemStack item) {
        if (player == null || item == null || item.getAmount() <= 0) {
            return;
        }
        int nextAmount = item.getAmount() - 1;
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(nextAmount > 0 ? copyWithAmount(item, nextAmount) : null);
            return;
        }
        player.getInventory().setItemInMainHand(nextAmount > 0 ? copyWithAmount(item, nextAmount) : null);
    }

    private ItemStack copyWithAmount(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    private boolean isBowLike(ItemStack item) {
        if (item == null || item.getType() == null) {
            return false;
        }
        String material = item.getType().name();
        return material.contains("BOW") || material.contains("CROSSBOW");
    }

    private void playProjectileSound(CustomContentDefinition definition, Location location, String key) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        String sound = projectileText(definition, key, "");
        if (sound.isBlank()) {
            return;
        }
        float volume = (float) Math.clamp(projectileNumber(definition, "sound_volume", 1.0), 0.0, 16.0);
        float pitch = (float) Math.clamp(projectileNumber(definition, "sound_pitch", 1.0), 0.5, 2.0);
        location.getWorld().playSound(location, sound.toLowerCase(Locale.ROOT), volume, pitch);
    }

    private String projectileText(CustomContentDefinition definition, String key, String fallback) {
        Object value = projectileConfiguration(definition, key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private boolean projectileFlag(CustomContentDefinition definition, String key, boolean fallback) {
        Object value = projectileConfiguration(definition, key);
        return value instanceof Boolean flag ? flag : value != null ? Boolean.parseBoolean(value.toString()) : fallback;
    }

    private double projectileNumber(CustomContentDefinition definition, String key, double fallback) {
        Object value = projectileConfiguration(definition, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Object projectileConfiguration(CustomContentDefinition definition, String key) {
        if (definition == null || definition.getGraph() == null || definition.getGraph().getContentProperties() == null) {
            return null;
        }
        return definition.getGraph().getContentProperties().get("projectile." + key);
    }
}
