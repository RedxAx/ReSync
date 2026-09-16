package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class RuntimeContractShapeTest {
    @Test
    void runtimeBindingRetainsItsImmutableExecutionFingerprint() {
        Map<String, Object> source = new LinkedHashMap<>(Map.of("value", "first"));
        RuntimeBindingDescriptor descriptor = binding(operation(Map.of()), Map.of("futureExecution", source));
        RuntimeBinding runtime = new RuntimeBinding(descriptor,
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        ContentHash expected = descriptor.executionFingerprint();
        ContentHash retained = runtime.executionFingerprint();
        assertEquals(expected, retained);
        for (int index = 0; index < 1000; index++) {
            assertSame(retained, runtime.executionFingerprint());
        }
        source.put("value", "changed");
        assertEquals(expected, descriptor.executionFingerprint());
        assertEquals(expected, runtime.executionFingerprint());
        RuntimeBindingDescriptor changed = binding(operation(Map.of()), Map.of("futureExecution", source));
        RuntimeBinding replacement = new RuntimeBinding(changed,
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        assertNotEquals(retained, replacement.executionFingerprint());
    }

    @Test
    void canonicalValuesUseReferenceObjectsAndPreserveUnknownData() {
        RuntimeOperationDescriptor operation = operation(Map.of("futureOperation", Map.of("enabled", true)));
        RuntimeBindingDescriptor binding = new RuntimeBindingDescriptor(
            operation.capability(), operation.operation(), provider("provider"), "1.0.0", operation.pins(),
            operation.semantics(), true, Map.of("futureBinding", "retained"));

        Map<?, ?> value = assertInstanceOf(Map.class, CanonicalJson.parse(binding.canonical()));
        assertInstanceOf(Map.class, value.get("capability"));
        assertInstanceOf(Map.class, value.get("operation"));
        assertInstanceOf(Map.class, value.get("provider"));
        assertEquals("retained", value.get("futureBinding"));
        assertEquals("retained", binding.unknown().get("futureBinding"));
        assertEquals(binding.executionFingerprint(), binding.fingerprint());
        assertEquals(binding.executionFingerprint(), binding.executionFingerprint());
    }

    @Test
    void typedCanonicalRoundTripPreservesUnknownNullAndSeparatesPortableValues() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("futureOperation", null);
        RuntimeOperationDescriptor operation = operation(unknown);
        RuntimeOperationDescriptor decodedOperation = RuntimeOperationDescriptor.fromCanonical(operation.canonical());
        RuntimeBindingDescriptor binding = binding(operation, Map.of());
        RuntimeBindingDescriptor decodedBinding = RuntimeBindingDescriptor.fromCanonical(binding.canonical());

        assertEquals(operation.canonical(), decodedOperation.canonical());
        assertEquals(binding.canonical(), decodedBinding.canonical());
        assertTrue(decodedOperation.unknown().containsKey("futureOperation"));
        assertTrue(decodedOperation.unknown().get("futureOperation") == null);
        assertFalse(operation.canonicalValue().containsKey("provider"));
        assertFalse(operation.canonicalValue().containsKey("providerVersion"));
        assertFalse(operation.canonicalValue().containsKey("available"));
        assertTrue(binding.canonicalValue().containsKey("provider"));
    }

    @Test
    void executionFingerprintExcludesPresentationUnknownsAndUsesInvalidationInputs() {
        RuntimeOperationDescriptor operation = operation(Map.of());
        RuntimeBindingDescriptor first = binding(operation, Map.of("displayName", "First"));
        RuntimeBindingDescriptor second = binding(operation, Map.of("displayName", "Second"));
        RuntimeBindingDescriptor firstExecution = binding(operation, Map.of("futureExecution", "First"));
        RuntimeBindingDescriptor secondExecution = binding(operation, Map.of("futureExecution", "Second"));

        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(firstExecution.fingerprint(), secondExecution.fingerprint());
        assertNotEquals(
            first.executionFingerprint(Map.of("definitionRevision", 1)),
            first.executionFingerprint(Map.of("definitionRevision", 2)));
    }

    @Test
    void manifestHashMatchesOnlyTheCatalogBindingManifestHash() {
        RuntimeOperationDescriptor operation = operation(Map.of());
        RuntimeBindingDescriptor binding = binding(operation, Map.of());
        RuntimeBindingManifest manifest = RuntimeBindingManifest.create(
            List.of(providerDescriptor()), List.of(binding), Map.of(binding.key(), binding.fingerprint()), List.of(),
            Map.of("futureManifest", List.of("retained")));
        ContentHash catalogChecksum = ContentHash.of("a".repeat(64));
        CatalogBinding catalogBinding = new CatalogBinding(1, catalogChecksum, manifest.bindingManifestHash());

        assertTrue(manifest.matches(catalogBinding));
        assertTrue(manifest.matchesBindingManifest(manifest.bindingManifestHash()));
        assertFalse(manifest.matches(new CatalogBinding(1, ContentHash.of("b".repeat(64)), ContentHash.of("c".repeat(64)))));
        assertEquals(List.of("retained"), manifest.unknown().get("futureManifest"));
        assertEquals(manifest.bindingManifestHash().canonicalText(), manifest.canonicalValue().get("bindingManifestHash"));
        assertTrue(manifest.wireCanonicalForm().contains("bindingManifestHash"));
        assertTrue(manifest.matches(new CatalogBinding(99, ContentHash.of("b".repeat(64)), manifest.bindingManifestHash())));
    }

    @Test
    void manifestRoundTripCarriesInvalidationInputsAndUnknowns() {
        RuntimeOperationDescriptor operation = operation(Map.of());
        RuntimeBindingDescriptor binding = binding(operation, Map.of());
        Map<String, Object> invalidation = Map.of("definitionRevision", 3);
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("futureManifest", null);
        RuntimeBindingManifest manifest = RuntimeBindingManifest.create(
            List.of(providerDescriptor()),
            List.of(binding),
            Map.of(binding.key(), binding.executionFingerprint(invalidation)),
            List.of(),
            unknown,
            Map.of(binding.key(), invalidation));

        RuntimeBindingManifest decoded = RuntimeBindingManifest.fromCanonical(manifest.wireCanonicalForm());

        assertEquals(manifest.wireCanonicalForm(), decoded.wireCanonicalForm());
        assertEquals(
            CanonicalJson.canonicalize(invalidation),
            CanonicalJson.canonicalize(decoded.invalidationInputs(binding.key()).orElseThrow()));
        assertTrue(decoded.unknown().containsKey("futureManifest"));
        assertTrue(decoded.unknown().get("futureManifest") == null);
    }

    @Test
    void wireRoundTripPreservesDiagnosticUnknownsAndRequiresHashes() {
        RuntimeOperationDescriptor operation = operation(Map.of());
        RuntimeBindingDescriptor binding = binding(operation, Map.of());
        Diagnostic diagnostic = Diagnostic.builder("RUNTIME.HANDLER_FAILURE", DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(capability("failure-message"))
            .message("Execution failed")
            .evidence(Map.of("detail", "retained"))
            .remediation("Retry the operation")
            .correlationId(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
            .serverId(new ServerId(UUID.fromString("123e4567-e89b-42d3-a456-426614174001")))
            .resource(new ServerResourceLocator(
                new ServerId(UUID.fromString("123e4567-e89b-42d3-a456-426614174001")),
                ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource")),
                "resource-id"))
            .provenance(new DiagnosticProvenance(
                new OwnerId("restudio.resync"),
                DiagnosticSourceKind.BUNDLED,
                "resync://runtime",
                "a".repeat(64),
                "1.0.0",
                "build-1",
                Instant.parse("2026-08-11T00:00:00Z")))
            .durable(true)
            .build();
        RuntimeBindingManifest base = RuntimeBindingManifest.create(
            List.of(providerDescriptor()), List.of(binding), Map.of(binding.key(), binding.fingerprint()), List.of(diagnostic));
        Map<String, Object> wire = new LinkedHashMap<>(base.canonicalValue());
        List<?> diagnostics = (List<?>) wire.get("diagnostics");
        Map<String, Object> diagnosticValue = new LinkedHashMap<>((Map<String, Object>) diagnostics.getFirst());
        diagnosticValue.put("futureDiagnostic", null);
        Map<String, Object> resource = new LinkedHashMap<>((Map<String, Object>) diagnosticValue.get("resource"));
        resource.put("futureResource", null);
        diagnosticValue.put("resource", resource);
        Map<String, Object> provenance = new LinkedHashMap<>((Map<String, Object>) diagnosticValue.get("provenance"));
        provenance.put("futureProvenance", null);
        diagnosticValue.put("provenance", provenance);
        wire.put("diagnostics", List.of(diagnosticValue));
        wire.remove("bindingManifestHash");
        wire.put("bindingManifestHash", CanonicalJson.sha256("runtime-manifest", wire));
        String wireValue = CanonicalJson.canonicalize(wire);
        RuntimeBindingManifest decoded = RuntimeBindingManifest.fromCanonical(wireValue);

        assertArrayEquals(
            wireValue.getBytes(StandardCharsets.UTF_8),
            decoded.wireCanonicalForm().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> decodedDiagnostic = decoded.diagnosticCanonicalValues().getFirst();
        assertTrue(decodedDiagnostic.containsKey("futureDiagnostic"));
        assertTrue(((Map<?, ?>) decodedDiagnostic.get("resource")).containsKey("futureResource"));
        assertTrue(((Map<?, ?>) decodedDiagnostic.get("provenance")).containsKey("futureProvenance"));
        assertTrue(decoded.diagnostics().getFirst().resource().unknown().containsKey("futureResource"));

        Map<String, Object> missingManifestHash = new LinkedHashMap<>(wire);
        missingManifestHash.remove("bindingManifestHash");
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeBindingManifest.fromCanonical(CanonicalJson.canonicalize(missingManifestHash)));
        Map<String, Object> tamperedBinding = new LinkedHashMap<>((Map<String, Object>) ((List<?>) wire.get("bindings")).getFirst());
        tamperedBinding.put("fingerprint", "0".repeat(64));
        Map<String, Object> tamperedWire = new LinkedHashMap<>(wire);
        tamperedWire.put("bindings", List.of(tamperedBinding));
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeBindingManifest.fromCanonical(CanonicalJson.canonicalize(tamperedWire)));
    }

    @Test
    void runtimeIdentifiersAreValidatedWithoutTrimmingAndUnorderedUnknownsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeFailureContract(
            type("failure"), Set.of(" RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeFailureContract(
            type("failure"), Set.of("RUNTIME.FAILURE "), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_READING,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability("authorization"),
            RuntimeSemantics.Cancellation.COOPERATIVE,
            1_000,
            100,
            2_000,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.MUTATION_ID,
            RuntimeSemantics.Audit.METADATA,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of(" success"),
            Set.of("failure"),
            Set.of("cancelled"),
            new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC),
            Set.of(),
            Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProviderDescriptor(
            provider("provider"), " 1.0.0", 100, 200, RuntimeSemantics.UnloadPolicy.DRAIN));
        Map<String, Object> unordered = new LinkedHashMap<>();
        unordered.put("future", Set.of("value"));
        assertThrows(IllegalArgumentException.class, () -> operation(unordered));
    }

    @Test
    void legacyTypeOnlyConstructorsAreRetiredAndExplicitPinConstructorsRemainPublic() throws ReflectiveOperationException {
        assertThrows(NoSuchMethodException.class, () -> RuntimeOperationDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, List.class, List.class, RuntimeSemantics.class));
        assertThrows(NoSuchMethodException.class, () -> RuntimeOperationDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, List.class, List.class, RuntimeSemantics.class, Map.class));
        assertThrows(NoSuchMethodException.class, () -> RuntimeBindingDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, ContractRef.class, String.class, List.class, List.class,
            RuntimeSemantics.class, boolean.class));
        assertThrows(NoSuchMethodException.class, () -> RuntimeBindingDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, ContractRef.class, String.class, List.class, List.class,
            RuntimeSemantics.class, boolean.class, Map.class));
        assertTrue(Modifier.isPublic(RuntimeOperationDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, List.class, RuntimeSemantics.class).getModifiers()));
        assertTrue(Modifier.isPublic(RuntimeOperationDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, List.class, RuntimeSemantics.class, Map.class).getModifiers()));
        assertTrue(Modifier.isPublic(RuntimeBindingDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, ContractRef.class, String.class, List.class,
            RuntimeSemantics.class, boolean.class).getModifiers()));
        assertTrue(Modifier.isPublic(RuntimeBindingDescriptor.class.getDeclaredConstructor(
            ContractRef.class, ContractRef.class, ContractRef.class, String.class, List.class,
            RuntimeSemantics.class, boolean.class, Map.class).getModifiers()));
    }

    @Test
    void legacyRuntimeFailureFixtureRemainsAClosedCatalogMember() {
        DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require("RUNTIME.FAILURE");
        assertEquals(DiagnosticSeverity.ERROR, definition.severity());
        assertEquals(DiagnosticPhase.ENVIRONMENT, definition.phase());
        assertEquals("failure", definition.stage());
        assertTrue(definition.argumentTypes().containsKey("detail"));
        RuntimeFailureContract contract = new RuntimeFailureContract(
            type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC);
        assertTrue(contract.acceptsDiagnosticCode("RUNTIME.FAILURE"));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeFailureContract(
            type("failure"), Set.of("RUNTIME.NOT_CATALOGUED"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC));
    }

    private static RuntimeBindingDescriptor binding(RuntimeOperationDescriptor operation, Map<String, ?> unknown) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.putAll(unknown);
        return new RuntimeBindingDescriptor(
            operation.capability(), operation.operation(), provider("provider"), "1.0.0", operation.pins(),
            operation.semantics(), true, values);
    }

    private static RuntimeOperationDescriptor operation(Map<String, ?> unknown) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.putAll(unknown);
        return new RuntimeOperationDescriptor(
            capability("capability"), operationId("operation"), List.of(
                new RuntimeOperationDescriptor.Pin("input", RuntimeOperationDescriptor.Direction.INPUT, type("string")),
                new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            semantics(), values);
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_READING,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability("authorization"),
            RuntimeSemantics.Cancellation.COOPERATIVE,
            1_000,
            100,
            2_000,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.MUTATION_ID,
            RuntimeSemantics.Audit.METADATA,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of("cancelled"),
            new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC),
            Set.of(),
            Set.of());
    }

    private static RuntimeProviderDescriptor providerDescriptor() {
        return new RuntimeProviderDescriptor(provider("provider"), "1.0.0", 100, 200, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static ContractRef<CapabilityId> capability(String localId) {
        return ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of(localId));
    }

    private static ContractRef<OperationId> operationId(String localId) {
        return ContractRef.of(new OwnerId("restudio.resync"), OperationId.of(localId));
    }

    private static ContractRef<ProviderId> provider(String localId) {
        return ContractRef.of(new OwnerId("restudio.resync"), ProviderId.of(localId));
    }

    private static TypeExpr type(String localId) {
        return TypeExpr.named(TypeReference.of("restudio.resync", localId));
    }
}
