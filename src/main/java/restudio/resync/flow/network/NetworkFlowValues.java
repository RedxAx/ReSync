package restudio.resync.flow.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import restudio.resync.network.NetworkVariable;
import restudio.resync.network.NetworkVariableType;
import restudio.resync.network.NetworkVariableValues;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class NetworkFlowValues {
    private static final Gson GSON = new Gson();

    private NetworkFlowValues() {
    }

    public static NetworkVariableType type(String value) {
        if (value == null || value.isBlank()) {
            return NetworkVariableType.STRING;
        }
        try {
            return NetworkVariableType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Network Variable Type Is Invalid");
        }
    }

    public static byte[] encode(NetworkVariableType type, Object value) {
        return switch (type) {
            case BOOLEAN -> NetworkVariableValues.booleanValue(value instanceof Boolean booleanValue ? booleanValue : Boolean.parseBoolean(String.valueOf(value)));
            case INTEGER -> NetworkVariableValues.integerValue(value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)));
            case DECIMAL -> NetworkVariableValues.decimalValue(value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value)));
            case STRING -> NetworkVariableValues.textValue(value == null ? "" : String.valueOf(value));
            case JSON -> NetworkVariableValues.textValue(json(value));
            case UUID -> NetworkVariableValues.uuidValue(value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value)));
            case BYTES -> value instanceof byte[] bytes ? NetworkVariableValues.bytesValue(bytes) : Base64.getDecoder().decode(String.valueOf(value));
        };
    }

    public static Object decode(NetworkVariable variable) {
        return switch (variable.type()) {
            case BOOLEAN -> NetworkVariableValues.asBoolean(variable);
            case INTEGER -> NetworkVariableValues.asInteger(variable);
            case DECIMAL -> NetworkVariableValues.asDecimal(variable);
            case STRING -> NetworkVariableValues.asText(variable);
            case JSON -> GSON.fromJson(JsonParser.parseString(NetworkVariableValues.asText(variable)), Object.class);
            case UUID -> NetworkVariableValues.asUuid(variable);
            case BYTES -> Base64.getEncoder().encodeToString(variable.value());
        };
    }

    public static byte[] eventPayload(Object value) {
        return json(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String eventText(byte[] payload) {
        return new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8);
    }

    public static Object eventData(byte[] payload) {
        String text = eventText(payload);
        if (text.isBlank()) {
            return null;
        }
        try {
            return GSON.fromJson(JsonParser.parseString(text), Object.class);
        } catch (RuntimeException exception) {
            return text;
        }
    }

    private static String json(Object value) {
        if (value instanceof String text) {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                return GSON.toJson(parsed);
            } catch (RuntimeException ignored) {
                return GSON.toJson(text);
            }
        }
        return GSON.toJson(value);
    }
}
