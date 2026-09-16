package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.resource.ResourcePayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.contract.canonical.JsonValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolDiagnosticCodecTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MUTATION_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MESSAGE_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE_UUID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Test
    void diagnosticWireRoundTripPreservesSharedMap() {
        ResourcePayloadCodec<Map<String, Object>> payloadCodec = ResourcePayloadCodecs.json();
        ServerResourceLocator resource = resource();
        CanonicalPayload<Map<String, Object>> payload = payloadCodec.canonicalize(Map.of("name", "draft"));
        ResourceSaveRequest<Map<String, Object>> save = new ResourceSaveRequest<>(resource, 4, payload, MUTATION_UUID);
        Diagnostic diagnostic = diagnostic(resource);
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(resource, payload.checksum(), save, List.of(diagnostic));
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(payloadCodec);

        byte[] encoded = codec.encodeBytes(envelope);
        ProtocolEnvelope<Map<String, Object>> decoded = codec.decodeBytes(encoded);

        assertArrayEquals(encoded, codec.encodeBytes(decoded));
        Diagnostic decodedDiagnostic = decoded.diagnostics().getFirst();
        assertEquals(diagnostic.code(), decodedDiagnostic.code());
        assertEquals(JsonValue.fromJava(diagnostic.arguments()).canonicalText(),
            JsonValue.fromJava(decodedDiagnostic.arguments()).canonicalText());
        assertEquals(JsonValue.fromJava(diagnostic.evidence()).canonicalText(),
            JsonValue.fromJava(decodedDiagnostic.evidence()).canonicalText());
        assertEquals(diagnostic.context(), decodedDiagnostic.context());
        assertEquals(diagnostic.provenance(), decodedDiagnostic.provenance());
        assertEquals(diagnostic.redaction(), decodedDiagnostic.redaction());
        assertEquals(diagnostic.metricPolicy(), decodedDiagnostic.metricPolicy());
        assertEquals(diagnostic.correlationId(), decodedDiagnostic.correlationId());
        assertEquals(diagnostic.traceId(), decodedDiagnostic.traceId());
        assertEquals(diagnostic.durable(), decodedDiagnostic.durable());
        assertEquals(Map.of("futureDiagnostic", Map.of("preserved", true)), decodedDiagnostic.unknown());
        assertEquals(Map.of("messageFuture", true), decodedDiagnostic.messageKey().unknown());
        assertEquals(Map.of("typeFuture", true), decodedDiagnostic.resource().type().unknown());
        assertEquals(Map.of("keyFuture", true), decodedDiagnostic.resource().key().unknown());
        assertEquals(Map.of("locatorFuture", List.of("preserved")), decodedDiagnostic.resource().unknown());
    }

    @Test
    void diagnosticDecoderRejectsDefinitionDriftAndMalformedMaps() {
        ResourcePayloadCodec<Map<String, Object>> payloadCodec = ResourcePayloadCodecs.json();
        ServerResourceLocator resource = resource();
        CanonicalPayload<Map<String, Object>> payload = payloadCodec.canonicalize(Map.of("name", "draft"));
        ResourceSaveRequest<Map<String, Object>> save = new ResourceSaveRequest<>(resource, 4, payload, MUTATION_UUID);
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(payloadCodec);
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(resource, payload.checksum(), save, List.of(diagnostic(resource)));
        Map<String, Object> base = new LinkedHashMap<>(javaMap(codec.encode(envelope)));
        Map<String, Object> wrongEnvelope = new LinkedHashMap<>(base);
        List<Map<String, Object>> wrongDiagnostics = new java.util.ArrayList<>();
        Map<String, Object> wrongSeverity = new LinkedHashMap<>(diagnostics(base).getFirst());
        wrongSeverity.put("severity", "warning");
        wrongDiagnostics.add(wrongSeverity);
        wrongEnvelope.put("diagnostics", wrongDiagnostics);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(wrongEnvelope)));

        Map<String, Object> malformedEnvelope = new LinkedHashMap<>(base);
        List<Map<String, Object>> malformedDiagnostics = new java.util.ArrayList<>();
        Map<String, Object> malformedEvidence = new LinkedHashMap<>(diagnostics(base).getFirst());
        malformedEvidence.put("evidence", List.of("not-an-object"));
        malformedDiagnostics.add(malformedEvidence);
        malformedEnvelope.put("diagnostics", malformedDiagnostics);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(malformedEnvelope)));
    }

    private static Diagnostic diagnostic(ServerResourceLocator resource) {
        return Diagnostic.builder("GRAPH.PIN_UNRESOLVED", restudio.resync.flow.diagnostic.DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId("diagnostic.graph-pin-unresolved"),
                Map.of("messageFuture", true)))
            .message("The graph pin is unresolved.")
            .arguments(Map.of("attempt", 2, "nested", Map.of("preserved", true)))
            .evidence(Map.of("pin", "input", "lossless", true))
            .remediation("Migrate or reconnect the wire to a valid immutable pin.")
            .correlationId(CORRELATION_UUID)
            .traceId(TRACE_UUID)
            .serverId(new ServerId(SERVER_UUID))
            .resource(resource)
            .nodeId(new NodeId("node-main"))
            .pinId(new PinId("input"))
            .catalogGeneration(7)
            .provenance(new DiagnosticProvenance(new OwnerId("restudio.resync"), DiagnosticSourceKind.BUNDLED,
                "classpath:/catalog.json?token=private",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "1.0", "build-7", Instant.parse("2026-08-02T00:00:00Z"),
                Map.of("provenanceFuture", Map.of("preserved", true))))
            .unknown(Map.of("futureDiagnostic", Map.of("preserved", true)))
            .durable(true)
            .redaction(DiagnosticRedaction.TECHNICAL)
            .metricPolicy(DiagnosticMetricPolicy.COUNT)
            .build();
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ContentHash hash,
                                                                    ResourceSaveRequest<Map<String, Object>> save,
                                                                    List<Diagnostic> diagnostics) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            TRACE_UUID,
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.save")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")),
            null,
            hash,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            diagnostics,
            Map.of("futureEnvelope", Map.of("preserved", true)),
            new ProtocolBody.ResourceRequest(save, Map.of("futureBody", "value")));
    }

    private static ServerResourceLocator resource() {
        ContractRef<ResourceTypeId> type = new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("flow"),
            Map.of("typeFuture", true));
        return new ServerResourceLocator(new ServerId(SERVER_UUID), new ResourceKey(type, "main", Map.of("keyFuture", true)),
            Map.of("locatorFuture", List.of("preserved")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaMap(Object value) {
        return (Map<String, Object>) ((JsonValue) value).toJava();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> diagnostics(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) envelope.get("diagnostics");
    }
}
