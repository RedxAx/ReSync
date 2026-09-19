package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockBreakEvent;
import restudio.resync.flow.identity.CorrelationId;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRuntimeContextAdapterTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-5111-8111-111111111111"));
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void snapshotsMutableEventValuesAndKeepsReadOnlyIdentityAfterTheWindow() {
        var server = MockBukkit.getMock();
        var player = server.addPlayer();
        var block = server.addSimpleWorld("snapshot").getBlockAt(0, 64, 0);
        block.getWorld().getChunkAt(0, 0);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);
        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(SERVER, player, event,
            Map.of("event.block", block));
        assertTrue(result.accepted(), result.failure());
        block.setType(Material.AIR);
        event.setCancelled(false);
        assertEquals("STONE", ((Map<?, ?>) result.context().variables().get("event.block").value()).get("material"));
        FlowContext context = new FlowContext(null, player, null, null, null, null, CorrelationId.random(),
            result.context(), 0);
        assertEquals("BlockBreakEvent", context.eventType());
        assertTrue(context.isEventCancelled());
        assertFalse(context.isEventMutationOpen());
        assertFalse(context.setEventCancelled(false));
    }

    @Test
    void adaptsPlayerEventAndDeterministicTransportValues() {
        Player player = MockBukkit.getMock().addPlayer();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("event.message", "joined");
        variables.put("event.count", 2);
        variables.put("event.values", Arrays.asList("a", null));
        variables.put("event.player", player);

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            SERVER, player, new PlayerJoinEvent(player, "joined"), variables);

        assertTrue(result.accepted());
        assertEquals(player.getUniqueId(), result.context().player().uniqueId());
        assertEquals(PlayerJoinEvent.class.getName(), result.context().event().type());
        assertEquals(List.of("event.count", "event.message", "event.player", "event.values"), result.context().variables().keySet().stream().toList());
        assertEquals(2, result.context().variables().get("event.count").value());
        assertEquals(Arrays.asList("a", null), result.context().variables().get("event.values").value());
        assertEquals(PlayerJoinEvent.class.getName(), result.context().event().type());
    }

    @Test
    void injectsCanonicalEventPlayerWhenTheHostOmitsIt() {
        Player player = MockBukkit.getMock().addPlayer();
        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            SERVER, player, null, Map.of("event.message", "go"));

        assertTrue(result.accepted(), result.failure());
        assertTrue(result.context().variables().containsKey("event.player"));
        assertEquals(player, CompiledRuntimeValueCodec.decode(SERVER, result.context().variables().get("event.player")));
    }

    @Test
    void preservesTypedEventVariablesAndConvertsLegacyResourcesWithTheInjectedServer() {
        FlowResourceReference legacy = new FlowResourceReference("quest", "main", "fixture");

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            SERVER, null, null, Map.of("event.quest", legacy, "event.message", "ready"));

        assertTrue(result.accepted(), result.failure());
        assertEquals("ready", result.context().variables().get("event.message").value());
        assertEquals(SERVER, ((ServerResourceLocator) result.context().variables().get("event.quest").value()).serverId());
        assertEquals("quest", ((ServerResourceLocator) result.context().variables().get("event.quest").value()).resourceType().value());
    }

    @Test
    void rejectsLegacyResourceContextWithoutAnInjectedServerIdentity() {
        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.quest", new FlowResourceReference("quest", "main", "fixture")));

        assertFalse(result.accepted());
        assertEquals("RESOURCE_REFERENCE_SERVER_REQUIRED", result.failure());
    }

    @Test
    void rejectsPreTypedResourceContextWithoutAnInjectedServerIdentity() {
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("fixture", "quest"));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("fixture"), ResourceTypeId.of("quest")), "main");

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.quest", TypedValue.locator(resourceType, locator)));

        assertFalse(result.accepted());
        assertEquals("RESOURCE_REFERENCE_SERVER_REQUIRED", result.failure());
    }

    @Test
    void transportsOptionalResourcesIntoImmutableCoreMaterial() {
        FlowResourceReference legacy = new FlowResourceReference("quest", "main", "fixture");

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            SERVER, null, null, Map.of("event.present", Optional.of(legacy), "event.empty", Optional.empty()));

        assertTrue(result.accepted(), result.failure());
        assertEquals(Map.of("present", true, "value", new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("fixture"), ResourceTypeId.of("quest")), "main")),
            result.context().variables().get("event.present").value());
        assertEquals(Map.of("present", false), result.context().variables().get("event.empty").value());
    }

    @Test
    void rejectsUnsupportedRuntimeObjects() {
        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.object", new Object()));

        assertFalse(result.accepted());
        assertTrue(result.failure().contains("Unsupported runtime context value"));
    }

    @Test
    void rejectsCyclicTransportContainersWithAControlledFailure() {
        Map<String, Object> cyclicMap = new LinkedHashMap<>();
        cyclicMap.put("self", cyclicMap);
        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        Object[] cyclicArray = new Object[1];
        cyclicArray[0] = cyclicArray;

        for (Object value : List.of(cyclicMap, cyclicList, cyclicArray)) {
            CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
                null, null, Map.of("event.value", value));

            assertFalse(result.accepted());
            assertEquals("RUNTIME_CONTEXT_VALUE_CYCLE", result.failure());
        }
    }

    @Test
    void boundsTransportDepthWithoutRejectingTheSupportedBoundary() {
        Object supported = "value";
        for (int depth = 0; depth < 32; depth++) {
            supported = List.of(supported);
        }
        Object excessive = List.of(supported);

        CompiledRuntimeContextAdapter.Result supportedResult = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.value", supported));
        CompiledRuntimeContextAdapter.Result excessiveResult = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.value", excessive));

        assertTrue(supportedResult.accepted(), supportedResult.failure());
        assertFalse(excessiveResult.accepted());
        assertEquals("RUNTIME_CONTEXT_VALUE_DEPTH_EXCEEDED", excessiveResult.failure());
    }

    @Test
    void boundsTheTotalTransportValueBudgetBeforeContainerAllocation() {
        List<Integer> excessive = Collections.nCopies(8_192, 1);

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.value", excessive));

        assertFalse(result.accepted());
        assertEquals("RUNTIME_CONTEXT_VALUE_BUDGET_EXCEEDED", result.failure());
    }

    @Test
    void preservesSharedAcyclicTransportValues() {
        List<Object> shared = List.of("value", 2);

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            null, null, Map.of("event.value", Map.of("first", shared, "second", shared)));

        assertTrue(result.accepted(), result.failure());
        assertEquals(Map.of("first", shared, "second", shared), result.context().variables().get("event.value").value());
    }

    @Test
    void adaptsStableBukkitValuesWithoutRetainingLiveObjects() {
        MockBukkit.getMock().addSimpleWorld("context-world");
        Player player = MockBukkit.getMock().addPlayer();
        var world = MockBukkit.getMock().getWorld("context-world");
        world.getChunkAt(0, -1);
        var block = world.getBlockAt(3, 64, -2);
        block.setType(Material.STONE);
        ItemStack item = new ItemStack(Material.DIAMOND, 3);
        Location location = new Location(world, 3.5, 64.25, -2.75, 20.0f, -5.0f);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("event.world", world);
        variables.put("event.block", block);
        variables.put("event.entity", player);
        variables.put("event.item", item);
        variables.put("event.location", location);
        variables.put("event.vector", new Vector(1.0, 2.0, 3.0));

        CompiledRuntimeContextAdapter.Result result = CompiledRuntimeContextAdapter.adapt(
            SERVER, player, new PlayerJoinEvent(player, "joined"), variables);

        assertTrue(result.accepted());
        assertTrue(result.context().variables().values().stream()
            .allMatch(value -> value.value() == null || value.value() instanceof Map<?, ?>));
        assertEquals("context-world", ((Map<?, ?>) result.context().variables().get("event.world").value()).get("name"));
        Map<?, ?> worldType = (Map<?, ?>) result.context().variables().get("event.world").type().canonicalValue();
        assertEquals("world", ((Map<?, ?>) worldType.get("type")).get("localId"));
        assertEquals("STONE", ((Map<?, ?>) result.context().variables().get("event.block").value()).get("material"));
        assertEquals(player.getUniqueId().toString(), ((Map<?, ?>) result.context().variables().get("event.entity").value()).get("uuid"));
        assertEquals("DIAMOND", ((Map<?, ?>) result.context().variables().get("event.item").value()).get("material"));
        assertEquals("context-world", ((Map<?, ?>) result.context().variables().get("event.location").value()).get("world"));
        assertEquals(new BigDecimal("1.0"), ((Map<?, ?>) result.context().variables().get("event.vector").value()).get("x"));
        assertEquals(SERVER.canonicalText(), ((Map<?, ?>) result.context().variables().get("event.entity").value()).get("serverId"));
        assertEquals(player, CompiledRuntimeValueCodec.decode(SERVER, result.context().variables().get("event.entity")));
    }

    @Test
    void requiresDeclaredTypesForNestedHostValuesInsteadOfLosingIdentity() {
        Player player = MockBukkit.getMock().addPlayer();
        CompiledRuntimeContextAdapter.Result untyped = CompiledRuntimeContextAdapter.adapt(
            SERVER, player, null, Map.of("event.players", List.of(player)));
        assertFalse(untyped.accepted());
        assertEquals("Runtime Value Requires An Explicit Host Type", untyped.failure());
        TypeExpr type = TypeExpr.list(TypeExpr.named(TypeReference.of("builtin", "player")));
        TypedValue players = CompiledRuntimeValueCodec.encode(SERVER, type, List.of(player));
        CompiledRuntimeContextAdapter.Result typed = CompiledRuntimeContextAdapter.adapt(
            SERVER, player, null, Map.of("event.players", players));
        assertTrue(typed.accepted(), typed.failure());
        assertEquals(List.of(player), CompiledRuntimeValueCodec.decode(SERVER, typed.context().variables().get("event.players")));
    }

}
