package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class TitleNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("title_send", "Send Title", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("subtitle", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("fade_in", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("stay", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("fade_out", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("title_clear", "Clear Title", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("title_action_bar", "Show Action Bar", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("duration_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("title_times", "Set Title Times", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("fade_in", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("stay", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("fade_out", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("times", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("title_subtitle", "Send Subtitle", NodeDefinition.NodeCategory.VISUAL)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("subtitle", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("fade_in", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("stay", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("fade_out", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
