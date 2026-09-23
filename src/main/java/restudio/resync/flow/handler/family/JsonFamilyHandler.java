package restudio.resync.flow.handler.family;

import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.ItemWriteback;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.ItemStackPropertySelector;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

public class JsonFamilyHandler implements NodeHandler {
    private static final Map<Material, String> BLOCK_DISPLAY_NAMES = new ConcurrentHashMap<>();
    private static final Set<String> OPERATIONS = Set.of("get", "set", "has", "do", "execute");
    private final String familyId;
    private final PropertyRegistry propertyRegistry;

    private JsonFamilyHandler(String familyId, PropertyRegistry propertyRegistry) {
        this.familyId = familyId;
        this.propertyRegistry = propertyRegistry;
    }

    public static void registerFamilies(HandlerRegistry registry, PropertyRegistry propertyRegistry) {
        registry.register("player", new JsonFamilyHandler("player", propertyRegistry));
        registry.register("entity", new JsonFamilyHandler("entity", propertyRegistry));
        registry.register("world", new JsonFamilyHandler("world", propertyRegistry));
        registry.register("block", new JsonFamilyHandler("block", propertyRegistry));
        registry.register("inventory", new JsonFamilyHandler("inventory", propertyRegistry));
        registry.register("itemstack", new JsonFamilyHandler("itemstack", propertyRegistry));
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String property = null;
        if (ctx.getRuntime().hasExplicitInput(node, "property")) {
            property = ctx.getInputValue(node, "property", String.class, null);
        }
        if (property == null || property.isBlank()) {
            property = ItemStackPropertySelector.text(node.getHandlerConfig() != null
                ? node.getHandlerConfig().getString("property") : null);
        }
        String configuredAction = node.getHandlerConfig() != null ? node.getHandlerConfig().getString("action", "get") : "get";
        String action = ctx.getInputValue(node, "action", String.class, configuredAction);
        if (property == null || property.isBlank()) {
            property = ctx.getInputValue(node, "property", String.class, "");
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("Property is required for " + familyId + " operations");
        }
        if (!isSupportedProperty(property)) {
            throw new IllegalArgumentException("Unknown " + familyId + " property: " + property);
        }
        String normalizedAction = action != null ? action.toLowerCase(Locale.ROOT) : "get";
        if (!propertyRegistry.getActions(familyId, property).contains(normalizedAction)) {
            throw new IllegalArgumentException("Property " + familyId + "." + property + " does not support action " + normalizedAction);
        }
        Object target = resolveTarget(ctx, node);
        if (target instanceof BlockState state && Set.of("set", "do", "execute").contains(normalizedAction)) {
            target = state.getBlock();
        }
        if (target == null) {
            throw new IllegalArgumentException("Target is required for " + familyId + "." + property);
        }
        switch (normalizedAction) {
            case "set" -> setValue(ctx, node, target, property);
            case "has" -> ctx.setOutput(node, "has", readValue(target, property) != null);
            case "do", "execute" -> ctx.setOutput(node, "success", executeAction(ctx, target, property));
            case "get" -> {
                Object value = readValue(target, property);
                Object genericValue = outputValue(ctx, node, "value", value);
                Object propertyValue = outputValue(ctx, node, property, value);
                ctx.setOutput(node, "value", genericValue);
                ctx.setOutput(node, property, propertyValue);
            }
            default -> throw new IllegalArgumentException("Unknown property action: " + normalizedAction);
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
        return propertyRegistry != null && propertyRegistry.hasProperty(familyId, property);
    }

    private Object outputValue(FlowContext ctx, FlowNode node, String output, Object value) {
        NodeDefinition.PinDefinition pin = ctx.getRuntime().resolveOutputPin(node, output);
        if (pin == null) {
            return value;
        }
        if (value instanceof UUID uuid && FlowTypeRef.simple("string").equals(pin.getTypeRef())) {
            return uuid.toString();
        }
        if (value instanceof Vector && FlowTypeRef.simple("location").equals(pin.getTypeRef())) {
            throw new IllegalStateException("Property " + familyId + "." + output + " produces a vector, not a location; its descriptor requires migration");
        }
        return value;
    }

    private Object resolveTarget(FlowContext ctx, FlowNode node) {
        return switch (familyId) {
            case "player" -> {
                Object raw = ctx.getRuntime().resolveInput(node, "target");
                if (raw == null && !ctx.getRuntime().hasInputConnection(node, "target")) {
                    yield ctx.getPlayer();
                }
                yield ctx.getRuntime().resolveInput(node, "target", Player.class);
            }
            case "entity" -> ctx.getInputValue(node, "target", Entity.class, null);
            case "world" -> ctx.getInputValue(node, "target", World.class, null);
            case "block" -> {
                Object raw = ctx.getRuntime().resolveInput(node, "target");
                yield raw instanceof BlockState ? raw : ctx.getInputValue(node, "target", Block.class, null);
            }
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
        for (String methodName : new String[] {"get" + suffix, "is" + suffix}) {
            try {
                Method method = target.getClass().getMethod(methodName);
                if (method.getReturnType() == Void.TYPE) {
                    continue;
                }
                return method.invoke(target);
            } catch (NoSuchMethodException exception) {
                continue;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read property " + familyId + "." + property, exception);
            }
        }
        throw new IllegalStateException("No runtime reader exists for advertised property " + familyId + "." + property);
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
            case "uuid" -> player.getUniqueId();
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
            case "last_damage" -> player.getLastDamage();
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
            case "uuid" -> entity.getUniqueId();
            case "world" -> entity.getWorld();
            case "exists" -> entity.isValid() && !entity.isDead();
            case "is_alive" -> entity.isValid() && !entity.isDead();
            case "is_valid" -> entity.isValid();
            case "is_dead" -> entity.isDead() || !entity.isValid();
            case "health" -> requireLiving(entity, property).getHealth();
            case "max_health" -> maxHealth(entity);
            case "absorption" -> requireLiving(entity, property).getAbsorptionAmount();
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
        BlockState snapshot = target instanceof BlockState state ? state : null;
        Block block = snapshot != null ? snapshot.getBlock() : target instanceof Block value ? value : null;
        if (block == null) {
            return MissingValue.INSTANCE;
        }
        Material material = snapshot != null ? snapshot.getType() : block.getType();
        return switch (property) {
            case "type" -> material.name();
            case "display_name" -> blockDisplayName(material);
            case "translation_key" -> material.translationKey();
            case "key" -> material.getKey().toString();
            case "data", "state" -> (snapshot != null ? snapshot.getBlockData() : block.getBlockData()).getAsString();
            case "location" -> block.getLocation();
            case "world" -> block.getWorld();
            case "x" -> block.getX();
            case "y" -> block.getY();
            case "z" -> block.getZ();
            case "is_solid" -> material.isSolid();
            case "is_liquid" -> material == Material.WATER || material == Material.LAVA;
            case "is_air" -> material.isAir();
            case "is_occluding" -> material.isOccluding();
            case "is_flammable" -> material.isFlammable();
            case "is_burnable" -> material.isBurnable();
            case "has_gravity" -> material.hasGravity();
            case "hardness" -> material.getHardness();
            case "blast_resistance" -> material.getBlastResistance();
            case "slipperiness" -> material.getSlipperiness();
            case "light_level" -> block.getLightLevel();
            case "light_from_sky" -> block.getLightFromSky();
            case "light_from_blocks" -> block.getLightFromBlocks();
            case "biome" -> block.getBiome();
            case "temperature" -> block.getTemperature();
            case "humidity" -> block.getHumidity();
            case "power" -> block.getBlockPower();
            case "is_powered" -> block.isBlockPowered();
            case "is_indirectly_powered" -> block.isBlockIndirectlyPowered();
            case "is_passable" -> block.isPassable();
            case "container_items" -> readContainerItems(block);
            default -> MissingValue.INSTANCE;
        };
    }

    private static String blockDisplayName(Material material) {
        return BLOCK_DISPLAY_NAMES.computeIfAbsent(material, JsonFamilyHandler::formatBlockDisplayName);
    }

    private static String formatBlockDisplayName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        for (int index = 0; index < words.length; index++) {
            words[index] = Character.toUpperCase(words[index].charAt(0)) + words[index].substring(1);
        }
        return String.join(" ", words);
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
            case "max_durability" -> meta instanceof Damageable damageable && damageable.hasMaxDamage()
                ? damageable.getMaxDamage() : item.getType().getMaxDurability();
            case "enchantments" -> enchantments(item);
            case "custom_model_data" -> meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
            case "unbreakable" -> meta != null && meta.isUnbreakable();
            case "item_flags" -> meta == null ? List.of() : meta.getItemFlags().stream()
                .map(Enum::name).sorted().toList();
            case "repair_cost" -> meta instanceof Repairable repairable && repairable.hasRepairCost()
                ? repairable.getRepairCost() : 0;
            case "localized_name" -> meta != null && meta.hasLocalizedName() ? meta.getLocalizedName() : "";
            default -> MissingValue.INSTANCE;
        };
    }

    private double maxHealth(Entity entity) {
        AttributeInstance attribute = requireLiving(entity, "max_health").getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            throw new IllegalStateException("Property entity.max_health requires a maximum health attribute");
        }
        return attribute.getValue();
    }

    private LivingEntity requireLiving(Entity entity, String property) {
        if (entity instanceof LivingEntity living) {
            return living;
        }
        throw new IllegalArgumentException("Property entity." + property + " requires a living entity");
    }

    private Map<String, Integer> enchantments(ItemStack item) {
        Map<String, Integer> values = new LinkedHashMap<>();
        item.getEnchantments().forEach((enchantment, level) -> values.put(enchantment.getKey().toString(), level));
        return values;
    }

    private Object readContainerItems(Block block) {
        BlockState state = block.getState();
        return state instanceof Container container ? container.getInventory().getContents() : new ItemStack[0];
    }

    private void setValue(FlowContext ctx, FlowNode node, Object target, String property) {
        Object value = ctx.getInputValue(node, "itemstack".equals(familyId) ? "set_" + property : "value",
            Object.class, null);
        if (target instanceof ItemStack item && "itemstack".equals(familyId)) {
            Consumer<ItemStack> writeback = ItemWriteback.resolve(ctx, node, item);
            setItemStackValue(item, property, value);
            writeback.accept(item);
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "success", true);
            return;
        }
        if (target instanceof LivingEntity living && setLivingAfterDamage(ctx, living, property, value)) {
            ctx.setOutput(node, "success", true);
            return;
        }
        String methodName = "set" + toMethodSuffix(property);
        RuntimeException failure = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.invoke(target, coerceValue(value, method.getParameterTypes()[0]));
                ctx.setOutput(node, "success", true);
                return;
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                failure = new IllegalArgumentException("Failed to write property " + familyId + "." + property, exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        throw new IllegalStateException("No runtime writer exists for advertised property " + familyId + "." + property);
    }

    private void setItemStackValue(ItemStack item, String property, Object value) {
        switch (property) {
            case "type" -> {
                String materialName = requiredString(value, property);
                Material material = Material.matchMaterial(materialName);
                if (material == null || !material.isItem()) {
                    throw new IllegalArgumentException("Unknown item material: " + materialName);
                }
                item.setType(material);
            }
            case "amount" -> item.setAmount(integer(value, property, 1, item.getMaxStackSize()));
            case "display_name" -> updateItemMeta(item, meta -> meta.setDisplayName(requiredString(value, property)));
            case "lore" -> updateItemMeta(item, meta -> meta.setLore(strings(value, property)));
            case "durability" -> updateItemMeta(item, meta -> {
                if (!(meta instanceof Damageable damageable)) {
                    throw new IllegalArgumentException("Item does not support durability: " + item.getType());
                }
                int maximum = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
                damageable.setDamage(integer(value, property, 0, Math.max(0, maximum)));
            });
            case "max_durability" -> updateItemMeta(item, meta -> {
                if (!(meta instanceof Damageable damageable)) {
                    throw new IllegalArgumentException("Item does not support maximum durability: " + item.getType());
                }
                int maximum = integer(value, property, 1, Integer.MAX_VALUE);
                damageable.setMaxDamage(maximum);
                if (damageable.getDamage() > maximum) {
                    damageable.setDamage(maximum);
                }
            });
            case "enchantments" -> setEnchantments(item, value);
            case "custom_model_data" -> updateItemMeta(item,
                meta -> meta.setCustomModelData(integer(value, property, 0, Integer.MAX_VALUE)));
            case "unbreakable" -> updateItemMeta(item, meta -> meta.setUnbreakable(requiredBoolean(value, property)));
            case "repair_cost" -> updateItemMeta(item, meta -> {
                if (!(meta instanceof Repairable repairable)) {
                    throw new IllegalArgumentException("Item does not support a repair cost: " + item.getType());
                }
                repairable.setRepairCost(integer(value, property, 0, Integer.MAX_VALUE));
            });
            case "item_flags" -> updateItemMeta(item, meta -> {
                meta.removeItemFlags(meta.getItemFlags().toArray(ItemFlag[]::new));
                List<ItemFlag> flags = strings(value, property).stream().map(flag -> {
                    try {
                        return ItemFlag.valueOf(flag.strip().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Unknown item flag: " + flag, exception);
                    }
                }).toList();
                meta.addItemFlags(flags.toArray(ItemFlag[]::new));
            });
            case "localized_name" -> updateItemMeta(item,
                meta -> meta.setLocalizedName(requiredString(value, property)));
            default -> throw new IllegalStateException("No runtime writer exists for advertised property itemstack." + property);
        }
    }

    private void setEnchantments(ItemStack item, Object value) {
        if (!(value instanceof Map<?, ?> enchantments)) {
            throw new IllegalArgumentException("Property itemstack.enchantments requires an enchantment map");
        }
        Map<Enchantment, Integer> resolved = new LinkedHashMap<>();
        enchantments.forEach((key, level) -> {
            String id = requiredString(key, "enchantments").strip().toLowerCase(Locale.ROOT);
            NamespacedKey namespacedKey = id.contains(":") ? NamespacedKey.fromString(id) : NamespacedKey.minecraft(id);
            Enchantment enchantment = namespacedKey != null ? Enchantment.getByKey(namespacedKey) : null;
            if (enchantment == null) {
                throw new IllegalArgumentException("Unknown enchantment: " + id);
            }
            resolved.put(enchantment, integer(level, "enchantments." + id, 1, Integer.MAX_VALUE));
        });
        new ArrayList<>(item.getEnchantments().keySet()).forEach(item::removeEnchantment);
        resolved.forEach(item::addUnsafeEnchantment);
    }

    private void updateItemMeta(ItemStack item, Consumer<ItemMeta> mutation) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("Item does not support metadata: " + item.getType());
        }
        mutation.accept(meta);
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("Item metadata is incompatible with " + item.getType());
        }
    }

    private String requiredString(Object value, String property) {
        if (value == null) {
            throw new IllegalArgumentException("Property itemstack." + property + " requires a value");
        }
        return String.valueOf(value);
    }

    private boolean requiredBoolean(Object value, String property) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Property itemstack." + property + " requires true or false");
    }

    private int integer(Object value, String property, int minimum, int maximum) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Property itemstack." + property + " requires a number");
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal) || decimal < minimum || decimal > maximum) {
            throw new IllegalArgumentException("Property itemstack." + property + " must be a whole number from "
                + minimum + " to " + maximum);
        }
        return (int) decimal;
    }

    private List<String> strings(Object value, String property) {
        if (!(value instanceof Iterable<?> values)) {
            throw new IllegalArgumentException("Property itemstack." + property + " requires a text list");
        }
        List<String> result = new ArrayList<>();
        values.forEach(entry -> result.add(requiredString(entry, property)));
        return result;
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
            } catch (NoSuchMethodException exception) {
                continue;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to execute property action " + familyId + "." + property, exception);
            }
        }
        throw new IllegalStateException("No runtime action exists for advertised property " + familyId + "." + property);
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
        if ("max_air".equals(property)) {
            return "MaximumAir";
        }
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
