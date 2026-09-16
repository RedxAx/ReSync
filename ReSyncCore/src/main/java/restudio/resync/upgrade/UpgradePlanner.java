package restudio.resync.upgrade;

import java.io.IOException;

import restudio.resync.migration.Snapshot;

@FunctionalInterface
public interface UpgradePlanner {
    UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException;
}
