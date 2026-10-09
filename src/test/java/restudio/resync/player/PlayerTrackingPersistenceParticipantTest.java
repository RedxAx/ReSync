package restudio.resync.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTrackingPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void managerPersistsDossiersUnderItsProvidedActiveRoot() throws Exception {
        Path operatorRoot = Files.createDirectory(temporary.resolve("operator"));
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        PlayerTrackingManager manager = new PlayerTrackingManager(activeRoot);
        UUID playerId = UUID.randomUUID();

        manager.recordEvent(playerId, "Active", "test", "state", "active", null);

        assertEquals(activeRoot.resolve("player-dossiers").toAbsolutePath().normalize(), manager.getDossierDirectory());
        assertTrue(Files.exists(activeRoot.resolve("player-dossiers").resolve(playerId + ".json")));
        assertFalse(Files.exists(operatorRoot.resolve("player-dossiers").resolve(playerId + ".json")));
    }

    @Test
    void quiesceClosesDossierMutationAdmissionAndResumeReopensIt() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PlayerTrackingManager manager = new PlayerTrackingManager(source);
        PlayerTrackingPersistenceParticipant participant = new PlayerTrackingPersistenceParticipant(source, manager);
        UUID playerId = UUID.randomUUID();

        manager.recordEvent(playerId, "Before", "test", "state", "before", null);
        Path dossier = source.resolve("player-dossiers").resolve(playerId + ".json");
        assertTrue(Files.exists(dossier));
        assertTrue(Files.readString(dossier).contains("before"));

        participant.quiesce();
        assertThrows(IllegalStateException.class,
            () -> manager.recordEvent(playerId, "Blocked", "test", "state", "blocked", null));

        participant.resume();
        manager.recordEvent(playerId, "After", "test", "state", "after", null);

        assertEquals(2, manager.getDossier(playerId).getRecentEvents().size());
        try (var files = Files.list(source.resolve("player-dossiers"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rebindLoadsTheCandidateOnlyAfterQuiescenceAndKeepsThePreviousRootOnFailure() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PlayerTrackingManager manager = new PlayerTrackingManager(source);
        PlayerTrackingPersistenceParticipant participant = new PlayerTrackingPersistenceParticipant(source, manager);
        UUID sourcePlayer = UUID.randomUUID();
        manager.recordEvent(sourcePlayer, "Source", "test", "state", "source", null);
        Path previousRoot = participant.root();

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        PlayerTrackingManager replacementManager = new PlayerTrackingManager(replacement);
        UUID replacementPlayer = UUID.randomUUID();
        replacementManager.recordEvent(replacementPlayer, "Replacement", "test", "state", "replacement", null);

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(temporary.resolve("missing")));
        assertEquals(previousRoot, participant.root());

        participant.rebind(replacement);
        assertEquals(replacement.resolve("player-dossiers").toAbsolutePath().normalize(), participant.root());
        assertNull(manager.getDossier(sourcePlayer));
        assertNotNull(manager.getDossier(replacementPlayer));

        participant.resume();
        manager.recordEvent(replacementPlayer, "Replacement", "test", "state", "resumed", null);
        assertTrue(Files.readString(participant.root().resolve(replacementPlayer + ".json")).contains("resumed"));
    }

    @Test
    void invalidCandidateHealthLeavesTheActiveDossierRootUntouched() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PlayerTrackingManager manager = new PlayerTrackingManager(source);
        PlayerTrackingPersistenceParticipant participant = new PlayerTrackingPersistenceParticipant(source, manager);
        UUID playerId = UUID.randomUUID();
        manager.recordEvent(playerId, "Source", "test", "state", "source", null);
        Path previousRoot = participant.root();

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        Path replacementDossiers = Files.createDirectory(replacement.resolve("player-dossiers"));
        Files.writeString(replacementDossiers.resolve(playerId + ".json"), "{not-json");

        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(replacement));
        assertEquals(previousRoot, participant.root());
        assertNotNull(manager.getDossier(playerId));

        participant.resume();
        assertFalse(manager.getDossier(playerId).getRecentEvents().isEmpty());
    }

    @Test
    void failedHistoryWritePreservesTheCommittedDossierAndDoesNotPublishADelta() throws Exception {
        PlayerTrackingManager manager = new PlayerTrackingManager(temporary);
        UUID playerId = UUID.randomUUID();
        manager.recordEvent(playerId, "Committed", "test", "state", "before", null);
        AtomicInteger updates = new AtomicInteger();
        manager.addListener(update -> updates.incrementAndGet());
        Path file = manager.getDossierDirectory().resolve(playerId + ".json");
        Path committed = manager.getDossierDirectory().resolve("preserved.json");
        Files.move(file, committed);
        Files.createDirectory(file);

        assertThrows(IllegalStateException.class,
            () -> manager.recordEvent(playerId, "Unpublished", "test", "state", "after", null));

        assertEquals("Committed", manager.getDossier(playerId).getPlayerName());
        assertEquals(1, manager.getDossier(playerId).getRecentEvents().size());
        assertEquals(0, updates.get());
        assertTrue(Files.readString(committed).contains("before"));
    }

    @Test
    void callerAndSnapshotDataCannotChangeResidentEventsOrFacets() {
        PlayerTrackingManager manager = new PlayerTrackingManager(temporary);
        UUID playerId = UUID.randomUUID();
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("state", "committed"));
        Map<String, Object> data = Map.of("nested", nested);
        manager.recordEvent(playerId, "Player", "test", "state", "before", data);
        manager.upsertFacet(playerId, "Player", "status", "test", data);
        nested.put("state", "caller");
        PlayerDossier snapshot = manager.getDossier(playerId);
        for (Map<String, Object> values : List.of(snapshot.getRecentEvents().getFirst().getData(), snapshot.getFacets().get("status").getData())) {
            assertEquals(Map.of("state", "committed"), values.get("nested"));
            ((Map<?, ?>) values.get("nested")).clear();
        }
        snapshot.getRecentEvents().getFirst().getData().put("nested", Map.of("state", "snapshot"));
        assertEquals(Map.of("state", "committed"), manager.getDossier(playerId).getRecentEvents().getFirst().getData().get("nested"));
        assertEquals(Map.of("state", "committed"), manager.getDossier(playerId).getFacets().get("status").getData().get("nested"));
    }

    @Test
    void ownershipIndexIncludesTheDossierRootAndDescendants() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source-index"));
        PlayerTrackingManager manager = new PlayerTrackingManager(source);
        PlayerTrackingPersistenceParticipant participant = new PlayerTrackingPersistenceParticipant(source, manager);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/player.json"))));
    }
}
