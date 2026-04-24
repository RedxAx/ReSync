package restudio.resync.flow.registry;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class NodeRegistrar {

    private final FlowRegistry flowRegistry;
    private final NodeDefinitionRegistry definitionRegistry;
    private final String pluginId;
    private final List<String> registeredNodeIds = new ArrayList<>();

    public NodeRegistrar(FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry, String pluginId) {
        this.flowRegistry = flowRegistry;
        this.definitionRegistry = definitionRegistry;
        this.pluginId = pluginId;
    }

    public void scan(Object container) {
        for (Method method : container.getClass().getDeclaredMethods()) {
            DefineNode annotation = method.getAnnotation(DefineNode.class);
            if (annotation == null) continue;

            method.setAccessible(true);

            if (definitionRegistry != null) {
                NodeDefinition definition = buildDefinition(annotation);
                definitionRegistry.register(pluginId, definition);
            }

            BiConsumer<FlowContext, FlowNode> executor = buildExecutor(method, container);
            flowRegistry.register(annotation.id(), executor);

            registeredNodeIds.add(annotation.id());
        }
    }

    public void scan(Class<?> containerClass) {
        Object instance;
        try {
            instance = containerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate node container: " + containerClass.getName(), e);
        }
        scan(instance);
    }

    private NodeDefinition buildDefinition(DefineNode annotation) {
        NodeDefinition.Builder builder = new NodeDefinition.Builder(
                annotation.id(),
                annotation.displayName(),
                annotation.category()
        );

        for (FlowPin pin : annotation.inputs()) {
            builder.input(buildPin(pin, NodeDefinition.PinDirection.INPUT));
        }

        for (FlowPin pin : annotation.outputs()) {
            builder.output(buildPin(pin, NodeDefinition.PinDirection.OUTPUT));
        }

        if (annotation.color() != -1) {
            builder.color(annotation.color());
        }

        builder.priority(annotation.priority());
        if (annotation.hidden()) {
            builder.hidden();
        }

        return builder.build();
    }

    private NodeDefinition.PinDefinition buildPin(FlowPin pin, NodeDefinition.PinDirection direction) {
        FlowType dataType = pin.dataType();
        if (pin.type() == NodeDefinition.PinType.FLOW && dataType == FlowType.ANY) {
            dataType = FlowType.EXECUTION;
        }

        boolean hasMetadata = pin.widget() != NodeDefinition.WidgetType.AUTO
                || pin.options().length > 0
                || !pin.optionsSource().isEmpty()
                || !pin.defaultValue().isEmpty()
                || !Double.isNaN(pin.min())
                || !Double.isNaN(pin.max())
                || !Double.isNaN(pin.step())
                || pin.visibleWhen().length > 0
                || !pin.description().isEmpty();

        if (!hasMetadata) {
            return new NodeDefinition.PinDefinition(pin.name(), pin.type(), direction, dataType);
        }

        NodeDefinition.PinBuilder pb = new NodeDefinition.PinBuilder(pin.name(), pin.type(), direction, dataType);
        if (pin.widget() != NodeDefinition.WidgetType.AUTO) {
            pb.widget(pin.widget());
        }
        if (pin.options().length > 0) {
            pb.options(List.of(pin.options()));
        }
        if (!pin.optionsSource().isEmpty()) {
            pb.optionsSource(pin.optionsSource());
            if (pin.options().length == 0) {
                pb.options(NodeCatalogs.resolve(pin.optionsSource()));
            }
        }
        if (!pin.defaultValue().isEmpty()) {
            pb.defaultValue(pin.defaultValue());
        }
        if (!Double.isNaN(pin.min()) || !Double.isNaN(pin.max()) || !Double.isNaN(pin.step())) {
            pb.constraints(
                Double.isNaN(pin.min()) ? null : pin.min(),
                Double.isNaN(pin.max()) ? null : pin.max(),
                Double.isNaN(pin.step()) ? null : pin.step()
            );
        }
        if (pin.visibleWhen().length > 0) {
            Map<String, String> conditions = new HashMap<>();
            for (VisibleWhen vw : pin.visibleWhen()) {
                conditions.put(vw.pin(), vw.value());
            }
            pb.visibleWhen(conditions);
        }
        if (!pin.description().isEmpty()) {
            pb.description(pin.description());
        }
        return pb.build();
    }

    private BiConsumer<FlowContext, FlowNode> buildExecutor(Method method, Object container) {
        return (ctx, node) -> {
            try {
                method.invoke(container, ctx, node);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("Node executor failed: " + method.getName(), cause);
            }
        };
    }

    public void unregisterAll() {
        for (String nodeId : registeredNodeIds) {
            flowRegistry.unregister(nodeId);
        }
        if (definitionRegistry != null) {
            definitionRegistry.unregisterPlugin(pluginId);
        }
        registeredNodeIds.clear();
    }

    public List<String> getRegisteredNodeIds() {
        return List.copyOf(registeredNodeIds);
    }
}
