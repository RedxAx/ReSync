package restudio.resync.worldgen.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.data.WorldGenBiomeProfile;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenProjectSettings;
import restudio.resync.worldgen.data.WorldGenSpawnRule;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WorldGenDatapackCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Plugin plugin;

    public WorldGenDatapackCompiler(Plugin plugin) {
        this.plugin = plugin;
    }

    public WorldGenDatapackBuild compile(WorldGenProject project, Path outputRoot, long revision) {
        if (project == null) {
            throw new IllegalArgumentException("WorldGen Project Missing");
        }
        project.rebuildIndices();
        TerrainPipeline pipeline = PipelineCompiler.compileProject(project);
        WorldGenProjectSettings settings = project.getSettings() == null ? new WorldGenProjectSettings() : project.getSettings();
        String namespace = namespace(settings.getDatapackNamespace());
        String projectId = safeId(project.getId() == null || project.getId().isBlank() ? "project" : project.getId());
        String packName = "resync_worldgen_" + projectId + "_" + revision;
        Path folder = outputRoot.resolve(projectId).resolve(String.valueOf(revision)).resolve(packName).normalize();
        List<String> warnings = new ArrayList<>();
        try {
            deleteDirectory(folder);
            Files.createDirectories(folder);
            Files.createDirectories(folder.resolve("data").resolve(namespace).resolve("worldgen"));
            int count = 0;
            count += writeJson(folder.resolve("pack.mcmeta"), packMeta(project, packFormat(), minecraftVersion()));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("dimension_type").resolve("worldgen.json"), dimensionType(settings));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("noise_settings").resolve("worldgen.json"), noiseSettings(project, settings));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("world_preset").resolve("worldgen.json"), worldPreset(namespace));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("multi_noise_biome_source_parameter_list").resolve("worldgen.json"), biomeParameters(namespace));
            count += writeBiomes(folder, namespace, project, pipeline, warnings);
            count += writeFeatures(folder, namespace, project, warnings);
            count += writeStructures(folder, namespace, project, warnings);
            count += writeJson(folder.resolve("resync-manifest.json"), manifest(project, namespace, packName, revision, count, warnings));
            WorldGenDatapackBuild build = new WorldGenDatapackBuild();
            build.setProjectId(project.getId());
            build.setNamespace(namespace);
            build.setPackName(packName);
            build.setMinecraftVersion(minecraftVersion());
            build.setPackFormat(packFormat());
            build.setRevision(revision);
            build.setFolder(folder);
            build.setFileCount(count);
            build.setWarnings(warnings);
            return build;
        } catch (IOException exception) {
            throw new IllegalStateException("Datapack Compile Failed: " + exception.getMessage(), exception);
        }
    }

    public Path generatedRoot() {
        return plugin.getDataFolder().toPath().resolve("worldgen").resolve("generated").toAbsolutePath().normalize();
    }

    private int writeBiomes(Path folder, String namespace, WorldGenProject project, TerrainPipeline pipeline, List<String> warnings) throws IOException {
        Set<String> biomes = collectBiomeIds(project);
        if (biomes.isEmpty()) {
            biomes.add("minecraft:plains");
        }
        int count = 0;
        for (String biomeId : biomes) {
            String local = localId(biomeId);
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("biome").resolve(local + ".json"), biome(project, pipeline, biomeId, namespace));
        }
        return count;
    }

    private int writeFeatures(Path folder, String namespace, WorldGenProject project, List<String> warnings) throws IOException {
        int count = 0;
        List<String> placements = collectFeaturePlacements(project.getFeatureGraph());
        if (placements.isEmpty()) {
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("configured_feature").resolve("short_grass.json"), configuredPatch("minecraft:short_grass"));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("placed_feature").resolve("short_grass.json"), placedFeature(namespace + ":short_grass", 18, 0, 320));
            return count;
        }
        int index = 0;
        for (String placement : placements) {
            String id = "feature_" + index++;
            Object configured = configuredFeature(placement, warnings);
            if (configured == null) {
                continue;
            }
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("configured_feature").resolve(id + ".json"), configured);
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("placed_feature").resolve(id + ".json"), placedFeature(namespace + ":" + id, 8, 0, 320));
        }
        return count;
    }

    private int writeStructures(Path folder, String namespace, WorldGenProject project, List<String> warnings) throws IOException {
        List<String> structures = collectStructurePlacements(project.getStructureGraph());
        if (structures.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        for (String placement : structures) {
            StructureData data = structureData(placement);
            if (data.id().isBlank()) {
                warnings.add("Structure placement missing structure_id");
                continue;
            }
            String local = "structure_" + index++;
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("structure_set").resolve(local + ".json"), structureSet(data));
        }
        return count;
    }

    private Map<String, Object> packMeta(WorldGenProject project, int format, String version) {
        Map<String, Object> pack = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pack_format", format);
        meta.put("description", "ReSync WorldGen " + project.getId() + " for Minecraft " + version);
        pack.put("pack", meta);
        return pack;
    }

    private Map<String, Object> manifest(WorldGenProject project, String namespace, String packName, long revision, int files, List<String> warnings) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("projectId", project.getId());
        manifest.put("namespace", namespace);
        manifest.put("packName", packName);
        manifest.put("revision", revision);
        manifest.put("minecraftVersion", minecraftVersion());
        manifest.put("packFormat", packFormat());
        manifest.put("fileCount", files);
        manifest.put("warnings", warnings);
        manifest.put("fingerprint", fingerprint(project.getId() + ":" + revision + ":" + namespace));
        return manifest;
    }

    private Map<String, Object> noiseSettings(WorldGenProject project, WorldGenProjectSettings settings) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sea_level", settings.getSeaLevel());
        value.put("disable_mob_generation", false);
        value.put("aquifers_enabled", true);
        value.put("ore_veins_enabled", true);
        value.put("legacy_random_source", false);
        value.put("default_block", Map.of("Name", block(settings.getDefaultBlock(), "minecraft:stone")));
        value.put("default_fluid", Map.of("Name", block(settings.getDefaultFluid(), "minecraft:water")));
        value.put("noise", Map.of("min_y", settings.getMinY(), "height", Math.max(384, settings.getMaxY() - settings.getMinY()), "size_horizontal", 1, "size_vertical", 2));
        value.put("noise_router", vanillaNoiseRouter(settings.getTerrainTemplate()));
        value.put("surface_rule", surfaceRule(settings));
        value.put("spawn_target", List.of());
        return value;
    }

    private Map<String, Object> dimensionType(WorldGenProjectSettings settings) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ultrawarm", false);
        value.put("natural", true);
        value.put("coordinate_scale", 1.0);
        value.put("has_skylight", true);
        value.put("has_ceiling", false);
        value.put("ambient_light", 0.0);
        value.put("fixed_time", null);
        value.put("piglin_safe", false);
        value.put("bed_works", true);
        value.put("respawn_anchor_works", false);
        value.put("has_raids", true);
        value.put("logical_height", Math.max(384, settings.getMaxY()));
        value.put("min_y", settings.getMinY());
        value.put("height", Math.max(384, settings.getMaxY() - settings.getMinY()));
        value.put("infiniburn", "#minecraft:infiniburn_overworld");
        value.put("effects", "minecraft:overworld");
        value.put("monster_spawn_light_level", Map.of("type", "minecraft:uniform", "value", Map.of("min_inclusive", 0, "max_inclusive", 7)));
        value.put("monster_spawn_block_light_limit", 0);
        return value;
    }

    private Map<String, Object> worldPreset(String namespace) {
        Map<String, Object> overworld = new LinkedHashMap<>();
        overworld.put("type", namespace + ":worldgen");
        overworld.put("generator", Map.of("type", "minecraft:noise", "settings", namespace + ":worldgen", "biome_source", Map.of("type", "minecraft:multi_noise", "preset", namespace + ":worldgen")));
        return Map.of("dimensions", Map.of("minecraft:overworld", overworld));
    }

    private Map<String, Object> biomeParameters(String namespace) {
        List<Object> values = new ArrayList<>();
        values.add(parameter(namespace + ":plains", 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0L));
        values.add(parameter(namespace + ":forest", 0.7, 0.8, 0.1, 0.0, 0.0, 0.0, 0L));
        values.add(parameter(namespace + ":desert", 1.0, 0.0, 0.2, 0.0, 0.0, 0.0, 0L));
        values.add(parameter(namespace + ":savanna", 0.9, 0.25, 0.15, 0.0, 0.0, 0.0, 0L));
        values.add(parameter(namespace + ":snowy_plains", 0.0, 0.5, 0.1, 0.0, 0.0, 0.0, 0L));
        return Map.of("values", values);
    }

    private Map<String, Object> parameter(String biome, double temperature, double humidity, double continentalness, double erosion, double weirdness, double depth, long offset) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("temperature", temperature);
        parameters.put("humidity", humidity);
        parameters.put("continentalness", continentalness);
        parameters.put("erosion", erosion);
        parameters.put("weirdness", weirdness);
        parameters.put("depth", depth);
        parameters.put("offset", offset);
        return Map.of("biome", biome, "parameters", parameters);
    }

    private Map<String, Object> biome(WorldGenProject project, TerrainPipeline pipeline, String biomeId, String namespace) {
        Biome biome = TerrainPipeline.biome(biomeId, Biome.PLAINS);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("temperature", temperature(project, biomeId, biome));
        value.put("downfall", downfall(project, biomeId, biome));
        value.put("has_precipitation", biome != Biome.DESERT && biome != Biome.SAVANNA);
        value.put("effects", effects(biome));
        value.put("spawners", spawners(project, biomeId, pipeline.isVanillaSpawnsEnabled(biome)));
        value.put("spawn_costs", Map.of());
        value.put("carvers", Map.of("air", List.of()));
        value.put("features", features(namespace, pipeline.isVanillaFeaturesEnabled(biome), biome));
        return value;
    }

    private List<Object> features(String namespace, boolean keepVanilla, Biome biome) {
        List<Object> stages = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            stages.add(new ArrayList<>());
        }
        if (keepVanilla) {
            ((List<Object>) stages.get(8)).addAll(vanillaVegetalFeatures(biome));
            ((List<Object>) stages.get(4)).add("minecraft:ore_dirt");
            ((List<Object>) stages.get(4)).add("minecraft:ore_gravel");
            ((List<Object>) stages.get(4)).add("minecraft:ore_coal_upper");
            ((List<Object>) stages.get(4)).add("minecraft:ore_iron_upper");
            ((List<Object>) stages.get(4)).add("minecraft:ore_copper");
            ((List<Object>) stages.get(4)).add("minecraft:ore_gold");
            ((List<Object>) stages.get(4)).add("minecraft:ore_redstone");
            ((List<Object>) stages.get(4)).add("minecraft:ore_diamond");
        } else {
            ((List<Object>) stages.get(8)).add(namespace + ":short_grass");
        }
        return stages;
    }

    private List<String> vanillaVegetalFeatures(Biome biome) {
        if (biome == Biome.FOREST || biome == Biome.BIRCH_FOREST || biome == Biome.DARK_FOREST) {
            return List.of("minecraft:forest_flowers", "minecraft:trees_plains", "minecraft:flower_default", "minecraft:patch_grass_forest");
        }
        if (biome == Biome.DESERT) {
            return List.of("minecraft:patch_dead_bush", "minecraft:patch_cactus_desert");
        }
        if (biome == Biome.SAVANNA || biome == Biome.SAVANNA_PLATEAU) {
            return List.of("minecraft:trees_savanna", "minecraft:patch_grass_savanna");
        }
        if (biome == Biome.SNOWY_PLAINS || biome == Biome.ICE_SPIKES) {
            return List.of("minecraft:patch_grass_plain");
        }
        return List.of("minecraft:trees_plains", "minecraft:flower_plain", "minecraft:patch_grass_plain");
    }

    private Map<String, Object> effects(Biome biome) {
        Map<String, Object> effects = new LinkedHashMap<>();
        effects.put("sky_color", biome == Biome.DESERT ? 7907327 : 7972607);
        effects.put("water_fog_color", 329011);
        effects.put("fog_color", 12638463);
        effects.put("water_color", biome == Biome.SWAMP ? 6388580 : 4159204);
        effects.put("grass_color_modifier", biome == Biome.DARK_FOREST ? "dark_forest" : "none");
        return effects;
    }

    private Map<String, Object> spawners(WorldGenProject project, String biomeId, boolean keepVanilla) {
        Map<String, Object> spawners = new LinkedHashMap<>();
        spawners.put("monster", new ArrayList<>());
        spawners.put("creature", new ArrayList<>());
        spawners.put("ambient", new ArrayList<>());
        spawners.put("axolotls", new ArrayList<>());
        spawners.put("underground_water_creature", new ArrayList<>());
        spawners.put("water_creature", new ArrayList<>());
        spawners.put("water_ambient", new ArrayList<>());
        spawners.put("misc", new ArrayList<>());
        if (keepVanilla) {
            ((List<Object>) spawners.get("monster")).add(spawn("minecraft:zombie", 95, 4, 4));
            ((List<Object>) spawners.get("monster")).add(spawn("minecraft:skeleton", 100, 4, 4));
            ((List<Object>) spawners.get("monster")).add(spawn("minecraft:creeper", 100, 4, 4));
            ((List<Object>) spawners.get("creature")).add(spawn("minecraft:sheep", 12, 4, 4));
            ((List<Object>) spawners.get("creature")).add(spawn("minecraft:pig", 10, 4, 4));
            return spawners;
        }
        for (WorldGenSpawnRule rule : collectSpawnRules(project)) {
            String category = safeCategory(rule.getCategory());
            ((List<Object>) spawners.computeIfAbsent(category, ignored -> new ArrayList<>())).add(spawn(rule.getEntityType(), rule.getWeight(), rule.getMinGroup(), rule.getMaxGroup()));
        }
        return spawners;
    }

    private Map<String, Object> spawn(String entity, int weight, int minGroup, int maxGroup) {
        return Map.of("type", entityId(entity), "weight", Math.max(1, weight), "minCount", Math.max(1, minGroup), "maxCount", Math.max(Math.max(1, minGroup), maxGroup));
    }

    private Map<String, Object> configuredFeature(String placement, List<String> warnings) {
        if (placement.startsWith("tree:")) {
            return Map.of("type", "minecraft:tree", "config", Map.of("trunk_provider", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:oak_log")), "foliage_provider", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:oak_leaves")), "minimum_size", Map.of("type", "minecraft:two_layers_feature_size", "limit", 1, "lower_size", 0, "upper_size", 1), "trunk_placer", Map.of("type", "minecraft:straight_trunk_placer", "base_height", 4, "height_rand_a", 2, "height_rand_b", 0), "foliage_placer", Map.of("type", "minecraft:blob_foliage_placer", "radius", 2, "offset", 0, "height", 3), "dirt_provider", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:dirt")), "force_dirt", false, "ignore_vines", true, "decorators", List.of()));
        }
        if (placement.startsWith("patch:")) {
            return configuredPatch(placement.substring("patch:".length()).split("\\|")[0]);
        }
        if (placement.startsWith("ore:")) {
            String[] parts = placement.split(":");
            String block = parts.length >= 3 ? parts[1] + ":" + parts[2] : "minecraft:coal_ore";
            return Map.of("type", "minecraft:ore", "config", Map.of("size", 8, "discard_chance_on_air_exposure", 0.0, "targets", List.of(Map.of("target", Map.of("predicate_type", "minecraft:tag_match", "tag", "minecraft:stone_ore_replaceables"), "state", Map.of("Name", block(block, "minecraft:coal_ore"))))));
        }
        warnings.add("Unsupported feature placement " + placement);
        return null;
    }

    private Map<String, Object> configuredPatch(String block) {
        return Map.of("type", "minecraft:simple_block", "config", Map.of("to_place", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", block(block, "minecraft:short_grass")))));
    }

    private Map<String, Object> placedFeature(String feature, int count, int minY, int maxY) {
        return Map.of("feature", feature, "placement", List.of(Map.of("type", "minecraft:count", "count", count), Map.of("type", "minecraft:in_square"), Map.of("type", "minecraft:height_range", "height", Map.of("type", "minecraft:uniform", "min_inclusive", Map.of("absolute", minY), "max_inclusive", Map.of("absolute", maxY))), Map.of("type", "minecraft:biome")));
    }

    private Map<String, Object> structureSet(StructureData data) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("type", "minecraft:random_spread");
        placement.put("spacing", Math.max(1, data.spacing()));
        placement.put("separation", Math.max(0, Math.min(data.spacing() - 1, data.separation())));
        placement.put("salt", data.salt());
        return Map.of("structures", List.of(Map.of("structure", data.id(), "weight", 1)), "placement", placement);
    }

    private Map<String, Object> vanillaNoiseRouter(String terrainTemplate) {
        String density = switch (safeId(terrainTemplate)) {
            case "floating" -> "minecraft:overworld/caves/entrances";
            case "cave_world" -> "minecraft:overworld/caves/pillars";
            default -> "minecraft:overworld/base_3d_noise";
        };
        Map<String, Object> router = new LinkedHashMap<>();
        router.put("barrier", 0);
        router.put("fluid_level_floodedness", 0);
        router.put("fluid_level_spread", 0);
        router.put("lava", 0);
        router.put("temperature", "minecraft:temperature");
        router.put("vegetation", "minecraft:vegetation");
        router.put("continents", "minecraft:overworld/continents");
        router.put("erosion", "minecraft:overworld/erosion");
        router.put("depth", "minecraft:overworld/depth");
        router.put("ridges", "minecraft:overworld/ridges");
        router.put("initial_density_without_jaggedness", density);
        router.put("final_density", density);
        router.put("vein_toggle", 0);
        router.put("vein_ridged", 0);
        router.put("vein_gap", 0);
        return router;
    }

    private Map<String, Object> surfaceRule(WorldGenProjectSettings settings) {
        return Map.of("type", "minecraft:sequence", "sequence", List.of(Map.of("type", "minecraft:condition", "if_true", Map.of("type", "minecraft:water", "offset", 0, "surface_depth_multiplier", 0, "add_stone_depth", false), "then_run", Map.of("type", "minecraft:block", "result_state", Map.of("Name", "minecraft:sand"))), Map.of("type", "minecraft:block", "result_state", Map.of("Name", block(settings.getDefaultBlock(), "minecraft:stone")))));
    }

    private Set<String> collectBiomeIds(WorldGenProject project) {
        Set<String> ids = new LinkedHashSet<>();
        collectBiomeIds(project.getBiomeGraph(), ids);
        if (project.getBiomeProfiles() != null) {
            for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
                if (profile.getId() != null && !profile.getId().isBlank()) {
                    ids.add(normalizeId(profile.getId()));
                }
                if (profile.getVanillaBaseBiome() != null && !profile.getVanillaBaseBiome().isBlank()) {
                    ids.add(normalizeId(profile.getVanillaBaseBiome()));
                }
            }
        }
        return ids;
    }

    private void collectBiomeIds(WorldGenGraph graph, Set<String> ids) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null || node.getInputValues() == null) {
                continue;
            }
            for (String key : List.of("biome", "true_biome", "false_biome", "profile")) {
                Object value = node.getInputValues().get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    ids.add(normalizeId(String.valueOf(value)));
                }
            }
            if ("climate_map".equals(node.getType())) {
                ids.addAll(List.of("minecraft:snowy_plains", "minecraft:desert", "minecraft:forest", "minecraft:savanna", "minecraft:plains"));
            }
        }
    }

    private List<String> collectFeaturePlacements(WorldGenGraph graph) {
        List<String> placements = new ArrayList<>();
        if (graph == null || graph.getNodes() == null) {
            return placements;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node == null) {
                continue;
            }
            if ("tree_feature".equals(node.getType())) {
                placements.add("tree:" + node.getInputValues().getOrDefault("tree", "TREE"));
            } else if ("vegetation_patch".equals(node.getType())) {
                placements.add("patch:" + node.getInputValues().getOrDefault("block", "minecraft:short_grass"));
            } else if ("ore_vein".equals(node.getType())) {
                placements.add("ore:" + node.getInputValues().getOrDefault("block", "minecraft:coal_ore"));
            }
        }
        return placements;
    }

    private List<String> collectStructurePlacements(WorldGenGraph graph) {
        List<String> placements = new ArrayList<>();
        if (graph == null || graph.getNodes() == null) {
            return placements;
        }
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node != null && "structure_placement".equals(node.getType())) {
                placements.add("structure:" + node.getInputValues().getOrDefault("structure_id", "") + ":" + Math.round(number(node.getInputValues().get("spacing"), 32)) + ":" + Math.round(number(node.getInputValues().get("separation"), 8)) + ":" + Math.round(number(node.getInputValues().get("salt"), 0)));
            }
        }
        return placements;
    }

    private List<WorldGenSpawnRule> collectSpawnRules(WorldGenProject project) {
        List<WorldGenSpawnRule> rules = new ArrayList<>();
        if (project.getBiomeProfiles() != null) {
            for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
                if (profile.getSpawnRules() != null) {
                    rules.addAll(profile.getSpawnRules());
                }
            }
        }
        if (project.getSpawnGraph() != null && project.getSpawnGraph().getNodes() != null) {
            for (WorldGenNode node : project.getSpawnGraph().getNodes().values()) {
                if (node != null && "spawn_rule".equals(node.getType())) {
                    WorldGenSpawnRule rule = new WorldGenSpawnRule();
                    rule.setEntityType(String.valueOf(node.getInputValues().getOrDefault("entity", "minecraft:zombie")));
                    rule.setWeight(Math.round(number(node.getInputValues().get("weight"), 10)));
                    rule.setMinGroup(Math.round(number(node.getInputValues().get("min_group"), 1)));
                    rule.setMaxGroup(Math.round(number(node.getInputValues().get("max_group"), 4)));
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private StructureData structureData(String placement) {
        String[] parts = placement.split(":");
        String id = parts.length >= 3 ? parts[1] + ":" + parts[2] : "";
        int spacing = parts.length >= 4 ? parseInt(parts[3], 32) : 32;
        int separation = parts.length >= 5 ? parseInt(parts[4], 8) : 8;
        int salt = parts.length >= 6 ? parseInt(parts[5], 0) : 0;
        return new StructureData(id, spacing, separation, salt);
    }

    private float temperature(WorldGenProject project, String biomeId, Biome biome) {
        WorldGenBiomeProfile profile = profile(project, biomeId);
        if (profile != null) {
            return profile.getTemperature();
        }
        if (biome == Biome.DESERT) {
            return 2.0f;
        }
        if (biome == Biome.SAVANNA || biome == Biome.SAVANNA_PLATEAU) {
            return 1.2f;
        }
        if (biome == Biome.SNOWY_PLAINS || biome == Biome.ICE_SPIKES) {
            return 0.0f;
        }
        return 0.8f;
    }

    private float downfall(WorldGenProject project, String biomeId, Biome biome) {
        WorldGenBiomeProfile profile = profile(project, biomeId);
        if (profile != null) {
            return profile.getHumidity();
        }
        if (biome == Biome.DESERT || biome == Biome.SAVANNA || biome == Biome.SAVANNA_PLATEAU) {
            return 0.0f;
        }
        if (biome == Biome.FOREST || biome == Biome.BIRCH_FOREST || biome == Biome.DARK_FOREST) {
            return 0.8f;
        }
        return 0.4f;
    }

    private WorldGenBiomeProfile profile(WorldGenProject project, String biomeId) {
        if (project.getBiomeProfiles() == null) {
            return null;
        }
        for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
            if (profile.getId() != null && normalizeId(profile.getId()).equals(normalizeId(biomeId))) {
                return profile;
            }
        }
        return null;
    }

    private int writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
        return 1;
    }

    private int packFormat() {
        String version = minecraftVersion();
        if (version.startsWith("1.21.8") || version.startsWith("1.21.7")) return 81;
        if (version.startsWith("1.21.6")) return 80;
        if (version.startsWith("1.21.5")) return 71;
        if (version.startsWith("1.21.4")) return 61;
        if (version.startsWith("1.21.3") || version.startsWith("1.21.2")) return 57;
        return 48;
    }

    private String minecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Exception ignored) {
            return "1.21";
        }
    }

    private String namespace(String value) {
        String safe = safeId(value == null || value.isBlank() ? "resync_worldgen" : value);
        if (safe.isBlank() || Character.isDigit(safe.charAt(0))) {
            return "resync_" + safe;
        }
        return safe;
    }

    private String localId(String value) {
        String id = normalizeId(value);
        return safeId(id.substring(id.indexOf(':') + 1));
    }

    private String normalizeId(String id) {
        String value = id == null || id.isBlank() ? "minecraft:plains" : id.toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private String block(String value, String fallback) {
        String id = normalizeId(value == null || value.isBlank() ? fallback : value);
        String local = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
        return Material.matchMaterial(local) == null ? fallback : id;
    }

    private String entityId(String value) {
        String id = normalizeId(value == null || value.isBlank() ? "minecraft:zombie" : value);
        String local = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
        try {
            EntityType.valueOf(local);
            return id;
        } catch (Exception ignored) {
            return "minecraft:zombie";
        }
    }

    private String safeCategory(String category) {
        String value = category == null || category.isBlank() ? "monster" : category.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "creature", "ambient", "axolotls", "underground_water_creature", "water_creature", "water_ambient", "misc" -> value;
            default -> "monster";
        };
    }

    private String safeId(String value) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (char c : source.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/') {
                builder.append(c);
            } else if (c == ':' || c == '.' || c == ' ') {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private float number(Object value, float fallback) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return value;
        }
    }

    private void deleteDirectory(Path folder) throws IOException {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (var stream = Files.walk(folder)) {
            for (Path path : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record StructureData(String id, int spacing, int separation, int salt) {
    }
}
