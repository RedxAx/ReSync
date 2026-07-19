package restudio.resync.flow.handler.event;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEventDefinitionGetterContractTest {
    @Test
    void everyShippedEventMappingResolvesAgainstItsDeclaredEventClass() throws Exception {
        Path path = Path.of("src", "main", "resources", "nodes", "migrated", "event.json");
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(path)) {
            definitions = new NodeDefinitionLoader().parse(input, path.toString());
        }
        List<String> failures = new ArrayList<>();
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        for (NodeDefinition definition : definitions) {
            if (!definition.isTrigger() || FlowEventRegistry.isSystemManagedEvent(definition.getId()) || definition.getEventType() == null || definition.getEventType().isBlank()) {
                continue;
            }
            Class<?> eventClass = Class.forName(definition.getEventType());
            for (NodeDefinition.PinMapping mapping : definition.getOutputMappings()) {
                if (!mapping.source().startsWith("event.")) {
                    continue;
                }
                Class<?> sourceType = validateChain(definition.getId(), mapping.source(), eventClass, failures);
                NodeDefinition.PinDefinition target = definition.getOutputs().stream()
                    .filter(pin -> mapping.target().equals(pin.getName()))
                    .findFirst()
                    .orElse(null);
                if (target == null) {
                    failures.add(definition.getId() + " maps " + mapping.source() + " to missing output " + mapping.target());
                    continue;
                }
                FlowDataType dataType = target.getDataType();
                Class<?> targetType = dataType != null ? dataType.getJavaType() : null;
                if (sourceType != null && sourceType != Object.class && dataType != null && targetType != null && targetType != Object.class
                    && dataType.getParent() != FlowDataType.RESOURCE_REFERENCE && !adapters.canConvert(sourceType, targetType) && !target.isOptional()) {
                    failures.add(definition.getId() + " maps " + mapping.source() + " (" + sourceType.getName() + ") to " + mapping.target()
                        + " (" + dataType.getId() + ") without a compatible adapter or optional narrowing contract");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    private Class<?> validateChain(String definitionId, String source, Class<?> eventClass, List<String> failures) {
        String[] parts = source.substring("event.".length()).split("\\.");
        Class<?> current = eventClass;
        Type genericType = eventClass;
        for (String part : parts) {
            if (Iterable.class.isAssignableFrom(current)) {
                current = iterableElementType(genericType);
            }
            if (current == Object.class) {
                return Object.class;
            }
            Method method = findMethod(current, part);
            if (method == null) {
                failures.add(definitionId + " maps " + source + " through missing getter " + current.getName() + "." + part);
                return null;
            }
            current = method.getReturnType();
            genericType = method.getGenericReturnType();
        }
        return current;
    }

    private Method findMethod(Class<?> type, String part) {
        String property = camelCase(part);
        for (String name : List.of(property, "get" + capitalize(property), "is" + capitalize(property))) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Class<?> iterableElementType(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length > 0) {
            Type element = parameterized.getActualTypeArguments()[0];
            if (element instanceof Class<?> elementClass) {
                return elementClass;
            }
        }
        return Object.class;
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String camelCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalizeNext = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(character));
                capitalizeNext = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }
}
