package restudio.resync.flow.diagnostic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.diagnostic.DurableDiagnosticReportStore;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.validation.ValidationRequest;
import restudio.resync.flow.validation.ValidationSubject;
import restudio.resync.flow.validation.ValidationTarget;
import restudio.resync.flow.validation.ValidationEngine;
import restudio.resync.flow.validation.ValidationRule;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gate1DiagnosticsBoundaryTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final ContractRef<NodeId> SUBJECT_REFERENCE = new ContractRef<>(new OwnerId("test"), new NodeId("graph"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        new ServerId(SERVER_UUID),
        new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("flow")),
        "main"
    );

    @Test
    void frozenCatalogResolvesDefinitionsAndRejectsUnknownCodes() throws Exception {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();

        assertEquals(269, catalog.size());
        DiagnosticCodeCatalog.Definition loopCycle = catalog.require("GRAPH.LOOP_CYCLE");
        assertEquals(DiagnosticSeverity.ERROR, loopCycle.severity());
        assertEquals(DiagnosticPhase.SEMANTIC, loopCycle.phase());
        assertEquals("loop-cycle", loopCycle.stage());
        assertEquals("diagnostic.graph-loop-cycle", loopCycle.messageKey());
        assertEquals("The graph contains a cycle that prevents the loop from running.", loopCycle.message());
        assertTrue(loopCycle.durable());
        assertEquals("Remove the cycle and validate the graph again.", loopCycle.remediation());
        assertEquals(DiagnosticCodeCatalog.Retryability.AFTER_CORRECTION, loopCycle.retryability());
        DiagnosticCodeCatalog.Definition loopScope = catalog.require("GRAPH.LOOP_SCOPE_INVALID");
        assertEquals(DiagnosticSeverity.ERROR, loopScope.severity());
        assertEquals(DiagnosticPhase.SEMANTIC, loopScope.phase());
        assertEquals("loop-scope-invalid", loopScope.stage());
        assertEquals("diagnostic.graph-loop-scope-invalid", loopScope.messageKey());
        assertEquals("The loop body and completion path are not separate and self-contained.", loopScope.message());
        assertTrue(loopScope.durable());
        assertEquals("Correct the loop connections and validate the graph again.", loopScope.remediation());
        assertEquals(DiagnosticCodeCatalog.Retryability.AFTER_CORRECTION, loopScope.retryability());
        assertTrue(catalog.contains("GRAPH.PIN_UNRESOLVED"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("GRAPH.NOT_FROZEN"));
        assertThrows(IllegalArgumentException.class, () -> Diagnostic.builder(
            "GRAPH.NOT_FROZEN", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph"
        ).messageKey(messageKey("message")).message("Unknown").remediation("Fix").correlationId(CORRELATION).build());
        byte[] tampered;
        try (InputStream stream = DiagnosticCodeCatalog.class.getResourceAsStream("/restudio/resync/diagnostics/codes.json")) {
            assertNotNull(stream);
            tampered = stream.readAllBytes();
        }
        tampered[tampered.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCodeCatalog.fromBytes(tampered));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticCodeCatalog(
            DiagnosticCodeCatalog.SCHEMA_VERSION,
            List.of(catalog.require("GRAPH.PIN_UNRESOLVED")),
            Map.of("codes", List.of())
        ));
    }

    @Test
    void bundledCatalogMatchesFrozenSourceAndCanonicalContract() throws Exception {
        byte[] bytes;
        try (InputStream stream = DiagnosticCodeCatalog.class.getResourceAsStream("/restudio/resync/diagnostics/codes.json")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }

        assertEquals("2293c87308144be62c6d7c6fc67c0d1b70dacfb3f5f07b97fb38ae1df6bdb3a5",
            DiagnosticCodeCatalog.FROZEN_SOURCE_SHA256);
        assertEquals(DiagnosticCodeCatalog.FROZEN_SOURCE_SHA256, CanonicalHash.rawSha256(bytes));
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.fromBytes(bytes);
        assertEquals("014ab72de45fcef0ba18e83483ff3acbad50bf4d75ddc26d60f3d3fb3271e879", catalog.sourceHash());
        assertArrayEquals(CanonicalCodec.decodePermissive(bytes).canonicalBytes(), catalog.canonicalBytes());
        assertEquals(CanonicalHash.sha256(CanonicalCodec.decodePermissive(bytes)), catalog.sourceHash());

        DiagnosticCodeCatalog.Definition definition = catalog.require("GRAPH.PIN_UNRESOLVED");
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCodeCatalog.of(List.of(definition, definition)));
    }

    @Test
    void typedSubjectRejectsDifferentNodeContext() {
        ValidationSubject subject = ValidationSubject.node(ValidationTarget.GRAPH, SUBJECT_REFERENCE, RESOURCE, new NodeId("node"));
        ValidationRule<String> rule = ValidationRule.of(
            new ContractRef<>(new OwnerId("test"), new CapabilityId("validator")),
            DiagnosticPhase.SEMANTIC,
            context -> List.of(Diagnostic.builder("GRAPH.PIN_UNRESOLVED", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
                .messageKey(messageKey("message"))
                .message("Ignored")
                .arguments(Map.of(
                    "subject", subject.canonicalText(),
                    "subjectType", subject.typeName(),
                    "subjectTypeKey", subject.typeKey()
                ))
                .remediation("Ignored")
                .correlationId(CORRELATION)
                .resource(RESOURCE)
                .nodeId(new NodeId("other"))
                .build())
        );

        var result = new ValidationEngine<String>(List.of(rule)).validate(new ValidationRequest<>(
            "value", ValidationTarget.GRAPH, subject, null, null, CORRELATION, null
        ));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().diagnostics().stream().anyMatch(value -> value.code().equals("VALIDATION.SUBJECT_MISMATCH")));
    }

    @Test
    void durableReportReloadsAndExportsCanonicalBytes(@TempDir Path directory) throws Exception {
        ContractRef<ResourceTypeId> type = new ContractRef<>(
            new OwnerId("restudio.resync"),
            new ResourceTypeId("flow"),
            Map.of("typeFuture", Map.of("preserved", true))
        );
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(SERVER_UUID),
            new ResourceKey(type, "main", Map.of("keyFuture", true)),
            Map.of("locatorFuture", List.of("preserved"))
        );
        Diagnostic diagnostic = Diagnostic.builder("GRAPH.PIN_UNRESOLVED", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId("message"), Map.of("messageFuture", true)))
            .message("The graph pin cannot be resolved.")
            .arguments(Map.of("subject", "graph"))
            .evidence(Map.of("attempt", 1))
            .remediation("Reconnect the wire.")
            .correlationId(CORRELATION)
            .resource(resource)
            .provenance(new DiagnosticProvenance(
                new OwnerId("restudio.resync"),
                DiagnosticSourceKind.BUNDLED,
                "classpath:/catalog.json",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "1.0",
                "build-1",
                null,
                Map.of("provenanceFuture", Map.of("preserved", true))
            ))
            .unknown(Map.of("futureField", Map.of("preserved", true)))
            .build();
        UUID reportId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);

        DurableDiagnosticReportStore.StoredReport saved = store.persist(reportId, List.of(diagnostic));
        DurableDiagnosticReportStore.StoredReport loaded = store.require(reportId);

        assertArrayEquals(saved.canonicalBytes(), loaded.canonicalBytes());
        assertEquals(saved.contentHash(), loaded.contentHash());
        assertEquals(saved.canonicalText(), store.exportText(reportId));
        assertEquals(1, loaded.diagnostics().size());
        Diagnostic loadedDiagnostic = loaded.diagnostics().diagnostics().getFirst();
        assertTrue(loadedDiagnostic.unknown().containsKey("futureField"));
        assertTrue(loadedDiagnostic.messageKey().unknown().containsKey("messageFuture"));
        assertTrue(loadedDiagnostic.resource().type().unknown().containsKey("typeFuture"));
        assertTrue(loadedDiagnostic.resource().key().unknown().containsKey("keyFuture"));
        assertTrue(loadedDiagnostic.resource().unknown().containsKey("locatorFuture"));
        assertTrue(loadedDiagnostic.provenance().unknown().containsKey("provenanceFuture"));
        assertThrows(IllegalArgumentException.class, () -> Diagnostic.builder("GRAPH.PIN_UNRESOLVED", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(messageKey("message"))
            .message("The graph pin cannot be resolved.")
            .remediation("Reconnect the wire.")
            .correlationId(CORRELATION)
            .unknown(Map.of("location", Map.of("path", "graph.json")))
            .build());

        DurableDiagnosticReportStore.StoredReport mismatched = new DurableDiagnosticReportStore.StoredReport(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            saved.createdAt(),
            saved.diagnostics(),
            saved.unknown(),
            saved.contentHash(),
            saved.canonicalBytes()
        );
        assertThrows(IllegalArgumentException.class, () -> store.save(mismatched));

        DurableDiagnosticReportStore.StoredReport mismatchedDiagnostics = new DurableDiagnosticReportStore.StoredReport(
            saved.reportId(),
            saved.createdAt(),
            new DiagnosticSet(List.of()),
            saved.unknown(),
            saved.contentHash(),
            saved.canonicalBytes()
        );
        assertThrows(IllegalArgumentException.class, () -> store.save(mismatchedDiagnostics));

        String staleSeverity = saved.canonicalText().replace("\"severity\":\"error\"", "\"severity\":\"warning\"");
        Files.writeString(directory.resolve(reportId + ".json"), staleSeverity);
        assertThrows(IllegalArgumentException.class, () -> store.require(reportId));

        Files.write(directory.resolve(reportId + ".json"), saved.canonicalBytes());
        Files.move(directory.resolve(reportId + ".json"), directory.resolve("wrong.json"));
        assertThrows(IllegalArgumentException.class, () -> store.list());
    }

    private static ContractRef<NodeId> messageKey(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new NodeId(localId));
    }
}
