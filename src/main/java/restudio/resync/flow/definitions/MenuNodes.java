package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class MenuNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("menu_create", "Create Menu", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("rows", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_set_item", "Set Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("lore", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("flow_to_execute", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_add_item", "Add Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("lore", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("flow_to_execute", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_clear", "Clear Menu", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_open", "Open Menu", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_update", "Update Menu", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_close", "Close Menu", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_set_click_sound", "Set Click Sound", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("sound", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("menu_set_title", "Set Title", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_click_action", "Set Click Action", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("flow_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_item_with_action", "Set Item With Action", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("lore", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("flow_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_enchant", "Set Enchant", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("enchanted", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_flags", "Set Flags", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("flags_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_custom_model", "Set Custom Model", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("model_data", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_head_texture", "Set Head Texture", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("player_name_or_uuid", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_fill_pattern", "Fill Pattern", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("start_slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("end_slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("lore", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_clear_slot", "Clear Slot", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_get_item", "Get Item", NodeDefinition.NodeCategory.INVENTORY)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("slot", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .build());

        registry.register(new NodeDefinition.Builder("menu_get_all_items", "Get All Items", NodeDefinition.NodeCategory.INVENTORY)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("menu_duplicate", "Duplicate", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_close_action", "Set Close Action", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("flow_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_open_action", "Set Open Action", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("flow_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_set_update_interval", "Set Update Interval", NodeDefinition.NodeCategory.INVENTORY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("interval_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("flow_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("menu_get_open_menu_id", "Get Open Menu ID", NodeDefinition.NodeCategory.INVENTORY)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("menu_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
    }
}
