package restudio.resync.network.paper.state;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkStateReconciliationTask;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerStateReconcilerTempTest {
    @TempDir
    Path temporary;

    @Test
    void quarantinesUniquePlayerTempByItsExactPlayerIdentity() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("playerdata"));
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        String name = "." + playerId + ".dat." + UUID.randomUUID() + ".resync.tmp";
        Path source = Files.writeString(playerData.resolve(name), "evidence");

        NetworkPlayerStateReconciler.recoverPlayerDataTemps(playerData);

        Path destination = playerData.resolve(".quarantine/player-data").resolve(playerId.toString()).resolve(name);
        assertFalse(Files.exists(source));
        assertEquals("evidence", Files.readString(destination));
    }

    @Test
    void leavesAmbiguousPlayerTempEvidenceInPlace() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("ambiguous-playerdata"));
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        Path source = Files.writeString(playerData.resolve("." + playerId + ".dat.bad-token.resync.tmp"), "evidence");

        assertThrows(IOException.class, () -> NetworkPlayerStateReconciler.recoverPlayerDataTemps(playerData));
        assertTrue(Files.exists(source));
        assertEquals("evidence", Files.readString(source));
    }

    @Test
    void rejectsSymlinkPlayerTempEvidenceWithoutFollowingIt() throws Exception {
        Path playerData = Files.createDirectories(temporary.resolve("symlink-playerdata"));
        Path external = Files.writeString(temporary.resolve("external"), "evidence");
        UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
        Path source = playerData.resolve("." + playerId + ".dat." + UUID.randomUUID() + ".resync.tmp");
        try {
            Files.createSymbolicLink(source, external);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> NetworkPlayerStateReconciler.recoverPlayerDataTemps(playerData));
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("evidence", Files.readString(external));
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

    @Test
    void shutdownCompletesWhenBukkitCancelsAnAdmittedTask() throws Exception {
        MockBukkit.mock();
        try {
            TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
            NetworkPlayerStateReconciler reconciler = new NetworkPlayerStateReconciler(plugin, null);
            UUID playerId = UUID.fromString("0b3ec67a-3381-48e2-add1-efb503353842");
            CompletableFuture<Void> reconciliation = reconciler.reconcile(new NetworkStateReconciliationTask(
                "cancelled-task", Set.of(playerId), Set.of("inventory")));
            BukkitTask scheduled = MockBukkit.getMock().getScheduler().getPendingTasks().stream()
                .findFirst().orElseThrow();
            scheduled.cancel();

            reconciler.shutdown().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> reconciliation.get(1, TimeUnit.SECONDS));
        } finally {
            MockBukkit.unmock();
        }
    }

    private static void assertNbtReadFails(Path file) throws Exception {
        Class<?> nbt = Class.forName(NetworkPlayerStateReconciler.class.getName() + "$Nbt");
        Method method = nbt.getDeclaredMethod("readCompressed", Path.class);
        method.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, file));
        assertTrue(exception.getCause() instanceof IOException);
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }

        @Override
        public restudio.resync.network.paper.ReSyncNetworkAgent getNetworkAgent() {
            return null;
        }
    }
}
