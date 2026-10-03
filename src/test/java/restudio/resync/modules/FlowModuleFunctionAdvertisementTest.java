package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.ReQuestDescriptorIdentityAdapter;
import restudio.resync.flow.TypedCommandGraphAdapter;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleFunctionAdvertisementTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("function-advertisement-server");
    private static final CatalogBinding BINDING = new CatalogBinding(1, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));

    @Test
    void nestedSourceAdvertisesItsSignatureWithoutWeakeningTheGraphProjectionGuard() {
        FunctionParameterContract parameter = parameter("nested-input", STRING, true, null, Map.of("name", "Reward Amount"));
        FunctionBinding dependency = new FunctionBinding(resource("child"), 7, List.of(), List.of());
        FunctionSourceDocument source = source("parent", 3, List.of(parameter), List.of(), List.of(dependency),
            Map.of("functionDescription", "Calculates the final reward using its declared child Function."));
        String original = source.canonicalJson();
        ContentHash checksum = source.checksum();

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source);

        assertEquals("custom_function:parent", definition.getId());
        assertEquals(OWNER.value(), definition.getOwner());
        assertEquals("Reward Amount", definition.getInputs().get(1).getDisplayName());
        assertEquals("function-input-" + parameter.id().canonicalText(), definition.getInputs().get(1).getRuntimeName());
        assertEquals("Calculates the final reward using its declared child Function.", definition.getDescription());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> TypedCommandGraphAdapter.materialize(source.graph(), source, true, "mutation"));
        assertEquals("Core graph static function bindings require compiled execution", failure.getMessage());
        assertEquals(original, source.canonicalJson());
        assertEquals(checksum, source.checksum());
        assertEquals(List.of(dependency), source.graph().functions());
    }

    @Test
    void canonicalDefaultsAndRequirementFlagsRemainExactThroughCatalogAdmission() {
        TypeExpr list = TypeExpr.list(STRING);
        TypeExpr map = TypeExpr.map(STRING, TypeExpr.optional(BOOLEAN));
        TypeExpr number = TypeExpr.named(TypeReference.of("builtin", "number"));
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("builtin", "advancement_tree"));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of("advancement_tree")), "gold");
        List<FunctionParameterContract> inputs = List.of(
            parameter("empty", STRING, true, TypedValue.value(STRING, ""), Map.of("name", "empty")),
            parameter("null", TypeExpr.optional(STRING), false, TypedValue.nullValue(TypeExpr.optional(STRING), Map.of("author", "source")), Map.of("name", "nullable")),
            parameter("absent", BOOLEAN, false, TypedValue.absent(BOOLEAN), Map.of("name", "absent")),
            parameter("empty-list", list, false, TypedValue.value(list, List.of()), Map.of("name", "empty_list")),
            parameter("list", list, true, TypedValue.value(list, List.of("one", "two")), Map.of("name", "list")),
            parameter("empty-map", map, false, TypedValue.value(map, Map.of()), Map.of("name", "empty_map")),
            parameter("map", map, true, TypedValue.value(map, Map.of("ready", true)), Map.of("name", "map")),
            parameter("locator", resourceType, true, TypedValue.locator(resourceType, locator), Map.of("name", "reward")),
            parameter("decimal-small", number, true, TypedValue.value(number, new BigDecimal("1E-7")), Map.of("name", "decimal_small")),
            parameter("decimal-whole", number, true, TypedValue.value(number, new BigDecimal("2E+1")), Map.of("name", "decimal_whole")),
            parameter("optional", BOOLEAN, false, null, Map.of("name", "optional", "widget", "boolean")));
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source("defaults", 1, inputs, List.of(), List.of(), Map.of()));

        CatalogNodeDescriptor descriptor = catalog(definition);

        for (FunctionParameterContract parameter : inputs) {
            CatalogNodeDescriptor.Pin pin = descriptor.pins().stream().filter(value -> value.id().equals(inputPin(parameter))).findFirst().orElseThrow();
            assertEquals(parameter.type(), pin.type(), parameter.id().canonicalText());
            assertEquals(parameter.defaultValue(), pin.defaultValue(), parameter.id().canonicalText());
            assertEquals(parameter.defaultValue() != null ? CatalogNodeDescriptor.Requirement.DEFAULTED
                : parameter.required() ? CatalogNodeDescriptor.Requirement.REQUIRED : CatalogNodeDescriptor.Requirement.OPTIONAL, pin.requirement());
            assertEquals(!parameter.required(), definition.getInputs().stream().filter(value -> value.getId().equals(pin.id())).findFirst().orElseThrow().isOptional());
        }
        assertEquals(NodeDefinition.WidgetType.TOGGLE, definition.getInputs().stream()
            .filter(pin -> "optional".equals(pin.getDisplayName())).findFirst().orElseThrow().getWidgetType());
        assertEquals("0.0000001", definition.getInputs().stream()
            .filter(pin -> "decimal_small".equals(pin.getDisplayName())).findFirst().orElseThrow().getDefaultValue());
        assertEquals("20", definition.getInputs().stream()
            .filter(pin -> "decimal_whole".equals(pin.getDisplayName())).findFirst().orElseThrow().getDefaultValue());
        Map<?, ?> defaults = (Map<?, ?>) definition.getHandlerConfig().get(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS);
        assertThrows(UnsupportedOperationException.class, defaults::clear);
        Map<?, ?> canonical = (Map<?, ?>) defaults.get(inputPin(inputs.getFirst()).value());
        assertThrows(UnsupportedOperationException.class, canonical::clear);
        assertThrows(UnsupportedOperationException.class, definition.getHandlerConfig()::clear);
    }

    @Test
    void onlySourceDefinedCallShapeChangesInvalidateTheDefinition() {
        FunctionParameterContract initial = parameter("shape-input", STRING, false, TypedValue.value(STRING, "first"), Map.of("name", "value"));
        FunctionSourceDocument first = source("shape", 1, List.of(initial), List.of(), List.of(), Map.of());
        NodeDefinition current = CustomFunctionNodeDefinitions.buildDefinition(first);
        FunctionSourceDocument rebound = source("shape", 19, List.of(initial), List.of(),
            List.of(new FunctionBinding(resource("child"), 9, List.of(), List.of())), Map.of());

        assertFalse(FlowModule.customFunctionDefinitionChanged(List.of(current), false, () -> rebound));
        assertTrue(FlowModule.customFunctionDefinitionChanged(List.of(current), true, () -> {
            throw new AssertionError("Deleted sources must not be loaded");
        }));
        assertTrue(FlowModule.customFunctionDefinitionChanged(List.of(), false, () -> {
            throw new AssertionError("A newly created definition must request refresh immediately");
        }));
        List<FunctionParameterContract> changed = List.of(
            new FunctionParameterContract(initial.id(), STRING, false, TypedValue.value(STRING, "second"), initial.unknown()),
            new FunctionParameterContract(initial.id(), STRING, true, initial.defaultValue(), initial.unknown()),
            new FunctionParameterContract(initial.id(), STRING, initial.required(), initial.defaultValue(), Map.of("name", "renamed")),
            new FunctionParameterContract(initial.id(), BOOLEAN, initial.required(), TypedValue.value(BOOLEAN, false), initial.unknown()));
        for (FunctionParameterContract parameter : changed) {
            assertTrue(FlowModule.customFunctionDefinitionChanged(List.of(current), false,
                () -> source("shape", 2, List.of(parameter), List.of(), List.of(), Map.of())), parameter.canonicalValue().toString());
        }
    }

    @Test
    void typedDefaultMetadataCannotChangeItsTypeOrParameterIdentity() {
        FunctionParameterContract parameter = parameter("checked-default", STRING, true, TypedValue.value(STRING, "exact"), Map.of("name", "value"));
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source("checked", 1, List.of(parameter), List.of(), List.of(), Map.of()));
        Map<String, Object> config = new LinkedHashMap<>(definition.getHandlerConfig());
        config.put(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS, Map.of(inputPin(parameter).value(), TypedValue.value(BOOLEAN, true).canonicalValue()));
        assertThrows(IllegalArgumentException.class, () -> catalog(configured(definition, config)));
        config.put(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS, Map.of("function-input-" + FunctionParameterId.deterministic("unknown").canonicalText(), parameter.defaultValue().canonicalValue()));
        assertThrows(IllegalArgumentException.class, () -> catalog(configured(definition, config)));
        config.put(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS, Map.of("flow", parameter.defaultValue().canonicalValue()));
        assertThrows(IllegalArgumentException.class, () -> catalog(configured(definition, config)));
    }

    @Test
    void completeSourceTypesAndOpaqueAnnotationsRemainExactInTheAdmittedCatalogAndRuntime() {
        TypeExpr annotated = new TypeExpr.Named(new TypeReference("builtin", "string", Map.of("referenceAnnotation", "preserved")),
            List.of(), Map.of("typeAnnotation", List.of("one", "two")));
        List<TypeExpr> types = List.of(TypeExpr.tuple(List.of(STRING, BOOLEAN)), TypeExpr.opaque(TypeReference.of("library", "future")),
            TypeExpr.union(List.of(new TypeExpr.UnionVariant("text", STRING), new TypeExpr.UnionVariant("flag", BOOLEAN))), annotated);
        List<FunctionParameterContract> inputs = new ArrayList<>();
        List<FunctionParameterContract> outputs = new ArrayList<>();
        for (int index = 0; index < types.size(); index++) {
            inputs.add(parameter("complete-input-" + index, types.get(index), false, null, Map.of("name", "input_" + index)));
            outputs.add(parameter("complete-output-" + index, types.get(index), true, null, Map.of("name", "output_" + index)));
        }
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source("complete_types", 1, inputs, outputs, List.of(), Map.of()));
        CatalogNodeDescriptor descriptor = catalog(definition);
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);
        RuntimeOperationDescriptor runtime = FlowModule.runtimeOperationDescriptor(definition, handlers);
        for (FunctionParameterContract parameter : inputs) {
            TypeExpr admitted = descriptor.pins().stream().filter(pin -> pin.id().equals(inputPin(parameter))).findFirst().orElseThrow().type();
            TypeExpr bound = runtime.inputPins().stream().filter(pin -> pin.id().equals(inputPin(parameter))).findFirst().orElseThrow().type();
            assertEquals(parameter.type().canonicalValue(), admitted.canonicalValue());
            assertEquals(parameter.type().canonicalValue(), bound.canonicalValue());
        }
        for (FunctionParameterContract parameter : outputs) {
            PinId pinId = PinId.of("function-output-" + parameter.id().canonicalText());
            TypeExpr admitted = descriptor.pins().stream().filter(pin -> pin.id().equals(pinId)).findFirst().orElseThrow().type();
            TypeExpr bound = runtime.outputPins().stream().filter(pin -> pin.id().equals(pinId)).findFirst().orElseThrow().type();
            assertEquals(parameter.type().canonicalValue(), admitted.canonicalValue());
            assertEquals(parameter.type().canonicalValue(), bound.canonicalValue());
        }
    }

    @Test
    void resourceRevisionAloneCannotAuthenticateTheReQuestDescriptor() {
        List<FunctionParameterContract> parameters = List.of(
            parameter("request-target", TypeExpr.named(TypeReference.of("builtin", "player")), true, null, Map.of("name", "target")),
            parameter("request-message", STRING, true, null, Map.of("name", "message")));
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source("reQuestMessage", 2, parameters, List.of(), List.of(), Map.of()));

        assertEquals(1, definition.getSchemaVersion());
        assertNull(definition.getCanonicalId());
        assertTrue(definition.getLegacyIds().isEmpty());
    }

    @Test
    void explicitReQuestMetadataPreservesItsDescriptorNamespaceAndAuthoritativeSourceIdentity() {
        List<FunctionParameterContract> parameters = List.of(
            parameter("request-target", TypeExpr.named(TypeReference.of("builtin", "player")), true, null, Map.of("name", "target")),
            parameter("request-message", STRING, true, null, Map.of("name", "message")));
        Map<String, Object> metadata = Map.of("functionOwner", "server", "functionNamespace", "local", "functionVersion", 2);
        FunctionSourceDocument source = source("reQuestMessage", 19, parameters, List.of(), List.of(), metadata);

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source);

        assertEquals(OWNER, source.signature().function().resource().owner());
        assertEquals("server", definition.getOwner());
        assertEquals("server", definition.getHandlerConfig().get("functionOwner"));
        assertEquals("local", definition.getHandlerConfig().get("functionNamespace"));
        assertEquals(ReQuestDescriptorIdentityAdapter.FUNCTION_VERSION, definition.getSchemaVersion());
        assertEquals(ReQuestDescriptorIdentityAdapter.CANONICAL_LOCAL_ID, definition.getCanonicalId());
        assertEquals(List.of("custom_function:reQuestMessage"), definition.getLegacyIds());
        FunctionParameterContract target = parameters.getFirst();
        TypeExpr disguisedPlayer = TypeExpr.named(TypeReference.of("builtin", "player"), STRING);
        NodeDefinition disguised = CustomFunctionNodeDefinitions.buildDefinition(source("reQuestMessage", 2,
            List.of(new FunctionParameterContract(target.id(), disguisedPlayer, true, null, target.unknown()), parameters.get(1)),
            List.of(), List.of(), metadata));
        assertEquals(1, disguised.getSchemaVersion());
        assertNull(disguised.getCanonicalId());
        for (String key : metadata.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(metadata);
            missing.remove(key);
            NodeDefinition unauthenticated = CustomFunctionNodeDefinitions.buildDefinition(
                source("reQuestMessage", 2, parameters, List.of(), List.of(), missing));
            assertEquals(1, unauthenticated.getSchemaVersion(), key);
            assertNull(unauthenticated.getCanonicalId(), key);
        }
        Map<String, Object> invalid = new LinkedHashMap<>(metadata);
        invalid.put("functionOwner", "invalid owner");
        assertThrows(IllegalArgumentException.class,
            () -> CustomFunctionNodeDefinitions.buildDefinition(source("reQuestMessage", 2, parameters, List.of(), List.of(), invalid)));
    }

    @Test
    void typedPinMetadataRequiresCompleteCoverageAndCannotBindAFlowPin() {
        FunctionParameterContract parameter = parameter("covered", STRING, true, null, Map.of("name", "value"));
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(source("coverage", 1, List.of(parameter), List.of(), List.of(), Map.of()));
        Map<String, Object> config = new LinkedHashMap<>(definition.getHandlerConfig());
        config.put(CustomFunctionNodeDefinitions.FUNCTION_TYPES, Map.of());
        assertThrows(IllegalArgumentException.class, () -> catalog(configured(definition, config)));
        config.put(CustomFunctionNodeDefinitions.FUNCTION_TYPES, Map.of(inputPin(parameter).value(), STRING.canonicalValue(), "flow", STRING.canonicalValue()));
        assertThrows(IllegalArgumentException.class, () -> catalog(configured(definition, config)));
    }

    private static NodeDefinition configured(NodeDefinition definition, Map<String, Object> config) {
        NodeDefinition copy = definition.copy();
        copy.getHandlerConfig().clear();
        copy.getHandlerConfig().putAll(config);
        return copy;
    }

    private static CatalogNodeDescriptor catalog(NodeDefinition definition) {
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("function-advertisement"));
        runtime.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(CustomFunctionNodeDefinitions.PLUGIN_ID, definition);
        var result = new CatalogCompiler(FlowModule.CATALOG_CONTRACT_VERSION, CatalogBindingProof.live(runtime))
            .compile(FlowModule.buildCatalogContributions(definitions, handlers, null, List.of(), FlowModule.CATALOG_CONTRACT_VERSION), 1);
        assertTrue(result.accepted(), result.diagnostics().toString());
        return result.snapshot().orElseThrow().definitions().getFirst().descriptor();
    }

    private static FunctionParameterContract parameter(String seed, TypeExpr type, boolean required, TypedValue defaultValue, Map<String, Object> metadata) {
        return new FunctionParameterContract(FunctionParameterId.deterministic(seed), type, required, defaultValue, metadata);
    }

    private static PinId inputPin(FunctionParameterContract parameter) {
        return PinId.of("function-input-" + parameter.id().canonicalText());
    }

    private static FunctionSourceDocument source(String id, long revision, List<FunctionParameterContract> inputs,
                                                List<FunctionParameterContract> outputs, List<FunctionBinding> functions,
                                                Map<String, Object> metadata) {
        ServerResourceLocator resource = resource(id);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), List.of(), List.of(),
            List.of(), functions, OpaqueData.empty());
        return new FunctionSourceDocument(new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(revision), inputs, outputs, metadata), graph);
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of("function")), id);
    }
}
