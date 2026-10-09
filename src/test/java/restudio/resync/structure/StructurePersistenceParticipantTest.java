package restudio.resync.structure;

import com.google.gson.Gson;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
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

        try (StructureLibrary library = StructureLibrary.get(plugin, activeRoot)) {
            library.save(structure("active"));

            assertEquals(activeRoot.resolve("structures").toAbsolutePath().normalize(), library.getStructuresDir());
            assertSame(library, StructureLibrary.get(plugin));
            assertTrue(Files.exists(activeRoot.resolve("structures").resolve("active.resync-structure")));
            assertFalse(Files.exists(operatorRoot.resolve("structures").resolve("active.resync-structure")));
        }
    }

    @Test
    void quiesceBlocksStructureWritesAndResumeReopensAdmission() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        try (StructureLibrary library = new StructureLibrary(dataRoot)) {
            StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);

            library.save(structure("first"));
            participant.quiesce();
            assertThrows(IllegalStateException.class, () -> library.save(structure("blocked")));

            participant.resume();
            library.save(structure("second"));
            assertTrue(Files.exists(dataRoot.resolve("structures").resolve("second.resync-structure")));
            assertEquals(List.of("first", "second"), library.list().stream().map(StructureSummary::id).toList());
        }
    }

    @Test
    void rebindRequiresQuiescenceAndPreservesThePreviousRootOnFailure() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        try (StructureLibrary library = new StructureLibrary(dataRoot)) {
            StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);
            library.save(structure("source"));
            Path previousRoot = participant.root();

            Path replacement = Files.createDirectory(temporary.resolve("replacement"));
            Files.createDirectories(replacement.resolve("structures"));
            try (StructureLibrary replacementLibrary = new StructureLibrary(replacement)) {
                replacementLibrary.save(structure("target"));
            }

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
    }

    @Test
    void healthCheckRejectsMalformedStructureData() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        try (StructureLibrary library = new StructureLibrary(dataRoot)) {
            Path file = dataRoot.resolve("structures").resolve("broken.resync-structure");
            try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
                gzip.write("not-json".getBytes(StandardCharsets.UTF_8));
            }

            assertThrows(IOException.class, library::healthCheckPersistence);

            Path legacy = Files.createDirectory(temporary.resolve("legacy"));
            Path legacyFolder = Files.createDirectories(legacy.resolve("structures/old-folder"));
            Path legacyFile = legacyFolder.resolve("kept.resync-structure");
            Files.writeString(legacyFile, "preserved");
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new StructureLibrary(legacy));
            assertTrue(failure.getCause().getMessage().contains("uncoordinated data"));
            assertEquals("preserved", Files.readString(legacyFile));
            assertFalse(Files.exists(legacy.resolve("structures/.asset-coordinator")));
        }
    }

    @Test
    void failedPhysicalCloseRemainsFailedAndCannotReopenTheRoot() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("failed-close"));
        StructureLibrary library = new StructureLibrary(dataRoot);
        Field coordinatorField = StructureLibrary.class.getDeclaredField("coordinator");
        coordinatorField.setAccessible(true);
        AssetTransactionCoordinator coordinator = (AssetTransactionCoordinator) coordinatorField.get(library);
        Field contextField = AssetTransactionCoordinator.class.getDeclaredField("context");
        contextField.setAccessible(true);
        Object context = contextField.get(coordinator);
        Field channelField = context.getClass().getDeclaredField("lockChannel");
        channelField.setAccessible(true);
        ((FileChannel) channelField.get(context)).close();

        IOException failure = assertThrows(IOException.class, library::close);
        assertSame(failure, assertThrows(IOException.class, library::close));
        assertSame(failure, assertThrows(IOException.class, coordinator::close));
        assertThrows(IllegalStateException.class, () -> library.save(structure("blocked")));
        assertThrows(IllegalStateException.class, library::resumePersistence);
        AssetTransactionCoordinator.RootBusyException reopen = assertThrows(AssetTransactionCoordinator.RootBusyException.class,
            () -> AssetTransactionCoordinator.open(dataRoot.resolve("structures"), new Gson()));
        assertSame(failure, reopen.getCause());
    }

    @Test
    void malformedGeometryCannotCommitOrEnterColdResidency() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("geometry"));
        Path file = dataRoot.resolve("structures/broken.resync-structure");
        try (StructureLibrary library = new StructureLibrary(dataRoot)) {
            ReSyncStructure malformed = structure("broken");
            malformed.setSizeX(-1);
            assertThrows(IllegalArgumentException.class, () -> library.save(malformed));
            malformed.setSizeX(2);
            assertThrows(IllegalArgumentException.class, () -> library.save(malformed));
            malformed.setSizeX(1);
            malformed.getBlockDataStrings()[0][0][0] = null;
            assertThrows(IllegalArgumentException.class, () -> library.save(malformed));
            assertFalse(Files.exists(file));
            assertTrue(library.list().isEmpty());

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(new Gson().toJson(malformed).getBytes(StandardCharsets.UTF_8));
            }
            try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(dataRoot.resolve("structures"), new Gson())) {
                coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(),
                    coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                    List.of(AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("structure", "broken"),
                        file, AssetTransactionCoordinator.Missing.INSTANCE, bytes.toByteArray())), List.of()));
            }
            assertThrows(IllegalStateException.class, () -> library.load("broken"));
            assertThrows(IOException.class, library::healthCheckPersistence);
        }
        assertThrows(IllegalStateException.class, () -> new StructureLibrary(dataRoot));
        assertTrue(Files.exists(file));
    }

    @Test
    void ownershipIndexIncludesTheStructureRootAndDescendants() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source-index"));
        try (StructureLibrary library = new StructureLibrary(dataRoot)) {
            StructurePersistenceParticipant participant = new StructurePersistenceParticipant(dataRoot, library);
            PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
            PersistenceOwnershipIndex index = participant.ownershipIndex(context);

            assertTrue(index.owns(context.relativeToSource(participant.root())));
            assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/structure.resync-structure"))));
        }
    }

    @Test
    void residentStructuresRemainIsolatedAndRejectPhysicalReplacementAcrossCommittedChanges() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("resident"));
        try (StructureLibrary library = new StructureLibrary(source)) {
            ReSyncStructure original = structure("first");
            library.save(original);
            original.getBlockTypes()[0][0][0] = "minecraft:dirt";
            ReSyncStructure loaded = library.load("first").orElseThrow();
            assertEquals("minecraft:stone", loaded.getBlockTypes()[0][0][0]);
            loaded.setDisplayName("Uncommitted");
            loaded.getBlockTypes()[0][0][0] = "minecraft:gold_block";
            loaded.getBlockDataStrings()[0][0][0] = "minecraft:gold_block";
            ReSyncStructure warm = library.load("first").orElseThrow();
            assertEquals("first", warm.getDisplayName());
            assertEquals("minecraft:stone", warm.getBlockTypes()[0][0][0]);
            assertEquals("minecraft:stone", warm.getBlockDataStrings()[0][0][0]);

            Path file = source.resolve("structures/first.resync-structure");
            byte[] committed = Files.readAllBytes(file);
            FileTime modified = Files.getLastModifiedTime(file);
            Path replacement = source.resolve("structures/replacement");
            Files.write(replacement, committed);
            Files.setLastModifiedTime(replacement, modified);
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            assertEquals("minecraft:stone", library.load("first").orElseThrow().getBlockTypes()[0][0][0]);
            byte[] tampered = committed.clone();
            tampered[tampered.length / 2] ^= 1;
            Files.write(replacement, tampered);
            Files.setLastModifiedTime(replacement, modified);
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IllegalStateException.class, () -> library.load("first"));
            assertThrows(IOException.class, library::healthCheckPersistence);
            Files.write(file, committed);
            library.reload();

            try (StructureLibrary peer = new StructureLibrary(source)) {
                ReSyncStructure changed = peer.load("first").orElseThrow();
                changed.setDisplayName("Committed");
                changed.getBlockTypes()[0][0][0] = "minecraft:dirt";
                peer.save(changed);
                assertEquals("Committed", library.load("first").orElseThrow().getDisplayName());
                assertEquals("minecraft:dirt", library.load("first").orElseThrow().getBlockTypes()[0][0][0]);
                assertTrue(peer.delete("first"));
                assertTrue(library.load("first").isEmpty());
                assertFalse(library.exists("first"));
                peer.save(structure("first"));
                assertEquals("minecraft:stone", library.load("first").orElseThrow().getBlockTypes()[0][0][0]);
            }

            Path target = Files.createDirectory(temporary.resolve("resident-target"));
            try (StructureLibrary replacementLibrary = new StructureLibrary(target)) {
                ReSyncStructure changed = structure("first");
                changed.setDisplayName("Rebound");
                replacementLibrary.save(changed);
                library.quiescePersistence();
                library.rebindPersistence(target.resolve("structures"));
                library.resumePersistence();
                assertEquals("Rebound", library.load("first").orElseThrow().getDisplayName());
            }
        }
        try (StructureLibrary reopened = new StructureLibrary(source)) {
            assertEquals("minecraft:stone", reopened.load("first").orElseThrow().getBlockTypes()[0][0][0]);
        }
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
