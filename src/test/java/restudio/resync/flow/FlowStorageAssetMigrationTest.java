package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageAssetMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void migrationPrunesMissingFileBackedResourcesButKeepsRuntimeWorldEntries() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path items = assets.resolve("Content").resolve("Items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("present.json"), """
            {"resourceType":"custom_content","id":"present","type":"item"}
            """);
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

        FlowStorage storage = new FlowStorage(tempDir.toFile());

        String project = Files.readString(assets.resolve("project.json"));
        assertTrue(project.contains("\"type\":\"custom_content\",\"id\":\"present\""));
        assertFalse(project.contains("\"id\":\"missing\""));
        assertFalse(project.contains("\"id\":\"Secondary\""));
        assertTrue(project.contains("\"type\":\"world\",\"id\":\"world\""));

        Gson gson = new Gson();
        JsonAssetStore<JsonObject> store = new JsonAssetStore<>(assets, tempDir.resolve("custom-content"), ReSyncResourceCatalog.CUSTOM_CONTENT,
            "Content/Items", json -> gson.fromJson(json, JsonObject.class), gson::toJson, this::id);
        store.delete("present");

        assertFalse(storage.getProjectMetadata("project").contains("\"id\": \"present\""));
    }

    @Test
    void graphStoragePreservesFlowFunctionAndCommandResourceTypes() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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
    void graphReclassificationMovesOneStableAssetWithoutLeavingShadowCopies() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"convertible","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        graph.setFunction(false);
        graph.getNodes().put("start", FlowSerializer.deserialize("""
            {"id":"temporary","version":2,"nodes":{"start":{"type":"event.resync.command","version":1,"x":0,"y":0,"inputValues":{}}},"connections":[],"localVariables":[]}
            """).getNodes().get("start"));

        storage.saveGraph(graph);

        assertEquals("command", storage.getGraphResourceType("convertible"));
        try (var paths = Files.walk(tempDir.resolve("assets"))) {
            List<Path> matching = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("convertible.json"))
                .toList();
            assertEquals(1, matching.size());
            assertTrue(Files.readString(matching.getFirst()).contains("\"resourceType\":\"command\""));
        }
        String project = Files.readString(tempDir.resolve("assets").resolve("project.json"));
        assertTrue(project.contains("\"type\":\"command\",\"id\":\"convertible\""));
        assertFalse(project.contains("\"type\":\"function\",\"id\":\"convertible\""));
    }

    @Test
    void graphResourcesUseTheManagedLifecycleRegistry() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(new FlowStorage(tempDir.toFile()), null, null, null, null, null, null, registry, ignored -> {
        });
        router.registerExternalLifecycle(null, service);

        assertEquals("survival", registry.discover(ReSyncResourceCatalog.WORLD, "surv").value().getFirst().id());
        assertEquals("survival", ((WorldRegistryEntry) registry.get(ReSyncResourceCatalog.WORLD, "SURVIVAL").value()).getWorldName());
        assertFalse(registry.delete(ReSyncResourceCatalog.WORLD, "survival").success());
        assertEquals("RESOURCE_OPERATION_UNSUPPORTED", registry.delete(ReSyncResourceCatalog.WORLD, "survival").errorCode());
    }

    @Test
    void worldGenResourcesUseValidatedDurableLifecycleOperations() {
        WorldGenProjectStorage worldGenStorage = new WorldGenProjectStorage(tempDir.toFile());
        WorldGenProject project = new WorldGenProject();
        project.setId("overworld-plus");
        project.getTerrainGraph().getNodes().put("height", new WorldGenNode("output_height", 0, 0, Map.of("height", 72.0f)));
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourcePacketRouter router = new FlowResourcePacketRouter(new FlowStorage(tempDir.toFile()), null, null, null, null, null, null, registry, ignored -> {
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

        migrateJsonResourceStores(assets);
        new FlowStorage(tempDir.toFile());
        migrateGenericAssetStore(assets, ReSyncResourceCatalog.CUSTOM_CONTENT, "custom-content", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT));
        migrateGenericAssetStore(assets, ReSyncResourceCatalog.WORLDGEN, "worldgen-projects", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN));

        Path migratedCommand = assets.resolve("Blueprints").resolve("Commands").resolve("thisisacommandoriginally.json");
        Path migratedGui = assets.resolve("GUIs").resolve("myCustomFolder").resolve("myCustomGuiInMyCustomFolder.json");
        Path migratedRecipe = assets.resolve("Content").resolve("Recipes").resolve("Custom").resolve("special.json");
        String project = Files.readString(assets.resolve("project.json"));

        assertTrue(Files.exists(command));
        assertTrue(Files.exists(gui));
        assertTrue(Files.exists(recipe));
        assertTrue(Files.exists(migratedCommand));
        assertTrue(Files.exists(migratedGui));
        assertTrue(Files.exists(migratedRecipe));
        assertTrue(Files.readString(migratedCommand).contains("\"resourceType\":\"command\""));
        assertTrue(Files.readString(migratedGui).contains("\"resourceType\":\"gui\""));
        assertTrue(Files.readString(migratedRecipe).contains("\"resourceType\":\"recipe_definition\""));
        assertTrue(project.contains("\"path\":\"GUIs/myCustomFolder\""));
        assertTrue(project.contains("\"path\":\"Content/Recipes/Custom\""));
        assertTrue(project.contains("\"type\":\"command\",\"id\":\"thisisacommandoriginally\""));
        assertTrue(project.contains("\"path\":\"Blueprints/Commands\""));
        assertFalse(project.contains("\"type\":\"flow\",\"id\":\"thisisacommandoriginally\""));
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

        new FlowStorage(tempDir.toFile());

        Path migrated = assets.resolve("GUIs").resolve("gui").resolve("main.json");
        String project = Files.readString(assets.resolve("project.json"));

        assertTrue(Files.exists(existing));
        assertTrue(Files.exists(legacy));
        assertTrue(Files.exists(migrated));
        assertTrue(Files.readString(existing).contains("\"resourceType\": \"scoreboard\"") || Files.readString(existing).contains("\"resourceType\":\"scoreboard\""));
        assertTrue(Files.readString(migrated).contains("\"resourceType\":\"gui\""));
        assertTrue(project.contains("\"type\":\"scoreboard\",\"id\":\"main\""));
        assertTrue(project.contains("\"type\":\"gui\",\"id\":\"main\""));
        assertTrue(project.contains("\"path\":\"GUIs/gui\""));
    }

    @Test
    void migrationClassifiesCommandGraphsWithoutTriggerFile() throws Exception {
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

        new FlowStorage(tempDir.toFile());

        String project = Files.readString(assets.resolve("project.json"));

        assertTrue(project.contains("\"type\":\"command\",\"id\":\"restartcommand\""));
        assertFalse(project.contains("\"type\":\"flow\",\"id\":\"restartcommand\""));
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

        new FlowStorage(tempDir.toFile());
        migrateJsonResourceStores(assets);
        migrateGenericAssetStore(assets, ReSyncResourceCatalog.CUSTOM_CONTENT, "custom-content", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT));
        migrateGenericAssetStore(assets, ReSyncResourceCatalog.WORLDGEN, "worldgen-projects", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN));

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

    private void migrateJsonResourceStores(Path assets) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        for (String type : ReSyncResourceCatalog.jsonStorageTypes()) {
            ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
            JsonAssetStore<JsonObject> store = new JsonAssetStore<>(
                    assets,
                    tempDir.resolve(legacyFolder(type)),
                    type,
                    resource.defaultFolder(),
                    json -> gson.fromJson(json, JsonObject.class),
                    gson::toJson,
                    this::id,
                    value -> folder(value, resource.defaultFolder())
            );
            store.migrateLegacyAssets();
        }
    }

    private void migrateGenericAssetStore(Path assets, String type, String legacyFolder, String defaultFolder) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonAssetStore<JsonObject> store = new JsonAssetStore<>(
                assets,
                tempDir.resolve(legacyFolder),
                type,
                defaultFolder,
                json -> gson.fromJson(json, JsonObject.class),
                gson::toJson,
                this::id
        );
        store.migrateLegacyAssets();
    }

    private String id(JsonObject value) {
        if (value == null || !value.has("id") || value.get("id").isJsonNull()) {
            return "";
        }
        return value.get("id").getAsString();
    }

    private String folder(JsonObject value, String fallback) {
        if (value == null || !value.has("folder") || value.get("folder").isJsonNull()) {
            return fallback;
        }
        String folder = value.get("folder").getAsString();
        return folder == null || folder.isBlank() ? fallback : folder;
    }

    private String legacyFolder(String type) {
        return switch (type) {
            case ReSyncResourceCatalog.CHAT -> "chat";
            case ReSyncResourceCatalog.MOTD_PROFILE -> "motd-profiles";
            case ReSyncResourceCatalog.MESSAGE_RULE -> "message-rules";
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> "recipes";
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> "text-templates";
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "advancement-trees";
            case ReSyncResourceCatalog.DIALOG -> "dialogs";
            default -> type;
        };
    }
}
