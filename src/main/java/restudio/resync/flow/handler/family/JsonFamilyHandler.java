package restudio.resync.flow.handler.family;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public class JsonFamilyHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("get", "set", "has", "do", "execute");
    private static final Map<String, Set<String>> PROPERTIES = Map.of(
        "player", Set.of("location", "health", "max_health", "food_level", "gamemode", "sneak", "fly", "name", "uuid", "world", "inventory", "item_in_hand", "display_name", "xp_level", "walk_speed", "fly_speed", "exp", "total_exp", "exp_to_level", "allow_flight", "on_ground", "sleeping", "bed_spawn_location", "last_damage", "killer", "ping", "player_list_name", "op", "offhand_item", "saturation", "exhaustion", "sprint", "vanish", "glowing", "invulnerable", "fire_ticks", "freeze_ticks", "no_damage_ticks", "remaining_air", "max_air", "xp_progress", "compass_target", "ip", "is_flying", "online", "whitelisted", "banned", "sleep_ticks", "locale", "view_distance", "is_op", "item_in_offhand", "armor"),
        "entity", Set.of("name", "custom_name", "glowing", "silent", "invulnerable", "fire_ticks", "freeze_ticks", "health", "max_health", "persistent", "target", "baby", "tamed", "sitting", "swimming", "pickup_items", "gravity", "visible", "ai", "collidable", "remove", "kill", "exists", "type", "uuid", "location", "velocity", "world", "passengers", "vehicle", "height", "width", "ticks_lived", "no_damage_ticks", "remaining_air", "max_air", "fall_distance", "portal_cooldown", "scoreboard_tags", "absorption"),
        "world", Set.of("time", "full_time", "weather", "has_storm", "is_thundering", "difficulty", "spawn_location", "seed", "name", "environment", "players", "entities", "loaded_chunks", "sea_level", "min_height", "max_height", "time_relative", "pvp", "auto_save", "keep_spawn", "thundering", "weather_type"),
        "block", Set.of("type", "data", "light_level", "biome", "temperature", "humidity", "is_solid", "is_liquid", "is_air", "break_naturally", "location", "world", "x", "y", "z"),
        "inventory", Set.of("size", "type", "items", "first_empty", "max_stack_size", "holder", "clear"),
        "itemstack", Set.of("type", "amount", "display_name", "lore", "durability", "max_durability", "enchantments", "custom_model_data", "unbreakable", "repair_cost", "item_flags", "localized_name")
    );
    private final String familyId;

    private JsonFamilyHandler(String familyId) {
        this.familyId = familyId;
    }

    public static void registerFamilies(HandlerRegistry registry) {
        registry.register("player", new JsonFamilyHandler("player"));
        registry.register("entity", new JsonFamilyHandler("entity"));
        registry.register("world", new JsonFamilyHandler("world"));
        registry.register("block", new JsonFamilyHandler("block"));
        registry.register("inventory", new JsonFamilyHandler("inventory"));
        registry.register("itemstack", new JsonFamilyHandler("itemstack"));
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String property = node.getHandlerConfig() != null ? node.getHandlerConfig().getString("property") : null;
        String configuredAction = node.getHandlerConfig() != null ? node.getHandlerConfig().getString("action", "get") : "get";
        String action = ctx.getInputValue(node, "action", String.class, configuredAction);
        if (property == null || property.isBlank()) {
            property = ctx.getInputValue(node, "property", String.class, "");
        }
        if (property == null || property.isBlank()) {
            ctx.triggerOutput("flow");
            return;
        }
        if (!isSupportedProperty(property)) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "has", false);
            ctx.triggerOutput("flow");
            return;
        }
        Object target = resolveTarget(ctx, node);
        if (target == null) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "has", false);
            ctx.triggerOutput("flow");
            return;
        }
        switch (action == null ? "get" : action.toLowerCase(Locale.ROOT)) {
            case "set" -> setValue(ctx, node, target, property);
            case "has" -> ctx.setOutput(node, "has", readValue(target, property) != null);
            case "do", "execute" -> ctx.setOutput(node, "success", executeAction(target, property));
            default -> {
                Object value = readValue(target, property);
                ctx.setOutput(node, "value", value);
                ctx.setOutput(node, property, value);
            }
        }
        ctx.triggerOutput("flow");
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    private boolean isSupportedProperty(String property) {
        Set<String> properties = PROPERTIES.get(familyId);
        return properties != null && properties.contains(property);
    }

    private Object resolveTarget(FlowContext ctx, FlowNode node) {
        return switch (familyId) {
            case "player" -> ctx.getInputValue(node, "target", Player.class, null);
            case "entity" -> ctx.getInputValue(node, "target", Entity.class, null);
            case "world" -> ctx.getInputValue(node, "target", World.class, null);
            case "block" -> ctx.getInputValue(node, "target", Block.class, null);
            case "inventory" -> ctx.getInputValue(node, "target", Inventory.class, null);
            case "itemstack" -> ctx.getInputValue(node, "target", ItemStack.class, null);
            default -> null;
        };
    }

    private Object readValue(Object target, String property) {
        String suffix = toMethodSuffix(property);
        for (String methodName : new String[] {"get" + suffix, "is" + suffix, property}) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private void setValue(FlowContext ctx, FlowNode node, Object target, String property) {
        Object value = ctx.getInputValue(node, "value", Object.class, null);
        String methodName = "set" + toMethodSuffix(property);
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.invoke(target, coerceValue(value, method.getParameterTypes()[0]));
                ctx.setOutput(node, "success", true);
                return;
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            }
        }
        ctx.setOutput(node, "success", false);
    }

    private boolean executeAction(Object target, String property) {
        if ("kill".equals(property) && target instanceof LivingEntity living) {
            living.setHealth(0);
            return true;
        }
        String methodName = toMethodName(property);
        for (String candidate : new String[] {methodName, "set" + toMethodSuffix(property)}) {
            try {
                Method method = target.getClass().getMethod(candidate);
                method.invoke(target);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private Object coerceValue(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if ((targetType == int.class || targetType == Integer.class) && value instanceof Number number) {
            return number.intValue();
        }
        if ((targetType == long.class || targetType == Long.class) && value instanceof Number number) {
            return number.longValue();
        }
        if ((targetType == double.class || targetType == Double.class) && value instanceof Number number) {
            return number.doubleValue();
        }
        if ((targetType == float.class || targetType == Float.class) && value instanceof Number number) {
            return number.floatValue();
        }
        if ((targetType == boolean.class || targetType == Boolean.class) && value instanceof Boolean bool) {
            return bool;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType.isEnum()) {
            return enumValue(targetType, String.valueOf(value));
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumValue(Class<?> targetType, String value) {
        return Enum.valueOf((Class<? extends Enum>) targetType.asSubclass(Enum.class), value.toUpperCase(Locale.ROOT));
    }

    private String toMethodSuffix(String property) {
        StringBuilder builder = new StringBuilder();
        for (String part : property.split("_")) {
            if (!part.isEmpty()) {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) {
                    builder.append(part.substring(1));
                }
            }
        }
        return builder.toString();
    }

    private String toMethodName(String property) {
        String suffix = toMethodSuffix(property);
        if (suffix.isEmpty()) {
            return property;
        }
        return suffix.substring(0, 1).toLowerCase(Locale.ROOT) + suffix.substring(1);
    }
}
