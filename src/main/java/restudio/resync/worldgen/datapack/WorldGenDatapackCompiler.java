package restudio.resync.worldgen.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.contract.WorldGenDatapackCapability;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;
import restudio.resync.worldgen.contract.WorldGenPackMetadata;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.data.WorldGenBiomeProfile;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenProjectSettings;
import restudio.resync.worldgen.data.WorldGenSpawnRule;
import restudio.resync.worldgen.pipeline.BiomePolicyCompiler;
import restudio.resync.worldgen.pipeline.CompiledBiomePolicy;
import restudio.resync.worldgen.pipeline.PipelineCompiler;

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
        WorldGenProjectSettings settings = project.getSettings() == null ? new WorldGenProjectSettings() : project.getSettings();
        WorldGenGenerationMode generationMode = WorldGenGenerationMode.resolve(settings.getGenerationMode());
        if (generationMode == WorldGenGenerationMode.VANILLA) {
            VanillaWorldGenValidator.validate(project);
        } else {
            PipelineCompiler.compileProject(project);
        }
        BiomePolicyCompiler.Result biomePolicy = BiomePolicyCompiler.compile(project);
        String configuredVersion = settings.getTargetVersion();
        WorldGenTargetVersion target = WorldGenTargetVersion.AUTOMATIC.equalsIgnoreCase(configuredVersion)
            ? WorldGenTargetVersion.resolve(configuredVersion, Bukkit.getMinecraftVersion())
            : WorldGenTargetVersion.require(configuredVersion);
        WorldGenVanillaCatalog catalog = WorldGenVanillaCatalog.load(target);
        validateVanillaReferences(project, settings, catalog);
        String namespace = namespace(settings.getDatapackNamespace());
        String projectId = safeId(project.getId() == null || project.getId().isBlank() ? "project" : project.getId());
        String dimensionId = safeId("worldgen_" + projectId + "_" + revision);
        String dimensionKey = generationMode == WorldGenGenerationMode.VANILLA ? namespace + ":" + dimensionId : "";
        String packName = "resync_worldgen_" + projectId + "_" + revision;
        Path folder = outputRoot.resolve(projectId).resolve(String.valueOf(revision)).resolve(packName).normalize();
        List<String> warnings = new ArrayList<>();
        try {
            deleteDirectory(folder);
            Files.createDirectories(folder);
            Files.createDirectories(folder.resolve("data").resolve(namespace).resolve("worldgen"));
            int count = 0;
            count += writeJson(folder.resolve("pack.mcmeta"), packMeta(project, target));
            if (generationMode == WorldGenGenerationMode.VANILLA) {
                Map<String, Object> dimension = vanillaDimension(settings);
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("dimension").resolve(dimensionId + ".json"), dimension);
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("world_preset").resolve(projectId + ".json"),
                    Map.of("dimensions", Map.of("minecraft:overworld", dimension)));
            } else {
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("dimension_type").resolve("worldgen.json"), dimensionType(settings, target));
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("noise_settings").resolve("worldgen.json"), noiseSettings(project, settings));
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("world_preset").resolve("worldgen.json"), worldPreset(namespace));
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("multi_noise_biome_source_parameter_list").resolve("worldgen.json"),
                    biomeParameters(namespace, project, catalog));
            }
            count += writeBiomes(folder, namespace, project, biomePolicy.policy(), generationMode, warnings, target, catalog);
            count += writeFeatures(folder, namespace, project, warnings, settings, catalog);
            count += writeStructures(folder, namespace, project, warnings);
            count += writeJson(folder.resolve("resync-manifest.json"),
                manifest(project, namespace, packName, revision, count, warnings, target, catalog, generationMode, dimensionKey));
            WorldGenDatapackBuild build = new WorldGenDatapackBuild();
            build.setProjectId(project.getId());
            build.setNamespace(namespace);
            build.setPackName(packName);
            build.setGenerationMode(generationMode.id());
            build.setDimensionKey(dimensionKey);
            build.setMinecraftVersion(target.id());
            build.setDatapackVersion(target.datapackVersion());
            build.setPackFormat(target.datapackMajor());
            build.setPackFormatMinor(target.datapackMinor());
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

    private int writeBiomes(Path folder, String namespace, WorldGenProject project, CompiledBiomePolicy biomePolicy,
                            WorldGenGenerationMode generationMode, List<String> warnings, WorldGenTargetVersion target,
                            WorldGenVanillaCatalog catalog) throws IOException {
        Set<String> biomes = generationMode == WorldGenGenerationMode.VANILLA
            ? new LinkedHashSet<>(catalog.biomes())
            : collectBiomeIds(project);
        if (biomes.isEmpty()) {
            biomes.add("minecraft:plains");
        }
        List<FeatureData> placements = collectFeaturePlacements(project.getFeatureGraph());
        int count = 0;
        for (String biomeId : biomes) {
            Map<Integer, List<String>> customFeatures = new LinkedHashMap<>();
            for (int index = 0; index < placements.size(); index++) {
                FeatureData placement = placements.get(index);
                if (placement.biome().isBlank() || placement.biome().equalsIgnoreCase(biomeId)) {
                    customFeatures.computeIfAbsent(placement.stage(), ignored -> new ArrayList<>()).add(placement.outputId(namespace, index));
                }
            }
            String local = localId(biomeId);
            String biomeNamespace = generationMode == WorldGenGenerationMode.VANILLA && biomeId.contains(":")
                ? biomeId.substring(0, biomeId.indexOf(':'))
                : namespace;
            count += writeJson(folder.resolve("data").resolve(biomeNamespace).resolve("worldgen").resolve("biome").resolve(local + ".json"),
                biome(project, biomePolicy, biomeId, namespace, customFeatures, target, catalog));
        }
        return count;
    }

    private int writeFeatures(Path folder, String namespace, WorldGenProject project, List<String> warnings, WorldGenProjectSettings settings,
                              WorldGenVanillaCatalog catalog) throws IOException {
        int count = 0;
        List<FeatureData> placements = collectFeaturePlacements(project.getFeatureGraph());
        int minY = settings.getMinY();
        int maxY = settings.getMaxY() - 1;
        if (placements.isEmpty()) {
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("configured_feature").resolve("short_grass.json"), configuredPatch("minecraft:short_grass"));
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("placed_feature").resolve("short_grass.json"),
                placedFeature(namespace + ":short_grass", 18, minY, maxY));
            return count;
        }
        int index = 0;
        for (FeatureData placement : placements) {
            if (placement.direct()) {
                index++;
                continue;
            }
            String id = "feature_" + index++;
            String configuredId = namespace + ":" + id;
            if (placement.specification().startsWith("tree:")) {
                configuredId = catalog.treeFeature(placement.specification().substring("tree:".length()));
            }
            Object configured = configuredFeature(placement.specification(), warnings);
            if (configured == null && !placement.specification().startsWith("tree:")) {
                continue;
            }
            if (configured != null) {
                count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("configured_feature").resolve(id + ".json"), configured);
            }
            count += writeJson(folder.resolve("data").resolve(namespace).resolve("worldgen").resolve("placed_feature").resolve(id + ".json"),
                placedFeature(configuredId, placement.count(), Math.max(minY, placement.minY()), Math.min(maxY, placement.maxY()), placement.chance()));
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

    private Map<String, Object> packMeta(WorldGenProject project, WorldGenTargetVersion target) {
        return WorldGenPackMetadata.create(target, "ReSync WorldGen " + project.getId() + " for Minecraft " + target.id());
    }

    private Map<String, Object> manifest(WorldGenProject project, String namespace, String packName, long revision, int files, List<String> warnings,
                                         WorldGenTargetVersion target, WorldGenVanillaCatalog catalog, WorldGenGenerationMode generationMode,
                                         String dimensionKey) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("projectId", project.getId());
        manifest.put("namespace", namespace);
        manifest.put("packName", packName);
        manifest.put("revision", revision);
        manifest.put("minecraftVersion", target.id());
        manifest.put("datapackVersion", target.datapackVersion());
        manifest.put("generationMode", generationMode.id());
        manifest.put("dimensionKey", dimensionKey);
        manifest.put("vanillaServerSha1", catalog.serverSha1());
        manifest.put("fileCount", files);
        manifest.put("warnings", warnings);
        manifest.put("fingerprint", fingerprint(project.getId() + ":" + revision + ":" + namespace + ":" + target.id()));
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

    private Map<String, Object> dimensionType(WorldGenProjectSettings settings, WorldGenTargetVersion target) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("coordinate_scale", 1.0);
        value.put("has_skylight", true);
        value.put("has_ceiling", false);
        value.put("ambient_light", 0.0);
        value.put("logical_height", Math.max(384, settings.getMaxY()));
        value.put("min_y", settings.getMinY());
        value.put("height", Math.max(384, settings.getMaxY() - settings.getMinY()));
        value.put("infiniburn", "#minecraft:infiniburn_overworld");
        value.put("effects", "minecraft:overworld");
        value.put("monster_spawn_light_level", Map.of("type", "minecraft:uniform", "min_inclusive", 0, "max_inclusive", 7));
        value.put("monster_spawn_block_light_limit", 0);
        if (!target.supports(WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES)) {
            value.put("ultrawarm", false);
            value.put("natural", true);
            value.put("piglin_safe", false);
            value.put("bed_works", true);
            value.put("respawn_anchor_works", false);
            value.put("has_raids", true);
            return value;
        }
        value.remove("effects");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("minecraft:audio/ambient_sounds", Map.of("mood", Map.of("block_search_extent", 8, "offset", 2.0,
            "sound", "minecraft:ambient.cave", "tick_delay", 6000)));
        attributes.put("minecraft:audio/background_music", Map.of(
            "creative", Map.of("max_delay", 24000, "min_delay", 12000, "sound", "minecraft:music.creative"),
            "default", Map.of("max_delay", 24000, "min_delay", 12000, "sound", "minecraft:music.game")));
        attributes.put("minecraft:gameplay/bed_rule", Map.of("can_set_spawn", "always", "can_sleep", "when_dark",
            "error_message", Map.of("translate", "block.minecraft.bed.no_sleep")));
        attributes.put("minecraft:gameplay/nether_portal_spawns_piglin", true);
        attributes.put("minecraft:gameplay/respawn_anchor_works", false);
        attributes.put("minecraft:visual/cloud_color", "#ccffffff");
        attributes.put("minecraft:visual/cloud_height", 192.33);
        attributes.put("minecraft:visual/fog_color", "#c0d8ff");
        attributes.put("minecraft:visual/sky_color", "#78a7ff");
        if (target.supports(WorldGenDatapackCapability.WORLD_CLOCKS)) {
            attributes.put("minecraft:visual/ambient_light_color", "#0a0a0a");
            value.put("default_clock", "minecraft:overworld");
            value.put("has_ender_dragon_fight", false);
        }
        value.put("attributes", attributes);
        value.put("timelines", "#minecraft:in_overworld");
        return value;
    }

    private Map<String, Object> worldPreset(String namespace) {
        Map<String, Object> overworld = new LinkedHashMap<>();
        overworld.put("type", namespace + ":worldgen");
        overworld.put("generator", Map.of("type", "minecraft:noise", "settings", namespace + ":worldgen", "biome_source", Map.of("type", "minecraft:multi_noise", "preset", namespace + ":worldgen")));
        return Map.of("dimensions", Map.of("minecraft:overworld", overworld));
    }

    private Map<String, Object> vanillaDimension(WorldGenProjectSettings settings) {
        String template = safeId(settings.getTerrainTemplate());
        String dimensionType = switch (template) {
            case "nether" -> "minecraft:the_nether";
            case "end" -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        String noiseSettings = switch (template) {
            case "amplified" -> "minecraft:amplified";
            case "large_biomes" -> "minecraft:large_biomes";
            case "nether" -> "minecraft:nether";
            case "end" -> "minecraft:end";
            default -> "minecraft:overworld";
        };
        Map<String, Object> biomeSource = switch (template) {
            case "nether" -> Map.of("type", "minecraft:multi_noise", "preset", "minecraft:nether");
            case "end" -> Map.of("type", "minecraft:the_end");
            default -> Map.of("type", "minecraft:multi_noise", "preset", "minecraft:overworld");
        };
        return Map.of("type", dimensionType, "generator", Map.of("type", "minecraft:noise", "settings", noiseSettings, "biome_source", biomeSource));
    }

    private Map<String, Object> biomeParameters(String namespace, WorldGenProject project, WorldGenVanillaCatalog catalog) {
        List<Object> values = new ArrayList<>();
        Set<String> biomeIds = collectBiomeIds(project);
        if (biomeIds.isEmpty()) {
            biomeIds.add("minecraft:plains");
        }
        for (String biomeId : biomeIds) {
            WorldGenBiomeProfile profile = profile(project, biomeId);
            JsonObject template = catalog.biome(sourceBiomeId(project, biomeId));
            double temperature = profile == null ? template.get("temperature").getAsDouble() : profile.getTemperature();
            double downfall = profile == null ? template.get("downfall").getAsDouble() : profile.getHumidity();
            double continentalness = profile == null ? 0.0 : profile.getContinentalness();
            double erosion = profile == null ? 0.0 : profile.getErosion();
            double weirdness = profile == null ? 0.0 : profile.getWeirdness();
            values.add(parameter(namespace + ":" + localId(biomeId), temperature, downfall, continentalness, erosion, weirdness, 0.0, 0L));
        }
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

    private JsonObject biome(WorldGenProject project, CompiledBiomePolicy biomePolicy, String biomeId, String namespace,
                             Map<Integer, List<String>> customFeatures,
                             WorldGenTargetVersion target, WorldGenVanillaCatalog catalog) {
        JsonObject value = catalog.biome(sourceBiomeId(project, biomeId));
        WorldGenBiomeProfile profile = profile(project, biomeId);
        if (profile != null) {
            value.addProperty("temperature", profile.getTemperature());
            value.addProperty("downfall", profile.getHumidity());
        }
        if (!biomePolicy.spawns(biomeId)) {
            value.add("spawners", GSON.toJsonTree(spawners(project, biomeId)));
            value.add("spawn_costs", new JsonObject());
        }
        if (!biomePolicy.features(biomeId)) {
            value.add("carvers", GSON.toJsonTree(target.supports(WorldGenDatapackCapability.FLAT_BIOME_CARVERS) ? List.of() : Map.of("air", List.of())));
            value.add("features", emptyFeatureStages());
        }
        JsonArray features = value.getAsJsonArray("features");
        if (customFeatures != null && !customFeatures.isEmpty()) {
            customFeatures.forEach((stage, ids) -> ids.forEach(featureStage(features, stage)::add));
        } else if (!biomePolicy.features(biomeId)) {
            featureStage(features, 9).add(namespace + ":short_grass");
        }
        return value;
    }

    private JsonArray emptyFeatureStages() {
        JsonArray stages = new JsonArray();
        for (int i = 0; i < 11; i++) {
            stages.add(new JsonArray());
        }
        return stages;
    }

    private JsonArray featureStage(JsonArray features, int stage) {
        while (features.size() <= stage) {
            features.add(new JsonArray());
        }
        if (!features.get(stage).isJsonArray()) {
            throw new IllegalArgumentException("Invalid Vanilla Feature Stage " + stage);
        }
        return features.get(stage).getAsJsonArray();
    }

    private Map<String, List<Object>> spawners(WorldGenProject project, String biomeId) {
        Map<String, List<Object>> spawners = new LinkedHashMap<>();
        spawners.put("monster", new ArrayList<>());
        spawners.put("creature", new ArrayList<>());
        spawners.put("ambient", new ArrayList<>());
        spawners.put("axolotls", new ArrayList<>());
        spawners.put("underground_water_creature", new ArrayList<>());
        spawners.put("water_creature", new ArrayList<>());
        spawners.put("water_ambient", new ArrayList<>());
        spawners.put("misc", new ArrayList<>());
        for (WorldGenSpawnRule rule : collectSpawnRules(project)) {
            if (rule.getBiomeFilters() != null && !rule.getBiomeFilters().isEmpty() && rule.getBiomeFilters().stream().noneMatch(biomeId::equalsIgnoreCase)) {
                continue;
            }
            String category = safeCategory(rule.getCategory());
            spawners.computeIfAbsent(category, ignored -> new ArrayList<>()).add(spawn(rule.getEntityType(), rule.getWeight(), rule.getMinGroup(), rule.getMaxGroup()));
        }
        return spawners;
    }

    private Map<String, Object> spawn(String entity, int weight, int minGroup, int maxGroup) {
        return Map.of("type", entityId(entity), "weight", Math.max(1, weight), "minCount", Math.max(1, minGroup), "maxCount", Math.max(Math.max(1, minGroup), maxGroup));
    }

    private Map<String, Object> configuredFeature(String placement, List<String> warnings) {
        if (placement.startsWith("tree:")) {
            return null;
        }
        if (placement.startsWith("patch:")) {
            return configuredPatch(placement.substring("patch:".length()).split("\\|")[0]);
        }
        if (placement.startsWith("ore:")) {
            String ore = placement.substring("ore:".length());
            int separator = ore.lastIndexOf(':');
            int size = separator > 0 ? parseInt(ore.substring(separator + 1), 8) : 8;
            String oreBlock = separator > 0 && isInteger(ore.substring(separator + 1)) ? ore.substring(0, separator) : ore;
            return Map.of("type", "minecraft:ore", "config", Map.of("size", Math.clamp(size, 1, 64), "discard_chance_on_air_exposure", 0.0, "targets", List.of(Map.of(
                "target", Map.of("predicate_type", "minecraft:tag_match", "tag", "minecraft:stone_ore_replaceables"),
                "state", Map.of("Name", block(oreBlock, "minecraft:coal_ore"))))));
        }
        if (placement.startsWith("lake:")) {
            String fluid = block(placement.substring("lake:".length()), "minecraft:water");
            return Map.of("type", "minecraft:lake", "config", Map.of(
                "fluid", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", fluid)),
                "barrier", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:stone"))));
        }
        if (placement.startsWith("disk:")) {
            String disk = placement.substring("disk:".length());
            int separator = disk.lastIndexOf(':');
            int radius = separator > 0 ? parseInt(disk.substring(separator + 1), 4) : 4;
            String diskBlock = separator > 0 && isInteger(disk.substring(separator + 1)) ? disk.substring(0, separator) : disk;
            return Map.of("type", "minecraft:disk", "config", Map.of(
                "state_provider", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", block(diskBlock, "minecraft:clay"))),
                "target", Map.of("type", "minecraft:matching_blocks", "blocks", List.of("minecraft:dirt", "minecraft:clay", "minecraft:sand", "minecraft:gravel")),
                "radius", Map.of("type", "minecraft:uniform", "value", Map.of("min_inclusive", 2, "max_inclusive", Math.clamp(radius, 2, 16))),
                "half_height", 1));
        }
        if (placement.startsWith("boulder:")) {
            return Map.of("type", "minecraft:block_pile", "config", Map.of("state_provider", Map.of(
                "type", "minecraft:simple_state_provider", "state", Map.of("Name", block(placement.substring("boulder:".length()), "minecraft:mossy_cobblestone")))));
        }
        warnings.add("Unsupported feature placement " + placement);
        return null;
    }

    private Map<String, Object> configuredPatch(String block) {
        return Map.of("type", "minecraft:simple_block", "config", Map.of("to_place", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", block(block, "minecraft:short_grass")))));
    }

    private Map<String, Object> placedFeature(String feature, int count, int minY, int maxY) {
        return placedFeature(feature, count, minY, maxY, 1f);
    }

    private Map<String, Object> placedFeature(String feature, int count, int minY, int maxY, float chance) {
        List<Object> placement = new ArrayList<>();
        if (chance > 0f && chance < 1f) {
            placement.add(Map.of("type", "minecraft:rarity_filter", "chance", Math.max(1, Math.round(1f / chance))));
        }
        placement.add(Map.of("type", "minecraft:count", "count", Math.max(1, count)));
        placement.add(Map.of("type", "minecraft:in_square"));
        placement.add(Map.of("type", "minecraft:height_range", "height", Map.of("type", "minecraft:uniform", "min_inclusive", Map.of("absolute", minY), "max_inclusive", Map.of("absolute", maxY))));
        placement.add(Map.of("type", "minecraft:biome"));
        return Map.of("feature", feature, "placement", placement);
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
        router.put("preliminary_surface_level", density);
        router.put("final_density", density);
        router.put("vein_toggle", 0);
        router.put("vein_ridged", 0);
        router.put("vein_gap", 0);
        return router;
    }

    private Map<String, Object> surfaceRule(WorldGenProjectSettings settings) {
        return Map.of("type", "minecraft:sequence", "sequence", List.of(Map.of("type", "minecraft:condition", "if_true", Map.of("type", "minecraft:water", "offset", 0, "surface_depth_multiplier", 0, "add_stone_depth", false), "then_run", Map.of("type", "minecraft:block", "result_state", Map.of("Name", "minecraft:sand"))), Map.of("type", "minecraft:block", "result_state", Map.of("Name", block(settings.getDefaultBlock(), "minecraft:stone")))));
    }

    private void validateVanillaReferences(WorldGenProject project, WorldGenProjectSettings settings, WorldGenVanillaCatalog catalog) {
        if (settings.getMaxY() <= settings.getMinY()) {
            throw new IllegalArgumentException("World Max Y Must Be Greater Than Min Y");
        }
        catalog.requireBlock(block(settings.getDefaultBlock(), "minecraft:stone"));
        catalog.requireBlock(block(settings.getDefaultFluid(), "minecraft:water"));
        Set<String> biomeIds = collectBiomeIds(project);
        if (biomeIds.isEmpty()) {
            biomeIds.add("minecraft:plains");
        }
        for (String biomeId : biomeIds) {
            catalog.requireBiome(biomeId);
            catalog.biome(sourceBiomeId(project, biomeId));
        }
        for (FeatureData feature : collectFeaturePlacements(project.getFeatureGraph())) {
            String placement = feature.specification();
            if (placement.startsWith("tree:")) {
                catalog.treeFeature(placement.substring("tree:".length()));
            } else if (placement.startsWith("patch:")) {
                catalog.requireBlock(placement.substring("patch:".length()).split("\\|")[0]);
            } else if (placement.startsWith("ore:")) {
                String ore = placement.substring("ore:".length());
                int separator = ore.lastIndexOf(':');
                catalog.requireBlock(separator > 0 && isInteger(ore.substring(separator + 1)) ? ore.substring(0, separator) : ore);
            } else if (placement.startsWith("lake:")) {
                catalog.requireBlock(placement.substring("lake:".length()));
            } else if (placement.startsWith("disk:")) {
                String disk = placement.substring("disk:".length());
                int separator = disk.lastIndexOf(':');
                catalog.requireBlock(separator > 0 && isInteger(disk.substring(separator + 1)) ? disk.substring(0, separator) : disk);
            } else if (placement.startsWith("boulder:")) {
                catalog.requireBlock(placement.substring("boulder:".length()));
            } else if (placement.startsWith("placed:") && !catalog.placedFeatures().contains(placement.substring("placed:".length()))) {
                throw new IllegalArgumentException("Unknown Placed Feature " + placement.substring("placed:".length()));
            }
        }
        for (WorldGenSpawnRule rule : collectSpawnRules(project)) {
            catalog.requireEntity(entityId(rule.getEntityType()));
        }
        for (String placement : collectStructurePlacements(project.getStructureGraph())) {
            StructureData data = structureData(placement);
            if (!data.id().isBlank()) {
                catalog.requireStructure(data.id());
            }
        }
    }

    private Set<String> collectBiomeIds(WorldGenProject project) {
        Set<String> ids = new LinkedHashSet<>();
        collectBiomeIds(project.getBiomeGraph(), ids);
        if (project.getBiomeProfiles() != null) {
            for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
                if (profile.getId() != null && !profile.getId().isBlank()) {
                    ids.add(normalizeId(profile.getId()));
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

    private List<FeatureData> collectFeaturePlacements(WorldGenGraph graph) {
        List<FeatureData> placements = new ArrayList<>();
        if (graph == null || graph.getNodes() == null) {
            return placements;
        }
        Set<String> active = activeNodes(graph, "output_features");
        Map<String, String> featureSources = new LinkedHashMap<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            if ("feature".equals(connection.getTargetPin())) {
                featureSources.put(connection.getTargetNodeId(), connection.getSourceNodeId());
            }
        }
        boolean hasPlacementNodes = false;
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node == null || !active.contains(entry.getKey()) || (!"scatter".equals(node.getType()) && !"poisson_scatter".equals(node.getType()))) {
                continue;
            }
            hasPlacementNodes = true;
            WorldGenNode source = graph.getNodes().get(featureSources.get(entry.getKey()));
            String specification = featureSpecification(source, node.getInputValues().get("feature"));
            if (specification.isBlank()) {
                continue;
            }
            boolean poisson = "poisson_scatter".equals(node.getType());
            float spacing = number(node.getInputValues().get("spacing"), 12);
            int count = poisson ? Math.max(1, Math.round(256f / Math.max(1f, spacing * spacing))) : Math.max(1, Math.round(number(node.getInputValues().get("count"), 8)));
            float chance = poisson ? 1f : Math.clamp(number(node.getInputValues().get("chance"), 1), 0f, 1f);
            int minY = Math.round(number(node.getInputValues().get("min_y"), -64));
            int maxY = Math.round(number(node.getInputValues().get("max_y"), 319));
            int stage = featureStage(String.valueOf(node.getInputValues().getOrDefault("generation_step", defaultFeatureStage(specification))));
            String biome = String.valueOf(node.getInputValues().getOrDefault("biome", "")).trim();
            placements.add(new FeatureData(specification, count, minY, maxY, chance, stage, biome));
        }
        if (hasPlacementNodes) {
            return placements;
        }
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node == null || !active.contains(entry.getKey())) {
                continue;
            }
            String specification = featureSpecification(node, null);
            if (!specification.isBlank()) {
                placements.add(new FeatureData(specification, 8, -64, 319, 1f, featureStage(defaultFeatureStage(specification)), ""));
            }
        }
        return placements;
    }

    private String featureSpecification(WorldGenNode node, Object fallback) {
        if (node == null) {
            String value = fallback == null ? "" : String.valueOf(fallback).trim();
            return value.isBlank() || value.contains(":") && value.substring(value.indexOf(':') + 1).contains(":") ? value : "placed:" + value;
        }
        return switch (node.getType()) {
            case "tree_feature" -> "tree:" + node.getInputValues().getOrDefault("tree", "TREE");
            case "vegetation_patch" -> "patch:" + node.getInputValues().getOrDefault("block", "minecraft:short_grass");
            case "ore_vein" -> "ore:" + node.getInputValues().getOrDefault("block", "minecraft:coal_ore") + ":" + Math.round(number(node.getInputValues().get("size"), 8));
            case "liquid_lake" -> "lake:" + node.getInputValues().getOrDefault("fluid", "minecraft:water");
            case "disk" -> "disk:" + node.getInputValues().getOrDefault("block", "minecraft:clay") + ":" + Math.round(number(node.getInputValues().get("radius"), 4));
            case "boulder" -> "boulder:" + node.getInputValues().getOrDefault("block", "minecraft:mossy_cobblestone");
            case "placed_feature" -> "placed:" + node.getInputValues().getOrDefault("feature", "minecraft:patch_grass");
            default -> "";
        };
    }

    private String defaultFeatureStage(String specification) {
        return specification.startsWith("ore:") ? "underground_ores" : specification.startsWith("lake:") ? "lakes" : "vegetal_decoration";
    }

    private int featureStage(String stage) {
        return switch (stage) {
            case "raw_generation" -> 0;
            case "lakes" -> 1;
            case "local_modifications" -> 2;
            case "underground_structures" -> 3;
            case "surface_structures" -> 4;
            case "strongholds" -> 5;
            case "underground_ores" -> 6;
            case "underground_decoration" -> 7;
            case "fluid_springs" -> 8;
            case "top_layer_modification" -> 10;
            default -> 9;
        };
    }

    private Set<String> activeNodes(WorldGenGraph graph, String outputType) {
        Set<String> active = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        graph.getNodes().forEach((id, node) -> {
            if (node != null && outputType.equals(node.getType())) {
                active.add(id);
                pending.add(id);
            }
        });
        if (pending.isEmpty()) {
            return new LinkedHashSet<>(graph.getNodes().keySet());
        }
        for (int index = 0; index < pending.size(); index++) {
            String target = pending.get(index);
            for (WorldGenConnection connection : graph.getConnections()) {
                if (target.equals(connection.getTargetNodeId()) && active.add(connection.getSourceNodeId())) {
                    pending.add(connection.getSourceNodeId());
                }
            }
        }
        return active;
    }

    private record FeatureData(String specification, int count, int minY, int maxY, float chance, int stage, String biome) {
        private boolean direct() {
            return specification.startsWith("placed:");
        }

        private String outputId(String namespace, int index) {
            return direct() ? specification.substring("placed:".length()) : namespace + ":feature_" + index;
        }
    }

    private List<String> collectStructurePlacements(WorldGenGraph graph) {
        List<String> placements = new ArrayList<>();
        if (graph == null || graph.getNodes() == null) {
            return placements;
        }
        Set<String> active = activeNodes(graph, "output_structures");
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node != null && active.contains(entry.getKey()) && "structure_placement".equals(node.getType())) {
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
            Set<String> active = activeNodes(project.getSpawnGraph(), "output_spawns");
            for (Map.Entry<String, WorldGenNode> entry : project.getSpawnGraph().getNodes().entrySet()) {
                WorldGenNode node = entry.getValue();
                if (node != null && active.contains(entry.getKey()) && "spawn_rule".equals(node.getType())) {
                    WorldGenSpawnRule rule = new WorldGenSpawnRule();
                    rule.setEntityType(String.valueOf(node.getInputValues().getOrDefault("entity", "minecraft:zombie")));
                    rule.setWeight(Math.round(number(node.getInputValues().get("weight"), 10)));
                    rule.setMinGroup(Math.round(number(node.getInputValues().get("min_group"), 1)));
                    rule.setMaxGroup(Math.round(number(node.getInputValues().get("max_group"), 4)));
                    rule.setCategory(String.valueOf(node.getInputValues().getOrDefault("category", "monster")));
                    String biome = String.valueOf(node.getInputValues().getOrDefault("biome", "")).trim();
                    rule.setBiomeFilters(biome.isBlank() ? List.of() : List.of(biome));
                    rule.setMinY(Math.round(number(node.getInputValues().get("min_y"), -64)));
                    rule.setMaxY(Math.round(number(node.getInputValues().get("max_y"), 319)));
                    rule.setBlockBelow(String.valueOf(node.getInputValues().getOrDefault("block_below", "")));
                    rule.setMinLight(Math.round(number(node.getInputValues().get("min_light"), 0)));
                    rule.setMaxLight(Math.round(number(node.getInputValues().get("max_light"), 15)));
                    rule.setTime(String.valueOf(node.getInputValues().getOrDefault("time", "any")));
                    rule.setWeather(String.valueOf(node.getInputValues().getOrDefault("weather", "any")));
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

    private String sourceBiomeId(WorldGenProject project, String biomeId) {
        WorldGenBiomeProfile profile = profile(project, biomeId);
        if (profile == null || profile.getVanillaBaseBiome() == null || profile.getVanillaBaseBiome().isBlank()) {
            return normalizeId(biomeId);
        }
        return normalizeId(profile.getVanillaBaseBiome());
    }

    private int writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
        return 1;
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
        return normalizeId(value == null || value.isBlank() ? fallback : value);
    }

    private String entityId(String value) {
        return normalizeId(value == null || value.isBlank() ? "minecraft:zombie" : value);
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

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (Exception ignored) {
            return false;
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
