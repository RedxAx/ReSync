package restudio.resync.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcServiceTest {
    @Test
    void hookPayloadUsesCompactNpcBindingShape() {
        Map<String, Object> variables = NpcService.hookVariables("guard", null, null, null);

        assertEquals("guard", variables.get("npcId"));
        assertTrue(variables.containsKey("player"));
        assertTrue(variables.containsKey("entity"));
        assertTrue(variables.containsKey("location"));
        assertNull(variables.get("player"));
        assertNull(variables.get("entity"));
        assertNull(variables.get("location"));
    }
}
