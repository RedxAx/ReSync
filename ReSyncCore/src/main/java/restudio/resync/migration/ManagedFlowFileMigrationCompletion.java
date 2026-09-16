package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManagedFlowFileMigrationCompletion {
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
        "schema", "formatVersion", "migrationVersion", "migrationId", "manifestHash",
        "database", "entryCount", "logicalContentHash", "sourceSnapshotId", "sourceInstallId",
        "sourceArchiveIdentity", "targetInstallIdentity");
    private static final Set<String> BODY_FIELDS = Set.of(
        "schema", "formatVersion", "migrationVersion", "migrationId", "manifestHash",
        "database", "entryCount", "logicalContentHash", "physicalDatabaseHash",
        "sourceSnapshotId", "sourceInstallId", "sourceArchiveIdentity", "targetInstallIdentity",
        "completionAuthorityHash");
    private static final Set<String> FULL_FIELDS = Set.of(
        "schema", "formatVersion", "migrationVersion", "migrationId", "manifestHash",
        "database", "entryCount", "logicalContentHash", "physicalDatabaseHash",
        "sourceSnapshotId", "sourceInstallId", "sourceArchiveIdentity", "targetInstallIdentity",
        "completionAuthorityHash", "completionHash");
    private static final Set<String> DATABASE_FIELDS = Set.of(
        "schemaId", "formatVersion", "writerId", "writerVersion", "contractVersion");

    private final int formatVersion;
    private final int migrationVersion;
    private final String migrationId;
    private final String manifestHash;
    private final ManagedFlowFileMigrationContract.DatabaseIdentity database;
    private final int entryCount;
    private final String logicalContentHash;
    private final String physicalDatabaseHash;
    private final ManagedFlowFileMigrationContract.SourceIdentity source;
    private final String sourceArchiveIdentity;
    private final String targetInstallIdentity;
    private final String completionAuthorityHash;
    private final String completionHash;

    public ManagedFlowFileMigrationCompletion(
        int migrationVersion,
        String migrationId,
        String manifestHash,
        ManagedFlowFileMigrationContract.DatabaseIdentity database,
        int entryCount,
        String logicalContentHash,
        String physicalDatabaseHash,
        ManagedFlowFileMigrationContract.SourceIdentity source,
        String sourceArchiveIdentity,
        String targetInstallIdentity) {
        this(ManagedFlowFileMigrationContract.COMPLETION_FORMAT_VERSION, migrationVersion, migrationId, manifestHash,
            database, entryCount, logicalContentHash, physicalDatabaseHash, source, sourceArchiveIdentity,
            targetInstallIdentity);
    }

    public ManagedFlowFileMigrationCompletion(
        int formatVersion,
        int migrationVersion,
        String migrationId,
        String manifestHash,
        ManagedFlowFileMigrationContract.DatabaseIdentity database,
        int entryCount,
        String logicalContentHash,
        String physicalDatabaseHash,
        ManagedFlowFileMigrationContract.SourceIdentity source,
        String sourceArchiveIdentity,
        String targetInstallIdentity) {
        if (formatVersion != ManagedFlowFileMigrationContract.COMPLETION_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported managed flow-file migration completion format");
        }
        if (migrationVersion < 1) {
            throw new IllegalArgumentException("Managed flow-file migration version must be positive");
        }
        this.formatVersion = formatVersion;
        this.migrationVersion = migrationVersion;
        this.migrationId = ManagedFlowFileMigrationContract.requireIdentity(migrationId, "migrationId");
        this.manifestHash = ManagedFlowFileMigrationContract.requireDigest(manifestHash, "manifestHash");
        this.database = Objects.requireNonNull(database, "database");
        if (entryCount < 0) {
            throw new IllegalArgumentException("Managed flow-file migration entry count must not be negative");
        }
        this.entryCount = entryCount;
        this.logicalContentHash = ManagedFlowFileMigrationContract.requireDigest(logicalContentHash, "logicalContentHash");
        this.physicalDatabaseHash = ManagedFlowFileMigrationContract.requireDigest(physicalDatabaseHash, "physicalDatabaseHash");
        this.source = Objects.requireNonNull(source, "source");
        this.sourceArchiveIdentity = ManagedFlowFileMigrationContract.requireIdentity(sourceArchiveIdentity, "sourceArchiveIdentity");
        this.targetInstallIdentity = ManagedFlowFileMigrationContract.requireIdentity(targetInstallIdentity, "targetInstallIdentity");
        this.completionAuthorityHash = ManagedFlowFileMigrationContract.sha256(authorityBody().canonicalBytes());
        this.completionHash = ManagedFlowFileMigrationContract.sha256(body().canonicalBytes());
    }

    public int formatVersion() {
        return formatVersion;
    }

    public int migrationVersion() {
        return migrationVersion;
    }

    public String migrationId() {
        return migrationId;
    }

    public String manifestHash() {
        return manifestHash;
    }

    public ManagedFlowFileMigrationContract.DatabaseIdentity database() {
        return database;
    }

    public int entryCount() {
        return entryCount;
    }

    public String logicalContentHash() {
        return logicalContentHash;
    }

    public String physicalDatabaseHash() {
        return physicalDatabaseHash;
    }

    public ManagedFlowFileMigrationContract.SourceIdentity source() {
        return source;
    }

    public String sourceArchiveIdentity() {
        return sourceArchiveIdentity;
    }

    public String targetInstallIdentity() {
        return targetInstallIdentity;
    }

    public String completionAuthorityHash() {
        return completionAuthorityHash;
    }

    public String completionHash() {
        return completionHash;
    }

    public JsonValue.JsonObject authorityBody() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("schema", JsonValue.of(ManagedFlowFileMigrationContract.COMPLETION_SCHEMA));
        fields.put("formatVersion", JsonValue.of(formatVersion));
        fields.put("migrationVersion", JsonValue.of(migrationVersion));
        fields.put("migrationId", JsonValue.of(migrationId));
        fields.put("manifestHash", JsonValue.of(manifestHash));
        fields.put("database", databaseValue());
        fields.put("entryCount", JsonValue.of(entryCount));
        fields.put("logicalContentHash", JsonValue.of(logicalContentHash));
        fields.put("sourceSnapshotId", JsonValue.of(source.snapshotId()));
        fields.put("sourceInstallId", JsonValue.of(source.installId()));
        fields.put("sourceArchiveIdentity", JsonValue.of(sourceArchiveIdentity));
        fields.put("targetInstallIdentity", JsonValue.of(targetInstallIdentity));
        return new JsonValue.JsonObject(fields);
    }

    public JsonValue.JsonObject body() {
        Map<String, JsonValue> fields = new LinkedHashMap<>(authorityBody().fields());
        fields.put("physicalDatabaseHash", JsonValue.of(physicalDatabaseHash));
        fields.put("completionAuthorityHash", JsonValue.of(completionAuthorityHash));
        return new JsonValue.JsonObject(fields);
    }

    public JsonValue.JsonObject value() {
        Map<String, JsonValue> fields = new LinkedHashMap<>(body().fields());
        fields.put("completionHash", JsonValue.of(completionHash));
        return new JsonValue.JsonObject(fields);
    }

    public byte[] canonicalBytes() {
        return value().canonicalBytes();
    }

    public String canonicalText() {
        return value().canonicalText();
    }

    public boolean matches(ManagedFlowFileLegacyOwnershipManifest manifest,
                           ManagedFlowFileMigrationContract.StoreMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING
            ? matchesPending(manifest, metadata)
            : matchesAdmitted(manifest, metadata);
    }

    public boolean matchesPending(ManagedFlowFileLegacyOwnershipManifest manifest,
                                  ManagedFlowFileMigrationContract.StoreMetadata metadata) {
        return immutableMatches(manifest, metadata)
            && metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING
            && targetInstallIdentity.equals(metadata.installIdentity());
    }

    public boolean matchesAdmitted(ManagedFlowFileLegacyOwnershipManifest manifest,
                                   ManagedFlowFileMigrationContract.StoreMetadata metadata) {
        return immutableMatches(manifest, metadata)
            && metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED
            && targetInstallIdentity.equals(metadata.installIdentity())
            && migrationId.equals(metadata.migrationId())
            && completionAuthorityHash.equals(metadata.completionAuthorityHash())
            && completionHash.equals(metadata.completionHash());
    }

    private boolean immutableMatches(ManagedFlowFileLegacyOwnershipManifest manifest,
                                     ManagedFlowFileMigrationContract.StoreMetadata metadata) {
        Objects.requireNonNull(manifest, "manifest");
        return manifest.manifestHash().equals(manifestHash)
            && manifest.source().equals(source)
            && manifest.source().equals(metadata.source())
            && sourceArchiveIdentity.equals(metadata.sourceArchiveIdentity())
            && migrationId.equals(metadata.migrationId())
            && metadata.databaseIdentity().equals(database)
            && manifest.entryCount() == entryCount
            && ManagedFlowFileMigrationContract.sha256(authorityBody().canonicalBytes()).equals(completionAuthorityHash);
    }

    public static ManagedFlowFileMigrationCompletion read(byte[] input) {
        Objects.requireNonNull(input, "input");
        ManagedFlowFileMigrationCompletion completion = decode(CanonicalCodec.decode(input));
        if (!java.util.Arrays.equals(input, completion.canonicalBytes())) {
            throw new IllegalArgumentException("Managed flow-file migration completion is not canonical");
        }
        return completion;
    }

    public static ManagedFlowFileMigrationCompletion read(String input) {
        Objects.requireNonNull(input, "input");
        return read(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ManagedFlowFileMigrationCompletion decode(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Managed flow-file migration completion");
        requireFields(object, FULL_FIELDS, "Managed flow-file migration completion");
        if (!ManagedFlowFileMigrationContract.COMPLETION_SCHEMA.equals(string(object, "schema"))) {
            throw new IllegalArgumentException("Managed flow-file migration completion schema is invalid");
        }
        JsonValue.JsonObject databaseObject = requireObject(object.value("database"), "Managed flow-file database identity");
        requireFields(databaseObject, DATABASE_FIELDS, "Managed flow-file database identity");
        ManagedFlowFileMigrationContract.DatabaseIdentity database = new ManagedFlowFileMigrationContract.DatabaseIdentity(
            string(databaseObject, "schemaId"), integer(databaseObject, "formatVersion"), string(databaseObject, "writerId"),
            integer(databaseObject, "writerVersion"), integer(databaseObject, "contractVersion"));
        ManagedFlowFileMigrationCompletion completion = new ManagedFlowFileMigrationCompletion(
            integer(object, "formatVersion"), integer(object, "migrationVersion"), string(object, "migrationId"),
            string(object, "manifestHash"), database, integer(object, "entryCount"),
            string(object, "logicalContentHash"), string(object, "physicalDatabaseHash"),
            new ManagedFlowFileMigrationContract.SourceIdentity(string(object, "sourceSnapshotId"), string(object, "sourceInstallId")),
            string(object, "sourceArchiveIdentity"), string(object, "targetInstallIdentity"));
        String storedAuthority = ManagedFlowFileMigrationContract.requireDigest(
            string(object, "completionAuthorityHash"), "completionAuthorityHash");
        if (!completion.completionAuthorityHash.equals(storedAuthority)) {
            throw new IllegalArgumentException("Managed flow-file migration authority hash does not match content");
        }
        String storedHash = ManagedFlowFileMigrationContract.requireDigest(string(object, "completionHash"), "completionHash");
        if (!completion.completionHash.equals(storedHash)) {
            throw new IllegalArgumentException("Managed flow-file migration completion hash does not match content");
        }
        return completion;
    }

    private JsonValue.JsonObject databaseValue() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("schemaId", JsonValue.of(database.schemaId()));
        fields.put("formatVersion", JsonValue.of(database.formatVersion()));
        fields.put("writerId", JsonValue.of(database.writerId()));
        fields.put("writerVersion", JsonValue.of(database.writerVersion()));
        fields.put("contractVersion", JsonValue.of(database.contractVersion()));
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
            throw new IllegalArgumentException("Managed flow-file completion field must be a string: " + field);
        }
        return string.value();
    }

    private static int integer(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Managed flow-file completion field must be numeric: " + field);
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Managed flow-file completion field must be an integer: " + field, exception);
        }
    }
}
