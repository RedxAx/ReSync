package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.Log;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class CompiledTriggerExecution {
    private static final int MAX_EXECUTION_DIAGNOSTICS = 32;
    private static final OwnerId DIAGNOSTIC_OWNER = OwnerId.of("resync");
    private static final WarningLimiter INVOCATION_WARNINGS = new WarningLimiter(128, 30_000L, 8,
        System::currentTimeMillis, Log::warn);
    private final FlowExecutor executor;
    private final CompiledGraphMetadataProvider metadataProvider;
    private final CompiledCoreFlowExecutionBridge bridge;
    private final DiagnosticPublisher diagnosticPublisher;
    private final RuntimePrincipalAuthority principalAuthority;
    private final ServerCompiledPlanRepository planRepository;
    private final Map<String, Boolean> synchronousTraits = new ConcurrentHashMap<>();
    private volatile SourceAuthority sourceAuthority;

    public CompiledTriggerExecution(
        FlowExecutor executor,
        CompiledGraphMetadataProvider metadataProvider,
        CompiledCoreFlowExecutionBridge bridge,
        Consumer<List<Diagnostic>> diagnosticSink
    ) {
        this(executor, metadataProvider, bridge, diagnosticSink, null);
    }

    public CompiledTriggerExecution(
        FlowExecutor executor,
        CompiledGraphMetadataProvider metadataProvider,
        CompiledCoreFlowExecutionBridge bridge,
        Consumer<List<Diagnostic>> diagnosticSink,
        RuntimePrincipalAuthority principalAuthority
    ) {
        this(executor, metadataProvider, bridge, (reportId, diagnostics) -> {
            diagnosticSink.accept(diagnostics);
            return reportId;
        }, principalAuthority, null);
    }

    public CompiledTriggerExecution(
        FlowExecutor executor,
        CompiledGraphMetadataProvider metadataProvider,
        CompiledCoreFlowExecutionBridge bridge,
        DiagnosticPublisher diagnosticPublisher,
        RuntimePrincipalAuthority principalAuthority
    ) {
        this(executor, metadataProvider, bridge, diagnosticPublisher, principalAuthority, null);
    }

    public CompiledTriggerExecution(
        FlowExecutor executor,
        CompiledGraphMetadataProvider metadataProvider,
        CompiledCoreFlowExecutionBridge bridge,
        DiagnosticPublisher diagnosticPublisher,
        RuntimePrincipalAuthority principalAuthority,
        ServerCompiledPlanRepository planRepository
    ) {
        this.executor = Objects.requireNonNull(executor, "Compiled Trigger Executor Is Required");
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "Compiled Trigger Metadata Provider Is Required");
        this.bridge = Objects.requireNonNull(bridge, "Compiled Trigger Bridge Is Required");
        this.diagnosticPublisher = Objects.requireNonNull(diagnosticPublisher, "Compiled Trigger Diagnostic Publisher Is Required");
        this.principalAuthority = principalAuthority;
        this.planRepository = planRepository;
    }

    @FunctionalInterface
    public interface DiagnosticPublisher {
        UUID publish(UUID reportId, List<Diagnostic> diagnostics);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables) {
        return execute(graph, startNodeId, player, event, eventVariables, null, CorrelationId.random());
    }

    public synchronized void bindCoreStorage(FlowStorage storage, ServerId serverId) {
        Objects.requireNonNull(storage, "Authoritative Core graph storage is required");
        Objects.requireNonNull(serverId, "Authoritative Core server identity is required");
        if (sourceAuthority != null && (sourceAuthority.storage() != storage || !sourceAuthority.serverId().equals(serverId))) {
            throw new IllegalStateException("Compiled trigger storage authority cannot change");
        }
        sourceAuthority = new SourceAuthority(storage, serverId);
    }

    private record SourceAuthority(FlowStorage storage, ServerId serverId) {}

    public Optional<CoreGraphStorageBoundary.Decoded> source(String type, String id) {
        SourceAuthority owner = Objects.requireNonNull(sourceAuthority, "Authoritative Core trigger source is not configured");
        if (!List.of("flow", "command").contains(type)) {
            throw new IllegalArgumentException("A Flow Or Command Source Is Required");
        }
        return owner.storage().getCoreGraph(type, id).map(source -> {
            GraphDocument document = Objects.requireNonNull(source.graphDocument(), "Typed Flow Source Is Required");
            ServerResourceLocator expected = new ServerResourceLocator(owner.serverId(),
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
            if (!expected.equals(document.resource()) || document.revision() != source.envelope().assetRevision()) {
                throw new IllegalStateException("The Typed Flow Source Does Not Match Its Committed Identity");
            }
            return source;
        });
    }

    public String findStartNode(CoreGraphStorageBoundary.Decoded source) {
        return executor.findStartNode(Objects.requireNonNull(source.graphDocument(), "Typed Flow Source Is Required"));
    }

    public boolean requiresSynchronousEventWindow(CoreGraphStorageBoundary.Decoded source) {
        if (planRepository != null) {
            return requiresSynchronousEventWindow(identityEnvelope(source));
        }
        return source.graphDocument().nodes().stream().map(node -> node.definition().canonicalText())
            .anyMatch(LiveEventScope::requiresWindow);
    }

    public void prepareSource(CoreGraphStorageBoundary.Decoded source) {
        prepare(identityEnvelope(source));
    }

    public CompletableFuture<Void> executeSource(CoreGraphStorageBoundary.Decoded source, String startNodeId,
                                                  Player player, Event event, Map<String, Object> variables,
                                                  RuntimePrincipal principal, CorrelationId invocationId,
                                                  long deadlineMillis) {
        return execute(identityEnvelope(source), startNodeId, player, event, variables, principal, invocationId,
            deadlineMillis, null, source.graphDocument());
    }

    public CompletionStage<CompiledCoreFlowExecutionBridge.ObservedExecution> executeSourceObserved(
            CoreGraphStorageBoundary.Decoded source, String startNodeId, Player player, Event event,
            Map<String, Object> variables, RuntimePrincipal principal, CorrelationId invocationId, long deadlineMillis) {
        CompiledCoreFlowExecutionBridge.ExecutionObservation observation = observeInvocation(invocationId);
        try {
            executeSource(source, startNodeId, player, event, variables, principal, invocationId, deadlineMillis)
                .whenComplete((ignored, failure) -> observation.finishWithoutExecution(failure));
        } catch (RuntimeException | Error failure) {
            observation.finishWithoutExecution(failure);
        }
        return observation.completion();
    }

    public CompletableFuture<Void> executeSourceDeferred(CoreGraphStorageBoundary.Decoded source, String startNodeId,
                                                          Player player, CompiledRuntimeContextAdapter.Result snapshot,
                                                          CorrelationId invocationId) {
        return execute(identityEnvelope(source), startNodeId, player, null, Map.of(), null, invocationId,
            RuntimeExecutionContext.NO_DEADLINE, Objects.requireNonNull(snapshot, "Event snapshot is required"),
            source.graphDocument());
    }

    private static FlowGraph identityEnvelope(CoreGraphStorageBoundary.Decoded source) {
        GraphDocument document = Objects.requireNonNull(source.graphDocument(), "Typed Flow Source Is Required");
        FlowGraph graph = new FlowGraph(document.resource().id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
        graph.setResourceType(document.resource().resourceType().value());
        graph.setResourceRevision(source.envelope().assetRevision());
        graph.setResourceHash(source.envelope().assetHash().canonicalText());
        graph.setResourceMutationId(source.envelope().assetMutationId());
        graph.setEnabled(source.envelope().assetActivationState() == ResourceActivationState.ACTIVE);
        return graph;
    }

    public boolean requiresSynchronousEventWindow(FlowGraph graph) {
        if (planRepository != null && graph != null && sourceAuthority != null) {
            Boolean prepared = synchronousTraits.get(traitKey(graph));
            return prepared != null ? prepared
                : residentExecution(graph).map(ServerCompiledPlanRepository.ResidentExecution::synchronousEventWindow).orElse(false);
        }
        return synchronousEventWindow(graph);
    }

    public void prepare(FlowGraph graph) {
        if (planRepository == null || graph == null) {
            return;
        }
        ServerCompiledPlanRepository.ResidentExecution resident = residentExecution(graph).orElseThrow(() ->
            new IllegalStateException("The admitted resident trigger execution template is unavailable"));
        String preparedKey = traitKey(graph);
        synchronousTraits.keySet().removeIf(key -> key.startsWith(resourcePrefix(graph.getResourceType(), graph.getId()))
            && !key.equals(preparedKey));
        synchronousTraits.merge(preparedKey, resident.synchronousEventWindow() || synchronousEventWindow(graph),
            (existing, incoming) -> existing || incoming);
    }

    public void retire(String resourceType, String resourceId) {
        synchronousTraits.keySet().removeIf(key -> key.startsWith(resourcePrefix(resourceType, resourceId)));
    }

    private Optional<ServerCompiledPlanRepository.ResidentExecution> residentExecution(FlowGraph graph) {
        SourceAuthority sourceOwner = Objects.requireNonNull(sourceAuthority, "Authoritative Core trigger source is not configured");
        ServerResourceLocator resource = new ServerResourceLocator(sourceOwner.serverId(),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(graph.getResourceType())), graph.getId());
        return planRepository.residentExecution(resource, graph.getResourceRevision());
    }

    private static boolean synchronousEventWindow(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return false;
        }
        return graph.getNodes().values().stream().filter(Objects::nonNull).map(node -> node.getType())
            .anyMatch(LiveEventScope::requiresWindow);
    }

    private static String traitKey(FlowGraph graph) {
        return resourcePrefix(graph.getResourceType(), graph.getId()) + graph.getResourceRevision();
    }

    private static String resourcePrefix(String type, String id) {
        return (type == null ? "" : type) + "\u0000" + (id == null ? "" : id) + "\u0000";
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                           Map<String, Object> eventVariables, RuntimePrincipal requestedPrincipal,
                                           CorrelationId invocationId) {
        return execute(graph, startNodeId, player, event, eventVariables, requestedPrincipal, invocationId,
            RuntimeExecutionContext.NO_DEADLINE);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                           Map<String, Object> eventVariables, RuntimePrincipal requestedPrincipal,
                                           CorrelationId invocationId, long requestedDeadlineMillis) {
        long started = System.nanoTime();
        try {
            return execute(graph, startNodeId, player, event, eventVariables, requestedPrincipal, invocationId, requestedDeadlineMillis, null);
        } finally {
            if (graph != null && "command".equals(graph.getResourceType())) {
                TemporaryLifecycleDiagnostics.recordTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.COMMAND_RETURN,
                    System.nanoTime() - started);
            }
        }
    }

    public CompiledCoreFlowExecutionBridge.ExecutionObservation observeInvocation(CorrelationId invocationId) {
        return bridge.observeInvocation(invocationId);
    }

    public CompletionStage<CompiledCoreFlowExecutionBridge.ObservedExecution> executeObserved(FlowGraph graph,
            String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
            RuntimePrincipal principal, CorrelationId invocationId, long deadlineMillis) {
        CompiledCoreFlowExecutionBridge.ExecutionObservation observation = observeInvocation(invocationId);
        try {
            execute(graph, startNodeId, player, event, eventVariables, principal, invocationId, deadlineMillis)
                .whenComplete((ignored, failure) -> observation.finishWithoutExecution(failure));
        } catch (RuntimeException | Error failure) {
            observation.finishWithoutExecution(failure);
        }
        return observation.completion();
    }

    public CompiledRuntimeContextAdapter.Result snapshotEvent(Player player, Event event, Map<String, Object> variables) {
        SourceAuthority owner = Objects.requireNonNull(sourceAuthority, "Authoritative Core trigger source is not configured");
        return CompiledRuntimeContextAdapter.adapt(owner.serverId(), player, event, variables);
    }

    public CompletableFuture<Void> executeDeferred(FlowGraph graph, String startNodeId, Player player,
                                                   CompiledRuntimeContextAdapter.Result snapshot, CorrelationId invocationId) {
        return execute(graph, startNodeId, player, null, Map.of(), null, invocationId, RuntimeExecutionContext.NO_DEADLINE,
            Objects.requireNonNull(snapshot, "Event snapshot is required"));
    }

    private CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVariables, RuntimePrincipal requestedPrincipal,
                                            CorrelationId invocationId, long requestedDeadlineMillis,
                                            CompiledRuntimeContextAdapter.Result preparedContext) {
        return execute(graph, startNodeId, player, event, eventVariables, requestedPrincipal, invocationId,
            requestedDeadlineMillis, preparedContext, null);
    }

    private CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVariables, RuntimePrincipal requestedPrincipal,
                                            CorrelationId invocationId, long requestedDeadlineMillis,
                                            CompiledRuntimeContextAdapter.Result preparedContext, GraphDocument expectedSource) {
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.recordsNormal()
            ? lifecycleIdentity(graph, startNodeId, invocationId, null)
            : new LinkedHashMap<>();
        progress("trigger_execution_received", started, identity, "outcome", "received");
        if (requestedDeadlineMillis < 0) {
            return rejected("The compiled trigger deadline is invalid", graph, startNodeId, invocationId, started, identity,
                null, null, null, List.of());
        }
        CompiledGraphMetadataProvider.Result metadata;
        GraphDocument sourceDocument = null;
        try {
            SourceAuthority sourceOwner = Objects.requireNonNull(sourceAuthority, "Authoritative Core trigger source is not configured");
            if (TemporaryLifecycleDiagnostics.recordsNormal()) {
                identity = TemporaryLifecycleDiagnostics.with(identity, "serverId", sourceOwner.serverId());
            }
            GraphDocument document;
            FunctionSourceDocument functionSource;
            String envelopeHash;
            ResourceActivationState activationState;
            if (planRepository != null) {
                ServerResourceLocator resource = new ServerResourceLocator(sourceOwner.serverId(),
                    ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(graph.getResourceType())), graph.getId());
                Optional<ServerCompiledPlanRepository.ResidentExecution> admitted = planRepository.residentExecution(resource,
                    graph.getResourceRevision());
                if (admitted.isEmpty()) {
                    sourceDocument = planRepository.residentExecution(resource)
                        .map(ServerCompiledPlanRepository.ResidentExecution::graph).orElse(null);
                    if (sourceDocument != null && TemporaryLifecycleDiagnostics.recordsNormal()) {
                        identity = TemporaryLifecycleDiagnostics.with(sourceIdentity(identity, sourceDocument),
                            "requestedRevision", graph.getResourceRevision(), "currentSourceRevision", sourceDocument.revision());
                    }
                    throw new IllegalStateException("The exact resident trigger revision is unavailable");
                }
                ServerCompiledPlanRepository.ResidentExecution template = admitted.orElseThrow();
                document = template.graph();
                functionSource = template.functionSource();
                envelopeHash = template.envelopeChecksum().canonicalText();
                activationState = template.activationState();
                metadata = Objects.requireNonNull(template.metadata(), "Resident Compiled Trigger Metadata Is Required");
            } else {
                CoreGraphStorageBoundary.Decoded source = sourceOwner.storage().getCoreGraph(graph.getResourceType(), graph.getId())
                    .orElseThrow(() -> new IllegalStateException("The authoritative Core trigger source is unavailable"));
                document = source.graphDocument() != null ? source.graphDocument() : source.functionSourceDocument().graph();
                functionSource = source.functionSourceDocument();
                envelopeHash = source.envelope().assetHash().canonicalText();
                activationState = source.envelope().assetActivationState();
                metadata = null;
            }
            sourceDocument = document;
            if (expectedSource != null && (document != expectedSource
                && !document.checksum().equals(expectedSource.checksum()))) {
                throw new IllegalStateException("The Selected Typed Source Is No Longer Current");
            }
            if (TemporaryLifecycleDiagnostics.recordsNormal()) {
                identity = sourceIdentity(identity, document);
            }
            if (!document.resource().serverId().equals(sourceOwner.serverId())
                || !document.resource().id().equals(graph.getId())
                || !document.resource().resourceType().value().equals(graph.getResourceType())
                || document.revision() != graph.getResourceRevision()
                || !(envelopeHash.equals(graph.getResourceHash())
                    || document.checksum().canonicalText().equals(graph.getResourceHash()))
                || activationState != ResourceActivationState.ACTIVE) {
                throw new IllegalStateException("The loaded trigger projection does not match its active authoritative Core source");
            }
            progress("trigger_source_admitted", started, identity, "outcome", "admitted");
            if (metadata == null) {
                metadata = Objects.requireNonNull(metadataProvider.provide(document, functionSource),
                    "Compiled trigger metadata result is required");
            }
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_source_admission", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.SOURCE_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = CompiledGraphMetadataProvider.diagnostic(
                "GRAPH.OPAQUE_UNAVAILABLE",
                graph,
                startNodeId,
                "The compiled trigger metadata provider failed",
                Map.of("failureType", failure.getClass().getName()));
            Diagnostic sourceFailure = diagnostic("GRAPH.RUNTIME_PROVIDER_UNAVAILABLE", "trigger-source",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("Compiled trigger metadata could not be provided", graph, startNodeId, invocationId, started,
                identity, sourceDocument, null, failure, List.of(diagnostic, sourceFailure));
        }
        if (!metadata.accepted()) {
            TemporaryLifecycleDiagnostics.event("trigger_activation_resolution", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "rejected", "diagnosticCode", "TRIGGER.ACTIVATION_REJECTED",
                    "diagnosticCount", metadata.diagnostics().size()));
            return rejected("The trigger graph has no complete compiled execution metadata", graph, startNodeId, invocationId,
                started, identity, sourceDocument, null, metadata.failure(), metadata.diagnostics());
        }
        CompiledGraphMetadata compiledMetadata;
        try {
            compiledMetadata = Objects.requireNonNull(metadata.metadata(), "Accepted compiled trigger metadata is required");
            if (TemporaryLifecycleDiagnostics.recordsNormal()) {
                identity = lifecycleIdentity(graph, startNodeId, invocationId, compiledMetadata);
            }
            progress("trigger_activation_resolved", started, identity, "outcome", "resolved");
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_activation_resolution", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.ACTIVATION_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = diagnostic("RUNTIME.INVALID_INVOCATION", "trigger-activation",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("The trigger activation could not be admitted", graph, startNodeId, invocationId, started,
                identity, sourceDocument, null, failure, List.of(diagnostic));
        }
        RuntimePrincipal principal;
        try {
            principal = requestedPrincipal != null ? requestedPrincipal
                : principalAuthority == null ? null
                    : player == null
                        ? principalAuthority.issueSystem("resync-trigger")
                        : principalAuthority.issuePlayer(player.getUniqueId().toString());
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_context_adaptation", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.CONTEXT_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = diagnostic("RUNTIME.INVALID_INVOCATION", "trigger-context",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("The trigger context could not be admitted", graph, startNodeId, invocationId, started,
                identity, sourceDocument, compiledMetadata, failure, List.of(diagnostic));
        }
        boolean trusted;
        try {
            trusted = principalAuthority == null || principal != null
                && principalAuthority.trusts(principal, principalAuthority.authority());
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_context_adaptation", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.PRINCIPAL_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = diagnostic("RUNTIME.INVALID_INVOCATION", "trigger-principal",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("The trigger principal could not be verified", graph, startNodeId, invocationId, started,
                identity, sourceDocument, compiledMetadata, failure, List.of(diagnostic));
        }
        if (!trusted) {
            TemporaryLifecycleDiagnostics.event("trigger_context_adaptation", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "rejected", "diagnosticCode", "TRIGGER.PRINCIPAL_REJECTED"));
            return rejected("The trigger principal is not trusted by the runtime authority", graph, startNodeId, invocationId,
                started, identity, sourceDocument, compiledMetadata, null, List.of());
        }
        CompiledRuntimeContextAdapter.Result contextResult;
        try {
            if (preparedContext == null) {
                contextResult = CompiledRuntimeContextAdapter.adapt(compiledMetadata.resource().serverId(), principal, player,
                    event, eventVariables == null ? Map.of() : eventVariables);
            } else if (preparedContext.accepted()) {
                CompiledRuntimeContext snapshot = preparedContext.context();
                contextResult = CompiledRuntimeContextAdapter.Result.accepted(new CompiledRuntimeContext(snapshot.player(),
                    snapshot.event(), snapshot.variables(), principal));
            } else {
                contextResult = preparedContext;
            }
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_context_adaptation", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.CONTEXT_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = diagnostic("RUNTIME.INVALID_INVOCATION", "trigger-context",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("The trigger context could not be admitted", graph, startNodeId, invocationId, started,
                identity, sourceDocument, compiledMetadata, failure, List.of(diagnostic));
        }
        if (!contextResult.accepted()) {
            Diagnostic diagnostic = CompiledGraphMetadataProvider.diagnostic(
                "GRAPH.OPAQUE_UNAVAILABLE",
                graph,
                startNodeId,
                "The compiled Core trigger contract cannot preserve the legacy event context",
                Map.of(
                    "boundary", "trigger-context",
                    "contextContract", "compiled-runtime-context-v1",
                    "supportedContext", "typed-identities-and-transport-values",
                    "playerPresent", player != null,
                    "eventPresent", event != null,
                    "eventVariablesPresent", eventVariables != null && !eventVariables.isEmpty(),
                    "failureType", "context-adaptation-rejected"));
            TemporaryLifecycleDiagnostics.event("trigger_context_adaptation", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "rejected", "diagnosticCode", "TRIGGER.CONTEXT_REJECTED",
                    "playerPresent", player != null, "eventPresent", event != null,
                    "variableCount", eventVariables == null ? 0 : eventVariables.size(),
                    "failureHash", TemporaryLifecycleDiagnostics.safeHash(contextResult.failure())));
            return rejected("The trigger requires a deterministic typed event context", graph, startNodeId, invocationId,
                started, identity, sourceDocument, compiledMetadata, null, List.of(diagnostic));
        }
        CompiledRuntimeContext runtimeContext = contextResult.context();
        progress("trigger_context_adapted", started, identity, "outcome", "adapted", "playerPresent", player != null,
            "eventPresent", event != null, "variableCount", eventVariables == null ? 0 : eventVariables.size());
        FlowExecutionBridge.MappingContext mappingContext = metadata.mappingContext();
        try {
            FlowExecutor.CompiledExecutionAuthority authority = new FlowExecutor.CompiledExecutionAuthority(
                CompiledCoreFlowExecutionBridge.authorityHash(),
                compiledMetadata.catalogBinding().catalogChecksum(),
                compiledMetadata.catalogBinding().bindingManifestHash());
            GraphDocument admittedSource = sourceDocument;
            CompletableFuture<Void> future = executor.withLiveEventScope(runtimeContext, invocationId, event, () -> expectedSource == null
                ? executor.executeCompiled(graph, startNodeId, runtimeContext, mappingContext, compiledMetadata,
                    authority, bridge, invocationId, requestedDeadlineMillis)
                : executor.executeCompiledSource(admittedSource, graph, startNodeId, runtimeContext, mappingContext,
                    compiledMetadata, authority, bridge, invocationId, requestedDeadlineMillis));
            progress("trigger_executor_admitted", started, identity, "outcome", "admitted");
            return terminal(future, graph, sourceDocument, compiledMetadata, startNodeId, invocationId, started, identity, true);
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.event("trigger_executor_admission", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "diagnosticCode", "TRIGGER.EXECUTOR_REJECTED",
                    "failureType", failure.getClass().getSimpleName()));
            Diagnostic diagnostic = CompiledGraphMetadataProvider.diagnostic(
                "GRAPH.OPAQUE_UNAVAILABLE",
                graph,
                startNodeId,
                "The compiled Core trigger admission failed",
                Map.of("failureType", failure.getClass().getName()));
            Diagnostic runtimeFailure = diagnostic("RUNTIME.HANDLER_FAILURE", "trigger-admission",
                Map.of("failureType", failure.getClass().getName()));
            return rejected("Compiled Core trigger admission failed", graph, startNodeId, invocationId, started, identity,
                sourceDocument, compiledMetadata, failure, List.of(diagnostic, runtimeFailure));
        }
    }

    public void observe(CompletableFuture<Void> future, CorrelationId invocationId, String source) {
        Objects.requireNonNull(future, "Trigger Execution Future Is Required");
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        future.exceptionally(failure -> {
            TerminalOutcome outcome = terminalOutcome(failure);
            String channel = source != null && source.startsWith("command") ? "command"
                : source != null && source.startsWith("event") ? "event" : "trigger";
            String message = "Compiled " + channel + " trigger invocation " + outcome.outcome()
                + " correlationId=" + invocationId.canonicalText() + " diagnosticCode=" + outcome.diagnosticCode()
                + (source == null || source.isBlank() ? "" : " source=" + source)
                + invocationDetail(failure);
            warnInvocation((source == null ? channel : source) + "|" + outcome.diagnosticCode(), message);
            return null;
        });
    }

    public static void warnInvocation(String key, String message) {
        INVOCATION_WARNINGS.warn(key, message);
    }

    private CompletableFuture<Void> rejected(String message, FlowGraph graph, String startNodeId, CorrelationId invocationId,
                                             long started, Map<String, Object> identity, GraphDocument sourceDocument,
                                             CompiledGraphMetadata metadata, Throwable failure,
                                             List<Diagnostic> diagnostics) {
        ArrayList<Diagnostic> normalized;
        try {
            List<Diagnostic> source = diagnostics == null || diagnostics.isEmpty()
                ? List.of(diagnostic("RUNTIME.INVALID_INVOCATION", "trigger-rejected",
                    Map.of("failureType", failure == null ? "rejected" : failure.getClass().getName())))
                : List.copyOf(diagnostics);
            normalized = new ArrayList<>(source.size() + 1);
            source.stream()
                .map(diagnostic -> invocationDiagnostic(diagnostic, graph, sourceDocument, metadata, startNodeId, invocationId))
                .forEach(normalized::add);
            normalized.add(invocationDiagnostic(diagnostic("RUNTIME.INVOCATION_AUDIT", "trigger-rejected-audit",
                Map.of("outcome", "rejected", "diagnosticCode", normalized.getFirst().code())), graph, sourceDocument,
                metadata, startNodeId, invocationId));
        } catch (RuntimeException diagnosticFailure) {
            CompletableFuture<Void> rejected = CompletableFuture.failedFuture(new FlowExecutor.FlowExecutionException(
                "CORE_EXECUTION_FAILED", "Compiled trigger diagnostics could not be constructed", diagnosticFailure,
                startNodeId, "Restore the diagnostic catalog before admitting trigger execution"));
            return terminal(rejected, graph, sourceDocument, metadata, startNodeId, invocationId, started, identity, false, null);
        }
        CompletableFuture<Void> future;
        UUID reportId = null;
        try {
            reportId = diagnosticPublisher.publish(invocationId.value(), normalized);
        } catch (RuntimeException reportFailure) {
            future = CompletableFuture.failedFuture(new FlowExecutor.FlowExecutionException(
                "CORE_EXECUTION_FAILED",
                "Compiled trigger diagnostics could not be durably persisted",
                reportFailure,
                startNodeId,
                "Restore durable diagnostics before admitting trigger execution",
                Map.of("diagnostics", normalized.stream().map(Diagnostic::toMap).toList())));
            return terminal(future, graph, sourceDocument, metadata, startNodeId, invocationId, started, identity, false, null);
        }
        future = CompletableFuture.failedFuture(new FlowExecutor.FlowExecutionException(
            "CORE_EXECUTION_UNSUPPORTED",
            message,
            failure,
            startNodeId,
            "Migrate the trigger graph and context to the compiled Core contract",
            Map.of("diagnostics", normalized.stream().map(Diagnostic::toMap).toList())));
        return terminal(future, graph, sourceDocument, metadata, startNodeId, invocationId, started, identity, false, reportId);
    }

    private CompletableFuture<Void> terminal(CompletableFuture<Void> future, FlowGraph graph, GraphDocument sourceDocument,
                                             CompiledGraphMetadata metadata, String startNodeId,
                                             CorrelationId invocationId, long started, Map<String, Object> identity,
                                             boolean reportFailure) {
        return terminal(future, graph, sourceDocument, metadata, startNodeId, invocationId, started, identity, reportFailure, null);
    }

    private CompletableFuture<Void> terminal(CompletableFuture<Void> future, FlowGraph graph, GraphDocument sourceDocument,
                                             CompiledGraphMetadata metadata, String startNodeId,
                                             CorrelationId invocationId, long started, Map<String, Object> identity,
                                             boolean reportFailure, UUID existingReportId) {
        return future.whenComplete((ignored, failure) -> {
            UUID reportId = existingReportId;
            try {
                if (reportFailure) {
                    reportId = reportExecutionFailure(graph, sourceDocument, metadata, startNodeId, invocationId, failure);
                }
            } catch (RuntimeException reportFailureValue) {
                warnInvocation("diagnostic-persistence|TRIGGER.DIAGNOSTIC_PERSISTENCE_FAILED",
                    "Compiled trigger diagnostic persistence failed correlationId=" + invocationId.canonicalText()
                        + " diagnosticCode=TRIGGER.DIAGNOSTIC_PERSISTENCE_FAILED");
            } finally {
                if (failure == null) {
                    return;
                }
                TerminalOutcome outcome = terminalOutcome(failure);
                Map<String, Object> terminalIdentity = reportId == null ? identity
                    : TemporaryLifecycleDiagnostics.with(identity, "diagnosticReportId", reportId);
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, terminalIdentity, outcome.outcome(),
                    outcome.diagnosticCode(), outcome.reason());
            }
        });
    }

    private static void progress(String stage, long started, Map<String, Object> identity, Object... fields) {
        if (!TemporaryLifecycleDiagnostics.recordsNormal()) {
            return;
        }
        TemporaryLifecycleDiagnostics.event(stage, started, TemporaryLifecycleDiagnostics.with(identity, fields));
    }

    private Map<String, Object> lifecycleIdentity(FlowGraph graph, String startNodeId, CorrelationId invocationId,
                                                   CompiledGraphMetadata metadata) {
        String resourceType = graph == null ? "" : value(graph.getResourceType());
        String resourceId = graph == null ? "" : value(graph.getId());
        Object typedKey = metadata != null ? metadata.resource()
            : resourceType.isBlank() || resourceId.isBlank() ? null : resourceType + ":" + resourceId;
        Object serverId = metadata == null ? null : metadata.resource().serverId();
        Object generation = metadata == null ? null : metadata.catalogBinding().generation();
        Object revision = graph == null ? null : graph.getResourceRevision();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(serverId, typedKey, "trigger-execution",
            null, null, invocationId, null, revision, null, generation);
        return TemporaryLifecycleDiagnostics.with(identity,
            "resourceType", resourceType,
            "resourceId", resourceId,
            "startNodeId", value(startNodeId),
            "graphHash", graph == null ? "" : value(graph.getResourceHash()),
            "catalogChecksum", metadata == null ? "" : metadata.catalogBinding().catalogChecksum().canonicalText(),
            "bindingManifestHash", metadata == null ? "" : metadata.catalogBinding().bindingManifestHash().canonicalText());
    }

    private Map<String, Object> sourceIdentity(Map<String, Object> identity, GraphDocument document) {
        return TemporaryLifecycleDiagnostics.with(identity,
            "serverId", document.resource().serverId(), "typedKey", document.resource(), "revision", document.revision(),
            "generation", document.catalogBinding().generation(),
            "catalogChecksum", document.catalogBinding().catalogChecksum().canonicalText(),
            "bindingManifestHash", document.catalogBinding().bindingManifestHash().canonicalText());
    }

    private TerminalOutcome terminalOutcome(Throwable failure) {
        if (failure == null) {
            return new TerminalOutcome("success", "TRIGGER.EXECUTION_SUCCEEDED", "execution-complete");
        }
        Throwable cause = unwrap(failure);
        String code = cause instanceof FlowExecutor.FlowExecutionException executionFailure
            ? executionFailure.getCode() : cause.getClass().getSimpleName();
        String diagnosticCode = code;
        for (Diagnostic diagnostic : executionDiagnostics(cause)) {
            if (diagnostic.code().startsWith("RUNTIME.") && !diagnostic.code().equals("RUNTIME.INVOCATION_AUDIT")) {
                diagnosticCode = diagnostic.code();
                if (!diagnosticCode.equals("RUNTIME.HANDLER_FAILURE")) break;
            }
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        if ("CORE_EXECUTION_UNSUPPORTED".equals(normalized)) {
            return new TerminalOutcome("rejected", diagnosticCode, "execution-rejected");
        }
        if (hasCause(cause, TimeoutException.class) || normalized.contains("TIMEOUT") || normalized.contains("DEADLINE")
            || normalized.contains("DURATION_BUDGET")) {
            return new TerminalOutcome("timeout", diagnosticCode, "execution-timeout");
        }
        if (hasCause(cause, CancellationException.class) || normalized.contains("CANCEL")) {
            return new TerminalOutcome("cancelled", diagnosticCode, "execution-cancelled");
        }
        return new TerminalOutcome("failed", diagnosticCode, "execution-failed");
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record TerminalOutcome(String outcome, String diagnosticCode, String reason) {
    }

    private UUID reportExecutionFailure(FlowGraph graph, GraphDocument sourceDocument, CompiledGraphMetadata metadata,
                                        String startNodeId, CorrelationId invocationId, Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable cause = unwrap(failure);
        List<Diagnostic> diagnostics = executionDiagnostics(cause);
        TerminalOutcome outcome = terminalOutcome(failure);
        if (diagnostics.isEmpty()) {
            Diagnostic compatibility = CompiledGraphMetadataProvider.diagnostic(
                "GRAPH.OPAQUE_UNAVAILABLE", graph, startNodeId, "The compiled Core trigger execution failed",
                Map.of("failureType", cause.getClass().getName()));
            String runtimeCode = switch (outcome.outcome()) {
                case "timeout" -> "RUNTIME.EXECUTION_TIMEOUT";
                case "cancelled" -> "RUNTIME.EXECUTION_CANCELLED";
                default -> "RUNTIME.HANDLER_FAILURE";
            };
            Diagnostic specific = diagnostic(runtimeCode, "trigger-execution",
                Map.of("failureType", cause.getClass().getName(), "executionCode", outcome.diagnosticCode()));
            diagnostics = List.of(compatibility, specific);
        }
        ArrayList<Diagnostic> normalized = new ArrayList<>(diagnostics.size() + 1);
        for (Diagnostic diagnostic : diagnostics) {
            normalized.add(invocationDiagnostic(diagnostic, graph, sourceDocument, metadata, startNodeId, invocationId));
        }
        Diagnostic audit = diagnostic("RUNTIME.INVOCATION_AUDIT", "trigger-execution-audit",
            Map.of("outcome", outcome.outcome(), "diagnosticCode", outcome.diagnosticCode()));
        normalized.add(invocationDiagnostic(audit, graph, sourceDocument, metadata, startNodeId, invocationId));
        return diagnosticPublisher.publish(invocationId.value(), normalized);
    }

    private List<Diagnostic> executionDiagnostics(Throwable cause) {
        if (!(cause instanceof FlowExecutor.FlowExecutionException failure)
            || !(failure.getDetails().get("diagnostics") instanceof List<?> values)) {
            return List.of();
        }
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        int limit = Math.min(values.size(), MAX_EXECUTION_DIAGNOSTICS);
        for (int index = 0; index < limit; index++) {
            try {
                Object value = values.get(index);
                if (!(value instanceof Map<?, ?> fields) || !boundedDiagnosticValue(fields, 0, new int[] {512, 16_384})) {
                    continue;
                }
                LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : fields.entrySet()) canonical.put((String) field.getKey(), field.getValue());
                diagnostics.add(Diagnostic.fromCanonical(canonical));
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(diagnostics);
    }

    private String invocationDetail(Throwable failure) {
        for (Diagnostic diagnostic : executionDiagnostics(unwrap(failure))) {
            Object reason = diagnostic.evidence().get("reason");
            if (!(reason instanceof String text) || text.isBlank()) {
                continue;
            }
            String normalized = text.replace('\n', ' ').replace('\r', ' ').strip();
            if (normalized.length() > 240) {
                normalized = normalized.substring(0, 240);
            }
            String node = diagnostic.nodeId() == null ? "" : " nodeId=" + diagnostic.nodeId().canonicalText();
            return node + " detail=" + normalized;
        }
        return "";
    }

    private boolean boundedDiagnosticValue(Object value, int depth, int[] budget) {
        if (depth > 8 || --budget[0] < 0) return false;
        if (value instanceof String text) {
            budget[1] -= text.length();
            return budget[1] >= 0;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > budget[0]) return false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !boundedDiagnosticValue(key, depth + 1, budget)
                    || !boundedDiagnosticValue(entry.getValue(), depth + 1, budget)) return false;
            }
            return true;
        }
        if (value instanceof List<?> list) {
            if (list.size() > budget[0]) return false;
            for (Object entry : list) {
                if (!boundedDiagnosticValue(entry, depth + 1, budget)) return false;
            }
            return true;
        }
        if (value instanceof BigInteger integer) return integer.bitLength() <= 426;
        if (value instanceof BigDecimal decimal) return decimal.precision() <= 128 && Math.abs((long) decimal.scale()) <= 128L;
        if (value instanceof Double decimal) return Double.isFinite(decimal);
        if (value instanceof Float decimal) return Float.isFinite(decimal);
        return value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long;
    }

    private Diagnostic invocationDiagnostic(Diagnostic source, FlowGraph graph, GraphDocument document,
                                            CompiledGraphMetadata metadata, String startNodeId,
                                            CorrelationId invocationId) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>(source.evidence());
        evidence.put("stableDiagnosticId", source.correlationId().toString());
        evidence.put("invocationCorrelationId", invocationId.canonicalText());
        evidence.put("resourceType", graph == null ? "" : value(graph.getResourceType()));
        evidence.put("resourceId", graph == null ? "" : value(graph.getId()));
        evidence.put("startNodeInstanceId", value(startNodeId));
        if (graph != null) {
            evidence.put("requestedRevision", graph.getResourceRevision());
        }
        SourceAuthority owner = sourceAuthority;
        if (owner != null) {
            evidence.put("serverId", owner.serverId().canonicalText());
        }
        if (document != null) {
            evidence.put("typedResource", document.resource().canonicalText());
            evidence.put("currentSourceRevision", document.revision());
            evidence.put("catalogGeneration", document.catalogBinding().generation());
            evidence.put("catalogChecksum", document.catalogBinding().catalogChecksum().canonicalText());
            evidence.put("bindingManifestHash", document.catalogBinding().bindingManifestHash().canonicalText());
        }
        var builder = Diagnostic.builder(source.code(), source.severity(), source.phase(), source.stage())
            .messageKey(source.messageKey())
            .message(source.message())
            .arguments(source.arguments())
            .evidence(evidence)
            .remediation(source.remediation())
            .correlationId(invocationId)
            .traceId(source.traceId())
            .provenance(source.provenance())
            .unknown(source.unknown())
            .durable(source.durable())
            .redaction(source.redaction())
            .metricPolicy(source.metricPolicy());
        if (document != null) {
            builder.serverId(document.resource().serverId())
                .resource(document.resource())
                .catalogGeneration(document.catalogBinding().generation());
            document.nodes().stream()
                .filter(node -> node.instanceId().canonicalText().equals(startNodeId))
                .findFirst()
                .map(GraphNode::definition)
                .ifPresent(definition -> builder.nodeId(definition.id()));
        } else if (owner != null) {
            builder.serverId(owner.serverId());
            if (graph != null && graph.getId() != null && !graph.getId().isBlank()
                && List.of("flow", "function", "command").contains(graph.getResourceType())) {
                builder.resource(new ServerResourceLocator(owner.serverId(), ContractRef.of(OwnerId.of("restudio.resync"),
                    ResourceTypeId.of(graph.getResourceType())), graph.getId()));
            }
        }
        return builder.build();
    }

    private Diagnostic diagnostic(String code, String source, Map<String, ?> evidence) {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();
        DiagnosticCodeCatalog.Definition definition = catalog.require(code);
        String identity = code + "\u0000" + source + "\u0000" + CanonicalJson.canonicalize(evidence);
        return Diagnostic.builder(code, definition.severity(), definition.phase(), definition.stage())
            .catalog(catalog)
            .messageKey(ContractRef.of(DIAGNOSTIC_OWNER, CapabilityId.of(definition.messageKey())))
            .evidence(evidence)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
            .durable(definition.durable())
            .build();
    }

    static final class WarningLimiter {
        private final int capacity;
        private final long windowMillis;
        private final int maximumWarnings;
        private final LongSupplier clock;
        private final Consumer<String> sink;
        private final LinkedHashMap<String, WarningState> warnings = new LinkedHashMap<>(16, 0.75f, true);
        private boolean initialized;
        private long windowStarted;
        private long windowEpoch;
        private int emitted;

        WarningLimiter(int capacity, long windowMillis, int maximumWarnings, LongSupplier clock, Consumer<String> sink) {
            if (capacity < 1 || windowMillis < 1L || maximumWarnings < 1) {
                throw new IllegalArgumentException("Invocation warning limits must be positive");
            }
            this.capacity = capacity;
            this.windowMillis = windowMillis;
            this.maximumWarnings = maximumWarnings;
            this.clock = Objects.requireNonNull(clock, "Invocation warning clock is required");
            this.sink = Objects.requireNonNull(sink, "Invocation warning sink is required");
        }

        synchronized boolean warn(String key, String message) {
            String normalizedKey = key == null || key.isBlank() ? "trigger" : key;
            long now = clock.getAsLong();
            if (!initialized || now - windowStarted >= windowMillis || now < windowStarted) {
                initialized = true;
                windowStarted = now;
                windowEpoch++;
                emitted = 0;
            }
            WarningState state = warnings.computeIfAbsent(normalizedKey, ignored -> new WarningState());
            while (warnings.size() > capacity) {
                warnings.remove(warnings.keySet().iterator().next());
            }
            if (state.emittedEpoch == windowEpoch || emitted >= maximumWarnings) {
                state.suppressed++;
                return false;
            }
            long coalesced = state.suppressed;
            state.suppressed = 0L;
            state.emittedEpoch = windowEpoch;
            emitted++;
            sink.accept((message == null ? "Compiled trigger invocation failed" : message)
                + (coalesced == 0L ? "" : " coalesced=" + coalesced));
            return true;
        }

        private static final class WarningState {
            private long emittedEpoch = -1L;
            private long suppressed;
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}
