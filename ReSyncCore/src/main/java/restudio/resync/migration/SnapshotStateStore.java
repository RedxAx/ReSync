package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SnapshotStateStore {
    private SnapshotStateStore() {
    }

    static void write(Path path, SnapshotState state, SnapshotVerification verification) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("state=").append(state.name()).append('\n');
        content.append("verified=").append(verification.verified()).append('\n');
        content.append("manifest-hash=").append(verification.manifestHash()).append('\n');
        content.append("failures=").append(verification.failures().size()).append('\n');
        verification.failures().forEach(failure -> content.append("failure=").append(MigrationCanonical.encode(failure)).append('\n'));
        AtomicFiles.write(path, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    static SnapshotVerification readVerification(Path path) throws IOException {
        Path target = MigrationPaths.requirePath(path, "statePath");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new MigrationException("Snapshot State Is Not A Regular File: " + target);
        }
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        if (lines.size() < 4) {
            throw new MigrationException("Snapshot State Is Incomplete");
        }
        String state = line(lines, 0, "state=");
        SnapshotState.valueOf(state);
        boolean verified = Boolean.parseBoolean(line(lines, 1, "verified="));
        String hash = MigrationCanonical.requireDigest(line(lines, 2, "manifest-hash="), "manifestHash");
        int count;
        try {
            count = Integer.parseInt(line(lines, 3, "failures="));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Snapshot Failure Count", exception);
        }
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            failures.add(MigrationCanonical.decode(line(lines, index + 4, "failure=")));
        }
        if (lines.size() != count + 4) {
            throw new MigrationException("Unexpected Snapshot State Content");
        }
        return new SnapshotVerification(verified, hash, failures);
    }

    private static String line(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Snapshot State Line: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }
}
