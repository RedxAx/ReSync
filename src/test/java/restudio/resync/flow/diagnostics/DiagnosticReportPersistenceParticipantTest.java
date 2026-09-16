package restudio.resync.flow.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticReportPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void rebindsTheLiveReporterOnlyAfterQuiescing() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path sourceDiagnostics = Files.createDirectory(source.resolve("diagnostics"));
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(sourceDiagnostics);
        DiagnosticReportPersistenceParticipant participant = new DiagnosticReportPersistenceParticipant(source, sourceDiagnostics, reporter);
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path targetDiagnostics = Files.createDirectory(target.resolve("diagnostics"));

        assertThrows(IllegalStateException.class, () -> participant.rebind(target));

        participant.quiesce();
        participant.rebind(target);
        participant.resume();

        assertEquals(targetDiagnostics, participant.root());
        assertEquals(targetDiagnostics, reporter.store().directory());
    }

    @Test
    void ownershipIndexIncludesTheDiagnosticRootAndDescendants() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path diagnostics = Files.createDirectory(source.resolve("diagnostics"));
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(diagnostics);
        DiagnosticReportPersistenceParticipant participant =
            new DiagnosticReportPersistenceParticipant(source, diagnostics, reporter);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/report.json"))));
    }
}
