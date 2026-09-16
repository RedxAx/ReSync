package restudio.resync.structure;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void compatibilityLookupRemainsBoundToTheActiveRoot() throws Exception {
        Path operatorRoot = Files.createDirectory(temporary.resolve("operator"));
        Path activeRoot = Files.createDirectory(temporary.resolve("active"));
        Plugin plugin = plugin(operatorRoot);

        StructureLibrary library = StructureLibrary.get(plugin, activeRoot);
        library.save(structure("active"));

        assertEquals(activeRoot.resolve("structures").toAbsolutePath().normalize(), library.getStructuresDir());
        assertSame(library, StructureLibrary.get(plugin));
        assertTrue(Files.exists(activeRoot.resolve("structures").resolve("active.resync-structure")));
        assertFalse(Files.exists(operatorRoot.resolve("structures").resolve("active.resync-structure")));
    }

    @Test
    void quiesceBlocksStructureWritesAndResumeReopensAdmission() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        StructureLibrary library = new StructureLibrary(dataRoot);
        StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);

        library.save(structure("first"));
        participant.quiesce();
        assertThrows(IllegalStateException.class, () -> library.save(structure("blocked")));

        participant.resume();
        library.save(structure("second"));
        assertTrue(Files.exists(dataRoot.resolve("structures").resolve("second.resync-structure")));
        assertEquals(List.of("first", "second"), library.list().stream().map(StructureSummary::id).toList());
    }

    @Test
    void rebindRequiresQuiescenceAndPreservesThePreviousRootOnFailure() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        StructureLibrary library = new StructureLibrary(dataRoot);
        StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);
        library.save(structure("source"));
        Path previousRoot = participant.root();

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        Files.createDirectories(replacement.resolve("structures"));
        StructureLibrary replacementLibrary = new StructureLibrary(replacement);
        replacementLibrary.save(structure("target"));

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(temporary.resolve("missing")));
        assertEquals(previousRoot, participant.root());

        participant.rebind(replacement);
        assertEquals(replacement.resolve("structures").toAbsolutePath().normalize(), participant.root());
        participant.resume();
        assertTrue(library.exists("target"));
        assertFalse(library.exists("source"));
    }

    @Test
    void healthCheckRejectsMalformedStructureData() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        StructureLibrary library = new StructureLibrary(dataRoot);
        Path file = dataRoot.resolve("structures").resolve("broken.resync-structure");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write("not-json".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(IOException.class, library::healthCheckPersistence);
    }

    @Test
    void ownershipIndexIncludesTheStructureRootAndDescendants() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source-index"));
        StructureLibrary library = new StructureLibrary(dataRoot);
        StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/structure.resync-structure"))));
    }

    private ReSyncStructure structure(String id) {
        ReSyncStructure structure = new ReSyncStructure();
        structure.setId(id);
        structure.setDisplayName(id);
        structure.setSizeX(1);
        structure.setSizeY(1);
        structure.setSizeZ(1);
        structure.setBlockTypes(new String[][][]{{{"minecraft:stone"}}});
        structure.setBlockDataStrings(new String[][][]{{{"minecraft:stone"}}});
        return structure;
    }

    private Plugin plugin(Path dataRoot) {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> method.getName().equals("getDataFolder") ? dataRoot.toFile() : null);
    }
}
