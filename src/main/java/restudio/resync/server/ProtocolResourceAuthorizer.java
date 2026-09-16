package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceOperation;

import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface ProtocolResourceAuthorizer {
    boolean authorize(ConnectionInfo connection, Session session,
                      ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation);

    static ProtocolResourceAuthorizer serverGranted() {
        return (connection, session, envelope, operation) -> connection != null && connection.hasProtocolResourceAccess()
            && ProtocolRequestAuthority.trustedClientId(connection, session) != null;
    }

    static ProtocolResourceAuthorizer denyAll() {
        return (connection, session, envelope, operation) -> false;
    }

    static ProtocolResourceAuthorizer require(ProtocolResourceAuthorizer authorizer) {
        return Objects.requireNonNull(authorizer, "Protocol resource authorizer is required");
    }
}
