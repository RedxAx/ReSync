package restudio.resync.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LRUCache<K, V> {
    private final LinkedHashMap<K, CacheEntry<V>> map;
    private final ReentrantReadWriteLock lock;
    private final int maxSize;
    private final long ttlMillis;
    private long totalSize;

    public LRUCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.totalSize = 0;
        this.lock = new ReentrantReadWriteLock();

        this.map = new LinkedHashMap<K, CacheEntry<V>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    synchronized (LRUCache.this) {
                        totalSize -= eldest.getValue().size;
                    }
                    return true;
                }
                return false;
            }
        };
    }

    public V get(K key) {
        lock.readLock().lock();
        try {
            CacheEntry<V> entry = map.get(key);
            if (entry == null) {
                return null;
            }

            if (entry.isExpired(ttlMillis)) {
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    CacheEntry<V> expiredEntry = map.remove(key);
                    if (expiredEntry != null) {
                        totalSize -= expiredEntry.size;
                    }
                } finally {
                    lock.writeLock().unlock();
                    lock.readLock().lock();
                }
                return null;
            }

            return entry.val;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            CacheEntry<V> existing = map.get(key);
            if (existing != null) {
                totalSize -= existing.size;
            }

            int size = estimateSize(value);
            CacheEntry<V> entry = new CacheEntry<>(value, System.currentTimeMillis(), size);
            map.put(key, entry);
            totalSize += size;

            while (map.size() > maxSize) {
                Map.Entry<K, CacheEntry<V>> eldest = map.entrySet().iterator().next();
                totalSize -= eldest.getValue().size;
                map.remove(eldest.getKey());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(K key) {
        lock.writeLock().lock();
        try {
            CacheEntry<V> entry = map.remove(key);
            if (entry != null) {
                totalSize -= entry.size;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
            totalSize = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getTotalSize() {
        lock.readLock().lock();
        try {
            return totalSize;
        } finally {
            lock.readLock().unlock();
        }
    }

    private int estimateSize(Object value) {
        if (value == null) {
            return 0;
        }

        if (value instanceof byte[]) {
            return ((byte[]) value).length;
        }

        return 1024;
    }

    private static class CacheEntry<V> {
        final V val;
        final long timestamp;
        final int size;

        CacheEntry(V val, long timestamp, int size) {
            this.val = val;
            this.timestamp = timestamp;
            this.size = size;
        }

        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - timestamp > ttl;
        }
    }
}
