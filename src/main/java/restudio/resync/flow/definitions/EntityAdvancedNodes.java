package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class EntityAdvancedNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("entity_mount", "Entity Mount", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("mount_entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_dismount", "Entity Dismount", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_ai_disable", "Entity AI Disable", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_ai_enable", "Entity AI Enable", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_set_no_damage", "Entity Set No Damage", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("no_damage", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_set_silent", "Entity Set Silent", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("silent", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_add_potion", "Entity Add Potion", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("effect_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("duration", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("amplifier", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_clear_potions", "Entity Clear Potions", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_leash", "Entity Leash", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("holder_entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_unleash", "Entity Unleash", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_set_custom_name", "Entity Set Custom Name", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_get_passengers", "Entity Get Passengers", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("passengers_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_get_vehicle", "Entity Get Vehicle", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("vehicle", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_set_fire_ticks", "Entity Set Fire Ticks", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("entity_set_frozen", "Entity Set Frozen", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("entity_add_tag", "Entity Add Tag", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("tag", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("entity_remove_tag", "Entity Remove Tag", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("tag", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("entity_clear_tags", "Entity Clear Tags", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("entity_has_tag", "Entity Has Tag", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("tag", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("has_tag", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("entity_has_any_tag", "Entity Has Any Tag", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("tags", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("has_any", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("entity_has_all_tags", "Entity Has All Tags", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("tags", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("has_all", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("entity_get_tags", "Entity Get Tags", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("tags", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
    }
}
