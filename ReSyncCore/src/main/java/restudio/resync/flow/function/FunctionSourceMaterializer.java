package restudio.resync.flow.function;

import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FunctionSourceMaterializer {
    public static final String BOUNDARY = "function-source-v1";

    public Result materialize(FunctionSignature signature, GraphDocument graph, OpaqueData unknown) {
        if (signature == null || graph == null) {
            return Result.rejected(diagnostic("FUNCTION.SOURCE_MISSING", null, null, "source",
                "A typed Function signature and immutable graph source are required.", Map.of(
                    "boundary", BOUNDARY,
                    "signaturePresent", signature != null,
                    "graphPresent", graph != null)));
        }
        if (!signature.function().resource().equals(graph.resource())) {
            return Result.rejected(diagnostic("FUNCTION.RESOURCE_MISMATCH", signature, null, "identity",
                "The Function signature and graph source use different resource identities.", Map.of(
                    "signatureResource", signature.function().canonicalText(),
                    "graphResource", graph.resource().canonicalText())));
        }
        if (signature.revision().value() != graph.revision()) {
            return Result.rejected(diagnostic("FUNCTION.REVISION_MISMATCH", signature, null, "identity",
                "The Function signature and graph source use different revisions.", Map.of(
                    "signatureRevision", signature.revision().value(),
                    "graphRevision", graph.revision())));
        }
        if (!"function".equals(graph.resource().resourceType().value())) {
            return Result.rejected(diagnostic("FUNCTION.RESOURCE_TYPE_MISMATCH", signature, null, "identity",
                "The Function source resource must use the function resource type.", Map.of(
                    "resourceTypeOwner", graph.resource().type().owner().value(),
                    "resourceType", graph.resource().resourceType().value())));
        }
        FunctionSourceDocument source;
        try {
            source = new FunctionSourceDocument(signature, graph, unknown);
        } catch (RuntimeException failure) {
            return Result.rejected(diagnostic("FUNCTION.SOURCE_INVALID", signature, null, "source",
                "The immutable Function source could not be constructed from the typed contracts.", Map.of(
                    "boundary", BOUNDARY,
                    "failureType", failure.getClass().getName(),
                    "failure", failure.getMessage() == null ? "" : failure.getMessage())));
        }
        return validate(source);
    }

    public Result materialize(FunctionSignature signature, GraphDocument graph) {
        return materialize(signature, graph, OpaqueData.empty());
    }

    public Result validate(FunctionSourceDocument source) {
        if (source == null) {
            return Result.rejected(diagnostic("FUNCTION.SOURCE_MISSING", null, null, "source",
                "A typed immutable Function source is required.", Map.of("boundary", BOUNDARY)));
        }
        List<FunctionDiagnostic> diagnostics = new ArrayList<>();
        GraphDocument graph = source.graph();
        Set<?> nodeIds = graph.nodes().stream().map(GraphNode::instanceId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> connectionEndpoints = new HashSet<>();
        for (GraphConnection connection : graph.connections()) {
            String sourceEndpoint = endpointKey(connection.source());
            String targetEndpoint = endpointKey(connection.target());
            if (!nodeIds.contains(connection.source().nodeId()) || !nodeIds.contains(connection.target().nodeId())) {
                diagnostics.add(diagnostic("FUNCTION.BODY_ENDPOINT_MISSING", source.signature(), null, "body-validation",
                    "Every Function connection endpoint must reference an immutable body node.", Map.of(
                        "source", sourceEndpoint,
                        "target", targetEndpoint)));
            }
            String connectionKey = sourceEndpoint + "\u0000" + targetEndpoint;
            if (!connectionEndpoints.add(connectionKey)) {
                diagnostics.add(diagnostic("FUNCTION.BODY_CONNECTION_AMBIGUOUS", source.signature(), null, "body-validation",
                    "Duplicate Function connection endpoints cannot be materialized deterministically.", Map.of(
                        "connection", connectionKey)));
            }
        }
        graph.nodes().forEach(node -> validateNode(source.signature(), node, diagnostics));
        graph.variables().forEach(variable -> validateValue(source.signature(), "variable:" + variable.name(), variable.value(), diagnostics));
        graph.functions().forEach(function -> validateNestedFunction(source.signature(), graph, function, diagnostics));
        return diagnostics.isEmpty() ? Result.accepted(source) : Result.rejected(diagnostics);
    }

    private static void validateNode(FunctionSignature signature, GraphNode node, List<FunctionDiagnostic> diagnostics) {
        node.values().forEach((pin, value) -> validatePinValue(signature, "node:" + node.instanceId().canonicalText() + ":pin:" + pin.canonicalText(), value, diagnostics));
        node.inspector().forEach((pin, value) -> validatePinValue(signature, "node:" + node.instanceId().canonicalText() + ":inspector-pin:" + pin.canonicalText(), value, diagnostics));
        node.inspectorFields().forEach((field, value) -> validateValue(signature, "node:" + node.instanceId().canonicalText() + ":field:" + field.canonicalText(), value, diagnostics));
        validateInspectorState(signature, node.instanceId().canonicalText(), node.inspectorState(), diagnostics);
        node.branches().forEach(branch -> validateBranch(signature, node.instanceId().canonicalText(), branch, diagnostics));
        node.repeatables().forEach(repeatable -> repeatable.elements().forEach(element -> element.values().forEach((pin, value) ->
            validatePinValue(signature, "node:" + node.instanceId().canonicalText() + ":element:" + element.elementId().canonicalText() + ":pin:" + pin.canonicalText(), value, diagnostics))));
    }

    private static void validateBranch(FunctionSignature signature, String nodeId, BranchBinding branch, List<FunctionDiagnostic> diagnostics) {
        for (BranchCase branchCase : branch.cases()) {
            branchCase.values().forEach((pin, value) -> validatePinValue(signature,
                "node:" + nodeId + ":branch:" + branch.branchId().canonicalText() + ":case:" + branchCase.caseId().canonicalText() + ":pin:" + pin.canonicalText(), value, diagnostics));
            validateInspectorState(signature, "node:" + nodeId + ":case:" + branchCase.caseId().canonicalText(), branchCase.inspectorState(), diagnostics);
        }
    }

    private static void validateInspectorState(FunctionSignature signature, String scope, InspectorState state, List<FunctionDiagnostic> diagnostics) {
        state.fields().forEach((field, value) -> validateValue(signature, scope + ":field:" + field.canonicalText(), value, diagnostics));
        state.legacyFields().forEach((pin, value) -> validatePinValue(signature, scope + ":pin:" + pin.canonicalText(), value, diagnostics));
    }

    private static void validatePinValue(FunctionSignature signature, String scope, PinValue value, List<FunctionDiagnostic> diagnostics) {
        validateValue(signature, scope, value.value(), diagnostics);
    }

    private static void validateValue(FunctionSignature signature, String scope, TypedValue value, List<FunctionDiagnostic> diagnostics) {
        if (value != null && value.state() == TypedValue.State.OPAQUE) {
            diagnostics.add(diagnostic("FUNCTION.TYPED_VALUE_OPAQUE", signature, null, "typed-value",
                "Opaque typed material cannot be bound to an executable Function body.", Map.of(
                    "scope", scope,
                    "type", value.type().canonicalJson())));
        }
    }

    private static void validateNestedFunction(FunctionSignature signature, GraphDocument graph, FunctionBinding function,
                                               List<FunctionDiagnostic> diagnostics) {
        if (!"function".equals(function.function().resourceType().value())) {
            diagnostics.add(diagnostic("FUNCTION.NESTED_RESOURCE_TYPE_MISMATCH", signature, null, "nested-function",
                "Nested Function bindings must use the function resource type.", Map.of(
                    "function", function.function().canonicalText(),
                    "resourceType", function.function().resourceType().value())));
        }
        if (!graph.resource().serverId().equals(function.function().serverId())) {
            diagnostics.add(diagnostic("FUNCTION.NESTED_SERVER_MISMATCH", signature, null, "nested-function",
                "Nested Function bindings must remain within the source server identity.", Map.of(
                    "sourceServer", graph.resource().serverId().canonicalText(),
                    "nestedServer", function.function().serverId().canonicalText())));
        }
        function.inputs().forEach(parameter -> validateParameter(signature, "nested-input", parameter, diagnostics));
        function.outputs().forEach(parameter -> validateParameter(signature, "nested-output", parameter, diagnostics));
    }

    private static void validateParameter(FunctionSignature signature, String scope, FunctionParameter parameter,
                                          List<FunctionDiagnostic> diagnostics) {
        validateValue(signature, scope + ":" + parameter.parameterId().canonicalText(), parameter.defaultValue(), diagnostics);
    }

    private static String endpointKey(GraphEndpoint endpoint) {
        StringBuilder value = new StringBuilder(endpoint.nodeId().canonicalText())
            .append('\u0000').append(endpoint.pinId().canonicalText());
        if (endpoint.elementId() != null) {
            value.append('\u0000').append(endpoint.elementId().canonicalText());
        }
        if (endpoint.branchId() != null) {
            value.append('\u0000').append(endpoint.branchId().canonicalText());
        }
        return value.toString();
    }

    private static FunctionDiagnostic diagnostic(String code, FunctionSignature signature, FunctionParameterId parameterId,
                                                 String stage, String message, Map<String, ?> evidence) {
        FunctionLocator function = signature == null ? null : signature.function();
        FunctionRevision revision = signature == null ? null : signature.revision();
        String identity = code + "\u0000" + (function == null ? "" : function.canonicalText()) + "\u0000"
            + (revision == null ? "" : revision.canonicalText()) + "\u0000" + stage + "\u0000" + evidence;
        return new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.SEMANTIC, stage,
            message, "Regenerate the typed Function source and provide an authoritative runtime binding before activation.",
            function, revision, parameterId, UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), null, Map.of(),
            Map.copyOf(evidence), true);
    }

    public record Result(FunctionSourceDocument source, List<FunctionDiagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Function Source Diagnostics Are Required"));
            if (source != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("An Accepted Function Source Cannot Contain Diagnostics");
            }
            if (source == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException("A Rejected Function Source Requires Diagnostics");
            }
        }

        public static Result accepted(FunctionSourceDocument source) {
            return new Result(Objects.requireNonNull(source, "Function Source Is Required"), List.of());
        }

        public static Result rejected(FunctionDiagnostic diagnostic) {
            return new Result(null, List.of(Objects.requireNonNull(diagnostic, "Function Source Diagnostic Is Required")));
        }

        public static Result rejected(List<FunctionDiagnostic> diagnostics) {
            return new Result(null, diagnostics);
        }

        public boolean materialized() {
            return source != null;
        }
    }
}
