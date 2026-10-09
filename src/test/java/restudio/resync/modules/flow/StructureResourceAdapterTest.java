package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.structure.ReSyncStructure;
import restudio.resync.structure.StructureLibrary;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureResourceAdapterTest {
    @TempDir
    Path root;

    @Test
    void exposesStructureLibraryThroughTheTypedResourceCatalog() throws Exception {
        try (StructureLibrary library = new StructureLibrary(root)) {
            FlowResourceRegistry registry = new FlowResourceRegistry();
            registry.register(ReSyncResourceCatalog.STRUCTURE_OWNER, new StructureResourceAdapter(library));

            FlowResourceMetadata metadata = registry.metadata(ReSyncResourceCatalog.STRUCTURE);
            assertNotNull(metadata);
            assertEquals("structure", metadata.getTypeId());
            assertEquals("restudio.resync", metadata.getOwner());
            assertEquals("restudio.resync:structure", metadata.getOwner() + ":" + metadata.getTypeId());
            assertEquals("server:resync:structure", metadata.getCatalogSource());
            assertEquals("StructureLibrary", metadata.getAuthoritativeService());
            assertTrue(metadata.isAvailable());
            assertTrue(metadata.getOperations().containsAll(List.of("discover", "query", "get", "save", "delete")));

            ReSyncStructure first = structure("Village Gate.v1", "Village Gate");
            ReSyncStructure second = structure("tower", "Tower");
            FlowOperationResult<FlowResourceReference> saved = registry.save(ReSyncResourceCatalog.STRUCTURE, first);
            assertTrue(saved.success());
            assertEquals("village_gate_v1", saved.value().id());
            assertEquals("restudio.resync", saved.value().owner());
            assertTrue(registry.save(ReSyncResourceCatalog.STRUCTURE, second).success());

            assertEquals(List.of("tower", "village_gate_v1"), registry.discover(ReSyncResourceCatalog.STRUCTURE, "").value().stream()
                .map(FlowResourceReference::id).toList());
            assertEquals(List.of("village_gate_v1"), registry.query(ReSyncResourceCatalog.STRUCTURE, "gate").value().stream()
                .map(FlowResourceReference::id).toList());
            assertEquals("Village Gate", ((ReSyncStructure) registry.get(ReSyncResourceCatalog.STRUCTURE, "village_gate_v1").value()).getDisplayName());

            assertTrue(registry.delete(ReSyncResourceCatalog.STRUCTURE, "village_gate_v1").success());
            assertFalse(library.exists("village_gate_v1"));
        }
    }

    private ReSyncStructure structure(String id, String displayName) {
        ReSyncStructure structure = new ReSyncStructure();
        structure.setId(id);
        structure.setDisplayName(displayName);
        structure.setSizeX(1);
        structure.setSizeY(1);
        structure.setSizeZ(1);
        structure.setBlockTypes(new String[][][]{{{"AIR"}}});
        structure.setBlockDataStrings(new String[][][]{{{"minecraft:air"}}});
        return structure;
    }
}
