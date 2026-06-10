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
    public static final int CURRENT_FORMAT_VERSION = 2;
    private static final Gson GSON = new Gson();

    private AssetFileFormat() {
    }

    public static String withResourceType(String json, String type) {
        JsonObject object = object(json);
        if (object == null) {
            return json;
        }
        object.addProperty(RESOURCE_TYPE, type);
        object.addProperty(FORMAT_VERSION, CURRENT_FORMAT_VERSION);
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
}
