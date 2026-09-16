package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.canonical.CanonicalJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FlowGraphMigrationSchemaGeneratorTest {
    @TempDir
    Path temporary;

    @Test
    void duplicateDefinitionsFailClosed() throws Exception {
        Files.writeString(temporary.resolve("first.json"), "[{\"id\":\"same\"},{\"id\":\"other\"}]");
        Files.writeString(temporary.resolve("second.json"), "{\"id\":\"same\"}");

        assertThrows(IllegalArgumentException.class, () -> FlowGraphMigrationSchemaGenerator.generate(temporary));
    }

    @Test
    void invalidPinTypeExpressionFailsClosed() throws Exception {
        Files.writeString(temporary.resolve("invalid.json"),
            "{\"id\":\"invalid\",\"inputs\":[{\"name\":\"value\",\"dataType\":\"list<\"}]}");

        assertThrows(IllegalArgumentException.class, () -> FlowGraphMigrationSchemaGenerator.generate(temporary));
    }

    @Test
    void worldGenSelectorMatchesCanonicalSemanticType() throws Exception {
        Files.writeString(temporary.resolve("worldgen.json"), """
            {
              "id":"worldgen.selector",
              "inputs":[{
                "name":"project",
                "dataType":"worldgen_id",
                "optionsSource":"server:resync:worldgen"
              }]
            }
            """);

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema = FlowGraphMigrationSchemaGenerator.generate(temporary);
        Map<String, Object> node = object(object(schema.value().get("nodes")).get("worldgen.selector"));
        assertEquals("worldgen_project", object(node.get("inputTypes")).get("project"));
        Map<String, Object> inputs = object(object(node.get("normalization")).get("inputs"));
        assertEquals("worldgen_project", object(inputs.get("project")).get("type"));
    }

    @Test
    void authoredPinMappingsSetGeneratedAuthorityVersionToTarget() throws Exception {
        Path sourceRoot = temporary.resolve("migrated");
        Path authoredRoot = temporary.resolve("authored");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(authoredRoot);
        Files.writeString(sourceRoot.resolve("legacy.json"),
            "{\"id\":\"legacy\",\"schemaVersion\":1,\"inputs\":[{\"name\":\"old\"}]}");
        Files.writeString(authoredRoot.resolve("current.json"), """
            {
              "id":"current",
              "schemaVersion":2,
              "legacyIds":["legacy"],
              "migrationMapping":{
                "sourceSchemaVersion":1,
                "targetSchemaVersion":2,
                "complete":true,
                "pins":[{"direction":"input","source":"old","target":"new"}]
              }
            }
            """);

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema =
            FlowGraphMigrationSchemaGenerator.generate(sourceRoot, authoredRoot);
        Map<String, Object> node = object(object(schema.value().get("nodes")).get("legacy"));
        assertEquals(2, node.get("version"));
        Map<String, Object> mapping = object(list(node.get("pinMappings")).getFirst());
        assertEquals(1, mapping.get("sourceSchemaVersion"));
        assertEquals(2, mapping.get("targetSchemaVersion"));
    }

    @Test
    void authoredPinMappingsProjectToEachLegacyAliasByRawWireKeyAndDirection() throws Exception {
        Path sourceRoot = temporary.resolve("projected-migrated");
        Path authoredRoot = temporary.resolve("projected-authored");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(authoredRoot);
        Files.writeString(sourceRoot.resolve("legacy.json"), """
            [
              {
                "id":"legacy.input",
                "schemaVersion":1,
                "inputs":[{"id":"input_id","name":"camelInput"}]
              },
              {
                "id":"legacy.output",
                "schemaVersion":1,
                "outputs":[{"id":"output_id","name":"camelOutput"}]
              }
            ]
            """);
        Files.writeString(authoredRoot.resolve("current.json"), """
            {
              "id":"current",
              "schemaVersion":2,
              "legacyIds":["legacy.input","legacy.output"],
              "migrationMapping":{
                "sourceSchemaVersion":1,
                "targetSchemaVersion":2,
                "complete":true,
                "pins":[
                  {"direction":"input","source":"camelInput","target":"canonical_input"},
                  {"direction":"output","source":"camelOutput","target":"canonical_output"}
                ]
              }
            }
            """);

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema =
            FlowGraphMigrationSchemaGenerator.generate(sourceRoot, authoredRoot);
        Map<String, Object> nodes = object(schema.value().get("nodes"));
        assertEquals(List.of(pinMapping("input", "camelInput", "canonical_input")),
            list(object(nodes.get("legacy.input")).get("pinMappings")));
        assertEquals(List.of(pinMapping("output", "camelOutput", "canonical_output")),
            list(object(nodes.get("legacy.output")).get("pinMappings")));
    }

    @Test
    void emptyLegacyIdsResolveToCanonicalAuthoredId() throws Exception {
        Path sourceRoot = temporary.resolve("canonical-migrated");
        Path authoredRoot = temporary.resolve("canonical-authored");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(authoredRoot);
        Files.writeString(sourceRoot.resolve("canonical.json"),
            "{\"id\":\"canonical\",\"schemaVersion\":1,\"inputs\":[{\"name\":\"old\"}]}\n");
        Files.writeString(authoredRoot.resolve("canonical.json"), """
            {
              "id":"canonical",
              "schemaVersion":2,
              "legacyIds":[],
              "migrationMapping":{
                "sourceSchemaVersion":1,
                "targetSchemaVersion":2,
                "complete":true,
                "pins":[{"direction":"input","source":"old","target":"new"}]
              }
            }
            """);

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema =
            FlowGraphMigrationSchemaGenerator.generate(sourceRoot, authoredRoot);
        Map<String, Object> node = object(object(schema.value().get("nodes")).get("canonical"));
        assertEquals(2, node.get("version"));
        assertEquals(1, list(node.get("pinMappings")).size());
    }

    @Test
    void canonicalEntityKillReceivesMigrationMappingWhenAliasIsAbsent() throws Exception {
        Path sourceRoot = temporary.resolve("entity-migrated");
        Path authoredRoot = temporary.resolve("entity-authored");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(authoredRoot);
        Files.writeString(sourceRoot.resolve("entity.json"),
            "{\"id\":\"entity.kill\",\"schemaVersion\":1,\"inputs\":[{\"name\":\"flow\"}]}");
        Files.writeString(authoredRoot.resolve("entity.json"), """
            {
              "id":"entity.kill",
              "schemaVersion":2,
              "legacyIds":["entity_kill"],
              "migrationMapping":{
                "sourceSchemaVersion":1,
                "targetSchemaVersion":2,
                "complete":true,
                "pins":[{"direction":"input","source":"flow","target":"input_flow"}]
              }
            }
            """);

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema =
            FlowGraphMigrationSchemaGenerator.generate(sourceRoot, authoredRoot);
        Map<String, Object> nodes = object(schema.value().get("nodes"));
        assertTrue(nodes.containsKey("entity.kill"));
        assertFalse(nodes.containsKey("entity_kill"));
        Map<String, Object> node = object(nodes.get("entity.kill"));
        assertEquals(2, node.get("version"));
        assertEquals(1, list(node.get("pinMappings")).size());
    }

    @Test
    void upgradeDefinitionsHaveStableProvenanceAndSourceIdentity() throws Exception {
        Path sourceRoot = Path.of("src/main/resources/nodes/migrated");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = Path.of("ReSyncUpgrade/src/main/resources/nodes/migrated");
        }

        FlowGraphMigrationSchemaGenerator.GeneratedSchema first = FlowGraphMigrationSchemaGenerator.generate(sourceRoot);
        FlowGraphMigrationSchemaGenerator.GeneratedSchema second = FlowGraphMigrationSchemaGenerator.generate(sourceRoot);
        Map<String, Object> source = object(first.value().get("source"));
        List<?> sourceFiles = list(source.get("files"));

        assertEquals(60, first.sourceFileCount());
        assertEquals(60, source.get("count"));
        assertEquals(60, sourceFiles.size());
        assertEquals("ReSyncUpgrade/src/main/resources/nodes/migrated", source.get("root"));
        assertEquals("compatibility", source.get("mode"));
        assertEquals("b8a58abcf644d99ef4e0cdacd9305ca87e4d32cf27883ec629e78f527d1c0a90", source.get("hash"));
        assertEquals(CanonicalJson.sha256("resync.flow-graph-schema.source", sourceFiles), source.get("hash"));
        assertEquals("fd968793835abbf43c3544fe76cb764cc0579786295ce106215458699b6e6f82", first.value().get("schemaHash"));
        assertArrayEquals(first.bytes(), second.bytes());
    }

    @Test
    void authoredPinMappingsMergeIntoMigratedAuthorityOnly() throws Exception {
        Path sourceRoot = Path.of("ReSyncUpgrade/src/main/resources/nodes/migrated");
        Path authoredRoot = Path.of("src/main/resources/nodes");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = Path.of("src/main/resources/nodes/migrated");
            authoredRoot = Path.of("../src/main/resources/nodes");
        }

        FlowGraphMigrationSchemaGenerator.GeneratedSchema schema = FlowGraphMigrationSchemaGenerator.generate(sourceRoot, authoredRoot);
        Map<String, Object> source = object(schema.value().get("source"));
        Map<String, Object> active = object(source.get("active"));
        assertEquals("src/main/resources/nodes", active.get("root"));
        assertEquals("authored", active.get("mode"));
        assertEquals(80, active.get("count"));
        List<?> activeFiles = list(active.get("files"));
        assertEquals(80, activeFiles.size());
        assertEquals("98d72e4a9921f2888c1a1523736bbf709ea490c7a08592e0cf40e51655916899", active.get("hash"));
        assertEquals(CanonicalJson.sha256("resync.flow-graph-schema.source", activeFiles), active.get("hash"));
        assertEquals(1254, authoredDefinitionCount(authoredRoot));
        assertEquals(1420, schema.nodeCount());
        Map<String, Object> unsigned = new LinkedHashMap<>(schema.value());
        Object schemaHash = unsigned.remove("schemaHash");
        assertEquals(CanonicalJson.sha256("resync.flow-graph-schema.artifact", unsigned), schemaHash);

        List<?> jsonDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("json.json"))).toJava());
        List<String> jsonIds = jsonDefinitions.stream()
            .map(value -> object(value).get("id").toString())
            .sorted()
            .toList();
        assertEquals(List.of(
            "json_create",
            "json_get",
            "json_has",
            "json_keys",
            "json_merge",
            "json_parse",
            "json_set_array",
            "json_to_string"), jsonIds);
        assertEquals(8, jsonIds.size());

        Map<String, Object> nodes = object(schema.value().get("nodes"));
        Map<String, Object> eventCommand = object(nodes.get("event.command"));
        assertEquals(3, ((Number) eventCommand.get("version")).intValue());
        assertEquals(List.of(
            pinMapping("output", "event.command", "event.command", 2, 3),
            pinMapping("output", "event.is_cancelled", "event.is_cancelled", 2, 3),
            pinMapping("output", "event.player", "event.player", 2, 3),
            pinMapping("output", "flow", "flow", 2, 3)),
            list(eventCommand.get("pinMappings")));
        List<?> managedDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("managed_resources.json"))).toJava());
        assertEquals(List.of(
            "resource.reference", "resource.discover", "resource.get", "resource.query", "resource.validate",
            "resource.save", "resource.create", "resource.update", "resource.duplicate", "resource.delete",
            "resource.reload", "resource.apply"), managedDefinitions.stream()
            .map(value -> object(value).get("id").toString())
            .toList());
        for (Object value : managedDefinitions) {
            Map<String, Object> definition = object(value);
            String id = definition.get("id").toString();
            Map<String, Object> authority = object(nodes.get(id));
            assertEquals("deprecated", definition.get("lifecycle"), id);
            assertEquals(2, ((Number) definition.get("schemaVersion")).intValue(), id);
            assertFalse(definition.containsKey("legacyIds"), id);
            if ("resource.reference".equals(id)) {
                assertFalse(definition.containsKey("migrationMapping"), id);
                assertEquals(1, ((Number) authority.get("version")).intValue(), id);
                assertFalse(authority.containsKey("pinMappings"), id);
                continue;
            }
            Map<String, Object> migration = object(definition.get("migrationMapping"));
            int sourceVersion = ((Number) migration.get("sourceSchemaVersion")).intValue();
            int targetVersion = ((Number) migration.get("targetSchemaVersion")).intValue();
            assertEquals(1, sourceVersion, id);
            assertEquals(2, targetVersion, id);
            assertEquals(true, migration.get("complete"), id);
            List<Map<String, Object>> expected = new ArrayList<>();
            for (Object pinValue : list(migration.get("pins"))) {
                Map<String, Object> pin = object(pinValue);
                expected.add(pinMapping(pin.get("direction").toString(), pin.get("source").toString(),
                    pin.get("target").toString(), sourceVersion, targetVersion));
            }
            expected.sort(Comparator.comparing(mapping -> mapping.get("sourcePinId") + "\u0000"
                + mapping.get("targetPinId") + "\u0000" + mapping.get("direction")));
            List<?> mappings = list(authority.get("pinMappings"));
            assertEquals(targetVersion, ((Number) authority.get("version")).intValue(), id);
            assertEquals(expected, mappings, id);
            assertEquals(expected.size(), mappings.stream().distinct().count(), id);
        }

        Map<String, Object> migratedMapSet = object(object(schema.value().get("nodes")).get("map.set"));
        List<?> mappings = list(migratedMapSet.get("pinMappings"));
        assertEquals(4, mappings.size());
        assertEquals(List.of("key->key:input", "map->map:input", "map->output_map:output", "value->value:input"),
            mappings.stream().map(value -> {
                Map<String, Object> mapping = object(value);
                assertEquals(1, mapping.get("sourceSchemaVersion"));
                assertEquals(2, mapping.get("targetSchemaVersion"));
                return mapping.get("sourcePinId") + "->" + mapping.get("targetPinId") + ":" + mapping.get("direction");
            }).toList());
        assertEquals(null, object(schema.value().get("nodes")).get("core.map.set"));

        assertFalse(nodes.containsKey("json_set"));
        assertFalse(nodes.containsKey("json_delete"));
        assertJsonOutputMigration(nodes, "json.parse", List.of("flow", "json_string"), List.of("flow", "object"));
        assertJsonOutputMigration(nodes, "json.to.string", List.of("flow", "object"), List.of("flow", "string"));
        assertJsonOutputMigration(nodes, "json.get", List.of("flow", "object", "path"), List.of("flow", "value"));
        assertJsonOutputMigration(nodes, "json.has", List.of("flow", "object", "path"), List.of("flow", "has"));
        assertJsonOutputMigration(nodes, "json.keys", List.of("flow", "object"), List.of("flow", "keys"));
        assertJsonOutputMigration(nodes, "json.merge", List.of("flow", "object1", "object2"), List.of("flow", "merged"));
        assertJsonOutputMigration(nodes, "json.create", List.of("flow"), List.of("flow", "object"));
        assertJsonOutputMigration(nodes, "json.set.array", List.of("flow", "values"), List.of("flow", "array"));

        List<?> functionDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("function_catalog.json"))).toJava());
        List<List<String>> expectedFunctionIds = List.of(
            List.of("function_list", "function.list"),
            List.of("function_find", "function.find"),
            List.of("function_exists", "function.exists"),
            List.of("function_index", "function.index"),
            List.of("function_at_index", "function.at_index"),
            List.of("function_filter", "function.filter"));
        assertEquals(expectedFunctionIds.size(), functionDefinitions.size());
        List<String> functionIds = new ArrayList<>();
        for (int index = 0; index < expectedFunctionIds.size(); index++) {
            Map<String, Object> definition = object(functionDefinitions.get(index));
            List<String> expected = expectedFunctionIds.get(index);
            String activeId = expected.getFirst();
            String legacyId = expected.get(1);
            functionIds.add(definition.get("id").toString());
            assertEquals(activeId, definition.get("id"));
            assertEquals(2, ((Number) definition.get("schemaVersion")).intValue(), activeId);
            assertEquals(List.of(legacyId), list(definition.get("legacyIds")), activeId);
            assertFalse(definition.containsKey("migrationMapping"), activeId);
            assertFalse(definition.containsKey("pinMappings"), activeId);
            assertTrue(nodes.containsKey(legacyId), legacyId);
            assertFalse(nodes.containsKey(activeId), activeId);
            Map<String, Object> authority = object(nodes.get(legacyId));
            assertEquals(2, ((Number) authority.get("version")).intValue(), legacyId);
            assertFalse(authority.containsKey("pinMappings"), legacyId);
        }
        assertEquals(expectedFunctionIds.stream().map(List::getFirst).toList(), functionIds);
        assertFalse(nodes.containsKey("function_describe"));

        for (String legacyId : List.of("get.location", "world.world_get_by_name", "world.world_get_all")) {
            Map<String, Object> worldSchema = object(nodes.get(legacyId));
            assertFalse(worldSchema.containsKey("pinMappings"), legacyId);
        }
        assertTrue(nodes.containsKey("get.location"));
        assertTrue(nodes.containsKey("world.world_get_by_name"));
        assertTrue(nodes.containsKey("world.world_get_all"));
        assertFalse(nodes.containsKey("get_location"));
        assertFalse(nodes.containsKey("world_get_by_name"));
        assertFalse(nodes.containsKey("world_get_all"));

        for (List<String> uuidIds : List.of(
            List.of("uuid_from_string", "uuid.from.string"),
            List.of("uuid_to_string", "uuid.to.string"),
            List.of("uuid_version", "utility.uuid_version"),
            List.of("uuid_timestamp", "utility.uuid_timestamp"))) {
            assertTrue(nodes.containsKey(uuidIds.get(1)));
            assertFalse(nodes.containsKey(uuidIds.getFirst()));
            assertFalse(object(nodes.get(uuidIds.get(1))).containsKey("pinMappings"), uuidIds.get(1));
        }
        assertTrue(nodes.containsKey("utility.uuid_generate"));
        assertFalse(nodes.containsKey("uuid_generate"));
        assertFalse(object(nodes.get("utility.uuid_generate")).containsKey("pinMappings"));
        assertTrue(nodes.containsKey("uuid.random"));
        assertFalse(nodes.containsKey("uuid_random"));
        assertFalse(object(nodes.get("uuid.random")).containsKey("pinMappings"));

        for (List<String> stringIds : List.of(
            List.of("string_concat", "string.concat"),
            List.of("string_upper", "string.upper"),
            List.of("string_lower", "string.lower"),
            List.of("string_trim", "string.trim"),
            List.of("string_length", "string.length"),
            List.of("string_is_empty", "string.is_empty"),
            List.of("string_is_blank", "string.is_blank"),
            List.of("string_is_numeric", "string.is_numeric"))) {
            assertTrue(nodes.containsKey(stringIds.get(1)));
            assertFalse(nodes.containsKey(stringIds.getFirst()));
            assertFalse(object(nodes.get(stringIds.get(1))).containsKey("pinMappings"), stringIds.get(1));
        }

        for (List<String> mathIds : List.of(
            List.of("math_abs", "math.abs"),
            List.of("math_floor", "math.floor"),
            List.of("math_ceil", "math.ceil"),
            List.of("math_sqrt", "math.sqrt"),
            List.of("math_cbrt", "math.cbrt"),
            List.of("math_signum", "math.signum"),
            List.of("math_to_radians", "math.to_radians"),
            List.of("math_to_degrees", "math.to_degrees"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> stringIds : List.of(
            List.of("string_md5", "string.md5"),
            List.of("string_sha256", "string.sha256"),
            List.of("string_sha512", "string.sha512"),
            List.of("string_is_alpha", "string.is_alpha"),
            List.of("string_is_alphanumeric", "string.is_alphanumeric"),
            List.of("string_is_email", "string.is_email"),
            List.of("string_contains", "string.contains"))) {
            assertTrue(nodes.containsKey(stringIds.get(1)));
            assertFalse(nodes.containsKey(stringIds.getFirst()));
            assertFalse(object(nodes.get(stringIds.get(1))).containsKey("pinMappings"), stringIds.get(1));
        }

        for (List<String> transformIds : List.of(
            List.of("string_starts_with", "string.starts_with"),
            List.of("string_ends_with", "string.ends_with"),
            List.of("string_replace", "string.replace"),
            List.of("string_base64_encode", "string.base64_encode"),
            List.of("string_url_encode", "string.url_encode"),
            List.of("string_join", "string.join"))) {
            assertTrue(nodes.containsKey(transformIds.get(1)));
            assertFalse(nodes.containsKey(transformIds.getFirst()));
            assertFalse(object(nodes.get(transformIds.get(1))).containsKey("pinMappings"), transformIds.get(1));
        }

        for (List<String> mathIds : List.of(
            List.of("math_add", "math.add"),
            List.of("math_subtract", "math.subtract"),
            List.of("math_multiply", "math.multiply"),
            List.of("math_negate", "math.negate"),
            List.of("math_hypotenuse", "math.hypotenuse"),
            List.of("math_sin", "math.sin"),
            List.of("math_cos", "math.cos"),
            List.of("math_tan", "math.tan"),
            List.of("math_atan", "math.atan"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> mathIds : List.of(
            List.of("math_clamp", "math.clamp"),
            List.of("math_lerp", "math.lerp"),
            List.of("math_round", "math.round"),
            List.of("math_asin", "math.asin"),
            List.of("math_acos", "math.acos"),
            List.of("math_atan2", "math.atan2"),
            List.of("math_distance", "math.distance"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> mathIds : List.of(
            List.of("math_min", "math.min"),
            List.of("math_max", "math.max"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> stringIds : List.of(
            List.of("string_pad_left", "string.pad_left"),
            List.of("string_pad_right", "string.pad_right"),
            List.of("string_truncate", "string.truncate"))) {
            assertTrue(nodes.containsKey(stringIds.get(1)));
            assertFalse(nodes.containsKey(stringIds.getFirst()));
            assertFalse(object(nodes.get(stringIds.get(1))).containsKey("pinMappings"), stringIds.get(1));
        }

        for (List<String> mathIds : List.of(
            List.of("math_log", "math.log"),
            List.of("math_log10", "math.log10"),
            List.of("math_pow", "math.pow"),
            List.of("math_power", "math.power"),
            List.of("math_round_decimal", "math.round_decimal"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> stringIds : List.of(
            List.of("string_reverse", "string.reverse"),
            List.of("string_repeat", "string.repeat"),
            List.of("string_levenshtein", "string.levenshtein"))) {
            assertTrue(nodes.containsKey(stringIds.get(1)));
            assertFalse(nodes.containsKey(stringIds.getFirst()));
            assertFalse(object(nodes.get(stringIds.get(1))).containsKey("pinMappings"), stringIds.get(1));
        }

        List<?> stringCaseDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("string_case.json"))).toJava());
        List<List<String>> expectedStringCase = List.of(
            List.of("string_slugify", "string.slugify"),
            List.of("string_camel_case", "string.camel_case"),
            List.of("string_pascal_case", "string.pascal_case"),
            List.of("string_snake_case", "string.snake_case"),
            List.of("string_kebab_case", "string.kebab_case"),
            List.of("string_template", "string.template"),
            List.of("string_capitalize", "string.capitalize"));
        assertEquals(7, stringCaseDefinitions.size());
        List<String> stringCaseIds = new ArrayList<>();
        for (int index = 0; index < expectedStringCase.size(); index++) {
            Map<String, Object> definition = object(stringCaseDefinitions.get(index));
            List<String> expected = expectedStringCase.get(index);
            String activeId = expected.getFirst();
            String legacyId = expected.get(1);
            stringCaseIds.add(definition.get("id").toString());
            assertEquals(activeId, definition.get("id"));
            assertEquals(1, ((Number) definition.get("schemaVersion")).intValue(), activeId);
            assertEquals(List.of(legacyId), list(definition.get("legacyIds")), activeId);
            assertFalse(definition.containsKey("migrationMapping"), activeId);
            assertTrue(nodes.containsKey(legacyId), legacyId);
            assertFalse(nodes.containsKey(activeId), activeId);
            Map<String, Object> authority = object(nodes.get(legacyId));
            assertEquals(1, ((Number) authority.get("version")).intValue(), legacyId);
            assertFalse(authority.containsKey("pinMappings"), legacyId);
        }
        assertEquals(expectedStringCase.stream().map(List::getFirst).toList(), stringCaseIds);
        assertFalse(stringCaseIds.contains("string.shuffle"));

        for (List<String> mathIds : List.of(
            List.of("math_divide", "math.divide"),
            List.of("math_modulo", "math.modulo"))) {
            assertTrue(nodes.containsKey(mathIds.get(1)));
            assertFalse(nodes.containsKey(mathIds.getFirst()));
            assertFalse(object(nodes.get(mathIds.get(1))).containsKey("pinMappings"), mathIds.get(1));
        }

        for (List<String> stringIds : List.of(
            List.of("string_substring", "string.substring"),
            List.of("string_split", "string.split"))) {
            assertTrue(nodes.containsKey(stringIds.get(1)));
            assertFalse(nodes.containsKey(stringIds.getFirst()));
            assertFalse(object(nodes.get(stringIds.get(1))).containsKey("pinMappings"), stringIds.get(1));
        }

        List<?> decodeWrapDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("string_decode_wrap.json"))).toJava());
        List<List<String>> expectedDecodeWrap = List.of(
            List.of("string_base64_decode", "string.base64_decode"),
            List.of("string_url_decode", "string.url_decode"),
            List.of("string_word_wrap", "string.word_wrap"));
        assertEquals(5, decodeWrapDefinitions.size());
        List<String> decodeWrapIds = new ArrayList<>();
        for (int index = 0; index < expectedDecodeWrap.size(); index++) {
            Map<String, Object> definition = object(decodeWrapDefinitions.get(index));
            List<String> expected = expectedDecodeWrap.get(index);
            String activeId = expected.getFirst();
            String legacyId = expected.get(1);
            decodeWrapIds.add(definition.get("id").toString());
            assertEquals(activeId, definition.get("id"));
            assertEquals(1, ((Number) definition.get("schemaVersion")).intValue(), activeId);
            assertEquals(List.of(legacyId), list(definition.get("legacyIds")), activeId);
            assertFalse(definition.containsKey("migrationMapping"), activeId);
            assertFalse(definition.containsKey("pinMappings"), activeId);
            assertTrue(nodes.containsKey(legacyId), legacyId);
            assertFalse(nodes.containsKey(activeId), activeId);
            Map<String, Object> authority = object(nodes.get(legacyId));
            assertEquals(1, ((Number) authority.get("version")).intValue(), legacyId);
            assertFalse(authority.containsKey("pinMappings"), legacyId);
        }
        assertEquals(expectedDecodeWrap.stream().map(List::getFirst).toList(), decodeWrapIds);
        assertFalse(decodeWrapIds.contains("string.replace_regex"));
        assertFalse(decodeWrapIds.contains("string.matches"));

        Map<String, Object> stringToJson = object(decodeWrapDefinitions.get(3));
        assertEquals("string_to_json", stringToJson.get("id"));
        assertEquals(2, ((Number) stringToJson.get("schemaVersion")).intValue());
        assertEquals(List.of("string.to_json"), list(stringToJson.get("legacyIds")));
        assertTrue(nodes.containsKey("string.to_json"));
        assertFalse(nodes.containsKey("string_to_json"));
        assertEquals(1, ((Number) object(nodes.get("string.to_json")).get("version")).intValue());
        assertFalse(object(nodes.get("string.to_json")).containsKey("pinMappings"));

        Map<String, Object> stringFromJson = object(decodeWrapDefinitions.get(4));
        assertEquals("string_from_json", stringFromJson.get("id"));
        assertEquals(2, ((Number) stringFromJson.get("schemaVersion")).intValue());
        assertEquals(List.of("string.from_json"), list(stringFromJson.get("legacyIds")));
        assertTrue(nodes.containsKey("string.from_json"));
        assertFalse(nodes.containsKey("string_from_json"));
        assertEquals(1, ((Number) object(nodes.get("string.from_json")).get("version")).intValue());
        assertFalse(object(nodes.get("string.from_json")).containsKey("pinMappings"));

        for (List<String> colorIds : List.of(
            List.of("color_from_rgb", "color.from.rgb"),
            List.of("color_from_hex", "color.from.hex"),
            List.of("color_to_hex", "color.to.hex"),
            List.of("color_to_rgb", "color.to.rgb"),
            List.of("color_invert", "color.invert"),
            List.of("color_brighten", "color.brighten"),
            List.of("color_darken", "color.darken"),
            List.of("color_blend", "utility.color_blend"),
            List.of("color_random", "utility.color_random"),
            List.of("color_distance", "utility.color_distance"))) {
            assertTrue(nodes.containsKey(colorIds.get(1)));
            assertFalse(nodes.containsKey(colorIds.getFirst()));
            assertFalse(object(nodes.get(colorIds.get(1))).containsKey("pinMappings"), colorIds.get(1));
        }

        for (List<String> listIds : List.of(
            List.of("list_create", "list.create"),
            List.of("list_add", "list.add"),
            List.of("list_remove", "list.remove"),
            List.of("list_remove_at", "list.remove_at"),
            List.of("list_clear", "list.clear"),
            List.of("list_get", "list.get"),
            List.of("list_set", "list.set"),
            List.of("list_size", "list.size"),
            List.of("list_is_empty", "list.is_empty"),
            List.of("list_contains", "list.contains"))) {
            assertTrue(nodes.containsKey(listIds.get(1)));
            assertFalse(nodes.containsKey(listIds.getFirst()));
        }

        for (String legacyId : List.of("list.add", "list.remove", "list.remove_at", "list.clear", "list.set")) {
            assertListOutputMigration(nodes, legacyId);
        }
        for (String legacyId : List.of("list.create", "list.get", "list.size", "list.is_empty", "list.contains")) {
            assertFalse(object(nodes.get(legacyId)).containsKey("pinMappings"), legacyId);
        }

        for (List<String> listQueryIds : List.of(
            List.of("list_index_of", "list.index_of"),
            List.of("list_count", "list.count"),
            List.of("list_first", "list.first"),
            List.of("list_last", "list.last"))) {
            String activeId = listQueryIds.getFirst();
            String legacyId = listQueryIds.get(1);
            assertTrue(nodes.containsKey(legacyId), legacyId);
            assertFalse(nodes.containsKey(activeId), activeId);
            Map<String, Object> authority = object(nodes.get(legacyId));
            assertEquals(1, ((Number) authority.get("version")).intValue(), legacyId);
            assertFalse(authority.containsKey("pinMappings"), legacyId);
        }
        assertFalse(nodes.containsKey("first"));
        assertFalse(nodes.containsKey("last"));

        assertTrue(nodes.containsKey("math.min"));
        assertTrue(nodes.containsKey("math.max"));
        assertTrue(nodes.containsKey("math.min_list"));
        assertTrue(nodes.containsKey("math.max_list"));
        assertFalse(nodes.containsKey("math_min_list"));
        assertFalse(nodes.containsKey("math_max_list"));

        for (List<String> listAggregateIds : List.of(
            List.of("list_sum", "list.sum"),
            List.of("list_average", "list.average"),
            List.of("list_min", "list.min"),
            List.of("list_max", "list.max"))) {
            assertFalse(nodes.containsKey(listAggregateIds.getFirst()));
            assertTrue(nodes.containsKey(listAggregateIds.get(1)));
        }

        List<?> listOrderingDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("list_ordering.json"))).toJava());
        assertEquals(3, listOrderingDefinitions.size());
        Map<String, Object> listSort = object(listOrderingDefinitions.getFirst());
        assertEquals("list_sort", listSort.get("id"));
        assertEquals(1, ((Number) listSort.get("schemaVersion")).intValue());
        assertFalse(listSort.containsKey("migrationMapping"));
        assertTrue(nodes.containsKey("list.sort"));
        assertFalse(nodes.containsKey("list_sort"));
        assertEquals(1, ((Number) object(nodes.get("list.sort")).get("version")).intValue());
        assertFalse(object(nodes.get("list.sort")).containsKey("pinMappings"));

        Map<String, Object> listSortDescending = object(listOrderingDefinitions.get(1));
        assertEquals("list_sort_descending", listSortDescending.get("id"));
        assertEquals(2, ((Number) listSortDescending.get("schemaVersion")).intValue());
        Map<String, Object> listSortDescendingMapping = object(listSortDescending.get("migrationMapping"));
        assertEquals(1, ((Number) listSortDescendingMapping.get("sourceSchemaVersion")).intValue());
        assertEquals(2, ((Number) listSortDescendingMapping.get("targetSchemaVersion")).intValue());
        assertEquals(true, listSortDescendingMapping.get("complete"));
        assertEquals(List.of(
            Map.of("direction", "input", "source", "list", "target", "list"),
            Map.of("direction", "output", "source", "list", "target", "output_list")),
            list(listSortDescendingMapping.get("pins")));
        assertTrue(nodes.containsKey("list.sort_descending"));
        assertFalse(nodes.containsKey("list_sort_descending"));
        Map<String, Object> listSortDescendingAuthority = object(nodes.get("list.sort_descending"));
        assertEquals(2, ((Number) listSortDescendingAuthority.get("version")).intValue());
        assertEquals(List.of(
            pinMapping("input", "list", "list", 1, 2),
            pinMapping("output", "list", "output_list", 1, 2)),
            list(listSortDescendingAuthority.get("pinMappings")));

        Map<String, Object> listSortByProperty = object(listOrderingDefinitions.get(2));
        assertEquals("list_sort_by_property", listSortByProperty.get("id"));
        assertEquals(2, ((Number) listSortByProperty.get("schemaVersion")).intValue());
        assertEquals(List.of("list.sort_by_property"), list(listSortByProperty.get("legacyIds")));
        assertTrue(nodes.containsKey("list.sort_by_property"));
        assertFalse(nodes.containsKey("list_sort_by_property"));
        assertEquals(1, ((Number) object(nodes.get("list.sort_by_property")).get("version")).intValue());
        assertFalse(object(nodes.get("list.sort_by_property")).containsKey("pinMappings"));

        List<?> colorDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("color.json"))).toJava());
        Map<String, Map<String, Object>> colorById = new LinkedHashMap<>();
        for (Object value : colorDefinitions) {
            Map<String, Object> definition = object(value);
            colorById.put(definition.get("id").toString(), definition);
        }
        List<String> colorIds = new ArrayList<>(colorById.keySet());
        assertEquals(List.of(
            "color_from_rgb", "color_from_hex", "color_to_hex", "color_to_rgb", "color_invert",
            "color_brighten", "color_darken", "color_blend", "color_random", "color_distance"), colorIds);
        for (String id : colorIds) {
            assertEquals(2, ((Number) colorById.get(id).get("schemaVersion")).intValue(), id);
        }

        List<?> timeDefinitions = list(CanonicalCodec.decodePermissive(
            Files.readAllBytes(authoredRoot.resolve("time.json"))).toJava());
        Map<String, Map<String, Object>> timeById = new LinkedHashMap<>();
        for (Object value : timeDefinitions) {
            Map<String, Object> definition = object(value);
            timeById.put(definition.get("id").toString(), definition);
        }
        List<String> timeIds = new ArrayList<>(timeById.keySet());
        assertEquals(List.of("time_format", "time_parse", "time_add", "time_diff", "time_to_ticks",
            "time_current", "time_get_current_ticks", "time_get_current_time"), timeIds);
        Map<String, Integer> timeVersions = Map.of(
            "time_format", 3,
            "time_parse", 3,
            "time_add", 4,
            "time_diff", 4,
            "time_to_ticks", 2,
            "time_current", 2,
            "time_get_current_ticks", 2,
            "time_get_current_time", 2);
        for (String id : timeIds) {
            assertEquals(timeVersions.get(id), ((Number) timeById.get(id).get("schemaVersion")).intValue(), id);
        }

        Map<String, Object> authoredTimeAdd = timeById.get("time_add");
        Map<String, Object> authoredTimeAddMapping = object(authoredTimeAdd.get("migrationMapping"));
        assertEquals(3, ((Number) authoredTimeAddMapping.get("sourceSchemaVersion")).intValue());
        assertEquals(4, ((Number) authoredTimeAddMapping.get("targetSchemaVersion")).intValue());
        assertEquals(true, authoredTimeAddMapping.get("complete"));
        assertEquals(List.of(
            Map.of("direction", "input", "source", "time", "target", "time"),
            Map.of("direction", "input", "source", "amount", "target", "amount"),
            Map.of("direction", "input", "source", "unit", "target", "unit"),
            Map.of("direction", "input", "source", "time_zone", "target", "time_zone"),
            Map.of("direction", "output", "source", "time", "target", "output_time"),
            Map.of("direction", "output", "source", "valid", "target", "valid"),
            Map.of("direction", "output", "source", "error", "target", "error")),
            list(authoredTimeAddMapping.get("pins")));
        for (String id : List.of("time_format", "time_parse", "time_diff", "time_to_ticks")) {
            assertFalse(timeById.get(id).containsKey("migrationMapping"), id);
        }

        List<Map<String, Object>> expectedTimeMappings = new ArrayList<>();
        expectedTimeMappings.add(pinMapping("input", "time", "time", 3, 4));
        expectedTimeMappings.add(pinMapping("input", "amount", "amount", 3, 4));
        expectedTimeMappings.add(pinMapping("input", "unit", "unit", 3, 4));
        expectedTimeMappings.add(pinMapping("input", "time_zone", "time_zone", 3, 4));
        expectedTimeMappings.add(pinMapping("output", "time", "output_time", 3, 4));
        expectedTimeMappings.add(pinMapping("output", "valid", "valid", 3, 4));
        expectedTimeMappings.add(pinMapping("output", "error", "error", 3, 4));
        expectedTimeMappings.sort(Comparator.comparing(value -> value.get("sourcePinId") + "\u0000"
            + value.get("targetPinId") + "\u0000" + value.get("direction")));
        assertEquals(expectedTimeMappings, list(object(nodes.get("time.add")).get("pinMappings")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private List<?> list(Object value) {
        return (List<?>) value;
    }

    private int authoredDefinitionCount(Path authoredRoot) throws Exception {
        int count = 0;
        try (var paths = Files.list(authoredRoot)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                Object value = CanonicalCodec.decodePermissive(Files.readAllBytes(path)).toJava();
                count += value instanceof List<?> definitions ? definitions.size() : 1;
            }
        }
        return count;
    }

    private void assertJsonOutputMigration(Map<String, Object> nodes, String legacyId,
                                           List<String> inputPins, List<String> outputPins) {
        List<Map<String, Object>> expected = new ArrayList<>();
        expected.addAll(inputPins.stream().map(pin -> pinMapping("input", pin, pin)).toList());
        expected.addAll(outputPins.stream()
            .map(pin -> pinMapping("output", pin, "flow".equals(pin) ? "output_flow" : pin))
            .toList());
        expected.sort(Comparator.comparing(value -> value.get("sourcePinId") + "\u0000"
            + value.get("targetPinId") + "\u0000" + value.get("direction")));
        assertEquals(expected, list(object(nodes.get(legacyId)).get("pinMappings")), legacyId);
    }

    private Map<String, Object> pinMapping(String direction, String sourcePinId, String targetPinId) {
        return pinMapping(direction, sourcePinId, targetPinId, 1, 2);
    }

    private Map<String, Object> pinMapping(String direction, String sourcePinId, String targetPinId,
                                           int sourceSchemaVersion, int targetSchemaVersion) {
        return Map.of(
            "sourcePinId", sourcePinId,
            "targetPinId", targetPinId,
            "direction", direction,
            "sourceSchemaVersion", sourceSchemaVersion,
            "targetSchemaVersion", targetSchemaVersion);
    }

    private void assertListOutputMigration(Map<String, Object> nodes, String legacyId) {
        List<?> mappings = list(object(nodes.get(legacyId)).get("pinMappings"));
        List<?> outputMappings = mappings.stream()
            .filter(value -> "output".equals(object(value).get("direction")))
            .toList();
        assertEquals(1, outputMappings.size(), legacyId);
        Map<String, Object> mapping = object(outputMappings.getFirst());
        assertEquals("list", mapping.get("sourcePinId"), legacyId);
        assertEquals("output_list", mapping.get("targetPinId"), legacyId);
        assertEquals("output", mapping.get("direction"), legacyId);
        assertEquals(1, mapping.get("sourceSchemaVersion"), legacyId);
        assertEquals(2, mapping.get("targetSchemaVersion"), legacyId);
    }
}
