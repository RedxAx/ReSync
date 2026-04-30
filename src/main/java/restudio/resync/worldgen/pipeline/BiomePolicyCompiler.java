package restudio.resync.worldgen.pipeline;

import restudio.resync.worldgen.data.WorldGenBiomeProfile;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenProjectSettings;
import restudio.resync.worldgen.data.WorldGenSpawnRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BiomePolicyCompiler {
    record Result(CompiledBiomePolicy policy, List<WorldGenSpawnRule> spawnRules) {
    }

    private BiomePolicyCompiler() {
    }

    static Result compile(WorldGenProject project) {
        WorldGenProjectSettings settings = project.getSettings();
        Map<String, Boolean> featureOverrides = new HashMap<>(settings == null ? Map.of() : settings.getBiomeVanillaFeatureOverrides());
        Map<String, Boolean> structureOverrides = new HashMap<>();
        Map<String, Boolean> spawnOverrides = new HashMap<>();
        List<WorldGenSpawnRule> spawnRules = new ArrayList<>();
        collectProfilePolicies(project, featureOverrides, structureOverrides, spawnOverrides, spawnRules);
        collectNodePolicies(project.getBiomeGraph(), featureOverrides, structureOverrides, spawnOverrides);
        collectSpawnRules(project.getSpawnGraph(), spawnRules);
        return new Result(new CompiledBiomePolicy(
            settings == null || settings.isVanillaFeaturesEnabled(),
            settings == null || settings.isVanillaStructuresEnabled(),
            settings == null || settings.isVanillaSpawnsEnabled(),
            featureOverrides, structureOverrides, spawnOverrides), spawnRules);
    }

    private static void collectProfilePolicies(WorldGenProject project, Map<String, Boolean> featureOverrides, Map<String, Boolean> structureOverrides,
                                               Map<String, Boolean> spawnOverrides, List<WorldGenSpawnRule> spawnRules) {
        if (project == null || project.getBiomeProfiles() == null) {
            return;
        }
        for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
            if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
                continue;
            }
            featureOverrides.put(profile.getId(), profile.isKeepVanillaFeatures());
            structureOverrides.put(profile.getId(), profile.isKeepVanillaStructures());
            spawnOverrides.put(profile.getId(), profile.isKeepVanillaSpawns());
            if (profile.getVanillaBaseBiome() != null && !profile.getVanillaBaseBiome().isBlank()) {
                featureOverrides.put(profile.getVanillaBaseBiome(), profile.isKeepVanillaFeatures());
                structureOverrides.put(profile.getVanillaBaseBiome(), profile.isKeepVanillaStructures());
                spawnOverrides.put(profile.getVanillaBaseBiome(), profile.isKeepVanillaSpawns());
            }
            if (profile.getSpawnRules() != null) {
                spawnRules.addAll(profile.getSpawnRules());
            }
        }
    }

    private static void collectNodePolicies(WorldGenGraph graph, Map<String, Boolean> featureOverrides, Map<String, Boolean> structureOverrides,
                                            Map<String, Boolean> spawnOverrides) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || node.getInputValues() == null) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            addPolicyId(ids, node.getInputValues().get("biome"));
            addPolicyId(ids, node.getInputValues().get("true_biome"));
            addPolicyId(ids, node.getInputValues().get("false_biome"));
            addPolicyId(ids, node.getInputValues().get("profile"));
            if ("climate_map".equals(node.getType()) || "biome_climate_router".equals(node.getType())) {
                ids.addAll(List.of("minecraft:snowy_plains", "minecraft:desert", "minecraft:forest", "minecraft:savanna", "minecraft:plains"));
            }
            for (String biomeId : ids) {
                featureOverrides.put(biomeId, booleanValue(node.getInputValues().get("keep_vanilla_features"), false));
                structureOverrides.put(biomeId, booleanValue(node.getInputValues().get("keep_vanilla_structures"), false));
                spawnOverrides.put(biomeId, booleanValue(node.getInputValues().get("keep_vanilla_spawns"), false));
            }
            if (booleanValue(node.getInputValues().get("keep_vanilla_features"), false)) {
                featureOverrides.put(TerrainPipeline.ANY_POLICY_KEY, true);
            }
            if (booleanValue(node.getInputValues().get("keep_vanilla_structures"), false)) {
                structureOverrides.put(TerrainPipeline.ANY_POLICY_KEY, true);
            }
            if (booleanValue(node.getInputValues().get("keep_vanilla_spawns"), false)) {
                spawnOverrides.put(TerrainPipeline.ANY_POLICY_KEY, true);
            }
        }
    }

    private static void collectSpawnRules(WorldGenGraph graph, List<WorldGenSpawnRule> spawnRules) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || !"spawn_rule".equals(node.getType())) {
                continue;
            }
            WorldGenSpawnRule rule = new WorldGenSpawnRule();
            rule.setEntityType(String.valueOf(node.getInputValues().getOrDefault("entity", "minecraft:zombie")));
            rule.setWeight(Math.round(number(node.getInputValues().get("weight"), 10f)));
            rule.setMinGroup(Math.round(number(node.getInputValues().get("min_group"), 1f)));
            rule.setMaxGroup(Math.round(number(node.getInputValues().get("max_group"), 4f)));
            spawnRules.add(rule);
        }
    }

    private static void addPolicyId(List<String> ids, Object value) {
        if (value == null) {
            return;
        }
        String id = String.valueOf(value);
        if (!id.isBlank()) {
            ids.add(id);
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static float number(Object value, float fallback) {
        if (value instanceof Number number) return number.floatValue();
        if (value == null) return fallback;
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
