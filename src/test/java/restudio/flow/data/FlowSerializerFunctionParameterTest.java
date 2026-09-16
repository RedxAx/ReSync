package restudio.flow.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.FunctionParameterId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowSerializerFunctionParameterTest {
    private static final String FIRST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    void canonicalIdsSurviveRenameAndReorderWhileRootUnknownFieldsRoundTrip() {
        String source = """
            {
              "id":"parameter-round-trip",
              "function":true,
              "functionInputs":[
                {"parameterId":"%s","name":"First Name","type":"string"},
                {"parameterId":"%s","name":"Second Name","type":"number"}
              ],
              "functionOutputs":[],
              "futureGraph":{"revision":7}
            }
            """.formatted(FIRST_ID, SECOND_ID);

        FlowGraph graph = FlowSerializer.deserialize(source);
        List<FlowGraph.FunctionParameter> parameters = new ArrayList<>(graph.getFunctionInputs());
        parameters.getFirst().setName("Renamed First");
        Collections.swap(parameters, 0, 1);
        graph.setFunctionInputs(parameters);

        JsonObject result = JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
        assertEquals(SECOND_ID, result.getAsJsonArray("functionInputs").get(0).getAsJsonObject().get("parameterId").getAsString());
        assertEquals("Second Name", result.getAsJsonArray("functionInputs").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(FIRST_ID, result.getAsJsonArray("functionInputs").get(1).getAsJsonObject().get("parameterId").getAsString());
        assertEquals("Renamed First", result.getAsJsonArray("functionInputs").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(7, result.getAsJsonObject("futureGraph").get("revision").getAsInt());
    }

    @Test
    void legacyMissingIdsUseOneDeterministicDecodeAdapterAndRemainStableAfterRenameAndReorder() {
        String source = """
            {
              "id":"legacy-graph",
              "function":true,
              "functionInputs":[
                {"name":"First Name","type":"string"},
                {"name":"Second Name","type":"number"}
              ],
              "functionOutputs":[{"name":"Result","type":"string"}]
            }
            """;

        FlowGraph graph = FlowSerializer.deserialize(source);
        FunctionParameterId firstId = FunctionParameterId.deterministic("legacy-flow-graph\u0000legacy-graph\u0000input\u00000");
        FunctionParameterId secondId = FunctionParameterId.deterministic("legacy-flow-graph\u0000legacy-graph\u0000input\u00001");
        FunctionParameterId outputId = FunctionParameterId.deterministic("legacy-flow-graph\u0000legacy-graph\u0000output\u00000");
        assertEquals(firstId, graph.getFunctionInputs().get(0).getParameterId());
        assertEquals(secondId, graph.getFunctionInputs().get(1).getParameterId());
        assertEquals(outputId, graph.getFunctionOutputs().getFirst().getParameterId());

        List<FlowGraph.FunctionParameter> parameters = new ArrayList<>(graph.getFunctionInputs());
        parameters.getFirst().setName("Renamed First");
        Collections.swap(parameters, 0, 1);
        graph.setFunctionInputs(parameters);

        JsonObject result = JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
        assertEquals(secondId.canonicalText(), result.getAsJsonArray("functionInputs").get(0).getAsJsonObject().get("parameterId").getAsString());
        assertEquals(firstId.canonicalText(), result.getAsJsonArray("functionInputs").get(1).getAsJsonObject().get("parameterId").getAsString());
        assertEquals(outputId.canonicalText(), result.getAsJsonArray("functionOutputs").get(0).getAsJsonObject().get("parameterId").getAsString());
    }

    @Test
    void legacyMissingIdsRequireAStablePersistedGraphId() {
        String source = """
            {
              "function":true,
              "functionInputs":[{"name":"value","type":"string"}]
            }
            """;

        assertThrows(IllegalArgumentException.class, () -> FlowSerializer.deserialize(source));
    }
}
