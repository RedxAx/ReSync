package restudio.resync.flow.handler.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEventRegistryAuthorityTest {
    @Test
    void systemLifecycleTriggersUseTheDedicatedRuntimeAuthority() {
        List<String> managed = List.of(
            "event.server.start",
            "event.server.stop",
            "event.server.tick",
            "event.server.save",
            "event.plugin.enable",
            "event.plugin.disable",
            "event.world.load",
            "event.world.unload",
            "event.chunk.load",
            "event.chunk.unload"
        );

        managed.forEach(id -> assertTrue(FlowEventRegistry.isSystemManagedEvent(id), id));
        assertTrue(FlowEventRegistry.isSystemManagedEvent("EVENT.SERVER.START"));
        assertFalse(FlowEventRegistry.isSystemManagedEvent("event.player.join"));
        assertFalse(FlowEventRegistry.isSystemManagedEvent(null));
    }
}
