package restudio.resync.runtime;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertTrue(restored.contains(" guide "));
        assertTrue(restored.remove(" guide "));
        assertFalse(new PlayerNpcInstanceStorage(file).snapshot().containsKey("guide"));
    }

    @Test
    void keepsCommittedStateWhenRemovalCannotBeWritten() throws Exception {
        Path file = directory.resolve("player-npcs.json");
        Location location = new Location(MockBukkit.getMock().getWorld("world"), 12.5, 70, -4.25, 90, 10);
        AtomicBoolean fail = new AtomicBoolean();
        PlayerNpcInstanceStorage storage = new PlayerNpcInstanceStorage(file, (target, content) -> {
            if (fail.get()) {
                throw new java.io.IOException("Unavailable");
            }
            Files.writeString(target, content);
        });
        assertTrue(storage.save("guide", location));
        fail.set(true);

        assertFalse(storage.remove("guide"));
        assertTrue(storage.contains("guide"));
        assertTrue(storage.snapshot().containsKey("guide"));
    }

    @Test
    void rejectsMalformedStateInsteadOfSilentlyDroppingEntries() throws Exception {
        Path file = directory.resolve("player-npcs.json");
        Files.writeString(file, "{\"guide\":{\"world\":\"world\",\"x\":\"bad\"}}");

        assertThrows(IllegalStateException.class, () -> new PlayerNpcInstanceStorage(file));
    }

    @Test
    void quiesceBlocksMutationAndRebindsOnlyAfterStrictCandidateValidation() throws Exception {
        Path file = directory.resolve("player-npcs.json");
        Path candidateRoot = Files.createDirectory(directory.resolve("candidate"));
        Path candidate = candidateRoot.resolve("player-npcs.json");
        Location location = new Location(MockBukkit.getMock().getWorld("world"), 12.5, 70, -4.25, 90, 10);
        PlayerNpcInstanceStorage storage = new PlayerNpcInstanceStorage(file);
        assertTrue(storage.save("guide", location));
        storage.quiesce();

        assertThrows(IllegalStateException.class, () -> storage.save("other", location));
        Files.writeString(candidate, "{\"replacement\":{\"world\":\"world\",\"x\":1,\"y\":70,\"z\":2,\"yaw\":0,\"pitch\":0}}");
        storage.rebind(candidate);
        assertTrue(storage.snapshot().containsKey("replacement"));
        assertEquals(candidate.toAbsolutePath().normalize(), storage.file());

        Path malformedRoot = Files.createDirectory(directory.resolve("malformed"));
        Path malformed = malformedRoot.resolve("player-npcs.json");
        Files.writeString(malformed, "[]");
        assertThrows(IOException.class, () -> storage.rebind(malformed));
        assertEquals(candidate.toAbsolutePath().normalize(), storage.file());
        assertTrue(storage.snapshot().containsKey("replacement"));
        storage.resume();
    }
}
