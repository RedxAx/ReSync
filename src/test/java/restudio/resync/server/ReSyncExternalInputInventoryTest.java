package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceExternalInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncExternalInputInventoryTest {
    @TempDir
    Path temporary;

    @Test
    void externalInputsAreSeparateFromWriterInventory() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("resync"));

        List<PersistenceExternalInput.Input> inputs = ReSyncUncoveredWriterInventory.externalInputs(root);
        List<String> writers = ReSyncUncoveredWriterInventory.forDataRoot(root).stream()
            .map(writer -> writer.id()).toList();

        assertEquals(List.of("dataRoot/nodes", "resync.properties"), inputs.stream().map(PersistenceExternalInput.Input::id).toList());
        assertFalse(writers.contains(PersistenceExternalInput.PROPERTIES_ID));
        assertFalse(writers.contains(PersistenceExternalInput.NODES_ID));
        assertTrue(inputs.stream().allMatch(PersistenceExternalInput.Input::excludedFromPersistence));
    }
}
