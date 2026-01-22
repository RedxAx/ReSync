package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class FileNodes implements NodeDefinitionCategory {
    
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("file_write", "Write File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_append", "Append File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_read", "Read File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_read_lines", "Read File Lines", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("lines", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_delete", "Delete File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_exists", "File Exists", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("exists", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_copy", "Copy File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("dest_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_move", "Move File", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("dest_path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_list_dir", "List Directory", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("files", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_create_dir", "Create Directory", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
        
        registry.register(new NodeDefinition.Builder("file_get_size", "Get File Size", NodeDefinition.NodeCategory.DATABASE)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());
    }
}
