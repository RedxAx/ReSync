package restudio.resync.customization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class ResourceJson {
    private ResourceJson() {
    }

    public static String string(JsonObject object, String key, String fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    public static int integer(JsonObject object, String key, int fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsInt();
    }

    public static double decimal(JsonObject object, String key, double fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsDouble();
    }

    public static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return values;
        }
        JsonArray array = object.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    public static JsonObject object(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }
}
