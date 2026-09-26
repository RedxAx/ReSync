package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.LeaseId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.UuidIdentity;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class GraphCompiler {
    private static final OwnerId LOOP_OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<CapabilityId> LOOP_CAPABILITY = ContractRef.of(LOOP_OWNER, CapabilityId.of("flow.control"));
    private static final TypeExpr EXECUTION_TYPE = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr NUMBER_TYPE = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr BOOLEAN_TYPE = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final PinId ENTRY_INPUT = PinId.of("flow");
    private static final PinId COUNT_INPUT = PinId.of("count");
    private static final PinId LIST_INPUT = PinId.of("list");
    private static final PinId BODY_OUTPUT = PinId.of("loop");
    private static final PinId DONE_OUTPUT = PinId.of("done");
    private static final PinId INDEX_OUTPUT = PinId.of("index");
    private static final PinId ELEMENT_OUTPUT = PinId.of("element");
    private static final PinId COMPLETED_OUTPUT = PinId.of("completed");
    private final GraphValidator validator;
    private final RuntimeBindingManifest runtimeManifest;

    public GraphCompiler() {
        this(new GraphValidator(), null);
    }

    public GraphCompiler(GraphValidator validator) {
        this(validator, null);
    }

    public GraphCompiler(RuntimeBindingManifest runtimeManifest) {
        this(new GraphValidator(), runtimeManifest);
    }

    public GraphCompiler(GraphValidator validator, RuntimeBindingManifest runtimeManifest) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.runtimeManifest = runtimeManifest;
    }

    public CompiledExecutionPlan compile(GraphDocument graph, CatalogSnapshot catalog) {
        GraphCompilationResult result = compileResult(graph, catalog);
        if (!result.compiled()) {
            throw new GraphCompilationException(result.validation());
        }
        return result.plan();
    }

    public GraphCompilationResult compileResult(GraphDocument graph, CatalogSnapshot catalog) {
        return compileResult(graph, catalog, null);
    }

    public CompiledExecutionPlan compile(FunctionSourceDocument source, CatalogSnapshot catalog) {
        GraphCompilationResult result = compileResult(source, catalog);
        if (!result.compiled()) {
            throw new GraphCompilationException(result.validation());
        }
        return result.plan();
    }

    public GraphCompilationResult compileResult(FunctionSourceDocument source, CatalogSnapshot catalog) {
        ValidationResult validation = validator.validate(source, catalog, runtimeManifest);
        if (source == null) {
            return new GraphCompilationResult(null, validation);
        }
        return compileResult(source.graph(), catalog, source.signature(), validation);
    }

    private GraphCompilationResult compileResult(GraphDocument graph, CatalogSnapshot catalog,
                                                 FunctionSignature functionSignature) {
        ValidationResult validation = validator.validate(graph, catalog, runtimeManifest);
        return compileResult(graph, catalog, functionSignature, validation);
    }

    private GraphCompilationResult compileResult(GraphDocument graph, CatalogSnapshot catalog,
                                                 FunctionSignature functionSignature, ValidationResult validation) {
        if (!validation.valid() || graph == null || catalog == null || !matchesBinding(graph, catalog)) {
            return new GraphCompilationResult(null, validation);
        }
        ContentHash graphHash = graph.checksum();
        CatalogBinding catalogBinding = graph.catalogBinding();
        UUID planId = UuidIdentity.deterministic("execution-plan", graphHash.canonicalText() + "\u0000" + catalogBinding.canonicalText());
        Map<NodeInstanceId, CompiledExecutionStep.LoopControl> loopControls;
        try {
            loopControls = loopControls(graph, catalog);
        } catch (LoopCompilationException failure) {
            return new GraphCompilationResult(null, validation.plus(loopValidation(failure)));
        }
        Map<NodeInstanceId, List<GraphConnection>> outgoing = graph.connections().stream()
            .sorted(Comparator.comparing(GraphConnection::connectionId))
            .collect(Collectors.groupingBy(connection -> connection.source().nodeId(), LinkedHashMap::new, Collectors.toList()));
        ArrayList<CompiledExecutionStep> steps = new ArrayList<>();
        for (GraphNode node : graph.nodes().stream().sorted(Comparator.comparing(GraphNode::instanceId)).toList()) {
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definition(node.definition()).orElseThrow();
            CatalogNodeDescriptor definition = owned.descriptor();
            Map<PinId, FunctionBoundaryPins.EffectivePin> pins = FunctionBoundaryPins.resolve(owned, functionSignature, node);
            Map<PinId, TypedValue> inputs = inputBindings(node, pins);
            Map<PinId, List<GraphEndpoint>> outputs = new LinkedHashMap<>();
            pins.values().stream()
                .filter(pin -> pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT)
                .forEach(pin -> outputs.put(pin.id(), outgoing.getOrDefault(node.instanceId(), List.of()).stream()
                    .filter(connection -> connection.source().pinId().equals(pin.id()))
                    .map(GraphConnection::target)
                    .toList()));
            UUID stepId = UuidIdentity.deterministic("execution-step", planId + "\u0000" + node.instanceId().canonicalText());
            RuntimeBindingDescriptor resolvedBinding = resolvedBinding(definition);
            ContentHash resolvedBindingFingerprint = resolvedBinding == null ? null : runtimeManifest.executionFingerprint(
                new RuntimeBindingKey(resolvedBinding.capability(), resolvedBinding.operation())).orElseThrow();
            CompiledExecutionStep step = new CompiledExecutionStep(stepId, node.instanceId(), node.definition(), definition.handler(), inputs, outputs,
                definition.semantics(), resolvedBinding, resolvedBindingFingerprint, node.unknown());
            CompiledExecutionStep.LoopControl loopControl = loopControls.get(node.instanceId());
            steps.add(loopControl == null ? step : step.withLoopControl(loopControl));
        }
        ConversionGraph conversionGraph = conversionGraph(catalog);
        List<ConversionRoute> conversionRoutes = graph.connections().stream()
            .sorted(Comparator.comparing(GraphConnection::connectionId))
            .map(connection -> conversionRoute(connection, catalog, graph, conversionGraph, functionSignature))
            .filter(Objects::nonNull)
            .toList();
        List<StructuralRoute> structuralRoutes = structuralRoutes(graph);
        List<ProviderLease> providerLeases = providerLeases(planId, steps, conversionRoutes, catalog);
        CompiledExecutionPlan plan = new CompiledExecutionPlan(planId, graph.resource(), graph.revision(), catalogBinding, graphHash,
            steps, graph.connections(), conversionRoutes, structuralRoutes, graph.functions(), providerLeases, graph.unknown());
        return new GraphCompilationResult(plan, validation);
    }

    private static Map<PinId, TypedValue> inputBindings(GraphNode node,
                                                        Map<PinId, FunctionBoundaryPins.EffectivePin> pins) {
        LinkedHashMap<PinId, TypedValue> values = new LinkedHashMap<>();
        pins.values().stream()
            .filter(pin -> pin.direction() == CatalogNodeDescriptor.Direction.INPUT)
            .forEach(pin -> {
                PinValue configured = node.values().get(pin.id());
                if (configured != null) {
                    values.put(pin.id(), configured.value());
                } else if (pin.defaultValue() != null) {
                    values.put(pin.id(), pin.defaultValue());
                } else {
                    values.put(pin.id(), TypedValue.absent(pin.type()));
                }
            });
        return values;
    }

    private static ConversionGraph conversionGraph(CatalogSnapshot catalog) {
        ConversionGraph.Builder builder = ConversionGraph.builder();
        catalog.conversions().forEach(value -> builder.add(value.descriptor()));
        return builder.build();
    }

    private static ConversionRoute conversionRoute(GraphConnection connection, CatalogSnapshot catalog, GraphDocument graph,
                                                   ConversionGraph conversionGraph, FunctionSignature functionSignature) {
        GraphNode sourceNode = graph.nodes().stream().filter(node -> node.instanceId().equals(connection.source().nodeId())).findFirst().orElseThrow();
        GraphNode targetNode = graph.nodes().stream().filter(node -> node.instanceId().equals(connection.target().nodeId())).findFirst().orElseThrow();
        CatalogOwned<CatalogNodeDescriptor> sourceDefinition = catalog.definition(sourceNode.definition()).orElseThrow();
        CatalogOwned<CatalogNodeDescriptor> targetDefinition = catalog.definition(targetNode.definition()).orElseThrow();
        FunctionBoundaryPins.EffectivePin source = FunctionBoundaryPins.resolve(sourceDefinition, functionSignature, sourceNode)
            .get(connection.source().pinId());
        FunctionBoundaryPins.EffectivePin target = FunctionBoundaryPins.resolve(targetDefinition, functionSignature, targetNode)
            .get(connection.target().pinId());
        if (source == null || target == null) {
            throw new IllegalStateException("Validated graph connection pin is unavailable");
        }
        if (GraphValidator.directlyAssignable(source.type(), target.type())) {
            return null;
        }
        ConversionGraph.ConversionPath path = conversionGraph.resolve(source.type(), target.type());
        return new ConversionRoute(connection.connectionId(), connection.source(), connection.target(), source.type(), target.type(), path.edgeIds());
    }

    private static List<StructuralRoute> structuralRoutes(GraphDocument graph) {
        ArrayList<StructuralRoute> routes = new ArrayList<>();
        graph.nodes().stream().sorted(Comparator.comparing(GraphNode::instanceId)).forEach(node -> {
            node.branches().stream().sorted(Comparator.comparing(BranchBinding::branchId)).forEach(branch -> {
                String branchRoute = "branch-" + node.instanceId().canonicalText() + "-" + branch.branchId().canonicalText();
                routes.add(new StructuralRoute(branchRoute, StructuralRoute.Kind.BRANCH, List.of(branch.branchId().canonicalText())));
                branch.cases().stream().sorted(Comparator.comparing(BranchCase::caseId)).forEach(branchCase ->
                    routes.add(new StructuralRoute(branchRoute + "-case-" + branchCase.caseId().canonicalText(), StructuralRoute.Kind.CASE,
                        List.of(branchCase.caseId().canonicalText()))));
            });
            node.repeatables().stream().sorted(Comparator.comparing(RepeatableBinding::groupId)).forEach(repeatable -> {
                String groupRoute = "repeatable-" + node.instanceId().canonicalText() + "-" + repeatable.groupId().canonicalText();
                routes.add(new StructuralRoute(groupRoute, StructuralRoute.Kind.REPEATABLE_GROUP,
                    repeatable.elements().isEmpty()
                        ? List.of(repeatable.groupId().canonicalText())
                        : repeatable.elements().stream().map(element -> element.elementId().canonicalText()).toList()));
                repeatable.elements().stream().sorted(Comparator.comparing(RepeatableElement::elementId)).forEach(element ->
                    routes.add(new StructuralRoute(groupRoute + "-element-" + element.elementId().canonicalText(), StructuralRoute.Kind.REPEATABLE_ELEMENT,
                        element.values().isEmpty()
                            ? List.of(element.elementId().canonicalText())
                            : element.values().keySet().stream().map(PinId::canonicalText).toList())));
            });
            node.inspector().keySet().stream().sorted().forEach(field -> {
                routes.add(new StructuralRoute("inspector-" + node.instanceId().canonicalText() + "-" + field.canonicalText(),
                    StructuralRoute.Kind.INSPECTOR_FIELD, List.of(field.canonicalText())));
            });
        });
        graph.functions().forEach(function -> {
            function.inputs().forEach(parameter -> routes.add(new StructuralRoute(
                "parameter-" + function.function().id() + "-" + parameter.parameterId().canonicalText(), StructuralRoute.Kind.PARAMETER,
                List.of(parameter.parameterId().canonicalText()))));
            function.outputs().forEach(parameter -> routes.add(new StructuralRoute(
                "parameter-" + function.function().id() + "-" + parameter.parameterId().canonicalText(), StructuralRoute.Kind.PARAMETER,
                List.of(parameter.parameterId().canonicalText()))));
        });
        return routes.stream().collect(Collectors.toMap(StructuralRoute::routeId, value -> value, (left, right) -> left, LinkedHashMap::new)).values().stream()
            .sorted(Comparator.comparing(StructuralRoute::routeId)).toList();
    }

    private List<ProviderLease> providerLeases(UUID planId, List<CompiledExecutionStep> steps,
                                               List<ConversionRoute> routes, CatalogSnapshot catalog) {
        if (runtimeManifest == null) {
            return List.of();
        }
        Map<String, ProviderLease> leases = new LinkedHashMap<>();
        List<RuntimeBindingKey> keys = new ArrayList<>();
        steps.forEach(step -> keys.add(new RuntimeBindingKey(step.handler().capability(), step.handler().operation())));
        routes.forEach(route -> route.conversionIds().forEach(id -> {
            ConversionGraph.ConversionEdge edge = catalog.conversions().stream()
                .map(CatalogOwned::descriptor).filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            keys.add(new RuntimeBindingKey(edge.capability(), edge.operation()));
        }));
        keys.forEach(key -> {
            RuntimeBindingDescriptor binding = runtimeManifest.binding(key).orElseThrow();
            RuntimeProviderDescriptor provider = runtimeManifest.providers().stream()
                .filter(value -> value.provider().equals(binding.provider())).findFirst().orElseThrow();
            LeaseId leaseId = LeaseId.deterministic("execution-plan\u0000" + planId + "\u0000" + key.canonical());
            ContentHash fingerprint = runtimeManifest.executionFingerprint(key).orElseThrow();
            ProviderLease lease = new ProviderLease(leaseId, binding.provider(), binding.capability(), binding.operation(),
                fingerprint, Math.max(provider.drainDeadlineMillis(), binding.semantics().drainDeadlineMillis()),
                Math.max(provider.hardDeadlineMillis(), binding.semantics().hardDeadlineMillis()),
                binding.semantics().unloadPolicy());
            leases.putIfAbsent(key.canonical(), lease);
        });
        return leases.values().stream().sorted(Comparator.comparing(ProviderLease::leaseId)).toList();
    }

    private RuntimeBindingDescriptor resolvedBinding(CatalogNodeDescriptor definition) {
        if (runtimeManifest == null) {
            return null;
        }
        RuntimeBindingKey key = new RuntimeBindingKey(definition.handler().capability(), definition.handler().operation());
        RuntimeBindingDescriptor binding = runtimeManifest.binding(key)
            .orElseThrow(() -> new IllegalStateException("Compiled Graph Runtime Binding Is Missing: " + key.canonical()));
        RuntimeProviderDescriptor provider = runtimeManifest.providers().stream()
            .filter(value -> value.provider().equals(binding.provider()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Compiled Graph Runtime Provider Is Missing: " + binding.provider().canonicalText()));
        if (!binding.available() || provider.state() != RuntimeProviderState.ACTIVE) {
            throw new IllegalStateException("Compiled Graph Runtime Binding Is Unavailable: " + key.canonical());
        }
        return binding;
    }

    private static Map<NodeInstanceId, CompiledExecutionStep.LoopControl> loopControls(GraphDocument graph, CatalogSnapshot catalog) {
        Map<NodeInstanceId, CatalogNodeDescriptor> definitions = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            definitions.put(node.instanceId(), catalog.definition(node.definition()).map(CatalogOwned::descriptor).orElseThrow());
        }
        List<LoopScope> scopes = graph.nodes().stream()
            .sorted(Comparator.comparing(GraphNode::instanceId))
            .map(node -> loopScope(node, definitions.get(node.instanceId())))
            .filter(Objects::nonNull)
            .toList();
        if (scopes.isEmpty()) {
            return Map.of();
        }
        GraphTopology topology = new GraphTopology(graph, definitions);
        List<NodeInstanceId> order = topologicalOrder(graph, scopes.getFirst().controller);
        for (LoopScope scope : scopes) {
            deriveScope(scope, topology);
        }
        validateScopeRelations(scopes);
        LinkedHashMap<NodeInstanceId, CompiledExecutionStep.LoopControl> controls = new LinkedHashMap<>();
        for (LoopScope scope : scopes) {
            LinkedHashSet<NodeInstanceId> immediate = new LinkedHashSet<>(scope.body);
            scopes.stream()
                .filter(nested -> !nested.controller.equals(scope.controller) && scope.body.contains(nested.controller))
                .forEach(nested -> immediate.removeAll(nested.body));
            List<NodeInstanceId> bodySteps = order.stream().filter(immediate::contains).toList();
            controls.put(scope.controller, new CompiledExecutionStep.LoopControl(scope.kind, ENTRY_INPUT, scope.sourceInput, BODY_OUTPUT,
                DONE_OUTPUT, INDEX_OUTPUT, scope.elementOutput, COMPLETED_OUTPUT, scope.sourceType, scope.elementType, bodySteps));
        }
        return Map.copyOf(controls);
    }

    private static LoopScope loopScope(GraphNode node, CatalogNodeDescriptor definition) {
        CatalogNodeDescriptor.Handler handler = definition.handler();
        if (!handler.capability().equals(LOOP_CAPABILITY) || !handler.operation().owner().equals(LOOP_OWNER)) {
            return null;
        }
        String operation = handler.operation().id().canonicalText();
        if (!operation.equals("loop_count") && !operation.equals("loop_for_each")) {
            return null;
        }
        Map<PinId, CatalogNodeDescriptor.Pin> pins = exactPins(node.instanceId(), definition,
            operation.equals("loop_count") ? Set.of(ENTRY_INPUT, COUNT_INPUT, BODY_OUTPUT, DONE_OUTPUT, INDEX_OUTPUT, COMPLETED_OUTPUT)
                : Set.of(ENTRY_INPUT, LIST_INPUT, BODY_OUTPUT, DONE_OUTPUT, INDEX_OUTPUT, ELEMENT_OUTPUT, COMPLETED_OUTPUT));
        requirePin(node.instanceId(), pins, ENTRY_INPUT, CatalogNodeDescriptor.Direction.INPUT, EXECUTION_TYPE);
        requirePin(node.instanceId(), pins, BODY_OUTPUT, CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION_TYPE);
        requirePin(node.instanceId(), pins, DONE_OUTPUT, CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION_TYPE);
        requirePin(node.instanceId(), pins, INDEX_OUTPUT, CatalogNodeDescriptor.Direction.OUTPUT, NUMBER_TYPE);
        requirePin(node.instanceId(), pins, COMPLETED_OUTPUT, CatalogNodeDescriptor.Direction.OUTPUT, BOOLEAN_TYPE);
        if (operation.equals("loop_count")) {
            requirePin(node.instanceId(), pins, COUNT_INPUT, CatalogNodeDescriptor.Direction.INPUT, NUMBER_TYPE);
            return new LoopScope(node.instanceId(), CompiledExecutionStep.LoopControl.Kind.COUNT, COUNT_INPUT, null, NUMBER_TYPE, null);
        }
        CatalogNodeDescriptor.Pin source = requirePin(node.instanceId(), pins, LIST_INPUT, CatalogNodeDescriptor.Direction.INPUT, null);
        if (!(source.type() instanceof TypeExpr.ListType listType)) {
            throw invalidLoop(node.instanceId(), "List Input Must Use A List Type");
        }
        requirePin(node.instanceId(), pins, ELEMENT_OUTPUT, CatalogNodeDescriptor.Direction.OUTPUT, listType.element());
        return new LoopScope(node.instanceId(), CompiledExecutionStep.LoopControl.Kind.FOR_EACH, LIST_INPUT, ELEMENT_OUTPUT,
            source.type(), listType.element());
    }

    private static Map<PinId, CatalogNodeDescriptor.Pin> exactPins(NodeInstanceId nodeId, CatalogNodeDescriptor definition,
                                                                   Set<PinId> expected) {
        LinkedHashMap<PinId, CatalogNodeDescriptor.Pin> pins = new LinkedHashMap<>();
        for (CatalogNodeDescriptor.Pin pin : definition.pins()) {
            if (pins.put(pin.id(), pin) != null) {
                throw invalidLoop(nodeId, "Duplicate Pin " + pin.id().canonicalText());
            }
        }
        if (!pins.keySet().equals(expected)) {
            throw invalidLoop(nodeId, "Pin Roles Do Not Match The Compiled Loop Contract");
        }
        return pins;
    }

    private static CatalogNodeDescriptor.Pin requirePin(NodeInstanceId nodeId, Map<PinId, CatalogNodeDescriptor.Pin> pins, PinId id,
                                                         CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        CatalogNodeDescriptor.Pin pin = pins.get(id);
        if (pin == null) {
            throw invalidLoop(nodeId, "Missing " + id.canonicalText() + " Pin");
        }
        if (pin.direction() != direction || type != null && !pin.type().equals(type)) {
            throw invalidLoop(nodeId, id, "Invalid " + id.canonicalText() + " Pin");
        }
        return pin;
    }

    private static void deriveScope(LoopScope scope, GraphTopology topology) {
        Set<NodeInstanceId> loopReach = topology.executionReach(scope.controller, BODY_OUTPUT);
        Set<NodeInstanceId> doneReach = topology.executionReach(scope.controller, DONE_OUTPUT);
        if (loopReach.contains(scope.controller) || doneReach.contains(scope.controller)) {
            throw invalidLoopScope(scope.controller, "Controller Cannot Be In Its Body Or Done Scope");
        }
        if (loopReach.stream().anyMatch(doneReach::contains)) {
            throw invalidLoopScope(scope.controller, "Body And Done Scopes Converge");
        }
        LinkedHashSet<NodeInstanceId> body = new LinkedHashSet<>(loopReach);
        Set<NodeInstanceId> requiredData = topology.reverseDataDependencies(loopReach);
        Set<PinId> iterationOutputs = scope.elementOutput == null ? Set.of(INDEX_OUTPUT) : Set.of(INDEX_OUTPUT, scope.elementOutput);
        Set<NodeInstanceId> iterationData = topology.dataReach(scope.controller, iterationOutputs);
        iterationData.stream().filter(requiredData::contains).forEach(body::add);
        body.remove(scope.controller);
        scope.body = Set.copyOf(body);

        Set<NodeInstanceId> ancestors = topology.ancestors(scope.controller);
        for (GraphConnection connection : topology.connections) {
            boolean sourceInside = scope.body.contains(connection.source().nodeId());
            boolean targetInside = scope.body.contains(connection.target().nodeId());
            if (targetInside && !sourceInside) {
                if (topology.execution(connection)) {
                    if (!connection.source().nodeId().equals(scope.controller) || !connection.source().pinId().equals(BODY_OUTPUT)) {
                        throw invalidLoopScope(scope.controller, "Body Has External Execution Ingress");
                    }
                } else if (connection.source().nodeId().equals(scope.controller)) {
                    if (!connection.source().pinId().equals(INDEX_OUTPUT)
                        && !Objects.equals(connection.source().pinId(), scope.elementOutput)) {
                        throw invalidLoopScope(scope.controller, "Body Uses Non-Iteration Controller Data");
                    }
                } else if (!ancestors.contains(connection.source().nodeId())) {
                    throw invalidLoopScope(scope.controller, "Body Has A Late External Data Dependency");
                }
            }
            if (sourceInside && !targetInside) {
                throw invalidLoopScope(scope.controller, topology.execution(connection)
                    ? "Body Has External Execution Egress" : "Body Has External Data Egress");
            }
        }
        topology.requireIterationOutputsInside(scope.controller, scope.elementOutput, scope.body);
        topology.requireCompletedOutsideBody(scope.controller, scope.body, doneReach);
    }

    private static void validateScopeRelations(List<LoopScope> scopes) {
        for (int leftIndex = 0; leftIndex < scopes.size(); leftIndex++) {
            LoopScope left = scopes.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < scopes.size(); rightIndex++) {
                LoopScope right = scopes.get(rightIndex);
                boolean rightNested = left.body.contains(right.controller);
                boolean leftNested = right.body.contains(left.controller);
                boolean overlap = left.body.stream().anyMatch(right.body::contains);
                if (rightNested && leftNested
                    || rightNested && !left.body.containsAll(right.body)
                    || leftNested && !right.body.containsAll(left.body)
                    || overlap && !rightNested && !leftNested) {
                    throw invalidLoopScope(left.controller.compareTo(right.controller) <= 0 ? left.controller : right.controller,
                        "Loop Body Scopes Cross Or Partially Overlap");
                }
            }
        }
    }

    private static List<NodeInstanceId> topologicalOrder(GraphDocument graph, NodeInstanceId loopController) {
        Map<NodeInstanceId, Integer> indegree = new HashMap<>();
        Map<NodeInstanceId, List<NodeInstanceId>> outgoing = new HashMap<>();
        graph.nodes().forEach(node -> indegree.put(node.instanceId(), 0));
        graph.connections().stream().sorted(Comparator.comparing(GraphConnection::connectionId)).forEach(connection -> {
            indegree.compute(connection.target().nodeId(), (ignored, count) -> count + 1);
            outgoing.computeIfAbsent(connection.source().nodeId(), ignored -> new ArrayList<>()).add(connection.target().nodeId());
        });
        PriorityQueue<NodeInstanceId> ready = new PriorityQueue<>();
        indegree.entrySet().stream().filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey).forEach(ready::add);
        ArrayList<NodeInstanceId> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            NodeInstanceId node = ready.remove();
            order.add(node);
            outgoing.getOrDefault(node, List.of()).stream().sorted().forEach(target -> {
                int remaining = indegree.compute(target, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.add(target);
                }
            });
        }
        if (order.size() != graph.nodes().size()) {
            throw loopCycle(loopController, "Compiled Graph Contains A Cycle");
        }
        return List.copyOf(order);
    }

    private static LoopCompilationException invalidLoop(NodeInstanceId nodeId, String reason) {
        return new LoopCompilationException("GRAPH.PIN_UNRESOLVED", nodeId, null, reason);
    }

    private static LoopCompilationException invalidLoop(NodeInstanceId nodeId, PinId pinId, String reason) {
        return new LoopCompilationException("GRAPH.PIN_TYPE_MISMATCH", nodeId, pinId, reason);
    }

    private static LoopCompilationException invalidLoopScope(NodeInstanceId nodeId, String reason) {
        return new LoopCompilationException("GRAPH.LOOP_SCOPE_INVALID", nodeId, null, reason);
    }

    private static LoopCompilationException loopCycle(NodeInstanceId nodeId, String reason) {
        return new LoopCompilationException("GRAPH.LOOP_CYCLE", nodeId, null, reason);
    }

    private static ValidationResult loopValidation(LoopCompilationException failure) {
        String identity = failure.code + "\u0000" + failure.nodeId.canonicalText() + "\u0000"
            + (failure.pinId == null ? "" : failure.pinId.canonicalText()) + "\u0000" + failure.reason;
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("nodeInstanceId", failure.nodeId.canonicalText());
        if (failure.pinId != null) {
            evidence.put("pinId", failure.pinId.canonicalText());
        }
        evidence.put("reason", failure.reason);
        Diagnostic diagnostic = Diagnostic.builder(failure.code, DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(new ContractRef<>(new OwnerId("resync"), new OperationId("graph-validation")))
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
            .evidence(evidence)
            .build();
        return ValidationResult.of(List.of(diagnostic));
    }

    private static final class LoopScope {
        private final NodeInstanceId controller;
        private final CompiledExecutionStep.LoopControl.Kind kind;
        private final PinId sourceInput;
        private final PinId elementOutput;
        private final TypeExpr sourceType;
        private final TypeExpr elementType;
        private Set<NodeInstanceId> body = Set.of();

        private LoopScope(NodeInstanceId controller, CompiledExecutionStep.LoopControl.Kind kind, PinId sourceInput,
                          PinId elementOutput, TypeExpr sourceType, TypeExpr elementType) {
            this.controller = controller;
            this.kind = kind;
            this.sourceInput = sourceInput;
            this.elementOutput = elementOutput;
            this.sourceType = sourceType;
            this.elementType = elementType;
        }
    }

    private static final class LoopCompilationException extends IllegalArgumentException {
        private final String code;
        private final NodeInstanceId nodeId;
        private final PinId pinId;
        private final String reason;

        private LoopCompilationException(String code, NodeInstanceId nodeId, PinId pinId, String reason) {
            super("Invalid Compiled Loop " + nodeId.canonicalText() + ": " + reason);
            this.code = code;
            this.nodeId = nodeId;
            this.pinId = pinId;
            this.reason = reason;
        }
    }

    private static final class GraphTopology {
        private final List<GraphConnection> connections;
        private final Map<NodeInstanceId, CatalogNodeDescriptor> definitions;
        private final Map<NodeInstanceId, List<GraphConnection>> executionOutgoing = new HashMap<>();
        private final Map<NodeInstanceId, List<GraphConnection>> dataOutgoing = new HashMap<>();
        private final Map<NodeInstanceId, List<GraphConnection>> dataIncoming = new HashMap<>();
        private final Map<NodeInstanceId, List<GraphConnection>> incoming = new HashMap<>();

        private GraphTopology(GraphDocument graph, Map<NodeInstanceId, CatalogNodeDescriptor> definitions) {
            this.connections = graph.connections().stream().sorted(Comparator.comparing(GraphConnection::connectionId)).toList();
            this.definitions = definitions;
            for (GraphConnection connection : connections) {
                incoming.computeIfAbsent(connection.target().nodeId(), ignored -> new ArrayList<>()).add(connection);
                if (execution(connection)) {
                    executionOutgoing.computeIfAbsent(connection.source().nodeId(), ignored -> new ArrayList<>()).add(connection);
                } else {
                    dataOutgoing.computeIfAbsent(connection.source().nodeId(), ignored -> new ArrayList<>()).add(connection);
                    dataIncoming.computeIfAbsent(connection.target().nodeId(), ignored -> new ArrayList<>()).add(connection);
                }
            }
        }

        private boolean execution(GraphConnection connection) {
            return pinType(connection.source()).equals(EXECUTION_TYPE) && pinType(connection.target()).equals(EXECUTION_TYPE);
        }

        private TypeExpr pinType(GraphEndpoint endpoint) {
            return definitions.get(endpoint.nodeId()).pins().stream()
                .filter(pin -> pin.id().equals(endpoint.pinId()))
                .findFirst().orElseThrow().type();
        }

        private Set<NodeInstanceId> executionReach(NodeInstanceId controller, PinId output) {
            List<NodeInstanceId> seeds = connections.stream()
                .filter(this::execution)
                .filter(connection -> connection.source().nodeId().equals(controller) && connection.source().pinId().equals(output))
                .map(connection -> connection.target().nodeId())
                .distinct()
                .sorted()
                .toList();
            return reach(seeds, executionOutgoing);
        }

        private Set<NodeInstanceId> dataReach(NodeInstanceId controller, Set<PinId> outputs) {
            List<NodeInstanceId> seeds = connections.stream()
                .filter(connection -> !execution(connection))
                .filter(connection -> connection.source().nodeId().equals(controller) && outputs.contains(connection.source().pinId()))
                .map(connection -> connection.target().nodeId())
                .distinct()
                .sorted()
                .toList();
            return reach(seeds, dataOutgoing);
        }

        private Set<NodeInstanceId> reverseDataDependencies(Set<NodeInstanceId> seeds) {
            LinkedHashSet<NodeInstanceId> dependencies = new LinkedHashSet<>();
            ArrayDeque<NodeInstanceId> queue = new ArrayDeque<>(seeds.stream().sorted().toList());
            while (!queue.isEmpty()) {
                NodeInstanceId node = queue.removeFirst();
                for (GraphConnection connection : dataIncoming.getOrDefault(node, List.of())) {
                    NodeInstanceId source = connection.source().nodeId();
                    if (dependencies.add(source)) {
                        queue.addLast(source);
                    }
                }
            }
            return Set.copyOf(dependencies);
        }

        private Set<NodeInstanceId> ancestors(NodeInstanceId controller) {
            LinkedHashSet<NodeInstanceId> ancestors = new LinkedHashSet<>();
            ArrayDeque<NodeInstanceId> queue = new ArrayDeque<>();
            queue.add(controller);
            while (!queue.isEmpty()) {
                NodeInstanceId node = queue.removeFirst();
                for (GraphConnection connection : incoming.getOrDefault(node, List.of())) {
                    NodeInstanceId source = connection.source().nodeId();
                    if (ancestors.add(source)) {
                        queue.addLast(source);
                    }
                }
            }
            ancestors.remove(controller);
            return Set.copyOf(ancestors);
        }

        private void requireIterationOutputsInside(NodeInstanceId controller, PinId elementOutput, Set<NodeInstanceId> body) {
            Set<PinId> outputs = elementOutput == null ? Set.of(INDEX_OUTPUT) : Set.of(INDEX_OUTPUT, elementOutput);
            boolean outside = connections.stream()
                .filter(connection -> connection.source().nodeId().equals(controller) && outputs.contains(connection.source().pinId()))
                .anyMatch(connection -> !body.contains(connection.target().nodeId()));
            if (outside) {
                throw invalidLoopScope(controller, "Iteration Data Leaves The Body Scope");
            }
        }

        private void requireCompletedOutsideBody(NodeInstanceId controller, Set<NodeInstanceId> body, Set<NodeInstanceId> doneReach) {
            List<GraphConnection> completed = connections.stream()
                .filter(connection -> connection.source().nodeId().equals(controller)
                    && connection.source().pinId().equals(COMPLETED_OUTPUT))
                .toList();
            if (completed.stream().anyMatch(connection -> body.contains(connection.target().nodeId()))) {
                throw invalidLoopScope(controller, "Completed Output Cannot Enter The Iteration Body");
            }
            if (completed.isEmpty()) {
                return;
            }
            Set<NodeInstanceId> required = reverseDataDependencies(doneReach);
            Set<NodeInstanceId> reached = dataReach(controller, Set.of(COMPLETED_OUTPUT));
            HashSet<NodeInstanceId> allowed = new HashSet<>(doneReach);
            reached.stream().filter(required::contains).forEach(allowed::add);
            if (!allowed.containsAll(reached)) {
                throw invalidLoopScope(controller, "Completed Output Must Belong To The Done Scope");
            }
        }

        private static Set<NodeInstanceId> reach(List<NodeInstanceId> seeds,
                                                  Map<NodeInstanceId, List<GraphConnection>> outgoing) {
            LinkedHashSet<NodeInstanceId> reached = new LinkedHashSet<>();
            ArrayDeque<NodeInstanceId> queue = new ArrayDeque<>();
            for (NodeInstanceId seed : seeds) {
                if (reached.add(seed)) {
                    queue.addLast(seed);
                }
            }
            while (!queue.isEmpty()) {
                NodeInstanceId node = queue.removeFirst();
                for (GraphConnection connection : outgoing.getOrDefault(node, List.of())) {
                    NodeInstanceId target = connection.target().nodeId();
                    if (reached.add(target)) {
                        queue.addLast(target);
                    }
                }
            }
            return Set.copyOf(reached);
        }
    }

    private static CatalogBinding binding(CatalogSnapshot catalog) {
        return new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
    }

    private boolean matchesBinding(GraphDocument graph, CatalogSnapshot catalog) {
        CatalogBinding expected = binding(catalog);
        CatalogBinding actual = graph.catalogBinding();
        if (actual.generation() != expected.generation() || !actual.catalogChecksum().equals(expected.catalogChecksum())) {
            return false;
        }
        return runtimeManifest == null
            ? actual.bindingManifestHash().equals(expected.bindingManifestHash())
            : actual.bindingManifestHash().equals(runtimeManifest.bindingManifestHash());
    }
}
