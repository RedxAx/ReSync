package restudio.resync.flow.resource;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.type.TypeValueCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResourceManagementDescriptorCodec implements CanonicalCodec<ResourceManagementDescriptor> {
    public static final int VERSION = 1;
    public static final ResourceManagementDescriptorCodec INSTANCE = new ResourceManagementDescriptorCodec();

    private static final String KIND = "resource-management-descriptor";
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of("kind", "version", "resourceType", "payloadType",
        "availability", "operations", "serverAuthoring");
    private static final Set<String> AVAILABILITY_FIELDS = Set.of("state", "reason");
    private static final Set<String> OPERATION_FIELDS = Set.of("operation", "state", "reason");
    private static final Set<String> AUTHORING_FIELDS = Set.of("kind", "version", "capability", "inputs");
    private static final Set<String> INPUT_FIELDS = Set.of("id", "type", "required", "editor", "default");

    private ResourceManagementDescriptorCodec() {
    }

    public static ResourceManagementDescriptorCodec instance() {
        return INSTANCE;
    }

    @Override
    public JsonValue.JsonObject encode(ResourceManagementDescriptor value) {
        Objects.requireNonNull(value, "Resource management descriptor is required");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", KIND);
        known.put("version", value.schemaVersion());
        known.put("resourceType", IdentityCodec.encode(value.resourceType()));
        known.put("payloadType", TypeValueCodec.INSTANCE.encodeTypeReference(value.payloadType()));
        known.put("availability", encodeAvailability(value.availability()));
        known.put("operations", value.operations().stream().map(ResourceManagementDescriptorCodec::encodeOperation).toList());
        if (value.serverAuthoring() != null) {
            known.put("serverAuthoring", encodeAuthoring(value.serverAuthoring()));
        }
        return object(known, value.unknown());
    }

    @Override
    public ResourceManagementDescriptor decode(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Resource management descriptor");
        if (!KIND.equals(text(required(object, "kind"), "management.kind"))) {
            throw new IllegalArgumentException("Unsupported resource management descriptor kind");
        }
        int version = integer(required(object, "version"), "management.version");
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported resource management descriptor version");
        }
        ResourceManagementDescriptor decoded = new ResourceManagementDescriptor(version,
            IdentityCodec.decodeReference(required(object, "resourceType"), ResourceTypeId::new),
            TypeValueCodec.INSTANCE.decodeTypeReference(required(object, "payloadType")),
            decodeAvailability(required(object, "availability")),
            decodeOperations(required(object, "operations")),
            object.contains("serverAuthoring") ? decodeAuthoring(required(object, "serverAuthoring")) : null,
            unknown(object, DESCRIPTOR_FIELDS));
        requireCanonical(encode(decoded), object, "Resource management descriptor");
        return decoded;
    }

    private static JsonValue.JsonObject encodeAvailability(ResourceManagementDescriptor.Availability value) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("state", value.state().wireName());
        if (value.reason() != null) {
            known.put("reason", value.reason());
        }
        return object(known, value.unknown());
    }

    private static ResourceManagementDescriptor.Availability decodeAvailability(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Resource availability");
        ResourceManagementDescriptor.Availability decoded = new ResourceManagementDescriptor.Availability(
            ResourceManagementDescriptor.AvailabilityState.fromWireName(text(required(object, "state"), "availability.state")),
            optionalText(object, "reason").orElse(null), unknown(object, AVAILABILITY_FIELDS));
        requireCanonical(encodeAvailability(decoded), object, "Resource availability");
        return decoded;
    }

    private static JsonValue.JsonObject encodeOperation(ResourceManagementDescriptor.Operation value) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("operation", operationWireName(value.operation()));
        known.put("state", value.state().wireName());
        if (value.reason() != null) {
            known.put("reason", value.reason());
        }
        return object(known, value.unknown());
    }

    private static List<ResourceManagementDescriptor.Operation> decodeOperations(JsonValue value) {
        JsonValue.JsonArray array = requireArray(value, "Resource operations");
        List<ResourceManagementDescriptor.Operation> result = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            JsonValue.JsonObject object = requireObject(member, "Resource operation");
            ResourceManagementDescriptor.Operation decoded = new ResourceManagementDescriptor.Operation(
                parseOperation(text(required(object, "operation"), "operation.operation")),
                ResourceManagementDescriptor.OperationState.fromWireName(text(required(object, "state"), "operation.state")),
                optionalText(object, "reason").orElse(null), unknown(object, OPERATION_FIELDS));
            requireCanonical(encodeOperation(decoded), object, "Resource operation");
            result.add(decoded);
        }
        return List.copyOf(result);
    }

    private static JsonValue.JsonObject encodeAuthoring(ResourceManagementDescriptor.ServerAuthoring value) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", value.kind());
        known.put("version", value.version());
        known.put("capability", IdentityCodec.encode(value.capability()));
        known.put("inputs", value.inputs().stream().map(ResourceManagementDescriptorCodec::encodeInput).toList());
        return object(known, value.unknown());
    }

    private static ResourceManagementDescriptor.ServerAuthoring decodeAuthoring(JsonValue value) {
        JsonValue.JsonObject object = requireObject(value, "Server authoring");
        ResourceManagementDescriptor.ServerAuthoring decoded = new ResourceManagementDescriptor.ServerAuthoring(
            text(required(object, "kind"), "serverAuthoring.kind"),
            integer(required(object, "version"), "serverAuthoring.version"),
            IdentityCodec.decodeReference(required(object, "capability"), CapabilityId::new),
            decodeInputs(required(object, "inputs")), unknown(object, AUTHORING_FIELDS));
        requireCanonical(encodeAuthoring(decoded), object, "Server authoring");
        return decoded;
    }

    private static JsonValue.JsonObject encodeInput(ResourceManagementDescriptor.Input value) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("id", value.id().canonicalText());
        known.put("type", TypeValueCodec.INSTANCE.encodeType(value.type()));
        known.put("required", value.required());
        known.put("editor", IdentityCodec.encode(value.editor()));
        if (value.defaultValue() != null) {
            known.put("default", TypeValueCodec.INSTANCE.encode(value.defaultValue()));
        }
        return object(known, value.unknown());
    }

    private static List<ResourceManagementDescriptor.Input> decodeInputs(JsonValue value) {
        JsonValue.JsonArray array = requireArray(value, "Server authoring inputs");
        if (array.values().size() > ResourceManagementDescriptor.MAX_INPUTS) {
            throw new IllegalArgumentException("Server authoring contains too many inputs");
        }
        List<ResourceManagementDescriptor.Input> result = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            JsonValue.JsonObject object = requireObject(member, "Server authoring input");
            ResourceManagementDescriptor.Input decoded = new ResourceManagementDescriptor.Input(
                new InspectorFieldId(text(required(object, "id"), "input.id")),
                TypeValueCodec.INSTANCE.decodeType(required(object, "type")),
                bool(required(object, "required"), "input.required"),
                IdentityCodec.decodeReference(required(object, "editor"), CapabilityId::new),
                object.contains("default") ? TypeValueCodec.INSTANCE.decode(required(object, "default")) : null,
                unknown(object, INPUT_FIELDS));
            requireCanonical(encodeInput(decoded), object, "Server authoring input");
            result.add(decoded);
        }
        return List.copyOf(result);
    }

    private static String operationWireName(ResourceOperationKind value) {
        return switch (value) {
            case LIST -> "list";
            case QUERY -> "query";
            case LOAD -> "load";
            case CREATE -> "create";
            case SAVE -> "save";
            case RENAME -> "rename";
            case MOVE -> "move";
            case DUPLICATE -> "duplicate";
            case ACTIVATE -> "activate";
            case DELETE -> "delete";
            case SUBSCRIBE -> "subscribe";
        };
    }

    private static ResourceOperationKind parseOperation(String value) {
        return switch (value) {
            case "list" -> ResourceOperationKind.LIST;
            case "query" -> ResourceOperationKind.QUERY;
            case "load" -> ResourceOperationKind.LOAD;
            case "create" -> ResourceOperationKind.CREATE;
            case "save" -> ResourceOperationKind.SAVE;
            case "rename" -> ResourceOperationKind.RENAME;
            case "move" -> ResourceOperationKind.MOVE;
            case "duplicate" -> ResourceOperationKind.DUPLICATE;
            case "activate" -> ResourceOperationKind.ACTIVATE;
            case "delete" -> ResourceOperationKind.DELETE;
            case "subscribe" -> ResourceOperationKind.SUBSCRIBE;
            default -> throw new IllegalArgumentException("Unknown resource operation: " + value);
        };
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
        return IdentitySupport.unknown(values, "resource management descriptor unknown data");
    }

    private static JsonValue required(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            throw new IllegalArgumentException("Required resource management descriptor field is missing: " + field);
        }
        return value;
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonValue.JsonArray requireArray(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return array;
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

    private static Optional<String> optionalText(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(text(value, field));
    }

    private static void requireCanonical(JsonValue.JsonObject encoded, JsonValue.JsonObject input, String name) {
        if (!encoded.canonicalText().equals(input.canonicalText())) {
            throw new IllegalArgumentException(name + " is not in the exact canonical shape");
        }
    }
}
