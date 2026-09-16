package restudio.resync.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class RecipeMigrationReportContract {
    public static final String FILE_NAME = "recipe-schema-v1.json";
    public static final String REPORT_ID = "recipe-schema-v1";
    public static final String MIGRATION_ID = "recipe-schema-v1";
    public static final int FORMAT_VERSION = 1;
    public static final int SOURCE_SCHEMA_VERSION = 0;
    public static final int TARGET_SCHEMA_VERSION = 1;
    public static final List<String> FIELD_ORDER = List.of(
        "formatVersion",
        "reportId",
        "migrationId",
        "sourceSchemaVersion",
        "targetSchemaVersion",
        "inspected",
        "normalized",
        "unchanged",
        "contentHash",
        "selfHash");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private RecipeMigrationReportContract() {
    }

    public static Report create(int inspected, int normalized) {
        int unchanged = inspected - normalized;
        JsonObject content = json(FORMAT_VERSION, REPORT_ID, MIGRATION_ID,
            SOURCE_SCHEMA_VERSION, TARGET_SCHEMA_VERSION, inspected, normalized, unchanged, null, null);
        String contentHash = StorageSafety.sha256(content.toString());
        JsonObject withoutSelfHash = json(FORMAT_VERSION, REPORT_ID, MIGRATION_ID,
            SOURCE_SCHEMA_VERSION, TARGET_SCHEMA_VERSION, inspected, normalized, unchanged, contentHash, null);
        String selfHash = StorageSafety.sha256(withoutSelfHash.toString());
        return new Report(FORMAT_VERSION, REPORT_ID, MIGRATION_ID, SOURCE_SCHEMA_VERSION, TARGET_SCHEMA_VERSION,
            inspected, normalized, unchanged, contentHash, selfHash);
    }

    public static Report read(Path report) throws IOException {
        Path normalized = MigrationPaths.requirePath(report, "recipe migration report");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recipe migration report must be a regular non-symbolic-link file");
        }
        try {
            return parse(Files.readString(normalized, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Recipe migration report could not be parsed", exception);
        }
    }

    public static Report parse(String content) throws IOException {
        if (content == null || content.isEmpty()) {
            throw new IOException("Recipe migration report is empty");
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IOException("Recipe migration report must be a JSON object");
            }
            JsonObject object = parsed.getAsJsonObject();
            List<String> fields = new ArrayList<>();
            object.entrySet().forEach(entry -> fields.add(entry.getKey()));
            if (!FIELD_ORDER.equals(fields)) {
                throw new IOException("Recipe migration report fields are not canonical");
            }
            Report report = new Report(
                requiredInt(object, "formatVersion"),
                requiredString(object, "reportId"),
                requiredString(object, "migrationId"),
                requiredInt(object, "sourceSchemaVersion"),
                requiredInt(object, "targetSchemaVersion"),
                requiredInt(object, "inspected"),
                requiredInt(object, "normalized"),
                requiredInt(object, "unchanged"),
                requiredString(object, "contentHash"),
                requiredString(object, "selfHash"));
            if (!report.canonicalJson().equals(content)) {
                throw new IOException("Recipe migration report is not canonical JSON");
            }
            return report;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Recipe migration report has invalid values", exception);
        }
    }

    public record Report(int formatVersion, String reportId, String migrationId, int sourceSchemaVersion,
                         int targetSchemaVersion, int inspected, int normalized, int unchanged,
                         String contentHash, String selfHash) {
        public Report {
            if (formatVersion != FORMAT_VERSION || !REPORT_ID.equals(reportId) || !MIGRATION_ID.equals(migrationId)
                || sourceSchemaVersion != SOURCE_SCHEMA_VERSION || targetSchemaVersion != TARGET_SCHEMA_VERSION
                || inspected < 0 || normalized < 0 || unchanged < 0 || normalized > inspected
                || unchanged != inspected - normalized || !isHash(contentHash) || !isHash(selfHash)) {
                throw new IllegalArgumentException("Invalid recipe migration report");
            }
            JsonObject content = json(formatVersion, reportId, migrationId, sourceSchemaVersion, targetSchemaVersion,
                inspected, normalized, unchanged, null, null);
            String expectedContentHash = StorageSafety.sha256(content.toString());
            JsonObject withoutSelfHash = json(formatVersion, reportId, migrationId, sourceSchemaVersion,
                targetSchemaVersion, inspected, normalized, unchanged, contentHash, null);
            String expectedSelfHash = StorageSafety.sha256(withoutSelfHash.toString());
            if (!expectedContentHash.equals(contentHash) || !expectedSelfHash.equals(selfHash)) {
                throw new IllegalArgumentException("Recipe migration report hashes do not match");
            }
        }

        public String canonicalJson() {
            return json(formatVersion, reportId, migrationId, sourceSchemaVersion, targetSchemaVersion,
                inspected, normalized, unchanged, contentHash, selfHash).toString();
        }

    }

    private static JsonObject json(int formatVersion, String reportId, String migrationId, int sourceSchemaVersion,
                                   int targetSchemaVersion, int inspected, int normalized, int unchanged,
                                   String contentHash, String selfHash) {
        JsonObject object = new JsonObject();
        object.addProperty("formatVersion", formatVersion);
        object.addProperty("reportId", reportId);
        object.addProperty("migrationId", migrationId);
        object.addProperty("sourceSchemaVersion", sourceSchemaVersion);
        object.addProperty("targetSchemaVersion", targetSchemaVersion);
        object.addProperty("inspected", inspected);
        object.addProperty("normalized", normalized);
        object.addProperty("unchanged", unchanged);
        if (contentHash != null) {
            object.addProperty("contentHash", contentHash);
        }
        if (selfHash != null) {
            object.addProperty("selfHash", selfHash);
        }
        return object;
    }

    private static int requiredInt(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Recipe migration report is missing or invalid " + name);
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException exception) {
            throw new IOException("Recipe migration report has an invalid " + name, exception);
        }
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Recipe migration report is missing or invalid " + name);
        }
        return value.getAsString();
    }

    private static boolean isHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
}
