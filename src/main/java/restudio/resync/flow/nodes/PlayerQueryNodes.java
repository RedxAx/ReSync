package restudio.resync.flow.nodes;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PlayerQueryNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("player_has_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack itemStack = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
            if (itemStack == null) {
                String materialName = ctx.getInputValue(node, "material", String.class, "");
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    itemStack = new ItemStack(material);
                } else {
                    return;
                }
            }
            boolean hasItem = player.getInventory().containsAtLeast(itemStack, 1);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_item", hasItem);
        });

        registry.register("player_count_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack itemStack = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
            if (itemStack == null) {
                String materialName = ctx.getInputValue(node, "material", String.class, "");
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    itemStack = new ItemStack(material);
                } else {
                    return;
                }
            }
            int count = 0;
            ItemStack[] contents = player.getInventory().getStorageContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && item.isSimilar(itemStack)) {
                        count += item.getAmount();
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "count", count);
        });

        registry.register("player_get_first_empty_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int slot = player.getInventory().firstEmpty();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "slot_index", slot);
        });

        registry.register("player_get_all_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            ItemStack[] contents = player.getInventory().getStorageContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && item.getType() != Material.AIR) {
                        items.add(item);
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "items_list", items);
        });

        registry.register("player_get_hotbar_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    items.add(item);
                } else {
                    items.add(new ItemStack(Material.AIR));
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "items_list", items);
        });

        registry.register("player_get_armor_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack[] armorContents = player.getInventory().getArmorContents();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "helmet", armorContents[3]);
            ctx.setNodeOutput(nodeId, "chestplate", armorContents[2]);
            ctx.setNodeOutput(nodeId, "leggings", armorContents[1]);
            ctx.setNodeOutput(nodeId, "boots", armorContents[0]);
        });

        registry.register("player_get_inventory_size", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int size = player.getInventory().getSize();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "size", size);
        });

        registry.register("player_get_mainhand_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack mainhandItem = player.getInventory().getItemInMainHand();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", mainhandItem);
        });

        registry.register("player_get_offhand_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack offhandItem = player.getInventory().getItemInOffHand();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", offhandItem);
        });

        registry.register("player_is_on_ground", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean onGround = player.isOnGround();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "on_ground", onGround);
        });

        registry.register("player_is_sleeping", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean isSleeping = player.isSleeping();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_sleeping", isSleeping);
        });

        registry.register("player_get_bed_location", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            org.bukkit.Location bedLocation = player.getBedSpawnLocation();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "bed_location", bedLocation);
        });

        registry.register("player_get_last_damage", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            EntityDamageEvent lastDamageEvent = player.getLastDamageCause();
            String nodeId = findNodeId(ctx, node);
            if (lastDamageEvent != null) {
                ctx.setNodeOutput(nodeId, "damage_cause", lastDamageEvent.getCause().name());
                Entity damageSource = lastDamageEvent.getEntity();
                ctx.setNodeOutput(nodeId, "damage_source", damageSource);
            } else {
                ctx.setNodeOutput(nodeId, "damage_cause", null);
                ctx.setNodeOutput(nodeId, "damage_source", null);
            }
        });

        registry.register("player_get_killer", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            Player killer = player.getKiller();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "killer", killer);
        });

        registry.register("player_get_ping", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int ping = player.getPing();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ping_ms", ping);
        });



        registry.register("player_get_lore", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String hand = ctx.getInputValue(node, "hand", String.class, "main");
            ItemStack item = switch (hand.toLowerCase()) {
                case "off" -> player.getInventory().getItemInOffHand();
                default -> player.getInventory().getItemInMainHand();
            };
            List<String> loreLines = new ArrayList<>();
            if (item != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                List<net.kyori.adventure.text.Component> lore = meta.lore();
                if (lore != null) {
                    for (net.kyori.adventure.text.Component line : lore) {
                        loreLines.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "lore_lines_list", loreLines);
        });

        registry.register("player_get_display_name", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String displayName = player.getDisplayName();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "display_name", displayName);
        });

        registry.register("player_get_player_list_name", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String playerListName = player.getPlayerListName();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "list_name", playerListName);
        });

        registry.register("player_is_op", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean isOp = player.isOp();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_op", isOp);
        });

        registry.register("player_get_allowed_flight", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean canFly = player.getAllowFlight();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "can_fly", canFly);
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (PlayerQueryNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "player_has_item", displayName = "Player Has Item", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "material_or_item", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "has_item", dataType = FlowType.BOOLEAN)})
    public void playerHasItem(FlowContext ctx, FlowNode node) { executeLegacy("player_has_item", ctx, node); }

    @DefineNode(id = "player_count_item", displayName = "Player Count Item", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "material_or_item", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "count", dataType = FlowType.NUMBER)})
    public void playerCountItem(FlowContext ctx, FlowNode node) { executeLegacy("player_count_item", ctx, node); }

    @DefineNode(id = "player_get_first_empty_slot", displayName = "Player First Empty Slot", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "slot_index", dataType = FlowType.NUMBER)})
    public void playerGetFirstEmptySlot(FlowContext ctx, FlowNode node) { executeLegacy("player_get_first_empty_slot", ctx, node); }

    @DefineNode(id = "player_get_all_items", displayName = "Player Get All Items", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "items_list", dataType = FlowType.LIST)})
    public void playerGetAllItems(FlowContext ctx, FlowNode node) { executeLegacy("player_get_all_items", ctx, node); }

    @DefineNode(id = "player_get_hotbar_items", displayName = "Player Get Hotbar Items", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "items_list", dataType = FlowType.LIST)})
    public void playerGetHotbarItems(FlowContext ctx, FlowNode node) { executeLegacy("player_get_hotbar_items", ctx, node); }

    @DefineNode(id = "player_get_armor_items", displayName = "Player Get Armor Items", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {
                    @FlowPin(name = "helmet", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "chestplate", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "leggings", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "boots", dataType = FlowType.ITEMSTACK)
            })
    public void playerGetArmorItems(FlowContext ctx, FlowNode node) { executeLegacy("player_get_armor_items", ctx, node); }

    @DefineNode(id = "player_get_inventory_size", displayName = "Player Get Inventory Size", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "size", dataType = FlowType.NUMBER)})
    public void playerGetInventorySize(FlowContext ctx, FlowNode node) { executeLegacy("player_get_inventory_size", ctx, node); }

    @DefineNode(id = "player_get_mainhand_item", displayName = "Player Get Mainhand Item", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK)})
    public void playerGetMainhandItem(FlowContext ctx, FlowNode node) { executeLegacy("player_get_mainhand_item", ctx, node); }

    @DefineNode(id = "player_get_offhand_item", displayName = "Player Get Offhand Item", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK)})
    public void playerGetOffhandItem(FlowContext ctx, FlowNode node) { executeLegacy("player_get_offhand_item", ctx, node); }

    @DefineNode(id = "player_is_on_ground", displayName = "Player Is On Ground", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "on_ground", dataType = FlowType.BOOLEAN)})
    public void playerIsOnGround(FlowContext ctx, FlowNode node) { executeLegacy("player_is_on_ground", ctx, node); }

    @DefineNode(id = "player_is_sleeping", displayName = "Player Is Sleeping", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "is_sleeping", dataType = FlowType.BOOLEAN)})
    public void playerIsSleeping(FlowContext ctx, FlowNode node) { executeLegacy("player_is_sleeping", ctx, node); }

    @DefineNode(id = "player_get_bed_location", displayName = "Player Get Bed Location", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "bed_location", dataType = FlowType.LOCATION)})
    public void playerGetBedLocation(FlowContext ctx, FlowNode node) { executeLegacy("player_get_bed_location", ctx, node); }

    @DefineNode(id = "player_get_last_damage", displayName = "Player Get Last Damage", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {
                    @FlowPin(name = "damage_cause", dataType = FlowType.STRING),
                    @FlowPin(name = "damage_source", dataType = FlowType.ENTITY)
            })
    public void playerGetLastDamage(FlowContext ctx, FlowNode node) { executeLegacy("player_get_last_damage", ctx, node); }

    @DefineNode(id = "player_get_killer", displayName = "Player Get Killer", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "killer", dataType = FlowType.ENTITY)})
    public void playerGetKiller(FlowContext ctx, FlowNode node) { executeLegacy("player_get_killer", ctx, node); }

    @DefineNode(id = "player_get_ping", displayName = "Player Get Ping", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "ping_ms", dataType = FlowType.NUMBER)})
    public void playerGetPing(FlowContext ctx, FlowNode node) { executeLegacy("player_get_ping", ctx, node); }

    @DefineNode(id = "player_get_lore", displayName = "Player Get Lore", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "hand", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "lore_lines_list", dataType = FlowType.LIST)})
    public void playerGetLore(FlowContext ctx, FlowNode node) { executeLegacy("player_get_lore", ctx, node); }

    @DefineNode(id = "player_get_display_name", displayName = "Player Get Display Name", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "display_name", dataType = FlowType.STRING)})
    public void playerGetDisplayName(FlowContext ctx, FlowNode node) { executeLegacy("player_get_display_name", ctx, node); }

    @DefineNode(id = "player_get_player_list_name", displayName = "Player Get Player List Name", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "list_name", dataType = FlowType.STRING)})
    public void playerGetPlayerListName(FlowContext ctx, FlowNode node) { executeLegacy("player_get_player_list_name", ctx, node); }

    @DefineNode(id = "player_is_op", displayName = "Player Is OP", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "is_op", dataType = FlowType.BOOLEAN)})
    public void playerIsOp(FlowContext ctx, FlowNode node) { executeLegacy("player_is_op", ctx, node); }

    @DefineNode(id = "player_get_allowed_flight", displayName = "Player Get Allowed Flight", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "can_fly", dataType = FlowType.BOOLEAN)})
    public void playerGetAllowedFlight(FlowContext ctx, FlowNode node) { executeLegacy("player_get_allowed_flight", ctx, node); }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
