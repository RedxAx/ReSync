package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.handler.generic.GenericMathHandler;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCatalogProductionShapeTest {
    @Test
    void productionHandlerOperationAndResourceIdentityReachTheRuntimeDescriptor() {
        HandlerRegistry handlers = productionHandlers();
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(productionDefinition(), handlers);

        assertEquals("add", requirement.operation().id().value());
        TypeExpr.ResourceType resource = assertInstanceOf(TypeExpr.ResourceType.class, requirement.inputs().getFirst());
        assertEquals("builtin", resource.resourceType().ownerId());
        assertEquals("gui", resource.resourceType().localId());
        assertTrue(handlers.hasOperation("GenericMathHandler", "add"));
        assertEquals(Set.of("RUNTIME.HANDLER_FAILURE"), requirement.semantics().failureContract().diagnosticCodes());

        RuntimeOperationDescriptor namespaced = FlowModule.runtimeOperationDescriptor(namespacedDefinition(), handlers);
        TypeExpr.ResourceType namespacedResource = assertInstanceOf(TypeExpr.ResourceType.class, namespaced.inputs().getFirst());
        assertEquals("fixture", namespacedResource.resourceType().ownerId());
        assertEquals("quest", namespacedResource.resourceType().localId());
    }

    @Test
    void liveProofAcceptsTheActiveProductionBinding() {
        HandlerRegistry handlers = productionHandlers();
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(productionDefinition(), handlers);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));
        RuntimeBinding binding = RuntimeBinding.available(requirement, provider, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success()));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));

        assertTrue(CatalogBindingProof.live(registry).proves(requirement));
    }

    @Test
    void startupContractAcceptsSelectorBearingProductionContribution() {
        HandlerRegistry handlers = productionHandlers();
        NodeDefinition definition = selectorDefinition();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("fixture"), ProviderId.of("flow"));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        List<CatalogContribution> contributions = FlowModule.buildCatalogContributions(
            definitions, handlers, null, List.of(), FlowModule.CATALOG_CONTRACT_VERSION);

        assertTrue(contributions.stream().anyMatch(contribution -> !contribution.optionSources().isEmpty()));
        var result = new CatalogCompiler(FlowModule.CATALOG_CONTRACT_VERSION, CatalogBindingProof.live(registry))
            .compile(contributions, 1L);

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(new CatalogVersion(1, 3), result.snapshot().orElseThrow().contractVersion());

        CatalogVersion legacyVersion = new CatalogVersion(1, 0);
        List<CatalogContribution> legacyContributions = FlowModule.buildCatalogContributions(
            definitions, handlers, null, List.of(), legacyVersion);
        var legacy = new CatalogCompiler(legacyVersion, CatalogBindingProof.live(registry))
            .compile(legacyContributions, 2L);
        var optionDiagnostic = legacy.diagnostics().stream()
            .filter(diagnostic -> diagnostic.code().equals("CATALOG.OPTION_SCHEMA_UNSUPPORTED"))
            .findFirst().orElseThrow();
        var summaryDiagnostic = legacy.diagnostics().stream()
            .filter(diagnostic -> diagnostic.code().equals("CATALOG.CONTRIBUTION_REJECTED"))
            .findFirst().orElseThrow();
        List<Diagnostic> diagnostics = new ArrayList<>(Collections.nCopies(20, summaryDiagnostic));
        diagnostics.add(optionDiagnostic);
        String rejection = FlowModule.startupCatalogRejection("Flow catalog startup preflight was rejected", diagnostics);
        assertTrue(rejection.contains("total=21"));
        assertTrue(rejection.contains("code=CATALOG.OPTION_SCHEMA_UNSUPPORTED"));
        assertTrue(rejection.contains("owner=fixture"));
        assertTrue(rejection.contains("subject=server-resync-gui"));
        assertTrue(rejection.contains("omitted=15"));
        assertTrue(rejection.length() < 2048);
    }

    @Test
    void productionCatalogPublishesTheRegisteredValueTypeInventory() {
        HandlerRegistry handlers = productionHandlers();
        NodeDefinition definition = selectorDefinition();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("fixture"), ProviderId.of("flow"));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        List<CatalogContribution> contributions = FlowModule.buildCatalogContributions(
            definitions, handlers, null, List.of(), FlowModule.CATALOG_CONTRACT_VERSION);
        var result = new CatalogCompiler(FlowModule.CATALOG_CONTRACT_VERSION, CatalogBindingProof.live(registry))
            .compile(contributions, 63L);

        assertTrue(result.accepted(), result.diagnostics().toString());
        Set<String> typeIds = contributions.stream().flatMap(contribution -> contribution.types().stream())
            .map(type -> type.id().ownerId() + ":" + type.id().localId()).collect(Collectors.toSet());
        assertTrue(typeIds.contains("builtin:boolean"));
        assertTrue(typeIds.contains("builtin:string"));
        assertTrue(typeIds.contains("builtin:list"));
        assertTrue(typeIds.contains("builtin:map"));
        assertTrue(typeIds.contains("builtin:optional"));
        assertTrue(typeIds.contains("builtin:job_reference"));
        Set<ContractRef<CapabilityId>> supported = contributions.stream()
            .flatMap(contribution -> contribution.capabilities().stream()
                .map(capability -> ContractRef.of(contribution.ownerId(), capability.id())))
            .collect(Collectors.toSet());
        CatalogAuthoringPublication publication = CatalogAuthoringPublication.project(
            result.snapshot().orElseThrow(), supported);

        assertTrue(publication.compatible());
        assertTrue(publication.section(CatalogAuthoringPublication.Section.TYPES).selectable());
        assertEquals(typeIds.size(), publication.types().size());
        assertTrue(publication.types().stream().allMatch(CatalogAuthoringPublication.Entry::selectable));
    }

    @Test
    void unsupportedHandlerConfigurationIsRejectedBeforeCatalogCanonicalization() {
        HandlerRegistry handlers = productionHandlers();
        NodeDefinition definition = new NodeDefinition.Builder("fixture:invalid", "Invalid", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("operation", "add", "futureValue", new Object()))
            .input("a", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .build();

        assertThrows(IllegalArgumentException.class, () -> FlowModule.runtimeOperationDescriptor(definition, handlers));
    }

    @Test
    void ambiguousHandlerOperationIsRejectedWithoutPropertyFallback() {
        HandlerRegistry handlers = productionHandlers();
        NodeDefinition definition = new NodeDefinition.Builder("fixture:ambiguous", "Ambiguous", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("property", "value"))
            .input("value", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .build();

        assertThrows(IllegalArgumentException.class, () -> FlowModule.runtimeOperationDescriptor(definition, handlers));
    }

    @Test
    void bareResourceReferenceIsRejected() {
        HandlerRegistry handlers = productionHandlers();
        NodeDefinition definition = new NodeDefinition.Builder("fixture:bare_resource", "Bare Resource", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("operation", "add"))
            .input(new NodeDefinition.PinBuilder("resource", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.ANY)
                .typeRef(FlowTypeRef.simple("resource_reference")).build())
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .build();

        assertThrows(IllegalArgumentException.class, () -> FlowModule.runtimeOperationDescriptor(definition, handlers));
    }

    @Test
    void customFunctionIdentityRemainsExactAtTheLegacyBoundary() {
        FlowGraph graph = new FlowGraph();
        graph.setId("library:calculate_reward");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER)));
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);

        assertEquals("custom_function:library:calculate_reward", definition.getId());
        assertEquals("custom_function_call", requirement.operation().id().value());
        assertTrue(requirement.capability().id().value().contains("library"));
        assertEquals("server", definition.getHandlerConfig().get("functionOwner"));
        assertEquals("local", definition.getHandlerConfig().get("functionNamespace"));

        FlowGraph collidingReadableId = new FlowGraph();
        collidingReadableId.setId("library.calculate_reward");
        collidingReadableId.setFunction(true);
        NodeDefinition collidingDefinition = CustomFunctionNodeDefinitions.buildDefinition(collidingReadableId);
        RuntimeOperationDescriptor collidingRequirement = FlowModule.runtimeOperationDescriptor(collidingDefinition, handlers);
        assertNotEquals(requirement.capability().id(), collidingRequirement.capability().id());
    }

    @Test
    void emptySignatureCustomFunctionPassesTheProductionCatalogPreflight() {
        FlowGraph graph = new FlowGraph();
        graph.setId("asdg");
        graph.setFunction(true);
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(CustomFunctionNodeDefinitions.PLUGIN_ID, definition);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));

        List<CatalogContribution> contributions = FlowModule.buildCatalogContributions(
            definitions, handlers, null, List.of(), FlowModule.CATALOG_CONTRACT_VERSION);
        var result = new CatalogCompiler(FlowModule.CATALOG_CONTRACT_VERSION, CatalogBindingProof.live(registry))
            .compile(contributions, 1L);

        assertTrue(result.accepted(), result.diagnostics().toString());
        CatalogNodeDescriptor node = contributions.getFirst().definitions().getFirst();
        assertEquals("Runs the Asdg custom Function with its declared inputs and outputs.", node.description());
        assertEquals("Starts this custom Function call.", node.pins().getFirst().description());
        assertEquals("Continues after this custom Function returns.", node.pins().get(1).description());
    }

    @Test
    void customFunctionDefinitionChangesOnlyWhenItsCallableShapeChanges() {
        FlowGraph first = new FlowGraph();
        first.setId("stable_call");
        first.setFunction(true);
        first.setFunctionVersion(1);
        FlowGraph rebound = first.copy();
        rebound.setFunctionVersion(9);
        FlowGraph changed = first.copy();
        changed.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("value", FlowDataType.STRING)));

        assertTrue(FlowModule.sameDefinition(CustomFunctionNodeDefinitions.buildDefinition(first),
            CustomFunctionNodeDefinitions.buildDefinition(rebound)));
        assertFalse(FlowModule.sameDefinition(CustomFunctionNodeDefinitions.buildDefinition(first),
            CustomFunctionNodeDefinitions.buildDefinition(changed)));
    }

    @Test
    void newCustomFunctionRequestsCatalogRefreshBeforeItsStoredProjectionIsVisible() {
        AtomicBoolean storageRead = new AtomicBoolean();

        assertTrue(FlowModule.customFunctionDefinitionChanged(List.of(), false, () -> {
            storageRead.set(true);
            return null;
        }));
        assertFalse(storageRead.get());
    }

    @Test
    void rejectedRequirementRetainsTheLastActiveCatalog() {
        HandlerRegistry handlers = productionHandlers();
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(productionDefinition(), handlers);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogCompiler compiler = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.live(registry));
        var initial = compiler.compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initial, registry.snapshot());
        var candidate = compiler.compile(List.of(contribution(requirement)), 2);
        assertTrue(candidate.accepted(), candidate.diagnostics().toString());
        var replacement = registry.prepareReplacement(List.of(), List.of());
        var initialActivation = activation.activate(candidate.snapshot().orElseThrow(), replacement, null);
        assertTrue(initialActivation.committed(), initialActivation.detail());
        var previous = activation.active();
        List<RuntimeOperationDescriptor.Pin> changedPins = List.of(
            new RuntimeOperationDescriptor.Pin("gui", RuntimeOperationDescriptor.Direction.INPUT,
                TypeExpr.named(TypeReference.of("builtin", "string"))),
            new RuntimeOperationDescriptor.Pin("a", RuntimeOperationDescriptor.Direction.INPUT, requirement.inputs().get(1)),
            new RuntimeOperationDescriptor.Pin("b", RuntimeOperationDescriptor.Direction.INPUT, requirement.inputs().get(2)),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, requirement.outputs().getFirst()));
        RuntimeOperationDescriptor changed = new RuntimeOperationDescriptor(requirement.capability(), requirement.operation(), changedPins,
            requirement.semantics(), requirement.unknown());

        var result = compiler.compile(List.of(contribution(changed)), 3);

        assertFalse(result.accepted());
        assertSame(previous, activation.active());
    }

    private HandlerRegistry productionHandlers() {
        HandlerRegistry handlers = new HandlerRegistry();
        new GenericMathHandler().registerTo(handlers);
        return handlers;
    }

    private NodeDefinition productionDefinition() {
        return new NodeDefinition.Builder("fixture:math.add", "Add", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("operation", "add"))
            .input(new NodeDefinition.PinBuilder("gui", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.GUI_DEFINITION)
                .typeRef(FlowTypeRef.simple("gui_id")).build())
            .input("a", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .build();
    }

    private NodeDefinition selectorDefinition() {
        return new NodeDefinition.Builder("fixture:math.selector", "Select", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .description("Selects a GUI and adds the two provided numeric values.")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("operation", "add"))
            .input(new NodeDefinition.PinBuilder("gui", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.GUI_DEFINITION)
                .typeRef(FlowTypeRef.simple("gui_id"))
                .optionsSource("server:resync:gui")
                .description("Selects the GUI used by this operation.")
                .build())
            .input(new NodeDefinition.PinBuilder("a", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.NUMBER)
                .description("Provides the first numeric value to add.")
                .build())
            .input(new NodeDefinition.PinBuilder("b", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.NUMBER)
                .description("Provides the second numeric value to add.")
                .build())
            .output(new NodeDefinition.PinBuilder("result", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.NUMBER)
                .description("Returns the sum produced by the operation.")
                .build())
            .build();
    }

    private NodeDefinition namespacedDefinition() {
        return new NodeDefinition.Builder("fixture:math.reference", "Reference", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .handler("GenericMathHandler")
            .handlerConfig(Map.of("operation", "add"))
            .input(new NodeDefinition.PinBuilder("reference", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.GUI_DEFINITION)
                .typeRef(FlowTypeRef.parse("resource_reference<fixture:quest>"))
                .build())
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .build();
    }

    private CatalogContribution contribution(RuntimeOperationDescriptor requirement) {
        OwnerId owner = requirement.capability().owner();
        CapabilityId editor = CapabilityId.of("generic-editor");
        CatalogCategoryDescriptor category = new CatalogCategoryDescriptor("data", "Data", "Groups typed data flow capabilities.", 0);
        List<CatalogCapabilityDescriptor> capabilities = List.of(
            new CatalogCapabilityDescriptor(requirement.capability().id(), 1, false, InspectorFallback.GENERIC),
            new CatalogCapabilityDescriptor(editor, 1, false, InspectorFallback.GENERIC));
        List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>();
        for (RuntimeOperationDescriptor.Pin runtimePin : requirement.pins()) {
            CatalogNodeDescriptor.Direction direction = runtimePin.direction() == RuntimeOperationDescriptor.Direction.INPUT
                ? CatalogNodeDescriptor.Direction.INPUT : CatalogNodeDescriptor.Direction.OUTPUT;
            pins.add(pin(runtimePin.id().value(), direction, runtimePin.type(), owner, editor));
        }
        CatalogNodeDescriptor node = CatalogNodeDescriptor.builder(NodeId.of("fixture-add"))
            .displayName("Add")
            .description("Runs the production flow operation for this node.")
            .category(ContractRef.of(owner, category.id()))
            .pins(pins)
            .branches(List.of(new CatalogNodeDescriptor.Branch("failure", "Failure", "Reports a structured operation failure.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation reported a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(requirement.capability(), requirement.operation()))
            .semantics(requirement.semantics())
            .requiredCapabilities(Set.of(requirement.capability()))
            .build();
        CatalogVersion version = new CatalogVersion(1, 0);
        return CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(version, version),
                new CatalogProvenance(CatalogProvenance.SourceKind.BUNDLED, "test://flow/catalog", "1.0.0", "production-shape"))
            .definitions(List.of(node))
            .categories(List.of(category))
            .capabilities(capabilities)
            .runtimeRequirements(List.of(requirement))
            .build();
    }

    private CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type,
                                          OwnerId owner, CapabilityId editor) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, id,
            "Provides a typed value for this operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
            ContractRef.of(owner, editor), null, InspectorCondition.always(), CatalogNodeDescriptor.RepeatableIntent.disabled(), null);
    }
}
