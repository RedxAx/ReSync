package restudio.resync.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.resources.AssetFileFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetIntegrityTraversalTest {
    @TempDir
    Path tempDir;

    @Test
    void scanPrunesInternalTreesAndStillDetectsExternalTamper() throws Exception {
        Path assets = tempDir.resolve("assets").toAbsolutePath().normalize();
        Path internalRoot = assets.resolve(".asset-coordinator");
        Path internalHistory = internalRoot.resolve("history");
        Path internalAsset = internalHistory.resolve("ignored.json");
        Path externalAsset = assets.resolve("Blueprints/Flows/external.json");
        Files.createDirectories(internalHistory);
        Files.createDirectories(externalAsset.getParent());
        Files.writeString(internalAsset, "{broken");
        Files.writeString(externalAsset, AssetFileFormat.withResourceIdentity("{\"id\":\"external\"}", "flow", 1L, "original"));
        Files.writeString(externalAsset, Files.readString(externalAsset).replace("\"assetMutationId\":\"original\"", "\"assetMutationId\":\"tampered\""));
        Files.writeString(assets.resolve("project.json"), """
            {"resources":[{"type":"flow","id":"external","path":"Blueprints/Flows"}]}
            """);
        List<Path> visited = new ArrayList<>();

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets, visited::add).scan(0);

        assertTrue(visited.contains(internalRoot));
        assertFalse(visited.stream().anyMatch(path -> path.startsWith(internalHistory)));
        assertTrue(visited.contains(externalAsset));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("HASH_MISMATCH")
            && issue.path().equals(assets.relativize(externalAsset).toString())));
        assertFalse(report.issues().stream().anyMatch(issue -> issue.path().contains(".asset-coordinator")));
    }
}
