package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedFunctionExecutionBoundaryTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INVOCATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("function"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("restudio.resync", "string"));
    private static final TypeExpr INTEGER = TypeExpr.named(TypeReference.of("restudio.resync", "integer"));
    private static final FunctionParameterId INPUT_ID = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT_ID = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));

    @Test
    void resolvesByLocatorAndRevisionAndReturnsTypedOutputs() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(4));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID))));
        FunctionExecutionRequest request = new FunctionExecutionRequest(signature, INVOCATION,
            new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello"))));
        AtomicBoolean resolved = new AtomicBoolean();
        TypedFunctionExecutionBoundary boundary = new TypedFunctionExecutionBoundary((locator, revision) -> {
            resolved.set(locator.equals(signature.function()) && revision.equals(signature.revision()));
            return Optional.of(function);
        });

        FunctionResult result = boundary.execute(request);

        assertTrue(resolved.get());
        assertTrue(result.successful());
        assertEquals(TypedValue.value(TEXT, "hello"), result.outputs().value(OUTPUT_ID));
    }

    @Test
    void missingRevisionFailsClosedWithoutOutputs() {
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(4));
        FunctionExecutionRequest request = new FunctionExecutionRequest(signature, INVOCATION,
            new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello"))));

        FunctionResult result = new TypedFunctionExecutionBoundary((locator, revision) -> Optional.empty()).execute(request);

        assertTrue(result.failed());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.NOT_FOUND", result.diagnostics().getFirst().code());
        assertEquals(INVOCATION, result.diagnostics().getFirst().correlationId());
    }

    @Test
    void resolverRevisionMismatchFailsClosed() {
        FunctionSignature expected = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(4));
        FunctionSignature actual = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(5));
        CompiledFunction function = new CompiledFunction(actual,
            CompiledFunctionBody.of(frame -> frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID))));
        FunctionExecutionRequest request = new FunctionExecutionRequest(expected, INVOCATION,
            new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello"))));

        FunctionResult result = new TypedFunctionExecutionBoundary((locator, revision) -> Optional.of(function)).execute(request);

        assertTrue(result.failed());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.RESOLUTION_MISMATCH", result.diagnostics().getFirst().code());
    }

    @Test
    void cancellationStopsExecutionAndPreservesTypedBoundary() {
        AtomicBoolean executed = new AtomicBoolean();
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(4));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> {
                executed.set(true);
                return frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID));
            }));
        FunctionExecutionRequest request = new FunctionExecutionRequest(signature, INVOCATION,
            new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello"))),
            FunctionCancellation.cancelled("caller"), Map.of());

        FunctionResult result = new TypedFunctionExecutionBoundary((locator, revision) -> Optional.of(function)).execute(request);

        assertTrue(result.cancelled());
        assertFalse(executed.get());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.CANCELLED", result.diagnostics().getFirst().code());
    }

    @Test
    void invalidTypedInputFailsClosedBeforeBodyExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        FunctionSignature signature = signature(new FunctionParameterContract(INPUT_ID, TEXT, true),
            new FunctionParameterContract(OUTPUT_ID, TEXT, true), new FunctionRevision(4));
        CompiledFunction function = new CompiledFunction(signature,
            CompiledFunctionBody.of(frame -> {
                executed.set(true);
                return frame.withOutput(OUTPUT_ID, frame.input(INPUT_ID));
            }));
        FunctionExecutionRequest request = new FunctionExecutionRequest(signature, INVOCATION,
            new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(INTEGER, 7))));

        FunctionResult result = new TypedFunctionExecutionBoundary((locator, revision) -> Optional.of(function)).execute(request);

        assertTrue(result.failed());
        assertFalse(executed.get());
        assertTrue(result.outputs().values().isEmpty());
        assertEquals("FUNCTION.PARAMETER_TYPE_MISMATCH", result.diagnostics().getFirst().code());
    }

    private static FunctionSignature signature(FunctionParameterContract input, FunctionParameterContract output,
                                               FunctionRevision revision) {
        return new FunctionSignature(new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome")),
            revision, List.of(input), List.of(output));
    }
}
