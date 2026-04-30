package restudio.resync.worldgen.pipeline;

import org.bukkit.block.Biome;

import java.util.Locale;
import java.util.Map;

public record CompiledBiomePolicy(boolean defaultFeatures, boolean defaultStructures, boolean defaultSpawns,
                                  Map<String, Boolean> featureOverrides, Map<String, Boolean> structureOverrides,
                                  Map<String, Boolean> spawnOverrides) {
    public CompiledBiomePolicy {
        featureOverrides = featureOverrides != null ? Map.copyOf(featureOverrides) : Map.of();
        structureOverrides = structureOverrides != null ? Map.copyOf(structureOverrides) : Map.of();
        spawnOverrides = spawnOverrides != null ? Map.copyOf(spawnOverrides) : Map.of();
    }

    public boolean hasAnyFeatures() {
        return defaultFeatures || featureOverrides.containsValue(true);
    }

    public boolean hasAnyStructures() {
        return defaultStructures || structureOverrides.containsValue(true);
    }

    public boolean hasAnySpawns() {
        return defaultSpawns || spawnOverrides.containsValue(true);
    }

    public boolean features(Biome biome) {
        return value(featureOverrides, defaultFeatures, biome);
    }

    public boolean structures(Biome biome) {
        return value(structureOverrides, defaultStructures, biome);
    }

    public boolean spawns(Biome biome) {
        return value(spawnOverrides, defaultSpawns, biome);
    }

    private boolean value(Map<String, Boolean> overrides, boolean fallback, Biome biome) {
        if (biome == null) {
            return fallback;
        }
        return overrides.getOrDefault("minecraft:" + biome.name().toLowerCase(Locale.ROOT), fallback);
    }
}
