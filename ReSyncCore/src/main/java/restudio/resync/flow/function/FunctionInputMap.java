package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypedValue;

public final class FunctionInputMap {
    private final Map<FunctionParameterId, TypedValue> values;

    public FunctionInputMap(Map<FunctionParameterId, TypedValue> values) {
        this.values = FunctionContractSupport.typedValues(values, "Function Input Values");
    }

    public static FunctionInputMap empty() {
        return new FunctionInputMap(Map.of());
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
        return validate(values, signature.inputs(), "input");
    }

    public FunctionInputMap require(FunctionSignature signature) {
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

    static List<FunctionDiagnostic> validate(Map<FunctionParameterId, TypedValue> values,
                                             List<FunctionParameterContract> parameters,
                                             String direction) {
        Map<FunctionParameterId, FunctionParameterContract> declarations = parameters.stream()
            .collect(Collectors.toUnmodifiableMap(FunctionParameterContract::id, value -> value));
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        values.forEach((id, value) -> {
            FunctionParameterContract declaration = declarations.get(id);
            if (declaration == null) {
                diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_UNKNOWN", "parameter-validation",
                    "The " + direction + " parameter is not declared by the function signature.", id));
            } else if (!declaration.type().equals(value.type())) {
                diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_TYPE_MISMATCH", "parameter-validation",
                    "The " + direction + " parameter value type does not match the function signature.", id));
            } else if (declaration.required() && value.state() == TypedValue.State.ABSENT) {
                diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_REQUIRED", "parameter-validation",
                    "The required " + direction + " parameter is absent.", id));
            }
        });
        parameters.stream()
            .filter(FunctionParameterContract::required)
            .filter(parameter -> parameter.defaultValue() == null)
            .filter(parameter -> !values.containsKey(parameter.id()))
            .forEach(parameter -> diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_REQUIRED", "parameter-validation",
                "The required " + direction + " parameter is missing.", parameter.id())));
        return List.copyOf(diagnostics);
    }
}
