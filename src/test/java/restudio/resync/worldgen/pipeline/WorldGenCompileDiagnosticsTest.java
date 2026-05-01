package restudio.resync.worldgen.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldGenCompileDiagnosticsTest {
    @Test
    void invalidProjectReportsDiagnostics() {
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(null);

        assertFalse(diagnostics.isSuccess());
        assertFalse(diagnostics.getDiagnostics().isEmpty());
    }
}
