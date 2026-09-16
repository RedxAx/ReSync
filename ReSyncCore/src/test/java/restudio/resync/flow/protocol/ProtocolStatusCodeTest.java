package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolStatusCodeTest {
    @Test
    void statusWireValuesRoundTripAndRejectUnknownValues() {
        for (ProtocolStatusCode status : ProtocolStatusCode.values()) {
            assertEquals(status, ProtocolStatusCode.fromWire(status.wireValue()));
            assertEquals(status, ProtocolStatusCode.fromWire(status.legacyValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> ProtocolStatusCode.fromWire("status.future"));
    }

    @Test
    void rejectionWireValuesCarryExplicitRecoveryMetadata() {
        for (ProtocolRejectionCode rejection : ProtocolRejectionCode.values()) {
            assertEquals(rejection, ProtocolRejectionCode.fromWire(rejection.wireValue()));
            assertTrue(rejection.transportCode() >= 400 && rejection.transportCode() <= 599);
            assertNotNull(rejection.transportClass());
            assertNotNull(rejection.retryability());
            assertNotNull(rejection.recoveryAction());
        }
        assertEquals(ProtocolRejectionCode.INVALID_BODY, ProtocolRejectionCode.fromLegacy("PROTO.INVALID_BODY", 400));
        assertEquals(ProtocolRejectionCode.INVALID_CURSOR, ProtocolRejectionCode.fromLegacy("PROTO.INVALID_CURSOR", 400));
        assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
            ProtocolRejectionCode.fromLegacy("PROTO.RESOURCE_OPERATION_FAILED", 422));
        assertEquals(ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
            ProtocolRejectionCode.fromLegacy("RUNTIME.AUTHORIZATION_DENIED", 401));
        assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
            ProtocolRejectionCode.fromResourceError("RESOURCE_MUTATION_PENDING"));
        assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
            ProtocolRejectionCode.fromResourceError("future.resource.failure"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRejectionCode.fromWire("PROTO.FUTURE_STATUS"));
    }
}
