package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class ListAdvancedNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("list_sort", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String sortOrder = ctx.getInputValue(node, "sort_order", String.class, "asc");
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                if (a instanceof Comparable && b instanceof Comparable) {
                    int cmp = ((Comparable<Object>) a).compareTo(b);
                    return sortOrder.equals("desc") ? -cmp : cmp;
                }
                int cmp = String.valueOf(a).compareTo(String.valueOf(b));
                return sortOrder.equals("desc") ? -cmp : cmp;
            });
            ctx.setNodeOutput(nodeId, "sorted_list", result);
        });

        registry.register("list_sort_by_property", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String sortOrder = ctx.getInputValue(node, "sort_order", String.class, "asc");
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                try {
                    Field fieldA = a != null ? a.getClass().getDeclaredField(propertyName) : null;
                    Field fieldB = b != null ? b.getClass().getDeclaredField(propertyName) : null;
                    if (fieldA != null) fieldA.setAccessible(true);
                    if (fieldB != null) fieldB.setAccessible(true);
                    Object valA = fieldA != null ? fieldA.get(a) : null;
                    Object valB = fieldB != null ? fieldB.get(b) : null;
                    if (valA == null && valB == null) return 0;
                    if (valA == null) return 1;
                    if (valB == null) return -1;
                    if (valA instanceof Comparable && valB instanceof Comparable) {
                        int cmp = ((Comparable<Object>) valA).compareTo(valB);
                        return sortOrder.equals("desc") ? -cmp : cmp;
                    }
                } catch (Exception ignored) {}
                return 0;
            });
            ctx.setNodeOutput(nodeId, "sorted_list", result);
        });

        registry.register("list_filter", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            String nodeId = findNodeId(ctx, node);

            List<Object> result = list.stream()
                .filter(item -> {
                    Object value = getProperty(item, propertyName);
                    if (value == null) return compareValue == null;
                    return switch (operator) {
                        case "equals" -> Objects.equals(value, compareValue);
                        case "contains" -> value.toString().contains(compareValue != null ? compareValue.toString() : "");
                        case "starts_with" -> value.toString().startsWith(compareValue != null ? compareValue.toString() : "");
                        case "ends_with" -> value.toString().endsWith(compareValue != null ? compareValue.toString() : "");
                        case "greater" -> compareNumber(value, compareValue, (a, b) -> a > b);
                        case "less" -> compareNumber(value, compareValue, (a, b) -> a < b);
                        case "not_equals" -> !Objects.equals(value, compareValue);
                        default -> false;
                    };
                })
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "filtered_list", result);
        });

        registry.register("list_map", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String transformationType = ctx.getInputValue(node, "transformation_type", String.class, "to_string");
            String nodeId = findNodeId(ctx, node);

            List<Object> result = list.stream()
                .map(item -> {
                    if (item == null) return null;
                    return switch (transformationType) {
                        case "to_string" -> item.toString();
                        case "to_upper" -> item.toString().toUpperCase();
                        case "to_lower" -> item.toString().toLowerCase();
                        case "to_number" -> {
                            try {
                                yield Double.parseDouble(item.toString());
                            } catch (NumberFormatException e) {
                                yield 0.0;
                            }
                        }
                        case "length" -> item.toString().length();
                        default -> item;
                    };
                })
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "transformed_list", result);
        });

        registry.register("list_reduce", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String operation = ctx.getInputValue(node, "operation", String.class, "sum");
            String separator = ctx.getInputValue(node, "separator", String.class, ",");
            String nodeId = findNodeId(ctx, node);

            Object result = switch (operation) {
                case "sum" -> list.stream()
                    .filter(item -> item instanceof Number)
                    .mapToDouble(item -> ((Number) item).doubleValue())
                    .sum();
                case "multiply" -> list.stream()
                    .filter(item -> item instanceof Number)
                    .mapToDouble(item -> ((Number) item).doubleValue())
                    .reduce(1.0, (a, b) -> a * b);
                case "concat" -> list.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining());
                case "join" -> list.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(separator));
                default -> null;
            };
            ctx.setNodeOutput(nodeId, "result", result);
        });

        registry.register("list_shuffle", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(list);
            Collections.shuffle(result);
            ctx.setNodeOutput(nodeId, "shuffled_list", result);
        });

        registry.register("list_unique", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(new LinkedHashSet<>(list));
            ctx.setNodeOutput(nodeId, "unique_list", result);
        });

        registry.register("list_slice", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer startIndex = ctx.getInputValue(node, "start_index", Integer.class, 0);
            Integer endIndex = ctx.getInputValue(node, "end_index", Integer.class, list.size());
            String nodeId = findNodeId(ctx, node);

            if (startIndex < 0) startIndex = list.size() + startIndex;
            if (endIndex < 0) endIndex = list.size() + endIndex;
            startIndex = Math.max(0, Math.min(startIndex, list.size()));
            endIndex = Math.max(0, Math.min(endIndex, list.size()));

            List<Object> result = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                result.add(list.get(i));
            }
            ctx.setNodeOutput(nodeId, "slice_list", result);
        });

        registry.register("list_reverse", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(list);
            Collections.reverse(result);
            ctx.setNodeOutput(nodeId, "reversed_list", result);
        });

        registry.register("list_find_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            String nodeId = findNodeId(ctx, node);

            Object result = list.stream()
                .filter(item -> {
                    Object value = getProperty(item, propertyName);
                    if (value == null) return compareValue == null;
                    return switch (operator) {
                        case "equals" -> Objects.equals(value, compareValue);
                        case "contains" -> value.toString().contains(compareValue != null ? compareValue.toString() : "");
                        case "starts_with" -> value.toString().startsWith(compareValue != null ? compareValue.toString() : "");
                        case "ends_with" -> value.toString().endsWith(compareValue != null ? compareValue.toString() : "");
                        case "greater" -> compareNumber(value, compareValue, (a, b) -> a > b);
                        case "less" -> compareNumber(value, compareValue, (a, b) -> a < b);
                        case "not_equals" -> !Objects.equals(value, compareValue);
                        default -> false;
                    };
                })
                .findFirst()
                .orElse(null);
            ctx.setNodeOutput(nodeId, "found_element", result);
        });

        registry.register("list_find_all", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            String nodeId = findNodeId(ctx, node);

            List<Object> result = list.stream()
                .filter(item -> {
                    Object value = getProperty(item, propertyName);
                    if (value == null) return compareValue == null;
                    return switch (operator) {
                        case "equals" -> Objects.equals(value, compareValue);
                        case "contains" -> value.toString().contains(compareValue != null ? compareValue.toString() : "");
                        case "starts_with" -> value.toString().startsWith(compareValue != null ? compareValue.toString() : "");
                        case "ends_with" -> value.toString().endsWith(compareValue != null ? compareValue.toString() : "");
                        case "greater" -> compareNumber(value, compareValue, (a, b) -> a > b);
                        case "less" -> compareNumber(value, compareValue, (a, b) -> a < b);
                        case "not_equals" -> !Objects.equals(value, compareValue);
                        default -> false;
                    };
                })
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "found_list", result);
        });

        registry.register("list_find_index", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Object element = ctx.getInputValue(node, "element", null);
            String nodeId = findNodeId(ctx, node);

            int index = list.indexOf(element);
            ctx.setNodeOutput(nodeId, "index", index);
        });

        registry.register("list_contains_any", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> elementsList = ctx.getInputValue(node, "elements_list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            boolean result = elementsList.stream().anyMatch(list::contains);
            ctx.setNodeOutput(nodeId, "contains_any", result);
        });

        registry.register("list_contains_all", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            List<Object> elementsList = ctx.getInputValue(node, "elements_list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            boolean result = elementsList.stream().allMatch(list::contains);
            ctx.setNodeOutput(nodeId, "contains_all", result);
        });

        registry.register("list_sum", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            double sum = list.stream()
                .filter(item -> item instanceof Number)
                .mapToDouble(item -> ((Number) item).doubleValue())
                .sum();
            ctx.setNodeOutput(nodeId, "sum", sum);
        });

        registry.register("list_average", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            double average = list.stream()
                .filter(item -> item instanceof Number)
                .mapToDouble(item -> ((Number) item).doubleValue())
                .average()
                .orElse(0.0);
            ctx.setNodeOutput(nodeId, "average", average);
        });

        registry.register("list_min_value", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            Double min = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .min(Double::compare)
                .orElse(null);
            ctx.setNodeOutput(nodeId, "min", min);
        });

        registry.register("list_max_value", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            Double max = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .max(Double::compare)
                .orElse(null);
            ctx.setNodeOutput(nodeId, "max", max);
        });

        registry.register("list_median", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Double> numbers = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .sorted()
                .collect(Collectors.toList());

            Double median = null;
            if (!numbers.isEmpty()) {
                int size = numbers.size();
                median = size % 2 == 0
                    ? (numbers.get(size / 2 - 1) + numbers.get(size / 2)) / 2.0
                    : numbers.get(size / 2);
            }
            ctx.setNodeOutput(nodeId, "median", median);
        });

        registry.register("list_mode", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            Map<Object, Long> frequency = list.stream()
                .collect(Collectors.groupingBy(item -> item, Collectors.counting()));

            Long maxFreq = frequency.values().stream().max(Long::compare).orElse(0L);
            List<Object> modes = frequency.entrySet().stream()
                .filter(entry -> entry.getValue().equals(maxFreq))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            ctx.setNodeOutput(nodeId, "modes_list", modes);
        });

        registry.register("list_range", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            Double range = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .collect(Collectors.collectingAndThen(
                    Collectors.toList(),
                    numbers -> {
                        if (numbers.isEmpty()) return null;
                        double min = numbers.stream().min(Double::compare).orElse(0.0);
                        double max = numbers.stream().max(Double::compare).orElse(0.0);
                        return max - min;
                    }
                ));
            ctx.setNodeOutput(nodeId, "range", range);
        });

        registry.register("list_variance", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Double> numbers = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .collect(Collectors.toList());

            Double variance = null;
            if (!numbers.isEmpty()) {
                double mean = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                variance = numbers.stream()
                    .mapToDouble(n -> Math.pow(n - mean, 2))
                    .average()
                    .orElse(0.0);
            }
            ctx.setNodeOutput(nodeId, "variance", variance);
        });

        registry.register("list_stddev", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Double> numbers = list.stream()
                .filter(item -> item instanceof Number)
                .map(item -> ((Number) item).doubleValue())
                .collect(Collectors.toList());

            Double stddev = null;
            if (!numbers.isEmpty()) {
                double mean = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = numbers.stream()
                    .mapToDouble(n -> Math.pow(n - mean, 2))
                    .average()
                    .orElse(0.0);
                stddev = Math.sqrt(variance);
            }
            ctx.setNodeOutput(nodeId, "stddev", stddev);
        });

        registry.register("list_flatten", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof List<?>) {
                    flatten((List<?>) item, result);
                } else {
                    result.add(item);
                }
            }
            ctx.setNodeOutput(nodeId, "flattened_list", result);
        });

        registry.register("list_chunk", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer chunkSize = ctx.getInputValue(node, "chunk_size", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);

            List<List<Object>> chunks = new ArrayList<>();
            for (int i = 0; i < list.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, list.size());
                chunks.add(new ArrayList<>(list.subList(i, end)));
            }
            ctx.setNodeOutput(nodeId, "chunks_list", chunks);
        });

        registry.register("list_partition", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            String propertyName = ctx.getInputValue(node, "property_name", String.class, "");
            String operator = ctx.getInputValue(node, "operator", String.class, "equals");
            Object compareValue = ctx.getInputValue(node, "compare_value", null);
            String nodeId = findNodeId(ctx, node);

            List<Object> trueList = new ArrayList<>();
            List<Object> falseList = new ArrayList<>();

            for (Object item : list) {
                Object value = getProperty(item, propertyName);
                boolean matches = value != null && switch (operator) {
                    case "equals" -> Objects.equals(value, compareValue);
                    case "contains" -> value.toString().contains(compareValue != null ? compareValue.toString() : "");
                    case "starts_with" -> value.toString().startsWith(compareValue != null ? compareValue.toString() : "");
                    case "ends_with" -> value.toString().endsWith(compareValue != null ? compareValue.toString() : "");
                    case "greater" -> compareNumber(value, compareValue, (a, b) -> a > b);
                    case "less" -> compareNumber(value, compareValue, (a, b) -> a < b);
                    case "not_equals" -> !Objects.equals(value, compareValue);
                    default -> false;
                };

                if (matches) {
                    trueList.add(item);
                } else {
                    falseList.add(item);
                }
            }

            ctx.setNodeOutput(nodeId, "true_list", trueList);
            ctx.setNodeOutput(nodeId, "false_list", falseList);
        });

        registry.register("list_intersect", (ctx, node) -> {
            List<Object> list1 = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> list2 = ctx.getInputValue(node, "list2", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = list1.stream()
                .filter(list2::contains)
                .distinct()
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "intersection_list", result);
        });

        registry.register("list_union", (ctx, node) -> {
            List<Object> list1 = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> list2 = ctx.getInputValue(node, "list2", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>(list1);
            result.addAll(list2);
            result = new ArrayList<>(new LinkedHashSet<>(result));
            ctx.setNodeOutput(nodeId, "union_list", result);
        });

        registry.register("list_difference", (ctx, node) -> {
            List<Object> list1 = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> list2 = ctx.getInputValue(node, "list2", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = list1.stream()
                .filter(item -> !list2.contains(item))
                .collect(Collectors.toList());
            ctx.setNodeOutput(nodeId, "difference_list", result);
        });

        registry.register("list_zip", (ctx, node) -> {
            List<Object> list1 = ctx.getInputValue(node, "list1", List.class, List.of());
            List<Object> list2 = ctx.getInputValue(node, "list2", List.class, List.of());
            String nodeId = findNodeId(ctx, node);

            List<Object> result = new ArrayList<>();
            int minSize = Math.min(list1.size(), list2.size());
            for (int i = 0; i < minSize; i++) {
                Map<String, Object> pair = new HashMap<>();
                pair.put("first", list1.get(i));
                pair.put("second", list2.get(i));
                result.add(pair);
            }
            ctx.setNodeOutput(nodeId, "pairs_list", result);
        });

        registry.register("list_take_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);

            int end = Math.min(Math.max(count, 0), list.size());
            List<Object> result = new ArrayList<>(list.subList(0, end));
            ctx.setNodeOutput(nodeId, "taken_list", result);
        });

        registry.register("list_take_last", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);

            int start = Math.max(list.size() - Math.max(count, 0), 0);
            List<Object> result = new ArrayList<>(list.subList(start, list.size()));
            ctx.setNodeOutput(nodeId, "taken_list", result);
        });

        registry.register("list_drop_first", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);

            int start = Math.min(Math.max(count, 0), list.size());
            List<Object> result = new ArrayList<>(list.subList(start, list.size()));
            ctx.setNodeOutput(nodeId, "remaining_list", result);
        });

        registry.register("list_drop_last", (ctx, node) -> {
            List<Object> list = ctx.getInputValue(node, "list", List.class, List.of());
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);

            int end = Math.max(list.size() - Math.max(count, 0), 0);
            List<Object> result = new ArrayList<>(list.subList(0, end));
            ctx.setNodeOutput(nodeId, "remaining_list", result);
        });
    }

    private static Object getProperty(Object obj, String propertyName) {
        if (obj == null || propertyName == null || propertyName.isEmpty()) {
            return obj;
        }
        try {
            Field field = obj.getClass().getDeclaredField(propertyName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return obj;
        }
    }

    private static boolean compareNumber(Object value, Object compareValue, java.util.function.BiPredicate<Double, Double> predicate) {
        try {
            double a = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
            double b = compareValue instanceof Number ? ((Number) compareValue).doubleValue() : Double.parseDouble(compareValue.toString());
            return predicate.test(a, b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void flatten(List<?> list, List<Object> result) {
        for (Object item : list) {
            if (item instanceof List) {
                flatten((List<?>) item, result);
            } else {
                result.add(item);
            }
        }
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
