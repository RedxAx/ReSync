package restudio.resync.worldgen.registry;

import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.structure.StructureSummary;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.datapack.WorldGenVanillaCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class WorldGenOptionCatalogs {
    private WorldGenOptionCatalogs() {
    }

    public static void register(OptionCatalogRegistry registry) {
        registry.register(provider("worldgen:blocks", WorldGenVanillaCatalog::blocks));
        registry.register(provider("worldgen:biomes", WorldGenVanillaCatalog::biomes));
        registry.register(provider("worldgen:entity_types", WorldGenVanillaCatalog::entities));
        registry.register(provider("worldgen:structures", WorldGenOptionCatalogs::structures));
        registry.register(provider("worldgen:tree_features", WorldGenVanillaCatalog::treeTypes));
        registry.register(provider("worldgen:features", WorldGenVanillaCatalog::placedFeatures));
    }

    private static OptionCatalogProvider provider(String sourceId, Function<WorldGenVanillaCatalog, List<String>> values) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public Set<String> contextKeys() {
                return Set.of(WorldGenTargetVersion.OPTION_CONTEXT_KEY);
            }

            @Override
            public String revision() {
                return revision(null);
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                WorldGenTargetVersion target = target(query);
                return target.id() + ":" + WorldGenVanillaCatalog.load(target).serverSha1();
            }

            @Override
            public List<String> values() {
                return values(null);
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return values.apply(WorldGenVanillaCatalog.load(target(query)));
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                WorldGenTargetVersion target = target(query);
                return values.apply(WorldGenVanillaCatalog.load(target)).stream()
                    .map(value -> item(sourceId, value, target))
                    .toList();
            }
        };
    }

    private static WorldGenTargetVersion target(OptionCatalogQuery query) {
        return WorldGenTargetVersion.resolve(query != null ? query.text(WorldGenTargetVersion.OPTION_CONTEXT_KEY) : null);
    }

    private static List<String> structures(WorldGenVanillaCatalog catalog) {
        List<String> custom = ReSync.getInstance() == null ? List.of() : StructureLibrary.get(ReSync.getInstance()).list().stream().map(StructureSummary::id).toList();
        return Stream.concat(catalog.structures().stream(), custom.stream()).distinct().sorted().toList();
    }

    private static OptionCatalogItem item(String source, String value, WorldGenTargetVersion target) {
        String label = label(value);
        String group = group(source, value);
        String description = switch (source) {
            case "worldgen:blocks" -> "Block Available In Minecraft " + target.id();
            case "worldgen:biomes" -> "Biome Available In Minecraft " + target.id();
            case "worldgen:entity_types" -> "Entity Available For Spawn Rules";
            case "worldgen:structures" -> value.startsWith("minecraft:") ? "Vanilla Structure" : "ReSync Structure";
            case "worldgen:tree_features" -> "Vanilla Tree Configuration";
            case "worldgen:features" -> "Vanilla Placed Feature";
            default -> "";
        };
        return new OptionCatalogItem(value, label, description, "", group, Map.of("minecraftVersion", target.id(), "namespace", namespace(value)));
    }

    private static String group(String source, String value) {
        String id = value.toLowerCase(Locale.ROOT);
        return switch (source) {
            case "worldgen:structures" -> value.startsWith("minecraft:") ? structureGroup(id) : "ReSync Structures";
            case "worldgen:features" -> featureGroup(id);
            case "worldgen:entity_types" -> entityGroup(id);
            case "worldgen:biomes" -> biomeGroup(id);
            case "worldgen:blocks" -> blockGroup(id);
            case "worldgen:tree_features" -> "Trees";
            default -> "Other";
        };
    }

    private static String structureGroup(String id) {
        if (containsAny(id, "village", "outpost", "mansion", "monument")) return "Settlements";
        if (containsAny(id, "fortress", "bastion", "city", "stronghold", "trial_chambers")) return "Dungeons";
        if (containsAny(id, "ruin", "shipwreck", "portal", "mineshaft")) return "Ruins";
        return "Vanilla Structures";
    }

    private static String featureGroup(String id) {
        if (containsAny(id, "ore", "geode")) return "Ores";
        if (containsAny(id, "tree", "vegetation", "flower", "grass", "mushroom")) return "Vegetation";
        if (containsAny(id, "lake", "spring", "water", "lava")) return "Fluids";
        if (containsAny(id, "cave", "underground", "dripstone")) return "Underground";
        return "Terrain Features";
    }

    private static String entityGroup(String id) {
        if (containsAny(id, "zombie", "skeleton", "creeper", "spider", "slime", "warden", "phantom", "witch", "pillager", "vindicator", "ravager", "blaze", "ghast", "enderman", "shulker")) return "Monsters";
        if (containsAny(id, "cod", "salmon", "squid", "dolphin", "guardian", "turtle", "axolotl", "tadpole")) return "Water Creatures";
        if (containsAny(id, "cow", "pig", "sheep", "chicken", "horse", "wolf", "cat", "rabbit", "fox", "goat", "camel", "armadillo")) return "Creatures";
        return "Other Entities";
    }

    private static String biomeGroup(String id) {
        if (id.contains("nether") || containsAny(id, "soul_sand_valley", "basalt_deltas", "crimson_forest", "warped_forest")) return "Nether";
        if (id.contains("end") || containsAny(id, "small_end_islands", "end_midlands", "end_highlands", "end_barrens")) return "End";
        if (containsAny(id, "ocean", "river", "beach")) return "Aquatic";
        if (containsAny(id, "snow", "frozen", "ice", "grove", "peaks")) return "Cold";
        if (containsAny(id, "desert", "savanna", "badlands", "jungle")) return "Warm";
        return "Overworld";
    }

    private static String blockGroup(String id) {
        if (containsAny(id, "_ore", "stone", "dirt", "sand", "gravel", "clay", "deepslate")) return "Terrain";
        if (containsAny(id, "_log", "_wood", "_leaves", "sapling", "flower", "grass", "mushroom")) return "Plants";
        if (containsAny(id, "chest", "furnace", "crafting", "redstone", "hopper", "piston", "rail")) return "Functional";
        return "Building Blocks";
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String label(String value) {
        String id = value.substring(value.indexOf(':') + 1).replace('_', ' ');
        StringBuilder label = new StringBuilder(id.length());
        boolean capitalize = true;
        for (char character : id.toCharArray()) {
            if (character == ' ') {
                label.append(character);
                capitalize = true;
            } else {
                label.append(capitalize ? Character.toUpperCase(character) : character);
                capitalize = false;
            }
        }
        return label.toString();
    }

    private static String namespace(String value) {
        int separator = value.indexOf(':');
        return separator > 0 ? value.substring(0, separator) : "resync";
    }
}
