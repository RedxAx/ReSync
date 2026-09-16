package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MigrationPreflight {
    private final UsableSpace usableSpace;

    public MigrationPreflight() {
        this(path -> Files.getFileStore(path).getUsableSpace());
    }

    MigrationPreflight(UsableSpace usableSpace) {
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
    }

    public PreflightResult inspect(Path sourceRoot, Path stagingRoot, PersistenceParticipantRegistry participants, long reservedBytes) {
        List<PreflightResult.Check> checks = new ArrayList<>();
        Path source = sourceRoot == null ? null : sourceRoot.toAbsolutePath().normalize();
        Path staging = stagingRoot == null ? null : stagingRoot.toAbsolutePath().normalize();
        SnapshotManifest sourceManifest = null;

        try {
            source = MigrationPaths.requireDirectory(source, "sourceRoot");
            checks.add(new PreflightResult.Check("source-root", true, source.toString()));
        } catch (Exception exception) {
            checks.add(new PreflightResult.Check("source-root", false, message(exception)));
        }

        try {
            if (staging == null) {
                throw new MigrationException("stagingRoot Is Required");
            }
            MigrationPaths.requirePath(staging, "stagingRoot");
            MigrationPaths.requireDistinctRoots(source, staging);
            MigrationPaths.requireWritableParent(staging);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("stagingRoot Must Be A Non-Symbolic-Link Directory");
                }
                try (var stream = Files.list(staging)) {
                    if (stream.findAny().isPresent()) {
                        throw new MigrationException("stagingRoot Must Be Empty");
                    }
                }
            }
            checks.add(new PreflightResult.Check("staging-root", true, staging.toString()));
        } catch (Exception exception) {
            checks.add(new PreflightResult.Check("staging-root", false, message(exception)));
        }

        try {
            if (participants == null) {
                throw new MigrationException("Persistence Participants Are Required");
            }
            participants.validateForRoot(source);
            checks.add(new PreflightResult.Check("participants", true, Integer.toString(participants.participants().size())));
        } catch (Exception exception) {
            checks.add(new PreflightResult.Check("participants", false, message(exception)));
        }

        try {
            if (source == null) {
                throw new MigrationException("Cannot Scan A Missing Source Root");
            }
            sourceManifest = SnapshotManifest.scan(source, SnapshotMetadata.preflight(), participants);
            checks.add(new PreflightResult.Check("source-tree", true, sourceManifest.entries().size() + " files"));
        } catch (Exception exception) {
            checks.add(new PreflightResult.Check("source-tree", false, message(exception)));
        }

        try {
            if (sourceManifest == null || staging == null) {
                throw new MigrationException("Source Tree Must Be Scannable Before Disk Check");
            }
            if (reservedBytes < 0) {
                throw new MigrationException("reservedBytes Must Be Non-Negative");
            }
            Path parent = staging.getParent();
            if (parent == null) {
                throw new MigrationException("stagingRoot Has No Parent");
            }
            Path existingParent = parent;
            while (!Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
                existingParent = existingParent.getParent();
                if (existingParent == null) {
                    throw new MigrationException("No Existing Staging Parent");
                }
            }
            long required = Math.addExact(sourceManifest.totalBytes(), reservedBytes);
            long available = usableSpace.bytes(existingParent);
            if (available < required) {
                throw new MigrationException("Insufficient Staging Space: Required " + required + ", Available " + available);
            }
            checks.add(new PreflightResult.Check("disk-space", true, Long.toString(required)));
        } catch (Exception exception) {
            checks.add(new PreflightResult.Check("disk-space", false, message(exception)));
        }

        return new PreflightResult(checks.stream().allMatch(PreflightResult.Check::passed), checks);
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface UsableSpace {
        long bytes(Path path) throws IOException;
    }
}
