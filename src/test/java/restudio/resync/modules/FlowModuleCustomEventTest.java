package restudio.resync.modules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.generic.CustomEventHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeOperationCancelledException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleCustomEventTest {
    private final CustomEventManager manager = CustomEventManager.getInstance();
    private final String eventId = "custom-capture-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        try {
            manager.clearListeners(eventId);
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void genericListenerWaitsForItsDeclaredFlowAndNestedPhysicalContinuation() throws Exception {
        NodeDefinition definition = definition(Path.of("src/main/resources/nodes/custom_event.json"), "custom.event.listen");
        List<String> deferred = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> child = new CompletableFuture<>();
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        FlowContext context = context(definition, 0, new RuntimeCancellationToken(), pin -> {
            deferred.add(pin);
            captured.get().trackOperation(child);
        });
        captured.set(context);
        new CustomEventHandler().execute(context, context.getRuntime().getGraph().getNodes().get("runtime"));
        context.finishSynchronousCapture();
        List<String> synchronous = context.consumeTriggeredOutputs();
        CompletableFuture<List<String>> result = capture(context, synchronous, deferred);
        try {
            assertTrue(synchronous.isEmpty());
            assertFalse(result.isDone());
            assertEquals(true, context.getRuntime().getNodeOutput("runtime", "listening"));

            manager.emit(eventId, Map.of("value", "payload"));

            assertEquals(List.of("output_flow"), deferred);
            assertFalse(result.isDone());
            assertEquals("payload", context.getRuntime().getEventVariables().get("custom." + eventId + ".value"));
            assertNull(context.getRuntime().getNodeOutput("runtime", "value"));
            assertNull(context.getRuntime().getNodeOutput("runtime", "event_data"));
            assertNull(context.getRuntime().getNodeOutput("runtime", "triggered"));
        } finally {
            child.complete(null);
        }
        assertEquals(List.of("output_flow"), result.join());
    }

    @Test
    void compiledCaptureCancellationDrainsStartedDeliveryAndItsNestedPhysicalWork() throws Exception {
        NodeDefinition definition = definition(Path.of("src/main/resources/nodes/custom_event.json"), "custom.event.listen");
        RuntimeCancellationToken token = new RuntimeCancellationToken();
        List<String> deferred = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> child = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        FlowContext context = context(definition, 0, token, pin -> {
            deferred.add(pin);
            captured.get().trackOperation(child);
            entered.countDown();
            await(release);
        });
        captured.set(context);
        new CustomEventHandler().execute(context, context.getRuntime().getGraph().getNodes().get("runtime"));
        context.finishSynchronousCapture();
        CompletableFuture<List<String>> result = capture(context, context.consumeTriggeredOutputs(), deferred);
        CompletableFuture<Void> emission = CompletableFuture.runAsync(() -> manager.emit(eventId, Map.of("value", "started")));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            token.cancel();
            manager.emit(eventId, Map.of("value", "late"));

            assertFalse(result.isDone());
            assertEquals(List.of("output_flow"), deferred);
            release.countDown();
            emission.get(2, TimeUnit.SECONDS);
            assertFalse(result.isDone());
        } finally {
            release.countDown();
            child.complete(null);
            emission.get(2, TimeUnit.SECONDS);
        }
        Throwable failure = result.handle((ignored, thrown) -> thrown).get(2, TimeUnit.SECONDS);
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue(token.isCancelled());
        assertTrue(failure != null);
        assertEquals("started", context.getRuntime().getEventVariables().get("custom." + eventId + ".value"));
    }

    @Test
    void boundedCompiledWaitFiresOnceBeforeExpiry() throws Exception {
        NodeDefinition definition = definition(Path.of("src/main/resources/nodes/custom_event.json"), "custom.event.listen");
        List<String> deferred = new CopyOnWriteArrayList<>();
        List<Object> payloads = new CopyOnWriteArrayList<>();
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        FlowContext context = context(definition, 2, new RuntimeCancellationToken(), pin -> {
            deferred.add(pin);
            payloads.add(captured.get().getRuntime().getEventVariables().get("custom." + eventId + ".value"));
        });
        captured.set(context);
        new CustomEventHandler().execute(context, context.getRuntime().getGraph().getNodes().get("runtime"));
        context.finishSynchronousCapture();
        CompletableFuture<List<String>> result = capture(context, context.consumeTriggeredOutputs(), deferred);

        manager.emit(eventId, Map.of("value", "first"));
        assertEquals(List.of("output_flow"), result.join());
        manager.tick();
        manager.emit(eventId, Map.of("value", "second"));

        assertEquals(List.of("first"), payloads);
        manager.tick();

        assertEquals(List.of("output_flow"), result.join());
        assertEquals("first", context.getRuntime().getEventVariables().get("custom." + eventId + ".value"));
        manager.emit(eventId, Map.of("value", "expired"));
        assertEquals(List.of("first"), payloads);
    }

    @Test
    void boundedCompiledWaitExpiresWithoutFlowAndSuppressesLateEvents() throws Exception {
        NodeDefinition definition = definition(Path.of("src/main/resources/nodes/custom_event.json"), "custom.event.listen");
        List<String> deferred = new CopyOnWriteArrayList<>();
        FlowContext context = context(definition, 2, new RuntimeCancellationToken(), deferred::add);
        new CustomEventHandler().execute(context, context.getRuntime().getGraph().getNodes().get("runtime"));
        context.finishSynchronousCapture();
        CompletableFuture<List<String>> result = capture(context, context.consumeTriggeredOutputs(), deferred);

        manager.tick();
        assertFalse(result.isDone());
        manager.tick();

        assertEquals(List.of(), result.join());
        manager.emit(eventId, Map.of("value", "expired"));
        assertTrue(deferred.isEmpty());
        assertNull(context.getRuntime().getEventVariables().get("custom." + eventId + ".value"));
        assertEquals(true, context.getRuntime().getNodeOutput("runtime", "listening"));
    }

    @Test
    void questListenerRoutesNextAndPublishesOnlyDeclaredEventOutputs() throws Exception {
        NodeDefinition definition = definition(Path.of("examples/request-extension/src/main/resources/request/nodes/quests.json"),
            "quest_event_listen");
        List<String> deferred = new CopyOnWriteArrayList<>();
        FlowContext context = context(definition, 0, new RuntimeCancellationToken(), deferred::add);
        manager.listen(eventId, new CustomEventManager.Listener(context, "runtime", 0));
        context.finishSynchronousCapture();
        CompletableFuture<List<String>> result = capture(context, context.consumeTriggeredOutputs(), deferred);
        Map<String, Object> payload = Map.of("quest_id", "quest-one", "player_name", "Player", "undeclared", "ignored");

        manager.emit(eventId, payload);

        assertEquals(List.of("next"), result.join());
        assertEquals("quest-one", context.getRuntime().getNodeOutput("runtime", "quest_id"));
        assertEquals("Player", context.getRuntime().getNodeOutput("runtime", "player_name"));
        assertEquals(true, context.getRuntime().getNodeOutput("runtime", "triggered"));
        assertEquals(payload, context.getRuntime().getNodeOutput("runtime", "event_data"));
        assertNull(context.getRuntime().getNodeOutput("runtime", "undeclared"));
    }

    private CompletableFuture<List<String>> capture(FlowContext context, List<String> synchronous, List<String> deferred) {
        return FlowModule.completeLegacyOutputCapture(synchronous, deferred,
            () -> new ArrayList<>(context.getAsyncOperations().values()), outputs -> {
                if (context.isExecutionCancelled()) {
                    throw new RuntimeOperationCancelledException();
                }
                return List.copyOf(outputs);
            });
    }

    private FlowContext context(NodeDefinition definition, int timeout, RuntimeCancellationToken token, Consumer<String> deferred) {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        registry.register(definition);
        FlowNode node = new FlowNode(definition.getId(), 0, 0, Map.of("event_id", eventId, "timeout_ticks", timeout));
        node.setHandlerConfig(definition.getHandlerConfig());
        FlowGraph graph = new FlowGraph("runtime", Map.of("runtime", node), List.of(), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), registry);
        return new FlowContext(runtime, null, null, deferred, null, null, null, null, null,
            RuntimeExecutionContext.NO_DEADLINE, node, token);
    }

    private NodeDefinition definition(Path file, String id) throws Exception {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        try (InputStream input = Files.newInputStream(file)) {
            return loader.parseReplacement(input, file.toString()).stream().filter(value -> id.equals(value.getId())).findFirst().orElseThrow();
        }
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
