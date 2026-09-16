package restudio.resync.flow.type;

import restudio.resync.flow.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface TypeExpr permits TypeExpr.Named, TypeExpr.OptionalType, TypeExpr.ListType, TypeExpr.MapType, TypeExpr.TupleType, TypeExpr.ResultType, TypeExpr.ResourceType, TypeExpr.UnionType, TypeExpr.OpaqueType {
    String kind();

    Map<String, Object> unknown();

    default String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    default Object canonicalValue() {
        return switch (this) {
            case Named named -> TypeSupport.merge(named.unknown(), Map.of(
                    "arguments", named.arguments().stream().map(TypeExpr::canonicalValue).toList(),
                    "kind", named.kind(),
                    "type", named.reference().canonicalValue()));
            case OptionalType optional -> TypeSupport.merge(optional.unknown(), Map.of(
                    "element", optional.element().canonicalValue(),
                    "kind", optional.kind()));
            case ListType list -> TypeSupport.merge(list.unknown(), Map.of(
                    "element", list.element().canonicalValue(),
                    "kind", list.kind()));
            case MapType map -> TypeSupport.merge(map.unknown(), Map.of(
                    "key", map.key().canonicalValue(),
                    "kind", map.kind(),
                    "value", map.value().canonicalValue()));
            case TupleType tuple -> TypeSupport.merge(tuple.unknown(), Map.of(
                    "elements", tuple.elements().stream().map(TypeExpr::canonicalValue).toList(),
                    "kind", tuple.kind()));
            case ResultType result -> TypeSupport.merge(result.unknown(), Map.of(
                    "failure", result.failure().canonicalValue(),
                    "kind", result.kind(),
                    "success", result.success().canonicalValue()));
            case ResourceType resource -> TypeSupport.merge(resource.unknown(), Map.of(
                    "kind", resource.kind(),
                    "resourceType", resource.resourceType().canonicalValue()));
            case UnionType union -> TypeSupport.merge(union.unknown(), Map.of(
                    "kind", union.kind(),
                    "variants", union.variants().stream().map(UnionVariant::canonicalValue).toList()));
            case OpaqueType opaque -> TypeSupport.merge(opaque.unknown(), Map.of(
                    "kind", opaque.kind(),
                    "raw", true,
                    "type", opaque.reference().canonicalValue()));
        };
    }

    static Named named(TypeReference reference, TypeExpr... arguments) {
        return new Named(reference, List.of(arguments));
    }

    static Named named(TypeReference reference, List<TypeExpr> arguments) {
        return new Named(reference, arguments);
    }

    static OptionalType optional(TypeExpr element) {
        return new OptionalType(element);
    }

    static ListType list(TypeExpr element) {
        return new ListType(element);
    }

    static MapType map(TypeExpr key, TypeExpr value) {
        return new MapType(key, value);
    }

    static TupleType tuple(List<TypeExpr> elements) {
        return new TupleType(elements);
    }

    static ResultType result(TypeExpr success, TypeExpr failure) {
        return new ResultType(success, failure);
    }

    static ResourceType resource(TypeReference resourceType) {
        return new ResourceType(resourceType);
    }

    static UnionType union(List<UnionVariant> variants) {
        return new UnionType(variants);
    }

    static OpaqueType opaque(TypeReference reference) {
        return new OpaqueType(reference);
    }

    record Named(TypeReference reference, List<TypeExpr> arguments, Map<String, Object> unknown) implements TypeExpr {
        public Named {
            Objects.requireNonNull(reference, "reference");
            arguments = immutableExpressions(arguments, 16, "arguments");
            unknown = TypeSupport.unknown(unknown, "named type unknown data");
        }

        public Named(TypeReference reference, List<TypeExpr> arguments) {
            this(reference, arguments, Map.of());
        }

        @Override
        public String kind() {
            return "named";
        }
    }

    record OptionalType(TypeExpr element, Map<String, Object> unknown) implements TypeExpr {
        public OptionalType {
            Objects.requireNonNull(element, "element");
            unknown = TypeSupport.unknown(unknown, "optional type unknown data");
        }

        public OptionalType(TypeExpr element) {
            this(element, Map.of());
        }

        @Override
        public String kind() {
            return "optional";
        }
    }

    record ListType(TypeExpr element, Map<String, Object> unknown) implements TypeExpr {
        public ListType {
            Objects.requireNonNull(element, "element");
            unknown = TypeSupport.unknown(unknown, "list type unknown data");
        }

        public ListType(TypeExpr element) {
            this(element, Map.of());
        }

        @Override
        public String kind() {
            return "list";
        }
    }

    record MapType(TypeExpr key, TypeExpr value, Map<String, Object> unknown) implements TypeExpr {
        public MapType {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            unknown = TypeSupport.unknown(unknown, "map type unknown data");
        }

        public MapType(TypeExpr key, TypeExpr value) {
            this(key, value, Map.of());
        }

        @Override
        public String kind() {
            return "map";
        }
    }

    record TupleType(List<TypeExpr> elements, Map<String, Object> unknown) implements TypeExpr {
        public TupleType {
            elements = immutableExpressions(elements, 16, "elements");
            if (elements.isEmpty()) {
                throw new IllegalArgumentException("A tuple needs at least one element");
            }
            unknown = TypeSupport.unknown(unknown, "tuple type unknown data");
        }

        public TupleType(List<TypeExpr> elements) {
            this(elements, Map.of());
        }

        @Override
        public String kind() {
            return "tuple";
        }
    }

    record ResultType(TypeExpr success, TypeExpr failure, Map<String, Object> unknown) implements TypeExpr {
        public ResultType {
            Objects.requireNonNull(success, "success");
            Objects.requireNonNull(failure, "failure");
            unknown = TypeSupport.unknown(unknown, "result type unknown data");
        }

        public ResultType(TypeExpr success, TypeExpr failure) {
            this(success, failure, Map.of());
        }

        @Override
        public String kind() {
            return "result";
        }
    }

    record ResourceType(TypeReference resourceType, Map<String, Object> unknown) implements TypeExpr {
        public ResourceType {
            Objects.requireNonNull(resourceType, "resourceType");
            unknown = TypeSupport.unknown(unknown, "resource type unknown data");
        }

        public ResourceType(TypeReference resourceType) {
            this(resourceType, Map.of());
        }

        @Override
        public String kind() {
            return "resource";
        }
    }

    record UnionType(List<UnionVariant> variants, Map<String, Object> unknown) implements TypeExpr {
        public UnionType {
            Objects.requireNonNull(variants, "variants");
            if (variants.size() < 2 || variants.size() > 16) {
                throw new IllegalArgumentException("A union needs between 2 and 16 variants");
            }
            var sorted = new ArrayList<>(variants);
            sorted.sort(Comparator.comparing(UnionVariant::variantId));
            for (int index = 1; index < sorted.size(); index++) {
                if (sorted.get(index - 1).variantId().equals(sorted.get(index).variantId())) {
                    throw new IllegalArgumentException("Duplicate union variant: " + sorted.get(index).variantId());
                }
            }
            variants = List.copyOf(sorted);
            unknown = TypeSupport.unknown(unknown, "union type unknown data");
        }

        public UnionType(List<UnionVariant> variants) {
            this(variants, Map.of());
        }

        public UnionVariant variant(String variantId) {
            TypeReference.requireLocalId(variantId);
            return variants.stream().filter(value -> value.variantId().equals(variantId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown union variant: " + variantId));
        }

        @Override
        public String kind() {
            return "union";
        }
    }

    record OpaqueType(TypeReference reference, Map<String, Object> unknown) implements TypeExpr {
        public OpaqueType {
            Objects.requireNonNull(reference, "reference");
            unknown = TypeSupport.unknown(unknown, "opaque type unknown data");
        }

        public OpaqueType(TypeReference reference) {
            this(reference, Map.of());
        }

        @Override
        public String kind() {
            return "opaque";
        }
    }

    record UnionVariant(String variantId, TypeExpr type, String displayName, String description, Map<String, Object> unknown) {
        public UnionVariant {
            variantId = TypeReference.requireLocalId(variantId);
            Objects.requireNonNull(type, "type");
            displayName = optionalText(displayName, "displayName", 1, 128);
            description = optionalText(description, "description", 16, 240);
            unknown = TypeSupport.unknown(unknown, "union variant unknown data");
        }

        public UnionVariant(String variantId, TypeExpr type) {
            this(variantId, type, null, null, Map.of());
        }

        public UnionVariant(String variantId, TypeExpr type, String displayName, String description) {
            this(variantId, type, displayName, description, Map.of());
        }

        public Map<String, Object> canonicalValue() {
            var fields = new LinkedHashMap<String, Object>();
            if (description != null) {
                fields.put("description", description);
            }
            if (displayName != null) {
                fields.put("displayName", displayName);
            }
            fields.put("type", type.canonicalValue());
            fields.put("variantId", variantId);
            return TypeSupport.merge(unknown, fields);
        }

        private static String optionalText(String value, String name, int minimum, int maximum) {
            if (value == null) {
                return null;
            }
            if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
                throw new IllegalArgumentException("Invalid " + name);
            }
            return value;
        }
    }

    private static List<TypeExpr> immutableExpressions(List<TypeExpr> values, int maximum, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " entries");
        }
        var copy = new ArrayList<TypeExpr>(values.size());
        for (var value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return List.copyOf(copy);
    }
}
