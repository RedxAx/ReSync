package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import restudio.resync.flow.identity.FunctionParameterId;

public record FunctionSignature(
    FunctionLocator function,
    FunctionRevision revision,
    List<FunctionParameterContract> inputs,
    List<FunctionParameterContract> outputs,
    Map<String, Object> unknown
) {
    public FunctionSignature {
        function = Objects.requireNonNull(function, "Function Locator Is Required");
        revision = Objects.requireNonNull(revision, "Function Revision Is Required");
        inputs = immutableParameters(inputs, "Function Inputs");
        outputs = immutableParameters(outputs, "Function Outputs");
        Set<FunctionParameterId> ids = new HashSet<>();
        inputs.forEach(parameter -> addParameter(ids, parameter));
        outputs.forEach(parameter -> addParameter(ids, parameter));
        unknown = FunctionContractSupport.immutableMap(unknown, "Function Signature Unknown Data");
    }

    public FunctionSignature(FunctionLocator function, FunctionRevision revision,
                             List<FunctionParameterContract> inputs, List<FunctionParameterContract> outputs) {
        this(function, revision, inputs, outputs, Map.of());
    }

    public Map<FunctionParameterId, FunctionParameterContract> parameters() {
        LinkedHashMap<FunctionParameterId, FunctionParameterContract> result = new LinkedHashMap<>();
        inputs.forEach(parameter -> result.put(parameter.id(), parameter));
        outputs.forEach(parameter -> result.put(parameter.id(), parameter));
        return Map.copyOf(result);
    }

    public List<FunctionDiagnostic> validate(FunctionInputMap inputValues, FunctionOutputMap outputValues) {
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(inputValues.validate(this));
        diagnostics.addAll(outputValues.validate(this));
        return List.copyOf(diagnostics);
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("function", function.canonicalValue());
        value.put("revision", revision.value());
        value.put("inputs", inputs.stream().map(FunctionParameterContract::canonicalValue).toList());
        value.put("outputs", outputs.stream().map(FunctionParameterContract::canonicalValue).toList());
        unknown.forEach((key, item) -> {
            if (value.putIfAbsent(key, item) != null) {
                throw new IllegalArgumentException("Function Signature Unknown Data Collides With Known Field: " + key);
            }
        });
        return Map.copyOf(value);
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }

    private static List<FunctionParameterContract> immutableParameters(List<FunctionParameterContract> values, String label) {
        Objects.requireNonNull(values, label + " Are Required");
        List<FunctionParameterContract> copy = new ArrayList<>(values.size());
        values.forEach(value -> copy.add(Objects.requireNonNull(value, label + " Cannot Contain Null Parameters")));
        return List.copyOf(copy);
    }

    private static void addParameter(Set<FunctionParameterId> ids, FunctionParameterContract parameter) {
        if (!ids.add(parameter.id())) {
            throw new IllegalArgumentException("Duplicate Function Parameter ID: " + parameter.id());
        }
    }
}
