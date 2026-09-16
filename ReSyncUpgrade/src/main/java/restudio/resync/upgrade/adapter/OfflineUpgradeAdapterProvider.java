package restudio.resync.upgrade.adapter;

import java.util.Collection;
import java.util.List;

public interface OfflineUpgradeAdapterProvider {
    Collection<? extends OfflineUpgradeAdapter> adapters();

    default Collection<? extends OfflineUpgradeSnapshotAdapter> snapshotAdapters() {
        return List.of();
    }
}
