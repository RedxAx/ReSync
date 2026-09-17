package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.Gson;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.catalog.*;
import restudio.resync.flow.identity.*;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.*;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.handler.generic.MiscHandler;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.FlowStorageCoreGraphResourceAuthority;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.modules.FlowRuntimeModule;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CompiledTriggerExecutionTest {
    @TempDir
    Path temporary;
    private final List<AssetPersistenceGate> gates = new ArrayList<>();
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();
    private final List<ServerCompiledPlanRepository> planRepositories = new ArrayList<>();

    @AfterEach
    void closePersistence() throws Exception {
        TemporaryLifecycleDiagnostics.close();
        for (ServerCompiledPlanRepository repository : planRepositories) {
            repository.close();
        }
        for (AssetPersistenceGate gate : gates) {
            gate.quiesce();
        }
        for (AssetTransactionCoordinator coordinator : coordinators) {
            coordinator.close();
        }
    }

    @Test
    void invocationWarningsAreBoundedAndCoalesceSuppressedFailures() {
        AtomicLong now = new AtomicLong(1L);
        List<String> warnings = new ArrayList<>();
        CompiledTriggerExecution.WarningLimiter limiter = new CompiledTriggerExecution.WarningLimiter(
            4, 100L, 2, now::get, warnings::add);

        assertTrue(limiter.warn("one", "first"));
        assertFalse(limiter.warn("one", "duplicate"));
        assertTrue(limiter.warn("two", "second"));
        assertFalse(limiter.warn("three", "overflow"));
        assertEquals(2, warnings.size());
        assertFalse(warnings.get(1).contains("coalesced="));

        now.set(101L);
        assertTrue(limiter.warn("three", "next"));
        assertTrue(warnings.getLast().contains("coalesced=1"));
        assertTrue(limiter.warn("one", "first-again"));
        assertEquals(4, warnings.size());
        assertTrue(warnings.getLast().contains("coalesced=1"));
    }

    @Test
    void invocationReportsAndDiscardedDispatchesRemainCorrelatedAndObserved() throws Exception {
        String execution = Files.readString(Path.of("src/main/java/restudio/resync/flow/CompiledTriggerExecution.java"));
        String runtimeModule = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        String systemEvents = Files.readString(Path.of("src/main/java/restudio/resync/flow/SystemEventListener.java"));
        String runtimeDispatch = Files.readString(Path.of("src/main/java/restudio/resync/runtime/RuntimeFlowDispatcher.java"));

        assertTrue(execution.contains("diagnosticPublisher.publish(invocationId.value(), normalized)"));
        assertTrue(execution.contains("\"diagnosticReportId\", reportId"));
        assertTrue(runtimeModule.contains("diagnosticReporter.reportDiagnostics(reportId, diagnostics)"));
        assertTrue(systemEvents.contains("execution.execute(graph, startNodeId, null, event, eventVars, null, invocationId)"));
        assertTrue(systemEvents.contains("execution.observe(future, invocationId, \"system-event:"));
        assertFalse(systemEvents.contains("Log.warn("));
        assertTrue(execution.indexOf("identity = TemporaryLifecycleDiagnostics.with(identity, \"serverId\", sourceOwner.serverId())")
            < execution.indexOf("sourceOwner.storage().getCoreGraph"));
        assertTrue(runtimeDispatch.contains("execution.observe(future, invocationId, \"runtime-flow:"));
        assertFalse(runtimeDispatch.contains("Log.warn("));
    }

    @Test
    void rejectedInvocationDiagnosticCarriesLiveCorrelationAndTypedAuthorityContext() throws Exception {
        Fixture fixture = fixture((executor, invocation) -> CompletableFuture.completedFuture(RuntimeResult.success()));
        CorrelationId invocationId = CorrelationId.deterministic("compiled-trigger-live-correlation");
        FlowGraph stale = fixture.graph().copy();
        stale.setResourceRevision(stale.getResourceRevision() + 1);
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        try {
            assertThrows(Exception.class, () -> fixture.execution().execute(stale, fixture.start(), null, null, Map.of(),
                null, invocationId).join());
            Diagnostic diagnostic = fixture.diagnostics().getLast();
            assertEquals("RUNTIME.INVOCATION_AUDIT", diagnostic.code());
            assertTrue(diagnostic.durable());
            assertEquals(invocationId.value(), diagnostic.correlationId());
            assertEquals(ServerId.deterministic("event-test"), diagnostic.serverId());
            assertEquals("event-test", diagnostic.resource().id());
            assertEquals("flow", diagnostic.resource().resourceType().value());
            assertEquals(1L, diagnostic.catalogGeneration());
            assertEquals("event.block.break", diagnostic.nodeId().canonicalText());
            assertEquals(invocationId.canonicalText(), diagnostic.evidence().get("invocationCorrelationId"));
            assertNotEquals(invocationId.canonicalText(), diagnostic.evidence().get("stableDiagnosticId"));
            assertTrue(fixture.diagnostics().stream()
                .anyMatch(value -> value.code().equals("GRAPH.RUNTIME_PROVIDER_UNAVAILABLE")));
            DiagnosticEvent terminal = lifecycle.events.stream()
                .filter(event -> invocationId.equals(event.identity().correlationId()))
                .filter(event -> event.stage().equals("trigger_execution_terminal"))
                .findFirst().orElseThrow();
            assertEquals(invocationId.canonicalText(), terminal.value("diagnosticReportId").toJava().toString());
            assertEquals(ServerId.deterministic("event-test"), terminal.identity().serverId());
            assertEquals("event-test", terminal.identity().typedKey().id());
            assertEquals(1L, terminal.identity().generation());
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void sourceRetrievalFailureRetainsKnownServerIdentity() throws Exception {
        Fixture fixture = fixture((executor, invocation) -> CompletableFuture.completedFuture(RuntimeResult.success()));
        FlowGraph unavailable = fixture.graph().copy();
        unavailable.setResourceType("missing");
        CorrelationId invocationId = CorrelationId.deterministic("compiled-trigger-source-retrieval-failure");
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        try {
            assertThrows(Exception.class, () -> fixture.execution().execute(unavailable, fixture.start(), null, null, Map.of(),
                null, invocationId).join());
            DiagnosticEvent terminal = lifecycle.events.stream()
                .filter(event -> invocationId.equals(event.identity().correlationId()))
                .filter(event -> event.stage().equals("trigger_execution_terminal"))
                .findFirst().orElseThrow();
            assertEquals(ServerId.deterministic("event-test"), terminal.identity().serverId());
            assertEquals("missing", terminal.identity().typedKey().resourceType().value());
            assertEquals("event-test", terminal.identity().typedKey().id());
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void commandInvocationLifecycleUsesOneCorrelationAndOneTerminalForEveryOutcome() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicReference<String> outcome = new AtomicReference<>("success");
        Fixture fixture = fixture((executor, invocation) -> switch (outcome.get()) {
            case "failure" -> CompletableFuture.completedFuture(RuntimeResult.failure(runtimeFailure("RUNTIME.HANDLER_FAILURE")));
            case "timeout" -> CompletableFuture.completedFuture(RuntimeResult.failure(runtimeFailure("RUNTIME.EXECUTION_TIMEOUT")));
            case "cancelled" -> CompletableFuture.completedFuture(RuntimeResult.cancelled(runtimeFailure("RUNTIME.CANCELLED"), "failed"));
            default -> CompletableFuture.completedFuture(RuntimeResult.success());
        }, "command", "event.command");
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        try {
            Command command = server.getCommandMap().getCommand("primary");
            assertTrue(command != null);
            for (String expected : List.of("success", "failure", "timeout", "cancelled")) {
                outcome.set(expected);
                assertTrue(command.execute(server.getConsoleSender(), "primary", new String[] {expected}));
            }

            Map<CorrelationId, List<DiagnosticEvent>> chains = new LinkedHashMap<>();
            for (DiagnosticEvent event : lifecycle.events) {
                CorrelationId correlation = event.identity().correlationId();
                if (correlation != null && event.stage().startsWith("trigger_")) {
                    chains.computeIfAbsent(correlation, ignored -> new ArrayList<>()).add(event);
                }
            }
            assertEquals(4, chains.size());
            Map<String, Integer> terminalOutcomes = new HashMap<>();
            int successfulWithoutTerminal = 0;
            for (List<DiagnosticEvent> chain : chains.values()) {
                assertEquals(1L, chain.stream().filter(event -> event.stage().equals("trigger_ingress")).count());
                assertEquals(1L, chain.stream().filter(event -> event.stage().equals("trigger_binding_selected")).count());
                List<DiagnosticEvent> terminals = chain.stream()
                    .filter(event -> event.stage().equals("trigger_execution_terminal"))
                    .toList();
                if (terminals.isEmpty()) {
                    successfulWithoutTerminal++;
                    continue;
                }
                assertEquals(1, terminals.size());
                terminalOutcomes.merge(terminals.getFirst().value("outcome").toJava().toString(), 1, Integer::sum);
            }
            assertEquals(1, successfulWithoutTerminal);
            assertNull(terminalOutcomes.get("success"));
            assertEquals(1, terminalOutcomes.get("failed"));
            assertEquals(1, terminalOutcomes.get("timeout"));
            assertEquals(1, terminalOutcomes.get("cancelled"));

            lifecycle.events.clear();
            CorrelationId rejectedId = CorrelationId.deterministic("synchronous-trigger-rejection");
            assertThrows(Exception.class, () -> fixture.execution().execute(fixture.graph(), fixture.start(), null, null,
                Map.of(), null, rejectedId, -1L).join());
            List<DiagnosticEvent> rejected = lifecycle.events.stream()
                .filter(event -> rejectedId.equals(event.identity().correlationId()))
                .toList();
            assertEquals(1L, rejected.stream().filter(event -> event.stage().equals("trigger_execution_received")).count());
            assertEquals(1L, rejected.stream().filter(event -> event.stage().equals("trigger_execution_terminal")).count());
            assertEquals("rejected", rejected.stream()
                .filter(event -> event.stage().equals("trigger_execution_terminal"))
                .findFirst().orElseThrow().value("outcome").toJava());
        } finally {
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    @Test
    void connectedCommandExecutesBroadcastActionWithoutOpaqueSourceDiagnostic() throws Exception {
        AtomicReference<String> broadcast = new AtomicReference<>();
        Fixture fixture = connectedCommandFixture(broadcast);
        CorrelationId invocationId = CorrelationId.deterministic("connected-command-broadcast");
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        try {
            long prepared = fixture.bridge().templatePreparationCount();
            long filesystemValidations = TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts();
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null,
                Map.of("event.command", "asdgasd"), null, invocationId).join();
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null,
                Map.of("event.command", "asdgasd"), null, CorrelationId.deterministic("connected-command-repeat")).join();

            assertEquals("asd", broadcast.get());
            assertEquals(prepared, fixture.bridge().templatePreparationCount());
            assertEquals(filesystemValidations, TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts());
            assertEquals(0, fixture.bridge().activeInvocationCount());
            assertEquals(0, fixture.plans().activeLeaseCount());
            assertTrue(fixture.diagnostics().stream().noneMatch(value -> value.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
            assertTrue(lifecycle.events.stream()
                .filter(event -> invocationId.equals(event.identity().correlationId()))
                .noneMatch(event -> event.stage().equals("trigger_execution_terminal")));
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void warmCommandExecutionRetainsTemplatesAndAvoidsFilesystemValidation() throws Exception {
        AtomicReference<String> broadcast = new AtomicReference<>();
        AtomicInteger storageReads = new AtomicInteger();
        Fixture fixture = connectedCommandFixture(broadcast, storageReads);
        try {
            fixture.executor().setExecutionAuthority(fixture.storage()::isExecutionAuthorized);
            storageReads.set(0);
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null, Map.of()).join();
            assertTrue(storageReads.get() > 0, "The real legacy authorization path must read coordinated storage");
            bindResidentAuthority(fixture.executor(), fixture.plans());
            storageReads.set(0);
            long prepared = fixture.bridge().templatePreparationCount();
            long validations = TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts();
            long[] samples = new long[2000];
            for (int index = -500; index < samples.length; index++) {
                CorrelationId invocation = CorrelationId.random();
                long started = System.nanoTime();
                fixture.execution().execute(fixture.graph(), fixture.start(), null, null,
                    Map.of("event.command", "asdgasd"), null, invocation).join();
                if (index >= 0) {
                    samples[index] = System.nanoTime() - started;
                }
            }
            Arrays.sort(samples);
            assertEquals("asd", broadcast.get());
            assertEquals(0, storageReads.get());
            assertEquals(prepared, fixture.bridge().templatePreparationCount());
            assertEquals(validations, TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts());
            assertEquals(0, fixture.bridge().activeInvocationCount());
            assertEquals(0, fixture.plans().activeLeaseCount());
            assertTrue(fixture.diagnostics().isEmpty());
            System.out.println("Synthetic warm two-node command execution: samples=" + samples.length
                + ", p95Nanos=" + samples[1899] + ", p99Nanos=" + samples[1979]
                + ", maxNanos=" + samples[1999] + ", filesystemValidationDelta=0, templatePreparationDelta=0");
            assertTrue(samples[1899] <= 2_000_000L, "Synthetic warm command p95 exceeds 2ms: " + samples[1899]);
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void systemEventInvocationPreservesLiveContextAndUsesOneObservedLifecycleChain() throws Exception {
        AtomicReference<CompiledRuntimeContext> captured = new AtomicReference<>();
        Fixture fixture = fixture((executor, invocation) -> {
            captured.set(invocation.runtimeContext());
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        SystemEventListener listener = new SystemEventListener(fixture.storage(), fixture.executor(), null);
        listener.setCompiledExecution(fixture.execution());
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        TestEvent event = new TestEvent();
        try {
            listener.dispatch(fixture.graph(), fixture.start(), event, Map.of("event.tick_number", 42)).join();

            assertEquals(42, ((Number) captured.get().variables().get("event.tick_number").value()).intValue());
            List<DiagnosticEvent> chain = lifecycle.events.stream()
                .filter(value -> value.identity().correlationId() != null)
                .filter(value -> value.stage().startsWith("trigger_"))
                .toList();
            assertEquals(1L, chain.stream().filter(value -> value.stage().equals("trigger_ingress")).count());
            assertEquals(1L, chain.stream().filter(value -> value.stage().equals("trigger_binding_selected")).count());
            assertEquals(0L, chain.stream().filter(value -> value.stage().equals("trigger_execution_terminal")).count());
            assertEquals(1, chain.stream().map(value -> value.identity().correlationId()).distinct().count());
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void compiledPluginDisablePreservesStableIdentityAndLiveEventType() throws Exception {
        MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicReference<CompiledRuntimeContext> captured = new AtomicReference<>();
        AtomicReference<Event> capturedEvent = new AtomicReference<>();
        Fixture fixture = fixture((executor, invocation) -> {
            captured.set(invocation.runtimeContext());
            LiveEventScope scope = executor.liveEventScope(invocation.runtimeContext(), invocation.invocationId());
            capturedEvent.set(scope == null ? null : scope.event(invocation.runtimeContext(), invocation.invocationId()));
            return CompletableFuture.completedFuture(RuntimeResult.success());
        }, "flow", "event.plugin.disable");
        SystemEventListener listener = new SystemEventListener(fixture.storage(), fixture.executor(), null);
        listener.setCompiledExecution(fixture.execution());
        listener.registerTrigger("plugin_disable", fixture.graph().getId());
        try {
            PluginDisableEvent event = new PluginDisableEvent(plugin);
            listener.onPluginDisable(event);

            CompiledRuntimeContext context = captured.get();
            assertTrue(context != null, fixture.diagnostics()::toString);
            assertEquals(plugin.getName(), context.variables().get("event.plugin_name").value());
            assertEquals(Map.of("kind", "bukkit-plugin", "name", plugin.getName(),
                    "version", plugin.getDescription().getVersion()),
                context.variables().get("event.plugin_instance").value());
            assertEquals(PluginDisableEvent.class.getName(), context.event().type());
            assertSame(event, capturedEvent.get());
        } finally {
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    private static void bindLifecycle(DiagnosticSink sink) throws Exception {
        Method bind = TemporaryLifecycleDiagnostics.class.getDeclaredMethod("bind", DiagnosticSink.class);
        bind.setAccessible(true);
        bind.invoke(null, sink);
    }

    private static final class CapturingDiagnosticSink implements DiagnosticSink {
        private final List<DiagnosticEvent> events = new ArrayList<>();

        @Override
        public Status status() {
            return Status.ready(Mode.VERBOSE);
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            events.add(event);
            return Offer.ACCEPTED;
        }

        @Override
        public Status pause() {
            return status();
        }

        @Override
        public Status resume() {
            return status();
        }

        @Override
        public Flush flush() {
            return events.isEmpty() ? Flush.EMPTY : Flush.FLUSHED;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void blockCompletionIsRecordedOnceAfterBothMatchingInvocationsFinish() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        CompletableFuture<RuntimeResult> first = new CompletableFuture<>();
        CompletableFuture<RuntimeResult> second = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture((executor, invocation) -> calls.getAndIncrement() == 0 ? first : second);
        GraphDocument original = fixture.storage().getCoreGraph("flow", fixture.graph().getId()).orElseThrow().graphDocument();
        ServerResourceLocator secondResource = new ServerResourceLocator(original.resource().serverId(),
            original.resource().type(), "second-event-test");
        GraphDocument secondDocument = new GraphDocument(secondResource, 1, original.catalogBinding(), original.nodes(), original.connections());
        fixture.storage().saveCoreGraph(secondDocument, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        fixture.plans().initialize();
        fixture.execution().prepare(fixture.storage().getGraph("flow", secondResource.id()));
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        NodeDefinition definition = new NodeDefinition.Builder("event.block.break", "Block Break", NodeDefinition.NodeCategory.EVENT)
            .owner("restudio.resync").trigger(true).eventType(BlockBreakEvent.class.getName()).build();
        new FlowEventRegistry(triggers.getTriggerDispatcher()).registerFromJson(List.of(definition));
        triggers.registerTrigger("restudio.resync/event.block.break", fixture.graph().getId());
        triggers.registerTrigger("restudio.resync/event.block.break", secondResource.id());
        TemporaryLifecycleDiagnostics.resetExecutionTimings();
        try {
            BlockBreakEvent event = new BlockBreakEvent(server.addSimpleWorld("completion").getBlockAt(0, 64, 0), server.addPlayer());
            server.getPluginManager().callEvent(event);
            assertEquals(1L, TemporaryLifecycleDiagnostics.executionTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.BLOCK_DISPATCH).observed());
            assertEquals(0L, TemporaryLifecycleDiagnostics.executionTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.EVENT_COMPLETION).observed());
            server.getScheduler().performOneTick();
            assertEquals(2, calls.get(), fixture.diagnostics()::toString);
            first.complete(RuntimeResult.success());
            assertEquals(0L, TemporaryLifecycleDiagnostics.executionTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.EVENT_COMPLETION).observed());
            second.complete(RuntimeResult.success());
            assertEquals(1L, TemporaryLifecycleDiagnostics.executionTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.EVENT_COMPLETION).observed());
            assertEquals(0, fixture.plans().activeLeaseCount());
        } finally {
            first.complete(RuntimeResult.success());
            second.complete(RuntimeResult.success());
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            TemporaryLifecycleDiagnostics.resetExecutionTimings();
            MockBukkit.unmock();
        }
    }

    @Test
    void nonCancellingEventExecutesAfterDispatchWithItsCapturedIdentity() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        Fixture fixture = fixture((executor, invocation) -> {
            captured.set(handlerContext(executor, invocation));
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        NodeDefinition definition = new NodeDefinition.Builder("event.block.break", "Block Break", NodeDefinition.NodeCategory.EVENT)
            .owner("restudio.resync").trigger(true).eventType(BlockBreakEvent.class.getName()).build();
        new FlowEventRegistry(triggers.getTriggerDispatcher()).registerFromJson(List.of(definition));
        triggers.registerTrigger("restudio.resync/event.block.break", fixture.graph().getId());
        var player = server.addPlayer();
        BlockBreakEvent event = new BlockBreakEvent(server.addSimpleWorld("deferred").getBlockAt(0, 64, 0), player);
        try {
            server.getPluginManager().callEvent(event);
            assertNull(captured.get());
            server.getScheduler().performOneTick();
            assertEquals("BlockBreakEvent", captured.get().eventType());
            assertFalse(captured.get().isEventMutationOpen());
            assertFalse(captured.get().setEventCancelled(true));
            assertFalse(event.isCancelled());
        } finally {
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    @Test
    void compiledTriggerRunsTheRealCancellationHandlerWithinTheOriginWindow() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        Fixture fixture = fixture((executor, invocation) -> {
            FlowContext context = handlerContext(executor, invocation);
            captured.set(context);
            FlowNode node = context.getRuntime().getGraph().getNodes().get("cancel");
            new MiscHandler().execute(context, node);
            assertEquals(true, context.getRuntime().getNodeOutput("cancel", "success"));
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        fixture.graph().getNodes().put("cancellation-marker", new FlowNode("event_cancel", 0, 0, Map.of()));
        fixture.execution().retire("flow", fixture.graph().getId());
        fixture.execution().prepare(fixture.graph());
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        NodeDefinition definition = new NodeDefinition.Builder("event.block.break", "Block Break", NodeDefinition.NodeCategory.EVENT)
            .owner("restudio.resync").trigger(true).eventType(BlockBreakEvent.class.getName()).build();
        new FlowEventRegistry(triggers.getTriggerDispatcher()).registerFromJson(List.of(definition));
        triggers.registerTrigger("restudio.resync/event.block.break", fixture.graph().getId());
        var player = server.addPlayer();
        var block = server.addSimpleWorld("events").getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        try {
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), fixture.diagnostics()::toString);
            assertNull(captured.get().getEvent());
            assertFalse(captured.get().setEventCancelled(false));
            captured.set(null);
            triggers.getTriggerDispatcher().clearBindings();
            BlockBreakEvent removed = new BlockBreakEvent(block, player);
            server.getPluginManager().callEvent(removed);
            assertFalse(removed.isCancelled());
            assertNull(captured.get());
        } finally {
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    @Test
    void incompleteHandlerCompletionCannotRetainLiveMutationOnTheSameThread() throws Exception {
        AtomicReference<FlowContext> captured = new AtomicReference<>();
        CompletableFuture<RuntimeResult> continuation = new CompletableFuture<>();
        Fixture fixture = fixture((executor, invocation) -> {
            captured.set(handlerContext(executor, invocation));
            return continuation;
        });
        TestEvent event = new TestEvent();
        try {
            CompletableFuture<Void> result = fixture.execution().execute(fixture.graph(), fixture.start(), null, event, Map.of());
            assertFalse(result.isDone(), fixture.diagnostics()::toString);
            assertFalse(captured.get().setEventCancelled(true));
            continuation.complete(RuntimeResult.success());
            result.join();
            assertFalse(event.isCancelled());
        } finally {
            fixture.executor().shutdown();
        }
    }

    private static FlowContext handlerContext(FlowExecutor executor, RuntimeInvocation invocation) {
        FlowNode node = new FlowNode("cancel", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "cancel_event"));
        FlowRuntime runtime = new FlowRuntime(new FlowGraph("handler", Map.of("cancel", node), List.of(), List.of()),
            new TypeAdapterRegistry(), Map.of());
        return new FlowContext(runtime, null, null, null, executor, invocation.principal(), invocation.invocationId(),
            invocation.idempotencyKey(), invocation.runtimeContext(), invocation.deadlineMillis());
    }

    @Test
    void typedSourceAdmissionRejectsStaleProjectionAndDoesNotCrossResourceFamilies() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture((executor, invocation) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        try {
            for (String type : List.of("command", "function")) {
                FlowGraph wrongFamily = fixture.graph().copy();
                wrongFamily.setResourceType(type);
                assertThrows(Exception.class, () -> fixture.execution().execute(wrongFamily, fixture.start(), null, null, Map.of()).join());
            }
            FlowGraph stale = fixture.graph().copy();
            stale.setResourceRevision(stale.getResourceRevision() + 1);
            assertThrows(Exception.class, () -> fixture.execution().execute(stale, fixture.start(), null, null, Map.of()).join());
            FlowGraph wrongHash = fixture.graph().copy();
            wrongHash.setResourceHash("b".repeat(64));
            assertThrows(Exception.class, () -> fixture.execution().execute(wrongHash, fixture.start(), null, null, Map.of()).join());
            assertEquals(0, calls.get());
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null, Map.of()).join();
            assertEquals(1, calls.get());
        } finally {
            fixture.executor().shutdown();
        }
    }

    private Fixture fixture(BiFunction<FlowExecutor, RuntimeInvocation, CompletableFuture<RuntimeResult>> handler) throws Exception {
        return fixture(handler, "flow", "event.block.break");
    }

    private Fixture fixture(BiFunction<FlowExecutor, RuntimeInvocation, CompletableFuture<RuntimeResult>> handler,
                            String resourceType, String nodeId) throws Exception {
        OwnerId owner = OwnerId.of("restudio.resync");
        ContractRef<CapabilityId> capability = ContractRef.of(owner, CapabilityId.of("event-test"));
        ContractRef<OperationId> operationId = ContractRef.of(owner, OperationId.of("event-test"));
        ContractRef<ProviderId> providerId = ContractRef.of(owner, ProviderId.of("event-test"));
        RuntimeSemantics semantics = semantics(capability);
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability, operationId, List.of(), semantics);
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> requested) {
                return capability.equals(requested);
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        };
        FlowRuntimeExecutionBoundary boundary = new FlowRuntimeExecutionBoundary(
            task -> { throw new AssertionError("The origin main thread must not be queued"); },
            task -> { throw new AssertionError("No asynchronous work was requested"); }, () -> true);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, RuntimeAuditBoundary.unavailable(), boundary,
            RuntimeReceiptStore.inMemory(false));
        registry.activate(new RuntimeProviderDescriptor(providerId, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, providerId, "1.0.0", invocation -> handler.apply(executor, invocation))));
        CatalogVersion version = new CatalogVersion(1, 0);
        CatalogNodeDescriptor definition = CatalogNodeDescriptor.builder(nodeId)
            .domain("event").family("block").displayName("Block Break").description("Runs a block break event handler.")
            .category(capability)
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "The handler failed.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The handler returned a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(capability, operationId)).semantics(semantics)
            .requiredCapabilities(Set.of(capability)).metadata(Map.of("sourceNodeId", nodeId, "handlerConfig", Map.of())).build();
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/event-test.json", "1.0.0", "test", "event-test"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("event-test"), "Events", "Event handler tests.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("event-test"), 1, false, InspectorFallback.GENERIC)))
            .definitions(List.of(definition)).runtimeRequirements(List.of(operation)).build();
        CatalogCompilationResult compiled = new CatalogCompiler(version, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1);
        assertTrue(compiled.accepted(), compiled.diagnostics()::toString);
        CatalogSnapshot catalog = compiled.snapshot().orElseThrow();
        ServerId server = ServerId.deterministic("event-test");
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
        CompiledGraphMetadataProvider provider = new CompiledGraphMetadataProvider(() -> activation, server, new FlowValueCodecRegistry());
        Path directory = temporary.resolve(UUID.randomUUID().toString());
        AssetPersistenceGate gate = new AssetPersistenceGate(directory);
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson());
        gates.add(gate);
        coordinators.add(coordinator);
        FlowStorage storage = new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), gate, server, coordinator);
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(server, () -> activation, CatalogActivationAuthority::freshInstall);
        FlowStorageCoreGraphResourceAuthority resources = new FlowStorageCoreGraphResourceAuthority(storage, server, validator);
        ServerCompiledPlanRepository plans = new ServerCompiledPlanRepository(resources, () -> activation);
        planRepositories.add(plans);
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(() -> activation, registry,
            RuntimeAuthority.anonymous(), null, plans);
        NodeInstanceId start = NodeInstanceId.deterministic("event-test-start");
        GraphDocument document = new GraphDocument(new ServerResourceLocator(server,
            ContractRef.of(owner, ResourceTypeId.of(resourceType)), "event-test"), 1,
            new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
            List.of(new GraphNode(start, ContractRef.of(owner, NodeId.of(nodeId)), 1, Map.of())), List.of());
        if ("command".equals(resourceType)) {
            document = new CommandGraphMetadata("primary", false, List.of()).apply(document);
        }
        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(document, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        var admission = validator.validate(document.resource(), saved);
        assertTrue(admission.valid(), admission.diagnostics()::toString);
        FlowGraph graph = storage.getGraph(resourceType, "event-test");
        plans.bindTemplateCompiler(bridge::prepare);
        plans.bindMetadataCompiler(provider::provide);
        plans.initialize();
        List<Diagnostic> diagnostics = new ArrayList<>();
        CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, provider, bridge, (reportId, values) -> {
            diagnostics.addAll(values);
            return reportId;
        }, null, plans);
        execution.bindCoreStorage(storage, server);
        execution.prepare(graph);
        return new Fixture(executor, execution, graph, start.canonicalText(), storage, diagnostics, plans, bridge);
    }

    private Fixture connectedCommandFixture(AtomicReference<String> broadcast) throws Exception {
        return connectedCommandFixture(broadcast, new AtomicInteger());
    }

    private Fixture connectedCommandFixture(AtomicReference<String> broadcast, AtomicInteger storageReads) throws Exception {
        return connectedCommandFixture(broadcast, storageReads, new AtomicBoolean());
    }

    private Fixture connectedCommandFixture(AtomicReference<String> broadcast, AtomicInteger storageReads,
                                             AtomicBoolean failReads) throws Exception {
        OwnerId owner = OwnerId.of("restudio.resync");
        ContractRef<CapabilityId> eventCapability = ContractRef.of(owner, CapabilityId.of("event-command"));
        ContractRef<CapabilityId> serverCapability = ContractRef.of(owner, CapabilityId.of("server-broadcast"));
        ContractRef<OperationId> eventOperation = ContractRef.of(owner, OperationId.of("event-command"));
        ContractRef<OperationId> broadcastOperation = ContractRef.of(owner, OperationId.of("system-broadcast"));
        ContractRef<ProviderId> providerId = ContractRef.of(owner, ProviderId.of("connected-command"));
        TypeExpr executionType = TypeExpr.named(TypeReference.of("builtin", "execution"));
        TypeExpr stringType = TypeExpr.named(TypeReference.of("builtin", "string"));
        PinId flow = PinId.of("flow");
        PinId message = PinId.of("message");
        RuntimeSemantics eventSemantics = semantics(eventCapability);
        RuntimeSemantics broadcastSemantics = semantics(serverCapability);
        RuntimeOperationDescriptor event = new RuntimeOperationDescriptor(eventCapability, eventOperation,
            List.of(new RuntimeOperationDescriptor.Pin(flow, RuntimeOperationDescriptor.Direction.OUTPUT, executionType)), eventSemantics);
        RuntimeOperationDescriptor action = new RuntimeOperationDescriptor(serverCapability, broadcastOperation, List.of(
            new RuntimeOperationDescriptor.Pin(flow, RuntimeOperationDescriptor.Direction.INPUT, executionType),
            new RuntimeOperationDescriptor.Pin(message, RuntimeOperationDescriptor.Direction.INPUT, stringType)), broadcastSemantics);
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> requested) {
                return eventCapability.equals(requested) || serverCapability.equals(requested);
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        };
        FlowRuntimeExecutionBoundary boundary = new FlowRuntimeExecutionBoundary(
            task -> { throw new AssertionError("The origin main thread must not be queued"); },
            task -> { throw new AssertionError("No asynchronous work was requested"); }, () -> true);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, RuntimeAuditBoundary.unavailable(), boundary,
            RuntimeReceiptStore.inMemory(false));
        registry.activate(new RuntimeProviderDescriptor(providerId, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(event, providerId, "1.0.0", invocation -> CompletableFuture.completedFuture(
                RuntimeResult.success(Map.of(flow, TypedValue.value(executionType, true)), null))),
            RuntimeBinding.available(action, providerId, "1.0.0", invocation -> {
                broadcast.set((String) invocation.inputs().get(message).value());
                return CompletableFuture.completedFuture(RuntimeResult.success());
            })));
        CatalogVersion version = new CatalogVersion(1, 0);
        CatalogNodeDescriptor sourceDefinition = CatalogNodeDescriptor.builder("event.command")
            .domain("event").family("command").displayName("Command").description("Runs an installed command graph.")
            .category(eventCapability)
            .pins(List.of(new CatalogNodeDescriptor.Pin("flow", CatalogNodeDescriptor.Direction.OUTPUT, executionType,
                "Flow", "Continues command execution.", CatalogNodeDescriptor.Requirement.REQUIRED, eventCapability)))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "The command source failed.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The command source returned a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(eventCapability, eventOperation)).semantics(eventSemantics)
            .requiredCapabilities(Set.of(eventCapability)).metadata(Map.of("sourceNodeId", "event.command", "handlerConfig", Map.of())).build();
        CatalogNodeDescriptor actionDefinition = CatalogNodeDescriptor.builder("server.system_broadcast")
            .domain("server").family("broadcast").displayName("System Broadcast").description("Broadcasts a message to the server.")
            .category(serverCapability)
            .pins(List.of(
                new CatalogNodeDescriptor.Pin("flow", CatalogNodeDescriptor.Direction.INPUT, executionType,
                    "Flow", "Runs the broadcast.", CatalogNodeDescriptor.Requirement.REQUIRED, serverCapability),
                new CatalogNodeDescriptor.Pin("message", CatalogNodeDescriptor.Direction.INPUT, stringType,
                    "Message", "Message to broadcast.", CatalogNodeDescriptor.Requirement.REQUIRED, serverCapability)))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "The broadcast failed.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The broadcast returned a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(serverCapability, broadcastOperation)).semantics(broadcastSemantics)
            .requiredCapabilities(Set.of(serverCapability)).metadata(Map.of("sourceNodeId", "server.system_broadcast", "handlerConfig", Map.of())).build();
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/connected-command.json", "1.0.0", "test", "connected-command"))
            .categories(List.of(
                new CatalogCategoryDescriptor(CapabilityId.of("event-command"), "Commands", "Command event tests.", 1),
                new CatalogCategoryDescriptor(CapabilityId.of("server-broadcast"), "Server", "Server broadcast tests.", 2)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(CapabilityId.of("event-command"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("server-broadcast"), 1, false, InspectorFallback.GENERIC)))
            .definitions(List.of(sourceDefinition, actionDefinition)).runtimeRequirements(List.of(event, action)).build();
        CatalogCompilationResult compiled = new CatalogCompiler(version, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1);
        assertTrue(compiled.accepted(), compiled.diagnostics()::toString);
        CatalogSnapshot catalog = compiled.snapshot().orElseThrow();
        ServerId server = ServerId.deterministic("connected-command");
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
        CompiledGraphMetadataProvider metadata = new CompiledGraphMetadataProvider(() -> activation, server, new FlowValueCodecRegistry());
        Path directory = temporary.resolve(UUID.randomUUID().toString());
        AssetPersistenceGate gate = new AssetPersistenceGate(directory);
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson());
        gates.add(gate);
        coordinators.add(coordinator);
        FlowStorage storage = new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), gate, server, coordinator) {
            @Override
            public synchronized FlowGraph getGraph(String type, String id) {
                storageReads.incrementAndGet();
                return super.getGraph(type, id);
            }

            @Override
            public synchronized Optional<CoreGraphStorageBoundary.Decoded> getCoreGraph(String type, String id) {
                storageReads.incrementAndGet();
                if (failReads.get()) {
                    throw new IllegalStateException("Authoritative Read Failed");
                }
                return super.getCoreGraph(type, id);
            }
        };
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(server, () -> activation, CatalogActivationAuthority::freshInstall);
        FlowStorageCoreGraphResourceAuthority resources = new FlowStorageCoreGraphResourceAuthority(storage, server, validator);
        ServerCompiledPlanRepository plans = new ServerCompiledPlanRepository(resources, () -> activation);
        planRepositories.add(plans);
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(() -> activation, registry,
            RuntimeAuthority.anonymous(), null, plans);
        NodeInstanceId start = NodeInstanceId.deterministic("connected-command-start");
        NodeInstanceId target = NodeInstanceId.deterministic("connected-command-broadcast");
        GraphDocument document = new GraphDocument(new ServerResourceLocator(server,
            ContractRef.of(owner, ResourceTypeId.of("command")), "asdgasd"), 1,
            new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()), List.of(
                new GraphNode(start, ContractRef.of(owner, NodeId.of("event.command")), 1, Map.of()),
                new GraphNode(target, ContractRef.of(owner, NodeId.of("server.system_broadcast")), 1,
                    Map.of(message, new PinValue(message, TypedValue.value(stringType, "asd"))))),
            List.of(new GraphConnection(ConnectionId.deterministic("connected-command-flow"),
                new GraphEndpoint(start, flow), new GraphEndpoint(target, flow))));
        document = new CommandGraphMetadata("asdgasd", false, List.of()).apply(document);
        CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(document, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        var admission = validator.validate(document.resource(), saved);
        assertTrue(admission.valid(), admission.diagnostics()::toString);
        FlowGraph graph = storage.getGraph("command", "asdgasd");
        plans.bindTemplateCompiler(bridge::prepare);
        plans.bindMetadataCompiler(metadata::provide);
        plans.initialize();
        executor.setExecutionAuthority(storage::isExecutionAuthorized);
        bindResidentAuthority(executor, plans);
        List<Diagnostic> diagnostics = new ArrayList<>();
        CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, metadata, bridge, (reportId, values) -> {
            diagnostics.addAll(values);
            return reportId;
        }, null, plans);
        execution.bindCoreStorage(storage, server);
        execution.prepare(graph);
        return new Fixture(executor, execution, graph, start.canonicalText(), storage, diagnostics, plans, bridge);
    }

    private static void bindResidentAuthority(FlowExecutor executor, ServerCompiledPlanRepository plans) {
        executor.setCompiledExecutionAuthority((graph, metadata) -> metadata.resource().id().equals(graph.getId())
            && metadata.resource().resourceType().value().equals(graph.getResourceType())
            && plans.isExecutionAuthorized(metadata, graph.getResourceRevision(), graph.getResourceHash()));
    }

    @Test
    void compiledRootUsesResidentAuthorityAndRejectsStaleOrRetiredSources() throws Exception {
        AtomicReference<String> broadcast = new AtomicReference<>();
        AtomicInteger storageReads = new AtomicInteger();
        Fixture fixture = connectedCommandFixture(broadcast, storageReads);
        try {
            CoreGraphStorageBoundary.Decoded source = fixture.storage().getCoreGraph("command", fixture.graph().getId()).orElseThrow();
            ServerResourceLocator resource = source.graphDocument().resource();
            CompiledGraphMetadataProvider.Result prepared = fixture.plans().residentExecution(resource,
                fixture.graph().getResourceRevision()).orElseThrow().metadata();
            CompiledGraphMetadata metadata = prepared.metadata();
            FlowExecutor.CompiledExecutionAuthority authority = new FlowExecutor.CompiledExecutionAuthority(
                CompiledCoreFlowExecutionBridge.authorityHash(), metadata.catalogBinding().catalogChecksum(),
                metadata.catalogBinding().bindingManifestHash());
            Function<FlowGraph, CompletableFuture<Void>> execute = graph -> fixture.executor().executeCompiled(
                graph, fixture.start(), CompiledRuntimeContext.empty(), prepared.mappingContext(), metadata,
                authority, fixture.bridge());
            storageReads.set(0);
            execute.apply(fixture.graph()).join();
            assertEquals("asd", broadcast.getAndSet(null));

            FlowGraph stale = fixture.graph().copy();
            stale.setResourceRevision(stale.getResourceRevision() + 1);
            execute.apply(stale).join();
            FlowGraph altered = fixture.graph().copy();
            altered.setResourceHash("different");
            execute.apply(altered).join();
            FlowGraph disabled = fixture.graph().copy();
            disabled.setEnabled(false);
            execute.apply(disabled).join();
            assertNull(broadcast.get());
            try (FlowExecutor.AdmissionFence ignored = fixture.executor().fenceAdmissions()) {
                assertThrows(Exception.class, () -> execute.apply(fixture.graph()).join());
            }
            assertNull(broadcast.get());
            assertTrue(fixture.plans().invalidate(CoreGraphMutationEvent.deleted(resource,
                fixture.graph().getResourceRevision() + 1, UUID.randomUUID(), source.graphDocument().checksum())));
            execute.apply(fixture.graph()).join();
            assertNull(broadcast.get());
            assertEquals(0, storageReads.get());
            assertEquals(0, fixture.bridge().activeInvocationCount());
            assertEquals(0, fixture.plans().activeLeaseCount());
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void failedMutationCallbackRetiresResidentAuthorityBeforeReadingStorage() throws Exception {
        AtomicReference<String> broadcast = new AtomicReference<>();
        AtomicBoolean failReads = new AtomicBoolean();
        AtomicInteger storageReads = new AtomicInteger();
        Fixture fixture = connectedCommandFixture(broadcast, storageReads, failReads);
        try {
            CoreGraphStorageBoundary.Decoded source = fixture.storage().getCoreGraph("command", fixture.graph().getId()).orElseThrow();
            ServerResourceLocator resource = source.graphDocument().resource();
            FlowRuntimeModule module = new FlowRuntimeModule();
            Field resources = ServerCompiledPlanRepository.class.getDeclaredField("resources");
            resources.setAccessible(true);
            for (Map.Entry<String, Object> entry : Map.<String, Object>of("startupActivationComplete", true,
                "compiledPlanRepository", fixture.plans(), "serverId", resource.serverId(),
                "coreGraphResourceAuthority", resources.get(fixture.plans())).entrySet()) {
                Field field = FlowRuntimeModule.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(module, entry.getValue());
            }
            Method callback = FlowRuntimeModule.class.getDeclaredMethod("handleCoreGraphChange", FlowStorage.GraphChange.class);
            callback.setAccessible(true);
            FlowStorage.GraphChange change = new FlowStorage.GraphChange("command", fixture.graph().getId());
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null, Map.of()).join();
            assertEquals("asd", broadcast.getAndSet(null));
            failReads.set(true);
            storageReads.set(0);
            callback.invoke(module, change);
            assertTrue(storageReads.get() > 0, "The real callback must attempt the authoritative read");
            assertTrue(fixture.plans().residentExecution(resource, fixture.graph().getResourceRevision()).isEmpty());
            assertThrows(Exception.class, () -> fixture.execution().execute(fixture.graph(), fixture.start(), null, null, Map.of()).join());
            assertNull(broadcast.get());
            failReads.set(false);
            callback.invoke(module, change);
            callback.invoke(module, change);
            fixture.execution().execute(fixture.graph(), fixture.start(), null, null, Map.of()).join();
            assertEquals("asd", broadcast.getAndSet(null));
            assertEquals(0, fixture.bridge().activeInvocationCount());
            assertEquals(0, fixture.plans().activeLeaseCount());
        } finally {
            failReads.set(false);
            fixture.executor().shutdown();
        }
    }

    private static RuntimeSemantics semantics(ContractRef<CapabilityId> authorization) {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.MAIN, authorization,
            RuntimeSemantics.Cancellation.COOPERATIVE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of("failed"),
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "string")), Set.of("RUNTIME.FAILURE"),
                Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static RuntimeFailure runtimeFailure(String code) {
        Diagnostic diagnostic = Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of(code.toLowerCase().replace('.', '-'))))
            .message("Runtime invocation did not complete")
            .correlationId(CorrelationId.deterministic(code))
            .build();
        return new RuntimeFailure(diagnostic, false,
            TypedValue.nullValue(TypeExpr.named(TypeReference.of("builtin", "string"))));
    }

    private record Fixture(FlowExecutor executor, CompiledTriggerExecution execution, FlowGraph graph, String start,
                           FlowStorage storage, List<Diagnostic> diagnostics, ServerCompiledPlanRepository plans,
                           CompiledCoreFlowExecutionBridge bridge) {}

    @Test
    void registeredTypedCommandPreservesZeroMultipleAndNamespacedAliasArgumentsForBothSenders() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicReference<CompiledRuntimeContext> captured = new AtomicReference<>();
        Fixture fixture = fixture((executor, invocation) -> {
            captured.set(invocation.runtimeContext());
            return CompletableFuture.completedFuture(RuntimeResult.success());
        }, "command", "event.command");
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        try {
            var source = fixture.storage().getCoreGraph("command", "event-test").orElseThrow();
            assertEquals(source.graphDocument().checksum().canonicalText(), fixture.storage().getCommandGraph("event-test").getResourceHash());
            assertEquals(source.envelope().assetHash().canonicalText(), fixture.graph().getResourceHash());
            Command command = server.getCommandMap().getCommand("primary");
            assertTrue(command != null);
            for (CommandSender sender : List.of(server.addPlayer(), server.getConsoleSender())) {
                for (String[] arguments : List.of(new String[0], new String[] {"one", "two words", ""})) {
                    captured.set(null);
                    assertTrue(command.execute(sender, plugin.getName().toLowerCase() + ":primary", arguments));
                    CompiledRuntimeContext context = captured.get();
                    assertTrue(context != null, fixture.diagnostics()::toString);
                    assertEquals("primary", context.variables().get("event.bound_command").value());
                    assertEquals("primary", context.variables().get("event.command_label").value());
                    assertEquals(arguments.length, ((Number) context.variables().get("event.args_count").value()).intValue());
                    assertEquals(List.of(arguments), context.variables().get("event.args_list").value());
                    assertEquals(!(sender instanceof Player), context.variables().get("event.is_console").value());
                    if (sender instanceof Player player) {
                        assertEquals(player.getUniqueId(), context.player().uniqueId());
                    } else {
                        assertNull(context.player());
                    }
                }
            }
        } finally {
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    @Test
    void registeredCommandUsesTheNewResidentRevisionAfterSave() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture((executor, invocation) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        }, "command", "event.command");
        GlobalTriggers triggers = new GlobalTriggers(fixture.storage(), fixture.executor(), new TriggerRegistry(plugin), null);
        triggers.setCompiledExecution(fixture.execution());
        fixture.storage().setGraphChangeListener(change -> fixture.plans().initialize());
        try {
            assertTrue(server.getCommandMap().getCommand("primary").execute(server.getConsoleSender(), "primary", new String[0]));
            assertEquals(1, calls.get(), fixture.diagnostics()::toString);
            GraphDocument original = fixture.storage().getCoreGraph("command", "event-test").orElseThrow().graphDocument();
            GraphDocument replacement = new GraphDocument(original.resource(), original.revision() + 1,
                original.catalogBinding(), original.nodes(), original.connections());
            replacement = new CommandGraphMetadata("primary", false, List.of()).apply(replacement);
            fixture.storage().saveCoreGraph(replacement, ResourceActivationState.ACTIVE, UUID.randomUUID(), original.revision());
            assertTrue(server.getCommandMap().getCommand("primary").execute(server.getConsoleSender(), "primary", new String[0]));
            assertEquals(2, calls.get(), fixture.diagnostics()::toString);
        } finally {
            fixture.storage().setGraphChangeListener(null);
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            fixture.executor().shutdown();
            MockBukkit.unmock();
        }
    }

    private static final class TestEvent extends Event implements Cancellable {
        private final HandlerList handlers = new HandlerList();
        private boolean cancelled;

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

        @Override
        public HandlerList getHandlers() { return handlers; }
    }

    @Test
    void rejectsTriggerWithoutCompiledMetadataAndReportsTheStructuredFailure() {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompiledGraphMetadataProvider provider = new CompiledGraphMetadataProvider(
            () -> null,
            () -> null,
            ServerId.deterministic("compiled-trigger-test"),
            new FlowValueCodecRegistry());
        RuntimeBindingRegistry bindings = new RuntimeBindingRegistry();
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)),
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()),
            bindings,
            RuntimeAuthority.anonymous());
        List<Diagnostic> reported = new ArrayList<>();
        CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, provider, bridge, reported::addAll);

        var failure = assertThrows(Exception.class, () -> execution.execute(new FlowGraph(), "start", null, null, Map.of()).join());

        assertTrue(failure.getCause() instanceof FlowExecutor.FlowExecutionException);
        assertEquals("CORE_EXECUTION_UNSUPPORTED", ((FlowExecutor.FlowExecutionException) failure.getCause()).getCode());
        assertFalse(reported.isEmpty());
        assertTrue(reported.stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
    }

    @Test
    void runtimeFailureDiagnosticsReachReportAndNativeTerminal() throws Exception {
        Diagnostic source = Diagnostic.builder("RUNTIME.RESULT_TYPE_MISMATCH", DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC, "result-type-mismatch")
            .messageKey(ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("runtime-result-type-mismatch")))
            .evidence(Map.of("expectedType", "string", "actualType", "number", "accessToken", "private-token"))
            .correlationId(CorrelationId.deterministic("source-runtime-failure"))
            .build();
        Fixture fixture = fixture((executor, invocation) -> CompletableFuture.completedFuture(
            RuntimeResult.failure(new RuntimeFailure(source, false,
                TypedValue.nullValue(TypeExpr.named(TypeReference.of("builtin", "string")))))));
        CapturingDiagnosticSink lifecycle = new CapturingDiagnosticSink();
        bindLifecycle(lifecycle);
        CorrelationId invocationId = CorrelationId.deterministic("reported-runtime-failure");
        try {
            assertThrows(Exception.class, () -> fixture.execution().execute(fixture.graph(), fixture.start(), null,
                null, Map.of(), null, invocationId).join());
            Diagnostic actual = fixture.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals(source.code())).findFirst().orElseThrow();
            assertEquals(invocationId.value(), actual.correlationId());
            assertEquals(source.correlationId().toString(), actual.evidence().get("stableDiagnosticId"));
            assertEquals("string", actual.evidence().get("expectedType"));
            assertEquals("number", actual.evidence().get("actualType"));
            assertFalse(actual.toRedactedJson().contains("private-token"));
            assertFalse(fixture.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
            assertTrue(fixture.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RUNTIME.INVOCATION_AUDIT")));
            DiagnosticEvent terminal = lifecycle.events.stream()
                .filter(event -> event.stage().equals("trigger_execution_terminal")).findFirst().orElseThrow();
            assertEquals(source.code(), terminal.value("diagnosticCode").toJava());
            assertEquals("failed", terminal.value("outcome").toJava());
            assertFalse(terminal.values().toString().contains("private-token"));
        } finally {
            fixture.executor().shutdown();
        }
    }

    @Test
    void malformedFailureDiagnosticsAreBoundedAndPreserveOuterOutcome() throws Exception {
        Fixture fixture = fixture((executor, invocation) -> CompletableFuture.completedFuture(RuntimeResult.success()));
        Diagnostic source = runtimeFailure("RUNTIME.RESULT_TYPE_MISMATCH").diagnostic();
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        List<Object> values = new ArrayList<>(List.of("invalid", Map.of("code", "INVALID"), cyclic,
            Map.of("oversized", "x".repeat(20_000)), source.toMap()));
        Method report = CompiledTriggerExecution.class.getDeclaredMethod("reportExecutionFailure", FlowGraph.class,
            GraphDocument.class, CompiledGraphMetadata.class, String.class, CorrelationId.class, Throwable.class);
        report.setAccessible(true);
        CorrelationId invocationId = CorrelationId.deterministic("bounded-runtime-failure");
        try {
            FlowExecutor.FlowExecutionException failure = new FlowExecutor.FlowExecutionException(
                "CORE_EXECUTION_FAILED", "Failed", null, fixture.start(), "Inspect runtime diagnostics",
                Map.of("diagnostics", values));
            report.invoke(fixture.execution(), fixture.graph(), null, null, fixture.start(), invocationId, failure);
            assertEquals(List.of(source.code(), "RUNTIME.INVOCATION_AUDIT"),
                fixture.diagnostics().stream().map(Diagnostic::code).toList());
            fixture.diagnostics().clear();
            values.clear();
            for (int index = 0; index < 32; index++) values.add(Map.of("code", "INVALID"));
            values.add(source.toMap());
            report.invoke(fixture.execution(), fixture.graph(), null, null, fixture.start(), invocationId, failure);
            assertEquals(List.of("GRAPH.OPAQUE_UNAVAILABLE", "RUNTIME.HANDLER_FAILURE", "RUNTIME.INVOCATION_AUDIT"),
                fixture.diagnostics().stream().map(Diagnostic::code).toList());
            Method classify = CompiledTriggerExecution.class.getDeclaredMethod("terminalOutcome", Throwable.class);
            classify.setAccessible(true);
            for (String code : List.of("CORE_EXECUTION_TIMEOUT", "CORE_EXECUTION_CANCELLED")) {
                FlowExecutor.FlowExecutionException outer = new FlowExecutor.FlowExecutionException(code, "Failed", null,
                    fixture.start(), "Inspect runtime diagnostics", Map.of("diagnostics", List.of(source.toMap())));
                Object outcome = classify.invoke(fixture.execution(), outer);
                Method outcomeValue = outcome.getClass().getDeclaredMethod("outcome");
                Method diagnosticCode = outcome.getClass().getDeclaredMethod("diagnosticCode");
                outcomeValue.setAccessible(true);
                diagnosticCode.setAccessible(true);
                assertEquals(code.endsWith("TIMEOUT") ? "timeout" : "cancelled", outcomeValue.invoke(outcome));
                assertEquals(source.code(), diagnosticCode.invoke(outcome));
            }
        } finally {
            fixture.executor().shutdown();
        }
    }

}
