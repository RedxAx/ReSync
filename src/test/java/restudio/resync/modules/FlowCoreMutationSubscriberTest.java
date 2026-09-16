package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
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
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.modules.flow.CoreResourceMutationTransition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCoreMutationSubscriberTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContentHash PAYLOAD_HASH = new ContentHash("a".repeat(64));
    private static final CatalogBinding BINDING = new CatalogBinding(1L, new ContentHash("b".repeat(64)),
        new ContentHash("c".repeat(64)));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        SERVER, ContractRef.of(OWNER, ResourceTypeId.of("flow")), "subscriber-lifecycle");

    @Test
    void successfulProjectionIsIdempotentAcrossRetriesAndStaleDelivery() {
        List<CoreResourceMutationTransition> applied = new ArrayList<>();
        FlowModule.IdempotentCoreMutationSubscriber subscriber =
            new FlowModule.IdempotentCoreMutationSubscriber(applied::add);
        CoreResourceMutationTransition first = tombstone(1L,
            UUID.fromString("22222222-2222-4222-8222-222222222222"));
        CoreResourceMutationTransition second = tombstone(2L,
            UUID.fromString("33333333-3333-4333-8333-333333333333"));

        subscriber.accept(first);
        subscriber.accept(first);
        subscriber.accept(second);
        subscriber.accept(first);

        assertEquals(List.of(first, second), applied);
        CoreResourceMutationTransition collision = tombstone(2L,
            UUID.fromString("44444444-4444-4444-8444-444444444444"));
        assertThrows(IllegalStateException.class, () -> subscriber.accept(collision));
        assertEquals(List.of(first, second), applied);
    }

    @Test
    void failedProjectionIsRetriedBeforeItsCheckpointAdvances() {
        AtomicBoolean reject = new AtomicBoolean(true);
        List<CoreResourceMutationTransition> applied = new ArrayList<>();
        FlowModule.IdempotentCoreMutationSubscriber subscriber = new FlowModule.IdempotentCoreMutationSubscriber(transition -> {
            if (reject.getAndSet(false)) {
                throw new IllegalStateException("Projection unavailable");
            }
            applied.add(transition);
        });
        CoreResourceMutationTransition transition = tombstone(1L,
            UUID.fromString("55555555-5555-4555-8555-555555555555"));

        assertThrows(IllegalStateException.class, () -> subscriber.accept(transition));
        subscriber.accept(transition);

        assertEquals(List.of(transition), applied);
    }

    @Test
    void commandRuntimeRefreshDeduplicatesOnlyProvenFreshSaveAndDelete() {
        CoreResourceMutationTransition commandCreate = saved("command", 1L,
            UUID.fromString("66666666-6666-4666-8666-666666666666"));
        CoreResourceMutationTransition commandSave = saved("command", 2L,
            UUID.fromString("77777777-7777-4777-8777-777777777777"));
        CoreResourceMutationTransition commandDelete = deleted("command", 3L,
            UUID.fromString("88888888-8888-4888-8888-888888888888"));
        CoreResourceMutationTransition replay = saved("command", 4L,
            UUID.fromString("99999999-9999-4999-8999-999999999999"));
        CoreResourceMutationTransition recovery = deleted("command", 5L,
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        CoreResourceMutationTransition failedCallback = saved("command", 6L,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        Set<CoreResourceMutationTransition> proven = new HashSet<>();
        List<CoreResourceMutationTransition> activations = new ArrayList<>();
        FlowModule.CoreMutationRuntimeSubscriber subscriber = new FlowModule.CoreMutationRuntimeSubscriber(
            proven::remove, activations::add);

        subscriber.accept(commandCreate);
        proven.add(commandSave);
        activations.add(commandSave);
        subscriber.accept(commandSave);
        proven.add(commandDelete);
        activations.add(commandDelete);
        subscriber.accept(commandDelete);
        subscriber.accept(replay);
        subscriber.accept(recovery);
        subscriber.accept(failedCallback);

        assertEquals(List.of(commandCreate, commandSave, commandDelete, replay, recovery, failedCallback), activations);
        assertTrue(proven.isEmpty());
    }

    @Test
    void flowAndFunctionRuntimeRefreshNeverConsultCommandProofs() {
        CoreResourceMutationTransition flowSave = saved("flow", 1L,
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        CoreResourceMutationTransition functionDelete = deleted("function", 1L,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        AtomicInteger proofChecks = new AtomicInteger();
        List<CoreResourceMutationTransition> refreshed = new ArrayList<>();
        FlowModule.CoreMutationRuntimeSubscriber subscriber = new FlowModule.CoreMutationRuntimeSubscriber(transition -> {
            proofChecks.incrementAndGet();
            return true;
        }, refreshed::add);

        subscriber.accept(flowSave);
        subscriber.accept(functionDelete);

        assertEquals(0, proofChecks.get());
        assertEquals(List.of(flowSave, functionDelete), refreshed);
    }

    @Test
    void provenFunctionDeletionRetainsDefinitionRefreshAndFlowRefresh() throws Exception {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        int start = source.indexOf("private void refreshCoreMutationRuntime(");
        int end = source.indexOf("private boolean consumeTypedCommandRefreshProof(", start);
        String refresh = source.substring(start, end);
        assertTrue(refresh.contains("ReSyncResourceCatalog.FUNCTION.equals(transition.locator().resourceType().value())"));
        assertTrue(refresh.contains("transition.deleted() && consumeTypedCommandRefreshProof(transition)"));
        assertTrue(refresh.contains("customFunctionDefinitionChanged(transition.locator().id(), true)"));
        assertTrue(refresh.contains("refreshCustomFunctionDefinitions();"));
        assertTrue(refresh.contains("refreshSharedResource(transition.locator().resourceType().value(), transition.locator().id(), transition.deleted());"));
    }

    @Test
    void productionLifecycleRegistersAfterAllConsumersExistAndClosesBeforeTeardown() throws Exception {
        String flow = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtime = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int workspace = flow.indexOf("this.workspaces = new FlowWorkspaceService(");
        int collaboration = flow.indexOf("this.collaboration = new FlowCollaborationService(");
        int runtimeRefresh = flow.indexOf("this.blueprintHandler = new FlowBlueprintPacketHandler(");
        int subscriberActivation = flow.indexOf("activateCoreMutationSubscribers();", runtimeRefresh);
        int stop = runtime.indexOf("private void stopFenced(");
        int subscriberDeactivation = runtime.indexOf("delegate.deactivateCoreMutationSubscribers();", stop);
        int refreshCancellation = runtime.indexOf("resourceRegistry.cancelPendingLiveRefreshes();", stop);

        assertEquals(3, occurrences(flow, "resources.addCoreMutationListener("));
        assertTrue(subscriberActivation > workspace);
        assertTrue(subscriberActivation > collaboration);
        assertTrue(subscriberActivation > runtimeRefresh);
        assertTrue(subscriberDeactivation > stop);
        assertTrue(subscriberDeactivation < refreshCancellation);
    }

    @Test
    void functionCatalogRefreshIsDeferredBeyondDurableTransitionPublication() throws Exception {
        String flow = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtime = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int requestStart = runtime.indexOf("private boolean requestCustomFunctionDefinitionRefresh()");
        int requestEnd = runtime.indexOf("public boolean retryPendingCatalogRuntimeCleanup()", requestStart);
        String request = requestStart >= 0 && requestEnd > requestStart
            ? runtime.substring(requestStart, requestEnd) : "";
        int tickStart = runtime.indexOf("public void onTick()");
        int tickEnd = runtime.indexOf("public void cleanup(Session session)", tickStart);
        String tick = tickStart >= 0 && tickEnd > tickStart ? runtime.substring(tickStart, tickEnd) : "";

        assertTrue(flow.contains("new IdempotentCoreMutationSubscriber(new CoreMutationRuntimeSubscriber("));
        assertTrue(flow.contains("this::consumeTypedCommandRefreshProof, this::refreshCoreMutationRuntime"));
        assertTrue(runtime.contains("delegate.setCustomFunctionDefinitionRefresh(this::requestCustomFunctionDefinitionRefresh);"));
        assertTrue(request.contains("customFunctionDefinitionRefreshPending.set(true);"));
        assertTrue(request.contains("fatalActivationFault != null"));
        assertFalse(request.contains("reloadNodeDefinitions();"));
        assertTrue(tick.contains("customFunctionDefinitionRefreshPending.getAndSet(false)"));
        assertTrue(tick.indexOf("customFunctionDefinitionRefreshPending.getAndSet(false)")
            < tick.indexOf("reloadNodeDefinitions();"));
    }

    private static CoreResourceMutationTransition tombstone(long revision, UUID mutationId) {
        String envelope = new String(new CoreGraphStorageBoundary().encodeTombstone(
            RESOURCE, revision, mutationId, PAYLOAD_HASH), StandardCharsets.UTF_8);
        return new CoreResourceMutationTransition(RESOURCE, revision, mutationId, true, null, "protocol:test", envelope);
    }

    private static CoreResourceMutationTransition saved(String type, long revision, UUID mutationId) {
        ServerResourceLocator resource = resource(type);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        String envelope = new String(new CoreGraphStorageBoundary().encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata(type, revision, mutationId, ResourceActivationState.ACTIVE), resource),
            StandardCharsets.UTF_8);
        return new CoreResourceMutationTransition(resource, revision, mutationId, false,
            ResourceActivationState.ACTIVE, "protocol:test", envelope);
    }

    private static CoreResourceMutationTransition deleted(String type, long revision, UUID mutationId) {
        ServerResourceLocator resource = resource(type);
        String envelope = new String(new CoreGraphStorageBoundary().encodeTombstone(
            resource, revision, mutationId, PAYLOAD_HASH), StandardCharsets.UTF_8);
        return new CoreResourceMutationTransition(resource, revision, mutationId, true, null, "protocol:test", envelope);
    }

    private static ServerResourceLocator resource(String type) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), "subscriber-lifecycle");
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
