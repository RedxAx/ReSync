package restudio.resync.replacement.evidence.runtime.c1;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionRuntimeMatrixTest {
    private static final Set<String> REQUIRED = Set.of("sourcePath", "definitionId", "handlerDeclaration", "bindingEvidence", "handlerOperation",
        "threadPolicyEvidence", "effectEvidence", "defaults", "conversionsEvidence", "branchOutputs", "branchSemanticsEvidence", "triggerRoute", "status");

    @Test
    void matrixMatchesEveryEligibleCurrentSourceDefinition() throws Exception {
        JsonObject matrix = resource("definition-runtime-matrix.json");
        Path root = Path.of("src", "main", "resources", "nodes");
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json") && !path.getFileName().toString().startsWith("_"))
                .sorted().toList();
        }
        Map<String, JsonObject> rows = rows(matrix.getAsJsonArray("rows"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int definitions = 0;
        int inputs = 0;
        int outputs = 0;
        int defaults = 0;
        int logicalOmittedInputs = 0;
        for (Path file : files) {
            String sourcePath = root.relativize(file).toString().replace('\\', '/');
            digest.update((sourcePath + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            JsonArray nodes = parsed.isJsonArray() ? parsed.getAsJsonArray() : array(parsed);
            for (JsonElement element : nodes) {
                JsonObject node = element.getAsJsonObject();
                String key = sourcePath + '\u0000' + node.get("id").getAsString();
                JsonObject row = rows.remove(key);
                assertNotNull(row, key);
                assertTrue(row.keySet().containsAll(REQUIRED));
                assertEquals(text(node, "handler"), row.get("handlerDeclaration").getAsString(), key);
                assertEquals(text(node.has("handlerConfig") ? node.getAsJsonObject("handlerConfig") : null, "operation"), row.get("handlerOperation").getAsString(), key);
                JsonArray nodeInputs = node.has("inputs") ? node.getAsJsonArray("inputs") : new JsonArray();
                JsonArray nodeOutputs = node.has("outputs") ? node.getAsJsonArray("outputs") : new JsonArray();
                assertEquals(nodeInputs.size(), row.get("inputPinCount").getAsInt(), key);
                assertEquals(nodeOutputs.size(), row.get("outputPinCount").getAsInt(), key);
                assertEquals(pinNames(nodeInputs, true), stringValues(row.getAsJsonArray("defaults")), key);
                assertEquals(branchNames(nodeOutputs), stringValues(row.getAsJsonArray("branchOutputs")), key);
                definitions++;
                inputs += nodeInputs.size();
                outputs += nodeOutputs.size();
                defaults += pinNames(nodeInputs, true).size();
                if (!node.has("inputs") && Set.of("custom_content.current", "event.custom_content", "logic.logic_true", "logic.logic_false", "map.create")
                    .contains(node.get("id").getAsString())) logicalOmittedInputs++;
            }
        }
        assertTrue(rows.isEmpty(), () -> "Extra matrix rows: " + rows.keySet());
        assertEquals(61, files.size());
        assertEquals(1428, definitions);
        assertEquals(3584, inputs);
        assertEquals(3689, outputs);
        assertEquals(723, defaults);
        assertEquals(5, logicalOmittedInputs);
        assertEquals(hex(digest.digest()), matrix.get("sourceHash").getAsString());
        assertEquals(definitions, matrix.get("definitionCount").getAsInt());
        assertEquals(inputs, matrix.get("physicalInputCount").getAsInt());
        assertEquals(outputs, matrix.get("physicalOutputCount").getAsInt());
        assertEquals(defaults, matrix.get("defaultInputCount").getAsInt());
        assertEquals(logicalOmittedInputs, matrix.get("logicalOmittedInputCount").getAsInt());
    }

    @Test
    void mutableExecutionFixtureStatesTheTwoCurrentBehaviors() {
        JsonObject fixture = resource("mutable-execution-baseline.json");
        assertTrue(fixture.getAsJsonObject("expected").get("handlerResolutionWritesHandlerConfig").getAsBoolean());
        assertTrue(fixture.getAsJsonObject("expected").get("definitionDefaultChangesWithoutGraphLiteral").getAsBoolean());
    }

    private Map<String, JsonObject> rows(JsonArray rows) {
        assertEquals(1428, rows.size());
        Map<String, JsonObject> values = new HashMap<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            assertFalse(row.get("sourcePath").getAsString().isBlank());
            assertTrue(Set.of("unverifiable", "quarantine-required", "proven").contains(row.get("status").getAsString()));
            assertTrue(values.put(row.get("sourcePath").getAsString() + '\u0000' + row.get("definitionId").getAsString(), row) == null);
        }
        return values;
    }

    private JsonArray array(JsonElement value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    private List<String> pinNames(JsonArray pins, boolean defaultsOnly) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : pins) {
            JsonObject pin = element.getAsJsonObject();
            if (!defaultsOnly || pin.has("defaultValue") && !pin.get("defaultValue").isJsonNull()) names.add(pin.get("name").getAsString());
        }
        return names;
    }

    private List<String> stringValues(JsonArray values) {
        List<String> strings = new ArrayList<>();
        for (JsonElement value : values) strings.add(value.getAsString());
        return strings;
    }

    private List<String> branchNames(JsonArray pins) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : pins) {
            String name = element.getAsJsonObject().get("name").getAsString();
            if (name.matches("^(true|false|branch_|case|success|failure|cancel).*")) names.add(name);
        }
        return names;
    }

    private String text(JsonObject value, String field) {
        return value != null && value.has(field) && !value.get(field).isJsonNull() ? value.get(field).getAsString() : "";
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte entry : bytes) value.append(String.format("%02x", entry));
        return value.toString();
    }

    private JsonObject resource(String name) {
        try (var stream = getClass().getResourceAsStream("/fixtures/node-replacement/runtime/c1/" + name)) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
