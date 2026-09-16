package restudio.resync.customcontent;

import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.server.ReSyncPersistenceTopology;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomBlocksPersistenceParticipantTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path temporary;

    @AfterEach
    void tearDown() {
        if (MockBukkit.getMock() != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void freshAndExistingMappingsLoadFromTheExactFile() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path freshRoot = Files.createDirectory(temporary.resolve("fresh"));
        VanillaContentProvider fresh = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), freshRoot);
        assertEquals(Map.of(), fresh.getPlacedBlocks());
        assertTrue(Files.exists(freshRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME)));

        Path existingRoot = Files.createDirectory(temporary.resolve("existing"));
        Files.writeString(existingRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME), "{\"world:1:2:3\":\"stone_block\"}");
        VanillaContentProvider existing = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), existingRoot);
        assertEquals(Map.of("world:1:2:3", "stone_block"), existing.getPlacedBlocks());
    }

    @Test
    void legacyWorldNamesPreserveSpacesUnicodeAndTheChunkProtocolBoundary() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("world-name-compatibility"));
        String boundaryWorld = "w".repeat(256);
        String unicodeBoundaryWorld = "界".repeat(85);
        Map<String, String> legacy = new LinkedHashMap<>();
        legacy.put("My World:1:2:3", "stone_block");
        legacy.put("世界基地:-4:5:6", "unicode_block");
        legacy.put(boundaryWorld + ":7:8:9", "boundary_block");
        legacy.put(unicodeBoundaryWorld + ":10:11:12", "unicode_boundary_block");
        Path file = root.resolve(CustomBlocksPersistenceParticipant.FILE_NAME);
        Files.writeString(file, GSON.toJson(legacy));

        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);

        assertEquals("stone_block", provider.getPlacedBlocks().get("My World:1:2:3"));
        assertEquals("unicode_block", provider.getPlacedBlocks().get("世界基地:-4:5:6"));
        assertEquals("boundary_block", provider.getPlacedBlocks().get(boundaryWorld + ":7:8:9"));
        assertEquals("unicode_boundary_block", provider.getPlacedBlocks().get(unicodeBoundaryWorld + ":10:11:12"));
        byte[] canonical = Files.readAllBytes(file);
        assertTrue(new String(canonical).contains("\"version\":1"));
        assertTrue(Files.exists(root.resolve(".migrations").resolve("resync.custom-blocks-v1.backup")));
        assertTrue(Files.exists(root.resolve(".migrations").resolve("resync.custom-blocks-v1.json")));

        VanillaContentProvider restarted = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);

        assertArrayEquals(canonical, Files.readAllBytes(file));
        assertEquals(provider.getPlacedBlocks(), restarted.getPlacedBlocks());
    }

    @Test
    void legacyCoordinateKeysRejectSeparatorsControlsAndTraversal() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("world-name-rejection"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        provider.quiescePersistence();
        Path candidate = temporary.resolve("world-name-rejection-candidate").resolve(CustomBlocksPersistenceParticipant.FILE_NAME);
        Files.createDirectories(candidate.getParent());
        for (String world : List.of("../world", "world/../x", "world\\\\..\\\\x", "bad\u0001world", ".", "..", "界".repeat(86))) {
            Files.writeString(candidate, GSON.toJson(Map.of(world + ":1:2:3", "stone_block")));
            assertThrows(IOException.class, () -> provider.rebindPersistence(candidate));
        }
    }

    @Test
    void migrationRecoversPreparedCutAndRetainsBackupAndReportIntegrity() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("custom-blocks-migration-cut"));
        Path file = root.resolve(CustomBlocksPersistenceParticipant.FILE_NAME);
        byte[] legacyBytes = GSON.toJson(Map.of("world:1:2:3", "stone_block")).getBytes(StandardCharsets.UTF_8);
        Files.write(file, legacyBytes);

        VanillaContentProvider migrated = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        Path migrationRoot = root.resolve(".migrations");
        Path report = migrationRoot.resolve("resync.custom-blocks-v1.json");
        Map<String, Object> prepared = new LinkedHashMap<>((Map<String, Object>) CanonicalCodec.decodePermissive(
            Files.readAllBytes(report)).toJava());
        prepared.remove("contentHash");
        prepared.put("state", "PREPARED");
        prepared.put("contentHash", StorageSafety.sha256(JsonValue.fromJava(prepared).canonicalBytes()));
        Files.write(report, JsonValue.fromJava(prepared).canonicalBytes());
        Files.write(file, legacyBytes);

        VanillaContentProvider recovered = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);

        assertEquals(migrated.getPlacedBlocks(), recovered.getPlacedBlocks());
        assertTrue(Files.readString(report).contains("\"state\":\"COMMITTED\""));
        byte[] canonical = Files.readAllBytes(file);
        new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        assertArrayEquals(canonical, Files.readAllBytes(file));

        Files.writeString(migrationRoot.resolve("resync.custom-blocks-v1.backup"), "tampered");
        assertThrows(IllegalStateException.class,
            () -> new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root));

        Path reportRoot = Files.createDirectory(temporary.resolve("custom-blocks-migration-report-tamper"));
        Path reportFile = reportRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME);
        Files.write(reportFile, legacyBytes);
        new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), reportRoot);
        Path reportPath = reportRoot.resolve(".migrations").resolve("resync.custom-blocks-v1.json");
        String reportText = Files.readString(reportPath);
        int hashStart = reportText.indexOf("\"contentHash\":\"") + "\"contentHash\":\"".length();
        char replacement = reportText.charAt(hashStart) == '0' ? '1' : '0';
        Files.writeString(reportPath, reportText.substring(0, hashStart) + replacement + reportText.substring(hashStart + 1));
        assertThrows(IllegalStateException.class,
            () -> new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), reportRoot));
    }

    @Test
    void mutationFlushAndRestartPreserveMappingsExactly() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("restart"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        provider.markPlacedBlock(location(1, 2, 3), definition("stone_block"));
        provider.flushPersistence();

        VanillaContentProvider restarted = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);

        assertEquals(Map.of("world:1:2:3", "stone_block"), restarted.getPlacedBlocks());
    }

    @Test
    void quiesceRejectsMappingMutationsAndShutdownClosesAuthority() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("quiesce"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        provider.quiescePersistence();

        assertThrows(IllegalStateException.class, () -> provider.markPlacedBlock(location(1, 2, 3), definition("blocked")));
        provider.closePersistence();
        assertThrows(IllegalStateException.class, () -> provider.clearPlacedBlock(location(1, 2, 3)));
    }

    @Test
    void rebindValidatesBeforeSwitchAndRetainsOldGenerationOnFailure() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("active"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(root, provider);
        provider.markPlacedBlock(location(1, 2, 3), definition("old"));
        byte[] originalBytes = Files.readAllBytes(root.resolve(CustomBlocksPersistenceParticipant.FILE_NAME));
        provider.quiescePersistence();
        long generation = provider.generation();

        Path malformedRoot = Files.createDirectory(temporary.resolve("malformed"));
        Files.writeString(malformedRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME), "[]");
        assertThrows(IOException.class, () -> participant.rebind(malformedRoot));
        assertEquals(root.resolve(CustomBlocksPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize(), participant.root());
        assertEquals(generation, provider.generation());
        assertArrayEquals(originalBytes, Files.readAllBytes(root.resolve(CustomBlocksPersistenceParticipant.FILE_NAME)));
        assertEquals(Map.of("world:1:2:3", "old"), provider.getPlacedBlocks());

        Path missingRoot = Files.createDirectory(temporary.resolve("missing"));
        assertThrows(IOException.class, () -> participant.rebind(missingRoot));
        assertEquals(generation, provider.generation());

        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate"));
        Files.writeString(candidateRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME), "{\"world:4:5:6\":\"new\"}");
        participant.rebind(candidateRoot);
        assertEquals(Map.of("world:4:5:6", "new"), provider.getPlacedBlocks());
        assertEquals(generation + 1L, provider.generation());
    }

    @Test
    void candidateValidationRejectsNonStringMappings() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("semantic"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        provider.quiescePersistence();
        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate-semantic"));
        Files.writeString(candidateRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME), "{\"world:1:2:3\":1}");

        assertThrows(IOException.class, () -> provider.rebindPersistence(candidateRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME)));
    }

    @Test
    void canonicalContractRejectsUnknownFieldsCaseCollisionsAndOutOfBoundsCoordinates() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("strict-blocks"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        provider.quiescePersistence();

        Path candidateRoot = Files.createDirectory(temporary.resolve("strict-blocks-candidate"));
        Path candidate = candidateRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME);
        Files.writeString(candidate, "{\"contentHash\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"kind\":\"resync.custom-blocks\",\"mappings\":[],\"unknown\":true,\"version\":1}");
        assertThrows(IOException.class, () -> provider.rebindPersistence(candidate));
        Files.writeString(candidate, "{\"world:1:2:3\":\"stone\",\"WORLD:1:2:3\":\"dirt\"}");
        assertThrows(IOException.class, () -> provider.rebindPersistence(candidate));
        Files.writeString(candidate, "{\"world:1:2147483648:3\":\"stone\"}");
        assertThrows(IOException.class, () -> provider.rebindPersistence(candidate));
        Files.writeString(candidate, "{\"world:1:2:3\":1}");
        assertThrows(IOException.class, () -> provider.rebindPersistence(candidate));
    }

    @Test
    void concurrentMutationAndReadsRemainLinearized() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("concurrent"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<?> first = executor.submit(() -> mutate(provider, "first", 0));
        Future<?> second = executor.submit(() -> mutate(provider, "second", 30));
        Future<?> reader = executor.submit(() -> {
            for (int index = 0; index < 50; index++) {
                provider.getPlacedBlocks();
            }
            return null;
        });
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        reader.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(60, provider.getPlacedBlocks().size());
    }

    @Test
    void concurrentMutationReadAndRebindNeverExposeMixedGeneration() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path activeRoot = Files.createDirectory(temporary.resolve("concurrent-rebind"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), activeRoot);
        provider.markPlacedBlock(location(1, 2, 3), definition("old"));
        CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(activeRoot, provider);
        provider.quiescePersistence();

        Path candidateRoot = Files.createDirectory(temporary.resolve("concurrent-candidate"));
        Files.writeString(candidateRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME), "{\"world:4:5:6\":\"new\"}");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> reader = executor.submit(() -> {
            start.await();
            for (int index = 0; index < 200; index++) {
                long before = participant.generation();
                String value = provider.getPlacedBlocks().values().stream().findFirst().orElseThrow();
                long after = participant.generation();
                if (before == after) {
                    String expected = before == 0L ? "old" : "new";
                    if (!expected.equals(value)) {
                        throw new AssertionError("Mixed custom block generation observed");
                    }
                }
            }
            return null;
        });
        Future<?> writer = executor.submit(() -> {
            start.await();
            assertThrows(IllegalStateException.class,
                () -> provider.markPlacedBlock(location(7, 8, 9), definition("blocked")));
            return null;
        });
        Future<?> rebinder = executor.submit(() -> {
            start.await();
            participant.rebind(candidateRoot);
            return null;
        });
        start.countDown();
        reader.get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);
        rebinder.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1L, participant.generation());
        assertEquals(Map.of("world:4:5:6", "new"), provider.getPlacedBlocks());
    }

    @Test
    void externalBukkitStateIsOutsideParticipantOwnership() throws Exception {
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = Files.createDirectory(temporary.resolve("external"));
        Path external = temporary.resolve("external-world-state.txt");
        Files.writeString(external, "untouched");
        ItemStack externalItem = new ItemStack(Material.STICK);
        NamespacedKey externalKey = new NamespacedKey(plugin, "external-sentinel");
        ItemMeta externalMeta = externalItem.getItemMeta();
        externalMeta.getPersistentDataContainer().set(externalKey, PersistentDataType.STRING, "untouched");
        externalItem.setItemMeta(externalMeta);
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), root);
        CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(root, provider);

        participant.flush();
        participant.quiesce();
        participant.healthCheck();
        participant.close();

        assertEquals("untouched", Files.readString(external));
        ItemMeta restoredMeta = externalItem.getItemMeta();
        assertEquals("untouched", restoredMeta.getPersistentDataContainer().get(externalKey, PersistentDataType.STRING));
        assertTrue(Files.notExists(temporary.resolve("custom-blocks.json")));
    }

    @Test
    void topologyOwnsOnlyExactMappingFile() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = Files.createDirectory(temporary.resolve("topology"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), dataRoot);
        CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(dataRoot, provider);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)),
            List.of());

        assertTrue(registration.sealed());
        Path activeRoot = coordinator.activeDataRoot();
        assertEquals(participant.owner(), coordinator.participants().ownerFor(activeRoot, participant.root()));
        assertFalse(participant.owns(participant.root().resolve("nested")));
        coordinator.close();
        assertTrue(provider.isClosed());
    }

    @Test
    void ownershipIndexClaimsOnlyTheCustomBlocksFile() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = Files.createDirectory(temporary.resolve("ownership"));
        VanillaContentProvider provider = new VanillaContentProvider(plugin, new ItemAttributeSchemaService(), dataRoot);
        CustomBlocksPersistenceParticipant participant = new CustomBlocksPersistenceParticipant(dataRoot, provider);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(dataRoot, participant.root()));

        assertTrue(index.owns(CustomBlocksPersistenceParticipant.FILE_NAME));
        assertFalse(index.owns(CustomBlocksPersistenceParticipant.FILE_NAME + ".tmp"));
        assertFalse(index.owns(CustomBlocksPersistenceParticipant.FILE_NAME + "/nested"));
        provider.closePersistence();
    }

    private void mutate(VanillaContentProvider provider, String prefix, int offset) {
        for (int index = 0; index < 30; index++) {
            provider.markPlacedBlock(location(offset + index, 2, 3), definition(prefix + index));
        }
    }

    private CustomContentDefinition definition(String id) {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(id);
        definition.setType("block");
        return definition;
    }

    private Location location(int x, int y, int z) {
        return new Location(MockBukkit.getMock().getWorld("world"), x, y, z);
    }
}
