package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.NetworkTransferStatus;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkTransferRecoveryStoreTest {
    @TempDir
    Path temporary;

    @Test
    void activeTransferSurvivesAStoreRestartForRecovery() {
        Path networkRoot = temporary.resolve("network");
        long now = Instant.now().toEpochMilli();
        PlayerTransfer transfer = new PlayerTransfer("transfer-1", "network-1", UUID.randomUUID(), "source-1", "target-1", 4, NetworkTransferStatus.SNAPSHOT_COMMITTED, "snapshot-1", "", now + 60_000, now, now);

        NetworkTransferRecoveryStore first = new NetworkTransferRecoveryStore(networkRoot);
        first.remember(transfer);

        NetworkTransferRecoveryStore restarted = new NetworkTransferRecoveryStore(networkRoot);

        assertEquals(List.of(transfer), restarted.snapshot());
    }

    @Test
    void flushRejectsARecoveryFileWhoseIdentityDoesNotMatchItsPath() throws Exception {
        Path networkRoot = temporary.resolve("network");
        long now = Instant.now().toEpochMilli();
        PlayerTransfer transfer = new PlayerTransfer("transfer-1", "network-1", UUID.randomUUID(), "source-1", "target-1", 4, NetworkTransferStatus.SNAPSHOT_COMMITTED, "snapshot-1", "", now + 60_000, now, now);
        PlayerTransfer replacement = new PlayerTransfer("transfer-2", "network-1", UUID.randomUUID(), "source-1", "target-1", 4, NetworkTransferStatus.SNAPSHOT_COMMITTED, "snapshot-2", "", now + 60_000, now, now);

        NetworkTransferRecoveryStore store = new NetworkTransferRecoveryStore(networkRoot);
        store.remember(transfer);
        Files.write(networkRoot.resolve("transfer-recovery").resolve(StorageSafety.sha256(transfer.transferId()) + ".transfer"), NetworkTransferCodec.encodeTransfer(replacement));

        assertThrows(IOException.class, store::flush);
    }
}
