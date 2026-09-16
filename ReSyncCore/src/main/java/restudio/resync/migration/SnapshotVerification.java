package restudio.resync.migration;

import java.io.IOException;
import java.util.List;

public record SnapshotVerification(boolean verified, String manifestHash, List<String> failures) {
    public SnapshotVerification {
        manifestHash = MigrationCanonical.requireDigest(manifestHash, "manifestHash");
        failures = failures == null ? List.of() : failures.stream().sorted().toList();
    }

    public void requireVerified() throws IOException {
        if (!verified) {
            throw new MigrationException("Snapshot Verification Failed: " + String.join("; ", failures));
        }
    }
}
