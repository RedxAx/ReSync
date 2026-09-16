package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.ProtocolEnvelope;

import java.util.Map;

@FunctionalInterface
public interface ProtocolEnvelopeHandler {
    ProtocolEnvelopeDispatchResult handle(ConnectionInfo connection, Session session,
                                          ProtocolEnvelope<Map<String, Object>> envelope);

    default boolean supports(ProtocolEnvelope<Map<String, Object>> envelope) {
        return envelope.kind() == ProtocolEnvelope.Kind.REQUEST;
    }

    default boolean authorize(ConnectionInfo connection, Session session,
                              ProtocolEnvelope<Map<String, Object>> envelope) {
        return true;
    }
}
