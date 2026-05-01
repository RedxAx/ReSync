package restudio.resync.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionInfoTest {
    @Test
    void rejectsDuplicateAndStaleDataSequences() {
        ConnectionInfo info = new ConnectionInfo(null, 1);

        assertTrue(info.acceptInboundDataSequence(1));
        assertFalse(info.acceptInboundDataSequence(1));
        assertFalse(info.acceptInboundDataSequence(0));
        assertTrue(info.acceptInboundDataSequence(2));
    }
}
