package restudio.resync.jobs;

import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class JobManager {
    private static final int MAX_RETAINED = 1024;
    private static final long TERMINAL_RETENTION_MS = Duration.ofMinutes(15).toMillis();
    private final Map<String, JobRecord<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, JobRecord<?>> jobsByRequest = new ConcurrentHashMap<>();
    private final Consumer<JobRecord<?>> listener;
    private final FlowJobRegistry canonicalRegistry;

    public JobManager(Consumer<JobRecord<?>> listener) {
        this(null, listener);
    }

    public JobManager(FlowJobRegistry canonicalRegistry, Consumer<JobRecord<?>> listener) {
        this.canonicalRegistry = canonicalRegistry;
        this.listener = listener;
    }

    public <T> JobRecord<T> create(String action, String actorClientId, String target) {
        return create(action, actorClientId, target, null);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> JobRecord<T> create(String action, String actorClientId, String target, String requestId) {
        prune();
        String normalizedRequestId = requestId == null || requestId.isBlank() ? null : requestId;
        String requestKey = requestKey(actorClientId, normalizedRequestId);
        if (requestKey != null) {
            JobRecord<?> existing = jobsByRequest.get(requestKey);
            if (existing != null) {
                return (JobRecord<T>) existing;
            }
        }
        String effectiveRequestId = normalizedRequestId != null ? normalizedRequestId : null;
        JobRecord<T> job;
        if (canonicalRegistry != null) {
            FlowJobReference<T> reference = canonicalRegistry.create(action, actorClientId);
            job = new JobRecord<>(effectiveRequestId != null ? effectiveRequestId : reference.getId(), action, actorClientId, target, reference, canonicalRegistry);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("action", action != null ? action : "");
            metadata.put("target", target != null ? target : "");
            metadata.put("actorClientId", actorClientId != null ? actorClientId : "unknown");
            if (effectiveRequestId != null) {
                metadata.put("requestId", effectiveRequestId);
            }
            canonicalRegistry.update(reference, 0.0, metadata);
        } else if (effectiveRequestId != null) {
            job = new JobRecord<>(UUID.randomUUID().toString(), effectiveRequestId, action, actorClientId, target);
        } else {
            job = new JobRecord<>(UUID.randomUUID().toString(), action, actorClientId, target);
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
        prune();
        return jobs.values().stream()
            .filter(job -> actorClientId == null || actorClientId.isBlank() || actorClientId.equals(job.getActorClientId()))
            .sorted((left, right) -> Long.compare(right.getSubmittedAt(), left.getSubmittedAt()))
            .map(JobRecord::snapshot)
            .toList();
    }

    public List<Map<String, Object>> activeOrRecentSnapshot(String actorClientId, long recentWindowMs) {
        prune();
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

    private void prune() {
        long cutoff = System.currentTimeMillis() - TERMINAL_RETENTION_MS;
        jobs.values().removeIf(job -> {
            boolean remove = job.getStatus().terminal() && job.getFinishedAt() < cutoff;
            if (remove && job.getRequestId() != null && !job.getRequestId().isBlank()) {
                jobsByRequest.remove(requestKey(job.getActorClientId(), job.getRequestId()), job);
            }
            return remove;
        });
        int overflow = jobs.size() - MAX_RETAINED;
        if (overflow <= 0) {
            return;
        }
        List<JobRecord<?>> removable = jobs.values().stream()
            .filter(job -> job.getStatus().terminal())
            .sorted((left, right) -> Long.compare(left.getSubmittedAt(), right.getSubmittedAt()))
            .limit(overflow)
            .toList();
        for (JobRecord<?> job : removable) {
            jobs.remove(job.getJobId(), job);
            if (job.getRequestId() != null && !job.getRequestId().isBlank()) {
                jobsByRequest.remove(requestKey(job.getActorClientId(), job.getRequestId()), job);
            }
        }
    }
}
