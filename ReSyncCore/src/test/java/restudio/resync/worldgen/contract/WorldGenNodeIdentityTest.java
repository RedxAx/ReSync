package restudio.resync.worldgen.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldGenNodeIdentityTest {
    @Test
    void qualifiedIdentityRoundTripsWithItsOwner() {
        WorldGenNodeIdentity identity = WorldGenNodeIdentity.parse("worldgen:simplex");

        assertEquals("worldgen", identity.reference().owner().canonicalText());
        assertEquals("simplex", identity.reference().id().value());
        assertEquals("worldgen:simplex", identity.wireText());
        assertEquals(identity, WorldGenNodeIdentity.parse(identity.wireText()));
    }

    @Test
    void wrongOwnersAndMalformedReferencesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.parse("other:simplex"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.parse("worldgen:simplex:extra"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.parse("worldgen: simplex"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.require("simplex"));
    }

    @Test
    void unqualifiedIdentityRequiresExplicitCompatibility() {
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.canonical("simplex", false));
        assertEquals("worldgen:simplex", WorldGenNodeIdentity.canonical("simplex", true));
        assertEquals("worldgen:simplex", WorldGenNodeIdentity.canonical("worldgen:simplex", false));
        assertThrows(IllegalArgumentException.class, () -> WorldGenNodeIdentity.canonical("other:simplex", true));
    }
}
