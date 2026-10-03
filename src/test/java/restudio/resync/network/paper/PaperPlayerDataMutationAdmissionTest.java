package restudio.resync.network.paper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.filesystem.windows.WindowsFileIdentity;
import restudio.resync.migration.MigrationFence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPlayerDataMutationAdmissionTest {
    @TempDir
    Path temporary;

    @Test
    void ownsOnlyExactWorldPlayerDataRoots() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        Path playerFile = playerData.resolve(UUID.randomUUID() + ".dat");

        assertEquals(List.of(world.toAbsolutePath().normalize()), admission.worldRoots());
        assertEquals(List.of(playerData.toAbsolutePath().normalize()), admission.playerDataRoots());
        assertTrue(admission.health().available());
        assertTrue(admission.readiness().mutationAvailable());
        assertTrue(admission.owns(playerFile));
        assertFalse(admission.owns(playerData.resolve("..").resolve("outside.dat")));
        assertThrows(IllegalArgumentException.class, () -> admission.acquire("outside", temporary.resolve("outside.dat")));
        assertTrue(admission.owns(playerFile.resolveSibling(playerFile.getFileName() + ".resync.tmp")));
        assertFalse(admission.owns(playerData.resolve(playerFile.getFileName().toString().toUpperCase())));
        assertFalse(admission.owns(playerData.resolve("nested").resolve(playerFile.getFileName())));
    }

    @Test
    void refreshMaterializesMissingPlayerDataRootBeforePublishingProofs() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("refresh-missing-world"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);

        admission.refreshRoots(List.of(world), temporary);

        Path playerData = world.resolve("playerdata");
        assertTrue(Files.isDirectory(playerData, LinkOption.NOFOLLOW_LINKS));
        assertTrue(admission.health().available());
        assertTrue(admission.owns(playerData.resolve(UUID.randomUUID() + ".dat")));
    }

    @Test
    void refreshRejectsSymlinkedOrNonDirectoryPlayerDataRoot() throws Exception {
        Path symlinkWorld = Files.createDirectories(temporary.resolve("refresh-symlink-world"));
        Path external = Files.createDirectories(temporary.resolve("refresh-external-playerdata"));
        try {
            Files.createSymbolicLink(symlinkWorld.resolve("playerdata"), external);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        PaperPlayerDataMutationAdmission symlinkAdmission = new PaperPlayerDataMutationAdmission(List.of(symlinkWorld), temporary);
        symlinkAdmission.refreshRoots(List.of(symlinkWorld), temporary);
        assertFalse(symlinkAdmission.health().available());
        assertFalse(symlinkAdmission.owns(symlinkWorld.resolve("playerdata").resolve(UUID.randomUUID() + ".dat")));

        Path fileWorld = Files.createDirectories(temporary.resolve("refresh-file-world"));
        Files.writeString(fileWorld.resolve("playerdata"), "not a directory");
        PaperPlayerDataMutationAdmission fileAdmission = new PaperPlayerDataMutationAdmission(List.of(fileWorld), temporary);
        fileAdmission.refreshRoots(List.of(fileWorld), temporary);
        assertFalse(fileAdmission.health().available());
        assertFalse(fileAdmission.owns(fileWorld.resolve("playerdata").resolve(UUID.randomUUID() + ".dat")));
    }

    @Test
    void invalidReplacementClearsPreviousPlayerDataAdmissionProofs() throws Exception {
        Path oldWorld = Files.createDirectories(temporary.resolve("refresh-old-world").resolve("playerdata")).getParent();
        Path oldPlayerData = oldWorld.resolve("playerdata");
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(oldWorld), temporary);
        Path oldTarget = oldPlayerData.resolve(UUID.randomUUID() + ".dat");
        assertTrue(admission.owns(oldTarget));

        Path replacementWorld = Files.createDirectories(temporary.resolve("refresh-replacement-world"));
        Files.writeString(replacementWorld.resolve("playerdata"), "invalid replacement");
        admission.refreshRoots(List.of(replacementWorld), temporary);

        assertEquals(List.of(replacementWorld.toAbsolutePath().normalize()), admission.worldRoots());
        assertFalse(admission.owns(oldTarget));
        assertFalse(admission.health().available());
    }

    @Test
    void rejectsSymlinkedRootsAndPlayerDataTargets() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        Path external = Files.writeString(temporary.resolve("external.dat"), "external");
        Path linkedRoot = temporary.resolve("linked-playerdata");
        try {
            Files.createSymbolicLink(linkedRoot, playerData);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        UUID playerId = UUID.randomUUID();
        assertFalse(admission.owns(linkedRoot.resolve(playerId + ".dat")));

        Path target = playerData.resolve(playerId + ".dat");
        Files.createSymbolicLink(target, external);
        assertFalse(admission.owns(target));

        Path temporaryTarget = playerData.resolve(playerId + ".dat.resync.tmp");
        Files.createSymbolicLink(temporaryTarget, external);
        assertFalse(admission.owns(temporaryTarget));
    }

    @Test
    void unavailableRootsFailClosedAndInstallationRejectsDoubleInstallAndAba() throws Exception {
        PaperPlayerDataMutationAdmission unavailable = new PaperPlayerDataMutationAdmission(List.of(), temporary);
        assertFalse(unavailable.health().available());
        assertThrows(IllegalStateException.class, () -> unavailable.acquire("missing-roots"));

        Path firstWorld = Files.createDirectories(temporary.resolve("first").resolve("playerdata")).getParent();
        Path secondWorld = Files.createDirectories(temporary.resolve("second").resolve("playerdata")).getParent();
        PaperPlayerDataMutationAdmission first = new PaperPlayerDataMutationAdmission(List.of(firstWorld), temporary);
        PaperPlayerDataMutationAdmission second = new PaperPlayerDataMutationAdmission(List.of(secondWorld), temporary);
        clearSharedInstallationIfPresent();
        PaperPlayerDataMutationAdmission.Installation firstInstallation = PaperPlayerDataMutationAdmission.installShared(first);
        assertSame(firstInstallation, PaperPlayerDataMutationAdmission.installShared(first));
        assertThrows(IllegalStateException.class, () -> PaperPlayerDataMutationAdmission.installShared(second));
        PaperPlayerDataMutationAdmission.Installation stale = new PaperPlayerDataMutationAdmission.Installation(first, firstInstallation.generation());
        assertFalse(PaperPlayerDataMutationAdmission.clearSharedInstallation(stale));
        assertTrue(PaperPlayerDataMutationAdmission.clearSharedInstallation(firstInstallation));
        PaperPlayerDataMutationAdmission.Installation reinstalledFirst = PaperPlayerDataMutationAdmission.installShared(first);
        assertNotSame(firstInstallation, reinstalledFirst);
        assertFalse(PaperPlayerDataMutationAdmission.clearSharedInstallation(firstInstallation));
        assertSame(first, PaperPlayerDataMutationAdmission.shared());
        assertTrue(PaperPlayerDataMutationAdmission.clearSharedInstallation(reinstalledFirst));
        PaperPlayerDataMutationAdmission.Installation secondInstallation = PaperPlayerDataMutationAdmission.installShared(second);
        assertSame(second, PaperPlayerDataMutationAdmission.shared());
        assertTrue(PaperPlayerDataMutationAdmission.clearSharedInstallation(secondInstallation));
    }

    @Test
    void quiesceClosesAdmissionAndDrainsActiveWork() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission.Lease blocker = admission.acquire("blocker");
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                admission.quiesce(Duration.ofSeconds(2));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admission.state() != PaperPlayerDataMutationAdmission.State.QUIESCING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCING, admission.state());
        assertThrows(IllegalStateException.class, () -> admission.acquire("late"));
        blocker.close();
        quiesce.get(2, TimeUnit.SECONDS);

        assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCED, admission.state());
        assertEquals(0, admission.activeWorkCount());
        admission.resume();
        assertEquals(PaperPlayerDataMutationAdmission.State.OPEN, admission.state());
    }

    @Test
    void anIdleQuiesceRequestCanResumeWithoutWaitingForAnotherDrain() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("idle-world").resolve("playerdata")).getParent();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission.ReadinessObservation previous = admission.readinessObservation();

        admission.requestQuiesce();

        assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCED, admission.state());
        assertFalse(admission.isCurrent(previous));
        assertThrows(IllegalStateException.class, () -> admission.acquire("late"));
        admission.resume();
        try (PaperPlayerDataMutationAdmission.Lease lease = admission.acquire("resumed")) {
            assertEquals(PaperPlayerDataMutationAdmission.State.OPEN, admission.state());
        }
    }

    @Test
    void anActiveQuiesceRequestStillRequiresTheAdmittedWorkToDrain() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("active-world").resolve("playerdata")).getParent();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        try (PaperPlayerDataMutationAdmission.Lease lease = admission.acquire("active")) {
            admission.requestQuiesce();
            assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCING, admission.state());
            assertThrows(IOException.class, admission::resume);
            assertThrows(IllegalStateException.class, () -> admission.acquire("late"));
        }
        admission.quiesce(Duration.ofSeconds(1));
        admission.resume();
        assertEquals(PaperPlayerDataMutationAdmission.State.OPEN, admission.state());
    }

    @Test
    void drainTimeoutFailsClosedAndReadinessDoesNotClaimSnapshotRestore() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        ExecutorService blockerExecutor = Executors.newSingleThreadExecutor();
        PaperPlayerDataMutationAdmission.Lease blocker = CompletableFuture.supplyAsync(
            () -> admission.acquire("blocker"), blockerExecutor).get(1, TimeUnit.SECONDS);
        try {
            assertThrows(IOException.class, () -> admission.quiesce(Duration.ofMillis(10)));
            assertEquals(PaperPlayerDataMutationAdmission.State.FAILED, admission.state());
            assertFalse(admission.readiness().snapshotSupported());
            assertFalse(admission.readiness().restoreSupported());
            assertTrue(admission.readiness().reason().contains("atomic snapshot"));
            assertTrue(admission.unavailableReadiness().reason().contains("snapshot"));
            blocker.close();
            assertThrows(IOException.class, admission::resume);
        } finally {
            blocker.close();
            blockerExecutor.shutdownNow();
        }
    }

    @Test
    void readinessObservationsFenceOwnerActivityLifecycleAndFailure() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("observation-world").resolve("playerdata")).getParent();
        Path otherWorld = Files.createDirectories(temporary.resolve("other-observation-world").resolve("playerdata"))
            .getParent();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission other = new PaperPlayerDataMutationAdmission(List.of(otherWorld), temporary);
        PaperPlayerDataMutationAdmission.ReadinessObservation baseline = admission.readinessObservation();
        assertSame(admission, baseline.owner());
        assertEquals(admission.generation(), baseline.generation());
        assertTrue(baseline.readiness().mutationAvailable());
        assertTrue(admission.isCurrent(baseline));
        assertFalse(other.isCurrent(baseline));

        ExecutorService blockerExecutor = Executors.newSingleThreadExecutor();
        PaperPlayerDataMutationAdmission.Lease blocker = CompletableFuture.supplyAsync(
            () -> admission.acquire("observation-blocker"), blockerExecutor).get(1L, TimeUnit.SECONDS);
        try {
            assertFalse(admission.isCurrent(baseline));
            PaperPlayerDataMutationAdmission.ReadinessObservation active = admission.readinessObservation();
            assertEquals(1, active.readiness().activeWork());
            assertTrue(admission.isCurrent(active));

            assertThrows(IOException.class, () -> admission.quiesce(Duration.ofMillis(10L)));
            assertFalse(admission.isCurrent(active));
            PaperPlayerDataMutationAdmission.ReadinessObservation failed = admission.readinessObservation();
            assertEquals(PaperPlayerDataMutationAdmission.State.FAILED, failed.readiness().state());
            assertFalse(failed.readiness().mutationAvailable());
            assertTrue(admission.isCurrent(failed));

            blocker.close();
            assertFalse(admission.isCurrent(failed));
        } finally {
            blocker.close();
            blockerExecutor.shutdownNow();
        }
    }

    @Test
    void closeFencesAndDrainsActiveWorkBeforeClosed() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("close-world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission.Lease blocker = admission.acquire("blocker");
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            try {
                admission.close(Duration.ofSeconds(2));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });

        waitForState(admission, PaperPlayerDataMutationAdmission.State.QUIESCING);
        assertThrows(IllegalStateException.class, () -> admission.acquire("late"));
        blocker.close();
        close.get(2, TimeUnit.SECONDS);
        assertEquals(PaperPlayerDataMutationAdmission.State.CLOSED, admission.state());
        assertEquals(0, admission.activeWorkCount());
    }

    @Test
    void closeTimeoutLeavesHonestFailureAndRetainsSharedInstallation() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("close-timeout-world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        clearSharedInstallationIfPresent();
        PaperPlayerDataMutationAdmission.Installation installation = PaperPlayerDataMutationAdmission.installShared(admission);
        PaperPlayerDataMutationAdmission.Lease blocker = admission.acquire("blocker");

        assertThrows(IOException.class, () -> admission.close(Duration.ofMillis(10)));
        assertEquals(PaperPlayerDataMutationAdmission.State.FAILED, admission.state());
        assertEquals(1, admission.activeWorkCount());
        assertSame(admission, PaperPlayerDataMutationAdmission.shared());
        blocker.close();
        assertFalse(PaperPlayerDataMutationAdmission.clearSharedInstallation(new PaperPlayerDataMutationAdmission.Installation(
            admission, installation.generation())));
        assertTrue(PaperPlayerDataMutationAdmission.clearSharedInstallation(installation));
    }

    @Test
    void pathSafetyProbeRejectsUnsupportedReparseProofForRootsAndTargets() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("unsafe-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        UUID playerId = UUID.randomUUID();
        PaperPlayerDataMutationAdmission.PathSafetyProbe unsupported = path -> {
            throw new IOException("Reparse-Point Safety Proof Is Unsupported: " + path);
        };
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary, unsupported);

        assertFalse(admission.health().available());
        assertFalse(admission.owns(playerData.resolve(playerId + ".dat")));
        assertThrows(IllegalArgumentException.class, () -> admission.acquire("unsafe", playerData.resolve(playerId + ".dat")));
    }

    @Test
    void pathSafetyProbeRejectsReparseTraversalForTarget() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("seamed-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        UUID playerId = UUID.randomUUID();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> {
                if (path.equals(playerData)) {
                    throw new IOException("Windows Reparse-Point Traversal Is Not Allowed: " + path);
                }
            });

        assertFalse(admission.owns(playerData.resolve(playerId + ".dat")));
    }

    @Test
    void nullFileKeyProofFailsClosedBeforeSafetyProbe() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("null-file-key-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        AtomicInteger safetyCalls = new AtomicInteger();
        PaperPlayerDataMutationAdmission.PathObservationProbe nullFileKey = path -> {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            return new PaperPlayerDataMutationAdmission.PathObservation(null, attributes.isSymbolicLink(),
                attributes.isDirectory(), attributes.isRegularFile(), attributes.isOther(), false);
        };
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> safetyCalls.incrementAndGet(), nullFileKey);

        assertFalse(admission.health().available());
        assertEquals(0, safetyCalls.get());
        assertFalse(admission.owns(playerData.resolve(UUID.randomUUID() + ".dat")));
        assertThrows(IllegalStateException.class, () -> admission.acquire("null-file-key"));
    }

    @Test
    void repeatedAcquireDoesNotRepeatConfiguredPathProof() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("cached-world"));
        Files.createDirectories(world.resolve("playerdata"));
        AtomicInteger proofCalls = new AtomicInteger();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> proofCalls.incrementAndGet());
        int configuredProofCalls = proofCalls.get();
        long started = System.nanoTime();

        for (int i = 0; i < 20; i++) {
            try (PaperPlayerDataMutationAdmission.Lease ignored = admission.acquire("cached-" + i)) {
            }
        }
        long elapsed = System.nanoTime() - started;

        assertTrue(configuredProofCalls > 0);
        assertEquals(configuredProofCalls, proofCalls.get());
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Cached acquisition path took too long: " + elapsed);
    }

    @Test
    void playerDataFileCreationDoesNotInvalidatePinnedAncestorProof() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("mutable-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        Path target = playerData.resolve(UUID.randomUUID() + ".dat");
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> {
            });

        for (int i = 0; i < 4; i++) {
            Files.writeString(target, Integer.toString(i));
            try (PaperPlayerDataMutationAdmission.Lease ignored = admission.acquire("mutable-" + i, target)) {
            }
            Files.delete(target);
        }

        assertTrue(admission.health().available());
    }

    @Test
    void ancestorIdentityChangeInvalidatesPinnedProof() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("identity-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> {
            });
        UUID playerId = UUID.randomUUID();
        assertTrue(admission.owns(playerData.resolve(playerId + ".dat")));

        Path moved = temporary.resolve("identity-world-moved");
        Files.move(world, moved);
        Files.createDirectories(world.resolve("playerdata"));

        assertFalse(admission.owns(world.resolve("playerdata").resolve(playerId + ".dat")));
        assertFalse(admission.health().available());
    }

    @Test
    void ancestorReparseChangeInvalidatesPinnedProof() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("reparse-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        AtomicBoolean reparse = new AtomicBoolean();
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
            path -> {
            }, path -> {
                Path normalized = path.toAbsolutePath().normalize();
                boolean directory = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
                boolean regular = Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
                boolean isReparse = reparse.get() && normalized.equals(world.toAbsolutePath().normalize());
                return new PaperPlayerDataMutationAdmission.PathObservation(
                    new WindowsFileIdentity.FileIdInfo(20L, windowsFileId(20), false), false, directory, regular,
                    !directory && !regular, isReparse, normalized, isReparse ? 0xA0000003 : 0);
            });
        UUID playerId = UUID.randomUUID();
        assertTrue(admission.owns(playerData.resolve(playerId + ".dat")));

        reparse.set(true);

        assertFalse(admission.owns(playerData.resolve(playerId + ".dat")));
        assertFalse(admission.health().available());
    }

    @Test
    void windowsObservationUsesHandleIdentityAndRejectsFinalPathMismatch() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("windows-world"));
        Files.createDirectories(world.resolve("playerdata"));
        runAsWindows(() -> {
            PaperPlayerDataMutationAdmission.PathObservationProbe observation = path -> {
                Path normalized = path.toAbsolutePath().normalize();
                boolean directory = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
                boolean regular = Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
                return new PaperPlayerDataMutationAdmission.PathObservation(
                    new WindowsFileIdentity.FileIdInfo(17L, windowsFileId(17), false), false, directory, regular,
                    !directory && !regular, false, normalized, 0);
            };
            PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
                ignored -> {
                }, observation);

            assertTrue(admission.health().available());
            assertTrue(admission.owns(world.resolve("playerdata").resolve(UUID.randomUUID() + ".dat")));

            Path mismatchedWorld = temporary.resolve("mismatched-windows-world");
            Files.createDirectories(mismatchedWorld.resolve("playerdata"));
            PaperPlayerDataMutationAdmission mismatched = new PaperPlayerDataMutationAdmission(List.of(mismatchedWorld),
                temporary, ignored -> {
                }, path -> {
                    Path normalized = path.toAbsolutePath().normalize();
                    boolean directory = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
                    boolean regular = Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
                    Path finalPath = normalized.equals(mismatchedWorld.toAbsolutePath().normalize())
                        ? normalized.resolveSibling("outside").normalize() : normalized;
                    return new PaperPlayerDataMutationAdmission.PathObservation(
                        new WindowsFileIdentity.FileIdInfo(18L, windowsFileId(18), false), false, directory, regular,
                        !directory && !regular, false, finalPath, 0);
                });

            assertFalse(mismatched.health().available());
            assertFalse(mismatched.owns(mismatchedWorld.resolve("playerdata").resolve(UUID.randomUUID() + ".dat")));
        });
    }

    @Test
    void windowsObservationFailureAndReparseStateFailClosedAndInvalidateProofs() throws Exception {
        Path world = Files.createDirectories(temporary.resolve("windows-reparse-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        AtomicBoolean reparse = new AtomicBoolean();
        runAsWindows(() -> {
            PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
                ignored -> {
                }, path -> {
                    Path normalized = path.toAbsolutePath().normalize();
                    boolean directory = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
                    boolean regular = Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
                    boolean isReparse = reparse.get() && normalized.equals(world.toAbsolutePath().normalize());
                    return new PaperPlayerDataMutationAdmission.PathObservation(
                        new WindowsFileIdentity.FileIdInfo(19L, windowsFileId(19), false), false, directory, regular,
                        !directory && !regular, isReparse, normalized, isReparse ? 0xA0000003 : 0);
                });
            Path target = playerData.resolve(UUID.randomUUID() + ".dat");
            assertTrue(admission.owns(target));

            reparse.set(true);

            assertFalse(admission.owns(target));
            assertFalse(admission.health().available());

            PaperPlayerDataMutationAdmission unavailable = new PaperPlayerDataMutationAdmission(List.of(world), temporary,
                ignored -> {
                }, ignored -> {
                    throw new IOException("Windows kernel32 is unavailable");
                });
            assertFalse(unavailable.health().available());
            assertFalse(unavailable.owns(target));
        });
    }

    @Test
    void realWindowsAdmissionAcquisitionAndReplacementAreBounded() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"),
            "Windows native identity is unavailable on this operating system");
        Path world = Files.createDirectories(temporary.resolve("real-windows-world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        Path target = playerData.resolve(UUID.randomUUID() + ".dat");
        long started = System.nanoTime();
        for (int index = 0; index < 20; index++) {
            try (PaperPlayerDataMutationAdmission.Lease ignored = admission.acquire("real-windows-" + index, target)) {
            }
        }
        long elapsed = System.nanoTime() - started;
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "Windows acquisition path took too long: " + elapsed);

        Path moved = temporary.resolve("real-windows-world-moved");
        Files.move(world, moved);
        Files.createDirectories(world.resolve("playerdata"));
        assertFalse(admission.owns(world.resolve("playerdata").resolve(target.getFileName())));
        assertFalse(admission.health().available());
    }

    @Test
    void networkAgentStartupShutdownReleasesItsInstallationForReload() throws Exception {
        clearSharedInstallationIfPresent();
        Path networkRoot = Files.createDirectories(temporary.resolve("agent-network"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "node", "source", "Test", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), networkRoot.resolve("node.credential"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config, new MigrationFence());
        PaperPlayerDataMutationAdmission installed = agent.playerDataAdmission();

        assertSame(installed, PaperPlayerDataMutationAdmission.shared());
        assertThrows(IllegalStateException.class, () -> PaperPlayerDataMutationAdmission.installShared(
            new PaperPlayerDataMutationAdmission(List.of(), temporary)));

        assertTrue(agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS).completed());
        assertEquals(PaperPlayerDataMutationAdmission.State.CLOSED, installed.state());

        Path world = Files.createDirectories(temporary.resolve("reloaded-world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission reloaded = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission.Installation installation = PaperPlayerDataMutationAdmission.installShared(reloaded);
        assertSame(reloaded, PaperPlayerDataMutationAdmission.shared());
        assertTrue(PaperPlayerDataMutationAdmission.clearSharedInstallation(installation));
    }

    private void clearSharedInstallationIfPresent() {
        PaperPlayerDataMutationAdmission.clearSharedInstallation(
            currentInstallation().orElse(null));
    }

    private static Optional<PaperPlayerDataMutationAdmission.Installation> currentInstallation() {
        return Optional.ofNullable(PaperPlayerDataMutationAdmission.sharedInstallation());
    }

    private static void waitForState(PaperPlayerDataMutationAdmission admission,
                                     PaperPlayerDataMutationAdmission.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admission.state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, admission.state());
    }

    private static byte[] windowsFileId(int value) {
        byte[] identifier = new byte[16];
        identifier[15] = (byte) value;
        return identifier;
    }

    private static void runAsWindows(ThrowingAction action) throws Exception {
        synchronized (PaperPlayerDataMutationAdmissionTest.class) {
            String original = System.getProperty("os.name");
            System.setProperty("os.name", "Windows 11");
            try {
                action.run();
            } finally {
                if (original == null) {
                    System.clearProperty("os.name");
                } else {
                    System.setProperty("os.name", original);
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
