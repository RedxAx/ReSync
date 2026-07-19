package restudio.resync.api;

import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.Set;

public interface RuntimeDataAdapter<T> {
    String id();

    String domain();

    FlowTypeRef valueType();

    Class<T> valueClass();

    default Set<RuntimeDataCapability> capabilities() {
        return Set.of(RuntimeDataCapability.ENUMERATE, RuntimeDataCapability.RESOLVE);
    }

    default boolean available() {
        return true;
    }

    default String revision() {
        return id();
    }

    List<RuntimeDataRecord> records(RuntimeDataQuery query);

    T resolve(RuntimeDataRecord record, int amount);

    default RuntimeDataRecord describe(T value) {
        return null;
    }
}
