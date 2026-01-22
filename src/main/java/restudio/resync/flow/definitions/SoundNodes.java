package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class SoundNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("sound_play", "Play Sound", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("sound_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("volume", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("sound_play_for_player", "Play Sound for Player", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("sound_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("volume", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("sound_play_for_all", "Play Sound for All", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("sound_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("volume", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("sound_stop", "Stop Sound", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("sound", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("sound_stop_all", "Stop All Sounds", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
