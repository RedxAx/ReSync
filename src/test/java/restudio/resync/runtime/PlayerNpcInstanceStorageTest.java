package restudio.resync.runtime;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNpcInstanceStorageTest {
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void storesSummonedPlayerNpcPositionAcrossInstances() {
        Path file = directory.resolve("player-npcs.json");
        Location location = new Location(MockBukkit.getMock().getWorld("world"), 12.5, 70, -4.25, 90, 10);

        new PlayerNpcInstanceStorage(file).save("guide", location);
        PlayerNpcInstanceStorage restored = new PlayerNpcInstanceStorage(file);

        PlayerNpcInstanceStorage.Position position = restored.snapshot().get("guide");
        assertEquals("world", position.world());
        assertEquals(12.5, position.x());
        assertEquals(70, position.y());
        assertEquals(-4.25, position.z());
        assertEquals(90, position.yaw());
        assertEquals(10, position.pitch());
        assertTrue(restored.remove("guide"));
        assertFalse(new PlayerNpcInstanceStorage(file).snapshot().containsKey("guide"));
    }
}
