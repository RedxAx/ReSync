package restudio.resync.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStateStorageCandidateTest {
    @TempDir
    Path temporary;

    @Test
    void malformedCandidateIsRejectedBeforeTheActiveRootCanChange() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        WorldStateStorage storage = new WorldStateStorage(source);
        Path candidate = Files.createDirectories(temporary.resolve("candidate").resolve("world-management"));
        Files.writeString(candidate.resolve("worlds.json"), "{");

        assertThrows(IOException.class, () -> storage.readCandidateState(candidate));
        assertEquals(source.resolve("world-management").toAbsolutePath().normalize(), storage.getRootPath());
    }

    @Test
    void malformedAuthoritativeStateFailsClosedAndCannotBeReplacedByARecreatedEmptyState() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("malformed-authority"));
        WorldStateStorage storage = new WorldStateStorage(source);
        Path worlds = storage.getRootPath().resolve("worlds.json");
        Files.writeString(worlds, "{", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, storage::loadWorlds);
        assertThrows(IllegalStateException.class, () -> storage.saveWorlds(List.of()));
        assertEquals("{", Files.readString(worlds, StandardCharsets.UTF_8));
    }

    @Test
    void canonicalAtomicTempsAreMovedToOwnedEvidenceWithoutBecomingAuthoritativeState() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("atomic-recovery"));
        Path root = Files.createDirectories(source.resolve("world-management"));
        String name = ".resync-00000000-0000-0000-0000-000000000001.tmp";
        Files.writeString(root.resolve(name), "partial", StandardCharsets.UTF_8);

        WorldStateStorage storage = new WorldStateStorage(source);
        Path evidence = root.resolve(WorldStateStorage.QUARANTINE_DIRECTORY).resolve(name);
        assertFalse(Files.exists(root.resolve(name)));
        assertEquals("partial", Files.readString(evidence, StandardCharsets.UTF_8));
        assertTrue(storage.loadWorlds().isEmpty());
    }

    @Test
    void unknownEntriesAndQuarantineCollisionsFailClosed() throws Exception {
        Path unknownSource = Files.createDirectory(temporary.resolve("unknown-entry"));
        Path unknownRoot = Files.createDirectories(unknownSource.resolve("world-management"));
        Files.writeString(unknownRoot.resolve("worlds.json.tmp"), "unknown", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new WorldStateStorage(unknownSource));

        Path collisionSource = Files.createDirectory(temporary.resolve("collision"));
        Path collisionRoot = Files.createDirectories(collisionSource.resolve("world-management"));
        Path quarantine = Files.createDirectories(collisionRoot.resolve(WorldStateStorage.QUARANTINE_DIRECTORY));
        String name = ".resync-00000000-0000-0000-0000-000000000002.tmp";
        Files.writeString(collisionRoot.resolve(name), "new", StandardCharsets.UTF_8);
        Files.writeString(quarantine.resolve(name), "old", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> new WorldStateStorage(collisionSource));
        assertEquals("new", Files.readString(collisionRoot.resolve(name), StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(quarantine.resolve(name), StandardCharsets.UTF_8));
    }

    @Test
    void playerStateIdsMustUseTheExactLowercaseUuidText() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("uuid-text"));
        WorldStateStorage storage = new WorldStateStorage(source);
        Path playerStates = storage.getRootPath().resolve("player-states.json");
        String nonCanonical = "00000000-0000-0000-0000-00000000000A";
        Files.writeString(playerStates, "{\"" + nonCanonical + "\":{}}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, storage::loadPlayerStates);
        assertEquals("{\"" + nonCanonical + "\":{}}", Files.readString(playerStates, StandardCharsets.UTF_8));
    }

    @Test
    void oversizedWorldStateFilesFailBeforeJsonRead() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("oversized-state"));
        WorldStateStorage storage = new WorldStateStorage(source);
        Path worlds = storage.getRootPath().resolve("worlds.json");
        createSparseFile(worlds, WorldStateStorage.MAXIMUM_STATE_FILE_BYTES + 1L);

        assertThrows(IllegalStateException.class, storage::loadWorlds);
        assertEquals(WorldStateStorage.MAXIMUM_STATE_FILE_BYTES + 1L, Files.size(worlds));

        Files.delete(worlds);
        Path playerStates = storage.getRootPath().resolve("player-states.json");
        createSparseFile(playerStates, WorldStateStorage.MAXIMUM_STATE_FILE_BYTES + 1L);

        assertThrows(IllegalStateException.class, storage::loadPlayerStates);
        assertEquals(WorldStateStorage.MAXIMUM_STATE_FILE_BYTES + 1L, Files.size(playerStates));
    }

    private static void createSparseFile(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(size - 1L);
            channel.write(ByteBuffer.wrap(new byte[] {' '}));
        }
    }
}
