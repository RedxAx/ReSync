package restudio.resync.server;

import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphOptionValidator {
    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OwnerId.of("resync"),
        OperationId.of("graph-validation"));
    private final OptionCatalogRegistry catalogs;

    public CoreGraphOptionValidator(OptionCatalogRegistry catalogs) {
        this.catalogs = Objects.requireNonNull(catalogs, "Option catalog registry is required");
    }

    public List<Diagnostic> validate(GraphDocument graph, CatalogSnapshot catalog) {
        Objects.requireNonNull(graph, "Graph document is required");
        Objects.requireNonNull(catalog, "Catalog snapshot is required");
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definition(node.definition()).orElse(null);
            if (owned == null) {
                continue;
            }
            validateNode(graph, node, owned.descriptor(), catalog, diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void validateNode(GraphDocument graph, GraphNode node, CatalogNodeDescriptor definition,
                              CatalogSnapshot catalog, List<Diagnostic> diagnostics) {
        Map<PinId, CatalogNodeDescriptor.Pin> pins = new LinkedHashMap<>();
        definition.pins().forEach(pin -> pins.put(pin.id(), pin));
        Map<PinId, TypedValue> ordinary = ordinaryValues(node, definition);
        Scope ordinaryScope = new Scope(null, null, ordinary, inspectorValues(node.inspectorState(), node.inspectorFields()));
        for (CatalogNodeDescriptor.Pin pin : definition.pins()) {
            if (pin.direction() == CatalogNodeDescriptor.Direction.INPUT && !pin.repeatable().enabled()) {
                validatePin(graph, node, definition, pin, ordinaryScope, catalog, diagnostics);
            }
        }
        for (RepeatableBinding binding : node.repeatables()) {
            CatalogNodeDescriptor.RepeatableGroup group = definition.repeatables().stream()
                .filter(candidate -> candidate.id().equals(binding.groupId())).findFirst().orElse(null);
            if (group == null) {
                continue;
            }
            Set<PinId> members = group.members().stream().map(CatalogNodeDescriptor.RepeatableMember::pinId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (members.isEmpty()) {
                definition.pins().stream().filter(pin -> pin.repeatable().enabled()
                    && (pin.repeatable().groupId() == null || pin.repeatable().groupId().equals(group.id())))
                    .map(CatalogNodeDescriptor.Pin::id).forEach(members::add);
            }
            for (RepeatableElement element : binding.elements()) {
                Map<PinId, TypedValue> values = new LinkedHashMap<>(ordinary);
                members.forEach(values::remove);
                for (PinId member : members) {
                    CatalogNodeDescriptor.Pin pin = pins.get(member);
                    PinValue explicit = element.values().get(member);
                    TypedValue value = selected(explicit, pin == null ? null : pin.defaultValue());
                    if (value != null) {
                        values.put(member, value);
                    }
                }
                Scope scope = new Scope(null, element.elementId(), Map.copyOf(values), ordinaryScope.inspectors());
                for (PinId member : members) {
                    CatalogNodeDescriptor.Pin pin = pins.get(member);
                    if (pin != null && pin.direction() == CatalogNodeDescriptor.Direction.INPUT) {
                        validatePin(graph, node, definition, pin, scope, catalog, diagnostics);
                    }
                }
            }
        }
        for (BranchBinding binding : node.branches()) {
            for (BranchCase branchCase : binding.cases()) {
                Map<PinId, TypedValue> values = new LinkedHashMap<>(ordinary);
                branchCase.values().forEach((pinId, pinValue) -> {
                    CatalogNodeDescriptor.Pin pin = pins.get(pinId);
                    TypedValue value = selected(pinValue, pin == null ? null : pin.defaultValue());
                    if (value == null) {
                        values.remove(pinId);
                    } else {
                        values.put(pinId, value);
                    }
                });
                Map<InspectorFieldId, TypedValue> inspectors = new LinkedHashMap<>(ordinaryScope.inspectors());
                inspectors.putAll(branchCase.inspectorState().fields());
                Scope scope = new Scope(binding.branchId(), null, Map.copyOf(values), Map.copyOf(inspectors));
                for (PinId pinId : branchCase.values().keySet()) {
                    CatalogNodeDescriptor.Pin pin = pins.get(pinId);
                    if (pin != null && pin.direction() == CatalogNodeDescriptor.Direction.INPUT && !pin.repeatable().enabled()) {
                        validatePin(graph, node, definition, pin, scope, catalog, diagnostics);
                    }
                }
            }
        }
    }

    private void validatePin(GraphDocument graph, GraphNode node, CatalogNodeDescriptor definition,
                             CatalogNodeDescriptor.Pin pin, Scope scope, CatalogSnapshot catalog,
                             List<Diagnostic> diagnostics) {
        if (pin.optionSource() == null || connected(graph, node.instanceId(), pin.id(), scope)) {
            return;
        }
        TypedValue value = scope.values().get(pin.id());
        if (value == null || value.state() == TypedValue.State.ABSENT || value.state() == TypedValue.State.NULL
            || !visible(graph, node, pin, scope)) {
            return;
        }
        CoreOptionCatalogSupport.Binding binding;
        try {
            if (catalog.optionSource(pin.optionSource()).isEmpty()) {
                diagnostics.add(diagnostic("CATALOG.SELECTOR_UNRESOLVED",
                    graph, node, pin, scope, Map.of("reason", "Option source is unavailable")));
                return;
            }
            binding = CoreOptionCatalogSupport.binding(catalog, pin.optionSource(),
                catalog.optionSource(pin.optionSource()).orElseThrow().descriptor().capability());
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("CATALOG.SELECTOR_UNRESOLVED",
                graph, node, pin, scope, Map.of("reason", safeMessage(failure))));
            return;
        }
        Map<String, Object> context = context(graph, node, definition, pin, scope);
        OptionCatalogRegistry.ResolvedCapture resolved;
        try {
            resolved = catalogs.resolveCapture(binding.providerSourceId(),
                new OptionCatalogQuery(binding.providerSourceId(), context));
        } catch (OptionCatalogRegistry.ProviderUnavailable failure) {
            diagnostics.add(diagnostic("CATALOG.SELECTOR_UNRESOLVED",
                graph, node, pin, scope, Map.of("sourceId", binding.providerSourceId(), "reason", safeMessage(failure))));
            return;
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("CATALOG.SELECTOR_UNRESOLVED", graph, node, pin, scope,
                Map.of("sourceId", binding.providerSourceId(), "reason", safeMessage(failure))));
            return;
        }
        boolean dynamic = resolved.contextKeys().stream().anyMatch(key -> connected(graph, node.instanceId(), key, scope));
        OptionCatalogCapture capture = resolved.capture();
        String status = capture.status().strip().toLowerCase(Locale.ROOT);
        if (dynamic && "invalid".equals(status)) {
            return;
        }
        if (!"available".equals(status)) {
            String code = switch (status) {
                case "permission_restricted", "restricted", "forbidden" -> "RUNTIME.AUTHORIZATION_DENIED";
                default -> "CATALOG.SELECTOR_UNRESOLVED";
            };
            diagnostics.add(diagnostic(code, graph, node, pin, scope,
                Map.of("sourceId", binding.providerSourceId(), "status", status,
                    "reason", capture.diagnostic().isBlank() ? "Option catalog is unavailable" : capture.diagnostic())));
            return;
        }
        if (dynamic) {
            return;
        }
        boolean matched;
        try {
            matched = capture.items().stream().map(item -> materialize(pin.type(), graph, item))
                .anyMatch(value::equals);
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("CATALOG.SELECTOR_UNRESOLVED", graph, node, pin, scope,
                Map.of("sourceId", binding.providerSourceId(), "reason", safeMessage(failure))));
            return;
        }
        if (!matched) {
            diagnostics.add(diagnostic("CATALOG.REFERENCE_UNRESOLVED", graph, node, pin, scope,
                Map.of("sourceId", binding.providerSourceId(),
                    "value", value.canonicalJson())));
        }
    }

    private TypedValue materialize(TypeExpr type, GraphDocument graph, OptionCatalogItem item) {
        return CoreOptionCatalogSupport.value(type, graph.resource().serverId(), item);
    }

    private Map<String, Object> context(GraphDocument graph, GraphNode node, CatalogNodeDescriptor definition,
                                        CatalogNodeDescriptor.Pin pin, Scope scope) {
        Map<String, Object> context = new LinkedHashMap<>();
        scope.values().forEach((pinId, value) -> {
            Object material = CoreOptionCatalogSupport.material(value);
            if (material != null) {
                context.put(pinId.value(), material);
            }
        });
        for (GraphConnection connection : graph.connections()) {
            GraphEndpoint target = connection.target();
            if (target.nodeId().equals(node.instanceId()) && sameScope(target, scope)) {
                context.remove(target.pinId().value());
            }
        }
        context.remove(pin.id().value());
        context.put("$nodeType", definition.id().value());
        context.put("$pin", pin.id().value());
        context.put("$resource", graph.resource().canonicalText());
        return Map.copyOf(context);
    }

    private boolean visible(GraphDocument graph, GraphNode node, CatalogNodeDescriptor.Pin pin, Scope scope) {
        if (!condition(pin.visibility(), scope.inspectors())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : pin.presentation().visibleWhen().entrySet()) {
            if (connected(graph, node.instanceId(), entry.getKey(), scope)) {
                continue;
            }
            TypedValue actual = value(scope.values(), entry.getKey());
            if (!matches(actual, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(TypedValue actual, Object expected) {
        Object material = CoreOptionCatalogSupport.material(actual);
        if (expected instanceof Iterable<?> values) {
            for (Object value : values) {
                if (matchesText(material, String.valueOf(value))) {
                    return true;
                }
            }
            return false;
        }
        for (String value : String.valueOf(expected).split(",")) {
            if (matchesText(material, value.strip())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesText(Object actual, String expected) {
        if (actual == null || expected == null || expected.isBlank()) {
            return false;
        }
        String text = String.valueOf(actual).strip();
        if (expected.endsWith("*")) {
            String prefix = expected.substring(0, expected.length() - 1);
            return text.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return text.equalsIgnoreCase(expected);
    }

    private boolean condition(InspectorCondition condition, Map<InspectorFieldId, TypedValue> values) {
        if (condition == null || condition instanceof InspectorCondition.Always) {
            return true;
        }
        if (condition instanceof InspectorCondition.Present present) {
            TypedValue value = values.get(present.field());
            return value != null && value.state() != TypedValue.State.ABSENT;
        }
        if (condition instanceof InspectorCondition.Equals equals) {
            return Objects.equals(values.get(equals.field()), equals.value());
        }
        if (condition instanceof InspectorCondition.NotEquals notEquals) {
            return !Objects.equals(values.get(notEquals.field()), notEquals.value());
        }
        if (condition instanceof InspectorCondition.All all) {
            return all.conditions().stream().allMatch(value -> condition(value, values));
        }
        if (condition instanceof InspectorCondition.Any any) {
            return any.conditions().stream().anyMatch(value -> condition(value, values));
        }
        if (condition instanceof InspectorCondition.Not not) {
            return !condition(not.condition(), values);
        }
        return false;
    }

    private Map<PinId, TypedValue> ordinaryValues(GraphNode node, CatalogNodeDescriptor definition) {
        Map<PinId, TypedValue> values = new LinkedHashMap<>();
        for (CatalogNodeDescriptor.Pin pin : definition.pins()) {
            if (!pin.repeatable().enabled() && pin.defaultValue() != null) {
                values.put(pin.id(), pin.defaultValue());
            }
        }
        node.values().forEach((pinId, pinValue) -> {
            CatalogNodeDescriptor.Pin pin = definition.pins().stream()
                .filter(candidate -> candidate.id().equals(pinId)).findFirst().orElse(null);
            TypedValue value = selected(pinValue, pin == null ? null : pin.defaultValue());
            if (value == null) {
                values.remove(pinId);
            } else {
                values.put(pinId, value);
            }
        });
        return Map.copyOf(values);
    }

    private TypedValue selected(PinValue explicit, TypedValue fallback) {
        return explicit == null || explicit.value().state() == TypedValue.State.ABSENT ? fallback : explicit.value();
    }

    private Map<InspectorFieldId, TypedValue> inspectorValues(InspectorState state,
                                                               Map<InspectorFieldId, TypedValue> values) {
        Map<InspectorFieldId, TypedValue> result = new LinkedHashMap<>();
        if (state != null) {
            result.putAll(state.fields());
        }
        if (values != null) {
            result.putAll(values);
        }
        return Map.copyOf(result);
    }

    private TypedValue value(Map<PinId, TypedValue> values, String key) {
        try {
            return values.get(PinId.of(key));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private boolean connected(GraphDocument graph, NodeInstanceId nodeId, PinId pinId, Scope scope) {
        return graph.connections().stream().map(GraphConnection::target)
            .anyMatch(target -> target.nodeId().equals(nodeId) && target.pinId().equals(pinId)
                && sameScope(target, scope));
    }

    private boolean connected(GraphDocument graph, NodeInstanceId nodeId, String key, Scope scope) {
        try {
            return connected(graph, nodeId, PinId.of(key), scope);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean sameScope(GraphEndpoint endpoint, Scope scope) {
        return Objects.equals(endpoint.branchId(), scope.branchId())
            && Objects.equals(endpoint.elementId(), scope.elementId());
    }

    private Diagnostic diagnostic(String code, GraphDocument graph, GraphNode node,
                                  CatalogNodeDescriptor.Pin pin, Scope scope, Map<String, ?> evidence) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nodeId", node.instanceId().canonicalText());
        fields.put("pinId", pin.id().canonicalText());
        if (scope.branchId() != null) {
            fields.put("branchId", scope.branchId().canonicalText());
        }
        if (scope.elementId() != null) {
            fields.put("elementId", scope.elementId().canonicalText());
        }
        fields.putAll(evidence);
        String identity = code + '\u0000' + graph.resource().canonicalText() + '\u0000' + fields;
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(MESSAGE_KEY)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
            .serverId(graph.resource().serverId())
            .resource(graph.resource())
            .catalogGeneration(graph.catalogBinding().generation())
            .evidence(fields)
            .build();
    }

    private String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record Scope(BranchId branchId, RepeatableElementId elementId, Map<PinId, TypedValue> values,
                         Map<InspectorFieldId, TypedValue> inspectors) {
    }
}
