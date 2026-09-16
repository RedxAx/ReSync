package restudio.resync.flow.migration;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.lifecycle.AutomationMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TriggerCommandMigrationAdapter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowNodeMigrationMapCompatibilityTest {
    private static final Map<String, String> RETIRING_AUTOMATION_TARGETS = Map.of(
        "variable.access", "automation.variable",
        "schedule.schedule", "automation.schedule",
        "schedule.schedule_repeating", "automation.schedule",
        "schedule.cron", "automation.schedule",
        "schedule.at.time", "automation.schedule",
        "schedule.interval", "automation.schedule");
    private static final Set<String> AUTOMATION_MIGRATION_TARGETS = Set.of(
        "variable.access",
        "schedule.schedule",
        "schedule.schedule_repeating",
        "schedule.cron",
        "schedule.at.time");

    @Test
    void migrationTargetsAreActiveOrBackedByRegisteredLifecycleMigration() {
        List<NodeDefinition> definitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");
        Set<String> definitionIds = definitions.stream().map(NodeDefinition::getId).collect(Collectors.toSet());
        Set<String> activeIds = definitions.stream()
            .filter(definition -> definition.getAuthoredMetadata() != null)
            .filter(definition -> "active".equals(definition.getAuthoredMetadata().lifecycle()))
            .map(NodeDefinition::getId)
            .collect(Collectors.toSet());
        Set<String> nonAliasIds = definitions.stream()
            .filter(definition -> definition.getAuthoredMetadata() != null)
            .filter(definition -> definition.getKind() != NodeDefinition.NodeKind.ALIAS)
            .map(NodeDefinition::getId)
            .collect(Collectors.toSet());
        Map<String, NodeDefinition> definitionsById = definitions.stream()
            .collect(Collectors.toMap(NodeDefinition::getId, definition -> definition));
        Map<String, String> migration = FlowNodeMigrationMap.load();

        assertTrue(migration.values().stream().filter(id -> !AUTOMATION_MIGRATION_TARGETS.contains(id))
            .allMatch(activeIds::contains), migration.toString());
        assertTrue(AUTOMATION_MIGRATION_TARGETS.stream().allMatch(migration.values()::contains), migration.toString());
        RETIRING_AUTOMATION_TARGETS.forEach((id, replacement) -> {
            NodeDefinition definition = definitionsById.get(id);
            assertEquals("retiring", definition.getAuthoredMetadata().lifecycle(), id);
            assertEquals(replacement, definition.getReplacementFor(), id);
            assertTrue(definition.isHidden(), id);
            assertTrue(definition.isDeprecated(), id);
            assertTrue(activeIds.contains(replacement), replacement);
        });
        assertTrue(migration.values().stream().allMatch(nonAliasIds::contains), migration.toString());
        assertTrue(migration.keySet().stream().noneMatch(definitionIds::contains), migration.toString());
        assertTrue(ReSyncTypedLifecycleUpgrade.production().adapters().stream()
            .anyMatch(adapter -> AutomationMigrationAdapter.ID.equals(adapter.adapterId())));
        assertFalse(ReSyncTypedLifecycleUpgrade.production().adapters().stream()
            .anyMatch(adapter -> TriggerCommandMigrationAdapter.ID.equals(adapter.adapterId())));
    }

    @Test
    void removedRuntimeAliasTargetsUseCanonicalDefinitions() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("perm_check", "permission.perm_has"),
            Map.entry("perm_grant", "permission.perm_add"),
            Map.entry("perm_revoke", "permission.perm_remove"),
            Map.entry("eco_deposit", "eco.add.balance"),
            Map.entry("eco_withdraw", "eco.remove.balance"),
            Map.entry("placeholder_set_relational", "placeholder.placeholder_set"),
            Map.entry("server_broadcast", "server.system_broadcast"),
            Map.entry("server_restart", "server.system_restart"),
            Map.entry("server_shutdown", "server.system_shutdown"),
            Map.entry("inventory_get_contents", "inventory.properties"),
            Map.entry("item_get_nbt", "itemstack.properties"),
            Map.entry("player_has_item", "inventory.actions"),
            Map.entry("particle_spawn", "particle.apply"),
            Map.entry("list.chunk", "list_chunk"),
            Map.entry("math.random_range", "math_random_range"));
        Map<String, String> migration = FlowNodeMigrationMap.load();

        expected.forEach((legacyId, activeId) -> assertEquals(activeId, migration.get(legacyId)));
    }

    @Test
    void retiredHttpTimeoutMapsToRequest() {
        NodeDefinition retired = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes").stream()
            .filter(definition -> "http_set_timeout".equals(definition.getId()))
            .findFirst()
            .orElseThrow();
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        assertEquals("retiring", retired.getAuthoredMetadata().lifecycle());
        assertEquals("http_request", retired.getReplacementFor());
        assertEquals(List.of("http.set.timeout"), retired.getLegacyIds());
        assertEquals("http_request", migration.get("http.set.timeout"));
        assertFalse(migration.containsKey("http_request"));
        assertFalse(migration.containsValue("http_set_timeout"));
        assertEquals("http_request", compatibility.mapToNew("http.set.timeout"));
        assertEquals("http_request", compatibility.mapToNew("http_request"));
    }

    @Test
    void retiredWorldLookupIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "get.location", "get_location",
            "world.world_get_by_name", "world_get_by_name",
            "world.world_get_all", "world_get_all");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredWorldPropertyIdsMapToCanonicalFamily() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("world.properties", "world_properties"),
            Map.entry("world.time", "world_properties"),
            Map.entry("world.full_time", "world_properties"),
            Map.entry("world.weather", "world_properties"),
            Map.entry("world.has_storm", "world_properties"),
            Map.entry("world.difficulty", "world_properties"),
            Map.entry("world.seed", "world_properties"),
            Map.entry("world.name", "world_properties"),
            Map.entry("world.environment", "world_properties"),
            Map.entry("world.players", "world_properties"),
            Map.entry("world.entities", "world_properties"),
            Map.entry("world.pvp", "world_properties"),
            Map.entry("world.auto_save", "world_properties"),
            Map.entry("world.keep_spawn", "world_properties"),
            Map.entry("world.thundering", "world_properties"),
            Map.entry("world.weather_type", "world_properties"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void unsupportedWorldPropertyWrappersRemainTerminal() {
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        Set.of(
            "world.actions",
            "world.is_thundering",
            "world.spawn_location",
            "world.loaded_chunks",
            "world.sea_level",
            "world.min_height",
            "world.max_height",
            "world.time_relative").forEach(retiredId -> {
                assertFalse(migration.containsKey(retiredId));
                assertFalse(migration.containsValue(retiredId));
                assertEquals(retiredId, compatibility.mapToNew(retiredId));
            });
    }

    @Test
    void retiredStringIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.concat", "string_concat",
            "string.upper", "string_upper",
            "string.lower", "string_lower",
            "string.trim", "string_trim",
            "string.length", "string_length",
            "string.is_empty", "string_is_empty",
            "string.is_blank", "string_is_blank",
            "string.is_numeric", "string_is_numeric");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredStringHashAndPredicateIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.md5", "string_md5",
            "string.sha256", "string_sha256",
            "string.sha512", "string_sha512",
            "string.is_alpha", "string_is_alpha",
            "string.is_alphanumeric", "string_is_alphanumeric",
            "string.is_email", "string_is_email",
            "string.contains", "string_contains");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredStringTransformIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.base64_encode", "string_base64_encode",
            "string.url_encode", "string_url_encode",
            "string.join", "string_join",
            "string.replace", "string_replace",
            "string.starts_with", "string_starts_with",
            "string.ends_with", "string_ends_with");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredStringDecodeAndWrappingIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.base64_decode", "string_base64_decode",
            "string.url_decode", "string_url_decode",
            "string.word_wrap", "string_word_wrap");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredStringCaseAndTemplateIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.slugify", "string_slugify",
            "string.camel_case", "string_camel_case",
            "string.pascal_case", "string_pascal_case",
            "string.snake_case", "string_snake_case",
            "string.kebab_case", "string_kebab_case",
            "string.template", "string_template",
            "string.capitalize", "string_capitalize");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredMathIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("math.abs", "math_abs"),
            Map.entry("math.clamp", "math_clamp"),
            Map.entry("math.lerp", "math_lerp"),
            Map.entry("math.round", "math_round"),
            Map.entry("math.floor", "math_floor"),
            Map.entry("math.ceil", "math_ceil"),
            Map.entry("math.sqrt", "math_sqrt"),
            Map.entry("math.cbrt", "math_cbrt"),
            Map.entry("math.signum", "math_signum"),
            Map.entry("math.to_radians", "math_to_radians"),
            Map.entry("math.to_degrees", "math_to_degrees"),
            Map.entry("math.hypotenuse", "math_hypotenuse"),
            Map.entry("math.sin", "math_sin"),
            Map.entry("math.cos", "math_cos"),
            Map.entry("math.tan", "math_tan"),
            Map.entry("math.asin", "math_asin"),
            Map.entry("math.acos", "math_acos"),
            Map.entry("math.atan", "math_atan"),
            Map.entry("math.atan2", "math_atan2"),
            Map.entry("math.add", "math_add"),
            Map.entry("math.subtract", "math_subtract"),
            Map.entry("math.multiply", "math_multiply"),
            Map.entry("math.negate", "math_negate"),
            Map.entry("math.distance", "math_distance"),
            Map.entry("math.log", "math_log"),
            Map.entry("math.log10", "math_log10"),
            Map.entry("math.pow", "math_pow"),
            Map.entry("math.power", "math_power"),
            Map.entry("math.round_decimal", "math_round_decimal"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredMathExtremumAndStringFormattingIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "math.min", "math_min",
            "math.max", "math_max",
            "string.pad_left", "string_pad_left",
            "string.pad_right", "string_pad_right",
            "string.truncate", "string_truncate");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredDivisionAndStringSliceIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "math.divide", "math_divide",
            "math.modulo", "math_modulo",
            "string.substring", "string_substring",
            "string.split", "string_split");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredTimeIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("time.format", "time_format"),
            Map.entry("time.parse", "time_parse"),
            Map.entry("time.add", "time_add"),
            Map.entry("time.diff", "time_diff"),
            Map.entry("time.time_to_ticks", "time_to_ticks"),
            Map.entry("misc.time_format", "time_format"),
            Map.entry("misc.time_parse", "time_parse"),
            Map.entry("misc.time_add", "time_add"),
            Map.entry("misc.time_diff", "time_diff"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredBasicListIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "list.create", "list_create",
            "list.add", "list_add",
            "list.remove", "list_remove",
            "list.remove_at", "list_remove_at",
            "list.clear", "list_clear",
            "list.get", "list_get",
            "list.set", "list_set",
            "list.size", "list_size",
            "list.is_empty", "list_is_empty",
            "list.contains", "list_contains");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
    }

    @Test
    void retiredListQueryIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("list.index_of", "list_index_of"),
            Map.entry("list.count", "list_count"),
            Map.entry("list.first", "list_first"),
            Map.entry("list.last", "list_last"),
            Map.entry("first", "list_first"),
            Map.entry("last", "list_last"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredListAggregateIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "list.sum", "list_sum",
            "list.average", "list_average",
            "list.min", "list_min",
            "list.max", "list_max");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();
        Set<String> definitionIds = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes").stream()
            .map(NodeDefinition::getId)
            .collect(Collectors.toSet());

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });

        Set.of("list.min_value", "list.max_value").forEach(retiredId -> {
            assertFalse(migration.containsKey(retiredId));
            assertFalse(migration.containsValue(retiredId));
            assertEquals(retiredId, compatibility.mapToNew(retiredId));
        });
        assertFalse(definitionIds.contains("list_min_value"));
        assertFalse(definitionIds.contains("list_max_value"));
    }

    @Test
    void retiredListTransformIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("list.slice", "list_slice"),
            Map.entry("list.reverse", "list_reverse"),
            Map.entry("list.unique", "list_unique"),
            Map.entry("list.flatten", "list_flatten"),
            Map.entry("list.intersect", "list_intersect"),
            Map.entry("list.difference", "list_difference"),
            Map.entry("list.zip", "list_zip"),
            Map.entry("list.concat", "list_concat"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredListOrderingIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "list.sort", "list_sort",
            "list.sort_descending", "list_sort_descending");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void retiredStringAndMathAliasIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "string.reverse", "string_reverse",
            "string.repeat", "string_repeat",
            "string.levenshtein", "string_levenshtein",
            "math.min_list", "math_min",
            "math.max_list", "math_max");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();
        Set<String> definitionIds = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes").stream()
            .map(NodeDefinition::getId)
            .collect(Collectors.toSet());

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
        });
        assertFalse(definitionIds.contains("math_min_list"));
        assertFalse(definitionIds.contains("math_max_list"));
    }

    @Test
    void retiredFunctionCatalogIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.of(
            "function.list", "function_list",
            "function.find", "function_find",
            "function.exists", "function_exists",
            "function.index", "function_index",
            "function.at_index", "function_at_index",
            "function.filter", "function_filter");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertFalse(migration.containsValue(legacyId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void unsupportedFunctionDescribeIdRemainsTerminal() {
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();
        Set<String> definitionIds = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes").stream()
            .map(NodeDefinition::getId)
            .collect(Collectors.toSet());

        assertFalse(migration.containsKey("function.describe"));
        assertFalse(migration.containsValue("function_describe"));
        assertFalse(definitionIds.contains("function_describe"));
        assertEquals("function.describe", compatibility.mapToNew("function.describe"));
        assertEquals("function_describe", compatibility.mapToNew("function_describe"));
    }

    @Test
    void retiredJsonIdsMapOnceToTerminalActiveIds() {
        Map<String, String> expected = Map.of(
            "json.parse", "json_parse",
            "json.to.string", "json_to_string",
            "json.get", "json_get",
            "json.has", "json_has",
            "json.keys", "json_keys",
            "json.merge", "json_merge",
            "json.create", "json_create",
            "json.set.array", "json_set_array");
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }

    @Test
    void unsupportedJsonMutationIdsRemainTerminal() {
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        assertFalse(migration.containsKey("json.set"));
        assertFalse(migration.containsValue("json_set"));
        assertEquals("json.set", compatibility.mapToNew("json.set"));
        assertEquals("json_set", compatibility.mapToNew("json_set"));

        assertFalse(migration.containsKey("json.delete"));
        assertFalse(migration.containsValue("json_delete"));
        assertEquals("json.delete", compatibility.mapToNew("json.delete"));
        assertEquals("json_delete", compatibility.mapToNew("json_delete"));
    }

    @Test
    void retiredColorIdsMapOnceToActiveIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("color.from.rgb", "color_from_rgb"),
            Map.entry("color.from.hex", "color_from_hex"),
            Map.entry("color.to.hex", "color_to_hex"),
            Map.entry("color.to.rgb", "color_to_rgb"),
            Map.entry("color.invert", "color_invert"),
            Map.entry("color.brighten", "color_brighten"),
            Map.entry("color.darken", "color_darken"),
            Map.entry("utility.color_blend", "color_blend"),
            Map.entry("utility.color_random", "color_random"),
            Map.entry("utility.color_distance", "color_distance"),
            Map.entry("color.mix", "color_blend"),
            Map.entry("color_mix", "color_blend"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        IdCompatibilityLayer compatibility = new IdCompatibilityLayer();

        expected.forEach((legacyId, activeId) -> {
            assertEquals(activeId, migration.get(legacyId));
            assertFalse(migration.containsKey(activeId));
            assertEquals(activeId, compatibility.mapToNew(legacyId));
            assertEquals(activeId, compatibility.mapToNew(activeId));
            assertEquals(activeId, compatibility.mapToNew(compatibility.mapToNew(legacyId)));
        });
    }
}
