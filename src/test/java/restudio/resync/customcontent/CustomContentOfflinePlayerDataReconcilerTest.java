package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomContentOfflinePlayerDataReconcilerTest {
    @Test
    void acceptsOnlyCanonicalPlayerDataFileNames() {
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");

        assertEquals(playerId, CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + ".dat")));
        assertEquals(playerId, CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId.toString().toUpperCase() + ".dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + "-13286177663396300053.dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + ".dat.tmp")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of("not-a-player.dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(null));
    }
}
