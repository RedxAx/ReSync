package restudio.resync.queue;

import org.bukkit.Bukkit;
import restudio.resync.Log;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestQueue {
    private final PriorityBlockingQueue<QueuedRequest> queue;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger activeRequests;
    private final AtomicInteger queuedRequests;
    private final int maxGlobalRequests;
    private final int maxClientRequests;
    private final ConcurrentHashMap<String, AtomicInteger> clientRequestCounts;
    private volatile boolean paused;
    private volatile double tpsThreshold;
    private volatile boolean tpsLimited;
    private Thread queueWorker;
    private Thread tpsMonitor;

    public RequestQueue(int maxGlobalRequests, int maxClientRequests, int threadPoolSize) {
        this.queue = new PriorityBlockingQueue<>();
        this.maxGlobalRequests = maxGlobalRequests;
        this.maxClientRequests = maxClientRequests;
        this.activeRequests = new AtomicInteger(0);
        this.queuedRequests = new AtomicInteger(0);
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

    public synchronized Request submit(String requestId, String clientId, String channelId, Priority priority, Runnable task) {
        if (paused) {
            throw new RejectedExecutionException("Queue is paused");
        }

        String ownerId = clientId == null || clientId.isBlank() ? "unknown" : clientId;
        AtomicInteger clientCount = clientRequestCounts.computeIfAbsent(ownerId, k -> new AtomicInteger(0));

        if (clientCount.get() >= maxClientRequests) {
            throw new RejectedExecutionException("Client request limit exceeded: " + maxClientRequests);
        }

        if (activeRequests.get() + queuedRequests.get() >= maxGlobalRequests) {
            throw new RejectedExecutionException("Global request limit exceeded: " + maxGlobalRequests);
        }

        Request request = new Request(requestId, ownerId, null, channelId, priority, task);
        QueuedRequest queued = new QueuedRequest(request);
        clientCount.incrementAndGet();
        queuedRequests.incrementAndGet();
        queue.put(queued);

        return request;
    }

    public void completeRequest(String clientId) {
        String ownerId = clientId == null || clientId.isBlank() ? "unknown" : clientId;
        AtomicInteger count = clientRequestCounts.get(ownerId);
        if (count != null) {
            int remaining = count.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining == 0) {
                clientRequestCounts.remove(ownerId, count);
            }
        }
        activeRequests.updateAndGet(value -> Math.max(0, value - 1));
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
        return queuedRequests.get();
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public int getClientRequestCount(String clientId) {
        AtomicInteger count = clientRequestCounts.get(clientId);
        return count != null ? count.get() : 0;
    }

    private void startWorker() {
        queueWorker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (paused || tpsLimited) {
                        Thread.sleep(100);
                        continue;
                    }

                    QueuedRequest queued = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (queued != null) {
                        queuedRequests.updateAndGet(value -> Math.max(0, value - 1));
                        executeRequest(queued);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ReSync-Queue-Worker");
        queueWorker.setDaemon(true);
        queueWorker.start();
    }

    private void executeRequest(QueuedRequest queued) {
        Request request = queued.request;
        long startTime = System.currentTimeMillis();

        try {
            activeRequests.incrementAndGet();
            if (!request.getJob().markRunning()) {
                return;
            }
            request.getTask().run();
            request.getJob().markSucceeded(null, "Succeeded");
        } catch (Exception e) {
            Log.warn("Request execution failed: " + e.getMessage());
            request.getJob().markFailed("Failed", e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            request.setExecutionTime(duration);

            completeRequest(request.getClientId());
        }
    }

    private void startTPSMonitor() {
        tpsMonitor = new Thread(() -> {
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
        tpsMonitor.setDaemon(true);
        tpsMonitor.start();
    }

    public void setTpsThreshold(double threshold) {
        this.tpsThreshold = threshold;
    }

    public void shutdown() {
        if (queueWorker != null) {
            queueWorker.interrupt();
        }
        if (tpsMonitor != null) {
            tpsMonitor.interrupt();
        }
        executor.shutdown();
        try {
            joinThread(queueWorker);
            joinThread(tpsMonitor);
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private void joinThread(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(5000);
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
