package restudio.resync.flow.automation;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.flow.automation.event.ScheduledTaskEvent;
import restudio.resync.flow.automation.event.TimerEvent;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
                                  Map<String, Object> arguments, int signatureVersion, String creatorPrincipal,
                                  String creatorSessionReference, ServerResourceLocator targetLocator) {
        public ScheduleRequest(ScheduleDefinition definition, AutomationOwner owner, long firstDelay, long interval,
                                LongSupplier nextDelay, Supplier<CompletableFuture<Object>> invocation,
                                Map<String, Object> arguments, int signatureVersion) {
            this(definition, owner, firstDelay, interval, nextDelay, invocation, arguments, signatureVersion, null, null, null);
        }

        public ScheduleRequest(ScheduleDefinition definition, AutomationOwner owner, long firstDelay, long interval,
                               LongSupplier nextDelay, Supplier<CompletableFuture<Object>> invocation,
                               Map<String, Object> arguments, int signatureVersion, String creatorPrincipal,
                               String creatorSessionReference) {
            this(definition, owner, firstDelay, interval, nextDelay, invocation, arguments, signatureVersion,
                creatorPrincipal, creatorSessionReference, null);
        }

        public ScheduleRequest {
            definition = Objects.requireNonNull(definition, "Schedule definition is required");
            owner = Objects.requireNonNull(owner, "Schedule owner is required");
            invocation = Objects.requireNonNull(invocation, "Schedule invocation is required");
            arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Map.of();
            creatorPrincipal = optionalCanonical(creatorPrincipal, "Schedule creator principal");
            creatorSessionReference = optionalCanonical(creatorSessionReference, "Schedule creator session reference");
            if (creatorPrincipal == null && creatorSessionReference != null) {
                throw new IllegalArgumentException("Schedule creator session requires a creator principal");
            }
        }
    }

    public record PersistentTask(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                                 State state, long generation, long createdAt, long nextRun, long lastRun, long runCount,
                                 long duration, long deadline, long remaining, long tickInterval, Map<String, Object> arguments,
                                 int signatureVersion, Object lastResult, String lastError, String creatorPrincipal,
                                 String creatorSessionReference, boolean invocationPending, String targetLocator,
                                 String targetType, String targetId) {
        public PersistentTask(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                               State state, long generation, long createdAt, long nextRun, long lastRun, long runCount,
                               long duration, long deadline, long remaining, long tickInterval, Map<String, Object> arguments,
                               int signatureVersion, Object lastResult, String lastError) {
            this(taskId, kind, definitionId, scope, ownerId, state, generation, createdAt, nextRun, lastRun, runCount,
                duration, deadline, remaining, tickInterval, arguments, signatureVersion, lastResult, lastError,
                null, null, false, null, null, null);
        }

        public PersistentTask(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                               State state, long generation, long createdAt, long nextRun, long lastRun, long runCount,
                               long duration, long deadline, long remaining, long tickInterval, Map<String, Object> arguments,
                               int signatureVersion, Object lastResult, String lastError, String creatorPrincipal,
                               String creatorSessionReference) {
            this(taskId, kind, definitionId, scope, ownerId, state, generation, createdAt, nextRun, lastRun, runCount,
                duration, deadline, remaining, tickInterval, arguments, signatureVersion, lastResult, lastError,
                creatorPrincipal, creatorSessionReference, false, null, null, null);
        }

        public PersistentTask(String taskId, Kind kind, String definitionId, AutomationScope scope, String ownerId,
                               State state, long generation, long createdAt, long nextRun, long lastRun, long runCount,
                               long duration, long deadline, long remaining, long tickInterval, Map<String, Object> arguments,
                               int signatureVersion, Object lastResult, String lastError, String creatorPrincipal,
                               String creatorSessionReference, boolean invocationPending) {
            this(taskId, kind, definitionId, scope, ownerId, state, generation, createdAt, nextRun, lastRun, runCount,
                duration, deadline, remaining, tickInterval, arguments, signatureVersion, lastResult, lastError,
                creatorPrincipal, creatorSessionReference, invocationPending, null, null, null);
        }

        public PersistentTask {
            arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Map.of();
            lastError = lastError != null ? lastError : "";
            creatorPrincipal = optionalCanonical(creatorPrincipal, "Persistent schedule creator principal");
            creatorSessionReference = optionalCanonical(creatorSessionReference, "Persistent schedule creator session reference");
            if (creatorPrincipal == null && creatorSessionReference != null) {
                throw new IllegalArgumentException("Persistent schedule creator session requires a creator principal");
            }
            targetLocator = optionalCanonical(targetLocator, "Persistent schedule target locator");
            targetType = optionalTargetType(targetType);
            targetId = optionalCanonical(targetId, "Persistent schedule target ID");
        }
    }

    private final Plugin plugin;
    private final AutomationDefinitionRegistry definitions;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<AutomationInstanceKey, TaskEntry> instances = new ConcurrentHashMap<>();
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final Map<AutomationInstanceKey, AtomicLong> generations = new ConcurrentHashMap<>();
    private volatile AutomationTaskStore store;
    private final List<PersistentTask> pendingRestoration;
    private final Object persistenceLock = new Object();
    private volatile PersistenceState persistenceState = PersistenceState.OPEN;

    public AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions) {
        this(plugin, definitions, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(new AutomationThreadFactory()),
            new AutomationTaskStore(plugin.getDataFolder().toPath().resolve("runtime").resolve("automation-tasks.json")));
    }

    AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions, Clock clock, ScheduledExecutorService scheduler) {
        this(plugin, definitions, clock, scheduler, (AutomationTaskStore) null);
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

    public AutomationTaskService(Plugin plugin, AutomationDefinitionRegistry definitions, Clock clock,
                                 ScheduledExecutorService scheduler, Path persistenceFile) {
        this(plugin, definitions, clock, scheduler, persistenceFile != null ? new AutomationTaskStore(persistenceFile) : null);
    }

    public TaskSnapshot startTimer(TimerDefinition definition, AutomationOwner owner, long duration, long tickInterval) {
        Objects.requireNonNull(definition, "Timer definition is required");
        Objects.requireNonNull(owner, "Timer owner is required");
        if (duration < 0L || tickInterval < 0L) {
            throw new IllegalArgumentException("Timer duration and tick interval must be non-negative");
        }
        TaskEntry entry;
        long now;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            now = clock.millis();
            long deadline = Math.addExact(now, duration);
            AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
            entry = replace(key, Kind.TIMER, definition.persistent(), owner, duration, 0L, null, null, null);
            entry.timer = definition;
            entry.tickInterval = Math.max(0L, tickInterval);
            entry.deadline = deadline;
            entry.nextRun = entry.tickInterval > 0L ? Math.min(entry.deadline, Math.addExact(now, entry.tickInterval)) : entry.deadline;
            try {
                persistLocked();
                schedule(entry, Math.max(0L, entry.nextRun - now));
            } catch (RuntimeException failure) {
                rollbackStart(entry, failure);
                throw failure;
            }
        }
        publishTimer(entry, TimerEvent.Type.STARTED);
        return snapshot(entry);
    }

    public StartResult startSchedule(ScheduleRequest request) {
        ScheduleDefinition definition = request.definition();
        validateScheduleRequestTarget(request);
        if (request.firstDelay() < 0L || request.interval() < 0L) {
            throw new IllegalArgumentException("Schedule delays must be non-negative");
        }
        synchronized (persistenceLock) {
            requireWritablePersistence();
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
            entry.arguments = request.arguments();
            entry.signatureVersion = request.signatureVersion();
            entry.creatorPrincipal = request.creatorPrincipal();
            entry.creatorSessionReference = request.creatorSessionReference();
            entry.targetLocator = request.targetLocator();
            try {
                persistLocked();
                schedule(entry, request.firstDelay());
            } catch (RuntimeException failure) {
                rollbackStart(entry, failure);
                throw failure;
            }
            return new StartResult(true, false, snapshot(entry));
        }
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
        TaskEntry entry;
        TaskRuntimeState previous;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            entry = instances.get(key);
            if (entry == null) {
                return inactive(key);
            }
            synchronized (entry) {
                if (entry.state != State.ACTIVE) {
                    return snapshot(entry);
                }
                previous = runtimeState(entry);
                entry.remainingAtPause = entry.kind == Kind.TIMER ? remainingTimer(entry) : Math.max(0L, entry.nextRun - clock.millis());
                cancelFuture(entry);
                entry.state = State.PAUSED;
            }
            try {
                persistLocked();
            } catch (RuntimeException failure) {
                restoreRuntimeState(entry, previous);
                throw failure;
            }
        }
        publishLifecycle(entry, ScheduledTaskEvent.Type.PAUSED);
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.PAUSED);
        }
        return snapshot(entry);
    }

    public TaskSnapshot resume(AutomationInstanceKey key) {
        TaskEntry entry;
        TaskRuntimeState previous;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            entry = instances.get(key);
            if (entry == null) {
                return inactive(key);
            }
            synchronized (entry) {
                if (entry.state != State.PAUSED) {
                    return snapshot(entry);
                }
                previous = runtimeState(entry);
                entry.state = State.ACTIVE;
                if (entry.kind == Kind.TIMER) {
                    entry.deadline = Math.addExact(clock.millis(), entry.remainingAtPause);
                    long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, entry.remainingAtPause) : entry.remainingAtPause;
                    entry.nextRun = Math.addExact(clock.millis(), delay);
                } else {
                    entry.nextRun = Math.addExact(clock.millis(), entry.remainingAtPause);
                }
            }
            try {
                persistLocked();
                schedule(entry, Math.max(0L, entry.nextRun - clock.millis()));
            } catch (RuntimeException failure) {
                restoreRuntimeState(entry, previous);
                try {
                    persistLocked();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
        publishLifecycle(entry, ScheduledTaskEvent.Type.RESUMED);
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.RESUMED);
        }
        return snapshot(entry);
    }

    public TaskSnapshot cancel(AutomationInstanceKey key) {
        TaskEntry entry;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            entry = instances.get(key);
            if (entry == null) {
                return inactive(key);
            }
            terminate(entry, State.CANCELLED);
        }
        if (entry.kind == Kind.TIMER) {
            publishTimer(entry, TimerEvent.Type.STOPPED);
        } else {
            publishLifecycle(entry, ScheduledTaskEvent.Type.CANCELLED);
        }
        return snapshot(entry);
    }

    public TaskSnapshot cancel(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? cancel(entry.key) : null;
    }

    public CompletableFuture<Object> runNow(AutomationInstanceKey key) {
        synchronized (persistenceLock) {
            requireWritablePersistence();
            TaskEntry entry = require(key);
            if (entry.kind != Kind.SCHEDULE) {
                throw new IllegalArgumentException("Run Now requires a Schedule");
            }
            return invoke(entry);
        }
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
        requireWritablePersistence();
        List<PersistentTask> restored = pendingRestoration.stream().filter(state -> state.kind() == Kind.TIMER).toList();
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
                    pendingRestoration.remove(state);
                    continue;
                } else {
                    entry.deadline = state.deadline();
                    long remaining = entry.deadline - clock.millis();
                    long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, remaining) : remaining;
                    entry.nextRun = Math.addExact(clock.millis(), Math.max(0L, delay));
                    register(entry);
                    schedule(entry, delay);
                    pendingRestoration.remove(state);
                    continue;
                }
                register(entry);
                pendingRestoration.remove(state);
            } catch (RuntimeException failure) {
                Log.warn("Failed to restore Timer " + state.definitionId() + ": " + failureMessage(failure));
            }
        }
        persist();
    }

    public void restorePersistentSchedules(Function<PersistentTask, ScheduleRequest> restorer) {
        requireWritablePersistence();
        List<PersistentTask> restored = pendingRestoration.stream().filter(state -> state.kind() == Kind.SCHEDULE).toList();
        for (PersistentTask state : restored) {
            try {
                validatePersistedTargetState(state);
                ScheduleRequest request = restorer.apply(state);
                if (request == null) {
                    continue;
                }
                validateScheduleRequestTarget(request);
                validateRestoredTargetRequest(state, request);
                ScheduleDefinition definition = request.definition();
                AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), request.owner().id());
                long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).updateAndGet(value -> Math.max(value + 1L, state.generation()));
                TaskEntry entry = restoredEntry(state, key, definition.persistent(), request.owner(), generation, definition,
                    request.nextDelay(), request.invocation());
                entry.arguments = request.arguments();
                entry.signatureVersion = request.signatureVersion();
                entry.targetLocator = request.targetLocator() != null
                    ? request.targetLocator() : persistedTargetLocator(state);
                register(entry);
                if (state.state() == State.PAUSED) {
                    entry.state = State.PAUSED;
                    entry.remainingAtPause = state.remaining();
                } else {
                    boolean missed = state.nextRun() <= clock.millis();
                    boolean pendingInvocation = state.invocationPending();
                    if (!pendingInvocation && missed && (definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.CANCEL
                        || (definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.SKIP && request.nextDelay() == null))) {
                        terminate(entry, State.CANCELLED);
                        pendingRestoration.remove(state);
                        continue;
                    }
                    long delay;
                    if (pendingInvocation) {
                        entry.nextRun = state.nextRun();
                        delay = 0L;
                    } else {
                        delay = missed && definition.missedRunPolicy() == ScheduleDefinition.MissedRunPolicy.SKIP
                            ? request.nextDelay().getAsLong() : Math.max(0L, state.nextRun() - clock.millis());
                        entry.nextRun = Math.addExact(clock.millis(), delay);
                    }
                    schedule(entry, delay);
                }
                pendingRestoration.remove(state);
            } catch (RuntimeException failure) {
                Log.warn("Failed to restore Schedule " + state.definitionId() + ": " + failureMessage(failure));
            }
        }
        persist();
    }

    public void shutdown() {
        RuntimeException persistenceFailure = null;
        try {
            quiescePersistence();
        } catch (IOException failure) {
            persistenceFailure = new IllegalStateException("Failed to quiesce automation task persistence", failure);
        } catch (RuntimeException failure) {
            persistenceFailure = failure;
        } finally {
            for (TaskEntry entry : new ArrayList<>(instances.values())) {
                cancelFuture(entry);
            }
            scheduler.shutdownNow();
        }
        if (persistenceFailure != null) {
            throw persistenceFailure;
        }
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
        entry.creatorPrincipal = state.creatorPrincipal();
        entry.creatorSessionReference = state.creatorSessionReference();
        entry.invocationPending = state.invocationPending();
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
        boolean admitted;
        synchronized (persistenceLock) {
            admitted = persistenceWritable() && current(entry, expectedGeneration) && entry.state == State.ACTIVE;
        }
        if (!admitted) {
            return;
        }
        if (entry.kind == Kind.TIMER) {
            fireTimer(entry);
        } else {
            fireSchedule(entry);
        }
    }

    private void fireTimer(TaskEntry entry) {
        synchronized (persistenceLock) {
            if (!persistenceWritable() || entry.state != State.ACTIVE) {
                return;
            }
            long now = clock.millis();
            if (now >= entry.deadline) {
                terminate(entry, State.FINISHED);
                publishTimer(entry, TimerEvent.Type.FINISHED);
                return;
            }
            publishTimer(entry, TimerEvent.Type.TICK);
            long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, entry.deadline - now) : entry.deadline - now;
            entry.nextRun = Math.addExact(now, delay);
            schedule(entry, delay);
        }
    }

    private void fireSchedule(TaskEntry entry) {
        synchronized (persistenceLock) {
            if (!persistenceWritable() || entry.state != State.ACTIVE) {
                return;
            }
            boolean skip = entry.running != null && !entry.running.isDone()
                && entry.schedule.overlapPolicy() == ScheduleDefinition.OverlapPolicy.SKIP;
            if (skip) {
                return;
            }
            if (!entry.invocationPending) {
                entry.lastRun = clock.millis();
                entry.runCount++;
                entry.invocationPending = true;
                persistLocked();
            }
        }
        CompletableFuture<Object> execution = invoke(entry);
        publishLifecycle(entry, ScheduledTaskEvent.Type.FIRED);
        if (entry.nextDelay != null && entry.state == State.ACTIVE) {
            try {
                long delay = entry.nextDelay.getAsLong();
                synchronized (persistenceLock) {
                    if (!persistenceWritable() || !current(entry, entry.generation) || entry.state != State.ACTIVE) {
                        return;
                    }
                    entry.nextRun = Math.addExact(clock.millis(), delay);
                    schedule(entry, delay);
                    persistLocked();
                }
            } catch (RuntimeException failure) {
                synchronized (persistenceLock) {
                    if (!persistenceWritable() || !current(entry, entry.generation)) {
                        return;
                    }
                    entry.lastError = failureMessage(failure);
                    publishLifecycle(entry, ScheduledTaskEvent.Type.FAILED);
                    terminate(entry, State.FAILED);
                }
            }
        } else {
            execution.whenComplete((result, failure) -> {
                synchronized (persistenceLock) {
                    if (!persistenceWritable() || !current(entry, entry.generation)) {
                        return;
                    }
                    if (failure == null && result == WAIT_FOR_OWNER) {
                        entry.nextRun = Math.addExact(clock.millis(), 1000L);
                        schedule(entry, 1000L);
                        persistLocked();
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
            synchronized (persistenceLock) {
                if (!persistenceWritable() || !current(entry, entry.generation)) {
                    return;
                }
                if (failure == null) {
                    if (result == WAIT_FOR_OWNER) {
                        return;
                    }
                    entry.invocationPending = false;
                    entry.lastResult = result;
                    entry.lastError = "";
                    publishLifecycle(entry, ScheduledTaskEvent.Type.COMPLETED);
                } else {
                    entry.invocationPending = false;
                    entry.lastError = failureMessage(failure);
                    publishLifecycle(entry, ScheduledTaskEvent.Type.FAILED);
                    if (entry.schedule.failurePolicy() == ScheduleDefinition.FailurePolicy.STOP) {
                        terminate(entry, State.FAILED);
                    }
                }
                persistLocked();
            }
        });
    }

    private void terminate(TaskEntry entry, State state) {
        synchronized (persistenceLock) {
            TaskRuntimeState previous;
            synchronized (entry) {
                previous = runtimeState(entry);
                cancelFuture(entry);
                entry.state = state;
                entry.nextRun = 0L;
                instances.remove(entry.key, entry);
            }
            try {
                persistLocked();
            } catch (RuntimeException failure) {
                restoreRuntimeState(entry, previous);
                throw failure;
            }
            if (!scheduler.isShutdown()) {
                scheduler.schedule(() -> tasks.remove(entry.taskId, entry), 5L, TimeUnit.MINUTES);
            }
        }
    }

    private TaskRuntimeState runtimeState(TaskEntry entry) {
        return new TaskRuntimeState(entry.state, entry.deadline, entry.nextRun, entry.remainingAtPause);
    }

    private void restoreRuntimeState(TaskEntry entry, TaskRuntimeState state) {
        synchronized (entry) {
            cancelFuture(entry);
            entry.state = state.state();
            entry.deadline = state.deadline();
            entry.nextRun = state.nextRun();
            entry.remainingAtPause = state.remainingAtPause();
            tasks.put(entry.taskId, entry);
            if (active(entry)) {
                instances.put(entry.key, entry);
            }
            if (entry.state == State.ACTIVE) {
                schedule(entry, Math.max(1_000L, entry.nextRun - clock.millis()));
            }
        }
    }

    private void rollbackStart(TaskEntry entry, RuntimeException failure) {
        synchronized (entry) {
            cancelFuture(entry);
            entry.state = State.FAILED;
            entry.nextRun = 0L;
            instances.remove(entry.key, entry);
            tasks.remove(entry.taskId, entry);
        }
        try {
            persist();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
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
            entry.targetLocator != null ? entry.targetLocator.resourceType().value()
                : entry.schedule.targetType().name().toLowerCase(Locale.ROOT),
            entry.targetLocator != null ? entry.targetLocator.id() : entry.schedule.targetId(), entry.lastResult, entry.lastError));
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
        synchronized (persistenceLock) {
            requireWritablePersistence();
            persistLocked();
        }
    }

    public Path persistenceRoot() {
        AutomationTaskStore activeStore = store;
        return activeStore != null ? activeStore.file() : null;
    }

    public void flushPersistence() throws IOException {
        synchronized (persistenceLock) {
            flushPersistenceLocked();
        }
    }

    public void quiescePersistence() throws IOException {
        synchronized (persistenceLock) {
            if (persistenceState == PersistenceState.QUIESCED) {
                return;
            }
            persistenceState = PersistenceState.QUIESCING;
            for (TaskEntry entry : tasks.values()) {
                cancelFuture(entry);
            }
            try {
                flushPersistenceLocked();
                persistenceState = PersistenceState.QUIESCED;
            } catch (IOException | RuntimeException failure) {
                persistenceState = PersistenceState.OPEN;
                throw failure;
            }
        }
    }

    public void resumePersistence() throws IOException {
        synchronized (persistenceLock) {
            if (persistenceState == PersistenceState.OPEN) {
                return;
            }
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Automation task persistence is not quiesced");
            }
            healthCheckPersistenceLocked();
            persistenceState = PersistenceState.OPEN;
            long now = clock.millis();
            for (TaskEntry entry : instances.values()) {
                if (entry.state == State.ACTIVE) {
                    schedule(entry, Math.max(0L, entry.nextRun - now));
                }
            }
        }
    }

    public void rebindPersistence(Path activeFile) throws IOException {
        synchronized (persistenceLock) {
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Automation task persistence must be quiesced before rebind");
            }
            Path candidate = requirePersistenceFile(activeFile);
            AutomationTaskStore candidateStore = new AutomationTaskStore(candidate);
            List<PersistentTask> candidateTasks = candidateStore.loadStrict();
            candidateStore.ensurePresent();
            for (TaskEntry entry : tasks.values()) {
                cancelFuture(entry);
            }
            instances.clear();
            tasks.clear();
            store = candidateStore;
            pendingRestoration.clear();
            pendingRestoration.addAll(candidateTasks);
        }
    }

    public void healthCheckPersistence() throws IOException {
        synchronized (persistenceLock) {
            healthCheckPersistenceLocked();
        }
    }

    public boolean isPersistenceQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    private void persistLocked() {
        if (store == null) {
            return;
        }
        List<PersistentTask> states = persistentStates();
        try {
            store.save(states);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to save persistent automation tasks", failure);
        }
    }

    private void flushPersistenceLocked() throws IOException {
        if (store != null) {
            store.save(persistentStates());
        }
    }

    private void healthCheckPersistenceLocked() throws IOException {
        if (store != null) {
            store.healthCheck(persistentStates());
        }
    }

    private List<PersistentTask> persistentStates() {
        Map<String, PersistentTask> states = new LinkedHashMap<>();
        pendingRestoration.forEach(state -> states.put(state.taskId(), state));
        tasks.values().stream().filter(entry -> entry.persistent && active(entry))
            .map(this::persistentState).forEach(state -> states.put(state.taskId(), state));
        return states.values().stream().sorted(Comparator.comparing(PersistentTask::taskId)).toList();
    }

    private boolean persistenceWritable() {
        return persistenceState == PersistenceState.OPEN;
    }

    private void requireWritablePersistence() {
        if (!persistenceWritable()) {
            throw new IllegalStateException("Automation task persistence is " + persistenceState.name().toLowerCase(Locale.ROOT)
                + "; mutation rejected");
        }
    }

    private static Path requirePersistenceFile(Path activeFile) throws IOException {
        Path candidate = MigrationPaths.requirePath(activeFile, "automation task persistence file");
        Path parent = candidate.getParent();
        if (parent == null || !candidate.getFileName().toString().equals("automation-tasks.json")) {
            throw new IOException("Automation task persistence file must be runtime/automation-tasks.json");
        }
        MigrationPaths.requireDirectory(parent, "automation task persistence directory");
        if (Files.exists(candidate) && (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate))) {
            throw new IOException("Automation task persistence file must be a regular non-symbolic-link file");
        }
        MigrationPaths.requireNoSymlinkTraversal(parent, candidate);
        return candidate;
    }

    private PersistentTask persistentState(TaskEntry entry) {
        TaskSnapshot snapshot = snapshot(entry);
        return new PersistentTask(entry.taskId, entry.kind, entry.key.definitionId(), entry.key.scope(), entry.key.ownerId(),
            entry.state, entry.generation, entry.createdAt, entry.nextRun, entry.lastRun, entry.runCount, entry.duration,
            entry.deadline, snapshot.remaining(), entry.tickInterval, entry.arguments, entry.signatureVersion, entry.lastResult,
            entry.lastError, entry.creatorPrincipal, entry.creatorSessionReference, entry.invocationPending,
            entry.targetLocator != null ? entry.targetLocator.canonicalText() : null,
            entry.schedule != null ? entry.schedule.targetResourceType() : null,
            entry.schedule != null ? entry.schedule.targetId() : null);
    }

    private static void validatePersistedTargetState(PersistentTask state) {
        String locatorText = state.targetLocator();
        boolean hasLocator = locatorText != null && !locatorText.isBlank();
        boolean hasLegacyType = state.targetType() != null && !state.targetType().isBlank();
        boolean hasLegacyId = state.targetId() != null && !state.targetId().isBlank();
        if (!hasLocator && (!hasLegacyType || !hasLegacyId)) {
            throw new IllegalArgumentException("Persisted schedule has no complete typed target");
        }
        if (hasLocator) {
            ServerResourceLocator locator;
            try {
                locator = ServerResourceLocator.parseCanonicalText(locatorText);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Persisted schedule target locator is invalid", failure);
            }
            if ((hasLegacyType && !state.targetType().equals(locator.resourceType().value()))
                || (hasLegacyId && !state.targetId().equals(locator.id()))) {
                throw new IllegalArgumentException("Persisted schedule target identity fields conflict");
            }
        }
    }

    private static void validateScheduleRequestTarget(ScheduleRequest request) {
        ServerResourceLocator locator = request.targetLocator();
        if (locator == null) {
            return;
        }
        if (!"restudio.resync".equals(locator.owner().value())
            || !Set.of("flow", "function", "command").contains(locator.resourceType().value())
            || !request.definition().targetResourceType().equals(locator.resourceType().value())
            || !request.definition().targetId().equals(locator.id())) {
            throw new IllegalArgumentException("Schedule request target does not match its definition");
        }
    }

    private static void validateRestoredTargetRequest(PersistentTask state, ScheduleRequest request) {
        if (request.targetLocator() == null) {
            if ((state.targetLocator() != null && !state.targetLocator().isBlank())
                || !request.definition().targetResourceType().equals(state.targetType())
                || !request.definition().targetId().equals(state.targetId())) {
                throw new IllegalArgumentException("Restored schedule target is not typed and exact");
            }
            return;
        }
        String persisted = state.targetLocator();
        if (persisted != null && !persisted.isBlank()
            && !persisted.equals(request.targetLocator().canonicalText())) {
            throw new IllegalArgumentException("Restored schedule target locator changed during restore");
        }
        if (state.targetType() != null && !state.targetType().isBlank()
            && !state.targetType().equals(request.targetLocator().resourceType().value())) {
            throw new IllegalArgumentException("Restored schedule target type changed during restore");
        }
        if (state.targetId() != null && !state.targetId().isBlank()
            && !state.targetId().equals(request.targetLocator().id())) {
            throw new IllegalArgumentException("Restored schedule target ID changed during restore");
        }
    }

    private static ServerResourceLocator persistedTargetLocator(PersistentTask state) {
        String locatorText = state.targetLocator();
        if (locatorText == null || locatorText.isBlank()) {
            return null;
        }
        try {
            return ServerResourceLocator.parseCanonicalText(locatorText);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Persisted schedule target locator is invalid", failure);
        }
    }

    private String failureMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null && !current.getMessage().isBlank() ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record TaskRuntimeState(State state, long deadline, long nextRun, long remainingAtPause) {
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
        private volatile String creatorPrincipal;
        private volatile String creatorSessionReference;
        private volatile boolean invocationPending;
        private volatile ServerResourceLocator targetLocator;
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

    private enum PersistenceState {
        OPEN,
        QUIESCING,
        QUIESCED
    }

    private static String optionalCanonical(String value, String label) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 512 || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(label + " must be canonical text");
        }
        return normalized;
    }

    private static String optionalTargetType(String value) {
        String normalized = optionalCanonical(value, "Persistent schedule target type");
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!Set.of("flow", "function", "command").contains(normalized)) {
            throw new IllegalArgumentException("Persistent schedule target type is unsupported: " + value);
        }
        return normalized;
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
