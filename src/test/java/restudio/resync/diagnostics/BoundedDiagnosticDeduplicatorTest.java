package restudio.resync.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedDiagnosticDeduplicatorTest {
    @Test
    void duplicateKeyIsRejectedWhileRetained() {
        BoundedDiagnosticDeduplicator deduplicator = new BoundedDiagnosticDeduplicator(2);

        assertTrue(deduplicator.add("failure"));
        assertFalse(deduplicator.add("failure"));
        assertEquals(1, deduplicator.size());
    }

    @Test
    void oldestKeyCanBeReportedAgainAfterBoundedEviction() {
        BoundedDiagnosticDeduplicator deduplicator = new BoundedDiagnosticDeduplicator(2);
        deduplicator.add("first");
        deduplicator.add("second");
        deduplicator.add("third");

        assertEquals(2, deduplicator.size());
        assertTrue(deduplicator.add("first"));
        assertEquals(2, deduplicator.size());
    }

    @Test
    void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedDiagnosticDeduplicator(0));
    }
}
