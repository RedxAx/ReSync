package restudio.resync.server;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.core.SessionManager;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.protocol.OptionInvalidation;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.FrameHeader;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.queue.RateLimiter;
import restudio.resync.security.ClientIdentity;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncServerProtocolDeliveryTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("92929292-9292-4292-9292-929292929292"));
    private static final ContractRef<InspectorFieldId> SOURCE = ContractRef.of(
        new OwnerId("restudio.resync"), new InspectorFieldId("server.resync.test"));
    private static final ContractRef<CapabilityId> QUERY = ContractRef.of(
        new OwnerId("restudio.resync"), new CapabilityId("options.test"));

    @Test
    void preparedResponseRechecksEverySessionAndAuthorityFenceBeforeInitialSend() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
            fixture -> fixture.sessions.removeSession(fixture.connection),
            fixture -> fixture.connection.setState(ConnectionState.CLOSING),
            fixture -> fixture.connection.setProtocolResourceAccess(false),
            fixture -> fixture.connection.setNegotiatedFlowCapabilities(Set.of()),
            fixture -> fixture.connection.setClientId("other"),
            fixture -> fixture.epoch.incrementAndGet()
        );

        for (Consumer<Fixture> mutation : mutations) {
            RecordingSender sender = new RecordingSender();
            try (Fixture fixture = new Fixture(sender)) {
                Prepared prepared = fixture.prepare(optionPage(fixture.epoch.get()));
                mutation.accept(fixture);
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                    (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(0, sender.attempts.get());
            }
        }
    }

    @Test
    void preparedResponseRechecksEverySessionAndAuthorityFenceBeforeBackpressureRetry() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
            fixture -> fixture.sessions.removeSession(fixture.connection),
            fixture -> fixture.connection.setState(ConnectionState.CLOSING),
            fixture -> fixture.connection.setProtocolResourceAccess(false),
            fixture -> fixture.connection.setNegotiatedFlowCapabilities(Set.of()),
            fixture -> fixture.connection.setClientId("other"),
            fixture -> fixture.epoch.incrementAndGet()
        );

        for (Consumer<Fixture> mutation : mutations) {
            BackpressuredSender sender = new BackpressuredSender();
            try (Fixture fixture = new Fixture(sender)) {
                Prepared prepared = fixture.prepare(optionPage(fixture.epoch.get()));
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                    (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
                assertTrue(sender.firstAttempt.await(2, TimeUnit.SECONDS));
                mutation.accept(fixture);
                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(1, sender.attempts.get());
            }
        }
    }

    @Test
    void encodedInvalidationClosureChecksTheFullEventFenceBeforeInitialSendAndRetry() throws Exception {
        for (int changed = 0; changed < 4; changed++) {
            RecordingSender sender = new RecordingSender();
            try (Fixture fixture = new Fixture(sender)) {
                boolean[] current = {true, true, true, true};
                CountDownLatch blockerEntered = new CountDownLatch(1);
                CountDownLatch releaseBlocker = new CountDownLatch(1);
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, new byte[]{1},
                    fixture::currentSession, () -> true, () -> {
                        blockerEntered.countDown();
                        await(releaseBlocker);
                        return FrameSender.SendResult.ACCEPTED;
                }).accepted());
                assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));

                ProtocolEnvelopeMailbox.EventFence fullFence =
                    () -> current[0] && current[1] && current[2] && current[3];
                Prepared prepared = fixture.prepare(invalidation(fixture.epoch.get()), fullFence);
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                    (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
                current[changed] = false;
                releaseBlocker.countDown();
                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(0, sender.attempts.get());

                fixture.mailbox.retire(fixture.connection);
                assertFalse(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                    fixture::currentSession, () -> true, prepared.delivery()).accepted());
            }

            BackpressuredSender retrySender = new BackpressuredSender();
            try (Fixture fixture = new Fixture(retrySender)) {
                boolean[] current = {true, true, true, true};
                ProtocolEnvelopeMailbox.EventFence fullFence =
                    () -> current[0] && current[1] && current[2] && current[3];
                Prepared prepared = fixture.prepare(invalidation(fixture.epoch.get()), fullFence);
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                    (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
                assertTrue(retrySender.firstAttempt.await(2, TimeUnit.SECONDS));
                current[changed] = false;
                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(1, retrySender.attempts.get());
            }
        }
    }

    @Test
    void nullResponseTransportErrorRetainsItsCapturedAuthorityEpoch() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            Prepared prepared = fixture.prepare(ProtocolEnvelopeDispatchResult.rejected(
                ProtocolRejectionCode.INVALID_PAYLOAD, "invalid"), () -> true);
            fixture.epoch.incrementAndGet();
            assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
            fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(0, sender.attempts.get());
        }
    }

    @Test
    void nullResponseTransportErrorSendsWhileItsCapturedAuthorityRemainsCurrent() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            Prepared prepared = fixture.prepare(ProtocolEnvelopeDispatchResult.rejected(
                ProtocolRejectionCode.INVALID_PAYLOAD, "invalid"), () -> true);
            assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, prepared.frame(),
                (candidate, candidateSession) -> true, () -> true, prepared.delivery()).accepted());
            fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, sender.attempts.get());
        }
    }

    @Test
    void malformedBridgeProtocolIngressUsesTheFencedMailboxErrorPath() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            fixture.server.onBridgeMessage(fixture.connection, allowedPlayer(), protocolFrame());

            fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, sender.attempts.get());
            ErrorMessage error = (ErrorMessage) fixture.codec.decodePayload(
                fixture.codec.decodeFrame(sender.lastFrame.get()));
            assertEquals(400, error.getErrorCode());
        }
    }

    @Test
    void rateLimitProtocolErrorRechecksCapturedSessionAndAuthorityBeforeInitialSend() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
            fixture -> fixture.sessions.removeSession(fixture.connection),
            fixture -> fixture.epoch.incrementAndGet()
        );

        for (Consumer<Fixture> mutation : mutations) {
            RecordingSender sender = new RecordingSender();
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(sender)) {
                assertTrue(fixture.mailbox.admitOutbound(fixture.connection, fixture.session, new byte[]{1},
                    fixture::currentSession, () -> true, () -> {
                        blockerEntered.countDown();
                        await(releaseBlocker);
                        return FrameSender.SendResult.ACCEPTED;
                    }).accepted());
                assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));
                fixture.exhaustGlobalRateLimit();
                fixture.server.onBridgeMessage(fixture.connection, allowedPlayer(), protocolFrame());
                mutation.accept(fixture);
                releaseBlocker.countDown();

                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(0, sender.attempts.get());
            } finally {
                releaseBlocker.countDown();
            }
        }
    }

    @Test
    void backpressuredRateLimitProtocolErrorStopsAfterCapturedIngressRollover() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
            fixture -> fixture.sessions.removeSession(fixture.connection),
            fixture -> fixture.epoch.incrementAndGet()
        );

        for (Consumer<Fixture> mutation : mutations) {
            BlockingBackpressuredSender sender = new BlockingBackpressuredSender();
            try (Fixture fixture = new Fixture(sender)) {
                fixture.exhaustGlobalRateLimit();
                fixture.server.onBridgeMessage(fixture.connection, allowedPlayer(), protocolFrame());
                assertTrue(sender.firstAttempt.await(2, TimeUnit.SECONDS));
                mutation.accept(fixture);
                sender.releaseFirst.countDown();

                fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
                assertEquals(1, sender.attempts.get());
            } finally {
                sender.releaseFirst.countDown();
            }
        }
    }

    @Test
    void rateLimitProtocolErrorRetainsItsTransportStatusOnInitialSend() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            fixture.exhaustGlobalRateLimit();
            fixture.server.onBridgeMessage(fixture.connection, allowedPlayer(), protocolFrame());

            fixture.mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, sender.attempts.get());
            ErrorMessage error = (ErrorMessage) fixture.codec.decodePayload(
                fixture.codec.decodeFrame(sender.lastFrame.get()));
            assertEquals(429, error.getErrorCode());
        }
    }

    private static ProtocolEnvelope<Map<String, Object>> optionPage(long epoch) {
        OptionPage page = new OptionPage(SOURCE, QUERY, 1L, "page:1", List.of(), null,
            true, List.of());
        return envelope(ProtocolEnvelope.Kind.RESPONSE, epoch, new ProtocolBody.OptionPageResponse(page));
    }

    private static ProtocolEnvelope<Map<String, Object>> invalidation(long epoch) {
        OptionInvalidation invalidation = new OptionInvalidation(SOURCE, QUERY, SERVER,
            null, 2L, "page:2", Set.of());
        return envelope(ProtocolEnvelope.Kind.EVENT, epoch, new ProtocolBody.OptionInvalidationEvent(invalidation));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ProtocolEnvelope.Kind kind, long epoch,
                                                                   ProtocolBody body) {
        UUID correlation = UUID.randomUUID();
        return new ProtocolEnvelope<>(kind, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(),
            kind == ProtocolEnvelope.Kind.RESPONSE ? UUID.randomUUID() : null, correlation, correlation, SERVER, null,
            0L, epoch, null, OptionQueryAuthority.OPERATION, Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY),
            OptionQueryAuthority.PAGE_TYPE, null, null, false, new CatalogVersion(1, 3), null, null, null, null, 1L,
            ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), body);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] protocolFrame() {
        FrameHeader header = new FrameHeader();
        header.setMessageType(MessageType.PROTOCOL_ENVELOPE);
        header.setPayloadLength(0);
        return header.toBytes();
    }

    private static Player allowedPlayer() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOnline", "isOp", "hasPermission" -> true;
                case "getUniqueId" -> UUID.fromString("83838383-8383-4383-8383-838383838383");
                case "toString" -> "ProtocolDeliveryPlayer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static ReSyncServer allocate() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ReSyncServer) ((Unsafe) field.get(null)).allocateInstance(ReSyncServer.class);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = ReSyncServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Prepared(byte[] frame, ProtocolEnvelopeMailbox.PendingDelivery delivery) {
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicLong epoch = new AtomicLong(7L);
        private final CompressionPool compression = new CompressionPool(1, 1);
        private final Codec codec = new Codec(compression);
        private final MemoryMonitor memory = new MemoryMonitor(0.01);
        private final SessionManager sessions = new SessionManager(memory, 60, 1_048_576);
        private final ConnectionInfo connection;
        private final Session session;
        private final ReSyncServer server;
        private final ProtocolEnvelopeMailbox mailbox;
        private final RateLimiter rateLimiter = new RateLimiter(1, 1, 1000);

        private Fixture(FrameSender sender) throws Exception {
            connection = new ConnectionInfo(null, sender, 1);
            connection.setClientId("test");
            connection.setClientVersion("test");
            session = sessions.createSession(connection, new ClientIdentity("test", "test"));
            connection.setState(ConnectionState.AUTHENTICATED);
            connection.setProtocolResourceAccess(true);
            connection.setNegotiatedFlowCapabilities(Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value()));
            server = allocate();
            set(server, "codec", codec);
            set(server, "sessionManager", sessions);
            set(server, "authorityEpoch", new AuthorityEpoch(epoch::get));
            ReSyncConfig config = new ReSyncConfig();
            ReSyncConfig.QueueConfig queue = new ReSyncConfig.QueueConfig();
            queue.setMaxGlobalRequests(1);
            queue.setMaxRequestsPerClient(1);
            config.setQueue(queue);
            set(server, "config", config);
            set(server, "rateLimiter", rateLimiter);
            ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
                new AuthorityEpoch(epoch::get), (ignoredConnection, ignoredSession, ignoredEnvelope) ->
                    ProtocolEnvelopeDispatchResult.accepted());
            mailbox = new ProtocolEnvelopeMailbox(boundary,
                ProtocolEnvelopeMailbox.Limits.standard(2, 8, Codec.DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES));
            set(server, "protocolEnvelopeMailbox", mailbox);
            mailbox.activate(connection, session);
        }

        private boolean currentSession(ConnectionInfo candidate, Session candidateSession) {
            return candidate == connection && candidateSession == session && sessions.getSession(connection) == session;
        }

        private Prepared prepare(ProtocolEnvelope<Map<String, Object>> envelope) throws Exception {
            return prepare(ProtocolEnvelopeDispatchResult.handled(envelope), () -> true);
        }

        private Prepared prepare(ProtocolEnvelope<Map<String, Object>> envelope,
                                 ProtocolEnvelopeMailbox.EventFence eventFence) throws Exception {
            return prepare(ProtocolEnvelopeDispatchResult.handled(envelope), eventFence);
        }

        private Prepared prepare(ProtocolEnvelopeDispatchResult result,
                                 ProtocolEnvelopeMailbox.EventFence eventFence) throws Exception {
            Method method = ReSyncServer.class.getDeclaredMethod("prepareProtocolDeliveryFrame", ConnectionInfo.class,
                Session.class, ProtocolEnvelopeDispatchResult.class, long.class,
                ProtocolEnvelopeMailbox.EventFence.class);
            method.setAccessible(true);
            Object encoded = method.invoke(server, connection, session, result, epoch.get(), eventFence);
            Method frame = encoded.getClass().getDeclaredMethod("frame");
            Method delivery = encoded.getClass().getDeclaredMethod("delivery");
            frame.setAccessible(true);
            delivery.setAccessible(true);
            return new Prepared((byte[]) frame.invoke(encoded),
                (ProtocolEnvelopeMailbox.PendingDelivery) delivery.invoke(encoded));
        }

        private void exhaustGlobalRateLimit() {
            assertTrue(rateLimiter.tryConsume("global", 1, 1, 1, TimeUnit.DAYS.toMillis(1)));
        }

        @Override
        public void close() {
            mailbox.shutdown();
            sessions.shutdown();
            memory.shutdown();
            compression.close();
        }
    }

    private static final class BackpressuredSender implements FrameSender {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstAttempt = new CountDownLatch(1);

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public SendResult trySend(byte[] frame) {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            return SendResult.BACKPRESSURED;
        }

        @Override
        public void close(int code, String reason) {
        }
    }

    private static final class BlockingBackpressuredSender implements FrameSender {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstAttempt = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public SendResult trySend(byte[] frame) {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            await(releaseFirst);
            return SendResult.BACKPRESSURED;
        }

        @Override
        public void close(int code, String reason) {
        }
    }

    private static final class RecordingSender implements FrameSender {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<byte[]> lastFrame = new AtomicReference<>();

        @Override
        public void send(byte[] frame) {
            attempts.incrementAndGet();
            lastFrame.set(Arrays.copyOf(frame, frame.length));
        }

        @Override
        public void close(int code, String reason) {
        }
    }
}
