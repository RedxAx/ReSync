package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypedValue;

public final class FunctionOutputMap {
    private final Map<FunctionParameterId, TypedValue> values;

    public FunctionOutputMap(Map<FunctionParameterId, TypedValue> values) {
        this.values = FunctionContractSupport.typedValues(values, "Function Output Values");
    }

    public static FunctionOutputMap empty() {
        return new FunctionOutputMap(Map.of());
    }

    public Map<FunctionParameterId, TypedValue> values() {
        return values;
    }

    public TypedValue value(FunctionParameterId id) {
        return values.get(Objects.requireNonNull(id, "Function Parameter ID Is Required"));
    }

    public boolean contains(FunctionParameterId id) {
        return values.containsKey(Objects.requireNonNull(id, "Function Parameter ID Is Required"));
    }

    public List<FunctionDiagnostic> validate(FunctionSignature signature) {
        Objects.requireNonNull(signature, "Function Signature Is Required");
        return FunctionInputMap.validate(values, signature.outputs(), "output");
    }

    public FunctionOutputMap require(FunctionSignature signature) {
        List<FunctionDiagnostic> diagnostics = validate(signature);
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException(diagnostics.getFirst().message());
        }
        return this;
    }

    public Map<String, Object> canonicalValue() {
        return FunctionContractSupport.canonicalTypedValues(values);
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }
}
