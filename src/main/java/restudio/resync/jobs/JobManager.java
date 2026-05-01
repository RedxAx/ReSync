package restudio.resync.jobs;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class JobManager {
    private final Map<String, JobRecord<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, JobRecord<?>> jobsByRequest = new ConcurrentHashMap<>();
    private final Consumer<JobRecord<?>> listener;

    public JobManager(Consumer<JobRecord<?>> listener) {
        this.listener = listener;
    }

    public <T> JobRecord<T> create(String action, String actorClientId, String target) {
        return create(action, actorClientId, target, null);
    }

    @SuppressWarnings("unchecked")
    public <T> JobRecord<T> create(String action, String actorClientId, String target, String requestId) {
        String normalizedRequestId = requestId == null || requestId.isBlank() ? null : requestId;
        String requestKey = requestKey(actorClientId, normalizedRequestId);
        if (requestKey != null) {
            JobRecord<?> existing = jobsByRequest.get(requestKey);
            if (existing != null) {
                return (JobRecord<T>) existing;
            }
        }
        JobRecord<T> job = new JobRecord<>(UUID.randomUUID().toString(), action, actorClientId, target);
        if (normalizedRequestId != null) {
            job = new JobRecord<>(UUID.randomUUID().toString(), normalizedRequestId, action, actorClientId, target);
        }
        jobs.put(job.getJobId(), job);
        if (requestKey != null) {
            JobRecord<?> existing = jobsByRequest.putIfAbsent(requestKey, job);
            if (existing != null) {
                jobs.remove(job.getJobId());
                return (JobRecord<T>) existing;
            }
        }
        publish(job);
        return job;
    }

    public JobRecord<?> get(String jobId) {
        return jobs.get(jobId);
    }

    public List<Map<String, Object>> snapshot(String actorClientId) {
        return jobs.values().stream()
            .filter(job -> actorClientId == null || actorClientId.isBlank() || actorClientId.equals(job.getActorClientId()))
            .sorted((left, right) -> Long.compare(right.getSubmittedAt(), left.getSubmittedAt()))
            .map(JobRecord::snapshot)
            .toList();
    }

    public List<Map<String, Object>> activeOrRecentSnapshot(String actorClientId, long recentWindowMs) {
        long cutoff = System.currentTimeMillis() - Math.max(0, recentWindowMs);
        return jobs.values().stream()
            .filter(job -> actorClientId == null || actorClientId.isBlank() || actorClientId.equals(job.getActorClientId()))
            .filter(job -> !job.getStatus().terminal() || job.getFinishedAt() >= cutoff)
            .sorted((left, right) -> Long.compare(right.getSubmittedAt(), left.getSubmittedAt()))
            .map(JobRecord::snapshot)
            .toList();
    }

    public void publish(JobRecord<?> job) {
        if (listener != null && job != null) {
            listener.accept(job);
        }
    }

    private String requestKey(String actorClientId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String actor = actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId;
        return actor + '\n' + requestId;
    }
}
