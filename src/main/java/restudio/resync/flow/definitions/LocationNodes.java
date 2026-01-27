package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class LocationNodes implements NodeDefinitionCategory {
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("location_add", "Location Add", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("offset_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_multiply", "Location Multiply", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("factor", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_direction", "Location Direction", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_relative_to_entity", "Location Relative To Entity", NodeDefinition.NodeCategory.DATA)
            .input("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .input("offset_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("offset_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_look_at", "Location Look At", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("target_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_get_offset", "Location Get Offset", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("direction", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_center", "Location Center", NodeDefinition.NodeCategory.DATA)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("location_distance", "Location Distance", NodeDefinition.NodeCategory.DATA)
            .input("location1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("location2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());
    }
}
