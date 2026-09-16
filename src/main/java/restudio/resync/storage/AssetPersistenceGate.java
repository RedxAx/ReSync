package restudio.resync.storage;

import restudio.resync.migration.MigrationPaths;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class AssetPersistenceGate {
    private final ReentrantReadWriteLock fence = new ReentrantReadWriteLock(true);
    private volatile State state = State.OPEN;
    private volatile Path scopeRoot;

    public AssetPersistenceGate(Path scopeRoot) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    public void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("Shared asset persistence is QUIESCED; mutation rejected");
        }
    }

    public MutationLease acquire() {
        fence.readLock().lock();
        if (!isOpen()) {
            fence.readLock().unlock();
            throw new IllegalStateException("Shared asset persistence is QUIESCED; mutation rejected");
        }
        return () -> fence.readLock().unlock();
    }

    public void quiesce() {
        fence.writeLock().lock();
        try {
            state = State.QUIESCED;
        } finally {
            fence.writeLock().unlock();
        }
    }

    public void resume() {
        fence.writeLock().lock();
        try {
            state = State.OPEN;
        } finally {
            fence.writeLock().unlock();
        }
    }

    public Path scopeRoot() {
        return scopeRoot;
    }

    public void rebind(Path scopeRoot) {
        Objects.requireNonNull(scopeRoot, "scopeRoot");
        fence.writeLock().lock();
        try {
            if (state != State.QUIESCED) {
                throw new IllegalStateException("Shared asset persistence must be quiesced before rebind");
            }
            this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        } finally {
            fence.writeLock().unlock();
        }
    }

    public enum State {
        OPEN,
        QUIESCED
    }

    @FunctionalInterface
    public interface MutationLease extends AutoCloseable {
        @Override
        void close();
    }
}
