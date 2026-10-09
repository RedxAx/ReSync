package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.CanonicalText;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface FlowExecutionBridge {
    CompletionStage<Result> execute(Context context);

    record Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
                   MappingContext mappingContext, CompiledGraphMetadata compiledGraphMetadata,
                   CompiledRuntimeContext compiledRuntimeContext,
                   CorrelationId invocationId, long requestedDeadlineMillis, GraphDocument sourceDocument) {
        public Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables) {
            this(graph, startNodeId, player, event, eventVariables, null, null,
                legacyContext(player, event, eventVariables), CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
                       MappingContext mappingContext) {
            this(graph, startNodeId, player, event, eventVariables, mappingContext, null,
                legacyContext(player, event, eventVariables), CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
                       MappingContext mappingContext, CompiledGraphMetadata compiledGraphMetadata) {
            this(graph, startNodeId, player, event, eventVariables, mappingContext, compiledGraphMetadata,
                legacyContext(player, event, eventVariables), CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, MappingContext mappingContext, CompiledGraphMetadata compiledGraphMetadata,
                       CompiledRuntimeContext compiledRuntimeContext) {
            this(graph, startNodeId, null, null, Map.of(), mappingContext, compiledGraphMetadata,
                compiledRuntimeContext, CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, MappingContext mappingContext,
                       CompiledGraphMetadata compiledGraphMetadata, CompiledRuntimeContext compiledRuntimeContext,
                       CorrelationId invocationId) {
            this(graph, startNodeId, null, null, Map.of(), mappingContext, compiledGraphMetadata,
                compiledRuntimeContext, invocationId, RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
                       MappingContext mappingContext, CompiledGraphMetadata compiledGraphMetadata,
                       CompiledRuntimeContext compiledRuntimeContext, CorrelationId invocationId) {
            this(graph, startNodeId, player, event, eventVariables, mappingContext, compiledGraphMetadata,
                compiledRuntimeContext, invocationId, RuntimeExecutionContext.NO_DEADLINE);
        }

        public Context(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVariables,
                       MappingContext mappingContext, CompiledGraphMetadata compiledGraphMetadata,
                       CompiledRuntimeContext compiledRuntimeContext, CorrelationId invocationId, long requestedDeadlineMillis) {
            this(graph, startNodeId, player, event, eventVariables, mappingContext, compiledGraphMetadata,
                compiledRuntimeContext, invocationId, requestedDeadlineMillis, null);
        }

        public Context {
            Objects.requireNonNull(graph, "Legacy Flow Graph Is Required");
            invocationId = Objects.requireNonNull(invocationId, "Invocation ID Is Required");
            if (requestedDeadlineMillis < 0) {
                throw new IllegalArgumentException("Requested Deadline Cannot Be Negative");
            }
            if (mappingContext != null && compiledGraphMetadata != null
                && (!mappingContext.resource().equals(compiledGraphMetadata.resource())
                    || !mappingContext.catalogBinding().equals(compiledGraphMetadata.catalogBinding())
                    || !mappingContext.snapshotId().equals(compiledGraphMetadata.snapshotId()))) {
                throw new IllegalArgumentException("Compiled mapping context must match compiled graph metadata");
            }
            if (sourceDocument != null && (compiledGraphMetadata == null || compiledRuntimeContext == null
                || !sourceDocument.resource().equals(compiledGraphMetadata.resource())
                || !sourceDocument.catalogBinding().equals(compiledGraphMetadata.catalogBinding())
                || !sourceDocument.resource().id().equals(graph.getId())
                || !sourceDocument.resource().resourceType().value().equals(graph.getResourceType())
                || sourceDocument.revision() != graph.getResourceRevision())) {
                throw new IllegalArgumentException("Typed Source Must Match The Compiled Execution Identity");
            }
            if (compiledRuntimeContext != null && (player != null || event != null
                || (eventVariables != null && !eventVariables.isEmpty()))) {
                throw new IllegalArgumentException("Typed compiled runtime context cannot carry legacy execution context");
            }
        }

        public Optional<MappingContext> optionalMappingContext() {
            return Optional.ofNullable(mappingContext);
        }

        public Optional<CompiledGraphMetadata> optionalCompiledGraphMetadata() {
            return Optional.ofNullable(compiledGraphMetadata);
        }

        public Optional<CompiledRuntimeContext> optionalCompiledRuntimeContext() {
            return Optional.ofNullable(compiledRuntimeContext);
        }

        public Optional<String> mappingFailure() {
            if (mappingContext == null) {
                return Optional.of("Complete compiled Core mapping context is required");
            }
            return sourceDocument == null ? mappingContext.validationFailure(graph, startNodeId)
                : mappingContext.validationFailure(sourceDocument, startNodeId);
        }

        private static CompiledRuntimeContext legacyContext(Player player, Event event, Map<String, Object> eventVariables) {
            return player == null && event == null && (eventVariables == null || eventVariables.isEmpty())
                ? CompiledRuntimeContext.empty() : null;
        }
    }

    record MappingContext(
        ServerResourceLocator resourceLocator,
        CatalogBinding catalogBinding,
        SnapshotId snapshotId,
        Map<String, NodeId> nodeMappings,
        Map<String, PinId> pinMappings,
        List<TypeReference> conversionPolicyReferences,
        Map<ConnectionId, GraphConnection> connectionMappings
    ) {
        public MappingContext(ServerResourceLocator resourceLocator, CatalogBinding catalogBinding, SnapshotId snapshotId,
                              Map<String, NodeId> nodeMappings, Map<String, PinId> pinMappings,
                              List<TypeReference> conversionPolicyReferences) {
            this(resourceLocator, catalogBinding, snapshotId, nodeMappings, pinMappings, conversionPolicyReferences, Map.of());
        }

        public MappingContext {
            resourceLocator = Objects.requireNonNull(resourceLocator, "Canonical Resource Locator Is Required");
            catalogBinding = Objects.requireNonNull(catalogBinding, "Catalog Binding Is Required");
            snapshotId = Objects.requireNonNull(snapshotId, "Snapshot Identity Is Required");
            nodeMappings = immutableMapping(nodeMappings, "Node Mapping", NodeId.class);
            pinMappings = immutableMapping(pinMappings, "Pin Mapping", PinId.class);
            conversionPolicyReferences = immutableConversionReferences(conversionPolicyReferences);
            connectionMappings = Map.copyOf(Objects.requireNonNull(connectionMappings, "Connection Mappings Are Required"));
            connectionMappings.forEach((id, connection) -> {
                if (!id.equals(connection.connectionId())) {
                    throw new IllegalArgumentException("Connection Mapping Must Preserve Its Persisted Identity");
                }
            });
        }

        public ServerResourceLocator resource() {
            return resourceLocator;
        }

        public List<TypeReference> conversionReferences() {
            return conversionPolicyReferences;
        }

        public Optional<String> validationFailure(FlowGraph graph, String startNodeId) {
            Objects.requireNonNull(graph, "Legacy Flow Graph Is Required");
            if (startNodeId == null || startNodeId.isBlank() || !startNodeId.equals(startNodeId.strip())) {
                return Optional.of("A non-blank start node ID is required");
            }
            Map<String, ?> graphNodes = graph.getNodes();
            if (!graphNodes.containsKey(startNodeId)) {
                return Optional.of("The requested start node is not present in the graph");
            }
            if (!nodeMappings.keySet().equals(graphNodes.keySet())) {
                return Optional.of("Node mappings must exactly cover every legacy graph node");
            }
            for (Map.Entry<String, ?> entry : graphNodes.entrySet()) {
                if (entry.getValue() == null) {
                    return Optional.of("The graph contains a null node");
                }
            }
            for (var connection : graph.getConnections()) {
                if (connection == null) {
                    return Optional.of("The graph contains a null connection");
                }
                if (!nodeMappings.containsKey(connection.getSourceNodeId())
                    || !nodeMappings.containsKey(connection.getTargetNodeId())) {
                    return Optional.of("Connection endpoints must have node mappings");
                }
                if (connection.getSourcePin() == null || connection.getTargetPin() == null
                    || !pinMappings.containsKey(pinMappingKey(connection.getSourceNodeId(), connection.getSourcePin()))
                    || !pinMappings.containsKey(pinMappingKey(connection.getTargetNodeId(), connection.getTargetPin()))) {
                    return Optional.of("Connection pins must have pin mappings");
                }
            }
            return Optional.empty();
        }

        public Optional<String> validationFailure(GraphDocument document, String startNodeId) {
            Objects.requireNonNull(document, "Typed Graph Source Is Required");
            if (startNodeId == null || startNodeId.isBlank() || !startNodeId.equals(startNodeId.strip())) {
                return Optional.of("A non-blank start node ID is required");
            }
            if (!resourceLocator.equals(document.resource()) || !catalogBinding.equals(document.catalogBinding())) {
                return Optional.of("The typed source must match its compiled mapping identity");
            }
            Set<String> nodes = new HashSet<>();
            for (var node : document.nodes()) {
                String id = node.instanceId().canonicalText();
                nodes.add(id);
                if (!node.definition().id().equals(nodeMappings.get(id))) {
                    return Optional.of("Node mappings must match each typed node definition");
                }
            }
            if (!nodes.contains(startNodeId) || !nodes.equals(nodeMappings.keySet())) {
                return Optional.of("Node mappings must cover the typed graph and requested start node");
            }
            if (!connectionMappings.isEmpty() && connectionMappings.size() != document.connections().size()) {
                return Optional.of("Connection mappings must exactly cover the typed graph");
            }
            for (var connection : document.connections()) {
                boolean structural = connection.source().branchId() != null || connection.source().elementId() != null
                    || connection.target().branchId() != null || connection.target().elementId() != null;
                if ((structural || !connectionMappings.isEmpty()) && !connection.equals(connectionMappings.get(connection.connectionId()))) {
                    return Optional.of("Connection mappings must preserve every typed endpoint and connection identity");
                }
                if (!pinMappings.containsKey(pinMappingKey(connection.source().nodeId().canonicalText(), connection.source().pinId().value()))
                    || !pinMappings.containsKey(pinMappingKey(connection.target().nodeId().canonicalText(), connection.target().pinId().value()))) {
                    return Optional.of("Connection pins must have typed pin mappings");
                }
            }
            return Optional.empty();
        }

        private static <T> Map<String, T> immutableMapping(Map<?, ?> values, String name, Class<T> valueType) {
            Objects.requireNonNull(values, name + "s Are Required");
            var entries = new ArrayList<Map.Entry<String, T>>(values.size());
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(name + " Keys Must Be Strings");
                }
                requireMappingKey(key, name);
                if (!valueType.isInstance(entry.getValue())) {
                    throw new IllegalArgumentException(name + " Values Must Be " + valueType.getSimpleName());
                }
                entries.add(Map.entry(key, valueType.cast(entry.getValue())));
            }
            entries.sort(Map.Entry.comparingByKey(CanonicalText.ORDER));
            var immutable = new LinkedHashMap<String, T>(entries.size());
            entries.forEach(entry -> immutable.put(entry.getKey(), entry.getValue()));
            return Collections.unmodifiableMap(immutable);
        }

        private static List<TypeReference> immutableConversionReferences(List<?> values) {
            Objects.requireNonNull(values, "Conversion Policy References Are Required");
            var references = new ArrayList<TypeReference>(values.size());
            Set<TypeReference> unique = new HashSet<>();
            for (Object value : values) {
                if (!(value instanceof TypeReference reference)) {
                    throw new IllegalArgumentException("Conversion Policy References Must Be Typed References");
                }
                if (!unique.add(reference)) {
                    throw new IllegalArgumentException("Conversion Policy References Must Be Unique");
                }
                references.add(reference);
            }
            references.sort(CanonicalText.orderBy(TypeReference::canonicalKey));
            return List.copyOf(references);
        }

        private static void requireMappingKey(String value, String name) {
            if (value.isEmpty() || value.isBlank() || !value.equals(value.strip()) || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(name + " Keys Must Be Non-Blank Canonical Text");
            }
        }

        static String pinMappingKey(String nodeId, String pinId) {
            requireMappingKey(nodeId, "Node Mapping");
            requireMappingKey(pinId, "Pin Mapping");
            return nodeId + "/" + pinId;
        }
    }

    record Result(Status status, String reason, Throwable failure, List<Diagnostic> diagnostics) {
        public Result {
            status = Objects.requireNonNull(status, "Flow Execution Bridge Status Is Required");
            reason = reason != null ? reason.trim() : "";
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Flow Execution Bridge Diagnostics Are Required"));
        }

        public Result(Status status, String reason, Throwable failure) {
            this(status, reason, failure, List.of());
        }

        public static Result executed() {
            return new Result(Status.EXECUTED, "", null);
        }

        public static Result unsupported(String reason) {
            return new Result(Status.UNSUPPORTED, reason, null);
        }

        public static Result unsupported(String reason, List<Diagnostic> diagnostics) {
            return new Result(Status.UNSUPPORTED, reason, null, diagnostics);
        }

        public static Result failed(String reason, Throwable failure) {
            return new Result(Status.FAILED, reason, failure);
        }

        public static Result failed(String reason, Throwable failure, List<Diagnostic> diagnostics) {
            return new Result(Status.FAILED, reason, failure, diagnostics);
        }

        public static Result failed(Throwable failure) {
            return failed("Compiled Core execution failed", failure);
        }

        public static Result timeout(String reason, Throwable failure, List<Diagnostic> diagnostics) {
            return new Result(Status.TIMEOUT, reason, failure, diagnostics);
        }

        public static Result cancelled(String reason, Throwable failure, List<Diagnostic> diagnostics) {
            return new Result(Status.CANCELLED, reason, failure, diagnostics);
        }
    }

    enum Status {
        EXECUTED,
        UNSUPPORTED,
        FAILED,
        TIMEOUT,
        CANCELLED
    }
}
