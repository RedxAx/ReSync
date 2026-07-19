package restudio.resync.flow.handler;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerRegistry {
    private final Map<String, NodeHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> handlerOperations = new ConcurrentHashMap<>();

    public void register(String handlerId, NodeHandler handler) {
        if (handlerId == null || handlerId.isBlank() || handler == null) throw new IllegalArgumentException("Handler ID and implementation are required");
        NodeHandler previous = handlers.get(handlerId);
        if (previous != null && previous != handler) previous.shutdown();
        handlers.put(handlerId, handler);
        handlerOperations.put(handlerId, resolveSupportedOperations(handler));
    }

    public void unregister(String handlerId) {
        NodeHandler removed = handlers.remove(handlerId);
        handlerOperations.remove(handlerId);
        if (removed != null) removed.shutdown();
    }

    public NodeHandler getHandler(String handlerId) {
        return handlers.get(handlerId);
    }

    public boolean hasHandler(String handlerId) {
        return handlers.containsKey(handlerId);
    }

    public boolean hasOperation(String handlerId, String operation) {
        if (operation == null || operation.isBlank()) {
            return true;
        }
        Set<String> operations = handlerOperations.get(handlerId);
        return operations != null && operations.contains(operation);
    }

    public Set<String> getSupportedOperations(String handlerId) {
        Set<String> operations = handlerOperations.get(handlerId);
        return operations != null ? operations : Set.of();
    }

    public int getHandlerCount() {
        return handlers.size();
    }

    public Set<String> getHandlerIds() {
        return Set.copyOf(handlers.keySet());
    }

    public void clear() {
        RuntimeException failure = null;
        for (NodeHandler handler : Set.copyOf(handlers.values())) {
            try {
                handler.shutdown();
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("One or more Flow handlers failed to shut down");
                failure.addSuppressed(exception);
            }
        }
        handlers.clear();
        handlerOperations.clear();
        if (failure != null) throw failure;
    }

    private Set<String> resolveSupportedOperations(NodeHandler handler) {
        if (handler == null) {
            return Set.of();
        }
        Set<String> declared = handler.getSupportedOperations();
        if (declared != null && !declared.isEmpty()) {
            return Set.copyOf(declared);
        }
        Class<?> type = handler.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("operations");
                field.setAccessible(true);
                Object value = field.get(handler);
                if (value instanceof Map<?, ?> map) {
                    return Set.copyOf(map.keySet().stream().filter(String.class::isInstance).map(String.class::cast).toList());
                }
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
                continue;
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to inspect supported operations for " + handler.getClass().getName(), exception);
            }
            type = type.getSuperclass();
        }
        return Collections.emptySet();
    }
}
