package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowModulePinIdentityTest {
    @Test
    void catalogAndRuntimeUseAuthoredPinIdsDirectionsTypesAndDisplayNames() {
        NodeDefinition definition = definition(false);
        HandlerRegistry handlers = handlers();
        List<RuntimeOperationDescriptor> requirements = new ArrayList<>();
        CatalogNodeDescriptor descriptor = FlowModule.catalogNode(OwnerId.of("fixture"), definition,
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), requirements, handlers);
        RuntimeOperationDescriptor requirement = requirements.getFirst();

        assertEquals(List.of("amount", "mode", "result"), descriptor.pins().stream().map(value -> value.id().value()).toList());
        assertEquals(List.of("Amount", "Mode Label", "Result Value"), descriptor.pins().stream().map(CatalogNodeDescriptor.Pin::displayName).toList());
        assertEquals(requirement.pins().stream().map(RuntimeOperationDescriptor.Pin::id).toList(),
            descriptor.pins().stream().map(CatalogNodeDescriptor.Pin::id).toList());
        assertEquals(requirement.pins().stream().map(RuntimeOperationDescriptor.Pin::type).toList(),
            descriptor.pins().stream().map(CatalogNodeDescriptor.Pin::type).toList());
        assertEquals(List.of(RuntimeOperationDescriptor.Direction.INPUT, RuntimeOperationDescriptor.Direction.INPUT,
                RuntimeOperationDescriptor.Direction.OUTPUT), requirement.pins().stream()
            .map(RuntimeOperationDescriptor.Pin::direction).toList());
    }

    @Test
    void reorderingAuthoredPinsDoesNotAllocateReplacementIds() {
        HandlerRegistry handlers = handlers();
        List<RuntimeOperationDescriptor> firstRequirements = new ArrayList<>();
        List<RuntimeOperationDescriptor> reorderedRequirements = new ArrayList<>();
        CatalogNodeDescriptor first = FlowModule.catalogNode(OwnerId.of("fixture"), definition(false),
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), firstRequirements, handlers);
        CatalogNodeDescriptor reordered = FlowModule.catalogNode(OwnerId.of("fixture"), definition(true),
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), reorderedRequirements, handlers);

        assertEquals(Set.of(PinId.of("amount"), PinId.of("mode"), PinId.of("result")),
            first.pins().stream().map(CatalogNodeDescriptor.Pin::id).collect(Collectors.toSet()));
        assertEquals(Set.of(PinId.of("amount"), PinId.of("mode"), PinId.of("result")),
            reordered.pins().stream().map(CatalogNodeDescriptor.Pin::id).collect(Collectors.toSet()));
        assertEquals(Set.copyOf(firstRequirements.getFirst().pins().stream().map(RuntimeOperationDescriptor.Pin::id).toList()),
            Set.copyOf(reorderedRequirements.getFirst().pins().stream().map(RuntimeOperationDescriptor.Pin::id).toList()));
    }

    @Test
    void authoredAndGeneratedOptionSourcesShareOneDescriptor() {
        OwnerId owner = OwnerId.of("fixture");
        NodeDefinition definition = optionDefinition();
        Map<String, InspectorOptionSource> generated = new LinkedHashMap<>();
        FlowModule.catalogNode(owner, definition, new LinkedHashMap<>(), new LinkedHashMap<>(), generated,
            new ArrayList<>(), handlers());
        Map<InspectorFieldId, InspectorOptionSource> authored = new LinkedHashMap<>();
        FlowModule.collectAuthoredOptionSources(owner, definition, authored);

        InspectorOptionSource generatedSource = generated.get("server-custom-content-asset");
        assertNotNull(generatedSource);
        assertEquals(generatedSource, authored.get(InspectorFieldId.of("server-custom-content-asset")));
        assertEquals("options-server-custom-content-asset", generatedSource.capability().id().value());
    }

    @Test
    void conflictingGeneratedOptionSourcesRemainRejected() {
        OwnerId owner = OwnerId.of("fixture");
        Map<String, InspectorOptionSource> optionSources = new LinkedHashMap<>();
        FlowModule.catalogNode(owner, optionDefinition(FlowDataType.STRING), new LinkedHashMap<>(), new LinkedHashMap<>(), optionSources,
            new ArrayList<>(), handlers());

        assertThrows(IllegalArgumentException.class, () -> FlowModule.catalogNode(owner, optionDefinition(FlowDataType.NUMBER),
            new LinkedHashMap<>(), new LinkedHashMap<>(), optionSources, new ArrayList<>(), handlers()));
    }

    private NodeDefinition definition(boolean reordered) {
        NodeDefinition.PinDefinition amount = pin("amount", "Amount", NodeDefinition.PinDirection.INPUT, FlowDataType.NUMBER);
        NodeDefinition.PinDefinition mode = pin("mode", "Mode Label", NodeDefinition.PinDirection.INPUT, FlowDataType.STRING);
        NodeDefinition.PinDefinition result = pin("result", "Result Value", NodeDefinition.PinDirection.OUTPUT, FlowDataType.NUMBER);
        NodeDefinition.Builder builder = new NodeDefinition.Builder("fixture:explicit_pins", "Explicit Pins", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .description("Exposes authored pin identities for catalog and runtime consumers.")
            .handler("ExplicitPinHandler")
            .handlerConfig(Map.of("operation", "run"));
        if (reordered) {
            builder.input(mode).input(amount);
        } else {
            builder.input(amount).input(mode);
        }
        return builder.output(result).build();
    }

    private NodeDefinition optionDefinition() {
        return optionDefinition(FlowDataType.STRING);
    }

    private NodeDefinition optionDefinition(FlowDataType dataType) {
        NodeDefinition.PinDefinition asset = new NodeDefinition.PinBuilder("asset", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, dataType)
            .optionsSource("server:custom_content:asset")
            .description("Selects a server-authored content asset for this operation.")
            .build();
        return new NodeDefinition.Builder("fixture:option_source", "Option Source", NodeDefinition.NodeCategory.DATA)
            .owner("fixture")
            .description("Exposes one server-authored option source for catalog validation.")
            .handler("ExplicitPinHandler")
            .handlerConfig(Map.of("operation", "run"))
            .input(asset)
            .build();
    }

    private NodeDefinition.PinDefinition pin(String id, String displayName, NodeDefinition.PinDirection direction,
                                              FlowDataType dataType) {
        return new NodeDefinition.PinBuilder(PinId.of(id), displayName, NodeDefinition.PinType.DATA, direction, dataType)
            .description("Provides this authored pin value to the operation.")
            .build();
    }

    private HandlerRegistry handlers() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("ExplicitPinHandler", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of("run");
            }
        });
        return handlers;
    }
}
