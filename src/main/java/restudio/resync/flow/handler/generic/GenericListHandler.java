package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class GenericListHandler implements NodeHandler {

    private static final Random RANDOM = new Random();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public GenericListHandler() {
        registerBasicOperations();
        registerTransformOperations();
        registerAdvancedOperations();
    }

    private void registerBasicOperations() {
        operations.put("create", (ctx, node) -> {
            ctx.setOutput(node, "list", new ArrayList<>());
        });
        operations.put("add", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            Object item = ctx.getInputValue(node, "value", null);
            list.add(item);
            ctx.setOutput(node, "list", list);
        });
        operations.put("add_at", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            Object item = ctx.getInputValue(node, "value", null);
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            if (index >= 0 && index <= list.size()) {
                list.add(index, item);
            }
            ctx.setOutput(node, "list", list);
        });
        operations.put("remove", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            Object item = ctx.getInputValue(node, "value", null);
            list.remove(item);
            ctx.setOutput(node, "list", list);
        });
        operations.put("remove_at", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            if (index >= 0 && index < list.size()) {
                list.remove((int) index);
            }
            ctx.setOutput(node, "list", list);
        });
        operations.put("get", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            Object item = (index >= 0 && index < list.size()) ? list.get(index) : null;
            ctx.setOutput(node, "value", item);
        });
        operations.put("index_of", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object item = ctx.getInputValue(node, "value", null);
            ctx.setOutput(node, "index", list.indexOf(item));
        });
        operations.put("contains", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object item = ctx.getInputValue(node, "value", null);
            ctx.setOutput(node, "contains", list.contains(item));
        });
        operations.put("size", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.setOutput(node, "size", list.size());
        });
        operations.put("is_empty", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.setOutput(node, "empty", list.isEmpty());
        });
        operations.put("clear", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            list.clear();
            ctx.setOutput(node, "list", list);
        });
        operations.put("set", (ctx, node) -> {
            List<Object> list = mutableList(ctx, node);
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            Object item = ctx.getInputValue(node, "value", null);
            if (index >= 0 && index < list.size()) {
                list.set(index, item);
            }
            ctx.setOutput(node, "list", list);
        });
        operations.put("first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.setOutput(node, "item", list.isEmpty() ? null : list.get(0));
        });
        operations.put("last", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.setOutput(node, "item", list.isEmpty() ? null : list.get(list.size() - 1));
        });
        operations.put("random", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object item = list.isEmpty() ? null : list.get(RANDOM.nextInt(list.size()));
            ctx.setOutput(node, "item", item);
        });
    }

    private void registerTransformOperations() {
        operations.put("slice", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer start = ctx.getInputValue(node, "start_index", Integer.class, 0);
            Integer end = ctx.getInputValue(node, "end_index", Integer.class, list.size());
            if (start < 0) start = list.size() + start;
            if (end < 0) end = list.size() + end;
            if (start < 0) start = 0;
            if (end < 0) end = 0;
            if (start > list.size()) start = list.size();
            if (end > list.size()) end = list.size();
            List<Object> result = new ArrayList<>();
            for (int i = start; i < end; i++) result.add(list.get(i));
            ctx.setOutput(node, "slice_list", result);
        });
        operations.put("sublist", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            if (start < 0) start = list.size() + start;
            if (start < 0) start = 0;
            if (start > list.size()) start = list.size();
            int end = Math.min(start + count, list.size());
            List<Object> result = new ArrayList<>();
            for (int i = start; i < end; i++) result.add(list.get(i));
            ctx.setOutput(node, "list", result);
        });
        operations.put("reverse", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>(list);
            Collections.reverse(result);
            ctx.setOutput(node, "reversed_list", result);
        });
        operations.put("shuffle", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>(list);
            Collections.shuffle(result);
            ctx.setOutput(node, "shuffled_list", result);
        });
        operations.put("sort", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                return compare(a, b);
            });
            String order = ctx.getInputValue(node, "sort_order", String.class, "ascending");
            if ("descending".equalsIgnoreCase(order) || "desc".equalsIgnoreCase(order)) {
                Collections.reverse(result);
            }
            ctx.setOutput(node, "sorted_list", result);
        });
        operations.put("sort_descending", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return -1;
                if (b == null) return 1;
                return compare(b, a);
            });
            ctx.setOutput(node, "list", result);
        });
        operations.put("filter", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            FlowGraph subGraph = ctx.extractSubGraph(node, "sub_flow");
            if (subGraph != null && !subGraph.getNodes().isEmpty()) {
                CompletableFuture<List<Object>> result = CompletableFuture.completedFuture(new ArrayList<>());
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item == null) {
                        continue;
                    }
                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("element", item);
                    inputs.put("index", i);
                    result = result.thenCompose(filtered -> ctx.executeSubFlowBooleanAsync(subGraph, node, inputs).thenApply(keep -> {
                        if (Boolean.TRUE.equals(keep)) {
                            filtered.add(item);
                        }
                        return filtered;
                    }));
                }
                ctx.awaitBeforeContinuation(result.thenAccept(filtered -> ctx.setOutput(node, "filtered_list", filtered)));
            } else {
                List<Object> result = new ArrayList<>();
                String property = ctx.getInputValue(node, "property_name", String.class, "");
                String operator = ctx.getInputValue(node, "operator", String.class, "equals");
                Object compareValue = ctx.getInputValue(node, "compare_value", null);
                for (Object item : list) {
                    if (matchesPredicate(item, property, operator, compareValue)) {
                        result.add(item);
                    }
                }
                ctx.setOutput(node, "filtered_list", result);
            }
        });
        operations.put("map", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            FlowGraph subGraph = ctx.extractSubGraph(node, "sub_flow");
            if (subGraph != null && !subGraph.getNodes().isEmpty()) {
                CompletableFuture<List<Object>> result = CompletableFuture.completedFuture(new ArrayList<>());
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("element", item);
                    inputs.put("index", i);
                    result = result.thenCompose(mapped -> ctx.executeSubFlowObjectAsync(subGraph, node, inputs).thenApply(value -> {
                        mapped.add(value);
                        return mapped;
                    }));
                }
                ctx.awaitBeforeContinuation(result.thenAccept(mapped -> ctx.setOutput(node, "transformed_list", mapped)));
            } else {
                List<Object> result = new ArrayList<>();
                String transform = ctx.getInputValue(node, "transformation_type", String.class, "");
                for (Object item : list) {
                    result.add(applyMapTransform(item, transform));
                }
                ctx.setOutput(node, "transformed_list", result);
            }
        });
        operations.put("reduce", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String operation = ctx.getInputValue(node, "operation", String.class, "sum");
            Object result;
            if ("concat".equalsIgnoreCase(operation)) {
                String separator = ctx.getInputValue(node, "separator", String.class, "");
                result = list.stream().map(String::valueOf).collect(Collectors.joining(separator));
            } else {
                double sum = 0.0;
                for (Object item : list) {
                    if (item instanceof Number number) {
                        sum += number.doubleValue();
                    }
                }
                result = sum;
            }
            ctx.setOutput(node, "result", result);
        });
        operations.put("flatten", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof List) {
                    result.addAll((List<?>) item);
                } else {
                    result.add(item);
                }
            }
            ctx.setOutput(node, "flattened_list", result);
        });
        operations.put("unique", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.setOutput(node, "unique_list", new ArrayList<>(new LinkedHashSet<>(list)));
        });
        operations.put("join", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String separator = ctx.getInputValue(node, "separator", String.class, ",");
            ctx.setOutput(node, "string", list.stream().map(String::valueOf).collect(Collectors.joining(separator)));
        });
        operations.put("concat", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            List<Object> result = new ArrayList<>(listA);
            result.addAll(listB);
            ctx.setOutput(node, "list", result);
        });
        operations.put("intersect", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "list2", List.class, List.of());
            ctx.setOutput(node, "intersection_list", listA.stream().filter(listB::contains).distinct().collect(Collectors.toList()));
        });
        operations.put("difference", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "list2", List.class, List.of());
            ctx.setOutput(node, "difference_list", listA.stream().filter(item -> !listB.contains(item)).collect(Collectors.toList()));
        });
        operations.put("zip", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "list2", List.class, List.of());
            List<Object> result = new ArrayList<>();
            int minSize = Math.min(listA.size(), listB.size());
            for (int i = 0; i < minSize; i++) {
                Map<String, Object> pair = new HashMap<>();
                pair.put("first", listA.get(i));
                pair.put("second", listB.get(i));
                result.add(pair);
            }
            ctx.setOutput(node, "pairs_list", result);
        });
    }

    private void registerAdvancedOperations() {
        operations.put("map_string", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item != null ? String.valueOf(item) : null);
            }
            ctx.setOutput(node, "list", result);
        });
        operations.put("filter_type", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String typeName = ctx.getInputValue(node, "type_name", String.class, "");
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (matchesType(item, typeName)) result.add(item);
            }
            ctx.setOutput(node, "list", result);
        });
        operations.put("find_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            Object found = list.stream().filter(item -> matchesPredicate(item, property, operator, compareValue)).findFirst().orElse(null);
            ctx.setOutput(node, "found_element", found);
        });
        operations.put("count", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object target = ctx.getInputValue(node, "value", null);
            long count = list.stream().filter(item -> Objects.equals(item, target)).count();
            ctx.setOutput(node, "count", (int) count);
        });
        operations.put("group_by", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property_name", String.class, "");
            Map<String, List<Object>> groups = new LinkedHashMap<>();
            for (Object item : list) {
                Object key = property.isBlank() ? item : extractProperty(item, property);
                groups.computeIfAbsent(String.valueOf(key), ignored -> new ArrayList<>()).add(item);
            }
            ctx.setOutput(node, "groups", groups);
        });
        operations.put("any", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            ctx.setOutput(node, "matches", list.stream().anyMatch(item -> matchesPredicate(item, property, operator, compareValue)));
        });
        operations.put("all", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            ctx.setOutput(node, "matches", list.stream().allMatch(item -> matchesPredicate(item, property, operator, compareValue)));
        });
        operations.put("none", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            ctx.setOutput(node, "matches", list.stream().noneMatch(item -> matchesPredicate(item, property, operator, compareValue)));
        });
        operations.put("sum", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            double sum = 0.0;
            for (Object item : list) {
                if (item instanceof Number) sum += ((Number) item).doubleValue();
            }
            ctx.setOutput(node, "sum", sum);
        });
        operations.put("average", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            double sum = 0.0;
            int count = 0;
            for (Object item : list) {
                if (item instanceof Number) {
                    sum += ((Number) item).doubleValue();
                    count++;
                }
            }
            ctx.setOutput(node, "average", count > 0 ? sum / count : 0.0);
        });
        operations.put("min", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object min = null;
            for (Object item : list) {
                if (item instanceof Comparable) {
                    if (min == null || ((Comparable<Object>) item).compareTo(min) < 0) {
                        min = item;
                    }
                }
            }
            ctx.setOutput(node, "min", min);
        });
        operations.put("max", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object max = null;
            for (Object item : list) {
                if (item instanceof Comparable) {
                    if (max == null || ((Comparable<Object>) item).compareTo(max) > 0) {
                        max = item;
                    }
                }
            }
            ctx.setOutput(node, "max", max);
        });
        operations.put("of", (ctx, node) -> {
            List<Object> items = ctx.getInputValue(node, "items", List.class, List.of());
            ctx.setOutput(node, "list", items);
        });
        operations.put("insert", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, new ArrayList<>());
            Integer index = ctx.getInputValue(node, "index", Integer.class, 0);
            Object item = ctx.getInputValue(node, "item", null);
            if (index >= 0 && index <= list.size()) {
                list.add(index, item);
            }
            ctx.setOutput(node, "list", list);
        });
        operations.put("take_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            int end = Math.min(count, list.size());
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < end; i++) result.add(list.get(i));
            ctx.setOutput(node, "list", result);
        });
        operations.put("take_last", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            int start = Math.max(0, list.size() - count);
            List<Object> result = new ArrayList<>();
            for (int i = start; i < list.size(); i++) result.add(list.get(i));
            ctx.setOutput(node, "list", result);
        });
        operations.put("drop_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            int start = Math.min(count, list.size());
            List<Object> result = new ArrayList<>();
            for (int i = start; i < list.size(); i++) result.add(list.get(i));
            ctx.setOutput(node, "list", result);
        });
        operations.put("drop_last", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            int end = Math.max(0, list.size() - count);
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < end; i++) result.add(list.get(i));
            ctx.setOutput(node, "list", result);
        });
        operations.put("sort_by_property", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String property = ctx.getInputValue(node, "property", String.class, "");
            if (property == null || property.isBlank()) {
                throw new IllegalArgumentException("Sort property is required");
            }
            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                Object valA = a != null ? extractProperty(a, property) : null;
                Object valB = b != null ? extractProperty(b, property) : null;
                if (valA == null && valB == null) return 0;
                if (valA == null) return 1;
                if (valB == null) return -1;
                if (valA instanceof Comparable && valB instanceof Comparable) {
                    return ((Comparable<Object>) valA).compareTo(valB);
                }
                return String.valueOf(valA).compareTo(String.valueOf(valB));
            });
            ctx.setOutput(node, "list", result);
        });
        operations.put("find_all", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object target = ctx.getInputValue(node, "target", null);
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (Objects.equals(list.get(i), target)) indices.add(i);
            }
            ctx.setOutput(node, "indices", indices);
        });
        operations.put("find_index", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String condition = ctx.getInputValue(node, "condition", String.class, "");
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item == null) continue;
                if (condition.isBlank()) {
                    index = i;
                    break;
                }
                if (matchesFilterCondition(item, condition)) {
                    index = i;
                    break;
                }
            }
            ctx.setOutput(node, "index", index);
        });
        operations.put("contains_any", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> items = ctx.getInputValue(node, "items", List.class, List.of());
            boolean contains = items.stream().anyMatch(list::contains);
            ctx.setOutput(node, "contains", contains);
        });
        operations.put("contains_all", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> items = ctx.getInputValue(node, "items", List.class, List.of());
            boolean contains = items.stream().allMatch(list::contains);
            ctx.setOutput(node, "contains", contains);
        });
        operations.put("partition", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer size = ctx.getInputValue(node, "size", Integer.class, 1);
            List<List<Object>> partitions = new ArrayList<>();
            if (size <= 0) {
                ctx.setOutput(node, "partitions", partitions);
                return;
            }
            for (int i = 0; i < list.size(); i += size) {
                partitions.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
            }
            ctx.setOutput(node, "partitions", partitions);
        });
        operations.put("chunk", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer size = ctx.getInputValue(node, "size", Integer.class, 1);
            List<List<Object>> chunks = new ArrayList<>();
            if (size <= 0) {
                ctx.setOutput(node, "chunks", chunks);
                return;
            }
            for (int i = 0; i < list.size(); i += size) {
                chunks.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
            }
            ctx.setOutput(node, "chunks", chunks);
        });
        operations.put("union", (ctx, node) -> {
            List<Object> listA = ctx.getInputValue(node, "listA", List.class, List.of());
            List<Object> listB = ctx.getInputValue(node, "listB", List.class, List.of());
            List<Object> result = new ArrayList<>(listA);
            for (Object item : listB) {
                if (!result.contains(item)) result.add(item);
            }
            ctx.setOutput(node, "list", result);
        });
        operations.put("min_value", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            double min = 0.0;
            boolean found = false;
            for (Object item : list) {
                if (item instanceof Number n) {
                    double val = n.doubleValue();
                    if (!found || val < min) {
                        min = val;
                        found = true;
                    }
                }
            }
            ctx.setOutput(node, "min", found ? min : 0.0);
        });
        operations.put("max_value", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            double max = 0.0;
            boolean found = false;
            for (Object item : list) {
                if (item instanceof Number n) {
                    double val = n.doubleValue();
                    if (!found || val > max) {
                        max = val;
                        found = true;
                    }
                }
            }
            ctx.setOutput(node, "max", found ? max : 0.0);
        });
        operations.put("median", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Double> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) values.add(n.doubleValue());
            }
            double median = 0.0;
            if (!values.isEmpty()) {
                Collections.sort(values);
                int mid = values.size() / 2;
                if (values.size() % 2 == 0) {
                    median = (values.get(mid - 1) + values.get(mid)) / 2.0;
                } else {
                    median = values.get(mid);
                }
            }
            ctx.setOutput(node, "median", median);
        });
        operations.put("mode", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object mode = null;
            int maxCount = 0;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                int count = 0;
                for (int j = 0; j < list.size(); j++) {
                    if (Objects.equals(item, list.get(j))) count++;
                }
                if (count > maxCount) {
                    maxCount = count;
                    mode = item;
                }
            }
            ctx.setOutput(node, "mode", mode);
        });
        operations.put("range", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            double min = 0.0;
            double max = 0.0;
            boolean found = false;
            for (Object item : list) {
                if (item instanceof Number n) {
                    double val = n.doubleValue();
                    if (!found) {
                        min = val;
                        max = val;
                        found = true;
                    } else {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            }
            ctx.setOutput(node, "range", found ? max - min : 0.0);
        });
        operations.put("variance", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Double> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) values.add(n.doubleValue());
            }
            double variance = 0.0;
            if (!values.isEmpty()) {
                double sum = 0.0;
                for (double v : values) sum += v;
                double mean = sum / values.size();
                double sqDiffSum = 0.0;
                for (double v : values) sqDiffSum += (v - mean) * (v - mean);
                variance = sqDiffSum / values.size();
            }
            ctx.setOutput(node, "variance", variance);
        });
        operations.put("stddev", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Double> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) values.add(n.doubleValue());
            }
            double stddev = 0.0;
            if (!values.isEmpty()) {
                double sum = 0.0;
                for (double v : values) sum += v;
                double mean = sum / values.size();
                double sqDiffSum = 0.0;
                for (double v : values) sqDiffSum += (v - mean) * (v - mean);
                stddev = Math.sqrt(sqDiffSum / values.size());
            }
            ctx.setOutput(node, "stddev", stddev);
        });
    }

    private static boolean matchesPredicate(Object item, String property, String operator, Object compareValue) {
        Object value = property == null || property.isBlank() ? item : extractProperty(item, property);
        String normalized = operator == null ? "equals" : operator.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "equals", "equal", "==" -> valuesEqual(value, compareValue);
            case "not_equals", "not_equal", "!=" -> !valuesEqual(value, compareValue);
            case "greater_than", ">" -> value != null && compareValue != null && compare(value, compareValue) > 0;
            case "greater_than_or_equal", "greater_or_equal", ">=" -> value != null && compareValue != null && compare(value, compareValue) >= 0;
            case "less_than", "<" -> value != null && compareValue != null && compare(value, compareValue) < 0;
            case "less_than_or_equal", "less_or_equal", "<=" -> value != null && compareValue != null && compare(value, compareValue) <= 0;
            case "contains" -> value instanceof Collection<?> collection ? collection.contains(compareValue)
                : value != null && compareValue != null && String.valueOf(value).contains(String.valueOf(compareValue));
            case "contains_ignore_case" -> value != null && compareValue != null
                && String.valueOf(value).toLowerCase(Locale.ROOT).contains(String.valueOf(compareValue).toLowerCase(Locale.ROOT));
            case "starts_with" -> value != null && compareValue != null && String.valueOf(value).startsWith(String.valueOf(compareValue));
            case "ends_with" -> value != null && compareValue != null && String.valueOf(value).endsWith(String.valueOf(compareValue));
            case "is_null" -> value == null;
            case "is_not_null", "exists" -> value != null;
            case "is_empty" -> value == null || value instanceof String string && string.isEmpty()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
            case "is_not_empty" -> value != null && (!(value instanceof String string) || !string.isEmpty())
                && (!(value instanceof Collection<?> collection) || !collection.isEmpty())
                && (!(value instanceof Map<?, ?> map) || !map.isEmpty());
            default -> false;
        };
    }

    private static List<Object> mutableList(FlowContext context, FlowNode node) {
        List<Object> list = context.getInputValue(node, "list", List.class, List.of());
        return new ArrayList<>(list);
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        return Objects.equals(left, right);
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        if (left instanceof Comparable<?> && left.getClass().isInstance(right)) {
            return ((Comparable<Object>) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static boolean matchesFilterCondition(Object item, String condition) {
        String lower = condition.toLowerCase();
        if (lower.startsWith("type:")) {
            return matchesType(item, lower.substring(5).trim());
        }
        if (lower.startsWith("not_null")) {
            return item != null;
        }
        if (lower.startsWith("instanceof ")) {
            return matchesType(item, lower.substring(11).trim());
        }
        if (item instanceof String s) {
            return s.toLowerCase().contains(lower);
        }
        if (item instanceof Number n && lower.startsWith(">")) {
            try {
                double target = Double.parseDouble(lower.substring(1).trim());
                return n.doubleValue() > target;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid numeric list filter: " + condition, exception);
            }
        }
        if (item instanceof Number n && lower.startsWith("<")) {
            try {
                double target = Double.parseDouble(lower.substring(1).trim());
                return n.doubleValue() < target;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid numeric list filter: " + condition, exception);
            }
        }
        return String.valueOf(item).toLowerCase().contains(lower);
    }

    private static Object applyMapTransform(Object item, String transform) {
        if (item == null) return null;
        if (transform.isBlank()) return String.valueOf(item);
        String lower = transform.toLowerCase();
        if (lower.startsWith("property:")) {
            String property = transform.substring(9).trim();
            return extractProperty(item, property);
        }
        if (lower.equals("string") || lower.equals("to_string")) {
            return String.valueOf(item);
        }
        if (lower.equals("number") || lower.equals("to_number")) {
            if (item instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(item)); } catch (NumberFormatException e) { return null; }
        }
        if (lower.equals("length") || lower.equals("size")) {
            if (item instanceof Collection<?> c) return c.size();
            if (item instanceof String s) return s.length();
            return null;
        }
        return String.valueOf(item);
    }

    private static Object extractProperty(Object item, String property) {
        if (item == null) {
            return null;
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("List item property is required");
        }
        if (item instanceof Entity entity) {
            return switch (property.toLowerCase()) {
                case "name", "custom_name" -> entity.getCustomName();
                case "type" -> entity.getType().name();
                case "uuid" -> entity.getUniqueId().toString();
                case "world" -> entity.getWorld().getName();
                case "location" -> entity.getLocation();
                default -> throw new IllegalArgumentException("Unknown entity list property: " + property);
            };
        }
        if (item instanceof ItemStack stack) {
            return switch (property.toLowerCase()) {
                case "type", "material" -> stack.getType().name();
                case "amount" -> stack.getAmount();
                default -> throw new IllegalArgumentException("Unknown item list property: " + property);
            };
        }
        if (item instanceof Map<?, ?> map) {
            if (!map.containsKey(property)) {
                throw new IllegalArgumentException("Map list property is missing: " + property);
            }
            return map.get(property);
        }
        try {
            Method method = item.getClass().getMethod("get" + property.substring(0, 1).toUpperCase() + property.substring(1));
            return method.invoke(item);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Unknown list property " + property + " for " + item.getClass().getSimpleName(), exception);
        }
    }

    private static boolean matchesType(Object item, String typeName) {
        if (item == null) return typeName.equalsIgnoreCase("null");
        String lower = typeName.toLowerCase();
        return switch (lower) {
            case "string" -> item instanceof String;
            case "number", "double", "int", "float", "long" -> item instanceof Number;
            case "boolean", "bool" -> item instanceof Boolean;
            case "list", "array", "collection" -> item instanceof Collection;
            case "map", "json" -> item instanceof Map;
            default -> false;
        };
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("GenericListHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown list operation: " + operation);
        }
        ctx.triggerOutput("flow");
    }
}
