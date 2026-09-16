package restudio.resync.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceShutdownStatus;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenGeneratedPersistenceParticipantTest {
    private static final String CATALOG_CHECKSUM = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void derivedOutputRemainsAvailableAcrossQuiesceAndResume() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        Path external = Files.createDirectory(temporary.resolve("world"));
        Files.writeString(external.resolve("sentinel.txt"), "untouched");
        AtomicInteger rebuilds = new AtomicInteger();
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, root -> {
            rebuilds.incrementAndGet();
            Files.createDirectories(root.resolve("current"));
            Files.writeString(root.resolve("current").resolve("pack.json"), "rebuilt");
        });
        Path stale = participant.root().resolve("stale").resolve("pack.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        participant.quiesce();

        assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
        assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().health().state());
        assertFalse(participant.controller().health().available());
        assertEquals("stale", Files.readString(stale));
        assertEquals("untouched", Files.readString(external.resolve("sentinel.txt")));

        participant.resume();

        assertEquals(0, rebuilds.get());
        assertEquals("stale", Files.readString(stale));
        assertFalse(Files.exists(participant.root().resolve("current").resolve("pack.json")));
        participant.close();
        assertEquals(WorldGenGeneratedOutputController.State.CLOSED, participant.controller().health().state());
        assertFalse(participant.controller().health().available());
    }

    @Test
    void coordinatorPreservesDerivedOutputBeforeCheckingReadiness() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("coordinator-source"));
        AtomicInteger rebuilds = new AtomicInteger();
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, root -> {
            rebuilds.incrementAndGet();
            Files.writeString(root.resolve("rebuilt.txt"), "rebuilt");
        });
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordinator-coordination"), new MigrationFence());
        coordinator.register(participant);

        coordinator.seal();

        assertEquals(0, rebuilds.get());
        assertFalse(Files.exists(participant.root().resolve("rebuilt.txt")));
        assertTrue(coordinator.restoreReady());
        assertEquals(PersistenceShutdownStatus.State.CLOSED, coordinator.close().state());
    }

    @Test
    void failedFlushRetainsTheAuthoritativeFailureAndControllerState() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("flush-failure"));
        WorldGenGeneratedOutputController.Rebuilder rebuilder = new WorldGenGeneratedOutputController.Rebuilder() {
            @Override
            public void rebuild(Path generatedRoot) {
            }

            @Override
            public void healthCheck() throws IOException {
                throw new IOException("WorldGen Rebuild Recipe Does Not Match Authoritative Assets");
            }
        };
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, rebuilder);
        try {
            IOException failure = assertThrows(IOException.class, participant::flush);

            assertEquals("WorldGen Rebuild Recipe Does Not Match Authoritative Assets", failure.getMessage());
            assertEquals(WorldGenGeneratedOutputController.State.OPEN, participant.controller().state());
            assertTrue(participant.controller().admissionOpen());
            assertEquals(0, participant.controller().activeOperationCount());
        } finally {
            participant.close();
        }
    }

    @Test
    void diagnosticFailureReasonIsBoundedAndDropsSensitiveValues() {
        String bounded = WorldGenGeneratedPersistenceParticipant.diagnosticReason(new IOException("x".repeat(300)));
        String redacted = WorldGenGeneratedPersistenceParticipant.diagnosticReason(
            new IOException("authorization token=should-not-appear"));

        assertEquals(240, bounded.length());
        assertEquals("IOException", redacted);
        assertFalse(redacted.contains("should-not-appear"));
    }

    @Test
    void asyncCompilationIsCancelledAndDrainedBeforeGeneratedRootIsQuiesced() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("async"));
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, ignored -> {
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            var future = participant.controller().submit("worldgen-compile", executor, token -> {
                started.countDown();
                while (!token.isCancellationRequested()) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignored) {
                        token.throwIfCancellationRequested();
                    }
                }
                token.throwIfCancellationRequested();
                return "unreachable";
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            participant.quiesce();

            assertTrue(future.isCancelled());
            assertEquals(0, participant.controller().activeOperationCount());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
            assertThrows(CancellationException.class, future::join);
            participant.resume();
        } finally {
            executor.shutdownNow();
            participant.close();
        }
    }

    @Test
    void ignoredCancellationFailsClosedUntilTheActiveCompilationFinishes() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("timeout"));
        WorldGenGeneratedPersistenceParticipant participant = new WorldGenGeneratedPersistenceParticipant(
            dataRoot,
            new MigrationFence(),
            Duration.ofMillis(50),
            ignored -> {
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            participant.controller().submit("stuck-worldgen-compile", executor, ignored -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignoredInterrupt) {
                    }
                }
                return "finished";
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertThrows(IOException.class, () -> participant.controller().quiesce(Duration.ofMillis(50)));
            assertEquals(WorldGenGeneratedOutputController.State.FAILED, participant.controller().state());
            assertEquals(1, participant.controller().activeOperationCount());

            release.countDown();
            assertTrue(participant.controller().awaitIdle(Duration.ofSeconds(2)));
            participant.controller().quiesce(Duration.ofSeconds(2));
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
            participant.resume();
        } finally {
            release.countDown();
            executor.shutdownNow();
            participant.close();
        }
    }

    @Test
    void rebindUsesAndPreservesOnlyTheExactGeneratedRoot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source-rebind"));
        Path replacement = Files.createDirectory(temporary.resolve("replacement-rebind"));
        Path external = Files.createDirectory(temporary.resolve("external-rebind"));
        Files.writeString(external.resolve("sentinel.txt"), "untouched");
        WorldGenGeneratedPersistenceParticipant participant = participant(source, root -> {
            Files.createDirectories(root.resolve("rebuilt"));
            Files.writeString(root.resolve("rebuilt").resolve("pack.json"), "replacement");
        });
        Path staleReplacement = WorldGenGeneratedOutputPolicy.root(replacement).resolve("stale.json");
        Files.createDirectories(staleReplacement.getParent());
        Files.writeString(staleReplacement, "stale");

        participant.quiesce();
        participant.rebind(replacement);

        assertEquals(WorldGenGeneratedOutputPolicy.root(replacement), participant.root());
        assertEquals("stale", Files.readString(staleReplacement));
        assertEquals("untouched", Files.readString(external.resolve("sentinel.txt")));

        participant.resume();

        assertEquals("stale", Files.readString(staleReplacement));
        assertFalse(Files.exists(participant.root().resolve("rebuilt").resolve("pack.json")));
        participant.close();
    }

    @Test
    void generatedBytesRemainLiveButAreNotRetainedInSnapshots() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("snapshot"));
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, ignored -> {
        });
        Path generatedFile = participant.root().resolve("project").resolve("revision").resolve("pack.mcmeta");
        Files.createDirectories(generatedFile.getParent());
        Files.writeString(generatedFile, "generated");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("snapshot-coordination"), new MigrationFence());
        coordinator.register(participant);
        coordinator.seal();

        Snapshot snapshot = coordinator.createSnapshot(
            coordinator.snapshotRoot().resolve("generated-cache"),
            new SnapshotMetadata(1, "generated-cache", Instant.EPOCH, "test", CATALOG_CHECKSUM, Map.of()));

        assertTrue(snapshot.verified());
        assertTrue(Files.isDirectory(snapshot.root().resolve(WorldGenGeneratedOutputPolicy.RELATIVE_ROOT)));
        assertFalse(Files.exists(snapshot.root().resolve(WorldGenGeneratedOutputPolicy.RELATIVE_ROOT)
            .resolve("project").resolve("revision").resolve("pack.mcmeta")));
        assertEquals("generated", Files.readString(generatedFile));
        coordinator.close();
    }

    @Test
    void failedResumeHealthCheckRemainsRollbackRebindCapable() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("resume-failure-source"));
        Path replacement = Files.createDirectory(temporary.resolve("resume-failure-replacement"));
        AtomicBoolean fail = new AtomicBoolean(true);
        WorldGenGeneratedOutputController.Rebuilder rebuilder = new WorldGenGeneratedOutputController.Rebuilder() {
            @Override
            public void rebuild(Path generatedRoot) {
            }

            @Override
            public void healthCheck() throws IOException {
                if (fail.get()) {
                    throw new IOException("rebuild unavailable");
                }
            }
        };
        WorldGenGeneratedPersistenceParticipant participant = new WorldGenGeneratedPersistenceParticipant(
            source, new MigrationFence(), Duration.ofSeconds(2), rebuilder);
        try {
            participant.quiesce();
            assertThrows(IOException.class, participant::resume);
            assertEquals(WorldGenGeneratedOutputController.State.DEGRADED, participant.controller().state());
            assertFalse(participant.controller().health().available());

            participant.rebind(replacement);
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
            fail.set(false);
            participant.resume();

            assertEquals(WorldGenGeneratedOutputController.State.OPEN, participant.controller().state());
            assertFalse(Files.exists(participant.root().resolve("rebuilt.txt")));
        } finally {
            participant.close();
        }
    }

    @Test
    void healthReportsAnUnavailableOpenOutputWhenItsRecipeCannotBeVerified() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("health-recipe"));
        WorldGenGeneratedOutputController.Rebuilder rebuilder = new WorldGenGeneratedOutputController.Rebuilder() {
            @Override
            public void rebuild(Path generatedRoot) {
            }

            @Override
            public void healthCheck() throws IOException {
                throw new IOException("WorldGen rebuild recipe is unavailable");
            }
        };
        WorldGenGeneratedPersistenceParticipant participant = new WorldGenGeneratedPersistenceParticipant(
            dataRoot, new MigrationFence(), Duration.ofSeconds(2), rebuilder);

        try {
            assertFalse(participant.controller().health().available());
            assertEquals("WorldGen rebuild recipe is unavailable", participant.controller().health().failureReason());
        } finally {
            participant.close();
        }
    }

    @Test
    void resumeAndRebindAreSerializedByLifecycleStates() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("resume-serialized-source"));
        Path replacement = Files.createDirectory(temporary.resolve("resume-serialized-replacement"));
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorldGenGeneratedOutputController.Rebuilder rebuilder = new WorldGenGeneratedOutputController.Rebuilder() {
            @Override
            public void rebuild(Path generatedRoot) {
            }

            @Override
            public void healthCheck() throws IOException {
                checking.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("health check interrupted", exception);
                }
            }
        };
        WorldGenGeneratedPersistenceParticipant participant = new WorldGenGeneratedPersistenceParticipant(
            source, new MigrationFence(), Duration.ofSeconds(2), rebuilder);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            participant.quiesce();
            var resume = executor.submit(() -> {
                participant.resume();
                return null;
            });
            assertTrue(checking.await(2, TimeUnit.SECONDS));
            assertEquals(WorldGenGeneratedOutputController.State.RESUMING, participant.controller().state());
            assertThrows(IOException.class, () -> participant.rebind(replacement));
            release.countDown();
            resume.get(2, TimeUnit.SECONDS);
            assertEquals(WorldGenGeneratedOutputController.State.OPEN, participant.controller().state());
            assertEquals(source.toAbsolutePath().normalize(), participant.controller().scopeRoot());
        } finally {
            release.countDown();
            executor.shutdownNow();
            participant.close();
        }
    }

    @Test
    void transactionalWriterRollsBackTheExactGeneratedRoot() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("transaction"));
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, ignored -> {
        });
        Path old = participant.root().resolve("old.txt");
        Files.writeString(old, "old");
        try {
            assertThrows(IOException.class, () -> participant.controller().transact("failed-compile", stage -> {
                Files.writeString(stage.resolve("new.txt"), "new");
                throw new IOException("compile failed");
            }));
            assertEquals("old", Files.readString(old));
            assertFalse(Files.exists(participant.root().resolve("new.txt")));
            try (var stream = Files.list(participant.root().getParent())) {
                assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith("generated.stage.")
                    || path.getFileName().toString().startsWith("generated.backup.")
                    || path.getFileName().toString().equals("generated.transaction")));
            }
        } finally {
            participant.close();
        }
    }

    @Test
    void ownershipRetainsStrictGeneratedRootAndRecognizesOnlyCanonicalRecoveryArtifacts() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("ownership"));
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, ignored -> {
        });
        Path generated = participant.root();
        Path parent = generated.getParent();
        String stageName = "generated.stage." + UUID.randomUUID();
        String backupName = "generated.backup." + UUID.randomUUID();
        String handoffName = ".generated.handoff." + UUID.randomUUID();
        Path quarantine = WorldGenGeneratedOutputController.recoveryQuarantine(parent);
        try {
            assertFalse(participant.owns(generated));
            assertTrue(participant.owns(generated.resolve("nested").resolve("output.txt")));
            assertTrue(participant.owns(parent.resolve("generated.transaction")));
            assertTrue(participant.owns(parent.resolve(stageName)));
            assertTrue(participant.owns(parent.resolve(backupName)));
            assertTrue(participant.owns(parent.resolve(handoffName)));
            assertFalse(participant.owns(parent.resolve("generated.stage.invalid")));
            assertFalse(participant.owns(parent.resolve("generated.stage." + UUID.randomUUID() + ".extra")));
            assertFalse(participant.owns(parent.resolve("generated.stage").resolve(UUID.randomUUID().toString())));

            Path quarantined = quarantine.resolve(stageName).resolve("candidate.txt");
            assertTrue(participant.owns(quarantined));
            assertFalse(participant.owns(quarantine));
            assertFalse(participant.owns(quarantine.resolve("unknown-artifact").resolve("value")));
            assertFalse(participant.ownsResolved(generated));
            assertTrue(participant.ownsResolved(generated.resolve("nested").resolve("output.txt")));
            assertTrue(participant.ownsResolved(parent.resolve("generated.transaction")));
            assertTrue(participant.ownsResolved(parent.resolve(stageName)));
            assertTrue(participant.ownsResolved(parent.resolve(backupName)));
            assertTrue(participant.ownsResolved(parent.resolve(handoffName)));
            assertTrue(participant.ownsResolved(quarantined));
            assertFalse(participant.ownsResolved(quarantine));
            assertFalse(participant.ownsResolved(quarantine.resolve("unknown-artifact").resolve("value")));
        } finally {
            participant.close();
        }
    }

    @Test
    void quiesceInterruptsAnAdmittedTransactionalCompilationBeforeDrainCompletes() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("transaction-cancel"));
        WorldGenGeneratedPersistenceParticipant participant = new WorldGenGeneratedPersistenceParticipant(
            dataRoot, new MigrationFence(), Duration.ofSeconds(2), ignored -> {
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            var compilation = executor.submit(() -> {
                try {
                    participant.controller().transact("cancelled-compile", stage -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofSeconds(10));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("compile interrupted", exception);
                        }
                        Files.writeString(stage.resolve("unexpected.txt"), "unexpected");
                        return Boolean.TRUE;
                    });
                } catch (IOException exception) {
                    cancelled.set(true);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            participant.controller().quiesce(Duration.ofSeconds(2));

            compilation.get(2, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
            assertEquals(0, participant.controller().activeOperationCount());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
        } finally {
            executor.shutdownNow();
            participant.close();
        }
    }

    @Test
    void handoffKeepsGeneratedSourceAdmittedUntilTheConsumerReleasesIt() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("handoff"));
        WorldGenGeneratedPersistenceParticipant participant = participant(dataRoot, ignored -> {
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorldGenGeneratedOutputController.Handoff handoff = participant.controller().acquireHandoff("handoff-install");
        try {
            handoff.transact(stage -> {
                Files.writeString(stage.resolve("pack.txt"), "compiled");
                return Boolean.TRUE;
            });
            var quiesce = executor.submit(() -> {
                participant.controller().quiesce(Duration.ofSeconds(5));
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (participant.controller().state() != WorldGenGeneratedOutputController.State.QUIESCING
                && !quiesce.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(quiesce.isDone());
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCING, participant.controller().state());
            handoff.close();
            quiesce.get(2, TimeUnit.SECONDS);
            assertEquals(WorldGenGeneratedOutputController.State.QUIESCED, participant.controller().state());
        } finally {
            handoff.close();
            executor.shutdownNow();
            participant.close();
        }
    }

    private WorldGenGeneratedPersistenceParticipant participant(Path dataRoot,
                                                                WorldGenGeneratedOutputController.Rebuilder rebuilder)
        throws IOException {
        return new WorldGenGeneratedPersistenceParticipant(dataRoot, new MigrationFence(), Duration.ofSeconds(2), rebuilder);
    }
}
