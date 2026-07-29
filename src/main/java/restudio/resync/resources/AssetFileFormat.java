package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AssetFileFormat {
    public static final String RESOURCE_TYPE = "resourceType";
    public static final String FORMAT_VERSION = "assetFormatVersion";
    public static final String REVISION = "assetRevision";
    public static final String CONTENT_HASH = "assetHash";
    public static final String MUTATION_ID = "assetMutationId";
    public static final int CURRENT_FORMAT_VERSION = 3;
    private static final Gson GSON = new Gson();

    private AssetFileFormat() {
    }

    public static String withResourceType(String json, String type) {
        JsonObject object = object(json);
        if (object == null) {
            return json;
        }
        long revision = number(object, REVISION, 0L);
        String mutationId = text(object, MUTATION_ID);
        return withResourceIdentity(object, type, revision, mutationId);
    }

    public static String withResourceIdentity(String json, String type, long revision, String mutationId) {
        JsonObject object = object(json);
        if (object == null) {
            return json;
        }
        return withResourceIdentity(object, type, revision, mutationId);
    }

    private static String withResourceIdentity(JsonObject object, String type, long revision, String mutationId) {
        object.addProperty(RESOURCE_TYPE, type);
        object.addProperty(FORMAT_VERSION, CURRENT_FORMAT_VERSION);
        object.addProperty(REVISION, Math.max(0L, revision));
        object.addProperty(MUTATION_ID, mutationId != null ? mutationId : "");
        object.remove(CONTENT_HASH);
        object.addProperty(CONTENT_HASH, StorageSafety.sha256(GSON.toJson(object)));
        return GSON.toJson(object);
    }

    public static String readResourceType(Path file) {
        JsonObject object = readObject(file);
        if (object == null) {
            return "";
        }
        return text(object, RESOURCE_TYPE);
    }

    public static boolean declaresResourceType(Path file, String type) {
        String resourceType = readResourceType(file);
        return type != null && type.equals(resourceType);
    }

    public static long readRevision(Path file) {
        JsonObject object = readObject(file);
        return number(object, REVISION, 0L);
    }

    public static String readContentHash(Path file) {
        return text(readObject(file), CONTENT_HASH);
    }

    public static String readMutationId(Path file) {
        return text(readObject(file), MUTATION_ID);
    }

    public static String contentHash(String json) {
        return text(object(json), CONTENT_HASH);
    }

    public static boolean verify(Path file) {
        JsonObject object = readObject(file);
        if (object == null) {
            return false;
        }
        String expected = text(object, CONTENT_HASH);
        if (expected.isBlank()) {
            return true;
        }
        object.remove(CONTENT_HASH);
        return expected.equals(StorageSafety.sha256(GSON.toJson(object)));
    }

    public static String idOnlyFileName(String id) {
        return id + ".json";
    }

    public static boolean isIdOnlyFileName(String fileName) {
        return fileName != null && fileName.endsWith(".json") && !fileName.contains("__");
    }

    public static String idFromIdOnlyFileName(String fileName) {
        if (!isIdOnlyFileName(fileName)) {
            return "";
        }
        return fileName.substring(0, fileName.length() - 5);
    }

    public static boolean needsRewrite(Path file, String type) {
        String fileName = file != null && file.getFileName() != null ? file.getFileName().toString() : "";
        return !isIdOnlyFileName(fileName) || !declaresResourceType(file, type);
    }

    public static void rewriteTyped(Path source, Path target, String type) throws IOException {
        String json = withResourceType(StorageSafety.readUtf8(source), type);
        StorageSafety.writeUtf8Atomic(target, json);
        if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            Files.deleteIfExists(source);
        }
    }

    public static void copyTyped(Path source, Path target, String type) throws IOException {
        StorageSafety.writeUtf8Atomic(target, withResourceType(StorageSafety.readUtf8(source), type));
    }

    public static String typedConflictFolder(String folder, String type) {
        String normalized = folder != null ? folder.replace('\\', '/').replaceAll("/+", "/").trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String suffix = type == null || type.isBlank() ? "unknown" : type;
        return normalized.isBlank() ? suffix : normalized + "/" + suffix;
    }

    private static JsonObject readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return object(StorageSafety.readUtf8(file));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static JsonObject object(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
