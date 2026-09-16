package restudio.resync.flow.graph;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExecutionTargetCodec implements CanonicalCodec<ExecutionTarget> {
    public static final ExecutionTargetCodec INSTANCE = new ExecutionTargetCodec();
    private static final Set<String> TARGET_FIELDS = Set.of("resource", "startNodeId", "expectedRevision", "expectedBinding");
    private static final Set<String> BINDING_FIELDS = Set.of("generation", "catalogChecksum", "bindingManifestHash");

    @Override
    public JsonValue.JsonObject encode(ExecutionTarget target) {
        Objects.requireNonNull(target, "Execution Target Is Required");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("resource", IdentityCodec.encode(target.resource()));
        fields.put("startNodeId", JsonValue.of(target.startNodeId().canonicalText()));
        fields.put("expectedRevision", JsonValue.of(target.expectedRevision()));
        fields.put("expectedBinding", encodeBinding(target.expectedBinding()));
        return JsonValue.object(fields);
    }

    @Override
    public ExecutionTarget decode(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Execution Target");
        rejectUnknown(object, TARGET_FIELDS, "Execution Target");
        ServerResourceLocator resource = IdentityCodec.decodeLocator(require(object, "resource"));
        NodeInstanceId startNodeId = NodeInstanceId.parseCanonicalText(text(object, "startNodeId"));
        long expectedRevision = integer(object, "expectedRevision");
        CatalogBinding expectedBinding = decodeBinding(require(object, "expectedBinding"));
        ExecutionTarget target = new ExecutionTarget(resource, startNodeId, expectedRevision, expectedBinding);
        if (!encode(target).equals(object)) {
            throw new IllegalArgumentException("Execution Target Is Not In The Exact Canonical Shape");
        }
        return target;
    }

    private static JsonValue.JsonObject encodeBinding(CatalogBinding binding) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("generation", JsonValue.of(binding.generation()));
        fields.put("catalogChecksum", JsonValue.of(binding.catalogChecksum().canonicalText()));
        fields.put("bindingManifestHash", JsonValue.of(binding.bindingManifestHash().canonicalText()));
        return JsonValue.object(fields);
    }

    private static CatalogBinding decodeBinding(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Execution Target Catalog Binding");
        rejectUnknown(object, BINDING_FIELDS, "Execution Target Catalog Binding");
        return new CatalogBinding(integer(object, "generation"),
            new ContentHash(text(object, "catalogChecksum")),
            new ContentHash(text(object, "bindingManifestHash")));
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " Must Be An Object");
        }
        return object;
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required Execution Target Field Is Missing: " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        if (!(require(object, field) instanceof JsonValue.JsonString value)) {
            throw new IllegalArgumentException("Execution Target Field Must Be Text: " + field);
        }
        return value.value();
    }

    private static long integer(JsonValue.JsonObject object, String field) {
        if (!(require(object, field) instanceof JsonValue.JsonNumber value)) {
            throw new IllegalArgumentException("Execution Target Field Must Be An Integer: " + field);
        }
        try {
            return value.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Execution Target Integer Is Out Of Range: " + field, exception);
        }
    }

    private static void rejectUnknown(JsonValue.JsonObject object, Set<String> fields, String name) {
        Set<String> unknown = object.fields().keySet().stream().filter(field -> !fields.contains(field)).collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(name + " Contains Unknown Fields: " + unknown);
        }
    }
}
