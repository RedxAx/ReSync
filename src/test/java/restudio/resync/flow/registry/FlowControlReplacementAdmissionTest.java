package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.FlowControlHandler;
import restudio.resync.flow.migration.IdCompatibilityLayer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlReplacementAdmissionTest {
    @Test
    void replacementCatalogAdmitsOwnerQualifiedIfAndWhileDefinitions() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions = loader.loadReplacementFromClasspath("nodes");
        NodeDefinition ifDefinition = definitions.stream().filter(value -> "if".equals(value.getId())).findFirst().orElse(null);
        NodeDefinition whileDefinition = definitions.stream().filter(value -> "loop_while".equals(value.getId())).findFirst().orElse(null);
        assertNotNull(ifDefinition);
        assertNotNull(whileDefinition);
        assertEquals("restudio.resync", ifDefinition.getOwner());
        assertEquals("restudio.resync", whileDefinition.getOwner());
        assertEquals(List.of("flow", "condition"), ifDefinition.getInputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals(List.of("true", "false"), ifDefinition.getOutputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals(List.of("flow", "condition"), ifDefinition.getInputs().stream().map(value -> value.getId().value()).toList());
        assertEquals(List.of("Flow", "Condition"), ifDefinition.getInputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());
        assertEquals(List.of("true", "false"), ifDefinition.getOutputs().stream().map(value -> value.getId().value()).toList());
        assertEquals(List.of("True", "False"), ifDefinition.getOutputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());
        assertEquals(List.of("flow", "condition", "interval_ticks", "max_iterations"),
            whileDefinition.getInputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals(List.of("loop", "done", "completed", "index"),
            whileDefinition.getOutputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals(List.of("flow", "condition", "interval_ticks", "max_iterations"),
            whileDefinition.getInputs().stream().map(value -> value.getId().value()).toList());
        assertEquals(List.of("Flow", "Condition", "Interval Ticks", "Max Iterations"),
            whileDefinition.getInputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());
        assertEquals(List.of("loop", "done", "completed", "index"),
            whileDefinition.getOutputs().stream().map(value -> value.getId().value()).toList());
        assertEquals(List.of("Loop", "Done", "Completed", "Index"),
            whileDefinition.getOutputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());

        HandlerRegistry handlers = new HandlerRegistry();
        new FlowControlHandler().registerTo(handlers);
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        assertTrue(validator.validate(ifDefinition).valid(), validator.validate(ifDefinition).errors().toString());
        assertTrue(validator.validate(whileDefinition).valid(), validator.validate(whileDefinition).errors().toString());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
    }

    @Test
    void activeFlowControlIdsAreNotMigrationKeys() {
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        assertEquals("if", compatibility.mapToNew("if"));
        assertEquals("loop_while", compatibility.mapToNew("loop_while"));
        assertFalse(compatibility.getAllMappings().containsKey("if"));
        assertFalse(compatibility.getAllMappings().containsKey("loop_while"));
        assertFalse(compatibility.hasMapping("if"));
        assertFalse(compatibility.hasMapping("loop_while"));
    }
}
