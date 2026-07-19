package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.structure.ReSyncStructure;
import restudio.resync.structure.StructureLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class RegionHandler implements NodeHandler {
    private static final long MAX_REGION_BLOCKS = 1_000_000L;
    private static final Gson GSON = new Gson();
    private static final Map<String, ClipboardData> clipboards = new ConcurrentHashMap<>();

    private static class ClipboardData {
        final String[][][] blockTypes;
        final String[][][] blockDataStrings;
        final int minX, minY, minZ;
        final int sizeX, sizeY, sizeZ;

        ClipboardData(Block[][][] blocks, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blockTypes = new String[sizeY][sizeX][sizeZ];
            this.blockDataStrings = new String[sizeY][sizeX][sizeZ];
            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        this.blockTypes[y][x][z] = blocks[y][x][z].getType().name();
                        this.blockDataStrings[y][x][z] = blocks[y][x][z].getBlockData().getAsString();
                    }
                }
            }
        }

        ClipboardData(String[][][] blockTypes, String[][][] blockDataStrings, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.blockTypes = blockTypes;
            this.blockDataStrings = blockDataStrings;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }
    }

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public RegionHandler() {
        operations.put("region_create", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

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
            Runnable task = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            blocks[y][x][z] = world.getBlockAt(minX + x, minY + y, minZ + z);
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(blocks, minX, minY, minZ, sizeX, sizeY, sizeZ));
            };

            task.run();
        });

        operations.put("region_delete", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_contains", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);

            if (minLoc == null || maxLoc == null || location == null) {
                throw new IllegalArgumentException("Region bounds and tested location are required");
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            int locX = location.getBlockX();
            int locY = location.getBlockY();
            int locZ = location.getBlockZ();

            boolean contains = locX >= minX && locX <= maxX && locY >= minY && locY <= maxY && locZ >= minZ && locZ <= maxZ;
            ctx.setOutput(node, "contains", contains);
        });

        operations.put("region_get_players", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) {
                throw new IllegalArgumentException("Region bounds are required");
            }

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            List<Player> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Location loc = player.getLocation();
                int px = loc.getBlockX();
                int py = loc.getBlockY();
                int pz = loc.getBlockZ();
                if (px >= minX && px <= maxX && py >= minY && py <= maxY && pz >= minZ && pz <= maxZ) {
                    players.add(player);
                }
            }
            ctx.setOutput(node, "players", players);
        });

        operations.put("region_get_entities", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null || minLoc.getWorld() == null) {
                throw new IllegalArgumentException("Region world bounds are required");
            }

            World world = minLoc.getWorld();
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            List<Entity> entities = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                Location loc = entity.getLocation();
                int ex = loc.getBlockX();
                int ey = loc.getBlockY();
                int ez = loc.getBlockZ();
                if (ex >= minX && ex <= maxX && ey >= minY && ey <= maxY && ez >= minZ && ez <= maxZ) {
                    entities.add(entity);
                }
            }
            ctx.setOutput(node, "entities", entities);
        });

        operations.put("region_get_size", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");

            int sizeX = Math.abs(maxLoc.getBlockX() - minLoc.getBlockX()) + 1;
            int sizeY = Math.abs(maxLoc.getBlockY() - minLoc.getBlockY()) + 1;
            int sizeZ = Math.abs(maxLoc.getBlockZ() - minLoc.getBlockZ()) + 1;

            ctx.setOutput(node, "size_x", sizeX);
            ctx.setOutput(node, "size_y", sizeY);
            ctx.setOutput(node, "size_z", sizeZ);
        });

        operations.put("region_expand", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);

            if (minLoc == null || maxLoc == null || minLoc.getWorld() == null) throw new IllegalArgumentException("Region world bounds are required");

            World world = minLoc.getWorld();
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX()) - amount;
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY()) - amount;
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ()) - amount;
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX()) + amount;
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY()) + amount;
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ()) + amount;

            ctx.setOutput(node, "min_location", new Location(world, minX, minY, minZ));
            ctx.setOutput(node, "max_location", new Location(world, maxX, maxY, maxZ));
        });

        operations.put("region_contract", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);

            if (minLoc == null || maxLoc == null || minLoc.getWorld() == null) throw new IllegalArgumentException("Region world bounds are required");

            World world = minLoc.getWorld();
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX()) + amount;
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY()) + amount;
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ()) + amount;
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX()) - amount;
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY()) - amount;
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ()) - amount;

            if (minX > maxX) minX = maxX = (minX + maxX) / 2;
            if (minY > maxY) minY = maxY = (minY + maxY) / 2;
            if (minZ > maxZ) minZ = maxZ = (minZ + maxZ) / 2;

            ctx.setOutput(node, "min_location", new Location(world, minX, minY, minZ));
            ctx.setOutput(node, "max_location", new Location(world, maxX, maxY, maxZ));
        });

        operations.put("region_shift", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer shiftX = ctx.getInputValue(node, "shift_x", Integer.class, 0);
            Integer shiftY = ctx.getInputValue(node, "shift_y", Integer.class, 0);
            Integer shiftZ = ctx.getInputValue(node, "shift_z", Integer.class, 0);

            if (minLoc == null || maxLoc == null || minLoc.getWorld() == null) throw new IllegalArgumentException("Region world bounds are required");

            World world = minLoc.getWorld();
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX()) + shiftX;
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY()) + shiftY;
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ()) + shiftZ;
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX()) + shiftX;
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY()) + shiftY;
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ()) + shiftZ;

            ctx.setOutput(node, "min_location", new Location(world, minX, minY, minZ));
            ctx.setOutput(node, "max_location", new Location(world, maxX, maxY, maxZ));
        });

        operations.put("region_clone", (ctx, node) -> {
            String sourceId = ctx.getInputValue(node, "source_clipboard_id", String.class, "default");
            String newId = ctx.getInputValue(node, "new_clipboard_id", String.class, "cloned");

            ClipboardData source = clipboards.get(sourceId);
            if (source == null) throw new IllegalArgumentException("Source region is required");

            String[][][] newBlockTypes = new String[source.sizeY][source.sizeX][source.sizeZ];
            String[][][] newBlockDataStrings = new String[source.sizeY][source.sizeX][source.sizeZ];
            for (int y = 0; y < source.sizeY; y++) {
                for (int x = 0; x < source.sizeX; x++) {
                    System.arraycopy(source.blockTypes[y][x], 0, newBlockTypes[y][x], 0, source.sizeZ);
                    System.arraycopy(source.blockDataStrings[y][x], 0, newBlockDataStrings[y][x], 0, source.sizeZ);
                }
            }

            clipboards.put(newId, new ClipboardData(newBlockTypes, newBlockDataStrings, source.minX, source.minY, source.minZ, source.sizeX, source.sizeY, source.sizeZ));
        });

        operations.put("region_intersect", (ctx, node) -> {
            Location minA = ctx.getInputValue(node, "min_location_a", Location.class, null);
            Location maxA = ctx.getInputValue(node, "max_location_a", Location.class, null);
            Location minB = ctx.getInputValue(node, "min_location_b", Location.class, null);
            Location maxB = ctx.getInputValue(node, "max_location_b", Location.class, null);

            if (minA == null || maxA == null || minB == null || maxB == null || minA.getWorld() == null || minB.getWorld() == null) {
                throw new IllegalArgumentException("Both region world bounds are required");
            }

            World world = minA.getWorld();
            int minX = Math.max(Math.min(minA.getBlockX(), maxA.getBlockX()), Math.min(minB.getBlockX(), maxB.getBlockX()));
            int minY = Math.max(Math.min(minA.getBlockY(), maxA.getBlockY()), Math.min(minB.getBlockY(), maxB.getBlockY()));
            int minZ = Math.max(Math.min(minA.getBlockZ(), maxA.getBlockZ()), Math.min(minB.getBlockZ(), maxB.getBlockZ()));
            int maxX = Math.min(Math.max(minA.getBlockX(), maxA.getBlockX()), Math.max(minB.getBlockX(), maxB.getBlockX()));
            int maxY = Math.min(Math.max(minA.getBlockY(), maxA.getBlockY()), Math.max(minB.getBlockY(), maxB.getBlockY()));
            int maxZ = Math.min(Math.max(minA.getBlockZ(), maxA.getBlockZ()), Math.max(minB.getBlockZ(), maxB.getBlockZ()));

            boolean hasIntersection = minX <= maxX && minY <= maxY && minZ <= maxZ;
            ctx.setOutput(node, "has_intersection", hasIntersection);
            if (hasIntersection) {
                ctx.setOutput(node, "min_location", new Location(world, minX, minY, minZ));
                ctx.setOutput(node, "max_location", new Location(world, maxX, maxY, maxZ));
            }
        });

        operations.put("region_union", (ctx, node) -> {
            Location minA = ctx.getInputValue(node, "min_location_a", Location.class, null);
            Location maxA = ctx.getInputValue(node, "max_location_a", Location.class, null);
            Location minB = ctx.getInputValue(node, "min_location_b", Location.class, null);
            Location maxB = ctx.getInputValue(node, "max_location_b", Location.class, null);

            if (minA == null || maxA == null || minB == null || maxB == null || minA.getWorld() == null) throw new IllegalArgumentException("Both region bounds are required");

            World world = minA.getWorld();
            int minX = Math.min(Math.min(minA.getBlockX(), maxA.getBlockX()), Math.min(minB.getBlockX(), maxB.getBlockX()));
            int minY = Math.min(Math.min(minA.getBlockY(), maxA.getBlockY()), Math.min(minB.getBlockY(), maxB.getBlockY()));
            int minZ = Math.min(Math.min(minA.getBlockZ(), maxA.getBlockZ()), Math.min(minB.getBlockZ(), maxB.getBlockZ()));
            int maxX = Math.max(Math.max(minA.getBlockX(), maxA.getBlockX()), Math.max(minB.getBlockX(), maxB.getBlockX()));
            int maxY = Math.max(Math.max(minA.getBlockY(), maxA.getBlockY()), Math.max(minB.getBlockY(), maxB.getBlockY()));
            int maxZ = Math.max(Math.max(minA.getBlockZ(), maxA.getBlockZ()), Math.max(minB.getBlockZ(), maxB.getBlockZ()));

            ctx.setOutput(node, "min_location", new Location(world, minX, minY, minZ));
            ctx.setOutput(node, "max_location", new Location(world, maxX, maxY, maxZ));
        });

        operations.put("region_difference", (ctx, node) -> {
            Location minA = ctx.getInputValue(node, "min_location_a", Location.class, null);
            Location maxA = ctx.getInputValue(node, "max_location_a", Location.class, null);
            Location minB = ctx.getInputValue(node, "min_location_b", Location.class, null);
            Location maxB = ctx.getInputValue(node, "max_location_b", Location.class, null);

            if (minA == null || maxA == null || minB == null || maxB == null || minA.getWorld() == null) {
                throw new IllegalArgumentException("Both region bounds are required");
            }

            World world = minA.getWorld();
            int minAX = Math.min(minA.getBlockX(), maxA.getBlockX());
            int minAY = Math.min(minA.getBlockY(), maxA.getBlockY());
            int minAZ = Math.min(minA.getBlockZ(), maxA.getBlockZ());
            int maxAX = Math.max(minA.getBlockX(), maxA.getBlockX());
            int maxAY = Math.max(minA.getBlockY(), maxA.getBlockY());
            int maxAZ = Math.max(minA.getBlockZ(), maxA.getBlockZ());

            int minBX = Math.min(minB.getBlockX(), maxB.getBlockX());
            int minBY = Math.min(minB.getBlockY(), maxB.getBlockY());
            int minBZ = Math.min(minB.getBlockZ(), maxB.getBlockZ());
            int maxBX = Math.max(minB.getBlockX(), maxB.getBlockX());
            int maxBY = Math.max(minB.getBlockY(), maxB.getBlockY());
            int maxBZ = Math.max(minB.getBlockZ(), maxB.getBlockZ());

            List<Block> blocks = new ArrayList<>();
            for (int x = minAX; x <= maxAX; x++) {
                for (int y = minAY; y <= maxAY; y++) {
                    for (int z = minAZ; z <= maxAZ; z++) {
                        if (x < minBX || x > maxBX || y < minBY || y > maxBY || z < minBZ || z > maxBZ) {
                            blocks.add(world.getBlockAt(x, y, z));
                        }
                    }
                }
            }
            ctx.setOutput(node, "blocks_list", blocks);
        });

        operations.put("region_save", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String filePath = ctx.getInputValue(node, "file_path", String.class, "");

            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) {
                throw new IllegalArgumentException("Region clipboard not found: " + clipboardId);
            }
            validateClipboardData(data);
            Path file = resolveRegionFile(filePath);
            ctx.runAsyncBeforeContinuation(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to save region clipboard: " + file.getFileName(), exception);
                }
            });
        });

        operations.put("region_load", (ctx, node) -> {
            String filePath = ctx.getInputValue(node, "file_path", String.class, "");
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "loaded");

            Path file = resolveRegionFile(filePath);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Region clipboard file not found: " + file.getFileName());
            }
            ctx.runAsyncBeforeContinuation(() -> {
                try {
                    ClipboardData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClipboardData.class);
                    validateClipboardData(data);
                    clipboards.put(clipboardId, data);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to load region clipboard: " + file.getFileName(), exception);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Region clipboard file is invalid: " + file.getFileName(), exception);
                }
            });
        });

        operations.put("structure_save", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String structureId = ctx.getInputValue(node, "structure_id", String.class, "");
            String displayName = ctx.getInputValue(node, "display_name", String.class, structureId);
            String tags = ctx.getInputValue(node, "tags", String.class, "");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null || structureId.isBlank()) throw new IllegalArgumentException("Region clipboard and structure ID are required");
            StructureLibrary.get(ReSync.getInstance()).save(toStructure(data, structureId, displayName, tags));
        });

        operations.put("structure_load", (ctx, node) -> {
            String structureId = ctx.getInputValue(node, "structure_id", String.class, "");
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "loaded");
            if (structureId.isBlank()) throw new IllegalArgumentException("Structure ID is required");
            ReSyncStructure structure = StructureLibrary.get(ReSync.getInstance()).load(structureId)
                .orElseThrow(() -> new IllegalArgumentException("Structure not found: " + structureId));
            ClipboardData data = fromStructure(structure);
            validateClipboardData(data);
            clipboards.put(clipboardId, data);
        });

        operations.put("structure_paste", (ctx, node) -> {
            String structureId = ctx.getInputValue(node, "structure_id", String.class, "");
            Location pasteLoc = ctx.getInputValue(node, "location", Location.class, null);
            Boolean ignoreAir = ctx.getInputValue(node, "ignore_air", Boolean.class, true);
            if (structureId.isBlank() || pasteLoc == null) throw new IllegalArgumentException("Structure ID and paste location are required");
            ReSyncStructure structure = StructureLibrary.get(ReSync.getInstance()).load(structureId)
                .orElseThrow(() -> new IllegalArgumentException("Structure not found: " + structureId));
            pasteStructure(structure, pasteLoc, ignoreAir);
        });

        operations.put("structure_list", (ctx, node) -> ctx.setOutput(node, "structures", new ArrayList<>(StructureLibrary.get(ReSync.getInstance()).list())));

        operations.put("structure_exists", (ctx, node) -> {
            String structureId = ctx.getInputValue(node, "structure_id", String.class, "");
            if (structureId.isBlank()) throw new IllegalArgumentException("Structure ID is required");
            ctx.setOutput(node, "exists", StructureLibrary.get(ReSync.getInstance()).exists(structureId));
        });

        operations.put("structure_delete", (ctx, node) -> {
            String structureId = ctx.getInputValue(node, "structure_id", String.class, "");
            if (structureId.isBlank()) throw new IllegalArgumentException("Structure ID is required");
            ctx.setOutput(node, "deleted", StructureLibrary.get(ReSync.getInstance()).delete(structureId));
        });

        operations.put("region_paste", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Location pasteLoc = ctx.getInputValue(node, "location", Location.class, null);

            if (pasteLoc == null) throw new IllegalArgumentException("Region paste location is required");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");
            World world = pasteLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            Runnable task = () -> {
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            Block target = world.getBlockAt(pasteLoc.getBlockX() + x, pasteLoc.getBlockY() + y, pasteLoc.getBlockZ() + z);
                            applyClipboardBlock(target, data.blockTypes[y][x][z], data.blockDataStrings[y][x][z], x, y, z);
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_replace", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String oldMaterialName = ctx.getInputValue(node, "old_material", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material oldMaterial = oldMaterialName != null && !oldMaterialName.isEmpty() ? Material.matchMaterial(oldMaterialName.toUpperCase()) : null;
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) throw new IllegalArgumentException("Replacement region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
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

            task.run();
        });

        operations.put("region_set_blocks", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(material);
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_get_blocks", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            List<Block> blocks = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        blocks.add(world.getBlockAt(x, y, z));
                    }
                }
            }
            ctx.setOutput(node, "blocks_list", blocks);
        });

        operations.put("region_count_blocks", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            int[] count = {0};
            Runnable task = () -> {
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

            task.run();

            ctx.setOutput(node, "count", count[0]);
        });

        operations.put("region_mirror_x", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");

            Runnable task = () -> {
                String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeX - 1 - x][z];
                            newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeX - 1 - x][z];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            task.run();
        });

        operations.put("region_mirror_y", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");

            Runnable task = () -> {
                String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            newBlockTypes[y][x][z] = data.blockTypes[data.sizeY - 1 - y][x][z];
                            newBlockDataStrings[y][x][z] = data.blockDataStrings[data.sizeY - 1 - y][x][z];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            task.run();
        });

        operations.put("region_mirror_z", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");

            Runnable task = () -> {
                String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            newBlockTypes[y][x][z] = data.blockTypes[y][x][data.sizeZ - 1 - z];
                            newBlockDataStrings[y][x][z] = data.blockDataStrings[y][x][data.sizeZ - 1 - z];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            task.run();
        });

        operations.put("region_rotate_90", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");

            Runnable task = () -> {
                int newSizeX = data.sizeZ;
                int newSizeZ = data.sizeX;
                String[][][] newBlockTypes = new String[data.sizeY][newSizeX][newSizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][newSizeX][newSizeZ];
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < newSizeX; x++) {
                        for (int z = 0; z < newSizeZ; z++) {
                            newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeZ - 1 - z][x];
                            newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeZ - 1 - z][x];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, newSizeX, data.sizeY, newSizeZ));
            };

            task.run();
        });

        operations.put("region_rotate_180", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");

            Runnable task = () -> {
                String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                for (int y = 0; y < data.sizeY; y++) {
                    for (int x = 0; x < data.sizeX; x++) {
                        for (int z = 0; z < data.sizeZ; z++) {
                            newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeX - 1 - x][data.sizeZ - 1 - z];
                            newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeX - 1 - x][data.sizeZ - 1 - z];
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            task.run();
        });

        operations.put("region_move", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            Integer shiftX = ctx.getInputValue(node, "shift_x", Integer.class, 0);
            Integer shiftY = ctx.getInputValue(node, "shift_y", Integer.class, 0);
            Integer shiftZ = ctx.getInputValue(node, "shift_z", Integer.class, 0);

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;

            String[][][] blockTypes = new String[sizeY][sizeX][sizeZ];
            String[][][] blockDataStrings = new String[sizeY][sizeX][sizeZ];

            Runnable task = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                            blockTypes[y][x][z] = block.getType().name();
                            blockDataStrings[y][x][z] = block.getBlockData().getAsString();
                            block.setType(Material.AIR);
                        }
                    }
                }

                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            Block target = world.getBlockAt(minX + x + shiftX, minY + y + shiftY, minZ + z + shiftZ);
                            applyClipboardBlock(target, blockTypes[y][x][z], blockDataStrings[y][x][z], x, y, z);
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_stack", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Location startLoc = ctx.getInputValue(node, "location", Location.class, null);
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            Integer dirX = ctx.getInputValue(node, "direction_x", Integer.class, 1);
            Integer dirY = ctx.getInputValue(node, "direction_y", Integer.class, 0);
            Integer dirZ = ctx.getInputValue(node, "direction_z", Integer.class, 0);

            if (startLoc == null) throw new IllegalArgumentException("Region start location is required");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");
            World world = startLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            Runnable task = () -> {
                for (int i = 0; i < count; i++) {
                    int offsetX = i * data.sizeX * dirX;
                    int offsetY = i * data.sizeY * dirY;
                    int offsetZ = i * data.sizeZ * dirZ;
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                Block target = world.getBlockAt(startLoc.getBlockX() + x + offsetX, startLoc.getBlockY() + y + offsetY, startLoc.getBlockZ() + z + offsetZ);
                                applyClipboardBlock(target, data.blockTypes[y][x][z], data.blockDataStrings[y][x][z], x, y, z);
                            }
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_outline", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isEdge = (x == minX || x == maxX) || (y == minY || y == maxY) || (z == minZ || z == maxZ);
                            if (isEdge) {
                                world.getBlockAt(x, y, z).setType(material);
                            }
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_copy", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

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
            Runnable task = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            blocks[y][x][z] = world.getBlockAt(minX + x, minY + y, minZ + z);
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(blocks, minX, minY, minZ, sizeX, sizeY, sizeZ));
            };

            task.run();
        });

        operations.put("region_cut", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

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
            Runnable task = () -> {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            blocks[y][x][z] = world.getBlockAt(minX + x, minY + y, minZ + z);
                        }
                    }
                }
                clipboards.put(clipboardId, new ClipboardData(blocks, minX, minY, minZ, sizeX, sizeY, sizeZ));

                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_rotate", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Integer degrees = ctx.getInputValue(node, "degrees", Integer.class, 90);
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");
            int deg = degrees != null ? degrees : 90;

            Runnable task = () -> {
                if (deg == 90) {
                    int newSizeX = data.sizeZ;
                    int newSizeZ = data.sizeX;
                    String[][][] newBlockTypes = new String[data.sizeY][newSizeX][newSizeZ];
                    String[][][] newBlockDataStrings = new String[data.sizeY][newSizeX][newSizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < newSizeX; x++) {
                            for (int z = 0; z < newSizeZ; z++) {
                                newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeZ - 1 - z][x];
                                newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeZ - 1 - z][x];
                            }
                        }
                    }
                    clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, newSizeX, data.sizeY, newSizeZ));
                } else if (deg == 180) {
                    String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                    String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeX - 1 - x][data.sizeZ - 1 - z];
                                newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeX - 1 - x][data.sizeZ - 1 - z];
                            }
                        }
                    }
                    clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
                } else if (deg == 270) {
                    int newSizeX = data.sizeZ;
                    int newSizeZ = data.sizeX;
                    String[][][] newBlockTypes = new String[data.sizeY][newSizeX][newSizeZ];
                    String[][][] newBlockDataStrings = new String[data.sizeY][newSizeX][newSizeZ];
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < newSizeX; x++) {
                            for (int z = 0; z < newSizeZ; z++) {
                                newBlockTypes[y][x][z] = data.blockTypes[y][z][data.sizeX - 1 - x];
                                newBlockDataStrings[y][x][z] = data.blockDataStrings[y][z][data.sizeX - 1 - x];
                            }
                        }
                    }
                    clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, newSizeX, data.sizeY, newSizeZ));
                }
            };

            task.run();
        });

        operations.put("region_mirror", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            String axis = ctx.getInputValue(node, "axis", String.class, "x");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null || axis == null) throw new IllegalArgumentException("Region clipboard and mirror axis are required");

            Runnable task = () -> {
                String[][][] newBlockTypes = new String[data.sizeY][data.sizeX][data.sizeZ];
                String[][][] newBlockDataStrings = new String[data.sizeY][data.sizeX][data.sizeZ];
                switch (axis.toLowerCase()) {
                    case "x":
                        for (int y = 0; y < data.sizeY; y++) {
                            for (int x = 0; x < data.sizeX; x++) {
                                for (int z = 0; z < data.sizeZ; z++) {
                                    newBlockTypes[y][x][z] = data.blockTypes[y][data.sizeX - 1 - x][z];
                                    newBlockDataStrings[y][x][z] = data.blockDataStrings[y][data.sizeX - 1 - x][z];
                                }
                            }
                        }
                        break;
                    case "y":
                        for (int y = 0; y < data.sizeY; y++) {
                            for (int x = 0; x < data.sizeX; x++) {
                                for (int z = 0; z < data.sizeZ; z++) {
                                    newBlockTypes[y][x][z] = data.blockTypes[data.sizeY - 1 - y][x][z];
                                    newBlockDataStrings[y][x][z] = data.blockDataStrings[data.sizeY - 1 - y][x][z];
                                }
                            }
                        }
                        break;
                    case "z":
                        for (int y = 0; y < data.sizeY; y++) {
                            for (int x = 0; x < data.sizeX; x++) {
                                for (int z = 0; z < data.sizeZ; z++) {
                                    newBlockTypes[y][x][z] = data.blockTypes[y][x][data.sizeZ - 1 - z];
                                    newBlockDataStrings[y][x][z] = data.blockDataStrings[y][x][data.sizeZ - 1 - z];
                                }
                            }
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown region mirror axis: " + axis);
                }
                clipboards.put(clipboardId, new ClipboardData(newBlockTypes, newBlockDataStrings, data.minX, data.minY, data.minZ, data.sizeX, data.sizeY, data.sizeZ));
            };

            task.run();
        });

        operations.put("region_distribute", (ctx, node) -> {
            String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, "default");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            Integer spacingX = ctx.getInputValue(node, "spacing_x", Integer.class, 0);
            Integer spacingY = ctx.getInputValue(node, "spacing_y", Integer.class, 0);
            Integer spacingZ = ctx.getInputValue(node, "spacing_z", Integer.class, 0);

            if (location == null || count == null || count <= 0) throw new IllegalArgumentException("Region stack location and positive count are required");
            ClipboardData data = clipboards.get(clipboardId);
            if (data == null) throw new IllegalArgumentException("Region clipboard was not found");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            Runnable task = () -> {
                for (int i = 0; i < count; i++) {
                    int offsetX = i * spacingX;
                    int offsetY = i * spacingY;
                    int offsetZ = i * spacingZ;
                    for (int y = 0; y < data.sizeY; y++) {
                        for (int x = 0; x < data.sizeX; x++) {
                            for (int z = 0; z < data.sizeZ; z++) {
                                Block target = world.getBlockAt(location.getBlockX() + x + offsetX, location.getBlockY() + y + offsetY, location.getBlockZ() + z + offsetZ);
                                applyClipboardBlock(target, data.blockTypes[y][x][z], data.blockDataStrings[y][x][z], x, y, z);
                            }
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_walls", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            if (x == minX || x == maxX || z == minZ || z == maxZ) {
                                world.getBlockAt(x, y, z).setType(material);
                            }
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_hollow", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");

            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Region bounds are required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Region material is invalid");
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Region world is required");

            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isEdge = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                            if (isEdge) {
                                world.getBlockAt(x, y, z).setType(material);
                            }
                        }
                    }
                }
            };

            task.run();
        });

        operations.put("region_set_air", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null || minLoc.getWorld() == null) {
                throw new IllegalArgumentException("Region world bounds are required");
            }

            World world = minLoc.getWorld();
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());

            Runnable task = () -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            };

            task.run();
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("RegionHandler", this);
    }

    private static Path resolveRegionFile(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("Region clipboard file path is required");
        }
        ReSync plugin = ReSync.getInstance();
        if (plugin == null) {
            throw new IllegalStateException("ReSync plugin is unavailable");
        }
        Path root = plugin.getDataFolder().toPath().resolve("flow-regions").toAbsolutePath().normalize();
        Path resolved = root.resolve(requestedPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Region clipboard path escapes the flow-regions directory");
        }
        return resolved;
    }

    private static void validateClipboardData(ClipboardData data) {
        if (data == null || data.sizeX <= 0 || data.sizeY <= 0 || data.sizeZ <= 0) {
            throw new IllegalArgumentException("Region clipboard dimensions are invalid");
        }
        long blockCount;
        try {
            blockCount = Math.multiplyExact(Math.multiplyExact((long) data.sizeX, data.sizeY), data.sizeZ);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Region clipboard dimensions overflow", exception);
        }
        if (blockCount > MAX_REGION_BLOCKS) {
            throw new IllegalArgumentException("Region clipboard exceeds the " + MAX_REGION_BLOCKS + " block limit");
        }
        validateClipboardArray(data.blockTypes, data.sizeX, data.sizeY, data.sizeZ, "block types");
        validateClipboardArray(data.blockDataStrings, data.sizeX, data.sizeY, data.sizeZ, "block data");
    }

    private static void validateClipboardArray(String[][][] values, int sizeX, int sizeY, int sizeZ, String label) {
        if (values == null || values.length != sizeY) {
            throw new IllegalArgumentException("Region clipboard " + label + " height does not match its dimensions");
        }
        for (String[][] layer : values) {
            if (layer == null || layer.length != sizeX) {
                throw new IllegalArgumentException("Region clipboard " + label + " width does not match its dimensions");
            }
            for (String[] row : layer) {
                if (row == null || row.length != sizeZ || Arrays.stream(row).anyMatch(value -> value == null || value.isBlank())) {
                    throw new IllegalArgumentException("Region clipboard " + label + " depth or values do not match its dimensions");
                }
            }
        }
    }

    private ReSyncStructure toStructure(ClipboardData data, String id, String displayName, String tags) {
        ReSyncStructure structure = new ReSyncStructure();
        structure.setId(id);
        structure.setDisplayName(displayName);
        structure.setTags(Arrays.stream(tags.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList());
        structure.setOriginX(0);
        structure.setOriginY(0);
        structure.setOriginZ(0);
        structure.setSizeX(data.sizeX);
        structure.setSizeY(data.sizeY);
        structure.setSizeZ(data.sizeZ);
        structure.setBlockTypes(copy(data.blockTypes));
        structure.setBlockDataStrings(copy(data.blockDataStrings));
        return structure;
    }

    private ClipboardData fromStructure(ReSyncStructure structure) {
        return new ClipboardData(copy(structure.getBlockTypes()), copy(structure.getBlockDataStrings()), 0, 0, 0, structure.getSizeX(), structure.getSizeY(), structure.getSizeZ());
    }

    private void applyClipboardBlock(Block target, String materialName, String blockDataString, int x, int y, int z) {
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            throw new IllegalArgumentException("Unknown clipboard material at " + x + "," + y + "," + z + ": " + materialName);
        }
        BlockData blockData;
        try {
            blockData = Bukkit.createBlockData(blockDataString);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid clipboard block data at " + x + "," + y + "," + z, exception);
        }
        if (blockData.getMaterial() != material) {
            throw new IllegalArgumentException("Clipboard material and block data disagree at " + x + "," + y + "," + z);
        }
        target.setBlockData(blockData);
    }

    private void pasteStructure(ReSyncStructure structure, Location pasteLoc, boolean ignoreAir) {
        World world = pasteLoc.getWorld();
        if (world == null || structure.getBlockTypes() == null || structure.getBlockDataStrings() == null) throw new IllegalArgumentException("Structure world and block data are required");
        validateClipboardData(new ClipboardData(structure.getBlockTypes(), structure.getBlockDataStrings(), 0, 0, 0,
            structure.getSizeX(), structure.getSizeY(), structure.getSizeZ()));
        Runnable task = () -> {
            for (int y = 0; y < structure.getSizeY(); y++) {
                for (int x = 0; x < structure.getSizeX(); x++) {
                    for (int z = 0; z < structure.getSizeZ(); z++) {
                        String materialName = structure.getBlockTypes()[y][x][z];
                        Material material = Material.matchMaterial(materialName);
                        if (material == null) {
                            throw new IllegalArgumentException("Unknown structure material at " + x + "," + y + "," + z + ": " + materialName);
                        }
                        if (ignoreAir && material == Material.AIR) continue;
                        Block target = world.getBlockAt(pasteLoc.getBlockX() + x - structure.getOriginX(), pasteLoc.getBlockY() + y - structure.getOriginY(), pasteLoc.getBlockZ() + z - structure.getOriginZ());
                        applyClipboardBlock(target, materialName, structure.getBlockDataStrings()[y][x][z], x, y, z);
                    }
                }
            }
        };
        task.run();
    }

    private String[][][] copy(String[][][] source) {
        if (source == null) {
            return new String[0][][];
        }
        String[][][] copy = new String[source.length][][];
        for (int y = 0; y < source.length; y++) {
            copy[y] = new String[source[y].length][];
            for (int x = 0; x < source[y].length; x++) {
                copy[y][x] = Arrays.copyOf(source[y][x], source[y][x].length);
            }
        }
        return copy;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown region operation: " + operation);
        }
        enforceOperationBudget(ctx, node, operation);
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    @Override
    public void shutdown() {
        clipboards.clear();
    }

    private void enforceOperationBudget(FlowContext ctx, FlowNode node, String operation) {
        validateBounds(ctx.getInputValue(node, "min_location", Location.class, null), ctx.getInputValue(node, "max_location", Location.class, null));
        validateBounds(ctx.getInputValue(node, "min_location_a", Location.class, null), ctx.getInputValue(node, "max_location_a", Location.class, null));
        validateBounds(ctx.getInputValue(node, "min_location_b", Location.class, null), ctx.getInputValue(node, "max_location_b", Location.class, null));
        String clipboardId = ctx.getInputValue(node, "clipboard_id", String.class, null);
        ClipboardData data = clipboardId != null ? clipboards.get(clipboardId) : null;
        if (data != null) {
            validateClipboardData(data);
        }
        String sourceId = ctx.getInputValue(node, "source_clipboard_id", String.class, null);
        ClipboardData source = sourceId != null ? clipboards.get(sourceId) : null;
        if (source != null) {
            validateClipboardData(source);
        }
        if ("region_stack".equals(operation) && data != null) {
            int count = ctx.getInputValue(node, "count", Integer.class, 1);
            if (count <= 0) {
                throw new IllegalArgumentException("Region stack count must be positive");
            }
            long total;
            try {
                total = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact((long) data.sizeX, data.sizeY), data.sizeZ), count);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Region stack block count overflow", exception);
            }
            if (total > MAX_REGION_BLOCKS) {
                throw new IllegalArgumentException("Region stack exceeds the " + MAX_REGION_BLOCKS + " block limit");
            }
        }
    }

    private void validateBounds(Location minimum, Location maximum) {
        if (minimum == null && maximum == null) {
            return;
        }
        if (minimum == null || maximum == null || minimum.getWorld() == null || maximum.getWorld() == null
            || !minimum.getWorld().equals(maximum.getWorld())) {
            throw new IllegalArgumentException("Region bounds must exist in the same world");
        }
        long sizeX = Math.abs((long) maximum.getBlockX() - minimum.getBlockX()) + 1L;
        long sizeY = Math.abs((long) maximum.getBlockY() - minimum.getBlockY()) + 1L;
        long sizeZ = Math.abs((long) maximum.getBlockZ() - minimum.getBlockZ()) + 1L;
        long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Region volume overflow", exception);
        }
        if (volume > MAX_REGION_BLOCKS) {
            throw new IllegalArgumentException("Region exceeds the " + MAX_REGION_BLOCKS + " block limit");
        }
    }
}
