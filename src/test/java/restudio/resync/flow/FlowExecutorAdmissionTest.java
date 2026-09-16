package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Bukkit;
import restudio.resync.ReSync;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowExecutorAdmissionTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rootLegacyExecutionHoldsAdmissionUntilTerminalCompletion() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        handlers.register("blocking", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                invocations.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.ASYNC;
            }
        });
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        try {
            CompletableFuture<Void> running = executor.execute(graph(), "start", null, null, Map.of());
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> drained;
            try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
                CompletableFuture<Void> denied = executor.execute(graph(), "start", null, null, Map.of());
                CompletionException failure = assertThrows(CompletionException.class, denied::join);
                FlowExecutor.FlowExecutionException executionFailure = assertInstanceOf(
                    FlowExecutor.FlowExecutionException.class, failure.getCause());
                assertEquals("EXECUTION_FENCED", executionFailure.getCode());
                assertFalse(running.isDone());

                drained = CompletableFuture.runAsync(fence::awaitDrained);
                assertThrows(TimeoutException.class, () -> drained.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                running.get(5, TimeUnit.SECONDS);
                drained.get(5, TimeUnit.SECONDS);
            }

            assertTrue(executor.execute(graph(), "start", null, null, Map.of()).get(5, TimeUnit.SECONDS) == null);
            assertEquals(2, invocations.get());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void nestedAdmissionFencesRemainClosedUntilTheLastFenceCloses() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try (FlowExecutor.AdmissionFence first = executor.fenceAdmissions();
             FlowExecutor.AdmissionFence second = executor.fenceAdmissions()) {
            CompletableFuture<Void> denied = executor.execute(graph(), "start", null, null, Map.of());
            CompletionException failure = assertThrows(CompletionException.class, denied::join);
            assertEquals("EXECUTION_FENCED", assertInstanceOf(FlowExecutor.FlowExecutionException.class, failure.getCause()).getCode());
            first.close();
            denied = executor.execute(graph(), "start", null, null, Map.of());
            failure = assertThrows(CompletionException.class, denied::join);
            assertEquals("EXECUTION_FENCED", assertInstanceOf(FlowExecutor.FlowExecutionException.class, failure.getCause()).getCode());
        }
    }

    @Test
    void runtimeSubflowEntryRespectsAdmissionFence() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        FlowRuntime parentRuntime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of());
        try (FlowExecutor.AdmissionFence ignored = executor.fenceAdmissions()) {
            CompletableFuture<Object> denied = executor.executeSubFlow(parentRuntime, graph(), "start", "value",
                null, null, Map.of());
            CompletionException failure = assertThrows(CompletionException.class, denied::join);
            FlowExecutor.FlowExecutionException executionFailure = assertInstanceOf(
                FlowExecutor.FlowExecutionException.class, failure.getCause());
            assertEquals("EXECUTION_FENCED", executionFailure.getCode());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aMainThreadExecutionWaitingForItsTickIsCancelledBeforeTheSchedulerRetires() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("main_only", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.MAIN;
            }
        });
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        try {
            CompletableFuture<CompletableFuture<Void>> submitted = CompletableFuture.supplyAsync(
                () -> executor.execute(mainOnlyGraph(), "start", null, null, Map.of()));
            CompletableFuture<Void> running = submitted.get(5, TimeUnit.SECONDS);
            assertFalse(running.isDone());
            assertTrue(Bukkit.getScheduler().getPendingTasks().stream().anyMatch(task -> !task.isCancelled()));

            try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
                executor.cancelPendingTasks();
                assertTrue(fence.awaitDrained(Duration.ofSeconds(1)));
            }
            assertFalse(Bukkit.getScheduler().getPendingTasks().stream().anyMatch(task -> !task.isCancelled()));
        } finally {
            executor.shutdown();
        }
    }

    private FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("admission-graph");
        graph.getNodes().put("start", new FlowNode("blocking", 0, 0, Map.of()));
        return graph;
    }

    private FlowGraph mainOnlyGraph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("main-only-graph");
        graph.getNodes().put("start", new FlowNode("main_only", 0, 0, Map.of()));
        return graph;
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
