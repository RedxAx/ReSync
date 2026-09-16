package restudio.resync.queue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void highContentionDrainsTheExactCapacityWithoutRecursiveRetry() throws Exception {
        int workers = 32;
        int attemptsPerWorker = 4_096;
        long capacity = (long) workers * attemptsPerWorker;
        RateLimiter limiter = new RateLimiter(capacity, 0, TimeUnit.DAYS.toMillis(1));
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    int consumed = 0;
                    for (int attempt = 0; attempt < attemptsPerWorker; attempt++) {
                        if (limiter.tryConsume("shared", 1)) {
                            consumed++;
                        }
                    }
                    return consumed;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long consumed = 0;
            for (Future<Integer> result : results) {
                consumed += result.get(10, TimeUnit.SECONDS);
            }

            assertEquals(capacity, consumed);
            assertEquals(0, limiter.getAvailableTokens("shared"));
            assertFalse(limiter.tryConsume("shared", 1));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
