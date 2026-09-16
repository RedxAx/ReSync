package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypedValue;

public record FunctionFrame(
    FunctionSignature signature,
    FunctionRuntimeContext context,
    FunctionInputMap inputs,
    FunctionOutputMap outputs,
    int step
) {
    public FunctionFrame {
        signature = Objects.requireNonNull(signature, "Function Frame Signature Is Required");
        context = Objects.requireNonNull(context, "Function Frame Context Is Required");
        inputs = Objects.requireNonNull(inputs, "Function Frame Inputs Are Required");
        outputs = Objects.requireNonNull(outputs, "Function Frame Outputs Are Required");
        if (!signature.function().equals(context.function()) || !signature.revision().equals(context.revision())) {
            throw new IllegalArgumentException("Function Frame Context Does Not Match The Function Signature");
        }
        if (!inputs.values().equals(context.inputs().values())) {
            throw new IllegalArgumentException("Function Frame Inputs Do Not Match The Function Context");
        }
        if (step < 0 || step > CompiledFunctionBody.MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Function Frame Step Is Out Of Bounds");
        }
        List<FunctionDiagnostic> diagnostics = validatePresentOutputs(signature, outputs);
        if (!diagnostics.isEmpty()) {
            throw new ValidationException(diagnostics.getFirst());
        }
    }

    public static FunctionFrame initial(FunctionSignature signature, FunctionRuntimeContext context) {
        Objects.requireNonNull(signature, "Function Frame Signature Is Required");
        Objects.requireNonNull(context, "Function Frame Context Is Required");
        return new FunctionFrame(signature, context, context.inputs(), FunctionOutputMap.empty(), 0);
    }

    public TypedValue input(FunctionParameterId id) {
        return inputs.value(Objects.requireNonNull(id, "Function Input Parameter ID Is Required"));
    }

    public TypedValue output(FunctionParameterId id) {
        return outputs.value(Objects.requireNonNull(id, "Function Output Parameter ID Is Required"));
    }

    public TypedValue value(FunctionParameterId id) {
        FunctionParameterId parameterId = Objects.requireNonNull(id, "Function Parameter ID Is Required");
        TypedValue output = outputs.value(parameterId);
        return output != null ? output : inputs.value(parameterId);
    }

    public FunctionFrame withOutput(FunctionParameterId id, TypedValue value) {
        FunctionParameterId parameterId = Objects.requireNonNull(id, "Function Output Parameter ID Is Required");
        TypedValue typedValue = Objects.requireNonNull(value, "Function Output Value Is Required");
        LinkedHashMap<FunctionParameterId, TypedValue> values = new LinkedHashMap<>(outputs.values());
        values.put(parameterId, typedValue);
        return new FunctionFrame(signature, context, inputs, new FunctionOutputMap(values), step);
    }

    public FunctionFrame withOutputs(FunctionOutputMap value) {
        return new FunctionFrame(signature, context, inputs, Objects.requireNonNull(value, "Function Outputs Are Required"), step);
    }

    public FunctionFrame nextStep() {
        if (step == CompiledFunctionBody.MAXIMUM_STEPS) {
            throw new IllegalStateException("Function Frame Cannot Advance Beyond The Maximum Step Count");
        }
        return new FunctionFrame(signature, context, inputs, outputs, step + 1);
    }

    private static List<FunctionDiagnostic> validatePresentOutputs(FunctionSignature signature, FunctionOutputMap outputs) {
        Map<FunctionParameterId, FunctionParameterContract> declarations = signature.outputs().stream()
            .collect(Collectors.toUnmodifiableMap(FunctionParameterContract::id, value -> value));
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        outputs.values().forEach((id, value) -> {
            FunctionParameterContract declaration = declarations.get(id);
            if (declaration == null) {
                diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_UNKNOWN", "output-validation",
                    "The output parameter is not declared by the function signature.", id));
            } else if (!declaration.type().equals(value.type())) {
                diagnostics.add(FunctionDiagnostic.error("FUNCTION.PARAMETER_TYPE_MISMATCH", "output-validation",
                    "The output parameter value type does not match the function signature.", id));
            }
        });
        return List.copyOf(diagnostics);
    }

    static final class ValidationException extends IllegalArgumentException {
        private final FunctionDiagnostic diagnostic;

        ValidationException(FunctionDiagnostic diagnostic) {
            super(diagnostic.message());
            this.diagnostic = diagnostic;
        }

        FunctionDiagnostic diagnostic() {
            return diagnostic;
        }
    }
}
