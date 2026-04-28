package restudio.flow.data;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TypeRegistry {
    private final Map<String, FlowDataType> dynamicTypes = new ConcurrentHashMap<>();

    public void register(FlowDataType type) {
        if (type != null) {
            dynamicTypes.put(type.getId(), type);
        }
    }

    public FlowDataType get(String id) {
        FlowDataType builtIn = FlowDataType.fromString(id);
        if (builtIn != FlowDataType.ANY && id != null && !id.isEmpty()) {
            return builtIn;
        }
        return dynamicTypes.get(id.toLowerCase());
    }

    public boolean has(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        FlowDataType builtIn = FlowDataType.fromString(id);
        if (builtIn != FlowDataType.ANY) {
            return true;
        }
        return dynamicTypes.containsKey(id.toLowerCase());
    }

    public Collection<FlowDataType> getAll() {
        return Collections.unmodifiableCollection(dynamicTypes.values());
    }

    public void clear() {
        dynamicTypes.clear();
    }
}
