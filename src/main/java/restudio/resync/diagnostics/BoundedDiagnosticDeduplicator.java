package restudio.resync.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BoundedDiagnosticDeduplicator {
    private final int capacity;
    private final ConcurrentHashMap<String, Boolean> keys = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();

    public BoundedDiagnosticDeduplicator(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Diagnostic deduplication capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean add(String key) {
        if (key == null || keys.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        insertionOrder.add(key);
        trim();
        return true;
    }

    public int size() {
        return keys.size();
    }

    private void trim() {
        while (keys.size() > capacity) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            keys.remove(oldest);
        }
    }
}
