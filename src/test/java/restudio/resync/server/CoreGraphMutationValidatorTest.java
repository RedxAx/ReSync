package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphMutationValidatorTest {
    private static final ServerId SERVER = ServerId.deterministic("core-graph-mutation-validator-test");
    private static final UUID MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final CatalogVersion SCHEMA = new CatalogVersion(1, 0);

    @Test
    void acceptsAFlowOnlyWhenItsBindingMatchesTheActiveCatalogAndRuntime() {
        Fixture fixture = fixture();
        ServerResourceLocator resource = resource("flow", "accepted");
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph(resource, fixture.binding()), "flow", resource);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(resource, decoded);

        assertTrue(result.valid(), result.diagnostics().toString());
        assertEquals(fixture.binding(), fixture.validator().activeBinding());
    }

    @Test
    void rejectsAStaleCatalogBindingBeforeMutationAdmission() {
        Fixture fixture = fixture();
        ServerResourceLocator resource = resource("flow", "stale");
        CatalogBinding stale = new CatalogBinding(fixture.catalog().generation() + 1,
            fixture.binding().catalogChecksum(), fixture.binding().bindingManifestHash());
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph(resource, stale), "flow", resource);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(resource, decoded);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> "GRAPH.CATALOG_MISMATCH".equals(value.code())));
    }

    @Test
    void rejectsACommandWithoutOneActiveCatalogCommandStartDefinition() {
        Fixture fixture = fixture();
        ServerResourceLocator resource = resource("command", "missing-start");
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph(resource, fixture.binding()), "command", resource);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(resource, decoded);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> "GRAPH.DEFINITION_MISSING".equals(value.code())));
    }

    @Test
    void rejectsLegacyCommandStartsEvenWhenTheCanonicalStartIsPresent() {
        Fixture fixture = fixture();
        ServerResourceLocator resource = resource("command", "legacy-start");
        ContractRef<NodeId> legacy = CommandGraphContract.LEGACY_TYPED_STARTS.iterator().next();
        GraphDocument graph = graph(resource, fixture.binding(), List.of(
            node("canonical", CommandGraphContract.CANONICAL_START), node("legacy", legacy)), OpaqueData.empty());
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph, "command", resource);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(resource, decoded);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> "GRAPH.DEFINITION_MISSING".equals(value.code())
            && Long.valueOf(1L).equals(value.evidence().get("legacyCommandStartCount"))));
    }

    @Test
    void emitsBoundedCommandMetadataDiagnosticsBeforeGenericGraphValidation() {
        Fixture fixture = fixture();
        ServerResourceLocator resource = resource("command", "invalid-metadata");
        GraphDocument graph = graph(resource, fixture.binding(),
            List.of(node("canonical", CommandGraphContract.CANONICAL_START)),
            OpaqueData.of(Map.of("commandLabel", List.of("not", "text"))));
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph, "command", resource);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(resource, decoded);

        assertFalse(result.valid());
        assertEquals("GRAPH.OPAQUE_UNAVAILABLE", result.diagnostics().getFirst().code());
        assertEquals("command metadata is invalid", result.diagnostics().getFirst().evidence().get("reason"));
        assertTrue(result.diagnostics().getFirst().evidence().get("failure").toString().length() <= 256);
    }

    @Test
    void rejectsAFlowEnvelopeWhoseGraphLocatorDiffersFromTheRequestedResource() {
        Fixture fixture = fixture();
        ServerResourceLocator requested = resource("flow", "requested");
        ServerResourceLocator payload = resource("flow", "payload");
        CoreGraphStorageBoundary.Decoded decoded = fixture.decoded(graph(payload, fixture.binding()), "flow", payload);

        CoreGraphMutationValidator.AdmissionResult result = fixture.validator().validate(requested, decoded);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> "GRAPH.CATALOG_MISMATCH".equals(value.code())));
    }

    @Test
    void rejectsAnAdmissionProofWhenTheActiveCatalogRuntimeChangesBeforeDurability() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = catalog(runtime);
        AtomicReference<CatalogRuntimeActivation.ActivationRecord> active = new AtomicReference<>(
            new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.empty()));
        CatalogActivationAuthority authority = CatalogActivationAuthority.freshInstall();
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, active::get, () -> authority);
        ServerResourceLocator resource = resource("flow", "proof");
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtime.bindingManifestHash());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph(resource, binding);
        byte[] encoded = boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, MUTATION, ResourceActivationState.ACTIVE), resource);
        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(encoded, resource);

        CoreGraphMutationValidator.AdmissionProof proof = validator.admit(resource, decoded);
        active.set(new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.empty()));

        CoreGraphMutationValidationException failure = assertThrows(CoreGraphMutationValidationException.class,
            () -> validator.requireCurrent(proof, resource, decoded));
        assertTrue(failure.diagnostics().stream().anyMatch(value -> "GRAPH.CATALOG_MISMATCH".equals(value.code())));
    }

    @Test
    void resolvesFunctionSignaturePinsDuringMutationAdmission() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = AuthoringTemplateProducerTest.currentAuthoringCatalog(runtime);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, activation,
            CatalogActivationAuthority.freshInstall());
        ServerResourceLocator resource = resource("function", "signature-pins");
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtime.bindingManifestHash());
        NodeInstanceId start = NodeInstanceId.deterministic("function-signature-pin-start");
        NodeInstanceId end = NodeInstanceId.deterministic("function-signature-pin-end");
        FunctionParameterId input = FunctionParameterId.deterministic("function-signature-pin-input");
        FunctionParameterId output = FunctionParameterId.deterministic("function-signature-pin-output");
        TypeExpr booleanType = TypeExpr.named(TypeReference.of("builtin", "boolean"));
        GraphDocument graph = new GraphDocument(SCHEMA, resource, 1L, binding, Set.of(), List.of(
            new GraphNode(start, ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("function_start")), 1,
                Map.of()),
            new GraphNode(end, ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("function_end")), 1,
                Map.of())), List.of(
            new GraphConnection(ConnectionId.deterministic("function-flow"),
                new GraphEndpoint(start, PinId.of("flow")), new GraphEndpoint(end, PinId.of("flow"))),
            new GraphConnection(ConnectionId.deterministic("function-value"),
                new GraphEndpoint(start, PinId.of("function-output-" + input.canonicalText())),
                new GraphEndpoint(end, PinId.of("function-input-" + output.canonicalText())))),
            List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1L),
            List.of(new FunctionParameterContract(input, booleanType)),
            List.of(new FunctionParameterContract(output, booleanType)));
        FunctionSourceDocument source = new FunctionSourceDocument(signature, graph);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] encoded = boundary.encode(source,
            new CoreGraphStorageBoundary.AssetMetadata("function", 1L, MUTATION, ResourceActivationState.ACTIVE),
            resource);

        CoreGraphMutationValidator.AdmissionResult result = validator.validate(resource, boundary.decode(encoded, resource));

        assertFalse(result.diagnostics().stream().anyMatch(value -> "GRAPH.ENDPOINT_PIN_MISSING".equals(value.code())),
            result.diagnostics().toString());
        assertFalse(result.diagnostics().stream().anyMatch(value -> "FUNCTION.SIGNATURE_MISMATCH".equals(value.code())),
            result.diagnostics().toString());
    }

    @Test
    void holdsTheAdmissionFenceWhileTheDurableActionRuns() throws Exception {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = catalog(runtime);
        CatalogRuntimeActivation.ActivationRecord initial = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.empty());
        AtomicReference<CatalogRuntimeActivation.ActivationRecord> active = new AtomicReference<>(initial);
        Object fence = new Object();
        CoreGraphMutationValidator.AdmissionExecutor admissionExecutor = new CoreGraphMutationValidator.AdmissionExecutor() {
            @Override
            public <T> T execute(CatalogRuntimeActivation.ActivationRecord expected, Supplier<T> action) {
                synchronized (fence) {
                    if (active.get() != expected) {
                        throw new CoreGraphMutationValidator.AdmissionFenceException();
                    }
                    return action.get();
                }
            }
        };
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, active::get,
            CatalogActivationAuthority::freshInstall, admissionExecutor);
        ServerResourceLocator resource = resource("flow", "fenced");
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtime.bindingManifestHash());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph(resource, binding);
        byte[] encoded = boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, MUTATION, ResourceActivationState.ACTIVE), resource);
        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(encoded, resource);
        CoreGraphMutationValidator.AdmissionProof proof = validator.admit(resource, decoded);

        CountDownLatch replacementAttempted = new CountDownLatch(1);
        AtomicReference<Boolean> actionObservedExpectedActivation = new AtomicReference<>();
        Thread replacement = new Thread(() -> {
            replacementAttempted.countDown();
            synchronized (fence) {
                active.set(new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.empty()));
            }
        });
        String result = validator.executeCurrent(proof, resource, decoded, () -> {
            replacement.start();
            try {
                assertTrue(replacementAttempted.await(1L, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Admission fence test was interrupted", exception);
            }
            actionObservedExpectedActivation.set(active.get() == proof.activation());
            return "written";
        });
        replacement.join();

        assertEquals("written", result);
        assertTrue(actionObservedExpectedActivation.get());
        assertTrue(active.get() != proof.activation());
    }

    private static Fixture fixture() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = catalog(runtime);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtime.bindingManifestHash());
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, activation,
            CatalogActivationAuthority.freshInstall());
        return new Fixture(catalog, binding, validator, new CoreGraphStorageBoundary());
    }

    private static CatalogSnapshot catalog(RuntimeRegistrySnapshot runtime) {
        CatalogSnapshot base = CatalogSnapshot.empty(SCHEMA);
        ContentHash bindingHash = runtime.bindingManifestHash();
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(base.generation(), base.contractVersion(),
            base.contributions(), base.minimumClientCapabilities(), base.diagnostics(), bindingHash);
        return new CatalogSnapshot(base.generation(), base.contractVersion(), base.contentChecksum(), bindingHash,
            base.minimumClientCapabilities(), base.contributions(), base.definitions(), base.types(),
            base.conversions(), base.categories(), base.inspectors(), base.capabilities(), base.runtimeRequirements(),
            base.optionSources(), base.validators(), base.editors(), base.previews(), base.migrations(),
            base.provenance(), base.diagnostics(), canonical, bindingHash);
    }

    private static GraphDocument graph(ServerResourceLocator resource, CatalogBinding binding) {
        return graph(resource, binding, List.of(), OpaqueData.empty());
    }

    private static GraphDocument graph(ServerResourceLocator resource, CatalogBinding binding, List<GraphNode> nodes,
                                       OpaqueData unknown) {
        return new GraphDocument(SCHEMA, resource, 1L, binding, Set.of(), nodes, List.of(), List.of(), List.of(), unknown);
    }

    private static GraphNode node(String id, ContractRef<NodeId> definition) {
        return new GraphNode(NodeInstanceId.deterministic(id), definition, 1, Map.of());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private record Fixture(CatalogSnapshot catalog, CatalogBinding binding,
                           CoreGraphMutationValidator validator, CoreGraphStorageBoundary boundary) {
        private CoreGraphStorageBoundary.Decoded decoded(GraphDocument graph, String type,
                                                         ServerResourceLocator expectedResource) {
            byte[] encoded = boundary.encode(graph,
                new CoreGraphStorageBoundary.AssetMetadata(type, 1L, MUTATION, ResourceActivationState.ACTIVE),
                expectedResource);
            return boundary.decode(encoded, expectedResource);
        }
    }
}
