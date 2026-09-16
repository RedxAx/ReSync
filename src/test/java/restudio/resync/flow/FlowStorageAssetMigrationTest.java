package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.core.Session;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;
import restudio.resync.storage.StorageSafety;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageAssetMigrationTest {
    @TempDir
    Path tempDir;
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void closeCoordinators() throws Exception {
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
        coordinators.clear();
    }

    @Test
    void projectMetadataSaveDoesNotRewriteCanonicalTypedAssets() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"stable","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        Path asset;
        try (var paths = Files.walk(tempDir.resolve("assets"))) {
            asset = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("stable.json"))
                .findFirst()
                .orElseThrow();
        }
        FileTime marker = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(asset, marker);

        storage.saveProjectMetadata(storage.getProjectMetadata("project"));

        assertEquals(marker, Files.getLastModifiedTime(asset));
    }

    @Test
    void freshCanonicalGraphSaveCreatesProjectMetadataWithoutLegacyMigration() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"fresh","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(graph);

        Path project = tempDir.resolve("assets").resolve("project.json");
        assertTrue(Files.isRegularFile(project));
        String metadata = Files.readString(project);
        assertTrue(hasProjectResource(metadata, "flow", "fresh"));
        assertFalse(Files.exists(tempDir.resolve("flows").resolve("fresh.json")));
    }

    @Test
    void graphSavePreservesUnknownProjectAndResourceFields() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"unknown-fields","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        JsonObject project = JsonParser.parseString(storage.getProjectMetadata("project")).getAsJsonObject();
        project.addProperty("futureProjectField", "retained");
        project.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("futureResourceField", "retained");
        storage.saveProjectMetadata(new Gson().toJson(project));

        graph.setEnabled(false);
        storage.saveGraph(graph);

        JsonObject saved = JsonParser.parseString(storage.getProjectMetadata("project")).getAsJsonObject();
        assertEquals("retained", saved.get("futureProjectField").getAsString());
        assertEquals("retained", saved.getAsJsonArray("resources").get(0).getAsJsonObject()
            .get("futureResourceField").getAsString());
    }

    @Test
    void guiDeleteAndRecreatePublishExactCoordinatorTombstoneLineage() throws Exception {
        AssetTransactionCoordinator shared = coordinator();
        FlowStorage storage = new FlowStorage(tempDir.toFile(), shared);
        var gui = FlowSerializer.deserializeGui("""
            {"id":"menu","title":"Menu","rows":3,"elements":[]}
            """);
        AssetTransactionCoordinator.AssetKey liveKey = new AssetTransactionCoordinator.AssetKey("gui", "menu");
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey("tombstone:gui", "menu");

        storage.saveGui(gui);
        storage.deleteGui("menu");

        AssetTransactionCoordinator.Snapshot deleted = shared.read(snapshot -> snapshot);
        assertTrue(deleted.state(liveKey).orElseThrow() instanceof AssetTransactionCoordinator.Deleted);
        assertTrue(deleted.state(tombstoneKey).orElseThrow() instanceof AssetTransactionCoordinator.Live);
        assertEquals(deleted.mutationValue(liveKey), deleted.mutationValue(tombstoneKey));
        assertTrue(Files.isRegularFile(tempDir.resolve("assets/.tombstones/gui/menu.json")));

        storage.saveGui(gui);

        AssetTransactionCoordinator.Snapshot recreated = shared.read(snapshot -> snapshot);
        assertTrue(recreated.state(liveKey).orElseThrow() instanceof AssetTransactionCoordinator.Live);
        assertTrue(recreated.state(tombstoneKey).orElseThrow() instanceof AssetTransactionCoordinator.Deleted);
        assertEquals(3L, recreated.state(liveKey).orElseThrow().revision());
        assertFalse(Files.exists(tempDir.resolve("assets/.tombstones/gui/menu.json")));
    }

    @Test
    void migrationPrunesMissingFileBackedResourcesButKeepsRuntimeWorldEntries() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path items = assets.resolve("Content").resolve("Items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("present.json"), AssetFileFormat.withResourceIdentity("""
            {"resourceType":"custom_content","id":"present","type":"item"}
            """, ReSyncResourceCatalog.CUSTOM_CONTENT, 1L, "00000000-0000-0000-0000-000000000001"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [],
              "resources": [
                {"type":"custom_content","id":"present","displayName":"Present","path":"Content/Items","sortOrder":0},
                {"type":"custom_content","id":"missing","displayName":"Missing","path":"Content/Items","sortOrder":1},
                {"type":"gui","id":"Secondary","displayName":"Secondary","path":"GUIs","sortOrder":2},
                {"type":"world","id":"world","displayName":"world","path":"Worlds","sortOrder":3}
              ]
            }
            """);

        FlowStorage storage = newCompatibilityStorage();

        String project = Files.readString(assets.resolve("project.json"));
        assertTrue(hasProjectResource(project, "custom_content", "present"));
        assertFalse(hasProjectResourceId(project, "missing"));
        assertFalse(hasProjectResourceId(project, "Secondary"));
        assertTrue(hasProjectResource(project, "world", "world"));

        Gson gson = new Gson();
        try (JsonAssetStore<JsonObject> store = new JsonAssetStore<>(assets, tempDir.resolve("custom-content"),
            ReSyncResourceCatalog.CUSTOM_CONTENT, "Content/Items", json -> gson.fromJson(json, JsonObject.class),
            gson::toJson, this::id, null, LegacyRuntimeActivationGate.compatibility(tempDir), coordinator(), () -> true)) {
            store.delete("present");
        }

        assertFalse(hasProjectResourceId(storage.getProjectMetadata("project"), "present"));
    }

    @Test
    void graphStoragePreservesFlowFunctionAndCommandResourceTypes() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph flow = FlowSerializer.deserialize("""
            {"id":"regular","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph function = FlowSerializer.deserialize("""
            {"id":"lookup","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph command = FlowSerializer.deserialize("""
            {"id":"restart","version":2,"nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(flow);
        storage.saveGraph(function);
        storage.saveGraph(command);

        assertEquals("flow", storage.getGraphResourceType("regular"));
        assertEquals("function", storage.getGraphResourceType("lookup"));
        assertEquals("command", storage.getGraphResourceType("restart"));
        assertEquals(List.of("regular"), storage.listGraphIds("flow"));
        assertEquals(List.of("lookup"), storage.listGraphIds("function"));
        assertEquals(List.of("restart"), storage.listGraphIds("command"));
        try (var paths = Files.walk(tempDir.resolve("assets"))) {
            Path commandFile = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("restart.json"))
                .findFirst()
                .orElseThrow();
            assertTrue(Files.readString(commandFile).contains("\"resourceType\":\"command\""));
        }
    }

    @Test
    void graphStorageAllowsCrossTypeDuplicateIdsButRejectsUntypedResolution() {
        FlowStorage storage = newStorage();
        FlowGraph command = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"resourceType":"command","nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """);
        FlowGraph function = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"function":true,"resourceType":"function","nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph flow = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"resourceType":"flow","nodes":{},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(command);
        storage.saveGraph(function);
        storage.saveGraph(flow);

        assertEquals(List.of("shared"), storage.listGraphIds("flow"));
        assertEquals(List.of("shared"), storage.listGraphIds("command"));
        assertEquals(List.of("shared"), storage.listGraphIds("function"));
        assertEquals("flow", storage.getGraph("flow", "shared").getResourceType());
        assertEquals("command", storage.getGraph("command", "shared").getResourceType());
        assertEquals("function", storage.getGraph("function", "shared").getResourceType());
        assertThrows(IllegalArgumentException.class, () -> storage.getGraph("shared"));
        assertThrows(IllegalArgumentException.class, () -> storage.getGraphResourceType("shared"));
        assertThrows(IllegalArgumentException.class, () -> storage.deleteGraph("shared"));
    }

    @Test
    void graphStoragePreservesCrossTypeGraphIdsAcrossRestart() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph flow = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"resourceType":"flow","nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph function = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"function":true,"resourceType":"function","nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph command = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"resourceType":"command","nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(flow);
        storage.saveGraph(function);
        storage.saveGraph(command);
        AssetTransactionCoordinator original = coordinators.removeFirst();
        original.close();

        FlowStorage reopened = newStorage();

        assertEquals("flow", reopened.getGraph("flow", "shared").getResourceType());
        assertEquals("function", reopened.getGraph("function", "shared").getResourceType());
        assertEquals("command", reopened.getGraph("command", "shared").getResourceType());
        assertThrows(IllegalArgumentException.class, () -> reopened.getGraph("shared"));
        assertThrows(IllegalArgumentException.class, () -> reopened.deleteGraph("shared"));
    }

    @Test
    void graphSaveRelocatesForeignTypedPathBeforeReadingLineage() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path shared = assets.resolve("Shared");
        Path existing = shared.resolve("test.json");
        Files.createDirectories(shared);
        Files.writeString(existing, AssetFileFormat.withResourceIdentity("""
            {"id":"test","version":2,"resourceType":"flow","nodes":{},"connections":[],"localVariables":[]}
            """, "flow", 1L, "00000000-0000-0000-0000-000000000001"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId":"project",
              "folders":[{"path":"Shared","parentPath":"","name":"Shared","sortOrder":0}],
              "resources":[
                {"type":"flow","id":"test","displayName":"test","path":"Shared","sortOrder":0},
                {"type":"function","id":"test","displayName":"test","path":"Shared","sortOrder":1}
              ]
            }
            """);

        AssetTransactionCoordinator coordinator = coordinator();
        CanonicalProjectMetadataFixture.seed(coordinator);
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), CanonicalProjectMetadataFixture.serverId(), coordinator);
        FlowGraph function = FlowSerializer.deserialize("""
            {"id":"test","version":2,"function":true,"resourceType":"function","nodes":{},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(function);

        Path relocated = assets.resolve("Blueprints").resolve("Functions").resolve("function").resolve("test.json");
        assertTrue(Files.isRegularFile(existing));
        assertTrue(Files.isRegularFile(relocated));
        assertEquals("flow", AssetFileFormat.readResourceType(existing));
        assertEquals("function", AssetFileFormat.readResourceType(relocated));
        String project = Files.readString(assets.resolve("project.json"));
        assertTrue(hasProjectResource(project, "flow", "test", "Shared"));
        assertTrue(hasProjectResource(project, "function", "test", "Blueprints/Functions/function"));
    }

    @Test
    void migrationRelocatesFunctionStoredUnderFlowRoot() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flowFolder = assets.resolve("Blueprints").resolve("Flows");
        Path misplaced = flowFolder.resolve("dasha.json");
        Files.createDirectories(flowFolder);
        Files.writeString(misplaced, AssetFileFormat.withResourceIdentity("""
            {"id":"dasha","version":2,"function":true,"resourceType":"function","nodes":{},"connections":[],"localVariables":[]}
            """, "function", 1L, "00000000-0000-0000-0000-000000000001"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId":"project",
              "folders":[
                {"path":"Blueprints","parentPath":"","name":"Blueprints","sortOrder":0},
                {"path":"Blueprints/Flows","parentPath":"Blueprints","name":"Flows","sortOrder":0}
              ],
              "resources":[
                {"type":"function","id":"dasha","displayName":"dasha","path":"Blueprints/Flows","sortOrder":0}
              ]
            }
            """);

        newCompatibilityStorage();

        Path canonical = assets.resolve("Blueprints").resolve("Functions").resolve("dasha.json");
        assertTrue(Files.isRegularFile(canonical));
        assertFalse(Files.exists(misplaced));
        assertEquals("function", AssetFileFormat.readResourceType(canonical));
        assertTrue(hasProjectResource(Files.readString(assets.resolve("project.json")), "function", "dasha",
            "Blueprints/Functions"));
    }

    @Test
    void graphReclassificationMovesOneStableAssetWithoutLeavingShadowCopies() throws Exception {
        AssetTransactionCoordinator shared = coordinator();
        FlowStorage storage = new FlowStorage(tempDir.toFile(), shared);
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"convertible","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        graph.setFunction(false);
        graph.getNodes().put("start", FlowSerializer.deserialize("""
            {"id":"temporary","version":2,"nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """).getNodes().get("start"));

        storage.reclassifyGraph(graph, "command");

        assertEquals("command", storage.getGraphResourceType("convertible"));
        List<Path> matching = assetFiles(tempDir.resolve("assets")).stream()
                .filter(path -> path.getFileName().toString().equals("convertible.json"))
                .toList();
        assertEquals(1, matching.size());
        assertTrue(Files.readString(matching.getFirst()).contains("\"resourceType\":\"command\""));
        String project = Files.readString(tempDir.resolve("assets").resolve("project.json"));
        assertTrue(hasProjectResource(project, "command", "convertible"));
        assertFalse(hasProjectResource(project, "function", "convertible"));
        AssetTransactionCoordinator.Snapshot snapshot = shared.read(current -> current);
        AssetTransactionCoordinator.AssetKey source = new AssetTransactionCoordinator.AssetKey("function", "convertible");
        AssetTransactionCoordinator.AssetKey target = new AssetTransactionCoordinator.AssetKey("command", "convertible");
        assertTrue(snapshot.state(source).orElseThrow() instanceof AssetTransactionCoordinator.Deleted);
        assertTrue(snapshot.state(target).orElseThrow() instanceof AssetTransactionCoordinator.Live);
        assertEquals(snapshot.state(source).orElseThrow().revision(), snapshot.state(target).orElseThrow().revision());
        assertEquals(snapshot.mutationValue(source), snapshot.mutationValue(target));
    }

    @Test
    void migrationQuarantinesResourceCopiesOutsideTheirManagedPath() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path canonical = assets.resolve("Content").resolve("NPCs").resolve("guide.json");
        Path stale = assets.resolve("Customization").resolve("guide.json");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(stale.getParent());
        Files.writeString(canonical, """
            {"resourceType":"npc_definition","id":"guide","name":"Guide"}
            """);
        Files.writeString(stale, """
            {"resourceType":"npc_definition","id":"guide","name":"Old Guide"}
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [],
              "resources": [
                {"type":"npc_definition","id":"guide","displayName":"Guide","path":"Content/NPCs","sortOrder":0}
              ]
            }
            """);

        newCompatibilityStorage();

        assertTrue(Files.isRegularFile(canonical));
        assertFalse(Files.exists(stale));
        try (var paths = Files.walk(assets.resolve(".quarantine").resolve("duplicates"))) {
            List<Path> quarantined = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("guide.json"))
                .toList();
            assertEquals(1, quarantined.size());
            assertTrue(Files.readString(quarantined.getFirst()).contains("Old Guide"));
        }
    }

    @Test
    void migrationRejectsUnboundSnapshotEvidence() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path snapshot = assets.resolve(".snapshots").resolve("old").resolve("Blueprints").resolve("Functions").resolve("missing.json");
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, """
            {"resourceType":"function","id":"missing","version":2,"function":true,"nodes":{},"connections":[]}
            """);
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [],
              "resources": [
                {"type":"function","id":"missing","displayName":"Missing","path":"Blueprints/Functions","sortOrder":0}
              ]
            }
            """);

        assertThrows(IllegalStateException.class, this::newCompatibilityStorage);

        assertTrue(Files.readString(assets.resolve("project.json")).contains("\"id\":\"missing\""));
        assertTrue(Files.isRegularFile(snapshot));
    }

    @Test
    void migrationRejectsUnboundTransactionEvidence() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path transaction = assets.resolve(".transactions").resolve("old").resolve("content-0.json");
        Files.createDirectories(transaction.getParent());
        Files.writeString(transaction, """
            {"resourceType":"command","id":"content-0","version":2,"nodes":{},"connections":[]}
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                {"path":".transactions","parentPath":"","name":".transactions","sortOrder":0,"collapsed":false},
                {"path":".transactions/old","parentPath":".transactions","name":"old","sortOrder":1,"collapsed":false}
              ],
              "resources": [
                {"type":"command","id":"content-0","displayName":"content-0","path":".transactions/old","sortOrder":0}
              ]
            }
            """);

        assertThrows(IllegalStateException.class, this::newCompatibilityStorage);

        String metadata = Files.readString(assets.resolve("project.json"));
        assertTrue(metadata.contains(".transactions"));
        assertTrue(metadata.contains("\"id\":\"content-0\""));
        assertTrue(Files.isRegularFile(transaction));
    }

    @Test
    void migrationRemovesEmptyFoldersThatAreNotInProjectMetadata() throws Exception {
        Path leakedCopy = tempDir.resolve("assets/Blueprints/Commands/Blueprints Copy");
        Files.createDirectories(leakedCopy);

        newCompatibilityStorage();

        assertFalse(Files.exists(leakedCopy));
    }

    @Test
    void graphResourcesUseTheManagedLifecycleRegistry() {
        FlowStorage storage = newStorage();
        FlowResourceRegistry registry = new FlowResourceRegistry();
        new FlowResourcePacketRouter(storage, null, null, null, null, null, null, registry, ignored -> {
        });
        FlowGraph flow = FlowSerializer.deserialize("""
            {"id":"managed","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);

        assertTrue(registry.create(ReSyncResourceCatalog.FLOW, flow).success());
        assertEquals("managed", registry.discover(ReSyncResourceCatalog.FLOW, "manage").value().getFirst().id());
        assertTrue(registry.duplicate(ReSyncResourceCatalog.FLOW, "managed", "copy").success());
        assertEquals(List.of("copy", "managed"), storage.listGraphIds(ReSyncResourceCatalog.FLOW));
        assertFalse(registry.create(ReSyncResourceCatalog.FUNCTION, flow).success());
        assertTrue(registry.metadata().stream().filter(value -> ReSyncResourceCatalog.FLOW.equals(value.getTypeId())).findFirst().orElseThrow().isAvailable());
        assertTrue(registry.metadata().stream().filter(value -> ReSyncResourceCatalog.FUNCTION.equals(value.getTypeId())).findFirst().orElseThrow().isAvailable());
        assertTrue(registry.metadata().stream().filter(value -> ReSyncResourceCatalog.COMMAND.equals(value.getTypeId())).findFirst().orElseThrow().isAvailable());
    }

    @Test
    void graphResourcePacketsRouteByTheirExactType() {
        FlowStorage storage = newStorage();
        FlowGraph function = FlowSerializer.deserialize("""
            {"id":"lookup","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        FlowGraph command = FlowSerializer.deserialize("""
            {"id":"restart","version":2,"resourceType":"command","nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(function);
        storage.saveGraph(command);
        RecordingFlowPacketSender sender = new RecordingFlowPacketSender();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(storage, null, null, null, sender, null, null,
            new FlowResourceRegistry(), ignored -> {
        }, new ItemAttributeSchemaService(), AuthorityEpoch.fixed(1L), ignored -> true);
        Session session = new Session("session", "client", null);

        assertTrue(router.handle(session, (byte) 231, ByteBuffer.wrap("lookup".getBytes(StandardCharsets.UTF_8))));
        assertEquals((byte) 233, sender.packetId);
        assertEquals("function", FlowSerializer.deserialize(sender.payload).getResourceType());
        assertTrue(router.handle(session, (byte) 238, ByteBuffer.wrap("restart".getBytes(StandardCharsets.UTF_8))));
        assertEquals((byte) 240, sender.packetId);
        assertEquals("command", FlowSerializer.deserialize(sender.payload).getResourceType());
        assertTrue(router.handle(session, (byte) 232, ByteBuffer.allocate(0)));
        assertEquals((byte) 234, sender.packetId);
        assertEquals(List.of("lookup"), sender.ids);
    }

    @Test
    void worldResourcesExposeDiscoveryWithoutBypassingWorldSafetyOperations() {
        WorldRegistryEntry entry = new WorldRegistryEntry();
        entry.setWorldName("survival");
        entry.setLoaded(true);
        WorldSnapshot snapshot = new WorldSnapshot();
        snapshot.setWorlds(List.of(entry));
        WorldManagementService service = (WorldManagementService) Proxy.newProxyInstance(
            WorldManagementService.class.getClassLoader(),
            new Class<?>[]{WorldManagementService.class},
            (proxy, method, arguments) -> "createSnapshot".equals(method.getName()) ? snapshot : null
        );
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(newStorage(), null, null, null, null, null, null, registry, ignored -> {
        });
        router.registerExternalLifecycle(null, service);

        assertEquals("survival", registry.discover(ReSyncResourceCatalog.WORLD, "surv").value().getFirst().id());
        assertEquals("survival", ((WorldRegistryEntry) registry.get(ReSyncResourceCatalog.WORLD, "SURVIVAL").value()).getWorldName());
        assertFalse(registry.delete(ReSyncResourceCatalog.WORLD, "survival").success());
        assertEquals("RESOURCE_OPERATION_UNSUPPORTED", registry.delete(ReSyncResourceCatalog.WORLD, "survival").errorCode());
    }

    @Test
    void worldGenResourcesUseValidatedDurableLifecycleOperations() {
        WorldGenProjectStorage worldGenStorage = new WorldGenProjectStorage(
            tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir), coordinator());
        WorldGenProject project = new WorldGenProject();
        project.setId("overworld-plus");
        project.getTerrainGraph().getNodes().put("height", new WorldGenNode("worldgen:output_height", 0, 0, Map.of("height", 72.0f)));
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(newStorage(), null, null, null, null, null, null, registry, ignored -> {
        });
        router.registerExternalLifecycle(worldGenStorage, null);

        assertTrue(registry.create(ReSyncResourceCatalog.WORLDGEN, project).success());
        assertTrue(registry.duplicate(ReSyncResourceCatalog.WORLDGEN, "overworld-plus", "overworld-copy").success());
        assertEquals(List.of("overworld-copy", "overworld-plus"), worldGenStorage.listProjectIds());
        assertTrue(registry.reload(ReSyncResourceCatalog.WORLDGEN, "overworld-plus").success());
        assertTrue(registry.delete(ReSyncResourceCatalog.WORLDGEN, "overworld-copy").success());
    }

    @Test
    void migrationPreservesDiskFoldersAndNormalizesAssetNames() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path command = assets.resolve("Blueprints").resolve("Commands").resolve("command__thisisacommandoriginally.json");
        Path gui = assets.resolve("GUIs").resolve("myCustomFolder").resolve("gui__myCustomGuiInMyCustomFolder.json");
        Path recipe = assets.resolve("Content").resolve("Recipes").resolve("Custom").resolve("recipe_definition__special.json");
        Files.createDirectories(command.getParent());
        Files.createDirectories(gui.getParent());
        Files.createDirectories(recipe.getParent());
        Files.writeString(command, """
            {
              "id": "thisisacommandoriginally",
              "version": 1,
              "nodes": {},
              "connections": []
            }
            """);
        Files.writeString(gui, """
            {
              "id": "myCustomGuiInMyCustomFolder",
              "title": "myCustomGuiInMyCustomFolder",
              "rows": 3,
              "elements": []
            }
            """);
        Files.writeString(recipe, """
            {
              "id": "special",
              "name": "special"
            }
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                { "path": "Blueprints", "parentPath": "", "name": "Blueprints", "sortOrder": 0 },
                { "path": "Blueprints/Flows", "parentPath": "Blueprints", "name": "Flows", "sortOrder": 0 },
                { "path": "GUIs", "parentPath": "", "name": "GUIs", "sortOrder": 2 }
              ],
              "resources": [
                { "type": "flow", "id": "thisisacommandoriginally", "displayName": "thisisacommandoriginally", "path": "Blueprints/Flows", "sortOrder": 0 },
                { "type": "gui", "id": "myCustomGuiInMyCustomFolder", "displayName": "myCustomGuiInMyCustomFolder", "path": "GUIs", "sortOrder": 1 },
                { "type": "recipe_definition", "id": "special", "displayName": "special", "path": "Content/Recipes", "sortOrder": 2 }
              ]
            }
            """);

        newCompatibilityStorage();

        Path migratedCommand = assets.resolve("Blueprints").resolve("Commands").resolve("thisisacommandoriginally.json");
        Path migratedGui = assets.resolve("GUIs").resolve("myCustomFolder").resolve("myCustomGuiInMyCustomFolder.json");
        Path migratedRecipe = assets.resolve("Content").resolve("Recipes").resolve("Custom").resolve("special.json");
        String project = Files.readString(assets.resolve("project.json"));

        assertFalse(Files.exists(command));
        assertFalse(Files.exists(gui));
        assertFalse(Files.exists(recipe));
        assertTrue(Files.exists(migratedCommand));
        assertTrue(Files.exists(migratedGui));
        assertTrue(Files.exists(migratedRecipe));
        assertTrue(Files.readString(migratedCommand).contains("\"resourceType\":\"command\""));
        assertTrue(Files.readString(migratedGui).contains("\"resourceType\":\"gui\""));
        assertTrue(Files.readString(migratedRecipe).contains("\"resourceType\":\"recipe_definition\""));
        assertTrue(hasProjectResource(project, "gui", "myCustomGuiInMyCustomFolder", "GUIs/myCustomFolder"));
        assertTrue(hasProjectResource(project, "recipe_definition", "special", "Content/Recipes/Custom"));
        assertTrue(hasProjectResource(project, "command", "thisisacommandoriginally", "Blueprints/Commands"));
        assertFalse(hasProjectResource(project, "flow", "thisisacommandoriginally"));
    }

    private static final class RecordingFlowPacketSender extends FlowPacketSender {
        private byte packetId;
        private String payload = "";
        private List<String> ids = List.of();

        private RecordingFlowPacketSender() {
            super(null, 0, Set.of());
        }

        @Override
        public void sendJsonResourceData(Session session, byte packetId, String json, String typeName) {
            this.packetId = packetId;
            this.payload = json;
        }

        @Override
        public void sendJsonResourceList(Session session, byte packetId, List<String> ids) {
            this.packetId = packetId;
            this.ids = List.copyOf(ids);
        }
    }

    @Test
    void migrationDoesNotOverwriteDifferentTypedIdOnlyAssetInSameFolder() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path folder = assets.resolve("Shared");
        Files.createDirectories(folder);
        Path existing = folder.resolve("main.json");
        Path legacy = folder.resolve("gui__main.json");
        Files.writeString(existing, """
            {
              "id": "main",
              "resourceType": "scoreboard",
              "title": "Board"
            }
            """);
        Files.writeString(legacy, """
            {
              "id": "main",
              "title": "Main",
              "rows": 3,
              "elements": []
            }
            """);

        newCompatibilityStorage();

        Path migrated = assets.resolve("GUIs").resolve("gui").resolve("main.json");
        String project = Files.readString(assets.resolve("project.json"));

        assertTrue(Files.exists(existing));
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(migrated));
        assertTrue(Files.readString(existing).contains("\"resourceType\": \"scoreboard\"") || Files.readString(existing).contains("\"resourceType\":\"scoreboard\""));
        assertTrue(Files.readString(migrated).contains("\"resourceType\":\"gui\""));
        assertTrue(hasProjectResource(project, "scoreboard", "main"));
        assertTrue(hasProjectResource(project, "gui", "main", "GUIs/gui"));
    }

    @Test
    void migrationPreservesDeclaredFlowTypeWhenPayloadContainsCommandStart() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path command = assets.resolve("Blueprints").resolve("Flows").resolve("restartcommand.json");
        Files.createDirectories(command.getParent());
        Files.writeString(command, """
            {
              "id": "restartcommand",
              "version": 1,
              "resourceType": "flow",
              "nodes": {
                "start": {
                  "type": "event.resync.command",
                  "version": 1,
                  "x": 0,
                  "y": 0,
                  "inputValues": {}
                }
              },
              "connections": []
            }
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                { "path": "Blueprints", "parentPath": "", "name": "Blueprints", "sortOrder": 0 },
                { "path": "Blueprints/Flows", "parentPath": "Blueprints", "name": "Flows", "sortOrder": 0 }
              ],
              "resources": [
                { "type": "flow", "id": "restartcommand", "displayName": "restartcommand", "path": "Blueprints/Flows", "sortOrder": 0 }
              ]
            }
            """);

        newCompatibilityStorage();

        String project = Files.readString(assets.resolve("project.json"));

        assertTrue(hasProjectResource(project, "flow", "restartcommand"));
        assertFalse(hasProjectResource(project, "command", "restartcommand"));
        assertEquals("flow", AssetFileFormat.readResourceType(command));

        AssetTransactionCoordinator original = coordinators.removeFirst();
        original.close();
        FlowStorage reopened = newStorage();

        assertEquals("flow", reopened.getGraph("flow", "restartcommand").getResourceType());
        assertEquals("flow", reopened.getGraphResourceType("restartcommand"));
    }

    @Test
    void migrationClassifiesUntypedLegacyCommandGraph() throws Exception {
        Path legacy = tempDir.resolve("flows").resolve("legacycommand.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, """
            {
              "id": "legacycommand",
              "version": 1,
              "nodes": {
                "start": {
                  "type": "event.resync.command",
                  "version": 1,
                  "x": 0,
                  "y": 0,
                  "inputValues": {}
                }
              },
              "connections": []
            }
            """);

        newCompatibilityStorage();

        Path migrated = tempDir.resolve("assets").resolve("Blueprints").resolve("Commands").resolve("legacycommand.json");
        String project = Files.readString(tempDir.resolve("assets").resolve("project.json"));

        assertTrue(Files.isRegularFile(migrated));
        assertEquals("command", AssetFileFormat.readResourceType(migrated));
        assertTrue(hasProjectResource(project, "command", "legacycommand"));
        assertFalse(hasProjectResource(project, "flow", "legacycommand"));
    }

    @Test
    void migrationPreservesLegacyCommandWhenTypedFlowSharesIdAndIsIdempotent() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path typedFlow = assets.resolve("Blueprints").resolve("Flows").resolve("test.json");
        Files.createDirectories(typedFlow.getParent());
        Files.writeString(typedFlow, AssetFileFormat.withResourceIdentity("""
            {
              "id": "test",
              "version": 1,
              "resourceType": "flow",
              "nodes": {},
              "connections": []
            }
            """, "flow", 1L, "00000000-0000-0000-0000-000000000001"));
        Path legacyCommand = tempDir.resolve("flows").resolve("test.json");
        Files.createDirectories(legacyCommand.getParent());
        Files.writeString(legacyCommand, """
            {
              "id": "test",
              "version": 1,
              "nodes": {
                "start": {
                  "type": "event.resync.command",
                  "version": 1,
                  "x": 0,
                  "y": 0,
                  "inputValues": {}
                }
              },
              "connections": []
            }
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                { "path": "Blueprints", "parentPath": "", "name": "Blueprints", "sortOrder": 0 },
                { "path": "Blueprints/Flows", "parentPath": "Blueprints", "name": "Flows", "sortOrder": 0 },
                { "path": "Blueprints/Commands", "parentPath": "Blueprints", "name": "Commands", "sortOrder": 1 }
              ],
              "resources": [
                { "type": "flow", "id": "test", "displayName": "test", "path": "Blueprints/Flows", "sortOrder": 0 }
              ]
            }
            """);

        newCompatibilityStorage();

        Path command = assets.resolve("Blueprints").resolve("Commands").resolve("test.json");
        assertTrue(Files.isRegularFile(typedFlow));
        assertTrue(Files.isRegularFile(command));
        assertFalse(Files.exists(legacyCommand));
        assertEquals("flow", AssetFileFormat.readResourceType(typedFlow));
        assertEquals("command", AssetFileFormat.readResourceType(command));
        String firstProject = Files.readString(assets.resolve("project.json"));
        assertEquals(1L, countProjectResource(firstProject, "flow", "test"));
        assertEquals(1L, countProjectResource(firstProject, "command", "test"));

        coordinators.removeFirst().close();
        FlowStorage reopened = newCompatibilityStorage();

        assertEquals("flow", reopened.getGraph("flow", "test").getResourceType());
        assertEquals("command", reopened.getGraph("command", "test").getResourceType());
        assertTrue(Files.isRegularFile(typedFlow));
        assertTrue(Files.isRegularFile(command));
        String secondProject = Files.readString(assets.resolve("project.json"));
        assertEquals(1L, countProjectResource(secondProject, "flow", "test"));
        assertEquals(1L, countProjectResource(secondProject, "command", "test"));
    }

    @Test
    void migrationQuarantinesConflictingLegacySourceInsteadOfDeletingIt() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path typedFlow = assets.resolve("Blueprints").resolve("Flows").resolve("conflict.json");
        Files.createDirectories(typedFlow.getParent());
        Files.writeString(typedFlow, AssetFileFormat.withResourceIdentity("""
            {
              "id": "conflict",
              "version": 1,
              "resourceType": "flow",
              "nodes": {},
              "connections": []
            }
            """, "flow", 1L, "00000000-0000-0000-0000-000000000001"));
        Path legacy = tempDir.resolve("flows").resolve("conflict.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, """
            {
              "id": "conflict",
              "version": 1,
              "enabled": false,
              "nodes": {
              },
              "connections": []
            }
            """);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                { "path": "Blueprints", "parentPath": "", "name": "Blueprints", "sortOrder": 0 },
                { "path": "Blueprints/Flows", "parentPath": "Blueprints", "name": "Flows", "sortOrder": 0 }
              ],
              "resources": [
                { "type": "flow", "id": "conflict", "displayName": "conflict", "path": "Blueprints/Flows", "sortOrder": 0 }
              ]
            }
            """);

        newCompatibilityStorage();

        assertTrue(Files.isRegularFile(typedFlow));
        assertFalse(Files.exists(legacy));
        Path quarantineRoot = tempDir.resolve("legacy-quarantine").resolve("migration").resolve("conflicts");
        try (var paths = Files.walk(quarantineRoot)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("conflict.json")));
        }
    }

    @Test
    void migrationKeepsRestoredLegacyAssetsWithSharedIds() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path legacySourceAssets = tempDir.resolve("legacy-source-assets");
        writeLegacyAsset(legacySourceAssets.resolve("Blueprints").resolve("Commands").resolve("command__main.json"), """
            {
              "id": "main",
              "version": 1,
              "nodes": {},
              "connections": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Blueprints").resolve("Commands").resolve("command__quest.json"), """
            {
              "id": "quest",
              "version": 1,
              "nodes": {},
              "connections": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Blueprints").resolve("Flows").resolve("flow__testt.json"), """
            {
              "id": "testt",
              "version": 1,
              "nodes": {},
              "connections": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("GUIs").resolve("gui__main.json"), """
            {
              "id": "main",
              "title": "main",
              "rows": 3,
              "elements": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("GUIs").resolve("gui__berger.json"), """
            {
              "id": "berger",
              "title": "berger",
              "rows": 3,
              "elements": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Customization").resolve("Tabs").resolve("tab__main.json"), """
            {
              "id": "main",
              "header": "",
              "footer": ""
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Customization").resolve("Scoreboards").resolve("scoreboard__test.json"), """
            {
              "id": "test",
              "title": "test",
              "lines": []
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Content").resolve("Blocks").resolve("custom_content__ejectingSofa.json"), """
            {
              "id": "ejectingSofa",
              "type": "block"
            }
            """);
        writeLegacyAsset(legacySourceAssets.resolve("Content").resolve("Recipes").resolve("recipe_definition__camp.json"), """
            {
              "id": "camp",
              "name": "camp"
            }
            """);
        copyTree(legacySourceAssets, assets);
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId": "project",
              "folders": [
                { "path": "Blueprints", "parentPath": "", "name": "Blueprints", "sortOrder": 0 },
                { "path": "Blueprints/Commands", "parentPath": "Blueprints", "name": "Commands", "sortOrder": 2 },
                { "path": "Blueprints/Flows", "parentPath": "Blueprints", "name": "Flows", "sortOrder": 0 },
                { "path": "GUIs", "parentPath": "", "name": "GUIs", "sortOrder": 2 },
                { "path": "Customization/Tabs", "parentPath": "Customization", "name": "Tabs", "sortOrder": 4 },
                { "path": "Customization/Scoreboards", "parentPath": "Customization", "name": "Scoreboards", "sortOrder": 3 },
                { "path": "Content/Blocks", "parentPath": "Content", "name": "Blocks", "sortOrder": 2 },
                { "path": "Content/Recipes", "parentPath": "Content", "name": "Recipes", "sortOrder": 3 }
              ],
              "resources": [
                { "type": "command", "id": "main", "displayName": "main", "path": "Blueprints/Commands", "sortOrder": 0 },
                { "type": "command", "id": "quest", "displayName": "quest", "path": "Blueprints/Commands", "sortOrder": 1 },
                { "type": "flow", "id": "testt", "displayName": "testt", "path": "Blueprints/Flows", "sortOrder": 2 },
                { "type": "gui", "id": "main", "displayName": "main", "path": "GUIs", "sortOrder": 3 },
                { "type": "gui", "id": "berger", "displayName": "berger", "path": "GUIs", "sortOrder": 4 },
                { "type": "tab", "id": "main", "displayName": "main", "path": "Customization/Tabs", "sortOrder": 5 },
                { "type": "scoreboard", "id": "test", "displayName": "test", "path": "Customization/Scoreboards", "sortOrder": 6 },
                { "type": "custom_content", "id": "ejectingSofa", "displayName": "ejectingSofa", "path": "Content/Blocks", "sortOrder": 7 },
                { "type": "recipe_definition", "id": "camp", "displayName": "camp", "path": "Content/Recipes", "sortOrder": 8 }
              ]
            }
            """);

        newCompatibilityStorage();

        assertTrue(Files.exists(assets.resolve("Blueprints").resolve("Commands").resolve("main.json")));
        assertTrue(Files.exists(assets.resolve("Blueprints").resolve("Commands").resolve("quest.json")));
        assertTrue(Files.exists(assets.resolve("Blueprints").resolve("Flows").resolve("testt.json")));
        assertTrue(Files.exists(assets.resolve("GUIs").resolve("main.json")));
        assertTrue(Files.exists(assets.resolve("GUIs").resolve("berger.json")));
        assertTrue(Files.exists(assets.resolve("Customization").resolve("Tabs").resolve("main.json")));
        assertTrue(Files.exists(assets.resolve("Customization").resolve("Scoreboards").resolve("test.json")));
        assertTrue(Files.exists(assets.resolve("Content").resolve("Blocks").resolve("ejectingSofa.json")));
        assertTrue(Files.exists(assets.resolve("Content").resolve("Recipes").resolve("camp.json")));
        assertTrue(Files.readString(assets.resolve("Blueprints").resolve("Flows").resolve("testt.json")).contains("\"resourceType\":\"flow\""));

    }

    private void writeLegacyAsset(Path path, String json) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private FlowStorage newCompatibilityStorage() {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.compatibility(tempDir), coordinator());
    }

    private FlowStorage newStorage() {
        return new FlowStorage(tempDir.toFile(), coordinator());
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            Path assets = tempDir.resolve("assets");
            AssetTransactionCoordinator coordinator;
            if (Files.isRegularFile(assets.resolve(".asset-coordinator/genesis.json")) || !Files.exists(assets)
                || assetFiles(assets).isEmpty() && !Files.isRegularFile(assets.resolve("project.json"))) {
                coordinator = AssetTransactionCoordinator.open(assets, new Gson());
            } else {
                coordinator = AssetTransactionCoordinator.adoptExisting(assets, new Gson(), adoptionInventory(assets));
            }
            coordinators.add(coordinator);
            return coordinator;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }

    private AssetTransactionCoordinator.AdoptionInventory adoptionInventory(Path assets) throws Exception {
        String project = Files.isRegularFile(assets.resolve("project.json"))
            ? Files.readString(assets.resolve("project.json")) : "{}";
        JsonObject projectObject = new Gson().fromJson(project, JsonObject.class);
        List<AssetTransactionCoordinator.AdoptedAsset> adopted = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Path file : assetFiles(assets)) {
            byte[] content = Files.readAllBytes(file);
            Path relative = assets.relativize(file);
            JsonObject object = parseAssetObject(content);
            boolean tombstone = relative.getNameCount() >= 3 && ".tombstones".equals(relative.getName(0).toString());
            String candidateId = object != null && object.has("id") ? object.get("id").getAsString()
                : AssetFileFormat.idFromIdOnlyFileName(relative.getFileName().toString());
            String type = tombstone ? "tombstone:" + relative.getName(1)
                : adoptedResourceType(projectObject, relative, candidateId, object);
            String id = !"legacy".equals(type) ? candidateId : relative.toString().replace('\\', '/');
            String canonical = new AssetTransactionCoordinator.AssetKey(type, id).canonical();
            if (!keys.add(canonical)) {
                type = "legacy-duplicate:" + type;
                id = relative.toString().replace('\\', '/');
                keys.add(new AssetTransactionCoordinator.AssetKey(type, id).canonical());
            }
            long revision = object != null && object.has("assetRevision") ? object.get("assetRevision").getAsLong()
                : object != null && object.has("revision") ? object.get("revision").getAsLong() : 0L;
            String mutation = object != null && object.has("assetMutationId") ? object.get("assetMutationId").getAsString()
                : object != null && object.has("mutationId") ? object.get("mutationId").getAsString() : "";
            AssetTransactionCoordinator.AssetMutationId mutationId = new AssetTransactionCoordinator.AssetMutationId(
                mutation.isBlank() ? UUID.nameUUIDFromBytes(relative.toString().getBytes(StandardCharsets.UTF_8)).toString() : mutation);
            adopted.add(new AssetTransactionCoordinator.AdoptedAsset(
                new AssetTransactionCoordinator.AssetKey(type, id), relative,
                new AssetTransactionCoordinator.Live(revision, StorageSafety.sha256(content)), mutationId, content, null));
        }
        List<AssetTransactionCoordinator.AdoptionEvidence> evidence = evidenceFiles(assets).stream().map(file -> {
            try {
                byte[] content = Files.readAllBytes(file);
                return new AssetTransactionCoordinator.AdoptionEvidence(
                    assets.relativize(file).toString().replace('\\', '/'), StorageSafety.sha256(content), content.length);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to bind asset adoption evidence", exception);
            }
        }).toList();
        return new AssetTransactionCoordinator.AdoptionInventory(
            "flow-storage-asset-migration-test", project, adopted, List.of(), evidence);
    }

    private JsonObject parseAssetObject(byte[] content) {
        try {
            var parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String adoptedResourceType(JsonObject project, Path relative, String id, JsonObject object) {
        if (object != null && object.has("resourceType")) {
            String declared = object.get("resourceType").getAsString();
            if (!declared.isBlank()) {
                return declared;
            }
        }
        String projectType = projectResourceType(project, relative, id);
        if (!"legacy".equals(projectType)) {
            return projectType;
        }
        String fileName = relative.getFileName().toString();
        int separator = fileName.indexOf("__");
        if (separator > 0) {
            return fileName.substring(0, separator);
        }
        if (object != null && object.has("function") && object.get("function").isJsonPrimitive()
            && object.get("function").getAsBoolean()) {
            return "function";
        }
        if (isCommandGraph(object)) {
            return "command";
        }
        return "legacy";
    }

    private boolean isCommandGraph(JsonObject object) {
        if (object == null || !object.has("nodes") || !object.get("nodes").isJsonObject()) {
            return false;
        }
        return object.getAsJsonObject("nodes").entrySet().stream()
            .map(Map.Entry::getValue)
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject().get("type"))
            .anyMatch(type -> type != null && type.isJsonPrimitive() && "event.resync.command".equals(type.getAsString()));
    }

    private String projectResourceType(JsonObject project, Path relative, String id) {
        if (project == null || !project.has("resources") || !project.get("resources").isJsonArray()) {
            return "legacy";
        }
        String parent = relative.getParent() != null ? relative.getParent().toString().replace('\\', '/') : "";
        List<JsonObject> entries = new ArrayList<>();
        for (var element : project.getAsJsonArray("resources")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            if (!resource.has("id") || !id.equals(resource.get("id").getAsString()) || !resource.has("type")) {
                continue;
            }
            entries.add(resource);
        }
        List<JsonObject> exactPath = entries.stream()
            .filter(resource -> resource.has("path") && parent.equals(resource.get("path").getAsString().replace('\\', '/')))
            .toList();
        List<JsonObject> candidates = exactPath.isEmpty()
            ? entries.stream().filter(resource -> !resource.has("path")).toList() : exactPath;
        String match = "";
        for (JsonObject resource : candidates) {
            String candidate = resource.get("type").getAsString();
            if (!candidate.equals(match) && !match.isBlank()) {
                return "legacy";
            }
            match = candidate;
        }
        return match.isBlank() ? "legacy" : match;
    }

    private List<Path> assetFiles(Path assets) throws Exception {
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (var paths = Files.walk(assets)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> !path.equals(assets.resolve("project.json")))
                .filter(path -> !path.startsWith(assets.resolve(".asset-coordinator")))
                .filter(path -> !path.startsWith(assets.resolve(".transactions")))
                .filter(path -> !path.startsWith(assets.resolve(".snapshots")))
                .filter(path -> !path.startsWith(assets.resolve(".quarantine")))
                .filter(path -> !path.startsWith(assets.resolve(".migrations")))
                .sorted()
                .toList();
        }
    }

    private List<Path> evidenceFiles(Path assets) throws Exception {
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (var paths = Files.walk(assets)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.startsWith(assets.resolve(".tombstones"))
                    || path.startsWith(assets.resolve(".quarantine"))
                    || path.startsWith(assets.resolve(".migrations")))
                .sorted()
                .toList();
        }
    }

    private boolean hasProjectResourceId(String json, String id) {
        JsonObject project = JsonParser.parseString(json).getAsJsonObject();
        return project.has("resources") && project.getAsJsonArray("resources").asList().stream()
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject())
            .anyMatch(resource -> resource.has("id") && id.equals(resource.get("id").getAsString()));
    }

    private boolean hasProjectResource(String json, String type, String id) {
        return hasProjectResource(json, type, id, null);
    }

    private long countProjectResource(String json, String type, String id) {
        JsonObject project = JsonParser.parseString(json).getAsJsonObject();
        return project.has("resources") ? project.getAsJsonArray("resources").asList().stream()
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject())
            .filter(resource -> resource.has("type") && type.equals(resource.get("type").getAsString())
                && resource.has("id") && id.equals(resource.get("id").getAsString()))
            .count() : 0L;
    }

    private boolean hasProjectResource(String json, String type, String id, String path) {
        JsonObject project = JsonParser.parseString(json).getAsJsonObject();
        return project.has("resources") && project.getAsJsonArray("resources").asList().stream()
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject())
            .anyMatch(resource -> resource.has("type") && type.equals(resource.get("type").getAsString())
                && resource.has("id") && id.equals(resource.get("id").getAsString())
                && (path == null || resource.has("path") && path.equals(resource.get("path").getAsString())));
    }

    private String id(JsonObject value) {
        if (value == null || !value.has("id") || value.get("id").isJsonNull()) {
            return "";
        }
        return value.get("id").getAsString();
    }

}
