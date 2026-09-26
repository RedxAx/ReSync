package restudio.flow.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSerializerOpaqueIdentityTest {
    @Test
    void preservesClientStablePinsAndCoreProjectionMetadata() {
        String source = """
            {
              "id":"content.block.block",
              "version":2,
              "nodes":{
                "source":{"type":"restudio.resync:custom_content.block","version":2,"x":120,"y":120,"inputValues":{},"instanceId":"source","contentInputs":{"__flow_branches":["interact"]}},
                "target":{"type":"restudio.resync:player.player_message","version":2,"x":358,"y":156,"inputValues":{},"instanceId":"target"}
              },
              "connections":[{"connectionId":"0c9dfc63-de2a-45c5-b021-0ce512829449","sourceNodeId":"source","sourcePinId":"nearby_player","targetNodeId":"target","targetPinId":"flow"}],
              "editorPassthroughs":[{"nodeId":"source","inputPin":"nearby_player","inputPinId":"nearby_player","inputPinDisplayName":"Nearby Player"}],
              "localVariables":[],
              "functionInputs":[],
              "functionOutputs":[],
              "contentCoreGraph":{"catalogBinding":{"generation":84}}
            }
            """;

        FlowGraph graph = FlowSerializer.deserialize(source);
        FlowConnection connection = graph.getConnections().getFirst();
        assertEquals("nearby_player", connection.getSourcePin());
        assertEquals("flow", connection.getTargetPin());

        JsonObject restored = JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
        JsonObject node = restored.getAsJsonObject("nodes").getAsJsonObject("source");
        JsonObject encodedConnection = restored.getAsJsonArray("connections").get(0).getAsJsonObject();
        assertEquals("source", node.get("instanceId").getAsString());
        assertTrue(node.has("contentInputs"));
        assertEquals("0c9dfc63-de2a-45c5-b021-0ce512829449", encodedConnection.get("connectionId").getAsString());
        assertEquals("nearby_player", encodedConnection.get("sourcePinId").getAsString());
        assertEquals("flow", encodedConnection.get("targetPinId").getAsString());
        assertFalse(encodedConnection.has("sourcePin"));
        JsonObject passthrough = restored.getAsJsonArray("editorPassthroughs").get(0).getAsJsonObject();
        assertEquals("nearby_player", passthrough.get("inputPinId").getAsString());
        assertEquals("Nearby Player", passthrough.get("inputPinDisplayName").getAsString());
        assertTrue(restored.has("contentCoreGraph"));
    }
}
