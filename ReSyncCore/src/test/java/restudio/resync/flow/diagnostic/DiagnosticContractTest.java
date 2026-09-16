package restudio.resync.flow.diagnostic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticContractTest {
    private static final ServerId SERVER_ID = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final NodeId NODE_ID = new NodeId("node-main");
    private static final PinId PIN_ID = new PinId("input");
    private static final ContractRef<ResourceTypeId> RESOURCE_TYPE = new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("flow"));
    private static final UUID CORRELATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TRACE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void modelContainsGate0BContextAndIsDeeplyImmutable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("zeta", List.of("last"));
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("nested", nested);

        Diagnostic diagnostic = Diagnostic.builder("GRAPH.PIN_UNRESOLVED", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(messageKey("graph-pin-unresolved"))
            .message("The graph pin cannot be resolved.")
            .evidence(evidence)
            .remediation("Reconnect the wire to a valid pin.")
            .correlationId(CORRELATION_ID)
            .traceId(TRACE_ID)
            .serverId(SERVER_ID)
            .resource(new ServerResourceLocator(SERVER_ID, RESOURCE_TYPE, "main"))
            .nodeId(NODE_ID)
            .pinId(PIN_ID)
            .catalogGeneration(7)
            .provenance(new DiagnosticProvenance(new OwnerId("restudio.resync"), DiagnosticSourceKind.BUNDLED, "classpath:/catalog.json?token=private", HASH, "1.0", "build-7", Instant.parse("2026-08-02T00:00:00Z")))
            .durable(true)
            .redaction(DiagnosticRedaction.TECHNICAL)
            .metricPolicy(DiagnosticMetricPolicy.COUNT)
            .build();

        nested.put("mutated", true);
        evidence.put("later", true);

        assertFalse(diagnostic.evidence().containsKey("later"));
        assertEquals(new OwnerId("restudio.resync"), diagnostic.ownerId());
        assertEquals(SERVER_ID, diagnostic.serverId());
        assertEquals(RESOURCE_TYPE, diagnostic.resource().type());
        assertEquals(PIN_ID, diagnostic.pinId());
        assertEquals(7L, diagnostic.catalogGeneration());
        assertThrows(UnsupportedOperationException.class, () -> diagnostic.evidence().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) diagnostic.evidence().get("nested")).clear());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) ((Map<?, ?>) diagnostic.evidence().get("nested")).get("zeta")).clear());

        Map<String, Object> exported = diagnostic.toMap();
        assertEquals("GRAPH.PIN_UNRESOLVED", exported.get("code"));
        assertEquals("semantic", exported.get("phase"));
        assertEquals(CORRELATION_ID.toString(), exported.get("correlationId"));
        assertTrue(exported.containsKey("provenance"));
    }

    @Test
    void redactedExportRemovesSecretEvidenceAndSensitiveProvenance() {
        Diagnostic diagnostic = Diagnostic.builder("RUNTIME.HANDLER_FAILURE", DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(messageKey("runtime-handler-failure"))
            .message("The handler failed while executing the node.")
            .evidence(Map.of("exception", "IllegalStateException", "access_token", "do-not-export", "attempt", 2))
            .remediation("Inspect the correlated handler failure.")
            .correlationId(CORRELATION_ID)
            .traceId(TRACE_ID)
            .provenance(new DiagnosticProvenance(new OwnerId("restudio.resync"), DiagnosticSourceKind.EXTENSION, "https://example.test/report?token=private", HASH, "2.0", "build-8", null))
            .redaction(DiagnosticRedaction.SECRET)
            .build();

        String redacted = diagnostic.toRedactedJson();
        assertTrue(redacted.contains("[REDACTED]"));
        assertFalse(redacted.contains("do-not-export"));
        assertFalse(redacted.contains("token=private"));
        assertFalse(redacted.contains(TRACE_ID.toString()));
        assertTrue(redacted.contains(CORRELATION_ID.toString()));
        assertFalse(redacted.contains("IllegalStateException"));
    }

    @Test
    void diagnosticSetHasStableOrderingAndExport() {
        Diagnostic first = diagnostic("PROTO.SEQUENCE_STALE", "ordering", CORRELATION_ID);
        Diagnostic second = diagnostic("CATALOG.CONTRIBUTION_REJECTED", "catalog", TRACE_ID);

        DiagnosticSet forward = new DiagnosticSet(List.of(first, second));
        DiagnosticSet reversed = new DiagnosticSet(List.of(second, first));

        assertEquals(forward.diagnostics(), reversed.diagnostics());
        assertEquals(forward.toRedactedJson(), reversed.toRedactedJson());
        assertEquals("CATALOG.CONTRIBUTION_REJECTED", forward.diagnostics().getFirst().code());
        assertTrue(forward.toRedactedJson().startsWith("["));
    }

    @Test
    void invalidSchemaValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Diagnostic.builder("bad-code", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(messageKey("message"))
            .message("message")
            .remediation("remediation")
            .correlationId(CORRELATION_ID)
            .build());
        assertThrows(IllegalArgumentException.class, () -> new ServerResourceLocator(SERVER_ID, RESOURCE_TYPE, "bad/id"));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticProvenance(null, DiagnosticSourceKind.BUNDLED, "classpath:/catalog.json", "not-a-hash", "1", "build", null));
    }

    private Diagnostic diagnostic(String code, String stage, UUID correlationId) {
        return Diagnostic.builder(code, DiagnosticSeverity.WARNING, DiagnosticPhase.SEMANTIC, stage)
            .messageKey(messageKey(code.toLowerCase().replace('.', '-')))
            .message("A structured diagnostic was produced.")
            .remediation("Reconcile the authoritative state.")
            .correlationId(correlationId)
            .build();
    }

    private ContractRef<NodeId> messageKey(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new NodeId(localId));
    }
}
