package restudio.resync.upgrade.command;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AssetFileIdentity {
    static final String RESOURCE_TYPE = "resourceType";
    static final String RESOURCE_REVISION = "resourceRevision";
    static final String RESOURCE_HASH = "resourceHash";
    static final String RESOURCE_MUTATION_ID = "resourceMutationId";
    static final String FORMAT_VERSION = "assetFormatVersion";
    static final String REVISION = "assetRevision";
    static final String CONTENT_HASH = "assetHash";
    static final String MUTATION_ID = "assetMutationId";
    static final int CURRENT_FORMAT_VERSION = 3;

    private AssetFileIdentity() {
    }

    static long nextRevision(JsonValue.JsonObject root) {
        validateExisting(root, "command");
        try {
            long current = root.contains(REVISION) ? number(root.value(REVISION), REVISION)
                : root.contains(RESOURCE_REVISION) ? number(root.value(RESOURCE_REVISION), RESOURCE_REVISION) : 0L;
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Asset revision overflow", exception);
        }
    }

    static String nextMutationId(JsonValue.JsonObject previous, RawGraphDocument next, String graphId, long revision) {
        validateExisting(previous, "command");
        String previousMutation = optionalText(previous.value(MUTATION_ID));
        if (previousMutation.isBlank()) {
            previousMutation = optionalText(previous.value(RESOURCE_MUTATION_ID));
        }
        String seed = "resync.command-binding.asset-mutation\u0000" + graphId + '\u0000'
            + previousMutation + '\u0000' + revision + '\u0000'
            + sha256(next.canonicalBytes());
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static JsonValue.JsonObject withResourceIdentity(JsonValue.JsonObject root, String type, long revision, String mutationId) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Asset resource type is required");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Asset revision cannot be negative");
        }
        if (mutationId == null || mutationId.isBlank()) {
            throw new IllegalArgumentException("Asset mutation ID is required");
        }
        Map<String, JsonValue> fields = orderedFields(root);
        fields.put(RESOURCE_TYPE, JsonValue.of(type));
        fields.put(RESOURCE_REVISION, JsonValue.of(revision));
        fields.put(RESOURCE_HASH, JsonValue.of(""));
        fields.put(RESOURCE_MUTATION_ID, JsonValue.of(mutationId));
        fields.put(FORMAT_VERSION, JsonValue.of(CURRENT_FORMAT_VERSION));
        fields.put(REVISION, JsonValue.of(revision));
        fields.put(MUTATION_ID, JsonValue.of(mutationId));
        fields.remove(CONTENT_HASH);
        JsonValue.JsonObject unsigned = JsonValue.object(fields);
        fields.put(CONTENT_HASH, JsonValue.of(sha256(bytes(unsigned))));
        return JsonValue.object(fields);
    }

    static long readRevision(Path file) throws IOException {
        return number(root(file).value(REVISION), REVISION);
    }

    static boolean verify(Path file) throws IOException {
        try {
            requireValid(root(file), "command");
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static byte[] bytes(JsonValue value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void requireValid(JsonValue.JsonObject root, String type) {
        validateExisting(root, type);
        if (!root.contains(FORMAT_VERSION) || number(root.value(FORMAT_VERSION), FORMAT_VERSION) != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported asset format version");
        }
        if (!root.contains(REVISION) || !root.contains(MUTATION_ID) || !root.contains(CONTENT_HASH)) {
            throw new IllegalArgumentException("Asset identity is incomplete");
        }
        String mutationId = text(root.value(MUTATION_ID), MUTATION_ID);
        if (mutationId.isBlank()) {
            throw new IllegalArgumentException("Asset mutation ID is required");
        }
        String declaredHash = text(root.value(CONTENT_HASH), CONTENT_HASH);
        if (!declaredHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Asset hash is missing or malformed");
        }
        Map<String, JsonValue> fields = new LinkedHashMap<>(root.fields());
        fields.remove(CONTENT_HASH);
        if (!declaredHash.equalsIgnoreCase(sha256(bytes(JsonValue.object(fields))))) {
            throw new IllegalArgumentException("Asset hash does not match content");
        }
    }

    static boolean isCurrent(JsonValue.JsonObject root, String type) {
        try {
            requireValid(root, type);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void validate(JsonValue.JsonObject root, String type) {
        validateExisting(root, type);
    }

    private static Map<String, JsonValue> orderedFields(JsonValue.JsonObject root) {
        List<String> runtimeFields = List.of("id", "enabled", "version", "nodes", "connections", "localVariables",
            "function", "functionOwner", "functionNamespace", "functionVersion", "functionDescription", "functionInputs",
            "functionOutputs", "editorPassthroughs", "contentProperties", RESOURCE_TYPE, RESOURCE_REVISION, RESOURCE_HASH,
            RESOURCE_MUTATION_ID);
        Set<String> known = new HashSet<>(runtimeFields);
        known.add(FORMAT_VERSION);
        known.add(REVISION);
        known.add(MUTATION_ID);
        known.add(CONTENT_HASH);
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        for (String key : runtimeFields) {
            JsonValue value = root.value(key);
            if (value != null) {
                fields.put(key, value);
            }
        }
        root.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                fields.put(key, value);
            }
        });
        return fields;
    }

    private static void validateExisting(JsonValue.JsonObject root, String type) {
        if (!type.equals(text(root.value(RESOURCE_TYPE), RESOURCE_TYPE))) {
            throw new IllegalArgumentException("Asset resource type does not match command graph");
        }
        if (root.contains(FORMAT_VERSION) && number(root.value(FORMAT_VERSION), FORMAT_VERSION) < 1) {
            throw new IllegalArgumentException("Asset format version must be positive");
        }
        long revision = root.contains(REVISION) ? number(root.value(REVISION), REVISION)
            : root.contains(RESOURCE_REVISION) ? number(root.value(RESOURCE_REVISION), RESOURCE_REVISION) : 0L;
        if (revision < 0) {
            throw new IllegalArgumentException("Asset revision cannot be negative");
        }
        if (root.contains(RESOURCE_REVISION) && number(root.value(RESOURCE_REVISION), RESOURCE_REVISION) != revision) {
            throw new IllegalArgumentException("Resource and asset revisions do not match");
        }
        String mutationId = optionalText(root.value(MUTATION_ID));
        String resourceMutationId = optionalText(root.value(RESOURCE_MUTATION_ID));
        if (!mutationId.isBlank() && !resourceMutationId.isBlank() && !mutationId.equals(resourceMutationId)) {
            throw new IllegalArgumentException("Resource and asset mutation IDs do not match");
        }
        if (root.contains(CONTENT_HASH)) {
            String declaredHash = text(root.value(CONTENT_HASH), CONTENT_HASH);
            if (!declaredHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Asset hash is missing or malformed");
            }
            Map<String, JsonValue> fields = new LinkedHashMap<>(root.fields());
            fields.remove(CONTENT_HASH);
            if (!declaredHash.equalsIgnoreCase(sha256(bytes(JsonValue.object(fields))))) {
                throw new IllegalArgumentException("Asset hash does not match content");
            }
        }
    }

    private static long number(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Asset " + field + " is missing or not an integer");
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Asset " + field + " is not an integer", exception);
        }
    }

    private static JsonValue.JsonObject root(Path file) throws IOException {
        JsonValue value = CanonicalCodec.decodePermissive(Files.readAllBytes(file));
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IOException("Asset root must be an object");
        }
        return object;
    }

    private static String text(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Asset " + field + " is missing or not text");
        }
        return string.value();
    }

    private static String optionalText(JsonValue value) {
        return value instanceof JsonValue.JsonString string ? string.value() : "";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void write(JsonValue value, StringBuilder output) {
        switch (value) {
            case JsonValue.JsonNull ignored -> output.append("null");
            case JsonValue.JsonBoolean booleanValue -> output.append(booleanValue.value() ? "true" : "false");
            case JsonValue.JsonNumber number -> output.append(number.value().toString());
            case JsonValue.JsonString string -> writeString(string.value(), output);
            case JsonValue.JsonArray array -> {
                output.append('[');
                for (int index = 0; index < array.values().size(); index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    write(array.values().get(index), output);
                }
                output.append(']');
            }
            case JsonValue.JsonObject object -> {
                output.append('{');
                int index = 0;
                for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
                    if (index++ > 0) {
                        output.append(',');
                    }
                    writeString(entry.getKey(), output);
                    output.append(':');
                    write(entry.getValue(), output);
                }
                output.append('}');
            }
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
                        output.append("\\u");
                        appendHex(output, current);
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void appendHex(StringBuilder output, char value) {
        String digits = Integer.toHexString(value);
        output.append("0000", 0, 4 - digits.length()).append(digits);
    }
}
