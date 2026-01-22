package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class DebugNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("debug_log", "Debug Log", NodeDefinition.NodeCategory.UTILITY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("level", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("debug_print_variable", "Debug Print Variable", NodeDefinition.NodeCategory.UTILITY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("variable_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());
        
        registry.register(new NodeDefinition.Builder("debug_dump_variables", "Debug Dump Variables", NodeDefinition.NodeCategory.UTILITY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("scope", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("variables", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
        
        registry.register(new NodeDefinition.Builder("debug_stack_trace", "Debug Stack Trace", NodeDefinition.NodeCategory.UTILITY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("stack_trace", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
        
        registry.register(new NodeDefinition.Builder("debug_break", "Debug Break", NodeDefinition.NodeCategory.UTILITY)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
