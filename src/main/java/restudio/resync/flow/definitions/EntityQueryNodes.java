package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class EntityQueryNodes implements NodeDefinitionCategory {
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("entity_is_alive", "Entity Is Alive", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("is_alive", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("is_valid", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("is_dead", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_info", "Entity Info", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("entity_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("uuid", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("custom_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("ticks_lived", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("is_dead", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("is_valid", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_health", "Entity Health", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("health", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("max_health", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("absorption", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_velocity", "Entity Velocity", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("velocity", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_fire_ticks", "Entity Fire Ticks", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("fire_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_freeze_ticks", "Entity Freeze Ticks", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("freeze_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_last_damage", "Entity Last Damage", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("damage", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("cause", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("damager", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_location", "Entity Location", NodeDefinition.NodeCategory.ENTITY)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());
    }
}
