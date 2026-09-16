package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimePinSignatureTest {
    @Test
    void pinIdentityChangesOperationAndBindingFingerprints() {
        RuntimeOperationDescriptor first = operation(List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, stringType())));
        RuntimeOperationDescriptor renamed = operation(List.of(
            new RuntimeOperationDescriptor.Pin("renamed", RuntimeOperationDescriptor.Direction.INPUT, stringType())));
        RuntimeBindingDescriptor firstBinding = new RuntimeBindingDescriptor(first, provider(), "1.0.0", true);
        RuntimeBindingDescriptor renamedBinding = new RuntimeBindingDescriptor(renamed, provider(), "1.0.0", true);

        assertNotEquals(first, renamed);
        assertNotEquals(first.executionFingerprint(), renamed.executionFingerprint());
        assertNotEquals(firstBinding, renamedBinding);
        assertNotEquals(firstBinding.fingerprint(), renamedBinding.fingerprint());
    }

    @Test
    void displayOnlyUnknownMetadataDoesNotChangeExecutionFingerprints() {
        RuntimeOperationDescriptor first = operation(
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, stringType())),
            Map.of("displayName", "First"));
        RuntimeOperationDescriptor second = operation(
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, stringType())),
            Map.of("displayName", "Second"));
        RuntimeOperationDescriptor pinOnly = operation(List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, stringType())));
        RuntimeBindingDescriptor firstBinding = new RuntimeBindingDescriptor(pinOnly, provider(), "1.0.0", true, Map.of("displayName", "First"));
        RuntimeBindingDescriptor secondBinding = new RuntimeBindingDescriptor(pinOnly, provider(), "1.0.0", true, Map.of("displayName", "Second"));

        assertEquals(first.executionFingerprint(), second.executionFingerprint());
        assertEquals(firstBinding.fingerprint(), secondBinding.fingerprint());
    }

    @Test
    void canonicalRoundTripPreservesExactPinIdsAndOrder() {
        List<RuntimeOperationDescriptor.Pin> pins = List.of(
            new RuntimeOperationDescriptor.Pin("first", RuntimeOperationDescriptor.Direction.INPUT, stringType()),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, integerType()));
        RuntimeOperationDescriptor operation = operation(pins);
        RuntimeBindingDescriptor binding = new RuntimeBindingDescriptor(operation, provider(), "1.0.0", true);

        RuntimeOperationDescriptor decodedOperation = RuntimeOperationDescriptor.fromCanonical(operation.canonical());
        RuntimeBindingDescriptor decodedBinding = RuntimeBindingDescriptor.fromCanonical(binding.canonical());

        assertEquals(pins, decodedOperation.pins());
        assertEquals(pins, decodedBinding.pins());
        assertEquals(operation.canonical(), decodedOperation.canonical());
        assertEquals(binding.canonical(), decodedBinding.canonical());
    }

    @Test
    void duplicateAndWrongDirectionPinsAreRejected() {
        RuntimeOperationDescriptor.Pin input = new RuntimeOperationDescriptor.Pin(
            "value", RuntimeOperationDescriptor.Direction.INPUT, stringType());
        RuntimeOperationDescriptor.Pin duplicate = new RuntimeOperationDescriptor.Pin(
            "value", RuntimeOperationDescriptor.Direction.OUTPUT, integerType());
        assertThrows(IllegalArgumentException.class, () -> operation(List.of(input, duplicate)));

        List<RuntimeOperationDescriptor.Pin> wrongDirection = List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.OUTPUT, stringType()),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, integerType()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeOperationDescriptor(
            capability(), operationId(), List.of(stringType()), List.of(integerType()), semantics(), Map.of(), wrongDirection));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeBindingDescriptor(
            capability(), operationId(), provider(), "1.0.0", List.of(stringType()), List.of(integerType()),
            semantics(), true, Map.of(), wrongDirection));
    }

    private static RuntimeOperationDescriptor operation(List<RuntimeOperationDescriptor.Pin> pins) {
        return operation(pins, Map.of());
    }

    private static RuntimeOperationDescriptor operation(
        List<RuntimeOperationDescriptor.Pin> pins,
        Map<String, ?> unknown
    ) {
        return new RuntimeOperationDescriptor(capability(), operationId(), pins, semantics(), unknown);
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability(),
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.NONE,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of(),
            new RuntimeFailureContract(stringType(), Set.of("RUNTIME.FAILURE"), Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static ContractRef<CapabilityId> capability() {
        return ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("runtime"));
    }

    private static ContractRef<OperationId> operationId() {
        return ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("execute"));
    }

    private static ContractRef<ProviderId> provider() {
        return ContractRef.of(new OwnerId("restudio.resync"), ProviderId.of("provider"));
    }

    private static TypeExpr stringType() {
        return TypeExpr.named(TypeReference.of("builtin", "string"));
    }

    private static TypeExpr integerType() {
        return TypeExpr.named(TypeReference.of("builtin", "integer"));
    }
}
