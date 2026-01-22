package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class CustomEventNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("custom_event_emit", "Custom Event Emit", NodeDefinition.NodeCategory.EVENT)
            .input("event_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("data_payload", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("custom_event_listen", "Custom Event Listen", NodeDefinition.NodeCategory.EVENT)
            .input("event_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("timeout_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("listening", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("next", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("custom_event_clear", "Custom Event Clear", NodeDefinition.NodeCategory.EVENT)
            .input("event_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("custom_event_get_data", "Custom Event Get Data", NodeDefinition.NodeCategory.EVENT)
            .input("event_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("data_key", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
