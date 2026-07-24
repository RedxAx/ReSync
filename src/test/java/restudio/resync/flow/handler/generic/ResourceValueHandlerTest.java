package restudio.resync.flow.handler.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceValueHandlerTest {
    @Test
    void resourceDetailsExposeIdentityAndStructuredFields() {
        JsonObject value = new JsonObject();
        value.addProperty("id", "welcome");
        value.addProperty("title", "Welcome");
        FlowNode node = node("resource_details", Map.of("value", value));
        FlowContext context = context(node);

        new ResourceValueHandler().execute(context, node);

        assertEquals("welcome", context.getOutput(node, "id"));
        assertEquals(Map.of("id", "welcome", "title", "Welcome"), context.getOutput(node, "details"));
    }

    @Test
    void motdDetailsExposeUsefulValuesWithoutRawConversion() {
        JsonObject value = new JsonObject();
        value.addProperty("id", "main");
        value.addProperty("line1", "Hello");
        value.addProperty("line2", "World");
        value.addProperty("priority", 10);
        value.addProperty("onlinePlayers", 4);
        value.addProperty("maxPlayers", 20);
        FlowNode node = node("motd_details", Map.of("profile", value));
        FlowContext context = context(node);

        new ResourceValueHandler().execute(context, node);

        assertEquals("Hello", context.getOutput(node, "line_1"));
        assertEquals(20.0, context.getOutput(node, "max_players"));
    }

    private FlowNode node(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("test", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }

    private FlowContext context(FlowNode node) {
        FlowGraph graph = new FlowGraph("test", Map.of("node", node), List.of(), List.of());
        return new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
    }
}
