package restudio.resync.flow.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionPinContractTest {
    @Test
    void descriptionBoundsMatchCatalogIngestionAfterTrimming() {
        JsonObject definition = definition("""
            [{"id":"value","name":"value","dataType":"string"}]
            """, "[]");
        definition.addProperty("description", " " + "n".repeat(280) + " ");
        definition.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("description", " " + "p".repeat(240) + " ");
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateDescriptions(List.of(definition), errors);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void oversizedDescriptionsFailBeforePackaging() {
        JsonObject definition = definition("""
            [{"id":"value","name":"value","dataType":"string"}]
            """, """
            [{"id":"result","name":"result","dataType":"string"}]
            """);
        definition.addProperty("description", "n".repeat(281));
        definition.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("description", "p".repeat(241));
        definition.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("description", "p".repeat(241));
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateDescriptions(List.of(definition), errors);

        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.contains("contract.node description must contain 24 through 280")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("contract.node.value description must contain 16 through 240")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("contract.node.result description must contain 16 through 240")));
    }

    @Test
    void missingOrShortDescriptionsFailBeforePackaging() {
        JsonObject definition = definition("""
            [{"id":"value","name":"value","dataType":"string","description":"Too Short"}]
            """, "[]");
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateDescriptions(List.of(definition), errors);

        assertEquals(2, errors.size());
    }

    @Test
    void duplicateStablePinIdsFailBeforePackaging() {
        JsonObject definition = definition("""
            [{"id":"value","name":"first","dataType":"string"},
             {"id":"value","name":"second","dataType":"string"}]
            """, "[]");

        List<String> errors = validate(definition);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("duplicate input pin id value"));
    }

    @Test
    void stableIdAndRuntimeNameCollisionsFailBeforePackaging() {
        JsonObject definition = definition("""
            [{"id":"stable","name":"runtime","dataType":"string"},
             {"id":"runtime","name":"other","dataType":"string"}]
            """, "[]");

        List<String> errors = validate(definition);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("ambiguous input pin address runtime"));
    }

    @Test
    void inputAndOutputAddressesRemainIndependent() {
        String pin = "[{\"id\":\"value\",\"name\":\"value\",\"dataType\":\"string\"}]";

        assertTrue(validate(definition(pin, pin)).isEmpty());
    }

    @Test
    void mappingTargetsAcceptStableIdsAndRuntimeNames() {
        JsonObject definition = definition("[]", """
            [{"id":"output_value","name":"value","dataType":"string"}]
            """);
        definition.add("outputMappings", JsonParser.parseString("""
            [{"source":"event.first","target":"output_value"},
             {"source":"event.second","target":"value"}]
            """));

        assertTrue(validate(definition).isEmpty());
    }

    @Test
    void unknownMappingTargetsFailBeforePackaging() {
        JsonObject definition = definition("[]", "[]");
        definition.add("outputMappings", JsonParser.parseString("""
            [{"source":"event.player","target":"missing"}]
            """));

        List<String> errors = validate(definition);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("maps to unknown output pin missing"));
    }

    @Test
    void wrongDirectionsAndFlowTypesFailBeforePackaging() {
        JsonObject definition = definition("""
            [{"id":"flow","name":"flow","direction":"output","pinType":"FLOW","dataType":"string"}]
            """, "[]");

        List<String> errors = validate(definition);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.contains("direction in inputs")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("flow pin without execution dataType")));
    }

    @Test
    void missingCanonicalTargetsFailBeforePackaging() {
        JsonObject definition = definition("[]", "[]");
        definition.addProperty("canonicalId", "missing");

        List<String> errors = validate(definition);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("references unknown canonicalId missing"));
    }

    private JsonObject definition(String inputs, String outputs) {
        return JsonParser.parseString("""
            {"id":"contract.node","inputs":%s,"outputs":%s}
            """.formatted(inputs, outputs)).getAsJsonObject();
    }

    private List<String> validate(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        NodeDefinitionBuildValidator.validatePinContracts(List.of(definition), errors);
        return errors;
    }
}
