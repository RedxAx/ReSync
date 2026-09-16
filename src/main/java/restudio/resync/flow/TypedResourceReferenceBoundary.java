package restudio.resync.flow;

import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class TypedResourceReferenceBoundary {
    private TypedResourceReferenceBoundary() {
    }

    static ServerResourceLocator requireLocator(TypeExpr.ResourceType expected, Object raw, ServerId serverId) {
        Objects.requireNonNull(expected, "Expected resource type is required");
        Objects.requireNonNull(serverId, "Authoritative server ID is required");
        ServerResourceLocator locator = raw instanceof ServerResourceLocator typed
            ? typed
            : raw instanceof FlowResourceReference legacy
                ? legacyLocator(legacy, serverId, expected.resourceType())
                : null;
        if (locator == null) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
        }
        validateLocator(expected, locator, serverId);
        return locator;
    }

    static ServerResourceLocator requireLocator(Object raw, ServerId serverId) {
        Objects.requireNonNull(serverId, "Authoritative server ID is required");
        if (raw instanceof ServerResourceLocator locator) {
            if (!serverId.equals(locator.serverId())) {
                throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_MISMATCH");
            }
            if (locator.id().isBlank()) {
                throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
            }
            return locator;
        }
        if (raw instanceof FlowResourceReference legacy) {
            return legacyLocator(legacy, serverId, null);
        }
        throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
    }

    static TypedValue requireValue(TypeExpr expected, TypedValue value, ServerId serverId) {
        Objects.requireNonNull(expected, "Expected value type is required");
        Objects.requireNonNull(value, "Typed value is required");
        Objects.requireNonNull(serverId, "Authoritative server ID is required");
        if (!expected.equals(value.type())) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPE_MISMATCH");
        }
        TypeExpr selected = selectedType(expected, value);
        switch (value.state()) {
            case LOCATOR -> validateLocator(selected, value.locator(), serverId);
            case VALUE -> {
                Object material = canonicalizeMaterial(selected, value.value(), serverId);
                if (!Objects.equals(material, value.value())) {
                    if (expected instanceof TypeExpr.UnionType union) {
                        return TypedValue.unionValue(union, value.variantId(), material, value.unknown());
                    }
                    return TypedValue.value(expected, material, value.unknown());
                }
            }
            case ABSENT, OPAQUE -> {
            }
            case NULL -> {
                if (!allowsNull(selected)) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
            }
        }
        return value;
    }

    static Object canonicalize(TypeExpr expected, Object raw, ServerId serverId) {
        Objects.requireNonNull(expected, "Expected value type is required");
        Objects.requireNonNull(serverId, "Authoritative server ID is required");
        return canonicalizeMaterial(expected, raw, serverId);
    }

    private static TypeExpr selectedType(TypeExpr type, TypedValue value) {
        if (type instanceof TypeExpr.UnionType union) {
            return union.variant(Objects.requireNonNull(value.variantId(), "Union value variant is required")).type();
        }
        return type;
    }

    private static Object canonicalizeMaterial(TypeExpr type, Object raw, ServerId serverId) {
        if (!containsResource(type)) {
            return raw;
        }
        if (raw == null) {
            if (allowsNull(type)) {
                return null;
            }
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
        }
        switch (type) {
            case TypeExpr.ResourceType resource -> {
                return requireLocator(resource, raw, serverId);
            }
            case TypeExpr.OptionalType optional -> {
                if (raw instanceof Optional<?> value) {
                    if (value.isPresent()) {
                        return canonicalizeMaterial(optional.element(), value.get(), serverId);
                    }
                    return null;
                } else {
                    return canonicalizeMaterial(optional.element(), raw, serverId);
                }
            }
            case TypeExpr.ListType list -> {
                if (!(raw instanceof Iterable<?> values)) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
                List<Object> result = new ArrayList<>();
                for (Object value : values) {
                    result.add(canonicalizeMaterial(list.element(), value, serverId));
                }
                return Collections.unmodifiableList(result);
            }
            case TypeExpr.MapType map -> {
                if (!(raw instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
                Map<Object, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    result.put(canonicalizeMaterial(map.key(), entry.getKey(), serverId),
                        canonicalizeMaterial(map.value(), entry.getValue(), serverId));
                }
                return Collections.unmodifiableMap(result);
            }
            case TypeExpr.TupleType tuple -> {
                if (!(raw instanceof List<?> values) || values.size() != tuple.elements().size()) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
                List<Object> result = new ArrayList<>(values.size());
                for (int index = 0; index < values.size(); index++) {
                    result.add(canonicalizeMaterial(tuple.elements().get(index), values.get(index), serverId));
                }
                return Collections.unmodifiableList(result);
            }
            case TypeExpr.ResultType result -> {
                if (!(raw instanceof Map<?, ?> values) || !(values.get("success") instanceof Boolean success) || !values.containsKey("value")) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
                TypeExpr branch = success ? result.success() : result.failure();
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                    }
                    copy.put(key, "value".equals(key)
                        ? canonicalizeMaterial(branch, entry.getValue(), serverId)
                        : canonicalizeUnknown(entry.getValue(), serverId));
                }
                return Collections.unmodifiableMap(copy);
            }
            case TypeExpr.UnionType union -> throw new IllegalArgumentException("RESOURCE_REFERENCE_AMBIGUOUS_TYPE");
            case TypeExpr.Named named -> canonicalizeNested(named, raw, serverId);
            case TypeExpr.OpaqueType ignored -> {
                return raw;
            }
        }
        throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
    }

    private static Object canonicalizeUnknown(Object raw, ServerId serverId) {
        if (raw == null || raw instanceof String || raw instanceof Boolean || raw instanceof Character
            || raw instanceof Number || raw instanceof java.util.UUID) {
            return raw;
        }
        if (raw instanceof ServerResourceLocator || raw instanceof FlowResourceReference) {
            return requireLocator(raw, serverId);
        }
        if (raw instanceof Optional<?> optional) {
            return optional.isPresent()
                ? Map.of("present", true, "value", canonicalizeUnknown(optional.get(), serverId))
                : Map.of("present", false);
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
                }
                copy.put(key, canonicalizeUnknown(entry.getValue(), serverId));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (raw instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object value : iterable) {
                copy.add(canonicalizeUnknown(value, serverId));
            }
            return Collections.unmodifiableList(copy);
        }
        if (raw.getClass().isArray()) {
            List<Object> copy = new ArrayList<>(Array.getLength(raw));
            for (int index = 0; index < Array.getLength(raw); index++) {
                copy.add(canonicalizeUnknown(Array.get(raw, index), serverId));
            }
            return Collections.unmodifiableList(copy);
        }
        return raw;
    }

    private static Object canonicalizeNested(TypeExpr type, Object raw, ServerId serverId) {
        List<TypeExpr.ResourceType> resources = resourceTypes(type);
        if (resources.isEmpty()) {
            return raw;
        }
        if (raw instanceof ServerResourceLocator || raw instanceof FlowResourceReference) {
            List<TypeExpr.ResourceType> matches = resources.stream()
                .filter(resource -> matches(resource, raw, serverId))
                .distinct()
                .toList();
            throw new IllegalArgumentException(matches.isEmpty()
                ? "RESOURCE_REFERENCE_TYPE_MISMATCH" : "RESOURCE_REFERENCE_AMBIGUOUS_TYPE");
        }
        if (raw instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey() instanceof ServerResourceLocator || entry.getKey() instanceof FlowResourceReference
                    ? canonicalizeNested(type, entry.getKey(), serverId) : entry.getKey();
                result.put(key,
                    canonicalizeNested(type, entry.getValue(), serverId));
            }
            return Collections.unmodifiableMap(result);
        } else if (raw instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object value : values) {
                result.add(canonicalizeNested(type, value, serverId));
            }
            return Collections.unmodifiableList(result);
        } else {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
        }
    }

    private static void validateLocator(TypeExpr type, ServerResourceLocator locator, ServerId serverId) {
        if (type instanceof TypeExpr.OptionalType optional) {
            validateLocator(optional.element(), locator, serverId);
            return;
        }
        if (!(type instanceof TypeExpr.ResourceType resource) || locator == null) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
        }
        if (!matches(resource, locator, serverId)) {
            if (!serverId.equals(locator.serverId())) {
                throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_MISMATCH");
            }
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPE_MISMATCH");
        }
    }

    private static boolean matches(TypeExpr.ResourceType expected, ServerResourceLocator locator, ServerId serverId) {
        return serverId.equals(locator.serverId())
            && expected.resourceType().ownerId().equals(locator.type().owner().value())
            && expected.resourceType().localId().equals(locator.type().id().value())
            && !locator.id().isBlank();
    }

    private static boolean matches(TypeExpr.ResourceType expected, Object raw, ServerId serverId) {
        if (raw instanceof ServerResourceLocator locator) {
            return matches(expected, locator, serverId);
        }
        if (!(raw instanceof FlowResourceReference legacy)) {
            return false;
        }
        return expected.resourceType().ownerId().equals(legacy.owner())
            && expected.resourceType().localId().equals(legacy.kind())
            && !legacy.id().isBlank()
            && legacy.available();
    }

    private static ServerResourceLocator legacyLocator(
        FlowResourceReference legacy,
        ServerId serverId,
        TypeReference expectedType
    ) {
        Objects.requireNonNull(legacy, "Legacy resource reference is required");
        if (legacy.kind().isBlank() || legacy.owner().isBlank() || legacy.id().isBlank()) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED");
        }
        if (!legacy.available()) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_LEGACY_UNKNOWN");
        }
        Map<String, Object> unknown = new LinkedHashMap<>(legacy.metadata());
        Object declaredServer = unknown.remove("serverId");
        if (declaredServer != null && (!(declaredServer instanceof String declared)
            || !serverId.canonicalText().equals(declared))) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_MISMATCH");
        }
        if (unknown.keySet().stream().anyMatch(Set.of("id", "type", "key")::contains)) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_LEGACY_UNKNOWN");
        }
        OwnerId owner;
        ResourceTypeId resourceType;
        try {
            owner = OwnerId.of(legacy.owner());
            resourceType = ResourceTypeId.of(legacy.kind());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_LEGACY_UNKNOWN", failure);
        }
        if (expectedType != null && (!expectedType.ownerId().equals(owner.value())
            || !expectedType.localId().equals(resourceType.value()))) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_TYPE_MISMATCH");
        }
        try {
            return new ServerResourceLocator(serverId, ContractRef.of(owner, resourceType), legacy.id(), unknown);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_LEGACY_UNKNOWN", failure);
        }
    }

    static boolean containsResource(TypeExpr type) {
        return !resourceTypes(type).isEmpty();
    }

    private static boolean allowsNull(TypeExpr type) {
        return !containsResource(type) || type instanceof TypeExpr.OptionalType;
    }

    private static List<TypeExpr.ResourceType> resourceTypes(TypeExpr type) {
        List<TypeExpr.ResourceType> result = new ArrayList<>();
        collectResourceTypes(type, result);
        return result;
    }

    private static void collectResourceTypes(TypeExpr type, List<TypeExpr.ResourceType> result) {
        switch (type) {
            case TypeExpr.ResourceType resource -> result.add(resource);
            case TypeExpr.Named named -> named.arguments().forEach(argument -> collectResourceTypes(argument, result));
            case TypeExpr.OptionalType optional -> collectResourceTypes(optional.element(), result);
            case TypeExpr.ListType list -> collectResourceTypes(list.element(), result);
            case TypeExpr.MapType map -> {
                collectResourceTypes(map.key(), result);
                collectResourceTypes(map.value(), result);
            }
            case TypeExpr.TupleType tuple -> tuple.elements().forEach(element -> collectResourceTypes(element, result));
            case TypeExpr.ResultType resultType -> {
                collectResourceTypes(resultType.success(), result);
                collectResourceTypes(resultType.failure(), result);
            }
            case TypeExpr.UnionType union -> union.variants().forEach(variant -> collectResourceTypes(variant.type(), result));
            case TypeExpr.OpaqueType ignored -> {
            }
        }
    }
}
