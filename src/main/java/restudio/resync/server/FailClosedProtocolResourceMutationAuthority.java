package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceOperation;

import java.util.Map;

public final class FailClosedProtocolResourceMutationAuthority implements ProtocolResourceMutationAuthority {
    public static final String ERROR_CODE = ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE.wireValue();
    public static final String MESSAGE = "Resource mutations are unavailable until a durable resource authority is configured";

    @Override
    public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                  ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation) {
        return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE, MESSAGE);
    }

    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public String unsupportedOperationReason(ResourceOperation operation) {
        return MESSAGE;
    }
}
