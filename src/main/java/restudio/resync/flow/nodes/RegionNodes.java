package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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

public class RegionNodes {

    private static final Map<String, ClipboardData> clipboards = new ConcurrentHashMap<>();
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("region_copy", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");

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

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;

            Block[][][] blocks = new Block[sizeY][sizeX][sizeZ];

            Runnable copyTask = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            blocks[y][x][z] = world.getBlockAt(minX + x, minY + y, minZ + z);
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(blocks, minX, minY, minZ, sizeX, sizeY, sizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                copyTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), copyTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_cut", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");

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

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;

            Block[][][] blocks = new Block[sizeY][sizeX][sizeZ];

            Runnable cutTask = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                            blocks[y][x][z] = block;
                            block.setType(Material.AIR);
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(blocks, minX, minY, minZ, sizeX, sizeY, sizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                cutTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), cutTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_paste", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Location pasteLoc = ctx.getInputValue(node, "location", Location.class, null);

            if (pasteLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) {
                ctx.triggerOutput("flow");
                return;
            }

            World world = pasteLoc.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable pasteTask = () -> {
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            Block source = data.blocks[y][x][z];
                            Block target = world.getBlockAt(pasteLoc.getBlockX() + x, pasteLoc.getBlockY() + y, pasteLoc.getBlockZ() + z);
                            target.setType(source.getType());
                            target.setBlockData(source.getBlockData());
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                pasteTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), pasteTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_rotate", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Integer rotations = ctx.getInputValue(node, "rotations", Integer.class, 90);

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) {
                ctx.triggerOutput("flow");
                return;
            }

            int normalizedRotations = ((rotations / 90) % 4 + 4) % 4;
            if (normalizedRotations == 0) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable rotateTask = () -> {
                Block[][][] rotatedBlocks;
                int newSizeX, newSizeZ;

                if (normalizedRotations == 2) {
                    rotatedBlocks = new Block[data.sizeY][data.sizeX][data.sizeZ];
                    newSizeX = data.sizeX;
                    newSizeZ = data.sizeZ;

                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                rotatedBlocks[y][x][z] = data.blocks[y][data.sizeX - 1 - x][data.sizeZ - 1 - z];
                            }
                        }
                    }
                } else {
                    newSizeX = data.sizeZ;
                    newSizeZ = data.sizeX;
                    rotatedBlocks = new Block[data.sizeY][newSizeX][newSizeZ];

                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < newSizeX; x++) {
                            for (int z = 0; z < newSizeZ; z++) {
                                if (normalizedRotations == 1) {
                                    rotatedBlocks[y][x][z] = data.blocks[y][data.sizeZ - 1 - z][x];
                                } else {
                                    rotatedBlocks[y][x][z] = data.blocks[y][z][data.sizeX - 1 - x];
                                }
                            }
                        }
                    }
                }

                clipboards.put(clipboardId, new ClipboardData(rotatedBlocks, data.minX, data.minY, data.minZ, newSizeX, data.sizeY, newSizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                rotateTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), rotateTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_mirror", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String axisName = ctx.getInputValue(node, "axis", String.class, "x");

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String axis = axisName.toLowerCase();
            Runnable mirrorTask = () -> {
                Block[][][] mirroredBlocks;

                if (axis.equals("x")) {
                    mirroredBlocks = new Block[data.sizeY][data.sizeX][data.sizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                mirroredBlocks[y][x][z] = data.blocks[y][data.sizeX - 1 - x][z];
                            }
                        }
                    }
                } else if (axis.equals("y")) {
                    mirroredBlocks = new Block[data.sizeY][data.sizeX][data.sizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                mirroredBlocks[y][x][z] = data.blocks[data.sizeY - 1 - y][x][z];
                            }
                        }
                    }
                } else {
                    mirroredBlocks = new Block[data.sizeY][data.sizeX][data.sizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                mirroredBlocks[y][x][z] = data.blocks[y][x][data.sizeZ - 1 - z];
                            }
                        }
                    }
                }

                clipboards.put(clipboardId, new ClipboardData(mirroredBlocks, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            if (Bukkit.isPrimaryThread()) {
                mirrorTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), mirrorTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_clear", (ctx, node) -> {
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

            Runnable clearTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            block.setType(Material.AIR);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                clearTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), clearTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_replace", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String oldMaterialName = ctx.getInputValue(node, "old_material", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material oldMaterial = oldMaterialName != null && !oldMaterialName.isEmpty() ? 
                Material.matchMaterial(oldMaterialName.toUpperCase()) : null;
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) {
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

        registry.register("region_set_air", (ctx, node) -> {
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

            Runnable airTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            block.setType(Material.AIR);
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                airTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), airTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_count_blocks", (ctx, node) -> {
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

            final int[] count = {0};
            Runnable countTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            if (world.getBlockAt(x, y, z).getType() == material) {
                                count[0]++;
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                countTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), countTask);
            }

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "count", count[0]);
            ctx.triggerOutput("flow");
        });

        registry.register("region_distribute", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            
            @SuppressWarnings("unchecked")
            java.util.List<String> patternList = ctx.getInputValue(node, "pattern_list", java.util.List.class, null);

            if (minLoc == null || maxLoc == null || patternList == null || patternList.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            Material[] pattern = new Material[patternList.size()];
            for (int i = 0; i < patternList.size(); i++) {
                pattern[i] = Material.matchMaterial(patternList.get(i).toUpperCase());
                if (pattern[i] == null) {
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

            Runnable distributeTask = () -> {
                int index = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block block = world.getBlockAt(x, y, z);
                            block.setType(pattern[index % pattern.length]);
                            index++;
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                distributeTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), distributeTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_outline", (ctx, node) -> {
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

            Runnable outlineTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isEdge = (x == minX || x == maxX) || (y == minY || y == maxY) || (z == minZ || z == maxZ);
                            if (isEdge) {
                                Block block = world.getBlockAt(x, y, z);
                                block.setType(material);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                outlineTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), outlineTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_walls", (ctx, node) -> {
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

            Runnable wallsTask = () -> {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isWall = (x == minX || x == maxX) || (z == minZ || z == maxZ);
                            if (isWall) {
                                Block block = world.getBlockAt(x, y, z);
                                block.setType(material);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                wallsTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), wallsTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("region_hollow", (ctx, node) -> {
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

            Runnable hollowTask = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isEdge = (x == minX || x == maxX) || (y == minY || y == maxY) || (z == minZ || z == maxZ);
                            Block block = world.getBlockAt(x, y, z);
                            if (isEdge) {
                                block.setType(material);
                            } else {
                                block.setType(Material.AIR);
                            }
                        }
                    }
                }
            };

            if (Bukkit.isPrimaryThread()) {
                hollowTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), hollowTask);
            }
            ctx.triggerOutput("flow");
        });
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (RegionNodes.class) {
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

    @DefineNode(id = "region_copy", displayName = "Copy Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "clipboard_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionCopy(FlowContext ctx, FlowNode node) {
        executeLegacy("region_copy", ctx, node);
    }

    @DefineNode(id = "region_cut", displayName = "Cut Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "clipboard_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionCut(FlowContext ctx, FlowNode node) {
        executeLegacy("region_cut", ctx, node);
    }

    @DefineNode(id = "region_paste", displayName = "Paste Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "clipboard_id", dataType = FlowType.STRING),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionPaste(FlowContext ctx, FlowNode node) {
        executeLegacy("region_paste", ctx, node);
    }

    @DefineNode(id = "region_rotate", displayName = "Rotate Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "clipboard_id", dataType = FlowType.STRING),
                    @FlowPin(name = "rotations", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionRotate(FlowContext ctx, FlowNode node) {
        executeLegacy("region_rotate", ctx, node);
    }

    @DefineNode(id = "region_mirror", displayName = "Mirror Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "clipboard_id", dataType = FlowType.STRING),
                    @FlowPin(name = "axis", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionMirror(FlowContext ctx, FlowNode node) {
        executeLegacy("region_mirror", ctx, node);
    }

    @DefineNode(id = "region_clear", displayName = "Clear Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionClear(FlowContext ctx, FlowNode node) {
        executeLegacy("region_clear", ctx, node);
    }

    @DefineNode(id = "region_replace", displayName = "Replace Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "old_material", dataType = FlowType.STRING),
                    @FlowPin(name = "new_material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionReplace(FlowContext ctx, FlowNode node) {
        executeLegacy("region_replace", ctx, node);
    }

    @DefineNode(id = "region_set_air", displayName = "Set Air Region", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionSetAir(FlowContext ctx, FlowNode node) {
        executeLegacy("region_set_air", ctx, node);
    }

    @DefineNode(id = "region_count_blocks", displayName = "Count Blocks", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "count", dataType = FlowType.NUMBER)
            })
    public void regionCountBlocks(FlowContext ctx, FlowNode node) {
        executeLegacy("region_count_blocks", ctx, node);
    }

    @DefineNode(id = "region_distribute", displayName = "Distribute Blocks", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "pattern_list", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionDistribute(FlowContext ctx, FlowNode node) {
        executeLegacy("region_distribute", ctx, node);
    }

    @DefineNode(id = "region_outline", displayName = "Create Outline", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionOutline(FlowContext ctx, FlowNode node) {
        executeLegacy("region_outline", ctx, node);
    }

    @DefineNode(id = "region_walls", displayName = "Create Walls", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionWalls(FlowContext ctx, FlowNode node) {
        executeLegacy("region_walls", ctx, node);
    }

    @DefineNode(id = "region_hollow", displayName = "Create Hollow", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "min_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "max_location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "material", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void regionHollow(FlowContext ctx, FlowNode node) {
        executeLegacy("region_hollow", ctx, node);
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static class ClipboardData {
        final Block[][][] blocks;
        final int minX, minY, minZ;
        final int sizeX, sizeY, sizeZ;

        ClipboardData(Block[][][] blocks, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.blocks = blocks;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }
    }
}
