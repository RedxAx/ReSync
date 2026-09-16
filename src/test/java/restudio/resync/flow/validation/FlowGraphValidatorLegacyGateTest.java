package restudio.resync.flow.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowVariable;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.api.OptionCatalogRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowGraphValidatorLegacyGateTest {
    @TempDir
    Path temporary;

    @Test
    void runtimeValidationDoesNotResolveLegacyAliases() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        definitions.register(new NodeDefinition.Builder("math.random_range", "Random Range", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .output(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.NUMBER).build())
            .build());
        FlowGraphValidator validator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), new OptionCatalogRegistry(), null, null,
            Clock.systemUTC(), null, LegacyRuntimeActivationGate.runtime(temporary));

        FlowGraph graph = new FlowGraph("test", Map.of("node", new FlowNode("math_random_range", 0, 0, Map.of())), List.of(), List.<FlowVariable>of());
        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "NODE_DEFINITION_MISSING".equals(diagnostic.code())));
    }
}
