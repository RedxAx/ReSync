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
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.Map;

public class BlockNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
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

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
