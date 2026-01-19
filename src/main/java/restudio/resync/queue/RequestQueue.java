package restudio.resync.queue;

import org.bukkit.Bukkit;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestQueue {
    private final PriorityBlockingQueue<QueuedRequest> queue;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger activeRequests;
    private final int maxGlobalRequests;
    private final int maxClientRequests;
    private final ConcurrentHashMap<String, AtomicInteger> clientRequestCounts;
    private volatile boolean paused;
    private volatile double tpsThreshold;
    private volatile boolean tpsLimited;

    public RequestQueue(int maxGlobalRequests, int maxClientRequests, int threadPoolSize) {
        this.queue = new PriorityBlockingQueue<>();
        this.maxGlobalRequests = maxGlobalRequests;
        this.maxClientRequests = maxClientRequests;
        this.activeRequests = new AtomicInteger(0);
        this.clientRequestCounts = new ConcurrentHashMap<>();
        this.paused = false;
        this.tpsThreshold = 18.0;
        this.tpsLimited = false;

        this.executor = new ThreadPoolExecutor(
            threadPoolSize,
            threadPoolSize * 2,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> new Thread(r, "ReSync-Request-Worker")
        );

        this.executor.allowCoreThreadTimeOut(true);

        startWorker();
        startTPSMonitor();
    }

    public Request submit(String requestId, String clientId, String channelId, Priority priority, Runnable task) {
        if (paused) {
            throw new RejectedExecutionException("Queue is paused");
        }

        AtomicInteger clientCount = clientRequestCounts.computeIfAbsent(clientId, k -> new AtomicInteger(0));

        if (clientCount.get() >= maxClientRequests) {
            throw new RejectedExecutionException("Client request limit exceeded: " + maxClientRequests);
        }

        if (activeRequests.get() >= maxGlobalRequests) {
            throw new RejectedExecutionException("Global request limit exceeded: " + maxGlobalRequests);
        }

        Request request = new Request(requestId, null, channelId, priority, task);
        QueuedRequest queued = new QueuedRequest(request);
        queue.put(queued);

        clientCount.incrementAndGet();

        return request;
    }

    public void completeRequest(String clientId) {
        AtomicInteger count = clientRequestCounts.get(clientId);
        if (count != null) {
            count.decrementAndGet();
        }
        activeRequests.decrementAndGet();
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public int getClientRequestCount(String clientId) {
        AtomicInteger count = clientRequestCounts.get(clientId);
        return count != null ? count.get() : 0;
    }

    private void startWorker() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (paused || tpsLimited) {
                        Thread.sleep(100);
                        continue;
                    }

                    QueuedRequest queued = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (queued != null) {
                        executeRequest(queued);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ReSync-Queue-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void executeRequest(QueuedRequest queued) {
        Request request = queued.request;
        long startTime = System.currentTimeMillis();

        try {
            activeRequests.incrementAndGet();
            request.getTask().run();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            request.setExecutionTime(duration);

            if (request.getSession() != null) {
                completeRequest(request.getSession().getClientId());
            } else {
                activeRequests.decrementAndGet();
            }
        }
    }

    private void startTPSMonitor() {
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (Bukkit.getServer() != null) {
                        double tps = Bukkit.getServer().getTPS()[0];
                        tpsLimited = tps < tpsThreshold;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ReSync-TPS-Monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public void setTpsThreshold(double threshold) {
        this.tpsThreshold = threshold;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private static class QueuedRequest implements Comparable<QueuedRequest> {
        final Request request;
        final long priority;

        QueuedRequest(Request request) {
            this.request = request;
            this.priority = (long) request.getPriority().getValue() << 32 | (System.currentTimeMillis() & 0xFFFFFFFFL);
        }

        @Override
        public int compareTo(QueuedRequest other) {
            long diff = this.priority - other.priority;
            return diff < 0 ? -1 : diff > 0 ? 1 : 0;
        }
    }
}
