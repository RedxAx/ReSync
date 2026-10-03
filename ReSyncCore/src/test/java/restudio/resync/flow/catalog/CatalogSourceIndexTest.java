package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void admittedNestedValuesCannotPoisonOtherReaders() {
        byte[] bytes = "{\"pins\":[{\"default\":null,\"name\":\"value\"}]}".getBytes(StandardCharsets.UTF_8);
        Map<?, ?> root = (Map<?, ?>) CatalogSourceIndex.intern(bytes).parsed();
        List<?> pins = (List<?>) root.get("pins");
        Map<?, ?> pin = (Map<?, ?>) pins.getFirst();

        assertThrows(UnsupportedOperationException.class, root::clear);
        assertThrows(UnsupportedOperationException.class, pins::clear);
        assertThrows(UnsupportedOperationException.class, pin::clear);
        assertNull(pin.get("default"));
        assertEquals("value", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) CatalogSourceIndex.intern(bytes).parsed())
            .get("pins")).getFirst()).get("name"));
    }

    @Test
    void changedBytesCreateANewVerifiedSourceIdentity() {
        byte[] bytes = "{\"id\":\"alpha\"}".getBytes(StandardCharsets.UTF_8);
        CatalogSourceIndex.Entry first = CatalogSourceIndex.intern(bytes);
        bytes[7] = 'o';
        CatalogSourceIndex.Entry changed = CatalogSourceIndex.intern(bytes);

        assertNotEquals(first.rawHash(), changed.rawHash());
        assertNotEquals(first.sourceHash(), changed.sourceHash());
        assertEquals("alpha", ((Map<?, ?>) first.parsed()).get("id"));
        assertEquals("olpha", ((Map<?, ?>) changed.parsed()).get("id"));
    }
}
