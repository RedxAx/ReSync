package restudio.resync.flow.handler.family;

import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
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
            if (isExecutionAction(action)) {
                ctx.triggerOutput("flow");
            }
            return;
        }
        if (!isSupportedProperty(property)) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "has", false);
            if (isExecutionAction(action)) {
                ctx.triggerOutput("flow");
            }
            return;
        }
        Object target = resolveTarget(ctx, node);
        if (target == null) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "has", false);
            if (isExecutionAction(action)) {
                ctx.triggerOutput("flow");
            }
            return;
        }
        switch (action == null ? "get" : action.toLowerCase(Locale.ROOT)) {
            case "set" -> setValue(ctx, node, target, property);
            case "has" -> ctx.setOutput(node, "has", readValue(target, property) != null);
            case "do", "execute" -> ctx.setOutput(node, "success", executeAction(ctx, target, property));
            default -> {
                Object value = readValue(target, property);
                ctx.setOutput(node, "value", value);
                ctx.setOutput(node, property, value);
            }
        }
        if (isExecutionAction(action)) {
            ctx.triggerOutput("flow");
        }
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
        Object explicit = readExplicitValue(target, property);
        if (explicit != MissingValue.INSTANCE) {
            return explicit;
        }
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

    private Object readExplicitValue(Object target, String property) {
        return switch (familyId) {
            case "player" -> readPlayerValue(target, property);
            case "entity" -> readEntityValue(target, property);
            case "world" -> readWorldValue(target, property);
            case "block" -> readBlockValue(target, property);
            case "inventory" -> readInventoryValue(target, property);
            case "itemstack" -> readItemStackValue(target, property);
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readPlayerValue(Object target, String property) {
        if (!(target instanceof Player player)) {
            return MissingValue.INSTANCE;
        }
        return switch (property) {
            case "uuid" -> player.getUniqueId().toString();
            case "gamemode" -> player.getGameMode().name();
            case "world" -> player.getWorld();
            case "inventory" -> player.getInventory();
            case "item_in_hand", "item_in_mainhand" -> player.getInventory().getItemInMainHand();
            case "offhand_item", "item_in_offhand" -> player.getInventory().getItemInOffHand();
            case "xp_level" -> player.getLevel();
            case "exp" -> player.getExp();
            case "total_exp" -> player.getTotalExperience();
            case "exp_to_level" -> player.getExpToLevel();
            case "allow_flight" -> player.getAllowFlight();
            case "on_ground" -> player.isOnGround();
            case "sleeping" -> player.isSleeping();
            case "bed_spawn_location" -> player.getBedSpawnLocation();
            case "last_damage" -> player.getLastDamageCause();
            case "killer" -> player.getKiller();
            case "ping" -> player.getPing();
            case "player_list_name" -> player.getPlayerListName();
            case "op", "is_op" -> player.isOp();
            case "sneak" -> player.isSneaking();
            case "fly", "is_flying" -> player.isFlying();
            case "sprint" -> player.isSprinting();
            case "vanish" -> !player.canSee(player);
            case "ip" -> player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";
            case "online" -> player.isOnline();
            case "whitelisted" -> player.isWhitelisted();
            case "banned" -> player.isBanned();
            case "locale" -> player.locale().toLanguageTag();
            case "armor" -> player.getInventory().getArmorContents();
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readEntityValue(Object target, String property) {
        if (!(target instanceof Entity entity)) {
            return MissingValue.INSTANCE;
        }
        return switch (property) {
            case "type" -> entity.getType().name();
            case "uuid" -> entity.getUniqueId().toString();
            case "world" -> entity.getWorld();
            case "exists" -> entity.isValid() && !entity.isDead();
            case "is_alive" -> entity.isValid() && !entity.isDead();
            case "is_valid" -> entity.isValid();
            case "is_dead" -> entity.isDead() || !entity.isValid();
            case "health" -> entity instanceof LivingEntity living ? living.getHealth() : 0.0;
            case "max_health" -> maxHealth(entity);
            case "absorption" -> entity instanceof LivingEntity living ? living.getAbsorptionAmount() : 0.0;
            case "type_info" -> entity.getType().name();
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readWorldValue(Object target, String property) {
        if (!(target instanceof World world)) {
            return MissingValue.INSTANCE;
        }
        return switch (property) {
            case "weather" -> world.hasStorm() ? world.isThundering() ? "thunder" : "rain" : "clear";
            case "weather_type" -> world.hasStorm() ? world.isThundering() ? "thunder" : "rain" : "clear";
            case "has_storm" -> world.hasStorm();
            case "difficulty" -> world.getDifficulty().name();
            case "environment" -> world.getEnvironment().name();
            case "players" -> world.getPlayers();
            case "entities" -> world.getEntities();
            case "loaded_chunks" -> Arrays.asList(world.getLoadedChunks());
            case "time_relative" -> world.getTime();
            case "pvp" -> world.getPVP();
            case "keep_spawn" -> world.getKeepSpawnInMemory();
            case "thundering", "is_thundering" -> world.isThundering();
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readBlockValue(Object target, String property) {
        if (!(target instanceof Block block)) {
            return MissingValue.INSTANCE;
        }
        return switch (property) {
            case "type" -> block.getType().name();
            case "data" -> block.getBlockData().getAsString();
            case "state" -> block.getState().getBlockData().getAsString();
            case "location" -> block.getLocation();
            case "world" -> block.getWorld();
            case "x" -> block.getX();
            case "y" -> block.getY();
            case "z" -> block.getZ();
            case "is_solid" -> block.isSolid();
            case "is_liquid" -> block.isLiquid();
            case "is_air" -> block.isEmpty();
            case "container_items" -> readContainerItems(block);
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readInventoryValue(Object target, String property) {
        if (!(target instanceof Inventory inventory)) {
            return MissingValue.INSTANCE;
        }
        return switch (property) {
            case "type" -> inventory.getType().name();
            case "items", "contents" -> inventory.getContents();
            case "storage_contents" -> inventory.getStorageContents();
            case "first_empty" -> inventory.firstEmpty();
            case "max_stack_size" -> inventory.getMaxStackSize();
            case "holder" -> inventory.getHolder();
            case "armor" -> inventory instanceof PlayerInventory playerInventory ? playerInventory.getArmorContents() : null;
            default -> MissingValue.INSTANCE;
        };
    }

    private Object readItemStackValue(Object target, String property) {
        if (!(target instanceof ItemStack item)) {
            return MissingValue.INSTANCE;
        }
        ItemMeta meta = item.getItemMeta();
        return switch (property) {
            case "type" -> item.getType().name();
            case "display_name" -> meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
            case "lore" -> meta != null && meta.hasLore() ? meta.getLore() : List.of();
            case "durability" -> meta instanceof Damageable damageable ? damageable.getDamage() : 0;
            case "max_durability" -> item.getType().getMaxDurability();
            case "enchantments" -> item.getEnchantments();
            case "custom_model_data" -> meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
            case "unbreakable" -> meta != null && meta.isUnbreakable();
            case "item_flags" -> meta != null ? meta.getItemFlags() : Set.of();
            default -> MissingValue.INSTANCE;
        };
    }

    private double maxHealth(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0.0;
        }
        return living.getAttribute(Attribute.MAX_HEALTH) != null
            ? living.getAttribute(Attribute.MAX_HEALTH).getValue()
            : 0.0;
    }

    private Object readContainerItems(Block block) {
        BlockState state = block.getState();
        return state instanceof Container container ? container.getInventory().getContents() : new ItemStack[0];
    }

    private void setValue(FlowContext ctx, FlowNode node, Object target, String property) {
        Object value = ctx.getInputValue(node, "value", Object.class, null);
        if (target instanceof LivingEntity living && setLivingAfterDamage(ctx, living, property, value)) {
            ctx.setOutput(node, "success", true);
            return;
        }
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

    private boolean executeAction(FlowContext ctx, Object target, String property) {
        if ("kill".equals(property) && target instanceof LivingEntity living) {
            FlowMutations.setHealth(ctx, living, 0.0);
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

    private boolean setLivingAfterDamage(FlowContext ctx, LivingEntity living, String property, Object value) {
        if ("health".equals(property) && value instanceof Number number) {
            FlowMutations.setHealth(ctx, living, number.doubleValue());
            return true;
        }
        if ("no_damage_ticks".equals(property) && value instanceof Number number) {
            FlowMutations.noDamageTicks(ctx, living, number.intValue());
            return true;
        }
        if ("absorption".equals(property) && value instanceof Number number) {
            FlowMutations.setAbsorption(ctx, living, number.doubleValue());
            return true;
        }
        return false;
    }

    private boolean isExecutionAction(String action) {
        if (action == null) {
            return false;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        return "set".equals(normalized) || "do".equals(normalized) || "execute".equals(normalized);
    }

    private enum MissingValue {
        INSTANCE
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
