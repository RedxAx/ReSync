package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationPublicationBindingTest {
    @TempDir
    Path temporary;

    @Test
    void publicationBindingIsDurableAndRoundTripsExactly() throws IOException {
        Path path = temporary.resolve("publication.journal");
        MigrationJournal journal = MigrationJournal.create(path, "migration-id", "1".repeat(64), binding());
        MigrationJournal.PublicationBinding publication = new MigrationJournal.PublicationBinding(
            "9".repeat(64), "asset-coordinator-adoption-v1", "2".repeat(64), "a".repeat(64));

        journal.bindPublication(publication);

        assertTrue(Files.readString(path).startsWith("format=6\n"));
        assertEquals(publication, journal.binding().orElseThrow().requirePublicationBinding());
        assertEquals(journal.binding(), MigrationJournal.open(path).binding());
    }

    @Test
    void publicationBindingCannotBeReplacedAfterItIsDurablyBound() throws IOException {
        MigrationJournal journal = MigrationJournal.create(
            temporary.resolve("mismatch.journal"), "migration-id", "1".repeat(64), binding());
        journal.bindPublication(new MigrationJournal.PublicationBinding(
            "9".repeat(64), "asset-coordinator-adoption-v1", "2".repeat(64), "a".repeat(64)));

        assertThrows(MigrationException.class, () -> journal.bindPublication(new MigrationJournal.PublicationBinding(
            "8".repeat(64), "asset-coordinator-adoption-v1", "2".repeat(64), "a".repeat(64))));
    }

    @Test
    void failedBindingPersistenceDoesNotPublishTheBindingInMemory() throws IOException {
        Path path = temporary.resolve("failed-binding.journal");
        MigrationJournal journal = MigrationJournal.create(path, "migration-id", "1".repeat(64), binding());
        Files.delete(path);
        Files.createDirectory(path);

        assertThrows(IOException.class, () -> journal.bindPublication(new MigrationJournal.PublicationBinding(
            "9".repeat(64), "asset-coordinator-adoption-v1", "2".repeat(64), "a".repeat(64))));

        assertTrue(journal.binding().orElseThrow().publicationBinding() == null);
    }

    @Test
    void terminalPublicationConflictIsDurableAndNotResumable() throws IOException {
        Path path = temporary.resolve("terminal-conflict.journal");
        MigrationJournal journal = MigrationJournal.create(path, "migration-id", "1".repeat(64), binding());
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        journal.transition(MigrationJournalState.VALIDATING, "validating");
        journal.transition(MigrationJournalState.STAGED, "staged");

        journal.markTerminalConflict("retained publication identity is invalid");

        MigrationJournal reopened = MigrationJournal.open(path);
        assertTrue(reopened.terminalConflict());
        assertEquals(JournalRecoveryAction.COMPLETE, reopened.recovery().action());
        assertEquals(MigrationJournalState.FAILED, reopened.currentState().orElseThrow());
    }

    private static MigrationJournal.Binding binding() {
        return new MigrationJournal.Binding(
            "snapshot-id", "2".repeat(64), "3".repeat(64), "4".repeat(64), "", "5".repeat(64),
            "6".repeat(64), "7".repeat(64), 1, "8".repeat(64), 1);
    }
}
