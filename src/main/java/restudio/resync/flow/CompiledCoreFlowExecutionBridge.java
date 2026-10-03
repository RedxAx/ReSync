package restudio.resync.flow;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionInputMap;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledPlanAuthority;
import restudio.resync.flow.graph.CompiledPlanLease;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.GraphCompilationResult;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimePrincipal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class CompiledCoreFlowExecutionBridge implements FlowExecutionBridge {
    private static final ContentHash AUTHORITY_HASH = ContentHash.of(CanonicalJson.sha256(
        "compiled-bridge",
        Map.of("implementation", "restudio.resync.compiled-core-flow-execution-bridge", "version", 3)));
    private final Supplier<CatalogSnapshot> catalogSupplier;
    private final Supplier<RuntimeBindingManifest> runtimeManifestSupplier;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final CompiledGraphMaterializer materializer;
    private final CompiledExecutionRunner runner;
    private final CompiledPlanAuthority planAuthority;
    private final Map<CorrelationId, ExecutionObservation> observations = new ConcurrentHashMap<>();

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogSnapshot> catalogSupplier,
        Supplier<RuntimeBindingManifest> runtimeManifestSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority
    ) {
        this(catalogSupplier, runtimeManifestSupplier, registry, authority, new CompiledGraphMaterializer());
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogSnapshot> catalogSupplier,
        Supplier<RuntimeBindingManifest> runtimeManifestSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer
    ) {
        this(catalogSupplier, runtimeManifestSupplier, registry, authority, materializer, null);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogSnapshot> catalogSupplier,
        Supplier<RuntimeBindingManifest> runtimeManifestSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer,
        RuntimePrincipal defaultPrincipal
    ) {
        this(catalogSupplier, runtimeManifestSupplier, registry, authority, materializer, defaultPrincipal, null);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogSnapshot> catalogSupplier,
        Supplier<RuntimeBindingManifest> runtimeManifestSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer,
        RuntimePrincipal defaultPrincipal,
        CompiledPlanAuthority planAuthority
    ) {
        this.catalogSupplier = Objects.requireNonNull(catalogSupplier, "Compiled Catalog Supplier Is Required");
        this.runtimeManifestSupplier = Objects.requireNonNull(runtimeManifestSupplier, "Compiled Runtime Manifest Supplier Is Required");
        this.activationSupplier = null;
        this.materializer = Objects.requireNonNull(materializer, "Compiled Graph Materializer Is Required");
        this.planAuthority = planAuthority;
        this.runner = new CompiledExecutionRunner(Objects.requireNonNull(registry,
            "Runtime Binding Registry Is Required"), Objects.requireNonNull(authority,
            "Runtime Authority Is Required"), defaultPrincipal);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority
    ) {
        this(activationSupplier, registry, authority, new CompiledGraphMaterializer());
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer
    ) {
        this(activationSupplier, registry, authority, materializer, null);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer,
        RuntimePrincipal defaultPrincipal
    ) {
        this(activationSupplier, registry, authority, materializer, defaultPrincipal, null);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        CompiledGraphMaterializer materializer,
        RuntimePrincipal defaultPrincipal,
        CompiledPlanAuthority planAuthority
    ) {
        this.catalogSupplier = null;
        this.runtimeManifestSupplier = null;
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Compiled Activation Supplier Is Required");
        this.materializer = Objects.requireNonNull(materializer, "Compiled Graph Materializer Is Required");
        this.planAuthority = planAuthority;
        this.runner = new CompiledExecutionRunner(this.activationSupplier, Objects.requireNonNull(registry,
            "Runtime Binding Registry Is Required"), Objects.requireNonNull(authority,
            "Runtime Authority Is Required"), defaultPrincipal);
    }

    public CompiledCoreFlowExecutionBridge(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        RuntimePrincipal defaultPrincipal,
        CompiledPlanAuthority planAuthority
    ) {
        this.catalogSupplier = null;
        this.runtimeManifestSupplier = null;
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Compiled Activation Supplier Is Required");
        this.materializer = null;
        this.planAuthority = Objects.requireNonNull(planAuthority, "Compiled Plan Authority Is Required");
        this.runner = new CompiledExecutionRunner(this.activationSupplier, Objects.requireNonNull(registry,
            "Runtime Binding Registry Is Required"), Objects.requireNonNull(authority,
            "Runtime Authority Is Required"), defaultPrincipal);
    }

    public static ContentHash authorityHash() {
        return AUTHORITY_HASH;
    }

    public CompiledExecutionRunner.ExecutionTemplate prepare(CompiledExecutionPlan plan, NodeInstanceId startNodeId) {
        return runner.prepare(plan, startNodeId);
    }

    public CompiledExecutionRunner.FunctionExecutionHandle executeFunctionObserved(FunctionSourceDocument source,
            NodeInstanceId entry, FunctionInputMap inputs, RuntimeCancellationToken cancellation,
            CompiledRuntimeContext context, CorrelationId invocationId, long deadlineMillis) {
        Objects.requireNonNull(source, "Authoritative Function Source Is Required");
        if (planAuthority == null) {
            throw new IllegalStateException("Function Execution Requires The Resident Compiled Plan Authority");
        }
        ExecutionTarget target = new ExecutionTarget(source.graph().resource(), entry, source.graph().revision(),
            source.graph().catalogBinding());
        CompiledPlanLease lease = planAuthority.acquireRequired(target);
        try {
            CompiledExecutionRunner.ExecutionTemplate template = lease.executionTemplate().orElseThrow(() ->
                new IllegalStateException("Function Execution Requires An Admitted Resident Template"));
            CompiledExecutionRunner.FunctionExecutionHandle handle = runner.executeFunctionObserved(template,
                source.signature(), inputs, cancellation, context, invocationId, deadlineMillis);
            return new CompiledExecutionRunner.FunctionExecutionHandle(handle.result(),
                handle.physicalCompletion().whenComplete((ignored, failure) -> lease.close()));
        } catch (RuntimeException | Error failure) {
            try {
                lease.close();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    public int activeInvocationCount() {
        return runner.activeInvocationCount();
    }

    public long templatePreparationCount() {
        return runner.templatePreparationCount();
    }

    @Override
    public CompletionStage<Result> execute(Context context) {
        ExecutionObservation observation = context == null ? null : observations.remove(context.invocationId());
        try {
            CompletionStage<Result> execution = executeCore(context, observation);
            if (observation != null) {
                execution.whenComplete(observation::finish);
            }
            return execution;
        } catch (RuntimeException | Error failure) {
            if (observation != null) {
                observation.finish(null, failure);
            }
            throw failure;
        }
    }

    public ExecutionObservation observeInvocation(CorrelationId invocationId) {
        ExecutionObservation observation = new ExecutionObservation(Objects.requireNonNull(invocationId,
            "Observed Invocation Identity Is Required"));
        if (observations.putIfAbsent(invocationId, observation) != null) {
            throw new IllegalStateException("The Invocation Already Has An Observer");
        }
        return observation;
    }

    private CompletionStage<Result> executeCore(Context context, ExecutionObservation observation) {
        if (context == null) {
            return CompletableFuture.completedFuture(Result.unsupported("A compiled Core context is required", List.of(diagnostic(
                "GRAPH.NULL", null, "Compiled Core context is unavailable", Map.of()))));
        }
        CompiledRuntimeContext runtimeContext = context.compiledRuntimeContext();
        if (runtimeContext != null && !runtimeContext.supportedByCompiledCore()) {
            return CompletableFuture.completedFuture(Result.unsupported(
                runtimeContext.unsupportedReason(), List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", context.compiledGraphMetadata(),
                    runtimeContext.unsupportedReason(), Map.of(
                        "boundary", "context",
                        "contextContract", "compiled-runtime-context-v1",
                        "supportedContext", "empty",
                        "playerPresent", runtimeContext.player() != null,
                        "eventPresent", runtimeContext.event() != null,
                        "eventVariablesPresent", !runtimeContext.variables().isEmpty())))));
        }
        if (runtimeContext == null && (context.player() != null || context.event() != null
            || (context.eventVariables() != null && !context.eventVariables().isEmpty()))) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The compiled Core bridge cannot preserve legacy execution context", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", context.compiledGraphMetadata(),
                    "Player, event, and event variables require a context-aware compiled runtime binding", Map.of(
                        "boundary", "context",
                        "contextContract", "compiled-runtime-context-v1",
                        "supportedContext", "empty",
                        "playerPresent", context.player() != null,
                        "eventPresent", context.event() != null,
                        "eventVariablesPresent", context.eventVariables() != null && !context.eventVariables().isEmpty())))));
        }
        if (planAuthority != null) {
            return executeFromPlanAuthority(context, runtimeContext, observation);
        }
        CompiledGraphMetadata metadata = context.compiledGraphMetadata();
        CompiledGraphMaterializer.Result materialized = context.sourceDocument() == null
            ? materializer.materialize(context.graph(), metadata)
            : materializer.materialize(context.sourceDocument(), metadata == null ? null : metadata.functionSource());
        if (!materialized.materialized()) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The legacy graph is missing complete canonical execution metadata", materialized.diagnostics()));
        }
        CatalogSnapshot catalog;
        RuntimeBindingManifest manifest;
        try {
            if (activationSupplier != null) {
                CatalogRuntimeActivation.ActivationRecord activation = Objects.requireNonNull(activationSupplier.get(), "Active Catalog Runtime Activation Is Required");
                catalog = activation.catalog();
                manifest = activation.runtimeManifest();
            } else {
                catalog = Objects.requireNonNull(catalogSupplier.get(), "Active Catalog Snapshot Is Required");
                manifest = Objects.requireNonNull(runtimeManifestSupplier.get(), "Active Runtime Binding Manifest Is Required");
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Result.failed(
                "The active catalog or runtime binding manifest is unavailable", failure,
                List.of(diagnostic("GRAPH.CATALOG_REQUIRED", metadata, failure.getMessage(), Map.of()))));
        }
        GraphCompilationResult compilation;
        try {
            FunctionSourceDocument functionSource = metadata.functionSource();
            compilation = functionSource == null
                ? new GraphCompiler(manifest).compileResult(materialized.document(), catalog)
                : new GraphCompiler(manifest).compileResult(functionSource, catalog);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Result.failed(
                "The canonical graph could not be compiled", failure,
                List.of(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", metadata, failure.getMessage(), Map.of(
                    "catalogGeneration", catalog.generation(),
                    "bindingManifestHash", manifest.bindingManifestHash().canonicalText())))));
        }
        if (!compilation.compiled()) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The canonical graph was rejected by the active catalog", compilation.validation().diagnostics()));
        }
        NodeInstanceId startNodeId = metadata.nodeInstances().get(context.startNodeId());
        if (startNodeId == null) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The requested start node has no compiled identity", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata,
                    "The requested start node is not present in compiled metadata", Map.of(
                        "boundary", "start-node",
                        "startNodeId", context.startNodeId() == null ? "" : context.startNodeId())))));
        }
        try {
            CompiledExecutionRunner.ExecutionHandle handle = runner.executeObserved(runner.prepare(compilation.plan(), startNodeId),
                Map.of(), new RuntimeCancellationToken(), runtimeContext, context.invocationId(), context.requestedDeadlineMillis());
            if (observation != null) {
                observation.attach(handle);
            }
            return handle.result()
                .thenApply(CompiledCoreFlowExecutionBridge::executionResult);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Result.failed(
                "Compiled Core execution could not start", failure,
                List.of(diagnostic("GRAPH.RUNTIME_BINDING_MISSING", metadata, failure.getMessage(), Map.of()))));
        }
    }

    private CompletionStage<Result> executeFromPlanAuthority(Context context, CompiledRuntimeContext runtimeContext,
                                                           ExecutionObservation observation) {
        CompiledGraphMetadata metadata = context.compiledGraphMetadata();
        if (metadata == null) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "Complete compiled Core graph metadata is required", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", null,
                    "The compiled plan authority requires a typed graph locator and catalog binding", Map.of(
                        "boundary", "compiled-plan-authority")))));
        }
        if (!metadata.resource().id().equals(context.graph().getId())
            || !metadata.resource().resourceType().value().equals(context.graph().getResourceType())) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The compiled graph metadata does not match the typed resource", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata,
                    "The compiled graph metadata and legacy graph envelope identify different resources", Map.of(
                        "boundary", "compiled-plan-authority",
                        "graphId", context.graph().getId() == null ? "" : context.graph().getId(),
                        "graphType", context.graph().getResourceType() == null ? "" : context.graph().getResourceType(),
                        "resource", metadata.resource().canonicalText())))));
        }
        NodeInstanceId startNodeId = metadata.nodeInstances().get(context.startNodeId());
        if (startNodeId == null) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The requested start node has no compiled identity", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata,
                    "The requested start node is not present in compiled metadata", Map.of(
                        "boundary", "start-node",
                        "startNodeId", context.startNodeId() == null ? "" : context.startNodeId())))));
        }
        long revision = context.graph().getResourceRevision();
        if (revision < 1L) {
            return CompletableFuture.completedFuture(Result.unsupported(
                "The compiled graph has no persisted positive revision", List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata,
                    "The compiled plan authority requires a positive typed resource revision", Map.of(
                        "boundary", "compiled-plan-authority",
                        "revision", revision)))));
        }
        ExecutionTarget target;
        try {
            target = new ExecutionTarget(metadata.resource(), startNodeId, revision, metadata.catalogBinding());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Result.failed(
                "The compiled execution target is invalid", failure, List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata, failure.getMessage(), Map.of(
                        "boundary", "compiled-plan-authority")))));
        }
        CompiledPlanLease planLease;
        try {
            planLease = planAuthority.acquireRequired(target);
        } catch (RuntimeException | Error failure) {
            if (observation != null) {
                observation.releaseFailed();
            }
            return CompletableFuture.completedFuture(Result.failed(
                "The immutable compiled plan could not be admitted", failure, List.of(diagnostic(
                    "GRAPH.OPAQUE_UNAVAILABLE", metadata, failure.getMessage(), Map.of(
                        "boundary", "compiled-plan-authority",
                        "resource", target.resource().canonicalText(),
                        "revision", target.expectedRevision(),
                        "catalogBinding", target.expectedBinding().canonicalText())))));
        }
        CompletionStage<CompiledExecutionRunner.ExecutionResult> execution;
        try {
            CompiledExecutionPlan plan = planLease.plan();
            CompiledExecutionRunner.ExecutionTemplate template = planLease.executionTemplate()
                .orElseGet(() -> runner.prepare(plan, startNodeId));
            CompiledExecutionRunner.ExecutionHandle handle = runner.executeObserved(template, Map.of(), new RuntimeCancellationToken(),
                runtimeContext, context.invocationId(), context.requestedDeadlineMillis());
            handle = new CompiledExecutionRunner.ExecutionHandle(handle.result(),
                handle.physicalCompletion().whenComplete((ignored, failure) -> planLease.close()));
            if (observation != null) {
                observation.attach(handle);
            }
            execution = handle.result();
        } catch (RuntimeException | Error failure) {
            try {
                planLease.close();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
                if (observation != null) {
                    observation.releaseFailed();
                }
            }
            return CompletableFuture.completedFuture(Result.failed(
                "The immutable compiled plan could not start", failure, List.of(diagnostic(
                    "GRAPH.RUNTIME_BINDING_MISSING", metadata, failure.getMessage(), Map.of(
                        "boundary", "compiled-plan-authority")))));
        }
        if (execution == null) {
            try {
                planLease.close();
            } catch (RuntimeException | Error closeFailure) {
                if (observation != null) {
                    observation.releaseFailed();
                }
                return CompletableFuture.completedFuture(Result.failed(
                    "The immutable compiled plan lease could not be released", closeFailure,
                    List.of(diagnostic("GRAPH.RUNTIME_BINDING_MISSING", metadata,
                        closeFailure.getMessage(), Map.of("boundary", "compiled-plan-authority")))));
            }
            return CompletableFuture.completedFuture(Result.failed(
                "The immutable compiled plan returned no execution", null, List.of(diagnostic(
                    "GRAPH.RUNTIME_BINDING_MISSING", metadata,
                    "The compiled execution runner returned no stage", Map.of(
                        "boundary", "compiled-plan-authority")))));
        }
        return execution.handle((result, failure) -> {
            Throwable executionFailure = failure;
            if (executionFailure != null) {
                return Result.failed("Compiled Core execution failed", executionFailure, List.of(diagnostic(
                    "GRAPH.RUNTIME_BINDING_MISSING", metadata, executionFailure.getMessage(), Map.of(
                        "boundary", "compiled-plan-authority"))));
            }
            return executionResult(result);
        });
    }

    public final class ExecutionObservation implements AutoCloseable {
        private final CorrelationId invocationId;
        private final CompletableFuture<ObservedExecution> completion = new CompletableFuture<>();
        private CompiledExecutionRunner.ExecutionHandle handle;
        private boolean releaseConfirmed = true;

        private ExecutionObservation(CorrelationId invocationId) {
            this.invocationId = invocationId;
        }

        private synchronized void attach(CompiledExecutionRunner.ExecutionHandle handle) {
            this.handle = Objects.requireNonNull(handle, "Observed Execution Is Required");
        }

        private synchronized void releaseFailed() {
            releaseConfirmed = false;
        }

        private void finish(Result boundary, Throwable failure) {
            Throwable boundaryFailure = failure == null && boundary != null ? boundary.failure() : failure;
            CompiledExecutionRunner.ExecutionHandle execution;
            boolean physicalComplete;
            synchronized (this) {
                execution = handle;
                physicalComplete = releaseConfirmed;
            }
            if (execution == null) {
                completion.complete(new ObservedExecution(invocationId, null, boundary, boundaryFailure, physicalComplete));
                return;
            }
            execution.result().handle((result, executionFailure) -> new ObservedExecution(invocationId, result, boundary,
                boundaryFailure == null ? executionFailure : boundaryFailure)).thenCombine(
                    execution.physicalCompletion().handle((ignored, physicalFailure) -> physicalFailure),
                    (result, physicalFailure) -> physicalFailure == null ? result
                        : new ObservedExecution(invocationId, result.execution(), result.boundary(), physicalFailure, false))
                .whenComplete((result, observationFailure) -> {
                    if (observationFailure == null) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(observationFailure);
                    }
                });
        }

        public CompletionStage<ObservedExecution> completion() {
            return completion.minimalCompletionStage();
        }

        public void finishWithoutExecution(Throwable failure) {
            if (observations.remove(invocationId, this)) {
                finish(null, failure);
            }
        }

        @Override
        public void close() {
            finishWithoutExecution(new IllegalStateException("The Observed Invocation Did Not Reach Compiled Execution"));
        }
    }

    public record ObservedExecution(CorrelationId invocationId, CompiledExecutionRunner.ExecutionResult execution,
                                    Result boundary, Throwable failure, boolean physicalComplete) {
        public ObservedExecution(CorrelationId invocationId, CompiledExecutionRunner.ExecutionResult execution,
                Result boundary, Throwable failure) {
            this(invocationId, execution, boundary, failure, true);
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("invocationId", invocationId.canonicalText());
            value.put("physicalComplete", physicalComplete);
            if (execution != null) {
                value.put("status", execution.status().name().toLowerCase(Locale.ROOT));
                Map<String, Object> nodes = new LinkedHashMap<>();
                execution.nodeResults().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> nodes.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()));
                value.put("nodeResults", Map.copyOf(nodes));
                value.put("outputs", execution.outputs().entrySet().stream().map(entry -> {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("nodeId", entry.getKey().nodeId().canonicalText());
                    output.put("pinId", entry.getKey().pinId().canonicalText());
                    if (entry.getKey().elementId() != null) {
                        output.put("elementId", entry.getKey().elementId().canonicalText());
                    }
                    if (entry.getKey().branchId() != null) {
                        output.put("branchId", entry.getKey().branchId().canonicalText());
                    }
                    output.put("value", entry.getValue().canonicalValue());
                    return Map.copyOf(output);
                }).toList());
            } else {
                value.put("status", "rejected");
            }
            if (boundary != null) {
                value.put("boundary", boundary.status().name().toLowerCase(Locale.ROOT));
                value.put("diagnostics", boundary.diagnostics().stream().map(Diagnostic::toMap).toList());
            }
            if (failure != null) {
                value.put("failure", Map.of("type", failure.getClass().getName(), "message",
                    failure.getMessage() == null ? "Execution Failed" : failure.getMessage()));
            }
            return Map.copyOf(value);
        }
    }

    private static Result executionResult(CompiledExecutionRunner.ExecutionResult result) {
        List<Diagnostic> diagnostics = result.failure() == null ? List.of() : List.of(result.failure().diagnostic());
        if (result.status() == CompiledExecutionRunner.Status.CANCELLED) {
            return Result.cancelled("Compiled Core execution was cancelled", null, diagnostics);
        }
        if (result.status() == CompiledExecutionRunner.Status.FAILURE && result.failure() != null
            && "RUNTIME.EXECUTION_TIMEOUT".equals(result.failure().diagnostic().code())) {
            return Result.timeout("Compiled Core execution timed out", null, diagnostics);
        }
        return result.status() == CompiledExecutionRunner.Status.SUCCESS
            ? Result.executed()
            : Result.failed("Compiled Core execution did not complete successfully", null, diagnostics);
    }

    private static Diagnostic diagnostic(String code, CompiledGraphMetadata metadata, String reason, Map<String, ?> evidence) {
        String normalizedReason = reason == null || reason.isBlank() ? "Compiled Core execution boundary rejected the graph" : reason;
        String identity = code + "\u0000" + (metadata == null ? "" : metadata.snapshotId().canonicalText()) + "\u0000" + normalizedReason;
        var builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, phase(code), "graph")
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of(messageKey(code))))
            .evidence(evidence)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        if (metadata != null) {
            builder.resource(metadata.resource()).catalogGeneration(metadata.catalogBinding().generation());
        }
        return builder.build();
    }

    private static DiagnosticPhase phase(String code) {
        return switch (code) {
            case "GRAPH.CATALOG_REQUIRED" -> DiagnosticPhase.CAPABILITY;
            case "GRAPH.RUNTIME_BINDING_MISSING" -> DiagnosticPhase.CAPABILITY;
            default -> DiagnosticPhase.CAPABILITY;
        };
    }

    private static String messageKey(String code) {
        return switch (code) {
            case "GRAPH.CATALOG_REQUIRED" -> "graph-catalog-required";
            case "GRAPH.RUNTIME_BINDING_MISSING" -> "graph-runtime-binding-missing";
            case "GRAPH.OPAQUE_UNAVAILABLE" -> "graph-opaque-unavailable";
            case "GRAPH.NULL" -> "graph-null";
            default -> throw new IllegalArgumentException("Unsupported compiled bridge diagnostic code: " + code);
        };
    }
}
