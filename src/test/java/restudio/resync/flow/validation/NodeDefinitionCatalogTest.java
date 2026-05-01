package restudio.resync.flow.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeDefinitionCatalogTest {
    @Test
    void migratedNodeCatalogKeepsExpectedNodeCount() throws Exception {
        Path root = Path.of("src", "main", "resources", "nodes", "migrated");
        int count = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().startsWith("_")).toList()) {
                JsonElement element = JsonParser.parseString(Files.readString(file));
                count += element.isJsonArray() ? element.getAsJsonArray().size() : 1;
            }
        }

        assertEquals(1207, count);
    }
}
