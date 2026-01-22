package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class WorldEventNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("event:block_redstone", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Integer oldPower = (Integer) ctx.getVariable("event.old_power");
            Integer newPower = (Integer) ctx.getVariable("event.new_power");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "old_power", oldPower);
            ctx.setNodeOutput(nodeId, "new_power", newPower);
            ctx.triggerOutput("next");
        });

        registry.register("event:physics", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:explosion", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Location location = (Location) ctx.getVariable("event.location");
            Float power = (Float) ctx.getVariable("event.power");
            Boolean breakBlocks = (Boolean) ctx.getVariable("event.break_blocks");
            Boolean fire = (Boolean) ctx.getVariable("event.fire");
            Entity entity = (Entity) ctx.getVariable("event.entity");
            String worldName = (String) ctx.getVariable("event.world_name");

            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "power", power);
            ctx.setNodeOutput(nodeId, "break_blocks", breakBlocks);
            ctx.setNodeOutput(nodeId, "fire", fire);
            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.triggerOutput("next");
        });

        registry.register("event:grow", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            BlockState newState = (BlockState) ctx.getVariable("event.new_state");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "new_state", newState);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_from_to", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block fromBlock = (Block) ctx.getVariable("event.from_block");
            Block toBlock = (Block) ctx.getVariable("event.to_block");
            Location fromLocation = (Location) ctx.getVariable("event.from_location");
            Location toLocation = (Location) ctx.getVariable("event.to_location");

            ctx.setNodeOutput(nodeId, "from_block", fromBlock);
            ctx.setNodeOutput(nodeId, "to_block", toBlock);
            ctx.setNodeOutput(nodeId, "from_location", fromLocation);
            ctx.setNodeOutput(nodeId, "to_location", toLocation);
            ctx.triggerOutput("next");
        });

        registry.register("event:structure_spawn", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Location location = (Location) ctx.getVariable("event.location");
            String structureType = (String) ctx.getVariable("event.structure_type");
            String worldName = (String) ctx.getVariable("event.world_name");

            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "structure_type", structureType);
            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.triggerOutput("next");
        });

        registry.register("event:world_save", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");

            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.triggerOutput("next");
        });

        registry.register("event:weather_change", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");
            String oldWeather = (String) ctx.getVariable("event.old_weather");
            String newWeather = (String) ctx.getVariable("event.new_weather");

            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.setNodeOutput(nodeId, "old_weather", oldWeather);
            ctx.setNodeOutput(nodeId, "new_weather", newWeather);
            ctx.triggerOutput("next");
        });

        registry.register("event:time_change", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");
            Long oldTime = (Long) ctx.getVariable("event.old_time");
            Long newTime = (Long) ctx.getVariable("event.new_time");

            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.setNodeOutput(nodeId, "old_time", oldTime);
            ctx.setNodeOutput(nodeId, "new_time", newTime);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_break", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_place", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Block placedAgainst = (Block) ctx.getVariable("event.placed_against");
            Location location = (Location) ctx.getVariable("event.location");
            Location againstLocation = (Location) ctx.getVariable("event.against_location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "placed_against", placedAgainst);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "against_location", againstLocation);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_dispense", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");
            ItemStack item = (ItemStack) ctx.getVariable("event.item");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_fade", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            BlockState newState = (BlockState) ctx.getVariable("event.new_state");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "new_state", newState);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_form", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            BlockState newState = (BlockState) ctx.getVariable("event.new_state");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "new_state", newState);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_spread", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Block newState = (Block) ctx.getVariable("event.new_block");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "new_block", newState);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:lightning_strike", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Location location = (Location) ctx.getVariable("event.location");
            Entity struckEntity = (Entity) ctx.getVariable("event.struck_entity");
            String worldName = (String) ctx.getVariable("event.world_name");

            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "struck_entity", struckEntity);
            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.triggerOutput("next");
        });

        registry.register("event:leaf_decay", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:sign_change", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");
            String[] lines = (String[]) ctx.getVariable("event.lines");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "lines", lines);
            ctx.triggerOutput("next");
        });

        registry.register("event:furnace_smelt", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block furnace = (Block) ctx.getVariable("event.furnace");
            ItemStack result = (ItemStack) ctx.getVariable("event.result");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "furnace", furnace);
            ctx.setNodeOutput(nodeId, "result", result);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:inventory_open", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String inventoryTitle = (String) ctx.getVariable("event.inventory_title");
            String inventoryType = (String) ctx.getVariable("event.inventory_type");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "inventory_title", inventoryTitle);
            ctx.setNodeOutput(nodeId, "inventory_type", inventoryType);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:inventory_close", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String inventoryTitle = (String) ctx.getVariable("event.inventory_title");
            String inventoryType = (String) ctx.getVariable("event.inventory_type");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "inventory_title", inventoryTitle);
            ctx.setNodeOutput(nodeId, "inventory_type", inventoryType);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.triggerOutput("next");
        });

        registry.register("event:note_play", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");
            Integer instrument = (Integer) ctx.getVariable("event.instrument");
            Integer note = (Integer) ctx.getVariable("event.note");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "instrument", instrument);
            ctx.setNodeOutput(nodeId, "note", note);
            ctx.triggerOutput("next");
        });

        registry.register("event:piston_extend", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");
            Integer length = (Integer) ctx.getVariable("event.length");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "length", length);
            ctx.triggerOutput("next");
        });

        registry.register("event:piston_retract", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Block block = (Block) ctx.getVariable("event.block");
            Location location = (Location) ctx.getVariable("event.location");

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.setNodeOutput(nodeId, "location", location);
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
