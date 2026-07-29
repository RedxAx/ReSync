package restudio.resync.worldgen.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenCompileDiagnosticsTest {
    @Test
    void invalidProjectReportsDiagnostics() {
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(null);

        assertFalse(diagnostics.isSuccess());
        assertFalse(diagnostics.getDiagnostics().isEmpty());
    }

    @Test
    void unsupportedTargetFailsBeforeGeneration() {
        JsonObject projectJson = JsonParser.parseString(WorldGenSerializer.serializeProject(new WorldGenProject())).getAsJsonObject();
        projectJson.getAsJsonObject("settings").addProperty("targetVersion", "1.20.6");
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(WorldGenSerializer.deserializeProject(projectJson.toString()));

        assertFalse(diagnostics.isSuccess());
        assertTrue(diagnostics.getDiagnostics().getFirst().message().contains("Unsupported Minecraft Version"));
    }
}
