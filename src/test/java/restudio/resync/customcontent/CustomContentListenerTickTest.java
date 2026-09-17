package restudio.resync.customcontent;

import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentListenerTickTest {
    @TempDir
    Path temporary;

    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;
    private CustomContentStorage storage;
    private RecordingCustomContentService service;
    private CustomContentListener listener;
    private World world;
    private Player player;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        world = MockBukkit.getMock().addSimpleWorld("world");
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        assetsGate = new AssetPersistenceGate(activeRoot);
        coordinator = AssetTransactionCoordinator.open(activeRoot.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        storage = new CustomContentStorage(plugin, activeRoot, new ItemAttributeSchemaService(),
            LegacyRuntimeActivationGate.runtime(activeRoot), assetsGate, coordinator);
        service = new RecordingCustomContentService(storage);
        listener = new CustomContentListener(storage, service);
        player = MockBukkit.getMock().addPlayer();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (storage != null) {
                assetsGate.quiesce();
                storage.close();
            }
        } finally {
            if (coordinator != null) {
                coordinator.close();
            }
            MockBukkit.unmock();
        }
    }

    @Test
    void nearbyPlayerKeepsFiringEveryTickInRangeAndSkipsUnboundTick() {
        CustomContentDefinition nearby = block("beacon", new CustomAbilityBinding("beacon_nearby", "block.nearby_player", "beacon_flow"));
        storage.save(nearby);
        List<Location> placed = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            Location location = location(x, 64, 0);
            load(location);
            service.markPlacedBlock(location, nearby);
            placed.add(location);
        }
        player.teleport(location(1.5, 64.0, 0.5));
        load(player.getLocation());
        int inRange = inRangeCount(placed, player);
        assertTrue(inRange > 1);

        listener.tick();
        listener.tick();
        listener.tick();
        listener.tick();
        listener.tick();

        assertEquals(inRange * 5, count("beacon:block.nearby_player"));
        assertEquals(0, count("beacon:block.tick"));
    }

    @Test
    void boundTickKeepsFiringEveryTickAndNearbyIgnoresPlayersOutOfRange() {
        CustomContentDefinition ticking = block("pulse",
            new CustomAbilityBinding("pulse_tick", "block.tick", "pulse_flow"),
            new CustomAbilityBinding("pulse_nearby", "block.nearby_player", "pulse_flow"));
        storage.save(ticking);
        Location origin = location(0, 64, 0);
        load(origin);
        service.markPlacedBlock(origin, ticking);
        player.teleport(location(20.5, 64.0, 20.5));
        load(player.getLocation());

        listener.tick();
        listener.tick();
        listener.tick();

        assertEquals(0, count("pulse:block.nearby_player"));
        assertEquals(3, count("pulse:block.tick"));
    }

    @Test
    void nearbyPlayerOmitsUnusedItemAndBlockContext() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("beacon", "block", "Beacon");
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        start.getInputValues().put(CustomContentGraphAdapter.FLOW_BRANCHES_KEY, List.of("nearby_player"));
        String startId = graph.findNodeId(start);
        graph.getNodes().put("message", new FlowNode("player.player_message", 400, 120, Map.of("text", "Nearby")));
        graph.getConnections().add(new FlowConnection(startId, "nearby_player", "message", "flow"));
        graph.getConnections().add(new FlowConnection(startId, "player", "message", "target"));
        CustomContentDefinition nearby = block("beacon", new CustomAbilityBinding("beacon_nearby", "block.nearby_player", graph.getId()));
        nearby.setGraph(graph);
        nearby.setFlowId(graph.getId());
        storage.save(nearby);
        Location origin = location(0, 64, 0);
        load(origin);
        service.markPlacedBlock(origin, nearby);
        player.teleport(location(0.5, 64.0, 0.5));
        load(player.getLocation());

        listener.tick();

        assertEquals(1, count("beacon:block.nearby_player"));
        Map<String, Object> vars = service.lastVars();
        assertTrue(vars.containsKey("event.player"));
        assertFalse(vars.containsKey("event.item"));
        assertFalse(vars.containsKey("event.block"));
        assertFalse(vars.containsKey("event.instance_id"));
        assertFalse(vars.containsKey("event.target"));
        assertFalse(vars.containsKey("event.location"));
    }

    @Test
    void nearbyPlayerOmitsOptionalContextWhenStartPinsAreOnlyTheTrigger() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("beacon", "block", "Beacon");
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        start.getInputValues().put(CustomContentGraphAdapter.FLOW_BRANCHES_KEY, List.of("nearby_player"));
        String startId = graph.findNodeId(start);
        graph.getNodes().put("message", new FlowNode("player.player_message", 400, 120, Map.of("text", "Nearby")));
        graph.getConnections().add(new FlowConnection(startId, "nearby_player", "message", "flow"));
        CustomContentDefinition nearby = block("beacon", new CustomAbilityBinding("beacon_nearby", "block.nearby_player", graph.getId()));
        nearby.setGraph(graph);
        nearby.setFlowId(graph.getId());
        storage.save(nearby);
        Location origin = location(0, 64, 0);
        load(origin);
        service.markPlacedBlock(origin, nearby);
        player.teleport(location(0.5, 64.0, 0.5));
        load(player.getLocation());

        listener.tick();

        assertEquals(1, count("beacon:block.nearby_player"));
        Map<String, Object> vars = service.lastVars();
        assertTrue(vars.containsKey("event.player"));
        assertFalse(vars.containsKey("event.item"));
        assertFalse(vars.containsKey("event.block"));
        assertFalse(vars.containsKey("event.location"));
        assertFalse(vars.containsKey("event.instance_id"));
    }

    private int count(String dispatch) {
        return (int) service.dispatches.stream().filter(dispatch::equals).count();
    }

    private int inRangeCount(List<Location> placed, Player target) {
        int count = 0;
        double px = target.getX();
        double py = target.getY();
        double pz = target.getZ();
        for (Location location : placed) {
            double dx = px - location.getBlockX();
            double dy = py - location.getBlockY();
            double dz = pz - location.getBlockZ();
            if (dx * dx + dy * dy + dz * dz <= 9.0) {
                count++;
            }
        }
        return count;
    }

    private void load(Location location) {
        location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private Location location(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private CustomContentDefinition block(String id, CustomAbilityBinding... abilities) {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(id);
        definition.setType("block");
        definition.setMaterial("STONE");
        definition.setDisplayName(id);
        definition.setAbilities(List.of(abilities));
        return definition;
    }

    private static final class RecordingCustomContentService extends CustomContentService {
        private final List<String> dispatches = new ArrayList<>();
        private final List<Map<String, Object>> capturedVars = new ArrayList<>();

        private RecordingCustomContentService(CustomContentStorage storage) {
            super(storage, null, null);
        }

        @Override
        public void dispatch(String contentId, String trigger, Player player, org.bukkit.event.Event event, Map<String, Object> eventVars) {
            dispatches.add(contentId + ":" + trigger);
            capturedVars.add(eventVars == null ? Map.of() : new HashMap<>(eventVars));
        }

        private Map<String, Object> lastVars() {
            return capturedVars.isEmpty() ? Map.of() : capturedVars.get(capturedVars.size() - 1);
        }
    }
}
