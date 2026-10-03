package restudio.resync.modules;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;
import restudio.resync.ReSync;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowResourceKey;
import restudio.resync.modules.flow.FlowResourceMutationAdmission;
import restudio.resync.modules.flow.FlowResourceMutationLease;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimePrimaryRefreshTest {
    @TempDir Path scope;
    private TestReSync plugin;
    private ControlledScheduler scheduler;
    private FlowRuntimeModule module;
    private ExecutorService workers;
    private FlowStorage commandStorage;
    private FlowExecutor commandExecutor;
    private GlobalTriggers commandBindings;
    private AssetPersistenceGate persistenceGate;
    private AssetTransactionCoordinator transactions;

    @BeforeEach
    void setUp() throws Exception {
        ControlledServer server = MockBukkit.mock(new ControlledServer());
        scheduler = server.getScheduler();
        plugin = MockBukkit.loadSimple(TestReSync.class);
        module = new FlowRuntimeModule();
        Field context = FlowRuntimeModule.class.getDeclaredField("moduleContext");
        context.setAccessible(true);
        context.set(module, new ModuleContext(plugin, null, null, null, null, null, null, null, null, null, null, null, null));
        workers = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (scheduler != null) {
                scheduler.release.countDown();
            }
            if (module != null) {
                module.closeLiveRefreshAdmission();
            }
        } finally {
            try {
                if (commandExecutor != null) commandExecutor.cancelPendingTasks();
                if (workers != null) {
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
                }
            } finally {
                try {
                    if (commandBindings != null) {
                        commandBindings.shutdownRuntimeCommands();
                        commandBindings.getTriggerDispatcher().shutdown();
                    }
                    if (commandExecutor != null) {
                        commandExecutor.cancelPendingTasks();
                        commandExecutor.shutdown();
                    }
                    if (persistenceGate != null) persistenceGate.quiesce();
                    if (transactions != null) transactions.close();
                } finally {
                    MockBukkit.unmock();
                }
            }
        }
    }

    @Test
    void aCommandRefreshUsesTheSharedHandoffAndLateMutationsStayDurableWithoutRegistering() throws Exception {
        initializeCommandBindings(true);
        CompletableFuture<Void> admitted = CompletableFuture.runAsync(
            () -> commandStorage.saveGraph(commandGraph("first", "before-stop")), workers);
        await(scheduler.scheduled);
        assertFalse(admitted.isDone());
        assertNull(Bukkit.getCommandMap().getCommand("before-stop"));

        scheduler.performOneTick();
        admitted.get(2, TimeUnit.SECONDS);
        assertNotNull(Bukkit.getCommandMap().getCommand("before-stop"));
        module.closeLiveRefreshAdmission();
        assertNull(Bukkit.getCommandMap().getCommand("before-stop"));

        CompletableFuture.runAsync(() -> commandStorage.saveGraph(commandGraph("late", "after-stop")), workers)
            .get(2, TimeUnit.SECONDS);
        scheduler.performOneTick();

        assertNotNull(commandStorage.getGraph("command", "late"));
        assertNull(Bukkit.getCommandMap().getCommand("after-stop"));
    }

    @Test
    void shutdownSuppressesAnAlreadyQueuedCommandRefreshAndAConcurrentLateMutation() throws Exception {
        initializeCommandBindings(true);
        commandStorage.saveGraph(commandGraph("current", "before-stop"));
        assertNotNull(Bukkit.getCommandMap().getCommand("before-stop"));
        FlowGraph changed = commandStorage.getGraph("command", "current");
        changed.getNodes().get("start").getInputValues().put("command", "queued-command");
        CompletableFuture<Void> queued = CompletableFuture.runAsync(() -> commandStorage.saveGraph(changed), workers);
        await(scheduler.scheduled);
        assertFalse(queued.isDone());
        assertNull(Bukkit.getCommandMap().getCommand("queued-command"));

        Bukkit.getPluginManager().disablePlugin(plugin);
        scheduler.cancelTasks(plugin);
        CompletableFuture<Void> late = CompletableFuture.runAsync(() -> {
            await(scheduler.release);
            commandStorage.saveGraph(commandGraph("late", "late-command"));
        }, workers);
        module.closeLiveRefreshAdmission();
        scheduler.release.countDown();
        queued.get(2, TimeUnit.SECONDS);
        late.get(2, TimeUnit.SECONDS);
        scheduler.performOneTick();

        assertEquals("queued-command", commandStorage.getGraph("command", "current").getNodes().get("start").getInputValues().get("command"));
        assertNotNull(commandStorage.getGraph("command", "late"));
        assertNull(Bukkit.getCommandMap().getCommand("before-stop"));
        assertNull(Bukkit.getCommandMap().getCommand("queued-command"));
        assertNull(Bukkit.getCommandMap().getCommand("late-command"));
    }

    @Test
    void interruptingACompatibilityRefreshWaitCannotReleaseItsPhysicalCommandCallback() throws Exception {
        initializeCommandBindings(false);
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
            thread.set(Thread.currentThread());
            try {
                commandStorage.saveGraph(commandGraph("compatibility", "tracked-command"));
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, workers);
        await(scheduler.scheduled);

        thread.get().interrupt();
        assertThrows(TimeoutException.class, () -> worker.get(100, TimeUnit.MILLISECONDS));
        assertNull(Bukkit.getCommandMap().getCommand("tracked-command"));
        scheduler.performOneTick();
        worker.get(2, TimeUnit.SECONDS);

        assertTrue(interrupted.get());
        assertNotNull(Bukkit.getCommandMap().getCommand("tracked-command"));
        commandBindings.shutdownRuntimeCommands();
        scheduler.performOneTick();
        assertNull(Bukkit.getCommandMap().getCommand("tracked-command"));
    }

    @Test
    void shutdownBetweenTheBindingCheckAndHandoffDoesNotFailOrRegisterCommands() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        initializeCommandBindings(true, action -> {
            if (!Bukkit.isPrimaryThread()) {
                entered.countDown();
                await(release);
            }
            refresh(action);
        });
        commandStorage.saveGraph(commandGraph("current", "before-stop"));
        assertNotNull(Bukkit.getCommandMap().getCommand("before-stop"));
        CompletableFuture<Void> worker = CompletableFuture.runAsync(commandBindings::refreshBindings, workers);
        await(entered);

        try {
            module.closeLiveRefreshAdmission();
        } finally {
            release.countDown();
        }
        worker.get(2, TimeUnit.SECONDS);
        scheduler.performOneTick();

        assertNotNull(commandStorage.getGraph("command", "current"));
        assertNull(Bukkit.getCommandMap().getCommand("before-stop"));
    }

    @Test
    void shutdownDrainsAQueuedRefreshAndItsRealLeaseAfterPluginDisable() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.tryAcquire(key).orElseThrow();
        FlowResourceMutationLease.DeferredCompletion deferred = lease.defer(() -> {
            assertTrue(Bukkit.isPrimaryThread());
            balance.addAndGet(25);
        });
        lease.close();
        CompletableFuture<Void> worker = work(deferred::run);
        await(scheduler.scheduled);
        assertTrue(admission.isAdmitted(key));

        Bukkit.getPluginManager().disablePlugin(plugin);
        scheduler.cancelTasks(plugin);
        module.closeLiveRefreshAdmission();
        worker.get(2, TimeUnit.SECONDS);

        assertEquals(125, balance.get());
        assertFalse(admission.isAdmitted(key));
        scheduler.performOneTick();
        module.closeLiveRefreshAdmission();
        assertEquals(125, balance.get());
    }

    @Test
    void aNormallyScheduledRefreshFinishesBeforeWorkerSettlement() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Void> worker = work(() -> {
            assertTrue(Bukkit.isPrimaryThread());
            balance.addAndGet(25);
        });
        await(scheduler.scheduled);
        assertFalse(worker.isDone());

        scheduler.performOneTick();
        worker.get(2, TimeUnit.SECONDS);
        module.closeLiveRefreshAdmission();

        assertEquals(125, balance.get());
    }

    @Test
    void shutdownCanDrainBeforeTheBukkitTaskIsRegisteredWithoutRepeatingEffects() throws Exception {
        scheduler.pause = true;
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Void> worker = work(() -> balance.addAndGet(25));
        await(scheduler.entered);
        try {
            module.closeLiveRefreshAdmission();
            assertEquals(125, balance.get());
            assertFalse(worker.isDone());
        } finally {
            scheduler.release.countDown();
        }
        worker.get(2, TimeUnit.SECONDS);
        scheduler.performOneTick();

        assertEquals(125, balance.get());
    }

    @Test
    void closingAdmissionRejectsNewWorkerHandoffsButLetsPrimaryFinalizersRun() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        module.closeLiveRefreshAdmission();

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> work(() -> balance.addAndGet(25)).get(2, TimeUnit.SECONDS));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        scheduler.performOneTick();
        assertEquals(100, balance.get());
        refresh(() -> balance.addAndGet(1));
        assertEquals(101, balance.get());
    }

    @Test
    void schedulerRejectionCannotLeaveAPendingCallbackThatRunsDuringShutdown() throws Exception {
        scheduler.reject = true;
        AtomicInteger balance = new AtomicInteger(100);

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> work(() -> balance.addAndGet(25)).get(2, TimeUnit.SECONDS));

        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        module.closeLiveRefreshAdmission();
        scheduler.performOneTick();
        assertEquals(100, balance.get());
    }

    @Test
    void aFailedRefreshDoesNotPreventOtherQueuedEffectsFromSettling() throws Exception {
        scheduler.scheduled = new CountDownLatch(2);
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Void> failed = work(() -> {
            throw new IllegalStateException("Refresh failed");
        });
        CompletableFuture<Void> successful = work(() -> balance.addAndGet(25));
        await(scheduler.scheduled);

        module.closeLiveRefreshAdmission();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
        assertEquals("Refresh failed", failure.getCause().getMessage());
        successful.get(2, TimeUnit.SECONDS);
        scheduler.performOneTick();
        assertEquals(125, balance.get());
    }

    @Test
    void aQueuedTimeoutSuppressesTheLateScheduledEffect() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Void> worker = work(() -> balance.addAndGet(25));
        await(scheduler.scheduled);
        Object pending = pending();

        assertThrows(TimeoutException.class, () -> awaitOwner(pending, 20));
        assertThrows(ExecutionException.class, () -> worker.get(2, TimeUnit.SECONDS));
        scheduler.performOneTick();
        module.closeLiveRefreshAdmission();

        assertEquals(100, balance.get());
    }

    @Test
    void aRunningTimeoutKeepsPhysicalOwnershipUntilTheRefreshEnds() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> worker = work(() -> {
            entered.countDown();
            await(release);
            balance.addAndGet(25);
        });
        await(scheduler.scheduled);
        Object pending = pending();
        CompletableFuture<Void> observer = CompletableFuture.runAsync(() -> {
            await(entered);
            awaitOwner(pending, 20);
        }, workers);
        CompletableFuture<Void> controller = CompletableFuture.runAsync(() -> {
            await(entered);
            try {
                assertThrows(TimeoutException.class, () -> observer.get(100, TimeUnit.MILLISECONDS));
                assertFalse(worker.isDone());
                assertEquals(100, balance.get());
            } finally {
                release.countDown();
            }
        }, workers);

        scheduler.performOneTick();
        controller.get(2, TimeUnit.SECONDS);
        observer.get(2, TimeUnit.SECONDS);
        worker.get(2, TimeUnit.SECONDS);

        assertEquals(125, balance.get());
    }

    @Test
    void anInterruptedRunningWorkerRetainsTheLeaseUntilPhysicalCompletion() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.tryAcquire(key).orElseThrow();
        FlowResourceMutationLease.DeferredCompletion deferred = lease.defer(() -> {
            entered.countDown();
            await(release);
            balance.addAndGet(25);
        });
        lease.close();
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
            thread.set(Thread.currentThread());
            try {
                refresh(deferred::run);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, workers);
        await(scheduler.scheduled);
        CompletableFuture<Void> controller = CompletableFuture.runAsync(() -> {
            await(entered);
            thread.get().interrupt();
            try {
                assertThrows(TimeoutException.class, () -> worker.get(100, TimeUnit.MILLISECONDS));
                assertTrue(admission.isAdmitted(key));
            } finally {
                release.countDown();
            }
        }, workers);

        scheduler.performOneTick();
        controller.get(2, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> worker.get(2, TimeUnit.SECONDS));

        assertTrue(interrupted.get());
        assertFalse(admission.isAdmitted(key));
        assertEquals(125, balance.get());
    }

    private CompletableFuture<Void> work(Runnable action) {
        return CompletableFuture.runAsync(() -> refresh(action), workers);
    }

    private void initializeCommandBindings(boolean shared) throws Exception {
        initializeCommandBindings(shared, this::refresh);
    }

    private void initializeCommandBindings(boolean shared, Consumer<Runnable> refresh) throws Exception {
        persistenceGate = new AssetPersistenceGate(scope);
        transactions = AssetTransactionCoordinator.open(scope.resolve("assets"), new Gson());
        commandStorage = new FlowStorage(scope.toFile(), LegacyRuntimeActivationGate.runtime(scope), persistenceGate, transactions);
        commandExecutor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        TriggerRegistry registry = new TriggerRegistry(plugin);
        commandBindings = shared
            ? new GlobalTriggers(commandStorage, commandExecutor, registry, null, false, refresh)
            : new GlobalTriggers(commandStorage, commandExecutor, registry, null, false);
        if (shared) {
            Field triggers = FlowRuntimeModule.class.getDeclaredField("globalTriggers");
            triggers.setAccessible(true);
            triggers.set(module, commandBindings);
        }
        commandBindings.activateRuntimeBindings();
        scheduler.scheduled = new CountDownLatch(1);
    }

    private FlowGraph commandGraph(String id, String command) {
        FlowGraph graph = new FlowGraph(id, Map.of("start", new FlowNode("event.resync.command", 0, 0, Map.of("command", command))),
            List.of(), List.of());
        graph.setResourceType("command");
        return graph;
    }

    private void refresh(Runnable action) {
        try {
            Method method = FlowRuntimeModule.class.getDeclaredMethod("runLiveRefresh", Runnable.class);
            method.setAccessible(true);
            method.invoke(module, action);
        } catch (InvocationTargetException failure) {
            rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object pending() throws Exception {
        Field fence = FlowRuntimeModule.class.getDeclaredField("primaryRefreshFence");
        Field pending = FlowRuntimeModule.class.getDeclaredField("primaryRefreshes");
        fence.setAccessible(true);
        pending.setAccessible(true);
        synchronized (fence.get(module)) {
            return ((Set<?>) pending.get(module)).iterator().next();
        }
    }

    private void awaitOwner(Object pending, long millis) {
        try {
            Method method = pending.getClass().getDeclaredMethod("await", long.class, TimeUnit.class);
            method.setAccessible(true);
            method.invoke(pending, millis, TimeUnit.MILLISECONDS);
        } catch (InvocationTargetException failure) {
            rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void rethrow(Throwable failure) {
        FlowRuntimePrimaryRefreshTest.<RuntimeException>raise(failure);
    }

    private static <T extends Throwable> void raise(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Refresh handoff did not reach its gate");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class ControlledServer extends ServerMock {
        private final ControlledScheduler scheduler = new ControlledScheduler();

        @Override
        public ControlledScheduler getScheduler() {
            return scheduler;
        }
    }

    private static final class ControlledScheduler extends BukkitSchedulerMock {
        private volatile CountDownLatch scheduled = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean pause;
        private volatile boolean reject;

        @Override
        public BukkitTask runTask(Plugin owner, Runnable action) {
            if (reject) throw new RejectedExecutionException("Scheduler admission failed");
            if (pause) {
                entered.countDown();
                await(release);
            }
            BukkitTask task = super.runTask(owner, action);
            scheduled.countDown();
            return task;
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }
}
