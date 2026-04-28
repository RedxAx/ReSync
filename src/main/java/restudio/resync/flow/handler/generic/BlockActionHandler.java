package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class BlockActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public BlockActionHandler() {
        operations.put("block_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) return;
            Block block = location.getBlock();
            if (Bukkit.isPrimaryThread()) {
                block.setType(material);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> block.setType(material));
            }
        });

        operations.put("block_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            ctx.setOutput(node, "type", block.getType().name());
            ctx.setOutput(node, "data", block.getBlockData().getAsString());
        });

        operations.put("block_replace", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");
            Block block = location.getBlock();
            if (oldMaterialName != null && !oldMaterialName.isEmpty()) {
                Material oldMaterial = Material.matchMaterial(oldMaterialName.toUpperCase());
                if (oldMaterial != null && block.getType() != oldMaterial) return;
            }
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial != null) {
                if (Bukkit.isPrimaryThread()) {
                    block.setType(newMaterial);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> block.setType(newMaterial));
                }
            }
        });

        operations.put("block_fill", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) return;
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) return;
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());
            World world = minLoc.getWorld();
            if (world == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), fillTask);
            }
        });

        operations.put("block_replace_area", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) return;
            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");
            Material oldMaterial = oldMaterialName != null && !oldMaterialName.isEmpty() ?
                Material.matchMaterial(oldMaterialName.toUpperCase()) : null;
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) return;
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());
            World world = minLoc.getWorld();
            if (world == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), replaceTask);
            }
        });

        operations.put("block_break_naturally", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            Runnable breakTask = block::breakNaturally;
            if (Bukkit.isPrimaryThread()) {
                breakTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), breakTask);
            }
        });

        operations.put("block_drop_item", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "item", String.class, "STONE");
            if (location == null) return;
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) return;
            ItemStack item = new ItemStack(material);
            Runnable dropTask = () -> location.getWorld().dropItemNaturally(location, item);
            if (Bukkit.isPrimaryThread()) {
                dropTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), dropTask);
            }
        });

        operations.put("block_update", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            Runnable updateTask = () -> block.getState().update(true, true);
            if (Bukkit.isPrimaryThread()) {
                updateTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), updateTask);
            }
        });

        operations.put("block_set_biome", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String biomeName = ctx.getInputValue(node, "biome", String.class, "PLAINS");
            if (location == null) return;
            World world = location.getWorld();
            if (world == null) return;
            try {
                Biome biome = Biome.valueOf(biomeName.toUpperCase());
                Runnable biomeTask = () -> world.setBiome(location, biome);
                if (Bukkit.isPrimaryThread()) {
                    biomeTask.run();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), biomeTask);
                }
            } catch (IllegalArgumentException ignored) {
            }
        });

        operations.put("block_set_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            if (location == null) return;
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) return;
            Block block = location.getBlock();
            Runnable setTask = () -> block.setType(material);
            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_get_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            ctx.setOutput(node, "type", block.getType().name());
        });

        operations.put("block_set_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = ctx.getInputValue(node, "data", Map.class, null);
            if (dataMap == null) return;
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
                } catch (Exception ignored) {
                }
            }
            Runnable setTask = () -> block.setBlockData(blockData);
            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_get_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            ctx.setOutput(node, "data", blockData.getAsString());
        });

        operations.put("block_set_age", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer age = ctx.getInputValue(node, "age", Integer.class, 0);
            if (location == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_set_level", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
            if (location == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_set_rotation", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String rotationName = ctx.getInputValue(node, "rotation", String.class, "NORTH");
            if (location == null) return;
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            Runnable setTask = () -> {
                if (blockData instanceof Rotatable) {
                    try {
                        Rotatable rotation = (Rotatable) block.getBlockData();
                        rotation.setRotation(BlockFace.valueOf(rotationName.toUpperCase()));
                        block.setBlockData(rotation);
                    } catch (IllegalArgumentException ignored) {
                    }
                } else if (blockData instanceof Directional) {
                    try {
                        Directional directional = (Directional) block.getBlockData();
                        directional.setFacing(BlockFace.valueOf(rotationName.toUpperCase()));
                        block.setBlockData(directional);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            };
            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_set_face", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String faceName = ctx.getInputValue(node, "face", String.class, "NORTH");
            if (location == null) return;
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            Runnable setTask = () -> {
                if (blockData instanceof Directional) {
                    try {
                        Directional directional = (Directional) block.getBlockData();
                        directional.setFacing(BlockFace.valueOf(faceName.toUpperCase()));
                        block.setBlockData(directional);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            };
            if (Bukkit.isPrimaryThread()) {
                setTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_set_powered", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean powered = ctx.getInputValue(node, "powered", Boolean.class, false);
            if (location == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_set_lit", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean lit = ctx.getInputValue(node, "lit", Boolean.class, false);
            if (location == null) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), setTask);
            }
        });

        operations.put("block_interact", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            Runnable interactTask = () -> {
                BlockState state = block.getState();
                state.update(true, true);
            };
            if (Bukkit.isPrimaryThread()) {
                interactTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), interactTask);
            }
        });

        operations.put("block_break_particles", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String typeName = ctx.getInputValue(node, "type", String.class, "STONE");
            if (location == null) return;
            Material type = Material.matchMaterial(typeName.toUpperCase());
            if (type == null) return;
            Runnable particleTask = () -> location.getWorld().spawnParticle(Particle.BLOCK, location, 10, type.createBlockData());
            if (Bukkit.isPrimaryThread()) {
                particleTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), particleTask);
            }
        });

        operations.put("block_play_sound", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "type", String.class, "BLOCK_STONE_PLACE");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (location == null) return;
            World world = location.getWorld();
            if (world == null) return;
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase());
                Runnable soundTask = () -> world.playSound(location, sound, volume, pitch);
                if (Bukkit.isPrimaryThread()) {
                    soundTask.run();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), soundTask);
                }
            } catch (IllegalArgumentException ignored) {
            }
        });

        operations.put("block_physics", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            Runnable physicsTask = () -> block.getState().update(true, false);
            if (Bukkit.isPrimaryThread()) {
                physicsTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), physicsTask);
            }
        });

        operations.put("block_explode", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            World world = location.getWorld();
            if (world == null) return;
            Float power = ctx.getInputValue(node, "power", Float.class, 4.0f);
            Boolean fire = ctx.getInputValue(node, "fire", Boolean.class, false);
            Boolean breakBlocks = ctx.getInputValue(node, "break_blocks", Boolean.class, true);
            Runnable explodeTask = () -> world.createExplosion(location, power, fire, breakBlocks);
            if (Bukkit.isPrimaryThread()) {
                explodeTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), explodeTask);
            }
        });

        operations.put("block_raytrace", (ctx, node) -> {
            Location startLocation = ctx.getInputValue(node, "start_location", Location.class, null);
            Vector direction = ctx.getInputValue(node, "direction_vector", Vector.class, null);
            Double maxDistance = ctx.getInputValue(node, "max_distance", Double.class, 50.0);
            if (startLocation == null || direction == null) return;
            World world = startLocation.getWorld();
            if (world == null) return;
            var result = world.rayTraceBlocks(startLocation, direction.normalize(), maxDistance);
            if (result != null) {
                Block hitBlock = result.getHitBlock();
                Location hitLocation = result.getHitPosition().toLocation(world);
                double distance = startLocation.distance(hitLocation);
                if (hitBlock != null) {
                    ctx.setOutput(node, "hit_block", hitBlock);
                }
                ctx.setOutput(node, "hit_location", hitLocation);
                ctx.setOutput(node, "distance", distance);
            }
        });

        operations.put("block_offset", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Integer offsetX = ctx.getInputValue(node, "offset_x", Integer.class, 0);
            Integer offsetY = ctx.getInputValue(node, "offset_y", Integer.class, 0);
            Integer offsetZ = ctx.getInputValue(node, "offset_z", Integer.class, 0);
            Block block = location.getBlock().getRelative(offsetX, offsetY, offsetZ);
            ctx.setOutput(node, "block", block);
        });

        operations.put("block_sign_text", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Sign)) return;
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
                Bukkit.getScheduler().runTask(ReSync.getInstance(), signTask);
            }
        });

        operations.put("block_container_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) return;
            Inventory inventory = container.getInventory();
            ctx.setOutput(node, "items_list", inventory.getContents());
        });

        operations.put("block_container_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) return;
            @SuppressWarnings("unchecked")
            List<ItemStack> itemsList = ctx.getInputValue(node, "items_list", List.class, null);
            if (itemsList == null) return;
            Inventory inventory = container.getInventory();
            Runnable containerTask = () -> inventory.setContents(itemsList.toArray(new ItemStack[0]));
            if (Bukkit.isPrimaryThread()) {
                containerTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), containerTask);
            }
        });

        operations.put("block_container_add", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) return;
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item == null) return;
            Inventory inventory = container.getInventory();
            Runnable containerTask = () -> inventory.addItem(item);
            if (Bukkit.isPrimaryThread()) {
                containerTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), containerTask);
            }
        });

        operations.put("block_spawn_falling", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) return;
            World world = location.getWorld();
            if (world == null) return;
            String materialName = ctx.getInputValue(node, "material_type", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) return;
            FallingBlock fallingBlock = world.spawnFallingBlock(location, material.createBlockData());
            ctx.setOutput(node, "falling_block_entity", fallingBlock);
        });

        operations.put("block_break_naturally_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);
            Collection<ItemStack> drops;
            if (Bukkit.isPrimaryThread()) {
                drops = block.getDrops(tool);
            } else {
                drops = new CopyOnWriteArrayList<>();
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> drops.addAll(block.getDrops(tool)));
            }
            block.setType(Material.AIR);
            ctx.setOutput(node, "dropped_items_list", drops.toArray(new ItemStack[0]));
        });

        operations.put("block_break_instantly", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            Runnable breakTask = () -> block.setType(Material.AIR);
            if (Bukkit.isPrimaryThread()) {
                breakTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), breakTask);
            }
        });

        operations.put("block_get_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);
            Collection<ItemStack> drops;
            if (Bukkit.isPrimaryThread()) {
                drops = block.getDrops(tool);
            } else {
                drops = new CopyOnWriteArrayList<>();
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> drops.addAll(block.getDrops(tool)));
            }
            ctx.setOutput(node, "dropped_items_list", drops.toArray(new ItemStack[0]));
        });

        operations.put("block_get_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            BlockState state = block.getState();
            ctx.setOutput(node, "state_data", state.getBlockData().getAsString());
        });

        operations.put("block_set_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            String stateData = ctx.getInputValue(node, "state_data", String.class, "");
            if (stateData.isEmpty()) return;
            Block block = location.getBlock();
            BlockData blockData = Bukkit.createBlockData(stateData);
            Runnable stateTask = () -> block.setBlockData(blockData);
            if (Bukkit.isPrimaryThread()) {
                stateTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), stateTask);
            }
        });

        operations.put("block_is_solid", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) return;
            Block block = location.getBlock();
            boolean isSolid = block.getType().isSolid();
            ctx.setOutput(node, "is_solid", isSolid);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("BlockActionHandler", this);
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
}
