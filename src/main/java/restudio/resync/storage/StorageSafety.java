package restudio.resync.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

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
        Files.createDirectories(directory);
        Path root = directory.toAbsolutePath().normalize().toRealPath();
        Path target = root.resolve(safeId + ".json").normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IllegalArgumentException("Unsafe path for id: " + id);
        }
        return target;
    }

    public static String readUtf8(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static void writeUtf8Atomic(Path file, String content) throws IOException {
        writeBytesAtomic(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytesAtomic(Path file, byte[] content) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("File has no parent: " + file);
        }
        Files.createDirectories(parent);
        Path root = parent.toAbsolutePath().normalize().toRealPath();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IOException("Unsafe write target: " + file);
        }
        Path temp = Files.createTempFile(root, target.getFileName().toString(), ".tmp");
        try {
            byte[] bytes = content != null ? content : new byte[0];
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(root);
            if (!sha256(bytes).equals(sha256(Files.readAllBytes(target)))) {
                throw new IOException("Write verification failed: " + file);
            }
        } finally {
            Files.deleteIfExists(temp);
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
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("File has no parent: " + file);
        }
        Files.createDirectories(parent);
        Path root = parent.toAbsolutePath().normalize().toRealPath();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root)) {
            throw new IOException("Unsafe delete target: " + file);
        }
        if (Files.deleteIfExists(target)) {
            forceDirectory(root);
        }
    }
}
