package restudio.resync.flow.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTraceServiceTest {
    @Test
    void traceBufferIsBoundedAndOrdered() {
        FlowTraceService service = new FlowTraceService(50);
        service.setEnabled(true);
        for (int i = 0; i < 60; i++) {
            FlowTraceRecord record = new FlowTraceRecord();
            record.setNodeId("node" + i);
            service.record(record);
        }

        assertEquals(50, service.snapshot().size());
        assertEquals("node10", service.snapshot().getFirst().getNodeId());
        assertTrue(service.snapshot().getLast().getSequence() > service.snapshot().getFirst().getSequence());
    }
}
