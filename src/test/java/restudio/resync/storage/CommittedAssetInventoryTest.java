package restudio.resync.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.server.OptionCatalogCaptureExecutor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommittedAssetInventoryTest {
    private static final String SOURCE = "server:resync:function";
    private static final CatalogBinding BINDING = new CatalogBinding(1, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));

    @TempDir
    Path root;

    @Test
    void primaryFunctionCaptureRemainsAvailableWhileAnIoWriterHoldsBothPersistenceLocks() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             OptionCatalogCaptureExecutor.Bounded executor = primaryExecutor()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(function("first"), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            OptionCatalogRegistry catalogs = catalogs(storage, executor);
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var writer = workers.submit(() -> {
                synchronized (storage) {
                    coordinator.withHealthCheckScope(() -> {
                        Files.readString(root.resolve("assets/project.json"));
                        writing.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("Persistence writer was not released");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Persistence writer was interrupted", failure);
                        }
                    });
                }
                return null;
            });
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                OptionCatalogCapture capture = workers.submit(() -> catalogs.capture(SOURCE,
                    new OptionCatalogQuery(SOURCE, Map.of()))).get(1, TimeUnit.SECONDS);
                assertEquals(List.of("first"), capture.values());
                assertEquals("available", capture.status());
                FlowStorage.CommittedGraphIds ids = workers.submit(() -> storage.readCommittedGraphIds("function"))
                    .get(1, TimeUnit.SECONDS);
                assertEquals(List.of("first"), ids.ids());
                assertTrue(storage.isRuntimeObservationCurrent(ids.observation()));
            } finally {
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void retainedInventoryAndMetadataCannotBeMutatedOrReplacedByLaterCommits() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(function("first"), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            AssetTransactionCoordinator.Snapshot retained = coordinator.committedSnapshot();
            FlowStorage.CommittedGraphIds first = storage.readCommittedGraphIds("function");
            assertThrows(UnsupportedOperationException.class, () -> retained.states().clear());
            assertThrows(UnsupportedOperationException.class, () -> retained.paths().clear());
            assertThrows(UnsupportedOperationException.class, () -> retained.lineages().clear());
            retained.metadata().document().addProperty("serverId", "changed");
            assertEquals(CanonicalProjectMetadataFixture.serverId().canonicalText(),
                coordinator.committedSnapshot().metadata().document().get("serverId").getAsString());

            storage.saveCoreGraph(function("second"), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);

            assertEquals(List.of("first", "second"), storage.readCommittedGraphIds("function").ids());
            assertEquals(List.of("first"), first.ids());
            assertFalse(storage.isRuntimeObservationCurrent(first.observation()));
            assertFalse(retained.states().containsKey(new AssetTransactionCoordinator.AssetKey("function", "second")));
            assertTrue(coordinator.committedSnapshot().rootSequence() > retained.rootSequence());
        }
    }

    @Test
    void coldAdmissionRejectsAssetBytesChangedOutsideTheCommittedInventoryOwner() throws Exception {
        Path file;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(function("guarded"), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            file = coordinator.committedSnapshot().path(new AssetTransactionCoordinator.AssetKey("function", "guarded"))
                .orElseThrow();
        }
        Files.writeString(file, "{\"changed\":true}");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root.resolve("assets"), new Gson()));
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), new AssetPersistenceGate(root),
            CanonicalProjectMetadataFixture.serverId(), coordinator);
    }

    private static OptionCatalogCaptureExecutor.Bounded primaryExecutor() {
        return OptionCatalogCaptureExecutor.bounded(1, Duration.ofSeconds(2), () -> true,
            task -> {
                throw new AssertionError("Committed inventory capture must not schedule a callback");
            }, Thread.ofVirtual().factory());
    }

    private static OptionCatalogRegistry catalogs(FlowStorage storage, OptionCatalogCaptureExecutor executor) throws Exception {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        registry.bindCapture(executor::capture);
        Method method = FlowRuntimeModule.class.getDeclaredMethod("registerFunctionCatalog", OptionCatalogRegistry.class, FlowStorage.class);
        method.setAccessible(true);
        method.invoke(new FlowRuntimeModule(), registry, storage);
        return registry;
    }

    private static FunctionSourceDocument function(String id) {
        ServerResourceLocator resource = new ServerResourceLocator(CanonicalProjectMetadataFixture.serverId(),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), id);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.empty());
        return new FunctionSourceDocument(new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1), List.of(), List.of()), graph);
    }
}
