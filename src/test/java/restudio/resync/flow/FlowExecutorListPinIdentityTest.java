package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericListHandler;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutorListPinIdentityTest {
    private HandlerRegistry handlers;
    private FlowExecutor executor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        handlers = new HandlerRegistry();
        new GenericListHandler().registerTo(handlers);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        handlers.clear();
        MockBukkit.unmock();
    }

    @Test
    void listAddDeliversItsCanonicalOutputPinToAConsumer() {
        AtomicReference<List<?>> observed = new AtomicReference<>();
        AtomicReference<FlowRuntime> runtime = new AtomicReference<>();
        handlers.register("list_consumer", (context, node) ->
            {
                runtime.set(context.getRuntime());
                observed.set(context.getInputValue(node, "value", List.class));
            });

        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.registerAll("active", new NodeDefinitionLoader().loadFromClasspath("nodes"));
        executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());

        FlowGraph graph = new FlowGraph();
        graph.setId("list-pin-identity");
        graph.getNodes().put("source", new FlowNode("list_add", 0, 0,
            Map.of("list", List.of("first"), "value", "second")));
        graph.getNodes().put("consumer", new FlowNode("list_consumer", 200, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "output_list", "consumer", "value"));

        executor.execute(graph, "consumer", null, null, Map.of()).join();

        assertEquals(List.of("first", "second"), observed.get());
        assertEquals(List.of("first", "second"), runtime.get().getNodeOutput("source", "list"));
    }
}
