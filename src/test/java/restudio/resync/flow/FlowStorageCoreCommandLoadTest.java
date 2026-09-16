package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageCoreCommandLoadTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void loadsFormatFourCommandThroughCoreMaterialization() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(command("aad"), ResourceActivationState.ACTIVE,
                UUID.fromString("33333333-3333-4333-8333-333333333333"), 0L);

            FlowGraph loaded = storage.getCommandGraph("aad");
            assertNotNull(loaded);
            assertEquals("aad", loaded.getId());
            assertEquals("command", loaded.getResourceType());
            assertEquals(4, storage.getCoreGraph("command", "aad").orElseThrow().envelope().assetFormatVersion());
        }
    }

    @Test
    void doesNotFallbackToLegacyCommandLoadingWhenCoreStateIsAbsent() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator) {
                @Override
                public synchronized Optional<CoreGraphStorageBoundary.Decoded> getCoreGraph(String type, String id) {
                    return Optional.empty();
                }

                @Override
                public synchronized FlowGraph getGraph(String type, String id) {
                    throw new AssertionError("Legacy command loading must not be used for authoritative storage");
                }
            };

            assertNull(storage.getCommandGraph("aad"));
        }
    }

    @Test
    void freshSaveAndDeleteCreateExactOneShotRefreshProofsButReplayDoesNot() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            UUID seedMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
            storage.saveCoreGraph(command("proof"), ResourceActivationState.ACTIVE, seedMutation, 0L);
            AtomicInteger notifications = new AtomicInteger();
            storage.setTypedCommandGraphChangeListener(ignored -> notifications.incrementAndGet());
            ServerResourceLocator resource = commandResource("proof");
            UUID saveMutation = UUID.fromString("55555555-5555-4555-8555-555555555555");
            UUID deleteMutation = UUID.fromString("66666666-6666-4666-8666-666666666666");

            storage.saveCoreGraph(command("proof", 2L), ResourceActivationState.ACTIVE, saveMutation, 1L);

            assertEquals(1, notifications.get());
            assertFalse(storage.consumeTypedCommandRefreshProof(commandResource("other"), 2L, saveMutation, false));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 3L, saveMutation, false));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), false));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L, saveMutation, true));
            assertTrue(storage.consumeTypedCommandRefreshProof(resource, 2L, saveMutation, false));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L, saveMutation, false));

            storage.saveCoreGraph(command("proof", 2L), ResourceActivationState.ACTIVE, saveMutation, 1L);

            assertEquals(1, notifications.get());
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L, saveMutation, false));

            storage.deleteCoreGraph("command", "proof", deleteMutation, 2L);

            assertEquals(2, notifications.get());
            assertTrue(storage.consumeTypedCommandRefreshProof(resource, 3L, deleteMutation, true));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 3L, deleteMutation, true));

            storage.deleteCoreGraph("command", "proof", deleteMutation, 2L);

            assertEquals(2, notifications.get());
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 3L, deleteMutation, true));
        }
    }

    @Test
    void functionDeletionRetainsOnlyTheExactCompletedCommandRefreshProof() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), "function-proof");
            GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1L, BINDING, Set.of(),
                List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
            FunctionSourceDocument source = new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(resource),
                new FunctionRevision(1L), List.of(), List.of(), Map.of()), graph);
            storage.saveCoreGraph(source, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            AtomicInteger refreshes = new AtomicInteger();
            storage.setTypedCommandGraphChangeListener(ignored -> refreshes.incrementAndGet());
            UUID deletion = UUID.randomUUID();

            storage.deleteCoreGraph(resource, deletion, 1L);

            assertEquals(1, refreshes.get());
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 1L, deletion, true));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L, deletion, false));
            assertTrue(storage.consumeTypedCommandRefreshProof(resource, 2L, deletion, true));
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 2L, deletion, true));
        }
    }

    @Test
    void failedCallbackAndRestartLeaveNoRefreshProof() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            UUID failedMutation = UUID.fromString("77777777-7777-4777-8777-777777777777");
            ServerResourceLocator failedResource = commandResource("failed-proof");
            storage.setTypedCommandGraphChangeListener(ignored -> {
                throw new IllegalStateException("runtime unavailable");
            });

            storage.saveCoreGraph(command("failed-proof"), ResourceActivationState.ACTIVE, failedMutation, 0L);

            assertFalse(storage.consumeTypedCommandRefreshProof(failedResource, 1L, failedMutation, false));

            UUID restartMutation = UUID.fromString("88888888-8888-4888-8888-888888888888");
            ServerResourceLocator restartResource = commandResource("restart-proof");
            storage.setTypedCommandGraphChangeListener(ignored -> {
            });
            storage.saveCoreGraph(command("restart-proof"), ResourceActivationState.ACTIVE, restartMutation, 0L);
            FlowStorage restarted = storage(coordinator);

            assertFalse(restarted.consumeTypedCommandRefreshProof(restartResource, 1L, restartMutation, false));
            assertTrue(storage.consumeTypedCommandRefreshProof(restartResource, 1L, restartMutation, false));
        }
    }

    @Test
    void aggregateCreatePublishesOneRuntimeNotificationWithoutTransitionProof() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            AtomicInteger notifications = new AtomicInteger();
            storage.setTypedCommandGraphChangeListener(ignored -> notifications.incrementAndGet());
            UUID mutationId = UUID.fromString("99999999-9999-4999-8999-999999999999");
            ServerResourceLocator resource = commandResource("create-proof");
            storage.createCoreGraph(command("create-proof"), ResourceActivationState.ACTIVE, mutationId, 0L,
                new ResourcePresentationIntent("Create Proof", "Commands/create-proof.json", 1));

            assertEquals(0, notifications.get());

            storage.publishCoreGraph("create-proof");

            assertEquals(1, notifications.get());
            assertFalse(storage.consumeTypedCommandRefreshProof(resource, 1L, mutationId, false));
        }
    }

    private AssetTransactionCoordinator coordinator() throws Exception {
        return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator);
    }

    private static GraphDocument command(String id) {
        return command(id, 1L);
    }

    private static GraphDocument command(String id, long revision) {
        ServerResourceLocator resource = commandResource(id);
        GraphNode start = new GraphNode(NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444")),
            CommandGraphContract.CANONICAL_START, 1, Map.of());
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(),
            List.of(start), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserved")));
        return new CommandGraphMetadata(id, false, List.of()).apply(document);
    }

    private static ServerResourceLocator commandResource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), id);
    }
}
