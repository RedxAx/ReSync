package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FunctionCatalogHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("list", "find", "exists", "index", "at_index", "filter", "describe");
    private static final Comparator<String> FUNCTION_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
    private final FlowStorage storage;

    public FunctionCatalogHandler(FlowStorage storage) {
        this.storage = storage;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FunctionCatalogHandler", this);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation", "");
        switch (operation) {
            case "list" -> ctx.setOutput(node, "functions", functions());
            case "find" -> find(ctx, node);
            case "exists" -> ctx.setOutput(node, "exists", lookup(text(ctx, node, "function")).found());
            case "index" -> index(ctx, node);
            case "at_index" -> atIndex(ctx, node);
            case "filter" -> filter(ctx, node);
            case "describe" -> describe(ctx, node);
            default -> throw new IllegalArgumentException("Unknown function catalog operation: " + operation);
        }
    }

    private void find(FlowContext ctx, FlowNode node) {
        String query = text(ctx, node, "name");
        Lookup lookup = lookup(query);
        String found = lookup.function();
        boolean available = lookup.found();
        ctx.setOutput(node, "function", found);
        ctx.setOutput(node, "found", available);
        if (available) {
            ctx.setOutput(node, "result", FlowOperationResult.success(found));
            return;
        }
        if (lookup.ambiguous()) {
            ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_NAME_AMBIGUOUS", "Function Name Is Ambiguous",
                Map.of("name", query, "matches", lookup.matches())));
            return;
        }
        ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_NOT_FOUND", "Function Not Found", Map.of("name", query)));
    }

    private void index(FlowContext ctx, FlowNode node) {
        List<String> values = inputFunctions(ctx, node);
        String function = text(ctx, node, "function");
        int index = lookup(values, function).index();
        ctx.setOutput(node, "index", index);
        ctx.setOutput(node, "found", index >= 0);
    }

    private void filter(FlowContext ctx, FlowNode node) {
        String query = text(ctx, node, "query").toLowerCase(Locale.ROOT);
        List<String> matches = query.isBlank() ? functions() : functions().stream()
            .filter(id -> id.toLowerCase(Locale.ROOT).contains(query))
            .toList();
        ctx.setOutput(node, "functions", matches);
    }

    private void atIndex(FlowContext ctx, FlowNode node) {
        List<String> values = inputFunctions(ctx, node);
        ParsedIndex parsed = parseIndex(ctx.getInputValue(node, "index", Object.class, null));
        if (!parsed.valid()) {
            ctx.setOutput(node, "function", "");
            ctx.setOutput(node, "found", false);
            ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_INDEX_INVALID", "Function Index Is Invalid", parsed.details()));
            return;
        }
        int index = parsed.value();
        boolean available = index >= 0 && index < values.size();
        String function = available ? values.get(index) : "";
        ctx.setOutput(node, "function", function);
        ctx.setOutput(node, "found", available);
        ctx.setOutput(node, "result", available
            ? FlowOperationResult.success(function)
            : FlowOperationResult.failure("FUNCTION_INDEX_OUT_OF_RANGE", "Function Index Is Out Of Range", Map.of("index", index, "size", values.size())));
    }

    private void describe(FlowContext ctx, FlowNode node) {
        String id = text(ctx, node, "function");
        FlowGraph function = function(id);
        if (function == null) {
            ctx.setOutput(node, "inputs", Map.of());
            ctx.setOutput(node, "outputs", Map.of());
            ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_NOT_FOUND", "Function Not Found", Map.of("function", id)));
            return;
        }
        ctx.setOutput(node, "inputs", parameters(function.getFunctionInputs()));
        ctx.setOutput(node, "outputs", parameters(function.getFunctionOutputs()));
        ctx.setOutput(node, "result", FlowOperationResult.success(id));
    }

    private Map<String, String> parameters(List<FlowGraph.FunctionParameter> parameters) {
        Map<String, String> signature = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.stream()
                .filter(parameter -> parameter != null && parameter.getName() != null && !parameter.getName().isBlank())
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName()))
                .forEach(parameter -> signature.put(parameter.getName(), parameter.getTypeRef().toString()));
        }
        return signature;
    }

    private List<String> functions() {
        if (storage == null) {
            return List.of();
        }
        return storage.listGraphIds("function").stream()
            .filter(id -> id != null && function(id) != null)
            .sorted(FUNCTION_ORDER)
            .toList();
    }

    private List<String> inputFunctions(FlowContext ctx, FlowNode node) {
        Object raw = ctx.getInputValue(node, "functions", Object.class, null);
        return raw == null ? functions() : stringList(raw instanceof List<?> values ? values : List.of());
    }

    private Lookup lookup(String query) {
        return lookup(functions(), query);
    }

    private Lookup lookup(List<String> values, String query) {
        String normalized = query != null ? query.trim() : "";
        if (normalized.isBlank()) {
            return Lookup.notFound();
        }
        String folded = normalized.toLowerCase(Locale.ROOT);
        List<String> matches = values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).equals(folded))
            .toList();
        if (matches.isEmpty()) {
            return Lookup.notFound();
        }
        List<String> exactMatches = matches.stream().filter(normalized::equals).toList();
        if (exactMatches.size() == 1) {
            String function = exactMatches.getFirst();
            return Lookup.found(function, matches, values.indexOf(function));
        }
        if (matches.size() == 1) {
            String function = matches.getFirst();
            return Lookup.found(function, matches, values.indexOf(function));
        }
        return Lookup.ambiguous(matches);
    }

    private ParsedIndex parseIndex(Object raw) {
        if (raw == null) {
            return ParsedIndex.valid(-1);
        }
        if (!(raw instanceof Number number)) {
            return ParsedIndex.invalid(raw, "Index Must Be A Number");
        }
        try {
            BigDecimal value = new BigDecimal(number.toString());
            if (value.stripTrailingZeros().scale() > 0) {
                return ParsedIndex.invalid(raw, "Index Must Be A Whole Number");
            }
            return ParsedIndex.valid(value.intValueExact());
        } catch (NumberFormatException | ArithmeticException exception) {
            return ParsedIndex.invalid(raw, "Index Must Be Finite, Whole, And In Range");
        }
    }

    private FlowGraph function(String id) {
        if (storage == null || id == null || id.isBlank()) {
            return null;
        }
        FlowGraph graph = storage.getGraph("function", id);
        return graph != null && graph.isFunction() ? graph : null;
    }

    private String text(FlowContext ctx, FlowNode node, String pin) {
        String value = ctx.getInputValue(node, pin, String.class, "");
        return value != null ? value.trim() : "";
    }

    private List<String> stringList(List<?> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null).map(Object::toString).toList();
    }

    private record Lookup(Status status, String function, List<String> matches, int index) {
        private static Lookup found(String function, List<String> matches, int index) {
            return new Lookup(Status.FOUND, function, matches, index);
        }

        private static Lookup notFound() {
            return new Lookup(Status.NOT_FOUND, "", List.of(), -1);
        }

        private static Lookup ambiguous(List<String> matches) {
            return new Lookup(Status.AMBIGUOUS, "", matches, -1);
        }

        private boolean found() {
            return status == Status.FOUND;
        }

        private boolean ambiguous() {
            return status == Status.AMBIGUOUS;
        }
    }

    private enum Status {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS
    }

    private record ParsedIndex(boolean valid, int value, Map<String, Object> details) {
        private static ParsedIndex valid(int value) {
            return new ParsedIndex(true, value, Map.of());
        }

        private static ParsedIndex invalid(Object raw, String reason) {
            return new ParsedIndex(false, -1, Map.of("index", raw, "reason", reason));
        }
    }
}
