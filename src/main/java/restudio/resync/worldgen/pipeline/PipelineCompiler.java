package restudio.resync.worldgen.pipeline;

import restudio.flow.data.FlowDataType;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenStage;
import restudio.resync.worldgen.evaluator.FractalEvaluator;
import restudio.resync.worldgen.evaluator.NoiseEvaluator;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;
import org.bukkit.Material;

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
            addCapabilityWarnings(project, diagnostics);
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

    private static void addCapabilityWarnings(WorldGenProject project, WorldGenCompileDiagnostics diagnostics) {
        if (project == null) {
            return;
        }
        if (hasNodes(project.getFeatureGraph())) {
            diagnostics.add("feature", "warning", "Datapack feature live activation is not supported yet");
        }
        if (hasNodes(project.getStructureGraph())) {
            diagnostics.add("structure", "warning", "Datapack structure live activation is not supported yet");
        }
    }

    private static boolean hasNodes(WorldGenGraph graph) {
        return graph != null && graph.getNodes() != null && !graph.getNodes().isEmpty();
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
        PipelineValidator.validateTerrainStage(project.getTerrainGraph());
        PipelineValidator.validateStage(project.getBiomeGraph(), WorldGenStage.BIOME, Set.of("output_biome"), true);
        PipelineValidator.validateStage(project.getSurfaceGraph(), WorldGenStage.SURFACE, Set.of("output_block"), true);
        PipelineValidator.validateStage(project.getCaveGraph(), WorldGenStage.CAVE, Set.of(), true);
        PipelineValidator.validateStage(project.getFeatureGraph(), WorldGenStage.FEATURE, Set.of(), true);
        PipelineValidator.validateStage(project.getStructureGraph(), WorldGenStage.STRUCTURE, Set.of(), true);
        PipelineValidator.validateStage(project.getSpawnGraph(), WorldGenStage.SPAWN, Set.of(), true);
        CompiledGraphs compiled = new CompiledGraphs();
        compileInto(compiled, "terrain", project.getTerrainGraph());
        compileInto(compiled, "biome", project.getBiomeGraph());
        compileInto(compiled, "surface", project.getSurfaceGraph());
        compileInto(compiled, "cave", project.getCaveGraph());
        compileInto(compiled, "feature", project.getFeatureGraph());
        compileInto(compiled, "structure", project.getStructureGraph());
        compileInto(compiled, "spawn", project.getSpawnGraph());
        if (compiled.heightOutput == null && compiled.densityOutput == null) throw new CompilationException("Terrain Output Missing");
        var settings = project.getSettings();
        BiomePolicyCompiler.Result biomePolicy = BiomePolicyCompiler.compile(project);
        return new TerrainPipeline(compiled.nodes, compiled.upstreams, compiled.outputNodes, compiled.heightOutput, compiled.densityOutput,
            compiled.continentalnessOutput, compiled.erosionOutput, compiled.weirdnessOutput, compiled.depthOutput,
            compiled.temperatureOutput, compiled.humidityOutput, compiled.biomeOutput, compiled.blockOutput, compiled.caveOutput, compiled.featureOutput, compiled.structureOutput, compiled.spawnOutput,
            biomePolicy.policy(), settings == null ? 63 : settings.getSeaLevel(),
            TerrainPipeline.material(settings == null ? "minecraft:stone" : settings.getDefaultBlock(), Material.STONE),
            TerrainPipeline.material(settings == null ? "minecraft:water" : settings.getDefaultFluid(), Material.WATER),
            biomePolicy.spawnRules());
    }

    public static TerrainPipeline compile(WorldGenGraph graph) {
        if (graph == null) throw new CompilationException("Graph Missing");
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        if (!registry.hasDefinitions()) {
            WorldGenNodeDefinitions.registerDefaults(registry);
        }
        graph.rebuildIndices();
        PipelineValidator.validateDag(graph);
        PipelineValidator.validateTypes(graph, registry);
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
            if ("output_density".equals(type)) compiled.densityOutput = node;
            if ("output_continentalness".equals(type)) compiled.continentalnessOutput = node;
            if ("output_erosion".equals(type)) compiled.erosionOutput = node;
            if ("output_weirdness".equals(type)) compiled.weirdnessOutput = node;
            if ("output_depth".equals(type)) compiled.depthOutput = node;
            if ("output_temperature".equals(type)) compiled.temperatureOutput = node;
            if ("output_humidity".equals(type)) compiled.humidityOutput = node;
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

    public static void invalidate(String graphId) {
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
            case "continental_shelf" -> (ctx, upstreams) -> remap(NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.0016f / Math.max(0.05f, inputFloat(node, upstreams, "scale", 1f))), -1f, 1f, 28f, 112f) - inputFloat(node, upstreams, "ocean", 0.42f) * 24f;
            case "mountain_range" -> (ctx, upstreams) -> clamp((Math.abs(NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.0048f / Math.max(0.05f, inputFloat(node, upstreams, "scale", 1f)))) - 0.22f) * 180f * inputFloat(node, upstreams, "amount", 1f), 0f, 148f);
            case "river_network" -> (ctx, upstreams) -> clamp(remap(NoiseEvaluator.evaluateCellular(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.0025f * Math.max(0.05f, inputFloat(node, upstreams, "density", 1f)), "euclidean"), 0f, 0.09f, -inputFloat(node, upstreams, "depth", 24f), 0f), -inputFloat(node, upstreams, "depth", 24f), 0f);
            case "eroded_peaks" -> (ctx, upstreams) -> terrace(clamp((Math.abs(NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.0065f)) - 0.15f) * 150f * inputFloat(node, upstreams, "amount", 1f), 0f, 128f), inputFloat(node, upstreams, "terraces", 12f));
            case "badlands_plateau" -> (ctx, upstreams) -> terrace(clamp(inputFloat(node, upstreams, "height", 86f) + NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.009f) * inputFloat(node, upstreams, "erosion", 0.55f) * 30f, 42f, 156f), 9f);
            case "volcanic_field" -> (ctx, upstreams) -> clamp(inputFloat(node, upstreams, "height", 72f) + NoiseEvaluator.evaluateCellular(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.007f, "euclidean") * inputFloat(node, upstreams, "roughness", 0.7f) * 80f, 30f, 190f);
            case "density_from_height" -> (ctx, upstreams) -> (inputFloat(node, upstreams, "height", 64f) - ctx.y()) / Math.max(1f, inputFloat(node, upstreams, "falloff", 12f));
            case "terrain_density" -> (ctx, upstreams) -> {
                float continentalness = inputFloat(node, upstreams, "continentalness", 0f);
                float erosion = inputFloat(node, upstreams, "erosion", 0f);
                float weirdness = inputFloat(node, upstreams, "weirdness", 0f);
                float depth = inputFloat(node, upstreams, "depth", 0f);
                float base = inputFloat(node, upstreams, "base", 64f);
                float ridge = Math.max(0f, weirdness) * 48f;
                float valley = Math.max(0f, -erosion) * 28f;
                float land = continentalness * 54f;
                float targetHeight = base + land + ridge - valley + depth * 24f;
                float caveNoise = NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx) + 977, 0.018f);
                return (targetHeight - ctx.y()) / 18f + caveNoise * 0.18f;
            };
            case "output_height" -> (ctx, upstreams) -> inputFloat(node, upstreams, "height", 64f);
            case "output_density" -> (ctx, upstreams) -> inputFloat(node, upstreams, "density", 0f);
            case "output_continentalness" -> (ctx, upstreams) -> inputFloat(node, upstreams, "continentalness", 0f);
            case "output_erosion" -> (ctx, upstreams) -> inputFloat(node, upstreams, "erosion", 0f);
            case "output_weirdness" -> (ctx, upstreams) -> inputFloat(node, upstreams, "weirdness", 0f);
            case "output_depth" -> (ctx, upstreams) -> inputFloat(node, upstreams, "depth", 0f);
            case "output_temperature" -> (ctx, upstreams) -> inputFloat(node, upstreams, "temperature", 0.5f);
            case "output_humidity" -> (ctx, upstreams) -> inputFloat(node, upstreams, "humidity", 0.5f);
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
            case "biome_climate_router" -> (ctx, upstreams) -> {
                float temperature = inputFloat(node, upstreams, "temperature", Float.NaN);
                float humidity = inputFloat(node, upstreams, "humidity", Float.NaN);
                float continentalness = inputFloat(node, upstreams, "continentalness", Float.NaN);
                float erosion = inputFloat(node, upstreams, "erosion", Float.NaN);
                float weirdness = inputFloat(node, upstreams, "weirdness", Float.NaN);
                if (Float.isNaN(temperature)) {
                    temperature = clamp(remap(NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.0018f * inputFloat(node, upstreams, "temperature_scale", 1f)), -1f, 1f, 0f, 1f), 0f, 1f);
                }
                if (Float.isNaN(humidity)) {
                    humidity = clamp(remap(NoiseEvaluator.evaluatePerlin(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx) + 411, 0.002f * inputFloat(node, upstreams, "humidity_scale", 1f)), -1f, 1f, 0f, 1f), 0f, 1f);
                }
                return biomeChoice(node, climateBiome(temperature, humidity, Float.isNaN(continentalness) ? 0f : continentalness, Float.isNaN(erosion) ? 0f : erosion, Float.isNaN(weirdness) ? 0f : weirdness));
            };
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
            case "cave_system" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), 0.018f * inputFloat(node, upstreams, "scale", 1f)) - 0.42f / Math.max(0.05f, inputFloat(node, upstreams, "amount", 1f));
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
            case "structure_placement" -> (ctx, upstreams) -> "structure:" + input(node, upstreams, "structure_id", "") + ":" + Math.round(inputFloat(node, upstreams, "spacing", 32f)) + ":" + Math.round(inputFloat(node, upstreams, "separation", 8f)) + ":" + seed(node, upstreams, ctx) + ":" + Math.round(inputFloat(node, upstreams, "y_offset", 0f));
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
        long worldSeed = context.seed();
        long nodeSeed = value instanceof Number number ? number.longValue() : 0L;
        return Long.hashCode(worldSeed + nodeSeed);
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

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String climateBiome(float temperature, float humidity) {
        return climateBiome(temperature, humidity, 0f, 0f, 0f);
    }

    private static String climateBiome(float temperature, float humidity, float continentalness, float erosion, float weirdness) {
        if (continentalness < -0.45f) return temperature < 0.25f ? "minecraft:frozen_ocean" : "minecraft:ocean";
        if (Math.abs(continentalness) < 0.12f && erosion < -0.25f) return "minecraft:river";
        if (weirdness > 0.55f && temperature < 0.55f) return "minecraft:stony_peaks";
        if (weirdness > 0.45f) return "minecraft:windswept_hills";
        if (erosion < -0.5f && humidity > 0.55f) return "minecraft:old_growth_pine_taiga";
        if (temperature < 0.25f) return "minecraft:snowy_plains";
        if (temperature > 0.8f && humidity < 0.35f) return "minecraft:desert";
        if (temperature > 0.85f && humidity > 0.65f) return "minecraft:jungle";
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
        private PipelineNode densityOutput;
        private PipelineNode continentalnessOutput;
        private PipelineNode erosionOutput;
        private PipelineNode weirdnessOutput;
        private PipelineNode depthOutput;
        private PipelineNode temperatureOutput;
        private PipelineNode humidityOutput;
        private PipelineNode biomeOutput;
        private PipelineNode blockOutput;
        private PipelineNode caveOutput;
        private PipelineNode featureOutput;
        private PipelineNode structureOutput;
        private PipelineNode spawnOutput;
    }
}
