package restudio.resync.flow.handler.event;

import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEventDefinitionGetterContractTest {
    private static final Map<String, FlowTypeRef> COMMAND_CONTEXT_TYPES = Map.of(
        "event.bound_command", FlowTypeRef.simple("string"),
        "event.command_label", FlowTypeRef.simple("string"),
        "event.args", FlowTypeRef.simple("string"),
        "event.args_list", FlowTypeRef.parse("list<string>"),
        "event.args_count", FlowTypeRef.simple("number"),
        "event.is_console", FlowTypeRef.simple("boolean")
    );

    @Test
    void everyShippedEventMappingResolvesAgainstItsDeclaredEventClass() throws Exception {
        List<NodeDefinition> definitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");
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
                NodeDefinition.PinDefinition target = definition.getOutputs().stream()
                    .filter(pin -> mapping.target().equals(pin.getName()))
                    .findFirst()
                    .orElse(null);
                if (target == null) {
                    failures.add(definition.getId() + " maps " + mapping.source() + " to missing output " + mapping.target());
                    continue;
                }
                FlowTypeRef contextType = commandContextType(definition, mapping, eventClass);
                if (contextType != null) {
                    if (!contextType.equals(target.getTypeRef())) {
                        failures.add(definition.getId() + " maps " + mapping.source() + " with the wrong command context type");
                    }
                    continue;
                }
                Class<?> sourceType = validateChain(definition.getId(), mapping.source(), eventClass, failures);
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

    @Test
    void shippedItemStackEventValuesUseTheSpecificItemStackType() {
        List<NodeDefinition> definitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");
        List<String> failures = new ArrayList<>();
        for (NodeDefinition definition : definitions) {
            if (!definition.isTrigger() || definition.getEventType() == null || definition.getEventType().isBlank()) {
                continue;
            }
            Class<?> eventClass;
            try {
                eventClass = Class.forName(definition.getEventType());
            } catch (ClassNotFoundException exception) {
                continue;
            }
            for (NodeDefinition.PinMapping mapping : definition.getOutputMappings()) {
                NodeDefinition.PinDefinition target = definition.getOutputs().stream()
                    .filter(pin -> mapping.target().equals(pin.getName()))
                    .findFirst().orElse(null);
                if (target == null || !mapping.source().startsWith("event.")) {
                    continue;
                }
                List<String> mappingFailures = new ArrayList<>();
                Class<?> sourceType = validateChain(definition.getId(), mapping.source(), eventClass, mappingFailures);
                if (sourceType != null && sourceType.getName().equals("org.bukkit.inventory.ItemStack")
                    && !target.getTypeRef().equals(FlowTypeRef.simple("itemstack"))) {
                    failures.add(definition.getId() + "." + mapping.target());
                }
            }
        }
        assertTrue(failures.isEmpty(), "ItemStack event outputs declared as another type: " + String.join(", ", failures));
    }

    @Test
    void shippedCommandContextMappingsProduceTheirDeclaredValuesThroughTheRealExtractor() throws Exception {
        NodeDefinition definition = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes").stream()
            .filter(value -> "restudio.resync".equals(value.getOwner()) && "event.command".equals(value.getId()))
            .findFirst().orElseThrow();
        Class<?> eventClass = Class.forName(definition.getEventType());
        assertEquals(PlayerCommandPreprocessEvent.class, eventClass);
        List<NodeDefinition.PinMapping> derived = definition.getOutputMappings().stream()
            .filter(mapping -> COMMAND_CONTEXT_TYPES.containsKey(mapping.source())).toList();
        assertEquals(COMMAND_CONTEXT_TYPES.size(), derived.size());
        assertEquals(COMMAND_CONTEXT_TYPES.keySet(), derived.stream().map(NodeDefinition.PinMapping::source).collect(Collectors.toSet()));
        for (NodeDefinition.PinMapping mapping : derived) {
            FlowTypeRef expected = commandContextType(definition, mapping, eventClass);
            assertNotNull(expected, mapping.toString());
            NodeDefinition.PinDefinition target = definition.getOutputs().stream()
                .filter(pin -> mapping.target().equals(pin.getName())).findFirst().orElseThrow();
            assertEquals(expected, target.getTypeRef());
        }
        MockBukkit.mock();
        try {
            var player = MockBukkit.getMock().addPlayer();
            var extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);
            for (List<String> arguments : List.of(List.<String>of(), List.of("one", "two"))) {
                String command = "/alias" + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
                PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, command);
                event.setCancelled(true);
                Map<String, Object> actual = extractor.apply(event);
                Map<String, Object> expected = Map.of("event.bound_command", "", "event.command_label", "alias",
                    "event.args", String.join(" ", arguments), "event.args_list", arguments,
                    "event.args_count", arguments.size(), "event.is_console", false);
                assertEquals(COMMAND_CONTEXT_TYPES.keySet(), expected.keySet());
                expected.forEach((key, value) -> assertEquals(value, actual.get(key), key));
                assertSame(player, actual.get("event.player"));
                assertEquals(command, actual.get("event.command"));
                assertEquals(true, actual.get("event.is_cancelled"));
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private FlowTypeRef commandContextType(NodeDefinition definition, NodeDefinition.PinMapping mapping, Class<?> eventClass) {
        if (!"restudio.resync".equals(definition.getOwner()) || !"event.command".equals(definition.getId())
            || eventClass != PlayerCommandPreprocessEvent.class || !mapping.source().equals(mapping.target())) {
            return null;
        }
        return COMMAND_CONTEXT_TYPES.get(mapping.source());
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
