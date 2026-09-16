package restudio.resync.modules.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.protocol.FrameSender;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.resources.ReSyncManagedResource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowWorkspaceServiceTest {
    private final FlowWorkspaceService service = new FlowWorkspaceService(null, null, null);

    @Test
    void appliesEntityAndConnectionOperations() {
        JsonObject document = object("""
            {"nodes":{"first":{"x":10,"y":20}},"connections":[]}
            """);
        JsonObject connection = object("""
            {"sourceNodeId":"first","sourcePin":"out","targetNodeId":"second","targetPin":"in"}
            """);

        service.apply(document, new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80")));
        service.apply(document, new WorkspacePatch<>("set", "/nodes/second", object("""
            {"x":140,"y":20}
            """)));
        service.apply(document, new WorkspacePatch<>("array_add", "/connections", connection));
        service.apply(document, new WorkspacePatch<>("array_add", "/connections", connection));

        assertEquals(80, document.getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals(2, document.getAsJsonObject("nodes").size());
        assertEquals(1, document.getAsJsonArray("connections").size());

        service.apply(document, new WorkspacePatch<>("array_remove", "/connections", connection));
        service.apply(document, new WorkspacePatch<>("remove", "/nodes/first", null));

        assertEquals(0, document.getAsJsonArray("connections").size());
        assertFalse(document.getAsJsonObject("nodes").has("first"));
    }

    @Test
    void rejectsClientChangesToServerOwnedIdentity() {
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/id", JsonParser.parseString("\"other\""))));
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/worldName", JsonParser.parseString("\"other\""))));
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/resourceRevision", JsonParser.parseString("30"))));
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/enabled", JsonParser.parseString("true"))));
        assertTrue(service.validClientPatch(new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80"))));
    }

    @Test
    void cleanupDoesNotWaitForWorkspaceBroadcasts() throws Exception {
        FlowGraph graph = new FlowGraph();
        graph.setId("shared");
        graph.setResourceType("flow");
        graph.getNodes().put("first", new FlowNode("event:startup", 10, 20, Map.of()));
        FixtureGraphAdapter adapter = new FixtureGraphAdapter(graph);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(adapter);
        BlockingWorkspaceSender sender = new BlockingWorkspaceSender();
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, sender, null, resources);
        Session first = session("first");
        Session second = session("second");
        ByteBuffer join = jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """);
        workspaceService.handleJoin(first, join.duplicate());
        workspaceService.handleJoin(second, join.duplicate());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> workspaceService.handleOperation(first, jsonBuffer("""
                {"type":"fixture:graph","resourceId":"shared","operationId":"move","baseSequence":0,
                 "patches":[{"op":"set","path":"/nodes/first/x","value":80}]}
                """)));
            assertTrue(sender.operationStarted.await(1, TimeUnit.SECONDS));
            executor.submit(() -> workspaceService.cleanup(first)).get(1, TimeUnit.SECONDS);
        } finally {
            sender.releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsPathsOutsideTheDocumentShape() {
        JsonObject document = object("""
            {"nodes":{},"connections":[]}
            """);

        assertThrows(IllegalArgumentException.class,
            () -> service.apply(document, new WorkspacePatch<>("set", "nodes/first", object("{}"))));
    }

    @Test
    void rebasesAcceptedPatchesOntoNewerResourceContent() {
        JsonObject latest = object("""
            {"resourceRevision":2,"nodes":{"first":{"x":10,"y":90}},"connections":[]}
            """);
        List<WorkspacePatch<JsonElement>> patches = List.of(
            new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80")),
            new WorkspacePatch<>("set", "/nodes/second", object("""
                {"x":140,"y":20}
                """)));

        JsonObject rebased = service.rebase(latest, patches);

        assertEquals(2, rebased.get("resourceRevision").getAsInt());
        assertEquals(80, rebased.getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals(90, rebased.getAsJsonObject("nodes").getAsJsonObject("first").get("y").getAsInt());
        assertTrue(rebased.getAsJsonObject("nodes").has("second"));
    }

    @Test
    void loadsAndPersistsAnyRegisteredDesignerResource() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService genericService = new FlowWorkspaceService(null, null, null, null, resources);

        JsonObject document = genericService.loadDocument("fixture:designer", "designer");
        genericService.apply(document, new WorkspacePatch<>("set", "/value", JsonParser.parseString("\"Shared\"")));
        genericService.persistDocument("fixture:designer", "designer", document);

        assertEquals("Shared", adapter.values.get("designer").value());
        document.addProperty("id", "other");
        assertThrows(IllegalStateException.class, () -> genericService.persistDocument("fixture:designer", "designer", document));
    }

    @Test
    void workspaceDurableCompletionRunsExactlyOnceAfterPersist() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);

        workspaceService.persistDocument("fixture:designer", "designer", object("""
            {"id":"designer","value":"Saved"}
            """));

        assertEquals("Saved", adapter.values.get("designer").value());
        assertEquals(1, adapter.afterSaves.get());
        assertTrue(resources.save("fixture:designer", new FixtureResource("designer", "Next")).success());
    }

    @Test
    void workspaceCancelsDroppedCompletionAfterPostSaveSerializationFailure() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        adapter.failSerializationAt(1);
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);

        assertThrows(IllegalStateException.class, () -> workspaceService.persistDocument("fixture:designer", "designer", object("""
            {"id":"designer","value":"Dropped"}
            """)));

        adapter.failSerializationAt(0);
        workspaceService.persistDocument("fixture:designer", "designer", object("""
            {"id":"designer","value":"Recovered"}
            """));
        assertEquals("Recovered", adapter.values.get("designer").value());
    }

    @Test
    void workspaceCleanupCancelsPendingSessionContinuation() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);
        resources.setWorkspaceService(workspaceService);
        Session editor = session("editor");

        resources.saveFromSession(editor, adapter, new FixtureResource("designer", "Pending"));
        workspaceService.cleanup(editor);

        assertTrue(resources.save("fixture:designer", new FixtureResource("designer", "Recovered")).success());
    }

    @Test
    void workspaceShutdownClosesPendingSessionSavesWithoutMembership() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);
        resources.setWorkspaceService(workspaceService);
        Session editor = session("editor");

        resources.saveFromSession(editor, adapter, new FixtureResource("designer", "Pending"));
        workspaceService.shutdown();

        assertTrue(resources.save("fixture:designer", new FixtureResource("designer", "Recovered")).success());
        assertThrows(IllegalStateException.class,
            () -> resources.saveFromSession(editor, adapter, new FixtureResource("designer", "Rejected")));
    }

    @Test
    void workspaceRejectsSameKeyReentryBeforeCreatingAnotherCompletion() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);
        AtomicReference<RuntimeException> duplicateFailure = new AtomicReference<>();
        resources.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                try {
                    workspaceService.persistDocument(type, resourceId, object("""
                        {"id":"designer","value":"Reentered"}
                        """));
                } catch (RuntimeException exception) {
                    duplicateFailure.set(exception);
                }
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });

        workspaceService.persistDocument("fixture:designer", "designer", object("""
            {"id":"designer","value":"Saved"}
            """));

        assertTrue(duplicateFailure.get() instanceof IllegalStateException);
        assertEquals(1, adapter.saves.get());
    }

    @Test
    void workspaceDoesNotCancelForeignSameKeyCompletion() {
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.values.put("designer", new FixtureResource("designer", "Initial"));
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, null, null, resources);
        FlowResourceMutationLease.DeferredCompletion foreign = resources.saveAuthoritativeDurableHandle("fixture:designer",
            new FixtureResource("designer", "Foreign"));

        workspaceService.resourceDeleted("fixture:designer", "designer");

        assertFalse(resources.save("fixture:designer", new FixtureResource("designer", "Blocked")).success());
        assertTrue(foreign.cancel());
        assertTrue(resources.save("fixture:designer", new FixtureResource("designer", "Recovered")).success());
    }

    @Test
    void nonCoreGraphWorkspacePersistencePublishesTheAuthoritativeCommit() {
        FixtureGraphAdapter adapter = new FixtureGraphAdapter();
        FlowResourceRegistry resources = new FlowResourceRegistry();
        AtomicReference<String> committed = new AtomicReference<>("");
        resources.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                committed.set(type + ":" + resourceId);
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        resources.register(adapter);
        FlowWorkspaceService graphService = new FlowWorkspaceService(null, null, null, null, resources);
        JsonObject document = object("""
            {"id":"shared","resourceType":"flow","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);

        graphService.persistDocument("fixture:graph", "shared", document);

        assertEquals("fixture:graph:shared", committed.get());
        assertEquals(1L, adapter.get("shared").getResourceRevision());
    }

    @Test
    void requiresEachTransportToJoinTheWorkspace() {
        FlowGraph original = new FlowGraph();
        original.setId("shared");
        original.setResourceType("flow");
        original.getNodes().put("first", new FlowNode("event:startup", 10, 20, Map.of()));
        FixtureGraphAdapter adapter = new FixtureGraphAdapter(original);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(adapter);
        RecordingWorkspaceSender sender = new RecordingWorkspaceSender();
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, sender, null, resources);
        Session direct = session("direct");
        direct.setCollaborationIdentity(new CollaborationIdentity("user", "Alex", "", "restudio"));
        Session bridge = session("bridge");
        bridge.setCollaborationIdentity(new CollaborationIdentity("user", "Alex", "", "minecraft"));
        workspaceService.handleJoin(direct, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """));
        workspaceService.handleJoin(bridge, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """));
        workspaceService.handleOperation(bridge, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared","operationId":"move-x","baseSequence":0,
             "patches":[{"op":"set","path":"/nodes/first/x","value":80}]}
            """));
        workspaceService.handleAwareness(bridge, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared","state":{"x":0.5,"y":0.5}}
            """));
        workspaceService.handleJoin(session("observer"), jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """));

        JsonObject snapshot = JsonParser.parseString(sender.latestSnapshot.get()).getAsJsonObject();
        assertEquals(1L, snapshot.get("sequence").getAsLong());
        assertEquals(80, snapshot.getAsJsonObject("document").getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals("bridge", snapshot.getAsJsonArray("awareness").get(0).getAsJsonObject().get("authorSessionId").getAsString());
    }

    @Test
    void leavingWorkspaceDiscardsUnsavedChanges() {
        FlowGraph graph = new FlowGraph();
        graph.setId("shared");
        graph.setResourceType("command");
        graph.getNodes().put("first", new FlowNode("event.resync.command", 10, 20, Map.of()));
        FixtureGraphAdapter adapter = new FixtureGraphAdapter(graph);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(adapter);
        RecordingWorkspaceSender sender = new RecordingWorkspaceSender();
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, sender, null, resources);
        Session editor = session("editor");
        ByteBuffer target = jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """);
        workspaceService.handleJoin(editor, target.duplicate());
        workspaceService.handleOperation(editor, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared","operationId":"move","baseSequence":0,
             "patches":[{"op":"set","path":"/nodes/first/x","value":80}]}
            """));
        workspaceService.handleLeave(editor, target.duplicate());
        workspaceService.handleJoin(session("reopened"), target.duplicate());

        JsonObject snapshot = JsonParser.parseString(sender.latestSnapshot.get()).getAsJsonObject();
        assertEquals(10, snapshot.getAsJsonObject("document").getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals(10, adapter.get("shared").getNodes().get("first").getX());
        assertEquals(1L, adapter.get("shared").getResourceRevision());
    }

    @Test
    void collaboratorSaveDoesNotOverwriteAnExternalCommit() {
        FlowGraph original = new FlowGraph();
        original.setId("shared");
        original.setResourceType("flow");
        original.getNodes().put("first", new FlowNode("event:startup", 10, 20, Map.of()));
        FixtureGraphAdapter adapter = new FixtureGraphAdapter(original);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, new RecordingWorkspaceSender(), null, resources);
        Session editor = session("editor");
        workspaceService.handleJoin(editor, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """));
        workspaceService.handleOperation(editor, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared","operationId":"move-x","baseSequence":0,
             "patches":[{"op":"set","path":"/nodes/first/x","value":80}]}
            """));
        FlowGraph external = adapter.get("shared");
        external.getNodes().get("first").setY(70);
        adapter.save(external);
        FlowGraph submitted = FlowSerializer.deserialize(FlowSerializer.serialize(original));
        submitted.getNodes().get("first").setX(80);
        submitted.getNodes().get("first").setY(90);

        assertThrows(ResourceRevisionConflictException.class, () -> workspaceService.save(editor, adapter, submitted));

        FlowGraph stored = adapter.get("shared");
        assertEquals(2L, stored.getResourceRevision());
        assertEquals(70, stored.getNodes().get("first").getY());
    }

    @Test
    void collaboratorSaveCannotChangeAuthoritativeActivation() {
        FlowGraph original = new FlowGraph();
        original.setId("shared");
        original.setResourceType("flow");
        original.setEnabled(false);
        original.getNodes().put("first", new FlowNode("event:startup", 10, 20, Map.of()));
        FixtureGraphAdapter adapter = new FixtureGraphAdapter(original);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(adapter);
        FlowWorkspaceService workspaceService = new FlowWorkspaceService(null, null, new RecordingWorkspaceSender(), null, resources);
        Session editor = session("editor");
        workspaceService.handleJoin(editor, jsonBuffer("""
            {"type":"fixture:graph","resourceId":"shared"}
            """));
        FlowGraph submitted = FlowSerializer.deserialize(FlowSerializer.serialize(original));
        submitted.setEnabled(true);
        submitted.getNodes().get("first").setX(80);

        FlowGraph saved = workspaceService.save(editor, adapter, submitted);

        assertFalse(saved.isEnabled());
        assertFalse(adapter.get("shared").isEnabled());
        assertEquals(80, adapter.get("shared").getNodes().get("first").getX());
    }

    @Test
    void delegatesAuthoritativeDocumentsToWorkspaceProviders() {
        FlowWorkspaceService providerService = new FlowWorkspaceService(null, null, null);
        AtomicReference<JsonObject> stored = new AtomicReference<>(object("""
            {"worldName":"world","difficulty":"NORMAL"}
            """));
        providerService.registerDocumentProvider(new FlowWorkspaceDocumentProvider() {
            @Override
            public String type() {
                return "world";
            }

            @Override
            public JsonObject load(String resourceId) {
                return "world".equals(resourceId) ? stored.get().deepCopy() : null;
            }

            @Override
            public void persist(String resourceId, JsonObject document) {
                assertEquals("world", resourceId);
                stored.set(document.deepCopy());
            }
        });

        assertTrue(providerService.supportsWorkspaceType("world"));
        assertFalse(providerService.supportsWorkspaceType("unknown"));
        JsonObject document = providerService.loadDocument("world", "world");
        providerService.apply(document, new WorkspacePatch<>("set", "/difficulty", JsonParser.parseString("\"HARD\"")));
        providerService.persistDocument("world", "world", document);

        assertEquals("HARD", stored.get().get("difficulty").getAsString());
    }

    private JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private ByteBuffer jsonBuffer(String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private Session session(String id) {
        FrameSender frameSender = new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        };
        return new Session(id, id, new ConnectionInfo(null, frameSender, id.hashCode()));
    }

    private record FixtureResource(String id, String value) {
    }

    private static final class FixtureAdapter implements FlowResourceAdapter<FixtureResource> {
        private final Map<String, FixtureResource> values = new LinkedHashMap<>();
        private final ReSyncManagedResource descriptor = new ReSyncManagedResource("fixture:designer", "Designer", "fixture/designers", null, true);
        private final AtomicInteger failAtSerialization = new AtomicInteger();
        private final AtomicInteger serializationCalls = new AtomicInteger();
        private final AtomicInteger afterSaves = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();

        @Override
        public ReSyncManagedResource descriptor() {
            return descriptor;
        }

        @Override
        public FixtureResource get(String id) {
            return values.get(id);
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public FixtureResource deserialize(String json) {
            JsonObject document = JsonParser.parseString(json).getAsJsonObject();
            return new FixtureResource(document.get("id").getAsString(), document.get("value").getAsString());
        }

        @Override
        public String serialize(FixtureResource value) {
            int call = serializationCalls.incrementAndGet();
            if (call == failAtSerialization.get()) {
                throw new IllegalStateException("Fixture serialization rejected");
            }
            return "{\"id\":\"" + value.id() + "\",\"value\":\"" + value.value() + "\"}";
        }

        @Override
        public String id(FixtureResource value) {
            return value.id();
        }

        @Override
        public void save(FixtureResource value) {
            saves.incrementAndGet();
            values.put(value.id(), value);
        }

        @Override
        public void afterSave(FixtureResource value) {
            afterSaves.incrementAndGet();
        }

        private void failSerializationAt(int call) {
            failAtSerialization.set(call);
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }
    }

    private static final class BlockingWorkspaceSender extends FlowPacketSender {
        private final CountDownLatch operationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseOperation = new CountDownLatch(1);

        private BlockingWorkspaceSender() {
            super(null, 1, Set.of());
        }

        @Override
        public void sendWorkspaceOperation(Session session, String json) {
            operationStarted.countDown();
            try {
                releaseOperation.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void sendWorkspaceSnapshot(Session session, String json) {
        }

        @Override
        public void sendWorkspaceAwareness(Session session, String json) {
        }

        @Override
        public void sendWorkspaceResync(Session session, String json) {
        }
    }

    private static final class RecordingWorkspaceSender extends FlowPacketSender {
        private final AtomicReference<String> latestSnapshot = new AtomicReference<>("");

        private RecordingWorkspaceSender() {
            super(null, 1, Set.of());
        }

        @Override
        public void sendWorkspaceOperation(Session session, String json) {
        }

        @Override
        public void sendWorkspaceSnapshot(Session session, String json) {
            latestSnapshot.set(json);
        }

        @Override
        public void sendWorkspaceAwareness(Session session, String json) {
        }

        @Override
        public void sendWorkspaceResync(Session session, String json) {
        }
    }

    private static final class FixtureGraphAdapter implements FlowResourceAdapter<FlowGraph> {
        private final Map<String, FlowGraph> values = new LinkedHashMap<>();
        private final ReSyncManagedResource descriptor = new ReSyncManagedResource("fixture:graph", "Graph", "fixture/graphs", null, true);

        private FixtureGraphAdapter() {
        }

        private FixtureGraphAdapter(FlowGraph graph) {
            FlowGraph initial = copy(graph);
            initial.setResourceRevision(1L);
            graph.setResourceRevision(1L);
            values.put(initial.getId(), initial);
        }

        @Override
        public ReSyncManagedResource descriptor() {
            return descriptor;
        }

        @Override
        public FlowGraph get(String id) {
            FlowGraph value = values.get(id);
            return value != null ? copy(value) : null;
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public FlowGraph deserialize(String json) {
            return FlowSerializer.deserialize(json);
        }

        @Override
        public String serialize(FlowGraph value) {
            return FlowSerializer.serialize(value);
        }

        @Override
        public String id(FlowGraph value) {
            return value.getId();
        }

        @Override
        public void save(FlowGraph value) {
            FlowGraph current = values.get(value.getId());
            long currentRevision = current != null ? current.getResourceRevision() : 0L;
            if (value.getResourceRevision() != currentRevision) {
                throw new ResourceRevisionConflictException(value.getId(), value.getResourceRevision(), currentRevision);
            }
            FlowGraph stored = copy(value);
            stored.setResourceRevision(Math.addExact(currentRevision, 1L));
            value.setResourceRevision(stored.getResourceRevision());
            values.put(stored.getId(), stored);
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }

        private static FlowGraph copy(FlowGraph graph) {
            return FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        }
    }
}
