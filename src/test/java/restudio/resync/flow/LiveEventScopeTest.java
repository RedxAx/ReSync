package restudio.resync.flow;

import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.ChatHandler;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeExecutionContext;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEventScopeTest {
    @Test
    void broadcastThenCancellationCompletesOnTheOriginThreadBeforeScopeCloses() {
        var server = MockBukkit.mock();
        server.addPlayer();
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            CompiledRuntimeContext context = CompiledRuntimeContext.empty();
            CorrelationId invocation = CorrelationId.random();
            TestEvent event = new TestEvent();
            Thread origin = Thread.currentThread();
            FlowNode broadcast = new FlowNode("system_broadcast", 0, 0, Map.of("message", "before cancellation"));
            broadcast.setHandlerConfig(Map.of("operation", "system_broadcast"));
            FlowNode cancel = new FlowNode("chat_cancel", 0, 0, Map.of());
            cancel.setHandlerConfig(Map.of("operation", "chat_cancel"));
            FlowRuntime runtime = new FlowRuntime(new FlowGraph("event-chain", Map.of("broadcast", broadcast, "cancel", cancel),
                List.of(), List.of()), new TypeAdapterRegistry(), Map.of());
            FlowContext handlerContext = new FlowContext(runtime, null, null, null, executor, null, invocation,
                "event-chain", context, RuntimeExecutionContext.NO_DEADLINE);
            ArrayList<String> observed = new ArrayList<>();
            CompletableFuture<Void> chain = executor.withLiveEventScope(context, invocation, event, () -> {
                new ServerHandler().execute(handlerContext, broadcast);
                assertSame(origin, Thread.currentThread());
                assertFalse(event.isCancelled());
                assertEquals(1, runtime.getNodeOutput("broadcast", "sent_count"));
                observed.add("broadcast");
                return CompletableFuture.completedFuture(null).thenRun(() -> {
                    new ChatHandler().execute(handlerContext, cancel);
                    assertSame(origin, Thread.currentThread());
                    assertTrue(event.isCancelled());
                    observed.add("cancel");
                });
            });
            assertTrue(chain.isDone());
            chain.join();
            assertEquals(List.of("broadcast", "cancel"), observed);
            assertNull(executor.liveEventScope(context, invocation));
            assertFalse(handlerContext.setEventCancelled(false));
            assertTrue(event.isCancelled());
        } finally {
            executor.shutdown();
            MockBukkit.unmock();
        }
    }

    @Test
    void authorityRequiresSameThreadContextAndInvocationAndExpiresOnReturn() throws Exception {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompiledRuntimeContext context = CompiledRuntimeContext.empty();
        CorrelationId invocation = CorrelationId.random();
        Event event = new TestEvent();
        AtomicReference<LiveEventScope> captured = new AtomicReference<>();
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        executor.withLiveEventScope(context, invocation, event, () -> {
            LiveEventScope scope = executor.liveEventScope(context, invocation);
            captured.set(scope);
            assertSame(event, scope.event(context, invocation));
            assertNull(scope.event(CompiledRuntimeContext.empty(), invocation));
            assertNull(scope.event(context, CorrelationId.random()));
            CompletableFuture.runAsync(() -> assertNull(executor.liveEventScope(context, invocation))).join();
            return delayed;
        });
        assertNull(executor.liveEventScope(context, invocation));
        assertNull(captured.get().event(context, invocation));
        delayed.complete(null);
        assertNull(captured.get().event(context, invocation));
        executor.shutdown();
    }

    @Test
    void exceptionClosesParentAndSynchronousChildAuthority() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompiledRuntimeContext context = CompiledRuntimeContext.empty();
        CorrelationId parent = CorrelationId.random();
        CorrelationId child = CorrelationId.random();
        assertThrows(IllegalStateException.class, () -> executor.withLiveEventScope(context, parent, new TestEvent(), () -> {
            executor.withInheritedLiveEventScope(context, parent, child, () -> {
                assertNotNull(executor.liveEventScope(context, child));
                return CompletableFuture.completedFuture(null);
            });
            assertNull(executor.liveEventScope(context, child));
            throw new IllegalStateException("failure");
        }));
        assertNull(executor.liveEventScope(context, parent));
        executor.shutdown();
    }

    @Test
    void anAsynchronousOriginThreadKeepsItsOwnSynchronousEventWindow() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompiledRuntimeContext context = CompiledRuntimeContext.empty();
        CorrelationId invocation = CorrelationId.random();
        CompletableFuture.runAsync(() -> {
            Event event = new TestEvent(true);
            executor.withLiveEventScope(context, invocation, event, () -> {
                assertSame(event, executor.liveEventScope(context, invocation).event(context, invocation));
                return CompletableFuture.completedFuture(null);
            });
            assertNull(executor.liveEventScope(context, invocation));
        }).join();
        executor.shutdown();
    }

    private static final class TestEvent extends Event implements Cancellable {
        private final HandlerList handlers = new HandlerList();
        private boolean cancelled;

        private TestEvent() {}

        private TestEvent(boolean asynchronous) {
            super(asynchronous);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public HandlerList getHandlers() {
            return handlers;
        }
    }
}
