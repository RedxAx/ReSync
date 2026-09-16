package restudio.resync.upgrade;

public final class LegacySnapshotWindow {
    public static final int SOURCE_FORMAT_VERSION = 1;
    public static final int TARGET_FORMAT_VERSION = 2;
    public static final String SOURCE_BUILD = "legacy-resync-snapshot-v1";
    public static final String REPLACEMENT_CONTRACT = "resync-legacy-snapshot-composition-v1";

    private LegacySnapshotWindow() {
    }

    public static UpgradeSourceWindow sourceWindow() {
        return new UpgradeSourceWindow(UpgradeVersion.current(), SOURCE_FORMAT_VERSION, SOURCE_BUILD,
            TARGET_FORMAT_VERSION, REPLACEMENT_CONTRACT);
    }
}
