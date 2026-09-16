package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.SnapshotMetadata;

public interface TypedLifecycleMigrationAdapter {
    String adapterId();

    Adaptation adapt(Input input) throws IOException;

    record Input(Path root, SnapshotMetadata metadata, String manifestHash, List<SourceFile> files) {
        public Input {
            root = MigrationPaths.requirePath(root, "snapshotRoot");
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("snapshotRoot Must Be A Non-Symbolic-Link Directory");
            }
            metadata = Objects.requireNonNull(metadata, "metadata");
            manifestHash = requireDigest(manifestHash, "manifestHash");
            List<SourceFile> sorted = new ArrayList<>(files == null ? List.of() : files);
            sorted.sort(Comparator.comparing(SourceFile::relativePath));
            Set<String> paths = new HashSet<>();
            for (SourceFile file : sorted) {
                if (!paths.add(file.relativePath())) {
                    throw new IllegalArgumentException("Duplicate Lifecycle Source File: " + file.relativePath());
                }
            }
            files = List.copyOf(sorted);
        }

        public Optional<SourceFile> file(String relativePath) {
            String normalized = MigrationPaths.requireRelative(relativePath);
            return files.stream().filter(file -> file.relativePath().equals(normalized)).findFirst();
        }

        public byte[] read(SourceFile source) throws IOException {
            Objects.requireNonNull(source, "source");
            SourceFile bound = file(source.relativePath())
                .filter(source::equals)
                .orElseThrow(() -> new MigrationException("Lifecycle Source Is Not Bound To Verified Input: " + source.relativePath()));
            Path path = MigrationPaths.resolveInside(root, bound.relativePath());
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Lifecycle Source Is Not A Regular File: " + bound.relativePath());
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != bound.size() || !sha256(bytes).equals(bound.sha256())) {
                throw new MigrationException("Lifecycle Source Changed After Verification: " + bound.relativePath());
            }
            return bytes;
        }
    }

    record SourceFile(String relativePath, long size, String sha256, String owner) {
        public SourceFile {
            relativePath = MigrationPaths.requireRelative(relativePath);
            if (size < 0) {
                throw new IllegalArgumentException("Lifecycle Source Size Must Be Non-Negative");
            }
            sha256 = requireDigest(sha256, "sha256");
            owner = requireText(owner, "owner");
        }
    }

    record Claim(String relativePath, String owner) {
        public Claim {
            relativePath = MigrationPaths.requireRelative(relativePath);
            owner = requireText(owner, "owner");
        }
    }

    record Change(String kind, String sourcePath, String targetPath, byte[] targetBytes) {
        public Change {
            kind = requireText(kind, "kind");
            sourcePath = sourcePath == null || sourcePath.isBlank() ? "" : MigrationPaths.requireRelative(sourcePath);
            targetPath = targetPath == null || targetPath.isBlank() ? "" : MigrationPaths.requireRelative(targetPath);
            if (targetPath.isEmpty() != (targetBytes == null)) {
                throw new IllegalArgumentException("Lifecycle Change Target And Bytes Must Both Be Present Or Absent");
            }
            if (sourcePath.isEmpty() && (!kind.equalsIgnoreCase("generate") || targetPath.isEmpty())) {
                throw new IllegalArgumentException("Only A Targeted Generate Change May Omit Its Source");
            }
            targetBytes = targetBytes == null ? null : targetBytes.clone();
        }

        @Override
        public byte[] targetBytes() {
            return targetBytes == null ? null : targetBytes.clone();
        }

        public String targetHash() {
            return targetBytes == null ? "" : sha256(targetBytes);
        }
    }

    record Adaptation(List<Claim> claims, List<Change> changes, List<QuarantineRecord> quarantineRecords) {
        public Adaptation {
            List<Claim> normalizedClaims = new ArrayList<>(claims == null ? List.of() : claims);
            normalizedClaims.sort(Comparator.comparing(Claim::relativePath).thenComparing(Claim::owner));
            Set<Claim> uniqueClaims = new HashSet<>();
            for (Claim claim : normalizedClaims) {
                if (!uniqueClaims.add(claim)) {
                    throw new IllegalArgumentException("Duplicate Lifecycle Source Claim: " + claim.relativePath());
                }
            }
            claims = List.copyOf(normalizedClaims);

            List<Change> normalizedChanges = new ArrayList<>(changes == null ? List.of() : changes);
            normalizedChanges.sort(Comparator.comparing(Change::sourcePath)
                .thenComparing(Change::targetPath)
                .thenComparing(Change::kind)
                .thenComparing(Change::targetHash));
            Set<String> uniqueChanges = new HashSet<>();
            for (Change change : normalizedChanges) {
                String identity = change.kind() + "\u0000" + change.sourcePath() + "\u0000" + change.targetPath() + "\u0000" + change.targetHash();
                if (!uniqueChanges.add(identity)) {
                    throw new IllegalArgumentException("Duplicate Lifecycle Change: " + change.sourcePath() + " -> " + change.targetPath());
                }
            }
            changes = List.copyOf(normalizedChanges);

            List<QuarantineRecord> normalizedQuarantine = new ArrayList<>(quarantineRecords == null ? List.of() : quarantineRecords);
            normalizedQuarantine.sort(Comparator.comparing(QuarantineRecord::recordId));
            quarantineRecords = List.copyOf(normalizedQuarantine);
        }

        public static Adaptation claimed(List<Claim> claims) {
            return new Adaptation(claims, List.of(), List.of());
        }
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }
}
