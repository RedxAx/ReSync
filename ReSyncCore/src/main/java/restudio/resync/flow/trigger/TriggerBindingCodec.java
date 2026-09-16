package restudio.resync.flow.trigger;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.ExecutionTargetCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TriggerBindingCodec implements CanonicalCodec<TriggerBindingDocument> {
    public static final TriggerBindingCodec INSTANCE = new TriggerBindingCodec(ExecutionTargetCodec.INSTANCE);
    private static final Set<String> DOCUMENT_FIELDS = Set.of("formatVersion", "serverId", "revision", "mutationId", "bindings");
    private static final Set<String> BINDING_FIELDS = Set.of("id", "route", "target");
    private static final Set<String> ROUTE_FIELDS = Set.of("kind", "source");
    private final CanonicalCodec<ExecutionTarget> targetCodec;

    public TriggerBindingCodec(CanonicalCodec<ExecutionTarget> targetCodec) {
        this.targetCodec = Objects.requireNonNull(targetCodec, "Execution Target Codec Is Required");
    }

    public static TriggerBindingCodec of(CanonicalCodec<ExecutionTarget> targetCodec) {
        return new TriggerBindingCodec(targetCodec);
    }

    @Override
    public JsonValue.JsonObject encode(TriggerBindingDocument document) {
        Objects.requireNonNull(document, "Trigger Binding Document Is Required");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("formatVersion", JsonValue.of(document.formatVersion()));
        fields.put("serverId", JsonValue.of(document.serverId().canonicalText()));
        fields.put("revision", JsonValue.of(document.revision()));
        fields.put("mutationId", JsonValue.of(document.mutationId().toString()));
        fields.put("bindings", JsonValue.array(document.bindings().stream().map(this::encodeBinding).toList()));
        return CanonicalCodec.mergeKnownFields(fields, json(document.unknown()));
    }

    @Override
    public TriggerBindingDocument decode(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Trigger Binding Document");
        int formatVersion = exactInt(object, "formatVersion");
        ServerId serverId = ServerId.parseCanonicalText(text(object, "serverId"));
        long revision = exactLong(object, "revision");
        UUID mutationId = uuid(text(object, "mutationId"), "Trigger Binding Mutation ID");
        List<TriggerBinding> bindings = array(object, "bindings").values().stream().map(this::decodeBinding).toList();
        TriggerBindingDocument document = new TriggerBindingDocument(formatVersion, serverId, revision, mutationId,
            bindings, OpaqueData.of(unknown(object, DOCUMENT_FIELDS)));
        requireExactShape(encode(document), object, "Trigger Binding Document");
        return document;
    }

    public JsonValue.JsonObject encodeBinding(TriggerBinding binding) {
        Objects.requireNonNull(binding, "Trigger Binding Is Required");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("id", JsonValue.of(binding.id().canonicalText()));
        fields.put("route", encodeRoute(binding.route()));
        fields.put("target", targetCodec.encode(binding.target()));
        return CanonicalCodec.mergeKnownFields(fields, json(binding.unknown()));
    }

    public TriggerBinding decodeBinding(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Trigger Binding");
        TriggerBindingId id = TriggerBindingId.parseCanonicalText(text(object, "id"));
        TriggerRoute route = decodeRoute(require(object, "route"));
        ExecutionTarget target = targetCodec.decode(require(object, "target"));
        TriggerBinding binding = new TriggerBinding(id, route, target, OpaqueData.of(unknown(object, BINDING_FIELDS)));
        requireExactShape(encodeBinding(binding), object, "Trigger Binding");
        return binding;
    }

    public JsonValue.JsonObject encodeRoute(TriggerRoute route) {
        Objects.requireNonNull(route, "Trigger Route Is Required");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("kind", JsonValue.of(route.kind().wireValue()));
        fields.put("source", IdentityCodec.encode(route.source()));
        return JsonValue.object(fields);
    }

    public TriggerRoute decodeRoute(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Trigger Route");
        rejectUnknown(object, ROUTE_FIELDS, "Trigger Route");
        TriggerKind kind = TriggerKind.fromWireValue(text(object, "kind"));
        ContractRef<NodeId> source = IdentityCodec.decodeReference(require(object, "source"), NodeId::new);
        TriggerRoute route = new TriggerRoute(kind, source);
        requireExactShape(encodeRoute(route), object, "Trigger Route");
        return route;
    }

    private static Map<String, JsonValue> json(OpaqueData unknown) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        unknown.fields().forEach((key, value) -> values.put(key, JsonValue.fromJava(value)));
        return values;
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!fields.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return values;
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " Must Be An Object");
        }
        return object;
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required Trigger Binding Field Is Missing: " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        if (!(require(object, field) instanceof JsonValue.JsonString value)) {
            throw new IllegalArgumentException("Trigger Binding Field Must Be Text: " + field);
        }
        return value.value();
    }

    private static JsonValue.JsonArray array(JsonValue.JsonObject object, String field) {
        if (!(require(object, field) instanceof JsonValue.JsonArray value)) {
            throw new IllegalArgumentException("Trigger Binding Field Must Be An Array: " + field);
        }
        return value;
    }

    private static long exactLong(JsonValue.JsonObject object, String field) {
        if (!(require(object, field) instanceof JsonValue.JsonNumber value)) {
            throw new IllegalArgumentException("Trigger Binding Field Must Be An Integer: " + field);
        }
        try {
            return value.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Trigger Binding Integer Is Out Of Range: " + field, exception);
        }
    }

    private static int exactInt(JsonValue.JsonObject object, String field) {
        try {
            return Math.toIntExact(exactLong(object, field));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Trigger Binding Integer Is Out Of Range: " + field, exception);
        }
    }

    private static UUID uuid(String value, String name) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException(name + " Must Be Canonical");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " Must Be A Canonical UUID", exception);
        }
    }

    private static void rejectUnknown(JsonValue.JsonObject object, Set<String> fields, String name) {
        Set<String> unknown = object.fields().keySet().stream().filter(field -> !fields.contains(field))
            .collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(name + " Contains Unknown Fields: " + unknown);
        }
    }

    private static void requireExactShape(JsonValue.JsonObject encoded, JsonValue.JsonObject supplied, String name) {
        if (!encoded.equals(supplied)) {
            throw new IllegalArgumentException(name + " Is Not In The Exact Canonical Shape");
        }
    }
}
