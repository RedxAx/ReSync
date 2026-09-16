package restudio.resync.worldgen.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void qualifiedWorldGenNodeIdentityReachesTheCompiler() {
        WorldGenProject project = projectWithTerrain("worldgen:simplex");

        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);

        assertTrue(diagnostics.isSuccess(), diagnostics.getDiagnostics().toString());
        assertTrue(PipelineCompiler.compileProject(project) != null);
    }

    @Test
    void unqualifiedIdentityRequiresExplicitCompatibility() {
        WorldGenProject project = projectWithTerrain("simplex");

        assertFalse(PipelineCompiler.diagnoseProject(project).isSuccess());
        assertTrue(PipelineCompiler.diagnoseProject(project, true).isSuccess());
    }

    @Test
    void wrongOwnerIsRejectedEvenInCompatibilityMode() {
        WorldGenProject project = projectWithTerrain("other:simplex");

        assertFalse(PipelineCompiler.diagnoseProject(project).isSuccess());
        assertFalse(PipelineCompiler.diagnoseProject(project, true).isSuccess());
    }

    private WorldGenProject projectWithTerrain(String noiseType) {
        WorldGenProject project = new WorldGenProject();
        WorldGenGraph graph = new WorldGenGraph();
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("noise", new WorldGenNode(noiseType, 0, 0, Map.of("seed", 0, "frequency", 0.01f)));
        nodes.put("height", new WorldGenNode("worldgen:output_height", 160, 0, Map.of()));
        graph.setNodes(nodes);
        graph.setConnections(List.of(new WorldGenConnection("noise", "out", "height", "height")));
        project.setTerrainGraph(graph);
        return project;
    }
}
