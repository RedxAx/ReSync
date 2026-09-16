package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledFunctionRunnerTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("function"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("restudio.resync", "string"));
    private static final TypeExpr INTEGER = TypeExpr.named(TypeReference.of("restudio.resync", "integer"));
    private static final FunctionParameterId INPUT_ID = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT_ID = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));

    @Test
    void missingRequiredInputFailsBeforeBodyExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> {
                executed.set(true);
                return frame.withOutput(OUTPUT_ID, TypedValue.value(TEXT, "done"));
            }));

        FunctionResult result = new CompiledFunctionRunner().run(function, context(signature, FunctionInputMap.empty()));

        assertTrue(result.failed());
        assertFalse(executed.get());
        assertEquals(0, result.executedSteps());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("FUNCTION.PARAMETER_REQUIRED")));
    }

    @Test
    void typedOutputIsReturnedAndImmutable() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        FunctionInputMap inputs = new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello")));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID))));

        FunctionResult result = new CompiledFunctionRunner().run(function, context(signature, inputs));

        assertTrue(result.successful());
        assertEquals(TypedValue.value(TEXT, "hello"), result.outputs().value(OUTPUT_ID));
        assertThrows(UnsupportedOperationException.class, () -> result.outputs().values().clear());
    }

    @Test
    void invalidTypedOutputFailsClosedWithoutReturningPartialValues() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> frame.withOutput(OUTPUT_ID, TypedValue.value(INTEGER, 7))));

        FunctionResult result = new CompiledFunctionRunner().run(function,
            context(signature, new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello")))));

        assertTrue(result.failed());
        assertTrue(result.outputs().values().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("FUNCTION.PARAMETER_TYPE_MISMATCH")));
    }

    @Test
    void cancellationStopsBodyAndProducesStructuredResult() {
        AtomicBoolean executed = new AtomicBoolean();
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> {
                executed.set(true);
                return frame.withOutput(OUTPUT_ID, TypedValue.value(TEXT, "done"));
            }));
        FunctionRuntimeContext context = context(signature, new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello"))))
            .withCancellation(FunctionCancellation.cancelled("caller"));

        FunctionResult result = new CompiledFunctionRunner().run(function, context);

        assertTrue(result.cancelled());
        assertFalse(executed.get());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.CANCELLED", result.diagnostics().getFirst().code());
    }

    @Test
    void executionBudgetFailsClosedBeforeAnOverlongBody() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunctionBody body = new CompiledFunctionBody(List.of(
            frame -> frame,
            frame -> frame.withOutput(OUTPUT_ID, TypedValue.value(TEXT, "done"))), 2);
        CompiledFunction function = new CompiledFunction(signature, body);

        FunctionResult result = new CompiledFunctionRunner(1).run(function,
            context(signature, new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello")))));

        assertTrue(result.failed());
        assertEquals("FUNCTION.BODY_LIMIT", result.diagnostics().getFirst().code());
        assertEquals(0, result.executedSteps());
    }

    @Test
    void stepFailureProducesAStableDiagnosticAndNoOutput() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> {
                throw new IllegalStateException("broken step");
            }));

        FunctionResult result = new CompiledFunctionRunner().run(function,
            context(signature, new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello")))));

        assertTrue(result.failed());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.STEP_FAILURE", result.diagnostics().getFirst().code());
        assertEquals("java.lang.IllegalStateException", result.diagnostics().getFirst().evidence().get("exceptionType"));
    }

    @Test
    void compiledBodyRemainsImmutable() {
        CompiledFunctionBody body = CompiledFunctionBody.of(frame -> frame);

        assertThrows(UnsupportedOperationException.class, () -> body.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> body.metadata().put("changed", true));
    }

    @Test
    void requiredDefaultIsMaterializedForTypedExecution() {
        FunctionParameterContract input = new FunctionParameterContract(INPUT_ID, TEXT, true, TypedValue.value(TEXT, "default"));
        FunctionSignature signature = signature(input, new FunctionParameterContract(OUTPUT_ID, TEXT, true));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID))));

        FunctionResult result = new CompiledFunctionRunner().run(function, context(signature, FunctionInputMap.empty()));

        assertTrue(result.successful());
        assertEquals(TypedValue.value(TEXT, "default"), result.outputs().value(OUTPUT_ID));
    }

    private FunctionSignature signature(FunctionParameterContract input, FunctionParameterContract output) {
        return new FunctionSignature(new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome")),
            new FunctionRevision(4), List.of(input), List.of(output));
    }

    private FunctionRuntimeContext context(FunctionSignature signature, FunctionInputMap inputs) {
        return new FunctionRuntimeContext(signature.function(), signature.revision(),
            UUID.fromString("44444444-4444-4444-8444-444444444444"), inputs);
    }
}
