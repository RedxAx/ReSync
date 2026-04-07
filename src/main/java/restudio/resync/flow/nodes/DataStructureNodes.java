package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.Set;
import java.util.Stack;
import java.util.Map.Entry;

public class DataStructureNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("map_create", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "map", new HashMap<>());
        });

        registry.register("map_put", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Object key = ctx.getInputValue(node, "key", null);
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>(map);
            result.put(key, value);
            ctx.setNodeOutput(nodeId, "map", result);
        });

        registry.register("map_get", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Object key = ctx.getInputValue(node, "key", null);
            Object defaultValue = ctx.getInputValue(node, "default_value", null);
            String nodeId = findNodeId(ctx, node);

            Object result = map.containsKey(key) ? map.get(key) : defaultValue;
            ctx.setNodeOutput(nodeId, "value", result);
        });

        registry.register("map_remove", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Object key = ctx.getInputValue(node, "key", null);
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>(map);
            result.remove(key);
            ctx.setNodeOutput(nodeId, "map", result);
        });

        registry.register("map_contains_key", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Object key = ctx.getInputValue(node, "key", null);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "contains", map.containsKey(key));
        });

        registry.register("map_contains_value", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Object value = ctx.getInputValue(node, "value", null);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "contains", map.containsValue(value));
        });

        registry.register("map_clear", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>();
            ctx.setNodeOutput(nodeId, "map", result);
        });

        registry.register("map_size", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "size", map.size());
        });

        registry.register("map_is_empty", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "is_empty", map.isEmpty());
        });

        registry.register("map_keys", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            List<Object> keys = new ArrayList<>(map.keySet());
            ctx.setNodeOutput(nodeId, "keys_list", keys);
        });

        registry.register("map_values", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            List<Object> values = new ArrayList<>(map.values());
            ctx.setNodeOutput(nodeId, "values_list", values);
        });

        registry.register("map_entries", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            List<Object> entries = new ArrayList<>();
            for (Entry<Object, Object> entry : map.entrySet()) {
                Map<Object, Object> entryMap = new HashMap<>();
                entryMap.put("key", entry.getKey());
                entryMap.put("value", entry.getValue());
                entries.add(entryMap);
            }
            ctx.setNodeOutput(nodeId, "entries_list", entries);
        });

        registry.register("map_merge", (ctx, node) -> {
            Map<Object, Object> map1 = ctx.getInputValue(node, "map1", Map.class, new HashMap<>());
            Map<Object, Object> map2 = ctx.getInputValue(node, "map2", Map.class, new HashMap<>());
            Boolean overwrite = ctx.getInputValue(node, "overwrite", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>(map1);
            for (Entry<Object, Object> entry : map2.entrySet()) {
                if (overwrite || !result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            ctx.setNodeOutput(nodeId, "merged_map", result);
        });

        registry.register("map_put_all", (ctx, node) -> {
            Map<Object, Object> targetMap = ctx.getInputValue(node, "target_map", Map.class, new HashMap<>());
            Map<Object, Object> sourceMap = ctx.getInputValue(node, "source_map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>(targetMap);
            result.putAll(sourceMap);
            ctx.setNodeOutput(nodeId, "target_map", result);
        });

        registry.register("map_from_lists", (ctx, node) -> {
            List<Object> keys = ctx.getInputValue(node, "keys_list", List.class, new ArrayList<>());
            List<Object> values = ctx.getInputValue(node, "values_list", List.class, new ArrayList<>());
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new LinkedHashMap<>();
            int size = Math.min(keys.size(), values.size());
            for (int i = 0; i < size; i++) {
                result.put(keys.get(i), values.get(i));
            }
            ctx.setNodeOutput(nodeId, "map", result);
        });

        registry.register("map_to_lists", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            List<Object> keys = new ArrayList<>(map.keySet());
            List<Object> values = new ArrayList<>(map.values());
            ctx.setNodeOutput(nodeId, "keys_list", keys);
            ctx.setNodeOutput(nodeId, "values_list", values);
        });

        registry.register("map_filter_by_keys", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            List<Object> filterList = ctx.getInputValue(node, "filter_list", List.class, new ArrayList<>());
            Boolean keep = ctx.getInputValue(node, "keep", Boolean.class, true);
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>();
            for (Entry<Object, Object> entry : map.entrySet()) {
                boolean matches = filterList.contains(entry.getKey());
                if (matches == keep) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            ctx.setNodeOutput(nodeId, "filtered_map", result);
        });

        registry.register("map_filter_by_values", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>();
            for (Entry<Object, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map) {
                    Map<String, Object> valueMap = (Map<String, Object>) value;
                    Object propValue = valueMap.get(propertyName);
                    if (compareValues(propValue, operator, compareValue)) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                } else if (propertyName == null || propertyName.isEmpty()) {
                    if (compareValues(value, operator, compareValue)) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "filtered_map", result);
        });

        registry.register("map_transform_values", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String transformation = ctx.getInputValue(node, "transformation", String.class, "");
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>();
            for (Entry<Object, Object> entry : map.entrySet()) {
                Object transformedValue = transformValue(entry.getValue(), transformation);
                result.put(entry.getKey(), transformedValue);
            }
            ctx.setNodeOutput(nodeId, "transformed_map", result);
        });

        registry.register("map_clone", (ctx, node) -> {
            Map<Object, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String nodeId = findNodeId(ctx, node);

            Map<Object, Object> result = new HashMap<>(map);
            ctx.setNodeOutput(nodeId, "cloned_map", result);
        });

        registry.register("set_create", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "set", new HashSet<>());
        });

        registry.register("set_add", (ctx, node) -> {
            Set<Object> set = ctx.getInputValue(node, "set", Set.class, new HashSet<>());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>(set);
            result.add(element);
            ctx.setNodeOutput(nodeId, "set", result);
        });

        registry.register("set_remove", (ctx, node) -> {
            Set<Object> set = ctx.getInputValue(node, "set", Set.class, new HashSet<>());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>(set);
            result.remove(element);
            ctx.setNodeOutput(nodeId, "set", result);
        });

        registry.register("set_contains", (ctx, node) -> {
            Set<Object> set = ctx.getInputValue(node, "set", Set.class, new HashSet<>());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "contains", set.contains(element));
        });

        registry.register("set_clear", (ctx, node) -> {
            Set<Object> set = ctx.getInputValue(node, "set", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>();
            ctx.setNodeOutput(nodeId, "set", result);
        });

        registry.register("set_size", (ctx, node) -> {
            Set<Object> set = ctx.getInputValue(node, "set", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            ctx.setNodeOutput(nodeId, "size", set.size());
        });

        registry.register("set_union", (ctx, node) -> {
            Set<Object> set1 = ctx.getInputValue(node, "set1", Set.class, new HashSet<>());
            Set<Object> set2 = ctx.getInputValue(node, "set2", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>(set1);
            result.addAll(set2);
            ctx.setNodeOutput(nodeId, "union_set", result);
        });

        registry.register("set_intersection", (ctx, node) -> {
            Set<Object> set1 = ctx.getInputValue(node, "set1", Set.class, new HashSet<>());
            Set<Object> set2 = ctx.getInputValue(node, "set2", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>(set1);
            result.retainAll(set2);
            ctx.setNodeOutput(nodeId, "intersection_set", result);
        });

        registry.register("set_difference", (ctx, node) -> {
            Set<Object> set1 = ctx.getInputValue(node, "set1", Set.class, new HashSet<>());
            Set<Object> set2 = ctx.getInputValue(node, "set2", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            Set<Object> result = new HashSet<>(set1);
            result.removeAll(set2);
            ctx.setNodeOutput(nodeId, "difference_set", result);
        });

        registry.register("set_is_subset", (ctx, node) -> {
            Set<Object> potentialSubset = ctx.getInputValue(node, "potential_subset", Set.class, new HashSet<>());
            Set<Object> superset = ctx.getInputValue(node, "superset", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            boolean isSubset = superset.containsAll(potentialSubset);
            ctx.setNodeOutput(nodeId, "is_subset", isSubset);
        });

        registry.register("set_is_superset", (ctx, node) -> {
            Set<Object> superset = ctx.getInputValue(node, "superset", Set.class, new HashSet<>());
            Set<Object> potentialSubset = ctx.getInputValue(node, "potential_subset", Set.class, new HashSet<>());
            String nodeId = findNodeId(ctx, node);

            boolean isSuperset = superset.containsAll(potentialSubset);
            ctx.setNodeOutput(nodeId, "is_superset", isSuperset);
        });

        registry.register("queue_create", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "queue", new LinkedList<>());
        });

        registry.register("queue_enqueue", (ctx, node) -> {
            LinkedList<Object> queue = ctx.getInputValue(node, "queue", LinkedList.class, new LinkedList<>());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            LinkedList<Object> result = new LinkedList<>(queue);
            result.addLast(element);
            ctx.setNodeOutput(nodeId, "queue", result);
        });

        registry.register("queue_dequeue", (ctx, node) -> {
            LinkedList<Object> queue = ctx.getInputValue(node, "queue", LinkedList.class, new LinkedList<>());
            String nodeId = findNodeId(ctx, node);

            LinkedList<Object> result = new LinkedList<>(queue);
            Object dequeuedElement = result.isEmpty() ? null : result.removeFirst();
            ctx.setNodeOutput(nodeId, "queue", result);
            ctx.setNodeOutput(nodeId, "dequeued_element", dequeuedElement);
        });

        registry.register("queue_peek", (ctx, node) -> {
            LinkedList<Object> queue = ctx.getInputValue(node, "queue", LinkedList.class, new LinkedList<>());
            String nodeId = findNodeId(ctx, node);

            Object frontElement = queue.isEmpty() ? null : queue.getFirst();
            ctx.setNodeOutput(nodeId, "front_element", frontElement);
        });

        registry.register("stack_create", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "stack", new Stack<>());
        });

        registry.register("stack_push", (ctx, node) -> {
            Stack<Object> stack = ctx.getInputValue(node, "stack", Stack.class, new Stack<>());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            Stack<Object> result = (Stack<Object>) stack.clone();
            result.push(element);
            ctx.setNodeOutput(nodeId, "stack", result);
        });

        registry.register("stack_pop", (ctx, node) -> {
            Stack<Object> stack = ctx.getInputValue(node, "stack", Stack.class, new Stack<>());
            String nodeId = findNodeId(ctx, node);

            Stack<Object> result = (Stack<Object>) stack.clone();
            Object poppedElement = result.isEmpty() ? null : result.pop();
            ctx.setNodeOutput(nodeId, "stack", result);
            ctx.setNodeOutput(nodeId, "popped_element", poppedElement);
        });

        registry.register("stack_peek", (ctx, node) -> {
            Stack<Object> stack = ctx.getInputValue(node, "stack", Stack.class, new Stack<>());
            String nodeId = findNodeId(ctx, node);

            Object topElement = stack.isEmpty() ? null : stack.peek();
            ctx.setNodeOutput(nodeId, "top_element", topElement);
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

    private static boolean compareValues(Object value, String operator, Object compareValue) {
        if (value == null || compareValue == null) {
            return switch (operator) {
                case "not_equals" -> value != compareValue;
                case "is_null" -> value == null;
                case "is_not_null" -> value != null;
                default -> false;
            };
        }

        if (value instanceof Number && compareValue instanceof Number) {
            double numValue = ((Number) value).doubleValue();
            double numCompare = ((Number) compareValue).doubleValue();
            return switch (operator) {
                case "equals" -> numValue == numCompare;
                case "not_equals" -> numValue != numCompare;
                case "greater_than" -> numValue > numCompare;
                case "less_than" -> numValue < numCompare;
                case "greater_or_equal" -> numValue >= numCompare;
                case "less_or_equal" -> numValue <= numCompare;
                default -> false;
            };
        }

        if (value instanceof String && compareValue instanceof String) {
            String strValue = (String) value;
            String strCompare = (String) compareValue;
            return switch (operator) {
                case "equals" -> strValue.equals(strCompare);
                case "not_equals" -> !strValue.equals(strCompare);
                case "contains" -> strValue.contains(strCompare);
                case "starts_with" -> strValue.startsWith(strCompare);
                case "ends_with" -> strValue.endsWith(strCompare);
                default -> false;
            };
        }

        return switch (operator) {
            case "equals" -> value.equals(compareValue);
            case "not_equals" -> !value.equals(compareValue);
            default -> false;
        };
    }

    private static Object transformValue(Object value, String transformation) {
        if (!(value instanceof String strValue)) {
            return value;
        }

        return switch (transformation) {
            case "to_upper" -> strValue.toUpperCase();
            case "to_lower" -> strValue.toLowerCase();
            case "reverse" -> new StringBuilder(strValue).reverse().toString();
            case "trim" -> strValue.trim();
            default -> value;
        };
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }
}
