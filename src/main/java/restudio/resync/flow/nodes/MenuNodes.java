package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.ReSync;
import restudio.resync.flow.GuiManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class MenuNodes {

    private static final Map<String, MenuData> menus = new HashMap<>();
    private static final Map<Player, String> openMenus = new HashMap<>();
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void registerLegacyNodes(FlowRegistry registry) {
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

        registry.register("menu_set_click_action", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_item_with_action", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_enchant", (ctx, node) -> {
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
                            meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
                            menuItem.item.setItemMeta(meta);
                        }
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_flags", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_custom_model", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_head_texture", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_fill_pattern", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_clear_slot", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9) {
                    menu.items.remove(slot);
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_get_item", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                if (slot >= 0 && slot < menu.rows * 9 && menu.items.containsKey(slot)) {
                    MenuItemData menuItem = menu.items.get(slot);
                    ctx.setNodeOutput(nodeId, "item", menuItem.item.clone());
                }
            }
        });

        registry.register("menu_get_all_items", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                List<ItemStack> items = new ArrayList<>();
                for (MenuItemData menuItem : menu.items.values()) {
                    items.add(menuItem.item.clone());
                }
                ctx.setNodeOutput(nodeId, "items_list", items);
            }
        });

        registry.register("menu_duplicate", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_close_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.closeAction = flowId;
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_open_action", (ctx, node) -> {
            String menuId = ctx.getInputValue(node, "menu_id", String.class, "");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            
            if (!menuId.isEmpty() && menus.containsKey(menuId)) {
                MenuData menu = menus.get(menuId);
                menu.openAction = flowId;
            }
            ctx.triggerOutput("flow");
        });

        registry.register("menu_set_update_interval", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("menu_get_open_menu_id", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player != null && openMenus.containsKey(player)) {
                ctx.setNodeOutput(nodeId, "menu_id", openMenus.get(player));
            }
        });
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (LEGACY_EXECUTORS) {
            if (initialized) {
                return;
            }
            FlowRegistry registry = new FlowRegistry();
            registerLegacyNodes(registry);
            for (String type : registry.getRegisteredTypes()) {
                BiConsumer<FlowContext, FlowNode> executor = registry.getExecutor(type);
                if (executor != null) {
                    LEGACY_EXECUTORS.put(type, executor);
                }
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "menu_create", displayName = "Create Menu", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "title", dataType = FlowType.STRING),
                    @FlowPin(name = "rows", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuCreate(FlowContext ctx, FlowNode node) { executeLegacy("menu_create", ctx, node); }

    @DefineNode(id = "menu_set_item", displayName = "Set Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "material", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "lore", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_to_execute", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetItem(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_item", ctx, node); }

    @DefineNode(id = "menu_add_item", displayName = "Add Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "material", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "lore", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_to_execute", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuAddItem(FlowContext ctx, FlowNode node) { executeLegacy("menu_add_item", ctx, node); }

    @DefineNode(id = "menu_clear", displayName = "Clear Menu", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuClear(FlowContext ctx, FlowNode node) { executeLegacy("menu_clear", ctx, node); }

    @DefineNode(id = "menu_open", displayName = "Open Menu", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuOpen(FlowContext ctx, FlowNode node) { executeLegacy("menu_open", ctx, node); }

    @DefineNode(id = "menu_update", displayName = "Update Menu", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuUpdate(FlowContext ctx, FlowNode node) { executeLegacy("menu_update", ctx, node); }

    @DefineNode(id = "menu_close", displayName = "Close Menu", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuClose(FlowContext ctx, FlowNode node) { executeLegacy("menu_close", ctx, node); }

    @DefineNode(id = "menu_set_click_sound", displayName = "Set Click Sound", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "sound", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetClickSound(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_click_sound", ctx, node); }

    @DefineNode(id = "menu_set_title", displayName = "Set Title", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "title", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetTitle(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_title", ctx, node); }

    @DefineNode(id = "menu_set_click_action", displayName = "Set Click Action", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetClickAction(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_click_action", ctx, node); }

    @DefineNode(id = "menu_set_item_with_action", displayName = "Set Item With Action", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "material", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "lore", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetItemWithAction(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_item_with_action", ctx, node); }

    @DefineNode(id = "menu_set_enchant", displayName = "Set Enchant", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "enchanted", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetEnchant(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_enchant", ctx, node); }

    @DefineNode(id = "menu_set_flags", displayName = "Set Flags", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flags_list", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetFlags(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_flags", ctx, node); }

    @DefineNode(id = "menu_set_custom_model", displayName = "Set Custom Model", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "model_data", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetCustomModel(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_custom_model", ctx, node); }

    @DefineNode(id = "menu_set_head_texture", displayName = "Set Head Texture", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "player_name_or_uuid", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetHeadTexture(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_head_texture", ctx, node); }

    @DefineNode(id = "menu_fill_pattern", displayName = "Fill Pattern", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "start_slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "end_slot", dataType = FlowType.NUMBER),
                    @FlowPin(name = "material", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "lore", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuFillPattern(FlowContext ctx, FlowNode node) { executeLegacy("menu_fill_pattern", ctx, node); }

    @DefineNode(id = "menu_clear_slot", displayName = "Clear Slot", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuClearSlot(FlowContext ctx, FlowNode node) { executeLegacy("menu_clear_slot", ctx, node); }

    @DefineNode(id = "menu_get_item", displayName = "Get Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "slot", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK)})
    public void menuGetItem(FlowContext ctx, FlowNode node) { executeLegacy("menu_get_item", ctx, node); }

    @DefineNode(id = "menu_get_all_items", displayName = "Get All Items", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "menu_id", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "items_list", dataType = FlowType.LIST)})
    public void menuGetAllItems(FlowContext ctx, FlowNode node) { executeLegacy("menu_get_all_items", ctx, node); }

    @DefineNode(id = "menu_duplicate", displayName = "Duplicate", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "source_menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "new_menu_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuDuplicate(FlowContext ctx, FlowNode node) { executeLegacy("menu_duplicate", ctx, node); }

    @DefineNode(id = "menu_set_close_action", displayName = "Set Close Action", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetCloseAction(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_close_action", ctx, node); }

    @DefineNode(id = "menu_set_open_action", displayName = "Set Open Action", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetOpenAction(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_open_action", ctx, node); }

    @DefineNode(id = "menu_set_update_interval", displayName = "Set Update Interval", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "menu_id", dataType = FlowType.STRING),
                    @FlowPin(name = "interval_ticks", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void menuSetUpdateInterval(FlowContext ctx, FlowNode node) { executeLegacy("menu_set_update_interval", ctx, node); }

    @DefineNode(id = "menu_get_open_menu_id", displayName = "Get Open Menu Id", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "menu_id", dataType = FlowType.STRING)})
    public void menuGetOpenMenuId(FlowContext ctx, FlowNode node) { executeLegacy("menu_get_open_menu_id", ctx, node); }

    public static Map<String, MenuData> getMenus() {
        return menus;
    }

    public static Map<Player, String> getOpenMenus() {
        return openMenus;
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
