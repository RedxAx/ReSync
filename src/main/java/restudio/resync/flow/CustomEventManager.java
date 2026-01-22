package restudio.resync.flow;

import restudio.flow.data.FlowNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomEventManager {
    private static CustomEventManager instance;
    
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> lastEventData = new ConcurrentHashMap<>();
    private final Map<Listener, Long> listenerTimeouts = new ConcurrentHashMap<>();
    private long currentTick = 0;
    
    public static synchronized CustomEventManager getInstance() {
        if (instance == null) {
            instance = new CustomEventManager();
        }
        return instance;
    }
    
    private CustomEventManager() {
    }
    
    public void listen(String eventId, Listener listener) {
        listeners.computeIfAbsent(eventId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(listener);
        if (listener.timeoutTicks > 0) {
            listenerTimeouts.put(listener, currentTick + listener.timeoutTicks);
        }
    }
    
    public void emit(String eventId, Map<String, Object> data) {
        lastEventData.put(eventId, new HashMap<>(data));
        
        List<Listener> eventListeners = listeners.get(eventId);
        if (eventListeners != null && !eventListeners.isEmpty()) {
            List<Object> results = new ArrayList<>();
            
            for (Listener listener : eventListeners) {
                FlowContext ctx = listener.ctx;
                String nodeId = listener.nodeId;
                
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    ctx.getRuntime().getEventVariables().put("custom." + eventId + "." + entry.getKey(), entry.getValue());
                }
                ctx.setNodeOutput(nodeId, "event_data", data);
                ctx.setNodeOutput(nodeId, "triggered", true);
                
                results.add(data);
                if (listener.timeoutTicks == 0) {
                   eventListeners.remove(listener);
                }
            }
            
            for (Listener listener : eventListeners) {
                 FlowContext ctx = listener.ctx;
                 ctx.triggerOutput("next");
            }
        }
    }
    
    public void clearListeners(String eventId) {
        listeners.remove(eventId);
        lastEventData.remove(eventId);
    }
    
    public Map<String, Object> getLastEventData(String eventId) {
        return lastEventData.get(eventId);
    }
    
    public void tick() {
        currentTick++;
        
        Iterator<Map.Entry<Listener, Long>> iter = listenerTimeouts.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Listener, Long> entry = iter.next();
            if (currentTick >= entry.getValue()) {
                Listener listener = entry.getKey();
                
                for (List<Listener> eventListeners : listeners.values()) {
                    eventListeners.remove(listener);
                }
                
                iter.remove();
            }
        }
    }
    
    public static class Listener {
        final FlowContext ctx;
        final String nodeId;
        final int timeoutTicks;
        
        public Listener(FlowContext ctx, String nodeId, int timeoutTicks) {
            this.ctx = ctx;
            this.nodeId = nodeId;
            this.timeoutTicks = timeoutTicks;
        }
    }
}
