package restudio.resync.flow.function;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ImmutableFunctionSourceStore implements FunctionSourceStore {
    private final Map<Key, FunctionSourceRecord> records;

    public ImmutableFunctionSourceStore(Collection<FunctionSourceRecord> records) {
        Objects.requireNonNull(records, "Function Source Records Are Required");
        LinkedHashMap<Key, FunctionSourceRecord> values = new LinkedHashMap<>();
        records.forEach(record -> {
            FunctionSourceRecord value = Objects.requireNonNull(record, "Function Source Record Cannot Be Null");
            Key key = new Key(value.function(), value.revision());
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Function Source Store Contains A Duplicate Locator And Revision");
            }
        });
        this.records = Map.copyOf(values);
    }

    public static ImmutableFunctionSourceStore of(FunctionSourceRecord... records) {
        Objects.requireNonNull(records, "Function Source Records Are Required");
        return new ImmutableFunctionSourceStore(java.util.List.of(records));
    }

    @Override
    public Optional<FunctionSourceRecord> load(FunctionLocator function, FunctionRevision revision) {
        if (function == null || revision == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(new Key(function, revision)));
    }

    public int size() {
        return records.size();
    }

    private record Key(FunctionLocator function, FunctionRevision revision) {
    }
}
