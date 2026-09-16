package restudio.resync.migration;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

public final class MigrationFence {
    private static final MigrationFence SYSTEM_WIDE = new MigrationFence();

    private static final StampedLock LOCK = new StampedLock();
    private static final ReentrantLock ADMISSION = new ReentrantLock(true);
    private static final Condition MUTATION_TURN = ADMISSION.newCondition();
    private static final ConcurrentHashMap<Thread, MutationState> MUTATIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_MUTATIONS = new AtomicInteger();
    private static final AtomicReference<Thread> MIGRATION_OWNER = new AtomicReference<>();
    private static final AtomicReference<MigrationState> MIGRATION = new AtomicReference<>();
    private static int MIGRATION_WAITERS;
    private boolean mutationAdmissionClosed;

    public static MigrationFence systemWide() {
        return SYSTEM_WIDE;
    }

    public MutationLease beginMutation() {
        Thread owner = Thread.currentThread();
        for (;;) {
            requireMutationAdmission();
            rejectMutationDuringMigration(owner);
            MutationState nested = retainExistingMutation(owner);
            if (nested != null) {
                return new MutationLease(nested);
            }
            awaitMutationTurn();
            long stamp = LOCK.readLock();
            boolean accepted = false;
            try {
                synchronized (this) {
                    if (mutationAdmissionClosed) {
                        throw new IllegalStateException("Persistence Mutations Are Closed");
                    }
                    ADMISSION.lock();
                    try {
                        if (migrationWaiters()) {
                            continue;
                        }
                        MutationState state = new MutationState(owner, stamp);
                        MutationState previous = MUTATIONS.putIfAbsent(owner, state);
                        if (previous != null) {
                            if (!previous.retain()) {
                                MUTATIONS.remove(owner, previous);
                                continue;
                            }
                            ACTIVE_MUTATIONS.incrementAndGet();
                            return new MutationLease(previous);
                        }
                        ACTIVE_MUTATIONS.incrementAndGet();
                        accepted = true;
                        return new MutationLease(state);
                    } finally {
                        ADMISSION.unlock();
                    }
                }
            } finally {
                if (!accepted) {
                    LOCK.unlockRead(stamp);
                }
            }
        }
    }

    public Optional<MutationLease> tryBeginMutation(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Mutation Timeout Must Be Non-Negative");
        }
        Thread owner = Thread.currentThread();
        if (mutationAdmissionClosed() || MIGRATION_OWNER.get() == owner) {
            return Optional.empty();
        }
        MutationState nested = retainExistingMutation(owner);
        if (nested != null) {
            if (mutationAdmissionClosed()) {
                releaseMutation(nested);
                return Optional.empty();
            }
            return Optional.of(new MutationLease(nested));
        }
        long timeoutNanos = timeout.toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        boolean firstAttempt = true;
        while (true) {
            boolean initialAttempt = firstAttempt;
            long remaining = initialAttempt ? timeoutNanos : remainingUntil(deadline);
            firstAttempt = false;
            if (remaining < 0L) {
                return Optional.empty();
            }
            if (!awaitMutationTurn(remaining)) {
                return Optional.empty();
            }
            remaining = remainingUntil(deadline);
            if (remaining < 0L && (timeoutNanos > 0L || !initialAttempt)) {
                return Optional.empty();
            }
            remaining = Math.max(0L, remaining);
            if (mutationAdmissionClosed() || MIGRATION_OWNER.get() == owner) {
                return Optional.empty();
            }
            long stamp = LOCK.tryReadLock(remaining, TimeUnit.NANOSECONDS);
            if (stamp == 0L) {
                return Optional.empty();
            }
            boolean accepted = false;
            try {
                ADMISSION.lockInterruptibly();
                try {
                    if (mutationAdmissionClosed || migrationWaiters()) {
                        continue;
                    }
                    MutationState state = new MutationState(owner, stamp);
                    MutationState previous = MUTATIONS.putIfAbsent(owner, state);
                    if (previous != null) {
                        if (!previous.retain()) {
                            MUTATIONS.remove(owner, previous);
                            continue;
                        }
                        ACTIVE_MUTATIONS.incrementAndGet();
                        return Optional.of(new MutationLease(previous));
                    }
                    ACTIVE_MUTATIONS.incrementAndGet();
                    accepted = true;
                    return Optional.of(new MutationLease(state));
                } finally {
                    ADMISSION.unlock();
                }
            } finally {
                if (!accepted) {
                    LOCK.unlockRead(stamp);
                }
            }
        }
    }

    public synchronized void closeMutations() {
        mutationAdmissionClosed = true;
    }

    public synchronized boolean mutationAdmissionClosed() {
        return mutationAdmissionClosed;
    }

    public MigrationLease acquireMigration() {
        Thread owner = Thread.currentThread();
        rejectMigrationDuringMutation(owner);
        MigrationState nested = retainExistingMigration(owner);
        if (nested != null) {
            return new MigrationLease(nested);
        }
        registerMigrationWaiter();
        long stamp;
        try {
            stamp = LOCK.writeLock();
        } catch (RuntimeException | Error failure) {
            unregisterMigrationWaiter();
            throw failure;
        }
        MigrationState migration = completeMigrationAcquisition(owner, stamp);
        return new MigrationLease(migration);
    }

    public Optional<MigrationLease> tryAcquireMigration(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Migration Timeout Must Be Non-Negative");
        }
        Thread owner = Thread.currentThread();
        if (mutationDepth(owner) > 0) {
            return Optional.empty();
        }
        MigrationState nested = retainExistingMigration(owner);
        if (nested != null) {
            return Optional.of(new MigrationLease(nested));
        }
        registerMigrationWaiter();
        boolean acquired = false;
        try {
            long stamp = LOCK.tryWriteLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (stamp == 0L) {
                return Optional.empty();
            }
            acquired = true;
            MigrationState migration = completeMigrationAcquisition(owner, stamp);
            return Optional.of(new MigrationLease(migration));
        } finally {
            if (!acquired) {
                unregisterMigrationWaiter();
            }
        }
    }

    public boolean migrationActive() {
        return LOCK.isWriteLocked();
    }

    public int activeMutationCount() {
        return ACTIVE_MUTATIONS.get();
    }

    private MutationState retainExistingMutation(Thread owner) {
        synchronized (this) {
            if (mutationAdmissionClosed) {
                return null;
            }
            MutationState state = MUTATIONS.get(owner);
            if (state == null) {
                return null;
            }
            if (!state.retain()) {
                MUTATIONS.remove(owner, state);
                return null;
            }
            ACTIVE_MUTATIONS.incrementAndGet();
            return state;
        }
    }

    private static void releaseMutation(MutationState state) {
        boolean last;
        synchronized (state) {
            if (state.released) {
                return;
            }
            state.leases--;
            ACTIVE_MUTATIONS.decrementAndGet();
            last = state.leases == 0;
            if (last) {
                state.released = true;
                MUTATIONS.remove(state.owner, state);
            }
        }
        if (last) {
            LOCK.unlockRead(state.stamp);
        }
    }

    private static int mutationDepth(Thread owner) {
        MutationState state = MUTATIONS.get(owner);
        return state == null ? 0 : state.leaseCount();
    }

    private static void rejectMutationDuringMigration(Thread owner) {
        if (MIGRATION_OWNER.get() == owner) {
            throw new IllegalStateException("Mutations Are Not Allowed During Migration");
        }
    }

    private static void rejectMigrationDuringMutation(Thread owner) {
        if (mutationDepth(owner) > 0) {
            throw new IllegalStateException("A Mutation Cannot Acquire The Migration Fence");
        }
    }

    private static MigrationState retainExistingMigration(Thread owner) {
        MigrationState state = MIGRATION.get();
        if (state == null || state.owner != owner || !state.retain()) {
            return null;
        }
        return state;
    }

    private static void registerMigrationWaiter() {
        ADMISSION.lock();
        try {
            MIGRATION_WAITERS++;
        } finally {
            ADMISSION.unlock();
        }
    }

    private static void unregisterMigrationWaiter() {
        ADMISSION.lock();
        try {
            MIGRATION_WAITERS--;
            MUTATION_TURN.signalAll();
        } finally {
            ADMISSION.unlock();
        }
    }

    private static MigrationState completeMigrationAcquisition(Thread owner, long stamp) {
        MigrationState state = new MigrationState(owner, stamp);
        ADMISSION.lock();
        try {
            MIGRATION_WAITERS--;
            MIGRATION.set(state);
            MIGRATION_OWNER.set(owner);
            MUTATION_TURN.signalAll();
        } finally {
            ADMISSION.unlock();
        }
        return state;
    }

    private static boolean migrationWaiters() {
        return MIGRATION_WAITERS > 0;
    }

    private static void awaitMutationTurn() {
        ADMISSION.lock();
        try {
            while (migrationWaiters()) {
                MUTATION_TURN.awaitUninterruptibly();
            }
        } finally {
            ADMISSION.unlock();
        }
    }

    private static boolean awaitMutationTurn(long timeoutNanos) throws InterruptedException {
        ADMISSION.lockInterruptibly();
        try {
            long remaining = timeoutNanos;
            while (migrationWaiters()) {
                if (remaining <= 0L) {
                    return false;
                }
                remaining = MUTATION_TURN.awaitNanos(remaining);
            }
            return true;
        } finally {
            ADMISSION.unlock();
        }
    }

    private static long remainingUntil(long deadline) {
        return deadline - System.nanoTime();
    }

    private void requireMutationAdmission() {
        if (mutationAdmissionClosed()) {
            throw new IllegalStateException("Persistence Mutations Are Closed");
        }
    }

    private static final class MutationState {
        private final Thread owner;
        private final long stamp;
        private int leases = 1;
        private boolean released;

        private MutationState(Thread owner, long stamp) {
            this.owner = owner;
            this.stamp = stamp;
        }

        private synchronized boolean retain() {
            if (released) {
                return false;
            }
            leases++;
            return true;
        }

        private synchronized int leaseCount() {
            return leases;
        }
    }

    private static final class MigrationState {
        private final Thread owner;
        private final long stamp;
        private int leases = 1;
        private boolean released;

        private MigrationState(Thread owner, long stamp) {
            this.owner = owner;
            this.stamp = stamp;
        }

        private synchronized boolean retain() {
            if (released) {
                return false;
            }
            leases++;
            return true;
        }

        private synchronized boolean release() {
            if (released) {
                return false;
            }
            leases--;
            if (leases == 0) {
                released = true;
                return true;
            }
            return false;
        }
    }

    public final class MutationLease implements AutoCloseable {
        private final MutationState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MutationLease(MutationState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseMutation(state);
            }
        }
    }

    public final class MigrationLease implements AutoCloseable {
        private final MigrationState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MigrationLease(MigrationState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.get()) {
                return;
            }
            if (Thread.currentThread() != state.owner) {
                throw new IllegalStateException("Migration Lease Must Be Closed By Its Owner");
            }
            if (closed.compareAndSet(false, true)) {
                if (!state.release()) {
                    return;
                }
                ADMISSION.lock();
                try {
                    MIGRATION.compareAndSet(state, null);
                    MIGRATION_OWNER.compareAndSet(state.owner, null);
                } finally {
                    ADMISSION.unlock();
                }
                LOCK.unlockWrite(state.stamp);
                ADMISSION.lock();
                try {
                    MUTATION_TURN.signalAll();
                } finally {
                    ADMISSION.unlock();
                }
            }
        }
    }
}
