package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledGraphMaterializer;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledRuntimeContextAdapter;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.ServerCompiledPlanRepository;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.FlowStorageCoreGraphResourceAuthority;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentDispatchRuntimeTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion CONTRACT = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("content-test"));
    private static final TypeExpr FLOW = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private final List<String> observedTriggers = new ArrayList<>();
    private final AtomicReference<CompiledRuntimeContext> observedContext = new AtomicReference<>();
    private final AtomicInteger compilations = new AtomicInteger();
    private final AtomicReference<CatalogRuntimeActivation.ActivationRecord> activation = new AtomicReference<>();
    private CustomContentStorage contentStorage;
    private FlowStorage flowStorage;
    private CustomContentService service;
    private CustomContentExecution contentExecution;
    private CompiledGraphMetadataProvider metadata;
    private ServerCompiledPlanRepository plans;
    private FlowExecutor executor;
    private AssetTransactionCoordinator coordinator;
    private RuntimeBindingRegistry bindings;
    private CatalogContribution contribution;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetPersistenceGate gate = new AssetPersistenceGate(root);
        coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        ServerId server = CanonicalProjectMetadataFixture.serverId();
        contentStorage = new CustomContentStorage(plugin, root, new ItemAttributeSchemaService(),
            LegacyRuntimeActivationGate.runtime(root), gate, coordinator);
        flowStorage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), gate, server, coordinator);
        executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        bindings = new RuntimeBindingRegistry(new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> requested) {
                return CAPABILITY.equals(requested);
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        }, RuntimeAuditBoundary.unavailable(), new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true),
            RuntimeReceiptStore.inMemory(false));
        contribution = contribution();
        activateCatalog(1);
        metadata = new CompiledGraphMetadataProvider(activation::get, server, new FlowValueCodecRegistry());
        contentExecution = new CustomContentExecution(contentStorage, new FlowStorageCoreGraphResourceAuthority(flowStorage, server,
            new CoreGraphMutationValidator(server, activation::get, CatalogActivationAuthority::freshInstall)),
            metadata, server, coordinator);
        plans = new ServerCompiledPlanRepository(contentExecution, activation::get, (graph, catalog, runtime) -> {
            compilations.incrementAndGet();
            return new GraphCompiler(runtime).compile(graph, catalog);
        });
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(activation::get, bindings,
            RuntimeAuthority.anonymous(), null, plans);
        plans.bindTemplateCompiler(bridge::prepare);
        plans.bindMetadataCompiler(metadata::provide);
        executor.configureExecutionBridge(bridge);
        executor.setCompiledExecutionAuthority((graph, compiled) -> plans.isExecutionAuthorized(compiled,
            graph.getResourceRevision(), graph.getResourceHash()));
        CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, metadata, bridge,
            (reportId, diagnostics) -> reportId, null, plans);
        execution.bindCoreStorage(flowStorage, server);
        contentExecution.bind(plans, execution);
        contentExecution.refreshAll();
        plans.initialize();
        service = new CustomContentService(contentStorage, flowStorage, executor);
        service.setCompiledExecution(contentExecution);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (contentExecution != null) contentExecution.close();
            if (plans != null) plans.close();
            if (executor != null) executor.shutdown();
            if (flowStorage != null) flowStorage.quiescePersistence();
            if (contentStorage != null) contentStorage.close();
        } finally {
            if (coordinator != null) coordinator.close();
            MockBukkit.unmock();
        }
    }

    @Test
    void firstCreateAdmissionUsesTheIntendedDurableIdentity() {
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(
            CustomContentGraphAdapter.createContentGraph("first-item", "item", "First Item"));
        Map<String, Object> payload = new Gson().fromJson(FlowSerializer.serializeCustomContent(content), Map.class);
        String payloadHash = ResourcePayloadCodecs.json().hashPayload(payload).canonicalText();
        FlowResourceMutationStamp intended = new FlowResourceMutationStamp("custom_content", "first-item", 1L,
            UUID.fromString("60000000-0000-4000-8000-000000000006"), payloadHash, false);

        assertDoesNotThrow(() -> contentExecution.admit(content, intended));
    }

    @Test
    void newlyCreatedItemUsesTheProductionAuthoredDescriptorAndMigration() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("g", "item", "g");
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        String instance = "3f0cfaf3-3776-46ea-949b-e623832dca0d";
        graph.getNodes().clear();
        graph.getNodes().put(instance, start);
        assertEquals("content.item.g", graph.getId());
        assertEquals(1, start.getVersion());
        assertTrue(start.getHandlerConfigValues().isEmpty());
        contentStorage.save(CustomContentGraphAdapter.toDefinition(graph));
        FlowGraph prepared = contentExecution.graph("g");
        assertNotNull(prepared, () -> projectionFailure("g"));
        assertEquals(1, prepared.getNodes().size());
        FlowNode admitted = prepared.getNodes().values().iterator().next();
        assertEquals("restudio.resync/custom_content.item", admitted.getType());
        assertEquals(2, admitted.getVersion());
        int compiled = compilations.get();
        contentExecution.execute(prepared, prepared.getNodes().keySet().iterator().next(), null, null,
            Map.of("event.content_id", "g", "event.content_type", "item", "event.trigger", "item.use")).join();
        assertEquals(compiled, compilations.get());
        FlowGraph persisted = contentStorage.get("g").getGraph();
        assertTrue(persisted.getNodes().containsKey(instance));
        assertEquals(1, persisted.getNodes().get(instance).getVersion());
        assertEquals("content.item.g", persisted.getId());
    }

    @Test
    void everyAdvertisedContentTriggerUsesTheResidentCoreBridgeAndItsOwnBranch() {
        List<String> expected = new ArrayList<>();
        for (String type : List.of("item", "block", "armor", "projectile")) {
            String id = "runtime_" + type;
            saveContentGraph(id, type);
            assertNotNull(contentExecution.graph(id), () -> projectionFailure(id));
            if ("armor".equals(type)) {
                assertEquals("any", CustomContentGraphAdapter.findStartNode(contentStorage.get(id).getGraph()).getInputValues().get("hand_filter"));
            }
            int prepared = compilations.get();
            for (CustomContentGraphAdapter.TriggerDescriptor trigger : CustomContentGraphAdapter.triggersForType(type)) {
                expected.add(id + ":" + trigger.trigger() + ":original");
                service.dispatch(id, trigger.trigger(), null, null, Map.of());
                if (observedTriggers.size() != expected.size()) {
                    FlowGraph graph = contentExecution.graph(id);
                    String start = graph.getNodes().entrySet().stream()
                        .filter(entry -> entry.getValue().getType().endsWith(CustomContentGraphAdapter.nodeType(type)))
                        .findFirst().orElseThrow().getKey();
                    contentExecution.execute(graph, start, null, null,
                        Map.of("event.content_id", id, "event.trigger", trigger.trigger())).handle((result, failure) -> {
                            Throwable cause = failure;
                            while (cause != null && cause.getCause() != null) cause = cause.getCause();
                            throw new AssertionError(cause instanceof FlowExecutor.FlowExecutionException executionFailure
                                ? executionFailure.getDetails().toString() : String.valueOf(cause));
                        }).join();
                }
            }
            assertEquals(prepared, compilations.get());
        }
        assertEquals(expected, observedTriggers);
    }

    @Test
    void consumeListenerPreservesTypedPlayerAndItemContext() {
        saveContentGraph("resin", "item");
        Player player = MockBukkit.getMock().addPlayer();
        player.getWorld().getChunkAt(player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
        ItemStack item = service.createItem("resin", 1);
        assertNotNull(item);
        var context = CompiledRuntimeContextAdapter.adapt(CanonicalProjectMetadataFixture.serverId(), player, null,
            Map.of("event.item", item, "event.location", player.getLocation(), "event.block", player.getLocation().getBlock()));
        assertTrue(context.accepted(), context::failure);
        new CustomContentListener(contentStorage, service).onConsume(new PlayerItemConsumeEvent(player, item, EquipmentSlot.HAND));
        assertEquals(List.of("resin:item.consume:original"), observedTriggers);
        assertEquals(player.getUniqueId(), observedContext.get().player().uniqueId());
        assertNotNull(observedContext.get().variables().get("event.item"));
    }

    @Test
    void repeatedDispatchReusesThePreparedGraph() {
        saveContentGraph("resin", "item");
        FlowGraph prepared = contentExecution.graph("resin");
        assertNotNull(prepared, () -> projectionFailure("resin"));
        int compiled = compilations.get();
        service.dispatch("resin", "item.use", null, null, Map.of());
        service.dispatch("resin", "item.use", null, null, Map.of());
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertSame(prepared, contentExecution.graph("resin"));
        assertEquals(compiled, compilations.get());
        assertEquals(List.of("resin:item.use:original", "resin:item.use:original", "resin:item.use:original"), observedTriggers);
    }

    @Test
    void committedMutationReplacesThePreparedRevisionAndDeleteRetiresIt() {
        saveContentGraph("resin", "item");
        FlowGraph old = contentExecution.graph("resin");
        CustomContentDefinition edited = contentStorage.get("resin");
        edited.getGraph().getNodes().get("capture").getInputValues().put("label", "edited");
        contentStorage.save(edited);
        FlowGraph updated = contentExecution.graph("resin");
        assertNotNull(updated);
        assertTrue(updated.getResourceRevision() > old.getResourceRevision());
        assertThrows(Exception.class, () -> contentExecution.execute(old, "missing", null, null, Map.of()).join());
        int prepared = compilations.get();
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:edited"), observedTriggers);
        assertEquals(prepared, compilations.get());
        contentStorage.delete("resin");
        assertNull(contentExecution.graph("resin"));
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(1, observedTriggers.size());
    }

    @Test
    void rejectedMutationFailsClosedAndAValidSaveRecovers() {
        saveContentGraph("resin", "item");
        CustomContentDefinition edited = contentStorage.get("resin");
        edited.getGraph().getNodes().get("capture").setType("missing");
        contentStorage.save(edited);
        assertNull(contentExecution.graph("resin"));
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertTrue(observedTriggers.isEmpty());
        edited.getGraph().getNodes().get("capture").setType("capture");
        contentStorage.save(edited);
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:original"), observedTriggers);
    }

    @Test
    void catalogRefreshReadmitsContentAndShutdownRejectsDispatch() {
        saveContentGraph("resin", "item");
        activateCatalog(2);
        contentExecution.refreshCatalog();
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:original"), observedTriggers);
        FlowGraph prepared = contentExecution.graph("resin");
        contentExecution.close();
        assertThrows(Exception.class, () -> contentExecution.execute(prepared, "missing", null, null, Map.of()).join());
    }

    @Test
    void embeddedCoreDocumentOwnsExecutionAndInvalidEmbeddedStateNeverFallsBack() {
        saveContentGraph("resin", "item");
        GraphDocument source = contentExecution.list("custom_content").getFirst().envelope().graphDocument();
        List<GraphNode> nodes = source.nodes().stream().map(node -> {
            if (!"capture".equals(node.definition().id().value())) return node;
            Map<PinId, PinValue> values = new LinkedHashMap<>(node.values());
            PinId label = PinId.of("label");
            values.put(label, new PinValue(label, TypedValue.value(STRING, "typed")));
            return new GraphNode(node.instanceId(), node.definition(), node.definitionVersion(), node.modeId(), values,
                node.inspectorFields(), node.branches(), node.repeatables(), node.inspectorState(), node.x(), node.y(), node.unknown());
        }).toList();
        GraphDocument edited = new GraphDocument(source.schemaVersion(), source.resource(), source.revision(), source.catalogBinding(),
            source.requiredCapabilities(), nodes, source.connections(), source.variables(), source.functions(), source.unknown());
        CustomContentDefinition content = contentStorage.get("resin");
        content.getGraph().getOpaqueProperties().put("contentCoreGraph", JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(edited)));
        contentStorage.save(content);
        assertTrue(contentStorage.get("resin").getGraph().getOpaqueProperties().containsKey("contentCoreGraph"));
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:typed"), observedTriggers);
        assertTrue(contentExecution.graph("resin").getResourceRevision() > source.revision());
        content.getGraph().getOpaqueProperties().put("contentCoreGraph", new JsonObject());
        contentStorage.save(content);
        assertNull(contentExecution.graph("resin"));
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(1, observedTriggers.size());
    }

    @Test
    void embeddedCatalogRebindRequiresAForwardBindingAndCompatibleTypedState() {
        saveContentGraph("resin", "item");
        GraphDocument source = contentExecution.list("custom_content").getFirst().envelope().graphDocument();
        activateCatalog(2);
        contentExecution.refreshCatalog();
        CustomContentDefinition content = contentStorage.get("resin");
        content.getGraph().getOpaqueProperties().put("contentCoreGraph", JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(source)));
        contentStorage.save(content);
        assertNotNull(contentExecution.graph("resin"));
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:original"), observedTriggers);
        CatalogBinding current = contentExecution.list("custom_content").getFirst().envelope().graphDocument().catalogBinding();
        for (CatalogBinding invalid : List.of(new CatalogBinding(current.generation(), "f".repeat(64), current.bindingManifestHash().canonicalText()),
            new CatalogBinding(current.generation() + 1, current.catalogChecksum(), current.bindingManifestHash()))) {
            GraphDocument changed = new GraphDocument(source.schemaVersion(), source.resource(), source.revision(), invalid,
                source.requiredCapabilities(), source.nodes(), source.connections(), source.variables(), source.functions(), source.unknown());
            content.getGraph().getOpaqueProperties().put("contentCoreGraph", JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(changed)));
            contentStorage.save(content);
            assertNull(contentExecution.graph("resin"));
        }
        List<GraphNode> incompatible = source.nodes().stream().map(node -> {
            if (!"capture".equals(node.definition().id().value())) return node;
            PinId label = PinId.of("label");
            Map<PinId, PinValue> values = new LinkedHashMap<>(node.values());
            values.put(label, new PinValue(label, TypedValue.value(FLOW, true)));
            return new GraphNode(node.instanceId(), node.definition(), node.definitionVersion(), node.modeId(), values,
                node.inspectorFields(), node.branches(), node.repeatables(), node.inspectorState(), node.x(), node.y(), node.unknown());
        }).toList();
        GraphDocument changed = new GraphDocument(source.schemaVersion(), source.resource(), source.revision(), source.catalogBinding(),
            source.requiredCapabilities(), incompatible, source.connections(), source.variables(), source.functions(), source.unknown());
        content.getGraph().getOpaqueProperties().put("contentCoreGraph", JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(changed)));
        contentStorage.save(content);
        assertNull(contentExecution.graph("resin"));
    }

    @Test
    void externalFlowUsesFlowAuthorityWithoutCollidingWithContentIdentity() {
        FlowGraph external = new FlowGraph("resin", Map.of(
            "start", new FlowNode("external.start", 0, 0, Map.of()),
            "capture", new FlowNode("capture", 100, 0, Map.of("label", "external"))),
            List.of(new FlowConnection("start", "flow", "capture", "flow")), List.of());
        external.setResourceType("flow");
        external.setResourceRevision(1);
        external.setResourceHash("a".repeat(64));
        var mapped = metadata.provide(external);
        assertTrue(mapped.accepted(), mapped.diagnostics()::toString);
        GraphDocument document = new CompiledGraphMaterializer().materialize(external, mapped.metadata()).document();
        assertNotNull(document);
        flowStorage.saveCoreGraph(document, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        plans.reconcile(document.resource());
        saveContentGraph("resin", "item");
        CustomContentDefinition content = contentStorage.get("resin");
        content.setGraph(null);
        content.setFlowId("resin");
        content.setAbilities(List.of(new CustomAbilityBinding("resin.use", "item.use", "resin")));
        contentStorage.save(content);
        service.dispatch("resin", "item.use", null, null, Map.of());
        assertEquals(List.of("resin:item.use:external"), observedTriggers);
        assertNull(contentExecution.graph("resin"));
    }

    private String projectionFailure(String id) {
        FlowGraph graph = contentStorage.get(id).getGraph().copy();
        graph.setId(id);
        graph.setResourceType("custom_content");
        graph.setResourceRevision(contentStorage.readMutationStamp(id).revision());
        try {
            metadata.projectCustomContent(graph);
            return "Projection missing after metadata acceptance for " + id;
        } catch (CompiledGraphMetadataProvider.UnsupportedGraphException failure) {
            return failure.diagnostics().toString();
        } catch (RuntimeException failure) {
            return failure.toString();
        }
    }

    private void saveContentGraph(String id, String type) {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(id, type, "Runtime " + type);
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        String startId = graph.findNodeId(start);
        List<CustomContentGraphAdapter.TriggerDescriptor> triggers = CustomContentGraphAdapter.triggersForType(type);
        CustomContentGraphAdapter.setEnabledTriggerBranches(graph, triggers.stream().map(CustomContentGraphAdapter.TriggerDescriptor::pin).toList());
        int branch = 0;
        for (CustomContentGraphAdapter.TriggerDescriptor trigger : triggers) {
            String capture = branch++ == 0 ? "capture" : "capture_" + trigger.pin();
            graph.getNodes().put(capture, new FlowNode("capture", 500, 120, Map.of("label", "original")));
            graph.getConnections().add(new FlowConnection(startId, trigger.pin(), capture, "flow"));
        }
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);
        assertNotNull(definition);
        contentStorage.save(definition);
    }

    private void activateCatalog(long generation) {
        var result = new CatalogCompiler(CONTRACT, CatalogBindingProof.live(bindings))
            .compile(List.of(contribution), generation);
        assertTrue(result.accepted(), result.diagnostics()::toString);
        CatalogSnapshot catalog = result.snapshot().orElseThrow();
        activation.set(new CatalogRuntimeActivation.ActivationRecord(catalog, bindings.snapshot()));
    }

    private CatalogContribution contribution() throws IOException {
        List<CatalogNodeDescriptor> nodes = new ArrayList<>();
        List<RuntimeOperationDescriptor> operations = new ArrayList<>();
        List<RuntimeBinding> runtime = new ArrayList<>();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("content-test"));
        List<String> types = List.of("external", "capture");
        for (String type : types) {
            boolean capture = "capture".equals(type);
            String id = capture ? "capture" : "external.start";
            List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>();
            if (capture) {
                pins.add(pin("flow", CatalogNodeDescriptor.Direction.INPUT, FLOW));
                pins.add(pin("label", CatalogNodeDescriptor.Direction.INPUT, STRING));
            } else {
                pins.add(pin("flow", CatalogNodeDescriptor.Direction.OUTPUT, FLOW));
            }
            var operationId = ContractRef.of(OWNER, OperationId.of(id));
            RuntimeSemantics semantics = semantics();
            RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(CAPABILITY, operationId, pins.stream()
                .map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(), pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList(), semantics);
            operations.add(operation);
            runtime.add(RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                if (capture) {
                    observedContext.set(invocation.runtimeContext());
                    Map<String, TypedValue> vars = invocation.runtimeContext().variables();
                    observedTriggers.add(vars.get("event.content_id").value() + ":" + vars.get("event.trigger").value()
                        + ":" + invocation.inputs().get(PinId.of("label")).value());
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                }
                return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(PinId.of("flow"), TypedValue.value(FLOW, true)), null));
            }));
            nodes.add(CatalogNodeDescriptor.builder(id).schemaVersion(1)
                .domain(capture ? "test" : "custom_content").family(type).displayName(type).description("Exercises custom content Core execution.")
                .category(CAPABILITY).pins(pins)
                .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "Execution failed.",
                    List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "Execution failed.")))))
                .handler(new CatalogNodeDescriptor.Handler(CAPABILITY, operationId)).semantics(semantics)
                .requiredCapabilities(Set.of(CAPABILITY)).metadata(Map.of("sourceNodeId", id, "handlerConfig", Map.of())).build());
        }
        CatalogVersion version = CONTRACT;
        CatalogContribution fixture = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(version, version),
            CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/content-test.json", "1.0.0", "content-test", "content-test"))
            .categories(List.of(new CatalogCategoryDescriptor(CAPABILITY.id(), "Content", "Content execution tests.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CAPABILITY.id(), 1, false, InspectorFallback.GENERIC)))
            .definitions(nodes).runtimeRequirements(operations).build();
        CatalogContribution content = authoredContent(runtime, provider);
        bindings.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), runtime);
        return CatalogSourceIngestor.combineContributions(List.of(fixture, content));
    }

    private CatalogContribution authoredContent(List<RuntimeBinding> runtime, ContractRef<ProviderId> provider) throws IOException {
        byte[] bytes;
        try (var source = getClass().getClassLoader().getResourceAsStream("nodes/custom_content.json")) {
            assertNotNull(source);
            bytes = source.readAllBytes();
        }
        CatalogVersion version = CONTRACT;
        TypeExpr any = TypeExpr.named(TypeReference.of("builtin", "any"));
        InspectorCapability editor = new InspectorCapability(CAPABILITY, "Content Editor", "Edits the authored content inputs.",
            new InspectorValueSchema(any), new InspectorValueSchema(any), List.of(), CAPABILITY, InspectorFallback.GENERIC);
        var context = new CatalogSourceIngestor.CatalogIngestionContext(new CatalogContractRange(version, version),
            List.of(new CatalogCategoryDescriptor(CapabilityId.of("custom_content"), "Content", "Custom content definitions.", 1)),
            editor, id -> Optional.of(new InspectorOptionSource(id, "Content Options", "Resolves the declared content option type.",
                TypeExpr.named(TypeReference.of("builtin", "server-minecraft-material".equals(id.value()) ? "material" : "string")),
                OptionQuerySchemaV1.empty(), CAPABILITY, 100)), request -> {
                assertEquals(Map.of("operation", "content_start"), request.handlerConfig());
                RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(request.capability(), request.operation(), request.pins(), semantics());
                runtime.add(RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                    String trigger = CustomContentGraphAdapter.pinForTrigger((String) invocation.runtimeContext().variables().get("event.trigger").value());
                    Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
                    request.pins().stream().filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
                        .forEach(pin -> outputs.put(pin.id(), pin.id().value().equals(trigger)
                            ? TypedValue.value(pin.type(), true) : TypedValue.absent(pin.type())));
                    return CompletableFuture.completedFuture(RuntimeResult.success(outputs, null));
                }));
                return Optional.of(operation);
            }, Optional::of);
        Set<String> starts = Set.of("custom_content.item", "custom_content.block", "custom_content.armor", "custom_content.projectile");
        CatalogContribution content = new CatalogSourceIngestor().ingest(new CatalogSourceIngestor.CatalogSource(OWNER,
            CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/custom_content.json", "1.0.0", "content-test", bytes), context,
            identity -> starts.contains(identity.id().value()));
        assertEquals(4, content.definitions().size());
        content.definitions().forEach(definition -> {
            assertFalse(definition.metadata().containsKey("sourceNodeId"));
            assertEquals(Map.of("operation", "content_start"), ((Map<?, ?>) definition.metadata().get("authoredSource")).get("handlerConfig"));
        });
        return content;
    }

    private static CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(id, direction, type, id, "Exercises the typed " + id + " pin.",
            CatalogNodeDescriptor.Requirement.REQUIRED, CAPABILITY);
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.MAIN, CAPABILITY,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
    }
}
