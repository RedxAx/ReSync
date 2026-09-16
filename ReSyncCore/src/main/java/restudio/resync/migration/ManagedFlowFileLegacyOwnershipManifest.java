package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManagedFlowFileLegacyOwnershipManifest {
    private static final Set<String> BODY_FIELDS = Set.of(
        "schema", "formatVersion", "owner", "sourceSnapshotId", "sourceInstallId", "entries");
    private static final Set<String> FULL_FIELDS = Set.of(
        "schema", "formatVersion", "owner", "sourceSnapshotId", "sourceInstallId", "entries", "manifestHash");
    private static final Set<String> ENTRY_FIELDS = Set.of("path", "kind", "size", "sha256");

    private final int formatVersion;
    private final String owner;
    private final ManagedFlowFileMigrationContract.SourceIdentity source;
    private final List<ManagedFlowFileMigrationContract.LegacyEntry> entries;
    private final String manifestHash;

    public ManagedFlowFileLegacyOwnershipManifest(
        ManagedFlowFileMigrationContract.SourceIdentity source,
        List<ManagedFlowFileMigrationContract.LegacyEntry> entries) {
        this(ManagedFlowFileMigrationContract.MANIFEST_FORMAT_VERSION,
            ManagedFlowFileStoreContract.OWNER, source, entries);
    }

    public ManagedFlowFileLegacyOwnershipManifest(
        int formatVersion,
        String owner,
        ManagedFlowFileMigrationContract.SourceIdentity source,
        List<ManagedFlowFileMigrationContract.LegacyEntry> entries) {
        if (formatVersion != ManagedFlowFileMigrationContract.MANIFEST_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported managed flow-file ownership manifest format");
        }
        this.formatVersion = formatVersion;
        this.owner = ManagedFlowFileMigrationContract.requireIdentity(owner, "owner");
        if (!ManagedFlowFileStoreContract.OWNER.equals(this.owner)) {
            throw new IllegalArgumentException("Managed flow-file ownership manifest owner is invalid");
        }
        this.source = Objects.requireNonNull(source, "source");
        List<ManagedFlowFileMigrationContract.LegacyEntry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        ManagedFlowFileMigrationContract.validateEntries(sorted);
        sorted.sort(ManagedFlowFileMigrationContract.legacyEntryComparator());
        this.entries = List.copyOf(sorted);
        this.manifestHash = ManagedFlowFileMigrationContract.sha256(body().canonicalBytes());
    }

    public int formatVersion() {
        return formatVersion;
    }

    public String owner() {
        return owner;
    }

    public ManagedFlowFileMigrationContract.SourceIdentity source() {
        return source;
    }

    public List<ManagedFlowFileMigrationContract.LegacyEntry> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public String manifestHash() {
        return manifestHash;
    }

    public JsonValue.JsonObject body() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("schema", JsonValue.of(ManagedFlowFileMigrationContract.MANIFEST_SCHEMA));
        fields.put("formatVersion", JsonValue.of(formatVersion));
        fields.put("owner", JsonValue.of(owner));
        fields.put("sourceSnapshotId", JsonValue.of(source.snapshotId()));
        fields.put("sourceInstallId", JsonValue.of(source.installId()));
        fields.put("entries", JsonValue.array(entries.stream().map(ManagedFlowFileLegacyOwnershipManifest::encodeEntry).toList()));
        return new JsonValue.JsonObject(fields);
    }

    public JsonValue.JsonObject value() {
        Map<String, JsonValue> fields = new LinkedHashMap<>(body().fields());
        fields.put("manifestHash", JsonValue.of(manifestHash));
        return new JsonValue.JsonObject(fields);
    }

    public byte[] canonicalBytes() {
        return value().canonicalBytes();
    }

    public String canonicalText() {
        return value().canonicalText();
    }

    public static ManagedFlowFileLegacyOwnershipManifest read(byte[] input) {
        Objects.requireNonNull(input, "input");
        JsonValue value = CanonicalCodec.decode(input);
        ManagedFlowFileLegacyOwnershipManifest manifest = decode(value);
        if (!java.util.Arrays.equals(input, manifest.canonicalBytes())) {
            throw new IllegalArgumentException("Managed flow-file ownership manifest is not canonical");
        }
        return manifest;
    }

    public static ManagedFlowFileLegacyOwnershipManifest read(String input) {
        Objects.requireNonNull(input, "input");
        return read(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ManagedFlowFileLegacyOwnershipManifest decode(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Managed flow-file ownership manifest");
        requireFields(object, FULL_FIELDS, "Managed flow-file ownership manifest");
        String schema = string(object, "schema");
        if (!ManagedFlowFileMigrationContract.MANIFEST_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Managed flow-file ownership manifest schema is invalid");
        }
        int formatVersion = integer(object, "formatVersion");
        String owner = string(object, "owner");
        String snapshotId = string(object, "sourceSnapshotId");
        String installId = string(object, "sourceInstallId");
        JsonValue.JsonArray encodedEntries = array(object, "entries");
        List<ManagedFlowFileMigrationContract.LegacyEntry> entries = new ArrayList<>();
        for (JsonValue encodedEntry : encodedEntries.values()) {
            JsonValue.JsonObject entry = requireObject(encodedEntry, "Managed flow-file ownership entry");
            requireFields(entry, ENTRY_FIELDS, "Managed flow-file ownership entry");
            entries.add(new ManagedFlowFileMigrationContract.LegacyEntry(
                string(entry, "path"),
                ManagedFlowFileMigrationContract.EntryKind.parse(string(entry, "kind")),
                longValue(entry, "size"),
                string(entry, "sha256")));
        }
        ManagedFlowFileLegacyOwnershipManifest manifest = new ManagedFlowFileLegacyOwnershipManifest(
            formatVersion, owner,
            new ManagedFlowFileMigrationContract.SourceIdentity(snapshotId, installId), entries);
        String storedHash = ManagedFlowFileMigrationContract.requireDigest(string(object, "manifestHash"), "manifestHash");
        if (!manifest.manifestHash.equals(storedHash)) {
            throw new IllegalArgumentException("Managed flow-file ownership manifest hash does not match content");
        }
        return manifest;
    }

    private static JsonValue.JsonObject encodeEntry(ManagedFlowFileMigrationContract.LegacyEntry entry) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("path", JsonValue.of(entry.logicalPath()));
        fields.put("kind", JsonValue.of(entry.kind().wireValue()));
        fields.put("size", JsonValue.of(entry.size()));
        fields.put("sha256", JsonValue.of(entry.sha256()));
        return new JsonValue.JsonObject(fields);
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String label) {
        if (!(Objects.requireNonNull(value, label) instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return object;
    }

    private static void requireFields(JsonValue.JsonObject object, Set<String> fields, String label) {
        if (!object.fields().keySet().equals(fields)) {
            throw new IllegalArgumentException(label + " contains unknown or missing fields");
        }
    }

    private static String string(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Managed flow-file manifest field must be a string: " + field);
        }
        return string.value();
    }

    private static JsonValue.JsonArray array(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Managed flow-file manifest field must be an array: " + field);
        }
        return array;
    }

    private static int integer(JsonValue.JsonObject object, String field) {
        try {
            return number(object, field).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Managed flow-file manifest field must be an integer: " + field, exception);
        }
    }

    private static long longValue(JsonValue.JsonObject object, String field) {
        try {
            return number(object, field).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Managed flow-file manifest field must be a long: " + field, exception);
        }
    }

    private static java.math.BigDecimal number(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Managed flow-file manifest field must be numeric: " + field);
        }
        return number.value();
    }
}
