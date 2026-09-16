package restudio.resync.customcontent;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentOfflinePlayerDataReconcilerTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsOnlyCanonicalPlayerDataFileNames() {
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");

        assertEquals(playerId, CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + ".dat")));
        assertEquals(playerId, CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId.toString().toUpperCase() + ".dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + "-13286177663396300053.dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of(playerId + ".dat.tmp")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(Path.of("not-a-player.dat")));
        assertNull(CustomContentOfflinePlayerDataReconciler.playerIdFromFile(null));
    }

    @Test
    void quarantinesLegacyAndUniquePlayerTempsWithoutClaimingTheirTarget() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("temp-recovery").resolve("playerdata"));
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        String uniqueName = "." + playerId + ".dat." + UUID.randomUUID() + ".resync.tmp";
        Path unique = Files.writeString(playerData.resolve(uniqueName), "unique-evidence");
        Path legacy = Files.writeString(playerData.resolve(playerId + ".dat.resync.tmp"), "legacy-evidence");

        CustomContentOfflinePlayerDataReconciler.recoverPlayerDataTemps(playerData);

        Path quarantine = playerData.resolve(".quarantine/player-data").resolve(playerId.toString());
        assertFalse(Files.exists(unique));
        assertFalse(Files.exists(legacy));
        assertEquals("unique-evidence", Files.readString(quarantine.resolve(uniqueName)));
        assertEquals("legacy-evidence", Files.readString(quarantine.resolve(legacy.getFileName())));
        assertFalse(Files.exists(playerData.resolve(playerId + ".dat")));
    }

    @Test
    void preservesAmbiguousPlayerTempEvidenceAndFailsClosed() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("ambiguous-temp").resolve("playerdata"));
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        Path ambiguous = Files.writeString(playerData.resolve("." + playerId + ".dat.not-a-uuid.resync.tmp"), "evidence");

        assertThrows(IOException.class, () -> CustomContentOfflinePlayerDataReconciler.recoverPlayerDataTemps(playerData));
        assertTrue(Files.exists(ambiguous));
        assertEquals("evidence", Files.readString(ambiguous));
    }

    @Test
    void preservesSymlinkPlayerTempEvidenceAndFailsClosed() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("symlink-temp").resolve("playerdata"));
        Path external = Files.writeString(temporary.resolve("external-temp"), "external");
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        Path linked = playerData.resolve("." + playerId + ".dat." + UUID.randomUUID() + ".resync.tmp");
        try {
            Files.createSymbolicLink(linked, external);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> CustomContentOfflinePlayerDataReconciler.recoverPlayerDataTemps(playerData));
        assertTrue(Files.isSymbolicLink(linked));
        assertEquals("external", Files.readString(external));
    }

    @Test
    void schedulerHandoffAndCancellationReleaseAdmissionLeaseExactlyOnce() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        Path playerData = Files.createDirectories(worldRoot.resolve("playerdata"));
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        Path file = playerData.resolve(playerId + ".dat");
        writeEmptyPlayerData(file);
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(
            List.of(worldRoot), worldRoot.getParent());
        RecordingScheduler scheduler = new RecordingScheduler();
        CustomContentOfflinePlayerDataReconciler reconciler = new CustomContentOfflinePlayerDataReconciler(
            null, admission, scheduler, ignored -> true);
        ExecutorService passExecutor = Executors.newSingleThreadExecutor();
        ExecutorService quiesceExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> pass = passExecutor.submit(() -> invokeReconcileFile(reconciler, null, file));
            pass.get(1, TimeUnit.SECONDS);
            assertEquals(1, admission.activeWorkCount());

            CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
                try {
                    admission.quiesce(Duration.ofSeconds(2));
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }, quiesceExecutor);
            waitForState(admission, PaperPlayerDataMutationAdmission.State.QUIESCING);
            assertFalse(quiesce.isDone());

            scheduler.runNext();
            quiesce.get(2, TimeUnit.SECONDS);
            assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCED, admission.state());
            assertEquals(0, admission.activeWorkCount());
        } finally {
            passExecutor.shutdownNow();
            quiesceExecutor.shutdownNow();
        }

        Path cancelledWorldRoot = Files.createDirectories(temporary.resolve("cancelled-world"));
        Path cancelledPlayerData = Files.createDirectories(cancelledWorldRoot.resolve("playerdata"));
        Path cancelledFile = cancelledPlayerData.resolve(playerId + ".dat");
        writeEmptyPlayerData(cancelledFile);
        PaperPlayerDataMutationAdmission cancelledAdmission = new PaperPlayerDataMutationAdmission(
            List.of(cancelledWorldRoot), cancelledWorldRoot.getParent());
        RecordingScheduler cancelledScheduler = new RecordingScheduler();
        CustomContentOfflinePlayerDataReconciler cancelledReconciler =
            new CustomContentOfflinePlayerDataReconciler(null, cancelledAdmission, cancelledScheduler, ignored -> true);
        invokeReconcileFile(cancelledReconciler, null, cancelledFile);
        assertEquals(1, cancelledAdmission.activeWorkCount());

        cancelledReconciler.shutdown();
        cancelledReconciler.shutdown();

        assertEquals(0, cancelledAdmission.activeWorkCount());
        assertEquals(1, cancelledScheduler.cancelledCount());
        cancelledScheduler.runNext();
        assertEquals(0, cancelledAdmission.activeWorkCount());
    }

    @Test
    void rejectsNbtNestingAndCollectionBoundsBeforeAllocatingPlayerData() throws Exception {
        Path deep = temporary.resolve("deep.dat");
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(deep)))) {
            int compounds = 70;
            for (int index = 0; index < compounds; index++) {
                output.writeByte(10);
                output.writeShort(0);
            }
            for (int index = 0; index < compounds; index++) {
                output.writeByte(0);
            }
        }
        assertNbtReadFails(deep);

        Path largeList = temporary.resolve("large-list.dat");
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(largeList)))) {
            output.writeByte(10);
            output.writeShort(0);
            output.writeByte(9);
            output.writeShort(1);
            output.writeByte('l');
            output.writeByte(1);
            output.writeInt(1_000_001);
            output.writeByte(0);
        }
        assertNbtReadFails(largeList);

        Path longName = temporary.resolve("long-name.dat");
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(longName)))) {
            output.writeByte(10);
            output.writeShort(0);
            output.writeByte(1);
            output.writeShort(4_097);
            output.write(new byte[4_097]);
            output.writeByte(0);
        }
        assertNbtReadFails(longName);

        Path trailing = temporary.resolve("trailing.dat");
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(trailing)))) {
            output.writeByte(10);
            output.writeShort(0);
            output.writeByte(0);
            output.writeByte(1);
        }
        assertNbtReadFails(trailing);
    }

    private static void invokeReconcileFile(CustomContentOfflinePlayerDataReconciler reconciler,
                                            JavaPlugin plugin, Path file) {
        try {
            Method method = CustomContentOfflinePlayerDataReconciler.class.getDeclaredMethod(
                "reconcileFile", JavaPlugin.class, Path.class, String.class, boolean.class, Set.class);
            method.setAccessible(true);
            method.invoke(reconciler, plugin, file, "content", false, Set.of());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void writeEmptyPlayerData(Path file) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10);
            output.writeShort(0);
            output.writeByte(0);
        }
    }

    private static void assertNbtReadFails(Path file) throws Exception {
        Class<?> nbt = Class.forName(CustomContentOfflinePlayerDataReconciler.class.getName() + "$Nbt");
        Method method = nbt.getDeclaredMethod("readCompressed", Path.class);
        method.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, file));
        assertTrue(exception.getCause() instanceof IOException);
    }

    private static void waitForState(PaperPlayerDataMutationAdmission admission,
                                     PaperPlayerDataMutationAdmission.State state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admission.state() != state && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(state, admission.state());
    }

    private static final class RecordingScheduler implements CustomContentOfflinePlayerDataReconciler.ReconcileScheduler {
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        private int cancelled;

        @Override
        public synchronized CustomContentOfflinePlayerDataReconciler.ScheduledTask schedule(
            JavaPlugin plugin, boolean asynchronous, Runnable callback) {
            callbacks.addLast(callback);
            return () -> {
                synchronized (this) {
                    cancelled++;
                }
            };
        }

        private synchronized void runNext() {
            callbacks.removeFirst().run();
        }

        private synchronized int cancelledCount() {
            return cancelled;
        }
    }
}
