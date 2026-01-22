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
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class PlayerEventNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
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

        registry.register("event:command", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = (Player) ctx.getVariable("event.player");
            String commandLabel = (String) ctx.getVariable("event.command_label");
            String args = (String) ctx.getVariable("event.args");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "command_label", commandLabel);
            ctx.setNodeOutput(nodeId, "args", args);
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

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
