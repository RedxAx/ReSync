package restudio.resync.flow.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncManagedResource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowGraphValidatorTest {
    private NodeDefinitionRegistry definitions;
    private HandlerRegistry handlers;
    private OptionCatalogRegistry catalogs;
    private FlowGraphValidator validator;

    @BeforeEach
    void setUp() {
        definitions = new NodeDefinitionRegistry();
        handlers = new HandlerRegistry();
        handlers.register("TestHandler", (context, node) -> {
        });
        catalogs = new OptionCatalogRegistry();
        validator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs);
    }

    @Test
    void validDefinitionsPinsHandlersAndTypesPassTogether() {
        definitions.register(query("test.number", FlowDataType.NUMBER));
        definitions.register(action("test.consume", FlowDataType.NUMBER, null));
        FlowGraph graph = graph(Map.of(
            "source", node("test.number"),
            "target", node("test.consume")
        ), List.of(new FlowConnection("source", "value", "target", "value")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void extensionValidatorsRunThroughTheSharedSaveAndExecutionPipeline() {
        FlowGraphValidationRegistry extensionValidators = new FlowGraphValidationRegistry();
        extensionValidators.register("request", "request:quest_graph", graph -> List.of(new FlowGraphDiagnostic(
            FlowGraphDiagnostic.Severity.ERROR, "QUEST_ENTRY_REQUIRED", graph.getId(), "", "", "Quest entry is required", "Add a quest entry")));
        FlowGraphValidator extensionAware = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs, null, extensionValidators);

        FlowGraphValidationResult result = extensionAware.validate(graph(Map.of(), List.of()));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "QUEST_ENTRY_REQUIRED".equals(diagnostic.code())));
    }

    @Test
    void metadataOnlyHandlerDoesNotSatisfyAnAdvertisedOperation() {
        handlers.register("MetadataOnlyHandler", (context, node) -> {
        });
        definitions.register(new NodeDefinition.Builder("test.metadata_only", "Metadata Only", NodeDefinition.NodeCategory.ACTION)
            .handler("MetadataOnlyHandler")
            .handlerConfig(Map.of("operation", "mutate"))
            .build());

        FlowGraphValidationResult result = validator.validate(graph(Map.of("node", node("test.metadata_only")), List.of()));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "HANDLER_OPERATION_UNAVAILABLE"));
    }

    @Test
    void migrationOnlyUnsupportedNodesFailWithAnExplicitCapabilityDiagnostic() {
        NodeDefinition unsupported = new NodeDefinition.Builder("entity.entity_set_wet", "Entity Set Wet", NodeDefinition.NodeCategory.ENTITY)
            .handler("TestHandler")
            .hidden()
            .hiddenReason("migration-only-unsupported-runtime-state")
            .build();
        definitions.register(unsupported);

        FlowGraphValidationResult result = validator.validate(graph(Map.of("wet", node("entity.entity_set_wet")), List.of()));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "NODE_CAPABILITY_UNSUPPORTED".equals(diagnostic.code())));
    }

    @Test
    void missingDefinitionsAndUnknownPinsAreStructuredErrors() {
        definitions.register(action("test.consume", FlowDataType.NUMBER, null));
        FlowGraph graph = graph(Map.of(
            "missing", node("test.missing"),
            "target", node("test.consume")
        ), List.of(new FlowConnection("missing", "stale", "target", "value")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "NODE_DEFINITION_MISSING"));
    }

    @Test
    void templatePlaceholdersAreValidatedAsDynamicDataInputs() {
        definitions.register(query("test.color", FlowDataType.STRING));
        definitions.register(action("test.message", FlowDataType.STRING, null));
        FlowGraph graph = graph(Map.of(
            "source", node("test.color"),
            "target", new FlowNode("test.message", 0, 0, Map.of("value", "Color: {color}"))
        ), List.of(new FlowConnection("source", "value", "target", "color")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void unknownAndEscapedTemplateInputsRemainRejected() {
        definitions.register(query("test.color", FlowDataType.STRING));
        definitions.register(action("test.message", FlowDataType.STRING, null));
        FlowGraph graph = graph(Map.of(
            "source", node("test.color"),
            "target", new FlowNode("test.message", 0, 0, Map.of("value", "Color: {{color}}"))
        ), List.of(new FlowConnection("source", "value", "target", "color")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "TARGET_PIN_UNKNOWN"));
    }

    @Test
    void incompatibleConnectionsAndDataCyclesAreRejected() {
        definitions.register(queryWithInput("test.string", FlowDataType.STRING));
        definitions.register(queryWithInput("test.number", FlowDataType.NUMBER));
        FlowGraph graph = graph(Map.of(
            "string", node("test.string"),
            "number", node("test.number")
        ), List.of(
            new FlowConnection("string", "value", "number", "input"),
            new FlowConnection("number", "value", "string", "input")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "CONNECTION_TYPE_INCOMPATIBLE"));
        assertTrue(hasCode(result, "DATA_DEPENDENCY_CYCLE"));
    }

    @Test
    void executionInputsAcceptMultipleIncomingPaths() {
        definitions.register(executionAction("test.first"));
        definitions.register(executionAction("test.second"));
        definitions.register(executionAction("test.join"));
        FlowGraph graph = graph(Map.of(
            "first", node("test.first"),
            "second", node("test.second"),
            "join", node("test.join")
        ), List.of(
            new FlowConnection("first", "flow", "join", "flow"),
            new FlowConnection("second", "flow", "join", "flow")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
        assertFalse(hasCode(result, "CONNECTION_TARGET_DUPLICATE"));
    }

    @Test
    void dataInputsStillRejectMultipleIncomingValues() {
        definitions.register(query("test.first", FlowDataType.NUMBER));
        definitions.register(query("test.second", FlowDataType.NUMBER));
        definitions.register(action("test.consume", FlowDataType.NUMBER, null));
        FlowGraph graph = graph(Map.of(
            "first", node("test.first"),
            "second", node("test.second"),
            "target", node("test.consume")
        ), List.of(
            new FlowConnection("first", "value", "target", "value"),
            new FlowConnection("second", "value", "target", "value")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "CONNECTION_TARGET_DUPLICATE"));
    }

    @Test
    void unresolvedManagedResourceLiteralIsRejected() {
        definitions.register(action("test.resource", FlowDataType.STRING, "server:resync:fixture:quest"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:fixture:quest";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("existing");
            }
        });
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register("fixture", new FixtureResourceAdapter());
        FlowGraphValidator resourceValidator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs, resources);
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.resource", 0, 0, Map.of("value", "missing"))), List.of());

        FlowGraphValidationResult result = resourceValidator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "RESOURCE_REFERENCE_UNRESOLVED"));
    }

    @Test
    void resyncNamespaceDoesNotImplyManagedResourceSemantics() {
        definitions.register(action("test.time_zone", FlowDataType.STRING, "server:resync:time_zone"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:time_zone";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("UTC");
            }
        });
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.time_zone", 0, 0, Map.of("value", "Invalid/Zone"))), List.of());

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(hasCode(result, "CATALOG_VALUE_UNRESOLVED"));
        assertFalse(hasCode(result, "RESOURCE_REFERENCE_UNRESOLVED"));
    }

    @Test
    void literalsFromAnyAuthoritativeCatalogAreValidated() {
        definitions.register(action("test.catalog", FlowDataType.STRING, "extension:fixture_values"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "extension:fixture_values";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("existing");
            }
        });
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.catalog", 0, 0, Map.of("value", "missing"))), List.of());

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "CATALOG_VALUE_UNRESOLVED"));
    }

    @Test
    void minecraftCatalogValidationAcceptsLegacyEnumCapitalization() {
        definitions.register(action("test.material", FlowDataType.STRING, "server:minecraft:material"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:minecraft:material";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("resin_clump", "stick");
            }
        });
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.material", 0, 0, Map.of("value", "RESIN_CLUMP"))), List.of());

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void effectiveLiteralOverridesCatalogDefault() {
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(
            "value",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).optionsSource("extension:fixture_values").defaultValue("missing-default").build();
        definitions.register(new NodeDefinition.Builder("test.catalog_default", "Catalog Default", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .schemaVersion(2)
            .input(input)
            .build());
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "extension:fixture_values";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("selected");
            }
        });
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.catalog_default", 0, 0, Map.of("value", "selected"))), List.of());

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void hiddenModeInputsDoNotBlockTheActiveMode() {
        NodeDefinition.PinDefinition provider = new NodeDefinition.PinBuilder(
            "provider",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).defaultValue("vanilla").build();
        NodeDefinition.PinDefinition asset = new NodeDefinition.PinBuilder(
            "asset",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).optionsSource("extension:assets").visibleWhen("provider", "nexo").build();
        definitions.register(new NodeDefinition.Builder("test.conditional", "Conditional", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .schemaVersion(2)
            .input(provider)
            .input(asset)
            .build());
        definitions.register(query("test.provider", FlowDataType.STRING));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "extension:assets";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("existing");
            }
        });
        FlowGraph defaultMode = graph(Map.of("target", node("test.conditional")), List.of());
        FlowGraph staleHiddenLiteral = graph(Map.of("target", new FlowNode("test.conditional", 0, 0,
            Map.of("provider", "vanilla", "asset", "forest_axe"))), List.of());
        FlowGraph activeMissing = graph(Map.of("target", new FlowNode("test.conditional", 0, 0, Map.of("provider", "nexo"))), List.of());
        FlowGraph activeUnknown = graph(Map.of("target", new FlowNode("test.conditional", 0, 0,
            Map.of("provider", "nexo", "asset", "forest_axe"))), List.of());
        FlowGraph dynamicMode = graph(Map.of(
            "source", node("test.provider"),
            "target", node("test.conditional")
        ), List.of(new FlowConnection("source", "value", "target", "provider")));

        assertTrue(validator.validate(defaultMode).valid());
        assertTrue(validator.validate(staleHiddenLiteral).valid());
        assertTrue(validator.validate(dynamicMode).valid());
        assertTrue(hasCode(validator.validate(activeMissing), "REQUIRED_INPUT_MISSING"));
        assertTrue(hasCode(validator.validate(activeUnknown), "CATALOG_VALUE_UNRESOLVED"));
    }

    @Test
    void contextualCatalogValidationUsesSiblingInputsAndDefersConnectedContext() {
        NodeDefinition definition = new NodeDefinition.Builder("test.contextual", "Contextual", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(pin("provider", NodeDefinition.PinDirection.INPUT, FlowDataType.STRING, null, false))
            .input(pin("content_type", NodeDefinition.PinDirection.INPUT, FlowDataType.STRING, null, false))
            .input(pin("asset", NodeDefinition.PinDirection.INPUT, FlowDataType.STRING, "extension:assets", false))
            .build();
        definitions.register(definition);
        definitions.register(query("test.provider", FlowDataType.STRING));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "extension:assets";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public Set<String> contextKeys() {
                return Set.of("provider", "content_type");
            }

            @Override
            public String status(OptionCatalogQuery query) {
                return query.text("provider").isBlank() || query.text("content_type").isBlank() ? "invalid" : "available";
            }

            @Override
            public List<String> values() {
                return List.of();
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return List.of(query.text("provider") + ":" + query.text("content_type"));
            }
        });
        FlowGraph valid = graph(Map.of("target", new FlowNode("test.contextual", 0, 0,
            Map.of("provider", "nexo", "content_type", "block", "asset", "nexo:block"))), List.of());
        FlowGraph invalid = graph(Map.of("target", new FlowNode("test.contextual", 0, 0,
            Map.of("provider", "nexo", "content_type", "block", "asset", "nexo:item"))), List.of());
        FlowGraph connected = graph(Map.of(
            "source", node("test.provider"),
            "target", new FlowNode("test.contextual", 0, 0, Map.of("content_type", "block", "asset", "runtime:value"))
        ), List.of(new FlowConnection("source", "value", "target", "provider")));

        assertTrue(validator.validate(valid).valid());
        assertTrue(hasCode(validator.validate(invalid), "CATALOG_VALUE_UNRESOLVED"));
        assertTrue(validator.validate(connected).valid());
    }

    @Test
    void permissionRestrictedCatalogHasADistinctDiagnostic() {
        definitions.register(action("test.restricted", FlowDataType.STRING, "extension:restricted"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "extension:restricted";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public String status(OptionCatalogQuery query) {
                return "permission_restricted";
            }

            @Override
            public List<String> values() {
                return List.of();
            }
        });
        FlowGraph graph = graph(Map.of("target", new FlowNode("test.restricted", 0, 0, Map.of("value", "secret"))), List.of());

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "CATALOG_PERMISSION_RESTRICTED"));
    }

    @Test
    void connectedInputOverridesStaleIncompatibleLiteral() {
        definitions.register(query("test.number", FlowDataType.NUMBER));
        definitions.register(action("test.consume", FlowDataType.NUMBER, null));
        FlowGraph graph = graph(Map.of(
            "source", node("test.number"),
            "target", new FlowNode("test.consume", 0, 0, Map.of("value", Map.of("stale", true)))
        ), List.of(new FlowConnection("source", "value", "target", "value")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
        assertFalse(hasCode(result, "LITERAL_TYPE_INVALID"));
    }

    @Test
    void connectedResourceInputOverridesStaleMissingLiteral() {
        definitions.register(query("test.string", FlowDataType.STRING));
        definitions.register(action("test.resource", FlowDataType.STRING, "server:resync:test_resource"));
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:test_resource";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("existing");
            }
        });
        FlowGraph graph = graph(Map.of(
            "source", node("test.string"),
            "target", new FlowNode("test.resource", 0, 0, Map.of("value", "missing"))
        ), List.of(new FlowConnection("source", "value", "target", "value")));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
        assertFalse(hasCode(result, "RESOURCE_REFERENCE_UNRESOLVED"));
    }

    @Test
    void typedManagedResourceReferenceValidatesKindAndExistenceAgainstRuntimeAuthority() {
        NodeDefinition.PinDefinition resourcePin = new NodeDefinition.PinBuilder(
            "value",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.RESOURCE_REFERENCE
        ).typeRef(FlowTypeRef.parse("resource_reference<fixture:quest>")).build();
        definitions.register(new NodeDefinition.Builder("test.typed_resource", "Typed Resource", NodeDefinition.NodeCategory.ACTION)
            .handler("TestHandler")
            .input(resourcePin)
            .build());
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register("fixture", new FixtureResourceAdapter());
        FlowGraphValidator resourceValidator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs, resources);

        FlowGraph valid = graph(Map.of("target", new FlowNode("test.typed_resource", 0, 0,
            Map.of("value", new FlowResourceReference("fixture:quest", "existing", "fixture")))), List.of());
        FlowGraph missing = graph(Map.of("target", new FlowNode("test.typed_resource", 0, 0,
            Map.of("value", new FlowResourceReference("fixture:quest", "missing", "fixture")))), List.of());
        FlowGraph wrongKind = graph(Map.of("target", new FlowNode("test.typed_resource", 0, 0,
            Map.of("value", new FlowResourceReference("fixture:other", "existing", "fixture")))), List.of());

        assertTrue(resourceValidator.validate(valid).valid());
        assertTrue(hasCode(resourceValidator.validate(missing), "RESOURCE_REFERENCE_UNRESOLVED"));
        assertTrue(hasCode(resourceValidator.validate(wrongKind), "RESOURCE_REFERENCE_KIND_MISMATCH"));
    }

    @Test
    void invalidDefinitionDefaultIsRejectedThroughRuntimeTypeRules() {
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(
            "amount",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.INTEGER
        ).defaultValue("not-a-number").build();
        definitions.register(new NodeDefinition.Builder("test.default", "Test Default", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(input)
            .build());

        FlowGraphValidationResult result = validator.validate(graph(Map.of("node", node("test.default")), List.of()));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "DEFAULT_TYPE_INVALID"));
    }

    @Test
    void numericDefinitionDefaultIsAcceptedForGenericNumberPins() {
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(
            "power",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.NUMBER
        ).defaultValue("4.0").build();
        definitions.register(new NodeDefinition.Builder("test.number_default", "Number Default", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(input)
            .build());

        FlowGraphValidationResult result = validator.validate(graph(Map.of("node", node("test.number_default")), List.of()));

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void executionConnectionCycleIsRejected() {
        definitions.register(action("test.first", FlowDataType.STRING, null));
        definitions.register(action("test.second", FlowDataType.STRING, null));
        FlowGraph graph = graph(Map.of(
            "first", new FlowNode("test.first", 0, 0, Map.of("value", "a")),
            "second", new FlowNode("test.second", 0, 0, Map.of("value", "b"))
        ), List.of(
            new FlowConnection("first", "flow", "second", "flow"),
            new FlowConnection("second", "flow", "first", "flow")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "EXECUTION_CYCLE"));
    }

    @Test
    void executionCycleThroughExplicitLoopIsAccepted() {
        definitions.register(executionAction("test.action"));
        definitions.register(new NodeDefinition.Builder("loop_while", "While", NodeDefinition.NodeCategory.LOGIC)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).build())
            .output(new NodeDefinition.PinBuilder("loop", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).build())
            .output(new NodeDefinition.PinBuilder("done", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).build())
            .build());
        FlowGraph graph = graph(Map.of(
            "action", node("test.action"),
            "loop", node("loop_while")
        ), List.of(
            new FlowConnection("action", "flow", "loop", "flow"),
            new FlowConnection("loop", "done", "action", "flow")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
        assertFalse(hasCode(result, "EXECUTION_CYCLE"));
    }

    @Test
    void unreachableFunctionOutputIsRejected() {
        definitions.register(new NodeDefinition.Builder("function.start", "Function Start", NodeDefinition.NodeCategory.FUNCTION)
            .handler("TestHandler")
            .build());
        definitions.register(new NodeDefinition.Builder("function.function_output", "Function Output", NodeDefinition.NodeCategory.FUNCTION)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).build())
            .build());
        FlowGraph graph = graph(Map.of(
            "start", node("function.start"),
            "output", node("function.function_output")
        ), List.of());
        graph.setFunction(true);

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "REQUIRED_SECTION_UNREACHABLE"));
    }

    @Test
    void functionSignaturesAuthoritativelyDefineBoundaryPins() {
        definitions.register(new NodeDefinition.Builder("function.start", "Function Start", NodeDefinition.NodeCategory.FUNCTION)
            .handler("TestHandler")
            .build());
        definitions.register(new NodeDefinition.Builder("function.end", "Function End", NodeDefinition.NodeCategory.FUNCTION)
            .handler("TestHandler")
            .build());
        FlowGraph graph = graph(Map.of(
            "start", node("function.start"),
            "end", node("function.end")
        ), List.of(
            new FlowConnection("start", "flow", "end", "flow"),
            new FlowConnection("start", "amount", "end", "result")
        ));
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter("result", FlowDataType.NUMBER)));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void malformedFunctionSignaturesProduceSpecificDiagnostics() {
        FlowGraph graph = graph(Map.of(), List.of());
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER),
            new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER),
            new FlowGraph.FunctionParameter("choice", FlowDataType.STRING, "future_widget", "missing:catalog", "")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(hasCode(result, "FUNCTION_PARAMETER_DUPLICATE"));
        assertTrue(hasCode(result, "FUNCTION_PARAMETER_WIDGET_INVALID"));
        assertTrue(hasCode(result, "FUNCTION_PARAMETER_CATALOG_UNAVAILABLE"));
    }

    @Test
    void repeatablePinsAndEditorMetadataValidateAgainstTheirTemplate() {
        NodeDefinition.PinDefinition repeatable = new NodeDefinition.PinBuilder(
            "value",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).repeatable("values", 0, 3, "Value").build();
        definitions.register(new NodeDefinition.Builder("test.repeatable", "Repeatable", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(repeatable)
            .build());
        FlowNode node = new FlowNode("test.repeatable", 0, 0, Map.of(
            "__repeatable_count:values", 2,
            "__removed_optional_inputs", List.of(),
            "value_2", "second"
        ));

        FlowGraphValidationResult result = validator.validate(graph(Map.of("node", node), List.of()));

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void inactiveRepeatablePinsAreRejected() {
        NodeDefinition.PinDefinition repeatable = new NodeDefinition.PinBuilder(
            "value",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).repeatable("values", 0, 3, "Value").build();
        definitions.register(new NodeDefinition.Builder("test.repeatable", "Repeatable", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(repeatable)
            .build());
        FlowNode node = new FlowNode("test.repeatable", 0, 0, Map.of("value", "stale"));

        FlowGraphValidationResult result = validator.validate(graph(Map.of("node", node), List.of()));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "REPEATABLE_PIN_INACTIVE"));
    }

    @Test
    void separateRepeatableConnectionsDoNotCollideAsOneTarget() {
        definitions.register(query("test.source", FlowDataType.STRING));
        NodeDefinition.PinDefinition repeatable = new NodeDefinition.PinBuilder(
            "value",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.STRING
        ).repeatable("values", 2, 3, "Value").build();
        definitions.register(new NodeDefinition.Builder("test.repeatable", "Repeatable", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(repeatable)
            .build());
        FlowNode target = new FlowNode("test.repeatable", 0, 0, Map.of("__repeatable_count:values", 2));
        FlowGraph graph = graph(Map.of("first", node("test.source"), "second", node("test.source"), "target", target), List.of(
            new FlowConnection("first", "value", "target", "value"),
            new FlowConnection("second", "value", "target", "value_2")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void genericCollectionElementTypeFlowsThroughTheNode() {
        definitions.register(new NodeDefinition.Builder("test.players", "Players", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .output(new NodeDefinition.PinBuilder("values", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<player>"))
                .build())
            .build());
        definitions.register(new NodeDefinition.Builder("test.first", "First", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("values", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<type:t>"))
                .build())
            .output(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.ANY)
                .typeRef(FlowTypeRef.parse("type:t"))
                .build())
            .build());
        definitions.register(action("test.consume_player", FlowDataType.PLAYER, null));
        FlowGraph graph = graph(Map.of(
            "players", node("test.players"),
            "first", node("test.first"),
            "consumer", node("test.consume_player")
        ), List.of(
            new FlowConnection("players", "values", "first", "values"),
            new FlowConnection("first", "value", "consumer", "value")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void resolvedGenericOutputRejectsAnIncompatibleConsumer() {
        definitions.register(typedListQuery("test.players", "player"));
        definitions.register(new NodeDefinition.Builder("test.first", "First", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("values", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<type:t>"))
                .build())
            .output(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.ANY)
                .typeRef(FlowTypeRef.parse("type:t"))
                .build())
            .build());
        definitions.register(action("test.consume_world", FlowDataType.WORLD, null));
        FlowGraph graph = graph(Map.of(
            "players", node("test.players"),
            "first", node("test.first"),
            "consumer", node("test.consume_world")
        ), List.of(
            new FlowConnection("players", "values", "first", "values"),
            new FlowConnection("first", "value", "consumer", "value")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "CONNECTION_TYPE_INCOMPATIBLE"));
    }

    @Test
    void incompatibleBindingsForOneTypeVariableAreRejected() {
        definitions.register(typedListQuery("test.players", "player"));
        definitions.register(typedListQuery("test.worlds", "world"));
        definitions.register(new NodeDefinition.Builder("test.concat", "Concat", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("first", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<type:t>"))
                .build())
            .input(new NodeDefinition.PinBuilder("second", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<type:t>"))
                .build())
            .build());
        FlowGraph graph = graph(Map.of(
            "players", node("test.players"),
            "worlds", node("test.worlds"),
            "concat", node("test.concat")
        ), List.of(
            new FlowConnection("players", "values", "concat", "first"),
            new FlowConnection("worlds", "values", "concat", "second")
        ));

        FlowGraphValidationResult result = validator.validate(graph);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "GENERIC_TYPE_CONFLICT"));
    }

    @Test
    void temporalTypesDoNotSilentlyMixClockMeanings() {
        definitions.register(query("test.instant", FlowDataType.INSTANT));
        definitions.register(action("test.duration", FlowDataType.DURATION, null));
        definitions.register(query("test.number", FlowDataType.NUMBER));
        definitions.register(action("test.legacy_instant", FlowDataType.INSTANT, null));

        FlowGraph invalid = graph(Map.of(
            "source", node("test.instant"),
            "target", node("test.duration")
        ), List.of(new FlowConnection("source", "value", "target", "value")));
        FlowGraph legacy = graph(Map.of(
            "source", node("test.number"),
            "target", node("test.legacy_instant")
        ), List.of(new FlowConnection("source", "value", "target", "value")));

        assertTrue(hasCode(validator.validate(invalid), "CONNECTION_TYPE_INCOMPATIBLE"));
        assertTrue(validator.validate(legacy).valid());
    }

    @Test
    void invalidLiteralScheduleValuesFailBeforeExecution() {
        handlers.register("ScheduleHandler", (context, node) -> {
        });
        definitions.register(scheduleDefinition("schedule.schedule", "schedule", "time_string", "12:00"));
        definitions.register(scheduleDefinition("schedule.cron", "cron", "expression", "0 12 * * *"));
        definitions.register(scheduleDefinition("schedule.at.time", "schedule_at_time", "time", null));
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneId.of("UTC"));
        FlowGraphValidator scheduleValidator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs, null, null, clock);

        FlowGraph invalidZone = graph(Map.of("schedule", new FlowNode("schedule.schedule", 0, 0,
            Map.of("time_string", "12:00", "time_zone", "Mars/Olympus"))), List.of());
        FlowGraph invalidDaily = graph(Map.of("schedule", new FlowNode("schedule.schedule", 0, 0,
            Map.of("time_string", "7:30", "time_zone", "UTC"))), List.of());
        FlowGraph impossibleCron = graph(Map.of("schedule", new FlowNode("schedule.cron", 0, 0,
            Map.of("expression", "0 12 31 FEB *", "time_zone", "UTC"))), List.of());
        FlowGraph pastOnce = graph(Map.of("schedule", new FlowNode("schedule.at.time", 0, 0,
            Map.of("time", "2026-07-16T23:59:59Z", "time_zone", "UTC"))), List.of());

        assertTrue(hasCode(scheduleValidator.validate(invalidZone), "SCHEDULE_ZONE_INVALID"));
        assertTrue(hasCode(scheduleValidator.validate(invalidDaily), "SCHEDULE_PATTERN_INVALID"));
        assertTrue(hasCode(scheduleValidator.validate(impossibleCron), "SCHEDULE_PATTERN_INVALID"));
        assertTrue(hasCode(scheduleValidator.validate(pastOnce), "SCHEDULE_TIME_NOT_FUTURE"));
    }

    @Test
    void connectedScheduleInputsOverrideStaleEditorLiterals() {
        handlers.register("ScheduleHandler", (context, node) -> {
        });
        definitions.register(query("test.expression", FlowDataType.STRING));
        definitions.register(scheduleDefinition("schedule.cron", "cron", "expression", "0 12 * * *"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneId.of("UTC"));
        FlowGraphValidator scheduleValidator = new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), catalogs, null, null, clock);
        FlowGraph graph = graph(Map.of(
            "source", node("test.expression"),
            "schedule", new FlowNode("schedule.cron", 0, 0, Map.of("expression", "0,", "time_zone", "UTC"))
        ), List.of(new FlowConnection("source", "value", "schedule", "expression")));

        FlowGraphValidationResult result = scheduleValidator.validate(graph);

        assertFalse(hasCode(result, "SCHEDULE_PATTERN_INVALID"), result.summary());
    }

    private NodeDefinition query(String id, FlowDataType outputType) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .output(pin("value", NodeDefinition.PinDirection.OUTPUT, outputType, null, false))
            .build();
    }

    private NodeDefinition typedListQuery(String id, String elementType) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .output(new NodeDefinition.PinBuilder("values", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<" + elementType + ">"))
                .build())
            .build();
    }

    private NodeDefinition queryWithInput(String id, FlowDataType type) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input(pin("input", NodeDefinition.PinDirection.INPUT, type, null, false))
            .output(pin("value", NodeDefinition.PinDirection.OUTPUT, type, null, false))
            .build();
    }

    private NodeDefinition action(String id, FlowDataType inputType, String optionsSource) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.ACTION)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).build())
            .input(pin("value", NodeDefinition.PinDirection.INPUT, inputType, optionsSource, false))
            .output(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).build())
            .build();
    }

    private NodeDefinition executionAction(String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.ACTION)
            .handler("TestHandler")
            .input(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).build())
            .output(new NodeDefinition.PinBuilder("flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).build())
            .build();
    }

    private NodeDefinition scheduleDefinition(String id, String operation, String valuePin, String defaultValue) {
        NodeDefinition.PinBuilder value = new NodeDefinition.PinBuilder(valuePin, NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING);
        if (defaultValue != null) {
            value.defaultValue(defaultValue);
        }
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.ACTION)
            .handler("ScheduleHandler")
            .handlerConfig(Map.of("operation", operation))
            .input(value.build())
            .input(new NodeDefinition.PinBuilder("time_zone", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .defaultValue("UTC")
                .build())
            .build();
    }

    private NodeDefinition.PinDefinition pin(String name, NodeDefinition.PinDirection direction, FlowDataType type, String optionsSource, boolean optional) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, direction, type).optionsSource(optionsSource).optional(optional).build();
    }

    private FlowGraph graph(Map<String, FlowNode> nodes, List<FlowConnection> connections) {
        return new FlowGraph("test", nodes, connections, List.of());
    }

    private FlowNode node(String type) {
        return new FlowNode(type, 0, 0, Map.of());
    }

    private boolean hasCode(FlowGraphValidationResult result, String code) {
        return result.diagnostics().stream().anyMatch(diagnostic -> code.equals(diagnostic.code()));
    }

    private static final class FixtureResourceAdapter implements FlowResourceAdapter<String> {
        private final ReSyncManagedResource descriptor = new ReSyncManagedResource("fixture:quest", "Quest", "fixture/quests", null, true);

        @Override
        public ReSyncManagedResource descriptor() {
            return descriptor;
        }

        @Override
        public String get(String id) {
            return "existing".equals(id) ? id : null;
        }

        @Override
        public List<String> listIds() {
            return List.of("existing");
        }

        @Override
        public String deserialize(String json) {
            return json;
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
        }

        @Override
        public void delete(String id) {
        }
    }
}
