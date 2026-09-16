package restudio.resync.flow;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReQuestDescriptorIdentityAdapter {
    public static final String LEGACY_WIRE_ID = "custom_function:reQuestMessage";
    public static final String FUNCTION_OWNER = "server";
    public static final String FUNCTION_NAMESPACE = "local";
    public static final String FUNCTION_SOURCE_ID = "reQuestMessage";
    public static final String FUNCTION_RESOURCE_ID = "server/function/reQuestMessage";
    public static final String CANONICAL_LOCAL_ID = "requestmessage";
    public static final String CANONICAL_WIRE_ID = FUNCTION_OWNER + ":" + CANONICAL_LOCAL_ID;
    public static final String FUNCTION_SOURCE_SHA256 = "a78620677cfa86ad4f6bb456244a07e0160e4cc3ce571e02c3c4c3ffd1e89016";
    public static final int FUNCTION_VERSION = 2;
    public static final String HANDLER_ID = CustomFunctionCallHandler.HANDLER_ID;
    public static final String OPERATION = CustomFunctionCallHandler.OPERATION;
    public static final List<String> INPUT_PINS = List.of("flow", "target", "message");
    public static final List<String> OUTPUT_PINS = List.of("flow");

    private ReQuestDescriptorIdentityAdapter() {
    }

    public static Descriptor descriptor() {
        return new Descriptor(FUNCTION_OWNER, FUNCTION_SOURCE_ID, CANONICAL_LOCAL_ID, CANONICAL_WIRE_ID,
            FUNCTION_RESOURCE_ID, FUNCTION_NAMESPACE, FUNCTION_VERSION, HANDLER_ID, OPERATION,
            INPUT_PINS, OUTPUT_PINS, FUNCTION_SOURCE_SHA256, legacyAliases());
    }

    public static Descriptor descriptor(FlowGraph graph) {
        Objects.requireNonNull(graph, "ReQuest Function graph is required");
        if (!isAuthenticatedFunction(graph)) {
            throw new IllegalArgumentException("The Function graph does not match the authenticated ReQuestMessage v2 source");
        }
        return descriptor();
    }

    public static boolean isAuthenticatedFunction(FlowGraph graph) {
        if (graph == null || !graph.isFunction() || !FUNCTION_SOURCE_ID.equals(graph.getId())
            || !FUNCTION_OWNER.equals(graph.getFunctionOwner()) || !FUNCTION_NAMESPACE.equals(graph.getFunctionNamespace())
            || graph.getFunctionVersion() != FUNCTION_VERSION) {
            return false;
        }
        return authenticatedParameters(graph.getFunctionInputs())
            && graph.getFunctionOutputs() != null && graph.getFunctionOutputs().isEmpty();
    }

    public static NodeDefinition adapt(NodeDefinition definition) {
        Objects.requireNonNull(definition, "ReQuest Function definition is required");
        Descriptor descriptor = descriptor();
        if (!LEGACY_WIRE_ID.equals(definition.getId()) || !FUNCTION_OWNER.equals(definition.getOwner())
            || definition.getSchemaVersion() != descriptor.version() || !HANDLER_ID.equals(definition.getHandler())) {
            throw new IllegalArgumentException("The Function definition does not match the legacy ReQuestMessage identity");
        }
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        if (handlerConfig == null || !OPERATION.equals(handlerConfig.get("operation"))
            || !FUNCTION_SOURCE_ID.equals(handlerConfig.get("functionId"))
            || !FUNCTION_OWNER.equals(handlerConfig.get("functionOwner"))
            || !FUNCTION_NAMESPACE.equals(handlerConfig.get("functionNamespace"))) {
            throw new IllegalArgumentException("The Function definition does not match the authenticated ReQuestMessage handler contract");
        }
        if (!authenticatedPins(definition.getInputs(), true)
            || !authenticatedPins(definition.getOutputs(), false)) {
            throw new IllegalArgumentException("The Function definition does not match the authenticated ReQuestMessage pin contract");
        }
        if (definition.getCanonicalId() != null && !definition.getCanonicalId().isBlank()
            && !CANONICAL_LOCAL_ID.equals(definition.getCanonicalId())) {
            throw new IllegalArgumentException("The ReQuestMessage descriptor has an ambiguous canonical identity");
        }
        if (!definition.getLegacyIds().isEmpty() && !definition.getLegacyIds().equals(List.of(LEGACY_WIRE_ID))) {
            throw new IllegalArgumentException("The ReQuestMessage descriptor has ambiguous legacy aliases");
        }
        return definition.withCanonicalId(CANONICAL_LOCAL_ID).withLegacyIds(List.of(LEGACY_WIRE_ID));
    }

    public static AliasMap legacyAliases() {
        return new AliasMap(List.of(new Alias(LEGACY_WIRE_ID, CANONICAL_WIRE_ID)));
    }

    private static boolean authenticatedParameters(List<FlowGraph.FunctionParameter> parameters) {
        if (parameters == null) {
            return false;
        }
        Set<FunctionParameterId> ids = new LinkedHashSet<>();
        Set<FlowDataType> types = new LinkedHashSet<>();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || parameter.getParameterId() == null || parameter.getName() == null
                || parameter.getName().isBlank() || parameter.getType() == null
                || !ids.add(parameter.getParameterId())) {
                return false;
            }
            types.add(parameter.getType());
        }
        return parameters.size() == 2 && types.equals(Set.of(FlowDataType.PLAYER, FlowDataType.STRING));
    }

    private static boolean authenticatedPins(List<NodeDefinition.PinDefinition> pins, boolean inputs) {
        if (pins == null) {
            return false;
        }
        if (!inputs) {
            return pins.size() == 1 && isFlowPin(pins.getFirst())
                && ("flow".equals(pins.getFirst().getName()) || "output_flow".equals(pins.getFirst().getName()));
        }
        NodeDefinition.PinDefinition flow = null;
        Set<String> parameterIds = new LinkedHashSet<>();
        Set<FlowDataType> parameterTypes = new LinkedHashSet<>();
        for (NodeDefinition.PinDefinition pin : pins) {
            if (pin == null || pin.getName() == null || pin.getName().isBlank()) {
                return false;
            }
            if (isFlowPin(pin)) {
                if (flow != null || pin.getDirection() != NodeDefinition.PinDirection.INPUT
                    || !"flow".equals(pin.getName())) {
                    return false;
                }
                flow = pin;
                continue;
            }
            if (pin.getType() != NodeDefinition.PinType.DATA || pin.getDirection() != NodeDefinition.PinDirection.INPUT
                || pin.getId() == null || !parameterPinId(pin.getId().value(), "function-input-")
                && !("target".equals(pin.getName()) || "message".equals(pin.getName()))) {
                return false;
            }
            if (!parameterIdsAdd(parameterIds, pin)) {
                return false;
            }
            parameterTypes.add(pin.getDataType());
        }
        return flow != null && parameterIds.size() == 2
            && parameterTypes.equals(Set.of(FlowDataType.PLAYER, FlowDataType.STRING));
    }

    private static boolean isFlowPin(NodeDefinition.PinDefinition pin) {
        return pin != null && pin.getType() == NodeDefinition.PinType.FLOW
            && pin.getDataType() == FlowDataType.EXECUTION;
    }

    private static boolean parameterPinId(String value, String prefix) {
        if (!value.startsWith(prefix)) {
            return false;
        }
        try {
            FunctionParameterId.parseCanonicalText(value.substring(prefix.length()));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean parameterIdsAdd(Set<String> parameterIds, NodeDefinition.PinDefinition pin) {
        String id = pin.getId().value();
        if (id.startsWith("function-input-")) {
            try {
                FunctionParameterId.parseCanonicalText(id.substring("function-input-".length()));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        } else if (!Set.of("target", "message").contains(id)) {
            return false;
        }
        return parameterIds.add(id);
    }

    public record Descriptor(String ownerId, String sourceId, String localId, String wireId, String functionResourceId,
                             String namespace, int version, String handlerId, String operation, List<String> inputPins,
                             List<String> outputPins, String functionSourceSha256, AliasMap aliases) {
        public Descriptor {
            ownerId = text(ownerId, "ownerId");
            sourceId = text(sourceId, "sourceId");
            localId = text(localId, "localId");
            wireId = text(wireId, "wireId");
            functionResourceId = text(functionResourceId, "functionResourceId");
            namespace = text(namespace, "namespace");
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
            handlerId = text(handlerId, "handlerId");
            operation = text(operation, "operation");
            inputPins = List.copyOf(inputPins == null ? List.of() : inputPins);
            outputPins = List.copyOf(outputPins == null ? List.of() : outputPins);
            functionSourceSha256 = digest(functionSourceSha256, "functionSourceSha256");
            aliases = Objects.requireNonNull(aliases, "aliases");
            if (!wireId.equals(ownerId + ":" + localId)) {
                throw new IllegalArgumentException("wireId must be the owner-qualified local descriptor identity");
            }
            if (!aliases.resolve(LEGACY_WIRE_ID).equals(wireId)) {
                throw new IllegalArgumentException("The legacy ReQuest alias does not resolve to the canonical descriptor");
            }
        }

        public String resolve(String id) {
            return aliases.resolve(id);
        }

        private static String text(String value, String field) {
            if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(field + " must be non-blank text");
            }
            return value;
        }

        private static String digest(String value, String field) {
            String normalized = text(value, field).toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be a SHA-256 digest");
            }
            return normalized;
        }
    }

    public record Alias(String legacyId, String canonicalId) {
        public Alias {
            legacyId = text(legacyId, "legacyId");
            canonicalId = text(canonicalId, "canonicalId");
        }

        private static String text(String value, String field) {
            if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(field + " must be non-blank text");
            }
            return value;
        }
    }

    public static final class AliasMap {
        private final Map<String, String> aliases;

        public AliasMap(Collection<Alias> entries) {
            Objects.requireNonNull(entries, "entries");
            List<Alias> ordered = new ArrayList<>();
            for (Alias entry : entries) {
                ordered.add(Objects.requireNonNull(entry, "entries cannot contain null aliases"));
            }
            ordered.sort(Comparator.comparing(Alias::legacyId).thenComparing(Alias::canonicalId));
            LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
            for (Alias entry : ordered) {
                String previous = resolved.putIfAbsent(entry.legacyId(), entry.canonicalId());
                if (previous != null) {
                    throw new IllegalArgumentException("Ambiguous legacy descriptor alias: " + entry.legacyId());
                }
                if (entry.legacyId().equals(entry.canonicalId())) {
                    throw new IllegalArgumentException("A descriptor alias cannot point to itself: " + entry.legacyId());
                }
            }
            aliases = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        }

        public String resolve(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A descriptor identity is required");
            }
            if (CANONICAL_WIRE_ID.equals(id)) {
                return CANONICAL_WIRE_ID;
            }
            String resolved = aliases.get(id);
            if (resolved != null) {
                return resolved;
            }
            if (FUNCTION_SOURCE_ID.equals(id)) {
                throw new IllegalArgumentException("The bare ReQuestMessage identity is ambiguous without its owner");
            }
            throw new IllegalArgumentException("Unknown ReQuest descriptor identity: " + id);
        }

        public Map<String, String> values() {
            return aliases;
        }
    }
}
