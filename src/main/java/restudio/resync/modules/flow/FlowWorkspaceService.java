package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceRevision;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FlowWorkspaceService {
    private static final long CHECKPOINT_DELAY_MS = 500L;
    private static final int MAX_PATCHES = 512;
    private static final int MAX_PATCH_PATH_LENGTH = 512;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command", ReSyncResourceCatalog.CUSTOM_CONTENT);
    private static final Set<String> PATCH_OPERATIONS = Set.of("set", "remove", "array_add", "array_remove");
    private static final Set<String> IMMUTABLE_ROOTS = Set.of("id", "function", "resourceType", "resourceRevision", "resourceHash", "resourceMutationId");
    private final FlowStorage storage;
    private final CustomContentStorage customContentStorage;
    private final FlowPacketSender sender;
    private final FlowCollaborationService collaboration;
    private final Gson gson = new Gson();
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public FlowWorkspaceService(FlowStorage storage, FlowPacketSender sender, FlowCollaborationService collaboration) {
        this(storage, null, sender, collaboration);
    }

    public FlowWorkspaceService(FlowStorage storage, CustomContentStorage customContentStorage, FlowPacketSender sender, FlowCollaborationService collaboration) {
        this.storage = storage;
        this.customContentStorage = customContentStorage;
        this.sender = sender;
        this.collaboration = collaboration;
    }

    public void handleJoin(Session session, ByteBuffer buffer) {
        JoinRequest request = read(buffer, JoinRequest.class);
        if (!valid(request)) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent("", "", "Invalid Workspace")));
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        String key = key(type, resourceId);
        while (true) {
            Workspace workspace = workspace(type, resourceId);
            if (workspace == null) {
                sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Resource Unavailable")));
                return;
            }
            sessions.put(session.getSessionId(), session);
            synchronized (workspace) {
                if (workspaces.get(key) != workspace) {
                    continue;
                }
                workspace.members.add(session.getSessionId());
                sender.sendWorkspaceSnapshot(session, gson.toJson(new SnapshotEvent(type, resourceId, workspace.revision.sequence(),
                    workspace.document.deepCopy(), List.copyOf(workspace.awareness.values()))));
                return;
            }
        }
    }

    public void handleLeave(Session session, ByteBuffer buffer) {
        JoinRequest request = read(buffer, JoinRequest.class);
        if (request == null) {
            return;
        }
        leave(session, key(safe(request.type()), safe(request.resourceId())));
    }

    public void handleOperation(Session session, ByteBuffer buffer) {
        OperationRequest request = read(buffer, OperationRequest.class);
        if (!valid(request) || request.patches() == null || request.patches().isEmpty() || request.patches().size() > MAX_PATCHES
            || request.patches().stream().anyMatch(patch -> !validClientPatch(patch))) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(request != null ? request.type() : "", request != null ? request.resourceId() : "", "Invalid Operation")));
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Not Joined")));
            return;
        }
        synchronized (workspace) {
            if (!workspace.members.contains(session.getSessionId())) {
                sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Not Joined")));
                return;
            }
            WorkspaceRevision.Assessment<OperationEvent> assessment =
                workspace.revision.assess(request.baseSequence(), request.operationId());
            if (assessment.status() == WorkspaceRevision.Status.DUPLICATE) {
                sender.sendWorkspaceOperation(session, gson.toJson(assessment.existing()));
                return;
            }
            if (assessment.status() == WorkspaceRevision.Status.CONFLICT) {
                sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Sequence Conflict")));
                return;
            }
            try {
                JsonObject next = workspace.document.deepCopy();
                for (WorkspacePatch<JsonElement> patch : request.patches()) {
                    apply(next, patch);
                }
                workspace.document = next;
            } catch (RuntimeException exception) {
                sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Invalid Operation")));
                return;
            }
            request.patches().stream().map(this::copy).forEach(workspace.pendingPatches::add);
            workspace.dirtyAt = System.currentTimeMillis();
            OperationEvent event = workspace.revision.advance(request.operationId(), sequence ->
                new OperationEvent(type, resourceId, sequence, safe(request.operationId()), session.getSessionId(),
                    identity(session), List.copyOf(request.patches())));
            broadcastOperation(workspace, event);
        }
    }

    public void handleAwareness(Session session, ByteBuffer buffer) {
        AwarenessRequest request = read(buffer, AwarenessRequest.class);
        if (!valid(request)) {
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            return;
        }
        JsonObject state = request.state() != null ? request.state().deepCopy() : new JsonObject();
        AwarenessEvent event = new AwarenessEvent(type, resourceId, session.getSessionId(), identity(session), state,
            System.currentTimeMillis());
        synchronized (workspace) {
            if (!workspace.members.contains(session.getSessionId())) {
                return;
            }
            workspace.awareness.put(session.getSessionId(), event);
            broadcastAwareness(workspace, event);
        }
    }

    public void checkpoint() {
        long now = System.currentTimeMillis();
        for (Workspace workspace : workspaces.values()) {
            synchronized (workspace) {
                if (workspace.deleted || workspaces.get(key(workspace.type, workspace.resourceId)) != workspace
                    || workspace.dirtyAt == 0L || now - workspace.dirtyAt < CHECKPOINT_DELAY_MS) {
                    continue;
                }
                workspace.dirtyAt = 0L;
                try {
                    FlowGraph graph = FlowSerializer.deserialize(workspace.document.toString());
                    graph.setId(workspace.resourceId);
                    graph.setResourceType(workspace.type);
                    collaboration.suppressResourceEvents(() -> persistGraph(workspace.type, workspace.resourceId, graph));
                    List<WorkspacePatch<JsonElement>> metadata = metadataPatches(graph);
                    if (!metadata.isEmpty()) {
                        for (WorkspacePatch<JsonElement> patch : metadata) {
                            apply(workspace.document, patch);
                        }
                        long sequence = workspace.revision.advance();
                        OperationEvent event = new OperationEvent(workspace.type, workspace.resourceId, sequence, "checkpoint:" + sequence, "", null, metadata);
                        broadcastOperation(workspace, event);
                    }
                    workspace.pendingPatches.clear();
                    if (workspace.members.isEmpty() && workspace.dirtyAt == 0L) {
                        workspaces.remove(key(workspace.type, workspace.resourceId), workspace);
                    }
                } catch (ResourceRevisionConflictException exception) {
                    if (!rebaseFromStored(workspace, now)) {
                        workspace.dirtyAt = now;
                    }
                } catch (RuntimeException exception) {
                    workspace.dirtyAt = now;
                }
            }
        }
    }

    private List<WorkspacePatch<JsonElement>> metadataPatches(FlowGraph graph) {
        ArrayList<WorkspacePatch<JsonElement>> patches = new ArrayList<>();
        patches.add(new WorkspacePatch<>("set", "/resourceRevision", gson.toJsonTree(graph.getResourceRevision())));
        patches.add(new WorkspacePatch<>("set", "/resourceHash", gson.toJsonTree(graph.getResourceHash())));
        patches.add(new WorkspacePatch<>("set", "/resourceMutationId", gson.toJsonTree(graph.getResourceMutationId())));
        return List.copyOf(patches);
    }

    public void cleanup(Session session) {
        sessions.remove(session.getSessionId());
        for (Map.Entry<String, Workspace> entry : workspaces.entrySet()) {
            leave(session, entry.getKey());
        }
    }

    public void resourceDeleted(String type, String resourceId) {
        synchronized (workspaces) {
            for (Workspace workspace : List.copyOf(workspaces.values())) {
                if (!safe(type).equals(workspace.type) || !safe(resourceId).equals(workspace.resourceId)) {
                    continue;
                }
                synchronized (workspace) {
                    if (!workspaces.remove(key(workspace.type, workspace.resourceId), workspace)) {
                        continue;
                    }
                    workspace.deleted = true;
                    workspace.dirtyAt = 0L;
                    String json = gson.toJson(new ResyncEvent(workspace.type, workspace.resourceId, "Resource Unavailable"));
                    for (String member : List.copyOf(workspace.members)) {
                        Session target = sessions.get(member);
                        if (target != null) {
                            sender.sendWorkspaceResync(target, json);
                        }
                    }
                    workspace.members.clear();
                    workspace.awareness.clear();
                }
            }
            if (storage.getGraph(type, resourceId) != null) {
                storage.deleteGraph(type, resourceId);
            }
        }
    }

    public void resourceSaved(String type, String resourceId, String payload) {
        Workspace workspace = workspaces.get(key(type, resourceId));
        JsonObject latest = workspaceDocument(type, resourceId, payload);
        if (workspace == null || latest == null) {
            return;
        }
        synchronized (workspace) {
            if (workspaces.get(key(workspace.type, workspace.resourceId)) != workspace || workspace.deleted) {
                return;
            }
            workspace.document = rebase(latest, workspace.pendingPatches);
            workspace.dirtyAt = workspace.pendingPatches.isEmpty() ? 0L : System.currentTimeMillis();
            requestResync(workspace, "Resource Updated");
        }
    }

    private Workspace workspace(String type, String resourceId) {
        String key = key(type, resourceId);
        synchronized (workspaces) {
            Workspace existing = workspaces.get(key);
            if (existing != null) {
                return existing;
            }
            FlowGraph graph = loadGraph(type, resourceId);
            if (graph == null || !compatibleGraphType(type, graph)) {
                return null;
            }
            JsonObject document = JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
            Workspace created = new Workspace(type, resourceId, document);
            workspaces.put(key, created);
            return created;
        }
    }

    private void leave(Session session, String key) {
        Workspace workspace = workspaces.get(key);
        if (workspace == null) {
            return;
        }
        synchronized (workspace) {
            if (!workspace.members.remove(session.getSessionId())) {
                return;
            }
            workspace.awareness.remove(session.getSessionId());
            AwarenessEvent event = new AwarenessEvent(workspace.type, workspace.resourceId, session.getSessionId(), identity(session), new JsonObject(),
                System.currentTimeMillis());
            broadcastAwareness(workspace, event);
            if (workspace.members.isEmpty() && workspace.dirtyAt == 0L) {
                workspaces.remove(key, workspace);
            }
        }
    }

    private void broadcastOperation(Workspace workspace, OperationEvent event) {
        String json = gson.toJson(event);
        for (String member : List.copyOf(workspace.members)) {
            Session target = sessions.get(member);
            if (target != null) {
                sender.sendWorkspaceOperation(target, json);
            }
        }
    }

    private void broadcastAwareness(Workspace workspace, AwarenessEvent event) {
        String json = gson.toJson(event);
        for (String member : List.copyOf(workspace.members)) {
            if (member.equals(event.authorSessionId())) {
                continue;
            }
            Session target = sessions.get(member);
            if (target != null) {
                sender.sendWorkspaceAwareness(target, json);
            }
        }
    }

    JsonObject rebase(JsonObject latest, List<WorkspacePatch<JsonElement>> patches) {
        JsonObject document = latest.deepCopy();
        if (patches != null) {
            patches.forEach(patch -> apply(document, patch));
        }
        return document;
    }

    private boolean rebaseFromStored(Workspace workspace, long now) {
        FlowGraph latest = loadGraph(workspace.type, workspace.resourceId);
        if (latest == null) {
            return false;
        }
        JsonObject document = JsonParser.parseString(FlowSerializer.serialize(latest)).getAsJsonObject();
        workspace.document = rebase(document, workspace.pendingPatches);
        workspace.dirtyAt = workspace.pendingPatches.isEmpty() ? 0L : now;
        requestResync(workspace, "Resource Updated");
        return true;
    }

    private JsonObject workspaceDocument(String type, String resourceId, String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
                FlowGraph graph = FlowSerializer.deserialize(payload);
                return JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
            }
            CustomContentDefinition content = gson.fromJson(payload, CustomContentDefinition.class);
            if (content == null || content.getGraph() == null) {
                return null;
            }
            FlowGraph graph = content.getGraph();
            graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : resourceId);
            return JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void requestResync(Workspace workspace, String reason) {
        String json = gson.toJson(new ResyncEvent(workspace.type, workspace.resourceId, reason));
        for (String member : List.copyOf(workspace.members)) {
            Session target = sessions.get(member);
            if (target != null) {
                sender.sendWorkspaceResync(target, json);
            }
        }
    }

    void apply(JsonObject document, WorkspacePatch<JsonElement> patch) {
        if (patch == null || patch.path() == null || !patch.path().startsWith("/")) {
            throw new IllegalArgumentException("Invalid workspace path");
        }
        List<String> path = segments(patch.path());
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Invalid workspace path");
        }
        JsonElement parent = parent(document, path);
        String leaf = path.getLast();
        switch (safe(patch.op())) {
            case "set" -> set(parent, leaf, patch.value());
            case "remove" -> remove(parent, leaf);
            case "array_add" -> addArrayValue(array(parent, leaf), patch.value());
            case "array_remove" -> removeArrayValue(array(parent, leaf), patch.value());
            default -> throw new IllegalArgumentException("Invalid workspace operation");
        }
    }

    private JsonElement parent(JsonObject document, List<String> path) {
        JsonElement current = document;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                JsonElement next = object.get(segment);
                if (next == null || next.isJsonNull()) {
                    next = new JsonObject();
                    object.add(segment, next);
                }
                current = next;
            } else if (current.isJsonArray()) {
                current = current.getAsJsonArray().get(Integer.parseInt(segment));
            } else {
                throw new IllegalArgumentException("Invalid workspace path");
            }
        }
        return current;
    }

    private void set(JsonElement parent, String leaf, JsonElement value) {
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(leaf, copy(value));
        } else if (parent.isJsonArray()) {
            parent.getAsJsonArray().set(Integer.parseInt(leaf), copy(value));
        } else {
            throw new IllegalArgumentException("Invalid workspace path");
        }
    }

    private void remove(JsonElement parent, String leaf) {
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().remove(leaf);
        } else if (parent.isJsonArray()) {
            parent.getAsJsonArray().remove(Integer.parseInt(leaf));
        }
    }

    private JsonArray array(JsonElement parent, String leaf) {
        JsonElement value = parent.isJsonObject() ? parent.getAsJsonObject().get(leaf) : parent.getAsJsonArray().get(Integer.parseInt(leaf));
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Invalid workspace array");
        }
        return value.getAsJsonArray();
    }

    private void addArrayValue(JsonArray array, JsonElement value) {
        JsonElement next = copy(value);
        for (JsonElement existing : array) {
            if (existing.equals(next)) {
                return;
            }
        }
        array.add(next);
    }

    private void removeArrayValue(JsonArray array, JsonElement value) {
        for (int index = array.size() - 1; index >= 0; index--) {
            if (array.get(index).equals(value)) {
                array.remove(index);
            }
        }
    }

    boolean validClientPatch(WorkspacePatch<JsonElement> patch) {
        if (patch == null || patch.op() == null || !patch.op().equals(safe(patch.op())) || !PATCH_OPERATIONS.contains(patch.op())
            || patch.path() == null || patch.path().length() > MAX_PATCH_PATH_LENGTH || !patch.path().startsWith("/")) {
            return false;
        }
        List<String> path = segments(patch.path());
        return !path.isEmpty() && !path.getFirst().isBlank() && !IMMUTABLE_ROOTS.contains(path.getFirst())
            && (!"remove".equals(patch.op()) || patch.value() == null || patch.value().isJsonNull())
            && ("remove".equals(patch.op()) || patch.value() != null);
    }

    private List<String> segments(String path) {
        String[] raw = path.substring(1).split("/", -1);
        ArrayList<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            segments.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return segments;
    }

    private JsonElement copy(JsonElement value) {
        return value != null ? value.deepCopy() : JsonNull.INSTANCE;
    }

    private WorkspacePatch<JsonElement> copy(WorkspacePatch<JsonElement> patch) {
        return new WorkspacePatch<>(patch.op(), patch.path(), copy(patch.value()));
    }

    private CollaborationIdentity identity(Session session) {
        return session.getCollaborationIdentity() != null ? session.getCollaborationIdentity() : CollaborationIdentity.client(session.getClientId());
    }

    private boolean valid(JoinRequest request) {
        return request != null && GRAPH_TYPES.contains(safe(request.type())) && validId(request.resourceId());
    }

    private boolean valid(OperationRequest request) {
        return request != null && GRAPH_TYPES.contains(safe(request.type())) && validId(request.resourceId())
            && request.baseSequence() >= 0L && validOperationId(request.operationId());
    }

    private boolean valid(AwarenessRequest request) {
        return request != null && GRAPH_TYPES.contains(safe(request.type())) && validId(request.resourceId());
    }

    private boolean validId(String value) {
        String id = safe(value);
        return !id.isBlank() && id.length() <= 160 && !id.contains("..") && id.indexOf('/') < 0 && id.indexOf('\\') < 0;
    }

    private boolean validOperationId(String value) {
        String id = safe(value);
        return !id.isBlank() && id.length() <= 128 && id.equals(value);
    }

    private boolean compatibleGraphType(String requestedType, FlowGraph graph) {
        if (ReSyncResourceCatalog.CUSTOM_CONTENT.equals(requestedType)) {
            return graph != null;
        }
        String type = safe(graph.getResourceType());
        boolean function = "function".equals(type) || graph.isFunction();
        if ("function".equals(requestedType)) {
            return function;
        }
        return !function;
    }

    private FlowGraph loadGraph(String type, String resourceId) {
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            return storage.getGraph(type, resourceId);
        }
        CustomContentDefinition content = customContentStorage != null ? customContentStorage.get(resourceId) : null;
        return content != null ? content.getGraph() : null;
    }

    private void persistGraph(String type, String resourceId, FlowGraph graph) {
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            storage.saveGraph(graph);
            return;
        }
        CustomContentDefinition content = customContentStorage != null ? customContentStorage.get(resourceId) : null;
        if (content == null) {
            throw new IllegalStateException("Custom content not found: " + resourceId);
        }
        graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : graph.getId());
        content.setGraph(graph);
        customContentStorage.save(content);
    }

    private String key(String type, String resourceId) {
        return safe(type) + '\u0000' + safe(resourceId);
    }

    private String safe(String value) {
        return value != null ? value.trim() : "";
    }

    private <T> T read(ByteBuffer buffer, Class<T> type) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record JoinRequest(String type, String resourceId) {
    }

    private record OperationRequest(String type, String resourceId, String operationId, long baseSequence,
                                    List<WorkspacePatch<JsonElement>> patches) {
    }

    private record AwarenessRequest(String type, String resourceId, JsonObject state) {
    }

    private record SnapshotEvent(String type, String resourceId, long sequence, JsonObject document, List<AwarenessEvent> awareness) {
    }

    private record OperationEvent(String type, String resourceId, long sequence, String operationId, String authorSessionId,
                                  CollaborationIdentity author, List<WorkspacePatch<JsonElement>> patches) {
    }

    private record AwarenessEvent(String type, String resourceId, String authorSessionId, CollaborationIdentity author, JsonObject state, long updatedAt) {
    }

    private record ResyncEvent(String type, String resourceId, String reason) {
    }

    private static final class Workspace {
        private final String type;
        private final String resourceId;
        private JsonObject document;
        private final Set<String> members = ConcurrentHashMap.newKeySet();
        private final Map<String, AwarenessEvent> awareness = new ConcurrentHashMap<>();
        private final List<WorkspacePatch<JsonElement>> pendingPatches = new ArrayList<>();
        private final WorkspaceRevision<OperationEvent> revision = new WorkspaceRevision<>();
        private long dirtyAt;
        private boolean deleted;

        private Workspace(String type, String resourceId, JsonObject document) {
            this.type = type;
            this.resourceId = resourceId;
            this.document = document;
        }
    }
}
