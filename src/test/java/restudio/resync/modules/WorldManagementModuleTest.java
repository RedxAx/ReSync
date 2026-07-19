package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldManagementModuleTest {
    @Test
    void worldManagementUsesTheRegisteredWorldGenAuthority() {
        List<String> dependencies = new WorldManagementModule().getMetadata().dependencies();

        assertTrue(dependencies.containsAll(List.of("flowJobs", "playerTracking", "worldGen")));
    }

    @Test
    void worldGenUsesTheCanonicalFlowJobAuthority() {
        List<String> dependencies = new WorldGenModule().getMetadata().dependencies();

        assertTrue(dependencies.contains("flowJobs"));
    }
}
