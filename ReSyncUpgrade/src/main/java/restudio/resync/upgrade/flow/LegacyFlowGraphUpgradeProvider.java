package restudio.resync.upgrade.flow;

import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterProvider;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;

import java.util.List;

public final class LegacyFlowGraphUpgradeProvider implements OfflineUpgradeAdapterProvider {
    @Override
    public List<OfflineUpgradeAdapter> adapters() {
        return List.of();
    }

    @Override
    public List<OfflineUpgradeSnapshotAdapter> snapshotAdapters() {
        return List.of(new LegacyResyncSnapshotAdapter());
    }
}
