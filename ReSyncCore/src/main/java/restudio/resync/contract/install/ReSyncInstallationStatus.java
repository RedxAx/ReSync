package restudio.resync.contract.install;

import restudio.resync.contract.canonical.JsonValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ReSyncInstallationStatus(int formatVersion, State state, String title, String summary,
                                       String detail, String actionLabel, String markerPath,
                                       boolean preservesLegacyData, String archivePath) {
    public static final int FORMAT_VERSION = 1;
    public static final String FILE_PATH = "plugins/.resync-installation-status.json";
    public static final String ARCHIVE_MARKER_PATH = "plugins/.resync-archive-legacy-and-start-fresh";

    public ReSyncInstallationStatus {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported ReSync installation status version");
        }
        state = Objects.requireNonNull(state, "Installation state is required");
        title = text(title, "title");
        summary = text(summary, "summary");
        detail = text(detail, "detail");
        actionLabel = optional(actionLabel);
        markerPath = optional(markerPath);
        archivePath = optional(archivePath);
        if (state == State.LEGACY_DATA_BLOCKED && (actionLabel.isBlank() || markerPath.isBlank())) {
            throw new IllegalArgumentException("Blocked legacy data requires a recovery action");
        }
    }

    public static ReSyncInstallationStatus legacyDataBlocked() {
        return new ReSyncInstallationStatus(FORMAT_VERSION, State.LEGACY_DATA_BLOCKED,
            "Old ReSync Data Needs Your Choice",
            "This ReSync version cannot safely convert the existing data.",
            "Nothing has been changed. Archive the old data and start with a clean ReSync workspace, or leave it untouched and return later.",
            "Archive Data And Start Fresh", ARCHIVE_MARKER_PATH, true, "");
    }

    public static ReSyncInstallationStatus legacyDataArchived(String archivePath) {
        return new ReSyncInstallationStatus(FORMAT_VERSION, State.LEGACY_DATA_ARCHIVED,
            "Old ReSync Data Was Archived",
            "ReSync started with a clean workspace.",
            "The previous data remains available in a read-only archive. Recreate any resources you still need in the new workspace.",
            "", "", true, archivePath);
    }

    public boolean blocksStartup() {
        return state == State.LEGACY_DATA_BLOCKED;
    }

    public String encode() {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        values.put("formatVersion", JsonValue.of(formatVersion));
        values.put("state", JsonValue.of(state.name()));
        values.put("title", JsonValue.of(title));
        values.put("summary", JsonValue.of(summary));
        values.put("detail", JsonValue.of(detail));
        values.put("actionLabel", JsonValue.of(actionLabel));
        values.put("markerPath", JsonValue.of(markerPath));
        values.put("preservesLegacyData", JsonValue.of(preservesLegacyData));
        values.put("archivePath", JsonValue.of(archivePath));
        return JsonValue.object(values).canonicalText();
    }

    public static ReSyncInstallationStatus decode(String content) {
        if (!(JsonValue.parse(Objects.requireNonNull(content, "Installation status is required").strip()) instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("ReSync installation status must be an object");
        }
        return new ReSyncInstallationStatus(
            integer(object, "formatVersion"),
            State.valueOf(string(object, "state")),
            string(object, "title"),
            string(object, "summary"),
            string(object, "detail"),
            string(object, "actionLabel"),
            string(object, "markerPath"),
            bool(object, "preservesLegacyData"),
            string(object, "archivePath")
        );
    }

    private static int integer(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Invalid ReSync installation status " + field);
        }
        BigDecimal decimal = number.value();
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid ReSync installation status " + field, exception);
        }
    }

    private static String string(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Invalid ReSync installation status " + field);
        }
        return string.value();
    }

    private static boolean bool(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonBoolean bool)) {
            throw new IllegalArgumentException("Invalid ReSync installation status " + field);
        }
        return bool.value();
    }

    private static String text(String value, String field) {
        String checked = optional(value);
        if (checked.isBlank()) {
            throw new IllegalArgumentException("ReSync installation status " + field + " is required");
        }
        return checked;
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }

    public enum State {
        LEGACY_DATA_BLOCKED,
        LEGACY_DATA_ARCHIVED
    }
}
