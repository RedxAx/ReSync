package restudio.resync.world;

import org.bukkit.Material;
import org.bukkit.generator.WorldInfo;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class ReSyncBuiltInGenerators {
    public static final String NORMAL_ID = "resync:normal";
    public static final String FLAT_ID = "resync:flat";
    public static final String VOID_ID = "resync:void";

    private ReSyncBuiltInGenerators() {
    }

    public static List<WorldGeneratorDescriptor> createDescriptors() {
        List<WorldGeneratorDescriptor> descriptors = new ArrayList<>();
        descriptors.add(descriptor(NORMAL_ID, "ReSync Normal", true, false, "", ""));
        descriptors.add(descriptor(FLAT_ID, "ReSync Flat", true, true, "grass_block,dirt,dirt,bedrock", "grass_block,dirt,dirt,bedrock"));
        descriptors.add(descriptor(VOID_ID, "ReSync Void", true, false, "", ""));
        return descriptors;
    }

    public static ChunkGenerator createGenerator(String id, String config) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (FLAT_ID.equalsIgnoreCase(normalized)) {
            return new FlatGenerator(config);
        }
        if (VOID_ID.equalsIgnoreCase(normalized)) {
            return new VoidGenerator();
        }
        return null;
    }

    public static boolean isBuiltIn(String id) {
        return NORMAL_ID.equalsIgnoreCase(id) || FLAT_ID.equalsIgnoreCase(id) || VOID_ID.equalsIgnoreCase(id);
    }

    public static boolean usesDefaultGeneration(String id) {
        return NORMAL_ID.equalsIgnoreCase(id);
    }

    private static WorldGeneratorDescriptor descriptor(String id, String displayName, boolean builtIn, boolean configurable, String configPlaceholder, String defaultConfig) {
        WorldGeneratorDescriptor descriptor = new WorldGeneratorDescriptor();
        descriptor.setId(id);
        descriptor.setDisplayName(displayName);
        descriptor.setBuiltIn(builtIn);
        descriptor.setConfigurable(configurable);
        descriptor.setConfigPlaceholder(configPlaceholder);
        descriptor.setDefaultConfig(defaultConfig);
        return descriptor;
    }

    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateBedrock() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }

    private static final class FlatGenerator extends ChunkGenerator {
        private final List<Material> layers;

        private FlatGenerator(String config) {
            this.layers = parseLayers(config);
        }

        @Override
        public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
            for (int y = 0; y < layers.size(); y++) {
                Material material = layers.get(y);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        chunkData.setBlock(x, y, z, material);
                    }
                }
            }
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return true;
        }

        @Override
        public boolean shouldGenerateBedrock() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }

        public List<BlockPopulator> getDefaultPopulators(WorldInfo world) {
            return List.of();
        }

        private List<Material> parseLayers(String config) {
            String value = config == null || config.isBlank() ? "grass_block,dirt,dirt,bedrock" : config;
            List<Material> resolved = new ArrayList<>();
            for (String token : value.split(",")) {
                String trimmed = token == null ? "" : token.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                Material material = Material.matchMaterial(trimmed, false);
                resolved.add(material == null ? Material.STONE : material);
            }
            if (resolved.isEmpty()) {
                resolved.add(Material.GRASS_BLOCK);
                resolved.add(Material.DIRT);
                resolved.add(Material.DIRT);
                resolved.add(Material.BEDROCK);
            }
            Collections.reverse(resolved);
            return resolved;
        }
    }
}
