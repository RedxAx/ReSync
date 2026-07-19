package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpHandlerPolicyTest {
    private final HttpHandler handler = new HttpHandler();

    @Test
    void acceptsOnlyHttpSchemesWithAHost() throws Exception {
        assertEquals("https", handler.validateUri("https://example.com/api").getScheme());
        assertThrows(IllegalArgumentException.class, () -> handler.validateUri("file:///tmp/data"));
        assertThrows(IllegalArgumentException.class, () -> handler.validateUri("https:/missing-host"));
        assertThrows(IllegalArgumentException.class, () -> handler.validateUri(""));
    }

    @Test
    void timeoutPolicyHasStableBounds() {
        assertEquals(100, handler.validateTimeout(100));
        assertEquals(120_000, handler.validateTimeout(120_000));
        assertThrows(IllegalArgumentException.class, () -> handler.validateTimeout(99));
        assertThrows(IllegalArgumentException.class, () -> handler.validateTimeout(120_001));
    }

    @Test
    void requestMethodsAreExplicitlyBounded() {
        assertEquals("GET", handler.validateMethod("get"));
        assertEquals("PATCH", handler.validateMethod(" PATCH "));
        assertThrows(IllegalArgumentException.class, () -> handler.validateMethod("TRACE"));
        assertThrows(IllegalArgumentException.class, () -> handler.validateMethod(""));
    }
}
