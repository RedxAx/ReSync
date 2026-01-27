package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RegionAdvancedNodes implements NodeCategory {

    private static final ConcurrentMap<String, ClipboardData> clipboards = new ConcurrentHashMap<>();

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("region_clone", (ctx, node) -> {
            String sourceClipboardId = ctx.getInputValue(node, "source_clipboard_id", String.class, "default");
            String newClipboardId = ctx.getInputValue(node, "new_clipboard_id", String.class, "cloned");

            ClipboardData sourceData = clipboards.get(sourceClipboardId);
            if (sourceData == null || sourceData.blocks == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Block[][][] newBlocks = new Block[sourceData.sizeY][sourceData.sizeX][sourceData.sizeZ];
            for (int y = 0; y < sourceData.sizeY; y++) {
                for (int x = 0; x < sourceData.sizeX; x++) {
                    System.arraycopy(sourceData.blocks[y][x], 0, newBlocks[y][x], 0, sourceData.sizeZ);
                }
            }

            clipboards.put(newClipboardId, new ClipboardData(newBlocks, sourceData.minX, sourceData.minY, sourceData.minZ, sourceData.sizeX, sourceData.sizeY, sourceData.sizeZ));
            ctx.triggerOutput("flow");
        });

        registry.register("region_save", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String filePath = ctx.getInputValue(node, "file_path", String.class, "");

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null || filePath.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable saveTask = () -> {
                try {
                    File file = new File(filePath);
                    file.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                        oos.writeObject(data);
                    }
                } catch (Exception ignored) {}
            };

            if (Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTaskAsynchronously(restudio.resync.ReSync.getInstance(), saveTask);
            } else {
                saveTask.run();
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_load", (ctx, node) -> {
            String filePath = ctx.getInputValue(node, "file_path", String.class, "");
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "loaded");

            if (filePath.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            File file = new File(filePath);
            if (!file.exists()) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable loadTask = () -> {
                try (FileInputStream fis = new FileInputStream(file); ObjectInputStream ois = new ObjectInputStream(fis)) {
                    ClipboardData data = (ClipboardData) ois.readObject();
                    clipboards.put(clipboardId, data);
                } catch (Exception ignored) {}
            };

            if (Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTaskAsynchronously(restudio.resync.ReSync.getInstance(), loadTask);
            } else {
                loadTask.run();
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_scale", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Double scaleX = ctx.getInputValue(node, "scale_x", Double.class, 1.0);
            Double scaleY = ctx.getInputValue(node, "scale_y", Double.class, 1.0);
            Double scaleZ = ctx.getInputValue(node, "scale_z", Double.class, 1.0);

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null || data.blocks == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int newSizeX = (int) Math.max(1, Math.round(data.sizeX * scaleX));
            int newSizeY = (int) Math.max(1, Math.round(data.sizeY * scaleY));
            int newSizeZ = (int) Math.max(1, Math.round(data.sizeZ * scaleZ));

            Block[][][] scaledBlocks = new Block[newSizeY][newSizeX][newSizeZ];

            Runnable scaleTask = () -> {
                for (int y = 0; y < newSizeY; y++) {
                    for (int x = 0; x < newSizeX; x++) {
                        for (int z = 0; z < newSizeZ; z++) {
                            int sourceX = (int) (x * data.sizeX / newSizeX);
                            int sourceY = (int) (y * data.sizeY / newSizeY);
                            int sourceZ = (int) (z * data.sizeZ / newSizeZ);
                            sourceX = Math.min(sourceX, data.sizeX - 1);
                            sourceY = Math.min(sourceY, data.sizeY - 1);
                            sourceZ = Math.min(sourceZ, data.sizeZ - 1);
                            scaledBlocks[y][x][z] = data.blocks[sourceY][sourceX][sourceZ];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(scaledBlocks, data.minX, data.minY, data.minZ, newSizeX, newSizeY, newSizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                scaleTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), scaleTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_flip", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String axisName = ctx.getInputValue(node, "axis", String.class, "x");

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null || data.blocks == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String axis = axisName.toLowerCase();
            Runnable flipTask = () -> {
                Block[][][] flippedBlocks = new Block[data.sizeY][data.sizeX][data.sizeZ];

                if (axis.equals("x")) {
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            System.arraycopy(data.blocks[y][data.sizeX - 1 - x], 0, flippedBlocks[y][x], 0, data.sizeZ);
                        }
                    }
                } else if (axis.equals("y")) {
                    for (int y = 0; y < data.sizeY; y++) {
                        flippedBlocks[y] = data.blocks[data.sizeY - 1 - y];
                    }
                } else {
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                flippedBlocks[y][x][z] = data.blocks[y][x][data.sizeZ - 1 - z];
                            }
                        }
                    }
                }

                clipboards.put(clipboardId, new ClipboardData(flippedBlocks, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                flipTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), flipTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_set", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable setTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(material);
                        }
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

        registry.register("region_set_pattern", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            @SuppressWarnings("unchecked")
            List<String> materialsList = ctx.getInputValue(node, "materials_list", List.class, null);

            if (minLoc == null || maxLoc == null || materialsList == null || materialsList.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            Material[] materials = new Material[materialsList.size()];
            for (int i = 0; i < materialsList.size(); i++) {
                materials[i] = Material.matchMaterial(materialsList.get(i).toUpperCase());
                if (materials[i] == null) {
                    ctx.triggerOutput("flow");
                    return;
                }
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable patternTask = () -> {
                int index = 0;
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(materials[index % materials.length]);
                            index++;
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                patternTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), patternTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_cylinder", (ctx, node) -> {
            Location centerLoc = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            Double height = ctx.getInputValue(node, "height", Double.class, 1.0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Boolean isFilled = ctx.getInputValue(node, "is_filled", Boolean.class, true);

            if (centerLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = centerLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int cx = centerLoc.getBlockX();
            int cy = centerLoc.getBlockY();
            int cz = centerLoc.getBlockZ();
            int r = radius.intValue();
            int h = height.intValue();

            Runnable cylinderTask = () -> {
                for (int y = 0; y < h; y++) {
                    for (int x = -r; x <= r; x++) {
                        for (int z = -r; z <= r; z++) {
                            double distance = Math.sqrt(x * x + z * z);
                            boolean shouldPlace = isFilled ? distance <= r : Math.abs(distance - r) < 1.0;
                            if (shouldPlace) {
                                world.getBlockAt(cx + x, cy + y, cz + z).setType(material);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                cylinderTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), cylinderTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_sphere", (ctx, node) -> {
            Location centerLoc = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Boolean isFilled = ctx.getInputValue(node, "is_filled", Boolean.class, true);

            if (centerLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = centerLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int cx = centerLoc.getBlockX();
            int cy = centerLoc.getBlockY();
            int cz = centerLoc.getBlockZ();
            int r = radius.intValue();

            Runnable sphereTask = () -> {
                for (int x = -r; x <= r; x++) {
                    for (int y = -r; y <= r; y++) {
                        for (int z = -r; z <= r; z++) {
                            double distance = Math.sqrt(x * x + y * y + z * z);
                            boolean shouldPlace = isFilled ? distance <= r : Math.abs(distance - r) < 1.0;
                            if (shouldPlace) {
                                world.getBlockAt(cx + x, cy + y, cz + z).setType(material);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                sphereTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), sphereTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_pyramid", (ctx, node) -> {
            Location baseCenterLoc = ctx.getInputValue(node, "base_center_location", Location.class, null);
            Integer size = ctx.getInputValue(node, "size", Integer.class, 5);
            Integer height = ctx.getInputValue(node, "height", Integer.class, 5);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Boolean isHollow = ctx.getInputValue(node, "is_hollow", Boolean.class, false);

            if (baseCenterLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = baseCenterLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int cx = baseCenterLoc.getBlockX();
            int cy = baseCenterLoc.getBlockY();
            int cz = baseCenterLoc.getBlockZ();

            Runnable pyramidTask = () -> {
                for (int y = 0; y < height; y++) {
                    int currentSize = size - y * 2;
                    if (currentSize <= 0) break;
                    int halfSize = currentSize / 2;

                    for (int x = -halfSize; x <= halfSize; x++) {
                        for (int z = -halfSize; z <= halfSize; z++) {
                            if (isHollow && (x != -halfSize && x != halfSize && z != -halfSize && z != halfSize)) {
                                continue;
                            }
                            world.getBlockAt(cx + x, cy + y, cz + z).setType(material);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                pyramidTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), pyramidTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_circle", (ctx, node) -> {
            Location centerLoc = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String axisName = ctx.getInputValue(node, "axis", String.class, "y");
            Integer thickness = ctx.getInputValue(node, "thickness", Integer.class, 1);

            if (centerLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = centerLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int cx = centerLoc.getBlockX();
            int cy = centerLoc.getBlockY();
            int cz = centerLoc.getBlockZ();
            int r = radius.intValue();
            String axis = axisName.toLowerCase();

            Runnable circleTask = () -> {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        for (int t = 0; t < thickness; t++) {
                            double distance = Math.sqrt(x * x + z * z);
                            if (Math.abs(distance - (r - t)) < 1.0) {
                                switch (axis) {
                                    case "x" -> world.getBlockAt(cx, cy + x, cz + z).setType(material);
                                    case "y" -> world.getBlockAt(cx + x, cy, cz + z).setType(material);
                                    case "z" -> world.getBlockAt(cx + x, cy + z, cz).setType(material);
                                }
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                circleTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), circleTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_walls_corners", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "corner1", new Location(world, minX, minY, minZ));
            ctx.setNodeOutput(nodeId, "corner2", new Location(world, maxX, minY, minZ));
            ctx.setNodeOutput(nodeId, "corner3", new Location(world, minX, maxY, minZ));
            ctx.setNodeOutput(nodeId, "corner4", new Location(world, maxX, maxY, minZ));
            ctx.setNodeOutput(nodeId, "corner5", new Location(world, minX, minY, maxZ));
            ctx.setNodeOutput(nodeId, "corner6", new Location(world, maxX, minY, maxZ));
            ctx.setNodeOutput(nodeId, "corner7", new Location(world, minX, maxY, maxZ));
            ctx.setNodeOutput(nodeId, "corner8", new Location(world, maxX, maxY, maxZ));
            ctx.triggerOutput("flow");
        });

        registry.register("region_center", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            double centerX = (minLoc.getX() + maxLoc.getX()) / 2.0;
            double centerY = (minLoc.getY() + maxLoc.getY()) / 2.0;
            double centerZ = (minLoc.getZ() + maxLoc.getZ()) / 2.0;

            Location centerLoc = new Location(world, centerX, centerY, centerZ);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "center_location", centerLoc);
            ctx.triggerOutput("flow");
        });

        registry.register("region_size", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int sizeX = Math.abs(maxLoc.getBlockX() - minLoc.getBlockX()) + 1;
            int sizeY = Math.abs(maxLoc.getBlockY() - minLoc.getBlockY()) + 1;
            int sizeZ = Math.abs(maxLoc.getBlockZ() - minLoc.getBlockZ()) + 1;

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "size_x", sizeX);
            ctx.setNodeOutput(nodeId, "size_y", sizeY);
            ctx.setNodeOutput(nodeId, "size_z", sizeZ);
            ctx.triggerOutput("flow");
        });

        registry.register("region_volume", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int sizeX = Math.abs(maxLoc.getBlockX() - minLoc.getBlockX()) + 1;
            int sizeY = Math.abs(maxLoc.getBlockY() - minLoc.getBlockY()) + 1;
            int sizeZ = Math.abs(maxLoc.getBlockZ() - minLoc.getBlockZ()) + 1;
            long volume = (long) sizeX * sizeY * sizeZ;

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "volume", volume);
            ctx.triggerOutput("flow");
        });

        registry.register("region_get_blocks", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            List<Block> blocksList = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        blocksList.add(world.getBlockAt(x, y, z));
                    }
                }
            }

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "blocks_list", blocksList);
            ctx.triggerOutput("flow");
        });

        registry.register("region_get_blocks_by_type", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            List<Block> blocksList = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == material) {
                            blocksList.add(block);
                        }
                    }
                }
            }

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "blocks_list", blocksList);
            ctx.triggerOutput("flow");
        });

        registry.register("region_replace_data", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String fromBlockData = ctx.getInputValue(node, "from_block_data", String.class, "");
            String toBlockData = ctx.getInputValue(node, "to_block_data", String.class, "");

            if (minLoc == null || maxLoc == null || fromBlockData.isEmpty() || toBlockData.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable replaceTask = () -> {
                var targetData = Bukkit.createBlockData(toBlockData);
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (block.getBlockData().getAsString().equals(fromBlockData)) {
                                block.setBlockData(targetData);
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

        registry.register("region_smooth", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer iterations = ctx.getInputValue(node, "iterations", Integer.class, 1);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable smoothTask = () -> {
                for (int iter = 0; iter < iterations; iter++) {
                    int sizeX = maxX - minX + 1;
                    int sizeZ = maxZ - minZ + 1;
                    int[][] heights = new int[sizeX][sizeZ];

                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            int sum = 0;
                            int count = 0;
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    int nx = x + dx;
                                    int nz = z + dz;
                                    if (nx >= 0 && nx < sizeX && nz >= 0 && nz < sizeZ) {
                                        sum += world.getHighestBlockAt(minX + nx, minZ + nz).getY();
                                        count++;
                                    }
                                }
                            }
                            heights[x][z] = sum / count;
                        }
                    }

                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            int currentY = world.getHighestBlockAt(minX + x, minZ + z).getY();
                            if (currentY < heights[x][z]) {
                                for (int y = currentY; y <= heights[x][z]; y++) {
                                    world.getBlockAt(minX + x, y, minZ + z).setType(Material.DIRT);
                                }
                                world.getBlockAt(minX + x, heights[x][z] + 1, minZ + z).setType(Material.GRASS_BLOCK);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                smoothTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), smoothTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_raise", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable raiseTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = maxY; y >= minY; y--) {
                            Block source = world.getBlockAt(x, y, z);
                            Block target = world.getBlockAt(x, Math.min(y + amount, world.getMaxHeight() - 1), z);
                            target.setType(source.getType());
                            target.setBlockData(source.getBlockData());
                        }
                        for (int y = minY; y < minY + amount; y++) {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                raiseTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), raiseTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_lower", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = minLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable lowerTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            Block source = world.getBlockAt(x, y, z);
                            Block target = world.getBlockAt(x, Math.max(y - amount, world.getMinHeight()), z);
                            target.setType(source.getType());
                            target.setBlockData(source.getBlockData());
                        }
                        for (int y = maxY; y > maxY - amount; y--) {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                lowerTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), lowerTask);
            }
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

    private static class ClipboardData implements java.io.Serializable {
        final String[][][] blockTypes;
        final String[][][] blockDataStrings;
        final int minX, minY, minZ;
        final int sizeX, sizeY, sizeZ;
        transient Block[][][] blocks;

        ClipboardData(Block[][][] blocks, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.blocks = blocks;
            this.blockTypes = new String[sizeY][sizeX][sizeZ];
            this.blockDataStrings = new String[sizeY][sizeX][sizeZ];
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;

            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        this.blockTypes[y][x][z] = blocks[y][x][z].getType().name();
                        this.blockDataStrings[y][x][z] = blocks[y][x][z].getBlockData().getAsString();
                    }
                }
            }
        }
    }
}
