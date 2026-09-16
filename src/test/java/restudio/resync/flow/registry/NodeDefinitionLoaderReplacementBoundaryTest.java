package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionLoaderReplacementBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void replacementSourceExcludesMigratedDefinitionsWhileCompatibilitySourceRetainsThem() throws Exception {
        Path nodes = temporary.resolve("nodes");
        Files.createDirectories(nodes.resolve("migrated"));
        Files.writeString(nodes.resolve("active.json"), definition("replacement.node"));
        Files.writeString(nodes.resolve("migrated").resolve("legacy.json"), definition("legacy.node"));

        NodeDefinitionLoader replacementLoader = new NodeDefinitionLoader();
        List<NodeDefinition> replacement = replacementLoader.loadReplacementFromDirectory(nodes);

        assertEquals(List.of("replacement.node"), replacement.stream().map(NodeDefinition::getId).toList());
        assertTrue(replacementLoader.getDiagnostics().stream().anyMatch(value -> "CATALOG.LEGACY_ACTIVE".equals(value.code())));

        List<NodeDefinition> compatibility = new NodeDefinitionLoader().loadFromDirectory(nodes);
        assertEquals(List.of("legacy.node", "replacement.node"), compatibility.stream().map(NodeDefinition::getId).sorted().toList());
    }

    @Test
    void replacementSourceRejectsRelativeAndJarMigrationPathLabels() {
        String source = definition("legacy.node");
        for (String sourcePath : List.of("nodes/migrated/legacy.json", "jar:file:/plugins/resync.jar!/nodes/migrated/legacy.json")) {
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), sourcePath);

            assertTrue(definitions.isEmpty(), sourcePath);
            assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.LEGACY_ACTIVE".equals(value.code())), sourcePath);
        }
    }

    @Test
    void replacementStreamRejectsMigrationPathBeforeParsing() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        List<NodeDefinition> definitions = loader.parseReplacement(
            new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8)), "nodes/migrated/stream.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.LEGACY_ACTIVE".equals(value.code())));
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> "FILE_PARSE_FAILED".equals(value.code())));
    }

    @Test
    void replacementClasspathSourceDoesNotExposeMigratedDefinitions() {
        List<NodeDefinition> definitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");

        assertTrue(definitions.stream()
            .map(NodeDefinition::getAuthoredMetadata)
            .map(AuthoredNodeMetadata::sourceProvenance)
            .map(AuthoredSourceProvenance::sourceUri)
            .map(value -> value.replace('\\', '/').toLowerCase(Locale.ROOT))
            .noneMatch(value -> value.startsWith("nodes/migrated/") || value.contains("/nodes/migrated/")));
    }

    @Test
    void authoredAutomationReplacementSourceLoadsWithoutFallbackDiagnostics() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions = loader.loadReplacementFromClasspath("nodes");

        assertTrue(definitions.stream().map(NodeDefinition::getId).toList().containsAll(List.of(
            "automation.variable", "automation.timer", "automation.schedule", "automation.scheduled_task",
            "event.variable.changed", "event.timer", "event.scheduled_task", "event.schedule",
            "if", "loop_while")));
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
        assertTrue(definitions.stream().allMatch(value -> value.getDescription() != null && !value.getDescription().isBlank()));
        assertTrue(definitions.stream().flatMap(value -> value.getInputs().stream()).allMatch(value -> value.getDescription() != null && !value.getDescription().isBlank()));
        assertTrue(definitions.stream().flatMap(value -> value.getOutputs().stream()).allMatch(value -> value.getDescription() != null && !value.getDescription().isBlank()));
        assertTrue(definitions.stream().flatMap(value -> value.getInputs().stream())
            .allMatch(value -> value.getId() != null && !value.getId().value().isBlank()
                && value.getDisplayName() != null && !value.getDisplayName().isBlank()));
        assertTrue(definitions.stream().flatMap(value -> value.getOutputs().stream())
            .allMatch(value -> value.getId() != null && !value.getId().value().isBlank()
                && value.getDisplayName() != null && !value.getDisplayName().isBlank()));
        NodeDefinition variable = definitions.stream().filter(value -> value.getId().equals("automation.variable")).findFirst().orElseThrow();
        assertEquals("flow", variable.getInputs().getFirst().getId().value());
        assertEquals("output_flow", variable.getOutputs().getFirst().getId().value());
        assertEquals("Flow", variable.getInputs().getFirst().getDisplayName());
        assertEquals("Flow", variable.getOutputs().getFirst().getDisplayName());
        assertTrue(definitions.stream().allMatch(value -> {
            AuthoredNodeMetadata metadata = value.getAuthoredMetadata();
            return metadata != null && metadata.id().equals(value.getId()) && !metadata.domain().isBlank()
                && !metadata.family().isBlank() && !metadata.lifecycle().isBlank() && !metadata.description().isBlank()
                && !metadata.handlerCapability().isBlank() && !metadata.selectorIntent().isBlank()
                && !metadata.inspectorIntent().isBlank() && metadata.sourceProvenance() != null
                && metadata.sourceProvenance().rowIndex() >= 0
                && metadata.sourceProvenance().owner().equals(value.getOwner())
                && metadata.sourceProvenance().sourceHash().length() == 64;
        }));
    }

    @Test
    void worldgenOptionSourcePreservesTheAuthoredProjectType() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("worldgen.option")
            .replace("\"outputs\":[]", "\"inputs\":[{\"id\":\"project\",\"displayName\":\"Project\",\"name\":\"project\",\"dataType\":\"worldgen_project\",\"optionsSource\":\"server:resync:worldgen\",\"description\":\"WorldGen project.\"}],\"outputs\":[]");

        List<NodeDefinition> definitions = loader.parseReplacement(
            new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "worldgen-option.json");

        assertEquals(1, definitions.size());
        assertEquals("worldgen_project", definitions.getFirst().getInputs().getFirst().getTypeRef().getTypeId());
    }

    @Test
    void replacementSourceRejectsMissingAuthoredMetadata() {
        for (String field : List.of("id", "domain", "family", "lifecycle", "description", "handlerCapability", "selectorIntent", "inspectorIntent")) {
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            String source = definition("replacement.incomplete").replace("\"" + field + "\":\"automation\",", "");
            if ("id".equals(field)) {
                source = definition("replacement.incomplete").replace("\"id\":\"replacement.incomplete\",", "");
            } else if ("description".equals(field)) {
                source = definition("replacement.incomplete").replace("\"description\":\"A replacement definition used for boundary verification.\",", "");
            } else if ("family".equals(field)) {
                source = definition("replacement.incomplete").replace("\"family\":\"automation\",", "");
            } else if ("lifecycle".equals(field)) {
                source = definition("replacement.incomplete").replace("\"lifecycle\":\"active\",", "");
            } else if ("handlerCapability".equals(field)) {
                source = definition("replacement.incomplete").replace("\"handlerCapability\":\"replacement.handler\",", "");
            } else if ("selectorIntent".equals(field)) {
                source = definition("replacement.incomplete").replace("\"selectorIntent\":\"none\",", "");
            } else if ("inspectorIntent".equals(field)) {
                source = definition("replacement.incomplete").replace("\"inspectorIntent\":\"generic\",", "");
            }
            List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "incomplete.json");

            assertTrue(definitions.isEmpty(), field);
            assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE".equals(value.code())), field);
        }
    }

    @Test
    void replacementSourceRejectsRetiringDefinitionWithoutAuthoredReplacementIdentity() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("replacement.retiring").replace("\"lifecycle\":\"active\",", "\"lifecycle\":\"retiring\",");

        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "retiring.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE".equals(value.code())));
    }

    @Test
    void replacementSourceRetainsAuthoredReplacementIdentityForRetiringDefinition() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("replacement.retiring")
            .replace("\"lifecycle\":\"active\",", "\"lifecycle\":\"retiring\",\"replacementFor\":\"replacement.target\",");

        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "retiring.json");

        assertEquals(1, definitions.size());
        assertEquals("replacement.target", definitions.getFirst().getReplacementFor());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
    }

    @Test
    void replacementSourceRetainsCanonicalReplacementIdentityWhenAuthoredWithWhitespace() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("replacement.retiring")
            .replace("\"lifecycle\":\"active\",", "\"lifecycle\":\"retiring\",\"replacementFor\":\"  replacement.target  \",");

        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "retiring-whitespace.json");

        assertEquals(1, definitions.size());
        assertEquals("replacement.target", definitions.getFirst().getReplacementFor());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
    }

    @Test
    void replacementSourceRejectsMissingAuthoredPinDescription() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("replacement.pin")
            .replace("\"outputs\":[]", "\"outputs\":[{\"name\":\"result\",\"dataType\":\"string\"}]");

        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "pin.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_PIN_DESCRIPTION_MISSING".equals(value.code())));
    }

    @Test
    void replacementSourceRejectsFieldsCoreCannotRepresentWithStableDiagnostic() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = definition("Replacement.Invalid");

        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "invalid.json");

        assertTrue(definitions.isEmpty());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> "CATALOG.REPLACEMENT_SOURCE_UNREPRESENTABLE".equals(value.code())));
    }

    private String definition(String id) {
        return "[{\"id\":\"" + id + "\",\"displayName\":\"" + id + "\",\"category\":\"UTILITY\",\"description\":\"A replacement definition used for boundary verification.\",\"domain\":\"automation\",\"family\":\"automation\",\"lifecycle\":\"active\",\"handlerCapability\":\"replacement.handler\",\"selectorIntent\":\"none\",\"inspectorIntent\":\"generic\",\"outputs\":[]}]";
    }
}
