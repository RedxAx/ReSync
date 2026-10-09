package restudio.resync.runtime;

import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceSnapshot;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceSnapshotValue;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceListener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

final class RuntimeResourceView<T> {
    private final ReSyncJsonResourceStorage storage;
    private final String type;
    private final Function<List<ResourceSnapshotValue>, T> project;
    private final AtomicLong changes = new AtomicLong();
    private final ResourceListener listener;
    private final AtomicReference<Resident<T>> resident = new AtomicReference<>();
    private volatile boolean subscribed;

    RuntimeResourceView(ReSyncJsonResourceStorage storage, String type, Function<List<ResourceSnapshotValue>, T> project) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.type = Objects.requireNonNull(type, "type");
        this.project = Objects.requireNonNull(project, "project");
        this.listener = (changedType, id, value, deleted) -> {
            if (type.equals(changedType)) {
                changes.incrementAndGet();
                resident.set(null);
            }
        };
    }

    T get() {
        subscribe();
        long generation = storage.snapshotGeneration();
        long sequence = storage.committedSequence();
        long change = changes.get();
        Resident<T> current = resident.get();
        if (current != null && current.generation() == generation && current.sequence() == sequence && current.change() == change) {
            if (subscribed && change == changes.get() && generation == storage.snapshotGeneration()
                && sequence == storage.committedSequence()) {
                return current.value();
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            subscribe();
            generation = storage.snapshotGeneration();
            change = changes.get();
            ResourceSnapshot snapshot = storage.readSnapshot(type);
            T value = Objects.requireNonNull(project.apply(snapshot.values()), "Runtime resource projection is required");
            if (generation != storage.snapshotGeneration() || change != changes.get()
                || snapshot.rootSequence() != storage.committedSequence() || !storage.isCurrent(snapshot)) {
                continue;
            }
            Resident<T> admitted = new Resident<>(generation, snapshot.rootSequence(), change, value);
            resident.set(admitted);
            if (subscribed && change == changes.get() && generation == storage.snapshotGeneration()
                && snapshot.rootSequence() == storage.committedSequence()) {
                return value;
            }
            resident.compareAndSet(admitted, null);
        }
        throw new IllegalStateException("Runtime resource authority changed during capture: " + type);
    }

    synchronized void reset() {
        subscribed = false;
        changes.incrementAndGet();
        resident.set(null);
        storage.removeListener(listener);
    }

    private void subscribe() {
        if (subscribed) {
            return;
        }
        synchronized (this) {
            if (!subscribed) {
                storage.addListener(listener);
                subscribed = true;
            }
        }
    }

    private record Resident<T>(long generation, long sequence, long change, T value) {
    }
}
