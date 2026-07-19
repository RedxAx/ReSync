package restudio.resync.flow.handler.event;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.registry.NodeDefinition;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEventRegistryOutputAdaptationTest {
    @Test
    void enumEventValuesAdaptToStableNames() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();

        assertEquals("RIGHT_CLICK_BLOCK", adapters.adapt(Action.RIGHT_CLICK_BLOCK, String.class));
        assertEquals("ZOMBIE", adapters.adapt(EntityType.ZOMBIE, String.class));
    }

    @Test
    void getterChainsResolveMaterialFromBlockState() {
        NodeDefinition definition = definition("event.new_state", FlowDataType.MATERIAL, FlowTypeRef.simple("material"), "event.state.type");
        Function<Event, Map<String, Object>> extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);

        Map<String, Object> variables = extractor.apply(new StateEvent(List.of(new State(Material.WHEAT, null))));

        assertEquals(Material.WHEAT, variables.get("event.new_state"));
    }

    @Test
    void getterChainsMapCollectionElements() {
        Block first = block();
        Block second = block();
        NodeDefinition definition = definition("event.new_state", FlowDataType.LIST, FlowTypeRef.parse("list<block>"), "event.states.block");
        Function<Event, Map<String, Object>> extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);

        Map<String, Object> variables = extractor.apply(new StateEvent(List.of(new State(Material.STONE, first), new State(Material.DIRT, second))));

        List<?> blocks = (List<?>) variables.get("event.new_state");
        assertSame(first, blocks.get(0));
        assertSame(second, blocks.get(1));
    }

    @Test
    void snakeCaseGetterNamesResolveAsJavaProperties() {
        NodeDefinition definition = definition("event.new_state", FlowDataType.MATERIAL, FlowTypeRef.simple("material"), "event.new_state.type");
        Function<Event, Map<String, Object>> extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);

        Map<String, Object> variables = extractor.apply(new StateEvent(List.of(new State(Material.WHEAT, null))));

        assertEquals(Material.WHEAT, variables.get("event.new_state"));
    }

    @Test
    void unavailableGetterDoesNotEscapeBukkitDispatch() {
        NodeDefinition definition = definition("event.value", FlowDataType.STRING, FlowTypeRef.simple("string"), "event.unavailable");
        Function<Event, Map<String, Object>> extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);

        assertTrue(extractor.apply(new StateEvent(List.of(new State(Material.WHEAT, null)))).isEmpty());
    }

    @Test
    void damageEventsResolveTheActingPlayerBeforeTheVictim() {
        FlowEventRegistry registry = new FlowEventRegistry(null);
        Function<Event, Player> extractor = registry.buildPlayerExtractor(DamageEvent.class);
        Player damager = player();
        Player victim = player();

        assertSame(damager, extractor.apply(new DamageEvent(victim, damager)));
        assertSame(victim, extractor.apply(new DamageEvent(victim, entity())));
        assertNull(extractor.apply(new DamageEvent(entity(), entity())));
    }

    private NodeDefinition definition(String target, FlowDataType dataType, FlowTypeRef typeRef, String source) {
        NodeDefinition.PinDefinition output = new NodeDefinition.PinDefinition(target, NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.OUTPUT, dataType, typeRef);
        return new NodeDefinition.Builder("event.test", "Event Test", NodeDefinition.NodeCategory.EVENT)
            .output(output)
            .outputMappings(List.of(new NodeDefinition.PinMapping(source, target)))
            .build();
    }

    private Block block() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> {
            Class<?> type = method.getReturnType();
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            return 0;
        });
    }

    private Player player() {
        return proxy(Player.class);
    }

    private Entity entity() {
        return proxy(Entity.class);
    }

    private <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return 0;
        }));
    }

    private static final class State {
        private final Material type;
        private final Block block;

        private State(Material type, Block block) {
            this.type = type;
            this.block = block;
        }

        public Material getType() {
            return type;
        }

        public Block getBlock() {
            return block;
        }
    }

    private static final class StateEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final List<State> states;

        private StateEvent(List<State> states) {
            this.states = states;
        }

        public State getState() {
            return states.getFirst();
        }

        public State getNewState() {
            return states.getFirst();
        }

        public List<State> getStates() {
            return states;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static final class DamageEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Entity entity;
        private final Entity damager;

        private DamageEvent(Entity entity, Entity damager) {
            this.entity = entity;
            this.damager = damager;
        }

        public Entity getEntity() {
            return entity;
        }

        public Entity getDamager() {
            return damager;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
