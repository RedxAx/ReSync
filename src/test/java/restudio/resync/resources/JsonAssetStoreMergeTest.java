package restudio.resync.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAssetStoreMergeTest {
    @Test
    void replacesAWholeOwnedTopLevelObject() {
        JsonObject existing = JsonParser.parseString("""
            {
              "futureProject": "keep",
              "nodes": {
                "root": {
                  "title": "old",
                  "legacyDefault": true
                }
              }
            }
            """).getAsJsonObject();
        JsonObject serialized = JsonParser.parseString("""
            {
              "nodes": {
                "root": {
                  "title": "new"
                }
              }
            }
            """).getAsJsonObject();

        JsonObject merged = JsonAssetStore.mergePayload(existing, serialized, Set.of("nodes"));
        JsonObject legacyMerged = JsonParser.parseString("""
            {
              "futureProject": "keep",
              "nodes": {
                "root": {
                  "title": "new",
                  "legacyDefault": true
                }
              }
            }
            """).getAsJsonObject();

        assertEquals("new", merged.getAsJsonObject("nodes").getAsJsonObject("root").get("title").getAsString());
        assertFalse(merged.getAsJsonObject("nodes").getAsJsonObject("root").has("legacyDefault"));
        assertEquals("keep", merged.get("futureProject").getAsString());
        assertTrue(JsonAssetStore.matchesLegacyMergedPayload(existing, serialized, legacyMerged, Set.of("nodes")));
        assertFalse(JsonAssetStore.matchesLegacyMergedPayload(existing, serialized, merged, Set.of("nodes")));
    }

    @Test
    void removesMissingOwnedWorldGenMapEntriesWithoutDroppingOpaqueMetadata() {
        JsonObject existing = JsonParser.parseString("""
            {
              "futureProject": "keep",
              "terrainGraph": {
                "futureGraph": "keep",
                "nodes": {
                  "keep": {
                    "type": "old",
                    "inputValues": {"removed": "old", "kept": "old"},
                    "futureNode": "keep"
                  },
                  "deleted": {"type": "delete", "inputValues": {}}
                }
              },
              "settings": {
                "futureSetting": "keep",
                "biomeVanillaFeatureOverrides": {"removed": "old", "kept": "old"}
              }
            }
            """).getAsJsonObject();
        JsonObject serialized = JsonParser.parseString("""
            {
              "terrainGraph": {
                "nodes": {
                  "keep": {
                    "type": "new",
                    "inputValues": {"kept": "new"}
                  }
                }
              },
              "settings": {
                "biomeVanillaFeatureOverrides": {"kept": "new"}
              }
            }
            """).getAsJsonObject();

        JsonObject merged = JsonAssetStore.mergePayload(existing, serialized, Set.of(
            "terrainGraph", "terrainGraph.nodes", "terrainGraph.nodes[].type", "terrainGraph.nodes[].inputValues",
            "settings", "settings.biomeVanillaFeatureOverrides"));

        JsonObject nodes = merged.getAsJsonObject("terrainGraph").getAsJsonObject("nodes");
        JsonObject keptNode = nodes.getAsJsonObject("keep");
        JsonObject inputValues = keptNode.getAsJsonObject("inputValues");
        JsonObject overrides = merged.getAsJsonObject("settings").getAsJsonObject("biomeVanillaFeatureOverrides");
        assertFalse(nodes.has("deleted"));
        assertEquals("new", keptNode.get("type").getAsString());
        assertFalse(inputValues.has("removed"));
        assertEquals("new", inputValues.get("kept").getAsString());
        assertEquals("keep", keptNode.get("futureNode").getAsString());
        assertFalse(overrides.has("removed"));
        assertEquals("new", overrides.get("kept").getAsString());
        assertEquals("keep", merged.get("futureProject").getAsString());
        assertEquals("keep", merged.getAsJsonObject("terrainGraph").get("futureGraph").getAsString());
        assertEquals("keep", merged.getAsJsonObject("settings").get("futureSetting").getAsString());
        assertTrue(merged.has("terrainGraph"));
    }
}
