package restudio.resync.storage;

import restudio.resync.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AsyncStorageExecutor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ReSync-Storage-Writer");
        thread.setDaemon(true);
        return thread;
    });
    private final List<CompletableFuture<Void>> pending = java.util.Collections.synchronizedList(new ArrayList<>());

    public void submit(Runnable task) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(task, executor)
            .exceptionally(error -> {
                Log.warn("Async storage write failed: " + error.getMessage());
                return null;
            });
        pending.add(future);
        future.whenComplete((ignored, error) -> pending.remove(future));
    }

    public void flush() {
        CompletableFuture<?>[] futures;
        synchronized (pending) {
            futures = pending.toArray(new CompletableFuture[0]);
        }
        CompletableFuture.allOf(futures).join();
    }

    public void shutdown() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
