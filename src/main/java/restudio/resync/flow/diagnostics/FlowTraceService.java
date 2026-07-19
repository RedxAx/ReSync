package restudio.resync.flow.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class FlowTraceService {
    private static final int MAX_METRIC_KEYS = 4096;
    private final ArrayDeque<FlowTraceRecord> records = new ArrayDeque<>();
    private final CopyOnWriteArrayList<FlowTraceSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, NodeMetricAccumulator> nodeMetrics = new ConcurrentHashMap<>();
    private final int capacity;

    public FlowTraceService(int capacity) {
        this.capacity = Math.max(50, capacity);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public void clear() {
        synchronized (records) {
            records.clear();
        }
        nodeMetrics.clear();
    }

    public void addSink(FlowTraceSink sink) {
        if (sink != null) {
            sinks.addIfAbsent(sink);
        }
    }

    public void removeSink(FlowTraceSink sink) {
        sinks.remove(sink);
    }

    public void record(FlowTraceRecord record) {
        if (record == null) {
            return;
        }
        aggregate(record);
        if (!enabled.get()) {
            return;
        }
        record.setSequence(sequence.incrementAndGet());
        record.setTimestamp(System.currentTimeMillis());
        synchronized (records) {
            records.addLast(record);
            while (records.size() > capacity) {
                records.removeFirst();
            }
        }
        for (FlowTraceSink sink : sinks) {
            sink.onTraceRecord(record);
        }
    }

    public List<FlowTraceRecord> snapshot() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    public List<FlowNodeMetric> metricsSnapshot(int limit) {
        int resultLimit = Math.clamp(limit, 1, MAX_METRIC_KEYS);
        return nodeMetrics.entrySet().stream()
            .map(entry -> entry.getValue().snapshot(entry.getKey()))
            .sorted(Comparator.comparingLong(FlowNodeMetric::totalDurationNanos).reversed()
                .thenComparing(FlowNodeMetric::nodeType, String.CASE_INSENSITIVE_ORDER))
            .limit(resultLimit)
            .toList();
    }

    private void aggregate(FlowTraceRecord record) {
        if (!("success".equals(record.getStatus()) || "failure".equals(record.getStatus()))) {
            return;
        }
        String nodeType = record.getNodeType() != null && !record.getNodeType().isBlank() ? record.getNodeType() : "unresolved";
        String key = nodeMetrics.containsKey(nodeType) || nodeMetrics.size() < MAX_METRIC_KEYS ? nodeType : "__other__";
        nodeMetrics.computeIfAbsent(key, ignored -> new NodeMetricAccumulator()).record(record);
    }

    public record FlowNodeMetric(String nodeType, long executions, long failures, long totalDurationNanos, long maximumDurationNanos,
                                 long averageDurationNanos) {
    }

    private static final class NodeMetricAccumulator {
        private final LongAdder executions = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder totalDurationNanos = new LongAdder();
        private final AtomicLong maximumDurationNanos = new AtomicLong();

        private void record(FlowTraceRecord record) {
            long duration = Math.max(0, record.getDurationNanos());
            executions.increment();
            totalDurationNanos.add(duration);
            maximumDurationNanos.accumulateAndGet(duration, Math::max);
            if ("failure".equals(record.getStatus())) {
                failures.increment();
            }
        }

        private FlowNodeMetric snapshot(String nodeType) {
            long count = executions.sum();
            long total = totalDurationNanos.sum();
            return new FlowNodeMetric(nodeType, count, failures.sum(), total, maximumDurationNanos.get(), count > 0 ? total / count : 0);
        }
    }
}
