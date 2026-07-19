package restudio.resync.flow.handler.generic;

import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.api.RuntimeDataCategory;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public final class RuntimeDataHandler implements NodeHandler {
    private final RuntimeDataRegistry registry;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new LinkedHashMap<>();

    public RuntimeDataHandler(RuntimeDataRegistry registry) {
        this.registry = registry;
        operations.put("query", this::query);
        operations.put("categories", this::categories);
        operations.put("filter", this::filter);
        operations.put("random", this::random);
        operations.put("entry_fields", this::entryFields);
        operations.put("query_items", this::queryItems);
        operations.put("random_item", this::randomItem);
        operations.put("item_from_entry", this::itemFromEntry);
        operations.put("item_categories", this::itemCategories);
        operations.put("describe_item", this::describeItem);
    }

    public void registerTo(HandlerRegistry handlers) {
        handlers.register("RuntimeDataHandler", this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unknown runtime data operation: " + operation);
        }
        handler.accept(context, node);
        context.triggerOutput("flow");
    }

    private void query(FlowContext context, FlowNode node) {
        String domain = text(context.getInputValue(node, "data_type", Object.class, "item"));
        List<RuntimeDataRecord> records = registry.query(domain, buildQuery(context, node));
        context.setOutput(node, "results", records.stream().map(RuntimeDataRecord::toMap).toList());
        context.setOutput(node, "count", records.size());
    }

    private void categories(FlowContext context, FlowNode node) {
        String domain = text(context.getInputValue(node, "data_type", Object.class, "item"));
        List<RuntimeDataCategory> categories = registry.categories(domain, buildQuery(context, node));
        context.setOutput(node, "categories", categories.stream().map(RuntimeDataCategory::toMap).toList());
        context.setOutput(node, "ids", categories.stream().map(RuntimeDataCategory::id).toList());
        context.setOutput(node, "count", categories.size());
    }

    private void filter(FlowContext context, FlowNode node) {
        List<RuntimeDataRecord> records = records(context.getInputValue(node, "data", Object.class, List.of()));
        RuntimeDataQuery query = buildQuery(context, node);
        List<RuntimeDataRecord> filtered = records.stream().filter(query::matches).limit(query.limit() > 0 ? query.limit() : Long.MAX_VALUE).toList();
        context.setOutput(node, "results", filtered.stream().map(RuntimeDataRecord::toMap).toList());
        context.setOutput(node, "count", filtered.size());
    }

    private void random(FlowContext context, FlowNode node) {
        String domain = text(context.getInputValue(node, "data_type", Object.class, "item"));
        RuntimeDataRecord record = registry.random(domain, buildQuery(context, node)).orElse(null);
        context.setOutput(node, "result", record != null ? record.toMap() : null);
        context.setOutput(node, "found", record != null);
    }

    private void entryFields(FlowContext context, FlowNode node) {
        RuntimeDataRecord record = record(context.getInputValue(node, "data", Object.class, null));
        context.setOutput(node, "data_type", record != null ? record.domain() : "");
        context.setOutput(node, "source", record != null ? record.adapterId() : "");
        context.setOutput(node, "id", record != null ? record.id() : "");
        context.setOutput(node, "name", record != null ? record.label() : "");
        context.setOutput(node, "details", record != null ? record.description() : "");
        context.setOutput(node, "categories", record != null ? record.categories().stream().toList() : List.of());
        context.setOutput(node, "tags", record != null ? record.tags().stream().toList() : List.of());
        context.setOutput(node, "properties", record != null ? record.attributes() : Map.of());
    }

    private void queryItems(FlowContext context, FlowNode node) {
        int amount = integer(context.getInputValue(node, "amount", Object.class, 1), 1);
        List<ItemStack> items = registry.resolveAll(registry.query("item", buildQuery(context, node)), amount).stream()
            .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList();
        context.setOutput(node, "items", items);
        context.setOutput(node, "count", items.size());
    }

    private void randomItem(FlowContext context, FlowNode node) {
        int amount = integer(context.getInputValue(node, "amount", Object.class, 1), 1);
        RuntimeDataRecord record = registry.random("item", buildQuery(context, node)).orElse(null);
        Object value = record != null ? registry.resolve(record, amount) : null;
        ItemStack item = value instanceof ItemStack stack ? stack : null;
        context.setOutput(node, "item", item);
        context.setOutput(node, "data", record != null ? record.toMap() : null);
        context.setOutput(node, "found", item != null);
    }

    private void itemFromEntry(FlowContext context, FlowNode node) {
        RuntimeDataRecord record = record(context.getInputValue(node, "data", Object.class, null));
        int amount = integer(context.getInputValue(node, "amount", Object.class, 1), 1);
        Object value = record != null && "item".equals(record.domain()) ? registry.resolve(record, amount) : null;
        context.setOutput(node, "item", value instanceof ItemStack stack ? stack : null);
        context.setOutput(node, "found", value instanceof ItemStack);
    }

    private void itemCategories(FlowContext context, FlowNode node) {
        List<RuntimeDataCategory> categories = registry.categories("item", buildQuery(context, node));
        context.setOutput(node, "categories", categories.stream().map(RuntimeDataCategory::toMap).toList());
        context.setOutput(node, "ids", categories.stream().map(RuntimeDataCategory::id).toList());
    }

    private void describeItem(FlowContext context, FlowNode node) {
        ItemStack item = context.getInputValue(node, "item", ItemStack.class, null);
        RuntimeDataRecord record = registry.describe("item", item);
        context.setOutput(node, "data", record != null ? record.toMap() : null);
        context.setOutput(node, "found", record != null);
    }

    private RuntimeDataQuery buildQuery(FlowContext context, FlowNode node) {
        Set<String> adapters = strings(context.getInputValue(node, "sources", Object.class, List.of()));
        addStrings(adapters, context.getInputValue(node, "source", Object.class, ""));
        Set<String> categories = strings(context.getInputValue(node, "categories", Object.class, List.of()));
        addStrings(categories, context.getInputValue(node, "category", Object.class, ""));
        return new RuntimeDataQuery(adapters, categories,
            strings(context.getInputValue(node, "tags", Object.class, List.of())),
            strings(context.getInputValue(node, "exclude_categories", Object.class, List.of())),
            strings(context.getInputValue(node, "exclude_tags", Object.class, List.of())),
            stringMap(context.getInputValue(node, "properties", Object.class, Map.of())), Map.of(),
            text(context.getInputValue(node, "search", Object.class, "")),
            RuntimeDataQuery.MatchMode.parse(text(context.getInputValue(node, "category_match", Object.class, "any")), RuntimeDataQuery.MatchMode.ANY),
            RuntimeDataQuery.MatchMode.parse(text(context.getInputValue(node, "tag_match", Object.class, "all")), RuntimeDataQuery.MatchMode.ALL),
            integer(context.getInputValue(node, "limit", Object.class, 0), 0));
    }

    private static List<RuntimeDataRecord> records(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<RuntimeDataRecord> records = new ArrayList<>();
        for (Object item : collection) {
            RuntimeDataRecord record = record(item);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    private static RuntimeDataRecord record(Object value) {
        return value instanceof Map<?, ?> map ? RuntimeDataRecord.fromMap(map) : null;
    }

    private static Set<String> strings(Object value) {
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addStrings(values, item));
        } else {
            addStrings(values, value);
        }
        return values;
    }

    private static void addStrings(Set<String> values, Object source) {
        if (source == null) {
            return;
        }
        for (String item : source.toString().split("[,\\r\\n]")) {
            if (!item.isBlank()) {
                values.add(item.trim());
            }
        }
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(text(value)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value != null ? value.toString() : "";
    }
}
