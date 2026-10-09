package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompilationResult;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledGraphMetadataProviderTest {
    @Test
    void rejectsNullGraphWithStructuredDiagnostic() {
        CompiledGraphMetadataProvider provider = provider(null, null);

        CompiledGraphMetadataProvider.Result result = provider.provide(null);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.NULL")));
        assertNotNull(result.failure());
    }

    @Test
    void rejectsGraphWithoutPersistedIdentityBeforeCompilation() {
        FlowGraph graph = new FlowGraph("graph", Map.of("node", new FlowNode("test", 0, 0, Map.of())), List.of(), List.of());
        graph.setResourceType("flow");
        CompiledGraphMetadataProvider provider = provider(null, null);

        CompiledGraphMetadataProvider.Result result = provider.provide(graph);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
        assertTrue(result.diagnostics().stream().noneMatch(value -> value.code().equals("GRAPH.DEFINITION_MISSING")));
    }

    @Test
    void rejectsLegacyFunctionBeforeCatalogOrRuntimeResolution() {
        FlowGraph graph = new FlowGraph("function", Map.of("node", new FlowNode("test", 0, 0, Map.of())), List.of(), List.of());
        graph.setResourceType("function");
        graph.setResourceRevision(2);
        graph.setResourceHash("a".repeat(64));
        graph.setFunction(true);
        CompiledGraphMetadataProvider provider = new CompiledGraphMetadataProvider(
            () -> {
                throw new AssertionError("Function metadata rejection must precede catalog resolution");
            },
            () -> {
                throw new AssertionError("Function metadata rejection must precede runtime resolution");
            },
            ServerId.deterministic("provider-function-test"),
            new FlowValueCodecRegistry());

        CompiledGraphMetadataProvider.Result result = provider.provide(graph);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> "function-source-v1".equals(value.evidence().get("boundary"))));
    }

    @Test
    void acceptsACompiledGraphWhenCatalogAndRuntimeShareTheActiveManifestIdentity() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        FlowGraph graph = new FlowGraph();
        graph.setId("compiled-flow");
        graph.setResourceType("flow");
        graph.setResourceRevision(1);
        graph.setResourceHash("a".repeat(64));
        graph.getNodes().put("source", new FlowNode("source", 0, 0, Map.of()));

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            ServerId.deterministic("manifest-authority-test"),
            new FlowValueCodecRegistry()).provide(graph);

        assertTrue(result.accepted(), result.diagnostics()::toString);
    }

    @Test
    void acceptsPersistedHandlerConfigurationOnlyWhenItMatchesTheActiveDefinition() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation, Map.of("operation", "operation"));
        FlowGraph graph = new FlowGraph();
        graph.setId("configured-flow");
        graph.setResourceType("flow");
        graph.setResourceRevision(1);
        graph.setResourceHash("a".repeat(64));
        FlowNode node = new FlowNode("source", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "operation"));
        graph.getNodes().put("source", node);

        CompiledGraphMetadataProvider.Result accepted = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            ServerId.deterministic("handler-config-test"),
            new FlowValueCodecRegistry()).provide(graph);

        assertTrue(accepted.accepted(), accepted.diagnostics()::toString);
        assertEquals("{\"operation\":\"operation\"}", accepted.metadata().handlerConfigCanonical().get("source"));

        node.setHandlerConfig(Map.of("operation", "tampered"));
        CompiledGraphMetadataProvider.Result rejected = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            ServerId.deterministic("handler-config-test"),
            new FlowValueCodecRegistry()).provide(graph);

        assertFalse(rejected.accepted());
        assertTrue(rejected.diagnostics().stream().anyMatch(value -> value.evidence().get("field").equals("handlerConfig")));
    }

    @Test
    void convertsKnownLegacyResourcePinValuesToTheInjectedServerIdentity() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("fixture", "quest"));
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(CAPABILITY, OPERATION,
            List.of(new RuntimeOperationDescriptor.Pin("target", RuntimeOperationDescriptor.Direction.INPUT, resourceType)),
            semantics());
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin(
            "target", CatalogNodeDescriptor.Direction.INPUT, resourceType, "Target",
            "Selects the authoritative quest resource for this operation.",
            CatalogNodeDescriptor.Requirement.REQUIRED, ContractRef.of(OWNER, CapabilityId.of("generic-editor")));
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch(
            "failed", "Failed", "The operation completed with a structured failure.",
            List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation returned a structured failure.")));
        CatalogNodeDescriptor definition = CatalogNodeDescriptor.builder("source")
            .domain("flow")
            .family("operation")
            .displayName("Source")
            .description("Runs one deterministic operation with an explicit runtime contract.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(List.of(pin))
            .branches(List.of(failure))
            .handler(new CatalogNodeDescriptor.Handler(CAPABILITY, OPERATION))
            .semantics(operation.semantics())
            .requiredCapabilities(Set.of(CAPABILITY))
            .metadata(Map.of("sourceNodeId", "source", "handlerConfig", Map.of()))
            .build();
        CatalogCompilationResult compiled = new CatalogCompiler(CONTRACT, CatalogBindingProof.live(registry))
            .compile(List.of(CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                    CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/manifest-authority.json", "1.0.0", "test", "resource-pin"))
                .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Flow operations for tests.", 1)))
                .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(CapabilityId.of("capability"), 1, false, InspectorFallback.GENERIC)))
                .definitions(List.of(definition))
                .runtimeRequirements(List.of(operation)).build()), 1);
        assertTrue(compiled.accepted(), compiled.diagnostics()::toString);
        CatalogSnapshot catalog = compiled.snapshot().orElseThrow();
        ServerId server = ServerId.deterministic("resource-pin-test");
        FlowGraph graph = new FlowGraph();
        graph.setId("resource-flow");
        graph.setResourceType("flow");
        graph.setResourceRevision(1);
        graph.setResourceHash("a".repeat(64));
        FlowNode node = new FlowNode("source", 0, 0, Map.of("target", new FlowResourceReference("quest", "main", "fixture")));
        graph.getNodes().put("source", node);

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(), server, new FlowValueCodecRegistry()).provide(graph);

        assertTrue(result.accepted(), result.diagnostics()::toString);
        assertEquals(server, result.metadata().inputValues().get(new CompiledGraphMetadata.PinAddress("source", "target")).locator().serverId());
    }

    @Test
    void rejectsACompiledGraphWhenTheRuntimeManifestChangesAfterCatalogCompilation() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        registry.unload(provider);
        FlowGraph graph = new FlowGraph();
        graph.setId("compiled-flow");
        graph.setResourceType("flow");
        graph.setResourceRevision(1);
        graph.setResourceHash("a".repeat(64));
        graph.getNodes().put("source", new FlowNode("source", 0, 0, Map.of()));

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            ServerId.deterministic("manifest-authority-test"),
            new FlowValueCodecRegistry()).provide(graph);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.RUNTIME_BINDING_MISSING")
            && value.evidence().get("catalogBindingManifestHash").equals(catalog.bindingManifestHash().canonicalText())));
    }

    @Test
    void acceptsCoreGraphWithPersistedStableNodeAndConnectionIdentities() throws Exception {
        RuntimeAuthority authority = new RuntimeAuthority("core-branch-test");
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority requested, ContractRef<CapabilityId> capability) {
                return authority.equals(requested) && OWNER.equals(capability.owner());
            }

            @Override
            public boolean confirm(RuntimeAuthority requested, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return authority.equals(requested) && confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        };
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, RuntimeAuditBoundary.unavailable(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(false));
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        TypeExpr execution = TypeExpr.named(TypeReference.of("builtin", "execution"));
        TypedValue token = TypedValue.value(execution, true);
        TypedValue configured = TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "Selected Value");
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(CAPABILITY, OPERATION, List.of(
            new RuntimeOperationDescriptor.Pin("input", RuntimeOperationDescriptor.Direction.INPUT, configured.type()),
            new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, execution)),
            semantics(Set.of("selected", "other")));
        RuntimeOperationDescriptor targetOperation = new RuntimeOperationDescriptor(CAPABILITY, TARGET_OPERATION, List.of(
            new RuntimeOperationDescriptor.Pin("target-input", RuntimeOperationDescriptor.Direction.INPUT, execution)), semantics());
        NodeInstanceId sourceId = NodeInstanceId.deterministic("core-source");
        NodeInstanceId targetId = NodeInstanceId.deterministic("core-target");
        NodeInstanceId otherId = NodeInstanceId.deterministic("core-other");
        GraphEndpoint targetEndpoint = new GraphEndpoint(targetId, PinId.of("target-input"), null, BranchId.of("failed"));
        List<Map<GraphEndpoint, TypedValue>> invoked = new ArrayList<>();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(
                RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                    assertEquals(configured, invocation.inputs().get(PinId.of("input")));
                    return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(PinId.of("output"), token), "selected"));
                }),
                RuntimeBinding.available(targetOperation, provider, "1.0.0", invocation -> {
                    invoked.add(invocation.routedInputs());
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                })));
        CatalogSnapshot catalog = catalogWithDistinctNodeDefinitions(registry, operation, targetOperation);
        BranchBinding selected = new BranchBinding(BranchId.of("selected"), CaseId.of("result"),
            List.of(new BranchCase(CaseId.of("result"), Map.of(PinId.of("input"), new PinValue(PinId.of("input"), configured)))));
        BranchBinding other = new BranchBinding(BranchId.of("other"), CaseId.of("result"),
            List.of(new BranchCase(CaseId.of("result"), Map.of())));
        BranchBinding failed = new BranchBinding(BranchId.of("failed"), CaseId.of("failure"),
            List.of(new BranchCase(CaseId.of("failure"), Map.of())));
        GraphNode source = new GraphNode(sourceId, ContractRef.of(OWNER, NodeId.of("source")), 1, null, Map.of(), Map.of(),
            List.of(selected, other), List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
        GraphNode target = new GraphNode(targetId, ContractRef.of(OWNER, NodeId.of("target")), 1, null, Map.of(), Map.of(),
            List.of(failed), List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
        ConnectionId connectionId = ConnectionId.deterministic("core-connection");
        GraphConnection selectedConnection = new GraphConnection(connectionId,
            new GraphEndpoint(sourceId, PinId.of("output"), null, selected.branchId()), targetEndpoint);
        GraphConnection otherConnection = new GraphConnection(ConnectionId.deterministic("core-other-connection"),
            new GraphEndpoint(sourceId, PinId.of("output"), null, other.branchId()), targetEndpoint);
        GraphConnection inactiveConnection = new GraphConnection(ConnectionId.deterministic("core-inactive-connection"),
            otherConnection.source(), new GraphEndpoint(otherId, PinId.of("target-input")));
        GraphDocument graph = coreGraph(catalog, "flow", "core-stable", 4, List.of(source, target,
            new GraphNode(otherId, target.definition(), 1, Map.of())), List.of(selectedConnection, otherConnection, inactiveConnection));

        CompiledGraphMetadataProvider metadataProvider = new CompiledGraphMetadataProvider(() -> catalog,
            () -> registry.snapshot().manifest(), CORE_SERVER, new FlowValueCodecRegistry());
        CompiledGraphMetadataProvider.Result result = metadataProvider.provide(graph, null);
        assertTrue(result.accepted(), result.diagnostics()::toString);
        assertEquals(graph.resource(), result.metadata().resource());
        assertEquals(graph.catalogBinding(), result.metadata().catalogBinding());
        assertEquals(sourceId, result.metadata().nodeInstances().get(sourceId.canonicalText()));
        assertEquals(targetId, result.metadata().nodeInstances().get(targetId.canonicalText()));
        assertEquals(connectionId, result.metadata().connections().get(
            CompiledGraphMetadata.ConnectionAddress.of(selectedConnection.source(), targetEndpoint)));
        assertEquals(3, result.metadata().connections().size());
        assertEquals(configured, result.metadata().inputValues().get(new CompiledGraphMetadata.PinAddress(sourceId.canonicalText(), "input")));
        assertEquals(selectedConnection, result.mappingContext().connectionMappings().get(connectionId));
        assertTrue(result.mappingContext().validationFailure(graph, sourceId.canonicalText()).isEmpty());
        FlowGraph shell = new FlowGraph();
        shell.setId(graph.resource().id());
        shell.setResourceType(graph.resource().resourceType().canonicalText());
        shell.setResourceRevision(graph.revision());
        FlowExecutionBridge.Context context = new FlowExecutionBridge.Context(shell, sourceId.canonicalText(), null, null, Map.of(),
            result.mappingContext(), result.metadata(), CompiledRuntimeContext.empty(), CorrelationId.random(),
            RuntimeExecutionContext.NO_DEADLINE, graph);
        FlowExecutionBridge.Result executed = new CompiledCoreFlowExecutionBridge(() -> catalog,
            () -> registry.snapshot().manifest(), registry, authority).execute(context).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(FlowExecutionBridge.Status.EXECUTED, executed.status());
        assertEquals(List.of(Map.of(targetEndpoint, token)), invoked);

        BranchBinding conflicting = new BranchBinding(other.branchId(), other.selectedCaseId(), List.of(new BranchCase(CaseId.of("result"),
            Map.of(PinId.of("input"), new PinValue(PinId.of("input"), TypedValue.value(configured.type(), "Other Value"))))));
        GraphNode conflict = new GraphNode(sourceId, source.definition(), 1, null, Map.of(), Map.of(), List.of(selected, conflicting),
            List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
        CompiledGraphMetadataProvider.Result rejected = metadataProvider.provide(
            coreGraph(catalog, "flow", "core-stable", 4, List.of(conflict), List.of()), null);
        assertFalse(rejected.accepted());
        assertTrue(rejected.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.BRANCH_INPUT_AMBIGUOUS")));

        GraphDocument changed = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(),
            graph.catalogBinding(), graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(),
            graph.functions(), OpaqueData.of(Map.of("future", Map.of("preserved", true))));
        CompiledGraphMetadataProvider.Result changedResult = metadataProvider.provide(changed, null);
        assertTrue(changedResult.accepted(), changedResult.diagnostics()::toString);
        assertNotEquals(result.metadata().snapshotId(), changedResult.metadata().snapshotId());
    }

    @Test
    void rejectsCoreGraphWhenPersistedChecksumBindingDoesNotMatchActiveCatalog() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("core-checksum"), ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of());
        CatalogBinding wrongBinding = new CatalogBinding(catalog.generation(),
            new ContentHash("f".repeat(64)), catalog.bindingManifestHash());
        GraphDocument graph = new GraphDocument(coreResource("flow", "core-checksum"), 2, wrongBinding, List.of(node), List.of());

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, null);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.CATALOG_MISMATCH")));
    }

    @Test
    void acceptsCoreGraphWithRepeatedDefinitionsAndCanonicalPinsInScopedMappings() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operationWithFlowPin();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        NodeInstanceId firstId = NodeInstanceId.deterministic("core-duplicate-source");
        NodeInstanceId secondId = NodeInstanceId.deterministic("core-duplicate-target");
        GraphDocument graph = coreGraph(catalog, "flow", "core-duplicate-definition", 2, List.of(
            new GraphNode(firstId, ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of()),
            new GraphNode(secondId, ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of())), List.of());

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, null);

        assertTrue(result.accepted(), result.diagnostics()::toString);
        FlowExecutionBridge.MappingContext mapping = result.mappingContext();
        assertEquals(graph.resource(), mapping.resource());
        assertEquals(graph.catalogBinding(), mapping.catalogBinding());
        assertEquals(result.metadata().snapshotId(), mapping.snapshotId());
        assertEquals(Map.of(
            firstId.canonicalText(), NodeId.of("source"),
            secondId.canonicalText(), NodeId.of("source")), mapping.nodeMappings());
        assertEquals(Map.of(
            firstId.canonicalText() + "/flow", PinId.of("flow"),
            secondId.canonicalText() + "/flow", PinId.of("flow")), mapping.pinMappings());
    }

    @Test
    void rejectsCoreFunctionWhenSourceLocatorOrRevisionDoesNotMatch() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("core-function"), ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of());
        GraphDocument graph = coreGraph(catalog, "function", "core-function", 3, List.of(node), List.of());
        GraphDocument otherGraph = coreGraph(catalog, "function", "other-function", 4, List.of(node), List.of());
        FunctionSourceDocument wrongSource = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(otherGraph.resource()), new FunctionRevision(otherGraph.revision()), List.of(), List.of()),
            otherGraph);

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, wrongSource);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.OPAQUE_UNAVAILABLE")
            && "functionSource".equals(value.evidence().get("field"))));
    }

    @Test
    void rejectsCoreFunctionWhenSourceBodyChecksumDoesNotMatchGraphIdentity() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("core-function-body"), ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of());
        GraphDocument graph = coreGraph(catalog, "function", "core-function-body", 3, List.of(node), List.of());
        GraphDocument changedBody = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), List.of(new GraphNode(NodeInstanceId.deterministic("core-function-body-changed"),
            ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of())), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
        FunctionSourceDocument source = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(graph.resource()), new FunctionRevision(graph.revision()), List.of(), List.of()),
            changedBody);

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, source);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.OPAQUE_UNAVAILABLE")
            && "functionSource".equals(value.evidence().get("field"))
            && value.evidence().containsKey("graphChecksum")
            && value.evidence().containsKey("sourceGraphChecksum")));
    }

    @Test
    void acceptsCoreFunctionWithValidatedMatchingSourceDocument() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = operation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CatalogSnapshot catalog = catalog(registry, operation);
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("core-function-accepted"), ContractRef.of(OWNER, NodeId.of("source")), 1, Map.of());
        GraphDocument graph = coreGraph(catalog, "function", "core-function-accepted", 3, List.of(node), List.of());
        FunctionSourceDocument source = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(graph.resource()), new FunctionRevision(graph.revision()), List.of(), List.of()),
            graph, OpaqueData.of(Map.of("future", Map.of("preserved", true))));

        CompiledGraphMetadataProvider.Result result = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, source);

        assertTrue(result.accepted(), result.diagnostics()::toString);
        assertEquals(source, result.metadata().functionSource());
        assertEquals(true, ((Map<?, ?>) result.metadata().functionSource().unknown().get("future")).get("preserved"));

        FunctionSourceDocument changedSource = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(graph.resource()), new FunctionRevision(graph.revision()), List.of(), List.of()),
            graph, OpaqueData.of(Map.of("future", Map.of("preserved", false))));
        CompiledGraphMetadataProvider.Result changedResult = new CompiledGraphMetadataProvider(
            () -> catalog,
            () -> registry.snapshot().manifest(),
            CORE_SERVER,
            new FlowValueCodecRegistry()).provide(graph, changedSource);

        assertTrue(changedResult.accepted(), changedResult.diagnostics()::toString);
        assertNotEquals(result.metadata().snapshotId(), changedResult.metadata().snapshotId());
    }

    private static CompiledGraphMetadataProvider provider(Object catalog, Object manifest) {
        return new CompiledGraphMetadataProvider(
            () -> (CatalogSnapshot) catalog,
            () -> (RuntimeBindingManifest) manifest,
            ServerId.deterministic("provider-test"),
            new FlowValueCodecRegistry());
    }

    private static final OwnerId OWNER = OwnerId.of("resync.test");
    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.resync");
    private static final ServerId CORE_SERVER = ServerId.deterministic("core-provider-test");
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("capability"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(OWNER, OperationId.of("operation"));
    private static final ContractRef<OperationId> TARGET_OPERATION = ContractRef.of(OWNER, OperationId.of("target-operation"));

    private static CatalogSnapshot catalog(RuntimeBindingRegistry registry, RuntimeOperationDescriptor operation) {
        return catalog(registry, operation, Map.of());
    }

    private static CatalogSnapshot catalog(RuntimeBindingRegistry registry, RuntimeOperationDescriptor operation,
                                           Map<String, Object> handlerConfig) {
        CatalogCompilationResult result = new CatalogCompiler(CONTRACT, CatalogBindingProof.live(registry))
            .compile(List.of(contribution(operation, handlerConfig)), 1);
        assertTrue(result.accepted(), result.diagnostics()::toString);
        return result.snapshot().orElseThrow();
    }

    private static CatalogSnapshot catalogWithDistinctNodeDefinitions(RuntimeBindingRegistry registry,
                                                                       RuntimeOperationDescriptor sourceOperation,
                                                                       RuntimeOperationDescriptor targetOperation) {
        CatalogCompilationResult result = new CatalogCompiler(CONTRACT, CatalogBindingProof.live(registry))
            .compile(List.of(contributionWithDistinctOperations(sourceOperation, targetOperation)), 1);
        assertTrue(result.accepted(), result.diagnostics()::toString);
        return result.snapshot().orElseThrow();
    }

    private static CatalogContribution contribution(RuntimeOperationDescriptor operation) {
        return contribution(operation, Map.of());
    }

    private static CatalogContribution contribution(RuntimeOperationDescriptor operation, Map<String, Object> handlerConfig) {
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch(
            "failed", "Failed", "The operation completed with a structured failure.",
            List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation returned a structured failure.")));
        return catalogContribution(
            List.of(nodeDefinition("source", operation, handlerConfig, failure, "", CAPABILITY, OPERATION)),
            List.of(operation));
    }

    private static CatalogContribution contributionWithDistinctOperations(RuntimeOperationDescriptor sourceOperation,
                                                                           RuntimeOperationDescriptor targetOperation) {
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch(
            "failed", "Failed", "The operation completed with a structured failure.",
            List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation returned a structured failure.")));
        return catalogContribution(
            List.of(
                nodeDefinition("source", sourceOperation, Map.of(), failure, "", CAPABILITY, OPERATION),
                nodeDefinition("target", targetOperation, Map.of(), failure, "", CAPABILITY, TARGET_OPERATION)),
            List.of(sourceOperation, targetOperation));
    }

    private static CatalogContribution catalogContribution(List<CatalogNodeDescriptor> definitions,
                                                            List<RuntimeOperationDescriptor> runtimeRequirements) {
        return CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/manifest-authority.json", "1.0.0", "test", "manifest-authority"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Flow operations for tests.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("capability"), 1, false, InspectorFallback.GENERIC)))
            .definitions(definitions)
            .runtimeRequirements(runtimeRequirements)
            .build();
    }

    private static CatalogNodeDescriptor nodeDefinition(String id, RuntimeOperationDescriptor operation,
                                                         Map<String, Object> handlerConfig,
                                                         CatalogNodeDescriptor.Branch failure, String pinPrefix,
                                                         ContractRef<CapabilityId> capability,
                                                         ContractRef<OperationId> handlerOperation) {
        return CatalogNodeDescriptor.builder(id)
            .domain("flow")
            .family("operation")
            .displayName("Source")
            .description("Runs one deterministic operation with an explicit runtime contract.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(operation.pins().stream().map(pin -> new CatalogNodeDescriptor.Pin(
                pinPrefix + pin.id().canonicalText(),
                pin.direction() == RuntimeOperationDescriptor.Direction.INPUT
                    ? CatalogNodeDescriptor.Direction.INPUT : CatalogNodeDescriptor.Direction.OUTPUT,
                pin.type(),
                pin.id().canonicalText(),
                "Provides the configured value for this test pin.",
                CatalogNodeDescriptor.Requirement.OPTIONAL,
                CAPABILITY)).toList())
            .branches(Stream.concat(Stream.of(failure), operation.semantics().successBranches().stream().sorted().map(branch ->
                new CatalogNodeDescriptor.Branch(branch, branch, "Selects this runtime result.",
                    List.of(new CatalogNodeDescriptor.Case("result", "Result", "Uses this result."))))).toList())
            .handler(new CatalogNodeDescriptor.Handler(capability, handlerOperation))
            .semantics(operation.semantics())
            .requiredCapabilities(Set.of(capability))
            .metadata(Map.of("sourceNodeId", id, "handlerConfig", handlerConfig))
            .build();
    }

    private static RuntimeOperationDescriptor operation() {
        return new RuntimeOperationDescriptor(CAPABILITY, OPERATION, List.of(), semantics());
    }

    private static RuntimeOperationDescriptor operationWithPins() {
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeOperationDescriptor(CAPABILITY, OPERATION, List.of(
            new RuntimeOperationDescriptor.Pin("input", RuntimeOperationDescriptor.Direction.INPUT, text),
            new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, text)), semantics());
    }

    private static RuntimeOperationDescriptor operationWithFlowPin() {
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeOperationDescriptor(CAPABILITY, OPERATION, List.of(
            new RuntimeOperationDescriptor.Pin("flow", RuntimeOperationDescriptor.Direction.INPUT, text)), semantics());
    }

    private static RuntimeOperationDescriptor operationWithTargetPins() {
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeOperationDescriptor(CAPABILITY, TARGET_OPERATION, List.of(
            new RuntimeOperationDescriptor.Pin("target-input", RuntimeOperationDescriptor.Direction.INPUT, text),
            new RuntimeOperationDescriptor.Pin("target-output", RuntimeOperationDescriptor.Direction.OUTPUT, text)), semantics());
    }

    private static GraphDocument coreGraph(CatalogSnapshot catalog, String type, String id, long revision,
                                           List<GraphNode> nodes, List<GraphConnection> connections) {
        return new GraphDocument(coreResource(type, id), revision,
            new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()), nodes, connections);
    }

    private static ServerResourceLocator coreResource(String type, String id) {
        return new ServerResourceLocator(CORE_SERVER,
            ContractRef.of(RESOURCE_OWNER, ResourceTypeId.of(type)), id);
    }

    private static RuntimeSemantics semantics() {
        return semantics(Set.of());
    }

    private static RuntimeSemantics semantics(Set<String> successBranches) {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorization")),
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            successBranches,
            Set.of("failed"),
            Set.of(),
            new RuntimeFailureContract(string, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }
}
