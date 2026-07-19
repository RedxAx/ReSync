package restudio.resync.flow.sync;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionCatalogSnapshotContractTest {
    @Test
    void readsTheSharedRichContextualCatalogFixture() throws Exception {
        Path fixture = Path.of("..", "Remotely", "contracts", "option-catalog-v2.fixture.json");
        OptionCatalogSnapshot snapshot = new Gson().fromJson(Files.readString(fixture), OptionCatalogSnapshot.class);

        assertEquals(OptionCatalogSnapshot.CURRENT_VERSION, snapshot.getVersion());
        assertEquals("server:request:quests", snapshot.getSourceId());
        assertEquals("{\"world\":\"world_nether\"}", snapshot.getContextKey());
        assertEquals("quests-42", snapshot.getRevision());
        assertEquals(42L, snapshot.getSequence());
        assertEquals(snapshot.getValues(), snapshot.getItems().stream().map(item -> item.value()).toList());
        assertEquals("Dragon Hunt", snapshot.getItems().getFirst().label());
        assertEquals("Campaign", snapshot.getItems().getFirst().group());
        assertTrue((Boolean) snapshot.getItems().getFirst().metadata().get("recommended"));
    }
}
