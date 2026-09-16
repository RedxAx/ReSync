package restudio.resync.flow.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.validation.FlowGraphDiagnostic;
import restudio.resync.flow.validation.FlowGraphValidationResult;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredFlowDiagnosticReporterTest {
    @Test
    void runtimeHealthReusesValidatedStoreStateUntilLifecycleChanges(@TempDir Path directory) {
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(directory);

        StructuredFlowDiagnosticReporter.Observation initial = reporter.validatedHealthObservation();
        assertNotNull(initial);
        assertNotNull(reporter.reportNodeDefinitionDiagnostics(List.of(new NodeDefinitionDiagnostic(
            NodeDefinitionDiagnostic.Severity.ERROR, "DUPLICATE_NODE_ID", "nodes.json", 3, "test.node", "Duplicate node ID"))));
        assertFalse(reporter.isHealthCurrent(initial));
        assertNotNull(reporter.validatedHealthObservation());

        reporter.quiesce();
        assertNull(reporter.validatedHealthObservation());
        reporter.resume();
        assertNull(reporter.validatedHealthObservation());
        reporter.healthCheck();
        assertNotNull(reporter.validatedHealthObservation());
    }

    @Test
    void lifecycleTransitionsLeaveHistoricalValidationToTheHealthGate(@TempDir Path directory) throws Exception {
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(directory);
        var report = reporter.reportNodeDefinitionDiagnostics(List.of(new NodeDefinitionDiagnostic(
            NodeDefinitionDiagnostic.Severity.ERROR, "DUPLICATE_NODE_ID", "nodes.json", 3, "test.node", "Duplicate node ID")));
        assertNotNull(report);
        var store = reporter.store();
        Files.writeString(directory.resolve(report.reportId() + ".json"), "invalid report");

        assertDoesNotThrow(reporter::flush);
        reporter.quiesce();
        reporter.rebind(directory);
        assertSame(store, reporter.store());
        assertDoesNotThrow(reporter::resume);
        assertThrows(RuntimeException.class, reporter::healthCheck);
    }

    @Test
    void graphFailuresBecomeDurableStructuredReports(@TempDir Path directory) {
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(directory);
        FlowGraph graph = new FlowGraph();
        graph.setId("graph");
        FlowGraphValidationResult result = new FlowGraphValidationResult(List.of(new FlowGraphDiagnostic(
            FlowGraphDiagnostic.Severity.ERROR, "NODE_DEFINITION_MISSING", "graph", "node", "", "Node is missing", "Install the node")));

        var report = reporter.reportGraphValidation(graph, result);

        assertNotNull(report);
        assertEquals(1, report.diagnostics().size());
        assertEquals("GRAPH.DEFINITION_MISSING", report.diagnostics().diagnostics().getFirst().code());
        assertEquals("NODE_DEFINITION_MISSING", report.diagnostics().diagnostics().getFirst().evidence().get("legacyCode"));
        assertTrue(reporter.store().load(report.reportId()).isPresent());
    }

    @Test
    void catalogFailuresUseOwnerQualifiedStructuredCodes(@TempDir Path directory) {
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(directory);

        var report = reporter.reportNodeDefinitionDiagnostics(List.of(new NodeDefinitionDiagnostic(
            NodeDefinitionDiagnostic.Severity.ERROR, "DUPLICATE_NODE_ID", "nodes.json", 3, "test.node", "Duplicate node ID")));

        assertNotNull(report);
        assertEquals("CATALOG.IDENTITY_COLLISION", report.diagnostics().diagnostics().getFirst().code());
        assertEquals("resync", report.diagnostics().diagnostics().getFirst().messageKey().owner().canonicalText());
    }
}
