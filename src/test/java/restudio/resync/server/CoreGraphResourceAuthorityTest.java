package restudio.resync.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.CoreGraphStorageBoundary;
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
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphResourceAuthorityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final UUID SAVE_MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTIVATE_MUTATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DELETE_MUTATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID LIST_FIRST_MUTATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID LIST_SECOND_MUTATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID LIST_DELETE_MUTATION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SKEW_MUTATION = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID REPAIR_MUTATION = UUID.fromString("99999999-9999-4999-8999-999999999999");

    @TempDir
    Path tempDir;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

    @Test
    void catalogRebindRecordsRejectMissingProofValues() {
        assertThrows(NullPointerException.class,
            () -> new CoreGraphResourceAuthority.CoreCatalogRebindSource(null));
        assertThrows(NullPointerException.class,
            () -> new CoreGraphResourceAuthority.CoreCatalogRebindResult(null, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (assetsGate != null) {
            assetsGate.quiesce();
        }
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void delegatesCanonicalEnvelopeLifecycleToFlowStorage() {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator resource = resource("main");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = graph(resource, 1L);
        byte[] requestedEnvelope = boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, SAVE_MUTATION,
                ResourceActivationState.INACTIVE), resource);
        CoreGraphStorageBoundary.Decoded requested = boundary.decode(requestedEnvelope, resource);

        CoreGraphStorageBoundary.Decoded saved = authority.save(resource, requestedEnvelope, SAVE_MUTATION, 0L,
            requested.envelope().assetHash());
        assertEquals(1L, saved.envelope().assetRevision());
        assertEquals(ResourceActivationState.INACTIVE, saved.envelope().assetActivationState());
        assertEquals(SAVE_MUTATION.toString(), saved.envelope().assetMutationId());
        assertEquals(resource, saved.graphDocument().resource());
        assertArrayEquals(boundary.encode(saved), authority.state(resource).orElseThrow().canonicalEnvelope());
        assertEquals(saved.envelope().assetHash(), authority.state(resource).orElseThrow().payloadChecksum());
        assertEquals(graph.checksum(), authority.state(resource).orElseThrow().corePayloadChecksum());

        CoreGraphStorageBoundary.Decoded activated = authority.activate(resource, ResourceActivationState.ACTIVE,
            ACTIVATE_MUTATION, 1L, saved.envelope().assetHash());
        assertEquals(2L, activated.envelope().assetRevision());
        assertEquals(ACTIVATE_MUTATION.toString(), activated.envelope().assetMutationId());
        assertEquals(ResourceActivationState.ACTIVE, activated.envelope().assetActivationState());
        assertEquals(resource, activated.graphDocument().resource());
        assertEquals(2L, activated.graphDocument().revision());
        assertArrayEquals(boundary.encode(activated), boundary.encode(authority.load(resource).orElseThrow()));

        ContentHash assetHash = activated.envelope().assetHash();
        ContentHash payloadChecksum = activated.graphDocument().checksum();
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = authority.delete(resource, DELETE_MUTATION, 2L,
            assetHash, payloadChecksum);
        assertEquals(3L, tombstone.revision());
        assertEquals(payloadChecksum, tombstone.priorPayloadHash());
        assertNotEquals(assetHash, tombstone.priorPayloadHash());
        CoreGraphResourceAuthority.CoreGraphResourceState deleted = authority.deleteState(resource,
            DELETE_MUTATION, 2L, assetHash);
        assertTrue(deleted.deleted());
        assertArrayEquals(boundary.encodeTombstone(tombstone), deleted.canonicalTombstone());
        assertTrue(authority.state(resource).isEmpty());
    }

    @Test
    void listsDurableLiveCoreStateWithoutLegacyOrTombstonedEntries() throws Exception {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator first = resource("flow", "first");
        ServerResourceLocator second = resource("flow", "second");
        CoreGraphStorageBoundary.Decoded savedFirst = saveFlow(authority, first, LIST_FIRST_MUTATION);
        CoreGraphStorageBoundary.Decoded savedSecond = saveFlow(authority, second, LIST_SECOND_MUTATION);

        assertEquals(List.of("first", "second"), authority.list("flow").stream()
            .map(state -> state.resource().id()).toList());

        authority.delete(second, LIST_DELETE_MUTATION, 1L, savedSecond.envelope().assetHash(),
            savedSecond.graphDocument().checksum());
        Path legacy = tempDir.resolve("flows").resolve("legacy.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"id\":\"legacy\"}");

        assertEquals(List.of("first"), authority.list("flow").stream()
            .map(state -> state.resource().id()).toList());
        assertEquals(first, authority.list("flow").getFirst().resource());
        assertEquals(savedFirst.envelope().assetHash(), authority.list("flow").getFirst().payloadChecksum());
    }

    @Test
    void activatesAndDeletesFunctionUsingTheCorePayloadChecksum() {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator resource = resource("function", "function-delete");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        FunctionSourceDocument source = source(resource, 1L);
        byte[] envelope = boundary.encode(source,
            new CoreGraphStorageBoundary.AssetMetadata("function", 1L, SAVE_MUTATION), resource);

        CoreGraphStorageBoundary.Decoded saved = authority.save(resource, envelope, SAVE_MUTATION, 0L,
            boundary.decode(envelope, resource).envelope().assetHash());
        CoreGraphStorageBoundary.Decoded activated = authority.activate(resource, ResourceActivationState.INACTIVE,
            ACTIVATE_MUTATION, 1L, saved.envelope().assetHash());
        assertEquals(2L, activated.envelope().assetRevision());
        assertEquals(2L, activated.functionSourceDocument().graph().revision());
        assertEquals(2L, activated.functionSourceDocument().signature().revision().value());
        assertArrayEquals(boundary.encode(activated), boundary.encode(authority.load(resource).orElseThrow()));
        ContentHash assetHash = activated.envelope().assetHash();
        ContentHash payloadChecksum = activated.functionSourceDocument().checksum();
        assertNotEquals(assetHash, payloadChecksum);

        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = authority.delete(resource, DELETE_MUTATION, 2L,
            assetHash, payloadChecksum);
        assertEquals(payloadChecksum, tombstone.priorPayloadHash());
        assertNotEquals(assetHash, tombstone.priorPayloadHash());
    }

    @Test
    void publishesTheCommittedTypedGraphWhenIdsOverlap() {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator flow = resource("flow", "shared");
        ServerResourceLocator function = resource("function", "shared");
        saveFlow(authority, flow, SAVE_MUTATION);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] functionEnvelope = boundary.encode(source(function, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("function", 1L, ACTIVATE_MUTATION), function);
        authority.save(function, functionEnvelope, ACTIVATE_MUTATION, 0L,
            boundary.decode(functionEnvelope, function).envelope().assetHash());
        List<FlowStorage.GraphChange> changes = new ArrayList<>();
        storage.setGraphChangeListener(changes::add);

        authority.publishCommitted(function, ACTIVATE_MUTATION);

        assertEquals(List.of(new FlowStorage.GraphChange("function", "shared")), changes);
    }

    @Test
    void rejectsChecksumAndLocatorDriftBeforeDelegatingMutation() {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator resource = resource("checksum");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] envelope = boundary.encode(graph(resource, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, SAVE_MUTATION), resource);

        assertThrows(IllegalArgumentException.class,
            () -> authority.save(resource, envelope, SAVE_MUTATION, 0L, new ContentHash("f".repeat(64))));
        assertTrue(authority.state(resource).isEmpty());

        byte[] wrongMutation = boundary.encode(graph(resource, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, ACTIVATE_MUTATION), resource);
        assertThrows(IllegalArgumentException.class,
            () -> authority.save(resource, wrongMutation, SAVE_MUTATION, 0L,
                boundary.decode(wrongMutation, resource).envelope().assetHash()));
        assertTrue(authority.state(resource).isEmpty());

        byte[] wrongRevision = boundary.encode(graph(resource, 2L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 2L, SAVE_MUTATION), resource);
        assertThrows(IllegalArgumentException.class,
            () -> authority.save(resource, wrongRevision, SAVE_MUTATION, 0L,
                boundary.decode(wrongRevision, resource).envelope().assetHash()));
        assertTrue(authority.state(resource).isEmpty());

        ServerResourceLocator wrongServer = new ServerResourceLocator(
            new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555")), resource.key());
        assertThrows(IllegalArgumentException.class,
            () -> authority.save(wrongServer, envelope, SAVE_MUTATION, 0L,
                boundary.decode(envelope, resource).envelope().assetHash()));
        assertTrue(authority.state(resource).isEmpty());
    }

    @Test
    void validatesDeleteAssetPreconditionSeparatelyFromCorePayloadChecksum() {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator resource = resource("delete-precondition");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] envelope = boundary.encode(graph(resource, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, SAVE_MUTATION), resource);
        CoreGraphStorageBoundary.Decoded saved = authority.save(resource, envelope, SAVE_MUTATION, 0L,
            boundary.decode(envelope, resource).envelope().assetHash());

        ContentHash assetHash = saved.envelope().assetHash();
        ContentHash payloadChecksum = saved.graphDocument().checksum();
        assertThrows(IllegalStateException.class,
            () -> authority.delete(resource, DELETE_MUTATION, 1L, new ContentHash("f".repeat(64)),
                payloadChecksum));
        assertThrows(IllegalStateException.class,
            () -> authority.delete(resource, DELETE_MUTATION, 1L, assetHash, new ContentHash("e".repeat(64))));
        assertTrue(authority.state(resource).isPresent());
    }

    @Test
    void unavailableAuthorityFailsClosedWithoutAStorageFallback() {
        CoreGraphResourceAuthority authority = CoreGraphResourceAuthority.unavailable();
        ServerResourceLocator resource = resource("unavailable");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] envelope = boundary.encode(graph(resource, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, SAVE_MUTATION), resource);
        ContentHash checksum = boundary.decode(envelope, resource).envelope().assetHash();

        assertFalse(authority.available());
        assertThrows(IllegalStateException.class, () -> authority.load(resource));
        assertThrows(IllegalStateException.class, () -> authority.list("flow"));
        assertThrows(IllegalStateException.class, () -> authority.state(resource));
        assertThrows(IllegalStateException.class,
            () -> authority.save(resource, envelope, SAVE_MUTATION, 0L, checksum));
        assertThrows(IllegalStateException.class,
            () -> authority.activate(resource, ResourceActivationState.ACTIVE, ACTIVATE_MUTATION, 0L, checksum));
        assertThrows(IllegalStateException.class,
            () -> authority.delete(resource, DELETE_MUTATION, 0L, checksum));
    }

    @Test
    void repairsReceiptBoundOneBehindCoordinatorStateAndReplaysTheExactMutation() throws Exception {
        FlowStorage storage = storage();
        FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        ServerResourceLocator resource = resource("revision-skew");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        CoreGraphStorageBoundary.Decoded initial = saveFlow(authority, resource, SAVE_MUTATION);
        AssetTransactionCoordinator.Snapshot beforeSkew = coordinator.read(value -> value);
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", resource.id());
        Path path = beforeSkew.path(key).orElseThrow();
        byte[] skewed = revisionSkew(boundary, boundary.encode(initial), 2L, SKEW_MUTATION);
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(SKEW_MUTATION, beforeSkew.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(key, path,
                beforeSkew.state(key).orElseThrow(), skewed)), List.of()));
        AssetTransactionCoordinator.ExpectedProject projectBeforeRepair = coordinator.read(
            AssetTransactionCoordinator.Snapshot::project);
        JsonValue.JsonObject skewedObject = CanonicalCodec.requireObject(CanonicalCodec.decode(skewed));
        ContentHash sourceAssetHash = new ContentHash(((JsonValue.JsonString) skewedObject.value(
            CoreGraphStorageBoundary.ASSET_HASH)).value());
        CoreGraphResourceAuthority.CoreRevisionRepairSource source =
            new CoreGraphResourceAuthority.CoreRevisionRepairSource(2L, SKEW_MUTATION, sourceAssetHash,
                initial.graphDocument().checksum(), CoreGraphStorageBoundary.GRAPH_DOCUMENT_KIND,
                ResourceActivationState.ACTIVE);

        CoreGraphResourceAuthority.CoreRevisionRepairSource wrong =
            new CoreGraphResourceAuthority.CoreRevisionRepairSource(2L, SKEW_MUTATION, sourceAssetHash,
                new ContentHash("f".repeat(64)), CoreGraphStorageBoundary.GRAPH_DOCUMENT_KIND,
                ResourceActivationState.ACTIVE);
        assertThrows(IllegalStateException.class,
            () -> authority.repairRevisionSkew(resource, wrong, REPAIR_MUTATION));

        CoreGraphResourceAuthority.CoreRevisionRepairResult repaired = authority.repairRevisionSkew(resource, source,
            REPAIR_MUTATION).orElseThrow();
        CoreGraphResourceAuthority.CoreRevisionRepairResult replayed = authority.repairRevisionSkew(resource, source,
            REPAIR_MUTATION).orElseThrow();

        assertFalse(repaired.replayed());
        assertTrue(replayed.replayed());
        assertEquals(3L, repaired.repaired().envelope().assetRevision());
        assertEquals(3L, repaired.repaired().graphDocument().revision());
        assertEquals(REPAIR_MUTATION.toString(), repaired.repaired().envelope().assetMutationId());
        assertEquals(initial.graphDocument().nodes(), repaired.repaired().graphDocument().nodes());
        assertEquals(initial.graphDocument().connections(), repaired.repaired().graphDocument().connections());
        assertEquals(initial.graphDocument().unknown(), repaired.repaired().graphDocument().unknown());
        assertEquals(projectBeforeRepair, coordinator.read(AssetTransactionCoordinator.Snapshot::project));
        assertEquals(REPAIR_MUTATION, coordinator.read(snapshot -> snapshot.mutationId(key).orElseThrow()));
        assertArrayEquals(boundary.encode(repaired.repaired()), boundary.encode(replayed.repaired()));
    }

    private FlowStorage storage() {
        assetsGate = new AssetPersistenceGate(tempDir);
        try {
            coordinator = new AssetTransactionCoordinator(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open Core graph authority test persistence", exception);
        }
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir), assetsGate, SERVER, coordinator);
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static FunctionSourceDocument source(ServerResourceLocator resource, long revision) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("futureGraph", true)));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("futureSignature", true));
        return new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("futureSource", true)));
    }

    private static CoreGraphStorageBoundary.Decoded saveFlow(FlowStorageCoreGraphResourceAuthority authority,
                                                               ServerResourceLocator resource, UUID mutationId) {
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] envelope = boundary.encode(graph(resource, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, mutationId), resource);
        return authority.save(resource, envelope, mutationId, 0L, boundary.decode(envelope, resource).envelope().assetHash());
    }

    private static ServerResourceLocator resource(String id) {
        return resource("flow", id);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private static byte[] revisionSkew(CoreGraphStorageBoundary boundary, byte[] encoded, long revision,
                                       UUID mutationId) {
        JsonValue.JsonObject object = CanonicalCodec.requireObject(CanonicalCodec.decode(encoded));
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        fields.put(CoreGraphStorageBoundary.ASSET_REVISION, JsonValue.of(revision));
        fields.put(CoreGraphStorageBoundary.ASSET_MUTATION_ID, JsonValue.of(mutationId.toString()));
        fields.remove(CoreGraphStorageBoundary.ASSET_HASH);
        fields.put(CoreGraphStorageBoundary.ASSET_HASH, JsonValue.of("0".repeat(64)));
        JsonValue.JsonObject unhashed = JsonValue.object(fields);
        fields.put(CoreGraphStorageBoundary.ASSET_HASH,
            JsonValue.of(boundary.assetIntegrityHash(unhashed).canonicalText()));
        return JsonValue.object(fields).canonicalBytes();
    }
}
