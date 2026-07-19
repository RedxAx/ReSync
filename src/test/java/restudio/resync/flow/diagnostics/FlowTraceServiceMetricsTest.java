package restudio.resync.flow.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowTraceServiceMetricsTest {
    @Test
    void aggregatesBoundedNodeMetricsWhenVerboseTracingIsDisabled() {
        FlowTraceService service = new FlowTraceService(50);
        service.record(record("time.parse", "success", 100));
        service.record(record("time.parse", "failure", 300));

        FlowTraceService.FlowNodeMetric metric = service.metricsSnapshot(10).getFirst();
        assertEquals("time.parse", metric.nodeType());
        assertEquals(2, metric.executions());
        assertEquals(1, metric.failures());
        assertEquals(400, metric.totalDurationNanos());
        assertEquals(300, metric.maximumDurationNanos());
        assertEquals(200, metric.averageDurationNanos());
        assertEquals(0, service.snapshot().size());
    }

    private FlowTraceRecord record(String nodeType, String status, long duration) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setNodeType(nodeType);
        record.setStatus(status);
        record.setDurationNanos(duration);
        return record;
    }
}
