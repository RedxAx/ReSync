package restudio.resync.worldgen;

import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.datapack.WorldGenBuildRecipe;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record WorldGenGeneratedRebuildRecipe(List<Entry> entries, String assetGraphChecksum,
                                             String catalogRuntimeFingerprint, long revision, String selfHash) {
    public static final int FORMAT_VERSION = 1;
    public static final AssetKey ASSET_KEY = new AssetKey("worldgen-generated-rebuild-recipe", "v1");

    public WorldGenGeneratedRebuildRecipe {
        entries = entries == null ? List.of() : entries.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparing(Entry::projectId)).toList();
        assetGraphChecksum = requireHash(assetGraphChecksum, "assetGraphChecksum");
        catalogRuntimeFingerprint = requireHash(catalogRuntimeFingerprint, "catalogRuntimeFingerprint");
        if (revision <= 0) {
            throw new IllegalArgumentException("WorldGen Rebuild Recipe Revision Must Be Positive");
        }
        selfHash = requireHash(selfHash, "selfHash");
        if (!selfHash.equals(StorageSafety.sha256(canonicalText(entries, assetGraphChecksum, catalogRuntimeFingerprint, revision)))) {
            throw new IllegalArgumentException("WorldGen Rebuild Recipe Self-Hash Does Not Match");
        }
    }

    public static WorldGenGeneratedRebuildRecipe capture(List<Entry> entries) {
        List<Entry> sorted = entries == null ? List.of() : entries.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparing(Entry::projectId)).toList();
        String assetGraphChecksum = StorageSafety.sha256(sorted.stream()
            .map(entry -> entry.projectId() + "=" + entry.recipe().assetGraphChecksum())
            .collect(Collectors.joining("\n")));
        String catalogRuntimeFingerprint = StorageSafety.sha256(sorted.stream()
            .map(entry -> entry.projectId() + "=" + entry.recipe().catalogRuntimeFingerprint())
            .collect(Collectors.joining("\n")));
        long revision = Long.parseUnsignedLong(StorageSafety.sha256(canonicalText(sorted, assetGraphChecksum,
            catalogRuntimeFingerprint, 1L)).substring(0, 16), 16) & Long.MAX_VALUE;
        long normalizedRevision = Math.max(1L, revision);
        String selfHash = StorageSafety.sha256(canonicalText(sorted, assetGraphChecksum, catalogRuntimeFingerprint, normalizedRevision));
        return new WorldGenGeneratedRebuildRecipe(sorted, assetGraphChecksum, catalogRuntimeFingerprint, normalizedRevision, selfHash);
    }

    public static WorldGenGeneratedRebuildRecipe read(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Rebuild Recipe Is Not A Regular File: " + target);
        }
        return readBytes(Files.readAllBytes(target), target);
    }

    public static WorldGenGeneratedRebuildRecipe readBytes(byte[] bytes, Path source) throws IOException {
        byte[] encoded = Objects.requireNonNull(bytes, "bytes");
        Path target = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new ByteArrayInputStream(encoded), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0 || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null) {
                    throw new IOException("WorldGen Rebuild Recipe Contains A Duplicate Or Invalid Field: " + target);
                }
            }
        }
        if (values.isEmpty()) {
            throw new IOException("WorldGen Rebuild Recipe Is Empty: " + target);
        }
        if (!Integer.toString(FORMAT_VERSION).equals(values.get("format"))) {
            throw new IOException("WorldGen Rebuild Recipe Format Is Unsupported: " + target);
        }
        int count;
        long revision;
        try {
            count = Integer.parseInt(required(values, "entryCount"));
            revision = Long.parseLong(required(values, "revision"));
        } catch (NumberFormatException exception) {
            throw new IOException("WorldGen Rebuild Recipe Numeric Field Is Invalid: " + target, exception);
        }
        if (count < 0 || count > 100000) {
            throw new IOException("WorldGen Rebuild Recipe Entry Count Is Invalid: " + target);
        }
        Set<String> expectedKeys = new HashSet<>(List.of(
            "format", "assetGraphChecksum", "catalogRuntimeFingerprint", "revision", "entryCount", "selfHash"));
        for (int index = 0; index < count; index++) {
            String prefix = "entry." + index + ".";
            expectedKeys.add(prefix + "projectId");
            expectedKeys.add(prefix + "assetGraphChecksum");
            expectedKeys.add(prefix + "catalogRuntimeFingerprint");
            expectedKeys.add(prefix + "catalogSha1");
            expectedKeys.add(prefix + "minecraftVersion");
            expectedKeys.add(prefix + "revision");
            expectedKeys.add(prefix + "selfHash");
        }
        if (!expectedKeys.equals(values.keySet())) {
            throw new IOException("WorldGen Rebuild Recipe Contains Unknown Or Missing Fields: " + target);
        }
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "entry." + index + ".";
            try {
                WorldGenBuildRecipe buildRecipe = new WorldGenBuildRecipe(
                    Long.parseLong(required(values, prefix + "revision")),
                    required(values, prefix + "minecraftVersion"),
                    required(values, prefix + "catalogSha1"),
                    required(values, prefix + "assetGraphChecksum"),
                    required(values, prefix + "catalogRuntimeFingerprint"),
                    required(values, prefix + "selfHash"));
                entries.add(new Entry(required(values, prefix + "projectId"), buildRecipe));
            } catch (IllegalArgumentException exception) {
                throw new IOException("WorldGen Rebuild Recipe Entry Is Invalid: " + index + ": " + target, exception);
            }
        }
        try {
            return new WorldGenGeneratedRebuildRecipe(entries,
                required(values, "assetGraphChecksum"),
                required(values, "catalogRuntimeFingerprint"), revision,
                required(values, "selfHash"));
        } catch (IllegalArgumentException exception) {
            throw new IOException("WorldGen Rebuild Recipe Integrity Check Failed: " + target, exception);
        }
    }

    public byte[] encodedBytes() {
        return encode().getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(List<Entry> expected) {
        return equals(capture(expected));
    }

    public String encode() {
        StringBuilder text = new StringBuilder();
        text.append("format=").append(FORMAT_VERSION).append('\n');
        text.append("assetGraphChecksum=").append(assetGraphChecksum).append('\n');
        text.append("catalogRuntimeFingerprint=").append(catalogRuntimeFingerprint).append('\n');
        text.append("revision=").append(revision).append('\n');
        text.append("entryCount=").append(entries.size()).append('\n');
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            WorldGenBuildRecipe recipe = entry.recipe();
            String prefix = "entry." + index + ".";
            text.append(prefix).append("projectId=").append(entry.projectId()).append('\n');
            text.append(prefix).append("assetGraphChecksum=").append(recipe.assetGraphChecksum()).append('\n');
            text.append(prefix).append("catalogRuntimeFingerprint=").append(recipe.catalogRuntimeFingerprint()).append('\n');
            text.append(prefix).append("catalogSha1=").append(recipe.catalogSha1()).append('\n');
            text.append(prefix).append("minecraftVersion=").append(recipe.minecraftVersion()).append('\n');
            text.append(prefix).append("revision=").append(recipe.revision()).append('\n');
            text.append(prefix).append("selfHash=").append(recipe.selfHash()).append('\n');
        }
        text.append("selfHash=").append(selfHash).append('\n');
        return text.toString();
    }

    private static String canonicalText(List<Entry> entries, String assetGraphChecksum,
                                        String catalogRuntimeFingerprint, long revision) {
        StringBuilder text = new StringBuilder();
        text.append("format=").append(FORMAT_VERSION).append('\n');
        text.append("assetGraphChecksum=").append(assetGraphChecksum).append('\n');
        text.append("catalogRuntimeFingerprint=").append(catalogRuntimeFingerprint).append('\n');
        text.append("revision=").append(revision).append('\n');
        text.append("entryCount=").append(entries.size()).append('\n');
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            WorldGenBuildRecipe recipe = entry.recipe();
            String prefix = "entry." + index + ".";
            text.append(prefix).append("projectId=").append(entry.projectId()).append('\n');
            text.append(prefix).append("assetGraphChecksum=").append(recipe.assetGraphChecksum()).append('\n');
            text.append(prefix).append("catalogRuntimeFingerprint=").append(recipe.catalogRuntimeFingerprint()).append('\n');
            text.append(prefix).append("catalogSha1=").append(recipe.catalogSha1()).append('\n');
            text.append(prefix).append("minecraftVersion=").append(recipe.minecraftVersion()).append('\n');
            text.append(prefix).append("revision=").append(recipe.revision()).append('\n');
            text.append(prefix).append("selfHash=").append(recipe.selfHash()).append('\n');
        }
        return text.toString();
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("WorldGen Rebuild Recipe Field Is Missing: " + key);
        }
        return value;
    }

    private static String requireHash(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("WorldGen Rebuild Recipe " + field + " Must Be A SHA-256 Digest");
        }
        return normalized;
    }

    public record Entry(String projectId, WorldGenBuildRecipe recipe) {
        public Entry {
            projectId = Objects.requireNonNull(projectId, "projectId").trim();
            if (projectId.isBlank() || projectId.contains("\n") || projectId.contains("\r") || projectId.contains("=")) {
                throw new IllegalArgumentException("WorldGen Rebuild Recipe Project ID Is Invalid");
            }
            recipe = Objects.requireNonNull(recipe, "recipe");
            if (!recipe.selfHashValid()) {
                throw new IllegalArgumentException("WorldGen Build Recipe Self-Hash Does Not Match: " + projectId);
            }
        }
    }
}
