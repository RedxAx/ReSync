package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.metadata.MinecraftRegistryBundle.Catalog;
import restudio.resync.metadata.MinecraftRegistryBundle.Entry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinecraftRegistryBundleCodec implements CanonicalCodec<MinecraftRegistryBundle> {
    private static final Set<String> BUNDLE_FIELDS = Set.of("artifactFamily", "catalogs", "createdAt", "formatVersion",
        "minecraftVersion");
    private static final Set<String> CATALOG_FIELDS = Set.of("description", "entries", "id", "label");
    private static final Set<String> ENTRY_FIELDS = Set.of("description", "group", "icon", "label", "metadata", "value");

    @Override
    public JsonValue encode(MinecraftRegistryBundle bundle) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(MinecraftRegistryBundle.ARTIFACT_FAMILY.canonicalText()));
        fields.put("catalogs", JsonValue.array(bundle.catalogs().stream().map(this::catalog).toList()));
        fields.put("createdAt", JsonValue.of(bundle.createdAt()));
        fields.put("formatVersion", JsonValue.of(bundle.formatVersion()));
        fields.put("minecraftVersion", JsonValue.of(bundle.minecraftVersion()));
        return JsonValue.object(fields);
    }

    @Override
    public MinecraftRegistryBundle decode(JsonValue value) {
        JsonObject object = object(value, "Minecraft registry bundle");
        CanonicalCodec.rejectUnknownFields(object, BUNDLE_FIELDS);
        MetadataArtifactFamily family = MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"),
            "Minecraft registry artifact family"));
        if (!MinecraftRegistryBundle.ARTIFACT_FAMILY.equals(family)) {
            throw new IllegalArgumentException("Minecraft registry bundle has the wrong artifact family");
        }
        int formatVersion = integer(required(object, "formatVersion"), "Minecraft registry bundle format version");
        String minecraftVersion = text(required(object, "minecraftVersion"), "Minecraft version");
        String createdAt = text(required(object, "createdAt"), "Minecraft registry bundle creation time");
        List<Catalog> catalogs = decodeList(required(object, "catalogs"), "Catalogs", this::decodeCatalog);
        MinecraftRegistryBundle bundle = new MinecraftRegistryBundle(formatVersion, minecraftVersion, createdAt, catalogs);
        requireOrder(catalogs, bundle.catalogs(), "Catalogs");
        return bundle;
    }

    @Override
    public byte[] encodeBytes(MinecraftRegistryBundle bundle) {
        return encode(bundle).canonicalBytes(CanonicalLimits.catalog());
    }

    @Override
    public String encodeText(MinecraftRegistryBundle bundle) {
        return encode(bundle).canonicalText(CanonicalLimits.catalog());
    }

    @Override
    public MinecraftRegistryBundle decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    @Override
    public MinecraftRegistryBundle decodeText(String input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    private JsonValue catalog(Catalog catalog) {
        return JsonValue.object(Map.of(
            "description", JsonValue.of(catalog.description()),
            "entries", JsonValue.array(catalog.entries().stream().map(this::entry).toList()),
            "id", JsonValue.of(catalog.id().canonicalText()),
            "label", JsonValue.of(catalog.label())
        ));
    }

    private Catalog decodeCatalog(JsonValue value) {
        JsonObject object = object(value, "Catalog");
        CanonicalCodec.rejectUnknownFields(object, CATALOG_FIELDS);
        return new Catalog(CatalogId.parseCanonicalText(text(required(object, "id"), "Catalog ID")),
            text(required(object, "label"), "Catalog label"),
            text(required(object, "description"), "Catalog description"),
            decodeList(required(object, "entries"), "Catalog entries", this::decodeEntry));
    }

    private JsonValue entry(Entry entry) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("description", JsonValue.of(entry.description()));
        optional(fields, "group", entry.group());
        optional(fields, "icon", entry.icon());
        fields.put("label", JsonValue.of(entry.label()));
        Map<String, JsonValue> metadata = new LinkedHashMap<>();
        entry.metadata().forEach((key, value) -> metadata.put(key, JsonValue.of(value)));
        fields.put("metadata", JsonValue.object(metadata));
        fields.put("value", JsonValue.of(entry.value()));
        return JsonValue.object(fields);
    }

    private Entry decodeEntry(JsonValue value) {
        JsonObject object = object(value, "Catalog entry");
        CanonicalCodec.rejectUnknownFields(object, ENTRY_FIELDS);
        return new Entry(text(required(object, "value"), "Catalog entry value"),
            text(required(object, "label"), "Catalog entry label"),
            text(required(object, "description"), "Catalog entry description"),
            optionalText(object, "icon"), optionalText(object, "group"),
            stringMap(required(object, "metadata"), "Catalog entry metadata"));
    }

    private static JsonObject object(JsonValue value, String field) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static JsonArray array(JsonValue value, String field) {
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return array;
    }

    private static JsonValue required(JsonObject object, String field) {
        return CanonicalCodec.requireField(object, field);
    }

    private static String text(JsonValue value, String field) {
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return string.value();
    }

    private static String optionalText(JsonObject object, String field) {
        JsonValue value = object.value(field);
        return value == null ? null : text(value, field);
    }

    private static int integer(JsonValue value, String field) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        BigDecimal decimal = number.value();
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an exactly representable integer", exception);
        }
    }

    private static Map<String, String> stringMap(JsonValue value, String field) {
        JsonObject object = object(value, field);
        Map<String, String> result = new LinkedHashMap<>();
        object.values().forEach((key, item) -> result.put(key, text(item, field + " value")));
        return result;
    }

    private static void optional(Map<String, JsonValue> fields, String name, String value) {
        if (value != null) {
            fields.put(name, JsonValue.of(value));
        }
    }

    private static <T> List<T> decodeList(JsonValue value, String field, Decoder<T> decoder) {
        JsonArray array = array(value, field);
        List<T> result = new ArrayList<>(array.values().size());
        for (JsonValue item : array.values()) {
            result.add(decoder.decode(item));
        }
        return result;
    }

    private static void requireOrder(List<?> decoded, List<?> canonical, String field) {
        if (!decoded.equals(canonical)) {
            throw new IllegalArgumentException(field + " are not in canonical order");
        }
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(JsonValue value);
    }
}
