package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementCatalogSourceValidationTest {
    @TempDir
    Path temporary;

    @Test
    void missingOperatorCatalogIsAValidAbsentInput() {
        ReplacementCatalogSource.LocalRootValidation validation = ReplacementCatalogSource.validateLocalRoot(temporary.resolve("missing-nodes"));

        assertTrue(validation.valid());
        assertFalse(validation.present());
    }

    @Test
    void nonDirectoryOperatorCatalogFailsClosed() throws Exception {
        Path file = Files.writeString(temporary.resolve("nodes"), "not-a-directory");

        ReplacementCatalogSource.LocalRootValidation validation = ReplacementCatalogSource.validateLocalRoot(file);

        assertFalse(validation.valid());
        assertTrue(validation.reason().contains("directory"));
    }

    @Test
    void malformedOperatorDefinitionProducesAnErrorInsteadOfAUsableCandidate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("nodes"));
        Files.writeString(root.resolve("broken.json"), "not-json");

        ReplacementCatalogSource.Snapshot snapshot = ReplacementCatalogSource.load(new NodeDefinitionLoader(), root);

        assertTrue(snapshot.hasErrors());
    }

    @Test
    void inventoryValidatesAndOrdersLocalSourcesOnceForBothConsumers() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("nodes"));
        Files.createDirectories(root.resolve("MiGrAtEd"));
        Files.writeString(root.resolve("z.json"), "[]");
        Files.writeString(root.resolve("a.json"), "[]");
        Files.writeString(root.resolve("_ignored.json"), "[]");
        Files.writeString(root.resolve("MiGrAtEd").resolve("legacy.json"), "[]");

        ClassLoader emptyResources = new ClassLoader(null) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        };
        ReplacementCatalogSource.SourceInventory inventory = ReplacementCatalogSource.inventory(emptyResources, root);

        assertTrue(inventory.localRoot().valid());
        assertEquals(List.of("a.json", "z.json"), inventory.localFiles().stream()
            .map(NodeDefinitionLoader.SourceFile::relativePath).toList());
        assertEquals(inventory.classpathFiles().size() + inventory.localFiles().size(), inventory.authoredFiles().size());
    }

    @Test
    void inventorySourceParsingUsesCanonicalSourceUriForProvenance() {
        String source = "[{\"id\":\"test.node\",\"owner\":\"test.owner\",\"displayName\":\"Test Node\","
            + "\"category\":\"UTILITY\",\"description\":\"A canonical source provenance test definition.\","
            + "\"domain\":\"test\",\"family\":\"test\",\"lifecycle\":\"active\","
            + "\"handlerCapability\":\"test.handler\",\"selectorIntent\":\"none\","
            + "\"inspectorIntent\":\"generic\",\"outputs\":[]}]";
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinition definition = loader.loadReplacementFromSources(List.of(new NodeDefinitionLoader.SourceFile(
            "C:/physical/nodes/test.json", "classpath:/nodes/test.json", "test.json",
            NodeDefinitionLoader.SourceOrigin.CLASSPATH, source.getBytes(StandardCharsets.UTF_8)))).getFirst();

        assertEquals("classpath:/nodes/test.json", definition.getAuthoredMetadata().sourceProvenance().sourceUri());
    }
}
