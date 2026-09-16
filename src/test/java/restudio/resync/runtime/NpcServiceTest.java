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
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcServiceTest {
    @TempDir
    Path temporary;
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
    void definitionReloadDoesNotSpawnAnInactiveNpc() {
        JsonObject definition = playerNpcDefinition();
        definition.addProperty("spawnMode", "startup");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(definition, runtime, new RecordingDispatcher());

        service.reload("guide", definition, false);

        assertFalse(runtime.isActive("guide"));
        assertEquals(0, runtime.spawnCount);
        service.shutdown();
    }

    @Test
    void repeatedPlayerNpcSpawnIsIdempotentAndDoesNotRepeatSpawnHook() {
        JsonObject definition = playerNpcDefinition();
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
    void reloadingAnActivePlayerNpcKeepsItsRuntimePositionWithoutRepeatingSpawn() {
        JsonObject definition = playerNpcDefinition();
        definition.getAsJsonObject("hooks").addProperty("spawnAction", "spawn-flow");
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, runtime, dispatcher);
        service.spawn("guide", location());
        JsonObject updated = playerNpcDefinition();
        JsonObject legacyLocation = new JsonObject();
        legacyLocation.addProperty("world", "world");
        legacyLocation.addProperty("x", 8);
        legacyLocation.addProperty("y", 70);
        legacyLocation.addProperty("z", 2);
        updated.add("location", legacyLocation);
        service.definition(updated);

        service.reload("guide", updated, false);

        assertEquals(1, runtime.spawnCount);
        assertEquals(1, runtime.reloadCount);
        assertEquals(1, runtime.location("guide").getX());
        assertEquals(List.of("spawn-flow"), dispatcher.flowIds);
        service.shutdown();
    }

    @Test
    void deletingAnActiveNpcUsesItsSnapshotAndDispatchesDespawnOnce() {
        JsonObject definition = playerNpcDefinition();
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
        JsonObject definition = playerNpcDefinition();
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

    @Test
    void replacementRuntimeDoesNotDispatchLegacyHookFallback() {
        JsonObject definition = playerNpcDefinition();
        definition.getAsJsonObject("hooks").addProperty("rightClickFlow", "legacy-right");
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, new TestPlayerNpcRuntime(), dispatcher, LegacyRuntimeActivationGate.runtime(Path.of("build", "npc-runtime-gate-test")));

        service.dispatchInteraction("guide", false, null, null, location(), null, Map.of());

        assertTrue(dispatcher.flowIds.isEmpty());
        service.shutdown();
    }

    @Test
    void replacementRuntimeDispatchesTypedNpcAction() {
        JsonObject definition = playerNpcDefinition();
        JsonObject action = new JsonObject();
        action.addProperty("functionId", "typed-right");
        definition.getAsJsonObject("hooks").add("rightClickAction", action);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = service(definition, new TestPlayerNpcRuntime(), dispatcher,
            LegacyRuntimeActivationGate.runtime(Path.of("build", "npc-runtime-typed-test")));

        service.dispatchInteraction("guide", false, null, null, location(), null, Map.of());

        assertEquals(1, dispatcher.functionCalls.size());
        service.shutdown();
    }

    @Test
    void persistenceQuiesceBlocksPlayerNpcMutationsAndRestoration() throws Exception {
        JsonObject definition = playerNpcDefinition();
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(definition, runtime, new RecordingDispatcher());
        PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(plugin.getDataFolder().toPath(), service);
        participant.flush();
        participant.quiesce();

        assertNull(service.spawn("guide", location()));
        assertFalse(service.despawn("guide"));
        assertFalse(service.teleport("guide", location()));
        service.reload("guide", definition, false);
        service.restorePersistentNpcs();
        assertEquals(0, runtime.spawnCount);
        assertEquals(0, runtime.despawnCount);
        participant.resume();
        service.shutdown();
    }

    @Test
    void activeDataRootBindsPlayerNpcServiceAndParticipantToTheSameFile() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(playerNpcDefinition(), runtime, new RecordingDispatcher(), activeRoot);
        PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(activeRoot, service);

        assertEquals(activeRoot.resolve("runtime/player-npcs.json").toAbsolutePath().normalize(), service.persistenceRoot());
        assertEquals(service.persistenceRoot(), participant.root());
        participant.flush();
        assertTrue(Files.isRegularFile(service.persistenceRoot()));
        assertFalse(Files.exists(plugin.getDataFolder().toPath().resolve("runtime/player-npcs.json")));
        service.shutdown();
    }

    @Test
    void participantRebindAtomicallyMovesTheServiceBackingFile() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active-rebind"));
        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate-rebind"));
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(playerNpcDefinition(), runtime, new RecordingDispatcher(), activeRoot);
        PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(activeRoot, service);
        participant.flush();
        service.spawn("guide", location());
        assertTrue(runtime.isActive("guide"));
        participant.quiesce();

        participant.rebind(candidateRoot);

        Path expected = candidateRoot.resolve("runtime/player-npcs.json").toAbsolutePath().normalize();
        assertEquals(expected, service.persistenceRoot());
        assertEquals(expected, participant.root());
        assertTrue(Files.isRegularFile(expected));
        assertFalse(runtime.isActive("guide"));
        participant.resume();
        service.shutdown();
    }

    @Test
    void failedRuntimeRebindRestoresThePreviousFileAndActiveInstance() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("active-rebind-rollback"));
        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate-rebind-rollback"));
        TestPlayerNpcRuntime runtime = new TestPlayerNpcRuntime();
        TestNpcService service = service(playerNpcDefinition(), runtime, new RecordingDispatcher(), activeRoot);
        PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(activeRoot, service);
        participant.flush();
        service.spawn("guide", location());
        participant.quiesce();
        runtime.failDespawn = true;

        assertThrows(java.io.IOException.class, () -> participant.rebind(candidateRoot));
        assertEquals(activeRoot.resolve("runtime/player-npcs.json").toAbsolutePath().normalize(), service.persistenceRoot());
        assertTrue(runtime.isActive("guide"));
        runtime.failDespawn = false;
        participant.resume();
        service.shutdown();
    }

    @Test
    void playerNpcOwnershipIndexClaimsOnlyThePersistenceFile() throws Exception {
        TestNpcService service = service(playerNpcDefinition(), new TestPlayerNpcRuntime(), new RecordingDispatcher());
        PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(
            plugin.getDataFolder().toPath(), service);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(
            plugin.getDataFolder().toPath(), participant.root()));

        assertTrue(index.owns("runtime/player-npcs.json"));
        assertFalse(index.owns("runtime/player-npcs.json.tmp"));
        assertFalse(index.owns("runtime/player-npcs.json/nested"));
        service.shutdown();
    }

    private TestNpcService service(JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher) {
        return new TestNpcService(plugin, definition, runtime, dispatcher);
    }

    private TestNpcService service(JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher, LegacyRuntimeActivationGate gate) {
        return new TestNpcService(plugin, definition, runtime, dispatcher, gate);
    }

    private TestNpcService service(JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher, Path activeDataRoot) {
        return new TestNpcService(plugin, definition, runtime, dispatcher, activeDataRoot);
    }

    private JsonObject playerNpcDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("enabled", true);
        definition.addProperty("entityType", "player");
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

        private TestNpcService(JavaPlugin plugin, JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher,
                               LegacyRuntimeActivationGate gate) {
            super(plugin, null, null, dispatcher, null, null, null, runtime, new NamespacedKey(plugin, "resync_npc_id"), gate, null);
            this.definition = definition;
        }

        private TestNpcService(JavaPlugin plugin, JsonObject definition, PlayerNpcRuntime runtime, RuntimeFlowDispatcher dispatcher,
                               Path activeDataRoot) {
            super(plugin, null, null, dispatcher, null, null, null, runtime, null, activeDataRoot);
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
        private final List<JsonObject> functionCalls = new ArrayList<>();

        private RecordingDispatcher() {
            super(null, null);
        }

        @Override
        public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
            flowIds.add(flowId);
            return true;
        }

        @Override
        public boolean dispatchFunction(JsonObject call, Player player, Event event, Map<String, Object> variables) {
            functionCalls.add(call);
            return true;
        }
    }

    private static final class TestPlayerNpcRuntime implements PlayerNpcRuntime {
        private String activeId;
        private Location location;
        private int spawnCount;
        private int despawnCount;
        private int reloadCount;
        private boolean failDespawn;

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
            if (failDespawn) {
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
