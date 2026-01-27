package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class PlayerQueryNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("player_has_item", "Player Has Item", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("material_or_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("has_item", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("player_count_item", "Player Count Item", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("material_or_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_first_empty_slot", "Player First Empty Slot", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("slot_index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_all_items", "Player Get All Items", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_hotbar_items", "Player Get Hotbar Items", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_armor_items", "Player Get Armor Items", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("helmet", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("chestplate", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("leggings", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("boots", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_inventory_size", "Player Get Inventory Size", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_mainhand_item", "Player Get Mainhand Item", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_offhand_item", "Player Get Offhand Item", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .build());

        registry.register(new NodeDefinition.Builder("player_is_on_ground", "Player Is On Ground", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("on_ground", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("player_is_sleeping", "Player Is Sleeping", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("is_sleeping", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_bed_location", "Player Get Bed Location", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("bed_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_last_damage", "Player Get Last Damage", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("damage_cause", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("damage_source", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_killer", "Player Get Killer", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("killer", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_ping", "Player Get Ping", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("ping_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_lore", "Player Get Lore", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("hand", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("lore_lines_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_display_name", "Player Get Display Name", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("display_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_player_list_name", "Player Get Player List Name", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("list_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("player_is_op", "Player Is OP", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("is_op", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_allowed_flight", "Player Get Allowed Flight", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("can_fly", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
    }
}
