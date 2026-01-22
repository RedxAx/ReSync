package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class FlowControlNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("switch_case", "Switch Case", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("cases", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("matched", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("case_0", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("case_1", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("case_2", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("case_3", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("default", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("branch_random", "Branch Random", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("branches", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("selected", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("branch_0", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_1", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_2", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_3", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("branch_all", "Branch All", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_0", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_1", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_2", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("branch_3", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("loop_count", "Loop Count", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("loop", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("completed", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("loop_for_each", "Loop For Each", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("loop", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("completed", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("loop_for_each_player", "Loop Players", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("loop", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("completed", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("loop_for_each_entity", "Loop Entities", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("radius", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("center", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("entity", NodeDefinition.PinType.DATA, FlowType.ENTITY)
            .output("loop", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("completed", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("break_loop", "Break Loop", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("continue_loop", "Continue Loop", NodeDefinition.NodeCategory.LOGIC)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
