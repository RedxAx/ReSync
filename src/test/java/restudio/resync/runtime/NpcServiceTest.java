package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcServiceTest {
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        MockBukkit.getMock().addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void hookPayloadUsesCompactNpcBindingShape() {
        Map<String, Object> variables = NpcService.hookVariables("guard", null, null, null);

        assertEquals("guard", variables.get("npcId"));
        assertTrue(variables.containsKey("player"));
        assertTrue(variables.containsKey("entity"));
        assertTrue(variables.containsKey("location"));
        assertTrue(variables.containsKey("handle"));
        assertEquals("guard", variables.get("event.npcId"));
        assertTrue(variables.containsKey("event.player"));
        assertTrue(variables.containsKey("event.handle"));
        assertNull(variables.get("player"));
        assertNull(variables.get("entity"));
        assertNull(variables.get("location"));
    }

    @Test
    void startupModeSpawnsAnInactivePlayerNpcWhenDefinitionReloads() {
        JsonObject definition = playerNpcDefinition("startup");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(definition, runtime, new RecordingDispatcher());

        service.reload("guide", definition, false);

        assertTrue(runtime.isActive("guide"));
        assertEquals(1, runtime.spawnCount);
        service.shutdown();
    }

    @Test
    void manualModeDoesNotTurnSavingADefinitionIntoAWorldMutation() {
        JsonObject definition = playerNpcDefinition("manual");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(definition, runtime, new RecordingDispatcher());

        service.reload("guide", definition, false);

        assertFalse(runtime.isActive("guide"));
        assertEquals(0, runtime.spawnCount);
        service.shutdown();
    }

    @Test
    void repeatedPlayerNpcSpawnIsIdempotentAndDoesNotRepeatSpawnHook() {
        JsonObject definition = playerNpcDefinition("manual");
        definition.getAsJsonObject("hooks").addProperty("spawnAction", "spawn-flow");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, runtime, dispatcher);
        Location location = location();

        service.spawn("guide", location);
        service.spawn("guide", location);

        assertEquals(1, runtime.spawnCount);
        assertEquals(List.of("spawn-flow"), dispatcher.flowIds);
        service.shutdown();
    }

    @Test
    void reloadingAnActivePlayerNpcUpdatesItWithoutRepeatingSpawn() {
        JsonObject definition = playerNpcDefinition("manual");
        definition.getAsJsonObject("hooks").addProperty("spawnAction", "spawn-flow");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, runtime, dispatcher);
        service.spawn("guide", location());
        JsonObject updated = playerNpcDefinition("manual");
        updated.getAsJsonObject("location").addProperty("x", 8);
        service.definition(updated);

        service.reload("guide", updated, false);

        assertEquals(1, runtime.spawnCount);
        assertEquals(1, runtime.reloadCount);
        assertEquals(8, runtime.location("guide").getX());
        assertEquals(List.of("spawn-flow"), dispatcher.flowIds);
        service.shutdown();
    }

    @Test
    void deletingAnActiveNpcUsesItsSnapshotAndDispatchesDespawnOnce() {
        JsonObject definition = playerNpcDefinition("manual");
        definition.getAsJsonObject("hooks").addProperty("despawnAction", "despawn-flow");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, runtime, dispatcher);
        service.spawn("guide", location());
        service.definition(null);

        service.reload("guide", null, true);
        service.reload("guide", null, true);

        assertFalse(runtime.isActive("guide"));
        assertEquals(1, runtime.despawnCount);
        assertEquals(List.of("despawn-flow"), dispatcher.flowIds);
        service.shutdown();
    }

    @Test
    void interactionDispatchesGenericAndButtonSpecificHooks() {
        JsonObject definition = playerNpcDefinition("manual");
        JsonObject hooks = definition.getAsJsonObject("hooks");
        hooks.addProperty("interactAction", "interact-flow");
        hooks.addProperty("rightClickAction", "right-flow");
        hooks.addProperty("leftClickAction", "left-flow");
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, new TestPlayerNpcRuntime(), dispatcher);

        service.dispatchInteraction("guide", false, null, null, location(), null, Map.of());
        service.dispatchInteraction("guide", true, null, null, location(), null, Map.of());

        assertEquals(List.of("interact-flow", "right-flow", "interact-flow", "left-flow"), dispatcher.flowIds);
        service.shutdown();
    }

    private TestNpcService service(JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher) {
        return new TestNpcService(plugin, definition, runtime, dispatcher);
    }

    private JsonObject playerNpcDefinition(String spawnMode) {
        JsonObject definition = new JsonObject();
        definition.addProperty("enabled", true);
        definition.addProperty("entityType", "player");
        definition.addProperty("spawnMode", spawnMode);
        JsonObject location = new JsonObject();
        location.addProperty("world", "world");
        location.addProperty("x", 1);
        location.addProperty("y", 70);
        location.addProperty("z", 2);
        definition.add("location", location);
        definition.add("hooks", new JsonObject());
        return definition;
    }

    private Location location() {
        return new Location(MockBukkit.getMock().getWorld("world"), 1, 70, 2);
    }

    private static final class TestNpcService extends NpcService {
        private JsonObject definition;

        private TestNpcService(JavaPlugin plugin, JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher) {
            super(plugin, null, null, dispatcher, null, null, null, runtime, new NamespacedKey(plugin, "resync_npc_id"));
            this.definition = definition;
        }

        @Override
        public JsonObject get(String id) {
            return definition;
        }

        private void definition(JsonObject definition) {
            this.definition = definition;
        }
    }

    private static final class RecordingDispatcher extends RuntimeFlowDispatcher {
        private final List<String> flowIds = new ArrayList<>();

        private RecordingDispatcher() {
            super(null, null);
        }

        @Override
        public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
            flowIds.add(flowId);
            return true;
        }
    }

    private static final class TestPlayerNpcRuntime implements PlayerNpcRuntime {
        private String activeId;
        private Location location;
        private int spawnCount;
        private int despawnCount;
        private int reloadCount;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public boolean spawn(String id, JsonObject definition, Location location) {
            activeId = id;
            this.location = location.clone();
            spawnCount++;
            return true;
        }

        @Override
        public boolean despawn(String id) {
            if (!isActive(id)) {
                return false;
            }
            activeId = null;
            location = null;
            despawnCount++;
            return true;
        }

        @Override
        public boolean reload(String id, JsonObject definition, boolean deleted, Location fallbackLocation) {
            if (!isActive(id)) {
                return false;
            }
            if (fallbackLocation != null) {
                location = fallbackLocation.clone();
            }
            reloadCount++;
            return true;
        }

        @Override
        public boolean isActive(String id) {
            return id != null && id.equals(activeId);
        }

        @Override
        public Location location(String id) {
            return isActive(id) && location != null ? location.clone() : null;
        }

        @Override
        public List<String> activeIds() {
            return activeId != null ? List.of(activeId) : List.of();
        }

        @Override
        public boolean teleport(String id, String world, double x, double y, double z, float yaw, float pitch) {
            if (!isActive(id) || location == null || !location.getWorld().getName().equals(world)) {
                return false;
            }
            location.setX(x);
            location.setY(y);
            location.setZ(z);
            location.setYaw(yaw);
            location.setPitch(pitch);
            return true;
        }

        @Override
        public void shutdown() {
            activeId = null;
            location = null;
        }
    }
}
