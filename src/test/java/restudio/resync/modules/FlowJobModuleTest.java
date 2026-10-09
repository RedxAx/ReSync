package restudio.resync.modules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowJobReference;
import restudio.resync.ReSync;
import restudio.resync.flow.jobs.FlowJobCompletedEvent;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowJobModuleTest {
    private TestReSync plugin;
    private ModuleContext context;
    private FlowJobModule module;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.loadSimple(TestReSync.class);
        context = new ModuleContext(plugin, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        if (module != null) {
            module.stop(context);
        }
        MockBukkit.unmock();
    }

    @Test
    void listenerFailureTerminalizesCompletionWithoutRetryingTheEvent() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("listener failure");
        module = new FlowJobModule(event -> {
            dispatches.incrementAndGet();
            throw failure;
        });
        module.initialize(context);
        FlowJobRegistry registry = context.getService(FlowJobRegistry.class);
        FlowJobReference<?> job = registry.create("module", "flow:test");
        registry.start(job);
        registry.succeed(job, null);

        Consumer<FlowJobReference.Snapshot<?>> listener = completionListener();
        FlowJobReference.Snapshot<?> snapshot = job.snapshot();
        listener.accept(snapshot);
        listener.accept(snapshot);
        MockBukkit.getMock().getScheduler().performOneTick();
        module.onTick();
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(1, dispatches.get());
    }

    @Test
    void availabilityFailureBeforeDispatchRetainsCompletionForRetry() {
        AtomicInteger availabilityChecks = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        module = new FlowJobModule(new FlowJobModule.CompletionEventDispatcher() {
            @Override
            public void verifyAvailable() {
                if (availabilityChecks.incrementAndGet() == 1) {
                    throw new IllegalStateException("dispatch unavailable");
                }
            }

            @Override
            public void dispatch(FlowJobCompletedEvent event) {
                dispatches.incrementAndGet();
            }
        });
        module.initialize(context);
        FlowJobRegistry registry = context.getService(FlowJobRegistry.class);
        FlowJobReference<?> job = registry.create("module", "flow:test");
        registry.start(job);
        registry.succeed(job, null);

        module.onTick();
        MockBukkit.getMock().getScheduler().performOneTick();
        module.onTick();
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(2, availabilityChecks.get());
        assertEquals(1, dispatches.get());
    }

    @Test
    void moduleShutdownWaitsForPhysicalJobTermination() throws Exception {
        module = new FlowJobModule();
        context = new ModuleContext(null, null, null, null, null, null, null, null, null, null, null, null, null);
        module.initialize(context);
        FlowJobRegistry registry = context.getService(FlowJobRegistry.class);
        var job = registry.create("module", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        registry.bind(job, () -> {
            cancellationStarted.countDown();
            try {
                if (!releaseCancellation.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Cancellation Release Timed Out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }, termination);

        module.prepareStop(context);
        CompletableFuture<Void> finish = module.finishStopAsync(context).toCompletableFuture();

        assertEquals(FlowJobRegistry.State.CLOSED, registry.state());
        assertFalse(finish.isDone());
        assertEquals(1, registry.physicalTaskCount());
        assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));

        termination.complete(null);
        assertEquals(0, registry.physicalTaskCount());
        assertFalse(finish.isDone());

        releaseCancellation.countDown();
        assertThrows(CompletionException.class, finish::join);

        assertEquals(0, registry.physicalTaskCount());
        assertFalse(registry.health().available());
        assertTrue(registry.health().failures().isEmpty());
    }

    @Test
    void shutdownPublicationCanRetryWithoutRepeatingDeliveredCompletions() {
        AtomicBoolean available = new AtomicBoolean();
        AtomicInteger dispatches = new AtomicInteger();
        module = new FlowJobModule(new FlowJobModule.CompletionEventDispatcher() {
            @Override
            public void verifyAvailable() {
                if (!available.get()) {
                    throw new IllegalStateException("dispatch unavailable");
                }
            }

            @Override
            public void dispatch(FlowJobCompletedEvent event) {
                dispatches.incrementAndGet();
            }
        });
        module.initialize(context);
        FlowJobRegistry registry = context.getService(FlowJobRegistry.class);
        FlowJobReference<Void> job = registry.create("module", "flow:test");
        registry.succeed(job, null);
        module.prepareStop(context);
        assertThrows(CompletionException.class, () -> module.finishStopAsync(context).toCompletableFuture().join());

        available.set(true);
        module.finishStopAsync(context).toCompletableFuture().join();
        module.finishStopAsync(context).toCompletableFuture().join();
        assertEquals(1, dispatches.get());
    }

    @SuppressWarnings("unchecked")
    private Consumer<FlowJobReference.Snapshot<?>> completionListener() throws ReflectiveOperationException {
        Field field = FlowJobModule.class.getDeclaredField("completionListener");
        field.setAccessible(true);
        return (Consumer<FlowJobReference.Snapshot<?>>) field.get(module);
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }
    }
}
