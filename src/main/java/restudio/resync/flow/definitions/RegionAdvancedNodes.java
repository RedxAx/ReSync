package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class RegionAdvancedNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("region_clone", "Region Clone", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_save", "Region Save", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("file_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_load", "Region Load", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("file_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_scale", "Region Scale", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("scale_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("scale_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("scale_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_flip", "Region Flip", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("clipboard_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("axis", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_set", "Region Set", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_set_pattern", "Region Set Pattern", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("materials_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_cylinder", "Region Cylinder", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("height", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("is_filled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_sphere", "Region Sphere", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("is_filled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_pyramid", "Region Pyramid", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("base_center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("height", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("is_hollow", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_circle", "Region Circle", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("axis", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("thickness", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_walls_corners", "Region Walls Corners", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner3", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner4", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner5", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner6", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner7", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("corner8", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("region_center", "Region Center", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("region_size", "Region Size", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("size_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("size_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("size_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("region_volume", "Region Volume", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("volume", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("region_get_blocks", "Region Get Blocks", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("blocks_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("region_get_blocks_by_type", "Region Get Blocks By Type", NodeDefinition.NodeCategory.DATA)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("blocks_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("region_replace_data", "Region Replace Data", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("from_block_data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("to_block_data", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_smooth", "Region Smooth", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("iterations", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_raise", "Region Raise", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("region_lower", "Region Lower", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
