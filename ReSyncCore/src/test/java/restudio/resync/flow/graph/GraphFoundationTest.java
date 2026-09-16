package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFoundationTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final NodeInstanceId SOURCE_ID = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final NodeInstanceId TARGET_ID = NodeInstanceId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final ConnectionId CONNECTION_ID = ConnectionId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final ContractRef<NodeId> SOURCE_DEFINITION = ContractRef.of(new OwnerId("builtin"), NodeId.of("source"));
    private static final ContractRef<NodeId> TARGET_DEFINITION = ContractRef.of(new OwnerId("builtin"), NodeId.of("target"));
    private static final TypeExpr NUMBER = TypeExpr.named(new TypeReference("builtin", "number"));
    private static final RepeatableGroupId FIRST_GROUP = RepeatableGroupId.of("first-group");
    private static final RepeatableGroupId SECOND_GROUP = RepeatableGroupId.of("second-group");
    private static final RepeatableGroupId LEGACY_GROUP = RepeatableGroupId.of("legacy-group");
    private static final RepeatableElementId FIRST_ELEMENT = RepeatableElementId.of(UUID.fromString("99999999-9999-4999-8999-999999999999"));
    private static final RepeatableElementId SECOND_ELEMENT = RepeatableElementId.of(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final CatalogSnapshot CATALOG = catalog(1);
    private static final CatalogBinding BINDING = binding(CATALOG);

    @Test
    void connectionsResolveByNodeAndPinIdentity() {
        GraphDocument graph = graph(List.of(
            new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, Map.of()),
            new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of())
        ), List.of(new GraphConnection(CONNECTION_ID, new GraphEndpoint(SOURCE_ID, PinId.of("result")), new GraphEndpoint(TARGET_ID, PinId.of("input")))));

        ValidationResult validation = new GraphValidator().validate(graph, CATALOG);
        assertTrue(validation.valid(), validation.diagnostics()::toString);

        CompiledExecutionPlan plan = new GraphCompiler().compile(graph, CATALOG);
        CompiledExecutionStep source = plan.steps().stream().filter(step -> step.nodeId().equals(SOURCE_ID)).findFirst().orElseThrow();
        assertEquals(TARGET_ID, source.outputBindings().get(PinId.of("result")).getFirst().nodeId());
        assertEquals(PinId.of("input"), source.outputBindings().get(PinId.of("result")).getFirst().pinId());
    }

    @Test
    void compiledStepsCarryTheResolvedProviderAndBindingDescriptor() {
        GraphDocument base = graph(List.of(
            new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, Map.of()),
            new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of())
        ), List.of(new GraphConnection(CONNECTION_ID, new GraphEndpoint(SOURCE_ID, PinId.of("result")),
            new GraphEndpoint(TARGET_ID, PinId.of("input")))));
        List<RuntimeBindingDescriptor> bindings = CATALOG.runtimeRequirements().stream()
            .map(owned -> new RuntimeBindingDescriptor(owned.descriptor(), ContractRef.of(new OwnerId("runtime"), new ProviderId("provider")), "1.0.0", true))
            .toList();
        Map<RuntimeBindingKey, Map<String, ?>> invalidationInputs = bindings.stream()
            .collect(Collectors.toMap(RuntimeBindingDescriptor::key, ignored -> Map.of("definitionRevision", 3)));
        RuntimeBindingManifest manifest = RuntimeBindingManifest.create(
            List.of(new RuntimeProviderDescriptor(bindings.getFirst().provider(), "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN)),
            bindings,
            bindings.stream().collect(Collectors.toMap(binding -> binding.key(), binding -> binding.executionFingerprint(invalidationInputs.get(binding.key())))),
            List.of(), Map.of(), invalidationInputs);
        GraphDocument bound = new GraphDocument(base.schemaVersion(), base.resource(), base.revision(),
            CatalogBinding.of(CATALOG.generation(), CATALOG.contentChecksum(), manifest.bindingManifestHash()), base.requiredCapabilities(),
            base.nodes(), base.connections(), base.functions(), base.unknown());

        CompiledExecutionPlan plan = new GraphCompiler(manifest).compile(bound, CATALOG);
        CompiledExecutionStep source = plan.steps().stream().filter(step -> step.nodeId().equals(SOURCE_ID)).findFirst().orElseThrow();

        assertEquals(bindings.getFirst().provider(), source.resolvedBinding().provider());
        assertEquals(bindings.getFirst().providerVersion(), source.resolvedBinding().providerVersion());
        assertEquals(bindings.getFirst().inputs(), source.resolvedBinding().inputs());
        assertEquals(bindings.getFirst().outputs(), source.resolvedBinding().outputs());
        assertEquals(manifest.executionFingerprint(bindings.getFirst().key()).orElseThrow(), source.resolvedBindingFingerprint());
        assertEquals(source.resolvedBindingFingerprint(), plan.providerLeases().stream()
            .filter(lease -> lease.capability().equals(bindings.getFirst().capability())
                && lease.operation().equals(bindings.getFirst().operation()))
            .findFirst().orElseThrow().bindingFingerprint());
        assertTrue(plan.canonicalJson().contains("resolvedBinding"));
    }

    @Test
    void unknownDataRemainsImmutableAndAvailableOnTheModel() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("futureNumber", 9);
        nested.put("futureNull", null);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("futureFlag", true);
        fields.put("futureObject", nested);
        OpaqueData unknown = OpaqueData.of(fields);

        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource(), 7, BINDING, Set.of(), List.of(), List.of(), List.of(), unknown);

        assertEquals(fields, graph.unknown().fields());
        assertEquals(true, graph.unknown().get("futureFlag"));
        assertThrows(UnsupportedOperationException.class, () -> graph.unknown().fields().put("new", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> preservedNested = (Map<String, Object>) graph.unknown().get("futureObject");
        assertThrows(UnsupportedOperationException.class, () -> preservedNested.put("changed", false));
    }

    @Test
    void graphAndPlanChecksumsDoNotDependOnInputCollectionOrder() {
        GraphNode source = new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, Map.of());
        GraphNode target = new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of());
        GraphConnection connection = new GraphConnection(CONNECTION_ID, new GraphEndpoint(SOURCE_ID, PinId.of("result")), new GraphEndpoint(TARGET_ID, PinId.of("input")));
        GraphDocument first = graph(List.of(source, target), List.of(connection));
        GraphDocument second = graph(List.of(target, source), List.of(connection));

        assertEquals(first.checksum(), second.checksum());
        assertEquals(new GraphCompiler().compile(first, CATALOG).planHash(), new GraphCompiler().compile(second, CATALOG).planHash());
    }

    @Test
    void catalogBindingMismatchIsRejectedBeforeCompilation() {
        GraphDocument graph = graph(List.of(
            new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, Map.of()),
            new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of())
        ), List.of(new GraphConnection(CONNECTION_ID, new GraphEndpoint(SOURCE_ID, PinId.of("result")), new GraphEndpoint(TARGET_ID, PinId.of("input")))));
        CatalogBinding mismatchedBinding = CatalogBinding.of(
            BINDING.generation() + 1,
            BINDING.catalogChecksum(),
            BINDING.bindingManifestHash());
        GraphDocument mismatched = new GraphDocument(
            graph.schemaVersion(), graph.resource(), graph.revision(), mismatchedBinding, graph.requiredCapabilities(),
            graph.nodes(), graph.connections(), graph.functions(), graph.unknown());

        ValidationResult validation = new GraphValidator().validate(mismatched, CATALOG);
        assertTrue(validation.valid());
        assertTrue(validation.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.CATALOG_MISMATCH")));
        GraphCompilationException exception = assertThrows(GraphCompilationException.class, () -> new GraphCompiler().compile(mismatched, CATALOG));
        assertTrue(exception.validation().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.CATALOG_MISMATCH")));
    }

    @Test
    void repeatableAndFunctionIdsAreDurable() {
        UUID namespace = UUID.fromString("55555555-5555-4555-8555-555555555555");
        FunctionParameter first = FunctionParameter.stable(namespace, "function-input", "value", NUMBER);
        FunctionParameter second = FunctionParameter.stable(namespace, "function-input", "value", NUMBER);
        assertEquals(first.parameterId(), second.parameterId());
        assertTrue(first.description().length() >= 16);
        RepeatableElementId elementId = RepeatableElementId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));
        RepeatableElement element = new RepeatableElement(elementId, Map.of());
        assertEquals(elementId, element.elementId());
    }

    @Test
    void repeatableValuesAndEndpointsStayInsideTheirTypedGroup() {
        CatalogSnapshot catalog = repeatableCatalog();

        ValidationResult valid = new GraphValidator().validate(
            repeatableGraph(catalog, PinId.of("first-value"), FIRST_ELEMENT), catalog);
        ValidationResult crossGroupValue = new GraphValidator().validate(
            repeatableGraph(catalog, PinId.of("second-value"), FIRST_ELEMENT), catalog);
        ValidationResult crossGroupEndpoint = new GraphValidator().validate(
            repeatableGraph(catalog, PinId.of("first-value"), SECOND_ELEMENT), catalog);

        assertTrue(valid.valid(), valid.diagnostics()::toString);
        assertFalse(crossGroupValue.valid());
        assertTrue(crossGroupValue.diagnostics().stream()
            .anyMatch(value -> value.code().equals("GRAPH.PIN_UNDECLARED")));
        assertFalse(crossGroupEndpoint.valid());
        assertTrue(crossGroupEndpoint.diagnostics().stream()
            .anyMatch(value -> value.code().equals("GRAPH.ENDPOINT_ELEMENT_MISSING")));
    }

    @Test
    void repeatableBoundsCoverOmittedUnderAndAboveBindings() {
        CatalogSnapshot catalog = repeatableCatalog();

        ValidationResult omitted = new GraphValidator().validate(repeatableBoundsGraph(catalog, null), catalog);
        ValidationResult under = new GraphValidator().validate(repeatableBoundsGraph(catalog, List.of()), catalog);
        ValidationResult above = new GraphValidator().validate(
            repeatableBoundsGraph(catalog, repeatableElements(PinId.of("first-value"), 5)), catalog);

        assertFalse(omitted.valid());
        assertTrue(omitted.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.REPEATABLE_BOUNDS")));
        assertFalse(under.valid());
        assertTrue(under.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.REPEATABLE_BOUNDS")));
        assertFalse(above.valid());
        assertTrue(above.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.REPEATABLE_BOUNDS")));
    }

    @Test
    void legacyRepeatablesKeepValuesAndEndpointsWithoutWeakeningTypedGroups() {
        CatalogSnapshot catalog = mixedLegacyCatalog();

        ValidationResult valid = new GraphValidator().validate(
            mixedLegacyGraph(catalog, PinId.of("legacy-value"), SECOND_ELEMENT), catalog);
        ValidationResult typedValueInLegacyGroup = new GraphValidator().validate(
            mixedLegacyGraph(catalog, PinId.of("first-value"), SECOND_ELEMENT), catalog);
        ValidationResult legacyEndpointOnTypedGroup = new GraphValidator().validate(
            mixedLegacyGraph(catalog, PinId.of("legacy-value"), FIRST_ELEMENT), catalog);

        assertTrue(valid.valid(), valid.diagnostics()::toString);
        assertFalse(typedValueInLegacyGroup.valid());
        assertTrue(typedValueInLegacyGroup.diagnostics().stream()
            .anyMatch(value -> value.code().equals("GRAPH.PIN_UNDECLARED")));
        assertFalse(legacyEndpointOnTypedGroup.valid());
        assertTrue(legacyEndpointOnTypedGroup.diagnostics().stream()
            .anyMatch(value -> value.code().equals("GRAPH.ENDPOINT_ELEMENT_MISSING")));
        assertFalse(legacyEndpointOnTypedGroup.diagnostics().stream()
            .anyMatch(value -> value.code().equals("GRAPH.ENDPOINT_REPEATABLE_UNSUPPORTED")));
    }

    private static GraphDocument graph(List<GraphNode> nodes, List<GraphConnection> connections) {
        return new GraphDocument(resource(), 4, BINDING, nodes, connections);
    }

    private static ServerResourceLocator resource() {
        return new ServerResourceLocator(SERVER_UUID, ContractRef.of(new OwnerId("resync"), new ResourceTypeId("flow")), "example");
    }

    private static CatalogSnapshot catalog(long generation) {
        CatalogVersion contract = new CatalogVersion(1, 0);
        CatalogContractRange range = new CatalogContractRange(contract, contract);
        List<CatalogNodeDescriptor> definitions = List.of(
            node("source", CatalogNodeDescriptor.Direction.OUTPUT, PinId.of("result")),
            node("target", CatalogNodeDescriptor.Direction.INPUT, PinId.of("input")));
        List<RuntimeOperationDescriptor> requirements = definitions.stream().map(GraphFoundationTest::requirement).toList();
        CatalogContribution contribution = CatalogContribution.builder(OwnerId.of("builtin"), "1.0.0", range, provenance())
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(definitions)
            .runtimeRequirements(requirements)
            .build();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        requirements.forEach(requirement -> fingerprints.put(requirement.key(), requirement.executionFingerprint()));
        return new CatalogCompiler(contract, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), generation).snapshot().orElseThrow();
    }

    private static CatalogSnapshot repeatableCatalog() {
        CatalogVersion contract = new CatalogVersion(1, 0);
        List<CatalogNodeDescriptor> definitions = List.of(repeatableNode(),
            node("target", CatalogNodeDescriptor.Direction.INPUT, PinId.of("input")));
        List<RuntimeOperationDescriptor> requirements = definitions.stream().map(GraphFoundationTest::requirement).toList();
        CatalogContribution contribution = CatalogContribution.builder(OwnerId.of("builtin"), "1.0.0",
                new CatalogContractRange(contract, contract), provenance())
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(definitions)
            .runtimeRequirements(requirements)
            .build();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        requirements.forEach(requirement -> fingerprints.put(requirement.key(), requirement.executionFingerprint()));
        return new CatalogCompiler(contract, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), 1).snapshot().orElseThrow();
    }

    private static CatalogSnapshot mixedLegacyCatalog() {
        CatalogVersion contract = new CatalogVersion(1, 0);
        List<CatalogNodeDescriptor> definitions = List.of(mixedLegacyNode(),
            node("target", CatalogNodeDescriptor.Direction.INPUT, PinId.of("input")));
        List<RuntimeOperationDescriptor> requirements = definitions.stream().map(GraphFoundationTest::requirement).toList();
        CatalogContribution contribution = CatalogContribution.builder(OwnerId.of("builtin"), "1.0.0",
                new CatalogContractRange(contract, contract), provenance())
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(definitions)
            .runtimeRequirements(requirements)
            .build();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        requirements.forEach(requirement -> fingerprints.put(requirement.key(), requirement.executionFingerprint()));
        return new CatalogCompiler(contract, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), 1).snapshot().orElseThrow();
    }

    private static CatalogNodeDescriptor repeatableNode() {
        ContractRef<CapabilityId> execute = ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow-execute"));
        ContractRef<CapabilityId> editor = ContractRef.of(new OwnerId("builtin"), CapabilityId.of("generic-editor"));
        CatalogNodeDescriptor.Pin firstValue = repeatablePin("first-value", CatalogNodeDescriptor.Direction.INPUT,
            FIRST_GROUP, editor);
        CatalogNodeDescriptor.Pin firstResult = repeatablePin("first-result", CatalogNodeDescriptor.Direction.OUTPUT,
            FIRST_GROUP, editor);
        CatalogNodeDescriptor.Pin secondValue = repeatablePin("second-value", CatalogNodeDescriptor.Direction.INPUT,
            SECOND_GROUP, editor);
        CatalogNodeDescriptor.Pin secondResult = repeatablePin("second-result", CatalogNodeDescriptor.Direction.OUTPUT,
            SECOND_GROUP, editor);
        CatalogNodeDescriptor.RepeatableGroup firstGroup = CatalogNodeDescriptor.RepeatableGroup.withMembers(FIRST_GROUP,
            "First", "Groups the first repeated values and results.", 1, 4, true, List.of(
                new CatalogNodeDescriptor.RepeatableMember(firstValue.id(), firstValue.direction(), firstValue.type()),
                new CatalogNodeDescriptor.RepeatableMember(firstResult.id(), firstResult.direction(), firstResult.type())));
        CatalogNodeDescriptor.RepeatableGroup secondGroup = CatalogNodeDescriptor.RepeatableGroup.withMembers(SECOND_GROUP,
            "Second", "Groups the second repeated values and results.", 1, 4, true, List.of(
                new CatalogNodeDescriptor.RepeatableMember(secondValue.id(), secondValue.direction(), secondValue.type()),
                new CatalogNodeDescriptor.RepeatableMember(secondResult.id(), secondResult.direction(), secondResult.type())));
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch("failed", "Failed",
            "Describes the failure outcome for this operation.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure",
            "The operation completed with a structured failure.")));
        RuntimeSemantics semantics = pure(ContractRef.of(new OwnerId("resync.system"),
            CapabilityId.of("flow-authorize")), "failed");
        return CatalogNodeDescriptor.builder(NodeId.of("source"))
            .domain("flow")
            .family("operation")
            .displayName("Repeatable Operation")
            .description("Executes one deterministic operation with two typed repeatable groups.")
            .category(ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow")))
            .pins(List.of(firstValue, firstResult, secondValue, secondResult))
            .branches(List.of(failure))
            .repeatables(List.of(firstGroup, secondGroup))
            .handler(new CatalogNodeDescriptor.Handler(execute,
                ContractRef.of(new OwnerId("builtin"), OperationId.of("flow-operation-repeatable"))))
            .semantics(semantics)
            .requiredCapabilities(Set.of(execute))
            .build();
    }

    private static CatalogNodeDescriptor mixedLegacyNode() {
        ContractRef<CapabilityId> execute = ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow-execute"));
        ContractRef<CapabilityId> editor = ContractRef.of(new OwnerId("builtin"), CapabilityId.of("generic-editor"));
        CatalogNodeDescriptor.Pin firstValue = repeatablePin("first-value", CatalogNodeDescriptor.Direction.INPUT,
            FIRST_GROUP, editor);
        CatalogNodeDescriptor.Pin firstResult = repeatablePin("first-result", CatalogNodeDescriptor.Direction.OUTPUT,
            FIRST_GROUP, editor);
        CatalogNodeDescriptor.Pin legacyValue = legacyRepeatablePin("legacy-value", CatalogNodeDescriptor.Direction.INPUT,
            editor);
        CatalogNodeDescriptor.Pin legacyResult = legacyRepeatablePin("legacy-result",
            CatalogNodeDescriptor.Direction.OUTPUT, editor);
        CatalogNodeDescriptor.RepeatableGroup typedGroup = CatalogNodeDescriptor.RepeatableGroup.withMembers(FIRST_GROUP,
            "Typed", "Groups typed repeated values and results.", 1, 4, true, List.of(
                new CatalogNodeDescriptor.RepeatableMember(firstValue.id(), firstValue.direction(), firstValue.type()),
                new CatalogNodeDescriptor.RepeatableMember(firstResult.id(), firstResult.direction(), firstResult.type())));
        CatalogNodeDescriptor.RepeatableGroup legacyGroup = new CatalogNodeDescriptor.RepeatableGroup(LEGACY_GROUP,
            "Legacy", "Preserves group-less repeatable metadata compatibility.", NUMBER, 1, 4, true);
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch("failed", "Failed",
            "Describes the failure outcome for this operation.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure",
            "The operation completed with a structured failure.")));
        RuntimeSemantics semantics = pure(ContractRef.of(new OwnerId("resync.system"),
            CapabilityId.of("flow-authorize")), "failed");
        return CatalogNodeDescriptor.builder(NodeId.of("source"))
            .domain("flow")
            .family("operation")
            .displayName("Mixed Repeatable Operation")
            .description("Executes one operation with typed and legacy repeatable groups.")
            .category(ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow")))
            .pins(List.of(firstValue, firstResult, legacyValue, legacyResult))
            .branches(List.of(failure))
            .repeatables(List.of(typedGroup, legacyGroup))
            .handler(new CatalogNodeDescriptor.Handler(execute,
                ContractRef.of(new OwnerId("builtin"), OperationId.of("flow-operation-repeatable"))))
            .semantics(semantics)
            .requiredCapabilities(Set.of(execute))
            .build();
    }

    private static CatalogNodeDescriptor.Pin repeatablePin(String id, CatalogNodeDescriptor.Direction direction,
                                                           RepeatableGroupId groupId,
                                                           ContractRef<CapabilityId> editor) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, NUMBER, "Repeated Value",
            "A typed value owned by one repeatable group.", CatalogNodeDescriptor.Requirement.REQUIRED, null, editor,
            null, null, new CatalogNodeDescriptor.RepeatableIntent(groupId, 1, 4, true));
    }

    private static CatalogNodeDescriptor.Pin legacyRepeatablePin(String id, CatalogNodeDescriptor.Direction direction,
                                                                 ContractRef<CapabilityId> editor) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, NUMBER, "Legacy Repeated Value",
            "A value retained by group-less legacy repeatable metadata.", CatalogNodeDescriptor.Requirement.REQUIRED,
            null, editor, null, null, new CatalogNodeDescriptor.RepeatableIntent(true, 1, 4, true));
    }

    private static GraphDocument repeatableGraph(CatalogSnapshot catalog, PinId firstElementValue,
                                                  RepeatableElementId firstEndpointElement) {
        RepeatableElement first = new RepeatableElement(FIRST_ELEMENT, Map.of(firstElementValue,
            new PinValue(firstElementValue, TypedValue.value(NUMBER, 1))));
        PinId secondValue = PinId.of("second-value");
        RepeatableElement second = new RepeatableElement(SECOND_ELEMENT, Map.of(secondValue,
            new PinValue(secondValue, TypedValue.value(NUMBER, 2))));
        GraphNode source = new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, null, Map.of(), Map.of(), List.of(),
            List.of(new RepeatableBinding(FIRST_GROUP, true, List.of(first)),
                new RepeatableBinding(SECOND_GROUP, true, List.of(second))),
            InspectorState.empty(), 0, 0, OpaqueData.empty());
        GraphNode target = new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of());
        GraphConnection connection = new GraphConnection(CONNECTION_ID,
            new GraphEndpoint(SOURCE_ID, PinId.of("first-result"), firstEndpointElement, null),
            new GraphEndpoint(TARGET_ID, PinId.of("input")));
        return new GraphDocument(resource(), 4, binding(catalog), List.of(source, target), List.of(connection));
    }

    private static GraphDocument repeatableBoundsGraph(CatalogSnapshot catalog, List<RepeatableElement> firstElements) {
        List<RepeatableBinding> bindings = firstElements == null
            ? List.of(new RepeatableBinding(SECOND_GROUP, true, repeatableElements(PinId.of("second-value"), 1)))
            : List.of(new RepeatableBinding(FIRST_GROUP, true, firstElements),
                new RepeatableBinding(SECOND_GROUP, true, repeatableElements(PinId.of("second-value"), 1)));
        GraphNode source = new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, null, Map.of(), Map.of(), List.of(), bindings,
            InspectorState.empty(), 0, 0, OpaqueData.empty());
        return new GraphDocument(resource(), 4, binding(catalog), List.of(source), List.of());
    }

    private static List<RepeatableElement> repeatableElements(PinId pinId, int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> {
                RepeatableElementId id = RepeatableElementId.deterministic(
                    "graph-foundation-" + pinId.canonicalText() + "-" + index);
                return new RepeatableElement(id, Map.of(pinId,
                    new PinValue(pinId, TypedValue.value(NUMBER, index + 1))));
            })
            .toList();
    }

    private static GraphDocument mixedLegacyGraph(CatalogSnapshot catalog, PinId legacyElementValue,
                                                  RepeatableElementId legacyEndpointElement) {
        PinId typedValue = PinId.of("first-value");
        RepeatableElement typed = new RepeatableElement(FIRST_ELEMENT, Map.of(typedValue,
            new PinValue(typedValue, TypedValue.value(NUMBER, 1))));
        RepeatableElement legacy = new RepeatableElement(SECOND_ELEMENT, Map.of(legacyElementValue,
            new PinValue(legacyElementValue, TypedValue.value(NUMBER, 2))));
        GraphNode source = new GraphNode(SOURCE_ID, SOURCE_DEFINITION, 1, null, Map.of(), Map.of(), List.of(),
            List.of(new RepeatableBinding(FIRST_GROUP, true, List.of(typed)),
                new RepeatableBinding(LEGACY_GROUP, true, List.of(legacy))),
            InspectorState.empty(), 0, 0, OpaqueData.empty());
        GraphNode target = new GraphNode(TARGET_ID, TARGET_DEFINITION, 1, Map.of());
        GraphConnection connection = new GraphConnection(CONNECTION_ID,
            new GraphEndpoint(SOURCE_ID, PinId.of("legacy-result"), legacyEndpointElement, null),
            new GraphEndpoint(TARGET_ID, PinId.of("input")));
        return new GraphDocument(resource(), 4, binding(catalog), List.of(source, target), List.of(connection));
    }

    private static CatalogNodeDescriptor node(String id, CatalogNodeDescriptor.Direction direction, PinId pinId) {
        ContractRef<CapabilityId> execute = ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow-execute"));
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch("failed", "Failed", "Describes the failure outcome for this operation.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation completed with a structured failure.")));
        RuntimeSemantics semantics = pure(ContractRef.of(new OwnerId("resync.system"), CapabilityId.of("flow-authorize")), "failed");
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin(pinId, direction, NUMBER, direction == CatalogNodeDescriptor.Direction.INPUT ? "Input" : "Result", "A number supplied to or returned by the operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null, ContractRef.of(new OwnerId("builtin"), CapabilityId.of("generic-editor")), null, null, null);
        return CatalogNodeDescriptor.builder(NodeId.of(id))
            .domain("flow")
            .family("operation")
            .displayName("Operation")
            .description("Executes one deterministic catalog operation with explicit behavior.")
            .category(ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow")))
            .pins(List.of(pin))
            .branches(List.of(failure))
            .handler(new CatalogNodeDescriptor.Handler(execute, ContractRef.of(new OwnerId("builtin"), OperationId.of("flow-operation-" + id))))
            .semantics(semantics)
            .requiredCapabilities(Set.of(execute))
            .build();
    }

    @Test
    void variablesAreTypedDurableAndOrderIndependent() {
        GraphVariable first = new GraphVariable(
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "first",
            NUMBER,
            TypedValue.value(NUMBER, 1),
            OpaqueData.of(Map.of("future", true)));
        GraphVariable second = new GraphVariable(
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            "second",
            NUMBER);
        GraphDocument ordered = new GraphDocument(
            new CatalogVersion(1, 0), resource(), 7, BINDING, Set.of(), List.of(), List.of(),
            List.of(first, second), List.of(), OpaqueData.empty());
        GraphDocument reversed = new GraphDocument(
            new CatalogVersion(1, 0), resource(), 7, BINDING, Set.of(), List.of(), List.of(),
            List.of(second, first), List.of(), OpaqueData.empty());

        assertEquals(ordered.checksum(), reversed.checksum());
        assertEquals(true, ordered.variables().getFirst().unknown().get("future"));
        assertThrows(IllegalArgumentException.class, () -> new GraphVariable(
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            "invalid",
            NUMBER,
            TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "value")));
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        List<RuntimeOperationDescriptor.Pin> pins = node.pins().stream()
            .map(pin -> new RuntimeOperationDescriptor.Pin(
                pin.id(),
                pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT
                    : RuntimeOperationDescriptor.Direction.OUTPUT,
                pin.type()))
            .toList();
        return new RuntimeOperationDescriptor(
            node.handler().capability(),
            node.handler().operation(),
            pins,
            node.semantics());
    }

    private static RuntimeSemantics pure(ContractRef<CapabilityId> authorization, String failureBranch) {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, authorization, RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of(failureBranch), Set.of(), new RuntimeFailureContract(NUMBER, Set.of("RUNTIME.FAILURE"), Set.of(failureBranch), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static CatalogCategoryDescriptor category() {
        return new CatalogCategoryDescriptor("flow", "Flow", "Operations that compose into a reusable flow graph.", 1);
    }

    private static List<CatalogCapabilityDescriptor> capabilities() {
        return List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow-execute"), 1, false, InspectorFallback.GENERIC), new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD));
    }

    private static CatalogProvenance provenance() {
        return CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/graph-foundation.json", "1.0.0", "test", "graph-foundation");
    }

    private static CatalogBinding binding(CatalogSnapshot catalog) {
        return CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
    }
}
