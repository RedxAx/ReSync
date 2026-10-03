package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.NodeHandler;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameQueryCompatibilityTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void materialNamesCountEveryMatchingStackWhileItemsPreserveMetadataMatching() {
        Player player = MockBukkit.getMock().addPlayer();
        ItemStack plain = new ItemStack(Material.STONE, 4);
        ItemStack named = new ItemStack(Material.STONE, 7);
        var meta = named.getItemMeta();
        meta.displayName(Component.text("Named Stone"));
        named.setItemMeta(meta);
        player.getInventory().setItem(0, plain);
        player.getInventory().setItem(1, named);
        RestoredNodeHandler handler = new RestoredNodeHandler();

        assertEquals(11, execute(handler, "player_count_item", Map.of("player", player, "material_or_item", "minecraft:stone")).outputs.get("count"));
        assertEquals(11, execute(handler, "player_count_item", Map.of("player", player, "material_or_item", Material.STONE)).outputs.get("count"));
        assertEquals(4, execute(handler, "player_count_item", Map.of("player", player, "material_or_item", new ItemStack(Material.STONE))).outputs.get("count"));
        assertEquals(7, execute(handler, "player_count_item", Map.of("player", player, "material_or_item", named)).outputs.get("count"));
    }

    @Test
    void lastDamageReportsTheAttackerAndEnvironmentalDamageHasNoEntitySource() {
        Player victim = MockBukkit.getMock().addPlayer();
        Player attacker = MockBukkit.getMock().addPlayer();
        RestoredNodeHandler handler = new RestoredNodeHandler();
        victim.setLastDamageCause(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2));

        assertSame(attacker, execute(handler, "player_get_last_damage", Map.of("player", victim)).outputs.get("damage_source"));
        victim.setLastDamageCause(new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 2));
        Context environmental = execute(handler, "player_get_last_damage", Map.of("player", victim));
        assertEquals("FALL", environmental.outputs.get("damage_cause"));
        assertTrue(environmental.outputs.containsKey("damage_source"));
        assertEquals(null, environmental.outputs.get("damage_source"));
    }

    @Test
    void nearbyMobsCannotPublishPlayersItemsOrProjectilesAsLivingEntities() {
        Entity item = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
            (proxy, method, arguments) -> method.getName().equals("getType") ? EntityType.ITEM : null);
        LivingEntity mob = (LivingEntity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {LivingEntity.class},
            (proxy, method, arguments) -> method.getName().equals("getType") ? EntityType.ZOMBIE : null);
        Player player = MockBukkit.getMock().addPlayer();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, arguments) -> method.getName().equals("getNearbyEntities") ? List.of(item, mob, player) : null);

        Context context = execute(new EntityActionHandler(), "entity_get_mob_nearby", Map.of("center", new Location(world, 0, 64, 0), "radius", 10.0));

        List<?> mobs = (List<?>) context.outputs.get("mobs");
        assertEquals(1, mobs.size());
        assertSame(mob, mobs.getFirst());
    }

    private Context execute(NodeHandler handler, String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode(operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        Context context = new Context(inputs);
        handler.execute(context, node);
        return context;
    }

    private static final class Context extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

        private Context(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String name, Class<T> type, T fallback) {
            Object value = inputs.get(name);
            return value == null ? fallback : type.cast(value);
        }

        @Override
        public void setOutput(FlowNode node, String name, Object value) {
            outputs.put(name, value);
        }

        @Override
        public void triggerOutput(String name) {
        }
    }
}
