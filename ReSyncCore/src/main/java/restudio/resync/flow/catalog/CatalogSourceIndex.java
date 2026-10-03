package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CatalogSourceIndex {
    private static final int MAX_ENTRIES = 1_024;
    private static final long MAX_SOURCE_BYTES = 67_108_864L;
    private static final Map<String, Resident> ENTRIES = new LinkedHashMap<>(128, 0.75f, true);
    private static long sourceBytes;

    private CatalogSourceIndex() {
    }

    public static Entry intern(byte[] bytes) {
        byte[] sourceBytes = Arrays.copyOf(Objects.requireNonNull(bytes, "Catalog source bytes are required"), bytes.length);
        String rawHash = CanonicalJson.genericCanonicalContentHash(sourceBytes);
        synchronized (ENTRIES) {
            Resident resident = ENTRIES.get(rawHash);
            if (resident != null) {
                return resident.entry();
            }
        }
        Entry prepared;
        try {
            Object parsed = CanonicalJson.parse(sourceBytes);
            ContentHash sourceHash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(parsed));
            prepared = new Entry(rawHash, sourceHash, parsed);
        } catch (RuntimeException exception) {
            throw exception instanceof IllegalArgumentException illegal ? illegal
                : new IllegalArgumentException("Catalog source is not canonical JSON", exception);
        }
        synchronized (ENTRIES) {
            Resident resident = ENTRIES.get(rawHash);
            if (resident != null) {
                return resident.entry();
            }
            ENTRIES.put(rawHash, new Resident(prepared, sourceBytes.length));
            CatalogSourceIndex.sourceBytes += sourceBytes.length;
            var entries = ENTRIES.entrySet().iterator();
            while (ENTRIES.size() > MAX_ENTRIES || CatalogSourceIndex.sourceBytes > MAX_SOURCE_BYTES) {
                CatalogSourceIndex.sourceBytes -= entries.next().getValue().bytes();
                entries.remove();
            }
            return prepared;
        }
    }

    public static ContentHash sourceHash(byte[] bytes) {
        return intern(bytes).sourceHash();
    }

    public record Entry(String rawHash, ContentHash sourceHash, Object parsed) {
        public Entry {
            rawHash = Objects.requireNonNull(rawHash, "Catalog source raw hash is required");
            sourceHash = Objects.requireNonNull(sourceHash, "Catalog source hash is required");
            parsed = freeze(parsed);
        }
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(freeze(element));
            }
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    private record Resident(Entry entry, int bytes) {
    }
}
