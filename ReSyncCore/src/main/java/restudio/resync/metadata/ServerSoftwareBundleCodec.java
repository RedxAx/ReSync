package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.metadata.ServerSoftwareBundle.GameRelease;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareBuild;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareCategory;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareFamily;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareVersion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerSoftwareBundleCodec implements CanonicalCodec<ServerSoftwareBundle> {
    private static final Set<String> BUNDLE_FIELDS = Set.of("artifactFamily", "categories", "createdAt", "families",
        "formatVersion", "gameReleases");
    private static final Set<String> RELEASE_FIELDS = Set.of("display", "id", "order", "releaseTime", "supported", "type");
    private static final Set<String> CATEGORY_FIELDS = Set.of("display", "id");
    private static final Set<String> FAMILY_FIELDS = Set.of("categoryIds", "color", "compatibility", "compatibleGameReleaseIds",
        "deprecated", "description", "display", "experimental", "homepage", "icon", "id", "versions");
    private static final Set<String> VERSION_FIELDS = Set.of("buildCount", "builds", "createdAt", "display", "gameReleaseId",
        "id", "latestBuildDisplay", "latestBuildId", "order", "supported");
    private static final Set<String> BUILD_FIELDS = Set.of("display", "id", "order");

    @Override
    public JsonValue encode(ServerSoftwareBundle bundle) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(ServerSoftwareBundle.ARTIFACT_FAMILY.canonicalText()));
        fields.put("categories", JsonValue.array(bundle.categories().stream().map(this::category).toList()));
        fields.put("createdAt", JsonValue.of(bundle.createdAt()));
        fields.put("families", JsonValue.array(bundle.families().stream().map(this::family).toList()));
        fields.put("formatVersion", JsonValue.of(bundle.formatVersion()));
        fields.put("gameReleases", JsonValue.array(bundle.gameReleases().stream().map(this::release).toList()));
        return JsonValue.object(fields);
    }

    @Override
    public ServerSoftwareBundle decode(JsonValue value) {
        JsonObject object = object(value, "Server software bundle");
        CanonicalCodec.rejectUnknownFields(object, BUNDLE_FIELDS);
        MetadataArtifactFamily family = MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"),
            "Server software artifact family"));
        if (!ServerSoftwareBundle.ARTIFACT_FAMILY.equals(family)) {
            throw new IllegalArgumentException("Server software bundle has the wrong artifact family");
        }
        int formatVersion = integer(required(object, "formatVersion"), "Server software bundle format version");
        String createdAt = text(required(object, "createdAt"), "Server software bundle creation time");
        List<GameRelease> releases = decodeList(required(object, "gameReleases"), "Game releases", this::decodeRelease);
        List<SoftwareCategory> categories = decodeList(required(object, "categories"), "Software categories", this::decodeCategory);
        List<SoftwareFamily> families = decodeList(required(object, "families"), "Software families", this::decodeFamily);
        ServerSoftwareBundle bundle = new ServerSoftwareBundle(formatVersion, createdAt, releases, categories, families);
        requireOrder(releases, bundle.gameReleases(), "Game releases");
        requireOrder(categories, bundle.categories(), "Software categories");
        requireOrder(families, bundle.families(), "Software families");
        return bundle;
    }

    @Override
    public byte[] encodeBytes(ServerSoftwareBundle bundle) {
        return encode(bundle).canonicalBytes(CanonicalLimits.catalog());
    }

    @Override
    public String encodeText(ServerSoftwareBundle bundle) {
        return encode(bundle).canonicalText(CanonicalLimits.catalog());
    }

    @Override
    public ServerSoftwareBundle decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    @Override
    public ServerSoftwareBundle decodeText(String input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    private JsonValue release(GameRelease release) {
        return JsonValue.object(Map.of(
            "display", JsonValue.of(release.display()),
            "id", JsonValue.of(release.id()),
            "order", JsonValue.of(release.order()),
            "releaseTime", JsonValue.of(release.releaseTime()),
            "type", JsonValue.of(release.type()),
            "supported", JsonValue.of(release.supported())
        ));
    }

    private GameRelease decodeRelease(JsonValue value) {
        JsonObject object = object(value, "Game release");
        CanonicalCodec.rejectUnknownFields(object, RELEASE_FIELDS);
        return new GameRelease(text(required(object, "id"), "Game release ID"),
            text(required(object, "display"), "Game release display"),
            text(required(object, "type"), "Game release type"),
            text(required(object, "releaseTime"), "Game release time"),
            bool(required(object, "supported"), "Game release supported flag"),
            integer(required(object, "order"), "Game release order"));
    }

    private JsonValue category(SoftwareCategory category) {
        return JsonValue.object(Map.of(
            "display", JsonValue.of(category.display()),
            "id", JsonValue.of(category.id())
        ));
    }

    private SoftwareCategory decodeCategory(JsonValue value) {
        JsonObject object = object(value, "Software category");
        CanonicalCodec.rejectUnknownFields(object, CATEGORY_FIELDS);
        return new SoftwareCategory(text(required(object, "id"), "Software category ID"),
            text(required(object, "display"), "Software category display"));
    }

    private JsonValue family(SoftwareFamily family) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("categoryIds", JsonValue.array(family.categoryIds().stream().map(JsonValue::of).toList()));
        optional(fields, "color", family.color());
        fields.put("compatibility", JsonValue.array(family.compatibility().stream().map(JsonValue::of).toList()));
        fields.put("compatibleGameReleaseIds", JsonValue.array(family.compatibleGameReleaseIds().stream().map(JsonValue::of).toList()));
        fields.put("deprecated", JsonValue.of(family.deprecated()));
        optional(fields, "description", family.description());
        fields.put("display", JsonValue.of(family.display()));
        fields.put("experimental", JsonValue.of(family.experimental()));
        optional(fields, "homepage", family.homepage());
        optional(fields, "icon", family.icon());
        fields.put("id", JsonValue.of(family.id()));
        fields.put("versions", JsonValue.array(family.versions().stream().map(this::version).toList()));
        return JsonValue.object(fields);
    }

    private SoftwareFamily decodeFamily(JsonValue value) {
        JsonObject object = object(value, "Software family");
        CanonicalCodec.rejectUnknownFields(object, FAMILY_FIELDS);
        List<String> categories = texts(required(object, "categoryIds"), "Software family category IDs");
        List<String> compatibility = texts(required(object, "compatibility"), "Software family compatibility");
        List<String> compatible = texts(required(object, "compatibleGameReleaseIds"), "Compatible game release IDs");
        List<SoftwareVersion> versions = decodeList(required(object, "versions"), "Software versions", this::decodeVersion);
        SoftwareFamily family = new SoftwareFamily(text(required(object, "id"), "Software family ID"),
            text(required(object, "display"), "Software family display"),
            optionalText(object, "description"), optionalText(object, "homepage"), optionalText(object, "icon"),
            optionalText(object, "color"), bool(required(object, "deprecated"), "Software family deprecated flag"),
            bool(required(object, "experimental"), "Software family experimental flag"), categories, compatibility,
            compatible, versions);
        requireOrder(categories, family.categoryIds(), "Software family category IDs");
        requireOrder(compatibility, family.compatibility(), "Software family compatibility");
        requireOrder(compatible, family.compatibleGameReleaseIds(), "Compatible game release IDs");
        requireOrder(versions, family.versions(), "Software versions");
        return family;
    }

    private JsonValue version(SoftwareVersion version) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("buildCount", JsonValue.of(version.buildCount()));
        fields.put("builds", JsonValue.array(version.builds().stream().map(this::build).toList()));
        fields.put("createdAt", JsonValue.of(version.createdAt()));
        fields.put("display", JsonValue.of(version.display()));
        optional(fields, "gameReleaseId", version.gameReleaseId());
        fields.put("id", JsonValue.of(version.id()));
        fields.put("latestBuildDisplay", JsonValue.of(version.latestBuildDisplay()));
        fields.put("latestBuildId", JsonValue.of(version.latestBuildId()));
        fields.put("order", JsonValue.of(version.order()));
        fields.put("supported", JsonValue.of(version.supported()));
        return JsonValue.object(fields);
    }

    private SoftwareVersion decodeVersion(JsonValue value) {
        JsonObject object = object(value, "Software version");
        CanonicalCodec.rejectUnknownFields(object, VERSION_FIELDS);
        List<SoftwareBuild> builds = decodeList(required(object, "builds"), "Software builds", this::decodeBuild);
        SoftwareVersion version = new SoftwareVersion(
            text(required(object, "id"), "Software version ID"),
            text(required(object, "display"), "Software version display"),
            optionalText(object, "gameReleaseId"),
            bool(required(object, "supported"), "Software version supported flag"),
            text(required(object, "createdAt"), "Software version creation time"),
            integer(required(object, "order"), "Software version order"),
            text(required(object, "latestBuildId"), "Latest software build ID"),
            text(required(object, "latestBuildDisplay"), "Latest software build display"),
            integer(required(object, "buildCount"), "Software build count"), builds);
        requireOrder(builds, version.builds(), "Software builds");
        return version;
    }

    private JsonValue build(SoftwareBuild build) {
        return JsonValue.object(Map.of(
            "display", JsonValue.of(build.display()),
            "id", JsonValue.of(build.id()),
            "order", JsonValue.of(build.order())
        ));
    }

    private SoftwareBuild decodeBuild(JsonValue value) {
        JsonObject object = object(value, "Software build");
        CanonicalCodec.rejectUnknownFields(object, BUILD_FIELDS);
        return new SoftwareBuild(text(required(object, "id"), "Software build ID"),
            text(required(object, "display"), "Software build display"),
            integer(required(object, "order"), "Software build order"));
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

    private static void optional(Map<String, JsonValue> fields, String name, String value) {
        if (value != null) {
            fields.put(name, JsonValue.of(value));
        }
    }

    private static boolean bool(JsonValue value, String field) {
        if (!(value instanceof JsonBoolean bool)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return bool.value();
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

    private static List<String> texts(JsonValue value, String field) {
        JsonArray array = array(value, field);
        List<String> result = new ArrayList<>(array.values().size());
        for (JsonValue item : array.values()) {
            result.add(text(item, field + " entry"));
        }
        return result;
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
