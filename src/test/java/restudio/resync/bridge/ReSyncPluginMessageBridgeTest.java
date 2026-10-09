package restudio.resync.bridge;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.ReSync;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.MessageType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPluginMessageBridgeTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void fragmentedHelloIsRejectedBeforeAdmissionAndPermittedHandshakeStillSucceeds() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        UUID sessionId = UUID.randomUUID();
        byte[] fragmented = new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, ReSyncBridgeEnvelope.HELLO,
            sessionId, 1, 0, 2, new byte[ReSyncBridgeChunker.CHUNK_SIZE]).encode();

        bridge.onPluginMessageReceived(ReSyncPluginMessageBridge.CHANNEL, player, fragmented);
        assertEquals(0, authPayload(player.messages.getLast()).get());
        player.setOp(true);
        bridge.onPluginMessageReceived(ReSyncPluginMessageBridge.CHANNEL, player, fragmented);
        assertEquals(0, authPayload(player.messages.getLast()).get());

        ByteBuffer hello = ByteBuffer.allocate(12).putInt(1).putInt(0).putInt(0);
        bridge.onPluginMessageReceived(ReSyncPluginMessageBridge.CHANNEL, player,
            new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, ReSyncBridgeEnvelope.HELLO,
                sessionId, 2, 0, 1, hello.array()).encode());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, authPayload(player.messages.getLast()).get());
    }

    @Test
    void workerFramesWaitForMainThreadDrainAndKeepWireOrder() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        Object session = newSession(player);
        Method enqueue = method("enqueueOutbound", session.getClass(), long.class, byte.class, byte[].class);

        Thread worker = new Thread(() -> {
            invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{1});
            invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{2});
        });
        worker.start();
        worker.join();

        assertEquals(2, queue(session).size());
        assertTrue(player.messages.isEmpty());
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(2, player.messages.size());
        ReSyncBridgeEnvelope first = ReSyncBridgeEnvelope.decode(player.messages.get(0));
        ReSyncBridgeEnvelope second = ReSyncBridgeEnvelope.decode(player.messages.get(1));
        assertEquals(ReSyncBridgeEnvelope.DATA, first.type());
        assertEquals(ReSyncBridgeEnvelope.DATA, second.type());
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
    }

    @Test
    void closeGenerationDropsQueuedDataAndCompletesOnceOnMainThread() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        Object session = newSession(player);
        Method enqueue = method("enqueueOutbound", session.getClass(), long.class, byte.class, byte[].class);
        Method requestClose = method("requestClose", session.getClass(), long.class, String.class, boolean.class);
        Method close = method("close", session.getClass());

        assertTrue((boolean) invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{7}));
        invoke(requestClose, bridge, session, 1L, "timeout", true);
        assertFalse((boolean) invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{8}));
        invoke(close, bridge, session);
        invoke(close, bridge, session);
        MockBukkit.getMock().getScheduler().performOneTick();

        assertTrue(queue(session).isEmpty());
        assertEquals(0, player.messages.size());
        assertEquals("CLOSED", state(session));
    }

    @Test
    void bridgeVersionMatrixAcceptsLegacyAndRequiresIdentityForV2() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer legacyPlayer = new RecordingPlayer(MockBukkit.getMock(), "legacy");
        RecordingPlayer currentPlayer = new RecordingPlayer(MockBukkit.getMock(), "current");
        MockBukkit.getMock().addPlayer(legacyPlayer);
        MockBukkit.getMock().addPlayer(currentPlayer);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        Object legacy = newSession(legacyPlayer);
        Object current = newSession(currentPlayer);
        setField(legacy, "bridgeProtocolVersion", 1);
        Method sendAuth = method("sendAuth", legacy.getClass(), boolean.class, String.class);

        assertTrue((boolean) invoke(sendAuth, bridge, legacy, true, "Legacy"));
        assertFalse((boolean) invoke(sendAuth, bridge, current, true, "Current"));
        MockBukkit.getMock().getScheduler().performOneTick();

        ByteBuffer legacyPayload = authPayload(legacyPlayer.messages.get(0));
        ByteBuffer currentPayload = authPayload(currentPlayer.messages.get(0));
        assertEquals(1, legacyPayload.get());
        assertEquals(0, currentPayload.get());
        assertTrue(isSupported(bridge, 1));
        assertTrue(isSupported(bridge, 2));
        assertFalse(isSupported(bridge, 3));
    }

    @Test
    void blockedOpenCanBeClosedConcurrentlyAndStaleConnectionIsClosedOnce() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        BlockingBridge bridge = new BlockingBridge(plugin);
        Object session = newSession(player);
        Method ensureConnection = method("ensureConnection", session.getClass());
        Method close = method("close", session.getClass());

        Thread opener = new Thread(() -> invoke(ensureConnection, bridge, session));
        opener.start();
        assertTrue(bridge.openEntered.await(1, TimeUnit.SECONDS));

        AtomicBoolean closeFinished = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            invoke(close, bridge, session);
            closeFinished.set(true);
        });
        closer.start();
        try {
            assertTrue(waitFor(closeFinished, 1, TimeUnit.SECONDS));
        } finally {
            bridge.releaseOpen.countDown();
        }
        opener.join();
        closer.join();
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(1, bridge.closedConnections.get());
        assertEquals("CLOSED", state(session));
        assertTrue(queue(session).isEmpty());
    }

    @Test
    void blockedPlayerDispatchReleasesActivationFenceAndDoesNotAllowPostCloseOutput() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        DispatchBridge bridge = new DispatchBridge(plugin);
        Object session = newSession(player);
        Method enqueue = method("enqueueOutbound", session.getClass(), long.class, byte.class, byte[].class);
        Method close = method("close", session.getClass());

        player.onSend = () -> {
            Thread closer = new Thread(() -> {
                invoke(close, bridge, session);
                bridge.closeFinished.set(true);
            });
            closer.start();
            assertTrue(waitFor(bridge.closeFinished, 1, TimeUnit.SECONDS));
            try {
                closer.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        setField(session, "connection", connection());
        assertTrue((boolean) invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{4}));
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, player.messages.size());
        assertEquals(1, bridge.closedConnections.get());
        assertFalse((boolean) invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{5}));
        invoke(close, bridge, session);
        assertEquals(1, player.messages.size());
        assertEquals(1, bridge.closedConnections.get());
    }

    @Test
    void saturatedCatalogOutputBackpressuresWithoutClosingTheFirstConnection() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        AdmissionBridge bridge = new AdmissionBridge(plugin);
        Object session = newSession(player);
        invoke(method("ensureConnection", session.getClass()), bridge, session);
        assertNotNull(bridge.sender);
        byte[] frame = new byte[ReSyncBridgeChunker.CHUNK_SIZE];
        frame[1] = MessageType.DATA.getValue();
        int frames = 11 * 1024 * 1024 / frame.length;
        int backpressureEvents = 0;

        for (int index = 0; index < frames; index++) {
            FrameSender.SendResult result = bridge.sender.trySend(frame);
            if (result == FrameSender.SendResult.BACKPRESSURED) {
                backpressureEvents++;
                MockBukkit.getMock().getScheduler().performOneTick();
                result = bridge.sender.trySend(frame);
            }
            assertEquals(FrameSender.SendResult.ACCEPTED, result);
            assertEquals("OPEN", state(session));
        }
        drainTicks(session, 32);

        assertTrue(backpressureEvents > 0);
        assertEquals("OPEN", state(session));
        assertTrue(queue(session).isEmpty());
        assertEquals(0, bridge.closedConnections.get());
    }

    @Test
    void protocolFrameBudgetUsesTheCodecLimitNotThePluginChunkSize() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        AdmissionBridge bridge = new AdmissionBridge(plugin);
        Object session = newSession(player);
        invoke(method("ensureConnection", session.getClass()), bridge, session);
        assertNotNull(bridge.sender);
        assertEquals(Codec.DEFAULT_MAX_ENCODED_FRAME_BYTES, bridge.sender.getMaxEncodedFrameBytes());

        byte[] frame = new byte[256 * 1024];
        frame[1] = MessageType.DATA.getValue();
        assertEquals(FrameSender.SendResult.ACCEPTED, bridge.sender.trySend(frame));
        drainTicks(session, 32);
        assertEquals("OPEN", state(session));
        assertTrue(queue(session).isEmpty());
        assertTrue(player.messages.size() > 1);
        assertEquals(0, bridge.closedConnections.get());
    }

    @Test
    void mainThreadDrainIsBoundedAndFairAcrossSessions() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer firstPlayer = new RecordingPlayer(MockBukkit.getMock(), "first");
        RecordingPlayer secondPlayer = new RecordingPlayer(MockBukkit.getMock(), "second");
        MockBukkit.getMock().addPlayer(firstPlayer);
        MockBukkit.getMock().addPlayer(secondPlayer);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        Object first = newSession(firstPlayer);
        Object second = newSession(secondPlayer);
        Method enqueue = method("enqueueOutbound", first.getClass(), long.class, byte.class, byte[].class);

        for (int index = 0; index < 20; index++) {
            assertTrue((boolean) invoke(enqueue, bridge, first, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{1}));
            assertTrue((boolean) invoke(enqueue, bridge, second, 1L, ReSyncBridgeEnvelope.DATA, new byte[]{2}));
        }
        MockBukkit.getMock().getScheduler().performOneTick();

        int firstMessages = firstPlayer.messages.size();
        int secondMessages = secondPlayer.messages.size();
        int firstDrain = firstMessages + secondMessages;
        assertTrue(firstDrain > 0 && firstDrain <= 24);
        assertTrue(firstMessages > 0);
        assertTrue(secondMessages > 0);
        assertTrue(Math.abs(firstMessages - secondMessages) <= 1);
        assertFalse(queue(first).isEmpty());
        assertFalse(queue(second).isEmpty());
        drainTicks(first, 8);
        drainTicks(second, 8);
        assertTrue(queue(first).isEmpty());
        assertTrue(queue(second).isEmpty());
    }

    @Test
    void mainThreadDrainDoesNotCrossTheByteBudget() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        ReSyncPluginMessageBridge bridge = new ReSyncPluginMessageBridge(plugin);
        Object session = newSession(player);
        Method enqueue = method("enqueueOutbound", session.getClass(), long.class, byte.class, byte[].class);
        byte[] payload = new byte[ReSyncBridgeChunker.CHUNK_SIZE];

        for (int index = 0; index < 20; index++) {
            assertTrue((boolean) invoke(enqueue, bridge, session, 1L, ReSyncBridgeEnvelope.DATA, payload));
        }
        MockBukkit.getMock().getScheduler().performOneTick();

        long deliveredBytes = player.messages.stream().mapToLong(message -> message.length).sum();
        assertTrue(deliveredBytes > 0L && deliveredBytes <= 384L * 1024L);
        assertFalse(queue(session).isEmpty());
    }

    @Test
    void bulkCatalogBackpressurePreservesProtocolQueueCapacity() throws Exception {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        RecordingPlayer player = new RecordingPlayer(MockBukkit.getMock());
        MockBukkit.getMock().addPlayer(player);
        AdmissionBridge bridge = new AdmissionBridge(plugin);
        Object session = newSession(player);
        invoke(method("ensureConnection", session.getClass()), bridge, session);
        byte[] bulkFrame = new byte[ReSyncBridgeChunker.CHUNK_SIZE];
        bulkFrame[1] = MessageType.DATA.getValue();
        FrameSender.SendResult bulkResult;
        do {
            bulkResult = bridge.sender.trySend(bulkFrame);
        } while (bulkResult == FrameSender.SendResult.ACCEPTED);

        byte[] protocolFrame = new byte[12];
        protocolFrame[1] = MessageType.PROTOCOL_ENVELOPE.getValue();
        assertEquals(FrameSender.SendResult.BACKPRESSURED, bulkResult);
        assertEquals(FrameSender.SendResult.ACCEPTED, bridge.sender.trySend(protocolFrame));
        assertEquals("OPEN", state(session));
    }

    private static boolean isSupported(ReSyncPluginMessageBridge bridge, int version) throws Exception {
        Method method = ReSyncPluginMessageBridge.class.getDeclaredMethod("isSupportedBridgeProtocol", int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(bridge, version);
    }

    private static ByteBuffer authPayload(byte[] message) {
        ReSyncBridgeEnvelope envelope = ReSyncBridgeEnvelope.decode(message);
        return ByteBuffer.wrap(envelope.payload());
    }

    private static Object newSession(PlayerMock player) throws Exception {
        Class<?> type = Class.forName("restudio.resync.bridge.ReSyncPluginMessageBridge$BridgeSession");
        Constructor<?> constructor = type.getDeclaredConstructor(UUID.class, org.bukkit.entity.Player.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UUID.randomUUID(), player);
    }

    private static Method method(String name, Class<?> sessionType, Class<?>... parameterTypes) throws Exception {
        Class<?>[] allParameterTypes = new Class<?>[parameterTypes.length + 1];
        allParameterTypes[0] = sessionType;
        System.arraycopy(parameterTypes, 0, allParameterTypes, 1, parameterTypes.length);
        Method method = ReSyncPluginMessageBridge.class.getDeclaredMethod(name, allParameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Object> queue(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("outbound");
        field.setAccessible(true);
        return (ArrayDeque<Object>) field.get(session);
    }

    private static String state(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("state");
        field.setAccessible(true);
        return field.get(session).toString();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ConnectionInfo connection() {
        return new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
    }

    private static boolean waitFor(AtomicBoolean value, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        return value.get();
    }

    private static void drainTicks(Object session, int limit) throws Exception {
        for (int tick = 0; tick < limit && !queue(session).isEmpty(); tick++) {
            MockBukkit.getMock().getScheduler().performOneTick();
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }

    private static final class RecordingPlayer extends PlayerMock {
        private final List<byte[]> messages = new ArrayList<>();
        private Runnable onSend;

        private RecordingPlayer(ServerMock server) {
            this(server, "bridge");
        }

        private RecordingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void sendPluginMessage(Plugin source, String channel, byte[] message) {
            messages.add(message.clone());
            if (onSend != null) {
                onSend.run();
            }
        }
    }

    private static class BlockingBridge extends ReSyncPluginMessageBridge {
        private final CountDownLatch openEntered = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);
        private final AtomicInteger closedConnections = new AtomicInteger();

        private BlockingBridge(ReSync plugin) {
            super(plugin);
        }

        @Override
        protected ConnectionInfo openBridgeConnection(FrameSender sender) {
            openEntered.countDown();
            try {
                releaseOpen.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return connection();
        }

        @Override
        protected void closeBridgeConnection(ConnectionInfo connection) {
            closedConnections.incrementAndGet();
        }
    }

    private static final class DispatchBridge extends ReSyncPluginMessageBridge {
        private final AtomicInteger closedConnections = new AtomicInteger();
        private final AtomicBoolean closeFinished = new AtomicBoolean();

        private DispatchBridge(ReSync plugin) {
            super(plugin);
        }

        @Override
        protected void closeBridgeConnection(ConnectionInfo connection) {
            closedConnections.incrementAndGet();
        }
    }

    private static final class AdmissionBridge extends ReSyncPluginMessageBridge {
        private final AtomicInteger closedConnections = new AtomicInteger();
        private FrameSender sender;

        private AdmissionBridge(ReSync plugin) {
            super(plugin);
        }

        @Override
        protected ConnectionInfo openBridgeConnection(FrameSender sender) {
            this.sender = sender;
            return new ConnectionInfo(null, sender, 1);
        }

        @Override
        protected void closeBridgeConnection(ConnectionInfo connection) {
            closedConnections.incrementAndGet();
        }
    }
}
