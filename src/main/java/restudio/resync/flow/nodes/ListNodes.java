package restudio.resync.flow.nodes;

import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ListNodes {

    @DefineNode(id = "list_create", displayName = "Create List", category = NodeDefinition.NodeCategory.DATA,
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void create(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.setOutput(node, "list", new ArrayList<>());
    }

    @DefineNode(id = "list_of", displayName = "List Of", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "values", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void of(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> values = ctx.getInputValue(node, "values", List.class, List.of());
        ctx.setOutput(node, "list", new ArrayList<>(values));
    }

    @DefineNode(id = "list_range", displayName = "Range", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "min", dataType = FlowType.NUMBER), @FlowPin(name = "max", dataType = FlowType.NUMBER), @FlowPin(name = "step", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void range(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
        Double max = ctx.getInputValue(node, "max", Double.class, 10.0);
        Double step = ctx.getInputValue(node, "step", Double.class, 1.0);
        List<Double> result = new ArrayList<>();
        for (double value = min; value <= max; value += step) {
            result.add(value);
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_repeat", displayName = "Repeat", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.ANY), @FlowPin(name = "count", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void repeat(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Object value = ctx.getInputValue(node, "value", null);
        Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(value);
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_add", displayName = "Add", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void add(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
        Object value = ctx.getInputValue(node, "value", null);
        List<Object> result = new ArrayList<>(list);
        result.add(value);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_insert", displayName = "Insert", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "index", dataType = FlowType.NUMBER), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void insert(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
        Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
        Object value = ctx.getInputValue(node, "value", null);
        List<Object> result = new ArrayList<>(list);
        int idx = Math.max(0, Math.min(index, result.size()));
        result.add(idx, value);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_remove", displayName = "Remove", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void remove(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
        Object value = ctx.getInputValue(node, "value", null);
        List<Object> result = new ArrayList<>(list);
        result.remove(value);
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_remove_at", displayName = "Remove At", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "index", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void removeAt(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
        Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
        List<Object> result = new ArrayList<>(list);
        if (index >= 0 && index < result.size()) {
            result.remove(index.intValue());
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_clear", displayName = "Clear", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void clear(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.setOutput(node, "list", new ArrayList<>());
    }

    @DefineNode(id = "list_get", displayName = "Get", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "index", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "value", dataType = FlowType.ANY)})
    public void get(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
        ctx.setOutput(node, "value", index >= 0 && index < list.size() ? list.get(index) : null);
    }

    @DefineNode(id = "list_set", displayName = "Set", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "index", dataType = FlowType.NUMBER), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "list", dataType = FlowType.LIST)})
    public void set(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
        Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
        Object value = ctx.getInputValue(node, "value", null);
        List<Object> result = new ArrayList<>(list);
        if (index >= 0 && index < result.size()) {
            result.set(index, value);
        }
        ctx.setOutput(node, "list", result);
    }

    @DefineNode(id = "list_size", displayName = "Size", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "size", dataType = FlowType.NUMBER)})
    public void size(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        ctx.setOutput(node, "size", list.size());
    }

    @DefineNode(id = "list_is_empty", displayName = "Is Empty", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {@FlowPin(name = "empty", dataType = FlowType.BOOLEAN)})
    public void isEmpty(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        ctx.setOutput(node, "empty", list.isEmpty());
    }

    @DefineNode(id = "list_contains", displayName = "Contains", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "contains", dataType = FlowType.BOOLEAN)})
    public void contains(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Object value = ctx.getInputValue(node, "value", null);
        ctx.setOutput(node, "contains", list.contains(value));
    }

    @DefineNode(id = "list_index_of", displayName = "Index Of", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "index", dataType = FlowType.NUMBER)})
    public void indexOf(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Object value = ctx.getInputValue(node, "value", null);
        ctx.setOutput(node, "index", list.indexOf(value));
    }

    @DefineNode(id = "list_count", displayName = "Count", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "count", dataType = FlowType.NUMBER)})
    public void count(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
        Object value = ctx.getInputValue(node, "value", null);
        int count = 0;
        for (Object item : list) {
            if (Objects.equals(item, value)) {
                count++;
            }
        }
        ctx.setOutput(node, "count", count);
    }
}
