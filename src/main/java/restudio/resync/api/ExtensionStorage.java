package restudio.resync.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public interface ExtensionStorage {
    <T> T read(StorageOperation<T> operation) throws IOException;

    <T> T write(StorageOperation<T> operation) throws IOException;

    @FunctionalInterface
    interface StorageOperation<T> {
        T apply(StorageFiles files) throws IOException;
    }

    interface StorageFiles {
        Path resolve(String path);
    }

    static StorageFiles files(Path root) {
        Path normalizedRoot = requireRoot(root);
        return path -> resolve(normalizedRoot, path);
    }

    private static Path requireRoot(Path root) {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Extension storage root cannot be a symbolic link");
        }
        return normalized;
    }

    private static Path resolve(Path root, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Extension storage path is required");
        }
        Path requested = Path.of(path);
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("Extension storage path must be relative");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Extension storage path escapes its owner directory");
        }
        Path current = root;
        for (Path part : root.relativize(resolved)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed in extension storage paths");
            }
        }
        return resolved;
    }
}
