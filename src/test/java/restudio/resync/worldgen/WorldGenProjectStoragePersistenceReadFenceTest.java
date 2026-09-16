package restudio.resync.worldgen;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.data.WorldGenProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenProjectStoragePersistenceReadFenceTest {
    @Test
    void stampAndIdReadsFailDuringQuiesceAndFollowTheReboundRoot(@TempDir Path temporary) throws Exception {
        Path current = Files.createDirectory(temporary.resolve("current"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        AssetPersistenceGate gate = new AssetPersistenceGate(current);
        try (AssetTransactionCoordinator currentCoordinator = coordinator(current);
             AssetTransactionCoordinator candidateCoordinator = coordinator(candidate)) {
            WorldGenProjectStorage storage = new WorldGenProjectStorage(current.toFile(),
                LegacyRuntimeActivationGate.runtime(current), gate, currentCoordinator);
            WorldGenProject project = new WorldGenProject();
            project.setId("shared");
            storage.saveProject(project, java.util.UUID.randomUUID(), 0L);
            assertTrue(storage.readMutationStamp("shared") != null);
            assertEquals(List.of("shared"), storage.listProjectIds());

            storage.quiescePersistence();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                ExecutionException stampFailure = assertThrows(ExecutionException.class,
                    () -> executor.submit(() -> storage.readMutationStamp("shared")).get());
                ExecutionException idsFailure = assertThrows(ExecutionException.class,
                    () -> executor.submit(storage::listProjectIds).get());
                assertTrue(stampFailure.getCause() instanceof IllegalStateException);
                assertTrue(idsFailure.getCause() instanceof IllegalStateException);
            } finally {
                executor.shutdownNow();
            }

            storage.rebindPersistence(candidate, candidateCoordinator);
            gate.rebind(candidate);
            storage.resumePersistence();

            FlowResourceMutationStamp stamp = storage.readMutationStamp("shared");
            assertFalse(storage.listProjectIds().contains("shared"));
            assertTrue(stamp == null);
        }
    }

    private AssetTransactionCoordinator coordinator(Path root) throws Exception {
        return new AssetTransactionCoordinator(Files.createDirectories(root.resolve("assets")), new Gson());
    }
}
