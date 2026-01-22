package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class TypeAdapterRegistry {
    private final Map<ClassPair, Function<Object, Object>> adapters = new HashMap<>();
    private final Map<Class<?>, Function<String, ?>> stringParsers = new HashMap<>();

    private static class ClassPair {
        private final Class<?> source;
        private final Class<?> target;

        public ClassPair(Class<?> source, Class<?> target) {
            this.source = source;
            this.target = target;
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

        register(String.class, Integer.class, s -> {
            try {
                return Integer.parseInt(s.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        });

        register(String.class, Long.class, s -> {
            try {
                return Long.parseLong(s.toString());
            } catch (NumberFormatException e) {
                return 0L;
            }
        });

        register(String.class, Double.class, s -> {
            try {
                return Double.parseDouble(s.toString());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        });

        register(String.class, Float.class, s -> {
            try {
                return Float.parseFloat(s.toString());
            } catch (NumberFormatException e) {
                return 0.0f;
            }
        });

        register(String.class, Boolean.class, s -> Boolean.parseBoolean(s.toString()));

        register(Number.class, Integer.class, n -> ((Number) n).intValue());
        register(Number.class, Long.class, n -> ((Number) n).longValue());
        register(Number.class, Double.class, n -> ((Number) n).doubleValue());
        register(Number.class, Float.class, n -> ((Number) n).floatValue());

        register(Boolean.class, String.class, b -> b.toString());
        register(Integer.class, String.class, n -> n.toString());
        register(Long.class, String.class, n -> n.toString());
        register(Double.class, String.class, n -> n.toString());
        register(Float.class, String.class, n -> n.toString());
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
}
