package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomEventManagerTest {
    private final CustomEventManager manager = CustomEventManager.getInstance();
    private final String eventId = "custom-test-" + UUID.randomUUID();
    private final List<FlowExecutor> executors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        try {
            manager.clearListeners(eventId);
            executors.forEach(FlowExecutor::shutdown);
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void zeroTimeoutPublishesNullPayloadValuesAndContinuesExactlyOnce() {
        AtomicInteger deliveries = new AtomicInteger();
        FlowContext context = context(null, null, pin -> {
            assertEquals("next", pin);
            deliveries.incrementAndGet();
        });
        manager.listen(eventId, new CustomEventManager.Listener(context, "listen", 0));
        CompletableFuture<Void> wait = wait(context);
        assertFalse(wait.isDone());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value", 7);
        payload.put("optional", null);

        manager.emit(eventId, payload);
        payload.put("value", 99);
        Map<String, Object> recorded = manager.getLastEventData(eventId);

        assertEquals(1, deliveries.get());
        assertTrue(wait.isDone());
        assertFalse(wait.isCompletedExceptionally());
        assertEquals(7, context.getRuntime().getNodeOutput("listen", "value"));
        assertEquals(true, context.getRuntime().getNodeOutput("listen", "triggered"));
        assertTrue(context.getRuntime().getEventVariables().containsKey("custom." + eventId + ".optional"));
        assertNull(context.getRuntime().getEventVariables().get("custom." + eventId + ".optional"));
        assertEquals(7, recorded.get("value"));
        assertThrows(UnsupportedOperationException.class, () -> recorded.put("value", 4));

        manager.emit(eventId, Map.of("value", 8));

        assertEquals(1, deliveries.get());
        assertEquals(7, context.getRuntime().getNodeOutput("listen", "value"));
    }

    @Test
    void positiveTimeoutRepeatsUntilExpiryAndNegativeTimeoutWaitsUntilClear() {
        AtomicInteger boundedDeliveries = new AtomicInteger();
        AtomicInteger persistentDeliveries = new AtomicInteger();
        FlowContext bounded = context(null, null, pin -> boundedDeliveries.incrementAndGet());
        FlowContext persistent = context(null, null, pin -> persistentDeliveries.incrementAndGet());
        manager.listen(eventId, new CustomEventManager.Listener(bounded, "listen", 2));
        manager.listen(eventId, new CustomEventManager.Listener(persistent, "listen", -1));

        manager.emit(eventId, Map.of());
        manager.tick();
        manager.emit(eventId, Map.of());
        assertFalse(wait(bounded).isDone());
        manager.tick();
        manager.emit(eventId, Map.of());

        assertEquals(2, boundedDeliveries.get());
        assertEquals(3, persistentDeliveries.get());
        assertTrue(wait(bounded).isDone());
        assertFalse(wait(persistent).isDone());

        manager.clearListeners(eventId);
        manager.tick();
        manager.emit(eventId, Map.of());

        assertTrue(wait(persistent).isDone());
        assertEquals(3, persistentDeliveries.get());
    }

    @Test
    void recursiveEmissionDoesNotRedeliverClaimedWaitersOrChangeTheirPayloads() {
        List<Object> firstPayloads = new ArrayList<>();
        List<Object> secondPayloads = new ArrayList<>();
        List<Object> laterPayloads = new ArrayList<>();
        AtomicReference<FlowContext> firstContext = new AtomicReference<>();
        AtomicReference<FlowContext> secondContext = new AtomicReference<>();
        AtomicReference<FlowContext> laterContext = new AtomicReference<>();
        FlowContext later = context(null, null, pin -> laterPayloads.add(laterContext.get().getRuntime().getNodeOutput("listen", "value")));
        laterContext.set(later);
        FlowContext first = context(null, null, pin -> {
            firstPayloads.add(firstContext.get().getRuntime().getNodeOutput("listen", "value"));
            manager.listen(eventId, new CustomEventManager.Listener(later, "listen", 0));
            manager.emit(eventId, Map.of("value", "inner"));
        });
        firstContext.set(first);
        FlowContext second = context(null, null, pin -> secondPayloads.add(secondContext.get().getRuntime().getNodeOutput("listen", "value")));
        secondContext.set(second);
        manager.listen(eventId, new CustomEventManager.Listener(first, "listen", 0));
        manager.listen(eventId, new CustomEventManager.Listener(second, "listen", 0));

        manager.emit(eventId, Map.of("value", "outer"));

        assertEquals(List.of("outer"), firstPayloads);
        assertEquals(List.of("outer"), secondPayloads);
        assertEquals(List.of("inner"), laterPayloads);
        assertTrue(wait(first).isDone());
        assertTrue(wait(second).isDone());
        assertTrue(wait(later).isDone());
    }

    @Test
    void clearingDuringDeliverySuppressesRemainingSnapshotAndDrainsTheStartedCallback() {
        AtomicInteger secondDeliveries = new AtomicInteger();
        AtomicReference<FlowContext> firstContext = new AtomicReference<>();
        FlowContext second = context(null, null, pin -> secondDeliveries.incrementAndGet());
        FlowContext first = context(null, null, pin -> {
            manager.clearListeners(eventId);
            assertFalse(wait(firstContext.get()).isDone());
            assertTrue(wait(second).isDone());
        });
        firstContext.set(first);
        manager.listen(eventId, new CustomEventManager.Listener(first, "listen", 0));
        manager.listen(eventId, new CustomEventManager.Listener(second, "listen", 0));

        manager.emit(eventId, Map.of("value", "payload"));

        assertTrue(wait(first).isDone());
        assertEquals(0, secondDeliveries.get());
        assertNull(second.getRuntime().getNodeOutput("listen", "value"));
        assertNull(manager.getLastEventData(eventId));
    }

    @Test
    void graphCancellationRetainsInFlightDeliveryAndSuppressesLaterEvents() throws Exception {
        FlowExecutor executor = executor(new HandlerRegistry());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        FlowContext context = context(executor, null, pin -> {
            deliveries.incrementAndGet();
            entered.countDown();
            await(release);
        });
        manager.listen(eventId, new CustomEventManager.Listener(context, "listen", -1));
        CompletableFuture<Void> emission = CompletableFuture.runAsync(() -> manager.emit(eventId, Map.of("value", "first")));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertEquals(1, executor.cancelPendingTasks("custom"));
            assertFalse(wait(context).isDone());
            manager.emit(eventId, Map.of("value", "late"));

            assertEquals(1, deliveries.get());
            assertEquals("first", context.getRuntime().getNodeOutput("listen", "value"));
        } finally {
            release.countDown();
            emission.get(2, TimeUnit.SECONDS);
        }
        assertTrue(wait(context).isCancelled());
    }

    @Test
    void concurrentEmissionsClaimAZeroTimeoutWaiterOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        FlowContext context = context(null, null, pin -> {
            deliveries.incrementAndGet();
            entered.countDown();
            await(release);
        });
        manager.listen(eventId, new CustomEventManager.Listener(context, "listen", 0));
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> manager.emit(eventId, Map.of("value", "first")));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            manager.emit(eventId, Map.of("value", "second"));

            assertEquals(1, deliveries.get());
            assertFalse(wait(context).isDone());
            assertEquals("first", context.getRuntime().getNodeOutput("listen", "value"));
        } finally {
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        }
        assertTrue(wait(context).isDone());
        assertFalse(wait(context).isCompletedExceptionally());
    }

    @Test
    void concurrentRecursiveListenersDrainInOrderWithoutHoldingEachOthersCallbacks() throws Exception {
        String secondEvent = eventId + "-second";
        CountDownLatch started = new CountDownLatch(2);
        List<Object> firstPayloads = new CopyOnWriteArrayList<>();
        List<Object> secondPayloads = new CopyOnWriteArrayList<>();
        AtomicReference<FlowContext> firstContext = new AtomicReference<>();
        AtomicReference<FlowContext> secondContext = new AtomicReference<>();
        FlowContext first = context(null, null, pin -> {
            Object value = firstContext.get().getRuntime().getNodeOutput("listen", "value");
            firstPayloads.add(value);
            if ("first-start".equals(value)) {
                started.countDown();
                await(started);
                manager.emit(secondEvent, Map.of("value", "from-first"));
            }
        });
        firstContext.set(first);
        FlowContext second = context(null, null, pin -> {
            Object value = secondContext.get().getRuntime().getNodeOutput("listen", "value");
            secondPayloads.add(value);
            if ("second-start".equals(value)) {
                started.countDown();
                await(started);
                manager.emit(eventId, Map.of("value", "from-second"));
            }
        });
        secondContext.set(second);
        manager.listen(eventId, new CustomEventManager.Listener(first, "listen", -1));
        manager.listen(secondEvent, new CustomEventManager.Listener(second, "listen", -1));
        CompletableFuture<Void> firstEmission = CompletableFuture.runAsync(() -> manager.emit(eventId, Map.of("value", "first-start")));
        CompletableFuture<Void> secondEmission = CompletableFuture.runAsync(() -> manager.emit(secondEvent, Map.of("value", "second-start")));
        try {
            firstEmission.get(2, TimeUnit.SECONDS);
            secondEmission.get(2, TimeUnit.SECONDS);

            assertEquals(List.of("first-start", "from-second"), firstPayloads);
            assertEquals(List.of("second-start", "from-first"), secondPayloads);
            assertFalse(wait(first).isDone());
            assertFalse(wait(second).isDone());
        } finally {
            manager.clearListeners(eventId);
            manager.clearListeners(secondEvent);
        }
        assertTrue(wait(first).isDone());
        assertTrue(wait(second).isDone());
    }

    @Test
    void cancellationBeforeRegistrationPublicationLeavesNoLiveWaiter() throws Exception {
        RuntimeCancellationToken token = new RuntimeCancellationToken();
        CountDownLatch tracked = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        FlowRuntime runtime = runtime();
        FlowContext context = new FlowContext(runtime, null, null, pin -> deliveries.incrementAndGet(), null,
            null, null, null, null, RuntimeExecutionContext.NO_DEADLINE, runtime.getGraph().getNodes().get("listen"), token) {
            @Override
            public CompletableFuture<Void> trackOperation(CompletableFuture<Void> operation, Runnable cancelAction) {
                CompletableFuture<Void> result = super.trackOperation(operation, cancelAction);
                tracked.countDown();
                await(publish);
                return result;
            }
        };
        context.finishSynchronousCapture();
        CompletableFuture<Void> registration = CompletableFuture.runAsync(() ->
            manager.listen(eventId, new CustomEventManager.Listener(context, "listen", 0)));
        try {
            assertTrue(tracked.await(2, TimeUnit.SECONDS));
            token.cancel();
            wait(context).handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
            manager.emit(eventId, Map.of("value", "late"));
            assertEquals(0, deliveries.get());
        } finally {
            publish.countDown();
            registration.get(2, TimeUnit.SECONDS);
        }
        assertTrue(wait(context).isCancelled());
        manager.emit(eventId, Map.of("value", "later"));
        assertEquals(0, deliveries.get());
    }

    @Test
    void alreadyCancelledInvocationCannotReceiveEventOutputs() {
        RuntimeCancellationToken token = new RuntimeCancellationToken();
        token.cancel();
        AtomicInteger deliveries = new AtomicInteger();
        FlowContext context = context(null, token, pin -> deliveries.incrementAndGet());

        manager.listen(eventId, new CustomEventManager.Listener(context, "listen", 0));
        manager.emit(eventId, Map.of("value", "late"));

        assertTrue(wait(context).isCancelled());
        assertEquals(0, deliveries.get());
        assertNull(context.getRuntime().getNodeOutput("listen", "value"));
    }

    @Test
    void deliveryErrorPreservesItsCauseAndDoesNotStrandOtherWaiters() {
        AssertionError expected = new AssertionError("listener failed");
        FlowContext first = context(null, null, pin -> {
            throw expected;
        });
        AtomicInteger secondDeliveries = new AtomicInteger();
        FlowContext second = context(null, null, pin -> secondDeliveries.incrementAndGet());
        manager.listen(eventId, new CustomEventManager.Listener(first, "listen", 0));
        manager.listen(eventId, new CustomEventManager.Listener(second, "listen", 0));

        AssertionError thrown = assertThrows(AssertionError.class, () -> manager.emit(eventId, Map.of("value", "payload")));

        assertSame(expected, thrown);
        assertSame(expected, wait(first).handle((ignored, failure) -> failure).join());
        assertEquals(1, secondDeliveries.get());
        assertTrue(wait(second).isDone());
    }

    @Test
    void realFlowWaitsForEventAndTheContinuationPhysicalWork() {
        HandlerRegistry handlers = new HandlerRegistry();
        CompletableFuture<Void> physical = new CompletableFuture<>();
        AtomicInteger continuations = new AtomicInteger();
        handlers.register("listen", (context, node) -> manager.listen(eventId,
            new CustomEventManager.Listener(context, "listen", 0)));
        handlers.register("continue", (context, node) -> {
            continuations.incrementAndGet();
            context.trackOperation(physical);
        });
        FlowGraph graph = new FlowGraph("custom", Map.of(
            "listen", new FlowNode("listen", 0, 0, Map.of()),
            "continue", new FlowNode("continue", 200, 0, Map.of())),
            List.of(new FlowConnection("listen", "next", "continue", "flow")), List.of());
        FlowExecutor executor = executor(handlers);
        CompletableFuture<Void> execution = executor.execute(graph, "listen", null, null, Map.of());
        try {
            assertFalse(execution.isDone());
            assertEquals(0, continuations.get());

            manager.emit(eventId, Map.of("value", "payload"));

            assertEquals(1, continuations.get());
            assertFalse(execution.isDone());
            manager.emit(eventId, Map.of("value", "later"));
            assertEquals(1, continuations.get());
        } finally {
            physical.complete(null);
        }
        execution.join();
    }

    private FlowExecutor executor(HandlerRegistry handlers) {
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        executors.add(executor);
        return executor;
    }

    private FlowContext context(FlowExecutor executor, RuntimeCancellationToken token, Consumer<String> output) {
        FlowRuntime runtime = runtime();
        FlowContext context = new FlowContext(runtime, null, null, output, executor, null, null, null, null,
            RuntimeExecutionContext.NO_DEADLINE, runtime.getGraph().getNodes().get("listen"), token);
        context.finishSynchronousCapture();
        return context;
    }

    private FlowRuntime runtime() {
        FlowGraph graph = new FlowGraph("custom", Map.of("listen", new FlowNode("listen", 0, 0, Map.of())), List.of(), List.of());
        return new FlowRuntime(graph, new TypeAdapterRegistry(), new HashMap<>());
    }

    private CompletableFuture<Void> wait(FlowContext context) {
        return context.getAsyncOperations().values().iterator().next();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
