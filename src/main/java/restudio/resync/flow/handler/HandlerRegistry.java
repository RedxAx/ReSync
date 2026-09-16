package restudio.resync.flow.handler;

import restudio.resync.api.ExtensionRegistryActivation;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerRegistry {
    private final Map<String, Registration> handlers = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private final boolean staging;
    private volatile ExtensionRegistryActivation activation;

    public HandlerRegistry() {
        this(false);
    }

    private HandlerRegistry(boolean staging) {
        this.staging = staging;
    }

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public void register(String handlerId, NodeHandler handler) {
        if (handlerId == null || handlerId.isBlank() || handler == null) throw new IllegalArgumentException("Handler ID and implementation are required");
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(HandlerRegistry.class, target -> {
                target.registerLocal(handlerId, handler);
                return null;
            });
            return;
        }
        registerLocal(handlerId, handler);
    }

    private void registerLocal(String handlerId, NodeHandler handler) {
        NodeHandler previous;
        synchronized (lifecycleMonitor) {
            Registration replacement = new Registration(handler, resolveSupportedOperations(handler));
            Registration previousRegistration = handlers.put(handlerId, replacement);
            previous = previousRegistration != null ? previousRegistration.handler() : null;
        }
        if (!staging && previous != null && previous != handler) previous.shutdown();
    }

    public void unregister(String handlerId) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(HandlerRegistry.class, target -> {
                target.unregisterLocal(handlerId);
                return null;
            });
            return;
        }
        unregisterLocal(handlerId);
    }

    private void unregisterLocal(String handlerId) {
        NodeHandler removed;
        synchronized (lifecycleMonitor) {
            Registration registration = handlers.remove(handlerId);
            removed = registration != null ? registration.handler() : null;
        }
        if (!staging && removed != null) removed.shutdown();
    }

    public NodeHandler getHandler(String handlerId) {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getHandler(handlerId);
        }
        Registration registration = handlers.get(handlerId);
        return registration != null ? registration.handler() : null;
    }

    public boolean hasHandler(String handlerId) {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.hasHandler(handlerId);
        }
        return handlers.containsKey(handlerId);
    }

    public boolean hasOperation(String handlerId, String operation) {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.hasOperation(handlerId, operation);
        }
        if (operation == null || operation.isBlank()) {
            return true;
        }
        Registration registration = handlers.get(handlerId);
        return registration != null && registration.operations().contains(operation);
    }

    public Set<String> getSupportedOperations(String handlerId) {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getSupportedOperations(handlerId);
        }
        Registration registration = handlers.get(handlerId);
        return registration != null ? registration.operations() : Set.of();
    }

    public int getHandlerCount() {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getHandlerCount();
        }
        return handlers.size();
    }

    public Set<String> getHandlerIds() {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getHandlerIds();
        }
        return Set.copyOf(handlers.keySet());
    }

    public Map<String, NodeHandler> snapshot() {
        HandlerRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.snapshot();
        }
        Map<String, NodeHandler> snapshot = new LinkedHashMap<>();
        handlers.forEach((id, registration) -> snapshot.put(id, registration.handler()));
        return Map.copyOf(snapshot);
    }

    public synchronized HandlerRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().handlers();
        }
        HandlerRegistry copy = new HandlerRegistry(true);
        copy.handlers.putAll(handlers);
        return copy;
    }

    public synchronized void replaceFrom(HandlerRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(HandlerRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(HandlerRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged handler registry is required");
        }
        handlers.clear();
        handlers.putAll(staged.handlers);
    }

    public synchronized NodeHandler unregisterWithoutShutdown(String handlerId) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.update(HandlerRegistry.class, target -> target.unregisterWithoutShutdownLocal(handlerId));
        }
        return unregisterWithoutShutdownLocal(handlerId);
    }

    private synchronized NodeHandler unregisterWithoutShutdownLocal(String handlerId) {
        Registration registration = handlerId != null ? handlers.remove(handlerId) : null;
        return registration != null ? registration.handler() : null;
    }

    public void shutdown(Collection<NodeHandler> handlers) {
        if (handlers == null) {
            return;
        }
        RuntimeException failure = null;
        for (NodeHandler handler : Set.copyOf(handlers)) {
            if (handler == null) {
                continue;
            }
            try {
                handler.shutdown();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IllegalStateException("One or more Flow handlers failed to shut down");
                }
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void clear() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(HandlerRegistry.class, target -> {
                target.clearLocal();
                return null;
            });
            return;
        }
        clearLocal();
    }

    public void clearForShutdown() {
        ExtensionRegistryActivation current = activation;
        Map<String, NodeHandler> retired;
        if (current != null) {
            retired = current.clearHandlersForShutdown();
        } else {
            synchronized (lifecycleMonitor) {
                Map<String, NodeHandler> snapshot = new LinkedHashMap<>();
                handlers.forEach((id, registration) -> snapshot.put(id, registration.handler()));
                retired = Map.copyOf(snapshot);
                handlers.clear();
            }
        }
        shutdown(retired.values());
    }

    private void clearLocal() {
        RuntimeException failure = null;
        Set<NodeHandler> registered;
        synchronized (lifecycleMonitor) {
            registered = Set.copyOf(handlers.values().stream().map(Registration::handler).toList());
            handlers.clear();
        }
        if (staging) {
            return;
        }
        for (NodeHandler handler : registered) {
            try {
                handler.shutdown();
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("One or more Flow handlers failed to shut down");
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private HandlerRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().handlers() : this;
    }

    private record Registration(NodeHandler handler, Set<String> operations) {
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
