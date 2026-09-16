package restudio.resync.upgrade.command;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CommandBindingTransformer {
    public static final String ADAPTER_ID = "resync.command-binding";
    public static final int ADAPTER_VERSION = 1;
    private static final Set<String> COMMAND_TYPES = Set.of("event.resync.command", "event:resync_command");
    private static final List<String> COMMAND_ALIASES = List.of("command", "label", "name");

    public CommandBindingOutput transform(CommandBindingInput input) {
        Objects.requireNonNull(input, "input");
        JsonValue.JsonObject root = input.graph().root();
        String graphId = requireString(root.value("id"), "COMMAND_GRAPH_INVALID: graph id is required");
        JsonValue.JsonObject nodes = requireObject(root.value("nodes"), "COMMAND_GRAPH_INVALID: nodes must be an object");
        validateBindingIds(input.bindings());

        List<NodeRef> commandNodes = commandNodes(nodes);
        List<CommandBinding> graphBindings = input.bindings().stream()
            .filter(binding -> graphId.equals(binding.flowId()))
            .sorted(Comparator.comparing(CommandBinding::bindingId, CanonicalJson::compareCodePoints))
            .toList();
        if (commandNodes.isEmpty()) {
            if (!graphBindings.isEmpty()) {
                throw failure("COMMAND_BINDING_NO_TARGET: command bindings have no command event node");
            }
            return output(input.graph(), false, List.of());
        }

        List<ParsedBinding> parsedBindings = graphBindings.stream().map(this::parseBinding).toList();
        Map<String, Assignment> assignments = assign(commandNodes, parsedBindings, graphId);
        if (assignments.isEmpty()) {
            return output(input.graph(), false, List.of());
        }

        Map<String, JsonValue> updatedNodes = new LinkedHashMap<>(nodes.fields());
        List<CommandBindingOutput.AppliedBinding> applied = new ArrayList<>();
        boolean changed = false;
        for (NodeRef node : commandNodes) {
            Assignment assignment = assignments.get(node.nodeId());
            if (assignment == null) {
                continue;
            }
            NodeUpdate update = apply(node.node(), assignment.binding());
            updatedNodes.put(node.nodeId(), update.node());
            changed |= update.changed();
            applied.add(new CommandBindingOutput.AppliedBinding(
                assignment.binding().source().bindingId(),
                assignment.binding().source().flowId(),
                node.nodeId(),
                update.changed()));
        }
        applied.sort(Comparator.comparing(CommandBindingOutput.AppliedBinding::nodeId, CanonicalJson::compareCodePoints));
        JsonValue.JsonObject updatedRoot = replace(root, "nodes", JsonValue.object(updatedNodes));
        return output(input.graph().withRoot(updatedRoot), changed, applied);
    }

    public CommandBindingOutput transform(RawGraphDocument graph, CommandBinding binding) {
        return transform(CommandBindingInput.single(graph, binding));
    }

    private CommandBindingOutput output(RawGraphDocument graph, boolean changed, List<CommandBindingOutput.AppliedBinding> applied) {
        return new CommandBindingOutput(graph, ADAPTER_ID, ADAPTER_VERSION, changed, applied);
    }

    private Map<String, Assignment> assign(List<NodeRef> nodes, List<ParsedBinding> bindings, String flowId) {
        Map<String, Assignment> assignments = new HashMap<>();
        Set<String> assignedBindings = new HashSet<>();
        for (NodeRef node : nodes) {
            String explicitId = flowId + ":command:" + node.nodeId();
            List<ParsedBinding> candidates = bindings.stream()
                .filter(binding -> explicitId.equals(binding.source().bindingId()))
                .toList();
            if (candidates.size() > 1) {
                throw failure("COMMAND_BINDING_AMBIGUOUS: multiple bindings target node " + node.nodeId());
            }
            if (candidates.size() == 1) {
                ParsedBinding binding = candidates.getFirst();
                assignments.put(node.nodeId(), new Assignment(binding));
                assignedBindings.add(binding.source().bindingId());
            }
        }

        List<ParsedBinding> remainingBindings = bindings.stream()
            .filter(binding -> !assignedBindings.contains(binding.source().bindingId()))
            .toList();
        List<NodeRef> remainingNodes = nodes.stream()
            .filter(node -> !assignments.containsKey(node.nodeId()))
            .toList();
        if (remainingBindings.isEmpty()) {
            return assignments;
        }
        if (remainingBindings.size() != 1 || remainingNodes.size() != 1) {
            throw failure("COMMAND_BINDING_AMBIGUOUS: bindings cannot be assigned to command nodes deterministically");
        }
        assignments.put(remainingNodes.getFirst().nodeId(), new Assignment(remainingBindings.getFirst()));
        return assignments;
    }

    private List<NodeRef> commandNodes(JsonValue.JsonObject nodes) {
        List<NodeRef> result = new ArrayList<>();
        List<Map.Entry<String, JsonValue>> entries = new ArrayList<>(nodes.fields().entrySet());
        entries.sort((left, right) -> CanonicalJson.compareCodePoints(left.getKey(), right.getKey()));
        for (Map.Entry<String, JsonValue> entry : entries) {
            JsonValue.JsonObject node = requireObject(entry.getValue(), "COMMAND_GRAPH_INVALID: node must be an object");
            String type = requireString(node.value("type"), "COMMAND_GRAPH_INVALID: node type is required");
            if (COMMAND_TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
                result.add(new NodeRef(entry.getKey(), node));
            }
        }
        return List.copyOf(result);
    }

    private ParsedBinding parseBinding(CommandBinding binding) {
        String context = binding.context().trim();
        if (context.isEmpty()) {
            throw failure("COMMAND_BINDING_MALFORMED: command context is empty");
        }
        if (!context.startsWith("{")) {
            if (context.startsWith("[")) {
                throw failure("COMMAND_BINDING_MALFORMED: command context must be a command string or object");
            }
            return new ParsedBinding(binding, normalizeCommand(context), List.of(), false);
        }
        JsonValue value;
        try {
            value = CanonicalCodec.decodeOpaquePermissive(context);
        } catch (RuntimeException exception) {
            throw failure("COMMAND_BINDING_MALFORMED: structured command context is invalid", exception);
        }
        JsonValue.JsonObject object = requireObject(value, "COMMAND_BINDING_MALFORMED: structured command context must be an object");
        String command = normalizeCommand(requireString(object.value("command"), "COMMAND_BINDING_MALFORMED: command is required"));
        List<String> subcommands = parseSubcommands(object.value("subcommands"));
        boolean structured = parseStructured(object.value("structured"));
        return new ParsedBinding(binding, command, subcommands, structured);
    }

    private List<String> parseSubcommands(JsonValue value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof JsonValue.JsonString string) {
            return List.of(normalizeSubcommand(string.value()));
        }
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw failure("COMMAND_BINDING_MALFORMED: subcommands must be a string or array");
        }
        List<String> result = new ArrayList<>();
        for (JsonValue item : array.values()) {
            if (!(item instanceof JsonValue.JsonString string)) {
                throw failure("COMMAND_BINDING_MALFORMED: every subcommand must be a string");
            }
            result.add(normalizeSubcommand(string.value()));
        }
        return List.copyOf(result);
    }

    private boolean parseStructured(JsonValue value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw failure("COMMAND_BINDING_MALFORMED: structured must be a boolean");
        }
        return booleanValue.value();
    }

    private NodeUpdate apply(JsonValue.JsonObject node, ParsedBinding binding) {
        String inputField = null;
        JsonValue.JsonObject values = null;
        if (node.contains("inputValues") && node.contains("inputs")) {
            inputField = "inputValues";
            values = mergeInputValues(
                requireObject(node.value("inputs"), "COMMAND_GRAPH_INVALID: inputs must be an object"),
                requireObject(node.value("inputValues"), "COMMAND_GRAPH_INVALID: inputValues must be an object"));
        }
        if (values == null && node.contains("inputValues")) {
            inputField = "inputValues";
            values = requireObject(node.value(inputField), "COMMAND_GRAPH_INVALID: inputValues must be an object");
        } else if (values == null && node.contains("inputs")) {
            inputField = "inputValues";
            values = requireObject(node.value("inputs"), "COMMAND_GRAPH_INVALID: inputs must be an object");
        } else if (values == null) {
            inputField = "inputValues";
            values = JsonValue.object(Map.of());
        }

        validateExistingCommand(values, binding.command());
        validateExistingSubcommands(values, binding.subcommands());
        validateExistingStructured(values, binding.structured());

        Map<String, JsonValue> updatedValues = new LinkedHashMap<>(values.fields());
        boolean changed = false;
        JsonValue commandValue = values.value("command");
        boolean missingCommand = commandValue == null
            || commandValue instanceof JsonValue.JsonString string && string.value().trim().isEmpty();
        if (missingCommand) {
            updatedValues.put("command", JsonValue.of(binding.command()));
            changed = true;
        }
        if (!values.contains("subcommands")) {
            updatedValues.put("subcommands", JsonValue.array(binding.subcommands().stream().map(JsonValue::of).toList()));
            changed = true;
        }
        if (!values.contains("structured")) {
            updatedValues.put("structured", JsonValue.of(binding.structured()));
            changed = true;
        }
        JsonValue.JsonObject updatedValuesObject = JsonValue.object(updatedValues);
        if (!node.contains(inputField) || !updatedValuesObject.equals(values)) {
            changed = true;
        }
        JsonValue.JsonObject updatedNode = replace(node, inputField, updatedValuesObject);
        return new NodeUpdate(updatedNode, changed);
    }

    private JsonValue.JsonObject mergeInputValues(JsonValue.JsonObject legacy, JsonValue.JsonObject typed) {
        Map<String, JsonValue> values = new LinkedHashMap<>(legacy.fields());
        typed.fields().forEach((key, value) -> {
            JsonValue previous = values.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw failure("COMMAND_BINDING_AMBIGUOUS: inputs and inputValues disagree for " + key);
            }
        });
        return JsonValue.object(values);
    }

    private void validateExistingCommand(JsonValue.JsonObject values, String expected) {
        String known = null;
        for (String alias : COMMAND_ALIASES) {
            JsonValue value = values.value(alias);
            if (value == null) {
                continue;
            }
            if (!(value instanceof JsonValue.JsonString string)) {
                throw failure("COMMAND_GRAPH_INVALID: " + alias + " must be a string");
            }
            String text = string.value().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (known != null && !known.equals(text)) {
                throw failure("COMMAND_BINDING_AMBIGUOUS: command aliases disagree");
            }
            if (!expected.equals(text)) {
                throw failure("COMMAND_BINDING_CONFLICT: graph command does not match the binding");
            }
            known = text;
        }
    }

    private void validateExistingSubcommands(JsonValue.JsonObject values, List<String> expected) {
        JsonValue value = values.value("subcommands");
        if (value == null) {
            return;
        }
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw failure("COMMAND_GRAPH_INVALID: subcommands must be an array");
        }
        List<String> actual = new ArrayList<>();
        for (JsonValue item : array.values()) {
            if (!(item instanceof JsonValue.JsonString string)) {
                throw failure("COMMAND_GRAPH_INVALID: every subcommand must be a string");
            }
            actual.add(string.value());
        }
        if (!expected.equals(actual)) {
            throw failure("COMMAND_BINDING_CONFLICT: graph subcommands do not match the binding");
        }
    }

    private void validateExistingStructured(JsonValue.JsonObject values, boolean expected) {
        JsonValue value = values.value("structured");
        if (value == null) {
            return;
        }
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw failure("COMMAND_GRAPH_INVALID: structured must be a boolean");
        }
        if (booleanValue.value() != expected) {
            throw failure("COMMAND_BINDING_CONFLICT: graph structured mode does not match the binding");
        }
    }

    private void validateBindingIds(List<CommandBinding> bindings) {
        Set<String> ids = new HashSet<>();
        for (CommandBinding binding : bindings) {
            if (!ids.add(binding.bindingId())) {
                throw failure("COMMAND_BINDING_AMBIGUOUS: duplicate binding id " + binding.bindingId());
            }
        }
    }

    private static JsonValue.JsonObject replace(JsonValue.JsonObject object, String key, JsonValue value) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        fields.put(key, value);
        return JsonValue.object(fields);
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String message) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw failure(message);
        }
        return object;
    }

    private static String requireString(JsonValue value, String message) {
        if (!(value instanceof JsonValue.JsonString string) || string.value().trim().isEmpty()) {
            throw failure(message);
        }
        return string.value().trim();
    }

    private static String normalizeCommand(String value) {
        String normalized = Objects.requireNonNull(value, "command").trim();
        if (normalized.isEmpty() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw failure("COMMAND_BINDING_MALFORMED: command must be a non-blank single-line string");
        }
        return normalized;
    }

    private static String normalizeSubcommand(String value) {
        String normalized = Objects.requireNonNull(value, "subcommand").trim();
        if (normalized.isEmpty() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw failure("COMMAND_BINDING_MALFORMED: subcommands must be non-blank single-line strings");
        }
        return normalized;
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }

    private record NodeRef(String nodeId, JsonValue.JsonObject node) {
    }

    private record ParsedBinding(CommandBinding source, String command, List<String> subcommands, boolean structured) {
    }

    private record Assignment(ParsedBinding binding) {
    }

    private record NodeUpdate(JsonValue.JsonObject node, boolean changed) {
    }
}
