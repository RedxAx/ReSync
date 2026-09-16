package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ProtocolStatusCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolEnvelopeDispatchStatusTest {
    @Test
    void typedRejectionCarriesTransportAndRecoveryMetadata() {
        ProtocolEnvelopeDispatchResult result = ProtocolEnvelopeDispatchResult.rejected(
            ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Resource reads are temporarily unavailable");

        assertFalse(result.handled());
        assertEquals(ProtocolStatusCode.REJECTED, result.statusCode());
        assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, result.rejectionCode());
        assertEquals(503, result.transportCode());
        assertEquals("RESOURCE_READ_UNAVAILABLE", result.code());
        assertEquals(503, result.structured().get("transportCode"));
        assertEquals("later", result.structured().get("retryability"));
        assertEquals("retry_later", result.structured().get("recoveryAction"));
    }

    @Test
    void legacyStringsUseExplicitCompatibilityMappingAndUnknownFailsClosed() {
        ProtocolEnvelopeDispatchResult old = ProtocolEnvelopeDispatchResult.rejected(
            400, "PROTO.INVALID_CURSOR", "Resource cursor is invalid");

        assertEquals(ProtocolRejectionCode.INVALID_CURSOR, old.rejectionCode());
        assertEquals("PROTO.INVALID_CURSOR", old.code());
        assertEquals(ProtocolRejectionCode.UNKNOWN_STATUS,
            ProtocolEnvelopeDispatchResult.rejected(500, "future.status", "ignored").rejectionCode());
        assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
            ProtocolRejectionCode.fromResourceError("future.resource.failure"));
        assertThrows(IllegalArgumentException.class,
            () -> new ProtocolEnvelopeDispatchResult(ProtocolEnvelopeDispatchResult.Status.REJECTED, 400,
                ProtocolStatusCode.REJECTED, null, "invalid", null));
    }
}
