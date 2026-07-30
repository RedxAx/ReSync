package restudio.resync.worldgen.generator;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import restudio.resync.ReSync;
import restudio.resync.structure.ReSyncStructure;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

public class StructurePopulator extends BlockPopulator {
    private final TerrainPipelineHolder pipelineHolder;

    public StructurePopulator(TerrainPipelineHolder pipelineHolder) {
        this.pipelineHolder = pipelineHolder;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null) {
            return;
        }
        TerrainPipeline pipeline = pipelineHolder.get();
        long seed = worldInfo == null ? 0L : worldInfo.getSeed();
        int originX = (chunkX << 4) + 8;
        int originZ = (chunkZ << 4) + 8;
        int y = worldInfo == null ? 64 : Math.max(worldInfo.getMinHeight(), Math.min(worldInfo.getMaxHeight() - 1, Math.round(pipeline.getHeight(originX, originZ, seed, worldInfo))));
        for (Placement placement : Placement.parseAll(pipeline.getStructurePlacement(originX, y, originZ, seed, worldInfo))) {
            if (!placement.shouldPlace(chunkX, chunkZ, seed) || !placement.matches(pipeline, originX, y, originZ, seed, worldInfo)) {
                continue;
            }
            StructureLibrary.get(plugin).load(placement.structureId()).ifPresent(structure -> paste(limitedRegion, structure,
                (chunkX << 4) + placement.localX(seed, chunkX, chunkZ), y + placement.yOffset(),
                (chunkZ << 4) + placement.localZ(seed, chunkX, chunkZ), placement.anchor()));
        }
    }

    @Override
    public void populate(World world, Random random, Chunk source) {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || source == null || world == null) {
            return;
        }
        TerrainPipeline pipeline = pipelineHolder.get();
        long seed = world.getSeed();
        int chunkX = source.getX();
        int chunkZ = source.getZ();
        int originX = (chunkX << 4) + 8;
        int originZ = (chunkZ << 4) + 8;
        int y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, Math.round(pipeline.getHeight(originX, originZ, seed, null))));
        for (Placement placement : Placement.parseAll(pipeline.getStructurePlacement(originX, y, originZ, seed, null))) {
            if (!placement.shouldPlace(chunkX, chunkZ, seed) || !placement.matches(pipeline, originX, y, originZ, seed, null)) {
                continue;
            }
            StructureLibrary.get(plugin).load(placement.structureId()).ifPresent(structure -> paste(world, structure,
                (chunkX << 4) + placement.localX(seed, chunkX, chunkZ), y + placement.yOffset(),
                (chunkZ << 4) + placement.localZ(seed, chunkX, chunkZ), placement.anchor()));
        }
    }

    private void paste(LimitedRegion region, ReSyncStructure structure, int originX, int originY, int originZ, String anchor) {
        int anchorY = anchorY(structure, anchor);
        for (int y = 0; y < structure.getSizeY(); y++) {
            for (int x = 0; x < structure.getSizeX(); x++) {
                for (int z = 0; z < structure.getSizeZ(); z++) {
                    String materialName = structure.getBlockTypes()[y][x][z];
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material == Material.AIR) continue;
                    int targetX = originX + x - structure.getOriginX();
                    int targetY = originY + y - anchorY;
                    int targetZ = originZ + z - structure.getOriginZ();
                    if (!region.isInRegion(targetX, targetY, targetZ)) continue;
                    region.setType(targetX, targetY, targetZ, material);
                    try {
                        region.setBlockData(targetX, targetY, targetZ, Bukkit.createBlockData(structure.getBlockDataStrings()[y][x][z]));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    private void paste(World world, ReSyncStructure structure, int originX, int originY, int originZ, String anchor) {
        int anchorY = anchorY(structure, anchor);
        for (int y = 0; y < structure.getSizeY(); y++) {
            for (int x = 0; x < structure.getSizeX(); x++) {
                for (int z = 0; z < structure.getSizeZ(); z++) {
                    String materialName = structure.getBlockTypes()[y][x][z];
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material == Material.AIR) continue;
                    Block target = world.getBlockAt(originX + x - structure.getOriginX(), originY + y - anchorY, originZ + z - structure.getOriginZ());
                    target.setType(material);
                    try {
                        target.setBlockData(Bukkit.createBlockData(structure.getBlockDataStrings()[y][x][z]));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    private int anchorY(ReSyncStructure structure, String anchor) {
        if ("origin".equalsIgnoreCase(anchor)) {
            return structure.getOriginY();
        }
        if ("buried".equalsIgnoreCase(anchor)) {
            return structure.getSizeY();
        }
        for (int y = 0; y < structure.getSizeY(); y++) {
            for (int x = 0; x < structure.getSizeX(); x++) {
                for (int z = 0; z < structure.getSizeZ(); z++) {
                    Material material = Material.matchMaterial(structure.getBlockTypes()[y][x][z]);
                    if (material != null && material != Material.AIR) {
                        return y;
                    }
                }
            }
        }
        return 0;
    }

    private record Placement(String structureId, int spacing, int separation, int salt, int yOffset, String anchor, String biome, boolean terrainMatch) {
        private static List<Placement> parseAll(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return value.lines().map(Placement::parse).filter(Objects::nonNull).toList();
        }

        private static Placement parse(String value) {
            if (value == null || value.isBlank() || !value.startsWith("structure:")) {
                return null;
            }
            String[] metadata = value.split("\\|", -1);
            String body = metadata[0].substring("structure:".length());
            String biome = metadata.length > 1 ? metadata[1].trim() : "";
            boolean terrainMatch = metadata.length <= 2 || Boolean.parseBoolean(metadata[2]);
            String[] parts = body.split(":");
            if (parts.length < 4) {
                return null;
            }
            String anchor = "surface";
            int tail = parts.length - 1;
            if (!isInt(parts[tail])) {
                anchor = parts[tail].toLowerCase(Locale.ROOT);
                tail--;
            }
            int yOffset = 0;
            int saltIndex = tail;
            if (tail >= 4 && isInt(parts[tail]) && isInt(parts[tail - 1]) && isInt(parts[tail - 2]) && isInt(parts[tail - 3])) {
                yOffset = parseInt(parts[tail], 0);
                saltIndex = tail - 1;
            }
            int salt = parseInt(parts[saltIndex], 0);
            int separation = parseInt(parts[saltIndex - 1], 8);
            int spacing = parseInt(parts[saltIndex - 2], 32);
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < saltIndex - 2; i++) {
                if (i > 0) id.append(':');
                id.append(parts[i]);
            }
            String structureId = id.toString().toLowerCase(Locale.ROOT);
            if (structureId.isBlank()) {
                return null;
            }
            return new Placement(structureId, Math.max(1, spacing), Math.max(0, separation), salt, yOffset, anchor, biome, terrainMatch);
        }

        private boolean matches(TerrainPipeline pipeline, int x, int y, int z, long seed, WorldInfo worldInfo) {
            if (!biome.isBlank() && !biome.equalsIgnoreCase(pipeline.getBiomeChoice(x, y, z, seed, worldInfo).biomeId())) {
                return false;
            }
            return !terrainMatch || pipeline.isVanillaStructureTerrainSuitable(x, z, seed, worldInfo);
        }

        private boolean shouldPlace(int chunkX, int chunkZ, long seed) {
            int cellX = Math.floorDiv(chunkX, spacing);
            int cellZ = Math.floorDiv(chunkZ, spacing);
            Random random = new Random(seed ^ salt ^ cellX * 341873128712L ^ cellZ * 132897987541L);
            int range = Math.max(1, spacing - separation);
            int targetX = cellX * spacing + random.nextInt(range);
            int targetZ = cellZ * spacing + random.nextInt(range);
            return chunkX == targetX && chunkZ == targetZ;
        }

        private int localX(long seed, int chunkX, int chunkZ) {
            return Math.floorMod(Long.hashCode(seed ^ salt ^ chunkX * 341873128712L ^ chunkZ * 132897987541L ^ 0x51f15eL), 16);
        }

        private int localZ(long seed, int chunkX, int chunkZ) {
            return Math.floorMod(Long.hashCode(seed ^ salt ^ chunkX * 132897987541L ^ chunkZ * 341873128712L ^ 0x71f15eL), 16);
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value);
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private static boolean isInt(String value) {
            try {
                Integer.parseInt(value);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
