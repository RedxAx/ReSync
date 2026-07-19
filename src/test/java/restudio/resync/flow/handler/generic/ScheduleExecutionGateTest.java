package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleExecutionGateTest {
    @Test
    void suppressesOverlapUntilExecutionCompletes() {
        ScheduleExecutionGate gate = new ScheduleExecutionGate();

        assertTrue(gate.tryBegin());
        assertTrue(gate.isRunning());
        assertFalse(gate.tryBegin());

        gate.complete();

        assertFalse(gate.isRunning());
        assertTrue(gate.tryBegin());
    }
}
