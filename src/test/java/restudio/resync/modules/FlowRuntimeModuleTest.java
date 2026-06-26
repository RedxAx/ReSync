package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FlowRuntimeModuleTest {
    @Test
    void resourceCatalogRevisionChangesWhenEqualCountIdsChange() {
        String first = FlowRuntimeModule.resourceCatalogRevision("npc-definition", List.of("alpha", "bravo"));
        String second = FlowRuntimeModule.resourceCatalogRevision("npc-definition", List.of("alpha", "charlie"));

        assertNotEquals(first, second);
    }

    @Test
    void resourceCatalogRevisionIsOrderStable() {
        String first = FlowRuntimeModule.resourceCatalogRevision("dialog", List.of("bravo", "alpha"));
        String second = FlowRuntimeModule.resourceCatalogRevision("dialog", List.of("alpha", "bravo"));

        assertEquals(first, second);
    }
}
