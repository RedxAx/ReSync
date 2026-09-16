package restudio.resync.flow.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceParticipantRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationTaskPersistenceParticipantTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ownsTheSchedulerJournalAndRejectsMutationsWhileQuiesced() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source").resolve("runtime"));
        AutomationTaskService service = service(source.resolve("automation-tasks.json"));
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), service);

        assertEquals(AutomationTaskPersistenceParticipant.OWNER, participant.owner());
        assertEquals(source.resolve("automation-tasks.json"), participant.root());
        assertEquals(source.getParent(), participant.rebindScope());
        assertTrue(Files.isRegularFile(participant.root()));

        participant.quiesce();
        assertTrue(service.isPersistenceQuiesced());
        assertThrows(IllegalStateException.class, () -> service.startTimer(timer("blocked"), owner(), 60_000L, 0L));
        participant.healthCheck();

        participant.resume();
        assertFalse(service.isPersistenceQuiesced());
        service.startTimer(timer("accepted"), owner(), 60_000L, 0L);
        service.shutdown();
    }

    @Test
    void resolvedOwnershipMatchesValidatedOwnershipAcrossRebind() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("resolved-source"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("resolved-target").resolve("runtime")).getParent();
        AutomationTaskService service = service(source.resolve("runtime/automation-tasks.json"));
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source, service);
        String id = "1a000000-0000-4000-8000-000000000001";
        List<String> accepted = List.of("automation-tasks.json", "automation-tasks.json.previous",
            ".quarantine/journals/automation-tasks.json.corrupt",
            ".quarantine/journals/automation-tasks.json.corrupt.1",
            ".quarantine/journals/automation-tasks.json.corrupt.127",
            ".quarantine/journals/automation-tasks.json.previous.corrupt",
            ".quarantine/journals/automation-tasks.json.previous.corrupt.127",
            ".quarantine/journals/.resync-" + id + ".tmp",
            ".quarantine/journals/.resync-" + id + ".tmp.127",
            ".quarantine/journals/.resync-" + id + ".backup.tmp",
            ".quarantine/journals/.resync-" + id + ".backup.tmp.127");
        List<String> rejected = List.of("automation-tasks.json.extra", "automation-tasks.json.previous.extra",
            "automation-tasks.json.corrupt", ".quarantine/journals/foreign.json.corrupt",
            ".quarantine/journals/automation-tasks.json.corrupt.0",
            ".quarantine/journals/automation-tasks.json.corrupt.01",
            ".quarantine/journals/automation-tasks.json.corrupt.128",
            ".quarantine/journals/automation-tasks.json.corrupt.1000",
            ".quarantine/journals/automation-tasks.json.previous.corrupt.extra",
            ".quarantine/journals/nested/automation-tasks.json.corrupt",
            ".quarantine/journals/automation-tasks.json.corrupt/nested",
            ".quarantine/journals/.resync-" + id.toUpperCase() + ".tmp",
            ".quarantine/journals/.resync-" + id + ".tmp.128",
            ".quarantine/journals/.resync-" + id + ".backup.tmp.0",
            ".quarantine/journals/.resync-invalid.tmp",
            ".resync-" + id + ".tmp");
        try {
            for (Path scope : List.of(source, target)) {
                if (scope.equals(target)) {
                    participant.quiesce();
                    participant.rebind(target);
                    participant.resume();
                    assertFalse(participant.ownsResolved(source.resolve("runtime/automation-tasks.json")));
                }
                Path runtime = scope.resolve("runtime");
                assertFalse(participant.ownsResolved(runtime));
                for (String name : accepted) {
                    Path candidate = runtime.resolve(name);
                    assertTrue(participant.owns(candidate), name);
                    assertEquals(participant.owns(candidate), participant.ownsResolved(candidate), name);
                }
                for (String name : rejected) {
                    Path candidate = runtime.resolve(name);
                    assertFalse(participant.owns(candidate), name);
                    assertEquals(participant.owns(candidate), participant.ownsResolved(candidate), name);
                }
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void rebindSwapsTheJournalOnlyAfterQuiescing() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source").resolve("runtime"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("target").resolve("runtime"));
        AutomationTaskService service = service(source.resolve("automation-tasks.json"));
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), service);
        service.startTimer(timer("source"), owner(), 60_000L, 0L);
        participant.quiesce();

        participant.rebind(target.getParent());

        assertEquals(target.resolve("automation-tasks.json"), participant.root());
        assertTrue(Files.isRegularFile(participant.root()));
        participant.healthCheck();
        participant.resume();
        service.shutdown();
    }

    @Test
    void failedRebindLeavesTheSourceJournalActive() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source").resolve("runtime"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("target").resolve("runtime"));
        Files.createDirectory(target.resolve("automation-tasks.json"));
        AutomationTaskService service = service(source.resolve("automation-tasks.json"));
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), service);
        participant.quiesce();

        assertThrows(IOException.class, () -> participant.rebind(target.getParent()));
        assertEquals(source.resolve("automation-tasks.json"), participant.root());
        service.shutdown();
    }

    @Test
    void failedRebindOfCorruptCandidatePreservesSourceJournal() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source-corrupt").resolve("runtime"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("target-corrupt").resolve("runtime"));
        Path sourceFile = source.resolve("automation-tasks.json");
        Path targetFile = target.resolve("automation-tasks.json");
        AutomationTaskService service = service(sourceFile);
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), service);
        service.startTimer(timer("source"), owner(), 60_000L, 0L);
        participant.quiesce();
        String sourceContents = Files.readString(sourceFile);
        Files.writeString(targetFile, "{corrupt");

        assertThrows(IOException.class, () -> participant.rebind(target.getParent()));
        assertEquals(sourceFile, participant.root());
        assertEquals(sourceContents, Files.readString(sourceFile));
        service.shutdown();
    }

    @Test
    void ownsRecoverableJournalSidecarsWithoutClaimingSiblingFiles() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source-sidecars").resolve("runtime"));
        Path journal = source.resolve("automation-tasks.json");
        AutomationTaskService service = service(journal);
        Path previous = source.resolve("automation-tasks.json.previous");
        Path quarantineDirectory = Files.createDirectories(source.resolve(".quarantine").resolve("journals"));
        Path currentCorrupt = quarantineDirectory.resolve("automation-tasks.json.corrupt");
        Path currentCollision = quarantineDirectory.resolve("automation-tasks.json.corrupt.127");
        Path previousCorrupt = quarantineDirectory.resolve("automation-tasks.json.previous.corrupt");
        Path previousCollision = quarantineDirectory.resolve("automation-tasks.json.previous.corrupt.1");
        Path atomicTemp = quarantineDirectory.resolve(".resync-" + UUID.randomUUID() + ".tmp");
        Path atomicTempCollision = quarantineDirectory.resolve(".resync-" + UUID.randomUUID() + ".tmp.127");
        Path atomicBackupTemp = quarantineDirectory.resolve(".resync-" + UUID.randomUUID() + ".backup.tmp");
        Path nestedQuarantine = quarantineDirectory.resolve("nested").resolve(currentCorrupt.getFileName().toString());
        Path malformedQuarantine = quarantineDirectory.resolve("automation-tasks.json.corrupt.128");
        Path obsoleteQuarantine = quarantineDirectory.resolve("automation-tasks.json." + UUID.randomUUID() + ".corrupt");
        Path foreignQuarantine = quarantineDirectory.resolve("flow-variables.json.corrupt");
        Path sibling = source.resolve("resource-mutations.db");
        Files.writeString(currentCorrupt, "current-corrupt");
        Files.writeString(currentCollision, "current-collision");
        Files.writeString(previousCorrupt, "previous-corrupt");
        Files.writeString(previousCollision, "previous-collision");
        Files.writeString(atomicTemp, "atomic-temp");
        Files.writeString(atomicTempCollision, "atomic-temp-collision");
        Files.writeString(atomicBackupTemp, "atomic-backup-temp");
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), service);
        service.flushPersistence();
        Files.writeString(malformedQuarantine, "malformed");
        Files.writeString(obsoleteQuarantine, "obsolete");
        Files.writeString(foreignQuarantine, "foreign");
        Files.writeString(sibling, "sibling");
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(source.getParent());
        participants.register(participant);

        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), journal));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), previous));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), currentCorrupt));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), currentCollision));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), previousCorrupt));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), previousCollision));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), atomicTemp));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), atomicTempCollision));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), atomicBackupTemp));
        assertThrows(IOException.class, () -> participants.ownerFor(source.getParent(), nestedQuarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source.getParent(), malformedQuarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source.getParent(), obsoleteQuarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source.getParent(), foreignQuarantine));
        assertThrows(IOException.class, () -> participants.ownerFor(source.getParent(), sibling));
        assertThrows(IOException.class, participant::healthCheck);
        Files.delete(malformedQuarantine);
        assertThrows(IOException.class, participant::healthCheck);
        Files.delete(obsoleteQuarantine);
        participant.healthCheck();
        service.shutdown();
    }

    @Test
    void secondBootInventoryOwnsRecoverableJournalArtifacts() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("second-boot").resolve("runtime"));
        Path journal = source.resolve("automation-tasks.json");
        AutomationTaskService firstBoot = service(journal);
        firstBoot.startTimer(timer("persisted"), owner(), 60_000L, 0L);
        firstBoot.flushPersistence();
        firstBoot.shutdown();

        Files.writeString(journal, "{corrupt");
        AutomationTaskService secondBoot = service(journal);
        AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(source.getParent(), secondBoot);
        Path quarantine = source.resolve(".quarantine").resolve("journals");
        Path recoveredArtifact;
        try (var files = Files.list(quarantine)) {
            recoveredArtifact = files.findFirst().orElseThrow();
        }
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(source.getParent());
        participants.register(participant);

        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), journal));
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), source.resolve("automation-tasks.json.previous")));
        assertEquals("automation-tasks.json.corrupt", recoveredArtifact.getFileName().toString());
        assertEquals(participant.owner(), participants.ownerFor(source.getParent(), recoveredArtifact));
        secondBoot.shutdown();
    }

    private AutomationTaskService service(Path file) {
        return new AutomationTaskService(null, null, Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC),
            Executors.newSingleThreadScheduledExecutor(), new AutomationTaskStore(file));
    }

    private TimerDefinition timer(String id) {
        return new TimerDefinition(id, id, "", AutomationScope.SERVER, true, 0D, TimerDefinition.TimeUnit.SECONDS, 0D);
    }

    private AutomationOwner owner() {
        return new AutomationOwner("server", null);
    }
}
