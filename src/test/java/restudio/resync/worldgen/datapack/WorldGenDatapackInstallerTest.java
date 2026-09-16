package restudio.resync.worldgen.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGenDatapackInstallerTest {
    @TempDir
    Path temporary;

    @Test
    void requiresExplicitWorldAndNeverFallsBackToMainWorld() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path main = Files.createDirectories(worlds.resolve("world").resolve("datapacks"));
        Files.writeString(main.resolve("sentinel.txt"), "main");
        Path source = source("pack-a", "new");
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> true);

        WorldGenDatapackInstaller.InstallResult missing = installer.install(build(source, "resync_worldgen_pack", "project"), " ");

        assertFalse(missing.installed());
        assertEquals("main", Files.readString(main.resolve("sentinel.txt")));
        assertFalse(Files.exists(main.resolve("resync_worldgen_pack")));

        WorldGenDatapackInstaller.InstallResult preview = installer.installPreview(build(source, "resync_worldgen_pack", "project"), "resync_preview_1");

        assertTrue(preview.installed(), preview.message());
        assertEquals("new", Files.readString(worlds.resolve("resync_preview_1").resolve("datapacks").resolve("resync_worldgen_pack").resolve("pack-a")));
        assertEquals("main", Files.readString(main.resolve("sentinel.txt")));
    }

    @Test
    void replacesOnlyOwnedPackAndPreservesUnrelatedDatapacksAndWorldFiles() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path world = Files.createDirectories(worlds.resolve("survival"));
        Path datapacks = Files.createDirectories(world.resolve("datapacks"));
        Files.writeString(world.resolve("level.dat"), "world");
        Path unrelated = Files.createDirectories(datapacks.resolve("external-pack"));
        Files.writeString(unrelated.resolve("external.txt"), "external");
        Path target = Files.createDirectories(datapacks.resolve("resync_worldgen_pack"));
        Files.writeString(target.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Files.writeString(target.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(target.resolve("old.txt"), "old");
        Path source = source("new.txt", "new");
        WorldGenDatapackInstaller installer = installer(worlds, new WorldGenDatapackInstaller.PackActivator() {
            @Override
            public boolean enable(String packName) {
                return true;
            }

            @Override
            public WorldGenDatapackInstaller.ActivationState capture(String packName, String worldName) {
                return WorldGenDatapackInstaller.ActivationState.known(false);
            }
        });

        WorldGenDatapackInstaller.InstallResult result = installer.install(build(source, "resync_worldgen_pack", "project"), "survival");

        assertTrue(result.installed(), result.message());
        assertEquals("external", Files.readString(unrelated.resolve("external.txt")));
        assertEquals("world", Files.readString(world.resolve("level.dat")));
        assertFalse(Files.exists(target.resolve("old.txt")));
        assertEquals("new", Files.readString(target.resolve("new.txt")));
        try (var stream = Files.list(datapacks)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".resync-")));
        }
    }

    @Test
    void rejectsAnExistingPackWithoutAReSyncManifest() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path target = Files.createDirectories(worlds.resolve("survival").resolve("datapacks").resolve("resync_worldgen_pack"));
        Files.writeString(target.resolve("pack.mcmeta"), "old");
        Path source = source("new.txt", "new");
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> true);

        WorldGenDatapackInstaller.InstallResult result = installer.install(build(source, "resync_worldgen_pack", "project"), "survival");

        assertFalse(result.installed());
        assertEquals("old", Files.readString(target.resolve("pack.mcmeta")));
        assertFalse(Files.exists(target.resolve("new.txt")));
    }

    @Test
    void rollsBackAFirstInstallWhenVanillaActivationFails() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path source = source("new.txt", "new");
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> false);
        WorldGenDatapackBuild build = build(source, "resync_worldgen_pack", "project");
        build.setGenerationMode("vanilla");

        WorldGenDatapackInstaller.InstallResult result = installer.install(build, "survival");

        assertFalse(result.installed());
        Path datapacks = worlds.resolve("survival").resolve("datapacks");
        assertFalse(Files.exists(datapacks.resolve("resync_worldgen_pack")));
        try (var stream = Files.list(datapacks)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".resync-")));
        }
    }

    @Test
    void rejectsTraversalAndSymlinkPaths() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path source = source("new.txt", "new");
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> true);
        WorldGenDatapackBuild build = build(source, "resync_worldgen_pack", "project");

        assertFalse(installer.install(build, "../outside").installed());
        build.setPackName("../outside");
        assertFalse(installer.install(build, "survival").installed());

        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        Path link = datapacks.resolve("resync_worldgen_pack");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable");
        }
        build.setPackName("resync_worldgen_pack");

        assertFalse(installer.install(build, "survival").installed());
        assertTrue(Files.isDirectory(outside));
        try (var stream = Files.list(datapacks)) {
            assertTrue(stream.anyMatch(link::equals));
        }
    }

    @Test
    void failsClosedOnAWorldContainerSymlink() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path outside = Files.createDirectories(temporary.resolve("outside-world"));
        try {
            Files.createSymbolicLink(worlds.resolve("linked-world"), outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable");
        }

        WorldGenDatapackInstaller installer = installer(worlds, ignored -> true);

        assertThrows(IOException.class, installer::healthCheck);
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void failsClosedOnAWorldContainerCaseAlias() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path worlds = Files.createDirectory(temporary.resolve("case-worlds"));
        Path alias = Path.of(worlds.toString().toUpperCase(Locale.ROOT));
        assumeTrue(Files.isDirectory(alias), "Case aliases are unavailable");

        WorldGenDatapackInstaller installer = installer(alias, ignored -> true);

        assertThrows(IOException.class, installer::healthCheck);
    }

    @Test
    void recoversACommittedDirectorySwapAfterAProcessCrash() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        String token = "a".repeat(32);
        Path stage = Files.createDirectories(datapacks.resolve(".resync-stage-" + token + "-resync_worldgen_pack"));
        Files.writeString(stage.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(stage.resolve("new.txt"), "new");
        Files.writeString(stage.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Path target = Files.createDirectories(datapacks.resolve("resync_worldgen_pack"));
        Files.writeString(target.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(target.resolve("new.txt"), "new");
        Files.writeString(target.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Path backup = Files.createDirectories(datapacks.resolve(".resync-backup-" + token + "-resync_worldgen_pack"));
        Files.writeString(backup.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(backup.resolve("old.txt"), "old");
        Files.writeString(backup.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Path journal = datapacks.resolve(".resync-install-" + token + ".txn");
        Path source = source("new.txt", "new");
        Files.writeString(source.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        String hash = treeHash(stage);
        String previousHash = treeHash(backup);
        Files.writeString(journal, "version=2\nowner=" + WorldGenInstalledDatapackCapability.OWNER + "\nworld=survival\n"
            + "pack=resync_worldgen_pack\nprojectId=project\nbuildRevision=1\nsource=" + source.toAbsolutePath() + "\n"
            + "sourceHash=" + hash + "\ntargetHash=" + hash + "\npreviousTargetExists=true\npreviousTargetHash=" + previousHash
            + "\npreviousEnabledKnown=false\npreviousEnabled=false\ntarget=" + target.toAbsolutePath() + "\n"
            + "stage=.resync-stage-" + token + "-resync_worldgen_pack\n"
            + "backup=.resync-backup-" + token + "-resync_worldgen_pack\nphase=ACTIVATED\n");
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> true);

        installer.healthCheck();

        assertEquals("new", Files.readString(datapacks.resolve("resync_worldgen_pack").resolve("new.txt")));
        assertFalse(Files.exists(stage));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(journal));
    }

    @Test
    void closesAdmissionDrainsInstallAndResumes() throws Exception {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path source = source("new.txt", "new");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return true;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorldGenDatapackInstaller.InstallResult> future = executor.submit(
                () -> installer.install(build(source, "resync_worldgen_pack", "project"), "survival"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            Thread quiesce = new Thread(() -> {
                try {
                    installer.quiesce(Duration.ofSeconds(2));
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            quiesce.start();
            Thread.sleep(50L);
            assertFalse(installer.admissionOpen());
            release.countDown();
            quiesce.join(2000L);

            assertTrue(future.get(2, TimeUnit.SECONDS).installed());
            assertEquals(WorldGenInstalledDatapackCapability.State.QUIESCED, installer.state());
            installer.resume();
            assertEquals(WorldGenInstalledDatapackCapability.State.OPEN, installer.state());
            installer.healthCheck();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void keepsCompileInstallMutationLeaseOpenUntilHandoffOwnerClosesIt() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path source = source("handoff.txt", "handoff");
        MigrationFence fence = new MigrationFence();
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds,
            "26.2",
            ignored -> true,
            WorldGenDatapackInstaller.DirectoryDurability.noop(),
            fence,
            Duration.ofSeconds(2));
        int before = fence.activeMutationCount();
        MigrationFence.MutationLease lease = fence.beginMutation();
        try {
            WorldGenDatapackInstaller.InstallResult result = installer.installWithHandoff(
                build(source, "resync_worldgen_pack", "project"), "survival", lease);

            assertTrue(result.installed(), result.message());
            assertEquals(before + 1, fence.activeMutationCount());
        } finally {
            lease.close();
        }
        assertEquals(before, fence.activeMutationCount());
    }

    @Test
    void failsClosedWhenDirectoryDurabilityIsUnavailable() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path source = source("new.txt", "new");
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds,
            "26.2",
            ignored -> false,
            WorldGenDatapackInstaller.DirectoryDurability.unavailable("test durability unavailable")
        );

        WorldGenDatapackInstaller.InstallResult result = installer.install(build(source, "resync_worldgen_pack", "project"), "survival");

        assertFalse(result.installed());
        assertFalse(result.available());
        assertFalse(installer.available());
        assertTrue(installer.failureReason().contains("test durability unavailable"));
    }

    @Test
    void defersRecoverablePreActivationDurabilityProbeUntilResume() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("preactivation-recoverable-worlds"));
        AtomicBoolean durable = new AtomicBoolean();
        WorldGenDatapackInstaller.DirectoryDurability durability = new WorldGenDatapackInstaller.DirectoryDurability() {
            @Override
            public boolean available(Path root) {
                return durable.get();
            }

            @Override
            public String unavailableReason(Path root) {
                return "temporary durability probe failure";
            }

            @Override
            public void force(Path path) {
            }
        };
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds, "26.2", ignored -> true, durability);

        assertThrows(IOException.class, installer::healthCheck);
        assertEquals(WorldGenInstalledDatapackCapability.State.OPEN, installer.state());

        durable.set(true);
        installer.resume();

        assertEquals(WorldGenInstalledDatapackCapability.State.OPEN, installer.state());
        assertTrue(installer.available());
    }

    @Test
    void failsClosedWhenDurabilityRemainsUnavailableAtActivation() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("preactivation-failure-worlds"));
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds, "26.2", ignored -> true,
            WorldGenDatapackInstaller.DirectoryDurability.unavailable("activation durability unavailable"));

        assertThrows(IOException.class, installer::healthCheck);
        assertEquals(WorldGenInstalledDatapackCapability.State.OPEN, installer.state());
        assertThrows(IOException.class, installer::resume);
        assertEquals(WorldGenInstalledDatapackCapability.State.FAILED, installer.state());
    }

    @Test
    void removesOrphanStagesButPreservesExternalDatapacks() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        Path orphan = Files.createDirectories(datapacks.resolve(".resync-stage-" + "b".repeat(32) + "-resync_worldgen_pack"));
        Files.writeString(orphan.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(orphan.resolve("partial.txt"), "partial");
        Files.writeString(orphan.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Path external = Files.createDirectories(datapacks.resolve("external-pack"));
        Files.writeString(external.resolve("external.txt"), "external");

        installer(worlds, ignored -> false).healthCheck();

        assertFalse(Files.exists(orphan));
        assertEquals("external", Files.readString(external.resolve("external.txt")));
    }

    @Test
    void recoversWorldsOncePerLifecycleGenerationAndInvalidatesAfterMutation() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("generation-worlds"));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        WorldGenDatapackInstaller installer = installer(worlds, ignored -> false);

        installer.healthCheck();
        Path resumeOrphan = orphanStage(datapacks, "6".repeat(32));
        installer.healthCheck();
        assertTrue(Files.isDirectory(resumeOrphan));

        installer.quiesce();
        installer.resume();
        assertFalse(Files.exists(resumeOrphan));

        Path mutationOrphan = orphanStage(datapacks, "7".repeat(32));
        Path source = source("generation.txt", "generation");
        assertTrue(installer.installPreview(build(source, "resync_worldgen_pack", "project"), "preview").installed());
        assertTrue(Files.isDirectory(mutationOrphan));

        installer.healthCheck();
        assertFalse(Files.exists(mutationOrphan));
        Path cachedOrphan = orphanStage(datapacks, "8".repeat(32));
        installer.healthCheck();
        assertTrue(Files.isDirectory(cachedOrphan));
    }

    @Test
    void rejectsHashSubstitutionDuringCommittedRecovery() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        TransactionFixture fixture = transactionFixture(worlds, "ACTIVATED");
        Files.writeString(fixture.target().resolve("new.txt"), "substituted");

        WorldGenDatapackInstaller installer = installer(worlds, ignored -> false);

        assertThrows(IOException.class, installer::healthCheck);
        assertTrue(Files.exists(fixture.journal()));
        assertEquals("substituted", Files.readString(fixture.target().resolve("new.txt")));
    }

    @Test
    void restoresPreviousEnabledStateWhenActivationFails() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path target = ownedTarget(worlds, "survival", "old.txt", "old");
        Path source = source("activation-new.txt", "new");
        AtomicBoolean restored = new AtomicBoolean();
        WorldGenDatapackInstaller.PackActivator activator = new WorldGenDatapackInstaller.PackActivator() {
            @Override
            public boolean enable(String packName) {
                return false;
            }

            @Override
            public WorldGenDatapackInstaller.ActivationState capture(String packName, String worldName) {
                return WorldGenDatapackInstaller.ActivationState.known(true);
            }

            @Override
            public void restore(String packName, String worldName, WorldGenDatapackInstaller.ActivationState state) {
                restored.set(state.known() && state.enabled());
            }
        };
        WorldGenDatapackInstaller installer = installer(worlds, activator);
        WorldGenDatapackBuild build = build(source, "resync_worldgen_pack", "project");
        build.setGenerationMode("vanilla");

        WorldGenDatapackInstaller.InstallResult result = installer.install(build, "survival");

        assertFalse(result.installed());
        assertTrue(restored.get());
        assertEquals("old", Files.readString(target.resolve("old.txt")));
        assertFalse(Files.exists(target.resolve("activation-new.txt")));
    }

    @Test
    void rollsBackWhenDatapackRefreshOrEnableThrows() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path target = ownedTarget(worlds, "survival", "old.txt", "old");
        Path source = source("refresh-new.txt", "new");
        WorldGenDatapackInstaller.PackActivator activator = new WorldGenDatapackInstaller.PackActivator() {
            @Override
            public boolean enable(String packName) {
                throw new IllegalStateException("refresh failed");
            }

            @Override
            public WorldGenDatapackInstaller.ActivationState capture(String packName, String worldName) {
                return WorldGenDatapackInstaller.ActivationState.known(false);
            }
        };
        WorldGenDatapackInstaller installer = installer(worlds, activator);
        WorldGenDatapackBuild build = build(source, "resync_worldgen_pack", "project");
        build.setGenerationMode("vanilla");

        WorldGenDatapackInstaller.InstallResult result = installer.install(build, "survival");

        assertFalse(result.installed());
        assertEquals("old", Files.readString(target.resolve("old.txt")));
        assertFalse(Files.exists(target.resolve("refresh-new.txt")));
    }

    @Test
    void refusesReplacementWhenPriorEnabledStateCannotBeCaptured() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        Path target = ownedTarget(worlds, "survival", "old.txt", "old");
        Path source = source("unknown-state-new.txt", "new");
        WorldGenDatapackInstaller installer = installer(worlds, new WorldGenDatapackInstaller.PackActivator() {
            @Override
            public boolean enable(String packName) {
                return true;
            }

            @Override
            public WorldGenDatapackInstaller.ActivationState capture(String packName, String worldName) {
                return WorldGenDatapackInstaller.ActivationState.unknown();
            }
        });

        WorldGenDatapackInstaller.InstallResult result = installer.install(
            build(source, "resync_worldgen_pack", "project"), "survival");

        assertFalse(result.installed());
        assertEquals("old", Files.readString(target.resolve("old.txt")));
        assertFalse(Files.exists(target.resolve("unknown-state-new.txt")));
        try (var stream = Files.list(target.getParent())) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".resync-")));
        }
    }

    @Test
    void recoversEveryPersistedPreCommitCutDeterministically() throws IOException {
        for (String phase : new String[] {"PREPARED", "BACKUP_MOVED", "TARGET_MOVED", "ACTIVATING", "ACTIVATED"}) {
            Path worlds = Files.createDirectory(temporary.resolve("cuts-" + phase));
            TransactionFixture fixture = transactionFixture(worlds, phase);
            WorldGenDatapackInstaller installer = installer(worlds, ignored -> false);

            installer.healthCheck();

            if ("ACTIVATED".equals(phase)) {
                assertEquals("new", Files.readString(fixture.target().resolve("new.txt")));
            } else {
                assertEquals("old", Files.readString(fixture.target().resolve("old.txt")));
                assertFalse(Files.exists(fixture.target().resolve("new.txt")));
            }
            assertFalse(Files.exists(fixture.stage()));
            assertFalse(Files.exists(fixture.backup()));
            assertFalse(Files.exists(fixture.journal()));
        }
    }

    @Test
    void rollsBackCrashCutsAfterRetiringSameProjectPriorRevision() throws IOException {
        for (String cut : new String[] {"RETIRING", "TARGET_MOVING"}) {
            Path worlds = Files.createDirectory(temporary.resolve("retirement-cut-" + cut));
            Path source = Files.createDirectories(worlds.resolveSibling("retirement-source-" + cut));
            Files.writeString(source.resolve("pack.mcmeta"), "{\"pack\":{}}");
            Files.writeString(source.resolve("new.txt"), "new");
            Files.writeString(source.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 2L));
            Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
            Path prior = Files.createDirectories(datapacks.resolve("resync_worldgen_pack_revision_1"));
            Files.writeString(prior.resolve("pack.mcmeta"), "{\"pack\":{}}");
            Files.writeString(prior.resolve("old.txt"), "old");
            Files.writeString(prior.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack_revision_1", 1L));
            String priorKey = fileKey(prior);
            Path retiredRoot = datapacks.resolve(".resync-retire-" + "c".repeat(32));
            Files.createDirectory(retiredRoot);
            Path retirement = retiredRoot.resolve(prior.getFileName());
            Files.move(prior, retirement);
            Path stage = datapacks.resolve(".resync-stage-" + "c".repeat(32) + "-resync_worldgen_pack");
            copyTree(source, stage);
            Path target = datapacks.resolve("resync_worldgen_pack");
            if ("TARGET_MOVING".equals(cut)) {
                copyTree(source, target);
                deleteTree(stage);
            }
            Path journal = datapacks.resolve(".resync-install-" + "c".repeat(32) + ".txn");
            writeRetirementJournal(journal, source, stage, target, retiredRoot, retirement, priorKey, cut);
            AtomicBoolean priorRestored = new AtomicBoolean();
            WorldGenDatapackInstaller installer = installer(worlds, new WorldGenDatapackInstaller.PackActivator() {
                @Override
                public boolean enable(String packName) {
                    return false;
                }

                @Override
                public void restore(String packName, String worldName, WorldGenDatapackInstaller.ActivationState state) {
                    if ("resync_worldgen_pack_revision_1".equals(packName)
                        && state.known() && state.present() && state.enabled()) {
                        priorRestored.set(true);
                    }
                }
            });

            installer.healthCheck();

            assertEquals("old", Files.readString(prior.resolve("old.txt")));
            assertFalse(Files.exists(retiredRoot));
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(journal));
            assertTrue(priorRestored.get());
        }
    }

    @Test
    void preservesAnUnknownEmptyRetirementDirectory() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("empty-retirement-worlds"));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        Path empty = Files.createDirectory(datapacks.resolve(".resync-retire-" + "d".repeat(32)));

        installer(worlds, ignored -> false).healthCheck();

        assertTrue(Files.isDirectory(empty, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void repairsAnInterruptedAtomicJournalRewrite() throws IOException {
        Path worlds = Files.createDirectory(temporary.resolve("journal-rewrite-worlds"));
        TransactionFixture fixture = transactionFixture(worlds, "ACTIVATED");
        Path temporaryJournal = fixture.journal().resolveSibling(fixture.journal().getFileName() + ".tmp");
        Files.writeString(temporaryJournal, "version=3\nphase=");

        installer(worlds, ignored -> false).healthCheck();

        assertFalse(Files.exists(temporaryJournal));
        assertFalse(Files.exists(fixture.journal()));
        assertEquals("new", Files.readString(fixture.target().resolve("new.txt")));
    }

    private void writeRetirementJournal(Path journal, Path source, Path stage, Path target, Path retiredRoot,
                                        Path retirement, String priorKey, String cut) throws IOException {
        Path datapacks = journal.getParent();
        String sourceHash = treeHash(source);
        String targetFileKey = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? fileKey(target) : "";
        String value = "version=3\n"
            + "owner=" + WorldGenInstalledDatapackCapability.OWNER + "\n"
            + "world=survival\npack=resync_worldgen_pack\nprojectId=project\nbuildRevision=2\n"
            + "source=" + source.toRealPath() + "\nsourceCanonical=" + source.toRealPath() + "\nsourceHash=" + sourceHash + "\n"
            + "sourceFileKey=" + fileKey(source) + "\ntargetHash=" + sourceHash + "\npreviousTargetExists=false\n"
            + "previousTargetHash=\npreviousTargetFileKey=\npreviousEnabledKnown=false\npreviousEnabledPresent=false\n"
            + "previousEnabled=false\nrecoveryState=ROLLBACK\n"
            + "datapacks=" + datapacks.toRealPath() + "\ndatapacksFileKey=" + fileKey(datapacks) + "\n"
            + "target=" + canonicalExpected(target) + "\ntargetCanonical=" + canonicalExpected(target) + "\n"
            + "targetFileKey=" + targetFileKey + "\n"
            + "stage=" + stage.getFileName() + "\nstageCanonical=" + canonicalExpected(stage) + "\nstageFileKey="
            + (Files.exists(stage, LinkOption.NOFOLLOW_LINKS) ? fileKey(stage) : "") + "\n"
            + "backup=.resync-backup-" + "c".repeat(32) + "-resync_worldgen_pack\n"
            + "backupCanonical=" + canonicalExpected(datapacks.resolve(".resync-backup-" + "c".repeat(32) + "-resync_worldgen_pack")) + "\n"
            + "backupFileKey=\nretiredRoot=" + retiredRoot.getFileName() + "\nretiredRootCanonical=" + retiredRoot.toRealPath()
            + "\nretiredRootFileKey=" + fileKey(retiredRoot) + "\nretiredCount=1\n"
            + "retired.0.projectId=project\nretired.0.packName=resync_worldgen_pack_revision_1\nretired.0.revision=1\n"
            + "retired.0.originalPath=" + canonicalExpected(datapacks.resolve("resync_worldgen_pack_revision_1")) + "\n"
            + "retired.0.originalFileKey=" + priorKey + "\nretired.0.retirementPath=" + retirement.toRealPath() + "\n"
            + "retired.0.retirementFileKey=" + priorKey + "\nretired.0.hash=" + treeHash(retirement) + "\n"
            + "retired.0.previousEnabledKnown=true\nretired.0.previousEnabledPresent=true\nretired.0.previousEnabled=true\n"
            + "retired.0.state=" + ("RETIRING".equals(cut) ? "MOVING" : "RETIRED") + "\n"
            + "phase=" + cut + "\n";
        Files.writeString(journal, value);
    }

    private String canonicalExpected(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return path.toRealPath().toString();
        }
        Path existing = path;
        var missing = new ArrayList<String>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing.getFileName().toString());
            existing = existing.getParent();
        }
        String value = existing.toRealPath().toString();
        for (int index = missing.size() - 1; index >= 0; index--) {
            value = Path.of(value).resolve(missing.get(index)).toString();
        }
        return value;
    }

    private String fileKey(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.fileKey() != null) {
            return attributes.fileKey().toString();
        }
        String type = attributes.isDirectory() ? "directory" : attributes.isRegularFile() ? "file" : "other";
        return "fallback:" + type + ':' + attributes.creationTime().toMillis();
    }

    private Path ownedTarget(Path worlds, String worldName, String file, String content) throws IOException {
        Path target = Files.createDirectories(worlds.resolve(worldName).resolve("datapacks").resolve("resync_worldgen_pack"));
        Files.writeString(target.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(target.resolve(file), content);
        Files.writeString(target.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        return target;
    }

    private Path orphanStage(Path datapacks, String token) throws IOException {
        Path orphan = Files.createDirectories(datapacks.resolve(".resync-stage-" + token + "-resync_worldgen_pack"));
        Files.writeString(orphan.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(orphan.resolve("partial.txt"), "partial");
        Files.writeString(orphan.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        return orphan;
    }

    private TransactionFixture transactionFixture(Path worlds, String phase) throws IOException {
        Path source = Files.createDirectories(worlds.resolveSibling(worlds.getFileName() + "-source-" + phase));
        Files.writeString(source.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(source.resolve("new.txt"), "new");
        Files.writeString(source.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        Path datapacks = Files.createDirectories(worlds.resolve("survival").resolve("datapacks"));
        String token = switch (phase) {
            case "PREPARED" -> "1".repeat(32);
            case "BACKUP_MOVED" -> "2".repeat(32);
            case "TARGET_MOVED" -> "3".repeat(32);
            case "ACTIVATING" -> "4".repeat(32);
            case "ACTIVATED" -> "5".repeat(32);
            default -> throw new IllegalArgumentException(phase);
        };
        Path stage = datapacks.resolve(".resync-stage-" + token + "-resync_worldgen_pack");
        Path backup = datapacks.resolve(".resync-backup-" + token + "-resync_worldgen_pack");
        Path journal = datapacks.resolve(".resync-install-" + token + ".txn");
        Path target = datapacks.resolve("resync_worldgen_pack");
        copyTree(source, stage);
        Path previous = Files.createDirectories(backup);
        Files.writeString(previous.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(previous.resolve("old.txt"), "old");
        Files.writeString(previous.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        String sourceHash = treeHash(stage);
        String previousHash = treeHash(previous);
        if ("PREPARED".equals(phase)) {
            Files.delete(backup.resolve("pack.mcmeta"));
            Files.delete(backup.resolve("old.txt"));
            Files.delete(backup.resolve("resync-manifest.json"));
            Files.delete(backup);
            copyTree(previousRoot(worlds, phase), target);
        } else if ("BACKUP_MOVED".equals(phase)) {
        } else {
            copyTree(stage, target);
            deleteTree(stage);
        }
        writeJournal(journal, source, phase, stage, backup, sourceHash, previousHash);
        return new TransactionFixture(target, stage, backup, journal);
    }

    private Path previousRoot(Path worlds, String phase) throws IOException {
        Path previous = worlds.resolveSibling(worlds.getFileName() + "-previous-" + phase);
        Files.createDirectories(previous);
        Files.writeString(previous.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(previous.resolve("old.txt"), "old");
        Files.writeString(previous.resolve("resync-manifest.json"), manifest("project", "resync_worldgen_pack", 1L));
        return previous;
    }

    private void writeJournal(Path journal, Path source, String phase, Path stage, Path backup,
                              String sourceHash, String previousHash) throws IOException {
        String token = journal.getFileName().toString().substring(".resync-install-".length(), ".resync-install-".length() + 32);
        String value = "version=2\nowner=" + WorldGenInstalledDatapackCapability.OWNER + "\nworld=survival\n"
            + "pack=resync_worldgen_pack\nprojectId=project\nbuildRevision=1\nsource=" + source.toAbsolutePath() + "\n"
            + "sourceHash=" + sourceHash + "\ntargetHash=" + sourceHash + "\npreviousTargetExists=true\npreviousTargetHash=" + previousHash
            + "\npreviousEnabledKnown=false\npreviousEnabled=false\ntarget=" + journal.getParent().resolve("resync_worldgen_pack").toAbsolutePath() + "\n"
            + "stage=.resync-stage-" + token + "-resync_worldgen_pack\n"
            + "backup=.resync-backup-" + token + "-resync_worldgen_pack\nphase=" + phase + "\n";
        Files.writeString(journal, value);
    }

    private void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root).sorted(Comparator.reverseOrder())) {
            for (Path path : stream.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record TransactionFixture(Path target, Path stage, Path backup, Path journal) {
    }

    private WorldGenDatapackInstaller installer(Path worlds, WorldGenDatapackInstaller.PackActivator activator) {
        return new WorldGenDatapackInstaller(worlds, "26.2", activator, WorldGenDatapackInstaller.DirectoryDurability.noop());
    }

    private Path source(String file, String content) throws IOException {
        Path source = Files.createDirectories(temporary.resolve("source-" + Math.abs(file.hashCode())));
        Files.writeString(source.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(source.resolve(file), content);
        return source;
    }

    private WorldGenDatapackBuild build(Path source, String packName, String projectId) {
        try {
            Files.writeString(source.resolve("resync-manifest.json"), manifest(projectId, packName, 1L));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        WorldGenDatapackBuild build = new WorldGenDatapackBuild();
        build.setFolder(source);
        build.setPackName(packName);
        build.setProjectId(projectId);
        build.setMinecraftVersion("26.2");
        build.setGenerationMode("hybrid");
        build.setRevision(1L);
        return build;
    }

    private String manifest(String projectId, String packName, long revision) {
        return "{\"owner\":\"" + WorldGenInstalledDatapackCapability.OWNER + "\",\"projectId\":\"" + projectId
            + "\",\"packName\":\"" + packName + "\",\"revision\":" + revision + "}";
    }

    private String treeHash(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, String> fingerprint = new TreeMap<>();
            try (var stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path)).sorted(Comparator.comparing(path -> root.relativize(path).toString())).forEach(path -> {
                    try {
                        MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
                        fileDigest.update(Files.readAllBytes(path));
                        fingerprint.put(root.relativize(path).toString(), HexFormat.of().formatHex(fileDigest.digest()));
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    } catch (NoSuchAlgorithmException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
            fingerprint.forEach((path, hash) -> {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(hash.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }
}
