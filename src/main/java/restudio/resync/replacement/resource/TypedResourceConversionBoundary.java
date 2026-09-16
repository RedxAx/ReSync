package restudio.resync.replacement.resource;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeReference;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TypedResourceConversionBoundary {
    public static final String ACTIVATION_FORBIDDEN = "forbidden";
    public static final String TYPED_LOCATOR_REQUIRED = "RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED";
    public static final String SERVER_MISMATCH = "RESOURCE_REFERENCE_SERVER_MISMATCH";
    public static final String TYPE_MISMATCH = "RESOURCE_REFERENCE_TYPE_MISMATCH";
    public static final String LEGACY_SHAPE = "RESOURCE_REFERENCE_LEGACY_SHAPE";
    public static final String TYPE_ARGUMENTS_REQUIRED = "RESOURCE_REFERENCE_TYPE_ARGUMENTS_REQUIRED";

    private static final Set<String> LEGACY_FIELDS = Set.of("kind", "owner", "resourceType", "resourceKind", "typeId", "localType");
    private static final Set<String> ALTERNATE_LOCATOR_FIELDS = Set.of(
        "ownerId", "localId", "resource", "resourceKey", "resourceLocator", "locator", "server", "serverKey", "keyText");
    private static final Set<String> ALTERNATE_KEY_FIELDS = Set.of(
        "serverId", "ownerId", "localId", "typeId", "resource", "resourceKey", "resourceLocator", "locator", "server", "serverKey", "keyText");

    private TypedResourceConversionBoundary() {
    }

    public static ServerResourceLocator requireLocator(Object raw, ServerId authoritativeServer) {
        return convert(raw, authoritativeServer, null);
    }

    public static ServerResourceLocator convert(Object raw, ServerId authoritativeServer) {
        return convert(raw, authoritativeServer, null);
    }

    public static ServerResourceLocator requireLocator(Object raw, ServerId authoritativeServer, TypeReference expectedType) {
        return convert(raw, authoritativeServer, expectedType);
    }

    public static ServerResourceLocator convert(Object raw, ServerId authoritativeServer, TypeReference expectedType) {
        Objects.requireNonNull(authoritativeServer, "Authoritative server ID is required");
        if (!(raw instanceof Map<?, ?> value)) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        validateShape(value);
        JsonValue encoded;
        try {
            encoded = JsonValue.fromJava(value);
        } catch (IllegalArgumentException exception) {
            throw failure(TYPED_LOCATOR_REQUIRED, exception);
        }
        ServerResourceLocator locator;
        try {
            locator = IdentityCodec.decodeLocator(encoded);
        } catch (IllegalArgumentException exception) {
            throw failure(TYPED_LOCATOR_REQUIRED, exception);
        }
        if (!authoritativeServer.equals(locator.serverId())) {
            throw failure(SERVER_MISMATCH);
        }
        if (expectedType != null && (!expectedType.ownerId().equals(locator.owner().value())
            || !expectedType.localId().equals(locator.resourceType().value()))) {
            throw failure(TYPE_MISMATCH);
        }
        return locator;
    }

    public static boolean activationAllowed() {
        return false;
    }

    private static void validateShape(Map<?, ?> value) {
        Set<String> fields = stringFields(value);
        rejectLegacyFields(fields);
        rejectAlternateLocatorFields(fields);
        requireField(value, "serverId");
        requireField(value, "type");
        requireField(value, "id");
        if (!(value.get("serverId") instanceof String serverId) || serverId.isBlank()) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        if (!(value.get("id") instanceof String id) || id.isBlank()) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        if (!(value.get("type") instanceof Map<?, ?> type)) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        validateType(type);
        if (value.containsKey("key")) {
            validateExplicitKey(value.get("key"), type, id);
        }
    }

    private static void validateType(Map<?, ?> type) {
        Set<String> fields = stringFields(type);
        rejectLegacyFields(fields);
        requireField(type, "ownerId");
        requireField(type, "localId");
        if (!(type.get("ownerId") instanceof String ownerId) || ownerId.isBlank()
            || !(type.get("localId") instanceof String localId) || localId.isBlank()) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        if (type.containsKey("arguments")) {
            Object rawArguments = type.get("arguments");
            if (!(rawArguments instanceof List<?> arguments)) {
                throw failure(TYPE_ARGUMENTS_REQUIRED);
            }
            if (arguments.size() > 16) {
                throw failure(TYPE_ARGUMENTS_REQUIRED);
            }
            for (Object argument : arguments) {
                validateTypeExpression(argument);
            }
        }
    }

    private static void validateTypeExpression(Object raw) {
        if (!(raw instanceof Map<?, ?> expression)) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
        Object kind = expression.get("kind");
        if (!(kind instanceof String text) || text.isBlank()) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
        switch (text) {
            case "named" -> {
                requireObjectField(expression, "type");
                validateTypeReference(expression.get("type"));
                validateExpressionArray(expression, "arguments", 0, 16);
            }
            case "resource" -> {
                requireObjectField(expression, "resourceType");
                validateTypeReference(expression.get("resourceType"));
            }
            case "opaque" -> {
                if (!Boolean.TRUE.equals(expression.get("raw"))) {
                    throw failure(TYPE_ARGUMENTS_REQUIRED);
                }
                requireObjectField(expression, "type");
                validateTypeReference(expression.get("type"));
            }
            case "optional", "list" -> {
                requireObjectField(expression, "element");
                validateTypeExpression(expression.get("element"));
            }
            case "map" -> {
                requireObjectField(expression, "key");
                requireObjectField(expression, "value");
                validateTypeExpression(expression.get("key"));
                validateTypeExpression(expression.get("value"));
            }
            case "tuple" -> validateExpressionArray(expression, "elements", 1, 16);
            case "result" -> {
                requireObjectField(expression, "success");
                requireObjectField(expression, "failure");
                validateTypeExpression(expression.get("success"));
                validateTypeExpression(expression.get("failure"));
            }
            case "union" -> {
                Object variants = expression.get("variants");
                if (!(variants instanceof List<?> values) || values.size() < 2 || values.size() > 16) {
                    throw failure(TYPE_ARGUMENTS_REQUIRED);
                }
                Set<String> variantIds = new HashSet<>();
                for (Object variant : values) {
                    if (!(variant instanceof Map<?, ?> item) || !(item.get("variantId") instanceof String variantId)
                        || variantId.isBlank() || !(item.get("type") instanceof Map<?, ?>)) {
                        throw failure(TYPE_ARGUMENTS_REQUIRED);
                    }
                    try {
                        TypeReference.requireLocalId(variantId);
                    } catch (IllegalArgumentException exception) {
                        throw failure(TYPE_ARGUMENTS_REQUIRED, exception);
                    }
                    if (!variantIds.add(variantId)) {
                        throw failure(TYPE_ARGUMENTS_REQUIRED);
                    }
                    validateTypeExpression(item.get("type"));
                }
            }
            default -> throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
    }

    private static void validateTypeReference(Object raw) {
        if (!(raw instanceof Map<?, ?> reference)) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
        rejectLegacyFields(stringFields(reference));
        if (!(reference.get("ownerId") instanceof String) || !(reference.get("localId") instanceof String)) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
        try {
            IdentityCodec.decodeReference(JsonValue.fromJava(reference), ResourceTypeId::new);
        } catch (IllegalArgumentException exception) {
            throw failure(TYPE_ARGUMENTS_REQUIRED, exception);
        }
    }

    private static void validateExpressionArray(Map<?, ?> expression, String field, int minimum, int maximum) {
        Object raw = expression.get(field);
        if (!(raw instanceof List<?> values) || values.size() < minimum || values.size() > maximum) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
        for (Object value : values) {
            validateTypeExpression(value);
        }
    }

    private static void requireObjectField(Map<?, ?> value, String field) {
        if (!(value.get(field) instanceof Map<?, ?>)) {
            throw failure(TYPE_ARGUMENTS_REQUIRED);
        }
    }

    private static void requireField(Map<?, ?> value, String field) {
        if (!value.containsKey(field) || value.get(field) == null) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
    }

    private static void rejectLegacyFields(Set<String> fields) {
        if (fields.stream().anyMatch(LEGACY_FIELDS::contains)) {
            throw failure(LEGACY_SHAPE);
        }
    }

    private static void rejectAlternateLocatorFields(Set<String> fields) {
        if (fields.stream().anyMatch(ALTERNATE_LOCATOR_FIELDS::contains)) {
            throw failure(LEGACY_SHAPE);
        }
    }

    private static void validateExplicitKey(Object raw, Map<?, ?> type, String id) {
        if (!(raw instanceof Map<?, ?> key)) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        Set<String> fields = stringFields(key);
        rejectLegacyFields(fields);
        if (fields.stream().anyMatch(ALTERNATE_KEY_FIELDS::contains)) {
            throw failure(LEGACY_SHAPE);
        }
        requireField(key, "type");
        requireField(key, "id");
        if (!(key.get("type") instanceof Map<?, ?> keyType) || !(key.get("id") instanceof String keyId)
            || !id.equals(keyId)) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
        validateTypeReference(keyType);
        if (!Objects.equals(type.get("ownerId"), keyType.get("ownerId"))
            || !Objects.equals(type.get("localId"), keyType.get("localId"))) {
            throw failure(TYPED_LOCATOR_REQUIRED);
        }
    }

    private static Set<String> stringFields(Map<?, ?> value) {
        Set<String> fields = new HashSet<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw failure(TYPED_LOCATOR_REQUIRED);
            }
            fields.add(field);
        }
        return fields;
    }

    private static IllegalArgumentException failure(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static IllegalArgumentException failure(String reason, Throwable cause) {
        return new IllegalArgumentException(reason, cause);
    }
}
