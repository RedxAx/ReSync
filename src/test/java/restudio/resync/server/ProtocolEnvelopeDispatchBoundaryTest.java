package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.protocol.ProtocolEnvelopeBoundary;
import restudio.resync.protocol.FrameSender;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEnvelopeDispatchBoundaryTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MESSAGE_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void dispatchesDecodedRequestThroughInjectedHandler() {
        ConnectionInfo connection = authenticatedConnection();
        Session session = new Session("session", "client", connection);
        AtomicReference<ProtocolEnvelope<Map<String, Object>>> received = new AtomicReference<>();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, envelope) -> {
            received.set(envelope);
            return ProtocolEnvelopeDispatchResult.accepted();
        });

        ProtocolEnvelopeDispatchResult result = boundary.dispatch(connection, session, encodedRequest());

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelopeDispatchResult.Status.HANDLED, result.status());
        assertNotNull(received.get());
        assertEquals(ProtocolEnvelope.Kind.REQUEST, received.get().kind());
        assertEquals("control.test", received.get().operation().id().value());
    }

    @Test
    void stampsExternalHandlerResponsesWithCurrentAuthorityEpoch() {
        ConnectionInfo connection = authenticatedConnection();
        Session session = new Session("session", "client", connection);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            new AuthorityEpoch(() -> 9L),
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.handled(response(0L)));

        ProtocolEnvelopeDispatchResult result = boundary.dispatch(connection, session, encodedRequest());

        assertTrue(result.handled());
        assertNotNull(result.response());
        assertEquals(9L, result.response().authorityEpoch());
    }

    @Test
    void rejectsMalformedUnauthenticatedAndUnsupportedRequestsStructurally() {
        ConnectionInfo connection = new ConnectionInfo(null, sender(), 1);
        Session session = new Session("session", "client", connection);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, envelope) -> ProtocolEnvelopeDispatchResult.accepted());

        ProtocolEnvelopeDispatchResult malformed = boundary.dispatch(connection, session, new byte[]{'{', '}'});
        ProtocolEnvelopeDispatchResult unauthorized = boundary.dispatch(connection, session, encodedRequest());

        ProtocolEnvelopeDispatchBoundary unsupportedBoundary = new ProtocolEnvelopeDispatchBoundary(null);
        connection.setState(ConnectionState.AUTHENTICATED);
        ProtocolEnvelopeDispatchResult unsupported = unsupportedBoundary.dispatch(connection, session, encodedRequest());

        assertRejected(malformed, 400, "PROTO.INVALID_PAYLOAD");
        assertRejected(unauthorized, 401, "RUNTIME.AUTHORIZATION_DENIED");
        assertRejected(unsupported, 501, "PROTO.UNSUPPORTED_GENERATION");
        assertTrue(boundary.isSupported());
        assertFalse(unsupportedBoundary.isSupported());
    }

    @Test
    void rejectsUnauthorizedHandlerWithoutInvokingIt() {
        ConnectionInfo connection = authenticatedConnection();
        Session session = new Session("session", "client", connection);
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        ProtocolEnvelopeHandler handler = new ProtocolEnvelopeHandler() {
            @Override
            public ProtocolEnvelopeDispatchResult handle(ConnectionInfo ignoredConnection, Session ignoredSession,
                                                         ProtocolEnvelope<Map<String, Object>> ignoredEnvelope) {
                invoked.set(true);
                return ProtocolEnvelopeDispatchResult.accepted();
            }

            @Override
            public boolean authorize(ConnectionInfo ignoredConnection, Session ignoredSession,
                                     ProtocolEnvelope<Map<String, Object>> ignoredEnvelope) {
                return false;
            }
        };

        ProtocolEnvelopeDispatchResult result = new ProtocolEnvelopeDispatchBoundary(handler)
            .dispatch(connection, session, encodedRequest());

        assertRejected(result, 403, "RUNTIME.AUTHORIZATION_DENIED");
        assertFalse(invoked.get());
    }

    @Test
    void rejectsAuthenticatedSessionBoundToAnotherConnection() {
        ConnectionInfo connection = authenticatedConnection();
        ConnectionInfo otherConnection = authenticatedConnection();
        Session session = new Session("session", "client", otherConnection);
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        ProtocolEnvelopeHandler handler = (ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            invoked.set(true);
            return ProtocolEnvelopeDispatchResult.accepted();
        };

        ProtocolEnvelopeDispatchResult result = new ProtocolEnvelopeDispatchBoundary(handler)
            .dispatch(connection, session, encodedRequest());

        assertRejected(result, 401, "RUNTIME.AUTHORIZATION_DENIED");
        assertFalse(invoked.get());
    }

    private static void assertRejected(ProtocolEnvelopeDispatchResult result, int transportCode, String code) {
        assertEquals(ProtocolEnvelopeDispatchResult.Status.REJECTED, result.status());
        assertEquals(transportCode, result.transportCode());
        assertEquals(code, result.code());
        assertEquals("rejected", result.structured().get("status"));
        assertEquals(code, result.structured().get("code"));
    }

    private static ConnectionInfo authenticatedConnection() {
        ConnectionInfo connection = new ConnectionInfo(null, sender(), 1);
        connection.setState(ConnectionState.AUTHENTICATED);
        return connection;
    }

    private static FrameSender sender() {
        return new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        };
    }

    private static byte[] encodedRequest() {
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            CORRELATION_UUID,
            new ServerId(SERVER_UUID),
            null,
            0,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("control.test")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("control.request")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ControlRequest("test", Map.of("value", true))
        );
        return new ProtocolEnvelopeBoundary().encode(envelope);
    }

    private static ProtocolEnvelope<Map<String, Object>> response(long authorityEpoch) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            CORRELATION_UUID,
            new ServerId(SERVER_UUID),
            null,
            0,
            authorityEpoch,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("control.test")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("control.response")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of(),
            new ProtocolBody.ControlResponse("test", Map.of("value", true))
        );
    }
}
