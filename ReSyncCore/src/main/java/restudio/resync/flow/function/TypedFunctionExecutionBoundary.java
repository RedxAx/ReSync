package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TypedFunctionExecutionBoundary {
    private final CompiledFunctionResolver resolver;
    private final CompiledFunctionRunner runner;

    public TypedFunctionExecutionBoundary(CompiledFunctionResolver resolver) {
        this(resolver, new CompiledFunctionRunner());
    }

    public TypedFunctionExecutionBoundary(CompiledFunctionResolver resolver, CompiledFunctionRunner runner) {
        this.resolver = Objects.requireNonNull(resolver, "Compiled Function Resolver Is Required");
        this.runner = Objects.requireNonNull(runner, "Compiled Function Runner Is Required");
    }

    public FunctionResult execute(FunctionExecutionRequest request) {
        Objects.requireNonNull(request, "Function Execution Request Is Required");
        FunctionSignature expected = request.signature();
        Optional<CompiledFunction> resolved;
        try {
            resolved = resolver.resolve(request.function(), request.revision());
            if (resolved == null) {
                return failure(expected, request, "FUNCTION.RESOLVER_FAILURE", "function-resolution",
                    "The compiled function resolver returned no resolution result.", Map.of("resolverResult", "null"));
            }
        } catch (RuntimeException failure) {
            return failure(expected, request, "FUNCTION.RESOLVER_FAILURE", "function-resolution",
                "The compiled function could not be resolved.", Map.of("exceptionType", failure.getClass().getName()));
        }
        if (resolved.isEmpty()) {
            return failure(expected, request, "FUNCTION.NOT_FOUND", "function-resolution",
                "The requested compiled function revision is not available.", Map.of(
                    "function", request.function().canonicalText(),
                    "revision", request.revision().value()));
        }
        CompiledFunction function = resolved.get();
        FunctionSignature actual = function.signature();
        if (!expected.function().equals(actual.function()) || !expected.revision().equals(actual.revision())) {
            return failure(expected, request, "FUNCTION.RESOLUTION_MISMATCH", "function-resolution",
                "The resolver returned a different function locator or revision.", Map.of(
                    "expectedFunction", expected.function().canonicalText(),
                    "expectedRevision", expected.revision().value(),
                    "actualFunction", actual.function().canonicalText(),
                    "actualRevision", actual.revision().value()));
        }
        if (!expected.equals(actual)) {
            return failure(expected, request, "FUNCTION.SIGNATURE_MISMATCH", "signature-validation",
                "The resolved function signature does not match the requested typed contract.", Map.of(
                    "function", expected.function().canonicalText(),
                    "revision", expected.revision().value()));
        }
        try {
            return runner.run(function, request.context());
        } catch (RuntimeException failure) {
            return failure(expected, request, "FUNCTION.EXECUTION_FAILURE", "function-execution",
                "The compiled function could not complete execution.", Map.of("exceptionType", failure.getClass().getName()));
        }
    }

    private static FunctionResult failure(FunctionSignature signature, FunctionExecutionRequest request,
                                          String code, String stage, String message, Map<String, Object> evidence) {
        FunctionDiagnostic diagnostic = new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR,
            FunctionDiagnostic.Phase.ENVIRONMENT, stage, message,
            "Inspect the requested function revision and retry after correcting the compiled function boundary.",
            signature.function(), signature.revision(), null, request.invocationId(), null, Map.of(), evidence, false);
        return FunctionResult.failure(signature, List.of(diagnostic), 0);
    }
}
