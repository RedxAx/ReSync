package restudio.resync.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SnapshotManifest {
    public record Entry(String relativePath, long size, String sha256, String owner) {
        public Entry {
            relativePath = MigrationPaths.requireRelative(relativePath);
            if (size < 0) {
                throw new IllegalArgumentException("File Size Must Be Non-Negative");
            }
            sha256 = MigrationCanonical.requireDigest(sha256, "sha256");
            owner = MigrationCanonical.requireText(owner, "owner");
        }
    }

    private final SnapshotMetadata metadata;
    private final List<String> directories;
    private final List<Entry> entries;
    private final String manifestHash;

    public SnapshotManifest(SnapshotMetadata metadata, List<String> directories, List<Entry> entries) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.directories = normalizeDirectories(directories);
        this.entries = normalizeEntries(entries);
        Set<String> directorySet = new HashSet<>(this.directories);
        for (Entry entry : this.entries) {
            if (directorySet.contains(entry.relativePath())) {
                throw new IllegalArgumentException("Manifest Path Is Both A File And Directory: " + entry.relativePath());
            }
        }
        validateFileAncestors(this.directories, this.entries);
        this.manifestHash = MigrationCanonical.sha256(canonicalText());
    }

    public static SnapshotManifest scan(Path root, SnapshotMetadata metadata, PersistenceParticipantRegistry participants) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "snapshotRoot");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(participants, "participants");
        PersistenceParticipantRegistry.OwnershipResolution resolution = participants.resolutionForRoot(normalizedRoot);
        resolution.validate();
        List<String> directories = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(normalizedRoot) && resolution.isExternalPath(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(normalizedRoot)) {
                    directories.add(MigrationPaths.relative(normalizedRoot, directory));
                    if (participants.isDerivedCachePath(normalizedRoot, directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                }
                if (resolution.isExternalPath(file)) {
                    return FileVisitResult.CONTINUE;
                }
                entries.add(new Entry(MigrationPaths.relative(normalizedRoot, file), attributes.size(), sha256(file),
                    resolution.ownerFor(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Read Snapshot File: " + file, exception);
            }
        });
        return new SnapshotManifest(metadata, directories, entries);
    }

    public SnapshotMetadata metadata() {
        return metadata;
    }

    public List<String> directories() {
        return directories;
    }

    public List<Entry> entries() {
        return entries;
    }

    public String manifestHash() {
        return manifestHash;
    }

    public long totalBytes() {
        return entries.stream().mapToLong(Entry::size).sum();
    }

    public String canonicalText() {
        StringBuilder value = new StringBuilder();
        value.append("format=").append(metadata.formatVersion()).append('\n');
        value.append("snapshot-id=").append(MigrationCanonical.encode(metadata.snapshotId())).append('\n');
        value.append("created-at=").append(metadata.createdAt().toEpochMilli()).append('\n');
        value.append("build=").append(MigrationCanonical.encode(metadata.build())).append('\n');
        value.append("catalog=").append(metadata.catalogChecksum()).append('\n');
        value.append("extensions=").append(metadata.extensionVersions().size()).append('\n');
        metadata.extensionVersions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> value.append("extension=").append(MigrationCanonical.encode(entry.getKey())).append('|').append(MigrationCanonical.encode(entry.getValue())).append('\n'));
        value.append("directories=").append(directories.size()).append('\n');
        directories.forEach(directory -> value.append("directory=").append(MigrationCanonical.encode(directory)).append('\n'));
        value.append("files=").append(entries.size()).append('\n');
        entries.forEach(entry -> value.append("file=").append(MigrationCanonical.encode(entry.relativePath())).append('|').append(entry.size()).append('|').append(entry.sha256()).append('|').append(MigrationCanonical.encode(entry.owner())).append('\n'));
        return value.toString();
    }

    public byte[] canonicalBytes() {
        return canonicalText().getBytes(StandardCharsets.UTF_8);
    }

    public void write(Path path) throws IOException {
        Path target = MigrationPaths.requirePath(path, "manifestPath");
        MigrationPaths.requireWritableParent(target);
        String content = canonicalText() + "manifest-hash=" + manifestHash + "\n";
        AtomicFiles.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static SnapshotManifest read(Path path) throws IOException {
        Path target = MigrationPaths.requirePath(path, "manifestPath");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new MigrationException("Manifest Is Not A Regular File: " + target);
        }
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getLast().startsWith("manifest-hash=")) {
            throw new MigrationException("Manifest Hash Is Missing");
        }
        String storedHash = MigrationCanonical.requireDigest(lines.removeLast().substring("manifest-hash=".length()), "manifestHash");
        int cursor = 0;
        int format = integerLine(lines, cursor++, "format=");
        String snapshotId = decodedLine(lines, cursor++, "snapshot-id=");
        long createdAt = longLine(lines, cursor++, "created-at=");
        String build = decodedLine(lines, cursor++, "build=");
        String catalog = rawLine(lines, cursor++, "catalog=");
        int extensionCount = nonNegativeCount(lines, cursor++, "extensions=");
        Map<String, String> extensions = new HashMap<>();
        for (int index = 0; index < extensionCount; index++) {
            String value = requiredLine(lines, cursor++, "extension=").substring("extension=".length());
            String[] fields = value.split("\\|", -1);
            if (fields.length != 2) {
                throw new MigrationException("Invalid Extension Manifest Row");
            }
            if (extensions.put(decoded(fields[0]), decoded(fields[1])) != null) {
                throw new MigrationException("Duplicate Extension Manifest Row");
            }
        }
        int directoryCount = nonNegativeCount(lines, cursor++, "directories=");
        List<String> directories = new ArrayList<>();
        for (int index = 0; index < directoryCount; index++) {
            directories.add(decodedLine(lines, cursor++, "directory="));
        }
        int fileCount = nonNegativeCount(lines, cursor++, "files=");
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < fileCount; index++) {
            String value = requiredLine(lines, cursor++, "file=").substring("file=".length());
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4) {
                throw new MigrationException("Invalid File Manifest Row");
            }
            entries.add(new Entry(decoded(fields[0]), parseSize(fields[1]), fields[2], decoded(fields[3])));
        }
        if (cursor != lines.size()) {
            throw new MigrationException("Unexpected Manifest Content");
        }
        SnapshotManifest manifest = new SnapshotManifest(new SnapshotMetadata(format, snapshotId, Instant.ofEpochMilli(createdAt), build, catalog, extensions), directories, entries);
        if (!manifest.manifestHash.equals(storedHash)) {
            throw new MigrationException("Manifest Hash Does Not Match Content");
        }
        return manifest;
    }

    public SnapshotVerification verify(Path root) throws IOException {
        List<String> failures = new ArrayList<>();
        Map<String, BasicFileAttributes> actualFiles = new HashMap<>();
        Set<String> actualDirectories = new TreeSet<>();
        try {
            Path normalizedRoot = MigrationPaths.requireDirectory(root, "snapshotRoot");
            Files.walkFileTree(normalizedRoot, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                    }
                    if (!directory.equals(normalizedRoot)) {
                        actualDirectories.add(MigrationPaths.relative(normalizedRoot, directory));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                    }
                    if (!attributes.isRegularFile()) {
                        throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                    }
                    actualFiles.put(MigrationPaths.relative(normalizedRoot, file), attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw new MigrationException("Cannot Read Snapshot File: " + file, exception);
                }
            });
            Map<String, Entry> expected = new HashMap<>();
            for (Entry entry : entries) {
                expected.put(entry.relativePath(), entry);
                BasicFileAttributes attributes = actualFiles.get(entry.relativePath());
                if (attributes == null) {
                    failures.add("Missing File: " + entry.relativePath());
                    continue;
                }
                Path file = MigrationPaths.resolveInside(normalizedRoot, entry.relativePath());
                if (attributes.size() != entry.size()) {
                    failures.add("Size Mismatch: " + entry.relativePath());
                }
                if (!entry.sha256().equals(sha256(file))) {
                    failures.add("Hash Mismatch: " + entry.relativePath());
                }
            }
            actualFiles.keySet().stream().filter(path -> !expected.containsKey(path)).sorted().forEach(path -> failures.add("Unexpected File: " + path));
            Set<String> expectedDirectories = new TreeSet<>(directories);
            expectedDirectories.stream().filter(path -> !actualDirectories.contains(path)).forEach(path -> failures.add("Missing Directory: " + path));
            actualDirectories.stream().filter(path -> !expectedDirectories.contains(path)).forEach(path -> failures.add("Unexpected Directory: " + path));
        } catch (Exception exception) {
            failures.add(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
        return new SnapshotVerification(failures.isEmpty(), manifestHash, failures);
    }

    private static List<String> normalizeDirectories(List<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        if (values != null) {
            values.forEach(value -> {
                String normalized = MigrationPaths.requireRelative(value);
                if (!sorted.add(normalized)) {
                    throw new IllegalArgumentException("Duplicate Manifest Directory: " + normalized);
                }
            });
        }
        return List.copyOf(sorted);
    }

    private static List<Entry> normalizeEntries(List<Entry> values) {
        List<Entry> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.sort(Comparator.comparing(Entry::relativePath));
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).relativePath().equals(sorted.get(index).relativePath())) {
                throw new IllegalArgumentException("Duplicate Manifest File: " + sorted.get(index).relativePath());
            }
        }
        return List.copyOf(sorted);
    }

    private static void validateFileAncestors(List<String> directories, List<Entry> entries) {
        for (Entry entry : entries) {
            String file = entry.relativePath();
            if (directories.stream().anyMatch(directory -> isStrictPathAncestor(file, directory))) {
                throw new IllegalArgumentException("Manifest File Is An Ancestor Of Another Path: " + file);
            }
            if (entries.stream().map(Entry::relativePath).anyMatch(path -> isStrictPathAncestor(file, path))) {
                throw new IllegalArgumentException("Manifest File Is An Ancestor Of Another Path: " + file);
            }
        }
    }

    private static boolean isStrictPathAncestor(String ancestor, String descendant) {
        return descendant.startsWith(ancestor + "/");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path); DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String requiredLine(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Manifest Line: " + prefix);
        }
        return lines.get(index);
    }

    private static String rawLine(List<String> lines, int index, String prefix) throws MigrationException {
        return requiredLine(lines, index, prefix).substring(prefix.length());
    }

    private static String decodedLine(List<String> lines, int index, String prefix) throws MigrationException {
        return decoded(rawLine(lines, index, prefix));
    }

    private static String decoded(String value) throws MigrationException {
        return MigrationCanonical.decode(value);
    }

    private static int integerLine(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Integer.parseInt(rawLine(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Manifest Number: " + prefix, exception);
        }
    }

    private static int nonNegativeCount(List<String> lines, int index, String prefix) throws MigrationException {
        int count = integerLine(lines, index, prefix);
        if (count < 0) {
            throw new MigrationException("Manifest Count Must Be Non-Negative: " + prefix);
        }
        return count;
    }

    private static long longLine(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Long.parseLong(rawLine(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Manifest Number: " + prefix, exception);
        }
    }

    private static long parseSize(String value) throws MigrationException {
        try {
            long size = Long.parseLong(value);
            if (size < 0) {
                throw new NumberFormatException();
            }
            return size;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Manifest File Size", exception);
        }
    }
}
