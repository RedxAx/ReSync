package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PlayerEventNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("event:move", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Location from = (Location) ctx.getVariable("event.from_location");
            Location to = (Location) ctx.getVariable("event.to_location");
            Double distance = (Double) ctx.getVariable("event.distance");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "from_location", from);
            ctx.setNodeOutput(nodeId, "to_location", to);
            ctx.setNodeOutput(nodeId, "distance", distance != null ? distance : from.distance(to));
            ctx.triggerOutput("next");
        });

        registry.register("event:interact", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Block clickedBlock = (Block) ctx.getVariable("event.clicked_block");
            Entity clickedEntity = (Entity) ctx.getVariable("event.clicked_entity");
            String actionType = (String) ctx.getVariable("event.action_type");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "clicked_block", clickedBlock);
            ctx.setNodeOutput(nodeId, "clicked_entity", clickedEntity);
            ctx.setNodeOutput(nodeId, "action_type", actionType);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_interact", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Entity entity = (Entity) ctx.getVariable("event.entity");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_damage", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Entity damager = (Entity) ctx.getVariable("event.damager");
            Entity victim = (Entity) ctx.getVariable("event.victim");
            Double damage = (Double) ctx.getVariable("event.damage");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "damager", damager);
            ctx.setNodeOutput(nodeId, "victim", victim);
            ctx.setNodeOutput(nodeId, "damage", damage);
            ctx.triggerOutput("next");
        });

        registry.register("event:shoot", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Entity projectile = (Entity) ctx.getVariable("event.projectile");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "projectile", projectile);
            ctx.triggerOutput("next");
        });

        registry.register("event:projectile_hit", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Entity projectile = (Entity) ctx.getVariable("event.projectile");
            Entity hitEntity = (Entity) ctx.getVariable("event.hit_entity");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "projectile", projectile);
            ctx.setNodeOutput(nodeId, "hit_entity", hitEntity);
            ctx.triggerOutput("next");
        });

        registry.register("event:pickup", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("next");
        });

        registry.register("event:drop", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("next");
        });

        registry.register("event:consume", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("next");
        });

        registry.register("event:craft", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack result = (ItemStack) ctx.getVariable("event.result");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "result", result);
            ctx.triggerOutput("next");
        });

        registry.register("event:smelt", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack result = (ItemStack) ctx.getVariable("event.result");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "result", result);
            ctx.triggerOutput("next");
        });

        registry.register("event:enchant", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");
            String enchantment = (String) ctx.getVariable("event.enchantment");
            Integer level = (Integer) ctx.getVariable("event.level");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.setNodeOutput(nodeId, "enchantment", enchantment);
            ctx.setNodeOutput(nodeId, "level", level);
            ctx.triggerOutput("next");
        });

        registry.register("event:bed_enter", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Location bedLocation = (Location) ctx.getVariable("event.bed_location");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "bed_location", bedLocation);
            ctx.triggerOutput("next");
        });

        registry.register("event:bed_leave", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Location bedLocation = (Location) ctx.getVariable("event.bed_location");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "bed_location", bedLocation);
            ctx.triggerOutput("next");
        });

        registry.register("event:respawn", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Location respawnLocation = (Location) ctx.getVariable("event.respawn_location");
            Location deathLocation = (Location) ctx.getVariable("event.death_location");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "respawn_location", respawnLocation);
            ctx.setNodeOutput(nodeId, "death_location", deathLocation);
            ctx.triggerOutput("next");
        });

        registry.register("event:level_up", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Integer oldLevel = (Integer) ctx.getVariable("event.old_level");
            Integer newLevel = (Integer) ctx.getVariable("event.new_level");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "old_level", oldLevel);
            ctx.setNodeOutput(nodeId, "new_level", newLevel);
            ctx.triggerOutput("next");
        });

        registry.register("event:resync_command", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String boundCommand = (String) ctx.getVariable("event.bound_command");
            String commandLabel = (String) ctx.getVariable("event.command_label");
            String args = (String) ctx.getVariable("event.args");
            Object argsList = ctx.getVariable("event.args_list");
            Integer argsCount = (Integer) ctx.getVariable("event.args_count");
            Boolean isConsole = (Boolean) ctx.getVariable("event.is_console");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "bound_command", boundCommand);
            ctx.setNodeOutput(nodeId, "command_label", commandLabel);
            ctx.setNodeOutput(nodeId, "args", args);
            ctx.setNodeOutput(nodeId, "args_list", argsList);
            ctx.setNodeOutput(nodeId, "args_count", argsCount);
            ctx.setNodeOutput(nodeId, "is_console", isConsole != null && isConsole);
            ctx.triggerOutput("next");
        });

        registry.register("event:command", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String boundCommand = (String) ctx.getVariable("event.bound_command");
            String commandLabel = (String) ctx.getVariable("event.command_label");
            String args = (String) ctx.getVariable("event.args");
            Object argsList = ctx.getVariable("event.args_list");
            Integer argsCount = (Integer) ctx.getVariable("event.args_count");
            Boolean isConsole = (Boolean) ctx.getVariable("event.is_console");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "bound_command", boundCommand);
            ctx.setNodeOutput(nodeId, "command_label", commandLabel);
            ctx.setNodeOutput(nodeId, "args", args);
            ctx.setNodeOutput(nodeId, "args_list", argsList);
            ctx.setNodeOutput(nodeId, "args_count", argsCount);
            ctx.setNodeOutput(nodeId, "is_console", isConsole != null && isConsole);
            ctx.triggerOutput("next");
        });

        registry.register("event:tab_complete", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String command = (String) ctx.getVariable("event.command");
            String completions = (String) ctx.getVariable("event.completions");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "command", command);
            ctx.setNodeOutput(nodeId, "completions", completions);
            ctx.triggerOutput("next");
        });

        registry.register("event:teleport", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Location from = (Location) ctx.getVariable("event.from_location");
            Location to = (Location) ctx.getVariable("event.to_location");
            String cause = (String) ctx.getVariable("event.cause");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "from_location", from);
            ctx.setNodeOutput(nodeId, "to_location", to);
            ctx.setNodeOutput(nodeId, "cause", cause);
            ctx.triggerOutput("next");
        });

        registry.register("event:gamemode_change", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String oldGamemode = (String) ctx.getVariable("event.old_gamemode");
            String newGamemode = (String) ctx.getVariable("event.new_gamemode");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "old_gamemode", oldGamemode);
            ctx.setNodeOutput(nodeId, "new_gamemode", newGamemode);
            ctx.triggerOutput("next");
        });

        registry.register("event:flight_toggle", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Boolean isFlying = (Boolean) ctx.getVariable("event.is_flying");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "is_flying", isFlying);
            ctx.triggerOutput("next");
        });

        registry.register("event:vanish_toggle", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Boolean isVanished = (Boolean) ctx.getVariable("event.is_vanished");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "is_vanished", isVanished);
            ctx.triggerOutput("next");
        });

        registry.register("event:fish", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String state = (String) ctx.getVariable("event.state");
            Entity caught = (Entity) ctx.getVariable("event.caught");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "state", state);
            ctx.setNodeOutput(nodeId, "caught", caught);
            ctx.triggerOutput("next");
        });

        registry.register("event:shear", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Entity entity = (Entity) ctx.getVariable("event.entity");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.triggerOutput("next");
        });

        registry.register("event:item_damage", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");
            Integer damage = (Integer) ctx.getVariable("event.damage");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.setNodeOutput(nodeId, "damage", damage);
            ctx.triggerOutput("next");
        });

        registry.register("event:item_break", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            ItemStack brokenItem = (ItemStack) ctx.getVariable("event.broken_item");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "broken_item", brokenItem);
            ctx.triggerOutput("next");
        });

        registry.register("event:exp_change", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            Integer amount = (Integer) ctx.getVariable("event.amount");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "amount", amount);
            ctx.triggerOutput("next");
        });
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (PlayerEventNodes.class) {
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
            ctx.triggerOutput("next");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "event:move", displayName = "On Player Move", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "from_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "to_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "distance", dataType = FlowType.NUMBER)
            })
    public void onMove(FlowContext ctx, FlowNode node) {
        executeLegacy("event:move", ctx, node);
    }

    @DefineNode(id = "event:interact", displayName = "On Player Interact", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "clicked_block", dataType = FlowType.ANY),
                    @FlowPin(name = "clicked_entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "action_type", dataType = FlowType.STRING)
            })
    public void onInteract(FlowContext ctx, FlowNode node) {
        executeLegacy("event:interact", ctx, node);
    }

    @DefineNode(id = "event:entity_interact", displayName = "On Entity Interact", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            })
    public void onEntityInteract(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_interact", ctx, node);
    }

    @DefineNode(id = "event:entity_damage", displayName = "On Entity Damage", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "damager", dataType = FlowType.ENTITY),
                    @FlowPin(name = "victim", dataType = FlowType.ENTITY),
                    @FlowPin(name = "damage", dataType = FlowType.NUMBER)
            })
    public void onEntityDamage(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_damage", ctx, node);
    }

    @DefineNode(id = "event:shoot", displayName = "On Projectile Shoot", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "projectile", dataType = FlowType.ENTITY)
            })
    public void onShoot(FlowContext ctx, FlowNode node) {
        executeLegacy("event:shoot", ctx, node);
    }

    @DefineNode(id = "event:projectile_hit", displayName = "On Projectile Hit", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "projectile", dataType = FlowType.ENTITY),
                    @FlowPin(name = "hit_entity", dataType = FlowType.ENTITY)
            })
    public void onProjectileHit(FlowContext ctx, FlowNode node) {
        executeLegacy("event:projectile_hit", ctx, node);
    }

    @DefineNode(id = "event:pickup", displayName = "On Item Pickup", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            })
    public void onPickup(FlowContext ctx, FlowNode node) {
        executeLegacy("event:pickup", ctx, node);
    }

    @DefineNode(id = "event:drop", displayName = "On Item Drop", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            })
    public void onDrop(FlowContext ctx, FlowNode node) {
        executeLegacy("event:drop", ctx, node);
    }

    @DefineNode(id = "event:consume", displayName = "On Item Consume", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            })
    public void onConsume(FlowContext ctx, FlowNode node) {
        executeLegacy("event:consume", ctx, node);
    }

    @DefineNode(id = "event:craft", displayName = "On Item Craft", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "result", dataType = FlowType.ITEMSTACK)
            })
    public void onCraft(FlowContext ctx, FlowNode node) {
        executeLegacy("event:craft", ctx, node);
    }

    @DefineNode(id = "event:smelt", displayName = "On Item Smelt", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "result", dataType = FlowType.ITEMSTACK)
            })
    public void onSmelt(FlowContext ctx, FlowNode node) {
        executeLegacy("event:smelt", ctx, node);
    }

    @DefineNode(id = "event:enchant", displayName = "On Item Enchant", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "enchantment", dataType = FlowType.STRING),
                    @FlowPin(name = "level", dataType = FlowType.NUMBER)
            })
    public void onEnchant(FlowContext ctx, FlowNode node) {
        executeLegacy("event:enchant", ctx, node);
    }

    @DefineNode(id = "event:bed_enter", displayName = "On Bed Enter", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "bed_location", dataType = FlowType.LOCATION)
            })
    public void onBedEnter(FlowContext ctx, FlowNode node) {
        executeLegacy("event:bed_enter", ctx, node);
    }

    @DefineNode(id = "event:bed_leave", displayName = "On Bed Leave", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "bed_location", dataType = FlowType.LOCATION)
            })
    public void onBedLeave(FlowContext ctx, FlowNode node) {
        executeLegacy("event:bed_leave", ctx, node);
    }

    @DefineNode(id = "event:respawn", displayName = "On Player Respawn", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "respawn_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "death_location", dataType = FlowType.LOCATION)
            })
    public void onRespawn(FlowContext ctx, FlowNode node) {
        executeLegacy("event:respawn", ctx, node);
    }

    @DefineNode(id = "event:level_up", displayName = "On Level Up", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "old_level", dataType = FlowType.NUMBER),
                    @FlowPin(name = "new_level", dataType = FlowType.NUMBER)
            })
    public void onLevelUp(FlowContext ctx, FlowNode node) {
        executeLegacy("event:level_up", ctx, node);
    }

    @DefineNode(id = "event:resync_command", displayName = "On ReSync Command Execute", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "bound_command", dataType = FlowType.STRING),
                    @FlowPin(name = "command_label", dataType = FlowType.STRING),
                    @FlowPin(name = "args", dataType = FlowType.STRING),
                    @FlowPin(name = "args_list", dataType = FlowType.LIST),
                    @FlowPin(name = "args_count", dataType = FlowType.NUMBER),
                    @FlowPin(name = "is_console", dataType = FlowType.BOOLEAN)
            })
    public void onReSyncCommand(FlowContext ctx, FlowNode node) {
        executeLegacy("event:resync_command", ctx, node);
    }

    @DefineNode(id = "event:command", displayName = "On Player Command", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "bound_command", dataType = FlowType.STRING),
                    @FlowPin(name = "command_label", dataType = FlowType.STRING),
                    @FlowPin(name = "args", dataType = FlowType.STRING),
                    @FlowPin(name = "args_list", dataType = FlowType.LIST),
                    @FlowPin(name = "args_count", dataType = FlowType.NUMBER),
                    @FlowPin(name = "is_console", dataType = FlowType.BOOLEAN)
            })
    public void onCommand(FlowContext ctx, FlowNode node) {
        executeLegacy("event:command", ctx, node);
    }

    @DefineNode(id = "event:tab_complete", displayName = "On Tab Complete", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "command", dataType = FlowType.STRING),
                    @FlowPin(name = "completions", dataType = FlowType.STRING)
            })
    public void onTabComplete(FlowContext ctx, FlowNode node) {
        executeLegacy("event:tab_complete", ctx, node);
    }

    @DefineNode(id = "event:teleport", displayName = "On Player Teleport", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "from_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "to_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "cause", dataType = FlowType.STRING)
            })
    public void onTeleport(FlowContext ctx, FlowNode node) {
        executeLegacy("event:teleport", ctx, node);
    }

    @DefineNode(id = "event:gamemode_change", displayName = "On Gamemode Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "old_gamemode", dataType = FlowType.STRING),
                    @FlowPin(name = "new_gamemode", dataType = FlowType.STRING)
            })
    public void onGamemodeChange(FlowContext ctx, FlowNode node) {
        executeLegacy("event:gamemode_change", ctx, node);
    }

    @DefineNode(id = "event:flight_toggle", displayName = "On Flight Toggle", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "is_flying", dataType = FlowType.BOOLEAN)
            })
    public void onFlightToggle(FlowContext ctx, FlowNode node) {
        executeLegacy("event:flight_toggle", ctx, node);
    }

    @DefineNode(id = "event:vanish_toggle", displayName = "On Vanish Toggle", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "is_vanished", dataType = FlowType.BOOLEAN)
            })
    public void onVanishToggle(FlowContext ctx, FlowNode node) {
        executeLegacy("event:vanish_toggle", ctx, node);
    }

    @DefineNode(id = "event:fish", displayName = "On Player Fish", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "state", dataType = FlowType.STRING),
                    @FlowPin(name = "caught", dataType = FlowType.ENTITY)
            })
    public void onFish(FlowContext ctx, FlowNode node) {
        executeLegacy("event:fish", ctx, node);
    }

    @DefineNode(id = "event:shear", displayName = "On Entity Shear", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            })
    public void onShear(FlowContext ctx, FlowNode node) {
        executeLegacy("event:shear", ctx, node);
    }

    @DefineNode(id = "event:item_damage", displayName = "On Item Damage", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "damage", dataType = FlowType.NUMBER)
            })
    public void onItemDamage(FlowContext ctx, FlowNode node) {
        executeLegacy("event:item_damage", ctx, node);
    }

    @DefineNode(id = "event:item_break", displayName = "On Item Break", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "broken_item", dataType = FlowType.ITEMSTACK)
            })
    public void onItemBreak(FlowContext ctx, FlowNode node) {
        executeLegacy("event:item_break", ctx, node);
    }

    @DefineNode(id = "event:exp_change", displayName = "On Experience Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER)
            })
    public void onExpChange(FlowContext ctx, FlowNode node) {
        executeLegacy("event:exp_change", ctx, node);
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
