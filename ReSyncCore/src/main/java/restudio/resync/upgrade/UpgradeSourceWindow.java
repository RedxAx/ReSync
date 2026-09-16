package restudio.resync.upgrade;

import java.util.Objects;

import restudio.resync.migration.SnapshotMetadata;

public record UpgradeSourceWindow(
    UpgradeVersion upgraderVersion,
    int sourceFormatVersion,
    String sourceBuild,
    int targetFormatVersion,
    String replacementContract
) {
    public UpgradeSourceWindow {
        upgraderVersion = Objects.requireNonNull(upgraderVersion, "upgraderVersion");
        if (sourceFormatVersion < 1 || targetFormatVersion < 1) {
            throw new IllegalArgumentException("Upgrade Format Versions Must Be Positive");
        }
        sourceBuild = requireText(sourceBuild, "sourceBuild");
        replacementContract = requireText(replacementContract, "replacementContract");
    }

    public boolean supports(SnapshotMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return metadata.formatVersion() == sourceFormatVersion && sourceBuild.equals(metadata.build());
    }

    public void requireSupported(SnapshotMetadata metadata) {
        if (!supports(metadata)) {
            throw new IllegalArgumentException("Snapshot Is Outside The Supported Upgrade Window");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return normalized;
    }
}
