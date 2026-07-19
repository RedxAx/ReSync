package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionHandlerTest {
    @Test
    void permissionModesImplementAnyAllNoneAndCountSemantics() {
        assertTrue(PermissionHandler.matchesMode(1, 3, "Find Any"));
        assertFalse(PermissionHandler.matchesMode(1, 3, "Find All"));
        assertTrue(PermissionHandler.matchesMode(3, 3, "Find All"));
        assertTrue(PermissionHandler.matchesMode(0, 3, "Find None"));
        assertFalse(PermissionHandler.matchesMode(1, 3, "Find None"));
        assertTrue(PermissionHandler.matchesMode(2, 3, "Count"));
    }

    @Test
    void permissionContextsNormalizeScalarAndMultiValueEntries() {
        Map<String, List<String>> context = PermissionHandler.normalizePermissionContext(Map.of(
            "World", "Survival",
            "server", List.of("Lobby", "Games", "games")
        ));

        assertEquals(List.of("survival"), context.get("world"));
        assertEquals(List.of("lobby", "games"), context.get("server"));
        assertThrows(IllegalArgumentException.class, () -> PermissionHandler.normalizePermissionContext(Map.of("world", List.of())));
        assertThrows(IllegalArgumentException.class, () -> PermissionHandler.normalizePermissionContext("world=survival"));
    }
}
