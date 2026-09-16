package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.FlowVariable;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TypedCommandGraphAdapter {
    private static final Gson GSON = new Gson();
    private static final List<String> COMMAND_FIELDS = List.of("command", "commandLabel", "label", "name");
    private static final List<String> PATH_FIELDS = List.of("commandPaths", "subcommands", "allowedSubcommands");
    private static final List<String> ALIAS_FIELDS = List.of("aliases", "commandAliases");

    private TypedCommandGraphAdapter() {
    }

    public record CommandBinding(String graphId, String nodeId, String command, List<String> aliases,
                                 List<String> subcommands, List<List<String>> commandPaths, boolean structured,
                                 boolean enabled, String permission, String permissionMessage, String description,
                                 String usage, Map<String, Object> metadata) {
        public CommandBinding {
            graphId = requireText(graphId, "graphId");
            nodeId = requireText(nodeId, "nodeId");
            String normalizedCommand = normalizeLabel(command, "command");
            command = normalizedCommand;
            aliases = normalizeLabels(aliases, "alias").stream().filter(alias -> !alias.equals(normalizedCommand)).toList();
            subcommands = normalizeTextList(subcommands, "subcommand");
            commandPaths = normalizePaths(commandPaths);
            permission = optionalText(permission, "permission");
            permissionMessage = optionalText(permissionMessage, "permissionMessage");
            description = optionalText(description, "description");
            usage = optionalText(usage, "usage");
            metadata = immutableMetadata(metadata);
        }

        public String bindingId() {
            return graphId + ":command:" + nodeId;
        }

        public List<String> labels() {
            List<String> labels = new ArrayList<>();
            labels.add(command);
            labels.addAll(aliases);
            return List.copyOf(labels);
        }
    }

    public record Rejection(String graphId, String nodeId, String code, String detail) {
        public Rejection {
            graphId = graphId == null ? "" : graphId;
            nodeId = nodeId == null ? "" : nodeId;
            code = requireText(code, "code");
            detail = requireText(detail, "detail");
        }
    }

    public record Snapshot(List<CommandBinding> bindings, List<Rejection> rejections) {
        public Snapshot {
            bindings = bindings == null ? List.of() : bindings.stream()
                .sorted(Comparator.comparing(CommandBinding::bindingId))
                .toList();
            rejections = rejections == null ? List.of() : rejections.stream()
                .sorted(Comparator.comparing(Rejection::graphId).thenComparing(Rejection::nodeId).thenComparing(Rejection::code))
                .toList();
        }

        public List<CommandBinding> activeBindings() {
            return bindings.stream().filter(CommandBinding::enabled).toList();
        }
    }

    public static Snapshot index(Map<String, FlowGraph> graphs, Map<String, String> loadFailures) {
        Map<String, FlowGraph> sources = graphs == null ? Map.of() : new LinkedHashMap<>(graphs);
        List<CommandBinding> candidates = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        if (loadFailures != null) {
            loadFailures.forEach((graphId, detail) -> rejections.add(new Rejection(graphId, "", "COMMAND_GRAPH_UNAVAILABLE",
                detail == null || detail.isBlank() ? "The typed command graph could not be loaded" : detail)));
        }
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                candidates.add(read(entry.getValue()));
            } catch (RuntimeException exception) {
                rejections.add(rejection(entry.getKey(), exception));
            }
        });

        Map<String, List<CommandBinding>> byLabel = new LinkedHashMap<>();
        for (CommandBinding binding : candidates.stream().filter(CommandBinding::enabled).toList()) {
            for (String label : binding.labels()) {
                byLabel.computeIfAbsent(label, ignored -> new ArrayList<>()).add(binding);
            }
        }
        Set<String> rejectedBindings = new HashSet<>();
        byLabel.forEach((label, bindings) -> {
            if (bindings.size() < 2) {
                return;
            }
            for (CommandBinding binding : bindings) {
                rejectedBindings.add(binding.bindingId());
                rejections.add(new Rejection(binding.graphId(), binding.nodeId(), "COMMAND_LABEL_COLLISION",
                    "The command label is claimed by more than one enabled typed command graph: " + label));
            }
        });
        List<CommandBinding> accepted = candidates.stream()
            .filter(binding -> !rejectedBindings.contains(binding.bindingId()))
            .toList();
        return new Snapshot(accepted, rejections);
    }

    public static CommandBinding read(FlowGraph graph) {
        if (graph == null) {
            throw invalid("COMMAND_GRAPH_INVALID", "The typed command graph is missing");
        }
        if (!"command".equals(graph.getResourceType())) {
            throw invalid("COMMAND_GRAPH_INVALID", "The graph resource type is not command");
        }
        String graphId = requireText(graph.getId(), "graph id");
        if (graph.getNodes() == null) {
            throw invalid("COMMAND_GRAPH_INVALID", "Command graph nodes are missing");
        }
        List<Map.Entry<String, FlowNode>> commandNodes = graph.getNodes().entrySet().stream()
            .filter(entry -> entry.getValue() != null && isCommandNode(entry.getValue().getType()))
            .sorted(Map.Entry.comparingByKey())
            .toList();
        if (commandNodes.size() != 1) {
            throw invalid("COMMAND_GRAPH_INVALID", "A typed command graph requires exactly one command start node");
        }
        Map.Entry<String, FlowNode> commandEntry = commandNodes.getFirst();
        FlowNode node = commandEntry.getValue();
        Map<String, Object> nodeValues = new LinkedHashMap<>();
        nodeValues.putAll(node.getHandlerConfigValues());
        if (node.getInputValues() != null) {
            nodeValues.putAll(node.getInputValues());
        }

        String command = textFromSources(graph, nodeValues, COMMAND_FIELDS, false);
        if (command == null || command.isBlank()) {
            command = graphId;
        }
        List<String> aliases = listFromSources(graph, nodeValues, ALIAS_FIELDS);
        Object pathsValue = valueFromSources(graph, nodeValues, PATH_FIELDS);
        List<String> subcommands = parseSubcommands(pathsValue);
        List<List<String>> commandPaths = parsePaths(pathsValue);
        Boolean structuredValue = booleanFromSources(graph, nodeValues, "structured");
        boolean structured = structuredValue != null && structuredValue;
        String permission = textFromSources(graph, nodeValues, List.of("permission"), true);
        String permissionMessage = textFromSources(graph, nodeValues, List.of("permissionMessage"), true);
        String description = textFromSources(graph, nodeValues, List.of("description", "commandDescription"), true);
        String usage = textFromSources(graph, nodeValues, List.of("usage", "commandUsage"), true);
        Map<String, Object> metadata = metadata(graph, nodeValues);
        return new CommandBinding(graphId, commandEntry.getKey(), command, aliases, subcommands, commandPaths,
            structured, graph.isEnabled(), permission, permissionMessage, description, usage, metadata);
    }

    public static FlowGraph materialize(GraphDocument document, boolean enabled, String mutationId) {
        return materialize(document, null, enabled, mutationId);
    }

    public static FlowGraph materialize(GraphDocument document, FunctionSourceDocument functionSource,
                                        boolean enabled, String mutationId) {
        Objects.requireNonNull(document, "Core graph document is required");
        CompiledGraphMaterializer.Result validated = new CompiledGraphMaterializer().materialize(document, functionSource);
        if (validated.document() == null) {
            String reason = validated.diagnostics().isEmpty() ? "Core graph projection is invalid"
                : validated.diagnostics().getFirst().message();
            throw new IllegalStateException(reason);
        }
        if (!document.functions().isEmpty()) {
            throw new IllegalStateException("Core graph static function bindings require compiled execution");
        }
        LinkedHashMap<String, FlowNode> nodes = new LinkedHashMap<>();
        for (GraphNode source : document.nodes()) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            source.values().forEach((pin, value) -> materialize(values, pin.value(), value.value()));
            LinkedHashMap<String, Object> inspector = new LinkedHashMap<>();
            source.inspector().forEach((pin, value) -> materialize(inspector, pin.value(), value.value()));
            source.inspectorFields().forEach((field, value) -> materialize(inspector, field.value(), value));
            Map<String, Object> effectiveValues = ItemStackPropertySelector.effectiveInputs(
                source.definition().canonicalText(), values, inspector);
            FlowNode node = new FlowNode(source.definition().canonicalText(), source.x(), source.y(), effectiveValues);
            node.setVersion(source.definitionVersion());
            node.setHandlerConfig(inspector);
            nodes.put(source.instanceId().canonicalText(), node);
        }
        ArrayList<FlowConnection> connections = new ArrayList<>();
        for (var source : document.connections()) {
            connections.add(new FlowConnection(source.source().nodeId().canonicalText(), source.source().pinId().value(),
                source.target().nodeId().canonicalText(), source.target().pinId().value()));
        }
        FlowGraph graph = new FlowGraph(document.resource().id(), nodes, connections, new ArrayList<>());
        graph.setResourceType(document.resource().resourceType().value());
        graph.setResourceRevision(document.revision());
        graph.setResourceHash(document.checksum().canonicalText());
        graph.setResourceMutationId(mutationId);
        graph.setEnabled(enabled);
        graph.setLocalVariables(document.variables().stream().map(TypedCommandGraphAdapter::materializeVariable).toList());
        if ("command".equals(document.resource().resourceType().value())) {
            CommandGraphMetadata metadata = CommandGraphMetadata.from(document);
            LinkedHashMap<String, Object> contentProperties = new LinkedHashMap<>();
            contentProperties.put("commandLabel", metadata.commandLabel());
            contentProperties.put("structured", metadata.structured());
            contentProperties.put("commandPaths", metadata.commandPaths());
            graph.setContentProperties(contentProperties);
        }
        if (functionSource != null) {
            graph.setFunction(true);
            graph.setFunctionOwner(functionSource.signature().function().resource().owner().value());
            graph.setFunctionNamespace("local");
            graph.setFunctionVersion(Math.toIntExact(functionSource.signature().revision().value()));
            graph.setFunctionInputs(materializeParameters(functionSource.signature().inputs()));
            graph.setFunctionOutputs(materializeParameters(functionSource.signature().outputs()));
        }
        return graph;
    }

    private static void materialize(Map<String, Object> target, String key, TypedValue value) {
        if (value == null || value.state() == TypedValue.State.ABSENT) {
            return;
        }
        if (value.state() == TypedValue.State.NULL) {
            target.put(key, null);
            return;
        }
        if (value.locator() != null) {
            target.put(key, value.locator().canonicalText());
            return;
        }
        target.put(key, value.value());
    }

    private static FlowVariable materializeVariable(GraphVariable variable) {
        FlowTypeRef type = materializeType(variable.type());
        Object value = null;
        if (variable.value() != null && variable.value().state() != TypedValue.State.ABSENT
            && variable.value().state() != TypedValue.State.NULL) {
            value = variable.value().locator() != null
                ? variable.value().locator().canonicalText() : variable.value().value();
        }
        return new FlowVariable(variable.name(), type.toString(), value);
    }

    private static List<FlowGraph.FunctionParameter> materializeParameters(List<FunctionParameterContract> parameters) {
        ArrayList<FlowGraph.FunctionParameter> result = new ArrayList<>(parameters.size());
        for (FunctionParameterContract parameter : parameters) {
            FlowTypeRef type = materializeType(parameter.type());
            String name = parameter.unknown().get("name") instanceof String value && !value.isBlank()
                ? value : parameter.id().canonicalText();
            String widget = parameter.unknown().get("widget") instanceof String value ? value : "";
            String optionsSource = parameter.unknown().get("optionsSource") instanceof String value ? value : "";
            result.add(new FlowGraph.FunctionParameter(parameter.id(), name,
                FlowDataType.fromString(type.getTypeId()), widget, optionsSource,
                materializeDefault(parameter.defaultValue()), type));
        }
        return List.copyOf(result);
    }

    private static String materializeDefault(TypedValue value) {
        if (value == null || value.state() == TypedValue.State.ABSENT || value.state() == TypedValue.State.NULL) {
            return "";
        }
        Object material = value.locator() != null ? value.locator().canonicalText() : value.value();
        if (material instanceof String text) {
            return text;
        }
        if (material instanceof Number || material instanceof Boolean || material instanceof Character) {
            return String.valueOf(material);
        }
        return GSON.toJson(material);
    }

    private static FlowTypeRef materializeType(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> new FlowTypeRef(typeId(named.reference().ownerId(), named.reference().localId()),
                named.arguments().stream().map(TypedCommandGraphAdapter::materializeType).toList());
            case TypeExpr.OptionalType optional -> new FlowTypeRef("optional", List.of(materializeType(optional.element())));
            case TypeExpr.ListType list -> new FlowTypeRef("list", List.of(materializeType(list.element())));
            case TypeExpr.MapType map -> new FlowTypeRef("map", List.of(materializeType(map.key()), materializeType(map.value())));
            case TypeExpr.ResultType result -> new FlowTypeRef("result", List.of(materializeType(result.success()),
                materializeType(result.failure())));
            case TypeExpr.ResourceType resource -> new FlowTypeRef("resource_reference",
                List.of(FlowTypeRef.simple(typeId(resource.resourceType().ownerId(), resource.resourceType().localId()))));
            default -> throw new IllegalStateException("Core graph type requires compiled execution: " + type.kind());
        };
    }

    private static String typeId(String owner, String local) {
        return "builtin".equals(owner) ? local : owner + ':' + local;
    }

    private static Map<String, Object> metadata(FlowGraph graph, Map<String, Object> nodeValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object graphMetadata = opaqueValue(graph, "metadata");
        if (graphMetadata instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    metadata.put(key.toString(), value);
                }
            });
        }
        for (String key : List.of("permission", "permissionMessage", "description", "usage", "aliases", "commandPaths", "structured")) {
            Object value = valueFromSources(graph, nodeValues, List.of(key));
            if (value != null) {
                metadata.putIfAbsent(key, value);
            }
        }
        return metadata;
    }

    private static String textFromSources(FlowGraph graph, Map<String, Object> nodeValues, List<String> keys, boolean optional) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            Object graphValue = opaqueValue(graph, key);
            if (graphValue != null) {
                values.add(requireString(graphValue, key, optional));
            }
            if (nodeValues.containsKey(key)) {
                values.add(requireString(nodeValues.get(key), key, optional));
            }
        }
        String selected = null;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (selected != null && !selected.equals(value)) {
                throw invalid("COMMAND_GRAPH_AMBIGUOUS", "Command metadata fields disagree for " + keys);
            }
            selected = value;
        }
        return selected;
    }

    private static Object valueFromSources(FlowGraph graph, Map<String, Object> nodeValues, List<String> keys) {
        Object selected = null;
        boolean selectedSet = false;
        for (String key : keys) {
            Object graphValue = opaqueValue(graph, key);
            if (graphValue != null) {
                if (selectedSet && !Objects.equals(selected, graphValue)) {
                    throw invalid("COMMAND_GRAPH_AMBIGUOUS", "Command metadata fields disagree for " + keys);
                }
                selected = graphValue;
                selectedSet = true;
            }
            if (nodeValues.containsKey(key)) {
                Object nodeValue = nodeValues.get(key);
                if (selectedSet && !Objects.equals(selected, nodeValue)) {
                    throw invalid("COMMAND_GRAPH_AMBIGUOUS", "Command metadata fields disagree for " + keys);
                }
                selected = nodeValue;
                selectedSet = true;
            }
        }
        return selected;
    }

    private static Boolean booleanFromSources(FlowGraph graph, Map<String, Object> nodeValues, String key) {
        Object value = valueFromSources(graph, nodeValues, List.of(key));
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw invalid("COMMAND_GRAPH_INVALID", key + " must be a boolean");
        }
        return booleanValue;
    }

    private static List<String> listFromSources(FlowGraph graph, Map<String, Object> nodeValues, List<String> keys) {
        Object value = valueFromSources(graph, nodeValues, keys);
        if (value == null) {
            return List.of();
        }
        return parseStringList(value, "command aliases");
    }

    private static List<String> parseSubcommands(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item == null || item instanceof String)) {
            return parseStringList(value, "subcommands");
        }
        if (value instanceof String string) {
            return List.of(requireText(string, "subcommand"));
        }
        if (value instanceof List<?>) {
            return parsePaths(value).stream().map(path -> String.join(" ", path)).toList();
        }
        throw invalid("COMMAND_GRAPH_INVALID", "Command paths must be strings or arrays");
    }

    private static List<List<String>> parsePaths(Object value) {
        if (value == null) {
            return List.of();
        }
        List<List<String>> paths = new ArrayList<>();
        if (value instanceof String string) {
            paths.add(tokens(string));
            return List.copyOf(paths);
        }
        if (!(value instanceof List<?> list)) {
            throw invalid("COMMAND_GRAPH_INVALID", "Command paths must be strings or arrays");
        }
        for (Object entry : list) {
            if (entry instanceof String string) {
                paths.add(tokens(string));
            } else if (entry instanceof List<?> nested) {
                List<String> path = new ArrayList<>();
                for (Object token : nested) {
                    if (!(token instanceof String text)) {
                        throw invalid("COMMAND_GRAPH_INVALID", "Command path tokens must be strings");
                    }
                    path.add(requireText(text, "command path token"));
                }
                if (path.isEmpty()) {
                    throw invalid("COMMAND_GRAPH_INVALID", "Command paths cannot be empty");
                }
                paths.add(List.copyOf(path));
            } else {
                throw invalid("COMMAND_GRAPH_INVALID", "Command paths must contain strings or string arrays");
            }
        }
        return List.copyOf(paths);
    }

    private static List<String> parseStringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a string or array");
        }
        List<String> values = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String text)) {
                throw invalid("COMMAND_GRAPH_INVALID", field + " entries must be strings");
            }
            values.add(requireText(text, field));
        }
        return List.copyOf(values);
    }

    private static List<String> tokens(String value) {
        String text = requireText(value, "command path");
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(requireText(token, "command path token"));
            }
        }
        if (tokens.isEmpty()) {
            throw invalid("COMMAND_GRAPH_INVALID", "Command paths cannot be empty");
        }
        return List.copyOf(tokens);
    }

    private static Object opaqueValue(FlowGraph graph, String key) {
        JsonElement value = graph.getOpaqueProperties().get(key);
        if (value != null && !value.isJsonNull()) {
            return GSON.fromJson(value, Object.class);
        }
        Map<String, Object> contentProperties = graph.getContentProperties();
        return contentProperties != null ? contentProperties.get(key) : null;
    }

    private static boolean isCommandNode(String type) {
        return CommandGraphContract.isAnyStart(type);
    }

    private static Rejection rejection(String graphId, RuntimeException exception) {
        String detail = exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName() : exception.getMessage();
        String code = detail.startsWith("COMMAND_GRAPH_AMBIGUOUS") ? "COMMAND_GRAPH_AMBIGUOUS" : "COMMAND_GRAPH_INVALID";
        return new Rejection(graphId, "", code, detail);
    }

    private static IllegalArgumentException invalid(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }

    private static String requireString(Object value, String field, boolean optional) {
        if (value == null) {
            return optional ? "" : null;
        }
        if (!(value instanceof String string)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a string");
        }
        return string.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " is required");
        }
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a non-blank single-line string");
        }
        return normalized;
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireText(value, field);
    }

    private static String normalizeLabel(String value, String field) {
        String normalized = requireText(value, field).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        if (normalized.isBlank() || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be one command label");
        }
        return normalized;
    }

    private static List<String> normalizeLabels(Collection<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeLabel(value, field));
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeTextList(Collection<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> requireText(value, field)).toList();
    }

    private static List<List<String>> normalizePaths(Collection<List<String>> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(path -> normalizeTextList(path, "command path token")).filter(path -> !path.isEmpty()).toList();
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(requireText(key, "metadata key"), immutableMetadataValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableMetadataValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) {
                    throw invalid("COMMAND_GRAPH_INVALID", "Command metadata keys must be strings");
                }
                result.put(requireText(text, "metadata key"), immutableMetadataValue(nested));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(collection.stream().map(TypedCommandGraphAdapter::immutableMetadataValue).toList());
        }
        throw invalid("COMMAND_GRAPH_INVALID", "Command metadata contains an unsupported value: " + value.getClass().getName());
    }
}
