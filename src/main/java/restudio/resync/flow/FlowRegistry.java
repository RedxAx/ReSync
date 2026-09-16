package restudio.resync.flow;

import restudio.flow.data.FlowNode;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

@Deprecated
public class FlowRegistry {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> executors;
    private HandlerRegistry handlerRegistry;
    private volatile ExtensionRegistryActivation activation;

    public FlowRegistry() {
        this.executors = new HashMap<>();
    }

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public void setHandlerRegistry(HandlerRegistry handlerRegistry) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowRegistry.class, target -> {
                target.setHandlerRegistryLocal(handlerRegistry);
                return null;
            });
            return;
        }
        setHandlerRegistryLocal(handlerRegistry);
    }

    private void setHandlerRegistryLocal(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    public void register(String type, BiConsumer<FlowContext, FlowNode> executor) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowRegistry.class, target -> {
                target.registerLocal(type, executor);
                return null;
            });
            return;
        }
        registerLocal(type, executor);
    }

    private void registerLocal(String type, BiConsumer<FlowContext, FlowNode> executor) {
        executors.put(type, executor);
        if (handlerRegistry != null) {
            handlerRegistry.register(type, (ctx, node) -> executor.accept(ctx, node));
        }
    }

    public void unregister(String type) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowRegistry.class, target -> {
                target.unregisterLocal(type);
                return null;
            });
            return;
        }
        unregisterLocal(type);
    }

    private void unregisterLocal(String type) {
        executors.remove(type);
        if (handlerRegistry != null) {
            handlerRegistry.unregister(type);
        }
    }

    public void unregisterWithoutShutdown(String type) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowRegistry.class, target -> {
                target.unregisterWithoutShutdownLocal(type);
                return null;
            });
            return;
        }
        unregisterWithoutShutdownLocal(type);
    }

    private void unregisterWithoutShutdownLocal(String type) {
        executors.remove(type);
        if (handlerRegistry != null) {
            handlerRegistry.unregisterWithoutShutdown(type);
        }
    }

    public BiConsumer<FlowContext, FlowNode> getExecutor(String type) {
        FlowRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getExecutor(type);
        }
        if (handlerRegistry != null && handlerRegistry.hasHandler(type)) {
            NodeHandler handler = handlerRegistry.getHandler(type);
            return (ctx, node) -> handler.execute(ctx, node);
        }
        return executors.get(type);
    }

    public Set<String> getRegisteredTypes() {
        FlowRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getRegisteredTypes();
        }
        return Set.copyOf(executors.keySet());
    }

    public Set<String> executorIncarnationFingerprint() {
        FlowRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.executorIncarnationFingerprint();
        }
        return executors.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + Integer.toHexString(System.identityHashCode(entry.getValue())))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public synchronized FlowRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().flowRegistry();
        }
        FlowRegistry copy = new FlowRegistry();
        copy.executors.putAll(executors);
        copy.handlerRegistry = handlerRegistry != null ? handlerRegistry.copy() : null;
        return copy;
    }

    public synchronized FlowRegistry copy(HandlerRegistry handlers) {
        FlowRegistry copy = new FlowRegistry();
        copy.executors.putAll(executors);
        copy.handlerRegistry = handlers != null ? handlers : handlerRegistry != null ? handlerRegistry.copy() : null;
        return copy;
    }

    public synchronized void replaceFrom(FlowRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(FlowRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged Flow registry is required");
        }
        executors.clear();
        executors.putAll(staged.executors);
    }

    public HandlerRegistry handlerRegistry() {
        FlowRegistry activeRegistry = activeRegistry();
        return activeRegistry != this ? activeRegistry.handlerRegistry : handlerRegistry;
    }

    private FlowRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().flowRegistry() : this;
    }
}
