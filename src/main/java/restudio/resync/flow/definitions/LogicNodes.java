package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class LogicNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("logic_and", "AND", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("b", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_or", "OR", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("b", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_not", "NOT", NodeDefinition.NodeCategory.LOGIC)
            .input("value", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_xor", "XOR", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("b", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_nand", "NAND", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("b", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_nor", "NOR", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("b", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_true", "True", NodeDefinition.NodeCategory.LOGIC)
            .output("value", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("logic_false", "False", NodeDefinition.NodeCategory.LOGIC)
            .output("value", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_equals", "Equals", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("b", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_not_equals", "Not Equals", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("b", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_greater", "Greater Than", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_less", "Less Than", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_greater_or_equal", "Greater Or Equal", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_less_or_equal", "Less Or Equal", NodeDefinition.NodeCategory.LOGIC)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_between", "Between", NodeDefinition.NodeCategory.LOGIC)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("compare_type", "Check Type", NodeDefinition.NodeCategory.LOGIC)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("result", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
    }
}
