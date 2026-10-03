package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityEffectHandlerAreaTest {
    private World world;
    private AbilityEffectHandler handler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("area-world");
        world.loadChunk(0, 0);
        handler = new AbilityEffectHandler();
    }

    @AfterEach
    void tearDown() {
        try {
            if (handler != null) handler.shutdown();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void sphereFiltersCornersBeforeSortingAndLimitingWhileRadiusKeepsItsBox() {
        Location center = new Location(world, 10, 64, 10);
        Entity inside = world.spawnEntity(new Location(world, 13, 64, 10), EntityType.ZOMBIE);
        Entity boundary = world.spawnEntity(new Location(world, 13, 64, 14), EntityType.ZOMBIE);
        Entity corner = world.spawnEntity(new Location(world, 14, 64, 14), EntityType.ZOMBIE);
        inside.setFireTicks(0);
        boundary.setFireTicks(0);
        corner.setFireTicks(0);
        Map<String, Object> values = Map.of("location", center, "radius", 5.0, "target_filter", "living_entity", "exclude_caster", false,
            "shape", "sphere", "mode", "ignite", "duration_ticks", 40, "sort", "farthest", "limit", 1);
        FlowNode area = node("area_effect", values);
        Context effect = new Context();

        handler.execute(effect, area);

        assertEquals(List.of(boundary), effect.outputs.get("entities"));
        assertEquals(1, effect.outputs.get("affected"));
        assertEquals(40, boundary.getFireTicks());
        assertEquals(0, inside.getFireTicks());
        assertEquals(0, corner.getFireTicks());
        assertEquals(List.of("flow"), effect.branches);

        FlowNode radius = node("entity_query", Map.of("location", center, "radius", 5.0, "mode", "radius", "target_filter", "living_entity"));
        Context query = new Context();
        handler.execute(query, radius);

        assertTrue(((List<?>) query.outputs.get("entities")).contains(corner));
    }

    private FlowNode node(String operation, Map<String, Object> values) {
        FlowNode node = new FlowNode("ability_" + operation, 0, 0, values);
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }

    private static final class Context extends FlowContext {
        private final Map<String, Object> outputs = new HashMap<>();
        private final List<String> branches = new ArrayList<>();

        private Context() {
            super(null, null, null);
        }

        @Override
        public <T> T getInputValue(FlowNode node, String name, Class<T> type, T fallback) {
            Object value = node.getInputValues().get(name);
            return value == null ? fallback : type.cast(value);
        }

        @Override
        public void setOutput(FlowNode node, String name, Object value) {
            outputs.put(name, value);
        }

        @Override
        public void triggerOutput(String name) {
            branches.add(name);
        }
    }
}
