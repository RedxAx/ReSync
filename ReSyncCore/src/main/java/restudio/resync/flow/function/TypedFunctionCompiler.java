package restudio.resync.flow.function;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;

public final class TypedFunctionCompiler {
    public static final String BOUNDARY = "typed-function-compiler-v1";

    private final int maximumSteps;
    private final FunctionSourceMaterializer materializer;

    public TypedFunctionCompiler() {
        this(CompiledFunctionBody.MAXIMUM_STEPS);
    }

    public TypedFunctionCompiler(int maximumSteps) {
        if (maximumSteps < 0 || maximumSteps > CompiledFunctionBody.MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Typed Function Compiler Maximum Step Count Is Invalid");
        }
        this.maximumSteps = maximumSteps;
        materializer = new FunctionSourceMaterializer();
    }

    public Result compile(FunctionSourceDocument source, TypedFunctionCapabilitySet capabilities) {
        if (source == null || capabilities == null) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_INPUT_MISSING", source, "source",
                "A typed Function source and capability set are required.", Map.of(
                    "boundary", BOUNDARY,
                    "sourcePresent", source != null,
                    "capabilitiesPresent", capabilities != null)));
        }
        FunctionSourceMaterializer.Result sourceResult = materializer.validate(source);
        if (!sourceResult.materialized()) {
            return Result.rejected(sourceResult.diagnostics());
        }
        GraphDocument graph = source.graph();
        if (!graph.catalogBinding().equals(capabilities.catalogBinding())) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_CATALOG_MISMATCH", source, "catalog-binding",
                "Typed Function capabilities must use the graph catalog binding.", Map.of(
                    "graphBinding", bindingValue(graph),
                    "capabilityBinding", bindingValue(capabilities))));
        }
        if (!capabilities.grantedCapabilities().containsAll(graph.requiredCapabilities())) {
            Set<ContractRef<CapabilityId>> missing = new HashSet<>(graph.requiredCapabilities());
            missing.removeAll(capabilities.grantedCapabilities());
            return Result.rejected(diagnostic("FUNCTION.COMPILER_CAPABILITY_MISSING", source, "capability",
                "The typed Function capability set does not grant every graph capability.", Map.of(
                    "missing", missing.stream().sorted().map(ContractRef::canonicalText).toList())));
        }
        if (!graph.functions().isEmpty()) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_NESTED_UNSUPPORTED", source, "nested-function",
                "Nested Function calls require an explicit typed frame binding and cannot be inferred from graph data.", Map.of(
                    "count", graph.functions().size())));
        }
        if (!graph.variables().isEmpty()) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_VARIABLES_UNSUPPORTED", source, "variable",
                "Graph variables require an explicit typed execution binding and cannot be inferred from graph data.", Map.of(
                    "count", graph.variables().size())));
        }
        Map<NodeInstanceId, TypedFunctionNodeCapability> byNode = capabilities.byNode();
        List<FunctionDiagnostic> diagnostics = validateNodes(source, graph, capabilities, byNode);
        if (!diagnostics.isEmpty()) {
            return Result.rejected(diagnostics);
        }
        List<TypedFunctionNodeCapability> ordered = byNode.values().stream()
            .sorted(Comparator.comparingInt(TypedFunctionNodeCapability::order)
                .thenComparing(TypedFunctionNodeCapability::nodeId)).toList();
        List<CompiledFunctionBody.Step> steps = new ArrayList<>(ordered.size());
        for (TypedFunctionNodeCapability capability : ordered) {
            GraphNode node = graph.nodes().stream().filter(value -> value.instanceId().equals(capability.nodeId())).findFirst().orElseThrow();
            try {
                CompiledFunctionBody.Step step = capability.compiler().compile(node);
                if (step == null) {
                    return Result.rejected(diagnostic("FUNCTION.COMPILER_STEP_MISSING", source, "step",
                        "A typed Function capability did not provide an executable step.", Map.of(
                            "nodeId", capability.nodeId().canonicalText(),
                            "binding", capability.binding().canonical())));
                }
                steps.add(step);
            } catch (Throwable failure) {
                return Result.rejected(diagnostic("FUNCTION.COMPILER_STEP_FAILURE", source, "step",
                    "A typed Function capability failed while producing its executable step.", Map.of(
                        "nodeId", capability.nodeId().canonicalText(),
                        "exceptionType", failure.getClass().getName())));
            }
        }
        if (steps.size() > maximumSteps) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_BODY_LIMIT", source, "body",
                "The typed Function body exceeds the compiler execution budget.", Map.of(
                    "stepCount", steps.size(),
                    "maximumSteps", maximumSteps)));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("boundary", BOUNDARY);
        metadata.put("capabilityFingerprint", capabilities.fingerprint().canonicalText());
        metadata.put("catalogBinding", bindingValue(graph));
        metadata.put("connectionCount", graph.connections().size());
        metadata.put("nodeCount", graph.nodes().size());
        metadata.put("sourceChecksum", source.checksum().canonicalText());
        try {
            CompiledFunctionBody body = new CompiledFunctionBody(steps, maximumSteps, metadata);
            return Result.accepted(new CompiledFunction(source.signature(), body));
        } catch (RuntimeException failure) {
            return Result.rejected(diagnostic("FUNCTION.COMPILER_BODY_INVALID", source, "body",
                "The typed Function body could not be assembled as an immutable executable contract.", Map.of(
                    "exceptionType", failure.getClass().getName())));
        }
    }

    public Result compile(FunctionSignature signature, GraphDocument graph, TypedFunctionCapabilitySet capabilities) {
        FunctionSourceMaterializer.Result source = materializer.materialize(signature, graph);
        return source.materialized() ? compile(source.source(), capabilities) : Result.rejected(source.diagnostics());
    }

    public int maximumSteps() {
        return maximumSteps;
    }

    private static List<FunctionDiagnostic> validateNodes(FunctionSourceDocument source, GraphDocument graph,
                                                           TypedFunctionCapabilitySet capabilities,
                                                           Map<NodeInstanceId, TypedFunctionNodeCapability> byNode) {
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        Set<NodeInstanceId> graphNodeIds = graph.nodes().stream().map(GraphNode::instanceId).collect(Collectors.toSet());
        for (GraphNode node : graph.nodes()) {
            TypedFunctionNodeCapability capability = byNode.get(node.instanceId());
            if (capability == null) {
                diagnostics.add(diagnostic("FUNCTION.COMPILER_NODE_CAPABILITY_MISSING", source, "capability",
                    "Every typed Function node requires an explicit capability binding.", Map.of(
                        "nodeId", node.instanceId().canonicalText())));
                continue;
            }
            if (!node.definition().equals(capability.definition()) || node.definitionVersion() != capability.definitionVersion()) {
                diagnostics.add(diagnostic("FUNCTION.COMPILER_NODE_IDENTITY_MISMATCH", source, "capability",
                    "A typed Function capability does not match its graph node definition identity.", Map.of(
                        "nodeId", node.instanceId().canonicalText(),
                        "graphDefinition", node.definition().canonicalText(),
                        "capabilityDefinition", capability.definition().canonicalText(),
                        "graphVersion", node.definitionVersion(),
                        "capabilityVersion", capability.definitionVersion())));
            }
            if (!capabilities.grantedCapabilities().contains(capability.capability())) {
                diagnostics.add(diagnostic("FUNCTION.COMPILER_NODE_CAPABILITY_UNGRANTED", source, "capability",
                    "A typed Function node capability is not present in the granted capability set.", Map.of(
                        "nodeId", node.instanceId().canonicalText(),
                        "capability", capability.capability().canonicalText())));
            }
            if (capability.order() >= graph.nodes().size()) {
                diagnostics.add(diagnostic("FUNCTION.COMPILER_ORDER_INVALID", source, "order",
                    "Typed Function execution order must be a contiguous body order.", Map.of(
                        "nodeId", node.instanceId().canonicalText(),
                        "order", capability.order(),
                        "nodeCount", graph.nodes().size())));
            }
        }
        byNode.keySet().stream().filter(nodeId -> !graphNodeIds.contains(nodeId)).forEach(nodeId ->
            diagnostics.add(diagnostic("FUNCTION.COMPILER_NODE_EXTRA", source, "capability",
                "The typed Function capability set contains a node absent from the graph source.", Map.of(
                    "nodeId", nodeId.canonicalText()))));
        List<TypedFunctionNodeCapability> ordered = byNode.values().stream()
            .sorted(Comparator.comparingInt(TypedFunctionNodeCapability::order)
                .thenComparing(TypedFunctionNodeCapability::nodeId)).toList();
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).order() != index) {
                diagnostics.add(diagnostic("FUNCTION.COMPILER_ORDER_AMBIGUOUS", source, "order",
                    "Typed Function execution order must not be inferred from graph insertion order.", Map.of(
                        "expected", index,
                        "actual", ordered.get(index).order(),
                        "nodeId", ordered.get(index).nodeId().canonicalText())));
                break;
            }
        }
        Map<ConnectionId, NodeInstanceId> owners = new HashMap<>();
        for (TypedFunctionNodeCapability capability : ordered) {
            for (ConnectionId connection : capability.handledConnections()) {
                NodeInstanceId previous = owners.putIfAbsent(connection, capability.nodeId());
                if (previous != null) {
                    diagnostics.add(diagnostic("FUNCTION.COMPILER_CONNECTION_AMBIGUOUS", source, "connection",
                        "Each typed Function connection must be owned by exactly one capability binding.", Map.of(
                            "connectionId", connection.canonicalText(),
                            "firstNodeId", previous.canonicalText(),
                            "secondNodeId", capability.nodeId().canonicalText())));
                }
            }
        }
        Set<ConnectionId> graphConnections = graph.connections().stream().map(GraphConnection::connectionId).collect(Collectors.toSet());
        Set<ConnectionId> unhandled = new HashSet<>(graphConnections);
        unhandled.removeAll(owners.keySet());
        if (!unhandled.isEmpty()) {
            diagnostics.add(diagnostic("FUNCTION.COMPILER_CONNECTION_UNHANDLED", source, "connection",
                "Every typed Function connection requires an explicit capability route.", Map.of(
                    "connectionIds", unhandled.stream().sorted().map(ConnectionId::canonicalText).toList())));
        }
        owners.keySet().stream().filter(connection -> !graphConnections.contains(connection)).forEach(connection ->
            diagnostics.add(diagnostic("FUNCTION.COMPILER_CONNECTION_EXTRA", source, "connection",
                "A typed Function capability route references a connection absent from the graph source.", Map.of(
                    "connectionId", connection.canonicalText()))));
        return List.copyOf(diagnostics);
    }

    private static Map<String, Object> bindingValue(GraphDocument graph) {
        return Map.of(
            "bindingManifestHash", graph.catalogBinding().bindingManifestHash().canonicalText(),
            "catalogChecksum", graph.catalogBinding().catalogChecksum().canonicalText(),
            "generation", graph.catalogBinding().generation());
    }

    private static Map<String, Object> bindingValue(TypedFunctionCapabilitySet capabilities) {
        return Map.of(
            "bindingManifestHash", capabilities.catalogBinding().bindingManifestHash().canonicalText(),
            "catalogChecksum", capabilities.catalogBinding().catalogChecksum().canonicalText(),
            "generation", capabilities.catalogBinding().generation());
    }

    private static FunctionDiagnostic diagnostic(String code, FunctionSourceDocument source, String stage,
                                                 String message, Map<String, ?> evidence) {
        FunctionSignature signature = source == null ? null : source.signature();
        FunctionLocator function = signature == null ? null : signature.function();
        FunctionRevision revision = signature == null ? null : signature.revision();
        Map<String, Object> normalized = FunctionContractSupport.immutableMap(evidence, "Typed Function Compiler Evidence");
        String identity = CanonicalJson.canonicalize(Map.of(
            "code", code,
            "evidence", normalized,
            "function", function == null ? "" : function.canonicalText(),
            "revision", revision == null ? "" : revision.canonicalText(),
            "stage", stage));
        return new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.SEMANTIC, stage, message,
            "Provide an explicit typed Function capability binding and retry compilation.", function, revision, null,
            UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), null, Map.of(), normalized, true);
    }

    public record Result(CompiledFunction function, List<FunctionDiagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Typed Function Compiler Diagnostics Are Required"));
            if (function != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("An Accepted Typed Function Compilation Cannot Contain Diagnostics");
            }
            if (function == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException("A Rejected Typed Function Compilation Requires Diagnostics");
            }
        }

        public static Result accepted(CompiledFunction function) {
            return new Result(Objects.requireNonNull(function, "Compiled Function Is Required"), List.of());
        }

        public static Result rejected(FunctionDiagnostic diagnostic) {
            return new Result(null, List.of(Objects.requireNonNull(diagnostic, "Typed Function Compiler Diagnostic Is Required")));
        }

        public static Result rejected(List<FunctionDiagnostic> diagnostics) {
            return new Result(null, diagnostics);
        }

        public boolean compiled() {
            return function != null;
        }
    }
}
