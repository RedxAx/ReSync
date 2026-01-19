package restudio.resync.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiter {
    private final ConcurrentHashMap<String, TokenBucket> buckets;
    private final long defaultCapacity;
    private final long defaultRefillRate;
    private final long defaultRefillInterval;

    public RateLimiter(long defaultCapacity, long defaultRefillRate, long refillIntervalMs) {
        this.buckets = new ConcurrentHashMap<>();
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.defaultRefillInterval = refillIntervalMs;
    }

    public boolean tryConsume(String clientId, int tokens) {
        return tryConsume(clientId, tokens, defaultCapacity, defaultRefillRate, defaultRefillInterval);
    }

    public boolean tryConsume(String clientId, int tokens, long capacity, long refillRate, long refillInterval) {
        TokenBucket bucket = buckets.computeIfAbsent(clientId, id -> 
            new TokenBucket(capacity, refillRate, refillInterval));
        return bucket.tryConsume(tokens);
    }

    public void reset(String clientId) {
        buckets.remove(clientId);
    }

    public void resetAll() {
        buckets.clear();
    }

    public long getAvailableTokens(String clientId) {
        TokenBucket bucket = buckets.get(clientId);
        return bucket != null ? bucket.getAvailableTokens() : 0;
    }

    private static class TokenBucket {
        private final long capacity;
        private final long refillRate;
        private final long refillInterval;
        private final AtomicLong tokens;
        private final AtomicLong lastRefill;

        TokenBucket(long capacity, long refillRate, long refillInterval) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.refillInterval = refillInterval;
            this.tokens = new AtomicLong(capacity);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
        }

        public boolean tryConsume(int amount) {
            refillIfNeeded();

            long currentTokens = tokens.get();
            if (currentTokens >= amount) {
                if (tokens.compareAndSet(currentTokens, currentTokens - amount)) {
                    return true;
                }
                return tryConsume(amount);
            }
            return false;
        }

        public long getAvailableTokens() {
            refillIfNeeded();
            return tokens.get();
        }

        private void refillIfNeeded() {
            long now = System.currentTimeMillis();
            long last = lastRefill.get();

            if (now - last >= refillInterval) {
                if (lastRefill.compareAndSet(last, now)) {
                    long elapsed = now - last;
                    long additions = (elapsed / refillInterval) * refillRate;
                    long currentTokens = tokens.get();
                    long newTokens = Math.min(capacity, currentTokens + additions);
                    tokens.set(newTokens);
                }
            }
        }
    }
}
