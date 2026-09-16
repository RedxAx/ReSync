package restudio.resync.modules.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import restudio.resync.contract.canonical.JsonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GsonJsonValues {
    private GsonJsonValues() {
    }

    static JsonValue convert(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonValue.nullValue();
        }
        if (element.isJsonObject()) {
            Map<String, JsonValue> values = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet().forEach(entry -> values.put(entry.getKey(), convert(entry.getValue())));
            return JsonValue.object(values);
        }
        if (element.isJsonArray()) {
            List<JsonValue> values = new ArrayList<>(element.getAsJsonArray().size());
            element.getAsJsonArray().forEach(value -> values.add(convert(value)));
            return JsonValue.array(values);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return JsonValue.of(primitive.getAsBoolean());
        }
        if (primitive.isString()) {
            return JsonValue.of(primitive.getAsString());
        }
        if (primitive.isNumber()) {
            try {
                return JsonValue.of(new BigDecimal(primitive.getAsString()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Canonical JSON number is invalid", exception);
            }
        }
        throw new IllegalArgumentException("Unsupported JSON primitive");
    }
}
