package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNpcHandle;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowPermission;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.GuiElement;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.flow.util.TextFormatter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class FlowValueCodecRegistry {
    private final Gson gson = new Gson();
    private final Map<String, FlowValueCodec<?>> codecs = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    public FlowValueCodecRegistry() {
        register(codec("string", String.class, value -> value, String::valueOf));
        register(codec("number", Number.class, Number::doubleValue, this::number));
        register(codec("integer", Integer.class, value -> value, value -> number(value).intValue()));
        register(codec("float", Float.class, value -> value, value -> number(value).floatValue()));
        register(codec("instant", Long.class, value -> value, value -> number(value).longValue()));
        register(codec("duration", Long.class, value -> value, value -> number(value).longValue()));
        register(codec("boolean", Boolean.class, value -> value, this::bool));
        register(codec("uuid", UUID.class, UUID::toString, value -> UUID.fromString(String.valueOf(value))));
        register(codec("rgb_color", Color.class, color -> String.format("#%06X", color.asRGB()), this::color));
        register(codec("color", Color.class, color -> String.format("#%06X", color.asRGB()), this::color));
        register(codec("named_text_color", NamedTextColor.class, NamedTextColor.NAMES::key,
            value -> NamedTextColor.NAMES.value(String.valueOf(value).toLowerCase(Locale.ROOT))));
        register(codec("component", Component.class, TextFormatter::formatLegacy, value -> TextFormatter.parse(String.valueOf(value))));
        register(codec("permission", FlowPermission.class, this::encodePermission, this::decodePermission));
        register(codec("resource_reference", FlowResourceReference.class, this::encodeResourceReference, this::decodeResourceReference));
        register(codec("job_reference", FlowJobReference.class, this::encodeJobReference, this::decodeJobReference));
        register(codec("npc_handle", FlowNpcHandle.class, this::encodeNpcHandle, this::decodeNpcHandle));
        register(codec("structured_value", Map.class, this::objectMap, this::objectMap));
        register(codec("structured_list", List.class, this::objectList, this::objectList));
        register(codec("json_object", JsonObject.class, JsonObject::toString, value -> JsonParser.parseString(String.valueOf(value)).getAsJsonObject()));
        register(codec("gui_definition", GuiDefinition.class, value -> gson.toJson(value), value -> gson.fromJson(String.valueOf(value), GuiDefinition.class)));
        register(codec("gui_element", GuiElement.class, value -> gson.toJson(value), value -> gson.fromJson(String.valueOf(value), GuiElement.class)));
        register(codec("scoreboard_definition", ScoreboardDefinition.class, value -> gson.toJson(value), value -> gson.fromJson(String.valueOf(value), ScoreboardDefinition.class)));
        register(codec("tab_definition", TabDefinition.class, value -> gson.toJson(value), value -> gson.fromJson(String.valueOf(value), TabDefinition.class)));
        register(codec("custom_content_definition", CustomContentDefinition.class, value -> gson.toJson(value), value -> gson.fromJson(String.valueOf(value), CustomContentDefinition.class)));
        register(codec("flow_definition", FlowGraph.class, value -> FlowSerializer.serialize(value), value -> FlowSerializer.deserialize(String.valueOf(value))));
        registerAlias("function_definition", "flow_definition");
        registerAlias("command_definition", "flow_definition");
        registerAliases("json_object", "chat_profile", "motd_profile", "message_rule", "text_template");
        registerAlias("dialog_definition", "json_object");
        registerAlias("trade_profile", "json_object");
        registerAlias("trade_definition", "json_object");
        registerAlias("loot_table_definition", "json_object");
        registerAlias("loot_pool_definition", "json_object");
        registerAlias("loot_entry_definition", "json_object");
        registerAlias("npc_definition", "json_object");
        registerAlias("advancement_tree_definition", "json_object");
        registerAlias("recipe_definition", "json_object");
        registerAlias("recipe_ingredient_definition", "json_object");
        registerAliases("structured_value", "permission_context", "formatting_policy", "item_definition", "recipe_condition", "gui_event", "dialog_result",
            "dialog_event", "scoreboard_line", "npc_event", "loot_context", "generated_loot", "advancement_criterion", "advancement_progress", "scheduled_task",
            "entity_data", "item_attribute", "item_component", "item_components", "item_modifier", "runtime_data_entry", "runtime_data_category", "schema_value", "offline_player_dossier", "tracked_player_state", "network_variable",
            "network_snapshot", "network_transfer_result");
        registerAlias("item_component_list", "structured_list");
        registerAlias("http_response", "structured_value");
        registerAliases("resource_reference", "permission_track", "gui_session", "sidebar_session", "tab_application", "merchant",
            "trade_session", "placed_content", "structure", "worldgen_project", "player_identity", "network_node", "network_route");
        registerAliases("string", "permission_group", "region", "network_scope", "flow_id", "function", "command_id", "custom_content_id", "gui_id",
            "scoreboard_id", "tab_id", "chat_id", "motd_profile_id", "message_rule_id", "recipe_id", "text_template_id", "advancement_tree_id",
            "dialog_id", "trade_profile_id", "npc_id", "loot_table_id", "worldgen_id");
        registerAlias("worldgen_job", "job_reference");
    }

    public void register(FlowValueCodec<?> codec) {
        if (codec == null || codec.id() == null || codec.id().isBlank()) {
            throw new IllegalArgumentException("Codec ID is required");
        }
        codecs.put(codec.id().toLowerCase(Locale.ROOT), codec);
    }

    public void unregister(String typeId) {
        if (typeId != null) {
            codecs.remove(typeId.toLowerCase(Locale.ROOT));
            aliases.remove(typeId.toLowerCase(Locale.ROOT));
        }
    }

    public void registerAlias(String typeId, String codecTypeId) {
        if (typeId == null || typeId.isBlank() || codecTypeId == null || codecTypeId.isBlank() || codec(codecTypeId) == null) {
            throw new IllegalArgumentException("Codec alias and registered target are required");
        }
        aliases.put(typeId.toLowerCase(Locale.ROOT), codecTypeId.toLowerCase(Locale.ROOT));
    }

    private void registerAliases(String codecTypeId, String... typeIds) {
        for (String typeId : typeIds) {
            registerAlias(typeId, codecTypeId);
        }
    }

    public boolean hasCodec(FlowTypeRef type) {
        if (type == null) {
            return false;
        }
        return switch (type.getTypeId()) {
            case "list", "set", "optional", "result" -> type.getArguments().size() == 1 && hasCodec(type.getArguments().getFirst());
            case "map" -> type.getArguments().size() == 2 && hasCodec(type.getArguments().getFirst()) && hasCodec(type.getArguments().get(1));
            default -> codec(type.getTypeId()) != null || isEnum(type.getTypeId());
        };
    }

    public Object encode(FlowTypeRef type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type.getTypeId()) {
            case "list" -> encodeIterable(type, (Iterable<?>) value, false);
            case "set" -> encodeIterable(type, (Iterable<?>) value, true);
            case "map" -> encodeMap(type, (Map<?, ?>) value);
            case "optional" -> encodeOptional(type, (Optional<?>) value);
            case "result" -> encodeResult(type, (FlowOperationResult<?>) value);
            default -> encodeScalar(type.getTypeId(), value);
        };
    }

    public Object decode(FlowTypeRef type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type.getTypeId()) {
            case "list" -> decodeList(type, value);
            case "set" -> new LinkedHashSet<>(decodeList(type, value));
            case "map" -> decodeMap(type, value);
            case "optional" -> decodeOptional(type, value);
            case "result" -> decodeResult(type, value);
            default -> decodeScalar(type.getTypeId(), value);
        };
    }

    public Map<String, FlowValueCodec<?>> codecs() {
        return Map.copyOf(codecs);
    }

    private Object encodeIterable(FlowTypeRef type, Iterable<?> values, boolean distinct) {
        requireArguments(type, 1);
        FlowTypeRef elementType = type.getArguments().getFirst();
        List<Object> encoded = new ArrayList<>();
        Set<Object> seen = distinct ? new LinkedHashSet<>() : null;
        for (Object value : values) {
            Object item = encode(elementType, value);
            if (seen == null || seen.add(item)) {
                encoded.add(item);
            }
        }
        return encoded;
    }

    private Object encodeMap(FlowTypeRef type, Map<?, ?> values) {
        requireArguments(type, 2);
        FlowTypeRef keyType = type.getArguments().getFirst();
        FlowTypeRef valueType = type.getArguments().get(1);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("key", encode(keyType, entry.getKey()));
            encoded.put("value", encode(valueType, entry.getValue()));
            entries.add(encoded);
        }
        return entries;
    }

    private Object encodeOptional(FlowTypeRef type, Optional<?> value) {
        requireArguments(type, 1);
        return value.isPresent()
            ? Map.of("present", true, "value", encode(type.getArguments().getFirst(), value.get()))
            : Map.of("present", false);
    }

    private Object encodeResult(FlowTypeRef type, FlowOperationResult<?> result) {
        requireArguments(type, 1);
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("success", result.success());
        encoded.put("value", result.value() != null ? encode(type.getArguments().getFirst(), result.value()) : null);
        encoded.put("errorCode", result.errorCode());
        encoded.put("message", result.message());
        encoded.put("details", result.details());
        return encoded;
    }

    private List<Object> decodeList(FlowTypeRef type, Object value) {
        requireArguments(type, 1);
        if (!(value instanceof Iterable<?> values)) {
            throw new IllegalArgumentException("Expected collection value for " + type);
        }
        FlowTypeRef elementType = type.getArguments().getFirst();
        List<Object> decoded = new ArrayList<>();
        for (Object item : values) {
            decoded.add(decode(elementType, item));
        }
        return decoded;
    }

    private Map<Object, Object> decodeMap(FlowTypeRef type, Object value) {
        requireArguments(type, 2);
        if (!(value instanceof Iterable<?> entries)) {
            throw new IllegalArgumentException("Expected map entries for " + type);
        }
        Map<Object, Object> decoded = new LinkedHashMap<>();
        for (Object item : entries) {
            Map<?, ?> entry = map(item);
            decoded.put(decode(type.getArguments().getFirst(), entry.get("key")), decode(type.getArguments().get(1), entry.get("value")));
        }
        return decoded;
    }

    private Optional<?> decodeOptional(FlowTypeRef type, Object value) {
        requireArguments(type, 1);
        Map<?, ?> encoded = map(value);
        return bool(encoded.get("present")) ? Optional.ofNullable(decode(type.getArguments().getFirst(), encoded.get("value"))) : Optional.empty();
    }

    private FlowOperationResult<?> decodeResult(FlowTypeRef type, Object value) {
        requireArguments(type, 1);
        Map<?, ?> encoded = map(value);
        boolean success = bool(encoded.get("success"));
        Object decodedValue = encoded.get("value") != null ? decode(type.getArguments().getFirst(), encoded.get("value")) : null;
        return new FlowOperationResult<>(success, decodedValue, string(encoded.get("errorCode")), string(encoded.get("message")), objectMap(encoded.get("details")));
    }

    private Object encodeScalar(String typeId, Object value) {
        FlowValueCodec<Object> codec = codec(typeId);
        if (codec != null) {
            return codec.encode(value);
        }
        FlowDataType type = FlowDataType.fromString(typeId);
        if (type.getJavaType() != null && type.getJavaType().isEnum() && value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("No codec registered for type " + typeId);
    }

    private Object decodeScalar(String typeId, Object value) {
        FlowValueCodec<Object> codec = codec(typeId);
        if (codec != null) {
            return codec.decode(value);
        }
        FlowDataType type = FlowDataType.fromString(typeId);
        Class<?> javaType = type.getJavaType();
        if (javaType != null && javaType.isEnum()) {
            return enumValue(javaType, String.valueOf(value));
        }
        throw new IllegalArgumentException("No codec registered for type " + typeId);
    }

    private boolean isEnum(String typeId) {
        Class<?> javaType = FlowDataType.fromString(typeId).getJavaType();
        return javaType != null && javaType.isEnum();
    }

    private Object enumValue(Class<?> type, String value) {
        for (Object constant : type.getEnumConstants()) {
            if (constant instanceof Enum<?> enumValue && enumValue.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + value);
    }

    private Object encodePermission(FlowPermission permission) {
        return Map.of("node", permission.node(), "context", permission.context());
    }

    private FlowPermission decodePermission(Object value) {
        if (value instanceof String string) {
            return new FlowPermission(string);
        }
        Map<?, ?> encoded = map(value);
        Map<String, String> context = new LinkedHashMap<>();
        map(encoded.get("context")).forEach((key, entry) -> context.put(String.valueOf(key), String.valueOf(entry)));
        return new FlowPermission(string(encoded.get("node")), context);
    }

    private Object encodeResourceReference(FlowResourceReference reference) {
        return Map.of("kind", reference.kind(), "id", reference.id(), "owner", reference.owner(), "available", reference.available(),
            "metadata", reference.metadata());
    }

    private FlowResourceReference decodeResourceReference(Object value) {
        Map<?, ?> encoded = map(value);
        return new FlowResourceReference(string(encoded.get("kind")), string(encoded.get("id")), string(encoded.get("owner")),
            bool(encoded.get("available")), objectMap(encoded.get("metadata")));
    }

    private Object encodeJobReference(FlowJobReference<?> reference) {
        FlowJobReference.Snapshot<?> snapshot = reference.snapshot();
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", snapshot.id());
        encoded.put("kind", snapshot.kind());
        encoded.put("owner", snapshot.owner());
        encoded.put("createdAt", snapshot.createdAt().toString());
        encoded.put("state", snapshot.state().name());
        encoded.put("progress", snapshot.progress());
        encoded.put("metadata", snapshot.metadata());
        encoded.put("cancellationRequested", snapshot.cancellationRequested());
        encoded.put("outcome", encodeUntypedResult(snapshot.outcome()));
        return encoded;
    }

    private FlowJobReference<?> decodeJobReference(Object value) {
        Map<?, ?> encoded = map(value);
        FlowJobReference.State state;
        try {
            state = FlowJobReference.State.valueOf(string(encoded.get("state")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid job state: " + encoded.get("state"));
        }
        FlowOperationResult<Object> outcome = decodeUntypedResult(encoded.get("outcome"));
        FlowJobReference.Snapshot<Object> snapshot = new FlowJobReference.Snapshot<>(
            string(encoded.get("id")),
            string(encoded.get("kind")),
            string(encoded.get("owner")),
            Instant.parse(string(encoded.get("createdAt"))),
            state,
            number(encoded.get("progress")).doubleValue(),
            objectMap(encoded.get("metadata")),
            bool(encoded.get("cancellationRequested")),
            outcome
        );
        return FlowJobReference.restore(snapshot);
    }

    private Object encodeNpcHandle(FlowNpcHandle handle) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("definitionId", handle.definitionId());
        encoded.put("entityUuid", handle.entityUuid());
        encoded.put("packetBacked", handle.packetBacked());
        encoded.put("active", handle.active());
        encoded.put("world", handle.world());
        encoded.put("x", handle.x());
        encoded.put("y", handle.y());
        encoded.put("z", handle.z());
        encoded.put("yaw", handle.yaw());
        encoded.put("pitch", handle.pitch());
        return encoded;
    }

    private FlowNpcHandle decodeNpcHandle(Object value) {
        Map<?, ?> encoded = map(value);
        return new FlowNpcHandle(string(encoded.get("definitionId")), string(encoded.get("entityUuid")), bool(encoded.get("packetBacked")),
            bool(encoded.get("active")), string(encoded.get("world")), number(encoded.get("x")).doubleValue(), number(encoded.get("y")).doubleValue(),
            number(encoded.get("z")).doubleValue(), number(encoded.get("yaw")).floatValue(), number(encoded.get("pitch")).floatValue());
    }

    private Object encodeUntypedResult(FlowOperationResult<?> result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("success", result.success());
        encoded.put("value", result.value());
        encoded.put("errorCode", result.errorCode());
        encoded.put("message", result.message());
        encoded.put("details", result.details());
        return encoded;
    }

    private FlowOperationResult<Object> decodeUntypedResult(Object value) {
        if (value == null) {
            return null;
        }
        Map<?, ?> encoded = map(value);
        return new FlowOperationResult<>(bool(encoded.get("success")), encoded.get("value"), string(encoded.get("errorCode")),
            string(encoded.get("message")), objectMap(encoded.get("details")));
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : Double.parseDouble(String.valueOf(value));
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value: " + value);
    }

    private Color color(Object value) {
        String hex = String.valueOf(value).strip().replace("#", "");
        if (hex.length() != 6) {
            throw new IllegalArgumentException("Invalid RGB color: " + value);
        }
        return Color.fromRGB(Integer.parseInt(hex, 16));
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        map(value).forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    private List<Object> objectList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected list value");
        return new ArrayList<>(list);
    }

    private void requireArguments(FlowTypeRef type, int count) {
        if (type.getArguments().size() != count) {
            throw new IllegalArgumentException("Type " + type.getTypeId() + " requires " + count + " argument(s)");
        }
    }

    @SuppressWarnings("unchecked")
    private FlowValueCodec<Object> codec(String typeId) {
        String normalized = typeId.toLowerCase(Locale.ROOT);
        String target = aliases.getOrDefault(normalized, normalized);
        return (FlowValueCodec<Object>) codecs.get(target);
    }

    private <T> FlowValueCodec<T> codec(String id, Class<T> type, Function<T, Object> encoder, Function<Object, T> decoder) {
        return new FlowValueCodec<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<T> javaType() {
                return type;
            }

            @Override
            public Object encode(T value) {
                return encoder.apply(value);
            }

            @Override
            public T decode(Object value) {
                return decoder.apply(value);
            }
        };
    }
}
