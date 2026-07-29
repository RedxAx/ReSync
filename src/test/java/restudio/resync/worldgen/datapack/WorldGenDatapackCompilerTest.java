package restudio.resync.worldgen.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.data.WorldGenBiomeProfile;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenDatapackCompilerTest {
    @TempDir
    Path output;

    @Test
    void writesLegacyPackMetadataFor1218() throws IOException {
        WorldGenDatapackBuild build = compile(project("1.21.8", false));
        JsonObject pack = json(build.getFolder().resolve("pack.mcmeta")).getAsJsonObject("pack");

        assertEquals(81, pack.get("pack_format").getAsInt());
        assertFalse(pack.has("min_format"));
        assertEquals("81", build.getDatapackVersion());
    }

    @Test
    void writesVersionedPackMetadataFor262() throws IOException {
        WorldGenDatapackBuild build = compile(project("26.2", false));
        JsonObject pack = json(build.getFolder().resolve("pack.mcmeta")).getAsJsonObject("pack");

        assertEquals(107, pack.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(1, pack.getAsJsonArray("min_format").get(1).getAsInt());
        assertEquals(pack.get("min_format"), pack.get("max_format"));
        assertFalse(pack.has("pack_format"));
        assertEquals("107.1", build.getDatapackVersion());
    }

    @Test
    void connectsGeneratedFeaturesAndBiomeParameters() throws IOException {
        WorldGenDatapackBuild build = compile(project("26.2", true));
        Path worldgen = build.getFolder().resolve("data").resolve("resync_worldgen").resolve("worldgen");
        JsonObject parameters = json(worldgen.resolve("multi_noise_biome_source_parameter_list").resolve("worldgen.json"));
        JsonArray values = parameters.getAsJsonArray("values");
        JsonObject biome = json(worldgen.resolve("biome").resolve("plains.json"));
        JsonArray vegetation = biome.getAsJsonArray("features").get(9).getAsJsonArray();
        JsonObject placed = json(worldgen.resolve("placed_feature").resolve("feature_0.json"));

        assertEquals("resync_worldgen:plains", values.get(0).getAsJsonObject().get("biome").getAsString());
        assertTrue(Files.exists(worldgen.resolve("placed_feature").resolve("feature_0.json")));
        assertFalse(Files.exists(worldgen.resolve("configured_feature").resolve("feature_0.json")));
        assertEquals("minecraft:birch", placed.get("feature").getAsString());
        assertTrue(vegetation.asList().stream().anyMatch(value -> "resync_worldgen:feature_0".equals(value.getAsString())));
    }

    @Test
    void preservesExactVanillaBiomePipeline() throws IOException {
        WorldGenProject project = project("26.2", false);
        project.getSettings().setVanillaFeaturesEnabled(true);
        project.getSettings().setVanillaSpawnsEnabled(true);
        WorldGenDatapackBuild build = compile(project);
        JsonObject actual = json(build.getFolder().resolve("data").resolve("resync_worldgen").resolve("worldgen").resolve("biome").resolve("plains.json"));
        JsonObject vanilla = WorldGenVanillaCatalog.load(WorldGenTargetVersion.MINECRAFT_26_2).biome("minecraft:plains");

        assertEquals(vanilla.get("carvers"), actual.get("carvers"));
        assertEquals(vanilla.get("features"), actual.get("features"));
        assertEquals(vanilla.get("spawners"), actual.get("spawners"));
    }

    @Test
    void usesProjectHeightBoundsForFeatures() throws IOException {
        WorldGenProject project = project("26.2", true);
        project.getSettings().setMinY(-32);
        project.getSettings().setMaxY(96);
        WorldGenDatapackBuild build = compile(project);
        JsonObject placed = json(build.getFolder().resolve("data").resolve("resync_worldgen").resolve("worldgen").resolve("placed_feature").resolve("feature_0.json"));
        JsonObject height = placed.getAsJsonArray("placement").get(2).getAsJsonObject().getAsJsonObject("height");

        assertEquals(-32, height.getAsJsonObject("min_inclusive").get("absolute").getAsInt());
        assertEquals(95, height.getAsJsonObject("max_inclusive").get("absolute").getAsInt());
    }

    @Test
    void placesCustomOresInUndergroundOresStage() throws IOException {
        WorldGenProject project = project("26.2", false);
        project.getFeatureGraph().setNodes(new LinkedHashMap<>(Map.of(
            "ore", new WorldGenNode("ore_vein", 0, 0, Map.of("block", "minecraft:diamond_ore"))
        )));
        WorldGenDatapackBuild build = compile(project);
        JsonArray features = json(build.getFolder().resolve("data").resolve("resync_worldgen").resolve("worldgen").resolve("biome").resolve("plains.json"))
            .getAsJsonArray("features");

        assertTrue(features.get(6).getAsJsonArray().asList().stream().anyMatch(value -> "resync_worldgen:feature_0".equals(value.getAsString())));
        assertFalse(features.get(9).getAsJsonArray().asList().stream().anyMatch(value -> "resync_worldgen:feature_0".equals(value.getAsString())));
    }

    @Test
    void padsMissingVanillaStagesOnlyForCustomFeatures() throws IOException {
        WorldGenProject project = project("26.2", true);
        WorldGenBiomeProfile profile = new WorldGenBiomeProfile();
        profile.setId("resync:nether");
        profile.setVanillaBaseBiome("minecraft:basalt_deltas");
        profile.setKeepVanillaFeatures(true);
        project.setBiomeProfiles(List.of(profile));
        WorldGenDatapackBuild build = compile(project);
        JsonArray features = json(build.getFolder().resolve("data").resolve("resync_worldgen").resolve("worldgen").resolve("biome").resolve("nether.json"))
            .getAsJsonArray("features");

        assertEquals(10, features.size());
        assertTrue(features.get(9).getAsJsonArray().asList().stream().anyMatch(value -> "resync_worldgen:feature_0".equals(value.getAsString())));
    }

    @Test
    void validatesVanillaIdsAgainstTargetRegistry() {
        WorldGenProject oldTarget = project("26.1.2", false);
        oldTarget.getSettings().setDefaultBlock("minecraft:sulfur");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(oldTarget));

        assertTrue(exception.getMessage().contains("Does Not Exist In Minecraft 26.1.2"));

        WorldGenProject currentTarget = project("26.2", false);
        currentTarget.getSettings().setDefaultBlock("minecraft:sulfur");
        compile(currentTarget);
    }

    @Test
    void loadsAuthoritativeCatalogForEveryTarget() {
        for (WorldGenTargetVersion target : WorldGenTargetVersion.values()) {
            WorldGenVanillaCatalog catalog = WorldGenVanillaCatalog.load(target);

            assertEquals(40, catalog.serverSha1().length());
            assertTrue(catalog.biome("minecraft:plains").has("features"));
        }
    }

    @Test
    void writesLegacyWorldgenSchemaFor1211() throws IOException {
        WorldGenDatapackBuild build = compile(project("1.21.1", false));
        Path data = build.getFolder().resolve("data").resolve("resync_worldgen");
        JsonObject dimension = json(data.resolve("dimension_type").resolve("worldgen.json"));
        JsonObject biome = json(data.resolve("worldgen").resolve("biome").resolve("plains.json"));
        JsonObject router = json(data.resolve("worldgen").resolve("noise_settings").resolve("worldgen.json")).getAsJsonObject("noise_router");

        assertTrue(dimension.has("natural"));
        assertFalse(dimension.has("attributes"));
        assertTrue(biome.get("carvers").isJsonObject());
        assertTrue(biome.getAsJsonObject("effects").has("fog_color"));
        assertTrue(router.has("preliminary_surface_level"));
        assertFalse(router.has("initial_density_without_jaggedness"));
    }

    @Test
    void writesEnvironmentAttributesFor12111() throws IOException {
        WorldGenDatapackBuild build = compile(project("1.21.11", false));
        Path data = build.getFolder().resolve("data").resolve("resync_worldgen");
        JsonObject dimension = json(data.resolve("dimension_type").resolve("worldgen.json"));
        JsonObject biome = json(data.resolve("worldgen").resolve("biome").resolve("plains.json"));

        assertFalse(dimension.has("natural"));
        assertFalse(dimension.has("default_clock"));
        assertTrue(dimension.getAsJsonObject("attributes").has("minecraft:gameplay/bed_rule"));
        assertTrue(biome.get("carvers").isJsonArray());
        assertTrue(biome.getAsJsonObject("attributes").has("minecraft:visual/sky_color"));
        assertFalse(biome.getAsJsonObject("effects").has("fog_color"));
        assertEquals("#3f76e4", biome.getAsJsonObject("effects").get("water_color").getAsString());
    }

    @Test
    void writesWorldClockDimensionFieldsFor2612() throws IOException {
        WorldGenDatapackBuild build = compile(project("26.1.2", false));
        JsonObject dimension = json(build.getFolder().resolve("data").resolve("resync_worldgen").resolve("dimension_type").resolve("worldgen.json"));

        assertEquals("minecraft:overworld", dimension.get("default_clock").getAsString());
        assertFalse(dimension.get("has_ender_dragon_fight").getAsBoolean());
        assertEquals("#0a0a0a", dimension.getAsJsonObject("attributes").get("minecraft:visual/ambient_light_color").getAsString());
    }

    private WorldGenDatapackBuild compile(WorldGenProject project) {
        return new WorldGenDatapackCompiler(null).compile(project, output, 1L);
    }

    private WorldGenProject project(String target, boolean feature) {
        WorldGenProject project = new WorldGenProject();
        project.setId("test");
        project.getSettings().setTargetVersion(target);
        project.getTerrainGraph().setNodes(new LinkedHashMap<>(Map.of(
            "height", new WorldGenNode("output_height", 0, 0, Map.of("height", 64f))
        )));
        if (feature) {
            project.getFeatureGraph().setNodes(new LinkedHashMap<>(Map.of(
                "tree", new WorldGenNode("tree_feature", 0, 0, Map.of("tree", "BIRCH"))
            )));
        }
        return project;
    }

    private JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
