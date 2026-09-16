package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolEnvelope;

import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface ProtocolOptionQueryAuthorizer {
    boolean authorize(ConnectionInfo connection, Session session, ProtocolEnvelope<Map<String, Object>> envelope,
                      OptionQuery query);

    static ProtocolOptionQueryAuthorizer serverGranted() {
        return (connection, session, envelope, query) -> connection != null && session != null
            && session.getConnection() == connection && ProtocolRequestAuthority.trustedClientId(connection, session) != null
            && connection.hasProtocolResourceAccess();
    }

    static ProtocolOptionQueryAuthorizer denyAll() {
        return (connection, session, envelope, query) -> false;
    }

    static ProtocolOptionQueryAuthorizer require(ProtocolOptionQueryAuthorizer authorizer) {
        return Objects.requireNonNull(authorizer, "Option query authorizer is required");
    }
}
