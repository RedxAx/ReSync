package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogCanonicalizerSortTest {
    @Test
    void computesEachSortKeyOnceAndPreservesStableCanonicalTies() {
        AtomicInteger keyCalls = new AtomicInteger();
        List<String> values = List.of("first", "second", "third", "fourth");

        List<String> sorted = CatalogCanonicalizer.stableSortedBy(values, value -> {
            keyCalls.incrementAndGet();
            return switch (value) {
                case "first", "third" -> "b";
                case "second", "fourth" -> "a";
                default -> throw new AssertionError(value);
            };
        });

        assertEquals(4, keyCalls.get());
        assertEquals(List.of("second", "fourth", "first", "third"), sorted);
        assertThrows(UnsupportedOperationException.class, () -> sorted.add("extra"));
    }
}
