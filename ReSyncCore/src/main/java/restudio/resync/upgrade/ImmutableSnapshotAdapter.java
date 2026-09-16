package restudio.resync.upgrade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotVerification;

public final class ImmutableSnapshotAdapter {
    private ImmutableSnapshotAdapter() {
    }

    public static View adapt(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.verified() || !snapshot.manifest().manifestHash().equals(snapshot.verification().manifestHash())) {
            throw new MigrationException("Snapshot Must Be Verified Before Offline Planning");
        }
        SnapshotVerification verification = snapshot.manifest().verify(snapshot.root());
        verification.requireVerified();
        if (!snapshot.manifest().manifestHash().equals(verification.manifestHash())) {
            throw new MigrationException("Snapshot Manifest Hash Changed During Offline Planning");
        }
        return new View(
            snapshot.metadata(),
            snapshot.manifest().manifestHash(),
            snapshot.manifest().directories(),
            snapshot.manifest().entries().stream()
                .map(Entry::from)
                .toList());
    }

    public record View(SnapshotMetadata metadata, String manifestHash, List<String> directories, List<Entry> entries) {
        public View {
            metadata = Objects.requireNonNull(metadata, "metadata");
            manifestHash = requireDigest(manifestHash, "manifestHash");
            directories = normalizeDirectories(directories);
            entries = normalizeEntries(entries);
            Set<String> directorySet = new HashSet<>(directories);
            for (Entry entry : entries) {
                if (directorySet.contains(entry.relativePath())) {
                    throw new IllegalArgumentException("Snapshot Path Is Both A File And Directory: " + entry.relativePath());
                }
            }
        }
    }

    public record Entry(String relativePath, long size, String sha256, String owner) {
        public Entry {
            relativePath = MigrationPaths.requireRelative(relativePath);
            if (size < 0) {
                throw new IllegalArgumentException("File Size Must Be Non-Negative");
            }
            sha256 = requireDigest(sha256, "sha256");
            owner = requireText(owner, "owner");
        }

        private static Entry from(SnapshotManifest.Entry entry) {
            return new Entry(entry.relativePath(), entry.size(), entry.sha256(), entry.owner());
        }
    }

    private static List<String> normalizeDirectories(List<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        if (values != null) {
            values.forEach(value -> sorted.add(MigrationPaths.requireRelative(value)));
        }
        return List.copyOf(sorted);
    }

    private static List<Entry> normalizeEntries(List<Entry> values) {
        List<Entry> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.sort(java.util.Comparator.comparing(Entry::relativePath));
        Set<String> paths = new HashSet<>();
        for (Entry entry : sorted) {
            if (!paths.add(entry.relativePath())) {
                throw new IllegalArgumentException("Duplicate Snapshot File: " + entry.relativePath());
            }
        }
        return List.copyOf(sorted);
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }
}
