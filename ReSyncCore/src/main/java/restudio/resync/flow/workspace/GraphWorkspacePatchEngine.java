package restudio.resync.flow.workspace;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.RepeatableElementId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class GraphWorkspacePatchEngine {
    public static final int MAX_OPERATIONS = 512;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_PATH_SEGMENTS = 64;
    public static final int MAX_VALUE_BYTES = 1_048_576;
    private static final Set<String> OPERATIONS = Set.of("set", "remove", "array_add", "array_remove", "array_reorder");
    private static final Set<String> MANAGED_ROOTS = Set.of("schemaVersion", "resource", "revision", "catalogBinding", "requiredCapabilities");

    public GraphDocument apply(GraphDocument current, List<WorkspacePatch<JsonValue>> patches) {
        Objects.requireNonNull(current, "Current graph document is required");
        JsonObject patched = apply(GraphDocumentCodec.INSTANCE.encode(current), patches);
        return GraphDocumentCodec.INSTANCE.decode(patched);
    }

    public JsonObject apply(JsonObject current, List<WorkspacePatch<JsonValue>> patches) {
        return apply(current, patches, false);
    }

    public JsonObject rebase(JsonObject current, List<WorkspacePatch<JsonValue>> patches) {
        return apply(current, patches, true);
    }

    private JsonObject apply(JsonObject current, List<WorkspacePatch<JsonValue>> patches, boolean rebase) {
        Objects.requireNonNull(current, "Current graph document is required");
        GraphDocumentCodec.INSTANCE.decode(current);
        if (patches == null || patches.isEmpty() || patches.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("Workspace operation count is outside its allowed range");
        }
        List<CheckedPatch> checked = patches.stream().map(this::validate).toList();
        JsonObject next = current;
        for (CheckedPatch patch : checked) {
            next = applyOne(next, patch, rebase);
        }
        validateManagedTransitions(current, next);
        validateOrderedTransitions(current, next, checked);
        validateOrderedSequences(current, next, checked);
        JsonObject normalized = requireObject(normalize(next, ObjectKind.ROOT, null, null), "Graph document");
        return GraphDocumentCodec.INSTANCE.encode(GraphDocumentCodec.INSTANCE.decode(removeCanonicalEmptyOptionals(normalized)));
    }

    public boolean valid(WorkspacePatch<? extends JsonValue> patch) {
        try {
            validate(patch);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private JsonObject applyOne(JsonObject document, CheckedPatch patch, boolean rebase) {
        JsonValue updated = mutate(document, patch.segments(), 0, patch.operation(), patch.value(), ObjectKind.ROOT, null, rebase);
        return requireObject(updated, "Graph document");
    }

    private JsonValue mutate(JsonValue current, List<String> path, int offset, String operation, JsonValue value, ObjectKind objectKind,
                             ArrayKind arrayKind, boolean rebase) {
        String segment = path.get(offset);
        boolean leaf = offset == path.size() - 1;
        if (current instanceof JsonObject object) {
            return mutateObject(object, path, offset, segment, leaf, operation, value, objectKind, rebase);
        }
        if (current instanceof JsonArray array) {
            return mutateArray(array, path, offset, segment, leaf, operation, value, arrayKind, rebase);
        }
        throw new IllegalArgumentException("Workspace path traverses a scalar value");
    }

    private JsonValue mutateObject(JsonObject object, List<String> path, int offset, String segment, boolean leaf, String operation, JsonValue value,
                                   ObjectKind objectKind, boolean rebase) {
        if (objectKind.protectedFields.contains(segment)) {
            throw new IllegalArgumentException("Workspace member identity is managed by the server: " + segment);
        }
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        JsonValue existing = fields.get(segment);
        ArrayKind childKind = objectKind.arrayKind(segment);
        if (leaf) {
            if (childKind != null && ("set".equals(operation) || "remove".equals(operation))) {
                throw new IllegalArgumentException("Known graph arrays must be edited through stable member selectors");
            }
            switch (operation) {
                case "set" -> fields.put(segment, Objects.requireNonNull(value, "Workspace set value is required"));
                case "remove" -> {
                    if (fields.remove(segment) == null && !rebase) {
                        throw new IllegalArgumentException("Workspace member does not exist: " + segment);
                    }
                }
                case "array_add" -> fields.put(segment, addMember(existing != null ? existing : new JsonArray(List.of()), childKind, value, rebase));
                case "array_remove" -> fields.put(segment, removeMember(existing, childKind, value, rebase));
                case "array_reorder" -> {
                    if (objectKind != ObjectKind.REPEATABLE || childKind != ArrayKind.ELEMENTS
                        || !(object.value("ordered") instanceof JsonValue.JsonBoolean ordered) || !ordered.value()) {
                        throw new IllegalArgumentException("array_reorder requires ordered repeatable elements");
                    }
                    fields.put(segment, reorderMembers(existing, childKind, value, rebase));
                }
                default -> throw new IllegalArgumentException("Unsupported workspace operation: " + operation);
            }
            return new JsonObject(fields);
        }
        if (existing == null) {
            if (rebase && childKind != null && offset + 1 == path.size() - 1 && path.get(offset + 1).startsWith("@")
                && "remove".equals(operation)) {
                return object;
            }
            throw new IllegalArgumentException("Workspace path member does not exist: " + segment);
        }
        fields.put(segment, mutate(existing, path, offset + 1, operation, value, ObjectKind.OPAQUE, childKind, rebase));
        return new JsonObject(fields);
    }

    private JsonValue mutateArray(JsonArray array, List<String> path, int offset, String segment, boolean leaf, String operation, JsonValue value,
                                  ArrayKind kind, boolean rebase) {
        if (kind == null) {
            throw new IllegalArgumentException("Unknown arrays are atomic workspace values");
        }
        String selector = selector(segment);
        int index = memberIndex(array, kind, selector);
        if (index < 0) {
            if (leaf && rebase && "remove".equals(operation)) {
                return array;
            }
            throw new IllegalArgumentException("Workspace array member does not exist: " + selector);
        }
        List<JsonValue> values = new ArrayList<>(array.values());
        if (leaf) {
            switch (operation) {
                case "set" -> {
                    JsonValue replacement = Objects.requireNonNull(value, "Workspace set value is required");
                    if (!selector.equals(identity(replacement, kind))) {
                        throw new IllegalArgumentException("Workspace member replacement changed its stable identity");
                    }
                    preserveManagedMember(array.values().get(index), replacement, kind);
                    values.set(index, replacement);
                }
                case "remove" -> values.remove(index);
                default -> throw new IllegalArgumentException("Workspace array members support only set or remove");
            }
        } else {
            values.set(index, mutate(values.get(index), path, offset + 1, operation, value, kind.memberKind(), null, rebase));
        }
        return new JsonArray(values);
    }

    private JsonArray addMember(JsonValue existing, ArrayKind kind, JsonValue value, boolean rebase) {
        JsonArray array = requireKnownArray(existing, kind);
        String identity = identity(Objects.requireNonNull(value, "Workspace array value is required"), kind);
        int currentIndex = memberIndex(array, kind, identity);
        if (currentIndex >= 0) {
            if (rebase && array.values().get(currentIndex).equals(value)) {
                return array;
            }
            throw new IllegalArgumentException("Workspace array already contains member: " + identity);
        }
        List<JsonValue> values = new ArrayList<>(array.values());
        values.add(value);
        return new JsonArray(values);
    }

    private JsonArray removeMember(JsonValue existing, ArrayKind kind, JsonValue value, boolean rebase) {
        if (existing == null && rebase && kind != null) {
            identity(Objects.requireNonNull(value, "Workspace array removal value is required"), kind);
            return new JsonArray(List.of());
        }
        JsonArray array = requireKnownArray(existing, kind);
        JsonValue expected = Objects.requireNonNull(value, "Workspace array removal value is required");
        String identity = identity(expected, kind);
        int index = memberIndex(array, kind, identity);
        if (index < 0 && rebase) {
            return array;
        }
        if (index < 0 || !array.values().get(index).equals(expected)) {
            throw new IllegalArgumentException("Workspace array member changed before exact removal: " + identity);
        }
        List<JsonValue> values = new ArrayList<>(array.values());
        values.remove(index);
        return new JsonArray(values);
    }

    private JsonArray reorderMembers(JsonValue existing, ArrayKind kind, JsonValue value, boolean rebase) {
        JsonArray array = requireKnownArray(existing, kind);
        Reorder reorder = reorder(value);
        List<String> current = array.values().stream().map(member -> identity(member, kind)).toList();
        if (Set.copyOf(current).size() != current.size()) {
            throw new IllegalArgumentException("array_reorder target contains duplicate identities");
        }
        if (rebase && current.equals(reorder.order())) {
            return array;
        }
        if (!current.equals(reorder.expected())) {
            throw new IllegalArgumentException("array_reorder conflicts with the current element order");
        }
        Map<String, JsonValue> members = new LinkedHashMap<>();
        for (int index = 0; index < current.size(); index++) {
            members.put(current.get(index), array.values().get(index));
        }
        return new JsonArray(reorder.order().stream().map(members::get).toList());
    }

    private Reorder reorder(JsonValue value) {
        if (!(value instanceof JsonObject object) || !object.fields().keySet().equals(Set.of("expected", "order"))) {
            throw new IllegalArgumentException("array_reorder value requires exactly expected and order");
        }
        List<String> expected = reorderIds(object.value("expected"), "expected");
        List<String> order = reorderIds(object.value("order"), "order");
        if (expected.equals(order) || !Set.copyOf(expected).equals(Set.copyOf(order))) {
            throw new IllegalArgumentException("array_reorder order must be a different permutation of expected");
        }
        return new Reorder(expected, order);
    }

    private List<String> reorderIds(JsonValue value, String field) {
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException("array_reorder " + field + " must be an array");
        }
        List<String> identities = array.values().stream().map(member -> {
            if (!(member instanceof JsonString string)) {
                throw new IllegalArgumentException("array_reorder identities must be strings");
            }
            String identity = string.value();
            if (!RepeatableElementId.parseCanonicalText(identity).canonicalText().equals(identity)) {
                throw new IllegalArgumentException("array_reorder identities must be canonical");
            }
            return identity;
        }).toList();
        if (Set.copyOf(identities).size() != identities.size()) {
            throw new IllegalArgumentException("array_reorder identities must be unique");
        }
        return identities;
    }

    private JsonArray requireKnownArray(JsonValue value, ArrayKind kind) {
        if (!(value instanceof JsonArray array) || kind == null) {
            throw new IllegalArgumentException("Unknown arrays are atomic workspace values");
        }
        return array;
    }

    private CheckedPatch validate(WorkspacePatch<? extends JsonValue> patch) {
        if (patch == null || patch.op() == null || !OPERATIONS.contains(patch.op()) || !patch.op().equals(patch.op().trim())) {
            throw new IllegalArgumentException("Invalid workspace operation");
        }
        List<String> segments = segments(patch.path());
        if (MANAGED_ROOTS.contains(segments.getFirst())) {
            throw new IllegalArgumentException("Workspace root is managed by the server: " + segments.getFirst());
        }
        JsonValue value = patch.value();
        if ("array_reorder".equals(patch.op()) && !repeatableElementsPath(segments)) {
            throw new IllegalArgumentException("array_reorder requires an ordered repeatable elements path");
        }
        if ("remove".equals(patch.op())) {
            if (value != null && !(value instanceof JsonValue.JsonNull)) {
                throw new IllegalArgumentException("Workspace remove cannot carry a value");
            }
        } else {
            Objects.requireNonNull(value, "Workspace operation value is required");
            if (value.canonicalBytes().length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Workspace operation value is too large");
            }
        }
        if ("array_reorder".equals(patch.op())) {
            reorder(value);
        }
        return new CheckedPatch(patch.op(), segments, value);
    }

    private List<String> segments(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH || path.charAt(0) != '/') {
            throw new IllegalArgumentException("Invalid workspace path");
        }
        String[] raw = path.substring(1).split("/", -1);
        if (raw.length == 0 || raw.length > MAX_PATH_SEGMENTS) {
            throw new IllegalArgumentException("Workspace path depth is outside its allowed range");
        }
        List<String> result = new ArrayList<>(raw.length);
        for (String value : raw) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Workspace path contains an empty segment");
            }
            StringBuilder decoded = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character != '~') {
                    decoded.append(character);
                    continue;
                }
                if (++index >= value.length()) {
                    throw new IllegalArgumentException("Workspace path contains an invalid escape");
                }
                char escaped = value.charAt(index);
                if (escaped == '0') {
                    decoded.append('~');
                } else if (escaped == '1') {
                    decoded.append('/');
                } else {
                    throw new IllegalArgumentException("Workspace path contains an invalid escape");
                }
            }
            result.add(decoded.toString());
        }
        return List.copyOf(result);
    }

    private String selector(String segment) {
        if (segment == null || segment.length() < 2 || segment.charAt(0) != '@') {
            throw new IllegalArgumentException("Workspace arrays require stable @ selectors");
        }
        return segment.substring(1);
    }

    private int memberIndex(JsonArray array, ArrayKind kind, String selector) {
        int found = -1;
        for (int index = 0; index < array.values().size(); index++) {
            if (!selector.equals(identity(array.values().get(index), kind))) {
                continue;
            }
            if (found >= 0) {
                throw new IllegalArgumentException("Workspace array contains a duplicate stable identity: " + selector);
            }
            found = index;
        }
        return found;
    }

    private String identity(JsonValue member, ArrayKind kind) {
        JsonObject object = requireObject(member, "Workspace array member");
        JsonValue identity = object.value(kind.identityField);
        if (kind == ArrayKind.FUNCTIONS) {
            return IdentityCodec.decodeLocator(Objects.requireNonNull(identity, "Function locator is required")).canonicalText();
        }
        if (!(identity instanceof JsonString string) || string.value().isBlank()) {
            throw new IllegalArgumentException("Workspace array member identity is missing: " + kind.identityField);
        }
        return string.value();
    }

    private void preserveManagedMember(JsonValue current, JsonValue replacement, ArrayKind kind) {
        JsonObject before = requireObject(current, "Current workspace array member");
        JsonObject after = requireObject(replacement, "Replacement workspace array member");
        preserveManagedFields(before, after, kind);
        for (String field : kind.memberKind().arrays.keySet()) {
            if (!Objects.equals(before.value(field), after.value(field))) {
                throw new IllegalArgumentException("Nested graph arrays require stable selector edits: " + field);
            }
        }
    }

    private void preserveManagedFields(JsonObject before, JsonObject after, ArrayKind kind) {
        for (String field : kind.memberKind().protectedFields) {
            if (!Objects.equals(before.value(field), after.value(field))) {
                throw new IllegalArgumentException("Workspace member replacement changed managed field: " + field);
            }
        }
    }

    private void validateManagedTransitions(JsonObject before, JsonObject after) {
        for (ArrayKind kind : List.of(ArrayKind.NODES, ArrayKind.FUNCTIONS)) {
            Map<String, JsonObject> original = members(before.value(kind == ArrayKind.NODES ? "nodes" : "functions"), kind);
            Map<String, JsonObject> staged = members(after.value(kind == ArrayKind.NODES ? "nodes" : "functions"), kind);
            original.forEach((id, member) -> {
                JsonObject replacement = staged.get(id);
                if (replacement != null) {
                    preserveManagedFields(member, replacement, kind);
                }
            });
        }
    }

    private Map<String, JsonObject> members(JsonValue value, ArrayKind kind) {
        if (!(value instanceof JsonArray array)) {
            return Map.of();
        }
        Map<String, JsonObject> members = new LinkedHashMap<>();
        for (JsonValue member : array.values()) {
            JsonObject object = requireObject(member, "Workspace array member");
            if (members.put(identity(object, kind), object) != null) {
                throw new IllegalArgumentException("Workspace array contains duplicate stable identity");
            }
        }
        return members;
    }

    private void validateOrderedSequences(JsonObject before, JsonObject after, List<CheckedPatch> patches) {
        Map<String, RepeatableState> original = repeatables(before);
        Set<String> reordered = patches.stream().filter(patch -> "array_reorder".equals(patch.operation()))
            .map(patch -> repeatableKey(patch.segments())).collect(Collectors.toSet());
        repeatables(after).forEach((id, staged) -> {
            RepeatableState current = original.get(id);
            if (current == null || !staged.ordered()) {
                return;
            }
            Set<String> currentIds = Set.copyOf(current.elements());
            Set<String> stagedIds = Set.copyOf(staged.elements());
            List<String> currentCommon = current.elements().stream().filter(stagedIds::contains).toList();
            List<String> stagedCommon = staged.elements().stream().filter(currentIds::contains).toList();
            boolean invalid = !currentCommon.equals(stagedCommon);
            boolean added = false;
            for (String element : staged.elements()) {
                if (!currentIds.contains(element)) {
                    added = true;
                } else if (added) {
                    invalid = true;
                }
            }
            if (invalid && !reordered.contains(id)) {
                throw new IllegalArgumentException("Ordered repeatable element order changed without array_reorder");
            }
        });
    }

    private Map<String, RepeatableState> repeatables(JsonObject graph) {
        Map<String, RepeatableState> result = new LinkedHashMap<>();
        if (!(graph.value("nodes") instanceof JsonArray nodes)) {
            return result;
        }
        for (JsonValue nodeValue : nodes.values()) {
            JsonObject node = requireObject(nodeValue, "Graph node");
            String nodeId = identity(node, ArrayKind.NODES);
            if (!(node.value("repeatables") instanceof JsonArray bindings)) {
                continue;
            }
            for (JsonValue bindingValue : bindings.values()) {
                JsonObject binding = requireObject(bindingValue, "Repeatable binding");
                String groupId = identity(binding, ArrayKind.REPEATABLES);
                JsonValue orderedValue = binding.value("ordered");
                boolean ordered = orderedValue instanceof JsonValue.JsonBoolean booleanValue && booleanValue.value();
                List<String> elements = binding.value("elements") instanceof JsonArray members
                    ? members.values().stream().map(member -> identity(member, ArrayKind.ELEMENTS)).toList() : List.of();
                if (result.put(nodeId + '\u0000' + groupId, new RepeatableState(ordered, elements)) != null) {
                    throw new IllegalArgumentException("Graph contains duplicate repeatable identity");
                }
            }
        }
        return result;
    }

    private void validateOrderedTransitions(JsonObject before, JsonObject staged, List<CheckedPatch> patches) {
        Map<List<String>, OrderedMutation> mutations = new LinkedHashMap<>();
        for (CheckedPatch patch : patches) {
            List<String> path = patch.segments();
            if (path.size() < 4 || !"nodes".equals(path.get(0)) || !path.get(1).startsWith("@")
                || !"repeatables".equals(path.get(2)) || !path.get(3).startsWith("@")) {
                continue;
            }
            List<String> target = List.copyOf(path.subList(0, 4));
            if (path.size() == 4) {
                if ("set".equals(patch.operation())) {
                    mutations.computeIfAbsent(target, ignored -> new OrderedMutation()).orderedChanged = true;
                }
                continue;
            }
            String member = path.get(4);
            OrderedMutation mutation = mutations.computeIfAbsent(target, ignored -> new OrderedMutation());
            if ("ordered".equals(member)) {
                mutation.orderedChanged = true;
            } else if ("elements".equals(member)) {
                mutation.elementChanged = true;
                if (path.size() == 5 && ("array_add".equals(patch.operation()) || "array_remove".equals(patch.operation()))) {
                    String identity = identity(Objects.requireNonNull(patch.value(), "Element mutation value is required"),
                        ArrayKind.ELEMENTS);
                    ("array_add".equals(patch.operation()) ? mutation.added : mutation.removed).add(identity);
                } else if (path.size() == 5 && "array_reorder".equals(patch.operation())) {
                    mutation.reordered = true;
                } else if (path.size() == 6 && path.get(5).startsWith("@") && "remove".equals(patch.operation())) {
                    mutation.removed.add(path.get(5).substring(1));
                }
            }
        }
        mutations.forEach((target, mutation) -> {
            if (mutation.orderedChanged && mutation.elementChanged) {
                throw new IllegalArgumentException("Ordered state cannot change with repeatable element mutations");
            }
            if (mutation.reordered && hasAncestorMutation(target, patches)) {
                throw new IllegalArgumentException("array_reorder cannot accompany repeatable ancestor replacement");
            }
            Set<String> repeated = new HashSet<>(mutation.added);
            repeated.retainAll(mutation.removed);
            if (!repeated.isEmpty() && (ordered(before, target) || ordered(staged, target))) {
                throw new IllegalArgumentException("Ordered repeatable elements cannot remove and re-add the same identity");
            }
        });
    }

    private boolean hasAncestorMutation(List<String> target, List<CheckedPatch> patches) {
        String nodeId = target.get(1).substring(1);
        String groupId = target.get(3).substring(1);
        for (CheckedPatch patch : patches) {
            List<String> path = patch.segments();
            if (("set".equals(patch.operation()) || "remove".equals(patch.operation()))
                && (path.equals(target) || path.equals(target.subList(0, 2)))) {
                return true;
            }
            if (("array_add".equals(patch.operation()) || "array_remove".equals(patch.operation())) && patch.value() != null) {
                if (path.equals(List.of("nodes")) && nodeId.equals(identity(patch.value(), ArrayKind.NODES))) {
                    return true;
                }
                if (path.equals(target.subList(0, 3)) && groupId.equals(identity(patch.value(), ArrayKind.REPEATABLES))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ordered(JsonObject document, List<String> path) {
        JsonValue current = document;
        for (String segment : path) {
            if (current instanceof JsonObject object) {
                current = object.value(segment);
            } else if (current instanceof JsonArray array) {
                String selector = selector(segment);
                current = array.values().stream().filter(value -> selector.equals(anyIdentity(value))).findFirst().orElse(null);
            } else {
                return false;
            }
        }
        if (!(current instanceof JsonObject repeatable)) {
            return false;
        }
        JsonValue value = repeatable.value("ordered");
        return value instanceof JsonValue.JsonBoolean ordered && ordered.value();
    }

    private String anyIdentity(JsonValue value) {
        JsonObject object = requireObject(value, "Workspace array member");
        for (String field : List.of("instanceId", "groupId")) {
            if (object.value(field) instanceof JsonString identity) {
                return identity.value();
            }
        }
        return "";
    }

    private boolean repeatableElementsPath(List<String> path) {
        return path.size() == 5 && "nodes".equals(path.get(0)) && path.get(1).startsWith("@")
            && path.get(1).length() > 1 && "repeatables".equals(path.get(2)) && path.get(3).startsWith("@")
            && path.get(3).length() > 1 && "elements".equals(path.get(4));
    }

    private String repeatableKey(List<String> path) {
        if (!repeatableElementsPath(path)) {
            throw new IllegalArgumentException("array_reorder requires an ordered repeatable elements path");
        }
        return path.get(1).substring(1) + '\u0000' + path.get(3).substring(1);
    }

    private JsonObject removeCanonicalEmptyOptionals(JsonObject document) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(document.fields());
        for (String field : List.of("variables", "functions")) {
            if (fields.get(field) instanceof JsonArray array && array.values().isEmpty()) {
                fields.remove(field);
            }
        }
        return new JsonObject(fields);
    }

    private static final class OrderedMutation {
        private boolean orderedChanged;
        private boolean elementChanged;
        private boolean reordered;
        private final Set<String> added = new HashSet<>();
        private final Set<String> removed = new HashSet<>();
    }

    private record Reorder(List<String> expected, List<String> order) {
    }

    private record RepeatableState(boolean ordered, List<String> elements) {
    }

    private JsonValue normalize(JsonValue value, ObjectKind objectKind, ArrayKind arrayKind, JsonObject arrayParent) {
        if (value instanceof JsonObject object) {
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            object.fields().forEach((name, child) -> fields.put(name,
                normalize(child, ObjectKind.OPAQUE, objectKind.arrayKind(name), object)));
            return new JsonObject(fields);
        }
        if (!(value instanceof JsonArray array)) {
            return value;
        }
        List<JsonValue> values = array.values().stream()
            .map(member -> normalize(member, arrayKind != null ? arrayKind.memberKind() : ObjectKind.OPAQUE, null, null))
            .collect(Collectors.toCollection(ArrayList::new));
        if (arrayKind != null && arrayKind.canonicalOrder(arrayParent)) {
            values.sort(Comparator.comparing(member -> identity(member, arrayKind)));
        }
        return new JsonArray(values);
    }

    private JsonObject requireObject(JsonValue value, String name) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private enum ArrayKind {
        NODES("instanceId", true),
        CONNECTIONS("connectionId", true),
        VARIABLES("variableId", true),
        FUNCTIONS("function", true),
        BRANCHES("branchId", false),
        CASES("caseId", true),
        REPEATABLES("groupId", false),
        ELEMENTS("elementId", false),
        PARAMETERS("parameterId", false);

        private final String identityField;
        private final boolean sorted;

        ArrayKind(String identityField, boolean sorted) {
            this.identityField = identityField;
            this.sorted = sorted;
        }

        private ObjectKind memberKind() {
            return switch (this) {
                case NODES -> ObjectKind.NODE;
                case CONNECTIONS -> ObjectKind.CONNECTION;
                case VARIABLES -> ObjectKind.VARIABLE;
                case FUNCTIONS -> ObjectKind.FUNCTION;
                case BRANCHES -> ObjectKind.BRANCH;
                case CASES -> ObjectKind.CASE;
                case REPEATABLES -> ObjectKind.REPEATABLE;
                case ELEMENTS -> ObjectKind.ELEMENT;
                case PARAMETERS -> ObjectKind.PARAMETER;
            };
        }

        private boolean canonicalOrder(JsonObject parent) {
            if (this != ELEMENTS) {
                return sorted;
            }
            JsonValue ordered = parent != null ? parent.value("ordered") : null;
            return ordered instanceof JsonValue.JsonBoolean booleanValue && !booleanValue.value();
        }
    }

    private enum ObjectKind {
        ROOT(Set.of(), Map.of("nodes", ArrayKind.NODES, "connections", ArrayKind.CONNECTIONS, "variables", ArrayKind.VARIABLES,
            "functions", ArrayKind.FUNCTIONS)),
        NODE(Set.of("instanceId", "definition", "definitionVersion"), Map.of("branches", ArrayKind.BRANCHES,
            "repeatables", ArrayKind.REPEATABLES)),
        CONNECTION(Set.of("connectionId"), Map.of()),
        VARIABLE(Set.of("variableId"), Map.of()),
        FUNCTION(Set.of("function", "revision"), Map.of("inputs", ArrayKind.PARAMETERS, "outputs", ArrayKind.PARAMETERS)),
        BRANCH(Set.of("branchId"), Map.of("cases", ArrayKind.CASES)),
        CASE(Set.of("caseId"), Map.of()),
        REPEATABLE(Set.of("groupId"), Map.of("elements", ArrayKind.ELEMENTS)),
        ELEMENT(Set.of("elementId"), Map.of()),
        PARAMETER(Set.of("parameterId"), Map.of()),
        OPAQUE(Set.of(), Map.of());

        private final Set<String> protectedFields;
        private final Map<String, ArrayKind> arrays;

        ObjectKind(Set<String> protectedFields, Map<String, ArrayKind> arrays) {
            this.protectedFields = protectedFields;
            this.arrays = arrays;
        }

        private ArrayKind arrayKind(String field) {
            return arrays.get(field);
        }
    }

    private record CheckedPatch(String operation, List<String> segments, JsonValue value) {
    }
}
