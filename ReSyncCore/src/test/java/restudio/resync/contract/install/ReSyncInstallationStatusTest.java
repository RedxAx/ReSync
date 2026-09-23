package restudio.resync.contract.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReSyncInstallationStatusTest {
    @Test
    void blockedStatusRoundTripsThroughThePortableContract() {
        ReSyncInstallationStatus status = ReSyncInstallationStatus.legacyDataBlocked();

        ReSyncInstallationStatus decoded = ReSyncInstallationStatus.decode(status.encode());

        assertEquals(status, decoded);
        assertTrue(decoded.blocksStartup());
        assertTrue(decoded.preservesLegacyData());
    }
}
