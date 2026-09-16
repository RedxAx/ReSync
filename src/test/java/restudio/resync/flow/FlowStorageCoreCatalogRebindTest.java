package restudio.resync.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowStorageCoreCatalogRebindTest {
    @Test
    void catalogRebindRecordsRequireCanonicalDecodedAssets() {
        assertThrows(NullPointerException.class, () -> new FlowStorage.CoreCatalogRebindSource(null));
        assertThrows(NullPointerException.class, () -> new FlowStorage.CoreCatalogRebindResult(null, false));
    }
}
