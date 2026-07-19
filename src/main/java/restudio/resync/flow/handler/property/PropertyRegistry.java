package restudio.resync.flow.handler.property;

import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PropertyRegistry {
    private final Map<String, Map<String, PropertyHandler<?, ?>>> families = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PropertyDescriptor>> descriptors = new ConcurrentHashMap<>();

    public record PropertyDescriptor(String family, String property, FlowTypeRef type, List<String> actions, boolean readable,
                                     boolean writable, boolean observable, boolean invokable, String owner) {
        public PropertyDescriptor {
            actions = actions != null ? List.copyOf(actions) : List.of();
            type = type != null ? type : FlowTypeRef.simple("any");
            owner = owner != null ? owner : "builtin";
        }
    }

    public <T, V> void register(String family, String property, PropertyHandler<T, V> handler) {
        families.computeIfAbsent(family, k -> new ConcurrentHashMap<>())
                .put(property, handler);
        registerDescriptor(descriptor(family, property, FlowTypeRef.simple(handler.getDataType().getId()), handler.getSupportedActions(), "runtime"));
    }

    public void registerDescriptor(PropertyDescriptor descriptor) {
        if (descriptor == null || descriptor.family() == null || descriptor.family().isBlank() || descriptor.property() == null || descriptor.property().isBlank()) {
            throw new IllegalArgumentException("Property family and ID are required");
        }
        descriptors.computeIfAbsent(descriptor.family(), ignored -> new ConcurrentHashMap<>())
            .merge(descriptor.property(), descriptor, this::mergeDescriptors);
    }

    public void loadNodeDefinitions(Collection<NodeDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        descriptors.values().forEach(properties -> properties.entrySet().removeIf(entry -> "builtin".equals(entry.getValue().owner())));
        descriptors.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (NodeDefinition definition : definitions) {
            registerNodeDefinition(definition);
        }
    }

    public void unregister(String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        if (familyMap == null) {
            return;
        }
        familyMap.remove(property);
        if (familyMap.isEmpty()) {
            families.remove(family, familyMap);
        }
        Map<String, PropertyDescriptor> descriptorMap = descriptors.get(family);
        if (descriptorMap != null) {
            descriptorMap.remove(property);
            if (descriptorMap.isEmpty()) {
                descriptors.remove(family, descriptorMap);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T, V> PropertyHandler<T, V> get(String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        return familyMap != null ? (PropertyHandler<T, V>) familyMap.get(property) : null;
    }

    public List<String> getProperties(String family) {
        Set<String> properties = new LinkedHashSet<>();
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        if (familyMap != null) {
            properties.addAll(familyMap.keySet());
        }
        Map<String, PropertyDescriptor> descriptorMap = descriptors.get(family);
        if (descriptorMap != null) {
            properties.addAll(descriptorMap.keySet());
        }
        return properties.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> getFamilies() {
        Set<String> result = new LinkedHashSet<>(families.keySet());
        result.addAll(descriptors.keySet());
        return result.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public boolean hasFamily(String family) {
        return families.containsKey(family) || descriptors.containsKey(family);
    }

    public boolean hasProperty(String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        Map<String, PropertyDescriptor> descriptorMap = descriptors.get(family);
        return familyMap != null && familyMap.containsKey(property) || descriptorMap != null && descriptorMap.containsKey(property);
    }

    public List<String> getActions(String family, String property) {
        PropertyHandler<?, ?> handler = get(family, property);
        PropertyDescriptor descriptor = getDescriptor(family, property);
        return descriptor != null ? descriptor.actions() : handler != null ? handler.getSupportedActions() : List.of();
    }

    public FlowDataType getDataType(String family, String property) {
        PropertyHandler<?, ?> handler = get(family, property);
        PropertyDescriptor descriptor = getDescriptor(family, property);
        return descriptor != null ? FlowDataType.fromString(descriptor.type().getTypeId()) : handler != null ? handler.getDataType() : FlowDataType.ANY;
    }

    public FlowTypeRef getType(String family, String property) {
        PropertyDescriptor descriptor = getDescriptor(family, property);
        return descriptor != null ? descriptor.type() : FlowTypeRef.simple(getDataType(family, property).getId());
    }

    public PropertyDescriptor getDescriptor(String family, String property) {
        Map<String, PropertyDescriptor> familyMap = descriptors.get(family);
        return familyMap != null ? familyMap.get(property) : null;
    }

    public void clear() {
        families.clear();
        descriptors.clear();
    }

    private void registerNodeDefinition(NodeDefinition definition) {
        if (definition == null || definition.getHandler() == null || definition.getHandlerConfig() == null) {
            return;
        }
        String family = definition.getHandler();
        if (!Set.of("player", "entity", "world", "block", "inventory", "itemstack").contains(family)) {
            return;
        }
        Object propertyValue = definition.getHandlerConfig().get("property");
        if (propertyValue == null || propertyValue.toString().isBlank()) {
            return;
        }
        String property = propertyValue.toString();
        List<String> actions = definition.getInputs().stream()
            .filter(pin -> "action".equals(pin.getName()))
            .findFirst()
            .map(NodeDefinition.PinDefinition::getOptions)
            .orElseGet(() -> {
                Object configured = definition.getHandlerConfig().get("action");
                return configured != null ? List.of(configured.toString()) : List.of("get");
            });
        FlowTypeRef type = definition.getOutputs().stream()
            .filter(pin -> "value".equals(pin.getName()) || property.equals(pin.getName()))
            .findFirst()
            .map(NodeDefinition.PinDefinition::getTypeRef)
            .orElseGet(() -> definition.getInputs().stream().filter(pin -> "value".equals(pin.getName())).findFirst()
                .map(NodeDefinition.PinDefinition::getTypeRef).orElse(FlowTypeRef.simple("any")));
        registerDescriptor(descriptor(family, property, type, actions, "builtin"));
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
        return new PropertyDescriptor(first.family(), first.property(), type, new ArrayList<>(actions),
            first.readable() || second.readable(), first.writable() || second.writable(), first.observable() || second.observable(),
            first.invokable() || second.invokable(), first.owner());
    }
}
