package restudio.resync.queue;

import restudio.resync.core.Session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class Request {
    private final String requestId;
    private final Session session;
    private final String channelId;
    private final Priority priority;
    private final Runnable task;
    private final CompletableFuture<Object> future;
    private final long submissionTime;
    private final AtomicLong executionTime;

    public Request(String requestId, Session session, String channelId, Priority priority, Runnable task) {
        this.requestId = requestId;
        this.session = session;
        this.channelId = channelId;
        this.priority = priority;
        this.task = task;
        this.future = new CompletableFuture<>();
        this.submissionTime = System.currentTimeMillis();
        this.executionTime = new AtomicLong(0);
    }

    public String getRequestId() {
        return requestId;
    }

    public Session getSession() {
        return session;
    }

    public String getChannelId() {
        return channelId;
    }

    public Priority getPriority() {
        return priority;
    }

    public Runnable getTask() {
        return task;
    }

    public CompletableFuture<Object> getFuture() {
        return future;
    }

    public long getSubmissionTime() {
        return submissionTime;
    }

    public long getExecutionTime() {
        return executionTime.get();
    }

    public void setExecutionTime(long time) {
        executionTime.set(time);
    }

    public long getQueueTime() {
        return System.currentTimeMillis() - submissionTime;
    }

    public int getWeight() {
        return priority.getWeight();
    }
}
