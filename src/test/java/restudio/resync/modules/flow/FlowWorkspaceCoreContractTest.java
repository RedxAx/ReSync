package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ProtocolEditability;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.protocol.FrameSender;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowWorkspaceCoreContractTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final RepeatableElementId FIRST =
        RepeatableElementId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final RepeatableElementId SECOND =
        RepeatableElementId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final RepeatableElementId FOREIGN =
        RepeatableElementId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));

    @TempDir
    Path directory;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

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
    void validatesTheWholeCoreBatchAndPublishesOneIndependentWorkspaceRevision() {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph(), ResourceActivationState.ACTIVE, UUID.fromString("33333333-3333-4333-8333-333333333333"), 0L);
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(storage, sender, null);
        Session editor = session("editor");
        service.setEditability(editor, "flow", "workspace", ProtocolEditability.EDITABLE);
        service.handleJoin(editor, json("""
            {"type":"FLOW","resourceId":"workspace"}
            """));
        String operation = """
            {"type":"FLOW","resourceId":"workspace","operationId":"batch-one","baseSequence":0,"patches":[
              {"op":"remove","path":"/nodes/@22222222-2222-4222-8222-222222222222/branches/@result/cases/@success"},
              {"op":"set","path":"/nodes/@22222222-2222-4222-8222-222222222222/branches/@result/selectedCaseId","value":"failure"}
            ]}
            """;

        service.handleOperation(editor, json(operation));
        service.handleOperation(editor, json(operation));
        service.handleOperation(editor, json(operation.replace("\"failure\"}", "\"success\"}")));
        service.handleJoin(session("observer"), json("""
            {"type":"flow","resourceId":"workspace"}
            """));

        JsonObject snapshot = JsonParser.parseString(sender.snapshot.get()).getAsJsonObject();
        JsonObject branch = snapshot.getAsJsonObject("document").getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonArray("branches").get(0).getAsJsonObject();
        assertEquals(1L, snapshot.get("sequence").getAsLong());
        assertEquals("failure", branch.get("selectedCaseId").getAsString());
        assertEquals(1, branch.getAsJsonArray("cases").size());
        assertEquals(2, sender.operations.get());
        assertEquals("Operation ID Conflict", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
        assertEquals(1L, storage.getCoreGraph("flow", "workspace").orElseThrow().graphDocument().revision());
    }

    @Test
    void reordersOrderedCoreRepeatablesOnceAndRejectsForeignChangesWithoutPartialMutation() {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph(), ResourceActivationState.ACTIVE, UUID.fromString("77777777-7777-4777-8777-777777777777"), 0L);
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(storage, sender, null);
        Session editor = session("reorder-editor");
        service.setEditability(editor, "flow", "workspace", ProtocolEditability.EDITABLE);
        service.handleJoin(editor, json("""
            {"type":"flow","resourceId":"workspace"}
            """));
        ByteBuffer operation = reorder("flow", "workspace", "reorder-one", 0L, "group", List.of(FIRST, SECOND),
            List.of(SECOND, FIRST));

        service.handleOperation(editor, operation);
        service.handleOperation(editor,
            reorder("flow", "workspace", "reorder-one", 0L, "group", List.of(FIRST, SECOND), List.of(SECOND, FIRST)));
        service.handleOperation(editor,
            reorder("flow", "workspace", "malformed", 1L, "group", List.of(SECOND, FOREIGN), List.of(FOREIGN, SECOND)));
        assertEquals("Invalid Operation", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
        service.handleOperation(editor,
            reorder("flow", "workspace", "foreign", 1L, "foreign", List.of(SECOND, FIRST), List.of(FIRST, SECOND)));
        service.handleJoin(session("reorder-observer"), json("""
            {"type":"flow","resourceId":"workspace"}
            """));

        JsonObject snapshot = JsonParser.parseString(sender.snapshot.get()).getAsJsonObject();
        JsonArray elements = snapshot.getAsJsonObject("document").getAsJsonArray("nodes").get(0).getAsJsonObject()
            .getAsJsonArray("repeatables").get(0).getAsJsonObject().getAsJsonArray("elements");
        assertEquals(1L, snapshot.get("sequence").getAsLong());
        assertEquals(2, elements.size());
        assertEquals(SECOND.canonicalText(), elements.get(0).getAsJsonObject().get("elementId").getAsString());
        assertEquals("second", elements.get(0).getAsJsonObject().get("marker").getAsString());
        assertEquals(FIRST.canonicalText(), elements.get(1).getAsJsonObject().get("elementId").getAsString());
        assertEquals("first", elements.get(1).getAsJsonObject().get("marker").getAsString());
        assertEquals(2, sender.operations.get());
        assertEquals("Invalid Operation", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void rejectsArrayReorderOutsideCoreGraphWorkspaces() {
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(null, sender, null);
        service.registerDocumentProvider(new FlowWorkspaceDocumentProvider() {
            @Override
            public String type() {
                return "document";
            }

            @Override
            public JsonObject load(String resourceId) {
                return JsonParser.parseString("{\"value\":true}").getAsJsonObject();
            }

            @Override
            public void persist(String resourceId, JsonObject document) {
            }
        });
        Session editor = session("document-editor");
        service.handleJoin(editor, json("""
            {"type":"document","resourceId":"shared"}
            """));

        service.handleOperation(editor,
            reorder("document", "shared", "foreign-type", 0L, "group", List.of(FIRST, SECOND), List.of(SECOND, FIRST)));
        service.handleJoin(session("document-observer"), json("""
            {"type":"document","resourceId":"shared"}
            """));

        JsonObject snapshot = JsonParser.parseString(sender.snapshot.get()).getAsJsonObject();
        assertEquals("Invalid Operation", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
        assertEquals(0, sender.operations.get());
        assertEquals(0L, snapshot.get("sequence").getAsLong());
        assertTrue(snapshot.getAsJsonObject("document").get("value").getAsBoolean());
    }

    @Test
    void rejectsCoreOperationsUntilEditabilityWasNegotiated() {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph(), ResourceActivationState.ACTIVE, UUID.fromString("99999999-9999-4999-8999-999999999999"), 0L);
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(storage, sender, null);
        Session viewer = session("viewer");
        service.handleJoin(viewer, json("""
            {"type":"flow","resourceId":"workspace"}
            """));

        service.handleOperation(viewer,
            reorder("flow", "workspace", "read-only", 0L, "group", List.of(FIRST, SECOND), List.of(SECOND, FIRST)));
        service.handleJoin(session("read-only-observer"), json("""
            {"type":"flow","resourceId":"workspace"}
            """));

        JsonObject snapshot = JsonParser.parseString(sender.snapshot.get()).getAsJsonObject();
        JsonArray elements = snapshot.getAsJsonObject("document").getAsJsonArray("nodes").get(0).getAsJsonObject()
            .getAsJsonArray("repeatables").get(0).getAsJsonObject().getAsJsonArray("elements");
        assertEquals("Workspace Read Only", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
        assertEquals(0, sender.operations.get());
        assertEquals(0L, snapshot.get("sequence").getAsLong());
        assertEquals(FIRST.canonicalText(), elements.get(0).getAsJsonObject().get("elementId").getAsString());
    }

    @Test
    void deletionFencesAConcurrentNullWorkspaceJoin() throws Exception {
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(null, sender, null);
        AtomicBoolean available = new AtomicBoolean(true);
        service.registerDocumentProvider(new FlowWorkspaceDocumentProvider() {
            @Override
            public String type() {
                return "document";
            }

            @Override
            public JsonObject load(String resourceId) {
                return available.get() ? JsonParser.parseString("{\"value\":true}").getAsJsonObject() : null;
            }

            @Override
            public void persist(String resourceId, JsonObject document) {
            }
        });
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var deletion = executor.submit(() -> service.delete("document", "shared", () -> {
                available.set(false);
                deleting.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(deleting.await(5, TimeUnit.SECONDS));
            var join = executor.submit(() -> {
                joining.countDown();
                service.handleJoin(session("late"), json("""
                    {"type":"document","resourceId":"shared"}
                    """));
            });
            assertTrue(joining.await(5, TimeUnit.SECONDS));
            assertFalse(join.isDone());
            release.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            join.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("", sender.snapshot.get());
        assertEquals("Resource Unavailable", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void missingCoreAuthorityNeverFallsBackToLegacyWorkspaceLoading() {
        RecordingSender sender = new RecordingSender();
        FlowWorkspaceService service = new FlowWorkspaceService(null, sender, null);

        service.handleJoin(session("missing"), json("""
            {"type":"flow","resourceId":"workspace"}
            """));

        assertEquals("", sender.snapshot.get());
        assertEquals("Resource Unavailable", JsonParser.parseString(sender.resync.get()).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void directCoreMutationsFailBeforeProtocolAuthorityWithoutLocalProjection() {
        FlowWorkspaceService service = new FlowWorkspaceService(null, null, new RecordingSender(), null, new FlowResourceRegistry());
        Session editor = session("missing-authority");
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "workspace");
        service.setEditability(editor, "flow", "workspace", ProtocolEditability.EDITABLE);

        assertThrows(IllegalStateException.class, () -> service.saveCore(editor, resource, new byte[0],
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), 0L, new ContentHash("c".repeat(64))));
        assertThrows(IllegalStateException.class, () -> service.deleteCore(editor, resource,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), 0L, new ContentHash("d".repeat(64))));
    }

    private FlowStorage storage() {
        assetsGate = new AssetPersistenceGate(directory);
        try {
            coordinator = new AssetTransactionCoordinator(directory.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open flow workspace test persistence", exception);
        }
        return new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), assetsGate, SERVER, coordinator);
    }

    private GraphDocument graph() {
        BranchBinding branch = new BranchBinding(BranchId.of("result"), CaseId.of("success"), List.of(
            new BranchCase(CaseId.of("failure"), Map.of()), new BranchCase(CaseId.of("success"), Map.of())));
        RepeatableBinding repeatable = new RepeatableBinding(RepeatableGroupId.of("group"), true, List.of(
            new RepeatableElement(FIRST, Map.of(), OpaqueData.of(Map.of("marker", "first"))),
            new RepeatableElement(SECOND, Map.of(), OpaqueData.of(Map.of("marker", "second")))));
        GraphNode node = new GraphNode(NODE, ContractRef.of(OwnerId.of("builtin"), NodeId.of("fixture")), 1, null, Map.of(), Map.of(), List.of(branch),
            List.of(repeatable), InspectorState.empty(), 0, 0, OpaqueData.empty());
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "workspace");
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private ByteBuffer reorder(String type, String resourceId, String operationId, long baseSequence, String groupId,
                               List<RepeatableElementId> expected, List<RepeatableElementId> order) {
        JsonArray expectedIds = new JsonArray();
        expected.forEach(id -> expectedIds.add(id.canonicalText()));
        JsonArray orderedIds = new JsonArray();
        order.forEach(id -> orderedIds.add(id.canonicalText()));
        JsonObject value = new JsonObject();
        value.add("expected", expectedIds);
        value.add("order", orderedIds);
        JsonObject patch = new JsonObject();
        patch.addProperty("op", "array_reorder");
        patch.addProperty("path", "/nodes/@" + NODE.canonicalText() + "/repeatables/@" + groupId + "/elements");
        patch.add("value", value);
        JsonArray patches = new JsonArray();
        patches.add(patch);
        JsonObject request = new JsonObject();
        request.addProperty("type", type);
        request.addProperty("resourceId", resourceId);
        request.addProperty("operationId", operationId);
        request.addProperty("baseSequence", baseSequence);
        request.add("patches", patches);
        return json(request.toString());
    }

    private ByteBuffer json(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private Session session(String id) {
        FrameSender transport = new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        };
        return new Session(id, id, new ConnectionInfo(null, transport, id.hashCode()));
    }

    private static final class RecordingSender extends FlowPacketSender {
        private final AtomicReference<String> snapshot = new AtomicReference<>("");
        private final AtomicReference<String> resync = new AtomicReference<>("");
        private final AtomicInteger operations = new AtomicInteger();

        private RecordingSender() {
            super(null, 1, Set.of());
        }

        @Override
        public void sendWorkspaceOperation(Session session, String json) {
            operations.incrementAndGet();
        }

        @Override
        public void sendWorkspaceSnapshot(Session session, String json) {
            snapshot.set(json);
        }

        @Override
        public void sendWorkspaceAwareness(Session session, String json) {
        }

        @Override
        public void sendWorkspaceResync(Session session, String json) {
            resync.set(json);
        }
    }
}
