package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ListNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("list_create", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "list", new ArrayList<>());
        });
        
        registry.register("list_of", (ctx, node) -> {
            List<Object> values = ctx.getInputValue(node, "values", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "list", new ArrayList<>(values));
        });
        
        registry.register("list_range", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 10.0);
            Double step = ctx.getInputValue(node, "step", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            
            List<Double> result = new ArrayList<>();
            for (double value = min; value <= max; value += step) {
                result.add(value);
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_repeat", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", null);
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                result.add(value);
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_add", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            result.add(value);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_insert", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            if (index < 0) {
                index = 0;
            } else if (index > result.size()) {
                index = result.size();
            }
            result.add(index, value);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_remove", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            result.remove(value);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_remove_at", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            if (index >= 0 && index < result.size()) {
                result.remove(index.intValue());
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_clear", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>();
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_get", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            Object result = null;
            if (index >= 0 && index < list.size()) {
                result = list.get(index);
            }
            ctx.setNodeOutput(nodeId, "value", result);
        });
        
        registry.register("list_set", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            if (index >= 0 && index < result.size()) {
                result.set(index, value);
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_size", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "size", list.size());
        });
        
        registry.register("list_is_empty", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "empty", list.isEmpty());
        });
        
        registry.register("list_contains", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            ctx.setNodeOutput(nodeId, "contains", list.contains(value));
        });
        
        registry.register("list_index_of", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            int index = list.indexOf(value);
            ctx.setNodeOutput(nodeId, "index", index);
        });
        
        registry.register("list_count", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);
            
            int count = 0;
            for (Object item : list) {
                if (Objects.equals(item, value)) {
                    count++;
                }
            }
            ctx.setNodeOutput(nodeId, "count", count);
        });
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
