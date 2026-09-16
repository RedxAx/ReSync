package restudio.resync.flow.function;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionDiagnosticContractTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("function"));
    private static final FunctionLocator FUNCTION = new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome"));
    private static final FunctionRevision REVISION = new FunctionRevision(4);
    private static final FunctionParameterId PARAMETER = FunctionParameterId.deterministic("input");

    @Test
    void catalogContainsExactlyTheFunctionDiagnosticFamily() {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();

        assertEquals(58, catalog.codes().stream().filter(code -> code.startsWith("FUNCTION.")).count());
        catalog.codes().stream().filter(code -> code.startsWith("FUNCTION.")).forEach(code -> {
            DiagnosticCodeCatalog.Definition definition = catalog.require(code);
            assertTrue(definition.messageKey().startsWith("diagnostic.function-"));
            assertFalse(definition.argumentTypes().isEmpty());
            assertTrue(definition.retryability() != DiagnosticCodeCatalog.Retryability.NEVER);
        });
    }

    @Test
    void functionAdapterUsesCatalogFieldsAndKeepsDynamicTextAsArguments() {
        FunctionDiagnostic diagnostic = new FunctionDiagnostic(
            "FUNCTION.PARAMETER_REQUIRED", FunctionDiagnostic.Severity.WARNING, FunctionDiagnostic.Phase.ENVIRONMENT,
            "caller-chosen-stage", "A caller-specific detail.", "caller-specific remediation.", FUNCTION, REVISION,
            PARAMETER, null, null, Map.of(), Map.of("scope", "input"), false);

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.diagnostic().severity());
        assertEquals(DiagnosticPhase.SYNTACTIC, diagnostic.diagnostic().phase());
        assertEquals("parameter-validation", diagnostic.stage());
        assertEquals("The required Function parameter is missing or absent.", diagnostic.message());
        assertEquals("Provide the required typed parameter before retrying.", diagnostic.remediation());
        assertEquals("A caller-specific detail.", diagnostic.arguments().get("detail"));
        assertEquals(DiagnosticCodeCatalog.Retryability.AFTER_CORRECTION, diagnostic.retryability());
        assertTrue(diagnostic.canonicalJson().contains("parameterId"));
        assertFalse(diagnostic.canonicalJson().contains("caller-specific remediation"));
        FunctionDiagnostic cancelled = new FunctionDiagnostic(
            "FUNCTION.CANCELLED", FunctionDiagnostic.Severity.WARNING, FunctionDiagnostic.Phase.ENVIRONMENT,
            "caller-stage", "The deadline expired.", "caller-remediation.", FUNCTION, REVISION, null,
            null, null, Map.of("state", "deadline"), Map.of(), false);
        assertEquals("deadline", cancelled.arguments().get("state"));
        assertEquals("The Function execution was cancelled.", cancelled.message());
        assertEquals("The deadline expired.", cancelled.arguments().get("detail"));
        assertThrows(IllegalArgumentException.class, () -> new FunctionDiagnostic(
            "FUNCTION.CANCELLED", FunctionDiagnostic.Severity.WARNING, FunctionDiagnostic.Phase.ENVIRONMENT,
            "stage", "message", "remediation", FUNCTION, REVISION, null, null, null,
            Map.of("undeclared", true), Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new FunctionDiagnostic(
            "FUNCTION.NOT_A_REAL_CODE", FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.SEMANTIC,
            "stage", "message", "remediation", FUNCTION, REVISION, null));
    }

    @Test
    void sharedDiagnosticReaderRetainsNestedUnknownsAndPolicies() {
        FunctionDiagnostic original = new FunctionDiagnostic(
            "FUNCTION.CANCELLED", FunctionDiagnostic.Severity.WARNING, FunctionDiagnostic.Phase.ENVIRONMENT,
            "legacy-stage", "legacy cancellation text", "legacy remediation", FUNCTION, REVISION, null,
            null, null, Map.of("state", "requested"), Map.of(), false);
        LinkedHashMap<String, Object> wire = new LinkedHashMap<>(original.canonicalValue());

        LinkedHashMap<String, Object> messageKey = new LinkedHashMap<>((Map<String, Object>) wire.get("messageKey"));
        messageKey.put("futureMessageKey", Map.of("nested", List.of(Map.of("enabled", true))));
        wire.put("messageKey", messageKey);

        LinkedHashMap<String, Object> resource = new LinkedHashMap<>((Map<String, Object>) wire.get("resource"));
        LinkedHashMap<String, Object> type = new LinkedHashMap<>((Map<String, Object>) resource.get("type"));
        type.put("futureType", Map.of("nested", Map.of("version", 2)));
        resource.put("type", type);
        resource.put("key", Map.of(
            "id", "welcome",
            "type", type,
            "futureKey", Map.of("nested", List.of("retained"))));
        resource.put("futureResource", Map.of("nested", Map.of("enabled", true)));
        wire.put("resource", resource);
        wire.put("nodeId", "node-context");
        wire.put("pinId", "pin-context");
        wire.put("catalogGeneration", 7);
        wire.put("redaction", DiagnosticRedaction.SENSITIVE.wireName());
        wire.put("metricPolicy", DiagnosticMetricPolicy.COUNT.wireName());
        wire.put("provenance", Map.of(
            "ownerId", "restudio.resync",
            "sourceKind", DiagnosticSourceKind.FUNCTION.wireName(),
            "sourceUri", "resync://function/welcome",
            "sourceHash", "a".repeat(64),
            "sourceVersion", "1.0.0",
            "buildId", "build-1",
            "loadedAt", Instant.parse("2026-08-11T00:00:00Z").toString(),
            "futureProvenance", Map.of("nested", List.of(Map.of("retained", true)))));
        wire.put("ownerId", "restudio.resync");
        LinkedHashMap<String, Object> futureDiagnostic = new LinkedHashMap<>();
        futureDiagnostic.put("nested", List.of(Map.of("retained", true)));
        futureDiagnostic.put("futureNull", null);
        wire.put("futureDiagnostic", futureDiagnostic);

        FunctionDiagnostic restored = FunctionDiagnostic.fromCanonical(wire);
        LinkedHashMap<String, Object> expectedShared = new LinkedHashMap<>(wire);
        expectedShared.remove("function");
        expectedShared.remove("revision");
        expectedShared.remove("parameterId");
        assertEquals(CanonicalJson.canonicalize(expectedShared), restored.diagnostic().toJson());
        assertEquals(Map.of("nested", Map.of("enabled", true)), restored.diagnostic().resource().unknown().get("futureResource"));
        assertEquals(Map.of("nested", List.of("retained")), restored.diagnostic().resource().key().unknown().get("futureKey"));
        assertEquals(CanonicalJson.canonicalize(Map.of("nested", Map.of("version", 2))),
            CanonicalJson.canonicalize(restored.diagnostic().resource().type().unknown().get("futureType")));
        assertEquals("sensitive", restored.diagnostic().redaction().wireName());
        assertEquals("count", restored.diagnostic().metricPolicy().wireName());
        assertEquals(7L, restored.diagnostic().catalogGeneration());
        assertEquals(CanonicalJson.canonicalize(futureDiagnostic),
            CanonicalJson.canonicalize(restored.diagnostic().unknown().get("futureDiagnostic")));
        assertTrue(restored.diagnostic().messageKey().unknown().containsKey("futureMessageKey"));
        assertTrue(restored.diagnostic().provenance().unknown().containsKey("futureProvenance"));
    }

    @Test
    void oldFunctionResultWireIsReadThroughTheSharedDiagnosticAdapter() {
        FunctionSignature signature = new FunctionSignature(FUNCTION, REVISION,
            List.of(new FunctionParameterContract(PARAMETER, TypeExpr.named(TypeReference.of("restudio.resync", "string")), true)), List.of());
        FunctionResult original = FunctionResult.failure(signature,
            List.of(new FunctionDiagnostic("FUNCTION.PARAMETER_REQUIRED", FunctionDiagnostic.Severity.ERROR,
                FunctionDiagnostic.Phase.SEMANTIC, "legacy-stage", "legacy text", "legacy remediation", FUNCTION,
                REVISION, PARAMETER)), 0);
        java.util.LinkedHashMap<String, Object> wire = new java.util.LinkedHashMap<>(original.canonicalValue());
        Map<String, Object> originalDiagnostic = (Map<String, Object>) ((List<?>) wire.get("diagnostics")).getFirst();
        java.util.LinkedHashMap<String, Object> diagnostic = new java.util.LinkedHashMap<>(originalDiagnostic);
        diagnostic.put("futureDiagnostic", Map.of("kept", true));
        wire.put("diagnostics", List.of(diagnostic));
        LinkedHashMap<String, Object> futureResult = new LinkedHashMap<>();
        futureResult.put("nested", List.of(Map.of("kept", true)));
        futureResult.put("futureNull", null);
        wire.put("futureResult", futureResult);

        FunctionResult restored = FunctionResult.fromLegacyCanonical(signature, wire);

        assertEquals(original.status(), restored.status());
        assertEquals(original.diagnostics().getFirst().code(), restored.diagnostics().getFirst().code());
        assertEquals(Map.of("kept", true), restored.diagnostics().getFirst().diagnostic().unknown().get("futureDiagnostic"));
        assertEquals(CanonicalJson.canonicalize(futureResult), CanonicalJson.canonicalize(restored.unknown().get("futureResult")));
        assertEquals(FUNCTION, restored.diagnostics().getFirst().function());
        assertEquals(REVISION, restored.diagnostics().getFirst().revision());
    }
}
