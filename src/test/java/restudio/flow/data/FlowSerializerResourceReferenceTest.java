package restudio.flow.data;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSerializerResourceReferenceTest {
    private static final String SERVER = "11111111-1111-5111-8111-111111111111";

    @Test
    void restoresReferencesInsideUntypedNodeValuesAndPreservesMetadata() {
        FlowResourceReference reference = new FlowResourceReference("variable_definition", "round", "server", true,
            Map.of("future", Map.of("enabled", true)));
        FlowGraph source = new FlowGraph("reference-round-trip", Map.of(
            "node", new FlowNode("automation.variable", 0, 0, Map.of(
                "variable", reference,
                "nested", List.of(Map.of("reference", reference))))), List.of(), List.of());

        FlowGraph restored = FlowSerializer.deserialize(FlowSerializer.serialize(source));
        FlowResourceReference direct = assertInstanceOf(FlowResourceReference.class,
            restored.getNodes().get("node").getInputValues().get("variable"));
        FlowResourceReference nested = assertInstanceOf(FlowResourceReference.class,
            ((Map<?, ?>) ((List<?>) restored.getNodes().get("node").getInputValues().get("nested")).getFirst()).get("reference"));

        assertEquals(reference, direct);
        assertEquals(reference, nested);
        assertEquals(true, ((Map<?, ?>) direct.metadata().get("future")).get("enabled"));
        var serialized = JsonParser.parseString(FlowSerializer.serialize(restored)).getAsJsonObject();
        assertFalse(serialized.has("contentProperties"));
        assertFalse(serialized.getAsJsonObject("nodes").getAsJsonObject("node").has("handlerConfig"));
    }

    @Test
    void restoresCanonicalLocatorWithExactIdentityAndUnknownFields() {
        String json = """
            {
              "id":"canonical-reference",
              "version":2,
              "nodes":{
                "node":{
                  "type":"automation.variable",
                  "version":1,
                  "x":0,
                  "y":0,
                  "inputValues":{
                    "variable":{
                      "serverId":"11111111-1111-5111-8111-111111111111",
                      "type":{"ownerId":"automation","localId":"variable_definition","futureType":{"enabled":true}},
                      "id":"round",
                      "futureLocator":{"revision":7}
                    }
                  },
                  "handlerConfig":{}
                }
              },
              "connections":[],
              "localVariables":[]
            }
            """;

        FlowGraph graph = FlowSerializer.deserialize(json, SERVER);
        FlowResourceReference reference = assertInstanceOf(FlowResourceReference.class,
            graph.getNodes().get("node").getInputValues().get("variable"));

        assertEquals("automation", reference.owner());
        assertEquals("variable_definition", reference.kind());
        assertEquals("round", reference.id());
        assertEquals(SERVER, reference.metadata().get("serverId"));
        assertEquals(true, ((Map<?, ?>) reference.metadata().get("type")).containsKey("futureType"));
        assertEquals(7.0, ((Map<?, ?>) reference.metadata().get("futureLocator")).get("revision"));
        assertTrue(JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject()
            .getAsJsonObject("nodes").getAsJsonObject("node").has("handlerConfig"));
    }

    @Test
    void leavesPartialReferenceLikeMapsOpaqueAcrossGraphValueContainers() {
        Map<String, Object> partialLegacy = Map.of("id", "ordinary", "available", true);
        Map<String, Object> partialMetadata = Map.of("id", "ordinary", "metadata", Map.of("value", "opaque"));
        Map<String, Object> partialCanonical = Map.of("serverId", SERVER, "id", "ordinary");
        FlowNode node = new FlowNode("automation.variable", 0, 0, Map.of(
            "legacy", partialLegacy,
            "canonical", partialCanonical));
        node.setHandlerConfig(Map.of("metadata", partialMetadata));
        FlowGraph source = new FlowGraph("opaque-reference-like-values", Map.of("node", node), List.of(),
            List.of(new FlowVariable("value", "any", partialLegacy)));
        source.setContentProperties(Map.of("server", partialCanonical));

        FlowGraph restored = FlowSerializer.deserialize(FlowSerializer.serialize(source));
        Object inputLegacy = restored.getNodes().get("node").getInputValues().get("legacy");
        Object inputCanonical = restored.getNodes().get("node").getInputValues().get("canonical");
        Object handlerMetadata = restored.getNodes().get("node").getHandlerConfigValues().get("metadata");
        Object variableValue = restored.getLocalVariables().getFirst().getInitialValue();
        Object contentServer = restored.getContentProperties().get("server");

        assertEquals(partialLegacy, inputLegacy);
        assertEquals(partialCanonical, inputCanonical);
        assertEquals(partialMetadata, handlerMetadata);
        assertEquals(partialLegacy, variableValue);
        assertEquals(partialCanonical, contentServer);
        assertFalse(inputLegacy instanceof FlowResourceReference);
        assertFalse(inputCanonical instanceof FlowResourceReference);
        assertFalse(handlerMetadata instanceof FlowResourceReference);
        assertFalse(variableValue instanceof FlowResourceReference);
        assertFalse(contentServer instanceof FlowResourceReference);
    }

    @Test
    void rejectsMalformedAndWrongServerReferences() {
        String malformed = """
            {"id":"malformed","version":2,"nodes":{"node":{"type":"automation.variable","inputValues":{"variable":{"kind":"variable_definition","id":"round","owner":"","available":true,"metadata":{}}}}},"connections":[],"localVariables":[]}
            """;
        String wrongServer = """
            {"id":"wrong-server","version":2,"nodes":{"node":{"type":"automation.variable","inputValues":{"variable":{"kind":"variable_definition","id":"round","owner":"server","available":true,"metadata":{"serverId":"22222222-2222-5222-8222-222222222222"}}}}},"connections":[],"localVariables":[]}
            """;

        assertThrows(IllegalArgumentException.class, () -> FlowSerializer.deserialize(malformed));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FlowSerializer.deserialize(wrongServer, SERVER));
        assertEquals("RESOURCE_REFERENCE_SERVER_MISMATCH", failure.getMessage());
    }

    @Test
    void rejectsCanonicalLocatorWithMismatchedExplicitKey() {
        String json = """
            {"id":"wrong-key","version":2,"nodes":{"node":{"type":"automation.variable","inputValues":{"variable":{"serverId":"11111111-1111-5111-8111-111111111111","type":{"ownerId":"automation","localId":"variable_definition"},"id":"round","key":{"type":{"ownerId":"automation","localId":"variable_definition"},"id":"other"}}}}},"connections":[],"localVariables":[]}
            """;

        assertThrows(IllegalArgumentException.class, () -> FlowSerializer.deserialize(json, SERVER));
    }
}
