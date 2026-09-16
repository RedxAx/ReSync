package restudio.resync.upgrade.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OfflineUpgradeAdapterRegistryTest {
    @Test
    void serviceDiscoveryDoesNotClaimRuntimeMigratorsAndRejectsDuplicateWireIds() {
        OfflineUpgradeAdapterRegistry discovered = OfflineUpgradeAdapterRegistry.discover(getClass().getClassLoader());

        assertTrue(discovered.adapters().isEmpty());
        assertTrue(discovered.snapshotAdapters().stream()
            .anyMatch(adapter -> adapter.wireId().equals("resync.legacy-snapshot#1")));
        OfflineUpgradeAdapter adapter = new IdentityFileAdapter("graph.json", "standalone-root");
        discovered.register(adapter);
        assertThrows(IllegalArgumentException.class, () -> discovered.register(adapter));
    }
}
