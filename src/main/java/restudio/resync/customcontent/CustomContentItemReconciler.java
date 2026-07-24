package restudio.resync.customcontent;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.ReSync;
import restudio.resync.world.WorldManagementManager;
import restudio.resync.world.WorldManagementService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

class CustomContentItemReconciler {
    private final CustomContentStorage storage;
    private final CustomContentService service;
    private final CustomContentOfflinePlayerDataReconciler offlinePlayerDataReconciler;

    CustomContentItemReconciler(CustomContentStorage storage, CustomContentService service) {
        this.storage = storage;
        this.service = service;
        this.offlinePlayerDataReconciler = new CustomContentOfflinePlayerDataReconciler(this);
    }

    void reconcileContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return;
        }
        runSync(() -> reconcileLoaded(contentId, false));
        reconcileWorldPlayerStates(contentId, false);
        offlinePlayerDataReconciler.reconcileAsync(contentId, false);
    }

    void reconcileAll() {
        runSync(() -> reconcileLoaded(null, false));
        reconcileWorldPlayerStates(null, false);
        offlinePlayerDataReconciler.reconcileAsync(null, false);
    }

    void clearContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return;
        }
        runSync(() -> reconcileLoaded(contentId, true));
        reconcileWorldPlayerStates(contentId, true);
        offlinePlayerDataReconciler.reconcileAsync(contentId, true);
    }

    void reconcilePlayer(Player player) {
        if (player == null) {
            return;
        }
        runSync(() -> reconcilePlayerNow(player, null, false));
    }

    void reconcileInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        runSync(() -> reconcileInventoryNow(inventory, null, false));
    }

    void reconcileChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        runSync(() -> reconcileChunkNow(chunk, null, false));
    }

    private void reconcileLoaded(String contentId, boolean clearDeleted) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            reconcilePlayerNow(player, contentId, clearDeleted);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                reconcileChunkNow(chunk, contentId, clearDeleted);
            }
        }
    }

    private void reconcileChunkNow(Chunk chunk, String contentId, boolean clearDeleted) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Container container && reconcileInventoryNow(container.getInventory(), contentId, clearDeleted)) {
                container.update(true, false);
            }
        }
        for (Entity entity : chunk.getEntities()) {
            reconcileEntity(entity, contentId, clearDeleted);
        }
    }

    private void reconcilePlayerNow(Player player, String contentId, boolean clearDeleted) {
        PlayerInventory inventory = player.getInventory();
        reconcileInventoryNow(inventory, contentId, clearDeleted);
        reconcileInventoryNow(player.getEnderChest(), contentId, clearDeleted);
        ItemStack cursor = player.getItemOnCursor();
        ItemStack updatedCursor = transformItem(cursor, contentId, clearDeleted);
        if (updatedCursor != cursor) {
            player.setItemOnCursor(updatedCursor);
        }
        if (player.getOpenInventory() != null) {
            reconcileInventoryNow(player.getOpenInventory().getTopInventory(), contentId, clearDeleted);
        }
        player.updateInventory();
    }

    private void reconcileEntity(Entity entity, String contentId, boolean clearDeleted) {
        switch (entity) {
            case Player player -> reconcilePlayerNow(player, contentId, clearDeleted);
            case Item item -> {
                ItemStack current = item.getItemStack();
                ItemStack updated = transformItem(current, contentId, clearDeleted);
                if (updated != current) {
                    item.setItemStack(updated != null ? updated : new ItemStack(Material.AIR));
                }
            }
            case ItemFrame itemFrame -> {
                ItemStack current = itemFrame.getItem();
                ItemStack updated = transformItem(current, contentId, clearDeleted);
                if (updated != current) {
                    itemFrame.setItem(updated != null ? updated : new ItemStack(Material.AIR));
                }
            }
            case ItemDisplay itemDisplay -> {
                ItemStack current = itemDisplay.getItemStack();
                ItemStack updated = transformItem(current, contentId, clearDeleted);
                if (updated != current) {
                    itemDisplay.setItemStack(updated);
                }
            }
            case LivingEntity living -> reconcileEquipment(living.getEquipment(), contentId, clearDeleted);
            default -> {
            }
        }
        if (entity instanceof InventoryHolder holder) {
            reconcileInventoryNow(holder.getInventory(), contentId, clearDeleted);
        }
    }

    private void reconcileEquipment(EntityEquipment equipment, String contentId, boolean clearDeleted) {
        if (equipment == null) {
            return;
        }
        ItemStack mainHand = equipment.getItemInMainHand();
        ItemStack updatedMainHand = transformItem(mainHand, contentId, clearDeleted);
        if (updatedMainHand != mainHand) {
            equipment.setItemInMainHand(updatedMainHand);
        }
        ItemStack offHand = equipment.getItemInOffHand();
        ItemStack updatedOffHand = transformItem(offHand, contentId, clearDeleted);
        if (updatedOffHand != offHand) {
            equipment.setItemInOffHand(updatedOffHand);
        }
        ItemStack helmet = equipment.getHelmet();
        ItemStack updatedHelmet = transformItem(helmet, contentId, clearDeleted);
        if (updatedHelmet != helmet) {
            equipment.setHelmet(updatedHelmet);
        }
        ItemStack chestplate = equipment.getChestplate();
        ItemStack updatedChestplate = transformItem(chestplate, contentId, clearDeleted);
        if (updatedChestplate != chestplate) {
            equipment.setChestplate(updatedChestplate);
        }
        ItemStack leggings = equipment.getLeggings();
        ItemStack updatedLeggings = transformItem(leggings, contentId, clearDeleted);
        if (updatedLeggings != leggings) {
            equipment.setLeggings(updatedLeggings);
        }
        ItemStack boots = equipment.getBoots();
        ItemStack updatedBoots = transformItem(boots, contentId, clearDeleted);
        if (updatedBoots != boots) {
            equipment.setBoots(updatedBoots);
        }
    }

    private boolean reconcileInventoryNow(Inventory inventory, String contentId, boolean clearDeleted) {
        if (inventory == null) {
            return false;
        }
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            ItemStack updated = transformItem(current, contentId, clearDeleted);
            if (updated != current) {
                inventory.setItem(slot, updated);
                changed = true;
            }
        }
        return changed;
    }

    ItemStack transformItem(ItemStack item, String contentId, boolean clearDeleted) {
        if (item == null || item.getType().isAir()) {
            return item;
        }
        String stampedId = service.getVanillaProvider().getStampedContentId(item);
        if (stampedId.isBlank()) {
            String identifiedId = service.identifyItem(item);
            if (identifiedId != null && !identifiedId.isBlank()) {
                stampedId = identifiedId;
            }
        }
        boolean matchesTarget = contentId == null || contentId.isBlank() || contentId.equalsIgnoreCase(stampedId);
        ItemStack working = item.clone();
        boolean nestedChanged = reconcileNestedItemState(working, contentId, clearDeleted);
        if (!matchesTarget || stampedId.isBlank()) {
            return nestedChanged ? working : item;
        }
        CustomContentDefinition definition = storage.get(stampedId);
        if (clearDeleted) {
            return service.getVanillaProvider().clearStamp(working);
        }
        if (definition == null || !isItemBacked(definition)) {
            return service.getVanillaProvider().clearStamp(working);
        }
        if (!service.canCreateAuthoritativeItem(definition)) {
            return nestedChanged ? working : item;
        }
        int amount = Math.max(1, item.getAmount());
        ItemStack replacement = service.createItem(stampedId, amount);
        if (replacement == null || replacement.getType().isAir()) {
            return nestedChanged ? working : item;
        }
        replacement.setAmount(amount);
        transferNestedItemState(working, replacement);
        return service.getVanillaProvider().restampItem(replacement, definition, service.getVanillaProvider().getInstanceId(item));
    }

    private boolean reconcileNestedItemState(ItemStack item, String contentId, boolean clearDeleted) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        boolean changed = false;
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof Container container) {
            if (reconcileInventoryNow(container.getInventory(), contentId, clearDeleted)) {
                blockStateMeta.setBlockState(container);
                changed = true;
            }
        }
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = new ArrayList<>(bundleMeta.getItems());
            if (reconcileList(items, contentId, clearDeleted)) {
                bundleMeta.setItems(items);
                changed = true;
            }
        }
        if (meta instanceof CrossbowMeta crossbowMeta && crossbowMeta.hasChargedProjectiles()) {
            List<ItemStack> projectiles = new ArrayList<>(crossbowMeta.getChargedProjectiles());
            if (reconcileList(projectiles, contentId, clearDeleted)) {
                crossbowMeta.setChargedProjectiles(projectiles);
                changed = true;
            }
        }
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    private boolean reconcileList(List<ItemStack> items, String contentId, boolean clearDeleted) {
        boolean changed = false;
        for (int index = 0; index < items.size(); index++) {
            ItemStack current = items.get(index);
            ItemStack updated = transformItem(current, contentId, clearDeleted);
            if (updated != current) {
                items.set(index, updated);
                changed = true;
            }
        }
        return changed;
    }

    private void transferNestedItemState(ItemStack source, ItemStack target) {
        ItemMeta sourceMeta = source.getItemMeta();
        ItemMeta targetMeta = target.getItemMeta();
        if (sourceMeta == null || targetMeta == null) {
            return;
        }
        boolean changed = false;
        if (sourceMeta instanceof BlockStateMeta sourceBlockStateMeta && targetMeta instanceof BlockStateMeta targetBlockStateMeta) {
            targetBlockStateMeta.setBlockState(sourceBlockStateMeta.getBlockState());
            changed = true;
        }
        if (sourceMeta instanceof BundleMeta sourceBundleMeta && targetMeta instanceof BundleMeta targetBundleMeta) {
            targetBundleMeta.setItems(new ArrayList<>(sourceBundleMeta.getItems()));
            changed = true;
        }
        if (sourceMeta instanceof CrossbowMeta sourceCrossbowMeta && targetMeta instanceof CrossbowMeta targetCrossbowMeta) {
            targetCrossbowMeta.setChargedProjectiles(new ArrayList<>(sourceCrossbowMeta.getChargedProjectiles()));
            changed = true;
        }
        if (changed) {
            target.setItemMeta(targetMeta);
        }
    }

    private boolean isItemBacked(CustomContentDefinition definition) {
        String type = definition.getType() != null ? definition.getType().toLowerCase(Locale.ROOT) : "";
        return type.equals("item") || type.equals("armor") || type.equals("block") || type.equals("projectile");
    }

    private void reconcileWorldPlayerStates(String contentId, boolean clearDeleted) {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) {
            return;
        }
        WorldManagementService service = plugin.getReSyncServer().getWorldManagementService();
        if (service instanceof WorldManagementManager manager) {
            UnaryOperator<ItemStack> transformer = item -> transformItem(item, contentId, clearDeleted);
            manager.reconcileStoredItems(transformer);
        }
    }

    private void runSync(Runnable action) {
        JavaPlugin plugin = ReSync.getInstance();
        if (plugin == null || Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("ItemReconcileFailed", exception);
        }
    }
}
