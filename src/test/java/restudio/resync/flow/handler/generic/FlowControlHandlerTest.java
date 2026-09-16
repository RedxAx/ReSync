package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlHandlerTest {
    @Test
    void ifTriggersTrueBranchWhenConditionIsTrue() {
        TestFlowContext context = executeIf(true);

        assertEquals("true", context.triggeredOutput);
    }

    @Test
    void ifTriggersFalseBranchWhenConditionIsFalse() {
        TestFlowContext context = executeIf(false);

        assertEquals("false", context.triggeredOutput);
    }

    @Test
    void switchCaseContinuesFlowWithMatchedIndex() {
        TestFlowContext context = executeSwitchCase("second", List.of("first", "second", "third"));

        assertEquals(true, context.outputs.get("matched"));
        assertEquals(1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void switchCaseContinuesFlowWithoutMatch() {
        TestFlowContext context = executeSwitchCase("missing", List.of("first", "second", "third"));

        assertEquals(false, context.outputs.get("matched"));
        assertEquals(-1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void switchCaseMatchesEquivalentNumericRepresentations() {
        TestFlowContext context = executeSwitchCase(2.0D, List.of(1, 2L, 3.0F));

        assertEquals(true, context.outputs.get("matched"));
        assertEquals(1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void branchingSwitchUsesEditableCasePins() {
        TestFlowContext context = executeBranchingSwitch("second", Map.of("case", "first", "case_2", "second"));

        assertEquals(true, context.outputs.get("matched"));
        assertEquals(1, context.outputs.get("index"));
        assertEquals("case_2", context.triggeredOutput);
    }

    @Test
    void branchingSwitchUsesDefaultWhenNoCaseMatches() {
        TestFlowContext context = executeBranchingSwitch("missing", Map.of("case", "first", "case_2", "second"));

        assertEquals(false, context.outputs.get("matched"));
        assertEquals(-1, context.outputs.get("index"));
        assertEquals("default", context.triggeredOutput);
    }

    @Test
    void executorManagedLoopOperationsAreAdvertisedToValidation() {
        HandlerRegistry registry = new HandlerRegistry();
        new FlowControlHandler().registerTo(registry);

        for (String operation : List.of("loop", "loop_count", "loop_for_each", "loop_for_each_player", "loop_for_each_entity", "loop_interval", "loop_while")) {
            assertTrue(registry.hasOperation("FlowControlHandler", operation), operation);
        }
    }

    @Test
    void compiledLoopCountAdmissionHasNoHandlerSideEffects() {
        assertCompiledLoopAdmission("loop_count", Map.of("count", 3));
    }

    @Test
    void compiledLoopForEachAdmissionHasNoHandlerSideEffects() {
        assertCompiledLoopAdmission("loop_for_each", Map.of("list", List.of("first", "second")));
    }

    private TestFlowContext executeSwitchCase(Object value, List<?> cases) {
        HandlerRegistry registry = new HandlerRegistry();
        new FlowControlHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("FlowControlHandler");
        FlowNode node = new FlowNode("flow.switch_case", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "switch_case"));
        TestFlowContext context = new TestFlowContext(Map.of("value", value, "cases", cases));

        handler.execute(context, node);

        return context;
    }

    private TestFlowContext executeIf(boolean condition) {
        HandlerRegistry registry = new HandlerRegistry();
        new FlowControlHandler().registerTo(registry);
        FlowNode node = new FlowNode("if", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "if"));
        TestFlowContext context = new TestFlowContext(Map.of("condition", condition));

        registry.getHandler("FlowControlHandler").execute(context, node);

        return context;
    }

    private TestFlowContext executeBranchingSwitch(Object value, Map<String, Object> cases) {
        HandlerRegistry handlers = new HandlerRegistry();
        new FlowControlHandler().registerTo(handlers);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("flow.switch_case", "Switch Case", NodeDefinition.NodeCategory.LOGIC)
            .handler("FlowControlHandler")
            .input(new NodeDefinition.PinBuilder("case", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.ANY)
                .repeatable("cases", 1, 8, "Case")
                .build())
            .build());
        Map<String, Object> stored = new HashMap<>(cases);
        stored.put("__repeatable_count:cases", cases.size());
        FlowNode node = new FlowNode("flow.switch_case", 0, 0, stored);
        node.setHandlerConfig(Map.of("operation", "switch_case"));
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("switch", node);
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);
        Map<String, Object> inputs = new HashMap<>(cases);
        inputs.put("value", value);
        inputs.put("branch", true);
        TestFlowContext context = new TestFlowContext(runtime, inputs);

        handlers.getHandler("FlowControlHandler").execute(context, node);

        return context;
    }

    private void assertCompiledLoopAdmission(String operation, Map<String, Object> inputs) {
        HandlerRegistry handlers = new HandlerRegistry();
        new FlowControlHandler().registerTo(handlers);
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of(), Map.of(), new NodeDefinitionRegistry());
        runtime.beginLoopControl();
        runtime.setContinueLoopRequested(true);
        FlowNode node = new FlowNode("flow." + operation, 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(runtime, inputs);

        handlers.getHandler("FlowControlHandler").execute(context, node);

        assertTrue(context.outputs.isEmpty());
        assertNull(context.triggeredOutput);
        assertFalse(context.isContinuationHalted());
        assertFalse(runtime.isBreakLoopRequested());
        assertTrue(runtime.isContinueLoopRequested());
        assertEquals(0, context.inputReads);
        runtime.endLoopControl();
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;
        private int inputReads;

        private TestFlowContext(Map<String, Object> inputs) {
            this(null, inputs);
        }

        private TestFlowContext(FlowRuntime runtime, Map<String, Object> inputs) {
            super(runtime, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            inputReads++;
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
