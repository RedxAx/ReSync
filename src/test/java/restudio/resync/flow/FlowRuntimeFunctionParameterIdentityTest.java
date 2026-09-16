package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.identity.FunctionParameterId;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowRuntimeFunctionParameterIdentityTest {
    @Test
    void nestedFunctionFramesRestoreInputsAndRouteOutputsByParameterId() {
        FunctionParameterId parentInput = FunctionParameterId.deterministic("runtime-parent-input");
        FunctionParameterId parentOutput = FunctionParameterId.deterministic("runtime-parent-output");
        FunctionParameterId childInput = FunctionParameterId.deterministic("runtime-child-input");
        FunctionParameterId childOutput = FunctionParameterId.deterministic("runtime-child-output");

        FlowGraph parent = function("parent", new FlowGraph.FunctionParameter(parentInput, "renamedParentInput", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(parentOutput, "renamedParentOutput", FlowDataType.STRING));
        FlowGraph child = function("child", new FlowGraph.FunctionParameter(childInput, "renamedChildInput", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(childOutput, "renamedChildOutput", FlowDataType.STRING));
        FlowRuntime runtime = new FlowRuntime(parent, new TypeAdapterRegistry(), Map.of());

        runtime.callFunctionById(parent, "parent-call", Map.of(parentInput, "parent-value"));
        runtime.callFunctionById(child, "child-call", Map.of(childInput, "child-value"));

        assertEquals("child-value", runtime.getFunctionInput(childInput));
        runtime.returnFromFunctionById(Map.of(childOutput, "child-result"));

        assertEquals("parent-value", runtime.getFunctionInput(parentInput));
        assertEquals("child-result", runtime.getNodeOutput("child-call", "renamedChildOutput"));
        runtime.returnFromFunctionById(Map.of(parentOutput, "parent-result"));

        assertEquals("parent-result", runtime.getNodeOutput("parent-call", "renamedParentOutput"));
    }

    @Test
    void legacyNameBoundaryAdaptsToStableParameterIds() {
        FlowGraph function = function("legacy", new FlowGraph.FunctionParameter("displayName", FlowDataType.STRING),
            new FlowGraph.FunctionParameter("displayResult", FlowDataType.STRING));
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of());

        Map<FunctionParameterId, Object> inputs = runtime.adaptLegacyFunctionInputs(function, Map.of("displayName", "value"));
        assertEquals(1, inputs.size());
        assertEquals(function.getFunctionInputs().getFirst().getParameterId(), inputs.keySet().iterator().next());
    }

    @Test
    void stringFunctionInputUsesCanonicalIdWithoutModernDisplayNameFallback() {
        FunctionParameterId inputId = FunctionParameterId.deterministic("runtime-string-input");
        FlowGraph function = function("modern", new FlowGraph.FunctionParameter(inputId, "renamedInput", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(FunctionParameterId.deterministic("runtime-string-output"), "output", FlowDataType.STRING));
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of());

        runtime.callFunctionById(function, "call", Map.of(inputId, "value"));

        assertEquals("value", runtime.getFunctionInput(inputId.canonicalText()));
        assertNull(runtime.getFunctionInput("renamedInput"));
    }

    private FlowGraph function(String id, FlowGraph.FunctionParameter input, FlowGraph.FunctionParameter output) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(input));
        graph.setFunctionOutputs(List.of(output));
        return graph;
    }
}
