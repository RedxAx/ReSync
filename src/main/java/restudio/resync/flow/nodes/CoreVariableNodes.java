package restudio.resync.flow.nodes;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.ReSync;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.HashMap;
import java.util.Map;

public class CoreVariableNodes {

    @DefineNode(id = "get_variable", displayName = "Get Variable", category = NodeDefinition.NodeCategory.VARIABLE,
            inputs = {@FlowPin(name = "name", dataType = FlowType.STRING)},
            outputs = {
                    @FlowPin(name = "value", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void getVariable(FlowContext ctx, FlowNode node) {
        String name = ctx.getInputValue(node, "name", String.class, "");
        Object value = ctx.getVariable(name);
        ctx.setOutput(node, "value", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "set_variable", displayName = "Set Variable", category = NodeDefinition.NodeCategory.VARIABLE,
            inputs = {
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setVariable(FlowContext ctx, FlowNode node) {
        String name = ctx.getInputValue(node, "name", String.class, "");
        Object value = ctx.getInputValue(node, "value", Object.class, null);
        ctx.setVariable(name, value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "get_server_var", displayName = "Get Server Variable", category = NodeDefinition.NodeCategory.VARIABLE,
            inputs = {@FlowPin(name = "name", dataType = FlowType.STRING)},
            outputs = {
                    @FlowPin(name = "value", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void getServerVar(FlowContext ctx, FlowNode node) {
        String name = ctx.getInputValue(node, "name", String.class, "");
        Object value = ctx.getGlobalVariables().get("server." + name);
        ctx.setOutput(node, "value", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "call_function", displayName = "Call Function", category = NodeDefinition.NodeCategory.FUNCTION,
            inputs = {@FlowPin(name = "function", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void callFunction(FlowContext ctx, FlowNode node) {
        String functionName = ctx.getInputValue(node, "function", String.class, "");
        FlowStorage storage = FlowRuntimeAccess.getStorage();
        if (storage == null) {
            storage = new FlowStorage(ReSync.getInstance());
        }
        FlowGraph functionGraph = storage.getGraph(functionName);
        if (functionGraph != null) {
            String returnNodeId = ctx.resolveNodeId(node);
            ctx.getRuntime().callFunction(functionGraph, returnNodeId);
        } else {
            System.err.println("[Flow] Function not found: " + functionName);
            ctx.triggerOutput("flow");
        }
    }

    @DefineNode(id = "return", displayName = "Return", category = NodeDefinition.NodeCategory.FUNCTION,
            inputs = {@FlowPin(name = "value", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void returnNode(FlowContext ctx, FlowNode node) {
        Object returnValue = ctx.getInputValue(node, "value", Object.class, null);
        if (!ctx.getRuntime().returnFromFunction(returnValue)) {
            System.err.println("[Flow] return called outside function");
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "function_start", displayName = "Function Start", category = NodeDefinition.NodeCategory.FUNCTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void functionStart(FlowContext ctx, FlowNode node) {
        FlowGraph graph = ctx.getRuntime().getGraph();
        if (graph != null && graph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                Object value = ctx.getRuntime().getFunctionInput(parameter.getName());
                ctx.setOutput(node, parameter.getName(), value);
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "function_end", displayName = "Function End", category = NodeDefinition.NodeCategory.FUNCTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void functionEnd(FlowContext ctx, FlowNode node) {
        FlowGraph graph = ctx.getRuntime().getGraph();
        Map<String, Object> values = new HashMap<>();
        if (graph != null && graph.getFunctionOutputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                values.put(parameter.getName(), ctx.getInputValue(node, parameter.getName(), Object.class, null));
            }
        }
        if (!ctx.getRuntime().returnFromFunction(values)) {
            ctx.triggerOutput("flow");
        }
    }
}
