package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.ReSync;
import restudio.resync.flow.GuiManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MenuNodes implements NodeCategory {

    private static final Map<String, MenuData> menus = new HashMap<>();
    private static final Map<Player, String> openMenus = new HashMap<>();

    private static class MenuData {
        String title;
        int rows;
        Map<Integer, MenuItemData> items = new HashMap<>();
        Sound clickSound = Sound.UI_BUTTON_CLICK;
    }

    private static class MenuItemData {
        ItemStack item;
        String flowId;
    }

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("menu_create", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String title = ctx.getInputValue(node, "title", String.class, "Menu");
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            
            if (!menuId.isEmpty() && rows >= 1 && rows <= 6) {
                MenuData menu = new MenuData();
                menu.title = title;
                menu.rows = rows;
                menus.put(menuId, menu);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_item", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String name = ctx.getInputValue(node, "name", String.class, "");
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_to_execute", String.class, "");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null && slot >= 0 && slot < menu.rows * 9) {
                    ItemStack item = new ItemStack(material);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        if (!name.isEmpty()) {
                            meta.displayName(TextFormatter.parse(name));
                        }
                        if (!lore.isEmpty()) {
                            meta.lore(TextFormatter.parseLines(lore));
                        }
                        item.setItemMeta(meta);
                    }
                    MenuItemData menuItem = new MenuItemData();
                    menuItem.item = item;
                    menuItem.flowId = flowId;
                    menu.items.put(slot, menuItem);
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_add_item", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String name = ctx.getInputValue(node, "name", String.class, "");
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_to_execute", String.class, "");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    ItemStack item = new ItemStack(material);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        if (!name.isEmpty()) {
                            meta.displayName(TextFormatter.parse(name));
                        }
                        if (!lore.isEmpty()) {
                            meta.lore(TextFormatter.parseLines(lore));
                        }
                        item.setItemMeta(meta);
                    }
                    int slot = -1;
                    for (int i = 0; i < menu.rows * 9; i++) {
                        if (!menu.items.containsKey(i)) {
                            slot = i;
                            break;
                        }
                    }
                    if (slot >= 0) {
                        MenuItemData menuItem = new MenuItemData();
                        menuItem.item = item;
                        menuItem.flowId = flowId;
                        menu.items.put(slot, menuItem);
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_clear", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.items.clear();
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_open", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            
            if (player != null && !menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (Bukkit.isPrimaryThread()) {
                    Inventory inv = Bukkit.createInventory(null, menu.rows * 9, TextFormatter.parse(menu.title));
                    for (Map.Entry<Integer, MenuItemData> entry : menu.items.entrySet()) {
                        inv.setItem(entry.getKey(), entry.getValue().item);
                    }
                    player.openInventory(inv);
                    openMenus.put(player, menuId);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        Inventory inv = Bukkit.createInventory(null, menu.rows * 9, TextFormatter.parse(menu.title));
                        for (Map.Entry<Integer, MenuItemData> entry : menu.items.entrySet()) {
                            inv.setItem(entry.getKey(), entry.getValue().item);
                        }
                        player.openInventory(inv);
                        openMenus.put(player, menuId);
                    });
                }
            } else if (player != null && !menuId.isEmpty()) {
                ReSyncServer server = ReSync.getInstance() != null ? ReSync.getInstance().getV2Server() : null;
                GuiManager guiManager = server != null ? server.getGuiManager() : null;
                if (guiManager != null) {
                    if (Bukkit.isPrimaryThread()) {
                        guiManager.openGui(player, menuId);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> guiManager.openGui(player, menuId));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_update", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            
            if (player != null && !menuId.isEmpty() && menus.containsKey(menuId) && openMenus.containsKey(player)) {
                MenuData menu = menus.get(menuId);
                if (openMenus.get(player).equals(menuId)) {
                    if (Bukkit.isPrimaryThread()) {
                        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                            Inventory inv = player.getOpenInventory().getTopInventory();
                            inv.clear();
                            for (Map.Entry<Integer, MenuItemData> entry : menu.items.entrySet()) {
                                inv.setItem(entry.getKey(), entry.getValue().item);
                            }
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                                Inventory inv = player.getOpenInventory().getTopInventory();
                                inv.clear();
                                for (Map.Entry<Integer, MenuItemData> entry : menu.items.entrySet()) {
                                    inv.setItem(entry.getKey(), entry.getValue().item);
                                }
                            }
                        });
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_close", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            
            if (player != null && !menuId.isEmpty()) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.closeInventory());
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_click_sound", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String soundName = ctx.getInputValue(node, "sound", String.class, "UI_BUTTON_CLICK");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                try {
                    MenuData menu = menus.get(menuId);
                    Sound sound = Sound.valueOf(soundName.toUpperCase());
                    menu.clickSound = sound;
                } catch (IllegalArgumentException e) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_title", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String title = ctx.getInputValue(node, "title", String.class, "Menu");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.title = title;
            }
            ctx.triggerOutput("flow");
        });
    }

    public static Map<String, MenuData> getMenus() {
        return menus;
    }

    public static Map<Player, String> getOpenMenus() {
        return openMenus;
    }
}
