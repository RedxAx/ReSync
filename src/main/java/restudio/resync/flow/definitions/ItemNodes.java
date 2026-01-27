package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class ItemNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("item_add_attribute", "Item Add Attribute", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("attribute_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("operation", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_remove_attribute", "Item Remove Attribute", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("attribute_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_get_attributes", "Item Get Attributes", NodeDefinition.NodeCategory.DATA)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("attributes_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_set_trim", "Item Set Trim", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("material_pattern", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("material_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_remove_trim", "Item Remove Trim", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_crossbow_charged", "Item Crossbow Charged", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("charged", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("projectile_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_copy_nbt", "Item Copy NBT", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("target_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("nbt_key", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("target_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_get_nbt", "Item Get NBT", NodeDefinition.NodeCategory.DATA)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("nbt_key", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("nbt_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_set_nbt", "Item Set NBT", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("nbt_key", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("nbt_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_set_damage", "Item Set Damage", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("damage", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_get_damage", "Item Get Damage", NodeDefinition.NodeCategory.DATA)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("damage", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("item_get_material", "Item Get Material", NodeDefinition.NodeCategory.DATA)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_set_max_stack_size", "Item Set Max Stack", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("stack_size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_can_destroy", "Item Can Destroy", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("blocks_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_can_place_on", "Item Can Place On", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("blocks_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("item_set_rarity", "Item Set Rarity", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .input("rarity", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
