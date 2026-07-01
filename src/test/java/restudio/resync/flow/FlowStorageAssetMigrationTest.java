package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageAssetMigrationTest {
    @TempDir
    Path tempDir;

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
