package restudio.resync.flow.handler.property;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PropertyRegistry {
    private final Map<String, Map<String, PropertyHandler<?, ?>>> families = new ConcurrentHashMap<>();

    public <T, V> void register(String family, String property, PropertyHandler<T, V> handler) {
        families.computeIfAbsent(family, k -> new ConcurrentHashMap<>())
                .put(property, handler);
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
    }

    @SuppressWarnings("unchecked")
    public <T, V> PropertyHandler<T, V> get(String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        return familyMap != null ? (PropertyHandler<T, V>) familyMap.get(property) : null;
    }

    public List<String> getProperties(String family) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        return familyMap != null ? List.copyOf(familyMap.keySet()) : List.of();
    }

    public boolean hasFamily(String family) {
        return families.containsKey(family);
    }

    public boolean hasProperty(String family, String property) {
        Map<String, PropertyHandler<?, ?>> familyMap = families.get(family);
        return familyMap != null && familyMap.containsKey(property);
    }

    public List<String> getActions(String family, String property) {
        PropertyHandler<?, ?> handler = get(family, property);
        return handler != null ? handler.getSupportedActions() : List.of();
    }

    public restudio.flow.data.FlowDataType getDataType(String family, String property) {
        PropertyHandler<?, ?> handler = get(family, property);
        return handler != null ? handler.getDataType() : restudio.flow.data.FlowDataType.ANY;
    }

    public void clear() {
        families.clear();
    }
}
