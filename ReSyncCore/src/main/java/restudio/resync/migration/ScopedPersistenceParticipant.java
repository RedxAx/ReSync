
package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class ScopedPersistenceParticipant implements RebindablePersistenceParticipant {
    private final String owner;
    private final Path initialRoot;
    private final Path scopeRoot;
    private final Path relativeRoot;
    private final Lifecycle lifecycle;
    private volatile Path activeRoot;

    public ScopedPersistenceParticipant(String owner, Path scopeRoot, Path root, Lifecycle lifecycle) {
        this.owner = requireText(owner, "owner");
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.initialRoot = MigrationPaths.requirePath(root, "root");
        if (!initialRoot.startsWith(this.scopeRoot) || initialRoot.equals(this.scopeRoot)) {
            throw new IllegalArgumentException("Participant Root Must Be A Child Of Scope Root: " + owner);
        }
        this.relativeRoot = this.scopeRoot.relativize(this.initialRoot);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.activeRoot = this.initialRoot;
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public Path root() {
        return activeRoot;
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        requireDirectory(activeRoot, "active participant root");
        lifecycle.flush(activeRoot);
    }

    @Override
    public void quiesce() throws IOException {
        requireDirectory(activeRoot, "active participant root");
        lifecycle.quiesce(activeRoot);
    }

    @Override
    public void resume() throws IOException {
        requireDirectory(activeRoot, "active participant root");
        lifecycle.resume(activeRoot);
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        Path normalizedScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path reboundRoot = normalizedScope.resolve(relativeRoot).toAbsolutePath().normalize();
        if (!reboundRoot.startsWith(normalizedScope) || reboundRoot.equals(normalizedScope)) {
            throw new MigrationException("Persistence Participant Rebind Escaped Scope: " + owner);
        }
        requireDirectory(reboundRoot, "rebound participant root");
        lifecycle.rebind(this.activeRoot, reboundRoot);
        this.activeRoot = reboundRoot;
    }

    @Override
    public void healthCheck() throws IOException {
        requireDirectory(activeRoot, "active participant root");
        lifecycle.healthCheck(activeRoot);
    }

    public Path expectedRoot(Path candidateScope) throws IOException {
        Path normalizedScope = MigrationPaths.requireDirectory(candidateScope, "candidateScope");
        Path expected = normalizedScope.resolve(relativeRoot).toAbsolutePath().normalize();
        if (!expected.startsWith(normalizedScope) || expected.equals(normalizedScope)) {
            throw new MigrationException("Persistence Participant Scope Is Invalid: " + owner);
        }
        requireDirectory(expected, "expected participant root");
        return expected;
    }

    public boolean owns(Path candidateScope, Path file) throws IOException {
        Path expected = expectedRoot(candidateScope);
        Path normalizedFile = MigrationPaths.requirePath(file, "file");
        return normalizedFile.startsWith(expected) && !normalizedFile.equals(expected);
    }

    private static void requireDirectory(Path path, String name) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, name);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(name + " Must Be An Existing Non-Symbolic-Link Directory");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }

    public interface Lifecycle {
        void flush(Path root) throws IOException;

        void quiesce(Path root) throws IOException;

        void resume(Path root) throws IOException;

        void rebind(Path previousRoot, Path nextRoot) throws IOException;

        void healthCheck(Path root) throws IOException;
    }
}


