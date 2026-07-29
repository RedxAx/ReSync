package restudio.resync.worldgen.contract;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public enum WorldGenTargetVersion {
    MINECRAFT_1_21("1.21", 48, 0),
    MINECRAFT_1_21_1("1.21.1", 48, 0),
    MINECRAFT_1_21_2("1.21.2", 57, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS),
    MINECRAFT_1_21_3("1.21.3", 57, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS),
    MINECRAFT_1_21_4("1.21.4", 61, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS),
    MINECRAFT_1_21_5("1.21.5", 71, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS),
    MINECRAFT_1_21_6("1.21.6", 80, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON),
    MINECRAFT_1_21_7("1.21.7", 81, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON),
    MINECRAFT_1_21_8("1.21.8", 81, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON),
    MINECRAFT_1_21_9("1.21.9", 88, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON,
        WorldGenDatapackCapability.VERSIONED_PACK_METADATA),
    MINECRAFT_1_21_10("1.21.10", 88, 0, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON,
        WorldGenDatapackCapability.VERSIONED_PACK_METADATA),
    MINECRAFT_1_21_11("1.21.11", 94, 1, WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.STRICT_JSON,
        WorldGenDatapackCapability.VERSIONED_PACK_METADATA, WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES),
    MINECRAFT_26_1("26.1", 101, 1, WorldGenDatapackCapability.STRICT_JSON, WorldGenDatapackCapability.VERSIONED_PACK_METADATA,
        WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES, WorldGenDatapackCapability.WORLD_CLOCKS,
        WorldGenDatapackCapability.JAVA_25_RUNTIME),
    MINECRAFT_26_1_1("26.1.1", 101, 1, WorldGenDatapackCapability.STRICT_JSON, WorldGenDatapackCapability.VERSIONED_PACK_METADATA,
        WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES, WorldGenDatapackCapability.WORLD_CLOCKS,
        WorldGenDatapackCapability.JAVA_25_RUNTIME),
    MINECRAFT_26_1_2("26.1.2", 101, 1, WorldGenDatapackCapability.STRICT_JSON, WorldGenDatapackCapability.VERSIONED_PACK_METADATA,
        WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES, WorldGenDatapackCapability.WORLD_CLOCKS,
        WorldGenDatapackCapability.JAVA_25_RUNTIME),
    MINECRAFT_26_2("26.2", 107, 1, WorldGenDatapackCapability.STRICT_JSON, WorldGenDatapackCapability.VERSIONED_PACK_METADATA,
        WorldGenDatapackCapability.FLAT_BIOME_CARVERS, WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES, WorldGenDatapackCapability.WORLD_CLOCKS,
        WorldGenDatapackCapability.JAVA_25_RUNTIME);

    public static final String OPTION_CONTEXT_KEY = "minecraft_version";
    public static final WorldGenTargetVersion DEFAULT = MINECRAFT_26_2;
    private static final List<String> SUPPORTED_IDS = Arrays.stream(values()).map(WorldGenTargetVersion::id).toList();
    private final String id;
    private final int datapackMajor;
    private final int datapackMinor;
    private final Set<WorldGenDatapackCapability> capabilities;

    WorldGenTargetVersion(String id, int datapackMajor, int datapackMinor, WorldGenDatapackCapability... capabilities) {
        this.id = id;
        this.datapackMajor = datapackMajor;
        this.datapackMinor = datapackMinor;
        this.capabilities = capabilities.length == 0
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(List.of(capabilities)));
    }

    public String id() {
        return id;
    }

    public int datapackMajor() {
        return datapackMajor;
    }

    public int datapackMinor() {
        return datapackMinor;
    }

    public String datapackVersion() {
        return supports(WorldGenDatapackCapability.VERSIONED_PACK_METADATA) ? datapackMajor + "." + datapackMinor : String.valueOf(datapackMajor);
    }

    public List<Integer> exactDatapackFormat() {
        return List.of(datapackMajor, datapackMinor);
    }

    public boolean supports(WorldGenDatapackCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean isDatapackCompatibleWith(WorldGenTargetVersion other) {
        return other != null && datapackMajor == other.datapackMajor && datapackMinor == other.datapackMinor;
    }

    public static List<String> supportedIds() {
        return SUPPORTED_IDS;
    }

    public static WorldGenTargetVersion require(String value) {
        String normalized = normalize(value);
        return Arrays.stream(values())
            .filter(version -> version.id.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported Minecraft Version " + value));
    }

    public static WorldGenTargetVersion resolve(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return require(value);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("1.21.0".equals(normalized)) {
            return "1.21";
        }
        return normalized;
    }
}
