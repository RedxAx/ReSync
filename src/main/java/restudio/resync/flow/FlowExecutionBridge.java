package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.diagnostic.Diagnostic;
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
                   CorrelationId invocationId, long requestedDeadlineMillis) {
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
            return mappingContext.validationFailure(graph, startNodeId);
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
        List<TypeReference> conversionPolicyReferences
    ) {
        public MappingContext {
            resourceLocator = Objects.requireNonNull(resourceLocator, "Canonical Resource Locator Is Required");
            catalogBinding = Objects.requireNonNull(catalogBinding, "Catalog Binding Is Required");
            snapshotId = Objects.requireNonNull(snapshotId, "Snapshot Identity Is Required");
            nodeMappings = immutableMapping(nodeMappings, "Node Mapping", NodeId.class);
            pinMappings = immutableMapping(pinMappings, "Pin Mapping", PinId.class);
            conversionPolicyReferences = immutableConversionReferences(conversionPolicyReferences);
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
