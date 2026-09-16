package restudio.resync.worldgen.preview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenPreviewAuthorityTest {
    @Test
    void previewIdentityIsExactAndPhysicalNamesAreCollisionFree() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/worldgen/preview/WorldGenPreviewManager.java"));

        assertTrue(source.contains("Map<PreviewKey, PreviewWorld> activePreviews"));
        assertTrue(source.contains("Map<PreviewKey, PreviewRequest> activeRequests"));
        assertTrue(source.contains("Map<String, OwnedPreview> ownedPreviews"));
        assertTrue(source.contains("value.chars().allMatch"));
        assertTrue(source.contains("HexFormat.of().formatHex"));
        assertTrue(source.contains("Consumer<PreviewTermination> onLogicalTermination"));
        assertTrue(source.contains("request.notifyLogicalTermination()"));
        assertTrue(source.contains("deleteActivePreview(previewKey, previewWorld)"));
        assertFalse(source.contains("activePreviews.replace("));
        assertFalse(source.contains("toLowerCase(Locale.ROOT)"));
        assertFalse(source.contains("replaceAll(\"[^a-z0-9_\\\\-]+\", \"_\")"));
    }

    @Test
    void previewCleanupFailsClosedBeforeUnregisteringRuntimeState() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/worldgen/preview/WorldGenPreviewManager.java"));

        assertTrue(source.contains("if (!Bukkit.unloadWorld(world, true))"));
        assertTrue(source.contains("try (Stream<Path> stream = Files.walk(folder))"));
        assertTrue(source.contains("ownedPreviews.remove(worldName)"));
        int deletion = source.indexOf("deleteWorldFolder(root, folder);");
        int unregister = source.indexOf("WorldGenRuntimeRegistry.unregister(worldName);");
        assertTrue(deletion >= 0);
        assertTrue(unregister > deletion);
        assertFalse(source.contains("catch (IOException ignored)"));
    }

    @Test
    void operationPreviewJoinsPhysicalCompletionAndBindsBeforeLaunch() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/worldgen/WorldGenOperationService.java"));

        assertTrue(source.contains("jobs.bindAndStart(job, cancellation, termination)"));
        assertTrue(source.contains("previewManager.stopPreview(resolvedPreviewId"));
        assertTrue(source.contains("cancelPreviewJobAfterPhysical(job)"));
        assertTrue(source.contains("() -> termination.complete(null), termination::completeExceptionally"));
        assertFalse(source.contains("jobs.bind(job, () ->"));
    }
}
