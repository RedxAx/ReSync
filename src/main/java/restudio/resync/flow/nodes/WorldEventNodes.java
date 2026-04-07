package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
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

public class WorldEventNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (WorldEventNodes.class) {
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

    @DefineNode(id = "event:block_redstone", displayName = "On Redstone Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "old_power", dataType = FlowType.NUMBER),
                    @FlowPin(name = "new_power", dataType = FlowType.NUMBER)
            })
    public void onBlockRedstone(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_redstone", ctx, node);
    }

    @DefineNode(id = "event:physics", displayName = "On Block Physics", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onPhysics(FlowContext ctx, FlowNode node) {
        executeLegacy("event:physics", ctx, node);
    }

    @DefineNode(id = "event:explosion", displayName = "On Explosion", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "power", dataType = FlowType.NUMBER),
                    @FlowPin(name = "break_blocks", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "fire", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            })
    public void onExplosion(FlowContext ctx, FlowNode node) {
        executeLegacy("event:explosion", ctx, node);
    }

    @DefineNode(id = "event:grow", displayName = "On Block Grow", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "new_state", dataType = FlowType.ANY)
            })
    public void onGrow(FlowContext ctx, FlowNode node) {
        executeLegacy("event:grow", ctx, node);
    }

    @DefineNode(id = "event:block_from_to", displayName = "On Block From To", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "from_block", dataType = FlowType.ANY),
                    @FlowPin(name = "to_block", dataType = FlowType.ANY),
                    @FlowPin(name = "from_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "to_location", dataType = FlowType.LOCATION)
            })
    public void onBlockFromTo(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_from_to", ctx, node);
    }

    @DefineNode(id = "event:structure_spawn", displayName = "On Structure Spawn", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "structure_type", dataType = FlowType.STRING),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            })
    public void onStructureSpawn(FlowContext ctx, FlowNode node) {
        executeLegacy("event:structure_spawn", ctx, node);
    }

    @DefineNode(id = "event:world_save", displayName = "On World Save", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            })
    public void onWorldSave(FlowContext ctx, FlowNode node) {
        executeLegacy("event:world_save", ctx, node);
    }

    @DefineNode(id = "event:weather_change", displayName = "On Weather Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "old_weather", dataType = FlowType.STRING),
                    @FlowPin(name = "new_weather", dataType = FlowType.STRING)
            })
    public void onWeatherChange(FlowContext ctx, FlowNode node) {
        executeLegacy("event:weather_change", ctx, node);
    }

    @DefineNode(id = "event:time_change", displayName = "On Time Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "old_time", dataType = FlowType.NUMBER),
                    @FlowPin(name = "new_time", dataType = FlowType.NUMBER)
            })
    public void onTimeChange(FlowContext ctx, FlowNode node) {
        executeLegacy("event:time_change", ctx, node);
    }

    @DefineNode(id = "event:block_break", displayName = "On Block Break", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onBlockBreak(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_break", ctx, node);
    }

    @DefineNode(id = "event:block_place", displayName = "On Block Place", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "placed_against", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "against_location", dataType = FlowType.LOCATION)
            })
    public void onBlockPlace(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_place", ctx, node);
    }

    @DefineNode(id = "event:block_dispense", displayName = "On Block Dispense", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            })
    public void onBlockDispense(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_dispense", ctx, node);
    }

    @DefineNode(id = "event:block_fade", displayName = "On Block Fade", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "new_state", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onBlockFade(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_fade", ctx, node);
    }

    @DefineNode(id = "event:block_form", displayName = "On Block Form", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "new_state", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onBlockForm(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_form", ctx, node);
    }

    @DefineNode(id = "event:block_spread", displayName = "On Block Spread", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "new_block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onBlockSpread(FlowContext ctx, FlowNode node) {
        executeLegacy("event:block_spread", ctx, node);
    }

    @DefineNode(id = "event:lightning_strike", displayName = "On Lightning Strike", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "struck_entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            })
    public void onLightningStrike(FlowContext ctx, FlowNode node) {
        executeLegacy("event:lightning_strike", ctx, node);
    }

    @DefineNode(id = "event:leaf_decay", displayName = "On Leaf Decay", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onLeafDecay(FlowContext ctx, FlowNode node) {
        executeLegacy("event:leaf_decay", ctx, node);
    }

    @DefineNode(id = "event:sign_change", displayName = "On Sign Change", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "lines", dataType = FlowType.LIST)
            })
    public void onSignChange(FlowContext ctx, FlowNode node) {
        executeLegacy("event:sign_change", ctx, node);
    }

    @DefineNode(id = "event:furnace_smelt", displayName = "On Furnace Smelt", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "furnace", dataType = FlowType.ANY),
                    @FlowPin(name = "result", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onFurnaceSmelt(FlowContext ctx, FlowNode node) {
        executeLegacy("event:furnace_smelt", ctx, node);
    }

    @DefineNode(id = "event:inventory_open", displayName = "On Inventory Open", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "inventory_title", dataType = FlowType.STRING),
                    @FlowPin(name = "inventory_type", dataType = FlowType.STRING),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onInventoryOpen(FlowContext ctx, FlowNode node) {
        executeLegacy("event:inventory_open", ctx, node);
    }

    @DefineNode(id = "event:inventory_close", displayName = "On Inventory Close", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "inventory_title", dataType = FlowType.STRING),
                    @FlowPin(name = "inventory_type", dataType = FlowType.STRING),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onInventoryClose(FlowContext ctx, FlowNode node) {
        executeLegacy("event:inventory_close", ctx, node);
    }

    @DefineNode(id = "event:note_play", displayName = "On Note Play", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "instrument", dataType = FlowType.NUMBER),
                    @FlowPin(name = "note", dataType = FlowType.NUMBER)
            })
    public void onNotePlay(FlowContext ctx, FlowNode node) {
        executeLegacy("event:note_play", ctx, node);
    }

    @DefineNode(id = "event:piston_extend", displayName = "On Piston Extend", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "length", dataType = FlowType.NUMBER)
            })
    public void onPistonExtend(FlowContext ctx, FlowNode node) {
        executeLegacy("event:piston_extend", ctx, node);
    }

    @DefineNode(id = "event:piston_retract", displayName = "On Piston Retract", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            })
    public void onPistonRetract(FlowContext ctx, FlowNode node) {
        executeLegacy("event:piston_retract", ctx, node);
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
