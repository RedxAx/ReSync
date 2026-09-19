package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleStartupPrewarmTest {
    @Test
    void startupPrewarmsOnlyTheFinalCatalog() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String constructor = source.substring(source.indexOf("public FlowModule("),
            source.indexOf("private synchronized FlowNodeRegistryPacketHandler.ActiveCatalogMetadata"));
        String noopCommit = source.substring(source.indexOf("if (noop) {", source.indexOf("public synchronized boolean commitCore")),
            source.indexOf("CatalogRuntimeActivation.ActivationResult result", source.indexOf("public synchronized boolean commitCore")));

        assertFalse(constructor.contains("prewarmAuthoring()"));
        assertTrue(noopCommit.contains("catalogPublicationTransport.prewarmAuthoring()"));
        String captureBinding = source.substring(source.indexOf("public void setOptionCatalogCaptureExecutor"),
            source.indexOf("public int getSubscribedSessionCount"));
        assertFalse(captureBinding.contains("WorldGenOptionCatalogs.prewarm"));
        assertFalse(captureBinding.contains("RuntimeDataOptionCatalogService.prewarm"));
        assertFalse(captureBinding.contains(".join()"));
    }
}
