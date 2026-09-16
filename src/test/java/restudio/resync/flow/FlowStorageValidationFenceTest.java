package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowStorageValidationFenceTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path root;

    @Test
    void legacyGraphCannotCommitAValidationResultFromBeforeAResourceChange() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            storage.setGraphValidator(graph -> {
                changeReference(coordinator);
                return new FlowGraphValidationResult(List.of());
            });
            FlowGraph graph = new FlowGraph("consumer", Map.of(), List.of(), List.of());
            graph.setResourceType("flow");

            assertThrows(IllegalStateException.class, () -> storage.saveGraph(graph));

            assertEquals(1L, coordinator.committedSequence());
            assertFalse(Files.exists(root.resolve("assets/Blueprints/Flows/consumer.json")));
        }
    }

    @Test
    void coreSaveAndAggregateCreateRejectAnExpiredObservationBeforeWriting() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            FlowStorage.RuntimeObservation observation = storage.observeRuntime().orElseThrow();
            changeReference(coordinator);

            assertThrows(IllegalStateException.class, () -> storage.saveCoreGraph(graph("consumer"),
                ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L, observation));
            assertThrows(IllegalStateException.class, () -> storage.createCoreGraph(graph("aggregate"),
                ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L,
                new ResourcePresentationIntent("Aggregate", "Blueprints/Flows", 0), observation));

            assertEquals(1L, coordinator.committedSequence());
            assertFalse(Files.exists(root.resolve("assets/Blueprints/Flows/consumer.json")));
            assertFalse(Files.exists(root.resolve("assets/Blueprints/Flows/aggregate.json")));
        }
    }

    @Test
    void legacyExactReplaySurvivesAResourceChangeDuringValidation() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            FlowGraph graph = new FlowGraph("consumer", Map.of(), List.of(), List.of());
            graph.setResourceType("flow");
            UUID mutationId = UUID.randomUUID();
            storage.saveGraph(graph, mutationId, 0L);
            FlowStorage.GraphIdentity saved = storage.readGraphIdentity("flow", "consumer");
            storage.setGraphValidator(candidate -> {
                changeReference(coordinator);
                return new FlowGraphValidationResult(List.of());
            });

            assertDoesNotThrow(() -> storage.saveGraph(graph, mutationId, 0L));

            assertEquals(saved, storage.readGraphIdentity("flow", "consumer"));
            assertEquals(2L, coordinator.committedSequence());
        }
    }

    @Test
    void legacyNoOpSurvivesAResourceChangeDuringValidation() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            FlowGraph graph = new FlowGraph("consumer", Map.of(), List.of(), List.of());
            graph.setResourceType("flow");
            storage.saveGraph(graph);
            FlowStorage.GraphIdentity saved = storage.readGraphIdentity("flow", "consumer");
            storage.setGraphValidator(candidate -> {
                changeReference(coordinator);
                return new FlowGraphValidationResult(List.of());
            });

            assertDoesNotThrow(() -> storage.saveGraph(graph));

            assertEquals(saved, storage.readGraphIdentity("flow", "consumer"));
            assertEquals(2L, coordinator.committedSequence());
        }
    }

    @Test
    void catalogRebindRejectsAnExpiredObservationBeforeWriting() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            CoreGraphStorageBoundary.Decoded source = storage.saveCoreGraph(graph("consumer"),
                ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L);
            GraphDocument graph = source.graphDocument();
            CatalogBinding target = new CatalogBinding(2, new ContentHash("c".repeat(64)),
                new ContentHash("d".repeat(64)));
            GraphDocument projected = new GraphDocument(graph.schemaVersion(), graph.resource(), 2L, target,
                graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
            UUID mutationId = UUID.randomUUID();
            CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
            CoreGraphStorageBoundary.Decoded candidate = boundary.decode(boundary.encode(projected,
                new CoreGraphStorageBoundary.AssetMetadata("flow", 2L, mutationId, ResourceActivationState.INACTIVE)), graph.resource());
            FlowStorage.RuntimeObservation observation = storage.observeRuntime().orElseThrow();
            changeReference(coordinator);

            assertThrows(IllegalStateException.class, () -> storage.rebindCoreCatalog(graph.resource(),
                new FlowStorage.CoreCatalogRebindSource(source), candidate, mutationId, observation));

            assertEquals(source.envelope(), storage.getCoreGraph("flow", "consumer").orElseThrow().envelope());
            assertEquals(2L, coordinator.committedSequence());
        }
    }

    @Test
    void exactCoreReplaySurvivesAnExpiredObservation() throws Exception {
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            FlowStorage.RuntimeObservation observation = storage.observeRuntime().orElseThrow();
            UUID mutationId = UUID.randomUUID();
            CoreGraphStorageBoundary.Decoded saved = storage.saveCoreGraph(graph("consumer"),
                ResourceActivationState.INACTIVE, mutationId, 0L, observation);
            changeReference(coordinator);
            long sequence = coordinator.committedSequence();

            CoreGraphStorageBoundary.Decoded replayed = storage.saveCoreGraph(graph("consumer"),
                ResourceActivationState.INACTIVE, mutationId, 0L, observation);

            assertEquals(saved.envelope(), replayed.envelope());
            assertEquals(sequence, coordinator.committedSequence());
        }
    }

    private AssetTransactionCoordinator transactions() throws IOException {
        return new AssetTransactionCoordinator(root.resolve("assets"), new Gson());
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root),
            new AssetPersistenceGate(root), SERVER, coordinator);
    }

    private GraphDocument graph(String id) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), id);
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1L, BINDING,
            Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private void changeReference(AssetTransactionCoordinator coordinator) {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(value -> value);
        try {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
                List.of(AssetTransactionCoordinator.AssetDelta.write(
                    new AssetTransactionCoordinator.AssetKey("variable_definition", "reference"),
                    Path.of("Automation/Variables/reference.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "{}".getBytes(StandardCharsets.UTF_8))), List.of()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
