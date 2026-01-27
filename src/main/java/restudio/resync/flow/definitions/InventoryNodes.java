package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class InventoryNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("inventory_open_gui", "Open GUI", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("rows", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_close", "Close GUI", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_set_title", "Set Title", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_set_rows", "Set Rows", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("rows", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_get_contents", "Get Contents", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("contents", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_set_contents", "Set Contents", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("contents", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_add_item", "Add Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_remove_item", "Remove Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_has_item", "Has Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("has", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_count_item", "Count Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_get_slot", "Get Slot", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_set_slot", "Set Slot", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_clear_slot", "Clear Slot", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_move_item", "Move Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("from_slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("to_slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_swap_items", "Swap Items", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("slot1", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("slot2", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_clear", "Clear All", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("inventory_update", "Update", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_has_space", "Inventory Has Space", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("has_space", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_count_item", "Inventory Count Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("material_or_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_get_first_empty", "Get First Empty", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("slot_index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_sort", "Inventory Sort", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_get_all", "Get All Items", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_clear", "Inventory Clear", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_size", "Inventory Size", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_get_storage_contents", "Get Storage Contents", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_get_max_stack_size", "Get Max Stack Size", NodeDefinition.NodeCategory.INVENTORY)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("max_size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_contains_at_least", "Contains At Least", NodeDefinition.NodeCategory.INVENTORY)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("material_or_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("contains", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_remove_any", "Remove Any", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("material_or_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_set_contents", "Set Contents", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("inventory_add_to_slot", "Add To Slot", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("inventory", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
