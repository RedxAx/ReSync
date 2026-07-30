package restudio.flow.data;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class FlowDataType {
    public static final FlowDataType EXECUTION = new FlowDataType("execution", null, Void.class, null, 0xFFFFFF);
    public static final FlowDataType ANY = new FlowDataType("any", null, Object.class, null, 0x808080);
    public static final FlowDataType STRING = new FlowDataType("string", null, String.class, null, 0xDA00FF);
    public static final FlowDataType FUNCTION = new FlowDataType("function", STRING, String.class, null, 0xFFAA55);
    public static final FlowDataType FLOW_ID = new FlowDataType("flow_id", STRING, String.class, null, 0x7AA2F7);
    public static final FlowDataType COMMAND_ID = new FlowDataType("command_id", STRING, String.class, null, 0x9C7BEF);
    public static final FlowDataType CUSTOM_CONTENT_ID = new FlowDataType("custom_content_id", STRING, String.class, null, 0x78D64B);
    public static final FlowDataType GUI_ID = new FlowDataType("gui_id", STRING, String.class, null, 0x4FA3FF);
    public static final FlowDataType SCOREBOARD_ID = new FlowDataType("scoreboard_id", STRING, String.class, null, 0x3E8BFF);
    public static final FlowDataType TAB_ID = new FlowDataType("tab_id", STRING, String.class, null, 0x61B5FF);
    public static final FlowDataType CHAT_ID = new FlowDataType("chat_id", STRING, String.class, null, 0x5CC8FF);
    public static final FlowDataType MOTD_PROFILE_ID = new FlowDataType("motd_profile_id", STRING, String.class, null, 0xE066FF);
    public static final FlowDataType MESSAGE_RULE_ID = new FlowDataType("message_rule_id", STRING, String.class, null, 0xB96BFF);
    public static final FlowDataType RECIPE_ID = new FlowDataType("recipe_id", STRING, String.class, null, 0x71C76F);
    public static final FlowDataType TEXT_TEMPLATE_ID = new FlowDataType("text_template_id", STRING, String.class, null, 0xE5A5FF);
    public static final FlowDataType ADVANCEMENT_TREE_ID = new FlowDataType("advancement_tree_id", STRING, String.class, null, 0xFFD34E);
    public static final FlowDataType DIALOG_ID = new FlowDataType("dialog_id", STRING, String.class, null, 0xC274FF);
    public static final FlowDataType TRADE_PROFILE_ID = new FlowDataType("trade_profile_id", STRING, String.class, null, 0xD6A84B);
    public static final FlowDataType NPC_ID = new FlowDataType("npc_id", STRING, String.class, null, 0xBF7A4A);
    public static final FlowDataType LOOT_TABLE_ID = new FlowDataType("loot_table_id", STRING, String.class, null, 0xE87948);
    public static final FlowDataType WORLDGEN_ID = new FlowDataType("worldgen_id", STRING, String.class, null, 0x1DBBB7);
    public static final FlowDataType FLOW_DEFINITION = new FlowDataType("flow_definition", null, FlowGraph.class, null, 0x7AA2F7);
    public static final FlowDataType FUNCTION_DEFINITION = new FlowDataType("function_definition", FLOW_DEFINITION, FlowGraph.class, null, 0xFFAA55);
    public static final FlowDataType COMMAND_DEFINITION = new FlowDataType("command_definition", FLOW_DEFINITION, FlowGraph.class, null, 0x9C7BEF);
    public static final FlowDataType NUMBER = new FlowDataType("number", null, Number.class, null, 0x00FF93);
    public static final FlowDataType INTEGER = new FlowDataType("integer", NUMBER, Integer.class, null, 0x00D982);
    public static final FlowDataType FLOAT = new FlowDataType("float", NUMBER, Float.class, null, 0x00BFFF);
    public static final FlowDataType INSTANT = new FlowDataType("instant", NUMBER, Long.class, null, 0x37C8FF);
    public static final FlowDataType DURATION = new FlowDataType("duration", NUMBER, Long.class, null, 0x45B7E8);
    public static final FlowDataType BOOLEAN = new FlowDataType("boolean", null, Boolean.class, null, 0xD20000);
    public static final FlowDataType ENTITY = new FlowDataType("entity", null, Entity.class, FlowEntityRef.class, 0x8B4513);
    public static final FlowDataType LIVING_ENTITY = new FlowDataType("living_entity", ENTITY, LivingEntity.class, FlowEntityRef.class, 0xA0522D);
    public static final FlowDataType PLAYER = new FlowDataType("player", LIVING_ENTITY, Player.class, FlowEntityRef.class, 0x0066FF);
    public static final FlowDataType MATERIAL = new FlowDataType("material", null, Material.class, null, 0x00AA00);
    public static final FlowDataType BLOCK = new FlowDataType("block", null, Block.class, FlowBlock.class, 0x228B22);
    public static final FlowDataType ITEM = new FlowDataType("item", MATERIAL, ItemStack.class, FlowItem.class, 0x32CD32);
    public static final FlowDataType WORLD = new FlowDataType("world", null, World.class, FlowWorldRef.class, 0x00CED1);
    public static final FlowDataType BIOME = new FlowDataType("biome", null, Biome.class, null, 0x20B2AA);
    public static final FlowDataType VECTOR = new FlowDataType("vector", null, Vector.class, null, 0x7FFFD4);
    public static final FlowDataType LOCATION = new FlowDataType("location", VECTOR, Location.class, null, 0xFFA500);
    public static final FlowDataType VECTOR2 = new FlowDataType("vector2", VECTOR, Vector.class, null, 0x7FFFD4);
    public static final FlowDataType VECTOR3 = new FlowDataType("vector3", VECTOR, Vector.class, null, 0x40E0D0);
    public static final FlowDataType SEED = new FlowDataType("seed", NUMBER, Integer.class, null, 0xFFD700);
    public static final FlowDataType COLOR = new FlowDataType("color", null, Color.class, null, 0xFF66CC);
    public static final FlowDataType RGB_COLOR = new FlowDataType("rgb_color", COLOR, Color.class, null, 0xFF66CC);
    public static final FlowDataType NAMED_TEXT_COLOR = new FlowDataType("named_text_color", null, NamedTextColor.class, null, 0xFFD166);
    public static final FlowDataType UUID = new FlowDataType("uuid", null, UUID.class, null, 0x708090);
    public static final FlowDataType GAMEMODE = new FlowDataType("gamemode", null, GameMode.class, null, 0x4169E1);
    public static final FlowDataType DIFFICULTY = new FlowDataType("difficulty", null, Difficulty.class, null, 0xDC143C);
    public static final FlowDataType ENTITY_TYPE = new FlowDataType("entity_type", null, EntityType.class, null, 0xCD853F);
    public static final FlowDataType ENCHANTMENT = new FlowDataType("enchantment", null, Enchantment.class, FlowEnchantment.class, 0x9370DB);
    public static final FlowDataType ITEMSTACK = ITEM;
    public static final FlowDataType INVENTORY = new FlowDataType("inventory", null, Inventory.class, null, 0x4682B4);
    public static final FlowDataType POTION_EFFECT = new FlowDataType("potion_effect", null, PotionEffectType.class, null, 0xFF1493);
    public static final FlowDataType SOUND = new FlowDataType("sound", null, Sound.class, null, 0xFFB347);
    public static final FlowDataType ADVANCEMENT = new FlowDataType("advancement", null, Advancement.class, null, 0xFFD700);
    public static final FlowDataType PERMISSION_GROUP = new FlowDataType("permission_group", null, String.class, null, 0x6A5ACD);
    public static final FlowDataType SCOREBOARD = new FlowDataType("scoreboard", null, Scoreboard.class, null, 0x1E90FF);
    public static final FlowDataType TEAM = new FlowDataType("team", null, Team.class, null, 0x00BFFF);
    public static final FlowDataType REGION = new FlowDataType("region", null, String.class, null, 0x9ACD32);
    public static final FlowDataType COMPONENT = new FlowDataType("component", STRING, Component.class, null, 0xE066FF);
    public static final FlowDataType PERMISSION = new FlowDataType("permission", null, FlowPermission.class, null, 0xB96BFF);
    public static final FlowDataType RESOURCE_REFERENCE = new FlowDataType("resource_reference", null, FlowResourceReference.class, null, 0x5CC8FF);
    public static final FlowDataType VARIABLE_REFERENCE = new FlowDataType("variable_reference", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x5CC8FF);
    public static final FlowDataType TIMER_REFERENCE = new FlowDataType("timer_reference", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x45B7E8);
    public static final FlowDataType SCHEDULE_REFERENCE = new FlowDataType("schedule_reference", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x7AA2F7);
    public static final FlowDataType RESULT = new FlowDataType("result", null, FlowOperationResult.class, null, 0x55D68A);
    public static final FlowDataType JOB_REFERENCE = new FlowDataType("job_reference", null, FlowJobReference.class, null, 0xFFB347);
    public static final FlowDataType SCHEDULED_TASK = new FlowDataType("scheduled_task", null, Map.class, null, 0x7AA2F7);
    public static final FlowDataType GUI_DEFINITION = new FlowDataType("gui_definition", null, GuiDefinition.class, null, 0x4FA3FF);
    public static final FlowDataType SCOREBOARD_DEFINITION = new FlowDataType("scoreboard_definition", null, ScoreboardDefinition.class, null, 0x3E8BFF);
    public static final FlowDataType TAB_DEFINITION = new FlowDataType("tab_definition", null, TabDefinition.class, null, 0x61B5FF);
    public static final FlowDataType CUSTOM_CONTENT_DEFINITION = new FlowDataType("custom_content_definition", null, CustomContentDefinition.class, null, 0x78D64B);
    public static final FlowDataType CHAT_PROFILE = new FlowDataType("chat_profile", null, JsonObject.class, null, 0x5CC8FF);
    public static final FlowDataType MOTD_PROFILE = new FlowDataType("motd_profile", null, JsonObject.class, null, 0xE066FF);
    public static final FlowDataType MESSAGE_RULE = new FlowDataType("message_rule", null, JsonObject.class, null, 0xB96BFF);
    public static final FlowDataType TEXT_TEMPLATE = new FlowDataType("text_template", null, JsonObject.class, null, 0xE5A5FF);
    public static final FlowDataType DIALOG_DEFINITION = new FlowDataType("dialog_definition", null, JsonObject.class, null, 0xC274FF);
    public static final FlowDataType TRADE_PROFILE = new FlowDataType("trade_profile", null, JsonObject.class, null, 0xD6A84B);
    public static final FlowDataType TRADE_DEFINITION = new FlowDataType("trade_definition", null, JsonObject.class, null, 0xE2B95E);
    public static final FlowDataType LOOT_TABLE_DEFINITION = new FlowDataType("loot_table_definition", null, JsonObject.class, null, 0xE87948);
    public static final FlowDataType LOOT_POOL_DEFINITION = new FlowDataType("loot_pool_definition", null, JsonObject.class, null, 0xD9683C);
    public static final FlowDataType LOOT_ENTRY_DEFINITION = new FlowDataType("loot_entry_definition", null, JsonObject.class, null, 0xC95A34);
    public static final FlowDataType NPC_DEFINITION = new FlowDataType("npc_definition", null, JsonObject.class, null, 0xBF7A4A);
    public static final FlowDataType ADVANCEMENT_TREE_DEFINITION = new FlowDataType("advancement_tree_definition", null, JsonObject.class, null, 0xFFD34E);
    public static final FlowDataType RECIPE_DEFINITION = new FlowDataType("recipe_definition", null, JsonObject.class, null, 0x71C76F);
    public static final FlowDataType RECIPE_INGREDIENT_DEFINITION = new FlowDataType("recipe_ingredient_definition", null, JsonObject.class, null, 0x62B861);
    public static final FlowDataType PERMISSION_TRACK = new FlowDataType("permission_track", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x765BC4);
    public static final FlowDataType PERMISSION_CONTEXT = new FlowDataType("permission_context", null, Map.class, null, 0x8A68D6);
    public static final FlowDataType TEXT_DECORATION = new FlowDataType("text_decoration", null, TextDecoration.class, null, 0xE5A5FF);
    public static final FlowDataType FORMATTING_POLICY = new FlowDataType("formatting_policy", null, Map.class, null, 0xCA82E8);
    public static final FlowDataType ITEM_DEFINITION = new FlowDataType("item_definition", null, Map.class, null, 0x70C95E);
    public static final FlowDataType RECIPE_CONDITION = new FlowDataType("recipe_condition", null, Map.class, null, 0x57A95A);
    public static final FlowDataType GUI_SESSION = new FlowDataType("gui_session", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x348CDD);
    public static final FlowDataType GUI_ELEMENT = new FlowDataType("gui_element", null, GuiElement.class, null, 0x69B8FF);
    public static final FlowDataType GUI_EVENT = new FlowDataType("gui_event", null, Map.class, null, 0x2F78BE);
    public static final FlowDataType DIALOG_RESULT = new FlowDataType("dialog_result", null, Map.class, null, 0xB25FE6);
    public static final FlowDataType DIALOG_EVENT = new FlowDataType("dialog_event", null, Map.class, null, 0x9F4CD5);
    public static final FlowDataType SIDEBAR_SESSION = new FlowDataType("sidebar_session", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x3479D3);
    public static final FlowDataType SCOREBOARD_LINE = new FlowDataType("scoreboard_line", null, Map.class, null, 0x4A9AF0);
    public static final FlowDataType DISPLAY_SLOT = new FlowDataType("display_slot", null, DisplaySlot.class, null, 0x1775D1);
    public static final FlowDataType TAB_APPLICATION = new FlowDataType("tab_application", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x4AA4EE);
    public static final FlowDataType NPC_HANDLE = new FlowDataType("npc_handle", null, FlowNpcHandle.class, null, 0xA96437);
    public static final FlowDataType NPC_EVENT = new FlowDataType("npc_event", null, Map.class, null, 0x99542C);
    public static final FlowDataType MERCHANT = new FlowDataType("merchant", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0xC69845);
    public static final FlowDataType TRADE_SESSION = new FlowDataType("trade_session", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0xD4A64A);
    public static final FlowDataType LOOT_CONTEXT = new FlowDataType("loot_context", null, Map.class, null, 0xC95531);
    public static final FlowDataType GENERATED_LOOT = new FlowDataType("generated_loot", null, Map.class, null, 0xB94A29);
    public static final FlowDataType ADVANCEMENT_CRITERION = new FlowDataType("advancement_criterion", null, Map.class, null, 0xE6BD35);
    public static final FlowDataType ADVANCEMENT_PROGRESS = new FlowDataType("advancement_progress", null, Map.class, null, 0xD9A820);
    public static final FlowDataType PLACED_CONTENT = new FlowDataType("placed_content", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x5EB63E);
    public static final FlowDataType ENTITY_DATA = new FlowDataType("entity_data", null, Map.class, null, 0x9B6847);
    public static final FlowDataType ITEM_ATTRIBUTE = new FlowDataType("item_attribute", null, Map.class, null, 0x5FCB82);
    public static final FlowDataType ITEM_COMPONENT = new FlowDataType("item_component", null, Map.class, null, 0x4CBA73);
    public static final FlowDataType ITEM_COMPONENTS = new FlowDataType("item_components", null, Map.class, null, 0x55C77C);
    public static final FlowDataType ITEM_COMPONENT_LIST = new FlowDataType("item_component_list", null, List.class, null, 0x63D18A);
    public static final FlowDataType ITEM_MODIFIER = new FlowDataType("item_modifier", null, Map.class, null, 0x42A766);
    public static final FlowDataType RUNTIME_DATA_ENTRY = new FlowDataType("runtime_data_entry", null, Map.class, null, 0x327A8F);
    public static final FlowDataType RUNTIME_DATA_CATEGORY = new FlowDataType("runtime_data_category", null, Map.class, null, 0x3B8EA3);
    public static final FlowDataType SCHEMA_VALUE = new FlowDataType("schema_value", null, Map.class, null, 0x38975B);
    public static final FlowDataType STRUCTURE = new FlowDataType("structure", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0xA68962);
    public static final FlowDataType WORLDGEN_PROJECT = new FlowDataType("worldgen_project", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x1DBBB7);
    public static final FlowDataType WORLDGEN_JOB = new FlowDataType("worldgen_job", JOB_REFERENCE, FlowJobReference.class, null, 0xE89B32);
    public static final FlowDataType WORLDGEN_FEATURE = new FlowDataType("worldgen_feature", STRING, String.class, null, 0x5CAD4A);
    public static final FlowDataType WORLDGEN_FEATURES = new FlowDataType("worldgen_features", STRING, String.class, null, 0x73C95C);
    public static final FlowDataType WORLDGEN_STRUCTURES = new FlowDataType("worldgen_structures", STRING, String.class, null, 0xC49A5A);
    public static final FlowDataType WORLDGEN_SPAWNS = new FlowDataType("worldgen_spawns", STRING, String.class, null, 0xD06E57);
    public static final FlowDataType PLAYER_IDENTITY = new FlowDataType("player_identity", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x287EE8);
    public static final FlowDataType OFFLINE_PLAYER_DOSSIER = new FlowDataType("offline_player_dossier", null, Map.class, null, 0x3569B7);
    public static final FlowDataType TRACKED_PLAYER_STATE = new FlowDataType("tracked_player_state", null, Map.class, null, 0x22599D);
    public static final FlowDataType NETWORK_NODE = new FlowDataType("network_node", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x3F73D6);
    public static final FlowDataType NETWORK_ROUTE = new FlowDataType("network_route", RESOURCE_REFERENCE, FlowResourceReference.class, null, 0x4F83E6);
    public static final FlowDataType NETWORK_SCOPE = new FlowDataType("network_scope", null, String.class, null, 0x5D91F1);
    public static final FlowDataType NETWORK_VARIABLE = new FlowDataType("network_variable", null, Map.class, null, 0x6A9DF3);
    public static final FlowDataType NETWORK_SNAPSHOT = new FlowDataType("network_snapshot", null, Map.class, null, 0x557FC7);
    public static final FlowDataType NETWORK_TRANSFER_RESULT = new FlowDataType("network_transfer_result", null, Map.class, null, 0x436CAD);
    public static final FlowDataType HTTP_RESPONSE = new FlowDataType("http_response", null, Map.class, null, 0x4E9ECF);
    public static final FlowDataType OPTIONAL = new FlowDataType("optional", null, Optional.class, null, 0xA0A0A0);
    public static final FlowDataType JSON_OBJECT = new FlowDataType("json_object", null, JsonObject.class, null, 0x4B0082);
    public static final FlowDataType LIST = new FlowDataType("list", null, List.class, null, 0xFF69B4);
    public static final FlowDataType MAP = new FlowDataType("map", null, Map.class, null, 0x9932CC);
    public static final FlowDataType SET = new FlowDataType("set", null, Set.class, null, 0xFF4500);
    public static final FlowDataType QUEUE = new FlowDataType("queue", null, Queue.class, null, 0x2E8B57);
    public static final FlowDataType STACK = new FlowDataType("stack", null, Deque.class, null, 0x4682B4);

    private final String id;
    private final FlowDataType parent;
    private final Class<?> javaType;
    private final Class<? extends FlowDataObject> dataClass;
    private final int color;
    private final String owner;
    private final boolean resolved;

    public FlowDataType(String id, FlowDataType parent, Class<?> javaType, Class<? extends FlowDataObject> dataClass, int color) {
        this(id, parent, javaType, dataClass, color, "builtin", true);
    }

    private FlowDataType(String id, FlowDataType parent, Class<?> javaType, Class<? extends FlowDataObject> dataClass, int color,
                         String owner, boolean resolved) {
        this.id = id;
        this.parent = parent;
        this.javaType = javaType;
        this.dataClass = dataClass;
        this.color = color;
        this.owner = owner;
        this.resolved = resolved;
    }

    public String getId() {
        return id;
    }

    public String getCanonicalId() {
        if (id != null && id.contains(":")) {
            return id;
        }
        String namespace = owner == null || owner.isBlank() || "builtin".equals(owner) ? "resync" : owner.toLowerCase(Locale.ROOT);
        return namespace + ":" + id;
    }

    public FlowDataType getParent() {
        return parent;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public Class<? extends FlowDataObject> getDataClass() {
        return dataClass;
    }

    public int getColor() {
        return 0xFF000000 | color;
    }

    public String getOwner() {
        return owner;
    }

    public boolean isResolved() {
        return resolved;
    }

    public boolean isAssignableFrom(FlowDataType other) {
        if (this == ANY || equals(other)) return true;
        if (other == null) return false;
        if (other.parent != null && (equals(other.parent) || isAssignableFrom(other.parent))) return true;
        return false;
    }

    private static final Map<String, FlowDataType> REGISTRY = new ConcurrentHashMap<>();
    private static final Map<String, String> OWNERS = new ConcurrentHashMap<>();

    static {
        for (Field field : FlowDataType.class.getDeclaredFields()) {
            if (field.getType() == FlowDataType.class && Modifier.isStatic(field.getModifiers())) {
                try {
                    FlowDataType type = (FlowDataType) field.get(null);
                    if (type != null) {
                        REGISTRY.put(type.id, type);
                        OWNERS.put(type.id, "builtin");
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        REGISTRY.put("itemstack", ITEM);
        REGISTRY.put("flow", EXECUTION);
        OWNERS.put("itemstack", "builtin");
        OWNERS.put("flow", "builtin");
    }

    public static FlowDataType fromString(String name) {
        if (name == null || name.isEmpty()) return ANY;
        String normalized = name.toLowerCase(Locale.ROOT);
        FlowDataType type = REGISTRY.get(normalized);
        if (type != null) {
            return type;
        }
        String owner = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "unresolved";
        return new FlowDataType(normalized, null, Object.class, null, 0x808080, owner, false);
    }

    public static List<FlowDataType> values() {
        return REGISTRY.values().stream().distinct().toList();
    }

    public static Map<String, FlowDataType> registeredTypes() {
        return Map.copyOf(REGISTRY);
    }

    public static FlowDataType registerExtensionType(String owner, FlowDataType definition) {
        if (owner == null || owner.isBlank() || definition == null || definition.id == null || definition.id.isBlank()) {
            throw new IllegalArgumentException("Extension type owner and definition are required");
        }
        String normalized = definition.id.toLowerCase(Locale.ROOT);
        FlowDataType type = new FlowDataType(normalized, definition.parent, definition.javaType, definition.dataClass, definition.color, owner, true);
        FlowDataType existing = REGISTRY.putIfAbsent(normalized, type);
        if (existing != null) {
            throw new IllegalArgumentException("Flow type already registered: " + normalized);
        }
        OWNERS.put(normalized, owner);
        return type;
    }

    public static void unregisterExtensionType(String owner, String typeId) {
        if (owner == null || typeId == null) {
            return;
        }
        String normalized = typeId.toLowerCase(Locale.ROOT);
        if (owner.equals(OWNERS.get(normalized))) {
            REGISTRY.remove(normalized);
            OWNERS.remove(normalized);
        }
    }

    public boolean canStringify() {
        return this != EXECUTION && this != ANY;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof FlowDataType type && id.equals(type.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
