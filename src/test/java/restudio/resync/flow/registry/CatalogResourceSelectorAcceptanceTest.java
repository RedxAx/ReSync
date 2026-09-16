package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogResourceSelectorAcceptanceTest {
    private static final Path CATALOG_ROOT = Path.of("src", "main", "resources", "nodes");
    private static final String MANIFEST = "/node-replacement/catalog-resource-selector-acceptance.tsv";
    private static Catalog cachedCatalog;
    private static Manifest cachedManifest;
    private static final Set<String> NON_RESOURCE_OPTION_SOURCES = Set.of(
        "server:custom_content:provider",
        "server:luckperms:permission",
        "server:minecraft:advancement",
        "server:minecraft:attribute",
        "server:minecraft:block",
        "server:minecraft:difficulty",
        "server:minecraft:entity_boolean_property",
        "server:minecraft:entity_data_property",
        "server:minecraft:entity_location_property",
        "server:minecraft:entity_number_property",
        "server:minecraft:entity_reference_property",
        "server:minecraft:entity_text_property",
        "server:minecraft:entity_type",
        "server:minecraft:entity_vector_property",
        "server:minecraft:entity_writable_data_property",
        "server:minecraft:item_attribute_schema",
        "server:minecraft:item_component_boolean",
        "server:minecraft:item_component_list",
        "server:minecraft:item_component_number",
        "server:minecraft:item_component_object",
        "server:minecraft:item_component_presence",
        "server:minecraft:item_component_text",
        "server:minecraft:material",
        "server:minecraft:particle",
        "server:minecraft:potion_effect",
        "server:minecraft:sound",
        "server:resync:network_node_status",
        "server:resync:network_scope",
        "server:resync:network_server_group",
        "server:resync:network_variable_type",
        "server:resync:time_zone",
        "server:runtime_data:category",
        "server:runtime_data:source",
        "server:runtime_data:type"
    );

    @Test
    void manifestFreezesTheReviewedGateZeroCatalogEvidence() throws Exception {
        Manifest manifest = manifest();

        assertEquals("1", manifest.metadata().get("schemaVersion"));
        assertEquals("1240", manifest.metadata().get("baselineActiveDescriptors"));
        assertEquals("6748", manifest.metadata().get("baselineActivePins"));
        assertEquals("3440", manifest.metadata().get("baselineActiveInputs"));
        assertEquals("3308", manifest.metadata().get("baselineActiveOutputs"));
        assertEquals("127", manifest.metadata().get("baselineCatalogBackedResourceSelectors"));
        assertEquals("resource<restudio.resync:{type}>", manifest.metadata().get("authoritativeTypeSyntax"));
        assertEquals("true", manifest.metadata().get("legacyColumnsAreEvidenceOnly"));
    }

    @Test
    void manifestResolvesUniqueActiveInputsAndCoversEveryResourceCatalogSource() throws Exception {
        Catalog catalog = catalog();
        Manifest manifest = manifest();
        Set<String> manifestKeys = new HashSet<>();
        List<String> missingRows = new ArrayList<>();

        for (SelectorRow row : manifest.rows()) {
            assertTrue(manifestKeys.add(row.key()), "Duplicate manifest row " + row.key());
            Pin pin = catalog.inputs().get(row.key());
            if (pin == null) {
                missingRows.add(row.key());
                continue;
            }
            assertEquals(row.operation(), pin.operation(), row.key());
            assertEquals("restudio.resync", row.expectedOwner(), row.key());
            assertFalse(row.expectedType().isBlank(), row.key());
            assertFalse(row.runtimeProvider().isBlank(), row.key());
        }

        assertTrue(missingRows.isEmpty(), () -> "Manifest rows missing from the active catalog: " + sorted(missingRows) + "; current inventory=" + catalog.inventory());

        Set<String> unclassified = new TreeSet<>();
        for (Pin pin : catalog.inputs().values()) {
            if (!pin.optionSource().isBlank() && !NON_RESOURCE_OPTION_SOURCES.contains(pin.optionSource()) && !manifestKeys.contains(pin.key())) {
                unclassified.add(pin.key() + "=" + pin.optionSource());
            }
        }
        assertTrue(unclassified.isEmpty(), () -> "Catalog-backed resource selectors missing from the manifest: " + unclassified + "; current inventory=" + catalog.inventory());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorRows")
    void resourceSelectorsUseExactGenericTypesAndBoundSources(SelectorRow row) throws Exception {
        Pin pin = catalog().inputs().get(row.key());
        assertNotNull(pin, row.key());
        List<String> mismatches = mismatches(row, pin);

        assertTrue(mismatches.isEmpty(), () -> row.key() + " operation=" + row.operation() + " owner=" + row.expectedOwner() + " type=" + row.expectedType()
            + " provider=" + row.runtimeProvider() + " legacyType=" + row.legacyType() + " legacyOptionSource=" + value(row.legacyOptionSource())
            + " mismatches=" + mismatches);
    }

    private static Stream<SelectorRow> selectorRows() throws Exception {
        return manifest().rows().stream();
    }

    private static List<String> mismatches(SelectorRow row, Pin pin) {
        List<String> mismatches = new ArrayList<>();
        String exactResourceType = "resource<" + row.expectedOwner() + ":" + row.expectedType() + ">";

        switch (row.intent()) {
            case REFERENCE_SELECTOR, REFERENCE_VALUE -> {
                if (!exactResourceType.equals(pin.dataType())) {
                    if ("string".equalsIgnoreCase(pin.dataType())) {
                        mismatches.add("STRING_RESOURCE_SELECTOR");
                    } else if ("any".equalsIgnoreCase(pin.dataType())) {
                        mismatches.add("ANY_RESOURCE_SELECTOR");
                    } else {
                        mismatches.add("MISSING_EXACT_GENERIC_RESOURCE_TYPE(" + pin.dataType() + " -> " + exactResourceType + ")");
                    }
                }
                if (row.intent() == Intent.REFERENCE_SELECTOR) {
                    if (pin.optionSource().isBlank()) {
                        mismatches.add("MISSING_OPTION_SOURCE(" + row.expectedOptionSource() + ")");
                    } else if (!row.expectedOptionSource().equals(pin.optionSource())) {
                        mismatches.add("WRONG_SOURCE_TYPE_BINDING(" + pin.optionSource() + " -> " + row.expectedOptionSource() + ")");
                    }
                } else if (!pin.optionSource().isBlank()) {
                    mismatches.add("VALUE_REFERENCE_HAS_OPTION_SOURCE(" + pin.optionSource() + ")");
                }
            }
            case FREEFORM_CREATION -> {
                if (!"string".equals(pin.dataType())) {
                    mismatches.add("ACCIDENTAL_RESOURCE_CONVERSION(" + pin.dataType() + ")");
                }
                if (!pin.optionSource().isBlank()) {
                    mismatches.add("ACCIDENTAL_CREATION_OPTION_SOURCE(" + pin.optionSource() + ")");
                }
            }
            case MIXED -> mismatches.add("MIXED_CREATION_AND_REFERENCE_TARGET_REQUIRES_SPLIT");
        }
        return mismatches;
    }

    private static synchronized Catalog catalog() throws Exception {
        if (cachedCatalog != null) {
            return cachedCatalog;
        }
        Map<String, Pin> inputs = new HashMap<>();
        int descriptors = 0;
        int inputCount = 0;
        int outputCount = 0;
        try (Stream<Path> files = Files.list(CATALOG_ROOT)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                Iterable<JsonElement> descriptorsInFile = root.isJsonArray() ? root.getAsJsonArray() : List.of(root);
                for (JsonElement element : descriptorsInFile) {
                    JsonObject descriptor = element.getAsJsonObject();
                    if (!"active".equals(text(descriptor, "lifecycle"))) {
                        continue;
                    }
                    descriptors++;
                    String id = text(descriptor, "id");
                    JsonObject handlerConfig = object(descriptor, "handlerConfig");
                    String operation = handlerConfig == null || text(handlerConfig, "operation").isBlank() ? id : text(handlerConfig, "operation");
                    JsonArray descriptorInputs = array(descriptor, "inputs");
                    JsonArray descriptorOutputs = array(descriptor, "outputs");
                    inputCount += descriptorInputs.size();
                    outputCount += descriptorOutputs.size();
                    for (JsonElement input : descriptorInputs) {
                        JsonObject pin = input.getAsJsonObject();
                        String pinId = text(pin, "id");
                        Pin previous = inputs.put(id + "|" + pinId, new Pin(id, pinId, operation, text(pin, "dataType"), text(pin, "optionsSource")));
                        assertTrue(previous == null, "Duplicate active input " + id + "|" + pinId);
                    }
                }
            }
        }
        cachedCatalog = new Catalog(Map.copyOf(inputs), new Inventory(descriptors, inputCount + outputCount, inputCount, outputCount));
        return cachedCatalog;
    }

    private static synchronized Manifest manifest() throws Exception {
        if (cachedManifest != null) {
            return cachedManifest;
        }
        InputStream stream = CatalogResourceSelectorAcceptanceTest.class.getResourceAsStream(MANIFEST);
        assertNotNull(stream, MANIFEST);
        Map<String, String> metadata = new LinkedHashMap<>();
        List<SelectorRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("# ")) {
                    String[] entry = line.substring(2).split("=", 2);
                    metadata.put(entry[0], entry[1]);
                    continue;
                }
                if (line.startsWith("descriptor\t")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                assertEquals(11, fields.length, "Malformed manifest row " + line);
                rows.add(new SelectorRow(fields[0], fields[1], fields[2], Intent.valueOf(fields[3]), fields[4], fields[5], fields[6], fields[7], fields[8], fields[9], fields[10]));
            }
        }
        cachedManifest = new Manifest(Map.copyOf(metadata), List.copyOf(rows));
        return cachedManifest;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : null;
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String sorted(List<String> values) {
        return new TreeSet<>(values).toString();
    }

    private static String value(String value) {
        return value.isBlank() ? "<none>" : value;
    }

    private enum Intent {
        REFERENCE_SELECTOR,
        REFERENCE_VALUE,
        FREEFORM_CREATION,
        MIXED
    }

    private record SelectorRow(
        String descriptor,
        String pin,
        String operation,
        Intent intent,
        String expectedOptionSource,
        String expectedOwner,
        String expectedType,
        String runtimeProvider,
        String legacyType,
        String legacyOptionSource,
        String note
    ) {
        String key() {
            return descriptor + "|" + pin;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    private record Pin(String descriptor, String pin, String operation, String dataType, String optionSource) {
        String key() {
            return descriptor + "|" + pin;
        }
    }

    private record Inventory(int activeDescriptors, int activePins, int activeInputs, int activeOutputs) {
    }

    private record Catalog(Map<String, Pin> inputs, Inventory inventory) {
    }

    private record Manifest(Map<String, String> metadata, List<SelectorRow> rows) {
    }
}
