package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.ReSync;
import restudio.resync.bridge.ReSyncPluginMessageBridge;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ChannelMuxer;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionManager;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.core.SessionManager;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.ModuleMetadata;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.protocol.messages.HandshakeRequest;
import restudio.resync.protocol.messages.HandshakeResponse;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetIntegrityService;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncBridgeHandshakeAsyncTest {
    private static final Unsafe UNSAFE = unsafe();

    @TempDir
    Path temporary;

    private TestReSync plugin;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.loadSimple(TestReSync.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void realBridgeHandshakeReturnsBeforeDurabilityProbeAndPublishesOnlyOnMainContinuation() throws Exception {
        fixture = fixture("success");
        PlayerMock player = player();
        ConnectionInfo connection = fixture.openConnection();
        ScheduledExecutorService fallback = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> forcedRelease = fallback.schedule(fixture.storage::release, 3, TimeUnit.SECONDS);
        long started = System.nanoTime();
        long elapsed;
        try {
            invokeHandshake(fixture.server, connection, player, request());
            elapsed = System.nanoTime() - started;
        } finally {
            forcedRelease.cancel(false);
            fallback.shutdownNow();
        }
        assertTrue(elapsed < Duration.ofSeconds(1).toNanos());
        assertTrue(fixture.storage.awaitEntered());
        assertFalse(fixture.storage.probeOnMainThread.get());
        assertEquals(ConnectionState.CONNECTED, connection.getState());
        assertFalse(connection.hasProtocolResourceAccess());
        assertNull(fixture.sessions.getSession(connection));
        assertTrue(fixture.sender.frames.isEmpty());

        int pendingBeforeCompletion = Bukkit.getScheduler().getPendingTasks().size();
        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        waitFor(() -> Bukkit.getScheduler().getPendingTasks().size() > pendingBeforeCompletion);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        Object playerDataMonitor = playerDataMonitor(fixture.playerDataAdmission);
        ExecutorService monitorHolder = Executors.newSingleThreadExecutor();
        Future<?> held = monitorHolder.submit(() -> {
            synchronized (fixture.persistence) {
                synchronized (fixture.storage) {
                    synchronized (fixture.triggers) {
                        synchronized (playerDataMonitor) {
                            monitorHeld.countDown();
                            BlockingFlowStorage.await(releaseMonitor);
                        }
                    }
                }
            }
        });
        assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
        ScheduledExecutorService monitorFallback = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> forcedMonitorRelease = monitorFallback.schedule(releaseMonitor::countDown, 3, TimeUnit.SECONDS);
        long continuationStarted = System.nanoTime();
        long continuationElapsed;
        try {
            MockBukkit.getMock().getScheduler().performOneTick();
            continuationElapsed = System.nanoTime() - continuationStarted;
        } finally {
            releaseMonitor.countDown();
            forcedMonitorRelease.cancel(false);
            monitorFallback.shutdownNow();
            held.get(5, TimeUnit.SECONDS);
            monitorHolder.shutdownNow();
        }
        assertTrue(continuationElapsed < Duration.ofSeconds(1).toNanos());
        assertFalse(fixture.sender.frames.isEmpty());

        Codec.Frame frame = fixture.codec.decodeFrame(fixture.sender.frames.get(0));
        assertEquals(MessageType.HANDSHAKE_RESPONSE, frame.header.getMessageType());
        HandshakeResponse response = assertInstanceOf(HandshakeResponse.class, fixture.codec.decodePayload(frame));
        JsonObject capabilities = JsonParser.parseString(response.getCapabilitiesJson()).getAsJsonObject();
        JsonObject triggerState = capabilities.get(
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY).getAsJsonObject();
        assertTrue(response.isSuccess());
        assertEquals(1L, capabilities.get("authorityEpoch").getAsLong());
        assertEquals(fixture.triggers.bindingObservation().epoch(), triggerState.get(
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD).getAsLong());
        assertEquals(fixture.triggers.bindingObservation().hash(), triggerState.get(
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD).getAsString());
        assertTrue(capabilities.has("durabilityHealth"));
        assertTrue(capabilities.getAsJsonObject("paperPlayerDataAdmission").get("mutationAvailable").getAsBoolean());
        assertEquals(ConnectionState.AUTHENTICATED, connection.getState());
        assertTrue(connection.hasProtocolResourceAccess());
        assertTrue(connection.hasNegotiatedFlowCapability(
            ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY.id().value()));
        assertNotNull(fixture.sessions.getSession(connection));
        assertTrue(fixture.sessions.getSession(connection).getClientId().startsWith("bridge:" + player.getUniqueId()));
        assertEquals(-1, fixture.sender.closeCode);

        Session oldSession = fixture.sessions.getSession(connection);
        HandshakeRequest invalid = request();
        invalid.setProtocolVersion(1);
        invokeHandshake(fixture.server, connection, player, invalid);
        assertFalse(connection.hasProtocolResourceAccess());
        assertTrue(connection.getNegotiatedFlowCapabilities().isEmpty());
        assertFalse(fixture.mailbox.admitOutbound(connection, oldSession, new byte[]{1},
            (candidate, candidateSession) -> true, () -> true,
            () -> FrameSender.SendResult.ACCEPTED).accepted());
    }

    @Test
    void staleReadinessAndDisconnectCannotPublishPreparedHandshake() throws Exception {
        fixture = fixture("stale");
        PlayerMock player = player();
        ConnectionInfo stale = fixture.openConnection();
        invokeHandshake(fixture.server, stale, player, request());
        assertTrue(fixture.storage.awaitEntered());

        fixture.persistence.invalidateReadinessCertificate();
        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        await(() -> fixture.sender.closeCode != -1);

        assertEquals(1011, fixture.sender.closeCode);
        assertFalse(stale.hasProtocolResourceAccess());
        assertTrue(stale.getNegotiatedFlowCapabilities().isEmpty());
        assertNull(fixture.sessions.getSession(stale));

        fixture = replaceFixture("disconnect");
        player = player();
        ConnectionInfo disconnected = fixture.openConnection();
        invokeHandshake(fixture.server, disconnected, player, request());
        assertTrue(fixture.storage.awaitEntered());

        fixture.server.onBridgeClose(disconnected);
        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        Thread.sleep(25L);
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(ConnectionState.CLOSING, disconnected.getState());
        assertFalse(disconnected.hasProtocolResourceAccess());
        assertTrue(disconnected.getNegotiatedFlowCapabilities().isEmpty());
        assertNull(fixture.sessions.getSession(disconnected));
        assertTrue(fixture.sender.frames.isEmpty());
    }

    @Test
    void changedTriggerObservationCannotPublishPreparedHandshake() throws Exception {
        fixture = fixture("trigger-stale");
        PlayerMock player = player();
        ConnectionInfo connection = fixture.openConnection();
        invokeHandshake(fixture.server, connection, player, request());
        assertTrue(fixture.storage.awaitEntered());

        int pendingBeforeCompletion = Bukkit.getScheduler().getPendingTasks().size();
        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        waitFor(() -> Bukkit.getScheduler().getPendingTasks().size() > pendingBeforeCompletion);
        fixture.triggers.addBinding(new TriggerBinding("changed", "flow", TriggerType.SYSTEM, null));
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(1011, fixture.sender.closeCode);
        assertEquals(ConnectionState.CLOSING, connection.getState());
        assertFalse(connection.hasProtocolResourceAccess());
        assertTrue(connection.getNegotiatedFlowCapabilities().isEmpty());
        assertNull(fixture.sessions.getSession(connection));
        assertTrue(pending(fixture.server).isEmpty());
        assertTrue(virtualConnections(fixture.connections).isEmpty());
    }

    @Test
    void changedPlayerDataReadinessCannotPublishPreparedHandshake() throws Exception {
        fixture = fixture("player-data-stale");
        PlayerMock player = player();
        ConnectionInfo connection = fixture.openConnection();
        invokeHandshake(fixture.server, connection, player, request());
        assertTrue(fixture.storage.awaitEntered());

        int pendingBeforeCompletion = Bukkit.getScheduler().getPendingTasks().size();
        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        waitFor(() -> Bukkit.getScheduler().getPendingTasks().size() > pendingBeforeCompletion);
        fixture.playerDataAdmission.requestQuiesce();
        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(1011, fixture.sender.closeCode);
        assertEquals(ConnectionState.CLOSING, connection.getState());
        assertFalse(connection.hasProtocolResourceAccess());
        assertTrue(connection.getNegotiatedFlowCapabilities().isEmpty());
        assertNull(fixture.sessions.getSession(connection));
        assertEquals(1, fixture.sender.frames.size());
        Codec.Frame errorFrame = fixture.codec.decodeFrame(fixture.sender.frames.get(0));
        assertEquals(MessageType.ERROR, errorFrame.header.getMessageType());
        ErrorMessage error = assertInstanceOf(ErrorMessage.class, fixture.codec.decodePayload(errorFrame));
        assertEquals(503, error.getErrorCode());
        assertEquals("Handshake Readiness Changed", error.getErrorText());
        for (byte[] frame : fixture.sender.frames) {
            assertFalse(MessageType.HANDSHAKE_RESPONSE.equals(fixture.codec.decodeFrame(frame).header.getMessageType()));
        }
        assertTrue(pending(fixture.server).isEmpty());
        assertTrue(virtualConnections(fixture.connections).isEmpty());
    }

    @Test
    void blockedProbesRemainBoundedAndDoNotStarveTheSharedScheduler() throws Exception {
        fixture = fixture("bounded-probes");
        RecordingSender firstSender = new RecordingSender();
        RecordingSender secondSender = new RecordingSender();
        RecordingSender busySender = new RecordingSender();
        ConnectionInfo first = fixture.openConnection(firstSender);
        ConnectionInfo second = fixture.openConnection(secondSender);
        ConnectionInfo busy = fixture.openConnection(busySender);

        invokeHandshake(fixture.server, first, player(), request());
        assertTrue(fixture.storage.awaitEntered());
        invokeHandshake(fixture.server, second, player(), request());
        Semaphore permits = probePermits(fixture.server);
        waitFor(() -> permits.availablePermits() == 0);
        invokeHandshake(fixture.server, busy, player(), request());

        assertEquals(2, pending(fixture.server).size());
        assertFalse(busySender.frames.isEmpty());
        assertEquals(ConnectionState.CONNECTING, busy.getState());
        ThreadPoolExecutor probes = assertInstanceOf(ThreadPoolExecutor.class, fixture.handshakeExecutor);
        assertEquals(2, probes.getMaximumPoolSize());

        CompletableFuture<Void> sharedSchedulerRan = new CompletableFuture<>();
        fixture.scheduler.execute(() -> sharedSchedulerRan.complete(null));
        sharedSchedulerRan.get(1, TimeUnit.SECONDS);

        List<Map.Entry<ConnectionInfo, Object>> attempts = List.copyOf(pending(fixture.server).entrySet());
        for (Map.Entry<ConnectionInfo, Object> entry : attempts) {
            expireAttempt(entry.getValue());
            invokeTimeout(fixture.server, entry.getKey(), entry.getValue());
        }
        waitFor(() -> permits.availablePermits() == 2);

        assertEquals(1011, firstSender.closeCode);
        assertEquals(1011, secondSender.closeCode);
        assertTrue(pending(fixture.server).isEmpty());
        assertFalse(virtualConnections(fixture.connections).containsValue(first));
        assertFalse(virtualConnections(fixture.connections).containsValue(second));
    }

    @Test
    void rejectedMainContinuationFullyCleansExistingBridgeSession() throws Exception {
        fixture = fixture("continuation-rejected");
        PlayerMock player = player();
        ServerBridge bridge = new ServerBridge(plugin, fixture.server);
        Future<?> rejection = null;
        try {
            Object bridgeSession = newBridgeSession(player);
            attachBridgeSession(bridge, bridgeSession);
            invokeEnsureConnection(bridge, bridgeSession);
            ConnectionInfo connection = bridgeConnection(bridgeSession);
            assertNotNull(connection);
            fixture.sessions.createSession(connection, new ClientIdentity("existing", "test"));
            connection.setProtocolResourceAccess(true);
            invokeHandshake(fixture.server, connection, player, request());
            assertTrue(fixture.storage.awaitEntered());
            Object attempt = pending(fixture.server).get(connection);
            assertNotNull(attempt);

            MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
            AtomicBoolean rejectionOffMain = new AtomicBoolean();
            rejection = fixture.scheduler.submit(() -> {
                rejectionOffMain.set(!Bukkit.isPrimaryThread());
                try {
                    invokeRejectedContinuation(fixture.server, connection, attempt);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            assertTrue(bridge.closeEntered.await(5, TimeUnit.SECONDS));

            assertEquals("CLOSING", bridgeState(bridgeSession));
            assertEquals(ConnectionState.CLOSING, connection.getState());
            assertFalse(connection.hasProtocolResourceAccess());
            assertNotNull(fixture.sessions.getSession(connection));
            assertTrue(pending(fixture.server).isEmpty());
            assertTrue(virtualConnections(fixture.connections).containsValue(connection));
            assertEquals(0, fixture.cleanup.count.get());
            assertEquals(0, bridge.closeCount.get());
            assertFalse(rejection.isDone());

            fixture.scheduler.schedule(bridge.releaseClose::countDown, 25L, TimeUnit.MILLISECONDS);
            bridge.unregister();
            rejection.get(5, TimeUnit.SECONDS);
            assertTrue(rejectionOffMain.get());

            fixture.storage.release();
            assertTrue(fixture.storage.awaitCompleted());
            MockBukkit.getMock().getScheduler().performOneTick();
            fixture.server.onBridgeClose(connection);

            assertEquals(1, fixture.cleanup.count.get());
            assertTrue(fixture.cleanup.onMain.get());
            assertEquals(1, bridge.closeCount.get());
            assertTrue(bridge.closeOnMain.get());
            assertNull(fixture.sessions.getSession(connection));
            assertTrue(bridgeSessions(bridge).isEmpty());
            assertTrue(virtualConnections(fixture.connections).isEmpty());
        } finally {
            bridge.releaseClose.countDown();
            fixture.storage.release();
            if (rejection != null) {
                rejection.cancel(true);
            }
        }
    }

    @Test
    void timedOutPreparedHandshakeIsClosedAndCannotPublishLate() throws Exception {
        fixture = fixture("timeout");
        PlayerMock player = player();
        ConnectionInfo connection = fixture.openConnection();
        invokeHandshake(fixture.server, connection, player, request());
        assertTrue(fixture.storage.awaitEntered());
        Object current = pending(fixture.server).get(connection);
        assertNotNull(current);
        expireAttempt(current);

        invokeTimeout(fixture.server, connection, current);

        assertEquals(1011, fixture.sender.closeCode);
        assertEquals(ConnectionState.CLOSING, connection.getState());
        assertFalse(connection.hasProtocolResourceAccess());
        assertTrue(connection.getNegotiatedFlowCapabilities().isEmpty());
        assertNull(fixture.sessions.getSession(connection));
        assertTrue(pending(fixture.server).isEmpty());

        fixture.storage.release();
        assertTrue(fixture.storage.awaitCompleted());
        Thread.sleep(25L);
        MockBukkit.getMock().getScheduler().performOneTick();
        assertNull(fixture.sessions.getSession(connection));
        assertFalse(connection.hasProtocolResourceAccess());
    }

    private Fixture replaceFixture(String name) throws Exception {
        fixture.close();
        return fixture(name);
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        Path persistenceRoot = Files.createDirectory(root.resolve("persistence"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(
            persistenceRoot, root.resolve("coordination"), new MigrationFence());
        persistence.prepareActiveRoot();

        Path identityRoot = Files.createDirectory(root.resolve("identity"));
        ServerIdentityStore identity = ServerIdentityStore.open(identityRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path dataRoot = Files.createDirectory(root.resolve("storage"));
        Path assetsRoot = dataRoot.resolve("assets");
        AssetTransactionCoordinator assets = AssetTransactionCoordinator.open(assetsRoot, new Gson());
        BlockingFlowStorage storage = new BlockingFlowStorage(dataRoot, identity.serverId(), assets);
        Path worldRoot = Files.createDirectories(root.resolve("world").resolve("playerdata")).getParent();
        PaperPlayerDataMutationAdmission playerDataAdmission =
            new PaperPlayerDataMutationAdmission(List.of(worldRoot), root);

        ReSyncServer server = allocate(ReSyncServer.class);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        ExecutorService handshakeExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2));
        CompressionPool compression = new CompressionPool(1, 2);
        Codec codec = new Codec(compression);
        ChannelMuxer channels = new ChannelMuxer();
        ModuleRegistry modules = new ModuleRegistry();
        MemoryMonitor memory = new MemoryMonitor(0.5);
        SessionManager sessions = new SessionManager(memory, 300, 1_048_576L);
        ConnectionManager connections = new ConnectionManager(30, 60);
        AuthorityEpoch authority = AuthorityEpoch.fixed(persistence.authorityEpoch());
        ProtocolEnvelopeDispatchBoundary dispatch = new ProtocolEnvelopeDispatchBoundary(authority, null);
        ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(
            dispatch, ProtocolEnvelopeMailbox.Limits.standard(8, 32, Codec.DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES));
        TriggerRegistry triggers = new TriggerRegistry(root.resolve("triggers.json").toFile());
        ModuleContext context = new ModuleContext(plugin, server, null, connections, sessions, channels, modules,
            null, null, compression, codec, scheduler, memory);
        context.registerService(ReSyncPersistenceCoordinator.class, persistence);
        context.registerService(FlowStorage.class, storage);
        context.registerService(ServerIdentityStore.class, identity);
        context.registerService(TriggerRegistry.class, triggers);

        CleanupModule cleanup = new CleanupModule();
        modules.registerModule(cleanup);
        modules.initializeModules(context);
        modules.startModules(context);

        set(server, "plugin", plugin);
        set(server, "persistence", persistence);
        set(server, "connectionManager", connections);
        set(server, "sessionManager", sessions);
        set(server, "channelMuxer", channels);
        set(server, "moduleRegistry", modules);
        set(server, "compressionPool", compression);
        set(server, "codec", codec);
        set(server, "authorityEpoch", authority);
        set(server, "protocolEnvelopeDispatch", dispatch);
        set(server, "protocolEnvelopeMailbox", mailbox);
        set(server, "scheduler", scheduler);
        set(server, "handshakeExecutor", handshakeExecutor);
        set(server, "bridgeHandshakes", new ConcurrentHashMap<ConnectionInfo, Object>());
        set(server, "handshakeProbes", new Semaphore(2));
        set(server, "memoryMonitor", memory);
        set(server, "moduleContext", context);
        set(server, "playerDataAdmission", playerDataAdmission);
        set(server, "shutdownStarted", new AtomicBoolean());
        set(server, "coreShutdownPrepared", new AtomicBoolean());

        return new Fixture(server, persistence, storage, assets, connections, sessions, compression, codec, memory,
            scheduler, handshakeExecutor, mailbox, triggers, cleanup, playerDataAdmission);
    }

    private PlayerMock player() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOp(true);
        return player;
    }

    private HandshakeRequest request() {
        HandshakeRequest request = new HandshakeRequest();
        request.setClientId("remotely-test");
        request.setClientVersion("test");
        request.setCapabilitiesJson("[\"option_queries\"]");
        return request;
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            MockBukkit.getMock().getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void invokeHandshake(ReSyncServer server, ConnectionInfo connection, Player player,
                                        HandshakeRequest request) throws Exception {
        Method method = ReSyncServer.class.getDeclaredMethod(
            "handleBridgeHandshake", ConnectionInfo.class, Player.class, HandshakeRequest.class);
        invoke(method, server, connection, player, request);
    }

    private static Object newBridgeSession(Player player) throws Exception {
        Class<?> type = Class.forName("restudio.resync.bridge.ReSyncPluginMessageBridge$BridgeSession");
        Constructor<?> constructor = type.getDeclaredConstructor(UUID.class, Player.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UUID.randomUUID(), player);
    }

    private static void attachBridgeSession(ReSyncPluginMessageBridge bridge, Object session) throws Exception {
        Field sessionId = session.getClass().getDeclaredField("sessionId");
        sessionId.setAccessible(true);
        bridgeSessions(bridge).put((UUID) sessionId.get(session), session);
    }

    private static void invokeEnsureConnection(ReSyncPluginMessageBridge bridge, Object session) throws Exception {
        Method method = ReSyncPluginMessageBridge.class.getDeclaredMethod("ensureConnection", session.getClass());
        method.setAccessible(true);
        invoke(method, bridge, session);
    }

    private static void invokeRejectedContinuation(ReSyncServer server, ConnectionInfo connection, Object attempt)
        throws Exception {
        Method method = ReSyncServer.class.getDeclaredMethod(
            "rejectBridgeHandshakeContinuation", ConnectionInfo.class, attempt.getClass());
        invoke(method, server, connection, attempt);
    }

    private static ConnectionInfo bridgeConnection(Object session) throws Exception {
        Field connection = session.getClass().getDeclaredField("connection");
        connection.setAccessible(true);
        return (ConnectionInfo) connection.get(session);
    }

    private static String bridgeState(Object session) {
        try {
            Field state = session.getClass().getDeclaredField("state");
            state.setAccessible(true);
            return state.get(session).toString();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> bridgeSessions(ReSyncPluginMessageBridge bridge) throws Exception {
        Field sessions = ReSyncPluginMessageBridge.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        return (Map<UUID, Object>) sessions.get(bridge);
    }

    private static void invokeTimeout(ReSyncServer server, ConnectionInfo connection, Object attempt) throws Exception {
        Method method = ReSyncServer.class.getDeclaredMethod("timeoutBridgeHandshake", ConnectionInfo.class,
            attempt.getClass());
        invoke(method, server, connection, attempt);
    }

    @SuppressWarnings("unchecked")
    private static Map<ConnectionInfo, Object> pending(ReSyncServer server) throws Exception {
        Field field = ReSyncServer.class.getDeclaredField("bridgeHandshakes");
        field.setAccessible(true);
        return (Map<ConnectionInfo, Object>) field.get(server);
    }

    private static Semaphore probePermits(ReSyncServer server) throws Exception {
        Field field = ReSyncServer.class.getDeclaredField("handshakeProbes");
        field.setAccessible(true);
        return (Semaphore) field.get(server);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, ConnectionInfo> virtualConnections(ConnectionManager connections) throws Exception {
        Field field = ConnectionManager.class.getDeclaredField("virtualConnections");
        field.setAccessible(true);
        return (Map<Integer, ConnectionInfo>) field.get(connections);
    }

    private static void expireAttempt(Object attempt) throws Exception {
        Field field = attempt.getClass().getDeclaredField("started");
        UNSAFE.putLong(attempt, UNSAFE.objectFieldOffset(field),
            System.nanoTime() - TimeUnit.SECONDS.toNanos(9));
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UNSAFE.allocateInstance(type));
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = ReSyncServer.class.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static Object playerDataMonitor(PaperPlayerDataMutationAdmission admission) throws Exception {
        Field field = PaperPlayerDataMutationAdmission.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return field.get(admission);
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }

    private static final class BlockingFlowStorage extends FlowStorage {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean probeOnMainThread = new AtomicBoolean();

        private BlockingFlowStorage(Path dataRoot, ServerId serverId, AssetTransactionCoordinator assets) {
            super(dataRoot.toFile(), LegacyRuntimeActivationGate.runtime(dataRoot),
                new AssetPersistenceGate(dataRoot), serverId, assets);
        }

        @Override
        public synchronized AssetIntegrityService.HealthReport getDurabilityHealth() {
            probeOnMainThread.set(Bukkit.isPrimaryThread());
            entered.countDown();
            try {
                await(release);
                return super.getDurabilityHealth();
            } finally {
                completed.countDown();
            }
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitCompleted() throws InterruptedException {
            return completed.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed Out Waiting For Handshake Probe Release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class RecordingSender implements FrameSender {
        private final CopyOnWriteArrayList<byte[]> frames = new CopyOnWriteArrayList<>();
        private volatile int closeCode = -1;

        @Override
        public void send(byte[] frame) {
            frames.add(frame.clone());
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
        }
    }

    private static final class CleanupModule implements Module {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicBoolean onMain = new AtomicBoolean();

        @Override
        public ModuleMetadata getMetadata() {
            return ModuleMetadata.of("bridge-cleanup-test", "Bridge Cleanup Test");
        }

        @Override
        public void cleanup(Session session) {
            count.incrementAndGet();
            onMain.set(Bukkit.isPrimaryThread());
        }
    }

    private static final class ServerBridge extends ReSyncPluginMessageBridge {
        private final ReSyncServer server;
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicBoolean closeOnMain = new AtomicBoolean();
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private ServerBridge(ReSync plugin, ReSyncServer server) {
            super(plugin);
            this.server = server;
        }

        @Override
        protected ConnectionInfo openBridgeConnection(FrameSender sender) {
            return server.onBridgeOpen(new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    sender.send(frame);
                }

                @Override
                public SendResult trySend(byte[] frame) {
                    return sender.trySend(frame);
                }

                @Override
                public void close(int code, String reason) {
                    sender.close(code, reason);
                    closeEntered.countDown();
                    try {
                        if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed Out Waiting For Bridge Close Contention");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }

                @Override
                public int getMaxEncodedFrameBytes() {
                    return sender.getMaxEncodedFrameBytes();
                }
            });
        }

        @Override
        protected void closeBridgeConnection(ConnectionInfo connection) {
            closeCount.incrementAndGet();
            closeOnMain.set(Bukkit.isPrimaryThread());
            server.onBridgeClose(connection);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ReSyncServer server;
        private final ReSyncPersistenceCoordinator persistence;
        private final BlockingFlowStorage storage;
        private final AssetTransactionCoordinator assets;
        private final ConnectionManager connections;
        private final SessionManager sessions;
        private final CompressionPool compression;
        private final Codec codec;
        private final MemoryMonitor memory;
        private final ScheduledExecutorService scheduler;
        private final ExecutorService handshakeExecutor;
        private final ProtocolEnvelopeMailbox mailbox;
        private final TriggerRegistry triggers;
        private final CleanupModule cleanup;
        private final PaperPlayerDataMutationAdmission playerDataAdmission;
        private final RecordingSender sender = new RecordingSender();

        private Fixture(ReSyncServer server, ReSyncPersistenceCoordinator persistence, BlockingFlowStorage storage,
                        AssetTransactionCoordinator assets, ConnectionManager connections, SessionManager sessions,
                        CompressionPool compression, Codec codec, MemoryMonitor memory,
                        ScheduledExecutorService scheduler, ExecutorService handshakeExecutor,
                        ProtocolEnvelopeMailbox mailbox, TriggerRegistry triggers, CleanupModule cleanup,
                        PaperPlayerDataMutationAdmission playerDataAdmission) {
            this.server = server;
            this.persistence = persistence;
            this.storage = storage;
            this.assets = assets;
            this.connections = connections;
            this.sessions = sessions;
            this.compression = compression;
            this.codec = codec;
            this.memory = memory;
            this.scheduler = scheduler;
            this.handshakeExecutor = handshakeExecutor;
            this.mailbox = mailbox;
            this.triggers = triggers;
            this.cleanup = cleanup;
            this.playerDataAdmission = playerDataAdmission;
        }

        private ConnectionInfo openConnection() {
            return server.onBridgeOpen(sender);
        }

        private ConnectionInfo openConnection(RecordingSender sender) {
            return server.onBridgeOpen(sender);
        }

        @Override
        public void close() throws Exception {
            storage.release();
            handshakeExecutor.shutdownNow();
            scheduler.shutdownNow();
            mailbox.shutdown();
            sessions.shutdown();
            connections.shutdown();
            compression.close();
            memory.shutdown();
            assets.close();
            persistence.beginShutdown();
        }
    }
}
