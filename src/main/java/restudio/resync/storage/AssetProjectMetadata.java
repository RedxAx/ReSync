package restudio.resync.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class AssetProjectMetadata {
    private final JsonObject document;
    private final String canonicalJson;
    private final String serializedJson;
    private final String hash;

    private AssetProjectMetadata(JsonObject document) {
        this(document, null);
    }

    private AssetProjectMetadata(JsonObject document, String serializedJson) {
        this.document = canonicalize(document).getAsJsonObject();
        this.canonicalJson = this.document.toString();
        this.serializedJson = serializedJson != null ? serializedJson : canonicalJson;
        this.hash = StorageSafety.sha256(this.serializedJson);
    }

    public static AssetProjectMetadata empty() {
        return new AssetProjectMetadata(new JsonObject());
    }

    public static AssetProjectMetadata parse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Asset project metadata must be a JSON object");
            }
            return new AssetProjectMetadata(parsed.getAsJsonObject(), json == null || json.isBlank() ? "{}" : json);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argumentFailure) {
                throw argumentFailure;
            }
            throw new IllegalArgumentException("Asset project metadata is invalid", failure);
        }
    }

    public static AssetProjectMetadata of(JsonObject document) {
        return new AssetProjectMetadata(Objects.requireNonNull(document, "document").deepCopy());
    }

    public JsonObject document() {
        return document.deepCopy();
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public String serializedJson() {
        return serializedJson;
    }

    public String canonicalHash() {
        return StorageSafety.sha256(canonicalJson);
    }

    public String hash() {
        return hash;
    }

    public AssetProjectMetadata apply(List<Delta> deltas) {
        List<Delta> safeDeltas = List.copyOf(Objects.requireNonNull(deltas, "deltas"));
        if (safeDeltas.isEmpty()) {
            return this;
        }
        Set<List<String>> paths = new HashSet<>();
        JsonObject updated = document.deepCopy();
        for (Delta delta : safeDeltas) {
            if (!paths.add(delta.path())) {
                throw new IllegalArgumentException("Asset project metadata has duplicate delta path: " + String.join("/", delta.path()));
            }
            apply(updated, delta);
        }
        AssetProjectMetadata result = new AssetProjectMetadata(updated);
        return result.canonicalJson.equals(canonicalJson) ? this : result;
    }

    private static void apply(JsonObject root, Delta delta) {
        JsonObject parent = root;
        for (int index = 0; index < delta.path().size() - 1; index++) {
            String segment = delta.path().get(index);
            JsonElement child = parent.get(segment);
            if (child == null || child.isJsonNull()) {
                if (delta.remove()) {
                    return;
                }
                JsonObject created = new JsonObject();
                parent.add(segment, created);
                parent = created;
                continue;
            }
            if (!child.isJsonObject()) {
                throw new IllegalArgumentException("Asset project metadata delta crosses a non-object field: " + String.join("/", delta.path()));
            }
            parent = child.getAsJsonObject();
        }
        String field = delta.path().getLast();
        if (delta.remove()) {
            parent.remove(field);
        } else {
            parent.add(field, delta.value().deepCopy());
        }
    }

    private static JsonElement canonicalize(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonObject()) {
            JsonObject canonical = new JsonObject();
            JsonObject source = value.getAsJsonObject();
            for (String key : new TreeSet<>(source.keySet())) {
                canonical.add(key, canonicalize(source.get(key)));
            }
            return canonical;
        }
        if (value.isJsonArray()) {
            JsonArray canonical = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                canonical.add(canonicalize(element));
            }
            return canonical;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            BigDecimal number = new BigDecimal(primitive.getAsString()).stripTrailingZeros();
            return new JsonPrimitive(number.signum() == 0 ? BigDecimal.ZERO : number);
        }
        if (primitive.isBoolean()) {
            return new JsonPrimitive(primitive.getAsBoolean());
        }
        return new JsonPrimitive(primitive.getAsString());
    }

    public record Delta(List<String> path, JsonElement value, boolean remove) {
        public Delta {
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.isEmpty() || path.stream().anyMatch(segment -> segment == null || segment.isBlank()
                || segment.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Asset project metadata delta path is required");
            }
            if (remove) {
                value = JsonNull.INSTANCE;
            } else {
                value = Objects.requireNonNull(value, "value").deepCopy();
            }
        }

        @Override
        public JsonElement value() {
            return value.deepCopy();
        }

        public static Delta set(List<String> path, JsonElement value) {
            return new Delta(path, value, false);
        }

        public static Delta remove(List<String> path) {
            return new Delta(path, JsonNull.INSTANCE, true);
        }
    }
}
