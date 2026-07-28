package restudio.resync.flow.automation;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.flow.automation.event.ScheduledTaskEvent;
import restudio.resync.flow.automation.event.TimerEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public final class AutomationTaskService {
    private static final Object WAIT_FOR_OWNER = new Object();
    public enum Kind {
        TIMER,
        SCHEDULE
    }

    public enum State {
        ACTIVE,
        PAUSED,
        INACTIVE,
        FINISHED,
        FAILED,
        CANCELLED
    }

    public record StartResult(boolean started, boolean keptExisting, TaskSnapshot task) {
    }

    public record TaskSnapshot(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                               Object owner, boolean persistent, State state, long generation, long createdAt,
                               long nextRun, long lastRun, long runCount, long duration, long remaining, long elapsed,
                               double progress, Object lastResult, String lastError) {
        public Map<String, Object> value() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("taskId", taskId);
            value.put("kind", kind.name().toLowerCase(Locale.ROOT));
            value.put("definitionId", definitionId);
            value.put("scope", scope.name().toLowerCase(Locale.ROOT));
            value.put("ownerId", ownerId);
            value.put("persistent", persistent);
            value.put("state", state.name().toLowerCase(Locale.ROOT));
            value.put("generation", generation);
            value.put("createdAt", createdAt);
            value.put("nextRun", nextRun);
            value.put("lastRun", lastRun);
            value.put("runCount", runCount);
            value.put("duration", duration);
            value.put("remaining", remaining);
            value.put("elapsed", elapsed);
            value.put("progress", progress);
            value.put("progressPercent", progress * 100D);
            value.put("lastResult", lastResult != null ? lastResult : "");
            value.put("lastError", lastError != null ? lastError : "");
            return Map.copyOf(value);
        }
    }

    public record ScheduleRequest(ScheduleDefinition definition, AutomationOwner owner, long firstDelay, long interval,
                                  LongSupplier nextDelay, Supplier<CompletableFuture<Object>> invocation,
                                  Map<String, Object> arguments, int signatureVersion) {
        public ScheduleRequest {
            definition = Objects.requireNonNull(definition, "Schedule definition is required");
            owner = Objects.requireNonNull(owner, "Schedule owner is required");
            invocation = Objects.requireNonNull(invocation, "Schedule invocation is required");
            arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Map.of();
        }
    }

    public record PersistentTask(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                                 State state, long generation, long createdAt, long nextRun, long lastRun, long runCount,
                                 long duration, long deadline, long remaining, long tickInterval, Map<String, Object> arguments,
                                 int signatureVersion, Object lastResult, String lastError) {
        public PersistentTask {
            arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Map.of();
            lastError = lastError != null ? lastError : "";
        }
    }

    private final Plugin plugin;
    private final AutomationDefinitionRegistry definitions;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<AutomationInstanceKey, TaskEntry> instances = new ConcurrentHashMap<>();
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final Map<AutomationInstanceKey, AtomicLong> generations = new ConcurrentHashMap<>();
    private final AutomationTaskStore store;
    private final List<PersistentTask> pendingRestoration;

    public AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions) {
        this(plugin, definitions, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(new AutomationThreadFactory()),
            new AutomationTaskStore(plugin.getDataFolder().toPath().resolve("runtime").resolve("automation-tasks.json")));
    }

    AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions, Clock clock, ScheduledExecutorService scheduler) {
        this(plugin, definitions, clock, scheduler, null);
    }

    AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions, Clock clock, ScheduledExecutorService scheduler,
                          AutomationTaskStore store) {
        this.plugin = plugin;
        this.definitions = definitions;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.scheduler = scheduler;
        this.store = store;
        this.pendingRestoration = store != null ? new ArrayList<>(store.load()) : new ArrayList<>();
    }

    public TaskSnapshot startTimer(TimerDefinition definition, AutomationOwner owner, long duration, long tickInterval) {
        Objects.requireNonNull(definition, "Timer definition is required");
        Objects.requireNonNull(owner, "Timer owner is required");
        if (duration < 0L || tickInterval < 0L) {
            throw new IllegalArgumentException("Timer duration and tick interval must be non-negative");
        }
        long now = clock.millis();
        long deadline = Math.addExact(now, duration);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
        TaskEntry entry = replace(key, Kind.TIMER, definition.persistent(), owner, duration, 0L, null, null, null);
        entry.timer = definition;
        entry.tickInterval = Math.max(0L, tickInterval);
        entry.deadline = deadline;
        entry.nextRun = entry.tickInterval > 0L ? Math.min(entry.deadline, Math.addExact(now, entry.tickInterval)) : entry.deadline;
        schedule(entry, Math.max(0L, entry.nextRun - now));
        publishTimer(entry, TimerEvent.Type.STARTED);
        persist();
        return snapshot(entry);
    }

    public StartResult startSchedule(ScheduleRequest request) {
        ScheduleDefinition definition = request.definition();
        if (request.firstDelay() < 0L || request.interval() < 0L) {
            throw new IllegalArgumentException("Schedule delays must be non-negative");
        }
        long deadline = Math.addExact(clock.millis(), request.firstDelay());
        AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), request.owner().id());
        TaskEntry existing = instances.get(key);
        if (existing != null && active(existing)) {
            if (definition.existingTaskPolicy() == ScheduleDefinition.ExistingTaskPolicy.KEEP) {
                return new StartResult(false, true, snapshot(existing));
            }
            if (definition.existingTaskPolicy() == ScheduleDefinition.ExistingTaskPolicy.FAIL) {
                throw new IllegalStateException("Schedule is already active: " + definition.name());
            }
        }
        TaskEntry entry = replace(key, Kind.SCHEDULE, definition.persistent(), request.owner(), request.firstDelay(),
            request.interval(), definition, request.nextDelay(), request.invocation());
        entry.deadline = deadline;
        entry.nextRun = entry.deadline;
        schedule(entry, request.firstDelay());
        entry.arguments = request.arguments();
        entry.signatureVersion = request.signatureVersion();
        persist();
        return new StartResult(true, false, snapshot(entry));
    }

    public TaskSnapshot check(AutomationInstanceKey key) {
        TaskEntry entry = instances.get(key);
        return entry != null ? snapshot(entry) : inactive(key);
    }

    public TaskSnapshot task(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? snapshot(entry) : null;
    }

    public TaskSnapshot pause(AutomationInstanceKey key) {
        TaskEntry entry = instances.get(key);
        if (entry == null) {
            return inactive(key);
        }
        synchronized (entry) {
            if (entry.state != State.ACTIVE) {
                return snapshot(entry);
            }
            entry.remainingAtPause = entry.kind == Kind.TIMER ? remainingTimer(entry) : Math.max(0L, entry.nextRun - clock.millis());
            cancelFuture(entry);
            entry.state = State.PAUSED;
        }
        publishLifecycle(entry, ScheduledTaskEvent.Type.PAUSED);
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.PAUSED);
        }
        persist();
        return snapshot(entry);
    }

    public TaskSnapshot resume(AutomationInstanceKey key) {
        TaskEntry entry = instances.get(key);
        if (entry == null) {
            return inactive(key);
        }
        synchronized (entry) {
            if (entry.state != State.PAUSED) {
                return snapshot(entry);
            }
            entry.state = State.ACTIVE;
            if (entry.kind == Kind.TIMER) {
                entry.deadline = Math.addExact(clock.millis(), entry.remainingAtPause);
                long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, entry.remainingAtPause) : entry.remainingAtPause;
                entry.nextRun = Math.addExact(clock.millis(), delay);
                schedule(entry, delay);
            } else {
                entry.nextRun = Math.addExact(clock.millis(), entry.remainingAtPause);
                schedule(entry, entry.remainingAtPause);
            }
        }
        publishLifecycle(entry, ScheduledTaskEvent.Type.RESUMED);
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.RESUMED);
        }
        persist();
        return snapshot(entry);
    }

    public TaskSnapshot cancel(AutomationInstanceKey key) {
        TaskEntry entry = instances.get(key);
        if (entry == null) {
            return inactive(key);
        }
        terminate(entry, State.CANCELLED);
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.STOPPED);
        } else {
            publishLifecycle(entry, ScheduledTaskEvent.Type.CANCELLED);
        }
        persist();
        return snapshot(entry);
    }

    public TaskSnapshot cancel(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? cancel(entry.key) : null;
    }

    public CompletableFuture<Object> runNow(AutomationInstanceKey key) {
        TaskEntry entry = require(key);
        if (entry.kind != Kind.SCHEDULE) {
            throw new IllegalArgumentException("Run Now requires a Schedule");
        }
        return invoke(entry);
    }

    public static Object waitForOwner() {
        return WAIT_FOR_OWNER;
    }

    public List<TaskSnapshot> snapshots() {
        return tasks.values().stream().map(this::snapshot)
            .sorted(Comparator.comparingLong(TaskSnapshot::createdAt).reversed()).toList();
    }

    public void restorePersistentTimers() {
        restorePersistentTimers(definitions::timer);
    }

    void restorePersistentTimers(Function<String, TimerDefinition> resolver) {
        List<PersistentTask> restored = pendingRestoration.stream().filter(state -> state.kind() == Kind.TIMER).toList();
        pendingRestoration.removeAll(restored);
        for (PersistentTask state : restored) {
            try {
                TimerDefinition definition = resolver.apply(state.definitionId());
                AutomationOwner owner = new AutomationOwner(state.ownerId(), state.ownerId());
                AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
                long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).updateAndGet(value -> Math.max(value + 1L, state.generation()));
                TaskEntry entry = restoredEntry(state, key, definition.persistent(), owner, generation, null, null, null);
                entry.timer = definition;
                entry.tickInterval = state.tickInterval();
                if (state.state() == State.PAUSED) {
                    entry.state = State.PAUSED;
                    entry.remainingAtPause = state.remaining();
                } else if (state.deadline() <= clock.millis()) {
                    entry.state = State.FINISHED;
                    publishTimer(entry, TimerEvent.Type.FINISHED);
                    continue;
                } else {
                    entry.deadline = state.deadline();
                    long remaining = entry.deadline - clock.millis();
                    long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, remaining) : remaining;
                    entry.nextRun = Math.addExact(clock.millis(), Math.max(0L, delay));
                    register(entry);
                    schedule(entry, delay);
                    continue;
                }
                register(entry);
            } catch (RuntimeException failure) {
                Log.warn("Failed to restore Timer " + state.definitionId() + ": " + failureMessage(failure));
            }
        }
        persist();
    }

    public void restorePersistentSchedules(Function<PersistentTask, ScheduleRequest> restorer) {
        List<PersistentTask> restored = pendingRestoration.stream().filter(state -> state.kind() == Kind.SCHEDULE).toList();
        pendingRestoration.removeAll(restored);
        for (PersistentTask state : restored) {
            try {
                ScheduleRequest request = restorer.apply(state);
                if (request == null) {
                    continue;
                }
                ScheduleDefinition definition = request.definition();
                AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), request.owner().id());
                long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).updateAndGet(value -> Math.max(value + 1L, state.generation()));
                TaskEntry entry = restoredEntry(state, key, definition.persistent(), request.owner(), generation, definition,
                    request.nextDelay(), request.invocation());
                entry.arguments = request.arguments();
                entry.signatureVersion = request.signatureVersion();
                register(entry);
                if (state.state() == State.PAUSED) {
                    entry.state = State.PAUSED;
                    entry.remainingAtPause = state.remaining();
                } else {
                    boolean missed = state.nextRun() <= clock.millis();
                    if (missed && (definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.CANCEL
                        || (definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.SKIP && request.nextDelay() == null))) {
                        terminate(entry, State.CANCELLED);
                        continue;
                    }
                    long delay = missed && definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.SKIP
                        ? request.nextDelay().getAsLong() : Math.max(0L, state.nextRun() - clock.millis());
                    entry.nextRun = Math.addExact(clock.millis(), delay);
                    schedule(entry, delay);
                }
            } catch (RuntimeException failure) {
                Log.warn("Failed to restore Schedule " + state.definitionId() + ": " + failureMessage(failure));
            }
        }
        persist();
    }

    public void shutdown() {
        persist();
        for (TaskEntry entry : new ArrayList<>(instances.values())) {
            cancelFuture(entry);
        }
        scheduler.shutdownNow();
    }

    private TaskEntry replace(AutomationInstanceKey key, Kind kind, boolean persistent, AutomationOwner owner, long duration,
                              long interval, ScheduleDefinition schedule, LongSupplier nextDelay,
                              Supplier<CompletableFuture<Object>> invocation) {
        TaskEntry previous = instances.get(key);
        if (previous != null) {
            terminate(previous, State.CANCELLED);
        }
        long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        TaskEntry entry = new TaskEntry("automation_" + UUID.randomUUID(), key, kind, persistent, owner, generation, clock.millis(),
            duration, interval, schedule, invocation);
        entry.nextDelay = nextDelay;
        instances.put(key, entry);
        tasks.put(entry.taskId, entry);
        return entry;
    }

    private TaskEntry restoredEntry(PersistentTask state, AutomationInstanceKey key, boolean persistent, AutomationOwner owner,
                                    long generation, ScheduleDefinition schedule, LongSupplier nextDelay,
                                    Supplier<CompletableFuture<Object>> invocation) {
        TaskEntry entry = new TaskEntry(state.taskId(), key, state.kind(), persistent, owner, generation, state.createdAt(),
            state.duration(), 0L, schedule, invocation);
        entry.nextDelay = nextDelay;
        entry.state = state.state();
        entry.nextRun = state.nextRun();
        entry.lastRun = state.lastRun();
        entry.runCount = state.runCount();
        entry.remainingAtPause = state.remaining();
        entry.lastResult = state.lastResult();
        entry.lastError = state.lastError();
        return entry;
    }

    private void register(TaskEntry entry) {
        instances.put(entry.key, entry);
        tasks.put(entry.taskId, entry);
    }

    private void schedule(TaskEntry entry, long delay) {
        cancelFuture(entry);
        long expectedGeneration = entry.generation;
        entry.future = scheduler.schedule(() -> fire(entry, expectedGeneration), Math.max(0L, delay), TimeUnit.MILLISECONDS);
    }

    private void fire(TaskEntry entry, long expectedGeneration) {
        if (!current(entry, expectedGeneration) || entry.state != State.ACTIVE) {
            return;
        }
        if (entry.kind == Kind.TIMER) {
            fireTimer(entry);
        } else {
            fireSchedule(entry);
        }
    }

    private void fireTimer(TaskEntry entry) {
        long now = clock.millis();
        if (now >= entry.deadline) {
            terminate(entry, State.FINISHED);
            publishTimer(entry, TimerEvent.Type.FINISHED);
            persist();
            return;
        }
        publishTimer(entry, TimerEvent.Type.TICK);
        long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, entry.deadline - now) : entry.deadline - now;
        entry.nextRun = Math.addExact(now, delay);
        schedule(entry, delay);
    }

    private void fireSchedule(TaskEntry entry) {
        entry.lastRun = clock.millis();
        entry.runCount++;
        publishLifecycle(entry, ScheduledTaskEvent.Type.FIRED);
        CompletableFuture<Object> execution = invoke(entry);
        if (entry.nextDelay != null && entry.state == State.ACTIVE) {
            try {
                long delay = entry.nextDelay.getAsLong();
                entry.nextRun = Math.addExact(clock.millis(), delay);
                schedule(entry, delay);
                persist();
            } catch (RuntimeException failure) {
                entry.lastError = failureMessage(failure);
                publishLifecycle(entry, ScheduledTaskEvent.Type.FAILED);
                terminate(entry, State.FAILED);
            }
        } else {
            execution.whenComplete((result, failure) -> {
                if (current(entry, entry.generation)) {
                    if (failure == null && result == WAIT_FOR_OWNER) {
                        entry.nextRun = Math.addExact(clock.millis(), 1000L);
                        schedule(entry, 1000L);
                        persist();
                    } else {
                        terminate(entry, failure == null ? State.FINISHED : State.FAILED);
                    }
                }
            });
        }
    }

    private CompletableFuture<Object> invoke(TaskEntry entry) {
        synchronized (entry) {
            if (!active(entry) || entry.invocation == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Scheduled task is inactive"));
            }
            if (entry.running != null && !entry.running.isDone()) {
                switch (entry.schedule.overlapPolicy()) {
                    case SKIP -> {
                        return CompletableFuture.completedFuture(null);
                    }
                    case QUEUE -> {
                        entry.running = entry.running.handle((value, failure) -> null).thenCompose(ignored -> invokeDirect(entry));
                        return entry.running;
                    }
                    case REPLACE -> entry.running.cancel(true);
                    case PARALLEL -> {
                        return invokeDirect(entry);
                    }
                }
            }
            entry.running = invokeDirect(entry);
            return entry.running;
        }
    }

    private CompletableFuture<Object> invokeDirect(TaskEntry entry) {
        CompletableFuture<Object> invocation;
        try {
            invocation = entry.invocation.get();
        } catch (RuntimeException failure) {
            invocation = CompletableFuture.failedFuture(failure);
        }
        if (invocation == null) {
            invocation = CompletableFuture.completedFuture(null);
        }
        return invocation.whenComplete((result, failure) -> {
            if (!current(entry, entry.generation)) {
                return;
            }
            if (failure == null) {
                if (result == WAIT_FOR_OWNER) {
                    return;
                }
                entry.lastResult = result;
                entry.lastError = "";
                publishLifecycle(entry, ScheduledTaskEvent.Type.COMPLETED);
            } else {
                entry.lastError = failureMessage(failure);
                publishLifecycle(entry, ScheduledTaskEvent.Type.FAILED);
                if (entry.schedule.failurePolicy() == ScheduleDefinition.FailurePolicy.STOP) {
                    terminate(entry, State.FAILED);
                }
            }
            persist();
        });
    }

    private void terminate(TaskEntry entry, State state) {
        synchronized (entry) {
            cancelFuture(entry);
            entry.state = state;
            entry.nextRun = 0L;
            instances.remove(entry.key, entry);
        }
        persist();
        if (!scheduler.isShutdown()) {
            scheduler.schedule(() -> tasks.remove(entry.taskId, entry), 5L, TimeUnit.MINUTES);
        }
    }

    private boolean current(TaskEntry entry, long expectedGeneration) {
        return entry.generation == expectedGeneration && instances.get(entry.key) == entry
            && generations.get(entry.key).get() == expectedGeneration;
    }

    private TaskEntry require(AutomationInstanceKey key) {
        TaskEntry entry = instances.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Automation task is not active: " + key.definitionId());
        }
        return entry;
    }

    private boolean active(TaskEntry entry) {
        return entry.state == State.ACTIVE || entry.state == State.PAUSED;
    }

    private void cancelFuture(TaskEntry entry) {
        ScheduledFuture<?> future = entry.future;
        if (future != null) {
            future.cancel(false);
            entry.future = null;
        }
    }

    private TaskSnapshot snapshot(TaskEntry entry) {
        long now = clock.millis();
        long remaining = entry.kind == Kind.TIMER ? remainingTimer(entry)
            : entry.state == State.PAUSED ? entry.remainingAtPause : Math.max(0L, entry.nextRun - now);
        long elapsed = entry.kind == Kind.TIMER ? Math.max(0L, entry.duration - remaining) : 0L;
        double progress = entry.kind == Kind.TIMER && entry.duration > 0L ? Math.clamp((double) elapsed / entry.duration, 0D, 1D) : 0D;
        return new TaskSnapshot(entry.taskId, entry.kind, entry.key.definitionId(), entry.key.scope(), entry.key.ownerId(), entry.owner.value(),
            entry.persistent, entry.state, entry.generation, entry.createdAt, entry.nextRun, entry.lastRun, entry.runCount, entry.duration,
            remaining, elapsed, progress, entry.lastResult, entry.lastError);
    }

    private long remainingTimer(TaskEntry entry) {
        if (entry.state == State.PAUSED) {
            return entry.remainingAtPause;
        }
        return entry.state == State.ACTIVE ? Math.max(0L, entry.deadline - clock.millis()) : 0L;
    }

    private TaskSnapshot inactive(AutomationInstanceKey key) {
        return new TaskSnapshot("", Kind.TIMER, key.definitionId(), key.scope(), key.ownerId(), null, false, State.INACTIVE,
            generations.getOrDefault(key, new AtomicLong()).get(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0D, null, "");
    }

    private void publishTimer(TaskEntry entry, TimerEvent.Type type) {
        if (definitions == null || plugin == null || Bukkit.getServer() == null) {
            return;
        }
        TimerDefinition definition = entry.timer != null ? entry.timer : definitions.timer(entry.key.definitionId());
        publish(new TimerEvent(definitions.reference(definition), entry.owner.value(), type, snapshot(entry).value()));
    }

    private void publishLifecycle(TaskEntry entry, ScheduledTaskEvent.Type type) {
        if (entry.kind != Kind.SCHEDULE || entry.schedule == null || definitions == null || plugin == null || Bukkit.getServer() == null) {
            return;
        }
        FlowResourceReference reference = definitions.reference(entry.schedule);
        publish(new ScheduledTaskEvent(reference, snapshot(entry).value(), entry.owner.value(), type,
            entry.schedule.targetType().name().toLowerCase(Locale.ROOT), entry.schedule.targetId(), entry.lastResult, entry.lastError));
    }

    private void publish(org.bukkit.event.Event event) {
        if (plugin == null || Bukkit.getServer() == null) {
            return;
        }
        Runnable dispatch = () -> Bukkit.getPluginManager().callEvent(event);
        if (Bukkit.isPrimaryThread() || event.isAsynchronous()) {
            dispatch.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatch);
        }
    }

    private void persist() {
        if (store == null) {
            return;
        }
        List<PersistentTask> states = tasks.values().stream().filter(entry -> entry.persistent && active(entry))
            .map(this::persistentState).sorted(Comparator.comparing(PersistentTask::taskId)).toList();
        store.save(states);
    }

    private PersistentTask persistentState(TaskEntry entry) {
        TaskSnapshot snapshot = snapshot(entry);
        return new PersistentTask(entry.taskId, entry.kind, entry.key.definitionId(), entry.key.scope(), entry.key.ownerId(),
            entry.state, entry.generation, entry.createdAt, entry.nextRun, entry.lastRun, entry.runCount, entry.duration,
            entry.deadline, snapshot.remaining(), entry.tickInterval, entry.arguments, entry.signatureVersion, entry.lastResult, entry.lastError);
    }

    private String failureMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null && !current.getMessage().isBlank() ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static final class TaskEntry {
        private final String taskId;
        private final AutomationInstanceKey key;
        private final Kind kind;
        private final boolean persistent;
        private final AutomationOwner owner;
        private final long generation;
        private final long createdAt;
        private final long duration;
        private final long interval;
        private final ScheduleDefinition schedule;
        private final Supplier<CompletableFuture<Object>> invocation;
        private volatile LongSupplier nextDelay;
        private volatile State state = State.ACTIVE;
        private volatile long deadline;
        private volatile long nextRun;
        private volatile long lastRun;
        private volatile long runCount;
        private volatile long remainingAtPause;
        private volatile long tickInterval;
        private volatile Object lastResult;
        private volatile String lastError = "";
        private volatile ScheduledFuture<?> future;
        private volatile CompletableFuture<Object> running;
        private volatile TimerDefinition timer;
        private volatile Map<String, Object> arguments = Map.of();
        private volatile int signatureVersion;

        private TaskEntry(String taskId, AutomationInstanceKey key, Kind kind, boolean persistent, AutomationOwner owner,
                          long generation, long createdAt, long duration, long interval, ScheduleDefinition schedule,
                          Supplier<CompletableFuture<Object>> invocation) {
            this.taskId = taskId;
            this.key = key;
            this.kind = kind;
            this.persistent = persistent;
            this.owner = owner;
            this.generation = generation;
            this.createdAt = createdAt;
            this.duration = duration;
            this.interval = interval;
            this.schedule = schedule;
            this.invocation = invocation;
        }
    }

    private static final class AutomationThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ReSync Automation");
            thread.setDaemon(true);
            return thread;
        }
    }
}
