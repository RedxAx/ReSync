package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogFunctionShape;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFunctionBindingValidationTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("advertised-function-binding");
    private static final CatalogVersion VERSION = new CatalogVersion(1, 0);
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("flow-execute"));
    private static final TypeExpr FLOW = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final FunctionParameterId FIRST = FunctionParameterId.deterministic("advertised-function-first");
    private static final FunctionParameterId SECOND = FunctionParameterId.deterministic("advertised-function-second");
    private static final FunctionParameterId RESULT = FunctionParameterId.deterministic("advertised-function-result");

    @Test
    void advertisedFunctionBindingsCompileByExactParameterIdentityAcrossRenameAndReorder() {
        CatalogSnapshot catalog = catalog(definition("compute", "compute", Map.of(FIRST, STRING, SECOND, NUMBER), Map.of(RESULT, NUMBER)));
        FunctionBinding binding = new FunctionBinding(resource("function", "compute"), 4,
            List.of(parameter(SECOND, "renamedSecond", NUMBER), parameter(FIRST, "renamedFirst", STRING)),
            List.of(parameter(RESULT, "renamedResult", NUMBER)));

        var compiled = new GraphCompiler().compileResult(graph(catalog, binding), catalog);

        assertTrue(compiled.compiled(), compiled.validation().diagnostics()::toString);
        assertTrue(compiled.plan().functionBindings().contains(binding));
    }

    @Test
    void effectOnlyAndMultipleOutputFunctionsUseTheirExactAdvertisedShape() {
        CatalogSnapshot catalog = catalog(definition("effect", "effect", Map.of(), Map.of()),
            definition("many", "many", Map.of(FIRST, STRING), Map.of(SECOND, STRING, RESULT, NUMBER)));
        FunctionBinding effect = new FunctionBinding(resource("function", "effect"), 3, List.of(), List.of());
        FunctionBinding many = new FunctionBinding(resource("function", "many"), 7,
            List.of(parameter(FIRST, "input", STRING)),
            List.of(parameter(RESULT, "number", NUMBER), parameter(SECOND, "text", STRING)));

        assertTrue(validate(catalog, effect).valid());
        assertTrue(validate(catalog, many).valid());
        rejects(catalog, new FunctionBinding(effect.function(), 3, List.of(), List.of(parameter(RESULT, "extra", NUMBER))),
            "GRAPH.PIN_TYPE_MISMATCH");
    }

    @Test
    void unknownMissingMistypedAndReversedParametersFailClosed() {
        CatalogSnapshot catalog = catalog(definition("compute", "compute", Map.of(FIRST, NUMBER), Map.of(RESULT, STRING)));
        ServerResourceLocator function = resource("function", "compute");

        rejects(catalog, new FunctionBinding(function, 4, List.of(parameter(SECOND, "unknown", NUMBER)),
            List.of(parameter(RESULT, "result", STRING))), "GRAPH.PIN_TYPE_MISMATCH");
        rejects(catalog, new FunctionBinding(function, 4, List.of(), List.of(parameter(RESULT, "result", STRING))),
            "GRAPH.PIN_TYPE_MISMATCH");
        rejects(catalog, new FunctionBinding(function, 4, List.of(parameter(FIRST, "input", STRING)),
            List.of(parameter(RESULT, "result", STRING))), "GRAPH.PIN_TYPE_MISMATCH");
        rejects(catalog, new FunctionBinding(function, 4, List.of(parameter(RESULT, "reversed", STRING)),
            List.of(parameter(FIRST, "reversed", NUMBER))), "GRAPH.PIN_TYPE_MISMATCH");
    }

    @Test
    void ownerServerResourceKindAndAmbiguousAdvertisementsCannotAliasAFunction() {
        CatalogNodeDescriptor effect = definition("effect", "effect", Map.of(), Map.of());
        CatalogSnapshot catalog = catalog(effect);

        rejects(catalog, new FunctionBinding(new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("other"), ResourceTypeId.of("function")), "effect"), 3, List.of(), List.of()),
            "GRAPH.DEFINITION_MISSING");
        rejects(catalog, new FunctionBinding(new ServerResourceLocator(ServerId.deterministic("other-server"),
            ContractRef.of(OWNER, ResourceTypeId.of("function")), "effect"), 3, List.of(), List.of()),
            "GRAPH.DEFINITION_MISSING");
        rejects(catalog, new FunctionBinding(resource("flow", "effect"), 3, List.of(), List.of()), "GRAPH.DEFINITION_MISSING");
        CatalogSnapshot ambiguous = catalog(effect, definition("second-effect", "effect", Map.of(), Map.of()));
        rejects(ambiguous, new FunctionBinding(resource("function", "effect"), 3, List.of(), List.of()),
            "GRAPH.DEFINITION_MISSING");
    }

    @Test
    void explicitSignatureAndRevisionEvidenceCannotBeBypassed() {
        CatalogSnapshot catalog = catalog(definition("effect", "effect", Map.of(), Map.of()));
        ServerResourceLocator function = resource("function", "effect");

        rejects(catalog, new FunctionBinding(function, 3, List.of(), List.of(),
            OpaqueData.of(Map.of("signatureId", "another-function"))), "GRAPH.PIN_UNRESOLVED");
        rejects(catalog, new FunctionBinding(function, 3, List.of(), List.of(),
            OpaqueData.of(Map.of("catalogRevision", 2))), "GRAPH.PIN_UNRESOLVED");
        assertTrue(validate(catalog, new FunctionBinding(function, 3, List.of(), List.of(),
            OpaqueData.of(Map.of("signatureId", "effect", "catalogRevision", 3)))).valid());
    }

    private static void rejects(CatalogSnapshot catalog, FunctionBinding binding, String code) {
        ValidationResult result = validate(catalog, binding);
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> code.equals(diagnostic.code())), result.diagnostics()::toString);
    }

    private static ValidationResult validate(CatalogSnapshot catalog, FunctionBinding binding) {
        return new GraphValidator().validate(graph(catalog, binding), catalog);
    }

    private static GraphDocument graph(CatalogSnapshot catalog, FunctionBinding binding) {
        return new GraphDocument(VERSION, resource("flow", "caller"), 1,
            CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
            Set.of(), List.of(), List.of(), List.of(), List.of(binding), OpaqueData.empty());
    }

    private static FunctionParameter parameter(FunctionParameterId id, String name, TypeExpr type) {
        return new FunctionParameter(id, name, type);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static CatalogNodeDescriptor definition(String id, String function, Map<FunctionParameterId, TypeExpr> inputs,
                                                     Map<FunctionParameterId, TypeExpr> outputs) {
        List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>();
        pins.add(pin("flow", CatalogNodeDescriptor.Direction.INPUT, FLOW));
        inputs.forEach((parameter, type) -> pins.add(pin("function-input-" + parameter.canonicalText(), CatalogNodeDescriptor.Direction.INPUT, type)));
        pins.add(pin("output_flow", CatalogNodeDescriptor.Direction.OUTPUT, FLOW));
        outputs.forEach((parameter, type) -> pins.add(pin("function-output-" + parameter.canonicalText(), CatalogNodeDescriptor.Direction.OUTPUT, type)));
        return CatalogNodeDescriptor.builder(id).domain("flow").family("function").displayName(id)
            .description("Calls the declared Function with its typed input and output values.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow"))).pins(pins)
            .handler(new CatalogNodeDescriptor.Handler(CAPABILITY, ContractRef.of(OWNER, CatalogFunctionShape.operation(runtimePins(pins)))))
            .semantics(semantics()).requiredCapabilities(Set.of(CAPABILITY))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports a structured Function failure.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The Function failed to return its declared values.")))))
            .metadata(Map.of("customFunctionIdentity", Map.of("owner", OWNER.value(), "namespace", "local", "id", function))).build();
    }

    private static CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, "Parameter",
            "Provides the declared value for this Function parameter.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
            ContractRef.of(OWNER, CapabilityId.of("generic-editor")), null, null, null);
    }

    private static List<RuntimeOperationDescriptor.Pin> runtimePins(List<CatalogNodeDescriptor.Pin> pins) {
        return pins.stream().map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
            pin.direction() == CatalogNodeDescriptor.Direction.INPUT ? RuntimeOperationDescriptor.Direction.INPUT
                : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList();
    }

    private static CatalogSnapshot catalog(CatalogNodeDescriptor... definitions) {
        List<CatalogNodeDescriptor> nodes = List.of(definitions);
        Map<RuntimeBindingKey, RuntimeOperationDescriptor> requirements = new LinkedHashMap<>();
        nodes.forEach(node -> {
            RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(),
                runtimePins(node.pins()), node.semantics());
            requirements.putIfAbsent(operation.key(), operation);
        });
        List<RuntimeOperationDescriptor> operations = List.copyOf(requirements.values());
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(VERSION, VERSION),
            CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/functions/binding-test", "1.0.0", "test", "bindings"))
            .definitions(nodes).runtimeRequirements(operations)
            .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Operations that call a reusable typed Function.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow-execute"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD))).build();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        operations.forEach(operation -> fingerprints.put(operation.key(), operation.executionFingerprint()));
        var compiled = new CatalogCompiler(VERSION, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), 1);
        assertTrue(compiled.accepted(), compiled.diagnostics()::toString);
        return compiled.snapshot().orElseThrow();
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, CAPABILITY,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
    }
}
