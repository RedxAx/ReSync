package restudio.resync.worldgen.datapack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenProjectSettings;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record WorldGenBuildRecipe(long revision, String minecraftVersion, String catalogSha1,
                                  String assetGraphChecksum, String catalogRuntimeFingerprint, String selfHash) {
    public WorldGenBuildRecipe(long revision, String minecraftVersion, String catalogSha1) {
        this(revision, minecraftVersion, catalogSha1, "", "", "");
    }

    public WorldGenBuildRecipe {
        if (revision <= 0) {
            throw new IllegalArgumentException("WorldGen Build Revision Must Be Positive");
        }
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        catalogSha1 = requireText(catalogSha1, "catalogSha1").toLowerCase(Locale.ROOT);
        if (!catalogSha1.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("catalogSha1 Must Be A SHA-1 Digest");
        }
        assetGraphChecksum = optionalHash(assetGraphChecksum, "assetGraphChecksum");
        catalogRuntimeFingerprint = optionalHash(catalogRuntimeFingerprint, "catalogRuntimeFingerprint");
        selfHash = optionalHash(selfHash, "selfHash");
    }

    public static WorldGenBuildRecipe capture(WorldGenProject project) {
        Objects.requireNonNull(project, "project");
        WorldGenProjectSettings settings = project.getSettings() == null ? new WorldGenProjectSettings() : project.getSettings();
        String configuredTarget = settings.getTargetVersion();
        String detectedTarget = WorldGenTargetVersion.AUTOMATIC.equalsIgnoreCase(configuredTarget)
            ? detectedMinecraftVersion()
            : configuredTarget;
        WorldGenTargetVersion target = resolveTarget(project, detectedTarget);
        WorldGenVanillaCatalog catalog = WorldGenVanillaCatalog.load(target);
        String assetGraphChecksum = canonicalAssetChecksum(project);
        String catalogRuntimeFingerprint = StorageSafety.sha256(canonicalFields(Map.of(
            "catalogSha1", catalog.serverSha1(),
            "minecraftVersion", target.id(),
            "runtimeVersion", runtimeVersion()
        )));
        long revision = Long.parseUnsignedLong(StorageSafety.sha256(canonicalFields(Map.of(
            "assetGraphChecksum", assetGraphChecksum,
            "catalogRuntimeFingerprint", catalogRuntimeFingerprint,
            "minecraftVersion", target.id()
        ))).substring(0, 16), 16) & Long.MAX_VALUE;
        WorldGenBuildRecipe withoutSelfHash = new WorldGenBuildRecipe(Math.max(1L, revision), target.id(), catalog.serverSha1(),
            assetGraphChecksum, catalogRuntimeFingerprint, "");
        return new WorldGenBuildRecipe(withoutSelfHash.revision(), withoutSelfHash.minecraftVersion(), withoutSelfHash.catalogSha1(),
            withoutSelfHash.assetGraphChecksum(), withoutSelfHash.catalogRuntimeFingerprint(),
            StorageSafety.sha256(withoutSelfHash.canonicalText()));
    }

    public static WorldGenTargetVersion resolveTarget(WorldGenProject project, String detectedVersion) {
        Objects.requireNonNull(project, "project");
        WorldGenProjectSettings settings = project.getSettings() == null ? new WorldGenProjectSettings() : project.getSettings();
        String configured = settings.getTargetVersion();
        WorldGenGenerationMode.resolve(settings.getGenerationMode());
        return WorldGenTargetVersion.AUTOMATIC.equalsIgnoreCase(configured)
            ? WorldGenTargetVersion.resolve(configured, detectedVersion)
            : WorldGenTargetVersion.require(configured);
    }

    public boolean matches(WorldGenProject project) {
        return equals(capture(project));
    }

    public String canonicalText() {
        return canonicalFields(Map.of(
            "assetGraphChecksum", assetGraphChecksum,
            "catalogRuntimeFingerprint", catalogRuntimeFingerprint,
            "catalogSha1", catalogSha1,
            "minecraftVersion", minecraftVersion,
            "revision", Long.toString(revision)
        ));
    }

    public boolean selfHashValid() {
        return !selfHash.isBlank() && selfHash.equals(StorageSafety.sha256(canonicalText()));
    }

    public static String canonicalAssetChecksum(WorldGenProject project) {
        Objects.requireNonNull(project, "project");
        return StorageSafety.sha256(canonicalJson(WorldGenSerializer.serializeProject(project)));
    }

    private static String runtimeVersion() {
        String version;
        try {
            version = Bukkit.getVersion();
        } catch (RuntimeException exception) {
            version = "";
        }
        if (version == null || version.isBlank()) {
            try {
                version = Bukkit.getMinecraftVersion();
            } catch (RuntimeException exception) {
                version = "";
            }
        }
        return version == null ? "" : version.trim();
    }

    private static String detectedMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            return version == null ? "" : version.trim();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String canonicalJson(String source) {
        try {
            return canonicalJson(JsonParser.parseString(source));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("WorldGen Project JSON Is Invalid", exception);
        }
    }

    private static String canonicalJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().asList().stream()
                .map(WorldGenBuildRecipe::canonicalJson)
                .collect(Collectors.joining(",", "[", "]"));
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> quote(entry.getKey()) + ":" + canonicalJson(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        return element.toString();
    }

    private static String canonicalFields(Map<String, String> fields) {
        return new TreeMap<>(fields).entrySet().stream()
            .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        return new Gson().toJson(value == null ? "" : value);
    }

    private static String optionalHash(String value, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank() && !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " Must Be A SHA-256 Digest");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }
}
