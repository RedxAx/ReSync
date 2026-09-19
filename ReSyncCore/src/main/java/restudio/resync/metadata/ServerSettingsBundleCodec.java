package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerSettingsBundleCodec implements CanonicalCodec<ServerSettingsBundle> {
    private static final Set<String> BUNDLE_FIELDS = Set.of("artifactFamily", "createdAt", "formatVersion", "sources");
    private static final Set<String> SOURCE_FIELDS = Set.of("content", "contentSha256", "id", "mediaType", "schemaVersion");

    @Override
    public JsonValue encode(ServerSettingsBundle bundle) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(ServerSettingsBundle.ARTIFACT_FAMILY.canonicalText()));
        fields.put("createdAt", JsonValue.of(bundle.createdAt()));
        fields.put("formatVersion", JsonValue.of(bundle.formatVersion()));
        fields.put("sources", JsonValue.array(bundle.sources().stream().map(this::source).toList()));
        return JsonValue.object(fields);
    }

    @Override
    public ServerSettingsBundle decode(JsonValue value) {
        JsonObject object = object(value, "Server settings bundle");
        CanonicalCodec.rejectUnknownFields(object, BUNDLE_FIELDS);
        MetadataArtifactFamily family = MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"),
                "Server settings artifact family"));
        if (!ServerSettingsBundle.ARTIFACT_FAMILY.equals(family)) {
            throw new IllegalArgumentException("Server settings bundle has the wrong artifact family");
        }
        int formatVersion = integer(required(object, "formatVersion"), "Server settings bundle format version");
        String createdAt = text(required(object, "createdAt"), "Server settings bundle creation time");
        JsonArray values = array(required(object, "sources"), "Server settings sources");
        List<ServerSettingsBundle.Source> sources = new ArrayList<>(values.values().size());
        for (JsonValue item : values.values()) {
            sources.add(decodeSource(item));
        }
        ServerSettingsBundle bundle = new ServerSettingsBundle(formatVersion, createdAt, sources);
        if (!sources.equals(bundle.sources())) {
            throw new IllegalArgumentException("Server settings sources are not in canonical order");
        }
        return bundle;
    }

    @Override
    public byte[] encodeBytes(ServerSettingsBundle bundle) {
        return encode(bundle).canonicalBytes(CanonicalLimits.catalog());
    }

    @Override
    public String encodeText(ServerSettingsBundle bundle) {
        return encode(bundle).canonicalText(CanonicalLimits.catalog());
    }

    @Override
    public ServerSettingsBundle decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    @Override
    public ServerSettingsBundle decodeText(String input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    private JsonValue source(ServerSettingsBundle.Source source) {
        return JsonValue.object(Map.of(
                "content", JsonValue.of(source.content()),
                "contentSha256", JsonValue.of(source.contentSha256()),
                "id", JsonValue.of(source.id()),
                "mediaType", JsonValue.of(source.mediaType()),
                "schemaVersion", JsonValue.of(source.schemaVersion())
        ));
    }

    private ServerSettingsBundle.Source decodeSource(JsonValue value) {
        JsonObject object = object(value, "Server settings source");
        CanonicalCodec.rejectUnknownFields(object, SOURCE_FIELDS);
        return new ServerSettingsBundle.Source(
                text(required(object, "id"), "Server settings source ID"),
                integer(required(object, "schemaVersion"), "Server settings source schema version"),
                text(required(object, "mediaType"), "Server settings source media type"),
                text(required(object, "contentSha256"), "Server settings source SHA-256"),
                text(required(object, "content"), "Server settings source content"));
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
}
