package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementModuleTickResidencyTest {
    @Test
    void tickAndJoinPathsReuseAdmittedTrees() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/AdvancementModule.java"));
        assertTrue(source.contains("private Map<String, JsonObject> admitTrees()"));
        assertTrue(source.contains("residentSnapshot"));
        assertTrue(source.contains("current.generation() == generation"));
        assertTrue(source.contains("storage.readSnapshot(ReSyncResourceCatalog.ADVANCEMENT_TREE)"));
        assertFalse(source.contains("storage.isCurrent(residentSnapshot)"));
        assertFalse(source.contains("storage.listIds(ReSyncResourceCatalog.ADVANCEMENT_TREE)"));
        int poll = source.indexOf("pollingTask = Bukkit.getScheduler().runTaskTimer");
        int pollEnd = source.indexOf("}, 20, 20);", poll);
        String polling = source.substring(poll, pollEnd);
        assertTrue(polling.contains("Map<String, JsonObject> trees = admitTrees();"));
        assertTrue(polling.contains("dispatch(player, \"held_item\""));
        assertTrue(polling.contains("pollQuestCompletion(player, trees)"));
        assertFalse(polling.contains("trees(null, null)"));
        assertTrue(source.contains("sync(event.getPlayer(), admitTrees())"));
    }
}
