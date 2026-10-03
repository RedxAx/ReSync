package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageNotificationLockTest {
    private static final String ID = "notification";
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void refreshCanReadCommittedStateBeforeTheMutatingCallerReturns(Mutation mutation) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root),
                new AssetPersistenceGate(root), SERVER, coordinator);
            FlowGraph graph = new FlowGraph(ID, Map.of(), List.of(), List.of());
            graph.setResourceType("flow");
            UUID firstMutation = UUID.randomUUID();
            if (mutation == Mutation.CORE_DELETE) {
                storage.saveCoreGraph(coreGraph(), ResourceActivationState.INACTIVE, firstMutation, 0L);
            } else if (mutation != Mutation.SAVE && mutation != Mutation.SAVE_EXPLICIT && mutation != Mutation.CORE_SAVE) {
                storage.saveGraph(graph, firstMutation, 0L);
            }
            if (mutation == Mutation.RESTORE) {
                graph.setEnabled(false);
            }
            List<FlowStorage.GraphChange> notifications = new ArrayList<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            storage.setGraphChangeListener(change -> {
                try {
                    notifications.add(change);
                    CompletableFuture.runAsync(() -> assertCommittedState(storage, coordinator, mutation, change), readers)
                        .get(2, TimeUnit.SECONDS);
                    assertFalse(Thread.holdsLock(storage), "Refresh must run after the storage monitor is released");
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                }
            });

            switch (mutation) {
                case SAVE, SAVE_NO_OP -> storage.saveGraph(graph);
                case SAVE_EXPLICIT -> storage.saveGraph(graph, UUID.randomUUID(), 0L);
                case SAVE_REPLAY -> storage.saveGraph(graph, firstMutation, 0L);
                case RESTORE -> storage.restoreGraph(graph);
                case RELOAD -> storage.reloadGraph(ID);
                case RELOAD_TYPED -> storage.reloadGraph("flow", ID);
                case RECLASSIFY -> storage.reclassifyGraph(graph, "function");
                case DELETE -> storage.deleteGraph(ID);
                case DELETE_TYPED -> storage.deleteGraph("flow", ID);
                case DELETE_EXPLICIT -> storage.deleteGraph("flow", ID, UUID.randomUUID(), 1L);
                case FORCE_DELETE -> storage.forceDeleteGraph(ID);
                case CORE_SAVE -> storage.saveCoreGraph(coreGraph(), ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L);
                case CORE_DELETE -> storage.deleteCoreGraph("flow", ID, UUID.randomUUID(), 1L);
            }

            assertNull(callbackFailure.get(), () -> "Committed refresh could not read storage: " + callbackFailure.get());
            List<FlowStorage.GraphChange> expected = switch (mutation) {
                case SAVE_REPLAY, SAVE_NO_OP -> List.of();
                case RECLASSIFY -> List.of(new FlowStorage.GraphChange("flow", ID), new FlowStorage.GraphChange("function", ID));
                default -> List.of(new FlowStorage.GraphChange("flow", ID));
            };
            assertEquals(expected, notifications);
            assertCommittedState(storage, coordinator, mutation,
                new FlowStorage.GraphChange(mutation == Mutation.RECLASSIFY ? "function" : "flow", ID));
            if (mutation == Mutation.RESTORE) {
                assertFalse(storage.getGraph("flow", ID).isEnabled());
            }
            if (mutation == Mutation.SAVE_REPLAY || mutation == Mutation.SAVE_NO_OP) {
                assertEquals(firstMutation.toString(), storage.readGraphIdentity("flow", ID).mutationId());
            }
        }
    }

    private void assertCommittedState(FlowStorage storage, AssetTransactionCoordinator coordinator,
                                      Mutation mutation, FlowStorage.GraphChange change) {
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey(change.type(), change.id());
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(value -> value);
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key).orElseThrow();
        long expectedRevision = switch (mutation) {
            case SAVE, SAVE_EXPLICIT, SAVE_REPLAY, SAVE_NO_OP, RELOAD, RELOAD_TYPED, CORE_SAVE -> 1L;
            default -> 2L;
        };
        assertEquals(expectedRevision, state.revision());
        boolean deleted = switch (mutation) {
            case DELETE, DELETE_TYPED, DELETE_EXPLICIT, FORCE_DELETE, CORE_DELETE -> true;
            case RECLASSIFY -> change.type().equals("flow");
            default -> false;
        };
        assertEquals(deleted, state instanceof AssetTransactionCoordinator.Deleted);
        if (mutation == Mutation.CORE_SAVE || mutation == Mutation.CORE_DELETE) {
            var current = storage.getCoreGraph(change.type(), change.id());
            assertEquals(deleted, current.isEmpty());
            if (!deleted) {
                assertEquals(expectedRevision, current.orElseThrow().envelope().assetRevision());
                assertEquals(snapshot.mutationValue(key).orElseThrow(), current.orElseThrow().envelope().assetMutationId());
                assertEquals(coreGraph().resource(), current.orElseThrow().graphDocument().resource());
                assertTrue(current.orElseThrow().graphDocument().nodes().isEmpty());
            }
        } else {
            if (mutation == Mutation.RECLASSIFY && deleted) {
                assertNull(storage.getGraph(change.type(), change.id()));
                return;
            }
            FlowStorage.GraphIdentity identity = storage.readGraphIdentity(change.type(), change.id());
            assertEquals(expectedRevision, identity.revision());
            assertEquals(snapshot.mutationValue(key).orElseThrow(), identity.mutationId());
            assertEquals(deleted, identity.deleted());
            FlowGraph current = storage.getGraph(change.type(), change.id());
            if (deleted) {
                assertNull(current);
            } else {
                assertEquals(expectedRevision, current.getResourceRevision());
                assertEquals(identity.mutationId(), current.getResourceMutationId());
                assertEquals(mutation != Mutation.RESTORE, current.isEnabled());
                assertEquals(change.type().equals("function"), current.isFunction());
                assertTrue(current.getNodes().isEmpty());
                assertTrue(state instanceof AssetTransactionCoordinator.Live);
            }
        }
    }

    private GraphDocument coreGraph() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), ID);
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1L, BINDING,
            Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private enum Mutation {
        SAVE, SAVE_EXPLICIT, SAVE_REPLAY, SAVE_NO_OP, RESTORE, RELOAD, RELOAD_TYPED,
        RECLASSIFY, DELETE, DELETE_TYPED, DELETE_EXPLICIT, FORCE_DELETE, CORE_SAVE, CORE_DELETE
    }
}
