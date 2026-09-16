package restudio.resync.worldgen.registry;

import restudio.flow.data.FlowDataType;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.worldgen.contract.WorldGenNodeIdentity;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldGenFlowCatalog {
    public static final String HANDLER_ID = "WorldGenNodeCatalogHandler";

    private WorldGenFlowCatalog() {
    }

    public static List<NodeDefinition> flowDefinitions() {
        return flowDefinitions(WorldGenNodeDefinitions.defaultDefinitions());
    }

    public static List<NodeDefinition> register(NodeDefinitionRegistry definitions, HandlerRegistry handlers) {
        WorldGenFlowCatalogContribution contribution = WorldGenFlowCatalogContribution.create();
        contribution.apply(definitions, handlers);
        return contribution.definitions();
    }

    static List<NodeDefinition> flowDefinitions(Collection<WorldGenNodeDefinition> sourceDefinitions) {
        return sourceDefinitions.stream().map(WorldGenFlowCatalog::toFlowDefinition).toList();
    }

    private static NodeDefinition toFlowDefinition(WorldGenNodeDefinition source) {
        String canonicalId = WorldGenNodeIdentity.require("worldgen:" + source.getId());
        String category = source.getCategory() == null || source.getCategory().isBlank() ? "world_gen" : source.getCategory();
        String family = category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String operation = WorldGenNodeCatalogHandler.operationFor(source.getId());
        NodeDefinition.Builder builder = new NodeDefinition.Builder(source.getId(), source.getDisplayName(), NodeDefinition.NodeCategory.WORLD_GEN)
            .owner(WorldGenNodeIdentity.OWNER.canonicalText())
            .color(source.getColor())
            .priority(source.getPriority())
            .description(source.getDescription())
            .hidden(source.isHidden())
            .kind(NodeDefinition.NodeKind.PURE)
            .family(family)
            .tags(List.of("worldgen", family))
            .handler(HANDLER_ID)
            .handlerConfig(Map.of("operation", operation, "worldgenNode", canonicalId));
        for (WorldGenNodeDefinition.PinDefinition pin : source.getInputs()) {
            builder.input(toFlowPin(pin, NodeDefinition.PinDirection.INPUT));
        }
        for (WorldGenNodeDefinition.PinDefinition pin : source.getOutputs()) {
            builder.output(toFlowPin(pin, NodeDefinition.PinDirection.OUTPUT));
        }
        return builder.build();
    }

    private static NodeDefinition.PinDefinition toFlowPin(WorldGenNodeDefinition.PinDefinition source,
                                                           NodeDefinition.PinDirection direction) {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(source.id(), source.name(), NodeDefinition.PinType.DATA,
            direction, source.dataType());
        NodeDefinition.WidgetType widget = widgetType(source.widgetType());
        if (widget != null) {
            builder.widget(widget);
        }
        if (source.defaultValue() != null) {
            builder.defaultValue(String.valueOf(source.defaultValue()));
        }
        if (source.options() != null && !source.options().isEmpty()) {
            builder.options(source.options());
        }
        if ("distance_func".equals(source.name())) {
            builder.options(List.of("euclidean", "euclidean_sq", "manhattan", "hybrid"));
        }
        if ("material".equalsIgnoreCase(source.widgetType())) {
            builder.optionsSource("worldgen:blocks");
        }
        if (FlowDataType.BIOME.equals(source.dataType())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).optionsSource("worldgen:biomes");
        }
        if (FlowDataType.ENTITY_TYPE.equals(source.dataType())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).optionsSource("worldgen:entity_types");
        }
        if ("structure_id".equals(source.name())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).optionsSource("worldgen:structures");
        }
        if ("tree".equals(source.name())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).optionsSource("worldgen:tree_features");
        }
        if ("feature".equals(source.name()) && FlowDataType.STRING.equals(source.dataType())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).optionsSource("worldgen:features");
        }
        if (source.constraints() != null && !source.constraints().isEmpty()) {
            builder.constraints(number(source.constraints().get("min")), number(source.constraints().get("max")), number(source.constraints().get("step")));
        }
        String description = source.description();
        if (description == null || description.isBlank() || description.strip().length() < 16) {
            description = "Provides the " + source.name() + " value for this WorldGen node.";
        }
        builder.description(description);
        return builder.build();
    }

    private static NodeDefinition.WidgetType widgetType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "number" -> NodeDefinition.WidgetType.NUMBER;
            case "dropdown" -> NodeDefinition.WidgetType.DROPDOWN;
            case "slider" -> NodeDefinition.WidgetType.SLIDER;
            case "toggle" -> NodeDefinition.WidgetType.TOGGLE;
            case "material", "searchable", "searchable_list" -> NodeDefinition.WidgetType.SEARCHABLE_LIST;
            default -> NodeDefinition.WidgetType.TEXT;
        };
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
