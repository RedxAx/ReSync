package restudio.resync.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenGeneratedOutputControllerTest {
    @TempDir
    Path temporary;

    @Test
    void preparedRecoveryRestoresExactBackupWhenLiveRootIsAbsent() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("prepared-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Files.writeString(backup.resolve("authoritative.txt"), "authoritative");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=PREPARED\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void malformedJournalFailsClosedWithoutDeletingItsBackup() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("malformed-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage.invalid";
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(backup.resolve("authoritative.txt"), "authoritative");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=PREPARED\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertTrue(Files.exists(backup));
        assertTrue(Files.exists(parent.resolve("generated.transaction")));
    }

    @Test
    void backupPendingRecoveryPreservesLiveRootAndDiscardsUnactivatedStage() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("backup-pending-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Files.writeString(root.resolve("authoritative.txt"), "authoritative");
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=BACKUP_PENDING\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void activationPendingRecoveryRestoresBackupWhenActivationDidNotReachLiveRoot() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("activation-pending-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Files.writeString(backup.resolve("authoritative.txt"), "authoritative");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=ACTIVATION_PENDING\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void backedUpRecoveryRestoresBackupOverAnUncommittedCandidateRoot() throws Exception {
        assertUncommittedCandidateRecoveryRestoresBackup("BACKED_UP");
    }

    @Test
    void activationPendingRecoveryRestoresBackupOverAnUncommittedCandidateRoot() throws Exception {
        assertUncommittedCandidateRecoveryRestoresBackup("ACTIVATION_PENDING");
    }

    @Test
    void activatedRecoveryRestoresBackupOverAnUncommittedCandidateRoot() throws Exception {
        assertUncommittedCandidateRecoveryRestoresBackup("ACTIVATED");
    }

    @Test
    void rollbackPendingRecoveryFinalizesACompletedBackupRestore() throws Exception {
        assertCompletedRollbackJournalRecovery("ROLLBACK_PENDING");
    }

    @Test
    void rolledBackRecoveryFinalizesJournalCleanup() throws Exception {
        assertCompletedRollbackJournalRecovery("ROLLED_BACK");
    }

    @Test
    void rollbackPendingRecoveryFinishesRestoreAfterCandidateRemoval() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("rollback-pending-before-restore"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=ROLLBACK_PENDING\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("previous", Files.readString(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void committedRecoveryKeepsTheCommittedRootAndRemovesItsBackup() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("committed-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(root.resolve("committed.txt"), "committed");
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=COMMITTED\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("committed", Files.readString(controller.generatedRoot().resolve("committed.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void committedRecoveryFailsClosedInsteadOfRestoringThePreviousRoot() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("committed-missing-root"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=COMMITTED\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        IOException failure = assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));

        assertTrue(failure.getMessage().contains("Cannot Roll Back A Committed Root"));
        assertFalse(Files.exists(parent.resolve("generated")));
        assertEquals("previous", Files.readString(backup.resolve("previous.txt")));
        assertTrue(Files.exists(parent.resolve("generated.transaction")));
    }

    @Test
    void rebindRecoversTheTargetTransactionBeforePublishingItsRoot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("rebind-recovery-source"));
        Path replacement = Files.createDirectory(temporary.resolve("rebind-recovery-target"));
        Path parent = Files.createDirectory(replacement.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=BACKUP_PENDING\nstage="
            + stageName + "\nbackup=" + backupName + "\n");
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            source, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            controller.quiesce();

            controller.rebind(replacement);

            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, controller.state());
            assertEquals(WorldGenGeneratedOutputPolicy.root(replacement), controller.generatedRoot());
            assertEquals("previous", Files.readString(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    @Test
    void orphanStageIsMovedToRecoveryQuarantineWithoutAJournal() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("orphan-stage"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        String stageName = "generated.stage." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Files.writeString(root.resolve("authoritative.txt"), "authoritative");
        Files.writeString(stage.resolve("candidate.txt"), "candidate");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            Path quarantined = WorldGenGeneratedOutputController.recoveryQuarantine(parent).resolve(stageName);
            assertFalse(Files.exists(stage));
            assertEquals("candidate", Files.readString(quarantined.resolve("candidate.txt")));
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
        } finally {
            controller.close();
        }
    }

    @Test
    void orphanHandoffIsMovedToRecoveryQuarantineWithoutAJournal() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("orphan-handoff"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Files.createDirectory(parent.resolve("generated"));
        String handoffName = ".generated.handoff." + UUID.randomUUID();
        Path handoff = Files.createDirectory(parent.resolve(handoffName));
        Files.writeString(handoff.resolve("snapshot.txt"), "snapshot");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            Path quarantined = WorldGenGeneratedOutputController.recoveryQuarantine(parent).resolve(handoffName);
            assertFalse(Files.exists(handoff));
            assertEquals("snapshot", Files.readString(quarantined.resolve("snapshot.txt")));
        } finally {
            controller.close();
        }
    }

    @Test
    void orphanBackupIsQuarantinedWhenTheLiveRootIsPresent() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("orphan-backup"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Files.createDirectory(parent.resolve("generated"));
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(backup.resolve("previous.txt"), "previous");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            Path quarantined = WorldGenGeneratedOutputController.recoveryQuarantine(parent).resolve(backupName);
            assertFalse(Files.exists(backup));
            assertEquals("previous", Files.readString(quarantined.resolve("previous.txt")));
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.admissionOpen());
            assertFalse(controller.health().available());
            assertThrows(IOException.class, controller::healthCheck);
            assertTrue(Files.isRegularFile(startupRebuildIntent(controller)));
        } finally {
            controller.close();
        }
    }

    @Test
    void quarantinedOrphanBackupDurablyRequiresRebuildAcrossRestart() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("quarantined-orphan-backup"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Files.createDirectory(parent.resolve("generated"));
        Path quarantine = Files.createDirectories(WorldGenGeneratedOutputController.recoveryQuarantine(parent));
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(quarantine.resolve(backupName));
        Files.writeString(backup.resolve("previous.txt"), "previous");

        WorldGenGeneratedOutputController first = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertTrue(first.rebuildRequired());
            assertFalse(first.admissionOpen());
            assertTrue(Files.isRegularFile(startupRebuildIntent(first)));
            assertEquals("previous", Files.readString(backup.resolve("previous.txt")));
        } finally {
            first.close();
        }

        WorldGenGeneratedOutputController restarted = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertTrue(restarted.rebuildRequired());
            assertFalse(restarted.admissionOpen());
            assertEquals("previous", Files.readString(backup.resolve("previous.txt")));
        } finally {
            restarted.close();
        }
    }

    @Test
    void partialOrphanBackupQuarantinePassRemainsIdempotentlyFailClosed() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("partial-orphan-backup-pass"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Files.createDirectory(parent.resolve("generated"));
        Path quarantine = Files.createDirectories(WorldGenGeneratedOutputController.recoveryQuarantine(parent));
        String completedName = "generated.backup." + UUID.randomUUID();
        Path completed = Files.createDirectory(quarantine.resolve(completedName));
        Files.writeString(completed.resolve("first.txt"), "first");
        String pendingName = "generated.backup." + UUID.randomUUID();
        Path pending = Files.createDirectory(parent.resolve(pendingName));
        Files.writeString(pending.resolve("second.txt"), "second");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.admissionOpen());
            assertFalse(Files.exists(pending));
            assertEquals("first", Files.readString(completed.resolve("first.txt")));
            assertEquals("second", Files.readString(quarantine.resolve(pendingName).resolve("second.txt")));
            assertTrue(Files.isRegularFile(startupRebuildIntent(controller)));
        } finally {
            controller.close();
        }
    }

    @Test
    void committedJournalRecoveryFinishesBeforeQuarantiningAnUnlistedHandoff() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("journal-with-handoff"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        Files.writeString(root.resolve("authoritative.txt"), "authoritative");
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        String handoffName = ".generated.handoff." + UUID.randomUUID();
        Path handoff = Files.createDirectory(parent.resolve(handoffName));
        Files.writeString(handoff.resolve("snapshot.txt"), "snapshot");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=COMMITTED\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            Path quarantined = WorldGenGeneratedOutputController.recoveryQuarantine(parent).resolve(handoffName);
            assertFalse(Files.exists(handoff));
            assertEquals("snapshot", Files.readString(quarantined.resolve("snapshot.txt")));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
        } finally {
            controller.close();
        }
    }

    @Test
    void orphanStageWithoutLiveRootRequiresRebuildAfterQuarantine() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("orphan-stage-without-root"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            Path quarantined = WorldGenGeneratedOutputController.recoveryQuarantine(parent).resolve(stageName);
            assertTrue(Files.isDirectory(controller.generatedRoot()));
            assertFalse(Files.exists(stage));
            assertEquals("candidate", Files.readString(quarantined.resolve("candidate.txt")));
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.health().available());
            assertEquals("Rebuild Required", controller.health().failureReason());
            assertFalse(controller.admissionOpen());
            assertThrows(IllegalStateException.class, () -> controller.acquire("ordinary-mutation"));
            assertThrows(IOException.class, controller::flush);

            controller.rebuild();

            assertFalse(controller.rebuildRequired());
            assertTrue(controller.health().available());
            controller.flush();
        } finally {
            controller.close();
        }
    }

    @Test
    void activationAndRollbackFailurePreservesRecoveryEvidenceAndClosesAdmission() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("activation-rollback-failure"));
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        Path parent = controller.generatedRoot().getParent();
        try {
            IOException failure = assertThrows(IOException.class, () -> controller.transact("activation-failure", stage -> {
                Files.writeString(stage.resolve("candidate.txt"), "candidate");
                Files.delete(controller.generatedRoot());
                return Boolean.TRUE;
            }));

            assertTrue(failure.getMessage().contains("Move Source Is Invalid"));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].getMessage().contains("No Authoritative Root"));
            assertEquals(WorldGenGeneratedOutputController.State.FAILED, controller.state());
            assertFalse(controller.admissionOpen());
            assertTrue(Files.isRegularFile(parent.resolve("generated.transaction")));
            try (var entries = Files.list(parent)) {
                Path stage = entries
                    .filter(path -> path.getFileName().toString().startsWith("generated.stage."))
                    .findFirst()
                    .orElseThrow();
                assertEquals("candidate", Files.readString(stage.resolve("candidate.txt")));
            }
        } finally {
            controller.close();
        }
    }

    @Test
    void proofScopedStartupRebuildKeepsAdmissionClosedAndPublishesAtomically() throws Exception {
        CountDownLatch compileStarted = new CountDownLatch(1);
        CountDownLatch releaseCompile = new CountDownLatch(1);
        StartupFixture fixture = startupFixture("startup-rebuild", stage -> {
            compileStarted.countDown();
            try {
                if (!releaseCompile.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Startup rebuild test compile timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Startup rebuild test compile was interrupted", exception);
            }
            Files.writeString(stage.resolve("current.txt"), "current");
        });
        Files.writeString(fixture.controller.generatedRoot().resolve("previous.txt"), "previous");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ReSyncPersistenceCoordinator.ReadinessProof> activation = executor.submit(() ->
                fixture.coordinator.completeStartupActivation(fixture.proof,
                    Set.of(WorldGenGeneratedOutputPolicy.OWNER), context -> {
                        fixture.controller.rebuildForStartup(context, () -> {
                        });
                        assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
                        assertFalse(fixture.controller.admissionOpen());
                    }));

            assertTrue(compileStarted.await(2, TimeUnit.SECONDS));
            assertEquals(WorldGenGeneratedOutputController.State.RESUMING, fixture.controller.state());
            assertFalse(fixture.controller.admissionOpen());
            assertThrows(IllegalStateException.class, () -> fixture.controller.acquire("blocked-compile"));
            assertEquals("previous", Files.readString(fixture.controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("current.txt")));
            assertTrue(Files.isRegularFile(startupRebuildIntent(fixture.controller)));

            releaseCompile.countDown();
            activation.get(2, TimeUnit.SECONDS);

            assertEquals(WorldGenGeneratedOutputController.State.OPEN, fixture.controller.state());
            assertTrue(fixture.controller.admissionOpen());
            assertEquals("current", Files.readString(fixture.controller.generatedRoot().resolve("current.txt")));
            assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
        } finally {
            releaseCompile.countDown();
            executor.shutdownNow();
            fixture.close();
        }
    }

    @Test
    void failedProofScopedStartupRebuildPreservesPreviousRootAndClosesAdmission() throws Exception {
        StartupFixture fixture = startupFixture("failed-startup-rebuild", stage -> {
            Files.writeString(stage.resolve("candidate.txt"), "candidate");
            throw new IOException("startup candidate rejected");
        });
        Files.writeString(fixture.controller.generatedRoot().resolve("previous.txt"), "previous");
        AtomicReference<WorldGenGeneratedOutputController.State> callbackFailureState = new AtomicReference<>();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                fixture.coordinator.completeStartupActivation(fixture.proof,
                    Set.of(WorldGenGeneratedOutputPolicy.OWNER), context -> {
                        try {
                            fixture.controller.rebuildForStartup(context, () -> {
                            });
                        } catch (IOException exception) {
                            callbackFailureState.set(fixture.controller.state());
                            assertEquals("previous", Files.readString(
                                fixture.controller.generatedRoot().resolve("previous.txt")));
                            assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("candidate.txt")));
                            throw exception;
                        }
                    }));

            assertTrue(failure.getMessage().contains("Derived Startup Preparation Failed"));
            assertEquals(WorldGenGeneratedOutputController.State.DEGRADED, callbackFailureState.get());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
            assertTrue(fixture.controller.rebuildRequired());
            assertFalse(fixture.controller.admissionOpen());
            assertTrue(Files.isRegularFile(startupRebuildIntent(fixture.controller)));
            assertEquals("previous", Files.readString(fixture.controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("candidate.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void failedProofScopedStartupValidationRollsBackTheActivatedCandidate() throws Exception {
        WorldGenGeneratedOutputController.Rebuilder rebuilder = new WorldGenGeneratedOutputController.Rebuilder() {
            @Override
            public void rebuild(Path stage) throws IOException {
                Files.writeString(stage.resolve("candidate.txt"), "candidate");
            }

            @Override
            public void healthCheck() throws IOException {
                throw new IOException("startup candidate health rejected");
            }
        };
        StartupFixture fixture = startupFixture("failed-startup-validation", rebuilder);
        Files.writeString(fixture.controller.generatedRoot().resolve("previous.txt"), "previous");
        AtomicReference<WorldGenGeneratedOutputController.State> callbackFailureState = new AtomicReference<>();
        try {
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER), context -> {
                    try {
                        fixture.controller.rebuildForStartup(context, () -> {
                        });
                    } catch (IOException exception) {
                        callbackFailureState.set(fixture.controller.state());
                        assertEquals("startup candidate health rejected", exception.getMessage());
                        assertEquals("previous", Files.readString(
                            fixture.controller.generatedRoot().resolve("previous.txt")));
                        assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("candidate.txt")));
                        throw exception;
                    }
                }));

            assertEquals(WorldGenGeneratedOutputController.State.DEGRADED, callbackFailureState.get());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
            assertTrue(fixture.controller.rebuildRequired());
            assertFalse(fixture.controller.admissionOpen());
            assertTrue(Files.isRegularFile(startupRebuildIntent(fixture.controller)));
            assertEquals("previous", Files.readString(fixture.controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(fixture.controller.generatedRoot().resolve("candidate.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void failedStartupPreparationPersistsIntentAndRestartRemainsUnavailable() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        StartupFixture fixture = startupFixture("failed-startup-preparation", stage -> rebuilds.incrementAndGet());
        Path activeRoot = fixture.controller.scopeRoot();
        Files.writeString(fixture.controller.generatedRoot().resolve("previous.txt"), "previous");
        try {
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER), context -> fixture.controller.rebuildForStartup(context,
                    () -> {
                        throw new IOException("recipe persistence rejected");
                    })));

            assertEquals(0, rebuilds.get());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
            assertTrue(fixture.controller.rebuildRequired());
            assertFalse(fixture.controller.admissionOpen());
            assertTrue(Files.isRegularFile(startupRebuildIntent(fixture.controller)));
            assertEquals("previous", Files.readString(fixture.controller.generatedRoot().resolve("previous.txt")));
        } finally {
            fixture.close();
        }

        WorldGenGeneratedOutputController restarted = new WorldGenGeneratedOutputController(
            activeRoot, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertTrue(restarted.rebuildRequired());
            assertFalse(restarted.admissionOpen());
            assertFalse(restarted.health().available());
            assertThrows(IOException.class, restarted::healthCheck);
            assertTrue(WorldGenGeneratedOutputController.ownsRecoveryArtifact(
                restarted.generatedRoot().getParent(), startupRebuildIntent(restarted)));
            restarted.quiesce();
            assertThrows(IOException.class, restarted::resume);
            assertTrue(Files.isRegularFile(startupRebuildIntent(restarted)));
        } finally {
            restarted.close();
        }
    }

    @Test
    void malformedStartupRebuildIntentFailsClosedWithoutDeletingEvidence() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("malformed-startup-rebuild-intent"));
        Path parent = Files.createDirectories(scope.resolve("worldgen"));
        Path intent = WorldGenGeneratedOutputController.startupRebuildIntent(parent);
        Files.writeString(intent, "invalid");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertEquals("invalid", Files.readString(intent));
    }

    @Test
    void targetBoundStartupIntentTemporaryIsOwnedAndRecoveredFailClosedOnRestart() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("startup-intent-atomic-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path target = WorldGenGeneratedOutputController.startupRebuildIntent(parent);
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.writeString(atomicTemporary, "version=1\nowner=" + WorldGenGeneratedOutputPolicy.OWNER + "\n");
        Path genericTemporary = parent.resolve(".resync-" + UUID.randomUUID() + ".tmp");
        Files.writeString(genericTemporary, "unowned");

        assertTrue(WorldGenGeneratedOutputController.ownsRecoveryArtifact(parent, atomicTemporary));
        assertFalse(WorldGenGeneratedOutputController.ownsRecoveryArtifact(parent, genericTemporary));

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertFalse(Files.exists(atomicTemporary));
            assertTrue(Files.isRegularFile(target));
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.admissionOpen());
            assertFalse(controller.health().available());
            assertThrows(IOException.class, controller::healthCheck);
            assertEquals("unowned", Files.readString(genericTemporary));
        } finally {
            controller.close();
        }
    }

    @Test
    void targetBoundJournalTemporaryIsPublishedAndRecoveredOnRestart() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("journal-atomic-recovery"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        Files.writeString(root.resolve("authoritative.txt"), "authoritative");
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Path target = parent.resolve("generated.transaction");
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.writeString(atomicTemporary, "version=1\nphase=PREPARED\nstage=" + stageName
            + "\nbackup=" + backupName + "\n");

        assertTrue(WorldGenGeneratedOutputController.ownsRecoveryArtifact(parent, atomicTemporary));

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(atomicTemporary));
            assertFalse(Files.exists(target));
        } finally {
            controller.close();
        }
    }

    @Test
    void emptyStartupIntentTemporaryBesideValidTargetIsDiscarded() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("empty-startup-temp-with-target"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path target = WorldGenGeneratedOutputController.startupRebuildIntent(parent);
        Files.writeString(target, "version=1\nowner=" + WorldGenGeneratedOutputPolicy.OWNER + "\n");
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.createFile(atomicTemporary);

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertFalse(Files.exists(atomicTemporary));
            assertTrue(controller.rebuildRequired());
            assertTrue(Files.isRegularFile(target));
        } finally {
            controller.close();
        }
    }

    @Test
    void partialJournalTemporaryBesideValidTargetIsDiscardedBeforeJournalRecovery() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("partial-journal-temp-with-target"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        Files.writeString(root.resolve("authoritative.txt"), "authoritative");
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path stage = Files.createDirectory(parent.resolve(stageName));
        Files.writeString(stage.resolve("candidate.txt"), "candidate");
        Path target = parent.resolve("generated.transaction");
        Files.writeString(target, "version=1\nphase=PREPARED\nstage=" + stageName
            + "\nbackup=" + backupName + "\n");
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.writeString(atomicTemporary, "version=1\nphase=");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertFalse(Files.exists(atomicTemporary));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(stage));
            assertEquals("authoritative", Files.readString(controller.generatedRoot().resolve("authoritative.txt")));
        } finally {
            controller.close();
        }
    }

    @Test
    void emptyStartupIntentTemporaryWithoutTargetIsPreservedAndRequiresRebuild() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("empty-startup-temp-without-target"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path target = WorldGenGeneratedOutputController.startupRebuildIntent(parent);
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.createFile(atomicTemporary);

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertTrue(Files.isRegularFile(atomicTemporary));
            assertFalse(Files.exists(target));
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.admissionOpen());
            assertThrows(IOException.class, controller::healthCheck);
        } finally {
            controller.close();
        }
    }

    @Test
    void partialJournalTemporaryWithoutTargetIsPreservedAndRequiresRebuild() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("partial-journal-temp-without-target"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Files.createDirectory(parent.resolve("generated"));
        Path target = parent.resolve("generated.transaction");
        Path atomicTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(target,
            UUID.randomUUID());
        Files.writeString(atomicTemporary, "version=1\nphase=");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("version=1\nphase=", Files.readString(atomicTemporary));
            assertFalse(Files.exists(target));
            assertTrue(controller.rebuildRequired());
            assertFalse(controller.admissionOpen());
            assertThrows(IOException.class, controller::healthCheck);
        } finally {
            controller.close();
        }
    }

    @Test
    void bothDerivedParticipantsOpenOnlyAfterScopedActivation() throws Exception {
        StartupFixture fixture = startupFixture("both-derived-activation", stage ->
            Files.writeString(stage.resolve("current.txt"), "current"), true);
        try {
            fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER, WorldGenInstalledDatapackCapability.OWNER), context -> {
                    fixture.controller.rebuildForStartup(context, () -> {
                    });
                    assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
                    assertFalse(fixture.controller.admissionOpen());
                    assertFalse(fixture.controller.rebuildRequired());
                    assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
                    fixture.installedAvailable.set(true);
                });

            assertEquals(WorldGenGeneratedOutputController.State.OPEN, fixture.controller.state());
            assertTrue(fixture.controller.admissionOpen());
            assertEquals("current", Files.readString(fixture.controller.generatedRoot().resolve("current.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void successfulProofScopedRebuildClearsQuarantinedBackupEvidenceAndIntent() throws Exception {
        StartupFixture fixture = startupFixture("quarantined-backup-rebuild", stage ->
            Files.writeString(stage.resolve("current.txt"), "current"));
        Path parent = fixture.controller.generatedRoot().getParent();
        Path quarantine = Files.createDirectories(WorldGenGeneratedOutputController.recoveryQuarantine(parent));
        Path backup = Files.createDirectory(quarantine.resolve("generated.backup." + UUID.randomUUID()));
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(startupRebuildIntent(fixture.controller), "version=1\nowner="
            + WorldGenGeneratedOutputPolicy.OWNER + "\n");
        try {
            fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER), context ->
                    fixture.controller.rebuildForStartup(context, () -> {
                    }));

            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
            assertFalse(fixture.controller.rebuildRequired());
            assertTrue(fixture.controller.admissionOpen());
            assertEquals("current", Files.readString(fixture.controller.generatedRoot().resolve("current.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void proofScopedRebuildRetiresInvalidAtomicEvidenceOnlyAfterPublishingIntent() throws Exception {
        StartupFixture fixture = startupFixture("invalid-atomic-evidence-rebuild", stage ->
            Files.writeString(stage.resolve("current.txt"), "current"));
        Path parent = fixture.controller.generatedRoot().getParent();
        Path intentTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(
            WorldGenGeneratedOutputController.startupRebuildIntent(parent), UUID.randomUUID());
        Files.createFile(intentTemporary);
        Path journalTemporary = WorldGenGeneratedOutputController.controllerAtomicTemporary(
            parent.resolve("generated.transaction"), UUID.randomUUID());
        Files.writeString(journalTemporary, "version=1\nphase=");
        try {
            fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER), context ->
                    fixture.controller.rebuildForStartup(context, () -> {
                    }));

            assertFalse(Files.exists(intentTemporary));
            assertFalse(Files.exists(journalTemporary));
            assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
            assertFalse(fixture.controller.rebuildRequired());
            assertTrue(fixture.controller.admissionOpen());
            assertEquals("current", Files.readString(fixture.controller.generatedRoot().resolve("current.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void laterDerivedActivationFailureRequiescesValidatedGeneratedOutput() throws Exception {
        StartupFixture fixture = startupFixture("installed-activation-failure", stage ->
            Files.writeString(stage.resolve("current.txt"), "current"), true);
        try {
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER, WorldGenInstalledDatapackCapability.OWNER), context -> {
                    fixture.controller.rebuildForStartup(context, () -> {
                    });
                    assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
                    assertFalse(fixture.controller.admissionOpen());
                    assertFalse(fixture.controller.rebuildRequired());
                    assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
                }));

            assertFalse(fixture.installedAvailable.get());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, fixture.controller.state());
            assertFalse(fixture.controller.admissionOpen());
            assertFalse(fixture.controller.rebuildRequired());
            assertFalse(Files.exists(startupRebuildIntent(fixture.controller)));
            assertEquals("current", Files.readString(fixture.controller.generatedRoot().resolve("current.txt")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void startupRebuildRejectsWrongOwnerBeforeChangingControllerState() throws Exception {
        ContextFixture fixture = contextFixture("wrong-startup-owner", "other-derived");
        Path controllerScope = Files.createDirectory(temporary.resolve("wrong-startup-owner-controller"));
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            controllerScope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        controller.quiesce();
        try {
            fixture.coordinator.completeStartupActivation(fixture.proof, Set.of("other-derived"), context -> {
                assertThrows(IllegalStateException.class, () -> controller.rebuildForStartup(context, () -> {
                }));
                assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, controller.state());
                fixture.derivedAvailable.set(true);
            });
        } finally {
            controller.close();
            fixture.close();
        }
    }

    @Test
    void startupRebuildRejectsWrongRootAndExpiredContext() throws Exception {
        ContextFixture fixture = contextFixture("wrong-startup-root", WorldGenGeneratedOutputPolicy.OWNER);
        Path otherScope = Files.createDirectory(temporary.resolve("wrong-startup-root-other"));
        WorldGenGeneratedOutputController wrongRoot = new WorldGenGeneratedOutputController(
            otherScope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        WorldGenGeneratedOutputController matchingRoot = new WorldGenGeneratedOutputController(
            fixture.activeRoot, fixture.fence, Duration.ofSeconds(2), ignored -> {
            });
        AtomicReference<ReSyncPersistenceCoordinator.DerivedStartupContext> expired = new AtomicReference<>();
        wrongRoot.quiesce();
        matchingRoot.quiesce();
        try {
            fixture.coordinator.completeStartupActivation(fixture.proof,
                Set.of(WorldGenGeneratedOutputPolicy.OWNER), context -> {
                    assertThrows(IllegalStateException.class, () -> wrongRoot.rebuildForStartup(context, () -> {
                    }));
                    assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, wrongRoot.state());
                    expired.set(context);
                    fixture.derivedAvailable.set(true);
                });

            assertThrows(IllegalStateException.class,
                () -> matchingRoot.rebuildForStartup(expired.get(), () -> {
                }));
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, matchingRoot.state());
            assertTrue(Arrays.stream(ReSyncPersistenceCoordinator.DerivedStartupContext.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        } finally {
            wrongRoot.close();
            matchingRoot.close();
            fixture.close();
        }
    }

    @Test
    void orphanBackupWithoutLiveRootFailsClosedAndPreservesTheBackup() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("orphan-backup-without-root"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(backup.resolve("authoritative.txt"), "authoritative");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertEquals("authoritative", Files.readString(backup.resolve("authoritative.txt")));
        assertFalse(Files.exists(WorldGenGeneratedOutputController.recoveryQuarantine(parent)));
    }

    @Test
    void malformedOrphanNamesFailClosedWithoutAJournal() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("malformed-orphan"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path malformed = Files.createDirectory(parent.resolve("generated.stage.invalid"));
        Files.writeString(malformed.resolve("candidate.txt"), "candidate");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertTrue(Files.exists(malformed));
        assertEquals("candidate", Files.readString(malformed.resolve("candidate.txt")));
    }

    @Test
    void nonDirectoryOrphanFailsClosedWithoutAJournal() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("non-directory-orphan"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        String stageName = "generated.stage." + UUID.randomUUID();
        Path stage = parent.resolve(stageName);
        Files.writeString(stage, "not-a-directory");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertEquals("not-a-directory", Files.readString(stage));
    }

    @Test
    void unknownRecoveryQuarantineEntryFailsClosed() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("unknown-recovery-entry"));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path quarantine = Files.createDirectories(parent.resolve(".quarantine").resolve("worldgen-generated"));
        Path unknown = Files.createDirectory(quarantine.resolve("unknown-artifact"));
        Files.writeString(unknown.resolve("value.txt"), "value");

        assertThrows(IOException.class, () -> new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            }));
        assertEquals("value", Files.readString(unknown.resolve("value.txt")));
    }

    @Test
    void concurrentHandoffsPinSuccessiveBuildsUntilBothConsumersReleaseThem() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("scope"));
        AtomicInteger rebuilds = new AtomicInteger();
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), root -> {
                rebuilds.incrementAndGet();
                Files.writeString(root.resolve("build.txt"), "resume");
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorldGenGeneratedOutputController.Handoff handoffA = controller.acquireHandoff("compile-a");
        WorldGenGeneratedOutputController.Handoff handoffB = controller.acquireHandoff("compile-b");
        try {
            WorldGenGeneratedOutputController.TransactionResult<Boolean> transactionA = handoffA.transact(stage -> {
                Files.writeString(stage.resolve("build-a.txt"), "A");
                return Boolean.TRUE;
            });
            Path buildA = transactionA.activePath(transactionA.stagingRoot().resolve("build-a.txt"));

            WorldGenGeneratedOutputController.TransactionResult<Boolean> transactionB = executor.submit(() -> handoffB.transact(stage -> {
                Files.writeString(stage.resolve("build-b.txt"), "B");
                return Boolean.TRUE;
            })).get(2, TimeUnit.SECONDS);
            Path buildB = transactionB.activePath(transactionB.stagingRoot().resolve("build-b.txt"));

            assertEquals(2, controller.activeOperationCount());
            assertEquals("A", Files.readString(buildA));
            assertEquals("B", Files.readString(buildB));

            WorldGenGeneratedOutputController.TransactionResult<Boolean> normal = controller.transact("compile-current", stage -> {
                Files.writeString(stage.resolve("build-current.txt"), "current");
                return Boolean.TRUE;
            });
            Path current = normal.activePath(normal.stagingRoot().resolve("build-current.txt"));

            assertEquals("A", Files.readString(buildA));
            assertEquals("B", Files.readString(buildB));
            assertEquals("current", Files.readString(current));

            handoffB.close();
            handoffA.close();
            assertEquals(0, controller.activeOperationCount());
            assertFalse(Files.exists(buildA));
            assertFalse(Files.exists(buildB));
            assertFalse(Files.exists(controller.generatedRoot().resolve(".handoffs")));
            assertTrue(Files.isRegularFile(current));

            controller.quiesce();
            assertEquals("current", Files.readString(current));
            controller.resume();

            assertEquals(0, rebuilds.get());
            assertEquals("current", Files.readString(current));
            assertFalse(Files.exists(controller.generatedRoot().resolve("build.txt")));
        } finally {
            handoffB.close();
            handoffA.close();
            executor.shutdownNow();
            controller.close();
        }
    }

    @Test
    void openRebuildPublishesTheCandidateWithoutClearingThePreviousRootFirst() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("open-rebuild"));
        AtomicBoolean previousVisibleDuringBuild = new AtomicBoolean();
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), root -> {
                previousVisibleDuringBuild.set("previous".equals(Files.readString(
                    WorldGenGeneratedOutputPolicy.root(scope).resolve("previous.txt"))));
                Files.writeString(root.resolve("candidate.txt"), "candidate");
            });
        try {
            Files.writeString(controller.generatedRoot().resolve("previous.txt"), "previous");

            controller.rebuild();

            assertTrue(previousVisibleDuringBuild.get());
            assertEquals("candidate", Files.readString(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("previous.txt")));
            assertEquals(WorldGenGeneratedOutputController.State.OPEN, controller.state());
        } finally {
            controller.close();
        }
    }

    @Test
    void failedOpenRebuildRestoresTheExactPreviousRoot() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("failed-open-rebuild"));
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), root -> {
                Files.writeString(root.resolve("candidate.txt"), "candidate");
                throw new IOException("candidate rejected");
            });
        try {
            Files.writeString(controller.generatedRoot().resolve("previous.txt"), "previous");

            IOException failure = assertThrows(IOException.class, controller::rebuild);

            assertEquals("candidate rejected", failure.getMessage());
            assertEquals("previous", Files.readString(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertEquals(WorldGenGeneratedOutputController.State.OPEN, controller.state());
            assertFalse(Files.exists(controller.generatedRoot().getParent().resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    private static Path startupRebuildIntent(WorldGenGeneratedOutputController controller) {
        return WorldGenGeneratedOutputController.startupRebuildIntent(controller.generatedRoot().getParent());
    }

    private void assertUncommittedCandidateRecoveryRestoresBackup(String phase) throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("uncommitted-candidate-" + phase));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Path backup = Files.createDirectory(parent.resolve(backupName));
        Files.writeString(root.resolve("candidate.txt"), "candidate");
        Files.writeString(backup.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=" + phase + "\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("previous", Files.readString(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(controller.generatedRoot().resolve("candidate.txt")));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    private void assertCompletedRollbackJournalRecovery(String phase) throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("completed-rollback-" + phase));
        Path parent = Files.createDirectory(scope.resolve("worldgen"));
        Path root = Files.createDirectory(parent.resolve("generated"));
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        Files.writeString(root.resolve("previous.txt"), "previous");
        Files.writeString(parent.resolve("generated.transaction"), "version=1\nphase=" + phase + "\nstage="
            + stageName + "\nbackup=" + backupName + "\n");

        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            scope, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        try {
            assertEquals("previous", Files.readString(controller.generatedRoot().resolve("previous.txt")));
            assertFalse(Files.exists(parent.resolve("generated.transaction")));
        } finally {
            controller.close();
        }
    }

    private StartupFixture startupFixture(String id, WorldGenGeneratedOutputController.Rebuilder rebuilder)
        throws Exception {
        return startupFixture(id, rebuilder, false);
    }

    private StartupFixture startupFixture(String id, WorldGenGeneratedOutputController.Rebuilder rebuilder,
                                          boolean includeInstalledDatapack) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(id));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path installedRoot = includeInstalledDatapack
            ? Files.createDirectory(dataRoot.resolve("installed")) : dataRoot;
        MigrationFence fence = new MigrationFence();
        WorldGenGeneratedOutputController controller = new WorldGenGeneratedOutputController(
            dataRoot, fence, Duration.ofSeconds(2), rebuilder);
        controller.quiesce();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve(id + "-coordination"), fence);
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative",
            PersistenceParticipantClassification.AUTHORITATIVE, new AtomicBoolean(true));
        WorldGenGeneratedPersistenceParticipant generated = new WorldGenGeneratedPersistenceParticipant(
            dataRoot, controller);
        AtomicBoolean installedAvailable = new AtomicBoolean(!includeInstalledDatapack);
        coordinator.register(authoritative);
        coordinator.register(generated);
        List<PersistenceRootReadiness.Owner> owners = new ArrayList<>();
        owners.add(PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
            PersistenceParticipantClassification.AUTHORITATIVE));
        owners.add(PersistenceRootReadiness.Owner.unavailable(WorldGenGeneratedOutputPolicy.OWNER,
            controller.generatedRoot(), false, PersistenceParticipantClassification.DERIVED_CACHE,
            "Generated output requires startup rebuild"));
        if (includeInstalledDatapack) {
            coordinator.register(new StartupParticipant(WorldGenInstalledDatapackCapability.OWNER, installedRoot,
                "installed", PersistenceParticipantClassification.DERIVED_CACHE, installedAvailable));
            owners.add(PersistenceRootReadiness.Owner.unavailable(WorldGenInstalledDatapackCapability.OWNER,
                installedRoot, false, PersistenceParticipantClassification.DERIVED_CACHE,
                "Installed datapack requires startup activation"));
        }
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(owners);
        ReSyncPersistenceCoordinator.ReadinessProof proof = coordinator.seal(readiness);
        return new StartupFixture(coordinator, proof, controller, installedAvailable);
    }

    private ContextFixture contextFixture(String id, String derivedOwner) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(id));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        String derivedRelativeRoot = WorldGenGeneratedOutputPolicy.OWNER.equals(derivedOwner)
            ? WorldGenGeneratedOutputPolicy.RELATIVE_ROOT : "derived";
        Path derivedRoot = Files.createDirectories(dataRoot.resolve(derivedRelativeRoot));
        MigrationFence fence = new MigrationFence();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve(id + "-coordination"), fence);
        AtomicBoolean derivedAvailable = new AtomicBoolean();
        coordinator.register(new StartupParticipant("authoritative", authoritativeRoot, "authoritative",
            PersistenceParticipantClassification.AUTHORITATIVE, new AtomicBoolean(true)));
        coordinator.register(new StartupParticipant(derivedOwner, derivedRoot, derivedRelativeRoot,
            PersistenceParticipantClassification.DERIVED_CACHE, derivedAvailable));
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.unavailable(derivedOwner, derivedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Derived output requires startup rebuild")));
        ReSyncPersistenceCoordinator.ReadinessProof proof = coordinator.seal(readiness);
        return new ContextFixture(coordinator, fence, proof, proof.activeRoot(), derivedAvailable);
    }

    private static final class StartupParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final String relativeRoot;
        private final PersistenceParticipantClassification classification;
        private final AtomicBoolean available;
        private Path activeRoot;

        private StartupParticipant(String owner, Path root, String relativeRoot,
                                   PersistenceParticipantClassification classification, AtomicBoolean available) {
            this.owner = owner;
            this.activeRoot = root;
            this.relativeRoot = relativeRoot;
            this.classification = classification;
            this.available = available;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public PersistenceParticipantClassification classification() {
            return classification;
        }

        @Override
        public void flush() throws IOException {
            requireAvailable();
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void rebind(Path activeRoot) {
            this.activeRoot = activeRoot.resolve(relativeRoot);
        }

        @Override
        public void healthCheck() throws IOException {
            requireAvailable();
        }

        private void requireAvailable() throws IOException {
            if (!available.get()) {
                throw new IOException("Participant is unavailable");
            }
        }
    }

    private record StartupFixture(ReSyncPersistenceCoordinator coordinator,
                                  ReSyncPersistenceCoordinator.ReadinessProof proof,
                                  WorldGenGeneratedOutputController controller,
                                  AtomicBoolean installedAvailable) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            coordinator.close();
        }
    }

    private record ContextFixture(ReSyncPersistenceCoordinator coordinator, MigrationFence fence,
                                  ReSyncPersistenceCoordinator.ReadinessProof proof, Path activeRoot,
                                  AtomicBoolean derivedAvailable) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            coordinator.close();
        }
    }
}
