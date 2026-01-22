package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class ConversionNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("to_string", "To String", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_number", "To Number", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("number", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_boolean", "To Boolean", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("boolean", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_player", "To Player", NodeDefinition.NodeCategory.DATA)
            .input("uuid_or_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_location", "To Location", NodeDefinition.NodeCategory.DATA)
            .input("x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_item", "To Item", NodeDefinition.NodeCategory.DATA)
            .input("material", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("item", NodeDefinition.PinType.DATA, FlowType.ITEMSTACK)
            .build());
        
        registry.register(new NodeDefinition.Builder("to_list", "To List", NodeDefinition.NodeCategory.DATA)
            .input("value_or_separator", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
    }
}
