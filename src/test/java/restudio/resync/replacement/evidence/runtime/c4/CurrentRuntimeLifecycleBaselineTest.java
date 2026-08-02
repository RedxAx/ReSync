package restudio.resync.replacement.evidence.runtime.c4;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentRuntimeLifecycleBaselineTest {
    @Test
    void nodeRegistrationReplacesAnotherOwnerImmediately() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        registry.register("first.extension", definition("fixture.extension:node"));
        registry.register("second.extension", definition("fixture.extension:node"));

        assertTrue(registry.getDefinitionsForPlugin("first.extension").isEmpty());
        assertTrue(registry.getDefinitionsForPlugin("second.extension").stream()
            .anyMatch(node -> "fixture.extension:node".equals(node.getId())));
        assertTrue("second.extension".equals(registry.getPluginForNode("fixture.extension:node")));
    }

    @Test
    void handlerReplacementAndUnregisterShutdownImmediatelyWithoutALease() {
        HandlerRegistry registry = new HandlerRegistry();
        AtomicBoolean firstShutdown = new AtomicBoolean();
        AtomicBoolean secondShutdown = new AtomicBoolean();
        NodeHandler first = handler(firstShutdown);
        NodeHandler second = handler(secondShutdown);

        registry.register("fixture.extension:handler", first);
        registry.register("fixture.extension:handler", second);

        assertTrue(firstShutdown.get());
        assertTrue(registry.getHandler("fixture.extension:handler") == second);

        registry.unregister("fixture.extension:handler");

        assertTrue(secondShutdown.get());
        assertNull(registry.getHandler("fixture.extension:handler"));
    }

    @Test
    void unregisterShutsDownAnInFlightHandlerBeforeItsInvocationReleases() throws Exception {
        HandlerRegistry registry = new HandlerRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean shutdown = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        NodeHandler handler = new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture handler release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public void shutdown() {
                shutdown.set(true);
            }
        };
        registry.register("fixture.extension:inflight", handler);
        Thread invocation = Thread.ofPlatform().start(() -> {
            try {
                handler.execute(null, new FlowNode("fixture.extension:node", 0, 0, Map.of()));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        registry.unregister("fixture.extension:inflight");
        assertTrue(shutdown.get());
        assertTrue(invocation.isAlive());

        release.countDown();
        invocation.join(5_000L);
        assertFalse(invocation.isAlive());
        assertNull(failure.get());
    }

    @Test
    void addReloadUnloadUsesAVisibleSequenceOfRegistryMutations() {
        assertTrue(fixtureText("registry-lifecycle-sequence.json").contains("unregister-plugin-nodes"));
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        HandlerRegistry handlers = new HandlerRegistry();
        List<String> observations = new java.util.ArrayList<>();
        NodeHandler first = handler(new AtomicBoolean());
        NodeHandler second = handler(new AtomicBoolean());

        definitions.register("fixture.extension", definition("fixture.extension:node"));
        observations.add(definitions.getPluginForNode("fixture.extension:node"));
        handlers.register("fixture.extension:handler", first);
        observations.add(handlers.getHandler("fixture.extension:handler") == first ? "first-handler" : "missing-handler");

        definitions.unregisterPlugin("fixture.extension");
        observations.add(definitions.get("fixture.extension:node") == null ? "node-removed" : "node-present");
        handlers.unregister("fixture.extension:handler");
        observations.add(handlers.getHandler("fixture.extension:handler") == null ? "handler-removed" : "handler-present");

        definitions.register("fixture.extension", definition("fixture.extension:node"));
        handlers.register("fixture.extension:handler", second);
        observations.add(definitions.getPluginForNode("fixture.extension:node"));
        observations.add(handlers.getHandler("fixture.extension:handler") == second ? "second-handler" : "missing-handler");

        assertEquals(List.of("fixture.extension", "first-handler", "node-removed", "handler-removed", "fixture.extension", "second-handler"), observations);
    }

    @Test
    void cancellationSurfaceTracksRegisteredTasksNotArbitraryHandlerFutures() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompletableFuture<Void> arbitrary = new CompletableFuture<>();
        try {
            assertTrue(Arrays.stream(FlowExecutor.class.getDeclaredFields()).anyMatch(field -> field.getName().equals("pendingTasks")));
            assertTrue(Arrays.stream(FlowExecutor.class.getDeclaredFields()).anyMatch(field -> field.getName().equals("wallClockTasks")));
            assertFalse(Arrays.stream(FlowExecutor.class.getDeclaredFields()).anyMatch(field -> field.getName().equals("activeExecutions")));
            assertFalse(Arrays.stream(NodeHandler.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals("cancel")));

            executor.cancelPendingTasks();

            assertFalse(arbitrary.isDone());
            assertFalse(arbitrary.isCancelled());
        } finally {
            arbitrary.cancel(false);
            executor.shutdown();
        }
    }

    @Test
    void missingProviderNodeFieldsAreDroppedWhileGraphOpaqueFieldsSurvive() throws IOException {
        String source = new String(getClass().getResourceAsStream("/fixtures/node-replacement/runtime/c4/missing-provider-node.json").readAllBytes(),
            StandardCharsets.UTF_8);
        FlowGraph graph = FlowSerializer.deserialize(source);
        String roundTrip = FlowSerializer.serialize(graph);

        assertTrue(roundTrip.contains("futureGraphPayload"));
        assertFalse(roundTrip.contains("extensionPayload"));
    }

    @Test
    void traceRecordHasNoProviderOrCatalogGenerationFields() {
        assertFalse(hasField("provider"));
        assertFalse(hasField("catalogGeneration"));
        assertFalse(hasField("catalogChecksum"));
        assertFalse(hasField("resourceType"));
        assertFalse(hasField("resourceId"));
    }

    @Test
    void printsRepresentativeCurrentRuntimeMeasurements() throws IOException {
        String source = fixture();
        long[] registrySamples = measure(this::registryCycle);
        long[] serializerSamples = measure(() -> FlowSerializer.serialize(FlowSerializer.deserialize(source)));
        long[] traceSamples = measure(this::traceCycle);

        System.out.println("C4_BASELINE registry=" + distribution(registrySamples)
            + " serializer=" + distribution(serializerSamples)
            + " trace=" + distribution(traceSamples));
    }

    private NodeDefinition definition(String id) {
        return new NodeDefinition.Builder(id, "Fixture", NodeDefinition.NodeCategory.UTILITY).build();
    }

    private NodeHandler handler(AtomicBoolean shutdown) {
        return new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public void shutdown() {
                shutdown.set(true);
            }
        };
    }

    private boolean hasField(String name) {
        return Arrays.stream(FlowTraceRecord.class.getDeclaredFields()).anyMatch(field -> name.equals(field.getName()));
    }

    private String fixture() throws IOException {
        return fixtureText("missing-provider-node.json");
    }

    private String fixtureText(String name) {
        try {
            return new String(getClass().getResourceAsStream("/fixtures/node-replacement/runtime/c4/" + name).readAllBytes(),
            StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void registryCycle() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        HandlerRegistry handlers = new HandlerRegistry();
        NodeHandler handler = handler(new AtomicBoolean());
        definitions.register("fixture.extension", definition("fixture.extension:node"));
        handlers.register("fixture.extension:handler", handler);
        definitions.get("fixture.extension:node");
        handlers.getHandler("fixture.extension:handler");
        definitions.unregisterPlugin("fixture.extension");
        handlers.unregister("fixture.extension:handler");
    }

    private void traceCycle() {
        FlowTraceService traces = new FlowTraceService(50);
        FlowTraceRecord record = new FlowTraceRecord();
        record.setNodeType("fixture.extension:node");
        record.setStatus("success");
        traces.record(record);
        traces.metricsSnapshot(1);
    }

    private long[] measure(ThrowingRunnable action) {
        for (int index = 0; index < 100; index++) {
            run(action);
        }
        long[] samples = new long[250];
        for (int index = 0; index < samples.length; index++) {
            long started = System.nanoTime();
            run(action);
            samples[index] = System.nanoTime() - started;
        }
        return samples;
    }

    private void run(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String distribution(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return "n=" + sorted.length + ",min=" + sorted[0] + ",median=" + sorted[sorted.length / 2]
            + ",p95=" + sorted[(int) Math.ceil(sorted.length * 0.95D) - 1] + ",max=" + sorted[sorted.length - 1];
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
