package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

public record RuntimeBindingDescriptor(
    ContractRef<CapabilityId> capability,
    ContractRef<OperationId> operation,
    ContractRef<ProviderId> provider,
    String providerVersion,
    List<TypeExpr> inputs,
    List<TypeExpr> outputs,
    RuntimeSemantics semantics,
    boolean available,
    Map<String, Object> unknown,
    List<RuntimeOperationDescriptor.Pin> pins
) {
    public RuntimeBindingDescriptor {
        capability = Objects.requireNonNull(capability, "Capability Is Required");
        operation = Objects.requireNonNull(operation, "Operation Is Required");
        provider = Objects.requireNonNull(provider, "Provider Is Required");
        providerVersion = Objects.requireNonNull(providerVersion, "Provider Version Is Required");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "Inputs Are Required").stream()
            .map(value -> Objects.requireNonNull(value, "Inputs Cannot Contain Null")).toList());
        outputs = List.copyOf(Objects.requireNonNull(outputs, "Outputs Are Required").stream()
            .map(value -> Objects.requireNonNull(value, "Outputs Cannot Contain Null")).toList());
        semantics = Objects.requireNonNull(semantics, "Runtime Semantics Are Required");
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Binding Unknown Data");
        RuntimeCanonicalSupport.rejectCollisions(unknown, "Binding Unknown Data", Set.of(
            "capability", "operation", "provider", "providerVersion", "inputs", "outputs", "pins", "semantics", "available", "fingerprint"));
        if (providerVersion.isEmpty() || !providerVersion.equals(providerVersion.trim())) {
            throw new IllegalArgumentException("Provider Version Is Required");
        }
        pins = immutablePins(pins, inputs, outputs);
    }

    public RuntimeBindingDescriptor(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        ContractRef<ProviderId> provider,
        String providerVersion,
        List<RuntimeOperationDescriptor.Pin> pins,
        RuntimeSemantics semantics,
        boolean available
    ) {
        this(capability, operation, provider, providerVersion, inputTypes(pins), outputTypes(pins), semantics, available, Map.of(), pins);
    }

    public RuntimeBindingDescriptor(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        ContractRef<ProviderId> provider,
        String providerVersion,
        List<RuntimeOperationDescriptor.Pin> pins,
        RuntimeSemantics semantics,
        boolean available,
        Map<String, ?> unknown
    ) {
        this(capability, operation, provider, providerVersion, inputTypes(pins), outputTypes(pins), semantics, available,
            RuntimeCanonicalSupport.unknown(unknown, "Binding Unknown Data"), pins);
    }

    public RuntimeBindingDescriptor(RuntimeOperationDescriptor operation, ContractRef<ProviderId> provider, String providerVersion, boolean available) {
        this(operation.capability(), operation.operation(), provider, providerVersion, operation.inputs(), operation.outputs(), operation.semantics(), available, operation.unknown(), operation.pins());
    }

    public RuntimeBindingDescriptor(
        RuntimeOperationDescriptor operation,
        ContractRef<ProviderId> provider,
        String providerVersion,
        boolean available,
        Map<String, ?> unknown
    ) {
        this(operation.capability(), operation.operation(), provider, providerVersion, operation.inputs(), operation.outputs(), operation.semantics(), available,
            RuntimeCanonicalSupport.combineUnknown(operation.unknown(), unknown, "Binding Unknown Data"), operation.pins());
    }

    public RuntimeBindingKey key() {
        return new RuntimeBindingKey(capability, operation);
    }

    public String canonicalWithoutFingerprint() {
        return CanonicalJson.canonicalize(canonicalValueWithoutFingerprint());
    }

    public ContentHash fingerprint() {
        return ContentHash.of(CanonicalJson.sha256("runtime-binding", executionCanonicalValue()));
    }

    public ContentHash executionFingerprint() {
        return fingerprint();
    }

    public ContentHash executionFingerprint(Map<String, ?> invalidationInputs) {
        Map<String, Object> normalizedInputs = RuntimeCanonicalSupport.canonicalMap(invalidationInputs, "Execution Invalidation Inputs");
        if (normalizedInputs.isEmpty()) {
            return fingerprint();
        }
        Map<String, Object> values = new LinkedHashMap<>(executionCanonicalValue());
        values.put("invalidationInputs", normalizedInputs);
        return ContentHash.of(CanonicalJson.sha256("runtime-execution", values));
    }

    public String canonical() {
        Map<String, Object> values = new LinkedHashMap<>(canonicalValueWithoutFingerprint());
        values.put("fingerprint", executionFingerprint().canonicalText());
        return CanonicalJson.canonicalize(values);
    }

    public static RuntimeBindingDescriptor fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::binding);
    }

    public static RuntimeBindingDescriptor fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.binding(RuntimeCanonicalDecoder.object(value, "binding"));
    }

    public Map<String, Object> canonicalValueWithoutFingerprint() {
        return RuntimeCanonicalSupport.merge(unknown, Map.of(
            "capability", capability.canonicalValue(),
            "operation", operation.canonicalValue(),
            "provider", provider.canonicalValue(),
            "providerVersion", providerVersion,
            "inputs", inputs.stream().map(TypeExpr::canonicalValue).toList(),
            "outputs", outputs.stream().map(TypeExpr::canonicalValue).toList(),
            "pins", pins.stream().map(RuntimeOperationDescriptor.Pin::canonicalValue).toList(),
            "semantics", semantics.canonicalValue(),
            "available", available));
    }

    public Map<String, Object> executionCanonicalValue() {
        return RuntimeCanonicalSupport.execution(unknown, Map.of(
            "capability", capability.canonicalValue(),
            "operation", operation.canonicalValue(),
            "provider", provider.canonicalValue(),
            "providerVersion", providerVersion,
            "inputs", inputs.stream().map(TypeExpr::canonicalValue).toList(),
            "outputs", outputs.stream().map(TypeExpr::canonicalValue).toList(),
            "pins", pins.stream().map(RuntimeOperationDescriptor.Pin::canonicalValue).toList(),
            "semantics", semantics.canonicalValue(),
            "available", available));
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> values = new LinkedHashMap<>(canonicalValueWithoutFingerprint());
        values.put("fingerprint", executionFingerprint().canonicalText());
        return Collections.unmodifiableMap(values);
    }

    private static List<RuntimeOperationDescriptor.Pin> immutablePins(
        List<RuntimeOperationDescriptor.Pin> values,
        List<TypeExpr> inputs,
        List<TypeExpr> outputs
    ) {
        Objects.requireNonNull(values, "Pins Are Required");
        List<RuntimeOperationDescriptor.Pin> result = List.copyOf(values.stream()
            .map(value -> Objects.requireNonNull(value, "Pins Cannot Contain Null"))
            .toList());
        Set<PinId> identities = new HashSet<>();
        for (RuntimeOperationDescriptor.Pin pin : result) {
            if (!identities.add(pin.id())) {
                throw new IllegalArgumentException("Runtime Pin Identities Must Be Unique");
            }
        }
        if (!inputTypes(result).equals(inputs) || !outputTypes(result).equals(outputs)) {
            throw new IllegalArgumentException("Runtime Pin Signature Does Not Match Input And Output Types");
        }
        return result;
    }

    private static List<TypeExpr> inputTypes(List<RuntimeOperationDescriptor.Pin> values) {
        return types(values, RuntimeOperationDescriptor.Direction.INPUT);
    }

    private static List<TypeExpr> outputTypes(List<RuntimeOperationDescriptor.Pin> values) {
        return types(values, RuntimeOperationDescriptor.Direction.OUTPUT);
    }

    private static List<TypeExpr> types(List<RuntimeOperationDescriptor.Pin> values, RuntimeOperationDescriptor.Direction direction) {
        Objects.requireNonNull(values, "Pins Are Required");
        return values.stream().map(value -> {
            RuntimeOperationDescriptor.Pin pin = Objects.requireNonNull(value, "Pins Cannot Contain Null");
            return pin.direction() == direction ? pin.type() : null;
        }).filter(Objects::nonNull).toList();
    }
}
