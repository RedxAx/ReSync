package restudio.resync.player;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTrackingRecoveryTest {
    private static final String TEMP_NAME = ".resync-0bca995b-98d5-454c-92e0-791e59909a66.tmp";
    private static final FileTime ORIGINAL_TIME = FileTime.fromMillis(946684800000L);
    private final Gson gson = new Gson();

    @TempDir
    Path root;

    @Test
    void interruptedWritesArePreservedWithoutReplacingCommittedHistoryAndRecoveryIsIdempotent() throws Exception {
        PlayerDossier dossier = dossier();
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        Path committed = directory.resolve(playerId + ".json");
        String json = gson.toJson(dossier) + "\n";
        Files.writeString(committed, json);
        Files.setLastModifiedTime(committed, ORIGINAL_TIME);
        dossier.setPlayerName("Unpublished");
        Map<String, String> interrupted = Map.of(
            TEMP_NAME, "",
            ".resync-00000000-0000-4000-8000-000000000001.tmp", "{partial",
            ".resync-00000000-0000-4000-8000-000000000002.tmp", gson.toJson(dossier),
            ".resync-00000000-0000-4000-8000-000000000003.backup.tmp", "backup");
        for (Map.Entry<String, String> entry : interrupted.entrySet()) {
            Files.writeString(directory.resolve(entry.getKey()), entry.getValue());
        }

        PlayerTrackingManager manager = new PlayerTrackingManager(root);
        manager.healthCheckPersistence();
        assertEquals("Committed", manager.getDossier(playerId).getPlayerName());
        assertEquals(json, Files.readString(committed));
        assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(committed));
        for (Map.Entry<String, String> entry : interrupted.entrySet()) {
            assertFalse(Files.exists(directory.resolve(entry.getKey())));
            assertEquals(entry.getValue(), Files.readString(quarantine().resolve(entry.getKey())));
        }
        try (var entries = Files.list(quarantine())) {
            assertEquals(interrupted.size(), entries.count());
        }
        FileTime quarantineTime = Files.getLastModifiedTime(quarantine());

        manager.quiescePersistence();
        manager.rebindPersistence(directory);
        manager.resumePersistence();
        PlayerTrackingManager restarted = new PlayerTrackingManager(root);
        restarted.healthCheckPersistence();
        assertEquals("Committed", restarted.getDossier(playerId).getPlayerName());
        assertEquals(json, Files.readString(committed));
        assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(committed));
        assertEquals(quarantineTime, Files.getLastModifiedTime(quarantine()));
    }

    @Test
    void unpublishedHistoryIsNotPromotedWhenTheCommittedFileIsMissing() throws Exception {
        PlayerDossier dossier = dossier();
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        String unpublished = gson.toJson(dossier);
        Files.writeString(directory.resolve(TEMP_NAME), unpublished);

        PlayerTrackingManager manager = new PlayerTrackingManager(root);
        manager.healthCheckPersistence();

        assertNull(manager.getDossier(playerId));
        assertFalse(Files.exists(directory.resolve(playerId + ".json")));
        assertEquals(unpublished, Files.readString(quarantine().resolve(TEMP_NAME)));
    }

    @Test
    void collidingEvidenceKeepsBothVersionsAndRetriesReuseTheSameBytes() throws Exception {
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        Files.createDirectories(quarantine());
        Files.writeString(quarantine().resolve(TEMP_NAME), "older");
        Files.writeString(directory.resolve(TEMP_NAME), "newer");

        new PlayerTrackingManager(root).healthCheckPersistence();

        assertEquals("older", Files.readString(quarantine().resolve(TEMP_NAME)));
        assertEquals("newer", Files.readString(quarantine().resolve(TEMP_NAME + ".1")));
        FileTime preservedTime = Files.getLastModifiedTime(quarantine().resolve(TEMP_NAME + ".1"));
        Files.writeString(directory.resolve(TEMP_NAME), "newer");

        new PlayerTrackingManager(root).healthCheckPersistence();

        assertFalse(Files.exists(directory.resolve(TEMP_NAME)));
        assertEquals(preservedTime, Files.getLastModifiedTime(quarantine().resolve(TEMP_NAME + ".1")));
        try (var entries = Files.list(quarantine())) {
            assertEquals(2, entries.count());
        }
    }

    @Test
    void invalidTemporaryNameFailsClosedWithoutMovingOtherEvidence() throws Exception {
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        Files.writeString(directory.resolve(TEMP_NAME), "preserve");
        Files.writeString(directory.resolve(".resync-invalid.tmp"), "invalid");

        assertThrows(IllegalStateException.class, () -> new PlayerTrackingManager(root));

        assertEquals("preserve", Files.readString(directory.resolve(TEMP_NAME)));
        assertEquals("invalid", Files.readString(directory.resolve(".resync-invalid.tmp")));
        assertFalse(Files.exists(quarantine()));
    }

    @Test
    void symbolicQuarantineFailsClosedAndPreservesSourceAndTarget() throws Exception {
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(directory.resolve(TEMP_NAME), "preserve");
        Files.createSymbolicLink(directory.resolve(".quarantine"), outside);

        assertThrows(IllegalStateException.class, () -> new PlayerTrackingManager(root));

        assertEquals("preserve", Files.readString(directory.resolve(TEMP_NAME)));
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void unexpectedFilesStillFailPersistenceValidation() throws Exception {
        Path directory = Files.createDirectory(root.resolve("player-dossiers"));
        Files.writeString(directory.resolve("unrelated.tmp"), "preserve");
        PlayerTrackingManager manager = new PlayerTrackingManager(root);

        assertThrows(IOException.class, manager::healthCheckPersistence);

        assertEquals("preserve", Files.readString(directory.resolve("unrelated.tmp")));
    }

    @Test
    void invalidRecoveryEvidenceCannotRebindTheManager() throws Exception {
        PlayerTrackingManager manager = new PlayerTrackingManager(root);
        UUID playerId = UUID.randomUUID();
        manager.recordEvent(playerId, "Committed", "test", "state", "before", null);
        Path previous = manager.getDossierDirectory();
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Path invalid = Files.createDirectories(candidate.resolve(".quarantine/atomic-writes"));
        Files.writeString(invalid.resolve("unknown"), "preserve");
        manager.quiescePersistence();

        assertThrows(IOException.class, () -> manager.rebindPersistence(candidate));

        assertEquals(previous, manager.getDossierDirectory());
        assertNotNull(manager.getDossier(playerId));
        assertEquals("preserve", Files.readString(invalid.resolve("unknown")));
        manager.resumePersistence();
        assertTrue(Files.exists(previous.resolve(playerId + ".json")));
    }

    private PlayerDossier dossier() {
        PlayerDossier dossier = new PlayerDossier();
        dossier.setPlayerId(UUID.randomUUID().toString());
        dossier.setPlayerName("Committed");
        dossier.setFirstSeenAt(1);
        dossier.setLastSeenAt(2);
        return dossier;
    }

    private Path quarantine() {
        return root.resolve("player-dossiers/.quarantine/atomic-writes");
    }
}
