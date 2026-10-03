package restudio.resync.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import restudio.resync.migration.MigrationPaths;

public final class StorageSafety {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,96}");

    private StorageSafety() {
    }

    public static String validateId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("Id is required");
        }
        String trimmed = id.trim();
        if (trimmed.isBlank() || !SAFE_ID.matcher(trimmed).matches() || trimmed.equals(".") || trimmed.equals("..") || trimmed.contains("..") || trimmed.endsWith(".json")) {
            throw new IllegalArgumentException("Unsafe id: " + id);
        }
        try {
            Path path = Path.of(trimmed);
            if (path.isAbsolute() || path.getNameCount() != 1) {
                throw new IllegalArgumentException("Unsafe id: " + id);
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Unsafe id: " + id, exception);
        }
        return trimmed;
    }

    public static Path jsonFile(Path directory, String id) throws IOException {
        String safeId = validateId(id);
        Path root = ensureDirectory(directory, "jsonDirectory");
        Path target = root.resolve(safeId + ".json").normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IllegalArgumentException("Unsafe path for id: " + id);
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("JSON File Cannot Be A Symbolic Link: " + target);
        }
        return target;
    }

    public static String readUtf8(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static void writeUtf8Atomic(Path file, String content) throws IOException {
        writeBytesAtomic(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeUtf8AtomicStrict(Path file, String content) throws IOException {
        writeBytesAtomicStrict(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytesAtomic(Path file, byte[] content) throws IOException {
        writeBytesAtomic(file, content, false);
    }

    public static void writeBytesAtomicStrict(Path file, byte[] content) throws IOException {
        writeBytesAtomic(file, content, true);
    }

    public static int recoverAtomicWrites(Path directory) throws IOException {
        return RecoverableJsonStore.recoverAtomicWrites(directory);
    }

    public static void validateAtomicWriteRecovery(Path directory) throws IOException {
        RecoverableJsonStore.validateAtomicWriteRecovery(directory);
    }

    public static void copyIfAbsentAtomic(Path source, Path target) throws IOException {
        Path normalizedSource = requireSafePath(source, "source");
        Path normalizedTarget = requireSafePath(target, "target");
        Path sourceParent = normalizedSource.getParent();
        if (sourceParent == null) {
            throw new IOException("Source Has No Parent: " + normalizedSource);
        }
        ensureDirectory(sourceParent, "sourceParent");
        if (Files.isSymbolicLink(normalizedSource)
            || !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source Is Not A Safe Regular File: " + normalizedSource);
        }
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Target Has No Parent: " + normalizedTarget);
        }
        Path safeParent = ensureDirectory(parent, "targetParent");
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalizedTarget)
                || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Target Is Not A Safe Regular File: " + normalizedTarget);
            }
            return;
        }
        Path temp = createTempFileNoFollow(safeParent, ".backup.tmp");
        boolean published = false;
        boolean publicationAttempted = false;
        try {
            ensureDirectory(safeParent, "targetParent");
            if (Files.isSymbolicLink(temp) || !Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Backup Temporary File Is Not A Safe Regular File: " + temp);
            }
            Files.copy(normalizedSource, temp, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            DosFileAttributeView temporaryDos = Files.getFileAttributeView(temp, DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
            if (temporaryDos != null && temporaryDos.readAttributes().isReadOnly()) {
                temporaryDos.setReadOnly(false);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            if (Files.isSymbolicLink(temp) || !Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Backup Temporary File Is Not A Safe Regular File: " + temp);
            }
            ensureDirectory(safeParent, "targetParent");
            publicationAttempted = true;
            try {
                Files.createLink(normalizedTarget, temp);
                published = true;
            } catch (FileAlreadyExistsException alreadyPresent) {
                publicationAttempted = false;
                Files.deleteIfExists(temp);
                return;
            } catch (UnsupportedOperationException unsupportedLink) {
                try {
                    Files.move(temp, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
                    published = true;
                } catch (FileAlreadyExistsException alreadyPresent) {
                    publicationAttempted = false;
                    Files.deleteIfExists(temp);
                    return;
                } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                    throw new IOException("Atomic Backup Publication Is Not Supported: " + normalizedTarget,
                        atomicUnsupported);
                }
            }
            if (published) {
                forceDirectory(safeParent);
                Files.deleteIfExists(temp);
                copyAttributesAfterContent(normalizedSource, normalizedTarget);
                forceDirectory(safeParent);
            }
            ensureDirectory(safeParent, "targetParent");
            if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Target Is Not A Regular File: " + normalizedTarget);
            }
        } catch (IOException | RuntimeException exception) {
            if (!published && !publicationAttempted) {
                cleanupTemp(temp, exception);
            }
            throw exception;
        }
    }

    public static void createDirectoriesNoSymlinks(Path root, Path directory) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "root");
        Path normalizedDirectory = MigrationPaths.requirePath(directory, "directory");
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IOException("Directory Escapes Root: " + normalizedDirectory);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(part).normalize();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Directory Is Not A Safe Non-Symbolic-Link Directory: " + current);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                }
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Directory Is Not A Safe Non-Symbolic-Link Directory: " + current);
                }
            }
            MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, current);
        }
    }

    private static void writeBytesAtomic(Path file, byte[] content, boolean strict) throws IOException {
        Path target = requireSafePath(file, "file");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("File has no parent: " + file);
        }
        Path root = ensureDirectory(parent, "writeParent");
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IOException("Unsafe write target: " + file);
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Unsafe write target: " + file);
        }
        Path temp = createTempFileNoFollow(root, ".tmp");
        boolean published = false;
        boolean preserveTemporary = false;
        try {
            byte[] bytes = content != null ? content : new byte[0];
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                published = true;
            } catch (AtomicMoveNotSupportedException atomicException) {
                if (strict) {
                    preserveTemporary = true;
                    throw atomicException;
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                published = true;
            }
            forceDirectory(root);
            if (!sha256(bytes).equals(sha256(Files.readAllBytes(target)))) {
                throw new IOException("Write verification failed: " + file);
            }
        } catch (IOException | RuntimeException exception) {
            if (!published && !preserveTemporary) {
                cleanupTemp(temp, exception);
            }
            throw exception;
        }
    }

    private static Path requireSafePath(Path path, String field) throws IOException {
        try {
            return MigrationPaths.requirePath(path, field);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private static Path ensureDirectory(Path directory, String field) throws IOException {
        Path normalized = requireSafePath(directory, field);
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = existing.getParent();
            if (parent == null) {
                throw new IOException(field + " Has No Existing Parent: " + normalized);
            }
            existing = parent;
        }
        MigrationPaths.requireDirectory(existing, field + "ExistingParent");
        Path current = existing;
        for (Path part : existing.relativize(normalized)) {
            current = current.resolve(part).normalize();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(field + " Is Not A Safe Directory: " + current);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                }
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(field + " Is Not A Safe Directory: " + current);
                }
            }
        }
        MigrationPaths.requireNoSymlinkTraversal(existing, normalized);
        return normalized;
    }

    private static Path createTempFileNoFollow(Path parent, String suffix) throws IOException {
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = parent.resolve(".resync-" + UUID.randomUUID() + suffix);
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Temporary File Is Not A Safe Regular File: " + candidate);
            }
            return candidate;
        }
        throw new IOException("Unable To Reserve A Unique Temporary File In: " + parent);
    }

    private static void copyAttributesAfterContent(Path source, Path target) throws IOException {
        BasicFileAttributeView sourceBasic = Files.getFileAttributeView(source, BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributeView targetBasic = Files.getFileAttributeView(target, BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (sourceBasic != null && targetBasic != null) {
            BasicFileAttributes attributes = sourceBasic.readAttributes();
            targetBasic.setTimes(attributes.lastModifiedTime(), attributes.lastAccessTime(),
                attributes.creationTime());
        }

        DosFileAttributeView sourceDos = Files.getFileAttributeView(source, DosFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        DosFileAttributeView targetDos = Files.getFileAttributeView(target, DosFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (sourceDos != null && targetDos != null) {
            DosFileAttributes attributes = sourceDos.readAttributes();
            targetDos.setArchive(attributes.isArchive());
            targetDos.setHidden(attributes.isHidden());
            targetDos.setReadOnly(attributes.isReadOnly());
            targetDos.setSystem(attributes.isSystem());
        }

        PosixFileAttributeView sourcePosix = Files.getFileAttributeView(source, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView targetPosix = Files.getFileAttributeView(target, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (sourcePosix != null && targetPosix != null) {
            PosixFileAttributes attributes = sourcePosix.readAttributes();
            targetPosix.setPermissions(attributes.permissions());
        }
    }

    private static void cleanupTemp(Path temp, Throwable failure) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public static String sha256(String content) {
        return sha256((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content != null ? content : new byte[0]));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    public static void deleteIfExists(Path file) throws IOException {
        Path target = requireSafePath(file, "file");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("File has no parent: " + file);
        }
        Path root = ensureDirectory(parent, "deleteParent");
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IOException("Unsafe delete target: " + file);
        }
        if (Files.deleteIfExists(target)) {
            forceDirectory(root);
        }
    }
}
