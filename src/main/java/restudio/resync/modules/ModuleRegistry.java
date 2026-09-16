package restudio.resync.modules;

import org.bukkit.Bukkit;
import restudio.resync.core.Session;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class ModuleRegistry {
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    public interface ModuleChangeListener {
        void onModuleRegistered(Module module);

        void onModuleUnregistered(String moduleId, Set<String> channels);
    }

    private final ConcurrentHashMap<String, Module> modules;
    private final ConcurrentHashMap<String, Module> channels;
    private final List<Module> initializedOrder;
    private final List<Module> startOrder;
    private final List<String> registrationOrder;
    private final List<ModuleChangeListener> listeners;
    private final Set<Module> preparedShutdownModules;
    private final Set<Module> finishedShutdownModules;
    private LifecycleState lifecycleState;
    private CompletionStage<Void> shutdownAttempt;
    private List<Module> preparedShutdownOrder = List.of();

    private enum LifecycleState {
        NEW,
        INITIALIZING,
        INITIALIZED,
        STARTING,
        STARTED,
        FAILED,
        SHUTTING_DOWN,
        STOPPED
    }

    public ModuleRegistry() {
        this.modules = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        this.initializedOrder = new ArrayList<>();
        this.startOrder = new ArrayList<>();
        this.registrationOrder = new CopyOnWriteArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.preparedShutdownModules = Collections.newSetFromMap(new IdentityHashMap<>());
        this.finishedShutdownModules = Collections.newSetFromMap(new IdentityHashMap<>());
        this.lifecycleState = LifecycleState.NEW;
    }

    public synchronized void registerModule(Module module) {
        if (module == null) {
            return;
        }
        requireStaticRegistrationState();
        registerModuleInternal(module);
    }

    public synchronized void registerRuntimeModule(Module module, ModuleContext context) {
        long lifecycleStarted = TemporaryLifecycleDiagnostics.start();
        long lifecycleCpuStarted = currentThreadCpuNanos();
        validateModule(module);
        if (lifecycleState != LifecycleState.STARTED) {
            throw new IllegalStateException("Runtime module registration requires started modules");
        }
        if (modules.containsKey(module.getModuleId())) {
            throw new IllegalArgumentException("Duplicate module id: " + module.getModuleId());
        }
        for (String channel : module.getChannels()) {
            if (channels.containsKey(channel)) {
                throw new IllegalArgumentException("Duplicate channel id: " + channel);
            }
        }
        registerModuleInternal(module);
        for (String channel : module.getChannels()) {
            if (context.getChannelMuxer().getChannel(channel) == null) {
                context.getChannelMuxer().createChannel(channel);
            }
        }
        initializedOrder.add(module);
        try {
            long initializeStarted = TemporaryLifecycleDiagnostics.start();
            long initializeCpuStarted = currentThreadCpuNanos();
            module.initialize(context);
            moduleLifecycleEvent("module_runtime_initialize", module, initializeStarted, initializeCpuStarted, "complete");
            long startStarted = TemporaryLifecycleDiagnostics.start();
            long startCpuStarted = currentThreadCpuNanos();
            module.start(context);
            moduleLifecycleEvent("module_runtime_start", module, startStarted, startCpuStarted, "complete");
            startOrder.add(module);
            notifyRegistered(module);
            moduleLifecycleEvent("module_runtime_register", module, lifecycleStarted, lifecycleCpuStarted, "complete");
        } catch (Throwable failure) {
            moduleLifecycleEvent("module_runtime_register", module, lifecycleStarted, lifecycleCpuStarted, "failed");
            stopModuleForRollback(module, context, failure);
            initializedOrder.remove(module);
            startOrder.remove(module);
            unregisterModule(module.getModuleId());
            for (String channel : module.getChannels()) {
                try {
                    context.getChannelMuxer().removeChannel(channel);
                } catch (Throwable cleanupFailure) {
                    addSuppressedFailure(failure, cleanupFailure);
                }
            }
            throw lifecycleFailure(failure);
        }
    }

    public synchronized void unregisterModule(String moduleId) {
        Module module = modules.remove(moduleId);
        if (module != null) {
            registrationOrder.remove(moduleId);
            for (String channel : module.getChannels()) {
                channels.remove(channel, module);
            }
            initializedOrder.remove(module);
            startOrder.remove(module);
        }
    }

    public synchronized void unregisterRuntimeModule(String moduleId, ModuleContext context) {
        Module module = modules.get(moduleId);
        if (module == null) {
            return;
        }
        Set<String> removedChannels = Set.copyOf(module.getChannels());
        context.getSessionManager().cleanupModuleChannels(removedChannels, module);
        module.stop(context);
        unregisterModule(moduleId);
        for (String channel : removedChannels) {
            context.getChannelMuxer().removeChannel(channel);
        }
        notifyUnregistered(moduleId, removedChannels);
    }

    public synchronized void initializeModules(ModuleContext context) {
        if (lifecycleState == LifecycleState.INITIALIZED || lifecycleState == LifecycleState.STARTING
            || lifecycleState == LifecycleState.STARTED) {
            return;
        }
        requireLifecycleState(LifecycleState.NEW, "initialize modules");
        lifecycleState = LifecycleState.INITIALIZING;
        long lifecycleStarted = TemporaryLifecycleDiagnostics.start();
        long lifecycleCpuStarted = currentThreadCpuNanos();
        try {
            long orderStarted = TemporaryLifecycleDiagnostics.start();
            long orderCpuStarted = currentThreadCpuNanos();
            List<Module> ordered = resolveOrder();
            TemporaryLifecycleDiagnostics.event("module_initialize_stage", orderStarted,
                Map.of("operation", "resolveOrder", "moduleCount", ordered.size(), "outcome", "complete",
                    "participantTimings", timing(orderStarted, orderCpuStarted)));
            for (Module module : ordered) {
                if (!module.isEnabledByDefault()) {
                    continue;
                }
                long channelsStarted = TemporaryLifecycleDiagnostics.start();
                long channelsCpuStarted = currentThreadCpuNanos();
                for (String channel : module.getChannels()) {
                    if (channel != null && !channel.isBlank() && context.getChannelMuxer().getChannel(channel) == null) {
                        context.getChannelMuxer().createChannel(channel);
                    }
                }
                TemporaryLifecycleDiagnostics.event("module_initialize_stage", channelsStarted,
                    Map.of("moduleId", module.getModuleId(), "operation", "channels", "count", module.getChannels().size(),
                        "outcome", "complete", "participantTimings", timing(channelsStarted, channelsCpuStarted)));
                initializedOrder.add(module);
                long initialized = TemporaryLifecycleDiagnostics.start();
                long initializedCpu = currentThreadCpuNanos();
                try {
                    module.initialize(context);
                    moduleLifecycleEvent("module_initialize", module, initialized, initializedCpu, "complete");
                } catch (Throwable failure) {
                    moduleLifecycleEvent("module_initialize", module, initialized, initializedCpu, "failed");
                    throw failure;
                }
            }
            lifecycleState = LifecycleState.INITIALIZED;
            TemporaryLifecycleDiagnostics.event("module_initialize_all", lifecycleStarted,
                Map.of("moduleCount", initializedOrder.size(), "outcome", "complete",
                    "participantTimings", timing(lifecycleStarted, lifecycleCpuStarted)));
        } catch (Throwable failure) {
            TemporaryLifecycleDiagnostics.event("module_initialize_all", lifecycleStarted,
                Map.of("moduleCount", initializedOrder.size(), "outcome", "failed",
                    "participantTimings", timing(lifecycleStarted, lifecycleCpuStarted)));
            stopModulesForRollback(initializedOrder, context, failure);
            initializedOrder.clear();
            startOrder.clear();
            lifecycleState = LifecycleState.FAILED;
            throw lifecycleFailure(failure);
        }
    }

    public synchronized void startModules(ModuleContext context) {
        if (lifecycleState == LifecycleState.STARTED) {
            return;
        }
        requireLifecycleState(LifecycleState.INITIALIZED, "start modules");
        lifecycleState = LifecycleState.STARTING;
        long lifecycleStarted = TemporaryLifecycleDiagnostics.start();
        long lifecycleCpuStarted = currentThreadCpuNanos();
        try {
            for (Module module : initializedOrder) {
                long started = TemporaryLifecycleDiagnostics.start();
                long startedCpu = currentThreadCpuNanos();
                try {
                    module.start(context);
                    moduleLifecycleEvent("module_start", module, started, startedCpu, "complete");
                } catch (Throwable failure) {
                    moduleLifecycleEvent("module_start", module, started, startedCpu, "failed");
                    throw failure;
                }
                startOrder.add(module);
            }
            lifecycleState = LifecycleState.STARTED;
            TemporaryLifecycleDiagnostics.event("module_start_all", lifecycleStarted,
                Map.of("moduleCount", startOrder.size(), "outcome", "complete",
                    "participantTimings", timing(lifecycleStarted, lifecycleCpuStarted)));
        } catch (Throwable failure) {
            TemporaryLifecycleDiagnostics.event("module_start_all", lifecycleStarted,
                Map.of("moduleCount", startOrder.size(), "outcome", "failed",
                    "participantTimings", timing(lifecycleStarted, lifecycleCpuStarted)));
            stopModulesForRollback(initializedOrder, context, failure);
            initializedOrder.clear();
            startOrder.clear();
            lifecycleState = LifecycleState.FAILED;
            throw lifecycleFailure(failure);
        }
    }

    public void shutdownModules(ModuleContext context) {
        shutdownModulesAsync(context).toCompletableFuture().join();
    }

    public synchronized CompletionStage<Void> shutdownModulesAsync(ModuleContext context) {
        if (shutdownAttempt != null) {
            return shutdownAttempt;
        }
        if (lifecycleState == LifecycleState.STOPPED) {
            return CompletableFuture.completedFuture(null);
        }
        if (lifecycleState == LifecycleState.INITIALIZING || lifecycleState == LifecycleState.STARTING) {
            return CompletableFuture.failedFuture(new IllegalStateException("Module lifecycle is still starting"));
        }
        lifecycleState = LifecycleState.SHUTTING_DOWN;
        List<Module> shutdownOrder = List.copyOf(initializedOrder);
        CompletionStage<List<Module>> admissions = admitModuleStopsOnPrimary(shutdownOrder, context);
        CompletableFuture<Void> attempt = admissions.thenCompose(prepared -> awaitModuleStops(prepared, context))
            .toCompletableFuture();
        shutdownAttempt = attempt;
        attempt.whenComplete((unused, failure) -> {
            synchronized (ModuleRegistry.this) {
                if (failure == null) {
                    initializedOrder.clear();
                    startOrder.clear();
                    preparedShutdownOrder = List.of();
                    preparedShutdownModules.clear();
                    finishedShutdownModules.clear();
                    lifecycleState = LifecycleState.STOPPED;
                } else if (shutdownAttempt == attempt) {
                    shutdownAttempt = null;
                }
            }
        });
        return attempt;
    }

    private CompletionStage<List<Module>> admitModuleStopsOnPrimary(List<Module> shutdownOrder,
                                                                    ModuleContext context) {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(admitModuleStops(shutdownOrder, context));
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (context == null || context.getPlugin() == null || !context.getPlugin().isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Bukkit Primary Module Stop Cannot Be Scheduled After Plugin Disable"));
        }
        CompletableFuture<List<Module>> admission = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                try {
                    admission.complete(admitModuleStops(shutdownOrder, context));
                } catch (RuntimeException exception) {
                    admission.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            admission.completeExceptionally(exception);
        }
        return admission;
    }

    private List<Module> admitModuleStops(List<Module> shutdownOrder, ModuleContext context) {
        List<Module> admissions;
        for (int i = shutdownOrder.size() - 1; i >= 0; i--) {
            Module module = shutdownOrder.get(i);
            synchronized (this) {
                if (preparedShutdownModules.contains(module)) {
                    continue;
                }
                preparedShutdownModules.add(module);
                List<Module> next = new ArrayList<>(preparedShutdownOrder);
                next.add(module);
                preparedShutdownOrder = List.copyOf(next);
            }
            try {
                module.prepareStop(context);
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    preparedShutdownModules.remove(module);
                    List<Module> next = new ArrayList<>(preparedShutdownOrder);
                    next.removeIf(prepared -> prepared == module);
                    preparedShutdownOrder = List.copyOf(next);
                }
                throw failure;
            }
        }
        synchronized (this) {
            admissions = List.copyOf(preparedShutdownOrder);
        }
        return admissions;
    }

    private CompletionStage<Void> awaitModuleStops(List<Module> modules, ModuleContext context) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (Module module : modules) {
            result = result.thenCompose(ignored -> {
                synchronized (ModuleRegistry.this) {
                    if (finishedShutdownModules.contains(module)) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
                CompletionStage<Void> finish = module.finishStopAsync(context);
                return finish == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Module shutdown returned no completion"))
                    : finish.thenRun(() -> {
                        synchronized (ModuleRegistry.this) {
                            finishedShutdownModules.add(module);
                        }
                    });
            }).toCompletableFuture();
        }
        return result;
    }

    public Module getModule(String moduleId) {
        return modules.get(moduleId);
    }

    public Module getModuleByChannel(String channelId) {
        return channels.get(channelId);
    }

    public boolean hasModule(String moduleId) {
        return modules.containsKey(moduleId);
    }

    public void cleanupSession(Session session) {
        for (Module module : startOrder) {
            module.cleanup(session);
        }
    }

    public void tickAll() {
        for (Module module : startOrder) {
            module.onTick();
        }
    }

    public int getModuleCount() {
        return modules.size();
    }

    public Collection<Module> getModules() {
        return List.copyOf(startOrder);
    }

    public List<String> getInitializationOrder() {
        return resolveOrder().stream().filter(Module::isEnabledByDefault).map(Module::getModuleId).toList();
    }

    public void addListener(ModuleChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ModuleChangeListener listener) {
        listeners.remove(listener);
    }

    private void requireStaticRegistrationState() {
        if (lifecycleState != LifecycleState.NEW) {
            throw new IllegalStateException("Static module registration is closed after lifecycle initialization");
        }
    }

    private void requireLifecycleState(LifecycleState expected, String action) {
        if (lifecycleState != expected) {
            throw new IllegalStateException("Cannot " + action + " while module lifecycle is " + lifecycleState);
        }
    }

    private void registerModuleInternal(Module module) {
        modules.put(module.getModuleId(), module);
        registrationOrder.remove(module.getModuleId());
        registrationOrder.add(module.getModuleId());
        for (String channel : module.getChannels()) {
            if (channel != null && !channel.isBlank()) {
                channels.put(channel, module);
            }
        }
    }

    private void stopModulesForRollback(List<Module> modules, ModuleContext context, Throwable failure) {
        Set<Module> stopped = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = modules.size() - 1; index >= 0; index--) {
            Module module = modules.get(index);
            if (stopped.add(module)) {
                stopModuleForRollback(module, context, failure);
            }
        }
    }

    private void stopModuleForRollback(Module module, ModuleContext context, Throwable failure) {
        try {
            module.stop(context);
        } catch (Throwable cleanupFailure) {
            addSuppressedFailure(failure, cleanupFailure);
        }
    }

    private void addSuppressedFailure(Throwable failure, Throwable cleanupFailure) {
        if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private RuntimeException lifecycleFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        return new IllegalStateException("Module lifecycle failed", failure);
    }

    private void validateModule(Module module) {
        if (module == null || module.getModuleId() == null || module.getModuleId().isBlank()) {
            throw new IllegalArgumentException("Module id is required");
        }
        for (String channel : module.getChannels()) {
            if (channel == null || channel.isBlank()) {
                throw new IllegalArgumentException("Module channel is required");
            }
        }
    }

    private void notifyRegistered(Module module) {
        for (ModuleChangeListener listener : listeners) {
            listener.onModuleRegistered(module);
        }
    }

    private void notifyUnregistered(String moduleId, Set<String> removedChannels) {
        for (ModuleChangeListener listener : listeners) {
            listener.onModuleUnregistered(moduleId, removedChannels);
        }
    }

    private static void moduleLifecycleEvent(String stage, Module module, long started, long cpuStarted, String outcome) {
        TemporaryLifecycleDiagnostics.event(stage, started,
            Map.of("moduleId", module.getModuleId(), "outcome", outcome,
                "participantTimings", timing(started, cpuStarted)));
    }

    private static Map<String, Long> timing(long started, long cpuStarted) {
        return Map.of("wallMs", elapsedMillis(started), "cpuMs", currentThreadCpuMillis(cpuStarted));
    }

    private static long elapsedMillis(long started) {
        return started <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    private static long currentThreadCpuNanos() {
        return THREAD_CPU.isCurrentThreadCpuTimeSupported() ? Math.max(0L, THREAD_CPU.getCurrentThreadCpuTime()) : 0L;
    }

    private static long currentThreadCpuMillis(long started) {
        return started <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, currentThreadCpuNanos() - started));
    }

    private List<Module> resolveOrder() {
        List<Module> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(registrationOrder);
        while (!stack.isEmpty()) {
            visit(stack.pop(), ordered, visited, visiting);
        }
        return ordered;
    }

    private void visit(String moduleId, List<Module> ordered, Set<String> visited, Set<String> visiting) {
        if (visited.contains(moduleId)) {
            return;
        }
        if (!visiting.add(moduleId)) {
            throw new IllegalStateException("Circular module dependency at " + moduleId);
        }
        Module module = modules.get(moduleId);
        if (module == null) {
            visiting.remove(moduleId);
            return;
        }
        for (String dependency : module.getMetadata().dependencies()) {
            if (!modules.containsKey(dependency)) {
                throw new IllegalStateException("Missing dependency '" + dependency + "' for module '" + moduleId + "'");
            }
            visit(dependency, ordered, visited, visiting);
        }
        visiting.remove(moduleId);
        visited.add(moduleId);
        ordered.add(module);
    }
}
