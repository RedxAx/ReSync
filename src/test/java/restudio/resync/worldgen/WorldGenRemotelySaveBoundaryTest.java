package restudio.resync.worldgen;

import com.google.gson.Gson;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenRemotelySaveBoundaryTest {
    @Test
    void remotelyProjectPassesReSyncValidationCompilationAndPersistence(@TempDir Path temporary) throws Exception {
        String sourceDirectory = System.getenv("RESYNC_SOURCE_DIR");
        Assumptions.assumeTrue(sourceDirectory != null && !sourceDirectory.isBlank(), "RESYNC_SOURCE_DIR is required");
        Path requestPath = Path.of(sourceDirectory).resolve("build/cross-repo/worldgen/project-request.json");
        assertTrue(Files.exists(requestPath), "Remotely project artifact is missing: " + requestPath);

        WorldGenProject project = WorldGenSerializer.deserializeProject(Files.readString(requestPath));
        assertNotNull(project);
        assertEquals("worldgen:simplex", project.getTerrainGraph().getNodes().get("noise").getType());
        assertEquals("worldgen:output_height", project.getTerrainGraph().getNodes().get("height").getType());

        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);
        assertTrue(diagnostics.isSuccess(), diagnostics.getDiagnostics().toString());
        assertNotNull(PipelineCompiler.compileProject(project));

        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);
        Path assets = Files.createDirectories(temporary.resolve("assets"));
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, new Gson())) {
            WorldGenProjectStorage storage = new WorldGenProjectStorage(temporary.toFile(), gate, new AssetPersistenceGate(temporary), coordinator);
            storage.saveProject(project);
            storage.healthCheckPersistence();
            assertTrue(storage.listProjectIds().contains(project.getId()));

            storage.closePersistence();
            WorldGenProjectStorage reloadedStorage = new WorldGenProjectStorage(temporary.toFile(),
                LegacyRuntimeActivationGate.runtime(temporary), new AssetPersistenceGate(temporary), coordinator);
            WorldGenProject reloaded = reloadedStorage.getProject(project.getId());
            assertNotNull(reloaded);
            assertEquals("worldgen:simplex", reloaded.getTerrainGraph().getNodes().get("noise").getType());
            assertEquals("worldgen:output_height", reloaded.getTerrainGraph().getNodes().get("height").getType());
            reloadedStorage.healthCheckPersistence();
            reloadedStorage.closePersistence();
        }
    }
}
