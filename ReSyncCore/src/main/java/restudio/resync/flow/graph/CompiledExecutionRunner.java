package restudio.resync.flow.graph;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeInvocation;
import restudio.resync.flow.runtime.RuntimeLeaseInput;
import restudio.resync.flow.runtime.RuntimePlanLease;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeFailure;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CompiledExecutionRunner {
    private static final TypeExpr EXECUTION_TYPE = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr NUMBER_TYPE = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr BOOLEAN_TYPE = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final int ROOT_OPERATION_BUDGET = 10_000;

    private final RuntimeBindingRegistry registry;
    private final RuntimeAuthority authority;
    private final RuntimePrincipal defaultPrincipal;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final AtomicInteger activeInvocations = new AtomicInteger();
    private final AtomicLong templatePreparations = new AtomicLong();

    public CompiledExecutionRunner(RuntimeBindingRegistry registry, RuntimeAuthority authority) {
        this(null, registry, authority, null);
    }

    public CompiledExecutionRunner(RuntimeBindingRegistry registry, RuntimeAuthority authority,
                                   RuntimePrincipal defaultPrincipal) {
        this(null, registry, authority, defaultPrincipal);
    }

    public CompiledExecutionRunner(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority
    ) {
        this(activationSupplier, registry, authority, null);
    }

    public CompiledExecutionRunner(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        RuntimeBindingRegistry registry,
        RuntimeAuthority authority,
        RuntimePrincipal defaultPrincipal
    ) {
        this.activationSupplier = activationSupplier;
        this.registry = Objects.requireNonNull(registry, "Runtime Binding Registry Is Required");
        this.authority = Objects.requireNonNull(authority, "Runtime Authority Is Required");
        this.defaultPrincipal = defaultPrincipal;
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan) {
        return execute(plan, Map.of(), new RuntimeCancellationToken());
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, NodeInstanceId startNodeId) {
        return execute(plan, startNodeId, Map.of(), new RuntimeCancellationToken());
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken) {
        return execute(plan, null, injectedInputs, cancellationToken);
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, NodeInstanceId startNodeId,
                                                    Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken) {
        return execute(plan, startNodeId, injectedInputs, cancellationToken, null);
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, NodeInstanceId startNodeId,
                                                    Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken,
                                                    CompiledRuntimeContext runtimeContext) {
        return execute(plan, startNodeId, injectedInputs, cancellationToken, runtimeContext, CorrelationId.random());
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, NodeInstanceId startNodeId,
                                                    Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken,
                                                    CompiledRuntimeContext runtimeContext,
                                                    CorrelationId invocationId) {
        return execute(plan, startNodeId, injectedInputs, cancellationToken, runtimeContext, invocationId,
            RuntimeExecutionContext.NO_DEADLINE);
    }

    public CompletionStage<ExecutionResult> execute(CompiledExecutionPlan plan, NodeInstanceId startNodeId,
                                                    Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken,
                                                    CompiledRuntimeContext runtimeContext,
                                                    CorrelationId invocationId,
                                                    long requestedDeadlineMillis) {
        try {
            return execute(prepare(plan, startNodeId), injectedInputs, cancellationToken, runtimeContext, invocationId,
                requestedDeadlineMillis);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public ExecutionTemplate prepare(CompiledExecutionPlan plan, NodeInstanceId startNodeId) {
        Objects.requireNonNull(plan, "Compiled Execution Plan Is Required");
        CatalogRuntimeActivation.ActivationRecord activation = activeActivation();
        RuntimeRegistrySnapshot runtimeSnapshot = activation == null ? null : activation.runtime();
        validateActivationPlan(plan, activation);
        List<CompiledExecutionStep> ordered = topologicalOrder(plan);
        List<CompiledExecutionStep> scoped = executionScope(plan, ordered, startNodeId);
        Map<GraphEndpoint, List<RoutedConnection>> routes = conversionBindings(plan, scoped, activation, runtimeSnapshot);
        List<RuntimeLeaseInput.BindingRequirement> requirements = requirements(plan, scoped, runtimeSnapshot, routes);
        Map<NodeInstanceId, TemplateBinding> templates = templateBindings(scoped, requirements);
        templatePreparations.incrementAndGet();
        LinkedHashMap<NodeInstanceId, CompiledExecutionStep> indexed = new LinkedHashMap<>();
        scoped.forEach(step -> indexed.put(step.nodeId(), step));
        Map<NodeInstanceId, CompiledExecutionStep> stepsByNode = Map.copyOf(indexed);
        LoopOwnership loopOwnership = LoopOwnership.of(scoped, stepsByNode.keySet());
        List<CompiledExecutionStep> rootSteps = scoped.stream()
            .filter(step -> !loopOwnership.bodyOwned(step.nodeId())).toList();
        return new ExecutionTemplate(plan, startNodeId, scoped, routes, templates, executionInputs(scoped),
            invocationStarts(plan, scoped, startNodeId), requirements, runtimeSnapshot, stepsByNode, loopOwnership,
            rootSteps);
    }

    public CompletionStage<ExecutionResult> execute(ExecutionTemplate template,
                                                    Map<GraphEndpoint, TypedValue> injectedInputs,
                                                    RuntimeCancellationToken cancellationToken,
                                                    CompiledRuntimeContext runtimeContext,
                                                    CorrelationId invocationId,
                                                    long requestedDeadlineMillis) {
        Objects.requireNonNull(template, "Compiled Execution Template Is Required");
        Objects.requireNonNull(injectedInputs, "Injected Inputs Are Required");
        Objects.requireNonNull(cancellationToken, "Cancellation Token Is Required");
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        if (requestedDeadlineMillis < 0) {
            throw new IllegalArgumentException("Requested Deadline Cannot Be Negative");
        }
        CompiledExecutionPlan plan = template.plan;
        RuntimeRegistrySnapshot runtimeSnapshot = template.runtimeSnapshot;
        RuntimePlanLease lease = null;
        try {
            long computedDeadline = requestedDeadlineMillis == RuntimeExecutionContext.NO_DEADLINE
                ? planDeadline(template.scoped, runtimeSnapshot, cancellationToken, template.routes)
                : requestedDeadlineMillis;
            RuntimePrincipal principal = runtimeContext == null || runtimeContext.principal() == null
                ? defaultPrincipal : runtimeContext.principal();
            RuntimeLeaseInput leaseInput = RuntimeLeaseInput.plan(
                template.requirements, plan.planHash(), authority, principal,
                plan.catalogBinding().generation(), plan.catalogBinding().catalogChecksum(), null, invocationId, computedDeadline);
            lease = runtimeSnapshot == null
                ? registry.acquire(leaseInput)
                : registry.acquire(leaseInput, runtimeSnapshot);
            activeInvocations.incrementAndGet();
            Map<NodeInstanceId, RuntimeResult> results = new LinkedHashMap<>();
            Map<NodeInstanceId, Map<PinId, TypedValue>> routedInputs = new HashMap<>();
            Map<NodeInstanceId, Set<PinId>> activatedExecutionInputs = new HashMap<>();
            injectedInputs.forEach((endpoint, value) -> routedInputs.computeIfAbsent(endpoint.nodeId(), ignored -> new LinkedHashMap<>())
                .put(endpoint.pinId(), Objects.requireNonNull(value, "Injected Input Value Is Required")));
            Map<GraphEndpoint, TypedValue> outputs = new LinkedHashMap<>();
            RuntimePlanLease executionLease = lease;
            ExecutionFrame rootFrame = new ExecutionFrame(results, routedInputs, activatedExecutionInputs, outputs);
            ExecutionMachine machine = new ExecutionMachine(template, executionLease, cancellationToken, runtimeContext,
                invocationId, computedDeadline);
            CompletableFuture<ExecutionResult> execution = machine.execute(rootFrame);
            execution.whenComplete((ignored, failure) -> {
                try {
                    executionLease.close();
                } finally {
                    activeInvocations.decrementAndGet();
                }
            });
            return execution;
        } catch (Throwable failure) {
            if (lease != null) {
                lease.close();
                activeInvocations.decrementAndGet();
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    public int activeInvocationCount() {
        return activeInvocations.get();
    }

    public long templatePreparationCount() {
        return templatePreparations.get();
    }

    private static Map<NodeInstanceId, TemplateBinding> templateBindings(List<CompiledExecutionStep> steps,
                                                                          List<RuntimeLeaseInput.BindingRequirement> requirements) {
        Map<RuntimeBindingKey, Set<PinId>> declared = new HashMap<>();
        requirements.forEach(requirement -> declared.put(requirement.binding(), Set.copyOf(requirement.inputPins())));
        Map<NodeInstanceId, TemplateBinding> bindings = new LinkedHashMap<>();
        for (CompiledExecutionStep step : steps) {
            RuntimeBindingKey key = new RuntimeBindingKey(step.handler().capability(), step.handler().operation());
            Set<PinId> staticPins = declared.get(key);
            if (staticPins == null) {
                throw new IllegalStateException("Compiled Runtime Input Pins Are Missing");
            }
            Set<PinId> dynamic = new LinkedHashSet<>(step.inputBindings().keySet());
            dynamic.removeAll(staticPins);
            Map<PinId, StringTemplatePins.Template> templates = new LinkedHashMap<>();
            for (PinId pin : staticPins) {
                TypedValue value = step.inputBindings().get(pin);
                if (value != null && StringTemplatePins.STRING.equals(value.type())
                    && value.state() == TypedValue.State.VALUE && value.value() instanceof String text
                    && (text.indexOf('{') >= 0 || text.indexOf('}') >= 0)) {
                    templates.put(pin, StringTemplatePins.parse(text));
                }
            }
            if (!dynamic.isEmpty() || !templates.isEmpty()) {
                bindings.put(step.nodeId(), new TemplateBinding(dynamic,
                    staticPins.stream().map(PinId::canonicalText).collect(Collectors.toSet()), templates));
            }
        }
        return Map.copyOf(bindings);
    }

    private record TemplateBinding(Set<PinId> dynamic, Set<String> reserved,
                                   Map<PinId, StringTemplatePins.Template> templates) {
        private TemplateBinding {
            dynamic = Set.copyOf(dynamic);
            reserved = Set.copyOf(reserved);
            templates = Map.copyOf(templates);
        }

        private void render(Map<PinId, TypedValue> inputs) {
            Map<String, Object> values = new HashMap<>();
            for (PinId pin : dynamic) {
                TypedValue value = inputs.remove(pin);
                if (value != null && value.hasValue()) {
                    values.put(pin.canonicalText(), value.value());
                }
            }
            templates.forEach((pin, template) -> {
                TypedValue original = inputs.get(pin);
                if (original != null && original.state() == TypedValue.State.VALUE && original.value() instanceof String) {
                    inputs.put(pin, new TypedValue(original.type(), TypedValue.State.VALUE, original.variantId(),
                        template.render(values, reserved), null, original.unknown()));
                }
            });
        }
    }

    public static final class ExecutionTemplate {
        private final CompiledExecutionPlan plan;
        private final NodeInstanceId startNodeId;
        private final List<CompiledExecutionStep> scoped;
        private final Map<GraphEndpoint, List<RoutedConnection>> routes;
        private final Map<NodeInstanceId, TemplateBinding> templates;
        private final Map<NodeInstanceId, Set<PinId>> executionInputs;
        private final Set<NodeInstanceId> invocationStarts;
        private final List<RuntimeLeaseInput.BindingRequirement> requirements;
        private final RuntimeRegistrySnapshot runtimeSnapshot;
        private final Map<NodeInstanceId, CompiledExecutionStep> stepsByNode;
        private final LoopOwnership loopOwnership;
        private final List<CompiledExecutionStep> rootSteps;

        private ExecutionTemplate(CompiledExecutionPlan plan, NodeInstanceId startNodeId,
                                  List<CompiledExecutionStep> scoped,
                                  Map<GraphEndpoint, List<RoutedConnection>> routes,
                                  Map<NodeInstanceId, TemplateBinding> templates,
                                  Map<NodeInstanceId, Set<PinId>> executionInputs,
                                  Set<NodeInstanceId> invocationStarts,
                                  List<RuntimeLeaseInput.BindingRequirement> requirements,
                                  RuntimeRegistrySnapshot runtimeSnapshot,
                                  Map<NodeInstanceId, CompiledExecutionStep> stepsByNode,
                                  LoopOwnership loopOwnership,
                                  List<CompiledExecutionStep> rootSteps) {
            this.plan = plan;
            this.startNodeId = startNodeId;
            this.scoped = scoped;
            this.routes = routes;
            this.templates = templates;
            this.executionInputs = executionInputs;
            this.invocationStarts = invocationStarts;
            this.requirements = requirements;
            this.runtimeSnapshot = runtimeSnapshot;
            this.stepsByNode = stepsByNode;
            this.loopOwnership = loopOwnership;
            this.rootSteps = rootSteps;
        }

        public CompiledExecutionPlan plan() {
            return plan;
        }

        public NodeInstanceId startNodeId() {
            return startNodeId;
        }
    }

    private long planDeadline(List<CompiledExecutionStep> steps, RuntimeRegistrySnapshot runtimeSnapshot,
                              RuntimeCancellationToken cancellationToken, Map<GraphEndpoint, List<RoutedConnection>> routes) {
        long deadline = cancellationToken.deadlineMillis();
        long now = System.currentTimeMillis();
        for (CompiledExecutionStep step : steps) {
            RuntimeBindingKey key = new RuntimeBindingKey(step.handler().capability(), step.handler().operation());
            RuntimeBinding binding = runtimeSnapshot == null
                ? registry.resolve(key)
                : runtimeSnapshot.binding(key).orElseThrow(() -> new IllegalStateException(
                    "Compiled Plan Runtime Binding Is Missing From The Active Activation: " + key.canonical()));
            deadline = Math.min(deadline, binding.descriptor().semantics().executionDeadlineMillis(now));
        }
        for (List<RoutedConnection> outgoing : routes.values()) {
            for (RoutedConnection route : outgoing) {
                for (ConversionBinding conversion : route.conversions()) {
                    deadline = Math.min(deadline, conversion.binding().descriptor().semantics().executionDeadlineMillis(now));
                }
            }
        }
        return deadline;
    }

    private static Map<NodeInstanceId, Set<PinId>> executionInputs(List<CompiledExecutionStep> steps) {
        Map<NodeInstanceId, Set<PinId>> inputs = new HashMap<>();
        for (CompiledExecutionStep step : steps) {
            Set<PinId> flowInputs = step.inputBindings().entrySet().stream()
                .filter(entry -> EXECUTION_TYPE.equals(entry.getValue().type()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!flowInputs.isEmpty()) {
                inputs.put(step.nodeId(), Set.copyOf(flowInputs));
            }
        }
        return Map.copyOf(inputs);
    }

    private static Set<NodeInstanceId> invocationStarts(CompiledExecutionPlan plan, List<CompiledExecutionStep> steps,
                                                        NodeInstanceId startNodeId) {
        if (startNodeId != null) {
            return Set.of(startNodeId);
        }
        Set<NodeInstanceId> starts = steps.stream().map(CompiledExecutionStep::nodeId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        plan.connections().forEach(connection -> starts.remove(connection.target().nodeId()));
        return Set.copyOf(starts);
    }

    private static Map<PinId, TypedValue> outputValues(CompiledExecutionStep step, RuntimeResult result) {
        if (!result.outputs().isEmpty()) {
            return result.outputs();
        }
        if (result.value() != null && step.outputBindings().size() == 1) {
            return Map.of(step.outputBindings().keySet().iterator().next(), result.value());
        }
        return Map.of();
    }

    private final class ExecutionMachine {
        private final CompiledExecutionPlan plan;
        private final RuntimePlanLease lease;
        private final RuntimeCancellationToken cancellationToken;
        private final CompiledRuntimeContext runtimeContext;
        private final CorrelationId invocationId;
        private final long deadlineMillis;
        private final Map<GraphEndpoint, List<RoutedConnection>> routes;
        private final Map<NodeInstanceId, TemplateBinding> templateBindings;
        private final Map<NodeInstanceId, Set<PinId>> executionInputs;
        private final Set<NodeInstanceId> invocationStarts;
        private final Map<NodeInstanceId, CompiledExecutionStep> stepsByNode;
        private final LoopOwnership loopOwnership;
        private final List<CompiledExecutionStep> rootSteps;
        private final ExecutionBudget budget = new ExecutionBudget(ROOT_OPERATION_BUDGET);
        private final Trampoline trampoline = new Trampoline();
        private final CompletableFuture<ExecutionResult> completion = new CompletableFuture<>();

        private ExecutionMachine(ExecutionTemplate template, RuntimePlanLease lease,
                                 RuntimeCancellationToken cancellationToken, CompiledRuntimeContext runtimeContext,
                                 CorrelationId invocationId, long deadlineMillis) {
            this.plan = template.plan;
            this.lease = lease;
            this.cancellationToken = cancellationToken;
            this.runtimeContext = runtimeContext;
            this.invocationId = invocationId;
            this.deadlineMillis = deadlineMillis;
            this.routes = template.routes;
            this.templateBindings = template.templates;
            this.executionInputs = template.executionInputs;
            this.invocationStarts = template.invocationStarts;
            this.stepsByNode = template.stepsByNode;
            this.loopOwnership = template.loopOwnership;
            this.rootSteps = template.rootSteps;
        }

        private CompletableFuture<ExecutionResult> execute(ExecutionFrame frame) {
            schedule(() -> runSteps(rootSteps, 0, frame, FramePath.root(), invocationStarts, outcome -> {
                if (outcome.successful()) {
                    completion.complete(ExecutionResult.success(frame.results, frame.outputs));
                } else {
                    completion.complete(ExecutionResult.failure(frame.results, frame.outputs, outcome.failure, outcome.status));
                }
            }));
            return completion;
        }

        private void runSteps(List<CompiledExecutionStep> steps, int startIndex, ExecutionFrame frame, FramePath path,
                              Set<NodeInstanceId> starts, Consumer<FlowOutcome> finished) {
            int index = startIndex;
            while (index < steps.size() && !active(steps.get(index), frame, starts)) {
                index++;
            }
            if (index >= steps.size()) {
                finished.accept(FlowOutcome.success());
                return;
            }
            CompiledExecutionStep step = steps.get(index);
            FlowOutcome boundary = boundaryFailure(step, "Runtime Execution Stopped Before Step Admission");
            if (boundary != null) {
                finished.accept(boundary);
                return;
            }
            if (!budget.admit()) {
                finished.accept(failureOutcome(step, "RUNTIME.INVALID_INVOCATION",
                    "Compiled Execution Operation Budget Was Exhausted", RuntimeResult.Status.FAILURE));
                return;
            }
            int nextIndex = index + 1;
            invoke(step, frame, path, outcome -> {
                if (!outcome.successful()) {
                    finished.accept(outcome);
                    return;
                }
                schedule(() -> runSteps(steps, nextIndex, frame, path, starts, finished));
            });
        }

        private boolean active(CompiledExecutionStep step, ExecutionFrame frame, Set<NodeInstanceId> starts) {
            Set<PinId> inputs = executionInputs.getOrDefault(step.nodeId(), Set.of());
            return starts.contains(step.nodeId()) || inputs.isEmpty()
                || inputs.stream().anyMatch(frame.activatedExecutionInputs.getOrDefault(step.nodeId(), Set.of())::contains);
        }

        private void invoke(CompiledExecutionStep step, ExecutionFrame frame, FramePath path, Consumer<FlowOutcome> finished) {
            Map<PinId, TypedValue> inputs = new LinkedHashMap<>(step.inputBindings());
            inputs.putAll(frame.routedInputs.getOrDefault(step.nodeId(), Map.of()));
            TemplateBinding binding = templateBindings.get(step.nodeId());
            if (binding != null) {
                binding.render(inputs);
            }
            RuntimeBindingKey key = new RuntimeBindingKey(step.handler().capability(), step.handler().operation());
            RuntimeInvocation invocation = new RuntimeInvocation(key, inputs, childId(invocationId, step, path), cancellationToken)
                .withRuntimeContext(runtimeContext)
                .withInvocationId(invocationId);
            CompletionStage<RuntimeResult> resultStage = lease.execute(invocation);
            resultStage.whenComplete((result, failure) -> schedule(() -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                    return;
                }
                frame.results.put(step.nodeId(), result);
                if (!result.successful()) {
                    finished.accept(FlowOutcome.from(result));
                    return;
                }
                if (step.loopControl() != null) {
                    LoopSequence sequence = loopSequence(step, inputs);
                    if (sequence == null) {
                        finished.accept(failureOutcome(step, "RUNTIME.INVALID_INVOCATION",
                            "Compiled Loop Source Input Is Invalid", RuntimeResult.Status.FAILURE));
                        return;
                    }
                    runLoop(step, sequence, 0, frame, path, finished);
                    return;
                }
                routeOutputs(step, outputValues(step, result), result.branch(), frame, path, finished);
            }));
        }

        private LoopSequence loopSequence(CompiledExecutionStep step, Map<PinId, TypedValue> inputs) {
            CompiledExecutionStep.LoopControl control = step.loopControl();
            TypedValue source = inputs.get(control.sourceInput());
            if (source == null || source.state() != TypedValue.State.VALUE || !control.sourceType().equals(source.type())) {
                return null;
            }
            if (control.kind() == CompiledExecutionStep.LoopControl.Kind.COUNT) {
                BigInteger count = integral(source.value());
                return count == null || count.signum() < 0 ? null : LoopSequence.count(count);
            }
            if (!(source.value() instanceof List<?> values)) {
                return null;
            }
            return LoopSequence.elements(Collections.unmodifiableList(new ArrayList<>(values)));
        }

        private void runLoop(CompiledExecutionStep step, LoopSequence sequence, int ordinal, ExecutionFrame frame,
                             FramePath parentPath, Consumer<FlowOutcome> finished) {
            if (!sequence.hasNext(ordinal)) {
                completeLoop(step, frame, parentPath, finished);
                return;
            }
            FlowOutcome boundary = boundaryFailure(step, "Runtime Execution Stopped Before Loop Iteration Admission");
            if (boundary != null) {
                finished.accept(boundary);
                return;
            }
            if (!budget.admit()) {
                finished.accept(failureOutcome(step, "RUNTIME.INVALID_INVOCATION",
                    "Compiled Execution Operation Budget Was Exhausted", RuntimeResult.Status.FAILURE));
                return;
            }
            CompiledExecutionStep.LoopControl control = step.loopControl();
            ExecutionFrame iteration = ExecutionFrame.iteration(frame);
            FramePath iterationPath = parentPath.child(step.stepId(), ordinal);
            ArrayList<EmittedValue> dynamic = new ArrayList<>();
            dynamic.add(new EmittedValue(control.indexOutput(), TypedValue.value(NUMBER_TYPE, BigDecimal.valueOf(ordinal))));
            if (control.kind() == CompiledExecutionStep.LoopControl.Kind.FOR_EACH) {
                Object element = sequence.element(ordinal);
                TypedValue elementValue = element == null
                    ? TypedValue.nullValue(control.elementType()) : TypedValue.value(control.elementType(), element);
                dynamic.add(new EmittedValue(control.elementOutput(), elementValue));
            }
            dynamic.add(new EmittedValue(control.bodyOutput(), TypedValue.value(EXECUTION_TYPE, true)));
            routeSynthetic(step, dynamic, 0, iteration, iterationPath, routed -> {
                if (!routed.successful()) {
                    mergeFailedIteration(frame, iteration);
                    finished.accept(routed);
                    return;
                }
                List<CompiledExecutionStep> body = control.bodySteps().stream().map(nodeId -> {
                    CompiledExecutionStep bodyStep = stepsByNode.get(nodeId);
                    if (bodyStep == null) {
                        throw new IllegalStateException("Compiled Loop Body Step Is Missing: " + nodeId.canonicalText());
                    }
                    return bodyStep;
                }).toList();
                schedule(() -> runSteps(body, 0, iteration, iterationPath, Set.of(), bodyOutcome -> {
                    if (!bodyOutcome.successful()) {
                        mergeFailedIteration(frame, iteration);
                        finished.accept(bodyOutcome);
                        return;
                    }
                    mergeSuccessfulIteration(step, frame, iteration);
                    schedule(() -> runLoop(step, sequence, ordinal + 1, frame, parentPath, finished));
                }));
            });
        }

        private void completeLoop(CompiledExecutionStep step, ExecutionFrame frame, FramePath path,
                                  Consumer<FlowOutcome> finished) {
            FlowOutcome boundary = boundaryFailure(step, "Runtime Execution Stopped Before Loop Completion Admission");
            if (boundary != null) {
                finished.accept(boundary);
                return;
            }
            if (!budget.canAdmit()) {
                finished.accept(failureOutcome(step, "RUNTIME.INVALID_INVOCATION",
                    "Compiled Execution Operation Budget Was Exhausted Before Done Admission", RuntimeResult.Status.FAILURE));
                return;
            }
            CompiledExecutionStep.LoopControl control = step.loopControl();
            routeSynthetic(step, List.of(new EmittedValue(control.completedOutput(), TypedValue.value(BOOLEAN_TYPE, true))),
                0, frame, path, completed -> {
                    if (!completed.successful()) {
                        finished.accept(completed);
                        return;
                    }
                    FlowOutcome afterCompleted = boundaryFailure(step, "Runtime Execution Stopped Before Done Admission");
                    if (afterCompleted != null) {
                        finished.accept(afterCompleted);
                        return;
                    }
                    if (!budget.canAdmit()) {
                        finished.accept(failureOutcome(step, "RUNTIME.INVALID_INVOCATION",
                            "Compiled Execution Operation Budget Was Exhausted Before Done Admission", RuntimeResult.Status.FAILURE));
                        return;
                    }
                    routeSynthetic(step, List.of(new EmittedValue(control.doneOutput(), TypedValue.value(EXECUTION_TYPE, true))),
                        0, frame, path, finished);
                });
        }

        private void routeOutputs(CompiledExecutionStep step, Map<PinId, TypedValue> values, String branch,
                                  ExecutionFrame frame, FramePath path, Consumer<FlowOutcome> finished) {
            List<EmittedValue> ordered = values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new EmittedValue(entry.getKey(), entry.getValue())).toList();
            routeValues(step, ordered, 0, branch, frame, path, finished);
        }

        private void routeSynthetic(CompiledExecutionStep step, List<EmittedValue> values, int index,
                                    ExecutionFrame frame, FramePath path, Consumer<FlowOutcome> finished) {
            routeValues(step, values, index, null, frame, path, finished);
        }

        private void routeValues(CompiledExecutionStep step, List<EmittedValue> values, int index, String branch,
                                 ExecutionFrame frame, FramePath path, Consumer<FlowOutcome> finished) {
            if (index >= values.size()) {
                finished.accept(FlowOutcome.success());
                return;
            }
            EmittedValue output = values.get(index);
            GraphEndpoint endpoint = new GraphEndpoint(step.nodeId(), output.pin());
            frame.outputs.put(endpoint, output.value());
            routeConnections(step, routes.getOrDefault(endpoint, List.of()), 0, output.value(), branch, frame, path, routed -> {
                if (!routed.successful()) {
                    finished.accept(routed);
                    return;
                }
                schedule(() -> routeValues(step, values, index + 1, branch, frame, path, finished));
            });
        }

        private void routeConnections(CompiledExecutionStep step, List<RoutedConnection> outgoing, int index,
                                      TypedValue value, String branch, ExecutionFrame frame, FramePath path,
                                      Consumer<FlowOutcome> finished) {
            int routeIndex = index;
            while (routeIndex < outgoing.size()) {
                RoutedConnection candidate = outgoing.get(routeIndex);
                if (candidate.connection().source().branchId() == null
                    || Objects.equals(candidate.connection().source().branchId().canonicalText(), branch)) {
                    break;
                }
                routeIndex++;
            }
            if (routeIndex >= outgoing.size()) {
                finished.accept(FlowOutcome.success());
                return;
            }
            RoutedConnection route = outgoing.get(routeIndex);
            int nextIndex = routeIndex + 1;
            convert(step, route, 0, value, path, converted -> {
                if (!converted.successful()) {
                    finished.accept(converted.outcome());
                    return;
                }
                GraphEndpoint target = route.connection().target();
                frame.routedInputs.computeIfAbsent(target.nodeId(), ignored -> new LinkedHashMap<>())
                    .put(target.pinId(), assignDirectly(converted.value(), route.targetType()));
                if (executionInputs.getOrDefault(target.nodeId(), Set.of()).contains(target.pinId())
                    && converted.value().hasValue()) {
                    frame.activatedExecutionInputs.computeIfAbsent(target.nodeId(), ignored -> new LinkedHashSet<>())
                        .add(target.pinId());
                }
                schedule(() -> routeConnections(step, outgoing, nextIndex, value, branch, frame, path, finished));
            });
        }

        private void convert(CompiledExecutionStep sourceStep, RoutedConnection route, int index, TypedValue value,
                             FramePath path, Consumer<ConversionOutcome> finished) {
            if (index >= route.conversions().size()) {
                finished.accept(ConversionOutcome.success(value));
                return;
            }
            FlowOutcome boundary = boundaryFailure(sourceStep, "Runtime Execution Stopped Before Conversion Admission");
            if (boundary != null) {
                finished.accept(ConversionOutcome.failure(boundary));
                return;
            }
            if (!budget.admit()) {
                finished.accept(ConversionOutcome.failure(failureOutcome(sourceStep, "RUNTIME.INVALID_INVOCATION",
                    "Compiled Execution Operation Budget Was Exhausted", RuntimeResult.Status.FAILURE)));
                return;
            }
            ConversionBinding conversion = route.conversions().get(index);
            String key = conversionId(route, index, conversion, path);
            RuntimeInvocation invocation = new RuntimeInvocation(conversion.binding().key(),
                Map.of(conversion.input(), value), key, cancellationToken)
                .withRuntimeContext(runtimeContext).withInvocationId(invocationId);
            CompletionStage<RuntimeResult> stage = lease.execute(invocation);
            stage.whenComplete((converted, failure) -> schedule(() -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                    return;
                }
                if (!converted.successful()) {
                    finished.accept(ConversionOutcome.failure(FlowOutcome.from(converted)));
                    return;
                }
                TypedValue output = converted.value() != null ? converted.value() : converted.outputs().get(conversion.output());
                if (output == null || !conversion.edge().target().equals(output.type())) {
                    completion.completeExceptionally(new IllegalStateException("Compiled Conversion Returned An Invalid Target Value"));
                    return;
                }
                schedule(() -> convert(sourceStep, route, index + 1, output, path, finished));
            }));
        }

        private FlowOutcome boundaryFailure(CompiledExecutionStep step, String reason) {
            if (deadlineMillis != RuntimeExecutionContext.NO_DEADLINE && System.currentTimeMillis() >= deadlineMillis) {
                return failureOutcome(step, "RUNTIME.EXECUTION_TIMEOUT", reason, RuntimeResult.Status.FAILURE);
            }
            if (cancellationToken.isCancelled()) {
                return failureOutcome(step, "RUNTIME.CANCELLED", reason, RuntimeResult.Status.CANCELLED);
            }
            return null;
        }

        private FlowOutcome failureOutcome(CompiledExecutionStep step, String code, String reason,
                                           RuntimeResult.Status status) {
            return FlowOutcome.failure(runtimeFailure(step, code, reason), status);
        }

        private RuntimeFailure runtimeFailure(CompiledExecutionStep step, String code, String reason) {
            Diagnostic diagnostic = Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
                .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of("runtime")))
                .correlationId(invocationId)
                .evidence(Map.of("nodeInstanceId", step.nodeId().canonicalText(), "reason", reason))
                .build();
            return new RuntimeFailure(diagnostic, "RUNTIME.EXECUTION_TIMEOUT".equals(code));
        }

        private void mergeSuccessfulIteration(CompiledExecutionStep controller, ExecutionFrame parent, ExecutionFrame iteration) {
            Set<NodeInstanceId> bodyNodes = loopOwnership.descendants(controller.nodeId());
            parent.results.keySet().removeIf(bodyNodes::contains);
            CompiledExecutionStep.LoopControl control = controller.loopControl();
            Set<PinId> dynamicPins = control.elementOutput() == null
                ? Set.of(control.bodyOutput(), control.indexOutput())
                : Set.of(control.bodyOutput(), control.indexOutput(), control.elementOutput());
            parent.outputs.keySet().removeIf(endpoint -> bodyNodes.contains(endpoint.nodeId())
                || endpoint.nodeId().equals(controller.nodeId()) && dynamicPins.contains(endpoint.pinId()));
            parent.results.putAll(iteration.results);
            parent.outputs.putAll(iteration.outputs);
        }

        private void mergeFailedIteration(ExecutionFrame parent, ExecutionFrame iteration) {
            parent.results.putAll(iteration.results);
        }

        private String conversionId(RoutedConnection route, int index, ConversionBinding conversion, FramePath path) {
            StringBuilder identity = new StringBuilder(80);
            identity.append("conversion:")
                .append(invocationId.canonicalText())
                .append(':')
                .append(plan.planHash().canonicalText())
                .append(':')
                .append(route.connection().connectionId().canonicalText())
                .append(':')
                .append(index)
                .append(':')
                .append(conversion.edge().id().ownerId())
                .append('.')
                .append(conversion.edge().id().localId());
            if (!path.isRoot()) {
                identity.append(":frame:").append(path.identity());
            }
            return identity.toString();
        }

        private void schedule(Runnable action) {
            if (!completion.isDone()) {
                trampoline.execute(() -> {
                    if (completion.isDone()) {
                        return;
                    }
                    try {
                        action.run();
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    }
                });
            }
        }
    }

    private static BigInteger integral(Object value) {
        try {
            return switch (value) {
                case BigInteger integer -> integer;
                case BigDecimal decimal -> decimal.toBigIntegerExact();
                case Byte number -> BigInteger.valueOf(number.longValue());
                case Short number -> BigInteger.valueOf(number.longValue());
                case Integer number -> BigInteger.valueOf(number.longValue());
                case Long number -> BigInteger.valueOf(number);
                default -> null;
            };
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static String childId(CorrelationId invocationId, CompiledExecutionStep step, FramePath path) {
        String base = invocationId.canonicalText() + ":" + step.stepId();
        if (path.isRoot()) {
            return base;
        }
        return base + ":frame:" + path.identity();
    }

    private static final class ExecutionFrame {
        private final Map<NodeInstanceId, RuntimeResult> results;
        private final Map<NodeInstanceId, Map<PinId, TypedValue>> routedInputs;
        private final Map<NodeInstanceId, Set<PinId>> activatedExecutionInputs;
        private final Map<GraphEndpoint, TypedValue> outputs;

        private ExecutionFrame(Map<NodeInstanceId, RuntimeResult> results,
                               Map<NodeInstanceId, Map<PinId, TypedValue>> routedInputs,
                               Map<NodeInstanceId, Set<PinId>> activatedExecutionInputs,
                               Map<GraphEndpoint, TypedValue> outputs) {
            this.results = results;
            this.routedInputs = routedInputs;
            this.activatedExecutionInputs = activatedExecutionInputs;
            this.outputs = outputs;
        }

        private static ExecutionFrame iteration(ExecutionFrame parent) {
            LinkedHashMap<NodeInstanceId, Map<PinId, TypedValue>> inherited = new LinkedHashMap<>();
            parent.routedInputs.forEach((nodeId, inputs) -> inherited.put(nodeId, new LinkedHashMap<>(inputs)));
            return new ExecutionFrame(new LinkedHashMap<>(), inherited, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private record LoopSequence(BigInteger count, List<?> elements) {
        private static LoopSequence count(BigInteger count) {
            return new LoopSequence(count, null);
        }

        private static LoopSequence elements(List<?> elements) {
            return new LoopSequence(null, elements);
        }

        private boolean hasNext(int ordinal) {
            return count == null ? ordinal < elements.size() : count.compareTo(BigInteger.valueOf(ordinal)) > 0;
        }

        private Object element(int ordinal) {
            return elements.get(ordinal);
        }
    }

    private record EmittedValue(PinId pin, TypedValue value) {}

    private record ConversionOutcome(TypedValue value, FlowOutcome outcome) {
        private static ConversionOutcome success(TypedValue value) {
            return new ConversionOutcome(Objects.requireNonNull(value, "Converted Value Is Required"), FlowOutcome.success());
        }

        private static ConversionOutcome failure(FlowOutcome outcome) {
            if (Objects.requireNonNull(outcome, "Conversion Outcome Is Required").successful()) {
                throw new IllegalArgumentException("Conversion Failure Requires A Failed Outcome");
            }
            return new ConversionOutcome(null, outcome);
        }

        private boolean successful() {
            return outcome.successful();
        }
    }

    private record FlowOutcome(RuntimeFailure failure, RuntimeResult.Status status) {
        private static FlowOutcome success() {
            return new FlowOutcome(null, null);
        }

        private static FlowOutcome failure(RuntimeFailure failure, RuntimeResult.Status status) {
            if (status == RuntimeResult.Status.SUCCESS) {
                throw new IllegalArgumentException("Failed Flow Outcome Cannot Use Success Status");
            }
            return new FlowOutcome(Objects.requireNonNull(failure, "Flow Failure Is Required"),
                Objects.requireNonNull(status, "Flow Failure Status Is Required"));
        }

        private static FlowOutcome from(RuntimeResult result) {
            return result.successful() ? success() : failure(result.failure(), result.status());
        }

        private boolean successful() {
            return failure == null;
        }
    }

    private record FrameSegment(String controller, int ordinal) {
        private FrameSegment {
            Objects.requireNonNull(controller, "Loop Controller Identity Is Required");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Loop Frame Ordinal Cannot Be Negative");
            }
        }
    }

    private record FramePath(List<FrameSegment> segments) {
        private FramePath {
            segments = List.copyOf(segments);
        }

        private static FramePath root() {
            return new FramePath(List.of());
        }

        private FramePath child(UUID controller, int ordinal) {
            ArrayList<FrameSegment> nested = new ArrayList<>(segments);
            nested.add(new FrameSegment(controller.toString(), ordinal));
            return new FramePath(nested);
        }

        private boolean isRoot() {
            return segments.isEmpty();
        }

        private String identity() {
            if (segments.isEmpty()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (FrameSegment segment : segments) {
                if (!text.isEmpty()) {
                    text.append('/');
                }
                text.append(segment.controller()).append('#').append(segment.ordinal());
            }
            return text.toString();
        }
    }

    private static final class LoopOwnership {
        private final Map<NodeInstanceId, NodeInstanceId> directOwners;
        private final Map<NodeInstanceId, Set<NodeInstanceId>> descendants;

        private LoopOwnership(Map<NodeInstanceId, NodeInstanceId> directOwners,
                              Map<NodeInstanceId, Set<NodeInstanceId>> descendants) {
            this.directOwners = directOwners;
            this.descendants = descendants;
        }

        private static LoopOwnership of(List<CompiledExecutionStep> steps, Set<NodeInstanceId> knownNodes) {
            LinkedHashMap<NodeInstanceId, NodeInstanceId> owners = new LinkedHashMap<>();
            for (CompiledExecutionStep step : steps) {
                if (step.loopControl() == null) {
                    continue;
                }
                for (NodeInstanceId bodyNode : step.loopControl().bodySteps()) {
                    if (!knownNodes.contains(bodyNode)) {
                        throw new IllegalStateException("Compiled Loop Body Step Is Missing: " + bodyNode.canonicalText());
                    }
                    NodeInstanceId previous = owners.putIfAbsent(bodyNode, step.nodeId());
                    if (previous != null && !previous.equals(step.nodeId())) {
                        throw new IllegalStateException("Compiled Loop Body Step Has More Than One Immediate Owner: " + bodyNode.canonicalText());
                    }
                }
            }
            LinkedHashMap<NodeInstanceId, Set<NodeInstanceId>> nested = new LinkedHashMap<>();
            for (CompiledExecutionStep controller : steps) {
                if (controller.loopControl() == null) {
                    continue;
                }
                LinkedHashSet<NodeInstanceId> owned = new LinkedHashSet<>();
                for (NodeInstanceId node : owners.keySet()) {
                    LinkedHashSet<NodeInstanceId> visited = new LinkedHashSet<>();
                    NodeInstanceId owner = owners.get(node);
                    while (owner != null) {
                        if (!visited.add(owner)) {
                            throw new IllegalStateException("Compiled Loop Ownership Contains A Cycle");
                        }
                        if (owner.equals(controller.nodeId())) {
                            owned.add(node);
                            break;
                        }
                        owner = owners.get(owner);
                    }
                }
                nested.put(controller.nodeId(), Set.copyOf(owned));
            }
            return new LoopOwnership(Map.copyOf(owners), Map.copyOf(nested));
        }

        private boolean bodyOwned(NodeInstanceId nodeId) {
            return directOwners.containsKey(nodeId);
        }

        private Set<NodeInstanceId> descendants(NodeInstanceId controller) {
            return descendants.getOrDefault(controller, Set.of());
        }
    }

    private static final class ExecutionBudget {
        private int remaining;

        private ExecutionBudget(int limit) {
            remaining = limit;
        }

        private boolean admit() {
            if (remaining == 0) {
                return false;
            }
            remaining--;
            return true;
        }

        private boolean canAdmit() {
            return remaining > 0;
        }
    }

    private static final class Trampoline {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean draining;

        private void execute(Runnable task) {
            boolean start;
            synchronized (this) {
                tasks.addLast(task);
                start = !draining;
                if (start) {
                    draining = true;
                }
            }
            if (!start) {
                return;
            }
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = tasks.pollFirst();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                next.run();
            }
        }
    }

    private List<RuntimeLeaseInput.BindingRequirement> requirements(CompiledExecutionPlan plan,
                                                                    List<CompiledExecutionStep> steps,
                                                                    RuntimeRegistrySnapshot runtimeSnapshot,
                                                                    Map<GraphEndpoint, List<RoutedConnection>> routes) {
        Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> requirements = new TreeMap<>(Comparator.comparing(RuntimeBindingKey::canonical));
        Map<RuntimeBindingKey, ContentHash> planFingerprints = new HashMap<>();
        for (CompiledExecutionStep step : steps) {
            RuntimeBindingKey key = new RuntimeBindingKey(step.handler().capability(), step.handler().operation());
            RuntimeBinding binding = validateResolvedBinding(step, key, runtimeSnapshot);
            ContentHash fingerprint = binding.executionFingerprint();
            ContentHash resolvedFingerprint = step.resolvedBindingFingerprint();
            ProviderLease declared = plan.providerLeases().stream()
                .filter(lease -> lease.capability().equals(key.capability()) && lease.operation().equals(key.operation())
                    && lease.provider().equals(binding.provider()))
                .findFirst().orElse(null);
            ContentHash expectedPlanFingerprint = resolvedFingerprint == null ? fingerprint : resolvedFingerprint;
            if (!plan.providerLeases().isEmpty() && (declared == null || !declared.bindingFingerprint().equals(expectedPlanFingerprint))) {
                throw new IllegalStateException("Compiled Plan Runtime Binding Fingerprint Is Stale: " + key.canonical());
            }
            ContentHash previousPlanFingerprint = planFingerprints.putIfAbsent(key, expectedPlanFingerprint);
            if (previousPlanFingerprint != null && !previousPlanFingerprint.equals(expectedPlanFingerprint)) {
                throw new IllegalStateException("Compiled Plan Runtime Binding Fingerprints Disagree: " + key.canonical());
            }
            RuntimeLeaseInput.BindingRequirement requirement = new RuntimeLeaseInput.BindingRequirement(key, fingerprint,
                binding.descriptor().pins().stream()
                    .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
                    .map(RuntimeOperationDescriptor.Pin::id).toList(), List.copyOf(step.outputBindings().keySet()));
            RuntimeLeaseInput.BindingRequirement previous = requirements.putIfAbsent(key, requirement);
            if (previous != null && !previous.equals(requirement)) {
                throw new IllegalStateException("Compiled Plan Runtime Pin Bindings Disagree: " + key.canonical());
            }
        }
        for (List<RoutedConnection> outgoing : routes.values()) {
            for (RoutedConnection route : outgoing) {
                for (ConversionBinding conversion : route.conversions()) {
                    RuntimeBinding binding = conversion.binding();
                    RuntimeBindingKey key = binding.key();
                    ProviderLease declared = plan.providerLeases().stream()
                        .filter(value -> value.provider().equals(binding.provider()) && value.capability().equals(key.capability())
                            && value.operation().equals(key.operation())).findFirst().orElseThrow(() ->
                            new IllegalStateException("Compiled Conversion Provider Lease Is Missing: " + key.canonical()));
                    if (!declared.bindingFingerprint().equals(binding.executionFingerprint())) {
                        throw new IllegalStateException("Compiled Conversion Provider Fingerprint Is Stale: " + key.canonical());
                    }
                    RuntimeLeaseInput.BindingRequirement requirement = new RuntimeLeaseInput.BindingRequirement(key,
                        binding.executionFingerprint(), List.of(conversion.input()), List.of(conversion.output()));
                    RuntimeLeaseInput.BindingRequirement previous = requirements.putIfAbsent(key, requirement);
                    if (previous != null && !previous.equals(requirement)) {
                        throw new IllegalStateException("Compiled Conversion Pin Bindings Disagree: " + key.canonical());
                    }
                }
            }
        }
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("Compiled Execution Plan Contains No Runtime Steps");
        }
        return List.copyOf(requirements.values());
    }

    private Map<GraphEndpoint, List<RoutedConnection>> conversionBindings(CompiledExecutionPlan plan,
                                                                       List<CompiledExecutionStep> steps,
                                                                       CatalogRuntimeActivation.ActivationRecord activation,
                                                                       RuntimeRegistrySnapshot runtimeSnapshot) {
        Map<NodeInstanceId, RuntimeBinding> bindings = new HashMap<>();
        Map<NodeInstanceId, CompiledExecutionStep> stepsByNode = new HashMap<>();
        steps.forEach(step -> bindings.put(step.nodeId(), validateResolvedBinding(step,
            new RuntimeBindingKey(step.handler().capability(), step.handler().operation()), runtimeSnapshot)));
        steps.forEach(step -> stepsByNode.put(step.nodeId(), step));
        Map<ConnectionId, ConversionRoute> declared = new HashMap<>();
        for (ConversionRoute route : plan.conversionRoutes()) {
            if (declared.putIfAbsent(route.connectionId(), route) != null || plan.connections().stream().noneMatch(connection ->
                connection.connectionId().equals(route.connectionId()) && connection.source().equals(route.source())
                    && connection.target().equals(route.target()))) {
                throw new IllegalStateException("Compiled Conversion Connection Is Invalid");
            }
        }
        Map<TypeReference, ConversionGraph.ConversionEdge> edges = new HashMap<>();
        ConversionGraph.Builder graph = ConversionGraph.builder();
        if (activation != null) {
            activation.catalog().conversions().forEach(owned -> {
                edges.put(owned.descriptor().id(), owned.descriptor());
                graph.add(owned.descriptor());
            });
        }
        ConversionGraph conversions = graph.build();
        Map<GraphEndpoint, List<RoutedConnection>> routes = new HashMap<>();
        for (GraphConnection connection : plan.connections().stream().sorted(Comparator.comparing(GraphConnection::connectionId)).toList()) {
            if (!bindings.containsKey(connection.source().nodeId()) || !bindings.containsKey(connection.target().nodeId())) {
                continue;
            }
            TypeExpr source = pinType(bindings.get(connection.source().nodeId()), connection.source().pinId(), RuntimeOperationDescriptor.Direction.OUTPUT);
            TypeExpr target = targetPinType(bindings.get(connection.target().nodeId()),
                stepsByNode.get(connection.target().nodeId()), connection.target().pinId());
            ConversionRoute route = declared.get(connection.connectionId());
            List<ConversionBinding> resolved = new ArrayList<>();
            if (GraphValidator.directlyAssignable(source, target)) {
                if (route != null) {
                    throw new IllegalStateException("Compiled Conversion Is Declared For Directly Assignable Pin Types");
                }
            } else {
                if (activation == null || route == null || !source.equals(route.sourceType()) || !target.equals(route.targetType())
                    || !conversions.resolve(source, target).edgeIds().equals(route.conversionIds())) {
                    throw new IllegalStateException("Compiled Conversion Does Not Match The Active Catalog Route");
                }
                for (TypeReference id : route.conversionIds()) {
                    ConversionGraph.ConversionEdge edge = edges.get(id);
                    RuntimeBinding binding = runtimeSnapshot.binding(new RuntimeBindingKey(edge.capability(), edge.operation())).orElseThrow();
                    RuntimeProviderDescriptor provider = runtimeSnapshot.providers().get(binding.provider());
                    if (!binding.descriptor().available() || provider == null || provider.state() != RuntimeProviderState.ACTIVE
                        || !GraphValidator.conversionSignature(edge, binding.descriptor())) {
                        throw new IllegalStateException("Compiled Conversion Runtime Binding Is Unavailable Or Invalid");
                    }
                    PinId input = binding.descriptor().pins().stream()
                        .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT).findFirst().orElseThrow().id();
                    PinId output = binding.descriptor().pins().stream()
                        .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT).findFirst().orElseThrow().id();
                    resolved.add(new ConversionBinding(edge, binding, input, output));
                }
            }
            GraphEndpoint output = new GraphEndpoint(connection.source().nodeId(), connection.source().pinId());
            routes.computeIfAbsent(output, ignored -> new ArrayList<>()).add(new RoutedConnection(connection, target, List.copyOf(resolved)));
        }
        routes.replaceAll((endpoint, outgoing) -> List.copyOf(outgoing));
        return Map.copyOf(routes);
    }

    private static TypeExpr pinType(RuntimeBinding binding, PinId id, RuntimeOperationDescriptor.Direction direction) {
        return binding.descriptor().pins().stream().filter(pin -> pin.id().equals(id) && pin.direction() == direction)
            .findFirst().orElseThrow(() -> new IllegalStateException("Compiled Connection Pin Is Missing")).type();
    }

    private static TypeExpr targetPinType(RuntimeBinding binding, CompiledExecutionStep step, PinId id) {
        RuntimeOperationDescriptor.Pin declared = binding.descriptor().pins().stream()
            .filter(pin -> pin.id().equals(id) && pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
            .findFirst().orElse(null);
        if (declared != null) {
            return declared.type();
        }
        TypedValue dynamic = step.inputBindings().get(id);
        if (dynamic == null || !StringTemplatePins.STRING.equals(dynamic.type())
            || binding.descriptor().pins().stream().noneMatch(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT
                && StringTemplatePins.STRING.equals(pin.type())
                && step.inputBindings().get(pin.id()) != null
                && step.inputBindings().get(pin.id()).value() instanceof String text
                && StringTemplatePins.names(text).contains(id.canonicalText()))) {
            throw new IllegalStateException("Compiled Connection Pin Is Missing");
        }
        return StringTemplatePins.STRING;
    }

    private record ConversionBinding(ConversionGraph.ConversionEdge edge, RuntimeBinding binding, PinId input, PinId output) {}

    private static TypedValue assignDirectly(TypedValue value, TypeExpr target) {
        if (value.type().equals(target)) {
            return value;
        }
        if (value.state() == TypedValue.State.ABSENT) {
            return TypedValue.absent(target);
        }
        if (value.state() == TypedValue.State.NULL) {
            return TypedValue.nullValue(target);
        }
        if (target instanceof TypeExpr.Named named && "builtin".equals(named.reference().ownerId())
            && "string".equals(named.reference().localId()) && named.arguments().isEmpty()) {
            Object material = value.state() == TypedValue.State.LOCATOR ? value.locator().canonicalText() : value.value();
            return TypedValue.value(target, String.valueOf(material));
        }
        if (value.state() == TypedValue.State.VALUE) {
            return TypedValue.value(target, value.value());
        }
        return value;
    }

    private record RoutedConnection(GraphConnection connection, TypeExpr targetType, List<ConversionBinding> conversions) {}

    private static List<CompiledExecutionStep> executionScope(CompiledExecutionPlan plan,
                                                              List<CompiledExecutionStep> ordered,
                                                              NodeInstanceId startNodeId) {
        if (startNodeId == null) {
            return ordered;
        }
        Set<NodeInstanceId> knownNodes = ordered.stream().map(CompiledExecutionStep::nodeId).collect(Collectors.toSet());
        if (!knownNodes.contains(startNodeId)) {
            throw new IllegalArgumentException("Compiled Execution Plan Start Node Is Unknown: " + startNodeId.canonicalText());
        }
        List<GraphConnection> incoming = plan.connections().stream()
            .filter(connection -> connection.target().nodeId().equals(startNodeId))
            .toList();
        if (!incoming.isEmpty()) {
            throw new IllegalArgumentException("Compiled Execution Plan Start Node Has Incoming Dependencies: " + startNodeId.canonicalText());
        }
        Map<NodeInstanceId, List<NodeInstanceId>> outgoing = new HashMap<>();
        plan.connections().forEach(connection -> outgoing.computeIfAbsent(connection.source().nodeId(), ignored -> new ArrayList<>())
            .add(connection.target().nodeId()));
        Set<NodeInstanceId> reachable = new LinkedHashSet<>();
        ArrayDeque<NodeInstanceId> queue = new ArrayDeque<>();
        reachable.add(startNodeId);
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            NodeInstanceId node = queue.removeFirst();
            for (NodeInstanceId target : outgoing.getOrDefault(node, List.of())) {
                if (reachable.add(target)) {
                    queue.addLast(target);
                }
            }
        }
        Map<NodeInstanceId, List<NodeInstanceId>> upstream = new HashMap<>();
        plan.connections().forEach(connection -> upstream.computeIfAbsent(connection.target().nodeId(), ignored -> new ArrayList<>())
            .add(connection.source().nodeId()));
        queue.addAll(reachable);
        while (!queue.isEmpty()) {
            NodeInstanceId node = queue.removeFirst();
            for (NodeInstanceId source : upstream.getOrDefault(node, List.of())) {
                if (reachable.add(source)) {
                    queue.addLast(source);
                }
            }
        }
        return ordered.stream().filter(step -> reachable.contains(step.nodeId())).toList();
    }

    private RuntimeBinding validateResolvedBinding(
        CompiledExecutionStep step,
        RuntimeBindingKey key,
        RuntimeRegistrySnapshot runtimeSnapshot
    ) {
        RuntimeBinding active = runtimeSnapshot == null
            ? registry.resolve(key)
            : runtimeSnapshot.binding(key).orElseThrow(() -> new IllegalStateException(
                "Compiled Plan Runtime Binding Is Missing From The Active Activation: " + key.canonical()));
        if (runtimeSnapshot != null) {
            RuntimeProviderDescriptor provider = runtimeSnapshot.providers().get(active.provider());
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                throw new IllegalStateException("Compiled Plan Runtime Provider Is Not Active: " + key.canonical());
            }
        }
        RuntimeBindingDescriptor resolved = step.resolvedBinding();
        if (resolved == null) {
            return active;
        }
        if (!resolved.provider().equals(active.provider())) {
            throw new IllegalStateException("Compiled Plan Runtime Binding Provider Is Stale: " + key.canonical());
        }
        if (!resolved.equals(active.descriptor())) {
            throw new IllegalStateException("Compiled Plan Runtime Binding Descriptor Is Stale: " + key.canonical());
        }
        return active;
    }

    private CatalogRuntimeActivation.ActivationRecord activeActivation() {
        if (activationSupplier == null) {
            return null;
        }
        CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
        if (activation == null) {
            throw new IllegalStateException("Active Catalog Runtime Activation Is Required");
        }
        return activation;
    }

    private static void validateActivationPlan(
        CompiledExecutionPlan plan,
        CatalogRuntimeActivation.ActivationRecord activation
    ) {
        if (activation == null) {
            return;
        }
        if (plan.catalogBinding().generation() != activation.catalog().generation()
            || !plan.catalogBinding().catalogChecksum().equals(activation.catalog().contentChecksum())
            || !plan.catalogBinding().bindingManifestHash().equals(activation.runtime().bindingManifestHash())) {
            throw new IllegalStateException("Compiled Plan Catalog Binding Does Not Match The Active Catalog Runtime Activation");
        }
    }

    private static List<CompiledExecutionStep> topologicalOrder(CompiledExecutionPlan plan) {
        Map<NodeInstanceId, CompiledExecutionStep> byNode = new HashMap<>();
        plan.steps().forEach(step -> {
            if (byNode.put(step.nodeId(), step) != null) {
                throw new IllegalArgumentException("Compiled Execution Plan Contains Duplicate Node Steps");
            }
        });
        Map<NodeInstanceId, Integer> indegree = new HashMap<>();
        Map<NodeInstanceId, List<NodeInstanceId>> outgoing = new HashMap<>();
        byNode.keySet().forEach(node -> indegree.put(node, 0));
        plan.connections().stream().sorted(Comparator.comparing(connection -> connection.connectionId().canonicalText())).forEach(connection -> {
            if (!byNode.containsKey(connection.source().nodeId()) || !byNode.containsKey(connection.target().nodeId())) {
                throw new IllegalArgumentException("Compiled Execution Plan Contains An Unknown Connection Node");
            }
            indegree.compute(connection.target().nodeId(), (ignored, count) -> count + 1);
            outgoing.computeIfAbsent(connection.source().nodeId(), ignored -> new ArrayList<>()).add(connection.target().nodeId());
        });
        ArrayDeque<NodeInstanceId> ready = new ArrayDeque<>(indegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey)
            .sorted(Comparator.comparing(NodeInstanceId::canonicalText))
            .toList());
        List<CompiledExecutionStep> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            NodeInstanceId node = ready.removeFirst();
            ordered.add(byNode.get(node));
            outgoing.getOrDefault(node, List.of()).stream().sorted(Comparator.comparing(NodeInstanceId::canonicalText)).forEach(target -> {
                int remaining = indegree.compute(target, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.add(target);
                }
            });
        }
        if (ordered.size() != byNode.size()) {
            throw new IllegalArgumentException("Compiled Execution Plan Contains A Cycle");
        }
        return List.copyOf(ordered);
    }

    public record ExecutionResult(Status status, Map<NodeInstanceId, RuntimeResult> nodeResults,
                                   Map<GraphEndpoint, TypedValue> outputs, RuntimeFailure failure) {
        public ExecutionResult {
            status = Objects.requireNonNull(status, "Execution Status Is Required");
            nodeResults = Map.copyOf(Objects.requireNonNull(nodeResults, "Node Results Are Required"));
            outputs = Map.copyOf(Objects.requireNonNull(outputs, "Execution Outputs Are Required"));
            if (status == Status.SUCCESS && failure != null) {
                throw new IllegalArgumentException("Successful Execution Cannot Contain A Failure");
            }
            if (status != Status.SUCCESS && failure == null) {
                throw new IllegalArgumentException("Failed Execution Requires A Failure");
            }
        }

        static ExecutionResult success(Map<NodeInstanceId, RuntimeResult> results, Map<GraphEndpoint, TypedValue> outputs) {
            return new ExecutionResult(Status.SUCCESS, results, outputs, null);
        }

        static ExecutionResult failure(Map<NodeInstanceId, RuntimeResult> results, Map<GraphEndpoint, TypedValue> outputs,
                                       RuntimeFailure failure, RuntimeResult.Status resultStatus) {
            return new ExecutionResult(resultStatus == RuntimeResult.Status.CANCELLED ? Status.CANCELLED : Status.FAILURE,
                results, outputs, Objects.requireNonNull(failure, "Execution Failure Is Required"));
        }
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        CANCELLED
    }
}
