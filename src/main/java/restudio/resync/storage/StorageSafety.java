package restudio.resync.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            Files.writeString(temp, content == null ? "" : content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
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
        Files.deleteIfExists(target);
    }
}
