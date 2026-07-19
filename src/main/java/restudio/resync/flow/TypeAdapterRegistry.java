package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowBlock;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowEnchantment;
import restudio.flow.data.FlowEntityRef;
import restudio.flow.data.FlowItem;
import restudio.flow.data.FlowPermission;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowWorldRef;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class TypeAdapterRegistry {
    private final Map<ClassPair, Function<Object, Object>> adapters = new HashMap<>();
    private final Map<Class<?>, Function<String, ?>> stringParsers = new HashMap<>();

    public static final class ClassPair {
        private final Class<?> source;
        private final Class<?> target;

        public ClassPair(Class<?> source, Class<?> target) {
            this.source = source;
            this.target = target;
        }

        public Class<?> getSource() {
            return source;
        }

        public Class<?> getTarget() {
            return target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClassPair classPair = (ClassPair) o;
            return source.equals(classPair.source) && target.equals(classPair.target);
        }

        @Override
        public int hashCode() {
            return 31 * source.hashCode() + target.hashCode();
        }
    }

    public TypeAdapterRegistry() {
        registerDefaultAdapters();
    }

    private void registerDefaultAdapters() {
        register(String.class, Component.class, TextFormatter::parse);
        register(Component.class, String.class, TextFormatter::formatLegacy);
        register(String.class, Color.class, this::parseRgbColor);
        register(Color.class, String.class, color -> String.format("#%06X", color.asRGB()));
        register(String.class, NamedTextColor.class, value -> NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT)));
        register(NamedTextColor.class, String.class, color -> NamedTextColor.NAMES.key(color));
        register(String.class, FlowPermission.class, FlowPermission::new);
        register(FlowPermission.class, String.class, FlowPermission::node);
        register(String.class, Material.class, Material::matchMaterial);
        register(String.class, Biome.class, value -> Registry.BIOME.get(namespacedKey(value)));
        register(String.class, Difficulty.class, value -> Difficulty.valueOf(enumName(value)));
        register(String.class, Enchantment.class, value -> Registry.ENCHANTMENT.get(namespacedKey(value)));
        register(String.class, EntityType.class, value -> Registry.ENTITY_TYPE.get(namespacedKey(value)));
        register(String.class, GameMode.class, value -> GameMode.valueOf(enumName(value)));
        register(String.class, PotionEffectType.class, value -> Registry.MOB_EFFECT.get(namespacedKey(value)));
        register(String.class, Sound.class, value -> Registry.SOUNDS.get(namespacedKey(value)));
        register(String.class, FlowResourceReference.class, value -> new FlowResourceReference("unresolved", value, "legacy", false,
            Map.of("migrationRequired", true)));
        register(FlowResourceReference.class, String.class, FlowResourceReference::id);

        register(FlowItem.class, ItemStack.class, FlowItem::toItemStack);
        register(ItemStack.class, FlowItem.class, FlowItem::fromItemStack);

        register(FlowEntityRef.class, Entity.class, FlowEntityRef::resolveEntity);
        register(Entity.class, FlowEntityRef.class, FlowEntityRef::fromEntity);

        register(FlowWorldRef.class, World.class, FlowWorldRef::resolveWorld);
        register(World.class, FlowWorldRef.class, FlowWorldRef::fromWorld);

        register(FlowEnchantment.class, Enchantment.class, FlowEnchantment::resolveEnchantment);

        register(FlowBlock.class, Block.class, flowBlock -> {
            World world = worldFromName(flowBlock.getWorld());
            if (world == null) {
                return null;
            }
            return world.getBlockAt(flowBlock.getX(), flowBlock.getY(), flowBlock.getZ());
        });
        register(Block.class, FlowBlock.class, block -> {
            if (block == null) {
                return null;
            } else {
                block.getWorld();
            }
            return new FlowBlock(
                    block.getType().name(),
                    block.getWorld().getName(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        });

        register(String.class, Integer.class, Integer::parseInt);
        register(String.class, Long.class, Long::parseLong);
        register(String.class, Number.class, Double::parseDouble);
        register(String.class, Double.class, Double::parseDouble);
        register(String.class, Float.class, Float::parseFloat);
        register(String.class, Boolean.class, this::parseBooleanStrict);

        register(Location.class, Vector.class, Location::toVector);
        register(Vector.class, Location.class, vector -> new Location(null, vector.getX(), vector.getY(), vector.getZ()));

        register(Number.class, Integer.class, Number::intValue);
        register(Number.class, Long.class, Number::longValue);
        register(Number.class, Double.class, Number::doubleValue);
        register(Number.class, Float.class, Number::floatValue);

        register(Boolean.class, String.class, Object::toString);
        register(Integer.class, String.class, Object::toString);
        register(Long.class, String.class, Object::toString);
        register(Double.class, String.class, Object::toString);
        register(Float.class, String.class, Object::toString);
        register(Enum.class, String.class, Enum::name);
        register(Map.class, String.class, map -> {
            Object id = map.get("id");
            return id != null ? String.valueOf(id) : map.toString();
        });

        register(UUID.class, String.class, UUID::toString);
        register(World.class, String.class, World::getName);
        register(FlowWorldRef.class, String.class, FlowWorldRef::getName);
        register(Entity.class, String.class, e -> e.getUniqueId().toString());
        register(FlowEntityRef.class, String.class, FlowEntityRef::getUuid);
        register(Location.class, String.class, l -> l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
        register(Vector.class, String.class, v -> v.getX() + "," + v.getY() + "," + v.getZ());
        register(Block.class, String.class, b -> b.getType().name() + "@" + b.getX() + "," + b.getY() + "," + b.getZ());
        register(FlowBlock.class, String.class, fb -> fb.getType() + "@" + fb.getX() + "," + fb.getY() + "," + fb.getZ());
        register(ItemStack.class, String.class, i -> i.getType().name());
        register(Note.class, String.class, Note::toString);
        register(FlowItem.class, String.class, FlowDataObject::getType);
        register(Enchantment.class, String.class, e -> e.getKey().getKey());
        register(FlowEnchantment.class, String.class, FlowEnchantment::getTypeId);
        register(Sound.class, String.class, sound -> sound.getKey().asString());
        register(Advancement.class, String.class, advancement -> advancement.getKey().asString());
    }

    private Boolean parseBooleanStrict(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value: " + value);
    }

    private NamespacedKey namespacedKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Registry key is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (key == null) {
            throw new IllegalArgumentException("Invalid registry key: " + value);
        }
        return key;
    }

    private String enumName(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private Color parseRgbColor(String value) {
        String hex = value != null ? value.strip().replace("#", "") : "";
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            throw new IllegalArgumentException("Invalid RGB color: " + value);
        }
        return Color.fromRGB(Integer.parseInt(hex, 16));
    }

    private World worldFromName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        return Bukkit.getWorld(worldName);
    }

    public <S, T> void register(Class<S> source, Class<T> target, Function<S, T> adapter) {
        Function<Object, Object> wrapper = obj -> adapter.apply((S) obj);
        adapters.put(new ClassPair(source, target), wrapper);
    }

    public void unregister(Class<?> source, Class<?> target) {
        if (source != null && target != null) {
            adapters.remove(new ClassPair(source, target));
        }
    }

    public <T> void registerStringParser(Class<T> target, Function<String, T> parser) {
        stringParsers.put(target, parser);
    }

    @SuppressWarnings("unchecked")
    public <S, T> T adapt(Object source, Class<T> target) {
        if (source == null) return null;
        Class<?> boxedTarget = boxed(target);
        if (boxedTarget.isInstance(source)) return (T) source;

        Class<?> sourceClass = source.getClass();

        Function<Object, Object> adapter = findAdapter(sourceClass, boxedTarget);

        if (adapter != null) {
            return (T) adapter.apply(source);
        }

        if (source instanceof Number number) {
            if (boxedTarget == Integer.class) {
                return (T) Integer.valueOf(number.intValue());
            }
            if (boxedTarget == Long.class) {
                return (T) Long.valueOf(number.longValue());
            }
            if (boxedTarget == Double.class) {
                return (T) Double.valueOf(number.doubleValue());
            }
            if (boxedTarget == Float.class) {
                return (T) Float.valueOf(number.floatValue());
            }
        }

        if (source instanceof String) {
            Function<String, ?> parser = stringParsers.get(boxedTarget);
            if (parser != null) {
                return (T) parser.apply((String) source);
            }
        }

        try {
            return (T) boxedTarget.cast(source);
        } catch (ClassCastException e) {
            return null;
        }
    }

    public boolean canConvert(Class<?> source, Class<?> target) {
        Class<?> boxedSource = boxed(source);
        Class<?> boxedTarget = boxed(target);
        if (boxedTarget.isAssignableFrom(boxedSource)) return true;
        if (boxedSource.equals(String.class) && stringParsers.containsKey(boxedTarget)) return true;
        return findAdapter(boxedSource, boxedTarget) != null;
    }

    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private Function<Object, Object> findAdapter(Class<?> source, Class<?> target) {
        Function<Object, Object> exact = adapters.get(new ClassPair(source, target));
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<ClassPair, Function<Object, Object>> entry : adapters.entrySet()) {
            ClassPair pair = entry.getKey();
            if (pair.getSource().isAssignableFrom(source) && target.isAssignableFrom(pair.getTarget())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<ClassPair, Function<Object, Object>> getAdapters() {
        return Map.copyOf(adapters);
    }

    public Map<Class<?>, Function<String, ?>> getStringParsers() {
        return Map.copyOf(stringParsers);
    }
}
