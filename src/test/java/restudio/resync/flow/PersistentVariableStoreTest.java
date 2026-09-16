package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceParticipantRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentVariableStoreTest {
    @TempDir
    Path temporary;

    @Test
    void persistsValuesAndOwnsRecoverableJournalSidecars() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PersistentVariableStore store = new PersistentVariableStore(source);

        store.set("global.score", 12D);
        store.set("global.nested", Map.of("enabled", true));

        assertEquals(PersistentVariableStore.OWNER, store.owner());
        assertEquals(source.resolve(PersistentVariableStore.FILE_NAME).toAbsolutePath().normalize(), store.root());
        assertEquals(Map.of("global.score", 12D, "global.nested", Map.of("enabled", true)), store.getAll());
        assertTrue(Files.isRegularFile(store.root()));

        Path previous = source.resolve(PersistentVariableStore.FILE_NAME + ".previous");
        Path quarantine = Files.createDirectories(source.resolve(".quarantine").resolve("journals"))
            .resolve(PersistentVariableStore.FILE_NAME + ".corrupt");
        Path nestedQuarantine = Files.createDirectories(quarantine.getParent().resolve("nested"))
            .resolve(PersistentVariableStore.FILE_NAME + ".corrupt");
        Path sibling = source.resolve("other.json");
        Files.writeString(previous, "previous");
        Files.writeString(quarantine, "corrupt");
        Files.writeString(nestedQuarantine, "nested");
        Files.writeString(sibling, "sibling");

        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(source);
        participants.register(store);
        assertEquals(store.owner(), participants.ownerFor(source, store.root()));
        assertEquals(store.owner(), participants.ownerFor(source, previous));
        assertEquals(store.owner(), participants.ownerFor(source, quarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source, nestedQuarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source, sibling));
    }

    @Test
    void quiesceResumeAndRebindUseAStagedCandidateAndPreserveTheSourceOnFailure() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PersistentVariableStore store = new PersistentVariableStore(source);
        store.set("global.score", 4D);
        store.quiesce();
        byte[] sourceBytes = Files.readAllBytes(store.root());

        assertThrows(IllegalStateException.class, () -> store.set("global.score", 5D));

        Path invalid = Files.createDirectory(temporary.resolve("invalid"));
        Files.writeString(invalid.resolve(PersistentVariableStore.FILE_NAME), "{broken");
        assertThrows(IOException.class, () -> store.rebind(invalid));
        assertEquals(source.resolve(PersistentVariableStore.FILE_NAME).toAbsolutePath().normalize(), store.root());
        assertArrayEquals(sourceBytes, Files.readAllBytes(source.resolve(PersistentVariableStore.FILE_NAME)));
        assertEquals(Map.of("global.score", 4D), store.getAll());

        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        PersistentVariableStore candidateStore = new PersistentVariableStore(candidate);
        candidateStore.set("global.score", 9D);
        candidateStore.set("global.extra", "candidate");
        candidateStore.close();

        store.rebind(candidate);
        assertEquals(candidate.resolve(PersistentVariableStore.FILE_NAME).toAbsolutePath().normalize(), store.root());
        assertEquals(1L, store.generation());
        assertEquals(Map.of("global.score", 9D, "global.extra", "candidate"), store.getAll());

        store.resume();
        store.healthCheck();
        assertFalse(store.isPersistenceQuiesced());
    }

    @Test
    void failedSaveDoesNotAdvanceMemoryAndHealthDetectsExternalDrift() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        PersistentVariableStore store = new PersistentVariableStore(source);
        store.set("global.score", 4D);
        byte[] original = Files.readAllBytes(store.root());

        Files.delete(store.root());
        Files.createDirectory(store.root());
        assertThrows(IllegalStateException.class, () -> store.set("global.score", 8D));
        assertEquals(Map.of("global.score", 4D), store.getAll());

        Files.delete(store.root());
        Files.writeString(store.root(), "{}");
        assertThrows(IOException.class, store::healthCheck);
        assertEquals(Map.of("global.score", 4D), store.getAll());
        assertTrue(original.length > 0);
    }
}
