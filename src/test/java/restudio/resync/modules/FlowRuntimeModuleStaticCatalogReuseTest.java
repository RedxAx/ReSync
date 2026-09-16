package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.ReplacementCatalogSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeModuleStaticCatalogReuseTest {
    @Test
    void staticCatalogReuseRequiresExactSourceIdentityAndBytes() {
        ReplacementCatalogSource.SourceInventory baseline = inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "first"));

        assertTrue(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "first"))));
        assertFalse(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/other.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "first"))));
        assertFalse(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/other.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "first"))));
        assertFalse(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/test.json", "other.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "first"))));
        assertFalse(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.LOCAL, "first"))));
        assertFalse(FlowRuntimeModule.sameCatalogSourceInventory(baseline, inventory(source(
            "C:/catalog/nodes/test.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, "second"))));
    }

    private static ReplacementCatalogSource.SourceInventory inventory(NodeDefinitionLoader.SourceFile source) {
        return new ReplacementCatalogSource.SourceInventory(List.of(source), List.of(source), List.of(),
            new ReplacementCatalogSource.LocalRootValidation(Path.of("C:/catalog/nodes"), true, true, ""));
    }

    private static NodeDefinitionLoader.SourceFile source(String sourceName, String sourceUri, String relativePath,
                                                           NodeDefinitionLoader.SourceOrigin origin, String content) {
        return new NodeDefinitionLoader.SourceFile(sourceName, sourceUri, relativePath, origin,
            content.getBytes(StandardCharsets.UTF_8));
    }
}
