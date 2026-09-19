package restudio.resync.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record MinecraftRegistryBundle(int formatVersion, String minecraftVersion, String createdAt, List<Catalog> catalogs) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final MetadataArtifactFamily ARTIFACT_FAMILY = MetadataArtifactFamily.of("minecraft_registry");
    public static final int MAX_CATALOGS = 1_024;
    public static final int MAX_ENTRIES_PER_CATALOG = 1_000_000;
    public static final int MAX_ENTRY_METADATA = 64;

    private static final Pattern OPAQUE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,191}");

    public MinecraftRegistryBundle {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Minecraft registry bundle format version: " + formatVersion);
        }
        minecraftVersion = opaqueVersion(minecraftVersion);
        createdAt = MetadataValidation.text(createdAt, "Minecraft registry bundle creation time", 128);
        catalogs = catalogs(catalogs);
    }

    public MinecraftRegistryBundle(String minecraftVersion, String createdAt, List<Catalog> catalogs) {
        this(CURRENT_FORMAT_VERSION, minecraftVersion, createdAt, catalogs);
    }

    public record Catalog(CatalogId id, String label, String description, List<Entry> entries) implements Comparable<Catalog> {
        public Catalog {
            id = Objects.requireNonNull(id, "Catalog ID is required");
            label = MetadataValidation.text(label, "Catalog label", 256);
            description = MetadataValidation.text(description, "Catalog description", 2_048);
            entries = MinecraftRegistryBundle.entries(entries, id);
        }

        @Override
        public int compareTo(Catalog other) {
            return id.compareTo(Objects.requireNonNull(other, "Catalog is required").id);
        }
    }

    public record Entry(String value, String label, String description, String icon, String group, Map<String, String> metadata) {
        public Entry {
            value = MetadataValidation.text(value, "Catalog entry value", 1_024);
            label = MetadataValidation.text(label, "Catalog entry label", 256);
            description = MetadataValidation.text(description, "Catalog entry description", 2_048);
            icon = MetadataValidation.optionalText(icon, "Catalog entry icon", 2_048);
            group = MetadataValidation.optionalText(group, "Catalog entry group", 256);
            metadata = MinecraftRegistryBundle.metadata(metadata);
        }
    }

    private static String opaqueVersion(String value) {
        String checked = MetadataValidation.text(value, "Minecraft version", 192);
        if (!OPAQUE_VERSION.matcher(checked).matches() || ".".equals(checked) || "..".equals(checked) || checked.contains("..")) {
            throw new IllegalArgumentException("Minecraft version contains unsafe characters");
        }
        return checked;
    }

    private static List<Catalog> catalogs(List<Catalog> values) {
        Objects.requireNonNull(values, "Catalogs are required");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Catalogs must not be empty");
        }
        if (values.size() > MAX_CATALOGS) {
            throw new IllegalArgumentException("Catalog count exceeds " + MAX_CATALOGS);
        }
        ArrayList<Catalog> sorted = new ArrayList<>(values.size());
        Set<CatalogId> identities = new HashSet<>();
        for (Catalog value : values) {
            Catalog checked = Objects.requireNonNull(value, "Catalog is required");
            if (!identities.add(checked.id())) {
                throw new IllegalArgumentException("Catalogs contain a duplicate identity: " + checked.id());
            }
            sorted.add(checked);
        }
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static List<Entry> entries(List<Entry> values, CatalogId catalogId) {
        Objects.requireNonNull(values, "Catalog entries are required");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Catalog entries must not be empty: " + catalogId);
        }
        if (values.size() > MAX_ENTRIES_PER_CATALOG) {
            throw new IllegalArgumentException("Catalog entry count exceeds " + MAX_ENTRIES_PER_CATALOG + ": " + catalogId);
        }
        ArrayList<Entry> ordered = new ArrayList<>(values.size());
        Set<String> identities = new HashSet<>();
        for (Entry value : values) {
            Entry checked = Objects.requireNonNull(value, "Catalog entry is required");
            if (!identities.add(checked.value())) {
                throw new IllegalArgumentException("Catalog contains a duplicate entry value: " + checked.value());
            }
            ordered.add(checked);
        }
        return List.copyOf(ordered);
    }

    private static Map<String, String> metadata(Map<String, String> values) {
        Objects.requireNonNull(values, "Catalog entry metadata is required");
        if (values.size() > MAX_ENTRY_METADATA) {
            throw new IllegalArgumentException("Catalog entry metadata count exceeds " + MAX_ENTRY_METADATA);
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = MetadataValidation.id(entry.getKey(), "Catalog entry metadata key");
            String value = MetadataValidation.text(entry.getValue(), "Catalog entry metadata value", 2_048);
            if (sorted.put(key, value) != null) {
                throw new IllegalArgumentException("Catalog entry metadata contains a duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
