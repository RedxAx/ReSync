package restudio.resync.flow.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FlowTraceService {
    private final ArrayDeque<FlowTraceRecord> records = new ArrayDeque<>();
    private final CopyOnWriteArrayList<FlowTraceSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
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
        if (record == null || !enabled.get()) {
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
}
