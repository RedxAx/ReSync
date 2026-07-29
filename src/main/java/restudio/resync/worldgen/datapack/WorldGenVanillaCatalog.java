package restudio.resync.worldgen.datapack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldGenVanillaCatalog {
    private static final Gson GSON = new Gson();
    private static final Map<WorldGenTargetVersion, WorldGenVanillaCatalog> CATALOGS = new ConcurrentHashMap<>();
    private static final Map<String, String> TREE_FEATURES = Map.ofEntries(
        Map.entry("TREE", "minecraft:oak"),
        Map.entry("BIG_TREE", "minecraft:fancy_oak"),
        Map.entry("REDWOOD", "minecraft:spruce"),
        Map.entry("TALL_REDWOOD", "minecraft:pine"),
        Map.entry("BIRCH", "minecraft:birch"),
        Map.entry("JUNGLE", "minecraft:mega_jungle_tree"),
        Map.entry("SMALL_JUNGLE", "minecraft:jungle_tree_no_vine"),
        Map.entry("COCOA_TREE", "minecraft:jungle_tree"),
        Map.entry("JUNGLE_BUSH", "minecraft:jungle_bush"),
        Map.entry("RED_MUSHROOM", "minecraft:huge_red_mushroom"),
        Map.entry("BROWN_MUSHROOM", "minecraft:huge_brown_mushroom"),
        Map.entry("SWAMP", "minecraft:swamp_oak"),
        Map.entry("ACACIA", "minecraft:acacia"),
        Map.entry("DARK_OAK", "minecraft:dark_oak"),
        Map.entry("MEGA_REDWOOD", "minecraft:mega_spruce"),
        Map.entry("MEGA_PINE", "minecraft:mega_pine"),
        Map.entry("TALL_BIRCH", "minecraft:super_birch_bees_0002"),
        Map.entry("CHORUS_PLANT", "minecraft:chorus_plant"),
        Map.entry("CRIMSON_FUNGUS", "minecraft:crimson_fungus"),
        Map.entry("WARPED_FUNGUS", "minecraft:warped_fungus"),
        Map.entry("AZALEA", "minecraft:azalea_tree"),
        Map.entry("MANGROVE", "minecraft:mangrove"),
        Map.entry("TALL_MANGROVE", "minecraft:tall_mangrove"),
        Map.entry("CHERRY", "minecraft:cherry"),
        Map.entry("PALE_OAK", "minecraft:pale_oak"),
        Map.entry("PALE_OAK_CREAKING", "minecraft:pale_oak_creaking")
    );

    private final String minecraftVersion;
    private final String serverSha1;
    private final Set<String> blocks;
    private final Set<String> entities;
    private final Set<String> biomes;
    private final Set<String> configuredFeatures;
    private final Set<String> placedFeatures;
    private final Set<String> structures;
    private final Map<String, JsonObject> biomeData;

    private WorldGenVanillaCatalog(CatalogData data) {
        minecraftVersion = data.minecraftVersion;
        serverSha1 = data.serverSha1;
        blocks = Set.copyOf(data.blocks);
        entities = Set.copyOf(data.entities);
        biomes = Set.copyOf(data.biomes);
        configuredFeatures = Set.copyOf(data.configuredFeatures);
        placedFeatures = Set.copyOf(data.placedFeatures);
        structures = Set.copyOf(data.structures);
        biomeData = Map.copyOf(data.biomeData);
    }

    public static WorldGenVanillaCatalog load(WorldGenTargetVersion target) {
        return CATALOGS.computeIfAbsent(target, WorldGenVanillaCatalog::read);
    }

    private static WorldGenVanillaCatalog read(WorldGenTargetVersion target) {
        String path = "/resync/worldgen/vanilla/" + target.id() + ".json";
        try (InputStream stream = WorldGenVanillaCatalog.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Vanilla Catalog For Minecraft " + target.id());
            }
            CatalogData data = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), CatalogData.class);
            if (data == null || !target.id().equals(data.minecraftVersion)) {
                throw new IllegalStateException("Invalid Vanilla Catalog For Minecraft " + target.id());
            }
            return new WorldGenVanillaCatalog(data);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable To Read Vanilla Catalog For Minecraft " + target.id(), exception);
        }
    }

    public String serverSha1() {
        return serverSha1;
    }

    public List<String> blocks() {
        return blocks.stream().sorted().toList();
    }

    public List<String> entities() {
        return entities.stream().sorted().toList();
    }

    public List<String> biomes() {
        return biomes.stream().sorted().toList();
    }

    public List<String> placedFeatures() {
        return placedFeatures.stream().sorted().toList();
    }

    public List<String> structures() {
        return structures.stream().sorted().toList();
    }

    public List<String> treeTypes() {
        return TREE_FEATURES.entrySet().stream().filter(entry -> configuredFeatures.contains(entry.getValue())).map(Map.Entry::getKey).sorted().toList();
    }

    JsonObject biome(String biomeId) {
        return template(biomeId).deepCopy();
    }

    String treeFeature(String treeType) {
        String type = treeType == null || treeType.isBlank() ? "TREE" : treeType.toUpperCase(Locale.ROOT);
        String feature = TREE_FEATURES.get(type);
        if (feature == null) {
            throw new IllegalArgumentException("Unknown Tree Type " + type);
        }
        require(feature, configuredFeatures, "Configured Feature");
        return feature;
    }

    void requireBlock(String id) {
        require(id, blocks, "Block");
    }

    void requireEntity(String id) {
        require(id, entities, "Entity Type");
    }

    void requireBiome(String id) {
        require(id, biomes, "Biome");
    }

    void requireStructure(String id) {
        require(id, structures, "Structure");
    }

    private JsonObject template(String biomeId) {
        String id = normalize(biomeId);
        JsonObject biome = biomeData.get(id);
        if (biome == null) {
            throw new IllegalArgumentException("Biome " + id + " Has No Vanilla Generation Template In Minecraft " + minecraftVersion);
        }
        return biome;
    }

    private void require(String value, Set<String> registry, String type) {
        String id = normalize(value);
        if (id.startsWith("minecraft:") && !registry.contains(id)) {
            throw new IllegalArgumentException(type + " " + id + " Does Not Exist In Minecraft " + minecraftVersion);
        }
    }

    private String normalize(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static final class CatalogData {
        private String minecraftVersion;
        private String serverSha1;
        private List<String> blocks;
        private List<String> entities;
        private List<String> biomes;
        private List<String> configuredFeatures;
        private List<String> placedFeatures;
        private List<String> structures;
        private Map<String, JsonObject> biomeData;
    }
}
