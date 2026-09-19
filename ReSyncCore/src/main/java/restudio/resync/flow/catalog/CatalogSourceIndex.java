package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class CatalogSourceIndex {
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private CatalogSourceIndex() {
    }

    public static Entry intern(byte[] bytes) {
        byte[] sourceBytes = Arrays.copyOf(Objects.requireNonNull(bytes, "Catalog source bytes are required"), bytes.length);
        String rawHash = CanonicalJson.genericCanonicalContentHash(sourceBytes);
        return ENTRIES.computeIfAbsent(rawHash, ignored -> {
            try {
                Object parsed = CanonicalJson.parse(sourceBytes);
                ContentHash sourceHash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(parsed));
                return new Entry(rawHash, sourceHash, parsed);
            } catch (RuntimeException exception) {
                throw exception instanceof IllegalArgumentException illegal ? illegal
                    : new IllegalArgumentException("Catalog source is not canonical JSON", exception);
            }
        });
    }

    public static ContentHash sourceHash(byte[] bytes) {
        return intern(bytes).sourceHash();
    }

    public record Entry(String rawHash, ContentHash sourceHash, Object parsed) {
        public Entry {
            rawHash = Objects.requireNonNull(rawHash, "Catalog source raw hash is required");
            sourceHash = Objects.requireNonNull(sourceHash, "Catalog source hash is required");
        }
    }
}
