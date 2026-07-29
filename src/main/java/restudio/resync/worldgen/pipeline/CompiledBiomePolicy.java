package restudio.resync.worldgen.pipeline;

import org.bukkit.block.Biome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record CompiledBiomePolicy(boolean defaultFeatures, boolean defaultStructures, boolean defaultSpawns,
                                  Map<String, Boolean> featureOverrides, Map<String, Boolean> structureOverrides,
                                  Map<String, Boolean> spawnOverrides) {
    public CompiledBiomePolicy {
        featureOverrides = normalize(featureOverrides);
        structureOverrides = normalize(structureOverrides);
        spawnOverrides = normalize(spawnOverrides);
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
        return features(biome == null ? null : "minecraft:" + biome.name().toLowerCase(Locale.ROOT));
    }

    public boolean features(String biomeId) {
        return value(featureOverrides, defaultFeatures, biomeId);
    }

    public boolean structures(Biome biome) {
        return structures(biome == null ? null : "minecraft:" + biome.name().toLowerCase(Locale.ROOT));
    }

    public boolean structures(String biomeId) {
        return value(structureOverrides, defaultStructures, biomeId);
    }

    public boolean spawns(Biome biome) {
        return spawns(biome == null ? null : "minecraft:" + biome.name().toLowerCase(Locale.ROOT));
    }

    public boolean spawns(String biomeId) {
        return value(spawnOverrides, defaultSpawns, biomeId);
    }

    private boolean value(Map<String, Boolean> overrides, boolean fallback, String biomeId) {
        if (biomeId == null || biomeId.isBlank()) {
            return fallback;
        }
        return overrides.getOrDefault(normalize(biomeId), fallback);
    }

    private static Map<String, Boolean> normalize(Map<String, Boolean> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(normalize(key), value));
        return Map.copyOf(normalized);
    }

    private static String normalize(String biomeId) {
        String id = biomeId.toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
