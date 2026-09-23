package restudio.resync.flow;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowItem;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRuntimeValueCodecTest {
    private static final ServerId SERVER = ServerId.parseCanonicalText("11111111-1111-5111-8111-111111111111");
    private static final ServerId FOREIGN = ServerId.parseCanonicalText("22222222-2222-5222-8222-222222222222");
    private World world;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("codec-world");
        player = MockBukkit.getMock().addPlayer();
        player.teleport(new Location(world, 2, 65, 3));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void typedPlayerAndEntityReachRealPropertyHandlersAndWorldOutputs() {
        TypedValue trigger = encode("player", player);
        assertEquals(player.getUniqueId(), property("player", "uuid", decode(trigger)));
        TypedValue next = encode("world", property("player", "world", decode(trigger)));
        assertSame(world, decode(next));
        assertEquals(world.getTime(), property("world", "time", decode(next)));
        TypedValue entity = encode("entity", player);
        assertEquals("PLAYER", property("entity", "type", decode(entity)));
        assertTrue(new CompiledRuntimeContext(null, null, Map.of("player", trigger, "world", next, "entity", entity)).supportedByCompiledCore());
        assertFalse(trigger.value() instanceof Entity);
        assertEquals(SERVER.canonicalText(), ((Map<?, ?>) trigger.value()).get("serverId"));
        assertEquals(world.getUID().toString(), ((Map<?, ?>) trigger.value()).get("worldId"));
    }

    @Test
    void blockPropertiesRoundTripLocationAndWorld() {
        Block block = world.getBlockAt(3, 64, -2);
        world.getChunkAt(0, -1);
        block.setType(Material.STONE);
        TypedValue encoded = encode("block", block);
        assertEquals("STONE", property("block", "type", decode(encoded)));
        Location location = assertInstanceOf(Location.class, decode(encode("location", property("block", "location", decode(encoded)))));
        assertEquals(block.getLocation(), location);
        assertSame(world, decode(encode("world", property("block", "world", decode(encoded)))));
        block.setType(Material.DIRT);
        assertEquals("DIRT", property("block", "type", decode(encoded)));
    }

    @Test
    void eventBlockSnapshotSurvivesBreakAndRetainsLiveActionTarget() {
        world.getChunkAt(0, 0);
        Block block = world.getBlockAt(3, 64, 2);
        block.setType(Material.STONE);
        TypedValue captured = encode("block", block.getState());
        block.setType(Material.AIR);
        Object snapshot = decode(captured);
        assertEquals("STONE", property("block", "type", snapshot));
        assertEquals("Stone", property("block", "display_name", snapshot));
        assertEquals("minecraft:stone", property("block", "key", snapshot));
        assertEquals(false, property("block", "is_air", snapshot));
        assertEquals(block.getLocation(), property("block", "location", snapshot));
        assertEquals("STONE", property("block", "type", decode(encode("block", snapshot))));
        assertEquals(Material.AIR, block.getType());
        assertThrows(IllegalArgumentException.class, () -> decode(changed(captured, "material", "DIRT")));
        PropertyRegistry properties = new PropertyRegistry();
        properties.registerDescriptor(new PropertyRegistry.PropertyDescriptor("block", "type", FlowTypeRef.simple("any"),
            List.of("set"), false, true, false, false, "builtin"));
        HandlerRegistry handlers = new HandlerRegistry();
        JsonFamilyHandler.registerFamilies(handlers, properties);
        FlowNode action = new FlowNode("test", 0, 0, Map.of("target", snapshot, "value", Material.DIRT));
        action.setHandlerConfig(Map.of("property", "type", "action", "set"));
        FlowRuntime runtime = new FlowRuntime(new FlowGraph("test", Map.of("node", action), List.of(), List.of()),
            new TypeAdapterRegistry(), Map.of());
        handlers.getHandler("block").execute(new FlowContext(runtime, player, null), action);
        assertEquals(Material.DIRT, block.getType());
        assertEquals("STONE", property("block", "type", snapshot));
    }

    @Test
    void itemSnapshotPreservesMetadataAndProperties() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Stored Sword");
        meta.setLore(List.of("Preserved Lore"));
        meta.setCustomModelData(42);
        ((Damageable) meta).setDamage(17);
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
        TypedValue encoded = encode("item", property("player", "item_in_mainhand", decode(encode("player", player))));
        ItemStack decoded = assertInstanceOf(ItemStack.class, decode(encoded));
        assertEquals(item, decoded);
        assertEquals("DIAMOND_SWORD", property("itemstack", "type", decoded));
        assertEquals(item.getItemMeta(), decoded.getItemMeta());
    }

    @Test
    void emptyItemStacksBecomeTypedNulls() {
        for (String type : List.of("item", "itemstack")) {
            TypedValue air = encode(type, new ItemStack(Material.AIR));
            assertEquals(TypedValue.State.NULL, air.state());
            assertNull(decode(air));

            ItemStack zero = new ItemStack(Material.STONE);
            zero.setAmount(0);
            TypedValue empty = encode(type, zero);
            assertEquals(TypedValue.State.NULL, empty.state());
            assertNull(decode(empty));
        }
    }

    @Test
    void playerInventoryReferenceResolvesLiveStateThroughPropertyHandlers() {
        TypedValue inventory = encode("inventory", property("player", "inventory", decode(encode("player", player))));
        PlayerInventory live = assertInstanceOf(PlayerInventory.class, decode(inventory));
        assertSame(player.getInventory(), live);
        assertEquals(live.getSize(), property("inventory", "size", live));
        assertSame(player, decode(encode("entity", property("inventory", "holder", live))));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        Object contents = property("inventory", "items", decode(inventory));
        TypedValue items = CompiledRuntimeValueCodec.encode(SERVER, TypeExpr.list(type("item")), contents);
        List<?> decoded = assertInstanceOf(List.class, decode(items));
        assertEquals("DIAMOND", property("itemstack", "type", decoded.getFirst()));
        assertEquals(3, property("itemstack", "amount", decoded.getFirst()));
        TypedValue restored = RuntimeResult.fromCanonical(RuntimeResult.success(inventory).canonicalJson()).value();
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 2));
        assertSame(player.getInventory(), decode(restored));
        assertEquals(Material.EMERALD, ((PlayerInventory) decode(restored)).getItem(0).getType());
    }

    @Test
    void playerInventoryReferencesRejectForeignStaleAndUnsupportedHolders() {
        TypedValue inventory = encode("inventory", player.getInventory());
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.decode(FOREIGN, inventory));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(inventory, "holderId", UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(inventory, "holderKind", "block")));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(inventory, "inventoryType", "CHEST")));
        assertThrows(IllegalArgumentException.class, () -> encode("inventory", MockBukkit.getMock().createInventory(null, 9)));
        assertThrows(IllegalArgumentException.class, () -> encode("inventory", MockBukkit.getMock().createInventory(player, 9)));
        World other = MockBukkit.getMock().addSimpleWorld("inventory-other");
        player.teleport(new Location(other, 1, 65, 1));
        assertThrows(IllegalArgumentException.class, () -> decode(inventory));
    }

    @Test
    void rejectsForeignMissingAndWrongKindReferences() {
        TypedValue encoded = encode("player", player);
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.decode(FOREIGN, encoded));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "serverId", null)));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "uuid", "1-1-1-1-1")));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "entityType", "ZOMBIE")));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "uuid", UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("world"), encoded.value())));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("player"), player.getUniqueId())));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(null, type("player"), player));
    }

    @Test
    void playerHostMapsDecodeAsEntityAndLivingEntityTargets() {
        TypedValue encoded = encode("player", player);
        assertSame(player, decode(TypedValue.value(type("entity"), encoded.value())));
        assertSame(player, decode(TypedValue.value(type("living_entity"), encoded.value())));
        Entity entity = world.spawnEntity(new Location(world, 5, 65, 5), EntityType.ZOMBIE);
        TypedValue living = encode("living_entity", entity);
        assertSame(entity, decode(TypedValue.value(type("entity"), living.value())));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("player"), living.value())));
    }

    @Test
    void rejectsMovedWorldIdentityAndMissingLoadedWorld() {
        TypedValue encoded = encode("entity", player);
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "worldId", UUID.randomUUID().toString())));
        World other = MockBukkit.getMock().addSimpleWorld("other-world");
        player.teleport(new Location(other, 1, 65, 1));
        assertThrows(IllegalArgumentException.class, () -> decode(encoded));
    }

    @Test
    void rejectsWrongEntitySubtypeAndRemovedEntity() {
        Entity entity = world.spawnEntity(new Location(world, 5, 65, 5), EntityType.ARMOR_STAND);
        TypedValue encoded = encode("entity", entity);
        assertThrows(IllegalArgumentException.class, () -> encode("player", entity));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("player"), encoded.value())));
        entity.remove();
        assertThrows(IllegalArgumentException.class, () -> decode(encoded));
    }

    @Test
    void capturesLiveTransientEntityBeforeBukkitMarksItValid() {
        Entity entity = world.spawnEntity(new Location(world, 5, 65, 5), EntityType.ARROW);
        Entity transientEntity = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(),
            new Class<?>[] {Entity.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("isValid")) return false;
                if (method.getName().equals("isDead")) return false;
                try {
                    return method.invoke(entity, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });

        TypedValue encoded = encode("entity", transientEntity);

        assertSame(entity, decode(encoded));
    }

    @Test
    void rejectsMalformedCoordinatesWithoutLoadingChunks() {
        world.getChunkAt(0, 0);
        Block block = world.getBlockAt(0, 64, 0);
        TypedValue encoded = encode("block", block);
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "x", new BigDecimal("1.5"))));
        assertThrows(IllegalArgumentException.class, () -> decode(changed(encoded, "y", world.getMaxHeight())));
        int unloaded = 1_000_000;
        assertFalse(world.isChunkLoaded(unloaded >> 4, unloaded >> 4));
        TypedValue missingChunk = changed(changed(encoded, "x", unloaded), "z", unloaded);
        assertThrows(IllegalArgumentException.class, () -> decode(missingChunk));
        assertFalse(world.isChunkLoaded(unloaded >> 4, unloaded >> 4));
        Location invalid = new Location(world, Double.NaN, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> encode("location", invalid));
    }

    @Test
    void sameShapedAnyAndExtensionMapsAreNotPromotedToBukkitObjects() {
        Object material = encode("player", player).value();
        assertEquals(material, decode(TypedValue.value(type("any"), material)));
        TypeExpr foreignType = TypeExpr.named(TypeReference.of("extension", "player"));
        assertEquals(material, decode(TypedValue.value(foreignType, material)));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, foreignType, player));
        assertEquals(player.getUniqueId(), decode(TypedValue.value(type("uuid"), player.getUniqueId())));
    }

    @Test
    void preservesTypedContainersVariantsAndOrdinaryPortableValues() {
        TypeExpr listType = TypeExpr.list(type("player"));
        TypedValue list = CompiledRuntimeValueCodec.encode(SERVER, listType, List.of(player));
        assertEquals(List.of(player), decode(list));
        TypeExpr optional = TypeExpr.optional(type("player"));
        assertSame(player, decode(CompiledRuntimeValueCodec.encode(SERVER, optional, player)));
        TypeExpr union = TypeExpr.union(List.of(new TypeExpr.UnionVariant("player", type("player")), new TypeExpr.UnionVariant("text", type("string"))));
        TypedValue tagged = TypedValue.unionValue((TypeExpr.UnionType) union, "player", encode("player", player).value());
        assertSame(player, decode(CompiledRuntimeValueCodec.encode(SERVER, union, tagged)));
        IllegalArgumentException untyped = assertThrows(IllegalArgumentException.class, () -> encode("any", List.of(player, "text", 2)));
        assertEquals("Runtime Value Requires An Explicit Host Type", untyped.getMessage());
        assertEquals(Arrays.asList("a", null, 2), decode(encode("any", new Object[] {"a", null, 2})));
        assertEquals("STONE", decode(encode("material", Material.STONE)));
        assertNull(decode(TypedValue.nullValue(type("player"))));
        assertEquals(new Vector(1, 2, 3), decode(encode("vector", new Vector(1, 2, 3))));
        TypedValue anyPlayer = encode("any", player);
        assertEquals(type("any"), anyPlayer.type());
        Object portablePlayer = decode(anyPlayer);
        assertInstanceOf(Map.class, portablePlayer);
        assertSame(player, decode(encode("player", portablePlayer)));
        assertSame(player, decode(encode("player", decode(encode("any", encode("player", player))))));
    }

    @Test
    void rejectsInvalidItemLiteralsAndSnapshotsWithoutAirFallbacks() {
        assertThrows(IllegalArgumentException.class, () -> encode("item", new FlowItem("not-a-material", 1)));
        assertThrows(IllegalArgumentException.class, () -> encode("item", new FlowItem("STONE", 0)));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("item"), Map.of("material", "STONE", "amount", 1))));
        Map<String, Object> invalid = Map.of("format", "paper-item-v1", "data", "not base64", "material", "STONE", "amount", 1);
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("item"), invalid)));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(type("item"), Map.of("format", "paper-item-v1", "data", "A".repeat(1_400_000)))));
    }

    @Test
    void boundedTraversalRejectsCyclesAndExcessiveDepth() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> encode("any", cyclic));
        Object nested = "value";
        for (int index = 0; index < 34; index++) nested = List.of(nested);
        Object excessive = nested;
        assertThrows(IllegalArgumentException.class, () -> encode("any", excessive));
    }

    @Test
    void canonicalRoundTripPreservesDeclaredHostTypesAndLeavesOrdinaryMapsInert() {
        TypedValue players = CompiledRuntimeValueCodec.encode(SERVER, TypeExpr.list(type("player")), List.of(player));
        TypedValue restored = RuntimeResult.fromCanonical(RuntimeResult.success(players).canonicalJson()).value();
        assertEquals(List.of(player), decode(restored));
        Object shapedMap = encode("player", player).value();
        TypedValue ordinary = encode("any", Map.of("plain", shapedMap, "values", List.of("one", true)));
        TypedValue restoredMap = RuntimeResult.fromCanonical(RuntimeResult.success(ordinary).canonicalJson()).value();
        assertEquals(ordinary.value(), decode(restoredMap));
        assertInstanceOf(Map.class, ((Map<?, ?>) decode(restoredMap)).get("plain"));
    }

    @Test
    void rejectsAmbiguousNamedResourceContainersAndWrongOwnerOrType() {
        TypeExpr resource = TypeExpr.resource(TypeReference.of("fixture", "quest"));
        TypeExpr box = TypeExpr.named(TypeReference.of("fixture", "box"), resource);
        for (ServerResourceLocator locator : List.of(locator("fixture", "quest"), locator("fixture", "other"), locator("other", "quest"))) {
            Map<String, Object> payload = Map.of("entry", locator);
            assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, box, payload));
            assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.value(box, payload)));
        }
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, resource, locator("fixture", "other")));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, resource, locator("other", "quest")));
    }

    @Test
    void preservesExplicitResourceContainerIdentityAndNullPolicy() {
        TypeExpr resource = TypeExpr.resource(TypeReference.of("fixture", "quest"));
        ServerResourceLocator locator = locator("fixture", "quest");
        TypeExpr container = TypeExpr.list(TypeExpr.optional(resource));
        TypedValue encoded = CompiledRuntimeValueCodec.encode(SERVER, container, Arrays.asList(locator, null));
        assertEquals(Arrays.asList(locator, null), decode(encoded));
        TypedValue optionalValues = CompiledRuntimeValueCodec.encode(SERVER, container, List.of(Optional.of(locator), Optional.empty()));
        assertEquals(Arrays.asList(locator, null), decode(optionalValues));
        TypedValue restored = RuntimeResult.fromCanonical(RuntimeResult.success(encoded).canonicalJson()).value();
        assertEquals(Arrays.asList(locator, null), decode(restored));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, resource, null));
        assertThrows(IllegalArgumentException.class, () -> decode(TypedValue.nullValue(resource)));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.encode(SERVER, TypeExpr.list(resource), Arrays.asList(locator, null)));
        assertNull(decode(CompiledRuntimeValueCodec.encode(SERVER, TypeExpr.optional(resource), null)));
        assertNull(decode(TypedValue.absent(resource)));
        assertThrows(IllegalArgumentException.class, () -> CompiledRuntimeValueCodec.decode(FOREIGN, encoded));
    }

    private ServerResourceLocator locator(String owner, String type) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of(owner), ResourceTypeId.of(type)), "entry");
    }

    private Object property(String family, String property, Object target) {
        PropertyRegistry properties = new PropertyRegistry();
        properties.registerDescriptor(new PropertyRegistry.PropertyDescriptor(family, property, FlowTypeRef.simple("any"), List.of("get"), true, false, false, false, "builtin"));
        HandlerRegistry handlers = new HandlerRegistry();
        JsonFamilyHandler.registerFamilies(handlers, properties);
        FlowNode node = new FlowNode("test", 0, 0, Map.of("target", target));
        node.setHandlerConfig(Map.of("property", property, "action", "get"));
        FlowGraph graph = new FlowGraph("test", Map.of("node", node), List.of(), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        handlers.getHandler(family).execute(new FlowContext(runtime, player, null), node);
        return runtime.getNodeOutput("node", "value");
    }

    private TypedValue changed(TypedValue value, String key, Object replacement) {
        Map<String, Object> material = new LinkedHashMap<>();
        ((Map<?, ?>) value.value()).forEach((name, entry) -> material.put((String) name, entry));
        if (replacement == null) material.remove(key);
        else material.put(key, replacement);
        return TypedValue.value(value.type(), material);
    }

    private TypedValue encode(String type, Object value) {
        return CompiledRuntimeValueCodec.encode(SERVER, type(type), value);
    }

    private Object decode(TypedValue value) {
        return CompiledRuntimeValueCodec.decode(SERVER, value);
    }

    private static TypeExpr type(String id) {
        return TypeExpr.named(TypeReference.of("builtin", id));
    }
}
