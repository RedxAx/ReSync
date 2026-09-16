package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.identity.PinId;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionPinIdentityBoundaryTest {
    @Test
    void replacementRequiresExplicitCanonicalPinId() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        List<NodeDefinition> definitions = loader.parseReplacement(jsonWithPin("", "Value"), "missing-id.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_PIN_ID_MISSING".equals(value.code())));
    }

    @Test
    void replacementRequiresNonBlankPinDisplayName() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        List<NodeDefinition> definitions = loader.parseReplacement(jsonWithPin("value", ""), "missing-display-name.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_PIN_DISPLAY_NAME_MISSING".equals(value.code())));
    }

    @Test
    void replacementRejectsDuplicatePinIdsAcrossDirections() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"boundary.node\",\"displayName\":\"Boundary Node\","
            + "\"category\":\"DATA\",\"description\":\"A boundary definition with authored identity.\","
            + "\"domain\":\"automation\",\"family\":\"automation\",\"lifecycle\":\"active\","
            + "\"handlerCapability\":\"boundary.handler\",\"selectorIntent\":\"none\","
            + "\"inspectorIntent\":\"generic\",\"inputs\":[{\"id\":\"value\",\"displayName\":\"Value\","
            + "\"dataType\":\"string\",\"description\":\"Provides the authored boundary value.\"}],"
            + "\"outputs\":[{\"id\":\"value\",\"displayName\":\"Value Result\",\"dataType\":\"string\","
            + "\"description\":\"Returns the authored boundary value.\"}]}]";

        List<NodeDefinition> definitions = loader.parseReplacement(jsonBytes(source), "duplicate-id.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_PIN_ID_DUPLICATE".equals(value.code())));
    }

    @Test
    void compatibilityModeMapsLegacyNameToBothIdentityAndDisplay() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"legacy.node\",\"displayName\":\"Legacy Node\",\"inputs\":["
            + "{\"name\":\"legacy_value\",\"dataType\":\"string\",\"defaultValue\":\"default\","
            + "\"description\":\"Legacy value\"}]}]";

        List<NodeDefinition> definitions = loader.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
            "legacy.json", NodeDefinitionLoader.CatalogSource.COMPATIBILITY);

        NodeDefinition.PinDefinition pin = definitions.getFirst().getInputs().getFirst();
        assertEquals(PinId.of("legacy_value"), pin.getId());
        assertEquals("legacy_value", pin.getName());
        assertEquals("legacy_value", pin.getDisplayName());
        assertEquals("default", pin.getDefaultValue());
    }

    @Test
    void compatibilityModeDisambiguatesLegacyNameCollisionsByDirection() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"legacy.flow\",\"displayName\":\"Legacy Flow\",\"handler\":\"legacy.handler\","
            + "\"inputs\":[{\"name\":\"flow\",\"pinType\":\"FLOW\",\"dataType\":\"execution\","
            + "\"description\":\"Legacy input flow pin\"}],\"outputs\":[{\"name\":\"flow\",\"pinType\":\"FLOW\","
            + "\"dataType\":\"execution\",\"description\":\"Legacy output flow pin\"}]}]";

        List<NodeDefinition> definitions = loader.parse(jsonBytes(source), "migrated/file.json", NodeDefinitionLoader.CatalogSource.COMPATIBILITY);

        NodeDefinition definition = definitions.getFirst();
        NodeDefinition.PinDefinition input = definition.getInputs().getFirst();
        NodeDefinition.PinDefinition output = definition.getOutputs().getFirst();
        assertEquals("flow", input.getId().value());
        assertEquals("output_flow", output.getId().value());
        assertEquals("flow", input.getDisplayName());
        assertEquals("flow", output.getDisplayName());

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);
        assertFalse(result.errors().contains("Duplicate pin ID: flow"));
    }

    @Test
    void replacementAcceptsLegacyMapPinNames() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        List<NodeDefinition> definitions = loader.parseReplacement(jsonWithMigration(
            "{\"direction\":\"input\",\"source\":\"mapA\",\"target\":\"map_a\"},"
                + "{\"direction\":\"input\",\"source\":\"mapB\",\"target\":\"map_b\"},"
                + "{\"direction\":\"output\",\"source\":\"map\",\"target\":\"output_map\"}"),
            "map-migration.json");

        assertEquals(1, definitions.size());
        assertEquals(List.of("mapA", "mapB", "map"), definitions.getFirst().getMigrationMapping().pins().stream()
            .map(value -> value.sourcePinId().value()).toList());
    }

    @Test
    void replacementRejectsUnsafeOrAmbiguousLegacyMigrationSources() {
        Stream.of("", "bad value", "bad/control", "mapA", "mapA").forEach(source -> {
            String pins = source.isEmpty()
                ? "{\"direction\":\"input\",\"source\":\"\",\"target\":\"map_a\"}"
                : source.equals("mapA")
                    ? "{\"direction\":\"input\",\"source\":\"mapA\",\"target\":\"map_a\"},"
                        + "{\"direction\":\"input\",\"source\":\"mapA\",\"target\":\"map_b\"}"
                    : "{\"direction\":\"input\",\"source\":\"" + source + "\",\"target\":\"map_a\"}"
                        + "," + "{\"direction\":\"input\",\"source\":\"mapB\",\"target\":\"map_b\"}";
            NodeDefinitionLoader loader = new NodeDefinitionLoader();

            assertTrue(loader.parseReplacement(jsonWithMigration(pins), "invalid-map-migration.json").isEmpty(), source);
            assertTrue(loader.getDiagnostics().stream().anyMatch(value ->
                "CATALOG.REPLACEMENT_PIN_MIGRATION_INVALID".equals(value.code())), source);
        });
    }

    @Test
    void displayRenameKeepsPinIdentityAndDuplicateDisplaysRemainSafe() {
        NodeDefinition first = parseReplacement(jsonWithPin("value", "Value")).getFirst();
        NodeDefinition renamed = parseReplacement(jsonWithPin("value", "Amount")).getFirst();

        assertEquals(first.getInputs().getFirst().getId(), renamed.getInputs().getFirst().getId());
        assertEquals("value", renamed.getInputs().getFirst().getId().value());
        assertFalse(first.getInputs().getFirst().getDisplayName().equals(renamed.getInputs().getFirst().getDisplayName()));

        NodeDefinition duplicateDisplay = new NodeDefinition.Builder("duplicate.display", "Duplicate Display", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinDefinition(PinId.of("first"), "Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING))
            .input(new NodeDefinition.PinDefinition(PinId.of("second"), "Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING))
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(duplicateDisplay);

        assertFalse(result.errors().stream().anyMatch(error -> error.contains("display") || error.contains("Duplicate pin")));
    }

    @Test
    void validatorRejectsDuplicateIdsAndDirectionMismatchDeterministically() {
        NodeDefinition duplicate = new NodeDefinition.Builder("duplicate.pin", "Duplicate Pin", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinDefinition(PinId.of("value"), "Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING))
            .input(new NodeDefinition.PinDefinition(PinId.of("value"), "Value Copy", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING))
            .build();

        NodeDefinitionValidator.ValidationResult duplicateResult = new NodeDefinitionValidator(null, true).validate(duplicate);

        assertTrue(duplicateResult.errors().contains("Duplicate pin ID: value"));

        NodeDefinition mismatched = new NodeDefinition.Builder("mismatched.pin", "Mismatched Pin", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinDefinition(PinId.of("result"), "Result", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING))
            .build();

        NodeDefinitionValidator.ValidationResult mismatchResult = new NodeDefinitionValidator(null, true).validate(mismatched);

        assertTrue(mismatchResult.errors().stream().anyMatch(error -> error.contains("mismatched direction")));
    }

    @Test
    void validatorAllowsInputAndOutputIdentitiesToOverlap() {
        NodeDefinition definition = new NodeDefinition.Builder("directional.identities", "Directional Identities", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("value"), "Input Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("shared").build())
            .output(new NodeDefinition.PinBuilder(PinId.of("value"), "Output Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING).runtimeName("shared").build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.valid(), result.errors().toString());
    }

    @Test
    void validatorRejectsDuplicateRuntimeNamesWithinDirection() {
        NodeDefinition definition = new NodeDefinition.Builder("duplicate.runtime", "Duplicate Runtime", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("first"), "First", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("shared").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("second"), "Second", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("shared").build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.errors().contains("Duplicate input pin runtime name: shared"));
    }

    @Test
    void validatorRejectsStableIdAndRuntimeNameCollisionWithinDirection() {
        NodeDefinition definition = new NodeDefinition.Builder("cross.identity", "Cross Identity", NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("stable"), "Stable", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("runtime").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("runtime"), "Runtime", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("other").build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.errors().stream().anyMatch(error ->
            error.contains("Pin identity collision in input pins: runtime")
                && error.contains("stable ID")
                && error.contains("runtime name")));
    }

    @Test
    void validatorRejectsStableIdentityInsideRepeatableExpansionNamespace() {
        NodeDefinition definition = new NodeDefinition.Builder("repeatable.stable.identity", "Repeatable Stable Identity",
            NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("values"), "Values", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("value")
                .repeatable("values", 0, 3, "Value").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("values_1"), "First Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("first_value").build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.errors().stream().anyMatch(error ->
            error.contains("values_1") && error.contains("repeatable stable ID expansion") && error.contains("index 1")));
    }

    @Test
    void validatorRejectsRuntimeIdentityInsideRepeatableExpansionNamespace() {
        NodeDefinition definition = new NodeDefinition.Builder("repeatable.runtime.identity", "Repeatable Runtime Identity",
            NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("values"), "Values", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("value")
                .repeatable("values", 1, 3, "Value").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("other"), "Other", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("value_3").build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.errors().stream().anyMatch(error ->
            error.contains("value_3") && error.contains("repeatable runtime name expansion") && error.contains("index 3")));
    }

    @Test
    void validatorAllowsIdentityOutsideRepeatableExpansionRange() {
        NodeDefinition definition = new NodeDefinition.Builder("repeatable.identity.range", "Repeatable Identity Range",
            NodeDefinition.NodeCategory.DATA)
            .handler("test")
            .input(new NodeDefinition.PinBuilder(PinId.of("values"), "Values", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).repeatable("values", 0, 2, "Value").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("values_3"), "Third Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.valid(), result.errors().toString());
    }

    private List<NodeDefinition> parseReplacement(String source) {
        return parseReplacement(jsonBytes(source));
    }

    private List<NodeDefinition> parseReplacement(ByteArrayInputStream source) {
        return new NodeDefinitionLoader().parseReplacement(source, "replacement.json");
    }

    private ByteArrayInputStream jsonWithPin(String id, String displayName) {
        return jsonBytes("[{\"id\":\"boundary.node\",\"displayName\":\"Boundary Node\","
            + "\"category\":\"DATA\",\"description\":\"A boundary definition with authored identity.\","
            + "\"domain\":\"automation\",\"family\":\"automation\",\"lifecycle\":\"active\","
            + "\"handlerCapability\":\"boundary.handler\",\"selectorIntent\":\"none\","
            + "\"inspectorIntent\":\"generic\",\"inputs\":[{\"id\":\"" + id + "\","
            + "\"displayName\":\"" + displayName + "\",\"dataType\":\"string\","
            + "\"description\":\"Provides the authored boundary value.\"}]}]");
    }

    private ByteArrayInputStream jsonWithMigration(String pins) {
        return jsonBytes("[{\"id\":\"boundary.node\",\"displayName\":\"Boundary Node\","
            + "\"category\":\"DATA\",\"description\":\"A boundary definition with authored identity.\","
            + "\"domain\":\"automation\",\"family\":\"automation\",\"lifecycle\":\"active\","
            + "\"handlerCapability\":\"boundary.handler\",\"selectorIntent\":\"none\","
            + "\"inspectorIntent\":\"generic\",\"schemaVersion\":2,"
            + "\"migrationMapping\":{\"sourceSchemaVersion\":1,\"targetSchemaVersion\":2,"
            + "\"complete\":true,\"pins\":[" + pins + "]},\"inputs\":["
            + "{\"id\":\"map_a\",\"displayName\":\"Map A\",\"name\":\"mapA\",\"dataType\":\"string\","
            + "\"description\":\"Provides the first map.\"},{\"id\":\"map_b\",\"displayName\":\"Map B\","
            + "\"name\":\"mapB\",\"dataType\":\"string\",\"description\":\"Provides the second map.\"}],"
            + "\"outputs\":[{\"id\":\"output_map\",\"displayName\":\"Map\",\"name\":\"map\","
            + "\"dataType\":\"string\",\"description\":\"Returns the merged map.\"}]}]");
    }

    private ByteArrayInputStream jsonBytes(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }
}
