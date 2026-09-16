package restudio.resync.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityEpochTest {
    @Test
    void requiresAPositiveBoundEpoch() {
        assertThrows(NullPointerException.class, () -> new AuthorityEpoch(null));
        assertThrows(IllegalArgumentException.class, () -> AuthorityEpoch.fixed(0L));
        assertThrows(IllegalArgumentException.class, () -> AuthorityEpoch.fixed(-1L));
    }

    @Test
    void rejectsUnboundOrMismatchedTypedEpochs() {
        AtomicLong value = new AtomicLong(7L);
        AuthorityEpoch epoch = new AuthorityEpoch(value::get);

        assertTrue(epoch.acceptsTyped(7L));
        assertFalse(epoch.acceptsTyped(0L));
        assertFalse(epoch.acceptsTyped(6L));
        assertFalse(epoch.acceptsTyped(8L));

        value.set(0L);
        assertThrows(IllegalStateException.class, epoch::current);
    }

    @Test
    void legacyJsonRequiresExplicitCompatibility() {
        AuthorityEpoch epoch = AuthorityEpoch.fixed(4L);

        assertFalse(epoch.acceptsLegacyJson("{}"));
        assertFalse(epoch.acceptsLegacyJson("{\"authorityEpoch\":0}"));
        assertTrue(epoch.acceptsLegacyJson("{}", true));
        assertTrue(epoch.acceptsLegacyJson("{\"authorityEpoch\":0}", true));
        assertTrue(epoch.acceptsLegacyJson("{\"authorityEpoch\":4}"));
        assertFalse(epoch.acceptsLegacyJson("{\"authorityEpoch\":3}", true));
        assertFalse(epoch.acceptsLegacyJson("{\"authorityEpoch\":-1}", true));
    }
}
