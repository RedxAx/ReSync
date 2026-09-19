package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CatalogSourceIndexTest {
    @Test
    void internReusesParsedCanonicalSourceForIdenticalBytes() {
        byte[] bytes = "{\"id\":\"alpha\",\"displayName\":\"Alpha\"}".getBytes(StandardCharsets.UTF_8);

        CatalogSourceIndex.Entry first = CatalogSourceIndex.intern(bytes);
        CatalogSourceIndex.Entry second = CatalogSourceIndex.intern(bytes.clone());

        assertSame(first, second);
        assertEquals(ContentHash.of(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(bytes))),
            first.sourceHash());
        assertEquals(first.sourceHash(), CatalogSourceIndex.sourceHash(bytes));
    }
}
