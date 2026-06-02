package restudio.resync.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class AdvancementDisplayJson {
    private AdvancementDisplayJson() {
    }

    static JsonElement textComponent(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return new JsonObject();
        }
        if (value.isJsonObject() || value.isJsonArray()) {
            return value.deepCopy();
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            JsonObject component = new JsonObject();
            component.addProperty("text", value.getAsString());
            return component;
        }
        return value.deepCopy();
    }

    static String background(String background) {
        if (background == null || background.isBlank()) {
            return "";
        }
        String value = background.trim();
        String prefix = "minecraft:textures/gui/advancements/backgrounds/";
        if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            value = value.substring(prefix.length());
        }
        if (value.endsWith(".png")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.contains(":")) {
            return value;
        }
        return "minecraft:gui/advancements/backgrounds/" + value;
    }
}
