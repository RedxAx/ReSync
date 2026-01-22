package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.*;
import java.util.stream.Collectors;

public class ListTransformNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("list_slice", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer end = ctx.getInputValue(node, "end", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            if (start < 0) {
                start = list.size() + start;
            }
            if (end < 0) {
                end = list.size() + end;
            }
            if (start < 0) start = 0;
            if (end < 0) end = 0;
            if (start > list.size()) start = list.size();
            if (end > list.size()) end = list.size();
            
            List<Object> result = new ArrayList<>();
            for (int i = start; i < end; i++) {
                result.add(list.get(i));
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_sublist", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            if (start < 0) {
                start = list.size() + start;
            }
            if (start < 0) start = 0;
            if (start > list.size()) start = list.size();
            
            int end = Math.min(start + count, list.size());
            List<Object> result = new ArrayList<>();
            for (int i = start; i < end; i++) {
                result.add(list.get(i));
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_reverse", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            Collections.reverse(result);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_shuffle", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            Collections.shuffle(result);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_sort", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            Collections.sort(result, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                if (a instanceof Comparable && b instanceof Comparable) {
                    return ((Comparable<Object>) a).compareTo(b);
                }
                return String.valueOf(a).compareTo(String.valueOf(b));
            });
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_sort_descending", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(list);
            Collections.sort(result, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return -1;
                if (b == null) return 1;
                if (a instanceof Comparable && b instanceof Comparable) {
                    return ((Comparable<Object>) b).compareTo(a);
                }
                return String.valueOf(b).compareTo(String.valueOf(a));
            });
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_filter", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = list.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_map", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item != null ? String.valueOf(item) : null);
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_reduce", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object initial = ctx.getInputValue(node, "initial", null);
            String nodeId = findNodeId(ctx, node);
            
            Object result = initial;
            for (Object item : list) {
                if (item instanceof Number && result instanceof Number) {
                    result = ((Number) result).doubleValue() + ((Number) item).doubleValue();
                } else {
                    result = result != null ? result.toString() + item.toString() : item.toString();
                }
            }
            ctx.setNodeOutput(nodeId, "value", result);
        });
        
        registry.register("list_flatten", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof List) {
                    result.addAll((List<?>) item);
                } else {
                    result.add(item);
                }
            }
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_unique", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(new LinkedHashSet<>(list));
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_join", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String separator = ctx.getInputValue(node, "separator", String.class, ",");
            String nodeId = findNodeId(ctx, node);
            
            String result = list.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(separator));
            ctx.setNodeOutput(nodeId, "string", result);
        });
        
        registry.register("list_concat", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>(listA);
            result.addAll(listB);
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_intersect", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = listA.stream()
                .filter(listB::contains)
                .distinct()
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_difference", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = listA.stream()
                .filter(item -> !listB.contains(item))
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "list", result);
        });
        
        registry.register("list_zip", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            List<Object> result = new ArrayList<>();
            int minSize = Math.min(listA.size(), listB.size());
            for (int i = 0; i < minSize; i++) {
                Map<String, Object> pair = new HashMap<>();
                pair.put("first", listA.get(i));
                pair.put("second", listB.get(i));
                result.add(pair);
            }
            ctx.setNodeOutput(nodeId, "list", result);
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
