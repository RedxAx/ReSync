package restudio.resync.core;

import org.junit.jupiter.api.Test;
import restudio.resync.protocol.FrameSender;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void frameSenderDefaultsToAnUnboundedEncodedFrameBudget() {
        ConnectionInfo info = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);

        assertEquals(FrameSender.UNBOUNDED_MAX_ENCODED_FRAME_BYTES, info.getMaxEncodedFrameBytes());
    }

    @Test
    void frameSenderBudgetIsExposedByConnectionInfo() {
        ConnectionInfo info = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }

            @Override
            public int getMaxEncodedFrameBytes() {
                return 24_000;
            }
        }, 1);

        assertEquals(24_000, info.getMaxEncodedFrameBytes());
    }

    @Test
    void negotiatedFlowCapabilitiesAreDefensiveExactAndClearedIndependently() {
        ConnectionInfo info = new ConnectionInfo(null, 1);
        Set<String> offered = new HashSet<>(Set.of("option_queries", "resources"));
        Set<String> negotiated = new HashSet<>(Set.of("option_queries"));

        info.setClientCapabilities(offered);
        info.setNegotiatedFlowCapabilities(negotiated);
        offered.clear();
        negotiated.clear();

        assertEquals(Set.of("option_queries", "resources"), info.getClientCapabilities());
        assertEquals(Set.of("option_queries"), info.getNegotiatedFlowCapabilities());
        assertTrue(info.hasNegotiatedFlowCapability("option_queries"));
        assertFalse(info.hasNegotiatedFlowCapability("OPTION_QUERIES"));

        info.setProtocolResourceAccess(true);
        info.clearProtocolSession();

        assertFalse(info.hasProtocolResourceAccess());
        assertTrue(info.getNegotiatedFlowCapabilities().isEmpty());
        assertEquals(Set.of("option_queries", "resources"), info.getClientCapabilities());
    }
}
