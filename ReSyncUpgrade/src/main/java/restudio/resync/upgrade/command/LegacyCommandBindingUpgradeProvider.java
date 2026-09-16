package restudio.resync.upgrade.command;

import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterProvider;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;

import java.util.List;

public final class LegacyCommandBindingUpgradeProvider implements OfflineUpgradeAdapterProvider {
    @Override
    public List<OfflineUpgradeAdapter> adapters() {
        return List.of();
    }

    @Override
    public List<OfflineUpgradeSnapshotAdapter> snapshotAdapters() {
        return List.of();
    }
}
