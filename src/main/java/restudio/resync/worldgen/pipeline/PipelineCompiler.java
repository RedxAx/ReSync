package restudio.resync.worldgen.pipeline;

import restudio.flow.data.FlowDataType;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenBiomeProfile;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSpawnRule;
import restudio.resync.worldgen.data.WorldGenStage;
import restudio.resync.worldgen.evaluator.FractalEvaluator;
import restudio.resync.worldgen.evaluator.NoiseEvaluator;
import restudio.resync.worldgen.registry.WorldGenNodeDefinition;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PipelineCompiler {
    public static WorldGenCompileDiagnostics diagnoseProject(WorldGenProject project) {
        long started = System.nanoTime();
        WorldGenCompileDiagnostics diagnostics = new WorldGenCompileDiagnostics();
        try {
            compileProject(project);
            diagnostics.setSuccess(true);
        } catch (CompilationException exception) {
            diagnostics.setSuccess(false);
            diagnostics.add(stageFromMessage(exception.getMessage()), "error", exception.getMessage());
        } catch (Exception exception) {
            diagnostics.setSuccess(false);
            diagnostics.add("project", "error", exception.getMessage());
        }
        diagnostics.setElapsedMillis(Math.max(1L, (System.nanoTime() - started) / 1_000_000L));
        return diagnostics;
    }

    private static String stageFromMessage(String message) {
        if (message == null) {
            return "project";
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        for (WorldGenStage stage : WorldGenStage.values()) {
            if (lower.startsWith(stage.name().toLowerCase(java.util.Locale.ROOT))) {
                return stage.name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "project";
    }

    public static TerrainPipeline compileProject(WorldGenProject project) {
        if (project == null) throw new CompilationException("Project Missing");
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        if (!registry.hasDefinitions()) {
            WorldGenNodeDefinitions.registerDefaults(registry);
        }
        project.rebuildIndices();
        validateStage(project.getTerrainGraph(), WorldGenStage.TERRAIN, Set.of("output_height"));
        validateStage(project.getBiomeGraph(), WorldGenStage.BIOME, Set.of("output_biome"), true);
        validateStage(project.getSurfaceGraph(), WorldGenStage.SURFACE, Set.of("output_block"), true);
        validateStage(project.getCaveGraph(), WorldGenStage.CAVE, Set.of(), true);
        validateStage(project.getFeatureGraph(), WorldGenStage.FEATURE, Set.of(), true);
        validateStage(project.getStructureGraph(), WorldGenStage.STRUCTURE, Set.of(), true);
        validateStage(project.getSpawnGraph(), WorldGenStage.SPAWN, Set.of(), true);
        CompiledGraphs compiled = new CompiledGraphs();
        compileInto(compiled, "terrain", project.getTerrainGraph());
        compileInto(compiled, "biome", project.getBiomeGraph());
        compileInto(compiled, "surface", project.getSurfaceGraph());
        compileInto(compiled, "cave", project.getCaveGraph());
        compileInto(compiled, "feature", project.getFeatureGraph());
        compileInto(compiled, "structure", project.getStructureGraph());
        compileInto(compiled, "spawn", project.getSpawnGraph());
        if (compiled.heightOutput == null) throw new CompilationException("Output Height Missing");
        var settings = project.getSettings();
        Map<String, Boolean> featureOverrides = new HashMap<>(settings == null ? Map.of() : settings.getBiomeVanillaFeatureOverrides());
        Map<String, Boolean> structureOverrides = new HashMap<>();
        Map<String, Boolean> spawnOverrides = new HashMap<>();
        List<WorldGenSpawnRule> spawnRules = new ArrayList<>();
        collectProfilePolicies(project, featureOverrides, structureOverrides, spawnOverrides, spawnRules);
        collectNodePolicies(project.getBiomeGraph(), featureOverrides, structureOverrides, spawnOverrides);
        collectSpawnRules(project.getSpawnGraph(), spawnRules);
        return new TerrainPipeline(compiled.nodes, compiled.upstreams, compiled.outputNodes, compiled.heightOutput, compiled.biomeOutput,
            compiled.blockOutput, compiled.caveOutput, compiled.featureOutput, compiled.structureOutput, compiled.spawnOutput,
            settings == null || settings.isVanillaFeaturesEnabled(),
            settings == null || settings.isVanillaStructuresEnabled(),
            settings == null || settings.isVanillaSpawnsEnabled(),
            featureOverrides, structureOverrides, spawnOverrides, spawnRules);
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
            if ("climate_map".equals(node.getType())) {
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

    private static void addPolicyId(List<String> ids, Object value) {
        if (value == null) {
            return;
        }
        String id = String.valueOf(value);
        if (!id.isBlank()) {
            ids.add(id);
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

    public static TerrainPipeline compile(WorldGenGraph graph) {
        if (graph == null) throw new CompilationException("Graph Missing");
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        if (!registry.hasDefinitions()) {
            WorldGenNodeDefinitions.registerDefaults(registry);
        }
        graph.rebuildIndices();
        validateDag(graph);
        validateTypes(graph, registry);
        Map<String, PipelineNode> nodes = new HashMap<>();
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            nodes.put(entry.getKey(), createNode(entry.getValue()));
        }
        Map<String, Map<String, PipelineNode>> upstreams = new HashMap<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            PipelineNode source = nodes.get(connection.getSourceNodeId());
            if (source != null) {
                upstreams.computeIfAbsent(connection.getTargetNodeId(), key -> new HashMap<>()).put(connection.getTargetPin(), source);
            }
        }
        List<PipelineNode> outputNodes = new ArrayList<>();
        PipelineNode heightOutput = null;
        PipelineNode biomeOutput = null;
        PipelineNode blockOutput = null;
        Set<String> nodesWithOutgoing = new HashSet<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            nodesWithOutgoing.add(connection.getSourceNodeId());
        }
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            PipelineNode pipelineNode = nodes.get(entry.getKey());
            if (!nodesWithOutgoing.contains(entry.getKey())) {
                outputNodes.add(pipelineNode);
            }
            if ("output_height".equals(entry.getValue().getType())) heightOutput = pipelineNode;
            if ("output_biome".equals(entry.getValue().getType())) biomeOutput = pipelineNode;
            if ("output_block".equals(entry.getValue().getType())) blockOutput = pipelineNode;
        }
        if (heightOutput == null) throw new CompilationException("Output Height Missing");
        return new TerrainPipeline(nodes, upstreams, outputNodes, heightOutput, biomeOutput, blockOutput);
    }

    private static void compileInto(CompiledGraphs compiled, String prefix, WorldGenGraph graph) {
        if (graph == null || graph.getNodes().isEmpty()) {
            return;
        }
        Map<String, String> ids = new HashMap<>();
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            String id = prefix + ":" + entry.getKey();
            ids.put(entry.getKey(), id);
            PipelineNode node = createNode(entry.getValue());
            compiled.nodes.put(id, node);
            String type = entry.getValue().getType();
            if ("output_height".equals(type)) compiled.heightOutput = node;
            if ("output_biome".equals(type)) compiled.biomeOutput = node;
            if ("output_block".equals(type)) compiled.blockOutput = node;
            if ("carve_if".equals(type)) compiled.caveOutput = node;
            if ("output_features".equals(type)) compiled.featureOutput = node;
            if ("output_structures".equals(type)) compiled.structureOutput = node;
            if ("output_spawns".equals(type)) compiled.spawnOutput = node;
        }
        Set<String> nodesWithOutgoing = new HashSet<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            String sourceId = ids.get(connection.getSourceNodeId());
            String targetId = ids.get(connection.getTargetNodeId());
            PipelineNode source = compiled.nodes.get(sourceId);
            if (source != null && targetId != null) {
                nodesWithOutgoing.add(sourceId);
                compiled.upstreams.computeIfAbsent(targetId, key -> new HashMap<>()).put(connection.getTargetPin(), source);
            }
        }
        for (String id : ids.values()) {
            if (!nodesWithOutgoing.contains(id)) {
                PipelineNode outputNode = compiled.nodes.get(id);
                if (outputNode != null) compiled.outputNodes.add(outputNode);
            }
        }
    }

    private static void validateStage(WorldGenGraph graph, WorldGenStage stage, Set<String> requiredOutputs) {
        validateStage(graph, stage, requiredOutputs, false);
    }

    private static void validateStage(WorldGenGraph graph, WorldGenStage stage, Set<String> requiredOutputs, boolean allowEmpty) {
        if (graph == null) {
            if (allowEmpty) return;
            throw new CompilationException(stage.name() + " Graph Missing");
        }
        if (allowEmpty && graph.getNodes().isEmpty()) {
            return;
        }
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        graph.rebuildIndices();
        validateDefinitions(graph, registry);
        validateDag(graph);
        validateTypes(graph, registry);
        for (String requiredOutput : requiredOutputs) {
            boolean found = graph.getNodes().values().stream().anyMatch(node -> requiredOutput.equals(node.getType()));
            if (!found) throw new CompilationException(stage.name() + " Output Missing");
        }
    }

    private static void validateDefinitions(WorldGenGraph graph, WorldGenNodeRegistry registry) {
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node == null || registry.getDefinition(node.getType()) == null) throw new CompilationException("Unsupported Node " + (node == null ? entry.getKey() : node.getType()));
        }
    }

    public static void invalidate(String graphId) {
    }

    private static void validateDag(WorldGenGraph graph) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        graph.getNodes().keySet().forEach(id -> incoming.put(id, 0));
        for (WorldGenConnection connection : graph.getConnections()) {
            incoming.compute(connection.getTargetNodeId(), (key, value) -> value == null ? 1 : value + 1);
            outgoing.computeIfAbsent(connection.getSourceNodeId(), key -> new ArrayList<>()).add(connection.getTargetNodeId());
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) queue.add(id);
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(nodeId, List.of())) {
                int count = incoming.compute(target, (key, value) -> value == null ? 0 : value - 1);
                if (count == 0) queue.add(target);
            }
        }
        if (visited != graph.getNodes().size()) throw new CompilationException("Cycle Detected");
    }

    private static void validateTypes(WorldGenGraph graph, WorldGenNodeRegistry registry) {
        for (WorldGenConnection connection : graph.getConnections()) {
            WorldGenNode sourceNode = graph.getNodes().get(connection.getSourceNodeId());
            WorldGenNode targetNode = graph.getNodes().get(connection.getTargetNodeId());
            if (sourceNode == null || targetNode == null) throw new CompilationException("Connection Node Missing");
            WorldGenNodeDefinition sourceDefinition = registry.getDefinition(sourceNode.getType());
            WorldGenNodeDefinition targetDefinition = registry.getDefinition(targetNode.getType());
            if (sourceDefinition == null || targetDefinition == null) throw new CompilationException("Node Definition Missing");
            WorldGenNodeDefinition.PinDefinition sourcePin = sourceDefinition.output(connection.getSourcePin());
            WorldGenNodeDefinition.PinDefinition targetPin = targetDefinition.input(connection.getTargetPin());
            if (sourcePin == null || targetPin == null) throw new CompilationException("Pin Definition Missing");
            if (!sourcePin.dataType().equals(targetPin.dataType())) throw new CompilationException("Type Mismatch");
        }
    }

    private static PipelineNode createNode(WorldGenNode node) {
        return switch (node.getType()) {
            case "simplex" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "perlin" -> (ctx, upstreams) -> NoiseEvaluator.evaluatePerlin(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "value" -> (ctx, upstreams) -> NoiseEvaluator.evaluateValue(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "cellular" -> (ctx, upstreams) -> NoiseEvaluator.evaluateCellular(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f), String.valueOf(input(node, upstreams, "distance_func", "euclidean")));
            case "white" -> (ctx, upstreams) -> NoiseEvaluator.evaluateWhite(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx));
            case "fbm" -> (ctx, upstreams) -> FractalEvaluator.evaluateFBM(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f));
            case "ridged" -> (ctx, upstreams) -> FractalEvaluator.evaluateRidged(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f));
            case "ping_pong" -> (ctx, upstreams) -> FractalEvaluator.evaluatePingPong(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f), inputFloat(node, upstreams, "ping_pong_strength", 2f));
            case "add" -> (ctx, upstreams) -> inputFloat(node, upstreams, "a", 0f) + inputFloat(node, upstreams, "b", 0f);
            case "multiply" -> (ctx, upstreams) -> inputFloat(node, upstreams, "a", 1f) * inputFloat(node, upstreams, "b", 1f);
            case "remap" -> (ctx, upstreams) -> remap(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "from_min", -1f), inputFloat(node, upstreams, "from_max", 1f), inputFloat(node, upstreams, "to_min", 0f), inputFloat(node, upstreams, "to_max", 128f));
            case "clamp" -> (ctx, upstreams) -> clamp(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "min", 0f), inputFloat(node, upstreams, "max", 1f));
            case "abs" -> (ctx, upstreams) -> Math.abs(inputFloat(node, upstreams, "in", 0f));
            case "min" -> (ctx, upstreams) -> Math.min(inputFloat(node, upstreams, "a", 0f), inputFloat(node, upstreams, "b", 0f));
            case "max" -> (ctx, upstreams) -> Math.max(inputFloat(node, upstreams, "a", 0f), inputFloat(node, upstreams, "b", 0f));
            case "domain_warp_gradient", "domain_warp_simplex" -> (ctx, upstreams) -> inputFloat(node, upstreams, "source", 0f) + NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f)) * inputFloat(node, upstreams, "amplitude", 1f);
            case "terrace" -> (ctx, upstreams) -> terrace(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "step_count", 8f));
            case "seed_offset" -> (ctx, upstreams) -> inputFloat(node, upstreams, "in", 0f);
            case "output_height" -> (ctx, upstreams) -> inputFloat(node, upstreams, "height", 64f);
            case "output_biome" -> (ctx, upstreams) -> {
                Object value = input(node, upstreams, "biome", inputFloat(node, upstreams, "biome", 0f));
                return biomeChoice(node, value);
            };
            case "output_block" -> (ctx, upstreams) -> input(node, upstreams, "block", null);
            case "biome_constant" -> (ctx, upstreams) -> biomeChoice(node, input(node, upstreams, "biome", "minecraft:plains"));
            case "biome_profile" -> (ctx, upstreams) -> biomeChoice(node, input(node, upstreams, "profile", "minecraft:plains"));
            case "biome_select" -> (ctx, upstreams) -> biomeChoice(node, inputBoolean(node, upstreams, "mask", false) ? input(node, upstreams, "true_biome", "minecraft:forest") : input(node, upstreams, "false_biome", "minecraft:plains"));
            case "biome_blend" -> (ctx, upstreams) -> inputFloat(node, upstreams, "weight", 0.5f) >= 0.5f ? input(node, upstreams, "b", "minecraft:forest") : input(node, upstreams, "a", "minecraft:plains");
            case "climate_map" -> (ctx, upstreams) -> biomeChoice(node, climateBiome(inputFloat(node, upstreams, "temperature", 0.5f), inputFloat(node, upstreams, "humidity", 0.5f)));
            case "temperature", "humidity", "continentalness", "erosion", "weirdness" -> (ctx, upstreams) -> inputFloat(node, upstreams, "value", 0f);
            case "surface_rule" -> (ctx, upstreams) -> input(node, upstreams, "top", "minecraft:grass_block");
            case "material_layer" -> (ctx, upstreams) -> input(node, upstreams, "block", "minecraft:stone");
            case "height_band" -> (ctx, upstreams) -> ctx.y() >= inputFloat(node, upstreams, "min", 0f) && ctx.y() <= inputFloat(node, upstreams, "max", 320f);
            case "slope_mask" -> (ctx, upstreams) -> true;
            case "beach_rule" -> (ctx, upstreams) -> input(node, upstreams, "sand", "minecraft:sand");
            case "underwater_rule" -> (ctx, upstreams) -> input(node, upstreams, "block", "minecraft:gravel");
            case "snow_rule" -> (ctx, upstreams) -> input(node, upstreams, "block", "minecraft:snow_block");
            case "cave_noise" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.02f));
            case "worm_cave" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.015f) + inputFloat(node, upstreams, "radius", 3f) * 0.05f;
            case "cheese_cave" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.04f) - inputFloat(node, upstreams, "threshold", 0.6f);
            case "ravine" -> (ctx, upstreams) -> Math.abs(NoiseEvaluator.evaluateSimplex(ctx.x(), 0f, ctx.z(), seed(node, upstreams, ctx), 0.01f)) - inputFloat(node, upstreams, "width", 6f) * 0.01f;
            case "carve_if" -> (ctx, upstreams) -> inputBoolean(node, upstreams, "mask", true) ? inputFloat(node, upstreams, "density", 1f) : 1f;
            case "density_combine" -> (ctx, upstreams) -> Math.min(inputFloat(node, upstreams, "a", 1f), inputFloat(node, upstreams, "b", 1f));
            case "ore_vein" -> (ctx, upstreams) -> "ore:" + input(node, upstreams, "block", "minecraft:coal_ore") + ":" + inputFloat(node, upstreams, "size", 8f);
            case "tree_feature" -> (ctx, upstreams) -> "tree:" + input(node, upstreams, "tree", "oak");
            case "vegetation_patch" -> (ctx, upstreams) -> "patch:" + input(node, upstreams, "block", "minecraft:grass");
            case "liquid_lake" -> (ctx, upstreams) -> "lake:" + input(node, upstreams, "fluid", "minecraft:water");
            case "disk" -> (ctx, upstreams) -> "disk:" + input(node, upstreams, "block", "minecraft:clay") + ":" + inputFloat(node, upstreams, "radius", 4f);
            case "boulder" -> (ctx, upstreams) -> "boulder:" + input(node, upstreams, "block", "minecraft:mossy_cobblestone");
            case "scatter" -> (ctx, upstreams) -> input(node, upstreams, "feature", "") + "|scatter|" + inputFloat(node, upstreams, "chance", 0.1f);
            case "poisson_scatter" -> (ctx, upstreams) -> input(node, upstreams, "feature", "") + "|poisson|" + inputFloat(node, upstreams, "spacing", 12f);
            case "biome_filter" -> (ctx, upstreams) -> true;
            case "height_filter" -> (ctx, upstreams) -> ctx.y() >= inputFloat(node, upstreams, "min", 0f) && ctx.y() <= inputFloat(node, upstreams, "max", 320f);
            case "chance_filter" -> (ctx, upstreams) -> deterministicChance(ctx.x(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "chance", 0.5f));
            case "structure_placement" -> (ctx, upstreams) -> "structure:" + input(node, upstreams, "structure_id", "") + ":" + Math.round(inputFloat(node, upstreams, "spacing", 32f)) + ":" + Math.round(inputFloat(node, upstreams, "separation", 8f)) + ":" + seed(node, upstreams, ctx);
            case "spawn_rule" -> (ctx, upstreams) -> "spawn:" + input(node, upstreams, "entity", "minecraft:zombie") + ":" + Math.round(inputFloat(node, upstreams, "weight", 10f)) + ":" + Math.round(inputFloat(node, upstreams, "min_group", 1f)) + ":" + Math.round(inputFloat(node, upstreams, "max_group", 4f));
            case "output_features" -> (ctx, upstreams) -> input(node, upstreams, "placements", "");
            case "output_structures" -> (ctx, upstreams) -> input(node, upstreams, "placements", "");
            case "output_spawns" -> (ctx, upstreams) -> input(node, upstreams, "table", "");
            default -> throw new CompilationException("Unsupported Node " + node.getType());
        };
    }

    private static Object input(WorldGenNode node, Map<String, PipelineNode> upstreams, String pin, Object fallback) {
        return TerrainPipeline.input(upstreams, pin, node.getInputValues().getOrDefault(pin, fallback));
    }

    private static float inputFloat(WorldGenNode node, Map<String, PipelineNode> upstreams, String pin, float fallback) {
        Object value = input(node, upstreams, pin, fallback);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int seed(WorldGenNode node, Map<String, PipelineNode> upstreams, EvalContext context) {
        Object value = input(node, upstreams, "seed", context.seed());
        return value instanceof Number number ? number.intValue() + context.seed() : context.seed();
    }

    private static boolean inputBoolean(WorldGenNode node, Map<String, PipelineNode> upstreams, String pin, boolean fallback) {
        Object value = input(node, upstreams, pin, fallback);
        if (value instanceof Boolean bool) return bool;
        return fallback;
    }

    private static BiomeChoice biomeChoice(WorldGenNode node, Object value) {
        if (value instanceof BiomeChoice choice) {
            return new BiomeChoice(choice.biomeId(), booleanValue(node.getInputValues().get("keep_vanilla_features"), choice.keepVanillaFeatures()),
                booleanValue(node.getInputValues().get("keep_vanilla_structures"), choice.keepVanillaStructures()),
                booleanValue(node.getInputValues().get("keep_vanilla_spawns"), choice.keepVanillaSpawns()));
        }
        return new BiomeChoice(TerrainPipeline.normalizeBiomeId(value),
            booleanValue(node.getInputValues().get("keep_vanilla_features"), false),
            booleanValue(node.getInputValues().get("keep_vanilla_structures"), false),
            booleanValue(node.getInputValues().get("keep_vanilla_spawns"), false));
    }

    private static String climateBiome(float temperature, float humidity) {
        if (temperature < 0.25f) return "minecraft:snowy_plains";
        if (temperature > 0.8f && humidity < 0.35f) return "minecraft:desert";
        if (humidity > 0.7f) return "minecraft:forest";
        if (temperature > 0.7f) return "minecraft:savanna";
        return "minecraft:plains";
    }

    private static boolean deterministicChance(float x, float z, int seed, float chance) {
        long hash = 1469598103934665603L;
        hash ^= Math.round(x) * 1099511628211L;
        hash ^= Math.round(z) * 1402946736689701973L;
        hash ^= seed * 1609587929392839161L;
        double value = ((hash >>> 11) & 0xFFFFFF) / (double) 0xFFFFFF;
        return value < chance;
    }

    private static float remap(float value, float fromMin, float fromMax, float toMin, float toMax) {
        if (fromMax == fromMin) return toMin;
        return toMin + (value - fromMin) / (fromMax - fromMin) * (toMax - toMin);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float terrace(float value, float steps) {
        float count = Math.max(1f, steps);
        return Math.round(value * count) / count;
    }

    private static class CompiledGraphs {
        private final Map<String, PipelineNode> nodes = new HashMap<>();
        private final Map<String, Map<String, PipelineNode>> upstreams = new HashMap<>();
        private final List<PipelineNode> outputNodes = new ArrayList<>();
        private PipelineNode heightOutput;
        private PipelineNode biomeOutput;
        private PipelineNode blockOutput;
        private PipelineNode caveOutput;
        private PipelineNode featureOutput;
        private PipelineNode structureOutput;
        private PipelineNode spawnOutput;
    }
}
