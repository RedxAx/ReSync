package restudio.resync.flow.handler.property;

import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PropertyRegistry {
    private volatile RegistryState state = RegistryState.empty();
    private volatile ExtensionRegistryActivation activation;

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public record PropertyDescriptor(String family, String property, FlowTypeRef type, List<String> actions, boolean readable,
                                     boolean writable, boolean observable, boolean invokable, String owner) {
        public PropertyDescriptor {
            actions = actions != null ? List.copyOf(actions) : List.of();
            type = type != null ? type : FlowTypeRef.simple("any");
            owner = owner != null ? owner : "builtin";
        }
    }

    public synchronized <T, V> void register(String family, String property, PropertyHandler<T, V> handler) {
        if (family == null || family.isBlank() || property == null || property.isBlank() || handler == null) {
            throw new IllegalArgumentException("Property family, ID, and handler are required");
        }
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.registerLocal(family, property, handler);
                return null;
            });
            return;
        }
        registerLocal(family, property, handler);
    }

    private synchronized <T, V> void registerLocal(String family, String property, PropertyHandler<T, V> handler) {
        MutableState next = new MutableState(state);
        next.families.computeIfAbsent(family, ignored -> new LinkedHashMap<>()).put(property, handler);
        next.mergeDescriptor(descriptor(family, property, FlowTypeRef.simple(handler.getDataType().getId()), handler.getSupportedActions(), "runtime"));
        state = next.freeze();
    }

    public synchronized void registerDescriptor(PropertyDescriptor descriptor) {
        validateDescriptor(descriptor);
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.registerDescriptorLocal(descriptor);
                return null;
            });
            return;
        }
        registerDescriptorLocal(descriptor);
    }

    private synchronized void registerDescriptorLocal(PropertyDescriptor descriptor) {
        MutableState next = new MutableState(state);
        next.mergeDescriptor(descriptor);
        state = next.freeze();
    }

    public synchronized void loadNodeDefinitions(Collection<NodeDefinition> definitions) {
        replaceNodeDefinitions(definitions);
    }

    public synchronized void replaceNodeDefinitions(Collection<NodeDefinition> definitions) {
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.replaceNodeDefinitionsLocal(definitions);
                return null;
            });
            return;
        }
        replaceNodeDefinitionsLocal(definitions);
    }

    private synchronized void replaceNodeDefinitionsLocal(Collection<NodeDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        MutableState next = new MutableState(state);
        next.replaceNodeDescriptors(definitions, this);
        state = next.freeze();
    }

    public synchronized void replaceFrom(PropertyRegistry staged) {
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(PropertyRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged property registry is required");
        }
        state = staged.state;
    }

    public synchronized PropertyRegistry copy() {
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            return currentActivation.snapshot().properties();
        }
        PropertyRegistry copy = new PropertyRegistry();
        copy.state = state;
        return copy;
    }

    public synchronized void unregister(String family, String property) {
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.unregisterLocal(family, property);
                return null;
            });
            return;
        }
        unregisterLocal(family, property);
    }

    private synchronized void unregisterLocal(String family, String property) {
        MutableState next = new MutableState(state);
        Map<String, PropertyHandler<?, ?>> familyMap = next.families.get(family);
        boolean changed = false;
        if (familyMap != null && familyMap.remove(property) != null) {
            changed = true;
            if (familyMap.isEmpty()) {
                next.families.remove(family);
            }
        }
        Map<String, PropertyDescriptor> descriptorMap = next.descriptors.get(family);
        if (descriptorMap != null && descriptorMap.remove(property) != null) {
            changed = true;
            if (descriptorMap.isEmpty()) {
                next.descriptors.remove(family);
            }
        }
        if (changed) {
            state = next.freeze();
        }
    }

    @SuppressWarnings("unchecked")
    public <T, V> PropertyHandler<T, V> get(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.get(family, property);
        }
        RegistryState current = state;
        return getHandler(current, family, property);
    }

    public List<String> getProperties(String family) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getProperties(family);
        }
        RegistryState current = state;
        Set<String> properties = new LinkedHashSet<>();
        Map<String, PropertyHandler<?, ?>> familyMap = current.families.get(family);
        if (familyMap != null) {
            properties.addAll(familyMap.keySet());
        }
        Map<String, PropertyDescriptor> descriptorMap = current.descriptors.get(family);
        if (descriptorMap != null) {
            properties.addAll(descriptorMap.keySet());
        }
        return properties.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> getFamilies() {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getFamilies();
        }
        RegistryState current = state;
        Set<String> result = new LinkedHashSet<>(current.families.keySet());
        result.addAll(current.descriptors.keySet());
        return result.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public boolean hasFamily(String family) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.hasFamily(family);
        }
        RegistryState current = state;
        return current.families.containsKey(family) || current.descriptors.containsKey(family);
    }

    public boolean hasProperty(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.hasProperty(family, property);
        }
        RegistryState current = state;
        Map<String, PropertyHandler<?, ?>> familyMap = current.families.get(family);
        Map<String, PropertyDescriptor> descriptorMap = current.descriptors.get(family);
        return familyMap != null && familyMap.containsKey(property) || descriptorMap != null && descriptorMap.containsKey(property);
    }

    public List<String> getActions(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getActions(family, property);
        }
        RegistryState current = state;
        PropertyHandler<?, ?> handler = getHandler(current, family, property);
        PropertyDescriptor descriptor = getDescriptor(current, family, property);
        return descriptor != null ? descriptor.actions() : handler != null ? immutableActions(handler.getSupportedActions()) : List.of();
    }

    public FlowDataType getDataType(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getDataType(family, property);
        }
        RegistryState current = state;
        PropertyHandler<?, ?> handler = getHandler(current, family, property);
        PropertyDescriptor descriptor = getDescriptor(current, family, property);
        return descriptor != null ? FlowDataType.fromString(descriptor.type().getTypeId()) : handler != null ? handler.getDataType() : FlowDataType.ANY;
    }

    public FlowTypeRef getType(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getType(family, property);
        }
        RegistryState current = state;
        PropertyDescriptor descriptor = getDescriptor(current, family, property);
        return descriptor != null ? descriptor.type() : FlowTypeRef.simple(getDataType(current, family, property).getId());
    }

    public PropertyDescriptor getDescriptor(String family, String property) {
        PropertyRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getDescriptor(family, property);
        }
        RegistryState current = state;
        return getDescriptor(current, family, property);
    }

    public synchronized void clear() {
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            currentActivation.update(PropertyRegistry.class, target -> {
                target.clear();
                return null;
            });
            return;
        }
        state = RegistryState.empty();
    }

    private PropertyRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().properties() : this;
    }

    private void registerNodeDefinition(Map<String, Map<String, PropertyDescriptor>> target, NodeDefinition definition) {
        if (definition == null || definition.getHandler() == null) {
            return;
        }
        String family = definition.getHandler();
        if (!Set.of("player", "entity", "world", "block", "inventory", "itemstack").contains(family)) {
            return;
        }
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        Object propertyValue = handlerConfig != null ? handlerConfig.get("property") : null;
        List<String> actions = definition.getInputs().stream()
            .filter(pin -> matchesPinSemantic(pin, "action"))
            .map(NodeDefinition.PinDefinition::getOptions)
            .filter(options -> options != null && options.stream().anyMatch(value -> value != null && !value.isBlank()))
            .findFirst()
            .orElseGet(() -> {
                Object configured = handlerConfig != null ? handlerConfig.get("action") : null;
                return configured != null ? List.of(configured.toString()) : List.of("get");
            });
        if (propertyValue != null && !propertyValue.toString().isBlank()) {
            registerNodeDescriptor(target, definition, family, propertyValue.toString(), actions, false);
            return;
        }
        definition.getInputs().stream()
            .filter(pin -> matchesPinSemantic(pin, "property"))
            .findFirst()
            .map(NodeDefinition.PinDefinition::getOptions)
            .orElseGet(List::of)
            .stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .forEach(property -> registerNodeDescriptor(target, definition, family, property, actions, true));
    }

    private void registerNodeDescriptor(Map<String, Map<String, PropertyDescriptor>> target, NodeDefinition definition,
                                        String family, String property, List<String> actions, boolean selector) {
        FlowTypeRef type = selector ? selectorType(definition, property) : configuredType(definition, property);
        PropertyDescriptor next = descriptor(family, property, type, actions, "builtin");
        target.computeIfAbsent(family, ignored -> new LinkedHashMap<>())
            .merge(property, next, this::mergeDescriptors);
    }

    private FlowTypeRef configuredType(NodeDefinition definition, String property) {
        return definition.getOutputs().stream()
            .filter(pin -> matchesPinSemantic(pin, "value") || matchesPinSemantic(pin, property))
            .findFirst()
            .map(NodeDefinition.PinDefinition::getTypeRef)
            .orElseGet(() -> definition.getInputs().stream().filter(pin -> matchesPinSemantic(pin, "value")).findFirst()
                .map(NodeDefinition.PinDefinition::getTypeRef).orElse(FlowTypeRef.simple("any")));
    }

    private FlowTypeRef selectorType(NodeDefinition definition, String property) {
        return definition.getOutputs().stream()
            .filter(pin -> matchesPinSemantic(pin, property))
            .findFirst()
            .map(NodeDefinition.PinDefinition::getTypeRef)
            .orElse(FlowTypeRef.simple("any"));
    }

    private static boolean matchesPinSemantic(NodeDefinition.PinDefinition pin, String semantic) {
        return pin != null && (semantic.equals(pin.getName()) || semantic.equals(pin.getRuntimeName()));
    }

    private PropertyDescriptor descriptor(String family, String property, FlowTypeRef type, List<String> actions, String owner) {
        List<String> normalizedActions = actions != null ? actions.stream().filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList() : List.of();
        return new PropertyDescriptor(family, property, type, normalizedActions,
            normalizedActions.contains("get") || normalizedActions.contains("has"), normalizedActions.contains("set"), false,
            normalizedActions.contains("do") || normalizedActions.contains("execute"), owner);
    }

    private PropertyDescriptor mergeDescriptors(PropertyDescriptor first, PropertyDescriptor second) {
        Set<String> actions = new LinkedHashSet<>(first.actions());
        actions.addAll(second.actions());
        FlowTypeRef type = first.type().getTypeId().equals("any") ? second.type() : first.type();
        List<String> mergedActions = actions.stream()
            .sorted(Comparator.comparingInt(PropertyRegistry::actionRank).thenComparing(String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new PropertyDescriptor(first.family(), first.property(), type, mergedActions,
            first.readable() || second.readable(), first.writable() || second.writable(), first.observable() || second.observable(),
            first.invokable() || second.invokable(), first.owner());
    }

    private static int actionRank(String action) {
        return switch (action) {
            case "get" -> 0;
            case "has" -> 1;
            case "set" -> 2;
            case "do" -> 3;
            case "execute" -> 4;
            default -> 5;
        };
    }

    private void validateDescriptor(PropertyDescriptor descriptor) {
        if (descriptor == null || descriptor.family() == null || descriptor.family().isBlank() || descriptor.property() == null || descriptor.property().isBlank()) {
            throw new IllegalArgumentException("Property family and ID are required");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, V> PropertyHandler<T, V> getHandler(RegistryState current, String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = current.families.get(family);
        return familyMap != null ? (PropertyHandler<T, V>) familyMap.get(property) : null;
    }

    private static PropertyDescriptor getDescriptor(RegistryState current, String family, String property) {
        Map<String, PropertyDescriptor> familyMap = current.descriptors.get(family);
        return familyMap != null ? familyMap.get(property) : null;
    }

    private static FlowDataType getDataType(RegistryState current, String family, String property) {
        PropertyHandler<?, ?> handler = getHandler(current, family, property);
        PropertyDescriptor descriptor = getDescriptor(current, family, property);
        return descriptor != null ? FlowDataType.fromString(descriptor.type().getTypeId()) : handler != null ? handler.getDataType() : FlowDataType.ANY;
    }

    private static List<String> immutableActions(List<String> actions) {
        return actions != null ? List.copyOf(actions) : List.of();
    }

    private static final class RegistryState {
        private final Map<String, Map<String, PropertyHandler<?, ?>>> families;
        private final Map<String, Map<String, PropertyDescriptor>> descriptors;

        private RegistryState(Map<String, Map<String, PropertyHandler<?, ?>>> families,
                              Map<String, Map<String, PropertyDescriptor>> descriptors) {
            this.families = immutableNestedMap(families);
            this.descriptors = immutableNestedMap(descriptors);
        }

        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of());
        }

        private static <K, V> Map<K, Map<String, V>> immutableNestedMap(Map<K, Map<String, V>> source) {
            Map<K, Map<String, V>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(values))));
            return Collections.unmodifiableMap(copy);
        }
    }

    private final class MutableState {
        private final Map<String, Map<String, PropertyHandler<?, ?>>> families;
        private final Map<String, Map<String, PropertyDescriptor>> descriptors;

        private MutableState(RegistryState source) {
            families = copyNestedMap(source.families);
            descriptors = copyNestedMap(source.descriptors);
        }

        private void mergeDescriptor(PropertyDescriptor descriptor) {
            descriptors.computeIfAbsent(descriptor.family(), ignored -> new LinkedHashMap<>())
                .merge(descriptor.property(), descriptor, PropertyRegistry.this::mergeDescriptors);
        }

        private void replaceNodeDescriptors(Collection<NodeDefinition> definitions, PropertyRegistry registry) {
            Map<String, Map<String, PropertyDescriptor>> next = new LinkedHashMap<>();
            this.descriptors.forEach((family, properties) -> {
                Map<String, PropertyDescriptor> retained = new LinkedHashMap<>();
                properties.forEach((property, descriptor) -> {
                    if (!"builtin".equals(descriptor.owner())) {
                        retained.put(property, descriptor);
                    }
                });
                if (!retained.isEmpty()) {
                    next.put(family, retained);
                }
            });
            for (NodeDefinition definition : definitions) {
                registry.registerNodeDefinition(next, definition);
            }
            descriptors.clear();
            descriptors.putAll(next);
        }

        private RegistryState freeze() {
            return new RegistryState(families, descriptors);
        }

        private static <K, V> Map<K, Map<String, V>> copyNestedMap(Map<K, Map<String, V>> source) {
            Map<K, Map<String, V>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, new LinkedHashMap<>(values)));
            return copy;
        }
    }
}
