package restudio.resync.filesystem.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class WindowsFileMutation {
    private final Backend backend;
    private final WindowsFileIdentity identity;

    public WindowsFileMutation() {
        this(new WindowsMutationNative());
    }

    public WindowsFileMutation(Backend backend) {
        this(new WindowsFileIdentity(backend), backend);
    }

    public WindowsFileMutation(WindowsFileIdentity identity, Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public static WindowsFileMutation system() {
        return new WindowsFileMutation();
    }

    public boolean available() {
        return backend.available();
    }

    public boolean available(Path root) {
        requirePath(root);
        return available();
    }

    public String unavailableReason(Path root) {
        Path normalized = requirePath(root);
        return backend.unavailableReason(normalized);
    }

    public WindowsFileIdentity.Observation observe(Path path) throws IOException {
        return identity.observe(requirePath(path));
    }

    public void rename(Path source, Path destination) throws IOException {
        Path normalizedSource = requirePath(source);
        Path normalizedDestination = requirePath(destination);
        if (normalizedSource.equals(normalizedDestination)) {
            throw new IOException("Windows rename source and destination are the same: " + normalizedSource);
        }
        backend.rename(normalizedSource, normalizedDestination);
    }

    public void renameNoReplace(Path source, Path destination) throws IOException {
        rename(source, destination);
    }

    public void deleteFile(Path path) throws IOException {
        backend.deleteFile(requirePath(path));
    }

    public void deleteTree(Path path) throws IOException {
        backend.deleteTree(requirePath(path));
    }

    public void flush(Path path) throws IOException {
        backend.flush(requirePath(path));
    }

    public void flush(Path path, boolean parent) throws IOException {
        Path normalized = requirePath(path);
        if (parent) {
            backend.flushParent(normalized);
        } else {
            backend.flush(normalized);
        }
    }

    public void flushParent(Path path) throws IOException {
        backend.flushParent(requirePath(path));
    }

    public void force(Path path) throws IOException {
        flush(path);
    }

    private static Path requirePath(Path path) {
        Objects.requireNonNull(path, "path");
        return path.toAbsolutePath().normalize();
    }

    public interface Backend extends WindowsFileIdentity.Backend {
        boolean available();

        default String unavailableReason(Path root) {
            if (available()) {
                return "";
            }
            return isWindows()
                ? "Windows File Mutation Is Unavailable"
                : "Windows File Mutation Is Unsupported On This Operating System";
        }

        void rename(Path source, Path destination) throws IOException;

        void deleteFile(Path path) throws IOException;

        void deleteTree(Path path) throws IOException;

        default void flushParent(Path path) throws IOException {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                throw new IOException("Windows file path has no stable parent: " + path);
            }
            flush(parent);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }
}
