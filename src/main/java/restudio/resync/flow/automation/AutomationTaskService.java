package restudio.resync.flow.automation;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.flow.automation.event.ScheduledTaskEvent;
import restudio.resync.flow.automation.event.TimerEvent;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
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

    public record Invocation(TaskSnapshot task, RuntimeCancellationToken cancellation, long epoch) {
    }

    @FunctionalInterface
    public interface InvocationSupplier extends Supplier<CompletableFuture<Object>> {
        CompletableFuture<Object> invoke(Invocation invocation);

        @Override
        default CompletableFuture<Object> get() {
            throw new IllegalStateException("Schedule invocation admission is required");
        }
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
    private long persistenceGeneration;

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
            AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
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
            AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), request.owner().id());
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
        }
        return cancelTask(entry);
    }

    public TaskSnapshot cancel(String taskId) {
        TaskEntry entry;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            entry = tasks.get(taskId);
        }
        return entry != null ? cancelTask(entry) : null;
    }

    private TaskSnapshot cancelTask(TaskEntry entry) {
        synchronized (persistenceLock) {
            requireWritablePersistence();
            if (!current(entry, entry.generation)) {
                return snapshot(entry);
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

    public boolean canInvoke(String taskId, long generation) {
        synchronized (persistenceLock) {
            TaskEntry entry = tasks.get(taskId);
            return persistenceWritable() && entry != null && current(entry, generation) && active(entry);
        }
    }

    public boolean canInvoke(Invocation invocation) {
        synchronized (persistenceLock) {
            return invocation.epoch() == persistenceGeneration && !invocation.cancellation().isCancelled()
                && canInvoke(invocation.task().taskId(), invocation.task().generation());
        }
    }

    public CompletableFuture<Object> runNow(AutomationInstanceKey key) {
        TaskEntry entry;
        synchronized (persistenceLock) {
            requireWritablePersistence();
            entry = require(key);
            if (entry.kind != Kind.SCHEDULE) {
                throw new IllegalArgumentException("Run Now requires a Schedule");
            }
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
        List<TaskEntry> finished = new ArrayList<>();
        synchronized (persistenceLock) {
            requireWritablePersistence();
            List<PersistentTask> restored = pendingRestoration.stream().filter(state -> state.kind() == Kind.TIMER).toList();
            for (PersistentTask state : restored) {
                try {
                    TimerDefinition definition = resolver.apply(state.definitionId());
                    AutomationOwner owner = new AutomationOwner(state.ownerId(), state.ownerId());
                    AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
                    long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).updateAndGet(value -> Math.max(value + 1L, state.generation()));
                    TaskEntry entry = restoredEntry(state, key, definition.persistent(), owner, generation, null, null, null);
                    entry.timer = definition;
                    entry.tickInterval = state.tickInterval();
                    if (state.state() == State.PAUSED) {
                        entry.state = State.PAUSED;
                        entry.remainingAtPause = state.remaining();
                    } else if (state.deadline() <= clock.millis()) {
                        entry.state = State.FINISHED;
                        finished.add(entry);
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
        finished.forEach(entry -> publishTimer(entry, TimerEvent.Type.FINISHED));
    }

    public void restorePersistentSchedules(Function<PersistentTask, ScheduleRequest> restorer) {
        synchronized (persistenceLock) {
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
                    AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), request.owner().id());
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
        entry.pendingNextRun = state.nextRun();
        return entry;
    }

    private void register(TaskEntry entry) {
        instances.put(entry.key, entry);
        tasks.put(entry.taskId, entry);
    }

    private void schedule(TaskEntry entry, long delay) {
        cancelFuture(entry);
        long expectedGeneration = entry.generation;
        long expectedWake = entry.wake;
        entry.future = scheduler.schedule(() -> fire(entry, expectedGeneration, expectedWake), Math.max(0L, delay), TimeUnit.MILLISECONDS);
    }

    private void fire(TaskEntry entry, long expectedGeneration, long expectedWake) {
        boolean admitted;
        synchronized (persistenceLock) {
            admitted = persistenceWritable() && current(entry, expectedGeneration) && entry.wake == expectedWake && entry.state == State.ACTIVE;
        }
        if (!admitted) {
            return;
        }
        if (entry.kind == Kind.TIMER) {
            fireTimer(entry, expectedWake);
        } else {
            fireSchedule(entry, expectedWake);
        }
    }

    private void fireTimer(TaskEntry entry, long expectedWake) {
        TimerEvent.Type event;
        synchronized (persistenceLock) {
            if (!persistenceWritable() || !current(entry, entry.generation) || entry.wake != expectedWake || entry.state != State.ACTIVE) {
                return;
            }
            long now = clock.millis();
            if (now >= entry.deadline) {
                terminate(entry, State.FINISHED);
                event = TimerEvent.Type.FINISHED;
            } else {
                long delay = entry.tickInterval > 0L ? Math.min(entry.tickInterval, entry.deadline - now) : entry.deadline - now;
                entry.nextRun = Math.addExact(now, delay);
                schedule(entry, delay);
                event = TimerEvent.Type.TICK;
            }
        }
        publishTimer(entry, event);
    }

    private void fireSchedule(TaskEntry entry, long expectedWake) {
        TaskRun run;
        synchronized (persistenceLock) {
            if (!persistenceWritable() || !current(entry, entry.generation) || entry.wake != expectedWake || entry.state != State.ACTIVE) {
                return;
            }
            run = reserveInvocation(entry);
        }
        if (run == null) {
            advanceSchedule(entry);
            return;
        }
        startInvocation(run);
        publishLifecycle(entry, ScheduledTaskEvent.Type.FIRED);
        if (entry.nextDelay != null) {
            advanceSchedule(entry);
        } else {
            run.completion.whenComplete((result, failure) -> finishSchedule(run, result, failure));
        }
    }

    private void finishSchedule(TaskRun run, Object result, Throwable failure) {
        TaskEntry entry = run.entry;
        CompletableFuture<Void> physical;
        synchronized (persistenceLock) {
            physical = CompletableFuture.allOf(entry.invocations.stream()
                .map(invocation -> invocation.completion).toArray(CompletableFuture[]::new));
        }
        physical.whenComplete((ignored, physicalFailure) -> {
            synchronized (persistenceLock) {
                if (!persistenceWritable() || run.epoch != persistenceGeneration || !current(entry, entry.generation)
                    || run.cancellation.isCancelled() || !run.settled || !entry.invocations.isEmpty()) {
                    return;
                }
                if (failure == null && result == WAIT_FOR_OWNER) {
                    entry.nextRun = Math.addExact(clock.millis(), 1000L);
                    persistLocked();
                    schedule(entry, 1000L);
                } else {
                    terminate(entry, failure == null ? State.FINISHED : State.FAILED);
                }
            }
        });
    }

    private void advanceSchedule(TaskEntry entry) {
        if (entry.nextDelay == null) {
            return;
        }
        try {
            synchronized (persistenceLock) {
                if (!persistenceWritable() || !current(entry, entry.generation) || entry.state != State.ACTIVE) {
                    return;
                }
                long delay = entry.nextDelay.getAsLong();
                if (delay < 0L) {
                    throw new IllegalArgumentException("Schedule delay must be non-negative");
                }
                entry.nextRun = Math.addExact(clock.millis(), delay);
                persistLocked();
                schedule(entry, delay);
            }
        } catch (RuntimeException failure) {
            synchronized (persistenceLock) {
                if (!persistenceWritable() || !current(entry, entry.generation)) {
                    return;
                }
                entry.lastError = failureMessage(failure);
                terminate(entry, State.FAILED);
            }
            publishLifecycle(entry, ScheduledTaskEvent.Type.FAILED);
        }
    }

    private CompletableFuture<Object> invoke(TaskEntry entry) {
        TaskRun run;
        synchronized (persistenceLock) {
            if (!persistenceWritable() || !current(entry, entry.generation) || !active(entry) || entry.invocation == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Scheduled task is inactive"));
            }
            run = reserveInvocation(entry);
        }
        if (run == null) {
            return CompletableFuture.completedFuture(null);
        }
        startInvocation(run);
        return run.completion.copy();
    }

    private TaskRun reserveInvocation(TaskEntry entry) {
        CompletableFuture<Void> previous = CompletableFuture.completedFuture(null);
        if (!entry.invocations.isEmpty()) {
            switch (entry.schedule.overlapPolicy()) {
                case SKIP -> {
                    return null;
                }
                case QUEUE -> previous = CompletableFuture.allOf(entry.invocations.stream()
                    .map(run -> run.completion).toArray(CompletableFuture[]::new));
                case REPLACE -> entry.invocations.forEach(run -> run.cancellation.cancel());
                case PARALLEL -> {
                }
            }
        }
        if (entry.invocationPending && entry.invocations.stream().anyMatch(run -> run.epoch != persistenceGeneration)) {
            previous = CompletableFuture.allOf(entry.invocations.stream()
                .map(run -> run.completion).toArray(CompletableFuture[]::new));
        }
        TaskRun run = new TaskRun(entry, persistenceGeneration, previous);
        entry.invocations.add(run);
        return run;
    }

    private void startInvocation(TaskRun run) {
        if (run.previous.isDone()) {
            try {
                invokeDirect(run);
            } catch (RuntimeException failure) {
                run.completion.completeExceptionally(failure);
                throw failure;
            }
        } else {
            run.previous.whenComplete((ignored, failure) -> {
                try {
                    invokeDirect(run);
                } catch (RuntimeException admissionFailure) {
                    run.completion.completeExceptionally(admissionFailure);
                }
            });
        }
    }

    private void invokeDirect(TaskRun run) {
        TaskEntry entry = run.entry;
        Invocation admitted = null;
        synchronized (persistenceLock) {
            if (persistenceWritable() && run.epoch == persistenceGeneration && current(entry, entry.generation)
                && active(entry) && !run.cancellation.isCancelled()) {
                long previousRun = entry.lastRun;
                long previousCount = entry.runCount;
                boolean previousPending = entry.invocationPending;
                long previousPendingRun = entry.pendingNextRun;
                boolean replay = previousPending && entry.invocations.stream().noneMatch(other -> other.started);
                if (!replay) {
                    entry.lastRun = clock.millis();
                    entry.runCount++;
                    entry.pendingNextRun = entry.nextRun;
                }
                entry.invocationPending = true;
                try {
                    persistLocked();
                } catch (RuntimeException failure) {
                    entry.lastRun = previousRun;
                    entry.runCount = previousCount;
                    entry.invocationPending = previousPending;
                    entry.pendingNextRun = previousPendingRun;
                    entry.invocations.remove(run);
                    entry.lastError = failureMessage(failure);
                    schedule(entry, 1000L);
                    throw failure;
                }
                run.started = true;
                admitted = new Invocation(snapshot(entry, entry.pendingNextRun), run.cancellation, run.epoch);
            } else {
                entry.invocations.remove(run);
            }
        }
        if (admitted == null) {
            run.completion.completeExceptionally(new IllegalStateException("Scheduled task is inactive"));
            return;
        }
        if (!canInvoke(admitted)) {
            completeInvocation(run, null, new IllegalStateException("Scheduled task is inactive"));
            return;
        }
        CompletableFuture<Object> execution;
        try {
            execution = entry.invocation instanceof InvocationSupplier supplier
                ? supplier.invoke(admitted) : entry.invocation.get();
        } catch (RuntimeException failure) {
            execution = CompletableFuture.failedFuture(failure);
        }
        if (execution == null) {
            execution = CompletableFuture.completedFuture(null);
        }
        execution.whenComplete((result, failure) -> completeInvocation(run, result, failure));
    }

    private void completeInvocation(TaskRun run, Object result, Throwable failure) {
        TaskEntry entry = run.entry;
        ScheduledTaskEvent.Type event = null;
        Throwable outcome = failure;
        try {
            synchronized (persistenceLock) {
                entry.invocations.remove(run);
                if (persistenceWritable() && run.epoch == persistenceGeneration && current(entry, entry.generation)) {
                    Object previousResult = entry.lastResult;
                    String previousError = entry.lastError;
                    try {
                        if (result != WAIT_FOR_OWNER) {
                            entry.invocationPending = entry.invocations.stream().anyMatch(other -> other.started);
                        }
                        boolean stop = false;
                        if (!run.cancellation.isCancelled() && result != WAIT_FOR_OWNER) {
                            if (failure == null) {
                                entry.lastResult = result;
                                entry.lastError = "";
                                event = ScheduledTaskEvent.Type.COMPLETED;
                            } else {
                                entry.lastError = failureMessage(failure);
                                event = ScheduledTaskEvent.Type.FAILED;
                                stop = entry.schedule.failurePolicy() == ScheduleDefinition.FailurePolicy.STOP;
                            }
                        }
                        if (stop) {
                            terminate(entry, State.FAILED);
                        } else {
                            persistLocked();
                        }
                        run.settled = true;
                    } catch (RuntimeException persistenceFailure) {
                        entry.invocationPending = true;
                        entry.lastResult = previousResult;
                        entry.lastError = previousError;
                        if (entry.state == State.ACTIVE) {
                            schedule(entry, 1000L);
                        }
                        throw persistenceFailure;
                    }
                }
            }
            if (event != null) {
                publishLifecycle(entry, event);
            }
        } catch (RuntimeException completionFailure) {
            if (outcome != null) {
                completionFailure.addSuppressed(outcome);
            }
            outcome = completionFailure;
        }
        if (outcome == null) {
            run.completion.complete(result);
        } else {
            run.completion.completeExceptionally(outcome);
        }
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
            entry.invocations.forEach(run -> run.cancellation.cancel());
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
        entry.wake++;
        ScheduledFuture<?> future = entry.future;
        if (future != null) {
            future.cancel(false);
            entry.future = null;
        }
    }

    private TaskSnapshot snapshot(TaskEntry entry) {
        return snapshot(entry, entry.nextRun);
    }

    private TaskSnapshot snapshot(TaskEntry entry, long nextRun) {
        long now = clock.millis();
        long remaining = entry.kind == Kind.TIMER ? remainingTimer(entry)
            : entry.state == State.PAUSED ? entry.remainingAtPause : Math.max(0L, entry.nextRun - now);
        long elapsed = entry.kind == Kind.TIMER ? Math.max(0L, entry.duration - remaining) : 0L;
        double progress = entry.kind == Kind.TIMER && entry.duration > 0L ? Math.clamp((double) elapsed / entry.duration, 0D, 1D) : 0D;
        return new TaskSnapshot(entry.taskId, entry.kind, entry.key.definitionId(), entry.key.scope(), entry.key.ownerId(), entry.owner.value(),
            entry.persistent, entry.state, entry.generation, entry.createdAt, nextRun, entry.lastRun, entry.runCount, entry.duration,
            remaining, elapsed, progress, entry.lastResult, entry.lastError);
    }

    private long remainingTimer(TaskEntry entry) {
        if (entry.state == State.PAUSED) {
            return entry.remainingAtPause;
        }
        return entry.state == State.ACTIVE ? Math.max(0L, entry.deadline - clock.millis()) : 0L;
    }

    private TaskSnapshot inactive(AutomationInstanceKey key) {
        Kind kind = switch (key.kind()) {
            case TIMER -> Kind.TIMER;
            case SCHEDULE -> Kind.SCHEDULE;
            case VARIABLE -> throw new IllegalArgumentException("Variables cannot identify automation tasks");
        };
        return new TaskSnapshot("", kind, key.definitionId(), key.scope(), key.ownerId(), null, false, State.INACTIVE,
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

    private void publish(Event event) {
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
                persistenceGeneration++;
                persistenceState = PersistenceState.QUIESCED;
                tasks.values().forEach(entry -> entry.invocations.forEach(run -> run.cancellation.cancel()));
            } catch (IOException | RuntimeException failure) {
                persistenceState = PersistenceState.OPEN;
                for (TaskEntry entry : instances.values()) {
                    if (entry.state == State.ACTIVE) {
                        schedule(entry, Math.max(1000L, entry.nextRun - clock.millis()));
                    }
                }
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
            entry.state, entry.generation, entry.createdAt, entry.invocationPending ? entry.pendingNextRun : entry.nextRun,
            entry.lastRun, entry.runCount, entry.duration,
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
        private volatile long pendingNextRun;
        private volatile ServerResourceLocator targetLocator;
        private volatile long wake;
        private volatile ScheduledFuture<?> future;
        private final List<TaskRun> invocations = new ArrayList<>();
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

    private static final class TaskRun {
        private final TaskEntry entry;
        private final long epoch;
        private final CompletableFuture<Void> previous;
        private final RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        private final CompletableFuture<Object> completion = new CompletableFuture<>();
        private boolean started;
        private boolean settled;

        private TaskRun(TaskEntry entry, long epoch, CompletableFuture<Void> previous) {
            this.entry = entry;
            this.epoch = epoch;
            this.previous = previous;
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
