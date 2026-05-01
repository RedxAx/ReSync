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

import java.util.Locale;
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
        Placement placement = Placement.parse(pipeline.getStructurePlacement(originX, y, originZ, seed, worldInfo));
        if (placement == null || !placement.shouldPlace(chunkX, chunkZ, seed)) {
            return;
        }
        StructureLibrary.get(plugin).load(placement.structureId()).ifPresent(structure -> paste(limitedRegion, structure, (chunkX << 4) + placement.localX(seed, chunkX, chunkZ), y + placement.yOffset(), (chunkZ << 4) + placement.localZ(seed, chunkX, chunkZ)));
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
        Placement placement = Placement.parse(pipeline.getStructurePlacement(originX, y, originZ, seed, null));
        if (placement == null || !placement.shouldPlace(chunkX, chunkZ, seed)) {
            return;
        }
        StructureLibrary.get(plugin).load(placement.structureId()).ifPresent(structure -> paste(world, structure, (chunkX << 4) + placement.localX(seed, chunkX, chunkZ), y + placement.yOffset(), (chunkZ << 4) + placement.localZ(seed, chunkX, chunkZ)));
    }

    private void paste(LimitedRegion region, ReSyncStructure structure, int originX, int originY, int originZ) {
        for (int y = 0; y < structure.getSizeY(); y++) {
            for (int x = 0; x < structure.getSizeX(); x++) {
                for (int z = 0; z < structure.getSizeZ(); z++) {
                    String materialName = structure.getBlockTypes()[y][x][z];
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material == Material.AIR) continue;
                    int targetX = originX + x - structure.getOriginX();
                    int targetY = originY + y - structure.getOriginY();
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

    private void paste(World world, ReSyncStructure structure, int originX, int originY, int originZ) {
        for (int y = 0; y < structure.getSizeY(); y++) {
            for (int x = 0; x < structure.getSizeX(); x++) {
                for (int z = 0; z < structure.getSizeZ(); z++) {
                    String materialName = structure.getBlockTypes()[y][x][z];
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material == Material.AIR) continue;
                    Block target = world.getBlockAt(originX + x - structure.getOriginX(), originY + y - structure.getOriginY(), originZ + z - structure.getOriginZ());
                    target.setType(material);
                    try {
                        target.setBlockData(Bukkit.createBlockData(structure.getBlockDataStrings()[y][x][z]));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    private record Placement(String structureId, int spacing, int separation, int salt, int yOffset) {
        private static Placement parse(String value) {
            if (value == null || value.isBlank() || !value.startsWith("structure:")) {
                return null;
            }
            String body = value.substring("structure:".length());
            String[] parts = body.split(":");
            if (parts.length < 4) {
                return null;
            }
            int yOffset = 0;
            int saltIndex = parts.length - 1;
            if (parts.length >= 5 && isInt(parts[parts.length - 1]) && isInt(parts[parts.length - 2]) && isInt(parts[parts.length - 3]) && isInt(parts[parts.length - 4])) {
                yOffset = parseInt(parts[parts.length - 1], 0);
                saltIndex = parts.length - 2;
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
            return new Placement(structureId, Math.max(1, spacing), Math.max(0, separation), salt, yOffset);
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
