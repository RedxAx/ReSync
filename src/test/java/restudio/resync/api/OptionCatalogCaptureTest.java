package restudio.resync.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionCatalogCaptureTest {
    @Test
    void freezesItemsAndDerivesValuesFromTheSameCapture() {
        List<OptionCatalogItem> source = new ArrayList<>();
        source.add(new OptionCatalogItem("one"));
        OptionCatalogCapture capture = new OptionCatalogCapture("revision-1", source, "available", "");
        source.add(new OptionCatalogItem("two"));

        assertEquals(List.of("one"), capture.values());
        assertEquals(1, capture.items().size());
        assertThrows(UnsupportedOperationException.class, () -> new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:unsupported";
            }

            @Override
            public String revision() {
                return "legacy";
            }

            @Override
            public List<String> values() {
                return List.of("legacy");
            }
        }.capture(new OptionCatalogQuery("server:test:unsupported", Map.of())));
    }
}
