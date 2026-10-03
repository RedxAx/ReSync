package restudio.resync.worldgen.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.resources.JsonAssetStore;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenSerializerOwnershipTest {
    private static final List<String> GRAPH_FIELDS = List.of(
        "terrainGraph", "biomeGraph", "surfaceGraph", "caveGraph", "featureGraph", "structureGraph", "spawnGraph");

    @Test
    void preservesUnknownDataInEveryWorldGenGraphWhileReplacingKnownState() {
        WorldGenProject source = new WorldGenProject();
        source.setId("opaque");
        JsonObject raw = JsonParser.parseString(WorldGenSerializer.serializeProject(source)).getAsJsonObject();
        for (String graphField : GRAPH_FIELDS) {
            JsonObject graph = raw.getAsJsonObject(graphField);
            graph.addProperty("futureGraph", graphField);

            JsonObject keptNode = new JsonObject();
            keptNode.addProperty("type", "known-old");
            keptNode.addProperty("x", 1.0D);
            keptNode.addProperty("y", 2.0D);
            keptNode.add("inputValues", new JsonObject());
            keptNode.addProperty("futureNode", graphField);
            graph.getAsJsonObject("nodes").add("kept", keptNode);

            JsonObject removedNode = keptNode.deepCopy();
            removedNode.addProperty("futureNode", "removed");
            graph.getAsJsonObject("nodes").add("removed", removedNode);

            JsonObject connection = new JsonObject();
            connection.addProperty("sourceNodeId", "kept");
            connection.addProperty("sourcePin", "old");
            connection.addProperty("targetNodeId", "kept");
            connection.addProperty("targetPin", "input");
            connection.addProperty("futureConnection", graphField);
            graph.getAsJsonArray("connections").add(connection);
        }

        WorldGenProject decoded = WorldGenSerializer.deserializeProject(raw.toString());
        for (WorldGenGraph graph : graphs(decoded)) {
            graph.setVersion(7);
            graph.getNodes().get("kept").setX(8.0D);
            graph.getNodes().remove("removed");
            graph.getConnections().getFirst().setSourcePin("changed");
        }

        JsonObject persisted = JsonParser.parseString(WorldGenSerializer.serializeProject(decoded)).getAsJsonObject();
        for (String graphField : GRAPH_FIELDS) {
            JsonObject graph = persisted.getAsJsonObject(graphField);
            assertEquals(graphField, graph.get("futureGraph").getAsString());
            assertEquals(7, graph.get("version").getAsInt());
            assertEquals(8.0D, graph.getAsJsonObject("nodes").getAsJsonObject("kept").get("x").getAsDouble());
            assertEquals(graphField, graph.getAsJsonObject("nodes").getAsJsonObject("kept").get("futureNode").getAsString());
            assertFalse(graph.getAsJsonObject("nodes").has("removed"));
            assertEquals("changed", graph.getAsJsonArray("connections").get(0).getAsJsonObject().get("sourcePin").getAsString());
            assertEquals(graphField, graph.getAsJsonArray("connections").get(0).getAsJsonObject()
                .get("futureConnection").getAsString());
        }
    }

    @Test
    void fullObjectOwnershipIsScopedToTheMatchingPath() {
        JsonObject existing = JsonParser.parseString("""
            {
              "graphA": {"version": 1, "future": "keep"},
              "graphB": {"future": "keep", "known": {"old": true}}
            }
            """).getAsJsonObject();
        JsonObject serialized = JsonParser.parseString("""
            {
              "graphA": {"version": 2},
              "graphB": {"known": {"new": true}}
            }
            """).getAsJsonObject();

        JsonObject merged = JsonAssetStore.mergePayload(existing, serialized,
            Set.of("graphA", "graphA.version", "graphB", "graphB.known"));

        assertEquals(2, merged.getAsJsonObject("graphA").get("version").getAsInt());
        assertTrue(merged.getAsJsonObject("graphA").has("future"));
        assertEquals("keep", merged.getAsJsonObject("graphB").get("future").getAsString());
        assertTrue(merged.getAsJsonObject("graphB").getAsJsonObject("known").get("new").getAsBoolean());
        assertFalse(merged.getAsJsonObject("graphB").getAsJsonObject("known").has("old"));
    }

    private List<WorldGenGraph> graphs(WorldGenProject project) {
        return List.of(project.getTerrainGraph(), project.getBiomeGraph(), project.getSurfaceGraph(), project.getCaveGraph(),
            project.getFeatureGraph(), project.getStructureGraph(), project.getSpawnGraph());
    }
}
