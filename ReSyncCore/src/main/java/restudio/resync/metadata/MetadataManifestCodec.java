package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MetadataManifestCodec implements CanonicalCodec<MetadataManifest> {
    private static final Set<String> MANIFEST_FIELDS = Set.of("bundles", "formatVersion", "resolverRevision");
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of("artifactFamily", "bundleId", "byteSize", "createdAt",
        "formatVersion", "provenance", "selector");
    private static final Set<String> SELECTOR_FIELDS = Set.of("artifactFamily", "distribution", "edition", "maximumDataVersion",
        "maximumProtocolVersion", "minecraftVersion", "minimumDataVersion", "minimumProtocolVersion", "requiredCapabilities",
        "softwareFamily", "softwareVersion");

    @Override
    public JsonValue encode(MetadataManifest manifest) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("bundles", JsonValue.array(manifest.bundles().stream().map(this::descriptor).toList()));
        fields.put("formatVersion", JsonValue.of(manifest.formatVersion()));
        fields.put("resolverRevision", JsonValue.of(manifest.resolverRevision()));
        return JsonValue.object(fields);
    }

    @Override
    public MetadataManifest decode(JsonValue value) {
        JsonObject object = object(value, "Metadata manifest");
        CanonicalCodec.rejectUnknownFields(object, MANIFEST_FIELDS);
        int formatVersion = integer(required(object, "formatVersion"), "Metadata manifest format version");
        String resolverRevision = text(required(object, "resolverRevision"), "Metadata resolver revision");
        JsonArray bundles = array(required(object, "bundles"), "Metadata bundles");
        List<MetadataBundleDescriptor> descriptors = new ArrayList<>(bundles.values().size());
        for (JsonValue descriptor : bundles.values()) {
            descriptors.add(decodeDescriptor(descriptor));
        }
        MetadataManifest manifest = new MetadataManifest(formatVersion, resolverRevision, descriptors);
        if (!descriptors.equals(manifest.bundles())) {
            throw new IllegalArgumentException("Metadata bundles are not in canonical order");
        }
        return manifest;
    }

    @Override
    public byte[] encodeBytes(MetadataManifest manifest) {
        return encode(manifest).canonicalBytes(CanonicalLimits.catalog());
    }

    @Override
    public String encodeText(MetadataManifest manifest) {
        return encode(manifest).canonicalText(CanonicalLimits.catalog());
    }

    @Override
    public MetadataManifest decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    @Override
    public MetadataManifest decodeText(String input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    private JsonValue descriptor(MetadataBundleDescriptor descriptor) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(descriptor.artifactFamily().canonicalText()));
        fields.put("bundleId", JsonValue.of(descriptor.bundleId().canonicalText()));
        fields.put("byteSize", JsonValue.of(descriptor.byteSize()));
        fields.put("createdAt", JsonValue.of(descriptor.createdAt().toString()));
        fields.put("formatVersion", JsonValue.of(descriptor.formatVersion()));
        Map<String, JsonValue> provenance = new LinkedHashMap<>();
        descriptor.provenance().forEach((key, value) -> provenance.put(key, JsonValue.of(value)));
        fields.put("provenance", JsonValue.object(provenance));
        fields.put("selector", encodeSelector(descriptor.selector()));
        return JsonValue.object(fields);
    }

    private MetadataBundleDescriptor decodeDescriptor(JsonValue value) {
        JsonObject object = object(value, "Metadata bundle descriptor");
        CanonicalCodec.rejectUnknownFields(object, DESCRIPTOR_FIELDS);
        MetadataArtifactFamily artifactFamily = MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"),
            "Metadata artifact family"));
        MetadataBundleId bundleId = MetadataBundleId.parseCanonicalText(text(required(object, "bundleId"), "Metadata bundle ID"));
        long byteSize = longNumber(required(object, "byteSize"), "Metadata bundle byte size");
        Instant createdAt = instant(text(required(object, "createdAt"), "Metadata bundle creation time"));
        int formatVersion = integer(required(object, "formatVersion"), "Metadata bundle format version");
        Map<String, String> provenance = stringMap(object(required(object, "provenance"), "Metadata bundle provenance"));
        MetadataSelector selector = decodeSelector(required(object, "selector"));
        return new MetadataBundleDescriptor(bundleId, artifactFamily, formatVersion, selector, byteSize, createdAt, provenance);
    }

    public JsonValue encodeSelector(MetadataSelector selector) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(selector.artifactFamily().canonicalText()));
        optional(fields, "distribution", selector.distribution());
        optional(fields, "edition", selector.edition());
        optional(fields, "maximumDataVersion", selector.maximumDataVersion());
        optional(fields, "maximumProtocolVersion", selector.maximumProtocolVersion());
        optional(fields, "minecraftVersion", selector.minecraftVersion());
        optional(fields, "minimumDataVersion", selector.minimumDataVersion());
        optional(fields, "minimumProtocolVersion", selector.minimumProtocolVersion());
        fields.put("requiredCapabilities", JsonValue.array(selector.requiredCapabilities().stream().map(JsonValue::of).toList()));
        optional(fields, "softwareFamily", selector.softwareFamily());
        optional(fields, "softwareVersion", selector.softwareVersion());
        return JsonValue.object(fields);
    }

    public MetadataSelector decodeSelector(JsonValue value) {
        JsonObject object = object(value, "Metadata selector");
        CanonicalCodec.rejectUnknownFields(object, SELECTOR_FIELDS);
        return new MetadataSelector(
            MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"), "Metadata selector artifact family")),
            optionalText(object, "edition"),
            optionalText(object, "minecraftVersion"),
            optionalInteger(object, "minimumDataVersion"),
            optionalInteger(object, "maximumDataVersion"),
            optionalInteger(object, "minimumProtocolVersion"),
            optionalInteger(object, "maximumProtocolVersion"),
            optionalText(object, "softwareFamily"),
            optionalText(object, "softwareVersion"),
            optionalText(object, "distribution"),
            strings(array(required(object, "requiredCapabilities"), "Required capabilities"))
        );
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
        long number = longNumber(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " exceeds the integer range");
        }
        return (int) number;
    }

    private static Integer optionalInteger(JsonObject object, String field) {
        JsonValue value = object.value(field);
        return value == null ? null : integer(value, field);
    }

    private static long longNumber(JsonValue value, String field) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        BigDecimal decimal = number.value();
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an exactly representable integer", exception);
        }
    }

    private static Instant instant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("Metadata bundle creation time must use canonical ISO-8601 UTC text");
            }
            return parsed;
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Metadata bundle creation time must use canonical ISO-8601 UTC text", exception);
        }
    }

    private static Map<String, String> stringMap(JsonObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> result.put(key, text(value, "Metadata provenance value")));
        return result;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> result = new LinkedHashSet<>();
        String previous = null;
        for (JsonValue value : array.values()) {
            String text = text(value, "Required capability");
            if (!result.add(text)) {
                throw new IllegalArgumentException("Required capabilities contain a duplicate: " + text);
            }
            if (previous != null && previous.compareTo(text) >= 0) {
                throw new IllegalArgumentException("Required capabilities are not in canonical order");
            }
            previous = text;
        }
        return result;
    }

    private static void optional(Map<String, JsonValue> fields, String name, String value) {
        if (value != null) {
            fields.put(name, JsonValue.of(value));
        }
    }

    private static void optional(Map<String, JsonValue> fields, String name, Integer value) {
        if (value != null) {
            fields.put(name, JsonValue.of(value));
        }
    }
}
