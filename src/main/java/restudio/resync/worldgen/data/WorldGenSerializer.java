package restudio.resync.worldgen.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.resources.JsonAssetStore;

import java.util.Set;

public class WorldGenSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> OWNED_PROJECT_FIELDS = Set.of(
        "id", "version", "terrainGraph", "terrainGraph.id", "terrainGraph.version", "terrainGraph.nodes", "terrainGraph.nodes[].type",
        "terrainGraph.nodes[].x", "terrainGraph.nodes[].y", "terrainGraph.nodes[].inputValues", "terrainGraph.connections",
        "terrainGraph.connections[].sourceNodeId", "terrainGraph.connections[].sourcePin", "terrainGraph.connections[].targetNodeId",
        "terrainGraph.connections[].targetPin", "biomeGraph", "biomeGraph.id", "biomeGraph.version", "biomeGraph.nodes",
        "biomeGraph.nodes[].type", "biomeGraph.nodes[].x", "biomeGraph.nodes[].y", "biomeGraph.nodes[].inputValues",
        "biomeGraph.connections", "biomeGraph.connections[].sourceNodeId", "biomeGraph.connections[].sourcePin",
        "biomeGraph.connections[].targetNodeId", "biomeGraph.connections[].targetPin", "surfaceGraph", "surfaceGraph.id",
        "surfaceGraph.version", "surfaceGraph.nodes", "surfaceGraph.nodes[].type", "surfaceGraph.nodes[].x",
        "surfaceGraph.nodes[].y", "surfaceGraph.nodes[].inputValues", "surfaceGraph.connections",
        "surfaceGraph.connections[].sourceNodeId", "surfaceGraph.connections[].sourcePin", "surfaceGraph.connections[].targetNodeId",
        "surfaceGraph.connections[].targetPin", "caveGraph", "caveGraph.id", "caveGraph.version", "caveGraph.nodes",
        "caveGraph.nodes[].type", "caveGraph.nodes[].x", "caveGraph.nodes[].y", "caveGraph.nodes[].inputValues",
        "caveGraph.connections", "caveGraph.connections[].sourceNodeId", "caveGraph.connections[].sourcePin",
        "caveGraph.connections[].targetNodeId", "caveGraph.connections[].targetPin", "featureGraph", "featureGraph.id",
        "featureGraph.version", "featureGraph.nodes", "featureGraph.nodes[].type", "featureGraph.nodes[].x",
        "featureGraph.nodes[].y", "featureGraph.nodes[].inputValues", "featureGraph.connections",
        "featureGraph.connections[].sourceNodeId", "featureGraph.connections[].sourcePin", "featureGraph.connections[].targetNodeId",
        "featureGraph.connections[].targetPin", "structureGraph", "structureGraph.id", "structureGraph.version",
        "structureGraph.nodes", "structureGraph.nodes[].type", "structureGraph.nodes[].x", "structureGraph.nodes[].y",
        "structureGraph.nodes[].inputValues", "structureGraph.connections", "structureGraph.connections[].sourceNodeId",
        "structureGraph.connections[].sourcePin", "structureGraph.connections[].targetNodeId", "structureGraph.connections[].targetPin",
        "spawnGraph", "spawnGraph.id", "spawnGraph.version", "spawnGraph.nodes", "spawnGraph.nodes[].type", "spawnGraph.nodes[].x",
        "spawnGraph.nodes[].y", "spawnGraph.nodes[].inputValues", "spawnGraph.connections", "spawnGraph.connections[].sourceNodeId",
        "spawnGraph.connections[].sourcePin", "spawnGraph.connections[].targetNodeId", "spawnGraph.connections[].targetPin",
        "settings", "settings.seedPolicy", "settings.minY", "settings.maxY", "settings.seaLevel", "settings.defaultBlock",
        "settings.defaultFluid", "settings.datapackNamespace", "settings.generatorBackend", "settings.generationMode",
        "settings.targetVersion", "settings.worldPreset", "settings.terrainTemplate", "settings.vanillaBiomesEnabled",
        "settings.vanillaFeaturesEnabled", "settings.vanillaStructuresEnabled", "settings.vanillaSpawnsEnabled",
        "settings.vanillaStructureTerrainSafety", "settings.vanillaStructureSampleRadius", "settings.vanillaStructureMaxHeightDelta",
        "settings.biomeVanillaFeatureOverrides", "settings.previewEnvironment", "settings.activePreviewPlayer", "biomeProfiles",
        "biomeProfiles[].id", "biomeProfiles[].displayName", "biomeProfiles[].mode", "biomeProfiles[].vanillaBaseBiome",
        "biomeProfiles[].temperature", "biomeProfiles[].humidity", "biomeProfiles[].continentalness", "biomeProfiles[].erosion",
        "biomeProfiles[].weirdness", "biomeProfiles[].surfaceReference", "biomeProfiles[].keepVanillaFeatures",
        "biomeProfiles[].keepVanillaStructures", "biomeProfiles[].keepVanillaSpawns", "biomeProfiles[].spawnRules",
        "biomeProfiles[].spawnRules[].entityType", "biomeProfiles[].spawnRules[].weight", "biomeProfiles[].spawnRules[].minGroup",
        "biomeProfiles[].spawnRules[].maxGroup", "biomeProfiles[].spawnRules[].category", "biomeProfiles[].spawnRules[].biomeFilters",
        "biomeProfiles[].spawnRules[].minY", "biomeProfiles[].spawnRules[].maxY", "biomeProfiles[].spawnRules[].blockBelow",
        "biomeProfiles[].spawnRules[].minLight", "biomeProfiles[].spawnRules[].maxLight", "biomeProfiles[].spawnRules[].time",
        "biomeProfiles[].spawnRules[].weather");

    public static String serialize(WorldGenGraph graph) {
        return GSON.toJson(graph);
    }

    public static WorldGenGraph deserialize(String json) {
        WorldGenGraph graph = GSON.fromJson(json, WorldGenGraph.class);
        if (graph != null) {
            graph.rebuildIndices();
        }
        return graph;
    }

    public static String serializeProject(WorldGenProject project) {
        if (project == null) {
            return GSON.toJson(null);
        }
        JsonObject serialized = ownedProject(project);
        JsonObject opaque = project.opaquePayload();
        if (opaque != null) {
            serialized = JsonAssetStore.mergePayload(opaque, serialized, OWNED_PROJECT_FIELDS);
        }
        return GSON.toJson(serialized);
    }

    public static String serializeProjectOwned(WorldGenProject project) {
        return GSON.toJson(project);
    }

    public static JsonObject ownedProjectPayload(JsonObject payload) {
        JsonObject owned = new JsonObject();
        if (payload == null) {
            return owned;
        }
        payload.entrySet().stream()
            .filter(entry -> OWNED_PROJECT_FIELDS.contains(entry.getKey()))
            .forEach(entry -> owned.add(entry.getKey(), entry.getValue().deepCopy()));
        return owned;
    }

    public static JsonObject mergeProjectPayload(WorldGenProject project, JsonObject existing, JsonObject serialized) {
        JsonObject opaque = project == null ? null : project.opaquePayload();
        if ((existing == null || existing.entrySet().isEmpty()) && opaque != null) {
            return JsonAssetStore.mergePayload(opaque, serialized, OWNED_PROJECT_FIELDS);
        }
        return JsonAssetStore.mergePayload(existing, serialized, OWNED_PROJECT_FIELDS);
    }

    public static WorldGenProject deserializeProject(String json) {
        WorldGenProject project = GSON.fromJson(json, WorldGenProject.class);
        if (project != null) {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                project.setOpaquePayload(logicalPayload(parsed.getAsJsonObject()));
            }
            project.rebuildIndices();
        }
        return project;
    }

    private static JsonObject ownedProject(WorldGenProject project) {
        return GSON.toJsonTree(project).getAsJsonObject();
    }

    private static JsonObject logicalPayload(JsonObject source) {
        return JsonAssetStore.logicalPayload(source);
    }
}
