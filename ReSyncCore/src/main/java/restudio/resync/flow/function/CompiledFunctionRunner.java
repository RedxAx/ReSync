package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypedValue;

public final class CompiledFunctionRunner {
    public static final int DEFAULT_MAXIMUM_STEPS = CompiledFunctionBody.MAXIMUM_STEPS;

    private final int maximumSteps;
    private final LongSupplier clock;

    public CompiledFunctionRunner() {
        this(DEFAULT_MAXIMUM_STEPS, System::currentTimeMillis);
    }

    public CompiledFunctionRunner(int maximumSteps) {
        this(maximumSteps, System::currentTimeMillis);
    }

    public CompiledFunctionRunner(int maximumSteps, LongSupplier clock) {
        if (maximumSteps < 0 || maximumSteps > CompiledFunctionBody.MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Compiled Function Runner Maximum Step Count Is Invalid");
        }
        this.maximumSteps = maximumSteps;
        this.clock = Objects.requireNonNull(clock, "Compiled Function Runner Clock Is Required");
    }

    public FunctionResult run(CompiledFunction function, FunctionRuntimeContext context) {
        Objects.requireNonNull(function, "Compiled Function Is Required");
        Objects.requireNonNull(context, "Function Runtime Context Is Required");
        FunctionSignature signature = function.signature();
        FunctionInputMap inputs = materializeInputs(signature, context.inputs());
        List<FunctionDiagnostic> contextDiagnostics = validateContext(signature, context, inputs);
        if (!contextDiagnostics.isEmpty()) {
            return FunctionResult.failure(signature, contextDiagnostics, 0);
        }
        FunctionRuntimeContext effectiveContext = new FunctionRuntimeContext(context.function(), context.revision(),
            context.invocationId(), inputs, context.cancellation(), context.attributes());
        FunctionCancellation cancellation = effectiveContext.cancellation();
        FunctionDiagnostic cancellationDiagnostic = cancellationDiagnostic(signature, cancellation);
        if (cancellationDiagnostic != null) {
            return FunctionResult.cancelled(signature, cancellationDiagnostic, 0);
        }
        FunctionFrame frame;
        try {
            frame = FunctionFrame.initial(signature, effectiveContext);
        } catch (Throwable failure) {
            return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.FRAME_INVALID",
                "frame-validation", "The compiled function frame is invalid.", failure)), 0);
        }
        int budget = Math.min(maximumSteps, function.body().maximumSteps());
        if (function.body().size() > budget) {
            return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.BODY_LIMIT",
                "body-validation", "The compiled function body exceeds the execution step budget.", null)), 0);
        }
        for (int index = 0; index < function.body().steps().size(); index++) {
            cancellationDiagnostic = cancellationDiagnostic(signature, effectiveContext.cancellation());
            if (cancellationDiagnostic != null) {
                return FunctionResult.cancelled(signature, cancellationDiagnostic, index);
            }
            CompiledFunctionBody.Step step = function.body().steps().get(index);
            try {
                FunctionFrame next = step.apply(frame);
                if (next == null) {
                    return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.STEP_INVALID",
                        "body-execution", "The compiled function step returned no frame.", null)), index);
                }
                if (!next.signature().equals(signature)) {
                    return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.FRAME_INVALID",
                        "body-execution", "The compiled function step returned a frame for another signature.", null)), index);
                }
                if (next.step() < index || next.step() > index + 1) {
                    return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.FRAME_INVALID",
                        "body-execution", "The compiled function step returned an out-of-order frame.", null)), index);
                }
                frame = next.step() == index + 1 ? next : next.nextStep();
            } catch (FunctionFrame.ValidationException failure) {
                return FunctionResult.failure(signature, withContext(signature, List.of(failure.diagnostic())), index);
            } catch (Throwable failure) {
                return FunctionResult.failure(signature, List.of(runtimeDiagnostic(signature, "FUNCTION.STEP_FAILURE",
                    "body-execution", "The compiled function step failed and execution was stopped.", failure)), index);
            }
        }
        List<FunctionDiagnostic> outputDiagnostics = frame.outputs().validate(signature);
        if (!outputDiagnostics.isEmpty()) {
            return FunctionResult.failure(signature, withContext(signature, outputDiagnostics), function.body().size());
        }
        return FunctionResult.success(signature, frame.outputs(), function.body().size());
    }

    public FunctionResult execute(CompiledFunction function, FunctionRuntimeContext context) {
        return run(function, context);
    }

    public FunctionResult run(FunctionSignature signature, CompiledFunctionBody body, FunctionRuntimeContext context) {
        return run(new CompiledFunction(signature, body), context);
    }

    public int maximumSteps() {
        return maximumSteps;
    }

    private static FunctionInputMap materializeInputs(FunctionSignature signature, FunctionInputMap provided) {
        LinkedHashMap<FunctionParameterId, TypedValue> values = new LinkedHashMap<>(provided.values());
        signature.inputs().forEach(parameter -> {
            if (!values.containsKey(parameter.id()) && parameter.defaultValue() != null) {
                values.put(parameter.id(), parameter.defaultValue());
            }
        });
        return new FunctionInputMap(values);
    }

    private static List<FunctionDiagnostic> validateContext(FunctionSignature signature,
                                                             FunctionRuntimeContext context,
                                                             FunctionInputMap inputs) {
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        if (!signature.function().equals(context.function()) || !signature.revision().equals(context.revision())) {
            diagnostics.add(runtimeDiagnostic(signature, "FUNCTION.CONTEXT_INVALID", "context-validation",
                "The function runtime context does not match the compiled function signature.", null));
        }
        diagnostics.addAll(withContext(signature, inputs.validate(signature)));
        return List.copyOf(diagnostics);
    }

    private FunctionDiagnostic cancellationDiagnostic(FunctionSignature signature, FunctionCancellation cancellation) {
        if (cancellation == null) {
            return runtimeDiagnostic(signature, "FUNCTION.CANCELLATION_INVALID", "cancellation-validation",
                "The function cancellation state is unavailable.", null);
        }
        if (cancellation.deadlineExceeded(clock.getAsLong())) {
            return new FunctionDiagnostic("FUNCTION.CANCELLED", FunctionDiagnostic.Severity.WARNING,
                FunctionDiagnostic.Phase.ENVIRONMENT, "cancellation", "The function deadline has expired.",
                "Retry the function with a valid execution deadline.", signature.function(), signature.revision(), null,
                null, null, Map.of("state", cancellation.state().wireName()), Map.of(), false);
        }
        if (cancellation.requested()) {
            return new FunctionDiagnostic("FUNCTION.CANCELLED", FunctionDiagnostic.Severity.WARNING,
                FunctionDiagnostic.Phase.ENVIRONMENT, "cancellation", "The function execution was cancelled.",
                "Retry the function when its caller is ready.", signature.function(), signature.revision(), null,
                null, null, Map.of("state", cancellation.state().wireName()), Map.of(), false);
        }
        return null;
    }

    private static List<FunctionDiagnostic> withContext(FunctionSignature signature, List<FunctionDiagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> new FunctionDiagnostic(diagnostic.code(), diagnostic.severity(),
            diagnostic.phase(), diagnostic.stage(), diagnostic.message(), diagnostic.remediation(), signature.function(),
            signature.revision(), diagnostic.parameterId(), diagnostic.correlationId(), diagnostic.traceId(),
            diagnostic.arguments(), diagnostic.evidence(), diagnostic.durable())).toList();
    }

    private static FunctionDiagnostic runtimeDiagnostic(FunctionSignature signature, String code, String stage,
                                                        String message, Throwable failure) {
        Map<String, Object> evidence = failure == null ? Map.of() : Map.of("exceptionType", failure.getClass().getName());
        return new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.ENVIRONMENT,
            stage, message, "Inspect the compiled function body and retry after correcting the typed contract.",
            signature.function(), signature.revision(), null, null, null, Map.of(), evidence, false);
    }
}
