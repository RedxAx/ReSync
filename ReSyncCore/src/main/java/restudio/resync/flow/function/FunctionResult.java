package restudio.resync.flow.function;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;
import java.util.Collections;

import restudio.resync.flow.diagnostic.Diagnostic;

public record FunctionResult(
    FunctionSignature signature,
    Status status,
    FunctionOutputMap outputs,
    List<FunctionDiagnostic> diagnostics,
    int executedSteps,
    Map<String, Object> unknown
) {
    public FunctionResult {
        signature = Objects.requireNonNull(signature, "Function Result Signature Is Required");
        status = Objects.requireNonNull(status, "Function Result Status Is Required");
        outputs = Objects.requireNonNull(outputs, "Function Result Outputs Are Required");
        diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
            .map(diagnostic -> Objects.requireNonNull(diagnostic, "Function Result Diagnostics Cannot Contain Null Values"))
            .toList();
        unknown = FunctionContractSupport.immutableMap(unknown, "Function Result Unknown Data");
        if (executedSteps < 0 || executedSteps > CompiledFunctionBody.MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Function Result Step Count Is Out Of Bounds");
        }
        List<FunctionDiagnostic> outputDiagnostics = outputs.validate(signature);
        if (status == Status.SUCCESS && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A Successful Function Result Cannot Contain Diagnostics");
        }
        if (status == Status.SUCCESS && !outputDiagnostics.isEmpty()) {
            throw new IllegalArgumentException(outputDiagnostics.getFirst().message());
        }
        if (status != Status.SUCCESS && !outputs.values().isEmpty()) {
            throw new IllegalArgumentException("A Non-Successful Function Result Cannot Contain Typed Outputs");
        }
        if (status != Status.SUCCESS && diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A Failed Function Result Requires Diagnostics");
        }
    }

    public FunctionResult(FunctionSignature signature, Status status, FunctionOutputMap outputs,
                           List<FunctionDiagnostic> diagnostics, int executedSteps) {
        this(signature, status, outputs, diagnostics, executedSteps, Map.of());
    }

    public static FunctionResult success(FunctionSignature signature, FunctionOutputMap outputs, int executedSteps) {
        return new FunctionResult(signature, Status.SUCCESS, outputs, List.of(), executedSteps);
    }

    public static FunctionResult failure(FunctionSignature signature, List<FunctionDiagnostic> diagnostics, int executedSteps) {
        return new FunctionResult(signature, Status.FAILURE, FunctionOutputMap.empty(), diagnostics, executedSteps);
    }

    public static FunctionResult cancelled(FunctionSignature signature, FunctionDiagnostic diagnostic, int executedSteps) {
        return new FunctionResult(signature, Status.CANCELLED, FunctionOutputMap.empty(), List.of(diagnostic), executedSteps);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }

    public boolean cancelled() {
        return status == Status.CANCELLED;
    }

    public boolean failed() {
        return status == Status.FAILURE;
    }

    public List<Diagnostic> sharedDiagnostics() {
        return diagnostics.stream().map(FunctionDiagnostic::diagnostic).toList();
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("diagnostics", diagnostics.stream().map(FunctionDiagnostic::canonicalValue).toList());
        value.put("executedSteps", executedSteps);
        value.put("outputs", outputs.canonicalValue());
        value.put("signature", signature.canonicalValue());
        value.put("status", status.wireName());
        unknown.forEach((key, item) -> {
            if (value.putIfAbsent(key, item) != null) {
                throw new IllegalArgumentException("Function Result Unknown Data Collides With Known Field: " + key);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }

    public static FunctionResult fromCanonical(FunctionSignature signature, Map<String, ?> value) {
        Objects.requireNonNull(signature, "Function Result Signature Is Required");
        Objects.requireNonNull(value, "Function Result Canonical Value Is Required");
        Status status = status(value.get("status"));
        int executedSteps = executedSteps(value.get("executedSteps"));
        Object rawDiagnostics = value.get("diagnostics");
        if (!(rawDiagnostics instanceof List<?> entries)) {
            throw new IllegalArgumentException("Function Result Diagnostics Must Be An Array");
        }
        List<FunctionDiagnostic> diagnostics = entries.stream().map(entry -> {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Function Result Diagnostic Must Be An Object");
            }
            return FunctionDiagnostic.fromCanonical(stringMap(map, "diagnostic"));
        }).toList();
        Object rawOutputs = value.get("outputs");
        if (!(rawOutputs instanceof Map<?, ?> outputs) || !outputs.isEmpty()) {
            throw new IllegalArgumentException("Function Result Output Reader Requires A Typed Output Codec");
        }
        return new FunctionResult(signature, status, FunctionOutputMap.empty(), diagnostics, executedSteps,
            unknown(value));
    }

    public static FunctionResult fromLegacyCanonical(FunctionSignature signature, Map<String, ?> value) {
        return fromCanonical(signature, value);
    }

    private static Status status(Object value) {
        if (!(value instanceof String wire)) {
            throw new IllegalArgumentException("Function Result Status Must Be Text");
        }
        for (Status candidate : Status.values()) {
            if (candidate.wireName().equals(wire)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown Function Result Status: " + wire);
    }

    private static int executedSteps(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Function Result Executed Steps Must Be An Integer");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Function Result Executed Steps Must Be An Integer", exception);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> value, String field) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(field + " Keys Must Be Text");
            }
            result.put(text, item);
        });
        return result;
    }

    private static Map<String, Object> unknown(Map<String, ?> value) {
        Set<String> known = Set.of("status", "executedSteps", "outputs", "signature", "diagnostics");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!known.contains(key)) {
                result.put(key, item);
            }
        });
        return result;
    }

    public enum Status {
        SUCCESS("success"),
        FAILURE("failure"),
        CANCELLED("cancelled");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
