package restudio.resync.customcontent;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.World;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import restudio.resync.ReSync;
import restudio.flow.data.CustomContentDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentListener implements Listener {
    private static final ThreadLocal<Integer> SUPPRESSED_DAMAGE_DEPTH = ThreadLocal.withInitial(() -> 0);
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
}
