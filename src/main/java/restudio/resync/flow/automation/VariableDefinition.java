package restudio.resync.flow.automation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;

public record VariableDefinition(String id, String name, String description, FlowTypeRef valueType, AutomationScope scope,
                                 boolean persistent, Object defaultValue) implements AutomationDefinition {
    private static final Gson GSON = new Gson();

    public VariableDefinition {
        id = requireId(id);
        name = text(name, id);
        description = text(description, "");
        valueType = valueType != null ? valueType.normalizedGenerics() : FlowTypeRef.simple("any");
        FlowDataType dataType = FlowDataType.fromString(valueType.getTypeId());
        if (!dataType.isResolved() || dataType == FlowDataType.ANY || dataType == FlowDataType.EXECUTION) {
            throw new IllegalArgumentException("Variable value type must be a known data type");
        }
        scope = scope != null ? scope : AutomationScope.FLOW;
    }

    public static VariableDefinition from(JsonObject json, String fallbackId) {
        JsonObject value = json != null ? json : new JsonObject();
        String id = string(value, "id", fallbackId);
        String type = string(value, "valueType", string(value, "type", "any"));
        Object defaultValue = value.has("defaultValue") && !value.get("defaultValue").isJsonNull()
            ? GSON.fromJson(value.get("defaultValue"), Object.class) : null;
        return new VariableDefinition(id, string(value, "name", string(value, "displayName", id)),
            string(value, "description", ""), FlowTypeRef.parse(type), AutomationScope.parse(string(value, "scope", "flow")),
            bool(value, "persistent", false), defaultValue);
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Variable definition ID is required");
        }
        return value.trim();
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static String text(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
