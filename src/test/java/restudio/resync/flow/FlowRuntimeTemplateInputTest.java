package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowRuntimeTemplateInputTest {
    @Test
    void connectedDynamicPinRendersInsideStoredStringTemplate() {
        FlowNode source = new FlowNode("source", 0, 0, Map.of());
        FlowNode actionBar = new FlowNode("player.player_send_action_bar", 0, 0, Map.of("text", "Color: {color}"));
        FlowGraph graph = new FlowGraph("chat_color", Map.of(
            "source", source,
            "action_bar", actionBar
        ), List.of(new FlowConnection("source", "color", "action_bar", "color")), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        runtime.setNodeOutput("source", "color", "red");

        assertEquals("Color: red", runtime.resolveInput(actionBar, "text", String.class));
    }

    @Test
    void escapedBracesRemainLiteralWhileDynamicPinsRender() {
        FlowNode source = new FlowNode("source", 0, 0, Map.of());
        FlowNode target = new FlowNode("target", 0, 0, Map.of("text", "{{Color}}: {color}"));
        FlowGraph graph = new FlowGraph("escaped_template", Map.of(
            "source", source,
            "target", target
        ), List.of(new FlowConnection("source", "color", "target", "color")), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        runtime.setNodeOutput("source", "color", "blue");

        assertEquals("{Color}: blue", runtime.resolveInput(target, "text", String.class));
    }
}
