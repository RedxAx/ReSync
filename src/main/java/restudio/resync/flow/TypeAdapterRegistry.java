package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowBlock;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowEnchantment;
import restudio.flow.data.FlowEntityRef;
import restudio.flow.data.FlowItem;
import restudio.flow.data.FlowWorldRef;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
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

    public <T> void registerStringParser(Class<T> target, Function<String, T> parser) {
        stringParsers.put(target, parser);
    }

    @SuppressWarnings("unchecked")
    public <S, T> T adapt(Object source, Class<T> target) {
        if (source == null) return null;
        if (target.isInstance(source)) return (T) source;

        Class<?> sourceClass = source.getClass();

        Function<Object, Object> adapter = adapters.get(new ClassPair(sourceClass, target));

        if (adapter != null) {
            return (T) adapter.apply(source);
        }

        if (source instanceof Number number) {
            if (target == Integer.class) {
                return (T) Integer.valueOf(number.intValue());
            }
            if (target == Long.class) {
                return (T) Long.valueOf(number.longValue());
            }
            if (target == Double.class) {
                return (T) Double.valueOf(number.doubleValue());
            }
            if (target == Float.class) {
                return (T) Float.valueOf(number.floatValue());
            }
        }

        if (source instanceof String) {
            Function<String, ?> parser = stringParsers.get(target);
            if (parser != null) {
                return (T) parser.apply((String) source);
            }
        }

        try {
            return target.cast(source);
        } catch (ClassCastException e) {
            return null;
        }
    }

    public boolean canConvert(Class<?> source, Class<?> target) {
        if (target.isAssignableFrom(source)) return true;
        if (source.equals(String.class) && stringParsers.containsKey(target)) return true;
        return adapters.containsKey(new ClassPair(source, target));
    }

    public Map<ClassPair, Function<Object, Object>> getAdapters() {
        return Map.copyOf(adapters);
    }

    public Map<Class<?>, Function<String, ?>> getStringParsers() {
        return Map.copyOf(stringParsers);
    }
}
