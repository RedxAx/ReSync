package restudio.resync.runtime.data;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.runtime.data.RuntimeDataCategoryCatalog.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeDataCategoryCatalogTest {
    @Test
    void onlyTheExactResidentSnapshotReusesItsDerivedCapture() {
        Snapshot<Long> firstSnapshot = snapshot("first");
        AtomicReference<Snapshot<Long>> source = new AtomicReference<>(firstSnapshot);
        RuntimeDataCategoryCatalog<Long> categories = new RuntimeDataCategoryCatalog<>(adapter()) {
            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            protected Snapshot<Long> captureSnapshot() {
                return source.get();
            }
        };
        OptionCatalogQuery query = new OptionCatalogQuery(categories.sourceId(), Map.of());
        OptionCatalogCapture first = categories.capture(query);
        assertSame(first, categories.capture(query));

        source.set(snapshot("second"));
        OptionCatalogCapture second = categories.capture(query);
        assertNotSame(first, second);
        assertEquals(List.of("second"), second.values());
        assertSame(second, categories.capture(query));

        source.set(firstSnapshot);
        OptionCatalogCapture revisited = categories.capture(query);
        assertNotSame(first, revisited);
        assertEquals(first, revisited);
    }

    @Test
    void cachedDerivedCaptureNeverBypassesIoValidityOrPreparation() {
        Snapshot<Long> source = snapshot("first");
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        RuntimeDataCategoryCatalog<Long> categories = new RuntimeDataCategoryCatalog<>(adapter()) {
            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            protected Snapshot<Long> captureSnapshot() {
                return source;
            }

            @Override
            protected boolean current(Long token) {
                checks.incrementAndGet();
                return valid.get();
            }
        };
        OptionCatalogQuery query = new OptionCatalogQuery(categories.sourceId(), Map.of());
        OptionCatalogCapture first = categories.capture(query);
        assertSame(first, categories.capture(query));
        assertEquals(2, checks.get());

        valid.set(false);
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class, () -> categories.capture(query));
        assertEquals(3, checks.get());
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class, () -> categories.preparedCapture(query));
        valid.set(true);
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class, () -> categories.preparedCapture(query));
        assertSame(first, categories.capture(query));
        assertSame(first, categories.preparedCapture(query));
        assertEquals(5, checks.get());
    }

    private static Snapshot<Long> snapshot(String category) {
        return new Snapshot<>("same-revision", List.of(new RuntimeDataRecord("item", "test:items", "item", "Item", "",
            Set.of(category), Set.of(), Map.of())), 1L);
    }

    private static RuntimeDataAdapter<String> adapter() {
        return new RuntimeDataAdapter<>() {
            @Override
            public String id() {
                return "test:items";
            }

            @Override
            public String domain() {
                return "item";
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                throw new AssertionError("Category capture must use its immutable snapshot");
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return record.id();
            }
        };
    }
}
