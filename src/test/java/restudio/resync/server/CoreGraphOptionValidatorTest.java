package restudio.resync.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphOptionValidatorTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion VERSION = new CatalogVersion(1, 3);
    private static final ServerId SERVER = ServerId.deterministic("core-graph-option-validator");
    private static final String STRING_SOURCE = "server:resync:variable_definition";
    private static final String RESOURCE_SOURCE = "server:test:flows";
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr RESOURCE = TypeExpr.resource(TypeReference.of(OWNER.value(), "flow"));
    private static final ContractRef<NodeId> DEFINITION = ContractRef.of(OWNER, NodeId.of("fixture.option-node"));
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("option-node");
    private static final UUID SAVE_MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DELETE_MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @TempDir
    Path tempDir;

    @Test
    void resolvesProductionAuthoredSourcesAndPreservesDefaultsVisibilityAndFunctions() {
        Fixture fixture = fixture(STRING_SOURCE);
        MutableProvider strings = provider(STRING_SOURCE, Set.of(), query -> available("alpha"));
        MutableProvider resources = provider(RESOURCE_SOURCE, Set.of(), query -> available("reference"));
        fixture.catalogs().register(strings);
        fixture.catalogs().register(resources);
        ServerResourceLocator function = resource("function", "bound-function");
        FunctionBinding binding = new FunctionBinding(function, 7L, List.of(), List.of(),
            OpaqueData.of(Map.of("future", true)));
        Map<PinId, PinValue> values = new LinkedHashMap<>();
        values.put(pin("hidden-choice"), value("hidden-choice", "missing"));
        values.put(pin("resource-choice"), new PinValue(pin("resource-choice"),
            TypedValue.locator(RESOURCE, resource("flow", "reference"))));
        GraphNode node = node(values, List.of(), List.of());
        GraphDocument graph = graph(fixture, resource("flow", "ordinary"), node, List.of(), List.of(binding));

        List<Diagnostic> diagnostics = new CoreGraphOptionValidator(fixture.catalogs()).validate(graph, fixture.catalog());

        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        assertEquals(1, strings.captures());
        assertEquals(1, resources.captures());
        assertSame(binding, graph.functions().getFirst());
        assertTrue(fixture.catalog().definitions().getFirst().descriptor().metadata().containsKey("authoredSource"));
        assertTrue(!fixture.catalog().definitions().getFirst().descriptor().metadata().containsKey("inputs"));
    }

    @Test
    void connectedVisibilityControlsKeepDependentValuesEligibleOnlyInTheirExactScope() {
        Fixture fixture = fixture(STRING_SOURCE);
        fixture.catalogs().register(provider(STRING_SOURCE, Set.of(), query -> available("alpha")));
        Map<PinId, PinValue> hiddenValue = Map.of(
            pin("mode"), value("mode", "hide"),
            pin("hidden-choice"), value("hidden-choice", "missing"));
        GraphDocument ordinary = graph(fixture, resource("flow", "connected-visibility-ordinary"),
            node(hiddenValue, List.of(), List.of()),
            List.of(connection("ordinary-visibility", pin("mode"), null, null)), List.of());

        List<Diagnostic> ordinaryDiagnostics = new CoreGraphOptionValidator(fixture.catalogs())
            .validate(ordinary, fixture.catalog());

        assertEquals(1, ordinaryDiagnostics.size(), ordinaryDiagnostics.toString());
        assertEquals("CATALOG.REFERENCE_UNRESOLVED", ordinaryDiagnostics.getFirst().code());
        assertEquals("hidden-choice", ordinaryDiagnostics.getFirst().evidence().get("pinId"));
        BranchId activeBranch = BranchId.of("active");
        CaseId activeCase = CaseId.of("active");
        BranchBinding active = new BranchBinding(activeBranch, activeCase, List.of(new BranchCase(activeCase, hiddenValue)));
        GraphDocument nested = graph(fixture, resource("flow", "connected-visibility-branch"),
            node(Map.of(), List.of(active), List.of()),
            List.of(connection("branch-visibility", pin("mode"), null, activeBranch)), List.of());

        List<Diagnostic> nestedDiagnostics = new CoreGraphOptionValidator(fixture.catalogs())
            .validate(nested, fixture.catalog());

        assertEquals(1, nestedDiagnostics.size(), nestedDiagnostics.toString());
        assertEquals("CATALOG.REFERENCE_UNRESOLVED", nestedDiagnostics.getFirst().code());
        assertEquals("hidden-choice", nestedDiagnostics.getFirst().evidence().get("pinId"));
        assertEquals("active", nestedDiagnostics.getFirst().evidence().get("branchId"));
        GraphDocument wrongScope = graph(fixture, resource("flow", "connected-visibility-wrong-scope"),
            node(hiddenValue, List.of(), List.of()),
            List.of(connection("wrong-scope-visibility", pin("mode"), null, BranchId.of("unrelated"))), List.of());

        List<Diagnostic> wrongScopeDiagnostics = new CoreGraphOptionValidator(fixture.catalogs())
            .validate(wrongScope, fixture.catalog());

        assertTrue(wrongScopeDiagnostics.isEmpty(), wrongScopeDiagnostics.toString());
    }

    @Test
    void scopesConnectedDependenciesToOneRepeatableElementAndOneBranch() {
        Fixture fixture = fixture(STRING_SOURCE);
        List<Map<String, Object>> contexts = new ArrayList<>();
        MutableProvider provider = provider(STRING_SOURCE, Set.of("repeat-dependency", "dependency"), query -> {
            contexts.add(query.context());
            return available("alpha");
        });
        fixture.catalogs().register(provider);
        RepeatableElementId first = RepeatableElementId.deterministic("first");
        RepeatableElementId second = RepeatableElementId.deterministic("second");
        RepeatableBinding repeatable = new RepeatableBinding(RepeatableGroupId.of("items"), List.of(
            element(first, "missing", "first-context"), element(second, "missing", "second-context")));
        BranchBinding completed = branch("completed", "missing", "completed-context");
        BranchBinding failed = branch("failed", "missing", "failed-context");
        GraphNode node = node(Map.of(), List.of(completed, failed), List.of(repeatable));
        List<GraphConnection> connections = List.of(
            connection("repeat", pin("repeat-dependency"), first, null),
            connection("branch", pin("dependency"), null, BranchId.of("completed")));
        GraphDocument graph = graph(fixture, resource("flow", "scopes"), node, connections, List.of());

        List<Diagnostic> diagnostics = new CoreGraphOptionValidator(fixture.catalogs()).validate(graph, fixture.catalog());

        assertEquals(2, diagnostics.stream().filter(value -> "CATALOG.REFERENCE_UNRESOLVED".equals(value.code())).count(),
            diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(value -> second.canonicalText().equals(value.evidence().get("elementId"))));
        assertTrue(diagnostics.stream().anyMatch(value -> "failed".equals(value.evidence().get("branchId"))));
        assertTrue(diagnostics.stream().noneMatch(value -> first.canonicalText().equals(value.evidence().get("elementId"))));
        assertTrue(diagnostics.stream().noneMatch(value -> "completed".equals(value.evidence().get("branchId"))));
        assertTrue(contexts.stream().anyMatch(context -> "second-context".equals(context.get("repeat-dependency"))));
        assertTrue(contexts.stream().anyMatch(context -> "failed-context".equals(context.get("dependency"))));
        assertTrue(contexts.stream().noneMatch(context -> "first-context".equals(context.get("repeat-dependency"))));
        assertTrue(contexts.stream().noneMatch(context -> "completed-context".equals(context.get("dependency"))));
    }

    @Test
    void mapsProviderStatusesAndRejectsUnresolvedAutomationCatalogValues() {
        Map<String, String> statuses = Map.of(
            "invalid", "CATALOG.SELECTOR_UNRESOLVED",
            "permission_restricted", "RUNTIME.AUTHORIZATION_DENIED",
            "rejected", "CATALOG.SELECTOR_UNRESOLVED",
            "unavailable", "CATALOG.SELECTOR_UNRESOLVED");
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            Fixture fixture = fixture(STRING_SOURCE);
            fixture.catalogs().register(provider(STRING_SOURCE, Set.of(), query ->
                new OptionCatalogCapture("1", List.of(), entry.getKey(), "fixture status")));
            GraphDocument graph = graph(fixture, resource("flow", entry.getKey()),
                node(Map.of(pin("choice"), value("missing")), List.of(), List.of()), List.of(), List.of());

            assertEquals(List.of(entry.getValue()), codes(new CoreGraphOptionValidator(fixture.catalogs())
                .validate(graph, fixture.catalog())));
        }
        for (String source : List.of("server:resync:variable_definition", "server:resync:timer_definition",
            "server:resync:schedule_definition")) {
            Fixture fixture = fixture(source);
            fixture.catalogs().register(provider(source, Set.of(), query -> available("known")));
            GraphDocument graph = graph(fixture, resource("flow", source.substring(source.lastIndexOf(':') + 1)),
                node(Map.of(pin("choice"), value("missing")), List.of(), List.of()), List.of(), List.of());

            assertEquals(List.of("CATALOG.REFERENCE_UNRESOLVED"), codes(new CoreGraphOptionValidator(fixture.catalogs())
                .validate(graph, fixture.catalog())));
        }
        Fixture missing = fixture(STRING_SOURCE);
        GraphDocument graph = graph(missing, resource("flow", "missing-provider"),
            node(Map.of(pin("choice"), value("missing")), List.of(), List.of()), List.of(), List.of());
        assertEquals(List.of("CATALOG.SELECTOR_UNRESOLVED"), codes(new CoreGraphOptionValidator(missing.catalogs())
            .validate(graph, missing.catalog())));
        Fixture failed = fixture(STRING_SOURCE);
        failed.catalogs().register(provider(STRING_SOURCE, Set.of(), query -> {
            throw new IllegalStateException("capture failed");
        }));
        assertEquals(List.of("CATALOG.SELECTOR_UNRESOLVED"), codes(new CoreGraphOptionValidator(failed.catalogs())
            .validate(graph(failed, resource("flow", "failed-capture"),
                node(Map.of(pin("choice"), value("missing")), List.of(), List.of()), List.of(), List.of()),
                failed.catalog())));
    }

    @Test
    void authoritySaveAndCreateRejectAProviderReadWhoseStorageObservationBecomesStale() throws Exception {
        runStaleAuthorityMutation(false, tempDir.resolve("save"));
        runStaleAuthorityMutation(true, tempDir.resolve("create"));
    }

    private void runStaleAuthorityMutation(boolean create, Path root) throws Exception {
        Fixture fixture = fixture(STRING_SOURCE);
        AssetPersistenceGate gate = new AssetPersistenceGate(root);
        AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(root.resolve("assets"), new Gson());
        try {
            FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), gate, SERVER,
                coordinator);
            fixture.catalogs().register(provider(STRING_SOURCE, Set.of(), query -> available("alpha")));
            ServerResourceLocator reference = resource("flow", "referenced-" + (create ? "create" : "save"));
            storage.saveCoreGraph(graph(fixture, reference, null, List.of(), List.of()), ResourceActivationState.ACTIVE,
                UUID.randomUUID(), 0L);
            fixture.catalogs().register(provider(RESOURCE_SOURCE, Set.of(), query -> {
                storage.deleteCoreGraph(reference, DELETE_MUTATION, 1L);
                return available(reference.id());
            }));
            CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, fixture.activation(),
                CatalogActivationAuthority.freshInstall());
            FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER,
                validator, fixture::catalogs);
            ServerResourceLocator target = resource("flow", "target-" + (create ? "create" : "save"));
            GraphNode node = node(Map.of(pin("resource-choice"), new PinValue(pin("resource-choice"),
                TypedValue.locator(RESOURCE, reference))), List.of(), List.of());
            GraphDocument candidate = graph(fixture, target, node, List.of(), List.of());
            CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
            byte[] envelope = boundary.encode(candidate,
                new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, SAVE_MUTATION, ResourceActivationState.ACTIVE), target);
            CoreGraphStorageBoundary.Decoded decoded = boundary.decode(envelope, target);

            assertThrows(IllegalStateException.class, () -> {
                if (create) {
                    authority.create(target, decoded, SAVE_MUTATION, 0L,
                        new ResourcePresentationIntent("Target", "Flows/target.json", 0));
                } else {
                    authority.save(target, envelope, SAVE_MUTATION, 0L, decoded.envelope().assetHash());
                }
            });
            assertTrue(authority.state(target).isEmpty());
            assertTrue(authority.state(reference).isEmpty());
        } finally {
            gate.quiesce();
            coordinator.close();
        }
    }

    private static Fixture fixture(String stringSource) {
        RuntimeSemantics semantics = semantics();
        CatalogContribution contribution = contribution(stringSource, semantics);
        RuntimeOperationDescriptor requirement = contribution.runtimeRequirements().getFirst();
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("fixture"));
        runtime.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0L, 0L,
            RuntimeSemantics.UnloadPolicy.DRAIN), List.of(RuntimeBinding.available(requirement, provider, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        var compiled = new CatalogCompiler(VERSION, CatalogBindingProof.live(runtime)).compile(List.of(contribution), 1L);
        if (!compiled.accepted()) {
            throw new IllegalStateException(compiled.diagnostics().toString());
        }
        CatalogSnapshot catalog = compiled.snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime.snapshot());
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtime.snapshot().bindingManifestHash());
        return new Fixture(catalog, activation, binding, new OptionCatalogRegistry());
    }

    private static CatalogContribution contribution(String stringSource, RuntimeSemantics semantics) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", DEFINITION.id().value());
        node.put("displayName", "Option Fixture");
        node.put("description", "Validates authoritative options across every Core graph value scope.");
        node.put("domain", "logic");
        node.put("family", "fixture");
        node.put("lifecycle", "active");
        node.put("category", "logic");
        node.put("schemaVersion", 1);
        node.put("kind", "PURE");
        node.put("handlerCapability", "fixture-handler");
        node.put("selectorIntent", "none");
        node.put("inspectorIntent", "generic");
        node.put("handler", "FixtureHandler");
        node.put("handlerConfig", Map.of("operation", "validate"));
        node.put("trigger", false);
        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(pin("mode", "string", null, "hide", null, null));
        inputs.add(pin("dependency", "string", null, null, null, null));
        inputs.add(pin("choice", "string", stringSource, "alpha", null, null));
        inputs.add(pin("hidden-choice", "string", stringSource, null, null, Map.of("mode", "show")));
        inputs.add(pin("repeat-dependency", "string", null, null, repeatable(), null));
        inputs.add(pin("repeat-choice", "string", stringSource, null, repeatable(), null));
        inputs.add(pin("branch-choice", "string", stringSource, null, null, null));
        inputs.add(pin("resource-choice", "resource<flow>", RESOURCE_SOURCE, null, null, null));
        node.put("inputs", inputs);
        node.put("outputs", List.of());
        node.put("semantics", semantics.canonicalValue());
        node.put("sourceDescriptor", Map.of());
        byte[] bytes = restudio.resync.flow.canonical.CanonicalJson.canonicalBytes(List.of(node));
        CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(OWNER,
            CatalogProvenance.SourceKind.BUNDLED, "nodes/options.json", "1.0.0", "test-build", bytes);
        InspectorCapability editor = new InspectorCapability(ContractRef.of(OWNER, CapabilityId.of("generic-editor")),
            "Generic Editor", "Edits the typed fixture values used by this catalog test.",
            new InspectorValueSchema(STRING), new InspectorValueSchema(STRING), List.of(),
            ContractRef.of(OWNER, CapabilityId.of("generic-editor")), InspectorFallback.GENERIC);
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            new CatalogContractRange(VERSION, VERSION),
            List.of(new CatalogCategoryDescriptor("logic", "Logic", "Logic operations used by this catalog test.", 1)),
            editor, id -> Optional.of(optionSource(id)), request -> Optional.of(new RuntimeOperationDescriptor(
            request.capability(), request.operation(), request.pins(), semantics)), Optional::of);
        return new CatalogSourceIngestor().ingest(source, context);
    }

    private static InspectorOptionSource optionSource(InspectorFieldId id) {
        TypeExpr type = id.value().equals("server-test-flows") ? RESOURCE : STRING;
        return new InspectorOptionSource(id, "Fixture Options", "Provides authoritative options for this catalog test.",
            type, OptionQuerySchemaV1.empty(), ContractRef.of(OWNER, CapabilityId.of("fixture-options")), 100);
    }

    private static Map<String, Object> pin(String id, String dataType, String source, Object defaultValue,
                                           Map<String, Object> repeatable, Map<String, Object> visibleWhen) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", id);
        pin.put("name", "legacy-" + id);
        pin.put("displayName", id);
        pin.put("description", "A typed input used by the authoritative option validation fixture.");
        pin.put("pinType", "DATA");
        pin.put("dataType", dataType);
        pin.put("optional", true);
        if (source != null) {
            pin.put("optionsSource", source);
        }
        if (defaultValue != null) {
            pin.put("defaultValue", defaultValue);
        }
        if (repeatable != null) {
            pin.put("repeatable", repeatable);
        }
        if (visibleWhen != null) {
            pin.put("visibleWhen", visibleWhen);
        }
        return pin;
    }

    private static Map<String, Object> repeatable() {
        return Map.of("groupId", "items", "minItems", 0, "maxItems", 4, "itemLabel", "Item", "ordered", true);
    }

    private static RuntimeSemantics semantics() {
        ContractRef<CapabilityId> authorization = ContractRef.of(OWNER, CapabilityId.of("fixture-handler"));
        TypeExpr diagnostic = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, authorization,
            RuntimeSemantics.Cancellation.NONE, 0L, 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of("completed"), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(diagnostic, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static GraphNode node(Map<PinId, PinValue> values, List<BranchBinding> branches,
                                  List<RepeatableBinding> repeatables) {
        return new GraphNode(NODE, DEFINITION, 1, null, values, Map.of(), branches, repeatables,
            InspectorState.empty(), 0, 0, OpaqueData.empty());
    }

    private static RepeatableElement element(RepeatableElementId id, String choice, String dependency) {
        return new RepeatableElement(id, Map.of(
            pin("repeat-choice"), value("repeat-choice", choice),
            pin("repeat-dependency"), value("repeat-dependency", dependency)));
    }

    private static BranchBinding branch(String id, String choice, String dependency) {
        BranchId branch = BranchId.of(id);
        CaseId branchCase = CaseId.of(id);
        return new BranchBinding(branch, branchCase, List.of(new BranchCase(branchCase, Map.of(
            pin("branch-choice"), value("branch-choice", choice),
            pin("dependency"), value("dependency", dependency)))));
    }

    private static GraphConnection connection(String id, PinId target, RepeatableElementId element, BranchId branch) {
        return new GraphConnection(ConnectionId.deterministic(id), new GraphEndpoint(NODE, pin("choice")),
            new GraphEndpoint(NODE, target, element, branch));
    }

    private static GraphDocument graph(Fixture fixture, ServerResourceLocator resource, GraphNode node,
                                       List<GraphConnection> connections, List<FunctionBinding> functions) {
        return new GraphDocument(VERSION, resource, 1L, fixture.binding(), Set.of(),
            node == null ? List.of() : List.of(node), connections, List.of(), functions, OpaqueData.empty());
    }

    private static PinValue value(String value) {
        return value("choice", value);
    }

    private static PinValue value(String pin, String value) {
        return new PinValue(pin(pin), TypedValue.value(STRING, value));
    }

    private static PinId pin(String value) {
        return PinId.of(value);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static MutableProvider provider(String source, Set<String> contextKeys,
                                            Function<OptionCatalogQuery, OptionCatalogCapture> capture) {
        return new MutableProvider(source, contextKeys, capture);
    }

    private static OptionCatalogCapture available(String... values) {
        return new OptionCatalogCapture("1", List.of(values).stream().map(OptionCatalogItem::new).toList(),
            "available", "");
    }

    private static List<String> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::code).toList();
    }

    private record Fixture(CatalogSnapshot catalog, CatalogRuntimeActivation activation, CatalogBinding binding,
                           OptionCatalogRegistry catalogs) {
    }

    private static final class MutableProvider implements OptionCatalogProvider {
        private final String source;
        private final Set<String> contextKeys;
        private final Function<OptionCatalogQuery, OptionCatalogCapture> capture;
        private final AtomicInteger captures = new AtomicInteger();

        private MutableProvider(String source, Set<String> contextKeys,
                                Function<OptionCatalogQuery, OptionCatalogCapture> capture) {
            this.source = source;
            this.contextKeys = Set.copyOf(contextKeys);
            this.capture = capture;
        }

        @Override
        public String sourceId() {
            return source;
        }

        @Override
        public CaptureAffinity captureAffinity() {
            return CaptureAffinity.CALLER;
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogQuery query) {
            captures.incrementAndGet();
            return capture.apply(query);
        }

        @Override
        public Set<String> contextKeys() {
            return contextKeys;
        }

        @Override
        public String revision() {
            return "1";
        }

        @Override
        public List<String> values() {
            return List.of();
        }

        private int captures() {
            return captures.get();
        }
    }
}
