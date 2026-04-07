package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.*;
import java.util.stream.Collectors;

public class ListTransformNodes {

    @DefineNode(id = "list_slice", displayName = "Slice", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "start", dataType = FlowType.NUMBER), @FlowPin(name = "end", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void slice(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
        Integer end = ctx.getInputValue(node, "end", Integer.class, 0);
        if (start < 0) start = list.size() + start;
        if (end < 0) end = list.size() + end;
        if (start < 0) start = 0;
        if (end < 0) end = 0;
        if (start > list.size()) start = list.size();
        if (end > list.size()) end = list.size();
        List<Object> result = new ArrayList<>();
        for (int i = start; i < end; i++) {
            result.add(list.get(i));
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_sublist", displayName = "Sublist", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "start", dataType = FlowType.NUMBER), @FlowPin(name = "count", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void sublist(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
        Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
        if (start < 0) start = list.size() + start;
        if (start < 0) start = 0;
        if (start > list.size()) start = list.size();
        int end = Math.min(start + count, list.size());
        List<Object> result = new ArrayList<>();
        for (int i = start; i < end; i++) {
            result.add(list.get(i));
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_reverse", displayName = "Reverse", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void reverse(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        List<Object> result = new ArrayList<>(list);
        Collections.reverse(result);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_shuffle", displayName = "Shuffle", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void shuffle(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        List<Object> result = new ArrayList<>(list);
        Collections.shuffle(result);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_sort", displayName = "Sort", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void sort(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
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
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_sort_descending", displayName = "Sort Descending", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void sortDescending(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
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
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_filter", displayName = "Filter Nulls", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void filter(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        ctx.setOutput(node, "list", list.stream().filter(Objects::nonNull).collect(Collectors.toList()));
    }

    @DefineNode(id = "list_map", displayName = "Map to String", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void map(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            result.add(item != null ? String.valueOf(item) : null);
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_reduce", displayName = "Reduce", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "initial", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "value", dataType = FlowType.ANY)})
    public void reduce(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Object initial = ctx.getInputValue(node, "initial", null);
        Object result = initial;
        for (Object item : list) {
            if (item instanceof Number && result instanceof Number) {
                result = ((Number) result).doubleValue() + ((Number) item).doubleValue();
            } else {
                result = result != null ? result.toString() + item.toString() : item.toString();
            }
        }
        ctx.setOutput(node, "value", result);
    }

    @DefineNode(id = "list_flatten", displayName = "Flatten", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void flatten(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof List) {
                result.addAll((List<?>) item);
            } else {
                result.add(item);
            }
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_unique", displayName = "Unique", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void unique(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        ctx.setOutput(node, "list", new ArrayList<>(new LinkedHashSet<>(list)));
    }

    @DefineNode(id = "list_join", displayName = "Join", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "separator", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "string", dataType = FlowType.STRING)})
    public void join(FlowContext ctx, FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        String separator = ctx.getInputValue(node, "separator", String.class, ",");
        ctx.setOutput(node, "string", list.stream().map(String::valueOf).collect(Collectors.joining(separator)));
    }

    @DefineNode(id = "list_concat", displayName = "Concat", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "listA", dataType = FlowType.LIST), @FlowPin(name = "listB", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void concat(FlowContext ctx, FlowNode node) {
        List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
        List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
        List<Object> result = new ArrayList<>(listA);
        result.addAll(listB);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_intersect", displayName = "Intersect", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "listA", dataType = FlowType.LIST), @FlowPin(name = "listB", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void intersect(FlowContext ctx, FlowNode node) {
        List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
        List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
        ctx.setOutput(node, "list", listA.stream().filter(listB::contains).distinct().collect(Collectors.toList()));
    }

    @DefineNode(id = "list_difference", displayName = "Difference", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "listA", dataType = FlowType.LIST), @FlowPin(name = "listB", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void difference(FlowContext ctx, FlowNode node) {
        List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
        List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
        ctx.setOutput(node, "list", listA.stream().filter(item -> !listB.contains(item)).collect(Collectors.toList()));
    }

    @DefineNode(id = "list_zip", displayName = "Zip", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "listA", dataType = FlowType.LIST), @FlowPin(name = "listB", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void zip(FlowContext ctx, FlowNode node) {
        List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
        List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
        List<Object> result = new ArrayList<>();
        int minSize = Math.min(listA.size(), listB.size());
        for (int i = 0; i < minSize; i++) {
            Map<String, Object> pair = new HashMap<>();
            pair.put("first", listA.get(i));
            pair.put("second", listB.get(i));
            result.add(pair);
        }
        ctx.setOutput(node, "list", result);
    }
}
