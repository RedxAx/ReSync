package restudio.resync.worldgen.datapack;

import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;

import java.util.Map;
import java.util.Set;

public final class VanillaWorldGenValidator {
    private static final Set<String> FEATURE_NODES = Set.of("ore_vein", "vegetation_patch", "liquid_lake", "disk", "boulder", "placed_feature", "scatter", "output_features");
    private static final Set<String> STRUCTURE_NODES = Set.of("structure_placement", "output_structures");
    private static final Set<String> SPAWN_NODES = Set.of("spawn_rule", "output_spawns");

    private VanillaWorldGenValidator() {
    }

    public static void validate(WorldGenProject project) {
        if (project == null) {
            throw new IllegalArgumentException("WorldGen Project Missing");
        }
        requireEmpty(project.getTerrainGraph(), "Terrain", "Choose A Vanilla Terrain Preset In World Settings");
        requireEmpty(project.getSurfaceGraph(), "Surface", "Vanilla Surface Rules Come From The Selected Terrain Preset");
        requireEmpty(project.getCaveGraph(), "Caves", "Vanilla Caves Come From The Selected Terrain Preset");
        validateNodes(project.getFeatureGraph(), FEATURE_NODES, "Features");
        validateNodes(project.getStructureGraph(), STRUCTURE_NODES, "Structures");
        validateNodes(project.getSpawnGraph(), SPAWN_NODES, "Spawns");
        validateFeatureRules(project.getFeatureGraph());
        validateStructureRules(project.getStructureGraph());
        validateSpawnRules(project.getSpawnGraph());
    }

    private static void requireEmpty(WorldGenGraph graph, String stage, String action) {
        if (graph != null && graph.getNodes() != null && !graph.getNodes().isEmpty()) {
            throw new IllegalArgumentException(stage + " Graph Isn't Available In Vanilla Mode. " + action);
        }
    }

    private static void validateNodes(WorldGenGraph graph, Set<String> supported, String stage) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node != null && !supported.contains(node.getType())) {
                throw new IllegalArgumentException(node.getType() + " Isn't Available In Vanilla " + stage);
            }
        }
    }

    private static void validateFeatureRules(WorldGenGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || !"scatter".equals(node.getType())) {
                continue;
            }
            double count = number(node.getInputValues(), "count", 8);
            double chance = number(node.getInputValues(), "chance", 1);
            if (count < 1 || count != Math.rint(count)) {
                throw new IllegalArgumentException("Vanilla Feature Count Must Be A Whole Number Of At Least 1");
            }
            if (chance <= 0 || chance > 1) {
                throw new IllegalArgumentException("Vanilla Feature Chance Must Be Greater Than 0 And At Most 1");
            }
            double rarity = 1.0 / chance;
            if (Math.abs(rarity - Math.rint(rarity)) > 0.000001) {
                throw new IllegalArgumentException("Vanilla Feature Chance Must Be 1 Divided By A Whole Number");
            }
        }
    }

    private static void validateStructureRules(WorldGenGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || !"structure_placement".equals(node.getType())) {
                continue;
            }
            String anchor = text(node.getInputValues(), "anchor", "surface");
            double yOffset = number(node.getInputValues(), "y_offset", 0);
            if (!"surface".equalsIgnoreCase(anchor) || yOffset != 0) {
                throw new IllegalArgumentException("Vanilla Structures Use Their Game-Defined Terrain Placement And Cannot Apply An Anchor Or Y Offset");
            }
        }
    }

    private static void validateSpawnRules(WorldGenGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || !"spawn_rule".equals(node.getType())) {
                continue;
            }
            Map<String, Object> values = node.getInputValues();
            boolean nativeRule = text(values, "block_below", "").isBlank()
                && "any".equalsIgnoreCase(text(values, "time", "any"))
                && "any".equalsIgnoreCase(text(values, "weather", "any"))
                && number(values, "min_light", 0) == 0
                && number(values, "max_light", 15) == 15
                && number(values, "min_y", -64) == -64
                && number(values, "max_y", 319) == 319;
            if (!nativeRule) {
                throw new IllegalArgumentException("Vanilla Spawns Support Biome, Category, Weight, And Group Size. Height, Block, Light, Time, And Weather Need Hybrid Mode");
            }
        }
    }

    private static double number(Map<String, Object> values, String key, double fallback) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values == null ? null : values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
