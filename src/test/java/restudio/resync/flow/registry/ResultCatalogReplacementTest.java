package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.ResultHandler;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultCatalogReplacementTest {
    @Test
    void replacementCatalogAdmitsCanonicalResultDefinitions() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions = loader.loadReplacementFromClasspath("nodes");
        List<NodeDefinition> results = definitions.stream()
            .filter(definition -> definition.getId().startsWith("core.result."))
            .toList();

        assertEquals(List.of("core.result.success", "core.result.failure", "core.result.is_success", "core.result.value",
            "core.result.error", "core.result.match"), results.stream().map(NodeDefinition::getId).toList());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());

        HandlerRegistry handlers = new HandlerRegistry();
        new ResultHandler().registerTo(handlers);
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        Map<String, String> operations = Map.of(
            "core.result.success", "success",
            "core.result.failure", "failure",
            "core.result.is_success", "is_success",
            "core.result.value", "value",
            "core.result.error", "error",
            "core.result.match", "match");
        Map<String, List<String>> inputIds = Map.of(
            "core.result.success", List.of("value"),
            "core.result.failure", List.of("error_code", "message", "details"),
            "core.result.is_success", List.of("result"),
            "core.result.value", List.of("result"),
            "core.result.error", List.of("result"),
            "core.result.match", List.of("flow", "result"));
        Map<String, List<String>> outputIds = Map.of(
            "core.result.success", List.of("result"),
            "core.result.failure", List.of("result"),
            "core.result.is_success", List.of("success"),
            "core.result.value", List.of("value", "available"),
            "core.result.error", List.of("error_code", "message", "details"),
            "core.result.match", List.of("success", "failure", "value", "error_code", "message", "details"));
        Map<String, List<String>> inputDisplayNames = Map.of(
            "core.result.success", List.of("Value"),
            "core.result.failure", List.of("Error Code", "Message", "Details"),
            "core.result.is_success", List.of("Result"),
            "core.result.value", List.of("Result"),
            "core.result.error", List.of("Result"),
            "core.result.match", List.of("Flow", "Result"));
        Map<String, List<String>> outputDisplayNames = Map.of(
            "core.result.success", List.of("Result"),
            "core.result.failure", List.of("Result"),
            "core.result.is_success", List.of("Success"),
            "core.result.value", List.of("Value", "Available"),
            "core.result.error", List.of("Error Code", "Message", "Details"),
            "core.result.match", List.of("Success", "Failure", "Value", "Error Code", "Message", "Details"));
        Map<String, List<String>> inputTypes = Map.of(
            "core.result.success", List.of("type:t"),
            "core.result.failure", List.of("string", "string", "map<string,any>"),
            "core.result.is_success", List.of("result<type:t>"),
            "core.result.value", List.of("result<type:t>"),
            "core.result.error", List.of("result<any>"),
            "core.result.match", List.of("execution", "result<type:t>"));
        Map<String, List<String>> outputTypes = Map.of(
            "core.result.success", List.of("result<type:t>"),
            "core.result.failure", List.of("result<any>"),
            "core.result.is_success", List.of("boolean"),
            "core.result.value", List.of("type:t", "boolean"),
            "core.result.error", List.of("string", "string", "map<string,any>"),
            "core.result.match", List.of("execution", "execution", "type:t", "string", "string", "map<string,any>"));

        for (NodeDefinition definition : results) {
            assertEquals("restudio.resync", definition.getOwner());
            assertEquals(List.of(definition.getId().substring("core.".length())), definition.getLegacyIds());
            assertEquals("ResultHandler", definition.getHandler());
            assertEquals(operations.get(definition.getId()), definition.getHandlerConfig().get("operation"));
            assertEquals(inputIds.get(definition.getId()), definition.getInputs().stream().map(pin -> pin.getId().value()).toList());
            assertEquals(outputIds.get(definition.getId()), definition.getOutputs().stream().map(pin -> pin.getId().value()).toList());
            assertEquals(inputDisplayNames.get(definition.getId()), definition.getInputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());
            assertEquals(outputDisplayNames.get(definition.getId()), definition.getOutputs().stream().map(NodeDefinition.PinDefinition::getDisplayName).toList());
            assertEquals(inputTypes.get(definition.getId()), definition.getInputs().stream().map(pin -> pin.getTypeRef().toString()).toList());
            assertEquals(outputTypes.get(definition.getId()), definition.getOutputs().stream().map(pin -> pin.getTypeRef().toString()).toList());
            assertTrue(definition.getInputs().stream().allMatch(pin -> pin.getDisplayName() != null && !pin.getDisplayName().isBlank()));
            assertTrue(definition.getOutputs().stream().allMatch(pin -> pin.getDisplayName() != null && !pin.getDisplayName().isBlank()));
            AuthoredNodeMetadata metadata = definition.getAuthoredMetadata();
            assertNotNull(metadata);
            assertEquals(definition.getId(), metadata.id());
            assertEquals("core", metadata.domain());
            assertEquals("result", metadata.family());
            assertEquals("active", metadata.lifecycle());
            assertEquals("restudio.resync", metadata.sourceProvenance().owner());
            assertTrue(metadata.sourceProvenance().sourceUri().endsWith("result.json"));
            assertTrue(metadata.sourceProvenance().rowIndex() >= 0);
            NodeDefinitionValidator.ValidationResult validation = validator.validate(definition);
            assertTrue(validation.valid(), validation.errors().toString());
        }

        NodeDefinition failure = results.stream().filter(definition -> definition.getId().equals("core.result.failure")).findFirst().orElseThrow();
        assertEquals("FAILED", failure.getInputs().get(0).getDefaultValue());
        assertEquals("Operation Failed", failure.getInputs().get(1).getDefaultValue());
        assertTrue(failure.getInputs().get(2).isOptional());

        NodeDefinition match = results.stream().filter(definition -> definition.getId().equals("core.result.match")).findFirst().orElseThrow();
        assertEquals(List.of("success", "failure"), match.getOutputs().subList(0, 2).stream().map(pin -> pin.getId().value()).toList());
        assertEquals("FLOW", match.getInputs().getFirst().getType().name());
        assertEquals("FLOW", match.getOutputs().getFirst().getType().name());
        assertEquals("FLOW", match.getOutputs().get(1).getType().name());
    }
}
