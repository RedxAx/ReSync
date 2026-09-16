package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ManagedResourceFileContract {
    public static final String RESOURCE_TYPE = "resourceType";
    public static final String FORMAT_VERSION = "assetFormatVersion";
    public static final String REVISION = "assetRevision";
    public static final String CONTENT_HASH = "assetHash";
    public static final String MUTATION_ID = "assetMutationId";
    public static final int CURRENT_FORMAT_VERSION = 3;

    private ManagedResourceFileContract() {
    }

    public static byte[] encode(String type, String id, Map<String, ?> payload) {
        String resourceType = requireText(type, "resourceType");
        String resourceId = requireText(id, "id");
        LinkedHashMap<String, Object> value = copy(payload);
        value.putIfAbsent("id", resourceId);
        if (!resourceId.equals(text(value.get("id")))) {
            throw new IllegalArgumentException("Managed resource id does not match its file identity");
        }
        value.put(RESOURCE_TYPE, resourceType);
        value.put(FORMAT_VERSION, CURRENT_FORMAT_VERSION);
        value.put(REVISION, 1L);
        value.put(MUTATION_ID, mutationId(resourceType, resourceId, value));
        value.remove(CONTENT_HASH);
        value.put(CONTENT_HASH, sha256(gsonBytes(value)));
        return gsonBytes(value);
    }

    public static Map<String, Object> decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return object(CanonicalCodec.decodePermissive(bytes).toJava());
    }

    public static boolean matches(String type, String id, Map<String, ?> expected, Map<String, ?> actual) {
        String resourceType = requireText(type, "resourceType");
        String resourceId = requireText(id, "id");
        if (!isValid(resourceType, resourceId, actual)) {
            return false;
        }
        LinkedHashMap<String, Object> expectedValue = copy(expected);
        expectedValue.putIfAbsent("id", resourceId);
        return resourceId.equals(text(expectedValue.get("id")))
            && canonicalSemantic(expectedValue).equals(canonicalSemantic(actual));
    }

    public static boolean isValid(String type, String id, Map<String, ?> actual) {
        String resourceType = requireText(type, "resourceType");
        String resourceId = requireText(id, "id");
        if (!resourceType.equals(text(actual.get(RESOURCE_TYPE))) || !resourceId.equals(text(actual.get("id")))) {
            return false;
        }
        if (!(actual.get(FORMAT_VERSION) instanceof Number formatVersion)
            || formatVersion.longValue() != CURRENT_FORMAT_VERSION
            || !(actual.get(REVISION) instanceof Number revision) || revision.longValue() < 1L
            || !(actual.get(MUTATION_ID) instanceof String mutationId) || mutationId.isBlank()
            || !(actual.get(CONTENT_HASH) instanceof String contentHash) || !contentHash.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        LinkedHashMap<String, Object> withoutHash = copy(actual);
        withoutHash.remove(CONTENT_HASH);
        return contentHash.equalsIgnoreCase(sha256(gsonBytes(withoutHash)));
    }

    public static Map<String, Object> semantic(Map<String, ?> value) {
        LinkedHashMap<String, Object> copy = copy(value);
        copy.remove(RESOURCE_TYPE);
        copy.remove(FORMAT_VERSION);
        copy.remove(REVISION);
        copy.remove(CONTENT_HASH);
        copy.remove(MUTATION_ID);
        return copy;
    }

    private static String canonicalSemantic(Map<String, ?> value) {
        return JsonValue.fromJava(semantic(value)).canonicalText();
    }

    private static LinkedHashMap<String, Object> copy(Map<String, ?> value) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (value != null) {
            for (Map.Entry<String, ?> entry : value.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("Managed resource field name is invalid");
                }
                result.put(entry.getKey(), deepCopy(entry.getValue()));
            }
        }
        return result;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Managed resource object keys must be strings");
                }
                result.put(key, deepCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ManagedResourceFileContract::deepCopy).toList();
        }
        return value;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Managed resource file must contain an object");
        }
        LinkedHashMap<String, Object> valueMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Managed resource object keys must be strings");
            }
            valueMap.put(key, entry.getValue());
        }
        return copy(valueMap);
    }

    private static String mutationId(String type, String id, Map<String, ?> value) {
        String seed = "resync.managed-resource.generated\u0000" + type + '\u0000' + id + '\u0000' + canonicalSemantic(value);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static byte[] gsonBytes(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof Boolean booleanValue) {
            output.append(booleanValue ? "true" : "false");
        } else if (value instanceof Number number) {
            output.append(number);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ > 0) {
                    output.append(',');
                }
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Managed resource object keys must be strings");
                }
                writeString(key, output);
                output.append(':');
                write(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                write(list.get(index), output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported managed resource value: " + value.getClass().getName());
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                case '<' -> output.append("\\u003c");
                case '>' -> output.append("\\u003e");
                case '&' -> output.append("\\u0026");
                case '=' -> output.append("\\u003d");
                case '\'' -> output.append("\\u0027");
                case '\u2028' -> output.append("\\u2028");
                case '\u2029' -> output.append("\\u2029");
                default -> {
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return normalized;
    }

    private static String sha256(byte[] bytes) {
        return MigrationCanonical.sha256(bytes);
    }
}
