package restudio.resync.flow.validation;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.ReSyncRuntimeResourceHandler;
import restudio.resync.flow.handler.generic.TextResourceHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionRegistryIntegrityTest {
    @Test
    void malformedDefinitionDoesNotEraseValidSiblings() {
        String json = """
            [
              {"id":"test.first","displayName":"First","category":"UTILITY","handler":"TestHandler"},
              {"id":"test.invalid","displayName":"Invalid","category":"UTILITY","handler":"TestHandler","inputs":[{"name":"value","dataType":"string","options":{"source":"server:test:values"}}]},
              {"id":"test.last","displayName":"Last","category":"UTILITY","handler":"TestHandler"}
            ]
            """;
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        List<NodeDefinition> definitions = loader.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "fixture.json");

        assertEquals(List.of("test.first", "test.last"), definitions.stream().map(NodeDefinition::getId).toList());
        assertEquals(1, loader.getDiagnostics().size());
        NodeDefinitionDiagnostic diagnostic = loader.getDiagnostics().getFirst();
        assertEquals("DEFINITION_PARSE_FAILED", diagnostic.code());
        assertEquals("test.invalid", diagnostic.nodeId());
        assertEquals(1, diagnostic.index());
    }

    @Test
    void runtimeResourceDefinitionsResolveThroughRegisteredProviders() throws Exception {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        register(catalogs, "server:resync:loot_table");
        register(catalogs, "server:resync:trade_profile");
        register(catalogs, "server:resync:npc_definition");
        HandlerRegistry handlers = new HandlerRegistry();
        new ReSyncRuntimeResourceHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        loader.setValidator(new NodeDefinitionValidator(handlers, catalogs, true));
        Path path = Path.of("src", "main", "resources", "nodes", "migrated", "resync_runtime_resources.json");

        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(path)) {
            definitions = loader.parse(input, path.toString());
        }
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        loader.validateAndRegister(definitions, registry, handlers, "json-classpath");

        assertEquals(17, registry.getAllDefinitions().size());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
        assertCategory(registry, "loot.generate", NodeDefinition.NodeCategory.LOOT);
        assertCategory(registry, "trade.open_trades", NodeDefinition.NodeCategory.TRADE);
        assertCategory(registry, "npc.spawn", NodeDefinition.NodeCategory.NPC);
        assertEquals(Set.of("server:resync:loot_table", "server:resync:trade_profile", "server:resync:npc_definition"),
            definitions.stream().flatMap(definition -> definition.getInputs().stream()).map(NodeDefinition.PinDefinition::getOptionsSource)
                .filter(source -> source != null && !source.isBlank()).collect(Collectors.toSet()));
    }

    @Test
    void textResourceDefinitionsResolveThroughRegisteredHandlerOperations() throws Exception {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        register(catalogs, "server:resync:text_template");
        HandlerRegistry handlers = new HandlerRegistry();
        new TextResourceHandler(null).registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        loader.setValidator(new NodeDefinitionValidator(handlers, catalogs, true));
        Path path = Path.of("src", "main", "resources", "nodes", "migrated", "text_resources.json");

        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(path)) {
            definitions = loader.parse(input, path.toString());
        }
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        loader.validateAndRegister(definitions, registry, handlers, "json-classpath");

        assertEquals(Set.of("text.lines", "text.entries", "text.lookup"), registry.getAllDefinitions().values().stream().map(NodeDefinition::getId).collect(Collectors.toSet()));
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
    }

    @Test
    void validatorRejectsCatalogsThatHaveNoProvider() {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String json = """
            {"id":"test.catalog","displayName":"Catalog","category":"UTILITY","handler":"TestHandler","inputs":[{"name":"value","dataType":"string","optionsSource":"server:test:values"}]}
            """;
        NodeDefinition definition = loader.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).getFirst();

        NodeDefinitionValidator.ValidationResult missing = new NodeDefinitionValidator(handlers, catalogs, true).validate(definition);
        register(catalogs, "server:test:values");
        NodeDefinitionValidator.ValidationResult registered = new NodeDefinitionValidator(handlers, catalogs, true).validate(definition);

        assertFalse(missing.valid());
        assertTrue(missing.errors().stream().anyMatch(error -> error.contains("Unknown optionsSource")));
        assertTrue(registered.valid());
    }

    @Test
    void loaderPreservesGenericAndRepeatablePinContracts() {
        String json = """
            {"id":"test.permissions","displayName":"Permissions","category":"PERMISSION","handler":"TestHandler","inputs":[
              {"name":"permission","dataType":"permission","repeatable":{"groupId":"permissions","minItems":1,"maxItems":32,"itemLabel":"Permission"}},
              {"name":"values","dataType":"list<permission>"}
            ]}
            """;
        NodeDefinition definition = new NodeDefinitionLoader()
            .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
            .getFirst();

        NodeDefinition.PinDefinition permission = definition.getInputs().getFirst();
        assertEquals("permission", permission.getTypeRef().getTypeId());
        assertEquals("permissions", permission.getRepeatable().getGroupId());
        assertEquals(32, permission.getRepeatable().getMaxItems());
        assertEquals("list<permission>", definition.getInputs().get(1).getTypeRef().toString());
        assertEquals("builtin", definition.getOwner());
        assertEquals("Returns permissions.", definition.getDescription());
        assertTrue(definition.getTags().containsAll(List.of("test", "permissions", "permission")));
        assertFalse(definition.getExamples().isEmpty());
        assertNotNull(definition.getAvailability());
    }

    @Test
    void destructiveActionsReceiveSafetyAndAuditMetadata() {
        String json = """
            {"id":"resource.delete","displayName":"Delete Resource","category":"UTILITY","handler":"TestHandler","inputs":[
              {"name":"flow","pinType":"FLOW","dataType":"execution"}
            ]}
            """;

        NodeDefinition definition = new NodeDefinitionLoader()
            .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
            .getFirst();

        assertTrue(definition.isDestructive());
        assertEquals("high_impact", definition.getAuditPolicy());
        assertEquals("explicit_flow_intent", definition.getConfirmationPolicy());
    }

    @Test
    void externallyConnectedCapabilitiesReceiveSensitiveAuditMetadata() {
        String json = """
            {"id":"http.get","displayName":"HTTP Get","category":"UTILITY","handler":"HttpHandler","handlerConfig":{"operation":"http_get"}}
            """;

        NodeDefinition definition = new NodeDefinitionLoader()
            .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
            .getFirst();

        assertTrue(definition.isSensitive());
        assertEquals("sensitive", definition.getAuditPolicy());
    }

    @Test
    void loaderGeneratesConciseDescriptionsWhenDefinitionsOmitThem() {
        String json = """
            [
              {"id":"resource.delete","displayName":"Delete Resource","category":"UTILITY","handler":"TestHandler"},
              {"id":"event.player_join","displayName":"Player Join","category":"EVENT","trigger":true,"eventType":"player_join"}
            ]
            """;

        List<NodeDefinition> definitions = new NodeDefinitionLoader()
            .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals("Deletes resource.", definitions.getFirst().getDescription());
        assertEquals("Runs when the player join event occurs.", definitions.get(1).getDescription());
    }

    @Test
    void deprecatedNodesRequireAReplacementContract() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinition missing = loader.parse(new ByteArrayInputStream("""
            {"id":"legacy.node","displayName":"Legacy Node","category":"UTILITY","handler":"TestHandler","deprecated":true}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinition migrated = loader.parse(new ByteArrayInputStream("""
            {"id":"legacy.node","displayName":"Legacy Node","category":"UTILITY","handler":"TestHandler","deprecated":true,"canonicalId":"modern.node","hidden":true}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);

        assertFalse(validator.validate(missing).valid());
        assertTrue(validator.validate(migrated).valid());
    }

    @Test
    void temporalNodesRequireAnExplicitClockDomain() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TimeHandler", (context, node) -> {
        });
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinition declared = loader.parse(new ByteArrayInputStream("""
            {"id":"time.now","displayName":"Now","category":"DATA","handler":"TimeHandler","clockDomain":"wall_time"}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinition missing = loader.parse(new ByteArrayInputStream("""
            {"id":"time.unknown","displayName":"Unknown Time","category":"DATA","handler":"TimeHandler"}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);

        assertEquals("wall_time", declared.getClockDomain());
        assertTrue(validator.validate(declared).valid());
        assertFalse(validator.validate(missing).valid());
    }

    @Test
    void collectionPinsRequireExplicitGenericArguments() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinition raw = loader.parse(new ByteArrayInputStream("""
            {"id":"list.raw","displayName":"Raw List","category":"DATA","handler":"TestHandler","outputs":[{"name":"values","dataType":"list"}]}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinition typed = loader.parse(new ByteArrayInputStream("""
            {"id":"list.typed","displayName":"Typed List","category":"DATA","handler":"TestHandler","outputs":[{"name":"values","dataType":"list<any>"}]}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);

        assertFalse(validator.validate(raw).valid());
        assertTrue(validator.validate(typed).valid());
    }

    @Test
    void genericTypeVariablesRemainAvailableToTheRuntimeValidator() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        NodeDefinition definition = new NodeDefinitionLoader().parse(new ByteArrayInputStream("""
            {"id":"list.first","displayName":"First","category":"DATA","handler":"TestHandler",
             "inputs":[{"name":"values","dataType":"list<type:t>"}],
             "outputs":[{"name":"value","dataType":"type:t"}]}
            """.getBytes(StandardCharsets.UTF_8))).getFirst();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(handlers, true).validate(definition);

        assertTrue(result.valid());
        assertEquals("list<type:t>", definition.getInputs().getFirst().getTypeRef().toString());
        assertEquals("type:t", definition.getOutputs().getFirst().getTypeRef().toString());
    }

    @Test
    void customContentStartNodesDeclareFlowInputsWithoutDesignerConfiguration() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        registry.registerAll("production", new NodeDefinitionLoader().loadFromClasspath("nodes"));
        Set<String> persistedFields = Set.of("content_id", "name", "material", "provider", "external_id", "custom_model_data", "components", "lore", "tags",
            "enabled", "priority", "cooldown_scope", "cooldown_ticks", "permission", "cancel_event", "consume_event", "require_sneaking",
            "require_on_ground", "allowed_worlds", "denied_worlds", "chance_percent", "max_activations_per_tick");

        for (String nodeId : List.of("custom_content.item", "custom_content.armor", "custom_content.block", "custom_content.projectile")) {
            NodeDefinition definition = registry.get(nodeId);
            assertNotNull(definition);
            Set<String> inputs = definition.getInputs().stream().map(NodeDefinition.PinDefinition::getName).collect(Collectors.toSet());
            assertTrue(inputs.containsAll(persistedFields), nodeId + " is missing " + persistedFields.stream().filter(field -> !inputs.contains(field)).toList());
            assertFalse(inputs.contains("armor_slot"));
        }
    }

    private void assertCategory(NodeDefinitionRegistry registry, String nodeId, NodeDefinition.NodeCategory category) {
        NodeDefinition definition = registry.get(nodeId);
        assertNotNull(definition);
        assertEquals(category, definition.getCategory());
    }

    private void register(OptionCatalogRegistry registry, String sourceId) {
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String revision() {
                return "test";
            }

            @Override
            public List<String> values() {
                return List.of();
            }
        });
    }
}
