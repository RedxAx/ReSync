package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class ParticleNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("particle_spawn", "Spawn Particle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("offset_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("speed", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_area", "Particle Area", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("min_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("max_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("density", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_line", "Particle Line", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("start_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("end_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("density", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_circle", "Particle Circle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("points", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_sphere", "Particle Sphere", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("center_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("points", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_block_dust", "Block Dust Particle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("block_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_item_break", "Item Break Particle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("item_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("particle_explosion", "Explosion Particle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("particle_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("large", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
