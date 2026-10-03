package restudio.resync.qa;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import restudio.resync.advancement.AdvancementService;
import restudio.resync.modules.AdvancementModule;
import restudio.resync.server.ProtocolRequestAuthority;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class QaAdvancementAdapter {
    private static final Map<String, Object> DESCRIPTION = Map.of("operations", List.of(
        operation("advancement.inspect", "Inspect committed custom trees, native runtime availability, and node criteria", List.of(), List.of("treeId")),
        operation("advancement.progress", "Read native advancement progress for an online player",
            List.of("treeId", "playerId"), List.of("nodeId")),
        operation("advancement.evaluate", "Evaluate a criterion's built-in conditions without awarding progress",
            List.of("treeId", "nodeId", "criterion", "playerId", "revision", "checksum"), List.of("inputs")),
        operation("advancement.grant", "Award a native criterion directly, bypassing its conditions",
            List.of("treeId", "nodeId", "criterion", "playerId", "revision", "checksum"), List.of()),
        operation("advancement.revoke", "Revoke a native criterion from an online player",
            List.of("treeId", "nodeId", "criterion", "playerId", "revision", "checksum"), List.of())),
        "permission", "resync.qa", "parentSemantics", "Parents organize the native display. They do not gate criterion awards",
        "grantSemantics", "Native rewards apply. ReSync onComplete actions are invoked by event and quest completion paths",
        "completion", "Synchronous native progress access has finished. Player save files are managed by Paper",
        "resourceWrites", "Use the existing typed resource QA operations");
    private final Supplier<AdvancementModule> module;

    public QaAdvancementAdapter(Supplier<AdvancementModule> module) {
        this.module = Objects.requireNonNull(module, "Advancement module supplier is required");
    }

    public Map<String, Object> describe() {
        return DESCRIPTION;
    }

    public CompletionStage<Map<String, Object>> invoke(CommandSender actor, String operation, Map<String, Object> input) {
        try {
            if (ProtocolRequestAuthority.trustedOperatorId(actor) == null) {
                throw new SecurityException("ReSync QA permission and server thread admission are required");
            }
            AdvancementModule active = Objects.requireNonNull(module.get(), "Advancement module is unavailable");
            Map<String, Object> request = Objects.requireNonNull(input, "Advancement input is required");
            Map<String, Object> result = switch (operation) {
                case "advancement.inspect" -> inspect(active, request);
                case "advancement.progress" -> progress(active, request);
                case "advancement.evaluate", "advancement.grant", "advancement.revoke" -> criterion(active, operation, request);
                default -> throw new IllegalArgumentException("Unknown advancement operation: " + operation);
            };
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private Map<String, Object> inspect(AdvancementModule active, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(active.capabilityPayload());
        if (!Boolean.TRUE.equals(result.get("supported")) || !Boolean.TRUE.equals(result.get("active"))) {
            result.put("trees", List.of());
            return result;
        }
        List<AdvancementModule.TreeState> trees = input.containsKey("treeId")
            ? List.of(active.treeState(text(input, "treeId"))) : active.treeStates();
        result.put("trees", trees.stream().map(state -> {
            Map<String, Object> tree = identity(state);
            tree.put("definition", json(state.definition()));
            return tree;
        }).toList());
        return result;
    }

    private Map<String, Object> progress(AdvancementModule active, Map<String, Object> input) {
        Player player = player(input);
        AdvancementModule.TreeState state = active.treeState(text(input, "treeId"));
        JsonObject nodes = state.definition().getAsJsonObject("nodes");
        List<String> ids;
        if (input.containsKey("nodeId")) {
            String id = text(input, "nodeId");
            node(state, id, false);
            ids = List.of(id);
        } else {
            ids = nodes.keySet().stream().sorted().toList();
        }
        Map<String, Object> result = identity(state);
        result.put("playerId", player.getUniqueId().toString());
        result.put("nodes", ids.stream().map(id -> Map.of("nodeId", id,
            "progress", active.advancementService().inspect(player, state.stamp().id(), id))).toList());
        return result;
    }

    private Map<String, Object> criterion(AdvancementModule active, String operation, Map<String, Object> input) {
        Player player = player(input);
        AdvancementModule.TreeState state = active.treeState(text(input, "treeId"));
        if (number(input, "revision") != state.stamp().revision() || !text(input, "checksum").equals(state.stamp().payloadHash())) {
            throw new IllegalArgumentException("Advancement tree revision or checksum changed. Inspect its current state");
        }
        String nodeId = text(input, "nodeId");
        String criterionId = text(input, "criterion");
        JsonObject node = node(state, nodeId, true);
        JsonObject criteria = object(node, "criteria");
        if (!criteria.has(criterionId) || !criteria.get(criterionId).isJsonObject()) {
            throw new IllegalArgumentException("Unknown advancement criterion: " + criterionId);
        }
        JsonObject criterion = criteria.getAsJsonObject(criterionId);
        Map<String, Object> result = identity(state);
        result.put("playerId", player.getUniqueId().toString());
        result.put("nodeId", nodeId);
        result.put("criterion", criterionId);
        AdvancementService service = active.advancementService();
        active.requireCurrent(state);
        Map<String, Object> before = service.inspect(player, state.stamp().id(), nodeId);
        if (!Boolean.TRUE.equals(before.get("registered"))) {
            throw new IllegalStateException("The committed advancement node is not registered in the native runtime");
        }
        result.put("before", before);
        if ("advancement.evaluate".equals(operation)) {
            Map<String, Object> inputs = inputs(input);
            result.put("conditionsMatch", active.conditionsMatch(player, state.stamp().id(), nodeId, criterionId, inputs));
            result.put("flowPredicateConfigured", nonempty(criterion, "predicateFlowId"));
            result.put("functionPredicateConfigured", !object(criterion, "predicate").isEmpty());
            result.put("externalPredicatesEvaluated", false);
            result.put("progressAwarded", false);
        } else {
            active.requireCurrent(state);
            boolean changed = "advancement.grant".equals(operation)
                ? service.grant(player, state.stamp().id(), nodeId, criterionId)
                : service.revoke(player, state.stamp().id(), nodeId, criterionId);
            result.put("changed", changed);
            result.put("conditionsBypassed", "advancement.grant".equals(operation));
        }
        active.requireCurrent(state);
        result.put("after", service.inspect(player, state.stamp().id(), nodeId));
        return result;
    }

    private JsonObject node(AdvancementModule.TreeState state, String id, boolean enabled) {
        JsonObject tree = state.definition();
        JsonObject nodes = object(tree, "nodes");
        if (!nodes.has(id) || !nodes.get(id).isJsonObject()) throw new IllegalArgumentException("Unknown advancement node: " + id);
        JsonObject node = nodes.getAsJsonObject(id);
        if (enabled && (!enabled(tree) || !enabled(node))) throw new IllegalArgumentException("Advancement tree or node is disabled");
        return node;
    }

    private Player player(Map<String, Object> input) {
        String text = text(input, "playerId");
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Player ID must be a complete UUID");
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("Player must be online");
        return player;
    }

    private Map<String, Object> identity(AdvancementModule.TreeState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("treeId", state.stamp().id());
        result.put("revision", state.stamp().revision());
        result.put("checksum", state.stamp().payloadHash());
        result.put("mutationId", state.stamp().mutationValue());
        return result;
    }

    private String text(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }

    private long number(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof Number value)) throw new IllegalArgumentException(key + " must be an integer");
        return new BigDecimal(value.toString()).longValueExact();
    }

    private Map<String, Object> inputs(Map<String, Object> input) {
        Object value = input.get("inputs");
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("inputs must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }

    private boolean enabled(JsonObject value) {
        return !value.has("enabled") || value.get("enabled").getAsBoolean();
    }

    private boolean nonempty(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() && !value.get(key).getAsString().isBlank();
    }

    private Object json(JsonElement value) {
        if (value.isJsonNull()) return null;
        if (value.isJsonArray()) return value.getAsJsonArray().asList().stream().map(this::json).toList();
        if (value.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), json(entry.getValue())));
            return result;
        }
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsBigDecimal();
        return value.getAsString();
    }

    private static Map<String, Object> operation(String id, String description, List<String> required, List<String> optional) {
        return Map.of("id", id, "description", description, "input", Map.of("required", required, "optional", optional));
    }
}
