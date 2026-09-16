package restudio.resync.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
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
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        write(target, bytes, FileChannel::write);
    }

    public static void writeNew(Path target, byte[] bytes) throws IOException {
        writeNew(target, bytes, FileChannel::write);
    }

    public static void copy(Path source, Path target) throws IOException {
        Path normalizedSource = MigrationPaths.requirePath(source, "source");
        Path normalizedTarget = MigrationPaths.requirePath(target, "target");
        copy(normalizedSource, normalizedTarget, FileChannel::write, AtomicFiles::open,
            AtomicFiles::forceChannel);
    }

    public static void force(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "path");
        force(normalized, AtomicFiles::open, AtomicFiles::forceChannel);
    }

    static void copy(Path source, Path target, ChannelWriter writer, ChannelOpener opener,
                      ChannelForcer forcer) throws IOException {
        Path normalizedSource = MigrationPaths.requirePath(source, "source");
        Path normalizedTarget = MigrationPaths.requirePath(target, "target");
        if (Files.isSymbolicLink(normalizedSource)
            || !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Atomic Copy Source Must Be A Regular File: " + normalizedSource);
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(normalizedTarget.toString());
        }
        byte[] content = Files.readAllBytes(normalizedSource);
        write(normalizedTarget, content, writer, opener, forcer, AtomicFiles::forceDirectory,
            AtomicFiles::move, true, false, normalizedSource);
    }

    static void write(Path target, byte[] bytes, ChannelWriter writer) throws IOException {
        write(target, bytes, writer, AtomicFiles::open, AtomicFiles::forceChannel,
            AtomicFiles::forceDirectory, AtomicFiles::move, false, true, null);
    }

    static void writeNew(Path target, byte[] bytes, ChannelWriter writer) throws IOException {
        write(target, bytes, writer, AtomicFiles::open, AtomicFiles::forceChannel,
            AtomicFiles::forceDirectory, AtomicFiles::move, true, false, null);
    }

    static void write(Path target, byte[] bytes, ChannelWriter writer, ChannelOpener opener,
                      ChannelForcer forcer, DirectoryForcer directoryForcer) throws IOException {
        write(target, bytes, writer, opener, forcer, directoryForcer, AtomicFiles::move,
            false, true, null);
    }

    static void write(Path target, byte[] bytes, ChannelWriter writer, ChannelOpener opener,
                      ChannelForcer forcer, DirectoryForcer directoryForcer, PathMover mover)
        throws IOException {
        write(target, bytes, writer, opener, forcer, directoryForcer, mover, false, true, null);
    }

    private static void force(Path path, ChannelOpener opener, ChannelForcer forcer) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "path");
        try (FileChannel channel = opener.open(normalized, StandardOpenOption.WRITE)) {
            forcer.force(channel);
        }
    }

    private static void write(Path target, byte[] bytes, ChannelWriter writer, ChannelOpener opener,
                              ChannelForcer forcer, DirectoryForcer directoryForcer, PathMover mover,
                              boolean verify, boolean replaceExisting, Path attributeSource)
        throws IOException {
        Path normalized = MigrationPaths.requirePath(target, "target");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new MigrationException("Atomic Target Has No Parent");
        }
        byte[] content = Objects.requireNonNull(bytes, "bytes");
        ChannelWriter operation = Objects.requireNonNull(writer, "writer");
        ChannelOpener channelOpener = Objects.requireNonNull(opener, "opener");
        ChannelForcer channelForcer = Objects.requireNonNull(forcer, "forcer");
        DirectoryForcer parentForcer = Objects.requireNonNull(directoryForcer, "directoryForcer");
        PathMover pathMover = Objects.requireNonNull(mover, "mover");
        MigrationPaths.requireWritableParent(normalized);
        Files.createDirectories(parent);
        MigrationPaths.requireDirectory(parent, "atomicTargetParent");
        Path temporary = createTempFileNoFollow(parent);
        boolean published = false;
        boolean preserveTemporary = false;
        boolean publicationAttempted = false;
        try {
            try (FileChannel channel = channelOpener.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    int before = buffer.position();
                    int written = operation.write(channel, buffer);
                    int progress = buffer.position() - before;
                    if (written <= 0 || progress <= 0 || written != progress) {
                        throw new IOException("Atomic File Write Made No Progress: " + normalized);
                    }
                }
                channelForcer.force(channel);
            }
            if (verify) {
                verify(Files.readAllBytes(temporary), content, normalized);
            }
            if (replaceExisting) {
                publicationAttempted = true;
                try {
                    pathMover.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    preserveTemporary = true;
                    throw new MigrationException("Atomic File Replacement Is Not Supported: " + normalized,
                        exception);
                }
            } else {
                publicationAttempted = true;
                try {
                    Files.createLink(normalized, temporary);
                } catch (FileAlreadyExistsException exception) {
                    publicationAttempted = false;
                    throw exception;
                } catch (UnsupportedOperationException exception) {
                    preserveTemporary = true;
                    throw new MigrationException("Atomic File Creation Is Not Supported: " + normalized,
                        exception);
                }
            }
            published = true;
            parentForcer.force(parent);
            if (!replaceExisting) {
                Files.delete(temporary);
            }
            if (attributeSource != null) {
                copyAttributesAfterContent(attributeSource, normalized);
                parentForcer.force(parent);
            }
        } catch (IOException | RuntimeException exception) {
            if (!published && !preserveTemporary && !publicationAttempted) {
                cleanupTemporary(temporary, exception);
            }
            throw exception;
        }
    }

    @FunctionalInterface
    interface ChannelWriter {
        int write(FileChannel channel, ByteBuffer buffer) throws IOException;
    }

    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path path, OpenOption... options) throws IOException;
    }

    @FunctionalInterface
    interface ChannelForcer {
        void force(FileChannel channel) throws IOException;
    }

    @FunctionalInterface
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }

    @FunctionalInterface
    interface PathMover {
        Path move(Path source, Path target, StandardCopyOption... options) throws IOException;
    }

    private static FileChannel open(Path path, OpenOption... options) throws IOException {
        return FileChannel.open(path, options);
    }

    private static void forceChannel(FileChannel channel) throws IOException {
        channel.force(true);
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private static Path move(Path source, Path target, StandardCopyOption... options) throws IOException {
        return Files.move(source, target, options);
    }

    private static Path createTempFileNoFollow(Path parent) throws IOException {
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = parent.resolve(".resync-" + UUID.randomUUID() + ".tmp");
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Atomic Temporary File Is Not A Safe Regular File: " + candidate);
            }
            return candidate;
        }
        throw new MigrationException("Unable To Reserve An Atomic Temporary File In: " + parent);
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

    private static void cleanupTemporary(Path temporary, Throwable failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void verify(byte[] actual, byte[] expected, Path path) throws IOException {
        if (actual.length != expected.length || !Arrays.equals(sha256(actual), sha256(expected))) {
            throw new IOException("Atomic File Content Verification Failed: " + path);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
