package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
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

public class BlockNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("block_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            if (Bukkit.isPrimaryThread()) {
                block.setType(material);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> block.setType(material));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "type", block.getType().name());
            ctx.setNodeOutput(nodeId, "data", block.getBlockData().getAsString());
            ctx.triggerOutput("flow");
        });

        registry.register("block_replace", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");

            Block block = location.getBlock();
            if (oldMaterialName != null && !oldMaterialName.isEmpty()) {
                Material oldMaterial = Material.matchMaterial(oldMaterialName.toUpperCase());
                if (oldMaterial != null && block.getType() != oldMaterial) {
                    ctx.triggerOutput("flow");
                    return;
                }
            }

            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial != null) {
                if (Bukkit.isPrimaryThread()) {
                    block.setType(newMaterial);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> block.setType(newMaterial));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_fill", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable fillTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            block.setType(material);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                fillTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), fillTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_replace_area", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");

            Material oldMaterial = oldMaterialName != null && !oldMaterialName.isEmpty() ? 
                Material.matchMaterial(oldMaterialName.toUpperCase()) : null;
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable replaceTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (oldMaterial == null || block.getType() == oldMaterial) {
                                block.setType(newMaterial);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                replaceTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), replaceTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_break_naturally", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String causeName = ctx.getInputValue(node, "cause", String.class, "PLAYER");
            Block block = location.getBlock();

            Runnable breakTask = block::breakNaturally;

            if (Bukkit.isPrimaryThread()) {
                breakTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), breakTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_drop_item", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "item", String.class, "STONE");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            ItemStack item = new ItemStack(material);

            Runnable dropTask = () -> {
                location.getWorld().dropItemNaturally(location, item);
            };

            if (Bukkit.isPrimaryThread()) {
                dropTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), dropTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_update", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            Runnable updateTask = () -> {
                block.getState().update(true, true);
            };

            if (Bukkit.isPrimaryThread()) {
                updateTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), updateTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_biome", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String biomeName = ctx.getInputValue(node, "biome", String.class, "PLAINS");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = location.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            try {
                org.bukkit.block.Biome biome = org.bukkit.block.Biome.valueOf(biomeName.toUpperCase());
                Runnable biomeTask = () -> world.setBiome(location, biome);

                if (Bukkit.isPrimaryThread()) {
                    biomeTask.run();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), biomeTask);
                }
            } catch (IllegalArgumentException ignored) {}
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            Runnable setTask = () -> {
                block.setType(material);
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_get_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "type", block.getType().name());
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = ctx.getInputValue(node, "data", Map.class, null);
            if (dataMap == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                try {
                    String key = entry.getKey().toLowerCase();
                    String value = entry.getValue().toString();

                    switch (blockData) {
                        case Waterlogged waterlogged when key.equals("waterlogged") ->
                                waterlogged.setWaterlogged(Boolean.parseBoolean(value));
                        case Powerable powerable when key.equals("powered") ->
                                powerable.setPowered(Boolean.parseBoolean(value));
                        case Lightable lightable when key.equals("lit") ->
                                lightable.setLit(Boolean.parseBoolean(value));
                        case Openable openable when key.equals("open") ->
                                openable.setOpen(Boolean.parseBoolean(value));
                        case Directional directional when key.equals("facing") ->
                                directional.setFacing(BlockFace.valueOf(value.toUpperCase()));
                        case Rotatable rotatable when key.equals("rotation") ->
                                rotatable.setRotation(BlockFace.valueOf(value.toUpperCase()));
                        case Ageable ageable when key.equals("age") ->
                                ageable.setAge(Integer.parseInt(value));
                        case Levelled levelled when key.equals("level") -> levelled.setLevel(Integer.parseInt(value));
                        default -> {
                        }
                    }
                } catch (Exception ignored) {}
            }

            Runnable setTask = () -> block.setBlockData(blockData);

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_get_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            String nodeId = findNodeId(ctx, node);
            
            String dataString = blockData.getAsString();
            ctx.setNodeOutput(nodeId, "data", dataString);
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_age", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer age = ctx.getInputValue(node, "age", Integer.class, 0);

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                if (blockData instanceof Sapling) {
                    ((Sapling) block.getBlockData()).setStage(Math.min(1, Math.max(0, age)));
                } else if (blockData instanceof Leaves) {
                    ((Leaves) block.getBlockData()).setDistance((short) Math.min(7, Math.max(1, age)));
                } else if (blockData instanceof Farmland) {
                    ((Farmland) block.getBlockData()).setMoisture(Math.min(7, Math.max(0, age)));
                }
                block.setBlockData(blockData);
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_level", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                switch (blockData) {
                    case Levelled levelled -> {
                        ((Levelled) block.getBlockData()).setLevel(Math.min(levelled.getMaximumLevel(), Math.max(0, level)));
                        block.setBlockData(blockData);
                    }
                    case Cake cake -> {
                        ((Cake) block.getBlockData()).setBites(Math.min(cake.getMaximumBites(), Math.max(0, level)));
                        block.setBlockData(blockData);
                    }
                    case Slab slab1 -> {
                        Slab slab = (Slab) block.getBlockData();
                        if (level > 0) {
                            slab.setType(Slab.Type.TOP);
                        } else {
                            slab.setType(Slab.Type.BOTTOM);
                        }
                        block.setBlockData(slab);
                    }
                    default -> {
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_rotation", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String rotationName = ctx.getInputValue(node, "rotation", String.class, "NORTH");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                if (blockData instanceof Rotatable) {
                    try {
                        Rotatable rotation = (Rotatable) block.getBlockData();
                        rotation.setRotation(BlockFace.valueOf(rotationName.toUpperCase()));
                        block.setBlockData(rotation);
                    } catch (IllegalArgumentException ignored) {}
                } else if (blockData instanceof Directional) {
                    try {
                        Directional directional = (Directional) block.getBlockData();
                        directional.setFacing(BlockFace.valueOf(rotationName.toUpperCase()));
                        block.setBlockData(directional);
                    } catch (IllegalArgumentException ignored) {}
                }
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_face", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String faceName = ctx.getInputValue(node, "face", String.class, "NORTH");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                if (blockData instanceof Directional) {
                    try {
                        Directional directional = (Directional) block.getBlockData();
                        directional.setFacing(BlockFace.valueOf(faceName.toUpperCase()));
                        block.setBlockData(directional);
                    } catch (IllegalArgumentException ignored) {}
                }
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_powered", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean powered = ctx.getInputValue(node, "powered", Boolean.class, false);

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                if (blockData instanceof Powerable) {
                    ((Powerable) block.getBlockData()).setPowered(powered);
                    block.setBlockData(blockData);
                }
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_lit", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean lit = ctx.getInputValue(node, "lit", Boolean.class, false);

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();

            Runnable setTask = () -> {
                if (blockData instanceof Lightable) {
                    ((Lightable) block.getBlockData()).setLit(lit);
                    block.setBlockData(blockData);
                }
            };

            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), setTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_interact", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            Runnable interactTask = () -> {
                BlockState state = block.getState();
                state.update(true, true);
            };

            if (Bukkit.isPrimaryThread()) {
                interactTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), interactTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_break_particles", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String typeName = ctx.getInputValue(node, "type", String.class, "STONE");

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material type = Material.matchMaterial(typeName.toUpperCase());
            if (type == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable particleTask = () -> {
                location.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, location, 10, type.createBlockData());
            };

            if (Bukkit.isPrimaryThread()) {
                particleTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), particleTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_play_sound", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "type", String.class, "BLOCK_STONE_PLACE");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);

            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = location.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            try {
                org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.toUpperCase());
                Runnable soundTask = () -> {
                    world.playSound(location, sound, volume, pitch);
                };

                if (Bukkit.isPrimaryThread()) {
                    soundTask.run();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), soundTask);
                }
            } catch (IllegalArgumentException ignored) {}
            ctx.triggerOutput("flow");
        });

        registry.register("block_physics", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            Runnable physicsTask = () -> {
                block.getState().update(true, false);
            };

            if (Bukkit.isPrimaryThread()) {
                physicsTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), physicsTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_explode", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = location.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Float power = ctx.getInputValue(node, "power", Float.class, 4.0f);
            Boolean fire = ctx.getInputValue(node, "fire", Boolean.class, false);
            Boolean breakBlocks = ctx.getInputValue(node, "break_blocks", Boolean.class, true);

            Runnable explodeTask = () -> {
                world.createExplosion(location, power, fire, breakBlocks);
            };

            if (Bukkit.isPrimaryThread()) {
                explodeTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), explodeTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_raytrace", (ctx, node) -> {
            Location startLocation = ctx.getInputValue(node, "start_location", Location.class, null);
            Vector direction = ctx.getInputValue(node, "direction_vector", Vector.class, null);
            Double maxDistance = ctx.getInputValue(node, "max_distance", Double.class, 50.0);

            if (startLocation == null || direction == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = startLocation.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            var result = world.rayTraceBlocks(startLocation, direction.normalize(), maxDistance);
            String nodeId = findNodeId(ctx, node);

            if (result != null) {
                Block hitBlock = result.getHitBlock();
                Location hitLocation = result.getHitPosition().toLocation(world);
                double distance = startLocation.distance(hitLocation);

                if (hitBlock != null) {
                    ctx.setNodeOutput(nodeId, "hit_block", hitBlock);
                }
                ctx.setNodeOutput(nodeId, "hit_location", hitLocation);
                ctx.setNodeOutput(nodeId, "distance", distance);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_offset", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Integer offsetX = ctx.getInputValue(node, "offset_x", Integer.class, 0);
            Integer offsetY = ctx.getInputValue(node, "offset_y", Integer.class, 0);
            Integer offsetZ = ctx.getInputValue(node, "offset_z", Integer.class, 0);

            Block block = location.getBlock().getRelative(offsetX, offsetY, offsetZ);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "block", block);
            ctx.triggerOutput("flow");
        });

        registry.register("block_sign_text", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockState state = block.getState();

            if (!(state instanceof Sign)) {
                ctx.triggerOutput("flow");
                return;
            }

            Sign sign = (Sign) state;
            String line1 = ctx.getInputValue(node, "line1", String.class, "");
            String line2 = ctx.getInputValue(node, "line2", String.class, "");
            String line3 = ctx.getInputValue(node, "line3", String.class, "");
            String line4 = ctx.getInputValue(node, "line4", String.class, "");

            Runnable signTask = () -> {
                sign.setLine(0, line1);
                sign.setLine(1, line2);
                sign.setLine(2, line3);
                sign.setLine(3, line4);
                sign.update(true, true);
            };

            if (Bukkit.isPrimaryThread()) {
                signTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), signTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_container_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockState state = block.getState();

            if (!(state instanceof Container container)) {
                ctx.triggerOutput("flow");
                return;
            }

            Inventory inventory = container.getInventory();
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "items_list", inventory.getContents());
            ctx.triggerOutput("flow");
        });

        registry.register("block_container_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockState state = block.getState();

            if (!(state instanceof Container container)) {
                ctx.triggerOutput("flow");
                return;
            }

            @SuppressWarnings("unchecked")
            java.util.List<ItemStack> itemsList = ctx.getInputValue(node, "items_list", java.util.List.class, null);
            if (itemsList == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Inventory inventory = container.getInventory();
            Runnable containerTask = () -> {
                inventory.setContents(itemsList.toArray(new ItemStack[0]));
            };

            if (Bukkit.isPrimaryThread()) {
                containerTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), containerTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_container_add", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockState state = block.getState();

            if (!(state instanceof Container container)) {
                ctx.triggerOutput("flow");
                return;
            }

            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Inventory inventory = container.getInventory();
            Runnable containerTask = () -> {
                inventory.addItem(item);
            };

            if (Bukkit.isPrimaryThread()) {
                containerTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), containerTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_spawn_falling", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = location.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String materialName = ctx.getInputValue(node, "material_type", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            FallingBlock fallingBlock = world.spawnFallingBlock(location, material.createBlockData());
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "falling_block_entity", fallingBlock);
            ctx.triggerOutput("flow");
        });

        registry.register("block_break_naturally_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);

            java.util.Collection<ItemStack> drops;
            if (Bukkit.isPrimaryThread()) {
                drops = block.getDrops(tool);
            } else {
                drops = new java.util.concurrent.CopyOnWriteArrayList<>();
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                    drops.addAll(block.getDrops(tool));
                });
            }

            block.setType(org.bukkit.Material.AIR);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "dropped_items_list", drops.toArray(new ItemStack[0]));
            ctx.triggerOutput("flow");
        });

        registry.register("block_break_instantly", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            Runnable breakTask = () -> {
                block.setType(org.bukkit.Material.AIR);
            };

            if (Bukkit.isPrimaryThread()) {
                breakTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), breakTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_get_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);

            java.util.Collection<ItemStack> drops;
            if (Bukkit.isPrimaryThread()) {
                drops = block.getDrops(tool);
            } else {
                drops = new java.util.concurrent.CopyOnWriteArrayList<>();
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                    drops.addAll(block.getDrops(tool));
                });
            }

            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "dropped_items_list", drops.toArray(new ItemStack[0]));
            ctx.triggerOutput("flow");
        });

        registry.register("block_get_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockState state = block.getState();
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "state_data", state.getBlockData().getAsString());
            ctx.triggerOutput("flow");
        });

        registry.register("block_set_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String stateData = ctx.getInputValue(node, "state_data", String.class, "");
            if (stateData.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            BlockData blockData = Bukkit.createBlockData(stateData);

            Runnable stateTask = () -> {
                block.setBlockData(blockData);
            };

            if (Bukkit.isPrimaryThread()) {
                stateTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), stateTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("block_is_solid", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block block = location.getBlock();
            boolean isSolid = block.getType().isSolid();
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "is_solid", isSolid);
            ctx.triggerOutput("flow");
        });
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (BlockNodes.class) {
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
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "block_set", displayName = "Set Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSet(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set", ctx, node);
    }

    @DefineNode(id = "block_get", displayName = "Get Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "type", dataType = FlowType.STRING),
                    @FlowPin(name = "data", dataType = FlowType.STRING)
            })
    public void blockGet(FlowContext ctx, FlowNode node) {
        executeLegacy("block_get", ctx, node);
    }

    @DefineNode(id = "block_replace", displayName = "Replace Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "old_type", dataType = FlowType.STRING),
                    @FlowPin(name = "new_type", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockReplace(FlowContext ctx, FlowNode node) {
        executeLegacy("block_replace", ctx, node);
    }

    @DefineNode(id = "block_fill", displayName = "Fill Area", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockFill(FlowContext ctx, FlowNode node) {
        executeLegacy("block_fill", ctx, node);
    }

    @DefineNode(id = "block_replace_area", displayName = "Replace Area", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "old_type", dataType = FlowType.STRING),
                    @FlowPin(name = "new_type", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockReplaceArea(FlowContext ctx, FlowNode node) {
        executeLegacy("block_replace_area", ctx, node);
    }

    @DefineNode(id = "block_break_naturally", displayName = "Break Naturally", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "cause", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockBreakNaturally(FlowContext ctx, FlowNode node) {
        executeLegacy("block_break_naturally", ctx, node);
    }

    @DefineNode(id = "block_drop_item", displayName = "Drop Item", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "item", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockDropItem(FlowContext ctx, FlowNode node) {
        executeLegacy("block_drop_item", ctx, node);
    }

    @DefineNode(id = "block_update", displayName = "Update Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockUpdate(FlowContext ctx, FlowNode node) {
        executeLegacy("block_update", ctx, node);
    }

    @DefineNode(id = "block_set_biome", displayName = "Set Biome", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "biome", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetBiome(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_biome", ctx, node);
    }

    @DefineNode(id = "block_set_type", displayName = "Set Block Type", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetType(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_type", ctx, node);
    }

    @DefineNode(id = "block_get_type", displayName = "Get Block Type", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "type", dataType = FlowType.STRING)
            })
    public void blockGetType(FlowContext ctx, FlowNode node) {
        executeLegacy("block_get_type", ctx, node);
    }

    @DefineNode(id = "block_set_data", displayName = "Set Block Data", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "data", dataType = FlowType.JSON_OBJECT)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetData(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_data", ctx, node);
    }

    @DefineNode(id = "block_get_data", displayName = "Get Block Data", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "data", dataType = FlowType.STRING)
            })
    public void blockGetData(FlowContext ctx, FlowNode node) {
        executeLegacy("block_get_data", ctx, node);
    }

    @DefineNode(id = "block_set_age", displayName = "Set Block Age", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "age", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetAge(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_age", ctx, node);
    }

    @DefineNode(id = "block_set_level", displayName = "Set Block Level", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "level", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetLevel(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_level", ctx, node);
    }

    @DefineNode(id = "block_set_rotation", displayName = "Set Rotation", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "rotation", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetRotation(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_rotation", ctx, node);
    }

    @DefineNode(id = "block_set_face", displayName = "Set Face", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "face", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetFace(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_face", ctx, node);
    }

    @DefineNode(id = "block_set_powered", displayName = "Set Powered", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "powered", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetPowered(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_powered", ctx, node);
    }

    @DefineNode(id = "block_set_lit", displayName = "Set Lit", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "lit", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetLit(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_lit", ctx, node);
    }

    @DefineNode(id = "block_interact", displayName = "Interact Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockInteract(FlowContext ctx, FlowNode node) {
        executeLegacy("block_interact", ctx, node);
    }

    @DefineNode(id = "block_break_particles", displayName = "Break Particles", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "type", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockBreakParticles(FlowContext ctx, FlowNode node) {
        executeLegacy("block_break_particles", ctx, node);
    }

    @DefineNode(id = "block_play_sound", displayName = "Play Block Sound", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "type", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockPlaySound(FlowContext ctx, FlowNode node) {
        executeLegacy("block_play_sound", ctx, node);
    }

    @DefineNode(id = "block_physics", displayName = "Block Physics", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockPhysics(FlowContext ctx, FlowNode node) {
        executeLegacy("block_physics", ctx, node);
    }

    @DefineNode(id = "block_explode", displayName = "Block Explode", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "power", dataType = FlowType.NUMBER),
                    @FlowPin(name = "fire", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "break_blocks", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockExplode(FlowContext ctx, FlowNode node) {
        executeLegacy("block_explode", ctx, node);
    }

    @DefineNode(id = "block_raytrace", displayName = "Block Raytrace", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "start_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "direction_vector", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_distance", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "hit_block", dataType = FlowType.ANY),
                    @FlowPin(name = "hit_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "distance", dataType = FlowType.NUMBER)
            })
    public void blockRaytrace(FlowContext ctx, FlowNode node) {
        executeLegacy("block_raytrace", ctx, node);
    }

    @DefineNode(id = "block_offset", displayName = "Block Offset", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "offset_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "offset_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "offset_z", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "block", dataType = FlowType.ANY)})
    public void blockOffset(FlowContext ctx, FlowNode node) {
        executeLegacy("block_offset", ctx, node);
    }

    @DefineNode(id = "block_sign_text", displayName = "Block Sign Text", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "line1", dataType = FlowType.STRING),
                    @FlowPin(name = "line2", dataType = FlowType.STRING),
                    @FlowPin(name = "line3", dataType = FlowType.STRING),
                    @FlowPin(name = "line4", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSignText(FlowContext ctx, FlowNode node) {
        executeLegacy("block_sign_text", ctx, node);
    }

    @DefineNode(id = "block_container_get", displayName = "Container Get", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "block_location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "items_list", dataType = FlowType.LIST)})
    public void blockContainerGet(FlowContext ctx, FlowNode node) {
        executeLegacy("block_container_get", ctx, node);
    }

    @DefineNode(id = "block_container_set", displayName = "Container Set", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "items_list", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockContainerSet(FlowContext ctx, FlowNode node) {
        executeLegacy("block_container_set", ctx, node);
    }

    @DefineNode(id = "block_container_add", displayName = "Container Add", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockContainerAdd(FlowContext ctx, FlowNode node) {
        executeLegacy("block_container_add", ctx, node);
    }

    @DefineNode(id = "block_spawn_falling", displayName = "Spawn Falling Block", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material_type", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "falling_block_entity", dataType = FlowType.ENTITY)})
    public void blockSpawnFalling(FlowContext ctx, FlowNode node) {
        executeLegacy("block_spawn_falling", ctx, node);
    }

    @DefineNode(id = "block_break_naturally_drops", displayName = "Break Naturally", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "tool_item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "dropped_items_list", dataType = FlowType.LIST)})
    public void blockBreakNaturallyDrops(FlowContext ctx, FlowNode node) {
        executeLegacy("block_break_naturally_drops", ctx, node);
    }

    @DefineNode(id = "block_break_instantly", displayName = "Break Instantly", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockBreakInstantly(FlowContext ctx, FlowNode node) {
        executeLegacy("block_break_instantly", ctx, node);
    }

    @DefineNode(id = "block_get_drops", displayName = "Get Drops", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "tool_item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "dropped_items_list", dataType = FlowType.LIST)})
    public void blockGetDrops(FlowContext ctx, FlowNode node) {
        executeLegacy("block_get_drops", ctx, node);
    }

    @DefineNode(id = "block_get_state", displayName = "Get State", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "block_location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "state_data", dataType = FlowType.STRING)})
    public void blockGetState(FlowContext ctx, FlowNode node) {
        executeLegacy("block_get_state", ctx, node);
    }

    @DefineNode(id = "block_set_state", displayName = "Set State", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "block_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "state_data", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void blockSetState(FlowContext ctx, FlowNode node) {
        executeLegacy("block_set_state", ctx, node);
    }

    @DefineNode(id = "block_is_solid", displayName = "Is Solid", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "block_location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "is_solid", dataType = FlowType.BOOLEAN)})
    public void blockIsSolid(FlowContext ctx, FlowNode node) {
        executeLegacy("block_is_solid", ctx, node);
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
