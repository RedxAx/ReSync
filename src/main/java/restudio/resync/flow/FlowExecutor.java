package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionDiagnostic;
import restudio.resync.flow.function.FunctionOutputMap;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeExecutionProvenance;
import restudio.resync.flow.runtime.RuntimeLeaseInput;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuditEvent;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeFailure;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.flow.validation.FlowGraphValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class FlowExecutor {
    private static final long DEFAULT_MAX_EXECUTION_DURATION_MILLIS = 300_000L;
    private static final int DEFAULT_MAX_FUNCTION_CALL_DEPTH = 64;
    private static final int MAX_AUDIT_RECORDS = 1_024;
    private static final Set<String> FUNCTION_CALL_RESERVED_INPUTS = Set.of("function", "arguments", "continue_on_failure");
    private static final String CALL_PARAMETERS_KEY = "__call_parameters";
    private final HandlerRegistry handlerRegistry;
    private final NodeDefinitionRegistry nodeDefinitionRegistry;
    private final TypeAdapterRegistry typeAdapter;
    private final Map<String, Object> globalVariables;
    private final int maxExecutionSteps;
    private final boolean enableDebug;
    private final long maxExecutionDurationMillis;
    private final int maxFunctionCallDepth;
    private final LegacyRuntimeActivationGate legacyRuntimeGate;
    private volatile FlowExecutionBridge executionBridge;
    private volatile CompiledFunctionExecutionBridge compiledFunctionExecutionBridge;
    private volatile RuntimeAuthority compiledFunctionAuthority;
    private volatile RuntimePrincipalAuthority compiledFunctionPrincipalAuthority;
    private volatile RuntimeReceiptStore compiledFunctionReceiptStore;
    private volatile ServerId compiledFunctionServerId;
    private volatile RuntimePrincipal compiledFunctionDefaultPrincipal;
    private volatile RuntimeAuditBoundary compiledFunctionAuditBoundary = RuntimeAuditBoundary.unavailable();
    private final Map<String, FunctionResult> compiledFunctionReplayResults = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<FunctionResult>> compiledFunctionInFlight = new ConcurrentHashMap<>();
    private final Map<String, Object> eventVariables = new ConcurrentHashMap<>();
    private final Map<CorrelationId, LiveEventScope> liveEvents = new ConcurrentHashMap<>();
    private final Map<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, WallClockTask> wallClockTasks = new ConcurrentHashMap<>();
    private final Map<String, TerminalTask> terminalTasks = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor wallClockScheduler;
    private final List<FlowExecutionListener> executionListeners = new CopyOnWriteArrayList<>();
    private final Deque<FlowNodeAuditRecord> auditRecords = new ArrayDeque<>();
    private final Object admissionMonitor = new Object();
    private final List<CompletableFuture<Void>> drainWaiters = new ArrayList<>();
    private int activeLegacyExecutions;
    private int admissionFenceDepth;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private FlowGraphValidator graphValidator;
    private Predicate<FlowGraph> executionAuthority = graph -> true;
    private BiPredicate<FlowGraph, CompiledGraphMetadata> compiledExecutionAuthority = (graph, metadata) -> executionAuthority.test(graph);
    private FlowNodeAuthorizationPolicy authorizationPolicy = (context, node, definition) -> {
        String policy = definition != null ? definition.getAuthorizationPolicy() : "trusted_server_flow";
        return "trusted_server_flow".equals(policy) || "public".equals(policy)
            ? FlowNodeAuthorizationPolicy.AuthorizationDecision.allow()
            : FlowNodeAuthorizationPolicy.AuthorizationDecision.deny(policy, node != null ? node.getType() : "");
    };

    public record FunctionInvocationContext(RuntimePrincipal principal, CorrelationId invocationId,
                                            CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis,
                                            String sessionReference, String creatorPrincipal,
                                            String creatorSessionReference) {
        public FunctionInvocationContext {
            principal = Objects.requireNonNull(principal, "Function Principal Is Required");
            invocationId = Objects.requireNonNull(invocationId, "Function Invocation ID Is Required");
            if (runtimeContext != null && runtimeContext.principal() != null
                && !principal.canonical().equals(runtimeContext.principal().canonical())) {
                throw new IllegalArgumentException("Function Runtime Context Principal Must Match Invocation Principal");
            }
            if (requestedDeadlineMillis < 0) {
                throw new IllegalArgumentException("Function Deadline Cannot Be Negative");
            }
            sessionReference = sessionReference == null ? null : sessionReference.trim();
            if (sessionReference != null && sessionReference.isEmpty()) {
                throw new IllegalArgumentException("Function Session Reference Is Required");
            }
            creatorPrincipal = creatorPrincipal == null ? null : creatorPrincipal.trim();
            creatorSessionReference = creatorSessionReference == null ? null : creatorSessionReference.trim();
            if (creatorPrincipal != null && creatorPrincipal.isEmpty()) {
                throw new IllegalArgumentException("Function Creator Principal Is Required");
            }
            if (creatorSessionReference != null && creatorSessionReference.isEmpty()) {
                throw new IllegalArgumentException("Function Creator Session Reference Is Required");
            }
            if (creatorPrincipal == null && creatorSessionReference != null) {
                throw new IllegalArgumentException("Function Creator Session Requires A Creator Principal");
            }
        }

        public FunctionInvocationContext(RuntimePrincipal principal, CorrelationId invocationId,
                                         CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis) {
            this(principal, invocationId, runtimeContext, requestedDeadlineMillis, null);
        }

        public FunctionInvocationContext(RuntimePrincipal principal, CorrelationId invocationId,
                                         CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis,
                                         String sessionReference) {
            this(principal, invocationId, runtimeContext, requestedDeadlineMillis, sessionReference, null, null);
        }

        public FunctionInvocationContext child(String identity) {
            String normalized = Objects.requireNonNull(identity, "Function Child Identity Is Required").trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Function Child Identity Is Required");
            }
            return new FunctionInvocationContext(principal,
                CorrelationId.deterministic(invocationId.value(), "compiled-function-child", normalized),
                runtimeContext, requestedDeadlineMillis, sessionReference, creatorPrincipal, creatorSessionReference);
        }
    }

    private record PendingTask(String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                               long createdAt, long nextFireAt, boolean recurring, String lastFailure, FlowTask operation,
                               Runnable cancelAction) {
    }

    private record TerminalTask(ScheduledTaskSnapshot snapshot, long completedAt) {
    }

    public final class AdmissionFence implements AutoCloseable {
        private boolean closed;

        private AdmissionFence() {
        }

        public void awaitDrained() {
            awaitLegacyExecutions();
        }

        public boolean awaitDrained(Duration timeout) {
            Objects.requireNonNull(timeout, "Admission Drain Timeout Is Required");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Admission Drain Timeout Cannot Be Negative");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean interrupted = false;
            synchronized (admissionMonitor) {
                while (activeLegacyExecutions > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(admissionMonitor, remaining);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        break;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return isDrained();
        }

        public CompletableFuture<Void> whenDrained() {
            return awaitLegacyExecutionsAsync();
        }

        public boolean isDrained() {
            synchronized (admissionMonitor) {
                return activeLegacyExecutions == 0;
            }
        }

        @Override
        public void close() {
            synchronized (admissionMonitor) {
                if (closed) {
                    return;
                }
                closed = true;
                admissionFenceDepth = Math.max(0, admissionFenceDepth - 1);
                admissionMonitor.notifyAll();
            }
        }
    }

    private final class LegacyAdmission implements AutoCloseable {
        private boolean released;

        @Override
        public void close() {
            List<CompletableFuture<Void>> completedWaiters = List.of();
            synchronized (admissionMonitor) {
                if (released) {
                    return;
                }
                released = true;
                activeLegacyExecutions = Math.max(0, activeLegacyExecutions - 1);
                if (activeLegacyExecutions == 0 && !drainWaiters.isEmpty()) {
                    completedWaiters = new ArrayList<>(drainWaiters);
                    drainWaiters.clear();
                }
                admissionMonitor.notifyAll();
            }
            for (CompletableFuture<Void> waiter : completedWaiters) {
                waiter.complete(null);
            }
        }
    }

    private static final class WallClockTask {
        private final String graphId;
        private final String runtimeOwner;
        private final CompletableFuture<Void> completion;
        private final FlowTask operation;
        private final long createdAt;
        private final long nextFireAt;
        private final boolean recurring;
        private volatile ScheduledFuture<?> timer;
        private volatile boolean cancelled;
        private volatile String lastFailure = "";

        private WallClockTask(String graphId, String runtimeOwner, CompletableFuture<Void> completion, long createdAt, long nextFireAt, boolean recurring) {
            this.graphId = graphId != null ? graphId : "";
            this.runtimeOwner = runtimeOwner != null && !runtimeOwner.isBlank() ? runtimeOwner : "flow_wall_clock";
            this.completion = completion;
            this.operation = new FlowTask(completion);
            this.createdAt = createdAt;
            this.nextFireAt = nextFireAt;
            this.recurring = recurring;
        }

        private void attach(ScheduledFuture<?> timer) {
            this.timer = timer;
            if (cancelled && timer != null) {
                timer.cancel(false);
            }
        }

        private void cancel() {
            cancelled = true;
            ScheduledFuture<?> activeTimer = timer;
            if (activeTimer != null) {
                activeTimer.cancel(false);
            }
            operation.cancel();
        }
    }

    public record ScheduledTaskSnapshot(String taskId, String runtimeOwner, String graphId, long createdAt, long nextFireAt,
                                        boolean recurring, ScheduledTaskState state, String lastFailure) {
    }

    public enum ScheduledTaskState {
        ACTIVE,
        CANCELLED,
        FINISHED,
        FAILED
    }

    public enum TaskCancellationStatus {
        CANCELLED,
        ALREADY_CANCELLED,
        FINISHED,
        UNKNOWN
    }

    public record CompiledExecutionAuthority(ContentHash bridgeHash, ContentHash catalogHash, ContentHash runtimeHash) {
        public CompiledExecutionAuthority {
            bridgeHash = Objects.requireNonNull(bridgeHash, "Compiled Bridge Hash Is Required");
            catalogHash = Objects.requireNonNull(catalogHash, "Compiled Catalog Hash Is Required");
            runtimeHash = Objects.requireNonNull(runtimeHash, "Compiled Runtime Hash Is Required");
        }

        public boolean matches(CompiledCoreFlowExecutionBridge bridge, CompiledGraphMetadata metadata) {
            return bridge != null
                && metadata != null
                && bridgeHash.equals(CompiledCoreFlowExecutionBridge.authorityHash())
                && catalogHash.equals(metadata.catalogBinding().catalogChecksum())
                && runtimeHash.equals(metadata.catalogBinding().bindingManifestHash());
        }
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, null, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        FlowExecutionBridge executionBridge) {
        this(handlerRegistry, null, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH, executionBridge);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter,
                        Map<String, Object> globalVariables, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, 10000, false,
            DEFAULT_MAX_EXECUTION_DURATION_MILLIS, DEFAULT_MAX_FUNCTION_CALL_DEPTH, null, legacyRuntimeGate);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter,
                        Map<String, Object> globalVariables, FlowExecutionBridge executionBridge) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH, executionBridge);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                       int maxExecutionSteps, boolean enableDebug) {
        this(handlerRegistry, null, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                       int maxExecutionSteps, boolean enableDebug, FlowExecutionBridge executionBridge) {
        this(handlerRegistry, null, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, DEFAULT_MAX_EXECUTION_DURATION_MILLIS,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH, executionBridge);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                         int maxExecutionSteps, boolean enableDebug) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter,
                        Map<String, Object> globalVariables, int maxExecutionSteps, boolean enableDebug,
                        FlowExecutionBridge executionBridge) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug,
            DEFAULT_MAX_EXECUTION_DURATION_MILLIS, DEFAULT_MAX_FUNCTION_CALL_DEPTH, executionBridge);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, maxExecutionDurationMillis,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter,
                        Map<String, Object> globalVariables, int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis,
                        FlowExecutionBridge executionBridge) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, maxExecutionDurationMillis,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH, executionBridge);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis, int maxFunctionCallDepth) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, maxExecutionDurationMillis,
            maxFunctionCallDepth, null);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis, int maxFunctionCallDepth,
                        FlowExecutionBridge executionBridge) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, maxExecutionDurationMillis,
            maxFunctionCallDepth, executionBridge, null);
    }

    private FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                         int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis, int maxFunctionCallDepth,
                         FlowExecutionBridge executionBridge, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this.handlerRegistry = handlerRegistry;
        this.nodeDefinitionRegistry = nodeDefinitionRegistry;
        this.typeAdapter = typeAdapter;
        this.globalVariables = new ConcurrentHashMap<>();
        if (globalVariables != null) {
            globalVariables.forEach((key, value) -> {
                if (key != null && value != null) {
                    this.globalVariables.put(key, value);
                }
            });
        }
        this.maxExecutionSteps = maxExecutionSteps;
        this.enableDebug = enableDebug;
        this.maxExecutionDurationMillis = Math.max(0L, maxExecutionDurationMillis);
        this.maxFunctionCallDepth = Math.max(1, maxFunctionCallDepth);
        this.legacyRuntimeGate = legacyRuntimeGate;
        this.executionBridge = executionBridge;
        this.wallClockScheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("ReSync-Flow-WallClock").factory());
        this.wallClockScheduler.setRemoveOnCancelPolicy(true);
    }

    public void configureExecutionBridge(FlowExecutionBridge executionBridge) {
        FlowExecutionBridge configuredBridge = Objects.requireNonNull(executionBridge, "Flow Execution Bridge Is Required");
        synchronized (admissionMonitor) {
            if (this.executionBridge != null && this.executionBridge != configuredBridge) {
                throw new IllegalStateException("Flow execution bridge is already configured");
            }
            this.executionBridge = configuredBridge;
        }
    }

    public void configureCompiledFunctionBridge(CompiledFunctionExecutionBridge compiledFunctionExecutionBridge) {
        CompiledFunctionExecutionBridge configuredBridge = Objects.requireNonNull(
            compiledFunctionExecutionBridge, "Compiled Function Execution Bridge Is Required");
        synchronized (admissionMonitor) {
            if (this.compiledFunctionExecutionBridge != null && this.compiledFunctionExecutionBridge != configuredBridge) {
                throw new IllegalStateException("Compiled Function execution bridge is already configured");
            }
            this.compiledFunctionExecutionBridge = configuredBridge;
        }
    }

    public void configureCompiledFunctionRuntime(RuntimeAuthority authority,
                                                 RuntimePrincipalAuthority principalAuthority,
                                                 RuntimeReceiptStore receiptStore) {
        configureCompiledFunctionRuntime(authority, principalAuthority, receiptStore, null, null, null);
    }

    public void configureCompiledFunctionRuntime(RuntimeAuthority authority,
                                                 RuntimePrincipalAuthority principalAuthority,
                                                 RuntimeReceiptStore receiptStore,
                                                 ServerId serverId) {
        configureCompiledFunctionRuntime(authority, principalAuthority, receiptStore, serverId, null);
    }

    public void configureCompiledFunctionRuntime(RuntimeAuthority authority,
                                                 RuntimePrincipalAuthority principalAuthority,
                                                 RuntimeReceiptStore receiptStore,
                                                 ServerId serverId,
                                                 RuntimePrincipal defaultPrincipal) {
        configureCompiledFunctionRuntime(authority, principalAuthority, receiptStore, serverId, defaultPrincipal, null);
    }

    public void configureCompiledFunctionRuntime(RuntimeAuthority authority,
                                                 RuntimePrincipalAuthority principalAuthority,
                                                 RuntimeReceiptStore receiptStore,
                                                 ServerId serverId,
                                                 RuntimePrincipal defaultPrincipal,
                                                 RuntimeAuditBoundary auditBoundary) {
        this.compiledFunctionAuthority = Objects.requireNonNull(authority, "Compiled Function Runtime Authority Is Required");
        this.compiledFunctionPrincipalAuthority = Objects.requireNonNull(principalAuthority,
            "Compiled Function Principal Authority Is Required");
        this.compiledFunctionReceiptStore = Objects.requireNonNull(receiptStore,
            "Compiled Function Receipt Store Is Required");
        this.compiledFunctionServerId = serverId;
        if (defaultPrincipal != null && !principalAuthority.trusts(defaultPrincipal, authority)) {
            throw new IllegalArgumentException("Compiled Function Default Principal Is Not Trusted");
        }
        this.compiledFunctionDefaultPrincipal = defaultPrincipal;
        this.compiledFunctionAuditBoundary = auditBoundary == null ? RuntimeAuditBoundary.unavailable() : auditBoundary;
    }

    public FunctionInvocationContext defaultFunctionInvocationContext(Player player, Event event,
                                                                       Map<String, Object> eventVariables) {
        return defaultFunctionInvocationContext(player, event, eventVariables, CorrelationId.random(),
            RuntimeExecutionContext.NO_DEADLINE);
    }

    public FunctionInvocationContext defaultFunctionInvocationContext(Player player, Event event,
                                                                       Map<String, Object> eventVariables,
                                                                       CorrelationId invocationId,
                                                                       long requestedDeadlineMillis) {
        RuntimePrincipal principal = compiledFunctionDefaultPrincipal;
        if (principal == null || compiledFunctionServerId == null || compiledFunctionExecutionBridge == null) {
            return null;
        }
        return functionInvocationContext(player, event, eventVariables, principal, invocationId, requestedDeadlineMillis);
    }

    public FunctionInvocationContext functionInvocationContext(Player player, Event event,
                                                               Map<String, Object> eventVariables,
                                                               RuntimePrincipal principal,
                                                               CorrelationId invocationId,
                                                               long requestedDeadlineMillis) {
        return functionInvocationContext(player, event, eventVariables, principal, invocationId,
            requestedDeadlineMillis, null, null);
    }

    public FunctionInvocationContext functionInvocationContext(Player player, Event event,
                                                               Map<String, Object> eventVariables,
                                                               RuntimePrincipal principal,
                                                               CorrelationId invocationId,
                                                               long requestedDeadlineMillis,
                                                               String creatorPrincipal,
                                                               String creatorSessionReference) {
        Objects.requireNonNull(principal, "Function Principal Is Required");
        Objects.requireNonNull(invocationId, "Function Invocation ID Is Required");
        if (compiledFunctionServerId == null) {
            throw new IllegalStateException("Compiled Function Server Identity Is Unavailable");
        }
        if (compiledFunctionPrincipalAuthority == null || compiledFunctionAuthority == null
            || !compiledFunctionPrincipalAuthority.trusts(principal, compiledFunctionAuthority)) {
            throw new IllegalArgumentException("Function Principal Is Not Trusted");
        }
        CompiledRuntimeContextAdapter.Result adapted = CompiledRuntimeContextAdapter.adapt(
            compiledFunctionServerId, principal, player, event, eventVariables == null ? Map.of() : eventVariables);
        if (!adapted.accepted()) {
            throw new IllegalArgumentException("Compiled Function Runtime Context Is Unsupported: " + adapted.failure());
        }
        String sessionReference = eventVariables != null && eventVariables.get("runtime.sessionId") instanceof String value
            ? value : null;
        return new FunctionInvocationContext(principal, invocationId, adapted.context(), requestedDeadlineMillis,
            sessionReference, creatorPrincipal, creatorSessionReference);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event) {
        return execute(graph, startNodeId, player, event, new HashMap<>());
    }

    public CompletableFuture<Void> execute(FlowGraph graph, Player player, Event event, Map<String, Object> eventVars) {
        return execute(graph, player, event, eventVars, null);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, Player player, Event event, Map<String, Object> eventVars,
                                            FlowExecutionBridge.MappingContext mappingContext) {
        return execute(graph, player, event, eventVars, mappingContext, null);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, Player player, Event event, Map<String, Object> eventVars,
                                            FlowExecutionBridge.MappingContext mappingContext,
                                            CompiledGraphMetadata compiledGraphMetadata) {
        return withLegacyAdmission(true,
            () -> executeRootWithoutStart(graph, player, event, eventVars, mappingContext, compiledGraphMetadata));
    }

    private CompletableFuture<Void> executeRootWithoutStart(FlowGraph graph, Player player, Event event,
                                                              Map<String, Object> eventVars,
                                                              FlowExecutionBridge.MappingContext mappingContext,
                                                              CompiledGraphMetadata compiledGraphMetadata) {
        if (executionBlocked(graph)) return CompletableFuture.completedFuture(null);
        FlowGraphValidationException validationFailure = validationFailure(graph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        String startNodeId = findStartNode(graph);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FLOW_START_MISSING",
                "Flow has no executable start node",
                null,
                null,
                "Add or connect an executable trigger or start node"
            ));
        }
        return executeValidated(graph, startNodeId, player, event, eventVars, mappingContext, compiledGraphMetadata);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVars) {
        return execute(graph, startNodeId, player, event, eventVars, null);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVars,
                                            FlowExecutionBridge.MappingContext mappingContext) {
        return execute(graph, startNodeId, player, event, eventVars, mappingContext, null);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVars,
                                            FlowExecutionBridge.MappingContext mappingContext,
                                            CompiledGraphMetadata compiledGraphMetadata) {
        return withLegacyAdmission(true,
            () -> executeRoot(graph, startNodeId, player, event, eventVars, mappingContext, compiledGraphMetadata));
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId, Player player, Event event,
                                                   Map<String, Object> eventVars,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge) {
        return executeCompiled(graph, startNodeId, player, event, eventVars, mappingContext, compiledGraphMetadata,
            authority, bridge, CorrelationId.random());
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId, Player player, Event event,
                                                   Map<String, Object> eventVars,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge,
                                                   CorrelationId invocationId) {
        if (compiledGraphMetadata == null) {
            return bridgeUnsupported("Compiled graph metadata is required", graph, startNodeId);
        }
        if (authority == null) {
            return bridgeUnsupported("Compiled execution authority is required", graph, startNodeId);
        }
        if (bridge == null) {
            return bridgeUnsupported("Compiled Core execution bridge is required", graph, startNodeId);
        }
        if (!authority.matches(bridge, compiledGraphMetadata)) {
            return bridgeFailure("CORE_EXECUTION_UNSUPPORTED", "Compiled execution authority does not match the graph binding",
                null, graph, startNodeId, "Refresh the compiled graph against the active bridge, catalog, and runtime hashes");
        }
        if (graph == null) {
            return bridgeUnsupported("A legacy graph envelope is required for compiled execution", null, startNodeId);
        }
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        return withLegacyAdmission(true,
            () -> executeCompiledRoot(graph, startNodeId, player, event, eventVars, mappingContext, compiledGraphMetadata,
                null, bridge, invocationId, RuntimeExecutionContext.NO_DEADLINE));
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId,
                                                   CompiledRuntimeContext runtimeContext,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge) {
        return executeCompiled(graph, startNodeId, runtimeContext, mappingContext, compiledGraphMetadata, authority,
            bridge, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId,
                                                   CompiledRuntimeContext runtimeContext,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge,
                                                   CorrelationId invocationId) {
        return executeCompiled(graph, startNodeId, runtimeContext, mappingContext, compiledGraphMetadata, authority,
            bridge, invocationId, RuntimeExecutionContext.NO_DEADLINE);
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId,
                                                   CompiledRuntimeContext runtimeContext,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge,
                                                   CorrelationId invocationId,
                                                   long requestedDeadlineMillis) {
        if (compiledGraphMetadata == null) {
            return bridgeUnsupported("Compiled graph metadata is required", graph, startNodeId);
        }
        if (authority == null) {
            return bridgeUnsupported("Compiled execution authority is required", graph, startNodeId);
        }
        if (bridge == null) {
            return bridgeUnsupported("Compiled Core execution bridge is required", graph, startNodeId);
        }
        if (!authority.matches(bridge, compiledGraphMetadata)) {
            return bridgeFailure("CORE_EXECUTION_UNSUPPORTED", "Compiled execution authority does not match the graph binding",
                null, graph, startNodeId, "Refresh the compiled graph against the active bridge, catalog, and runtime hashes");
        }
        if (graph == null) {
            return bridgeUnsupported("A legacy graph envelope is required for compiled execution", null, startNodeId);
        }
        if (runtimeContext == null) {
            return bridgeUnsupported("A typed compiled runtime context is required", graph, startNodeId);
        }
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        if (requestedDeadlineMillis < 0) {
            return bridgeUnsupported("Compiled execution deadline is invalid", graph, startNodeId);
        }
        return withLegacyAdmission(true,
            () -> executeCompiledRoot(graph, startNodeId, null, null, Map.of(), mappingContext, compiledGraphMetadata,
                runtimeContext, bridge, invocationId, requestedDeadlineMillis));
    }

    public CompletableFuture<Void> executeCompiledSource(GraphDocument source, FlowGraph identity, String startNodeId,
                                                         CompiledRuntimeContext runtimeContext,
                                                         FlowExecutionBridge.MappingContext mappingContext,
                                                         CompiledGraphMetadata metadata, CompiledExecutionAuthority authority,
                                                         CompiledCoreFlowExecutionBridge bridge, CorrelationId invocationId,
                                                         long deadlineMillis) {
        if (source == null || identity == null || runtimeContext == null || metadata == null || authority == null
            || bridge == null || invocationId == null || deadlineMillis < 0 || !authority.matches(bridge, metadata)) {
            return bridgeUnsupported("Complete typed compiled execution authority is required", identity, startNodeId);
        }
        return withLegacyAdmission(true, () -> {
            if (!identity.isEnabled() || !compiledExecutionAuthority.test(identity, metadata)) {
                return bridgeFailure("CORE_EXECUTION_UNSUPPORTED", "The typed source is no longer authorized for execution",
                    null, identity, startNodeId, "Refresh the committed source and compiled catalog binding");
            }
            notifyExecutionListeners(identity, startNodeId, null, null);
            return executeThroughBridge(bridge, identity, startNodeId, null, null, Map.of(), mappingContext,
                metadata, runtimeContext, invocationId, deadlineMillis, source);
        });
    }

    private CompletableFuture<Void> executeCompiledRoot(FlowGraph graph, String startNodeId, Player player, Event event,
                                                        Map<String, Object> eventVars,
                                                        FlowExecutionBridge.MappingContext mappingContext,
                                                        CompiledGraphMetadata compiledGraphMetadata,
                                                        CompiledRuntimeContext runtimeContext,
                                                        CompiledCoreFlowExecutionBridge bridge,
                                                        CorrelationId invocationId,
                                                        long requestedDeadlineMillis) {
        if (!graph.isEnabled() || !compiledExecutionAuthority.test(graph, compiledGraphMetadata)) {
            return CompletableFuture.completedFuture(null);
        }
        notifyExecutionListeners(graph, startNodeId, player, event);
        return executeThroughBridge(bridge, graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, runtimeContext, invocationId, requestedDeadlineMillis);
    }

    private CompletableFuture<Void> executeRoot(FlowGraph graph, String startNodeId, Player player, Event event,
                                                 Map<String, Object> eventVars,
                                                 FlowExecutionBridge.MappingContext mappingContext,
                                                 CompiledGraphMetadata compiledGraphMetadata) {
        if (executionBlocked(graph)) return CompletableFuture.completedFuture(null);
        FlowGraphValidationException validationFailure = validationFailure(graph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        return executeValidated(graph, startNodeId, player, event, eventVars, mappingContext, compiledGraphMetadata);
    }

    private CompletableFuture<Void> executeValidated(FlowGraph graph, String startNodeId, Player player, Event event,
                                                     Map<String, Object> eventVars,
                                                     FlowExecutionBridge.MappingContext mappingContext,
                                                     CompiledGraphMetadata compiledGraphMetadata) {
        FlowGraph executionGraph = graph != null ? graph.copy() : null;
        notifyExecutionListeners(executionGraph, startNodeId, player, event);
        FlowExecutionBridge configuredBridge = executionBridge;
        if (configuredBridge != null) {
            return executeThroughBridge(configuredBridge, executionGraph, startNodeId, player, event, eventVars,
                mappingContext, compiledGraphMetadata, null);
        }
        FlowRuntime runtime = new FlowRuntime(executionGraph, typeAdapter, globalVariables, eventVars, nodeDefinitionRegistry, legacyRuntimeGate);
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> future;
        try {
            future = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        future.whenComplete((result, ex) -> runtime.cleanupThreadLocals());
        return future;
    }

    private CompletableFuture<Void> executeThroughBridge(FlowGraph graph, String startNodeId, Player player, Event event,
                                                         Map<String, Object> eventVars,
                                                         FlowExecutionBridge.MappingContext mappingContext,
                                                         CompiledGraphMetadata compiledGraphMetadata,
                                                         CompiledRuntimeContext runtimeContext) {
        return executeThroughBridge(executionBridge, graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, runtimeContext, CorrelationId.random());
    }

    private CompletableFuture<Void> executeThroughBridge(FlowExecutionBridge bridge, FlowGraph graph, String startNodeId,
                                                          Player player, Event event, Map<String, Object> eventVars,
                                                          FlowExecutionBridge.MappingContext mappingContext,
                                                          CompiledGraphMetadata compiledGraphMetadata,
                                                          CompiledRuntimeContext runtimeContext) {
        return executeThroughBridge(bridge, graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, runtimeContext, CorrelationId.random());
    }

    private CompletableFuture<Void> executeThroughBridge(FlowExecutionBridge bridge, FlowGraph graph, String startNodeId,
                                                          Player player, Event event, Map<String, Object> eventVars,
                                                           FlowExecutionBridge.MappingContext mappingContext,
                                                           CompiledGraphMetadata compiledGraphMetadata,
                                                           CompiledRuntimeContext runtimeContext,
                                                           CorrelationId invocationId) {
        return executeThroughBridge(bridge, graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, runtimeContext, invocationId, RuntimeExecutionContext.NO_DEADLINE);
    }

    private CompletableFuture<Void> executeThroughBridge(FlowExecutionBridge bridge, FlowGraph graph, String startNodeId,
                                                          Player player, Event event, Map<String, Object> eventVars,
                                                          FlowExecutionBridge.MappingContext mappingContext,
                                                          CompiledGraphMetadata compiledGraphMetadata,
                                                          CompiledRuntimeContext runtimeContext,
                                                          CorrelationId invocationId,
                                                          long requestedDeadlineMillis) {
        return executeThroughBridge(bridge, graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, runtimeContext, invocationId, requestedDeadlineMillis, null);
    }

    private CompletableFuture<Void> executeThroughBridge(FlowExecutionBridge bridge, FlowGraph graph, String startNodeId,
                                                          Player player, Event event, Map<String, Object> eventVars,
                                                          FlowExecutionBridge.MappingContext mappingContext,
                                                          CompiledGraphMetadata compiledGraphMetadata,
                                                          CompiledRuntimeContext runtimeContext,
                                                          CorrelationId invocationId, long requestedDeadlineMillis,
                                                          GraphDocument sourceDocument) {
        FlowExecutionBridge.Context context;
        try {
            CompiledRuntimeContext legacyRuntimeContext = player == null && event == null
                && (eventVars == null || eventVars.isEmpty()) ? CompiledRuntimeContext.empty() : null;
            context = runtimeContext == null
                ? new FlowExecutionBridge.Context(graph, startNodeId, player, event, eventVars, mappingContext,
                    compiledGraphMetadata, legacyRuntimeContext, invocationId, requestedDeadlineMillis)
                : new FlowExecutionBridge.Context(graph, startNodeId, null, null, Map.of(), mappingContext,
                    compiledGraphMetadata, runtimeContext, invocationId, requestedDeadlineMillis, sourceDocument);
        } catch (Throwable failure) {
            return bridgeFailure("CORE_EXECUTION_FAILED", "Unable to create the compiled Core execution context", failure, graph, startNodeId,
                "Repair the legacy execution context before enabling the compiled Core bridge");
        }
        Optional<String> mappingFailure = context.mappingFailure();
        if (mappingFailure.isPresent()) {
            return bridgeUnsupported(mappingFailure.get(), graph, startNodeId);
        }

        CompletionStage<FlowExecutionBridge.Result> resultStage;
        try {
            resultStage = bridge.execute(context);
        } catch (Throwable failure) {
            return bridgeFailure("CORE_EXECUTION_FAILED", "Compiled Core execution bridge failed", failure, graph, startNodeId,
                "Inspect the compiled Core execution bridge");
        }
        if (resultStage == null) {
            return bridgeFailure("CORE_EXECUTION_FAILED", "Compiled Core execution bridge returned no result", null, graph, startNodeId,
                "Return an explicit compiled Core execution result");
        }

        try {
            return resultStage.handle((result, failure) -> {
                if (failure != null) {
                    return bridgeFailure("CORE_EXECUTION_FAILED", "Compiled Core execution failed", unwrapBridgeFailure(failure), graph, startNodeId,
                        "Inspect the compiled Core execution failure");
                }
                return bridgeResult(result, graph, startNodeId);
            }).thenCompose(result -> result).toCompletableFuture();
        } catch (Throwable failure) {
            return bridgeFailure("CORE_EXECUTION_FAILED", "Compiled Core execution bridge failed", failure, graph, startNodeId,
                "Inspect the compiled Core execution bridge");
        }
    }

    private CompletableFuture<Void> bridgeResult(FlowExecutionBridge.Result result, FlowGraph graph, String startNodeId) {
        if (result == null) {
            return bridgeFailure("CORE_EXECUTION_FAILED", "Compiled Core execution bridge returned no result", null, graph, startNodeId,
                "Return an explicit compiled Core execution result");
        }
        String reason = result.reason();
        return switch (result.status()) {
            case EXECUTED -> CompletableFuture.completedFuture(null);
            case UNSUPPORTED -> bridgeFailure(
                "CORE_EXECUTION_UNSUPPORTED", bridgeReason(reason, "Compiled Core execution is unsupported for this graph"), result.failure(), graph, startNodeId,
                "Migrate the graph to the compiled Core graph contract", result.diagnostics());
            case FAILED -> bridgeFailure(
                "CORE_EXECUTION_FAILED", bridgeReason(reason, "Compiled Core execution failed"), result.failure(), graph, startNodeId,
                "Inspect the compiled Core execution failure", result.diagnostics());
            case TIMEOUT -> bridgeFailure(
                "CORE_EXECUTION_TIMEOUT", bridgeReason(reason, "Compiled Core execution timed out"), result.failure(), graph, startNodeId,
                "Inspect the compiled Core execution deadline", result.diagnostics());
            case CANCELLED -> bridgeFailure(
                "CORE_EXECUTION_CANCELLED", bridgeReason(reason, "Compiled Core execution was cancelled"), result.failure(), graph,
                startNodeId, "Inspect the compiled Core execution cancellation", result.diagnostics());
        };
    }

    private CompletableFuture<Void> bridgeFailure(String code, String message, Throwable failure, FlowGraph graph, String startNodeId,
                                                  String remediation) {
        return bridgeFailure(code, message, failure, graph, startNodeId, remediation, List.of());
    }

    private CompletableFuture<Void> bridgeFailure(String code, String message, Throwable failure, FlowGraph graph, String startNodeId,
                                                  String remediation, List<Diagnostic> diagnostics) {
        String graphId = graph != null && graph.getId() != null ? graph.getId() : "";
        String nodeId = startNodeId != null ? startNodeId : "";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("graphId", graphId);
        details.put("bridge", "compiled-core");
        details.put("diagnostics", diagnostics == null ? List.of() : diagnostics.stream().map(Diagnostic::toMap).toList());
        return CompletableFuture.failedFuture(new FlowExecutionException(
            code, message, failure, nodeId, remediation,
            details));
    }

    private String bridgeReason(String reason, String fallback) {
        return reason != null && !reason.isBlank() ? reason : fallback;
    }

    private Throwable unwrapBridgeFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public CompletableFuture<Object> executeSubFlow(FlowGraph subGraph, String startNodeId, String outputNodeId, String outputPin,
                                                      Player player, Event event, Map<String, Object> localInputs) {
        if (executionBridge != null) {
            return compiledSubFlowUnavailable(subGraph, startNodeId, "FlowExecutor.executeSubFlow");
        }
        return withLegacyAdmission(true, () -> executeSubFlowRoot(subGraph, startNodeId, outputNodeId, outputPin, player, event, localInputs));
    }

    private CompletableFuture<Object> executeSubFlowRoot(FlowGraph subGraph, String startNodeId, String outputNodeId, String outputPin,
                                                           Player player, Event event, Map<String, Object> localInputs) {
        if (executionBlocked(subGraph)) return CompletableFuture.completedFuture(null);
        FlowGraphValidationException validationFailure = validationFailure(subGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        FlowRuntime runtime = new FlowRuntime(subGraph, typeAdapter, globalVariables, new HashMap<>(), nodeDefinitionRegistry, legacyRuntimeGate);
        if (localInputs != null) {
            runtime.getLocalVariables().putAll(localInputs);
        }
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> execution;
        try {
            execution = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        return execution
            .thenApply(v -> runtime.getNodeOutput(outputNodeId, outputPin))
            .whenComplete((result, failure) -> runtime.cleanupThreadLocals());
    }

    public CompletableFuture<Object> executeSubFlow(FlowGraph subGraph, String outputNodeId, String outputPin,
                                                     Player player, Event event, Map<String, Object> localInputs) {
        if (executionBridge != null) {
            return compiledSubFlowUnavailable(subGraph, null, "FlowExecutor.executeSubFlow");
        }
        return withLegacyAdmission(true, () -> {
            if (executionBlocked(subGraph)) return CompletableFuture.completedFuture(null);
            FlowGraphValidationException validationFailure = validationFailure(subGraph);
            if (validationFailure != null) {
                return CompletableFuture.failedFuture(validationFailure);
            }
            String startNodeId = findStartNode(subGraph);
            if (startNodeId == null) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "SUBFLOW_START_MISSING", "Subflow has no executable start node", null, null,
                    "Connect an executable subflow entry"
                ));
            }
            return executeSubFlowRoot(subGraph, startNodeId, outputNodeId, outputPin, player, event, localInputs);
        });
    }

    public CompletableFuture<Object> executeSubFlow(FlowRuntime parentRuntime, FlowGraph subGraph, String outputNodeId, String outputPin,
                                                     Player player, Event event, Map<String, Object> localInputs) {
        if (executionBridge != null) {
            return compiledSubFlowUnavailable(subGraph, null, "FlowExecutor.executeSubFlow");
        }
        return withLegacyAdmission(true, () -> executeSubFlowFromRuntime(parentRuntime, subGraph, outputNodeId, outputPin,
            player, event, localInputs));
    }

    private CompletableFuture<Object> executeSubFlowFromRuntime(FlowRuntime parentRuntime, FlowGraph subGraph, String outputNodeId,
                                                                  String outputPin, Player player, Event event,
                                                                  Map<String, Object> localInputs) {
        if (parentRuntime == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "SUBFLOW_RUNTIME_UNAVAILABLE", "Parent Flow runtime is unavailable", null, null,
                "Execute the subflow from an active Flow context"
            ));
        }
        if (executionBlocked(subGraph)) return CompletableFuture.completedFuture(null);
        FlowGraphValidationException validationFailure = validationFailure(subGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        String startNodeId = findStartNode(subGraph);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "SUBFLOW_START_MISSING", "Subflow has no executable start node", null, null,
                "Connect an executable subflow entry"
            ));
        }
        FlowRuntime runtime = parentRuntime.createSubRuntime(subGraph);
        if (localInputs != null) {
            runtime.getLocalVariables().putAll(localInputs);
        }
        CompletableFuture<Void> execution = execute(runtime, startNodeId, player, event, 0);
        return execution.thenApply(v -> runtime.getNodeOutput(outputNodeId, outputPin))
            .whenComplete((result, failure) -> runtime.cleanupThreadLocals());
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
                                                                   Map<String, Object> inputs, Map<String, Object> eventVars) {
        if (compiledFunctionExecutionBridge != null && compiledFunctionExecutionBridge.hasTypedFunctionProviders()) {
            FunctionInvocationContext context = defaultFunctionInvocationContext(player, event, eventVars);
            if (context == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("The Compiled Function Principal Is Unavailable"));
            }
            return executeFunction(functionGraph, player, event, inputs, eventVars, context);
        }
        return withLegacyAdmission(true, () -> executeFunctionRoot(functionGraph, player, event, inputs, eventVars));
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
                                                                   Map<String, Object> inputs, Map<String, Object> eventVars,
                                                                   RuntimePrincipal principal, CorrelationId invocationId,
                                                                   CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis) {
        return executeFunction(functionGraph, player, event, inputs, eventVars, principal, invocationId, runtimeContext,
            requestedDeadlineMillis, null, null);
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
                                                                   Map<String, Object> inputs, Map<String, Object> eventVars,
                                                                   RuntimePrincipal principal, CorrelationId invocationId,
                                                                   CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis,
                                                                   String creatorPrincipal, String creatorSessionReference) {
        if (principal == null || invocationId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_AUTHORIZATION_DENIED",
                "An authenticated compiled Function principal and invocation ID are required",
                null, null, "Authenticate the client and provide a canonical invocation ID"));
        }
        if (compiledFunctionPrincipalAuthority != null && compiledFunctionAuthority != null
            && !compiledFunctionPrincipalAuthority.trusts(principal, compiledFunctionAuthority)) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_AUTHORIZATION_DENIED",
                "The compiled Function principal is not trusted by the runtime authority",
                null, null, "Use the authenticated client principal issued by the active runtime authority"));
        }
        CompiledFunctionExecutionBridge bridge = compiledFunctionExecutionBridge;
        if (bridge != null && bridge.hasTypedFunctionProviders()) {
            return executeTypedFunctionGraph(functionGraph, player, event, inputs, eventVars, principal, invocationId,
                runtimeContext, requestedDeadlineMillis, creatorPrincipal, creatorSessionReference, bridge);
        }
        return withLegacyAdmission(true, () -> executeFunctionRoot(functionGraph, player, event, inputs, eventVars));
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
                                                                   Map<String, Object> inputs, Map<String, Object> eventVars,
                                                                   FunctionInvocationContext invocationContext) {
        return executeFunction(functionGraph, player, event, inputs, eventVars, invocationContext, null);
    }

    public CompletableFuture<Map<String, Object>> executeFunctionSource(FunctionSourceDocument source, Player player, Event event,
            Map<String, Object> inputs, Map<String, Object> eventVars, FunctionInvocationContext invocationContext) {
        return executeFunctionSource(source, player, event, inputs, eventVars, invocationContext, null);
    }

    public CompletableFuture<Map<String, Object>> executeFunctionSource(FunctionSourceDocument source, Player player, Event event,
            Map<String, Object> inputs, Map<String, Object> eventVars, FunctionInvocationContext invocationContext,
            RuntimeCancellationToken parentCancellation) {
        CompiledFunctionExecutionBridge bridge = compiledFunctionExecutionBridge;
        ServerId serverId = compiledFunctionServerId;
        RuntimeAuthority authority = compiledFunctionAuthority;
        if (bridge == null || !bridge.hasTypedFunctionProviders() || serverId == null || authority == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException("FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                "The compiled Function source runtime is unavailable", null, null, "Restore the compiled Function runtime"));
        }
        if (invocationContext == null || compiledFunctionPrincipalAuthority == null
            || !compiledFunctionPrincipalAuthority.trusts(invocationContext.principal(), authority)) {
            return CompletableFuture.failedFuture(new FlowExecutionException("FUNCTION_AUTHORIZATION_DENIED",
                "An authenticated Function invocation context is required", null, null, "Use a trusted runtime principal"));
        }
        Map<String, Object> contextVariables = new LinkedHashMap<>();
        if (eventVars != null) {
            contextVariables.putAll(eventVars);
        }
        if (invocationContext.sessionReference() != null) {
            contextVariables.put("runtime.sessionId", invocationContext.sessionReference());
        }
        CompiledFunctionExecutionRequest request;
        try {
            request = bridge.requestForSource(source, player, event, inputs, contextVariables, serverId, authority,
                invocationContext.principal(), invocationContext.invocationId(), invocationContext.runtimeContext(),
                invocationContext.requestedDeadlineMillis(), invocationContext.creatorPrincipal(), invocationContext.creatorSessionReference());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new FlowExecutionException("FUNCTION_COMPILED_EXECUTION_UNSUPPORTED",
                "The Function source could not be admitted", failure, null, "Restore the exact Function source and active catalog binding"));
        }
        return executeCompiledFunction(request, bridge, parentCancellation).thenCompose(result -> {
            if (!result.successful()) {
                return CompletableFuture.failedFuture(new FlowExecutionException("FUNCTION_COMPILED_EXECUTION_FAILED",
                    "Compiled Function execution failed", null, null, "Inspect the typed Function diagnostics",
                    Map.of("status", result.status().wireName(), "diagnosticCount", result.diagnostics().size())));
            }
            try {
                return CompletableFuture.completedFuture(bridge.outputsForSource(source, result));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new FlowExecutionException("FUNCTION_COMPILED_EXECUTION_FAILED",
                    "Compiled Function outputs do not match the Function source", failure, null, "Correct the Function output signature"));
            }
        });
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
            Map<String, Object> inputs, Map<String, Object> eventVars, FunctionInvocationContext invocationContext,
            RuntimeCancellationToken parentCancellation) {
        Objects.requireNonNull(invocationContext, "Function Invocation Context Is Required");
        Map<String, Object> contextVariables = new LinkedHashMap<>();
        if (eventVars != null) {
            contextVariables.putAll(eventVars);
        }
        if (invocationContext.sessionReference() != null) {
            contextVariables.put("runtime.sessionId", invocationContext.sessionReference());
        }
        CompiledFunctionExecutionBridge bridge = compiledFunctionExecutionBridge;
        if (bridge != null && bridge.hasTypedFunctionProviders()) {
            return executeTypedFunctionGraph(functionGraph, player, event, inputs, contextVariables, invocationContext.principal(),
                invocationContext.invocationId(), invocationContext.runtimeContext(), invocationContext.requestedDeadlineMillis(),
                invocationContext.creatorPrincipal(), invocationContext.creatorSessionReference(), bridge, parentCancellation);
        }
        return executeFunction(functionGraph, player, event, inputs, contextVariables, invocationContext.principal(),
            invocationContext.invocationId(), invocationContext.runtimeContext(), invocationContext.requestedDeadlineMillis(),
            invocationContext.creatorPrincipal(), invocationContext.creatorSessionReference());
    }

    private CompletableFuture<Map<String, Object>> executeTypedFunctionGraph(
        FlowGraph functionGraph,
        Player player,
        Event event,
        Map<String, Object> inputs,
        Map<String, Object> eventVars,
        RuntimePrincipal principal,
        CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext,
        long requestedDeadlineMillis,
        String creatorPrincipal,
        String creatorSessionReference,
        CompiledFunctionExecutionBridge bridge
    ) {
        return executeTypedFunctionGraph(functionGraph, player, event, inputs, eventVars, principal, invocationId, runtimeContext,
            requestedDeadlineMillis, creatorPrincipal, creatorSessionReference, bridge, null);
    }

    private CompletableFuture<Map<String, Object>> executeTypedFunctionGraph(FlowGraph functionGraph, Player player, Event event,
            Map<String, Object> inputs, Map<String, Object> eventVars, RuntimePrincipal principal, CorrelationId invocationId,
            CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis, String creatorPrincipal,
            String creatorSessionReference, CompiledFunctionExecutionBridge bridge, RuntimeCancellationToken parentCancellation) {
        ServerId serverId = compiledFunctionServerId;
        RuntimeAuthority authority = compiledFunctionAuthority;
        if (serverId == null || authority == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                "The compiled Function runtime identity is unavailable",
                null, null, "Restore the compiled Function runtime before invoking this Function"));
        }
        CompiledFunctionExecutionRequest request;
        try {
            request = bridge.requestForLegacyGraph(functionGraph, player, event, inputs, eventVars, serverId, authority,
                principal, invocationId, runtimeContext, requestedDeadlineMillis, creatorPrincipal,
                creatorSessionReference);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_UNSUPPORTED",
                "The legacy Function graph cannot be admitted to the compiled Function boundary",
                failure, null, "Correct the Function signature and active catalog binding"));
        }
        return executeCompiledFunction(request, bridge, parentCancellation).thenCompose(result -> {
            if (!result.successful()) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_COMPILED_EXECUTION_FAILED",
                    "Compiled Function execution failed",
                    null, null, "Inspect the typed Function diagnostics",
                    Map.of("status", result.status().wireName(), "diagnosticCount", result.diagnostics().size())));
            }
            try {
                return CompletableFuture.completedFuture(bridge.outputsForLegacyGraph(functionGraph, result));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_COMPILED_EXECUTION_FAILED",
                    "Compiled Function outputs do not match the Function graph",
                    failure, null, "Correct the Function output signature"));
            }
        });
    }

    private CompletableFuture<Map<String, Object>> executeFunctionRoot(FlowGraph functionGraph, Player player, Event event,
                                                                          Map<String, Object> inputs, Map<String, Object> eventVars) {
        if (functionGraph == null || !functionGraph.isFunction()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_INVALID",
                "A callable function graph is required",
                null,
                null,
                "Select an existing Flow function"
            ));
        }
        if (executionBlocked(functionGraph)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        FlowGraphValidationException validationFailure = validationFailure(functionGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        FlowGraph callable = FlowSerializer.deserialize(FlowSerializer.serialize(functionGraph));
        callable.adaptLegacyFunctionParameterIds();
        String startNodeId = findFunctionStartNodeId(callable);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_START_MISSING", "Function has no executable start node", null, null,
                "Add a Function Start node"
            ));
        }
        Map<String, Object> callInputs = new LinkedHashMap<>(inputs != null ? inputs : Map.of());
        Map<FunctionParameterId, Object> inputFrame;
        try {
            inputFrame = adaptLegacyFunctionInputFrame(callable, callInputs, true);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_ARGUMENT_UNKNOWN", failure.getMessage(), failure, startNodeId,
                "Remove the unknown arguments or update the function signature"));
        }
        FlowExecutionException inputFailure = validateFunctionInputFrame(callable, inputFrame, startNodeId);
        if (inputFailure != null) {
            return CompletableFuture.failedFuture(inputFailure);
        }
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), typeAdapter, globalVariables, eventVars, nodeDefinitionRegistry, legacyRuntimeGate);
        String callerNodeId = "__function_call";
        runtime.callFunctionById(callable, callerNodeId, inputFrame);
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> future;
        try {
            future = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        return future.thenApply(v -> {
            while (runtime.getCallDepth() > 0) {
                runtime.returnFromFunction(Collections.emptyMap());
            }
            return legacyFunctionOutputs(callable, runtime.getFunctionOutputsById());
        }).whenComplete((result, ex) -> runtime.cleanupThreadLocals());
    }

    private Map<String, Object> functionOutputs(FlowGraph functionGraph, FunctionResult result) {
        Map<FunctionParameterId, String> names = new LinkedHashMap<>();
        List<FlowGraph.FunctionParameter> declared = functionGraph != null && functionGraph.getFunctionOutputs() != null
            ? functionGraph.getFunctionOutputs() : List.of();
        for (FlowGraph.FunctionParameter parameter : declared) {
            if (parameter != null && parameter.getParameterId() != null) {
                names.put(parameter.getParameterId(), parameter.getName());
            }
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        Set<String> outputNames = new HashSet<>();
        result.outputs().values().forEach((key, value) -> {
            String name = names.get(key);
            String outputKey = name != null && !name.isBlank() ? name : key.canonicalText();
            if (!outputNames.add(outputKey)) {
                throw new IllegalArgumentException("Function output display name is ambiguous: " + outputKey);
            }
            outputs.put(outputKey, value.value());
        });
        return outputs;
    }

    public CompletableFuture<FunctionResult> executeCompiledFunction(
        CompiledFunctionExecutionRequest request,
        CompiledFunctionExecutionBridge bridge
    ) {
        return executeCompiledFunction(request, bridge, null);
    }

    public CompletableFuture<FunctionResult> executeCompiledFunction(CompiledFunctionExecutionRequest request,
            CompiledFunctionExecutionBridge bridge, RuntimeCancellationToken parentCancellation) {
        return withLegacyAdmission(true, () -> executeCompiledFunctionAdmitted(request, bridge, parentCancellation)).copy();
    }

    private CompletableFuture<FunctionResult> executeCompiledFunctionAdmitted(
        CompiledFunctionExecutionRequest request,
        CompiledFunctionExecutionBridge bridge,
        RuntimeCancellationToken parentCancellation
    ) {
        if (request == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                "A compiled Function execution request is required",
                null,
                null,
                "Provide the typed Function signature, catalog binding, and capability fingerprint"
            ));
        }
        if (bridge == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                "The compiled Function execution bridge is unavailable",
                null,
                null,
                "Initialize the replacement-owned compiled Function bridge before execution"
            ));
        }
        RuntimeAuthority authority = request.authority() != null ? request.authority() : compiledFunctionAuthority;
        RuntimePrincipal principal = request.principal();
        if (principal != null) {
            RuntimePrincipalAuthority principalAuthority = compiledFunctionPrincipalAuthority;
            if (authority == null || principalAuthority == null || !principalAuthority.trusts(principal, authority)) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_AUTHORIZATION_DENIED",
                    "The compiled Function principal is not trusted by the runtime authority",
                    null, null, "Use the authenticated client principal issued by the active runtime authority"));
            }
            if (request.runtimeContext() != null && request.runtimeContext().principal() != null
                && !principal.canonical().equals(request.runtimeContext().principal().canonical())) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_AUTHORIZATION_DENIED",
                    "The compiled Function runtime context principal does not match the request principal",
                    null, null, "Use one authenticated principal throughout the Function invocation"));
            }
            return executeCompiledFunctionDurably(request, bridge, authority, principal, parentCancellation);
        }
        try {
            return bridge.executeAsync(request, parentCancellation).toCompletableFuture();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_COMPILED_EXECUTION_FAILED",
                "Compiled Function execution failed",
                failure,
                null,
                "Inspect the typed Function source and capability binding"
            ));
        }
    }

    private CompletableFuture<FunctionResult> executeCompiledFunctionDurably(
        CompiledFunctionExecutionRequest request,
        CompiledFunctionExecutionBridge bridge,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        RuntimeCancellationToken parentCancellation
    ) {
        RuntimeReceiptStore store = compiledFunctionReceiptStore;
        if (store == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_DURABILITY_UNAVAILABLE", "The compiled Function receipt store is unavailable", null,
                null, "Restore the runtime receipt authority before executing authenticated Functions"));
        }
        if (request.catalogBinding() == null || request.capabilityFingerprint() == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_INVALID_INVOCATION", "The authenticated Function request has no complete catalog binding", null,
                null, "Provide the active typed Function catalog binding and capability fingerprint"));
        }
        RuntimeReceiptStore.InvocationLease invocationLease;
        try {
            invocationLease = store.acquireInvocationLease();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_DURABILITY_UNAVAILABLE", "The compiled Function receipt store rejected the invocation lease",
                failure, null, "Restore the runtime receipt authority before retrying"));
        }
        boolean retained = false;
        try {
            RuntimeReceiptStore.Key key = functionReceiptKey(request, authority, principal);
            ContentHash inputHash = functionInputHash(request, principal);
            RuntimeExecutionProvenance provenance = functionProvenance(request, authority, principal, key, inputHash);
            RuntimeLeaseInput.AuditEvent auditAttempt = functionAuditEvent(key, authority, provenance,
                RuntimeResult.Status.FAILURE, "attempt");
            RuntimeReceiptStore.Claim claim;
            try {
                claim = store.claim(key, inputHash);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_DURABILITY_UNAVAILABLE", "The compiled Function receipt store rejected the invocation",
                    failure, null, "Restore the runtime receipt authority before retrying"));
            }
            if (!claim.principalMatches()) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_AUTHORIZATION_DENIED", "The invocation ID belongs to another authenticated principal", null,
                    null, "Use a new invocation ID for a different authenticated client"));
            }
            String replayKey = functionReplayKey(key, inputHash);
            if (!claim.owner()) {
                FunctionResult cached = compiledFunctionReplayResults.get(replayKey);
                if (cached != null) {
                    return CompletableFuture.completedFuture(cached);
                }
                CompletableFuture<FunctionResult> inFlight = compiledFunctionInFlight.get(replayKey);
                if (inFlight != null) {
                    return inFlight;
                }
                if (!claim.inputMatches()) {
                    return CompletableFuture.failedFuture(new FlowExecutionException(
                        "FUNCTION_INVALID_INVOCATION", "The invocation ID was reused with different Function inputs", null,
                        null, "Retry with the original canonical Function request or use a new invocation ID"));
                }
                if (claim.outcome().isDone()) {
                    try {
                        FunctionResult recovered = functionResultFromReceipt(request, claim.outcome().join());
                        if (recovered != null) {
                            compiledFunctionReplayResults.put(replayKey, recovered);
                            return CompletableFuture.completedFuture(recovered);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "FUNCTION_REPLAY_RECOVERED", "The Function invocation was already durably reserved", null,
                    null, "Inspect the durable Function receipt before retrying"));
            }
            CompletableFuture<FunctionResult> running = new CompletableFuture<>();
            CompletableFuture<FunctionResult> previous = compiledFunctionInFlight.putIfAbsent(replayKey, running);
            if (previous != null) {
                return previous;
            }
            try {
                store.reserve(key, inputHash, provenance, auditAttempt);
            } catch (RuntimeException failure) {
                compiledFunctionInFlight.remove(replayKey, running);
                FlowExecutionException reservationFailure = new FlowExecutionException(
                    "FUNCTION_DURABILITY_UNAVAILABLE", "The compiled Function reservation could not be persisted", failure,
                    null, "Restore the runtime receipt authority before executing authenticated Functions");
                running.completeExceptionally(reservationFailure);
                return running;
            }
            CompletionStage<FunctionResult> execution;
            try {
                execution = Objects.requireNonNull(bridge.executeAsync(request, parentCancellation), "Function Physical Completion Stage Is Required");
            } catch (Throwable failure) {
                execution = CompletableFuture.failedFuture(failure);
            }
            retained = true;
            execution.whenComplete((result, failure) -> {
                try {
                    if (failure != null) {
                        throw new CompletionException(failure);
                    }
                    RuntimeResult persisted = functionReceiptResult(result);
                    RuntimeLeaseInput.AuditEvent auditEvent = functionAuditEvent(key, authority, provenance,
                        persisted.status(), "outcome");
                    store.complete(key, persisted, provenance, auditEvent);
                    deliverFunctionAudit(store, key, provenance, auditEvent);
                    compiledFunctionReplayResults.put(replayKey, result);
                    running.complete(result);
                } catch (Throwable executionFailure) {
                    FunctionDiagnostic diagnostic = FunctionDiagnostic.error("FUNCTION.EXECUTION_FAILURE", "execution",
                        "Compiled Function execution failed", null);
                    FunctionResult failed = FunctionResult.failure(request.execution().signature(), List.of(diagnostic), 0);
                    try {
                        RuntimeResult persisted = functionReceiptResult(failed);
                        RuntimeLeaseInput.AuditEvent auditEvent = functionAuditEvent(key, authority, provenance,
                            persisted.status(), "outcome");
                        store.complete(key, persisted, provenance, auditEvent);
                        deliverFunctionAudit(store, key, provenance, auditEvent);
                    } catch (RuntimeException | Error ignored) {
                    }
                    running.completeExceptionally(new FlowExecutionException(
                        "FUNCTION_COMPILED_EXECUTION_FAILED", "Compiled Function execution failed", executionFailure,
                        null, "Inspect the typed Function source and capability binding"));
                } finally {
                    compiledFunctionInFlight.remove(replayKey, running);
                    invocationLease.close();
                }
            });
            return running;
        } finally {
            if (!retained) {
                invocationLease.close();
            }
        }
    }

    private RuntimeLeaseInput.AuditEvent functionAuditEvent(RuntimeReceiptStore.Key key, RuntimeAuthority authority,
                                                             RuntimeExecutionProvenance provenance,
                                                             RuntimeResult.Status status, String phase) {
        return RuntimeAuditEvent.create(provenance.leaseId(), authority, key.binding(), key.idempotencyKey(), status,
            true, phase, provenance).leaseEvent();
    }

    private void deliverFunctionAudit(RuntimeReceiptStore store, RuntimeReceiptStore.Key key,
                                      RuntimeExecutionProvenance provenance, RuntimeLeaseInput.AuditEvent event) {
        RuntimeAuditBoundary boundary = compiledFunctionAuditBoundary;
        try {
            if (!boundary.available(RuntimeSemantics.Audit.FULL_REDACTED)) {
                store.recordPendingAudit(key, provenance, event,
                    new IllegalStateException("Compiled Function audit boundary is unavailable"));
                return;
            }
            boundary.record(event);
            store.markAuditRecorded(key, event);
        } catch (RuntimeException | Error failure) {
            try {
                store.recordPendingAudit(key, provenance, event, failure);
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private RuntimeReceiptStore.Key functionReceiptKey(CompiledFunctionExecutionRequest request,
                                                        RuntimeAuthority authority, RuntimePrincipal principal) {
        OwnerId owner = new OwnerId("resync");
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("compiled-function"));
        RuntimeBindingKey binding = new RuntimeBindingKey(
            ContractRef.of(owner, CapabilityId.of("function-execution")),
            ContractRef.of(owner, OperationId.of("test-run")));
        ContentHash plan = ContentHash.of(CanonicalJson.sha256("compiled-function-plan", Map.of(
            "function", request.function().canonicalText(), "revision", request.revision().value(),
            "catalog", request.catalogBinding() == null ? "" : request.catalogBinding().canonicalText())));
        return new RuntimeReceiptStore.Key(provider, plan, binding, request.capabilityFingerprint(), authority.identity(),
            request.execution().invocationId().toString(), principal.canonical(), RuntimeReceiptStore.IdempotencyKind.MUTATION_ID);
    }

    private ContentHash functionInputHash(CompiledFunctionExecutionRequest request, RuntimePrincipal principal) {
        return ContentHash.of(CanonicalJson.sha256("compiled-function-invocation", Map.of(
            "request", request.execution().canonicalValue(),
            "context", request.runtimeContext() == null ? Map.of() : request.runtimeContext().canonicalValue(),
            "deadlineMillis", request.requestedDeadlineMillis(),
            "session", request.sessionReference() == null ? "" : request.sessionReference(),
            "principal", principal.canonical(),
            "creatorPrincipal", request.creatorPrincipal() == null ? "" : request.creatorPrincipal(),
            "creatorSession", request.creatorSessionReference() == null ? "" : request.creatorSessionReference())));
    }

    private RuntimeExecutionProvenance functionProvenance(CompiledFunctionExecutionRequest request,
                                                           RuntimeAuthority authority, RuntimePrincipal principal,
                                                           RuntimeReceiptStore.Key key, ContentHash inputHash) {
        ContentHash contextHash = ContentHash.of(CanonicalJson.sha256("runtime-context",
            request.runtimeContext() == null ? Map.of() : request.runtimeContext().canonicalValue()));
        return new RuntimeExecutionProvenance(authority.identity(), principal, key.binding(), key.provider(),
            "compiled-function", request.catalogBinding().generation(), request.catalogBinding().bindingManifestHash(),
            request.catalogBinding().generation(), request.catalogBinding().catalogChecksum(), key.planFingerprint(),
            key.executionFingerprint(), key.idempotencyKey(), CorrelationId.of(request.execution().invocationId()),
            key.idempotencyKey(), inputHash, contextHash, request.requestedDeadlineMillis(),
            UUID.nameUUIDFromBytes(("compiled-function-lease:" + key.idempotencyKey()).getBytes(StandardCharsets.UTF_8)),
            request.sessionReference(), request.creatorPrincipal(), request.creatorSessionReference());
    }

    private RuntimeResult functionReceiptResult(FunctionResult result) {
        if (result.successful()) {
            Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
            result.outputs().values().forEach((key, value) -> outputs.put(functionOutputPin(key), value));
            return RuntimeResult.success(outputs, null);
        }
        if (result.diagnostics().isEmpty()) {
            return RuntimeResult.failure(new RuntimeFailure(
                Diagnostic.builder("RUNTIME.HANDLER_FAILURE", DiagnosticSeverity.ERROR,
                    DiagnosticPhase.ENVIRONMENT, "execution")
                    .messageKey(new ContractRef<>(new OwnerId("resync"), new CapabilityId("runtime-handler-failure")))
                    .message("Compiled Function execution failed")
                    .remediation("Inspect the compiled Function diagnostics")
                    .correlationId(UUID.randomUUID()).build(), false));
        }
        return RuntimeResult.failure(new RuntimeFailure(
            result.diagnostics().getFirst().diagnostic(), false));
    }

    private FunctionResult functionResultFromReceipt(CompiledFunctionExecutionRequest request, RuntimeResult receipt) {
        if (receipt == null) {
            return null;
        }
        if (receipt.successful()) {
            Map<FunctionParameterId, TypedValue> outputs = new LinkedHashMap<>();
            receipt.outputs().forEach((key, value) -> outputs.put(functionOutputId(key), value));
            return FunctionResult.success(request.execution().signature(), new FunctionOutputMap(outputs), 0);
        }
        Diagnostic diagnostic = receipt.failure() == null ? null : receipt.failure().diagnostic();
        FunctionDiagnostic functionDiagnostic;
        try {
            functionDiagnostic = diagnostic == null
                ? FunctionDiagnostic.error("FUNCTION.EXECUTION_FAILURE", "execution", "The Function execution failed", null)
                : FunctionDiagnostic.fromCanonical(diagnostic.toMap());
        } catch (RuntimeException ignored) {
            functionDiagnostic = FunctionDiagnostic.error("FUNCTION.EXECUTION_FAILURE", "execution", "The Function execution failed", null);
        }
        return receipt.status() == RuntimeResult.Status.CANCELLED
            ? FunctionResult.cancelled(request.execution().signature(), functionDiagnostic, 0)
            : FunctionResult.failure(request.execution().signature(), List.of(functionDiagnostic), 0);
    }

    private static PinId functionOutputPin(FunctionParameterId id) {
        return PinId.of("function-output-" + id.canonicalText());
    }

    private static FunctionParameterId functionOutputId(PinId pin) {
        String prefix = "function-output-";
        String value = pin.canonicalText();
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid persisted function output pin: " + value);
        }
        return FunctionParameterId.parseCanonicalText(value.substring(prefix.length()));
    }

    private static String functionReplayKey(RuntimeReceiptStore.Key key, ContentHash inputHash) {
        return key.authorityIdentity() + "|" + key.principalReference() + "|" + key.idempotencyKey() + "|"
            + inputHash.canonicalText();
    }

    public CompletableFuture<FunctionResult> executeCompiledFunction(CompiledFunctionExecutionRequest request) {
        return executeCompiledFunction(request, compiledFunctionExecutionBridge);
    }

    public void addExecutionListener(FlowExecutionListener listener) {
        if (listener != null) {
            executionListeners.add(listener);
        }
    }

    public void removeExecutionListener(FlowExecutionListener listener) {
        executionListeners.remove(listener);
    }

    public void setTraceService(FlowTraceService traceService) {
        this.traceService = traceService;
    }

    public void setDebugService(FlowDebugService debugService) {
        this.debugService = debugService;
    }

    public void setGraphValidator(FlowGraphValidator graphValidator) {
        this.graphValidator = graphValidator;
    }

    public void setExecutionAuthority(Predicate<FlowGraph> executionAuthority) {
        this.executionAuthority = executionAuthority != null ? executionAuthority : graph -> true;
        this.compiledExecutionAuthority = (graph, metadata) -> this.executionAuthority.test(graph);
    }

    public void setCompiledExecutionAuthority(BiPredicate<FlowGraph, CompiledGraphMetadata> executionAuthority) {
        this.compiledExecutionAuthority = Objects.requireNonNull(executionAuthority, "Compiled Execution Authority Is Required");
    }

    public AdmissionFence fenceAdmissions() {
        synchronized (admissionMonitor) {
            admissionFenceDepth++;
            return new AdmissionFence();
        }
    }

    public LiveEventScope liveEventScope(CompiledRuntimeContext context, CorrelationId invocationId) {
        LiveEventScope scope = invocationId == null ? null : liveEvents.get(invocationId);
        return scope != null && scope.event(context, invocationId) != null ? scope : null;
    }

    public String eventBindingContext(String nodeType) {
        return nodeDefinitionRegistry == null ? null : FlowEventRegistry.bindingContext(nodeDefinitionRegistry.get(nodeType));
    }

    <T> CompletableFuture<T> withLiveEventScope(CompiledRuntimeContext context, CorrelationId invocationId,
                                               Event event, Supplier<CompletableFuture<T>> operation) {
        return withLiveEventScope(context, invocationId, event, null, operation);
    }

    public <T> CompletableFuture<T> withInheritedLiveEventScope(CompiledRuntimeContext context, CorrelationId parentId,
                                                               CorrelationId invocationId, Supplier<CompletableFuture<T>> operation) {
        LiveEventScope parent = liveEventScope(context, parentId);
        return withLiveEventScope(context, invocationId, parent == null ? null : parent.event(context, parentId), parent, operation);
    }

    private <T> CompletableFuture<T> withLiveEventScope(CompiledRuntimeContext context, CorrelationId invocationId,
                                                       Event event, LiveEventScope parent, Supplier<CompletableFuture<T>> operation) {
        if (event == null) {
            return operation.get();
        }
        LiveEventScope scope = new LiveEventScope(context, invocationId, event, parent);
        if (liveEvents.putIfAbsent(invocationId, scope) != null) {
            scope.close();
            throw new IllegalStateException("A live event invocation is already active");
        }
        try (scope) {
            return operation.get();
        } finally {
            liveEvents.remove(invocationId, scope);
        }
    }

    private <T> CompletableFuture<T> withLegacyAdmission(boolean required, Supplier<CompletableFuture<T>> operation) {
        if (!required) {
            return invokeAdmissionOperation(operation);
        }
        LegacyAdmission admission;
        synchronized (admissionMonitor) {
            if (admissionFenceDepth > 0) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "EXECUTION_FENCED",
                    "Flow execution is temporarily fenced",
                    null,
                    null,
                    "Wait for the Flow runtime lifecycle operation to finish"
                ));
            }
            activeLegacyExecutions++;
            admission = new LegacyAdmission();
        }
        try {
            CompletableFuture<T> future = operation.get();
            if (future == null) {
                admission.close();
                return CompletableFuture.failedFuture(new IllegalStateException("Flow execution returned no completion"));
            }
            future.whenComplete((result, failure) -> admission.close());
            return future;
        } catch (Throwable failure) {
            admission.close();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private <T> CompletableFuture<T> invokeAdmissionOperation(Supplier<CompletableFuture<T>> operation) {
        try {
            CompletableFuture<T> future = operation.get();
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException("Flow execution returned no completion"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void awaitLegacyExecutions() {
        boolean interrupted = false;
        synchronized (admissionMonitor) {
            while (activeLegacyExecutions > 0) {
                try {
                    admissionMonitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<Void> awaitLegacyExecutionsAsync() {
        synchronized (admissionMonitor) {
            if (activeLegacyExecutions == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> completion = new CompletableFuture<>();
            drainWaiters.add(completion);
            return completion;
        }
    }

    public void setAuthorizationPolicy(FlowNodeAuthorizationPolicy authorizationPolicy) {
        if (authorizationPolicy != null) {
            this.authorizationPolicy = authorizationPolicy;
        }
    }

    public synchronized List<FlowNodeAuditRecord> auditSnapshot() {
        return List.copyOf(auditRecords);
    }

    private FlowGraphValidationException validationFailure(FlowGraph graph) {
        if (graphValidator == null) {
            return null;
        }
        FlowGraphValidationResult result = graphValidator.validate(graph);
        return result.valid() ? null : new FlowGraphValidationException(result);
    }

    private boolean executionBlocked(FlowGraph graph) {
        return graph != null && (!graph.isEnabled() || executionDenied(graph));
    }

    private boolean executionDenied(FlowGraph graph) {
        return graph != null && !executionAuthority.test(graph);
    }

    private CompletableFuture<Void> execute(FlowRuntime runtime, String startNodeId, Player player, Event event, int steps) {
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, startNodeId);
        }

        FlowGraph graph = runtime.getGraph();
        if (startNodeId == null || startNodeId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_NODE_MISSING", "Flow execution target is missing", null, null, "Reconnect the execution path to an existing node"));
        }
        if (!runtime.beginFlowExecution(graph, startNodeId)) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_REENTRANT_NODE", "Execution re-entered an active node: " + startNodeId, null, startNodeId,
                "Use a supported loop node instead of an execution cycle"));
        }

        FlowNode node = graph.getNodes().get(startNodeId);
        if (node == null) {
            runtime.endFlowExecution(graph, startNodeId);
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_NODE_NOT_FOUND", "Flow node not found: " + startNodeId, null, startNodeId,
                "Reconnect the execution path to an existing node"));
        }

        runtime.consumeTriggeredOutput();
        long traceStarted = System.nanoTime();
        trace(startTraceRecord(runtime, graph, node, startNodeId, steps, "started", 0L, null));
        FlowDebugService debugger = debugService;
        CompletableFuture<Void> execution;
        try {
            if (debugger != null && debugger.isEnabled()) {
                execution = debugger.beforeNode(runtime, graph, node, startNodeId, steps, summarizeInputs(node))
                    .thenCompose(ignored -> executePreparedNode(runtime, node, startNodeId, player, event, steps));
            } else {
                execution = executePreparedNode(runtime, node, startNodeId, player, event, steps);
            }
        } catch (Exception exception) {
            execution = CompletableFuture.failedFuture(exception);
        }
        return completeNodeExecution(runtime, graph, node, startNodeId, steps, traceStarted, execution);
    }

    private CompletableFuture<Void> executePreparedNode(FlowRuntime runtime, FlowNode node, String startNodeId,
                                                         Player player, Event event, int steps) {
        FlowContext context = new FlowContext(
                runtime,
                player,
                event,
                null,
                this,
                null,
                null,
                null,
                null,
                RuntimeExecutionContext.NO_DEADLINE,
                node
        );
        context.setDeferredOutputDispatcher(outputPin -> dispatchDeferredOutput(runtime, startNodeId, outputPin, player, event, steps));

        return ensureInputNodesReady(runtime, node, player, event)
            .thenCompose(ignored -> executeWithThreadPolicy(runtime, resolveThreadPolicy(node),
                () -> executePreparedNodeWithInputs(runtime, node, startNodeId, player, event, steps, context)));
    }

    private CompletableFuture<Void> executePreparedNodeWithInputs(FlowRuntime runtime, FlowNode node, String startNodeId,
                                                                   Player player, Event event, int steps, FlowContext context) {

        String type = node.getType();
        NodeDefinition definition = resolveDefinition(node);
        FlowNodeAuthorizationPolicy.AuthorizationDecision authorization = authorizationPolicy.authorize(context, node, definition);
        FlowNodeAuthorizationPolicy.AuthorizationDecision decision = authorization != null
            ? authorization
            : FlowNodeAuthorizationPolicy.AuthorizationDecision.deny(definition != null ? definition.getAuthorizationPolicy() : "", type);
        recordNodeAudit(runtime, context, node, startNodeId, definition, decision);
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                decision.code(),
                decision.message(),
                null,
                startNodeId,
                "Review the capability authorization policy",
                decision.details()
            ));
        }
        String loopOperation = resolveLoopOperation(node);
        if (loopOperation != null) {
            return executeLoopNode(runtime, node, loopOperation, player, event, steps);
        }

        String functionId = resolveFunctionCallId(runtime, node, definition);
        if (functionId != null) {
            int depthBefore = runtime.getCallDepth();
            CompletableFuture<Void> call = executeFunctionCallNode(runtime, node, startNodeId, functionId, player, event, steps);
            if (!isRecoverableFunctionCall(runtime, node)) {
                return call;
            }
            return call.handle((ignored, failure) -> {
                if (failure == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return recoverFunctionCall(runtime, startNodeId, depthBefore, unwrapCompletionFailure(failure), player, event, steps);
            }).thenCompose(result -> result);
        }

        NodeHandler handler = resolveHandler(node);
        if (handler == null) {
            NodeDefinition triggerDefinition = resolveTriggerDefinition(node);
            if (triggerDefinition != null) {
                publishTriggerOutputs(runtime, startNodeId, triggerDefinition);
                return findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
            }
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "HANDLER_UNAVAILABLE",
                "No handler registered for node type: " + node.getType(),
                null,
                startNodeId,
                "Install the required capability or replace the unavailable node"
            ));
        }
        FlowExecutionException operationFailure = validateHandlerOperation(node, startNodeId);
        if (operationFailure != null) {
            return CompletableFuture.failedFuture(operationFailure);
        }

        try {
            handler.execute(context, node);
            context.finishSynchronousCapture();

            List<String> outputPins = context.consumeTriggeredOutputs();
            String runtimeOutputPin = runtime.consumeTriggeredOutput();
            if (runtimeOutputPin != null && !runtimeOutputPin.isBlank() && !outputPins.contains(runtimeOutputPin)) {
                if (outputPins.isEmpty()) {
                    outputPins = List.of(runtimeOutputPin);
                } else {
                    List<String> merged = new ArrayList<>(outputPins);
                    merged.add(runtimeOutputPin);
                    outputPins = merged;
                }
            }
            outputPins = outputPins.stream()
                .map(pin -> runtime.normalizeOutputPin(node, pin))
                .toList();

            CompletableFuture<Void> result;
            if (!outputPins.isEmpty()) {
                CompletableFuture<Void> pending = pendingBeforeContinuationOperations(context);
                List<String> pins = outputPins;
                result = pending.thenCompose(ignored -> executeTriggeredOutputs(runtime, startNodeId, pins, player, event, steps));
            } else if (context.isContinuationHalted()) {
                result = CompletableFuture.completedFuture(null);
            } else if (context.hasPendingAsyncOperations()) {
                result = pendingOperations(context).thenCompose(ignored -> context.hasDeferredOutputTriggered() || context.isContinuationHalted()
                    ? CompletableFuture.completedFuture(null)
                    : findNextAndExecute(runtime, startNodeId, "flow", player, event, steps));
            } else {
                result = findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
            }
            return settleContext(context, result);
        } catch (Exception e) {
            return settleContext(context, CompletableFuture.failedFuture(
                handlerFailure(e, startNodeId, node.getType(), "HANDLER_EXECUTION_FAILED", "executing")));
        }
    }

    private CompletableFuture<Void> completeNodeExecution(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId,
                                                           int steps, long traceStarted, CompletableFuture<Void> execution) {
        return execution.handle((ignored, failure) -> {
            runtime.endFlowExecution(graph, nodeId);
            runtime.clearDataDependencies(nodeId);
            Throwable effectiveFailure = failure;
            if (effectiveFailure == null && !runtime.isWithinElapsedBudget(maxExecutionDurationMillis)) {
                effectiveFailure = new FlowExecutionException(
                    "EXECUTION_DURATION_BUDGET",
                    "Flow execution exceeded maximum duration: " + maxExecutionDurationMillis + "ms",
                    null,
                    nodeId,
                    "Reduce long-running work, or raise the configured duration budget"
                );
            }
            if (effectiveFailure == null) {
                traceSuccess(runtime, graph, node, nodeId, steps, traceStarted);
                return null;
            }

            Throwable cause = unwrapCompletionFailure(effectiveFailure);
            FlowExecutionException exception;
            if (cause instanceof FlowExecutionException flowExecutionException) {
                exception = flowExecutionException;
            } else if (cause instanceof Exception handlerException) {
                exception = handlerFailure(handlerException, nodeId, node.getType(), "HANDLER_EXECUTION_FAILED", "executing");
            } else {
                exception = new FlowExecutionException(
                    "HANDLER_EXECUTION_FAILED",
                    "Error executing node '" + node.getType() + "' (ID: " + nodeId + ")",
                    cause,
                    nodeId,
                    "Inspect the node inputs and the underlying handler failure"
                );
            }
            traceFailure(runtime, graph, node, nodeId, steps, traceStarted, exception);
            throw new CompletionException(exception);
        });
    }

    private Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private synchronized void recordNodeAudit(FlowRuntime runtime, FlowContext context, FlowNode node, String nodeId,
                                              NodeDefinition definition, FlowNodeAuthorizationPolicy.AuthorizationDecision decision) {
        String auditPolicy = definition != null ? definition.getAuditPolicy() : "none";
        boolean sensitive = definition != null && definition.isSensitive();
        boolean destructive = definition != null && definition.isDestructive();
        if (!sensitive && !destructive && "none".equals(auditPolicy) && decision.allowed()) return;
        while (auditRecords.size() >= MAX_AUDIT_RECORDS) {
            auditRecords.removeFirst();
        }
        String graphId = runtime != null && runtime.getGraph() != null && runtime.getGraph().getId() != null ? runtime.getGraph().getId() : "";
        String playerId = context != null && context.getPlayer() != null ? context.getPlayer().getUniqueId().toString() : "";
        auditRecords.addLast(new FlowNodeAuditRecord(
            System.currentTimeMillis(),
            runtime != null ? runtime.getExecutionId() : "",
            graphId,
            nodeId != null ? nodeId : "",
            node != null && node.getType() != null ? node.getType() : "",
            playerId,
            definition != null ? definition.getAuthorizationPolicy() : "trusted_server_flow",
            auditPolicy,
            definition != null ? definition.getConfirmationPolicy() : "none",
            sensitive,
            destructive,
            decision.allowed(),
            decision.code()
        ));
    }

    private CompletableFuture<Void> bridgeUnsupported(String reason, FlowGraph graph, String startNodeId) {
        return bridgeFailure("CORE_EXECUTION_UNSUPPORTED", bridgeReason(reason,
            "Compiled Core execution is unsupported for this graph"), null, graph, startNodeId,
            "Provide a complete canonical mapping context before enabling the compiled Core bridge");
    }

    private CompletableFuture<Void> pendingOperations(FlowContext context) {
        return pendingOperations(context, new HashSet<>(), null);
    }

    private CompletableFuture<Void> settleContext(FlowContext context, CompletableFuture<Void> execution) {
        return execution.handle((ignored, executionFailure) -> pendingOperations(context)
            .handle((pending, pendingFailure) -> {
                Throwable failure = executionFailure != null ? executionFailure : pendingFailure;
                if (failure != null) {
                    throw new CompletionException(unwrapCompletionFailure(failure));
                }
                return (Void) null;
            })).thenCompose(result -> result);
    }

    private CompletableFuture<Void> pendingOperations(FlowContext context, Set<CompletableFuture<Void>> observed, Throwable firstFailure) {
        if (context == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] pending = context.getAsyncOperations().values().stream()
            .filter(observed::add)
            .toArray(CompletableFuture[]::new);
        if (pending.length == 0) {
            return firstFailure == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(firstFailure);
        }
        return CompletableFuture.allOf(pending).handle((ignored, failure) -> firstFailure != null ? firstFailure : failure)
            .thenCompose(failure -> pendingOperations(context, observed, failure));
    }

    private CompletableFuture<Void> pendingBeforeContinuationOperations(FlowContext context) {
        return pendingBeforeContinuationOperations(context, new HashSet<>());
    }

    private CompletableFuture<Void> pendingBeforeContinuationOperations(FlowContext context, Set<CompletableFuture<Void>> observed) {
        if (context == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] pending = context.getBeforeContinuationOperations().values().stream()
            .filter(observed::add)
            .toArray(CompletableFuture[]::new);
        if (pending.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending).thenCompose(ignored -> pendingBeforeContinuationOperations(context, observed));
    }

    private CompletableFuture<Void> dispatchDeferredOutput(FlowRuntime runtime, String currentNodeId, String outputPin,
                                                           Player player, Event event, int steps) {
        if (outputPin == null || outputPin.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        FlowTask operation = new FlowTask();
        CompletableFuture<Void> completion = operation.completion();
        Runnable continuation = () -> {
            if (!operation.start()) {
                return;
            }
            try {
                executeTriggeredOutputs(runtime, currentNodeId, List.of(outputPin), player, event, steps)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            operation.finish(null);
                        } else {
                            operation.finish(unwrapCompletionFailure(failure));
                        }
                    });
            } catch (Exception ex) {
                operation.finish(ex);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            continuation.run();
        } else {
            String taskId = "deferred_" + UUID.randomUUID();
            BukkitTask task;
            try {
                task = Bukkit.getScheduler().runTask(ReSync.getInstance(), continuation);
            } catch (RuntimeException exception) {
                completion.completeExceptionally(exception);
                return completion;
            }
            trackTask(taskId, graphId(runtime), task, operation);
        }
        return completion;
    }

    private NodeHandler.ThreadPolicy resolveThreadPolicy(FlowNode node) {
        NodeHandler handler = resolveHandler(node);
        return handler != null && handler.getThreadPolicy() != null ? handler.getThreadPolicy() : NodeHandler.ThreadPolicy.MAIN;
    }

    private CompletableFuture<Void> executeWithThreadPolicy(FlowRuntime runtime, NodeHandler.ThreadPolicy policy,
                                                            Supplier<CompletableFuture<Void>> action) {
        NodeHandler.ThreadPolicy resolved = policy != null ? policy : NodeHandler.ThreadPolicy.MAIN;
        if (resolved == NodeHandler.ThreadPolicy.CURRENT
            || resolved == NodeHandler.ThreadPolicy.MAIN && Bukkit.isPrimaryThread()
            || resolved == NodeHandler.ThreadPolicy.ASYNC && !Bukkit.isPrimaryThread()) {
            return invokeExecutionAction(action);
        }

        FlowTask operation = new FlowTask();
        CompletableFuture<Void> completion = operation.completion();
        String taskId = "thread_policy_" + UUID.randomUUID();
        Runnable scheduledAction = () -> {
            if (!operation.start()) {
                return;
            }
            invokeExecutionAction(action).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    operation.finish(null);
                } else {
                    operation.finish(unwrapCompletionFailure(failure));
                }
            });
        };
        BukkitTask task;
        try {
            task = resolved == NodeHandler.ThreadPolicy.MAIN
                ? Bukkit.getScheduler().runTask(ReSync.getInstance(), scheduledAction)
                : Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), scheduledAction);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
            return completion;
        }
        trackTask(taskId, graphId(runtime), task, operation);
        return completion;
    }

    private CompletableFuture<Void> invokeExecutionAction(Supplier<CompletableFuture<Void>> action) {
        try {
            CompletableFuture<Void> result = action.get();
            return result != null ? result : CompletableFuture.completedFuture(null);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<Void> executeTriggeredOutputs(FlowRuntime runtime, String currentNodeId, List<String> outputPins,
                                                            Player player, Event event, int steps) {
        if (outputPins == null || outputPins.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String outputPin : outputPins) {
            if (outputPin == null || outputPin.isBlank()) {
                continue;
            }
            String normalizedPin = runtime.normalizeOutputPin(currentNodeId, outputPin);
            future = future.thenCompose(ignored -> loopControlRequested(runtime)
                ? CompletableFuture.completedFuture(null)
                : findNextAndExecute(runtime, currentNodeId, normalizedPin, player, event, steps));
        }
        return future;
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, FlowNode currentNode, String outputPin, 
                                                   Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String currentNodeId = findNodeId(graph, currentNode);
        return findNextAndExecute(runtime, currentNodeId, outputPin, player, event, steps);
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, String currentNodeId, String outputPin,
                                                       Player player, Event event, int steps) {
        if (outputPin == null || outputPin.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (currentNodeId == null || currentNodeId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_SOURCE_MISSING", "Execution source node is missing", null, null,
                "Reconnect the execution path to an existing node"));
        }

        FlowGraph graph = runtime.getGraph();
        outputPin = runtime.normalizeOutputPin(currentNodeId, outputPin);
        List<String> nextNodeIds = findTargetNodes(runtime, graph, currentNodeId, outputPin);
        traceTraversedConnections(runtime, graph, currentNodeId, outputPin, steps);

        if (nextNodeIds.isEmpty()) {
            FlowNode sourceNode = graph.getNodes().get(currentNodeId);
            if (runtime.outputPinMatches(sourceNode, outputPin, "next")) {
                nextNodeIds = findTargetNodes(runtime, graph, currentNodeId, "flow");
                traceTraversedConnections(runtime, graph, currentNodeId, "flow", steps);
            } else if (runtime.outputPinMatches(sourceNode, outputPin, "flow")) {
                nextNodeIds = findTargetNodes(runtime, graph, currentNodeId, "next");
                traceTraversedConnections(runtime, graph, currentNodeId, "next", steps);
            }
        }

        return executeTargets(runtime, nextNodeIds, player, event, steps + 1);
    }

    private void traceTraversedConnections(FlowRuntime runtime, FlowGraph graph, String currentNodeId, String outputPin, int steps) {
        FlowDebugService debugger = debugService;
        if (debugger == null || !debugger.isEnabled() || graph == null || currentNodeId == null || outputPin == null) {
            return;
        }
        for (FlowConnection connection : graph.getConnectionsFromSource(currentNodeId)) {
            FlowNode source = graph.getNodes().get(currentNodeId);
            if (runtime.outputPinMatches(source, connection.getSourcePin(), outputPin)) {
                debugger.connectionTraversed(runtime, graph, connection, steps);
            }
        }
    }

    private CompletableFuture<Void> executeFunctionCallNode(FlowRuntime runtime, FlowNode node, String startNodeId, String functionId,
                                                            Player player, Event event, int steps) {
        if (functionId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_ID_MISSING", "Function call has no function identity", null, startNodeId,
                "Replace the function call with an existing function"));
        }
        FlowExecutionException depthFailure = functionDepthFailure(runtime, startNodeId);
        if (depthFailure != null) {
            return CompletableFuture.failedFuture(depthFailure);
        }

        FlowStorage storage = FlowRuntimeAccess.getStorage();
        if (storage == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_STORAGE_UNAVAILABLE", "Function storage is unavailable", null, startNodeId,
                "Restore the Flow storage service before executing this graph"));
        }

        FlowGraph functionGraph = storage.getGraph("function", functionId);
        if (functionGraph == null || !functionGraph.isFunction()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_NOT_FOUND", "Function not found: " + functionId, null, startNodeId,
                "Select an existing function or restore the missing function"));
        }
        if (executionBlocked(functionGraph)) {
            Map<String, Object> results = Map.of();
            runtime.setNodeOutput(startNodeId, "results", results);
            runtime.setNodeOutput(startNodeId, "result", FlowOperationResult.success(results));
            return executeTargets(runtime, findTargetNodes(runtime, runtime.getGraph(), startNodeId, "flow"), player, event, steps + 1);
        }
        FlowGraphValidationException functionValidationFailure = validationFailure(functionGraph);
        if (functionValidationFailure != null) {
            return CompletableFuture.failedFuture(functionValidationFailure);
        }
        functionGraph = FlowSerializer.deserialize(FlowSerializer.serialize(functionGraph));
        functionGraph.adaptLegacyFunctionParameterIds();
        FlowGraph calledFunction = functionGraph;
        String functionStartNodeId = findFunctionStartNodeId(functionGraph);
        if (functionStartNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_START_MISSING", "Function has no executable start node", null, startNodeId,
                "Add a Function Start node to " + functionId));
        }

        FlowExecutionException contractFailure = validateFunctionCallContractById(node, functionGraph, startNodeId);
        if (contractFailure != null) {
            return CompletableFuture.failedFuture(contractFailure);
        }
        Map<FunctionParameterId, Object> callInputs;
        try {
            callInputs = resolveFunctionInputFrame(runtime, node, startNodeId, functionGraph);
        } catch (FlowExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        FlowExecutionException inputFailure = validateFunctionInputFrame(functionGraph, callInputs, startNodeId);
        if (inputFailure != null) {
            return CompletableFuture.failedFuture(inputFailure);
        }
        if (executionBridge != null) {
            FunctionInvocationContext invocation = defaultFunctionInvocationContext(player, event,
                runtime.getEventVariables(), runtime.getInvocationId(), RuntimeExecutionContext.NO_DEADLINE);
            if (invocation == null) {
                return compiledFunctionUnavailable(runtime.getGraph(), startNodeId,
                    "FlowExecutor.executeFunctionCallNode");
            }
            FunctionInvocationContext child = invocation.child("legacy-call|" + startNodeId + "|" + functionId + "|" + steps);
            return executeFunction(functionGraph, player, event, legacyFunctionInputs(functionGraph, callInputs), runtime.getEventVariables(), child)
                .thenCompose(results -> {
                    runtime.setNodeOutput(startNodeId, "results", results);
                    runtime.setNodeOutput(startNodeId, "result", FlowOperationResult.success(results));
                    return executeTargets(runtime, findTargetNodes(runtime, runtime.getGraph(), startNodeId, "flow"), player, event, steps + 1);
                });
        }
        int depthBefore = runtime.getCallDepth();
        runtime.callFunctionById(functionGraph, startNodeId, callInputs);
        CompletableFuture<Void> functionExecution = execute(runtime, functionStartNodeId, player, event, steps + 1);

        return functionExecution.thenCompose(v -> {
            while (runtime.getCallDepth() > depthBefore) {
                runtime.returnFromFunction(Collections.emptyMap());
            }

            runtime.consumeFunctionReturnRequested();
            String callerNodeId = runtime.consumeReturnedCallerNodeId();
            if (callerNodeId == null) {
                callerNodeId = startNodeId;
            }

            Map<String, Object> results = legacyFunctionOutputs(calledFunction, runtime.getReturnedFunctionOutputsById());
            runtime.setNodeOutput(callerNodeId, "results", results);
            runtime.setNodeOutput(callerNodeId, "result", FlowOperationResult.success(results));

            List<String> nextNodeIds = findTargetNodes(runtime, runtime.getGraph(), callerNodeId, "flow");
            return executeTargets(runtime, nextNodeIds, player, event, steps + 1);
        });
    }

    private Map<FunctionParameterId, Object> resolveFunctionInputFrame(FlowRuntime runtime, FlowNode node, String nodeId,
                                                                       FlowGraph functionGraph) throws FlowExecutionException {
        return resolveFunctionInputFrame(runtime, node, nodeId, functionGraph, false);
    }

    private Map<FunctionParameterId, Object> resolveLegacyFunctionInputFrame(FlowRuntime runtime, FlowNode node, String nodeId,
                                                                               FlowGraph functionGraph) throws FlowExecutionException {
        boolean legacyGraph = hasLegacyFunctionParameters(functionGraph);
        functionGraph.adaptLegacyFunctionParameterIds();
        return resolveFunctionInputFrame(runtime, node, nodeId, functionGraph, legacyGraph);
    }

    private Map<FunctionParameterId, Object> resolveFunctionInputFrame(FlowRuntime runtime, FlowNode node, String nodeId,
                                                                       FlowGraph functionGraph, boolean legacyGraph)
            throws FlowExecutionException {
        Map<FunctionParameterId, Object> callInputs = new LinkedHashMap<>();
        Object dynamicArguments = runtime.resolveInput(node, "arguments");
        if (dynamicArguments instanceof Map<?, ?> arguments) {
            for (Map.Entry<?, ?> argument : arguments.entrySet()) {
                if (argument.getKey() == null) {
                    continue;
                }
                FunctionParameterId id = legacyGraph
                    ? legacyFunctionParameterId(functionGraph, argument.getKey(), true)
                    : functionParameterId(argument.getKey());
                if (id == null) {
                    throw new FlowExecutionException("FUNCTION_ARGUMENT_UNKNOWN",
                        "Function argument is not declared: " + argument.getKey(), null, nodeId,
                        "Remove the unknown argument or update the function signature",
                        Map.of("argument", argument.getKey().toString(), "function", functionGraph.getId()));
                }
                callInputs.put(id, argument.getValue());
            }
        } else if (dynamicArguments != null) {
            List<FlowGraph.FunctionParameter> declared = functionParameters(functionGraph);
            if (declared.size() != 1) {
                throw new FlowExecutionException("FUNCTION_ARGUMENTS_NEED_NAMES",
                    "This function has multiple inputs, so each value needs an argument name", null, nodeId,
                    "Add named arguments to Call Function",
                    Map.of("function", functionGraph.getId(), "inputCount", declared.size()));
            }
            callInputs.put(requireFunctionParameterId(declared.getFirst()), dynamicArguments);
        }
        for (FlowGraph.FunctionParameter input : functionParameters(functionGraph)) {
            if (input == null || input.getName() == null || input.getName().isBlank()) {
                continue;
            }
            FunctionParameterId id = requireFunctionParameterId(input);
            if (FUNCTION_CALL_RESERVED_INPUTS.contains(input.getName())) {
                callInputs.putIfAbsent(id, null);
                continue;
            }
            String inputPin = legacyGraph ? input.getName() : FunctionCallSupport.parameterPinKey(input, true);
            boolean hasLiteral = node.getInputValues() != null && node.getInputValues().containsKey(inputPin);
            boolean hasConnection = runtime.getGraph().getConnectionsToTarget(nodeId).stream()
                .anyMatch(connection -> runtime.inputPinMatches(node, connection.getTargetPin(), inputPin));
            if (hasLiteral || hasConnection || !callInputs.containsKey(id)) {
                callInputs.put(id, runtime.resolveInput(node, inputPin));
            }
        }
        Map<FunctionParameterId, Object> orderedInputs = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter input : functionParameters(functionGraph)) {
            FunctionParameterId id = requireFunctionParameterId(input);
            if (callInputs.containsKey(id)) {
                orderedInputs.put(id, callInputs.get(id));
            }
        }
        callInputs.forEach(orderedInputs::putIfAbsent);
        return orderedInputs;
    }

    private Map<String, Object> resolveFunctionInputs(FlowRuntime runtime, FlowNode node, String nodeId,
                                                      FlowGraph functionGraph) throws FlowExecutionException {
        return legacyFunctionInputs(functionGraph, resolveLegacyFunctionInputFrame(runtime, node, nodeId, functionGraph));
    }

    private FlowExecutionException validateFunctionCallContractById(FlowNode node, FlowGraph functionGraph, String nodeId) {
        if (node.getInputValues() == null || !(node.getInputValues().get(CALL_PARAMETERS_KEY) instanceof Iterable<?> values)) {
            return null;
        }
        functionGraph.adaptLegacyFunctionParameterIds();
        Map<FunctionParameterId, FlowTypeRef> declared = new LinkedHashMap<>();
        Map<FunctionParameterId, String> declaredNames = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> entry) || entry.get("type") == null) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_INVALID", "Function argument needs a name and type", null, nodeId,
                    "Remove the invalid argument and add it again", Map.of("function", functionGraph.getId()));
            }
            String name = entry.get("name") != null ? entry.get("name").toString().trim() : "";
            Object rawId = entry.get("parameterId") != null ? entry.get("parameterId") : entry.get("id");
            FunctionParameterId id = functionParameterId(rawId);
            if (id == null) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_UNKNOWN", "Function argument is not declared: " + name, null, nodeId,
                    "Match the argument ID to the function input", Map.of("argument", name, "function", functionGraph.getId()));
            }
            FlowTypeRef type;
            try {
                type = FlowTypeRef.parse(entry.get("type").toString()).normalizedGenerics();
            } catch (IllegalArgumentException exception) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_TYPE_INVALID", "Function argument type is invalid: " + name, exception, nodeId,
                    "Choose the type expected by the called function", Map.of("argument", name, "function", functionGraph.getId()));
            }
            if (declared.putIfAbsent(id, type) != null) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_DUPLICATE", "Function argument is declared more than once: " + name, null, nodeId,
                    "Remove or rename the duplicate argument", Map.of("argument", name, "function", functionGraph.getId()));
            }
            declaredNames.put(id, displayName(functionGraph, id, name));
        }
        if (declared.isEmpty()) {
            return null;
        }
        Map<FunctionParameterId, FlowTypeRef> expected = new LinkedHashMap<>();
        Map<FunctionParameterId, String> expectedNames = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph)) {
            if (parameter != null && parameter.getName() != null && !parameter.getName().isBlank()) {
                FunctionParameterId id = requireFunctionParameterId(parameter);
                expected.put(id, parameter.getTypeRef().normalizedGenerics());
                expectedNames.put(id, parameter.getName());
            }
        }
        List<String> missing = expected.keySet().stream().filter(id -> !declared.containsKey(id))
            .map(expectedNames::get).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> unknown = declared.keySet().stream().filter(id -> !expected.containsKey(id))
            .map(id -> declaredNames.getOrDefault(id, id.canonicalText())).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            return new FlowExecutionException("FUNCTION_CALL_ARGUMENTS_DO_NOT_MATCH", "Function arguments do not match the selected function", null, nodeId,
                "Match the argument names to the function inputs", Map.of(
                    "function", functionGraph.getId(),
                    "missing", missing,
                    "unknown", unknown
                ));
        }
        for (Map.Entry<FunctionParameterId, FlowTypeRef> argument : declared.entrySet()) {
            FlowTypeRef expectedType = expected.get(argument.getKey());
            if (!expectedType.equals(argument.getValue())) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_TYPE_MISMATCH",
                    "Argument " + displayName(functionGraph, argument.getKey(), declaredNames.get(argument.getKey())) + " is "
                        + argument.getValue() + " but the function expects " + expectedType, null, nodeId,
                    "Choose the same type as the function input", Map.of(
                        "argument", displayName(functionGraph, argument.getKey(), declaredNames.get(argument.getKey())),
                        "parameterId", argument.getKey().canonicalText(),
                        "declaredType", argument.getValue().toString(),
                        "expectedType", expectedType.toString(),
                        "function", functionGraph.getId()
                    ));
            }
        }
        return null;
    }

    private FlowExecutionException validateFunctionCallContract(FlowNode node, FlowGraph functionGraph, String nodeId) {
        if (node.getInputValues() == null
                || !(node.getInputValues().get(CALL_PARAMETERS_KEY) instanceof Iterable<?> values)) {
            return validateFunctionCallContractById(node, functionGraph, nodeId);
        }
        functionGraph.adaptLegacyFunctionParameterIds();
        List<Object> adaptedValues = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> entry)) {
                adaptedValues.add(value);
                continue;
            }
            Map<String, Object> adapted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : entry.entrySet()) {
                if (item.getKey() != null) {
                    adapted.put(item.getKey().toString(), item.getValue());
                }
            }
            Object rawId = adapted.get("parameterId") != null ? adapted.get("parameterId") : adapted.get("id");
            if (functionParameterId(rawId) == null) {
                Object legacyKey = adapted.get("name") != null ? adapted.get("name") : rawId;
                FunctionParameterId id = legacyFunctionParameterId(functionGraph, legacyKey, true);
                if (id != null) {
                    adapted.put("parameterId", id.canonicalText());
                }
            }
            adaptedValues.add(adapted);
        }
        boolean hadValues = node.getInputValues().containsKey(CALL_PARAMETERS_KEY);
        Object previousValues = node.getInputValues().put(CALL_PARAMETERS_KEY, adaptedValues);
        try {
            return validateFunctionCallContractById(node, functionGraph, nodeId);
        } finally {
            if (hadValues) {
                node.getInputValues().put(CALL_PARAMETERS_KEY, previousValues);
            } else {
                node.getInputValues().remove(CALL_PARAMETERS_KEY);
            }
        }
    }

    private FlowExecutionException validateFunctionInputFrame(FlowGraph functionGraph, Map<FunctionParameterId, Object> inputs, String nodeId) {
        Map<FunctionParameterId, FlowGraph.FunctionParameter> declared = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph)) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            declared.put(requireFunctionParameterId(parameter), parameter);
        }
        for (Map.Entry<FunctionParameterId, FlowGraph.FunctionParameter> entry : declared.entrySet()) {
            FunctionParameterId id = entry.getKey();
            FlowGraph.FunctionParameter parameter = entry.getValue();
            String name = parameter.getName();
            Object value = inputs.get(id);
            if (value == null && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                value = parameter.getDefaultValue();
            }
            if (value == null) {
                return new FlowExecutionException("FUNCTION_ARGUMENT_REQUIRED", "Function argument is missing: " + name, null, nodeId,
                    "Connect " + name + " on Call Function", Map.of("argument", name, "function", functionGraph.getId()));
            }
            FlowDataType type = parameter.getType();
            Class<?> javaType = type != null ? type.getJavaType() : Object.class;
            Object adapted = javaType == null || Object.class.equals(javaType) ? value : typeAdapter.adapt(value, javaType);
            if (adapted == null) {
                return new FlowExecutionException("FUNCTION_ARGUMENT_TYPE_MISMATCH", "Function argument has the wrong type: " + name, null, nodeId,
                    "Provide a value compatible with " + parameter.getTypeRef(), Map.of(
                        "argument", name,
                        "parameterId", id.canonicalText(),
                        "expectedType", parameter.getTypeRef().toString(),
                        "actualType", value.getClass().getSimpleName(),
                        "function", functionGraph.getId()
                ));
            }
            inputs.put(id, adapted);
        }
        List<String> unknown = inputs.keySet().stream().filter(id -> !declared.containsKey(id))
            .map(id -> id != null ? id.canonicalText() : "null").sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!unknown.isEmpty()) {
            return new FlowExecutionException("FUNCTION_ARGUMENT_UNKNOWN", "Function arguments are not declared: " + String.join(", ", unknown), null, nodeId,
                "Remove the unknown arguments or update the function signature", Map.of("arguments", unknown, "function", functionGraph.getId()));
        }
        return null;
    }

    private FlowExecutionException validateFunctionInputs(FlowGraph functionGraph, Map<String, Object> inputs, String nodeId) {
        try {
            boolean legacyGraph = hasLegacyFunctionParameters(functionGraph);
            functionGraph.adaptLegacyFunctionParameterIds();
            Map<FunctionParameterId, Object> frame = adaptLegacyFunctionInputFrame(functionGraph, inputs, legacyGraph);
            FlowExecutionException failure = validateFunctionInputFrame(functionGraph, frame, nodeId);
            if (failure == null) {
                inputs.clear();
                inputs.putAll(legacyFunctionInputs(functionGraph, frame));
            }
            return failure;
        } catch (IllegalArgumentException failure) {
            return new FlowExecutionException("FUNCTION_ARGUMENT_UNKNOWN", failure.getMessage(), failure, nodeId,
                "Remove the unknown arguments or update the function signature");
        }
    }

    private List<FlowGraph.FunctionParameter> functionParameters(FlowGraph functionGraph) {
        if (functionGraph == null || functionGraph.getFunctionInputs() == null) {
            return List.of();
        }
        return functionGraph.getFunctionInputs().stream()
            .filter(parameter -> parameter != null && parameter.getName() != null && !parameter.getName().isBlank())
            .toList();
    }

    private FunctionParameterId requireFunctionParameterId(FlowGraph.FunctionParameter parameter) {
        FunctionParameterId id = parameter != null ? parameter.getParameterId() : null;
        if (id == null) {
            throw new IllegalArgumentException("Function parameter ID is required: "
                + (parameter != null ? parameter.getName() : ""));
        }
        return id;
    }

    private boolean hasLegacyFunctionParameters(FlowGraph functionGraph) {
        if (functionGraph == null) {
            return false;
        }
        return (functionGraph.getFunctionInputs() != null && functionGraph.getFunctionInputs().stream()
            .anyMatch(parameter -> parameter != null && parameter.getParameterId() == null))
            || (functionGraph.getFunctionOutputs() != null && functionGraph.getFunctionOutputs().stream()
            .anyMatch(parameter -> parameter != null && parameter.getParameterId() == null));
    }

    private FunctionParameterId functionParameterId(Object rawKey) {
        if (rawKey instanceof FunctionParameterId id) {
            return id;
        }
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.toString().trim();
        if (key.isEmpty()) {
            return null;
        }
        try {
            return FunctionParameterId.parseCanonicalText(key);
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private FunctionParameterId legacyFunctionParameterId(FlowGraph functionGraph, Object rawKey, boolean legacyGraph) {
        FunctionParameterId direct = functionParameterId(rawKey);
        if (direct != null || !legacyGraph || rawKey == null) {
            return direct;
        }
        String name = rawKey.toString().trim();
        if (name.isEmpty()) {
            return null;
        }
        FlowGraph.FunctionParameter parameter = CustomFunctionNodeDefinitions.parameterForKey(functionGraph, name,
            NodeDefinition.PinDirection.INPUT, true);
        return parameter != null ? requireFunctionParameterId(parameter) : null;
    }

    private String displayName(FlowGraph functionGraph, FunctionParameterId id, String fallback) {
        for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph)) {
            if (id.equals(parameter.getParameterId())) {
                return parameter.getName();
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : id.canonicalText();
    }

    private Map<FunctionParameterId, Object> adaptLegacyFunctionInputFrame(FlowGraph functionGraph,
                                                                            Map<String, Object> inputs,
                                                                            boolean allowDisplayNames) {
        if (functionGraph == null || inputs == null || inputs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<FunctionParameterId, Object> frame = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            FunctionParameterId id = allowDisplayNames
                ? legacyFunctionParameterId(functionGraph, entry.getKey(), true)
                : functionParameterId(entry.getKey());
            if (id == null) {
                throw new IllegalArgumentException("Function argument is not declared: " + entry.getKey());
            }
            frame.put(id, entry.getValue());
        }
        return frame;
    }

    private Map<String, Object> legacyFunctionInputs(FlowGraph functionGraph, Map<FunctionParameterId, Object> frame) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (frame == null) {
            return values;
        }
        for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph)) {
            FunctionParameterId id = parameter.getParameterId();
            if (id != null && frame.containsKey(id)) {
                values.put(parameter.getName(), frame.get(id));
            }
        }
        return values;
    }

    private Map<String, Object> legacyFunctionOutputs(FlowGraph functionGraph, Map<FunctionParameterId, Object> frame) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (functionGraph == null || functionGraph.getFunctionOutputs() == null || frame == null) {
            return values;
        }
        for (FlowGraph.FunctionParameter parameter : functionGraph.getFunctionOutputs()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            FunctionParameterId id = parameter.getParameterId();
            if (id != null && frame.containsKey(id)) {
                values.put(parameter.getName(), frame.get(id));
            }
        }
        return values;
    }

    private boolean isRecoverableFunctionCall(FlowRuntime runtime, FlowNode node) {
        if (!"call.function".equals(node.getType()) && !"call_function".equals(node.getType())) {
            return false;
        }
        Object value = runtime.resolveInput(node, "continue_on_failure");
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private CompletableFuture<Void> recoverFunctionCall(FlowRuntime runtime, String nodeId, int depthBefore, Throwable failure,
                                                         Player player, Event event, int steps) {
        while (runtime.getCallDepth() > depthBefore) {
            runtime.returnFromFunction(Collections.emptyMap());
        }
        runtime.consumeFunctionReturnRequested();
        runtime.consumeReturnedCallerNodeId();
        FlowExecutionException executionFailure = failure instanceof FlowExecutionException value
            ? value
            : new FlowExecutionException("FUNCTION_CALL_FAILED", failure != null && failure.getMessage() != null ? failure.getMessage() : "Function Call Failed",
                failure, nodeId, "Review the selected function and its arguments");
        runtime.setNodeOutput(nodeId, "results", Map.of());
        runtime.setNodeOutput(nodeId, "result", FlowOperationResult.failure(
            executionFailure.getCode(),
            executionFailure.getMessage(),
            executionFailure.getDetails()
        ));
        return executeTargets(runtime, findTargetNodes(runtime, runtime.getGraph(), nodeId, "flow"), player, event, steps + 1);
    }

    private CompletableFuture<Void> executeLoopNode(FlowRuntime runtime, FlowNode loopNode, String operation, Player player, Event event, int steps) {
        if ("loop".equals(operation) || "loop_count".equals(operation)) {
            return executeLoopCount(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each".equals(operation)) {
            return executeLoopForEach(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_player".equals(operation)) {
            return executeLoopForEachPlayer(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_entity".equals(operation)) {
            return executeLoopForEachEntity(runtime, loopNode, player, event, steps);
        }
        if ("loop_interval".equals(operation)) {
            return executeLoopInterval(runtime, loopNode, player, event, steps);
        }
        if ("loop_while".equals(operation)) {
            return executeLoopWhile(runtime, loopNode, player, event, steps);
        }
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_OPERATION_UNKNOWN", "Unknown loop operation: " + operation, null, findNodeId(runtime.getGraph(), loopNode),
            "Replace the node with a supported loop operation"));
    }

    private CompletableFuture<Void> executeLoopCount(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer count = (Integer) runtime.resolveInput(loopNode, "count", Integer.class);
        int iterations = count != null ? count : 0;
        if (iterations < 0) return invalidLoopConfiguration(nodeId, "Loop count cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < iterations; i++) {
            int index = i;
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEach(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        List<?> list = (List<?>) runtime.resolveInput(loopNode, "list", List.class);
        if (list == null) {
            list = List.of();
        } else {
            list = new ArrayList<>(list);
        }
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            Object element = list.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "element", element);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachPlayer(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < players.size(); i++) {
            int index = i;
            Player loopPlayer = players.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "player", loopPlayer);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachEntity(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Double radius = (Double) runtime.resolveInput(loopNode, "radius", Double.class);
        if (radius == null) {
            radius = 10.0;
        }
        Location center = (Location) runtime.resolveInput(loopNode, "center", Location.class);
        if (center == null && player != null) {
            center = player.getLocation();
        }
        if (center == null || center.getWorld() == null) {
            return invalidLoopConfiguration(nodeId, "Entity loop center must have a world");
        }
        if (!Double.isFinite(radius) || radius < 0 || radius > 128) return invalidLoopConfiguration(nodeId, "Entity loop radius must be between 0 and 128");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<Entity> entities = new ArrayList<>(
            center.getWorld().getNearbyEntities(center, radius, radius, radius));
        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < entities.size(); i++) {
            int index = i;
            Entity entity = entities.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "entity", entity);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopInterval(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? intervalValue : 20;
        int maxIterations = maxValue != null ? maxValue : 0;
        if (intervalTicks <= 0) return invalidLoopConfiguration(nodeId, "Loop interval must be positive");
        if (maxIterations < 0) return invalidLoopConfiguration(nodeId, "Maximum loop iterations cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");
        CompletableFuture<Void> iterations = executeLoopIntervalIteration(runtime, nodeId, loopTargets, player, event, steps, 0, intervalTicks, maxIterations);
        return iterations.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps));
    }

    private CompletableFuture<Void> executeLoopIntervalIteration(FlowRuntime runtime, String nodeId, List<String> loopTargets,
                                                                 Player player, Event event, int steps, int index,
                                                                 int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, nodeId);
        }

        runtime.setNodeOutput(nodeId, "index", index);
        return scheduleDelay(runtime, intervalTicks)
            .thenCompose(v -> {
                clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            })
            .thenRun(runtime::consumeContinueLoopRequested)
            .thenCompose(v -> executeLoopIntervalIteration(runtime, nodeId, loopTargets, player, event, steps, index + 1, intervalTicks, maxIterations));
    }

    private CompletableFuture<Void> executeLoopWhile(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? intervalValue : 1;
        int maxIterations = maxValue != null ? maxValue : 0;
        if (intervalTicks <= 0) return invalidLoopConfiguration(nodeId, "Loop interval must be positive");
        if (maxIterations < 0) return invalidLoopConfiguration(nodeId, "Maximum loop iterations cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(runtime, graph, nodeId);
        List<String> completedTargets = findTargetNodes(runtime, graph, nodeId, "completed");
        CompletableFuture<Void> iterations = executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, player, event,
            steps, 0, intervalTicks, maxIterations);
        return iterations.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps));
    }

    private CompletableFuture<Void> executeLoopWhileIteration(FlowRuntime runtime, FlowNode loopNode, String nodeId, List<String> loopTargets,
                                                              Player player, Event event, int steps, int index,
                                                              int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, nodeId);
        }
        clearDependencyOutputs(runtime, runtime.getGraph(), nodeId, "condition");
        return ensureInputNodesReady(runtime, loopNode, player, event).thenCompose(ignored -> {
            Boolean condition = (Boolean) runtime.resolveInput(loopNode, "condition", Boolean.class);
            if (!Boolean.TRUE.equals(condition)) {
                return CompletableFuture.completedFuture(null);
            }

            runtime.setNodeOutput(nodeId, "index", index);
            return scheduleDelay(runtime, intervalTicks)
                .thenCompose(v -> {
                    clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                    return executeTargets(runtime, loopTargets, player, event, steps + 1);
                })
                .thenRun(runtime::consumeContinueLoopRequested)
                .thenCompose(v -> executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, player, event, steps,
                    index + 1, intervalTicks, maxIterations));
        });
    }

    private FlowExecutionException functionDepthFailure(FlowRuntime runtime, String nodeId) {
        int depth = runtime != null ? runtime.getCallDepth() : 0;
        if (depth < maxFunctionCallDepth) {
            return null;
        }
        return new FlowExecutionException(
            "FUNCTION_RECURSION_LIMIT",
            "Function call depth exceeded the configured limit: " + maxFunctionCallDepth,
            null,
            nodeId,
            "Remove unbounded recursion or raise the function call-depth policy",
            Map.of("callDepth", depth, "maximumCallDepth", maxFunctionCallDepth)
        );
    }

    private CompletableFuture<Void> executeLoopCompletion(FlowRuntime runtime, String nodeId, List<String> completedTargets,
                                                          Player player, Event event, int steps) {
        runtime.setNodeOutput(nodeId, "completed", true);
        List<String> doneTargets = findTargetNodes(runtime, runtime.getGraph(), nodeId, "done");
        runtime.resetFlowExecutionPath();
        return executeTargets(runtime, doneTargets.isEmpty() ? completedTargets : doneTargets, player, event, steps);
    }

    private boolean acquireExecutionBudget(FlowRuntime runtime) {
        return runtime.isWithinElapsedBudget(maxExecutionDurationMillis) && runtime.acquireExecutionOperation(maxExecutionSteps);
    }

    private CompletableFuture<Void> executionBudgetFailure(FlowRuntime runtime, String nodeId) {
        return CompletableFuture.failedFuture(executionBudgetFailureException(runtime, nodeId));
    }

    private FlowExecutionException executionBudgetFailureException(FlowRuntime runtime, String nodeId) {
        boolean operationBudgetExceeded = runtime.isWithinElapsedBudget(maxExecutionDurationMillis);
        String message = operationBudgetExceeded
            ? "Flow execution exceeded maximum operations: " + maxExecutionSteps
            : "Flow execution exceeded maximum duration: " + maxExecutionDurationMillis + "ms";
        return new FlowExecutionException(
            operationBudgetExceeded ? "EXECUTION_OPERATION_BUDGET" : "EXECUTION_DURATION_BUDGET",
            message,
            null,
            nodeId,
            operationBudgetExceeded
                ? "Reduce loop or fan-out work, or raise the configured operation budget"
                : "Reduce long-running work, or raise the configured duration budget"
        );
    }

    private CompletableFuture<Void> invalidLoopConfiguration(String nodeId, String message) {
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_CONFIGURATION_INVALID",
            message,
            null,
            nodeId,
            "Use a positive tick interval and zero or a positive maximum iteration count"
        ));
    }

    private CompletableFuture<Void> missingLoopNode() {
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_NODE_MISSING",
            "Loop node is not part of the active graph",
            null,
            null,
            "Reconnect the loop to an existing node"
        ));
    }

    private CompletableFuture<Void> scheduleDelay(FlowRuntime runtime, int ticks) {
        if (ticks <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        FlowTask operation = new FlowTask();
        CompletableFuture<Void> future = operation.completion();
        String taskId = "loop_delay_" + UUID.randomUUID();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
                if (operation.start()) {
                    operation.finish(null);
                }
            }, ticks);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            return future;
        }
        trackTask(taskId, graphId(runtime), task, operation);
        return future;
    }

    private CompletableFuture<Void> executeTargets(FlowRuntime runtime, List<String> targetNodeIds, Player player, Event event, int steps) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (targetNodeIds.size() == 1) {
            return execute(runtime, targetNodeIds.getFirst(), player, event, steps + 1);
        }
        List<CompletableFuture<Void>> executions = new ArrayList<>(targetNodeIds.size());
        List<FlowRuntime> branches = new ArrayList<>(targetNodeIds.size());
        for (String nodeId : targetNodeIds) {
            if (loopControlRequested(runtime)) {
                break;
            }
            FlowRuntime branch = runtime.forkBranch();
            branches.add(branch);
            CompletableFuture<Void> execution = execute(branch, nodeId, player, event, steps + 1);
            execution.whenComplete((result, failure) -> branch.cleanupThreadLocals());
            executions.add(execution);
        }
        return CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            try {
                runtime.mergeCompletedBranches(branches);
                return CompletableFuture.completedFuture(null);
            } catch (IllegalStateException failure) {
                return CompletableFuture.failedFuture(new FlowExecutionException(
                    "EXECUTION_BRANCH_FRAME_DIVERGED",
                    failure.getMessage(),
                    failure,
                    null,
                    "Reconnect parallel targets so every branch finishes in the same function and loop frame"
                ));
            }
        });
    }

    private boolean loopControlRequested(FlowRuntime runtime) {
        return runtime != null && (runtime.isBreakLoopRequested() || runtime.isContinueLoopRequested());
    }

    private void clearFlowDataDependencies(FlowRuntime runtime, FlowGraph graph, List<String> targetNodeIds) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return;
        }
        for (String targetNodeId : targetNodeIds) {
            FlowNode targetNode = graph.getNodes().get(targetNodeId);
            for (FlowConnection conn : graph.getConnectionsToTarget(targetNodeId)) {
                if (runtime.inputPinMatches(targetNode, conn.getTargetPin(), "flow")) {
                    continue;
                }
                clearDependencyOutputs(runtime, graph, targetNodeId, conn.getTargetPin());
            }
        }
    }

    private void clearDependencyOutputs(FlowRuntime runtime, FlowGraph graph, String targetNodeId, String pinName) {
        Set<String> visited = new HashSet<>();
        clearDependencyOutputs(runtime, graph, targetNodeId, pinName, visited);
    }

    private void clearDependencyOutputs(FlowRuntime runtime, FlowGraph graph, String targetNodeId, String pinName, Set<String> visited) {
        if (targetNodeId == null) {
            return;
        }
        FlowNode targetNode = graph.getNodes().get(targetNodeId);
        for (FlowConnection conn : graph.getConnectionsToTarget(targetNodeId)) {
            if (!runtime.inputPinMatches(targetNode, conn.getTargetPin(), pinName)) {
                continue;
            }
            String editorSourceId = conn.getEditorSourceNodeId() != null && !conn.getEditorSourceNodeId().isBlank()
                && conn.getEditorSourcePin() != null && conn.getEditorSourcePin().startsWith("__passthrough:")
                ? conn.getEditorSourceNodeId() : null;
            clearDependencyNode(runtime, graph, editorSourceId, visited);
            if (editorSourceId == null || !editorSourceId.equals(conn.getSourceNodeId())) {
                clearDependencyNode(runtime, graph, conn.getSourceNodeId(), visited);
            }
        }
    }

    private void clearDependencyNode(FlowRuntime runtime, FlowGraph graph, String sourceId, Set<String> visited) {
        if (sourceId == null || !visited.add(sourceId) || !shouldClearNodeOutputs(graph, sourceId)) {
            return;
        }
        runtime.clearNodeOutputs(sourceId);
        FlowNode sourceNode = graph.getNodes().get(sourceId);
        for (FlowConnection sourceConn : graph.getConnectionsToTarget(sourceId)) {
            if (!runtime.inputPinMatches(sourceNode, sourceConn.getTargetPin(), "flow")) {
                clearDependencyOutputs(runtime, graph, sourceId, sourceConn.getTargetPin(), visited);
            }
        }
    }

    private boolean shouldClearNodeOutputs(FlowGraph graph, String nodeId) {
        FlowNode node = graph.getNodes().get(nodeId);
        if (node == null) {
            return false;
        }
        String type = node.getType();
        if (type != null && (type.startsWith("event:") || type.startsWith("event."))) {
            return false;
        }
        return !hasIncomingFlowConnection(graph, nodeId);
    }

    private NodeDefinition resolveTriggerDefinition(FlowNode node) {
        NodeDefinition definition = resolveDefinition(node);
        return definition != null && definition.isTrigger() ? definition : null;
    }

    private void publishTriggerOutputs(FlowRuntime runtime, String nodeId, NodeDefinition definition) {
        Map<String, Object> eventVars = runtime.getEventVariables();
        for (NodeDefinition.PinDefinition output : definition.getOutputs()) {
            if (output.getType() == NodeDefinition.PinType.FLOW) {
                continue;
            }
            String name = output.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String runtimeName = output.getRuntimeName();
            Object value = eventVars.containsKey(name) ? eventVars.get(name) : eventVars.get(runtimeName);
            if (value != null || eventVars.containsKey(name) || eventVars.containsKey(runtimeName)) {
                runtime.setNodeOutput(nodeId, name, value);
            }
        }
    }

    private boolean hasIncomingFlowConnection(FlowGraph graph, String nodeId) {
        FlowNode targetNode = graph.getNodes().get(nodeId);
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            NodeDefinition definition = resolveDefinition(targetNode);
            if (FlowRuntime.inputPinMatches(definition, conn.getTargetPin(), "flow")
                || FlowRuntime.inputPinMatches(definition, conn.getTargetPin(), "next")) {
                return true;
            }
        }
        return false;
    }

    private String resolveLoopOperation(FlowNode node) {
        if (node == null) {
            return null;
        }
        String configuredOperation = node.getHandlerConfig().getString("operation");
        if (isLoopOperation(configuredOperation)) {
            return configuredOperation;
        }
        String type = node.getType();
        return isLoopOperation(type) ? type : null;
    }

    private boolean isLoopOperation(String type) {
        if (type == null) {
            return false;
        }
        return "loop".equals(type)
            || "loop_count".equals(type)
            || "loop_for_each".equals(type)
            || "loop_for_each_player".equals(type)
            || "loop_for_each_entity".equals(type)
            || "loop_interval".equals(type)
            || "loop_while".equals(type);
    }

    private boolean isCustomFunctionNode(String type) {
        return type != null && type.startsWith("custom_function:");
    }

    private String resolveFunctionCallId(FlowRuntime runtime, FlowNode node, NodeDefinition definition) {
        String customFunctionId = extractCustomFunctionId(node.getType());
        if (customFunctionId != null) {
            return customFunctionId;
        }
        String operation = node.getHandlerConfig().getString("operation");
        if ((operation == null || operation.isBlank()) && definition != null) {
            Object configuredOperation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
            operation = configuredOperation instanceof String value ? value : null;
        }
        boolean legacyType = "call.function".equals(node.getType()) || "call_function".equals(node.getType());
        if (!"call_function".equals(operation)
            || !legacyType && (definition == null || !"FunctionHandler".equals(definition.getHandler()))) {
            return null;
        }
        Object configuredFunction = runtime.resolveInput(node, "function");
        return configuredFunction != null ? String.valueOf(configuredFunction).trim() : "";
    }

    private String extractCustomFunctionId(String type) {
        if (!isCustomFunctionNode(type)) {
            return null;
        }
        return type.substring("custom_function:".length());
    }

    private CompletableFuture<Void> ensureInputNodesReady(FlowRuntime runtime, FlowNode node, Player player, Event event) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = graph.findNodeId(node);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }

        return ensureInputNodesReady(runtime, node, player, event, Set.of(nodeId), nodeId);
    }

    private CompletableFuture<Void> ensureInputNodesReady(FlowRuntime runtime, FlowNode node, Player player, Event event,
                                                           Set<String> dependencyPath, String dependencyOwnerNodeId) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = graph.findNodeId(node);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
        FlowNode targetNode = graph.getNodes().get(nodeId);
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if (runtime.inputPinMatches(targetNode, conn.getTargetPin(), "flow")) {
                continue;
            }
            ready = ready.thenCompose(ignored -> ensureDataDependency(runtime, conn.getSourceNodeId(), conn.getSourcePin(),
                player, event, dependencyPath, dependencyOwnerNodeId));
        }
        return ready;
    }

    private CompletableFuture<Void> ensureDataDependency(FlowRuntime runtime, String nodeId, String pinName,
                                                          Player player, Event event, Set<String> dependencyPath,
                                                          String dependencyOwnerNodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (dependencyPath.contains(nodeId)) {
            return dataDependencyCycle(nodeId);
        }
        CompletableFuture<Void> dataEvaluation = runtime.currentDataEvaluation(nodeId);
        if (dataEvaluation == null && runtime.hasNodeOutput(nodeId, pinName)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!runtime.registerDataDependency(dependencyOwnerNodeId, nodeId)) {
            return dataDependencyCycle(nodeId);
        }
        CompletableFuture<Void> pendingEvaluation = dataEvaluation != null ? dataEvaluation : runtime.awaitDataEvaluation(nodeId);
        return pendingEvaluation
            .thenCompose(ignored -> runtime.hasNodeOutput(nodeId, pinName)
                ? CompletableFuture.completedFuture(null)
                : executeDataNode(runtime, nodeId, player, event, dependencyPath));
    }

    private CompletableFuture<Void> executeDataNode(FlowRuntime runtime, String nodeId, Player player, Event event,
                                                    Set<String> dependencyPath) {
        if (nodeId == null || nodeId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (dependencyPath.contains(nodeId)) {
            return dataDependencyCycle(nodeId);
        }
        FlowNode sourceNode = runtime.getGraph().getNodes().get(nodeId);
        if (sourceNode == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "DATA_NODE_MISSING",
                "Data dependency node not found: " + nodeId,
                null,
                nodeId,
                "Reconnect the input to an existing data node"
            ));
        }
        FlowRuntime.DataEvaluation dataEvaluation = runtime.beginDataEvaluation(nodeId);
        if (!dataEvaluation.owner()) {
            return dataEvaluation.completion();
        }
        NodeHandler handler = resolveHandler(sourceNode);
        if (handler == null) {
            FlowExecutionException failure = new FlowExecutionException(
                "DATA_HANDLER_UNAVAILABLE",
                "No handler registered for data node type: " + sourceNode.getType(),
                null,
                nodeId,
                "Install the required capability or replace the unavailable data node"
            );
            runtime.completeDataEvaluation(nodeId, dataEvaluation, failure);
            return dataEvaluation.completion();
        }
        FlowExecutionException operationFailure = validateHandlerOperation(sourceNode, nodeId);
        if (operationFailure != null) {
            runtime.completeDataEvaluation(nodeId, dataEvaluation, operationFailure);
            return dataEvaluation.completion();
        }
        if (!acquireExecutionBudget(runtime)) {
            FlowExecutionException failure = executionBudgetFailureException(runtime, nodeId);
            runtime.completeDataEvaluation(nodeId, dataEvaluation, failure);
            return dataEvaluation.completion();
        }

        Set<String> nextDependencyPath = new HashSet<>(dependencyPath);
        nextDependencyPath.add(nodeId);
        CompletableFuture<Void> evaluation = ensureInputNodesReady(runtime, sourceNode, player, event, Set.copyOf(nextDependencyPath), nodeId)
            .thenCompose(ignored -> executeWithThreadPolicy(runtime, handler.getThreadPolicy(),
                () -> executeDataHandler(runtime, sourceNode, nodeId, player, event, handler)));
        CompletableFuture<Void> result = evaluation.handle((ignored, failure) -> {
                if (failure == null) {
                    return (Void) null;
                }
                Throwable cause = unwrapCompletionFailure(failure);
                FlowExecutionException exception = cause instanceof FlowExecutionException flowExecutionException
                    ? flowExecutionException
                    : new FlowExecutionException(
                        "DATA_EVALUATION_FAILED",
                        "Error evaluating data node '" + sourceNode.getType() + "' (ID: " + nodeId + ")",
                        cause,
                        nodeId,
                        "Inspect the data node inputs and the underlying handler failure"
                    );
                throw new CompletionException(exception);
            });
        result.whenComplete((ignored, failure) -> runtime.completeDataEvaluation(nodeId, dataEvaluation,
            failure != null ? unwrapCompletionFailure(failure) : null));
        return result;
    }

    private CompletableFuture<Void> dataDependencyCycle(String nodeId) {
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "DATA_DEPENDENCY_CYCLE",
            "Data dependency cycle detected at node: " + nodeId,
            null,
            nodeId,
            "Break the data dependency cycle or use explicit state and sequencing"
        ));
    }

    private CompletableFuture<Void> executeDataHandler(FlowRuntime runtime, FlowNode sourceNode, String nodeId, Player player,
                                                       Event event, NodeHandler handler) {
        String previousPin = runtime.getTriggeredOutputPin();
        runtime.setTriggeredOutputPin(null);
        FlowContext context = new FlowContext(runtime, player, event, ignored -> {}, this, null, null, null, null,
            RuntimeExecutionContext.NO_DEADLINE, sourceNode);
        try {
            handler.execute(context, sourceNode);
            context.finishSynchronousCapture();
            context.consumeTriggeredOutputs();
            runtime.consumeTriggeredOutput();
            return pendingOperations(context);
        } catch (Exception e) {
            return settleContext(context, CompletableFuture.failedFuture(
                handlerFailure(e, nodeId, sourceNode.getType(), "DATA_EVALUATION_FAILED", "evaluating")));
        } finally {
            runtime.setTriggeredOutputPin(previousPin);
        }
    }

    private FlowExecutionException handlerFailure(Exception failure, String nodeId, String nodeType, String fallbackCode, String action) {
        if (failure instanceof FlowHandlerException handlerFailure) {
            return new FlowExecutionException(handlerFailure.getCode(), handlerFailure.getMessage(), handlerFailure, nodeId,
                handlerFailure.getRemediation(), handlerFailure.getDetails());
        }
        return new FlowExecutionException(fallbackCode, "Error " + action + " node '" + nodeType + "' (ID: " + nodeId + ")", failure, nodeId,
            "Inspect the node inputs and the underlying handler failure");
    }

    private String findNodeId(FlowGraph graph, FlowNode node) {
        return graph.findNodeId(node);
    }

    private String findTargetNode(FlowGraph graph, String nodeId, String pinName) {
        FlowNode sourceNode = graph.getNodes().get(nodeId);
        for (FlowConnection conn : graph.getConnectionsFromSource(nodeId)) {
            if (FlowRuntime.outputPinMatches(resolveDefinition(sourceNode), conn.getSourcePin(), pinName)) {
                return conn.getTargetNodeId();
            }
        }
        return null;
    }

    private List<String> findLoopTargets(FlowRuntime runtime, FlowGraph graph, String nodeId) {
        List<String> targets = findTargetNodes(runtime, graph, nodeId, "flow");
        if (targets.isEmpty()) {
            targets = findTargetNodes(runtime, graph, nodeId, "loop");
        }
        return targets;
    }

    private List<String> findTargetNodes(FlowRuntime runtime, FlowGraph graph, String nodeId, String pinName) {
        List<String> targets = new ArrayList<>();
        FlowNode sourceNode = graph.getNodes().get(nodeId);
        for (FlowConnection conn : graph.getConnectionsFromSource(nodeId)) {
            if (runtime.outputPinMatches(sourceNode, conn.getSourcePin(), pinName)) {
                targets.add(conn.getTargetNodeId());
            }
        }
        return targets;
    }

    public void setGlobalVariable(String name, Object value) {
        if (value == null) {
            globalVariables.remove(name);
        } else {
            globalVariables.put(name, value);
        }
    }

    public Object getGlobalVariable(String name) {
        return globalVariables.get(name);
    }

    public Map<String, Object> getGlobalVariables() {
        return globalVariables;
    }

    public Map<String, Object> getEventVariables() {
        return eventVariables;
    }

    public void setEventVariable(String name, Object value) {
        eventVariables.put(name, value);
    }

    public void clearEventVariables() {
        eventVariables.clear();
    }

    public void registerPendingTask(String taskId, BukkitTask task) {
        registerPendingTask(taskId, "", task, null);
    }

    public void registerPendingTask(String taskId, BukkitTask task, CompletableFuture<Void> completion) {
        registerPendingTask(taskId, "", task, completion);
    }

    public void registerPendingTask(String taskId, String graphId, BukkitTask task) {
        registerPendingTask(taskId, graphId, task, null);
    }

    public void scheduleWallClockTask(String taskId, String graphId, long delayMillis, Runnable action, CompletableFuture<Void> completion) {
        if (taskId == null || taskId.isBlank() || action == null || completion == null) {
            throw new IllegalArgumentException("Wall-clock task ID, action, and completion are required");
        }
        if (delayMillis < 0L) throw new IllegalArgumentException("Wall-clock task delay must be non-negative");
        long normalizedDelay = delayMillis;
        long createdAt = System.currentTimeMillis();
        long nextFireAt = Math.addExact(createdAt, normalizedDelay);
        scheduleWallClockTask(taskId, graphId, "flow_wall_clock", normalizedDelay, createdAt, nextFireAt, false, () -> {
            action.run();
            return CompletableFuture.completedFuture(null);
        }, completion);
    }

    public void scheduleWallClockTask(String taskId, String graphId, String runtimeOwner, long delayMillis, long createdAt, long nextFireAt,
                                      boolean recurring, Supplier<CompletableFuture<Void>> action, CompletableFuture<Void> completion) {
        if (taskId == null || taskId.isBlank() || action == null || completion == null) {
            throw new IllegalArgumentException("Wall-clock task ID, action, and completion are required");
        }
        if (delayMillis < 0L) throw new IllegalArgumentException("Wall-clock task delay must be non-negative");
        if (wallClockScheduler.isShutdown()) {
            throw new IllegalStateException("Flow wall-clock scheduler is unavailable");
        }
        long normalizedDelay = delayMillis;
        WallClockTask pending = new WallClockTask(graphId, runtimeOwner, completion, createdAt, nextFireAt, recurring);
        terminalTasks.remove(taskId);
        WallClockTask previous = wallClockTasks.put(taskId, pending);
        if (previous != null) {
            previous.cancel();
        }
        completion.whenComplete((result, failure) -> unregisterWallClockTask(taskId, pending, failure));
        try {
            ScheduledFuture<?> timer = wallClockScheduler.schedule(() -> {
                if (completion.isDone()) {
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        if (!pending.operation.start()) {
                            return;
                        }
                        try {
                            CompletableFuture<Void> actionCompletion = action.get();
                            if (actionCompletion == null) {
                                pending.operation.finish(new IllegalStateException("Wall-clock task returned no completion future"));
                                return;
                            }
                            actionCompletion.whenComplete((result, failure) -> {
                                if (failure != null) {
                                    pending.operation.finish(unwrapCompletionFailure(failure));
                                } else {
                                    pending.operation.finish(null);
                                }
                            });
                        } catch (Throwable exception) {
                            pending.operation.finish(exception);
                        }
                    });
                } catch (RuntimeException exception) {
                    completion.completeExceptionally(exception);
                }
            }, normalizedDelay, TimeUnit.MILLISECONDS);
            pending.attach(timer);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
            throw exception;
        }
    }

    private void unregisterWallClockTask(String taskId, WallClockTask expected, Throwable failure) {
        if (taskId == null || expected == null || !wallClockTasks.remove(taskId, expected) || expected.cancelled) {
            return;
        }
        ScheduledTaskState state = expected.completion.isCancelled() ? ScheduledTaskState.CANCELLED
                : failure != null ? ScheduledTaskState.FAILED : ScheduledTaskState.FINISHED;
        Throwable cause = failure != null ? unwrapCompletionFailure(failure) : null;
        String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        rememberTerminalWallClockTask(taskId, expected, state, message);
    }

    public void registerPendingTask(String taskId, String graphId, BukkitTask task, CompletableFuture<Void> completion) {
        registerPendingTask(taskId, graphId, "flow_runtime", task, completion, System.currentTimeMillis(), -1L, false);
    }

    public void registerPendingTask(String taskId, String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                                    long createdAt, long nextFireAt, boolean recurring) {
        registerPendingTask(taskId, graphId, runtimeOwner, task, completion, createdAt, nextFireAt, recurring, null);
    }

    public CompletableFuture<Void> runOnMain(Plugin plugin, Runnable action) {
        Objects.requireNonNull(action, "Main Thread Action Is Required");
        return runOnMain(plugin, false, cancelled -> {
            action.run();
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletableFuture<Void> runOnMain(Plugin plugin, boolean defer,
            Function<BooleanSupplier, CompletionStage<Void>> action) {
        Objects.requireNonNull(plugin, "Main Thread Plugin Is Required");
        Objects.requireNonNull(action, "Main Thread Action Is Required");
        CompletableFuture<Void> admitted = withLegacyAdmission(true, () -> {
            FlowTask operation = new FlowTask();
            Runnable callback = () -> {
                if (!operation.start()) {
                    return;
                }
                try {
                    synchronized (admissionMonitor) {
                        if (admissionFenceDepth > 0 || !plugin.isEnabled()) {
                            throw new IllegalStateException("Main Thread Admission Is Closed");
                        }
                    }
                    CompletionStage<Void> physical = Objects.requireNonNull(action.apply(operation::isCancelled),
                        "Main Thread Physical Completion Is Required");
                    physical.whenComplete((ignored, failure) -> operation.finish(failure));
                } catch (Throwable thrown) {
                    operation.finish(thrown);
                }
            };
            synchronized (admissionMonitor) {
                if (admissionFenceDepth > 0 || !plugin.isEnabled()) {
                    operation.finish(new IllegalStateException("Main Thread Admission Is Closed"));
                } else if (!defer && Bukkit.isPrimaryThread()) {
                    callback.run();
                } else {
                    try {
                        String taskId = "main_callback_" + UUID.randomUUID();
                        BukkitTask task = Bukkit.getScheduler().runTask(plugin, callback);
                        trackTask(taskId, "", task, operation);
                    } catch (Throwable failure) {
                        operation.finish(failure);
                    }
                }
            }
            return operation.completion();
        });
        return admitted.copy();
    }

    void trackTask(String taskId, String graphId, BukkitTask task, FlowTask operation) {
        registerPendingTask(taskId, graphId, "flow_runtime", task, operation.completion(), System.currentTimeMillis(), -1L, false, operation);
    }

    void trackOperation(String taskId, String graphId, CompletableFuture<Void> completion, Runnable cancelAction) {
        Objects.requireNonNull(completion, "Flow Operation Completion Is Required");
        Objects.requireNonNull(cancelAction, "Flow Operation Cancellation Is Required");
        boolean rejected;
        synchronized (admissionMonitor) {
            rejected = admissionFenceDepth > 0 || wallClockScheduler.isShutdown();
            if (!rejected) {
                registerPendingTask(taskId, graphId, "flow_operation", null, completion,
                    System.currentTimeMillis(), -1L, false, null, cancelAction);
            }
        }
        if (rejected) {
            cancelAction.run();
        }
    }

    private void registerPendingTask(String taskId, String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                                     long createdAt, long nextFireAt, boolean recurring, FlowTask operation) {
        registerPendingTask(taskId, graphId, runtimeOwner, task, completion, createdAt, nextFireAt, recurring, operation, null);
    }

    private void registerPendingTask(String taskId, String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                                     long createdAt, long nextFireAt, boolean recurring, FlowTask operation, Runnable cancelAction) {
        if (taskId == null || taskId.isBlank() || task == null && (completion == null || cancelAction == null)) {
            throw new IllegalArgumentException("Tracked task ID and Bukkit task are required");
        }
        terminalTasks.remove(taskId);
        PendingTask previous = pendingTasks.get(taskId);
        long stableCreatedAt = previous != null ? previous.createdAt() : createdAt;
        PendingTask replacement = new PendingTask(graphId != null ? graphId : "", runtimeOwner != null ? runtimeOwner : "flow_runtime", task,
            completion, stableCreatedAt, nextFireAt, recurring, previous != null ? previous.lastFailure() : "", operation, cancelAction);
        previous = pendingTasks.put(taskId, replacement);
        if (previous != null && (previous.task() != task || previous.completion() != completion || previous.cancelAction() != cancelAction)) {
            cancelTask(previous);
        }
        if (completion != null) {
            completion.whenComplete((ignored, failure) -> finishPendingTask(taskId, replacement, failure));
        }
    }

    public void unregisterPendingTask(String taskId) {
        if (taskId != null) {
            PendingTask removed = pendingTasks.remove(taskId);
            if (removed != null) {
                rememberTerminalTask(taskId, removed, TaskCancellationStatus.FINISHED);
            }
        }
    }

    public void finishPendingTask(String taskId, Throwable failure) {
        finishPendingTask(taskId, null, failure);
    }

    private void finishPendingTask(String taskId, PendingTask expected, Throwable failure) {
        if (taskId == null) {
            return;
        }
        AtomicReference<PendingTask> finished = new AtomicReference<>();
        pendingTasks.computeIfPresent(taskId, (ignored, current) -> {
            if (expected != null && (current.task() != expected.task() || current.completion() != expected.completion()
                || current.cancelAction() != expected.cancelAction())) {
                return current;
            }
            Throwable cause = failure == null ? null : unwrapCompletionFailure(failure);
            String message = cause != null && cause.getMessage() != null ? cause.getMessage() : current.lastFailure();
            finished.set(new PendingTask(current.graphId(), current.runtimeOwner(), current.task(), current.completion(),
                current.createdAt(), current.nextFireAt(), current.recurring(), message, current.operation(), current.cancelAction()));
            return null;
        });
        PendingTask removed = finished.get();
        if (removed != null) {
            TaskCancellationStatus status = removed.completion() != null && removed.completion().isCancelled()
                ? TaskCancellationStatus.CANCELLED : TaskCancellationStatus.FINISHED;
            rememberTerminalTask(taskId, removed, status);
        }
    }

    public boolean cancelPendingTask(String taskId) {
        TaskCancellationStatus status = cancelPendingTaskWithStatus(taskId);
        return status == TaskCancellationStatus.CANCELLED || status == TaskCancellationStatus.ALREADY_CANCELLED;
    }

    public TaskCancellationStatus cancelPendingTaskWithStatus(String taskId) {
        PendingTask pending = taskId != null ? pendingTasks.remove(taskId) : null;
        if (pending == null) {
            WallClockTask wallClockTask = taskId != null ? wallClockTasks.remove(taskId) : null;
            if (wallClockTask != null) {
                wallClockTask.cancel();
                rememberTerminalWallClockTask(taskId, wallClockTask, ScheduledTaskState.CANCELLED, "");
                return TaskCancellationStatus.CANCELLED;
            }
            TerminalTask terminal = taskId != null ? terminalTasks.get(taskId) : null;
            if (terminal == null) {
                return TaskCancellationStatus.UNKNOWN;
            }
            return terminal.snapshot().state() == ScheduledTaskState.CANCELLED
                ? TaskCancellationStatus.ALREADY_CANCELLED
                : TaskCancellationStatus.FINISHED;
        }
        cancelTask(pending);
        rememberTerminalTask(taskId, pending, TaskCancellationStatus.CANCELLED);
        return TaskCancellationStatus.CANCELLED;
    }

    public void cancelPendingTasks() {
        for (Map.Entry<String, PendingTask> entry : pendingTasks.entrySet()) {
            PendingTask pending = entry.getValue();
            if (pendingTasks.remove(entry.getKey(), pending)) {
                cancelTask(pending);
                rememberTerminalTask(entry.getKey(), pending, TaskCancellationStatus.CANCELLED);
            }
        }
        for (Map.Entry<String, WallClockTask> entry : wallClockTasks.entrySet()) {
            WallClockTask pending = entry.getValue();
            if (wallClockTasks.remove(entry.getKey(), pending)) {
                pending.cancel();
                rememberTerminalWallClockTask(entry.getKey(), pending, ScheduledTaskState.CANCELLED, "");
            }
        }
    }

    private void cancelTask(PendingTask pending) {
        if (pending.task() != null && !pending.task().isCancelled()) {
            pending.task().cancel();
        }
        if (pending.cancelAction() != null) {
            pending.cancelAction().run();
        } else if (pending.operation() != null) {
            pending.operation().cancel();
        } else if (pending.completion() != null && !pending.completion().isDone()) {
            pending.completion().cancel(false);
        }
    }

    public int cancelPendingTasks(String graphId) {
        if (graphId == null || graphId.isBlank()) {
            return 0;
        }
        List<String> taskIds = pendingTasks.entrySet().stream().filter(entry -> graphId.equals(entry.getValue().graphId())).map(Map.Entry::getKey).toList();
        List<String> wallClockTaskIds = wallClockTasks.entrySet().stream().filter(entry -> graphId.equals(entry.getValue().graphId)).map(Map.Entry::getKey).toList();
        int cancelled = 0;
        for (String taskId : taskIds) {
            if (cancelPendingTask(taskId)) {
                cancelled++;
            }
        }
        for (String taskId : wallClockTaskIds) {
            if (cancelPendingTask(taskId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    public ScheduledTaskSnapshot getScheduledTaskSnapshot(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        PendingTask pending = pendingTasks.get(taskId);
        if (pending != null) {
            return snapshot(taskId, pending, ScheduledTaskState.ACTIVE);
        }
        WallClockTask wallClockTask = wallClockTasks.get(taskId);
        if (wallClockTask != null) {
            return snapshot(taskId, wallClockTask, ScheduledTaskState.ACTIVE, "");
        }
        TerminalTask terminal = terminalTasks.get(taskId);
        return terminal != null ? terminal.snapshot() : null;
    }

    public List<ScheduledTaskSnapshot> getScheduledTaskSnapshots() {
        Map<String, ScheduledTaskSnapshot> snapshots = new LinkedHashMap<>();
        terminalTasks.forEach((taskId, terminal) -> snapshots.put(taskId, terminal.snapshot()));
        pendingTasks.forEach((taskId, pending) -> snapshots.put(taskId, snapshot(taskId, pending, ScheduledTaskState.ACTIVE)));
        wallClockTasks.forEach((taskId, pending) -> snapshots.put(taskId, snapshot(taskId, pending, ScheduledTaskState.ACTIVE, "")));
        return snapshots.values().stream()
            .sorted((first, second) -> first.taskId().compareToIgnoreCase(second.taskId()))
            .toList();
    }

    public void updateScheduledTaskNextFireAt(String taskId, long nextFireAt) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        pendingTasks.computeIfPresent(taskId, (ignored, pending) -> new PendingTask(pending.graphId(), pending.runtimeOwner(), pending.task(),
            pending.completion(), pending.createdAt(), nextFireAt, pending.recurring(), pending.lastFailure(), pending.operation(), pending.cancelAction()));
    }

    public void recordScheduledTaskFailure(String taskId, Throwable failure) {
        if (taskId == null || taskId.isBlank() || failure == null) {
            return;
        }
        String message = failure.getMessage() != null && !failure.getMessage().isBlank() ? failure.getMessage() : failure.getClass().getSimpleName();
        pendingTasks.computeIfPresent(taskId, (ignored, pending) -> new PendingTask(pending.graphId(), pending.runtimeOwner(), pending.task(),
            pending.completion(), pending.createdAt(), pending.nextFireAt(), pending.recurring(), message, pending.operation(), pending.cancelAction()));
        WallClockTask wallClockTask = wallClockTasks.get(taskId);
        if (wallClockTask != null) {
            wallClockTask.lastFailure = message;
        }
    }

    private void rememberTerminalTask(String taskId, PendingTask pending, TaskCancellationStatus status) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        ScheduledTaskState state = status == TaskCancellationStatus.CANCELLED ? ScheduledTaskState.CANCELLED
            : pending.lastFailure() != null && !pending.lastFailure().isBlank() ? ScheduledTaskState.FAILED : ScheduledTaskState.FINISHED;
        terminalTasks.put(taskId, new TerminalTask(snapshot(taskId, pending, state), System.currentTimeMillis()));
        trimTerminalTasks();
    }

    private void rememberTerminalWallClockTask(String taskId, WallClockTask pending, ScheduledTaskState state, String lastFailure) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        terminalTasks.put(taskId, new TerminalTask(snapshot(taskId, pending, state, lastFailure), System.currentTimeMillis()));
        trimTerminalTasks();
    }

    private void trimTerminalTasks() {
        if (terminalTasks.size() <= 1024) {
            return;
        }
        terminalTasks.entrySet().stream()
            .min(Map.Entry.comparingByValue((first, second) -> Long.compare(first.completedAt(), second.completedAt())))
            .map(Map.Entry::getKey)
            .ifPresent(terminalTasks::remove);
    }

    private ScheduledTaskSnapshot snapshot(String taskId, PendingTask pending, ScheduledTaskState state) {
        return new ScheduledTaskSnapshot(taskId, pending.runtimeOwner(), pending.graphId(), pending.createdAt(), pending.nextFireAt(), pending.recurring(), state,
            pending.lastFailure() != null ? pending.lastFailure() : "");
    }

    private ScheduledTaskSnapshot snapshot(String taskId, WallClockTask pending, ScheduledTaskState state, String lastFailure) {
        String failure = lastFailure != null && !lastFailure.isBlank() ? lastFailure : pending.lastFailure;
        return new ScheduledTaskSnapshot(taskId, pending.runtimeOwner, pending.graphId, pending.createdAt, pending.nextFireAt, pending.recurring, state,
            failure != null ? failure : "");
    }

    public void shutdown() {
        liveEvents.values().forEach(LiveEventScope::close);
        liveEvents.clear();
        AdmissionFence admissionFence = fenceAdmissions();
        cancelPendingTasks();
        boolean primaryThread = Bukkit.getServer() != null && Bukkit.isPrimaryThread();
        if (!primaryThread) {
            try {
                admissionFence.awaitDrained();
            } finally {
                try {
                    wallClockScheduler.shutdownNow();
                } finally {
                    admissionFence.close();
                }
            }
            return;
        }
        CompletableFuture<Void> drained = admissionFence.whenDrained();
        if (drained.isDone()) {
            try {
                wallClockScheduler.shutdownNow();
            } finally {
                admissionFence.close();
            }
            return;
        }
        drained.whenComplete((ignored, failure) -> {
            try {
                wallClockScheduler.shutdownNow();
            } finally {
                admissionFence.close();
            }
        });
    }

    private <T> CompletableFuture<T> compiledFunctionUnavailable(FlowGraph graph, String nodeId, String entryPoint) {
        String graphId = graph != null && graph.getId() != null ? graph.getId() : "";
        String normalizedNodeId = nodeId != null ? nodeId : "";
        String normalizedEntryPoint = entryPoint != null && !entryPoint.isBlank() ? entryPoint : "FlowExecutor";
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE");
        diagnostic.put("phase", "capability");
        diagnostic.put("stage", "function-execution");
        diagnostic.put("entryPoint", normalizedEntryPoint);
        diagnostic.put("graphId", graphId);
        diagnostic.put("nodeId", normalizedNodeId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("graphId", graphId);
        details.put("nodeId", normalizedNodeId);
        details.put("bridge", "compiled-core");
        details.put("entryPoint", normalizedEntryPoint);
        details.put("diagnostics", List.of(diagnostic));
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
            "Compiled Function execution is unavailable",
            null,
            normalizedNodeId,
            "Initialize the compiled Function runtime before executing this function",
            details));
    }

    private CompletableFuture<Object> compiledSubFlowUnavailable(FlowGraph graph, String nodeId, String entryPoint) {
        if (graph != null && graph.isFunction()) {
            return compiledFunctionUnavailable(graph, nodeId, entryPoint);
        }
        String graphId = graph != null && graph.getId() != null ? graph.getId() : "";
        String normalizedNodeId = nodeId != null ? nodeId : "";
        String normalizedEntryPoint = entryPoint != null && !entryPoint.isBlank() ? entryPoint : "FlowExecutor.executeSubFlow";
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", "SUBFLOW_COMPILED_EXECUTION_UNAVAILABLE");
        diagnostic.put("phase", "capability");
        diagnostic.put("stage", "subflow-execution");
        diagnostic.put("entryPoint", normalizedEntryPoint);
        diagnostic.put("graphId", graphId);
        diagnostic.put("nodeId", normalizedNodeId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("graphId", graphId);
        details.put("nodeId", normalizedNodeId);
        details.put("bridge", "compiled-core");
        details.put("entryPoint", normalizedEntryPoint);
        details.put("diagnostics", List.of(diagnostic));
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "SUBFLOW_COMPILED_EXECUTION_UNAVAILABLE",
            "Compiled subflow execution is unavailable",
            null,
            normalizedNodeId,
            "Initialize the compiled subflow runtime before executing this subflow",
            details));
    }

    private String graphId(FlowRuntime runtime) {
        FlowGraph graph = runtime != null ? runtime.getGraph() : null;
        return graph != null && graph.getId() != null ? graph.getId() : "";
    }

    public String findStartNode(GraphDocument graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return null;
        }
        List<GraphNode> candidates = graph.nodes().stream().filter(node -> {
            NodeDefinition definition = typedDefinition(node);
            return graph.connections().stream().filter(connection -> connection.target().nodeId().equals(node.instanceId()))
                .noneMatch(connection -> FlowRuntime.inputPinMatches(definition, connection.target().pinId().value(), "flow")
                    || FlowRuntime.inputPinMatches(definition, connection.target().pinId().value(), "next"));
        }).sorted(Comparator.comparing(node -> node.instanceId().canonicalText(), String.CASE_INSENSITIVE_ORDER)).toList();
        for (GraphNode node : candidates) {
            NodeDefinition definition = typedDefinition(node);
            if (definition != null && definition.isTrigger() || isFunctionStartType(node.definition().canonicalText())) {
                return node.instanceId().canonicalText();
            }
        }
        for (GraphNode node : candidates) {
            NodeDefinition definition = typedDefinition(node);
            if (definition != null && definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW)
                || graph.connections().stream().filter(connection -> connection.source().nodeId().equals(node.instanceId()))
                    .anyMatch(connection -> {
                        String pin = connection.source().pinId().value();
                        return FlowRuntime.outputPinMatches(definition, pin, "flow") || FlowRuntime.outputPinMatches(definition, pin, "next")
                            || FlowRuntime.outputPinMatches(definition, pin, "loop") || FlowRuntime.outputPinMatches(definition, pin, "done")
                            || FlowRuntime.outputPinMatches(definition, pin, "true") || FlowRuntime.outputPinMatches(definition, pin, "false")
                            || pin.startsWith("branch_");
                    })) {
                return node.instanceId().canonicalText();
            }
        }
        return candidates.isEmpty() ? graph.nodes().stream().map(node -> node.instanceId().canonicalText())
            .sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null) : candidates.getFirst().instanceId().canonicalText();
    }

    private NodeDefinition typedDefinition(GraphNode node) {
        return nodeDefinitionRegistry == null ? null : nodeDefinitionRegistry.get(node.definition().canonicalText());
    }

    public String findStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> candidates = graph.getNodes().entrySet().stream()
            .filter(entry -> !hasIncomingFlowConnection(graph, entry.getKey()))
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (Map.Entry<String, FlowNode> candidate : candidates) {
            String type = candidate.getValue().getType();
            if (resolveTriggerDefinition(candidate.getValue()) != null || isFunctionStartType(type)) {
                return candidate.getKey();
            }
        }
        for (Map.Entry<String, FlowNode> candidate : candidates) {
            NodeDefinition definition = resolveDefinition(candidate.getValue());
            if (definition != null && definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW)) {
                return candidate.getKey();
            }
            if (hasOutgoingExecutionConnection(graph, candidate.getKey())) {
                return candidate.getKey();
            }
        }
        return candidates.isEmpty() ? graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null)
            : candidates.getFirst().getKey();
    }

    private NodeDefinition resolveDefinition(FlowNode node) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return null;
        }
        return nodeDefinitionRegistry.get(node.getType());
    }

    private boolean isFunctionStartType(String type) {
        return FlowRuntime.isFunctionStartType(type);
    }

    private String findFunctionStartNodeId(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        return graph.getNodes().entrySet().stream()
            .filter(entry -> entry.getValue() != null && (isFunctionStartType(entry.getValue().getType())
                || hasHandlerOperation(resolveDefinition(entry.getValue()), "function_start")))
            .map(Map.Entry::getKey)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .findFirst()
            .orElse(null);
    }

    private boolean hasHandlerOperation(NodeDefinition definition, String operation) {
        return definition != null && definition.getHandlerConfig() != null
            && operation.equals(definition.getHandlerConfig().get("operation"));
    }

    private boolean hasOutgoingExecutionConnection(FlowGraph graph, String nodeId) {
        FlowNode sourceNode = graph.getNodes().get(nodeId);
        NodeDefinition definition = resolveDefinition(sourceNode);
        for (FlowConnection connection : graph.getConnectionsFromSource(nodeId)) {
            String pin = connection.getSourcePin();
            if (pin != null && (FlowRuntime.outputPinMatches(definition, pin, "flow")
                || FlowRuntime.outputPinMatches(definition, pin, "next")
                || FlowRuntime.outputPinMatches(definition, pin, "loop")
                || FlowRuntime.outputPinMatches(definition, pin, "done")
                || FlowRuntime.outputPinMatches(definition, pin, "true")
                || FlowRuntime.outputPinMatches(definition, pin, "false")
                || pin.startsWith("branch_"))) {
                return true;
            }
        }
        return false;
    }

    private void notifyExecutionListeners(FlowGraph graph, String startNodeId, Player player, Event event) {
        for (FlowExecutionListener listener : executionListeners) {
            try {
                listener.onFlowExecution(graph, startNodeId, player, event);
            } catch (Exception e) {
                if (enableDebug) {
                    Log.warn("[Flow] Execution listener failed: " + e.getMessage(), e);
                }
            }
        }
    }

    private NodeHandler resolveHandler(FlowNode node) {
        String nodeType = node.getType();
        if (nodeType == null) {
            return null;
        }

        if (nodeDefinitionRegistry != null) {
            NodeDefinition definition = nodeDefinitionRegistry.get(nodeType);
            if (definition != null) {
                node.setType(nodeType);
                Map<String, Object> authoredConfig = node.getHandlerConfigValues();
                node.setHandlerConfig(ItemStackPropertySelector.runtimeHandlerConfig(
                    nodeType, definition.getHandlerConfig(), node.getInputValues(), authoredConfig));
                String handlerName = definition.getHandler();
                if (handlerName != null && !handlerName.isBlank()) {
                    NodeHandler handler = handlerRegistry.getHandler(handlerName);
                    if (handler != null) {
                        return handler;
                    }
                }
            }
        }

        return handlerRegistry.getHandler(nodeType);
    }

    private FlowExecutionException validateHandlerOperation(FlowNode node, String nodeId) {
        String operation = node.getHandlerConfig().getString("operation");
        if (operation == null || operation.isBlank()) {
            return null;
        }
        String handlerId = node.getType();
        if (nodeDefinitionRegistry != null) {
            NodeDefinition definition = nodeDefinitionRegistry.get(node.getType());
            if (definition != null && definition.getHandler() != null && !definition.getHandler().isBlank()) {
                handlerId = definition.getHandler();
            }
        }
        Set<String> supportedOperations = handlerRegistry.getSupportedOperations(handlerId);
        if (supportedOperations.contains(operation)) {
            return null;
        }
        return new FlowExecutionException(
            "OPERATION_UNAVAILABLE",
            "Handler '" + handlerId + "' does not support operation: " + operation,
            null,
            nodeId,
            "Migrate or replace the node with a supported operation",
            Map.of("handler", handlerId, "operation", operation)
        );
    }

    private FlowTraceRecord startTraceRecord(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, String status,
                                             long durationNanos, Throwable error) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setGraphId(graph != null ? graph.getId() : "");
        record.setExecutionId(runtime != null ? runtime.getExecutionId() : "");
        record.setNodeId(nodeId);
        record.setNodeType(node != null ? node.getType() : "");
        record.setStatus(status);
        record.setExecutionDepth(steps);
        record.setDurationNanos(durationNanos);
        record.setErrorText(error != null ? error.getMessage() : "");
        if (error instanceof FlowExecutionException executionException) {
            record.setErrorCode(executionException.getCode());
            record.setRemediation(executionException.getRemediation());
        }
        record.setInputSummary(summarizeInputs(node));
        record.setOutputSummary("");
        return record;
    }

    private String summarizeInputs(FlowNode node) {
        if (node == null || node.getInputValues() == null || node.getInputValues().isEmpty()) {
            return "";
        }
        NodeDefinition definition = resolveDefinition(node);
        if (definition != null && definition.isSensitive()) {
            return "[redacted]";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : node.getInputValues().entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(isSensitiveInput(entry.getKey()) ? "[redacted]" : summarizeValue(entry.getValue()));
            if (builder.length() > 240) {
                return builder.substring(0, 240);
            }
        }
        return builder.toString();
    }

    private boolean isSensitiveInput(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
            || normalized.contains("api_key") || normalized.contains("apikey") || normalized.contains("credential")
            || normalized.contains("authorization") || normalized.equals("auth") || normalized.equals("headers")
            || normalized.equals("response")
            || normalized.contains("cookie") || normalized.contains("session") || normalized.contains("private_key")
            || normalized.contains("access_key");
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }
                String key = String.valueOf(entry.getKey());
                builder.append(key).append('=').append(isSensitiveInput(key) ? "[redacted]" : summarizeValue(entry.getValue()));
                if (builder.length() > 48) {
                    return builder.substring(0, 48) + "...";
                }
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            for (Object element : iterable) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }
                builder.append(summarizeValue(element));
                if (builder.length() > 48) {
                    return builder.substring(0, 48) + "...";
                }
            }
            return builder.append(']').toString();
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > 48 ? text.substring(0, 48) + "..." : text;
    }

    private void trace(FlowTraceRecord record) {
        FlowTraceService service = traceService;
        if (service != null) {
            service.record(record);
        }
    }

    private void traceSuccess(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, long traceStarted) {
        trace(startTraceRecord(runtime, graph, node, nodeId, steps, "success", System.nanoTime() - traceStarted, null));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "success", "", summarizeInputs(node), "");
        }
    }

    private void traceFailure(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, long traceStarted, Throwable error) {
        trace(startTraceRecord(runtime, graph, node, nodeId, steps, "failure", System.nanoTime() - traceStarted, error));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "failure", error != null ? error.getMessage() : "", summarizeInputs(node), "");
        }
    }

    public static class FlowExecutionException extends Exception {
        private final String code;
        private final String nodeId;
        private final String remediation;
        private final Map<String, Object> details;

        public FlowExecutionException(String message, Throwable cause, String nodeId) {
            this("FLOW_EXECUTION_FAILED", message, cause, nodeId, "Inspect the node and its runtime inputs");
        }

        public FlowExecutionException(String code, String message, Throwable cause, String nodeId, String remediation) {
            this(code, message, cause, nodeId, remediation, Map.of());
        }

        public FlowExecutionException(String code, String message, Throwable cause, String nodeId, String remediation,
                                      Map<String, Object> details) {
            super(message, cause);
            this.code = code;
            this.nodeId = nodeId;
            this.remediation = remediation;
            this.details = details != null ? Map.copyOf(details) : Map.of();
        }

        public String getCode() {
            return code;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getRemediation() {
            return remediation;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", code);
            result.put("message", getMessage());
            result.put("nodeId", nodeId != null ? nodeId : "");
            result.put("remediation", remediation != null ? remediation : "");
            result.put("details", details);
            return Collections.unmodifiableMap(result);
        }
    }
}
