package restudio.resync.flow.type;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TypeDescriptorCodec implements CanonicalCodec<TypeDescriptor> {
    public static final int VERSION = 1;
    public static final TypeDescriptorCodec INSTANCE = new TypeDescriptorCodec();

    private static final String KIND = "type-descriptor";
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of("kind", "version", "id", "displayName", "expression",
        "literalSchema", "storageCodec", "networkCodec", "editor", "validators", "transportable", "persistable");
    private static final Set<String> CODEC_FIELDS = Set.of("id", "version", "deterministic", "preservesOpaque");

    private TypeDescriptorCodec() {
    }

    public static TypeDescriptorCodec instance() {
        return INSTANCE;
    }

    @Override
    public JsonValue.JsonObject encode(TypeDescriptor value) {
        Objects.requireNonNull(value, "Type descriptor is required");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", KIND);
        known.put("version", VERSION);
        known.put("id", TypeValueCodec.INSTANCE.encodeTypeReference(value.id()));
        known.put("displayName", value.displayName());
        known.put("expression", TypeValueCodec.INSTANCE.encodeType(value.expression()));
        known.put("literalSchema", value.literalSchema());
        known.put("storageCodec", encodeCodec(value.storageCodec()));
        known.put("networkCodec", encodeCodec(value.networkCodec()));
        known.put("editor", IdentityCodec.encode(value.editor()));
        known.put("validators", value.validators().stream().map(TypeValueCodec.INSTANCE::encodeTypeReference).toList());
        known.put("transportable", value.transportable());
        known.put("persistable", value.persistable());
        return object(known, value.unknown());
    }

    @Override
    public TypeDescriptor decode(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Type descriptor");
        if (!KIND.equals(text(required(object, "kind"), "typeDescriptor.kind"))) {
            throw new IllegalArgumentException("Unsupported type descriptor kind");
        }
        if (integer(required(object, "version"), "typeDescriptor.version") != VERSION) {
            throw new IllegalArgumentException("Unsupported type descriptor version");
        }
        TypeDescriptor decoded = new TypeDescriptor(
            TypeValueCodec.INSTANCE.decodeTypeReference(required(object, "id")),
            text(required(object, "displayName"), "typeDescriptor.displayName"),
            TypeValueCodec.INSTANCE.decodeType(required(object, "expression")),
            present(object, "literalSchema").toJava(),
            decodeCodec(required(object, "storageCodec"), "storageCodec"),
            decodeCodec(required(object, "networkCodec"), "networkCodec"),
            IdentityCodec.decodeReference(required(object, "editor"), CapabilityId::new),
            decodeReferences(required(object, "validators")),
            bool(required(object, "transportable"), "typeDescriptor.transportable"),
            bool(required(object, "persistable"), "typeDescriptor.persistable"),
            unknown(object, DESCRIPTOR_FIELDS));
        requireCanonical(encode(decoded), object, "Type descriptor");
        return decoded;
    }

    private static JsonValue.JsonObject encodeCodec(CodecDescriptor codec) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("id", TypeValueCodec.INSTANCE.encodeTypeReference(codec.id()));
        known.put("version", codec.version());
        known.put("deterministic", codec.deterministic());
        known.put("preservesOpaque", codec.preservesOpaque());
        return object(known, codec.unknown());
    }

    private static CodecDescriptor decodeCodec(JsonValue value, String name) {
        JsonValue.JsonObject object = requireObject(value, name);
        CodecDescriptor decoded = new CodecDescriptor(TypeValueCodec.INSTANCE.decodeTypeReference(required(object, "id")),
            positiveInteger(required(object, "version"), name + ".version"),
            bool(required(object, "deterministic"), name + ".deterministic"),
            bool(required(object, "preservesOpaque"), name + ".preservesOpaque"), unknown(object, CODEC_FIELDS));
        requireCanonical(encodeCodec(decoded), object, name);
        return decoded;
    }

    private static List<TypeReference> decodeReferences(JsonValue value) {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("typeDescriptor.validators must be an array");
        }
        List<TypeReference> result = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            result.add(TypeValueCodec.INSTANCE.decodeTypeReference(member));
        }
        return List.copyOf(result);
    }

    private static JsonValue.JsonObject object(Map<String, Object> known, Map<String, ?> unknown) {
        Map<String, JsonValue> knownValues = new LinkedHashMap<>();
        known.forEach((key, value) -> knownValues.put(key, JsonValue.fromJava(value)));
        Map<String, JsonValue> unknownValues = new LinkedHashMap<>();
        if (unknown != null) {
            unknown.forEach((key, value) -> unknownValues.put(key, JsonValue.fromJava(value)));
        }
        return CanonicalCodec.mergeKnownFields(knownValues, unknownValues);
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return IdentitySupport.unknown(values, "type descriptor unknown data");
    }

    private static JsonValue required(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            throw new IllegalArgumentException("Required type descriptor field is missing: " + field);
        }
        return value;
    }

    private static JsonValue present(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required type descriptor field is missing: " + field);
        }
        return value;
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static String text(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return string.value();
    }

    private static boolean bool(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return booleanValue.value();
    }

    private static int positiveInteger(JsonValue value, String name) {
        int result = integer(value, name);
        if (result < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }

    private static int integer(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        BigDecimal decimal = number.value();
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static void requireCanonical(JsonValue.JsonObject encoded, JsonValue.JsonObject input, String name) {
        if (!encoded.canonicalText().equals(input.canonicalText())) {
            throw new IllegalArgumentException(name + " is not in the exact canonical shape");
        }
    }
}
