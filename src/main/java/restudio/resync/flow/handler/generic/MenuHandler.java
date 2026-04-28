package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.server.ReSyncServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class MenuHandler implements NodeHandler {
    private static final Map<String, MenuData> menus = new HashMap<>();
    private static final Map<Player, String> openMenus = new HashMap<>();

    private static class MenuData {
        String title;
        int rows;
        Map<Integer, MenuItemData> items = new HashMap<>();
        Sound clickSound = Sound.UI_BUTTON_CLICK;
        String closeAction;
        String openAction;
        int updateInterval;
        int updateTaskId = -1;
    }

    private static class MenuItemData {
        ItemStack item;
        String flowId;
        boolean enchanted;
        List<ItemFlag> flags;
        int customModelData = -1;
        String skullTexture;
    }

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public MenuHandler() {
        operations.put("menu_create", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String title = ctx.getInputValue(node, "title", String.class, "Menu");
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);

            if (!menuId.isEmpty() && rows >= 1 && rows <= 6) {
                MenuData menu = new MenuData();
                menu.title = title;
                menu.rows = rows;
                menus.put(menuId, menu);
            }
        });

        operations.put("menu_set_item", (ctx, node) -> {
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
        });

        operations.put("menu_add_item", (ctx, node) -> {
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
        });

        operations.put("menu_clear", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.items.clear();
            }
        });

        operations.put("menu_open", (ctx, node) -> {
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
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        Inventory inv = Bukkit.createInventory(null, menu.rows * 9, TextFormatter.parse(menu.title));
                        for (Map.Entry<Integer, MenuItemData> entry : menu.items.entrySet()) {
                            inv.setItem(entry.getKey(), entry.getValue().item);
                        }
                        player.openInventory(inv);
                        openMenus.put(player, menuId);
                    });
                }
            } else if (player != null && !menuId.isEmpty()) {
                ReSyncServer server = ReSync.getInstance() != null ? ReSync.getInstance().getReSyncServer() : null;
                GuiManager guiManager = server != null ? server.getGuiManager() : null;
                if (guiManager != null) {
                    if (Bukkit.isPrimaryThread()) {
                        guiManager.openGui(player, menuId);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> guiManager.openGui(player, menuId));
                    }
                }
            }
        });

        operations.put("menu_update", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
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
        });

        operations.put("menu_close", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");

            if (player != null && !menuId.isEmpty()) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.closeInventory());
                }
            }
        });

        operations.put("menu_set_click_sound", (ctx, node) -> {
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
        });

        operations.put("menu_set_title", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String title = ctx.getInputValue(node, "title", String.class, "Menu");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.title = title;
            }
        });

        operations.put("menu_set_click_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9) {
                    MenuItemData menuItem = menu.items.get(slot);
                    if (menuItem == null) {
                        menuItem = new MenuItemData();
                        menuItem.item = new ItemStack(Material.AIR);
                        menu.items.put(slot, menuItem);
                    }
                    menuItem.flowId = flowId;
                }
            }
        });

        operations.put("menu_set_item_with_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String name = ctx.getInputValue(node, "name", String.class, "");
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");

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
        });

        operations.put("menu_set_enchant", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            Boolean enchanted = ctx.getInputValue(node, "enchanted", Boolean.class, false);

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    menuItem.enchanted = enchanted;
                    if (enchanted) {
                        ItemMeta meta = menuItem.item.getItemMeta();
                        if (meta != null) {
                            meta.addEnchant(Enchantment.MENDING, 1, true);
                            menuItem.item.setItemMeta(meta);
                        }
                    }
                }
            }
        });

        operations.put("menu_set_flags", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String flagsList = ctx.getInputValue(node, "flags_list", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    List<ItemFlag> flags = new ArrayList<>();
                    if (!flagsList.isEmpty()) {
                        String[] flagNames = flagsList.split(",");
                        for (String flagName : flagNames) {
                            try {
                                flags.add(ItemFlag.valueOf(flagName.trim().toUpperCase()));
                            } catch (IllegalArgumentException e) {
                            }
                        }
                    }
                    menuItem.flags = flags;
                    ItemMeta meta = menuItem.item.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(flags.toArray(new ItemFlag[0]));
                        menuItem.item.setItemMeta(meta);
                    }
                }
            }
        });

        operations.put("menu_set_custom_model", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    menuItem.customModelData = modelData;
                    ItemMeta meta = menuItem.item.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(modelData);
                        menuItem.item.setItemMeta(meta);
                    }
                }
            }
        });

        operations.put("menu_set_head_texture", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String playerNameOrUuid = ctx.getInputValue(node, "player_name_or_uuid", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    menuItem.skullTexture = playerNameOrUuid;
                    ItemStack item = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) item.getItemMeta();
                    if (meta != null) {
                        try {
                            UUID uuid = UUID.fromString(playerNameOrUuid);
                            meta.setOwnerProfile(Bukkit.createProfile(uuid));
                        } catch (IllegalArgumentException e) {
                            meta.setOwnerProfile(Bukkit.createProfile(playerNameOrUuid));
                        }
                        item.setItemMeta(meta);
                    }
                    menuItem.item = item;
                }
            }
        });

        operations.put("menu_fill_pattern", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer startSlot = ctx.getInputValue(node, "start_slot", Integer.class, 0);
            Integer endSlot = ctx.getInputValue(node, "end_slot", Integer.class, 0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String name = ctx.getInputValue(node, "name", String.class, "");
            String lore = ctx.getInputValue(node, "lore", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null && startSlot >= 0 && endSlot >= startSlot && endSlot < menu.rows * 9) {
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
                    for (int i = startSlot; i <= endSlot; i++) {
                        MenuItemData menuItem = new MenuItemData();
                        menuItem.item = item.clone();
                        menu.items.put(i, menuItem);
                    }
                }
            }
        });

        operations.put("menu_clear_slot", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9) {
                    menu.items.remove(slot);
                }
            }
        });

        operations.put("menu_get_item", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    ctx.setOutput(node, "item", menuItem.item.clone());
                }
            }
        });

        operations.put("menu_get_all_items", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                List<ItemStack> items = new ArrayList<>();
                for (MenuItemData menuItem : menu.items.values()) {
                    items.add(menuItem.item.clone());
                }
                ctx.setOutput(node, "items_list", items);
            }
        });

        operations.put("menu_duplicate", (ctx, node) -> {
            String sourceMenuId = ctx.getInputValue(node, "source_menu_id", String.class, "");
            String newMenuId = ctx.getInputValue(node, "new_menu_id", String.class, "");

            if (!sourceMenuId.isEmpty() && !newMenuId.isEmpty() && menus.containsKey(sourceMenuId) && !menus.containsKey(newMenuId)) {
                MenuData sourceMenu = menus.get(sourceMenuId);
                MenuData newMenu = new MenuData();
                newMenu.title = sourceMenu.title;
                newMenu.rows = sourceMenu.rows;
                newMenu.clickSound = sourceMenu.clickSound;
                newMenu.closeAction = sourceMenu.closeAction;
                newMenu.openAction = sourceMenu.openAction;
                newMenu.updateInterval = sourceMenu.updateInterval;
                for (Map.Entry<Integer, MenuItemData> entry : sourceMenu.items.entrySet()) {
                    MenuItemData sourceItem = entry.getValue();
                    MenuItemData newItem = new MenuItemData();
                    newItem.item = sourceItem.item.clone();
                    newItem.flowId = sourceItem.flowId;
                    newItem.enchanted = sourceItem.enchanted;
                    newItem.flags = sourceItem.flags != null ? new ArrayList<>(sourceItem.flags) : null;
                    newItem.customModelData = sourceItem.customModelData;
                    newItem.skullTexture = sourceItem.skullTexture;
                    newMenu.items.put(entry.getKey(), newItem);
                }
                menus.put(newMenuId, newMenu);
            }
        });

        operations.put("menu_set_close_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.closeAction = flowId;
            }
        });

        operations.put("menu_set_open_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.openAction = flowId;
            }
        });

        operations.put("menu_set_update_interval", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer intervalTicks = ctx.getInputValue(node, "interval_ticks", Integer.class, 20);
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");

            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (menu.updateTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(menu.updateTaskId);
                }
                menu.updateInterval = intervalTicks;
                menu.openAction = flowId;
            }
        });

        operations.put("menu_get_open_menu_id", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);

            if (player != null && openMenus.containsKey(player)) {
                ctx.setOutput(node, "menu_id", openMenus.get(player));
            }
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("MenuHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }

    public static Map<String, MenuData> getMenus() {
        return menus;
    }

    public static Map<Player, String> getOpenMenus() {
        return openMenus;
    }
}
