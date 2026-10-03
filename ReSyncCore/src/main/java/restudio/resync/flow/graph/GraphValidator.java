package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogFunctionShape;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorField;
import restudio.resync.flow.inspector.InspectorFunctionParameter;
import restudio.resync.flow.inspector.InspectorFunctionSignature;
import restudio.resync.flow.inspector.InspectorRow;
import restudio.resync.flow.inspector.InspectorSection;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class GraphValidator {
    public ValidationResult validate(GraphDocument graph, CatalogSnapshot catalog) {
        return validate(graph, catalog, null);
    }

    public ValidationResult validate(GraphDocument graph, CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest) {
        return validate(graph, catalog, runtimeManifest, null);
    }

    public ValidationResult validate(FunctionSourceDocument source, CatalogSnapshot catalog) {
        return validate(source, catalog, null);
    }

    public ValidationResult validate(FunctionSourceDocument source, CatalogSnapshot catalog,
                                     RuntimeBindingManifest runtimeManifest) {
        if (source == null) {
            return ValidationResult.of(List.of(error("GRAPH.NULL", null, null)));
        }
        return validate(source.graph(), catalog, runtimeManifest, source.signature());
    }

    private ValidationResult validate(GraphDocument graph, CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest,
                                      FunctionSignature functionSignature) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        if (graph == null) {
            diagnostics.add(error("GRAPH.NULL", null, null));
            return ValidationResult.of(diagnostics);
        }
        if (catalog == null) {
            diagnostics.add(error("GRAPH.CATALOG_REQUIRED", null, null));
            return ValidationResult.of(diagnostics);
        }
        validateCatalogBinding(graph, catalog, runtimeManifest, diagnostics);
        validateRequiredCapabilities(graph, catalog, runtimeManifest, diagnostics);

        Map<NodeInstanceId, GraphNode> nodes = new LinkedHashMap<>();
        Map<NodeInstanceId, NodeContext> contexts = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.instanceId(), node) != null) {
                diagnostics.add(error("GRAPH.DUPLICATE_NODE", node.instanceId(), null));
                continue;
            }
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definition(node.definition()).orElse(null);
            if (owned == null) {
                diagnostics.add(error("GRAPH.DEFINITION_MISSING", node.instanceId(), null));
                continue;
            }
            CatalogNodeDescriptor definition = owned.descriptor();
            Map<PinId, FunctionBoundaryPins.EffectivePin> endpointPins;
            try {
                endpointPins = FunctionBoundaryPins.resolve(owned, functionSignature, node);
            } catch (IllegalArgumentException failure) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", node.instanceId(), null));
                endpointPins = FunctionBoundaryPins.resolve(owned, null, node);
            }
            contexts.put(node.instanceId(), new NodeContext(node, definition, owned, endpointPins));
            validateNode(node, definition, owned, catalog, runtimeManifest, endpointPins, diagnostics);
        }

        ConversionGraph conversionGraph = conversionGraph(catalog);
        Map<TypeReference, ConversionGraph.ConversionEdge> conversionEdges = catalog.conversions().stream()
            .map(CatalogOwned::descriptor)
            .collect(Collectors.toUnmodifiableMap(ConversionGraph.ConversionEdge::id, value -> value, (left, right) -> left));
        Set<ConnectionId> connectionIds = new HashSet<>();
        for (GraphConnection connection : graph.connections()) {
            if (!connectionIds.add(connection.connectionId())) {
                diagnostics.add(error("GRAPH.DUPLICATE_CONNECTION", null, null));
            }
            validateEndpoint(connection.source(), contexts, true, diagnostics);
            validateEndpoint(connection.target(), contexts, false, diagnostics);
            validateConnectionTypes(connection, contexts, conversionGraph, conversionEdges, runtimeManifest, diagnostics);
        }
        validateVariables(graph.variables(), diagnostics);
        validateFunctions(graph, catalog, diagnostics);
        validateFunctionIds(graph.functions(), diagnostics);
        return ValidationResult.of(diagnostics);
    }

    public static ValidationResult check(GraphDocument graph, CatalogSnapshot catalog) {
        return new GraphValidator().validate(graph, catalog);
    }

    public static ValidationResult check(GraphDocument graph, CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest) {
        return new GraphValidator().validate(graph, catalog, runtimeManifest);
    }

    private static void validateCatalogBinding(GraphDocument graph, CatalogSnapshot catalog,
                                               RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        CatalogBinding graphBinding = graph.catalogBinding();
        if (graphBinding.generation() != catalog.generation()
            || !graphBinding.catalogChecksum().equals(catalog.contentChecksum())) {
            diagnostics.add(error("GRAPH.CATALOG_MISMATCH", null, null));
        }
        if (runtimeManifest == null) {
            if (!graphBinding.bindingManifestHash().equals(catalog.bindingManifestHash())) {
                diagnostics.add(error("GRAPH.CATALOG_MISMATCH", null, null));
            }
        } else if (!graphBinding.bindingManifestHash().equals(runtimeManifest.bindingManifestHash())) {
            diagnostics.add(error("GRAPH.RUNTIME_FINGERPRINT_MISMATCH", null, null));
        }
    }

    private static void validateRequiredCapabilities(GraphDocument graph, CatalogSnapshot catalog,
                                                      RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        for (ContractRef<?> capability : graph.requiredCapabilities()) {
            if (catalog.capability(capability).isEmpty()) {
                diagnostics.add(error("GRAPH.RUNTIME_CAPABILITY_MISSING", null, null));
                continue;
            }
            if (runtimeManifest != null && !runtimeCapabilityAvailable(capability, runtimeManifest)) {
                diagnostics.add(error("GRAPH.RUNTIME_CAPABILITY_MISSING", null, null));
            }
        }
    }

    private static void validateNode(GraphNode node, CatalogNodeDescriptor definition, CatalogOwned<CatalogNodeDescriptor> owned,
                                     CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest,
                                     Map<PinId, FunctionBoundaryPins.EffectivePin> endpointPins, List<Diagnostic> diagnostics) {
        if (definition.lifecycle() != CatalogNodeDescriptor.Lifecycle.ACTIVE) {
            diagnostics.add(error("GRAPH.DEFINITION_UNAVAILABLE", node.instanceId(), null));
        }
        if (node.definitionVersion() != definition.schemaVersion()) {
            diagnostics.add(error("GRAPH.DEFINITION_VERSION_MISMATCH", node.instanceId(), null));
        }
        Map<PinId, CatalogNodeDescriptor.Pin> pins = new HashMap<>();
        for (CatalogNodeDescriptor.Pin pin : definition.pins()) {
            if (pins.put(pin.id(), pin) != null) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", node.instanceId(), pin.id()));
            }
        }
        node.values().forEach((pinId, value) -> {
            FunctionBoundaryPins.EffectivePin pin = endpointPins.get(pinId);
            if (pin == null) {
                diagnostics.add(error("GRAPH.PIN_UNDECLARED", node.instanceId(), pinId));
            } else {
                if (pin.direction() != CatalogNodeDescriptor.Direction.INPUT) {
                    diagnostics.add(error("GRAPH.OUTPUT_VALUE", node.instanceId(), pinId));
                }
                if (!pin.type().equals(value.value().type())) {
                    diagnostics.add(error("GRAPH.PIN_VALUE_TYPE", node.instanceId(), pinId));
                }
            }
        });
        validateInspector(node, definition, owned, catalog, diagnostics);
        validateNodeConditions(node, definition, owned, catalog, pins, diagnostics);
        validateMode(node, definition, diagnostics);
        validateBranches(node, definition, pins, diagnostics);
        validateRepeatables(node, definition, pins, diagnostics);
        validateNodeCapabilities(node, definition, catalog, runtimeManifest, diagnostics);
        validateRuntimeBinding(node, definition, catalog, runtimeManifest, diagnostics);
    }

    private static void validateNodeCapabilities(GraphNode node, CatalogNodeDescriptor definition, CatalogSnapshot catalog,
                                                 RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        for (ContractRef<?> capability : definition.requiredCapabilities()) {
            if (catalog.capability(capability).isEmpty()) {
                diagnostics.add(error("GRAPH.RUNTIME_CAPABILITY_MISSING", node.instanceId(), null));
            } else if (runtimeManifest != null && !runtimeCapabilityAvailable(capability, runtimeManifest)) {
                diagnostics.add(error("GRAPH.RUNTIME_CAPABILITY_MISSING", node.instanceId(), null));
            }
        }
    }

    private static void validateRuntimeBinding(GraphNode node, CatalogNodeDescriptor definition, CatalogSnapshot catalog,
                                               RuntimeBindingManifest manifest, List<Diagnostic> diagnostics) {
        RuntimeBindingKey key = new RuntimeBindingKey(definition.handler().capability(), definition.handler().operation());
        RuntimeOperationDescriptor requirement = catalog.runtimeRequirements().stream()
            .map(CatalogOwned::descriptor)
            .filter(value -> value.key().equals(key))
            .findFirst()
            .orElse(null);
        if (requirement == null) {
            diagnostics.add(error("GRAPH.RUNTIME_BINDING_MISSING", node.instanceId(), null));
        } else {
            List<TypeExpr> inputs = inputTypes(definition);
            List<TypeExpr> outputs = outputTypes(definition);
            if (!inputs.equals(requirement.inputs()) || !definition.semantics().equals(requirement.semantics())) {
                diagnostics.add(error("GRAPH.RUNTIME_INPUT_TYPES", node.instanceId(), null));
            }
            if (!outputs.equals(requirement.outputs())) {
                diagnostics.add(error("GRAPH.RUNTIME_OUTPUT_TYPES", node.instanceId(), null));
            }
        }
        if (manifest == null) {
            return;
        }
        RuntimeBindingDescriptor binding = manifest.binding(key).orElse(null);
        if (binding == null) {
            diagnostics.add(error("GRAPH.RUNTIME_BINDING_MISSING", node.instanceId(), null));
            return;
        }
        if (!binding.available()) {
            diagnostics.add(error("GRAPH.RUNTIME_BINDING_UNAVAILABLE", node.instanceId(), null));
        }
        RuntimeProviderDescriptor provider = provider(manifest, binding);
        if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
            diagnostics.add(error("GRAPH.RUNTIME_PROVIDER_UNAVAILABLE", node.instanceId(), null));
        }
        if (!manifest.executionFingerprints().containsKey(key)
            || !manifest.executionFingerprints().get(key).equals(binding.executionFingerprint(manifest.invalidationInputs().get(key)))) {
            diagnostics.add(error("GRAPH.RUNTIME_FINGERPRINT_MISMATCH", node.instanceId(), null));
        }
        if (!inputTypes(definition).equals(binding.inputs()) || !definition.semantics().equals(binding.semantics())) {
            diagnostics.add(error("GRAPH.RUNTIME_INPUT_TYPES", node.instanceId(), null));
        }
        if (!outputTypes(definition).equals(binding.outputs())) {
            diagnostics.add(error("GRAPH.RUNTIME_OUTPUT_TYPES", node.instanceId(), null));
        }
    }

    private static void validateMode(GraphNode node, CatalogNodeDescriptor definition, List<Diagnostic> diagnostics) {
        if (definition.modes().isEmpty()) {
            if (node.modeId() != null) {
                diagnostics.add(error("GRAPH.MODE_MISSING", node.instanceId(), null));
            }
            return;
        }
        if (node.modeId() == null) {
            diagnostics.add(error("GRAPH.MODE_MISSING", node.instanceId(), null));
            return;
        }
        CatalogNodeDescriptor.Mode mode = definition.modes().stream().filter(value -> value.id().equals(node.modeId())).findFirst().orElse(null);
        if (mode == null) {
            diagnostics.add(error("GRAPH.MODE_MISSING", node.instanceId(), null));
        }
    }

    private static void validateBranches(GraphNode node, CatalogNodeDescriptor definition,
                                         Map<PinId, CatalogNodeDescriptor.Pin> pins, List<Diagnostic> diagnostics) {
        Set<BranchId> branchIds = new HashSet<>();
        for (BranchBinding branch : node.branches()) {
            if (!branchIds.add(branch.branchId())) {
                diagnostics.add(error("GRAPH.DUPLICATE_BRANCH", node.instanceId(), null));
            }
            CatalogNodeDescriptor.Branch descriptor = definition.branches().stream()
                .filter(value -> value.id().equals(branch.branchId())).findFirst().orElse(null);
            if (descriptor == null) {
                diagnostics.add(error("GRAPH.BRANCH_MISSING", node.instanceId(), null));
                continue;
            }
            Set<String> descriptorCases = descriptor.cases().stream().map(value -> value.id().canonicalText())
                .collect(Collectors.toSet());
            Set<String> boundCases = new HashSet<>();
            for (BranchCase branchCase : branch.cases()) {
                if (!boundCases.add(branchCase.caseId().canonicalText()) || !descriptorCases.contains(branchCase.caseId().canonicalText())) {
                    diagnostics.add(error("GRAPH.CASE_MISSING", node.instanceId(), null));
                }
                validatePinValues(node.instanceId(), branchCase.values(), pins, true, diagnostics);
                validateInspectorState(node, branchCase.inspectorState(), Map.<InspectorFieldId, InspectorField>of(), diagnostics);
            }
            if (!descriptorCases.contains(branch.selectedCaseId().canonicalText()) || !boundCases.contains(branch.selectedCaseId().canonicalText())) {
                diagnostics.add(error("GRAPH.CASE_MISSING", node.instanceId(), null));
            }
        }
    }

    private static void validateRepeatables(GraphNode node, CatalogNodeDescriptor definition,
                                             Map<PinId, CatalogNodeDescriptor.Pin> pins, List<Diagnostic> diagnostics) {
        Set<RepeatableGroupId> groups = new HashSet<>();
        Set<RepeatableElementId> elements = new HashSet<>();
        for (RepeatableBinding repeatable : node.repeatables()) {
            if (!groups.add(repeatable.groupId())) {
                diagnostics.add(error("GRAPH.DUPLICATE_REPEATABLE_GROUP", node.instanceId(), null));
            }
            CatalogNodeDescriptor.RepeatableGroup descriptor = definition.repeatables().stream()
                .filter(value -> value.id().equals(repeatable.groupId())).findFirst().orElse(null);
            if (descriptor == null) {
                diagnostics.add(error("GRAPH.REPEATABLE_GROUP_MISSING", node.instanceId(), null));
                continue;
            }
            if (repeatable.elements().size() < descriptor.minimum() || repeatable.elements().size() > descriptor.maximum()) {
                diagnostics.add(error("GRAPH.REPEATABLE_BOUNDS", node.instanceId(), null));
            }
            if (repeatable.ordered() != descriptor.ordered()) {
                diagnostics.add(error("GRAPH.REPEATABLE_ORDERING", node.instanceId(), null));
            }
            Map<PinId, CatalogNodeDescriptor.Pin> memberPins = new HashMap<>();
            if (descriptor.members().isEmpty()) {
                for (CatalogNodeDescriptor.Pin pin : pins.values()) {
                    if (pin.repeatable().enabled() && pin.repeatable().groupId() == null) {
                        memberPins.put(pin.id(), pin);
                    }
                }
            } else {
                for (CatalogNodeDescriptor.RepeatableMember member : descriptor.members()) {
                    CatalogNodeDescriptor.Pin pin = pins.get(member.pinId());
                    if (pin != null) {
                        memberPins.put(member.pinId(), pin);
                    }
                }
            }
            for (RepeatableElement element : repeatable.elements()) {
                if (!elements.add(element.elementId())) {
                    diagnostics.add(error("GRAPH.DUPLICATE_REPEATABLE_ELEMENT", node.instanceId(), null));
                }
                validatePinValues(node.instanceId(), element.values(), memberPins, true, diagnostics);
            }
        }
        for (CatalogNodeDescriptor.RepeatableGroup descriptor : definition.repeatables()) {
            if (descriptor.minimum() > 0 && !groups.contains(descriptor.id())) {
                diagnostics.add(error("GRAPH.REPEATABLE_BOUNDS", node.instanceId(), null));
            }
        }
    }

    private static void validateNodeConditions(GraphNode node, CatalogNodeDescriptor definition,
                                               CatalogOwned<CatalogNodeDescriptor> owned, CatalogSnapshot catalog,
                                               Map<PinId, CatalogNodeDescriptor.Pin> pins, List<Diagnostic> diagnostics) {
        Map<InspectorFieldId, TypedValue> values = inspectorValues(node);
        Set<InspectorFieldId> knownFields = inspectorFieldMap(definition, owned, catalog).keySet();
        for (CatalogNodeDescriptor.Pin pin : pins.values()) {
            validateConditionReferences(pin.visibility(), knownFields, node.instanceId(), pin.id(), diagnostics);
            if (node.values().containsKey(pin.id())) {
                validateConditionValue(pin.visibility(), values, node.instanceId(), pin.id(), diagnostics);
            }
        }
        definition.modes().forEach(mode -> {
            validateConditionReferences(mode.visibility(), knownFields, node.instanceId(), null, diagnostics);
            if (node.modeId() != null && node.modeId().equals(mode.id())) {
                validateConditionValue(mode.visibility(), values, node.instanceId(), null, diagnostics);
            }
        });
    }

    private static void validateInspector(GraphNode node, CatalogNodeDescriptor definition,
                                          CatalogOwned<CatalogNodeDescriptor> owned, CatalogSnapshot catalog,
                                          List<Diagnostic> diagnostics) {
        Map<InspectorFieldId, InspectorField> fields = inspectorFieldMap(definition, owned, catalog);
        Map<InspectorFieldId, TypedValue> values = inspectorValues(node);
        Set<InspectorFieldId> known = fields.keySet();
        for (Map.Entry<InspectorFieldId, TypedValue> entry : values.entrySet()) {
            InspectorField field = fields.get(entry.getKey());
            if (field == null) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", node.instanceId(), null));
                continue;
            }
            if (!field.valueType().equals(entry.getValue().type())) {
                diagnostics.add(error("GRAPH.PIN_VALUE_TYPE", node.instanceId(), null));
            }
            validateConditionReferences(field.visibility(), known, node.instanceId(), null, diagnostics);
            validateConditionValue(field.visibility(), values, node.instanceId(), null, diagnostics);
        }
        validateInspectorState(node, node.inspectorState(), fields, diagnostics);
    }

    private static void validateInspectorState(GraphNode node, InspectorState state,
                                               Map<InspectorFieldId, ? extends InspectorField> fields, List<Diagnostic> diagnostics) {
        Map<InspectorFieldId, TypedValue> values = new LinkedHashMap<>(state.fields());
        state.legacyFields().forEach((key, value) -> values.put(InspectorFieldId.of(key.value()), value.value()));
        for (Map.Entry<InspectorFieldId, TypedValue> entry : values.entrySet()) {
            if (!fields.isEmpty() && !fields.containsKey(entry.getKey())) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", node.instanceId(), null));
            }
            InspectorField field = fields.get(entry.getKey());
            if (field != null && !field.valueType().equals(entry.getValue().type())) {
                diagnostics.add(error("GRAPH.PIN_VALUE_TYPE", node.instanceId(), null));
            }
        }
    }

    private static Map<InspectorFieldId, InspectorField> inspectorFieldMap(CatalogNodeDescriptor definition,
                                                                            CatalogOwned<CatalogNodeDescriptor> owned,
                                                                            CatalogSnapshot catalog) {
        if (definition.inspector() == null) {
            return Map.of();
        }
        InspectorDescriptor descriptor = catalog.inspector(ContractRef.of(owned.key().owner(), definition.inspector()))
            .map(CatalogOwned::descriptor).orElse(null);
        if (descriptor == null) {
            return Map.of();
        }
        LinkedHashMap<InspectorFieldId, InspectorField> fields = new LinkedHashMap<>();
        for (InspectorSection section : descriptor.sections()) {
            for (InspectorRow row : section.rows()) {
                for (InspectorField field : row.fields()) {
                    collectFields(field, fields);
                }
            }
        }
        return Map.copyOf(fields);
    }

    private static void collectFields(InspectorField field, Map<InspectorFieldId, InspectorField> fields) {
        fields.putIfAbsent(field.id(), field);
        field.children().forEach(child -> collectFields(child, fields));
    }

    private static void validateConditionReferences(InspectorCondition condition, Set<InspectorFieldId> known,
                                                    NodeInstanceId nodeId, PinId pinId, List<Diagnostic> diagnostics) {
        if (condition == null) {
            return;
        }
        Set<InspectorFieldId> references = condition.references();
        for (InspectorFieldId reference : references) {
            if (!known.contains(reference)) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", nodeId, pinId));
            }
        }
    }

    private static void validateConditionValue(InspectorCondition condition, Map<InspectorFieldId, TypedValue> values,
                                                NodeInstanceId nodeId, PinId pinId, List<Diagnostic> diagnostics) {
        if (condition == null || !condition.references().stream().allMatch(values::containsKey)) {
            return;
        }
        if (!conditionValue(condition, values)) {
            diagnostics.add(error("GRAPH.PIN_UNRESOLVED", nodeId, pinId));
        }
    }

    private static boolean conditionValue(InspectorCondition condition, Map<InspectorFieldId, TypedValue> values) {
        if (condition instanceof InspectorCondition.Always) {
            return true;
        }
        if (condition instanceof InspectorCondition.Present present) {
            return presentValue(values.get(present.field()));
        }
        if (condition instanceof InspectorCondition.Equals equals) {
            return Objects.equals(values.get(equals.field()), equals.value());
        }
        if (condition instanceof InspectorCondition.NotEquals notEquals) {
            return !Objects.equals(values.get(notEquals.field()), notEquals.value());
        }
        if (condition instanceof InspectorCondition.All all) {
            return all.conditions().stream().allMatch(value -> conditionValue(value, values));
        }
        if (condition instanceof InspectorCondition.Any any) {
            return any.conditions().stream().anyMatch(value -> conditionValue(value, values));
        }
        if (condition instanceof InspectorCondition.Not not) {
            return !conditionValue(not.condition(), values);
        }
        return false;
    }

    private static boolean presentValue(TypedValue value) {
        return value != null && value.state() != TypedValue.State.ABSENT;
    }

    private static Map<InspectorFieldId, TypedValue> inspectorValues(GraphNode node) {
        LinkedHashMap<InspectorFieldId, TypedValue> values = new LinkedHashMap<>(node.inspectorFields());
        node.inspector().forEach((key, value) -> values.put(InspectorFieldId.of(key.value()), value.value()));
        values.putAll(node.inspectorState().fields());
        node.inspectorState().legacyFields().forEach((key, value) -> values.put(InspectorFieldId.of(key.value()), value.value()));
        return values;
    }

    private static void validatePinValues(NodeInstanceId nodeId, Map<PinId, PinValue> values,
                                          Map<PinId, CatalogNodeDescriptor.Pin> pins, boolean inputs,
                                          List<Diagnostic> diagnostics) {
        values.forEach((pinId, pinValue) -> {
            CatalogNodeDescriptor.Pin pin = pins.get(pinId);
            if (pin == null) {
                diagnostics.add(error("GRAPH.PIN_UNDECLARED", nodeId, pinId));
                return;
            }
            if (inputs && pin.direction() != CatalogNodeDescriptor.Direction.INPUT) {
                diagnostics.add(error("GRAPH.OUTPUT_VALUE", nodeId, pinId));
            }
            if (!pinValue.value().type().equals(pin.type())) {
                diagnostics.add(error("GRAPH.PIN_VALUE_TYPE", nodeId, pinId));
            }
        });
    }

    private static void validateEndpoint(GraphEndpoint endpoint, Map<NodeInstanceId, NodeContext> contexts,
                                         boolean source, List<Diagnostic> diagnostics) {
        NodeContext context = contexts.get(endpoint.nodeId());
        if (context == null) {
            diagnostics.add(error("GRAPH.ENDPOINT_NODE_MISSING", endpoint.nodeId(), endpoint.pinId()));
            return;
        }
        FunctionBoundaryPins.EffectivePin pin = context.endpointPins().get(endpoint.pinId());
        if (pin == null) {
            diagnostics.add(error("GRAPH.ENDPOINT_PIN_MISSING", endpoint.nodeId(), endpoint.pinId()));
            return;
        }
        CatalogNodeDescriptor.Direction expected = source ? CatalogNodeDescriptor.Direction.OUTPUT : CatalogNodeDescriptor.Direction.INPUT;
        if (pin.direction() != expected) {
            diagnostics.add(error("GRAPH.ENDPOINT_DIRECTION", endpoint.nodeId(), endpoint.pinId()));
        }
        if (endpoint.elementId() != null) {
            RepeatableGroupId groupId = pin.repeatable().groupId();
            boolean legacy = groupId == null && pin.repeatable().enabled()
                && context.definition().repeatables().stream().anyMatch(group -> group.members().isEmpty());
            if (!pin.repeatable().enabled() || (groupId == null && !legacy)) {
                diagnostics.add(error("GRAPH.ENDPOINT_REPEATABLE_UNSUPPORTED", endpoint.nodeId(), endpoint.pinId()));
            }
            boolean present = (groupId != null || legacy) && context.node().repeatables().stream()
                .filter(group -> groupId != null ? group.groupId().equals(groupId)
                    : legacyRepeatableGroup(context.definition(), group.groupId()))
                .anyMatch(group -> group.elements().stream().anyMatch(element -> element.elementId().equals(endpoint.elementId())));
            if (!present) {
                diagnostics.add(error("GRAPH.ENDPOINT_ELEMENT_MISSING", endpoint.nodeId(), endpoint.pinId()));
            }
        }
        if (endpoint.branchId() != null) {
            boolean present = context.node().branches().stream().anyMatch(branch -> branch.branchId().equals(endpoint.branchId()));
            boolean declared = context.definition().branches().stream().anyMatch(branch -> branch.id().equals(endpoint.branchId()));
            if (!present || !declared) {
                diagnostics.add(error("GRAPH.ENDPOINT_BRANCH_MISSING", endpoint.nodeId(), endpoint.pinId()));
            }
        }
    }

    private static boolean legacyRepeatableGroup(CatalogNodeDescriptor definition, RepeatableGroupId groupId) {
        return definition.repeatables().stream()
            .anyMatch(group -> group.id().equals(groupId) && group.members().isEmpty());
    }

    private static void validateConnectionTypes(GraphConnection connection, Map<NodeInstanceId, NodeContext> contexts,
                                                ConversionGraph conversionGraph,
                                                Map<TypeReference, ConversionGraph.ConversionEdge> conversionEdges,
                                                RuntimeBindingManifest manifest, List<Diagnostic> diagnostics) {
        NodeContext sourceContext = contexts.get(connection.source().nodeId());
        NodeContext targetContext = contexts.get(connection.target().nodeId());
        if (sourceContext == null || targetContext == null) {
            return;
        }
        FunctionBoundaryPins.EffectivePin source = sourceContext.endpointPins().get(connection.source().pinId());
        FunctionBoundaryPins.EffectivePin target = targetContext.endpointPins().get(connection.target().pinId());
        if (source == null || target == null || directlyAssignable(source.type(), target.type())) {
            return;
        }
        ConversionGraph.ConversionPath path;
        try {
            path = conversionGraph.resolve(source.type(), target.type());
        } catch (ConversionGraph.ResolutionException exception) {
            String code = exception.reason() == ConversionGraph.Reason.AMBIGUOUS
                ? "GRAPH.PIN_CONVERSION_AMBIGUOUS" : "GRAPH.PIN_CONVERSION_MISSING";
            diagnostics.add(error(code, connection.source().nodeId(), connection.source().pinId()));
            return;
        }
        if (manifest == null) {
            return;
        }
        for (TypeReference conversionId : path.edgeIds()) {
            ConversionGraph.ConversionEdge edge = conversionEdges.get(conversionId);
            if (edge == null) {
                diagnostics.add(error("GRAPH.PIN_CONVERSION_MISSING", connection.source().nodeId(), connection.source().pinId()));
                continue;
            }
            RuntimeBindingKey key = new RuntimeBindingKey(edge.capability(), edge.operation());
            RuntimeBindingDescriptor binding = manifest.binding(key).orElse(null);
            if (binding == null) {
                diagnostics.add(error("GRAPH.RUNTIME_BINDING_MISSING", connection.source().nodeId(), connection.source().pinId()));
                continue;
            }
            if (!conversionSignature(edge, binding)) {
                diagnostics.add(error("GRAPH.PIN_CONVERSION_MISSING", connection.source().nodeId(), connection.source().pinId()));
            }
            if (!binding.available()) {
                diagnostics.add(error("GRAPH.RUNTIME_BINDING_UNAVAILABLE", connection.source().nodeId(), connection.source().pinId()));
            }
            RuntimeProviderDescriptor provider = provider(manifest, binding);
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                diagnostics.add(error("GRAPH.RUNTIME_PROVIDER_UNAVAILABLE", connection.source().nodeId(), connection.source().pinId()));
            }
            if (!manifest.executionFingerprints().containsKey(key)
                || !manifest.executionFingerprints().get(key).equals(binding.executionFingerprint(manifest.invalidationInputs().get(key)))) {
                diagnostics.add(error("GRAPH.RUNTIME_FINGERPRINT_MISMATCH", connection.source().nodeId(), connection.source().pinId()));
            }
        }
    }

    static boolean conversionSignature(ConversionGraph.ConversionEdge edge, RuntimeBindingDescriptor binding) {
        return binding.inputs().equals(List.of(edge.source())) && binding.outputs().equals(List.of(edge.target()));
    }

    static boolean directlyAssignable(TypeExpr source, TypeExpr target) {
        if (source.equals(target) || typeVariable(source) || typeVariable(target) || builtin(target, "any")
            || builtin(target, "string")
            || builtin(source, "any")) {
            return true;
        }
        if (source instanceof TypeExpr.Named sourceNamed && target instanceof TypeExpr.Named targetNamed) {
            if (sourceNamed.reference().equals(targetNamed.reference())) {
                return sourceNamed.arguments().size() == targetNamed.arguments().size()
                    && indexesAssignable(sourceNamed.arguments(), targetNamed.arguments());
            }
            return builtinSubtype(sourceNamed.reference(), targetNamed.reference());
        }
        if (source instanceof TypeExpr.ListType sourceList && target instanceof TypeExpr.ListType targetList) {
            return directlyAssignable(sourceList.element(), targetList.element());
        }
        if (source instanceof TypeExpr.OptionalType sourceOptional && target instanceof TypeExpr.OptionalType targetOptional) {
            return directlyAssignable(sourceOptional.element(), targetOptional.element());
        }
        if (source instanceof TypeExpr.MapType sourceMap && target instanceof TypeExpr.MapType targetMap) {
            return directlyAssignable(sourceMap.key(), targetMap.key())
                && directlyAssignable(sourceMap.value(), targetMap.value());
        }
        if (source instanceof TypeExpr.TupleType sourceTuple && target instanceof TypeExpr.TupleType targetTuple) {
            return sourceTuple.elements().size() == targetTuple.elements().size()
                && indexesAssignable(sourceTuple.elements(), targetTuple.elements());
        }
        if (source instanceof TypeExpr.ResultType sourceResult && target instanceof TypeExpr.ResultType targetResult) {
            return directlyAssignable(sourceResult.success(), targetResult.success())
                && directlyAssignable(sourceResult.failure(), targetResult.failure());
        }
        return false;
    }

    private static boolean indexesAssignable(List<TypeExpr> source, List<TypeExpr> target) {
        for (int index = 0; index < source.size(); index++) {
            if (!directlyAssignable(source.get(index), target.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean typeVariable(TypeExpr type) {
        return type instanceof TypeExpr.Named named && "type".equals(named.reference().ownerId())
            && named.arguments().isEmpty();
    }

    private static boolean builtin(TypeExpr type, String id) {
        return type instanceof TypeExpr.Named named && "builtin".equals(named.reference().ownerId())
            && id.equals(named.reference().localId()) && named.arguments().isEmpty();
    }

    private static boolean builtinSubtype(TypeReference source, TypeReference target) {
        if (!"builtin".equals(source.ownerId()) || !"builtin".equals(target.ownerId())) {
            return false;
        }
        String current = source.localId();
        while ((current = builtinParent(current)) != null) {
            if (current.equals(target.localId())) {
                return true;
            }
        }
        return false;
    }

    private static String builtinParent(String type) {
        return switch (type) {
            case "player" -> "living_entity";
            case "living_entity" -> "entity";
            case "integer", "float", "instant", "duration", "seed" -> "number";
            case "itemstack" -> "item";
            case "item" -> "material";
            case "location", "vector2", "vector3" -> "vector";
            case "function", "flow_id", "command_id", "custom_content_id", "gui_id", "scoreboard_id", "tab_id",
                 "chat_id", "motd_profile_id", "message_rule_id", "recipe_id", "text_template_id",
                 "advancement_tree_id", "dialog_id", "trade_profile_id", "npc_id", "loot_table_id", "worldgen_id",
                 "component", "worldgen_feature", "worldgen_features", "worldgen_structures", "worldgen_spawns" -> "string";
            case "function_definition", "command_definition" -> "flow_definition";
            case "variable_reference", "timer_reference", "schedule_reference", "permission_track", "gui_session",
                 "sidebar_session", "tab_application", "merchant", "trade_session", "placed_content", "structure",
                 "worldgen_project", "player_identity", "network_node", "network_route" -> "resource_reference";
            case "rgb_color" -> "color";
            case "worldgen_job" -> "job_reference";
            default -> null;
        };
    }

    private static void validateVariables(List<GraphVariable> variables, List<Diagnostic> diagnostics) {
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (GraphVariable variable : variables) {
            if (!ids.add(variable.variableId()) || !names.add(variable.name())) {
                diagnostics.add(error("IDENTITY.COLLISION", null, null));
            }
            if (variable.value() != null && !variable.type().equals(variable.value().type())) {
                diagnostics.add(error("GRAPH.PIN_VALUE_TYPE", null, null));
            }
        }
    }

    private static void validateFunctions(GraphDocument graph, CatalogSnapshot catalog, List<Diagnostic> diagnostics) {
        List<SignatureRef> signatures = catalog.inspectors().stream()
            .flatMap(owned -> owned.descriptor().functionSignatures().stream().map(signature -> new SignatureRef(owned, signature)))
            .toList();
        Set<String> functions = new HashSet<>();
        for (FunctionBinding function : graph.functions()) {
            if (!functions.add(function.function().canonicalText())) {
                diagnostics.add(error("IDENTITY.COLLISION", null, null));
            }
            if (!function.function().serverId().equals(graph.resource().serverId())) {
                diagnostics.add(error("GRAPH.DEFINITION_MISSING", null, null));
                continue;
            }
            List<CatalogOwned<CatalogNodeDescriptor>> advertised = catalog.definitions().stream()
                .filter(owned -> advertises(owned, function)).toList();
            if (!advertised.isEmpty()) {
                validateAdvertisedFunction(function, advertised, diagnostics);
                continue;
            }
            SignatureRef signature = findSignature(function, signatures);
            if (signature == null) {
                diagnostics.add(error("GRAPH.DEFINITION_MISSING", null, null));
                continue;
            }
            List<FunctionParameter> inputs = function.inputs();
            List<InspectorFunctionParameter> expected = signature.signature().parameters();
            Map<FunctionParameterId, InspectorFunctionParameter> expectedById = new LinkedHashMap<>();
            boolean duplicateExpected = false;
            for (InspectorFunctionParameter parameter : expected) {
                if (expectedById.put(parameter.id(), parameter) != null) {
                    duplicateExpected = true;
                }
            }
            Set<FunctionParameterId> actualIds = new LinkedHashSet<>();
            for (FunctionParameter parameter : inputs) {
                actualIds.add(parameter.parameterId());
            }
            if (duplicateExpected || actualIds.size() != inputs.size() || !actualIds.equals(expectedById.keySet())) {
                diagnostics.add(error("GRAPH.PIN_TYPE_MISMATCH", null, null));
            } else {
                for (FunctionParameter input : inputs) {
                    InspectorFunctionParameter expectedParameter = expectedById.get(input.parameterId());
                    if (!input.type().equals(expectedParameter.type())) {
                        diagnostics.add(error("GRAPH.PIN_TYPE_MISMATCH", null, null));
                    }
                }
            }
            if (function.outputs().size() != 1 || !function.outputs().getFirst().type().equals(signature.signature().returnType())) {
                diagnostics.add(error("GRAPH.PIN_TYPE_MISMATCH", null, null));
            }
            Object expectedRevision = function.unknown().get("catalogRevision");
            if (expectedRevision instanceof Number number && number.longValue() != function.revision()) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", null, null));
            }
            Object expectedSignature = function.unknown().get("signatureId");
            if (expectedSignature != null && !signature.signature().id().value().equals(String.valueOf(expectedSignature))) {
                diagnostics.add(error("GRAPH.PIN_UNRESOLVED", null, null));
            }
        }
    }

    private static boolean advertises(CatalogOwned<CatalogNodeDescriptor> owned, FunctionBinding function) {
        Object identity = owned.descriptor().metadata().get("customFunctionIdentity");
        return owned.key().owner().equals(function.function().owner())
            && identity instanceof Map<?, ?> values && function.function().id().equals(values.get("id"));
    }

    private static void validateAdvertisedFunction(FunctionBinding function,
                                                    List<CatalogOwned<CatalogNodeDescriptor>> advertised,
                                                    List<Diagnostic> diagnostics) {
        if (advertised.size() != 1 || !"function".equals(function.function().resourceType().value())) {
            diagnostics.add(error("GRAPH.DEFINITION_MISSING", null, null));
            return;
        }
        CatalogOwned<CatalogNodeDescriptor> owned = advertised.getFirst();
        CatalogNodeDescriptor definition = owned.descriptor();
        CatalogFunctionShape shape = CatalogFunctionShape.from(owned.key().owner(), definition).orElse(null);
        if (shape == null || definition.lifecycle() != CatalogNodeDescriptor.Lifecycle.ACTIVE
            || !owned.key().owner().equals(definition.handler().capability().owner())
            || !owned.key().owner().equals(definition.handler().operation().owner())) {
            diagnostics.add(error("GRAPH.DEFINITION_MISSING", null, null));
            return;
        }
        Map<PinId, TypeExpr> actual = new LinkedHashMap<>();
        function.inputs().forEach(parameter -> actual.put(PinId.of("function-input-" + parameter.parameterId().canonicalText()), parameter.type()));
        function.outputs().forEach(parameter -> actual.put(PinId.of("function-output-" + parameter.parameterId().canonicalText()), parameter.type()));
        Map<PinId, TypeExpr> expected = new LinkedHashMap<>();
        shape.parameters().forEach((id, pin) -> expected.put(id, pin.type()));
        if (!actual.equals(expected)) {
            diagnostics.add(error("GRAPH.PIN_TYPE_MISMATCH", null, null));
        }
        Object expectedRevision = function.unknown().get("catalogRevision");
        if (expectedRevision instanceof Number number && number.longValue() != function.revision()) {
            diagnostics.add(error("GRAPH.PIN_UNRESOLVED", null, null));
        }
        Object expectedSignature = function.unknown().get("signatureId");
        if (expectedSignature != null && !function.function().id().equals(String.valueOf(expectedSignature))) {
            diagnostics.add(error("GRAPH.PIN_UNRESOLVED", null, null));
        }
    }

    private static SignatureRef findSignature(FunctionBinding function, List<SignatureRef> signatures) {
        Object rawSignature = function.unknown().get("signatureId");
        String signatureId = rawSignature == null ? function.function().id() : String.valueOf(rawSignature);
        return signatures.stream().filter(value -> value.signature().id().value().equals(signatureId)).findFirst().orElse(null);
    }

    private static void validateFunctionIds(List<FunctionBinding> functions, List<Diagnostic> diagnostics) {
        Set<FunctionParameterId> ids = new HashSet<>();
        functions.forEach(function -> {
            function.inputs().forEach(parameter -> addParameterId(parameter, ids, diagnostics));
            function.outputs().forEach(parameter -> addParameterId(parameter, ids, diagnostics));
        });
    }

    private static void addParameterId(FunctionParameter parameter, Set<FunctionParameterId> ids, List<Diagnostic> diagnostics) {
        if (!ids.add(parameter.parameterId())) {
            diagnostics.add(error("GRAPH.DUPLICATE_FUNCTION_PARAMETER", null, null));
        }
    }

    private static boolean runtimeCapabilityAvailable(ContractRef<?> capability, RuntimeBindingManifest manifest) {
        return manifest.bindings().stream().anyMatch(binding -> binding.capability().equals(capability)
            && binding.available() && provider(manifest, binding) != null
            && provider(manifest, binding).state() == RuntimeProviderState.ACTIVE);
    }

    private static RuntimeProviderDescriptor provider(RuntimeBindingManifest manifest, RuntimeBindingDescriptor binding) {
        return manifest.providers().stream().filter(value -> value.provider().equals(binding.provider())).findFirst().orElse(null);
    }

    private static List<TypeExpr> inputTypes(CatalogNodeDescriptor definition) {
        return definition.pins().stream().filter(pin -> pin.direction() == CatalogNodeDescriptor.Direction.INPUT)
            .map(CatalogNodeDescriptor.Pin::type).toList();
    }

    private static List<TypeExpr> outputTypes(CatalogNodeDescriptor definition) {
        return definition.pins().stream().filter(pin -> pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT)
            .map(CatalogNodeDescriptor.Pin::type).toList();
    }

    private static ConversionGraph conversionGraph(CatalogSnapshot catalog) {
        ConversionGraph.Builder builder = ConversionGraph.builder();
        catalog.conversions().forEach(value -> builder.add(value.descriptor()));
        return builder.build();
    }

    private static Diagnostic error(String code, NodeInstanceId nodeId, PinId pinId) {
        String identity = code + "\u0000" + (nodeId == null ? "" : nodeId.canonicalText()) + "\u0000" + (pinId == null ? "" : pinId.canonicalText());
        Diagnostic.Builder builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(new ContractRef<>(new OwnerId("resync"), new OperationId("graph-validation")))
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (nodeId != null) {
            evidence.put("nodeInstanceId", nodeId.canonicalText());
        }
        if (pinId != null) {
            evidence.put("pinId", pinId.canonicalText());
        }
        builder.evidence(evidence);
        return builder.build();
    }

    private record NodeContext(GraphNode node, CatalogNodeDescriptor definition, CatalogOwned<CatalogNodeDescriptor> owned,
                               Map<PinId, FunctionBoundaryPins.EffectivePin> endpointPins) {
    }

    private record SignatureRef(CatalogOwned<InspectorDescriptor> owned, InspectorFunctionSignature signature) {
    }
}
