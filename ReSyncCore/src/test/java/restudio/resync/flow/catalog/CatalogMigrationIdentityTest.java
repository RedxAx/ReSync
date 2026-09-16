package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogMigrationIdentityTest {
    private static final OwnerId OWNER = OwnerId.of("resync.catalog.identity");
    private static final NodeId NODE = NodeId.of("list-node");

    @Test
    void canonicalMigrationPreservesCaseSensitiveLegacySourcePins() {
        CatalogMigrationEdge migration = migration(List.of(
            mapping("listA", "lista", CatalogNodeDescriptor.Direction.INPUT),
            mapping("listB", "listb", CatalogNodeDescriptor.Direction.INPUT),
            mapping("list", "list", CatalogNodeDescriptor.Direction.OUTPUT, true)));

        String canonical = CatalogCanonicalizer.canonicalMigrationContent(migration);

        assertTrue(canonical.contains("\"sourcePinId\":\"listA\""));
        assertTrue(canonical.contains("\"sourcePinId\":\"listB\""));
        assertTrue(canonical.contains("\"targetPinId\":\"lista\""));
        assertTrue(canonical.contains("\"targetPinId\":\"listb\""));
    }

    @Test
    void lowercasePinConstructorRemainsCompatible() {
        CatalogMigrationEdge.PinMapping mapping = new CatalogMigrationEdge.PinMapping(
            OWNER, NODE, 1, 2, PinId.of("value"), PinId.of("renamed-value"), CatalogNodeDescriptor.Direction.INPUT);

        assertEquals("value", mapping.source().value());
        assertEquals("renamed-value", mapping.target().value());
    }

    @Test
    void invalidLegacySourcePinIdsAreRejected() {
        for (String value : List.of("", " ", ".", "..", "1value", "value/name", "value name", "value\nname")) {
            assertThrows(IllegalArgumentException.class, () -> CatalogMigrationEdge.LegacyPinId.of(value), value);
        }
        assertThrows(IllegalArgumentException.class,
            () -> CatalogMigrationEdge.LegacyPinId.of("a".repeat(129)));
    }

    @Test
    void duplicateSourceAndTargetIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> migration(List.of(
            mapping("listA", "lista", CatalogNodeDescriptor.Direction.INPUT),
            mapping("listA", "listb", CatalogNodeDescriptor.Direction.INPUT))));
        assertThrows(IllegalArgumentException.class, () -> migration(List.of(
            mapping("listA", "lista", CatalogNodeDescriptor.Direction.INPUT),
            mapping("listB", "lista", CatalogNodeDescriptor.Direction.INPUT))));
        assertThrows(IllegalArgumentException.class, () -> migration(List.of(
            mapping("listA", "lista", CatalogNodeDescriptor.Direction.OUTPUT),
            mapping("listA", "listb", CatalogNodeDescriptor.Direction.OUTPUT))));
        assertThrows(IllegalArgumentException.class, () -> migration(List.of(
            mapping("listA", "lista", CatalogNodeDescriptor.Direction.OUTPUT),
            mapping("listB", "lista", CatalogNodeDescriptor.Direction.OUTPUT))));
    }

    @Test
    void oppositeDirectionsHaveIndependentPinIdentities() {
        CatalogMigrationEdge migration = migration(List.of(
            mapping("flow", "input-target", CatalogNodeDescriptor.Direction.INPUT),
            mapping("flow", "output-target", CatalogNodeDescriptor.Direction.OUTPUT),
            mapping("input-source", "shared", CatalogNodeDescriptor.Direction.INPUT),
            mapping("output-source", "shared", CatalogNodeDescriptor.Direction.OUTPUT)));

        assertEquals(4, migration.pinMappings().size());
        assertEquals(2, migration.pinMappings().stream()
            .filter(value -> value.direction() == CatalogNodeDescriptor.Direction.INPUT).count());
        assertEquals(2, migration.pinMappings().stream()
            .filter(value -> value.direction() == CatalogNodeDescriptor.Direction.OUTPUT).count());
    }

    @Test
    void canonicalPinMappingOrderIncludesDirectionBeforePinIdentity() {
        CatalogMigrationEdge migration = migration(List.of(
            mapping("a-source", "a-target", CatalogNodeDescriptor.Direction.OUTPUT),
            mapping("z-source", "z-target", CatalogNodeDescriptor.Direction.INPUT)));

        assertEquals(CatalogNodeDescriptor.Direction.INPUT, migration.pinMappings().getFirst().direction());
        assertEquals(CatalogNodeDescriptor.Direction.OUTPUT, migration.pinMappings().getLast().direction());
    }

    @Test
    void sourceAndTargetIdentityCollisionRequiresExplicitIdentityMeaning() {
        assertThrows(IllegalArgumentException.class,
            () -> mapping("value", "value", CatalogNodeDescriptor.Direction.INPUT));
        CatalogMigrationEdge.PinMapping identity = mapping(
            "value", "value", CatalogNodeDescriptor.Direction.INPUT, true);
        assertEquals("value", identity.source().value());
    }

    private static CatalogMigrationEdge migration(List<CatalogMigrationEdge.PinMapping> mappings) {
        return new CatalogMigrationEdge(
            CapabilityId.of("list-migration"), OWNER, NODE, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(NODE.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), mappings);
    }

    private static CatalogMigrationEdge.PinMapping mapping(
        String source,
        String target,
        CatalogNodeDescriptor.Direction direction
    ) {
        return mapping(source, target, direction, false);
    }

    private static CatalogMigrationEdge.PinMapping mapping(
        String source,
        String target,
        CatalogNodeDescriptor.Direction direction,
        boolean identityMeaningful
    ) {
        return new CatalogMigrationEdge.PinMapping(
            OWNER, NODE, 1, 2,
            CatalogMigrationEdge.LegacyPinId.of(source), PinId.of(target), direction, identityMeaningful);
    }
}
