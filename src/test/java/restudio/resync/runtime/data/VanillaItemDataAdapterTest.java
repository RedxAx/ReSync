package restudio.resync.runtime.data;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VanillaItemDataAdapterTest {
    @Test
    void categoryCaptureUsesTheResidentImmutableAdapterRevisionWithoutRecursion() {
        MockBukkit.mock();
        try {
            VanillaItemDataAdapter adapter = new VanillaItemDataAdapter();
            List<RuntimeDataRecord> records = adapter.records(RuntimeDataQuery.all());
            assertFalse(records.isEmpty());
            String revision = adapter.revision();
            OptionCatalogProvider categories = adapter.categoryCatalog();
            OptionCatalogQuery query = new OptionCatalogQuery(categories.sourceId(), Map.of());

            OptionCatalogCapture capture = assertDoesNotThrow(() -> categories.capture(query));
            assertFalse(capture.items().isEmpty());
            assertEquals(records.size(), capture.items().stream().filter(item -> item.value().equals("vanilla"))
                .findFirst().orElseThrow().metadata().get("count"));
            assertEquals(capture.revision(), assertDoesNotThrow(() -> categories.revision()));
            assertSame(capture, assertDoesNotThrow(() -> categories.capture(query)));
            assertThrows(UnsupportedOperationException.class, () -> capture.items().add(capture.items().getFirst()));
            assertThrows(UnsupportedOperationException.class, () -> capture.items().getFirst().metadata().put("count", -1));
            assertSame(revision, adapter.revision());
            assertSame(records, adapter.records(RuntimeDataQuery.all()));
            assertThrows(UnsupportedOperationException.class, () -> records.add(records.getFirst()));
            assertThrows(UnsupportedOperationException.class, () -> records.getFirst().categories().add("changed"));
            assertThrows(UnsupportedOperationException.class, () -> records.getFirst().attributes().put("material", "changed"));
            assertSame(revision, adapter.revision());
        } finally {
            MockBukkit.unmock();
        }
    }
}
