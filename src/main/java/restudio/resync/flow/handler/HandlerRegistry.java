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
        handlers.put(handlerId, handler);
        handlerOperations.put(handlerId, resolveSupportedOperations(handler));
    }

    public void unregister(String handlerId) {
        handlers.remove(handlerId);
        handlerOperations.remove(handlerId);
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
        return operations == null || operations.isEmpty() || operations.contains(operation);
    }

    public Set<String> getSupportedOperations(String handlerId) {
        Set<String> operations = handlerOperations.get(handlerId);
        return operations != null ? operations : Set.of();
    }

    public int getHandlerCount() {
        return handlers.size();
    }

    public void clear() {
        handlers.clear();
        handlerOperations.clear();
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
            } catch (ReflectiveOperationException ignored) {
            }
            type = type.getSuperclass();
        }
        return Collections.emptySet();
    }
}
