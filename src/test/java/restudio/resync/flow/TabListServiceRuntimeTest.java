package restudio.resync.flow;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import restudio.flow.data.TabDefinition;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabListServiceRuntimeTest {
    @TempDir
    Path directory;
    private ServerMock server;
    private CountingStorage storage;
    private AssetTransactionCoordinator coordinator;
    private PacketEventsAPI<?> previousPackets;
    private RecordingPackets packets;
    private final Map<String, Object> globals = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson());
        storage = new CountingStorage(directory.toFile(), coordinator);
        FlowRuntimeAccess.configure(MockBukkit.createMockPlugin(), () -> storage, () -> globals);
        previousPackets = PacketEvents.getAPI();
        packets = new RecordingPackets();
        PacketEvents.setAPI(packets);
    }

    @AfterEach
    void tearDown() throws Exception {
        TabListService.stopUpdater();
        if (server != null) {
            server.getOnlinePlayers().forEach(TabListService::clearTrackedPlayer);
        }
        FlowRuntimeAccess.clear();
        PacketEvents.setAPI(previousPackets);
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void idleScheduledRefreshDoesNotReadConfiguredAnimatedDefinitions() {
        saveDefault("%resync_animation:test%");
        storage.reads = 0;
        storage.runtimeReads = 0;
        TabListService.startUpdater();

        server.getScheduler().performTicks(400);

        assertEquals(0, storage.reads);
        assertEquals(0, storage.runtimeReads);
        assertTrue(packets.sent.isEmpty());
    }

    @Test
    void warmAnimationRefreshKeepsPerPlayerRenderingWithoutDefinitionReads() {
        Player alice = server.addPlayer("Alice");
        Player bob = server.addPlayer("Bob");
        globals.put("player_vars_" + alice.getUniqueId(), Map.of("label", "Alice One"));
        globals.put("player_vars_" + bob.getUniqueId(), Map.of("label", "Bob One"));
        saveDefault("%resync_label% %resync_animation:test%");
        TabListService.startUpdater();
        server.getScheduler().performTicks(1);
        assertEquals(Component.text("Alice One %resync_animation:test%"), packets.lastHeader(alice));
        assertEquals(Component.text("Bob One %resync_animation:test%"), packets.lastHeader(bob));
        storage.reads = 0;
        packets.sent.clear();
        globals.put("player_vars_" + alice.getUniqueId(), Map.of("label", "Alice Two"));
        globals.put("player_vars_" + bob.getUniqueId(), Map.of("label", "Bob Two"));

        server.getScheduler().performTicks(4);

        assertEquals(0, storage.reads);
        assertTrue(packets.headers(alice).size() >= 2);
        assertTrue(packets.headers(bob).size() >= 2);
        assertEquals(Component.text("Alice Two %resync_animation:test%"), packets.lastHeader(alice));
        assertEquals(Component.text("Bob Two %resync_animation:test%"), packets.lastHeader(bob));
    }

    @Test
    void scheduledRenderingTracksSaveDisableAndDeleteWithoutRouterCallbacks() {
        Player player = server.addPlayer();
        storage.setTabRefreshIntervalTicks(1);
        saveDefault("Original");
        TabListService.startUpdater();
        server.getScheduler().performTicks(1);
        assertEquals(Component.text("Original"), packets.lastHeader(player));

        TabDefinition changed = new TabDefinition("main");
        changed.setHeader("Changed");
        storage.saveTab(changed);
        server.getScheduler().performTicks(1);
        assertEquals(Component.text("Changed"), packets.lastHeader(player));
        changed.setEnabled(false);
        storage.saveTab(changed);
        server.getScheduler().performTicks(1);
        assertEquals(Component.empty(), packets.lastHeader(player));

        changed.setEnabled(true);
        storage.saveTab(changed);
        server.getScheduler().performTicks(1);
        assertEquals(Component.text("Changed"), packets.lastHeader(player));
        storage.deleteTab("main");
        server.getScheduler().performTicks(1);
        assertEquals(Component.empty(), packets.lastHeader(player));
        storage.reads = 0;
        packets.sent.clear();
        server.getScheduler().performTicks(40);
        assertEquals(0, storage.reads);
        assertTrue(packets.sent.isEmpty());
    }

    private void saveDefault(String header) {
        TabDefinition definition = new TabDefinition("main");
        definition.setHeader(header);
        storage.saveTab(definition);
        storage.setDefaultTab("main", true);
    }

    private static final class CountingStorage extends FlowStorage {
        private int reads;
        private int runtimeReads;

        private CountingStorage(File directory, AssetTransactionCoordinator coordinator) {
            super(directory, coordinator);
        }

        @Override
        public synchronized TabDefinition getTab(String id) {
            reads++;
            return super.getTab(id);
        }

        @Override
        public synchronized TabDefinition getRuntimeTab(String id) {
            runtimeReads++;
            return super.getRuntimeTab(id);
        }
    }

    private static final class RecordingPackets extends PacketEventsAPI<Object> {
        private final Map<UUID, List<PacketWrapper<?>>> sent = new HashMap<>();
        private final PlayerManager players = (PlayerManager) Proxy.newProxyInstance(PlayerManager.class.getClassLoader(),
            new Class<?>[]{PlayerManager.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("sendPacket") && arguments[0] instanceof Player player
                    && arguments[1] instanceof PacketWrapper<?> packet) {
                    sent.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(packet);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });

        private List<WrapperPlayServerPlayerListHeaderAndFooter> headers(Player player) {
            return sent.getOrDefault(player.getUniqueId(), List.of()).stream()
                .filter(WrapperPlayServerPlayerListHeaderAndFooter.class::isInstance)
                .map(WrapperPlayServerPlayerListHeaderAndFooter.class::cast).toList();
        }

        private Component lastHeader(Player player) {
            return headers(player).getLast().getHeader();
        }

        @Override
        public void load() {
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void terminate() {
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return null;
        }

        @Override
        public ServerManager getServerManager() {
            return () -> ServerVersion.V_1_21_10;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlayerManager getPlayerManager() {
            return players;
        }

        @Override
        public NettyManager getNettyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelInjector getInjector() {
            throw new UnsupportedOperationException();
        }
    }
}
