package restudio.resync.player;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTrackingStartupTest {
    private static final FileTime ORIGINAL_TIME = FileTime.fromMillis(946684800000L);
    private final Gson gson = new Gson();

    @TempDir
    Path root;

    @Test
    void unchangedHistoryIsLoadedWithoutRewritingFilesAcrossRestarts() throws Exception {
        Map<UUID, String> records = new LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            PlayerDossier dossier = dossier();
            dossier.setTotalPlayTimeMs(index * 1000L);
            records.put(UUID.fromString(dossier.getPlayerId()), write(dossier));
        }

        for (int restart = 0; restart < 2; restart++) {
            PlayerTrackingManager manager = new PlayerTrackingManager(root);
            assertEquals(records.size(), manager.getDossiers().size());
            for (Map.Entry<UUID, String> record : records.entrySet()) {
                assertNotNull(manager.getDossier(record.getKey()));
                assertEquals(record.getValue(), Files.readString(path(record.getKey())));
                assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(path(record.getKey())));
            }
        }
    }

    @Test
    void interruptedSessionIsRecoveredDurablyOnce() throws Exception {
        PlayerDossier dossier = dossier();
        dossier.setOnline(true);
        dossier.setTotalPlayTimeMs(5000);
        PlayerSessionRecord session = new PlayerSessionRecord();
        session.setSessionId("interrupted");
        session.setStartedAt(System.currentTimeMillis() - 1000);
        dossier.setActiveSession(session);
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        write(dossier);

        PlayerDossier recovered = new PlayerTrackingManager(root).getDossier(playerId);
        assertFalse(recovered.isOnline());
        assertNull(recovered.getActiveSession());
        assertEquals(1, recovered.getSessions().size());
        PlayerSessionRecord closed = recovered.getSessions().getFirst();
        assertEquals("interrupted", closed.getSessionId());
        assertEquals("startupRecovery", closed.getSource());
        assertTrue(closed.getEndedAt() >= session.getStartedAt());
        assertEquals(closed.getEndedAt() - session.getStartedAt(), closed.getDurationMs());
        assertEquals(5000 + closed.getDurationMs(), recovered.getTotalPlayTimeMs());
        assertEquals(closed.getEndedAt(), recovered.getLastSeenAt());
        String recoveredJson = Files.readString(path(playerId));
        assertEquals(gson.toJsonTree(recovered), gson.toJsonTree(gson.fromJson(recoveredJson, PlayerDossier.class)));
        Files.setLastModifiedTime(path(playerId), ORIGINAL_TIME);

        PlayerDossier restarted = new PlayerTrackingManager(root).getDossier(playerId);
        assertEquals(recovered.getTotalPlayTimeMs(), restarted.getTotalPlayTimeMs());
        assertEquals(recovered.getLastSeenAt(), restarted.getLastSeenAt());
        assertEquals(1, restarted.getSessions().size());
        assertEquals(recoveredJson, Files.readString(path(playerId)));
        assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(path(playerId)));
    }

    @Test
    void onlineFlagWithoutASessionIsClearedOnce() throws Exception {
        PlayerDossier dossier = dossier();
        dossier.setOnline(true);
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        write(dossier);

        PlayerDossier recovered = new PlayerTrackingManager(root).getDossier(playerId);
        assertFalse(recovered.isOnline());
        assertTrue(recovered.getSessions().isEmpty());
        assertFalse(gson.fromJson(Files.readString(path(playerId)), PlayerDossier.class).isOnline());
        String recoveredJson = Files.readString(path(playerId));
        Files.setLastModifiedTime(path(playerId), ORIGINAL_TIME);

        new PlayerTrackingManager(root);
        assertEquals(recoveredJson, Files.readString(path(playerId)));
        assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(path(playerId)));
    }

    @Test
    void endedActiveSessionIsClearedWithoutCountingItAgain() throws Exception {
        PlayerDossier dossier = dossier();
        dossier.setTotalPlayTimeMs(100);
        PlayerSessionRecord session = new PlayerSessionRecord();
        session.setSessionId("ended");
        session.setStartedAt(10);
        session.setEndedAt(110);
        session.setDurationMs(100);
        dossier.setActiveSession(session);
        dossier.getSessions().add(session.copy());
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        write(dossier);

        PlayerDossier recovered = new PlayerTrackingManager(root).getDossier(playerId);
        assertNull(recovered.getActiveSession());
        assertEquals(100, recovered.getTotalPlayTimeMs());
        assertEquals(1, recovered.getSessions().size());
        assertNull(gson.fromJson(Files.readString(path(playerId)), PlayerDossier.class).getActiveSession());
    }

    @Test
    void returnedHistoryCopiesCannotMutateTheResidentOrPersistedRecord() throws Exception {
        PlayerDossier dossier = dossier();
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        String json = write(dossier);
        PlayerTrackingManager manager = new PlayerTrackingManager(root);

        PlayerDossier copy = manager.getDossier(playerId);
        copy.setPlayerName("Changed");
        copy.getSessions().add(new PlayerSessionRecord());
        assertEquals("Tester", manager.getDossier(playerId).getPlayerName());
        assertTrue(manager.getDossier(playerId).getSessions().isEmpty());
        assertEquals(json, Files.readString(path(playerId)));

        manager.recordEvent(playerId, "Tester", "test", "state", "changed", Map.of("value", 1));
        PlayerDossier restarted = new PlayerTrackingManager(root).getDossier(playerId);
        assertEquals(1, restarted.getRecentEvents().size());
        assertEquals("changed", restarted.getRecentEvents().getFirst().getType());
    }

    @Test
    void mismatchedFileIdentityIsRejectedWithoutPublishingAnotherFile() throws Exception {
        PlayerDossier dossier = dossier();
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        UUID fileId = UUID.randomUUID();
        Files.createDirectories(root.resolve("player-dossiers"));
        String json = gson.toJson(dossier);
        Files.writeString(path(fileId), json);

        PlayerTrackingManager manager = new PlayerTrackingManager(root);

        assertNull(manager.getDossier(playerId));
        assertFalse(Files.exists(path(playerId)));
        assertEquals(json, Files.readString(path(fileId)));
    }

    @Test
    void repairedFieldsArePersistedOnce() throws Exception {
        UUID playerId = UUID.randomUUID();
        Files.createDirectories(root.resolve("player-dossiers"));
        Files.writeString(path(playerId), """
            {"playerId":"%s","playerName":null,"sessions":null,"recentEvents":null,"facets":null}
            """.formatted(playerId));

        PlayerDossier recovered = new PlayerTrackingManager(root).getDossier(playerId);
        assertEquals(playerId.toString(), recovered.getPlayerName());
        assertTrue(recovered.getSessions().isEmpty());
        assertTrue(recovered.getRecentEvents().isEmpty());
        assertTrue(recovered.getFacets().isEmpty());
        String recoveredJson = Files.readString(path(playerId));
        assertEquals(gson.toJsonTree(recovered), gson.toJsonTree(gson.fromJson(recoveredJson, PlayerDossier.class)));
        Files.setLastModifiedTime(path(playerId), ORIGINAL_TIME);

        new PlayerTrackingManager(root);
        assertEquals(recoveredJson, Files.readString(path(playerId)));
        assertEquals(ORIGINAL_TIME, Files.getLastModifiedTime(path(playerId)));
    }

    @Test
    void symbolicLinkIsRejectedWithoutReadingOrWritingItsTarget() throws Exception {
        PlayerDossier dossier = dossier();
        UUID playerId = UUID.fromString(dossier.getPlayerId());
        Path outside = root.resolve("outside.json");
        String json = gson.toJson(dossier);
        Files.writeString(outside, json);
        Files.createDirectories(root.resolve("player-dossiers"));
        Files.createSymbolicLink(path(playerId), outside);

        assertThrows(IllegalStateException.class, () -> new PlayerTrackingManager(root));

        assertTrue(Files.isSymbolicLink(path(playerId)));
        assertEquals(json, Files.readString(outside));
    }

    private PlayerDossier dossier() {
        PlayerDossier dossier = new PlayerDossier();
        dossier.setPlayerId(UUID.randomUUID().toString());
        dossier.setPlayerName("Tester");
        dossier.setFirstSeenAt(10);
        dossier.setLastSeenAt(20);
        return dossier;
    }

    private String write(PlayerDossier dossier) throws Exception {
        Files.createDirectories(root.resolve("player-dossiers"));
        Path path = path(UUID.fromString(dossier.getPlayerId()));
        String json = gson.toJson(dossier) + "\n";
        Files.writeString(path, json);
        Files.setLastModifiedTime(path, ORIGINAL_TIME);
        return json;
    }

    private Path path(UUID playerId) {
        return root.resolve("player-dossiers").resolve(playerId + ".json");
    }
}
