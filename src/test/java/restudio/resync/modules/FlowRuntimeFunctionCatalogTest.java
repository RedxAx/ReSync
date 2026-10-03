package restudio.resync.modules;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TypedCommandGraphAdapter;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.server.OptionCatalogCaptureExecutor;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeFunctionCatalogTest {
    private static final String SOURCE = "server:resync:function";
    private static final CatalogBinding BINDING = new CatalogBinding(1, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));

    @TempDir
    Path root;

    @Test
    void primaryCaptureListsCommittedNestedFunctionsWithoutProjectingTheirSources() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(root, coordinator);
            OptionCatalogRegistry catalogs = catalogs(storage, executor);
            OptionCatalogCapture empty = capture(catalogs);
            assertEquals(List.of(), empty.values());
            FunctionBinding dependency = new FunctionBinding(resource("function", "child"), 1, List.of(), List.of());
            FunctionSourceDocument parent = function("parent", List.of(dependency));
            storage.saveCoreGraph(function("child", List.of()), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            storage.saveCoreGraph(parent, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            storage.saveCoreGraph(graph("flow", "unrelated"), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);

            OptionCatalogCapture admitted = capture(catalogs);

            assertEquals(List.of("child", "parent"), admitted.values());
            assertEquals("available", admitted.status());
            assertEquals("function", admitted.items().getFirst().metadata().get("resourceType"));
            assertThrows(UnsupportedOperationException.class, () -> admitted.items().getFirst().metadata().put("resourceType", "flow"));
            assertThrows(UnsupportedOperationException.class, () -> admitted.items().clear());
            assertThrows(UnsupportedOperationException.class, () -> storage.readCommittedGraphIds("function").ids().clear());
            FunctionSourceDocument restored = storage.getCoreGraph("function", "parent").orElseThrow().functionSourceDocument();
            assertEquals(parent.checksum(), restored.checksum());
            assertEquals(parent.signature(), restored.signature());
            assertEquals(GraphDocumentCodec.INSTANCE.encode(parent.graph()).values().get("functions").canonicalText(),
                GraphDocumentCodec.INSTANCE.encode(restored.graph()).values().get("functions").canonicalText());
            assertEquals("Core graph static function bindings require compiled execution", assertThrows(IllegalStateException.class,
                () -> TypedCommandGraphAdapter.materialize(restored.graph(), restored, true, "fixture")).getMessage());
            storage.deleteCoreGraph("function", "parent", UUID.randomUUID(), 1L);
            assertEquals(List.of("child"), capture(catalogs).values());
            assertEquals(List.of("child", "parent"), admitted.values());
            assertEquals(List.of(), empty.values());
        }
    }

    @Test
    void classificationChangesMoveTheExactCommittedIdBetweenTypedInventories() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(root, coordinator);
            OptionCatalogRegistry catalogs = catalogs(storage, executor);
            FlowGraph graph = new FlowGraph("classified", Map.of(), List.of(), List.of());
            graph.setResourceType("flow");
            storage.saveGraph(graph);
            OptionCatalogCapture before = capture(catalogs);
            assertEquals(List.of(), before.values());
            assertEquals(List.of("classified"), storage.readCommittedGraphIds("flow").ids());

            storage.reclassifyGraph(graph, "function");

            assertEquals(List.of("classified"), capture(catalogs).values());
            assertEquals(List.of(), storage.readCommittedGraphIds("flow").ids());
            assertEquals(List.of(), before.values());
            assertTrue(coordinator.committedSnapshot().state(new AssetTransactionCoordinator.AssetKey("flow", "classified"))
                .orElseThrow() instanceof AssetTransactionCoordinator.Deleted);
        }
    }

    @Test
    void persistenceEpochAndRootReplacementFencePreviouslyPreparedCaptures() throws Exception {
        Path replacement = root.resolve("replacement");
        try (AssetTransactionCoordinator initial = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             AssetTransactionCoordinator next = AssetTransactionCoordinator.open(replacement.resolve("assets"), new Gson());
             OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            CanonicalProjectMetadataFixture.seed(initial);
            CanonicalProjectMetadataFixture.seed(next);
            FlowStorage storage = storage(root, initial);
            storage.saveCoreGraph(function("initial", List.of()), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            storage(replacement, next).saveCoreGraph(function("replacement", List.of()), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            OptionCatalogRegistry catalogs = catalogs(storage, executor);
            OptionCatalogCapture old = capture(catalogs);
            FlowStorage.CommittedGraphIds oldIds = storage.readCommittedGraphIds("function");

            storage.quiescePersistence();
            assertThrows(IllegalStateException.class, () -> capture(catalogs));
            assertFalse(storage.isRuntimeObservationCurrent(oldIds.observation()));
            storage.resumePersistence();
            assertEquals(List.of("initial"), capture(catalogs).values());
            assertFalse(storage.isRuntimeObservationCurrent(oldIds.observation()));
            storage.quiescePersistence();
            storage.rebindPersistence(replacement.resolve("assets"), next);
            storage.resumePersistence();

            assertEquals(List.of("replacement"), capture(catalogs).values());
            assertEquals(List.of("initial"), old.values());
            assertFalse(storage.isRuntimeObservationCurrent(oldIds.observation()));
        }
    }

    @Test
    void closingAndReopeningTheCoordinatorCannotReuseThePreviousAuthority() throws Exception {
        OptionCatalogCapture old;
        OptionCatalogRegistry closed;
        try (OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson())) {
                CanonicalProjectMetadataFixture.seed(coordinator);
                FlowStorage storage = storage(root, coordinator);
                storage.saveCoreGraph(function("retained", List.of()), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
                closed = catalogs(storage, executor);
                old = capture(closed);
            }
            assertThrows(IllegalStateException.class, () -> capture(closed));
            try (AssetTransactionCoordinator reopened = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson())) {
                OptionCatalogRegistry current = catalogs(storage(root, reopened), executor);
                assertEquals(List.of("retained"), capture(current).values());
                assertEquals(List.of("retained"), old.values());
            }
        }
    }

    @Test
    void explicitLegacyFixturesRetainTheirIoAdmissionInsteadOfClaimingCommittedNativeAuthority() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), new AssetPersistenceGate(root), coordinator);
            OptionCatalogRegistry catalogs = catalogs(storage, executor);
            assertFalse(catalogs.provider(SOURCE) instanceof OptionCatalogRegistry.PreparedCaptureProvider);
            assertThrows(IllegalArgumentException.class, () -> storage.readCommittedGraphIds("function"));
            assertThrows(OptionCatalogCaptureExecutor.CaptureUnavailable.class, () -> capture(catalogs));
        }
    }

    private static OptionCatalogCaptureExecutor.Bounded primaryExecutor() {
        return OptionCatalogCaptureExecutor.bounded(1, Duration.ofSeconds(2), () -> true,
            task -> {
                throw new AssertionError("Function inventory must not schedule a Bukkit callback");
            }, Thread.ofVirtual().factory());
    }

    private static OptionCatalogRegistry catalogs(FlowStorage storage, OptionCatalogCaptureExecutor executor) throws Exception {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        registry.bindCapture(executor::capture);
        Method registration = FlowRuntimeModule.class.getDeclaredMethod("registerFunctionCatalog", OptionCatalogRegistry.class, FlowStorage.class);
        registration.setAccessible(true);
        registration.invoke(new FlowRuntimeModule(), registry, storage);
        assertEquals(OptionCatalogProvider.CaptureAffinity.IO, registry.provider(SOURCE).captureAffinity());
        return registry;
    }

    private static OptionCatalogCapture capture(OptionCatalogRegistry catalogs) {
        return catalogs.capture(SOURCE, new OptionCatalogQuery(SOURCE, Map.of()));
    }

    private static FlowStorage storage(Path scope, AssetTransactionCoordinator coordinator) {
        return new FlowStorage(scope.toFile(), LegacyRuntimeActivationGate.runtime(scope), new AssetPersistenceGate(scope),
            CanonicalProjectMetadataFixture.serverId(), coordinator);
    }

    private static FunctionSourceDocument function(String id, List<FunctionBinding> dependencies) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource("function", id), 1, BINDING, Set.of(),
            List.of(), List.of(), List.of(), dependencies, OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(graph.resource()), FunctionRevision.of(1),
            List.of(), List.of(), Map.of("authored", Map.of("fields", List.of("first", "second"))));
        return new FunctionSourceDocument(signature, graph);
    }

    private static GraphDocument graph(String type, String id) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), 1, BINDING, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(CanonicalProjectMetadataFixture.serverId(), ContractRef.of(OwnerId.of("restudio.resync"),
            ResourceTypeId.of(type)), id);
    }
}
