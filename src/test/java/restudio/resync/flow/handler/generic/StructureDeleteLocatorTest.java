package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureDeleteLocatorTest {
    private static final ContractRef<ResourceTypeId> STRUCTURE = ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("structure"));

    @Test
    void acceptsOnlyTheExactStructureLocatorForThisServer() {
        ServerId server = new ServerId(UUID.randomUUID());
        ServerResourceLocator locator = new ServerResourceLocator(server, STRUCTURE, "village_gate");

        assertEquals(locator, RegionHandler.requireStructureLocator(locator, server));
    }

    @Test
    void rejectsRawMissingAndWrongStructureReferences() {
        ServerId server = new ServerId(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> RegionHandler.requireStructureLocator("village_gate", server));
        assertThrows(IllegalArgumentException.class, () -> RegionHandler.requireStructureLocator(null, server));
        assertThrows(IllegalArgumentException.class, () -> RegionHandler.requireStructureLocator(
            new ServerResourceLocator(new ServerId(UUID.randomUUID()), STRUCTURE, "village_gate"), server));
        assertThrows(IllegalArgumentException.class, () -> RegionHandler.requireStructureLocator(
            new ServerResourceLocator(server, ContractRef.of(OwnerId.of("extension"), ResourceTypeId.of("structure")), "village_gate"), server));
        assertThrows(IllegalArgumentException.class, () -> RegionHandler.requireStructureLocator(
            new ServerResourceLocator(server, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("world")), "village_gate"), server));
        assertThrows(IllegalArgumentException.class, () -> new ServerResourceLocator(server, STRUCTURE, ""));
    }
}
