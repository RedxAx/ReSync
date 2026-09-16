package restudio.resync.migration;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MigrationPaths {
    private MigrationPaths() {
    }

    public static Path requireDirectory(Path path, String field) throws IOException {
        if (path == null) {
            throw new MigrationException(field + " Is Required");
        }
        Path absolute = normalizePath(path, field);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new MigrationException(field + " Must Be An Existing Directory: " + absolute, exception);
        }
        if (attributes.isSymbolicLink()) {
            throw new MigrationException(field + " Cannot Be A Symbolic Link");
        }
        if (!attributes.isDirectory()) {
            throw new MigrationException(field + " Must Be An Existing Directory: " + absolute);
        }
        if (absolute.getParent() != null) {
            requireNoSymlinkAncestors(absolute.getParent());
        }
        return absolute;
    }

    public static ValidatedRoot requireValidatedRoot(Path path, String field) throws IOException {
        return requireValidatedRoot(path, field, MigrationPaths::readAttributes);
    }

    static ValidatedRoot requireValidatedRoot(Path path, String field, PathAttributeReader reader) throws IOException {
        Path absolute = normalizePath(path, field);
        PathAttributeReader attributeReader = Objects.requireNonNull(reader, "reader");
        BasicFileAttributes before = requireAttributes(absolute, field, attributeReader);
        requireDirectoryAttributes(absolute, field, before);
        if (absolute.getParent() != null) {
            requireNoSymlinkAncestors(absolute.getParent(), attributeReader);
        }
        BasicFileAttributes after = requireAttributes(absolute, field, attributeReader);
        requireDirectoryAttributes(absolute, field, after);
        if (!sameRootIdentity(before, after)) {
            throw new MigrationException(field + " Changed During Validation: " + absolute);
        }
        return new ValidatedRoot(absolute, after, attributeReader);
    }

    public static Path requirePath(Path path, String field) {
        Path absolute = normalizePath(path, field);
        if (hasSymlinkAncestor(absolute)) {
            throw new IllegalArgumentException(field + " Cannot Be A Symbolic Link");
        }
        return absolute;
    }

    public static String requireRelative(String value) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Relative Path Is Invalid");
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Relative Path Must Not Be Absolute");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Relative Path Contains An Unsafe Segment");
            }
        }
        return value;
    }

    public static Path resolveInside(Path root, String relative) {
        Path normalizedRoot = requirePath(root, "root");
        String safeRelative = requireRelative(relative);
        Path resolved = normalizedRoot.resolve(safeRelative).normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw new IllegalArgumentException("Path Escapes Root");
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(resolved)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic Link Traversal Is Not Allowed");
            }
        }
        return resolved;
    }

    public static String relative(Path root, Path path) {
        Path normalizedRoot = requirePath(root, "root");
        Path normalizedPath = requirePath(path, "path");
        if (!normalizedPath.startsWith(normalizedRoot) || normalizedPath.equals(normalizedRoot)) {
            throw new IllegalArgumentException("Path Is Outside Root");
        }
        return requireRelative(normalizedRoot.relativize(normalizedPath).toString().replace(File.separatorChar, '/'));
    }

    public static void requireNoSymlinkTraversal(Path root, Path path) throws IOException {
        Path normalizedRoot = requireDirectory(root, "root");
        Path normalizedPath = normalizePath(path, "path");
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new MigrationException("Path Is Outside Root: " + normalizedPath);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)) {
                throw new MigrationException("Symbolic Link Traversal Is Not Allowed: " + current);
            }
        }
    }

    public static void requireNoSymlinkTree(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "root");
        Files.walkFileTree(normalizedRoot, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Path: " + file, exception);
            }
        });
    }

    public static void requireDistinctRoots(Path first, Path second) {
        Path normalizedFirst = requirePath(first, "firstRoot");
        Path normalizedSecond = requirePath(second, "secondRoot");
        if (normalizedFirst.equals(normalizedSecond) || normalizedFirst.startsWith(normalizedSecond) || normalizedSecond.startsWith(normalizedFirst)) {
            throw new IllegalArgumentException("Roots Must Be Distinct And Non-Nested");
        }
    }

    public static void requireWritableParent(Path path) throws IOException {
        Path absolute = requirePath(path, "path");
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new MigrationException("Path Has No Writable Parent: " + absolute);
        }
        Path existingParent = parent;
        BasicFileAttributes attributes = null;
        while (existingParent != null) {
            try {
                attributes = Files.readAttributes(existingParent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                break;
            } catch (NoSuchFileException exception) {
                existingParent = existingParent.getParent();
            }
        }
        if (existingParent == null || attributes == null) {
            throw new MigrationException("No Existing Parent For Path: " + absolute);
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new MigrationException("Writable Parent Is Invalid: " + existingParent);
        }
        requireNoSymlinkAncestors(existingParent);
        if (!Files.isWritable(existingParent)) {
            throw new MigrationException("Parent Is Not Writable: " + existingParent);
        }
    }

    private static void requireNoSymlinkAncestors(Path path) throws IOException {
        requireNoSymlinkAncestors(path, MigrationPaths::readAttributes);
    }

    private static void requireNoSymlinkAncestors(Path path, PathAttributeReader reader) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            BasicFileAttributes attributes = requireAttributes(current, "Path Ancestor", reader);
            if (attributes.isSymbolicLink()) {
                throw new MigrationException("Symbolic Link Ancestor Is Not Allowed: " + current);
            }
            if (!attributes.isDirectory()) {
                throw new MigrationException("Path Ancestor Is Not A Directory: " + current);
            }
        }
    }

    private static BasicFileAttributes requireAttributes(Path path, String field, PathAttributeReader reader)
        throws IOException {
        try {
            return reader.read(path);
        } catch (NoSuchFileException exception) {
            throw new MigrationException(field + " Does Not Exist: " + path, exception);
        }
    }

    private static void requireDirectoryAttributes(Path path, String field, BasicFileAttributes attributes)
        throws IOException {
        if (attributes.isSymbolicLink()) {
            throw new MigrationException(field + " Cannot Be A Symbolic Link: " + path);
        }
        if (!attributes.isDirectory()) {
            throw new MigrationException(field + " Must Be An Existing Directory: " + path);
        }
    }

    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return Objects.equals(first.fileKey(), second.fileKey())
            && first.size() == second.size()
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static boolean sameRootIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return first.fileKey() != null || second.fileKey() != null
            ? Objects.equals(first.fileKey(), second.fileKey())
            : first.creationTime().equals(second.creationTime());
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasSymlinkAncestor(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static Path normalizePath(Path path, String field) {
        if (path == null) {
            throw new IllegalArgumentException(field + " Is Required");
        }
        return path.toAbsolutePath().normalize();
    }

    @FunctionalInterface
    interface PathAttributeReader {
        BasicFileAttributes read(Path path) throws IOException;
    }

    public static final class ValidatedRoot {
        private final Path path;
        private final BasicFileAttributes identity;
        private final PathAttributeReader reader;

        private ValidatedRoot(Path path, BasicFileAttributes identity, PathAttributeReader reader) {
            this.path = path;
            this.identity = identity;
            this.reader = reader;
        }

        public Path path() {
            return path;
        }

        public Optional<BasicFileAttributes> inspectDirectRegularFile(Path file, String field) throws IOException {
            return inspectDirectRegularFiles(List.of(file), field).getFirst();
        }

        public List<Optional<BasicFileAttributes>> inspectDirectRegularFiles(List<Path> files, String field)
            throws IOException {
            Objects.requireNonNull(files, "files");
            List<Path> normalized = new ArrayList<>(files.size());
            for (Path file : files) {
                Path candidate = normalizePath(file, field);
                if (!path.equals(candidate.getParent())) {
                    throw new MigrationException(field + " Must Be A Direct Child Of Validated Root: " + candidate);
                }
                normalized.add(candidate);
            }
            requireRootIdentity();
            List<BasicFileAttributes> before = new ArrayList<>(normalized.size());
            for (Path file : normalized) {
                before.add(readOptional(file));
            }
            List<BasicFileAttributes> after = new ArrayList<>(normalized.size());
            for (Path file : normalized) {
                after.add(readOptional(file));
            }
            List<Optional<BasicFileAttributes>> attributes = new ArrayList<>(normalized.size());
            for (int index = 0; index < normalized.size(); index++) {
                attributes.add(requireStableDirectFile(normalized.get(index), field, before.get(index), after.get(index)));
            }
            requireRootIdentity();
            return List.copyOf(attributes);
        }

        private static Optional<BasicFileAttributes> requireStableDirectFile(Path file, String field,
                                                                             BasicFileAttributes before,
                                                                             BasicFileAttributes after)
            throws IOException {
            if (before == null || after == null) {
                if (before != after) {
                    throw new MigrationException(field + " Changed During Validation: " + file);
                }
                return Optional.empty();
            }
            requireRegularAttributes(file, field, before);
            requireRegularAttributes(file, field, after);
            if (!sameIdentity(before, after)) {
                throw new MigrationException(field + " Changed During Validation: " + file);
            }
            return Optional.of(after);
        }

        private BasicFileAttributes readOptional(Path file) throws IOException {
            try {
                return reader.read(file);
            } catch (NoSuchFileException exception) {
                return null;
            }
        }

        private void requireRootIdentity() throws IOException {
            BasicFileAttributes before = requireAttributes(path, "Validated Root", reader);
            requireDirectoryAttributes(path, "Validated Root", before);
            if (path.getParent() != null) {
                requireNoSymlinkAncestors(path.getParent(), reader);
            }
            BasicFileAttributes after = requireAttributes(path, "Validated Root", reader);
            requireDirectoryAttributes(path, "Validated Root", after);
            if (!sameRootIdentity(identity, before) || !sameRootIdentity(identity, after)
                || !sameRootIdentity(before, after)) {
                throw new MigrationException("Validated Root Changed During Descendant Validation: " + path);
            }
        }

        private static void requireRegularAttributes(Path path, String field, BasicFileAttributes attributes)
            throws IOException {
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new MigrationException(field + " Must Be A Regular Non-Symbolic-Link File: " + path);
            }
        }
    }

}
