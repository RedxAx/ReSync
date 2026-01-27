package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class BlockNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("block_set", "Set Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_get", "Get Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("block_replace", "Replace Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("old_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_fill", "Fill Area", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_replace_area", "Replace Area", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("old_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_break_naturally", "Break Naturally", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("cause", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_drop_item", "Drop Item", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_update", "Update Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_biome", "Set Biome", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("biome", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_type", "Set Block Type", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_get_type", "Get Block Type", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_data", "Set Block Data", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("data", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_get_data", "Get Block Data", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_age", "Set Block Age", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("age", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_level", "Set Block Level", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("level", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_rotation", "Set Rotation", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("rotation", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_face", "Set Face", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("face", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_powered", "Set Powered", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("powered", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_lit", "Set Lit", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("lit", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_interact", "Interact Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_break_particles", "Break Particles", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_play_sound", "Play Block Sound", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("volume", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_physics", "Block Physics", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_explode", "Block Explode", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("power", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("fire", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("break_blocks", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_raytrace", "Block Raytrace", NodeDefinition.NodeCategory.DATA)
            .input("start_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("direction_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("hit_block", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("hit_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("block_offset", "Block Offset", NodeDefinition.NodeCategory.DATA)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("offset_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("block", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("block_sign_text", "Block Sign Text", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("line1", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("line2", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("line3", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("line4", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_container_get", "Container Get", NodeDefinition.NodeCategory.DATA)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("block_container_set", "Container Set", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_container_add", "Container Add", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_spawn_falling", "Spawn Falling Block", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("falling_block_entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .build());

        registry.register(new NodeDefinition.Builder("block_break_naturally_drops", "Break Naturally", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("tool_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("dropped_items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("block_break_instantly", "Break Instantly", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_get_drops", "Get Drops", NodeDefinition.NodeCategory.DATA)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("tool_item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .output("dropped_items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("block_get_state", "Get State", NodeDefinition.NodeCategory.DATA)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("state_data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("block_set_state", "Set State", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("state_data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("block_is_solid", "Is Solid", NodeDefinition.NodeCategory.DATA)
            .input("block_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("is_solid", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
    }
}
