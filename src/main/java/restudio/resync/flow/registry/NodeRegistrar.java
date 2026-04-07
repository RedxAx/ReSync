package restudio.resync.flow.registry;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
            builder.input(pin.name(), pin.type(), pin.dataType());
        }

        for (FlowPin pin : annotation.outputs()) {
            builder.output(pin.name(), pin.type(), pin.dataType());
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
