package restudio.resync.flow.jobs;

import restudio.flow.data.FlowJobReference;
import restudio.resync.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class FlowJobRegistry {
    private static final int DEFAULT_MAX_RETAINED = 1024;
    private static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofMinutes(15);
    private final Map<String, FlowJobReference<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, Instant> terminalTimes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<FlowJobReference.Snapshot<?>>> listeners = new CopyOnWriteArrayList<>();
    private final int maxRetained;
    private final Duration terminalRetention;

    public FlowJobRegistry() {
        this(DEFAULT_MAX_RETAINED, DEFAULT_TERMINAL_RETENTION);
    }

    public FlowJobRegistry(int maxRetained, Duration terminalRetention) {
        this.maxRetained = Math.clamp(maxRetained, 16, 65_536);
        this.terminalRetention = terminalRetention != null && !terminalRetention.isNegative() ? terminalRetention : DEFAULT_TERMINAL_RETENTION;
    }

    public <T> FlowJobReference<T> create(String kind, String owner) {
        prune();
        FlowJobReference<T> reference = new FlowJobReference<>(UUID.randomUUID().toString(), kind, owner);
        jobs.put(reference.getId(), reference);
        reference.getCompletion().whenComplete((outcome, failure) -> {
            terminalTimes.putIfAbsent(reference.getId(), Instant.now());
            publish(reference);
        });
        publish(reference);
        prune();
        return reference;
    }

    public FlowJobReference<?> get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    public List<FlowJobReference.Snapshot<?>> snapshots(String owner) {
        prune();
        List<FlowJobReference.Snapshot<?>> snapshots = new ArrayList<>();
        for (FlowJobReference<?> reference : jobs.values()) {
            if (owner == null || owner.isBlank() || owner.equals(reference.getOwner())) {
                snapshots.add(reference.snapshot());
            }
        }
        snapshots.sort(Comparator.comparing(FlowJobReference.Snapshot<?>::createdAt).reversed());
        return List.copyOf(snapshots);
    }

    public void start(FlowJobReference<?> reference) {
        if (reference == null) {
            return;
        }
        reference.start();
        publish(reference);
    }

    public void update(FlowJobReference<?> reference, double progress, Map<String, Object> metadata) {
        if (reference == null) {
            return;
        }
        reference.updateProgress(progress, metadata);
        publish(reference);
    }

    public <T> boolean succeed(FlowJobReference<T> reference, T value) {
        return reference != null && reference.succeed(value);
    }

    public boolean fail(FlowJobReference<?> reference, String code, String message, Map<String, Object> details) {
        return reference != null && reference.fail(code, message, details);
    }

    public boolean cancel(FlowJobReference<?> reference) {
        return reference != null && reference.cancel();
    }

    public boolean cancel(String jobId) {
        return cancel(get(jobId));
    }

    public void addListener(Consumer<FlowJobReference.Snapshot<?>> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Consumer<FlowJobReference.Snapshot<?>> listener) {
        listeners.remove(listener);
    }

    public void shutdown() {
        for (FlowJobReference<?> reference : jobs.values()) {
            if (reference.getState() == FlowJobReference.State.PENDING || reference.getState() == FlowJobReference.State.RUNNING) {
                reference.cancel();
            }
        }
        jobs.clear();
        terminalTimes.clear();
        listeners.clear();
    }

    private void publish(FlowJobReference<?> reference) {
        if (reference == null) {
            return;
        }
        FlowJobReference.Snapshot<?> snapshot = reference.snapshot();
        for (Consumer<FlowJobReference.Snapshot<?>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException failure) {
                Log.warn("Flow job listener failed: " + failure.getMessage());
            }
        }
    }

    private void prune() {
        Instant cutoff = Instant.now().minus(terminalRetention);
        jobs.values().removeIf(reference -> {
            Instant terminalTime = terminalTimes.get(reference.getId());
            boolean expired = terminal(reference.getState()) && terminalTime != null && !terminalTime.isAfter(cutoff);
            if (expired) {
                terminalTimes.remove(reference.getId());
            }
            return expired;
        });
        int overflow = jobs.size() - maxRetained;
        if (overflow <= 0) {
            return;
        }
        List<FlowJobReference<?>> removable = jobs.values().stream()
            .filter(reference -> terminal(reference.getState()))
            .sorted(Comparator.comparing((FlowJobReference<?> reference) -> reference.getCreatedAt()))
            .limit(overflow)
            .toList();
        for (FlowJobReference<?> reference : removable) {
            jobs.remove(reference.getId(), reference);
            terminalTimes.remove(reference.getId());
        }
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED || state == FlowJobReference.State.CANCELLED;
    }
}
