package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;

import restudio.resync.flow.identity.ContentHash;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RuntimeOperationDescriptor(
    ContractRef<CapabilityId> capability,
    ContractRef<OperationId> operation,
    List<TypeExpr> inputs,
    List<TypeExpr> outputs,
    RuntimeSemantics semantics,
    Map<String, Object> unknown,
    List<Pin> pins
) {
    public RuntimeOperationDescriptor {
        capability = Objects.requireNonNull(capability, "Capability Is Required");
        operation = Objects.requireNonNull(operation, "Operation Is Required");
        inputs = immutableTypes(inputs, "Inputs");
        outputs = immutableTypes(outputs, "Outputs");
        semantics = Objects.requireNonNull(semantics, "Runtime Semantics Are Required");
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Operation Unknown Data");
        RuntimeCanonicalSupport.rejectCollisions(unknown, "Operation Unknown Data", Set.of(
            "capability", "operation", "inputs", "outputs", "pins", "semantics"));
        pins = immutablePins(pins, inputs, outputs);
    }

    public RuntimeOperationDescriptor(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        List<Pin> pins,
        RuntimeSemantics semantics
    ) {
        this(capability, operation, inputTypes(pins), outputTypes(pins), semantics, Map.of(), pins);
    }

    public RuntimeOperationDescriptor(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        List<Pin> pins,
        RuntimeSemantics semantics,
        Map<String, ?> unknown
    ) {
        this(capability, operation, inputTypes(pins), outputTypes(pins), semantics,
            RuntimeCanonicalSupport.unknown(unknown, "Operation Unknown Data"), pins);
    }

    private static List<TypeExpr> immutableTypes(List<TypeExpr> values, String label) {
        Objects.requireNonNull(values, label + " Are Required");
        return List.copyOf(values.stream().map(value -> Objects.requireNonNull(value, label + " Cannot Contain Null")).toList());
    }

    public RuntimeBindingKey key() {
        return new RuntimeBindingKey(capability, operation);
    }

    public String canonical() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeOperationDescriptor fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::operation);
    }

    public static RuntimeOperationDescriptor fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.operation(RuntimeCanonicalDecoder.object(value, "operation"));
    }

    public Map<String, Object> canonicalValue() {
        return RuntimeCanonicalSupport.merge(unknown, Map.of(
            "capability", capability.canonicalValue(),
            "operation", operation.canonicalValue(),
            "inputs", inputs.stream().map(TypeExpr::canonicalValue).toList(),
            "outputs", outputs.stream().map(TypeExpr::canonicalValue).toList(),
            "pins", pins.stream().map(Pin::canonicalValue).toList(),
            "semantics", semantics.canonicalValue()));
    }

    public Map<String, Object> executionCanonicalValue() {
        return RuntimeCanonicalSupport.execution(unknown, Map.of(
            "capability", capability.canonicalValue(),
            "operation", operation.canonicalValue(),
            "inputs", inputs.stream().map(TypeExpr::canonicalValue).toList(),
            "outputs", outputs.stream().map(TypeExpr::canonicalValue).toList(),
            "pins", pins.stream().map(Pin::canonicalValue).toList(),
            "semantics", semantics.canonicalValue()));
    }

    public ContentHash executionFingerprint() {
        return ContentHash.of(CanonicalJson.sha256("runtime-definition", executionCanonicalValue()));
    }

    public ContentHash executionFingerprint(Map<String, ?> invalidationInputs) {
        Map<String, Object> values = new LinkedHashMap<>(executionCanonicalValue());
        values.put("invalidationInputs", RuntimeCanonicalSupport.canonicalMap(invalidationInputs, "Execution Invalidation Inputs"));
        return ContentHash.of(CanonicalJson.sha256("runtime-definition", values));
    }

    public List<Pin> inputPins() {
        return pins.stream().filter(pin -> pin.direction() == Direction.INPUT).toList();
    }

    public List<Pin> outputPins() {
        return pins.stream().filter(pin -> pin.direction() == Direction.OUTPUT).toList();
    }

    private static List<Pin> immutablePins(List<Pin> values, List<TypeExpr> inputs, List<TypeExpr> outputs) {
        Objects.requireNonNull(values, "Pins Are Required");
        List<Pin> result = List.copyOf(values.stream()
            .map(value -> Objects.requireNonNull(value, "Pins Cannot Contain Null"))
            .toList());
        Set<PinId> identities = new HashSet<>();
        for (Pin pin : result) {
            if (!identities.add(pin.id())) {
                throw new IllegalArgumentException("Runtime Pin Identities Must Be Unique");
            }
        }
        List<TypeExpr> inputTypes = inputTypes(result);
        List<TypeExpr> outputTypes = outputTypes(result);
        if (!inputTypes.equals(inputs) || !outputTypes.equals(outputs)) {
            throw new IllegalArgumentException("Runtime Pin Signature Does Not Match Input And Output Types");
        }
        return result;
    }

    private static List<TypeExpr> inputTypes(List<Pin> values) {
        return types(values, Direction.INPUT);
    }

    private static List<TypeExpr> outputTypes(List<Pin> values) {
        return types(values, Direction.OUTPUT);
    }

    private static List<TypeExpr> types(List<Pin> values, Direction direction) {
        Objects.requireNonNull(values, "Pins Are Required");
        return values.stream().map(value -> {
            Pin pin = Objects.requireNonNull(value, "Pins Cannot Contain Null");
            return pin.direction() == direction ? pin.type() : null;
        }).filter(Objects::nonNull).toList();
    }

    public enum Direction {
        INPUT,
        OUTPUT
    }

    public record Pin(PinId id, Direction direction, TypeExpr type) {
        public Pin(String id, Direction direction, TypeExpr type) {
            this(PinId.of(id), direction, type);
        }

        public Pin {
            id = Objects.requireNonNull(id, "Pin Id Is Required");
            direction = Objects.requireNonNull(direction, "Pin Direction Is Required");
            type = Objects.requireNonNull(type, "Pin Type Is Required");
        }

        public Map<String, Object> canonicalValue() {
            return Map.of(
                "id", id.canonicalText(),
                "direction", direction.name().toLowerCase(Locale.ROOT),
                "type", type.canonicalValue());
        }
    }
}
