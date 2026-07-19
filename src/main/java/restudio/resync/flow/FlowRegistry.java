package restudio.resync.flow;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

@Deprecated
public class FlowRegistry {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> executors;
    private HandlerRegistry handlerRegistry;

    public FlowRegistry() {
        this.executors = new HashMap<>();
    }

    public void setHandlerRegistry(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    public void register(String type, BiConsumer<FlowContext, FlowNode> executor) {
        executors.put(type, executor);
        if (handlerRegistry != null) {
            handlerRegistry.register(type, (ctx, node) -> executor.accept(ctx, node));
        }
    }

    public void unregister(String type) {
        executors.remove(type);
        if (handlerRegistry != null) {
            handlerRegistry.unregister(type);
        }
    }

    public BiConsumer<FlowContext, FlowNode> getExecutor(String type) {
        if (handlerRegistry != null && handlerRegistry.hasHandler(type)) {
            NodeHandler handler = handlerRegistry.getHandler(type);
            return (ctx, node) -> handler.execute(ctx, node);
        }
        return executors.get(type);
    }

    public Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(executors.keySet());
    }
}
