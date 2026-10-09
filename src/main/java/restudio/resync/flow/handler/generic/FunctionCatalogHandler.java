package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FunctionCatalogHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("list", "find", "exists", "index", "at_index", "filter", "describe");
    private static final Comparator<String> FUNCTION_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
    private final FlowStorage storage;
    private volatile Catalog resident;

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
        Object raw = ctx.getInputValue(node, "functions", Object.class, null);
        String function = text(ctx, node, "function");
        int index = (raw == null ? lookup(function) : lookup(stringList(raw instanceof List<?> values ? values : List.of()), function)).index();
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
        Signature function = catalog().signatures().get(id);
        if (function == null) {
            ctx.setOutput(node, "inputs", Map.of());
            ctx.setOutput(node, "outputs", Map.of());
            ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_NOT_FOUND", "Function Not Found", Map.of("function", id)));
            return;
        }
        ctx.setOutput(node, "inputs", function.inputs());
        ctx.setOutput(node, "outputs", function.outputs());
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
        return Collections.unmodifiableMap(signature);
    }

    private List<String> functions() {
        return catalog().ids();
    }

    private Catalog catalog() {
        if (storage == null) {
            return projection(null, Map.of());
        }
        if (!storage.hasCoreGraphAuthority()) {
            return legacyCatalog();
        }
        FlowStorage.RuntimeObservation observation = storage.observeRuntime()
            .orElseThrow(() -> new IllegalStateException("Function catalog storage is unavailable"));
        Catalog current = resident;
        if (current != null && current.observation().equals(observation)
            && storage.isRuntimeObservationCurrent(observation)) {
            return current;
        }
        return admitCatalog();
    }

    private synchronized Catalog admitCatalog() {
        for (int attempt = 0; attempt < 3; attempt++) {
            FlowStorage.RuntimeObservation observation = storage.observeRuntime()
                .orElseThrow(() -> new IllegalStateException("Function catalog storage is unavailable"));
            Catalog current = resident;
            if (current != null && current.observation().equals(observation)
                && storage.isRuntimeObservationCurrent(observation)) {
                return current;
            }
            FlowStorage.CommittedGraphIds inventory = storage.readCommittedGraphIds("function");
            Map<String, Signature> signatures = new LinkedHashMap<>();
            for (String id : inventory.ids()) {
                CoreGraphStorageBoundary.Decoded decoded = storage.getCoreGraph("function", id).orElse(null);
                if (decoded == null || decoded.envelope().assetActivationState() != ResourceActivationState.ACTIVE) {
                    continue;
                }
                FunctionSourceDocument source = decoded.functionSourceDocument();
                if (source == null) {
                    continue;
                }
                if (!"function".equals(source.graph().resource().resourceType().value())
                    || !id.equals(source.graph().resource().id())
                    || !source.signature().function().resource().equals(source.graph().resource())
                    || source.signature().revision().value() != decoded.envelope().assetRevision()) {
                    throw new IllegalStateException("Function catalog source identity does not match " + id);
                }
                signatures.put(id, new Signature(coreParameters(source.signature().inputs()), coreParameters(source.signature().outputs())));
            }
            Catalog admitted = projection(inventory.observation(), signatures);
            if (!storage.isRuntimeObservationCurrent(inventory.observation())) {
                continue;
            }
            resident = admitted;
            if (storage.isRuntimeObservationCurrent(inventory.observation())) {
                return admitted;
            }
        }
        throw new IllegalStateException("Function catalog storage changed during admission");
    }

    private Catalog legacyCatalog() {
        Map<String, Signature> signatures = new LinkedHashMap<>();
        storage.listGraphIds("function").stream().filter(id -> id != null).sorted(FUNCTION_ORDER).forEach(id -> {
            FlowGraph graph = function(id);
            if (graph != null) {
                signatures.put(id, new Signature(parameters(graph.getFunctionInputs()), parameters(graph.getFunctionOutputs())));
            }
        });
        return projection(null, signatures);
    }

    private Map<String, String> coreParameters(List<FunctionParameterContract> parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.stream().sorted(Comparator.comparing(this::parameterName, FUNCTION_ORDER))
            .forEach(parameter -> values.put(parameterName(parameter), typeText(parameter.type())));
        return Collections.unmodifiableMap(values);
    }

    private String parameterName(FunctionParameterContract parameter) {
        return parameter.unknown().get("name") instanceof String name && !name.isBlank() ? name : parameter.id().canonicalText();
    }

    private String typeText(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> typeName(named.reference()) + (named.arguments().isEmpty() ? ""
                : named.arguments().stream().map(this::typeText).collect(Collectors.joining(",", "<", ">")));
            case TypeExpr.OptionalType optional -> "optional<" + typeText(optional.element()) + ">";
            case TypeExpr.ListType list -> "list<" + typeText(list.element()) + ">";
            case TypeExpr.MapType map -> "map<" + typeText(map.key()) + "," + typeText(map.value()) + ">";
            case TypeExpr.ResultType result -> "result<" + typeText(result.success()) + "," + typeText(result.failure()) + ">";
            case TypeExpr.ResourceType resource -> "resource_reference<" + typeName(resource.resourceType()) + ">";
            default -> type.canonicalJson();
        };
    }

    private String typeName(TypeReference type) {
        return "builtin".equals(type.ownerId()) ? type.localId() : type.ownerId() + ':' + type.localId();
    }

    private Catalog projection(FlowStorage.RuntimeObservation observation, Map<String, Signature> signatures) {
        List<String> ids = signatures.keySet().stream().sorted(FUNCTION_ORDER).toList();
        Map<String, Integer> indices = new LinkedHashMap<>();
        Map<String, List<String>> folded = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            indices.put(id, index);
            folded.computeIfAbsent(id.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(id);
        }
        folded.replaceAll((key, values) -> List.copyOf(values));
        return new Catalog(observation, ids, Map.copyOf(signatures), Map.copyOf(indices), Map.copyOf(folded));
    }

    private record Catalog(FlowStorage.RuntimeObservation observation, List<String> ids, Map<String, Signature> signatures,
                           Map<String, Integer> indices, Map<String, List<String>> folded) {
    }

    private record Signature(Map<String, String> inputs, Map<String, String> outputs) {
    }

    private List<String> inputFunctions(FlowContext ctx, FlowNode node) {
        Object raw = ctx.getInputValue(node, "functions", Object.class, null);
        return raw == null ? functions() : stringList(raw instanceof List<?> values ? values : List.of());
    }

    private Lookup lookup(String query) {
        String normalized = query != null ? query.trim() : "";
        if (normalized.isBlank()) {
            return Lookup.notFound();
        }
        Catalog current = catalog();
        List<String> matches = current.folded().get(normalized.toLowerCase(Locale.ROOT));
        if (matches == null) {
            return Lookup.notFound();
        }
        Integer exact = current.indices().get(normalized);
        if (exact != null) {
            return Lookup.found(normalized, matches, exact);
        }
        if (matches.size() == 1) {
            String function = matches.getFirst();
            return Lookup.found(function, matches, current.indices().get(function));
        }
        return Lookup.ambiguous(matches);
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
