package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleStartupPrewarmTest {
    @Test
    void startupPreparesRuntimeCategoriesBeforePublishingRuntimeReadiness() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String constructor = source.substring(source.indexOf("public FlowModule("),
            source.indexOf("private synchronized FlowNodeRegistryPacketHandler.ActiveCatalogMetadata"));
        String noopCommit = source.substring(source.indexOf("if (noop) {", source.indexOf("public synchronized boolean commitCore")),
            source.indexOf("CatalogRuntimeActivation.ActivationResult result", source.indexOf("public synchronized boolean commitCore")));

        assertFalse(constructor.contains("prewarmAuthoring()"));
        assertTrue(noopCommit.contains("catalogPublicationTransport.prewarmAuthoring()"));
        String captureBinding = source.substring(source.indexOf("public void setOptionCatalogCaptureExecutor"),
            source.indexOf("CompletionStage<Void> prepareRuntimeDataCategories"));
        String categoryPreparation = source.substring(source.indexOf("CompletionStage<Void> prepareRuntimeDataCategories"),
            source.indexOf("public int getSubscribedSessionCount"));
        assertFalse(captureBinding.contains("RuntimeDataOptionCatalogService.prewarm"));
        assertFalse(categoryPreparation.contains("WorldGenOptionCatalogs.prewarm"));
        assertTrue(categoryPreparation.contains("RuntimeDataOptionCatalogService.prewarm"));
        assertTrue(categoryPreparation.contains("publishRuntimeDataCategoryRefresh"));
        assertTrue(categoryPreparation.contains("RuntimeDataOptionCatalogService.prewarm"));

        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        String activation = runtimeSource.substring(runtimeSource.indexOf("public synchronized void completeStartupActivation"),
            runtimeSource.indexOf("private static long elapsedMillis"));
        assertTrue(activation.indexOf("customContentStorage.preloadAll()")
            < activation.indexOf("delegate.prepareRuntimeDataCategories()"));
        assertTrue(activation.indexOf("customContentExecution.refreshAll()")
            < activation.indexOf("delegate.prepareRuntimeDataCategories()"));
        assertTrue(activation.contains("delegate.prepareRuntimeDataCategories().toCompletableFuture().join()"));
        assertTrue(activation.indexOf("delegate.prepareRuntimeDataCategories().toCompletableFuture().join()")
            < activation.indexOf("delegate.completeStartupActivation(verified)"));
    }
}
